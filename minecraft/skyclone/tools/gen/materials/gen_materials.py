"""Generate the materials module's data: the fallback catalogue of generic materials, vanilla-derived
resources and every ENCHANTED_* / compacted chain, plus their compression recipes.

Outputs (plugin/src/main/resources/data/materials/):
  items_<family>.json   arrays of ItemDef (families: farming, mining, combat, foraging, fishing,
                         blocks, misc) - registered by MaterialsModule in postEnable, only if absent
  recipes.json          Recipe array (CRAFTING) for every material the catalogue registers
  catalogue.json        {families:{id:[item ids]}, chains:{base:[next...]}} for the menus
Report: tools/gen/materials/report.txt (what was skipped and why).

Sources: research/data/items.json (id, material, tier, price), research/data/neu-repo/items
(recipes, unlock text, modern item models, skull textures), research/data/collections.json
(collection keys + recipe unlock tiers), research/data/bazaar.json (bazaar catalogue tag).

Descriptions are NOT copied: every lore line is written here from mechanics (what the item is made
from, what it compresses into, where it comes from). Proper-noun renames come from RENAMES below and
are mirrored into docs/names/materials.md.

Run:  py -3 tools/gen/materials/gen_materials.py
"""
import glob
import json
import os
import re
from collections import Counter, defaultdict, OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
D = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "materials")
NAMES_DOC = os.path.join(ROOT, "docs", "names", "materials.md")
REPORT = os.path.join(HERE, "report.txt")

VALID = {l.strip() for l in open(os.path.join(HERE, "item_types.txt"), encoding="utf8") if l.strip()}

report = []


def log(*a):
    report.append(" ".join(str(x) for x in a))


def L(p):
    return json.load(open(os.path.join(D, p), encoding="utf8"))


def colon(i):
    m = re.match(r"^(.*)-(\d+)$", i)
    return m.group(1) + ":" + m.group(2) if m else i


def clean_name(s):
    s = re.sub(r"§.", "", s)
    s = re.sub(r"%%[a-z_]+%%", "", s)
    s = s.replace("�", "").replace("™", "").replace("®", "")
    s = re.sub(r"[-]", "", s)
    return re.sub(r"\s+", " ", s).strip()


# ---- what a vanilla-derived item IS, from its modern material ----
# The "blocks" family is where every vanilla item with no collection lands (Stick, Bread, potions,
# buckets, music discs...), so a single family line there called 304 items "a common building block".
# This answers per item from the 26.1.2 material name; an exact name wins over a pattern, and None
# means "no opinion" (the caller falls back to the family line, which is right for real blocks).
VANILLA_EXACT = {
    # food
    "APPLE": "Food. Eat it to restore hunger.",
    "BAKED_POTATO": "Food. Eat it to restore hunger.",
    "BREAD": "Food. Eat it to restore hunger.",
    "COOKIE": "A quick snack. Eat it to restore hunger.",
    "PUMPKIN_PIE": "Food. Eat it to restore hunger.",
    "MUSHROOM_STEW": "A hearty stew. Eat it to restore hunger.",
    "RABBIT_STEW": "A hearty stew. Eat it to restore hunger.",
    "CAKE": "A whole cake. Place it and take a slice at a time.",
    "GOLDEN_APPLE": "A golden apple. Eating it grants Regeneration and Absorption.",
    "ENCHANTED_GOLDEN_APPLE": "A rare enchanted apple with powerful healing effects.",
    "MILK_BUCKET": "A bucket of milk. Drinking it clears every potion effect.",
    # brewing
    "POTION": "A bottle of water, the base of every potion.",
    "GLASS_BOTTLE": "An empty bottle. Fill it with water to start brewing.",
    "BREWING_STAND": "A brewing station for turning bottles into potions.",
    "CAULDRON": "A large pot that holds water, lava or powder snow.",
    "FERMENTED_SPIDER_EYE": "Brewed into a potion, it turns the effect into its opposite.",
    "GOLDEN_CARROT": "A carrot wrapped in gold. Filling food, and brewed into Night Vision.",
    "SUGAR": "Sweet and fast. Brewed into Swiftness, and baked into cakes.",
    # liquids
    "BUCKET": "An empty bucket for carrying water, lava and milk.",
    "WATER_BUCKET": "A bucket of water. Pour it out to place a water source.",
    "LAVA_BUCKET": "A bucket of lava. Pour it out to place a lava source.",
    # crafting staples
    "STICK": "A basic crafting material for tools, torches and fences.",
    "BOOK": "A book. Used for enchanting and bookshelves.",
    "WRITABLE_BOOK": "A book you can write in.",
    "PAPER": "A sheet of paper. Used for books, maps and rockets.",
    "BOWL": "An empty bowl for stews and soups.",
    "GOLD_NUGGET": "A small piece of gold. Nine make a gold ingot.",
    # utility items
    "COMPASS": "A compass. Its needle points toward spawn.",
    "CLOCK": "A clock. Shows the time of day.",
    "MAP": "An empty map. Use it to chart the area around you.",
    "FLINT_AND_STEEL": "Strikes a spark to light fires and portals.",
    "CARROT_ON_A_STICK": "Steers a saddled pig.",
    "LEAD": "A leash for leading animals around.",
    "SADDLE": "A saddle for riding horses and pigs.",
    "FIREWORK_ROCKET": "A firework rocket. Launch it for a show.",
    "FIREWORK_STAR": "A firework star. Adds colour and shape to a rocket.",
    "ENDER_EYE": "A pearl infused with blaze powder. Thrown, it flies toward a stronghold.",
    "NETHER_STAR": "A star dropped by a powerful boss. Used to craft a beacon.",
    "DRAGON_EGG": "A rare trophy egg left behind by a dragon.",
    "ARMOR_STAND": "A stand for displaying armour.",
    "ITEM_FRAME": "A frame for displaying an item on a wall.",
    "PAINTING": "A decorative painting for your walls.",
    "BARRIER": "An invisible, unbreakable barrier block.",
    "END_PORTAL_FRAME": "A frame block from an ancient portal.",
    # workstations and storage
    "CRAFTING_TABLE": "A crafting table with a full 3x3 grid.",
    "FURNACE": "Smelts ores and cooks food.",
    "ANVIL": "Repairs, renames and combines items.",
    "ENCHANTING_TABLE": "Enchants tools, weapons and armour.",
    "JUKEBOX": "Plays music discs.",
    "CHEST": "A chest for storing items.",
    "TRAPPED_CHEST": "A chest that gives off a redstone signal when opened.",
    "ENDER_CHEST": "A chest whose contents follow you to every ender chest.",
    "BOOKSHELF": "A shelf of books. Placed near an enchanting table it unlocks stronger enchantments.",
    "LADDER": "A ladder you can climb.",
    "TORCH": "A torch. Lights up the area around it.",
    "SKELETON_SKULL": "A skeleton's skull. Wear it or place it as decoration.",
    # redstone
    "REPEATER": "A redstone component that delays and repeats a signal.",
    "COMPARATOR": "A redstone component that compares and measures signals.",
    "LEVER": "A redstone switch you flip on and off.",
    "DAYLIGHT_DETECTOR": "Gives off a redstone signal in daylight.",
    "PISTON": "A redstone component that pushes blocks.",
    "STICKY_PISTON": "A redstone component that pushes and pulls blocks.",
    "DISPENSER": "Fires or uses the items inside it when powered.",
    "DROPPER": "Drops the items inside it when powered.",
    "HOPPER": "Moves items into and out of containers.",
    "REDSTONE_TORCH": "A redstone power source shaped like a torch.",
    "REDSTONE_LAMP": "A lamp that lights up when powered by redstone.",
    "TRIPWIRE_HOOK": "Hooks a tripwire that triggers redstone.",
    "NOTE_BLOCK": "Plays a note when hit or powered.",
    "TNT": "An explosive block. Light it and stand back.",
    # plants
    "DEAD_BUSH": "A dry, dead shrub.",
    "SHORT_GRASS": "A tuft of tall grass.",
    "FERN": "A small fern.",
    "VINE": "A climbing vine that grows down walls.",
}

VANILLA_PATTERNS = [
    (r"^MUSIC_DISC_", "A music disc. Plays a song in a jukebox."),
    (r"_BUCKET$", "A filled bucket. Pour it out to place what is inside."),
    (r"^COOKED_", "Cooked food. Eat it to restore hunger."),
    (r"_SEEDS$", "Seeds. Plant them on farmland."),
    (r"_SAPLING$", "A sapling. Plant it to grow a tree."),
    (r"_DYE$", "A dye for colouring wool, glass, beds and more."),
    (r"_HORSE_ARMOR$", "Armour for a horse."),
    (r"^(TNT_|HOPPER_|CHEST_|FURNACE_)MINECART$|^MINECART$", "A minecart. Rides along rails."),
    (r"(^|_)RAIL$", "A rail for minecarts to ride on."),
    (r"_BOAT$", "A boat for crossing water."),
    (r"_TRAPDOOR$", "A trapdoor you can open and close."),
    (r"_DOOR$", "A door you can open and close."),
    (r"_FENCE_GATE$", "A gate in a fence that you can open and close."),
    (r"_BUTTON$", "A redstone button that sends a short pulse."),
    (r"_PRESSURE_PLATE$", "Gives off a redstone signal when stepped on."),
    (r"_BANNER$", "A decorative banner. Hang it up or place it on the ground."),
    (r"_BED$", "A bed. Sleep through the night."),
    (r"_SIGN$", "A sign you can write on."),
    (r"_CARPET$", "A thin decorative floor covering."),
    (r"_LEAVES$", "Leaves from a tree."),
    (r"_STAIRS$", "Stairs for building."),
    (r"_SLAB$", "A half-height building block."),
    (r"_FENCE$|^IRON_BARS$", "A fence for walling off an area."),
    (r"_WALL$", "A wall for building."),
    (r"_GLASS_PANE$|^GLASS_PANE$", "A thin pane of glass for windows."),
]


def vanilla_blurb(material, iid=""):
    """One plain line saying what a vanilla-derived item is, or None when there is nothing better
    to say than its family line (a plain block, an ore, a flower)."""
    if iid == "GOLDEN_APPLE:1":
        material = "ENCHANTED_GOLDEN_APPLE"
    if material in VANILLA_EXACT:
        return VANILLA_EXACT[material]
    for pat, text in VANILLA_PATTERNS:
        if re.search(pat, material):
            return text
    return None


# Misc items (no collection, not vanilla) used to share one line, "A crafting material." (422 of them, playtest
# 2026-10-03). Say what KIND of thing each one is from the words of its id/name; the first match wins, so the
# specific patterns sit above the broad ones. Our own words - never the reference description.
MISC_KINDS = [
    (r"UPGRADE_STONE", "An upgrade stone. Raises a pet to the next rarity."),
    (r"FOSSIL|^HELIX$|^SPINE_|^CLAW_FOSSIL|^CLUBBED|^UGLY_FOSSIL|^WEBBED", "A fossil dug out of the deep caves."),
    (r"_FRAGMENT$|^FRAGMENT_|_FRAGMENT_", "A boss fragment. Collect enough to craft its gear."),
    (r"^(ROUGH|FLAWED|FINE|FLAWLESS|PERFECT)_[A-Z]+_GEM$", "A gemstone. Socket it into gear for stats."),
    (r"^LAMP_", "A decorative lamp for your island."),
    (r"_BOX(_|$| )|GIFT|PRESENT", "A gift box. Open it and see."),
    (r"^BUILDER_", "A decoration for your island builds."),
    (r"^ORGANIC_", "Organic produce, raised without fertiliser."),
    (r"_PRIZE$|GRATITUDE|RECOMMENDATION", "A reward given for a job well done."),
    (r"_BAIT$|^BAIT_|CHUM", "Fishing bait. Attracts sea creatures."),
    (r"CANDY|GUMMY|JAWBREAKER|LOLLIPOP|TREAT|COOKIE|CHOCOLATE|CAKE|PIE$|SOUP|STEW|OMELETTE|BREAD|FRIES|ESCARGOT"
     r"|DUMPLING|SUSHI|BURGER|^BUILDER_(APPLE|LEMON|ONION)", "Something to eat. Some folk will trade for it."),
    (r"POTION|BREW|ELIXIR|TONIC|PARFUM|PERFUME|DRINK|SODA|TANKARD|FLASK", "A drink or brew in a sealed container."),
    (r"FUEL|CANISTER|COAL|^INFERNO_|SCORCHER", "Fuel. Keeps machines and minions running."),
    (r"FERTILIZER|COMPOST|DUNG|MULCH|MEAL$", "Fertiliser. Feeds crops and plots."),
    (r"COIN|COUPON|DOUBLOON|TOKEN|TICKET|STOCK|BEZOS|CURRENCY", "A coin or token of trade. Worth keeping."),
    (r"KEY$|_KEY_", "A key. Opens something somewhere."),
    (r"BOOK|JOURNAL|PLANS|DRAFT|POSTCARD|SCROLL|NOTE|LETTER|MAP$", "Written pages. Someone will want to read them."),
    (r"DISC|RECORD|STEREO|RADIO", "Makes noise. Loudly."),
    (r"CORE|ENGINE|MOTOR|PANEL|SWITCH|TRANSMITTER|RADAR|REFLECTOR|INVERTER|APPARATUS|OVERCLOCKER|MOUSEMAT|GFX|FTX"
     r"|CIRCUIT|CHIP|GEAR|COG|PISTON|ROBOT|MECH", "A machine part. Salvaged or built."),
    (r"ALLOY|PLATE|INGOT|COPPER|IRIDIUM|TUNGSTEN|UMBER|TITANIUM|METAL|CERAMIC|SCRAP|BUCKLE", "Refined metal or hard stock."),
    (r"GEM|JEWEL|CRYSTAL|DIAMOND|LOTUS", "A precious stone. Valued by crafters."),
    (r"TOOTH|TEETH|FANG|CLAW|PINCER|TUSK|HORN|BONE|SKULL|HEAD$|SPINE", "A trophy taken from a creature."),
    (r"SHELL|SCALE|SCUTE|PELT|SKIN|HIDE|SHED|LEATHER|MEMBRANE|FUR|WOOL", "A tough hide or shell from a creature."),
    (r"FEATHER", "A feather, light as air."),
    (r"(^|_)EGG(_|$| )", "An egg. Best not to ask what hatches."),
    (r"BUG|FLY$|WORM|LEECH|LARVA|WASP|MOSQUITO", "A creepy-crawly. Still wriggling."),
    (r"PILL|EYEDROPS|EXTRACT|POLISH|MOONDEW|DEW$", "A tincture or remedy. Handle with care."),
    (r"SILK|WEB|YARN|STRING|CLOTH|FIBER|NETTING|SINEW|ROPE|THREAD", "A length of strong fibre."),
    (r"FLESH|MEAT|TENTACLE|BRAIN|HEART|LEGS|EYE$|VISCERA|BLOOD|BAT$|PIGEON|CHICKEN|MOUSE", "Something organic. Better not ask."),
    (r"DUST|POWDER|SOOT|ASH$|RESIDUE|SAND$|GLITTER", "A fine powder. Crafters sprinkle it into recipes."),
    (r"SEED|SPORE|SAPLING|BULB|BUSH|CROP|FLOWER|ROSE|BLOOM|LILAC|PETAL|LEAF|LEAVES|VINE|PLANT|BERRY|FRUIT|CACTUS"
     r"|APPLE|MUSHROOM|SHROOM|FUNGUS|CAP$|ALOE|CORNUCOPIA|BEAN|WHEAT|FLOUR|CARROT|POTATO|PUMPKIN|MELON|CANE|WART|COCOA",
     "Grown, picked or gathered from the wild."),
    (r"PEARL|ORB|SPHERE|OVOID|ATOM|ESSENCE|SOUL|PLASMA|FLAME|EMBER|FIRE|CATALYST|MAGIC|ARCANE|STAR|VOID|NULL",
     "Crackles with stored magic."),
    (r"(^|_)ICE(_| |$)|FROZEN|SNOW|FROST|CRYO|GLACIAL|GLACITE", "Ice-cold to the touch."),
    (r"BOTTLE|WATER|VIAL|JUICE|DISTILLATE|JAR|OIL|BRINE|BUCKET|(^|_)INK(_| |$)", "A liquid, safely bottled."),
    (r"GEL|GLOOP|OOZE|JELLY|SLIME|GOO", "Sticky. Very sticky."),
    (r"FISH|SHARK|EEL|SQUID|CRAB|SHRIMP|LOBSTER|FROG|TURTLE", "Pulled from the water."),
    (r"ROCK|PEBBLE|STONE|ORE|MINERAL|BOULDER|CHUNK|BRICK|GLASS|CLAY", "Dug, quarried or broken off something hard."),
    (r"WOOD|BARK|LOG|STICK|ROOT|STEM|BRANCH|PLANK|HANDLE|HILT|POINTER|FLUTE", "Shaped from wood."),
    (r"SWORD|AXE|BLADE|BOW( |$)|HOOK|NEEDLE|SHOVEL|HOE|PICK|DRILL|HAMMER|FORK|SYPHONER|NET$", "A tool or blade, not much use as a weapon."),
    (r"FIREWORK|BALL|TOY|SCRUBBER|SCAFFOLD|INCENSE|CANDLE|FRISBEE|RUG$|HOODIE|TEACUP|PEN$|PAINTING|BOUQUET|CUBE$|IGLOO|DJ_SET|SATCHEL|SLEEVE|FINGERNAILS", "A curiosity. Somebody somewhere wants one."),
]


# wave 5 (2026-10-06): the 32 misc items whose names say nothing about what they are (MISC_KINDS had no opinion),
# each described by hand. Our own words; never the reference description.
MISC_MANUAL = {
    "AGGOURDIAN": "A snarling gourd that turns up in pumpkin patches during the harvest feast.",
    "AMALGAMATED_CRIMSONITE": "The old, cruder fusion of crimson ores. Still traded, rarely made.",
    "ATTRIBUTE_SHARD": "A splinter of a creature's essence, waiting to be fused onto gear.",
    "CHEWED_GUM": "Somebody already chewed it. Somebody else still wants it.",
    "CRUXMOTION": "A crux that will not stop moving, even in a closed hand.",
    "DAY_SAVER": "Holds back the night on your island. Pairs with a Night Saver.",
    "NIGHT_SAVER": "Holds back the day on your island. Pairs with a Day Saver.",
    "DIRT:2": "Dark forest-floor soil. Mushrooms love it.",
    "FERMENTO": "A fizzing, fermented lump that rarely drops from Garden crops.",
    "GUNTHESIZER_LICHEN": "A tough lichen scraped off a racetrack wall.",
    "HASTE_BLOCK": "Stand on it to feel your feet get lighter. An island block.",
    "SILENCE_BLOCK": "Hushes every creature on your island. An island block.",
    "HIGHLITE": "A shard of time that ran ahead of itself.",
    "LUSH_LILY_PAD": "A juicy lily pad. A certain critter would love a nibble.",
    "MITHRIL_GOURMAND": "A perfectly round, oddly appetising ball of mithril.",
    "MITHRIL_TECH": "Clever little contraptions machined out of mithril.",
    "OVERCHEWED_DETRITUS": "What is left when something has been chewed far too long.",
    "PRICKLY_CREEPER": "A spiky, mildly explosive shrub. Fuse it onto armour for sharper Thorns.",
    "PROTEIN_BAR": "Half of a protein bar. Nobody admits to eating the other half.",
    "REFINED_DARK_CACAO_TRUFFLE": "A dark, bitter truffle. Cocoa farmers swear by it.",
    "SHINIEST": "A gleaming green trinket said to match a dragon's scales.",
    "SPONGE:1": "A sponge soaked through. Dry it in a furnace to use it again.",
    "TNT_CHARGE": "A shaped charge strong enough to hurt a sea titan.",
    "VERY_SCIENTIFIC_PAPER": "Peer-reviewed by its own author. Very scientific indeed.",
    "WARMED_FLAKES": "Warm flakes shed by magmatic creatures. Cooks make stew from them.",
    "WEATHER_NODE": "Lets you choose the weather on your island. An island block.",
    "WISHING_COMPASS": "It does not point north. It points at what you want most.",
    "ZOG_ANVIL": "A small anvil for upgrading pet items.",
}

# wave 5: consumables the catalogue registers that no other module wires. Each gets a real ability (handled by
# mod/materials/Consumables.java while materials owns the def) and lore that says what it does.
# id -> (description line, ability id, ability name, trigger, ability lore, requirements)
CONSUMABLES = {
    "CHYME": ("Half-digested goo. Pets find it irresistible.", "materials_chyme", "Pet Snack", "RIGHT_CLICK",
              ["&7Feed it to your active pet for", "&a+90,000 &7pet experience."], []),
    "FILET_O_FORTUNE": ("A lucky fish fillet. Miners swear by it.", "materials_filet_o_fortune", "Lucky Lunch",
                        "RIGHT_CLICK", ["&7Eat it to gain &6+20☘ Mining Fortune", "&7for &a60m&7."], ["hotm:4"]),
    "REAPER_PEPPER": ("Far too hot to be safe.", "materials_reaper_pepper", "Burning Bite", "RIGHT_CLICK",
                      ["&7Permanently gain &c+1❤ Health &7and", "&b+1❄ Cold Resistance&7.",
                       "&8You can stomach up to 5."], []),
    # wave-5 integration (chocolate request 6): the handler is chocolate's (ability id `time_warp`, persistent 48 h
    # cooldown in the profile). Only the factory reads it here, so the lore names only the factory.
    "ENCHANTED_TIME_CLOCK": ("A clock that runs ahead of the world.", "time_warp", "Time Warp", "RIGHT_CLICK",
                             ["&7Advance Time by &d1 hour&7!", "&8Cooldown: &a48 hours", "",
                              "&7Impacts your Chocolate Factory."], ["collection:GOLD_INGOT:10"]),
    "SAVING_GRACE": ("A last-chance charm.", "materials_saving_grace", "Second Chance", "PASSIVE",
                     ["&7Carried in your inventory: when you", "&7would lose coins to a death, it",
                      "&7crumbles instead and sends you", "&7home with your purse intact."], []),
}


def misc_blurb(iid, name, it):
    """What kind of thing a misc item is, from its id/name words; falls back on the research flags."""
    if iid in MISC_MANUAL:
        return MISC_MANUAL[iid]
    if iid in CONSUMABLES:
        return CONSUMABLES[iid][0]
    hay = iid + " " + re.sub(r"[^A-Z ]", "", name.upper()).replace(" ", "_")
    for pat, text in MISC_KINDS:
        if re.search(pat, hay):
            return text
    if it.get("dungeon_item"):
        return "Recovered from the catacombs."
    if it.get("museum"):
        return "A rare find. Worth showing off."
    return None


ROMAN = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100}


def roman(s):
    t, prev = 0, 0
    for ch in reversed(s):
        v = ROMAN[ch]
        t = t - v if v < prev else t + v
        prev = max(prev, v)
    return t


# ---------------------------------------------------------------------------------------------
# Proper-noun renames (display names only; ids never change). Token-level replacements applied
# to every display name; whole-name overrides win. Generic words stay generic (CONTRACT §1).
# ---------------------------------------------------------------------------------------------
RENAME_WHOLE = {
    # id: our name
    # the reference renamed it "Shredded Sinew" in 2026 - our old name - so ours moved on (slayer audit C8; slayer
    # registers it first, this keeps the fallback in step)
    "SHARD_OF_THE_SHREDDED": "Shredded Tendon",
    "ARACHNE_KEEPER_FRAGMENT": "Weaver's Calling",
    "ARACHNE_FANG": "Broodmother's Fang",
    "ARACHNE_CRYSTAL": "Broodmother Crystal",
    "ARACHNE_FRAGMENT": "Broodmother Fragment",
    "BONZO_FRAGMENT": "Jester Fragment",
    "SCARF_FRAGMENT": "Necromancer Fragment",
    "THORN_FRAGMENT": "Bramble Fragment",
    "LIVID_FRAGMENT": "Mirror Fragment",
    "DIVAN_FRAGMENT": "Sultan Fragment",
    "DIVAN_ALLOY": "Sultan's Alloy",
    "DIVAN_POWDER_COATING": "Sultan's Powder Coating",
    "DIVER_FRAGMENT": "Abyssal Emperor's Skull",
    "GIANT_FRAGMENT_BIGFOOT": "Strider's Bola",
    "GIANT_FRAGMENT_BOULDER": "Rosy Boulder",
    "GIANT_FRAGMENT_DIAMOND": "Sparkling Handle",
    "GIANT_FRAGMENT_LASER": "Beam Giant's Eye",
    "MAGMA_LORD_FRAGMENT": "Molten Lord Fragment",
    "LEECH_SUPREME_FRAGMENT": "Supreme Leech Fragment",
    "MIMIC_FRAGMENT": "Mimic Fragment",
    "KUUDRA_TEETH": "Leviathan Teeth",
    "KUUDRA_TENTACLE": "Leviathan Tentacle",
    "UNCOMMON_KUUDRA_CHUNK": "Leviathan Chunk",
    "RARE_KUUDRA_CHUNK": "Meaty Leviathan Chunk",
    "EPIC_KUUDRA_CHUNK": "Hardened Leviathan Chunk",
    "LEGENDARY_KUUDRA_CHUNK": "Enriched Leviathan Chunk",
    "REVENANT_FLESH": "Ghoul Flesh",
    "REVENANT_VISCERA": "Ghoul Viscera",
    "REVENANT_CATALYST": "Ghoul Catalyst",
    "TARANTULA_WEB": "Broodweaver Web",
    "TARANTULA_SILK": "Broodweaver Silk",
    "TARANTULA_CATALYST": "Broodweaver Catalyst",
    "GRIFFIN_FEATHER": "Gryphon Feather",
    "ENCHANTED_GRIFFIN_FEATHER": "Enchanted Gryphon Feather",
    "BRAIDED_GRIFFIN_FEATHER": "Braided Gryphon Feather",
    "BASE_GRIFFIN_UPGRADE_STONE": "Base Gryphon Upgrade Stone",
    "GRIFFIN_UPGRADE_STONE_UNCOMMON": "Uncommon Gryphon Upgrade Stone",
    "GRIFFIN_UPGRADE_STONE_RARE": "Rare Gryphon Upgrade Stone",
    "GRIFFIN_UPGRADE_STONE_EPIC": "Epic Gryphon Upgrade Stone",
    "GRIFFIN_UPGRADE_STONE_LEGENDARY": "Legendary Gryphon Upgrade Stone",
    "GRIFFIN_UPGRADE_STONE_MYTHIC": "Mythic Gryphon Upgrade Stone",
    "MYTHOS_FRAGMENT": "Legend Fragment",
    "JACOBS_TICKET": "Harvest Fair Ticket",
    "JACOBS_PARTICIPATION_MEDAL": "Harvest Fair Participation Medal",
    "KAT_FLOWER": "Pet Sitter's Flower",
    "KAT_BOUQUET": "Pet Sitter's Bouquet",
    "AGATHA_COUPON": "Herbalist's Coupon",
    "STARLYN_PRIZE": "Herbalist's Prize",
    "MIRIA_COUPON": "Curator's Coupon",
    "MIRIA_PRIZE": "Curator's Prize",
    "FINNS_FOCACCIA": "Fisher's Focaccia",
    "SAULS_RECOMMENDATION": "Captain's Recommendation",
    "DARK_QUEENS_SOUL_DROP": "Shadow Queen's Soul",
    "PRINCE_CROWN_JEWEL": "Prince's Crown Jewel",
    "WILSON_ENGINEERING_PLANS": "Tinker's Engineering Plans",
    "EINARY_RED_HOODIE": "Courier's Red Hoodie",
    "BARRY_PEN": "Clerk's Fancy Pen",
    "TALBOTS_THEODOLITE": "Surveyor's Theodolite",
    "WRAPPED_GIFT_FOR_JULIETTE": "Secret Gift for a Friend",
    "EMMETT_POINTER": "Professor's Pointer",
    "MATRIARCH_PARFUM": "Matriarch's Perfume",
    "HORSEMAN_CANDLE": "Headless Rider's Candle",
    "GOLEM_POPPY": "Ancient Rose",
    "BEZOS": "Tycoon's Coin",
    "STOCK_OF_STONKS": "Stock of Gains",
    "FTX_3070": "GFX 3070",
    "UBIKS_CUBE": "Puzzler's Cube",
    "INFLATABLE_JERRY": "Inflatable Snowman",
    "JERRY_CANDY": "Snowman Candy",
    "JERRY_BOX_GREEN": "Green Snowman Box",
    "JERRY_BOX_BLUE": "Blue Snowman Box",
    "JERRY_BOX_PURPLE": "Purple Snowman Box",
    "JERRY_BOX_GOLDEN": "Golden Snowman Box",
    "JERRY_BOX_MEGA": "Mega Snowman Box",
    "MEGA_JERRY_LURE": "Mega Snowman Lure",
    "GOLDOR_THE_FISH": "Gilded Warden the Fish",
    "MAXOR_THE_FISH": "Storm Warden the Fish",
    "CRUDE_GABAGOOL": "Crude Gourmet Meat",
    "CRUDE_GABAGOOL_DISTILLATE": "Gourmet Meat Distillate",
    "VERY_CRUDE_GABAGOOL": "Very Crude Gourmet Meat",
    "HEAVY_GABAGOOL": "Heavy Gourmet Meat",
    "FUEL_GABAGOOL": "Fuel Gourmet Meat",
    "HYPERGOLIC_GABAGOOL": "Hypergolic Gourmet Meat",
    "RAW_SOULFLOW": "Raw Soulstream",
    "SOULFLOW": "Soulstream",
    "SOULFLOW_ENGINE": "Soulstream Engine",
    "LESSER_SOULFLOW_ENGINE": "Lesser Soulstream Engine",
    "REFINED_BOTTLE_OF_JYRRE": "Refined Bottle of Midnight",
    "VOLTA": "Volta",
    "CORLEONITE": "Gangsterite",
    "DAEDALUS_STICK": "Labyrinth Stick",
    "WIZARD_BREADCRUMBS": "Wizard's Breadcrumbs",
    "SIRIUS_PERSONAL_PHONE_NUMBER": "Star Merchant's Phone Number",
    "AATROX_PHONE_NUMBER": "Hunter's Phone Number",
    "DIANAS_BOOKSHELF": "Huntress's Bookshelf",
    "THE_WATCHERS_HEAD": "The Overseer's Head",
    "OLD_FRAGMENT": "Old Wyrm Fragment",
    # wave 5 (2026-10-06): names other modules already ship (docs/names/garden.md, pets.md, shops.md), so the
    # catalogue copy and every "Used to craft" line agree with what the player is handed.
    "FERMENTO": "Fermenta",
    "ZOG_ANVIL": "Tamer's Anvil",
    "GUNTHESIZER_LICHEN": "Racer's Lichen",
    # wave 5 (shops request 9): 1.8 block names that are now a DIFFERENT modern block. A buyer handed
    # "Smooth Sandstone" got Cut Sandstone, next to the real Smooth Sandstone; name the block they get.
    "SANDSTONE:2": "Cut Sandstone",
    "RED_SANDSTONE:1": "Chiseled Red Sandstone",
    "RED_SANDSTONE:2": "Cut Red Sandstone",
    "STEP": "Smooth Stone Slab",
}

# wave 5: legacy (material, durability) pairs the learned table got wrong. (SKULL_ITEM, 3) is how the reference
# draws BAMBOO's 1.8 head, so the plain Player Head came out as bamboo; the wither/dragon skulls and the enchanted
# apple only had the base item. A vanilla pickup of these converts INTO the catalogue id, so the look must match.
MATERIAL_FIX = {
    "SKULL_ITEM:1": "WITHER_SKELETON_SKULL",
    "SKULL_ITEM:3": "PLAYER_HEAD",
    "SKULL_ITEM:5": "DRAGON_HEAD",
    "GOLDEN_APPLE:1": "ENCHANTED_GOLDEN_APPLE",
}

# Whole-word token replacements (case-sensitive) for anything not covered above.
RENAME_TOKENS = OrderedDict([
    ("Kuudra", "Leviathan"),
    ("Jerry", "Snowman"),
    ("Arachne", "Broodmother"),
    ("Revenant", "Ghoul"),
    ("Tarantula", "Broodweaver"),
    ("Sven", "Packleader"),
    ("Voidgloom", "Voidwraith"),
    ("Griffin", "Gryphon"),
    ("Jacob's", "Harvest Fair"),
    ("Jacob", "Harvest Fair"),
    ("Gabagool", "Gourmet Meat"),
    ("Soulflow", "Soulstream"),
    ("Divan's", "Sultan's"),
    ("Divan", "Sultan"),
    ("Bonzo", "Jester"),
    ("Livid", "Mirror"),
    ("Necron", "Undying Warden"),
    ("Goldor", "Gilded Warden"),
    ("Maxor", "Storm Warden"),
    ("Sadan", "Giant King"),
    ("Kat", "Pet Sitter"),
    ("Abiphone", "Ringphone"),
    ("Hypixel", "Sky"),
    ("Agarimoo", "Fungimoo"),
    ("Moogma", "Lavamoo"),
    ("Kada", "Ember Knight"),
    ("Robotron", "Automaton"),
    ("Blazetekk", "Emberworks"),
    ("Saphhire", "Sapphire"),
])

PROPER_HINTS = list(RENAME_TOKENS.keys())


def rename(iid, name):
    if iid in RENAME_WHOLE:
        return RENAME_WHOLE[iid]
    out = name
    for a, b in RENAME_TOKENS.items():
        out = re.sub(r"\b" + re.escape(a) + r"\b", b, out)
    return out


# ---------------------------------------------------------------------------------------------
# Which items the catalogue covers
# ---------------------------------------------------------------------------------------------
EXCLUDE_PATTERNS = [
    r"^CANCEL_", r"_ABILITY(_\d+)?$", r"_DUNGEON_ABILITY_", r"_PERK_ITEM$", r"_BACKPACK$", r"^ABIPHONE",
    r"^ABINGOPHONE", r"_ISLAND$", r"_PERSONALITY$", r"_THE_FISH(_\d+)?$", r"_BIOME_STICK$", r"_SHOP_ITEM$",
    r"^DUNGEON_", r"^KEY_[A-Z]$", r"_TALISMAN_BAG$", r"_POTION_BAG$", r"ENCHANTED_CHEST$", r"ENCHANTED_CHEST_UPGRADE$",
    r"^MIRRORED_", r"^LEAVE_CHALLENGE", r"^PARKOUR_", r"_POWDER_TIER_\d$", r"^SKYBLOCK_MENU$", r"^UNKNOWN_ITEM$",
    r"^[XYZ]$", r"^SWAPPABLE_PREVIEW$", r"_ASSISTANT$", r"^SPELL_BOOK_TEST$", r"^WATCHER_(BOOTS|CHESTPLATE|LEGS)$",
    r"PHONE_NUMBER$", r"_CONTACTS_ADDON$", r"_SCYTHE$", r"^HOE_OF_", r"_SWORD$", r"_HELMET$", r"_DRILL_ENGINE$",
    r"_FUEL_TANK$", r"^SECRET_BINGO_CARD$", r"_SKIN$", r"^SOCIAL_DISPLAY$", r"^EGG_HUNT$", r"^CONNECT_FOUR$",
    r"^TIC_TAC_TOE$", r"^ROCK_PAPER_SHEARS$", r"^SPRAY_CAN$", r"^PLOT_ERASER$", r"_PEST_TRAP$", r"^PEST_TRAP$",
    r"_VACUUM$", r"^GRAPPLING_HOOK$", r"^TIME_GUN$", r"^PATRICE_SWORD$", r"^HORSEZOOKA$", r"^SNOW_(BLASTER|CANNON|HOWITZER)$",
    r"_ULTIMATE$", r"^WISH_ULTIMATE$", r"^SPIRIT_LEAP$", r"^REVIVE_STONE", r"^LESSER_ORB_OF_HEALING$",
    r"^SUMMONING_RING$", r"^TACTICAL_INSERTION$", r"^NIGHTMARE_NULLIFIER$", r"^DEAD_CAT_DETECTOR$",
    r"^DETECTIVE_SCANNER$", r"^HOSTAGE_COMPASS$", r"^GIFT_COMPASS$", r"^WEATHER_STICK$", r"^SECRET_TRACKER$",
    r"^SOLVED_PRISM$", r"^POEM_OF_INFINITE_LOVE$", r"^VERY_OFFICIAL_YELLOW_ROCK$", r"^ROSE_BOUQUET$", r"^YELLOW_ROCK$",
    r"^FAIRY_SOULS_PAMPHLET$", r"^FAKE_", r"^FULL_FAIRY_CONTROL$", r"^FLOWER_MAELSTROM$", r"_SWAPPER$",
    r"^PORTABLE_", r"^TELEPORT_PAD$", r"^LAUNCH_PAD$", r"^MINION_EXPANDER$", r"^SAM_", r"^REED_BOAT$",
    r"^PILE_OF_CASH$", r"^PET_ITEM_", r"_GIFT$", r"^PARTY_GIFT$", r"^BEACON_\d$", r"^BEACON_SKULL$",
    r"_ORB$", r"^COMPACTOR$", r"^SUPER_COMPACTOR_3000$", r"^DWARVEN_COMPACTOR$", r"_HOPPER$", r"^AUTO_SMELTER$",
    r"_FURNACE$", r"^DIAMOND_SPREADING$", r"^FLYCATCHER_UPGRADE$", r"_UPGRADE$", r"^SOULFLOW_ENGINE$",
    r"^LESSER_SOULFLOW_ENGINE$", r"^SLEEPY_HOLLOW$", r"^TALISMAN_ENRICHMENT_", r"^MAGIC_FOR_PETS$",
    r"_REMOTE$", r"^RAT_JETPACK$", r"^INFINI_TORCH$", r"^BOB_OMB$", r"^SUPERBOOM_TNT$", r"^SPOOKY_DISC$", r"_DISC$",
    r"^TRICK_OR_TREAT_BAG$", r"^SEAL_TREAT_BAG$", r"^THE_CAKE$", r"^PET_CAKE$", r"^SLICE_OF_",
    r"^HAMSTER_WHEEL$", r"^CHAIN_END_TIMES$", r"^BLIZZARD_BOTTLE$", r"^FIRE_IN_A_BOTTLE$", r"^HONEY_JAR$",
    r"^EXPORTABLE_CARROTS$", r"^MINING_RAFFLE_TICKET$", r"^CARNIVAL_TICKET$", r"_PREVIEW$", r"^KISMET_FEATHER$",
    r"^RECOMBOBULATOR_3000$", r"^THE_ART_OF_(WAR|PEACE)$", r"_MASTER_STAR$", r"^WIZARD_BREADCRUMBS$",
    r"^ELLE_", r"^MOON_CHUNK$", r"^VOLCANIC_ROCK$", r"^RED_GIFT$", r"^WARTS_STEW$", r"^FIRE_FLOWER_TOOL$",
    r"^HARPOON_LAUNCHER$", r"^SPORE_HARVESTER$", r"^DEFUSE_KIT$", r"^BOTTLE_OF_NIGHTMARE$", r"^PESTHUNTING_GUIDE$",
    r"^THEORETICAL_HOE$", r"^TOOL_EXP_CAPSULE$", r"^STRETCHING_STICKS$", r"^TRAINING_WEIGHTS$", r"^ARCHERY_CUBE$",
    r"^PRISMAPUMP$", r"^WATER_ORB$", r"^SHIELD_DUST$", r"^GUARDIAN_LUCKY_BLOCK$",
    r"^ASSISTANT$", r"^REVERSE_", r"^TINY_HAMMER$", r"^NEARLY_COHERENT_ROD$", r"_OF_LIVE_RELOADING$",
    r"^TELEPORT_PAD$", r"^SPIRIT_LEAP$",
]

# NEU ingredient ids that items.json spells differently.
# TUNE (integrator round 1): the reference trades 1 log -> 4 leaves (2 vines) while leaves sell for 2 and vines 5,
# so the Trades menu printed coins (crafting.trades-valid). Leaves/vines are priced so a trade never beats its input.
SELL_OVERRIDE = {"LEAVES": 0.5, "LEAVES:1": 0.5, "LEAVES:2": 0.5, "LEAVES:3": 0.5, "LEAVES_2": 0.5, "LEAVES_2:1": 0.5,
                 "VINE": 2, "MOSSY_COBBLESTONE": 3}  # mossy = cobble + vine, kept at its inputs' value

INGREDIENT_ALIAS = {"INK_SACK:15": "BONE_MEAL"}
EXCLUDE_RE = [re.compile(p) for p in EXCLUDE_PATTERNS]

# Items that must be in the catalogue whatever the filters say (chain members / research table).
FORCE_INCLUDE = set()

LEGACY_FALLBACK = {
    "SKULL_ITEM": "PLAYER_HEAD", "RAW_FISH": "COD", "COOKED_FISH": "COOKED_COD", "INK_SACK": "INK_SAC",
    "SULPHUR": "GUNPOWDER", "NETHER_STALK": "NETHER_WART", "MYCEL": "MYCELIUM", "ENDER_STONE": "END_STONE",
    "QUARTZ": "QUARTZ", "LOG": "OAK_LOG", "LOG_2": "ACACIA_LOG", "WOOD": "OAK_PLANKS", "SEEDS": "WHEAT_SEEDS",
    "CARROT_ITEM": "CARROT", "POTATO_ITEM": "POTATO", "PORK": "PORKCHOP", "GRILLED_PORK": "COOKED_PORKCHOP",
    "RAW_BEEF": "BEEF", "RAW_CHICKEN": "CHICKEN", "MUTTON": "MUTTON", "RABBIT": "RABBIT", "WATCH": "CLOCK",
    "SPECKLED_MELON": "GLISTERING_MELON_SLICE", "MELON": "MELON_SLICE", "MELON_BLOCK": "MELON",
    "HUGE_MUSHROOM_1": "BROWN_MUSHROOM_BLOCK", "HUGE_MUSHROOM_2": "RED_MUSHROOM_BLOCK", "WEB": "COBWEB",
    "WATER_LILY": "LILY_PAD", "RED_ROSE": "POPPY", "YELLOW_FLOWER": "DANDELION", "DOUBLE_PLANT": "SUNFLOWER",
    "SNOW_BALL": "SNOWBALL", "EXP_BOTTLE": "EXPERIENCE_BOTTLE", "FIREWORK": "FIREWORK_ROCKET",
    "FIREWORK_CHARGE": "FIREWORK_STAR", "STAINED_CLAY": "WHITE_TERRACOTTA", "HARD_CLAY": "TERRACOTTA",
    "STAINED_GLASS": "WHITE_STAINED_GLASS", "STAINED_GLASS_PANE": "WHITE_STAINED_GLASS_PANE", "WOOL": "WHITE_WOOL",
    "CARPET": "WHITE_CARPET", "THIN_GLASS": "GLASS_PANE", "IRON_FENCE": "IRON_BARS", "SMOOTH_BRICK": "STONE_BRICKS",
    "CLAY_BRICK": "BRICK", "BRICK": "BRICKS", "NETHER_BRICK_ITEM": "NETHER_BRICK", "NETHER_BRICK": "NETHER_BRICKS",
    "WORKBENCH": "CRAFTING_TABLE", "ENCHANTMENT_TABLE": "ENCHANTING_TABLE", "BOOK_AND_QUILL": "WRITABLE_BOOK",
    "MONSTER_EGG": "PIG_SPAWN_EGG", "SAPLING": "OAK_SAPLING", "LEAVES": "OAK_LEAVES", "LEAVES_2": "ACACIA_LEAVES",
    "LONG_GRASS": "SHORT_GRASS", "DEAD_BUSH": "DEAD_BUSH", "GOLD_RECORD": "MUSIC_DISC_13", "GREEN_RECORD": "MUSIC_DISC_CAT",
    "EYE_OF_ENDER": "ENDER_EYE", "GOLD_BARDING": "GOLDEN_HORSE_ARMOR", "IRON_BARDING": "IRON_HORSE_ARMOR",
    "DIAMOND_BARDING": "DIAMOND_HORSE_ARMOR", "LEASH": "LEAD", "BOAT": "OAK_BOAT", "MUSHROOM_SOUP": "MUSHROOM_STEW",
    "STEP": "SMOOTH_STONE_SLAB", "WOOD_STEP": "OAK_SLAB", "FENCE": "OAK_FENCE", "FENCE_GATE": "OAK_FENCE_GATE",
    "WOOD_STAIRS": "OAK_STAIRS", "WOOD_DOOR": "OAK_DOOR", "TRAP_DOOR": "OAK_TRAPDOOR", "SIGN": "OAK_SIGN",
    "BED": "RED_BED", "CAULDRON_ITEM": "CAULDRON", "BREWING_STAND_ITEM": "BREWING_STAND", "SKULL": "SKELETON_SKULL",
    "REDSTONE_TORCH_ON": "REDSTONE_TORCH", "REDSTONE_LAMP_OFF": "REDSTONE_LAMP", "DIODE": "REPEATER",
    "REDSTONE_COMPARATOR": "COMPARATOR", "PISTON_BASE": "PISTON", "PISTON_STICKY_BASE": "STICKY_PISTON",
    "RAILS": "RAIL", "STORAGE_MINECART": "CHEST_MINECART", "POWERED_MINECART": "FURNACE_MINECART",
    "EXPLOSIVE_MINECART": "TNT_MINECART", "WOOD_BUTTON": "OAK_BUTTON", "WOOD_PLATE": "OAK_PRESSURE_PLATE",
    "STONE_PLATE": "STONE_PRESSURE_PLATE", "GOLD_PLATE": "LIGHT_WEIGHTED_PRESSURE_PLATE",
    "IRON_PLATE": "HEAVY_WEIGHTED_PRESSURE_PLATE", "COBBLE_WALL": "COBBLESTONE_WALL", "NETHER_FENCE": "NETHER_BRICK_FENCE",
    "SMOOTH_STAIRS": "STONE_BRICK_STAIRS", "NOTE_BLOCK": "NOTE_BLOCK", "MOSSY_COBBLESTONE": "MOSSY_COBBLESTONE",
    "GRASS": "GRASS_BLOCK", "SOIL": "FARMLAND", "MOB_SPAWNER": "SPAWNER", "ENDER_PORTAL_FRAME": "END_PORTAL_FRAME",
    "COMMAND": "COMMAND_BLOCK", "FISHING_ROD": "FISHING_ROD", "CARROT_STICK": "CARROT_ON_A_STICK",
    "NETHER_WARTS": "NETHER_WART", "POTION": "POTION", "GOLD_SPADE": "GOLDEN_SHOVEL", "IRON_HOE": "IRON_HOE",
}


# The reference game draws most custom items over PAPER with a resource pack we do not ship. For those
# (no skull texture either) pick a vanilla look from the id's words so menus are readable. Custom ids are
# never the vanilla id of their material, so core's vanilla guards still block placing/eating/throwing.
LOOK = [
    (r"FRAGMENT|SHARD", "PRISMARINE_SHARD"), (r"CRYSTAL", "AMETHYST_SHARD"), (r"DUST|POWDER|SOOT", "GLOWSTONE_DUST"),
    (r"FOSSIL|BONE|SPINE|SKULL", "BONE"), (r"CANDY|TREAT|CHOCOLATE|GUM|COOKIE", "COOKIE"),
    (r"COIN|DOUBLOON|TOKEN|TICKET|MEDAL", "GOLD_NUGGET"), (r"KEY", "TRIPWIRE_HOOK"),
    (r"PLATE", "HEAVY_WEIGHTED_PRESSURE_PLATE"), (r"ALLOY|METAL|INGOT|IRIDIUM|TUNGSTEN|UMBER|TITANIUM", "IRON_INGOT"),
    (r"GEM|JEWEL", "EMERALD"), (r"FEATHER|WING", "FEATHER"), (r"SHELL|SCALE|SCUTE|PELT|SKIN|HIDE", "TURTLE_SCUTE"),
    (r"TOOTH|FANG|CLAW|PINCER|TUSK|HORN", "BONE_MEAL"), (r"SILK|WEB|YARN|STRING|CLOTH|FIBER|NETTING|SINEW", "STRING"),
    (r"SEED", "WHEAT_SEEDS"), (r"FLESH|MEAT|TENTACLE|BRAIN", "ROTTEN_FLESH"), (r"HEART|CORE|APPARATUS", "HEART_OF_THE_SEA"),
    (r"PEARL|ORB|EYE", "ENDER_PEARL"), (r"BOTTLE|WATER|VIAL|JUICE|BREW|DISTILLATE|FLASK|JAR|PARFUM|OIL|BRINE", "GLASS_BOTTLE"),
    (r"SOUP|STEW|OMELETTE", "MUSHROOM_STEW"), (r"MUSHROOM|SHROOM|FUNGUS|CAP|SPORE", "RED_MUSHROOM"),
    (r"WOOD|BARK|LOG|STICK|ROOT|STEM|BRANCH", "STICK"), (r"ROCK|PEBBLE|STONE|ORE|MINERAL", "FLINT"),
    (r"ICE|FROZEN|SNOW|FROST|CRYO", "SNOWBALL"), (r"FISH|SHARK|EEL", "COD"), (r"EGG", "EGG"),
    (r"BOOK|JOURNAL|PLANS|DRAFT|POSTCARD|GUIDE|SCROLL", "BOOK"), (r"FLOWER|ROSE|LOTUS|BLOOM|LILAC|PETAL", "POPPY"),
    (r"ASH", "GUNPOWDER"), (r"GEL|GLOOP|OOZE|JELLY|SLIME", "SLIME_BALL"),
    (r"ENGINE|MOTOR|PANEL|SWITCH|TRANSMITTER|RADAR|REFLECTOR|INVERTER|ENHANCER|TECH", "COMPARATOR"),
    (r"CATALYST|ESSENCE|SOUL|PLASMA|FLAME|EMBER|FIRE", "BLAZE_POWDER"), (r"LEAF|LICHEN|VINE|BERBERIS|PLANT", "VINE"),
    (r"BERRY|FRUIT|APPLE", "SWEET_BERRIES"), (r"CARROT", "CARROT"), (r"POTATO", "POTATO"), (r"PUMPKIN", "PUMPKIN"),
    (r"MELON", "MELON_SLICE"), (r"WHEAT|HAY|FLOUR", "WHEAT"), (r"CACTUS", "CACTUS"), (r"WART", "NETHER_WART"),
    (r"BUCKET", "BUCKET"), (r"MAGMA|LAVA", "MAGMA_CREAM"), (r"BUG|FLY|LARVA|WASP|MOSQUITO|WORM|LICE", "SPIDER_EYE"),
]


GEM_LOOK = {"RUBY": "RED_DYE", "AMBER": "ORANGE_DYE", "SAPPHIRE": "LIGHT_BLUE_DYE", "JADE": "LIME_DYE",
            "AMETHYST": "PURPLE_DYE", "TOPAZ": "YELLOW_DYE", "JASPER": "MAGENTA_DYE", "OPAL": "WHITE_DYE",
            "ONYX": "BLACK_DYE", "CITRINE": "BROWN_DYE", "AQUAMARINE": "CYAN_DYE", "PERIDOT": "GREEN_DYE"}
DYE = ["BLACK", "RED", "GREEN", "BROWN", "BLUE", "PURPLE", "CYAN", "LIGHT_GRAY", "GRAY", "PINK", "LIME", "YELLOW",
       "LIGHT_BLUE", "MAGENTA", "ORANGE", "WHITE"]


MANUAL_LOOK = {
    "ENCHANTED_MYCELIUM_CUBE": "MYCELIUM", "ENCHANTED_RED_SAND_CUBE": "RED_SAND", "ENCHANTED_SULPHUR_CUBE": "GLOWSTONE",
    "NULL_SPHERE": "ENDER_PEARL", "NULL_OVOID": "ENDER_EYE", "NULL_ATOM": "ENDER_EYE", "NULL_BLADE": "IRON_SWORD",
    "REFINED_MITHRIL": "PRISMARINE_CRYSTALS", "REFINED_DIAMOND": "DIAMOND", "SEA_LUMIES": "SEA_PICKLE",
    "ENCHANTED_SEA_LUMIES": "SEA_PICKLE", "AMALGAMATED_CRIMSONITE": "NETHERITE_SCRAP",
    "AMALGAMATED_CRIMSONITE_NEW": "NETHERITE_SCRAP", "GLACITE_AMALGAMATION": "PACKED_ICE", "COMPOST": "BONE_MEAL",
    "DUNG": "BROWN_DYE", "CHUM": "ROTTEN_FLESH", "CONDENSED_HELIANTHUS": "SUNFLOWER", "HELIXIS": "TWISTING_VINES",
    "TREASURITE": "GOLD_INGOT", "STARFALL": "NETHER_STAR", "VOLTA": "LIGHTNING_ROD", "YOGGIE": "SUGAR",
    "CORLEONITE": "QUARTZ", "TITANOBOA_SHED": "LEATHER", "INK_SPLAT": "INK_SAC", "KUUDRA_TEETH": "BONE_MEAL",
    "GILL_MEMBRANE": "KELP", "OLD_LEATHER_BOOT": "LEATHER_BOOTS", "WISHING_COMPASS": "COMPASS",
    "ASCENSION_ROPE": "LEAD", "BRONZE_BOWL": "BOWL", "CUP_OF_BLOOD": "RED_DYE", "MITHRIL_GOURMAND": "PRISMARINE_CRYSTALS",
    # finish wave 2026-10-03: the last custom items still drawn as plain PAPER (no skull, no keyword). Letters,
    # stock certificates and Enchanted Paper really are paper and keep it.
    "CHEESE_FUEL": "HONEYCOMB", "CHIRPING_STEREO": "JUKEBOX", "FTX_3070": "COMPARATOR", "SQUEAKY_MOUSEMAT": "BLACK_CARPET",
    "SUSPICIOUS_SCRAP": "IRON_NUGGET", "PRICKLY_KISS": "CACTUS_FLOWER", "BEZOS": "GOLD_NUGGET", "BLUE_RING": "LAPIS_LAZULI",
    "POISON_SAMPLE": "FERMENTED_SPIDER_EYE", "SEARED_ESCARGOT": "NAUTILUS_SHELL", "BUSTED_BELT_BUCKLE": "IRON_CHAIN",
    "DEEPFRIES": "BAKED_POTATO", "FUEL_TANK": "CAULDRON", "MIRIA_PRIZE": "GOLD_INGOT", "STARLYN_PRIZE": "GOLD_INGOT",
    "OVERCLOCKER_3000": "OBSERVER", "RITUAL_RESIDUE": "REDSTONE", "VISITORS_GRATITUDE": "PINK_TULIP",
}


def look_for(iid):
    if iid.split(" ")[0] in MANUAL_LOOK and MANUAL_LOOK[iid.split(" ")[0]] in VALID:
        return MANUAL_LOOK[iid.split(" ")[0]]
    m = re.match(r"^(?:ROUGH|FLAWED|FINE|FLAWLESS|PERFECT)_([A-Z]+)_GEM$|^([A-Z]+)_CRYSTAL$", iid)
    if m and (m.group(1) or m.group(2)) in GEM_LOOK:
        return GEM_LOOK[m.group(1) or m.group(2)]
    for pat, mat in LOOK:
        if re.search(pat, iid) and mat in VALID:
            return mat
    return "PAPER"


def is_excluded(iid):
    return any(r.search(iid) for r in EXCLUDE_RE)


def main():
    items = {i["id"]: i for i in L("items.json")["items"]}
    coll = L("collections.json")["collections"]
    bazaar = set(L("bazaar.json")["products"].keys())
    coll_cat = {}          # item id -> FARMING/MINING/...
    coll_name = {}         # display name -> collection id
    coll_tiers = {}        # id -> [amountRequired...]
    unlock_by_text = {}    # "Enchanted Diamond" -> (collection id, tier)
    for cat, c in coll.items():
        for cid, ci in c["items"].items():
            coll_cat[cid] = cat
            coll_name[clean_name(ci["name"])] = cid
            coll_tiers[cid] = [t["amountRequired"] for t in ci["tiers"]]
            for t in ci["tiers"]:
                for u in t.get("unlocks", []):
                    u = clean_name(u)
                    if u.endswith(" Recipe"):
                        unlock_by_text.setdefault(u[:-7], (cid, t["tier"]))
                    elif u.endswith(" Recipes"):
                        unlock_by_text.setdefault(u[:-8], (cid, t["tier"]))

    neu = {}
    for f in glob.glob(os.path.join(D, "neu-repo", "items", "*.json")):
        try:
            d = json.load(open(f, encoding="utf8"))
        except Exception:
            continue
        neu[colon(d.get("internalname", ""))] = d

    # legacy (material, durability) -> modern material, learned from NEU vanilla items
    legacy = {}
    MODEL = re.compile(r'ItemModel:"minecraft:([a-z0-9_]+)"')
    for iid, it in items.items():
        d = neu.get(iid)
        if not d:
            continue
        m = MODEL.search(d.get("nbttag", ""))
        if not m:
            continue
        mod = m.group(1).upper()
        if mod in VALID and mod != "PLAYER_HEAD":
            key = (it.get("material"), int(it.get("durability", 0) or 0))
            legacy.setdefault(key, mod)

    def modern_material(iid, it):
        mat, skin = modern_material0(iid, it)
        d = neu.get(iid)
        if mat == "PAPER" and not skin and iid != "PAPER" and not (d and d.get("vanilla")):
            path = (it.get("item_model") or "").split("/")[-1].upper()
            mat = look_for(iid + " " + path)
        return mat, skin

    def modern_material0(iid, it):
        d = neu.get(iid)
        if it.get("material") == "BANNER":
            return DYE[int(it.get("durability", 0) or 0) % 16] + "_BANNER", None
        im = it.get("item_model") or ""
        if im.startswith("minecraft:") and im[10:].upper() in VALID and im[10:].upper() != "PLAYER_HEAD":
            if im[10:].upper() != "PAPER":
                return im[10:].upper(), None
        model = None
        if d:
            m = MODEL.search(d.get("nbttag", ""))
            if m:
                mod = m.group(1).upper()
                if mod in VALID and mod != "PLAYER_HEAD":
                    model = mod
        # a real vanilla model wins (BAMBOO has a 1.8 skull but a modern item), except PAPER, which is
        # only the base the reference game's resource pack draws over - a textured head reads better.
        if model and model != "PAPER":
            return model, None
        mat = it.get("material", "")
        dur = int(it.get("durability", 0) or 0)
        skin = it.get("skin")
        if skin:
            v = skin.get("value") if isinstance(skin, dict) else skin
            if v:
                return "PLAYER_HEAD", v
        if d and "SkullOwner" in d.get("nbttag", ""):
            m2 = re.search(r'Value:"([A-Za-z0-9+/=]+)"', d["nbttag"])
            if m2:
                return "PLAYER_HEAD", m2.group(1)
        if model == "PAPER" and not (d and d.get("vanilla")):
            return look_for(iid), None
        if model:
            return model, None
        if (mat, dur) in legacy:
            return legacy[(mat, dur)], None
        if (mat, 0) in legacy and dur == 0:
            return legacy[(mat, 0)], None
        if mat in VALID:
            return mat, None
        if mat in LEGACY_FALLBACK and LEGACY_FALLBACK[mat] in VALID:
            return LEGACY_FALLBACK[mat], None
        if (mat, 0) in legacy:
            return legacy[(mat, 0)], None
        log("material fallback PAPER for", iid, mat, dur)
        return "PAPER", None

    # ---- selection ----
    selected = OrderedDict()
    skipped = Counter()
    for iid, it in sorted(items.items()):
        cat = it.get("category")
        if cat and not iid.startswith("ENCHANTED_"):
            continue
        if cat and iid.startswith("ENCHANTED_") and cat not in ("NONE",):
            skipped["categorised enchanted (" + cat + ")"] += 1
            continue
        if "generator" in it:
            skipped["minion"] += 1
            continue
        if "furniture" in it:
            skipped["furniture"] += 1
            continue
        if it.get("unstackable") or it.get("has_uuid"):
            skipped["unique/unstackable"] += 1
            continue
        if it.get("tier") == "UNOBTAINABLE":
            skipped["unobtainable"] += 1
            continue
        if is_excluded(iid) and iid not in FORCE_INCLUDE:
            skipped["excluded pattern"] += 1
            continue
        nd = neu.get(iid)
        if nd and any("Legacy Item" in clean_name(l) for l in nd.get("lore", [])[:3]):
            skipped["legacy item"] += 1
            continue
        selected[iid] = it
    log("selected", len(selected), "skipped", dict(skipped))

    # ---- recipes (NEU crafting) ----
    def grid_of(r):
        cells = []
        for row in "ABC":
            for col in "123":
                v = (r.get(row + col) or "").strip()
                cells.append(v)
        return cells

    def conv_cell(v):
        """NEU 'INK_SACK-3:32' -> (id, amount)."""
        if not v:
            return None
        if ":" in v:
            a, b = v.rsplit(":", 1)
            try:
                n = int(float(b))
            except ValueError:
                a, n = v, 1
        else:
            a, n = v, 1
        a = colon(a)
        return INGREDIENT_ALIAS.get(a, a), n

    raw_recipes = []   # (output, grid[(id,n)|None], count, crafttext)
    for out_id, d in neu.items():
        rs = []
        if isinstance(d.get("recipe"), dict):
            rs.append(dict(d["recipe"]))
        for r in d.get("recipes", []) or []:
            if r.get("type") == "crafting":
                rs.append(r)
        for r in rs:
            o = colon(r.get("overrideOutputId") or out_id)
            if o not in selected:
                continue
            g = [conv_cell(c) for c in grid_of(r)]
            if not any(g):
                continue
            cnt = int(r.get("count", 1) or 1)
            raw_recipes.append((o, g, cnt, d.get("crafttext", "") if o == out_id else ""))

    # ingredients that the catalogue must make sure exist
    def known_id(x):
        return x in selected or x in VALID

    # add missing ingredient items that exist in items.json (pull-in pass, materials-like only)
    pulled = 0
    for o, g, cnt, ct in raw_recipes:
        for c in g:
            if c and not known_id(c[0]) and c[0] in items and not is_excluded(c[0]):
                it = items[c[0]]
                if not it.get("category") and not it.get("unstackable") and not it.get("has_uuid") and "generator" not in it:
                    selected[c[0]] = it
                    pulled += 1
    log("pulled in as ingredients:", pulled)

    # ---- unlock parsing ----
    SLAYERS = {"Zombie": "zombie", "Spider": "spider", "Wolf": "wolf", "Enderman": "enderman", "Blaze": "blaze",
               "Vampire": "vampire", "Revenant": "zombie", "Tarantula": "spider", "Sven": "wolf"}
    SKILLS = {"Farming", "Mining", "Combat", "Foraging", "Fishing", "Enchanting", "Alchemy", "Carpentry", "Taming",
              "Runecrafting", "Social", "Hunting"}

    def unlock_from(out_id, crafttext, name):
        # collections.json is the authority for collection gates ("Enchanted Glowstone Recipe" at tier V);
        # NEU's crafttext disagrees on a handful, so it is only the fallback (and the source for slayer/skill).
        if name in unlock_by_text:
            cid, tier = unlock_by_text[name]
            alt = unlock_from_text(out_id, crafttext)
            key = "collection:%s:%d" % (cid, tier)
            if alt and alt != key:
                log("unlock: collections.json", key, "over crafttext", alt, "for", out_id)
            return key
        return unlock_from_text(out_id, crafttext)

    def unlock_from_text(out_id, crafttext):
        ct = clean_name(crafttext or "")
        m = re.match(r"^Requires:?\s+(.+?)\s+([IVXLC]+)$", ct)
        if m and m.group(1) in coll_name:
            return "collection:%s:%d" % (coll_name[m.group(1)], roman(m.group(2)))
        m = re.match(r"^Requires:?\s+(.+?) Collection\s+([IVXLC]+)$", ct)
        if m and m.group(1) in coll_name:
            return "collection:%s:%d" % (coll_name[m.group(1)], roman(m.group(2)))
        m = re.match(r"^Requires:?\s+(\w+) Slayer\s+(\d+)$", ct)
        if m and m.group(1) in SLAYERS:
            return "slayer:%s:%s" % (SLAYERS[m.group(1)], m.group(2))
        m = re.match(r"^Requires:?\s+(\w+) Skill\s+(\d+)$", ct)
        if m and m.group(1) in SKILLS:
            return "skill:%s:%s" % (m.group(1).lower(), m.group(2))
        if ct:
            log("unparsed crafttext for", out_id, repr(ct))
        return None

    # ---- build item defs ----
    defs = OrderedDict()
    names = {}
    for iid, it in selected.items():
        mat, skin = modern_material(iid, it)
        if iid in MATERIAL_FIX:
            mat, skin = MATERIAL_FIX[iid], None
        nm0 = clean_name(it.get("name", iid)) or iid
        nm = rename(iid, nm0)
        names[iid] = (nm0, nm)
        tier = (it.get("tier") or "COMMON").upper()
        tier = {"SUPREME": "DIVINE", "UNOBTAINABLE": "SPECIAL"}.get(tier, tier)
        cat = "MATERIAL"
        if re.match(r"^(ROUGH|FLAWED|FINE|FLAWLESS|PERFECT)_[A-Z]+_GEM$", iid):
            cat = "GEMSTONE"
        d = OrderedDict()
        d["id"] = iid
        d["name"] = nm
        d["material"] = mat
        d["rarity"] = tier
        d["category"] = cat
        if skin:
            d["skull"] = skin
        if it.get("glowing"):
            d["glow"] = True
        price = SELL_OVERRIDE.get(iid, it.get("npc_sell_price"))
        if price:
            d["npcSell"] = float(price)
        tags = []
        if iid in bazaar:
            tags.append("bazaar")
        if it.get("soulbound"):
            tags.append("soulbound")
        if tags:
            d["tags"] = tags
        defs[iid] = d

    # recipes -> Recipe JSON
    recipes = []
    ambiguous = 0
    unknown_ing = Counter()
    per_out = Counter()
    for o, g, cnt, ct in raw_recipes:
        if o not in defs:
            continue
        cells = []
        ing_ids = []
        ok = True
        for c in g:
            if not c:
                cells.append("")
                continue
            iid, n = c
            ing_ids.append(iid)
            if not known_id(iid):
                unknown_ing[iid] += 1
                ok = False
                break
            if n == 1:
                cell = iid
            else:
                cell = "%s:%d" % (iid, n)
                if cell in defs or cell in items:
                    ambiguous += 1
                    log("ambiguous cell (id:amount is itself an id)", o, cell)
                    ok = False
                    break
            cells.append(cell)
        if not ok:
            continue
        unlock = unlock_from(o, ct, names[o][0])
        r = OrderedDict()
        # wave 5 (crafting request 8): a vanilla item made from vanilla items (Block of Diamond, planks, sticks) is a
        # VANILLA recipe in the reference; crafting files any recipe id starting "vanilla:" under the Vanilla book.
        is_van = lambda x: bool((neu.get(x) or {}).get("vanilla")) and not x.startswith("ENCHANTED_")
        # A collection-gated one (Packed Ice at Ice III, Cobweb from String) is a SkyBlock recipe whatever it makes.
        prefix = "vanilla:materials/" if not unlock and is_van(o) and all(is_van(x) for x in ing_ids) else "materials:"
        r["id"] = "%s%s#%d" % (prefix, o, per_out[o])
        per_out[o] += 1
        r["type"] = "CRAFTING"
        r["result"] = o
        r["amount"] = cnt
        r["grid"] = cells
        if unlock:
            r["unlock"] = unlock
        r["station"] = "Crafting Table"
        recipes.append(r)
    log("recipes", len(recipes), "ambiguous dropped", ambiguous, "unknown ingredients", dict(unknown_ing.most_common(40)))

    # dedupe identical recipes (NEU sometimes lists the same grid twice)
    seen = set()
    uniq = []
    for r in recipes:
        k = (r["result"], r["amount"], tuple(r["grid"]))
        if k in seen:
            continue
        seen.add(k)
        uniq.append(r)
    recipes = uniq

    # An ENCHANTED_* id nobody can craft is a legacy id or a gadget, not a compression: drop it.
    produced = {r["result"] for r in recipes}
    for iid in [i for i in defs if i.startswith("ENCHANTED_") and i not in produced]:
        log("dropped ENCHANTED_ without a recipe:", iid)
        del defs[iid]
        del selected[iid]
    recipes = [r for r in recipes if all((c.rsplit(":", 1)[0] if c not in defs and c not in VALID and c not in items else c) in defs
                                         or (c.rsplit(":", 1)[0] if c not in defs and c not in VALID and c not in items else c) in VALID
                                         for c in r["grid"] if c)]

    # ---- ingredient sums, compression graph, derived prices ----
    def ingredients(r):
        m = OrderedDict()
        for c in r["grid"]:
            if not c:
                continue
            if c in defs or c in VALID or c in items:
                iid, n = c, 1
            else:
                iid, n = c.rsplit(":", 1)
                n = int(n)
            m[iid] = m.get(iid, 0) + n
        return m

    def price(i):
        if i in defs:
            return defs[i].get("npcSell", 0.0)
        it = items.get(i)
        return float(it.get("npc_sell_price", 0) or 0) if it else 0.0

    producing = defaultdict(list)
    for r in recipes:
        producing[r["result"]].append(r)

    # compression recipe = all inputs are materials of the catalogue / vanilla, >= 4 total items in
    def is_compression(r):
        ing = ingredients(r)
        total = sum(ing.values())
        return total >= 4 and all((i in defs or i in VALID) for i in ing)

    derived = 0
    for o, rs in producing.items():
        if defs[o].get("npcSell"):
            continue
        for r in rs:
            if not is_compression(r):
                continue
            ing = ingredients(r)
            if all(price(i) > 0 for i in ing):
                v = sum(price(i) * n for i, n in ing.items()) / r["amount"]
                defs[o]["npcSell"] = round(v, 1)
                defs[o].setdefault("extra", {})["derivedPrice"] = True
                derived += 1
                break
    log("derived npc prices:", derived)

    # Clamp: a compression must never SELL for more than its inputs (research 02 §5.3: otherwise
    # compacting is a coin printer). Iterate to a fixpoint so chains settle top-down.
    clamped = 0
    for _ in range(60):
        changed = False
        for r in recipes:
            ing = ingredients(r)
            o = r["result"]
            # wave 5: a compression of inputs that have NO npc value cannot have one either (Soulflow sold for 1
            # coin from 160 unsellable Raw Soulflow; accessories' SellCap replica flagged it as the one materials
            # printer). Mixed recipes (a Dispenser's bow) are left alone.
            if ing and is_compression(r) and all(price(i) <= 0 for i in ing) and price(o) > 0:
                ex = defs[o].setdefault("extra", {})
                ex.setdefault("referencePrice", defs[o].get("npcSell"))
                defs[o]["npcSell"] = 0.0
                clamped += 1
                changed = True
                continue
            if not ing or any(price(i) <= 0 for i in ing):
                continue
            vin = sum(price(i) * n for i, n in ing.items())
            o = r["result"]
            vout = price(o) * r["amount"]
            if vin > 0 and vout > vin + 1e-9:
                newp = int(vin / r["amount"] * 10) / 10.0
                ex = defs[o].setdefault("extra", {})
                ex.setdefault("referencePrice", defs[o].get("npcSell"))
                defs[o]["npcSell"] = newp
                clamped += 1
                changed = True
        if not changed:
            break
    log("prices clamped to input value:", clamped)

    # coin printers in the research data
    printers = []
    for r in recipes:
        if not is_compression(r):
            continue
        ing = ingredients(r)
        vin = sum(price(i) * n for i, n in ing.items())
        vout = price(r["result"]) * r["amount"]
        if vin > 0 and vout > vin * 1.0001:
            printers.append((r["result"], vin, vout))
    log("compression recipes whose output sells for MORE than inputs:", len(printers))
    for p in printers:
        log("   printer", p)

    # chains: base -> outputs (compression only, single-input or main input)
    # One PRIMARY recipe per output decides its place in a chain: makes exactly one, then the most
    # inputs (Enchanted Diamond is "160 Diamond", not "160 Block of Diamond -> 9").
    chains = defaultdict(list)
    made_from = {}
    primary = {}
    MULTI_OK = {"REVENANT_VISCERA", "TARANTULA_SILK", "NULL_OVOID", "MOLTEN_POWDER", "ENCHANTED_EYE_OF_ENDER",
                "ENCHANTED_FIREWORK_ROCKET", "ENCHANTED_FERMENTED_SPIDER_EYE", "ENCHANTED_COOKIE",
                "AMALGAMATED_CRIMSONITE", "AMALGAMATED_CRIMSONITE_NEW"}

    def is_chain_edge(r):
        """A compression in the genre's sense (what a compactor would do), not any recipe with 4+ inputs."""
        if not is_compression(r):
            return False
        ing = ingredients(r)
        total = sum(ing.values())
        o = r["result"]
        if len(ing) > 1 and o not in MULTI_OK:
            return False
        if o in ("IRON_TRAPDOOR", "WORKBENCH", "TRAP_DOOR"):
            return False   # vanilla crafts that happen to use 4 of one thing
        vanilla_out = (neu.get(o) or {}).get("vanilla") and not o.startswith("ENCHANTED_")
        if vanilla_out:
            return r["amount"] == 1 and total in (4, 9)
        return total >= 4

    for r in recipes:
        if not is_chain_edge(r):
            continue
        o = r["result"]
        key = (1 if r["amount"] == 1 else 0, sum(ingredients(r).values()))
        if o not in primary or key > primary[o][0]:
            primary[o] = (key, r)
    for o, (_, r) in primary.items():
        ing = ingredients(r)
        main = max(ing.items(), key=lambda kv: kv[1])[0]
        if main == o:
            continue
        chains[main].append(o)
        made_from[o] = ing
    # the enchanted step first, plain vanilla block crafts (Block of Diamond) after it
    for k in chains:
        chains[k].sort(key=lambda x: (0 if sum(made_from[x].values()) == 160 else 1, -sum(made_from[x].values())))

    # ---- families ----
    FAM = {"FARMING": "farming", "MINING": "mining", "COMBAT": "combat", "FORAGING": "foraging",
           "FISHING": "fishing", "RIFT": "misc"}
    family = {}
    for iid in defs:
        if iid in coll_cat:
            family[iid] = FAM.get(coll_cat[iid], "misc")
    changed = True
    while changed:
        changed = False
        for o, ing in made_from.items():
            if o in family:
                continue
            for i in ing:
                if i in family:
                    family[o] = family[i]
                    changed = True
                    break
    # what monsters / sea creatures drop (NEU mob pages), for items nothing else places
    dropped = {}
    for page, d in neu.items():
        for r in d.get("recipes", []) or []:
            if r.get("type") != "drops":
                continue
            fam = "fishing" if page.endswith("_SC") or "SEA_CREATURE" in page else "combat"
            for dr in r.get("drops", []) or []:
                did = colon(str(dr.get("id", "")).rsplit(":", 1)[0])
                dropped.setdefault(did, fam)
    for iid, it in selected.items():
        if iid in family:
            continue
        exp = it.get("experience") or {}
        for sk in ("farming", "mining", "combat", "foraging", "fishing"):
            if sk in exp:
                family[iid] = sk
                break
        else:
            cd = it.get("category_display", "")
            if cd in ("ORE", "DWARVEN METAL") or iid.endswith("_GEM") or iid.endswith("_CRYSTAL"):
                family[iid] = "mining"
            elif cd == "BLOCK" or (iid in neu and neu[iid].get("vanilla")):
                family[iid] = "blocks"
            elif iid in dropped and not (neu.get(iid) or {}).get("vanilla"):
                family[iid] = dropped[iid]
            else:
                family[iid] = "misc"

    # ---- lore (our own words) ----
    def nm(i):
        if i in defs:
            return defs[i]["name"]
        if i in items:
            return rename(i, clean_name(items[i]["name"]))
        return i.replace("_", " ").title()

    # Every reference recipe that USES an id, whatever it makes (a minion, a sword, a talisman), so an item
    # with nothing better to say can at least say what it is for.
    used_in = defaultdict(list)
    for out_id, d0 in neu.items():
        rs0 = ([d0["recipe"]] if isinstance(d0.get("recipe"), dict) else []) + \
              [r for r in (d0.get("recipes") or []) if r.get("type") == "crafting"]
        for r in rs0:
            o = colon(r.get("overrideOutputId") or out_id)
            for c in grid_of(r):
                cc = conv_cell(c)
                if cc and cc[0] != o and o not in used_in[cc[0]]:
                    used_in[cc[0]].append(o)

    # Display names OTHER modules ship (their data JSON, then their docs/names tables), so "Used to craft"
    # never prints a reference proper noun another module already renamed (Gunther's Sneakers -> Racer's Sneakers).
    shipped_names = {}

    def walk_names(o):
        if isinstance(o, dict):
            i, n = o.get("id"), o.get("name")
            if isinstance(i, str) and isinstance(n, str) and re.match(r"^[A-Z0-9_:]+$", i):
                shipped_names.setdefault(i, re.sub(r"&[0-9a-fk-or]", "", n))
            for v in o.values():
                walk_names(v)
        elif isinstance(o, list):
            for v in o:
                walk_names(v)
    data_root = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")
    for f in glob.glob(os.path.join(data_root, "*", "**", "*.json"), recursive=True):
        if os.path.normpath(f).startswith(os.path.normpath(OUT)):
            continue
        try:
            walk_names(json.load(open(f, encoding="utf8")))
        except (ValueError, OSError):
            pass
    for f in glob.glob(os.path.join(ROOT, "docs", "names", "*.md")):
        for line in open(f, encoding="utf8"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) >= 3:
                i = cells[0].strip("`* ")
                n = re.sub(r"\s*\(.*$", "", cells[2].replace("**", "")).strip()
                if re.match(r"^[A-Z0-9_:]+$", i) and n:
                    shipped_names.setdefault(i, n)
    PROPER_RE = re.compile(r"[A-Z][a-z]+'s\b|\b(Kuudra|Jerry|Arachne|Revenant|Tarantula|Griffin|Jacob|Gabagool|Hypixel"
                           r"|Divan|Bonzo|Gunther|Scarf|Livid|Necron|Sadan|Thorn|Goldor|Storm|Maxor|Vargul|Dante)\b")

    def out_name(o):
        if o in defs:
            return defs[o]["name"]
        if o in shipped_names:
            return shipped_names[o]
        n = nm(o)
        return None if PROPER_RE.search(n) else n

    def uses_line(iid):
        outs = used_in.get(iid) or []
        shown = []
        for o in sorted(outs, key=lambda o: ("_GENERATOR_" in o, o)):
            if ";" in o or (o not in defs and o not in items):
                continue   # enchant books / pets / shards (ID;N) and ids with no real name
            n = out_name(o)
            if not n:
                continue
            if "_GENERATOR_" in o:   # minion tiers: "Wheat Minion III" -> "Wheat Minion"
                n = re.sub(r"\s+[IVXL]+$", "", n)
            if n not in shown:
                shown.append(n)
        if not shown:
            return []
        more = len(shown) - 2
        txt = "&7Used to craft " + " and ".join("&a" + n + "&7" for n in shown[:2])
        if more > 0:
            txt = "&7Used to craft " + ", ".join("&a" + n + "&7" for n in shown[:2]) + " and %d more" % more
        return wrap(txt + ".")

    misc_described = Counter()
    for iid, d in defs.items():
        lore = []
        it = selected[iid]
        if iid in coll_cat:
            lore.append("&8Collection Item")
        exp = it.get("experience") or {}
        if "alchemy" in exp:
            lore.append("&8Brewing Ingredient")
        if lore:
            lore.append("")
        if iid in made_from:
            ing = made_from[iid]
            parts = ["&a%s &7%s" % ("{:,}".format(n), nm(i)) for i, n in ing.items()]
            r0 = producing[iid][0]
            per = r0["amount"]
            head = "&7Compressed from " if len(parts) == 1 else "&7Combined from "
            body = parts[0] if len(parts) == 1 else ", ".join(parts[:-1]) + " &7and " + parts[-1]
            text = head + body + "&7" + (" into &a%d&7." % per if per > 1 else ".")
            from_textwrap = wrap(text)
            lore.extend(from_textwrap)
        if iid in chains:
            nxt = chains[iid][:3]
            lore.extend(wrap("&7Compresses into " + ", ".join("&a" + nm(x) + "&7" for x in nxt) + "."))
        nd0 = neu.get(iid) or {}
        blurb = vanilla_blurb(d["material"], iid) if (nd0.get("vanilla") or family.get(iid) == "blocks") else None
        if blurb and (not lore or lore == [""]):
            lore = wrap("&7" + blurb)
        elif blurb and lore and lore[-1] == "" and iid not in made_from and iid not in chains:
            lore.extend(wrap("&7" + blurb))   # "Brewing Ingredient" header with nothing under it
        if not lore or lore == [""]:
            fam = family.get(iid, "misc")
            src = {"farming": "Grown and gathered on farms.", "mining": "Found by mining.",
                   "combat": "Dropped by monsters.", "foraging": "Gathered from trees and plants.",
                   "fishing": "Pulled from the water.", "blocks": "A common building block.",
                   "misc": "A crafting material."}[fam]
            if fam == "misc":
                kind = misc_blurb(iid, d["name"], it)
                misc_described["kind" if kind else "generic"] += 1
                src = kind or src
            lore = wrap("&7" + src) + uses_line(iid)
        while lore and lore[-1] == "":
            lore.pop()
        d["lore"] = lore
        if iid in CONSUMABLES:
            _, aid, aname, trig, alore, reqs = CONSUMABLES[iid]
            d["abilities"] = [OrderedDict([("id", aid), ("name", aname), ("trigger", trig), ("lore", alore)])]
            if reqs:
                d["requirements"] = list(reqs)
        d.setdefault("extra", {})["family"] = family.get(iid, "misc")
        if iid in coll_cat:
            d["extra"]["collection"] = iid
        nd = neu.get(iid)
        if nd and nd.get("vanilla"):
            d["extra"]["vanilla"] = True

    log("misc lore: described by kind", misc_described["kind"], "| still generic", misc_described["generic"])

    # An id that is itself a modern material name (PUMPKIN) must BE that material, or vanilla pickups of
    # it would convert into whatever look we picked - unless the alias file sends that material to
    # another catalogue id first (MELON -> MELON_BLOCK), which core's vanillaId() checks before the name.
    aliases0 = json.load(open(os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "core-items",
                                           "vanilla_aliases.json"), encoding="utf8"))
    for iid, d in defs.items():
        if iid in VALID and d["material"] != iid and not any(a in defs for a in aliases0.get(iid, [])):
            log("forced material = id for", iid, "(was", d["material"] + ")")
            d["material"] = iid
            d.pop("skull", None)

    # vanilla tag: reference id != modern material, only first claimant per material, and only when
    # the core alias file does not already map that material to this id.
    aliases = json.load(open(os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "core-items",
                                          "vanilla_aliases.json"), encoding="utf8"))
    claimed = {}
    vtag = 0
    for iid, d in defs.items():
        nd = neu.get(iid)
        is_vanilla = bool(nd and nd.get("vanilla"))   # a custom collection item (Lotus on a poppy) must never claim vanilla pickups
        if not is_vanilla or iid == d["material"] or iid.startswith("ENCHANTED_"):
            continue
        if d.get("skull"):
            continue
        mat = d["material"]
        if iid in aliases.get(mat, []):
            continue
        if mat in claimed or mat in defs:
            continue
        claimed[mat] = iid
        d.setdefault("tags", []).append("vanilla")
        vtag += 1
    log("vanilla tags added:", vtag)

    # ---- write ----
    os.makedirs(OUT, exist_ok=True)
    by_fam = defaultdict(list)
    for iid, d in defs.items():
        by_fam[d["extra"]["family"]].append(d)
    # Remove stale FAMILY files only. items_integration.json is not this script's output: gen_integration.py
    # (run at the end of main) writes it, and it holds X/Y/Z, Glacial Fragment, Stretching Sticks... that other
    # modules' recipes need. A blanket items_*.json delete during the 2026-10-03 lore pass dropped it silently
    # and 11 live self-tests plus accessories' SellCapTest went red.
    for f in glob.glob(os.path.join(OUT, "items_*.json")):
        fam = os.path.basename(f)[len("items_"):-len(".json")]
        if fam != "integration" and fam not in by_fam:
            os.remove(f)
    for fam, lst in sorted(by_fam.items()):
        with open(os.path.join(OUT, "items_%s.json" % fam), "w", encoding="utf8", newline="\n") as f:
            json.dump(lst, f, ensure_ascii=False, indent=1)
        log("wrote items_%s.json" % fam, len(lst))
    with open(os.path.join(OUT, "recipes.json"), "w", encoding="utf8", newline="\n") as f:
        json.dump(recipes, f, ensure_ascii=False, indent=1)
    cat = OrderedDict()
    cat["families"] = OrderedDict((fam, [d["id"] for d in lst]) for fam, lst in sorted(by_fam.items()))
    cat["chains"] = OrderedDict(sorted(chains.items()))
    cat["collectionTiers"] = OrderedDict((k, v) for k, v in sorted(coll_tiers.items()) if k in defs)
    with open(os.path.join(OUT, "catalogue.json"), "w", encoding="utf8", newline="\n") as f:
        json.dump(cat, f, ensure_ascii=False, indent=1)

    # renames doc
    rows = [(i, a, b) for i, (a, b) in sorted(names.items()) if a != b]
    with open(NAMES_DOC, "w", encoding="utf8", newline="\n") as f:
        f.write("# materials - renames\n\n")
        f.write("Generated by `tools/gen/materials/gen_materials.py` (RENAME_WHOLE + RENAME_TOKENS). Ids never change;\n")
        f.write("only the display name does. Generic words (Enchanted Diamond, Wool, Slimeball...) stay generic.\n\n")
        f.write("| id | reference name | our name |\n|---|---|---|\n")
        for i, a, b in rows:
            f.write("| `%s` | %s | %s |\n" % (i, a, b))
        f.write("\n## Kept on purpose (owner may want to review)\n\n")
        f.write("Material coinages that read as ordinary nouns of a fantasy world rather than as a named character,\n")
        f.write("boss or place were left alone: Mithril, Titanium, Tungsten, Umber, Glacite, the gemstone types\n")
        f.write("(Ruby, Jade, Amber, Topaz...), Hemovibe, Hemoglass, Vinesap, Caducous, Agaricus, Berberis, Timite,\n")
        f.write("Youngite, Obsolite, Highlite, Treasurite, Starfall, Sorrow, Ectoplasm, Volta, Magmag, Helixis,\n")
        f.write("Figstone, Mangcore, Living Metal, Null Sphere/Ovoid, Derelict Ashe. Renaming any of them is one line in\n")
        f.write("`RENAME_WHOLE` / `RENAME_TOKENS` of the generator; ids never change.\n\n")
        review = sorted({(i, b) for i, (a, b) in names.items() if re.search(r"[A-Z][a-z]+'s\b", b)})
        f.write("Possessive names after renaming (all generic roles now, listed for review):\n\n")
        for i, b in review:
            f.write("- `%s` %s\n" % (i, b))
        # integration fallbacks (gen_integration.py ITEMS): names typed by hand, listed where they differ
        try:
            import gen_integration as gi
            irows = [(iid, gi.REF.get(iid, {}).get("name", ""), v[0]) for iid, v in gi.ITEMS.items()
                     if gi.REF.get(iid, {}).get("name") and clean_name(gi.REF[iid]["name"]) != v[0]]
            if irows:
                f.write("\n## Integration fallbacks (`gen_integration.py`)\n\n| id | reference name | our name |\n|---|---|---|\n")
                for iid, a, b in sorted(irows):
                    f.write("| `%s` | %s | %s |\n" % (iid, clean_name(a), b))
        except ImportError:
            pass
    log("renames:", len(rows))

    # proper-noun scan: names with a capitalised possessive that we did not rename (review list)
    review = sorted({(i, b) for i, (a, b) in names.items() if re.search(r"[A-Z][a-z]+'s\b", b)})
    log("possessive names kept (review):", len(review))
    for i, b in review:
        log("   ", i, "|", b)

    # The integration fallbacks are part of the same data set: regenerate them in the same run so one command
    # can never ship one half without the other. It skips any id the family files above already define.
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import gen_integration
    log("integration fallbacks written:", gen_integration.main())

    with open(REPORT, "w", encoding="utf8", newline="\n") as f:
        f.write("\n".join(report) + "\n")
    print("\n".join(report[:40]))
    print("... full report in", REPORT)


def wrap(text, width=38):
    out, line, vis, carry = [], "", 0, "&7"
    for w in text.split(" "):
        wv = len(re.sub(r"&[0-9a-fk-or]", "", w))
        if vis and vis + 1 + wv > width:
            out.append(line)
            line, vis = carry, 0
        if vis:
            line += " "
            vis += 1
        line += w
        vis += wv
        for m in re.finditer(r"&[0-9a-fk-or]", w):
            carry = m.group(0)
    if line:
        out.append(line)
    return out


if __name__ == "__main__":
    main()
