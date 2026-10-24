#!/usr/bin/env python3
"""
Generate the fishing module's data from the research dumps.

    python tools/gen/fishing/gen_fishing.py

Reads
    research/data/items.json                    rods, baits, rod parts, drops (id, material, rarity, stats, reqs)
    research/data/neu-repo/items/*_SC.json      sea creature HP / level / coins / combat XP / drop tables
    research/data/neu-repo/items/<ITEM>.json    recipes, skull textures, trophy fish items
    research/data/skyhanni-repo/constants/SeaCreatures.json   rarity + Fishing XP per creature
    research/data/skyhanni-repo/constants/TrophyFish.json     trophy base rates + fillet values
    research/data/collections.json              which collection tier unlocks a recipe
    plugin/src/main/resources/data/*/           which ids other modules already register

Writes
    plugin/src/main/resources/data/fishing/items.json     rods, rod parts, baits, trophy fish, fishing-owned drops
    plugin/src/main/resources/data/fishing/mobs.json      every sea creature as a MobDef
    plugin/src/main/resources/data/fishing/recipes.json   recipes for items this module owns
    plugin/src/main/resources/data/fishing/fishing.json   pools, baits, parts, trophy fish, treasure tables
    docs/names/fishing.md                                 every rename

Numbers come from the research; anything invented is listed in the TUNE section of the hand-off.
"""
import glob
import json
import math
import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
NEU = os.path.join(DATA, "neu-repo", "items")
RES = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")
OUT = os.path.join(RES, "fishing")
NAMES = os.path.join(ROOT, "docs", "names", "fishing.md")


def L(path):
    return json.load(open(path, encoding="utf-8"))


def strip(s):
    return re.sub(r"§.", "", s or "")


def neu(iid):
    p = os.path.join(NEU, iid + ".json")
    return L(p) if os.path.exists(p) else None


def skull_of(nd):
    """Base64 texture 'Value' out of a NEU nbttag."""
    if not nd:
        return None
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', nd.get("nbttag", ""))
    return m.group(1) if m else None


# ---------------------------------------------------------------------------------------------
# Ownership: ids other modules already register (so drops can point at them, and so we do not
# register them twice). Materials registers "only if absent" in postEnable, so fishing-owned ids
# that materials also lists are copied (same name) into our file: we are the owner.
# ---------------------------------------------------------------------------------------------
OTHER = {}
MATERIALS = {}
FALLBACK = set()   # ids another module registers only "if absent" (pets/weapons/slayer fallback files): we may own them
for f in glob.glob(os.path.join(RES, "*", "*.json")):
    mod = os.path.basename(os.path.dirname(f))
    if mod == "fishing":
        continue
    try:
        d = L(f)
    except Exception:
        continue
    if not isinstance(d, list):
        continue
    for x in d:
        if isinstance(x, dict) and "id" in x and ("material" in x or "rarity" in x):
            OTHER.setdefault(x["id"], mod)
            if "fallback" in os.path.basename(f):
                FALLBACK.add(x["id"])
            if mod == "materials":
                MATERIALS.setdefault(x["id"], x)

ITEMS = {i["id"]: i for i in L(os.path.join(DATA, "items.json"))["items"]}
for i in ITEMS.values():
    i["stats"] = {k.upper(): v for k, v in (i.get("stats") or {}).items()}

# recipe display name -> collection unlock key ("Rod of Champions" -> collection:WATER_LILY:7)
UNLOCK_BY_NAME = {}
for cat in L(os.path.join(DATA, "collections.json"))["collections"].values():
    for cid, col in cat["items"].items():
        for t in col["tiers"]:
            for u in t["unlocks"]:
                m = re.match(r"(.+?) Recipes?$", u.strip())
                if m:
                    UNLOCK_BY_NAME.setdefault(m.group(1).strip(), "collection:%s:%d" % (cid, t["tier"]))

RENAMES = []   # (kind, id, reference, ours)


def rename(kind, iid, ref, ours):
    if ref != ours:
        RENAMES.append((kind, iid, ref, ours))
    return ours


# ---------------------------------------------------------------------------------------------
# Sea creatures. One row each:
#   mob id, NEU page, our name, reference name, entity, level req, flags, behaviours, catch line
# The catch line is our own wording (the reference's chat lines are not copied).
# HP / level / coins / combat XP / drops come from the NEU page; overrides are explicit below.
# ---------------------------------------------------------------------------------------------
C = OrderedDict()


def creature(mid, page, name, ref, entity, lvl, msg, flags=(), beh=(), hp=None, fxp=None, rarity=None,
             speed=1.0, damage_mult=1.0, drops=None, extra=None, level=None, skull=True):
    C[mid] = dict(mid=mid, page=page, name=name, ref=ref, entity=entity, lvl=lvl, msg=msg, flags=list(flags),
                  beh=list(beh), hp=hp, fxp=fxp, rarity=rarity, speed=speed, dmult=damage_mult,
                  drops=drops, extra=extra or {}, level=level, skull=skull)


# Bestiary skull textures by reference name (creatures with no NEU *_SC page, e.g. the Torrhus pool).
BEST_TEX = {}


def _best_walk(o):
    if isinstance(o, dict):
        if "name" in o and "texture" in o and isinstance(o.get("name"), str):
            BEST_TEX.setdefault(strip(o["name"]), o["texture"])
        for v in o.values():
            _best_walk(v)
    elif isinstance(o, list):
        for v in o:
            _best_walk(v)


try:
    _best_walk(L(os.path.join(DATA, "neu-repo", "constants", "bestiary.json")))
except Exception:
    pass


# --- regular water ---
creature("sc_squid", "SQUID_SC", "Squid", "Squid", "SQUID", 1, "A squid wriggles up with your line.", hp=120,
         drops=[("INK_SACK", 1, 3, 1.0), ("WATER_LILY", 1, 1, 0.5)], level=2)
creature("sc_sea_walker", "SEA_WALKER_SC", "Sea Walker", "Sea Walker", "DROWNED", 1,
         "Something soggy climbs out of the water: a Sea Walker.", skull=False)
creature("sc_night_squid", "NIGHT_SQUID_SC", "Night Squid", "Night Squid", "GLOW_SQUID", 3,
         "The dark water stirs - a Night Squid rises.")
creature("sc_sea_guardian", "SEA_GUARDIAN_SC", "Sea Guardian", "Sea Guardian", "GUARDIAN", 5,
         "A Sea Guardian blinks at you from the deep.")
creature("sc_sea_witch", "SEA_WITCH_SC", "Sea Witch", "Sea Witch", "WITCH", 7,
         "You spilled a Sea Witch's cauldron. She is not pleased.")
creature("sc_sea_archer", "SEA_ARCHER_SC", "Sea Archer", "Sea Archer", "SKELETON", 9,   # NEU lore + wiki: Fishing IX
         "A Sea Archer surfaces with its bow already drawn.", skull=False)
creature("sc_rider_of_the_deep", "RIDER_OF_THE_DEEP_SC", "Deep Rider", "Rider of the Deep", "ZOMBIE", 11,
         "A Deep Rider gallops out of the waves.")
creature("sc_catfish", "CATFISH_SC", "Catfish", "Catfish", "OCELOT", 13, "Is that a cat? A Catfish!")
creature("sc_sea_leech", "SEA_LEECH_SC", "Sea Leech", "Sea Leech", "SILVERFISH", 16,
         "A slimy Sea Leech latches onto your hook.")
creature("sc_guardian_defender", "GUARDIAN_DEFENDER_SC", "Guardian Defender", "Guardian Defender", "GUARDIAN", 17,
         "A Guardian Defender rises to protect its waters.")
creature("sc_deep_sea_protector", "DEEP_SEA_PROTECTOR_SC", "Deep Sea Protector", "Deep Sea Protector", "IRON_GOLEM", 18,
         "The Deep Sea Protector wakes from its long sleep!", beh=["fishing_slam"],
         extra={"slamEvery": 8, "slamRadius": 4, "slamMult": 1.5})
creature("sc_water_hydra", "WATER_HYDRA_SC", "Water Hydra", "Water Hydra", "DROWNED", 19,
         "Three heads break the surface - the Water Hydra wants a fight.", beh=["fishing_split"], fxp=2025,
         extra={"splitAt": 0.5})
creature("sc_sea_emperor", "SEA_EMPEROR_SC", "Tidelord", "Sea Emperor", "ELDER_GUARDIAN", 24,
         "The sea itself bows as the Tidelord rises.", beh=["fishing_slam"], rarity="LEGENDARY", fxp=3000,
         extra={"slamEvery": 7, "slamRadius": 5, "slamMult": 1.6},
         drops=[("SEA_LUMIES", 16, 32, 1.0), ("ENCHANTED_SEA_LUMIES", 1, 1, 0.2), ("DIVER_FRAGMENT", 1, 1, 0.2),
                ("FLYING_FISH;2", 1, 1, 0.04), ("FLYING_FISH;3", 1, 1, 0.02), ("FLYING_FISH;4", 1, 1, 0.005),
                ("DYE_AQUAMARINE", 1, 1, 0.00002)])
creature("sc_carrot_king", "CARROT_KING_SC", "Carrot Monarch", "Carrot King", "RABBIT", 15,
         "A crowned rabbit hops out of the water. The Carrot Monarch has arrived!")
creature("sc_agarimoo", "AGARIMOO_SC", "Mooshroom Diver", "Agarimoo", "MOOSHROOM", 15,
         "Your chumcap bucket rattles - a Mooshroom Diver surfaces.")
# --- mushroom desert oasis ---
creature("sc_oasis_rabbit", "OASIS_RABBIT_SC", "Oasis Rabbit", "Oasis Rabbit", "RABBIT", 10,
         "A drenched Oasis Rabbit paddles to shore.", fxp=180, extra={"coinsDefault": 5})
creature("sc_oasis_sheep", "OASIS_SHEEP_SC", "Oasis Sheep", "Oasis Sheep", "SHEEP", 10,
         "A very wet Oasis Sheep climbs out of the pool.", fxp=180)
# --- dwarven mines (quarry water) ---
for size, mult, w in (("Small", 1, 200), ("Medium", 2, 110), ("Large", 4, 55), ("Bloated", 8, 20)):
    mid = "sc_%s_mithril_grubber" % size.lower()
    creature(mid, "MITHRIL_GRUBBER_SC", "%s Ore Grubber" % size, "%s Mithril Grubber" % size, "SILVERFISH", 6,
             "A %s Ore Grubber wriggles out of the quarry water." % size.lower(), hp=6000 * mult,
             extra={"powder": {"mithril": 250 * mult}, "grubberWeight": w}, fxp=245)
# --- crystal hollows ---
creature("sc_water_worm", "WATER_WORM_SC", "Water Worm", "Water Worm", "SILVERFISH", 15,
         "A Water Worm burrows out of the cave pool.")
creature("sc_poisoned_water_worm", "POISONED_WATER_WORM_SC", "Poisoned Water Worm", "Poisoned Water Worm",
         "ENDERMITE", 17, "A Poisoned Water Worm oozes up from the depths.")
creature("sc_abyssal_miner", "ZOMBIE_MINER_SC", "Abyssal Miner", "Abyssal Miner", "ZOMBIE", 24,
         "An Abyssal Miner smashes its way out of the water!", beh=["fishing_slam"],
         extra={"slamEvery": 9, "slamRadius": 4, "slamMult": 1.4})
creature("sc_flaming_worm", "FLAMING_WORM_SC", "Flaming Worm", "Flaming Worm", "SILVERFISH", 19,
         "A Flaming Worm wriggles out of the magma.", flags=["magmatic"])
creature("sc_lava_blaze", "LAVA_BLAZE_SC", "Lava Blaze", "Lava Blaze", "BLAZE", 20,
         "A Lava Blaze climbs out of the molten rock.", flags=["magmatic"], beh=["fishing_fireball"],
         extra={"fireballEvery": 5})
creature("sc_lava_pigman", "LAVA_PIGMAN_SC", "Lava Pigman", "Lava Pigman", "ZOMBIFIED_PIGLIN", 22,
         "A Lava Pigman snorts its way out of the lava!", flags=["magmatic"])
# --- crimson isle lava ---
creature("sc_magma_slug", "MAGMA_SLUG_SC", "Magma Slug", "Magma Slug", "MAGMA_CUBE", 27,
         "A Magma Slug oozes out of the lava.", flags=["magmatic"])
creature("sc_moogma", "MOOGMA_SC", "Magma Cow", "Moogma", "MOOSHROOM", 28,
         "A long moo echoes from the lava. A Magma Cow!", flags=["magmatic"], fxp=950, level=210)
creature("sc_lava_leech", "LAVA_LEECH_SC", "Lava Leech", "Lava Leech", "ENDERMITE", 30,
         "A small but nasty Lava Leech grabs your line.", flags=["magmatic"])
creature("sc_pyroclastic_worm", "PYROCLASTIC_WORM_SC", "Pyroclastic Worm", "Pyroclastic Worm", "SILVERFISH", 31,
         "The heat spikes as a Pyroclastic Worm surfaces.", flags=["magmatic"], hp=1200000)
creature("sc_lava_flame", "LAVA_FLAME_SC", "Lava Flame", "Lava Flame", "BLAZE", 33,
         "A Lava Flame shoots out of the lava!", flags=["magmatic"], beh=["fishing_fireball"],
         extra={"fireballEvery": 4})
creature("sc_fire_eel", "FIRE_EEL_SC", "Fire Eel", "Fire Eel", "GUARDIAN", 34,
         "A Fire Eel coils out of the molten depths.", flags=["magmatic"], beh=["fishing_fireball"],
         extra={"fireballEvery": 4})
creature("sc_taurus", "TAURUS_SC", "Taurus", "Taurus", "ZOMBIFIED_PIGLIN", 35,
         "Taurus charges out of the lava on his burning steed!", flags=["magmatic"], beh=["fishing_slam"],
         extra={"slamEvery": 7, "slamRadius": 4, "slamMult": 1.5})
creature("sc_thunder", "THUNDER_SC", "Thunder", "Thunder", "GUARDIAN", 36,
         "The ground shakes. Thunder rolls up out of the lava.", flags=["magmatic"],
         beh=["fishing_lightning"], extra={"lightningEvery": 5}, fxp=12000)
creature("sc_lord_jawbus", "LORD_JAWBUS_SC", "Lord Gnashmaw", "Lord Jawbus", "IRON_GOLEM", 45,
         "You have angered something ancient... Lord Gnashmaw rises from the lava!", flags=["magmatic"],
         beh=["fishing_fireball", "fishing_slam"], fxp=40000,
         extra={"fireballEvery": 3, "slamEvery": 10, "slamRadius": 5, "slamMult": 3.0, "requires": "trophy:title:2"})
creature("sc_plhlegblast", "PLHLEGBLAST_SC", "Phlegmburst", "Plhlegblast", "GLOW_SQUID", 36,
         "WHOA! A Phlegmburst gurgles up from the pool.", flags=["magmatic"])
# --- crimson isle lava hotspots ---
creature("sc_fried_chicken", "FRIED_CHICKEN_SC", "Fried Chicken", "Fried Chicken", "CHICKEN", 14,
         "Smells like dinner. It's a Fried Chicken!", flags=["magmatic"])
creature("sc_volcanic_snail", "VOLCANIC_SNAIL_SC", "Volcanic Snail", "Volcanic Snail", "MAGMA_CUBE", 17,
         "Ouch, that's hot! You reeled in a Volcanic Snail.", flags=["magmatic"], hp=150000, fxp=600)
creature("sc_fireproof_witch", "FIREPROOF_WITCH_SC", "Fireproof Witch", "Fireproof Witch", "WITCH", 22,
         "Something's brewing in the lava: a Fireproof Witch!", flags=["magmatic"])
creature("sc_magma_pillar", "MAGMA_PILLAR_SC", "Magma Pillar", "Magma Pillar", "BLAZE", 31,
         "A Magma Pillar towers up out of the lava.", flags=["magmatic"], beh=["fishing_fireball"],
         hp=2500000, fxp=2000, extra={"fireballEvery": 3})
creature("sc_fiery_scuttler", "FIERY_SCUTTLER_SC", "Fiery Scuttler", "Fiery Scuttler", "MAGMA_CUBE", 37,
         "A Fiery Scuttler sidles up to you... with friends.", flags=["magmatic"], beh=["fishing_slam"],
         extra={"slamEvery": 6, "slamRadius": 4, "slamMult": 1.5})
creature("sc_ragnarok", "RAGNAROK_SC", "Ragnarok", "Ragnarok", "WITHER_SKELETON", 47,
         "The sky darkens and the lava boils. Ragnarok has come.", flags=["magmatic"],
         beh=["fishing_slam", "fishing_fireball"], extra={"slamEvery": 8, "slamRadius": 6, "slamMult": 2.5,
                                                          "fireballEvery": 3})
# --- water hotspots ---
creature("sc_frog_man", "FROG_MAN_SC", "Frog Man", "Frog Man", "DROWNED", 5,
         "Half frog, half man, all trouble - it's Frog Man!")
creature("sc_inkling", "INKLING_SC", "Inkspawn", "Inkling", "SQUID", 7,
         "A tiny Inkspawn squirts out of the hotspot.", fxp=300,
         drops=[("INK_SACK", 2, 4, 1.0), ("ENCHANTED_INK_SACK", 1, 1, 0.04), ("CLAY_BALL", 1, 2, 1.0),
                ("DYE_AQUAMARINE", 1, 1, 0.00002)], level=40)
creature("sc_snapping_turtle", "SNAPPING_TURTLE_SC", "Snapping Turtle", "Snapping Turtle", "TURTLE", 10,
         "A very angry Snapping Turtle is coming your way!")
creature("sc_manta_ray", "MANTA_RAY_SC", "Manta Ray", "Manta Ray", "PHANTOM", 19,
         "A Manta Ray glides up out of the hotspot.", hp=300000, fxp=1200)
creature("sc_blue_ringed_octopus", "BLUE_RINGED_OCTOPUS_SC", "Blue Ringed Octopus", "Blue Ringed Octopus",
         "GLOW_SQUID", 35, "Bright rings flash beneath you - a Blue Ringed Octopus!", beh=["fishing_slam"], level=200,
         extra={"slamEvery": 7, "slamRadius": 4, "slamMult": 1.6})
creature("sc_wiki_tiki", "WIKI_TIKI_SC", "Tiki Totem", "Wiki Tiki", "IRON_GOLEM", 40,
         "The water froths and a Tiki Totem lurches out. You will pay for this!", beh=["fishing_slam"],
         extra={"slamEvery": 8, "slamRadius": 6, "slamMult": 2.2})
# --- backwater bayou ---
creature("sc_trash_gobbler", "TRASH_GOBBLER_SC", "Trash Gobbler", "Trash Gobbler", "ENDERMITE", 5,
         "The Trash Gobbler is hungry, and you look edible.")
creature("sc_dumpster_diver", "DUMPSTER_DIVER_SC", "Dumpster Diver", "Dumpster Diver", "ZOMBIE", 5,
         "A Dumpster Diver crawls out of the bayou muck.")
creature("sc_banshee", "BANSHEE_SC", "Banshee", "Banshee", "VEX", 10,
         "A mournful wail drifts over the swamp. A Banshee!")
creature("sc_bayou_sludge", "BAYOU_SLUDGE_SC", "Bayou Sludge", "Bayou Sludge", "SLIME", 15,
         "A wobbling heap of swamp gunk rises: the Bayou Sludge!")
creature("sc_alligator", "ALLIGATOR_SC", "Alligator", "Alligator", "RAVAGER", 20,
         "A long snout cuts through the water. Alligator!", beh=["fishing_slam"], level=120,
         extra={"slamEvery": 8, "slamRadius": 4, "slamMult": 1.5})
creature("sc_titanoboa", "TITANOBOA_SC", "Titanoboa", "Titanoboa", "DROWNED", 30,
         "The water bulges for twenty blocks. A Titanoboa uncoils!", beh=["fishing_slam"],
         extra={"slamEvery": 7, "slamRadius": 6, "slamMult": 2.2})
# --- moonlit marsh (our bayou's marsh; the reference's Galatea pool) ---
creature("sc_bogged", "BOGGED_SC", "Bogged", "Bogged", "BOGGED", 5, "A Bogged staggers out of the marsh, dripping moss.",
         drops=[("SEA_LUMIES", 1, 1, 1.0), ("MANGROVE_LOG", 4, 8, 1.0)], fxp=225, level=5)
creature("sc_wetwing", "WETWING_SC", "Wetwing", "Wetwing", "PHANTOM", 7, "Look up! A Wetwing shakes itself dry above you.",
         fxp=300)
creature("sc_tadgang", "TADGANG_FROG_SC", "Tadgang", "Tadgang", "FROG", 9, "A whole gang of tadpoles swarms your bobber!",
         fxp=500)
creature("sc_ent", "ENT_SC", "Ent", "Ent", "IRON_GOLEM", 12, "An Ent, old as the marsh itself, rises from the reeds.",
         fxp=1000, drops=[("MANGROVE_LOG", 16, 32, 1.0), ("ENCHANTED_MANGROVE_LOG", 1, 1, 0.1),
                          ("SEA_LUMIES", 2, 4, 1.0), ("DYE_AQUAMARINE", 1, 1, 0.00004)], level=60)
creature("sc_loch_emperor", "THE_LOCH_EMPEROR_SC", "Loch Sovereign", "The Loch Emperor", "ELDER_GUARDIAN", 20,
         "The Loch Sovereign rises, crowned in weed.", beh=["fishing_slam"], fxp=3375,
         extra={"slamEvery": 7, "slamRadius": 5, "slamMult": 1.8},
         drops=[("SEA_LUMIES", 16, 32, 1.0), ("ENCHANTED_SEA_LUMIES", 1, 1, 0.2), ("DIVER_FRAGMENT", 1, 1, 0.2),
                ("ENCHANTED_MANGROVE_LOG", 4, 8, 1.0), ("DYE_AQUAMARINE", 1, 1, 0.002)], level=150)
creature("sc_nessie", "NESSIE_SC", "Loch Wyrm", "Nessie", "SNIFFER", 25,
         "Ripples, then a long neck... the Loch Wyrm is real!", beh=["fishing_slam"], fxp=7500,
         extra={"slamEvery": 8, "slamRadius": 6, "slamMult": 2.0})
creature("sc_stridersurfer", "STRIDERSURFER_SC", "Lava Strider", "Stridersurfer", "STRIDER", 17,
         "A Lava Strider surfs your line right out of the vent.", flags=["magmatic"], fxp=700)
# --- lotus lagoon (our bayou's lagoon; the reference's atoll pool) ---
creature("sc_atoll_croaker", "ATOLL_CROAKER_SC", "Atoll Croaker", "Atoll Croaker", "FROG", 10,
         "A curious Atoll Croaker gulps down your bait.", fxp=200)
creature("sc_lotus_guardian", "LOTUS_GUARDIAN_SC", "Lotus Guardian", "Lotus Guardian", "IRON_GOLEM", 10,
         "A Lotus Guardian stands up out of the lagoon.", fxp=400)
creature("sc_gorf", "GORF_SC", "Grof", "gorF", "FROG", 10, "What is THAT? A... Grof?", fxp=800)
creature("sc_drowned_captain", "DROWNED_CAPTAIN_SC", "Drowned Captain", "Drowned Captain", "DROWNED", 10,
         "A Drowned Captain seizes your bobber!", fxp=1500, skull=False)
# The Puddle Jumper is ELUSIVE (NEU: 5 HP, Mob Type Elusive): nothing hurts it. It hops round the
# bobber; cast your rod INTO it and it takes off with you on the line - hold on (rod in hand, line
# unbroken) until it lands and it is yours (PuddleJumper.java, research: "cast your rod into it and
# hold on tight"). The ride numbers are ours (TUNE).
creature("sc_puddle_jumper", "PUDDLE_JUMPER_SC", "Puddle Jumper", "Puddle Jumper", "FROG", 10,
         "A Puddle Jumper is getting ready to leap - cast your rod into it and hold on tight!", hp=5, fxp=3000,
         flags=["passive"], beh=["fishing_puddle_jumper"],
         extra={"elusive": True, "rideSeconds": 6, "escapeSeconds": 40, "hopEvery": 2.5})
creature("sc_frog_prince", "FROG_PRINCE_SC", "Frog Prince", "Frog Prince", "FROG", 10,
         "Kneel before the Frog Prince... or else!", beh=["fishing_slam"], hp=1000000, fxp=6000,
         extra={"slamEvery": 7, "slamRadius": 5, "slamMult": 2.0})
# --- torrent canyon (galatea module's island `torrhus_canyon`, reference Torrhus Canyon) ---
# No NEU *_SC pages exist: levels are NEU's bestiary (44/58/71/85/105/186), rarities, catch order and the
# signature drops are the reference's (Sea Brine, Sublime Silk, Water Hyacinth, Distant Echo, Reinforced
# Netting, Isopod Husk - each item's own lore names its creature); HP, Fishing XP, coins and drop chances are
# ours (TUNE). Names match the hunting module's shard renames (Brineling -> Saltling, Silkbreeze -> Silkdrift).
creature("sc_haggard", None, "Haggard", "Haggard", "HUSK", 15,
         "A Haggard staggers up the bank, spoiling for a fight!", hp=15000, fxp=250, level=44,
         drops=[("SEA_LUMIES", 1, 2, 1.0), ("DYE_AQUAMARINE", 1, 1, 0.0000002)], extra={"coinsDefault": 40})
creature("sc_brineling", None, "Saltling", "Brineling", "DROWNED", 15,
         "A Saltling cuts you off with a stream of bubbles!", hp=30000, fxp=450, level=58,
         drops=[("SEA_LUMIES", 1, 3, 1.0), ("SEA_BRINE", 1, 1, 0.05)], extra={"coinsDefault": 60})
creature("sc_sprawl", None, "Sprawl", "Sprawl", "SPIDER", 15,
         "A Sprawl unfolds out of the blue, and it has seen you.", hp=60000, fxp=900, level=71,
         drops=[("SEA_LUMIES", 2, 4, 1.0), ("ENCHANTED_SEA_LUMIES", 1, 1, 0.02)], extra={"coinsDefault": 90})
creature("sc_torrid", None, "Torrid", "Torrid", "VEX", 15,
         "Laughter skims across the water - a Torrid!", hp=120000, fxp=1700, level=85, beh=["fishing_fireball"],
         drops=[("SEA_LUMIES", 2, 4, 1.0), ("ENCHANTED_SEA_LUMIES", 1, 1, 0.05), ("WATER_HYACINTH", 1, 1, 0.01)],
         extra={"coinsDefault": 150, "fireballEvery": 5})
creature("sc_silkbreeze", None, "Silkdrift", "Silkbreeze", "BREEZE", 15,
         "Something zips over the springs - a Silkdrift!", hp=400000, fxp=3500, level=105, speed=1.3,
         drops=[("ENCHANTED_SEA_LUMIES", 1, 1, 1.0), ("SUBLIME_SILK", 1, 1, 0.05), ("DISTANT_ECHO", 1, 1, 0.002),
                ("DYE_AQUAMARINE", 1, 1, 0.00002)], extra={"coinsDefault": 400})
creature("sc_giant_isopod", None, "Giant Isopod", "Giant Isopod", "ARMADILLO", 15,
         "A Giant Isopod is dredged up out of the depths!", hp=3000000, fxp=7000, level=186, beh=["fishing_slam"],
         drops=[("ENCHANTED_SEA_LUMIES", 1, 2, 1.0), ("ISOPOD_HUSK", 1, 1, 0.05), ("REINFORCED_NETTING", 1, 1, 0.02),
                ("DYE_AQUAMARINE", 1, 1, 0.00002)],
         extra={"coinsDefault": 1500, "slamEvery": 8, "slamRadius": 5, "slamMult": 2.0})
# --- winter workshop ---
creature("sc_frozen_steve", "FROZEN_STEVE_SC", "Frozen Wanderer", "Frozen Steve", "STRAY", 1,
         "A Frozen Wanderer thaws out of the pond after years under the ice.")
creature("sc_frosty", "FROSTY_SC", "Frosty", "Frosty", "SNOW_GOLEM", 1, "It's a snowman! He seems friendly enough.",
         hp=7000)
creature("sc_grinch", "GRINCH_SC", "Gift Goblin", "Grinch", "ZOMBIE", 13,
         "A Gift Goblin is running off with the presents - catch him!", flags=["baby", "passive"], hp=100, speed=1.4)
creature("sc_nutcracker", "NUTCRACKER_SC", "Nutcracker", "Nutcracker", "ZOMBIE", 28,
         "A forgotten Nutcracker marches up from under the ice.", fxp=950, beh=["fishing_slam"],
         extra={"slamEvery": 8, "slamRadius": 4, "slamMult": 1.4})
creature("sc_yeti", "YETI_SC", "Yeti", "Yeti", "POLAR_BEAR", 25, "What in the world is THAT? A Yeti!",
         beh=["fishing_slam"], extra={"slamEvery": 7, "slamRadius": 5, "slamMult": 1.8})
creature("sc_reindrake", "REINDRAKE_SC", "Frost Drake", "Reindrake", "PHANTOM", 35,
         "Ice cracks and a Frost Drake takes shape from the depths.", beh=["fishing_gifts", "fishing_slam"],
         hp=2500000, fxp=8000, extra={"slamEvery": 8, "slamRadius": 5, "slamMult": 2.0})
# --- spooky festival ---
creature("sc_jumpin_jack", None, "Hopping Jack", "Jumpin' Jack", "ZOMBIE", 6, "Look out! Hopping Jack bounces at you.",
         hp=3000, fxp=210, level=8,
         drops=[("PUMPKIN", 1, 1, 1.0), ("GREEN_CANDY", 1, 1, 1.0), ("PURPLE_CANDY", 1, 1, 0.1),
                ("ENCHANTED_PUMPKIN", 1, 1, 0.01)], extra={"coinsDefault": 50})
creature("sc_scarecrow", "SCARECROW_SC", "Scarecrow", "Scarecrow", "ZOMBIE", 9, "Phew, only a Scarecrow.")
creature("sc_nightmare", "NIGHTMARE_SC", "Nightmare", "Nightmare", "SKELETON_HORSE", 14,
         "Hooves thud under the waves. A Nightmare surfaces.")
creature("sc_werewolf", "WEREWOLF_SC", "Werewolf", "Werewolf", "WOLF", 17, "The moon is full... a Werewolf leaps out!")
creature("sc_phantom_fisher", "PHANTOM_FISHER_SC", "Phantom Fisher", "Phantom Fisher", "STRAY", 21,
         "The ghost of a long-drowned fisher has come for its rod.", beh=["fishing_slam"],
         extra={"slamEvery": 8, "slamRadius": 4, "slamMult": 1.6})
creature("sc_grim_reaper", "GRIM_REAPER_SC", "Grim Reaper", "Grim Reaper", "WITHER_SKELETON", 26,
         "No... death itself rises from the water!", beh=["fishing_slam"],
         extra={"slamEvery": 7, "slamRadius": 5, "slamMult": 2.0})
# --- fishing festival sharks ---
creature("sc_nurse_shark", "NURSE_SHARK_SC", "Nurse Shark", "Nurse Shark", "DROWNED", 5,
         "A small fin pokes out of the water: a Nurse Shark.", speed=1.2)
creature("sc_blue_shark", "BLUE_SHARK_SC", "Blue Shark", "Blue Shark", "DROWNED", 10,
         "A fin as blue as the sea - a Blue Shark!", speed=1.25)
creature("sc_tiger_shark", "TIGER_SHARK_SC", "Tiger Shark", "Tiger Shark", "DROWNED", 18,
         "A striped hunter bursts from the deep: Tiger Shark!", speed=1.3)
creature("sc_great_white_shark", "GREAT_WHITE_SHARK_SC", "Great White Shark", "Great White Shark", "DROWNED", 24,
         "A Great White Shark has your scent, and it is hungry!", speed=1.35, beh=["fishing_slam"],
         extra={"slamEvery": 8, "slamRadius": 4, "slamMult": 1.8})

RARE_FLAG = set()
SH = L(os.path.join(DATA, "skyhanni-repo", "constants", "SeaCreatures.json"))
SH_BY_NAME = {}
for cat in SH.values():
    for n, c in cat["sea_creatures"].items():
        SH_BY_NAME[n] = c

FXP_BY_RARITY = {"COMMON": 60, "UNCOMMON": 200, "RARE": 500, "EPIC": 1000, "LEGENDARY": 3000, "MYTHIC": 7500}  # TUNE


def parse_chance(s):
    s = str(s).strip().split(" ")[0]
    if s.endswith("%"):
        return float(s[:-1]) / 100.0
    try:
        return float(s)
    except ValueError:
        return 1.0


def conv_drop_id(raw):
    """NEU drop ids -> our ids. Returns (id, count) or (None, reason)."""
    cnt = 1
    if ":" in raw:
        a, b = raw.rsplit(":", 1)
        try:
            cnt = int(float(b))
            raw = a
        except ValueError:
            pass
    if raw.startswith("ATTRIBUTE_SHARD"):
        return None, "shard"
    if raw.startswith("SKYBLOCK_POWDER_"):
        return ("POWDER:" + raw[len("SKYBLOCK_POWDER_"):].lower()), cnt
    m = re.match(r"^([A-Z_]+)-(\d+)$", raw)
    if m:
        raw = m.group(1) + ":" + m.group(2)
    m = re.match(r"^([A-Z_]+);(\d+)$", raw)
    if m:
        base, lv = m.group(1), int(m.group(2))
        if (base + ";" + str(lv)) in OTHER or neu(base + ";" + str(lv)) and base + ";0" in OTHER:
            return base + ";" + str(lv), cnt     # pets keep their ';' id
        # an enchanted book: the enchanting module registers ENCHANTMENT_<X>_<L>
        return "ENCHANTMENT_%s_%d" % (base, lv), cnt
    return raw, cnt


# The Puddle Jumper's trophy-frog drop is rolled by the galatea module on the kill (its frog tiers have
# their own pity; dial galatea.puddle-jumper-drop), so it is left out of our table rather than paid twice.
NOT_HERE_DROPS = {"PUDDLE_JUMPER_BRONZE", "PUDDLE_JUMPER_SILVER", "PUDDLE_JUMPER_GOLD", "PUDDLE_JUMPER_DIAMOND"}


def mob_level_guess(hp):
    return max(1, int(round(4 * math.log10(max(hp, 10)) ** 2)))


def damage_for(hp, mult):
    # TUNE: the reference does not publish sea creature damage. 2.5 * HP^0.45 gives Sea Walker 20,
    # Water Hydra ~920 and Lord Jawbus ~10,000 (the wiki's figure for Jawbus).
    return int(round(2.5 * (max(hp, 1) ** 0.45) * mult))


# Live numbers (audit 2026-10-06 F1). Hypixel's 0.27.2 patch (2026-10-06, "Mob Level Improvements")
# recomputed every sea creature: Mob Level = (log10(HP x damage) - 2)^2.6 (x1.5 Mythic), Fishing XP =
# level x 7.5/10/12.5/15/20/25 by rarity (floor, minimum 25), coins = level x 0.75/1/1.25/1.5/2/5. The
# March 2025 rebalance the audit names (Sea Walker 68 -> 150, Squid 41 -> 75, Sea Guardian 101 -> 250, Sea
# Witch 338 -> 400) is superseded by it. Values read off each creature's wiki page on 2026-10-06 (the
# page's infobox AND its 0.27.2 history line); a creature missing here keeps NEU/SkyHanni/our numbers.
# reference name -> (mob level, Fishing XP, coins, rarity or None)
# Wiki infoboxes not updated by 0.27.2, so the FORMULA value is used (level x rarity rate): Night Squid (page
# still 270), Sea Guardian (page 250; the page also says it became Jumpin' Jack in 0.25), Tadgang and
# Stridersurfer (the patch line filed level x 12.5 = 362 / 775 under "Combat XP"). The Mithril Grubbers' page
# was not updated at all (four sizes, one stale Lv15 box), so they keep ours. "Sea Emperor" now redirects to
# The Loch Emperor; our sc_sea_emperor (open water) keeps its old numbers.
LIVE = {
    'Squid': (1, 25, 1, 'COMMON'),
    'Sea Walker': (1, 25, 1, 'COMMON'),
    'Night Squid': (3, 25, 2, 'COMMON'),
    'Sea Guardian': (33, 247, 24, 'COMMON'),
    'Sea Witch': (35, 350, 35, 'UNCOMMON'),
    'Sea Archer': (36, 360, 36, 'UNCOMMON'),
    'Rider of the Deep': (54, 540, 54, 'UNCOMMON'),
    'Catfish': (59, 737, 73, 'RARE'),
    'Sea Leech': (74, 925, 92, 'RARE'),
    'Guardian Defender': (75, 1125, 112, 'EPIC'),
    'Deep Sea Protector': (90, 1350, 135, 'EPIC'),
    'Water Hydra': (119, 2380, 238, 'LEGENDARY'),
    'Carrot King': (63, 787, 78, 'RARE'),
    'Agarimoo': (81, 1012, 101, 'RARE'),
    'Oasis Rabbit': (4, 40, 4, 'UNCOMMON'),
    'Oasis Sheep': (4, 40, 4, 'UNCOMMON'),
    'Water Worm': (71, 887, 88, 'RARE'),
    'Poisoned Water Worm': (83, 1037, 103, 'RARE'),
    'Abyssal Miner': (182, 3640, 364, 'LEGENDARY'),
    'Flaming Worm': (92, 1150, 115, 'RARE'),
    'Lava Blaze': (123, 1845, 184, 'EPIC'),
    'Lava Pigman': (129, 1935, 193, 'EPIC'),
    'Magma Slug': (186, 1860, 186, 'UNCOMMON'),
    'Moogma': (203, 2030, 203, 'UNCOMMON'),
    'Lava Leech': (215, 2687, 268, 'RARE'),
    'Pyroclastic Worm': (221, 2762, 276, 'RARE'),
    'Lava Flame': (219, 2737, 273, 'RARE'),
    'Fire Eel': (243, 3037, 303, 'RARE'),
    'Taurus': (262, 3930, 393, 'EPIC'),
    'Thunder': (324, 6480, 648, 'LEGENDARY'),
    'Lord Jawbus': (597, 14925, 2985, 'MYTHIC'),
    'Plhlegblast': (210, 5250, 1050, 'MYTHIC'),
    'Fried Chicken': (86, 645, 64, 'COMMON'),
    'Volcanic Snail': (90, 900, 90, 'UNCOMMON'),
    'Fireproof Witch': (150, 1875, 187, 'RARE'),
    'Magma Pillar': (220, 3300, 330, 'EPIC'),
    'Fiery Scuttler': (291, 5820, 582, 'LEGENDARY'),
    'Ragnarok': (612, 15300, 3060, 'MYTHIC'),
    'Frog Man': (24, 180, 18, 'COMMON'),
    'Inkling': (38, 380, 38, 'UNCOMMON'),
    'Snapping Turtle': (60, 750, 75, 'RARE'),
    'Manta Ray': (124, 1860, 186, 'EPIC'),
    'Blue Ringed Octopus': (237, 4740, 474, 'LEGENDARY'),
    'Wiki Tiki': (592, 14800, 2960, 'MYTHIC'),
    'Trash Gobbler': (17, 127, 12, 'COMMON'),
    'Dumpster Diver': (21, 210, 21, 'UNCOMMON'),
    'Banshee': (56, 700, 70, 'RARE'),
    'Bayou Sludge': (64, 960, 96, 'EPIC'),
    'Alligator': (132, 2640, 264, 'LEGENDARY'),
    'Titanoboa': (517, 12925, 2585, 'MYTHIC'),
    'Bogged': (25, 187, 18, 'COMMON'),
    'Wetwing': (46, 460, 46, 'UNCOMMON'),
    'Tadgang': (29, 362, 36, 'RARE'),
    'Ent': (60, 900, 90, 'EPIC'),
    'The Loch Emperor': (101, 2020, 202, 'LEGENDARY'),
    'Nessie': (292, 7300, 1460, 'MYTHIC'),
    'Stridersurfer': (62, 775, 77, 'RARE'),
    'Atoll Croaker': (45, 337, 33, 'COMMON'),
    'Lotus Guardian': (55, 550, 55, 'UNCOMMON'),
    'gorF': (65, 812, 81, 'RARE'),
    'Drowned Captain': (74, 1110, 111, 'EPIC'),
    'Puddle Jumper': (1, 25, 2, 'LEGENDARY'),
    'Frog Prince': (292, 7300, 1460, 'MYTHIC'),
    'Haggard': (44, 330, 33, 'COMMON'),
    'Brineling': (58, 580, 58, 'UNCOMMON'),
    'Sprawl': (71, 887, 88, 'RARE'),
    'Torrid': (85, 1275, 127, 'EPIC'),
    'Silkbreeze': (105, 2100, 210, 'LEGENDARY'),
    'Giant Isopod': (186, 4650, 930, 'MYTHIC'),
    'Frozen Steve': (1, 25, 1, 'COMMON'),
    'Frosty': (3, 30, 3, 'UNCOMMON'),
    'Grinch': (25, 312, 31, 'RARE'),
    'Nutcracker': (115, 1725, 172, 'EPIC'),
    'Yeti': (175, 3500, 350, 'LEGENDARY'),
    'Reindrake': (186, 4650, 930, 'MYTHIC'),
    "Jumpin' Jack": (28, 210, 21, 'COMMON'),
    'Scarecrow': (32, 320, 32, 'UNCOMMON'),
    'Nightmare': (77, 962, 96, 'RARE'),
    'Werewolf': (69, 1035, 103, 'EPIC'),
    'Phantom Fisher': (157, 3140, 314, 'LEGENDARY'),
    'Grim Reaper': (312, 7800, 1560, 'MYTHIC'),
    'Nurse Shark': (22, 220, 22, 'UNCOMMON'),
    'Blue Shark': (52, 650, 65, 'RARE'),
    'Tiger Shark': (99, 1485, 148, 'EPIC'),
    'Great White Shark': (160, 3200, 320, 'LEGENDARY'),
}

# Drop chances where the current wiki disagrees with the NEU page (audit 2026-10-06 F3-F6; research
# SkyblockMoney/work/fishing.md, "Can of Worms is 5%, not NEU's 20%"). mob -> item -> chance.
DROP_FIX = {
    "sc_trash_gobbler": {"CAN_OF_WORMS": 0.05},       # F3: NEU 20%
    "sc_dumpster_diver": {"BRONZE_BOWL": 0.02},       # F4: NEU 4.5%
    "sc_fried_chicken": {"FRIED_FEATHER": 0.10},      # F5: NEU 50%
    "sc_fireproof_witch": {"SINGED_POWDER": 0.01},    # F6: NEU 2.5%
}

mobs = []
DROP_IDS = set()
for mid, c in C.items():
    page = neu(c["page"]) if c["page"] else None
    hp = None
    if page:
        for l in page.get("lore", []):
            m = re.search(r"Health§8: §c([\d,\.]+)", l)
            if m:
                hp = float(m.group(1).replace(",", "").replace(".", "") if m.group(1).count(".") > 0 and "," in m.group(1)
                           else m.group(1).replace(",", ""))
    rec = None
    if page:
        for r in page.get("recipes", []):
            if r.get("type") == "drops":
                rec = r
                break
    if c["hp"] is not None:
        hp = c["hp"]
    if hp is None:
        hp = 1000
        print("WARN no hp for", mid, file=sys.stderr)
    sh = SH_BY_NAME.get(c["ref"]) or SH_BY_NAME.get(c["ref"].replace("Small ", "").replace("Medium ", "")
                                                    .replace("Large ", "").replace("Bloated ", "")) or {}
    rarity = c["rarity"] or sh.get("rarity") or "COMMON"
    fxp = c["fxp"] if c["fxp"] is not None else (sh.get("fishing_experience") or 0)
    if not fxp:
        fxp = FXP_BY_RARITY.get(rarity, 100)
    rare = bool(sh.get("rare")) or rarity in ("LEGENDARY", "MYTHIC")
    level = c["level"] or (rec.get("level") if rec else None) or mob_level_guess(hp)
    coins = (rec.get("coins") if rec else None) or c["extra"].get("coinsDefault") or max(1, int(level // 4))
    live = LIVE.get(c["ref"])
    if live:   # the live game's 0.27.2 numbers win over NEU / SkyHanni / our TUNE values
        level, fxp, coins = live[0], live[1], live[2]
        rarity = live[3] or rarity
        rare = rare or rarity in ("LEGENDARY", "MYTHIC")
    cxp = (rec.get("xp") if rec else None) or max(1, int(level // 3))
    drops = []
    powder = dict(c["extra"].get("powder") or {})
    if c["drops"] is not None:
        for (iid, a, b, ch) in c["drops"]:
            drops.append({"item": iid, "min": a, "max": b, "chance": ch})
    elif rec:
        for d in rec.get("drops", []):
            iid, cnt = conv_drop_id(d["id"])
            if iid is None or iid in NOT_HERE_DROPS:
                continue
            ch = parse_chance(d.get("chance", "100%"))
            lo = hi = cnt
            for ex in d.get("extra", []) or []:
                m = re.search(r"x(\d+)-(\d+)", strip(ex))
                if m:
                    lo, hi = int(m.group(1)), int(m.group(2))
            if iid.startswith("POWDER:"):
                powder.setdefault(iid.split(":", 1)[1], lo)
                continue
            drops.append({"item": iid, "min": lo, "max": hi, "chance": round(ch, 9)})
    for d in drops:
        fix = DROP_FIX.get(mid, {}).get(d["item"])
        if fix is not None:
            d["chance"] = fix
        DROP_IDS.add(d["item"])
    flags = ["sea_creature", "aggressive"] + c["flags"]
    if "passive" in flags:
        flags.remove("aggressive")
    if "magmatic" in flags:
        flags.append("fire_immune")
    types = ["sea_creature", "aquatic"] if "magmatic" not in flags else ["sea_creature", "magmatic"]
    if c["entity"] in ("SKELETON", "STRAY", "WITHER_SKELETON", "ZOMBIE", "DROWNED", "ZOMBIFIED_PIGLIN", "BOGGED", "HUSK"):
        types.append("undead")
    if "squid" in mid or "inkling" in mid:
        types.append("squid")
    if mid in ("sc_jumpin_jack", "sc_scarecrow", "sc_nightmare", "sc_werewolf", "sc_phantom_fisher", "sc_grim_reaper"):
        types.append("spooky")
    extra = {k: v for k, v in c["extra"].items() if k not in ("coinsDefault", "powder", "grubberWeight")}
    extra.update({"fishingXp": fxp, "rarity": rarity, "rare": rare, "catchMessage": c["msg"], "fishingLevel": c["lvl"],
                  "lava": "magmatic" in c["flags"], "types": types, "refName": c["ref"]})
    if powder:
        extra["powder"] = powder
    md = OrderedDict()
    md["id"] = mid
    md["name"] = c["name"]
    md["level"] = int(level)
    md["entity"] = c["entity"]
    md["health"] = hp
    md["damage"] = damage_for(hp, c["dmult"]) if "passive" not in flags else 0
    md["speed"] = c["speed"]
    md["combatXp"] = cxp
    md["coins"] = coins
    md["drops"] = drops
    md["family"] = re.sub(r"^sc_", "", mid)
    if mid.endswith("_mithril_grubber"):
        md["family"] = "mithril_grubber"
    md["flags"] = flags
    md["behaviours"] = c["beh"]
    humanoid = c["entity"] in ("ZOMBIE", "DROWNED", "SKELETON", "STRAY", "WITHER_SKELETON", "ZOMBIFIED_PIGLIN", "HUSK")
    sk = skull_of(page) if (c["skull"] and page and humanoid) else None
    if not sk and c["skull"] and humanoid and not page:
        sk = BEST_TEX.get(c["ref"])     # no *_SC page: the bestiary's head texture
    if sk:
        md["skull"] = sk
    md["extra"] = extra
    mobs.append(md)
    rename("sea creature", mid, c["ref"], c["name"])

# ---------------------------------------------------------------------------------------------
# Pools. mode: base (highest priority match is THE pool), add (merged in), replace (rolled as a
# chance to swap a rolled creature for one from this pool). Weights are the research's.
# ---------------------------------------------------------------------------------------------
LADDER = [5000, 2875, 1500, 500, 100, 25]


def pc(mob, weight, cond=None):
    e = {"mob": mob, "weight": weight}
    if cond:
        e["cond"] = cond
    return e


POOLS = [
    {"id": "water", "name": "Open Water", "liquid": "water", "mode": "base", "priority": 0, "islands": [],
     "creatures": [pc("sc_squid", 1200), pc("sc_sea_walker", 800), pc("sc_night_squid", 600, "night&bait:DARK_BAIT"),
                   pc("sc_sea_guardian", 600), pc("sc_sea_witch", 700), pc("sc_sea_archer", 550),
                   pc("sc_rider_of_the_deep", 400), pc("sc_catfish", 250), pc("sc_sea_leech", 160),
                   pc("sc_guardian_defender", 130), pc("sc_deep_sea_protector", 88), pc("sc_water_hydra", 18),
                   pc("sc_sea_emperor", 6)]},
    {"id": "park", "name": "Park Waters", "liquid": "water", "mode": "add", "islands": ["park"],
     "creatures": [pc("sc_night_squid", 600, "night")]},
    {"id": "oasis", "name": "Desert Oasis", "liquid": "water", "mode": "add", "islands": ["mushroom_desert"],
     "keywords": ["oasis"], "creatures": [pc("sc_oasis_rabbit", 300), pc("sc_oasis_sheep", 700)]},
    {"id": "quarry", "name": "Flooded Quarry", "liquid": "water", "mode": "add", "islands": ["dwarven_mines"],
     "creatures": [pc(m["id"], C[m["id"]]["extra"]["grubberWeight"]) for m in mobs if m["id"].endswith("_mithril_grubber")]},
    {"id": "hollows_water", "name": "Hollows Pools", "liquid": "water", "mode": "add", "islands": ["crystal_hollows"],
     "creatures": [pc("sc_water_worm", 300, "zone:goblin"), pc("sc_poisoned_water_worm", 300, "zone:goblin"),
                   pc("sc_abyssal_miner", 90, "zone:mithril|precursor|jungle")]},
    {"id": "hollows_lava", "name": "Hollows Magma", "liquid": "lava", "mode": "base", "priority": 10,
     "islands": ["crystal_hollows"],
     "creatures": [pc("sc_flaming_worm", 180, "zone:precursor&above:64"), pc("sc_lava_blaze", 36, "zone:magma"),
                   pc("sc_lava_pigman", 36, "zone:magma")]},
    {"id": "crimson_lava", "name": "Crimson Lava", "liquid": "lava", "mode": "base", "priority": 10,
     "islands": ["crimson_isle"],
     "creatures": [pc("sc_magma_slug", 1600), pc("sc_moogma", 1200), pc("sc_lava_leech", 600),
                   pc("sc_pyroclastic_worm", 400), pc("sc_lava_flame", 360), pc("sc_fire_eel", 280),
                   pc("sc_taurus", 160), pc("sc_thunder", 40), pc("sc_lord_jawbus", 8)]},
    {"id": "bayou", "name": "Backwater Bayou", "liquid": "water", "mode": "base", "priority": 10,
     "islands": ["backwater_bayou"],
     "creatures": [pc(m, w) for m, w in zip(["sc_trash_gobbler", "sc_dumpster_diver", "sc_banshee", "sc_bayou_sludge",
                                             "sc_alligator", "sc_titanoboa"], LADDER)]},
    # The Galatea pools live on their own islands (wave 5): the Moonmarsh is foraging's island `galatea`, the
    # Lotus Atoll and Torrent Canyon are the galatea module's. Until wave 5 they stood in for those islands
    # inside the Bayou's marsh/lagoon zones; those zones now fish the Bayou pool, as in the reference.
    {"id": "marsh", "name": "Moonmarsh", "liquid": "water", "mode": "base", "priority": 20,
     "islands": ["galatea"],
     "creatures": [pc(m, w) for m, w in zip(["sc_bogged", "sc_wetwing", "sc_tadgang", "sc_ent", "sc_loch_emperor",
                                             "sc_nessie"], LADDER)]},
    # the Moonmarsh's only lava creature. Our Moonmarsh has no lava yet, so the Bayou's marsh vent keeps it
    # (foraging Request: a lava vent on the Moonmarsh); both islands fish this pool.
    {"id": "marsh_lava", "name": "Marsh Vents", "liquid": "lava", "mode": "base", "priority": 20,
     "islands": ["galatea", "backwater_bayou"], "creatures": [pc("sc_stridersurfer", 200)]},
    {"id": "lagoon", "name": "Lotus Atoll", "liquid": "water", "mode": "base", "priority": 20,
     "islands": ["lotus_atoll"],
     "creatures": [pc(m, w) for m, w in zip(["sc_atoll_croaker", "sc_lotus_guardian", "sc_gorf", "sc_drowned_captain",
                                             "sc_puddle_jumper", "sc_frog_prince"], LADDER)]},
    {"id": "torrhus", "name": "Torrent Springs", "liquid": "water", "mode": "base", "priority": 20,
     "islands": ["torrhus_canyon"],
     "creatures": [pc(m, w) for m, w in zip(["sc_haggard", "sc_brineling", "sc_sprawl", "sc_torrid",
                                             "sc_silkbreeze", "sc_giant_isopod"], LADDER)]},
    {"id": "winter", "name": "Frozen Pond", "liquid": "water", "mode": "base", "priority": 10,
     "islands": ["jerrys_workshop"],
     "creatures": [pc(m, w) for m, w in zip(["sc_frozen_steve", "sc_frosty", "sc_grinch", "sc_nutcracker", "sc_yeti",
                                             "sc_reindrake"], LADDER)]},
    # replacement pools: "chance" is the base swap chance (0..1); bonuses are applied in code.
    {"id": "shark", "name": "Fishing Festival", "liquid": "water", "mode": "replace", "chance": 0.15, "trigger": "festival",
     "creatures": [pc("sc_nurse_shark", 2875), pc("sc_blue_shark", 1500), pc("sc_tiger_shark", 500),
                   pc("sc_great_white_shark", 100)]},
    {"id": "spooky", "name": "Spooky Festival", "liquid": "water", "mode": "replace", "chance": 0.15, "trigger": "spooky",
     "creatures": [pc(m, w) for m, w in zip(["sc_jumpin_jack", "sc_scarecrow", "sc_nightmare", "sc_werewolf",
                                             "sc_phantom_fisher", "sc_grim_reaper"], LADDER)]},
    {"id": "water_hotspot", "name": "Water Hotspot", "liquid": "water", "mode": "replace", "chance": 0.15,
     "trigger": "hotspot",
     "creatures": [pc(m, w) for m, w in zip(["sc_frog_man", "sc_inkling", "sc_snapping_turtle", "sc_manta_ray",
                                             "sc_blue_ringed_octopus", "sc_wiki_tiki"], LADDER)]},
    {"id": "lava_hotspot", "name": "Lava Hotspot", "liquid": "lava", "mode": "replace", "chance": 0.15, "trigger": "hotspot",
     "creatures": [pc(m, w) for m, w in zip(["sc_fried_chicken", "sc_volcanic_snail", "sc_fireproof_witch",
                                             "sc_magma_pillar", "sc_fiery_scuttler", "sc_ragnarok"], LADDER)]},
    {"id": "carrot", "name": "Carrot Bait", "liquid": "water", "mode": "replace", "chance": 0.05, "trigger": "bait:CARROT_BAIT",
     "creatures": [pc("sc_carrot_king", 1)]},
    {"id": "chumcap", "name": "Chumcap Bucket", "liquid": "water", "mode": "replace", "chance": 0.15,
     # wave 5+: the reference's trigger is a PLACED Chumcap Bucket of your own nearby (ChumBuckets.java)
     "trigger": "chumcap", "creatures": [pc("sc_agarimoo", 1)]},
    {"id": "plhlegblast", "name": "Phlegm Pool", "liquid": "lava", "mode": "replace", "chance": 0.001,
     "trigger": "zone:plhleg|phlegm", "islands": ["crimson_isle"], "creatures": [pc("sc_plhlegblast", 1)]},
]
MOB_IDS = {m["id"] for m in mobs}
for p in POOLS:
    for e in p["creatures"]:
        assert e["mob"] in MOB_IDS, (p["id"], e["mob"])
        e["level"] = C[e["mob"]]["lvl"]

# ---------------------------------------------------------------------------------------------
# Items this module registers.
# ---------------------------------------------------------------------------------------------
items_out = OrderedDict()
REQ_TYPES = {"SKILL", "COLLECTION", "HEART_OF_THE_MOUNTAIN"}


def req_keys(it):
    out = []
    for r in it.get("requirements", []) or []:
        t = r.get("type")
        if t == "SKILL":
            out.append("skill:%s:%d" % (r["skill"].lower(), r["level"]))
        elif t == "COLLECTION":
            out.append("collection:%s:%d" % (r["collection"], r["tier"]))
        elif t == "HEART_OF_THE_MOUNTAIN":
            out.append("hotm:%d" % r["tier"])
    return out


def mat_of(it, default="PAPER"):
    m = (it or {}).get("material") or default
    return {"SKULL_ITEM": "PLAYER_HEAD", "RAW_FISH": "COD", "WATER_LILY": "LILY_PAD", "INK_SACK": "INK_SAC",
            "EMPTY_MAP": "MAP", "WOOL": "WHITE_WOOL"}.get(m, m)


STAT_KEYS = {"STRENGTH", "FISHING_SPEED", "SEA_CREATURE_CHANCE", "TREASURE_CHANCE", "TROPHY_FISH_CHANCE",
             "DOUBLE_HOOK_CHANCE", "FEROCITY", "CRITICAL_DAMAGE", "CRITICAL_CHANCE", "INTELLIGENCE", "HEALTH", "DEFENSE"}
STAT_MAP = {"CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE"}


def base_item(iid, name, material, rarity, category, lore=None, stats=None, damage=0, tags=(), extra=None,
              reqs=None, skull=None, sell=0, max_stack=0, glow=False):
    d = OrderedDict()
    d["id"] = iid
    d["name"] = name
    d["material"] = material
    d["rarity"] = rarity
    d["category"] = category
    if damage:
        d["damage"] = damage
    if stats:
        d["stats"] = stats
    if lore:
        d["lore"] = lore
    if reqs:
        d["requirements"] = reqs
    if skull:
        d["skull"] = skull
    if sell:
        d["npcSell"] = sell
    if max_stack:
        d["maxStack"] = max_stack
    if glow:
        d["glow"] = True
    d["tags"] = sorted(set(tags))
    d["extra"] = extra or {}
    items_out[iid] = d
    return d


# ---- rods ----
ROD_META = {
    # id: (our name or None, lava, extra lore lines in our own words)
    "FISHING_ROD": (None, False, ["&7A plain rod. Every angler starts somewhere."]),
    "PRISMARINE_ROD": (None, False, ["&7Carved from sea-glass; light but sturdy."]),
    "SPONGE_ROD": (None, False, ["&7Soaks up the water so you never slip."]),
    "SPEEDSTER_ROD": (None, False, ["&7Built for quick, light casts."]),
    "FARMER_ROD": (None, False, ["&7A farmhand's rod, good for a pond after harvest."]),
    "CHALLENGE_ROD": (None, False, ["&7Proof you are taking fishing seriously."]),
    "CHAMP_ROD": (None, False, ["&7A rod for those who win the big ones."]),
    "LEGEND_ROD": (None, False, ["&7Sung about in every harbour tavern."]),
    "ROD_OF_THE_SEA": (None, False, ["&7The ocean answers whoever holds it."]),
    "ICE_ROD": (None, False, ["&7Frost creeps up the line as you reel.", "&7Strongest on the Winter Workshop."]),
    "WINTER_ROD": (None, False, ["&7A warmer grip for colder waters.", "&7Strongest on the Winter Workshop."]),
    "YETI_ROD": (None, False, ["&7Wrapped in the fur of a Yeti.", "&7Strongest on the Winter Workshop."]),
    "PHANTOM_ROD": (None, False, ["&7Its line glows faintly in the dark.", "&7Draws &5spooky &7creatures closer."]),
    "AUGER_ROD": (None, False, ["&7A drill-bit hook that bites deep."]),
    "THE_SHREDDER": (None, False, ["&7The line is serrated. Reel carefully."]),
    "GIANT_FISHING_ROD": (None, False, ["&7Ridiculously large. Hooks two at a time."]),
    "DIRT_ROD": (None, False, ["&7Cast it onto dirt and something still bites.", "&7At least you stay dry!"]),
    "CHUM_ROD": (None, False, ["&7Smells of chum. The fish love it."]),
    "STARTER_LAVA_ROD": (None, True, ["&7A first rod that does not melt."]),
    "POLISHED_TOPAZ_ROD": ("Topaz Rod", True, ["&7Polished topaz shrugs off the heat."]),
    "MAGMA_ROD": (None, True, ["&7Forged from hardened magma."]),
    "INFERNO_ROD": (None, True, ["&7Burns hot enough to boil the lava."]),
    "HELLFIRE_ROD": (None, True, ["&7The finest lava rod there is."]),
    "ELLES_LAVA_ROD": ("Heirloom Lava Rod", True, ["&7A keepsake lava rod, passed hand to hand."]),
}
for rid, (ours, lava, lore) in ROD_META.items():
    it = ITEMS[rid]
    ref = it["name"]
    name = ours or ref
    rename("rod", rid, ref, name)
    stats = OrderedDict()
    dmg = 0
    for k, v in it["stats"].items():
        if k == "DAMAGE":
            dmg = v
        elif k in STAT_KEYS:
            stats[STAT_MAP.get(k, k)] = v
    ll = (["&6Lava Rod", ""] if lava else []) + lore
    extra = {"lava": lava, "rod": True}
    if rid == "DIRT_ROD":
        extra["dirt"] = True
    if rid in ("ICE_ROD", "WINTER_ROD", "YETI_ROD"):
        extra["winterBonus"] = {"FISHING_SPEED": 20, "SEA_CREATURE_CHANCE": 2}  # TUNE: our own perk for legacy rods
    if rid == "PHANTOM_ROD":
        extra["spookyBonus"] = 0.10   # TUNE
    base_item(rid, name, "FISHING_ROD", it.get("tier", "COMMON") if it.get("tier") != "None" else "COMMON",
              "FISHING_ROD", lore=ll, stats=stats, damage=dmg,
              tags=["enchantable", "reforgeable", "museum", "rod"] + (["soulbound"] if rid == "STARTER_LAVA_ROD" else []),
              extra=extra, reqs=req_keys(it), sell=it.get("npc_sell_price") or 0)

# ---- rod parts ----
PARTS = OrderedDict()
PART_META = [
    # id, slot, our name, effect dict, lore (our words, numbers from the reference)
    ("COMMON_HOOK", "hook", None, {"commonWeight": 1.25}, ["&7Raises the odds of hooking &fCOMMON", "&7sea creatures by &a25%&7."]),
    ("HOTSPOT_HOOK", "hook", None, {"hotspotChance": 0.20}, ["&7While fishing in a &dHotspot&7, adds a", "&a+20% &7chance to hook a Hotspot creature."]),
    ("PHANTOM_HOOK", "hook", "Spooky Hook", {"spookyChance": 0.20}, ["&7During the &6Spooky Festival&7, adds a", "&a+20% &7chance to hook a spooky creature."]),
    ("TREASURE_HOOK", "hook", None, {"noCreatures": True}, ["&7Nothing alive will take this hook:", "&7you only reel in items and &6Treasure&7."]),
    ("PUDDLE_JUMPER_HOOK", "hook", None, {"mobWeight": {"sc_puddle_jumper": 5.0}}, ["&7Makes a &9Puddle Jumper &7five times", "&7as likely to bite."]),
    ("SPEEDY_LINE", "line", None, {"stats": {"FISHING_SPEED": 10}}, ["&7Grants &b+10 ☂ Fishing Speed&7."]),
    ("SHREDDED_LINE", "line", None, {"damage": 250, "stats": {"FEROCITY": 50}}, ["&7Grants &c+250 Damage &7and &c+50 ⫽ Ferocity&7."]),
    ("TITAN_LINE", "line", None, {"stats": {"DOUBLE_HOOK_CHANCE": 2}}, ["&7Grants &9+2 ⚓ Double Hook Chance&7."]),
    ("TROPHY_LINE", "line", None, {"stats": {"TROPHY_FISH_CHANCE": 5}}, ["&7Grants &6+5 ♔ Trophy Fish Chance&7."]),
    ("JUNK_SINKER", "sinker", None, {"junk": True}, ["&7Grants &6+10 ⛃ Treasure Chance &7in the", "&aBackwater Bayou&7, but every treasure", "&7catch there turns into junk."]),
    ("PRISMARINE_SINKER", "sinker", None, {"materialize": ["PRISMARINE_SHARD", "PRISMARINE_CRYSTALS"]}, ["&7Every catch also brings up a Prismarine", "&7Shard or Prismarine Crystals."]),
    ("SPONGE_SINKER", "sinker", None, {"materialize": ["SPONGE"]}, ["&7Every catch also brings up a Sponge."]),
    ("CHUM_SINKER", "sinker", None, {"materialize": ["CHUM"]}, ["&7Every catch also brings up some Chum."]),
    ("FESTIVE_SINKER", "sinker", None, {"festive": 0.05}, ["&7During the &fSeason of Gerald&7, catches", "&7sometimes come with a Gift."]),
    ("ICY_SINKER", "sinker", None, {"materialize": ["ICE"]}, ["&7Every catch also brings up a block of Ice."]),
    ("STINGY_SINKER", "sinker", None, {"keepBait": 0.10}, ["&7Grants a &a10% &7chance to keep your bait."]),
    ("HOTSPOT_SINKER", "sinker", None, {"hotspotMult": 2.0}, ["&7Doubles the bonus you get from fishing", "&7in a &dHotspot&7."]),
    ("LOTUS_SINKER", "sinker", None, {"materialize": ["LOTUS"]}, ["&7Every catch also brings up a Lotus."]),
]
for pid, slot, ours, eff, lore in PART_META:
    it = ITEMS[pid]
    name = ours or it["name"]
    rename("rod part", pid, it["name"], name)
    tier = it.get("tier") or "COMMON"
    base_item(pid, name, "PLAYER_HEAD" if skull_of(neu(pid)) else "PAPER", tier, "MATERIAL",
              lore=lore + ["", "&8%s - apply it at the Tackle-smith" % slot.capitalize()],
              tags=["rod_part", "museum"], extra={"rodPart": slot}, reqs=req_keys(it),
              skull=skull_of(neu(pid)), sell=it.get("npc_sell_price") or 0)
    PARTS[pid] = {"slot": slot, **eff}

# ---- baits ----
BAITS = OrderedDict()
BAIT_META = [
    # id, effect dict, lore (our words, reference numbers)
    ("MINNOW_BAIT", {"fs": 25}, ["&7Grants &b+25 ☂ Fishing Speed&7."]),
    ("FISH_BAIT", {"fs": 45}, ["&7Grants &b+45 ☂ Fishing Speed&7."]),
    ("LIGHT_BAIT", {"rareDay": 1.25}, ["&7Rare sea creatures bite more often", "&7during the &eday&7."]),
    ("DARK_BAIT", {"rareNight": 1.25}, ["&7Rare sea creatures bite more often", "&7at &8night&7."]),
    ("SPIKED_BAIT", {"scc": 6}, ["&7Grants &3+6 α Sea Creature Chance&7."]),
    ("SPOOKY_BAIT", {"fs": 25, "spookyChance": 0.15}, ["&7Grants &b+25 ☂ Fishing Speed &7and a", "&a+15% &7chance at spooky creatures", "&7during the &6Spooky Festival&7."]),
    ("CARROT_BAIT", {}, ["&7Something with long ears might bite..."]),
    ("CORRUPTED_BAIT", {"fsMult": 0.5, "lavaOnly": True}, ["&7Halves your &b☂ Fishing Speed&7, but lures", "&7a strange &5trophy fish &7in the lava."]),
    ("BLESSED_BAIT", {"blessed": 0.5}, ["&7Treasure catches are &a50% &7more likely", "&7to be &9GREAT &7or &6OUTSTANDING&7."]),
    ("ICE_BAIT", {"fs": 25, "scc": 4, "island": "jerrys_workshop"}, ["&7Grants &b+25 ☂ Fishing Speed &7and", "&3+4 α Sea Creature Chance&7.", "&cOnly works on the Winter Workshop."]),
    ("SHARK_BAIT", {"sharkChance": 0.20}, ["&7Adds a &a+20% &7chance to hook &bSharks", "&7during the &bFishing Festival&7."]),
    ("GLOWY_CHUM_BAIT", {"fs": 25, "scc": 3, "chum": 2}, ["&7Grants &b+25 ☂ Fishing Speed&7,", "&3+3 α Sea Creature Chance&7, and creatures", "&7caught with it drop &a+2 &7Chum."]),
    ("HOT_BAIT", {"fs": 15, "trophy": 5}, ["&7Grants &b+15 ☂ Fishing Speed &7and", "&6+5 ♔ Trophy Fish Chance&7."]),
    ("WORM_BAIT", {"fs": 60, "wormsOnly": True, "island": "crystal_hollows"}, ["&7Grants &b+60 ☂ Fishing Speed&7, but only", "&7worms will bite.", "&cOnly works in the Crystal Caverns."]),
    ("GOLDEN_BAIT", {"treasure": 4}, ["&7Grants &6+4 ⛃ Treasure Chance&7."]),
    ("WOODEN_BAIT", {"fs": 10, "treasure": 1}, ["&7A carved lure. Grants &b+10 ☂ Fishing Speed", "&7and &6+1 ⛃ Treasure Chance&7."]),
    ("FROZEN_BAIT", {"fs": 50, "scc": 8, "island": "jerrys_workshop"}, ["&7Grants &b+50 ☂ Fishing Speed &7and", "&3+8 α Sea Creature Chance&7.", "&cOnly works on the Winter Workshop."]),
    ("WHALE_BAIT", {"fs": 30, "rareAlways": 1.25}, ["&7Grants &b+30 ☂ Fishing Speed &7and makes", "&7rare sea creatures bite more often."]),
    ("TREASURE_BAIT", {"fs": 10, "treasure": 2}, ["&7Grants &b+10 ☂ Fishing Speed &7and", "&6+2 ⛃ Treasure Chance&7."]),
    ("HOTSPOT_BAIT", {"hotspotChance": 0.20}, ["&7While fishing in a &dHotspot&7, adds a", "&a+20% &7chance to hook a Hotspot creature."]),
]
for bid, eff, lore in BAIT_META:
    it = ITEMS[bid]
    name = it["name"]
    tier = it.get("tier") or "COMMON"
    base_item(bid, name, "PLAYER_HEAD" if skull_of(neu(bid)) else "PAPER", tier, "BAIT",
              lore=["&8Fishing Bait", "&8Used up when you cast", ""] + lore,
              tags=["bait", "bazaar"], extra={"bait": True}, skull=skull_of(neu(bid)), sell=it.get("npc_sell_price") or 0)
    BAITS[bid] = eff

# ---- trophy fish ----
TF = L(os.path.join(DATA, "skyhanni-repo", "constants", "TrophyFish.json"))["trophy_fish"]
TROPHY = OrderedDict()
TROPHY_META = [
    # stem, skyhanni key, our name, rarity, cond, material, our description
    ("SULPHUR_SKITTER", "sulphurskitter", "Sulphur Skitter", "COMMON", "sulphur", "PUFFERFISH", "Bites when you stand within &a4 blocks &7of a Sulphur (sponge) block."),
    ("OBFUSCATED_FISH_1", "obfuscatedfish1", "Obfuscated 1", "COMMON", "bait:CORRUPTED_BAIT", "TROPICAL_FISH", "Bites on Corrupted Bait, or drops from corrupted sea creatures."),
    ("STEAMING_HOT_FLOUNDER", "steaminghotflounder", "Steaming-Hot Flounder", "COMMON", "geyser", "COD", "Bites when your bobber floats within &a2 blocks &7of a geyser in the volcano."),
    ("GUSHER", "gusher", "Gusher", "COMMON", "eruption", "SALMON", "Bites in the volcano for a while after an eruption."),
    ("BLOBFISH", "blobfish", "Blobfish", "COMMON", "any", "PUFFERFISH", "Lives in every lava pool on the isle."),
    ("OBFUSCATED_FISH_2", "obfuscatedfish2", "Obfuscated 2", "UNCOMMON", "bait:OBFUSCATED_FISH_1", "TROPICAL_FISH", "Bites when you use an Obfuscated 1 as bait."),
    ("SLUGFISH", "slugfish", "Slugfish", "UNCOMMON", "patience", "COD", "Only bites a bobber that has been out for &a20 seconds&7."),
    ("FLYFISH", "flyfish", "Flyfish", "UNCOMMON", "height", "SALMON", "Bites when you fish from &a8 blocks &7or more above the lava in the volcano."),
    ("OBFUSCATED_FISH_3", "obfuscatedfish3", "Obfuscated 3", "RARE", "bait:OBFUSCATED_FISH_2", "TROPICAL_FISH", "Bites when you use an Obfuscated 2 as bait."),
    ("LAVA_HORSE", "lavahorse", "Lavahorse", "RARE", "any", "SALMON", "Swims everywhere, but rarely bites."),
    ("MANA_RAY", "manaray", "Mana Ray", "RARE", "mana", "TROPICAL_FISH", "Drawn to anglers holding &b1,200 Mana &7or more. More mana, more bites."),
    ("VOLCANIC_STONEFISH", "volcanicstonefish", "Volcanic Stonefish", "RARE", "zone:volcano", "COD", "Hides in the volcano's lava."),
    ("VANILLE", "vanille", "Plain Vanille", "RARE", "vanille", "COD", "Only bites a Starter Lava Rod with no enchantments."),
    ("SKELETON_FISH", "skeletonfish", "Skeleton Fish", "EPIC", "zone:desert|dragontail", "PUFFERFISH", "Haunts the lava of the desert and the barbarian town."),
    ("MOLDFIN", "moldfin", "Moldfin", "EPIC", "zone:marsh|scarleton", "TROPICAL_FISH", "Grows in the lava of the marsh and the mage town."),
    ("SOUL_FISH", "soulfish", "Soul Fish", "EPIC", "zone:stronghold", "SALMON", "Swims only in the stronghold's lava."),
    ("KARATE_FISH", "karatefish", "Karate Fish", "EPIC", "zone:dojo", "PUFFERFISH", "Trains in the dojo's lava."),
    ("GOLDEN_FISH", "goldenfish", "Golden Fish", "LEGENDARY", "golden", "GOLD_BLOCK", "Appears after you have fished the lava for a long time."),
]
TIERS = ["BRONZE", "SILVER", "GOLD", "DIAMOND"]
TIER_COLOR = {"BRONZE": "&6", "SILVER": "&7", "GOLD": "&e", "DIAMOND": "&b"}
TIER_MAT = {"GOLD_BLOCK": "GOLD_INGOT"}
for stem, key, ours, rar, cond, mat, desc in TROPHY_META:
    t = TF[key]
    ref = strip(t["displayName"])
    rename("trophy fish", stem, ref, ours)
    rate = t.get("rate")
    if stem == "VOLCANIC_STONEFISH":
        rate = 4   # research 07 table (skyhanni says 3)
    TROPHY[stem] = {"name": ours, "rarity": rar, "rate": rate, "cond": cond, "fillet": t["fillet"], "desc": desc}
    for tier in TIERS:
        iid = "%s_%s" % (stem, tier)
        nd = neu(iid)
        base_item(iid, "%s %s" % (ours, tier.capitalize()), TIER_MAT.get(mat, mat), rar, "MATERIAL",
                  lore=["&7" + desc, "", TIER_COLOR[tier] + "&l" + tier + " &7trophy", "",
                        "&7Show it to the &6Trophy Keeper &7on the", "&7Crimson Isle, or fillet it for &6Magmafish&7."],
                  tags=["trophy_fish", "soulbound", "museum"] + (["bait"] if stem in ("OBFUSCATED_FISH_1", "OBFUSCATED_FISH_2") else []),
                  extra={"trophyFish": stem, "trophyTier": tier}, glow=(tier == "DIAMOND"))
        if stem in ("OBFUSCATED_FISH_1", "OBFUSCATED_FISH_2"):
            BAITS.setdefault(iid, {"trophyBait": stem, "lavaOnly": True})

# ---- fishing-owned drops and misc items ----
OWN_EXTRA = OrderedDict([
    # id: (our name, reference name, material, rarity, lore)
    ("MAGMA_FISH_DIAMOND", ("Diamond Magmafish", "Diamond Magmafish", "PRISMARINE_CRYSTALS", "MYTHIC", ["&7Sixteen Gold Magmafish, pressed hard."])),
    ("ALLIGATOR_SKIN", ("Alligator Hide", "Alligator Skin", "LEATHER", "RARE", ["&7Tough, scaly, and still damp."])),
    ("WEREWOLF_SKIN", ("Werewolf Pelt", "Werewolf Skin", "LEATHER", "RARE", ["&7It still smells of moonlight."])),
    ("BOBBIN_SCRIPTURES", ("Chorus Scriptures", "Bobbin' Scriptures", "BOOK", "RARE", ["&7Old fishing hymns. Said to teach the", "&7&dBobber's Chorus &7enchantment."])),
    ("CHAIN_END_TIMES", ("Chain of the Last Days", "Chain of the End Times", "IRON_CHAIN", "EPIC", ["&7Heavy, hot, and humming."])),
    ("BRIMSTONE_HANDLE", ("Brimstone Handle", "Brimstone Handle", "BLAZE_ROD", "LEGENDARY", ["&7The grip of something far too large."])),
    ("WALNUT", ("Walnut", "Walnut", "COCOA_BEANS", "UNCOMMON", ["&7Cracked open by a very determined", "&7Nutcracker."])),
    ("MOUND_OF_SEAGRASS", ("Mound of Seagrass", "Mound of Seagrass", "SEAGRASS", "RARE", ["&7Pulled from the loch bed."])),
    ("VIBRANT_CORAL", ("Vibrant Coral", "Vibrant Coral", "FIRE_CORAL", "EPIC", ["&7Glows even out of the water."])),
    ("CARNIVAL_TICKET", ("Carnival Ticket", "Carnival Ticket", "NAME_TAG", "RARE", ["&7Good for one ride, someday."])),
    ("DYE_AQUAMARINE", ("Aquamarine Dye", "Aquamarine Dye", "LIGHT_BLUE_DYE", "EPIC", ["&7An extremely rare dye, fished from", "&7the deepest water."])),
    ("DYE_CARMINE", ("Carmine Dye", "Carmine Dye", "RED_DYE", "EPIC", ["&7An extremely rare dye, fished from", "&7the hottest lava."])),
    ("DYE_ICEBERG", ("Iceberg Dye", "Iceberg Dye", "LIGHT_BLUE_DYE", "EPIC", ["&7An extremely rare dye, chipped off", "&7a frozen pond."])),
    ("DYE_MIDNIGHT", ("Midnight Dye", "Midnight Dye", "BLACK_DYE", "EPIC", ["&7An extremely rare dye with a", "&7spooky shimmer."])),
    ("DYE_BONE", ("Bone Dye", "Bone Dye", "WHITE_DYE", "EPIC", ["&7An extremely rare dye, rattled loose", "&7from a Sea Archer."])),
    ("HOTSPOT_RADAR", ("Hotspot Radar", "Hotspot Radar", "COMPASS", "RARE", ["&7Right-click to point at the nearest", "&dFishing Hotspot &7on this island."])),
    ("BAYOU_WATER_ORB", ("Bayou Water Orb", "Bayou Water Orb", "HEART_OF_THE_SEA", "LEGENDARY", ["&7Swamp water, perfectly round.", "&7The Scrapper trades for these."])),
    ("HOTSPOT_WATER_ORB", ("Hotspot Water Orb", "Hotspot Water Orb", "HEART_OF_THE_SEA", "LEGENDARY", ["&7Still warm from the hotspot."])),
    ("SHARK_WATER_ORB", ("Shark Water Orb", "Shark Water Orb", "HEART_OF_THE_SEA", "LEGENDARY", ["&7Smells faintly of blood."])),
    # The plain orb is the core of every themed orb (their recipes all need one); the themed orbs feed
    # the weapons (Hotspot/Lava) and pets (Lotus) modules' recipes, so they must exist for those to register.
    ("WATER_ORB", ("Water Orb", "Water Orb", "HEART_OF_THE_SEA", "EPIC", ["&7Every kind of catch, pressed into one", "&7smooth, heavy sphere. The heart of", "&7every high-end fishing craft."])),
    ("LAVA_WATER_ORB", ("Lava Water Orb", "Lava Water Orb", "HEART_OF_THE_SEA", "LEGENDARY", ["&7Hot enough to blister your palm."])),
    ("SPOOKY_WATER_ORB", ("Spooky Water Orb", "Spooky Water Orb", "HEART_OF_THE_SEA", "LEGENDARY", ["&7It whispers when nobody is looking."])),
    ("WINTER_WATER_ORB", ("Winter Water Orb", "Winter Water Orb", "HEART_OF_THE_SEA", "LEGENDARY", ["&7Frost never melts off its surface."])),
    ("LOTUS_WATER_ORB", ("Lotus Water Orb", "Lotus Water Orb", "HEART_OF_THE_SEA", "LEGENDARY", ["&7Smells of rust and lotus petals."])),
    ("CHUM", ("Chum", "Chum", "ROTTEN_FLESH", "UNCOMMON", ["&7Dropped by sea creatures slain near a", "&7placed Chum Bucket.", "", "&7Deposit it in any Chum Bucket to earn", "&6+20 Coins &7and &3+10 Fishing XP&7!"])),
    # a deployable (ChumBuckets.java): placed by water it adds Chum to slain sea creatures, Fishing Speed to
    # nearby anglers and calls the Mooshroom Diver for its owner (reference lore, NEU)
    ("EMPTY_CHUMCAP_BUCKET", ("Empty Chumcap Bucket", "Empty Chumcap Bucket", "PLAYER_HEAD", "UNCOMMON", ["&7Place it down next to water.", "", "&7Sea creatures slain nearby drop", "&a+1 Chum&7.", "&8Up to +4 total from buckets!", "", "&7You gain a good chance to fish up", "&7a &cMooshroom Diver&7.", "&7You and other nearby anglers gain", "&b+4 ☂ Fishing Speed&7.", "&8Up to +16 ☂ from buckets!"])),
    # wave 5: Torrent Canyon drops nobody registered (materials has Sea Brine / Sublime Silk / Reinforced Netting)
    ("ISOPOD_HUSK", ("Isopod Husk", "Isopod Husk", "RABBIT_HIDE", "EPIC", ["&8Consumable", "", "&7Eat it to permanently gain &3+1 Respiration&7:", "&7you hold your breath longer underwater.", "", "&7Only &a5 &7can be eaten before the scratching", "&7at the back of your throat wins.", "", "&eRight-click to consume!"])),
    ("WATER_HYACINTH", ("Water Hyacinth", "Water Hyacinth", "BLUE_ORCHID", "LEGENDARY", ["&7A Torrid is born when a water hyacinth", "&7blooms for the very first time."])),
    ("DISTANT_ECHO", ("Distant Echo", "Distant Echo", "ECHO_SHARD", "LEGENDARY", ["&7A Silkdrift passes so fast it is mostly", "&7heard, never seen. This is what it left."])),
    # the Thunder in a Bottle line (reference: "charge it up"): charged by slaying lava sea creatures with the
    # empty bottle in your inventory (ThunderBottles.java); the Pulse Ring adds 1% to every charge
    ("THUNDER_IN_A_BOTTLE_EMPTY", ("Empty Thunder Bottle", "Empty Thunder Bottle", "PLAYER_HEAD", "EPIC", ["&7This bottle is empty. Slay lava sea", "&7creatures with it in your inventory", "&7to charge it up."])),
    ("THUNDER_IN_A_BOTTLE", ("Thunder in a Bottle", "Thunder in a Bottle", "PLAYER_HEAD", "EPIC", ["&7What happens if you open it? The", "&7answer may shock you."])),
    # audit 2026-10-06 F10: the Junk Sinker's 1-in-6 OUTSTANDING junk. An "aging" book: kept in the inventory it
    # gains +0.1 Fishing Wisdom per hour online up to +30 (300 h), then evolves; consumed it is that much Fishing
    # Wisdom for 60 minutes (MobyDuck.java; numbers are the reference's, dials fishing.moby-duck.*).
    ("MOBY_DUCK", ("The Drowned Mallard", "Moby-Duck", "BOOK", "RARE", ["&7Keep it in your inventory: it gains", "&3+0.1 ☯ Fishing Wisdom &7for every hour you", "&7are online, up to &3+30☯&7, then it evolves.", "", "&7Right-click to read it: the bonus is", "&7yours for &a60 minutes&7.", "", "&8A waterlogged epic about a sailor and", "&8his best friend, a very large duck."])),
    ("MOBY_DUCK_COLLECTOR_EDITION", ("The Drowned Mallard: Collector's Edition", "Moby-Duck: Collector's Edition", "BOOK", "EPIC", ["&7Right-click to read it: &3+30☯ Fishing", "&3Wisdom &7for &a60 minutes&7.", "", "&6Bookworm", "&7The first one you ever read also grants", "&3+1☯ Fishing Wisdom &7for good.", "", "&8Somebody kept this copy dry."])),
])
for iid, (ours, ref, mat, rar, lore) in OWN_EXTRA.items():
    if iid in OTHER and iid not in MATERIALS and iid not in FALLBACK:
        continue    # another specialist owns it
    rename("item", iid, ref, ours)
    nd = neu(iid)
    sk = skull_of(nd)
    base_item(iid, ours, "PLAYER_HEAD" if (sk and mat in ("PAPER",)) else mat, rar, "MATERIAL", lore=lore,
              tags=["museum"] if iid.startswith("DYE_") else ["bazaar"], skull=sk if mat == "PLAYER_HEAD" else None,
              sell=(ITEMS.get(iid) or {}).get("npc_sell_price") or 0)

# the reference's "Requires Fishing Skill 15" on the Chumcap Bucket (checked when it is placed)
if "EMPTY_CHUMCAP_BUCKET" in items_out:
    items_out["EMPTY_CHUMCAP_BUCKET"]["requirements"] = ["skill:fishing:15"]

# a charge lives on the item (extra.thunder_charge / extra.moby_wisdom): these must not stack
for _iid in ("THUNDER_IN_A_BOTTLE_EMPTY", "MOBY_DUCK", "MOBY_DUCK_COLLECTOR_EDITION"):
    if _iid in items_out:
        items_out[_iid]["maxStack"] = 1

if "HOTSPOT_RADAR" in items_out:
    items_out["HOTSPOT_RADAR"]["abilities"] = [{"id": "fishing_hotspot_radar", "name": "Ping", "trigger": "RIGHT_CLICK",
                                                "mana": 0, "cooldown": 2,
                                                "lore": ["&7Points you at the nearest &dFishing", "&dHotspot &7on this island."]}]
    items_out["HOTSPOT_RADAR"]["lore"] = ["&7A battered radar tuned to warm water."]
    items_out["HOTSPOT_RADAR"]["category"] = "MATERIAL"
    items_out["HOTSPOT_RADAR"]["maxStack"] = 1

# sea creature drops listed by materials (fishing family) are fishing-owned: copy their def so the
# names stay identical, mark the source creature.
MAT_RECIPES = set()
try:
    MAT_RECIPES = {r.get("result") for r in L(os.path.join(RES, "materials", "recipes.json"))}
except Exception:
    pass
SOURCE_OF = {}
for m in mobs:
    for d in m["drops"]:
        SOURCE_OF.setdefault(d["item"], []).append(m["name"])
for iid, md in MATERIALS.items():
    fam = (md.get("extra") or {}).get("family")
    if fam != "fishing" and iid not in ("RUSTY_COIN", "BUSTED_BELT_BUCKLE", "OLD_LEATHER_BOOT"):
        continue
    if iid in items_out:
        continue
    if iid in MAT_RECIPES:
        continue   # a compression: materials owns it and its recipe
    if iid in ("RAW_FISH", "RAW_FISH:1", "RAW_FISH:2", "RAW_FISH:3", "INK_SACK", "WATER_LILY", "SPONGE", "CLAY_BALL",
               "CLAY", "PRISMARINE", "PRISMARINE:1", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "BLAZE_POWDER",
               "GRAND_EXP_BOTTLE") or iid.startswith("ENCHANTED_"):
        continue   # generic materials / compressions stay with materials
    d = json.loads(json.dumps(md))
    src = SOURCE_OF.get(iid)
    if src:
        d.setdefault("lore", [])
        d["lore"] = list(d["lore"]) + ["", "&8Dropped by: " + ", ".join(sorted(set(src))[:3])]
    d.setdefault("extra", {})["family"] = "fishing"
    items_out[iid] = d

# ---------------------------------------------------------------------------------------------
# Recipes for items we own (NEU grids), with collection unlocks from collections.json.
# ---------------------------------------------------------------------------------------------
CELLS = ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]


def conv_cell(v):
    v = (v or "").strip()
    if not v:
        return ""
    cnt = 1
    if ":" in v:
        a, b = v.rsplit(":", 1)
        try:
            cnt = int(float(b))
            v = a
        except ValueError:
            pass
    m = re.match(r"^([A-Z_]+)-(\d+)$", v)
    if m:
        v = m.group(1) + ":" + m.group(2)
    if cnt == 1:
        return v                      # a bare known id means x1 (core RecipeCells rule 1)
    cell = "%s:%d" % (v, cnt)
    assert cell not in KNOWN_COLON_IDS, "ambiguous recipe cell " + cell
    return cell


KNOWN_COLON_IDS = {i for i in list(OTHER) + list(items_out) if ":" in i}
COMPRESSIONS = set()
try:
    for r in L(os.path.join(RES, "materials", "recipes.json")):
        COMPRESSIONS.add(r.get("result"))
except Exception:
    pass
recipes = []
seen = set()
for iid in list(items_out.keys()):
    nd = neu(iid)
    if not nd:
        continue
    rs = []
    if isinstance(nd.get("recipe"), dict):
        rs.append(nd["recipe"])
    for r in nd.get("recipes", []) or []:
        if r.get("type") == "crafting":
            rs.append(r)
        elif r.get("type") == "forge":
            rs.append(r)
    for n, r in enumerate(rs):
        name = strip(nd.get("displayname"))
        unlock = UNLOCK_BY_NAME.get(name)
        if r.get("type") == "forge":
            rec = OrderedDict(id="fishing:%s#%d" % (iid, n), type="FORGE", result=iid, amount=int(r.get("count", 1)),
                              inputs=[conv_cell(x) for x in r.get("inputs", [])], durationSeconds=int(r.get("duration", 0)),
                              station="Forge")
            if iid == "POLISHED_TOPAZ_ROD":
                rec["unlock"] = "hotm:5"
        else:
            grid = [conv_cell(r.get(k, "")) for k in CELLS]
            if not any(grid):
                continue
            key = (iid, tuple(grid))
            if key in seen:
                continue
            seen.add(key)
            rec = OrderedDict(id="fishing:%s#%d" % (iid, n), type="CRAFTING", result=iid, amount=int(r.get("count", 1)),
                              grid=grid, station="Crafting Table")
            if unlock:
                rec["unlock"] = unlock
        recipes.append(rec)
# The plain rod: the vanilla shape (sticks + string).
if not any(r["result"] == "FISHING_ROD" for r in recipes):
    recipes.append(OrderedDict(id="fishing:FISHING_ROD#v", type="CRAFTING", result="FISHING_ROD", amount=1,
                               grid=["", "", "STICK:1", "", "STICK:1", "STRING:1", "STICK:1", "", "STRING:1"],
                               station="Crafting Table"))

# ---------------------------------------------------------------------------------------------
# Catches that are not creatures.
# ---------------------------------------------------------------------------------------------
FISH = [  # normal water catches, vanilla odds
    {"item": "RAW_FISH", "weight": 60, "xp": 5}, {"item": "RAW_FISH:1", "weight": 25, "xp": 8},
    {"item": "RAW_FISH:3", "weight": 13, "xp": 10}, {"item": "RAW_FISH:2", "weight": 2, "xp": 20}]   # xp TUNE
LAVA_FISH = [{"item": "MAGMA_FISH", "weight": 1, "xp": 15}]   # TUNE


def t(item, a=1, b=None, w=10):
    return {"item": item, "min": a, "max": b if b is not None else a, "weight": w}


TREASURE = {
    "weights": {"good": 85, "great": 13, "outstanding": 2},     # TUNE: research gives no exact split
    "xp": {"good": 25, "great": 75, "outstanding": 250},          # TUNE
    "water": {
        "good": [t("COINS", 100, 400, 20), t("PRISMARINE_SHARD", 2, 4), t("PRISMARINE_CRYSTALS", 2, 4), t("SPONGE", 1, 2),
                 t("INK_SACK", 4, 8), t("WATER_LILY", 2, 4), t("CLAY_BALL", 4, 8), t("RAW_FISH", 4, 8), t("RAW_FISH:1", 2, 4),
                 t("RAW_FISH:3", 2, 3), t("RAW_FISH:2", 1, 1, 5), t("EXP_BOTTLE", 2, 4), t("MINNOW_BAIT", 4, 4),
                 t("FISH_BAIT", 2, 2), t("LIGHT_BAIT", 2, 2, 5), t("DARK_BAIT", 2, 2, 5),
                 t("ENCHANTMENT_LURE_1", w=4), t("ENCHANTMENT_ANGLER_1", w=4), t("ENCHANTMENT_CASTER_1", w=4),
                 t("ENCHANTMENT_LUCK_OF_THE_SEA_1", w=4), t("ENCHANTMENT_FRAIL_1", w=4), t("ENCHANTMENT_MAGNET_1", w=4),
                 t("ENCHANTMENT_SPIKED_HOOK_1", w=4)],
        "great": [t("COINS", 1000, 3000, 20), t("ENCHANTED_RAW_FISH", 1), t("ENCHANTED_PRISMARINE_SHARD", 1),
                  t("ENCHANTED_PRISMARINE_CRYSTALS", 1), t("ENCHANTED_SPONGE", 1, 1, 6), t("ENCHANTED_INK_SACK", 1),
                  t("ENCHANTED_CLAY_BALL", 1), t("GRAND_EXP_BOTTLE", 1, 2), t("WHALE_BAIT", 1, 2, 6), t("SPIKED_BAIT", 4, 4),
                  t("BLESSED_BAIT", 2, 2, 6), t("ENCHANTMENT_LURE_3", w=3), t("ENCHANTMENT_ANGLER_3", w=3),
                  t("ENCHANTMENT_CASTER_3", w=3), t("ENCHANTMENT_LUCK_OF_THE_SEA_3", w=3), t("ENCHANTMENT_FRAIL_3", w=3),
                  t("ENCHANTMENT_MAGNET_3", w=3), t("ENCHANTMENT_SPIKED_HOOK_3", w=3),
                  t("PET_ITEM_FISHING_SKILL_BOOST_COMMON", w=2)],
        "outstanding": [t("COINS", 8000, 20000, 20), t("ENCHANTED_WET_SPONGE", 1, 1, 4), t("TITANIC_EXP_BOTTLE", 1, 1, 6),
                        t("PET_ITEM_LUCKY_CLOVER_DROP", 1, 1, 3), t("PET_ITEM_FISHING_SKILL_BOOST_RARE", 1, 1, 3),
                        t("ENCHANTMENT_LURE_5", w=3), t("ENCHANTMENT_ANGLER_5", w=3), t("ENCHANTMENT_CASTER_5", w=3),
                        t("ENCHANTMENT_LUCK_OF_THE_SEA_5", w=3), t("ENCHANTMENT_FRAIL_5", w=3), t("CHALLENGE_ROD", w=2),
                        t("TREASURE_BAIT", 4, 4, 5), t("GOLDEN_BAIT", 2, 2, 5), t("FISH_AFFINITY_TALISMAN", w=2),
                        t("HOTSPOT_RADAR", w=2)]},
    "islands": {
        "jerrys_workshop": {"great": [t("ICE_BAIT", 4, 4, 15), t("ICE", 8, 16, 15)],
                            "outstanding": [t("ICE_ROD", w=12), t("FROZEN_BAIT", 2, 2, 12)]},
        "backwater_bayou": {"good": [t("RUSTY_COIN", 1, 2, 12)], "great": [t("RUSTY_COIN", 2, 4, 10)],
                            "outstanding": [t("BAYOU_WATER_ORB", w=4)]},
        "crystal_hollows": {"great": [t("ROUGH_SAPPHIRE_GEM", 16, 32, 10), t("WORM_BAIT", 4, 4, 8)],
                            "outstanding": [t("FLAWED_SAPPHIRE_GEM", 1, 3, 8)]},
        # wave 5, TUNE (the reference lists no tables): each Galatea island's own currency/material
        "lotus_atoll": {"good": [t("LOTUS", 1, 2, 12)], "great": [t("LOTUS", 3, 6, 10), t("FROGCOIN", 1, 1, 2)],
                        "outstanding": [t("LOTUS_SILVER", 1, 1, 6), t("LOTUS_WATER_ORB", w=2)]},
        "torrhus_canyon": {"good": [t("SEA_LUMIES", 2, 4, 12)], "great": [t("ENCHANTED_SEA_LUMIES", 1, 1, 8),
                                                                         t("SEA_BRINE", 1, 1, 3)],
                           "outstanding": [t("SUBLIME_SILK", 1, 1, 3)]},
        "galatea": {"good": [t("SEA_LUMIES", 1, 3, 12), t("MANGROVE_LOG", 4, 8, 8)],
                    "great": [t("ENCHANTED_SEA_LUMIES", 1, 1, 8)], "outstanding": [t("MOUND_OF_SEAGRASS", 1, 1, 3)]},
    },
    # Junk Sinker in the bayou: every treasure catch becomes ONE piece of junk (these feed the Scrapper's
    # shop). Research (SkyblockMoney/work/fishing.md section 2, wiki Treasure/Loot/Junk): its own tier
    # split Good 89 / Great 10 / Outstanding 1 (junkWeights below, not the treasure split); Good is a
    # Rusty Coin, Great a Busted Belt Buckle, Outstanding an Old Leather Boot 5/6 or a Moby-Duck 1/6.
    "junkWeights": {"good": 89, "great": 10, "outstanding": 1},
    "junk": {"good": [t("RUSTY_COIN", 1, 1, 1)],
             "great": [t("BUSTED_BELT_BUCKLE", 1, 1, 1)],
             "outstanding": [t("OLD_LEATHER_BOOT", 1, 1, 5), t("MOBY_DUCK", 1, 1, 1)]},
}

TROPHY_XP = {"COMMON": 100, "UNCOMMON": 200, "RARE": 400, "EPIC": 800, "LEGENDARY": 2000}   # TUNE
TROPHY_TIER = {"chances": {"DIAMOND": 0.002, "GOLD": 0.02, "SILVER": 0.25}, "pity": {"GOLD": 100, "DIAMOND": 600},
               "xpMult": {"BRONZE": 1.0, "SILVER": 1.5, "GOLD": 2.0, "DIAMOND": 3.0}}       # xpMult TUNE
TITLES = [  # requirement: every trophy fish at this tier or better
    {"level": 1, "name": "Novice Trophy Fisher", "tier": "BRONZE"},
    {"level": 2, "name": "Adept Trophy Fisher", "tier": "SILVER"},
    {"level": 3, "name": "Expert Trophy Fisher", "tier": "GOLD"},
    {"level": 4, "name": "Master Trophy Fisher", "tier": "DIAMOND"},
]
# Trophy Keeper shop (reference prices; gated by the title the reference gates them by).
KEEPER_SHOP = []
for title_lvl, tier, price in ((0, "BRONZE", 100000), (1, "BRONZE", 100000), (2, "SILVER", 500000), (3, "GOLD", 1000000),
                               (4, "DIAMOND", 2500000)):
    pass
for tier_name, lvl, price in (("BRONZE", 1, 100000), ("SILVER", 2, 500000), ("GOLD", 3, 1000000), ("DIAMOND", 4, 2500000)):
    for piece in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"):
        KEEPER_SHOP.append({"item": "%s_HUNTER_%s" % (tier_name, piece), "coins": price, "title": lvl})
    KEEPER_SHOP.append({"item": "ODGERS_%s_TOOTH" % tier_name, "coins": price, "title": lvl})
KEEPER_SHOP.append({"item": "STARTER_LAVA_ROD", "coins": 10000, "title": 0})
KEEPER_SHOP.append({"item": "BRONZE_TROPHY_FISHING_SACK", "coins": 1000000, "title": 1})
KEEPER_SHOP.append({"item": "SILVER_TROPHY_FISHING_SACK", "coins": 5000000, "title": 2})
# storage Request 4 (wave 5): the Gold/Diamond sacks had no source. Same ladder, prices ours (TUNE: no NEU page).
KEEPER_SHOP.append({"item": "GOLD_TROPHY_FISHING_SACK", "coins": 15000000, "title": 3})
KEEPER_SHOP.append({"item": "DIAMOND_TROPHY_FISHING_SACK", "coins": 40000000, "title": 4})

# Tackle-smith / bait shop stock (coins; TUNE where the reference has no coin price).
BAIT_SHOP = [
    {"item": "MINNOW_BAIT", "coins": 15}, {"item": "FISH_BAIT", "coins": 40}, {"item": "LIGHT_BAIT", "coins": 40},
    {"item": "DARK_BAIT", "coins": 40}, {"item": "SPIKED_BAIT", "coins": 80}, {"item": "CARROT_BAIT", "coins": 60},
    {"item": "BLESSED_BAIT", "coins": 150}, {"item": "SHARK_BAIT", "coins": 120}, {"item": "HOT_BAIT", "coins": 120},
    {"item": "WHALE_BAIT", "coins": 400}, {"item": "HOTSPOT_BAIT", "coins": 5000},
    {"item": "FISHING_ROD", "coins": 50}, {"item": "PRISMARINE_ROD", "coins": 250}, {"item": "SPONGE_ROD", "coins": 600},
    {"item": "HOTSPOT_RADAR", "coins": 25000}, {"item": "EMPTY_CHUMCAP_BUCKET", "coins": 20000},
]
PART_SHOP = [
    {"item": "COMMON_HOOK", "coins": 25000}, {"item": "PRISMARINE_SINKER", "coins": 10000},
    {"item": "SPONGE_SINKER", "coins": 12000}, {"item": "ICY_SINKER", "coins": 10000}, {"item": "CHUM_SINKER", "coins": 10000},
    {"item": "FESTIVE_SINKER", "coins": 15000}, {"item": "HOTSPOT_SINKER", "coins": 750000},
    {"item": "HOTSPOT_HOOK", "coins": 1000000}, {"item": "TITAN_LINE", "coins": 5000000},
]   # TUNE: coin prices; the reference crafts most parts

# Jonesy's Bayou chapters (audit 2026-10-06 F11; wiki /w/Jonesy, read 2026-10-06). Our Bayou guide (Warden Fen)
# gives them. Seven chapters; every task in a chapter pays that chapter's Fishing XP (100 .. 10,000); the chapter
# reward is junk + Treasure Bait + SkyBlock XP, and Chapter VII's is the Pearl of the Swamp (+1 Treasure Chance
# for good). Task kinds (Jonesy.java): talk (he explains it), collection (WATER_LILY tier), fished (an item ever
# fished up), caught (a sea creature ever hooked), hotspot (any hotspot creature hooked), own (carried / in the
# accessory bag / fishing bag), part (a rod part on any rod you carry; ANY = any part), pet (Hermit Crab active at
# that rarity or better). LEFT OUT: the three Bronze Ship parts (V Helm + Engine, VI Hull) - there is no ship here.
def task(kind, arg, label):
    return {"kind": kind, "arg": arg, "label": label}


JONESY = [
    {"name": "Chapter I", "xp": 100, "sbXp": 5, "rewards": [t("TREASURE_BAIT", 4, 4, 1)], "tasks": [
        task("talk", "junk", "Learn about Junk"), task("part", "ANY", "Apply a Rod Part"),
        task("talk", "hotspots", "Learn about Hotspots")]},
    {"name": "Chapter II", "xp": 250, "sbXp": 10, "rewards": [t("RUSTY_COIN", 1, 1, 1), t("TREASURE_BAIT", 8, 8, 1)], "tasks": [
        task("collection", "WATER_LILY:1", "Reach Lily Pad Collection I"), task("fished", "RUSTY_COIN", "Catch a Rusty Coin"),
        task("own", "TREASURE_BAIT", "Get some Treasure Bait"), task("caught", "sc_trash_gobbler", "Catch a Trash Gobbler"),
        task("caught", "sc_dumpster_diver", "Catch a Dumpster Diver")]},
    {"name": "Chapter III", "xp": 500, "sbXp": 15, "rewards": [t("RUSTY_COIN", 4, 4, 1), t("TREASURE_BAIT", 16, 16, 1)], "tasks": [
        task("collection", "WATER_LILY:3", "Reach Lily Pad Collection III"),
        task("own", "JUNK_TALISMAN|JUNK_RING|JUNK_ARTIFACT", "Own a Junk Talisman"),
        task("own", "CHALLENGE_ROD", "Own a Challenging Rod"),
        task("own", "BACKWATER_HELMET|BACKWATER_CHESTPLATE|BACKWATER_LEGGINGS|BACKWATER_BOOTS", "Own a piece of Backwater Armor"),
        task("pet", "HERMIT_CRAB:COMMON", "Have a Hermit Crab pet out"), task("hotspot", "", "Catch a Hotspot Sea Creature")]},
    {"name": "Chapter IV", "xp": 1000, "sbXp": 20, "rewards": [t("BUSTED_BELT_BUCKLE", 1, 1, 1), t("RUSTY_COIN", 8, 8, 1),
                                                            t("TREASURE_BAIT", 32, 32, 1)], "tasks": [
        task("collection", "WATER_LILY:5", "Reach Lily Pad Collection V"), task("fished", "BUSTED_BELT_BUCKLE", "Catch a Busted Belt Buckle"),
        task("caught", "sc_banshee", "Catch a Banshee"), task("pet", "HERMIT_CRAB:UNCOMMON", "Have an Uncommon Hermit Crab out"),
        task("part", "STINGY_SINKER", "Apply a Stingy Sinker"), task("own", "JUNK_RING|JUNK_ARTIFACT", "Own a Junk Ring")]},
    {"name": "Chapter V", "xp": 2500, "sbXp": 25, "rewards": [t("BUSTED_BELT_BUCKLE", 2, 2, 1), t("RUSTY_COIN", 16, 16, 1),
                                                           t("TREASURE_BAIT", 64, 64, 1)], "tasks": [
        task("collection", "WATER_LILY:7", "Reach Lily Pad Collection VII"), task("caught", "sc_bayou_sludge", "Catch a Bayou Sludge"),
        task("pet", "HERMIT_CRAB:RARE", "Have a Rare Hermit Crab out"), task("part", "SPEEDY_LINE", "Apply a Speedy Line")]},
    {"name": "Chapter VI", "xp": 5000, "sbXp": 30, "rewards": [t("BUSTED_BELT_BUCKLE", 4, 4, 1), t("RUSTY_COIN", 32, 32, 1),
                                                            t("TREASURE_BAIT", 128, 128, 1)], "tasks": [
        task("collection", "WATER_LILY:8", "Reach Lily Pad Collection VIII"), task("caught", "sc_alligator", "Catch an Alligator"),
        task("fished", "OLD_LEATHER_BOOT", "Catch an Old Leather Boot"), task("pet", "HERMIT_CRAB:EPIC", "Have an Epic Hermit Crab out"),
        task("own", "JUNK_ARTIFACT", "Own a Junk Artifact")]},
    {"name": "Chapter VII", "xp": 10000, "sbXp": 35, "rewards": [], "pearl": True, "tasks": [
        task("collection", "WATER_LILY:9", "Reach Lily Pad Collection IX"), task("caught", "sc_titanoboa", "Catch a Titanoboa"),
        task("own", "BAYOU_WATER_ORB", "Craft a Bayou Water Orb"), task("part", "TREASURE_HOOK", "Apply a Treasure Hook"),
        task("own", "GOLD_BOTTLE_CAP", "Own a Gold Bottle Cap"), task("pet", "HERMIT_CRAB:LEGENDARY", "Have a Legendary Hermit Crab out")]},
]

HOTSPOTS = {"hub": 3, "spiders_den": 1, "park": 1, "backwater_bayou": 2, "jerrys_workshop": 2, "crimson_isle": 3,
            "lotus_atoll": 2, "torrhus_canyon": 2}            # research 03: 2 each on the Atoll and the Canyon
FS_CAPS = {"backwater_bayou": 200, "crimson_isle": 350, "lotus_atoll": 250}   # research 01: Atoll 250

fishing = OrderedDict()
fishing["pools"] = POOLS
fishing["baits"] = BAITS
fishing["parts"] = PARTS
fishing["trophy"] = {"fish": TROPHY, "tiers": TROPHY_TIER, "xp": TROPHY_XP, "titles": TITLES}
fishing["fish"] = FISH
fishing["lavaFish"] = LAVA_FISH
fishing["treasure"] = TREASURE
fishing["keeperShop"] = KEEPER_SHOP
fishing["baitShop"] = BAIT_SHOP
fishing["partShop"] = PART_SHOP
fishing["hotspots"] = HOTSPOTS
fishing["fsCaps"] = FS_CAPS
fishing["rods"] = {rid: {"lava": m[1]} for rid, m in ROD_META.items()}
fishing["jonesy"] = JONESY

os.makedirs(OUT, exist_ok=True)


def dump(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")


# ---- NPCs (skins from the community repo's NPC pages, as the shops module does) ----
NPCS = [
    # id, name, title, island, anchor, NEU page for the skin, hand item
    ("fishing_guide", "&fFisher Gideon", "&b&lFISHING", "hub", "npc.fishing_guide", "FISHERMAN_GERALD_NPC", "FISHING_ROD"),
    ("fishing_roddy", "&fTackle-smith Rudd", "&9&lROD PARTS", "backwater_bayou", "npc.roddy", "RODDY_NPC", "TRIPWIRE_HOOK"),
    ("fishing_bait_shop", "&fBait-seller Mae", "&a&lBAITS & RODS", "backwater_bayou", "npc.bait_shop", "FISHERWOMAN_ENID_NPC", "COD"),
    ("fishing_warden", "&fWarden Fen", "&e&lBAYOU GUIDE", "backwater_bayou", "npc.warden", "CAPTAIN_BAHA_NPC", "LANTERN"),
    ("fishing_trophy_keeper", "&fTrophy Keeper Ogden", "&6&lTROPHY FISH", "crimson_isle", "npc.trophy_keeper", "ODGER_NPC", "TROPICAL_FISH"),
]
npcs_out = []
for nid, name, title, island, anchor, page, hand in NPCS:
    npcs_out.append(OrderedDict(id=nid, name=name, title=title, island=island, anchor=anchor,
                                skin=skull_of(neu(page)), hand=hand))

dump("npcs.json", npcs_out)
dump("items.json", list(items_out.values()))
dump("mobs.json", mobs)
dump("recipes.json", recipes)
dump("fishing.json", fishing)

# ---- names doc ----
with open(NAMES, "w", encoding="utf-8", newline="\n") as f:
    f.write("# fishing - renames\n\n")
    f.write("Generated by `tools/gen/fishing/gen_fishing.py` (edit the tables there and re-run). Internal ids keep the\n")
    f.write("reference id; only display names change. Generic names (Squid, Catfish, Blue Shark, Magma Slug, Fishing Rod,\n")
    f.write("every bait and most rod parts, Blobfish...) are kept. Sea creature family ids match the progression module's\n")
    f.write("bestiary renames (Mooshroom Diver, Ore Grubber, Inkspawn, Tiki Totem, Lord Gnashmaw, Magma Cow, Phlegmburst,\n")
    f.write("Frozen Wanderer, Gift Goblin, Frost Drake).\n\n")
    f.write("| kind | id | reference name | our name |\n|---|---|---|---|\n")
    for k, i, r, o in RENAMES:
        f.write("| %s | %s | %s | %s |\n" % (k, i, r, o))
    f.write("| npc | fishing_guide | (hub fishing guide) | Fisher Gideon |\n")
    f.write("| npc | fishing_roddy | Roddy | Tackle-smith Rudd |\n")
    f.write("| npc | fishing_bait_shop | (bayou merchant) | Bait-seller Mae |\n")
    f.write("| npc | fishing_warden | Jonesy (the Bayou guide who gives the chapters) | Warden Fen |\n")
    f.write("| perk | jonesy pearl | Pearl of the Swamp | Bayou Pearl |\n")
    f.write("| npc | fishing_trophy_keeper | Odger | Trophy Keeper Ogden (the shops module's name) |\n")
    f.write("| place | pools | Moonglade Marsh / Lotus Atoll / Torrhus Canyon | Moonmarsh (foraging's island) / Lotus Atoll / Torrent Springs (galatea's Torrent Canyon) |\n")
    f.write("| title | trophy titles | Novice/Adept/Expert/Master Trophy Fisher | (kept: generic) |\n")
    f.write("| mayor perk | marina.* | Marina | Coralie (mayors module) |\n")

print("items", len(items_out), "mobs", len(mobs), "recipes", len(recipes), "pools", len(POOLS),
      "baits", len(BAITS), "parts", len(PARTS), "trophy", len(TROPHY), "renames", len(RENAMES))
missing = sorted(i for i in DROP_IDS if i not in OTHER and i not in items_out)
print("drop ids not registered by any data file (vanilla, code-registered or pruned at runtime):", len(missing))
print("  " + " ".join(missing))
