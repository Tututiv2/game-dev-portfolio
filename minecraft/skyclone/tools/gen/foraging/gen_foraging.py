#!/usr/bin/env python3
"""Generate plugin/src/main/resources/data/foraging/*.json from the research data.

    py -3 tools/gen/foraging/gen_foraging.py

Numbers (damage, stats, rarities, sell prices, gem slots, requirements, recipes, mob levels / HP /
damage / XP / coins / drop chances) come out of research/data (items.json + the NEU repo). What is
typed here is OUR wording: item names for the reference's proper nouns, lore sentences, ability
text, mob names (see docs/names/foraging.md).

Outputs (data/foraging/):
  items.json    axes + the Park travel scrolls (ItemDef array)
  recipes.json  crafting recipes for the axes NEU knows a recipe for (Recipe array)
  mobs.json     the Howling Cave wolves (+ Moonmarsh creatures) (MobDef array)
  spawns.json   spawn rules (SpawnRule array)
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
NEU = os.path.join(DATA, "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "foraging")

COLOR = re.compile("§.")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


ITEMS = {i["id"]: i for i in load(os.path.join(DATA, "items.json"))["items"]}
COLL = load(os.path.join(DATA, "collections.json"))["collections"]
COLL_BY_NAME = {}
for cat in COLL.values():
    for cid, c in cat["items"].items():
        COLL_BY_NAME[c["name"].lower()] = cid

ROMAN = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5, "VI": 6, "VII": 7, "VIII": 8, "IX": 9, "X": 10,
         "XI": 11, "XII": 12}

# legacy material names in items.json -> 26.1.2 Material
MATERIAL = {"WOOD_AXE": "WOODEN_AXE", "GOLD_AXE": "GOLDEN_AXE", "EMPTY_MAP": "PAPER", "SKULL_ITEM": "PLAYER_HEAD"}

STAT_KEYS = {"strength": "STRENGTH", "sweep": "SWEEP", "foraging_fortune": "FORAGING_FORTUNE",
             "STRENGTH": "STRENGTH", "SWEEP": "SWEEP", "FORAGING_FORTUNE": "FORAGING_FORTUNE"}

THROW = {
    "id": "foraging_throw_axe", "name": "Throwing Axe", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
    "lore": ["&7While on a foraging island, hurl your", "&7axe at a tree up to &a24 &7blocks away.",
             "&7It fells &a50% &7of the logs a swing would."],
}

# id -> (our name or None = keep reference name, extra lore, abilities, extra tags, extra fields)
AXES = {
    "WOODEN_AXE": dict(ref="WOOD_AXE", vanilla=True, lore=["&7A crude axe. It still bites wood."]),
    "STONE_AXE": dict(vanilla=True, lore=["&7Heavier than wood and twice as stubborn."]),
    "GOLDEN_AXE": dict(ref="GOLD_AXE", vanilla=True, lore=["&7Soft metal, sharp edge, short life", "&7anywhere but here."]),
    "IRON_AXE": dict(vanilla=True, lore=["&7A dependable woodcutter's axe."]),
    "DIAMOND_AXE": dict(vanilla=True, lore=["&7Splits trunks like kindling."]),
    "ROOKIE_AXE": dict(lore=["&7Comes sharpened: gains &aEfficiency I", "&7the first time it fells a log."]),
    "PROMISING_AXE": dict(lore=["&7Learns as it works: gains &aEfficiency I", "&7after felling &a50 &7logs."],
                          extra={"growsAt": 50}),
    "SWEET_AXE": dict(lore=["&7Every log it fells has a &a20% &7chance", "&7to shake loose an &cApple&7."],
                      extra={"appleChance": 0.20}),
    "EFFICIENT_AXE": dict(lore=["&7Every log it fells also drops", "&a5 &7planks of the same wood."],
                          extra={"planks": 5}),
    "JUNGLE_AXE": dict(name="Sprucecutter", lore=[],
                       abilities=[THROW, {"id": "foraging_park_fortune_50", "name": "Park Regular", "trigger": "PASSIVE",
                                           "lore": ["&7Gain &6+50☘ Foraging Fortune &7while", "&7in The Park."]}],
                       extra={"parkFortune": 50}),
    "TREECAPITATOR_AXE": dict(name="Timberfeller", lore=[],
                              abilities=[THROW, {"id": "foraging_park_fortune_100", "name": "Park Devotee", "trigger": "PASSIVE",
                                                  "lore": ["&7Gain &6+100☘ Foraging Fortune &7while", "&7in The Park."]}],
                              extra={"parkFortune": 100}),
    "SCULPTORS_AXE": dict(lore=[], abilities=[{"id": "foraging_sculpt", "name": "Carve", "trigger": "RIGHT_CLICK",
                                               "mana": 0, "cooldown": 0,
                                               "lore": ["&7Right-click a log on your own island", "&7to carve it into a six-sided log."]}]),
    "SERIOUSLY_DAMAGED_AXE": dict(lore=["&7Chipped, bent and still better than", "&7nothing on the marsh."], abilities=[THROW]),
    "DECENT_AXE": dict(lore=["&7Not great. Not bad. Decent."], abilities=[THROW]),
    "FIG_AXE": dict(name="Fig Hatchet", lore=[], abilities=[
        {"id": "foraging_frenzy_2000", "name": "Frenzy", "trigger": "PASSIVE",
         "lore": ["&7Gains &2+1∮ Sweep &7for every &a2,000", "&7logs it cuts in the Moonmarsh.", "&8(max +20)"]}],
        extra={"frenzyEvery": 2000, "frenzyMax": 20}),
    "FIGSTONE_AXE": dict(name="Figstone Cleaver", lore=[], abilities=[
        {"id": "foraging_frenzy_10000", "name": "Greater Frenzy", "trigger": "PASSIVE",
         "lore": ["&7Gains &2+1∮ Sweep &7for every &a10,000", "&7logs it cuts in the Moonmarsh.", "&8(max +20)"]}],
        extra={"frenzyEvery": 10000, "frenzyMax": 20}),
    "HELIX_CHOPPER": dict(name="Spiral Chopper", lore=[], abilities=[
        {"id": "foraging_frenzy_20000", "name": "Grand Frenzy", "trigger": "PASSIVE",
         "lore": ["&7Gains &2+1∮ Sweep &7for every &a20,000", "&7logs it cuts in the Moonmarsh.", "&8(max +40)"]}],
        extra={"frenzyEvery": 20000, "frenzyMax": 40}),
}

SCROLLS = {
    "PARK_JUNGLE_TRAVEL_SCROLL": ("Travel Scroll to Jungle Island", "jungle", "Jungle Island"),
    "PARK_CAVE_TRAVEL_SCROLL": ("Travel Scroll to Howling Cave", "howl", "the Howling Cave"),
}


def neu(item_id):
    p = os.path.join(NEU, item_id + ".json")
    return load(p) if os.path.exists(p) else None


def cell(c):
    """NEU cell 'LOG-1:32' -> our 'LOG:1:32' (core: the part after the LAST ':' is the amount)."""
    if not c:
        return ""
    name, _, amt = c.rpartition(":")
    if not name:
        name, amt = c, "1"
    name = re.sub(r"-(\d+)$", r":\1", name)
    n = int(float(amt))
    return name if n == 1 and ":" not in name else f"{name}:{n}"


def unlock_from(crafttext):
    m = re.match(r"Requires:\s*(.+?)\s+([IVX]+)$", (crafttext or "").strip())
    if not m:
        return None
    cid = COLL_BY_NAME.get(m.group(1).lower())
    return f"collection:{cid}:{ROMAN[m.group(2)]}" if cid else None


def build_items():
    out, recipes = [], []
    for iid, spec in AXES.items():
        ref = ITEMS.get(spec.get("ref", iid)) or ITEMS.get(iid)
        if ref is None:
            print("missing in items.json:", iid, file=sys.stderr)
            continue
        d = {"id": iid, "name": spec.get("name") or ref["name"],
             "material": iid if spec.get("vanilla") else MATERIAL.get(ref["material"], ref["material"]),
             "rarity": ref.get("tier") or "COMMON", "category": "AXE"}
        stats = {}
        for k, v in (ref.get("stats") or {}).items():
            if k.lower() == "damage":
                d["damage"] = v
            elif k in STAT_KEYS:
                stats[STAT_KEYS[k]] = v
        if stats:
            d["stats"] = stats
        if spec.get("lore"):
            d["lore"] = spec["lore"]
        if spec.get("abilities"):
            d["abilities"] = spec["abilities"]
        reqs = []
        for r in ref.get("requirements") or []:
            if r.get("type") == "SKILL":
                reqs.append(f"skill:{r['skill'].lower()}:{r['level']}")
        if reqs:
            d["requirements"] = reqs
        if ref.get("npc_sell_price"):
            d["npcSell"] = ref["npc_sell_price"]
        gems = [g["slot_type"] for g in ref.get("gemstone_slots") or []]
        if gems:
            d["gemSlots"] = gems
        tags = ["enchantable", "reforgeable", "recombable", "foraging_axe"]
        if spec.get("vanilla"):
            tags.append("vanilla")
        if ref.get("museum") or ref.get("museum_data"):
            tags.append("museum")
        d["tags"] = sorted(tags)
        extra = {"owner": "foraging", "refName": ref["name"]}
        extra.update(spec.get("extra", {}))
        d["extra"] = extra
        out.append(d)

        n = None if spec.get("vanilla") else neu(iid)   # vanilla recipes are the crafting module's
        if n:
            rs = n.get("recipes") or ([n["recipe"]] if n.get("recipe") else [])
            for i, r in enumerate(rs):
                if r.get("type") not in (None, "crafting"):
                    continue
                grid = [cell(r.get(k, "")) for k in ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")]
                rec = {"id": f"foraging:{iid}#{i}", "type": "CRAFTING", "result": iid, "amount": int(r.get("count", 1)),
                       "grid": grid, "station": "Crafting Table"}
                u = unlock_from(n.get("crafttext"))
                if u:
                    rec["unlock"] = u
                recipes.append(rec)

    for iid, (name, warp, place) in SCROLLS.items():
        ref = ITEMS[iid]
        out.append({"id": iid, "name": name, "material": "PAPER", "rarity": ref.get("tier", "EPIC"),
                    "category": "TRAVEL_SCROLL", "maxStack": 1,
                    "lore": [f"&7Read it to unlock the &a/warp {warp}", f"&7destination, straight to {place}.",
                             "", "&8Consumed on use."],
                    "abilities": [{"id": "foraging_read_scroll", "name": "Read", "trigger": "RIGHT_CLICK", "mana": 0,
                                   "cooldown": 0, "lore": ["&7Unlock the warp for good."]}],
                    "tags": ["museum"], "npcSell": 0,
                    "extra": {"owner": "foraging", "refName": ref["name"], "warp": warp}})
    # wave 5: Pots of Honeycomb - right-click a Moonmarsh / canyon tree to lather it; a critter comes
    # after the pot's wait (NEU lore: 60 / 30 / 15 minutes, Behemoth at once). Requirements from items.json.
    for iid, (minutes, lure) in POTS.items():
        ref = ITEMS[iid]
        reqs = []
        for r in ref.get("requirements") or []:
            if r.get("type") == "COLLECTION":
                reqs.append(f"collection:{r['collection']}:{r['tier']}")
            elif r.get("type") == "SKILL":
                reqs.append(f"skill:{r['skill'].lower()}:{r['level']}")
        wait = "&aat once" if minutes == 0 else f"after &a{minutes}m"
        out.append({"id": iid, "name": ref["name"], "material": "HONEY_BOTTLE" if minutes else "HONEY_BLOCK",
                    "rarity": ref.get("tier", "UNCOMMON"), "category": "CONSUMABLE",
                    "lore": ["&7Smear it on a fig, mangrove or spiral", "&7tree to lather it with honeycomb.",
                             "", f"&7A critter comes for the honey {wait}&7.", "&7Which one depends on the tree.",
                             "", "&8Consumed on use."],
                    "abilities": [{"id": "foraging_lather", "name": "Lather", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                                   "lore": ["&7Right-click a tree log on the", "&2Moonmarsh &7or in the &6Torrent Canyon&7."]}],
                    "requirements": reqs, "npcSell": 0, "tags": [],
                    "extra": {"owner": "foraging", "refName": ref["name"], "latherMinutes": minutes, "lure": lure}})
        n = neu(iid)
        rs = (n.get("recipes") or ([n["recipe"]] if n.get("recipe") else [])) if n else []
        for i, r in enumerate(rs):
            if r.get("type") not in (None, "crafting"):
                continue
            grid = [cell(r.get(k, "")) for k in ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")]
            rec = {"id": f"foraging:{iid}#{i}", "type": "CRAFTING", "result": iid, "amount": int(r.get("count", 1)),
                   "grid": grid, "station": "Crafting Table"}
            if reqs and reqs[0].startswith("collection:"):
                rec["unlock"] = reqs[0]
            recipes.append(rec)
    # Behemoth has no recipe: it is a Canyon Starfall Contest prize (Contest.java)

    # wave 5 (second pass): Boosters (ref. the Foraging 2.0 boosters, "Combinable in Anvil" with foraging armor,
    # equipment or axes). Ours are applied at the Booster Bench (/boosters); the numbers are TUNE (Boosters.java).
    for iid, (kind, tier, what) in BOOSTERS.items():
        ref = ITEMS.get(iid, {})
        upg = tier == 2
        lore = ["&8Booster", ""]
        if upg:
            lore += [f"&7Apply to an item with a &fCommon {kind}", f"&fBooster &7to upgrade it to an", f"&aUncommon {kind} Booster&7,",
                     f"&7which gives {what} &7twice over."]
        else:
            lore += [f"&7Applies the &b{kind} &7booster to", "&7Foraging Armor, Equipment or an Axe.", "", f"&7Grants {what}&7."]
        lore += ["", "&eRight-click to open the Booster Bench!"]
        out.append({"id": iid, "name": ref.get("name", f"{'Uncommon' if upg else 'Common'} {kind} Booster"), "material": "PAPER",
                    "rarity": ref.get("tier", "UNCOMMON" if upg else "COMMON"), "category": "MATERIAL", "glow": upg,
                    "lore": lore, "npcSell": ref.get("npc_sell_price", 0), "tags": ["booster"],
                    "abilities": [{"id": "foraging_booster", "name": "Booster Bench", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 0,
                                   "lore": ["&7Hold the item to boost in your", "&7hand and pick this booster."]}],
                    "extra": {"owner": "foraging", "refName": ref.get("name", iid), "booster": kind.upper().replace(" ", "_"), "boosterTier": tier}})
    # Tree Gift rarities nobody else registers (research 07 4.4; NEU names)
    tf = ITEMS["TREE_THE_FISH"]
    out.append({"id": "TREE_THE_FISH", "name": "Bark the Fish", "material": "COD",   # ref. "Tree the Fish" (docs/names) "rarity": tf.get("tier", "SPECIAL"), "category": "MATERIAL",
                "maxStack": 1, "lore": ["&7Some fish grow on trees.", "", "&8A 1 in 2,000 Fig Tree Gift."], "npcSell": tf.get("npc_sell_price", 0),
                "tags": ["museum"], "extra": {"owner": "foraging", "refName": tf["name"]}})
    ff = ITEMS["FORESTS_FAVOR"]
    out.append({"id": "FORESTS_FAVOR", "name": ff["name"], "material": "PAPER", "rarity": ff.get("tier", "LEGENDARY"), "category": "MATERIAL",
                "maxStack": 1, "glow": True,
                "lore": ["&7Raises an axe's &dKarma &7by &a1&7.", "&7Can be applied once. &cRequires Karma V!", "",
                         "&8The will of the forest made solid.", "", "&eRight-click to open the Booster Bench!"],
                "npcSell": ff.get("npc_sell_price", 0), "tags": [],
                "abilities": [{"id": "foraging_booster", "name": "Booster Bench", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 0,
                               "lore": ["&7Hold a Karma V axe and pick this."]}],
                "extra": {"owner": "foraging", "refName": ff["name"], "forestsFavor": True}})
    return out, recipes


# Boosters: id -> (kind shown, tier 1 = common / 2 = uncommon upgrade, what it grants)
BOOSTERS = {
    "SWEEP_BOOSTER": ("Sweep", 1, "&2Sweep"), "SWEEP_BOOSTER_UNCOMMON": ("Sweep", 2, "&2Sweep"),
    "FORAGING_FORTUNE_BOOSTER": ("Foraging Fortune", 1, "&6Foraging Fortune"),
    "FORAGING_FORTUNE_BOOSTER_UNCOMMON": ("Foraging Fortune", 2, "&6Foraging Fortune"),
    "FORAGING_WISDOM_BOOSTER": ("Foraging Wisdom", 1, "&3Foraging Wisdom"),
    "FORAGING_WISDOM_BOOSTER_UNCOMMON": ("Foraging Wisdom", 2, "&3Foraging Wisdom"),
    "HUNTING_WISDOM_BOOSTER_COMMON": ("Hunting Wisdom", 1, "&3Hunting Wisdom"),
    "HUNTING_WISDOM_BOOSTER_UNCOMMON": ("Hunting Wisdom", 2, "&3Hunting Wisdom"),
    "FIGHTING_BOOSTER": ("Fighting", 1, "&cmore damage to foraging island creatures"),
    "FIGHTING_BOOSTER_UNCOMMON": ("Fighting", 2, "&cmore damage to foraging island creatures"),
}


# Pots of Honeycomb: id -> (minutes until a critter comes (0 = at once), how many critters it lures)
POTS = {
    "FUN_SIZED_POT_OF_HONEYCOMB": (60, 1),
    "FAMILY_SIZED_POT_OF_HONEYCOMB": (30, 1),
    "JUMBO_POT_OF_HONEYCOMB": (15, 1),
    "BEHEMOTH_POT_OF_HONEYCOMB": (0, 1),
}


# ---- mobs ---------------------------------------------------------------------------------

def pct(s):
    return float(str(s).rstrip("%")) / 100.0


MOBS = [
    # id, family, NEU id, our name, entity, research hp, research damage, extra
    ("park_pack_spirit", "pack_spirit", "PACK_SPIRIT_MONSTER", "Pack Wraith", "WOLF", 6000, 300, {}),
    ("park_howling_spirit", "howling_spirit", "HOWLING_SPIRIT_MONSTER", "Howling Wraith", "WOLF", 7000, 450, {}),
    ("park_soul_of_the_alpha", "soul_of_the_alpha", "SOUL_OF_THE_ALPHA_MONSTER", "Shade of the Alpha", "WOLF", 31150, 1425,
     {"trueDamage": True}),
]


def build_mobs():
    out = []
    for mid, fam, nid, name, ent, hp, dmg, extra in MOBS:
        n = neu(nid)
        r = n["recipes"][0]
        drops = []
        for dr in r["drops"]:
            item = cell(dr["id"]).rsplit(":", 1)[0] if re.search(r":\d+:\d+$", cell(dr["id"])) else cell(dr["id"])
            if item.startswith("ATTRIBUTE_SHARD"):
                continue           # hunting shards: no hunting system here
            drops.append({"item": item, "min": 1, "max": 2 if item.startswith("LOG") and mid.endswith("alpha") else 1,
                          "chance": round(pct(dr["chance"]), 7)})
        e = {"types": ["animal", "wolf"], "region": "howling_cave"}
        e.update(extra)
        out.append({"id": mid, "name": name, "level": r["level"], "entity": ent, "health": hp, "damage": dmg,
                    "speed": 1.15 if mid.endswith("alpha") else 1.05, "combatXp": r["combat_xp"], "coins": r["coins"],
                    "family": fam, "slayer": "wolf", "flags": ["aggressive"] + (["no_knockback"] if mid.endswith("alpha") else []),
                    "drops": drops, "extra": e, "behaviours": ["foraging_alpha"] if mid.endswith("alpha") else ["foraging_wolf"]})
    return out


# Moonmarsh mobs (wave 3). Health/damage from the NEU mob cards (lore), levels from NEU's bestiary
# (tidetot_10, seacurse_15, hydrospear_25, chillblade_31, chillshot_31); combat XP for the drowned is TUNE
# (their cards carry no combat_xp). Family = the bestiary group (Tidetot / Chill).
MARSH_MOBS = [
    # id, family, NEU id, our name, entity, level, hp, dmg, combatXp, coins, speed, equipment, types
    ("marsh_tidetot", "tidetot", "TIDETOT_MONSTER", "Silt Wretch", "DROWNED", 10, 4000, 300, 40, 20, 1.0, {},
     ["woodland", "aquatic", "undead"]),
    ("marsh_seacurse", "tidetot", "SEACURSE_MONSTER", "Brine Hex", "DROWNED", 15, 5000, 300, 50, 20, 1.05,
     {"HEAD": "PRISMARINE"}, ["woodland", "aquatic", "undead"]),
    ("marsh_hydrospear", "tidetot", "HYDROSPEAR_MONSTER", "Loch Lancer", "DROWNED", 25, 5000, 500, 70, 20, 1.05,
     {"HAND": "TRIDENT"}, ["woodland", "aquatic", "undead"]),
    ("marsh_chillblade", "chill", "CHILLBLADE_MONSTER", "Frostfang", "STRAY", 31, 400, 200, 35, 10, 1.1,
     {"HAND": "STONE_SWORD"}, ["woodland", "skeletal"]),
    ("marsh_chillshot", "chill", "CHILLSHOT_MONSTER", "Frostbow", "STRAY", 31, 400, 200, 35, 10, 1.0,
     {"HAND": "BOW"}, ["woodland", "skeletal"]),
]


def build_marsh_mobs():
    out = []
    for mid, fam, nid, name, ent, lvl, hp, dmg, cxp, coins, spd, equip, types in MARSH_MOBS:
        n = neu(nid)
        r = n["recipes"][0]
        drops = []
        for dr in r["drops"]:
            raw = dr["id"]
            if raw.startswith("ATTRIBUTE_SHARD") or ";" in raw.split(":")[0]:
                continue           # hunting shards and enchanted-book drops: not in this module's lane
            item, _, amt = raw.rpartition(":")
            if not item:
                item, amt = raw, "1"
            ch = pct(dr["chance"])
            whole = int(ch)        # 120% = one guaranteed + a 20% second
            k = max(1, int(float(amt)))
            if whole >= 1:
                drops.append({"item": item, "min": k * whole, "max": k * (whole + (1 if ch - whole > 0 else 0)), "chance": 1.0})
            else:
                drops.append({"item": item, "min": k, "max": k, "chance": round(ch, 7)})
        out.append({"id": mid, "name": name, "level": lvl, "entity": ent, "health": hp, "damage": dmg, "speed": spd,
                    "combatXp": cxp, "coins": coins, "family": fam, "flags": ["aggressive"], "equipment": equip,
                    "drops": drops, "extra": {"types": types, "region": "moonmarsh"}})
    return out


SPAWNS = [
    {"id": "park_pack_spirits", "island": "park", "area": "howling_cave", "mob": "park_pack_spirit", "max": 10, "respawnSeconds": 8},
    {"id": "park_howling_spirits", "island": "park", "area": "howling_cave.deep", "mob": "park_howling_spirit", "max": 6, "respawnSeconds": 10},
    {"id": "park_alpha", "island": "park", "anchors": ["spawn.alpha"], "mob": "park_soul_of_the_alpha", "max": 1, "respawnSeconds": 60},
    # the Moonmarsh (wave 3): drowned on the loch shores, the Chill in the old ruins
    {"id": "marsh_tidetots", "island": "galatea", "area": "mobs.loch", "mob": "marsh_tidetot", "max": 6, "respawnSeconds": 10},
    {"id": "marsh_seacurses", "island": "galatea", "area": "mobs.loch", "mob": "marsh_seacurse", "max": 4, "respawnSeconds": 12},
    {"id": "marsh_hydrospears", "island": "galatea", "area": "mobs.loch", "mob": "marsh_hydrospear", "max": 3, "respawnSeconds": 15},
    {"id": "marsh_chill_west", "island": "galatea", "area": "mobs.ruins.west", "mob": "marsh_chillblade", "max": 4, "respawnSeconds": 10},
    {"id": "marsh_chill_south", "island": "galatea", "area": "mobs.ruins.south", "mob": "marsh_chillshot", "max": 4, "respawnSeconds": 10},
    {"id": "marsh_chill_east_blade", "island": "galatea", "area": "mobs.ruins.east", "mob": "marsh_chillblade", "max": 2, "respawnSeconds": 10},
    {"id": "marsh_chill_east_shot", "island": "galatea", "area": "mobs.ruins.east", "mob": "marsh_chillshot", "max": 2, "respawnSeconds": 10},
]


# our NPC id -> the reference NPC whose SKIN (only the skin texture) we reuse
NPC_SKINS = {
    "park_ranger": "CHARLIE_NPC", "spruce_keeper": "JACK_NPC", "thicket_warden": "RYAN_NPC", "harpist": "MELODY_NPC",
    "ember_trials": "GUSTAVE_NPC", "jungle_elder": "MELANCHOLIC_VIKING_NPC", "axe_trader": "LUMBER_MERCHANT_NPC",
    "lumber_jack": "LUMBER_JACK_NPC", "hotf_keeper": "HINA_NPC", "marsh_axewright": "AMAURY_NPC",
    "sapwitch": "ROSEMARY_NPC", "herbalist": "AGATHA_NPC", "jade_dragon": "JADE_DRAGON_NPC",
}


def build_skins():
    out = {}
    for ours, ref in NPC_SKINS.items():
        n = neu(ref)
        if not n:
            continue
        m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', n.get("nbttag", ""))
        if m:
            out[ours] = m.group(1)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    items, recipes = build_items()
    mobs = build_mobs() + build_marsh_mobs()
    skins = build_skins()
    for name, data in (("items.json", items), ("recipes.json", recipes), ("mobs.json", mobs), ("spawns.json", SPAWNS), ("skins.json", skins)):
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
            f.write("\n")
    print(f"items {len(items)}  recipes {len(recipes)}  mobs {len(mobs)}  spawns {len(SPAWNS)}")


if __name__ == "__main__":
    main()
