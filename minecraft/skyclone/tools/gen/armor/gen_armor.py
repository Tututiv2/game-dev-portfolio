# -*- coding: utf-8 -*-
"""
Generates the armor module's data from the research dumps.

    python tools/gen/armor/gen_armor.py          (run from the SkyblockClone folder)

Reads   research/data/items.json            (stats, tier, material, colour, skull, requirements)
        research/data/neu-repo/items/*.json (crafting / forge recipes)
        research/data/collections.json      (which collection tier unlocks which recipe)
Writes  plugin/src/main/resources/data/armor/items.json    (ItemDef[])
        plugin/src/main/resources/data/armor/recipes.json  (Recipe[])
        plugin/src/main/resources/data/armor/sets.json     (our own: set browser + bonus metadata)
        docs/names/armor.md                                 (rename table)

Ownership (CONTRACT §3): armor owns every armour piece EXCEPT
  - slayer armour (any SLAYER requirement)                     -> slayer
  - Kuudra lines (KUUDRA_COMPLETION requirement)                -> crimson
  - dungeon drops (dungeon_item / STARRED_*), except the four starter sets the armor brief names
    explicitly (Rotten, Skeleton Grunt, Heavy, Super Heavy)    -> dungeons (wave 2)
  - Rift-only gear (origin RIFT / rift_* stats)                 -> rift (wave 2)
  - UNOBTAINABLE / test items                                   -> nobody
"""
import json, os, re, sys, collections
sys.dont_write_bytecode = True

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bonus_text import BONUS, ITEM_LORE, FLAVOUR  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "armor")
NAMES = os.path.join(ROOT, "docs", "names", "armor.md")
STAT_JAVA = os.path.join(ROOT, "plugin", "src", "main", "java", "com", "skyclone", "api", "stat", "Stat.java")

SLOTS = ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")
# Reference stats with no Stat in this game that a bonus still reads (written to extra.otherStats).
OTHER_STATS = ("FEAR", "PRESSURE_RESISTANCE")
SHOPS = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "shops", "shops.json")
KEEP_DUNGEON_SETS = {"ROTTEN", "SKELETON_GRUNT", "HEAVY", "SUPER_HEAVY"}
# Trophy Fisher title level per reward tier (data/fishing/fishing.json "titles").
TROPHY_TITLE = {"BRONZE": 1, "SILVER": 2, "GOLD": 3, "DIAMOND": 4}

# ---- renames: reference proper nouns -> ours (substring replace on display names) ----------------
RENAME = [
    ("Arachne's", "Silkmother's"),
    ("of Divan", "of Varnas"),
    ("Yog ", "Lavaborn "),
    ("Charlie's Trousers", "Grovekeeper's Trousers"),
    ("Kelly's T-Shirt", "Ranger's T-Shirt"),
    ("Melody's Shoes", "Songbird Shoes"),
    ("Rosetta's", "Ironmonger's"),
    ("Cropie", "Cropling"),
    ("Fermento", "Fermenta"),
    ("Minos Hunter", "Labyrinth Hunter"),
    ("Kalhuiki Mask", "Tidecarver Mask"),
    ("Froggles", "Frog Goggles"),
    ("Moogma Leggings", "Moolten Leggings"),
]
# clickable abilities (handlers live in com.skyclone.mod.armor.BonusListener)
ITEM_ABILITIES = {
    "RANCHERS_BOOTS": [{"id": "armor_ranchers_speed", "name": "Farmer's Speed", "trigger": "LEFT_CLICK",
                        "lore": ["&7Hold these boots and left click to set", "&7a top &f✦ Speed &7you will never exceed",
                                 "&7while wearing them. &8(0 = no limit)"]}],
}

# set display names where the piece names do not share a clean prefix
SET_NAMES = {
    "SEYMOUR": "Tailor's Finery", "PARK": "Park Ranger Outfit", "MONSTER_HUNTER": "Monster Hunter",
    "LAVA_SEA_CREATURE": "Lava Sea Creature Gear", "MINER_OUTFIT": "Prospecting Outfit", "TANK_MINER": "Miner",
    "FARM_SUIT": "Farmhand", "FARM_ARMOR": "Haymaker", "MELON": "Tater", "PUMPKIN": "Sprout", "END": "Ender",
    "FAIRY": "Fairy's Outfit", "DIVER": "Diver's", "SALMON_NEW": "Salmon", "SALMON": "Salmon (Old)",
    "GENERALS_ARMOR_OF_THE_RESISTANCE": "General's Rising Sun", "ARMOR_OF_THE_RESISTANCE": "Rising Sun",
    "ARMOR_OF_YOG": "Lavaborn", "ARMOR_OF_MAGMA": "Armor of Magma", "DIVAN": "Varnas", "ARACHNE": "Silkmother's",
    "ROSETTA": "Ironmonger's", "CROPIE": "Cropling", "FERMENTO": "Fermenta", "MINOS_HUNTER": "Labyrinth Hunter",
    "HYDRA1": "Water Hydra", "GROWTH": "Growth", "FLAMEBREAKER": "Ember Ash", "FLAME_BREAKER": "Flamebreaker",
    "SHIMMERING_LIGHT": "Shimmering Light", "SNORKELING": "Snorkeling", "CANOPY": "Canopy", "LEAFLET": "Leaflet",
    "FIG": "Fig", "BIOHAZARD": "Biohazard", "GOLD": "Golden", "EMPEROR": "Emperor's Regalia",
    "CHEAP_TUXEDO": "Cheap Tuxedo", "FANCY_TUXEDO": "Fancy Tuxedo", "ELEGANT_TUXEDO": "Elegant Tuxedo",
    "GHAST": "Ghast", "FALLEN_STAR": "Fallen Star", "ZOMBIE": "Zombie", "SEA_WALKER": "Sea Walker",
}
for _n in range(1, 14):
    SET_NAMES["PERFECT_%d" % _n] = "Perfect Armor - Tier " + ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII"][_n - 1]

# legacy material -> modern Material name
DYE = ["WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN",
       "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"]
LEAVES = ["OAK_LEAVES", "SPRUCE_LEAVES", "BIRCH_LEAVES", "JUNGLE_LEAVES"]


def material(it):
    m = it["material"]
    dur = it.get("durability", 0) or 0
    if m == "SKULL_ITEM":
        return "PLAYER_HEAD"
    if m.startswith("GOLD_"):
        return "GOLDEN_" + m[5:]
    if m == "CARPET":
        return DYE[dur % 16] + "_CARPET"
    if m == "STAINED_CLAY":
        return DYE[dur % 16] + "_TERRACOTTA"
    if m == "STAINED_GLASS":
        return DYE[dur % 16] + "_STAINED_GLASS"
    if m == "LEAVES":
        return LEAVES[dur % 4]
    return m


# stat key mapping (API key upper-cased) -> our Stat enum name; anything not in Stat.java is dropped
STAT_ALIAS = {"WALK_SPEED": "SPEED", "CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE",
              "ABILITY_DAMAGE_PERCENT": "ABILITY_DAMAGE", "BONUS_PEST_CHANCE": "PEST_CHANCE",
              "TROPHY_FISH_CHANCE": "TROPHY_FISH_CHANCE"}


def stat_names():
    src = open(STAT_JAVA, encoding="utf-8").read()
    return set(re.findall(r"^\s{4}([A-Z_]+)\(\"", src, re.M))


SET_MERGE = {"EMPEROR_HEAD": "EMPEROR", "EMPEROR_ROBES": "EMPEROR", "EMPEROR_SHOES": "EMPEROR",
             "GHAST_HEAD": "GHAST", "WATER_HYDRA_HEAD": "HYDRA1", "FALLEN_STAR_HAT": "FALLEN_STAR"}


def set_key(it):
    k = set_key_raw(it)
    return SET_MERGE.get(k, k)


def set_key_raw(it):
    md = it.get("museum_data", {}).get("armor_set_donation_xp", {})
    if md:
        k = list(md)[0]
        m = re.match(r"PERFECT_TIER_(\d+)$", k)
        return "PERFECT_" + m.group(1) if m else k
    s = it["id"]
    s = re.sub(r"_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)(_\d+)?$", lambda mm: (mm.group(2) or ""), s)
    return s


def excluded(it, key):
    rq = {r["type"] for r in it.get("requirements", [])}
    if it.get("tier") == "UNOBTAINABLE" or it["id"].startswith("TEST_"):
        return "unobtainable"
    if "SLAYER" in rq:
        return "slayer"
    if "KUUDRA_COMPLETION" in rq:
        return "crimson"
    if it.get("origin") == "RIFT" or any(k.lower().startswith("rift_") for k in it.get("stats", {})):
        return "rift"
    if it["id"].startswith("STARRED_"):
        return "dungeons"
    if it.get("dungeon_item") and key not in KEEP_DUNGEON_SETS:
        return "dungeons"
    return None


def requirement_keys(it, notes):
    out = []
    for r in it.get("requirements", []):
        t = r["type"]
        if t == "SKILL":
            out.append("skill:%s:%d" % (r["skill"].lower(), r["level"]))
        elif t == "COLLECTION":
            out.append("collection:%s:%d" % (r["collection"], r["tier"]))
        elif t == "HEART_OF_THE_MOUNTAIN":
            out.append("hotm:%d" % r["tier"])
        elif t == "GARDEN_LEVEL":
            out.append("garden:%d" % r["level"])
        elif t == "ONE_OF":
            hot = [x for x in r.get("requirements", []) if x["type"] == "HEART_OF_THE_MOUNTAIN"]
            if hot:
                out.append("hotm:%d" % hot[0]["tier"])
        elif t == "TROPHY_FISHING":
            notes["trophyReq"] = "%s:%s" % (r.get("trophy_type", "").lower(), r.get("reward", "").lower())
            # Wave 3: fishing registers `trophy:TITLE:<level>`; lava Bronze..Diamond = title 1..4.
            title = TROPHY_TITLE.get(r.get("reward", "").upper())
            if r.get("trophy_type", "").upper() == "LAVA" and title:
                out.append("trophy:TITLE:%d" % title)
        elif t in ("DUNGEON_SKILL", "DUNGEON_TIER", "DUNGEON_BOSS_COLLECTION"):
            notes["catacombsReq"] = r.get("level", r.get("tier", 0))
            # Wave 3: dungeons registers `catacombs:<level>` and `dungeon_completion:<floor>`.
            if t == "DUNGEON_SKILL" and r.get("level"):
                out.append("catacombs:%d" % r["level"])
            elif t == "DUNGEON_TIER" and r.get("tier") is not None:
                out.append("dungeon_completion:%s" % ("E" if r["tier"] == 0 else "F%d" % r["tier"]))
    return out


def rename(name):
    for a, b in RENAME:
        name = name.replace(a, b)
    return name


def colour(c):
    if not c:
        return None
    try:
        r, g, b = [int(x) for x in c.split(",")]
        return "#%02X%02X%02X" % (r, g, b)
    except Exception:
        return None


def slot_of(it):
    # the API mislabels a few pieces (Fallen Star boots/leggings say CHESTPLATE): trust the id suffix
    m = re.search(r"_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)(_\d+)?$", it["id"])
    return m.group(1) if m else it.get("category")


def norm_cell(cell):
    """NEU 'RAW_FISH-1:8' -> 'RAW_FISH:1:8'; 'X:1' -> 'X:1'; amounts made integral."""
    if not cell:
        return ""
    cell = cell.strip()
    if ":" in cell:
        iid, amt = cell.rsplit(":", 1)
    else:
        iid, amt = cell, "1"
    iid = re.sub(r"-(\d+)$", r":\1", iid)
    try:
        a = int(float(amt))
    except ValueError:
        a = 1
    return "%s:%d" % (iid, a)


def build_extras(by_id):
    """
    Wave 5: non-armour items a set bonus of ours needs, which no other module registers.
    GREAT_SPOOK_STAFF: Spooky (Great Spook, full set) is "punch a player with the staff", and its Fear +10
    counts toward Fearsome. Stats/rarity from the reference API, ability text in our words (NEU lore numbers).
    """
    out = []
    ref = by_id.get("GREAT_SPOOK_STAFF")
    if ref:
        out.append({
            "id": "GREAT_SPOOK_STAFF", "name": ref["name"], "material": ref.get("material", "DEAD_BUSH"),
            "rarity": ref.get("tier", "LEGENDARY"), "category": "NONE",
            "tags": ["museum"] if ref.get("museum") else [],
            "abilities": [
                {"id": "armor_spooker", "name": "Spooker", "trigger": "LEFT_CLICK", "cooldown": 60,
                 "lore": ["&7Punch another player while wearing the", "&7full &5Great Spook &7set to give them a",
                          "&7fright they will not forget."]},
                {"id": "armor_spook", "name": "Spook", "trigger": "RIGHT_CLICK", "cooldown": 15,
                 "lore": ["&7Other players see you as a &6Dead Bush", "&7for a few seconds."]},
            ],
            "lore": ["&8Earned in the alchemist's trials."],   # Fear is drawn by armor's other-stats lore contributor
            "extra": {"refName": ref["name"], "otherStats": {"FEAR": ref.get("stats", {}).get("FEAR", 10)}},
        })
    return out


def main():
    known_stats = stat_names()
    items = json.load(open(os.path.join(DATA, "items.json"), encoding="utf-8"))["items"]
    by_id = {i["id"]: i for i in items}
    armor = [i for i in items if i.get("category") in SLOTS]

    # recipe display-name -> collection unlock key
    cols = json.load(open(os.path.join(DATA, "collections.json"), encoding="utf-8"))["collections"]
    unlock_by_name = {}
    for cat in cols.values():
        for cid, col in cat["items"].items():
            for t in col["tiers"]:
                for u in t["unlocks"]:
                    m = re.match(r"(.+?) Recipes?$", u.strip())
                    if m:
                        unlock_by_name.setdefault(m.group(1).strip(), "collection:%s:%d" % (cid, t["tier"]))

    excluded_count = collections.Counter()
    sets = collections.OrderedDict()
    for it in sorted(armor, key=lambda x: x["id"]):
        k = set_key(it)
        why = excluded(it, k)
        if why:
            excluded_count[why] += 1
            continue
        sets.setdefault(k, []).append(it)

    out_items, out_recipes, out_sets, renames = [], [], [], []
    dropped_stats = collections.Counter()
    skipped_recipes = []
    for key, pieces in sets.items():
        pieces.sort(key=lambda i: SLOTS.index(slot_of(i)))
        multi = len(pieces) >= 2 and len({slot_of(p) for p in pieces}) == len(pieces)
        set_id = key.lower() if multi else None
        bonuses = BONUS.get(key, [])
        museum_cat = None
        for p in pieces:
            museum_cat = museum_cat or p.get("museum_data", {}).get("category")
        set_pieces = []
        for it in pieces:
            notes = {}
            d = {"id": it["id"], "name": rename(it["name"]), "material": material(it),
                 "rarity": it.get("tier", "COMMON"), "category": slot_of(it)}
            if d["name"] != it["name"]:
                renames.append((it["id"], it["name"], d["name"]))
            stats = {}
            src = dict(it.get("stats", {}))
            for sk, vals in it.get("tiered_stats", {}).items():
                src.setdefault(sk, vals[0])  # TUNE: dungeon quality rolls -> lowest roll until dungeons owns these
            for sk, v in src.items():
                k2 = STAT_ALIAS.get(sk.upper(), sk.upper())
                if k2 == "RESPIRATION":
                    notes["respiration"] = v  # Wave 3: no Stat; BonusListener turns it into longer breath
                if k2 in OTHER_STATS:
                    # Wave 5: no Stat either; kept in the same shape accessories/equipment use
                    # (extra.otherStats) so Fear (Fearsome) and Pressure Resistance can be summed.
                    notes.setdefault("otherStats", {})[k2] = v
                if k2 in known_stats:
                    stats[k2] = stats.get(k2, 0) + v
                else:
                    dropped_stats[sk.upper()] += 1
            if stats:
                d["stats"] = stats
            col = colour(it.get("color"))
            if col:
                d["color"] = col
            if it.get("skin", {}).get("value"):
                d["skull"] = it["skin"]["value"]
            reqs = requirement_keys(it, notes)
            if reqs:
                d["requirements"] = reqs
            if it.get("npc_sell_price"):
                d["npcSell"] = it["npc_sell_price"]
            gems = [g["slot_type"] for g in it.get("gemstone_slots", [])]
            if gems:
                d["gemSlots"] = gems
            tags = ["enchantable", "recombable", "armor"]
            if it.get("soulbound"):
                tags.append("soulbound")
            if it.get("museum_data") or it.get("museum"):
                tags.append("museum")
            if it["id"] in ("LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
                            "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS",
                            "DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS",
                            "CHAINMAIL_HELMET", "CHAINMAIL_CHESTPLATE", "CHAINMAIL_LEGGINGS", "CHAINMAIL_BOOTS",
                            "GOLD_HELMET", "GOLD_CHESTPLATE", "GOLD_LEGGINGS", "GOLD_BOOTS", "NETHERITE_HELMET"):
                tags.append("vanilla")
            if it.get("dungeon_item"):
                d["dungeon"] = True
            d["tags"] = sorted(set(tags))
            # ---- text
            lore = []
            if key in FLAVOUR and FLAVOUR[key]:
                lore.append(FLAVOUR[key])
            if key.startswith("PERFECT_"):
                lore.append(FLAVOUR["PERFECT"])
            for kind, name, lines in bonuses + ITEM_LORE.get(it["id"], []):
                if kind == "FULL" and set_id and "setBonusName" not in d:
                    d["setBonusName"] = name + ("" if len(pieces) == 4 else " &7(%d/%d)" % (len(pieces), len(pieces)))
                    d["setBonusLore"] = list(lines)
                else:
                    hdr = {"TIERED": "&6Tiered Bonus: ", "PIECE": "&6Piece Bonus: ", "ABILITY": "&6Ability: ",
                           "FULL": "&6Full Set Bonus: " if set_id else "&6Bonus: "}[kind]
                    if lore:
                        lore.append("")
                    lore.append(hdr + name)
                    lore.extend(lines)
            if lore:
                d["lore"] = lore
            if it["id"] in ITEM_ABILITIES:
                d["abilities"] = ITEM_ABILITIES[it["id"]]
            if set_id:
                d["setId"] = set_id
            extra = {"refName": it["name"], "setKey": key}
            extra.update(notes)
            d["extra"] = extra
            out_items.append(d)
            set_pieces.append(it["id"])

            # ---- recipes
            p = os.path.join(DATA, "neu-repo", "items", it["id"] + ".json")
            if os.path.exists(p):
                n = json.load(open(p, encoding="utf-8"))
                rs = []
                if isinstance(n.get("recipe"), dict):
                    rs.append(dict(n["recipe"], type="crafting"))
                rs += [r for r in (n.get("recipes") or []) if isinstance(r, dict)]
                unlock = unlock_by_name.get(it["name"])
                for idx, r in enumerate(rs):
                    t = r.get("type", "crafting")
                    if t == "crafting":
                        grid = [norm_cell(r.get(c + str(n_), "")) for c in "ABC" for n_ in (1, 2, 3)]
                        if not any(grid):
                            continue
                        rec = {"id": "armor:%s#%d" % (it["id"], idx), "type": "CRAFTING", "result": it["id"],
                               "amount": int(r.get("count", 1) or 1), "grid": grid, "station": "Crafting Table"}
                    elif t == "forge":
                        rec = {"id": "armor:%s#%d" % (it["id"], idx), "type": "FORGE", "result": it["id"],
                               "amount": int(r.get("count", 1) or 1),
                               "inputs": [norm_cell(x) for x in r.get("inputs", [])],
                               "durationSeconds": int(r.get("duration", 0) or 0), "station": "Forge"}
                    else:
                        continue  # npc_shop / trade: the shops module owns merchants
                    cells = [c for c in (rec.get("grid") or []) + rec.get("inputs", []) if c]
                    bad = [c.rsplit(":", 1)[0] for c in cells if c.rsplit(":", 1)[0] not in by_id]
                    if bad:
                        skipped_recipes.append((it["id"], bad))
                        continue  # NEU placeholder ids (X/Y/Z) or ids the item registry does not know
                    if unlock:
                        rec["unlock"] = unlock
                    out_recipes.append(rec)

        # ---- set record
        if museum_cat is None:
            sk = [r for p in pieces for r in p.get("requirements", []) if r["type"] == "SKILL"]
            museum_cat = sk[0]["skill"] if sk else "MISC"
        if key in KEEP_DUNGEON_SETS:
            museum_cat = "DUNGEONEERING"
        if key in ("IRON", "DIAMOND", "GOLD", "LEATHER", "CHAINMAIL", "NETHERITE"):
            museum_cat = "VANILLA"
        if key in ("SEYMOUR", "EMPEROR"):
            museum_cat = "MISC"
        if key.startswith("PERFECT_"):
            museum_cat = "COMBAT"
        display = SET_NAMES.get(key)
        if not display:
            nm = rename(pieces[0]["name"])
            display = re.sub(r"\s+(Helmet|Chestplate|Leggings|Boots|Hat|Head|Mask|Cap|Tunic|Pants|Shoes|Sandals|Hood|Slippers|Trousers)$", "", nm) if multi else nm
        rar = max((p.get("tier", "COMMON") for p in pieces),
                  key=lambda t: ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL"].index(t) if t in
                  ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL"] else 0)
        out_sets.append({"id": key.lower(), "key": key, "name": display, "category": museum_cat,
                         "rarity": rar, "pieces": set_pieces, "isSet": bool(multi),
                         "bonuses": [{"kind": k_, "name": n_, "lines": l_} for k_, n_, l_ in bonuses]})

    link_sets(out_sets, out_recipes)

    os.makedirs(OUT, exist_ok=True)
    json.dump(out_items, open(os.path.join(OUT, "items.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(out_recipes, open(os.path.join(OUT, "recipes.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(out_sets, open(os.path.join(OUT, "sets.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    extras = build_extras(by_id)
    json.dump(extras, open(os.path.join(OUT, "extras.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("extras:", [e["id"] for e in extras])

    # ---- renames doc
    bonus_renames = [("bonus", "Odger's Blessing", "Angler's Favor"), ("bonus", "Arachne's Faithful", "Silkmother's Faithful"),
                     ("lore", "Diana's Mythological Ritual", "the Mythic Ritual"), ("lore", "Dante", "the Usurper"),
                     ("lore", "Frosty the Snow Cannon", "snow cannon"), ("lore", "Jerry's Workshop", "the Winter Workshop"),
                     ("lore", "Aspect of the End (Strong Blood)", "your teleporting blade"),
                     ("lore", "Aspect of the Dragons (Superior Blood)", "the dragon-breath ability of your blade"),
                     ("lore", "Yogs", "lava beasts"), ("lore", "Cropie / Squash / Fermento / Helianthus drops", "Cropling / Squash / Fermenta / Helianthus")]
    os.makedirs(os.path.dirname(NAMES), exist_ok=True)
    with open(NAMES, "w", encoding="utf-8") as f:
        f.write("# armor — renames\n\n")
        f.write("Generated by `tools/gen/armor/gen_armor.py` (item rows) plus the bonus/lore rows below.\n")
        f.write("Every set-bonus description is rewritten in our own words; the numbers are the reference game's.\n\n")
        f.write("| id | reference name | our name |\n|---|---|---|\n")
        for a, b, c in renames:
            f.write("| %s | %s | %s |\n" % (a, b, c))
        for a, b, c in bonus_renames:
            f.write("| (%s) | %s | %s |\n" % (a, b, c))

    print("items %d, recipes %d, sets %d (multi-piece %d), renames %d" % (
        len(out_items), len(out_recipes), len(out_sets), sum(1 for s in out_sets if s["isSet"]), len(renames)))
    print("excluded:", dict(excluded_count))
    print("dropped stat keys:", dict(dropped_stats))
    print("skipped recipes:", skipped_recipes)


def link_sets(out_sets, out_recipes):
    """
    Wave 5: where each set comes from and what it turns into, so the set browser can answer
    "does this upgrade into anything?" (Angler -> Backwater at the bayou scrapper, Snorkeling -> Diver's...).

    - "shops": every offer of one of the set's pieces in OUR shops module's data (data/shops/shops.json),
      i.e. what a player can really buy in this game, with that module's own (renamed) NPC name.
    - "upgradeFrom"/"upgradeInto": set ids linked by an armour piece of one set being an ingredient of a
      piece of another (our crafting/forge recipes, and shop offers that take a piece as payment).
    """
    piece_set = {p: s["id"] for s in out_sets for p in s["pieces"]}
    ups = collections.defaultdict(set)    # from -> into
    downs = collections.defaultdict(set)  # into -> from

    def link(ingredient, result):
        a, b = piece_set.get(ingredient), piece_set.get(result)
        if a and b and a != b:
            ups[a].add(b)
            downs[b].add(a)

    for r in out_recipes:
        for c in (r.get("grid") or []) + (r.get("inputs") or []):
            if c:
                link(c.rsplit(":", 1)[0], r["result"])

    offers = collections.defaultdict(list)
    shops = json.load(open(SHOPS, encoding="utf-8")) if os.path.exists(SHOPS) else []
    for shop in shops if isinstance(shops, list) else []:
        for e in shop.get("entries", []):
            sid = piece_set.get(e.get("item"))
            if not sid:
                continue
            cost = ["%s:%d" % (x["item"], int(x.get("amount", 1) or 1)) for x in e.get("items", []) if x.get("item")]
            for x in e.get("items", []):
                link(x.get("item"), e["item"])
            o = {"piece": e["item"], "shop": shop.get("id"), "npc": shop.get("name"), "island": shop.get("island"),
                 "coins": float(e.get("coins", 0) or 0), "items": cost}
            if shop.get("where"):
                o["where"] = shop["where"]
            if e.get("req"):
                o["req"] = e["req"]
            offers[sid].append(o)

    for s in out_sets:
        if offers.get(s["id"]):
            s["shops"] = offers[s["id"]]
        if downs.get(s["id"]):
            s["upgradeFrom"] = sorted(downs[s["id"]])
        if ups.get(s["id"]):
            s["upgradeInto"] = sorted(ups[s["id"]])
    print("set links: %d sets sold in shops, %d upgrade edges" % (len(offers), sum(len(v) for v in ups.values())))


if __name__ == "__main__":
    main()
