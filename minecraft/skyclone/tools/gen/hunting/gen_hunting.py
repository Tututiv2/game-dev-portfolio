#!/usr/bin/env python3
r"""
Generates the hunting module's data from the research dumps.

    PYTHONIOENCODING=utf-8 python tools/gen/hunting/gen_hunting.py

Inputs (read only):
  research/data/neu-repo/constants/attribute_shards.json   322 shards: rarity, alignment, families, id line (C1..L60)
  research/data/neu-repo/items/ATTRIBUTE_SHARD_*.json       per-shard lore (the attribute's level-I effect) + skull
  research/data/neu-repo/items/<tool>.json                  lassos, nets, black holes, huntaxes, huntraps, salts
  plugin/src/main/resources/data/*/mobs.json                every mob other modules registered (shard sources)

Outputs (plugin/src/main/resources/data/hunting/):
  shards.json     one row per shard: our display name, attribute, parsed effect, sources, critter/habitats
  items.json      ItemDefs: every shard + the hunting tools + Safari Essence + Safari tickets/capsules
  mobs.json       critter MobDefs (one per shard that has no other source)
  habitats.json   where critters live (island + area + which critters)
  recipes.json    the reference recipes for the tools (registered at runtime only if every ingredient exists)
  levelling.json  shards needed per attribute level, per rarity (reference table)

Numbers trace to the research; anything invented is marked "tune" in the row.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo")
DATA = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")
OUT = os.path.join(DATA, "hunting")

RARITIES = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"]
LETTER = {"C": "COMMON", "U": "UNCOMMON", "R": "RARE", "E": "EPIC", "L": "LEGENDARY"}

# ---------------------------------------------------------------------------------------------
# renames: invented proper nouns of the reference game -> ours (docs/names/hunting.md lists them)
# ---------------------------------------------------------------------------------------------
NAME_RENAMES = {
    "Phanpyre": "Duskfang", "Phanflare": "Marshflare", "Hideonleaf": "Leaflurker", "Hideongift": "Giftlurker",
    "Hideonsun": "Sunlurker", "Hideonsack": "Sacklurker", "Hideonbox": "Boxlurker", "Hideongeon": "Cryptlurker",
    "Hideondra": "Lavalurker", "Hideonring": "Ringlurker", "Hideyho": "Hollowpeek", "Hideonfloor": "Floorlurker",
    "Hideonwall": "Masonlurker", "Hideoncave": "Cavelurker", "Birries": "Berrybob", "Coralot": "Reeflotl",
    "Honeyhog": "Hivehog", "Shyworm": "Timidworm", "Brineling": "Saltling", "Chocobun": "Cocoabun",
    "Timil": "Springtail", "Bunbun": "Hopsy", "Honeybuzz": "Nectarbuzz", "Pollendart": "Pollenzip",
    "Pandarai": "Bamboo Ronin", "Fenlord": "Bog Baron", "Murderwing": "Gloomwing", "Shellwise": "Shellsage",
    "XYZ": "Arcanum Cube", "Cavernshade": "Gloomshade", "Silkbreeze": "Silkdrift", "Gemzie": "Gemkin",
    "Beeheemoth": "Hivecolossus", "Nilbog": "Goblinkin", "Tenebris": "Umbra", "Wartybug": "Wartmite",
    "Doomspiral": "Dread Vortex", "Hellwisp": "Infernal Wisp", "Etherdrake": "Voiddrake", "Molthorn": "Magmaspine",
    "Starborn": "Starchild", "Cocoaleech": "Cocoasucker", "Rainbug": "Prismbug", "Timestalk Clone": "Chrono Echo",
    "Wumpa": "Frostbumble", "Bambloom": "Bamboo Bloom", "Beaconmite": "Signalmite", "Invisibug": "Ghostbug",
    "Bitbug": "Bytebug", "Silentdepth": "Hushdeep", "Skeletor": "Skullwarden", "Quartzfang": "Quartzbite",
    "Lumisquid": "Lumen Squid", "Joydive": "Gleediver", "Stalagmight": "Stalagbrute", "Fungloom": "Fungleam",
    "Hewver": "Hewbeaver", "Snoozle": "Dozemole", "Gimmiegold": "Goldgrabber", "Nozzlenose": "Snoutfish",
    "Fluffling": "Fluffkin", "Zombuddy": "Ghoulpal", "Goldolot": "Goldlotl", "Troglobyte": "Cavebiter",
    "Cuboa": "Cubeboa", "Mossybit": "Mossling", "Lotum": "Lotuspad", "Bambuleaf": "Bambooleaf",
    "Mochibear": "Dumpling Bear", "Flipflopper": "Flopfish", "Areita": "Weavra", "Duplico": "Doppel",
    "Shuddersquid": "Quiversquid", "Solsnatcher": "Sunsnatcher", "Sepialot": "Inklotl", "Tewtil": "Lilyturtle",
    "Dustybit": "Dustling", "Honeymite": "Nectarmite", "Rabbit Mafioso": "Rabbit Racketeer",
    "Rabbit Godmother": "Rabbit Matriarch", "Rabbit Godfather": "Rabbit Patriarch", "Bonzo": "Grinko",
    "Scarf": "Veilwright", "Littlefoot": "Little Wanderer", "Inferno Demonlord": "Burningsoul Demon",
    "Loch Emperor": "Loch Sovereign", "Nessie": "Loch Wyrm", "Lord Jawbus": "Lord Gnashmaw", "Wiki Tiki": "Tiki Totem",
    "Kada Knight": "Ashen Knight", "Bezal": "Scorchling", "Barbarian Duke X": "Duke Brakkus the Tenth",
    "Arachne": "Silkmother", "Scatha": "Tunnel Wyrm", "Yog": "Cinderling", "Thyst": "Amethyst Leech",
    "Glacite Walker": "Rimestone Walker", "Carrot King": "Carrot Monarch", "End Stone Protector": "End Stone Colossus",
    "Soul of the Alpha": "Shade of the Alpha", "Howling Spirit": "Howling Wraith", "Matcho": "Tinderling",
    "Cinderbat": "Emberwing Bat", "Bal": "Magmaw", "Sea Serpent": "Sea Serpent", "Leviathan": "Abyssal Leviathan",
}

# words inside effect text / attribute names that are reference places, people or brands
TEXT_RENAMES = [
    ("Moonglade Marsh", "the Moonmarsh"), ("Moonglade", "Moonmarsh"), ("Torrhus Canyon", "Torrent Canyon"),
    ("Torrhus", "Torrent"), ("Galatea's", "the Moonmarsh's"), ("Galatea", "Moonmarsh"),
    ("Starlyn", "Starfall"), ("Mines of Divan", "Mines of Varnas"), ("Lotus Atoll", "Lotus Lagoon"),
    ("Hoppity's Hunt", "Hopsworth's Hunt"), ("David's Cloak", "Huntmaster's Cloak"), ("Grandma Wolf", "Granny Moss"),
    ("Kat's Favorite", "Sitter's Favorite"), ("Kat takes", "The Pet Sitter takes"), ("from Fann", "from the Pet Trainer"),
    ("Kuudra's Box", "Leviathan's Box"), ("Kuudra Teeth", "Leviathan Teeth"), ("Kuudra", "Leviathan"),
    ("Wiki Tiki", "Tiki Totem"), ("Yog Membrane", "Cinder Membrane"), ("Glacite", "Rimestone"),
    ("Abiphone", "Ringphone"), ("Crystal Hollows", "Crystal Caverns"),
]

# shard -> mob ids (overrides and additions to the automatic name match). "!" removes the auto match.
MOB_OVERRIDES = {
    "SHARD_TANK_ZOMBIE": ["dungeon_super_tank_zombie"],
    "SHARD_SPECIAL_ZEALOT": ["end_zealot_55"],
    "SHARD_ENDSTONE_PROTECTOR": ["end_stone_protector"],
    "SHARD_BONZO": ["dboss_grinko"],
    "SHARD_SCARF": ["dboss_veilwright"],
    "SHARD_POWER_DRAGON": ["dboss_dragon_power"],
    "SHARD_APEX_DRAGON": ["dboss_dragon_apex"],
    "SHARD_BARBARIAN_DUKE_X": ["crimson_barbarian_duke"],
    "SHARD_BURNINGSOUL": ["slayer_mini_burningsoul_demon", "slayer_blaze_3", "slayer_blaze_4"],
    "SHARD_REVENANT": ["slayer_zombie_3", "slayer_zombie_4", "slayer_zombie_5"],
    "SHARD_SYCOPHANT": ["slayer_mini_revenant_sycophant"],
    "SHARD_PRIMORDIAL": ["slayer_spider_5", "slayer_spider_5b"],
    "SHARD_SKELETOR": ["dungeon_skeleton_master"],
    "SHARD_BAL": ["ch_magmaw"],
    "SHARD_KING_MINOS": ["myth_inquisitor_5", "myth_inquisitor_6"],
    "SHARD_MINOS_HUNTER": ["myth_hunter_1", "myth_hunter_2", "myth_hunter_3"],
    "SHARD_DIVE_GHAST": ["crimson_ghast"],
    "SHARD_DRAGONFLY": ["garden_pest_dragonfly"],
    "SHARD_KEELED_SLUG": ["garden_pest_slug"],
    "SHARD_WITHER": ["dungeon_wither_guard"],
    "SHARD_DROWNED": ["marsh_hydrospear"],
    "SHARD_CHILL": ["marsh_chillblade", "marsh_chillshot"],
    "SHARD_SEA_ARCHER": ["sc_bogged", "sc_sea_archer"],
    "SHARD_STRONGARM": ["gt_glacite_mutt"],
    "SHARD_HARPY": [],
    "SHARD_LEVIATHAN": ["!"],          # our leviathan_* mobs are the Magma Leviathan boss, not this reptile
}

# shards whose source belongs to another module (audit 2026-10-06): shown in the shard book, never wired as a
# Charm Chance / Black Hole mob and never a critter.
#  C15 Paragon: the slayer module's own pool, 0.25% on every T4 and 0.5% on every T5 boss at slayer level 5
#      (Revenant/Tarantula T4-T5, Sven/Voidgloom/Inferno T4; Riftstalker is not a T4/T5 boss in this sense).
#  C16 Kraken: a guaranteed drop of every paid Kuudra chest (1/1/2/2/3 by tier, the kuudra module's K5), no longer
#      a netted critter.
OTHER_SOURCES = {
    "SHARD_PARAGON": {"type": "slayer", "mobs": ["slayer_zombie_4", "slayer_zombie_5", "slayer_spider_4", "slayer_spider_5",
                                                  "slayer_wolf_4", "slayer_enderman_4", "slayer_blaze_4"]},
    "SHARD_KRAKEN": {"type": "kuudra"},
}
# C14/C20: Shellwise (Epic) and Joydive are netted at the Murkwater Loch - our Dimwater Loch (`loch`, docs/names/
# foraging.md dimwater_loch), reached as /warp murk in the reference. Shellwise is a Reptile EPIC, which the fusion
# rule below would otherwise make fusion-only.
NETTED_AT = {"SHARD_SHELLWISE": ["loch"], "SHARD_JOYDIVE": ["loch"]}

KEEP_SHARD_NAME = {"SHARD_SPECIAL_ZEALOT", "SHARD_PARAGON", "SHARD_PRIMORDIAL", "SHARD_REVENANT", "SHARD_WITHER"}

# fusion-only shards (no critter, no mob): the upper Elemental chain, the reptile line above RARE, the echoes
def fusion_only(a):
    fam = a["family"]
    r = a["rarity"]
    if "Elemental" in fam and r != "COMMON":
        return True
    if "Reptile" in fam and r in ("EPIC", "LEGENDARY") and a["bazaarName"] not in ("SHARD_CHAMELEON", "SHARD_SHELLWISE"):
        return True
    # the Chameleon is a critter (a Forest reptile you rope); it can never come OUT of a fusion. Shellwise is netted.
    return False

# ---------------------------------------------------------------------------------------------
STAT_WORDS = {
    "Health": "HEALTH", "Intelligence": "INTELLIGENCE", "Strength": "STRENGTH", "Sweep": "SWEEP", "Defense": "DEFENSE",
    "Speed": "SPEED", "Fishing Speed": "FISHING_SPEED", "Sea Creature Chance": "SEA_CREATURE_CHANCE",
    "Double Hook Chance": "DOUBLE_HOOK_CHANCE", "Farming Fortune": "FARMING_FORTUNE", "Mining Fortune": "MINING_FORTUNE",
    "Foraging Fortune": "FORAGING_FORTUNE", "Mining Speed": "MINING_SPEED", "Magic Find": "MAGIC_FIND",
    "Trophy Chance": "TROPHY_FISH_CHANCE", "Treasure Chance": "TREASURE_CHANCE", "True Defense": "TRUE_DEFENSE",
    "Attack Speed": "ATTACK_SPEED", "Vitality": "VITALITY", "Health Regen": "HEALTH_REGEN",
    "Hunting Fortune": "HUNTING_FORTUNE", "Cold Resistance": "COLD_RESISTANCE", "Heat Resistance": "HEAT_RESISTANCE",
    "Ore Fortune": "ORE_FORTUNE", "Block Fortune": "BLOCK_FORTUNE", "Bonus Pest Chance": "PEST_CHANCE",
    "Mining Spread": "MINING_SPREAD", "Crit Damage": "CRIT_DAMAGE",
    "Foraging Wisdom": "FORAGING_WISDOM", "Fishing Wisdom": "FISHING_WISDOM", "Hunting Wisdom": "HUNTING_WISDOM",
    "Mining Wisdom": "MINING_WISDOM", "Farming Wisdom": "FARMING_WISDOM", "Social Wisdom": "SOCIAL_WISDOM",
    "Enchanting Wisdom": "ENCHANTING_WISDOM", "Taming Wisdom": "TAMING_WISDOM", "Combat Wisdom": "COMBAT_WISDOM",
}
# stats the reference has and our Stat enum does not: hunting keeps the first three itself, the rest are hooks
PSEUDO = {"Charm Chance": "charm_chance", "Tracking": "tracking", "Pull": "pull", "Respiration": "respiration",
          "Pressure Resistance": "pressure_resistance", "Overbloom": "overbloom", "Gemstone Spread": "gemstone_spread",
          "Fig Fortune": "fig_fortune", "Mangrove Fortune": "mangrove_fortune", "Helix Fortune": "helix_fortune",
          "Timber": "timber", "Crop Growth": "crop_growth"}

MOB_TYPES = {"Animal": "animal", "Aquatic": "aquatic", "Arthropod": "arthropod", "Undead": "undead", "Ender": "ender",
             "Infernal": "infernal", "Woodland": "woodland", "Mythological": "mythological", "Skeletal": "skeletal",
             "Frozen": "frozen", "Subterranean": "subterranean", "Arcane": "arcane", "Airborne": "airborne",
             "Humanoid": "humanoid", "Magmatic": "magmatic", "Construct": "construct", "Cubic": "cubic",
             "Elusive": "elusive", "Critter": "critter", "Pest": "pest"}

ISLAND_GROUPS = {"Foraging Islands": "foraging_islands", "Fishing Islands": "fishing_islands",
                 "Mining Islands": "mining_islands", "Combat Islands": "combat_islands"}


def clean(s):
    s = re.sub("§.", "", s)
    s = re.sub("[-]", "", s)          # private-use stat glyphs
    s = re.sub("[☯❣✿] ?", "", s)    # yin-yang / heart / flower stat symbols
    s = re.sub(" {2,}", " ", s)
    return s.strip()


def renamed(s):
    for a, b in TEXT_RENAMES:
        s = s.replace(a, b)
    return s


def groups_of(ability):
    g = []
    a = ability
    if a.endswith("Resistance") and a != "Cold Resistance":
        g.append("resistance")
    if a.endswith("Ruler"):
        g.append("ruler")
    if a.startswith("Atomized"):
        g.append("atomized")
    if a.startswith("Essence of"):
        g.append("essence")
    if a.startswith("Hunter's") or a == "Hunter's Karma":
        g.append("hunter")
    if a.endswith("Sharpening"):
        g.append("sharpening")
    if a.endswith("Box"):
        g.append("box")
    if a.endswith("Tracker"):
        g.append("tracker")
    if a.endswith("Elemental"):
        g.append("elemental")
    if a.endswith("Wisdom"):
        g.append("wisdom")
    if a.startswith("Visitor"):
        g.append("visitor")
    if a.startswith("Bucket"):
        g.append("bucket")
    if a.startswith("Unlimited"):
        g.append("unlimited")
    if a.startswith("Echo of") or "Echo" in a:
        g.append("echo")
    return g


ECHO_TARGETS = {"Echo of Resistance": "resistance", "Echo of Atomized": "atomized", "Echo of Essence": "essence",
                "Echo of Ruler": "ruler", "Echo of Hunter": "hunter", "Echo of Sharpening": "sharpening",
                "Echo of Boxes": "box", "Echo of Tracking": "tracker", "Echo of Elemental": "elemental",
                "Echo of Wisdom": "wisdom", "Queenly Echo": "visitor", "Echo of Buckets": "bucket",
                "Almighty Echo": "unlimited", "Echo of Echoes": "echo"}

NUM = r"(\d+(?:\.\d+)?)"


def parse_effect(ability, text):
    """Effect of ONE level, from the level-I lore. Returns (effect dict, template text)."""
    t = text
    eff = {"kind": "hook"}
    # template: every '+number' scales with level (constants such as 'every +29 Cold Resistance' do not)
    nums = []

    def repl(m):
        pre = t[max(0, m.start() - 6):m.start()]
        if "every" in pre or pre.endswith("of "):
            return m.group(0)
        nums.append(float(m.group(1)))
        return "+{v%d}" % (len(nums))
    tmpl = re.sub(r"\+" + NUM, repl, t)
    per = nums[0] if nums else 0.0

    m = re.match(r"^Grants \+" + NUM + r" (.+?)(?: and \+" + NUM + r" (.+?))?(?: (while on|while in|during|on|towards|against|while) (.+?))?\.$", t)
    if ability == "Ultimate DNA":
        eff = {"kind": "stat", "stat": "MINING_FORTUNE", "per": per, "stat2": "FARMING_FORTUNE", "per2": per,
               "stat3": "FORAGING_FORTUNE"}
    elif ability == "Mana Regeneration":
        eff = {"kind": "stat", "stat": "MANA_REGEN", "per": per}
    elif ability == "Help From Above":
        eff = {"kind": "stat", "stat": "HEALTH_REGEN", "per": per}
    elif ability == "Eelastic":
        eff = {"kind": "pseudo", "stat": "pull", "per": per}
    elif ability in ECHO_TARGETS:
        eff = {"kind": "echo", "group": ECHO_TARGETS[ability], "per": per}
    elif ability.startswith("Unlimited"):
        mm = re.search(r"Your (.+?) is increased by", t)
        st = STAT_WORDS.get(mm.group(1)) if mm else None
        eff = {"kind": "stat_pct", "stat": st, "per": per} if st else {"kind": "hook", "per": per}
    elif re.search(r"Hunting Fortune towards (\w+) Attributes", t):
        r = re.search(r"towards (\w+) Attributes", t).group(1)
        eff = {"kind": "hf_rarity", "rarity": r, "per": per}
    elif ability == "Charmed":
        eff = {"kind": "pseudo", "stat": "charm_chance", "per": per}
    elif ability == "Warrior":
        eff = {"kind": "melee", "per": per}
    elif ability == "Deadeye":
        eff = {"kind": "ranged", "per": per}
    elif ability == "Dominance":
        eff = {"kind": "full_hp", "per": per}
    elif ability == "Lifeline":
        eff = {"kind": "low_hp", "per": per}
    elif ability == "Payback":
        eff = {"kind": "payback", "per": per}
    elif ability == "Elite":
        eff = {"kind": "damage_vs", "target": "boss", "per": per}
    elif re.search(r"Defense against (.+?) mobs", t) and ability.endswith("Resistance"):
        tg = re.search(r"Defense against (.+?) mobs", t).group(1).strip()
        eff = {"kind": "defense_vs", "target": MOB_TYPES.get(tg, tg.lower()), "per": per}
    elif re.search(r"Damage against (.+?) mobs", t) or re.search(r"damage to (.+?) mobs", t):
        mm = re.search(r"Damage against (.+?) mobs", t) or re.search(r"damage to (.+?) mobs", t)
        tg = mm.group(1).strip()
        eff = {"kind": "damage_vs", "target": MOB_TYPES.get(tg, tg.lower()), "per": per}
    elif re.search(r"Magic Find (?:against|on) (.+?) mobs", t):
        tg = re.search(r"Magic Find (?:against|on) (.+?) mobs", t).group(1).strip()
        eff = {"kind": "mf_vs", "target": MOB_TYPES.get(tg, tg.lower()), "per": per}
    elif m:
        w1, n2, w2, cw, cond = m.group(2), m.group(3), m.group(4), m.group(5), m.group(6)
        w1 = w1.strip()
        st = STAT_WORDS.get(w1)
        ps = PSEUDO.get(w1)
        c = None
        if cw is not None:
            if cond in ISLAND_GROUPS:
                c = ISLAND_GROUPS[cond]
            elif cond.lower() in ("the night",):
                c = "night"
            elif cond.lower() in ("the day",):
                c = "day"
            elif "Garden" in cond:
                c = "island:garden"
            elif "Lotus" in cond or "Backwater Bayou" in cond:
                c = "island:backwater_bayou"
            elif "Torrhus" in cond:
                c = "island:galatea"
            elif "Divan" in cond:
                c = "island:crystal_hollows"
            elif "Glacite Mineshafts" in cond:
                c = "island:mineshaft"
            else:
                c = "hook:" + cond
        if c is not None and c.startswith("hook:"):
            eff = {"kind": "hook", "per": per}
        elif st:
            eff = {"kind": "stat", "stat": st, "per": per}
            if n2 and w2 and STAT_WORDS.get(w2.strip()):
                eff["stat2"] = STAT_WORDS[w2.strip()]
                eff["per2"] = float(n2)
            if c:
                eff["cond"] = c
        elif ps:
            eff = {"kind": "pseudo", "stat": ps, "per": per}
            if c:
                eff["cond"] = c
    elif ability == "Ultimate DNA":
        eff = {"kind": "stat", "stat": "MINING_FORTUNE", "per": per, "stat2": "FARMING_FORTUNE", "per2": per,
               "stat3": "FORAGING_FORTUNE"}
    elif ability == "Mana Regeneration":
        eff = {"kind": "stat", "stat": "MANA_REGEN", "per": per}
    elif ability == "Help From Above":
        eff = {"kind": "stat", "stat": "HEALTH_REGEN", "per": per}
    elif ability == "Eelastic":
        eff = {"kind": "pseudo", "stat": "pull", "per": per}
    elif ability == "Pest Cooldown":
        # "Reduces the spawn cooldown of Pests by 0.5s." has no '+', so per came out 0 and value:pest_cooldown was
        # always 0. It is 0.5 s a level (-5 s at X, garden_max.md); the garden subtracts value:pest_cooldown seconds.
        eff = {"kind": "hook", "per": 0.5}
        tmpl = t.replace("by 0.5s", "by {v1}s")
    if "per" not in eff:
        eff["per"] = per
    return eff, tmpl


def color_template(tmpl):
    """'&7' body, numbers green."""
    out = "&7" + re.sub(r"\+\{v(\d)\}", lambda m: "&a+{v" + m.group(1) + "}&7", tmpl)
    return out


# ---------------------------------------------------------------------------------------------
def load_mobs():
    mobs = {}
    for f in glob.glob(os.path.join(DATA, "*", "*.json")):
        if os.path.basename(os.path.dirname(f)) == "hunting":
            continue
        try:
            d = json.load(open(f, encoding="utf-8"))
        except Exception:
            continue
        if isinstance(d, list) and d and isinstance(d[0], dict) and "health" in d[0] and "entity" in d[0]:
            for m in d:
                if "id" in m:
                    mobs[m["id"]] = m
    return mobs


PRE = ['hub_', 'den_', 'end_', 'crimson_', 'dungeon_', 'dboss_', 'sc_', 'park_', 'marsh_', 'ch_', 'gt_', 'dwarven_',
       'deep_caverns_', 'mining_', 'garden_pest_', 'rift_', 'slayer_mini_', 'myth_', 'spooky_', 'kuudra_', 'gold_mine_',
       'barn_', 'desert_', 'siege_']


def core_name(i):
    for p in PRE:
        if i.startswith(p):
            i = i[len(p):]
            break
    i = re.sub(r"(_\d+)+$", "", i)
    return i


def strip_codes(s):
    return re.sub("&.", "", s or "")


# ---------------------------------------------------------------------------------------------
# critter bodies, by name first then by family
ENTITY_BY_NAME = {
    "wolverine": "WOLF", "badger": "FOX", "mountain_goat": "GOAT", "billygoat": "GOAT", "bunbun": "RABBIT",
    "chocobun": "RABBIT", "rabbit_mafioso": "RABBIT", "rabbit_godmother": "RABBIT", "rabbit_godfather": "RABBIT",
    "rabbit_neighbor": "RABBIT", "rabbit_cat": "RABBIT", "carrot_king": "RABBIT", "firefox": "FOX", "foxtrot": "FOX",
    "grizzly_bear": "POLAR_BEAR", "red_panda": "PANDA", "wild_hog": "PIG", "pangolin": "ARMADILLO",
    "anteater": "ARMADILLO", "groundhog": "RABBIT", "kiwi": "CHICKEN", "dodo": "CHICKEN", "toad": "FROG",
    "bullfrog": "FROG", "treefrog": "FROG", "rana": "FROG", "salmon": "SALMON", "cod": "COD", "piranha": "PUFFERFISH",
    "moray_eel": "COD", "eel": "COD", "fire_eel": "COD", "stingray": "TROPICAL_FISH", "ghost_crab": "ARMADILLO",
    "blue_crab": "ARMADILLO", "mantis_shrimp": "TROPICAL_FISH", "tortoise": "TURTLE", "leatherback": "TURTLE",
    "megalith": "TURTLE", "shellwise": "TURTLE", "tewtil": "TURTLE", "water_snake": "COD", "queen_snake": "SALMON",
    "giant_isopod": "SILVERFISH", "troodon": "SNIFFER", "polaris": "POLAR_BEAR", "wumpa": "POLAR_BEAR",
    "littlefoot": "GOAT", "strongarm": "ZOMBIE", "cretan_bull": "COW", "sphinx": "CAT", "harpy": "PHANTOM",
    "murderwing": "PHANTOM", "dreadwing": "PHANTOM", "murkbat": "BAT", "bloodbat": "BAT", "snoozle": "ARMADILLO",
    "hewver": "FOX", "fluffling": "RABBIT", "mochibear": "PANDA", "bambuleaf": "PANDA", "bambloom": "PANDA",
    "pandarai": "PANDA", "xyz": "SHULKER", "gemzie": "ALLAY", "starborn": "ALLAY", "tenebris": "VEX",
    "chameleon": "FROG", "iguana": "FROG", "gecko": "FROG", "komodo_dragon": "FROG", "chuckwalla": "FROG",
    "newt": "AXOLOTL", "salamander": "AXOLOTL", "lizard_king": "AXOLOTL", "leviathan": "AXOLOTL",
    "naga": "SALMON", "kraken": "GLOW_SQUID", "skeletor": "SKELETON", "lapis_skeleton": "SKELETON",
    "lapis_creeper": "CREEPER", "prince": "ZOMBIE", "drowned": "DROWNED", "zombuddy": "ZOMBIE",
    "honey_badger": "FOX", "honeyhog": "PIG", "chill": "STRAY", "thorn": "SKELETON", "gazer": "ENDERMITE",
    "special_zealot": "ENDERMAN", "wither": "WITHER_SKELETON", "daemon": "BLAZE", "hellwisp": "BLAZE",
    "doomspiral": "BLAZE", "ananke": "PHANTOM", "etherdrake": "PHANTOM", "molthorn": "MAGMA_CUBE",
    "jormung": "SILVERFISH", "tiamat": "FROG", "wyvern": "PHANTOM", "draconic": "PHANTOM", "apex_dragon": "PHANTOM",
    "power_dragon": "PHANTOM", "primordial": "SPIDER", "paragon": "ZOMBIE", "timestalk_clone": "ZOMBIE",
    "basilisk": "SILVERFISH", "python": "SILVERFISH", "king_cobra": "SILVERFISH", "viper": "SILVERFISH",
    "cuboa": "SLIME", "alligator": "FROG", "caiman": "FROG", "crocodile": "FROG",
}
ENTITY_BY_FAMILY = [
    ("Bird", "PARROT"), ("Bug", "BEE"), ("Frog", "FROG"), ("Amphibian", "FROG"), ("Axolotl", "AXOLOTL"),
    ("Squid", "SQUID"), ("Tropical Fish", "TROPICAL_FISH"), ("Treasure Fish", "TROPICAL_FISH"), ("Eel", "COD"),
    ("Turtle", "TURTLE"), ("Crustacean", "ARMADILLO"), ("Shulker", "SHULKER"), ("Panda", "PANDA"),
    ("Phantom", "PHANTOM"), ("Poltergeist", "VEX"), ("Elemental", "ALLAY"), ("Spider", "CAVE_SPIDER"),
    ("Demon", "BLAZE"), ("Arctic", "POLAR_BEAR"), ("Lapis", "ZOMBIE"), ("Drowned", "DROWNED"),
    ("Creation", "ZOMBIE_VILLAGER"), ("Cave Dweller", "SILVERFISH"), ("Trash Lover", "SILVERFISH"),
    ("Crime", "RABBIT"), ("Elusive", "FOX"), ("Reptile", "FROG"), ("Dragon", "PHANTOM"), ("Dinosaur", "SNIFFER"),
]
BUG_BODIES = {"ant": "SILVERFISH", "queen_ant": "SILVERFISH", "stag_beetle": "ENDERMITE", "cropeetle": "ENDERMITE",
              "dung_beetle": "ENDERMITE", "ladybug": "ENDERMITE", "beaconmite": "ENDERMITE", "rockmite": "ENDERMITE",
              "honeymite": "ENDERMITE", "woodlouse": "SILVERFISH", "maggot": "SILVERFISH", "litterbug": "SILVERFISH",
              "giant_water_bug": "SILVERFISH", "june_bug": "ENDERMITE", "wartybug": "ENDERMITE", "bitbug": "ENDERMITE",
              "invisibug": "ENDERMITE", "cocoaleech": "SILVERFISH", "rainbug": "ENDERMITE"}
WATER_ENTITIES = {"COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH", "SQUID", "GLOW_SQUID", "AXOLOTL", "TURTLE"}


def entity_for(key, a):
    if key in ENTITY_BY_NAME:
        return ENTITY_BY_NAME[key]
    if key in BUG_BODIES:
        return BUG_BODIES[key]
    for fam, ent in ENTITY_BY_FAMILY:
        if fam in a["family"]:
            return ent
    al = a["alignment"]
    return {"Forest": "FOX", "Water": "COD", "Combat": "ZOMBIE"}[al]


# ---------------------------------------------------------------------------------------------
# habitats: island + area from the built layouts; "kind" = land / water / combat
HABITATS = [
    {"id": "moonmarsh", "island": "galatea", "area": "forest.marsh", "kind": "land", "name": "Moonmarsh Reeds"},
    {"id": "canyon", "island": "torrhus_canyon", "area": "helix_grove", "kind": "land", "name": "Torrent Heights"},  # playtest wave 5: galatea built the canyon as its own island
    {"id": "loch", "island": "galatea", "area": "mobs.loch", "kind": "water", "name": "Dimwater Loch"},
    {"id": "park_birch", "island": "park", "area": "forest.birch_park", "kind": "land", "name": "Birch Park"},
    {"id": "park_jungle", "island": "park", "area": "forest.jungle_island", "kind": "land", "name": "Jungle Island"},
    {"id": "park_dark", "island": "park", "area": "forest.dark_thicket", "kind": "land", "name": "Dark Thicket"},
    {"id": "hub_forest", "island": "hub", "area": "forest", "kind": "land", "name": "Hub Forest"},
    {"id": "hub_pond", "island": "hub", "area": "pond", "kind": "water", "name": "Fishing Outpost"},
    {"id": "barn_fields", "island": "barn", "area": "fields", "kind": "land", "name": "Barn Fields"},
    {"id": "bayou", "island": "backwater_bayou", "area": "bayou_water", "kind": "water", "name": "Bayou Waters"},
    {"id": "lagoon", "island": "lotus_atoll", "area": "lagoon_water", "kind": "water", "name": "Lotus Lagoon"},  # playtest wave 5: the Lotus Atoll is its own island now
    {"id": "marsh_water", "island": "backwater_bayou", "area": "marsh_water", "kind": "water", "name": "Moonlit Marsh"},
    {"id": "rimestone_lake", "island": "glacite_tunnels", "area": "fishing.lake", "kind": "water", "name": "Rimestone Lake"},
    {"id": "frozen_tunnels", "island": "glacite_tunnels", "area": "mobs.tunnels.mid", "kind": "combat", "name": "Frozen Tunnels"},
    {"id": "hollows_grotto", "island": "crystal_hollows", "area": "mobs.grotto.1", "kind": "land", "name": "Fairy Grotto"},
    {"id": "hollows_jungle", "island": "crystal_hollows", "area": "mobs.jungle", "kind": "combat", "name": "Hollows Jungle"},
    {"id": "upper_mines", "island": "dwarven_mines", "area": "upper_mines", "kind": "combat", "name": "Upper Mines"},
    {"id": "wilderness", "island": "hub", "area": "wilderness", "kind": "combat", "name": "Wilderness"},
    {"id": "crypt", "island": "hub", "area": "crypt", "kind": "combat", "name": "Crypts"},
    {"id": "den_slime", "island": "spiders_den", "area": "slime_hill", "kind": "combat", "name": "Slime Hill"},
    {"id": "end_hollow", "island": "the_end", "area": "hollow", "kind": "combat", "name": "Bruiser's Hollow"},
    {"id": "crimson_fields", "island": "crimson_isle", "area": "mobs.crimson_fields", "kind": "combat", "name": "Crimson Fields"},
    {"id": "crimson_marsh", "island": "crimson_isle", "area": "mobs.mystic_marsh", "kind": "combat", "name": "Mystic Marsh"},
    # the Critter Safari's four biomes (our own island)
    {"id": "safari_cavern", "island": "critter_safari", "area": "biome.cavern", "kind": "land", "name": "Cavern Biome", "safari": True},
    {"id": "safari_forest", "island": "critter_safari", "area": "biome.forest", "kind": "land", "name": "Forest Biome", "safari": True},
    {"id": "safari_haunted", "island": "critter_safari", "area": "biome.haunted", "kind": "land", "name": "Haunted Biome", "safari": True},
    {"id": "safari_icy", "island": "critter_safari", "area": "biome.icy", "kind": "land", "name": "Icy Biome", "safari": True},
]


def habitats_for(key, a, text, ent):
    al = a["alignment"]
    fam = a["family"]
    t = text
    water = ent in WATER_ENTITIES
    hs = []
    if al == "Water" or water:
        if "Mining Islands" in t or "Cave Dweller" in fam:
            hs = ["rimestone_lake", "hub_pond"]
        elif "Foraging Islands" in t or "Torrhus" in t or "Moonglade" in t or "Tree Gift" in t:
            hs = ["loch"]
        elif "Lotus" in t or "Frog" in fam or "Axolotl" in fam:
            hs = ["lagoon", "marsh_water"]
        elif "Arctic" in fam or "Winter" in t or "Snow" in a["displayName"]:
            hs = ["rimestone_lake"]
        elif "Treasure Fish" in fam:
            hs = ["bayou", "lagoon"]
        else:
            hs = ["bayou", "marsh_water", "hub_pond"]
        return hs
    if al == "Forest":
        if "Cave Dweller" in fam:
            hs = ["hollows_grotto"]
        elif "Torrhus" in t or "Helix" in t or "Honey" in t or key in ("bunbun", "pangolin", "mountain_goat", "firefox", "anteater", "hawk", "woodpecker", "vulture"):
            hs = ["canyon"]
        elif "Garden" in t or "Pest" in t or "Farming" in t or "Crop" in t or "Composter" in t:
            hs = ["barn_fields"]
        elif "Bird" in fam:
            hs = ["park_birch", "moonmarsh"]
        elif "Panda" in fam:
            hs = ["park_jungle"]
        elif "Phantom" in fam or "Poltergeist" in fam:
            hs = ["park_dark", "moonmarsh"]
        elif "Crime" in fam:
            hs = ["hub_forest"]
        else:
            hs = ["moonmarsh", "hub_forest"]
        return hs
    # combat
    if "Arctic" in fam or "Frozen" in t or "Cold" in t or "Glacite" in t:
        return ["frozen_tunnels"]
    if "Cave Dweller" in fam or "Mining" in t or "Gemstone" in t or "Powder" in t or "Mithril" in t or "Pickaxe" in t:
        return ["upper_mines", "hollows_jungle"]
    if "Demon" in fam or "Infernal" in t or "Blazing" in t or "Magmatic" in t or "Kuudra" in t:
        return ["crimson_fields", "crimson_marsh"]
    if "Spider" in fam or "Arthropod" in t:
        return ["den_slime"]
    if "Ender" in t or "Dragon" in fam or "Zealot" in t or "Summoning Eyes" in t:
        return ["end_hollow"]
    if "Catacombs" in t or "Dungeon" in t or "Undead" in t or "Skeletal" in t or "Lapis" in fam:
        return ["crypt"]
    return ["wilderness"]


SAFARI = {
    "safari_cavern": ["SHARD_FLITTER", "SHARD_SHYWORM", "SHARD_FUNGLOOM", "SHARD_DRIFTLING", "SHARD_THYST",
                      "SHARD_ROCKMITE", "SHARD_HIDEONCAVE", "SHARD_GIANT_ISOPOD", "SHARD_TROGLOBYTE"],
    "safari_forest": ["SHARD_SPARROW", "SHARD_HERON", "SHARD_BLUE_JAY", "SHARD_HUMMINGBIRD", "SHARD_TOUCAN",
                      "SHARD_PARAKEET", "SHARD_FALCON", "SHARD_MACAW", "SHARD_DODO"],
    "safari_haunted": ["SHARD_PHANPYRE", "SHARD_PHANFLARE", "SHARD_DREADWING", "SHARD_MURDERWING", "SHARD_SOLSNATCHER",
                       "SHARD_HIDEYHO", "SHARD_PUCK", "SHARD_MURKBAT", "SHARD_BLOODBAT", "SHARD_TIMESTALK_CLONE"],
    "safari_icy": ["SHARD_STRONGARM", "SHARD_POLARIS", "SHARD_BILLYGOAT", "SHARD_WUMPA", "SHARD_LITTLEFOOT",
                   "SHARD_SNOWFIN", "SHARD_BOREAL_OWL", "SHARD_GRIZZLY_BEAR", "SHARD_WOLVERINE"],
}

# critter numbers by rarity // TUNE: the reference does not publish stamina, pull or critter health
CRITTER = {
    "COMMON":    {"stamina": 3.0, "reels": 1, "pull": 5,   "health": 50,    "level": 5},
    "UNCOMMON":  {"stamina": 4.0, "reels": 1, "pull": 20,  "health": 200,   "level": 15},
    "RARE":      {"stamina": 5.0, "reels": 2, "pull": 40,  "health": 1000,  "level": 30},
    "EPIC":      {"stamina": 6.0, "reels": 2, "pull": 70,  "health": 5000,  "level": 60},
    "LEGENDARY": {"stamina": 8.0, "reels": 3, "pull": 110, "health": 25000, "level": 100},
}
# combat shard creatures hit back
COMBAT_DAMAGE = {"COMMON": 40, "UNCOMMON": 120, "RARE": 300, "EPIC": 800, "LEGENDARY": 2000}


def skull_of(item):
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', item.get("nbttag", ""))
    return m.group(1) if m else None


# ---------------------------------------------------------------------------------------------
def main():
    os.makedirs(OUT, exist_ok=True)
    consts = json.load(open(os.path.join(NEU, "constants", "attribute_shards.json"), encoding="utf-8"))
    mobs = load_mobs()
    idx = {}
    for i in mobs:
        idx.setdefault(core_name(i), []).append(i)

    shards, items, crit_mobs = [], [], []
    hab_members = {h["id"]: [] for h in HABITATS}
    for a in consts["attributes"]:
        sid = a["bazaarName"] or "SHARD_RAINBUG"
        key = sid[6:].lower()
        code = a["shardId"]
        rarity = a["rarity"]
        item = json.load(open(os.path.join(NEU, "items", a["internalName"] + ".json"), encoding="utf-8"))
        lore = [clean(l) for l in item["lore"]]
        eff_lines = []
        for l in lore[1:]:
            if l == "":
                break
            eff_lines.append(l)
        text = " ".join(eff_lines)
        ability = a["abilityName"]
        attr = a["internalName"].replace("ATTRIBUTE_SHARD_", "").split(";")[0].lower()
        eff, tmpl = parse_effect(ability, text)

        # sources
        auto = idx.get(key) or idx.get(re.sub("[^a-z0-9]+", "_", a["displayName"].lower()).strip("_")) or []
        ov = MOB_OVERRIDES.get(sid)
        if ov is not None:
            mob_ids = [] if ov == ["!"] else ov
        else:
            mob_ids = list(auto)
        mob_ids = [m for m in mob_ids if m in mobs]
        name = a["displayName"]
        if name in NAME_RENAMES:
            name = NAME_RENAMES[name]
        elif mob_ids and sid not in KEEP_SHARD_NAME:
            # the mob's module may already have renamed it: a shard reads like its creature
            mobname = re.sub(r"^[^A-Za-z]+", "", strip_codes(mobs[mob_ids[0]].get("name", ""))).strip()
            mobname = re.sub(r"\s+\d+$", "", mobname)
            if mobname and len(mobname) < 30:
                name = mobname
        name = re.sub(r"\s+\d+$", "", name)
        name = re.sub(r"^[^A-Za-z]+", "", name).strip()
        ab_name = renamed(ability)
        sources = []
        if mob_ids:
            sources.append({"type": "mob", "mobs": mob_ids})
        crit = None
        habitats = []
        if sid in OTHER_SOURCES:
            sources.append(dict(OTHER_SOURCES[sid]))
        if not fusion_only(a) and sid not in OTHER_SOURCES:
            if not mob_ids or a["alignment"] != "Combat":
                ent = entity_for(key, a)
                hs = NETTED_AT.get(sid) or habitats_for(key, a, text, ent)
                for hid, members in SAFARI.items():
                    if sid in members:
                        hs = hs + [hid]
                method = "net" if (a["alignment"] == "Water" or ent in WATER_ENTITIES) else ("blackhole" if a["alignment"] == "Combat" else "lasso")
                if method == "net" and ent not in WATER_ENTITIES:
                    ent = "COD"
                if not mob_ids or method != "blackhole":
                    cid = "critter_" + key
                    c = CRITTER[rarity]
                    mob = {"id": cid, "name": name, "level": c["level"], "entity": ent,
                           "health": c["health"] if method != "blackhole" else c["health"] * 4,
                           "damage": 0 if method != "blackhole" else COMBAT_DAMAGE[rarity],
                           "combatXp": 0 if method != "blackhole" else round(c["level"] * 1.5),
                           "coins": 0, "drops": [], "family": "critter_" + key,
                           "flags": ["passive"] if method != "blackhole" else ["aggressive"],
                           "speed": 1.0 if method != "blackhole" else 1.1,
                           "extra": {"critter": True, "shard": sid, "method": method,
                                     "stamina": c["stamina"], "reels": c["reels"], "pull": c["pull"],
                                     "types": ["critter"] + (["aquatic"] if method == "net" else []) +
                                              (["animal"] if ent in ("FOX", "RABBIT", "PIG", "COW", "GOAT", "WOLF", "PANDA", "POLAR_BEAR", "CHICKEN", "PARROT", "ARMADILLO", "CAT") else []),
                                     "tune": "critter numbers by rarity (gen_hunting.py CRITTER)"}}
                    if ent in ("PARROT", "BEE", "BAT", "PHANTOM", "VEX", "ALLAY"):
                        mob["flags"].append("flying")
                    if ent in ("BLAZE", "MAGMA_CUBE", "STRIDER"):
                        mob["flags"].append("fire_immune")
                    if ent in ("ZOMBIE", "SKELETON", "STRAY", "DROWNED", "ZOMBIE_VILLAGER", "WITHER_SKELETON", "PHANTOM"):
                        mob["flags"].append("no_burn")
                    if ent == "MAGMA_CUBE" or ent == "SLIME":
                        mob["extra"]["size"] = 2
                    crit_mobs.append(mob)
                    crit = {"mob": cid, "method": method}
                    habitats = hs
                    for h in hs:
                        hab_members[h].append(cid)
                    sources.append({"type": "critter", "mob": cid, "method": method, "habitats": hs})
        if not sources:
            sources.append({"type": "fusion"})
        lvl_tmpl = color_template(renamed(tmpl))
        row = {
            "id": sid, "name": name, "rarity": rarity, "alignment": a["alignment"], "families": a["family"],
            "code": code, "num": int(code[1:]), "attr": attr, "attrName": ab_name,
            "text": lvl_tmpl, "effect": eff, "groups": groups_of(ability),
            "sources": sources, "fusionOnly": fusion_only(a),
            "unconsumable": sid in consts.get("unconsumable_attributes", []),
        }
        shards.append(row)

        sk = skull_of(item)
        rcol = {"COMMON": "f", "UNCOMMON": "a", "RARE": "9", "EPIC": "5", "LEGENDARY": "6"}[rarity]
        idef = {"id": sid, "name": name + " Shard", "material": "PLAYER_HEAD", "rarity": rarity, "category": "MATERIAL",
                "lore": ["&" + rcol + ab_name + " I", lvl_tmpl.replace("{v1}", fmt(eff.get("per", 0))).replace("{v2}", fmt(eff.get("per2", 0)))
                         .replace("{v3}", "0"),
                         "", "&7You can Syphon this shard from", "&7your &aHunting Box&7.", "",
                         "&8" + a["alignment"].upper() + " SHARD (ID " + code + ")",
                         "&eRight-click to send to Hunting Box!", "&eShift Right-click to move all!"],
                "tags": ["bazaar", "attribute_shard"], "extra": {"shard": True, "alignment": a["alignment"], "code": code, "attr": attr}}
        if sk:
            idef["skull"] = sk
        items.append(idef)

    # habitats file
    habs = []
    for h in HABITATS:
        hh = dict(h)
        hh["critters"] = sorted(set(hab_members[h["id"]]))
        habs.append(hh)

    # ---------------- tools ----------------
    tools, recipes = tool_items()
    items.extend(tools)

    os.makedirs(OUT, exist_ok=True)
    dump("shards.json", shards)
    dump("items.json", items)
    dump("mobs.json", crit_mobs)
    dump("habitats.json", habs)
    dump("recipes.json", recipes)
    dump("levelling.json", consts["attribute_levelling"])
    kinds = {}
    for s in shards:
        kinds[s["effect"]["kind"]] = kinds.get(s["effect"]["kind"], 0) + 1
    print("shards", len(shards), "critters", len(crit_mobs), "items", len(items), "recipes", len(recipes))
    print("effect kinds", kinds)
    print("sources", {t: sum(1 for s in shards if any(x["type"] == t for x in s["sources"])) for t in ("mob", "critter", "fusion")})
    empty = [h["id"] for h in habs if not h["critters"]]
    print("empty habitats", empty)


def fmt(v):
    if v == int(v):
        return str(int(v))
    return ("%.3f" % v).rstrip("0").rstrip(".")


def dump(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")


# ---------------------------------------------------------------------------------------------
def neu_recipe(iid):
    p = os.path.join(NEU, "items", iid + ".json")
    if not os.path.exists(p):
        return None
    d = json.load(open(p, encoding="utf-8"))
    r = d.get("recipe")
    if r is None and d.get("recipes"):
        r = next((x for x in d["recipes"] if x.get("type") in (None, "crafting")), None)
    if not r:
        return None
    grid = []
    for k in ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"):
        v = r.get(k) or ""
        grid.append(v)
    return {"id": "hunting_" + iid.lower(), "type": "CRAFTING", "result": iid, "amount": int(r.get("count") or 1), "grid": grid}


def ab(aid, name, lore, trigger="RIGHT_CLICK", cooldown=0.0, mana=0):
    return {"id": aid, "name": name, "trigger": trigger, "cooldown": cooldown, "mana": mana, "lore": lore}


def tool_items():
    T = []
    R = []

    def add(i, recipe=True):
        T.append(i)
        if recipe:
            r = neu_recipe(i["id"])
            if r:
                R.append(r)

    lassos = [("ABYSMAL_LASSO", "Frayed Lasso", "COMMON", 0, 0, "", 10),
              ("VINERIP_LASSO", "Vinerip Lasso", "UNCOMMON", 20, 0, "", 12),
              ("ENTANGLER_LASSO", "Entangler Lasso", "RARE", 40, 2.5, "Tug", 14),
              ("EVERSTRETCH_LASSO", "Everstretch Lasso", "EPIC", 60, 5, "Wrangle", 16)]
    for iid, nm, rar, faster, hf, abn, rng in lassos:
        lore = ["&8Hunting Tool", ""]
        if faster:
            lore += ["&7Critters lose stamina &a" + str(faster) + "% &7faster."]
        if hf:
            lore += ["&6Ability: " + abn, "&7Grants &d+" + fmt(hf) + " Hunting Fortune &7on", "&7Forest Shards caught with this Lasso."]
        add({"id": iid, "name": nm, "material": "LEAD", "rarity": rar, "category": "NONE", "maxStack": 1,
             "lore": lore, "tags": ["hunting_tool", "soulbound"] if iid == "ABYSMAL_LASSO" else ["hunting_tool"],
             "abilities": [ab("hunting_lasso", "Throw", ["&7Throw the Lasso at &2Forest &7critters", "&7and hold them still until they", "&7lose all their stamina."]),
                           ab("hunting_lasso_reel", "Reel In", ["&7Reel in a lassoed critter each", "&7time its stamina runs out."], "ON_HIT")],
             "extra": {"tool": "lasso", "faster": faster, "hf": hf, "range": rng}})
    nets = [("BASIC_FISHING_NET", "Basic Fishing Net", "UNCOMMON", 5, 0, 0, ""),
            ("MEDIUM_FISHING_NET", "Medium Fishing Net", "RARE", 25, 50, 2.5, "Grab"),
            ("TURBO_FISHING_NET", "Turbo Fishing Net", "EPIC", 50, 100, 5, "Haul"),
            ("GIGANTIC_FISHING_NET", "Gigantic Fishing Net", "LEGENDARY", 100, 150, 10, "Overhaul")]
    for iid, nm, rar, pull, dist, hf, abn in nets:
        lore = ["&8Hunting Tool", "&7Pull: &a+" + str(pull), ""]
        if dist:
            lore += ["&7Throw Distance: &a+" + str(dist) + "%", ""]
        if hf:
            lore += ["&6Ability: " + abn, "&7Grants &d+" + fmt(hf) + " Hunting Fortune &7on", "&7Water Shards caught with this net.", ""]
        add({"id": iid, "name": nm, "material": "COBWEB", "rarity": rar, "category": "NONE", "maxStack": 1, "lore": lore,
             "tags": ["hunting_tool", "reforgeable"],
             "abilities": [ab("hunting_net", "Yoink", ["&7Throw your net at swimming", "&7critters to catch them!"], "RIGHT_CLICK", 0.5)],
             "extra": {"tool": "net", "pull": pull, "distance": dist, "hf": hf}})
    holes = [("SMALL_POCKET_BLACK_HOLE", "Small Pocket Black Hole", "UNCOMMON", 0, "", 6),
             ("MEDIUM_POCKET_BLACK_HOLE", "Medium Pocket Black Hole", "RARE", 2.5, "Blessing from the Dark", 8),
             ("LARGE_POCKET_BLACK_HOLE", "Large Pocket Black Hole", "EPIC", 5, "Blessing from the Void", 10)]
    for iid, nm, rar, hf, abn, rad in holes:
        lore = ["&8Hunting Tool", "", "&7Harness the power of Gravity!", "", "&7Monsters below &c10% &7HP can even", "&7get their Shard captured!"]
        if hf:
            lore += ["", "&6Ability: " + abn, "&7Grants &d" + fmt(hf) + " Hunting Fortune &7on Combat", "&7Shards caught with this Black Hole!"]
        add({"id": iid, "name": nm, "material": "PLAYER_HEAD", "rarity": rar, "category": "NONE", "maxStack": 1, "lore": lore,
             "skull": "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjc2ZDJlYjcyMjE2YjU2ZjdmNmUyNGIyNjI2NGRlMTE3ZGVhZjI0YjkyYzk3Y2RmMWEwZTIzODc2ZWRjMmUxNSJ9fX0=",
             "tags": ["hunting_tool"],
             "abilities": [ab("hunting_black_hole", "Black Hole", ["&7Place the Black Hole for &a180s&7, and", "&7it will start pulling low HP", "&7monsters towards it."], "RIGHT_CLICK", 2.0)],
             "extra": {"tool": "blackhole", "hf": hf, "radius": rad, "seconds": 180}})
    axes = [("VENATOR_GENESIS", "Worn Huntaxe - Origo", "COMMON", 5, 5, 15),
            ("SILVA_DOMINUS", "Sharpened Huntaxe - Silvanus", "UNCOMMON", 10, 10, 20),
            ("CURSUS_FERAE", "Reinforced Huntaxe - Venatrix", "RARE", 15, 15, 25),
            ("APEX_PRAEDATOR", "Savage Huntaxe - Ferox", "EPIC", 20, 20, 33),
            ("NEX_TITANUM", "Prime Huntaxe - Rex Ferarum", "LEGENDARY", 25, 25, 50)]
    for iid, nm, rar, dmg, absorb, cap in axes:
        add({"id": iid, "name": nm, "material": "GOLDEN_AXE", "rarity": rar, "category": "AXE", "damage": dmg,
             "lore": ["&8Hunting Tool"], "tags": ["hunting_tool", "reforgeable", "huntaxe"],
             "abilities": [ab("hunting_huntaxe_absorb", "Borrowed Edge", ["&7Absorb &a" + str(absorb) + "% &7of the basic combat", "&7stats of any weapon you carry."]),
                           ab("hunting_huntaxe_cap", "Tempered Blow", ["&7Each strike is capped at &c" + str(cap) + "% &7of", "&7the enemy's max Health. This weapon", "&7cannot cause a fatal blow."], "PASSIVE")],
             "extra": {"tool": "huntaxe", "absorb": absorb, "cap": cap}})
    traps = [("RETIA_BASICA", "Small Huntrap", "COMMON", 0, None),
             ("RETIA_MELIORA", "Medium Huntrap", "UNCOMMON", 10, "skill:hunting:5"),
             ("RETIA_ROBUSTA", "Large Huntrap", "RARE", 20, "skill:hunting:10"),
             ("RETIA_FORTA", "Greater Huntrap", "EPIC", 35, "skill:hunting:15"),
             ("RETIA_SUPREMA", "Astral Huntrap", "LEGENDARY", 50, "skill:hunting:20")]
    for iid, nm, rar, red, req in traps:
        lore = ["&8Hunting Tool", ""]
        if red:
            lore += ["&7Reduces the time to capture by &a" + str(red) + "%&7."]
        add({"id": iid, "name": nm, "material": "TRIPWIRE_HOOK", "rarity": rar, "category": "NONE", "maxStack": 1,
             "lore": lore, "tags": ["hunting_tool"], "requirements": [req] if req else [],
             "abilities": [ab("hunting_trap", "Set the Snare", ["&7Set the trap where Shard creatures", "&7live; given time it will catch one."])],
             "extra": {"tool": "trap", "reduction": red}})
    salts = [("LUSHLILAC", None, "UNCOMMON", 0.5, 8, None, False),
             ("LUSHLILAC_BONBON", "Lushlilac Bonbon", "UNCOMMON", 0.62, 12, None, True),
             ("PRIME_LUSHLILAC_BONBON", "Prime Lushlilac Bonbon", "UNCOMMON", 0.75, 18, None, True),
             ("EXALTED_LUSHLILAC_BONBON", "Exalted Lushlilac Bonbon", "UNCOMMON", 1.0, 24, None, True),
             ("OCEANDY", "Oceandy", "RARE", 1.25, 12, "sea_creature", True),
             ("CANDYCOMB", "Candycomb", "RARE", 1.25, 12, "island:catacombs", True),
             ("BRINE_TONIC", "Brine Tonic", "RARE", 2.75, 24, "island:galatea", True)]
    SALT = []
    for iid, nm, rar, charm, hours, target, own in salts:
        SALT.append({"id": iid, "charm": charm, "hours": hours, "target": target})
        if not own:
            continue           # LUSHLILAC is a collection item another module registers
        tgt = {"sea_creature": " &7towards &3Sea Creatures", "island:catacombs": " &7towards mobs in &cThe Catacombs",
               "island:galatea": " &7on &3Moonmarsh sea creatures"}.get(target, "")
        add({"id": iid, "name": nm, "material": "SUGAR" if "BONBON" in iid else ("HONEYCOMB" if iid == "CANDYCOMB" else "PRISMARINE_CRYSTALS"),
             "rarity": rar, "category": "CONSUMABLE",
             "lore": ["&8Hunting Salt", "", "&7Grants &d" + fmt(charm) + "❣ Charm Chance" + tgt + "&7.", "&7Duration: &a" + str(hours) + "h", "", "&eLeft-click to consume!"],
             "tags": ["hunting_salt"], "extra": {"tool": "salt", "charm": charm, "hours": hours, "target": target}})
    # safari
    add({"id": "CRITTER_CAPSULE", "name": "Critter Capsule", "material": "SNOWBALL", "rarity": "SPECIAL" if False else "UNCOMMON",
         "category": "NONE", "lore": ["&8Hunting Tool", "", "&7Throw it at a &aCritter &7to try to catch", "&7it. Critters holding a rarer Shard", "&7are harder to catch.", "",
                                      "&cVanishes when you leave the Critter Safari!"],
         "tags": ["hunting_tool", "no_trade", "no_ah"], "maxStack": 64,
         "abilities": [ab("hunting_capsule", "Capture", ["&7Throw the capsule ahead of you."])], "extra": {"tool": "capsule", "certain": False}}, False)
    add({"id": "MASTERFUL_CRITTER_CAPSULE", "name": "Masterful Critter Capsule", "material": "SNOWBALL", "rarity": "LEGENDARY",
         "category": "NONE", "glow": True, "lore": ["&8Hunting Tool", "", "&7Throw it at a &aCritter &7and it", "&7WILL catch it.", "", "&cVanishes when you leave the Critter Safari!"],
         "tags": ["hunting_tool", "no_trade", "no_ah"], "maxStack": 64,
         "abilities": [ab("hunting_capsule", "Certain Capture", ["&7Throw the capsule ahead of you."])], "extra": {"tool": "capsule", "certain": True}}, False)
    tickets = [("SAFARI_TICKET_BASIC", "Basic Safari Ticket", "UNCOMMON", 32, 0, 0),
               ("SAFARI_TICKET_ECONOMY", "Economy Safari Ticket", "RARE", 40, 10, 5),
               ("SAFARI_TICKET_PREMIUM", "Premium Safari Ticket", "EPIC", 48, 25, 15),
               ("SAFARI_TICKET_FIRST_CLASS", "First-Class Safari Ticket", "LEGENDARY", 56, 50, 25)]
    for iid, nm, rar, caps, ess, wis in tickets:
        lore = ["&7Admits one hunter to the", "&aCritter Safari&7.", "", "&7Capsules: &a" + str(caps)]
        if ess:
            lore += ["&7Safari Essence: &a+" + str(ess) + "%", "&7Hunting Wisdom: &3+" + str(wis)]
        lore += ["", "&eRight-click inside the Safari to start a hunt!"]
        add({"id": iid, "name": nm, "material": "PAPER", "rarity": rar, "category": "NONE", "lore": lore,
             "tags": ["safari_ticket"], "extra": {"capsules": caps, "essence": ess, "wisdom": wis}}, False)
    add({"id": "ESSENCE_SAFARI", "name": "Safari Essence", "material": "PLAYER_HEAD", "rarity": "RARE", "category": "MATERIAL",
         "skull": "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjQ1ZjJjMGJiZmUzYjhiMTliNzQ1MjA3MmRiNjlhNWY1OWRhMzhmZjYxNDE1NTQ1MTU2ZTU3MDFlMWJlNzU2ZCJ9fX0=",
         "lore": ["&7The sweet smell of the safari.", "", "&7Spend it at the &aSafari Perk Keeper&7."],
         "tags": ["bazaar"], "extra": {"essence": "SAFARI"}}, False)
    add({"id": "RAINBOW_FEATHER", "name": "Rainbow Feather", "material": "FEATHER", "rarity": "LEGENDARY", "category": "MATERIAL", "glow": True,
         "lore": ["&7Shed by a &dSparkling &7critter in", "&7the Critter Safari."], "tags": ["bazaar"]}, False)
    # store the salts table for the runtime
    with open(os.path.join(OUT, "salts.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(SALT, f, ensure_ascii=False, indent=1)
        f.write("\n")
    return T, R


if __name__ == "__main__":
    sys.exit(main())
