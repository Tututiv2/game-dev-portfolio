"""
Generate the slayer module's data from the research data (never typed by hand where a source exists).

    py -3 tools/gen/slayer/gen_slayer.py

Reads
  research/data/items.json                       stats, rarity, material, skins, sell prices, wear requirements
  research/data/neu-repo/items/*_BOSS.json        per-tier boss loot (chance %, quantities), HP and damage from lore
  research/data/neu-repo/items/*_MINIBOSS.json    slayer minibosses (HP, level, token drop)
  research/data/neu-repo/items/<ID>.json          recipes + slayer_req for the gear/items this module owns
  research/data/neu-repo/constants/leveling.json  slayer XP tables
  research/data/neu-repo/constants/rngscore.json  RNG meter XP per item
  plugin/src/main/resources/data/materials/*.json  look (material/skull) of materials the catalogue already drew
and the research 04 tables (sec 2.3 master table, 2.2 gates, 2.5-2.10 level rewards and min levels), which are
transcribed below with their section numbers.

Writes plugin/src/main/resources/data/slayer/{slayers,items,items_fallback,mobs,recipes}.json and
docs/names/slayer.md.
"""
import json, os, re, glob, collections

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "research", "data")
NEU = os.path.join(RES, "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "slayer")
NAMES = os.path.join(ROOT, "docs", "names", "slayer.md")

ITEMS = {i["id"]: i for i in json.load(open(os.path.join(RES, "items.json"), encoding="utf-8"))["items"]}
LEVELING = json.load(open(os.path.join(RES, "neu-repo", "constants", "leveling.json"), encoding="utf-8"))
RNG = json.load(open(os.path.join(RES, "neu-repo", "constants", "rngscore.json"), encoding="utf-8"))["slayer"]

MATERIALS = {}
for f in glob.glob(os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "materials", "items_*.json")):
    for e in json.load(open(f, encoding="utf-8")):
        MATERIALS[e["id"]] = e

SECT = re.compile("§.")


def neu(iid):
    p = os.path.join(NEU, iid + ".json")
    if not os.path.exists(p):
        return None
    return json.load(open(p, encoding="utf-8"))


def strip(s):
    return SECT.sub("", s or "")


# ---------------------------------------------------------------------------------------------------------------
# Renames (CONTRACT 1.1). Reference proper noun -> our name. Ids never change.
# ---------------------------------------------------------------------------------------------------------------
RENAMES = []   # (id, reference, ours)


def rn(iid, ref, ours):
    RENAMES.append((iid, ref, ours))
    return ours


NPC_NAME = rn("npc:slayer_master", "Maddox the Slayer", "Brannoc the Slayer")

# ---------------------------------------------------------------------------------------------------------------
# The six slayers. Numbers: research 04 sec 2.3 (master table), 2.2 (gates), 2.4 (XP via leveling.json), level
# rewards from 2.5-2.10. Damage = boss DPS (one melee hit per second). Vampire HP is the Rift-heart number; the
# module multiplies it by dial slayer.vampire-hp-scale because there is no Rift health scale outside the Rift.
# ---------------------------------------------------------------------------------------------------------------
TYPES = [
    dict(id="zombie", ref="Revenant Horror", name="Ghoul Horror", t5name="Penitent Horror", t5ref="Atoned Horror",
         mobWord="Zombie", color="2", icon="ROTTEN_FLESH", entity="ZOMBIE",
         entities=["ZOMBIE", "HUSK", "ZOMBIE_VILLAGER", "DROWNED", "ZOMBIFIED_PIGLIN", "ZOGLIN"],
         unlock=None,
         hp=[500, 20000, 400000, 1500000, 10000000], dps=[15, 25, 120, 400, 2400], trueDps=[0, 0, 0, 0, 0],
         level=[10, 70, 310, 610, 1580],
         cost=[2000, 7500, 20000, 50000, 100000], spawnXp=[150, 1440, 2400, 4800, 6000],
         combatGate=[0, 5, 10, 15, 25], prevTier=False, t5slayer=7,
         difficulty=["Beginner", "Strong", "Challenging", "Deadly", "Excruciating"],
         levelNames=["Novice", "Apprentice", "Skilled", "Hardened", "Bulldozer", "Savage", "Deathbringer",
                     "Necrovanquisher", "Grave Lord"],
         rewards={1: {"HEALTH": 2}, 2: {"HEALTH": 2}, 3: {"HEALTH": 3}, 4: {"HEALTH": 3}, 5: {"HEALTH": 4},
                  6: {"HEALTH": 4}, 7: {"HEALTH": 5}, 8: {"HEALTH": 5, "HEALTH_REGEN": 30}, 9: {"HEALTH": 6}},
         notes={7: ["Unlocks &4Tier V &7quests"], 8: ["Zombie Brain Mixin in the Slayer Shop"]},
         abilities=[
             (1, "Life Drain", "Every &a4s &7it rips health out of you and heals itself by exactly what it took. Defense applies."),
             (2, "Pestilence", "A sickly aura hurts everyone close every second and shreds &a25% &7of their Defense."),
             (3, "Enrage", "Every &a40s &7it gets mad, then &cenraged &7for &a12s&7: faster and hitting twice as hard."),
             (5, "Redemption", "Regenerates &a0.1% &7of its health every second and shrugs off ability damage."),
             (5, "Explosive Assault", "Lobs TNT at marked circles. A hit heals it by a huge amount - keep moving."),
             (5, "Thermonuclear", "Every &a30s &7it roots itself and charges a blast that hits everything within &a7 &7blocks."),
         ]),
    dict(id="spider", ref="Tarantula Broodfather", name="Broodweaver Patriarch", t5name="Broodweaver Patriarch",
         t5ref=None, mobWord="Spider", color="4", icon="COBWEB", entity="SPIDER",
         entities=["SPIDER", "CAVE_SPIDER"],
         unlock={"type": "zombie", "tier": 2},
         hp=[1000, 30000, 900000, 2400000, 10000000], dps=[35, 55, 260, 530, 2200], trueDps=[0, 0, 0, 0, 0],
         level=[10, 80, 470, 770, 1580],
         cost=[2000, 7500, 20000, 50000, 100000], spawnXp=[250, 650, 1500, 3000, 10000],
         combatGate=[0, 5, 10, 15, 25], prevTier=False, t5slayer=7,
         difficulty=["Beginner", "Strong", "Challenging", "Debilitating", "Excruciating"],
         levelNames=["Novice", "Apprentice", "Skilled", "Hardened", "Bulldozer", "Savage", "Pest Purger",
                     "Brood Breaker", "Silk Sovereign"],
         rewards={1: {"CRIT_DAMAGE": 1}, 2: {"CRIT_DAMAGE": 1}, 3: {"CRIT_DAMAGE": 1}, 4: {"CRIT_DAMAGE": 1},
                  5: {"CRIT_DAMAGE": 2}, 6: {"CRIT_DAMAGE": 2}, 7: {"CRIT_DAMAGE": 2},
                  8: {"CRIT_DAMAGE": 3, "ALCHEMY_WISDOM": 5}, 9: {"CRIT_DAMAGE": 3}},
         notes={7: ["Unlocks &4Tier V &7quests"], 8: ["Spider Egg Mixin in the Slayer Shop"]},
         abilities=[
             (1, "Backstab", "Leaps behind you now and then. Hits from behind deal &c+20% &7(T5: &c+80%&7)."),
             (2, "Paralytic Venom", "Its bite saps your Vitality and Speed (T5: &c-75% &7each, and no Absorption)."),
             (3, "Web Cocoon", "At &a66% &7and &a33% &7health it hides in a cocoon and lays egg sacs. Pop them before they hatch!"),
             (4, "Bat Barrage", "Releases bats. Every bat alive drains a slice of your max health each second."),
             (5, "Bound in Death", "When it falls, the Elder Broodmother fuses with it into the &cTwinbound Brood&7."),
         ]),
    dict(id="wolf", ref="Sven Packmaster", name="Fenris Packlord", t5name=None, t5ref=None, mobWord="Wolf",
         color="f", icon="BONE", entity="WOLF", entities=["WOLF"],
         unlock={"type": "spider", "tier": 2},
         hp=[2000, 40000, 750000, 2000000], dps=[60, 80, 180, 440], trueDps=[0, 10, 50, 200],
         level=[20, 100, 430, 700],
         cost=[2000, 7500, 20000, 50000], spawnXp=[250, 650, 1500, 3000],
         combatGate=[0, 5, 10, 15], prevTier=False, t5slayer=0,
         difficulty=["Beginner", "Strong", "Challenging", "Deadly"],
         levelNames=["Novice", "Apprentice", "Skilled", "Hardened", "Bulldozer", "Savage", "Pack Hunter",
                     "Pack Leader", "Alpha Fang"],
         rewards={1: {"SPEED": 1}, 2: {"HEALTH": 2}, 3: {"SPEED": 1}, 4: {"HEALTH": 2}, 5: {"CRIT_DAMAGE": 1},
                  6: {"HEALTH": 3}, 7: {"CRIT_DAMAGE": 2}, 8: {"SPEED": 1}, 9: {"HEALTH": 5}},
         notes={3: ["A free &aSlayer Hotline &7(claim it in the Slayer menu)"], 8: ["Lose &a5% &7fewer coins on death",
                                                                                   "Wolf Fur Mixin in the Slayer Shop"]},
         abilities=[
             (1, "Agile", "Small and very fast - hard to pin down."),
             (2, "True Damage", "Its bites carry extra damage that ignores your Defense."),
             (3, "Call the Pups", "At &a50% &7health it stops, turns invulnerable and calls five pups. Kill them all!"),
         ]),
    dict(id="enderman", ref="Voidgloom Seraph", name="Nullshade Seraph", t5name=None, t5ref=None,
         mobWord="Enderman", color="5", icon="ENDER_PEARL", entity="ENDERMAN", entities=["ENDERMAN", "ENDERMITE"],
         unlock={"type": "wolf", "tier": 4},
         hp=[300000, 12000000, 50000000, 210000000], dps=[800, 2720, 7650, 11000], trueDps=[0, 0, 0, 0],
         level=[270, 1730, 3530, 7240],
         cost=[2000, 7500, 20000, 50000], spawnXp=[2750, 6600, 11000, 22000],
         combatGate=[0, 0, 0, 0], prevTier=True, t5slayer=0,
         difficulty=["Beginner", "Strong", "Challenging", "Guaranteed Doom"],
         levelNames=["Novice", "Apprentice", "Skilled", "Hardened", "Bulldozer", "Savage", "Voidwracker",
                     "Null Breaker", "End of Ends"],
         rewards={1: {"HEALTH": 1}, 2: {"INTELLIGENCE": 2}, 3: {"HEALTH": 2}, 4: {"INTELLIGENCE": 2}, 5: {"HEALTH": 3},
                  6: {"INTELLIGENCE": 5}, 7: {"HEALTH": 4}, 8: {"INTELLIGENCE": 4}, 9: {"HEALTH": 5}},
         notes={8: ["End Portal Fumes in the Slayer Shop"]},
         abilities=[
             (1, "Hitshield", "A shield that breaks by number of HITS, not damage. It returns at &a66% &7and &a33%&7."),
             (1, "Ferocity Ward", "While its shield is down it takes &c75% &7less extra Ferocity hits."),
             (2, "Dissonance", "Blinks next to you and blasts everything within &a6.5 &7blocks."),
             (2, "Void Glyphs", "Throws glyphs. Stand next to one before it bursts, or take a lethal blast."),
             (3, "Nukekubi Heads", "Below &a33% &7floating heads fixate on you. Look at them to pop them."),
             (4, "Heartbreak Beams", "Turns invulnerable and spins &a12 &7beams of void around itself. Dodge them."),
         ]),
    dict(id="blaze", ref="Inferno Demonlord", name="Cinder Demonlord", t5name=None, t5ref=None, mobWord="Blaze",
         color="6", icon="BLAZE_POWDER", entity="BLAZE",
         entities=["BLAZE", "MAGMA_CUBE", "WITHER_SKELETON", "PIGLIN", "PIGLIN_BRUTE", "GHAST", "ZOMBIFIED_PIGLIN"],
         unlock={"type": "enderman", "tier": 3},
         # research 2.3: Inferno HP; melee is not published (Immolate is the damage): TUNE melee
         hp=[2500000, 10000000, 45000000, 150000000], dps=[400, 1500, 4000, 9000], trueDps=[0, 0, 0, 0],
         level=[400, 1200, 2400, 5000],
         cost=[10000, 25000, 60000, 150000], spawnXp=[6000, 14400, 18000, 36000],
         combatGate=[0, 0, 0, 0], prevTier=True, t5slayer=0,
         difficulty=["Beginner", "Strong", "Challenging", "Overwhelming Despair"],
         levelNames=["Novice", "Apprentice", "Skilled", "Hardened", "Bulldozer", "Savage", "Hellsplitter",
                     "Demon Eater", "Cinder King"],
         rewards={1: {"HEALTH": 3}, 2: {"STRENGTH": 1}, 3: {"HEALTH": 4}, 4: {"TRUE_DEFENSE": 1}, 5: {"HEALTH": 5},
                  6: {"STRENGTH": 2}, 7: {"HEALTH": 6}, 8: {"TRUE_DEFENSE": 2}, 9: {"HEALTH": 7}},
         notes={8: ["Gourmet Mixin in the Slayer Shop"]},
         immolate=[[45, 65, 115], [65, 130, 190], [80, 160, 320], [385, 575, 955]],
         demons={"hp": [500000, 1750000, 5000000, 10000000], "dps": [2500, 8000, 15000, 30000]},
         abilities=[
             (1, "Immolate", "Burns everything near it every second with true damage that grows as it weakens."),
             (1, "Demon Split", "At &a50% &7(T3+: &a66% &7and &a33%&7) it splits into two demons and is invulnerable until both die."),
             (2, "Attuned Shield", "Only a dagger attuned to its shield colour hurts it. The colour shifts every &a8 &7matching hits."),
             (2, "Fire Pillars", "Raises a pillar that must take &a8 &7hits within &a7s&7. If it erupts, everyone near it dies."),
             (3, "Fire Pit Dance", "Below &a33% &7the ground lights up in patterns. Do not stand in the fire."),
         ]),
    dict(id="vampire", ref="Riftstalker Bloodfiend", name="Duskborne Bloodfiend", t5name=None, t5ref=None,
         mobWord="Vampire", color="4", icon="REDSTONE", entity="ZOMBIE",
         entities=[],
         unlock={"type": "wolf", "tier": 3},
         hp=[625, 1100, 1800, 2400, 3000], dps=[0.5, 1, 1, 1, 1.5], trueDps=[0, 0, 0, 0, 0],
         level=[60, 180, 420, 780, 1200],
         cost=[2000, 4000, 5000, 7000, 10000], spawnXp=[600, 1200, 1800, 2400, 3000],   # TUNE: no Rift mobs here
         combatGate=[0, 0, 0, 15, 25], prevTier=True, t5slayer=0,
         difficulty=["Beginner", "Strong", "Challenging", "Truly Painful", "Excruciating"],
         levelNames=["Novice", "Apprentice", "Coven Hunter", "Heartpiercer", "Night Lord"],
         rewards={1: {"RIFT_TIME": 20}, 2: {"RIFT_TIME": 30}, 3: {"RIFT_TIME": 45}, 4: {"RIFT_TIME": 60},
                  5: {"RIFT_TIME": 80}},
         notes={},
         abilities=[
             (1, "Coagulation", "Drains your blood every &a1.5s &7to heal itself."),
             (1, "Spectral Form", "Turns into a swarm of bats and reappears next to you."),
             (1, "Mania", "Turns invulnerable and paints a safe ring on the ground. Stand in it or bleed."),
             (2, "Twin Claws", "A telegraphed slam in front of it. Step to the side!"),
             (2, "Stone Wrath", "Raises blood gargoyles that sap your maximum health while they live."),
             (3, "Spring Trap", "Drops a spring on you. Hit it with alternating left and right clicks or be crushed."),
             (3, "Command", "Shouts an order - JUMP, SNEAK, LOOK UP or LOOK DOWN. Obey in time or pay."),
             (5, "Blood Ichor", "Spawns ichor that only its own Twin Claws can break. Bait the slam into it."),
             (1, "Execution", "It cannot drop below &a20% &7health except to a &aSteak Stake&7."),
         ]),
]

for t in TYPES:
    rn("boss:" + t["id"], t["ref"], t["name"])
    if t.get("t5ref"):
        rn("boss:" + t["id"] + ":5", t["t5ref"], t["t5name"])
rn("boss:spider:5b", "Conjoined Brood", "Twinbound Brood")
rn("npc:broodmother", "Primordial Broodmother", "Elder Broodmother")
rn("mob:quazii", "Quazii", "Quorrix")
rn("mob:typhoeus", "Typhoeus", "Tyrphon")
rn("mob:sven_pup", "Sven Pup", "Fenris Pup")
rn("mob:clotgoyle", "Clotgoyle", "Blood Gargoyle")

SB_XP = [15, 25, 35, 50, 75, 100, 125, 150, 150]
SLAYER_XP = [5, 25, 100, 500, 1500]          # leveling.json slayer_boss_xp
BITS = [2, 5, 8, 12, 15]                     # research 2.1
HEAL = [40, 80, 120, 160, 200]               # research 2.1, after-kill heal per second by tier
BOSS_COMBAT_XP = [50, 100, 200, 500, 500]    # NEU boss pages (Tarantula/Voidgloom/Inferno 50-500)

# min slayer level per drop (research 2.5-2.10); unlisted = 0
MINLVL = {
    "zombie": {"ZOMBIE_SLAYER_RUNE": 2, "UNDEAD_CATALYST": 3, "ENCHANTMENT_SMITE_6": 4, "BEHEADED_HORROR": 5,
               "REVENANT_CATALYST": 6, "SNAKE_RUNE": 6, "FESTERING_MAGGOT": 6, "SCYTHE_BLADE": 7, "REVENANT_VISCERA": 7,
               "SHARD_OF_THE_SHREDDED": 7, "SEVERED_HAND": 7, "WARDEN_HEART": 7},
    "spider": {"TOXIC_ARROW_POISON": 1, "SPIDER_CATALYST": 2, "BITE_RUNE": 2, "TARANTULA_CATALYST": 4,
               "ENCHANTMENT_BANE_OF_ARTHROPODS_6": 4, "FLY_SWATTER": 5, "TARANTULA_TALISMAN": 6, "VIAL_OF_VENOM": 6,
               "DARKNESS_WITHIN_RUNE": 6, "DIGESTED_MOSQUITO": 7, "SHRIVELED_WASP": 7, "ENSNARED_SNAIL": 7,
               "PRIMORDIAL_EYE": 7},
    "wolf": {"HAMSTER_WHEEL": 1, "SPIRIT_RUNE": 2, "ENCHANTMENT_CRITICAL_6": 4, "FURBALL": 4, "RED_CLAW_EGG": 5,
             "COUTURE_RUNE": 6, "OVERFLUX_CAPACITOR": 7, "GRIZZLY_BAIT": 7},
    "enderman": {"TWILIGHT_ARROW_POISON": 1, "ENDERSNAKE_RUNE": 1, "SUMMONING_EYE": 2, "ENCHANTMENT_MANA_STEAL_1": 3,
                 "TRANSMISSION_TUNER": 3, "NULL_ATOM": 4, "HAZMAT_ENDERMAN": 5, "POCKET_ESPRESSO_MACHINE": 5,
                 "ENCHANTMENT_SMARTY_PANTS_1": 5, "DRAGON_RUNE": 5, "HANDY_BLOOD_CHALICE": 6, "SINFUL_DICE": 6,
                 # audit C1 (2026-10-06): the ERE Artifact Upgrader drops from Enderman 6, not 7 (combat.md)
                 "EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER": 6, "ETHERWARP_MERGER": 7, "PET_SKIN_ENDERMAN_SLAYER": 7,
                 "JUDGEMENT_CORE": 7, "ENCHANT_RUNE": 7, "ENDSTONE_IDOL": 7},
    "blaze": {"LAVATEARS_RUNE": 1, "ARROW_BUNDLE_MAGMA": 1, "MANA_DISINTEGRATOR": 2, "SCORCHED_BOOKS": 2,
              "KELVIN_INVERTER": 3, "BLAZE_ROD_DISTILLATE": 3, "GLOWSTONE_DUST_DISTILLATE": 3, "MAGMA_CREAM_DISTILLATE": 3,
              "NETHER_STALK_DISTILLATE": 3, "CRUDE_GABAGOOL_DISTILLATE": 3, "SCORCHED_POWER_CRYSTAL": 4,
              "ARCHFIEND_DICE": 4, "ENCHANTMENT_FIRE_ASPECT_3": 5, "FIERY_BURST_RUNE": 5, "FLAWED_OPAL_GEM": 5,
              "ENCHANTMENT_ULTIMATE_REITERATE_1": 6, "HIGH_CLASS_ARCHFIEND_DICE": 7, "WILSON_ENGINEERING_PLANS": 7,
              "SUBZERO_INVERTER": 7},
    "vampire": {},
}

BOSS_FILES = {"zombie": "REVENANT_HORROR", "spider": "TARANTULA_BROODFATHER", "wolf": "SVEN_PACKMASTER",
              "enderman": "VOIDGLOOM_SERAPH", "blaze": "INFERNO_DEMONLORD", "vampire": "RIFTSTALKER_BLOODFIEND"}
RNG_KEY = {"zombie": "Revenant Horror", "spider": "Tarantula Broodfather", "wolf": "Sven Packmaster",
           "enderman": "Voidgloom Seraph", "blaze": "Inferno Demonlord", "vampire": "Riftstalker Bloodfiend"}


# Hunting shards in the boss loot (research 04: 2.5 Zombie, 2.6 Spider, 2.7 Wolf, 2.9 Blaze; NEU's legacy
# ATTRIBUTE_SHARD_SLAYER_DISCOUNT 0.25% is the Paragon). type -> shard -> (min slayer level, % per tier).
# Audit C2 (2026-10-06, SkyblockMoney/work/combat.md): the Paragon drops 0.25% from EVERY T4 and 0.5% from EVERY T5
# boss at slayer level 5, in its OWN pool (an independent roll - see OWN_POOL), never from a T3.
SHARD_DROPS = {
    "zombie": {"SHARD_REVENANT": (0, "revenant"),                       # w100/w150/w200 at T3/T4/T5
               "SHARD_PARAGON": (5, [0, 0, 0, 0.25, 0.50])},
    "spider": {"SHARD_PARAGON": (5, [0, 0, 0, 0.25, 0.50]),
               "SHARD_PRIMORDIAL": (0, [0, 0, 0, 0, 0.71])},            # T5 only (a T5 already needs Spider 7)
    "wolf": {"SHARD_PARAGON": (5, [0, 0, 0, 0.25])},
    "enderman": {"SHARD_PARAGON": (5, [0, 0, 0, 0.25])},
    "blaze": {"SHARD_PARAGON": (5, [0, 0, 0, 0.25]),
              "SHARD_BURNINGSOUL": (3, [0, 1.54, 2.90, 5.27])},         # "Inferno Demonlord Shard" row, T2-T4
}
# Rolled on their own, outside the one weighted pick (like the dyes): audit C2/C19, "its own pool".
OWN_POOL = {"SHARD_PARAGON"}

# Audit 2026-10-06 rows (docs/AUDIT_REAL_GAME_2026-10-06.md sec 3; SkyblockMoney/work/combat.md; research 04 tables).
# NEU's per-tier % is the chance at the drop's OWN unlock level; the wiki's is at MAX level, which is what our weights
# mean (the pool renormalises as levels unlock). type -> item -> {tier: (chance % or None to keep, min, max)}.
LOOT_FIX = {
    "zombie": {"FOUL_FLESH": {5: (13.6082, 3, 4)},           # C7: w2000, the same weight as Viscera -> 13.6% x3.5
               "REVENANT_VISCERA": {5: (None, 1, 2)}},        # research 04 "1-2 . w2000"; combat.md "x1.5"
    "spider": {"TARANTULA_WEB": {3: (None, 16, 32), 4: (None, 32, 48)}},   # C6: research 04 2.6 (T4 ~40 a kill)
    "enderman": {"SUMMONING_EYE": {4: (0.565, 1, 1)},          # C5
                 "TWILIGHT_ARROW_POISON": {4: (12.7, 60, 64)}},  # C5 (x62 on average)
    # C3: of the five distillates only Gabagool drops to the summoner (research 04 2.9 + combat.md "4.87% x12")
    "blaze": {"CRUDE_GABAGOOL_DISTILLATE": {2: (5.19, 4, 4), 3: (4.87, 10, 14), 4: (4.43, 24, 32)}},
}
# C3: these drop to LOOTSHARE helpers only (one shared distillate slot, research 04 2.9: 4 / 10-14 / 24-32).
# NEU's per-tier % stays as their weight in the helper's roll.
LOOTSHARE_ONLY = {
    "blaze": {i: {2: (None, 4, 4), 3: (None, 10, 14), 4: (None, 24, 32)}
              for i in ("BLAZE_ROD_DISTILLATE", "GLOWSTONE_DUST_DISTILLATE", "MAGMA_CREAM_DISTILLATE",
                        "NETHER_STALK_DISTILLATE")},
}
try:
    _hs = json.load(open(os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "hunting", "shards.json"),
                         encoding="utf-8"))
    _hs = _hs if isinstance(_hs, list) else _hs.get("shards", [])
    HUNT_SHARDS = {s["id"]: (s.get("name", s["id"]), s.get("rarity", "RARE")) for s in _hs}   # the box's own names
except Exception:
    HUNT_SHARDS = {}


def map_drop_id(raw):
    """NEU drop id -> our item id, or None to skip."""
    raw = raw.split(":")[0] if raw.count(":") >= 1 and not raw.endswith(";") else raw
    if raw.startswith("ATTRIBUTE_SHARD"):
        # NEU still lists the legacy Kuudra-era attribute shard; its attribute (Slayer Discount) is the Paragon
        # Hunting shard's since the 2025 rework (hunting's shards.json: SHARD_PARAGON attr slayer_discount).
        return "SHARD_PARAGON" if raw.split(";")[0] == "ATTRIBUTE_SHARD_SLAYER_DISCOUNT" else None
    if raw.startswith("POTION_WISP_ICE"):
        return "POTION_WISP_ICE;1"      # alchemy registers it ("Chilled Spirit Water I", effect wisp_ice)
    if raw.startswith("POTION_"):
        return None                     # no other potion drops in the slayer tables
    if ";" in raw:
        name, lvl = raw.split(";", 1)
        if name.endswith("_RUNE"):
            return name
        return "ENCHANTMENT_" + name + "_" + lvl
    return raw


def qty_of(drop):
    raw = drop["id"]
    m = re.search(r":(\d+)$", raw.split(";")[-1]) if ";" in raw else re.search(r":(\d+)$", raw)
    lo = hi = 1
    if m:
        lo = hi = int(m.group(1))
    for e in drop.get("extra", []):
        mm = re.search(r"x(\d+)(?:-(\d+))?", strip(e))
        if mm:
            lo = int(mm.group(1))
            hi = int(mm.group(2) or mm.group(1))
    return lo, hi


def chance_of(drop):
    return float(drop.get("chance", "0").rstrip("%"))


def build_loot(t):
    tiers = len(t["hp"])
    entries = collections.OrderedDict()
    rngmap = {}
    for k, v in RNG[RNG_KEY[t["id"]]].items():
        mid = map_drop_id(k)
        if mid:
            rngmap[mid] = v
    for tier in range(1, tiers + 1):
        d = neu(BOSS_FILES[t["id"]] + "_" + str(tier) + "_BOSS")
        for r in d.get("recipes", []):
            for drop in r.get("drops", []):
                mid = map_drop_id(drop["id"])
                if not mid:
                    continue
                lo, hi = qty_of(drop)
                ch = chance_of(drop)
                e = entries.setdefault(mid, {"item": mid, "min": [0] * tiers, "max": [0] * tiers,
                                             "chance": [0.0] * tiers, "minLevel": MINLVL[t["id"]].get(mid, 0)})
                e["min"][tier - 1] = lo
                e["max"][tier - 1] = hi
                e["chance"][tier - 1] = ch
    # audit 2026-10-06 fixes (before the shards: the Revenant shard is priced off Foul Flesh's weight)
    for table, flag in ((LOOT_FIX, False), (LOOTSHARE_ONLY, True)):
        for mid, per in table.get(t["id"], {}).items():
            e = entries[mid]
            for tier, (ch, lo, hi) in per.items():
                if ch is not None:
                    e["chance"][tier - 1] = ch
                e["min"][tier - 1] = lo
                e["max"][tier - 1] = hi
            if flag:
                e["lootshareOnly"] = True
    # Hunting shards (wave 5): research rows override/extend NEU. They land in the Hunting Box, never as an item.
    for sid, (minlvl, chances) in SHARD_DROPS.get(t["id"], {}).items():
        if chances == "revenant":
            # research 2.5 gives weights (w100/150/200 at T3-T5) next to Foul Flesh's w2000, whose % NEU knows
            foul = entries.get("FOUL_FLESH")
            w = [0, 0, 100, 150, 200]
            chances = [round(w[i] * (foul["chance"][i] if foul else 0) / 2000.0, 4) for i in range(tiers)]
        e = entries.setdefault(sid, {"item": sid, "min": [0] * tiers, "max": [0] * tiers,
                                     "chance": [0.0] * tiers, "minLevel": minlvl})
        e["minLevel"] = minlvl
        for i in range(tiers):
            c = chances[i] if i < len(chances) else 0
            e["chance"][i] = c
            e["min"][i] = e["max"][i] = 1 if c > 0 else 0
    for sid, e in entries.items():
        if sid.startswith("SHARD_") and sid != "SHARD_OF_THE_SHREDDED":
            name, rarity = HUNT_SHARDS.get(sid, (sid[6:].title(), "RARE"))
            e["shard"] = True
            e["name"] = name + " Shard"
            e["rarity"] = rarity
    out = []
    for mid, e in entries.items():
        e["token"] = all(c >= 100.0 or c == 0 for c in e["chance"]) and any(c >= 100.0 for c in e["chance"])
        e["independent"] = mid.startswith("DYE_") or mid in OWN_POOL
        if mid in rngmap:
            e["rng"] = rngmap[mid]
        out.append(e)
    # weighted pool: weights are the per-kill % at max level; the token fills the rest of 100 (research 2.11)
    token_w = []
    for i in range(tiers):
        s = sum(e["chance"][i] for e in out if not e["token"] and not e["independent"] and not e.get("lootshareOnly"))
        token_w.append(round(max(0.0, 100.0 - s), 4))
    return out, token_w


# ---------------------------------------------------------------------------------------------------------------
# Item ownership. OWN = registered in enable (we are the owner); FALLBACK = registered in postEnable if nobody else did.
# ---------------------------------------------------------------------------------------------------------------
LEGACY = {"SKULL_ITEM": "PLAYER_HEAD", "RAW_FISH": "COD", "INK_SACK": "INK_SAC", "SULPHUR": "GUNPOWDER",
          "NETHER_STALK": "NETHER_WART", "GRILLED_PORK": "COOKED_PORKCHOP", "TRAP_DOOR": "OAK_TRAPDOOR",
          "FIREWORK": "FIREWORK_ROCKET", "GOLD_CHESTPLATE": "GOLDEN_CHESTPLATE", "GOLD_NUGGET": "GOLD_NUGGET",
          "WOOD_SWORD": "WOODEN_SWORD"}

# id -> (our name or None to keep, look override material, lore lines)
DROPS = {
    # zombie
    "REVENANT_FLESH": (rn("REVENANT_FLESH", "Revenant Flesh", "Ghoul Flesh"), None, ["&7Torn from a Ghoul Horror. The base of every undead-hunter craft."]),
    "FOUL_FLESH": (None, "CHARCOAL", ["&7Blackened meat that still twitches.", "&7Prized by alchemists."]),
    "UNDEAD_CATALYST": (None, None, ["&7Fuses with a heart to wake a blade", "&7meant for the walking dead."]),
    "REVENANT_CATALYST": (rn("REVENANT_CATALYST", "Revenant Catalyst", "Ghoul Catalyst"), None, ["&7Lifts Ghoul gear into its reaper form."]),
    "BEHEADED_HORROR": (rn("BEHEADED_HORROR", "Beheaded Horror", "Beheaded Ghoul"), None, ["&7The head of a Ghoul Horror, still grinning."]),
    "SCYTHE_BLADE": (None, None, ["&7A curved blade of grave iron."]),
    "FESTERING_MAGGOT": (None, None, ["&7It wriggles. It should not."]),
    "REVENANT_VISCERA": (rn("REVENANT_VISCERA", "Revenant Viscera", "Ghoul Viscera"), None, ["&7Compressed horror. Ghoul armour is stitched from it."]),
    # audit C8: the reference renamed it Shredded Sinew in 2026 - which was our name, so ours moved on
    "SHARD_OF_THE_SHREDDED": (rn("SHARD_OF_THE_SHREDDED", "Shredded Sinew (was Shard of the Shredded)", "Shredded Tendon"), None, ["&7What is left after a Penitent Horror is", "&7torn apart."]),
    "SEVERED_HAND": (None, None, ["&7Still clutching something holy."]),
    "WARDEN_HEART": (rn("WARDEN_HEART", "Warden Heart", "Sentinel Heart"), None, ["&7Beats once a minute. The core of a", "&7Sentinel Helmet."]),
    # spider
    "TARANTULA_WEB": (rn("TARANTULA_WEB", "Tarantula Web", "Broodweaver Web"), "COBWEB", ["&7Sticky silk spun by a Broodweaver Patriarch."]),
    "TARANTULA_SILK": (rn("TARANTULA_SILK", "Tarantula Silk", "Broodweaver Silk"), "STRING", ["&7Woven webs, strong as wire. Used for Broodweaver armour."]),
    "SPIDER_CATALYST": (None, None, ["&7Turns spider gear into its broodweaver form."]),
    "TARANTULA_CATALYST": (rn("TARANTULA_CATALYST", "Tarantula Catalyst", "Broodweaver Catalyst"), None, ["&7Awakens the primordial power in broodweaver gear."]),
    "FLY_SWATTER": (None, "BAMBOO", ["&7A very serious tool. Spiders fear it."]),
    "VIAL_OF_VENOM": (None, None, ["&7Concentrated broodweaver venom."]),
    "DIGESTED_MOSQUITO": (None, None, ["&7Half-eaten, still buzzing."]),
    "SHRIVELED_WASP": (None, None, ["&7Dried out in a cocoon for years."]),
    "ENSNARED_SNAIL": (None, None, ["&7Caught in the brood's web. Very slowly", "&7trying to leave."]),
    "PRIMORDIAL_EYE": (None, None, ["&7The eye of the Elder Broodmother herself."]),
    # wolf
    "WOLF_TOOTH": (None, None, ["&7A tooth from the Fenris pack."]),
    "GOLDEN_TOOTH": (None, None, ["&7A tooth plated in enchanted gold."]),
    "HAMSTER_WHEEL": (None, "OAK_TRAPDOOR", ["&7Why a wolf had this is a mystery."]),
    "RED_CLAW_EGG": (None, "EGG", ["&7A rare egg a pet trainer would love."]),
    "OVERFLUX_CAPACITOR": (None, "QUARTZ", ["&7Overcharged core for power orbs."]),
    "GRIZZLY_BAIT": (None, "COD", ["&7Smells strongly of salmon and bear."]),
    "WEAK_WOLF_CATALYST": (None, None, ["&7A faint howl lives inside."]),
    # enderman
    "NULL_SPHERE": (None, None, ["&7A sphere of nothing torn from a Nullshade Seraph."]),
    "NULL_OVOID": (None, None, ["&7Thirty-two spheres of nothing, folded."]),
    "NULL_ATOM": (None, None, ["&7The smallest possible amount of nothing."]),
    "NULL_EDGE": (None, None, ["&7An edge that cuts through space."]),
    "NULL_BLADE": (None, None, ["&7A blade of pure null."]),
    "TRANSMISSION_TUNER": (None, None, ["&7Tunes teleport gear for a longer jump."]),
    "SINFUL_DICE": (rn("SINFUL_DICE", "Sinful Dice", "Sinner's Die"), None, ["&7A void-black die. Crafters use it;", "&7it is never rolled."]),
    "ETHERWARP_MERGER": (rn("ETHERWARP_MERGER", "Etherwarp Merger", "Phasewarp Merger"), None, ["&7Binds a teleport blade to the void."]),
    "JUDGEMENT_CORE": (rn("JUDGEMENT_CORE", "Judgement Core", "Verdict Core"), None, ["&7The heart of a fallen seraph."]),
    "EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER": (rn("EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER", "Exceedingly Rare Ender Artifact Upgrader", "Ender Relic Refiner"), None, ["&7Refines an ender artifact into a relic."]),
    "ENDSTONE_IDOL": (None, None, ["&7A carved idol of the void's worshippers."]),
    # blaze
    "DERELICT_ASHE": (None, None, ["&7Ash left where a Cinder Demonlord burned."]),
    "MANA_DISINTEGRATOR": (None, None, ["&7Breaks mana into something denser."]),
    "KELVIN_INVERTER": (None, None, ["&7Flips heat to cold. Daggers love it."]),
    "SUBZERO_INVERTER": (None, None, ["&7Flips heat to absolute cold."]),
    "BLAZE_ROD_DISTILLATE": (None, None, ["&7Distilled blaze rod essence."]),
    "GLOWSTONE_DUST_DISTILLATE": (None, None, ["&7Distilled glowstone essence."]),
    "MAGMA_CREAM_DISTILLATE": (None, None, ["&7Distilled magma essence."]),
    "NETHER_STALK_DISTILLATE": (None, None, ["&7Distilled nether wart essence."]),
    "CRUDE_GABAGOOL_DISTILLATE": (rn("CRUDE_GABAGOOL_DISTILLATE", "Gabagool Distillate", "Gourmet Meat Distillate"), None, ["&7Distilled gourmet meat essence."]),
    "SCORCHED_POWER_CRYSTAL": (None, None, ["&7Burning power for crystal sockets."]),
    "ARCHFIEND_DICE": (rn("ARCHFIEND_DICE", "Archfiend Dice", "Archfiend Token"), "GOLD_NUGGET", ["&7A demon's token. Worth a fixed sum to", "&7collectors - no rolling, no luck."]),
    "HIGH_CLASS_ARCHFIEND_DICE": (rn("HIGH_CLASS_ARCHFIEND_DICE", "High Class Archfiend Dice", "Gilded Archfiend Token"), "GOLD_INGOT", ["&7A demon lord's token. Worth a fixed sum."]),
    "WILSON_ENGINEERING_PLANS": (rn("WILSON_ENGINEERING_PLANS", "Wilson's Engineering Plans", "Tinker's Engineering Plans"), "MAP", ["&7Blueprints for a flare that calls for help."]),
    # vampire
    "COVEN_SEAL": (None, None, ["&7The seal of a vampire coven."]),
    "MCGRUBBER_BURGER": (rn("MCGRUBBER_BURGER", "McGrubber's Burger", "Grubber's Burger"), None, ["&7Do not ask what is in it."]),
    "UNFANGED_VAMPIRE_PART": (None, None, ["&7A vampire part, safely de-fanged."]),
    "ENCHANTED_BOOK_BUNDLE_QUANTUM": (rn("ENCHANTED_BOOK_BUNDLE_QUANTUM", "Enchanted Book Bundle (Quantum)", "Book Bundle (Quantum)"), None, ["&7A bundle of enchanted books.", "&eRight-click to open!"]),
    "ENCHANTED_BOOK_BUNDLE_THE_ONE": (rn("ENCHANTED_BOOK_BUNDLE_THE_ONE", "Enchanted Book Bundle (The One)", "Book Bundle (Sovereign)"), None, ["&7A bundle of rare enchanted books.", "&eRight-click to open!"]),
}

RUNES = {"ZOMBIE_SLAYER_RUNE": ("Pestilence Rune", "LIME_DYE"), "BITE_RUNE": ("Bite Rune", "RED_DYE"),
         "SPIRIT_RUNE": ("Spirit Rune", "WHITE_DYE"), "COUTURE_RUNE": ("Couture Rune", "PINK_DYE"),
         "DARKNESS_WITHIN_RUNE": ("Inner Darkness Rune", "BLACK_DYE"), "SNAKE_RUNE": ("Snake Rune", "GREEN_DYE"),
         "ENDERSNAKE_RUNE": ("Endersnake Rune", "PURPLE_DYE"), "DRAGON_RUNE": ("Dragon Rune", "MAGENTA_DYE"),
         "ENCHANT_RUNE": ("Enchant Rune", "LIGHT_BLUE_DYE"), "LAVATEARS_RUNE": ("Lava Tears Rune", "ORANGE_DYE"),
         "FIERY_BURST_RUNE": ("Fiery Burst Rune", "ORANGE_DYE"), "SOULTWIST_RUNE": ("Soultwist Rune", "CYAN_DYE")}
DYES = {"DYE_MATCHA": ("Matcha Dye", "LIME_DYE"), "DYE_BRICK_RED": ("Brick Red Dye", "RED_DYE"),
        "DYE_CELESTE": ("Celeste Dye", "LIGHT_BLUE_DYE"), "DYE_BYZANTIUM": ("Byzantium Dye", "PURPLE_DYE"),
        "DYE_FLAME": ("Flame Dye", "ORANGE_DYE"), "DYE_SANGRIA": ("Sangria Dye", "RED_DYE")}

# gear: armor sets this module owns (armor module excludes slayer-gated sets)
GEAR = {
    "REVENANT_CHESTPLATE": ("Ghoul Chestplate", "zombie_set"), "REVENANT_LEGGINGS": ("Ghoul Leggings", "zombie_set"),
    "REVENANT_BOOTS": ("Ghoul Boots", "zombie_set"),
    "REAPER_MASK": (None, "reaper_set"), "REAPER_CHESTPLATE": (None, "reaper_set"),
    "REAPER_LEGGINGS": (None, "reaper_set"), "REAPER_BOOTS": (None, "reaper_set"),
    "CRYSTALLIZED_HEART": (None, None), "REVIVED_HEART": (None, None), "WARDEN_HELMET": ("Sentinel Helmet", None),
    "TARANTULA_HELMET": ("Broodweaver Helmet", "spider_set"), "TARANTULA_CHESTPLATE": ("Broodweaver Chestplate", "spider_set"),
    "TARANTULA_LEGGINGS": ("Broodweaver Leggings", "spider_set"), "TARANTULA_BOOTS": ("Broodweaver Boots", "spider_set"),
    "PRIMORDIAL_HELMET": (None, "primordial_set"), "PRIMORDIAL_CHESTPLATE": (None, "primordial_set"),
    "PRIMORDIAL_LEGGINGS": (None, "primordial_set"), "PRIMORDIAL_BOOTS": (None, "primordial_set"),
    "MASTIFF_HELMET": ("Mastiff Crown", "mastiff_set"), "MASTIFF_CHESTPLATE": (None, "mastiff_set"),
    "MASTIFF_LEGGINGS": (None, "mastiff_set"), "MASTIFF_BOOTS": (None, "mastiff_set"),
    "HELMET_OF_THE_PACK": (None, "pack_set"), "CHESTPLATE_OF_THE_PACK": (None, "pack_set"),
    "LEGGINGS_OF_THE_PACK": (None, "pack_set"), "BOOTS_OF_THE_PACK": (None, "pack_set"),
    "FINAL_DESTINATION_HELMET": ("Last Horizon Helmet", "enderman_set"),
    "FINAL_DESTINATION_CHESTPLATE": ("Last Horizon Chestplate", "enderman_set"),
    "FINAL_DESTINATION_LEGGINGS": ("Last Horizon Leggings", "enderman_set"),
    "FINAL_DESTINATION_BOOTS": ("Last Horizon Boots", "enderman_set"),
}
for gid, (nm, _s) in GEAR.items():
    if nm:
        rn(gid, strip(neu(gid)["displayname"]), nm)

# Set bonus text (our wording, research 06 numbers). The behaviour is in SlayerGear.java.
SETS = {
    "zombie_set": ("Grave Trolling", 3, ["&7Healing wands heal &a+50%&7.", "&7Gain &a+100 &a❈ Defense &7against undead."],
                   ["&6Piece Bonus: Undead Bulwark", "&7Every &a50 &7zombies killed while", "&7wearing it adds &a+20 &7Defense against",
                    "&7zombies (max &a+120&7)."]),
    "reaper_set": ("Reaper's Rage", 4, ["&7While sneaking, gain &a+100 &f✦ Speed&7,", "&a+100 &c❁ Strength &7and &a+100% &7damage",
                                         "&7for &a6s&7. &8(25s cooldown)", "&7Deal &a+100% &7damage to undead."], []),
    "spider_set": ("Eightfold Strike", 4, ["&7Every &a4th &7strike deals &a+100% &7damage", "&7and venoms the target.",
                                            "&7Gain &a+1 &9☠ Crit Damage &7per &a10 &c❁ Strength", "&8(max +100)."],
                   ["&6Piece Bonus: Arachnid Bulwark", "&7Every &a50 &7spiders killed while", "&7wearing it adds &a+20 &7Defense against",
                    "&7spiders (max &a+120&7)."]),
    "primordial_set": ("Elder Strike", 4, ["&7Every &a3rd &7strike deals &a+50% &7damage.",
                                           "&7Gain &a+1 &9☠ Crit Damage &7per &a10 &c❁ Strength", "&8(max +150)."], []),
    "mastiff_set": ("Absolute Unit", 4, ["&7Take &a20% &7less damage from wolves and animals.",
                                         "&7Gain &a+1% &c❤ Health &7per &a25 &9☠ Crit Damage &8(max 40%)&7.",
                                         "&7Gain &a+1 &9☠ Crit Damage &7per &a2 &a❈ Defense&7.",
                                         "&7Heal &c150 ❤ &7when hit &8(1s cooldown)&7.", "&7Your &a❈ Defense &7is capped at &a300&7."], []),
    "pack_set": ("Pack Spirit", 4,["&7For every other player within &a30 &7blocks", "&7wearing pack armour, gain &a+35 &c❁ Strength",
                                    "&7and &a+80 &a❈ Defense &8(max 3)&7."], []),
    "enderman_set": ("Vivid Darkness", 4, ["&7Costs &32⸎ Soulstream &7per &a5s &7in combat", "&7while sneaking: &a+30 &c❁ Strength&7, &a+20 &e⚔ Attack Speed&7,",
                                           "&a+10 &f✦ Speed&7, &b✎ Intelligence &ax1.25&7,", "&a+200 &c⫽ Ferocity &7and &a+100% &7damage", "&7against endermen."],
                     ["&6Piece Bonus: Ender Bulwark", "&7Every &a100 &7endermen killed while", "&7wearing it adds &a+20 &7Defense against",
                      "&7endermen (max &a+120&7)."]),
}
SET_LEVEL_REQ = {"zombie_set": "skill:combat:0"}

EXTRA_GEAR_LORE = {
    "CRYSTALLIZED_HEART": ["&7A diamond-cased zombie heart."],
    "REVIVED_HEART": ["&7The heart beats again."],
    "WARDEN_HELMET": ["&7The helmet of an undying sentinel.", "&6Brute Force", "&7Halves your &f✦ Speed&7. Your weapon's base",
                      "&7damage is raised &a+20% &7for every &a25 &f✦ Speed", "&7you have left."],
    "REAPER_MASK": ["&7Heal &c10% &7of your max health on", "&7every undead kill."],
}

# usable items, owned here
USABLE = {
    "AATROX_BATPHONE": (rn("AATROX_BATPHONE", "Maddox Batphone", "Slayer Hotline"), "slayer_hotline", "Ring Ring",
                        ["&7Calls &5" + NPC_NAME + " &7from anywhere", "&7so you can start or claim quests."], 0, 0),
    "ZOMBIE_BRAIN_MIXIN": (None, "slayer_mixin", "Drink", ["&7Gain &a+10 &c⫽ Ferocity&7 for &a36h&7.", "&7Durations stack.", "&8Needs an active Boon Cookie."], 0, 0),
    "SPIDER_EGG_MIXIN": (None, "slayer_mixin", "Drink", ["&7Gain &a+15 &9☠ Crit Damage&7 for &a36h&7.", "&7Durations stack.", "&8Needs an active Boon Cookie."], 0, 0),
    "WOLF_FUR_MIXIN": (None, "slayer_mixin", "Drink", ["&7Gain &a+7 &b✯ Magic Find &7on monsters", "&7you slay in &aone hit&7, for &a36h&7.", "&7Durations stack.", "&8Needs an active Boon Cookie."], 0, 0),
    "END_PORTAL_FUMES_MIXIN": (None, "slayer_mixin", "Drink", ["&7Soulstream conversions give &a+30%", "&7more &3Overflow Mana&7, for &a36h&7.", "&7Durations stack.", "&8Needs an active Boon Cookie."], 0, 0),
    "GABAGOEY_MIXIN": (rn("GABAGOEY_MIXIN", "Gabagoey Mixin", "Gourmet Mixin"), "slayer_mixin", "Drink",
                       ["&7Gain &a+5% &f❂ True Defense&7 for &a36h&7.", "&7Durations stack.", "&8Needs an active Boon Cookie."], 0, 0),
    "RADIANT_POWER_ORB": (None, "slayer_orb", "Deploy", ["&7Place an orb for &a1m &7that buffs up to", "&a5 &7players within &a18 &7blocks:",
                                                          "&8• &7Heal yourself &c30❤/s&7, others &c15❤/s",
                                                          "&8• &7+&a25 &c❣ Health Regen", "&8Only one deployable buff applies."], 0.5, 0),
    "MANA_FLUX_POWER_ORB": (None, "slayer_orb", "Deploy", ["&7Place an orb for &a1m &7that buffs up to", "&a5 &7players within &a18 &7blocks:",
                                                            "&8• &7Heal yourself &c40❤/s&7, others &c20❤/s",
                                                            "&8• &7+&a50 &c❣ Health Regen&7, +&a50% &bmana regen",
                                                            "&8• &7+&a10 &c❁ Strength", "&8Only one deployable buff applies."], 0.5, 0),
    "OVERFLUX_POWER_ORB": (None, "slayer_orb", "Deploy", ["&7Place an orb for &a1m &7that buffs up to", "&a5 &7players within &a18 &7blocks:",
                                                           "&8• &7Heal yourself &c50❤/s&7, others &c25❤/s",
                                                           "&8• &7+&a100 &c❣ Health Regen&7, +&a100% &bmana regen",
                                                           "&8• &7+&a25 &c❁ Strength", "&8Only one deployable buff applies."], 0.5, 0),
    "PLASMAFLUX_POWER_ORB": (None, "slayer_orb", "Deploy", ["&7Place an orb for &a1m &7that buffs up to", "&a5 &7players within &a20 &7blocks:",
                                                             "&8• &7Heal yourself &c60❤/s&7, others &c30❤/s",
                                                             "&8• &7+&a125 &c❣ Health Regen&7, +&a125% &bmana regen",
                                                             "&8• &7+&a35 &c❁ Strength", "&8Only one deployable buff applies."], 0.5, 0),
    "WARNING_FLARE": (None, "slayer_flare", "Deploy", ["&7Fire a flare for &a3m &7that buffs up to", "&a8 &7players within &a40 &7blocks:",
                                                       "&8• &7+&a10 &4♨ Vitality&7, +&a10 &f❂ True Defense",
                                                       "&8• &7Heal &c30❤/s &7on the Crimson Isle", "&8Only one deployable buff applies."], 300, 20),
    "ALERT_FLARE": (None, "slayer_flare", "Deploy", ["&7Fire a flare for &a3m &7that buffs up to", "&a8 &7players within &a40 &7blocks:",
                                                     "&8• &7+&a50% &bmana regen&7, +&a15 &4♨ Vitality",
                                                     "&8• &7+&a20 &f❂ True Defense&7, +&a10 &c⫽ Ferocity",
                                                     "&8• &7Heal &c40❤/s &7on the Crimson Isle", "&8Only one deployable buff applies."], 300, 20),
    "SOS_FLARE": (None, "slayer_flare", "Deploy", ["&7Fire a flare for &a3m &7that buffs up to", "&a8 &7players within &a40 &7blocks:",
                                                   "&8• &7+&a125% &bmana regen&7, +&a25 &4♨ Vitality",
                                                   "&8• &7+&a30 &f❂ True Defense&7, +&a10 &c⫽ Ferocity&7, +&a5 &e⚔ Attack Speed",
                                                   "&8• &7Heal &c50❤/s &7on the Crimson Isle", "&8Only one deployable buff applies."], 250, 10),
    "STEAK_STAKE": (None, None, None, ["&7Stab a vampire at &c20%❤ &7or less", "&7to finish it off instantly.",
                                       "&8(It does not actually hurt it.)"], 0, 0),
    "SHARP_STEAK_STAKE": (rn("SHARP_STEAK_STAKE", "Super Sharp 'n Stabby Steak Stake", "Razor Steak Stake"), None, None,
                          ["&7Stab a vampire at &c20%❤ &7or less", "&7to finish it off instantly.",
                           "&7After a stake kill your next hit deals", "&ax2 &7damage."], 0, 0),
    "WEIRD_TUBA": (None, "slayer_tuba", "Howl", ["&7You and &a4 &7nearby players gain", "&a+30 &c❁ Strength &7and &a+30 &f✦ Speed",
                                                 "&7for &a20s&7. Does not stack."], 150, 20),
    "WEIRDER_TUBA": (None, "slayer_tuba", "Howl", ["&7You and &a4 &7nearby players gain", "&a+40 &c❁ Strength&7, &a+5 &e⚔ Attack Speed&7,",
                                                   "&a+10 &9☠ Crit Damage &7and &a+30 &f✦ Speed", "&7for &a30s&7. Does not stack."], 150, 20),
    "GLOOMLOCK_GRIMOIRE": (None, "slayer_grimoire", "Life Tap", ["&7Spend &c800❤ &7to restore &b600✎ &7mana."], 0, 1),
    "HOLY_ICE": (None, "slayer_holy_ice", "Splash", ["&7For &a1.5s&7, take &a80% &7less damage."], 20, 4),
    # Finish wave 2026-10-03: the two slayer-gated utility items the crafting book already listed with no item behind them.
    "TACTICAL_INSERTION": (None, "slayer_tactical", "Gorilla Tactics",
                           ["&7Marks your location and teleports you", "&7back there after &a3s&7.", "",
                            "&7On coming back, burn enemies within &a3", "&7blocks and set your &c❤ Health &7to &cHALF",
                            "&7of what it was.", "", "&7The burn deals &a10% &7of ALL damage you",
                            "&7dealt within the &a3s&7, spread over &a6s&7."], 200, 20),
    "VOODOO_DOLL": (None, "slayer_voodoo", "Acupuncture",
                    ["&7Shoot arrows from every direction around", "&7the targeted monster.", "",
                     "&7Monsters hit by at least one arrow are", "&7slowed and receive &c1,500 &7damage/s for &a10s&7.", "",
                     "&8Who do you voodoo?"], 200, 5),
    "VOODOO_DOLL_WILTED": (None, "slayer_voodoo_jinxed", "Acupuncture",
                           ["&7Shoot arrows from every direction around", "&7the targeted monster.", "",
                            "&7Monsters hit by at least one arrow are", "&7slowed and receive &c2,222 &7damage/s for &a12s&7.", "",
                            "&8Who do you voodoo?"], 180, 3),
}

MOBWORD_EXTRA = {}


def material_for(iid, override=None):
    it = ITEMS.get(iid, {})
    mat = MATERIALS.get(iid)
    if override:
        return override, None
    if mat:
        return mat.get("material", "PAPER"), mat.get("skull")
    skin = it.get("skin")
    if skin:
        v = skin.get("value") if isinstance(skin, dict) else skin
        if v:
            return "PLAYER_HEAD", v
    m = it.get("material", "PAPER")
    m = LEGACY.get(m, m)
    if m == "LEATHER_CHESTPLATE" or m.startswith("LEATHER_") or m.endswith(("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS")):
        m = m.replace("GOLD_", "GOLDEN_")
        return m, None
    return m, None


def rarity_of(iid, default="RARE"):
    t = ITEMS.get(iid, {}).get("tier")
    if not t or t in ("UNOBTAINABLE", "SUPREME"):
        mat = MATERIALS.get(iid)
        return mat.get("rarity", default) if mat else default
    return t


def sell_of(iid):
    v = ITEMS.get(iid, {}).get("npc_sell_price")
    if v:
        return v
    mat = MATERIALS.get(iid)
    return mat.get("npcSell", 0) if mat else 0


def reqs_of(iid):
    out = []
    for r in ITEMS.get(iid, {}).get("requirements", []):
        if r.get("type") == "SLAYER":
            out.append("slayer:%s:%d" % (r["slayer_boss_type"], r["level"]))
        elif r.get("type") == "SKILL":
            out.append("skill:%s:%d" % (r["skill"].lower(), r["level"]))
    return out


STAT_KEY = {"WALK_SPEED": "SPEED", "CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE"}


def stats_of(iid):
    out = {}
    for k, v in (ITEMS.get(iid, {}).get("stats") or {}).items():
        k = STAT_KEY.get(k.upper(), k.upper())
        if k in ("DAMAGE", "WEAPON_ABILITY_DAMAGE"):
            continue
        out[k] = v
    return out


def base_def(iid, name, category, lore, rarity=None, material=None, skull=None):
    if name is None:
        d = neu(iid)
        name = strip(d["displayname"]) if d else ITEMS.get(iid, {}).get("name", iid.title())
    mat, sk = material_for(iid, material)
    out = {"id": iid, "name": name, "material": mat, "rarity": rarity or rarity_of(iid), "category": category,
           "lore": lore, "extra": {"src": "slayer"}}
    if skull or sk:
        out["skull"] = skull or sk
    s = sell_of(iid)
    if s:
        out["npcSell"] = s
    st = stats_of(iid)
    if st:
        out["stats"] = st
    rq = reqs_of(iid)
    if rq:
        out["requirements"] = rq
    col = ITEMS.get(iid, {}).get("color")
    if col and mat.startswith("LEATHER_"):
        r, g, b = [int(x) for x in col.split(",")]
        out["color"] = "#%02X%02X%02X" % (r, g, b)
    return out


items_own = []
items_fb = []

for iid, (nm, look, lore) in DROPS.items():
    d = base_def(iid, nm, "MATERIAL", lore, material=look)
    d["tags"] = ["bazaar", "slayer_drop"]
    items_own.append(d)

BUNDLES = {"ENCHANTED_BOOK_BUNDLE_QUANTUM": ("ENCHANTMENT_QUANTUM_3", "Quantum III"),
           "ENCHANTED_BOOK_BUNDLE_THE_ONE": ("ENCHANTMENT_ULTIMATE_THE_ONE_4", "Sovereign IV")}
for d in items_own:
    if d["id"] in BUNDLES:
        book, label = BUNDLES[d["id"]]
        d["lore"] = ["&9" + label, "&7A bundle holding one enchanted book."]
        d["abilities"] = [{"id": "slayer_book_bundle", "name": "Open", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 0,
                           "lore": ["&7Open the bundle and take the book."]}]
        d["extra"]["book"] = book
        d["tags"] = ["slayer_drop"]

FIXED_VALUE = {"ARCHFIEND_DICE": 100000, "HIGH_CLASS_ARCHFIEND_DICE": 1000000}   # TUNE: the dice gamble is replaced by a fixed value
for d in items_own:
    if d["id"] in FIXED_VALUE:
        d["npcSell"] = FIXED_VALUE[d["id"]]

for iid, (nm, look) in RUNES.items():
    rn(iid, nm, nm) if False else None
    d = {"id": iid, "name": nm, "material": look, "rarity": "RARE", "category": "COSMETIC",
         "lore": ["&7A cosmetic rune from a slayer boss.", "&8Runecrafting arrives with a later update;", "&8until then it is a trophy."],
         "glow": True, "npcSell": 500, "tags": ["slayer_drop"], "extra": {"src": "slayer"}}
    items_fb.append(d)
for iid, (nm, look) in DYES.items():
    d = {"id": iid, "name": nm, "material": look, "rarity": "LEGENDARY", "category": "COSMETIC",
         "lore": ["&7An astonishingly rare dye from a slayer boss.", "&7Collectors will pay anything for it."],
         "glow": True, "npcSell": 1000000, "tags": ["slayer_drop"], "extra": {"src": "slayer"}}
    items_fb.append(d)
items_fb.append({"id": "PET_SKIN_ENDERMAN_SLAYER", "name": rn("PET_SKIN_ENDERMAN_SLAYER", "Void Conqueror Enderman Skin", "Nullshade Enderman Skin"),
                 "material": "PLAYER_HEAD", "rarity": "EPIC", "category": "COSMETIC",
                 "skull": (ITEMS.get("PET_SKIN_ENDERMAN_SLAYER", {}).get("skin") or {}).get("value"),
                 "lore": ["&7A cosmetic skin for an Enderman pet."], "npcSell": 0, "tags": ["slayer_drop"], "extra": {"src": "slayer"}})

for iid, (nm, setid) in GEAR.items():
    cat = ITEMS[iid]["category"]
    lore = list(EXTRA_GEAR_LORE.get(iid, []))
    d = base_def(iid, nm, cat, lore)
    d["tags"] = ["reforgeable", "enchantable", "recombable", "museum", "slayer_gear"]
    if setid:
        s = SETS[setid]
        d["setId"] = "slayer_" + setid
        d["setBonusName"] = s[0]
        d["setBonusLore"] = s[2]
        if s[3]:
            d["lore"] = d["lore"] + s[3]
    items_own.append(d)

for iid, (nm, ability, aname, lore, mana, cd) in USABLE.items():
    cat = ITEMS.get(iid, {}).get("category") or ("CONSUMABLE" if "MIXIN" in iid else "MATERIAL")
    if cat == "DEPLOYABLE":
        cat = "DEPLOYABLE"
    if iid in ("WEIRD_TUBA", "WEIRDER_TUBA", "GLOOMLOCK_GRIMOIRE", "HOLY_ICE", "AATROX_BATPHONE"):
        cat = "WAND" if iid == "GLOOMLOCK_GRIMOIRE" else "DEPLOYABLE" if False else "NONE"
    d = base_def(iid, nm, cat, [] if ability else lore)
    d["tags"] = ["slayer_item"]
    if ability:
        trig = "RIGHT_CLICK"
        ab = {"id": ability, "name": aname, "trigger": trig, "mana": mana, "cooldown": cd, "lore": lore}
        d["abilities"] = [ab]
        d["maxStack"] = 64 if "MIXIN" in iid else 1
    if iid in ("WEIRD_TUBA", "WEIRDER_TUBA"):
        d["material"] = "NAUTILUS_SHELL"
    if iid in ("STEAK_STAKE", "SHARP_STEAK_STAKE"):
        d["category"] = "SWORD"
        d["damage"] = 1
        d["tags"] = ["slayer_item", "no_reforge"]
    if iid == "AATROX_BATPHONE":
        d["tags"].append("soulbound")
        d["tags"].append("no_ah")
    if iid == "GLOOMLOCK_GRIMOIRE":
        d["abilities"].append({"id": "slayer_grimoire_heal", "name": "Extreme Measures", "trigger": "LEFT_CLICK",
                               "mana": 0, "cooldown": 1, "lore": ["&7Heal &c1,000❤&7."]})
    items_own.append(d)

# requirements on usable items: steak stakes are free at Vampire 0 (no Rift here); everything else keeps research gates
for d in items_own:
    if d["id"] in ("STEAK_STAKE",):
        d["requirements"] = []
    if d["id"] == "SHARP_STEAK_STAKE":
        d["requirements"] = ["slayer:vampire:3"]
    if d["id"] == "WEIRDER_TUBA":
        d["requirements"] = ["slayer:wolf:5"]

# ---------------------------------------------------------------------------------------------------------------
# Recipes: NEU grid for every owned item that has one; unlock = NEU slayer_req or crafttext, else the wear requirement.
# Ingredients that only exist in the Rift are replaced (noted) - the Rift is a wave-2 module.
# ---------------------------------------------------------------------------------------------------------------
SLAYER_KEY = {"ZOMBIE": "zombie", "SPIDER": "spider", "WOLF": "wolf", "EMAN": "enderman", "BLAZE": "blaze", "VAMPIRE": "vampire"}
RIFT_SUBST = {"WILTED_BERBERIS_BUNCH": "ENCHANTED_IRON", "CRUXMOTION": "COVEN_SEAL", "FROZEN_WATER": "ICE",
              "METAL_HEART": "GOLDEN_TOOTH", "BLOODBADGE": "COVEN_SEAL"}


def unlock_of(iid, d):
    sr = d.get("slayer_req")
    if sr:
        k, lvl = sr.rsplit("_", 1)
        return "slayer:%s:%s" % (SLAYER_KEY.get(k, k.lower()), lvl)
    m = re.search(r"Requires:? (\w+) Slayer (\d+)", d.get("crafttext", "") or "")
    if m:
        return "slayer:%s:%s" % (m.group(1).lower().replace("enderman", "enderman"), m.group(2))
    rq = reqs_of(iid)
    return rq[0] if rq else None


recipes = []
own_ids = {d["id"] for d in items_own}
for d in items_own:
    iid = d["id"]
    nd = neu(iid)
    if not nd:
        continue
    grid = None
    if "recipe" in nd:
        grid = [nd["recipe"].get(k, "") for k in ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]]
    else:
        for r in nd.get("recipes", []):
            if r.get("type") == "crafting":
                grid = [r.get(k, "") for k in ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]]
                break
    if not grid:
        continue
    cells = []
    for c in grid:
        if not c:
            cells.append("")
            continue
        cid, _, amt = c.partition(":")
        if cid in RIFT_SUBST:
            cid = RIFT_SUBST[cid]
        cells.append(cid + (":" + amt if amt and amt != "1" else ""))
    rec = {"id": "slayer:" + iid, "type": "CRAFTING", "result": iid, "amount": 1, "grid": cells, "station": "Crafting Table"}
    u = unlock_of(iid, nd)
    if iid == "STEAK_STAKE":
        u = None
    if u:
        rec["unlock"] = u
    recipes.append(rec)

# steak stake recipe without Rift ingredients
for r in recipes:
    if r["result"] == "STEAK_STAKE":
        r["grid"] = ["", "", "ENCHANTED_IRON", "", "COVEN_SEAL:3", "", "STICK", "", ""]
        r["note"] = "Rift ingredients replaced"

# ---------------------------------------------------------------------------------------------------------------
# Mobs
# ---------------------------------------------------------------------------------------------------------------
def boss_skull(iid):
    d = neu(iid)
    if not d:
        return None
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', d.get("nbttag", ""))
    return m.group(1) if m else None


mobs = []
for t in TYPES:
    n = len(t["hp"])
    for i in range(n):
        tier = i + 1
        name = t["t5name"] if (tier == 5 and t.get("t5name")) else t["name"]
        hp = t["hp"][i] * (1000 if t["id"] == "vampire" else 1)
        dmg = t["dps"][i]
        if t["id"] == "vampire":
            dmg = 1          # Rift-heart damage is dealt as % of max health by the fight code (1 = let core call onAttack)
        m = {"id": "slayer_%s_%d" % (t["id"], tier), "name": name + " " + ["I", "II", "III", "IV", "V"][i],
             "level": t["level"][i], "entity": t["entity"], "health": hp, "damage": dmg,
             "combatXp": BOSS_COMBAT_XP[i], "coins": 0, "drops": [], "family": "slayer_boss_" + t["id"],
             "slayer": t["id"], "behaviours": ["slayer_boss"], "flags": ["boss", "aggressive", "no_knockback"],
             "extra": {"slayerTier": tier, "nameplate": "show", "lootSource": "SLAYER"}}
        if t["id"] == "zombie":
            m["equipment"] = {"CHEST": "DIAMOND_CHESTPLATE", "LEGS": "CHAINMAIL_LEGGINGS", "FEET": "DIAMOND_BOOTS",
                              "HAND": "DIAMOND_HOE" if tier < 5 else "NETHERITE_HOE"}
            if tier == 5:
                m["equipment"]["CHEST"] = "NETHERITE_CHESTPLATE"
            sk = boss_skull("REVENANT_HORROR_%d_BOSS" % tier)
            if sk:
                m["skull"] = sk
            m["extra"]["types"] = ["undead"]
        if t["id"] == "vampire":
            m["equipment"] = {"CHEST": "NETHERITE_CHESTPLATE", "LEGS": "NETHERITE_LEGGINGS", "FEET": "NETHERITE_BOOTS",
                              "HAND": "IRON_SWORD"}
            sk = boss_skull("RIFTSTALKER_BLOODFIEND_%d_BOSS" % tier)
            if sk:
                m["skull"] = sk
            m["extra"]["types"] = ["undead"]
            m["extra"]["riftHp"] = t["hp"][i]
        if t["id"] == "wolf":
            m["speed"] = 1.5
            m["extra"]["types"] = ["wolf", "animal"]
        if t["id"] == "spider":
            m["speed"] = 1.15
            m["extra"]["types"] = ["arthropod"]
        if t["id"] == "enderman":
            m["extra"]["types"] = ["ender", "enderman"]
        if t["id"] == "blaze":
            m["flags"].append("fire_immune")
            m["extra"]["types"] = ["infernal"]
        if t["id"] == "zombie" and tier == 5:
            m["magicResistance"] = 1.0
        mobs.append(m)

# the brood (spider T5 second phase)
mobs.append({"id": "slayer_spider_5b", "name": "Twinbound Brood", "level": 2230, "entity": "SPIDER", "health": 20000000,
             "damage": 4400, "combatXp": 0, "coins": 0, "drops": [], "family": "slayer_boss_spider", "slayer": "spider",
             "behaviours": ["slayer_boss"], "flags": ["boss", "aggressive", "no_knockback"], "speed": 1.2,
             "extra": {"slayerTier": 5, "phase": "brood", "types": ["arthropod"], "lootSource": "SLAYER"}})


def minion(mid, name, entity, hp, dmg, flags, beh=True, extra=None, speed=1.0):
    m = {"id": mid, "name": name, "level": 1, "entity": entity, "health": hp, "damage": dmg, "combatXp": 0, "coins": 0,
         "drops": [], "family": "slayer_minion", "behaviours": ["slayer_minion"] if beh else [], "flags": flags,
         "speed": speed, "extra": {"nameplate": "show"}}
    if extra:
        m["extra"].update(extra)
    return m


mobs += [
    minion("slayer_wolf_pup", rn("mob:slayer_wolf_pup", "Sven Pup", "Fenris Pup"), "WOLF", 1000, 100, ["aggressive", "baby"], extra={"types": ["wolf", "animal"]}, speed=1.3),
    minion("slayer_egg_sac", "Egg Sac", "SLIME", 3, 0, ["no_ai", "passive", "no_knockback", "silent"], extra={"types": ["arthropod", "egg_sac"]}),
    minion("slayer_broodling", "Broodling", "CAVE_SPIDER", 1000, 50, ["aggressive"], extra={"types": ["arthropod"]}),
    minion("slayer_spring", "Spring Trap", "SLIME", 6, 0, ["no_ai", "passive", "no_knockback", "silent"], extra={"types": ["construct"]}),
    minion("slayer_ichor", "Blood Ichor", "SLIME", 1000000, 0, ["no_ai", "passive", "no_knockback", "silent"], extra={"types": ["construct"]}),
    minion("slayer_bat", "Brood Bat", "BAT", 100, 0, ["passive", "silent"], extra={"types": ["animal"]}),
    minion("slayer_quazii", rn("mob:slayer_quazii", "Quazii", "Quorrix"), "WITHER_SKELETON", 500000, 2500, ["aggressive", "no_knockback", "fire_immune"], extra={"types": ["infernal", "skeletal", "wither"]}),
    minion("slayer_typhoeus", rn("mob:slayer_typhoeus", "Typhoeus", "Tyrphon"), "PIGLIN_BRUTE", 500000, 2500, ["aggressive", "no_knockback", "fire_immune"], extra={"types": ["infernal"]}),
    minion("slayer_fire_pillar", "Fire Pillar", "MAGMA_CUBE", 8, 0, ["no_ai", "passive", "no_knockback", "silent", "fire_immune"], extra={"types": ["infernal"]}),
    minion("slayer_clotgoyle", rn("mob:slayer_clotgoyle", "Clotgoyle", "Blood Gargoyle"), "VEX", 50000, 300, ["aggressive", "flying"], extra={"types": ["undead"]}),
]
for m in mobs:
    if m["id"] in ("slayer_quazii", "slayer_typhoeus"):
        m["level"] = 300
        m["family"] = "slayer_demon"

# minibosses (NEU *_MINIBOSS pages): HP, level, token drop. They spawn rarely on quest-mob kills (SlayerQuests).
MINIS = [
    ("REVENANT_SYCOPHANT", "zombie", 3, "Ghoul Sycophant", "ZOMBIE", 360),
    ("REVENANT_CHAMPION", "zombie", 4, "Ghoul Champion", "ZOMBIE", 600),
    ("DEFORMED_REVENANT", "zombie", 4, "Deformed Ghoul", "ZOMBIE", 1200),
    ("ATONED_CHAMPION", "zombie", 5, "Penitent Champion", "ZOMBIE", 2000),
    ("ATONED_REVENANT", "zombie", 5, "Penitent Ghoul", "ZOMBIE", 3000),
    ("TARANTULA_VERMIN", "spider", 3, "Brood Vermin", "SPIDER", 220),
    ("TARANTULA_BEAST", "spider", 4, "Brood Beast", "SPIDER", 450),
    ("MUTANT_TARANTULA", "spider", 4, "Mutant Broodweaver", "SPIDER", 1200),
    ("PACK_ENFORCER", "wolf", 3, "Pack Enforcer", "WOLF", 70),
    ("SVEN_FOLLOWER", "wolf", 4, "Fenris Follower", "WOLF", 300),
    ("SVEN_ALPHA", "wolf", 4, "Fenris Alpha", "WOLF", 900),
    ("VOIDLING_DEVOTEE", "enderman", 3, "Nullshade Devotee", "ENDERMAN", 1500),
    ("VOIDLING_RADICAL", "enderman", 4, "Nullshade Radical", "ENDERMAN", 3000),
    ("VOIDCRAZED_MANIAC", "enderman", 4, "Void-Mad Maniac", "ENDERMAN", 6000),
    ("FLARE_DEMON", "blaze", 3, "Flare Demon", "BLAZE", 1200),
    ("KINDLEHEART_DEMON", "blaze", 4, "Emberheart Demon", "BLAZE", 2400),
    ("BURNINGSOUL_DEMON", "blaze", 4, "Searsoul Demon", "BLAZE", 8000),
]
minis_out = []
for key, typ, tier, ours, ent, cxp in MINIS:
    d = neu(key + "_MINIBOSS")
    ref = strip(d["displayname"]).replace(" (Miniboss)", "")
    rn("miniboss:" + key, ref, ours)
    hp = 0
    for l in d["lore"]:
        mm = re.search(r"Health: ([\d,]+)", strip(l))
        if mm:
            hp = int(mm.group(1).replace(",", ""))
    rec = d["recipes"][0]
    lvl = rec.get("level", 100)
    cx = rec.get("combat_xp", cxp)
    drop = rec["drops"][0]["id"]
    item, _, amt = drop.partition(":")
    amt = int(amt or 1)
    # TUNE: damage per hit is not published for minibosses. 3/4 of the DPS of the boss tier they first appear in,
    # never above 1/250 of their health, at least 50 (1/250 alone had a Void-Mad Maniac hit for 300,000).
    tdef = next(x for x in TYPES if x["id"] == typ)
    dmg = max(50, int(min(hp / 250, tdef["dps"][tier - 1] * 0.75)))
    mid = "slayer_mini_" + key.lower()
    mobs.append({"id": mid, "name": ours, "level": lvl, "entity": ent, "health": hp, "damage": dmg, "combatXp": cx,
                 "coins": 0, "drops": [{"item": item, "min": amt, "max": amt, "chance": 1.0}],
                 "family": "slayer_miniboss_" + typ, "slayer": typ, "behaviours": ["slayer_minion"],
                 "flags": ["aggressive", "no_knockback"] + (["fire_immune"] if typ == "blaze" else []),
                 "extra": {"miniboss": True, "types": {"zombie": ["undead"], "spider": ["arthropod"], "wolf": ["wolf", "animal"],
                                                       "enderman": ["ender", "enderman"], "blaze": ["infernal"]}[typ],
                           "lootSource": "SLAYER"}})
    minis_out.append({"mob": mid, "type": typ, "minTier": tier, "combatXp": cx})

# ---------------------------------------------------------------------------------------------------------------
# slayers.json
# ---------------------------------------------------------------------------------------------------------------
types_out = []
for t in TYPES:
    loot, token_w = build_loot(t)
    n = len(t["hp"])
    xp_table = LEVELING["slayer_xp"][t["id"]]
    sxp = LEVELING["slayer_boss_xp_type"].get(t["id"], SLAYER_XP)
    tiers = []
    for i in range(n):
        tier = i + 1
        req = []
        if t["combatGate"][i] > 0:
            req.append("skill:combat:%d" % t["combatGate"][i])
        if t["prevTier"] and tier > 1:
            req.append("slayerkill:%s:%d" % (t["id"], tier - 1))
        if tier == 5 and t["t5slayer"]:
            req.append("slayer:%s:%d" % (t["id"], t["t5slayer"]))
        tiers.append({"tier": tier, "mob": "slayer_%s_%d" % (t["id"], tier), "health": t["hp"][i], "dps": t["dps"][i],
                      "trueDps": t["trueDps"][i], "level": t["level"][i], "cost": t["cost"][i], "spawnXp": t["spawnXp"][i],
                      "slayerXp": sxp[i], "combatXp": BOSS_COMBAT_XP[i], "bits": BITS[i], "heal": HEAL[i],
                      "requirement": " & ".join(req) if req else None, "difficulty": t["difficulty"][i],
                      "tokenWeight": token_w[i]})
    rewards = []
    for lvl in range(1, len(xp_table) + 1):
        rewards.append({"level": lvl, "stats": t["rewards"].get(lvl, {}), "notes": t["notes"].get(lvl, []),
                        "sbXp": SB_XP[lvl - 1]})
    ab = [{"tier": a[0], "name": a[1], "desc": a[2]} for a in t["abilities"]]
    out = {"id": t["id"], "name": t["name"], "ref": t["ref"], "mobWord": t["mobWord"], "color": t["color"],
           "icon": t["icon"], "entities": t["entities"], "unlock": t["unlock"], "xp": xp_table,
           "levelNames": t["levelNames"], "tiers": tiers, "rewards": rewards, "abilities": ab, "loot": loot,
           "minibosses": [m for m in minis_out if m["type"] == t["id"]]}
    if t.get("t5name"):
        out["t5name"] = t["t5name"]
    if "immolate" in t:
        out["immolate"] = t["immolate"]
        out["demons"] = t["demons"]
    types_out.append(out)

os.makedirs(OUT, exist_ok=True)


def dump(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")


# accessories request 3 (wave 5): explicit monster families for mobs whose entity type does not say it (an egg sac is
# a SLIME, a Blood Gargoyle a VEX). accessories' MobTypes reads extra.mobTypes and then skips inference, so the list
# is the whole answer: our "types" minus the tags that are not families.
NOT_FAMILIES = {"egg_sac", "enderman", "wolf"}
for m in mobs:
    fams = [t for t in m.get("extra", {}).get("types", []) if t not in NOT_FAMILIES]
    if fams:
        m["extra"]["mobTypes"] = fams

dump("slayers.json", {"npcName": NPC_NAME, "types": types_out})
dump("items.json", items_own)
dump("items_fallback.json", items_fb)
dump("mobs.json", mobs)
# Recipes whose ingredients no module registers yet (e.g. a Crystal Hollows item) go to recipes_late.json; the
# module registers those in postEnable only when every ingredient exists, so core's recipe self-test stays green.
OTHERS = set()
for f in glob.glob(os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "*", "*.json")):
    if os.path.basename(os.path.dirname(f)) == "slayer":
        continue
    try:
        arr = json.load(open(f, encoding="utf-8"))
    except Exception:
        continue
    if isinstance(arr, list):
        for e in arr:
            if isinstance(e, dict) and "id" in e and ("material" in e or "rarity" in e):
                OTHERS.add(e["id"])
KNOWN = OTHERS | {d["id"] for d in items_own} | {d["id"] for d in items_fb}
early, late = [], []
for r in recipes:
    ing = [c.split(":")[0] for c in r["grid"] if c]
    (early if all(i in KNOWN for i in ing) else late).append(r)
dump("recipes.json", early)
dump("recipes_late.json", late)
print("recipes early", len(early), "late", [r["result"] for r in late])

# names doc
seen = set()
lines = ["# slayer - renames", "",
         "Generated by `tools/gen/slayer/gen_slayer.py`. Ids never change; only display names do.", "",
         "| id | reference name | our name |", "|---|---|---|"]
for iid, ref, ours in RENAMES:
    if (iid, ours) in seen:
        continue
    seen.add((iid, ours))
    lines.append("| `%s` | %s | %s |" % (iid, ref, ours))
lines += ["", "Ability names that were proper nouns: Malevolent Hitshield -> Hitshield, Yang Glyphs -> Void Glyphs,",
          "Broken Heart Radiation -> Heartbreak Beams, Hellion Shield -> Attuned Shield, Demonsplit -> Demon Split,",
          "DDR Apocalypse -> Fire Pit Dance, Web of Lies -> Web Cocoon, Noxious Paralysis -> Paralytic Venom,",
          "Till Death Do Us Part -> Bound in Death, Stonewrath -> Stone Wrath, Killer Springs -> Spring Trap,",
          "Impel -> Command, Twinclaws -> Twin Claws. Set bonuses: Trolling the Reaper -> Grave Trolling,",
          "Octodexterity -> Eightfold Strike, Vivacious Darkness -> Vivid Darkness, Armor of the Pack -> Pack Spirit.",
          "Slayer level names are our own ladder (see slayers.json `levelNames`).",
          "Hunting shards in the boss loot (`SHARD_*`) carry the hunting module's own names (docs/names/hunting.md),",
          "copied at generation time so the drop line and the Hunting Box agree.", ""]
with open(NAMES, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))

print("types", len(types_out), "items", len(items_own), "fallback", len(items_fb), "mobs", len(mobs), "recipes", len(recipes))
for t in types_out:
    print(" ", t["id"], "loot", len(t["loot"]), "tokenW", [x["tokenWeight"] for x in t["tiers"]])
