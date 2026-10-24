#!/usr/bin/env python3
"""Generate plugin/src/main/resources/data/collections/collections.json from the research data.

    python tools/gen/collections/gen_collections.py

Sources (all under research/data/):
  collections.json            90 collections, 790 tiers, thresholds + unlock strings (the API dump)
  items.json                  names -> ids (to turn "Enchanted Bread Recipe" into ENCHANTED_BREAD),
                              item materials / head textures for menu icons
  neu-repo/items/*.json       displayname + crafttext ("Requires: Wheat V") to pick the right id when a
                              name is ambiguous (BONZO_MASK vs STARRED_BONZO_MASK)
  ../../plugin/src/main/resources/data/core-items/vanilla_aliases.json
                              Material -> reference ids, so a collection also counts the modern id
                              (COCOA_BEANS counts for INK_SACK:3 when nobody registered INK_SACK:3)
Boss collections are not in the API dump; they are transcribed from research/02 section 3.8 below.

Reference-game proper nouns are renamed here (FALLBACK_NAMES, BOSSES) and the same table is written
to docs/names/collections.md by hand. At runtime the module prefers the registry's display name for
any item another module registered, so its rename always wins over ours.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "collections", "collections.json")
ALIASES = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "core-items", "vanilla_aliases.json")

SKILLS = ["FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "ENCHANTING", "ALCHEMY", "CARPENTRY",
          "TAMING", "RUNECRAFTING", "SOCIAL"]

ROMAN = [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"), (50, "L"), (40, "XL"),
         (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]


def roman(n):
    s = ""
    for v, r in ROMAN:
        while n >= v:
            s += r
            n -= v
    return s


def strip(s):
    s = re.sub(r"[§&][0-9a-fk-or]", "", s or "")
    return re.sub(r"[-]", "", s).strip()


# ---- categories (research 08 section 11.4 slot layout) ----------------------------------------
CATEGORIES = [
    {"id": "FARMING", "name": "Farming", "icon": "GOLDEN_HOE", "slot": 20},
    {"id": "MINING", "name": "Mining", "icon": "STONE_PICKAXE", "slot": 21},
    {"id": "COMBAT", "name": "Combat", "icon": "STONE_SWORD", "slot": 22},
    {"id": "FORAGING", "name": "Foraging", "icon": "JUNGLE_SAPLING", "slot": 23},
    {"id": "FISHING", "name": "Fishing", "icon": "FISHING_ROD", "slot": 24},
    {"id": "RIFT", "name": "Rift", "icon": "MYCELIUM", "slot": 25},
    {"id": "BOSS", "name": "Boss", "icon": "WITHER_SKELETON_SKULL", "slot": 31},
]

# Menu order inside each category (the genre's order; anything not listed is appended).
ORDER = {
    "FARMING": ["WHEAT", "CARROT_ITEM", "POTATO_ITEM", "PUMPKIN", "MELON", "SEEDS", "MUSHROOM_COLLECTION",
                "INK_SACK:3", "CACTUS", "SUGAR_CANE", "FEATHER", "LEATHER", "PORK", "RAW_CHICKEN", "MUTTON",
                "RABBIT", "NETHER_STALK", "DOUBLE_PLANT", "MOONFLOWER", "WILD_ROSE"],
    "MINING": ["COBBLESTONE", "COAL", "IRON_INGOT", "GOLD_INGOT", "DIAMOND", "INK_SACK:4", "EMERALD", "REDSTONE",
               "QUARTZ", "OBSIDIAN", "GLOWSTONE_DUST", "GRAVEL", "ICE", "NETHERRACK", "SAND", "SAND:1",
               "ENDER_STONE", "MYCEL", "MITHRIL_ORE", "HARD_STONE", "GEMSTONE_COLLECTION", "SULPHUR_ORE",
               "GLACITE", "UMBER", "TUNGSTEN"],
    "COMBAT": ["ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE", "SULPHUR", "ENDER_PEARL", "GHAST_TEAR",
               "SLIME_BALL", "BLAZE_ROD", "MAGMA_CREAM", "CHILI_PEPPER"],
    "FORAGING": ["LOG", "LOG:1", "LOG:2", "LOG_2:1", "LOG_2", "LOG:3", "MANGROVE_LOG", "FIG_LOG", "HELIX_LOG",
                 "TENDER_WOOD", "VINESAP", "LUSHLILAC", "SEA_LUMIES", "RUBY_VEILSHROOM", "HONEYCOMB"],
    "FISHING": ["RAW_FISH", "RAW_FISH:1", "RAW_FISH:3", "RAW_FISH:2", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS",
                "CLAY_BALL", "WATER_LILY", "INK_SACK", "SPONGE", "MAGMA_FISH", "LOTUS"],
    "RIFT": ["WILTED_BERBERIS", "CADUCOUS_STEM", "AGARICUS_CAP", "HALF_EATEN_CARROT", "HEMOVIBE", "METAL_HEART",
             "TIMITE"],
}

# Invented proper nouns of the reference game -> our names (only used when no module registered the
# item; the registry's name wins at runtime). Mirrored in docs/names/collections.md.
FALLBACK_NAMES = {
    "SEA_LUMIES": "Sea Glimmers",
    "RUBY_VEILSHROOM": "Ruby Hoodcap",
    "VINESAP": "Vine Resin",
    "LUSHLILAC": "Velvet Lilac",
    "HELIX_LOG": "Spiral Log",
    "GLACITE": "Rimestone",
    "MITHRIL_ORE": "Starsteel",
    "HEMOVIBE": "Pulseblood",
    "TIMITE": "Chronite",
    "METAL_HEART": "Clockwork Heart",
}

# Menu icons in MODERN material names (the dump uses 1.8 materials and custom item models).
ICON_OVERRIDE = {
    "INK_SACK:3": "COCOA_BEANS", "MOONFLOWER": "BLUE_ORCHID", "CARROT_ITEM": "CARROT", "DOUBLE_PLANT": "SUNFLOWER",
    "WILD_ROSE": "ROSE_BUSH", "RAW_CHICKEN": "CHICKEN", "SEEDS": "WHEAT_SEEDS", "MUSHROOM_COLLECTION": "RED_MUSHROOM",
    "NETHER_STALK": "NETHER_WART", "MELON": "MELON_SLICE", "POTATO_ITEM": "POTATO", "PORK": "PORKCHOP",
    "INK_SACK:4": "LAPIS_LAZULI", "UMBER": "BROWN_TERRACOTTA", "MYCEL": "MYCELIUM", "ENDER_STONE": "END_STONE",
    "TUNGSTEN": "GRAY_DYE", "HARD_STONE": "STONE", "MITHRIL_ORE": "PRISMARINE_CRYSTALS", "SAND:1": "RED_SAND",
    "GLACITE": "PACKED_ICE", "SULPHUR_ORE": "GLOWSTONE_DUST", "SULPHUR": "GUNPOWDER", "SEA_LUMIES": "SEA_PICKLE",
    "LOG_2": "ACACIA_LOG", "RUBY_VEILSHROOM": "CRIMSON_FUNGUS", "LOG:1": "SPRUCE_LOG", "VINESAP": "VINE",
    "LOG:3": "JUNGLE_LOG", "LOG:2": "BIRCH_LOG", "LUSHLILAC": "LILAC", "MANGROVE_LOG": "MANGROVE_LOG",
    "HELIX_LOG": "WARPED_STEM", "LOG": "OAK_LOG", "HONEYCOMB": "HONEYCOMB", "TENDER_WOOD": "STRIPPED_CHERRY_LOG",
    "LOG_2:1": "DARK_OAK_LOG", "WATER_LILY": "LILY_PAD", "INK_SACK": "INK_SAC", "RAW_FISH": "COD",
    "RAW_FISH:3": "PUFFERFISH", "LOTUS": "PINK_PETALS", "RAW_FISH:2": "TROPICAL_FISH", "RAW_FISH:1": "SALMON",
    "MAGMA_FISH": "COOKED_SALMON", "WILTED_BERBERIS": "DEAD_BUSH", "CADUCOUS_STEM": "TWISTING_VINES",
    "HEMOVIBE": "REDSTONE_ORE", "HALF_EATEN_CARROT": "CARROT", "QUARTZ": "QUARTZ",
}

# Extra ids that count toward a collection besides the collection id itself.
#   weight = how many collection units one item is worth.
# TUNE: the reference counts a Pristine-proc'd Flawed gemstone as 80 rough (its compaction value).
EXTRA_ALIASES = {
    "MUSHROOM_COLLECTION": {"RED_MUSHROOM": 1, "BROWN_MUSHROOM": 1},
    "IRON_INGOT": {"IRON_ORE": 1, "RAW_IRON": 1, "DEEPSLATE_IRON_ORE": 1},
    "GOLD_INGOT": {"GOLD_ORE": 1, "RAW_GOLD": 1, "DEEPSLATE_GOLD_ORE": 1},
    "SPONGE": {"WET_SPONGE": 1},
}
GEMS = ["RUBY", "AMBER", "SAPPHIRE", "JADE", "AMETHYST", "TOPAZ", "JASPER", "OPAL", "ONYX", "AQUAMARINE",
        "CITRINE", "PERIDOT"]
EXTRA_ALIASES["GEMSTONE_COLLECTION"] = {}
for g in GEMS:
    EXTRA_ALIASES["GEMSTONE_COLLECTION"]["ROUGH_%s_GEM" % g] = 1
    EXTRA_ALIASES["GEMSTONE_COLLECTION"]["FLAWED_%s_GEM" % g] = 80

# Vanilla aliases whose FIRST reference id is a collection but where counting would be wrong.
SKIP_VANILLA_ALIAS = {"CARVED_PUMPKIN", "ROSE_BUSH", "STRIPPED_MANGROVE_LOG", "BLUE_ORCHID"}

# Pets / enchanted books named by the reference game's invented nouns -> ours.
PET_NAMES = {"Mithril Golem": "Starsteel Golem", "Glacite Golem": "Rimestone Golem",
             "Precursor Drone": "Relic Drone"}
BOOK_NAMES = {"Transylvanian": "Bloodsong", "Habanero Tactics": "Pepper Tactics", "Tabasco": "Red Sauce",
              "Thunderlord": "Stormbringer"}

# Text rewards that need rewording (reference-game wording / proper nouns).
TEXT_REWRITE = {
    "Access to /bank (Requires Cookie Buff)": "Remote /bank access (while boosted)",
}

# ---- boss collections (research/02 section 3.8; not in the API dump) -----------------------------
# key, reference name, our name, icon, mob-id match tokens, master-mode tokens, tiers [(amount, reward
# reference name, our fallback name, sbxp)]
SBXP_DUNGEON = lambda i: 15 if i < 3 else 25  # noqa: E731  tiers I-III 15, IV+ 25 (sums to the 1,015 task max)
BOSSES = [
    ("BONZO", "Bonzo", "Grinko", "f1", [
        (25, "Red Nose", "Clown Nose"), (50, "Bonzo's Mask", "Grinko's Mask"),
        (100, "Golden Bonzo Head", "Golden Grinko Head"), (150, "Bonzo's Staff", "Grinko's Staff"),
        (250, "Recombobulator 3000", "Rarity Amplifier"), (1000, "Diamond Bonzo Head", "Diamond Grinko Head")]),
    ("SCARF", "Scarf", "Veilwright", "f2", [
        (25, "Red Scarf", "Red Scarf"), (50, "Scarf's Thesis", "Veilwright's Thesis"),
        (100, "Golden Scarf Head", "Golden Veilwright Head"), (150, "Adaptive Blade", "Adaptive Blade"),
        (250, "Recombobulator 3000", "Rarity Amplifier"), (1000, "Diamond Scarf Head", "Diamond Veilwright Head")]),
    ("THE_PROFESSOR", "The Professor", "The Lecturer", "f3", [
        (25, "Suspicious Vial", "Suspicious Vial"), (50, "Adaptive Leggings", "Adaptive Leggings"),
        (100, "Golden Professor Head", "Golden Lecturer Head"), (150, "Adaptive Chestplate", "Adaptive Chestplate"),
        (250, "Recombobulator 3000", "Rarity Amplifier"), (1000, "Diamond Professor Head", "Diamond Lecturer Head")]),
    ("THORN", "Thorn", "Bramblemaw", "f4", [
        (50, "Spirit Stone", "Spirit Stone"), (100, "Golden Thorn Head", "Golden Bramblemaw Head"),
        (150, "Spirit Shortbow", "Spirit Shortbow"), (250, "Recombobulator 3000", "Rarity Amplifier"),
        (400, "Spirit Boots", "Spirit Boots"), (1000, "Diamond Thorn Head", "Diamond Bramblemaw Head")]),
    ("LIVID", "Livid", "Lurid", "f5", [
        (50, "Dark Orb", "Dark Orb"), (100, "Golden Livid Head", "Golden Lurid Head"),
        (150, "Livid Dagger", "Lurid Dagger"), (250, "Recombobulator 3000", "Rarity Amplifier"),
        (500, "Last Breath", "Last Breath"), (750, "Shadow Assassin Chestplate", "Shadow Assassin Chestplate"),
        (1000, "Diamond Livid Head", "Diamond Lurid Head")]),
    ("SADAN", "Sadan", "Gorrak", "f6", [
        (50, "Giant Tooth", "Giant Tooth"), (100, "Golden Sadan Head", "Golden Gorrak Head"),
        (150, "Necromancer Lord Helmet", "Necromancer Lord Helmet"), (250, "Recombobulator 3000", "Rarity Amplifier"),
        (500, "Necromancer Lord Chestplate", "Necromancer Lord Chestplate"),
        (750, "Necromancer Sword", "Necromancer Sword"), (1000, "Diamond Sadan Head", "Diamond Gorrak Head")]),
    ("NECRON", "Necron", "Morvane", "f7", [
        (50, "Wither Blood", "Wither Blood"), (100, "Golden Necron Head", "Golden Morvane Head"),
        (150, "Wither Helmet", "Wither Helmet"), (250, "Recombobulator 3000", "Rarity Amplifier"),
        (500, "Wither Leggings", "Wither Leggings"), (750, "Wither Chestplate", "Wither Chestplate"),
        (1000, "Diamond Necron Head", "Diamond Morvane Head")]),
]
# Kuudra is "the Magma Leviathan" everywhere else (docs/names/kuudra.md; kuudra Request 8).
KUUDRA = ("KUUDRA", "Kuudra", "Magma Leviathan", [
    (10, ["Kuudra Pet", "Crimson Essence", "Kuudra Teeth"], ["Magma Leviathan Pet", "Crimson Essence", "Leviathan Teeth"], 10),
    (100, ["Kuudra Chunk", "Crimson Essence", "Kuudra Teeth"], ["Leviathan Chunk", "Crimson Essence", "Leviathan Teeth"], 15),
    (500, ["Meaty Kuudra Chunk", "Crimson Essence", "Kuudra Teeth"], ["Meaty Leviathan Chunk", "Crimson Essence", "Leviathan Teeth"], 20),
    (2000, ["Hardened Kuudra Chunk", "Crimson Essence", "Kuudra Teeth"], ["Hardened Leviathan Chunk", "Crimson Essence", "Leviathan Teeth"], 25),
    (5000, ["Enriched Kuudra Chunk", "Crimson Essence", "Kuudra Teeth"], ["Enriched Leviathan Chunk", "Crimson Essence", "Leviathan Teeth"], 30),
])
BOSS_ICONS = {"BONZO": "RED_DYE", "SCARF": "RED_BANNER", "THE_PROFESSOR": "BREWING_STAND", "THORN": "BONE",
              "LIVID": "ENDER_EYE", "SADAN": "IRON_BLOCK", "NECRON": "WITHER_SKELETON_SKULL",
              "KUUDRA": "MAGMA_BLOCK"}
# Kuudra tier tokens -> collection units per kill (research 02 section 3.8: Basic 1 ... Infernal 5)
KUUDRA_TIERS = {"basic": 1, "hot": 2, "burning": 3, "fiery": 4, "infernal": 5}


MINION_NAMES = {"Mithril": "Starsteel"}


def cross_check_research(out_cols, ref_name):
    """Every threshold must equal research/02 section 3.1.1 (the human-checked summary table)."""
    md = os.path.join(ROOT, "research", "02-skills-collections-minions-level.md")
    with open(md, encoding="utf-8") as f:
        text = f.read()
    start = text.index("### 3.1.1")
    end = text.index("### Full unlock tables")
    table = {}
    for line in text[start:end].splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 4 or not cells[2].isdigit():
            continue
        table[cells[0].lower()] = [int(x.replace(",", "")) for x in cells[3].split("/")]
    bad = 0
    checked = 0
    for c in out_cols:
        if c["category"] == "BOSS":
            continue
        name = ref_name.get(c["id"], c["name"]).lower()
        want = table.get(name)
        if want is None:
            print("  research table has no row for", c["id"], name)
            continue
        got = [t["amount"] for t in c["tiers"]]
        checked += 1
        if got != want:
            bad += 1
            print("  MISMATCH", c["id"], got, "research", want)
    print("research cross-check: %d collections compared, %d mismatches" % (checked, bad))
    if bad:
        sys.exit(1)


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    cols = load(os.path.join(DATA, "collections.json"))["collections"]
    items = {i["id"]: i for i in load(os.path.join(DATA, "items.json"))["items"]}
    aliases = load(ALIASES)

    # name -> [ids] from items.json and NEU, plus NEU crafttext per id
    by_name = {}
    craft = {}
    for i in items.values():
        by_name.setdefault(strip(i.get("name", "")).lower(), set()).add(i["id"])
    for f in glob.glob(os.path.join(DATA, "neu-repo", "items", "*.json")):
        try:
            n = load(f)
        except Exception:
            continue
        iid = n.get("internalname")
        if not iid:
            continue
        iid = iid.replace("-", ":") if re.match(r"^[A-Z_]+-\d+$", iid) else iid
        by_name.setdefault(strip(n.get("displayname", "")).lower(), set()).add(iid)
        craft[iid] = strip(n.get("crafttext", ""))

    def resolve(name, coll_name=None, tier=None):
        cands = by_name.get(name.lower())
        if not cands:
            return None
        cands = sorted(cands)
        if coll_name and tier:
            want = "requires: %s %s" % (coll_name.lower(), roman(tier).lower())
            hit = [c for c in cands if craft.get(c, "").lower() == want]
            if hit:
                return sorted(hit, key=len)[0]
        plain = [c for c in cands if not c.startswith("STARRED_")]
        return sorted(plain or cands, key=lambda c: (len(c), c))[0]

    # reverse vanilla aliases: collection id <- modern Material names
    rev = {}
    for mat, ids in aliases.items():
        if ids and mat not in SKIP_VANILLA_ALIAS:
            rev.setdefault(ids[0], []).append(mat)

    unresolved = []
    out_cols = []
    for cat in CATEGORIES:
        if cat["id"] == "BOSS":
            continue
        group = cols[cat["id"]]["items"]
        order = [c for c in ORDER[cat["id"]] if c in group] + [c for c in group if c not in ORDER[cat["id"]]]
        for cid in order:
            c = group[cid]
            ref_name = c["name"]
            it = items.get(cid, {})
            skin = None
            if isinstance(it.get("skin"), dict):
                skin = it["skin"].get("value")
            elif isinstance(it.get("skin"), str):
                skin = it["skin"]
            icon = ICON_OVERRIDE.get(cid)
            if icon is None:
                icon = "PLAYER_HEAD" if skin else it.get("material", "PAPER")
            if icon != "PLAYER_HEAD":
                skin_out = None
            else:
                skin_out = skin
            al = {}
            for m in rev.get(cid, []):
                if m != cid:
                    al[m] = 1
            al.update(EXTRA_ALIASES.get(cid, {}))
            tiers = []
            for t in c["tiers"]:
                sbxp = 0
                rewards = []
                for u in t["unlocks"]:
                    u = re.sub(r"[-]", "", u).strip()
                    m = re.match(r"^\+(\d+) SkyBlock XP$", u)
                    if m:
                        sbxp += int(m.group(1))
                        continue
                    m = re.match(r"^\+([\d,]+) (\w+) Experience$", u)
                    if m and m.group(2).upper() in SKILLS:
                        rewards.append({"type": "skillxp", "skill": m.group(2).upper(),
                                        "amount": int(m.group(1).replace(",", ""))})
                        continue
                    if m and m.group(2) == "Hunting":
                        continue  # this game has no Hunting skill; showing an XP reward nobody receives would lie
                    m = re.match(r"^(.+) Exp Discount \(-(\d+)%\)$", u)
                    if m:
                        ench = re.sub(r"[^a-z0-9]+", "_", m.group(1).lower()).strip("_")
                        rewards.append({"type": "discount", "enchant": ench, "label": BOOK_NAMES.get(m.group(1), m.group(1)),
                                        "percent": int(m.group(2))})
                        continue
                    m = re.match(r"^\+(\d+) +(Mining Fortune|Farming Fortune|Foraging Fortune)$", u)
                    if m:
                        rewards.append({"type": "stat", "stat": m.group(2).upper().replace(" ", "_"),
                                        "amount": int(m.group(1))})
                        continue
                    m = re.match(r"^\+(\d+) (\w+) Level Cap$", u)
                    if m and m.group(2).upper() in SKILLS:
                        rewards.append({"type": "skillcap", "skill": m.group(2).upper(), "amount": int(m.group(1))})
                        continue
                    m = re.match(r"^\[Lvl 1\] (.+) Recipe$", u)
                    if m:
                        pet = m.group(1)
                        rewards.append({"type": "pet", "pet": re.sub(r"[^A-Z0-9]+", "_", pet.upper()).strip("_"),
                                        "label": PET_NAMES.get(pet, pet)})
                        continue
                    m = re.match(r"^Enchanted Book \((.+) ([IVX]+)\) Recipe$", u)
                    if m:
                        ench = re.sub(r"[^a-z0-9]+", "_", m.group(1).lower()).strip("_")
                        rewards.append({"type": "book", "enchant": ench, "label": BOOK_NAMES.get(m.group(1), m.group(1)),
                                        "level": m.group(2)})
                        continue
                    m = re.match(r"^(.+) Potion Recipe$", u)
                    if m:
                        rewards.append({"type": "potion", "potion": re.sub(r"[^a-z0-9]+", "_", m.group(1).lower()).strip("_"),
                                        "label": m.group(1) + " Potion"})
                        continue
                    m = re.match(r"^(.+) Dwarven Forge Recipe$", u)
                    if m:
                        rid = resolve(m.group(1), ref_name, t["tier"])
                        if not rid:
                            unresolved.append((cid, t["tier"], u))
                        rewards.append({"type": "recipe", "item": rid, "label": m.group(1), "forge": True})
                        continue
                    m = re.match(r"^(.+) Minion Recipes$", u)
                    if m:
                        first = resolve(m.group(1) + " Minion I")
                        prefix = re.sub(r"_\d+$", "", first) if first else None
                        if not prefix:
                            unresolved.append((cid, t["tier"], u))
                        rewards.append({"type": "minion", "prefix": prefix,
                                        "label": MINION_NAMES.get(m.group(1), m.group(1)) + " Minion"})
                        continue
                    m = re.match(r"^(.+) Recipe$", u)
                    if m:
                        rid = resolve(m.group(1), ref_name, t["tier"])
                        if not rid:
                            unresolved.append((cid, t["tier"], u))
                        rewards.append({"type": "recipe", "item": rid, "label": m.group(1)})
                        continue
                    rewards.append({"type": "text", "label": TEXT_REWRITE.get(u, u)})
                tiers.append({"tier": t["tier"], "amount": t["amountRequired"], "sbxp": sbxp, "rewards": rewards})
            tiers.sort(key=lambda x: x["tier"])
            out_cols.append({
                "id": cid, "category": cat["id"], "name": FALLBACK_NAMES.get(cid, ref_name),
                "icon": icon, "skull": skin_out, "aliases": al, "tiers": tiers,
            })

    # ---- boss collections ----
    for key, ref, ours, floor, rows in BOSSES:
        tiers = []
        for i, (amount, ref_reward, our_reward) in enumerate(rows):
            rid = resolve(ref_reward)
            tiers.append({"tier": i + 1, "amount": amount, "sbxp": SBXP_DUNGEON(i),
                          "rewards": [{"type": "loot", "item": rid, "label": our_reward}]})
        out_cols.append({
            "id": "BOSS_" + key, "category": "BOSS", "name": ours, "icon": BOSS_ICONS[key], "skull": None,
            "aliases": {}, "tiers": tiers,
            "boss": {"match": [key.lower().replace("the_", ""), floor], "masterWeight": 2},
        })
    key, ref, ours, rows = KUUDRA
    tiers = []
    for i, (amount, refs, our_names, sbxp) in enumerate(rows):
        rewards = []
        for r, o in zip(refs, our_names):
            rewards.append({"type": "loot", "item": resolve(r), "label": o})
        tiers.append({"tier": i + 1, "amount": amount, "sbxp": sbxp, "rewards": rewards})
    out_cols.append({
        "id": "BOSS_" + key, "category": "BOSS", "name": ours, "icon": BOSS_ICONS[key], "skull": None,
        "aliases": {}, "tiers": tiers, "boss": {"match": [key.lower()], "tierWeights": KUUDRA_TIERS},
    })

    cross_check_research(out_cols, {cid: c["name"] for cat in cols.values() for cid, c in cat["items"].items()})

    doc = {
        "_generated": "tools/gen/collections/gen_collections.py from research/data/collections.json (+ research/02 3.8)",
        "categories": CATEGORIES,
        "collections": out_cols,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=1, ensure_ascii=False)
        f.write("\n")

    n = len([c for c in out_cols if c["category"] != "BOSS"])
    t = sum(len(c["tiers"]) for c in out_cols if c["category"] != "BOSS")
    print("collections: %d (+%d boss), tiers: %d, unresolved recipe names: %d" % (
        n, len(out_cols) - n, t, len(unresolved)))
    for u in unresolved:
        print("  unresolved:", u)
    if n != 90 or t != 790:
        print("EXPECTED 90 collections / 790 tiers", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
