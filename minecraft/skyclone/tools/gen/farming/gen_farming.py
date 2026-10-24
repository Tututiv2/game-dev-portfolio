#!/usr/bin/env python3
"""Generate the farming module's data files from the research data.

    python tools/gen/farming/gen_farming.py

Writes (plugin/src/main/resources/data/farming/):
  items.json    every hoe / farming tool / contest item this module OWNS (CONTRACT item ownership table)
  recipes.json  Mk II / Mk III upgrade recipes (copied from NEU) + our Mk I recipes (TUNE: the reference sells
                Mk I tools for Copper in the Garden shop; we also let Barn farmers craft them)
  mobs.json     Barn + Mushroom Desert animals and the trapper's 30 quarry mobs (6 animals x 5 rarities)
  spawns.json   spawn rules for the pens / desert areas the two generators lay out
  shops.json    Harvesta's medal exchange (ANITA_NPC), the Surveyor (TALBOT_NPC), the Fur-trader (TONY_SHOP_NPC)
                and our own Farmhand counter

Sources (research/data/): items.json (id/material/tier/sell/skull), neu-repo/items/*.json (recipes, NPC shops),
skyhanni-repo/constants/Garden.json (tool level XP table, personal best steps) and AnitaUpgradeCosts.json.
Numbers that are NOT in the data carry a "TUNE" note here and in the Java that reads them.

Every reference proper noun shown to players is renamed below (NAMES) and in docs/names/farming.md.
"""
import glob
import json
import os
import re
import sys

# Collection ids whose reference key differs from the crop item (integrator: RED_MUSHROOM is in the Mushroom collection).
COLL_KEY = {"RED_MUSHROOM": "MUSHROOM_COLLECTION", "BROWN_MUSHROOM": "MUSHROOM_COLLECTION"}

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "farming")


def L(p):
    with open(os.path.join(DATA, p), encoding="utf-8") as f:
        return json.load(f)


ITEMS = {i["id"]: i for i in L("items.json")["items"]}


def neu(name):
    p = os.path.join(DATA, "neu-repo", "items", name + ".json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def skin_of(iid):
    s = ITEMS.get(iid, {}).get("skin")
    if isinstance(s, dict):
        return s.get("value")
    if isinstance(s, str):
        m = re.search(r"'value':\s*'([^']+)'", s)
        return m.group(1) if m else s
    return None


def sell_of(iid):
    v = ITEMS.get(iid, {}).get("npc_sell_price")
    return float(v) if v else 0.0


# Legacy material names in the reference data -> 26.1 Material names.
MATERIAL = {"WOOD_HOE": "WOODEN_HOE", "GOLD_HOE": "GOLDEN_HOE", "GOLD_AXE": "GOLDEN_AXE", "WOOD_AXE": "WOODEN_AXE",
            "DIODE": "REPEATER", "SKULL_ITEM": "PLAYER_HEAD"}


def mat(iid, default):
    m = ITEMS.get(iid, {}).get("material", default)
    return MATERIAL.get(m, m)


# =============================================================================================
# 1. Crop tools. Each line = (id prefix, our name, crops it boosts, Mk I material, Mk II/III material,
#    collection item for the Mk I recipe, "hoe" lines get the counter + collection analysis).
#    Fortune per Mk: research 08 section 1.4 "Tier I +10, II +25, III +50 Crop Fortune" (marked (?) there,
#    so TUNE) - applied to every line so a player never has to ask which tool is "better" per tier.
# =============================================================================================
MK_FORTUNE = {1: 10, 2: 25, 3: 50}          # research 08 1.4 (?) -> TUNE
MK_WISDOM = {1: 1, 2: 2, 3: 3}              # NEU lore shows "Farming Wisdom: +1" on Mk I; II/III TUNE
MK_RARITY = {1: "UNCOMMON", 2: "RARE", 3: "EPIC"}
MK_SKILL = {1: 10, 2: 15, 3: 20}            # items.json requirements (SKILL FARMING 10/15/20)
DICER_CHANCE = {1: 0.01, 2: 0.015, 3: 0.02}  # TUNE: research names the Dicer ability without a number

LINES = [
    # prefix, our base name, crops, mk1 id style, mat1, mat23, collection item for the Mk I recipe, kind
    ("THEORETICAL_HOE_WHEAT", "Fibonacci's Wheat Sickle", ["WHEAT"], "num", "IRON_HOE", "IRON_HOE", "WHEAT", "hoe"),
    ("THEORETICAL_HOE_CARROT", "Kepler's Carrot Shovel", ["CARROT"], "num", "IRON_HOE", "IRON_HOE", "CARROT_ITEM", "hoe"),
    ("THEORETICAL_HOE_POTATO", "Archimedes' Potato Shovel", ["POTATO"], "num", "IRON_HOE", "IRON_HOE", "POTATO_ITEM", "hoe"),
    ("THEORETICAL_HOE_CANE", "Lovelace's Sugar Cane Cutter", ["SUGAR_CANE"], "num", "IRON_HOE", "IRON_HOE", "SUGAR_CANE", "hoe"),
    ("THEORETICAL_HOE_WARTS", "Leibniz's Nether Wart Cutter", ["NETHER_WART"], "num", "IRON_HOE", "IRON_HOE", "NETHER_STALK", "hoe"),
    ("THEORETICAL_HOE_SUNFLOWER", "Solstice Sickle", ["SUNFLOWER", "MOONFLOWER"], "num", "IRON_HOE", "IRON_HOE", "DOUBLE_PLANT", "hoe"),
    ("THEORETICAL_HOE_WILD_ROSE", "Briarwood Rose Cutter", ["WILD_ROSE"], "num", "IRON_HOE", "IRON_HOE", "WILD_ROSE", "hoe"),
    ("MELON_DICER", "Melon Dicer", ["MELON"], "bare", "IRON_AXE", "GOLDEN_AXE", "MELON", "dicer"),
    ("PUMPKIN_DICER", "Pumpkin Dicer", ["PUMPKIN"], "bare", "IRON_AXE", "GOLDEN_AXE", "PUMPKIN", "dicer"),
    ("CACTUS_KNIFE", "Cactus Knife", ["CACTUS"], "bare", "IRON_SWORD", "IRON_SWORD", "CACTUS", "knife"),
    ("COCO_CHOPPER", "Cocoa Chopper", ["COCOA"], "bare", "GOLDEN_AXE", "GOLDEN_AXE", "INK_SACK:3", "chopper"),
    ("FUNGI_CUTTER", "Fungi Cutter", ["MUSHROOM"], "bare", "IRON_HOE", "IRON_HOE", "RED_MUSHROOM", "fungi"),
]

CROP_NAMES = {"WHEAT": "Wheat", "CARROT": "Carrot", "POTATO": "Potato", "SUGAR_CANE": "Sugar Cane",
              "NETHER_WART": "Nether Wart", "SUNFLOWER": "Sunflower", "MOONFLOWER": "Moonflower", "WILD_ROSE": "Wild Rose",
              "MELON": "Melon", "PUMPKIN": "Pumpkin", "CACTUS": "Cactus", "COCOA": "Cocoa Beans", "MUSHROOM": "Mushroom"}

FLAVOUR = {
    "hoe": "&7A precise farming tool. It keeps count of every crop it reaps and grows sharper with each digit.",
    "dicer": "&7Slices so cleanly that a fruit sometimes falls apart into a whole pile at once.",
    "knife": "&7Glides through cactus without a single prick.",
    "chopper": "&7Splits cocoa pods off the vine with one tap.",
    "fungi": "&7Tuned to one colour of mushroom at a time. &eRight-click &7to switch between red and brown.",
}


def wrap(text, width=38):
    out, line, vis = [], "", 0
    carry = "&7"
    for word in text.split(" "):
        wv = len(re.sub(r"&[0-9a-fk-or]", "", word))
        if vis and vis + 1 + wv > width:
            out.append(line)
            line, vis = carry, 0
        if vis:
            line += " "
            vis += 1
        line += word
        vis += wv
        for m in re.findall(r"&[0-9a-fk-or]", word):
            carry = m
    if line:
        out.append(line)
    return out


GARDEN_KEY = {"COCOA": "COCOA_BEANS"}


def tool_id(prefix, style, mk):
    if style == "num":
        return f"{prefix}_{mk}"
    return prefix if mk == 1 else f"{prefix}_{mk}"


items = []
recipes = []
ROMAN = {1: "I", 2: "II", 3: "III"}

for prefix, base, crops, style, mat1, mat23, coll, kind in LINES:
    for mk in (1, 2, 3):
        iid = tool_id(prefix, style, mk)
        if iid not in ITEMS:
            sys.exit(f"missing reference item {iid}")
        nxt = tool_id(prefix, style, mk + 1) if mk < 3 else None
        ft = {
            "line": prefix, "kind": kind, "mk": mk, "crops": crops,
            "cropFortune": MK_FORTUNE[mk], "wisdom": MK_WISDOM[mk],
            "counter": kind == "hoe" and mk >= 2,           # research 08 1.4: Logarithmic Counter on Tier II+
            "analysis": kind == "hoe" and mk >= 3,          # research 08 1.4: Collection Analysis on Tier III
            "dicer": DICER_CHANCE[mk] if kind == "dicer" else 0.0,
            "leveling": True,                               # skyhanni Garden.json hoe_exp_levels (2025 tool levels)
        }
        if nxt:
            ft["next"] = nxt
        # The reference also gates every crop tool on a Garden level (items.json requirements GARDEN_LEVEL). It is NOT
        # written into "requirements" (an unknown prefix fails closed, so the tools would lock while the garden module is
        # absent): FarmingModule.postEnable adds "garden:N" only when the garden resolver is registered (dial).
        for req in ITEMS[iid].get("requirements") or []:
            if req.get("type") == "GARDEN_LEVEL":
                ft["gardenLevel"] = int(req["level"])
        crop_list = " and ".join(CROP_NAMES[c] for c in crops)
        lore = wrap(FLAVOUR[kind])
        if kind == "dicer":
            lore += [""] + wrap(f"&6Dicer &7Each {CROP_NAMES[crops[0]]} you break has a &a{DICER_CHANCE[mk]*100:g}% &7chance to "
                               f"burst into a heap of extra drops.")
        if kind == "knife":
            lore += [""] + wrap("&6Swift Slicer &7While held on a farming island you gain &f+50✦ Speed&7.")
        d = {
            "id": iid,
            "name": f"{base} Mk. {ROMAN[mk]}",
            "material": mat1 if mk == 1 else mat23,
            "rarity": MK_RARITY[mk],
            "category": "HOE",
            "lore": lore,
            "requirements": [f"skill:farming:{MK_SKILL[mk]}"],
            "npcSell": sell_of(iid),
            "tags": ["farming_tool", "reforgeable", "enchantable", "recombable"],
            # "crop_fortune" is the Garden's documented hand-shake (docs/handoff/garden.md request 3b): the Garden adds the
            # def value (tier fortune) plus the instance value (level/counter/analysis, written by ToolTracker) when you
            # farm there. Keys are the Garden's crop keys (COCOA_BEANS, not COCOA).
            "extra": {"farmTool": ft, "owner": "farming", "refName": ITEMS[iid]["name"],
                      "crop_fortune": {GARDEN_KEY.get(c, c): MK_FORTUNE[mk] for c in crops}},
        }
        items.append(d)

        # ---- recipes -------------------------------------------------------------------------
        if mk == 1:
            # Our Mk I recipe (TUNE): the blueprint in the middle, 8 x 64 of the crop around it.
            cell = f"{coll}:64"
            recipes.append({"id": f"farming:{iid}", "type": "CRAFTING", "result": iid, "amount": 1,
                            "grid": [cell, cell, cell, cell, "THEORETICAL_HOE:1", cell, cell, cell, cell],
                            "unlock": f"collection:{COLL_KEY.get(coll, coll)}:5", "station": "Crafting Table"})
        else:
            n = neu(iid)
            got = False
            for r in (n or {}).get("recipes", []) or []:
                if r.get("type") != "crafting":
                    continue
                grid = [r.get(k, "") or "" for k in ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")]
                grid = [g.replace("INK_SACK-3", "INK_SACK:3") for g in grid]
                recipes.append({"id": f"farming:{iid}", "type": "CRAFTING", "result": iid, "amount": 1,
                                "grid": grid, "unlock": None, "station": "Crafting Table"})
                got = True
                break
            if not got:
                sys.exit(f"no NEU recipe for {iid}")

# =============================================================================================
# 2. Generic hoes and farming axes (items.json stats; research 08 1.3/1.4 values)
# =============================================================================================
GENERIC = [
    # id, our name, material, rarity, FF, skill req, lore, extra tags
    ("WOOD_HOE", "Wooden Hoe", "WOODEN_HOE", "COMMON", 0, 0, ["&7A plain wooden hoe."], ["vanilla"]),
    ("STONE_HOE", "Stone Hoe", "STONE_HOE", "COMMON", 0, 0, ["&7A plain stone hoe."], []),
    ("IRON_HOE", "Iron Hoe", "IRON_HOE", "COMMON", 0, 0, ["&7A plain iron hoe."], []),
    ("GOLD_HOE", "Golden Hoe", "GOLDEN_HOE", "COMMON", 0, 0, ["&7A plain golden hoe."], ["vanilla"]),
    ("DIAMOND_HOE", "Diamond Hoe", "DIAMOND_HOE", "UNCOMMON", 0, 0, ["&7A plain diamond hoe."], []),
    ("ROOKIE_HOE", "Rookie Hoe", "STONE_HOE", "COMMON", 5, 1,
     ["&7Farming Fortune raises your", "&7chance of extra crops from", "&7every harvest."], []),
    ("ROOKIE_FARMING_AXE", "Rookie Farming Axe", "STONE_AXE", "COMMON", 5, 1,
     ["&7A starter axe for melons,", "&7pumpkins and cocoa."], []),
    ("PROMISING_HOE", "Promising Hoe", "IRON_HOE", "UNCOMMON", 10, 0,     # FF TUNE: items.json lists no stat
     ["&7A sturdy step up from the", "&7rookie hoe."], []),
    ("BASIC_GARDENING_HOE", "Basic Gardening Hoe", "IRON_HOE", "UNCOMMON", 10, 5,
     ["&7Works on every crop, nothing fancy."], []),
    ("ADVANCED_GARDENING_HOE", "Advanced Gardening Hoe", "IRON_HOE", "RARE", 15, 7,
     ["&7A well-balanced hoe for any crop."], []),
    ("BASIC_GARDENING_AXE", "Basic Gardening Axe", "IRON_AXE", "UNCOMMON", 10, 0,
     ["&7For melons, pumpkins and cocoa."], []),
    ("ADVANCED_GARDENING_AXE", "Advanced Gardening Axe", "GOLDEN_AXE", "RARE", 15, 0,
     ["&7A keen edge for block crops."], []),
]
for iid, name, m, rar, ff, sk, lore, tags in GENERIC:
    d = {"id": iid, "name": name, "material": m, "rarity": rar, "category": "HOE", "lore": lore,
         "npcSell": sell_of(iid), "tags": ["farming_tool", "reforgeable", "enchantable"] + tags,
         "extra": {"owner": "farming", "farmTool": {"line": "generic", "kind": "generic", "mk": 0, "crops": [],
                                                    "cropFortune": 0, "wisdom": 0, "counter": False,
                                                    "analysis": False, "dicer": 0.0, "leveling": False}}}
    if ff:
        d["stats"] = {"FARMING_FORTUNE": ff}
    if sk:
        d["requirements"] = [f"skill:farming:{sk}"]
    if "vanilla" in tags:
        d["tags"].append("vanilla")
    items.append(d)

# Tilling hoes (Anita's shop in the reference; abilities from NEU lore)
TILL = [("HOE_OF_GREAT_TILLING", "Hoe of Great Tilling", "UNCOMMON", 2, "3x3", "&7Tills a &a3x3 &7patch of soil at once."),
        ("HOE_OF_GREATER_TILLING", "Hoe of Greater Tilling", "RARE", 5, "5x5", "&7Tills a &a5x5 &7patch of soil at once."),
        ("HOE_OF_GREATEST_TILLING", "Hoe of Greatest Tilling", "EPIC", 10, "row",
         "&7Tills a whole &arow &7of soil in the direction you face.")]
for iid, name, rar, sk, mode, line in TILL:
    items.append({"id": iid, "name": name, "material": "IRON_HOE", "rarity": rar, "category": "HOE",
                  "lore": [line, "&7Only works where you are allowed to build."],
                  "requirements": [f"skill:farming:{sk}"], "glow": iid == "HOE_OF_GREATEST_TILLING",
                  "tags": ["farming_tool"], "extra": {"owner": "farming", "tilling": mode}})

# The blueprint for every Mk I crop tool
items.append({"id": "THEORETICAL_HOE", "name": "Mathematical Hoe Blueprint", "material": "PAPER", "rarity": "UNCOMMON",
              "category": "MATERIAL", "lore": ["&7Faded plans for a family of", "&7precision farming tools.", "",
                                                "&7Combine with &a512 &7of a crop at a", "&7crafting table to build its tool."],
              "npcSell": 0, "tags": [], "extra": {"owner": "farming"}})

# =============================================================================================
# 3. Contest items (CONTRACT: farming owns "contest medals/items")
# =============================================================================================
CONTEST = [
    ("JACOBS_TICKET", "Harvest Fair Ticket", "PAPER", None, ["&7Awarded at the Harvest Fair.", "&7Spend them at Harvesta's",
                                                             "&7Medal Exchange or on tool upgrades."]),
    ("CROPIE", "Cropling", "PLAYER_HEAD", "CROPIE", ["&7A lucky find from wheat, carrot", "&7and potato fields during a", "&7Harvest Fair."]),
    ("SQUASH", "Squash", "PLAYER_HEAD", "SQUASH", ["&7A lucky find from melon, pumpkin", "&7and cocoa during a Harvest Fair."]),
    ("FERMENTO", "Fermenta", "PLAYER_HEAD", "FERMENTO", ["&7A lucky find from nether wart,", "&7sugar cane, cactus and mushrooms",
                                                         "&7during a Harvest Fair."]),
    ("HELIANTHUS", "Helianthus", "PLAYER_HEAD", "HELIANTHUS", ["&7The cream of the crop: a lucky", "&7find from sunflowers, moonflowers",
                                                               "&7and wild roses during a Harvest Fair."]),
]
for iid, name, m, skull, lore in CONTEST:
    d = {"id": iid, "name": name, "material": m, "rarity": ITEMS[iid].get("tier", "COMMON"), "category": "MATERIAL",
         "lore": lore, "npcSell": sell_of(iid), "tags": ["bazaar"], "extra": {"owner": "farming", "refName": ITEMS[iid]["name"]}}
    if skull:
        d["skull"] = skin_of(skull)
    items.append(d)

items.append({"id": "FARMING_FOR_DUMMIES", "name": "Farmer's Almanac", "material": "BOOK", "rarity": "EPIC",
              "category": "MATERIAL", "maxStack": 1,
              "lore": ["&7Grants &6+1☘ Farming Fortune &7when", "&7applied to a farming tool.", "&7Up to &a5 &7per tool.", "",
                       "&7Apply it from &e/farmtool&7."],
              "npcSell": 0, "tags": ["museum"], "extra": {"owner": "farming", "refName": "Farming for Dummies"}})

# Shiny Pigs (wave 5): the orb the pig is brought back to. In the world it is a display entity; the item exists so the
# orb shows the reference head and the loot/info lines can name it. Not obtainable (no recipe, no shop).
items.append({"id": "SHINY_ORB", "name": "Shiny Orb", "material": "PLAYER_HEAD", "rarity": ITEMS.get("SHINY_ORB", {}).get("tier", "COMMON"),
              "category": "NONE", "maxStack": 1, "skull": skin_of("SHINY_ORB"),
              "lore": ["&7Right-click a &dShiny Pig &7to catch it,", "&7then bring the pig back to the orb", "&7to gain loot."],
              "npcSell": 0, "tags": [], "extra": {"owner": "farming", "refName": "Shiny Orb"}})

items.append({"id": "TALBOTS_THEODOLITE", "name": "Surveyor's Theodolite", "material": "REPEATER", "rarity": "RARE",
              "category": "NONE", "maxStack": 1,
              "lore": ["&7Points you toward your current", "&7trapper quarry."],
              "abilities": [{"id": "farming_theodolite", "name": "Track", "trigger": "RIGHT_CLICK", "mana": 100, "cooldown": 10,
                             "lore": ["&7Shows the direction, distance and", "&7height difference to the animal you", "&7are hunting."]}],
              "npcSell": 10, "tags": [], "extra": {"owner": "farming", "refName": "Talbot's Theodolite"}})

# =============================================================================================
# 4. Mobs: Barn + Desert animals (research 03 sections 5.1 / 6.2) and trapper quarry (6.3)
# =============================================================================================
# farming XP: research 02 2.4.1 "2 each (Raw Beef 1)" per animal product -> per kill = sum of expected items. TUNE.
ANIMALS = {
    "cow": ("COW", "Cow", 100, [("RAW_BEEF", 1, 2, 1.0), ("LEATHER", 1, 2, 0.66)], 3),
    "pig": ("PIG", "Pig", 100, [("PORK", 1, 3, 1.0)], 4),
    "chicken": ("CHICKEN", "Chicken", 50, [("RAW_CHICKEN", 1, 1, 1.0), ("FEATHER", 1, 2, 0.66)], 4),
    "sheep": ("SHEEP", "Sheep", 100, [("MUTTON", 1, 2, 1.0), ("WOOL", 1, 1, 1.0)], 4),
    "rabbit": ("RABBIT", "Rabbit", 50, [("RABBIT", 1, 1, 1.0), ("RABBIT_HIDE", 1, 1, 0.5), ("RABBIT_FOOT", 1, 1, 0.05)], 3),
    "mooshroom": ("MOOSHROOM", "Mushroom Cow", 100, [("RED_MUSHROOM", 1, 2, 1.0), ("RAW_BEEF", 1, 1, 1.0), ("LEATHER", 1, 1, 0.5)], 4),
    "horse": ("HORSE", "Horse", 100, [("LEATHER", 1, 3, 1.0)], 4),
}


def drops(lst, mult=1):
    return [{"item": i, "min": a * mult, "max": b * mult, "chance": c} for i, a, b, c in lst]


mobs = []
for island, kinds in (("barn", ["cow", "pig", "chicken", "sheep", "rabbit"]), ("desert", ["rabbit", "sheep", "mooshroom"])):
    for k in kinds:
        ent, nm, hp, dl, xp = ANIMALS[k]
        mobs.append({"id": f"{island}_{k}", "name": nm, "level": 1, "entity": ent, "health": hp, "damage": 0,
                     "combatXp": 0, "coins": 0, "drops": drops(dl), "family": k, "flags": ["passive"],
                     "extra": {"farmingXp": xp, "farmAnimal": True}})

# Trapper quarry: research 03 6.3 HP and pelts per rarity (both marked (?) there -> TUNE).
RARITIES = [("trackable", "Trackable", "f", 100, 5), ("untrackable", "Untrackable", "a", 500, 10),
            ("undetected", "Undetected", "9", 1000, 15), ("endangered", "Endangered", "5", 5000, 20),
            ("elusive", "Elusive", "d", 10000, 25)]
for k in ["cow", "pig", "sheep", "chicken", "rabbit", "horse"]:
    ent, nm, hp, dl, xp = ANIMALS[k]
    for ri, (rid, rname, col, rhp, pelts) in enumerate(RARITIES):
        mobs.append({"id": f"trapper_{k}_{rid}", "name": f"&{col}{rname} {nm}", "level": [1, 5, 10, 20, 30][ri],
                     "entity": ent, "health": rhp, "damage": 0, "speed": 1.25 + 0.1 * ri, "combatXp": 0, "coins": 0,
                     "drops": drops(dl, 1 + ri), "family": "trapper_" + k, "flags": ["passive", "no_knockback"],
                     "extra": {"pelts": pelts, "farmingXp": 20 * (2 ** ri), "trapper": True, "rarity": rid}})

# Shiny Pig (wave 5; Pig pet Hamfisted / Shining Stampede, Harvesting VI and Potato Spreading's source). Bestiary
# family "shiny_pig" = progression's curated family (NEU bestiary hub, bracket 5, cap 400). It is never fought: the
# farming module cancels every hit and "kills" it itself when it is delivered to its orb, which is what counts the
# bestiary. No drops/coins on the def - the orb pays (FDials farming.shiny.*).
mobs.append({"id": "shiny_pig", "name": "&dShiny Pig", "level": 1, "entity": "PIG", "health": 100, "damage": 0,
             "speed": 1.3, "combatXp": 0, "coins": 0, "drops": [], "family": "shiny_pig", "behaviours": ["farming_shiny_pig"],
             "flags": ["passive", "no_knockback", "silent"], "extra": {"shinyPig": True}})

spawns = [
    {"id": "barn_cows", "island": "barn", "area": "pen.cow", "mob": "barn_cow", "max": 6, "respawnSeconds": 15},
    {"id": "barn_pigs", "island": "barn", "area": "pen.pig", "mob": "barn_pig", "max": 6, "respawnSeconds": 15},
    {"id": "barn_chickens", "island": "barn", "area": "pen.chicken", "mob": "barn_chicken", "max": 8, "respawnSeconds": 12},
    {"id": "barn_sheep", "island": "barn", "area": "pen.sheep", "mob": "barn_sheep", "max": 6, "respawnSeconds": 15},
    {"id": "barn_rabbits", "island": "barn", "area": "pen.rabbit", "mob": "barn_rabbit", "max": 6, "respawnSeconds": 12},
    {"id": "desert_rabbits", "island": "mushroom_desert", "area": "desert.rabbits", "mob": "desert_rabbit", "max": 8, "respawnSeconds": 15},
    {"id": "desert_sheep", "island": "mushroom_desert", "area": "keep.sheep", "mob": "desert_sheep", "max": 8, "respawnSeconds": 15},
    {"id": "desert_mooshrooms", "island": "mushroom_desert", "area": "gorge.mooshrooms", "mob": "desert_mooshroom", "max": 8, "respawnSeconds": 15},
]

# =============================================================================================
# 5. Shops (NEU npc_shop lists), currencies: SKYBLOCK_COIN -> coins, *_MEDAL -> medals, SKYBLOCK_PELT -> pelts
# =============================================================================================


def parse_cost(c):
    out = {"coins": 0, "bronze": 0, "silver": 0, "gold": 0, "pelts": 0, "items": []}
    for s in c:
        if ":" in s:
            iid, amt = s.rsplit(":", 1)
            amt = int(float(amt))
        else:
            iid, amt = s, 1
        if iid == "SKYBLOCK_COIN":
            out["coins"] += amt
        elif iid == "SKYBLOCK_BRONZE_MEDAL":
            out["bronze"] += amt
        elif iid == "SKYBLOCK_SILVER_MEDAL":
            out["silver"] += amt
        elif iid == "SKYBLOCK_GOLD_MEDAL":
            out["gold"] += amt
        elif iid == "SKYBLOCK_PELT":
            out["pelts"] += amt
        else:
            out["items"].append(f"{iid.replace('INK_SACK-3', 'INK_SACK:3')}:{amt}")
    return out


def shop_from(npc):
    n = neu(npc)
    entries = []
    for r in n.get("recipes", []):
        if r.get("type") != "npc_shop":
            continue
        res = r["result"]
        rid, amt = (res.rsplit(":", 1) + ["1"])[:2] if ":" in res else (res, "1")
        if ";" in rid:                       # enchanted books (enchanting module's items) - not stockable here
            continue
        e = {"result": rid, "amount": int(float(amt)), "cost": parse_cost(r.get("cost", []))}
        # medal / ticket exchange rows produce a currency, not an item
        if rid.startswith("SKYBLOCK_") and rid.endswith("_MEDAL"):
            e["medal"] = rid.split("_")[1].lower()
        entries.append(e)
    return entries


shops = {
    "anita": shop_from("ANITA_NPC"),
    "talbot": shop_from("TALBOT_NPC"),
    "tony": shop_from("TONY_SHOP_NPC"),
    # Our own counter on the Barn (TUNE prices): the starter tools of the Farm Merchant + the Mk I blueprint.
    "farmhand": [
        {"result": "ROOKIE_HOE", "amount": 1, "cost": {"coins": 10}},
        {"result": "ROOKIE_FARMING_AXE", "amount": 1, "cost": {"coins": 10}},
        {"result": "PROMISING_HOE", "amount": 1, "cost": {"coins": 1500}},
        {"result": "THEORETICAL_HOE", "amount": 1, "cost": {"coins": 5000}},
        {"result": "BASIC_GARDENING_HOE", "amount": 1, "cost": {"coins": 2500}},
        {"result": "BASIC_GARDENING_AXE", "amount": 1, "cost": {"coins": 2500}},
    ],
}
# Harvesta also sells the almanac (TUNE: the reference's source is a Harvest Fair prize).
shops["anita"].append({"result": "FARMING_FOR_DUMMIES", "amount": 1, "cost": {"gold": 1, "items": ["JACOBS_TICKET:32"]}})

# =============================================================================================
# 6. Tables the Java reads (tool level XP, personal-best steps, Anita upgrade costs)
# =============================================================================================
G = L(os.path.join("skyhanni-repo", "constants", "Garden.json"))
A = L(os.path.join("skyhanni-repo", "constants", "AnitaUpgradeCosts.json"))
tables = {
    "_generated": "tools/gen/farming/gen_farming.py",
    "toolLevelXp": G["hoe_exp_levels"],
    "personalBestStep": G["personal_best_increment"],
    "anitaFortune": [{"tier": int(k), "gold": v["gold_medals"], "tickets": v["jacob_tickets"]}
                     for k, v in sorted(A["level_price"].items(), key=lambda kv: int(kv[0]))],
    # research 02 section 2.4.1: farming level cap 51..60
    "farmingCap": [{"cap": 51 + i, "gold": g, "tickets": t} for i, (g, t) in
                   enumerate(zip([1, 1, 1, 1, 1, 2, 2, 2, 2, 3], [0, 50, 50, 100, 100, 150, 150, 200, 250, 500]))],
    # Crop milestones (garden tables) drive the Dedication enchant here: fortune x milestone tier.
    "cropMilestones": G["crop_milestones"],
    "npcSkins": {},
}

# Shiny Pig bestiary tiers (NEU constants/bestiary.json: hub "Shiny Pig", bracket 5, cap 400). The Pig pet's Shining
# Stampede pays Potato Fortune per tier, so farming needs the same kill thresholds the bestiary uses.
_B = L(os.path.join("neu-repo", "constants", "bestiary.json"))
_shiny = next(m for m in _B["hub"]["mobs"] if "shiny_pig_1" in m.get("mobs", []))
tables["shinyPigTiers"] = [k for k in _B["brackets"][str(_shiny["bracket"])] if k <= _shiny["cap"]]


def npc_skin(npc):
    n = neu(npc)
    if not n:
        return None
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', n.get("nbttag", ""))
    return m.group(1) if m else None


for key, npc in {"contest_host": "JACOB_NPC", "anita": "ANITA_NPC", "trapper": "TREVOR_NPC", "talbot": "TALBOT_NPC",
                 "tony": "TONY_SHOP_NPC", "farmhand": "FARMHAND_NPC", "shepherd": "SHEPHERD_NPC",
                 "treasure_hunter": "TREASURE_HUNTER_NPC", "farmer": "FARMER_JON_NPC"}.items():
    s = npc_skin(npc)
    if s:
        tables["npcSkins"][key] = s

# =============================================================================================
os.makedirs(OUT, exist_ok=True)


def dump(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")


dump("items.json", items)
dump("recipes.json", recipes)
dump("mobs.json", mobs)
dump("spawns.json", spawns)
dump("shops.json", shops)
dump("tables.json", tables)
print(f"items {len(items)}, recipes {len(recipes)}, mobs {len(mobs)}, spawns {len(spawns)}, "
      f"shops {sum(len(v) for v in shops.values())} entries, tool levels {len(tables['toolLevelXp']) + 1}")
