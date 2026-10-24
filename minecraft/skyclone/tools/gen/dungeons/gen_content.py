#!/usr/bin/env python3
"""Dungeons part B (content) data generator.

Writes, into plugin/src/main/resources/data/dungeons/:
  gear.json          the dungeon gear this module owns (armour sets, masks, goggles, boss heads, scrolls, keys...)
  gear_recipes.json  fragment upgrades (starred gear), wither-class armour, from NEU recipes
  loot.json          reward-chest tables per floor (E, F1-F7, M1-M7): chest tiers, costs, essence, entries
  salvage.json       dungeon item -> essence returned by salvaging
  boss_mobs.json     every boss, phase add and summon of the seven boss floors

Sources: research/data/items.json (stats, colours, skins, upgrade costs, requirements),
research/data/neu-repo/items/*.json (recipes), neu-repo/constants/rngscore.json (per-floor loot pool and RNG meter
XP), essenceshops.json (the essence perks), research 04 sec 3.13/3.14 (boss HP/damage, chest costs, F7 contents).
Every proper noun is renamed (docs/names/dungeons.md); lore is written here in our own words, numbers kept.
Run:  python tools/gen/dungeons/gen_content.py
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "dungeons")
RES = os.path.join(ROOT, "research", "data")
NEU = os.path.join(RES, "neu-repo", "items")
DATA = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")

ITEMS = {x["id"]: x for x in json.load(open(os.path.join(RES, "items.json"), encoding="utf-8"))["items"]}
RNG = json.load(open(os.path.join(RES, "neu-repo", "constants", "rngscore.json"), encoding="utf-8"))["catacombs"]
ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]


def registered_ids():
    ids = set()
    for d in os.listdir(DATA):
        if d == "dungeons":
            continue
        p = os.path.join(DATA, d)
        if not os.path.isdir(p):
            continue
        for f in os.listdir(p):
            if not f.endswith(".json"):
                continue
            try:
                arr = json.load(open(os.path.join(p, f), encoding="utf-8"))
            except Exception:
                continue
            if isinstance(arr, list):
                for x in arr:
                    if isinstance(x, dict) and "id" in x and "material" in x:
                        ids.add(x["id"])
    return ids


OTHER = registered_ids()
ENCHANTS = {x["id"]: x.get("maxLevel", 10) for x in json.load(open(os.path.join(DATA, "enchanting", "enchants.json"), encoding="utf-8"))}

# ------------------------------------------------------------------------------------------------------
# names: reference -> ours (docs/names/dungeons.md). Boss names follow the collections module.
# ------------------------------------------------------------------------------------------------------
BOSS = {1: "Grinko", 2: "Veilwright", 3: "Lecturer", 4: "Bramblemaw", 5: "Lurid", 6: "Gorrak", 7: "Morvane"}
HEAD_KEYS = {"BONZO": 1, "SCARF": 2, "PROFESSOR": 3, "THORN": 4, "LIVID": 5, "SADAN": 6, "NECRON": 7}
BOSS_COLL = {1: "BOSS_BONZO", 2: "BOSS_SCARF", 3: "BOSS_THE_PROFESSOR", 4: "BOSS_THORN", 5: "BOSS_LIVID", 6: "BOSS_SADAN", 7: "BOSS_NECRON"}

STAT_KEYS = {"WALK_SPEED": "SPEED", "CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE",
             "ABILITY_DAMAGE_PERCENT": "ABILITY_DAMAGE", "BONUS_ATTACK_SPEED": "ATTACK_SPEED", "TRUE_DEFENSE": "TRUE_DEFENSE",
             "HEALTH": "HEALTH", "DEFENSE": "DEFENSE", "STRENGTH": "STRENGTH", "INTELLIGENCE": "INTELLIGENCE",
             "FEROCITY": "FEROCITY", "MAGIC_FIND": "MAGIC_FIND", "MENDING": "MENDING", "VITALITY": "VITALITY"}

# ------------------------------------------------------------------------------------------------------
# armour sets: set id -> (our name prefix, reference id prefix, set bonus name, set bonus lore, per-piece lore)
# ------------------------------------------------------------------------------------------------------
SETS = [
    ("zombie_soldier", "Zombie Soldier", "ZOMBIE_SOLDIER", "Phalanx",
     ["&7Every other player wearing the full", "&7set within &a30 &7blocks grants you", "&a+30❈ Defense&7."], []),
    ("zombie_knight", "Zombie Knight", "ZOMBIE_KNIGHT", "Oathbound",
     ["&7While you hold a &5Zombie Knight Sword&7,", "&7gain &a+50❈ Defense&7."], []),
    ("zombie_commander", "Zombie Commander", "ZOMBIE_COMMANDER", "Drill Sergeant",
     ["&7Every &a50 &7zombies you slay grant", "&c+5❤ Health &7while the set is worn.", "&7Caps at &c+500❤&7."], []),
    ("zombie_lord", "Zombie Lord", "ZOMBIE_LORD", "Lord of the Halls",
     ["&7Each minute you spend in a dungeon run", "&7grants &c+10❁ Strength &7and &a+10❈ Defense&7.", "&8(Up to 10 minutes)"], []),
    ("skeleton_soldier", "Skeleton Soldier", "SKELETON_SOLDIER", "Volley",
     ["&7Arrows deal an extra &a25% &7damage."], ["&7Arrows deal &a+5% &7damage."]),
    ("skeleton_master", "Skeleton Master", "SKELETON_MASTER", "Marksman",
     ["&7Arrows deal an extra &a25% &7damage."], ["&7Arrows deal &a+5% &7damage."]),
    ("skeleton_lord", "Skeleton Lord", "SKELETON_LORD", "Lord of the Halls",
     ["&7Each minute you spend in a dungeon run", "&7grants &c+5❁ Strength &7and &9+10☠ Crit Damage&7.", "&8(Up to 10 minutes)"], []),
    ("skeletor", "Bonelord", "SKELETOR", "Bone Collector",
     ["&7Every &a10 &7Bonelords you slay grant", "&c+1❁ Strength &7and &9+1☠ Crit Damage&7.", "&8(Up to +100 each)"],
     ["&7Each piece shortens the Bone Plating", "&7cooldown by &a1s&7."]),
    ("crypt_witherlord", "Crypt Witherlord", "CRYPT_WITHERLORD", None, [],
     ["&7Withered foes deal &a5% &7less damage", "&7to you."]),
    ("bouncy", "Bouncy", "BOUNCY", "Ricochet",
     ["&7Your arrows have a &a25% &7chance to", "&7bounce on to a second target."], []),
    ("adaptive", "Adaptive", "ADAPTIVE", "Ever Learning",
     ["&7Each piece gains &a+2% &7stats for every", "&a5 &7Catacombs levels you have."],
     ["&7Bends to your dungeon class:", "&cBerserk&7: &c+20❁ Strength", "&aHealer&7: &a+5☄ Mending&7, &c+40❤ Health",
      "&bMage&7: &b+50✎ Intelligence", "&7Tank: &a+30❈ Defense", "&6Archer&7: &9+5☣ Crit Chance&7, &9+15☠ Crit Damage"]),
    ("starred_adaptive", "Adaptive", "STARRED_ADAPTIVE", "Ever Learning",
     ["&7Each piece gains &a+2% &7stats for every", "&a5 &7Catacombs levels you have."],
     ["&7Bends to your dungeon class:", "&cBerserk&7: &c+20❁ Strength", "&aHealer&7: &a+5☄ Mending&7, &c+40❤ Health",
      "&bMage&7: &b+50✎ Intelligence", "&7Tank: &a+30❈ Defense", "&6Archer&7: &9+5☣ Crit Chance&7, &9+15☠ Crit Damage"]),
    ("shadow_assassin", "Shade Stalker", "SHADOW_ASSASSIN", "Silent Hunt",
     ["&7Each monster you slay in the Catacombs", "&7grants &c+1❁ Strength &7for the rest", "&7of the run."],
     ["&7Piece Bonus: &6Sinew", "&7After you teleport, gain &c+10❁ Strength", "&7for &a10s&7. &8(3s cooldown)"]),
    ("starred_shadow_assassin", "Shade Stalker", "STARRED_SHADOW_ASSASSIN", "Silent Hunt",
     ["&7Each monster you slay in the Catacombs", "&7grants &c+1❁ Strength &7for the rest", "&7of the run."],
     ["&7Piece Bonus: &6Sinew", "&7After you teleport, gain &c+10❁ Strength", "&7for &a10s&7. &8(3s cooldown)"]),
    ("necromancer_lord", "Necromancer Lord", "NECROMANCER_LORD", "Soul Whisper",
     ["&7In the Catacombs, deal &a+20% &7damage", "&7to undead monsters."], []),
    ("wither", "Wither", "WITHER", "Witherborn",
     ["&7Every &a30s &7a wither imp gathers at your", "&7side, flies to the nearest monster and", "&7bursts for magic damage."],
     ["&7Withered foes deal &a10% &7less damage", "&7to you."]),
    ("power_wither", "Morvane's", "POWER_WITHER", "Witherborn",
     ["&7Every &a30s &7a wither imp gathers at your", "&7side, flies to the nearest monster and", "&7bursts for magic damage."],
     ["&7Withered foes deal &a10% &7less damage", "&7to you."]),
    ("tank_wither", "Aurik's", "TANK_WITHER", "Witherborn",
     ["&7Every &a30s &7a wither imp gathers at your", "&7side, flies to the nearest monster and", "&7bursts for magic damage."],
     ["&7Withered foes deal &a10% &7less damage", "&7to you."]),
    ("speed_wither", "Gravax's", "SPEED_WITHER", "Witherborn",
     ["&7Every &a30s &7a wither imp gathers at your", "&7side, flies to the nearest monster and", "&7bursts for magic damage."],
     ["&7Withered foes deal &a10% &7less damage", "&7to you, and your arrows deal &a+5%&7."]),
    ("wise_wither", "Tempestra's", "WISE_WITHER", "Witherborn",
     ["&7Every &a30s &7a wither imp gathers at your", "&7side, flies to the nearest monster and", "&7bursts for magic damage."],
     ["&7Withered foes deal &a10% &7less damage", "&7to you."]),
]
PIECES = [("HELMET", "Helmet"), ("CHESTPLATE", "Chestplate"), ("LEGGINGS", "Leggings"), ("BOOTS", "Boots")]

# single pieces: id -> (our name, lore, extra dict, abilities)
SINGLES = {
    "BONZO_MASK": ("Grinko's Mask", ["&7A clown's mask that refuses to let its", "&7wearer die in the Catacombs."], {"cheat": "clownin"},
                   [{"id": "dungeons_clownin", "name": "Last Laugh", "trigger": "PASSIVE", "cooldown": 360,
                     "lore": ["&7Instead of dying in the Catacombs, gain", "&7damage immunity and &c+20❁ Strength", "&7for &a3s &7and heal to full. Higher",
                              "&7Catacombs levels shorten the cooldown", "&7and raise the Strength."]}]),
    "STARRED_BONZO_MASK": ("Grinko's Mask", ["&7A clown's mask that refuses to let its", "&7wearer die in the Catacombs."], {"cheat": "clownin"},
                           [{"id": "dungeons_clownin", "name": "Last Laugh", "trigger": "PASSIVE", "cooldown": 360,
                             "lore": ["&7Instead of dying in the Catacombs, gain", "&7damage immunity and &c+20❁ Strength", "&7for &a3s &7and heal to full. Higher",
                                      "&7Catacombs levels shorten the cooldown", "&7and raise the Strength."]}]),
    "SPIRIT_MASK": ("Spirit Mask", ["&7Woven from the breath of the grove."], {"cheat": "second_wind"},
                    [{"id": "dungeons_second_wind", "name": "Second Wind", "trigger": "PASSIVE", "cooldown": 30,
                      "lore": ["&7Instead of dying, gain &f+50✦ Speed", "&7and damage immunity for &a3s&7."]}]),
    "STARRED_SPIRIT_MASK": ("Spirit Mask", ["&7Woven from the breath of the grove."], {"cheat": "second_wind", "heal": 0.10},
                            [{"id": "dungeons_second_wind", "name": "Second Wind", "trigger": "PASSIVE", "cooldown": 30,
                              "lore": ["&7Instead of dying, gain &f+50✦ Speed", "&7and damage immunity for &a3s&7, and", "&7heal &c10%❤ &7over &a5s&7."]}]),
    "THORNS_BOOTS": ("Spirit Boots", ["&7Light enough to walk on the wind."], {},
                     [{"id": "dungeons_spirit_glide", "name": "Spirit Glide", "trigger": "SNEAK", "mana": 250, "cooldown": 60,
                       "lore": ["&7Sneak to rise and glide as a spirit", "&7for &a5s&7."]}]),
    "STARRED_THORNS_BOOTS": ("Spirit Boots", ["&7Light enough to walk on the wind."], {},
                             [{"id": "dungeons_spirit_glide", "name": "Spirit Glide", "trigger": "SNEAK", "mana": 250, "cooldown": 60,
                               "lore": ["&7Sneak to rise and glide as a spirit", "&7for &a5s&7."]}]),
    "MENDER_HELMET": ("Mender Helmet", ["&7Grants &a+50☄ Mending &7in dungeons."], {"dungeonStats": {"MENDING": 50}}, []),
    "MENDER_FEDORA": ("Mender Fedora", ["&7Grants &a+65☄ Mending &7and &4+65♨ Vitality", "&7in dungeons."], {"dungeonStats": {"MENDING": 65, "VITALITY": 65}}, []),
    "MENDER_CROWN": ("Mender Crown", ["&7Grants &a+80☄ Mending &7and &4+80♨ Vitality", "&7in dungeons."], {"dungeonStats": {"MENDING": 80, "VITALITY": 80}}, []),
    "DARK_GOGGLES": ("Dark Goggles", ["&7Lenses ground for seeing spells."], {}, []),
    "SHADOW_GOGGLES": ("Shadow Goggles", ["&7Lenses ground for seeing spells."], {}, []),
    "WITHER_GOGGLES": ("Wither Goggles", ["&7Lenses ground for seeing spells."], {}, []),
    "STONE_CHESTPLATE": ("Stone Chestplate", ["&7In dungeons, players within &a10 &7blocks", "&7of you take &a5% &7less damage &8(30 as Tank)&7."], {"aura": 0.05}, []),
    "METAL_CHESTPLATE": ("Metal Chestplate", ["&7In dungeons, players within &a10 &7blocks", "&7of you take &a8% &7less damage &8(30 as Tank)&7."], {"aura": 0.08}, []),
    "STEEL_CHESTPLATE": ("Steel Chestplate", ["&7In dungeons, players within &a10 &7blocks", "&7of you take &a10% &7less damage &8(30 as Tank)&7."], {"aura": 0.10}, []),
    "SNIPER_HELMET": ("Sniper Helmet", ["&7Arrows deal &a+1% &7damage for every", "&a2 &7blocks they flew past &a20&7."], {"sniper": True}, []),
    "PRECURSOR_EYE": ("Forerunner Eye", ["&7The eye still remembers how to see."], {},
                      [{"id": "dungeons_eye_beam", "name": "Eye Beam", "trigger": "SNEAK", "mana": 40, "cooldown": 1, "damage": 4000, "scaling": 0.2,
                        "lore": ["&7Hold Sneak to fire a beam ahead of you.", "&7Its damage doubles every second for &a5s",
                                 "&7and its mana cost grows by &b25%&7. Mana", "&7is only spent when the beam hits."]}]),
}

HEAD_LORE = {
    1: "A ringmaster who learned the dark arts to please his parents.",
    2: "Taught a whole class of undead, and graded them harshly.",
    3: "Lectured on guardians until the guardians lectured back.",
    4: "The grove grew teeth, and the teeth learned to roar.",
    5: "Nobody ever found out which one was the real one.",
    6: "Built giants out of stone because nobody else would listen.",
    7: "The last warden of the withered vault.",
}

MISC = [
    {"id": "DUNGEON_CHEST_KEY", "name": "Dungeon Chest Key", "material": "TRIPWIRE_HOOK", "rarity": "RARE", "category": "MATERIAL",
     "lore": ["&7Lets you open one &aextra &7reward chest", "&7at the end of a dungeon run.", "", "&8Consumed when used."],
     "tags": ["bazaar", "dungeon_key"], "npcSell": 5000},
    {"id": "KISMET_FEATHER", "name": "Fate Feather", "material": "FEATHER", "rarity": "RARE", "category": "MATERIAL", "glow": True,
     "lore": ["&7Keep it in your inventory and open a", "&7reward chest: you may &areroll &7that", "&7chest's contents once.", "",
              "&8One reroll per run."], "tags": ["bazaar"], "npcSell": 0},
    {"id": "INFINITE_SPIRIT_LEAP", "name": "Endless Soul Leap", "material": "ENDER_EYE", "rarity": "EPIC", "category": "MATERIAL",
     "lore": ["&7A Soul Leap that never runs out.", "", "&cDungeons only!"], "dungeon": True, "maxStack": 1,
     "abilities": [{"id": "dungeons_infinileap", "name": "Soul Leap", "trigger": "RIGHT_CLICK", "cooldown": 2,
                    "lore": ["&7Leap to any living teammate."]}]},
    {"id": "MAXOR_ENERGY_CRYSTAL", "name": "Energy Crystal", "material": "NETHER_STAR", "rarity": "SPECIAL", "category": "MATERIAL",
     "lore": ["&7Carry it to an empty podium.", "", "&cVanishes when the fight ends."], "tags": ["no_trade", "no_ah", "dungeon_only"],
     "maxStack": 1},
    {"id": "WITHER_RELIC_APEX", "name": "Verdant Relic", "material": "LIME_DYE", "rarity": "SPECIAL", "category": "MATERIAL",
     "lore": ["&7Place it in the &agreen &7cauldron."], "tags": ["no_trade", "no_ah", "dungeon_only"], "maxStack": 1, "glow": True},
    {"id": "WITHER_RELIC_FLAME", "name": "Ember Relic", "material": "ORANGE_DYE", "rarity": "SPECIAL", "category": "MATERIAL",
     "lore": ["&7Place it in the &6orange &7cauldron."], "tags": ["no_trade", "no_ah", "dungeon_only"], "maxStack": 1, "glow": True},
    {"id": "WITHER_RELIC_ICE", "name": "Frost Relic", "material": "LIGHT_BLUE_DYE", "rarity": "SPECIAL", "category": "MATERIAL",
     "lore": ["&7Place it in the &bblue &7cauldron."], "tags": ["no_trade", "no_ah", "dungeon_only"], "maxStack": 1, "glow": True},
    {"id": "WITHER_RELIC_POWER", "name": "Might Relic", "material": "RED_DYE", "rarity": "SPECIAL", "category": "MATERIAL",
     "lore": ["&7Place it in the &cred &7cauldron."], "tags": ["no_trade", "no_ah", "dungeon_only"], "maxStack": 1, "glow": True},
    {"id": "WITHER_RELIC_SOUL", "name": "Soul Relic", "material": "PURPLE_DYE", "rarity": "SPECIAL", "category": "MATERIAL",
     "lore": ["&7Place it in the &5purple &7cauldron."], "tags": ["no_trade", "no_ah", "dungeon_only"], "maxStack": 1, "glow": True},
]

SCROLLS = {
    "IMPLOSION_SCROLL": ("Implosion", ["&7Bind it to a &6Warden's Blade &7or one of", "&7its four refinements at the &dScroll",
                                       "&dAltar &8(/dungeons scrolls)&7.", "", "&6Ability: Implosion &e&lRIGHT CLICK",
                                       "&7Blast every monster within &a10 &7blocks", "&7for &c10,000 &7magic damage.", "&8Mana Cost: &3300",
                                       "&8Cooldown: &a10s"]),
    "SHADOW_WARP_SCROLL": ("Shadow Warp", ["&7Bind it to a &6Warden's Blade &7or one of", "&7its four refinements at the &dScroll",
                                           "&dAltar &8(/dungeons scrolls)&7.", "", "&6Ability: Shadow Warp &e&lRIGHT CLICK",
                                           "&7Step &a10 &7blocks ahead through shadow.", "&8Mana Cost: &3300", "&8Cooldown: &a10s"]),
    "WITHER_SHIELD_SCROLL": ("Wither Shield", ["&7Bind it to a &6Warden's Blade &7or one of", "&7its four refinements at the &dScroll",
                                               "&dAltar &8(/dungeons scrolls)&7.", "", "&6Ability: Wither Shield &e&lRIGHT CLICK",
                                               "&7Take &a10% &7less damage for &a5s &7and gain", "&7an absorption shield that grows with",
                                               "&7your Catacombs level.", "&8Mana Cost: &3150", "&8Cooldown: &a10s"]),
}


def stats_of(x):
    out = {}
    src = dict(x.get("stats") or {})
    for k, v in (x.get("tiered_stats") or {}).items():
        if isinstance(v, list) and v:
            src.setdefault(k, v[0])          # lowest quality roll (like the armour module)
    damage = 0
    for k, v in src.items():
        K = k.upper()
        if K == "DAMAGE":
            damage = v
            continue
        key = STAT_KEYS.get(K)
        if key:
            out[key] = v
    return out, damage


def colour(x):
    c = x.get("color")
    if not c:
        return None
    r, g, b = (int(v) for v in c.split(","))
    return "#%02X%02X%02X" % (r, g, b)


def skull(x):
    s = x.get("skin")
    if isinstance(s, dict):
        return s.get("value")
    if isinstance(s, str):
        m = re.search(r"'value': '([A-Za-z0-9+/=]+)'", s)
        if m:
            return m.group(1)
        if re.fullmatch(r"[A-Za-z0-9+/=]+", s):
            return s
    return None


def material(x):
    m = x.get("material", "STONE")
    if m == "SKULL_ITEM":
        return "PLAYER_HEAD"
    return {"GOLD_HELMET": "GOLDEN_HELMET", "GOLD_CHESTPLATE": "GOLDEN_CHESTPLATE", "GOLD_LEGGINGS": "GOLDEN_LEGGINGS",
            "GOLD_BOOTS": "GOLDEN_BOOTS"}.get(m, m)


def coll_tier(boss_n, amount):
    try:
        cats = json.load(open(os.path.join(DATA, "collections", "collections.json"), encoding="utf-8"))
        for c in (cats if isinstance(cats, list) else cats.get("collections", [])):
            if c.get("id") == BOSS_COLL[boss_n]:
                for t in c["tiers"]:
                    if t["amount"] >= amount:
                        return t["tier"]
    except Exception:
        pass
    return 6


def requirements(x):
    out = []
    for r in x.get("requirements") or []:
        t = r.get("type")
        if t == "DUNGEON_TIER":
            out.append("dungeon_completion:F%d:1" % r["tier"])
        elif t == "DUNGEON_SKILL":
            out.append("catacombs:%d" % r["level"])
        elif t == "DUNGEON_BOSS_COLLECTION":
            out.append("collection:%s:%d" % (BOSS_COLL[r["tier"]], coll_tier(r["tier"], r.get("amount", 1000))))
    return out


def upgrade_costs(x):
    out = []
    for star in x.get("upgrade_costs") or []:
        out.append([{"essence": c.get("essence_type"), "amount": c.get("amount")} if c.get("type") == "ESSENCE"
                    else {"item": c.get("item_id"), "amount": c.get("amount")} for c in star])
    return out


def base_def(rid, name, category, lore, set_id=None, abilities=None, extra=None):
    x = ITEMS[rid]
    st, dmg = stats_of(x)
    d = {"id": rid, "name": name, "material": material(x), "rarity": x.get("tier", "COMMON") if x.get("tier") not in (None, "UNOBTAINABLE") else "COMMON",
         "category": category, "stats": st, "lore": list(lore), "dungeon": True,
         "tags": ["reforgeable", "enchantable", "recombable", "museum", "dungeon_gear"],
         "requirements": requirements(x), "npcSell": x.get("npc_sell_price", 0) or 0}
    if dmg:
        d["damage"] = dmg
    c = colour(x)
    if c:
        d["color"] = c
    s = skull(x)
    if s and d["material"] == "PLAYER_HEAD":
        d["skull"] = s
    slots = [g.get("slot_type") for g in (x.get("gemstone_slots") or []) if g.get("slot_type")]
    if slots:
        d["gemSlots"] = slots
    e = {"src": "dungeons", "refName": x.get("name")}
    uc = upgrade_costs(x)
    if uc:
        e["upgrade_costs"] = uc
    if extra:
        e.update(extra)
    d["extra"] = e
    if set_id:
        d["setId"] = set_id
    if abilities:
        d["abilities"] = abilities
    if x.get("soulbound"):
        d["tags"].append("soulbound")
    return d


def gear():
    out = []
    for (sid, prefix, rid, bonus, bonus_lore, piece_lore) in SETS:
        for (suffix, word) in PIECES:
            iid = rid + "_" + suffix
            if iid not in ITEMS:
                continue
            cat = suffix
            lore = list(piece_lore)
            d = base_def(iid, "%s %s" % (prefix, word), cat, lore, sid if bonus else None)
            if bonus:
                d["setBonusName"] = bonus
                d["setBonusLore"] = list(bonus_lore)
            out.append(d)
    for iid, (name, lore, extra, abil) in SINGLES.items():
        x = ITEMS[iid]
        out.append(base_def(iid, name, x.get("category", "HELMET"), lore, None, abil, extra))
    for key, n in HEAD_KEYS.items():
        for kind, word in (("GOLD", "Golden"), ("DIAMOND", "Diamond")):
            iid = "%s_%s_HEAD" % (kind, key)
            boss = BOSS[n] if n != 3 else "Lecturer"
            d = base_def(iid, "%s %s Head" % (word, boss), "HELMET",
                         ["&7Earned by defeating %s many times." % (BOSS[n] if n != 3 else "the Lecturer"),
                          "&7Its stats are &adoubled &7on &cFloor %s&7." % ROMAN[n], "", "&8" + HEAD_LORE[n]],
                         None, None, {"bossHead": n})
            d["tags"] = ["reforgeable", "museum", "soulbound", "dungeon_gear"]
            out.append(d)
    for iid, (name, lore) in SCROLLS.items():
        out.append({"id": iid, "name": name, "material": "PAPER", "rarity": "EPIC", "category": "MATERIAL", "glow": True,
                    "maxStack": 1, "lore": lore, "dungeon": True, "tags": ["wither_scroll"], "npcSell": 1,
                    "extra": {"src": "dungeons", "scroll": iid.replace("_SCROLL", "").lower()}})
    for m in MISC:
        d = dict(m)
        d.setdefault("extra", {})["src"] = "dungeons"
        out.append(d)
    return out


# ------------------------------------------------------------------------------------------------------
# recipes: every NEU 3x3 recipe whose result is ours (starred = fragment upgrades, wither class armour)
# ------------------------------------------------------------------------------------------------------
def recipes(owned):
    out = []
    slots = ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]
    for iid in sorted(owned):
        p = os.path.join(NEU, iid + ".json")
        if not os.path.exists(p):
            continue
        r = json.load(open(p, encoding="utf-8")).get("recipe")
        if not isinstance(r, dict) or not any(r.get(s) for s in slots):
            continue
        grid = []
        for s in slots:
            c = r.get(s) or ""
            if c and c.endswith(":1"):
                c = c[:-2] + ":1"
            grid.append(c)
        req = owned[iid].get("requirements") or []
        out.append({"id": "dungeons:%s#0" % iid, "type": "CRAFTING", "result": iid, "amount": 1, "grid": grid,
                    "station": "Crafting Table", "unlock": req[0] if req else None})
    return out


# ------------------------------------------------------------------------------------------------------
# loot tables
# ------------------------------------------------------------------------------------------------------
CHESTS = ["WOOD", "GOLD", "DIAMOND", "EMERALD", "OBSIDIAN", "BEDROCK"]
# research 04 sec 3.14: Gold 25k (F1) -> 100k (F7), Diamond 50k -> 250k, Emerald 100k -> 500k, Obsidian 250k -> 1M,
# Bedrock 2M (F5-F7). Floors in between are linear (TUNE: research only gives the ends).
BASE_RANGE = {"WOOD": (0, 0), "GOLD": (25000, 100000), "DIAMOND": (50000, 250000), "EMERALD": (100000, 500000),
              "OBSIDIAN": (250000, 1000000), "BEDROCK": (2000000, 2000000)}
# Follow-up 2026-10-06: the wiki floor pages price Diamond/Emerald/Obsidian on F1 (25k/50k/100k/250k, WIKI_BASE) and
# F2, so those chests exist from F1; research 04's "F2+/F3+/F4+" were (?) guesses. Bedrock stays F5+ ([W]).
# The Entrance keeps Wood + Gold (TUNE, no source).
FIRST_FLOOR = {"WOOD": 0, "GOLD": 0, "DIAMOND": 1, "EMERALD": 1, "OBSIDIAN": 1, "BEDROCK": 5}
# F7 essence per chest (research; its "1-15" was the 1x Wither roll, see WIKI_ESS): Wood 10 / 1-15, Gold 20 / 18, Diamond 30 / 21, Emerald 40 / 28, Obsidian 55 / 35, Bedrock 70 / 50
ESS_F7 = {"WOOD": (10, 1, 15), "GOLD": (20, 18, 18), "DIAMOND": (30, 21, 21), "EMERALD": (40, 28, 28),
          "OBSIDIAN": (55, 35, 35), "BEDROCK": (70, 50, 50)}
# F7 coin costs straight from research 04 sec 3.14
F7_COST = {"WITHER_BOOTS": 2400000, "WITHER_CATALYST": 900000, "PRECURSOR_GEAR": 400000, "WITHER_HELMET": 3750000,
           "ULTIMATE_SOUL_EATER;1": 750000, "WITHER_LEGGINGS": 5500000, "WITHER_CLOAK": 4000000, "WITHER_BLOOD": 2000000,
           "WITHER_CHESTPLATE": 9000000, "ULTIMATE_ONE_FOR_ALL;1": 1000000, "RECOMBOBULATOR_3000": 5000000,
           "NECRON_HANDLE": 98000000, "IMPLOSION_SCROLL": 48000000, "SHADOW_WARP_SCROLL": 48000000,
           "WITHER_SHIELD_SCROLL": 48000000, "AUTO_RECOMBOBULATOR": 8000000}
F7_CHEST = {"WITHER_BOOTS": "GOLD", "WITHER_CATALYST": "GOLD", "PRECURSOR_GEAR": "GOLD", "HOT_POTATO_BOOK": "GOLD",
            "WITHER_HELMET": "DIAMOND", "ULTIMATE_SOUL_EATER;1": "DIAMOND", "WITHER_LEGGINGS": "EMERALD",
            "WITHER_CLOAK": "EMERALD", "WITHER_BLOOD": "EMERALD", "WITHER_CHESTPLATE": "OBSIDIAN",
            "ULTIMATE_ONE_FOR_ALL;1": "OBSIDIAN", "RECOMBOBULATOR_3000": "OBSIDIAN", "NECRON_HANDLE": "BEDROCK",
            "IMPLOSION_SCROLL": "BEDROCK", "SHADOW_WARP_SCROLL": "BEDROCK", "WITHER_SHIELD_SCROLL": "BEDROCK",
            "AUTO_RECOMBOBULATOR": "BEDROCK"}
# TUNE: how much the most valuable item of a floor costs on top of its chest (F7's is research: 98M)
TOP_COST = {1: 1500000, 2: 2500000, 3: 4000000, 4: 8000000, 5: 15000000, 6: 30000000, 7: 98000000}
ENTRANCE = [("ENCHANTMENT_ULTIMATE_JERRY_1", 1, 8000), ("ENCHANTMENT_FEATHER_FALLING_6", 1, 6000),
            ("ENCHANTMENT_INFINITE_QUIVER_6", 1, 6000), ("HOT_POTATO_BOOK", 1, 5000), ("ENCHANTMENT_REJUVENATE_1", 1, 3000),
            ("SPIRIT_LEAP", 4, 2000), ("SUPERBOOM_TNT", 8, 2000), ("REVIVE_STONE", 1, 9000)]
# research 04 sec 3.14 "M7 notable drops": these sit in the best chests whatever their meter value
TOP_SHELF = {"FIRST_MASTER_STAR": "OBSIDIAN", "SECOND_MASTER_STAR": "OBSIDIAN", "THIRD_MASTER_STAR": "OBSIDIAN",
             "FOURTH_MASTER_STAR": "OBSIDIAN", "FIFTH_MASTER_STAR": "BEDROCK", "DARK_CLAYMORE": "BEDROCK",
             "MASTER_SKULL_TIER_5": "BEDROCK", "THUNDERLORD;7": "OBSIDIAN", "NECRON_HANDLE": "BEDROCK"}
SKIP = {"STORM_THE_FISH", "MAXOR_THE_FISH", "GOLDOR_THE_FISH", "GIANT_THE_FISH", "DYE_LIVID", "DYE_NECRON"}


def norm(iid):
    """research/NEU id -> registry id (enchanted books are ENCHANTMENT_<ID>_<LVL>)."""
    if ";" in iid:
        base, lvl = iid.split(";", 1)
        if base.lower() in ENCHANTS:
            return "ENCHANTMENT_%s_%s" % (base, lvl)
    return iid


def is_book(iid):
    return iid.startswith("ENCHANTMENT_") or iid in ("HOT_POTATO_BOOK", "FUMING_POTATO_BOOK")


def nice(v):
    if v <= 0:
        return 0
    for step in (1000000, 100000, 10000, 1000):
        if v >= step * 10:
            return int(round(v / step) * step)
    return int(round(v / 100) * 100)


def floor_table(fid, n, master, mine):
    pool = RNG.get(fid, {})
    avail = [c for c in CHESTS if FIRST_FLOOR[c] <= n or master]
    entries = []
    if not pool:
        for (iid, amt, rng) in ENTRANCE:
            entries.append({"item": iid, "amount": amt, "rng": rng, "minChest": "WOOD" if is_book(iid) else "GOLD",
                            "weight": round(1e6 / rng, 2), "cost": 0})
        return entries, avail
    top = max(v for k, v in pool.items() if k not in SKIP)
    ordered = sorted(pool.items(), key=lambda kv: kv[1])
    for i, (raw, rng) in enumerate(ordered):
        if raw in SKIP:
            continue
        iid = norm(raw)
        if iid not in OTHER and iid not in mine and not iid.startswith("ENCHANTMENT_"):
            continue
        frac = rng / top
        if n == 7 and raw in F7_CHEST:
            mc = F7_CHEST[raw]
        elif raw in TOP_SHELF:
            mc = TOP_SHELF[raw]
        elif is_book(iid) and rng < 50000:
            mc = "WOOD" if frac < 0.3 else "GOLD"
        else:
            # rarer (higher meter) items sit in better chests (TUNE: research gives only F7's placement)
            idx = min(len(avail) - 1, 1 + int(frac * (len(avail) - 1) + 0.25))
            mc = avail[idx]
        if mc not in avail:
            mc = avail[-1]
        if n == 7 and raw in F7_COST:
            cost = F7_COST[raw]
        elif is_book(iid) and rng < 50000:
            cost = 0
        else:
            cost = nice(TOP_COST[n] * frac ** 0.8)
        if master:
            cost = nice(cost * 2)        # TUNE: Master Mode items cost twice the normal floor's
        e = {"item": iid, "amount": 1, "rng": rng, "minChest": mc, "weight": round(1e6 / rng, 3), "cost": cost}
        if raw.startswith(("GOLD_", "DIAMOND_")) and raw.endswith("_HEAD"):
            e["gate"] = None
        entries.append(e)
    # boss heads: Golden from Gold chests at boss collection III, Diamond from Diamond chests at VI (collections data)
    key = {v: k for k, v in HEAD_KEYS.items()}[n]
    entries.append({"item": "GOLD_%s_HEAD" % key, "amount": 1, "rng": 30000, "minChest": "GOLD", "weight": 4, "cost": nice(TOP_COST[n] * 0.05),
                    "gate": "collection:%s:3" % BOSS_COLL[n]})
    entries.append({"item": "DIAMOND_%s_HEAD" % key, "amount": 1, "rng": 60000, "minChest": "DIAMOND" if "DIAMOND" in avail else avail[-1],
                    "weight": 1, "cost": nice(TOP_COST[n] * 0.12), "gate": "collection:%s:6" % BOSS_COLL[n]})
    # wave 5: the boss dyes (data/dungeons/dyes.json; cosmetic, very rare, never on the RNG meter: rng 0 and free).
    # Lurid Dye from Floor V / Master V, Morvane Dye from Floor VII / Master VII, best chest only. Weights TUNE.
    if n in DYES:
        entries.append({"item": DYES[n], "amount": 1, "rng": 0, "minChest": "BEDROCK" if "BEDROCK" in avail else avail[-1],
                        "weight": 0.25 if master else 0.1, "cost": 0})
    return entries, avail


DYES = {5: "DYE_LIVID", 7: "DYE_NECRON"}

# ------------------------------------------------------------------------------------------------------
# audit C10/C11/C17 (2026-10-06): the wiki floor pages give chest base costs, essence and - for Floor VI - every
# chest's own item table with its own coin costs and drop chances. Research 04 had only F7 and two cost ends.
# ------------------------------------------------------------------------------------------------------
# chest base cost by floor number (wiki Floor I / II / III-VII pages; Bedrock 2M everywhere it exists)
WIKI_BASE = {1: {"GOLD": 25000, "DIAMOND": 50000, "EMERALD": 100000, "OBSIDIAN": 250000, "BEDROCK": 2000000},
             2: {"GOLD": 50000, "DIAMOND": 100000, "EMERALD": 200000, "OBSIDIAN": 500000, "BEDROCK": 2000000}}
WIKI_BASE_LATE = {"GOLD": 100000, "DIAMOND": 250000, "EMERALD": 500000, "OBSIDIAN": 1000000, "BEDROCK": 2000000}
# guaranteed (Undead, Wither) per chest, per floor - fixed amounts (research's "1-15" was the 1x Wither Essence roll)
WIKI_ESS = {
    5: {"WOOD": (10, 10), "GOLD": (15, 12), "DIAMOND": (20, 14), "EMERALD": (25, 18), "OBSIDIAN": (35, 24), "BEDROCK": (45, 34)},
    6: {"WOOD": (10, 12), "GOLD": (15, 15), "DIAMOND": (25, 18), "EMERALD": (35, 21), "OBSIDIAN": (45, 28), "BEDROCK": (55, 40)},
    7: {"WOOD": (10, 15), "GOLD": (20, 18), "DIAMOND": (30, 21), "EMERALD": (40, 28), "OBSIDIAN": (55, 35), "BEDROCK": (70, 50)},
}
WIKI_WOOD = {1: (3, 4), 2: (5, 6), 3: (5, 8), 4: (10, 8)}

# Floor VI, every chest's table: item -> {chest: (coin cost, drop chance %)} (wiki The_Catacombs_-_Floor_VI).
# Book names map to our ids: Bank/Jerry/Swarm/Combo/No Pain No Gain/Ultimate Wise/Wisdom/Last Stand are ultimates.
B = "ENCHANTMENT_"
F6_WIKI = {
    B + "ULTIMATE_BANK_1": {"WOOD": (0, 17.094), "GOLD": (0, 8.3333), "DIAMOND": (0, 7.9365), "EMERALD": (0, 8.0214)},
    B + "ULTIMATE_JERRY_1": {"WOOD": (0, 0.8547), "GOLD": (0, 0.5556), "DIAMOND": (0, 0.5291)},
    B + "FEATHER_FALLING_6": {"WOOD": (0, 29.9145), "GOLD": (0, 13.8889), "DIAMOND": (0, 13.2275), "EMERALD": (0, 10.6952)},
    B + "INFINITE_QUIVER_6": {"WOOD": (0, 25.641), "GOLD": (0, 11.1111), "DIAMOND": (0, 10.582), "EMERALD": (0, 8.5561)},
    B + "REJUVENATE_1": {"WOOD": (0, 25.641), "GOLD": (0, 22.2222), "DIAMOND": (0, 21.164)},
    "ESSENCE_WITHER": {"WOOD": (0, 0.8547), "GOLD": (0, 0.5556), "DIAMOND": (0, 0.5291), "EMERALD": (0, 0.5348),
                       "OBSIDIAN": (0, 0.2232), "BEDROCK": (0, 0.0375)},
    B + "ULTIMATE_SWARM_1": {"GOLD": (400000, 1.1111), "DIAMOND": (250000, 3.1746), "EMERALD": (0, 4.2781),
                             "OBSIDIAN": (0, 3.3482), "BEDROCK": (0, 5.6243)},
    "HOT_POTATO_BOOK": {"GOLD": (0, 2.7778), "DIAMOND": (0, 2.6455), "EMERALD": (0, 2.6738), "OBSIDIAN": (0, 8.9286)},
    "GIANT_TOOTH": {"GOLD": (400000, 2.2222), "DIAMOND": (250000, 3.7037), "EMERALD": (0, 4.2781), "OBSIDIAN": (0, 3.3482),
                    "BEDROCK": (0, 1.8748)},
    B + "ULTIMATE_COMBO_1": {"GOLD": (0, 8.8889), "DIAMOND": (0, 8.4656), "EMERALD": (0, 8.5561), "OBSIDIAN": (0, 8.9286)},
    B + "ULTIMATE_NO_PAIN_NO_GAIN_1": {"GOLD": (0, 8.8889), "DIAMOND": (0, 8.4656), "EMERALD": (0, 2.6738), "OBSIDIAN": (0, 8.9286)},
    B + "ULTIMATE_WISE_1": {"GOLD": (0, 11.1111), "DIAMOND": (0, 10.582), "EMERALD": (0, 10.6952), "OBSIDIAN": (0, 10.0446)},
    B + "ULTIMATE_WISDOM_1": {"GOLD": (0, 8.3333), "DIAMOND": (0, 7.9365), "EMERALD": (0, 8.0214), "OBSIDIAN": (0, 10.0446)},
    "NECROMANCER_LORD_BOOTS": {"DIAMOND": (1250000, 1.0582), "EMERALD": (1000000, 1.6043), "OBSIDIAN": (500000, 1.3393),
                               "BEDROCK": (0, 2.2497)},
    "NECROMANCER_LORD_HELMET": {"EMERALD": (1500000, 1.0695), "OBSIDIAN": (1000000, 0.8929), "BEDROCK": (0, 1.4998)},
    "SADAN_BROOCH": {"EMERALD": (1000000, 1.0695), "OBSIDIAN": (500000, 0.8929), "BEDROCK": (0, 1.4998)},
    B + "ULTIMATE_LAST_STAND_1": {"EMERALD": (0, 0.5348), "OBSIDIAN": (0, 13.3929)},
    B + "REJUVENATE_2": {"EMERALD": (0, 13.369), "OBSIDIAN": (0, 13.3929)},
    B + "ULTIMATE_JERRY_2": {"EMERALD": (0, 13.369), "OBSIDIAN": (0, 0.8929)},
    "SUMMONING_RING": {"OBSIDIAN": (11000000, 0.2232), "BEDROCK": (10000000, 0.375)},
    "NECROMANCER_SWORD": {"OBSIDIAN": (9000000, 0.4464), "BEDROCK": (8000000, 0.7499)},
    "NECROMANCER_LORD_LEGGINGS": {"OBSIDIAN": (3000000, 0.6696), "BEDROCK": (2000000, 1.1249)},
    "RECOMBOBULATOR_3000": {"OBSIDIAN": (5000000, 1.3393), "BEDROCK": (4000000, 3.7495)},
    "SOULWEAVER_GLOVES": {"OBSIDIAN": (4000000, 0.6696), "BEDROCK": (3000000, 1.1249)},
    "FUMING_POTATO_BOOK": {"OBSIDIAN": (0, 2.0089), "BEDROCK": (0, 7.4991)},
    B + "ULTIMATE_BANK_2": {"OBSIDIAN": (0, 10.0446)},
    "PRECURSOR_EYE": {"BEDROCK": (28000000, 0.1125)},
    "GIANTS_SWORD": {"BEDROCK": (23000000, 0.1875)},
    "NECROMANCER_LORD_CHESTPLATE": {"BEDROCK": (8000000, 0.3)},
    "FEL_SKULL": {"BEDROCK": (4000000, 0.5624)},
    B + "ULTIMATE_COMBO_2": {"BEDROCK": (0, 7.4991)},
    B + "ULTIMATE_NO_PAIN_NO_GAIN_2": {"BEDROCK": (0, 6.5617)},
    B + "ULTIMATE_LAST_STAND_2": {"BEDROCK": (0, 14.2482)},
    B + "REJUVENATE_3": {"BEDROCK": (0, 14.2482)},
    B + "ULTIMATE_WISE_2": {"BEDROCK": (0, 12.3735)},
    B + "ULTIMATE_WISDOM_2": {"BEDROCK": (0, 8.4364)},
    B + "ULTIMATE_BANK_3": {"BEDROCK": (0, 6.5617)},
    B + "ULTIMATE_JERRY_3": {"BEDROCK": (0, 1.4998)},
}
# the floor's boss heads are not on the wiki's chest tables (they come with the boss collection): TUNE, small
F6_HEADS = {"GOLD_SADAN_HEAD": ({"GOLD": 0.5, "DIAMOND": 0.5, "EMERALD": 0.5, "OBSIDIAN": 0.5, "BEDROCK": 0.5}, 30000, "collection:BOSS_SADAN:3"),
            "DIAMOND_SADAN_HEAD": ({"DIAMOND": 0.25, "EMERALD": 0.25, "OBSIDIAN": 0.25, "BEDROCK": 0.25}, 60000, "collection:BOSS_SADAN:6")}


def per_chest_table(fid, table, heads, mine):
    """Entries for a floor whose every chest table is known: each carries weights/costs per chest (LootTables.Entry)."""
    meter = {norm(k): v for k, v in RNG.get(fid, {}).items()}
    out, skipped = [], []
    for iid, per in table.items():
        if iid not in OTHER and iid not in mine and not iid.startswith("ENCHANTMENT_"):
            skipped.append(iid)
            continue
        chests = [c for c in CHESTS if c in per]
        low = chests[0]
        out.append({"item": iid, "amount": 1, "rng": meter.get(iid, 0), "minChest": low, "weight": per[low][1],
                    "cost": per[low][0], "weights": {c: per[c][1] for c in chests}, "costs": {c: per[c][0] for c in chests}})
    for iid, (w, rng, gate) in heads.items():
        chests = [c for c in CHESTS if c in w]
        out.append({"item": iid, "amount": 1, "rng": rng, "minChest": chests[0], "weight": w[chests[0]], "cost": 0,
                    "weights": w, "costs": {c: 0 for c in chests}, "gate": gate})
    if skipped:
        print(fid, "per-chest items nobody registers (left out; the rest of the chest renormalises):", skipped)
    return out


def loot(mine):
    floors = {}
    order = ["E"] + ["F%d" % i for i in range(1, 8)] + ["M%d" % i for i in range(1, 8)]
    for fid in order:
        master = fid.startswith("M")
        n = 0 if fid == "E" else int(fid[1:])
        entries, avail = floor_table(fid, n, master, mine)
        if fid == "E":
            avail = ["WOOD", "GOLD"]
        per_chest = fid == "F6"
        if per_chest:
            entries = per_chest_table(fid, F6_WIKI, F6_HEADS, mine)
        chests = {}
        scale = (max(n, 0.5) / 7.0) * (2 if master else 1)
        for c in avail:
            # base cost: the wiki floor pages (F1 25k/50k/100k/250k, F2 double, F3+ 100k/250k/500k/1M, Bedrock 2M)
            base = 0 if c == "WOOD" else WIKI_BASE.get(n, WIKI_BASE_LATE)[c]
            if fid == "E":
                base = {"WOOD": 0, "GOLD": 10000}[c]       # TUNE: Entrance gold chest
            if master:
                base *= 2                                  # TUNE (unverified for Master Mode)
            # essence: fixed per chest (wiki); floors/chests the wiki lacks scale F7's numbers (TUNE)
            u, wlo, whi = ESS_F7[c]
            und, wit = max(1, round(u * scale)), max(1, round(whi * scale))
            if n in WIKI_ESS:
                und, wit = WIKI_ESS[n][c]
                if master:
                    und, wit = und * 2, wit * 2
            elif c == "WOOD" and n in WIKI_WOOD:
                und, wit = WIKI_WOOD[n]
                if master:
                    und, wit = und * 2, wit * 2
            # a per-chest floor rolls ONE item per chest: the wiki's "1 in N runs" (its percentages sum to 100 per chest)
            slots = 1 if per_chest else {"WOOD": 1, "GOLD": 2, "DIAMOND": 2, "EMERALD": 3, "OBSIDIAN": 3, "BEDROCK": 3}[c]
            chests[c] = {"cost": nice(base), "undead": und, "witherMin": wit, "witherMax": wit, "slots": slots}
        floors[fid] = {"chests": chests, "entries": entries}
    return {"chestOrder": CHESTS,
            "unlock": {"WOOD": "milestone:2", "GOLD": "boss", "DIAMOND": "boss", "EMERALD": "score:230",
                       "OBSIDIAN": "score:270", "BEDROCK": "score:300"},
            "floors": floors}


# ------------------------------------------------------------------------------------------------------
# salvage: dungeon items -> essence (research `salvages`; otherwise by rarity, TUNE)
# ------------------------------------------------------------------------------------------------------
SALVAGE_BY_RARITY = {"COMMON": 1, "UNCOMMON": 2, "RARE": 4, "EPIC": 8, "LEGENDARY": 15, "MYTHIC": 25}


def salvage():
    out = {}
    for iid, x in ITEMS.items():
        if x.get("salvages"):
            for s in x["salvages"]:
                if s.get("type") == "ESSENCE":
                    out[iid] = {"essence": s["essence_type"], "amount": s["amount"]}
                    break
        elif x.get("dungeon_item") and x.get("rarity_salvageable") and x.get("category") in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SWORD", "BOW"):
            out[iid] = {"essence": "UNDEAD", "amount": SALVAGE_BY_RARITY.get(x.get("tier", "COMMON"), 1)}
    return out


# ------------------------------------------------------------------------------------------------------
# boss mobs. health/damage here are the NORMAL-mode values; fights set exact HP per floor (research 04 sec 3.13)
# ------------------------------------------------------------------------------------------------------
def bm(id_, name, entity, hp, dmg, level, flags=None, eq=None, skull=None, extra=None, speed=1.0):
    d = {"id": id_, "name": name, "entity": entity, "level": level, "health": hp, "damage": dmg, "combatXp": 0, "coins": 0,
         "drops": [], "family": id_, "flags": flags or ["aggressive", "no_knockback", "fire_immune"],
         "equipment": eq or {}, "behaviours": [], "speed": speed}
    if skull:
        d["skull"] = skull
    d["extra"] = dict(extra or {})
    d["extra"].setdefault("types", ["undead"])
    return d


BOSSF = ["boss", "aggressive", "no_knockback", "fire_immune"]
STILL = ["boss", "no_ai", "no_knockback", "fire_immune", "silent"]
LURID_COLOURS = [("red", "&c"), ("green", "&a"), ("blue", "&9"), ("yellow", "&e"), ("purple", "&5"), ("gray", "&7"),
                 ("pink", "&d"), ("white", "&f"), ("cyan", "&3")]


def boss_mobs():
    out = [
        # F1 - Grinko (Bonzo): 250k / 200M, 2,664 / 45,000; Undead adds 45,000 / 15M
        bm("dboss_grinko", "Grinko", "ZOMBIE", 250000, 2664, 120, BOSSF, {"HEAD": "PLAYER_HEAD", "HAND": "BLAZE_ROD"},
           skull=skull(ITEMS["BONZO_MASK"])),
        bm("dboss_grinko_undead", "Circus Undead", "ZOMBIE", 45000, 1200, 90, ["aggressive"], {"HEAD": "LEATHER_HELMET", "HAND": "STONE_SWORD"}),
        # F2 - Veilwright (Scarf) and his four students
        bm("dboss_veilwright", "Veilwright", "WITHER_SKELETON", 1000000, 4000, 140, BOSSF, {"HAND": "BLAZE_ROD"}, extra={"types": ["undead", "wither"]}),
        bm("dboss_veil_warrior", "Undead Warrior", "ZOMBIE", 500000, 2880, 120, ["aggressive", "no_knockback"], {"HEAD": "IRON_HELMET", "CHEST": "IRON_CHESTPLATE", "HAND": "IRON_AXE"}),
        bm("dboss_veil_archer", "Undead Archer", "SKELETON", 400000, 2752, 120, ["aggressive", "no_knockback"], {"HEAD": "CHAINMAIL_HELMET", "HAND": "BOW"}),
        bm("dboss_veil_mage", "Undead Mage", "STRAY", 400000, 2432, 120, ["aggressive", "no_knockback"], {"HAND": "STICK"}),
        bm("dboss_veil_priest", "Undead Priest", "HUSK", 600000, 1920, 120, ["aggressive", "no_knockback"], {"HEAD": "GOLDEN_HELMET", "HAND": "GOLDEN_HOE"}),
        # F3 - the Lecturer (Professor) and his guardians: 3M / 600M, 6,640 / 150,000
        bm("dboss_lecturer", "The Lecturer", "EVOKER", 3000000, 6640, 160, BOSSF, {}),
        bm("dboss_guardian_healthy", "Healthy Guardian", "GUARDIAN", 900000, 1500, 150, STILL, extra={"types": ["aquatic"]}),
        bm("dboss_guardian_reinforced", "Reinforced Guardian", "GUARDIAN", 1500000, 1500, 150, STILL, extra={"types": ["aquatic"]}),
        bm("dboss_guardian_chaos", "Chaos Guardian", "GUARDIAN", 900000, 1500, 150, STILL, extra={"types": ["aquatic"]}),
        bm("dboss_guardian_laser", "Laser Guardian", "GUARDIAN", 900000, 1500, 150, STILL, extra={"types": ["aquatic"]}),
        bm("dboss_rogue_guardian", "Rogue Guardian", "GUARDIAN", 120000, 900, 120, ["aggressive"], extra={"types": ["aquatic"]}),
        bm("dboss_elder_guardian", "Elder Guardian", "ELDER_GUARDIAN", 400000, 1600, 140, ["aggressive", "no_knockback"], extra={"types": ["aquatic"]}),
        # F4 - Bramblemaw (Thorn): only Spirit Bow hits hurt it
        bm("dboss_bramblemaw", "Bramblemaw", "RAVAGER", 4, 8000, 180, STILL, extra={"types": ["animal"]}),
        bm("dboss_spirit_bull", "Spirit Bull", "COW", 90000, 2400, 120, ["aggressive", "no_knockback"], extra={"types": ["animal"]}, speed=1.6),
        bm("dboss_spirit_wolf", "Spirit Wolf", "WOLF", 60000, 1800, 120, ["aggressive"], extra={"types": ["animal", "wolf"]}, speed=1.3),
        bm("dboss_spirit_bat", "Spirit Bat", "BAT", 25000, 1200, 120, ["aggressive"], extra={"types": ["animal"]}),
        bm("dboss_spirit_rabbit", "Spirit Rabbit", "RABBIT", 30000, 900, 120, ["aggressive"], extra={"types": ["animal"]}, speed=1.4),
        bm("dboss_spirit_chicken", "Spirit Chicken", "CHICKEN", 30000, 900, 120, ["aggressive"], extra={"types": ["animal"]}),
        bm("dboss_spirit_sheep", "Spirit Sheep", "SHEEP", 40000, 3000, 120, ["aggressive"], extra={"types": ["animal"]}),
        bm("dboss_spirit_bear", "Spirit Bear", "POLAR_BEAR", 5000000, 4000, 170, ["boss", "aggressive", "no_knockback"], extra={"types": ["animal"]}),
        # F6 - Gorrak (Sadan): 40M / 800M; giants ~25M (TUNE) / 600M; terracottas
        bm("dboss_gorrak", "Gorrak", "GIANT", 40000000, 14000, 220, STILL, {"HAND": "DIAMOND_SWORD"}),
        bm("dboss_terracotta", "Terracotta", "ZOMBIE", 300000, 2500, 160, ["aggressive"], {"HEAD": "TERRACOTTA", "HAND": "STONE_SWORD"}),
        bm("dboss_terracotta_archer", "Terracotta Archer", "SKELETON", 220000, 2200, 160, ["aggressive"], {"HEAD": "TERRACOTTA", "HAND": "BOW"}),
        bm("dboss_giant_diamond", "Sparkling Giant", "GIANT", 25000000, 9000, 200, STILL, {"HAND": "DIAMOND_SWORD", "HEAD": "DIAMOND_HELMET"}),
        bm("dboss_giant_laser", "Beam Giant", "GIANT", 25000000, 8000, 200, STILL, {"HEAD": "IRON_HELMET", "CHEST": "IRON_CHESTPLATE"}),
        bm("dboss_giant_bigfoot", "Strider Giant", "GIANT", 25000000, 10000, 200, STILL, {"FEET": "LEATHER_BOOTS", "LEGS": "LEATHER_LEGGINGS"}),
        bm("dboss_giant_boulder", "Rosy Giant", "GIANT", 25000000, 9000, 200, STILL, {"HEAD": "PINK_WOOL"}),
        # F7 - the four Wither Lords: Gravax 100M, Tempestra 400M, Aurik 750M, Morvane 1B (master 0.8B..1.4B)
        bm("dboss_gravax", "Gravax", "WITHER_SKELETON", 100000000, 20000, 240, BOSSF, {"HAND": "NETHERITE_SWORD"}, extra={"types": ["wither", "undead"]}, speed=1.3),
        bm("dboss_tempestra", "Tempestra", "WITHER_SKELETON", 400000000, 22000, 250, BOSSF, {"HAND": "TRIDENT"}, extra={"types": ["wither", "undead"]}),
        bm("dboss_aurik", "Aurik", "WITHER_SKELETON", 750000000, 25000, 260, BOSSF, {"HEAD": "GOLDEN_HELMET", "CHEST": "GOLDEN_CHESTPLATE", "HAND": "GOLDEN_SWORD"},
           extra={"types": ["wither", "undead"]}, speed=0.5),
        bm("dboss_morvane", "Morvane", "WITHER_SKELETON", 1000000000, 30000, 270, STILL, {"HAND": "NETHER_STAR"}, extra={"types": ["wither", "undead"]}),
        bm("dboss_wither_guard", "Wither Guard", "WITHER_SKELETON", 3500000, 20000, 220, ["aggressive"], {"HEAD": "NETHERITE_HELMET", "HAND": "BOW"}, extra={"types": ["wither", "undead"]}),
        bm("dboss_wither_miner", "Wither Miner", "WITHER_SKELETON", 10000000, 14460, 220, ["aggressive"], {"HAND": "NETHERITE_PICKAXE"}, extra={"types": ["wither", "undead"]}),
        bm("dboss_withermancer", "Witherbinder", "WITHER_SKELETON", 1200000, 12000, 220, ["aggressive"], {"HAND": "BLAZE_ROD"}, extra={"types": ["wither", "undead"]}),
        bm("dboss_shade_stalker", "Shade Stalker", "WITHER_SKELETON", 20000000, 18000, 230, ["aggressive", "no_knockback"], {"HAND": "NETHERITE_SWORD"}, extra={"types": ["wither", "undead"]}, speed=1.4),
        # M7 - the Hollow King and his five dragons (~1B each, 800k damage, 2,500 defense)
        bm("dboss_hollow_king", "The Hollow King", "WITHER_SKELETON", 1, 300000, 300, STILL, {"HEAD": "WITHER_SKELETON_SKULL"}, extra={"types": ["wither"]}),
        bm("dboss_dragon_apex", "Verdant Dragon", "PHANTOM", 1000000000, 800000, 300, BOSSF, extra={"types": ["dragon"]}),
        bm("dboss_dragon_flame", "Ember Dragon", "PHANTOM", 1000000000, 800000, 300, BOSSF, extra={"types": ["dragon"]}),
        bm("dboss_dragon_ice", "Frost Dragon", "PHANTOM", 1000000000, 800000, 300, BOSSF, extra={"types": ["dragon"]}),
        bm("dboss_dragon_power", "Might Dragon", "PHANTOM", 1000000000, 800000, 300, BOSSF, extra={"types": ["dragon"]}),
        bm("dboss_dragon_soul", "Soul Dragon", "PHANTOM", 1000000000, 800000, 300, BOSSF, extra={"types": ["dragon"]}),
        bm("dboss_wither_husk", "Wither Husk", "WITHER_SKELETON", 500000000, 100000, 280, ["aggressive"], {"HAND": "STONE_SWORD"}, extra={"types": ["wither", "undead"]}),
        # wave 5: the Hollow King's Apostles (research 04 sec 3.13: 800M, 120k damage)
        bm("dboss_apostle", "Hollow Apostle", "WITHER_SKELETON", 800000000, 120000, 290, ["aggressive", "no_knockback"],
           {"HEAD": "WITHER_SKELETON_SKULL", "HAND": "NETHERITE_SWORD"}, extra={"types": ["wither", "undead"]}),
    ]
    for d in out:
        if d["id"].startswith("dboss_dragon"):
            d["defense"] = 2500
    # F5 - Lurid (Livid) and the eight copies: one def per nametag colour (the real one is one of them per run)
    for (c, code) in LURID_COLOURS:
        out.append(bm("dboss_lurid_" + c, code + "Lurid", "PLAYER" if False else "ZOMBIE", 7000000, 10000, 200, BOSSF,
                      {"HEAD": "PLAYER_HEAD", "CHEST": "LEATHER_CHESTPLATE", "HAND": "IRON_SWORD"}, speed=1.2))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    g = gear()
    mine = {d["id"]: d for d in g}
    dup = [i for i in mine if i in OTHER]
    if dup:
        print("NOTE: also registered elsewhere (first registration wins):", dup)
    rec = recipes(mine)
    bad = []
    for r in rec:
        for c in r["grid"]:
            if c:
                cid = c.rsplit(":", 1)[0]
                if cid not in OTHER and cid not in mine:
                    bad.append((r["result"], cid))
    if bad:
        print("recipes with unknown ingredients (kept; the self-test lists them):", bad)
    lt = loot(mine)
    missing = sorted({e["item"] for f in lt["floors"].values() for e in f["entries"]
                      if e["item"] not in OTHER and e["item"] not in mine and not e["item"].startswith("ENCHANTMENT_")})
    if missing:
        print("loot items nobody registers (skipped at roll time):", missing)
    files = (("gear.json", g), ("gear_recipes.json", rec), ("loot.json", lt), ("salvage.json", salvage()), ("boss_mobs.json", boss_mobs()))
    for name, data in files:
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
        print("wrote", name, len(data))


if __name__ == "__main__":
    main()
