"""TestPilot client -- drive a real Minecraft client from inside the game.

Stdlib only.  Talks to the TestPilot Fabric mod's HTTP server on 127.0.0.1.
Nothing in here touches the OS keyboard, mouse, window focus or the screen:
every action is a JSON request that the mod executes on the client thread.

Library::

    from pilot import Pilot
    p = Pilot(port=25620)
    p.state()
    p.chat("/warp hub")
    p.kill_nearest("Zombie")
    p.screenshot("out.png")

CLI::

    py -3 pilot.py launch --user PilotTest            # start a test client (see README)
    py -3 pilot.py state
    py -3 pilot.py chat "/warp hub"
    py -3 pilot.py screenshot out.png
    py -3 pilot.py kill --user PilotTest              # close that client only
    py -3 pilot.py call look '{"nearest": "Zombie"}'  # any endpoint, raw JSON

Port: ``--port`` / ``Pilot(port=...)``, default from env TESTPILOT_PORT, else 25620.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_PORT = int(os.environ.get("TESTPILOT_PORT", "25620"))
HERE = Path(__file__).resolve().parent
LAUNCHER_DIR = HERE.parents[2] / "MinecraftLauncher"   # .../Ai Game/MinecraftLauncher
INSTANCE = "Fabric-26.1.2"


class PilotError(RuntimeError):
    pass


class Pilot:
    def __init__(self, port: int = DEFAULT_PORT, host: str = "127.0.0.1", timeout: float = 120.0):
        self.base = f"http://{host}:{port}"
        self.timeout = timeout

    # ------------------------------------------------------------------ transport
    def call(self, endpoint: str, body: dict | None = None, timeout: float | None = None) -> dict:
        """POST ``body`` (JSON) to ``/endpoint``; return the decoded JSON reply.

        Raises PilotError if the mod answers ``{"ok": false}``.
        """
        url = self.base + "/" + endpoint.lstrip("/")
        data = json.dumps(body or {}).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                out = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            try:
                out = json.loads(e.read().decode("utf-8"))
            except Exception:
                raise PilotError(f"{endpoint}: HTTP {e.code}") from None
        except urllib.error.URLError as e:
            raise PilotError(f"{endpoint}: cannot reach TestPilot at {self.base} ({e.reason})") from None
        if not out.get("ok", False):
            raise PilotError(f"{endpoint}: {out.get('error', out)}")
        return out

    # ------------------------------------------------------------------ endpoints
    def ping(self) -> dict:
        return self.call("ping", timeout=5)

    def state(self, **opts) -> dict:
        """Full snapshot.  opts: radius (default 32), chat (lines, default 100)."""
        return self.call("state", opts)

    def chat(self, text: str) -> dict:
        """Send chat, or a command if ``text`` starts with '/'."""
        return self.call("chat", {"text": text})

    def look(self, yaw: float | None = None, pitch: float | None = None, *, x=None, y=None, z=None,
             entity: int | None = None, nearest: str | None = None) -> dict:
        body: dict = {}
        if entity is not None:
            body["entity"] = entity
        elif nearest is not None:
            body["nearest"] = nearest
        elif x is not None:
            body.update(x=x, y=y, z=z)
        else:
            body.update(yaw=yaw, pitch=pitch)
        return self.call("look", body)

    def attack(self, entity: int | None = None, nearest: str | None = None) -> dict:
        body: dict = {}
        if entity is not None:
            body["entity"] = entity
        if nearest is not None:
            body["nearest"] = nearest
        return self.call("attack", body)

    def attack_hold(self, seconds: float, x=None, y=None, z=None) -> dict:
        body: dict = {"seconds": seconds}
        if x is not None:
            body.update(x=x, y=y, z=z)
        return self.call("attackhold", body, timeout=seconds + 30)

    def use(self, target: str = "auto", entity: int | None = None, hand: str = "main") -> dict:
        body: dict = {"target": target, "hand": hand}
        if entity is not None:
            body["entity"] = entity
        return self.call("use", body)

    def use_hold(self, seconds: float = 1.2, x=None, y=None, z=None, lift: float = 0.0) -> dict:
        """Hold right click for ``seconds`` then let go (a real bow draw and loose).

        With x,y,z the crosshair stays on that exact point (+``lift`` blocks up, for arrow drop)
        for the whole draw.  Needs the jar built 2026-10-04 or later (endpoint ``usehold``).
        """
        body: dict = {"seconds": seconds}
        if x is not None:
            body.update(x=x, y=y, z=z, lift=lift)
        return self.call("usehold", body, timeout=seconds + 30)

    def hotbar(self, slot: int) -> dict:
        """Select hotbar slot 0-8 (slot 8 is the key labelled 9)."""
        return self.call("hotbar", {"slot": slot})

    def drop(self, all: bool = False) -> dict:
        return self.call("drop", {"all": all})

    def swap_offhand(self) -> dict:
        return self.call("swapoffhand")

    def click(self, slot: int, button: int = 0, action: str = "PICKUP") -> dict:
        """Container click; slot numbered the way the OPEN menu numbers it."""
        return self.call("click", {"slot": slot, "button": button, "action": action})

    def close(self) -> dict:
        return self.call("close")

    def open_inventory(self) -> dict:
        return self.call("openinv")

    def move(self, forward: float = 0, strafe: float = 0, jump: bool = False, sneak: bool = False,
             sprint: bool = False, seconds: float = 1.0, edge_guard: bool = True,
             fly_toggle: bool = False) -> dict:
        """Hold movement keys for ``seconds``. The same edge guard as ``walk_to`` tones the keys down
        (no sprint, no jump, let go, brake) whenever they would carry the player over a drop deeper
        than 3.5 blocks: ``edge`` true / ``guardTicks`` > 0 in the reply when it did.
        ``edge_guard=False`` only for a test that means to walk off something.
        While the player may fly (Rat "Turbo Tail"), a jump pressed again within vanilla's double-tap
        window would toggle flight, so it is held back a few ticks (``jumpsHeldBack``) unless
        ``fly_toggle=True``."""
        return self.call("move", dict(forward=forward, strafe=strafe, jump=jump, sneak=sneak,
                                      sprint=sprint, seconds=seconds, edgeGuard=edge_guard,
                                      flyToggle=fly_toggle), timeout=seconds + 30)

    def walk_to(self, x: float, y: float, z: float, timeout: float = 20, tolerance: float = 0.6,
                sprint: bool = False, path: bool = True, edge_guard: bool = True,
                dry: bool = False) -> dict:
        """Walk there like a player: plans round walls, jumps real rises (a 15/16 dirt_path too),
        opens wooden doors / fence gates on the way (both leaves of a double door) BARE-HANDED -
        an empty hotbar slot, or the held item parked in the inventory for the click and put back,
        so a held item's right-click ability never fires (``doorsSkipped`` counts doors routed
        round because every slot held an ability item) - and climbs ladders up and down.
        ``path=False`` = the old straight-line steering.

        Never walks off an edge: before every tick the keys are played forward through the player's
        own movement physics (momentum, the 5 airborne ticks of a step down, sprint-jump kicks) and
        toned down - no sprint, no jump, let go, brake - unless braking right after them still ends
        standing on something; it never sprints within 1.5 blocks of a drop. A target past an edge
        ends with ``edge`` true and ``stoppedBy`` "blocked: edge - ..." at the edge (``noRoute`` is
        also true when no walkable way gets closer). ``edge_guard=False`` only for a test that means
        to walk off something. Ladders and vines are climbed both ways (down: it lines the body up
        over the hole until it drops onto the ladder). With a ``y``, arrival also needs the same
        floor (within 3 blocks): a point straight below on the storey under this one is not "here".

        Stops early (``arrived`` false, reason in ``stoppedBy``) when the player changes world or is
        teleported - a portal, a /warp, a lift (``worldChanged`` true for a new world): the target's
        coordinates mean nothing past that, so walk again from the new place. ``noRoute`` true =
        nothing walkable gets closer to the target (up a wall, inside a block, behind a locked door);
        the player stands at the closest point it found.

        Breath: with the eyes under water and the air bar under 60% the walk surfaces first (holds
        jump; under a dock or a roof it swims to the nearest open water), and the planner never routes
        through water with no air above it. ``dry=True`` never steps INTO water at all (``water`` true
        and ``stoppedBy`` "blocked: water - ..." when the target is in it) - a fight from a dock. The
        reply has ``surfaced``, ``underWaterTicks``, ``air`` / ``maxAir`` (300 = 15 s)."""
        return self.call("walkto", dict(x=x, y=y, z=z, timeout=timeout, tolerance=tolerance,
                                        sprint=sprint, path=path, edgeGuard=edge_guard, dry=dry),
                         timeout=timeout + 30)

    def clearance(self, directions: int = 16, max: float = 16) -> dict:
        """Walkable distance (no jumping) from where the player stands in ``directions`` yaws.
        ``bestYaw`` / ``bestClear`` = the most open one: point a ridden pet or a sprint there."""
        return self.call("clearance", {"directions": directions, "max": max})

    def stop(self) -> dict:
        """Release every key TestPilot is holding."""
        return self.call("stop")

    def screenshot(self, path: str | os.PathLike) -> str:
        p = str(Path(path).resolve())
        return self.call("screenshot", {"path": p}, timeout=60)["path"]

    def wait(self, ticks: int) -> dict:
        return self.call("wait", {"ticks": ticks}, timeout=ticks / 20 + 30)

    # ------------------------------------------------------------------ helpers
    LOADING_SCREENS = ("LevelLoadingScreen", "ReceivingLevelScreen", "ConnectScreen", "ProgressScreen",
                       "GenericMessageScreen")

    def wait_until_ready(self, timeout: float = 180) -> dict:
        """Block until the mod answers, the player is in a world and no loading screen is up."""
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            try:
                last = self.ping()
                if last.get("inWorld") and last.get("screenType") not in self.LOADING_SCREENS:
                    return last
            except PilotError as e:
                last = str(e)
            time.sleep(1)
        raise PilotError(f"client not in world after {timeout}s (last: {last})")

    def screen(self) -> dict | None:
        return self.state(radius=0, chat=0).get("screen")

    def wait_for_screen(self, title_substring: str | None = None, timeout: float = 10) -> dict:
        """Wait for a screen whose title contains ``title_substring`` (case-insensitive).

        ``None`` waits for any screen.  Returns the screen dict.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            s = self.screen()
            if s and (title_substring is None or title_substring.lower() in (s.get("title") or "").lower()):
                return s
            time.sleep(0.1)
        raise PilotError(f"no screen titled {title_substring!r} within {timeout}s (now: {self.screen()})")

    def wait_for_no_screen(self, timeout: float = 5) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.screen():
                return
            time.sleep(0.1)
        raise PilotError("screen still open")

    def find_slot(self, name_substring: str, container_only: bool = True) -> dict | None:
        """First slot in the open screen whose item name contains the substring."""
        s = self.screen()
        if not s:
            raise PilotError("no screen open")
        needle = name_substring.lower()
        for sl in s.get("slots", []):
            if container_only and not sl.get("container", True):
                continue
            if needle in (sl.get("name") or "").lower():
                return sl
        return None

    def click_item_named(self, name_substring: str, button: int = 0, action: str = "PICKUP",
                         container_only: bool = True) -> dict:
        """Click the first slot whose item name contains ``name_substring``."""
        sl = self.find_slot(name_substring, container_only)
        if sl is None:
            names = [x.get("name") for x in (self.screen() or {}).get("slots", []) if x.get("name")]
            raise PilotError(f"no item named like {name_substring!r}; screen has {names}")
        r = self.click(sl["slot"], button, action)
        r["clicked"] = sl
        return r

    def sidebar(self) -> list[str]:
        return (self.state(radius=0, chat=0).get("sidebar") or {}).get("lines", [])

    def chat_count(self) -> int:
        return self.ping().get("chatCount", 0)

    def chat_since(self, n: int) -> list[str]:
        """Chat lines received after the client had seen ``n`` lines in total.

        Use ``n = p.chat_count()`` before an action, ``p.chat_since(n)`` after.
        """
        st = self.call("chatsince", {"since": n})
        return st.get("lines", [])

    def entities(self, radius: float = 32) -> list[dict]:
        return self.state(radius=radius, chat=0).get("entities", [])

    LABEL_TYPES = ("minecraft:text_display", "minecraft:armor_stand", "minecraft:item_display",
                   "minecraft:block_display", "minecraft:marker")

    def nearest(self, name: str, radius: float = 32, include_labels: bool = False) -> dict | None:
        """Nearest LIVING entity whose type/name/custom name/nameplate contains ``name``.

        Floating label entities (text displays, armour stands, markers) are skipped
        unless ``include_labels`` - otherwise "Zombie" would find the label over
        the zombie rather than the zombie.
        """
        needle = name.lower()
        best = None
        for e in self.entities(radius):
            if not e.get("alive", True):
                continue
            if not include_labels and e.get("type") in self.LABEL_TYPES:
                continue
            hay = " ".join(str(e.get(k) or "") for k in ("name", "type", "customName", "nameplate")).lower()
            if needle in hay and (best is None or e["distance"] < best["distance"]):
                best = e
        return best

    def hold(self, needle: str, slot: int | None = None) -> int:
        """Put the first item whose id or name contains ``needle`` in the main hand; returns the
        hotbar slot it is in.

        Already in the hotbar: just select it. Only in the main inventory (``/give`` with a full
        hotbar puts it there): swap it into the hotbar the way a player does - open E, hover it,
        press the number key (a SWAP click) - into ``slot``, else the first empty slot 1-8, else
        the selected slot. Slot 9 (index 8, the SkyBlock Menu) is never used.
        """
        st = self.state(radius=0, chat=0)
        n = needle.lower()

        def match(it):
            return bool(it) and not it.get("empty") and n in f"{it.get('id', '')} {it.get('name', '')}".lower()

        hot = st.get("hotbar") or []
        for i, it in enumerate(hot):
            if match(it):
                self.hotbar(i)
                return i
        src = next((it for it in st.get("inventory") or [] if it.get("slot", 0) >= 9 and match(it)), None)
        if src is None:
            raise PilotError(f"no item like {needle!r} in the hotbar or inventory")
        if slot is None:
            empty = [i for i in range(8) if i < len(hot) and (not hot[i] or hot[i].get("empty"))]
            sel = (st.get("player") or {}).get("selectedSlot", 0)
            slot = empty[0] if empty else (sel if sel != 8 else 0)
        if not 0 <= slot <= 7:
            raise PilotError("hold() swaps into hotbar slots 0-7 only (8 is the SkyBlock Menu)")
        self.open_inventory()
        scr = self.wait_for_screen(None, timeout=5)
        menu_slot = next((sl["slot"] for sl in scr.get("slots", [])
                          if not sl.get("container") and sl.get("containerSlot") == src["slot"]), None)
        try:
            if menu_slot is None:
                raise PilotError(f"inventory slot {src['slot']} not in the open menu")
            self.click(menu_slot, slot, "SWAP")
            self.wait(4)
        finally:
            self.close()
            self.wait(2)
        self.hotbar(slot)
        self.wait(2)
        now = (self.state(radius=0, chat=0).get("hotbar") or [None] * 9)[slot]
        if not match(now):
            raise PilotError(f"swap into hotbar slot {slot} did not take (slot holds {now})")
        return slot

    def find_blocks(self, block: str, radius: int = 8, limit: int = 20, exposed: bool = False) -> list[dict]:
        """Loaded blocks whose id contains ``block`` (e.g. "coal_ore"), nearest first."""
        return self.call("findblocks", {"block": block, "radius": radius, "limit": limit,
                                        "exposed": exposed}).get("blocks", [])

    def kill_nearest(self, name: str, timeout: float = 30, reach: float = 3.0,
                     approach: bool = True, stay_dry: bool = True) -> dict:
        """Look at + attack the nearest entity matching ``name`` until it is dead.

        Walks toward it when it is out of reach (``approach``) and waits out the
        attack cooldown the mod reports after each swing.  Returns
        ``result: "dead"`` (seen with alive=false / 0 health) or ``"vanished"``
        (no longer loaded - despawned, or removed instantly on death).

        ``stay_dry`` (default): the approach never steps into water - a sea
        creature is fought from the dock or the shore, and one that stays out of
        reach in the water is waited for there. Wave 5: chasing a Trash Gobbler
        under the Bayou dock drowned the player (half the purse). The walk's breath
        guard also surfaces a player who is already under water.
        """
        target = self.nearest(name)
        if target is None:
            raise PilotError(f"no living entity like {name!r} within 32 blocks")
        eid = target["id"]
        hits = 0
        waits = 0
        deadline = time.time() + timeout
        hold_until = 0.0
        while time.time() < deadline:
            ents = {e["id"]: e for e in self.entities(48)}
            e = ents.get(eid)
            if e is None:
                return {"ok": True, "killed": True, "result": "vanished", "entity": target, "hits": hits}
            if not e.get("alive", True) or (e.get("health") is not None and e["health"] <= 0):
                return {"ok": True, "killed": True, "result": "dead", "entity": e, "hits": hits}
            if e["distance"] > reach:
                if approach and time.time() >= hold_until:
                    w = self.walk_to(e["x"], e["y"], e["z"], timeout=3, tolerance=reach - 0.6, dry=stay_dry)
                    if w.get("water") or w.get("noRoute") or w.get("edge"):
                        # as close as dry ground goes: let it come to us instead of re-walking on the spot
                        waits += 1
                        hold_until = time.time() + 1.5
                else:
                    time.sleep(0.2)
                continue
            r = self.attack(entity=eid)
            if r.get("attacked"):
                hits += 1
            self.wait(max(2, int(r.get("cooldownTicks", 10))))
        why = ""
        if waits and stay_dry:
            why = (" - it stayed out of reach in the water and stay_dry kept the player on dry ground"
                   " (stay_dry=False to swim after it)")
        raise PilotError(f"{name!r} (id {eid}) still alive after {timeout}s, {hits} hits{why}")

    def mine_block_at(self, x: int, y: int, z: int, max_seconds: float = 10) -> dict:
        """Aim at a visible point of block (x,y,z) and hold attack until it breaks.

        Returns ``broken`` True/False; raises PilotError when no point of the
        block is in line of sight within reach (the error says what is in the way).
        """
        return self.attack_hold(max_seconds, x=x, y=y, z=z)

    def mine_nearest(self, block: str, max_seconds: float = 10, radius: int = 6) -> dict:
        """Mine the nearest reachable, visible block whose id contains ``block``."""
        tried = []
        for b in self.find_blocks(block, radius=radius, limit=12, exposed=True):
            if b["distance"] > 4.5:
                break
            try:
                r = self.mine_block_at(b["x"], b["y"], b["z"], max_seconds)
            except PilotError as e:
                tried.append(f"{b['x']},{b['y']},{b['z']}: {e}")
                continue
            r["block"] = b
            return r
        raise PilotError(f"no visible {block!r} within reach; tried {tried}")


# ---------------------------------------------------------------------- launch / kill
def launch_client(user: str, port: int, server: str = "localhost:25630", wait: bool = True) -> dict:
    """Start the Fabric-26.1.2 instance with TestPilot enabled.

    The mod is INERT unless TESTPILOT=1 is in the game's environment, so the
    owner's own sessions on this instance are untouched.  The launcher passes
    its environment to the game unchanged.
    """
    env = dict(os.environ)
    env["TESTPILOT"] = "1"
    env["TESTPILOT_PORT"] = str(port)
    cmd = ["py", "-3", "-m", "launcher", "launch", INSTANCE, "--dev-user", user, "--detach"]
    if server:
        cmd += ["--server", server]
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    r = subprocess.run(cmd, cwd=str(LAUNCHER_DIR), env=env, capture_output=True, text=True,
                       creationflags=flags, timeout=600)
    if r.returncode != 0:
        raise PilotError(f"launcher failed ({r.returncode}):\n{r.stdout[-2000:]}\n{r.stderr[-2000:]}")
    out = {"ok": True, "launcher": r.stdout.strip().splitlines()[-1:]}
    if wait:
        out["ping"] = Pilot(port=port).wait_until_ready()
    return out


def kill_client(user: str) -> list[int]:
    """Kill ONLY java processes whose command line contains ``--username <user>``."""
    marker = f"--username {user}"
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
          "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }")
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True,
                         text=True, creationflags=flags).stdout
    killed = []
    for line in out.splitlines():
        pid, _, cmdline = line.partition("\t")
        if pid.strip().isdigit() and (marker + " ") in (cmdline + " "):
            subprocess.run(["taskkill", "/PID", pid.strip(), "/F"], capture_output=True, creationflags=flags)
            killed.append(int(pid))
    return killed


# ---------------------------------------------------------------------- CLI
def _num(s: str):
    try:
        return int(s)
    except ValueError:
        return float(s)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="pilot.py", description="Drive a TestPilot Minecraft client.")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("launch", help="start a test client with TestPilot enabled")
    s.add_argument("--user", required=True)
    s.add_argument("--server", default="localhost:25630")
    s.add_argument("--no-wait", action="store_true")
    s = sub.add_parser("kill", help="kill the client whose command line has --username USER")
    s.add_argument("--user", required=True)

    sub.add_parser("ping")
    s = sub.add_parser("state"); s.add_argument("--radius", type=float, default=32); s.add_argument("--chat", type=int, default=100)
    sub.add_parser("sidebar")
    sub.add_parser("screen")
    s = sub.add_parser("chat"); s.add_argument("text")
    s = sub.add_parser("chatsince"); s.add_argument("n", type=int)
    s = sub.add_parser("look"); s.add_argument("args", nargs="+", help="yaw pitch | x y z | entity:<id> | nearest:<name>")
    s = sub.add_parser("attack"); s.add_argument("target", nargs="?", help="<entity id> | nearest:<name>")
    s = sub.add_parser("attackhold"); s.add_argument("seconds", type=float); s.add_argument("xyz", nargs="*", type=int)
    s = sub.add_parser("use"); s.add_argument("target", nargs="?", default="auto", choices=["auto", "air", "block", "entity"])
    s = sub.add_parser("hotbar"); s.add_argument("slot", type=int, help="0-8")
    s = sub.add_parser("drop"); s.add_argument("--all", action="store_true")
    sub.add_parser("swapoffhand")
    s = sub.add_parser("click"); s.add_argument("slot", type=int); s.add_argument("button", type=int, nargs="?", default=0); s.add_argument("action", nargs="?", default="PICKUP")
    s = sub.add_parser("clickname"); s.add_argument("name"); s.add_argument("--button", type=int, default=0)
    sub.add_parser("close")
    sub.add_parser("openinv")
    s = sub.add_parser("move"); s.add_argument("--forward", type=float, default=0); s.add_argument("--strafe", type=float, default=0)
    s.add_argument("--jump", action="store_true"); s.add_argument("--sneak", action="store_true"); s.add_argument("--sprint", action="store_true")
    s.add_argument("--seconds", type=float, default=1)
    s = sub.add_parser("walkto"); s.add_argument("x", type=float); s.add_argument("y", type=float); s.add_argument("z", type=float); s.add_argument("--timeout", type=float, default=20); s.add_argument("--straight", action="store_true", help="no path planning"); s.add_argument("--dry", action="store_true", help="never step into water")
    s = sub.add_parser("clearance"); s.add_argument("--directions", type=int, default=16); s.add_argument("--max", type=float, default=16)
    s = sub.add_parser("hold"); s.add_argument("item", help="id or name substring"); s.add_argument("--slot", type=int)
    sub.add_parser("stop")
    s = sub.add_parser("screenshot"); s.add_argument("path")
    s = sub.add_parser("wait"); s.add_argument("ticks", type=int)
    s = sub.add_parser("waitscreen"); s.add_argument("title", nargs="?"); s.add_argument("--timeout", type=float, default=10)
    s = sub.add_parser("killmob"); s.add_argument("name"); s.add_argument("--timeout", type=float, default=30)
    s = sub.add_parser("mine"); s.add_argument("x", type=int); s.add_argument("y", type=int); s.add_argument("z", type=int)
    s = sub.add_parser("findblocks"); s.add_argument("block"); s.add_argument("--radius", type=int, default=8); s.add_argument("--limit", type=int, default=20); s.add_argument("--exposed", action="store_true")
    s = sub.add_parser("nearest"); s.add_argument("name"); s.add_argument("--radius", type=float, default=32)
    s = sub.add_parser("call", help="raw endpoint call"); s.add_argument("endpoint"); s.add_argument("json", nargs="?", default="{}")

    a = ap.parse_args(argv)
    p = Pilot(port=a.port)
    try:
        if a.cmd == "launch":
            r = launch_client(a.user, a.port, a.server, wait=not a.no_wait)
        elif a.cmd == "kill":
            r = {"killed": kill_client(a.user)}
        elif a.cmd == "ping":
            r = p.ping()
        elif a.cmd == "state":
            r = p.state(radius=a.radius, chat=a.chat)
        elif a.cmd == "sidebar":
            r = p.sidebar()
        elif a.cmd == "screen":
            r = p.screen()
        elif a.cmd == "chat":
            r = p.chat(a.text)
        elif a.cmd == "chatsince":
            r = p.chat_since(a.n)
        elif a.cmd == "look":
            x = a.args
            if len(x) == 1 and x[0].startswith("entity:"):
                r = p.look(entity=int(x[0][7:]))
            elif len(x) == 1 and x[0].startswith("nearest:"):
                r = p.look(nearest=x[0][8:])
            elif len(x) == 3:
                r = p.look(x=_num(x[0]), y=_num(x[1]), z=_num(x[2]))
            else:
                r = p.look(float(x[0]), float(x[1]))
        elif a.cmd == "attack":
            t = a.target
            if t is None:
                r = p.attack()
            elif t.startswith("nearest:"):
                r = p.attack(nearest=t[8:])
            else:
                r = p.attack(entity=int(t))
        elif a.cmd == "attackhold":
            r = p.attack_hold(a.seconds, *a.xyz) if a.xyz else p.attack_hold(a.seconds)
        elif a.cmd == "use":
            r = p.use(a.target)
        elif a.cmd == "hotbar":
            r = p.hotbar(a.slot)
        elif a.cmd == "drop":
            r = p.drop(a.all)
        elif a.cmd == "swapoffhand":
            r = p.swap_offhand()
        elif a.cmd == "click":
            r = p.click(a.slot, a.button, a.action)
        elif a.cmd == "clickname":
            r = p.click_item_named(a.name, a.button)
        elif a.cmd == "close":
            r = p.close()
        elif a.cmd == "openinv":
            r = p.open_inventory()
        elif a.cmd == "move":
            r = p.move(a.forward, a.strafe, a.jump, a.sneak, a.sprint, a.seconds)
        elif a.cmd == "walkto":
            r = p.walk_to(a.x, a.y, a.z, timeout=a.timeout, path=not a.straight, dry=a.dry)
        elif a.cmd == "clearance":
            r = p.clearance(a.directions, a.max)
        elif a.cmd == "hold":
            r = {"ok": True, "slot": p.hold(a.item, a.slot)}
        elif a.cmd == "stop":
            r = p.stop()
        elif a.cmd == "screenshot":
            r = p.screenshot(a.path)
        elif a.cmd == "wait":
            r = p.wait(a.ticks)
        elif a.cmd == "waitscreen":
            r = p.wait_for_screen(a.title, a.timeout)
        elif a.cmd == "killmob":
            r = p.kill_nearest(a.name, a.timeout)
        elif a.cmd == "mine":
            r = p.mine_block_at(a.x, a.y, a.z)
        elif a.cmd == "findblocks":
            r = p.find_blocks(a.block, a.radius, a.limit, a.exposed)
        elif a.cmd == "nearest":
            r = p.nearest(a.name, a.radius)
        elif a.cmd == "call":
            r = p.call(a.endpoint, json.loads(a.json))
        else:
            ap.error("unknown command")
            return 2
    except PilotError as e:
        print(json.dumps({"ok": False, "error": str(e)}), file=sys.stderr)
        return 1
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(json.dumps(r, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
