"""End-to-end TestPilot smoke test against the SkyClone dev server.

Drives a running TestPilot client ONLY through pilot.py (no OS input, no focus,
no desktop capture) and saves screenshots for a human/agent to look at.

Preconditions (see README):
  * the SkyClone server is running on localhost:25630 with online-mode=false
  * a client is up:   py -3 pilot.py launch --user PilotTest
  * PilotTest is op'd (RCON `op PilotTest`) so /sb mob spawn works

    py -3 smoke_test.py --out verify/smoke
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from pilot import Pilot, PilotError


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25620)
    ap.add_argument("--out", default="verify/smoke")
    ap.add_argument("--mob", default="hub_graveyard_zombie")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    p = Pilot(port=a.port)
    results: list[tuple[str, bool, str]] = []

    def step(name, fn):
        t = time.time()
        try:
            detail = fn()
            results.append((name, True, str(detail)))
            print(f"PASS {name} ({time.time() - t:.1f}s): {detail}")
        except Exception as e:  # noqa: BLE001 - report every failure and keep going
            results.append((name, False, str(e)))
            print(f"FAIL {name}: {e}")

    def shot(name):
        return p.screenshot(out / f"{name}.png")

    def not_focused():
        ping = p.wait_until_ready(60)
        if ping["osFocused"] or ping["focused"]:
            raise PilotError(f"client window is focused: {ping}")
        return f"{ping['player']} in world, window NOT focused"
    step("ping / not focused", not_focused)

    def read_state():
        st = p.state(radius=32, chat=10)
        (out / "state.json").write_text(json.dumps(st, indent=2, ensure_ascii=False), encoding="utf-8")
        pl = st["player"]
        return (f"{pl['name']} at {pl['x']:.1f},{pl['y']:.1f},{pl['z']:.1f} in {pl['dimension']}, "
                f"hp {pl['health']}, slot9={st['hotbar'][8].get('name')!r}, sidebar={len(st['sidebar']['lines'])} lines, "
                f"{len(st['entities'])} entities, actionBar={st['actionBar']['text']!r}")
    step("state", read_state)

    def menu():
        p.close()
        p.hotbar(8)
        p.use()
        s = p.wait_for_screen("SkyBlock Menu", 5)
        shot("01_menu")
        r = p.click_item_named("Your Skills")
        s2 = p.wait_for_screen("Skills", 5)
        shot("02_skills")
        p.close()
        p.wait_for_no_screen()
        return f"opened {s['title']!r} ({s['containerSlots']} slots), clicked slot {r['clicked']['slot']} -> {s2['title']!r}, closed"
    step("skyblock menu", menu)

    def warp():
        n = p.chat_count()
        p.chat("/warp hub")
        deadline = time.time() + 15
        while time.time() < deadline:
            st = p.state(radius=0, chat=0)
            if st["player"]["dimension"].endswith("hub"):
                time.sleep(1.5)
                return f"{st['player']['dimension']}, chat: {[l['text'] for l in p.chat_since(n)][:3]}"
            time.sleep(0.5)
        raise PilotError("not in the hub after 15s")
    step("/warp hub", warp)

    def walk():
        st = p.state(radius=0, chat=0)["player"]
        r = p.move(forward=1, seconds=1)
        x, y, z = r["pos"]["x"], r["pos"]["y"], r["pos"]["z"]
        w = p.walk_to(x + 4, y, z + 2, timeout=10)
        shot("03_walked")
        return f"move 1s: {r['moved']:.1f} blocks; walkto arrived={w['arrived']} dist={w['distance']:.2f} moved={w['moved']:.1f}"
    step("walk", walk)

    def kill():
        n = p.chat_count()
        p.chat(f"/sb mob spawn {a.mob} 1")
        time.sleep(1.5)
        p.move(forward=-1, seconds=0.5)
        z = p.nearest("Zombie")
        if z is None:
            raise PilotError(f"no zombie after spawn; chat: {[l['text'] for l in p.chat_since(n)]}")
        p.hotbar(1)
        p.look(entity=z["id"])
        p.attack(entity=z["id"])
        p.wait(3)
        shot("04_hit")
        r = p.kill_nearest("Zombie", timeout=60)
        p.wait(10)
        shot("05_killed")
        return f"{z['nameplate']!r} -> {r['result']} after {r['hits']} hits; chat: {[l['text'] for l in p.chat_since(n)][-3:]}"
    step("kill mob", kill)

    ok = all(r[1] for r in results)
    (out / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print("\nSMOKE", "OK" if ok else "FAILED", f"{sum(r[1] for r in results)}/{len(results)}; screenshots in {out}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
