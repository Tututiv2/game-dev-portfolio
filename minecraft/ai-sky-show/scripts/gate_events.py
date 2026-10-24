"""BUILD GATE for ShowControl: trigger >= 10 kinds of server events over RCON and prove every one
arrives on the SSE feed (CONTRACT build gate).

Needs: the show server running (scripts/start_server.ps1 -Detached -WaitReady) with ShowControl, and
ONE cast account online (a TestPilot client, or any client joined as Kaito/Ren/Mochi/Yuki). RCON
cannot fake a player, and almost every event needs one.

Every trigger drives a REAL SkyClone code path (no synthetic /show emit events are counted):
    show mark / start / stop                 -> mark, episode_start, episode_end
    sb coins <p> give 12000                  -> coins_milestone (+ first_of_kind 10k)
    sb give <p> ASPECT_OF_THE_END 1          -> item_gain (RARE)
    sb skill <p> combat addxp 600            -> skill_level_up (+ first_of_kind)
    sb collection <p> WHEAT add 200          -> collection_tier_up (when the collections module fires it)
    sb warpto <island> <p>                   -> island_change, zone_change (+ first_of_kind first to reach)
    show test kill <p>                       -> mob_kill (a real hit through SkyClone's combat pipeline)
    show test boss <p>                       -> boss_spawn
    show test hit <p> 0.9                    -> big_hit, then near_death (vitals poll)
    show test die <p>                        -> death (+ first_of_kind first death)
    sb calendar force spiders_den_rain on/off-> calendar_start, calendar_end
    --chat: TestPilot pilot.py say           -> chat   (optional; needs the client's TestPilot port)

Usage:
    py -3 scripts/gate_events.py                       # first online cast member
    py -3 scripts/gate_events.py --player Kaito --chat --pilot-port 25650
Exit 0 = PASS (>= --need distinct kinds arrived on SSE), 1 = FAIL, 2 = not runnable (no server/player).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rcon import Rcon, RconError, show_config  # noqa: E402

CFG = show_config()
HTTP = f"http://127.0.0.1:{CFG.get('ports', {}).get('showcontrol', 25642)}"


def get(path: str, timeout: float = 5.0):
    with urllib.request.urlopen(HTTP + path, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


class SseCollector(threading.Thread):
    """Reads /events from 'now' and keeps every event it sees (this is what the gate certifies)."""

    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.events: list[dict] = []
        self.ready = threading.Event()
        self.stop_flag = threading.Event()
        self.error: str | None = None

    def run(self) -> None:
        try:
            req = urllib.request.Request(HTTP + "/events", headers={"Accept": "text/event-stream"})
            with urllib.request.urlopen(req, timeout=30) as r:
                self.ready.set()
                data: list[str] = []
                for raw in r:
                    if self.stop_flag.is_set():
                        return
                    line = raw.decode("utf-8", "replace").rstrip("\r\n")
                    if line.startswith("data:"):
                        data.append(line[5:].lstrip())
                    elif line == "" and data:
                        try:
                            self.events.append(json.loads("\n".join(data)))
                        except ValueError:
                            pass
                        data = []
        except Exception as e:  # noqa: BLE001 - report whatever broke the stream
            self.error = repr(e)
            self.ready.set()


def pick_player(want: str | None) -> str | None:
    rows = get("/players")
    online = [r for r in rows if r.get("online") and not r.get("camera")]
    if want:
        return want if any(r["name"].lower() == want.lower() for r in online) else None
    cast = [r for r in online if r.get("cast")]
    return (cast or online or [{}])[0].get("name")


def pilot_say(port: int, text: str) -> str:
    """Optional: make the client type in chat through TestPilot (never OS input)."""
    tp = CFG.get("paths", {}).get("testpilot")
    if tp and tp not in sys.path:
        sys.path.insert(0, tp)      # import only - TestPilot is read-only for this rig
    try:
        import pilot  # type: ignore
        return str(pilot.Pilot(port=port, timeout=15).chat(text))[:200]
    except Exception as e:  # noqa: BLE001 - optional step: report, never fail the gate on it
        return f"could not reach TestPilot on :{port} ({e})"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="ShowControl build gate")
    ap.add_argument("--player")
    ap.add_argument("--need", type=int, default=10, help="distinct event kinds required")
    ap.add_argument("--chat", action="store_true", help="also send a chat line through TestPilot")
    ap.add_argument("--pilot-port", type=int, default=25650)
    ap.add_argument("--settle", type=float, default=4.0, help="seconds to wait for stragglers")
    a = ap.parse_args(argv)

    try:
        h = get("/health")
    except Exception as e:  # noqa: BLE001
        print(f"NOT RUNNABLE: ShowControl /health did not answer at {HTTP} ({e})")
        return 2
    print(f"ShowControl up: tps={h.get('tps')} last_event_id={h.get('last_event_id')}")

    player = pick_player(a.player)
    if not player:
        print("NOT RUNNABLE: no cast player online. Join one client (TestPilot) as a cast account, then re-run.")
        return 2
    print(f"Driving events with player: {player}")

    sse = SseCollector()
    sse.start()
    sse.ready.wait(10)
    if sse.error:
        print(f"FAIL: could not open the SSE feed: {sse.error}")
        return 1
    since = get("/health").get("last_event_id", 0)

    state = get(f"/state/{player}")
    here = state.get("island")
    warps = state.get("warps_unlocked") or []
    target = "hub" if here != "hub" else next((w for w in ("private_island", "home") if w in warps), "private_island")

    steps = [
        ("mark", "show mark gate-begin"),
        ("episode_start", "show start 2"),
        ("coins_milestone", f"sb coins {player} give 12000"),
        ("item_gain", f"sb give {player} ASPECT_OF_THE_END 1"),
        ("skill_level_up", f"sb skill {player} combat addxp 600"),
        ("collection_tier_up", f"sb collection {player} WHEAT add 200"),
        ("island_change", f"sb warpto {target} {player}"),
        ("mob_kill", f"show test kill {player}"),
        ("boss_spawn", f"show test boss {player}"),
        ("big_hit", f"show test hit {player} 0.9"),
        ("death", f"show test die {player}"),
        ("calendar_start", "sb calendar force spiders_den_rain on"),
        ("calendar_end", "sb calendar force spiders_den_rain off"),
        ("calendar_clear", "sb calendar force spiders_den_rain clear"),
        ("episode_end", "show stop"),
    ]
    try:
        with Rcon() as r:
            for want, cmd in steps:
                out = r.command(cmd, strip=True).strip().replace("\n", " | ")
                print(f"  > {cmd:48s} {out[:110]}")
                # near_death is polled every snapshot (0.5 s): give it a beat after the big hit,
                # and give the warp time to land before the next step moves the player.
                time.sleep(2.5 if want in ("big_hit", "island_change") else 0.6)
    except RconError as e:
        print(f"NOT RUNNABLE: RCON failed: {e}")
        return 2

    if a.chat:
        print(f"  > TestPilot say on :{a.pilot_port}: {pilot_say(a.pilot_port, 'gate check, ignore me')}")

    time.sleep(a.settle)
    sse.stop_flag.set()

    seen_sse = [e for e in sse.events if not (e.get("data") or {}).get("synthetic")]
    polled = [e for e in get(f"/events/since?id={since}") if not (e.get("data") or {}).get("synthetic")]
    kinds_sse: dict[str, int] = {}
    for e in seen_sse:
        kinds_sse[e["type"]] = kinds_sse.get(e["type"], 0) + 1
    kinds_poll = {e["type"] for e in polled}

    print("\nEvent kinds that arrived on the SSE feed:")
    for k in sorted(kinds_sse):
        sample = next(e for e in seen_sse if e["type"] == k)
        print(f"  {k:20s} x{kinds_sse[k]:<3d} drama {sample.get('drama')}  {sample.get('text', '')[:80]}")
    missing = sorted(kinds_poll - set(kinds_sse))
    if missing:
        print(f"  (in /events/since but NOT seen on SSE: {', '.join(missing)})")
    expected = {w for w, _ in steps if w != "calendar_clear"} | {"near_death", "first_of_kind", "zone_change"}
    absent = sorted(expected - set(kinds_sse))
    if absent:
        print(f"  not seen (informational): {', '.join(absent)}")
    ok = len(kinds_sse) >= a.need and not missing
    print(f"\n{'PASS' if ok else 'FAIL'}: {len(kinds_sse)} distinct kinds on SSE (need {a.need}); "
          f"{len(seen_sse)} events; /events/since agreed on {len(kinds_poll)} kinds")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
