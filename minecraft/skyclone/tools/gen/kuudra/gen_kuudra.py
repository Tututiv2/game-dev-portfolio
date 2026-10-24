"""Generate data/kuudra/*.json (the Magma Leviathan hunt, reference: Kuudra).

    python tools/gen/kuudra/gen_kuudra.py

Writes plugin/src/main/resources/data/kuudra/:
  items.json   items this module OWNS (Leviathan-only drops + the in-run perk item)
  mobs.json    the Leviathan (one def per tier) and its brood (13 kinds x 5 tiers)
  tiers.json   the five hunt tiers: research 04 sec 4.2-4.5 numbers + TUNE fight constants
  perks.json   the in-run perk shop (research 04 sec 4.4 "tokens & perk shop": our own perk set)
  loot.json    free + paid chest tables per tier: the June-2025 chest (guaranteed Kraken/teeth/essence + the wiki's
               real per-tier slot-1 / slot-2 weights, SkyblockMoney/work/kuudra.md sec 2; audit 2026-10-06 K1-K23)
  npcs.json    skins for the module's NPCs (from NEU)

Sources:
  research/04-slayers-dungeons-kuudra.md sec 4    tiers, rep, keys, waves, HP, phases, loot
  research/data/neu-repo/constants/sblevels.json  defeat_kuudra_xp 20/40/60/80/100 (first clear per tier)
  research/data/neu-repo/constants/bestiary.json  the brood (13 kinds, levels 100..500 by tier)
  research/data/neu-repo/items/*.json             skins
Numbers that the research does not give are marked TUNE here; every one is also a dial or a field
the module reads at use time.  Every description is our own wording; proper nouns are renamed
(docs/names/kuudra.md).
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "research", "data")
NEU = os.path.join(RES, "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "kuudra")

TIER_IDS = ["basic", "hot", "burning", "fiery", "infernal"]
TIER_NAMES = ["Basic", "Hot", "Burning", "Fiery", "Infernal"]
TIER_COLORS = ["a", "e", "6", "c", "4"]
KEYS = ["KUUDRA_TIER_KEY", "KUUDRA_HOT_TIER_KEY", "KUUDRA_BURNING_TIER_KEY", "KUUDRA_FIERY_TIER_KEY",
        "KUUDRA_INFERNAL_TIER_KEY"]


def skin(name):
    p = os.path.join(NEU, name + ".json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', d.get("nbttag", ""))
    return m.group(1) if m else None


def sblevel_xp():
    with open(os.path.join(RES, "neu-repo", "constants", "sblevels.json"), encoding="utf-8") as f:
        d = json.load(f)
    return d["slaying_task"]["defeat_kuudra_xp"]


# ---------------------------------------------------------------------------------------------
# tiers
# ---------------------------------------------------------------------------------------------

def tiers():
    xp = sblevel_xp()                                  # [20, 40, 60, 80, 100]
    rep = [0, 1000, 3000, 7000, 12000]                 # research 04 sec 4.2
    wave = [35, 30, 25, 20, 15]                        # research 04 sec 4.2
    coll = [1, 2, 3, 4, 5]                             # research 04 sec 4.2 boss collection points
    boss_hp = [10_000_000, 25_000_000, 60_000_000, 150_000_000, 800_000_000]   # research 04 sec 4.4 clone defaults
    # The June-2025 chest (SkyblockMoney/work/kuudra.md sec 1-2, audit K5-K8): every paid chest holds a GUARANTEED
    # part - Kraken shards 1/1/2/2/3 (official changelog), Leviathan Teeth 1/1/2/2/3 (wiki Kuudra Teeth) and Crimson
    # Essence - plus one weighted slot-1 and one weighted slot-2 roll. Free chest essence is the official 10/25/50/100/200.
    free_ess = [[10, 10], [25, 25], [50, 50], [100, 100], [200, 200]]
    # ESTIMATE: the reference publishes only Infernal = 2,000 (wiki, unconfirmed); the lower tiers are ~10x their free
    # chest (research sec 2). Dial kuudra.paid-essence-scale multiplies all five.
    paid_ess = [[100, 100], [250, 250], [500, 500], [1000, 1000], [2000, 2000]]
    teeth = [[1, 1], [1, 1], [2, 2], [2, 2], [3, 3]]
    kraken = [1, 1, 2, 2, 3]
    mob_hp = [1, 2.5, 6, 15, 40]                       # TUNE: brood health multiplier per tier
    mob_dmg = [1, 1.6, 2.5, 4, 6]                      # TUNE: brood damage multiplier per tier
    boss_dmg = [800, 1300, 2000, 3200, 4800]           # TUNE: the Leviathan's attacks, before defense
    out = []
    for i, t in enumerate(TIER_IDS):
        req = "faction:any" if i == 0 else f"faction:any:{rep[i]}"
        out.append({
            "id": t, "index": i, "name": TIER_NAMES[i], "color": TIER_COLORS[i],
            "key": KEYS[i], "rep": rep[i], "requirement": req,
            "sbXp": xp[i], "waveSeconds": wave[i], "collection": coll[i],
            "bossHp": boss_hp[i], "bossDamage": boss_dmg[i],
            "mobHp": mob_hp[i], "mobDamage": mob_dmg[i],
            "stun": i >= 2,                            # research: stomach phase on Burning, Fiery, Infernal
            "lair": i == 4,                            # research: lair phase on Infernal only
            "freeEssence": free_ess[i], "paidEssence": paid_ess[i], "teeth": teeth[i], "kraken": kraken[i],
            "bookLevel": i + 1,                        # mana books are level I at Basic .. V at Infernal (K4)
        })
    return out


# ---------------------------------------------------------------------------------------------
# the fight constants (one object, read by the module; every one also has a dial multiplier)
# ---------------------------------------------------------------------------------------------

FIGHT = {
    "supplies": 6,                 # research 04 sec 4.4 (?) default 6
    "fuelPerVolley": 4,            # research 04 sec 4.4: 4 fuel cells
    "volleyPercent": 15.0,         # TUNE: % of max HP a volley removes (Basic/Hot always, Burning+ when stunned)
    "unstunnedPercent": 1.5,       # TUNE: Burning+ volley without a stun ("does very little")
    "dpsAtPercent": 40.0,          # TUNE: the ballista can take the beast down to here; then the DPS phase
    "lairAtPercent": 25.0,         # research 04 sec 4.4: Infernal lair at 25%
    "pickupSeconds": 1.5,          # TUNE
    "deliverSeconds": 3.0,         # TUNE: "standing still while a meter fills"
    "fuelPickupSeconds": 0.6,      # TUNE
    "fuelLoadSeconds": 1.0,        # TUNE
    "buildPercentPerSecond": 1.4,  # TUNE: per builder, before perks/accessories
    "stunSeconds": 15,             # TUNE
    "stomachSeconds": 30,          # TUNE
    "orbs": 6,                     # TUNE
    "orbHits": 3,                  # TUNE
    "digestPercent": 2.5,          # TUNE: % max HP per second inside
    "devourSeconds": 40,           # TUNE: Burning+ the beast swallows someone this often (ballista phase)
    "spitSeconds": 12,             # TUNE
    "slamSeconds": 9,              # TUNE
    "rampageSeconds": 6,           # TUNE
    "moveSeconds": 25,             # TUNE
    "timeLimitMinutes": 25,        # TUNE
    "rewardSeconds": 90,           # TUNE
    "maxAlive": 28,                # TUNE
    "startTokens": 25,             # TUNE
    "fuelOnGround": 4,             # TUNE
    "fuelRespawnSeconds": 6,       # TUNE
    "fuelDropChance": 0.25,        # TUNE: a brood kill in the ballista phase drops a fuel cell
    "tokens": {                    # TUNE: research names the sources (kills, objectives, dropships 150)
        "pickup": 10, "deliver": 30, "buildPerPercent": 0.5, "fuelLoad": 15, "fire": 20,
        "stun": 60, "orb": 10, "dropship": 150,
    },
}


# ---------------------------------------------------------------------------------------------
# mobs: the brood (bestiary.json "kuudra": 13 kinds, levels 100..500) + one Leviathan per tier
# ---------------------------------------------------------------------------------------------

# kind, our name, family (progression bestiary key), entity, base hp, base dmg, speed, flags, tokens,
# behaviours, equipment, role ("common" waves, "elite" scheduled), wave weight, first wave
BROOD = [
    ("follower", "Leviathan Follower", "kuudra_follower", "HUSK", 25_000, 400, 1.1, ["aggressive"], 4,
     [], {"HEAD": "MAGMA_BLOCK", "CHEST": "LEATHER_CHESTPLATE", "HAND": "STONE_AXE"}, "common", 10, 1),
    ("landmine", "Leviathan Landmine", "kuudra_landmine", "MAGMA_CUBE", 8_000, 900, 1.0,
     ["no_ai", "fire_immune", "no_knockback"], 3, ["kuudra_landmine"], {}, "common", 3, 1),
    ("magma_cube", "Inferno Magma Cube", "inferno_magma_cube", "MAGMA_CUBE", 18_000, 350, 1.0,
     ["aggressive", "fire_immune"], 4, [], {}, "common", 4, 1),
    ("berserker", "Leviathan Berserker", "kuudra_berserker", "WITHER_SKELETON", 40_000, 650, 1.25,
     ["aggressive"], 8, [], {"HAND": "IRON_AXE"}, "common", 3, 4),
    ("knocker", "Leviathan Knocker", "kuudra_knocker", "VINDICATOR", 60_000, 500, 1.0,
     ["aggressive"], 10, ["kuudra_knocker"], {"HAND": "IRON_AXE"}, "common", 3, 3),
    ("wandering_blaze", "Wandering Blaze", "wandering_blaze", "BLAZE", 30_000, 450, 1.0,
     ["aggressive", "fire_immune", "flying"], 8, [], {}, "common", 3, 2),
    ("blight", "Blight", "blight", "BOGGED", 35_000, 500, 1.1,
     ["aggressive"], 8, ["kuudra_blight"], {}, "common", 3, 3),
    ("explosive_imp", "Explosive Imp", "explosive_imp", "ZOMBIFIED_PIGLIN", 15_000, 1200, 1.35,
     ["aggressive", "baby", "fire_immune"], 5, ["kuudra_imp"], {"HEAD": "TNT"}, "common", 2, 2),
    ("magma_follower", "Magma Follower", "magma_follower", "ZOMBIFIED_PIGLIN", 120_000, 900, 1.0,
     ["aggressive", "fire_immune", "no_knockback"], 25, [], {"HEAD": "MAGMA_BLOCK", "HAND": "GOLDEN_AXE"}, "elite", 0, 0),
    ("dropship", "Dropship", "dropship", "GHAST", 300_000, 0, 1.0,
     ["no_ai", "fire_immune", "flying", "no_knockback"], 0, [], {}, "elite", 0, 0),
    ("wither_sentry", "Wither Sentry", "wither_sentry", "SKELETON", 80_000, 700, 0.9,
     ["aggressive"], 15, [], {"HEAD": "WITHER_SKELETON_SKULL", "HAND": "BOW"}, "elite", 0, 0),
    ("blazing_golem", "Blazing Golem", "blazing_golem", "IRON_GOLEM", 250_000, 1500, 0.85,
     ["aggressive", "no_knockback", "fire_immune"], 35, ["kuudra_knocker"], {}, "elite", 0, 0),
    ("slasher", "Leviathan Slasher", "kuudra_slasher", "WITHER_SKELETON", 500_000, 2000, 1.1,
     ["aggressive", "no_knockback"], 60, [], {"HAND": "NETHERITE_SWORD", "HEAD": "NETHERITE_HELMET"}, "elite", 0, 0),
    # Not in the reference bestiary; research 04 sec 4.4 lists them for Infernal only.
    ("cindermite", "Cindermite", "cindermite", "ENDERMITE", 10_000, 300, 1.4,
     ["aggressive", "fire_immune"], 2, [], {}, "lair", 0, 0),
]

BOSS_XP = [1500, 3000, 6000, 12000, 24000]   # TUNE: combat XP for the killing blow


def mobs(tier_list):
    out = []
    for t in tier_list:
        i = t["index"]
        lvl = (i + 1) * 100                         # bestiary: <kind>_100 .. <kind>_500
        for (kind, name, fam, ent, hp, dmg, spd, flags, tok, beh, eq, role, weight, first) in BROOD:
            out.append({
                "id": f"kuudra_{kind}_{t['id']}", "name": name, "level": lvl, "entity": ent,
                "health": round(hp * t["mobHp"]), "damage": round(dmg * t["mobDamage"]),
                "speed": spd, "combatXp": round(40 * (i + 1) * (2 if role == "elite" else 1)),
                "coins": round(15 * (i + 1) * (3 if role == "elite" else 1)),
                "family": fam, "flags": flags, "behaviours": beh, "equipment": eq, "drops": [],
                "extra": {"kuudraKind": kind, "tier": t["id"], "tokens": round(tok * (1 + 0.5 * i)),
                          "role": role, "weight": weight, "firstWave": first,
                          "mobTypes": ["kuudra_follower", "magmatic"], "nameplate": "show"},
            })
        out.append({
            "id": f"leviathan_{t['id']}", "name": "Magma Leviathan", "level": lvl, "entity": "MAGMA_CUBE",
            "health": t["bossHp"], "damage": t["bossDamage"], "speed": 1.0,
            "combatXp": BOSS_XP[i], "coins": 0, "family": "kuudra",
            "flags": ["boss", "no_ai", "no_knockback", "fire_immune"], "behaviours": ["kuudra_boss"],
            "equipment": {}, "drops": [],
            "extra": {"kuudraKind": "boss", "tier": t["id"], "size": 12, "scale": 2.4, "mobTypes": ["magmatic"]},
        })
    return out


# ---------------------------------------------------------------------------------------------
# items this module owns
# ---------------------------------------------------------------------------------------------

def items():
    return [
        {
            "id": "KUUDRA_SHOP_ITEM", "name": "Hunt Perks", "material": "PLAYER_HEAD", "rarity": "SPECIAL",
            "category": "NONE", "skull": skin("KUUDRA_SHOP_ITEM"),
            "lore": ["&7Spend the tokens you earn in a hunt", "&7on perks that last until it ends."],
            "abilities": [{"id": "kuudra_open_shop", "name": "Open Perks", "trigger": "RIGHT_CLICK", "mana": 0,
                           "cooldown": 0, "lore": ["&7Open the perk shop for this hunt."]}],
            "tags": ["soulbound", "no_trade", "no_ah", "no_reforge"], "maxStack": 1,
            "extra": {"kuudraRun": True},
        },
        {
            "id": "HEAT_CORE", "name": "Heat Core", "material": "PLAYER_HEAD", "rarity": "SPECIAL",
            "category": "MATERIAL", "skull": skin("HEAT_CORE"), "glow": True,
            "lore": ["&7A coal-hot heart torn from the", "&cMagma Leviathan&7. Minion makers fold it",
                     "&7into &5Enchanted Lava Buckets &7to push", "&7their speed bonus to &a30% &7and &a35%&7."],
            "tags": ["no_reforge"], "npcSell": 0,
            "extra": {"kuudraDrop": True},
        },
        {
            # reference WHEEL_OF_FATE: "consumed inside a Kuudra chest to reroll the Shard inside into a higher
            # rarity one" (NEU lore, the 2025 shard rework). Spent from the paid-chest menu (KMenus.spin).
            "id": "WHEEL_OF_FATE", "name": "Wheel of Embers", "material": "PLAYER_HEAD", "rarity": "RARE",
            "category": "MATERIAL", "skull": skin("WHEEL_OF_FATE"),
            "lore": ["&7Spin it inside a &cLeviathan &7paid chest", "&7before you open it: the &dshard &7in the",
                     "&7chest turns into one of a &ahigher rarity&7.", "",
                     "&8Some say the beast's luck rubs off."],
            "tags": ["no_reforge"], "npcSell": 0,
            "extra": {"kuudraDrop": True},
        },
        {
            "id": "TENTACLE_DYE", "name": "Tentacle Dye", "material": "PLAYER_HEAD", "rarity": "LEGENDARY",
            "category": "MATERIAL", "skull": skin("TENTACLE_DYE"),
            "lore": ["&8Combinable in Anvil", "", "&7Stains an armor piece the deep", "&7slate of the Leviathan's arms",
                     "&7(&5#324D6C&7).", "", "&8A trophy few crews ever bring home."],
            "tags": ["no_reforge"], "npcSell": 0,
            "extra": {"kuudraDrop": True, "dyeColor": "#324D6C"},
        },
        {
            # reference ANANKE_FEATHER (NEU: RARE consumable, "add a large amount of progress to an RNG Meter of your
            # choosing"; Infernal paid chest slot 1, weight 0.2625, audit K21). Registered here because this chest is
            # its only source. The RNG meters live in dungeons and slayer: spending it is theirs to wire (extra
            # rngMeterFeather), see docs/handoff/kuudra.md.
            "id": "ANANKE_FEATHER", "name": "Ananke Feather", "material": "FEATHER", "rarity": "RARE",
            "category": "CONSUMABLE", "glow": True,
            "lore": ["&7A feather shed by the bird that", "&7keeps the Leviathan's luck. Spent",
                     "&7at an &dRNG Meter&7, it pushes the meter", "&7a long way toward its prize.", "",
                     "&8Only the Infernal hunt ever turns one up."],
            "tags": ["no_reforge"], "npcSell": 0,
            "extra": {"kuudraDrop": True, "rngMeterFeather": True},
        },
    ]


# ---------------------------------------------------------------------------------------------
# perks (research 04 sec 4.4: "faster supply pickup, build speed, cannon access, pickaxe mining speed,
# auto-revive, and the Support route"; the set and its names are our own)
# ---------------------------------------------------------------------------------------------

PERKS = [
    {"id": "sure_grip", "name": "Sure Grip", "icon": "CHEST", "max": 3, "cost": [40, 60, 80], "per": 15,
     "lore": "&7Pick up and set down supplies and fuel &a{v}% &7faster.", "phase": "any"},
    {"id": "master_mason", "name": "Master Mason", "icon": "BRICKS", "max": 3, "cost": [50, 75, 100], "per": 25,
     "lore": "&7Build the ballista &a{v}% &7faster.", "phase": "any"},
    {"id": "cannon_pass", "name": "Cannon Pass", "icon": "TNT", "max": 1, "cost": [60], "per": 1,
     "lore": "&7Lets you ride the cannon into the beast's maw.", "phase": "stun"},
    {"id": "gut_pick", "name": "Gut Pick", "icon": "IRON_PICKAXE", "max": 2, "cost": [50, 90], "per": 1,
     "lore": "&7Organs inside the beast need &a{v} &7fewer strikes.", "phase": "stun"},
    {"id": "second_wind", "name": "Second Wind", "icon": "TOTEM_OF_UNDYING", "max": 1, "cost": [120], "per": 40,
     "lore": "&7Survive one killing blow and recover &a{v}% &7health.", "phase": "any"},
    {"id": "lifeline", "name": "Lifeline", "icon": "GOLDEN_APPLE", "max": 1, "cost": [150], "per": 1,
     "lore": "&7Revive fallen crewmates in the lair by", "lore2": "&7crouching beside their marker.", "phase": "lair"},
    {"id": "ember_edge", "name": "Ember Edge", "icon": "BLAZE_POWDER", "max": 3, "cost": [60, 90, 120], "per": 10,
     "lore": "&7Deal &c+{v}% &7damage to the Leviathan and its brood.", "phase": "any"},
    {"id": "lava_hide", "name": "Lava Hide", "icon": "MAGMA_CREAM", "max": 3, "cost": [50, 75, 100], "per": 8,
     "lore": "&7Take &a{v}% &7less damage from the hunt.", "phase": "any"},
    {"id": "overcharge", "name": "Overcharge", "icon": "FIRE_CHARGE", "max": 2, "cost": [80, 120], "per": 10,
     "lore": "&7Ballista volleys you fire hit &c+{v}% &7harder.", "phase": "any"},
    {"id": "fleetfoot", "name": "Fleetfoot", "icon": "FEATHER", "max": 2, "cost": [30, 50], "per": 15,
     "lore": "&7Gain &f+{v} Speed &7for the rest of the hunt.", "phase": "any"},
    {"id": "fuel_magnet", "name": "Fuel Magnet", "icon": "LODESTONE", "max": 1, "cost": [40], "per": 4,
     "lore": "&7Grab fuel cells within &a{v} blocks &7without crouching.", "phase": "any"},
    {"id": "mending_tonic", "name": "Mending Tonic", "icon": "POTION", "max": 0, "cost": [25], "per": 50,
     "lore": "&7Drink now: heal &a{v}% &7of your health.", "phase": "any"},
]


# ---------------------------------------------------------------------------------------------
# loot: the June-2025 chest (audit K1-K12, K19-K23). Weights are the reference's per-tier slot weights, transcribed
# from hypixelskyblock.minecraft.wiki Kuudra/Loot (all five tabs; every slot sum-checked against its printed
# percentages) in SkyblockMoney/work/kuudra.md sec 2 and its ev.py. The wiki flags them "not confirmed"; they are the
# best published numbers. A paid chest is: the GUARANTEED part + one slot-1 roll + one slot-2 roll + the extra rares.
# ---------------------------------------------------------------------------------------------

# Only BASIC-tier armour drops, from every tier (minecraft.wiki Crimson Armor: higher tiers come from prestige).
ARMOR_SETS = ["CRIMSON", "AURORA", "TERROR", "FERVOR", "HOLLOW"]
ARMOR_PIECES = ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"]
ARMOR_W = 2.875                                       # each of the 20 pieces, every tier
MOLTEN = ["MOLTEN_BELT", "MOLTEN_CLOAK", "MOLTEN_BRACELET", "MOLTEN_NECKLACE"]
BOOKS = ["STRONG_MANA", "HARDENED_MANA", "FEROCIOUS_MANA", "MANA_VAMPIRE"]   # 16.5 each, level == tier (K4)
KRAKEN = "SHARD_KRAKEN"                               # guaranteed, never in the random tables (June 2025)

# reference names in the wiki table -> our ids (Aurora Staff is RUNIC_STAFF, Hellstorm Wand is HELLSTORM_STAFF)
ID = {
    "Aurora Staff": "RUNIC_STAFF", "Hollow Wand": "HOLLOW_WAND", "Burning Kuudra Core": "BURNING_KUUDRA_CORE",
    "Mandraa": "MANDRAA", "Ananke Feather": "ANANKE_FEATHER", "Tormentor": "TORMENTOR", "Hellstorm Wand": "HELLSTORM_STAFF",
    "Wheel of Fate": "WHEEL_OF_FATE", "Inferno": "ENCHANTMENT_ULTIMATE_INFERNO_1",
    "Fatal Tempo": "ENCHANTMENT_ULTIMATE_FATAL_TEMPO_1", "Tentacle": "KUUDRA_TENTACLE", "Teeth": "KUUDRA_TEETH",
    "Essence": "ESSENCE_CRIMSON", "Heavy Pearl": "HEAVY_PEARL", "Mandible": "KUUDRA_MANDIBLE",
    # "Dusty Travel Scroll to the Kuudra Skull": nobody registers it yet (it needs a crimson warp) -> optional
    "Dusty Scroll": "NETHER_FORTRESS_BOSS_TRAVEL_SCROLL",
    "Bezal": "SHARD_BEZAL", "MagmaSlug": "SHARD_MAGMA_SLUG", "Kada": "SHARD_KADA_KNIGHT",
    "WitherSpectre": "SHARD_WITHER_SPECTER", "Matcho": "SHARD_MATCHO", "LavaFlame": "SHARD_LAVA_FLAME",
    "FireEel": "SHARD_FIRE_EEL", "Flare": "SHARD_FLARE", "BDX": "SHARD_BARBARIAN_DUKE_X", "Hellwisp": "SHARD_HELLWISP",
    "XYZ": "SHARD_XYZ", "Taurus": "SHARD_TAURUS", "Cinderbat": "SHARD_CINDER_BAT", "Moltenfish": "SHARD_MOLTENFISH",
    "Jawbus": "SHARD_LORD_JAWBUS", "Daemon": "SHARD_DAEMON", "Ananke": "SHARD_ANANKE",
}
OPTIONAL = {"NETHER_FORTRESS_BOSS_TRAVEL_SCROLL"}

# (name, count, weight) per tier and slot, besides the 20 armour pieces (slot 1) and the 4 books, Tentacle, Wheel and
# the two ultimate books (slot 2)
WEAPONS = [("Aurora Staff", 1, .7), ("Hollow Wand", 1, .7)]
SLOT1 = [
    WEAPONS + [(m, 1, .8) for m in MOLTEN] + [("Bezal", 1, 4.5)],
    WEAPONS + [(m, 1, .9) for m in MOLTEN] + [("Bezal", 1, 4.5), ("MagmaSlug", 1, 3.75)],
    WEAPONS + [(m, 1, 1.0) for m in MOLTEN] + [("Bezal", 1, 4.5), ("MagmaSlug", 1, 3.75), ("Burning Kuudra Core", 1, .1),
        ("Mandraa", 1, .8), ("Kada", 1, 3.05), ("WitherSpectre", 1, 3.05), ("Matcho", 1, 3.05), ("LavaFlame", 1, 2.5)],
    WEAPONS + [(m, 1, 1.1) for m in MOLTEN] + [("Bezal", 1, 4.5), ("MagmaSlug", 1, 3.75), ("Burning Kuudra Core", 1, .3),
        ("Mandraa", 1, 1.0), ("Kada", 1, 3.05), ("WitherSpectre", 1, 3.05), ("Matcho", 1, 3.05), ("LavaFlame", 1, 2.5),
        ("FireEel", 1, 1.9), ("Flare", 1, 1.9), ("BDX", 1, 1.9), ("Hellwisp", 1, 1.55), ("XYZ", 1, 1.25)],
    WEAPONS + [(m, 1, 1.2) for m in MOLTEN] + [("Ananke", 1, .05), ("Hellstorm Wand", 1, .1), ("Tormentor", 1, .1),
        ("Ananke Feather", 1, .2625), ("Burning Kuudra Core", 1, .5), ("Daemon", 1, .5), ("Jawbus", 1, .5),
        ("Moltenfish", 1, .5), ("Cinderbat", 1, .65), ("Taurus", 1, .65), ("Mandraa", 1, 1.2), ("XYZ", 1, 1.25),
        ("Hellwisp", 1, 1.55), ("BDX", 1, 1.9), ("FireEel", 1, 1.9), ("Flare", 1, 1.9), ("LavaFlame", 1, 2.5),
        ("Kada", 1, 3.05), ("Matcho", 1, 3.05), ("WitherSpectre", 1, 3.05), ("MagmaSlug", 1, 3.75), ("Bezal", 1, 4.5)],
]
TEMPO = [.02, .11, .2, .29, .38]                      # Fatal Tempo I and Inferno I, each (K3)
WHEEL = [2.2, 2.8, 3.4, 4.0, 4.6]                     # Wheel of Fate (K11)
BURNING_UP = [("Teeth", 5, 10), ("Essence", 500, 10), ("Kada", 1, 6.1), ("WitherSpectre", 1, 6.1), ("Matcho", 1, 6.1),
              ("LavaFlame", 1, 5)]
FIERY_UP = [("Teeth", 25, 1), ("Essence", 2500, 1), ("Heavy Pearl", 3, 10), ("FireEel", 1, 3.8), ("Flare", 1, 3.8),
            ("BDX", 1, 3.8), ("Hellwisp", 1, 3.1), ("XYZ", 1, 2.5)]
SLOT2 = [
    [("Bezal", 1, 9)],
    [("Bezal", 1, 9), ("MagmaSlug", 1, 7.5)],
    [("Bezal", 1, 9), ("MagmaSlug", 1, 7.5)] + BURNING_UP,
    [("Bezal", 1, 9), ("MagmaSlug", 1, 7.5)] + BURNING_UP + FIERY_UP,
    [("Bezal", 1, 9), ("MagmaSlug", 1, 7.5)] + BURNING_UP + FIERY_UP + [
        ("Teeth", 100, .1), ("Essence", 10000, .1), ("Ananke", 1, .1), ("Heavy Pearl", 10, 1), ("Jawbus", 1, 1),
        ("Daemon", 1, 1), ("Moltenfish", 1, 1), ("Taurus", 1, 1.3), ("Cinderbat", 1, 1.3), ("Dusty Scroll", 1, 1.5),
        ("Mandible", 1, 3)],
]


def entry(name_or_id, count, weight):
    iid = ID.get(name_or_id, name_or_id)
    e = {"item": iid, "count": count, "weight": weight}
    if iid in OPTIONAL:
        e["optional"] = True
    return e


def shard_rarities():
    """id -> rarity from the hunting module's generated shards.json (the reference's rarity)."""
    p = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "hunting", "shards.json")
    with open(p, encoding="utf-8") as f:
        return {s["id"]: s["rarity"] for s in json.load(f)}


def loot(tier_list):
    known = shard_rarities()
    # The Wheel of Embers (reference Wheel of Fate) re-rolls the chest's shard into a higher rarity: its ladder is
    # every shard any Kuudra chest can roll (the Kraken is guaranteed, not a roll, so it is not on it).
    ladder_ids = []
    for rows in SLOT1 + SLOT2:
        for (n, _, _) in rows:
            sid = ID.get(n, n)
            if sid.startswith("SHARD_") and sid not in ladder_ids:
                ladder_ids.append(sid)
    for sid in ladder_ids + [KRAKEN]:
        if sid not in known:
            raise SystemExit(f"shard {sid} is not in data/hunting/shards.json")
    ladder = [{"id": s, "rarity": known[s]} for s in ladder_ids]
    out = []
    for t in tier_list:
        i = t["index"]
        lvl = t["bookLevel"]
        slot1 = [entry(f"{s}_{p}", 1, ARMOR_W) for s in ARMOR_SETS for p in ARMOR_PIECES]
        slot1 += [entry(n, c, w) for (n, c, w) in SLOT1[i]]
        slot2 = [entry(f"ENCHANTMENT_{b}_{lvl}", 1, 16.5) for b in BOOKS]
        slot2 += [entry("Tentacle", 1, 16), entry("Wheel of Fate", 1, WHEEL[i]),
                  entry("Fatal Tempo", 1, TEMPO[i]), entry("Inferno", 1, TEMPO[i])]
        slot2 += [entry(n, c, w) for (n, c, w) in SLOT2[i]]
        # extra independent rolls the wiki table does not carry; kept from research 04 sec 4.5 (TUNE chances)
        rares = [{"item": "ENRAGER", "chance": [0.002, 0.003, 0.004, 0.005, 0.005][i]},
                 {"item": "HEAT_CORE", "chance": [0, 0, 0.004, 0.005, 0.006][i]},
                 # the Leviathan pet (pets id KUUDRA;<rarity>): the tier's own rarity, Common at Basic .. Legendary
                 {"item": "KUUDRA;%d" % i, "chance": 0.003}]
        if i == 4:
            rares.append({"item": "TENTACLE_DYE", "chance": 0.0001})
        out.append({
            "tier": t["id"],
            "freeEssence": t["freeEssence"],
            "paidEssence": t["paidEssence"],
            "guaranteed": [entry(KRAKEN, t["kraken"], 1), entry("Teeth", t["teeth"][0], 1)],
            "bookLevel": lvl,
            "slot1": slot1,
            "slot2": slot2,
            "shards": ladder,
            "rares": rares,
        })
    return out


def npcs():
    return {
        "ferryman": skin("KUUDRA_GATEKEEPER_NPC"),
        "quartermaster": skin("KUUDRA_ARCHEOLOGIST_NPC"),
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    t = tiers()
    files = {
        "tiers.json": {"tiers": t, "fight": FIGHT},
        "mobs.json": mobs(t),
        "items.json": items(),
        "perks.json": PERKS,
        "loot.json": loot(t),
        "npcs.json": npcs(),
    }
    for name, data in files.items():
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
            f.write("\n")
        n = len(data) if isinstance(data, list) else len(data.keys())
        print(f"wrote {name} ({n})")


if __name__ == "__main__":
    main()
