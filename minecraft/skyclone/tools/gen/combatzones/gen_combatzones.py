"""
Generate the combatzones module's data (Spider's Den + The End) from the research data.

    py -3 tools/gen/combatzones/gen_combatzones.py

Reads
  research/data/items.json                        rarity, sell price, legacy material of the drops this module owns
  research/data/neu-repo/items/<MOB>_MONSTER.json  drop tables, levels, coins, combat XP per mob level (type "drops")
  research/data/neu-repo/items/<ID>.json           skull textures of the items we own
and research/03 sec 13.2 / 14.2 / 14.3 (HP and damage per mob level, dragon variants) transcribed below.

Writes plugin/src/main/resources/data/combatzones/{items,mobs,spawns}.json
"""
import json, os, re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "research", "data")
NEU = os.path.join(RES, "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "combatzones")
os.makedirs(OUT, exist_ok=True)

ITEMS = {i["id"]: i for i in json.load(open(os.path.join(RES, "items.json"), encoding="utf-8"))["items"]}
SECT = re.compile("§.")


def neu(iid):
    p = os.path.join(NEU, iid + ".json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None


def skull_of(iid):
    d = neu(iid)
    if not d:
        return None
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', d.get("nbttag", ""))
    return m.group(1) if m else None


RARITY = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "SPECIAL"}

# ------------------------------------------------------------------------------------------------ items we own
# id -> (our name, material, lore lines (our own words), extra)
OWN = {
    "SUMMONING_EYE": ("Summoning Eye", "ENDER_EYE", [
        "&7Set this into an empty socket of the",
        "&7altar in the &5Dragon's Nest&7. When all",
        "&7eight sockets are filled, a dragon",
        "&7answers the call.", "", "&eRight-click an altar frame to place!"], {"glow": True}),
    "SLEEPING_EYE": ("Sleeping Eye", "ENDER_EYE", [
        "&7Proof that one of your eyes sits in the",
        "&7altar. Keep it to take your eye back if",
        "&7you leave before the dragon wakes.", "",
        "&7When the dragon rises it turns into a",
        "&5Remnant of the Eye&7."], {}),
    "REMNANT_OF_THE_EYE": ("Remnant of the Eye", "ENDER_EYE", [
        "&7Carried in your inventory, it spends",
        "&7itself to pull you back from a killing",
        "&7blow, granting a moment of invulnerability.", "",
        "&cOnly works in The End."], {"glow": True}),
    "YOUNG_FRAGMENT": ("Young Dragon Fragment", "PLAYER_HEAD", ["&7A shard of a swift young dragon's hide."], {}),
    "OLD_FRAGMENT": ("Old Dragon Fragment", "PLAYER_HEAD", ["&7A heavy, scarred plate from an ancient dragon."], {}),
    "STRONG_FRAGMENT": ("Strong Dragon Fragment", "PLAYER_HEAD", ["&7A dense fragment that still hums with power."], {}),
    "UNSTABLE_FRAGMENT": ("Unstable Dragon Fragment", "PLAYER_HEAD", ["&7It crackles. Do not hold it in the rain."], {}),
    "WISE_FRAGMENT": ("Wise Dragon Fragment", "PLAYER_HEAD", ["&7Cold to the touch and faintly glowing blue."], {}),
    "PROTECTOR_FRAGMENT": ("Protector Dragon Fragment", "PLAYER_HEAD", ["&7Thick as a shield wall and nearly as heavy."], {}),
    "SUPERIOR_FRAGMENT": ("Superior Dragon Fragment", "PLAYER_HEAD", ["&7Torn from the rarest dragon of them all."], {}),
    "HOLY_FRAGMENT": ("Holy Dragon Fragment", "PLAYER_HEAD", ["&7Warm, and it smells faintly of incense."], {}),
    "CRYSTAL_FRAGMENT": ("Crystal Fragment", "QUARTZ", ["&7A splinter of the crystals that feed the", "&7dragons of the nest."], {"glow": True}),
    "MITE_GEL": ("Mite Gel", "PURPLE_DYE", ["&7Sticky goo scraped off a nest endermite."], {}),
    "ENDSTONE_ROSE": ("End Stone Rose", "POPPY", ["&7A gift the Colossus never got to give."], {"glow": True}),
    "ARACHNE_FRAGMENT": ("Silkmother Fragment", "PLAYER_HEAD", ["&7A chitin shard carrying the Silkmother's", "&7lingering strength."], {}),
    "ARACHNE_KEEPER_FRAGMENT": ("Silkmother's Calling", "PAPER", [
        "&7Four of these, set into the altar of the",
        "&7&dSilkmother's Sanctuary&7, wake her.", "", "&8\"She hears every thread.\""], {}),
    "ARACHNE_CRYSTAL": ("Silkmother Crystal", "PLAYER_HEAD", [
        "&7Set this into the Sanctuary altar to call",
        "&7the Silkmother at her &cfull strength&7.", "", "&8\"We should not have done that.\""], {}),
    "SOUL_STRING": ("Soul String", "STRING", ["&7Silk spun by the Silkmother herself, used", "&7to bind weapons with her power."], {"glow": True}),
    "DARK_QUEENS_SOUL_DROP": ("Silk Queen's Soul", "CLAY_BALL", ["&7A sliver of the Silkmother's soul, still", "&7twitching."], {"glow": True}),
    "ARACHNE_FANG": ("Silkmother's Fang", "BONE_MEAL", ["&7Hollow, and still wet with venom."], {}),
    "SPIDERS_DEN_TOP_TRAVEL_SCROLL": ("Travel Scroll to the Top of the Nest", "MAP", [], {"warp": "nest"}),
    "ARACHNE_SANCTUARY_TRAVEL_SCROLL": ("Travel Scroll to the Silkmother's Sanctuary", "MAP", [], {"warp": "sanctuary"}),
    "DRAGON_NEST_TRAVEL_SCROLL": ("Travel Scroll to the Dragon's Nest", "MAP", [], {"warp": "drag"}),
    "VOID_SEPULTURE_TRAVEL_SCROLL": ("Travel Scroll to the Null Tomb", "MAP", [], {"warp": "void"}),
    # wave 5: the Draconic Altar's bonus loot (materials registered a fallback; ours wins, we enable first)
    "RITUAL_RESIDUE": ("Ritual Residue", "PAPER", [
        "&7What the Draconic Altar leaves behind", "&7after a sacrifice. Crafted into the", "&7draconic accessories and equipment."], {"glow": True}),
}
SCROLL_TARGET = {"nest": ("Spider's Den", "Top of the Nest"), "sanctuary": ("Spider's Den", "Silkmother's Sanctuary"),
                 "drag": ("The End", "Dragon's Nest"), "void": ("The End", "Null Tomb")}

items = []
for iid, (name, mat, lore, ex) in OWN.items():
    ref = ITEMS.get(iid, {})
    rarity = ref.get("tier", "COMMON")
    if rarity not in RARITY:
        rarity = "COMMON"
    d = {"id": iid, "name": name, "material": mat, "rarity": rarity, "category": "MATERIAL", "lore": list(lore),
         "tags": ["bazaar"] if "FRAGMENT" in iid or iid in ("SUMMONING_EYE", "MITE_GEL", "SOUL_STRING", "DARK_QUEENS_SOUL_DROP", "ARACHNE_FANG", "RITUAL_RESIDUE") else [],
         "extra": {"family": "combat", "owner": "combatzones"}}
    if ref.get("npc_sell_price"):
        d["npcSell"] = float(ref["npc_sell_price"])
    if mat == "PLAYER_HEAD":
        s = skull_of(iid)
        if s:
            d["skull"] = s
        else:
            d["material"] = "PRISMARINE_SHARD"
    if ex.get("glow"):
        d["glow"] = True
    if "warp" in ex:
        isl, place = SCROLL_TARGET[ex["warp"]]
        d["category"] = "TRAVEL_SCROLL"
        d["extra"]["czWarp"] = ex["warp"]
        d["lore"] = ["&7Read it to add a new destination to", "&7your fast travel list for good.", "",
                     "&7Island: &a" + isl, "&7Destination: &b" + place, "", "&eRight-click to consume!"]
        d["tags"] = ["usable"]
    if iid in ("SLEEPING_EYE", "REMNANT_OF_THE_EYE"):
        d["tags"] = ["no_ah"]
        d["maxStack"] = 64
    items.append(d)
json.dump(items, open(os.path.join(OUT, "items.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)


# ------------------------------------------------------------------------------------------------ drop tables
def drops_of(mob_file):
    """{level: (coins, combatXp, [DropDef])} from a NEU mob page."""
    d = neu(mob_file)
    out = {}
    if not d:
        return out
    for r in d.get("recipes", []):
        if r.get("type") != "drops":
            continue
        lvl = r.get("level")
        rows = []
        for dr in r.get("drops", []):
            iid = dr["id"]
            if iid.startswith("ATTRIBUTE_SHARD") or iid.startswith("DYE_"):
                continue                                   # attribute shards / dyes are other systems
            parts = iid.split(":")
            base, qty = parts[0], 1
            if len(parts) > 1 and parts[-1].isdigit():
                qty = int(parts[-1])
            ch = dr.get("chance", "100%").strip()
            per_eye = "per Summoning Eye" in ch
            ch = ch.replace("per Summoning Eye", "").strip()
            chance = float(ch[:-1]) / 100.0 if ch.endswith("%") else float(ch)
            lo = hi = qty
            for e in dr.get("extra", []):
                m = re.search(r"x(\d+)-(\d+)", SECT.sub("", e))
                if m:
                    lo, hi = int(m.group(1)), int(m.group(2))
            if hi == 0:
                continue
            row = {"item": base, "min": max(1, lo) if lo > 0 else 1, "max": hi, "chance": round(chance, 8)}
            if lo == 0:
                row["chance"] = round(chance * hi / (hi + 1), 6)    # "x0-N": a roll that can give nothing
                row["min"] = 1
            if per_eye:
                row["perEye"] = True
            if base == "SUMMONING_EYE":
                row["magicFind"] = False
            rows.append(row)
        if lvl not in out:                                 # first row per level is the plain mob (variants follow)
            out[lvl] = (r.get("coins") or 0, r.get("combat_xp") or r.get("xp") or 0, rows)
    return out


def pick(table, level):
    """Closest level row of a NEU table (the page lists a few levels, we have more)."""
    if not table:
        return (0, 0, [])
    if level in table:
        return table[level]
    keys = [k for k in table if k is not None]
    if not keys:
        return list(table.values())[0]
    k = min(keys, key=lambda x: (abs(x - level), -x))
    return table[k]


# ------------------------------------------------------------------------------------------------ mobs
# research/03 sec 13.2 (Spider's Den) and 14.2 (The End): id, name, entity, NEU page, [(level, hp, dmg)], family,
# slayer, flags, behaviours, types, override xp. Damage marked TUNE where the research gives only a range.
MOBS = [
    # ---- Spider's Den -------------------------------------------------------------------------------
    ("den_splitter_spider", "Splitter Spider", "SPIDER", "SPLITTER_SPIDER_MONSTER",
     [(2, 180, 30), (4, 220, 45), (6, 260, 60), (42, 1600, 400), (45, 2000, 500), (50, 2400, 600)],
     "splitter_spider", "spider", ["aggressive"], ["den_splitter"], ["arthropod"]),
    ("den_silverfish", "Silverfish", "SILVERFISH", "SILVERFISH_MONSTER",
     [(2, 50, 20), (42, 300, 200)], "silverfish", "spider", ["aggressive"], [], ["arthropod"]),
    ("den_dasher_spider", "Dasher Spider", "SPIDER", "DASHER_SPIDER_MONSTER",
     [(4, 160, 55), (6, 170, 70), (42, 750, 300), (45, 900, 400), (50, 1000, 500)],
     "dasher_spider", "spider", ["aggressive"], ["den_dasher"], ["arthropod"]),
    ("den_weaver_spider", "Weaver Spider", "SPIDER", "WEAVER_SPIDER_MONSTER",
     [(3, 160, 40), (4, 180, 45), (5, 200, 50), (6, 220, 55), (42, 800, 300), (45, 950, 350), (50, 1100, 400)],
     "weaver_spider", "spider", ["aggressive"], ["den_weaver"], ["arthropod"]),
    ("den_voracious_spider", "Voracious Spider", "SPIDER", "VORACIOUS_SPIDER_MONSTER",
     [(10, 1000, 100), (42, 1800, 400)], "voracious_spider", "spider", ["aggressive"], ["den_voracious"], ["arthropod"]),
    ("den_spider_jockey", "Spider Jockey", "SPIDER", "SPIDER_JOCKEY_MONSTER",
     [(3, 220, 50), (5, 250, 60), (42, 1200, 400)], "spider_jockey", "spider", ["aggressive"], ["den_jockey"], ["arthropod", "skeletal"]),
    ("den_cave_spider", "Cave Spider", "CAVE_SPIDER", "CAVE_SPIDER_MONSTER",
     [(5, 120, 35)], "spider", "spider", ["aggressive"], [], ["arthropod"]),
    ("den_flint_skeleton", "Flint Skeleton", "SKELETON", "GRAVEL_SKELETON_MONSTER",
     [(2, 100, 25)], "flint_skeleton", None, ["aggressive"], [], ["skeletal", "undead"]),
    ("den_rain_slime", "Rain Slime", "SLIME", "RAIN_SLIME_MONSTER",
     [(8, 300, 60)], "rain_slime", None, ["aggressive"], [], ["cubic", "slime"]),     # wave 5: the genre's mob type is Cubic
    ("den_toxic_rain_slime", "Toxic Rain Slime", "SLIME", "RAIN_SLIME_MONSTER",
     [(20, 1500, 150)], "rain_slime", None, ["aggressive"], ["den_toxic"], ["cubic", "slime"]),   # TUNE: research gives "(?)"
    ("den_arachnes_keeper", "Silkmother's Keeper", "SPIDER", "ARACHNES_KEEPER_MINIBOSS",
     [(100, 3000, 400)], "arachnes_keeper", "spider", ["aggressive", "no_knockback"], ["den_keeper"], ["arthropod"]),
    ("den_arachnes_brood", "Silkmother's Brood", "CAVE_SPIDER", "ARACHNES_BROOD_MONSTER",
     [(100, 4000, 500), (200, 8000, 700)], "arachnes_brood", "spider", ["aggressive"], [], ["arthropod"]),       # lv200 TUNE
    ("den_broodmother", "Broodmother", "SPIDER", "BROOD_MOTHER_MINIBOSS",
     [(12, 6000, 300)], "broodmother", "spider", ["aggressive", "boss", "no_knockback"], ["den_broodmother"], ["arthropod"]),
    ("den_arachne", "Silkmother", "SPIDER", "ARACHNE_BOSS",
     [(300, 20000, 1500), (500, 100000, 3000)], "arachne", "spider", ["aggressive", "boss", "no_knockback"], ["den_arachne"], ["arthropod"]),
    # ---- The End ------------------------------------------------------------------------------------
    ("end_enderman", "Enderman", "ENDERMAN", "ENDERMAN_MONSTER",
     [(42, 4500, 500), (45, 6000, 600), (50, 9000, 700)], "enderman_combat_3", "enderman", ["aggressive"], [], ["ender", "enderman"]),
    ("end_endermite", "Endermite", "ENDERMITE", "ENDERMITE_MONSTER",
     [(37, 2000, 400), (40, 2300, 450)], "endermite", None, ["aggressive"], [], ["ender", "arthropod"]),
    ("end_nest_endermite", "Nest Endermite", "ENDERMITE", "NEST_ENDERMITE_MONSTER",
     [(50, 2200, 500)], "endermite", None, ["aggressive"], [], ["ender", "arthropod"]),
    ("end_obsidian_defender", "Obsidian Defender", "WITHER_SKELETON", "OBSIDIAN_DEFENDER_MONSTER",
     [(55, 10000, 200)], "obsidian_defender", None, ["aggressive", "fire_immune"], ["end_defender"], ["ender", "skeletal"]),
    ("end_seer", "Seer", "SKELETON", "WATCHER_MONSTER",
     [(55, 9500, 475)], "seer", None, ["aggressive"], ["end_seer"], ["ender", "skeletal"]),
    ("end_zealot", "Zealot", "ENDERMAN", "ZEALOT_MONSTER",
     [(55, 13000, 1250)], "zealot", "enderman", ["aggressive"], ["end_zealot"], ["ender", "enderman"]),
    ("end_zealot_bruiser", "Zealot Bruiser", "ENDERMAN", "ZEALOT_BRUISER_MONSTER",
     [(100, 65000, 2500)], "zealot", "enderman", ["aggressive"], ["end_zealot"], ["ender", "enderman"]),
    ("end_voidling_fanatic", "Voidspawn Fanatic", "ENDERMAN", "VOIDLING_FANATIC_MONSTER",
     [(85, 750000, 1800)], "voidling_fanatic", "enderman", ["aggressive"], [], ["ender", "enderman"]),      # dmg TUNE
    ("end_voidling_extremist", "Voidspawn Extremist", "ENDERMAN", "VOIDLING_EXTREMIST_MONSTER",
     [(100, 8000000, 3000)], "voidling_extremist", "enderman", ["aggressive", "no_knockback"], [], ["ender", "enderman"]),  # dmg TUNE
    ("end_stone_protector", "End Stone Colossus", "IRON_GOLEM", "ENDSTONE_PROTECTOR_BOSS",
     [(100, 5000000, 1500)], "end_stone_protector", None, ["aggressive", "boss", "no_knockback"], ["end_protector"], ["ender", "construct"]),
]

# research/03 sec 14.3 - variant, HP, damage
DRAGONS = [
    ("young", "Young", 7500000, 1100), ("old", "Old", 15000000, 1100), ("protector", "Protector", 9000000, 1100),
    ("strong", "Strong", 9000000, 1100), ("unstable", "Unstable", 9000000, 1100), ("wise", "Wise", 9000000, 2200),
    ("superior", "Superior", 12000000, 1650), ("holy", "Holy", 9000000, 1100),
]

mobs = []
for (mid, name, entity, page, levels, family, slayer, flags, behaviours, types) in MOBS:
    table = drops_of(page)
    for (lvl, hp, dmg) in levels:
        coins, xp, rows = pick(table, lvl)
        if page == "RAIN_SLIME_MONSTER" and mid == "den_toxic_rain_slime":
            rows = [{"item": "SLIME_BALL", "min": 6, "max": 10, "chance": 1.0},
                    {"item": "ENCHANTED_SLIME_BALL", "min": 1, "max": 1, "chance": 0.05},
                    {"item": "POISON_SAMPLE", "min": 1, "max": 1, "chance": 0.2}]          # TUNE: reference table unpublished
            coins, xp = 12, 30
        if mid == "den_cave_spider":
            coins, xp = 3, 6                                                                 # TUNE: NEU page has no level
        if mid == "den_silverfish":
            coins, xp = (1, 3) if lvl < 10 else (5, 15)
        if mid == "end_zealot":
            # the plain page row; ender-chest and special variants are rolled at spawn by behaviour "end_zealot"
            coins, xp, rows = table.get(55, (15, 40, []))
        d = {"id": f"{mid}_{lvl}" if len(levels) > 1 or mid in ("den_arachne", "den_broodmother", "end_zealot") else mid,
             "name": name, "level": lvl, "entity": entity, "health": hp, "damage": dmg,
             "combatXp": xp, "coins": coins, "drops": [r for r in rows if not r.get("perEye")],
             "family": family, "behaviours": list(behaviours), "flags": list(flags),
             "extra": {"types": list(types), "mobTypes": list(types), "zone": "spiders_den" if mid.startswith("den_") else "the_end"}}
        if slayer:
            d["slayer"] = slayer
        if mid == "end_zealot_bruiser":
            d["drops"].append({"item": "SUMMONING_EYE", "min": 1, "max": 1, "chance": round(1 / 380, 6), "magicFind": False, "label": "CRAZY_RARE"})
        if mid == "end_obsidian_defender":
            d["equipment"] = {"HAND": "OBSIDIAN"}
        if mid == "end_seer":
            d["equipment"] = {"HEAD": "ENDER_EYE", "HAND": "BOW"}
        if mid == "den_spider_jockey" or mid == "den_flint_skeleton":
            d["equipment"] = {"HAND": "BOW"} if mid == "den_flint_skeleton" else {}
        if mid == "end_stone_protector":
            d["magicResistance"] = 0.0
        if "boss" in flags and mid != "den_broodmother":
            d["damageCap"] = 0
        mobs.append(d)

# zealot variants keep their NEU rows for the behaviour to roll on death
zt = drops_of("ZEALOT_MONSTER")
ZEALOT_EXTRA = {"chest": [], "special": []}
for lvl, (c, x, rows) in zt.items():
    pass
neu_z = neu("ZEALOT_MONSTER")
for r in neu_z.get("recipes", []):
    if r.get("type") != "drops":
        continue
    nm = SECT.sub("", r.get("name", ""))
    key = "chest" if "Enderchest" in nm else "special" if "Special" in nm else None
    if not key:
        continue
    for dr in r["drops"]:
        iid = dr["id"].split(":")[0]
        if iid.startswith("ATTRIBUTE") or iid.startswith("DYE") or iid in ("ENDER_PEARL", "ENCHANTED_ENDER_PEARL") and key == "special":
            continue
        if iid == "ENDER_PEARL":
            continue
        ch = dr["chance"].strip()
        ZEALOT_EXTRA[key].append({"item": iid, "chance": float(ch[:-1]) / 100.0})

for (vid, vname, hp, dmg) in DRAGONS:
    page = vid.upper() + "_ENDER_DRAGON_BOSS"
    t = drops_of(page) if neu(page) else drops_of("OLD_ENDER_DRAGON_BOSS")
    coins, xp, rows = pick(t, 100)
    d = {"id": "end_dragon_" + vid, "name": vname + " Dragon", "level": 100, "entity": "ENDER_DRAGON", "health": hp,
         "damage": dmg, "combatXp": xp or 300, "coins": coins or 1000, "drops": [],
         "family": "dragon", "behaviours": ["end_dragon"], "flags": ["boss", "aggressive", "no_knockback", "fire_immune"],
         "extra": {"types": ["ender", "dragon", "airborne"], "mobTypes": ["ender", "dragon", "airborne"], "variant": vid, "zone": "the_end",
                   "loot": rows}}
    mobs.append(d)

json.dump(mobs, open(os.path.join(OUT, "mobs.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
json.dump(ZEALOT_EXTRA, open(os.path.join(OUT, "zealot_variants.json"), "w", encoding="utf-8"), indent=1)

# ------------------------------------------------------------------------------------------------ spawn rules
# research/03 sec 31.2: low areas cap 10-20 per sub-area, respawn 10-30 s; elite 4-8, 30-60 s; Zealots wave of 16 / 11 s.
S = []


def rule(rid, island, prefix, mob, mx, resp, cond=None, rng=None):
    r = {"id": "combatzones_" + rid, "island": island, "anchors": [prefix + "*"], "mob": mob, "max": mx, "respawnSeconds": resp}
    if cond:
        r["condition"] = cond
    if rng:
        r["activationRange"] = rng
    S.append(r)


# Spider's Den mountain bands are HUNTING GROUNDS (DenGenerator.putGrounds): anchors
# spawn.mob.<band>.<ground>.<n>, one rule set per ground, so a kill respawns in the ground it happened in
# (core cycles a rule's anchors round-robin; one island-wide anchor list sent respawns anywhere on a
# 560-block ring -- playtest-loops 2026-10-03). Ground letters must match DenGenerator.*_GROUNDS.
GROUND_RANGE = 48
for g in "abcd":
    p = "spawn.mob.low." + g + "."
    rule("den_low_" + g + "_splitter2", "spiders_den", p, "den_splitter_spider_2", 2, 10, rng=GROUND_RANGE)
    rule("den_low_" + g + "_splitter", "spiders_den", p, "den_splitter_spider_4", 2, 10, rng=GROUND_RANGE)
    rule("den_low_" + g + "_dasher", "spiders_den", p, "den_dasher_spider_4", 2, 10, rng=GROUND_RANGE)
    rule("den_low_" + g + "_dasher6", "spiders_den", p, "den_dasher_spider_6", 1, 12, rng=GROUND_RANGE)
    rule("den_low_" + g + "_weaver", "spiders_den", p, "den_weaver_spider_3", 1, 12, rng=GROUND_RANGE)
    rule("den_low_" + g + "_weaver5", "spiders_den", p, "den_weaver_spider_5", 1, 12, rng=GROUND_RANGE)
    rule("den_low_" + g + "_jockey", "spiders_den", p, "den_spider_jockey_3", 1, 15, rng=GROUND_RANGE)
    if g in "bd":
        rule("den_low_" + g + "_voracious", "spiders_den", p, "den_voracious_spider_10", 1, 25, rng=GROUND_RANGE)
for g in "abc":
    p = "spawn.mob.mid." + g + "."
    rule("den_mid_" + g + "_splitter", "spiders_den", p, "den_splitter_spider_42", 2, 12, rng=GROUND_RANGE)
    rule("den_mid_" + g + "_dasher", "spiders_den", p, "den_dasher_spider_42", 2, 12, rng=GROUND_RANGE)
    rule("den_mid_" + g + "_weaver", "spiders_den", p, "den_weaver_spider_42", 2, 12, rng=GROUND_RANGE)
    rule("den_mid_" + g + "_jockey", "spiders_den", p, "den_spider_jockey_42", 1, 20, rng=GROUND_RANGE)
    if g == "a":
        rule("den_mid_" + g + "_voracious", "spiders_den", p, "den_voracious_spider_42", 1, 25, rng=GROUND_RANGE)
for g in "ab":
    p = "spawn.mob.high." + g + "."
    rule("den_high_" + g + "_splitter", "spiders_den", p, "den_splitter_spider_45", 2, 15, rng=GROUND_RANGE)
    rule("den_high_" + g + "_dasher", "spiders_den", p, "den_dasher_spider_45", 2, 15, rng=GROUND_RANGE)
    rule("den_high_" + g + "_weaver", "spiders_den", p, "den_weaver_spider_45", 2, 15, rng=GROUND_RANGE)
rule("den_nest_splitter", "spiders_den", "spawn.mob.nest.", "den_splitter_spider_50", 3, 25)
rule("den_nest_dasher", "spiders_den", "spawn.mob.nest.", "den_dasher_spider_50", 3, 25)
rule("den_nest_weaver", "spiders_den", "spawn.mob.nest.", "den_weaver_spider_50", 2, 25)
rule("den_gravel", "spiders_den", "spawn.mob.gravel.", "den_flint_skeleton", 8, 10)
rule("den_burrow_keeper", "spiders_den", "spawn.mob.burrow.", "den_arachnes_keeper", 4, 45)
rule("den_burrow_cave", "spiders_den", "spawn.mob.burrow.", "den_cave_spider", 4, 15)
rule("den_rain_slime", "spiders_den", "spawn.mob.slime.", "den_rain_slime", 10, 10, "event:spiders_den_rain")
rule("den_toxic_slime", "spiders_den", "spawn.mob.slime.", "den_toxic_rain_slime", 4, 20, "event:spiders_den_thunder")
rule("end_main_42", "the_end", "spawn.mob.main.", "end_enderman_42", 8, 15)
rule("end_main_45", "the_end", "spawn.mob.main.", "end_enderman_45", 6, 15)
rule("end_main_50", "the_end", "spawn.mob.main.", "end_enderman_50", 5, 20)
rule("end_mite_37", "the_end", "spawn.mob.mite.", "end_endermite_37", 5, 15)
rule("end_mite_40", "the_end", "spawn.mob.mite.", "end_endermite_40", 4, 15)
rule("end_seer", "the_end", "spawn.mob.seer.", "end_seer", 6, 30)
rule("end_defender", "the_end", "spawn.mob.defender.", "end_obsidian_defender", 8, 30)
rule("end_zealot", "the_end", "spawn.mob.zealot.", "end_zealot_55", 16, 11)
rule("end_nestmite", "the_end", "spawn.mob.nestmite.", "end_nest_endermite", 5, 20)
rule("end_bruiser", "the_end", "spawn.mob.bruiser.", "end_zealot_bruiser", 8, 20)
rule("end_void_fanatic", "the_end", "spawn.mob.void.", "end_voidling_fanatic", 6, 30)
rule("end_void_extremist", "the_end", "spawn.mob.void.", "end_voidling_extremist", 1, 120)
json.dump(S, open(os.path.join(OUT, "spawns.json"), "w", encoding="utf-8"), indent=1)

print("items", len(items), "mobs", len(mobs), "spawn rules", len(S))
