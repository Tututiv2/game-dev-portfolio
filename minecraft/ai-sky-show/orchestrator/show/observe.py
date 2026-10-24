"""What an agent perceives each turn: a compact, token-cheap text digest + an optional screenshot.

    from show.observe import observe, screenshot_block
    digest, chat_n = observe(ctx, since_chat_n=chat_n, extra="Time: 24:10 left")
    blocks.append(screenshot_block(ctx))           # Anthropic base64 PNG block (or None)

`ctx` is the same object the macros get: `.pilot` (pilot.Pilot), `.char` (config.Character),
`.log(msg)`, `.showcontrol_state()` (ShowControl /state/<account> dict, or {}).

Also the shared perception helpers the macros use: colour-code stripping, fuzzy matching, vitals and
sidebar parsing, entity classification (mob / npc / player / label), the island layout index (anchors,
areas, zones read from SkyClone's own generated layouts), and a stdlib-only PNG decoder/encoder for
downscaling screenshots.
"""
from __future__ import annotations

import base64
import difflib
import json
import math
import os
import re
import struct
import tempfile
import threading
import time
import zlib
from pathlib import Path
from typing import Any, Iterable

from .config import SHOW

# ====================================================================== text helpers
_CODES = re.compile(r"[§&](?:x(?:[§&][0-9a-f]){6}|[0-9a-fk-orx#])", re.I)


def clean(s: Any) -> str:
    """Strip §/& colour codes and outer whitespace."""
    return _CODES.sub("", str(s or "")).strip()


def norm(s: Any) -> str:
    s = clean(s).lower().replace("'", "")
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return " ".join(w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w
                    for w in s.split())


def fuzzy_score(query: str, text: str) -> float:
    """0..1 - how well `text` matches `query` (exact 1.0, substring ~0.9, token overlap, difflib)."""
    q, t = norm(query), norm(text)
    if not q or not t:
        return 0.0
    if q == t:
        return 1.0
    best = 0.0
    if q in t:
        best = 0.8 + 0.15 * (len(q) / len(t))
    elif t in q and len(t) >= 4:
        best = 0.6 + 0.2 * (len(t) / len(q))
    qt, tt = set(q.split()), set(t.split())
    if qt:
        best = max(best, 0.75 * len(qt & tt) / len(qt))
    best = max(best, 0.7 * difflib.SequenceMatcher(None, q, t).ratio())
    return round(best, 3)


def best_match(query: str, items: Iterable, key=lambda x: x, threshold: float = 0.55):
    """(item, score) of the best fuzzy match, or (None, best_score)."""
    best, bs = None, 0.0
    for it in items:
        k = key(it)
        texts = k if isinstance(k, (list, tuple)) else [k]
        s = max((fuzzy_score(query, x) for x in texts if x), default=0.0)
        if s > bs:
            best, bs = it, s
    return (best, bs) if bs >= threshold else (None, bs)


def short(s: str, n: int) -> str:
    s = " ".join(str(s).split())
    return s if len(s) <= n else s[: n - 1] + "…"


def fmt_num(v) -> str:
    try:
        v = float(v)
    except (TypeError, ValueError):
        return str(v)
    if abs(v) >= 1e6:
        return f"{v / 1e6:.1f}M"
    if abs(v) >= 1e4:
        return f"{v / 1e3:.1f}k"
    return f"{v:,.0f}" if v == int(v) else f"{v:,.1f}"


def _num(s: str) -> float:
    s = s.replace(",", "").strip()
    mult = 1.0
    if s[-1:].lower() == "k":
        mult, s = 1e3, s[:-1]
    elif s[-1:].lower() == "m":
        mult, s = 1e6, s[:-1]
    elif s[-1:].lower() == "b":
        mult, s = 1e9, s[:-1]
    return float(s) * mult


# ====================================================================== HUD parsing
_HP = re.compile(r"([\d,.]+[kKmM]?)\s*/\s*([\d,.]+[kKmM]?)\s*❤")
_DEF = re.compile(r"([\d,.]+)\s*❈")
_MANA = re.compile(r"([\d,.]+)\s*/\s*([\d,.]+)\s*✎")
_XP = re.compile(r"\+([\d,.]+)\s+([A-Za-z ]+?)\s*\(([^)]*)\)")


def parse_vitals(action_bar: str) -> dict:
    """SkyClone's action bar: '100/100❤     0❈ Defense     100/100✎ Mana' (Defense is replaced for
    2 s by an XP popup '+5 Mining (120/175)')."""
    out: dict = {}
    ab = clean(action_bar)
    m = _HP.search(ab)
    if m:
        try:
            out["hp"], out["max_hp"] = _num(m.group(1)), _num(m.group(2))
        except ValueError:
            pass
    m = _DEF.search(ab)
    if m:
        try:
            out["defense"] = _num(m.group(1))
        except ValueError:
            pass
    m = _MANA.search(ab)
    if m:
        try:
            out["mana"], out["max_mana"] = _num(m.group(1)), _num(m.group(2))
        except ValueError:
            pass
    m = _XP.search(ab)
    if m:
        out["xp_popup"] = f"+{m.group(1)} {m.group(2).strip()} ({m.group(3)})"
    return out


def player_hp(state: dict, sc: dict | None = None) -> tuple[float, float]:
    """(hp, max) in SkyBlock units: action bar, else ShowControl, else vanilla hearts x5."""
    v = parse_vitals(((state or {}).get("actionBar") or {}).get("text") or "")
    if "hp" in v and v.get("max_hp"):
        return v["hp"], v["max_hp"]
    if sc and sc.get("health") is not None and sc.get("maxHealth"):
        return float(sc["health"]), float(sc["maxHealth"])
    p = (state or {}).get("player") or {}
    return float(p.get("health") or 0) * 5, float(p.get("maxHealth") or 20) * 5


_SKIP_SIDEBAR = re.compile(r"^(\d\d/\d\d/\d\d.*|www\..*|skyclone.*|\s*)$", re.I)


def parse_sidebar(lines: list[str]) -> dict:
    out: dict = {"lines": []}
    lines = [clean(x) for x in (lines or [])]
    for i, ln in enumerate(lines):
        if _SKIP_SIDEBAR.match(ln):
            continue
        out["lines"].append(ln)
        low = ln.lower()
        m = re.match(r"(purse|piggy):\s*([\d,.]+)", low)
        if m:
            try:
                out["purse"] = float(m.group(2).replace(",", ""))
            except ValueError:
                pass
        m = re.match(r"bits:\s*([\d,.]+)", low)
        if m:
            out["bits"] = m.group(1)
        if "⏣" in ln:
            out["zone"] = ln.replace("⏣", "").strip()
        if low == "objective" and i + 1 < len(lines):
            out["objective"] = lines[i + 1]
    return out


def island_of(dimension: str | None) -> str:
    """'minecraft:sb_hub' -> 'hub'; anything else -> its path."""
    d = (dimension or "").split(":")[-1]
    return d[3:] if d.startswith("sb_") else d


ISLAND_NAMES = {
    "hub": "Hub", "private_island": "Private Island", "gold_mine": "Gold Mine", "deep_caverns": "Deep Caverns",
    "dwarven_mines": "Dwarven Mines", "crystal_hollows": "Crystal Hollows", "barn": "The Barn",
    "mushroom_desert": "Mushroom Desert", "park": "The Park", "spiders_den": "Spider's Den",
    "the_end": "The End", "crimson_isle": "Crimson Isle", "garden": "The Garden",
    "backwater_bayou": "Backwater Bayou", "dungeon_hub": "Dungeon Hub", "galatea": "The Moonmarsh",
    "glacite_tunnels": "Glacite Tunnels", "rift": "The Rift",
}


def compass(dx: float, dz: float) -> str:
    """8-way direction, north = -z."""
    if abs(dx) < 1e-6 and abs(dz) < 1e-6:
        return "here"
    ang = (math.degrees(math.atan2(dx, -dz)) + 360) % 360
    return ("N", "NE", "E", "SE", "S", "SW", "W", "NW")[int((ang + 22.5) // 45) % 8]


def facing(yaw: float) -> str:
    # MC yaw: 0 = +z (south), 90 = -x (west), 180 = -z (north), -90 = +x (east)
    rad = math.radians(yaw or 0)
    return compass(-math.sin(rad), math.cos(rad))


# ====================================================================== entities
LABEL_TYPES = {"minecraft:text_display", "minecraft:armor_stand", "minecraft:item_display",
               "minecraft:block_display", "minecraft:marker", "minecraft:interaction",
               "minecraft:area_effect_cloud"}
ITEM_TYPES = {"minecraft:item", "minecraft:experience_orb", "minecraft:arrow", "minecraft:fishing_bobber",
              "minecraft:trident", "minecraft:snowball"}
NPC_TYPES = {"minecraft:mannequin", "minecraft:villager", "minecraft:wandering_trader"}
_MOB_RE = re.compile(r"\[\s*Lv\s*(\d+)\s*\]\s*(.+?)\s+([\d.,]+[kKmMbB]?)\s*/\s*([\d.,]+[kKmMbB]?)\s*❤")
_BOSS_RE = re.compile(r"☠\s*(.+?)\s+([\d.,]+[kKmMbB]?)\s*❤")


def _plate(e: dict) -> str:
    return clean(e.get("nameplate") or e.get("customName") or "")


def mob_info(e: dict) -> dict | None:
    """Parse a SkyClone mob nameplate '[Lv1] Graveyard Zombie 100/100❤' (or a boss '☠ Name 50M❤')."""
    for part in reversed(_plate(e).split(" | ")):
        m = _MOB_RE.search(part)
        if m:
            try:
                return {"name": m.group(2).strip(), "level": int(m.group(1)),
                        "hp": _num(m.group(3)), "max_hp": _num(m.group(4))}
            except ValueError:
                return {"name": m.group(2).strip(), "level": int(m.group(1))}
        m = _BOSS_RE.search(part)
        if m:
            try:
                return {"name": m.group(1).strip(), "level": None, "hp": _num(m.group(2)), "boss": True}
            except ValueError:
                return {"name": m.group(1).strip(), "level": None, "boss": True}
    return None


def entity_kind(e: dict, tab_players: set[str] | None = None) -> str:
    t = e.get("type") or ""
    if t in ITEM_TYPES:
        return "item"
    if t in LABEL_TYPES:
        return "label"
    if e.get("isPlayer") or t == "minecraft:player":
        name = e.get("name") or ""
        if tab_players is None or name in tab_players:
            return "player"
        return "npc"           # fake-player NPCs are not in the tab list
    if mob_info(e):
        return "mob"
    if t in NPC_TYPES:
        return "npc"
    if _plate(e) and not e.get("health"):
        return "npc"
    return "creature"          # vanilla animal / unnamed mob


def entity_name(e: dict) -> str:
    mi = mob_info(e)
    if mi:
        return mi["name"]
    plate = _plate(e)
    if plate:
        parts = [p.strip() for p in plate.split(" | ") if p.strip()]
        # labels stack top->bottom: 'HUB GUIDE | Lost? Click me! | Town Crier Hollis' - the name is last
        return parts[-1] if parts else plate
    return clean(e.get("name") or e.get("type", "?").split(":")[-1].replace("_", " ").title())


def tab_names(state: dict) -> set[str]:
    out = set()
    for p in ((state.get("tab") or {}).get("players") or []):
        if isinstance(p, dict):
            out.add(str(p.get("name") or p.get("profile") or ""))
        else:
            out.add(clean(p).split(" ")[-1])
    out.discard("")
    return out


def classify(state: dict) -> dict[str, list[dict]]:
    """Group /state entities into players / mobs / npcs / creatures / items (nearest first)."""
    tab = tab_names(state) or None
    me = ((state.get("player") or {}).get("name") or "")
    out: dict[str, list[dict]] = {"player": [], "mob": [], "npc": [], "creature": [], "item": [], "label": []}
    for e in state.get("entities") or []:
        if not e.get("alive", True):
            continue
        if e.get("name") == me and (e.get("isPlayer") or e.get("type") == "minecraft:player"):
            continue
        out.setdefault(entity_kind(e, tab), []).append(e)
    for v in out.values():
        v.sort(key=lambda e: e.get("distance") or 0)
    return out


# ====================================================================== island layouts (anchors / areas / zones)
INSTANCE_SPACING = {"private_island": 1024, "garden": 1024, "kuudra": 512}

# Display names for NPCs whose data is defined in Java (not in a data JSON) + friendly aliases.
NPC_ALIASES = {
    "gerald": "npc.island_helper", "island helper": "npc.island_helper", "helper": "npc.island_helper",
    "banker": "npc.banker", "banker odell": "npc.banker", "bank": "npc.banker",
    "fairy": "npc.fairy", "wren": "npc.fairy", "wren the fairy": "npc.fairy", "bazaar": "npc.bazaar", "bazaar agent": "npc.bazaar",
    "slayer master": "npc.slayer_master", "brannoc": "npc.slayer_master", "brannoc the slayer": "npc.slayer_master",
    "librarian": "npc.librarian", "enchanting table": "npc.enchanting_table",
    "blacksmith": "npc.blacksmith", "reforge": "npc.reforge_anvil",
    "auction": "npc.auction_master", "auction master": "npc.auction_master",
    "hub guide": "npc.hub_guide", "town crier": "npc.hub_guide",
}


class Layouts:
    """Island layouts generated by SkyClone itself (template-relative; instanced islands get the
    player's slot origin added). Source order: the show server's plugins/SkyClone/islands/<id>.json,
    the SkyClone dev server's copy, then previews/<id>/report.txt."""

    def __init__(self, dirs: list[Path] | None = None, previews: Path | None = None) -> None:
        if dirs is None:
            dirs = []
            for k in ("show_server", "skyclone_server_template"):
                if SHOW.paths.get(k):
                    dirs.append(Path(SHOW.paths[k]) / "plugins" / "SkyClone" / "islands")
        self.dirs = dirs
        self.previews = previews if previews is not None else (
            Path(SHOW.paths["skyclone"]) / "previews" if SHOW.paths.get("skyclone") else None)
        self._cache: dict[str, dict] = {}
        self._lock = threading.Lock()
        self._npcs: dict[str, str] | None = None

    # -------------------------------------------------- loading
    def get(self, island: str) -> dict:
        with self._lock:
            if island in self._cache:
                return self._cache[island]
            lay = self._load(island)
            self._cache[island] = lay
            return lay

    def _load(self, island: str) -> dict:
        empty = {"anchors": {}, "areas": {}, "zones": [], "source": None}
        for d in self.dirs:
            f = d / f"{island}.json"
            if f.is_file():
                try:
                    j = json.loads(f.read_text(encoding="utf-8"))
                    L = j.get("layout") or {}
                    return {
                        "anchors": {a["name"]: (float(a["x"]), float(a["y"]), float(a["z"]))
                                    for a in L.get("anchors", []) if "name" in a},
                        "areas": {k: tuple(v) for k, v in (L.get("areas") or {}).items() if len(v) == 6},
                        "zones": [{"id": z.get("id"), "name": clean(z.get("name")), "box": tuple(z.get("box") or ()),
                                   "flags": z.get("flags") or []} for z in L.get("zones", [])],
                        "source": str(f)}
                except (OSError, ValueError, KeyError, TypeError):
                    pass
        if self.previews:
            f = self.previews / island / "report.txt"
            if f.is_file():
                try:
                    return self.parse_report(f.read_text(encoding="utf-8", errors="replace")) | {"source": str(f)}
                except OSError:
                    pass
        return empty

    @staticmethod
    def parse_report(text: str) -> dict:
        anchors, areas, zones = {}, {}, []
        num = r"(-?\d+(?:\.\d+)?)"
        for ln in text.splitlines():
            m = re.match(rf"\s+anchor\s+(\S+)\s+{num}\s+{num}\s+{num}", ln)
            if m:
                anchors.setdefault(m.group(1), (float(m.group(2)), float(m.group(3)), float(m.group(4))))
                continue
            m = re.match(r"\s+area\s+(\S+)\s+\[(-?\d+),(-?\d+),(-?\d+) -> (-?\d+),(-?\d+),(-?\d+)\]", ln)
            if m:
                areas[m.group(1)] = tuple(int(m.group(i)) for i in range(2, 8))
                continue
            m = re.match(r"\s+zone\s+(\S+)\s+(.+?)\s+\[(-?\d+),(-?\d+),(-?\d+) -> (-?\d+),(-?\d+),(-?\d+)\](?:\s+\[(.*)\])?", ln)
            if m:
                zones.append({"id": m.group(1), "name": clean(m.group(2)),
                              "box": tuple(int(m.group(i)) for i in range(3, 9)),
                              "flags": [f.strip() for f in (m.group(9) or "").split(",") if f.strip()]})
        return {"anchors": anchors, "areas": areas, "zones": zones}

    # -------------------------------------------------- geometry
    @staticmethod
    def origin(island: str, pos: tuple[float, float, float] | None) -> tuple[float, float]:
        sp = INSTANCE_SPACING.get(island)
        if not sp or pos is None:
            return 0.0, 0.0
        return round(pos[0] / sp) * sp, round(pos[2] / sp) * sp

    def anchors_world(self, island: str, pos) -> dict[str, tuple[float, float, float]]:
        ox, oz = self.origin(island, pos)
        return {k: (x + ox, y, z + oz) for k, (x, y, z) in self.get(island)["anchors"].items()}

    def area_center(self, island: str, name: str, pos) -> tuple[float, float, float] | None:
        box = self.get(island)["areas"].get(name)
        if not box:
            return None
        ox, oz = self.origin(island, pos)
        return ((box[0] + box[3]) / 2 + ox + 0.5, float(box[1]), (box[2] + box[5]) / 2 + oz + 0.5)

    def zone_at(self, island: str, pos) -> dict | None:
        if pos is None:
            return None
        ox, oz = self.origin(island, pos)
        x, y, z = pos[0] - ox, pos[1], pos[2] - oz
        best = None
        for zn in self.get(island)["zones"]:
            b = zn["box"]
            if len(b) == 6 and b[0] <= x <= b[3] + 1 and b[1] <= y <= b[4] + 1 and b[2] <= z <= b[5] + 1:
                vol = (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1)
                if best is None or vol < best[0]:
                    best = (vol, zn)
        return best[1] if best else None

    # -------------------------------------------------- names
    def npc_index(self) -> dict[str, str]:
        """display name (lower) -> anchor name, from every SkyClone data JSON that pairs a name with an anchor."""
        if self._npcs is not None:
            return self._npcs
        idx: dict[str, str] = {}
        root = Path(SHOW.paths.get("skyclone", "")) / "plugin" / "src" / "main" / "resources" / "data"

        def walk(o):
            if isinstance(o, dict):
                a, n = o.get("anchor"), o.get("name")
                if isinstance(a, str) and isinstance(n, str) and a.startswith("npc."):
                    idx.setdefault(clean(n).lower(), a)
                for v in o.values():
                    walk(v)
            elif isinstance(o, list):
                for v in o:
                    walk(v)
        try:
            for f in sorted(root.glob("*/*.json")):
                if f.stat().st_size > 3_000_000:
                    continue
                try:
                    walk(json.loads(f.read_text(encoding="utf-8")))
                except (OSError, ValueError):
                    continue
        except OSError:
            pass
        for k, v in NPC_ALIASES.items():
            idx.setdefault(k, v)
        self._npcs = idx
        return idx

    def find_place(self, island: str, query: str, pos) -> tuple[str, tuple[float, float, float]] | None:
        """Resolve a free-text place on an island: anchor name ('npc.banker', 'warp.graveyard'), NPC
        display name ('Farm Merchant'), area or zone ('graveyard', 'coal mine', 'portal.barn')."""
        q = query.strip()
        anchors = self.anchors_world(island, pos)
        if q in anchors:
            return q, anchors[q]
        lay = self.get(island)
        if f"warp.{q}" in anchors:                  # a district's warp point is its (safe) way in
            return f"warp.{q}", anchors[f"warp.{q}"]
        if q in lay["areas"]:
            return q, self.area_center(island, q, pos)
        nq = norm(q)
        a = self.npc_index().get(clean(q).lower())
        if a and a in anchors:
            return a, anchors[a]
        cands: list[tuple[str, list[str], tuple]] = []
        idx = self.npc_index()
        for disp, an in idx.items():
            if an in anchors:
                cands.append((an, [disp], anchors[an]))
        for an, p in anchors.items():
            words = an.replace("npc.", "").replace("warp.", "").replace("door.", "").replace("_", " ").replace(".", " ")
            cands.append((an, [words, an], p))
        for ar in lay["areas"]:
            c = anchors.get(f"warp.{ar}") or self.area_center(island, ar, pos)
            cands.append((ar, [ar.replace("_", " ").replace(".", " "), ar], c))
        for zn in lay["zones"]:
            b = zn["box"]
            if len(b) == 6:
                ox, oz = self.origin(island, pos)
                c = ((b[0] + b[3]) / 2 + ox, float(b[1]) + 1, (b[2] + b[5]) / 2 + oz)
                wa = anchors.get(f"warp.{zn['id']}")
                cands.append((f"zone.{zn['id']}", [zn["name"], zn["id"].replace("_", " ")], wa or c))
        hit, score = best_match(nq, cands, key=lambda c: c[1], threshold=0.6)
        if hit:
            return hit[0], hit[2]
        return None

    def nearby(self, island: str, pos, radius: float = 48, n: int = 6) -> list[tuple[str, float, str]]:
        """Interesting anchors near pos: (label, distance, direction)."""
        if pos is None:
            return []
        rev = {}
        for disp, an in self.npc_index().items():
            rev.setdefault(an, disp.title())
        out = []
        for an, (x, y, z) in self.anchors_world(island, pos).items():
            if not an.startswith(("npc.", "warp.", "door.")) or an.startswith("npc.lore"):
                continue
            d = math.dist((x, z), (pos[0], pos[2]))
            if d <= radius:
                if an.startswith("npc."):
                    label = rev.get(an)
                    if not label:
                        continue             # seasonal/unnamed vendor spot: usually empty
                elif an.startswith("warp."):
                    label = an[5:].replace("_", " ").title() + " (warp point)"
                else:
                    label = an[5:].replace("_", " ").title() + " door"
                out.append((label, d, compass(x - pos[0], z - pos[2])))
        for ar, box in self.get(island)["areas"].items():
            if ar.startswith("portal."):
                c = self.area_center(island, ar, pos)
                d = math.dist((c[0], c[2]), (pos[0], pos[2]))
                if d <= radius:
                    out.append((f"portal to {ar[7:].replace('_', ' ')}", d, compass(c[0] - pos[0], c[2] - pos[2])))
        out.sort(key=lambda t: t[1])
        return out[:n]


LAYOUTS = Layouts()


# ====================================================================== items
def item_label(it: dict | None) -> str:
    if not it or not it.get("id"):
        return ""
    name = clean(it.get("name")) or it["id"].split(":")[-1].replace("_", " ").title()
    c = it.get("count") or 1
    return f"{name} x{c}" if c > 1 else name


def sky_id(it: dict | None) -> str:
    """SkyClone's item id from customData ('skyclone:id'), else the vanilla id upper-cased."""
    if not it:
        return ""
    m = re.search(r'skyclone:id"\s*:\s*"([A-Z0-9_:]+)"', it.get("customData") or "")
    if m:
        return m.group(1)
    return (it.get("id") or "").split(":")[-1].upper()


def inventory_summary(state: dict, limit: int = 18) -> tuple[str, int]:
    agg: dict[str, int] = {}
    order: list[str] = []
    for it in state.get("inventory") or []:
        if not it.get("id"):
            continue
        name = clean(it.get("name")) or it["id"].split(":")[-1]
        if name not in agg:
            order.append(name)
            agg[name] = 0
        agg[name] += int(it.get("count") or 1)
    used = sum(1 for it in (state.get("inventory") or []) if it.get("id"))
    parts = [f"{n} x{agg[n]}" if agg[n] > 1 else n for n in order[:limit]]
    if len(order) > limit:
        parts.append(f"+{len(order) - limit} more")
    return ", ".join(parts), 36 - used


FILLER_NAMES = {"", " ", "close", "go back"}


def screen_summary(screen: dict | None, max_items: int = 36, with_lore: bool = True) -> str:
    """'SkyBlock Menu': Your Skills | Collections | ... (container slots only, filler panes skipped)."""
    if not screen:
        return "none"
    title = clean(screen.get("title")) or screen.get("type") or "?"
    names = []
    for sl in screen.get("slots") or []:
        if sl.get("container") is False:
            continue
        nm = clean(sl.get("name"))
        if not nm or nm.lower() in FILLER_NAMES or "glass_pane" in (sl.get("id") or ""):
            continue
        extra = ""
        if with_lore:
            lore = [clean(x) for x in (sl.get("lore") or []) if clean(x)]
            cost = next((lore[i + 1] for i, x in enumerate(lore[:-1]) if x.lower() == "cost"), None)
            if cost:
                extra = f" ({cost})"
            elif any(x.lower() in ("locked", "recipe locked!") or x.lower().startswith("requires") for x in lore[:6]):
                extra = " (locked)"
        cnt = sl.get("count") or 1
        names.append(f"{nm}{' x' + str(cnt) if cnt > 1 else ''}{extra}")
    more = f" | +{len(names) - max_items} more" if len(names) > max_items else ""
    return f"'{title}': " + (" | ".join(names[:max_items]) or "(empty)") + more


# ====================================================================== chat
_NOISE = re.compile(r"^[\s▬\-=_]*$")


def chat_lines(lines: list, account: str, limit: int = 15) -> list[str]:
    """Clean new chat lines; mark the agent's own messages as (you)."""
    out = []
    own = re.compile(rf"(^|[\s\]>])<?{re.escape(account)}>?\s*[:>»]", re.I)
    for ln in lines or []:
        txt = clean(ln.get("text") if isinstance(ln, dict) else ln)
        for part in txt.split("\n"):
            part = part.strip()
            if not part or _NOISE.match(part) or part.startswith("Unknown or incomplete command"):
                continue
            if own.search(part[:48]):
                part = "(you) " + part
            out.append(short(part, 170))
    return out[-limit:]


def max_chat_n(lines: list, fallback: int) -> int:
    ns = [ln.get("n") for ln in lines or [] if isinstance(ln, dict) and isinstance(ln.get("n"), int)]
    return max(ns + [fallback])


# ====================================================================== digest
def _sc_state(ctx) -> dict:
    try:
        return ctx.showcontrol_state() or {}
    except Exception:  # noqa: BLE001
        return {}


def observe(ctx, since_chat_n: int, extra: str = "") -> tuple[str, int]:
    """Compact digest of everything a human reads off the screen (≤ ~1500 tokens). Never raises."""
    try:
        return _observe(ctx, since_chat_n, extra)
    except Exception as e:  # noqa: BLE001
        msg = f"{extra}\n(observation failed: {short(str(e), 200)})".strip()
        return msg, since_chat_n


def _observe(ctx, since_chat_n: int, extra: str) -> tuple[str, int]:
    p = ctx.pilot
    st = p.state(radius=24, chat=0)
    sc = _sc_state(ctx)
    pl = st.get("player") or {}
    pos = (pl.get("x"), pl.get("y"), pl.get("z")) if pl.get("x") is not None else None
    island = island_of(pl.get("dimension"))
    sb = parse_sidebar((st.get("sidebar") or {}).get("lines") or [])
    out: list[str] = []
    if extra:
        out.append(extra.strip())

    # --- where
    zone = sb.get("zone") or sc.get("zone") or ((LAYOUTS.zone_at(island, pos) or {}).get("name"))
    where = f"WHERE: {ISLAND_NAMES.get(island, island or '?')}"
    if zone:
        where += f" > {zone}"
    if pos:
        where += f" at ({pos[0]:.0f}, {pos[1]:.0f}, {pos[2]:.0f}) facing {facing(pl.get('yaw') or 0)}"
    out.append(where)
    near = LAYOUTS.nearby(island, pos, radius=60, n=6)
    if near:
        out.append("PLACES NEAR: " + ", ".join(f"{lab} {d:.0f}m {dr}" for lab, d, dr in near))

    # --- you
    hp, mx = player_hp(st, sc)
    v = parse_vitals((st.get("actionBar") or {}).get("text") or "")
    you = [f"{hp:.0f}/{mx:.0f} HP"]
    if v.get("defense") is not None:
        you.append(f"{v['defense']:.0f} Defense")
    if v.get("mana") is not None:
        you.append(f"{v['mana']:.0f}/{v.get('max_mana', 0):.0f} Mana")
    purse = sc.get("purse", sb.get("purse"))
    if purse is not None:
        you.append(f"purse {fmt_num(purse)} coins")
    if sc.get("bank"):
        you.append(f"bank {fmt_num(sc['bank'])}")
    out.append("YOU: " + ", ".join(you))
    skills = sc.get("skills") or {}
    if skills:
        sk = []
        for name, s in skills.items():
            lvl = s.get("level", 0) if isinstance(s, dict) else s
            if lvl or len(skills) <= 8:
                sk.append(f"{str(name).title()} {lvl}")
        if sk:
            out.append("SKILLS: " + ", ".join(sk[:12]))
    obj = sc.get("objective") or sb.get("objective")
    if obj:
        out.append(f"OBJECTIVE: {clean(obj)}")
    for key, label in (("next_unlocks", "NEXT UNLOCKS"), ("can_afford", "CAN AFFORD")):
        val = sc.get(key)
        if val:
            out.append(f"{label}: " + short(", ".join(clean(x if isinstance(x, str) else (x.get('name') or x.get('id') or x)) for x in val[:8]), 220))
    if sc.get("warps_unlocked"):
        out.append("WARPS: " + ", ".join(map(str, sc["warps_unlocked"][:20])))

    # --- items
    hb = st.get("hotbar") or []
    sel = pl.get("selectedSlot")
    held = hb[sel] if isinstance(sel, int) and sel < len(hb) else None
    out.append(f"HOLDING: {item_label(held) or 'nothing'}")
    hbs = [f"{i + 1}:{item_label(it)}" for i, it in enumerate(hb) if it and it.get("id")]
    if hbs:
        out.append("HOTBAR: " + " | ".join(hbs))
    inv, free = inventory_summary(st)
    out.append(f"INVENTORY ({free} free slots): {inv or 'empty'}")
    eq = st.get("equipment") or {}
    arm = [item_label(eq.get(k)) for k in ("head", "chest", "legs", "feet") if eq.get(k)]
    if arm:
        out.append("ARMOR: " + ", ".join(arm))
    if sc.get("minions"):
        out.append(f"MINIONS PLACED: {len(sc['minions'])}")

    # --- screen
    scr = st.get("screen")
    if scr:
        out.append("OPEN SCREEN: " + short(screen_summary(scr), 900))

    # --- surroundings
    groups = classify(st)
    if groups["player"]:
        out.append("PLAYERS: " + ", ".join(
            f"{SHOW.char_name(e.get('name') or '?')} {e['distance']:.0f}m "
            f"{compass(e['x'] - pos[0], e['z'] - pos[2]) if pos else ''}".strip() for e in groups["player"][:6]))
    if groups["mob"]:
        agg: dict[str, list] = {}
        for e in groups["mob"]:
            agg.setdefault(entity_name(e), []).append(e)
        parts = []
        for name, es in list(agg.items())[:6]:
            e0 = es[0]
            mi = mob_info(e0) or {}
            hp_s = f" {fmt_num(mi['hp'])}/{fmt_num(mi.get('max_hp', mi['hp']))}hp" if mi.get("hp") is not None else ""
            lvl = f"Lv{mi['level']} " if mi.get("level") else ""
            dr = compass(e0["x"] - pos[0], e0["z"] - pos[2]) if pos else ""
            parts.append(f"{lvl}{name}{' x' + str(len(es)) if len(es) > 1 else ''} (nearest {e0['distance']:.0f}m {dr}{hp_s})")
        out.append("MOBS: " + "; ".join(parts))
    if groups["npc"]:
        seen, parts = set(), []
        for e in groups["npc"]:
            nm = entity_name(e)
            if nm in seen:
                continue
            seen.add(nm)
            parts.append(f"{nm} {e['distance']:.0f}m")
            if len(parts) >= 8:
                break
        out.append("NPCS: " + ", ".join(parts))
    if groups["creature"]:
        agg2: dict[str, int] = {}
        for e in groups["creature"][:20]:
            agg2[entity_name(e)] = agg2.get(entity_name(e), 0) + 1
        out.append("ANIMALS: " + ", ".join(f"{k}{' x' + str(n) if n > 1 else ''}" for k, n in list(agg2.items())[:6]))
    if groups["item"]:
        out.append(f"DROPPED ITEMS NEARBY: {len([e for e in groups['item'] if e.get('type') == 'minecraft:item'])}")

    # --- HUD
    side = [ln for ln in sb.get("lines", []) if not ln.lower().startswith(("purse", "bits", "objective"))
            and ln != sb.get("objective") and "⏣" not in ln]
    if side:
        out.append("SIDEBAR: " + short(" / ".join(side), 300))
    ab = clean((st.get("actionBar") or {}).get("text"))
    if v.get("xp_popup"):
        out.append(f"XP: {v['xp_popup']}")
    elif ab and not v:
        out.append(f"ACTION BAR: {short(ab, 160)}")
    t1, t2 = clean((st.get("title") or {}).get("text")), clean((st.get("subtitle") or {}).get("text"))
    if t1 or t2:
        out.append(f"TITLE: {short((t1 + ' - ' + t2).strip(' -'), 160)}")

    # --- chat
    new_n = since_chat_n
    try:
        lines = p.chat_since(since_chat_n)
        new_n = max_chat_n(lines, since_chat_n)
        if not lines and st.get("chatCount") is not None and st["chatCount"] < since_chat_n:
            new_n = st["chatCount"]          # client reconnected: counter reset
        cl = chat_lines(lines, ctx.char.account)
        if cl:
            out.append("NEW CHAT:\n  " + "\n  ".join(cl))
    except Exception:  # noqa: BLE001
        pass

    text = "\n".join(out)
    if len(text) > 6000:
        text = text[:5990] + "…"
    return text, new_n


# ====================================================================== screenshot
def screenshot_block(ctx, max_w: int = 768) -> dict | None:
    """Framebuffer screenshot as an Anthropic image block, downscaled to <= max_w px wide.
    Falls back to the full-size PNG if decoding fails; None if no screenshot could be taken."""
    path = Path(tempfile.gettempdir()) / "AISkyShow" / f"shot_{ctx.char.account}_{int(time.time() * 1000)}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        real = ctx.pilot.screenshot(str(path))
        raw = Path(real).read_bytes()
    except Exception as e:  # noqa: BLE001
        try:
            ctx.log(f"screenshot failed: {e}")
        except Exception:  # noqa: BLE001
            pass
        return None
    finally:
        pass
    try:
        data = downscale_png(raw, max_w)
    except Exception:  # noqa: BLE001
        data = raw
    try:
        os.remove(real)
    except OSError:
        pass
    return {"type": "image", "source": {"type": "base64", "media_type": "image/png",
                                        "data": base64.b64encode(data).decode("ascii")}}


# ---------------------------------------------------------------------- minimal PNG codec (zlib + struct)
class PngError(ValueError):
    pass


def decode_png(data: bytes) -> tuple[int, int, int, list[bytearray]]:
    """-> (width, height, channels, rows). 8-bit, non-interlaced, colour types 0/2/4/6 only."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise PngError("not a PNG")
    pos, idat, ihdr = 8, [], None
    while pos + 8 <= len(data):
        (ln,) = struct.unpack(">I", data[pos:pos + 4])
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif typ == b"IDAT":
            idat.append(body)
        elif typ == b"IEND":
            break
    if not ihdr:
        raise PngError("no IHDR")
    w, h, depth, ctype, _comp, _filt, interlace = ihdr
    chans = {0: 1, 2: 3, 4: 2, 6: 4}.get(ctype)
    if depth != 8 or chans is None or interlace:
        raise PngError(f"unsupported PNG (depth {depth}, colour {ctype}, interlace {interlace})")
    raw = zlib.decompress(b"".join(idat))
    bpp, rb = chans, w * chans
    stride = rb + 1
    if len(raw) < stride * h:
        raise PngError("truncated image data")
    prev = bytearray(rb)
    rows = []
    for y in range(h):
        f = raw[y * stride]
        line = bytearray(raw[y * stride + 1:(y + 1) * stride])
        if f == 1:
            for i in range(bpp, rb):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif f == 2:
            line = bytearray(_add_bytes(bytes(line), bytes(prev)))
        elif f == 3:
            for i in range(rb):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(rb):
                if i >= bpp:
                    a = line[i - bpp]
                    c = prev[i - bpp]
                else:
                    a = c = 0
                b = prev[i]
                pp = a + b - c
                pa = abs(pp - a)
                pb = abs(pp - b)
                pc = abs(pp - c)
                if pa <= pb and pa <= pc:
                    pr = a
                elif pb <= pc:
                    pr = b
                else:
                    pr = c
                line[i] = (line[i] + pr) & 255
        elif f != 0:
            raise PngError(f"bad filter {f} on row {y}")
        rows.append(line)
        prev = line
    return w, h, chans, rows


def _masks(n: int) -> tuple[int, int, int]:
    hi = int.from_bytes(b"\x80" * n, "big")
    return hi, int.from_bytes(b"\x7f" * n, "big"), (1 << (8 * n)) - 1


def _add_bytes(a: bytes, b: bytes) -> bytes:
    """Byte-wise (a + b) mod 256 using big-int SWAR (no per-byte Python loop)."""
    n = len(a)
    hi, lo, _ = _masks(n)
    x, y = int.from_bytes(a, "big"), int.from_bytes(b, "big")
    s = ((x & lo) + (y & lo)) ^ ((x ^ y) & hi)
    return s.to_bytes(n, "big")


def _sub_bytes(a: bytes, b: bytes) -> bytes:
    """Byte-wise (a - b) mod 256 using big-int SWAR."""
    n = len(a)
    hi, lo, full = _masks(n)
    x, y = int.from_bytes(a, "big"), int.from_bytes(b, "big")
    d = ((x | hi) - (y & lo)) ^ ((x ^ ~y) & hi)
    return (d & full).to_bytes(n, "big")


def downscale_rgb(w: int, h: int, chans: int, rows: list[bytearray], max_w: int) -> tuple[int, int, list[bytes]]:
    """Integer-step downscale to <= max_w wide; each output pixel averages a 2x2 sample of its block.
    Output is RGB (alpha dropped, greyscale expanded)."""
    step = max(1, math.ceil(w / max_w))
    nw, nh = w // step, h // step
    out = []
    off = step // 2 if step > 1 else 0          # second sample offset inside the block
    for oy in range(nh):
        r0 = rows[oy * step]
        r1 = rows[min(h - 1, oy * step + off)]
        line = bytearray(nw * 3)
        for c in range(3):
            src = c if chans >= 3 else 0
            sx = chans * step
            a = r0[src::sx][:nw]
            b = r0[src + chans * off::sx][:nw] if off else a
            cc = r1[src::sx][:nw]
            d = r1[src + chans * off::sx][:nw] if off else cc
            if len(b) < nw:
                b = a
            if len(d) < nw:
                d = cc
            line[c::3] = bytes((p + q + r + t + 2) >> 2 for p, q, r, t in zip(a, b, cc, d))
        out.append(bytes(line))
    return nw, nh, out


def encode_png(w: int, h: int, rows: list[bytes], chans: int = 3) -> bytes:
    """RGB/RGBA 8-bit PNG, 'Up' filter (cheap via SWAR, compresses screenshots well)."""
    ctype = {1: 0, 2: 4, 3: 2, 4: 6}[chans]
    rb = w * chans
    prev = bytes(rb)
    buf = bytearray()
    for r in rows:
        r = bytes(r)
        buf.append(2)
        buf += _sub_bytes(r, prev)
        prev = r

    def chunk(t: bytes, b: bytes) -> bytes:
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(buf), 6)) + chunk(b"IEND", b""))


def downscale_png(data: bytes, max_w: int = 768) -> bytes:
    w, h, chans, rows = decode_png(data)
    if w <= max_w and chans == 3:
        return data
    nw, nh, out = downscale_rgb(w, h, chans, rows, max_w)
    return encode_png(nw, nh, out, 3)
