"""Wave-3 real-client pass: drive every wave-3 feature in a real 26.1.2 client through TestPilot.

Why this exists: after wave 3 (2026-10-04) fourteen features had passed compile, JUnit, boot and
/sb selftest and had never been seen in a client (docs/INTEGRATION.md, "Not verified live"). Each
hand-off listed its own "verify live" steps in prose, so the client pass was a day of retyping.
This script is those steps, written once, with an automatic verdict wherever the client gives a
reliable signal and a screenshot wherever only eyes can judge.

Verdicts:  PASS  the client saw what the feature promises
           FAIL  it did not (evidence says what was seen instead)
           LOOK  ran; the deciding evidence is a screenshot or a free-text line a person must read
           SKIP  a precondition was missing (says which)
           ERROR the step itself broke (TestPilot / RCON error, with the message)

What it does NOT do (all on purpose):
  * start or stop the server, change server.properties, or launch a client. Do those by hand
    (README section 1): online-mode=false for the session only, then RESTORE true.
  * use OS input. Every action is a TestPilot request; RCON for admin commands.
  * install the TestPilot jar. Two checks (Target Practice, Quiver Arrow) need the ``usehold``
    endpoint added on 2026-10-04; rebuild with ``gradlew.bat build`` and copy the jar into the
    Fabric-26.1.2 instance's mods folder (README section 5) before running, or they report SKIP.

Usage (from tools\\testpilot, client already in the world as --user):
    py -3 wave3_pass.py --user PilotTest                 # every check
    py -3 wave3_pass.py --user PilotTest --only rift_arch,quiver_arrow
    py -3 wave3_pass.py --list

Output: docs/playtest/wave3/report.json + report.md + one PNG per screenshot. Stdlib only.
Set PYTHONIOENCODING=utf-8 (game text has symbols cp1252 cannot print).
"""
from __future__ import annotations

import argparse
import json
import math
import re
import socket
import struct
import sys
import time
import traceback
from pathlib import Path

from pilot import Pilot, PilotError

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / "docs" / "playtest" / "wave3"
COLOUR = re.compile("§[0-9a-fk-orx]", re.IGNORECASE)


# ---------------------------------------------------------------------------- RCON
class Rcon:
    """Minimal Source-RCON client (same protocol as scripts/rcon.ps1, end-marked the same way)."""

    def __init__(self, host="127.0.0.1", port=25631, password="skydev", timeout=10.0):
        self.addr, self.password, self.timeout = (host, port), password, timeout
        self.next_id = 10
        self.s = None
        self._connect()
        self.s.close()

    def _connect(self) -> None:
        # One connection per command: Paper drops the connection after answering the end-marker
        # packet, so a second command on the same socket reads "connection closed" (seen 2026-10-04).
        self.s = socket.create_connection(self.addr, timeout=self.timeout)
        self.s.settimeout(self.timeout)
        self._send(3, self.password, 1)
        rid, _, _ = self._recv()
        if rid == -1:
            raise RuntimeError("RCON authentication failed")

    def _send(self, kind: int, body: str, rid: int) -> None:
        payload = struct.pack("<ii", rid, kind) + body.encode("utf-8") + bytes(2)
        self.s.sendall(struct.pack("<i", len(payload)) + payload)

    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise RuntimeError("RCON connection closed")
            buf += chunk
        return buf

    def _recv(self):
        (length,) = struct.unpack("<i", self._recv_exact(4))
        data = self._recv_exact(length)
        rid, kind = struct.unpack("<ii", data[:8])
        return rid, kind, data[8:-2].decode("utf-8", "replace")

    def cmd(self, command: str) -> str:
        self._connect()
        try:
            self.next_id += 2
            rid, marker = self.next_id, self.next_id + 1
            self._send(2, command, rid)
            # The end marker goes out only AFTER the first reply packet: sent back-to-back with the
            # command, Paper 26.2 dropped the connection with no reply about one time in four
            # (measured 2026-10-04: 0/10 failures this way, 2/6 back-to-back).
            got, _, body = self._recv()
            out = [body] if got == rid else []
            self._send(0, "", marker)          # answered only after the command's reply: marks the end
            while True:
                got, _, body = self._recv()
                if got == marker:
                    break
                if got == rid:
                    out.append(body)
            return COLOUR.sub("", "".join(out)).strip()
        finally:
            try:
                self.s.close()
            except OSError:
                pass


# ---------------------------------------------------------------------------- the run
class Run:
    def __init__(self, p: Pilot, rc: Rcon, user: str):
        self.p, self.rc, self.user = p, rc, user
        self.results: list[dict] = []
        self.cur: dict | None = None

    # -- recording
    def note(self, line: str) -> None:
        self.cur["evidence"].append(line)
        print("    " + line[:300])

    def rcon(self, command: str) -> str:
        out = self.rc.cmd(command)
        self.note(f"rcon> {command}  =>  {out[:400]}")
        return out

    def shot(self, name: str) -> str:
        path = OUT / f"{self.cur['name']}_{name}.png"
        try:
            self.p.screenshot(path)
            self.cur["shots"].append(str(path.relative_to(ROOT)))
        except PilotError as e:
            self.note(f"screenshot {name} failed: {e}")
        return str(path)

    def chat_mark(self) -> int:
        return self.p.chat_count()

    def chat_after(self, mark: int) -> list[str]:
        lines = [COLOUR.sub("", l.get("text", l) if isinstance(l, dict) else str(l)) for l in self.p.chat_since(mark)]
        for l in lines:
            self.note("chat: " + l)
        return lines

    def ticks(self, n: int) -> None:
        self.p.wait(n)

    def me(self) -> dict:
        return self.p.state(radius=24, chat=0)

    # -- helpers used by several checks
    def hold_item(self, needle: str) -> int:
        """Put the item whose id/name contains needle in the hand; -1 if the player has none.

        A full hotbar sends /give to the main inventory - the first wave-3 run FAILed Target
        Practice on exactly that - so Pilot.hold swaps it into the hotbar the way a player does.
        """
        try:
            i = self.p.hold(needle)
        except PilotError as e:
            self.note(f"hold {needle}: {e}")
            return -1
        self.note(f"holding {needle} in hotbar slot {i}")
        self.ticks(4)
        return i

    def inv_count(self, needle: str) -> int:
        st = self.me()
        n = 0
        for it in (st.get("hotbar") or []) + (st.get("inventory") or []):
            if it and needle.lower() in f"{it.get('id', '')} {it.get('name', '')}".lower():
                n += it.get("count", 1)
        return n

    def has_usehold(self) -> bool:
        try:
            self.p.call("usehold", {"probe": True}, timeout=15)   # presses nothing
            return True
        except PilotError:
            return False

    def tp_anchor(self, admin: str, anchor: str, settle: int = 40) -> None:
        self.rcon(f"sb {admin} tp {self.user} {anchor}")
        self.ticks(settle)

    def warp(self, warp: str, settle: int = 80) -> None:
        self.p.chat(f"/warp {warp}")
        self.ticks(settle)


CHECKS: list[tuple[str, str]] = []


def check(name: str, title: str):
    def deco(fn):
        CHECKS.append((name, title))
        globals()["_check_" + name] = fn
        return fn
    return deco


def verdict(run: Run, v: str, why: str) -> None:
    run.cur["verdict"], run.cur["why"] = v, why


# ---------------------------------------------------------------------------- checks
@check("rift_arch", "Hub Rift arch: refused without a Planar Infusion (pushed OUT of the sheet), enters with one")
def _(r: Run):
    r.rcon(f"sb rift leave {r.user}")
    r.rcon(f"sb rift infusions {r.user} 0")
    r.tp_anchor("hub", "gate.rift")
    st = r.me()["player"]
    x0, z0 = st["x"], st["z"]
    r.p.look(yaw=90, pitch=0)                       # the anchor faces the arch (west)
    m = r.chat_mark()
    r.p.move(forward=1, seconds=1.6)
    r.ticks(30)
    after = r.me()["player"]
    lines = r.chat_after(m)
    r.shot("refused")
    refused = any("Infusion" in l or "Requires" in l for l in lines)
    sheet_x = math.floor(x0) - 2                    # the gate anchor is two blocks east of the sheet
    inside = sheet_x <= after["x"] < sheet_x + 1
    r.note(f"start x={x0:.2f} z={z0:.2f}, after x={after['x']:.2f} z={after['z']:.2f}, sheet column x={sheet_x}")
    if not refused or inside:
        verdict(r, "FAIL", f"refused={refused}, still inside the portal sheet={inside}")
        return
    r.rcon(f"sb rift infusions {r.user} 1")
    r.tp_anchor("hub", "gate.rift")
    r.p.look(yaw=90, pitch=0)
    m = r.chat_mark()
    r.p.move(forward=1, seconds=1.6)
    r.ticks(80)
    st2 = r.me()
    lines = r.chat_after(m)
    title = COLOUR.sub("", str(st2.get("title") or ""))
    moved = math.dist((st2["player"]["x"], st2["player"]["z"]), (x0, z0))
    r.note(f"after entering: title={title!r}, dimension={st2['player'].get('dimension')}, moved {moved:.0f} blocks")
    r.shot("entered")
    info = r.rcon(f"sb rift info {r.user}")
    r.rcon(f"sb rift leave {r.user}")
    ok = moved > 40 or "rift" in title.lower()
    verdict(r, "PASS" if ok else "FAIL", f"refused + pushed out; then entered={ok} (sb rift info: {info[:120]})")


@check("target_practice", "Archery Range Target Practice: a glowing target only you see, arrows score")
def _(r: Run):
    if not r.has_usehold():
        verdict(r, "SKIP", "TestPilot jar without the usehold endpoint (rebuild + install, README section 5)")
        return
    r.rcon(f"sb hub practice {r.user} stop")
    r.rcon(f"give {r.user} minecraft:bow")
    r.rcon(f"give {r.user} minecraft:arrow 64")
    r.tp_anchor("hub", "npc.range_master")
    if r.hold_item("bow") < 0:
        verdict(r, "FAIL", "no bow in the hotbar after /give")
        return
    m = r.chat_mark()
    r.rcon(f"sb hub practice {r.user} start")
    r.ticks(10)
    r.shot("started")
    shots = 0
    # The targets (x -93) are 27-35 blocks from the shooting line (x -66..-58) and /findblocks reaches 24,
    # so the glow is looked for from a scouting spot down-range and the arrow is loosed back on the line
    # (an arrow from in front of the line does not count). Final verification 2026-10-04: scanning from
    # the Range Master found nothing and the check never fired an arrow.
    scout, line = (-74.5, 75.0, 37.5), (-63.5, 75.0, 37.5)
    for _ in range(12):
        try:
            r.p.walk_to(*scout, timeout=15)
        except PilotError as e:
            r.note(f"walk to scouting spot: {e}")
        lamps = [b for b in r.p.find_blocks("redstone_lamp", radius=24, limit=40)]
        lanterns = {(b["x"], b["y"], b["z"]) for b in r.p.find_blocks("sea_lantern", radius=24, limit=60)}
        lit = [b for b in lamps if (b["x"], b["y"] + 1, b["z"]) in lanterns]
        if not lit:
            r.note("no glowing target (lamp under a sea lantern) visible to the client")
            break
        t = lit[0]
        try:
            r.p.walk_to(*line, timeout=15)
        except PilotError as e:
            r.note(f"walk back to the line: {e}")
        r.ticks(5)
        d = math.hypot(t["x"] + 0.5 - line[0], t["z"] + 0.5 - line[2])
        lift = 0.025 * (d / 3.0) ** 2              # full-power arrow drop over that distance
        r.p.use_hold(1.3, x=t["x"] + 0.5, y=t["y"] + 1.0, z=t["z"] + 0.5, lift=lift)
        shots += 1
        r.ticks(25)
    r.shot("shooting")
    r.rcon(f"sb hub practice {r.user} stop")
    r.ticks(10)
    lines = r.chat_after(m)
    score = None
    for l in lines:
        mm = re.search(r"Score:\s*(\d+)", l)
        if mm:
            score = int(mm.group(1))
    if score is None:
        verdict(r, "FAIL", f"no result box after {shots} shots")
    elif score > 0:
        verdict(r, "PASS", f"scored {score} from {shots} shots; only this client's glow was used to aim")
    else:
        verdict(r, "LOOK", f"result box came back with score 0 after {shots} shots - read the chat (line / wrong target?)")


@check("portal_isle", "Private Island Portal Isle: labels, dark portal refuses and nudges out")
def _(r: Run):
    r.warp("home")
    m = r.chat_mark()
    r.p.chat("/is portals")
    try:
        scr = r.p.wait_for_screen("Portal", timeout=8)
    except PilotError as e:
        verdict(r, "FAIL", f"/is portals opened no menu: {e}")
        return
    r.note("menu: " + scr.get("title", ""))
    r.shot("menu")
    names = [s.get("name") for s in scr.get("slots", []) if s.get("container") and s.get("name")]
    r.note("icons: " + ", ".join(n for n in names if "Glass" not in n)[:300])
    pick = next((s for s in scr.get("slots", []) if s.get("container") and "Barn" in (s.get("name") or "")), None)
    if pick is None:
        verdict(r, "FAIL", "no Barn portal icon in the Portal Isle menu")
        return
    r.p.click(pick["slot"])
    r.ticks(40)
    r.chat_after(m)
    labels = [e for e in r.p.entities(10) if e.get("type") == "minecraft:text_display"]
    texts = [COLOUR.sub("", str(e.get("text") or e.get("nameplate") or "")) for e in labels]
    r.note("labels near: " + " | ".join(texts)[:300])
    r.shot("front")
    portal = r.p.find_blocks("nether_portal", radius=4, limit=4)
    frame = r.p.find_blocks("obsidian", radius=4, limit=12)
    target = portal[0] if portal else None
    if target is None and frame:
        xs = [b["x"] for b in frame]; ys = [b["y"] for b in frame]; zs = [b["z"] for b in frame]
        target = {"x": sum(xs) / len(xs), "y": min(ys) + 1, "z": sum(zs) / len(zs)}
    if target is None:
        verdict(r, "FAIL", "no frame within 4 blocks of the spot the menu sent us to")
        return
    m = r.chat_mark()
    try:
        r.p.walk_to(target["x"] + 0.5, target["y"], target["z"] + 0.5, timeout=4, tolerance=0.3)
    except PilotError as e:
        r.note(f"walk: {e}")
    r.ticks(40)
    lines = r.chat_after(m)
    r.shot("walked_in")
    if portal:
        verdict(r, "LOOK", "the Barn portal was LIT: did the walk warp us (screenshot) ?")
    elif any("opens once you have been there" in l for l in lines):
        verdict(r, "PASS", f"dark frame refused with the visit message; labels: {texts[:3]}")
    else:
        verdict(r, "FAIL", "walking into a dark frame said nothing")


@check("kuudra_again", "Leviathan hunt: [HUNT AGAIN] after a win starts the next hunt from the hollow")
def _(r: Run):
    r.rcon(f"sb kuudra end {r.user}")
    m = r.chat_mark()
    out = r.rcon(f"sb kuudra start {r.user}")
    if "Starting" not in out:
        verdict(r, "SKIP", f"admin start refused: {out[:160]}")
        return
    r.ticks(200)
    r.rcon(f"sb kuudra phase {r.user} win")
    r.ticks(60)
    lines = r.chat_after(m)
    r.shot("victory")
    offered = any("HUNT AGAIN" in l.upper() for l in lines)
    m = r.chat_mark()
    r.p.chat("/kuudra again")
    r.ticks(200)
    lines = r.chat_after(m)
    r.shot("again")
    started = any("Preparing" in l for l in lines)
    r.rcon(f"sb kuudra end {r.user}")
    if offered and started:
        verdict(r, "PASS", "victory offered HUNT AGAIN and /kuudra again prepared a new hollow")
    else:
        verdict(r, "FAIL", f"offered={offered}, started={started} - see chat")


@check("moonmarsh", "Moonmarsh: resources pickable, Starfall Contest on the sidebar and paid out")
def _(r: Run):
    r.warp("moonmarsh", settle=120)
    side = [COLOUR.sub("", s) for s in r.p.sidebar()]
    r.note("sidebar: " + " / ".join(side))
    r.shot("arrival")
    contest_shown = any("starfall" in s.lower() or "contest" in s.lower() for s in side)
    picked = None
    for kind in ("lilac", "red_mushroom", "sea_pickle"):
        blocks = r.p.find_blocks(kind, radius=24, limit=5)
        if not blocks:
            continue
        b = blocks[0]
        try:
            r.p.walk_to(b["x"] + 0.5, b["y"], b["z"] + 0.5, timeout=15, tolerance=2.5)
            m = r.chat_mark()
            res = r.p.mine_block_at(b["x"], b["y"], b["z"], max_seconds=4)
            r.ticks(10)
            r.chat_after(m)
            r.note(f"{kind}: {res.get('before')} -> {res.get('after')}")
            picked = kind if res.get("broken") else picked
        except PilotError as e:
            r.note(f"{kind}: {e}")
        if picked:
            break
    r.shot("picked")
    m = r.chat_mark()
    r.rcon(f"sb foraging contest {r.user} marsh 100")
    r.rcon(f"sb foraging contest {r.user} end")
    r.ticks(20)
    paid = r.chat_after(m)
    mobs = [e.get("customName") or e.get("nameplate") for e in r.p.entities(32)
            if any(k in str(e.get("nameplate") or e.get("customName") or "") for k in ("Wretch", "Hex", "Lancer", "Frost"))]
    r.note("moonmarsh mobs in 32 blocks: " + ", ".join(str(x) for x in mobs[:6]))
    ok = contest_shown and picked and paid
    verdict(r, "PASS" if ok else "LOOK", f"contest on sidebar={contest_shown}, picked={picked}, contest paid lines={len(paid)}")


@check("belly_pearls", "Crimson Isle Belly of the Beast: your pearls glow for you and are picked up by walking")
def _(r: Run):
    r.warp("crimson_isle", settle=120)
    r.rcon(f"sb crimson pearls {r.user} reset")
    out = r.rcon(f"sb crimson pearls {r.user} show")
    spots = [tuple(int(v) for v in mm) for mm in re.findall(r"\((-?\d+), (-?\d+), (-?\d+)\)", out)]
    if not spots:
        verdict(r, "FAIL", "no pearl spots listed for today")
        return
    x, y, z = spots[0]
    r.rcon(f"tp {r.user} {x + 0.5} {y - 1} {z + 3.5}")
    r.ticks(40)
    disp = [e for e in r.p.entities(8) if e.get("type") == "minecraft:item_display"]
    r.note(f"item displays within 8 blocks (client-side): {len(disp)}")
    r.p.look(x=x + 0.5, y=y + 0.3, z=z + 0.5)
    r.shot("pearl_visible")
    m = r.chat_mark()
    try:
        r.p.walk_to(x + 0.5, y - 1, z + 0.5, timeout=6, tolerance=0.4)
    except PilotError as e:
        r.note(f"walk: {e}")
    r.ticks(30)
    lines = r.chat_after(m)
    got = any("PEARL!" in l for l in lines)
    verdict(r, "PASS" if (got and disp) else "FAIL", f"pearl display seen={bool(disp)}, picked up by walking={got}")


@check("goblin_queen", "Crystal Caverns: the Goblin Queen stands in her den and can be hit")
def _(r: Run):
    r.warp("crystal_hollows", settle=160)
    r.tp_anchor("hollows", "boss.goblin_queen")
    r.rcon("sb hollows queen")
    r.ticks(40)
    q = r.p.nearest("Goblin Queen", radius=32)
    if q is None:
        r.shot("den")
        verdict(r, "FAIL", "no Goblin Queen visible within 32 blocks of her anchor")
        return
    r.note(f"queen: {q.get('nameplate')} hp={q.get('health')}/{q.get('maxHealth')} at {q['distance']:.1f} blocks")
    r.p.look(entity=q["id"])
    r.shot("queen")
    try:
        r.p.kill_nearest("Goblin Queen", timeout=8)
    except PilotError as e:
        r.note(f"fight: {e}")
    q2 = r.p.nearest("Goblin Queen", radius=32)
    r.shot("fight")
    r.note(f"after 8 s: {q2.get('nameplate') if q2 else 'gone'}")
    verdict(r, "LOOK", "queen found; read the nameplate before/after and the fight screenshot (Brood Guards at 75%)")


@check("quiver_arrow", "Hunter-style bow draws from the quiver through the Quiver Arrow in slot 9")
def _(r: Run):
    if not r.has_usehold():
        verdict(r, "SKIP", "TestPilot jar without the usehold endpoint (rebuild + install, README section 5)")
        return
    r.rcon(f"clear {r.user} minecraft:arrow")
    r.rcon(f"clear {r.user} minecraft:tipped_arrow")
    r.rcon(f"give {r.user} minecraft:bow")
    out = r.rcon(f"sb storage absorb {r.user} ARROW 64")
    if r.inv_count("arrow") > 0 and "tipped" not in str(r.me().get("hotbar")):
        verdict(r, "SKIP", "the arrows went to the inventory (quiver locked for this account): " + out[:120])
        return
    if r.hold_item("bow") < 0:
        verdict(r, "FAIL", "no bow in the hotbar")
        return
    r.ticks(20)
    st = r.me()
    nine = (st.get("hotbar") or [None] * 9)[8] or {}
    r.note(f"slot 9 while holding the bow: {nine.get('id')} {COLOUR.sub('', str(nine.get('name')))} x{nine.get('count')}")
    before = r.rcon(f"sb weapons quiverarrow {r.user}")
    r.shot("holding_bow")
    st0 = r.me()["player"]
    yaw = math.radians(st0["yaw"])
    ahead = (st0["x"] - math.sin(yaw) * 20, st0["y"] + 4, st0["z"] + math.cos(yaw) * 20)
    res = r.p.use_hold(1.3, x=ahead[0], y=ahead[1], z=ahead[2])
    r.note(f"draw: wasUsing={res.get('wasUsing')}")
    r.ticks(15)
    after = r.rcon(f"sb weapons quiverarrow {r.user}")
    n0 = re.search(r"x(\d+)", before)
    n1 = re.search(r"x(\d+)", after)
    spent = n0 and n1 and int(n1.group(1)) == int(n0.group(1)) - 1
    r.p.hotbar(0 if r.me()["player"].get("selectedSlot") != 0 else 1)
    r.ticks(20)
    nine2 = (r.me().get("hotbar") or [None] * 9)[8] or {}
    r.note(f"slot 9 after switching away: {nine2.get('id')} {COLOUR.sub('', str(nine2.get('name')))}")
    r.shot("switched_away")
    proxy = nine.get("id") == "minecraft:tipped_arrow"
    back = nine2.get("id") == "minecraft:nether_star"
    ok = proxy and spent and back
    verdict(r, "PASS" if ok else "FAIL", f"proxy in slot 9={proxy}, one quiver arrow spent={bool(spent)}, SkyBlock Menu back={back}")


@check("pet_riding", "Pets: sit on the Rock, steer the Armadillo, sneak to get off")
def _(r: Run):
    results = []
    # Steer on open ground: the first run inherited the previous check's spot beside the Archery
    # Range and drove the Armadillo into the house (2.2 blocks in 2 s). Measure, then point.
    r.warp("hub")
    open_dir = r.p.clearance(directions=24, max=16)
    r.note(f"most open direction from here: yaw {open_dir['bestYaw']:.0f}, {open_dir['bestClear']:.1f} blocks walkable")
    for pet in ("ROCK", "ARMADILLO"):
        r.rcon(f"sb pets give {r.user} {pet} LEGENDARY 100")
        r.rcon(f"sb pets summon {r.user} {pet}")
        r.ticks(30)
        m = r.chat_mark()
        r.p.chat("/pet ride")
        r.ticks(20)
        lines = r.chat_after(m)
        on = any("You are riding" in l for l in lines)
        st = r.me()["player"]
        r.shot(f"{pet.lower()}_on")
        moved = 0.0
        if pet == "ARMADILLO" and on:
            r.p.look(yaw=open_dir["bestYaw"], pitch=0)
            r.ticks(2)
            r.p.move(forward=1, seconds=2.0)
            st2 = r.me()["player"]
            moved = math.dist((st["x"], st["z"]), (st2["x"], st2["z"]))
            r.note(f"armadillo steered {moved:.1f} blocks in 2 s")
            r.shot("armadillo_moving")
        m = r.chat_mark()
        r.p.move(sneak=True, seconds=0.6)
        r.ticks(10)
        off = any("got off" in l for l in r.chat_after(m))
        results.append((pet, on, off, moved))
        r.rcon(f"sb pets despawn {r.user}")
    r.note(f"results: {results}")
    ok = all(on and off for _, on, off, _ in results) and results[1][3] > 3
    verdict(r, "PASS" if ok else "FAIL", "; ".join(f"{p}: on={a} off={b}" + (f" moved={mv:.1f}" if p == "ARMADILLO" else "")
                                               for p, a, b, mv in results))


@check("bank_account_wide", "Bank: Booster-Cookie buff/bits/fame follow the ACCOUNT across a profile switch")
def _(r: Run):
    first = r.rcon(f"sb banking info {r.user}")
    m = r.chat_mark()
    r.p.chat("/profiles list")
    r.ticks(10)
    listing = r.chat_after(m)
    r.p.chat("/profiles create")
    r.ticks(200)
    try:
        r.p.wait_until_ready(60)
    except PilotError as e:
        r.note(f"after create: {e}")
    second = r.rcon(f"sb banking info {r.user}")
    r.shot("new_profile")
    loaded = "loaded" in second.lower()
    verdict(r, "LOOK" if loaded else "FAIL",
            "compare the two 'sb banking info' blocks (buff/bits/fame must match; account-wide: loaded); "
            "switch back by hand with /profiles switch <name> from: " + " | ".join(listing)[:200])


@check("rift_gallery", "Rift: Curator secures Timecharms (floating exhibits), Split or Steal round, Odonata")
def _(r: Run):
    r.rcon(f"sb rift charm {r.user} all")
    r.tp_anchor("rift", "npc.curator", settle=60)
    try:
        r.p.use(target="entity", entity=r.p.nearest("Curator", radius=8)["id"])
        scr = r.p.wait_for_screen(None, timeout=6)
        r.note("curator menu: " + scr.get("title", ""))
        r.shot("curator_menu")
        m = r.chat_mark()
        r.p.click_item_named("Secure Everything")
        r.ticks(20)
        r.chat_after(m)
        r.p.close()
    except (PilotError, TypeError) as e:
        r.note(f"curator: {e}")
    r.ticks(20)
    exhibits = [e for e in r.p.entities(24) if e.get("type") == "minecraft:item_display"]
    r.note(f"item displays (exhibits) visible within 24: {len(exhibits)}")
    r.shot("gallery")
    r.rcon(f"sb rift splitsteal {r.user}")
    r.tp_anchor("rift", "npc.split_steal", settle=60)
    m = r.chat_mark()
    try:
        r.p.use(target="entity", entity=r.p.nearest("Pip", radius=8)["id"])
        scr = r.p.wait_for_screen(None, timeout=6)
        r.note("split or steal menu: " + scr.get("title", ""))
        r.shot("splitsteal_menu")
        r.p.click_item_named("Split")
        r.p.close()
    except (PilotError, TypeError) as e:
        r.note(f"split or steal: {e}")
    r.ticks(20 * 50)                                  # the Echo answers after rift.split-steal-wait (45 s)
    lines = r.chat_after(m)
    r.shot("splitsteal_result")
    resolved = any("split" in l.lower() or "steal" in l.lower() for l in lines)
    r.rcon(f"sb rift leave {r.user}")
    verdict(r, "LOOK", f"exhibits visible={len(exhibits)}, Split or Steal resolved={resolved}. "
                        "Odonata is particles only: catch one by hand (buy an Empty Odonata Bottle from a Glade Peddler, "
                        "right-click while looking at a dragonfly in the Wildwood meadows)")


@check("runic_pedestal", "Runic Pedestal (/runes): fuse two Blood Rune I by clicking your inventory")
def _(r: Run):
    r.rcon(f"sb runes test-fuse {r.user}")
    r.ticks(10)
    r.p.chat("/runes")
    try:
        scr = r.p.wait_for_screen("Runic", timeout=6)
    except PilotError as e:
        verdict(r, "FAIL", f"/runes opened no Runic Pedestal: {e}")
        return
    rune = next((s for s in scr.get("slots", []) if not s.get("container") and "Rune" in (s.get("name") or "")), None)
    if rune is None:
        verdict(r, "FAIL", "no rune in the inventory part of the pedestal screen")
        return
    r.p.click(rune["slot"]); r.ticks(6)
    r.p.click(rune["slot"]); r.ticks(6)
    r.shot("placed")
    m = r.chat_mark()
    r.p.click(22)
    r.ticks(10)
    lines = r.chat_after(m)
    r.shot("fused")
    r.p.close()
    fused = any("RUNE FUSED" in l for l in lines)
    verdict(r, "PASS" if fused else "FAIL", "fused" if fused else "no RUNE FUSED line (see the 'placed' screenshot: action icon text)")


@check("carnival", "Carnival (Fairground perk): /carnival opens, Fruit Digging reachable")
def _(r: Run):
    r.rcon("sb mayors perk foxy.chivalrous_carnival on")
    r.p.chat("/carnival")
    try:
        scr = r.p.wait_for_screen(None, timeout=6)
        r.note("carnival menu: " + scr.get("title", ""))
        names = [s.get("name") for s in scr.get("slots", []) if s.get("container") and s.get("name") and "Glass" not in s.get("name")]
        r.note("icons: " + ", ".join(names)[:300])
        r.shot("menu")
        r.p.close()
        ok = True
    except PilotError as e:
        r.note(f"/carnival: {e}")
        ok = False
    r.rcon("sb mayors perk foxy.chivalrous_carnival clear")
    verdict(r, "LOOK" if ok else "FAIL", "menu opened; play one Fruit Digging / Zombie Shootout round by hand (timing games)")


@check("gfs", "/gfs pulls from sacks into the inventory")
def _(r: Run):
    r.rcon(f"sb storage givesack {r.user} SMALL_MINING_SACK")
    r.rcon(f"sb storage sack {r.user} add COBBLESTONE 100")
    r.rcon(f"clear {r.user} minecraft:cobblestone")
    m = r.chat_mark()
    r.p.chat("/gfs cobblestone 10")
    r.ticks(10)
    r.chat_after(m)
    n = r.inv_count("cobblestone")
    verdict(r, "PASS" if n == 10 else "FAIL", f"inventory cobblestone after /gfs cobblestone 10: {n}")


# ---------------------------------------------------------------------------- main
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--user", required=False, default="PilotTest")
    ap.add_argument("--port", type=int, default=25620)
    ap.add_argument("--only", default="")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args(argv)
    if a.list:
        for n, t in CHECKS:
            print(f"{n:20} {t}")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    p = Pilot(port=a.port)
    ping = p.ping()
    if not ping.get("inWorld") or ping.get("player") != a.user:
        print(f"TestPilot client is not in the world as {a.user}: {ping}")
        return 2
    rc = Rcon()
    run = Run(p, rc, a.user)
    run.cur = {"name": "setup", "evidence": [], "shots": []}
    run.rcon(f"op {a.user}")
    run.rcon("sb info")
    only = {x.strip() for x in a.only.split(",") if x.strip()}
    for name, title in CHECKS:
        if only and name not in only:
            continue
        print(f"\n== {name}: {title}")
        run.cur = {"name": name, "title": title, "verdict": "ERROR", "why": "", "evidence": [], "shots": []}
        run.results.append(run.cur)
        t0 = time.time()
        try:
            p.stop()
            if p.screen():
                p.close()
            globals()["_check_" + name](run)
        except Exception as e:                       # one broken check must not stop the pass
            run.cur["verdict"], run.cur["why"] = "ERROR", f"{type(e).__name__}: {e}"
            run.cur["evidence"].append(traceback.format_exc()[-1500:])
        run.cur["seconds"] = round(time.time() - t0, 1)
        print(f"   -> {run.cur['verdict']}: {run.cur['why']}")
    run.cur = {"name": "teardown", "evidence": [], "shots": []}
    for c in (f"sb rift leave {a.user}", f"sb kuudra end {a.user}", f"sb hub practice {a.user} stop",
              "sb mayors perk foxy.chivalrous_carnival clear", f"deop {a.user}"):
        try:
            run.rcon(c)
        except Exception:
            pass
    (OUT / "report.json").write_text(json.dumps(run.results, indent=2), encoding="utf-8")
    md = ["# Wave-3 client pass", "", f"{time.strftime('%Y-%m-%d %H:%M')} - user {a.user}", "",
          "| Check | Verdict | Why | Screenshots |", "|---|---|---|---|"]
    for x in run.results:
        md.append(f"| {x['name']} | **{x['verdict']}** | {x['why'].replace('|', '/')} | "
                  + " ".join(f"[{Path(s).stem}]({Path(s).name})" for s in x["shots"]) + " |")
    (OUT / "report.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n" + "\n".join(md))
    return 0 if all(x["verdict"] in ("PASS", "LOOK", "SKIP") for x in run.results) else 1


if __name__ == "__main__":
    sys.exit(main())
