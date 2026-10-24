"""Generate the Bazaar catalogue from the research data.

    py -3 tools/gen/bazaar/gen_bazaar.py

Reads  research/data/bazaar.json            (the 2,197-product snapshot: ids + quick_status prices/volumes)
       research/data/items.json             (names, NPC sell prices, categories)
       research/data/collections.json       (collection roots and their skill category)
       research/data/neu-repo/items/*.json  (crafting/forge recipes for the family pass, NPC shop prices)
       plugin/src/main/resources/data/shops/shops.json  (the shops module's NPC prices: ask ceilings; read only)
Writes plugin/src/main/resources/data/bazaar/products.json   (one row per product)
       plugin/src/main/resources/data/bazaar/groups.json     (categories -> ordered groups)
       tools/gen/bazaar/report.txt                           (what was placed where, price clamps)

The runtime only SHOWS products whose id exists in Sky.items(), so this file may list more than the game has.
Every price here is a starting point for the market makers; they drift from it at runtime.
"""
import json, math, os, re, sys, glob, collections

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "bazaar")

CATEGORIES = [
    # id, display, icon material, glass colour (menu separator)
    ("FARMING", "Farming", "GOLDEN_HOE", "YELLOW"),
    ("MINING", "Mining", "DIAMOND_PICKAXE", "BLUE"),
    ("COMBAT", "Combat", "IRON_SWORD", "RED"),
    ("WOODS_FISHES", "Woods & Fishes", "FISHING_ROD", "ORANGE"),
    ("ODDITIES", "Oddities", "ENCHANTING_TABLE", "PINK"),
    ("ENCHANTMENTS", "Enchantments", "ENCHANTED_BOOK", "PURPLE"),
]

SKILL_TO_CAT = {"FARMING": "FARMING", "MINING": "MINING", "COMBAT": "COMBAT",
                "FORAGING": "WOODS_FISHES", "FISHING": "WOODS_FISHES", "RIFT": "ODDITIES"}

# Collection roots merged into one group (first id is the group key).
MERGE = {
    "SEEDS": "WHEAT",
    "SAND:1": "SAND",
    "RAW_FISH:1": "RAW_FISH:1", "RAW_FISH:2": "RAW_FISH:2", "RAW_FISH:3": "RAW_FISH:3",
    "PRISMARINE_CRYSTALS": "PRISMARINE_SHARD",
    "SULPHUR": "SULPHUR",
}

# Raw items that are not themselves a collection key but obviously belong to one.
ROOT_ALIAS = {
    "BROWN_MUSHROOM": "MUSHROOM_COLLECTION", "RED_MUSHROOM": "MUSHROOM_COLLECTION",
    "HUGE_MUSHROOM_1": "MUSHROOM_COLLECTION", "HUGE_MUSHROOM_2": "MUSHROOM_COLLECTION",
    "RAW_BEEF": "LEATHER", "FLINT": "GRAVEL", "RABBIT_FOOT": "RABBIT", "RABBIT_HIDE": "RABBIT",
    "POISONOUS_POTATO": "POTATO_ITEM", "BAKED_POTATO": "POTATO_ITEM", "HAY_BLOCK": "WHEAT",
    "TIGHTLY_TIED_HAY_BALE": "WHEAT", "EGG": "RAW_CHICKEN", "SUPER_EGG": "RAW_CHICKEN",
    "OMEGA_EGG": "RAW_CHICKEN", "COOKED_FISH:1": "RAW_FISH:1", "RED_ROSE": "DOUBLE_PLANT",
    "YELLOW_FLOWER": "DOUBLE_PLANT", "TURBO_GOURD": "PUMPKIN", "POLISHED_PUMPKIN": "PUMPKIN",
    "LUSH_BERBERIS": "WILD_ROSE", "HELIANTHUS": "DOUBLE_PLANT", "CONDENSED_HELIANTHUS": "DOUBLE_PLANT",
    "BONE_MEAL": "BONE", "INK_SACK:15": "BONE", "FERMENTED_SPIDER_EYE": "SPIDER_EYE",
    "CARROT_ON_A_STICK": "CARROT_ITEM", "COOKIE": "WHEAT",
}

# items.json category / lore-footer keyword -> placement, used after PATTERNS.
CATEGORY_RULES = [
    ("MUTATION", "FARMING", "MUTATIONS", "Crop Mutations"),
    ("GARDEN CHIP", "FARMING", "GARDEN_CHIPS", "Garden Chips"),
    ("POWER STONE", "ODDITIES", "POWER_STONES", "Power Stones"),
    ("REFORGE STONE", "ODDITIES", "REFORGE_STONES", "Reforge Stones"),
    ("PET ITEM", "ODDITIES", "PET_ITEMS", "Pet Items"),
    ("BOOSTER", "ODDITIES", "BOOSTERS", "Skill Boosters"),
    ("SALT", "WOODS_FISHES", "FISHING_GEAR", "Fishing Supplies"),
    ("ARROW POISON", "COMBAT", "ARROWS", "Arrows & Poisons"),
    ("DUNGEON ITEM", "COMBAT", "DUNGEONS", "Dungeon Loot"),
    ("CONSUMABLE", "ODDITIES", "CONSUMABLES", "Consumables"),
]

GEMS = ["RUBY", "AMBER", "TOPAZ", "JADE", "SAPPHIRE", "AMETHYST", "JASPER", "OPAL", "ONYX",
        "AQUAMARINE", "CITRINE", "PERIDOT"]
GEM_TIERS = ["ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT"]

# Hand placement for products the recipe-family pass cannot reach (mob drops, event items, misc).
# (regex on the id) -> (category, group key, group display name). First match wins.
PATTERNS = [
    # --- combat: slayer drops by boss line -------------------------------------------------------
    (r"^(REVENANT_FLESH|REVENANT_VISCERA|FOUL_FLESH|UNDEAD_CATALYST|BEHEADED_HORROR|SCYTHE_BLADE|REVENANT_CATALYST|SHARD_OF_THE_SHREDDED|WARDEN_HEART|SEVERED_HAND|SKULL_OF_THE_WARDEN)$",
     "COMBAT", "SLAYER_ZOMBIE", "Zombie Slayer Drops"),
    (r"^(TARANTULA_WEB|TARANTULA_SILK|TOXIC_ARROW_POISON|SPIDER_CATALYST|BANE_OF_ARTHROPODS_6|FLY_SWATTER|DIGESTED_MOSQUITO|VIAL_OF_VENOM|SPIDER_KEY|ENSNARED_SNAIL|PRIMORDIAL_EYE)$",
     "COMBAT", "SLAYER_SPIDER", "Spider Slayer Drops"),
    (r"^(WOLF_TOOTH|GOLDEN_TOOTH|HAMSTER_WHEEL|GRIZZLY_BAIT|RED_CLAW_EGG|OVERFLUX_CAPACITOR|COUTURE_RUNE|WEREWOLF_SKIN|MINOS_RELIC)$",
     "COMBAT", "SLAYER_WOLF", "Wolf Slayer Drops"),
    (r"^(NULL_SPHERE|NULL_OVOID|NULL_ATOM|NULL_BLADE|NULL_EDGE|RAW_SOULFLOW|SOULFLOW|SUMMONING_EYE|TRANSMISSION_TUNER|SINFUL_DICE|EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER|ETHERWARP_MERGER|ETHERWARP_CONDUIT|JUDGEMENT_CORE|POCKET_ESPRESSO_MACHINE|ENCHANT_RUNE|ENDERSNAKE_RUNE|BRAIN_FOOD|VOID_CONQUEROR_ENDERMAN_SKIN|HANDY_BLOOD_CHALICE|ENCHANTED_ENDER_PEARL)$",
     "COMBAT", "SLAYER_ENDERMAN", "Enderman Slayer Drops"),
    (r"^(DERELICT_ASHE|FLAMES|WISP_ICE_FLAVORED_WATER|MAGMA_URCHIN|INFERNO_.*|SUBZERO_INVERTER|HIGH_CLASS_ARCHFIEND_DICE|ARCHFIEND_DICE|FIERY_BURST_RUNE|BLAZE_ROD_DISTILLATE|GLOWSTONE_DUST_DISTILLATE|MAGMA_CREAM_DISTILLATE|NETHER_STALK_DISTILLATE|CRUDE_GABAGOOL_DISTILLATE|GABAGOOL.*|CRUDE_GABAGOOL|HEAVY_GABAGOOL|FUEL_GABAGOOL|VERY_CRUDE_GABAGOOL|MOLTEN_POWDER|KELVIN_INVERTER|SCORCHED_POWER_CRYSTAL|BLAZE_WAX)$",
     "COMBAT", "SLAYER_BLAZE", "Blaze Slayer Drops"),
    (r"^(COVEN_SEAL|BUBBA_BLISTER|FANGTASTIC_CHOCOLATE_CHIP|GUARDIAN_LUCKY_BLOCK|MCGRUBBER_BURGER|UNFANGED_VAMPIRE_PART|CHOCOLATE_CHIP|VAMPIRE_DENTIST_RELIC)$",
     "COMBAT", "SLAYER_VAMPIRE", "Vampire Slayer Drops"),
    # --- combat: dungeons, kuudra, crimson, end ---------------------------------------------------
    (r"^(ESSENCE_.*)$", "ODDITIES", "ESSENCES", "Essences"),
    (r"^(GIANT_FRAGMENT_.*|.*_FRAGMENT|WITHER_CATALYST|FEL_PEARL|ANCIENT_ROSE|RECOMBOBULATOR_3000|.*_SCROLL|WITHER_BLOOD|DUNGEON_.*|KISMET_FEATHER|ARCHITECT_FIRST_DRAFT|DUNGEON_CHEST_KEY|SPIRIT_LEAP|SUPERBOOM_TNT|DECOY|INFLATABLE_JERRY|TRAP|JOLLY_PINK_ROCK|SECRET_DUNGEON_REDSTONE_KEY|SPIRIT_BONE|SPIRIT_DECOY|SPIRIT_WING|SPIRIT_BOW)$",
     "COMBAT", "DUNGEONS", "Dungeon Loot"),
    (r"^(KUUDRA_.*|MANDRAA|TOXIC_RANDOM_DROP|CHILI_PEPPER|STUFFED_CHILI_PEPPER|SPECTRE_DUST|TENTACLE_DYE|BURNING_KUUDRA_CORE|ENRAGER|WHEEL_OF_FATE|AURORA_STAFF)$",
     "COMBAT", "KUUDRA", "Leviathan Loot"),
    (r"^(FACTION_.*|MAGMA_CHUNK|HORN_OF_TAURUS|MOOGMA_PELT|MOLTEN_CUBE|LUMP_OF_MAGMA|CORRUPTED_FRAGMENT|LAVA_WATER_ORB|SMOLDERING_CHAMBER|TORMENTED_CHAMBER|.*_DYE|FLAMING_HEART|PYROCLASTIC_SCALE|BLAZEN_SPHERE|MILLENIA_OLD_BLAZE_ASHES|SULPHURIC_COAL|ENCHANTED_SULPHUR|ENCHANTED_SULPHUR_CUBE|DIGESTED_MUSHROOMS|ENDSTONE_GEODE|SCORCHED_BOOKS|GAUNTLET_OF_CONTAGION|HELLSTORM_WAND|FLAMING_FIST|MAGMA_BUCKET|ARMOR_OF_YOG|HEAVY_PEARL)$",
     "COMBAT", "CRIMSON", "Crimson Isle Loot"),
    (r"^(SUMMONING_EYE|DRAGON_.*|.*_DRAGON_FRAGMENT|ENDER_MONOCLE|ENDSTONE_ROSE|ENDER_PEARL_STONE|CRYSTAL_FRAGMENT|TRITANIUM|LUXURIOUS_SPOOL|ENCHANTED_EYE_OF_ENDER|ENCHANTED_ENDSTONE|ENCHANTED_ENDER_PEARL|ABSOLUTE_ENDER_PEARL)$",
     "COMBAT", "THE_END", "The End"),
    (r"^(GHAST_TEAR|ENCHANTED_GHAST_TEAR|SILVER_FANG|ANCIENT_CLAW|ENCHANTED_ANCIENT_CLAW|GRIFFIN_FEATHER|DAEDALUS_STICK|CHIMERA|CROCHET_TIGER_PLUSHIE|DWARF_TURTLE_SHELMET|ANTIQUE_REMEDIES|CRYPT_DREADLORD_SWORD|WASHED_UP_SOUVENIR|MINOS_.*|GRIFFIN_.*)$",
     "COMBAT", "MYTHOLOGICAL", "Mythological Loot"),
    (r"^(SHARD_.*)$", "COMBAT", "SHARDS", "Hunting Shards"),
    (r"^(.*_FOSSIL|HELIX|SUSPICIOUS_SCRAP|GEMSTONE_MIXTURE|POWER_CRYSTAL|PRECURSOR_APPARATUS|DIVAN_POWDER_COATING|GOLDEN_PLATE|BLUE_ICE_HUNK|DRILL_ENGINE|HIGHLITE|SPOTLITE|IRIDIUM|GOLDEN_POWDER|BROKEN_RADAR|CORRUPTED_NETHER_STAR)$",
     "MINING", "FOSSILS", "Fossils & Relics"),
    (r"^((SMALL|MEDIUM|LARGE|GIANT)_(FROG_TREAT|HONEY_DIPPER|PIG_EAR|SEAL_PLUSHIE|SLOTH_CLAW|WITCH_CAULDRON))$",
     "ODDITIES", "PET_ITEMS", "Pet Items"),
    (r"^(.*_WATER_ORB|WATER_ORB|WET_WATER|WET_BOOK|MOUND_OF_SEAGRASS|VIBRANT_CORAL|TROUBLED_BUBBLE|OCTOPUS_TENDRIL|TENTACLE_MEAT|SCUTTLER_SHELL|FROZEN_SCUTE|TITANOBOA_SHED|ALLIGATOR_SKIN|GILL_MEMBRANE|OLD_LEATHER_BOOT|RUSTY_COIN|UNLUCKY_DOUBLOON|MOBY_DUCK_COLLECTOR_EDITION|.*_CHUM_BUCKET|CAN_OF_WORMS|INK_SPLAT|WATER_HYACINTH|REINFORCED_NETTING|SEARED_ESCARGOT|SCORCHED_CRAB_STICK|SEVERED_PINCER|KADA_LEAD|FROGCOIN|BLIZZARD_BOTTLE|FIRE_IN_A_BOTTLE)$",
     "WOODS_FISHES", "FISHING_GEAR", "Fishing Supplies"),
    # --- mining extras ------------------------------------------------------------------------------
    (r"^(REFINED_.*|MITHRIL_.*|ENCHANTED_MITHRIL|TITANIUM_.*|ENCHANTED_TITANIUM|REFINED_TITANIUM|STARFALL|TREASURITE|ENCHANTED_TREASURITE|GOBLIN_.*|DWARVEN_.*|SORROW|BIOFUEL|OIL_BARREL|PLASMA|VOLTA|FTX_3070|ROBOTRON_REFLECTOR|SUPERLITE_MOTOR|CONTROL_SWITCH|ELECTRON_TRANSMITTER|SYNTHETIC_HEART|PREHISTORIC_EGG|PICKONIMBUS|BEJEWELED_HANDLE|CORLEONITE|WORM_MEMBRANE|JUNGLE_HEART|ASCENSION_ROPE|WISHING_COMPASS|YOGGIE|GLACITE_.*|ENCHANTED_GLACITE|UMBER|ENCHANTED_UMBER|UMBER_PLATE|TUNGSTEN|ENCHANTED_TUNGSTEN|TUNGSTEN_PLATE|BEJEWELED_COLLAR|MINERAL_PLATE|GLOSSY_GEMSTONE|FROSTY_THE_SNOW_.*|SHATTERED_PENDANT|ONYX_.*|SLUDGE_JUICE|PERFECT_PLATE|FLAWLESS_.*_GEM|HARD_STONE|ENCHANTED_HARD_STONE|CONCENTRATED_STONE|SULPHUR_ORE|ENCHANTED_SULPHUR_ORE)$",
     "MINING", "DEEP_MINING", "Deep Mining"),
    # --- farming extras -----------------------------------------------------------------------------
    (r"^(COMPOST|ENCHANTED_COMPOST|DUNG|HONEY_JAR|CROPIE|SQUASH|FERMENTO|CONDENSED_FERMENTO|PLANT_MATTER|JACOBS_TICKET|BOX_OF_SEEDS|TOOL_EXP_CAPSULE|FINE_FLOUR|BEADY_EYES|BITE_BUG|.*_BUG|PESTERMINATOR.*|DUNG.*|HONEY.*|OVERGROWN_GRASS|FIGSTONE|SUPREME_CHOCOLATE_BAR|BURROWING_SPORES|POLISHED_PUMPKIN|ENCHANTED_SEEDS|CHOCOLATE_.*|COPPER|ENCHANTED_COPPER|SPRAY.*|MAGIC_TOP_HAT|CHIRPING_STEREO|DUST_.*|.*_SPRAYONATOR|.*_MUTATION|WRIGGLING_LARVA|WRIGGLING_LARVAE|PELT|GEMSTONE_FUEL)$",
     "FARMING", "GARDEN", "Garden & Pests"),
    # --- woods & fishes extras ----------------------------------------------------------------------
    (r"^(SHARK_FIN|ENCHANTED_SHARK_FIN|NURSE_SHARK_TOOTH|BLUE_SHARK_TOOTH|TIGER_SHARK_TOOTH|GREAT_WHITE_SHARK_TOOTH|GREAT_WHITE_TOOTH_MEAL|SHARK_.*)$",
     "WOODS_FISHES", "SHARKS", "Shark Parts"),
    (r"^(MAGMA_FISH.*|ENCHANTED_MAGMA_FISH.*|FLAMING_WORM|LAVA_SHELL|THUNDER_SHARDS|THUNDER_IN_A_BOTTLE|EMPTY_THUNDER_BOTTLE|STORM_IN_A_BOTTLE|EMPTY_STORM_BOTTLE|HURRICANE_IN_A_BOTTLE|EMPTY_HURRICANE_BOTTLE|MAGMA_CORE|ORB_OF_ENERGY|LUMINOUS_.*|.*_TROPHY|TROPHY_.*|SLUG_BOOTS|MOODY_GRAPPLESHOT|MAGMA_LORD_FRAGMENT|LAVA_HORSE_.*|FLAMING_SPIDER.*|LAVA_.*|PLASMIX)$",
     "WOODS_FISHES", "LAVA_FISHING", "Lava Fishing"),
    (r"^(.*_BAIT|BAIT.*|.*_SPONGE|SPONGE|ENCHANTED_SPONGE|ENCHANTED_WET_SPONGE|WET_SPONGE|SEA_.*|SQUID.*|ENCHANTED_INK_SACK|DEEP_SEA_ORB|GREAT_WHITE_.*|FISH_AFFINITY_TALISMAN|EMPEROR_.*|WATER_HYDRA_HEAD|AGATHA_COUPON|FROZEN_BAIT|SPOOKY_BAIT|DARK_BAIT|CARROT_BAIT|SHARK_BAIT|HOT_BAIT|OBFUSCATED_FISH_.*|CHUM.*|FISHING_.*|SALT_CUBE|.*_SALT|BOBBIN.*|.*_ROD_.*|SINKER.*|HOOK.*|LINE.*)$",
     "WOODS_FISHES", "FISHING_GEAR", "Fishing Supplies"),
    (r"^(.*_LOG.*|LOG.*|ENCHANTED_.*_LOG|ENCHANTED_.*_WOOD|.*_SAPLING|.*_LEAVES|TENDER_WOOD|VINESAP|LUSHLILAC|SEA_LUMIES|RUBY_VEILSHROOM|HONEYCOMB|.*_BARK|FOREST_ESSENCE|DEEP_ROOT|SPECTRE.*|.*_WOOD)$",
     "WOODS_FISHES", "FORESTS", "Forest Goods"),
    # --- oddities -------------------------------------------------------------------------------------
    (r"^(BOOSTER_COOKIE|STOCK_OF_STONKS|HOT_POTATO_BOOK|JALAPENO_BOOK|POLARVOID_BOOK|FUMING_POTATO_BOOK|RECOMBOBULATOR_3000|KAT_.*|FLOWER_.*|PET_ITEM_.*|.*_PET_ITEM|PET_CAKE|BOTTLE_OF_JYRRE|DARK_CACAO_TRUFFLE|DISCRITE|EXP_BOTTLE|GRAND_EXP_BOTTLE|TITANIC_EXP_BOTTLE|COLOSSAL_EXP_BOTTLE|EXPERIENCE_BOTTLE|BITS_.*|GOD_POTION.*|POTION_.*|FIRST_MASTER_STAR|SECOND_MASTER_STAR|THIRD_MASTER_STAR|FOURTH_MASTER_STAR|FIFTH_MASTER_STAR|.*_MASTER_STAR|ARTIFACT_UPGRADER|TALISMAN_ENRICHMENT_.*|ENRICHMENT_.*|.*_ENRICHMENT)$",
     "ODDITIES", "UPGRADES", "Upgrades & Boosters"),
    (r"^(JERRY_.*|WHITE_GIFT|GREEN_GIFT|RED_GIFT|GIFT.*|SNOW_.*|ENCHANTED_SNOW_BLOCK|ICE_.*|ENCHANTED_ICE|ENCHANTED_PACKED_ICE|GLACIAL_FRAGMENT|WALNUT|NORTH_STAR.*|CANDY.*|PURPLE_CANDY|GREEN_CANDY|ECTOPLASM|PUMPKIN_GUTS|SPOOKY_SHARD|WEREWOLF_SKIN|SOUL_FRAGMENT|HALLOWEEN_.*|SPOOKY_.*|NEW_YEAR_.*|CAKE_.*|ENCHANTED_CAKE.*|CENTURY_CAKE.*|EASTER.*|EGG.*|CHOCOLATE.*|HOPPITY.*|RABBIT_.*_EGG)$",
     "ODDITIES", "SEASONAL", "Festival Goods"),
    (r"^(GENERATOR_.*|MINION_EXPANDER|.*_ENCHANTED_CHEST|DWARVEN_COMPACTOR|.*_MINION_.*|MINION_.*|ENCHANTED_LAVA_BUCKET|ENCHANTED_CHARCOAL|HAMSTER_WHEEL|FOUL_FLESH|CATALYST|HYPER_CATALYST|HYPER_CATALYST_UPGRADE|SOLAR_PANEL|COMPACTOR|SUPER_COMPACTOR_3000|DWARVEN_SUPER_COMPACTOR|AUTO_SMELTER|DIAMOND_SPREADING|BUDGET_HOPPER|ENCHANTED_HOPPER|CORRUPT_SOIL|FLYCATCHER_UPGRADE|BERBERIS_FUEL_INJECTOR|SLEEPY_HOLLOW|MITHRIL_INFUSION|FREE_WILL|POTATO_SPREADING|KRAMPUS_HELMET|LAVA_BUCKET|MAGMA_BUCKET|PLASMA_BUCKET|INFERNO_MINION_FUEL|TASTY_CHEESE|FOUL_FLESH)$",
     "ODDITIES", "MINION_GOODS", "Minion Supplies"),
    (r"^(.*_RUNE.*|RUNE_.*|.*_REFORGE.*|PRECURSOR_GEAR|BLESSED_FRUIT|DRAGON_HORN|BEADY_EYES|RED_SCARF|AMBER_MATERIAL|SALMON_OPAL|MOLTEN_CUBE|PETRIFIED_STARFALL|SCORCHED_BOOKS|DIAMONITE|ROCK_GEMSTONE|HARDENED_WOOD|LUCKY_DICE|DIAMOND_ATOM|DIRT_BOTTLE|DRAGON_CLAW|DRAGON_SCALE|END_STONE_GEODE|ETERNAL_FLAME_RING|GIANT_TOOTH|JADERALD|KUUDRA_MANDIBLE|LARGE_WALNUT|MIDAS_JEWEL|MOIL_LOG|OPTICAL_LENS|PITCHIN_KOI|PURE_MITHRIL|REFINED_AMBER|SADAN_BROOCH|SHINY_PRISM|SKYMART_BROCHURE|SPIRIT_DECOY|SUSPICIOUS_VIAL|TOIL_LOG|WARPED_STONE|WITHER_BLOOD|BULKY_STONE|BURROWING_SPORES|CANDY_CORN|CHERRY_BLOSSOM|DWARVEN_TREASURE|ENDSTONE_GEODE|FROZEN_BAUBLE|GLEAMING_CRYSTAL|GOLDEN_BALL|HOT_STUFF|MOONGLADE_JEWEL|PRECIOUS_PEARL|ROCK_CANDY|SCORCHED_BLAZE_ROD|SQUEAKY_TOY|TOXIC_ROSE|.*_STONE)$",
     "ODDITIES", "REFORGE_GOODS", "Reforge Materials"),
]


# Rule groups that beat the recipe-family pass (a Hot Potato Book is made of paper but is not a farming good).
PRIORITY_GROUPS = {"UPGRADES", "MINION_GOODS", "FISHING_GEAR", "SLAYER_ZOMBIE", "SLAYER_SPIDER", "SLAYER_WOLF",
                   "SLAYER_ENDERMAN", "SLAYER_BLAZE", "SLAYER_VAMPIRE", "DUNGEONS", "KUUDRA", "SEASONAL"}


def jload(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def strip_codes(s):
    return re.sub("§.", "", s or "")


def load():
    ctx = {}
    ctx["products"] = jload(os.path.join(DATA, "bazaar.json"))["products"]
    items = {}
    for it in jload(os.path.join(DATA, "items.json"))["items"]:
        items[it["id"]] = it
    ctx["items"] = items
    col = jload(os.path.join(DATA, "collections.json"))["collections"]
    roots = {}
    for skill, v in col.items():
        for iid, c in v["items"].items():
            roots[iid] = (SKILL_TO_CAT.get(skill, "ODDITIES"), c.get("name", iid))
    ctx["roots"] = roots
    recipes, shop, lore_tail, crafttext = {}, {}, {}, {}
    neu_dir = os.path.join(DATA, "neu-repo", "items")
    for path in glob.glob(os.path.join(neu_dir, "*.json")):
        try:
            d = jload(path)
        except Exception:
            continue
        iid = d.get("internalname")
        if not iid:
            continue
        lore = d.get("lore") or []
        if lore:
            lore_tail[iid] = strip_codes(lore[-1])
        if d.get("crafttext"):
            crafttext[iid] = strip_codes(d["crafttext"])
        rs = []
        if d.get("recipe") and isinstance(d["recipe"], dict):
            r = dict(d["recipe"]); r.setdefault("type", "crafting"); rs.append(r)
        for r in d.get("recipes") or []:
            rs.append(r)
        for r in rs:
            t = r.get("type", "crafting")
            if t == "crafting":
                cells = [r.get(k, "") for k in ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")]
                ins = collections.Counter()
                for c in cells:
                    if not c: continue
                    nm, amt = split_cell(c)
                    ins[nm] += amt
                if ins:
                    recipes.setdefault(iid, []).append(("crafting", dict(ins), int(r.get("count", 1) or 1)))
            elif t == "forge":
                ins = collections.Counter()
                for c in r.get("inputs") or []:
                    nm, amt = split_cell(c)
                    ins[nm] += amt
                if ins:
                    recipes.setdefault(iid, []).append(("forge", dict(ins), int(r.get("count", 1) or 1)))
            elif t == "npc_shop":
                res = r.get("result", "")
                rnm, ramt = split_cell(res) if res else (iid, 1)
                coins = None
                other = False
                for c in r.get("cost") or []:
                    nm, amt = split_cell(c)
                    if nm == "SKYBLOCK_COIN" or nm == "COINS":
                        coins = amt
                    else:
                        other = True
                if coins and not other and ramt > 0:
                    unit = coins / ramt
                    if rnm not in shop or unit < shop[rnm]:
                        shop[rnm] = unit
    ctx["recipes"] = recipes
    ctx["shop"] = shop
    ctx["neu_lore_tail"] = lore_tail
    ctx["crafttext"] = crafttext
    return ctx


def split_cell(c):
    c = c.strip()
    m = re.match(r"^(.*):(\d+(?:\.\d+)?)$", c)
    nm, amt = (m.group(1), float(m.group(2))) if m else (c, 1.0)
    # NEU writes damage values with a dash ("INK_SACK-3"); the game ids use a colon ("INK_SACK:3").
    nm = re.sub(r"-(\d+)$", r":\1", nm)
    return nm, amt


def dominant_parent(pid, ctx):
    for kind, ins, cnt in ctx["recipes"].get(pid, []):
        best = max(ins.items(), key=lambda kv: kv[1])
        total = sum(ins.values())
        if best[1] / total >= 0.5 and best[0] != pid:
            return best[0]
    return None


def families(ctx):
    """product id -> (category, group key) for everything a recipe chain leads back to a collection root."""
    roots, out = ctx["roots"], {}
    for pid in ctx["products"]:
        cur, seen = pid, set()
        for _ in range(10):
            if cur in ROOT_ALIAS:
                cur = ROOT_ALIAS[cur]
            if cur in roots:
                key = MERGE.get(cur, cur)
                cat = roots[key][0] if key in roots else roots[cur][0]
                out[pid] = (cat, key)
                break
            if cur in seen:
                break
            seen.add(cur)
            nxt = dominant_parent(cur, ctx)
            if nxt is None:
                break
            cur = nxt
    return out


def gem_of(pid):
    for t in GEM_TIERS:
        for g in GEMS:
            if pid == f"{t}_{g}_GEM":
                return g, GEM_TIERS.index(t)
    return None


def special_group(pid, ctx):
    """(category, group key, display) for products placed by rule rather than by recipe family."""
    if pid.startswith("ENCHANTMENT_"):
        m = re.match(r"^ENCHANTMENT_(.+)_(\d+)$", pid)
        if m:
            ench = m.group(1)
            return ("ENCHANTMENTS", "ENCH_" + ench, None)
    g = gem_of(pid)
    if g:
        return ("MINING", "GEM_" + g[0], g[0].title() + " Gemstones")
    for rx, cat, key, disp in PATTERNS:
        if re.match(rx, pid):
            return (cat, key, disp)
    it = ctx["items"].get(pid, {})
    tail = (ctx["neu_lore_tail"].get(pid, "") + " " + (it.get("category") or "").replace("_", " ")).upper()
    for kw, cat, key, disp in CATEGORY_RULES:
        if kw in tail:
            return (cat, key, disp)
    return None


def level_of(pid):
    m = re.match(r"^ENCHANTMENT_(.+)_(\d+)$", pid)
    return int(m.group(2)) if m else 0


def main():
    ctx = load()
    fam = families(ctx)
    items, products = ctx["items"], ctx["products"]
    rows, report = [], []
    groups = collections.OrderedDict()   # (cat, key) -> dict
    placed = collections.Counter()

    def group(cat, key, disp, root=None):
        k = (cat, key)
        if k not in groups:
            groups[k] = {"category": cat, "key": key, "name": disp, "root": root, "products": []}
        return groups[k]

    for pid in sorted(products):
        q = products[pid]["quick_status"]
        it = items.get(pid, {})
        sp = special_group(pid, ctx)
        is_root = pid in ctx["roots"] or pid in ROOT_ALIAS
        if pid in fam and (sp is None or (sp[0] != "ENCHANTMENTS" and (is_root or sp[1] not in PRIORITY_GROUPS))):
            cat, key = fam[pid]
            # collection display name comes from runtime item names; keep a fallback here
            disp = None
            gr = group(cat, key, disp, root=key)
            how = "family"
        elif sp is not None:
            cat, key, disp = sp
            gr = group(cat, key, disp)
            how = "rule"
        else:
            cat, key, disp = "ODDITIES", "MISC", "Miscellaneous"
            gr = group(cat, key, disp)
            how = "misc"
        placed[how] += 1
        bid = float(q.get("sellPrice") or 0)    # instant-sell price = best buy order
        ask = float(q.get("buyPrice") or 0)     # instant-buy price = best sell offer
        npc = float(it.get("npc_sell_price") or 0)
        shop = ctx["shop"].get(pid)
        ref, note = reference_price(bid, ask, npc, shop)
        weekly = float(q.get("sellMovingWeek") or 0) + float(q.get("buyMovingWeek") or 0)
        row = {
            "id": pid,
            "category": cat,
            "group": key,
            "ref": round(ref, 1),
            "refBid": round(bid, 1),
            "refAsk": round(ask, 1),
            "npcSell": npc,
            "weekly": int(weekly),
        }
        if shop:
            row["npcBuy"] = round(shop, 1)
        if bid <= 0 and ask <= 0:
            row["derived"] = True
        elif lowball(bid, ask):
            # A troll buy order (0.1 coins under a 1,000-coin ask) is the commonest absurdity on a thin
            # product, and "trust the smaller number" would have let the market maker SELL the item for
            # pennies. Price it like an empty snapshot instead, remembering the ask as an upper bound.
            row["derived"] = True
            row["askHint"] = ask
            note = (note or "") + f" lowball bid {bid} under ask {ask}: re-derived"
        if note:
            report.append(f"{pid}: {note}")
        gr["products"].append(pid)
        rows.append(row)

    derive_prices(rows, ctx, report)
    shop_ceilings(rows, report)
    seed_materials(rows, ctx, report)

    # order products inside each group: by recipe depth (raw first), gems by tier, books by level
    depth = {}
    def d(pid, n=0):
        if pid in depth: return depth[pid]
        p = dominant_parent(pid, ctx)
        r = 0 if (p is None or n > 8 or p not in products) else d(p, n + 1) + 1
        depth[pid] = r
        return r
    for gr in groups.values():
        def sk(pid):
            g = gem_of(pid)
            if g: return (g[1], pid)
            if pid.startswith("ENCHANTMENT_"): return (level_of(pid), pid)
            return (d(pid), pid)
        gr["products"].sort(key=sk)

    # order groups: category order, then collection-root groups first (sorted by root name), rules, misc
    cat_order = {c[0]: i for i, c in enumerate(CATEGORIES)}
    def gk(g):
        kind = 0 if g["root"] else (2 if g["key"] == "MISC" else 1)
        return (cat_order[g["category"]], kind, (g["name"] or g["key"]).lower())
    ordered = sorted(groups.values(), key=gk)

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "products.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=0, separators=(",", ":"))
    gdoc = {
        "categories": [{"id": c[0], "name": c[1], "icon": c[2], "glass": c[3]} for c in CATEGORIES],
        "groups": [group_doc(g, ctx) for g in ordered],
    }
    with open(os.path.join(OUT, "groups.json"), "w", encoding="utf-8") as f:
        json.dump(gdoc, f, ensure_ascii=False, indent=1)

    per_cat = collections.Counter(g["category"] for g in ordered)
    prod_cat = collections.Counter(r["category"] for r in rows)
    with open(os.path.join(HERE, "report.txt"), "w", encoding="utf-8") as f:
        f.write(f"products: {len(rows)}  groups: {len(ordered)}\n")
        f.write(f"placed by: {dict(placed)}\n")
        for c in CATEGORIES:
            f.write(f"  {c[0]:14} groups {per_cat[c[0]]:4}  products {prod_cat[c[0]]:5}\n")
        f.write("\n# groups\n")
        for g in ordered:
            f.write(f"{g['category']:14} {g['key']:28} {len(g['products']):3}  {' '.join(g['products'][:12])}\n")
        f.write("\n# price notes\n")
        for line in report:
            f.write(line + "\n")
    print(f"wrote {len(rows)} products, {len(ordered)} groups; placed {dict(placed)}")


def derive_prices(rows, ctx, report):
    """Products with an EMPTY snapshot get a price from what they are made of, or (books) from the next level
    (two level-N books combine into one N+1, so N is worth about half of N+1). Anything still unpriced is flagged
    "mm": false: the market makers will not quote it, so it cannot be bought for one coin out of thin air."""
    by = {r["id"]: r for r in rows}
    known = {r["id"]: r["ref"] for r in rows if not r.get("derived")}
    real = set(known)
    guesses = set()
    for _ in range(6):
        changed = False
        for r in rows:
            pid = r["id"]
            if pid in known:
                continue
            v = None
            m = re.match(r"^ENCHANTMENT_(.+)_(\d+)$", pid)
            if m:
                ench, lv = m.group(1), int(m.group(2))
                up, down = f"ENCHANTMENT_{ench}_{lv + 1}", f"ENCHANTMENT_{ench}_{lv - 1}"
                # Levels up to V come off the enchanting table for next to nothing and nobody trades them;
                # above that two books of level N combine into N+1, so a real neighbour prices a missing level.
                if lv >= 6 and up in real:
                    v = known[up] / 2.2
                elif lv >= 6 and down in real:
                    v = known[down] * 2.0
                else:
                    continue
            guessed = False
            if v is None:
                for kind, ins, cnt in ctx["recipes"].get(pid, []):
                    tot, ok, gs = 0.0, True, False
                    for iid, amt in ins.items():
                        if iid in known:
                            tot += known[iid] * amt
                            gs = gs or iid in guesses
                        elif iid in ctx["items"] and ctx["items"][iid].get("npc_sell_price"):
                            tot += float(ctx["items"][iid]["npc_sell_price"]) * 1.2 * amt
                            gs = True
                        else:
                            ok = False
                            break
                    if ok and tot > 0:
                        v = tot / max(1, cnt)
                        guessed = gs
                        break
            if v is not None:
                hint = r.get("askHint") or 0
                if hint > 0 and v > hint:
                    v = hint          # never value it above what sellers were actually asking
                v = max(v, r["npcSell"] * 1.05, 0.2)
                known[pid] = v
                if guessed:
                    guesses.add(pid)
                r["ref"] = round(v, 1)
                report.append(f"{pid}: derived {v:.1f}" + (" (inputs partly guessed from NPC prices)" if guessed else ""))
                changed = True
        if not changed:
            break
    for r in rows:
        hint = r.get("askHint") or 0
        if r["id"] not in known:
            if hint > 0:
                # Only the ask is believable: the market maker SELLS near it and never bids above the NPC price,
                # so a bad guess can only make the item dear (a coin sink), never cheap or a coin faucet.
                r["ref"] = round(max(hint * 0.9, r["npcSell"] * 1.05, 0.2), 1)
                r["mmSides"] = "ask"
                report.append(f"{r['id']}: priced from its ask {hint}, market maker sells only")
            elif r["npcSell"] > 0:
                # Nothing to price it by but the NPC: the market maker only BIDS, at exactly the NPC price (what an
                # NPC pays anyway), and sells nothing, so it cannot become a cheap source of the item.
                r["ref"] = round(r["npcSell"] * 1.5, 1)
                r["mmSides"] = "bid"
                report.append(f"{r['id']}: unpriced, market maker bids the npc price {r['npcSell']} only")
            else:
                r["mm"] = False
                report.append(f"{r['id']}: unpriced, no market maker")
        elif r["id"] in guesses:
            # Derived from NPC-price guesses: a cheap ask would be an item faucet, so bid-only at the NPC floor.
            r["mmSides"] = "bid" if r["npcSell"] > 0 else None
            if r["mmSides"] is None:
                r.pop("mmSides")
                r["mm"] = False
                report.append(f"{r['id']}: price only guessed, no market maker")
        r.pop("derived", None)
        r.pop("askHint", None)


def lowball(bid, ask):
    """A wide snapshot spread whose bid is a lowball troll order (or missing): the bid says nothing about value."""
    if ask <= 0:
        # Buy orders but NOBODY selling: the item is scarce, and a lone bid (often a 0.1-coin troll order) says
        # what someone hopes to pay, not what it is worth. Selling it from the market maker at that number would
        # be the cheapest source in the game, so re-derive it instead.
        return bid > 0
    if bid <= 0:
        return True
    return ask > bid * 1.5 and bid < ask / 20.0


def shop_ceilings(rows, report):
    """NPC shop prices from the shops module's catalogue (data/shops/shops.json), so the market makers never ask
    above what an NPC sells for and never bid what the shop charges (buy from the NPC, dump on the bots). An entry
    that also takes items is valued at those items' reference prices; one that takes another currency (bits,
    pelts, north stars) is skipped, because a ceiling that ignores part of the price would be too LOW."""
    path = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "shops", "shops.json")
    if not os.path.exists(path):
        report.append("shops.json not found: NPC shop ceilings from the NEU data only")
        return
    by = {r["id"]: r for r in rows}
    try:
        shops = jload(path)
    except Exception as e:
        report.append(f"shops.json unreadable ({e}): NPC shop ceilings from the NEU data only")
        return
    n = 0
    for s in shops:
        for e in s.get("entries") or []:
            pid = e.get("item")
            if pid not in by or e.get("currency"):
                continue
            amount = float(e.get("amount") or 1)
            cost = float(e.get("coins") or 0)
            ok = True
            for it in e.get("items") or []:
                iid = it.get("item")
                ir = by.get(iid)
                if ir is None or ir.get("mm") is False or ir["ref"] <= 0:
                    ok = False
                    break
                cost += ir["ref"] * float(it.get("amount") or 1)
            if not ok or cost <= 0 or amount <= 0:
                continue
            unit = cost / amount
            r = by[pid]
            if r.get("npcBuy") and r["npcBuy"] <= unit:
                continue
            r["npcBuy"] = round(unit, 1)
            n += 1
            if r["ref"] > unit and unit >= r["npcSell"] * 1.05:
                report.append(f"{pid}: capped at shop {s.get('id')} price {unit:.1f} (was {r['ref']})")
                r["ref"] = round(unit, 1)
    report.append(f"shop ceilings from shops.json: {n} products")


def group_doc(g, ctx):
    """A group as written: rule groups carry their own (generic) name; collection groups carry the root ids whose
    RUNTIME display names make the title ("Wheat & Seeds"), so a rename by the owning module shows up here too."""
    d = {"category": g["category"], "key": g["key"], "products": g["products"]}
    if g["root"]:
        roots = [pid for pid in g["products"] if MERGE.get(pid, pid) == g["key"] and pid in ctx["roots"]]
        if not roots:
            roots = [g["key"]]
        d["titleIds"] = roots
        d["icon"] = roots[0] if roots[0] in ctx["products"] else g["products"][0]
    else:
        d["name"] = g["name"]
        d["icon"] = g["products"][0]
    return d


# ---- seeding trick (research 05 s2.9): periodic Merchant Guild sell offers for progression-gating materials ----
# A material GATES progression when it is a drop nobody can craft or gather by collection (a slayer, dungeon, mining
# or fishing drop) and it goes into gear/tools whose recipe or use is unlocked at an EARLY tier. On a 100k-player
# server somebody always farms it; on a small one nobody has yet, and a new player is stuck. These caps define
# "early" and are // TUNE (research gives the trick, not the thresholds).
SEED_EARLY = {"slayer": 5, "hotm": 4, "skill": 20, "cata": 15}
SEED_MAX_REF = 100_000      # coins: anything dearer is not an early-game purchase
SEED_MIN_REF = 20           # coins: cheaper drops (rough gems, snowballs) are gathered in bulk, not bottlenecks
SEED_MIN_WEEKLY = 20_000    # a material players really trade, not an oddity
SEED_TOP = 30
SEED_BATCH_MIN, SEED_BATCH_MAX = 16, 640


def early_gate(iid, ctx):
    """Short labels of the EARLY gates on an output item ([] = none / late)."""
    out = []
    t = ctx["crafttext"].get(iid, "")
    m = re.match(r"Requires: (Zombie|Wolf|Spider|Enderman|Blaze|Vampire) Slayer (\d+)", t)
    if m and int(m.group(2)) <= SEED_EARLY["slayer"]:
        out.append(f"{m.group(1).lower()} slayer {m.group(2)}")
    m = re.match(r"Requires: HotM (\d+)", t)
    if m and int(m.group(1)) <= SEED_EARLY["hotm"]:
        out.append(f"hotm {m.group(1)}")
    for q in ctx["items"].get(iid, {}).get("requirements") or []:
        ty, lv = q.get("type"), q.get("level", q.get("tier", 99))
        if ty == "SKILL" and lv <= SEED_EARLY["skill"]:
            out.append(f"{q.get('skill', '?').lower()} {lv}")
        elif ty == "SLAYER" and lv <= SEED_EARLY["slayer"]:
            out.append(f"{q.get('slayer_boss_type', '?')} slayer {lv}")
        elif ty == "DUNGEON_SKILL" and lv <= SEED_EARLY["cata"]:
            out.append(f"catacombs {lv}")
        elif ty == "HEART_OF_THE_MOUNTAIN" and lv <= SEED_EARLY["hotm"]:
            out.append(f"hotm {lv}")
    return out


def seed_materials(rows, ctx, report):
    """Mark the progression-gating materials: "seed" = units per Merchant Guild drop (enough for the biggest
    early craft that needs it, clamped), "seedFor" = up to three of the gated items it unlocks (display only)."""
    by = {r["id"]: r for r in rows}
    roots = set(ctx["roots"]) | set(ROOT_ALIAS) | set(MERGE)
    recipes = ctx["recipes"]

    def bases(i, mult, depth, acc, seen):
        # unwrap a crafted ingredient down to the drops it is made of (Revenant Viscera -> Revenant Flesh)
        if i in seen or depth > 4:
            return
        rs = [r for r in recipes.get(i, []) if r[0] in ("crafting", "forge")]
        if not rs or i in roots:
            acc[i] = acc.get(i, 0) + mult
            return
        kind, ins, cnt = rs[0]
        for j, a in ins.items():
            if j in ("SKYBLOCK_COIN", "COINS"):
                continue
            bases(j, mult * a / max(1, cnt), depth + 1, acc, seen | {i})

    unlocks = collections.defaultdict(set)
    need = collections.defaultdict(float)
    for out in sorted(recipes):
        if not early_gate(out, ctx):
            continue
        for kind, ins, cnt in recipes[out]:
            if kind not in ("crafting", "forge"):
                continue
            acc = {}
            for j, a in ins.items():
                bases(j, a, 1, acc, {out})
            for b, a in acc.items():
                if b in by:
                    unlocks[b].add(out)
                    need[b] = max(need[b], a)
            break
    cand = []
    for i, outs in unlocks.items():
        r = by[i]
        if i in roots or r.get("mm") is False or r.get("mmSides") == "bid" or r["category"] == "ENCHANTMENTS":
            continue
        if not (SEED_MIN_REF <= r["ref"] <= SEED_MAX_REF) or r["weekly"] < SEED_MIN_WEEKLY:
            continue
        cand.append((-len(outs), i))
    cand.sort()
    for _, i in cand[:SEED_TOP]:
        r = by[i]
        r["seed"] = int(max(SEED_BATCH_MIN, min(SEED_BATCH_MAX, math.ceil(need[i]))))
        r["seedFor"] = sorted(unlocks[i])[:3]
        report.append(f"{i}: SEED x{r['seed']} per drop, gates {len(unlocks[i])} early items e.g. {', '.join(r['seedFor'])}")


def reference_price(bid, ask, npc, shop):
    """A fair mid price from a snapshot that is often thin or absurd (5x spreads on rare items).

    Rules (research 05 s2.9): mid of the book when the spread is sane; when it is not, trust the
    side with the smaller number moved 10% toward the other (a single absurd ask is common, an
    absurd bid is rare); floor at NPC sell x 1.05; ceiling at the NPC shop price (an item an NPC
    sells can never be worth more than the shop asks)."""
    note = None
    if bid > 0 and ask > 0:
        if ask <= bid * 1.5:
            ref = (bid + ask) / 2
        else:
            ref = bid * 1.1 if bid > 0 else ask
            note = f"wide snapshot spread bid {bid} ask {ask} -> {ref:.1f}"
    elif ask > 0:
        ref = ask * 0.9
        note = f"no bids; ask {ask} -> {ref:.1f}"
    elif bid > 0:
        ref = bid * 1.1
        note = f"no asks; bid {bid} -> {ref:.1f}"
    else:
        ref = max(npc * 1.5, 1.0)
        note = f"empty snapshot -> {ref:.1f} from npc {npc}"
    if npc > 0 and ref < npc * 1.05:
        note = (note or "") + f" floored to npc {npc}x1.05"
        ref = npc * 1.05
    if shop and ref > shop and shop >= npc * 1.05:
        note = (note or "") + f" capped at shop price {shop}"
        ref = shop
    return max(ref, 0.2), note


if __name__ == "__main__":
    main()
