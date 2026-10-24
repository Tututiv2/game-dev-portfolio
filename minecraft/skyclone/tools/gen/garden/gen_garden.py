#!/usr/bin/env python3
"""
Generates every data file of the garden module from the research data, plus the renames table.

    python tools/gen/garden/gen_garden.py

Reads   research/data/neu-repo/constants/{garden,skymart}.json
        research/data/skyhanni-repo/constants/Garden.json
        research/data/items.json
Writes  plugin/src/main/resources/data/garden/{garden,visitors,items,mobs,recipes,skymart,greenhouse}.json
        docs/names/garden.md

Never hand-edit the JSON: change this script and re-run it. Numbers marked TUNE in comments are
clone defaults where the research marked the value (?) - every one of them is also a dial or a
data field the integrator can change.
"""
import json
import os
import re
import hashlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "garden")
NAMES = os.path.join(ROOT, "docs", "names", "garden.md")


def load(*p):
    with open(os.path.join(RES, *p), encoding="utf-8") as f:
        return json.load(f)


def clean(s):
    return re.sub("§.", "", s).replace("Â", "").strip()


def h(s):
    return int(hashlib.md5(s.encode()).hexdigest()[:8], 16)


neu = load("neu-repo", "constants", "garden.json")
sky = load("skyhanni-repo", "constants", "Garden.json")
mart = load("neu-repo", "constants", "skymart.json")
ref_items = {it["id"]: it for it in load("items.json")["items"]}

renames = []   # (id, reference, ours, kind)


def rn(kind, rid, ref, ours):
    if ref != ours:
        renames.append((kind, rid, ref, ours))
    return ours


# =====================================================================================
# crops (research 08 section 1.2 / 1.1 / 1.11; milestones from NEU constants)
# =====================================================================================
# key, display, main item, extra item, enchanted id, garden level, base min, base max, xp, pest,
# block(s), organic matter per item, growth stage block property max (informational)
CROPS = [
    ("WHEAT", "Wheat", "WHEAT", "SEEDS", "ENCHANTED_WHEAT", 1, 1, 1, 4.0, "fly", ["wheat"], 1.0, "&e"),
    ("CARROT", "Carrot", "CARROT_ITEM", None, "ENCHANTED_CARROT", 2, 3, 3, 4.0, "cricket", ["carrots"], 0.29, "&6"),
    ("POTATO", "Potato", "POTATO_ITEM", None, "ENCHANTED_POTATO", 3, 3, 3, 4.0, "locust", ["potatoes"], 0.33, "&e"),
    ("PUMPKIN", "Pumpkin", "PUMPKIN", None, "ENCHANTED_PUMPKIN", 4, 1, 1, 4.5, "rat", ["pumpkin"], 1.0, "&6"),
    ("SUGAR_CANE", "Sugar Cane", "SUGAR_CANE", None, "ENCHANTED_SUGAR", 5, 2, 2, 4.0, "mosquito", ["sugar_cane"], 0.5, "&a"),
    ("MELON", "Melon", "MELON", None, "ENCHANTED_MELON", 6, 5, 5, 4.0, "earthworm", ["melon"], 0.2, "&a"),
    ("CACTUS", "Cactus", "CACTUS", None, "ENCHANTED_CACTUS_GREEN", 7, 2, 2, 4.0, "mite", ["cactus"], 0.5, "&2"),
    ("COCOA_BEANS", "Cocoa Beans", "INK_SACK:3", None, "ENCHANTED_COCOA", 8, 3, 3, 4.0, "moth", ["cocoa"], 0.4, "&6"),
    ("MUSHROOM", "Mushroom", "RED_MUSHROOM", "BROWN_MUSHROOM", "ENCHANTED_RED_MUSHROOM", 9, 1, 1, 6.0, "slug", ["red_mushroom", "brown_mushroom"], 1.0, "&c"),
    ("NETHER_WART", "Nether Wart", "NETHER_STALK", None, "ENCHANTED_NETHER_STALK", 10, 2, 3, 4.0, "beetle", ["nether_wart"], 0.33, "&4"),
    ("SUNFLOWER", "Sunflower", "DOUBLE_PLANT", None, "ENCHANTED_SUNFLOWER", 11, 2, 2, 4.0, "dragonfly", ["sunflower"], 0.5, "&e"),
    ("MOONFLOWER", "Moonflower", "MOONFLOWER", None, "ENCHANTED_MOONFLOWER", 11, 2, 2, 4.0, "firefly", ["blue_orchid"], 0.5, "&9"),
    ("WILD_ROSE", "Wild Rose", "WILD_ROSE", None, "ENCHANTED_WILD_ROSE", 12, 2, 2, 4.0, "praying_mantis", ["rose_bush"], 0.5, "&c"),
]
# Carrot/potato research says "1-4"; the wiki average is 3 (fortune does the rest) - TUNE.
# Melon slices research says "3-7"; 5 is the mean - TUNE.

# Milestone thresholds per crop: NEU lists "amount for this tier". 46 tiers each.
crops_out = []
for (key, disp, item, extra, ench, lvl, bmin, bmax, xp, pest, blocks, om, col) in CROPS:
    ms = neu["crop_milestones"][key]
    assert len(ms) == 46, key
    crops_out.append({
        "key": key, "name": disp, "color": col, "item": item, "extraItem": extra, "enchanted": ench,
        "level": lvl, "baseMin": bmin, "baseMax": bmax, "xp": xp, "pest": pest, "blocks": blocks,
        "organic": om, "milestones": ms,
        "personalBest": sky["personal_best_increment"].get(key, 100),
    })

# =====================================================================================
# plots: NEU groups (beginner/intermediate/advanced/expert) with their exact costs
# =====================================================================================
GROUP_LEVEL = {"beginner": 1, "intermediate": 3, "advanced": 5, "expert": 7}   # research 1.6 (A=I, B=III, C=V, D=VII)
GROUP_NAME = {"beginner": "Beginner", "intermediate": "Intermediate", "advanced": "Advanced", "expert": "Expert"}
plots = []
for pid, p in neu["plots"].items():
    num = int(re.sub("[^0-9]", "", clean(p["name"])[-3:]))
    group = pid.split("_")[0]
    plots.append({"id": pid, "number": num, "col": p["x"], "row": p["y"], "group": group})
plots.sort(key=lambda p: p["number"])
assert len(plots) == 24
groups = []
for g, costs in neu["plot_costs"].items():
    groups.append({"id": g, "name": GROUP_NAME[g], "level": GROUP_LEVEL[g],
                   "costs": [{"item": c["item"], "amount": c["amount"]} for c in costs]})

# =====================================================================================
# composter (NEU upgrades: the 'upgrade' field is the effect value AT that tier)
# =====================================================================================
COMP_LEVEL = {"speed": 2, "multi_drop": 4, "fuel_cap": 6, "organic_matter_cap": 8, "cost_reduction": 10}  # research 1.11
COMP_NAME = {"speed": "Composter Speed", "multi_drop": "Multi Drop", "fuel_cap": "Fuel Cap",
             "organic_matter_cap": "Organic Matter Cap", "cost_reduction": "Cost Reduction"}
COMP_DESC = {"speed": "&7Makes compost &a{}% &7faster.",
             "multi_drop": "&a{}% &7chance to squeeze out an extra compost.",
             "fuel_cap": "&7Holds up to &a{} &7fuel.",
             "organic_matter_cap": "&7Holds up to &a{} &7organic matter.",
             "cost_reduction": "&7Each compost uses &a{}% &7less matter and fuel."}
ITEM_FIX = {"enchanted_huge_mushroom_1": "ENCHANTED_HUGE_MUSHROOM_1"}
composter = {"intervalSeconds": 600, "organicPer": 4000, "fuelPer": 2000,
             "organicCap": 40000, "fuelCap": 100000, "upgrades": []}
for uid, tiers in neu["composter_upgrades"].items():
    tl = []
    for t in range(1, len(tiers) + 1):
        e = tiers[str(t)]
        tl.append({"value": e["upgrade"], "copper": e["copper"],
                   "items": {ITEM_FIX.get(k, k.upper()): v for k, v in e["items"].items()}})
    composter["upgrades"].append({"id": uid, "name": COMP_NAME[uid], "level": COMP_LEVEL[uid],
                                  "desc": COMP_DESC[uid], "tiers": tl})
# fuel cap tier1 = 130k and +30k each -> base 100k; OM cap tier1 = 70k -> base 40k (checked by the unit test)

organic = {}
for k, v in sky["organic_matter"].items():
    k2 = {"INK_SACK-3": "INK_SACK:3", "INK_SACK-2": "INK_SACK:2", "CACTUS_GREEN": "INK_SACK:2"}.get(k, k)
    organic[k2] = v
# enchanted crops = 160 base crops (research 1.11 "base x 160"); compacted = 160 enchanted
for (key, disp, item, extra, ench, *_rest) in CROPS:
    base = organic.get(item, 0.5)
    organic.setdefault(ench, round(base * 160, 2))
organic.setdefault("ENCHANTED_SEEDS", 160.0)
organic.setdefault("BOX_OF_SEEDS", 25600.0)
organic.setdefault("ENCHANTED_HAY_BALE", 160 * 9 * 1.0 * 9 / 9)   # 1296 wheat
organic.setdefault("HAY_BLOCK", 9.0)
organic.setdefault("ENCHANTED_BROWN_MUSHROOM", 160.0)
organic.setdefault("ENCHANTED_CACTUS", 160 * 80.0)
organic.setdefault("ENCHANTED_SUGAR_CANE", 160 * 80.0)
organic.setdefault("ENCHANTED_GOLDEN_CARROT", 128 * 0.29 * 160)
organic.setdefault("ENCHANTED_BAKED_POTATO", 160 * 0.33 * 160)
organic.setdefault("ENCHANTED_MELON_BLOCK", 160 * 0.2 * 160)
organic.setdefault("MUTANT_NETHER_STALK", 160 * 0.33 * 160)
organic.setdefault("ENCHANTED_COOKIE", 128 * 0.4 * 160)
organic.setdefault("POLISHED_PUMPKIN", 160 * 160.0)
organic.setdefault("COMPACTED_SUNFLOWER", 160 * 0.5 * 160)
organic.setdefault("COMPACTED_MOONFLOWER", 160 * 0.5 * 160)
organic.setdefault("COMPACTED_WILD_ROSE", 160 * 0.5 * 160)
organic.setdefault("CONDENSED_FERMENTO", 180000.0)     # research 1.11
organic.setdefault("CONDENSED_HELIANTHUS", 270000.0)   # research 1.11
fuel = dict(sky["fuel"])
fuel.setdefault("ENCHANTED_COAL", 1000)              # research 1.11 (?) TUNE
fuel.setdefault("ENCHANTED_COAL_BLOCK", 10000)       # research 1.11 clone default

# =====================================================================================
# garden levels and their rewards (research 1.5)
# =====================================================================================
levels = neu["garden_exp"]     # xp for this level, index 0 = level I
assert levels == [0, 70, 70, 140, 240, 600, 1500, 2000, 2500, 3000, 10000, 10000, 10000, 10000, 10000]

# crop upgrades (research 1.7)
crop_upgrades = {"copper": neu["crop_upgrades"], "levels": [2, 4, 6, 8, 10, 12, 13, 14, 15],
                 "fortunePerTier": 5}
assert sum(crop_upgrades["copper"]) == 7685

# visitor milestone tracks (research 1.10: 32 unique tiers, 11 accepted tiers) - thresholds TUNE
unique_ms = [1, 2, 3, 5, 7, 10, 13, 16, 20, 24, 28, 32, 36, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95,
             100, 105, 110, 115, 120, 125, 130]
accepted_ms = [1, 5, 10, 25, 50, 100, 200, 300, 500, 750, 1000]

# barn skins: palette ids the BarnBuilder knows. source: free | level:N | copper:N | unique:N | milestone:CROP:N
BARN_SKINS = [
    ("default", "Default", "OAK_PLANKS", "free"),
    ("medieval", "Medieval", "SPRUCE_LOG", "level:5"),
    ("red", "Red", "RED_TERRACOTTA", "level:10"),
    ("sunny", "Sunny", "RED_SANDSTONE", "level:15"),
    ("cabin", "Cabin", "STRIPPED_SPRUCE_LOG", "unique:50"),
    ("mansion_heights", "Mansion Heights", "QUARTZ_BLOCK", "unique:100"),
    ("trading_post", "Trading Post", "OAK_FENCE", "copper:1000"),
    ("autumn_hut", "Autumn Hut", "ORANGE_TERRACOTTA", "copper:2000"),
    ("cozy_cottage", "Cozy Cottage", "HAY_BLOCK", "copper:2500"),
    ("tavern", "Tavern", "DARK_OAK_LOG", "copper:2500"),
    ("bamboo", "Bamboo", "BAMBOO_BLOCK", "copper:7500"),
    ("melon", "Melon", "MELON", "copper:9500"),
    ("hive", "Hive", "HONEYCOMB_BLOCK", "copper:10000"),
    ("castle", "Castle", "STONE_BRICKS", "copper:15000"),
    ("mushroom", "Mushroom", "RED_MUSHROOM_BLOCK", "milestone:MUSHROOM:20"),
    ("winter_barn", "Winter Barn", "SNOW_BLOCK", "milestone:WHEAT:25"),
    ("sand_castle", "Sand Castle", "SANDSTONE", "milestone:CACTUS:20"),
    ("the_end", "The End", "END_STONE_BRICKS", "milestone:NETHER_WART:25"),
    ("pestwarden_lair", "Pestwarden's Lair", "MOSSY_COBBLESTONE", "pesthunter:1500"),   # wiki: Phillip, 1,500 Pests
]
rn("barn skin", "pesthunters_lair", "Pesthunter's Lair", "Pestwarden's Lair")
rn("barn skin", "jerry", "Jerry", "(not shipped)")

garden = {
    "levels": levels,
    "crops": crops_out,
    "plotSize": 96,
    "plots": plots,
    "plotGroups": groups,
    "cropUpgrades": crop_upgrades,
    "composter": composter,
    "organic": organic,
    "fuel": fuel,
    "uniqueVisitorMilestones": unique_ms,
    "acceptedMilestones": accepted_ms,
    "barnSkins": [{"id": a, "name": b, "icon": c, "source": d} for a, b, c, d in BARN_SKINS],
}

# =====================================================================================
# visitors (skyhanni roster: 140) - renamed, gated, themed bodies (no reference skins shipped)
# =====================================================================================
VNAME = {
    "Alchemist": "Alchemist Wendel", "Andrew": "Andrin", "Anita": "Harvesta", "Arthur": "Arlo",
    "Baker": "Baker Haverly", "Banker Broadjaw": "Banker Stoutjaw", "Bartender": "Bartender", "Beth": "Bettina",
    "Carrot King Avatar": "Root Monarch Avatar", "Clerk Seraphine": "Clerk Seralda", "Combat Merchant": "Combat Merchant",
    "Dalbrek": "Dolbrin", "Duke": "Duke Harrow", "Dusk": "Gloam", "Emissary Carlton": "Emissary Carver",
    "Emissary Ceanna": "Emissary Cerise", "Emissary Fraiser": "Emissary Fenwick", "Emissary Sisko": "Emissary Sorrel",
    "Emissary Wilson": "Emissary Welby", "Farmer Jon": "Farmer Joss", "Farmhand": "Farmhand",
    "Fear Mongerer": "Dread Peddler", "Felix": "Fennick", "Fisherman Gerald": "Fisherman Garrick",
    "Fragilis": "Brittlewisp", "Friendly Hiker": "Friendly Hiker", "Geonathan Greatforge": "Geodric Deepforge",
    "Gimley": "Grumley", "Gold Forger": "Gold Forger", "Grandma Wolf": "Granny Lupa", "Guy": "Gus",
    "Gwendolyn": "Gwenna", "Hornum": "Horvik", "Hungry Hiker": "Hungry Hiker", "Iron Forger": "Iron Forger",
    "Jack": "Jock", "Jacob": "Harvest Warden Ambrose", "Jamie": "Jemma", "Jerry": "Gerald",
    "Jotraeline Greatforge": "Jorelle Deepforge", "Lazy Miner": "Lazy Miner", "Leo": "Leone", "Liam": "Lorcan",
    "Librarian": "Librarian", "Lumber Jack": "Lumberjack", "Lumina": "Lamplighter Luminelle", "Lynn": "Lynette",
    "Madame Eleanor Q. Goldsworth III": "Curator Ottoline Vane", "Mason": "Mason", "Maeve": "Maelis",
    "Odawa": "Odala", "Old Man Garry": "Old Man Garrow", "Oringo": "Zebulon the Keeper",
    "Pest Wrangler": "Pest Wrangler", "Pest Wrangler?": "Pest Wrangler?", "Pete": "Perrin",
    "Plumber Joe": "Plumber Jem", "Puzzler": "Puzzler", "Queen Mismyla": "Queen Mirabel",
    "Ravenous Rhino": "Ravenous Rhino", "Rhys": "Rhodri", "Resident Snooty": "Resident Snooty",
    "Resident Neighbor": "Resident Neighbor", "Royal Resident": "Royal Resident", "Rusty": "Old Rusk",
    "Ryu": "Kaito", "Sargwyn": "Sarlwen", "Seymour": "Seward", "Shaggy": "Scruff", "Shifty": "Shady Sid",
    "Sirius": "Ashgrave", "Spaceman": "Spaceman", "Stella": "Estrel", "Tammy": "Tilly", "Tarwen": "Tarlow",
    "Tal Ker": "Tal Vorn", "Terry": "Tobin", "Tia the Fairy": "Wren the Fairy", "Tom": "Tomlin",
    "Trevor": "Trevin", "Vex": "Vesk", "Vinyl Collector": "Vinyl Collector", "Weaponsmith": "Weaponsmith",
    "Wizard": "Wizard", "Xalx": "Zalq", "Zog": "Pet Supplier Zob", "Vincent": "Vinson", "Alchemage": "Alchemage",
    "An": "Oan", "Archaeologist": "Archaeologist", "Bednom": "Bedrun", "Bruuh": "Bruhm",
    "Chantelle": "Chanterelle", "Chief Scorn": "Chief Sneer", "Chunk": "Chonk", "Cold Enjoyer": "Cold Enjoyer",
    "Dante Goon": "Tyrant's Goon", "Dulin": "Dulvar", "Duncan": "Dunstan", "Elle": "Elia", "Erihann": "Erivan",
    "Fann": "Pet Trainer", "Farm Merchant": "Farm Merchant", "Frozen Alex": "Frostbitten Aleks", "Gary": "Garvey",
    "Gemma": "Jemima", "Hendrik": "Halvard", "Hoppity": "Hopsworth", "Jacobus": "Bagwright Jory",
    "Lift Operator": "Lift Operator", "Ludleth": "Ludwick", "Marco": "Marcel", "Marigold": "Calendula",
    "Master Tactician Funk": "Master Tactician Fenk", "Mayor Aatrox": "Mayor Vexmar", "Mayor Cole": "Mayor Bram",
    "Mayor Diana": "Mayor Orla", "Mayor Diaz": "Mayor Mercer", "Mayor Finnegan": "Mayor Wystan",
    "Mayor Foxy": "Mayor Pip", "Mayor Marina": "Mayor Coralie", "Mayor Paul": "Mayor Osric", "Moby": "Mobley",
    "Old Shaman Nyko": "Old Shaman Nyro", "Ophelia": "Ottilie", "Pearl Dealer": "Pearl Dealer",
    "Queen Nyx": "Queen Nyssa", "Romero": "Romulo", "Ryan": "Rowan", "Scout Scardius": "Scout Scaldor",
    "Sherry": "Marnie", "Spider Tamer": "Spider Tamer", "St. Jerry": "Saint Gerald", "Tomioka": "Tomoya",
    "Trinity": "Trina", "Tyashoi Alchemist": "Tyrosh Alchemist", "Vargul": "Varguk", "Carpenter": "Carpenter",
    "Tyzzo": "Tizzo", "Taylor": "Tailor Clementine",
}
NEED = {
    "Wheat": "WHEAT", "Carrot": "CARROT_ITEM", "Potato": "POTATO_ITEM", "Pumpkin": "PUMPKIN",
    "Sugar Cane": "SUGAR_CANE", "Melon Slice": "MELON", "Cactus": "CACTUS", "Cocoa Beans": "INK_SACK:3",
    "Red Mushroom Block": "HUGE_MUSHROOM_2", "Nether Wart": "NETHER_STALK", "Sunflower": "DOUBLE_PLANT",
    "Moonflower": "MOONFLOWER", "Wild Rose": "WILD_ROSE", "Compost": "COMPOST",
    "Enchanted Golden Carrot": "ENCHANTED_GOLDEN_CARROT", "Golden Carrot": "GOLDEN_CARROT",
    "Raw Mutton": "MUTTON", "Raw Porkchop": "PORK", "Raw Rabbit": "RABBIT", "Fine Flour": "FINE_FLOUR",
    "Lucky Clover": "LUCKY_CLOVER", "Cropie": "CROPIE", "Squash": "SQUASH", "Fermento": "FERMENTO",
    "Dung": "DUNG", "Honey Jar": "HONEY_JAR", "Plant Matter": "PLANT_MATTER", "Tasty Cheese": "CHEESE_FUEL",
    "Jelly": "JELLY", "Bread": "BREAD", "Cake": "CAKE", "Jack o' Lantern": "JACK_O_LANTERN",
    "Music Disc - 13": "RECORD_13", "Enchanted Sugar": "ENCHANTED_SUGAR", "Deepfries": "DEEPFRIES",
    "Salted Sunflower Seeds": "SALTED_SUNFLOWER_SEEDS", "Enchanted Baked Potato": "ENCHANTED_BAKED_POTATO",
    "Floral Gelatin": "FLORAL_GELATIN", "Melon Juice": "MELON_JUICE", "Compacted Wild Rose": "COMPACTED_WILD_ROSE",
}
# home island of the NPC the visitor is (skyhanni 'mode') -> the island you must have visited
MODE_ISLAND = {"hub": None, "mining_1": "gold_mine", "mining_3": "dwarven_mines", "mines_3": "dwarven_mines",
               "crystal_hollows": "crystal_hollows", "farming_1": "barn", "farming_3": "mushroom_desert",
               "combat_1": "spiders_den", "combat_3": "the_end", "foraging_1": "park", "winter": "jerrys_workshop",
               "crimson_isle": "crimson_isle", "dungeon_hub": "dungeon_hub", None: None}
# themed bodies: no reference skins are shipped (they are the reference game's art)
BODY = {"hub": ["VILLAGER", "MANNEQUIN", "WANDERING_TRADER"], "mining_1": ["MANNEQUIN", "VILLAGER"],
        "mining_3": ["MANNEQUIN", "VILLAGER"], "mines_3": ["MANNEQUIN"], "crystal_hollows": ["MANNEQUIN", "VILLAGER"],
        "farming_1": ["VILLAGER", "WANDERING_TRADER"], "farming_3": ["VILLAGER"], "combat_1": ["WITCH", "MANNEQUIN"],
        "combat_3": ["MANNEQUIN"], "foraging_1": ["VILLAGER", "MANNEQUIN"], "winter": ["SNOW_GOLEM", "MANNEQUIN"],
        "crimson_isle": ["WITCH", "MANNEQUIN"], "dungeon_hub": ["MANNEQUIN"], None: ["MANNEQUIN", "WANDERING_TRADER"]}
RARITY = {"uncommon": "UNCOMMON", "rare": "RARE", "legendary": "LEGENDARY", "mythic": "MYTHIC", "special": "SPECIAL"}
# garden level gate by rarity (research 1.5 snapshot: +78 visitors by V, +120 by X, +137 by XV) - TUNE
LEVEL_POOL = {"UNCOMMON": [1, 1, 1, 2, 2, 3, 3, 4, 5, 6], "RARE": [2, 3, 4, 5, 5, 6, 7, 8, 9, 10],
              "LEGENDARY": [3, 5, 6, 7, 8, 9, 10, 11, 12, 13], "MYTHIC": [14, 15], "SPECIAL": [15]}
FIXED_LEVEL = {"Carpenter": 7, "Taylor": 14, "Ludleth": 15, "Spaceman": 15, "Jacob": 1, "Anita": 1,
               "Maeve": 14, "Ravenous Rhino": 13}
visitors = []
for ref_name, v in sky["visitors"].items():
    if ref_name not in VNAME:
        raise SystemExit("visitor without a rename: " + ref_name)
    ours = VNAME[ref_name]
    vid = re.sub("[^a-z0-9]+", "_", ref_name.lower()).strip("_")
    if not vid:
        vid = "unknown"
    rarity = RARITY[v["rarity"]]
    mode = v.get("mode")
    lvl = FIXED_LEVEL.get(ref_name, LEVEL_POOL[rarity][h(vid) % len(LEVEL_POOL[rarity])])
    if ref_name.startswith("Mayor "):
        lvl = max(lvl, 3)
    wants = [NEED[n] for n in v.get("need_items", []) if n in NEED]
    bodies = BODY.get(mode, BODY[None])
    body = "WITCH" if v.get("skinOrType") == "EntityWitch" else (
        "VILLAGER" if v.get("skinOrType") == "EntityVillager" else bodies[h(vid + "b") % len(bodies)])
    req = []
    isl = MODE_ISLAND.get(mode)
    if isl:
        req.append("visited:" + isl)
    if ref_name == "Ludleth":
        req.append("garden:unique:100")
    if ref_name == "Spaceman":
        req.append("garden:unique:75")
    visitors.append({"id": vid, "name": ours, "rarity": rarity, "level": lvl, "wants": wants,
                     "entity": body, "requirement": " & ".join(req) if req else None, "home": isl})
    rn("visitor", "garden_visitor_" + vid, ref_name, ours)
visitors.sort(key=lambda x: (x["level"], x["rarity"], x["id"]))
assert len(visitors) == 140, len(visitors)
VISITOR_BONUS = {   # research 1.10 rare visitor drops (chance per accepted offer)
    "global": [
        {"item": "HYPERCHARGE_GARDEN_CHIP", "chance": 0.03},
        {"item": "QUICKDRAW_GARDEN_CHIP", "chance": 0.03},
        {"item": "FLOWERING_BOUQUET", "chance": 0.02},
        {"item": "GREEN_BANDANA", "chance": 0.004, "minRarity": "RARE"},
        {"item": "OVERGROWN_GRASS", "chance": 0.001},
    ],
    "special": {
        "carpenter": [{"item": "GREENHOUSE_BLUEPRINT", "chance": 1.0, "once": True, "minLevel": 7}],
        "spaceman": [{"item": "DCTR_SPACE_HELM", "chance": 0.05}],
        "jacob": [{"item": "ENCHANTMENT_CULTIVATING_1", "chance": 0.05}],
        "anita": [{"item": "ENCHANTMENT_DEDICATION_4", "chance": 0.0004}],
        "farmhand": [{"item": "ENCHANTMENT_REPLENISH_1", "chance": 0.05}],
        "librarian": [{"item": "ENCHANTMENT_DELICATE_5", "chance": 0.05}],
        "maeve": [{"item": "ENCHANTMENT_GREEN_THUMB_1", "chance": 0.25}],
        "ludleth": [{"item": "ENCHANTMENT_DEDICATION_4", "chance": 0.05}],
        "taylor": [{"item": "ENCHANTMENT_DEDICATION_4", "chance": 0.02}],
    },
}
# rarity tuning (research 1.10 clone defaults)
RARITY_TUNE = {
    "UNCOMMON": {"weight": 70, "gardenXp": 1, "copperMin": 15, "copperMax": 40, "size": 1.0, "bits": 0},
    "RARE": {"weight": 22, "gardenXp": 2, "copperMin": 30, "copperMax": 60, "size": 1.3, "bits": 1},
    "LEGENDARY": {"weight": 7, "gardenXp": 5, "copperMin": 50, "copperMax": 100, "size": 1.8, "bits": 2},
    "MYTHIC": {"weight": 1, "gardenXp": 10, "copperMin": 100, "copperMax": 200, "size": 2.5, "bits": 5},
    "SPECIAL": {"weight": 1, "gardenXp": 10, "copperMin": 100, "copperMax": 250, "size": 2.0, "bits": 5},
}
visitors_file = {"visitors": visitors, "bonus": VISITOR_BONUS, "rarities": RARITY_TUNE}

# =====================================================================================
# pests (research 1.12)
# =====================================================================================
PESTS = [
    # id, name, crop, level, attractants, entity, signature drops
    # Signature-drop chances: the wiki's per-pest drop tables (hypixelskyblock.minecraft.wiki/w/Pests, Oct 2026), which
    # agree with research garden_max.md 6.5 where it lists them (cricket 1%, mite 0.5%, moth 1%, beetle 7%, rat 0.4%).
    ("fly", "Fly", "WHEAT", 5, ["DUNG"], "BEE", [("BEADY_EYES", 0.03), ("VINYL_PRETTY_FLY", 0.05)]),
    ("cricket", "Cricket", "CARROT", 5, ["HONEY_JAR"], "SILVERFISH", [("CHIRPING_STEREO", 0.01), ("VINYL_CRICKET_CHOIR", 0.05)]),
    ("locust", "Locust", "POTATO", 5, ["PLANT_MATTER"], "ENDERMITE", [("LOCUST_LARVA", 0.04), ("VINYL_CICADA_SYMPHONY", 0.05)]),
    ("rat", "Rat", "PUMPKIN", 5, ["CHEESE_FUEL"], "RABBIT", [("RAT;4", 0.004), ("VINYL_RODENT_REVOLUTION", 0.05)]),
    ("mosquito", "Mosquito", "SUGAR_CANE", 5, ["COMPOST"], "BEE", [("CLIPPED_WINGS", 0.02), ("VINYL_BUZZIN_BEATS", 0.05)]),
    ("earthworm", "Earthworm", "MELON", 6, ["COMPOST"], "SILVERFISH", [("BOOKWORM_BOOK", 0.04), ("VINYL_EARTHWORM_ENSEMBLE", 0.05)]),
    ("mite", "Mite", "CACTUS", 7, ["CHEESE_FUEL"], "ENDERMITE", [("ATMOSPHERIC_FILTER", 0.005), ("VINYL_DYNAMITES", 0.05)]),
    ("moth", "Moth", "COCOA_BEANS", 8, ["HONEY_JAR"], "BAT", [("WRIGGLING_LARVA", 0.01), ("VINYL_WINGS_OF_HARMONY", 0.05)]),
    ("slug", "Slug", "MUSHROOM", 9, ["PLANT_MATTER"], "SLIME", [("SLUG;3", 0.01), ("SLUG;4", 0.002), ("VINYL_SLOW_AND_GROOVY", 0.05)]),
    ("beetle", "Beetle", "NETHER_WART", 10, ["DUNG"], "ENDERMITE", [("ENCHANTMENT_PESTERMINATOR_1", 0.07), ("VINYL_BEETLE", 0.05)]),
    ("dragonfly", "Dragonfly", "SUNFLOWER", 11, ["JELLY"], "PARROT", [("VERMIN_VAPORIZER_GARDEN_CHIP", 0.02), ("VINYL_IMAGINE_DRAGONFLIES", 0.05)]),
    ("firefly", "Firefly", "MOONFLOWER", 11, ["JELLY"], "ALLAY", [("FIRE_IN_A_BOTTLE", 0.02), ("VINYL_FIREFLY", 0.05)]),
    ("praying_mantis", "Praying Mantis", "WILD_ROSE", 12, ["JELLY"], "FROG", [("MANTID_CLAW", 0.02), ("VINYL_PRAY_FOR_ME", 0.05)]),
    ("lunar_moth", "Lunar Moth", "SUNFLOWER,MOONFLOWER,WILD_ROSE", 5, ["MOONDEW"], "BAT", [("ENCHANTMENT_ULTIMATE_SUNSET_1", 0.35)]),
    ("field_mouse", "Field Mouse", "", 5, ["SQUEAKY_TOY", "SQUEAKY_MOUSEMAT"], "RABBIT", [("SQUEAKY_TOY", 0.05), ("SQUEAKY_MOUSEMAT", 0.01)]),
]
# The guaranteed crop drop (research farming_passive.md 1.3 + the wiki's pest pages): base + Farming Fortune / scale
# ENCHANTED crops of the pest's crop (Fly at 2,520 FF drops 1 + 2520/52.5 = 49 Enchanted Wheat). The Slug is the
# Fly/Rat row (its crop value in research 1.3 back-solves to it); the Lunar Moth drops 2 of each of its three
# flowers on the flowers' row; the Field Mouse drops ONE random crop on that crop's own pest row.
PEST_CROP = {"fly": (1, 52.5), "rat": (1, 52.5), "slug": (1, 52.5), "cricket": (3, 15.75), "locust": (3, 15.75),
             "beetle": (3, 18.0), "moth": (3, 18.0), "earthworm": (5, 10.5), "mite": (2, 26.25), "mosquito": (2, 26.25),
             "dragonfly": (2, 26.25), "firefly": (2, 26.25), "praying_mantis": (2, 26.25), "lunar_moth": (2, 26.25),
             "field_mouse": (0, 0)}
# The pest's rare crop (research farming_passive.md 1.3 table; items registered by materials): 15% per pest, not
# fortune-affected; Overbloom raises it to 15% x (1 + Overbloom/100) (garden_max.md corrections). The wiki adds
# "when the crop is in season"; nothing in this game has crop seasons, so it always rolls. The Field Mouse rolls a
# random rare crop at 30%.
RARE_CROP = {"fly": "CORNUCOPIA", "cricket": "CARROT_ZEST", "locust": "DEEPFRIES", "rat": "AGGOURDIAN",
             "mosquito": "CANE_KNOT", "earthworm": "MELON_JUICE", "mite": "CACTUS_FLOWER", "moth": "DESIGNER_COFFEE_BEANS",
             "slug": "FEASTFUNGUS", "beetle": "BOTROOT", "dragonfly": "SALTED_SUNFLOWER_SEEDS",
             "firefly": "CRYSTALIZED_MOONLIGHT", "praying_mantis": "FLORAL_GELATIN", "field_mouse": "RANDOM"}
RARE_CHANCE = {"field_mouse": 0.30}
NIGHT_ONLY = {"firefly"}                 # wiki Firefly: "Fireflies can only spawn during the night."
COMPACTED_CHANCE = 0.0075                # wiki pest drop tables (0.75%; the Slug splits it 0.375% + 0.375%)
UNIVERSAL = ["COMPOST", "HONEY_JAR", "DUNG", "PLANT_MATTER", "CHEESE_FUEL", "JELLY"]
mobs = []
pests_table = []
for pid, name, crop, lvl, attract, ent, sig in PESTS:
    drops = [{"item": i, "min": 1, "max": 1, "chance": c} for i, c in sig]
    # the six spray materials: 10% each, all six at 100% from a Field Mouse (wiki)
    drops += [{"item": i, "min": 1, "max": 1, "chance": 1.0 if pid == "field_mouse" else 0.10, "magicFind": False} for i in UNIVERSAL]
    rare = sky["pest_rare_drops"].get(pid.upper())
    if rare:
        for item, amt in rare.items():
            drops.append({"item": item, "min": amt, "max": amt, "chance": round(COMPACTED_CHANCE / len(rare), 6)})
    rc = RARE_CROP.get(pid)
    if rc and rc != "RANDOM":
        drops.append({"item": rc, "min": 1, "max": 1, "chance": 0.15, "magicFind": False})
    mob = {"id": "garden_pest_" + pid, "name": name, "level": 1, "entity": ent, "health": 600, "damage": 0,
           "combatXp": 0, "coins": 10000 if pid == "field_mouse" else 1000, "drops": drops, "family": "garden_pest_" + pid,
           "flags": ["passive", "no_knockback", "silent", "no_ai"] + (["baby"] if ent in ("RABBIT", "BEE") else []),
           "behaviours": ["garden_pest"], "extra": {"pest": pid}}
    if ent == "SLIME":
        mob["extra"]["size"] = 1
    mobs.append(mob)
    cb, cs = PEST_CROP[pid]
    pests_table.append({"id": pid, "name": name, "crops": [c for c in crop.split(",") if c], "level": lvl,
                        "attractants": attract, "mob": "garden_pest_" + pid,
                        "signature": [i for i, _ in sig],
                        "cropBase": cb, "cropScale": cs, "rareCrop": rc,
                        "rareChance": RARE_CHANCE.get(pid, 0.15) if rc else 0.0,
                        "xpMult": 4.0 if pid == "field_mouse" else 1.0,   # wiki: Field Mouse 6,000 Farming XP vs 1,500
                        "nightOnly": pid in NIGHT_ONLY})
# every pest row of the formula (the Field Mouse borrows its random crop's pest row)
CROP_PEST_ROW = {c[0]: PEST_CROP[c[9]] for c in CROPS}
# FF penalty matrix (research 1.12, confirmed): rows = BPC bracket (0-99 .. 500+), cols = live pests 0..8 ; -1 = Max
penalty = [
    [0, 0, 0, 5, 15, 30, 50, 75, 100],
    [0, 0, 5, 15, 30, 50, 75, 100, 100],
    [0, 5, 15, 30, 50, 75, 100, 100, 100],
    [5, 15, 30, 50, 75, 100, 100, 100, 100],
    [15, 30, 50, 75, 100, 100, 100, 100, 100],
    [30, 50, 75, 100, 100, 100, 100, 100, 100],
]
VACUUMS = [  # id, our name, rarity, range, damage, ff, bpc, material, glow
    ("SKYMART_VACUUM", "Garden Vacuum", "COMMON", 5.0, 100, 5, 0, "LIGHTNING_ROD", False),
    ("SKYMART_TURBO_VACUUM", "Turbo Garden Vacuum", "UNCOMMON", 7.5, 150, 10, 0, "END_ROD", False),
    ("SKYMART_HYPER_VACUUM", "Hyper Garden Vacuum", "RARE", 10.0, 200, 15, 0, "BLAZE_ROD", False),
    ("INFINI_VACUUM", "Bottomless Vacuum", "EPIC", 12.5, 300, 20, 0, "BREEZE_ROD", False),
    ("INFINI_VACUUM_HOOVERIUS", "Bottomless Vacuum Maximus", "LEGENDARY", 15.0, 400, 25, 0, "BREEZE_ROD", True),
]
# damage + Farming Fortune: NEU items/*VACUUM*.json lore (100/150/200/300/400, +5/10/15/20/25). No vacuum gives Bonus
# Pest Chance (NEU lore; garden_max.md: "Hooverius does NOT give +25 Bonus Pest Chance"). Range: research 1.12 (?) TUNE
SPRAYERS = [  # id, name, rarity, minutes, bpc, multiplier, materials used per spray (wiki: 1 / 3 / 5)
    ("SPRAYONATOR", "Plot Sprayer", "UNCOMMON", 30, 25, 1.5, 1),
    ("JUICY_SPRAYONATOR", "Juicy Plot Sprayer", "RARE", 45, 50, 2.0, 3),
    ("SALTY_SPRAYONATOR", "Salty Plot Sprayer", "EPIC", 60, 75, 3.0, 5),
]
TRAPS = [  # id, name, rarity, capacity, mouse multiplier
    ("PEST_TRAP", "Pest Trap", "RARE", 2, 1.0),
    ("MOUSE_PEST_TRAP", "Mouse Trap", "EPIC", 3, 3.0),
    ("VERMIN_PEST_TRAP", "Vermin Trap", "LEGENDARY", 4, 4.0),
]
pests_file = {"pests": pests_table, "penalty": penalty,
              "vacuums": [{"id": a, "range": d, "damage": e, "bpc": g} for a, b, c, d, e, f, g, *_ in VACUUMS],
              "sprayers": [{"id": a, "minutes": d, "bpc": e, "mult": f, "uses": g} for a, b, c, d, e, f, g in SPRAYERS],
              "traps": [{"id": a, "capacity": d, "mouseMult": e} for a, b, c, d, e in TRAPS],
              "universal": UNIVERSAL,
              "cropRows": {k: {"base": v[0], "scale": v[1]} for k, v in CROP_PEST_ROW.items()},
              "rareCrops": [v for v in RARE_CROP.values() if v != "RANDOM"]}
garden["pests"] = pests_file

# Pesthunter Phillip's shop, paid in Pests (1 per pest vacuumed). Wiki "Pesthunter Phillip" (Oct 2026), every price
# and trade-in as listed there; garden_max.md 3.3 agrees on Trap 50 / Guide 500 / Hedgehog 500 / Badge..Relic 25-200 /
# equipment 40 each. The Vermin Trap also takes a Mouse Trap and a Fire in a Bottle (NEU FIRE_IN_A_BOTTLE lore:
# "Can be used at Phillip to upgrade the Mouse Trap into the Vermin Trap"). The barn skin is a Garden barn skin
# (`pestwarden_lair`), sold here as a skin unlock, not an item. The Pest Vest is NOT his (wiki: Talbot, 250 Pelts;
# farming's shops.json sells it for pelts).
PESTHUNTER_SHOP = [
    ("PEST_TRAP", 50, {}), ("MOUSE_PEST_TRAP", 75, {"PEST_TRAP": 1}),
    ("VERMIN_PEST_TRAP", 200, {"MOUSE_PEST_TRAP": 1, "FIRE_IN_A_BOTTLE": 1}),
    ("PESTHUNTERS_NECKLACE", 40, {}), ("PESTHUNTERS_CLOAK", 40, {}), ("PESTHUNTERS_BELT", 40, {}), ("PESTHUNTERS_GLOVES", 40, {}),
    ("PESTHUNTER_BADGE", 25, {}), ("PESTHUNTER_RING", 50, {"PESTHUNTER_BADGE": 1}),
    ("PESTHUNTER_ARTIFACT", 100, {"PESTHUNTER_RING": 1}), ("PESTHUNTER_RELIC", 200, {"PESTHUNTER_ARTIFACT": 1}),
    ("BIOHAZARD_HELMET", 50, {}), ("BIOHAZARD_SUIT", 80, {}), ("BIOHAZARD_LEGGINGS", 70, {}), ("BIOHAZARD_BOOTS", 40, {}),
    ("PESTHUNTING_GUIDE", 500, {}), ("HEDGEHOG;4", 500, {}), ("skin:pestwarden_lair", 1500, {}),
]
garden["pesthunterShop"] = [{"item": i, "pests": n, "tradeIn": t} for i, n, t in PESTHUNTER_SHOP]
# Pesthunter's Bonus (wiki): 5 Farming Fortune per Pest handed in, at most 40 Pests (200 FF), for 30 minutes
garden["pesthunterBonus"] = {"perPest": 5, "maxPests": 40, "minutes": 30}

# Sowdust (wiki "Sowdust"): ceil(total Farming Fortune / 100) per Garden crop broken, halved for Sugar Cane and
# Cactus; greenhouse harvests 5 x round(base / 5 x crop multiplier) x (1 + FF/100). Spent only on chip levels.
garden["sowdust"] = {
    "halved": ["SUGAR_CANE", "CACTUS"],
    "greenhouseMult": {"WHEAT": 1, "CARROT": 3.5, "POTATO": 3, "PUMPKIN": 0.85, "SUGAR_CANE": 2, "MELON": 4,
                       "CACTUS": 1.5, "COCOA_BEANS": 2, "MUSHROOM": 0.95, "NETHER_WART": 3, "SUNFLOWER": 2,
                       "MOONFLOWER": 2, "WILD_ROSE": 2},
    "earthyPct": 5,
}
# Cropie armor's Squashbuckle (wiki "Cropie Armor"): inside the Greenhouse a 4 / 4 / 8 / 12% chance per crop
# harvested to drop a Squash at 1 / 2 / 3 / 4 pieces, pieces required 2+ (so 1 piece gives nothing).
garden["greenhouseSquash"] = {"set": "CROPIE_", "item": "SQUASH", "pct": [0, 0, 4, 8, 12]}

# =====================================================================================
# greenhouse (research 1.15) + mutations (40 reference items, renamed, our own recipes)
# =====================================================================================
GH_CROPS = [  # crop key, yield, cycles, effect
    ("WHEAT", 72, 8, "harvest_boost"), ("CARROT", 175, 8, "xp_boost"), ("POTATO", 150, 8, "immunity"),
    ("PUMPKIN", 68, 11, "bonus_drops"), ("SUGAR_CANE", 136, 8, "improved_xp_boost,harvest_loss"),
    ("MELON", 320, 11, "water_retain"), ("CACTUS", 132, 8, "improved_water_retain,harvest_loss"),
    ("COCOA_BEANS", 120, 6, "immunity"), ("MUSHROOM", 30, 6, "improved_harvest_boost,water_drain"),
    ("NETHER_WART", 108, 8, "improved_harvest_boost,xp_loss"), ("SUNFLOWER", 232, 15, "bonus_drops"),
    ("MOONFLOWER", 232, 15, "bonus_drops"), ("WILD_ROSE", 200, 15, "effect_spread"),
]
EFFECTS = {
    "harvest_boost": ("Harvest Boost", "&a+20% &7yield to neighbours", "yield", 20),
    "improved_harvest_boost": ("Improved Harvest Boost", "&a+30% &7yield to neighbours", "yield", 30),
    "xp_boost": ("XP Boost", "&a+20% &7Farming XP to neighbours", "xp", 20),
    "improved_xp_boost": ("Improved XP Boost", "&a+30% &7Farming XP to neighbours", "xp", 30),
    "water_retain": ("Water Retain", "&a+50% &7water kept by neighbours", "water", 50),
    "improved_water_retain": ("Improved Water Retain", "&a+100% &7water kept by neighbours", "water", 100),
    "immunity": ("Immunity", "&7Neighbours ignore negative effects", "immunity", 0),
    "effect_spread": ("Effect Spread", "&7Passes neighbours' effects along", "spread", 0),
    "bonus_drops": ("Bonus Drops", "&7Rolls the Harvest Bounty when harvested", "bounty", 0),
    "harvest_loss": ("Harvest Loss", "&c-20% &7yield to neighbours", "yield", -20),
    "xp_loss": ("XP Loss", "&c-20% &7Farming XP to neighbours", "xp", -20),
    "water_drain": ("Water Drain", "&c-30% &7water kept by neighbours", "water", -30),
}
MUTATIONS = [  # ref id, our name, material
    ("SCOURROOT", "Grimroot", "HANGING_ROOTS"), ("BLASTBERRY", "Boomberry", "SWEET_BERRIES"),
    ("CHEESEBITE", "Curdbloom", "OXEYE_DAISY"), ("STOPLIGHT_PETAL", "Signal Petal", "POPPY"),
    ("STARTLEVINE", "Jumpvine", "GLOW_LICHEN"), ("SHADEVINE", "Duskvine", "VINE"),
    ("GODSEED", "Worldseed", "TORCHFLOWER_SEEDS"), ("CREAMBLOOM", "Milkpetal", "LILY_OF_THE_VALLEY"),
    ("CINDERSHADE", "Embershade", "CRIMSON_FUNGUS"), ("LONELILY", "Lonepetal", "AZURE_BLUET"),
    ("FLESHTRAP", "Gnawtrap", "PITCHER_POD"), ("GLOOMGOURD", "Murkgourd", "CARVED_PUMPKIN"),
    ("JERRYFLOWER", "Geraldflower", "CORNFLOWER"), ("SHELLFRUIT", "Husknut", "TURTLE_SCUTE"),
    ("CHOCONUT", "Cacaonut", "BROWN_DYE"), ("THUNDERLING", "Stormsprout", "LIGHTNING_ROD"),
    ("MAGIC_JELLYBEAN", "Wonder Jellybean", "SLIME_BALL"), ("NOCTILUME", "Nightglow", "GLOW_BERRIES"),
    ("TIMESTALK", "Clockstalk", "CLOCK"), ("ALL_IN_ALOE", "Jackpot Aloe", "SEA_PICKLE"),
    ("PUFFERCLOUD", "Puffbloom", "WHITE_DYE"), ("TURTLELLINI", "Shellini", "LILY_PAD"),
    ("ASHWREATH", "Cinderwreath", "DEAD_BUSH"), ("ZOMBUD", "Deadbud", "WITHER_ROSE"),
    ("DEVOURER", "Gobbler", "BIG_DRIPLEAF"), ("CHOCOBERRY", "Cocoaberry", "COCOA_BEANS"),
    ("SNOOZLING", "Drowsling", "SPORE_BLOSSOM"), ("DUSTGRAIN", "Siltgrain", "WHEAT_SEEDS"),
    ("THORNSHADE", "Briarshade", "SWEET_BERRIES"), ("CHLORONITE", "Leafstone", "MOSS_BLOCK"),
    ("DO_NOT_EAT_SHROOM", "Don't-Eat Cap", "RED_MUSHROOM"), ("GLASSCORN", "Crystalcorn", "AMETHYST_CLUSTER"),
    ("VEILSHROOM", "Shroudcap", "BROWN_MUSHROOM"), ("WITHERBLOOM", "Blightbloom", "WITHER_ROSE"),
    ("DUSKBLOOM", "Eveningbloom", "ALLIUM"), ("PLANTBOY_ADVANCE", "Sprout Pocket Console", "OBSERVER"),
    ("SOGGYBUD", "Dampbud", "KELP"), ("PHANTOMLEAF", "Wraithleaf", "PHANTOM_MEMBRANE"),
    ("COALROOT", "Charroot", "COAL"),
]
# CHORUS_FRUIT (the 40th) is skipped: its id is a vanilla material and registering it would replace vanilla chorus fruit.
crop_keys = [c[0] for c in GH_CROPS]
mutations = []
done = []
for i, (mid, name, mat) in enumerate(MUTATIONS):
    tier = ref_items.get(mid, {}).get("tier", "COMMON")
    r = h(mid)
    # recipe: the neighbour crops an EMPTY cell needs (orthogonal) for this mutation to sprout.
    # COMMON 2 crops, UNCOMMON 3, RARE 4 (two pairs), EPIC an earlier mutation + 2 crops,
    # LEGENDARY two earlier mutations + 1 crop. Deterministic from the id so every server agrees.
    if tier == "COMMON":
        a, b = crop_keys[r % 13], crop_keys[(r // 13 + 1 + r % 5) % 13]
        if a == b:
            b = crop_keys[(crop_keys.index(a) + 3) % 13]
        need = [a, b]
    elif tier == "UNCOMMON":
        a = crop_keys[r % 13]
        b = crop_keys[(r // 7 + 2) % 13]
        c = crop_keys[(r // 11 + 5) % 13]
        need = [a, b, c]
    elif tier == "RARE":
        a = crop_keys[r % 13]
        b = crop_keys[(r // 17 + 4) % 13]
        need = [a, a, b, b]
    elif tier == "EPIC":
        base = [m["id"] for m in done if m["rarity"] in ("COMMON", "UNCOMMON")] or [crop_keys[0]]
        need = [base[r % len(base)], crop_keys[r % 13], crop_keys[(r // 3 + 6) % 13]]
    else:
        base = [m["id"] for m in done if m["rarity"] in ("RARE", "EPIC", "UNCOMMON")] or [crop_keys[1]]
        need = [base[r % len(base)], base[(r // 5 + 1) % len(base)], crop_keys[(r // 9) % 13]]
    chance = {"COMMON": 0.25, "UNCOMMON": 0.15, "RARE": 0.10, "EPIC": 0.06, "LEGENDARY": 0.03}.get(tier, 0.1)
    # first-time Crop Analyzer Copper (garden_max.md 6.2 from the wiki: 250 for each Common mutation, Soggybud (Rare)
    # 750; the other rarities are not pinned and follow the step, TUNE)
    copper = {"COMMON": 250, "UNCOMMON": 500, "RARE": 750, "EPIC": 1000, "LEGENDARY": 1500}.get(tier, 250)
    m = {"id": mid, "name": name, "rarity": tier, "material": mat, "needs": need, "chance": chance, "copper": copper,
         "cycles": {"COMMON": 4, "UNCOMMON": 5, "RARE": 6, "EPIC": 8, "LEGENDARY": 10}.get(tier, 5),
         "vine": {"COMMON": 0.05, "UNCOMMON": 0.08, "RARE": 0.12, "EPIC": 0.2, "LEGENDARY": 0.35}.get(tier, 0.05)}
    mutations.append(m)
    done.append(m)
    rn("mutation", mid, clean(ref_items.get(mid, {}).get("name", mid)), name)
BOUNTY = [  # research 1.15 Harvest Bounty
    {"item": "OVERCLOCKER_3000", "chance": 0.0005}, {"item": "IRIDIUM", "chance": 0.002},
    {"item": "BURROWING_SPORES", "chance": 0.002}, {"item": "OVERGROWN_GRASS", "chance": 0.001},
    {"item": "EVERGREEN_GARDEN_CHIP", "chance": 0.03}, {"item": "SYNTHESIS_GARDEN_CHIP", "chance": 0.03},
    {"item": "ETHEREAL_VINE", "chance": 0.05},
]
greenhouse = {
    "baseStageSeconds": 14400, "minStageSeconds": 6063, "size": 5, "xpPerHarvest": 320,
    "crops": [{"crop": c, "yield": y, "cycles": n, "effects": e.split(",")} for c, y, n, e in GH_CROPS],
    "effects": {k: {"name": v[0], "desc": v[1], "kind": v[2], "value": v[3]} for k, v in EFFECTS.items()},
    "mutations": mutations, "bounty": BOUNTY,
    "plantCost": 64,           # TUNE: base crops consumed to plant one cell (not in research)
    "waterMax": 10, "waterPerStage": [2, 3],
    # DNA Analysis Milestones I-VI at 1 / 10 / 15 / 20 / 30 / 40 analyses, each +5 Farming Fortune, +10 Crop Growth and
    # +15 SkyBlock XP; I gives the Bioanalysis Talisman (garden_max.md 6.2, the wiki's Crop Analyzer page)
    "analyzerLevels": [1, 10, 15, 20, 30, 40],
    "analyzerReward": {"fortune": 5, "cropGrowth": 10, "skyblockXp": 15, "firstItem": "BIOANALYSIS_TALISMAN"},
}

# =====================================================================================
# items owned by garden (CONTRACT section 3: compost, copper shop items, pest drops, vacuums, sprays)
# =====================================================================================
items = []


def item(iid, name, material, rarity="COMMON", category="MATERIAL", lore=None, ref=None, **kw):
    d = {"id": iid, "name": name, "material": material, "rarity": rarity, "category": category,
         "lore": lore or [], "tags": kw.pop("tags", [])}
    d.update(kw)
    items.append(d)
    refname = ref if ref is not None else clean(ref_items.get(iid, {}).get("name", name))
    rn("item", iid, refname, name)
    return d


def sell(iid, default=0):
    return ref_items.get(iid, {}).get("npc_sell_price", default) or default


ATTRACT = {}
for pid, name, crop, lvl, attract, *_ in PESTS:
    for a in attract:
        ATTRACT.setdefault(a, []).append(name)
for iid, nm, mat, rar in [("COMPOST", "Compost", "BROWN_DYE", "UNCOMMON"), ("DUNG", "Dung", "BLACK_DYE", "UNCOMMON"),
                          ("HONEY_JAR", "Honey Jar", "HONEY_BOTTLE", "UNCOMMON"),
                          ("PLANT_MATTER", "Plant Matter", "GREEN_DYE", "UNCOMMON"),
                          ("CHEESE_FUEL", "Tasty Cheese", "YELLOW_DYE", "UNCOMMON"), ("JELLY", "Jelly", "SLIME_BALL", "UNCOMMON"),
                          ("MOONDEW", "Night Dew", "GLOW_INK_SAC", "EPIC")]:
    lore = ["&7Spread it over a Garden plot with a", "&7Plot Sprayer, or bait a Pest Trap.", "",
            "&7Pests drawn to it: &a" + ", ".join(ATTRACT.get(iid, []))]
    if iid == "COMPOST":
        lore = ["&7Rich, dark and fresh from the", "&7Composter. Buys new Garden plots,", "&7baits traps and feeds sprayers.", "",
                "&7Pests drawn to it: &a" + ", ".join(ATTRACT.get(iid, []))]
    if iid == "CHEESE_FUEL":
        lore += ["", "&7Minions love it too: doubles what", "&7a minion makes for &a1 hour&7."]
    item(iid, nm, mat, rar, lore=lore, npcSell=sell(iid, 4), tags=["bazaar", "garden"])
item("ENCHANTED_COMPOST", "Compost Bundle", "BROWN_DYE", "RARE", glow=True, npcSell=sell("ENCHANTED_COMPOST", 10),
     lore=["&7160 Compost, tied up with twine.", "&7The bigger Garden plots want these."], tags=["bazaar", "garden"])
item("BIOFUEL", "Biofuel", "LIME_DYE", "RARE", npcSell=10000,
     lore=["&8Machine Fuel", "", "&7Adds &2+3,000 Fuel &7to a machine", "&7that takes fuel, like the Composter.", "",
           "&7Grown, not drilled."], tags=["bazaar", "garden", "fuel"])
for iid, nm, mat, rar, lore in [
    ("LOCUST_LARVA", "Locust Larva", "SPIDER_EYE", "UNCOMMON",
     ["&8Consumable", "", "&7Eat it on your Garden to drop", "&a+1 Pest &7into your vacuum bag.", "", "&eRight-click to consume!"]),
    ("WRIGGLING_LARVA", "Wriggling Larva", "FERMENTED_SPIDER_EYE", "EPIC",
     ["&8Consumable", "", "&7Swallow it to gain &2+2 ൠ Bonus Pest", "&2Chance &7for good. Your stomach", "&7gives up after &a5&7.", "", "&eRight-click to consume!"]),
    ("CHIRPING_STEREO", "Cricket Boombox", "JUKEBOX", "LEGENDARY",
     ["&7Bring it to the Copper Market to", "&7turn a &5Bottomless Vacuum &7into a", "&6Bottomless Vacuum Maximus&7, which", "&7can play vinyls to pests."]),
    ("FIRE_IN_A_BOTTLE", "Fire in a Bottle", "BLAZE_POWDER", "LEGENDARY",
     ["&7The Pestwarden can use this to", "&7turn a &5Mouse Trap &7into a", "&6Vermin Trap&7."]),
    ("BOOKWORM_BOOK", "Worn Pest Almanac", "BOOK", "RARE",
     ["&8Combinable in Anvil", "", "&7Apply to a vacuum for &c+10❁ Damage&7.", "&7Up to &a5 &7times per vacuum."]),
    ("PESTHUNTING_GUIDE", "Vermin Handbook", "KNOWLEDGE_BOOK", "LEGENDARY",
     ["&8Combinable in Anvil", "", "&7Raises a Pest Bane enchant on armour", "&7by one level. Needs Pest Bane V.", "",
      "&7Dog-eared and smelling of mulch."]),
    ("SQUEAKY_MOUSEMAT", "Squeaky Mousemat", "PAPER", "LEGENDARY",
     ["&7Found at the bottom of a vacuum", "&7bag, ears and all.", "", "&7Bait a trap with it to catch", "&7Field Mice."]),
    ("GREENHOUSE_BLUEPRINT", "Greenhouse Blueprint", "MAP", "RARE",
     ["&7A sketch of a glass growing house.", "&7Bring it to the Greenhouse in your", "&7Garden to build one.", "",
      "&8* Co-op Soulbound *"]),
    ("ETHEREAL_VINE", "Ghostvine", "VINE", "EPIC",
     ["&7A vine you can almost see through.", "&7Harvested from greenhouse mutations.", "&7Builds extra greenhouse grids."]),
    ("OVERCLOCKER_3000", "Overtuner 9000", "COMPARATOR", "LEGENDARY",
     ["&7Pushes a farming tool past its", "&7level cap, up to level &a50&7."]),
    ("IRIDIUM", "Iridium", "IRON_NUGGET", "EPIC", ["&7A rare, heavy metal that turns up", "&7in greenhouse harvests."]),
]:
    item(iid, nm, mat, rar, lore=lore, npcSell=sell(iid, 0), tags=["garden"] + (["consumable_garden"] if "Consumable" in lore[0] else []))

# vinyls
VINYLS = [("VINYL_PRETTY_FLY", "Buzz Off Vinyl", "MUSIC_DISC_STAL", "fly"),
          ("VINYL_CRICKET_CHOIR", "Night Chorus Vinyl", "MUSIC_DISC_CHIRP", "cricket"),
          ("VINYL_CICADA_SYMPHONY", "Summer Drone Vinyl", "MUSIC_DISC_BLOCKS", "locust"),
          ("VINYL_RODENT_REVOLUTION", "Whisker Waltz Vinyl", "MUSIC_DISC_13", "rat"),
          ("VINYL_BUZZIN_BEATS", "Hum Along Vinyl", "MUSIC_DISC_WARD", "mosquito"),
          ("VINYL_EARTHWORM_ENSEMBLE", "Soil Serenade Vinyl", "MUSIC_DISC_MALL", "earthworm"),
          ("VINYL_DYNAMITES", "Tiny Thunder Vinyl", "MUSIC_DISC_STRAD", "mite"),
          ("VINYL_WINGS_OF_HARMONY", "Fluttersong Vinyl", "MUSIC_DISC_WAIT", "moth"),
          ("VINYL_SLOW_AND_GROOVY", "Trail of Slime Vinyl", "MUSIC_DISC_FAR", "slug"),
          ("VINYL_BEETLE", "Shell Shock Vinyl", "MUSIC_DISC_MELLOHI", "beetle"),
          ("VINYL_IMAGINE_DRAGONFLIES", "Skimmer Dreams Vinyl", "MUSIC_DISC_CAT", "dragonfly"),
          ("VINYL_FIREFLY", "Glow Show Vinyl", "MUSIC_DISC_BLOCKS", "firefly"),
          ("VINYL_PRAY_FOR_ME", "Folded Hands Vinyl", "MUSIC_DISC_MELLOHI", "praying_mantis")]
for iid, nm, mat, pest in VINYLS:
    pname = [p[1] for p in PESTS if p[0] == pest][0]
    item(iid, nm, mat, "UNCOMMON", npcSell=sell(iid, 50000), tags=["garden", "vinyl"], extra={"pest": pest},
         lore=["&7Spin it on a Bottomless Vacuum", "&7Maximus. While it plays, &6" + pname + "s", "&7turn up far more often."])
garden["vinyls"] = {iid: pest for iid, _, _, pest in VINYLS}

# garden chips (redeemed into chip levels). Effects are the reference items' own lore (NEU items/*_GARDEN_CHIP.json),
# one number per chip level; the names are ours. (Wave 5: the first pass invented effects before this lore was in research.)
CHIPS = [("HYPERCHARGE_GARDEN_CHIP", "Surge Chip", ["&7Temporary &6☘ Farming Fortune &7buffs", "&7are &a3% &7stronger per level."], "ff_buffs", 3),
         ("QUICKDRAW_GARDEN_CHIP", "Swiftpick Chip", ["&7Visitors take &a1.5% &7less time to", "&7show up while you harvest crops,", "&7per level."], "visitor_harvest", 1.5),
         ("OVERDRIVE_GARDEN_CHIP", "Redline Chip", ["&6+5 Crop Fortune &7for the crops of a", "&7running Harvest Fair, per level."], "contest_fortune", 5),
         ("SYNTHESIS_GARDEN_CHIP", "Fusion Chip", ["&7Analysing a mutation in the Crop", "&7Analyzer pays &a1% &7more Copper", "&7per level."], "analyzer_copper", 1),
         ("EVERGREEN_GARDEN_CHIP", "Perennial Chip", ["&7Harvests in the Greenhouse give", "&a2% &7more base crops per level."], "greenhouse", 2),
         ("CROPSHOT_GARDEN_CHIP", "Harvest Shot Chip", ["&6+3☘ Farming Fortune &7per level."], "ff", 3),
         ("RAREFINDER_GARDEN_CHIP", "Lucky Find Chip", ["&d+1.5 Overbloom &7per level."], "overbloom", 1.5),
         ("VERMIN_VAPORIZER_GARDEN_CHIP", "Pest Zapper Chip", ["&2+3ൠ Bonus Pest Chance &7per level."], "pest_chance", 3),
         ("MECHAMIND_GARDEN_CHIP", "Cogmind Chip", ["&7Farming tools earn &a1.5% &7more", "&7tool experience per level."], "tool_xp", 1.5),
         ("SOWLEDGE_GARDEN_CHIP", "Seedsense Chip", ["&3+1☯ Farming Wisdom &7per level."], "wisdom", 1)]
# Per chip level at Rare / Epic / Legendary: the wiki's Garden Chips page gives each chip's value at its rarity's cap
# (Rare L10, Epic L15, Legendary L20), e.g. Vermin Vaporizer +30 / +60 / +100 = 3 / 4 / 5 a level; Synthesis
# 10 / 22.5 / 40% = 1 / 1.5 / 2; Sowledge 10 / 18.75 / 30 = 1 / 1.25 / 1.5; Overdrive 50 / 90 / 140 = 5 / 6 / 7.
CHIP_RARITY = {"HYPERCHARGE_GARDEN_CHIP": [3, 4, 5], "QUICKDRAW_GARDEN_CHIP": [1.5, 2, 2.5],
               "OVERDRIVE_GARDEN_CHIP": [5, 6, 7], "SYNTHESIS_GARDEN_CHIP": [1, 1.5, 2],
               "EVERGREEN_GARDEN_CHIP": [2, 2.5, 3], "CROPSHOT_GARDEN_CHIP": [3, 4, 5],
               "RAREFINDER_GARDEN_CHIP": [1.5, 2, 2.5], "VERMIN_VAPORIZER_GARDEN_CHIP": [3, 4, 5],
               "MECHAMIND_GARDEN_CHIP": [1.5, 2, 2.5], "SOWLEDGE_GARDEN_CHIP": [1, 1.25, 1.5]}
SOWDUST_ICON = "\u273f"
for iid, nm, effect, kind, per in CHIPS:
    assert CHIP_RARITY[iid][0] == per, iid
    lore = ["&8Garden Chip", "", "&6Ability: " + nm.replace(" Chip", "")] + effect
    lore += ["", "&7Redeem chips to raise its rarity:", "&9Rare &71 chip, max level &a10", "&5Epic &7+4 chips, max level &a15",
             "&6Legendary &7+16 chips, max level &a20", "&7Level it up with &2" + SOWDUST_ICON + " Sowdust&7.", "",
             "&eRight-click to redeem!", "&eSneak right-click to redeem all!"]
    item(iid, nm, "AMETHYST_SHARD", "RARE", npcSell=sell(iid, 25000), tags=["bazaar", "garden", "garden_chip"],
         extra={"chip": kind, "per": per}, lore=lore)
garden["chips"] = {iid: {"kind": kind, "per": per, "perRarity": CHIP_RARITY[iid], "name": nm} for iid, nm, _, kind, per in CHIPS}
# Garden Chips (wiki, Oct 2026): the first chip unlocks it at level 1 (Rare); 4 more make it Epic (5 total), 16 more
# Legendary (21 total). Level caps 10 / 15 / 20. Levels 2..20 cost Sowdust (sowdustCost[i] = cost to reach level i+2):
# 100k, 200k, 300k, 400k, then +150k a level up to 2.65M for level 20 - 25,000,000 to max one chip.
CHIP_COSTS = [100000, 200000, 300000, 400000] + [550000 + 150000 * i for i in range(15)]
assert len(CHIP_COSTS) == 19 and sum(CHIP_COSTS) == 25000000 and CHIP_COSTS[-1] == 2650000
garden["chipRules"] = {"rarities": ["RARE", "EPIC", "LEGENDARY"], "chipsFor": [1, 5, 21], "caps": [10, 15, 20],
                       "sowdustCost": CHIP_COSTS}

# Sprayonator nozzles (NEU items JUICY_NOZZLE / SALTY_NOZZLE: crafted, then traded in at SkyMart with the sprayer)
item("JUICY_NOZZLE", "Juicy Nozzle", "TRIPWIRE_HOOK", "RARE", npcSell=sell("JUICY_NOZZLE", 0), tags=["garden"],
     lore=["&7Bring it to the &aCopper Market &7with a", "&aPlot Sprayer &7to make a &9Juicy Plot Sprayer&7."])
item("SALTY_NOZZLE", "Salty Nozzle", "TRIPWIRE_HOOK", "EPIC", npcSell=sell("SALTY_NOZZLE", 0), tags=["garden"],
     lore=["&7Bring it to the &aCopper Market &7with a", "&9Juicy Plot Sprayer &7to make a &5Salty Plot Sprayer&7."])
# Green Bandana (NEU GREEN_BANDANA lore: "+4 Farming Fortune for each of your Garden Levels"; a Rare+ visitor drop)
item("GREEN_BANDANA", "Green Bandana", "LIME_DYE", "EPIC", "PET_ITEM", tags=["garden", "pet_item"],
     npcSell=sell("GREEN_BANDANA", 0), extra={"fortunePerGardenLevel": 4},
     lore=["&8Consumed on use", "", "&7Tie it on your pet for &6+4\u2618 Farming", "&6Fortune &7for each of your &aGarden Levels&7.", "",
           "&eRight-click on your summoned pet to", "&egive it this item!"])

# vacuums
for iid, nm, rar, rng, dmg, ff, bpc, mat, glow in VACUUMS:
    abil = [{"id": "garden_vacuum", "name": "Vacuum", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 0,
             "lore": ["&7Hold to pull &c" + str(dmg) + " &7damage a second", "&7out of pests within &a" + str(rng) + " &7blocks."]},
            {"id": "garden_pest_tracker", "name": "Pest Tracker", "trigger": "LEFT_CLICK", "mana": 0, "cooldown": 1,
             "lore": ["&7Draws a trail to the nearest", "&7pest in your Garden."]}]
    if iid == "INFINI_VACUUM_HOOVERIUS":
        abil.append({"id": "garden_stereo", "name": "Pest Radio", "trigger": "SNEAK_RIGHT_CLICK", "mana": 0, "cooldown": 1,
                     "lore": ["&7Choose a vinyl to play. Pests that", "&7like the tune show up more often."]})
    stats = {"FARMING_FORTUNE": ff}
    if bpc:
        stats["PEST_CHANCE"] = bpc
    item(iid, nm, mat, rar, "VACUUM", damage=dmg, stats=stats, abilities=abil, glow=glow,
         requirements=["garden:5"], tags=["garden", "reforgeable"], extra={"range": rng, "vacuumDamage": dmg},
         lore=["&7The only thing pests are afraid of."])
# sprayers
for iid, nm, rar, mins, bpc, mult, uses in SPRAYERS:
    item(iid, nm, "WARPED_FUNGUS_ON_A_STICK", rar, "NONE", maxStack=1, requirements=["garden:5"], tags=["garden"],
         extra={"minutes": mins, "bpc": bpc, "mult": mult, "uses": uses},
         abilities=[{"id": "garden_spray", "name": "Spray", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 3,
                     "lore": ["&7Coats the plot you stand on with the", "&7selected material for &a" + str(mins) + "m&7.",
                              "&7Uses &a" + str(uses) + " &7of the material."]},
                    {"id": "garden_spray_config", "name": "Configure", "trigger": "LEFT_CLICK", "mana": 0, "cooldown": 0,
                     "lore": ["&7Pick the material to spray."]}],
         lore=["&7Nozzle bonus while in a sprayed plot:", "&8➜ &2+" + str(bpc) + " ൠ Bonus Pest Chance",
               "&8➜ &7Pests are &a" + str(mult) + "x &7more likely there."])
# repellents
for iid, nm, rar, f in [("PEST_REPELLENT", "Pest Repellent", "UNCOMMON", 2), ("PEST_REPELLENT_MAX", "Pest Repellent MAX", "RARE", 4)]:
    item(iid, nm, "LIME_DYE", rar, glow=(f == 4), tags=["garden", "consumable_garden"], extra={"factor": f},
         lore=["&8Consumable", "", "&7A smell pests simply cannot stand.", "&7For &a60m&7 pests take &a" + str(f) + "x &7as", "&7long to show up.", "", "&eRight-click to consume!"])
# traps
for iid, nm, rar, cap, mm in TRAPS:
    lore = ["&7Set it in a Garden plot and bait it", "&7to catch pests while you are away.", "&7Holds &a" + str(cap) + " &7pests. Up to &a3 &7traps."]
    if mm > 1:
        lore += ["", "&7Field Mice are &a" + str(int(mm)) + "x &7as likely to", "&7wander in."]
    item(iid, nm, "TRIPWIRE_HOOK", rar, "NONE", maxStack=1, tags=["garden"], extra={"capacity": cap, "mouseMult": mm}, lore=lore)
# watering cans (greenhouse)
CANS = [("HYDRO_CAN_1000", "Watering Can", "COMMON", 3000), ("HYDRO_CAN_TURBO_2000", "Turbo Watering Can", "UNCOMMON", 6000),
        ("HYDRO_CAN_ULTRA_3000", "Ultra Watering Can", "RARE", 10000), ("AQUAMASTER_X", "Rainmaker", "EPIC", 20000),
        ("AQUAMASTER_HYDROMAX", "Rainmaker Deluxe", "LEGENDARY", 40000)]
for iid, nm, rar, cap in CANS:
    item(iid, nm, "CARROT_ON_A_STICK", rar, "NONE", maxStack=1, tags=["garden", "watering_can"], extra={"capacity": cap},
         lore=["&7Keeps greenhouse plants watered.", "&7Holds &b" + "{:,}".format(cap) + " &7water; refill it from the", "&7greenhouse well."])
# scythes
item("GARDEN_SCYTHE", "Garden Scythe", "IRON_HOE", "UNCOMMON", "HOE", tags=["garden"], extra={"radius": 2},
     lore=["&7Clears grass, leaves and flowers", "&7in a &a5x5 &7area of an owned plot."])
item("SAM_SCYTHE", "Starter Scythe", "IRON_HOE", "COMMON", "HOE", tags=["garden", "soulbound"], extra={"radius": 1},
     lore=["&7Clears grass, leaves and flowers", "&7in a &a3x3 &7area of an owned plot."])
# mutations
for m in mutations:
    item(m["id"], m["name"], m["material"], m["rarity"], tags=["garden", "mutation", "bazaar"],
         npcSell=sell(m["id"], 1000),
         lore=["&7A greenhouse mutation. It only sprouts", "&7in an empty cell with the right", "&7neighbours.", "",
               "&8" + m["rarity"].title() + " Mutation"])

# =====================================================================================
# recipes
# =====================================================================================
recipes = [
    {"id": "garden:ENCHANTED_COMPOST", "type": "CRAFTING", "result": "ENCHANTED_COMPOST", "amount": 1,
     "grid": ["", "COMPOST:32", "", "COMPOST:32", "COMPOST:32", "COMPOST:32", "", "COMPOST:32", ""]},
    {"id": "garden:PEST_REPELLENT_MAX", "type": "CRAFTING", "result": "PEST_REPELLENT_MAX", "amount": 1,
     "grid": ["", "PEST_REPELLENT:1", "", "PEST_REPELLENT:1", "COMPOST:8", "PEST_REPELLENT:1", "", "PEST_REPELLENT:1", ""]},
    # nozzles: NEU recipes (A2, B1-B3, C1-C2); the sprayers themselves are SkyMart trade-ins (see the shop below)
    {"id": "garden:JUICY_NOZZLE", "type": "CRAFTING", "result": "JUICY_NOZZLE", "amount": 1,
     "grid": ["", "ENCHANTED_MELON:32", "", "ENCHANTED_MELON:32", "ENCHANTED_MELON:32", "ENCHANTED_MELON:32",
              "ENCHANTED_MELON:32", "ENCHANTED_MELON:32", ""]},
    {"id": "garden:SALTY_NOZZLE", "type": "CRAFTING", "result": "SALTY_NOZZLE", "amount": 1,
     "grid": ["", "SALTED_SUNFLOWER_SEEDS:16", "", "SALTED_SUNFLOWER_SEEDS:16", "SALTED_SUNFLOWER_SEEDS:16",
              "SALTED_SUNFLOWER_SEEDS:16", "SALTED_SUNFLOWER_SEEDS:16", "SALTED_SUNFLOWER_SEEDS:16", ""]},
    {"id": "garden:MOUSE_PEST_TRAP", "type": "CRAFTING", "result": "MOUSE_PEST_TRAP", "amount": 1,
     "grid": ["", "SQUEAKY_MOUSEMAT:1", "", "CHEESE_FUEL:16", "PEST_TRAP:1", "CHEESE_FUEL:16", "", "", ""]},
]
# TUNE: the reference crafts these from items we do not have; recipes above are clone defaults.

# =====================================================================================
# Copper Market (SkyMart) catalogue - gems become copper x10 (no paid currency in this game)
# =====================================================================================
CAT = {"BIOFUEL": "Farming", "SAM_SCYTHE": "Tools", "GARDEN_SCYTHE": "Tools", "BUILDERS_RULER": "Tools",
       "SUNDER;1": "Books", "DEDICATION;1": "Books", "GREEN_THUMB;1": "Books"}
BOOK = {"SUNDER;1": "ENCHANTMENT_SUNDER_1", "DEDICATION;1": "ENCHANTMENT_DEDICATION_1", "GREEN_THUMB;1": "ENCHANTMENT_GREEN_THUMB_1"}
LEVEL_GATE = {"SKYMART_TURBO_VACUUM": 6, "SKYMART_HYPER_VACUUM": 8, "INFINI_VACUUM": 10, "INFINI_VACUUM_HOOVERIUS": 12,
              "AQUAMASTER_X": 9, "AQUAMASTER_HYDROMAX": 11, "BLOSSOM_BRACELET": 8, "BLOSSOM_BELT": 9, "BLOSSOM_NECKLACE": 10,
              "BLOSSOM_CLOAK": 11, "COPPER_RING": 8, "COPPER_ARTIFACT": 12, "GREEN_THUMB;1": 5, "DEDICATION;1": 3}
NEEDS = {"INFINI_VACUUM_HOOVERIUS": {"INFINI_VACUUM": 1, "CHIRPING_STEREO": 1}, "SKYMART_HYPER_VACUUM": {"SKYMART_TURBO_VACUUM": 1},
         "SKYMART_TURBO_VACUUM": {"SKYMART_VACUUM": 1}, "INFINI_VACUUM": {"SKYMART_HYPER_VACUUM": 1}}
# The buy gate IS the item's own "Requires Garden Level N" whenever the item as REGISTERED in this
# plugin carries one (garden's own items below, plus other modules' data read-only): a shop saying
# "Requires Garden VIII" above an item whose footer says "Requires Garden Level 6" read as a bug
# (PLAYTEST wave4 #14), and a gate below the use gate sold things you could not use yet. LEVEL_GATE
# only covers items with no garden use gate of their own (Copper Ring/Artifact, watering cans, books).
USE_GATE = {}


def _gates(obj):
    if isinstance(obj, dict):
        if isinstance(obj.get("id"), str) and isinstance(obj.get("requirements"), list):
            lv = [int(r.split(":")[1]) for r in obj["requirements"] if isinstance(r, str) and re.fullmatch(r"garden:\d+", r)]
            if lv and obj["id"] not in USE_GATE:
                USE_GATE[obj["id"]] = max(lv)
        # farming's crop tools carry their Garden level in the tool spec (extra.<spec>.gardenLevel) and add the
        # garden:N requirement at boot (FarmingModule.gardenRequirements, wave 5), so read it from there too
        if isinstance(obj.get("id"), str) and isinstance(obj.get("extra"), dict) and obj["id"] not in USE_GATE:
            for _spec in obj["extra"].values():
                if isinstance(_spec, dict) and isinstance(_spec.get("gardenLevel"), int) and _spec["gardenLevel"] > 0:
                    USE_GATE[obj["id"]] = _spec["gardenLevel"]
        for v in obj.values():
            _gates(v)
    elif isinstance(obj, list):
        for v in obj:
            _gates(v)


_gates(items)
_DATA = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")
for _d in sorted(os.listdir(_DATA)):
    if _d == "garden" or not os.path.isdir(os.path.join(_DATA, _d)):
        continue
    for _f in sorted(os.listdir(os.path.join(_DATA, _d))):
        if _f.endswith(".json"):
            with open(os.path.join(_DATA, _d, _f), encoding="utf-8") as _fh:
                _gates(json.load(_fh))
shop = []
for mid, v in mart.items():
    if mid.endswith("_BARN_SKIN") or mid.endswith("_GREENHOUSE_SKIN") or mid in ("PORTABLE_WASHER", "BUILDERS_RULER", "SKYMART_BROCHURE",
                                                                                   "LARGE_WALNUT", "YELLOW_BANDANA", "PLANT_DIAGNOSTICS_TOOL", "FARMING_TOOLKIT"):
        continue
    price, cur = v["price"], v["currency"]
    if cur == "gems":
        price, cur = price * 10, "copper"     # no paid currency
    iid = BOOK.get(mid, mid)
    cat = CAT.get(mid) or ("Pests" if ("VACUUM" in mid or "SPRAY" in mid or "REPELLENT" in mid) else
                           "Watering" if ("HYDRO" in mid or "AQUA" in mid) else
                           "Equipment" if mid.startswith(("LOTUS", "BLOSSOM", "COPPER_")) else
                           "Chips" if "CHIP" in mid else "Tools")
    shop.append({"item": iid, "price": price, "currency": cur, "category": cat,
                 "level": USE_GATE.get(iid, LEVEL_GATE.get(mid, 1)), "needs": NEEDS.get(mid, {})})
# Copper Talisman line: 5,000 / +20,000 / +50,000 Copper, each tier trading in the one below (garden_max.md
# corrections: 75,000 in total, not NEU's 25,000 / 750,000)
for s_ in shop:
    if s_["item"] == "COPPER_RING":
        s_["price"], s_["needs"] = 20000, {"COPPER_TALISMAN": 1}
    if s_["item"] == "COPPER_ARTIFACT":
        s_["price"], s_["needs"] = 50000, {"COPPER_RING": 1}
# garden-owned extras the research lists in SkyMart that the NEU catalogue lacks. The Pest Trap is NOT one: it is
# Pesthunter Phillip's, for 50 Pests (wiki; audit G7). Juicy / Salty Sprayonators: the sprayer below + its nozzle +
# 1,000 / 10,000 Copper (wiki "Juicy Sprayonator" / "Salty Sprayonator").
shop += [
    {"item": "COMPOST", "price": 2500, "currency": "coins", "category": "Farming", "level": 1, "needs": {}},
    {"item": "JUICY_SPRAYONATOR", "price": 1000, "currency": "copper", "category": "Pests", "level": 5,
     "needs": {"SPRAYONATOR": 1, "JUICY_NOZZLE": 1}},
    {"item": "SALTY_SPRAYONATOR", "price": 10000, "currency": "copper", "category": "Pests", "level": 5,
     "needs": {"JUICY_SPRAYONATOR": 1, "SALTY_NOZZLE": 1}},
]
# the blueprint normally comes from the Carpenter visitor; the market sells one too so a garden is
# never stuck waiting on one rare visitor (TUNE: not in the reference)
shop.append({"item": "GREENHOUSE_BLUEPRINT", "price": 2500, "currency": "copper", "category": "Farming", "level": 7, "needs": {}})
shop = [s for i, s in enumerate(shop) if s["item"] not in [x["item"] for x in shop[:i]]]
rn("place", "skymart", "SkyMart", "Copper Market")

# =====================================================================================
# NPC renames (garden-owned NPCs)
# =====================================================================================
rn("npc", "garden_market", "Sam (SkyMart)", "Marten the Grocer")
rn("npc", "garden_pestwarden", "Pesthunter Phillip", "Pestwarden Pell")
rn("mechanic", "pesthunter_bonus", "Pesthunter's Bonus (Phillip's pest turn-in)", "Pestwarden's Bonus")
rn("npc", "garden_gardener", "The Garden NPC in the Hub (Gardener)", "Gardener Rowena")
rn("npc", "garden_botanist", "Carpenter / Greenhouse helper", "Botanist Fern")
rn("item", "SKYMART_VACUUM", "SkyMart Vacuum", "Garden Vacuum")

os.makedirs(OUT, exist_ok=True)


def dump(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write("\n")


dump("garden.json", garden)
dump("visitors.json", visitors_file)
dump("items.json", items)
dump("mobs.json", mobs)
dump("recipes.json", recipes)
dump("skymart.json", shop)
dump("greenhouse.json", greenhouse)

# renames table (deduplicated)
seen = set()
rows = []
for kind, rid, ref, ours in renames:
    k = (rid, ref)
    if k in seen:
        continue
    seen.add(k)
    rows.append("| " + rid + " | " + ref + " | " + ours + " |")
with open(NAMES, "w", encoding="utf-8", newline="\n") as f:
    f.write("# garden — renames\n\n")
    f.write("Generated by `tools/gen/garden/gen_garden.py` (re-run it; do not hand-edit). Item ids keep the reference id;\n")
    f.write("visitor NPC ids are `garden_visitor_<id>`. Generic job titles (Bartender, Farmhand, Weaponsmith...) and\n")
    f.write("animal names (Fly, Cricket, Rat...) are kept. Names shared with other modules reuse their renames\n")
    f.write("(Anita -> Harvesta, Jerry -> Gerald, Tia -> Wren, Madame Goldsworth -> Curator Ottoline Vane, mayors).\n")
    f.write("No reference NPC skins or pest head textures are shipped: visitors and pests use vanilla bodies.\n\n")
    f.write("| id | reference name | our name |\n|---|---|---|\n")
    f.write("\n".join(rows) + "\n")

print("garden data: %d crops, %d plots, %d visitors, %d pests, %d items, %d mutations, %d shop entries, %d renames"
      % (len(crops_out), len(plots), len(visitors), len(mobs), len(items), len(mutations), len(shop), len(rows)))
