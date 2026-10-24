#!/usr/bin/env python3
"""Generate the dungeons module's data (floors, mobs, items) from research 04 sec 3.

    py -3 tools/gen/dungeons/gen_dungeons.py

Writes plugin/src/main/resources/data/dungeons/{floors,mobs,items}.json.

Numbers with a research source cite it inline (R = research/04-slayers-dungeons-kuudra.md). Anything the research
marks (?) or does not have is labelled TUNE. Names are OUR names (docs/names/dungeons.md); ids keep the reference ids.
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "dungeons")
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")

ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII"]

# Puzzle floors (R sec 3.9 "Floors"): id -> lowest floor number
PUZZLES = {"tic_tac_toe": 0, "three_strangers": 0, "creeper_beams": 0, "water_board": 0, "teleport_maze": 0,
           "higher_lower": 2, "boulder": 3, "ice_path": 3, "quiz": 4, "ice_fill": 7}

# Normal floors. grid/cata/secret% from R sec 3.2; puzzles + wither doors from R sec 3.6 step 4/7 (some (?));
# speed limits R sec 3.2; boss HP/dmg R sec 3.13.
# XP (audit C9, 2026-10-06): the wiki floor pages' infobox `base_xp` (hypixelskyblock.minecraft.wiki, raw wikitext):
# Entrance 50, F1 80, F2 160, F3 400, F4 2,400, F5 2,400, F6 "5k" (4,880 per SkyblockMoney/work/combat.md, the
# adjectils formula's S+ base; the wiki rounds it), F7 30k. It used to be the older table x1.5 (F6 6,000 = +23%).
# F4 = F5 = 2,400 is what the wiki states for both; it looks odd but nothing contradicts it.
FLOORS = [
    # id  n  gw gh cata puzMin puzMax wMin wMax trap mini speed secret  hp    dmg   xp      blood theme   mpc
    ("E", 0, 4, 4, 0, 1, 2, 1, 1, 0, 1, 600, 0.30, 0.35, 0.5, 50, 4, "crypt", 2),
    ("F1", 1, 4, 5, 1, 2, 2, 1, 2, 0, 1, 600, 0.30, 1.0, 1.0, 80, 5, "crypt", 3),
    ("F2", 2, 5, 5, 3, 2, 3, 1, 2, 0, 1, 600, 0.40, 1.8, 1.4, 160, 6, "crypt", 3),
    ("F3", 3, 5, 5, 5, 2, 3, 1, 2, 1, 1, 600, 0.50, 3.0, 2.0, 400, 7, "ruin", 3),
    ("F4", 4, 6, 5, 9, 3, 4, 2, 3, 1, 2, 720, 0.60, 5.0, 2.8, 2400, 8, "ruin", 4),
    ("F5", 5, 6, 5, 14, 3, 4, 2, 3, 1, 2, 600, 0.70, 8.0, 3.8, 2400, 9, "deep", 4),
    ("F6", 6, 6, 6, 19, 3, 4, 2, 3, 1, 2, 720, 0.85, 13.0, 5.0, 4880, 10, "deep", 4),
    ("F7", 7, 6, 6, 24, 3, 4, 3, 5, 1, 2, 840, 1.00, 22.0, 7.0, 30000, 12, "wither", 4),
]
# Master Mode: cata req, speed limit (R sec 3.2), XP (R sec 3.16)
# XP: wiki base_xp2 M1 10k, M2 15k, M3 60k (was 36,500), M5 70k (its history: 75k -> 70k on 2021-04-30); M4 / M6 / M7
# have no base_xp2 on the wiki and keep the older table (48,500 / 100,000 / 300,000), unverified.
MASTER = {1: (24, 480, 10000), 2: (26, 480, 15000), 3: (28, 480, 60000), 4: (30, 540, 48500),
          5: (32, 480, 70000), 6: (34, 540, 100000), 7: (36, 840, 300000)}
# Wood chest Undead / Wither per floor number (wiki: F1 3/4, F2 5/6, F3 5/8, F4 10/8, F5 10/10, F6 10/12, F7 10/15)
WOOD_ESSENCE = {0: (1, 1), 1: (3, 4), 2: (5, 6), 3: (5, 8), 4: (10, 8), 5: (10, 10), 6: (10, 12), 7: (10, 15)}
MASTER_HP = 60.0   # TUNE: R sec 3.10 "Master Mode x~100-300 (?)"; bosses use their own table below
MASTER_DMG = 6.0   # TUNE

# floor -> (boss key, our boss name, entity, normal HP, master HP, normal dmg, master dmg, collection)
# HP: R sec 3.13 [W]. F4 is hit-based in the reference (4 / 6 Spirit Bow hits): the fallback gives it HP (TUNE).
# F7 fallback uses Maxor's phase HP (the full four-phase fight is agent B's). Damage marked (?) in R is TUNE.
BOSSES = {
    1: ("F1", "Grinko", "ZOMBIE", 250_000, 200_000_000, 2_664, 45_000, "BOSS_BONZO"),
    2: ("F2", "Veilwright", "WITHER_SKELETON", 1_000_000, 375_000_000, 4_000, 70_000, "BOSS_SCARF"),
    3: ("F3", "The Lecturer", "SKELETON", 3_000_000, 600_000_000, 6_640, 150_000, "BOSS_THE_PROFESSOR"),
    4: ("F4", "Bramblemaw", "RAVAGER", 4_000_000, 600_000_000, 8_000, 160_000, "BOSS_THORN"),
    5: ("F5", "Lurid", "VINDICATOR", 7_000_000, 600_000_000, 10_000, 180_000, "BOSS_LIVID"),
    6: ("F6", "Gorrak", "ZOMBIE", 40_000_000, 800_000_000, 14_000, 220_000, "BOSS_SADAN"),
    7: ("F7", "Morvane", "WITHER_SKELETON", 100_000_000, 800_000_000, 20_000, 300_000, "BOSS_NECRON"),
}

# Room mobs (R sec 3.10 "Regular dungeon mobs"): id, our name, entity, base HP, dmg, floors, combat xp, equipment.
# Base stats are F1-strength numbers (TUNE: R gives no per-mob stats); floors scale them by hpScale/dmgScale.
ROOM_MOBS = [
    ("dungeon_zombie_grunt", "Zombie Grunt", "ZOMBIE", 2000, 120, (0, 3), 8, {"HEAD": "LEATHER_HELMET", "HAND": "WOODEN_SWORD"}),
    ("dungeon_skeleton_grunt", "Skeleton Grunt", "SKELETON", 1600, 110, (0, 7), 8, {"HAND": "BOW"}),
    ("dungeon_crypt_lurker", "Crypt Lurker", "ZOMBIE", 2400, 140, (0, 2), 9, {"CHEST": "CHAINMAIL_CHESTPLATE"}),
    ("dungeon_crypt_souleater", "Crypt Souleater", "HUSK", 3000, 160, (0, 2), 10, {"HAND": "STONE_SWORD"}),
    ("dungeon_lonely_spider", "Lonely Spider", "SPIDER", 1500, 130, (1, 7), 8, {}),
    ("dungeon_cellar_spider", "Cellar Spider", "CAVE_SPIDER", 900, 90, (1, 7), 6, {}),
    ("dungeon_zombie_soldier", "Zombie Soldier", "ZOMBIE", 4000, 180, (2, 7), 12, {"HEAD": "IRON_HELMET", "CHEST": "IRON_CHESTPLATE", "HAND": "IRON_SWORD"}),
    ("dungeon_zombie_knight", "Zombie Knight", "ZOMBIE", 5500, 220, (2, 7), 14, {"HEAD": "GOLDEN_HELMET", "CHEST": "GOLDEN_CHESTPLATE", "HAND": "GOLDEN_SWORD"}),
    ("dungeon_zombie_commander", "Zombie Commander", "ZOMBIE", 7000, 260, (2, 7), 18, {"HEAD": "DIAMOND_HELMET", "CHEST": "DIAMOND_CHESTPLATE", "HAND": "DIAMOND_SWORD"}),
    ("dungeon_super_tank_zombie", "Super Tank Zombie", "ZOMBIE", 12000, 200, (2, 7), 20, {"HEAD": "NETHERITE_HELMET", "CHEST": "NETHERITE_CHESTPLATE", "LEGS": "NETHERITE_LEGGINGS"}),
    ("dungeon_deathmite", "Deathmite", "ENDERMITE", 600, 150, (2, 7), 5, {}),
    ("dungeon_skeleton_master", "Skeleton Master", "SKELETON", 5000, 240, (3, 7), 16, {"HEAD": "IRON_HELMET", "HAND": "BOW"}),
    ("dungeon_bonelord", "Bonelord", "WITHER_SKELETON", 7000, 280, (3, 7), 20, {"HAND": "STONE_SWORD"}),
    ("dungeon_super_archer", "Super Archer", "SKELETON", 4000, 350, (4, 7), 18, {"HEAD": "CHAINMAIL_HELMET", "HAND": "BOW"}),
    ("dungeon_sniper", "Sniper", "STRAY", 3500, 450, (4, 7), 18, {"HAND": "BOW"}),
    ("dungeon_witherbinder", "Witherbinder", "WITHER_SKELETON", 9000, 320, (5, 7), 24, {"HAND": "BLAZE_ROD"}),
    ("dungeon_veilwraith", "Veilwraith", "ENDERMAN", 12000, 400, (5, 7), 26, {}),
    ("dungeon_zombie_lord", "Zombie Lord", "ZOMBIE", 20000, 450, (6, 7), 32, {"HEAD": "NETHERITE_HELMET", "HAND": "NETHERITE_SWORD"}),
    ("dungeon_skeleton_lord", "Skeleton Lord", "SKELETON", 18000, 480, (6, 7), 32, {"HEAD": "NETHERITE_HELMET", "HAND": "BOW"}),
    ("dungeon_wither_guard", "Wither Guard", "WITHER_SKELETON", 25000, 500, (7, 7), 40, {"HEAD": "NETHERITE_HELMET", "HAND": "NETHERITE_SWORD"}),
    ("dungeon_wither_miner", "Wither Miner", "WITHER_SKELETON", 22000, 550, (7, 7), 40, {"HAND": "NETHERITE_PICKAXE"}),
]

# Minibosses (R sec 3.10): id, our name, entity, base HP, dmg, first floor, behaviour, equipment. TUNE stats.
# Sized by TIME, not hits (playtest-endgame): they swing every second plus a special (model.Balance.pattern), and
# 40000/400 won the F1 duel against a 1,079 HP / 678 Def player. BalanceTest wants the 588/630, 2,000-DPS reference
# to outlast the F1 miniboss twice over; the other four keep their old ratios to the Fallen Explorer.
MINIBOSSES = [
    ("dungeon_fallen_explorer", "Fallen Explorer", "ZOMBIE", 25000, 140, 0, "dungeons_explorer",
     {"HEAD": "LEATHER_HELMET", "CHEST": "LEATHER_CHESTPLATE", "LEGS": "LEATHER_LEGGINGS", "FEET": "LEATHER_BOOTS", "HAND": "IRON_SWORD"}),
    ("dungeon_gilded_monarch", "Gilded Monarch", "ZOMBIE", 50000, 175, 2, "dungeons_monarch",
     {"HEAD": "GOLDEN_HELMET", "CHEST": "GOLDEN_CHESTPLATE", "LEGS": "GOLDEN_LEGGINGS", "FEET": "GOLDEN_BOOTS", "HAND": "GOLDEN_SWORD"}),
    ("dungeon_furious_excavator", "Furious Excavator", "ZOMBIE", 28000, 160, 3, "dungeons_excavator",
     {"HEAD": "DIAMOND_HELMET", "CHEST": "DIAMOND_CHESTPLATE", "LEGS": "DIAMOND_LEGGINGS", "FEET": "DIAMOND_BOOTS", "HAND": "DIAMOND_SWORD"}),
    ("dungeon_shade_stalker", "Shade Stalker", "WITHER_SKELETON", 22000, 210, 4, "dungeons_shade",
     {"HAND": "NETHERITE_SWORD"}),
    ("dungeon_frostbound_explorer", "Frostbound Explorer", "STRAY", 31000, 175, 4, "dungeons_frost",
     {"HEAD": "ICE", "HAND": "DIAMOND_HOE"}),
]

# Blood mobs (R sec 3.10 "The Watcher", 18 names, renamed). TUNE stats; later entries need higher floors.
BLOOD = [
    ("defiler", "Defiler", "ZOMBIE"), ("maniac", "Maniac", "VINDICATOR"), ("harvester", "Harvester", "WITHER_SKELETON"),
    ("gnawer", "Gnawer", "HUSK"), ("silent_one", "Silent One", "SKELETON"), ("sludge", "Sludge", "DROWNED"),
    ("rotter", "Rotter", "ZOMBIE"), ("twisted", "Twisted", "STRAY"), ("bloodsucker", "Bloodsucker", "HUSK"),
    ("weeper", "Weeper", "SKELETON"), ("leechling", "Leechling", "ZOMBIE"), ("kindler", "Kindler", "WITHER_SKELETON"),
    ("bonehead", "Bonehead", "SKELETON"), ("mister_gone", "Mister Gone", "ZOMBIE"), ("darkhelm", "Darkhelm", "WITHER_SKELETON"),
    ("chiller", "Chiller", "STRAY"), ("shambler", "Shambler", "ZOMBIE"), ("lost_soul", "Lost Soul", "VEX"),
]


# Part B: what room mobs drop (the genre's dungeon mobs drop their own gear). Chances are TUNE (research gives none);
# ids are armour/weapons-module or dungeons gear ids. A piece is a 1-in-100 (sets) or 1-in-200 (weapons) roll.
SET = lambda p, c: [{"item": p + "_" + s, "chance": c} for s in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")]
DROPS = {
    "dungeon_zombie_grunt": SET("ROTTEN", 0.01),
    "dungeon_crypt_lurker": SET("ROTTEN", 0.012),
    "dungeon_skeleton_grunt": SET("SKELETON_GRUNT", 0.01),
    "dungeon_lonely_spider": SET("BOUNCY", 0.004),
    "dungeon_zombie_soldier": SET("ZOMBIE_SOLDIER", 0.01) + [{"item": "ZOMBIE_SOLDIER_CUTLASS", "chance": 0.005}],
    "dungeon_zombie_knight": SET("ZOMBIE_KNIGHT", 0.01) + [{"item": "ZOMBIE_KNIGHT_SWORD", "chance": 0.005}],
    "dungeon_zombie_commander": SET("ZOMBIE_COMMANDER", 0.01) + [{"item": "ZOMBIE_COMMANDER_WHIP", "chance": 0.005}],
    "dungeon_super_tank_zombie": SET("SUPER_HEAVY", 0.008) + SET("HEAVY", 0.01),
    "dungeon_skeleton_master": SET("SKELETON_MASTER", 0.01) + SET("SKELETON_SOLDIER", 0.01),
    "dungeon_bonelord": SET("SKELETOR", 0.008) + [{"item": "BONE_BOOMERANG", "chance": 0.002}],
    "dungeon_super_archer": SET("SKELETON_SOLDIER", 0.01) + [{"item": "MACHINE_GUN_BOW", "chance": 0.004}],
    "dungeon_sniper": [{"item": "SNIPER_HELMET", "chance": 0.01}, {"item": "SNIPER_BOW", "chance": 0.006}],
    "dungeon_witherbinder": SET("CRYPT_WITHERLORD", 0.006) + [{"item": "CRYPT_WITHERLORD_SWORD", "chance": 0.003}],
    "dungeon_veilwraith": [{"item": "FEL_PEARL", "chance": 0.05}, {"item": "FEL_SKULL", "chance": 0.002}],
    "dungeon_zombie_lord": SET("ZOMBIE_LORD", 0.006),
    "dungeon_skeleton_lord": SET("SKELETON_LORD", 0.006) + [{"item": "SKELETON_LORD_BOW", "chance": 0.003}],
    "dungeon_crypt_souleater": [{"item": "CRYPT_BOW", "chance": 0.004}, {"item": "CRYPT_DREADLORD_SWORD", "chance": 0.004}],
    "dungeon_fallen_explorer": [{"item": "DUNGEON_CHEST_KEY", "chance": 0.02}],
    "dungeon_furious_excavator": [{"item": "DUNGEON_CHEST_KEY", "chance": 0.03}],
    "dungeon_shade_stalker": [{"item": "SILENT_DEATH", "chance": 0.01}],
    "dungeon_frostbound_explorer": [{"item": "ICE_SPRAY_WAND", "chance": 0.01}],
    "dungeon_gilded_monarch": [{"item": "MIDAS_JEWEL", "chance": 0.01}],
}


def mob(id_, name, entity, hp, dmg, xp, level, flags, equipment=None, behaviours=None, family=None, extra=None):
    d = {"id": id_, "name": name, "entity": entity, "level": level, "health": hp, "damage": dmg,
         "combatXp": xp, "coins": 0, "drops": [dict(min=1, max=1, **d) for d in DROPS.get(id_, [])], "family": family or id_, "flags": flags,
         "equipment": equipment or {}, "behaviours": behaviours or []}
    if extra:
        d["extra"] = extra
    return d


def floors():
    out = []
    for (fid, n, gw, gh, cata, pmin, pmax, wmin, wmax, trap, mini, speed, secret, hp, dmg, xp, blood, theme, mpc) in FLOORS:
        out.append(floor_def(fid, n, False, gw, gh, cata, pmin, pmax, wmin, wmax, trap, mini, speed, secret, hp, dmg, xp, blood, theme, mpc))
    for n in range(1, 8):
        base = FLOORS[n]
        cata, speed, xp = MASTER[n]
        (_, _, gw, gh, _, pmin, pmax, wmin, wmax, trap, mini, _, _, hp, dmg, _, blood, theme, mpc) = base
        out.append(floor_def("M%d" % n, n, True, gw, gh, cata, pmin, pmax, wmin, wmax, trap, mini, speed, 1.0,
                             hp * MASTER_HP, dmg * MASTER_DMG, xp, blood, theme, mpc))
    return out


def floor_def(fid, n, master, gw, gh, cata, pmin, pmax, wmin, wmax, trap, mini, speed, secret, hp, dmg, xp, blood, theme, mpc):
    name = "Entrance" if n == 0 else ("Master Mode Floor " if master else "Floor ") + ROMAN[n]
    d = {"id": fid, "name": name, "number": n, "master": master, "gridW": gw, "gridH": gh,
         "puzzlesMin": pmin, "puzzlesMax": pmax, "witherDoorsMin": wmin, "witherDoorsMax": wmax,
         "trapRooms": trap, "trapChance": 0.75, "minibossRooms": mini, "cataReq": cata,
         "speedLimitSec": speed, "secretPct": secret, "hpScale": hp, "dmgScale": dmg, "baseXp": xp,
         "bloodMobs": blood, "bloodSpawnSeconds": 3.0, "theme": theme, "mobsPerCell": mpc,
         "firstCompletionSbXp": 50 if master else (10 if n == 0 else 20)}
    if n == 0:
        d["requirement"] = "skill:combat:15"        # R sec 3.1: Catacombs Entrance requires Combat 15
    elif master:
        d["requirement"] = "dungeon_completion:F7:1"   # R sec 3.2: M1 = Cata 24 + F7 beaten
    if n > 0:
        key, bname, ent, hpn, hpm, dn, dm, coll = BOSSES[n]
        d.update({"bossKey": key, "bossName": bname, "bossMob": "dungeon_guardian_%d" % n,
                  "bossHp": hpm if master else hpn, "bossDamage": dm if master else dn, "bossCollection": coll})
    d["mobPool"] = [m[0] for m in ROOM_MOBS if m[5][0] <= n <= m[5][1]]
    d["minibossPool"] = [m[0] for m in MINIBOSSES if m[5] <= n]
    d["bloodPool"] = ["dungeon_blood_" + b[0] for b in BLOOD[: min(len(BLOOD), 6 + n * 2)]]
    d["puzzlePool"] = [p for p, lo in PUZZLES.items() if lo <= n]
    # Wood chest essence (audit C11, wiki floor pages): a FIXED Undead + Wither pair per floor. Research read F7's
    # "1-15 Wither" off the table, but the "1" is the separate 1x Wither Essence roll (0.85%), not a range. The free
    # chest fallback gives exactly woodWitherMax. Entrance and Master Mode (x2) are not on the wiki: TUNE.
    wu, ww = WOOD_ESSENCE.get(n, (1, 1))
    d["woodUndead"] = wu * (2 if master else 1)
    d["woodWitherMax"] = ww * (2 if master else 1)
    return d


def mobs():
    out = []
    for (mid, name, ent, hp, dmg, floors_, xp, eq) in ROOM_MOBS:
        level = 10 + 10 * floors_[0]
        out.append(mob(mid, "✯ " + name, ent, hp, dmg, xp, level, ["aggressive"], eq, family=mid))
    out.append(mob("dungeon_crypt_undead", "Crypt Undead", "ZOMBIE", 1500, 100, 6, 20, ["aggressive"],
                   {"HEAD": "CHAINMAIL_HELMET"}))
    for (mid, name, ent, hp, dmg, lo, beh, eq) in MINIBOSSES:
        out.append(mob(mid, name, ent, hp, dmg, 60 + 40 * lo, 40 + 20 * lo, ["aggressive", "no_knockback"], eq, [beh]))
    for i, (bid, name, ent) in enumerate(BLOOD):
        out.append(mob("dungeon_blood_" + bid, name, ent, 8000 + 600 * i, 300 + 20 * i, 25, 60 + 5 * i,
                       ["aggressive"], {"HAND": "IRON_SWORD"} if ent in ("ZOMBIE", "HUSK", "VINDICATOR", "WITHER_SKELETON") else {},
                       family="dungeon_blood"))
    out.append(mob("dungeon_puzzle_blaze", "Pillar Blaze", "BLAZE", 100, 0, 0, 10, ["passive", "no_ai", "silent"]))
    out.append(mob("dungeon_mimic", "Mimic", "ZOMBIE", 120000, 600, 120, 100, ["aggressive", "baby", "no_knockback"],
                   {"HEAD": "CHEST"}))
    for n, (key, bname, ent, hpn, hpm, dn, dm, coll) in BOSSES.items():
        # health/damage are set per run from the floor (normal or master); these are the normal values
        out.append(mob("dungeon_guardian_%d" % n, bname, ent, hpn, dn, 0, 100 + 20 * n,
                       ["boss", "aggressive", "no_knockback", "fire_immune"], {}, ["dungeons_boss"], family="dungeon_boss_" + key.lower()))
    return out


def neu_skull(item_id):
    path = os.path.join(NEU, item_id + ".json")
    if not os.path.exists(path):
        return None
    tag = json.load(open(path, encoding="utf-8")).get("nbttag", "")
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', tag)
    return m.group(1) if m else None


def items():
    ab = lambda i, n, cd, lore: {"id": i, "name": n, "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": cd, "lore": lore}
    only = "&cDungeons only!"
    out = [
        {"id": "SPIRIT_LEAP", "name": "Soul Leap", "material": "ENDER_PEARL", "rarity": "RARE", "category": "CONSUMABLE",
         "maxStack": 16, "tags": ["dungeon_only", "no_ah"], "lore": ["", only],
         "abilities": [ab("dungeons_spirit_leap", "Soul Leap", 5,
                          ["&7Leap to any living teammate. You are", "&7untouchable for &a1 &7second after landing."])]},
        {"id": "SUPERBOOM_TNT", "name": "Megablast TNT", "material": "TNT", "rarity": "RARE", "category": "CONSUMABLE",
         "maxStack": 64, "tags": ["dungeon_only"], "lore": ["&7Blows open cracked brick walls and", "&7the lids of crypts.", "", only],
         "abilities": [ab("dungeons_superboom", "Detonate", 1, ["&7Explode the block you are looking at", "&7(up to &a6 &7blocks away)."])]},
        {"id": "DUNGEON_DECOY", "name": "Decoy", "material": "POLAR_BEAR_SPAWN_EGG", "rarity": "UNCOMMON", "category": "CONSUMABLE",
         "maxStack": 64, "tags": ["dungeon_only"], "lore": ["&7Runs about and pulls nearby monsters", "&7away from you for &a10 &7seconds.", "", only],
         "abilities": [ab("dungeons_decoy", "Distract", 5, ["&7Place a decoy that lures mobs."])]},
        {"id": "INFLATABLE_JERRY", "name": "Inflatable Dummy", "material": "POLAR_BEAR_SPAWN_EGG", "rarity": "COMMON", "category": "CONSUMABLE",
         "maxStack": 64, "tags": ["dungeon_only"], "lore": ["&7A puffed-up stand-in. Monsters near it", "&7forget you for &a5 &7seconds.", "", only],
         "abilities": [ab("dungeons_dummy", "Inflate", 5, ["&7Place a short-lived decoy."])]},
        {"id": "REVIVE_STONE", "name": "Revive Stone", "material": "PLAYER_HEAD", "rarity": "LEGENDARY", "category": "CONSUMABLE",
         "maxStack": 1, "tags": ["dungeon_only", "no_ah", "no_trade"], "skull": neu_skull("REVIVE_STONE"),
         "lore": ["&7One use. &eVanishes when the run ends!", "", only],
         "abilities": [ab("dungeons_revive", "Revive", 1, ["&7Bring a fallen teammate back to life."])]},
    ]
    colors = ["#7AE36B", "#55D0FF", "#2E7CFF", "#C04CFF", "#FFB327", "#FF6A3D", "#FF2E4D"]
    rar = ["UNCOMMON", "RARE", "RARE", "EPIC", "EPIC", "LEGENDARY", "LEGENDARY"]
    for lvl in range(1, 8):
        out.append({"id": "POTION_DUNGEON_%d" % lvl, "name": "Dungeon Potion " + ROMAN[lvl], "material": "POTION",
                    "rarity": rar[lvl - 1], "category": "POTION", "color": colors[lvl - 1], "maxStack": 1,
                    "tags": ["dungeon_only"],
                    "lore": ["&7Drink inside the Catacombs for the rest", "&7of the run:",
                             "&c+%d Strength &7and &9+%d%% Crit Damage" % (5 * lvl, 5 * lvl),
                             "&c+%d Health &7and &f+%d Speed" % (20 * lvl, 2 * lvl), "", only],
                    "abilities": [ab("dungeons_potion", "Drink", 1, ["&7Drink it."])]})
    for it in out:
        if it.get("skull") is None:
            it.pop("skull", None)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, data in (("floors.json", floors()), ("mobs.json", mobs()), ("items.json", items())):
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
        print("wrote", name, len(data))


if __name__ == "__main__":
    main()
