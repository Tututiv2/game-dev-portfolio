"""A tiny fake SkyClone so the whole agent loop can be dry-run with no Minecraft at all.

    from show.mock import MockWorld
    world = MockWorld()                       # private islands + hub, NPCs, mobs, ores, logs, crops, water
    ctx = world.ctx("Kaito")                  # .pilot (MockPilot), .char, .log, .showcontrol_state()
    run_action(ctx, "chop", {"block": "oak", "count": 3})

`MockPilot` implements the subset of TestPilot's `pilot.Pilot` the macros + observe use, returning the
same JSON shapes (player / hotbar / inventory / screen.slots / sidebar / actionBar / entities / chat).
`MockShowControl` answers `/state/<name>` and `/players` from the same world. Behaviour is deliberately
simple but faithful where macros depend on it: NPC labels as text displays, '[Lv1] Name hp/max❤'
nameplates, DigEngine ores turning into bedrock in the hub, '!!!' bite labels over the bobber, shop
buy/sell by slot clicks with the real chat lines, `/recipe` + Supercraft, locked hub district warps.
"""
from __future__ import annotations

import itertools
import math
import threading
import time
from pathlib import Path

from .config import SHOW
from .observe import LAYOUTS, encode_png

ORIGIN = (1024, 0)          # every mock private island sits in instance slot (1024, 0) of its own copy


def _item(id_: str, name: str, count: int = 1, lore=None, sky: str | None = None) -> dict:
    cd = f'{{PublicBukkitValues:{{"skyclone:id":"{sky or id_.split(":")[-1].upper()}"}}}}'
    if name == "SkyBlock Menu (Click)":
        cd = '{PublicBukkitValues:{"skyclone:sbmenu":1b}}'
    return {"id": id_, "count": count, "name": name, "nameLegacy": name, "lore": list(lore or []),
            "loreLegacy": list(lore or []), "customData": cd}


STAR = lambda: _item("minecraft:nether_star", "SkyBlock Menu (Click)", lore=["View all of your SkyBlock progress,"])  # noqa: E731
ITEMS = {
    "ROOKIE_PICKAXE": ("minecraft:stone_pickaxe", "Rookie Pickaxe", ["Breaking Power 2", "Damage: +15"]),
    "ROOKIE_AXE": ("minecraft:stone_axe", "Rookie Axe", ["Damage: +10"]),
    "ROOKIE_HOE": ("minecraft:stone_hoe", "Rookie Hoe", []),
    "BREAD": ("minecraft:bread", "Bread", []),
    "COBBLESTONE_GENERATOR_1": ("minecraft:player_head", "Cobblestone Minion I", ["RARE MINION"]),
    "OAK_LOG": ("minecraft:oak_log", "Oak Log", ["COMMON"]),
    "OAK_PLANKS": ("minecraft:oak_planks", "Oak Planks", ["COMMON"]),
    "STICK": ("minecraft:stick", "Stick", ["COMMON"]),
    "CRAFTING_TABLE": ("minecraft:crafting_table", "Crafting Table", ["COMMON"]),
    "WOODEN_PICKAXE": ("minecraft:wooden_pickaxe", "Wooden Pickaxe", ["Breaking Power 1", "COMMON"]),
    "COBBLESTONE": ("minecraft:cobblestone", "Cobblestone", ["COMMON"]),
    "COAL": ("minecraft:coal", "Coal", ["COMMON"]),
    "WHEAT": ("minecraft:wheat", "Wheat", ["COMMON"]),
    "RAW_FISH": ("minecraft:cod", "Raw Cod", ["COMMON"]),
    "UNDEAD_SWORD": ("minecraft:iron_sword", "Undead Sword", ["Damage: +30", "COMMON SWORD"]),
    "FISHING_ROD": ("minecraft:fishing_rod", "Fishing Rod", ["COMMON FISHING ROD"]),
    "ROTTEN_FLESH": ("minecraft:rotten_flesh", "Rotten Flesh", ["COMMON"]),
}
SELL = {"OAK_LOG": 2, "OAK_PLANKS": 0.5, "COBBLESTONE": 1, "COAL": 2, "WHEAT": 6, "RAW_FISH": 6, "ROTTEN_FLESH": 2}
SHOPS = {
    "Farm Merchant": [("WHEAT", 3, 7), ("ROOKIE_HOE", 1, 10)],
    "Weaponsmith": [("UNDEAD_SWORD", 1, 100)],
    "Fish Merchant": [("FISHING_ROD", 1, 50)],
}
STACKABLE = set(SELL) | {"BREAD", "STICK", "CRAFTING_TABLE"}
RECIPES = {   # result -> (count made, {ingredient: n})
    "OAK_PLANKS": (4, {"OAK_LOG": 1}),
    "STICK": (4, {"OAK_PLANKS": 2}),
    "CRAFTING_TABLE": (1, {"OAK_PLANKS": 4}),
    "WOODEN_PICKAXE": (1, {"OAK_PLANKS": 3, "STICK": 2}),
}
BLOCK_DROPS = {"oak_log": "OAK_LOG", "stone": "COBBLESTONE", "cobblestone": "COBBLESTONE", "coal_ore": "COAL",
               "wheat": "WHEAT"}


def make(sky: str, count: int = 1) -> dict:
    id_, name, lore = ITEMS[sky]
    return _item(id_, name, count, lore, sky)


class MockWorld:
    """Shared world for any number of mock players (they see each other and share chat)."""

    def __init__(self, accounts: list[str] | None = None) -> None:
        self.lock = threading.RLock()
        self.ids = itertools.count(1000)
        self.tick = 0
        self.players: dict[str, dict] = {}
        self.entities: dict[str, list[dict]] = {"hub": [], "private_island": []}
        self.blocks: dict[str, dict[tuple, str]] = {"hub": {}, "private_island": {}}
        self.discovered: dict[str, set] = {}
        self.events: list[dict] = []
        self._build_hub()
        self._build_island()
        for a in accounts or [c.account for c in SHOW.characters]:
            self.add_player(a)

    # ------------------------------------------------------------ building
    def _ent(self, island: str, type_: str, x, y, z, **kw) -> dict:
        e = {"id": next(self.ids), "uuid": "", "type": type_, "name": kw.pop("name", type_.split(":")[-1].title()),
             "customName": None, "nameplate": None, "x": x, "y": y, "z": z, "centerY": y + 0.9, "height": 1.8,
             "alive": True, "health": None, "maxHealth": None, "isPlayer": False, "text": None}
        e.update(kw)
        self.entities[island].append(e)
        return e

    def _npc(self, island: str, name: str, x, y, z, title: str | None = None, villager=False, **kw):
        plate = " | ".join([t for t in (title, name) if t])
        e = self._ent(island, "minecraft:villager" if villager else "minecraft:mannequin", x, y, z,
                      name="Villager" if villager else "Mannequin", nameplate=plate, health=20.0, maxHealth=20.0,
                      npc=name, **kw)
        self._ent(island, "minecraft:text_display", x, y + 2.05, z, text=name)
        if title:
            self._ent(island, "minecraft:text_display", x, y + 2.33, z, text=title)
        return e

    def _mob(self, island: str, name: str, lvl: int, hp: float, x, y, z, coins=5, drop="ROTTEN_FLESH"):
        e = self._ent(island, "minecraft:zombie", x, y, z, name="Zombie", health=20.0, maxHealth=20.0,
                      mob={"name": name, "lvl": lvl, "hp": hp, "max": hp, "coins": coins, "drop": drop,
                           "home": (x, y, z)})
        self._plate(e)
        return e

    @staticmethod
    def _plate(e):
        m = e["mob"]
        e["nameplate"] = f"[Lv{m['lvl']}] {m['name']} {int(m['hp'])}/{int(m['max'])}❤"

    def _build_hub(self):
        a = LAYOUTS.anchors_world("hub", (0, 75, 0))
        g = lambda k, d: a.get(k, d)  # noqa: E731
        self._npc("hub", "Town Crier Hollis", *g("npc.hub_guide", (4.5, 75.0, 15.5)), title="HUB GUIDE", shop=None)
        self._npc("hub", "Farm Merchant", *g("npc.farm_merchant", (105.5, 75.0, 108.5)), shop="Farm Merchant")
        self._npc("hub", "Weaponsmith", *g("npc.weaponsmith", (-85.5, 75.0, 19.5)), shop="Weaponsmith")
        self._npc("hub", "Fish Merchant", *g("npc.fish_merchant", (40.5, 74.0, 138.5)), shop="Fish Merchant")
        for i in range(5):
            self._mob("hub", "Graveyard Zombie", 1, 100, 160 + i * 3, 76, -20 + i * 2)
        for i in range(6):
            self.blocks["hub"][(60 + i, 76, -150)] = "coal_ore"
        for i in range(4):
            self.blocks["hub"][(110 + i, 75, 112)] = "wheat"

    def _build_island(self):
        ox, oz = ORIGIN
        a = LAYOUTS.anchors_world("private_island", (ox, 73, oz))
        gx, gy, gz = a.get("npc.island_helper", (ox - 21.5, 73.0, oz + 3.5))
        self._npc("private_island", "Gerald", gx, gy, gz, villager=True, intro=True)
        B = self.blocks["private_island"]
        for y in range(73, 78):
            B[(ox - 18, y, oz - 6)] = "oak_log"
            B[(ox - 16, y, oz - 7)] = "oak_log"
        for x in range(ox + 18, ox + 24):
            B[(x, 73, oz + 16)] = "stone"
            B[(x, 73, oz + 18)] = "coal_ore" if x % 2 else "cobblestone"
        for x in range(ox - 20, ox - 15):
            B[(x, 73, oz + 14)] = "wheat"
        for x in range(ox - 2, ox + 6):
            for z in range(oz + 14, oz + 22):
                B[(x, 71, z)] = "water"

    def add_player(self, account: str) -> dict:
        with self.lock:
            ox, oz = ORIGIN
            inv = [None] * 36
            inv[0] = make("COBBLESTONE_GENERATOR_1")
            inv[8] = STAR()
            p = {"name": account, "island": "private_island", "x": ox - 24.5, "y": 73.0, "z": oz + 0.5,
                 "yaw": -90.0, "pitch": 0.0, "hp": 100.0, "max_hp": 100.0, "purse": 0.0, "inv": inv,
                 "sel": 8, "screen": None, "chat": [], "talked": set(), "kit": False, "skills": {"FORAGING": 0, "MINING": 0, "COMBAT": 0, "FARMING": 0, "FISHING": 0},
                 "bobber": None, "bite_in": 0, "stuck": 0, "minions": [], "action": ""}
            self.players[account] = p
            self.discovered[account] = set()
            self.say_to(account, "Welcome to SkyClone! The portal behind you leads to the Hub.")
            return p

    # ------------------------------------------------------------ chat
    def say_to(self, account: str, text: str) -> None:
        p = self.players[account]
        p["chat"].append({"n": len(p["chat"]) + 1, "text": text, "legacy": text, "time": int(time.time() * 1000)})

    def broadcast(self, text: str) -> None:
        for a in self.players:
            self.say_to(a, text)

    def event(self, type_: str, players: list[str], text: str, **data) -> None:
        self.events.append({"id": len(self.events) + 1, "t": int(time.time() * 1000), "src": "server", "type": type_,
                            "players": players, "drama": 3, "text": text, "loc": None, "data": data})


class MockPilot:
    """Implements the pilot.Pilot subset used by show.macros / show.observe."""

    LOADING_SCREENS = ()

    def __init__(self, world: MockWorld, account: str) -> None:
        self.w = world
        self.account = account
        self.calls: list[tuple[str, dict]] = []

    @property
    def me(self) -> dict:
        return self.w.players[self.account]

    # ------------------------------------------------------------ basics
    def ping(self) -> dict:
        return {"ok": True, "inWorld": True, "player": self.account, "focused": False, "osFocused": False,
                "screen": None, "screenType": None, "chatCount": len(self.me["chat"]), "tick": self.w.tick}

    def wait_until_ready(self, timeout: float = 180) -> dict:
        return self.ping()

    def chat_count(self) -> int:
        return len(self.me["chat"])

    def chat_since(self, n: int) -> list[dict]:
        return [c for c in self.me["chat"] if c["n"] > n]

    def wait(self, ticks: int) -> dict:
        self.w.tick += int(ticks)
        return {"ok": True}

    def stop(self) -> dict:
        self.calls.append(("stop", {}))
        return {"ok": True}

    def screenshot(self, path) -> str:
        w, h = 1600, 40
        rows = [bytes((x * 7 + y * 3) % 256 for x in range(w * 3)) for y in range(h)]
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_bytes(encode_png(w, h, rows, 3))
        return str(Path(path).resolve())

    # ------------------------------------------------------------ state
    def _pos(self):
        m = self.me
        return (m["x"], m["y"], m["z"])

    def _inv_items(self) -> list[dict]:
        out = []
        for i, it in enumerate(self.me["inv"]):
            if it:
                d = dict(it)
                d["slot"] = i
                out.append(d)
        return out

    def _entities(self, radius: float) -> list[dict]:
        m = self.me
        out = []
        for e in self.w.entities[m["island"]]:
            d = math.dist((e["x"], e["y"], e["z"]), self._pos())
            if d <= radius:
                x = {k: v for k, v in e.items() if k not in ("mob", "npc", "shop", "intro")}
                x["distance"] = d
                out.append(x)
        for a, o in self.w.players.items():
            if a != self.account and o["island"] == m["island"]:
                d = math.dist((o["x"], o["y"], o["z"]), self._pos())
                if d <= radius:
                    out.append({"id": hash(a) % 100000, "type": "minecraft:player", "name": a, "customName": None,
                                "nameplate": a, "x": o["x"], "y": o["y"], "z": o["z"], "distance": d, "alive": True,
                                "health": 20.0, "maxHealth": 20.0, "isPlayer": True, "text": None})
        out.sort(key=lambda e: e["distance"])
        return out

    def state(self, radius: float = 32, chat: int = 100, **_) -> dict:
        with self.w.lock:
            self._tick_fishing()
            m = self.me
            inv = self._inv_items()
            hotbar = [dict(m["inv"][i]) if m["inv"][i] else {"id": None} for i in range(9)]
            zone = {"hub": "Village Square", "private_island": "Private Island"}[m["island"]]
            ab = m["action"] or ""
            return {
                "ok": True, "inWorld": True, "focused": False, "osFocused": False, "tick": self.w.tick,
                "player": {"name": self.account, "uuid": "", "x": m["x"], "y": m["y"], "z": m["z"],
                           "blockX": math.floor(m["x"]), "blockY": math.floor(m["y"]), "blockZ": math.floor(m["z"]),
                           "yaw": m["yaw"], "pitch": m["pitch"], "dimension": f"minecraft:sb_{m['island']}",
                           "health": max(0.0, m["hp"] / 5), "maxHealth": 20.0, "food": 20, "selectedSlot": m["sel"],
                           "onGround": True, "gameMode": "survival"},
                "hotbar": hotbar, "inventory": inv, "equipment": {"head": None, "chest": None, "legs": None, "feet": None, "offhand": None},
                "screen": self._screen_json(),
                "sidebar": {"title": "SKYBLOCK", "lines": ["10/03/26 m1A", " ", " Early Summer 5th", f" ⏣ {zone}", " ",
                                                          f"Purse: {m['purse']:g}", "Bits: 0", " ", "Objective",
                                                          "Talk to your Island Helper", " ", "www.skyclone.example"]},
                "tab": {"header": {"text": ""}, "footer": {"text": ""}, "players": [{"name": a} for a in self.w.players]},
                "actionBar": {"text": f"{m['hp']:.0f}/{m['max_hp']:.0f}❤     {ab or '0❈ Defense'}     100/100✎ Mana", "ageMs": 50},
                "title": {"text": None}, "subtitle": {"text": None},
                "chat": m["chat"][-chat:] if chat else [], "chatCount": len(m["chat"]),
                "entities": self._entities(radius) if radius else [],
                "crosshair": {"type": "MISS"},
            }

    def screen(self) -> dict | None:
        return self._screen_json()

    def sidebar(self) -> list[str]:
        return self.state(radius=0, chat=0)["sidebar"]["lines"]

    def entities(self, radius: float = 32) -> list[dict]:
        return self._entities(radius)

    # ------------------------------------------------------------ chat / commands
    def chat(self, text: str) -> dict:
        self.calls.append(("chat", {"text": text}))
        w, m = self.w, self.me
        with w.lock:
            if not text.startswith("/"):
                w.broadcast(f"[0] {self.account}: {text}")
                w.event("chat", [self.account], f"{self.account}: {text}", message=text)
                return {"ok": True}
            parts = text[1:].split()
            cmd, args = parts[0].lower(), parts[1:]
            if cmd in ("hub", "lobby") or (cmd == "warp" and args and args[0] == "hub"):
                self._teleport("hub", (0.5, 75.0, 12.5), "Hub")
            elif cmd in ("is", "island", "home") or (cmd == "warp" and args and args[0] in ("home", "private_island")):
                self._teleport("private_island", (ORIGIN[0] - 24.5, 73.0, ORIGIN[1] + 0.5), "Private Island")
            elif cmd == "warp" and args:
                wid = args[0]
                zone = {"hub_graveyard": ("graveyard", (144.5, 76.0, -23.5)), "hub_farm": ("farm", (106.5, 75, 100.5)),
                        "hub_coal_mine": ("coal_mine", (77.5, 75.0, -139.5))}.get(wid)
                if zone and m["island"] == "hub" and zone[0] in w.discovered[self.account] or zone and zone[0] in w.discovered[self.account]:
                    self._teleport("hub", zone[1], zone[0].replace("_", " ").title())
                elif zone:
                    w.say_to(self.account, f"Requires discovering the {zone[0].replace('_', ' ').title()} in the Hub!")
                elif wid == "barn":
                    w.say_to(self.account, "Requires Farming Skill 1!")
                else:
                    w.say_to(self.account, f"Unknown destination '{wid}'!")
            elif cmd == "sbmenu":
                m["screen"] = {"kind": "sbmenu"}
            elif cmd == "recipe" and args:
                q = "_".join(args).upper()
                hit = next((k for k in RECIPES if k == q or ITEMS[k][1].upper().replace(" ", "_") == q), None)
                if hit:
                    m["screen"] = {"kind": "recipe", "item": hit}
                else:
                    w.say_to(self.account, f"No recipe found for '{' '.join(args)}'.")
            elif cmd == "questlog":
                m["screen"] = {"kind": "generic", "title": "Quest Log", "buttons": ["Talk to your Island Helper"]}
            elif cmd in ("skills",):
                m["screen"] = {"kind": "generic", "title": "Your Skills", "buttons": ["Farming", "Mining", "Combat", "Foraging", "Fishing"]}
            else:
                w.say_to(self.account, "Unknown or incomplete command. See below for error")
            return {"ok": True}

    def _teleport(self, island: str, pos, label: str) -> None:
        m = self.me
        self.w.say_to(self.account, f"Warping to {label}...")
        old = m["island"]
        m.update(island=island, x=pos[0], y=pos[1], z=pos[2], screen=None)
        if old != island:
            self.w.event("island_change", [self.account], f"{self.account} -> {island}", **{"from": old, "to": island})

    # ------------------------------------------------------------ movement
    def look(self, yaw=None, pitch=None, *, x=None, y=None, z=None, entity=None, nearest=None) -> dict:
        m = self.me
        if x is not None:
            dx, dz = x - m["x"], z - m["z"]
            m["yaw"] = math.degrees(math.atan2(-dx, dz))
            m["look_at"] = (x, y, z)
        elif yaw is not None:
            m["yaw"] = yaw
        return {"ok": True}

    def walk_to(self, x, y, z, timeout=20, tolerance=0.6, sprint=False) -> dict:
        self.calls.append(("walkto", {"x": x, "z": z}))
        m = self.me
        if m["screen"]:
            return {"ok": True, "arrived": False, "distance": 99, "moved": 0}
        if m["stuck"] > 0:
            m["stuck"] -= 1
            return {"ok": True, "arrived": False, "distance": math.hypot(x - m["x"], z - m["z"]), "moved": 0.1}
        d = math.hypot(x - m["x"], z - m["z"])
        step = min(d - tolerance * 0.9, (5.6 if sprint else 4.3) * timeout) if d > tolerance else 0
        if step > 0:
            m["x"] += (x - m["x"]) / d * step
            m["z"] += (z - m["z"]) / d * step
            m["y"] = y if abs(y - m["y"]) < 3 else m["y"]
        self._discover()
        nd = math.hypot(x - m["x"], z - m["z"])
        return {"ok": True, "arrived": nd <= tolerance + 0.01, "distance": nd, "moved": max(0, step)}

    def move(self, forward=0, strafe=0, jump=False, sneak=False, sprint=False, seconds=1.0) -> dict:
        self.calls.append(("move", {"forward": forward, "strafe": strafe}))
        m = self.me
        rad = math.radians(m["yaw"])
        fx, fz = -math.sin(rad), math.cos(rad)
        sp = 4.3 * seconds
        m["x"] += (fx * forward - fz * strafe) * sp
        m["z"] += (fz * forward + fx * strafe) * sp
        return {"ok": True, "moved": abs(sp), "pos": {"x": m["x"], "y": m["y"], "z": m["z"]}}

    def _discover(self):
        m = self.me
        if m["island"] != "hub":
            return
        zn = LAYOUTS.zone_at("hub", self._pos())
        if zn and zn["id"] not in self.w.discovered[self.account]:
            self.w.discovered[self.account].add(zn["id"])
            if zn["id"] not in ("hub", "village", "plaza"):
                self.w.say_to(self.account, f"NEW AREA DISCOVERED! {zn['name']}")

    # ------------------------------------------------------------ hotbar / inventory
    def hotbar(self, slot: int) -> dict:
        self.me["sel"] = int(slot)
        return {"ok": True}

    def open_inventory(self) -> dict:
        self.me["screen"] = {"kind": "inventory"}
        return {"ok": True}

    def close(self) -> dict:
        self.me["screen"] = None
        return {"ok": True}

    def _give(self, sky: str, n: int) -> None:
        inv = self.me["inv"]
        for i, it in enumerate(inv):
            if it and it["customData"].find(f'"{sky}"') >= 0 and it["count"] < 64 and i != 8 and sky in STACKABLE:
                add = min(n, 64 - it["count"])
                it["count"] += add
                n -= add
                if n <= 0:
                    return
        for i in list(range(0, 8)) + list(range(9, 36)):
            if n <= 0:
                return
            if inv[i] is None:
                inv[i] = make(sky, min(64, n))
                n -= min(64, n)

    def _count(self, sky: str) -> int:
        return sum(it["count"] for it in self.me["inv"] if it and f'"{sky}"' in it["customData"])

    def _take(self, sky: str, n: int) -> None:
        for i, it in enumerate(self.me["inv"]):
            if n <= 0:
                break
            if it and f'"{sky}"' in it["customData"]:
                t = min(n, it["count"])
                it["count"] -= t
                n -= t
                if it["count"] <= 0:
                    self.me["inv"][i] = None

    @staticmethod
    def _sky(it: dict) -> str:
        cd = it.get("customData") or ""
        i = cd.find('skyclone:id":"')
        return cd[i + 14:cd.find('"', i + 14)] if i >= 0 else ""

    # ------------------------------------------------------------ screens
    def _slot(self, i, it, container=True, cslot=None) -> dict:
        d = dict(it) if it else {"id": None}
        d.update(slot=i, container=container, containerSlot=i if cslot is None else cslot)
        return d

    def _player_part(self, start: int) -> list[dict]:
        out = []
        inv = self.me["inv"]
        order = list(range(9, 36)) + list(range(0, 9))
        for k, idx in enumerate(order):
            out.append(self._slot(start + k, inv[idx], False, idx))
        return out

    def _screen_json(self) -> dict | None:
        s = self.me["screen"]
        if not s:
            return None
        kind = s["kind"]
        slots: list[dict] = []
        title = "?"
        if kind == "sbmenu":
            title = "SkyBlock Menu"
            names = {13: "Stats & Equipment", 19: "Your Skills", 20: "Collections", 21: "Recipe Book", 23: "Quest Log",
                     31: "Crafting Table", 41: "Your Island", 47: "Fast Travel", 49: "Close"}
            for i in range(54):
                slots.append(self._slot(i, _item("minecraft:book", names[i]) if i in names else
                                        _item("minecraft:black_stained_glass_pane", " ")))
            slots += self._player_part(54)
        elif kind == "generic":
            title = s["title"]
            for i, b in enumerate(s["buttons"]):
                slots.append(self._slot(10 + i, _item("minecraft:paper", b)))
            slots += self._player_part(54)
        elif kind == "shop":
            title = s["shop"]
            for k, (sky, n, cost) in enumerate(SHOPS[s["shop"]]):
                it = make(sky, n)
                it["lore"] = it["lore"] + ["", "Cost", f"{cost} Coins", "", "Click to trade!"]
                slots.append(self._slot(10 + k, it))
            slots.append(self._slot(49, _item("minecraft:barrier", "Close")))
            slots.append(self._slot(50, _item("minecraft:hopper", "Sell Item")))
            slots.append(self._slot(51, _item("minecraft:chest", "Sell Items")))
            slots += self._player_part(54)
        elif kind == "recipe":
            res = s["item"]
            made, need = RECIPES[res]
            ok = all(self._count(k) >= v for k, v in need.items())
            can = min(self._count(k) // v for k, v in need.items())
            lore = ["Craft this straight from your", "inventory, no grid needed.", ""]
            lore += [f"{'✔' if self._count(k) >= v else '✖'} {self._count(k)}/{v} {ITEMS[k][1]}" for k, v in need.items()]
            lore += [""] + (["Left-click to craft x1!", "Right-click to choose an amount!", f"Shift-click to craft max! ({can})"]
                            if ok else ["Missing materials!"])
            title = f"{ITEMS[res][1]} Recipe"
            slots.append(self._slot(25, make(res, made)))
            slots.append(self._slot(14, _item("minecraft:lime_dye", "Recipe Unlocked")))
            slots.append(self._slot(32, _item("minecraft:crafting_table", "Supercraft", lore=lore)))
            slots += self._player_part(54)
        elif kind == "inventory":
            title = "Crafting"
            inv = self.me["inv"]
            for i in range(9, 36):
                slots.append(self._slot(i, inv[i], True, i))
            for k in range(9):
                slots.append(self._slot(36 + k, inv[k], True, k))
        elif kind == "minion":
            title = "Cobblestone Minion I"
            slots.append(self._slot(48, _item("minecraft:chest", "Collect All")))
        return {"type": "ContainerScreen", "title": title, "titleLegacy": title, "containerId": 1,
                "menuType": "minecraft:generic_9x6", "containerSlots": 54, "slots": slots, "carried": None}

    def click(self, slot: int, button: int = 0, action: str = "PICKUP") -> dict:
        self.calls.append(("click", {"slot": slot, "button": button, "action": action}))
        w, m = self.w, self.me
        s = m["screen"]
        if not s:
            raise _err("click: no screen open")
        sj = self._screen_json()
        sl = next((x for x in sj["slots"] if x["slot"] == slot), None)
        if sl is None:
            return {"ok": True}
        name = sl.get("name")
        if s["kind"] == "sbmenu":
            if name == "Your Skills":
                m["screen"] = {"kind": "generic", "title": "Your Skills", "buttons": ["Farming", "Mining"]}
            elif name == "Close":
                m["screen"] = None
        elif s["kind"] == "shop":
            if sl.get("container") is False and sl.get("id"):
                sky = self._sky(sl)
                if sky in SELL:
                    cnt = m["inv"][sl["containerSlot"]]["count"]
                    gain = SELL[sky] * cnt
                    m["inv"][sl["containerSlot"]] = None
                    m["purse"] += gain
                    w.say_to(self.account, f"You sold {sl['name']} x{cnt} for {gain:g} Coins!")
                else:
                    w.say_to(self.account, "Merchants won't buy that item.")
            elif sl.get("container") and slot >= 10 and slot < 10 + len(SHOPS[s["shop"]]):
                sky, n, cost = SHOPS[s["shop"]][slot - 10]
                if m["purse"] < cost:
                    w.say_to(self.account, f"You don't have enough coins! ({cost} needed)")
                else:
                    m["purse"] -= cost
                    self._give(sky, n)
                    w.say_to(self.account, f"You bought {ITEMS[sky][1]}{' x' + str(n) if n > 1 else ''} for {cost} Coins!")
        elif s["kind"] == "recipe" and name == "Supercraft":
            res = s["item"]
            made, need = RECIPES[res]
            times = 1
            if action == "QUICK_MOVE":
                times = min(self._count(k) // v for k, v in need.items())
            done = 0
            for _ in range(times):
                if all(self._count(k) >= v for k, v in need.items()):
                    for k, v in need.items():
                        self._take(k, v)
                    self._give(res, made)
                    done += 1
            if done:
                w.say_to(self.account, f"Crafted {done * made}x {ITEMS[res][1]}!")
        elif s["kind"] == "inventory" and action == "SWAP":
            src = sl["containerSlot"]
            inv = m["inv"]
            inv[src], inv[button] = inv[button], inv[src]
        elif s["kind"] == "minion" and name == "Collect All":
            self._give("COBBLESTONE", 3)
            w.say_to(self.account, "Collected 3 Cobblestone.")
        return {"ok": True}

    # ------------------------------------------------------------ use / attack / mining
    def use(self, target: str = "auto", entity: int | None = None, hand: str = "main") -> dict:
        return self.call("use", {"target": target, "entity": entity, "hand": hand})

    def call(self, endpoint: str, body: dict | None = None, timeout=None) -> dict:
        body = body or {}
        if endpoint != "use":
            raise _err(f"{endpoint}: not mocked")
        self.calls.append(("use", dict(body)))
        w, m = self.w, self.me
        held = m["inv"][m["sel"]] if 0 <= m["sel"] < 9 else None
        if body.get("target") == "entity" and body.get("entity") is not None:
            e = next((x for x in w.entities[m["island"]] if x["id"] == body["entity"]), None)
            if e is None or math.dist((e["x"], e["y"], e["z"]), self._pos()) > 5:
                raise _err("use: entity out of reach")
            return self._interact(e)
        if body.get("target") == "block":
            if held and "Minion" in held["name"] and m["island"] == "private_island":
                x, y, z = body["x"], body["y"], body["z"]
                st = w._ent("private_island", "minecraft:armor_stand", x + 0.5, y + 1, z + 0.5, nameplate=None)
                m["minions"].append(st["id"])
                m["inv"][m["sel"]] = None
                w.say_to(self.account, "You placed a Cobblestone Minion I! (1/5)")
            return {"ok": True}
        if held and held["name"] == "SkyBlock Menu (Click)":
            m["screen"] = {"kind": "sbmenu"}
            return {"ok": True}
        if held and held["id"] == "minecraft:fishing_rod":
            if m["bobber"]:
                self._reel()
            else:
                la = m.get("look_at") or (m["x"] + 4, m["y"], m["z"])
                b = (math.floor(la[0]), math.floor(la[1] - 1), math.floor(la[2]))
                if w.blocks[m["island"]].get(b) == "water" or w.blocks[m["island"]].get((b[0], b[1] - 1, b[2])) == "water":
                    e = w._ent(m["island"], "minecraft:fishing_bobber", la[0], la[1] - 0.9, la[2])
                    m["bobber"] = e["id"]
                    m["bite_in"] = 3
            return {"ok": True}
        return {"ok": True}

    def _interact(self, e: dict) -> dict:
        w, m = self.w, self.me
        name = e.get("npc")
        if name == "Gerald":
            if "Gerald" not in m["talked"]:
                m["talked"].add("Gerald")
                w.say_to(self.account, "[NPC] Gerald: Oh! You're awake. Welcome to your island!")
                w.say_to(self.account, "[NPC] Gerald: Take this starter kit, you'll need it.")
                w.event("first_of_kind", [self.account], "talked to Gerald", what="talk_island_helper")
            else:
                m["screen"] = {"kind": "generic", "title": "Gerald", "buttons": ["Starter Kit", "Island Tips", "To the Hub"]}
                if not m["kit"]:
                    m["kit"] = True
                    for sky, n in (("ROOKIE_PICKAXE", 1), ("ROOKIE_AXE", 1), ("ROOKIE_HOE", 1), ("BREAD", 8)):
                        self._give(sky, n)
            return {"ok": True}
        if e.get("shop"):
            if name not in m["talked"]:
                m["talked"].add(name)
                w.say_to(self.account, f"[NPC] {name}: Take a look at my wares!")
            m["screen"] = {"kind": "shop", "shop": e["shop"]}
            return {"ok": True}
        if e.get("type") == "minecraft:armor_stand" and e["id"] in m["minions"]:
            m["screen"] = {"kind": "minion"}
            return {"ok": True}
        if name:
            w.say_to(self.account, f"[NPC] {name}: Lost? Open the Hub Map!")
            m["screen"] = {"kind": "generic", "title": "Hub Map", "buttons": ["Village", "Graveyard", "Farm"]}
        return {"ok": True}

    def attack(self, entity: int | None = None, nearest: str | None = None) -> dict:
        self.calls.append(("attack", {"entity": entity}))
        w, m = self.w, self.me
        e = next((x for x in w.entities[m["island"]] if x["id"] == entity), None)
        if e is None or "mob" not in e:
            raise _err("attack: no such entity")
        if math.dist((e["x"], e["y"], e["z"]), self._pos()) > 4.5:
            return {"ok": True, "hit": False, "attacked": False, "cooldownTicks": 10}
        held = m["inv"][m["sel"]] if 0 <= m["sel"] < 9 else None
        dmg = 5
        if held:
            for ln in held.get("lore") or []:
                if ln.startswith("Damage: +"):
                    dmg = 5 + int(ln[9:])
        mob = e["mob"]
        mob["hp"] -= dmg
        m["hp"] = max(0.0, m["hp"] - 2)          # it hits back a little
        if mob["hp"] <= 0:
            w.entities[m["island"]].remove(e)
            m["purse"] += mob["coins"]
            self._give(mob["drop"], 1)
            m["skills"]["COMBAT"] += 1
            w.event("mob_kill", [self.account], f"{self.account} killed a {mob['name']}", mob_name=mob["name"], coins=mob["coins"])
            # respawn one later at home
            hx, hy, hz = mob["home"]
            w._mob(m["island"], mob["name"], mob["lvl"], mob["max"], hx + 1, hy, hz + 1, mob["coins"], mob["drop"])
        else:
            self.w._plate(e)
        return {"ok": True, "hit": True, "attacked": True, "onTarget": True, "cooldownTicks": 10,
                "targetAlive": mob["hp"] > 0}

    def find_blocks(self, block: str, radius: int = 8, limit: int = 20, exposed: bool = False) -> list[dict]:
        m = self.me
        out = []
        for (x, y, z), bid in self.w.blocks[m["island"]].items():
            if block in bid:
                d = math.dist((x + .5, y + .5, z + .5), self._pos())
                if d <= min(24, radius) + 1:
                    out.append({"x": x, "y": y, "z": z, "id": f"minecraft:{bid}", "distance": d})
        out.sort(key=lambda b: b["distance"])
        return out[:limit]

    def mine_block_at(self, x: int, y: int, z: int, max_seconds: float = 10) -> dict:
        return self.attack_hold(max_seconds, x, y, z)

    def attack_hold(self, seconds: float, x=None, y=None, z=None) -> dict:
        self.calls.append(("attackhold", {"x": x, "y": y, "z": z}))
        w, m = self.w, self.me
        B = w.blocks[m["island"]]
        bid = B.get((x, y, z))
        eye = (m["x"], m["y"] + 1.62, m["z"])
        if bid is None:
            return {"ok": True, "broken": False, "before": "minecraft:air", "after": "minecraft:air"}
        if math.dist(eye, (x + .5, y + .5, z + .5)) > 4.8:
            raise _err(f"attackhold: block {x},{y},{z} is out of reach")
        held = m["inv"][m["sel"]] if 0 <= m["sel"] < 9 else None
        if bid == "coal_ore" and m["island"] == "hub" and not (held and "Pickaxe" in held["name"]):
            m["action"] = "You need a tool with Ⓟ Breaking Power 1 to mine Coal Ore!"
            return {"ok": True, "broken": False, "before": bid, "after": bid}
        m["action"] = ""
        if bid == "water":
            return {"ok": True, "broken": False, "before": bid, "after": bid}
        drop = BLOCK_DROPS.get(bid)
        B[(x, y, z)] = "bedrock" if m["island"] == "hub" and bid.endswith("_ore") else None
        if B[(x, y, z)] is None:
            del B[(x, y, z)]
        if drop:
            self._give(drop, 1)
            skill = {"OAK_LOG": "FORAGING", "WHEAT": "FARMING"}.get(drop, "MINING")
            m["skills"][skill] += 1
            m["action"] = f"+6 {skill.title()} (6/50)"
        return {"ok": True, "broken": True, "before": f"minecraft:{bid}", "after": "minecraft:air"}

    # ------------------------------------------------------------ fishing
    def _tick_fishing(self):
        m = self.me
        if not m.get("bobber"):
            return
        b = next((e for e in self.w.entities[m["island"]] if e["id"] == m["bobber"]), None)
        if b is None:
            m["bobber"] = None
            return
        if m["bite_in"] > 0:
            m["bite_in"] -= 1
            if m["bite_in"] == 0:
                t = self.w._ent(m["island"], "minecraft:text_display", b["x"], b["y"] + 0.9, b["z"], text="!!!")
                m["bite_label"] = t["id"]

    def _reel(self):
        w, m = self.w, self.me
        isl = w.entities[m["island"]]
        bit = m.get("bite_label")
        for e in list(isl):
            if e["id"] in (m["bobber"], bit):
                isl.remove(e)
        if bit:
            self._give("RAW_FISH", 1)
            m["skills"]["FISHING"] += 1
            m["action"] = "+5 Fishing (5/50)"
        m["bobber"], m["bite_label"] = None, None


def _err(msg: str):
    from .pilot_bridge import PilotError
    return PilotError(msg)


class MockShowControl:
    """ShowControl's HTTP API answered from a MockWorld (CONTRACT §4 shapes)."""

    def __init__(self, world: MockWorld) -> None:
        self.w = world

    def state(self, name: str) -> dict:
        p = self.w.players.get(name)
        if not p:
            return {"name": name, "online": False}
        inv = [{"id": MockPilot._sky(it), "name": it["name"], "count": it["count"]} for it in p["inv"] if it]
        return {"name": name, "online": True, "island": p["island"], "zone": None,
                "pos": {"x": p["x"], "y": p["y"], "z": p["z"]}, "health": p["hp"], "maxHealth": p["max_hp"],
                "defense": 0, "mana": 100, "maxMana": 100, "purse": p["purse"], "bank": 0,
                "skills": {k: {"level": v // 5, "xp": float(v * 6), "next": 50} for k, v in p["skills"].items()},
                "inventory": inv, "inventory_free": 36 - len(inv), "objective": "Talk to your Island Helper",
                "warps_unlocked": ["hub", "home"] + sorted(f"hub_{z}" for z in self.w.discovered.get(name, ())),
                "minions": [{"type": "COBBLESTONE", "tier": 1, "slot": i} for i, _ in enumerate(p["minions"])]}

    def players(self) -> list[dict]:
        return [{"name": a, "online": True, "island": p["island"], "health": p["hp"], "maxHealth": p["max_hp"],
                 "purse": p["purse"]} for a, p in self.w.players.items()]

    def events_since(self, n: int) -> list[dict]:
        return [e for e in self.w.events if e["id"] > n]

    def health(self) -> dict:
        return {"ok": True, "episode": {}, "tps": 20.0}


class MockCtx:
    """The ctx object macros/observe expect, backed by the mock world."""

    def __init__(self, world: MockWorld, account: str) -> None:
        self.world = world
        self.pilot = MockPilot(world, account)
        self.char = SHOW.by_account(account) or _FakeChar(account)
        self.logs: list[str] = []
        self.sc = MockShowControl(world)
        self.stop_event = threading.Event()

    def log(self, msg: str) -> None:
        self.logs.append(msg)

    def showcontrol_state(self) -> dict:
        return self.sc.state(self.char.account)


class _FakeChar:
    def __init__(self, account: str) -> None:
        self.id = self.name = self.account = account
        self.slot = 0


def _ctx(self: MockWorld, account: str) -> MockCtx:
    if account not in self.players:
        self.add_player(account)
    return MockCtx(self, account)


MockWorld.ctx = _ctx
