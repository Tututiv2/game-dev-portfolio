"""Macro tools: the model decides WHAT, these do HOW - reliably, through TestPilot only.

Interface (builder B1 codes against exactly this):

    from show.macros import TOOLS, describe_tools, run_action
    describe_tools() -> str                       # compact text for the system prompt
    run_action(ctx, "fight", {"target": "Graveyard Zombie", "count": 3})
        -> {"ok": True, "result": "killed 3 Graveyard Zombie in 21s, took 40 dmg, now 60/100 HP"}

`ctx` has `.pilot` (pilot.Pilot), `.char` (config.Character), `.log(msg)` and
`.showcontrol_state()` (ShowControl `/state/<account>` dict, or {}). Optional: `ctx.stop_event`
(threading.Event) - a set event makes the running macro return early.

Every macro is bounded in time (45 s default, 60 s max), never raises out of `run_action`, releases
every held key when it ends, and returns a SHORT result the model can act on (what happened, the
numbers, and what to try next when it failed).

SkyClone facts these macros depend on (read from SkyblockClone's handoffs + data, 2026-10-03):
- NPCs are `minecraft:mannequin` (hub) / villager (Gerald) entities with name labels; right-click
  (TestPilot `/use` target=entity) talks/opens. Shops greet once per profile ([NPC] lines 1.2 s apart)
  then open a 6-row menu titled with the merchant's name; clicking a stack in YOUR inventory part of a
  shop sells it; left-click a stock item buys one bundle.
- The SkyBlock Menu is the nether star in hotbar slot 8 (key 9); `/sbmenu` also opens it.
- Mining islands + the hub Coal Mine use the DigEngine (server breaks the block while you keep
  swinging; mined ore becomes BEDROCK and regrows). TestPilot `/attackhold` keeps swinging and stops
  when the block changes. Private island = vanilla breaking. Crops/logs: vanilla break, server pays.
- Fishing: the bite is a red '!!!' text display just above the bobber; reel within ~1.5 s.
- `/recipe <item>` opens the recipe view whose 'Supercraft' button crafts straight from inventory.
- `/warp <id>`; hub district warps (`hub_<district>`, `crypt`, `castle`, `wizard`, `da`, `museum`)
  unlock only after you walk into the district once.
"""
from __future__ import annotations

import inspect
import math
import re
import time
from dataclasses import dataclass, field
from typing import Any, Callable

from . import observe as ob
from .observe import (LAYOUTS, best_match, classify, clean, compass, entity_name, fuzzy_score, island_of,
                      mob_info, norm, parse_sidebar, parse_vitals, player_hp, screen_summary, short)
from .pilot_bridge import PilotError

DEFAULT_TIMEOUT = 45.0
MAX_TIMEOUT = 60.0
RESULT_MAX = 420


# ====================================================================== plumbing
class MacroFail(Exception):
    """A macro could not do what was asked; the message is the result shown to the model."""


class Timeout(Exception):
    pass


@dataclass
class Tool:
    name: str
    args: dict[str, str]
    doc: str
    fn: Callable
    timeout: float = DEFAULT_TIMEOUT
    aliases: dict[str, str] = field(default_factory=dict)

    def signature(self) -> str:
        return f"{self.name}({', '.join(self.args)})"


TOOLS: dict[str, Tool] = {}
_TOOL_ALIASES: dict[str, str] = {}

# argument names models commonly use for the same thing
_ARG_ALIASES = {
    "destination": "dest", "to": "dest", "where": "dest", "island": "dest", "location": "dest",
    "anchor": "place", "target_name": "target", "mob": "target", "mobs": "target", "enemy": "target",
    "npc_name": "npc", "who": "npc", "name": "item", "item_name": "item", "items": "item",
    "amount": "n", "quantity": "n", "qty": "n", "number": "n", "times": "n", "kills": "count",
    "duration": "seconds", "time": "seconds", "secs": "seconds", "s": "seconds",
    "message": "text", "msg": "text", "line": "text", "cmd": "text",
    "crop": "block", "log": "block", "ore": "block", "resource": "block", "type": "block",
}


def tool(name: str, doc: str, args: dict[str, str] | None = None, timeout: float = DEFAULT_TIMEOUT,
         aliases: tuple[str, ...] = ()):
    def deco(fn):
        TOOLS[name] = Tool(name, args or {}, doc, fn, timeout)
        for a in aliases:
            _TOOL_ALIASES[a] = name
        return fn
    return deco


def describe_tools() -> str:
    lines = ["Tools (put up to the allowed number in \"actions\"; args are JSON, `?` = optional):"]
    for t in TOOLS.values():
        lines.append(f"- {t.signature()}: {t.doc}")
    return "\n".join(lines)


def _resolve_tool(name: str) -> Tool | None:
    n = (name or "").strip().lower().replace("-", "_").replace(" ", "_")
    if n in TOOLS:
        return TOOLS[n]
    if n in _TOOL_ALIASES:
        return TOOLS[_TOOL_ALIASES[n]]
    hit, _ = best_match(n, TOOLS.keys(), threshold=0.75)
    return TOOLS.get(hit) if hit else None


def _bind_args(t: Tool, args: Any) -> dict:
    params = [p for p in inspect.signature(t.fn).parameters.values() if p.name != "g"]
    names = [p.name for p in params]
    if args is None:
        args = {}
    if not isinstance(args, dict):
        if isinstance(args, (list, tuple)):
            return {names[i]: v for i, v in enumerate(args) if i < len(names)}
        return {names[0]: args} if names else {}
    out: dict = {}
    for k, v in args.items():
        k2 = str(k).strip().lower()
        if k2 in names:
            out[k2] = v
            continue
        a = _ARG_ALIASES.get(k2)
        if a in names and a not in out:
            out[a] = v
            continue
        # a lone unknown string arg goes to the first parameter
        if names and names[0] not in out and len(args) == 1:
            out[names[0]] = v
    return out


def run_action(ctx, tool_name: str, args: Any = None) -> dict:
    """Run one macro. Never raises. -> {"ok": bool, "result": short str}."""
    t = _resolve_tool(tool_name)
    if t is None:
        return {"ok": False, "result": f"unknown tool {tool_name!r}; tools: {', '.join(TOOLS)}"}
    try:
        kwargs = _bind_args(t, args)
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "result": f"bad args for {t.name}: {e}"}
    budget = t.timeout
    for k in ("seconds", "timeout"):
        if k in kwargs:
            try:
                budget = max(budget, min(MAX_TIMEOUT, float(kwargs[k]) + 8))
            except (TypeError, ValueError):
                pass
    g = Game(ctx, budget)
    ok, result = False, ""
    try:
        r = t.fn(g, **kwargs)
        if isinstance(r, tuple):
            ok, result = bool(r[0]), str(r[1])
        else:
            ok, result = True, str(r)
    except MacroFail as e:
        ok, result = False, str(e)
    except Timeout:
        ok, result = False, f"{t.name} ran out of time ({budget:.0f}s){g.progress_note()}"
    except TypeError as e:
        ok, result = False, f"bad args for {t.signature()}: {e}"
    except PilotError as e:
        ok, result = False, f"game client error: {short(str(e), 200)}"
    except Exception as e:  # noqa: BLE001
        ok, result = False, f"{t.name} failed: {type(e).__name__}: {short(str(e), 200)}"
    finally:
        if g.moved:
            try:
                ctx.pilot.stop()
            except Exception:  # noqa: BLE001
                pass
    try:
        ctx.log(f"[macro] {t.name}{kwargs} -> {'ok' if ok else 'FAIL'}: {short(result, 300)}")
    except Exception:  # noqa: BLE001
        pass
    return {"ok": ok, "result": short(result, RESULT_MAX)}


# ====================================================================== game helper
def _f(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def hdist(a, b) -> float:
    return math.hypot(a[0] - b[0], a[2] - b[2])


class Game:
    """Per-call helper around the pilot: deadline, cached state, movement with recovery."""

    def __init__(self, ctx, budget: float) -> None:
        self.ctx = ctx
        self.p = ctx.pilot
        self.char = ctx.char
        self.deadline = time.time() + budget
        self.moved = False
        self.note = ""
        self.st: dict = {}

    # ---------------------------------------------------------- time
    def left(self) -> float:
        return self.deadline - time.time()

    def check(self, reserve: float = 0.0) -> None:
        ev = getattr(self.ctx, "stop_event", None)
        if ev is not None and ev.is_set():
            raise MacroFail("stopped by the show")
        if self.left() <= reserve:
            raise Timeout()

    def sleep(self, s: float) -> None:
        time.sleep(max(0.0, min(s, self.left())))

    def progress_note(self) -> str:
        return f" - {self.note}" if self.note else ""

    def log(self, msg: str) -> None:
        try:
            self.ctx.log(msg)
        except Exception:  # noqa: BLE001
            pass

    # ---------------------------------------------------------- state
    def state(self, radius: float = 24, chat: int = 0) -> dict:
        self.st = self.p.state(radius=radius, chat=chat)
        return self.st

    def sc(self) -> dict:
        try:
            return self.ctx.showcontrol_state() or {}
        except Exception:  # noqa: BLE001
            return {}

    @staticmethod
    def pos(st: dict) -> tuple[float, float, float]:
        pl = st.get("player") or {}
        return (_f(pl.get("x"), 0.0), _f(pl.get("y"), 0.0), _f(pl.get("z"), 0.0))

    @staticmethod
    def island(st: dict) -> str:
        return island_of((st.get("player") or {}).get("dimension"))

    def hp(self, st: dict | None = None) -> tuple[float, float]:
        return player_hp(st or self.st, None)

    def purse(self, st: dict | None = None) -> float | None:
        st = st or self.state(radius=0)
        sc = self.sc()
        if sc.get("purse") is not None:
            return _f(sc["purse"])
        return parse_sidebar((st.get("sidebar") or {}).get("lines") or []).get("purse")

    def chat_n(self) -> int:
        try:
            return int(self.p.chat_count())
        except Exception:  # noqa: BLE001
            return 0

    def chat_new(self, n: int) -> list[str]:
        try:
            lines = self.p.chat_since(n)
        except Exception:  # noqa: BLE001
            return []
        out = []
        for ln in lines:
            for part in clean(ln.get("text") if isinstance(ln, dict) else ln).split("\n"):
                part = part.strip()
                if part and not re.fullmatch(r"[\s▬\-=_]+", part):
                    out.append(part)
        return out

    # ---------------------------------------------------------- screens
    def screen(self) -> dict | None:
        return self.state(radius=0).get("screen")

    def wait_screen(self, pred: Callable[[dict], bool] | None = None, timeout: float = 4.0) -> dict | None:
        end = time.time() + min(timeout, max(0.2, self.left()))
        while time.time() < end:
            s = self.screen()
            if s and (pred is None or pred(s)):
                return s
            time.sleep(0.15)
        return None

    def close(self) -> None:
        try:
            if self.screen():
                self.p.close()
                time.sleep(0.25)
        except Exception:  # noqa: BLE001
            pass

    @staticmethod
    def title(s: dict | None) -> str:
        return clean((s or {}).get("title"))

    @staticmethod
    def slots(s: dict | None, container: bool | None = True) -> list[dict]:
        out = []
        for sl in (s or {}).get("slots") or []:
            if not sl.get("id") or "glass_pane" in (sl.get("id") or ""):
                continue
            if container is not None and bool(sl.get("container", True)) != container:
                continue
            if not clean(sl.get("name")).strip():
                continue
            out.append(sl)
        return out

    def find_slot(self, s: dict | None, query: str, container: bool | None = True,
                  use_lore: bool = True, threshold: float = 0.6) -> dict | None:
        cands = self.slots(s, container)
        hit, score = best_match(query, cands, key=lambda sl: clean(sl.get("name")), threshold=threshold)
        if hit or not use_lore:
            return hit
        hit, _ = best_match(query, cands, key=lambda sl: [clean(x) for x in (sl.get("lore") or [])[:6]],
                            threshold=0.8)
        return hit

    # ---------------------------------------------------------- items
    @staticmethod
    def hotbar(st: dict) -> list[dict]:
        return st.get("hotbar") or []

    @staticmethod
    def held(st: dict) -> dict | None:
        hb = st.get("hotbar") or []
        sel = (st.get("player") or {}).get("selectedSlot")
        return hb[sel] if isinstance(sel, int) and 0 <= sel < len(hb) else None

    def select(self, slot: int) -> None:
        self.p.hotbar(int(slot))
        time.sleep(0.1)

    def inv_counts(self, st: dict | None = None) -> dict[str, int]:
        st = st or self.state(radius=0)
        out: dict[str, int] = {}
        for it in st.get("inventory") or []:
            if it.get("id"):
                nm = clean(it.get("name")) or it["id"].split(":")[-1]
                out[nm] = out.get(nm, 0) + int(it.get("count") or 1)
        return out

    @staticmethod
    def inv_delta(before: dict[str, int], after: dict[str, int], keyword: str | None = None) -> str:
        parts = []
        for k, v in after.items():
            d = v - before.get(k, 0)
            if d > 0 and (keyword is None or keyword in norm(k)):
                parts.append(f"+{d} {k}")
        for k, v in before.items():
            d = after.get(k, 0) - v
            if d < 0 and keyword is None:
                parts.append(f"{d} {k}")
        return ", ".join(parts[:8])

    def equip_where(self, pred: Callable[[dict], bool], prefer_slot: int | None = None) -> dict | None:
        """Select the hotbar slot whose item satisfies pred; pull it from the inventory if needed.
        Returns the item, or None."""
        st = self.state(radius=0)
        hb = self.hotbar(st)
        for i, it in enumerate(hb[:8]):
            if it and it.get("id") and pred(it):
                self.select(i)
                return it
        # in the main inventory: swap into the hotbar through the inventory screen
        main = [it for it in (st.get("inventory") or []) if it.get("id") and (it.get("slot") or 0) >= 9 and pred(it)]
        if not main:
            return None
        want = main[0]
        free = [i for i in range(8) if not (hb[i] if i < len(hb) else {}).get("id")]
        target = free[0] if free else (prefer_slot if prefer_slot is not None else 7)
        self.close()
        self.p.open_inventory()
        s = self.wait_screen(timeout=3)
        if not s:
            return None
        src = None
        for sl in s.get("slots") or []:
            if sl.get("id") == want.get("id") and clean(sl.get("name")) == clean(want.get("name")) \
                    and (sl.get("containerSlot") == want.get("slot") or src is None):
                src = sl
                if sl.get("containerSlot") == want.get("slot"):
                    break
        if src is None:
            self.close()
            return None
        self.p.click(src["slot"], target, "SWAP")
        time.sleep(0.2)
        self.close()
        self.select(target)
        return want

    # ---------------------------------------------------------- movement
    def look_at(self, x, y, z) -> None:
        try:
            self.p.look(x=x, y=y, z=z)
        except Exception:  # noqa: BLE001
            pass

    def _walk(self, x, y, z, timeout: float, tol: float, sprint: bool = True) -> dict:
        self.moved = True
        try:
            return self.p.walk_to(x, y, z, timeout=max(1.0, timeout), tolerance=tol, sprint=sprint)
        except PilotError as e:
            return {"error": str(e)}

    def _move(self, **kw) -> None:
        self.moved = True
        try:
            self.p.move(**kw)
        except PilotError:
            pass

    def goto(self, target: tuple[float, float, float], tol: float = 1.5, timeout: float | None = None,
             sprint: bool = True, route: bool = True) -> tuple[bool, str]:
        """Walk to target (x,y,z) with routing, per-segment timeouts and a stuck-recovery ladder.
        -> (arrived, message)."""
        end = time.time() + min(self.left() - 0.5, timeout if timeout else self.left())
        st = self.state(radius=0)
        if st.get("screen"):
            self.close()
        start_island = self.island(st)
        pos = self.pos(st)
        pts = plan_route(start_island, pos, target) if route else [target]
        total_stuck = 0
        for i, wp in enumerate(pts):
            final = i == len(pts) - 1
            wtol = tol if final else 2.5
            stuck = 0
            while True:
                if time.time() >= end:
                    d = hdist(self.pos(self.state(radius=0)), target)
                    return False, f"ran out of time {d:.0f} blocks short"
                self.check()
                st = self.state(radius=0)
                if self.island(st) != start_island:
                    return False, f"you ended up on {ob.ISLAND_NAMES.get(self.island(st), self.island(st))} (walked into a portal?)"
                if st.get("screen"):
                    self.close()
                cur = self.pos(st)
                d = hdist(cur, wp)
                if d <= wtol:
                    break
                seg = max(2.0, min(14.0, d / 3.5 + 2.0, end - time.time()))
                r = self._walk(wp[0], wp[1], wp[2], timeout=seg, tol=wtol, sprint=sprint)
                st2 = self.state(radius=0)
                new = self.pos(st2)
                if self.island(st2) != start_island:
                    return False, f"you ended up on {ob.ISLAND_NAMES.get(self.island(st2), self.island(st2))}"
                if new[1] < cur[1] - 10:
                    return False, f"fell {cur[1] - new[1]:.0f} blocks; now at ({new[0]:.0f},{new[1]:.0f},{new[2]:.0f})"
                moved = hdist(cur, new)
                progress = d - hdist(new, wp)
                if moved < 0.8 or (progress < 0.5 and not r.get("arrived")):
                    stuck += 1
                    total_stuck += 1
                    if stuck > 5:
                        return False, (f"stuck {hdist(new, target):.0f} blocks from the target at "
                                       f"({new[0]:.0f},{new[1]:.0f},{new[2]:.0f}); something blocks the way - "
                                       f"try another approach, warp, or unstuck")
                    self._recover(stuck, wp, new)
                else:
                    stuck = 0
        cur = self.pos(self.state(radius=0))
        return True, f"arrived ({cur[0]:.0f},{cur[1]:.0f},{cur[2]:.0f})"

    def _recover(self, level: int, wp, cur) -> None:
        """Stuck ladder: jump forward, sidestep left/right, back off and swing wide."""
        self.look_at(wp[0], cur[1] + 1.6, wp[2])
        if level == 1:
            self._move(forward=1, jump=True, sprint=True, seconds=0.7)
        elif level == 2:
            self._move(forward=0.6, strafe=-1, jump=True, seconds=1.0)
        elif level == 3:
            self._move(forward=0.6, strafe=1, jump=True, seconds=1.4)
        elif level == 4:
            self._move(forward=-1, seconds=0.8)
            self._move(strafe=-1, seconds=1.2)
            self._move(forward=1, jump=True, seconds=1.0)
        else:
            self._move(forward=-1, seconds=0.8)
            self._move(strafe=1, seconds=2.0)
            self._move(forward=1, jump=True, seconds=1.0)

    # ---------------------------------------------------------- entities
    def find_entities(self, st: dict, query: str | None, kinds=("mob",), threshold: float = 0.55) -> list[dict]:
        groups = classify(st)
        out = []
        for k in kinds:
            for e in groups.get(k, []):
                if not query:
                    out.append((1.0, e))
                    continue
                s = max(fuzzy_score(query, entity_name(e)), fuzzy_score(query, clean(e.get("nameplate") or "")) * 0.95,
                        fuzzy_score(query, (e.get("type") or "").split(":")[-1].replace("_", " ")) * 0.9)
                if s >= threshold:
                    out.append((s, e))
        out.sort(key=lambda se: (-round(se[0], 1), se[1].get("distance") or 0))
        return [e for _, e in out]


# ====================================================================== hub routing
VILLAGE = 98          # the hub village is |x|,|z| <= 98; four avenues meet at the plaza (|x|,|z| <= 23)
PLAZA = 23


def _in_village(p) -> bool:
    return abs(p[0]) <= VILLAGE and abs(p[2]) <= VILLAGE


def _in_plaza(p) -> bool:
    return abs(p[0]) <= PLAZA and abs(p[2]) <= PLAZA


def _cardinal(p) -> str:
    return ("E" if p[0] > 0 else "W") if abs(p[0]) >= abs(p[2]) else ("S" if p[2] > 0 else "N")


_DIRV = {"N": (0, -1), "S": (0, 1), "E": (1, 0), "W": (-1, 0)}


def _mouth(d: str, y: float, r: float = 27) -> tuple[float, float, float]:
    vx, vz = _DIRV[d]
    return (0.5 + vx * r, y, 0.5 + vz * r)


def _toward_plaza(p, r: float = 19) -> tuple[float, float, float]:
    m = max(abs(p[0]), abs(p[2]), 1e-6)
    return (p[0] * r / m, p[1], p[2] * r / m)


def _seg_hits_box(a, b, half: float) -> bool:
    for i in range(21):
        t = i / 20
        x, z = a[0] + (b[0] - a[0]) * t, a[2] + (b[2] - a[2]) * t
        if abs(x) < half - 4 and abs(z) < half - 4:
            return True
    return False


def _door_for(island: str, p) -> tuple[float, float, float] | None:
    """If p is inside a hub building, the door anchor of that building (doors face the square)."""
    zn = LAYOUTS.zone_at(island, p)
    if not zn or zn["id"] in ("village", "plaza", "hub"):
        return None
    anchors = LAYOUTS.anchors_world(island, p)
    for key in (f"door.{zn['id']}", f"door.{zn['id'].replace('_room', '_hall')}"):
        if key in anchors:
            return anchors[key]
    b = zn["box"]
    best = None
    for an, (x, y, z) in anchors.items():
        if an.startswith("door.") and b[0] - 3 <= x <= b[3] + 3 and b[2] - 3 <= z <= b[5] + 3:
            dd = math.hypot(x - p[0], z - p[2])
            if best is None or dd < best[0]:
                best = (dd, (x, y, z))
    return best[1] if best else None


def plan_route(island: str, start, target) -> list[tuple[float, float, float]]:
    """Waypoints to `target`. Straight line except in the hub, where the village's buildings are walked
    round via the plaza and the four avenues, and buildings are entered/left by their doors."""
    target = tuple(target)
    if island != "hub" or hdist(start, target) < 20:
        return [target]
    y = start[1]
    pts: list[tuple] = []
    s_door, t_door = _door_for(island, start), _door_for(island, target)
    if s_door:
        pts.append(s_door)
    s_in, t_in = _in_village(start), _in_village(target)
    if s_in and t_in:
        if not (_in_plaza(start) and _in_plaza(target)):
            if not _in_plaza(start):
                pts.append(_toward_plaza(s_door or start))
            if not _in_plaza(target):
                pts.append(_toward_plaza(t_door or target))
    elif s_in and not t_in:
        d = _cardinal(target)
        if not _in_plaza(start) and _cardinal(start) != d:
            pts.append(_toward_plaza(s_door or start))
        pts += [_mouth(d, y), _mouth(d, y, VILLAGE + 4)]
    elif t_in and not s_in:
        d = _cardinal(start)
        pts += [_mouth(d, y, VILLAGE + 4), _mouth(d, y)]
        if not _in_plaza(target) and _cardinal(target) != d:
            pts.append(_toward_plaza(t_door or target))
    else:
        if _seg_hits_box(start, target, VILLAGE):
            ds, dt = _cardinal(start), _cardinal(target)
            pts += [_mouth(ds, y, VILLAGE + 4), _mouth(ds, y)]
            if dt != ds:
                pts += [_mouth(dt, y), _mouth(dt, y, VILLAGE + 4)]
    if t_door:
        pts.append(t_door)
    pts.append(target)
    # drop waypoints that would make us walk backwards right at the start
    out = []
    for p in pts:
        if out and hdist(out[-1], p) < 3:
            continue
        out.append(p)
    return out


# ====================================================================== item predicates
def _iname(it: dict) -> str:
    return (clean(it.get("name")) + " " + (it.get("id") or "")).lower()


def is_menu_star(it: dict) -> bool:
    return "skyblock menu" in _iname(it) or "sbmenu" in (it.get("customData") or "")


def is_pickaxe(it: dict) -> bool:
    n = _iname(it)
    return any(w in n for w in ("pickaxe", "drill", "gauntlet", "_pickaxe")) and not is_menu_star(it)


def is_axe(it: dict) -> bool:
    n = _iname(it)
    return ("axe" in n or "treecapitator" in n) and "pickaxe" not in n


def is_hoe(it: dict) -> bool:
    n = _iname(it)
    return "hoe" in n or "sickle" in n or "dicer" in n


def is_rod(it: dict) -> bool:
    n = _iname(it)
    return "fishing_rod" in n or re.search(r"\brod\b", n) is not None


ARMOR_WORDS = ("helmet", "chestplate", "leggings", "boots", "cap", "tunic", "pants", "hood")


def is_armor(it: dict) -> bool:
    n = _iname(it)
    return any(w in n for w in ARMOR_WORDS)


def weapon_damage(it: dict) -> float:
    if not it or not it.get("id") or is_menu_star(it):
        return -1
    best = 0.0
    for ln in it.get("lore") or []:
        m = re.search(r"Damage:\s*\+?([\d,.]+)", clean(ln))
        if m:
            best = max(best, _f(m.group(1).replace(",", ""), 0.0) or 0.0)
    n = _iname(it)
    if best == 0.0 and any(w in n for w in ("sword", "blade", "katana", "scythe", "dagger", "axe")):
        best = 5.0
    if "bow" in n and "elbow" not in n:
        best = min(best, 1.0)            # bows need charged right-clicks; melee loop cannot use them
    return best


PROTECT_WORDS = ("pickaxe", "axe", "hoe", "sword", "bow", "rod", "minion", "skyblock menu", "helmet",
                 "chestplate", "leggings", "boots", "drill", "talisman", "ring", "artifact", "scroll",
                 "sack", "backpack", "pet", "blade", "wand", "gauntlet", "sickle", "necklace", "cloak",
                 "belt", "bracelet", "gloves")


def is_protected(sl: dict) -> bool:
    n = _iname(sl)
    if any(w in n for w in PROTECT_WORDS):
        return True
    lore = " ".join(clean(x) for x in (sl.get("lore") or [])[-3:]).upper()
    return any(r in lore for r in ("EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL"))


# ====================================================================== tools: perception
NOTABLE_BLOCKS = ("_ore", "_log", "wheat", "carrots", "potatoes", "sugar_cane", "pumpkin", "melon", "water")


@tool("look_around", "where am I + who/what is near: players, mobs, NPCs, ores/logs/crops/water, nearby places", {})
def t_look_around(g: Game):
    st = g.state(radius=32)
    pos, island = g.pos(st), g.island(st)
    sb = parse_sidebar((st.get("sidebar") or {}).get("lines") or [])
    zone = sb.get("zone") or (LAYOUTS.zone_at(island, pos) or {}).get("name") or "?"
    out = [f"{ob.ISLAND_NAMES.get(island, island)} > {zone} ({pos[0]:.0f},{pos[1]:.0f},{pos[2]:.0f})"]
    grp = classify(st)
    if grp["player"]:
        out.append("players: " + ", ".join(f"{e.get('name')} {e['distance']:.0f}m" for e in grp["player"][:5]))
    if grp["mob"]:
        agg: dict[str, list] = {}
        for e in grp["mob"]:
            agg.setdefault(entity_name(e), []).append(e)
        out.append("mobs: " + ", ".join(
            f"{k} x{len(v)} ({v[0]['distance']:.0f}m {compass(v[0]['x'] - pos[0], v[0]['z'] - pos[2])})"
            for k, v in list(agg.items())[:5]))
    if grp["npc"]:
        seen, parts = set(), []
        for e in grp["npc"]:
            nm = entity_name(e)
            if nm not in seen:
                seen.add(nm)
                parts.append(f"{nm} {e['distance']:.0f}m {compass(e['x'] - pos[0], e['z'] - pos[2])}")
        out.append("NPCs: " + ", ".join(parts[:7]))
    found = []
    for pat in NOTABLE_BLOCKS:
        g.check(1)
        try:
            bl = g.p.find_blocks(pat, radius=16, limit=8, exposed=True)
        except PilotError:
            continue
        byid: dict[str, dict] = {}
        for b in bl:
            bid = (b.get("id") or pat).split(":")[-1]
            if "stem" in bid or bid.startswith(("stripped_", "attached_")):
                continue
            byid.setdefault(bid, b)
        for bid, b in list(byid.items())[:2]:
            found.append(f"{bid} {b.get('distance', 0):.0f}m {compass(b['x'] + 0.5 - pos[0], b['z'] + 0.5 - pos[2])}")
    if found:
        out.append("blocks: " + ", ".join(found[:8]))
    near = LAYOUTS.nearby(island, pos, radius=80, n=6)
    if near:
        out.append("places: " + ", ".join(f"{a} {d:.0f}m {dr}" for a, d, dr in near))
    if st.get("screen"):
        out.append("screen open: " + screen_summary(st["screen"], max_items=12, with_lore=False))
    return " | ".join(out)


@tool("wait", "stand still (max 30s); returns chat that arrived meanwhile", {"seconds": "number"}, timeout=35)
def t_wait(g: Game, seconds: float = 5):
    s = max(0.5, min(30.0, _f(seconds, 5.0)))
    n = g.chat_n()
    g.sleep(s)
    new = g.chat_new(n)
    return f"waited {s:.0f}s" + (f"; chat: {' / '.join(short(x, 90) for x in new[-5:])}" if new else "")


# ====================================================================== tools: chat & commands
def clean_chat(text: str) -> str:
    t = " ".join(str(text or "").replace("§", "").split())
    t = t.lstrip("/").strip()
    return t[:200]


@tool("say", "type a line in public chat (≤200 chars)", {"text": "string"}, timeout=10)
def t_say(g: Game, text: str = ""):
    t = clean_chat(text)
    if not t:
        raise MacroFail("nothing to say")
    g.p.chat(t)
    return f"said: {t}"


COMMAND_WHITELIST = {
    "warp", "warps", "travel", "hub", "lobby", "is", "island", "home", "visit", "setspawn", "sbmenu",
    "fasttravel", "ft", "islands", "stats", "calendar", "craft", "workbench", "wb", "recipe", "recipes",
    "rb", "recipebook", "trades", "bz", "bazaar", "ah", "ahs", "bank", "merchants", "buyback", "storage", "ec",
    "sacks", "wardrobe", "bags", "questlog", "sblevel", "fairysouls", "bestiary", "museum", "collection",
    "collections", "skills", "minions", "mining", "hotm", "commissions", "forge", "mines", "lift", "minecart",
    "farming", "fortune", "harvestfair", "foraging", "hotf", "fishing", "seacreatures", "hotspots",
    "fishingbag", "combatzones", "hubmap", "hm", "districts", "portals", "village", "town", "sail", "ship",
    "hubguides", "slayer", "pets", "trophyfish", "viewstash", "pickupstash", "accessories", "potionbag",
    "quiver", "commands", "trade", "coop",
}


@tool("command", "run a whitelisted player command, e.g. \"/warp hub\", \"/is\", \"/questlog\", \"/hubmap\", \"/recipe wooden pickaxe\"",
      {"text": "string"}, timeout=15)
def t_command(g: Game, text: str = ""):
    raw = " ".join(str(text or "").split()).lstrip("/")
    if not raw:
        raise MacroFail("empty command")
    head = raw.split(" ", 1)[0].lower()
    if head not in COMMAND_WHITELIST:
        raise MacroFail(f"/{head} is not allowed; allowed: /warp /hub /is /sbmenu /craft /recipe /questlog /hubmap /village /bz /ah /bank /skills /collection /minions ...")
    if head in ("trade",) and len(raw.split()) < 2:
        raise MacroFail("/trade needs a player name")
    n = g.chat_n()
    g.p.chat("/" + raw[:120])
    s = g.wait_screen(timeout=2.0)
    g.sleep(0.4)
    new = g.chat_new(n)
    res = f"/{raw}"
    if s:
        res += f" -> screen {screen_summary(s, max_items=20)}"
    if new:
        res += f" | chat: {' / '.join(short(x, 100) for x in new[-5:])}"
    if not s and not new:
        res += " -> (no visible response)"
    return res


# ====================================================================== tools: menus
MENU_COMMANDS = {
    "skills": "skills", "your skills": "skills", "collections": "collection", "collection": "collection",
    "recipe book": "recipes", "recipes": "recipes", "crafting": "craft", "crafting table": "craft", "craft": "craft",
    "bazaar": "bz", "auction house": "ah", "auction": "ah", "bank": "bank", "quest log": "questlog",
    "quests": "questlog", "objectives": "questlog", "fast travel": "fasttravel", "warps": "fasttravel",
    "hub map": "hubmap", "map": "hubmap", "village": "village", "village directory": "village",
    "portals": "portals", "minions": "minions", "storage": "storage", "sacks": "sacks", "wardrobe": "wardrobe",
    "bags": "bags", "island": "is menu", "island management": "is menu", "skyblock level": "sblevel",
    "sblevel": "sblevel", "fairy souls": "fairysouls", "bestiary": "bestiary", "museum": "museum",
    "merchants": "merchants", "stats": "stats", "calendar": "calendar", "slayer": "slayer", "hotm": "hotm",
    "heart of the mountain": "hotm", "commissions": "commissions", "forge": "forge", "lift": "lift",
    "district guides": "hubguides", "sea creatures": "seacreatures", "pets": "pets",
}
SBMENU_WORDS = {"skyblock", "skyblock menu", "sbmenu", "menu", "sb menu", "main menu", ""}
_MENU_N = {norm(k): v for k, v in MENU_COMMANDS.items()}


def open_sbmenu(g: Game) -> dict | None:
    g.close()
    st = g.state(radius=0)
    hb = g.hotbar(st)
    slot = 8
    for i, it in enumerate(hb):
        if it and is_menu_star(it):
            slot = i
    try:
        g.select(slot)
        g.p.use(target="auto")
    except PilotError:
        pass
    s = g.wait_screen(lambda s: "skyblock menu" in g.title(s).lower(), 2.5)
    if not s:
        g.close()
        g.p.chat("/sbmenu")
        s = g.wait_screen(lambda s: "skyblock menu" in g.title(s).lower(), 3.0)
    return s


@tool("open_menu", "open a menu: \"skyblock\" (the SkyBlock Menu), skills, collections, recipe book, crafting, quest log, fast travel, hub map, village, bank, bazaar, minions, island ... -> shows its buttons",
      {"name": "string"}, timeout=20)
def t_open_menu(g: Game, name: str = "skyblock"):
    q = norm(name)
    if q in SBMENU_WORDS or q in ("skyblock menu",):
        s = open_sbmenu(g)
        if not s:
            raise MacroFail("the SkyBlock Menu did not open (hotbar slot 9 / /sbmenu)")
        return "opened " + screen_summary(s, with_lore=False)
    cmd = _MENU_N.get(q)
    if cmd is None:
        hit, _ = best_match(q, _MENU_N.keys(), threshold=0.75)
        cmd = _MENU_N.get(hit) if hit else None
    if cmd:
        g.close()
        n = g.chat_n()
        g.p.chat("/" + cmd)
        s = g.wait_screen(timeout=3.0)
        if s:
            return "opened " + screen_summary(s)
        new = g.chat_new(n)
        if new:
            return (False, f"/{cmd}: {' / '.join(short(x, 120) for x in new[-3:])}")
    # fall back: a button on the SkyBlock Menu
    s = open_sbmenu(g)
    if not s:
        raise MacroFail(f"could not open {name!r}")
    sl = g.find_slot(s, name)
    if not sl:
        return (False, f"no menu called {name!r}; the SkyBlock Menu has: {screen_summary(s, with_lore=False)}")
    g.p.click(sl["slot"], 0, "PICKUP")
    s2 = g.wait_screen(lambda x: g.title(x) != g.title(s), 3.0)
    return "opened " + screen_summary(s2 or g.screen())


@tool("click_item", "click a button/item in the open menu by name (fuzzy; also matches your own inventory items in the lower half). button: left|right|shift",
      {"name": "string", "button?": "left|right|shift"}, timeout=15)
def t_click_item(g: Game, item: str = "", button: str = "left"):
    s = g.screen()
    if not s:
        raise MacroFail("no menu is open (open_menu or talk_to first)")
    sl = g.find_slot(s, item, container=True) or g.find_slot(s, item, container=False, use_lore=False, threshold=0.7)
    if not sl:
        raise MacroFail(f"no {item!r} in {screen_summary(s, max_items=30, with_lore=False)}")
    b = (button or "left").lower()
    n = g.chat_n()
    before_title = g.title(s)
    if b.startswith("r"):
        g.p.click(sl["slot"], 1, "PICKUP")
    elif b.startswith("s"):
        g.p.click(sl["slot"], 0, "QUICK_MOVE")
    else:
        g.p.click(sl["slot"], 0, "PICKUP")
    g.sleep(0.5)
    s2 = g.screen()
    new = g.chat_new(n)
    res = f"clicked {clean(sl.get('name'))!r}"
    if s2 is None:
        res += " -> menu closed"
    elif g.title(s2) != before_title:
        res += f" -> {screen_summary(s2)}"
    else:
        res += f" (same menu '{before_title}')"
    if new:
        res += f" | chat: {' / '.join(short(x, 110) for x in new[-4:])}"
    carried = (s2 or {}).get("carried")
    if carried and carried.get("id"):
        res += f" | holding {clean(carried.get('name'))} on the cursor (click an empty slot to put it down)"
    return res


@tool("close_menu", "close the open menu/screen", {}, timeout=8)
def t_close_menu(g: Game):
    s = g.screen()
    if not s:
        return "no menu was open"
    g.close()
    return f"closed '{g.title(s)}'"


# ====================================================================== tools: movement
@tool("walk_to", "walk to coordinates {x,y,z} or a named place {place:\"npc.banker\"|\"Farm Merchant\"|\"graveyard\"|\"portal.hub\"|\"coal mine\"} on this island (routes round hub buildings, recovers when stuck)",
      {"x?": "number", "y?": "number", "z?": "number", "place?": "string", "timeout?": "seconds (≤60)"}, timeout=45)
def t_walk_to(g: Game, x=None, y=None, z=None, place: str | None = None, timeout: float | None = None):
    st = g.state(radius=0)
    island, pos = g.island(st), g.pos(st)
    label = None
    if place is None and isinstance(x, str) and _f(x) is None:
        place, x = x, None
    if x is not None and z is not None and _f(x) is not None:
        target = (_f(x), _f(y, pos[1]), _f(z))
        label = f"({target[0]:.0f},{target[1]:.0f},{target[2]:.0f})"
    elif place:
        hit = LAYOUTS.find_place(island, str(place), pos)
        if not hit:
            near = LAYOUTS.nearby(island, pos, radius=200, n=8)
            raise MacroFail(f"no place called {place!r} on {ob.ISLAND_NAMES.get(island, island)}; known nearby: "
                            + ", ".join(f"{a} {d:.0f}m {dr}" for a, d, dr in near))
        label, target = hit[0], hit[1]
    else:
        raise MacroFail("walk_to needs x,y,z or place")
    d0 = hdist(pos, target)
    if d0 > 450:
        raise MacroFail(f"{label} is {d0:.0f} blocks away - warp instead")
    t = min(MAX_TIMEOUT, _f(timeout, None) or DEFAULT_TIMEOUT, g.left() - 1)
    g.note = f"was walking to {label}"
    ok, msg = g.goto(target, tol=1.8, timeout=t)
    st = g.state(radius=0)
    zone = parse_sidebar((st.get("sidebar") or {}).get("lines") or []).get("zone")
    where = f"; zone: {zone}" if zone else ""
    if ok:
        return f"reached {label} ({d0:.0f} blocks){where}"
    return (False, f"walk to {label}: {msg}{where}")


WARP_ALIASES = {
    "island": "home", "my island": "home", "private island": "home", "home": "home", "private_island": "home",
    "hub": "hub", "village": "hub", "spawn": "hub", "lobby": "hub",
    "barn": "barn", "farming islands": "barn", "park": "park", "gold mine": "gold_mine", "gold": "gold_mine",
    "deep caverns": "deep_caverns", "deep": "deep_caverns", "dwarven mines": "dwarven_mines",
    "spiders den": "spiders_den", "spider den": "spiders_den", "spiders": "spiders_den",
    "end": "the_end", "the end": "the_end", "garden": "garden", "bayou": "backwater_bayou",
    "backwater bayou": "backwater_bayou", "dungeon hub": "dungeon_hub", "dungeons": "dungeon_hub",
    "crimson isle": "crimson_isle", "mushroom desert": "desert", "desert": "desert",
    "crystal hollows": "crystal_hollows", "glacite tunnels": "glacite_tunnels",
    # hub districts (unlock by walking in once)
    "graveyard": "hub_graveyard", "coal mine": "hub_coal_mine", "mine": "hub_coal_mine",
    "mining district": "hub_mining_district", "farm": "hub_farm", "hub farm": "hub_farm",
    "forest": "hub_forest", "foraging camp": "hub_foraging_camp", "fishing outpost": "hub_fishing_outpost",
    "lake": "hub_fishing_outpost", "fishing": "hub_fishing_outpost", "ruins": "hub_ruins",
    "colosseum": "hub_colosseum", "wilderness": "hub_wilderness", "carnival": "hub_carnival",
    "summit": "hub_summit", "mountain": "hub_summit", "weather station": "hub_summit",
    "portal plaza": "hub_portal_plaza", "dark auction": "da", "crypt": "crypt", "crypts": "crypt",
    "wizard tower": "wizard", "wizard": "wizard", "castle": "castle", "old keep": "castle", "museum": "museum",
}
_WARP_N = {norm(k): v for k, v in WARP_ALIASES.items()}
HUB_DISTRICT_ZONE = {"hub_graveyard": "graveyard", "hub_coal_mine": "coal_mine", "hub_mining_district": "mining_district",
                     "hub_farm": "farm", "hub_forest": "forest", "hub_foraging_camp": "foraging_camp",
                     "hub_fishing_outpost": "fishing_outpost", "hub_ruins": "ruins", "hub_colosseum": "colosseum",
                     "hub_wilderness": "wilderness", "hub_carnival": "carnival", "hub_summit": "summit",
                     "hub_portal_plaza": "portal_plaza", "da": "dark_auction", "crypt": "crypt",
                     "wizard": "wizard_tower", "castle": "castle", "museum": "museum"}
_FAIL_RE = re.compile(r"(requires|unknown destination|cannot|can't|not unlocked|locked|you need|not open yet|leads nowhere)", re.I)


def warp_id(dest: str) -> str:
    raw = str(dest or "").strip().lower()
    if re.fullmatch(r"[a-z0-9_]+", raw) and norm(raw) not in _WARP_N and norm(raw.replace("_", " ")) not in _WARP_N:
        return raw                                   # already an id, e.g. "hub_graveyard"
    q = norm(raw)
    if q in _WARP_N:
        return _WARP_N[q]
    if q.startswith("the ") and q[4:] in _WARP_N:
        return _WARP_N[q[4:]]
    return q.replace(" ", "_")


@tool("warp", "fast-travel: hub, island (home), gold_mine, barn, park, deep_caverns, spiders_den, ... or a discovered hub district (graveyard, coal mine, farm, forest, fishing outpost, ...)",
      {"dest": "string"}, timeout=40)
def t_warp(g: Game, dest: str = "hub"):
    wid = warp_id(str(dest))
    st = g.state(radius=0)
    island0, pos0 = g.island(st), g.pos(st)
    g.close()
    n = g.chat_n()
    cmd = {"hub": "/hub", "home": "/is"}.get(wid, f"/warp {wid}")
    g.p.chat(cmd)
    end = time.time() + min(30, g.left() - 1)
    saw_warping, warping_at = False, 0.0
    while time.time() < end:
        g.sleep(0.5)
        st = g.state(radius=0)
        isl, pos = g.island(st), g.pos(st)
        new = g.chat_new(n)
        if any("warping" in x.lower() for x in new):
            if not saw_warping:
                warping_at = time.time()
            saw_warping = True
        if saw_warping and island0 == "hub" and wid in HUB_DISTRICT_ZONE and time.time() - warping_at > 3:
            return f"warped to {wid} (you were already near its landing point)"
        if isl != island0 or hdist(pos, pos0) > 24 or (saw_warping and hdist(pos, pos0) > 1.5):
            g.sleep(1.0)
            st = g.state(radius=0)
            zone = parse_sidebar((st.get("sidebar") or {}).get("lines") or []).get("zone") or "?"
            return f"warped to {ob.ISLAND_NAMES.get(g.island(st), g.island(st))} > {zone}"
        bad = [x for x in new if _FAIL_RE.search(x)]
        if bad:
            msg = short(bad[-1], 150)
            hint = ""
            zid = HUB_DISTRICT_ZONE.get(wid)
            if zid and ("discover" in msg.lower() or "require" in msg.lower()):
                hp_ = LAYOUTS.find_place("hub", zid, pos) if isl == "hub" else None
                if hp_:
                    hint = (f" - walk into it once to unlock: walk_to {{place: \"{zid}\"}} "
                            f"({hdist(pos, hp_[1]):.0f} blocks {compass(hp_[1][0] - pos[0], hp_[1][2] - pos[2])})")
                else:
                    hint = " - go to the hub and walk into that district once to unlock its warp"
            return (False, f"{cmd}: {msg}{hint}")
    if saw_warping:
        return (False, f"{cmd}: 'Warping...' but you have not moved yet (island still loading?) - wait a few seconds and look_around")
    return (False, f"{cmd}: no response; try open_menu('fast travel')")


@tool("unstuck", "escape a hole/water/corner: jump out, sidestep, and as a last resort warp to this island's spawn", {}, timeout=30)
def t_unstuck(g: Game):
    g.close()
    st = g.state(radius=0)
    p0 = g.pos(st)
    for kw in ({"forward": 1, "jump": True, "seconds": 1.2}, {"forward": -1, "jump": True, "seconds": 1.0},
               {"strafe": 1, "jump": True, "seconds": 1.0}, {"strafe": -1, "jump": True, "seconds": 1.0}):
        g._move(**kw)
        p1 = g.pos(g.state(radius=0))
        if p1[1] > p0[1] + 0.9 or hdist(p0, p1) > 3:
            return f"moved free to ({p1[0]:.0f},{p1[1]:.0f},{p1[2]:.0f})"
    isl = g.island(st)
    cmd = "/is" if isl == "private_island" else "/hub" if isl == "hub" else f"/warp {isl}"
    g.p.chat(cmd)
    g.sleep(3)
    p2 = g.pos(g.state(radius=0))
    return f"could not climb out; used {cmd}, now at ({p2[0]:.0f},{p2[1]:.0f},{p2[2]:.0f})"


# ====================================================================== tools: NPCs
def _npc_open(g: Game, npc: str) -> tuple[dict | None, list[str], str]:
    """Find an NPC (walking to its anchor if needed), right-click it, follow its dialogue until a menu
    opens. -> (screen or None, dialogue lines, npc display name)."""
    st = g.state(radius=40)
    pos, island = g.pos(st), g.island(st)
    cands = g.find_entities(st, npc, kinds=("npc", "creature"), threshold=0.6)
    if not cands:
        hit = LAYOUTS.find_place(island, npc, pos)
        if hit:
            g.note = f"was walking to {npc}"
            ok, msg = g.goto(hit[1], tol=2.5, timeout=min(g.left() - 8, 40))
            st = g.state(radius=16)
            cands = g.find_entities(st, npc, kinds=("npc", "creature"), threshold=0.55)
            if not cands and not ok:
                raise MacroFail(f"could not reach {npc}: {msg}")
        if not cands:
            vis = sorted({entity_name(e) for e in classify(st)["npc"]})[:10]
            raise MacroFail(f"no NPC like {npc!r} here" + (f"; NPCs nearby: {', '.join(vis)}" if vis else "")
                            + (f" (check the hub: open_menu('village') lists buildings)" if island == "hub" else ""))
    e = cands[0]
    name = entity_name(e)
    if e.get("distance", 99) > 3.2:
        ok, msg = g.goto((e["x"], e["y"], e["z"]), tol=2.4, timeout=min(g.left() - 6, 30), route=e["distance"] > 20)
        st = g.state(radius=12)
        again = [x for x in g.find_entities(st, npc, kinds=("npc", "creature"), threshold=0.55)]
        if again:
            e = again[0]
        if e.get("distance", 99) > 4.5:
            raise MacroFail(f"could not get next to {name} ({e.get('distance', 0):.0f}m away): {msg}")
    g.close()
    n = g.chat_n()
    dialogue: list[str] = []

    def click():
        try:
            g.look_at(e["x"], (e.get("centerY") or e["y"] + 1.0), e["z"])
            g.p.use(target="entity", entity=e["id"])
        except PilotError:
            g.p.use(target="auto")
    click()
    s = g.wait_screen(timeout=2.0)
    clicks, last_new, quiet_since = 1, 0, time.time()
    end = time.time() + min(14, max(2, g.left() - 2))
    while not s and time.time() < end:
        new = g.chat_new(n)
        if len(new) != last_new:
            last_new, quiet_since = len(new), time.time()
        elif time.time() - quiet_since > 2.2 and clicks < 3:
            click()           # dialogue finished without a menu: talk again (first click = intro)
            clicks += 1
            quiet_since = time.time()
        s = g.wait_screen(timeout=0.6)
    dialogue = [x for x in g.chat_new(n)]
    return s, dialogue, name


def _dialogue_text(lines: list[str], limit: int = 4) -> str:
    said = [re.sub(r"^\[NPC\]\s*", "", x) for x in lines if not x.lower().startswith(("you ", "warping"))]
    return " / ".join(short(x, 110) for x in said[-limit:])


@tool("talk_to", "walk to an NPC by name (e.g. \"Gerald\", \"Banker\", \"Farm Merchant\", \"Town Crier Hollis\") and right-click it; returns what it said and the menu it opened",
      {"npc": "string"}, timeout=45)
def t_talk_to(g: Game, npc: str = ""):
    if not npc:
        raise MacroFail("talk_to needs an npc name")
    s, dialogue, name = _npc_open(g, str(npc))
    res = f"talked to {name}"
    d = _dialogue_text(dialogue)
    if d:
        res += f": \"{d}\""
    res += f" | menu: {screen_summary(s)}" if s else " (no menu opened)"
    return res


MERCHANT_WORDS = ("merchant", "smith", "forger", "adventurer", "builder", "bartender", "librarian",
                  "operator", "alchemist", "armorsmith", "weaponsmith", "trader", "seller", "vendor", "peddler")


def _is_shop(s: dict | None) -> bool:
    if not s:
        return False
    names = {clean(sl.get("name")).lower() for sl in (s.get("slots") or [])}
    return bool(names & {"sell items", "sell item", "buyback"}) or any("click to trade" in clean(x).lower()
                                                                     for sl in (s.get("slots") or [])
                                                                     for x in (sl.get("lore") or [])[-4:])


def _open_any_merchant(g: Game, npc: str | None) -> dict:
    s = g.screen()
    if _is_shop(s) and (not npc or fuzzy_score(npc, g.title(s)) >= 0.6):
        return s
    if npc:
        s, _, name = _npc_open(g, npc)
        if not _is_shop(s):
            raise MacroFail(f"{name} did not open a shop" + (f" (opened '{g.title(s)}')" if s else ""))
        return s
    st = g.state(radius=40)
    merchants = [e for e in classify(st)["npc"] if any(w in entity_name(e).lower() for w in MERCHANT_WORDS)]
    if not merchants:
        raise MacroFail("no merchant nearby. Hub merchants: Farm Merchant (Farm, SE), Mine Merchant (Mining District, NE), "
                        "Lumber Merchant (Foraging Camp, W), Weaponsmith/Armorsmith (village west), Fish Merchant (Fishing Outpost, S), Builder")
    s, _, name = _npc_open(g, entity_name(merchants[0]))
    if not _is_shop(s):
        raise MacroFail(f"{name} did not open a shop")
    return s


def _parse_cost(sl: dict) -> str | None:
    lore = [clean(x) for x in (sl.get("lore") or []) if clean(x)]
    for i, x in enumerate(lore[:-1]):
        if x.lower() == "cost":
            return lore[i + 1]
    return None


@tool("buy_from_npc", "buy n of an item from a merchant NPC's shop (walks there and opens it if needed)",
      {"npc": "string", "item": "string", "n?": "integer (bundles, default 1)"}, timeout=50)
def t_buy_from_npc(g: Game, npc: str = "", item: str = "", n: int = 1):
    if not item:
        raise MacroFail("buy_from_npc needs an item")
    n = max(1, min(64, int(_f(n, 1) or 1)))
    s = _open_any_merchant(g, npc or None)
    sl = g.find_slot(s, item, container=True)
    if not sl:
        nxt = g.find_slot(s, "Next Page", container=True, use_lore=False, threshold=0.8)
        if nxt:
            g.p.click(nxt["slot"], 0, "PICKUP")
            g.sleep(0.5)
            s = g.screen()
            sl = g.find_slot(s, item, container=True)
    if not sl:
        raise MacroFail(f"'{g.title(s)}' doesn't sell {item!r}; it sells: {screen_summary(s, max_items=24)}")
    nm, cost = clean(sl.get("name")), _parse_cost(sl)
    purse0 = g.purse()
    n0 = g.chat_n()
    bought, msgs = 0, []
    for _ in range(n):
        g.check(1)
        g.p.click(sl["slot"], 0, "PICKUP")
        g.sleep(0.35)
        new = g.chat_new(n0)
        n0 = g.chat_n()
        msgs += new
        if any("bought" in x.lower() for x in new):
            bought += 1
        if any(re.search(r"(enough coins|inventory space|daily limit|not in stock)", x, re.I) for x in new):
            break
        if g.screen() is None or (g.title(g.screen()) != g.title(s)):
            break
    purse1 = g.purse()
    err = [x for x in msgs if re.search(r"(enough|limit|stock|won't)", x, re.I)]
    money = f" (purse {purse0:,.0f} -> {purse1:,.0f})" if purse0 is not None and purse1 is not None else ""
    if bought == 0:
        return (False, f"could not buy {nm}{' for ' + cost if cost else ''}: {short(err[-1], 120) if err else 'no confirmation'}{money}")
    return f"bought {nm} x{bought}{' at ' + cost + ' each' if cost else ''}{money}" + (f"; stopped: {short(err[-1], 80)}" if err else "")


@tool("sell_to_npc", "sell items to a merchant (any shop NPC): item=\"Oak Log\" sells those stacks; all=true sells everything except tools/weapons/armor/minions/rare items",
      {"item?": "string", "all?": "bool", "npc?": "string"}, timeout=50)
def t_sell_to_npc(g: Game, item: str | None = None, all: bool = False, npc: str | None = None):  # noqa: A002
    if isinstance(all, str):
        all = all.strip().lower() in ("1", "true", "yes", "all")
    if not item and not all:
        raise MacroFail("say which item to sell, or all=true")
    s = _open_any_merchant(g, npc)
    purse0 = g.purse()
    n0 = g.chat_n()
    sold_names, refused = [], []
    for _ in range(36):
        g.check(2)
        s = g.screen()
        if not s:
            break
        if "sell this item" in g.title(s).lower():           # confirm screen for EPIC+ uniques
            if item:
                c = g.find_slot(s, "Confirm", use_lore=False, threshold=0.8)
                if c:
                    g.p.click(c["slot"], 0, "PICKUP")
                    g.sleep(0.4)
                    continue
            g.close()
            break
        mine = [sl for sl in g.slots(s, container=False) if not is_menu_star(sl)]
        if item:
            mine = [sl for sl in mine if fuzzy_score(item, clean(sl.get("name"))) >= 0.6]
        else:
            mine = [sl for sl in mine if not is_protected(sl)]
        mine = [sl for sl in mine if clean(sl.get("name")) not in refused]
        if not mine:
            break
        sl = mine[0]
        nm = clean(sl.get("name"))
        g.p.click(sl["slot"], 0, "PICKUP")
        g.sleep(0.35)
        new = g.chat_new(n0)
        n0 = g.chat_n()
        if any("won't buy" in x.lower() or "cannot" in x.lower() for x in new):
            refused.append(nm)
        elif any("sold" in x.lower() for x in new):
            sold_names.append(f"{nm} x{sl.get('count') or 1}")
        else:
            refused.append(nm)
    purse1 = g.purse()
    money = (f" for {purse1 - purse0:,.0f} coins (purse {purse1:,.0f})"
             if purse0 is not None and purse1 is not None else "")
    if not sold_names:
        why = f"; merchant refused: {', '.join(refused[:4])}" if refused else ""
        return (False, f"sold nothing{' matching ' + repr(item) if item else ''}{why}")
    return f"sold {', '.join(sold_names[:8])}{money}" + (f"; refused: {', '.join(refused[:3])}" if refused else "")


# ====================================================================== tools: crafting
def _supercraft_slot(g: Game, s: dict | None) -> dict | None:
    for sl in (s or {}).get("slots") or []:
        if clean(sl.get("name")).lower() == "supercraft" and sl.get("container", True):
            return sl
    return None


@tool("craft", "craft an item straight from your inventory (opens /recipe <item> and presses Supercraft), n times",
      {"item": "string", "n?": "integer"}, timeout=40)
def t_craft(g: Game, item: str = "", n: int = 1):
    if not item:
        raise MacroFail("craft needs an item name")
    n = max(1, min(64, int(_f(n, 1) or 1)))
    g.close()
    s = None
    tries = [str(item).strip(), re.sub(r"[^A-Za-z0-9]+", "_", str(item).strip()).upper()]
    nchat = g.chat_n()
    for q in tries:
        g.p.chat(f"/recipe {q}")
        s = g.wait_screen(timeout=3.0)
        if s and not _supercraft_slot(g, s):
            sl = g.find_slot(s, item, container=True, use_lore=False)
            if sl:
                g.p.click(sl["slot"], 0, "PICKUP")
                s = g.wait_screen(lambda x: _supercraft_slot(g, x) is not None, 3.0)
        if s and _supercraft_slot(g, s):
            break
        g.close()
        s = None
    if not s:
        new = g.chat_new(nchat)
        raise MacroFail(f"no recipe found for {item!r}" + (f": {short(new[-1], 120)}" if new else ""))
    before = g.inv_counts()
    for attempt in range(4):
        sc = _supercraft_slot(g, s)
        lore = [clean(x) for x in (sc.get("lore") or []) if clean(x)]
        text = " ".join(lore).lower()
        if "recipe locked" in text:
            lock = next((clean(x) for sl in s.get("slots") or [] if "locked" in clean(sl.get("name")).lower()
                         for x in (sl.get("lore") or [])[:2] if "requires" in clean(x).lower()), "")
            g.close()
            return (False, f"{item}: recipe locked{' - ' + lock if lock else ''}")
        if "missing materials" in text:
            nxt = next((sl for sl in s.get("slots") or [] if clean(sl.get("name")).lower() == "next recipe"), None)
            if nxt and attempt < 3:
                g.p.click(nxt["slot"], 0, "PICKUP")
                g.sleep(0.5)
                s = g.screen()
                if s and _supercraft_slot(g, s):
                    continue
            need = [x for x in lore if re.search(r"\d+\s*/\s*\d+|✔|✖|x\d", x)][:5]
            g.close()
            return (False, f"{item}: missing materials" + (f" ({'; '.join(need)})" if need else ""))
        break
    m = re.search(r"craft max!\s*\((\d+)\)", " ".join(lore), re.I)
    can = int(m.group(1)) if m else None
    n0 = g.chat_n()
    if can is not None and n >= can:
        g.p.click(sc["slot"], 0, "QUICK_MOVE")
        g.sleep(0.6)
    else:
        for _ in range(n):
            g.check(1)
            s = g.screen()
            sc = _supercraft_slot(g, s)
            if not sc:
                break
            g.p.click(sc["slot"], 0, "PICKUP")
            g.sleep(0.35)
    new = g.chat_new(n0)
    g.close()
    after = g.inv_counts()
    crafted = [x for x in new if x.lower().startswith("crafted")]
    delta = g.inv_delta(before, after)
    if not crafted and not delta:
        return (False, f"pressed Supercraft for {item} but nothing changed" + (f": {short(new[-1], 100)}" if new else ""))
    return f"{'; '.join(crafted[-2:]) or 'crafted ' + item}" + (f" ({delta})" if delta else "")


# ====================================================================== tools: equipment
@tool("equip", "hold an item from your hotbar/inventory by name (armor gets worn)", {"item": "string"}, timeout=20)
def t_equip(g: Game, item: str = ""):
    if not item:
        raise MacroFail("equip needs an item name")
    st = g.state(radius=0)
    allitems = [it for it in (st.get("inventory") or []) if it.get("id") and not is_menu_star(it)]
    hit, _ = best_match(item, allitems, key=lambda it: clean(it.get("name")), threshold=0.6)
    if not hit:
        names = sorted({clean(it.get("name")) for it in allitems})[:15]
        raise MacroFail(f"no {item!r} in your inventory; you have: {', '.join(names) or 'nothing'}")
    nm = clean(hit.get("name"))
    if is_armor(hit):
        g.close()
        g.p.open_inventory()
        s = g.wait_screen(timeout=3)
        if s:
            src = next((sl for sl in s.get("slots") or [] if clean(sl.get("name")) == nm), None)
            if src:
                g.p.click(src["slot"], 0, "QUICK_MOVE")
                g.sleep(0.3)
            g.close()
        eq = g.state(radius=0).get("equipment") or {}
        if any(clean((eq.get(k) or {}).get("name")) == nm for k in ("head", "chest", "legs", "feet")):
            return f"wearing {nm}"
        return (False, f"could not put on {nm}")
    got = g.equip_where(lambda it: clean(it.get("name")) == nm)
    if not got:
        return (False, f"could not move {nm} to the hotbar")
    return f"holding {nm}"


@tool("use_ability", "right-click with the held item (item abilities, travel scrolls); item? = equip it first",
      {"item?": "string"}, timeout=15)
def t_use_ability(g: Game, item: str | None = None):
    if item:
        r = t_equip(g, item)
        if isinstance(r, tuple) and not r[0]:
            return r
    g.close()
    n = g.chat_n()
    held = g.held(g.state(radius=0))
    g.p.use(target="air")
    g.sleep(0.6)
    st = g.state(radius=0)
    new = g.chat_new(n)
    ab = clean((st.get("actionBar") or {}).get("text"))
    s = st.get("screen")
    res = f"used {ob.item_label(held) or 'empty hand'}"
    if s:
        res += f" -> {screen_summary(s)}"
    if new:
        res += f" | chat: {' / '.join(short(x, 100) for x in new[-3:])}"
    elif ab and "❤" not in ab:
        res += f" | {short(ab, 100)}"
    return res


# ====================================================================== tools: combat
def _retreat(g: Game, st: dict, threat: dict | None) -> None:
    pos = g.pos(st)
    if threat:
        ax, az = pos[0] - threat["x"], pos[2] - threat["z"]
    else:
        ax, az = 1.0, 0.0
    m = math.hypot(ax, az) or 1.0
    g.look_at(pos[0] + ax / m * 10, pos[1] + 1.6, pos[2] + az / m * 10)
    g._move(forward=1, sprint=True, jump=True, seconds=2.5)


@tool("fight", "attack mobs by name (e.g. \"Graveyard Zombie\", \"zombie\", or \"\" for nearest hostile) until count kills or seconds pass; selects your best weapon; retreats under 30% HP",
      {"target?": "string", "count?": "integer", "seconds?": "number (≤60, default 30)"}, timeout=45)
def t_fight(g: Game, target: str = "", count: int | None = None, seconds: float | None = None):
    want = int(_f(count, 0) or 0) or None
    dur = min(MAX_TIMEOUT - 2, _f(seconds, None) or (40.0 if want else 30.0), g.left() - 1)
    end = time.time() + dur
    q = "" if norm(target) in ("", "any", "nearest", "mob", "mobs", "hostile", "monster", "anything") else str(target)
    g.close()
    st = g.state(radius=32)
    hb = g.hotbar(st)
    best = max(range(min(8, len(hb))), key=lambda i: weapon_damage(hb[i]) if hb[i] else -1, default=None)
    wname = "fists"
    if best is not None and hb[best] and weapon_damage(hb[best]) > 0:
        g.select(best)
        wname = clean(hb[best].get("name"))
    elif (st.get("player") or {}).get("selectedSlot") == 8:
        g.select(0)
    hp0, mx = g.hp(st)
    hp_prev, taken = hp0, 0.0
    kills, hits = 0, 0
    tgt_id, tgt_last = None, None
    started = time.time()
    while time.time() < end and (want is None or kills < want):
        g.check(0.5)
        st = g.state(radius=32)
        hp, mx = g.hp(st)
        if hp < hp_prev:
            taken += hp_prev - hp
        hp_prev = hp
        if hp <= 0 or any("you died" in clean(c.get("text")).lower() for c in (st.get("chat") or [])[-2:]):
            return (False, f"you died after {kills} kills (lost coins). Get a weapon/armor or fight weaker mobs")
        mobs = g.find_entities(st, q, kinds=("mob",)) if q else classify(st)["mob"]
        ents = {e["id"]: e for e in mobs}
        if mx and hp / mx < 0.3:
            _retreat(g, st, mobs[0] if mobs else None)
            hp2, _ = g.hp(g.state(radius=0))
            return (False, f"retreated at {hp:.0f}/{mx:.0f} HP after killing {kills}{' ' + q if q else ''}; "
                           f"took {taken:.0f} dmg. Heal up (wait) or get better gear")
        if tgt_id is not None and tgt_id not in ents:
            # target gone: dead (or despawned far away)
            if tgt_last and (tgt_last.get("distance", 99) < 8 or (mob_info(tgt_last) or {}).get("hp", 1) <= (mob_info(tgt_last) or {}).get("max_hp", 1) * 0.6):
                kills += 1
            tgt_id, tgt_last = None, None
            continue
        if tgt_id is None:
            if not mobs:
                if kills and time.time() < end - 4:
                    g.sleep(1.5)       # wait for respawns
                    continue
                vis = sorted({entity_name(e) for e in classify(st)["mob"]})[:6]
                if kills:
                    break
                raise MacroFail(f"no {'mob' if not q else repr(q)} within 32 blocks"
                                + (f"; mobs here: {', '.join(vis)}" if vis else "; no mobs here")
                                + ("; in the hub the Graveyard (east, warp graveyard once discovered) has Lv1 zombies"
                                   if g.island(st) == "hub" else ""))
            e = mobs[0]
            tgt_id = e["id"]
        e = ents[tgt_id]
        tgt_last = e
        if e.get("distance", 99) > 3.0:
            g._walk(e["x"], e["y"], e["z"], timeout=min(3.0, max(1.0, end - time.time())), tol=2.4, sprint=True)
            continue
        try:
            r = g.p.attack(entity=tgt_id)
        except PilotError:
            tgt_id = None
            continue
        if r.get("hit") or r.get("attacked") or r.get("onTarget"):
            hits += 1
        cd = int(_f(r.get("cooldownTicks"), 6) or 6)
        g.p.wait(max(2, min(12, cd)))
    st = g.state(radius=32)
    hp, mx = g.hp(st)
    left_mobs = len(g.find_entities(st, q, kinds=("mob",)) if q else classify(st)["mob"])
    name = q or "mobs"
    res = (f"killed {kills} {name} in {time.time() - started:.0f}s with {wname} ({hits} hits), took {taken:.0f} dmg, "
           f"now {hp:.0f}/{mx:.0f} HP")
    if left_mobs:
        res += f"; {left_mobs} more nearby"
    if kills == 0:
        return (False, res + ("; your hits barely hurt - buy a sword (Weaponsmith, village west)" if wname == "fists" else ""))
    return res


# ====================================================================== tools: gathering
ORE_WORDS = {"coal", "iron", "gold", "lapis", "redstone", "emerald", "diamond", "quartz", "nether_quartz"}
CROP_IDS = {"wheat": "wheat", "carrot": "carrots", "carrots": "carrots", "potato": "potatoes", "potatoes": "potatoes",
            "sugar cane": "sugar_cane", "cane": "sugar_cane", "pumpkin": "pumpkin", "melon": "melon",
            "cactus": "cactus", "mushroom": "mushroom", "cocoa": "cocoa", "nether wart": "nether_wart",
            "beetroot": "beetroots", "beetroots": "beetroots"}


_CROP_N = {norm(k): v for k, v in CROP_IDS.items()}


def block_pattern(kind: str, block: str) -> str:
    b = norm(block).replace(" ", "_")
    if kind == "mine":
        if not b or b in ("ore", "any"):
            return "_ore"
        if b == "cobble":
            return "cobblestone"
        if b in ORE_WORDS:
            return f"{b}_ore"
        return b
    if kind == "chop":
        if not b or b in ("log", "wood", "tree", "any"):
            return "_log"
        return b if b.endswith("_log") or b.endswith("_wood") else f"{b}_log"
    if kind == "farm":
        return _CROP_N.get(norm(block), b or "wheat")
    return b


def _skip_block(kind: str, bid: str) -> bool:
    bid = bid.split(":")[-1]
    if "stem" in bid or bid.startswith(("attached_", "potted_")):
        return True
    if kind == "farm" and bid.endswith("_block"):
        return True
    if kind == "chop" and bid.startswith("stripped_"):
        return False
    return False


GATHER_HINTS = {
    "mine": ("Private island: the Quarry (SE of spawn) has stone/coal/iron and regrows. Hub: the Coal Mine "
             "(north-east, through the Mining District). Gold Mine: warp gold_mine (needs a better pickaxe for gold)."),
    "chop": ("Private island: the big oak by the spawn plaza + the wooded rim. Hub: the Forest / Foraging Camp "
             "(west, past the Portal Plaza). The Park: warp park (needs Foraging 1)."),
    "farm": ("Private island: the fenced farm SW of spawn. Hub: the Farm (south-east of the village). "
             "The Barn: warp barn (needs Farming 1). Only fully grown crops pay."),
}


# where each kind of resource lives, per island (area / anchor names from SkyClone's layouts)
RESOURCE_SPOTS = {
    "private_island": {"mine": ["quarry"], "farm": ["farm"], "chop": ["plaza", "house"], "fish": ["dock", "pond"]},
    "hub": {"mine": ["warp.coal_mine", "coal_mine"], "farm": ["warp.farm", "farm"], "chop": ["warp.forest", "forest"],
            "fish": ["fishing.hotspot.1", "warp.fishing_outpost", "pond"]},
}


def resource_spot(g: Game, island: str, kind: str, pos) -> tuple[str, tuple] | None:
    names = list(RESOURCE_SPOTS.get(island, {}).get(kind, []))
    if kind == "mine":
        names += sorted(a for a in LAYOUTS.get(island)["areas"] if a.startswith("ores."))
    anchors = LAYOUTS.anchors_world(island, pos)
    best = None
    for nm in names:
        p = anchors.get(nm) or LAYOUTS.area_center(island, nm, pos)
        if p is None:
            continue
        d = hdist(pos, p)
        if best is None or d < best[0]:
            best = (d, nm, p)
    if best and best[0] > 6:
        return best[1], best[2]
    return None


def _collect_drops(g: Game, max_s: float = 3.0) -> int:
    end = time.time() + min(max_s, max(0.0, g.left() - 2))
    got = 0
    while time.time() < end:
        st = g.state(radius=7)
        items = [e for e in classify(st)["item"] if e.get("type") == "minecraft:item" and e.get("distance", 99) > 1.2]
        if not items:
            break
        e = items[0]
        g._walk(e["x"], e["y"], e["z"], timeout=min(2.0, max(0.5, end - time.time())), tol=0.5)
        got += 1
    return got


def _gather(g: Game, kind: str, block: str, count, seconds) -> tuple[bool, str]:
    want = max(1, min(64, int(_f(count, 5) or 5)))
    dur = min(MAX_TIMEOUT - 2, _f(seconds, None) or DEFAULT_TIMEOUT - 3, g.left() - 1)
    end = time.time() + dur
    pat = block_pattern(kind, block)
    g.close()
    pred = {"mine": is_pickaxe, "chop": is_axe, "farm": is_hoe}[kind]
    tool_it = g.equip_where(pred)
    tool_name = clean(tool_it.get("name")) if tool_it else None
    if not tool_it:
        st = g.state(radius=0)
        if (st.get("player") or {}).get("selectedSlot") == 8:
            g.select(0)
    before = g.inv_counts()
    done, tries, fails_in_row = 0, 0, 0
    skip: set[tuple] = set()
    last_msg = ""
    radius = 12
    relocated = False
    started = time.time()
    while done < want and time.time() < end:
        g.check(1)
        try:
            blocks = g.p.find_blocks(pat, radius=radius, limit=40, exposed=True)
        except PilotError as e:
            raise MacroFail(f"block search failed: {e}")
        st = g.state(radius=0)
        pos = g.pos(st)
        eye = (pos[0], pos[1] + 1.62, pos[2])
        cands = [b for b in blocks if (b["x"], b["y"], b["z"]) not in skip and not _skip_block(kind, b.get("id", ""))]
        # prefer blocks at our level (reachable) - penalise height difference
        cands.sort(key=lambda b: math.dist(eye, (b["x"] + .5, b["y"] + .5, b["z"] + .5)) + 2.5 * max(0, abs(b["y"] - pos[1]) - 2))
        if not cands:
            if radius < 24:
                radius = 24
                continue
            if not relocated and done == 0:
                relocated = True
                spot = resource_spot(g, g.island(st), kind, pos)
                if spot and time.time() < end - 6:
                    g.note = f"was walking to the {spot[0]}"
                    g.goto(spot[1], tol=3.0, timeout=min(30, end - time.time() - 4))
                    radius = 12
                    continue
            break
        b = cands[0]
        bc = (b["x"] + 0.5, b["y"] + 0.5, b["z"] + 0.5)
        if math.dist(eye, bc) > 4.2:
            ok, _msg = g.goto((bc[0], b["y"], bc[2]), tol=2.2, timeout=min(12, max(2, end - time.time())))
            st = g.state(radius=0)
            pos = g.pos(st)
            eye = (pos[0], pos[1] + 1.62, pos[2])
            if math.dist(eye, bc) > 4.6:
                skip.add((b["x"], b["y"], b["z"]))
                continue
        tries += 1
        n0 = g.chat_n()
        try:
            hold = {"mine": 7.0, "chop": 4.0, "farm": 1.5}[kind]
            r = g.p.mine_block_at(b["x"], b["y"], b["z"], max_seconds=min(hold, max(1.0, end - time.time())))
            g.moved = True
        except PilotError as e:
            skip.add((b["x"], b["y"], b["z"]))
            last_msg = short(str(e), 100)
            fails_in_row += 1
            if fails_in_row >= 6:
                break
            continue
        if r.get("broken"):
            done += 1
            fails_in_row = 0
            if done % 3 == 0:
                _collect_drops(g, 2.0)
            continue
        skip.add((b["x"], b["y"], b["z"]))
        fails_in_row += 1
        ab = clean((g.state(radius=0).get("actionBar") or {}).get("text"))
        flash = ab if ("breaking power" in ab.lower() or "growing" in ab.lower() or "need" in ab.lower()) else ""
        chat = [x for x in g.chat_new(n0) if re.search(r"(can't|cannot|need|requires|growing)", x, re.I)]
        msg = flash or (chat[-1] if chat else "")
        if "breaking power" in msg.lower():
            g.note = msg
            return False, f"{msg} (you have {tool_name or 'no tool'}). Mine weaker blocks or buy a better pickaxe (Mine Merchant / Iron Forger)"
        if msg:
            last_msg = msg
        if "growing" in msg.lower():
            fails_in_row = 0                        # unripe crop: skip it, keep looking
            continue
        if fails_in_row >= 3 and done == 0:
            return False, (f"the {pat.strip('_')} here won't break{(': ' + last_msg) if last_msg else ' (protected area?)'}. "
                           + GATHER_HINTS[kind])
    _collect_drops(g, 3.0)
    after = g.inv_counts()
    word = {"mine": None, "chop": "log", "farm": None}[kind]
    delta = g.inv_delta(before, after, word) if word else g.inv_delta(before, after)
    verb = {"mine": "mined", "chop": "chopped", "farm": "harvested"}[kind]
    res = f"{verb} {done} {pat.strip('_')} in {time.time() - started:.0f}s"
    if tool_name:
        res += f" with {tool_name}"
    if delta:
        res += f" ({delta})"
    if done == 0:
        why = f": {last_msg}" if last_msg else ""
        return False, f"found no breakable {pat.strip('_')} within 24 blocks{why}. {GATHER_HINTS[kind]}"
    if done < want:
        res += f"; ran out of reachable {pat.strip('_')} nearby" if time.time() < end else "; out of time"
    if kind == "mine" and not tool_name:
        res += "; you have no pickaxe"
    return True, res


@tool("mine", "mine blocks/ores (e.g. \"coal\", \"stone\", \"cobblestone\", \"iron\") with your best pickaxe, walking to each",
      {"block": "string", "count?": "integer (default 5)", "seconds?": "number"}, timeout=45)
def t_mine(g: Game, block: str = "stone", count: int = 5, seconds: float | None = None):
    return _gather(g, "mine", block, count, seconds)


@tool("chop", "chop logs (\"oak\", \"birch\", \"log\" = any) with your axe", {"block?": "string", "count?": "integer", "seconds?": "number"},
      timeout=45)
def t_chop(g: Game, block: str = "log", count: int = 5, seconds: float | None = None):
    return _gather(g, "chop", block, count, seconds)


@tool("farm", "harvest grown crops (\"wheat\", \"carrot\", \"potato\", \"sugar cane\", \"pumpkin\", \"melon\")",
      {"block": "string", "count?": "integer", "seconds?": "number"}, timeout=45)
def t_farm(g: Game, block: str = "wheat", count: int = 10, seconds: float | None = None):
    return _gather(g, "farm", block, count, seconds)


# ====================================================================== tools: fishing
def _bobber(st: dict) -> dict | None:
    bs = [e for e in st.get("entities") or [] if e.get("type") == "minecraft:fishing_bobber"]
    bs.sort(key=lambda e: e.get("distance") or 0)
    return bs[0] if bs else None


def _bite(st: dict, bob: dict) -> bool:
    for e in st.get("entities") or []:
        if e.get("type") in ("minecraft:text_display", "minecraft:armor_stand"):
            txt = clean(e.get("text") or e.get("customName") or e.get("nameplate") or "")
            if "!!!" in txt and math.dist((e["x"], e["y"], e["z"]), (bob["x"], bob["y"], bob["z"])) < 2.5:
                return True
    return False


@tool("fish", "fish in nearby water for N seconds: casts, waits for the '!!!' bite, reels, recasts. Needs a fishing rod",
      {"seconds?": "number (≤60, default 40)"}, timeout=50)
def t_fish(g: Game, seconds: float = 40):
    dur = min(MAX_TIMEOUT - 2, _f(seconds, 40.0) or 40.0, g.left() - 2)
    end = time.time() + dur
    g.close()
    rod = g.equip_where(is_rod)
    if not rod:
        raise MacroFail("no fishing rod. Fisher Gideon at the hub Fishing Outpost (south) gives one free; the Fish Merchant sells one (50 coins)")
    water = []
    for attempt in range(2):
        st = g.state(radius=0)
        pos = g.pos(st)
        try:
            water = g.p.find_blocks("water", radius=16, limit=60, exposed=True)
        except PilotError:
            water = []
        water = [w for w in water if 2.5 <= math.dist((pos[0], pos[2]), (w["x"] + .5, w["z"] + .5)) <= 12 and w["y"] <= pos[1] + 1]
        if water or attempt:
            break
        spot = resource_spot(g, g.island(st), "fish", pos)
        if not spot:
            break
        g.goto(spot[1], tol=2.5, timeout=min(25, g.left() - 12))
        end = max(end, time.time() + min(20, g.left() - 2))
    if not water:
        raise MacroFail("no open water within 12 blocks. Fish at the hub lake (Fishing Outpost, south; 3 hotspots) or warp backwater_bayou")
    water.sort(key=lambda w: abs(math.dist((pos[0], pos[2]), (w["x"] + .5, w["z"] + .5)) - 6))
    w = water[0]
    aim = (w["x"] + 0.5, w["y"] + 1.0, w["z"] + 0.5)
    before = g.inv_counts()
    n0 = g.chat_n()
    casts = bites = 0
    creatures: list[str] = []

    def cast() -> dict | None:
        nonlocal casts
        g.look_at(*aim)
        g.p.use(target="auto")
        casts += 1
        g.sleep(1.5)
        return _bobber(g.state(radius=24))

    bob = cast()
    if not bob:
        aim = (aim[0], aim[1] + 2.0, aim[2])
        bob = cast()
    cast_at = time.time()
    while time.time() < end:
        g.check(1)
        st = g.state(radius=24)
        b = _bobber(st)
        if b is None or time.time() - cast_at > 32:
            if b is not None:
                g.p.use(target="auto")       # reel the stale cast in
                g.sleep(0.4)
            if time.time() > end - 3:
                break
            bob = cast()
            cast_at = time.time()
            continue
        if _bite(st, b):
            g.p.use(target="auto")
            bites += 1
            g.sleep(0.8)
            new = g.chat_new(n0)
            n0 = g.chat_n()
            for x in new:
                if re.search(r"(bit|appeared|emerged|sea creature|surfaced|double hook)", x, re.I):
                    creatures.append(short(x, 80))
            if creatures and any(m for m in classify(g.state(radius=10))["mob"]):
                break                       # a sea creature is up: let the model decide to fight
            if time.time() < end - 3:
                bob = cast()
                cast_at = time.time()
            continue
        time.sleep(0.25)
    st = g.state(radius=24)
    if _bobber(st):
        g.p.use(target="auto")
        g.sleep(0.3)
    after = g.inv_counts()
    delta = g.inv_delta(before, after)
    res = f"fished {dur:.0f}s: {bites} bites from {casts} casts" + (f" ({delta})" if delta else "")
    if creatures:
        mobs = classify(g.state(radius=12))["mob"]
        res += f"; SEA CREATURE: {creatures[-1]}" + (f" - {entity_name(mobs[0])} is next to you, fight it!" if mobs else "")
    if bites == 0:
        return (False, res + "; nothing bit - try other water or longer")
    return res


# ====================================================================== tools: minions
@tool("place_minion", "place a minion from your inventory on one of the free minion pads of YOUR island (east terrace)", {}, timeout=45)
def t_place_minion(g: Game):
    st = g.state(radius=0)
    if g.island(st) != "private_island":
        raise MacroFail("minions go on your own island - warp island first")
    it = g.equip_where(lambda it: "minion" in _iname(it))
    if not it:
        raise MacroFail("you have no minion item (the Cobblestone Minion I comes with every new profile)")
    pos = g.pos(st)
    anchors = LAYOUTS.anchors_world("private_island", pos)
    pads = [anchors[k] for k in sorted(anchors) if k.startswith("minion.pad.")]
    st = g.state(radius=48)
    stands = [e for e in st.get("entities") or [] if e.get("type") == "minecraft:armor_stand"]
    free = [p for p in pads if not any(math.dist((p[0], p[2]), (e["x"], e["z"])) < 1.6 for e in stands)]
    if not free:
        raise MacroFail("all five minion pads are taken")
    pad = free[0]
    ok, msg = g.goto(pad, tol=2.5, timeout=min(30, g.left() - 6))
    if not ok:
        raise MacroFail(f"could not reach a minion pad: {msg}")
    bx, by, bz = math.floor(pad[0]), int(pad[1]) - 1, math.floor(pad[2])
    n = g.chat_n()
    g.look_at(bx + 0.5, by + 1.0, bz + 0.5)
    try:
        g.p.call("use", {"target": "block", "x": bx, "y": by, "z": bz, "hand": "main"})
    except PilotError:
        g.p.use(target="auto")
    g.sleep(1.0)
    new = g.chat_new(n)
    st = g.state(radius=6)
    placed = any(e.get("type") == "minecraft:armor_stand" and math.dist((e["x"], e["z"]), (pad[0], pad[2])) < 2.5
                 for e in st.get("entities") or [])
    if placed or any("placed" in x.lower() for x in new):
        return f"placed {clean(it.get('name'))} on a minion pad" + (f": {short(new[-1], 100)}" if new else "")
    return (False, f"minion not placed" + (f": {short(new[-1], 120)}" if new else " (no response)"))


@tool("collect_minions", "on your island: open each minion and press Collect All", {}, timeout=50)
def t_collect_minions(g: Game):
    st = g.state(radius=48)
    if g.island(st) != "private_island":
        raise MacroFail("go to your island first (warp island)")
    stands = sorted([e for e in st.get("entities") or [] if e.get("type") == "minecraft:armor_stand"
                     and ("minion" in clean(e.get("nameplate") or "").lower() or not e.get("text"))],
                    key=lambda e: e.get("distance") or 0)
    before = g.inv_counts()
    visited = 0
    for e in stands[:5]:
        g.check(4)
        if e.get("distance", 99) > 3.5:
            ok, _ = g.goto((e["x"], e["y"], e["z"]), tol=2.2, timeout=min(12, g.left() - 4))
        g.close()
        try:
            g.p.use(target="entity", entity=e["id"])
        except PilotError:
            continue
        s = g.wait_screen(timeout=2.5)
        if not s:
            continue
        visited += 1
        sl = g.find_slot(s, "Collect All", use_lore=False, threshold=0.8)
        if sl:
            g.p.click(sl["slot"], 0, "PICKUP")
            g.sleep(0.4)
        g.close()
    if not visited:
        return (False, "no minion menus opened (no minions placed nearby?)")
    delta = g.inv_delta(before, g.inv_counts())
    return f"collected from {visited} minion(s)" + (f": {delta}" if delta else " (they were empty)")


# ====================================================================== tools: bazaar
@tool("bazaar", "Bazaar (needs SkyBlock Level 7, at the hub Bazaar): action = buy|sell|sell_inventory; item; n",
      {"action": "buy|sell|sell_inventory", "item?": "string", "n?": "integer"}, timeout=40)
def t_bazaar(g: Game, action: str = "sell_inventory", item: str | None = None, n: int = 1):
    act = norm(action).replace(" ", "_")
    g.close()
    nchat = g.chat_n()
    g.p.chat("/bz" + (f" {item}" if item and act != "sell_inventory" else ""))
    s = g.wait_screen(timeout=3.0)
    if not s:
        new = g.chat_new(nchat)
        return (False, f"bazaar did not open: {short(new[-1], 140) if new else 'no response'} (it needs SkyBlock Level 7; "
                       f"away from the hub Bazaar NPC it also needs a cookie buff - walk_to place:\"npc.bazaar\" then talk_to Bazaar)")
    if act in ("sell_inventory", "sell_all", "dump"):
        sl = g.find_slot(s, "Sell Inventory Now", use_lore=False, threshold=0.8)
        if not sl:
            return (False, f"no 'Sell Inventory Now' in {screen_summary(s, max_items=16, with_lore=False)}")
        g.p.click(sl["slot"], 0, "PICKUP")
        s = g.wait_screen(lambda x: g.find_slot(x, "Sell Everything", use_lore=False, threshold=0.8) is not None, 3)
        if s:
            g.p.click(g.find_slot(s, "Sell Everything", use_lore=False, threshold=0.8)["slot"], 0, "PICKUP")
            g.sleep(0.6)
        new = [x for x in g.chat_new(nchat) if "bazaar" in x.lower() or "sold" in x.lower()]
        g.close()
        return (bool(new), "; ".join(short(x, 110) for x in new[-3:]) or "nothing sold")
    if not item:
        raise MacroFail("bazaar buy/sell needs an item")
    # search results / group page -> product page
    for _ in range(3):
        if g.find_slot(s, "Buy Instantly", use_lore=False, threshold=0.85):
            break
        sl = g.find_slot(s, item, container=True, use_lore=False)
        if not sl:
            return (False, f"{item!r} not found on the Bazaar: {screen_summary(s, max_items=16, with_lore=False)}")
        g.p.click(sl["slot"], 0, "PICKUP")
        s = g.wait_screen(lambda x: g.title(x) != g.title(s), 3) or g.screen()
    btn = "Buy Instantly" if act.startswith("buy") else "Sell Instantly"
    sl = g.find_slot(s, btn, use_lore=False, threshold=0.85)
    if not sl:
        return (False, f"no {btn} button: {screen_summary(s, max_items=16, with_lore=False)}")
    g.p.click(sl["slot"], 0, "PICKUP")
    s = g.wait_screen(lambda x: "instant" in g.title(x).lower(), 3)
    if not s:
        return (False, "the instant buy/sell page did not open")
    n = int(_f(n, 1) or 1)
    choice = ("Buy only one!" if n <= 1 else "Buy a stack!" if n >= 64 else "Buy only one!") if act.startswith("buy") \
        else "Sell your inventory!"
    reps = 1 if (not act.startswith("buy") or n >= 64) else min(n, 16)
    for _ in range(reps):
        g.check(1)
        s = g.screen()
        sl = g.find_slot(s, choice, use_lore=False, threshold=0.8)
        if not sl:
            break
        g.p.click(sl["slot"], 0, "PICKUP")
        g.sleep(0.4)
    new = [x for x in g.chat_new(nchat) if "bazaar" in x.lower() or "bought" in x.lower() or "sold" in x.lower()
           or "enough" in x.lower()]
    g.close()
    return (any("bought" in x.lower() or "sold" in x.lower() for x in new),
            "; ".join(short(x, 110) for x in new[-3:]) or "no confirmation")
