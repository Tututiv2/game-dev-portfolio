"""Generate the storage module's data from the research dumps.

Outputs (plugin/src/main/resources/data/storage/):
  items.json      ItemDef array: every sack, every backpack (5 sizes x colours), the Jumbo Backpack
                  Upgrade and the sack lining (Pocket Sack-in-a-Sack).
  sacks.json      {families:[...], sacks:{ID:{family,capacity,tier}}, quiver:[arrow ids]}
                  family = what a sack HOLDS; several sack items (Beginner/Small/Medium/Large) share one.
  backpacks.json  {sizes:{ID:slots}, base:{SIZE:ID}, colours:{SIZE:{COLOUR:ID}}, dyes:{COLOUR:Material}}
  recipes.json    the crafting recipes of my items, copied from the crafting module's derived table
                  (data/crafting/skyblock_recipes.json, already converted to our unlock keys); StorageModule
                  registers each only when every ingredient exists at postEnable.
Also writes docs/names/storage.md and tools/gen/storage/report.txt.

Sources: research/data/neu-repo/constants/sacks.json (contents), research/data/neu-repo/items/*.json
(capacity from the lore numbers, rarity, crafttext), research/data/items.json (ids, rarity, skull
textures). Descriptions are written here, not copied.

Run:  py -3 tools/gen/storage/gen_storage.py
"""
import glob
import json
import os
import re
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
D = os.path.join(ROOT, "research", "data")
NEU = os.path.join(D, "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "storage")
NAMES = os.path.join(ROOT, "docs", "names", "storage.md")
REPORT = os.path.join(HERE, "report.txt")
CRAFT_RECIPES = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crafting", "skyblock_recipes.json")

report = []


def log(*a):
    report.append(" ".join(str(x) for x in a))


def load(p):
    with open(p, encoding="utf8") as f:
        return json.load(f)


def colon(i):
    m = re.match(r"^(.*)-(\d+)$", i)
    return m.group(1) + ":" + m.group(2) if m else i


def strip(s):
    return re.sub(r"§.", "", s or "")


ITEMS = {x["id"]: x for x in load(os.path.join(D, "items.json"))["items"]}


def neu(id_):
    p = os.path.join(NEU, id_.replace(":", "-") + ".json")
    return load(p) if os.path.exists(p) else None


def neu_skin(n):
    if not n:
        return None
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', n.get("nbttag", ""))
    return m.group(1) if m else None


def skin_of(id_):
    x = ITEMS.get(id_)
    if x and isinstance(x.get("skin"), dict) and x["skin"].get("value"):
        return x["skin"]["value"]
    if x and isinstance(x.get("skin"), str):
        return x["skin"]
    return neu_skin(neu(id_))


def rarity_of(id_):
    x = ITEMS.get(id_)
    if x and x.get("tier"):
        return x["tier"]
    n = neu(id_)
    if n and n.get("lore"):
        last = strip(n["lore"][-1]).split(" ")
        if last and last[0] in ("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "SPECIAL"):
            return last[0]
    return "COMMON"


def exists_ref(id_):
    """Is this a real reference-game id (items.json or NEU)?"""
    return id_ in ITEMS or neu(id_) is not None


# ---------------------------------------------------------------------------------------------
# Sack families. key = the family name in neu constants/sacks.json. Our display name, the field
# the lore names, the menu order, and the siblings that hold the same things (one per size).
# ---------------------------------------------------------------------------------------------
FAMILIES = OrderedDict([
    # key                     (fid,               our family name,        field text (lore),             sacks smallest -> largest)
    ("Agronomy",             ("AGRONOMY",        "Agronomy",             "&aFarming",                   ["BEGINNER_AGRONOMY_SACK", "SMALL_AGRONOMY_SACK", "MEDIUM_AGRONOMY_SACK", "LARGE_AGRONOMY_SACK"])),
    ("Enchanted Agronomy",   ("ENCH_AGRONOMY",   "Enchanted Agronomy",   "&aFarming &7(enchanted)",     ["LARGE_ENCHANTED_AGRONOMY_SACK"])),
    ("Husbandry",            ("HUSBANDRY",       "Husbandry",            "&aanimal farming",            ["BEGINNER_HUSBANDRY_SACK", "SMALL_HUSBANDRY_SACK", "MEDIUM_HUSBANDRY_SACK", "LARGE_HUSBANDRY_SACK"])),
    ("Enchanted Husbandry",  ("ENCH_HUSBANDRY",  "Enchanted Husbandry",  "&aanimal farming &7(enchanted)", ["LARGE_ENCHANTED_HUSBANDRY_SACK"])),
    ("Mining",               ("MINING",          "Mining",               "&bMining",                    ["BEGINNER_MINING_SACK", "SMALL_MINING_SACK", "MEDIUM_MINING_SACK", "LARGE_MINING_SACK"])),
    ("Enchanted Mining",     ("ENCH_MINING",     "Enchanted Mining",     "&bMining &7(enchanted)",      ["LARGE_ENCHANTED_MINING_SACK"])),
    ("Gemstone",             ("GEMSTONE",        "Gemstone",             "&dgemstone mining",           ["SMALL_GEMSTONE_SACK", "MEDIUM_GEMSTONE_SACK", "LARGE_GEMSTONE_SACK", "EXTRA_LARGE_GEMSTONE_SACK"])),
    ("Dwarven",              ("DWARVEN",         "Dwarven",              "&bthe dwarven mines",         ["DWARVEN_MINES_SACK"])),
    ("Crystal Hollows",      ("HOLLOWS",         "Hollows",              "&bthe Crystal Hollows",       ["CRYSTAL_HOLLOWS_SACK"])),
    ("Combat",               ("COMBAT",          "Combat",               "&cCombat",                    ["BEGINNER_COMBAT_SACK", "SMALL_COMBAT_SACK", "MEDIUM_COMBAT_SACK", "LARGE_COMBAT_SACK"])),
    ("Enchanted Combat",     ("ENCH_COMBAT",     "Enchanted Combat",     "&cCombat &7(enchanted)",      ["LARGE_ENCHANTED_COMBAT_SACK"])),
    ("Slayer",               ("SLAYER",          "Slayer",               "&cslayer bosses",             ["SMALL_SLAYER_SACK", "MEDIUM_SLAYER_SACK", "LARGE_SLAYER_SACK"])),
    ("Dragon",               ("DRAGON",          "Dragon",               "&5dragon fights",             ["SMALL_DRAGON_SACK", "MEDIUM_DRAGON_SACK", "LARGE_DRAGON_SACK"])),
    ("Nether",               ("NETHER",          "Nether",               "&cthe nether",          ["SMALL_NETHER_SACK", "MEDIUM_NETHER_SACK", "LARGE_NETHER_SACK"])),
    ("Dungeon",              ("DUNGEON",         "Dungeon",              "&cdungeon runs",              ["LARGE_DUNGEON_SACK"])),
    ("Rune",                 ("RUNE",            "Rune",                 "&5runes",                     ["SMALL_RUNES_SACK", "MEDIUM_RUNES_SACK", "LARGE_RUNES_SACK", "RUNE_SACK"])),
    ("Foraging",             ("FORAGING",        "Foraging",             "&2Foraging",                  ["BEGINNER_FORAGING_SACK", "SMALL_FORAGING_SACK", "MEDIUM_FORAGING_SACK", "LARGE_FORAGING_SACK"])),
    ("Enchanted Foraging",   ("ENCH_FORAGING",   "Enchanted Foraging",   "&2Foraging &7(enchanted)",    ["LARGE_ENCHANTED_FORAGING_SACK"])),
    ("Flower",               ("FLOWER",          "Flower",               "&eflower picking",            ["FLOWER_SACK"])),
    ("Fishing",              ("FISHING",         "Fishing",              "&3Fishing",                   ["BEGINNER_FISHING_SACK", "SMALL_FISHING_SACK", "MEDIUM_FISHING_SACK", "LARGE_FISHING_SACK"])),
    ("Enchanted Fishing",    ("ENCH_FISHING",    "Enchanted Fishing",    "&3Fishing &7(enchanted)",     ["LARGE_ENCHANTED_FISHING_SACK"])),
    ("Lava Fishing",         ("LAVA_FISHING",    "Lava Fishing",         "&6lava fishing",              ["SMALL_LAVA_FISHING_SACK", "MEDIUM_LAVA_FISHING_SACK", "LARGE_LAVA_FISHING_SACK"])),
    ("Bronze Trophy Fishing", ("TROPHY_BRONZE",  "Bronze Trophy",        "&6trophy fishing &7(bronze)", ["BRONZE_TROPHY_FISHING_SACK"])),
    ("Silver Trophy Fishing", ("TROPHY_SILVER",  "Silver Trophy",        "&6trophy fishing &7(silver)", ["SILVER_TROPHY_FISHING_SACK"])),
    ("Gold Trophy Fishing",  ("TROPHY_GOLD",     "Gold Trophy",          "&6trophy fishing &7(gold)",   ["GOLD_TROPHY_FISHING_SACK"])),
    ("Diamond Trophy Fishing", ("TROPHY_DIAMOND", "Diamond Trophy",      "&6trophy fishing &7(diamond)", ["DIAMOND_TROPHY_FISHING_SACK"])),
    ("Bronze Trophy Frog",   ("FROG_BRONZE",     "Bronze Trophy Frog",   "&2frog hunting &7(bronze)",   ["BRONZE_TROPHY_FROG_SACK"])),
    ("Silver Trophy Frog",   ("FROG_SILVER",     "Silver Trophy Frog",   "&2frog hunting &7(silver)",   ["SILVER_TROPHY_FROG_SACK"])),
    ("Garden",               ("GARDEN",          "Garden",               "&athe Garden",                ["GARDEN_SACK"])),
    ("Mutations",            ("MUTATIONS",       "Mutations",            "&acrop mutations",            ["SMALL_MUTATIONS_SACK", "MEDIUM_MUTATIONS_SACK", "LARGE_MUTATIONS_SACK"])),
    ("Spooky",               ("SPOOKY",          "Spooky",               "&6spooky season",       ["LARGE_CANDY_SACK"])),
    ("Winter",               ("WINTER",          "Winter",               "&bthe winter season",         ["LARGE_WINTER_SACK"])),
    ("Events",               ("EVENTS",          "Events",               "&eseasonal events",           ["LARGE_EVENTS_SACK"])),
    ("Witch",                ("WITCH",           "Witch",                "&5witchcraft",                ["WITCH_SACK"])),
])

# Display names: generic words stay; unique place names get our words (CONTRACT 1).
NAME_OVERRIDE = {
    "CRYSTAL_HOLLOWS_SACK": "Hollows Sack",
    "POCKET_SACK_IN_A_SACK": "Inner Sack Lining",
    "LARGE_CANDY_SACK": "Spooky Sack",
    "EXTRA_LARGE_GEMSTONE_SACK": "Extra Large Gemstone Sack",
    "SMALL_RUNES_SACK": "Small Rune Sack",
    "MEDIUM_RUNES_SACK": "Medium Rune Sack",
    "LARGE_RUNES_SACK": "Large Rune Sack",
    "RUNE_SACK": "Rune Sack",
}

# Lore text rewrites of place names that other modules rename (keeps lore consistent).
FIELD_TEXT_FIX = {"&bthe Crystal Hollows": "&bthe crystal caverns"}

TIER_ORDER = {"BEGINNER": 0, "SMALL": 1, "MEDIUM": 2, "LARGE": 3, "EXTRA_LARGE": 4}

# Capacities the lore does not state.  TUNE: Gold/Diamond trophy sacks follow the bronze/silver 512;
# the Extra Large Gemstone Sack continues the x5 step of Medium -> Large (512,000 -> 2,560,000).
CAP_FALLBACK = {"GOLD_TROPHY_FISHING_SACK": 512, "DIAMOND_TROPHY_FISHING_SACK": 512,
                "EXTRA_LARGE_GEMSTONE_SACK": 12800000, "SMALL_RUNES_SACK": 64, "MEDIUM_RUNES_SACK": 64,
                "LARGE_RUNES_SACK": 64}

# Gem tiers inside the Gemstone family: capacity counts ROUGH-equivalents per gem type
# ("Capacity: 128,000 of each gemstone type"). research 07: 80 rough -> 1 flawed, 80 flawed -> 1 fine.
GEM_WEIGHT = {"ROUGH": 1, "FLAWED": 80, "FINE": 6400}

QUIVER = ["ARROW", "REINFORCED_IRON_ARROW", "GOLD_TIPPED_ARROW", "REDSTONE_TIPPED_ARROW", "EMERALD_TIPPED_ARROW",
          "BOUNCY_ARROW", "ICY_ARROW", "ARMORSHRED_ARROW", "EXPLOSIVE_ARROW", "GLUE_ARROW", "NANSORB_ARROW",
          "MAGMA_ARROW", "FLINT_ARROW"]

COLOURS = ["WHITE", "LIGHT_GREY", "GREY", "BLACK", "BROWN", "RED", "ORANGE", "YELLOW", "LIME", "GREEN", "CYAN",
           "LIGHT_BLUE", "BLUE", "PURPLE", "MAGENTA", "PINK"]
DYES = {"WHITE": "WHITE_DYE", "LIGHT_GREY": "LIGHT_GRAY_DYE", "GREY": "GRAY_DYE", "BLACK": "BLACK_DYE",
        "BROWN": "BROWN_DYE", "RED": "RED_DYE", "ORANGE": "ORANGE_DYE", "YELLOW": "YELLOW_DYE", "LIME": "LIME_DYE",
        "GREEN": "GREEN_DYE", "CYAN": "CYAN_DYE", "LIGHT_BLUE": "LIGHT_BLUE_DYE", "BLUE": "BLUE_DYE",
        "PURPLE": "PURPLE_DYE", "MAGENTA": "MAGENTA_DYE", "PINK": "PINK_DYE"}
SIZES = OrderedDict([("SMALL", 9), ("MEDIUM", 18), ("LARGE", 27), ("GREATER", 36), ("JUMBO", 45)])
SIZE_RARITY = {"SMALL": "UNCOMMON", "MEDIUM": "RARE", "LARGE": "EPIC", "GREATER": "EPIC", "JUMBO": "LEGENDARY"}


def capacity(sid):
    n = neu(sid)
    if n:
        for l in n.get("lore", []):
            m = re.search(r"Capacity: ([\d,]+)", strip(l))
            if m:
                return int(m.group(1).replace(",", ""))
    if sid in CAP_FALLBACK:
        log("capacity fallback (TUNE)", sid, CAP_FALLBACK[sid])
        return CAP_FALLBACK[sid]
    log("NO CAPACITY", sid)
    return 0


def tier_of(sid):
    for t in ("EXTRA_LARGE", "BEGINNER", "SMALL", "MEDIUM", "LARGE"):
        if sid.startswith(t + "_"):
            return t
    return "LARGE"


def sack_name(sid):
    if sid in NAME_OVERRIDE:
        return NAME_OVERRIDE[sid]
    x = ITEMS.get(sid)
    if x:
        return x["name"]
    n = neu(sid)
    return strip(n["displayname"]) if n else sid.replace("_", " ").title()


def sack_lore(field, cap, unit):
    field = FIELD_TEXT_FIX.get(field, field)
    return [
        "&7Stores what you gather from " + field + "&7.",
        "&7While this sack is in your inventory or",
        "&7your &aSack of Sacks&7, matching pickups",
        "&7go straight into it.",
        "",
        "&7Capacity: &e" + "{:,}".format(cap) + " &7" + unit,
        "&8Sacks of the same kind add together.",
        "",
        "&eRight-click to open!",
    ]


def main():
    os.makedirs(OUT, exist_ok=True)
    raw = load(os.path.join(D, "neu-repo", "constants", "sacks.json"))["sacks"]
    raw = dict(raw)
    # Gold/Diamond trophy fishing sacks are not in the constants table: same fish, other tier suffix.
    if "Bronze Trophy Fishing" in raw:
        bronze = raw["Bronze Trophy Fishing"]["contents"]
        for t in ("GOLD", "DIAMOND"):
            key = t.title() + " Trophy Fishing"
            ids = [c.replace("_BRONZE", "_" + t) for c in bronze]
            raw[key] = {"item": t + "_TROPHY_FISHING_SACK", "contents": [c for c in ids if exists_ref(c)]}
    for k in raw:
        if k not in FAMILIES:
            log("UNMAPPED sack family in research (skipped):", k)

    items = []
    names = []
    families = []
    sacks = OrderedDict()
    all_contents = set()
    order = 0
    for key, (fid, fname, field, sids) in FAMILIES.items():
        src = raw.get(key, {"contents": []})
        contents = []
        weights = {}
        for c in src.get("contents", []):
            cid = colon(c)
            if len(cid) < 3:
                log("content id too short to be real (dropped):", fid, c)
                continue
            if not exists_ref(c) and not exists_ref(cid):
                log("content not a reference id (dropped):", fid, c)
                continue
            if cid in contents:
                continue
            contents.append(cid)
            all_contents.add(cid)
            if fid == "GEMSTONE":
                m = re.match(r"^(ROUGH|FLAWED|FINE)_([A-Z]+)_GEM$", cid)
                if m:
                    weights[cid] = {"group": m.group(2), "weight": GEM_WEIGHT[m.group(1)]}
        present = [s for s in sids if exists_ref(s)]
        for s in sids:
            if s not in present:
                log("sack id not in research (skipped):", s)
        unit = "per item"
        if fid == "GEMSTONE":
            unit = "per gem type (in rough gems)"
        elif fid.startswith("TROPHY") or fid.startswith("FROG"):
            unit = "per trophy"
        elif fid == "RUNE":
            unit = "per rune tier"
        fam = OrderedDict([("id", fid), ("name", fname), ("field", FIELD_TEXT_FIX.get(field, field)), ("order", order),
                           ("sacks", present), ("contents", contents)])
        if weights:
            fam["weights"] = weights
        if not contents:
            log("family has no contents:", fid)
        families.append(fam)
        order += 1
        for sid in present:
            cap = capacity(sid)
            sacks[sid] = OrderedDict([("family", fid), ("capacity", cap), ("tier", tier_of(sid))])
            name = sack_name(sid)
            ref_name = ITEMS.get(sid, {}).get("name") or strip((neu(sid) or {}).get("displayname", ""))
            if ref_name and ref_name != name:
                names.append((sid, ref_name, name))
            d = OrderedDict([
                ("id", sid), ("name", name), ("material", "PLAYER_HEAD"), ("rarity", rarity_of(sid)),
                ("category", "SACK"), ("lore", sack_lore(field, cap, unit)),
                ("tags", ["storage_sack", "no_trade", "no_ah", "no_reforge"]),
                ("extra", {"sack_family": fid, "sack_capacity": cap}),
            ])
            sk = skin_of(sid)
            if sk:
                d["skull"] = sk
            else:
                d["material"] = "BUNDLE"
                log("no skull texture, BUNDLE look:", sid)
            items.append(d)

    # ---- backpacks ---------------------------------------------------------------------------
    sizes = OrderedDict()
    base = OrderedDict()
    colours = OrderedDict()
    for size, slots in SIZES.items():
        bid = size + "_BACKPACK"
        if not exists_ref(bid):
            log("backpack missing:", bid)
            continue
        base[size] = bid
        sizes[bid] = slots
        colours[size] = OrderedDict()
        for col in COLOURS:
            cid = col + "_" + size + "_BACKPACK"
            if exists_ref(cid):
                colours[size][col] = cid
                sizes[cid] = slots
    for bid, slots in sizes.items():
        size = next(s for s in SIZES if bid.endswith(s + "_BACKPACK"))
        col = bid[: -len(size + "_BACKPACK")].rstrip("_")
        name = size.title() + " Backpack"
        lore = ["&7A bag with &a" + str(slots) + " &7slots. Put it in a",
                "&7Backpack Slot of your &aStorage &7to use it.", ""]
        if col:
            lore.insert(2, "&7Dyed " + col.replace("_", " ").lower() + ".")
        lore.append("&eRight-click to open your Storage!")
        d = OrderedDict([
            ("id", bid), ("name", name), ("material", "PLAYER_HEAD"),
            ("rarity", ITEMS.get(bid, {}).get("tier") or SIZE_RARITY[size]),
            ("category", "BACKPACK"), ("lore", lore), ("tags", ["storage_backpack", "no_reforge"]),
            ("extra", {"backpack_slots": slots, "backpack_size": size, "backpack_colour": col or "NONE"}),
        ])
        sk = skin_of(bid)
        if sk:
            d["skull"] = sk
        else:
            d["material"] = "BUNDLE"
            log("no skull texture, BUNDLE look:", bid)
        items.append(d)

    # ---- upgrades ----------------------------------------------------------------------------
    up = OrderedDict([
        ("id", "JUMBO_BACKPACK_UPGRADE"), ("name", "Jumbo Backpack Upgrade"), ("material", "PLAYER_HEAD"),
        ("rarity", "SPECIAL"), ("category", "MATERIAL"), ("maxStack", 1), ("glow", True),
        ("lore", ["&7Craft it together with a &5Greater", "&5Backpack &7to stretch it to &a45 &7slots."]),
        ("tags", ["storage_upgrade"]),
    ])
    if skin_of("JUMBO_BACKPACK_UPGRADE"):
        up["skull"] = skin_of("JUMBO_BACKPACK_UPGRADE")
    items.append(up)
    lin = OrderedDict([
        ("id", "POCKET_SACK_IN_A_SACK"), ("name", NAME_OVERRIDE["POCKET_SACK_IN_A_SACK"]), ("material", "PLAYER_HEAD"),
        ("rarity", "SPECIAL"), ("category", "MATERIAL"), ("maxStack", 1),
        ("lore", ["&7Stitch it into any sack from the", "&aSack of Sacks &7menu: that sack holds",
                  "&c+200% &7of its &coriginal &7capacity.", "", "&7A sack takes up to &a3 &7linings.", "",
                  "&8A spare pocket, sewn inside a sack."]),
        ("tags", ["storage_upgrade"]),
    ])
    if skin_of("POCKET_SACK_IN_A_SACK"):
        lin["skull"] = skin_of("POCKET_SACK_IN_A_SACK")
    items.append(lin)
    names.append(("POCKET_SACK_IN_A_SACK", "Pocket Sack-in-a-Sack", NAME_OVERRIDE["POCKET_SACK_IN_A_SACK"]))

    quiver = [q for q in QUIVER if exists_ref(q)]

    # ---- recipes (from crafting's converted table) -------------------------------------------
    mine = {i["id"] for i in items}
    recipes = []
    if os.path.exists(CRAFT_RECIPES):
        for r in load(CRAFT_RECIPES):
            if r.get("result") in mine and r.get("type", "CRAFTING") == "CRAFTING":
                r = dict(r)
                r["id"] = "storage:" + r["result"] + "#" + r["id"].split("#")[-1]
                r["station"] = "Crafting Table"
                recipes.append(r)
    else:
        log("crafting/skyblock_recipes.json missing: no recipes written")
    have = {r["result"] for r in recipes}
    for i in sorted(mine - have):
        log("no crafting recipe (shop / event / bits item):", i)

    # wave-5 integration (galatea request 6): the frog sacks need the Frog Collector title (NEU lore:
    # "Requires Novice / Adept Frog Collector"); galatea resolves `frogs:title:<n>`.
    SACK_REQ = {"BRONZE_TROPHY_FROG_SACK": ["frogs:title:1"], "SILVER_TROPHY_FROG_SACK": ["frogs:title:2"]}
    for it in items:
        if it.get("id") in SACK_REQ:
            it["requirements"] = list(SACK_REQ[it["id"]])

    with open(os.path.join(OUT, "items.json"), "w", encoding="utf8") as f:
        json.dump(items, f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "sacks.json"), "w", encoding="utf8") as f:
        json.dump(OrderedDict([("families", families), ("sacks", sacks), ("quiver", quiver)]), f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "backpacks.json"), "w", encoding="utf8") as f:
        json.dump(OrderedDict([("sizes", sizes), ("base", base), ("colours", colours), ("dyes", DYES)]), f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "recipes.json"), "w", encoding="utf8") as f:
        json.dump(recipes, f, ensure_ascii=False, indent=1)

    with open(NAMES, "w", encoding="utf8") as f:
        f.write("# storage - renames\n\nGenerated by `tools/gen/storage/gen_storage.py` (NAME_OVERRIDE) plus the hand-written\n"
                "menu/feature names below. Ids never change; only the display name does. Generic words (Sack,\n"
                "Backpack, Ender Chest, Wardrobe, Quiver, Agronomy, Husbandry...) stay generic.\n\n"
                "| id | reference name | our name |\n|---|---|---|\n")
        for sid, ref, ours in sorted(set(names)):
            f.write("| `" + sid + "` | " + ref + " | " + ours + " |\n")
        f.write("| (lore) | the Crystal Hollows | the crystal caverns |\n")
        f.write("| (lore) | the Crimson Isle | the nether |\n")
        f.write("| (lore) | the Spooky Festival | spooky season |\n")
        f.write("| (menu) | Personal Vault | Personal Vault (generic, kept) |\n")
        f.write("| (menu) | Sack of Sacks | Sack of Sacks (generic, kept) |\n")
        f.write("| (bits shop note) | Elizabeth | (not named here: the shops module owns the Community Shop) |\n")
        # wave 5 (hand-written; gen_backpack_skins.py holds the skin NAME_OVERRIDE)
        f.write("| `HAMBAGGER_BACKPACK` | Hambagger Backpack Skin | Burger Backpack Skin |\n")
        f.write("| (menu) | Equipment Wardrobe | Equipment Wardrobe (generic, kept) |\n")
        f.write("| (menu) | Link Equipment | ours: Link Equipment (armour slot N also swaps equipment slot N) |\n")
        f.write("| (attribute) | Sack Size | Sack Size (generic, kept; the shard itself is hunting's Sacklurker) |\n")

    log("items:", len(items), "sacks:", len(sacks), "families:", len(families), "backpacks:", len(sizes),
        "contents:", len(all_contents), "recipes:", len(recipes), "quiver ids:", len(quiver))
    with open(REPORT, "w", encoding="utf8") as f:
        f.write("\n".join(report) + "\n")
    print("\n".join(report[-1:]))


if __name__ == "__main__":
    main()
