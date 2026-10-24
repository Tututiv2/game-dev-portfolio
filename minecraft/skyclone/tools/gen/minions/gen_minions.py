#!/usr/bin/env python3
"""
Generate every data file of the minions module from the research data. Never hand-edit the output.

    py -3 tools/gen/minions/gen_minions.py

Reads
  research/data/items.json                       ids, names, rarities, skull textures of every minion tier
  research/data/neu-repo/items/*.json            per-tier Time Between Actions + Max Storage (lore), recipes, crafttext
  research/data/neu-repo/constants/misc.json     max tier per type, SkyBlock XP per unique tier
  research/data/collections.json                 which collection tier unlocks which minion's recipes
  research/data/skyhanni-repo/constants/MinionXP.json   skill XP per collected item
  research/02-skills-collections-minions-level.md       section 4.10 (drops per harvest) + 4.3 (slot table)

Writes plugin/src/main/resources/data/minions/
  items.json        every minion tier (61 types x 11-12 tiers) + upgrades, fuels, storage chests, crystals, skins, stones
  recipes.json      crafting recipes for all of the above (grids, unlock keys)
  types.json        per type: category, kind, visuals, tiers (TBA, storage), drops, unlock key
  fuels.json        speed / multiplier / duration of every fuel
  upgrades.json     every upgrade, shipping item, storage chest, crystal, skin, upgrade stone
  compaction.json   Compactor (block), Super Compactor (enchanted) and Auto Smelter tables
  xp.json           item id -> {skill, xp} awarded when an item is collected from a minion
  slots.json        unique-crafts -> placeable-minion table, SkyBlock XP per new unique tier
  report.txt        (next to this script) everything skipped, overridden or unresolved
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "research")
DATA = os.path.join(RES, "data")
NEU = os.path.join(DATA, "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "minions")

REPORT = []


def note(msg):
    REPORT.append(msg)


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


ITEMS = {x["id"]: x for x in load(os.path.join(DATA, "items.json"))["items"]}
COLOR_CODE = re.compile(r"§.")


def strip(s):
    return COLOR_CODE.sub("", s or "")


def neu(item_id):
    p = os.path.join(NEU, item_id.replace(":", "-") + ".json")
    if not os.path.exists(p):
        return None
    try:
        return load(p)
    except Exception:
        return None


ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"]
ROMAN_TO_INT = {r: i for i, r in enumerate(ROMAN) if r}

# ----------------------------------------------------------------------------------------------
# 1. The type table. Design data (what the minion LOOKS like and what space it needs); the numbers
#    (TBA, storage, drops, recipes) all come from the research below.
#    kind: BLOCK (places/mines a block), CROP (plants/harvests), MOB (spawns/slays), TREE (grows/chops),
#          WATER (fishes from nearby water)
#    floor (what must be under a work cell): NONE, SOLID, FARMLAND, SOIL, SAND, SOUL_SAND, GRASS, WATERSIDE
# ----------------------------------------------------------------------------------------------
T = {}


def typ(tid, name, category, kind, visual, floor, color, skill=None):
    T[tid] = dict(id=tid, name=name, category=category, kind=kind, visual=visual, floor=floor,
                  color=color, skill=skill or {"SLAYER": "COMBAT"}.get(category, category))


# farming
typ("WHEAT", "Wheat", "FARMING", "CROP", "WHEAT", "FARMLAND", "#E3C35B")
typ("CARROT", "Carrot", "FARMING", "CROP", "CARROTS", "FARMLAND", "#F08A24")
typ("POTATO", "Potato", "FARMING", "CROP", "POTATOES", "FARMLAND", "#C9A35A")
typ("PUMPKIN", "Pumpkin", "FARMING", "CROP", "PUMPKIN", "SOIL", "#E07B1A")
typ("MELON", "Melon", "FARMING", "CROP", "MELON", "SOIL", "#6FAF2E")
typ("MUSHROOM", "Mushroom", "FARMING", "CROP", "RED_MUSHROOM", "SOLID", "#B23A2E")
typ("COCOA", "Cocoa Beans", "FARMING", "CROP", "COCOA", "SOLID", "#7A4A23")
typ("CACTUS", "Cactus", "FARMING", "CROP", "CACTUS", "SAND", "#3E8A2E")
typ("SUGAR_CANE", "Sugar Cane", "FARMING", "CROP", "SUGAR_CANE", "WATERSIDE", "#8FD16A")
typ("NETHER_WARTS", "Nether Wart", "FARMING", "CROP", "NETHER_WART", "SOUL_SAND", "#8E1B23")
typ("SUNFLOWER", "Sunflower", "FARMING", "CROP", "SUNFLOWER", "GRASS", "#F2D42C")
typ("CHICKEN", "Chicken", "FARMING", "MOB", "CHICKEN", "SOLID", "#F4F4F4")
typ("COW", "Cow", "FARMING", "MOB", "COW", "SOLID", "#5A3E2B")
typ("PIG", "Pig", "FARMING", "MOB", "PIG", "SOLID", "#EFA3A3")
typ("SHEEP", "Sheep", "FARMING", "MOB", "SHEEP", "SOLID", "#E8E8E8")
typ("RABBIT", "Rabbit", "FARMING", "MOB", "RABBIT", "SOLID", "#A27D5A")
# mining
typ("COBBLESTONE", "Cobblestone", "MINING", "BLOCK", "COBBLESTONE", "NONE", "#7A7A7A")
typ("OBSIDIAN", "Obsidian", "MINING", "BLOCK", "OBSIDIAN", "NONE", "#2B1E3D")
typ("GLOWSTONE", "Glowstone", "MINING", "BLOCK", "GLOWSTONE", "NONE", "#F7D774")
typ("GRAVEL", "Gravel", "MINING", "BLOCK", "GRAVEL", "SOLID", "#8C8481")
typ("SAND", "Sand", "MINING", "BLOCK", "SAND", "SOLID", "#E6D9A3")
typ("RED_SAND", "Red Sand", "MINING", "BLOCK", "RED_SAND", "SOLID", "#C1652A")
typ("ICE", "Ice", "MINING", "BLOCK", "PACKED_ICE", "NONE", "#9CC3F5")
typ("SNOW", "Snow", "MINING", "BLOCK", "SNOW_BLOCK", "NONE", "#F5FBFF")
typ("COAL", "Coal", "MINING", "BLOCK", "COAL_ORE", "NONE", "#2E2E2E")
typ("IRON", "Iron", "MINING", "BLOCK", "IRON_ORE", "NONE", "#D8AF93")
typ("GOLD", "Gold", "MINING", "BLOCK", "GOLD_ORE", "NONE", "#F4D03F")
typ("DIAMOND", "Diamond", "MINING", "BLOCK", "DIAMOND_ORE", "NONE", "#4EE2EC")
typ("LAPIS", "Lapis", "MINING", "BLOCK", "LAPIS_ORE", "NONE", "#2950B8")
typ("REDSTONE", "Redstone", "MINING", "BLOCK", "REDSTONE_ORE", "NONE", "#B3150C")
typ("EMERALD", "Emerald", "MINING", "BLOCK", "EMERALD_ORE", "NONE", "#17DD62")
typ("QUARTZ", "Quartz", "MINING", "BLOCK", "NETHER_QUARTZ_ORE", "NONE", "#E9E1D6")
typ("ENDER_STONE", "End Stone", "MINING", "BLOCK", "END_STONE", "NONE", "#DFE3A6")
typ("MITHRIL", "Mithril", "MINING", "BLOCK", "PRISMARINE", "NONE", "#5BC3B2")
typ("HARD_STONE", "Hard Stone", "MINING", "BLOCK", "STONE", "NONE", "#6E6E6E")
typ("MYCELIUM", "Mycelium", "MINING", "BLOCK", "MYCELIUM", "NONE", "#7B6A80")
# combat
typ("ZOMBIE", "Zombie", "COMBAT", "MOB", "ZOMBIE", "SOLID", "#3F7A3A")
typ("SKELETON", "Skeleton", "COMBAT", "MOB", "SKELETON", "SOLID", "#C9C9C9")
typ("SPIDER", "Spider", "COMBAT", "MOB", "SPIDER", "SOLID", "#3A302A")
typ("CAVESPIDER", "Cave Spider", "COMBAT", "MOB", "CAVE_SPIDER", "SOLID", "#0E3C46")
typ("CREEPER", "Creeper", "COMBAT", "MOB", "CREEPER", "SOLID", "#4CB53A")
typ("ENDERMAN", "Enderman", "COMBAT", "MOB", "ENDERMAN", "SOLID", "#1A1A22")
typ("BLAZE", "Blaze", "COMBAT", "MOB", "BLAZE", "SOLID", "#F2A81C")
typ("MAGMA_CUBE", "Magma Cube", "COMBAT", "MOB", "MAGMA_CUBE", "SOLID", "#5A1E0E")
typ("SLIME", "Slime", "COMBAT", "MOB", "SLIME", "SOLID", "#6DBE5A")
typ("GHAST", "Ghast", "COMBAT", "MOB", "GHAST", "NONE", "#F0F0F0")   # wave 5: a ghast scaled to ~1 block floats in the pen
typ("VAMPIRE", "Vampire", "COMBAT", "MOB", "BAT", "NONE", "#6B0F1A")
# slayer
typ("REVENANT", "Ghoul", "SLAYER", "MOB", "ZOMBIE", "SOLID", "#25401F")
typ("TARANTULA", "Broodweaver", "SLAYER", "MOB", "SPIDER", "SOLID", "#1E1414")
typ("VOIDLING", "Void Wraith", "SLAYER", "MOB", "ENDERMAN", "SOLID", "#3B1C5A")
typ("INFERNO", "Inferno", "SLAYER", "MOB", "BLAZE", "SOLID", "#C2410C")
# foraging
typ("OAK", "Oak", "FORAGING", "TREE", "OAK", "SOIL", "#A27A47")
typ("SPRUCE", "Spruce", "FORAGING", "TREE", "SPRUCE", "SOIL", "#5E4128")
typ("BIRCH", "Birch", "FORAGING", "TREE", "BIRCH", "SOIL", "#E3DCC1")
typ("DARK_OAK", "Dark Oak", "FORAGING", "TREE", "DARK_OAK", "SOIL", "#3F2A16")
typ("ACACIA", "Acacia", "FORAGING", "TREE", "ACACIA", "SOIL", "#B05A30")
typ("JUNGLE", "Jungle", "FORAGING", "TREE", "JUNGLE", "SOIL", "#9C7148")
typ("FLOWER", "Flower", "FORAGING", "CROP", "FLOWERS", "GRASS", "#E85D9A")
# fishing
typ("FISHING", "Fishing", "FISHING", "WATER", "", "NONE", "#2D7FB8")
typ("CLAY", "Clay", "FISHING", "WATER", "", "NONE", "#A4A9B8")
typ("LILY_PAD", "Lily Pad", "FISHING", "WATER", "LILY_PAD", "NONE", "#2F8A3B")

# Our display names for the reference minions named after slayer bosses (see docs/names/minions.md).
REF_NAME = {}   # type -> reference name, filled from items.json below

# ----------------------------------------------------------------------------------------------
# 2. Drops per harvest. Parsed from research 02 section 4.10; names mapped to the reference ids.
# ----------------------------------------------------------------------------------------------
NAME2ID = {
    "Cactus": "CACTUS", "Cactus Green": "INK_SACK:2", "Carrot": "CARROT_ITEM", "Raw Chicken": "RAW_CHICKEN",
    "Feather": "FEATHER", "Egg": "EGG", "Cocoa Beans": "INK_SACK:3", "Raw Beef": "RAW_BEEF", "Leather": "LEATHER",
    "Melon Slice": "MELON", "Brown Mushroom": "BROWN_MUSHROOM", "Red Mushroom": "RED_MUSHROOM",
    "Nether Wart": "NETHER_STALK", "Raw Porkchop": "PORK", "Potato": "POTATO_ITEM", "Pumpkin": "PUMPKIN",
    "Raw Rabbit": "RABBIT", "Rabbit's Foot": "RABBIT_FOOT", "Rabbit Hide": "RABBIT_HIDE", "Raw Mutton": "MUTTON",
    "White Wool": "WOOL", "Sugar Cane": "SUGAR_CANE", "Sunflower": "DOUBLE_PLANT", "Moonflower": "MOONFLOWER",
    "Wheat": "WHEAT", "Seeds": "SEEDS", "Coal": "COAL", "Cobblestone": "COBBLESTONE", "Diamond": "DIAMOND",
    "Emerald": "EMERALD", "End Stone": "ENDER_STONE", "Glowstone Dust": "GLOWSTONE_DUST", "Gold Ore": "GOLD_ORE",
    "Gold Ingot": "GOLD_INGOT", "Gravel": "GRAVEL", "Flint": "FLINT", "Hard Stone": "HARD_STONE", "Ice": "ICE",
    "Iron Ore": "IRON_ORE", "Iron Ingot": "IRON_INGOT", "Lapis Lazuli": "INK_SACK:4", "Mithril": "MITHRIL_ORE",
    "Mycelium": "MYCEL", "Obsidian": "OBSIDIAN", "Nether Quartz": "QUARTZ", "Red Sand": "SAND:1",
    "Redstone Dust": "REDSTONE", "Sand": "SAND", "Snowball": "SNOW_BALL", "Blaze Rod": "BLAZE_ROD",
    "Spider Eye": "SPIDER_EYE", "String": "STRING", "Gunpowder": "SULPHUR", "Ender Pearl": "ENDER_PEARL",
    "Ghast Tear": "GHAST_TEAR", "Magma Cream": "MAGMA_CREAM", "Bone": "BONE", "Slimeball": "SLIME_BALL",
    "Hemovibe": "HEMOVIBE", "Rotten Flesh": "ROTTEN_FLESH", "Poisonous Potato": "POISONOUS_POTATO",
    "Crude Gabagool": "CRUDE_GABAGOOL", "Enchanted Ender Pearl": "ENCHANTED_ENDER_PEARL",
    "Acacia Log": "LOG_2", "Birch Log": "LOG:2", "Dark Oak Log": "LOG_2:1", "Jungle Log": "LOG:3", "Oak Log": "LOG",
    "Spruce Log": "LOG:1", "Dandelion": "YELLOW_FLOWER", "Poppy": "RED_ROSE", "Allium": "RED_ROSE:2",
    "Azure Bluet": "RED_ROSE:3", "Blue Orchid": "RED_ROSE:1", "Oxeye Daisy": "RED_ROSE:8", "Red Tulip": "RED_ROSE:4",
    "Orange Tulip": "RED_ROSE:5", "White Tulip": "RED_ROSE:6", "Pink Tulip": "RED_ROSE:7", "Lilac": "DOUBLE_PLANT:1",
    "Peony": "DOUBLE_PLANT:5", "Rose Bush": "DOUBLE_PLANT:4", "Clay Ball": "CLAY_BALL", "Raw Cod": "RAW_FISH",
    "Raw Salmon": "RAW_FISH:1", "Pufferfish": "RAW_FISH:3", "Tropical Fish": "RAW_FISH:2",
    "Prismarine Crystals": "PRISMARINE_CRYSTALS", "Prismarine Shard": "PRISMARINE_SHARD", "Sponge": "SPONGE",
}
ROW_NAME = {"Cocoa Beans": "COCOA", "Nether Wart": "NETHER_WARTS", "Cave Spider": "CAVESPIDER",
            "End Stone": "ENDER_STONE", "Dark Oak": "DARK_OAK"}

with open(os.path.join(RES, "02-skills-collections-minions-level.md"), encoding="utf-8") as f:
    MD = f.read()

sec = MD[MD.index("### 4.10"):MD.index("### 4.11")]
DROPS = {}
XP_RESEARCH = {}
for line in sec.splitlines():
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) < 8 or cells[0] in ("Minion", "---") or cells[0].startswith("---"):
        continue
    nm = cells[0]
    tid = ROW_NAME.get(nm, nm.upper().replace(" ", "_"))
    if tid not in T:
        note("research 4.10 row for unknown type: " + nm)
        continue
    out = []
    for part in cells[3].split(";"):
        m = re.match(r"\s*(.+?) ×([0-9.]+) \(([0-9.]+) (?:[A-Za-z]+ )?xp\)", part)
        if not m:
            note("unparsed drop '%s' of %s" % (part, tid))
            continue
        name, avg, xp = m.group(1), float(m.group(2)), float(m.group(3))
        iid = NAME2ID.get(name)
        if iid is None or iid not in ITEMS:
            note("drop name not mapped: %s (%s)" % (name, tid))
            continue
        out.append({"item": iid, "avg": avg})
        XP_RESEARCH.setdefault(iid, (T[tid]["skill"] if tid != "FLOWER" else "FORAGING", xp))
    DROPS[tid] = out

# Overrides: the research row lists every item a minion CAN yield (incl. smelted / upgrade-only forms).
# What a minion yields with no upgrades, per harvest (TUNE where marked):
OVERRIDE = {
    "CACTUS": [("CACTUS", 3)],                                  # Cactus Green is the Auto Smelter form
    "CHICKEN": [("RAW_CHICKEN", 1), ("FEATHER", 1)],            # Egg only with the Enchanted Egg upgrade
    "GOLD": [("GOLD_INGOT", 1)],                                 # gold yields ingots directly
    "IRON": [("IRON_ORE", 1)],                                   # iron needs the Auto Smelter for ingots
    "GRAVEL": [("GRAVEL", 0.9), ("FLINT", 0.1)],                 # TUNE: flint 1 in 10 without a Flint Shovel
    "SUNFLOWER": [("DOUBLE_PLANT", 1.5), ("MOONFLOWER", 1.5)],   # day/night split; Dayswitch/Nightswitch force one
    "LILY_PAD": [("WATER_LILY", 1)],                              # TUNE: 61st minion, research has no row
}
for tid, lst in OVERRIDE.items():
    DROPS[tid] = [{"item": i, "avg": a} for i, a in lst]
    note("drops override %s -> %s" % (tid, lst))
XP_RESEARCH.setdefault("WATER_LILY", ("FISHING", 0.8))

for tid in T:
    if tid not in DROPS:
        note("NO DROPS for " + tid)

# ----------------------------------------------------------------------------------------------
# 3. Tiers: TBA and storage from the NEU lore of every tier item; names/rarities/skins from items.json.
# ----------------------------------------------------------------------------------------------
NEU_PREFIX = {tid: tid for tid in T}
MISC = load(os.path.join(DATA, "neu-repo", "constants", "misc.json"))
MAXT = {k.replace("_GENERATOR", ""): v for k, v in MISC["minions"].items()}
SBXP = {int(k): v for k, v in MISC["minionXp"].items()}

TOOL_OF = {"WOOD_PICKAXE": "PICKAXE", "WOOD_HOE": "HOE", "WOOD_AXE": "AXE", "WOOD_SWORD": "SWORD",
           "WOOD_SPADE": "SHOVEL", "FISHING_ROD": "ROD"}


def grid_of(recipe):
    """NEU grid: the LETTER is the row (A top), the DIGIT the column (1 left). Verified on shaped
    recipes: Budget Hopper (A1 _ A3 / B1 B2 B3 / _ C2 _ = the vanilla hopper V) and Solar Panel."""
    if recipe is None:
        return None
    g = []
    for row in "ABC":
        for col in "123":
            c = recipe.get(row + col, "") or ""
            g.append(norm_cell(c))
    return g


def norm_cell(c):
    """NEU writes variants with '-' (INK_SACK-3:10); the game's ids use ':' (INK_SACK:3:10)."""
    if not c:
        return ""
    parts = c.rsplit(":", 1)
    if len(parts) == 2 and parts[1].isdigit():
        iid, amt = parts[0].replace("-", ":"), int(parts[1])
    else:
        iid, amt = c.replace("-", ":"), 1
    # Core's cell rule: a cell that IS an id means x1, so x1 is written bare ("WOOD", never "WOOD:1",
    # which is Spruce Planks). An "ID:N" that is itself another id cannot be expressed: report it.
    if amt == 1:
        return iid
    cell = "%s:%d" % (iid, amt)
    if cell in ITEMS:
        note("AMBIGUOUS recipe cell %s (is also an item id)" % cell)
    return cell


def cell_id(c):
    """Core's rule: a cell that IS an item id is that item x1; otherwise ID:amount."""
    if c in ITEMS:
        return c
    parts = c.rsplit(":", 1)
    return parts[0] if len(parts) == 2 and parts[1].isdigit() else c


def cell_amount(c):
    if c in ITEMS:
        return 1
    parts = c.rsplit(":", 1)
    return int(parts[1]) if len(parts) == 2 and parts[1].isdigit() else 1


def neu_recipe(d):
    if d is None:
        return None
    if d.get("recipe"):
        return d["recipe"]
    for r in d.get("recipes") or []:
        if r.get("type", "crafting") == "crafting" and any(k in r for k in ("A1", "B2")):
            return r
    return None


def num(s):
    return float(s.replace(",", "").rstrip("s"))


TYPES_OUT = []
MINION_ITEMS = []
RECIPES = []
COLL = load(os.path.join(DATA, "collections.json"))["collections"]

# collection unlock per reference minion name: "Cocoa Beans Minion Recipes" -> collection:INK_SACK:3:1
UNLOCK_BY_REFNAME = {}
COLL_BY_NAME = {}
for cat, c in COLL.items():
    for cid, rec in c["items"].items():
        COLL_BY_NAME[rec["name"].lower()] = cid
        for t in rec["tiers"]:
            for u in t["unlocks"]:
                m = re.match(r"(.+) Minion Recipes?$", u)
                if m:
                    UNLOCK_BY_REFNAME[m.group(1).lower()] = "collection:%s:%d" % (cid, t["tier"])

SPECIAL_UNLOCK = {  # minions whose gate is not a collection tier
    "REVENANT": "slayer:zombie:5", "TARANTULA": "slayer:spider:5", "VOIDLING": "slayer:enderman:4",
    "INFERNO": "slayer:blaze:3",
    "SNOW": "collection:ICE:2",            # TUNE: reference gives Snow I from winter gifts; we gate it on Ice II
    "FLOWER": "collection:DOUBLE_PLANT:2",  # TUNE: reference sells Flower I at an auction event; we let you craft it
}

SLAYER_WORDS = {"zombie": "zombie", "spider": "spider", "wolf": "wolf", "enderman": "enderman", "blaze": "blaze",
                "vampire": "vampire"}


def crafttext_key(text):
    """'Requires: Iron Ingot V' -> collection:IRON_INGOT:5 ; 'Requires: Zombie Slayer 9' -> slayer:zombie:9"""
    if not text:
        return None
    t = text.replace("Requires:", "").strip()
    m = re.match(r"(\w+) Slayer ([0-9]+|[IVX]+)$", t)
    if m and m.group(1).lower() in SLAYER_WORDS:
        lvl = m.group(2) if m.group(2).isdigit() else str(roman_int(m.group(2)))
        return "slayer:%s:%s" % (SLAYER_WORDS[m.group(1).lower()], lvl)
    m = re.match(r"(.+) ([IVXL]+)$", t)
    if m:
        name = m.group(1).lower()
        alias = {"mithril": "mithril", "nether quartz": "nether quartz", "raw cod": "raw cod",
                 "slimeball": "slimeball", "spider eye": "spider eye"}
        cid = COLL_BY_NAME.get(alias.get(name, name))
        if cid:
            return "collection:%s:%d" % (cid, roman_int(m.group(2)))
    note("crafttext not mapped: " + text)
    return None


def roman_int(r):
    vals = {"I": 1, "V": 5, "X": 10, "L": 50}
    total = 0
    for i, ch in enumerate(r):
        v = vals[ch]
        if i + 1 < len(r) and vals[r[i + 1]] > v:
            total -= v
        else:
            total += v
    return total


SEC411 = MD[MD.index("### 4.11"):MD.index("## 5. Crafting")]


def research_tiers(ref_name):
    """{tier: (tba, storage)} from the research 4.11 table of '<ref_name> Minion', or None."""
    if not ref_name:
        return None
    i = SEC411.find("#### %s Minion (" % ref_name)
    if i < 0:
        return None
    j = SEC411.find("#### ", i + 5)
    block = SEC411[i:j if j > 0 else len(SEC411)]
    tba, sto = None, None
    for line in block.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if cells and cells[0] == "TBA (s)":
            tba = cells[1:]
        if cells and cells[0] == "Storage (items)":
            sto = cells[1:]
    if tba is None:
        return None
    out = {}
    for n, v in enumerate(tba, start=1):
        try:
            t = float(v.replace(",", ""))
        except ValueError:
            t = None
        s = None
        if sto and n - 1 < len(sto):
            try:
                s = int(sto[n - 1].replace(",", ""))
            except ValueError:
                s = None
        out[n] = (t, s)
    return out


LORE_VERB = {"BLOCK": "mine", "CROP": "harvest", "MOB": "slay", "TREE": "chop", "WATER": "fish up"}
NEEDS = {
    "NONE": "Needs open air next to it.",
    "SOLID": "Needs solid ground around it.",
    "FARMLAND": "Needs dirt or farmland around it to plant on.",
    "SOIL": "Needs dirt or grass around it.",
    "SAND": "Needs sand around it to grow on.",
    "SOUL_SAND": "Needs soul sand around it.",
    "GRASS": "Needs grass around it to plant on.",
    "WATERSIDE": "Needs dirt or sand beside water.",
}

for tid, t in T.items():
    max_tier = MAXT.get(tid, 11)
    tiers = []
    first_recipe_center = None
    ref_name = None
    for n in range(1, max_tier + 1):
        iid = "%s_GENERATOR_%d" % (tid, n)
        rec = ITEMS.get(iid)
        d = neu(iid)
        if rec is None or d is None:
            note("missing tier item " + iid)
            continue
        lore = [strip(x) for x in d.get("lore", [])]
        tba = next((num(x.split(":")[1].strip()) for x in lore if x.startswith("Time Between Actions")), None)
        sto = next((int(x.split(":")[1].strip().replace(",", "")) for x in lore if x.startswith("Max Storage")), None)
        if tba is None or sto is None:
            note("no TBA/storage in lore of " + iid)
            continue
        ref_name = ref_name or re.sub(r" Minion [IVX]+$", "", rec["name"])
        tiers.append({"tier": n, "tba": tba, "storage": sto})
        grid = grid_of(neu_recipe(d))
        if n == 1 and grid:
            first_recipe_center = grid[4].split(":")[0]
        skin = (rec.get("skin") or {}).get("value")
        rarity = rec.get("tier", "RARE")
        lore_out = [
            "&7Set this minion down on your island",
            "&7and it will %s &a%s &7for you," % (LORE_VERB[t["kind"]], t["name"].lower() if t["kind"] != "MOB" else t["name"].lower() + "s"),
            "&7even while you are away.",
            "&8" + NEEDS[t["floor"]] if t["kind"] not in ("MOB", "WATER") else ("&8Needs nearby water." if t["kind"] == "WATER" else "&8Needs open ground for mobs."),
            "",
            "&7Time Between Actions: &a%ss" % (("%g" % tba)),
            "&7Max Storage: &e%d" % sto,
        ]
        if tid == "INFERNO":   # research 02 section 4.11: the Inferno Minion's ability, renamed
            lore_out[5:5] = ["&6Ability: Rising Heat", "&7Every Inferno Minion you place makes", "&7all of them &a18% &7faster, up to &a180%&7.", ""]
        MINION_ITEMS.append({
            "id": iid, "name": "%s Minion %s" % (t["name"], ROMAN[n]), "material": "PLAYER_HEAD",
            "rarity": rarity, "category": "MINION", "skull": skin, "lore": lore_out,
            "tags": ["minion", "no_reforge"], "extra": {"minionType": tid, "minionTier": n},
        })
        # recipe
        if grid and any(grid):
            unlock = crafttext_key(d.get("crafttext")) if "Slayer" in (d.get("crafttext") or "") else None
            RECIPES.append({"id": "minions:" + iid, "type": "CRAFTING", "result": iid, "amount": 1,
                            "grid": grid, "unlock": unlock or "@TYPE", "station": "Minion"})
        elif n == 1 and tid in ("SNOW", "FLOWER"):
            base = {"SNOW": "SNOW_BALL:32", "FLOWER": "YELLOW_FLOWER:10"}[tid]
            center = {"SNOW": "WOOD_SPADE", "FLOWER": "WOOD_HOE"}[tid]
            g = [base] * 9
            g[4] = center
            first_recipe_center = center.split(":")[0]
            RECIPES.append({"id": "minions:" + iid, "type": "CRAFTING", "result": iid, "amount": 1,
                            "grid": g, "unlock": "@TYPE", "station": "Minion"})
            note("synthesised tier I recipe for %s (TUNE): %s around %s" % (tid, base, center))
        else:
            note("no crafting recipe for %s (trade / upgrade stone tier)" % iid)
    REF_NAME[tid] = ref_name
    # Cross-check against research 02 section 4.11 (the human-checked wiki tables); research wins.
    rt = research_tiers(ref_name)
    if rt:
        for td in tiers:
            want = rt.get(td["tier"])
            if want is None:
                continue
            if want[0] is not None and abs(want[0] - td["tba"]) > 1e-9:
                note("TBA %s %d: NEU %s, research %s -> research" % (tid, td["tier"], td["tba"], want[0]))
                td["tba"] = want[0]
                for it in MINION_ITEMS:
                    if it["id"] == "%s_GENERATOR_%d" % (tid, td["tier"]):
                        it["lore"] = [("&7Time Between Actions: &a%gs" % want[0]) if l.startswith("&7Time Between") else l for l in it["lore"]]
            if want[1] is not None and want[1] != td["storage"]:
                note("storage %s %d: NEU %s, research %s -> research" % (tid, td["tier"], td["storage"], want[1]))
                td["storage"] = want[1]
                for it in MINION_ITEMS:
                    if it["id"] == "%s_GENERATOR_%d" % (tid, td["tier"]):
                        it["lore"] = [("&7Max Storage: &e%d" % want[1]) if l.startswith("&7Max Storage") else l for l in it["lore"]]
    else:
        note("research 4.11 has no table for %s (%s); NEU numbers used" % (tid, ref_name))
    unlock = SPECIAL_UNLOCK.get(tid) or UNLOCK_BY_REFNAME.get((ref_name or "").lower())
    if unlock is None:
        note("NO UNLOCK for %s (%s)" % (tid, ref_name))
    for r in RECIPES:
        if r["unlock"] == "@TYPE" and r["result"].startswith(tid + "_GENERATOR_"):
            r["unlock"] = unlock
    tool = TOOL_OF.get(first_recipe_center or "", {"FARMING": "HOE", "MINING": "PICKAXE", "COMBAT": "SWORD",
                                                    "SLAYER": "SWORD", "FORAGING": "AXE",
                                                    "FISHING": "ROD"}[t["category"]])
    TYPES_OUT.append(dict(t, refName=ref_name, maxTier=max_tier, tiers=tiers, drops=DROPS.get(tid, []),
                          unlock=unlock, tool=tool, actionsPerHarvest=2,
                          nameFromItem="MITHRIL_ORE" if tid == "MITHRIL" else None))

# ----------------------------------------------------------------------------------------------
# 4. Fuels, upgrades, shipping, storage chests, crystals, stones, skins. Numbers from the NEU lore
#    (quoted beside each) or research 02 section 4.4-4.6.
# ----------------------------------------------------------------------------------------------
H = 3600
FUELS = [
    # id, seconds (0 = unlimited), speed %, output multiplier, extra
    ("COAL", 30 * 60, 5, 1, {}),                    # "5% for 30 minutes"
    ("CHARCOAL", 30 * 60, 5, 1, {}),                # "5% for 30 minutes"
    ("COAL_BLOCK", 5 * H, 5, 1, {}),                # "5% for 5 hours"
    ("ENCHANTED_BREAD", 12 * H, 5, 1, {}),          # "5% for 12 hours"
    ("ENCHANTED_COAL", 24 * H, 10, 1, {}),          # "10% for 24 hours"
    ("ENCHANTED_CHARCOAL", 36 * H, 20, 1, {}),      # "20% for 36 hours"
    ("SOLAR_PANEL", 0, 25, 1, {"solar": True}),     # "25% during the day. Unlimited"
    ("ENCHANTED_LAVA_BUCKET", 0, 25, 1, {}),        # "25%. Unlimited"
    ("MAGMA_BUCKET", 0, 30, 1, {}),                 # "30%. Unlimited"
    ("PLASMA_BUCKET", 0, 35, 1, {}),                # "35%. Unlimited"
    ("EVERBURNING_FLAME", 0, 35, 1, {"categoryBonus": {"COMBAT": 5, "SLAYER": 5}}),  # "35% ... +5% on Combat minions"
    ("HAMSTER_WHEEL", 24 * H, 50, 1, {}),           # "50% for 24 hours"
    ("FOUL_FLESH", 5 * H, 90, 1, {}),               # "90% for 5 hours"
    ("CHEESE_FUEL", 1 * H, 0, 2, {}),               # "2x for 1 hour"
    ("CATALYST", 3 * H, 0, 3, {}),                  # "3x for 3 hours"
    ("HYPER_CATALYST", 6 * H, 0, 4, {}),            # "4x for 6 hours"
    # NEU lore: "Use this as Fuel on ONE Farming minion, and it will occasionally generate Jacob's Tickets for 3d".
    # TUNE: "occasionally" has no number; one ticket per 4 h of burn = 18 over the medal's 3 days (its recipe costs
    # one ticket + 32 Botroot), a little under the Super Jacob System's 1 per 3 h.
    ("JACOBS_PARTICIPATION_MEDAL", 72 * H, 0, 1, {"onlyCategory": "FARMING", "onePerProfile": True,
                                                    "perSecond": {"item": "JACOBS_TICKET", "every": 4 * H}}),
]
INFERNO_SPECIALTY = {  # NEU lore "Specialty amount"
    "CRUDE_GABAGOOL": ("CRUDE_GABAGOOL", 1, 1), "GLOWSTONE_DUST": ("GLOWSTONE_DUST", 2, 3),
    "MAGMA_CREAM": ("MAGMA_CREAM", 1, 3), "NETHER_STALK": ("NETHER_STALK", 3, 7), "BLAZE_ROD": ("BLAZE_ROD", 1, 1),
}
INFERNO_GRADES = {"FUEL": (10, "RARE"), "HEAVY": (15, "EPIC"), "HYPERGOLIC": (20, "LEGENDARY")}
# Hypergolic extras per harvest. TUNE: reference rates are per action and undocumented in the research.
HYPERGOLIC_EXTRAS = [("CHILI_PEPPER", 1 / 156.0), ("INFERNO_VERTEX", 1 / 16364.0),
                     ("REAPER_PEPPER", 1 / 458182.0), ("GABAGOOL_THE_FISH", 1 / 400000.0),
                     ("INFERNO_APEX", 1 / 1048576.0)]
FUELS_OUT = []
for fid, secs, spd, mult, extra in FUELS:
    FUELS_OUT.append(dict({"id": fid, "seconds": secs, "speed": spd, "mult": mult}, **extra))
for grade, (mult, rar) in INFERNO_GRADES.items():
    for spec, (sid, lo, hi) in INFERNO_SPECIALTY.items():
        fid = "INFERNO_%s_%s" % (grade, spec)
        f = {"id": fid, "seconds": 24 * H, "speed": 0, "mult": mult, "onlyType": "INFERNO",
             "specialty": {"item": sid, "avg": (lo + hi) / 2.0, "replace": 0.8}}
        if grade == "HYPERGOLIC":
            f["extras"] = [{"item": i, "avg": a} for i, a in HYPERGOLIC_EXTRAS]
        FUELS_OUT.append(f)

UPGRADES = [
    # id, slot, effect
    ("AUTO_SMELTER", "upgrade", {"smelt": True}),
    ("COMPACTOR", "upgrade", {"compact": "block"}),
    ("SUPER_COMPACTOR_3000", "upgrade", {"compact": "enchanted"}),
    ("DWARVEN_COMPACTOR", "upgrade", {"smelt": True, "compact": "enchanted"}),
    ("DIAMOND_SPREADING", "upgrade", {"spread": {"item": "DIAMOND", "perItem": 0.1}}),       # research: ~1 per 10 items
    ("POTATO_SPREADING", "upgrade", {"spread": {"item": "POTATO_ITEM", "perItem": 0.1}, "onePerProfile": True}),  # TUNE
    ("KRAMPUS_HELMET", "upgrade", {"spread": {"item": "RED_GIFT", "perItem": 0.001}, "onePerProfile": True}),     # TUNE
    ("SLEEPY_HOLLOW", "upgrade", {"spread": {"item": "PURPLE_CANDY", "perItem": 0.005}, "onePerProfile": True}),  # TUNE
    ("MINION_EXPANDER", "upgrade", {"speed": 5, "radius": 1}),                                 # "+1 block, 5%"
    ("FLYCATCHER_UPGRADE", "upgrade", {"speed": 20, "requirement": "slayer:spider:6"}),         # "20%"
    ("BERBERIS_FUEL_INJECTOR", "upgrade", {"speed": 15, "categories": ["FARMING"],
                                           "spread": {"item": "LUSH_BERBERIS", "perItem": 0.01}}),  # "15%"; rate TUNE
    ("ENCHANTED_EGG", "upgrade", {"types": ["CHICKEN"], "addDrops": [{"item": "EGG", "avg": 1}]}),
    ("FLINT_SHOVEL", "upgrade", {"types": ["GRAVEL"], "setDrops": [{"item": "FLINT", "avg": 1}]}),
    ("ENCHANTED_SHEARS", "upgrade", {"types": ["SHEEP"], "addDrops": [{"item": "WOOL", "avg": 1}]}),   # TUNE
    ("DAYSWITCH", "upgrade", {"types": ["SUNFLOWER"], "speed": 20, "setDrops": [{"item": "DOUBLE_PLANT", "avg": 3}]}),
    ("NIGHTSWITCH", "upgrade", {"types": ["SUNFLOWER"], "speed": 20, "setDrops": [{"item": "MOONFLOWER", "avg": 3}]}),
    ("THORNY_VINES", "upgrade", {"types": ["FLOWER"], "speed": 20, "setDrops": [{"item": "WILD_ROSE", "avg": 1}]}),
    ("LESSER_SOULFLOW_ENGINE", "upgrade", {"output": 0.5, "perSecond": {"item": "RAW_SOULFLOW", "every": 1800},
                                           "requirement": "slayer:enderman:2"}),                # rate TUNE
    ("SOULFLOW_ENGINE", "upgrade", {"output": 0.5, "perSecond": {"item": "RAW_SOULFLOW", "every": 900},
                                    "requirement": "slayer:enderman:5", "voidlingTierBonus": 3}),  # rate TUNE
    ("CORRUPT_SOIL", "upgrade", {"kinds": ["MOB"], "addDrops": [{"item": "SULPHUR_ORE", "avg": 0.5},
                                                                {"item": "CORRUPTED_FRAGMENT", "avg": 0.5}]}),  # TUNE
    # NEU lore: "Place it in ONE Farming Minion and it will generate a Jacob's Ticket once every 3h."
    ("SUPER_JACOB_SYSTEM", "upgrade", {"categories": ["FARMING"], "onePerProfile": True,
                                       "perSecond": {"item": "JACOBS_TICKET", "every": 3 * 3600}}),
    # NEU lore: loyal = "+10% Minion Speed"; loyalty chance 50% at tier I, +4% per tier (dials, MinionsModule).
    ("FREE_WILL", "freewill", {"speed": 10}),
    ("BUDGET_HOPPER", "shipping", {"sell": 0.5}),     # "50% of their selling price"
    ("ENCHANTED_HOPPER", "shipping", {"sell": 0.7}),  # "70% of their selling price"
    ("SMALL_ENCHANTED_CHEST", "chest", {"slots": 3}),
    ("MEDIUM_ENCHANTED_CHEST", "chest", {"slots": 9}),
    ("LARGE_ENCHANTED_CHEST", "chest", {"slots": 15}),
    ("XLARGE_ENCHANTED_CHEST", "chest", {"slots": 21}),
    ("XXLARGE_ENCHANTED_CHEST", "chest", {"slots": 27}),
    ("MITHRIL_INFUSION", "infusion", {"speed": 10}),
    ("FARM_CRYSTAL", "crystal", {"speed": 10, "categories": ["FARMING"]}),
    ("WOODCUTTING_CRYSTAL", "crystal", {"speed": 10, "categories": ["FORAGING"]}),
    ("MITHRIL_CRYSTAL", "crystal", {"speed": 10, "categories": ["MINING"]}),
    ("WINTER_ISLAND_CRYSTAL", "crystal", {"speed": 5, "types": ["SNOW"]}),
    ("CORNUCOPIA_CRYSTAL", "crystal", {"speed": 10, "categories": ["FARMING"], "perUniqueType": 1}),
    ("POSTCARD", "crystal", {"speed": 5}),
    # Research 02 section 4.6: Beacon I-V give +2/+4/+6/+8/+10% to every minion while powered; NEU lore: each
    # tier requires Heart of the Mountain tier N. Tier I is crafted (NEU recipe), II-V come from the Forge.
    # The radius (120-140 blocks) covers a whole private island here, so it is island-wide like the crystals.
    ("BEACON_1", "beacon", {"speed": 2, "tier": 1, "requirement": "hotm:1"}),
    ("BEACON_2", "beacon", {"speed": 4, "tier": 2, "requirement": "hotm:2"}),
    ("BEACON_3", "beacon", {"speed": 6, "tier": 3, "requirement": "hotm:3"}),
    ("BEACON_4", "beacon", {"speed": 8, "tier": 4, "requirement": "hotm:4"}),
    ("BEACON_5", "beacon", {"speed": 10, "tier": 5, "requirement": "hotm:5"}),
    # NEU lore: "Powers Beacons for up to 48 hours at a time"; Scorched adds "+1% minion speed buff".
    # Both items are registered by materials (mining materials); only their effect lives here.
    ("POWER_CRYSTAL", "power", {"seconds": 48 * 3600}),
    ("SCORCHED_POWER_CRYSTAL", "power", {"seconds": 48 * 3600, "speed": 1}),
]
for tid in T:
    sid = "GENERATOR_UPGRADE_STONE_%s_12" % tid
    if sid in ITEMS:
        UPGRADES.append((sid, "stone", {"types": [tid], "fromTier": 11, "toTier": 12}))

# ----------------------------------------------------------------------------------------------
# 5. Item defs for everything this module owns besides the minions themselves.
# ----------------------------------------------------------------------------------------------
OWN_NAMES = {  # id -> (our name, our lore lines). Proper nouns renamed (docs/names/minions.md).
    "AUTO_SMELTER": ("Auto Smelter", ["&7Smelts what the minion produces:", "&7ore into ingots, cobble into stone,", "&7sand into glass, logs into charcoal."]),
    "COMPACTOR": ("Compactor", ["&7Packs what the minion produces into", "&7its &ablock &7form as soon as there is", "&7enough of it in storage."]),
    "SUPER_COMPACTOR_3000": ("Ultra Compactor 3000", ["&7Packs what the minion produces into", "&7its &aenchanted &7form, and keeps", "&7packing up the chain when it can.", "&8Works even before you unlock the recipe."]),
    "DWARVEN_COMPACTOR": ("Deepdelver Compactor", ["&7An &aAuto Smelter &7and an &aUltra", "&aCompactor 3000 &7in a single slot."]),
    "DIAMOND_SPREADING": ("Diamond Spreading", ["&7Every so often the minion finds a", "&bDiamond &7among its work.", "&8About one per 10 items produced."]),
    "POTATO_SPREADING": ("Potato Spreading", ["&7Every so often the minion turns up", "&7a &aPotato&7.", "&cOnly one per island."]),
    "SLEEPY_HOLLOW": ("Drowsy Hollow", ["&7Every so often the minion turns up", "&7a &5Purple Candy&7.", "&cOnly one per island."]),
    "MINION_EXPANDER": ("Minion Expander", ["&7Widens the minion's work area by", "&a1 &7block in every direction and", "&7makes it work &a5% &7faster."]),
    "FLYCATCHER_UPGRADE": ("Flycatcher", ["&7Keeps the flies away so the minion", "&7can concentrate: &a+20% &7speed."]),
    "BERBERIS_FUEL_INJECTOR": ("Berberis Fuel Injector", ["&7Farming minions only: &a+15% &7speed,", "&7and now and then a &aLush Berberis&7."]),
    "FLINT_SHOVEL": ("Flint Shovel", ["&7In a &aGravel Minion&7, every harvest", "&7comes out as &aFlint&7."]),
    "ENCHANTED_SHEARS": ("Enchanted Shears", ["&7In a &aSheep Minion&7, the sheep are", "&7shorn before they are collected:", "&7one extra &aWool &7per harvest."]),
    "DAYSWITCH": ("Dayswitch", ["&7In a &aSunflower Minion&7: only", "&eSunflowers&7, and &a+20% &7speed."]),
    "NIGHTSWITCH": ("Nightswitch", ["&7In a &aSunflower Minion&7: only", "&9Moonflowers&7, and &a+20% &7speed."]),
    "THORNY_VINES": ("Thorny Vines", ["&7In a &aFlower Minion&7: only", "&cWild Roses&7, and &a+20% &7speed."]),
    "LESSER_SOULFLOW_ENGINE": ("Lesser Soulstream Engine", ["&7Halves the minion's output, but it", "&7distils &bRaw Soulstream &7as it works."]),
    "SOULFLOW_ENGINE": ("Soulstream Engine", ["&7Halves the minion's output, but it", "&7distils &bRaw Soulstream &7twice as fast.", "&7In a &5Void Wraith Minion&7: &a+3%", "&7output per minion tier."]),
    "CORRUPT_SOIL": ("Corrupt Soil", ["&7Mob minions only: the mobs rise", "&7corrupted and leave &eSulphur &7and", "&5Corrupted Fragments &7behind."]),
    "BUDGET_HOPPER": ("Budget Hopper", ["&7When the minion runs out of room,", "&7it sells what it makes to the NPCs", "&7for &650% &7of the usual price."]),
    "ENCHANTED_HOPPER": ("Enchanted Hopper", ["&7When the minion runs out of room,", "&7it sells what it makes to the NPCs", "&7for &670% &7of the usual price."]),
    "SMALL_ENCHANTED_CHEST": ("Small Storage", ["&7Attach to a minion for &a3 &7extra", "&7storage slots."]),
    "MEDIUM_ENCHANTED_CHEST": ("Medium Storage", ["&7Attach to a minion for &a9 &7extra", "&7storage slots."]),
    "LARGE_ENCHANTED_CHEST": ("Large Storage", ["&7Attach to a minion for &a15 &7extra", "&7storage slots."]),
    "XLARGE_ENCHANTED_CHEST": ("X-Large Storage", ["&7Attach to a minion for &a21 &7extra", "&7storage slots."]),
    "XXLARGE_ENCHANTED_CHEST": ("XX-Large Storage", ["&7Attach to a minion for &a27 &7extra", "&7storage slots."]),
    "MINION_STORAGE_EXPANDER": ("Minion Storage X-pender", ["&7Craft it with a &aLarge Storage &7to", "&7make an &aX-Large &7one, and again", "&7for &aXX-Large&7."]),
    "MITHRIL_INFUSION": ("Mithril Infusion", ["&7Apply to a minion from its menu to", "&7make it &a10% &7faster for good.", "&8Stays on the minion through tiers."]),
    "SOLAR_PANEL": ("Solar Panel", ["&7Minion fuel: &a+25% &7speed while the", "&7sun is up. Never runs out."]),
    "ENCHANTED_LAVA_BUCKET": ("Enchanted Lava Bucket", ["&7Minion fuel: &a+25% &7speed.", "&7Never runs out."]),
    "MAGMA_BUCKET": ("Magma Bucket", ["&7Minion fuel: &a+30% &7speed.", "&7Never runs out."]),
    "PLASMA_BUCKET": ("Plasma Bucket", ["&7Minion fuel: &a+35% &7speed.", "&7Never runs out."]),
    "EVERBURNING_FLAME": ("Everburning Flame", ["&7Minion fuel: &a+35% &7speed, and &a+5%", "&7more on combat minions.", "&7Never runs out."]),
    "CHEESE_FUEL": ("Tasty Cheese", ["&7Minion fuel: &a2x &7output", "&7for &a1 hour&7."]),
    "CATALYST": ("Catalyst", ["&7Minion fuel: &a3x &7output", "&7for &a3 hours&7."]),
    "HYPER_CATALYST": ("Hyper Catalyst", ["&7Minion fuel: &a4x &7output", "&7for &a6 hours&7."]),
    "HYPER_CATALYST_UPGRADE": ("Hyper Catalyst Upgrade", ["&7Craft with &a8 Catalysts &7to make", "&a8 Hyper Catalysts&7."]),
    "FARM_CRYSTAL": ("Farm Crystal", ["&7Place it on your island: every", "&7farming minion there works &a10%", "&7faster."]),
    "WOODCUTTING_CRYSTAL": ("Woodcutting Crystal", ["&7Place it on your island: every", "&7foraging minion there works &a10%", "&7faster."]),
    "MITHRIL_CRYSTAL": ("Mithril Crystal", ["&7Place it on your island: every", "&7mining minion there works &a10%", "&7faster."]),
    "WINTER_ISLAND_CRYSTAL": ("Winter Crystal", ["&7Place it on your island: every", "&7snow minion there works &a5% &7faster.",
                                                "", "&7Once every SkyBlock year a &cpresent", "&7turns up somewhere near it."]),
    "CORNUCOPIA_CRYSTAL": ("Cornucopia Crystal", ["&7Place it on your island: farming", "&7minions work &a10% &7faster, plus &a1%", "&7for every different farming minion", "&7you have placed."]),
    "POSTCARD": ("Postcard", ["&7Pin it up on your island and every", "&7minion there works &a5% &7faster,", "&7dreaming of distant places."]),
    "SUPER_JACOB_SYSTEM": ("Super Fairground Entertainment System", ["&7Install it in &aONE Farming Minion&7:",
                           "&7every &a3 hours &7of work it prints a", "&aHarvest Fair Ticket&7.", "",
                           "&8The console rewards whoever clears", "&8the last level of a very dull game.", "&cOnly one per island."]),
    "JACOBS_PARTICIPATION_MEDAL": ("Harvest Fair Participation Medal", ["&7Minion fuel for &aONE Farming Minion&7:",
                                   "&7for &a3 days &7it now and then turns", "&7up a &aHarvest Fair Ticket&7.", "",
                                   "&8A keepsake from the early fairs.", "&cOnly one per island."]),
    "FREE_WILL": ("Free Will", ["&7Offer it to a minion from its menu.", "&7The minion gets to make the biggest",
                  "&7choice of its little life:", "", "&7It &astays&7, loyal for good, and works",
                  "&a10% &7faster, or it &cleaves you&7 and", "&7mails you a &dPostcard &7instead.", "",
                  "&7Loyalty chance: &a50% &7at tier I,", "&a+4% &7for every tier above."]),
    **{"BEACON_%d" % n: ("Beacon " + ROMAN[n], ["&7Tier: &b" + ROMAN[n], "", "&7Place it on your private island.",
                                               "&7While a &dPower Crystal &7runs it, every", "&7minion there works &a+%d%% &7faster." % (2 * n),
                                               "", "&8Power Crystals come from the Forge."])
       for n in range(1, 6)},
}
INFERNO_SPEC_NAME = {"CRUDE_GABAGOOL": "Gourmet Meat", "GLOWSTONE_DUST": "Glowstone", "MAGMA_CREAM": "Magma Cream",
                     "NETHER_STALK": "Nether Wart", "BLAZE_ROD": "Blaze Rod"}
for grade, (mult, rar) in INFERNO_GRADES.items():
    for spec in INFERNO_SPECIALTY:
        fid = "INFERNO_%s_%s" % (grade, spec)
        gname = {"FUEL": "", "HEAVY": "Heavy ", "HYPERGOLIC": "Hypergolic "}[grade]
        lore = ["&7Fuel for an &cInferno Minion &7only.", "&7Lasts &a24 hours&7.", "",
                "&7Output: &a%dx&7, but &a4/5 &7of it becomes" % mult,
                "&7%s &7instead." % ("&e" + INFERNO_SPEC_NAME[spec])]
        if grade == "HYPERGOLIC":
            lore += ["", "&7Now and then it creates something", "&dvery rare&7."]
        OWN_NAMES[fid] = ("%sInferno Fuel (%s)" % (gname, INFERNO_SPEC_NAME[spec]), lore)
for tid in T:
    sid = "GENERATOR_UPGRADE_STONE_%s_12" % tid
    if sid in ITEMS:
        OWN_NAMES[sid] = ("%s Minion XII Upgrade Stone" % T[tid]["name"],
                          ["&7Use it on a &a%s Minion XI &7(from its" % T[tid]["name"],
                           "&7menu, or craft them together) to", "&7raise it to &6tier XII&7."])

SKIN_RENAME = {"GRINCH_PERSONALITY": "Grump Minion Skin", "FESTIVE_JERRY_PERSONALITY": "Festive Gerald Minion Skin",
               "SANTA_PERSONALITY": "Winter Elf Minion Skin", "CHOCO_RABBIT_PERSONALITY": "Cocoa Bunny Minion Skin",
               "APOTHEJERRY_PERSONALITY": "Apothecary Minion Skin"}
SKINS = []
for iid, rec in ITEMS.items():
    if iid.endswith("_PERSONALITY") and (rec.get("skin") or {}).get("value"):
        nm = SKIN_RENAME.get(iid, strip(rec["name"]))
        nm = nm.replace("Jerry", "Gerald")
        OWN_NAMES[iid] = (nm, ["&7A new look for one of your minions.", "&7Apply it from the minion's menu.", "&8Purely cosmetic."])
        SKINS.append(iid)
        UPGRADES.append((iid, "skin", {"texture": rec["skin"]["value"]}))

CATEGORY_OF_SLOT = {"upgrade": "MINION_UPGRADE", "shipping": "MINION_UPGRADE", "chest": "MINION_UPGRADE",
                    "infusion": "MINION_UPGRADE", "crystal": "DEPLOYABLE", "stone": "MINION_UPGRADE",
                    "skin": "COSMETIC", "beacon": "DEPLOYABLE", "freewill": "MINION_UPGRADE"}

MODEL = re.compile(r'ItemModel:\\"minecraft:([a-z_0-9]+)\\"')


def material_of(iid):
    d = neu(iid)
    if d:
        m = MODEL.search(d.get("nbttag", ""))
        if m:
            return {"COMMAND": "COMMAND_BLOCK", "WEB": "COBWEB"}.get(m.group(1).upper(), m.group(1).upper())
    legacy = (ITEMS.get(iid) or {}).get("material", "PAPER")
    return {"SKULL_ITEM": "PLAYER_HEAD", "WOOD_SPADE": "WOODEN_SHOVEL", "IRON_SPADE": "IRON_SHOVEL",
            "TRAP_DOOR": "OAK_TRAPDOOR", "WATCH": "CLOCK", "INK_SACK": "INK_SAC", "COMMAND": "COMMAND_BLOCK",
            "WEB": "COBWEB"}.get(legacy, legacy)


RARITY_WORDS = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "SPECIAL"]
SKIN_VALUE = re.compile(r'Value:\\"([A-Za-z0-9+/=]+)\\"')


def neu_as_item(iid):
    """items.json misses a few newer items; NEU has them (rarity = last lore word, skin = nbt texture)."""
    d = neu(iid)
    if d is None:
        return None
    last = strip((d.get("lore") or [""])[-1]).split(" ")
    rar = next((w for w in last if w in RARITY_WORDS), "COMMON")
    rec = {"id": iid, "name": strip(d.get("displayname")), "tier": rar}
    m = SKIN_VALUE.search(d.get("nbttag", ""))
    if m and "skull" in (d.get("itemid") or ""):
        rec["skin"] = {"value": m.group(1)}
    return rec


OTHER_ITEMS = []
OWNED = set()
upgrade_slot = {u[0]: u[1] for u in UPGRADES}
fuel_ids = {f["id"] for f in FUELS_OUT}
MATERIALS_OWNED = {"COAL", "CHARCOAL", "COAL_BLOCK", "ENCHANTED_BREAD", "ENCHANTED_COAL", "ENCHANTED_CHARCOAL",
                   "ENCHANTED_EGG", "HAMSTER_WHEEL", "FOUL_FLESH", "KRAMPUS_HELMET"}   # other modules register these
for iid, (nm, lore) in OWN_NAMES.items():
    if iid in MATERIALS_OWNED:
        continue
    rec = ITEMS.get(iid)
    if rec is None:
        rec = neu_as_item(iid)
        if rec is None:
            note("owned item missing from items.json AND neu: " + iid)
            continue
        note("owned item taken from NEU (not in items.json): " + iid)
    slot = upgrade_slot.get(iid)
    cat = CATEGORY_OF_SLOT.get(slot, "FUEL" if iid in fuel_ids else "MINION_UPGRADE")
    item = {"id": iid, "name": nm, "material": material_of(iid), "rarity": rec.get("tier", "COMMON"),
            "category": cat, "lore": lore, "tags": ["minion_item"]}
    if (rec.get("skin") or {}).get("value"):
        item["skull"] = rec["skin"]["value"]
        item["material"] = "PLAYER_HEAD"
    if rec.get("npc_sell_price"):
        item["npcSell"] = rec["npc_sell_price"]
    if iid in ("ENCHANTED_SHEARS",):
        item["category"] = "SHEARS"
        item["material"] = "SHEARS"
        item["glow"] = True
    if iid == "FLINT_SHOVEL":
        item["category"] = "SHOVEL"
        item["material"] = "IRON_SHOVEL"
    if cat == "COSMETIC":
        item["maxStack"] = 1
    if slot == "beacon":
        item["material"] = "BEACON"
        item["requirements"] = [dict((u[0], u[2]) for u in UPGRADES)[iid]["requirement"]]
    if iid.startswith("INFERNO_") and item["material"] == "PAPER":
        item["material"] = "FIRE_CHARGE"
    if cat in ("MINION_UPGRADE", "FUEL") and iid not in ("ENCHANTED_SHEARS", "FLINT_SHOVEL"):
        item["maxStack"] = 64
    if iid == "FREE_WILL":
        item["maxStack"] = 1          # items.json: "unstackable"
        item["glow"] = True
    if iid.startswith("ENCHANTED_") or iid in ("SUPER_COMPACTOR_3000", "DWARVEN_COMPACTOR", "PLASMA_BUCKET",
                                               "MAGMA_BUCKET", "EVERBURNING_FLAME", "HYPER_CATALYST"):
        item["glow"] = True
    OTHER_ITEMS.append(item)
    OWNED.add(iid)
    # recipe
    d = neu(iid)
    r = neu_recipe(d)
    g = grid_of(r)
    if g and any(g):
        amount = int((r or {}).get("count", 1) or 1)
        RECIPES.append({"id": "minions:" + iid, "type": "CRAFTING", "result": iid, "amount": amount, "grid": g,
                        "unlock": crafttext_key(d.get("crafttext")), "station": "Minion"})
# The X-pender upgrades a Large chest to X-Large, then X-Large to XX-Large (NEU only lists the first).
RECIPES.append({"id": "minions:XXLARGE_ENCHANTED_CHEST", "type": "CRAFTING", "result": "XXLARGE_ENCHANTED_CHEST",
                "amount": 1, "grid": ["", "MINION_STORAGE_EXPANDER", "", "", "XLARGE_ENCHANTED_CHEST", "", "", "", ""],
                "unlock": None, "station": "Minion"})
# Upgrade stones: stone + tier XI = tier XII, the way the reference crafts them (NEU lists most of them).
for tid in T:
    sid = "GENERATOR_UPGRADE_STONE_%s_12" % tid
    res = "%s_GENERATOR_12" % tid
    if sid in OWNED and not any(r["result"] == res for r in RECIPES):
        RECIPES.append({"id": "minions:" + res, "type": "CRAFTING", "result": res, "amount": 1,
                        "grid": ["", "", "", "", "%s_GENERATOR_11" % tid, "", "", sid, ""],
                        "unlock": next(t["unlock"] for t in TYPES_OUT if t["id"] == tid), "station": "Minion"})

# ----------------------------------------------------------------------------------------------
# 6. Compaction tables from the NEU recipes (single-ingredient recipes only).
# ----------------------------------------------------------------------------------------------
SINGLE = {}   # result -> (input, amount, count)
for path in glob.glob(os.path.join(NEU, "*.json")):
    rid = os.path.basename(path)[:-5].replace("-", ":")
    if rid not in ITEMS:
        continue
    try:
        d = load(path)
    except Exception:
        continue
    r = neu_recipe(d)
    g = grid_of(r)
    if not g:
        continue
    cells = [c for c in g if c]
    ids = {cell_id(c) for c in cells}
    if len(ids) != 1:
        continue
    total = 0
    for c in cells:
        total += cell_amount(c)
    SINGLE[rid] = (ids.pop(), total, int((r or {}).get("count", 1) or 1))

produced = set()
for t in TYPES_OUT:
    for dd in t["drops"]:
        produced.add(dd["item"])
for f in FUELS_OUT:
    if "specialty" in f:
        produced.add(f["specialty"]["item"])
for u in UPGRADES:
    for k in ("addDrops", "setDrops"):
        for dd in u[2].get(k, []):
            produced.add(dd["item"])
    if "spread" in u[2]:
        produced.add(u[2]["spread"]["item"])

SMELT = {"COBBLESTONE": "STONE", "IRON_ORE": "IRON_INGOT", "GOLD_ORE": "GOLD_INGOT", "SAND": "GLASS",
         "SAND:1": "GLASS", "CACTUS": "INK_SACK:2", "LOG": "CHARCOAL", "LOG:1": "CHARCOAL", "LOG:2": "CHARCOAL",
         "LOG:3": "CHARCOAL", "LOG_2": "CHARCOAL", "LOG_2:1": "CHARCOAL", "RAW_CHICKEN": "COOKED_CHICKEN",
         "PORK": "GRILLED_PORK", "RAW_BEEF": "COOKED_BEEF", "MUTTON": "COOKED_MUTTON", "RABBIT": "COOKED_RABBIT",
         "POTATO_ITEM": "BAKED_POTATO", "RAW_FISH": "COOKED_FISH", "RAW_FISH:1": "COOKED_FISH:1",
         "CLAY_BALL": "CLAY_BRICK"}
for a, b in list(SMELT.items()):
    if a not in ITEMS or b not in ITEMS:
        note("smelt pair dropped (unknown id): %s -> %s" % (a, b))
        SMELT.pop(a)
for v in list(SMELT.values()):
    produced.add(v)

# The Compactor makes BLOCK forms only (research 02 section 4.5): whitelist the vanilla storage blocks.
BLOCK_RESULTS = {"COAL_BLOCK", "IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK", "REDSTONE_BLOCK",
                 "LAPIS_BLOCK", "QUARTZ_BLOCK", "GLOWSTONE", "SNOW_BLOCK", "CLAY", "SLIME_BLOCK", "MELON_BLOCK",
                 "HAY_BLOCK", "PACKED_ICE", "HUGE_MUSHROOM_1", "HUGE_MUSHROOM_2", "BRICK", "BONE_BLOCK"}
BLOCK, ENCH = [], []
frontier = set(produced)
seen = set()
while frontier:
    nxt = set()
    for src in frontier:
        if src in seen:
            continue
        seen.add(src)
        for res, (inp, amt, cnt) in SINGLE.items():
            if inp != src:
                continue
            if res.startswith("ENCHANTED_") or res in ("CONCENTRATED_STONE", "ABSOLUTE_ENDER_PEARL", "MUTANT_NETHER_STALK",
                                                       "POLISHED_PUMPKIN", "BOX_OF_SEEDS", "HEMOGLASS", "HEMOBOMB",
                                                       "ENCHANTED_HAY_BLOCK", "WHIPPED_MAGMA_CREAM", "SILVER_FANG",
                                                       "CONDENSED_WATER_LILY", "OMEGA_ENCHANTED_EGG", "REFINED_MITHRIL"):
                if amt >= 32:
                    ENCH.append({"from": inp, "amount": amt, "to": res, "count": cnt})
                    nxt.add(res)
            elif amt in (4, 9) and cnt == 1 and src in produced and res in BLOCK_RESULTS:
                BLOCK.append({"from": inp, "amount": amt, "to": res, "count": cnt})
                nxt.add(res)   # a block form can compress further (Snow Block -> Enchanted Snow Block)
    frontier = nxt


def rule_rank(r):
    """One rule per input. The reference chain step is the 160-item compression (Enchanted Sugar, not
    Enchanted Paper); prefer it, then a result named after its input, then the bigger compression."""
    token = r["from"].split(":")[0].replace("_ITEM", "")
    return (r["amount"] == 160, token in r["to"], r["amount"])


def dedupe(rules):
    best = {}
    for r in rules:
        k = r["from"]
        if k not in best or rule_rank(r) > rule_rank(best[k]):
            best[k] = r
    return sorted(best.values(), key=lambda r: r["from"])


BLOCK = dedupe(BLOCK)
ENCH = dedupe(ENCH)
note("compaction: %d block rules, %d enchanted rules, %d smelt rules" % (len(BLOCK), len(ENCH), len(SMELT)))

# ----------------------------------------------------------------------------------------------
# 7. XP per collected item: research per-drop xp first, then SkyHanni's MinionXP for compacted forms.
# ----------------------------------------------------------------------------------------------
MXP = load(os.path.join(DATA, "skyhanni-repo", "constants", "MinionXP.json"))["minion_xp"]
MODERN = {"CACTUS_GREEN": "INK_SACK:2", "COCOA_BEANS": "INK_SACK:3", "CARROT": "CARROT_ITEM",
          "POTATO": "POTATO_ITEM", "RAW_PORKCHOP": "PORK", "RAW_RABBIT": "RABBIT", "NETHER_WART": "NETHER_STALK",
          "END_STONE": "ENDER_STONE", "MITHRIL": "MITHRIL_ORE", "MYCELIUM": "MYCEL", "CLAY": "CLAY_BALL",
          "CLAY_BLOCK": "CLAY", "BLOCK_OF_REDSTONE": "REDSTONE_BLOCK", "LAPIS_LAZULI_BLOCK": "LAPIS_BLOCK",
          "ENCHANTED_NETHER_WART": "ENCHANTED_NETHER_STALK", "ENCHANTED_BLOCK_OF_COAL": "ENCHANTED_COAL_BLOCK",
          "ENCHANTED_GUNPOWDER": "ENCHANTED_GUNPOWDER", "RED_MUSHROOM_BLOCK": "HUGE_MUSHROOM_2",
          "BROWN_MUSHROOM_BLOCK": "HUGE_MUSHROOM_1", "ENCHANTED_CLAY": "ENCHANTED_CLAY_BALL",
          "ENCHANTED_COCOA_BEAN": "ENCHANTED_COCOA", "ENCHANTED_PORK": "ENCHANTED_PORK",
          "ENCHANTED_CACTUS_GREEN": "ENCHANTED_CACTUS_GREEN", "ENCHANTED_POTATO": "ENCHANTED_POTATO",
          "ENCHANTED_CARROT": "ENCHANTED_CARROT", "ENCHANTED_RAW_RABBIT": "ENCHANTED_RABBIT",
          "MUTANT_NETHER_WART": "MUTANT_NETHER_STALK", "ENCHANTED_END_STONE": "ENCHANTED_ENDSTONE",
          "SNOWBALL": "SNOW_BALL", "OMEGA_ENCHANTED_EGG": "OMEGA_EGG"}
XP = {}
for iid, (skill, xp) in XP_RESEARCH.items():
    XP[iid] = {"skill": skill, "xp": xp}
for skill, tbl in MXP.items():
    for k, v in tbl.items():
        iid = k.replace("-", ":")
        iid = MODERN.get(iid, iid)
        if iid not in ITEMS:
            note("xp id unresolved: %s (%s)" % (k, skill))
            continue
        if iid not in XP and v > 0:
            XP[iid] = {"skill": skill.upper(), "xp": v}

# ----------------------------------------------------------------------------------------------
# 8. Slots (research 02 section 4.3)
# ----------------------------------------------------------------------------------------------
slot_sec = MD[MD.index("### 4.3"):MD.index("### 4.4")]
SLOTS = []
for line in slot_sec.splitlines():
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) == 3 and cells[0] in ROMAN_TO_INT or (len(cells) == 3 and re.match(r"^[IVXL]+$", cells[0] or "")):
        try:
            SLOTS.append({"tier": roman_int(cells[0]), "minions": int(cells[1]), "uniques": int(cells[2].replace(",", ""))})
        except ValueError:
            pass
assert len(SLOTS) == 22, SLOTS

# ----------------------------------------------------------------------------------------------
# 9. Write
# ----------------------------------------------------------------------------------------------
os.makedirs(OUT, exist_ok=True)


def write(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write("\n")


ALL_ITEMS = MINION_ITEMS + OTHER_ITEMS
write("items.json", ALL_ITEMS)
write("recipes.json", RECIPES)
write("types.json", TYPES_OUT)
write("fuels.json", FUELS_OUT)
write("upgrades.json", [dict({"id": i, "slot": s}, **e) for i, s, e in UPGRADES])
write("compaction.json", {"block": BLOCK, "enchanted": ENCH, "smelt": [{"from": a, "to": b} for a, b in sorted(SMELT.items())]})
write("xp.json", XP)
write("slots.json", {"table": SLOTS, "sbxpPerTier": SBXP, "communityShopMax": 5})

# sanity
ids = {i["id"] for i in ALL_ITEMS}
for r in RECIPES:
    for c in r["grid"]:
        if not c:
            continue
        cid = cell_id(c)
        if cid not in ITEMS and cid not in ids:
            note("recipe %s ingredient unknown in reference data: %s" % (r["id"], c))
    if r["unlock"] is None and "GENERATOR" in r["result"]:
        note("minion recipe without unlock: " + r["id"])

with open(os.path.join(HERE, "report.txt"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(REPORT) + "\n")
print("types %d  minion items %d  other items %d  recipes %d  fuels %d  upgrades %d  xp %d" % (
    len(TYPES_OUT), len(MINION_ITEMS), len(OTHER_ITEMS), len(RECIPES), len(FUELS_OUT), len(UPGRADES), len(XP)))
print("report: %d lines -> tools/gen/minions/report.txt" % len(REPORT))
