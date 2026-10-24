#!/usr/bin/env python3
"""
Generate the mines module's mob and spawn data.

    python tools/gen/mines/gen_mines_data.py

Reads the mob stat tables straight out of research/03-world-zones-mobs-npcs.md (section 9.2 Deep
Caverns, section 10.2 Dwarven Mines) so HP / damage / combat XP / coins come from the research, not
from a hand-typed copy. Bestiary family ids come from research/data/neu-repo/constants/bestiary.json
(the progression module matches families by these ids). Values the research does not give are marked
TUNE in the "_tune" field of the emitted entry.

Writes plugin/src/main/resources/data/mines/mobs.json and spawns.json.
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RESEARCH = os.path.join(ROOT, "research", "03-world-zones-mobs-npcs.md")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "mines")


def num(s):
    s = s.replace("(?)", "").replace(",", "").strip()
    m = re.match(r"^(\d+(?:\.\d+)?)", s)
    return float(m.group(1)) if m else None


def table_after(text, heading):
    """Rows (list of cell lists) of the first markdown table after a heading line."""
    i = text.index(heading)
    rows = []
    started = False
    for line in text[i:].splitlines()[1:]:
        if line.startswith("|"):
            started = True
            cells = [c.strip().replace("**", "") for c in line.strip().strip("|").split("|")]
            if set("".join(cells)) <= set("-: "):
                continue
            rows.append(cells)
        elif started:
            break
    return rows[1:]  # drop header


text = open(RESEARCH, encoding="utf-8").read()
deep = {r[0]: r for r in table_after(text, "### 9.2 Mobs")}
dwarf = {r[0].replace("**", ""): r for r in table_after(text, "### 10.2 Mobs")}


def deep_stats(name, idx=0):
    """Deep Caverns row: Mob | Layer | Lv | HP | Dmg | XP | Coins | Drops. idx picks a '/'-variant."""
    r = deep[name]
    pick = lambda cell: num(cell.split("/")[idx]) if "/" in cell else num(cell)
    lv, hp, dmg = pick(r[2]), pick(r[3]), pick(r[4])

    def spread(cell):  # "9–15": spread across the variants
        if "–" in cell:
            lo, hi = [num(x) for x in cell.split("–")]
            return round(lo + (hi - lo) * idx / 2)
        return pick(cell)
    xp, coins = spread(r[5]), spread(r[6])
    return dict(level=int(lv), health=hp, damage=dmg, combatXp=xp, coins=coins)


def dwarf_stats(name):
    """Dwarven row: Mob | Lv | HP | Dmg | XP | Drops | Where."""
    r = dwarf[name]
    return dict(level=int(num(r[1]) or 1), health=num(r[2]) or 100, damage=num(r[3]) or 0,
                combatXp=num(r[4]) or 0)


def drop(item, mn=1, mx=1, chance=1.0, label=None):
    d = {"item": item, "min": mn, "max": mx, "chance": chance}
    if label:
        d["label"] = label
    return d


def mob(id, name, entity, stats, drops, family, equipment=None, flags=("aggressive",), behaviours=(), extra=None,
        speed=1.0, tune=None, coins=None, skull=None):
    m = {"id": id, "name": name, "entity": entity, "level": stats["level"], "health": stats["health"],
         "damage": stats["damage"], "combatXp": stats["combatXp"],
         "coins": coins if coins is not None else stats.get("coins", 0), "speed": speed,
         "drops": drops, "family": family, "flags": list(flags), "behaviours": list(behaviours),
         "equipment": equipment or {}}
    if skull:
        m["skull"] = skull
    if extra:
        m["extra"] = extra
    if tune:
        m["_tune"] = tune
    return m


# ---- wave 3: looks and drop tables from the NEU repo ----
NEU = os.path.join(ROOT, "research", "data", "neu-repo")
DATA = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")


def bestiary_textures():
    """Bestiary head texture per family name (the reference's own mob heads)."""
    b = json.load(open(os.path.join(NEU, "constants", "bestiary.json"), encoding="utf-8"))
    out = {}

    def walk(o):
        if isinstance(o, dict):
            if "mobs" in o and "name" in o and all(isinstance(x, str) for x in o["mobs"]):
                out[re.sub("§.", "", o["name"])] = o.get("texture")
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(b)
    return out


def registered_items():
    """Every item id some module's item file registers (drops and equipment must name one of these)."""
    ids = set()
    for mod in os.listdir(DATA):
        d = os.path.join(DATA, mod)
        if not os.path.isdir(d):
            continue
        for fn in os.listdir(d):
            if not fn.endswith(".json") or "item" not in fn:
                continue
            try:
                arr = json.load(open(os.path.join(d, fn), encoding="utf-8"))
            except Exception:
                continue
            if isinstance(arr, dict):
                arr = arr.get("items", [])
            if isinstance(arr, list):
                for e in arr:
                    if isinstance(e, dict) and isinstance(e.get("id"), str):
                        ids.add(e["id"])
    return ids


HEADS = bestiary_textures()
GOBLIN_SKULL = HEADS.get("Goblin")
HOARDER_SKULL = HEADS.get("Treasure Hoarder")
ITEMS = registered_items()
# The reference Goblin family drops its armour set at 0.3% a piece (NEU GOBLIN_MONSTER, every Lv25 variant).
GOBLIN_ARMOR = ["GOBLIN_HELMET", "GOBLIN_CHESTPLATE", "GOBLIN_LEGGINGS", "GOBLIN_BOOTS"]
GLACITE_ARMOR = ["GLACITE_HELMET", "GLACITE_CHESTPLATE", "GLACITE_LEGGINGS", "GLACITE_BOOTS"]
# A goblin is a short humanoid with the bestiary's goblin head: an adult zombie at 0.8 scale, not a baby zombie
# (baby zombies run 50% faster than the def's speed and have a hitbox half a player tall).
GOBLIN_LOOK = {"scale": 0.8}


MINER_ARMOR = ["TANK_MINER_HELMET", "TANK_MINER_CHESTPLATE", "TANK_MINER_LEGGINGS", "TANK_MINER_BOOTS"]
LAPIS_ARMOR = ["LAPIS_ARMOR_HELMET", "LAPIS_ARMOR_CHESTPLATE", "LAPIS_ARMOR_LEGGINGS", "LAPIS_ARMOR_BOOTS"]

mobs = []
# ---- Gold Mine: the research lists no hostile mobs; the brief asks for zombie miners in the side tunnels ----
mobs.append(mob("gold_mine_zombie", "Zombie Miner", "ZOMBIE",
                dict(level=6, health=150, damage=30, combatXp=8, coins=2),
                [drop("ROTTEN_FLESH", 1, 2), drop("GOLD_INGOT", 1, 1, 0.05)], "gold_mine_zombie",
                {"HEAD": "GOLDEN_HELMET", "HAND": "GOLDEN_PICKAXE"},
                tune="no research numbers: a Combat 1-2 zombie between the hub graveyard (Lv1-5) and Lapis Zombies (Lv7)"))

# ---- Deep Caverns (research 03 section 9.2; coins, drops and looks from NEU *_MONSTER.json + neu-repo/mobs/*.json;
#      the Lv15 slime's 10 coins is TUNE -- NEU lists only Lv5/Lv10) ----
s = deep_stats("Sneaky Creeper")
mobs.append(mob("deep_caverns_sneaky_creeper", "Sneaky Creeper", "CREEPER", s,
                [drop("SULPHUR", 1, 3), drop("ENCHANTED_GUNPOWDER", 1, 1, 0.005)],
                "sneaky_creeper", behaviours=["mines_sneaky"], coins=3))
s = deep_stats("Lapis Zombie")
mobs.append(mob("deep_caverns_lapis_zombie", "Lapis Zombie", "ZOMBIE", s,
                [drop("ROTTEN_FLESH", 1, 2), drop("LAPIS_CRYSTAL", 1, 1, 0.01)] + [drop(a, 1, 1, 0.01) for a in LAPIS_ARMOR],
                "lapis_zombie", dict(zip(("HEAD", "CHEST", "LEGS", "FEET"), LAPIS_ARMOR)), coins=5,
                tune="NEU LAPIS_ZOMBIE_MONSTER: each armour piece 1%, Lapis Crystal 1%"))
s = deep_stats("Redstone Pigman")
mobs.append(mob("deep_caverns_redstone_pigman", "Redstone Pigman", "ZOMBIFIED_PIGLIN", s,
                [drop("GOLD_NUGGET", 1, 3, 0.5), drop("REDSTONE", 1, 3, 0.5), drop("FLAMING_SWORD", 1, 1, 0.01)],
                "redstone_pigman", {"HAND": "GOLDEN_SWORD"}, coins=12,
                tune="NEU REDSTONE_PIGMAN_MONSTER: nugget 50%, redstone 50%, Flaming Sword 1%"))
for i, lv in enumerate([5, 10, 15]):
    s = deep_stats("Emerald Slime", i)
    mobs.append(mob("deep_caverns_emerald_slime_%d" % lv, "Emerald Slime", "SLIME", s,
                    [drop("SLIME_BALL", 1, 2), drop("EMERALD", 1, 1, 0.05)], "emerald_slime",
                    behaviours=["mines_slime"], extra={"size": i + 2}, coins=(5, 8, 10)[i]))
# NEU mobs/miner_zombie.json: Enchanted Diamond Block on the head + Miner's Outfit (TANK_MINER_*); 1% a piece.
MINER_LOOK = {"HEAD": "ENCHANTED_DIAMOND_BLOCK", "CHEST": MINER_ARMOR[1], "LEGS": MINER_ARMOR[2], "FEET": MINER_ARMOR[3]}
for kind, ent, loot, hand in (("Zombie", "ZOMBIE", "ROTTEN_FLESH", "DIAMOND_PICKAXE"),
                              ("Skeleton", "SKELETON", "BONE", "BOW")):
    s = deep_stats("Miner " + kind)
    mobs.append(mob("deep_caverns_miner_%s_15" % kind.lower(), "Miner " + kind, ent, s,
                    [drop(loot, 1, 3 if kind == "Skeleton" else 2)] + [drop(a, 1, 1, 0.01) for a in MINER_ARMOR],
                    "miner_" + kind.lower(), dict(MINER_LOOK, HAND=hand), coins=12,
                    tune="NEU MINER_*_MONSTER: each Miner's Outfit piece 1%"))
    s = deep_stats("Diamond " + kind)
    mobs.append(mob("deep_caverns_diamond_%s_20" % kind.lower(), "Diamond " + kind, ent, s,
                    [drop(loot, 1, 3 if kind == "Skeleton" else 2), drop("DIAMOND", 1, 1, 0.02)]
                    + [drop(a, 1, 1, 0.01) for a in MINER_ARMOR],
                    "miner_" + kind.lower(), {"HEAD": "DIAMOND_HELMET", "CHEST": "DIAMOND_CHESTPLATE",
                                              "LEGS": "DIAMOND_LEGGINGS", "FEET": "DIAMOND_BOOTS", "HAND": hand}, coins=15,
                    tune="'Diamond (rare)' taken as 2%; outfit 1% a piece like the Lv15 miners"))

# ---- Dwarven Mines (research 03 section 10.2; levels from the bestiary ids, drops from NEU GOBLIN_MONSTER) ----
# Only the Lv25 goblins live in the Goblin Burrows. The Lv70 Pitfighter, Lv100 Creepertamer/Flamethrower and Lv200
# Murderlover are Crystal Hollows (Goblin Holdout) mobs in the reference -- the hollows module owns them -- and
# spawning a Lv200 here killed players at the minecart station (PLAYTEST round 2). Removed in wave 3.
def goblin_drops(egg_chance):
    return [drop("GOBLIN_EGG", 1, 1, egg_chance)] + [drop(a, 1, 1, 0.003) for a in GOBLIN_ARMOR]


s = dwarf_stats("Goblin")
mobs.append(mob("dwarven_goblin", "Goblin", "ZOMBIE", s, goblin_drops(0.01), "goblin",
                {"HAND": "STONE_SWORD"}, speed=1.0, coins=10, skull=GOBLIN_SKULL, extra=GOBLIN_LOOK,
                tune="NEU: coins 10, egg 1%, goblin armour 0.3% a piece"))
s = dict(dwarf_stats("Weakling Goblin"), level=25)
mobs.append(mob("dwarven_goblin_weakling", "Weakling Goblin", "ZOMBIE", s, goblin_drops(0.005), "goblin",
                {"HAND": "WOODEN_SWORD"}, speed=0.9, coins=8, skull=GOBLIN_SKULL, extra=GOBLIN_LOOK,
                tune="level 25 from the bestiary id goblin_weakling_melee_25 (research said 20?)"))
mobs.append(mob("dwarven_goblin_weakling_bow", "Weakling Goblin", "SKELETON", s, goblin_drops(0.005),
                "goblin", {"HAND": "BOW"}, coins=8, skull=GOBLIN_SKULL, extra=GOBLIN_LOOK,
                tune="archer variant (bestiary goblin_weakling_bow_25), same stats as the melee weakling"))
s = dwarf_stats("Knifethrower")
mobs.append(mob("dwarven_goblin_knifethrower", "Knifethrower", "ZOMBIE", dict(s, combatXp=28), goblin_drops(0.005),
                "goblin", {"HAND": "IRON_SWORD"}, speed=0.9, behaviours=["mines_knife"], coins=10,
                skull=GOBLIN_SKULL, extra=GOBLIN_LOOK, tune="NEU: combat XP 28, egg 0.5%"))
s = dwarf_stats("Fireslinger")
mobs.append(mob("dwarven_goblin_fireslinger", "Fireslinger", "ZOMBIE", s, goblin_drops(0.02), "goblin",
                {"HAND": "BLAZE_ROD"}, flags=("aggressive", "fire_immune"), speed=0.9,
                behaviours=["mines_fireslinger"], coins=10, skull=GOBLIN_SKULL, extra=GOBLIN_LOOK,
                tune="NEU: egg 2% (plain Goblin Egg)"))
# NEU ICE_WALKER_MONSTER: 888 HP, 40 combat XP, 8 coins; Glacite Jewel + each Glacite armour piece 0.5%;
# mobs/ice_walker.json wears a packed-ice head and the Glacite set.
mobs.append(mob("dwarven_ice_walker", "Ice Walker", "ZOMBIE",
                dict(level=45, health=888, damage=dwarf_stats("Ice Walker")["damage"], combatXp=40),
                [drop("ICE", 1, 3), drop("GLACITE_JEWEL", 1, 1, 0.005)] + [drop(a, 1, 1, 0.005) for a in GLACITE_ARMOR],
                "glacite_walker", {"HEAD": "PACKED_ICE", "CHEST": GLACITE_ARMOR[1], "LEGS": GLACITE_ARMOR[2],
                                   "FEET": GLACITE_ARMOR[3], "HAND": "IRON_SWORD"},
                behaviours=["mines_frost"], coins=8, tune="NEU numbers (888 HP / 40 XP / 8 coins); damage from research"))
s = dwarf_stats("Treasure Hoarder")
mobs.append(mob("dwarven_treasure_hoarder", "Treasure Hoarder", "ZOMBIE", dict(s, combatXp=70),
                [drop("STARFALL", 1, 2), drop("TREASURITE", 1, 3, 0.5), drop("SALMON_OPAL", 1, 1, 0.01)],
                "treasure_hoarder", {"HAND": "IRON_PICKAXE"}, speed=0.9, coins=30, skull=HOARDER_SKULL,
                extra=GOBLIN_LOOK,
                tune="NEU: Starfall 100%, Salmon Opal 1%, 70 combat XP; Treasurite raised from the reference 0.5% to 50% "
                     "because the mining module's drill parts need it (research said Treasurite is this mob's drop)"))
s = dwarf_stats("Ghost")
mobs.append(mob("dwarven_ghost", "Ghost", "CREEPER", s,
                [drop("VOLTA", 1, 1, 0.002), drop("SORROW", 1, 1, 0.0012), drop("PLASMA", 1, 1, 0.001),
                 drop("GHOST_BOOTS", 1, 1, 0.0001)], "ghost", flags=("aggressive", "no_knockback"),
                behaviours=["mines_ghost"], coins=20,
                tune="the research's 1,000,000-coin 0.01% drop is paid as a coin burst by the mines_ghost behaviour"))

missing = sorted({d["item"] for m in mobs for d in m["drops"]} - ITEMS)
if missing:
    raise SystemExit("drop items not registered by any module: %s" % ", ".join(missing))
for name, tex in (("Goblin", GOBLIN_SKULL), ("Treasure Hoarder", HOARDER_SKULL)):
    if not tex:
        raise SystemExit("bestiary has no head texture for " + name)


spawns = []


def spawn(id, island, area, mob_id, mx, respawn):
    spawns.append({"id": id, "island": island, "area": area, "mob": mob_id, "max": mx, "respawnSeconds": respawn})


spawn("mines_gold_grotto", "gold_mine", "lapis_grotto", "gold_mine_zombie", 6, 12)
spawn("mines_gold_gallery", "gold_mine", "gold_gallery", "gold_mine_zombie", 4, 15)
spawn("mines_deep_creepers", "deep_caverns", "gunpowder_mines", "deep_caverns_sneaky_creeper", 8, 15)
spawn("mines_deep_lapis", "deep_caverns", "lapis_quarry", "deep_caverns_lapis_zombie", 12, 10)
spawn("mines_deep_pigmen", "deep_caverns", "pigmens_den", "deep_caverns_redstone_pigman", 12, 10)
for lv in (5, 10, 15):
    spawn("mines_deep_slime_%d" % lv, "deep_caverns", "slimehill", "deep_caverns_emerald_slime_%d" % lv, 5, 12)
spawn("mines_deep_miner_zombie", "deep_caverns", "diamond_reserve", "deep_caverns_miner_zombie_15", 7, 12)
spawn("mines_deep_miner_skeleton", "deep_caverns", "diamond_reserve", "deep_caverns_miner_skeleton_15", 6, 12)
spawn("mines_deep_diamond_zombie", "deep_caverns", "obsidian_sanctuary", "deep_caverns_diamond_zombie_20", 7, 15)
spawn("mines_deep_diamond_skeleton", "deep_caverns", "obsidian_sanctuary", "deep_caverns_diamond_skeleton_20", 6, 15)
for mob_id, mx, rs in (("dwarven_goblin_weakling", 8, 10), ("dwarven_goblin_weakling_bow", 5, 12), ("dwarven_goblin", 8, 12),
                       ("dwarven_goblin_knifethrower", 4, 15), ("dwarven_goblin_fireslinger", 4, 15)):
    spawn("mines_" + mob_id, "dwarven_mines", "goblin_burrows", mob_id, mx, rs)
spawn("mines_ramparts_goblins", "dwarven_mines", "ramparts_quarry", "dwarven_goblin", 4, 20)
spawn("mines_ramparts_weaklings", "dwarven_mines", "ramparts_quarry", "dwarven_goblin_weakling", 4, 20)
spawn("mines_ice_walkers", "dwarven_mines", "great_ice_wall", "dwarven_ice_walker", 10, 15)
spawn("mines_ghosts", "dwarven_mines", "the_mist", "dwarven_ghost", 10, 20)
spawn("mines_treasure_hoarders", "dwarven_mines", "upper_mines", "dwarven_treasure_hoarder", 5, 30)

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "mobs.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(mobs, f, indent=1, ensure_ascii=False)
with open(os.path.join(OUT, "spawns.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(spawns, f, indent=1, ensure_ascii=False)
print("wrote %d mobs, %d spawn rules" % (len(mobs), len(spawns)))
for m in mobs:
    print("  %-36s Lv%-4d hp %-10s dmg %-6s xp %-5s coins %s" % (m["id"], m["level"], m["health"], m["damage"], m["combatXp"], m["coins"]))
