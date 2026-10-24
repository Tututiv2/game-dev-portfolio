#!/usr/bin/env python3
"""
Generate the shops module's data from the research files.

    py -3 tools/gen/shops/gen_shops.py

Reads
  research/data/neu-repo/items/*_NPC.json      NPC shop stock (recipes[type=npc_shop]), NPC skins, islands
  research/data/neu-repo/items/<ID>.json       to tell enchanted books (SHARPNESS;1) from pets (BEE;0)
  research/data/items.json                     reference NPC sell prices (Builder pricing, report)
  plugin/src/main/resources/data/core-items/vanilla_aliases.json   (core) Material -> legacy reference ids
  plugin/src/main/resources/data/crafting/neu_aliases.json          (crafting, read-only) legacy -> modern
  plugin/src/main/resources/gen/known_blocks.txt                    valid 26.1.2 block names (Builder stock)
  plugin/src/main/resources/data/*/items*.json  every other module's item ids (coverage report only)

Writes
  plugin/src/main/resources/data/shops/shops.json       every merchant: NPC, anchor, stock, costs
  plugin/src/main/resources/data/shops/townsfolk.json   talk-only hub NPCs (lore)
  docs/names/shops.md                                   rename table (generated from CONFIG below)
  tools/gen/shops/report.txt                            skipped shops (and why), coverage, price clamps

Everything a person decided (our names, anchors, dialogue, which merchant goes where, who owns which
shop) is in the CONFIG tables below. Every PRICE and every item comes from neu-repo; the only numbers
typed here are the Builder's block prices, which the research gives only as "~1-10 coins per block"
(marked TUNE in the output).
"""
import glob
import json
import math
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
RES = os.path.join(ROOT, "plugin", "src", "main", "resources")
OUT_DIR = os.path.join(RES, "data", "shops")

# --------------------------------------------------------------------------------------------------
# CONFIG: merchants this module stations.
#   id        our internal shop id (lower_snake; never shown)
#   src       neu-repo NPC files whose stock is merged into this shop (in order)
#   name      OUR display name ('&' codes) - proper nouns of the reference are renamed (docs/names)
#   ref       the reference NPC name (for docs/names only)
#   island    our island id (CONTRACT §3)
#   anchor    the anchor the NPC stands on
#   mode      contract  = anchor guaranteed by CONTRACT §4: registered in postEnable unless another
#                         module already put an NPC on that anchor
#             secondary = anchor NOT in the contract: registered only once the island layout has it
#                         (requested in the hand-off); until then the shop is traded from /merchants
#   cat       directory category
#   hello     first-meeting dialogue (our words)
# --------------------------------------------------------------------------------------------------
HUB = "hub"
SHOPS = [
    # ---- hub, CONTRACT anchors ------------------------------------------------------------------
    dict(id="farm_merchant", src=["FARM_MERCHANT_NPC"], name="&fFarm Merchant", ref="Farm Merchant", island=HUB,
         anchor="npc.farm_merchant", mode="contract", cat="Farming", icon="WHEAT",
         hello=["Fresh from the fields this morning!", "Seeds, crops, a hoe if you need one. Have a look."]),
    dict(id="mine_merchant", src=["MINE_MERCHANT_NPC"], name="&fMine Merchant", ref="Mine Merchant", island=HUB,
         anchor="npc.mine_merchant", mode="contract", cat="Mining", icon="IRON_PICKAXE",
         hello=["Heading down the coal mine? You'll want a proper pick.", "I'll buy back whatever you dig up, too."]),
    dict(id="lumber_merchant", src=["LUMBER_MERCHANT_NPC"], name="&fLumber Merchant", ref="Lumber Merchant", island=HUB,
         anchor="npc.lumber_merchant", mode="contract", cat="Foraging", icon="OAK_LOG",
         hello=["Every log in this town passed through my hands.", "Axes, logs, the odd wooden tool. Take your pick."]),
    dict(id="fish_merchant", src=["FISH_MERCHANT_NPC", "FISHING_MERCHANT_NPC"], name="&fFish Merchant", ref="Fish Merchant / Fishing Merchant",
         island=HUB, anchor="npc.fish_merchant", mode="contract", cat="Fishing", icon="FISHING_ROD",
         hello=["The pond's been generous today.", "A rod, some bait and a full angler's kit, all here."]),
    dict(id="weaponsmith", src=["WEAPONSMITH_NPC"], name="&fWeaponsmith", ref="Weaponsmith", island=HUB,
         anchor="npc.weaponsmith", mode="contract", cat="Combat", icon="IRON_SWORD",
         hello=["Every blade on this rack has seen a graveyard or two.", "Pick one that fits your hand."]),
    dict(id="armorsmith", src=["ROSETTA_NPC"], name="&fArmorsmith Brenna", ref="Rosetta", island=HUB,
         anchor="npc.armorsmith", mode="contract", cat="Combat", icon="IRON_CHESTPLATE",
         hello=["Brenna's the name, plate is the game.", "From plain iron to sets fit for a knight - I hammered them all."]),
    dict(id="adventurer", src=["ADVENTURER_NPC"], name="&fAdventurer", ref="Adventurer (Combat Merchant)", island=HUB,
         anchor="npc.adventurer", mode="contract", cat="Combat", icon="BONE",
         hello=["I've cleared every crypt from here to the ruins.", "Mob drops and a few lucky charms - interested?"]),
    dict(id="builder", src=[], name="&fBuilder", ref="Builder", island=HUB,
         anchor="npc.builder", mode="contract", cat="Building", icon="BRICKS",
         hello=["An island is only as good as what you build on it.", "Stone, wood, glass, colour - I stock the lot."]),
    dict(id="bartender", src=["BARTENDER_NPC"], name="&fBartender", ref="Bartender", island=HUB,
         anchor="npc.bartender", mode="contract", cat="Combat", icon="POTION",
         hello=["What'll it be? Coffee keeps the slayers going.", "Ask about the stronger stuff if you've the coin."]),
    dict(id="librarian", src=["LIBRARIAN_NPC"], name="&fLibrarian", ref="Librarian", island=HUB,
         anchor="npc.librarian", mode="contract", cat="Enchanting", icon="ENCHANTED_BOOK",
         hello=["Shh - this is a library.", "Bottles of experience and starter books, quietly sold."]),
    dict(id="alchemist", src=["ALCHEMIST_NPC"], name="&fAlchemist", ref="Alchemist", island=HUB,
         anchor="npc.alchemist", mode="contract", cat="Alchemy", icon="BREWING_STAND",
         hello=["Careful with that bottle, it's still bubbling.", "Ingredients for every brew you can name."]),

    # ---- hub, secondary (anchors requested from the hub builder) --------------------------------
    dict(id="fletcher", src=["JAX_NPC"], name="&fFletcher Quill", ref="Jax", island=HUB, anchor="npc.fletcher",
         mode="secondary", cat="Combat", icon="ARROW", where="the archery range by the weaponsmith",
         hello=["Arrows, fletched by hand.", "Bring me enchanted materials and I'll tip them with something nasty."]),
    dict(id="gravedigger", src=["PAT_NPC"], name="&fGravedigger Sexby", ref="Pat", island=HUB, anchor="npc.gravedigger",
         mode="secondary", cat="Mining", icon="FLINT", where="the graveyard",
         hello=["Digging graves turns up a lot of gravel.", "And gravel turns up flint. Want some?"]),
    dict(id="beekeeper", src=["BEA_NPC"], name="&fBeekeeper Honora", ref="Bea", island=HUB, anchor="npc.beekeeper",
         mode="secondary", cat="Pets", icon="HONEYCOMB", where="the village, near the flower house",
         hello=["Mind the hive - they're friendly once they know you.", "I'll even part with a bee of your own."]),
    dict(id="pet_supplies", src=["ZOG_NPC"], name="&fCritter Merchant Wex", ref="Zog", island=HUB, anchor="npc.pet_supplies",
         mode="secondary", cat="Pets", icon="BONE", where="next to the pet sitter",
         hello=["Treats, claws, teeth - everything a pet could want.", "They gain levels faster with the right item."]),
    dict(id="aquarist", src=["LUKAS_THE_AQUARIST_NPC"], name="&fMarlo the Aquarist", ref="Lukas the Aquarist", island=HUB,
         anchor="npc.aquarist", mode="secondary", cat="Fishing", icon="TROPICAL_FISH", where="by the hub pond",
         hello=["My seals love fishy treats.", "Bring me treats and I'll trade you something special."]),
    dict(id="tailor", src=["SEYMOUR_NPC"], name="&fTailor Fennimore", ref="Seymour", island=HUB, anchor="npc.tailor",
         mode="secondary", cat="Fashion", icon="LEATHER_CHESTPLATE", where="a fashion shop in the village",
         hello=["Only the finest suits leave my shop.", "Fine tailoring has never been cheap."]),
    dict(id="broker", src=["LIZ_NPC"], name="&fBroker Vell", ref="Liz", island=HUB, anchor="npc.broker",
         mode="secondary", cat="Economy", icon="GOLD_NUGGET", where="near the bank or the auction house",
         hello=["Stocks for treasures, treasures for stocks.", "A sharp trader always has a few shares to spend."]),
    dict(id="shady_dealer", src=["LUCIUS_NPC"], name="&fShady Malloy", ref="Lucius", island=HUB, anchor="npc.shady_dealer",
         mode="secondary", cat="Accessories", icon="GOLD_INGOT", where="the wilderness, near the dark auction",
         hello=["Psst. Shops overcharge, everybody knows it.", "Wear one of my rings and they'll knock a little off."]),
    dict(id="hedgewitch", src=["AGNES_NPC"], name="&fHedgewitch Morra", ref="Agnes", island=HUB, anchor="npc.hedgewitch",
         mode="secondary", cat="Accessories", icon="CAULDRON", where="a crooked hut in the village",
         hello=["Bring me the odd things monsters leave behind.", "I brew them into charms. Mostly harmless ones."]),
    dict(id="phone_vendor", src=["ALDA_NPC"], name="&fDialer Pim", ref="Alda", island=HUB, anchor="npc.phone_vendor",
         mode="secondary", cat="Utility", icon="PAPER", where="the village",
         hello=["Stay in touch with the whole island!"]),
    dict(id="decorator", src=["AMELIA_NPC"], name="&fDecorator Lune", ref="Amelia", island=HUB, anchor="npc.decorator",
         mode="secondary", cat="Building", icon="ITEM_FRAME", where="the village",
         hello=["Make your island a place people want to visit.", "Games, displays, a parkour course or two."]),
    dict(id="owl_keeper", src=["FANN_NPC"], name="&fOwl-keeper Whitcombe", ref="Fann", island=HUB, anchor="npc.owl_keeper",
         mode="secondary", cat="Pets", icon="FEATHER", where="the village",
         hello=["Hoo goes there? Ah, a customer."]),
    dict(id="scroll_seller", src=["GRUMBLEFOOT_NPC"], name="&fOld Tobias", ref="Grumblefoot", island=HUB, anchor="npc.scroll_seller",
         mode="secondary", cat="Utility", icon="PAPER", where="at the foot of the wizard tower",
         hello=["Tired of the climb? So am I.", "A scroll will take you straight to the top."]),
    dict(id="hermit", src=["LONELY_PHILOSOPHER_NPC"], name="&fPensive Hermit", ref="Lonely Philosopher", island=HUB, anchor="npc.hermit",
         mode="secondary", cat="Utility", icon="PAPER", where="the castle ruins",
         hello=["What is a castle without visitors?", "Take a scroll, so you may return."]),
    dict(id="snow_shoveler", src=["SCOOP_NPC"], name="&fShoveller Brisk", ref="Scoop", island=HUB, anchor="npc.snow_shoveler",
         mode="secondary", cat="Utility", icon="IRON_SHOVEL", where="the mountain top",
         hello=["Up here the snow never stops.", "A good shovel makes short work of it."]),
    dict(id="plumber", src=["PLUMBER_JOE_NPC"], name="&fPipefitter Dunn", ref="Plumber Joe", island=HUB, anchor="npc.plumber",
         mode="secondary", cat="Utility", icon="SPONGE", where="the sewer entrance",
         hello=["Water where it shouldn't be? I've got just the thing."]),
    dict(id="sly_vendor", src=["SHIFTY_NPC"], name="&fSly Quentin", ref="Shifty", island=HUB, anchor="npc.sly_vendor",
         mode="secondary", cat="Combat", icon="HONEY_BOTTLE", where="a back alley near the tavern",
         hello=["Slayers swear by my drinks.", "Don't ask what's in them."]),
    dict(id="stone_merchant", src=["SMITHMONGER_NPC"], name="&fStonemonger Haldor", ref="Smithmonger", island=HUB, anchor="npc.stone_merchant",
         mode="secondary", cat="Reforging", icon="ANVIL", where="beside the blacksmith",
         hello=["Reforge stones, the rare ones the Blacksmith won't stock."]),
    dict(id="tinkerer", src=["MAD_REDSTONE_ENGINEER_NPC"], name="&fTinkerer Sprocket", ref="Mad Redstone Engineer", island=HUB, anchor="npc.tinkerer",
         mode="secondary", cat="Building", icon="REDSTONE", where="near the builder's house",
         hello=["Levers! Repeaters! Pressure plates!", "Everything you need to wire up your island."]),
    dict(id="carpenter", src=["CARPENTER_NPC"], name="&fCarpenter", ref="Carpenter", island=HUB, anchor="npc.carpenter",
         mode="secondary", cat="Building", icon="CRAFTING_TABLE", where="the foraging camp",
         hello=["Furniture built to last.", "Every piece is hand-carved, right here."]),
    dict(id="wool_weaver", src=["WOOL_WEAVER_NPC"], name="&fWool Weaver", ref="Wool Weaver", island=HUB, anchor="npc.wool_weaver",
         mode="secondary", cat="Building", icon="WHITE_WOOL", where="near the builder's house",
         hello=["Sixteen colours of everything.", "Wool, carpet, glass and clay - dyed fresh."],
         groups=[("WOOL", "Wool", "WHITE_WOOL"), ("CARPET", "Carpets", "WHITE_CARPET"),
                 ("STAINED_CLAY", "Terracotta", "WHITE_TERRACOTTA"), ("STAINED_GLASS", "Glass", "WHITE_STAINED_GLASS"),
                 ("STAINED_GLASS_PANE", "Glass Panes", "WHITE_STAINED_GLASS_PANE")]),
    dict(id="pig_shop", src=["PIG_SHOP_NPC"], name="&fPig Shop", ref="Pig Shop", island=HUB, anchor="npc.pig_shop",
         mode="secondary", cat="Pets", icon="PORKCHOP", where="the village",
         hello=["Oink. I mean - welcome!", "Shiny shards and pig things, best prices on the island."]),

    # ---- farming islands -------------------------------------------------------------------------
    dict(id="windmill_operator", src=["WINDMILL_OPERATOR_NPC"], name="&fWindmill Operator", ref="Windmill Operator",
         island="barn", anchor="npc.windmill_operator", mode="secondary", cat="Farming", icon="WHEAT", where="the windmill",
         hello=["The mill grinds all day; I sell what it doesn't.", "Same stock as the hub, no walk back."]),
    dict(id="rancher", src=["JAKE_NPC"], name="&fRancher Jed", ref="Jake", island="mushroom_desert", anchor="npc.rancher",
         mode="secondary", cat="Farming", icon="IRON_AXE", where="the trapper's den",
         hello=["Hunting critters takes the right axe.", "Bring your basic axe and I'll upgrade it."]),
    dict(id="chum_vendor", src=["MOBY_NPC"], name="&fBait-maker Wade", ref="Moby", island="mushroom_desert", anchor="npc.chum_vendor",
         mode="secondary", cat="Fishing", icon="BUCKET", where="the glowing mushroom cave",
         hello=["Chum! Glowing chum! The fish go wild for it."]),
    dict(id="surveyor", src=["TALBOT_NPC"], name="&fSurveyor Pruitt", ref="Talbot", island="mushroom_desert", anchor="npc.surveyor",
         mode="secondary", cat="Farming", icon="COMPASS", where="the trapper's den",
         hello=["Pelts are the currency out here.", "Trap enough and I'll make it worth your while."]),
    dict(id="fur_trader", src=["TONY_SHOP_NPC"], name="&fFur-trader Alder", ref="Tony", island="mushroom_desert", anchor="npc.fur_trader",
         mode="secondary", cat="Farming", icon="LEATHER", where="the trapper's den",
         hello=["I upgrade minions. I take pelts. Simple."]),

    # ---- the park -------------------------------------------------------------------------------
    dict(id="merry_norseman", src=["JOYFUL_VIKING_NPC"], name="&fMerry Norseman", ref="Joyful Viking", island="park",
         anchor="npc.merry_norseman", mode="secondary", cat="Foraging", icon="IRON_AXE", where="the jungle island",
         hello=["Hah! Another wanderer in the woods!", "My axes split trunks and skulls alike."]),
    dict(id="strategist", src=["MASTER_TACTICIAN_NPC"], name="&fStrategist Varro", ref="Master Tactician", island="park",
         anchor="npc.strategist", mode="secondary", cat="Combat", icon="IRON_SWORD", where="the dark thicket",
         hello=["Every battle is won before it starts.", "The right sword helps, of course."]),
    dict(id="shaman", src=["OLD_SHAMAN_NYKO_NPC"], name="&fElder Shaman Oku", ref="Old Shaman Nyko", island="park",
         anchor="npc.shaman", mode="secondary", cat="Combat", icon="GHAST_TEAR", where="the spruce woods",
         hello=["The forest speaks to those who listen.", "I sell its essence, for a price."]),

    # ---- mines ----------------------------------------------------------------------------------
    dict(id="gold_forger", src=["GOLD_FORGER_NPC"], name="&fGold Forger", ref="Gold Forger", island="gold_mine",
         anchor="npc.gold_forger", mode="contract", cat="Mining", icon="GOLD_INGOT",
         hello=["Gold, gold and more gold!", "Forged it all myself, down here in the heat."]),
    dict(id="iron_forger", src=["IRON_FORGER_NPC"], name="&fIron Forger", ref="Iron Forger", island="gold_mine",
         anchor="npc.iron_forger", mode="secondary", cat="Mining", icon="IRON_INGOT", where="next to the gold forger",
         hello=["My brother does gold. I do iron. Better metal."]),
    dict(id="blaster", src=["WALTER_NPC"], name="&fBlaster Wim", ref="Walter", island="deep_caverns", anchor="npc.blaster",
         mode="secondary", cat="Mining", icon="TNT", where="the gunpowder mines",
         hello=["Stand back! Further! Further!"]),
    dict(id="lamplighter", src=["LUMINA_NPC"], name="&fLamplighter Luminelle", ref="Lumina", island="dwarven_mines", anchor="npc.lamplighter",
         mode="secondary", cat="Mining", icon="LANTERN", where="the dwarven village",
         hello=["The mines are dark, but they needn't be."]),
    dict(id="pickwright", src=["BUBU_NPC"], name="&fPickwright Dobb", ref="Bubu", island="dwarven_mines", anchor="npc.pickwright",
         mode="secondary", cat="Mining", icon="DIAMOND_PICKAXE", where="the dwarven village",
         hello=["Broken pickaxe? I can mend it into something better."]),
    dict(id="foreman", src=["BULVAR_NPC"], name="&fForeman Bolgar", ref="Bulvar", island="dwarven_mines", anchor="npc.foreman",
         mode="secondary", cat="Minions", icon="COBBLESTONE", where="the dwarven village",
         hello=["Your minions could work harder. I can make them."]),
    dict(id="mine_cook", src=["BRYNMOR_NPC"], name="&fGrubmaster Tuck", ref="Brynmor", island="dwarven_mines", anchor="npc.mine_cook",
         mode="secondary", cat="Mining", icon="BAKED_POTATO", where="the dwarven village",
         hello=["Miners eat like dragons.", "My cooking puts fortune in your stomach."]),
    dict(id="fossil_researcher", src=["RESEARCHER_BERYL_NPC"], name="&fResearcher Opaline", ref="Researcher Beryl",
         island="glacite_tunnels", anchor="npc.fossil_researcher", mode="secondary", cat="Mining", icon="BONE_BLOCK",
         where="the base camp", hello=["Fossils! Under all that ice!", "Bring me essence and I'll lend you my tools."]),
    dict(id="hollows_merchant", src=["CHUNK_NPC"], name="&fHollows Merchant", ref="Chunk", island="crystal_hollows",
         anchor="npc.hollows_merchant", mode="contract", cat="Mining", icon="TNT",
         hello=["Some walls just need persuading.", "Boom. Persuaded."]),
    dict(id="gemcutter", src=["GEMMA_NPC"], name="&fGemcutter Isla", ref="Gemma", island="crystal_hollows", anchor="npc.gemcutter",
         mode="secondary", cat="Mining", icon="DIAMOND", where="the precursor remnants",
         hello=["A diamond is only a rock until it is cut."]),
    dict(id="tribal_trader", src=["ODAWA_NPC"], name="&fTribal Elder Wahu", ref="Odawa", island="crystal_hollows", anchor="npc.tribal_trader",
         mode="secondary", cat="Mining", icon="JUNGLE_LOG", where="the jungle",
         hello=["The sludge of the jungle is precious to my people.", "Bring it, and we will trade."]),

    # ---- combat islands -------------------------------------------------------------------------
    dict(id="crystal_mender", src=["SHAGGY_NPC"], name="&fCrystal Mender Thorne", ref="Shaggy", island="spiders_den",
         anchor="npc.crystal_mender", mode="secondary", cat="Combat", icon="AMETHYST_SHARD", where="near the spider queen's altar",
         hello=["The altar answers only to a crystal.", "Bring fragments and I'll shape one."]),
    dict(id="spider_tamer", src=["SPIDER_TAMER_NPC"], name="&fSpider Tamer", ref="Spider Tamer", island="spiders_den",
         anchor="npc.spider_tamer", mode="secondary", cat="Utility", icon="STRING", where="the base of the mound",
         hello=["They only bite when they're hungry. Usually."]),
    dict(id="end_archer", src=["GREGORY_NPC"], name="&fArcher Peregrine", ref="Gregory", island="the_end", anchor="npc.end_archer",
         mode="secondary", cat="Combat", icon="BOW", where="near the end gate",
         hello=["Endermen hate arrows. That's why I sell them."]),
    dict(id="pearl_dealer", src=["PEARL_DEALER_NPC"], name="&fPearl Dealer", ref="Pearl Dealer", island="the_end",
         anchor="npc.pearl_dealer", mode="secondary", cat="Combat", icon="ENDER_PEARL", where="the end's trading post",
         hello=["Pearls, eyes and the odd rare book.", "The void is generous to those who pay."]),

    # ---- crimson isle ---------------------------------------------------------------------------
    dict(id="peddler_dragontail", src=["AN_NPC"], name="&fDesert Peddler Kesh", ref="An", island="crimson_isle",
         anchor="npc.peddler_dragontail", mode="secondary", cat="Alchemy", icon="RED_SAND", where="the barbarian town",
         hello=["Red sand, blaze rods, everything a brewer needs."]),
    dict(id="peddler_scarleton", src=["ULYN_NPC"], name="&fMushroom Peddler Vos", ref="Ulyn", island="crimson_isle",
         anchor="npc.peddler_scarleton", mode="secondary", cat="Alchemy", icon="MYCELIUM", where="the mage town",
         hello=["Mycelium and nether goods, fresh off the fields."]),
    dict(id="brewer_scarleton", src=["EZEKIEL_NPC"], name="&fBrewmaster Calder", ref="Ezekiel", island="crimson_isle",
         anchor="npc.brewer_scarleton", mode="secondary", cat="Combat", icon="POTION", where="the mage town tavern",
         hello=["A warm drink against the cold of the void."]),
    dict(id="brewer_dragontail", src=["GROG_NPC"], name="&fBrewer Gruk", ref="Grog", island="crimson_isle",
         anchor="npc.brewer_dragontail", mode="secondary", cat="Combat", icon="POTION", where="the barbarian town tavern",
         hello=["Drink up! Barbarians don't do small cups."]),
    dict(id="generator_smith_scarleton", src=["HILDA_NPC"], name="&fForgewife Hessa", ref="Hilda", island="crimson_isle",
         anchor="npc.generator_smith_scarleton", mode="secondary", cat="Minions", icon="BLAZE_ROD", where="the mage town",
         hello=["Nether minions need nether upgrades."]),
    dict(id="generator_smith_dragontail", src=["MARTHOS_NPC"], name="&fForgemaster Marrok", ref="Marthos", island="crimson_isle",
         anchor="npc.generator_smith_dragontail", mode="secondary", cat="Minions", icon="MAGMA_CREAM", where="the barbarian town",
         hello=["My hammer makes minions work through the night."]),
    dict(id="lampwright", src=["LYS_NPC"], name="&fLampwright Sabine", ref="Lys", island="crimson_isle",
         anchor="npc.lampwright", mode="secondary", cat="Building", icon="LANTERN", where="the mage town",
         hello=["A lamp in every colour of the rainbow - and the rainbow too."]),
    dict(id="trophy_keeper", src=["ODGER_NPC"], name="&fTrophy Keeper Ogden", ref="Odger", island="crimson_isle",
         anchor="npc.trophy_keeper", mode="secondary", cat="Fishing", icon="COD", where="the lava fishing hut",
         hello=["Every trophy fish tells a story.", "Prove your skill and gear up like a true hunter."]),
    dict(id="phone_dealer", src=["UDEL_NPC"], name="&fPhone Dealer Ude", ref="Udel", island="crimson_isle",
         anchor="npc.phone_dealer", mode="secondary", cat="Utility", icon="PAPER", where="the mage town",
         hello=["Latest models, straight from the factory!"]),
    dict(id="frantic_engineer", src=["DESPERATE_ENGINEER_NPC"], name="&fFrantic Engineer", ref="Desperate Engineer",
         island="crimson_isle", anchor="npc.frantic_engineer", mode="secondary", cat="Utility", icon="FISHING_ROD",
         where="the smoldering tomb", hello=["Please, buy it! I need the coins to fix my machine!"]),
    dict(id="devotee", src=["DRAKUU_NPC"], name="&fDevotee Draven", ref="Drakuu", island="crimson_isle", anchor="npc.devotee",
         mode="secondary", cat="Accessories", icon="NETHER_STAR", where="the forgotten skull",
         hello=["The great beast below rewards its followers."]),
    dict(id="mage_envoy", src=["MAGE_EMISSARY_NPC"], name="&fMage Envoy", ref="Mage Emissary", island="crimson_isle",
         anchor="npc.mage_envoy", mode="secondary", cat="Combat", icon="BLAZE_POWDER", where="the mage town gate",
         hello=["The mages of the isle extend their welcome."]),
    dict(id="barbarian_envoy", src=["BARBARIAN_EMISSARY_NPC"], name="&fBarbarian Envoy", ref="Barbarian Emissary", island="crimson_isle",
         anchor="npc.barbarian_envoy", mode="secondary", cat="Combat", icon="IRON_AXE", where="the barbarian town gate",
         hello=["The barbarians trade with the strong."]),

    # ---- fishing / winter / dungeon hub ---------------------------------------------------------
    dict(id="scrapper", src=["JUNKER_JOEL_NPC"], name="&fScrapper Nettle", ref="Junker Joel", island="backwater_bayou",
         anchor="npc.scrapper", mode="secondary", cat="Fishing", icon="IRON_NUGGET", where="the docks",
         hello=["One fisher's junk is another fisher's treasure.", "Rusty coins welcome."]),
    dict(id="star_trader", src=["EINARY_NPC"], name="&fStargazer Eino", ref="Einary", island="jerrys_workshop",
         anchor="npc.star_trader", mode="secondary", cat="Seasonal", icon="NETHER_STAR", where="the workshop square",
         hello=["Every gift opened during the season brings a star.", "Collect them - I trade generously."]),
    dict(id="frost_vendor", src=["SHERRY_NPC"], name="&fFrost Vendor Sela", ref="Sherry", island="jerrys_workshop",
         anchor="npc.frost_vendor", mode="secondary", cat="Seasonal", icon="ICE", where="the workshop square",
         hello=["Ice, bait and festive treats!"]),
    dict(id="ice_carver", src=["TERRY_NPC"], name="&fIce Carver Tarn", ref="Terry", island="jerrys_workshop",
         anchor="npc.ice_carver", mode="secondary", cat="Seasonal", icon="BLUE_ICE", where="the frozen lake",
         hello=["Hunks of ice carve into lovely trinkets."]),
    dict(id="frozen_trader", src=["FROZEN_ALEX_NPC"], name="&fFrostbitten Aleks", ref="Frozen Alex", island="jerrys_workshop",
         anchor="npc.frozen_trader", mode="secondary", cat="Seasonal", icon="ENCHANTED_BOOK", where="the glacial cave",
         hello=["S-s-so c-cold... b-buy something?"]),
    dict(id="dungeon_quartermaster", src=["OPHELIA_NPC"], name="&fQuartermistress Odile", ref="Ophelia", island="dungeon_hub",
         anchor="npc.dungeon_quartermaster", mode="secondary", cat="Dungeons", icon="BOW", where="the dungeon hub plaza",
         hello=["Heading into the catacombs? Don't go empty-handed."]),
    dict(id="essence_broker", src=["MALIK_NPC"], name="&fEssence Broker Sameer", ref="Malik", island="dungeon_hub",
         anchor="npc.essence_broker", mode="secondary", cat="Dungeons", icon="WITHER_SKELETON_SKULL", where="the dungeon hub plaza",
         hello=["Essence buys what coins cannot."]),
]

# neu-repo shops deliberately NOT stationed by this module, and why (also printed in report.txt).
SKIPPED = {
    "ELIZABETH_NPC": "Community Shop: the bank module owns the bits shop and its NPC (CONTRACT/brief)",
    "ANITA_NPC": "contest medal shop: owned by farming (brief)",
    "SKYMART_NPC": "copper shop: owned by garden (brief)",
    "PESTHUNTER_PHILLIP_NPC": "pest shop: garden (instanced island, garden currency)",
    "FEAR_MONGERER_NPC": "Spooky Festival candy shop: owned by mayors (calendar event)",
    "TYASHOI_ALCHEMIST_NPC": "Spooky Festival trades: owned by mayors (calendar event)",
    "ORINGO_NPC": "Traveling Zoo: owned by mayors (calendar event)",
    "BAKER_NPC": "New Year celebration: owned by mayors (calendar event)",
    "DOUG_NPC": "carnival points: carnival is a mayor event (mayors)",
    "BINGO_NPC": "bingo points: no Bingo profile mode in this game",
    "FEAST_BAKER_SCOTT_NPC": "kernels: a time-limited reference event with no owner here",
    "LUDLETH_NPC": "Rose Dragon pet: 500M coins + copper + garden-only materials; garden content",
    "VARGUL_NPC": "spooky event currency, no island in the data",
    "LOTUS_EATER_NPC": "Lotus Atoll is not an island in this game",
    "RESEARCHER_RIBERY_NPC": "Lotus Atoll is not an island in this game",
    "GOLDEN_DRAGON_EGG_NPC": "no island in the data (pet upgrade altar)",
    "JADE_DRAGON_NPC": "Galatea pet: Galatea is not an island in this game",
    "HUSBANDRY_MERCHANT_NPC": "no island in the data (Galatea hunting); stock duplicated by the Farm/Adventurer lines",
    "DUNCAN_NPC": "no island in the data",
    "TOMIOKA_NPC": "no island in the data",
    "WINONA_NPC": "no island in the data (dungeon revive stone)",
    "ZODD_NPC": "no island in the data",
}
SKIP_ISLANDS = {"rift": "the Rift is wave 2 (motes currency belongs to the rift module)",
                "foraging_2": "Galatea is not an island in this game", "foraging_3": "Galatea is not an island in this game",
                "lotus_atoll": "Lotus Atoll is not an island in this game"}

# Requirement gates (api.util.Requirements keys). neu-repo carries none, so every gate here cites the research.
# A shop gate applies to every line it sells; selling to the merchant is never gated.
SHOP_REQ = {
    # research 04 §4.3: keys are bought from YOUR faction's emissary; crimson registers the faction: resolver
    "mage_envoy": "faction:mage",
    "barbarian_envoy": "faction:barbarian",
}
# (shop, result id) -> requirement key on top of the shop's. research 04 §4.3 rep column: 0 / 1k / 3k / 7k / 12k.
KEY_REP = {"KUUDRA_TIER_KEY": 0, "KUUDRA_HOT_TIER_KEY": 1000, "KUUDRA_BURNING_TIER_KEY": 3000,
           "KUUDRA_FIERY_TIER_KEY": 7000, "KUUDRA_INFERNAL_TIER_KEY": 12000}
LINE_REQ = {}
# The envoys ARE the reference's Emissaries: their Leviathan key lines ("emissary": true) take the same
# 5/10/15/20% coin discount at 1k/3k/7k/12k faction reputation as crimson's quartermaster (audit K17, SkyHanni
# misc/ItemDiscounts.json). Thresholds are the shops dial shops.emissary-discount.
EMISSARY = {"mage_envoy": "mage", "barbarian_envoy": "barbarian"}
for _fac in ("mage", "barbarian"):
    for _key, _rep in KEY_REP.items():
        if _rep > 0:
            LINE_REQ[(_fac + "_envoy", _key)] = "faction:%s:%d" % (_fac, _rep)

# (shop, result id) -> (coins, why). Only where the neu snapshot contradicts the research AND the difference is a
# coin printer. The Mage basic key is 7,600 in neu (every other key of both envoys matches research 04 §4.3), and
# four basic keys craft into a Hot key worth 400,000, so 7,600 x 4 would mint a Hot key for ~30k.
PRICE_FIX = {
    ("mage_envoy", "KUUDRA_TIER_KEY"): (200000.0, "research 04 §4.3: 200,000 (neu snapshot 7,600; 4 keys craft a Hot Key)"),
}

# Hunting attributes (shards) that take a percent off ONE merchant's coin prices (wave 5; hunting hand-off request 4).
# Woodchucker = "Reduces the cost of items in the Builder's Shop", Bloodbat = the same for the Bartender's.
HUNT_DISCOUNT = {"builder": "builder_discount", "bartender": "bartender_discount"}

# Merchants OTHER modules run with their own menus, so the Merchant Directory holds every merchant in the game
# (wave 5). Clicking one runs the owner's PLAYER command (never player text); a row only shows while that command
# is registered. Names are generic job titles: the owners name their own NPCs.
EXTERNAL = [
    dict(id="community_shop", name="&fCommunity Shop", island=HUB, category="Economy", icon="EMERALD", command="cshop",
         owner="bank", about="Boon Cookies, the Bits shop and account upgrades, in the Community Center."),
    dict(id="pet_sitter", name="&fPet Sitter", island=HUB, category="Pets", icon="BONE", command="petsitter",
         owner="pets", about="Pet rarity upgrades and pet supplies."),
    dict(id="medal_exchange", name="&fMedal Exchange", island=HUB, category="Farming", icon="GOLD_INGOT", command="medals",
         owner="farming", about="Trade Harvest Fair medals for farming gear."),
    dict(id="chocolate_shop", name="&fChocolate Shop", island=HUB, category="Events", icon="COCOA_BEANS", command="chocshop",
         owner="chocolate", about="Spend the chocolate your factory makes."),
    dict(id="spooky_shop", name="&fFestival Candy Stall", island=HUB, category="Events", icon="JACK_O_LANTERN", command="spooky",
         owner="mayors", about="Candy goods, while the Spooky Festival runs."),
    dict(id="travelling_zoo", name="&fTravelling Zoo", island=HUB, category="Events", icon="LEAD", command="zoo",
         owner="mayors", about="Pets for sale, when the zoo is in town."),
    dict(id="carnival_stall", name="&fCarnival Stall", island=HUB, category="Events", icon="CAKE", command="carnival",
         owner="mayors", about="Masks and tickets, while the carnival is in town."),
    dict(id="pickaxe_smith", name="&fPickaxe Smith", island="dwarven_mines", category="Mining", icon="IRON_PICKAXE",
         command="pickaxesmith", owner="mining", about="Pickaxes and their upgrades."),
    dict(id="faction_quartermaster", name="&fFaction Quartermaster", island="crimson_isle", category="Combat",
         icon="BLAZE_POWDER", command="factionshop", owner="crimson", about="Your own faction's goods."),
    dict(id="dungeon_shop", name="&fDungeon Supplies", island="dungeon_hub", category="Dungeons", icon="WITHER_SKELETON_SKULL",
         command="dungeons", args=["shop"], owner="dungeons", about="Supplies for the Catacombs."),
]

# The Island Decorator's goods (wave 5). No module owns island decorations / minigames (skills hand-off request 16)
# and the Decorator is our merchant, so shops registers them. Material, rarity and Social gate come from neu-repo;
# names and lore are ours. kind: game = played from the item (Decorations.java), place = a block for your island.
DECOR = [
    # id, our name, kind, social level, lore
    ("PARKOUR_CONTROLLER", "Parkour Start/End", "place", 1,
     ["A pressure plate that marks where a", "parkour course on your private", "island starts and ends."]),
    ("PARKOUR_POINT", "Parkour Checkpoint", "place", 1,
     ["A pressure plate that marks a", "checkpoint along a parkour course", "on your private island."]),
    ("PARKOUR_TIMES", "Parkour Times", "place", 1,
     ["A plinth for the best parkour", "times on your island."]),
    ("SOCIAL_DISPLAY", "Social Display", "place", 2,
     ["A golden plinth honouring the most", "active visitors of your island."]),
    ("EGG_HUNT", "Egg Hunt", "place", 3,
     ["Hide eggs around your island for", "your guests to find.", "", "&7Maximum of &a10&7."]),
    ("ISLAND_NPC", "Island NPC", "place", 5,
     ["A resident for your private world.", "Give your island some company."]),
    ("TIC_TAC_TOE", "Tic Tac Toe", "game", 6,
     ["&eRight-click &7another player to", "challenge them to a game of", "Tic Tac Toe!"]),
    ("CONNECT_FOUR", "Four in a Row", "game", 7,
     ["&eRight-click &7another player to", "challenge them to Four in a Row:", "drop discs, line up four!"]),
    ("ROCK_PAPER_SHEARS", "Rock Paper Snips", "game", 8,
     ["&eRight-click &7to start a tournament", "with every player near you.", "",
      "Each round, the LEAST picked sign", "wins and its players go through.",
      "The final two play by the classic", "rules."]),
    ("SHOWCASE_BLOCK", "Showcase Block", "place", 10,
     ["A fancy glass display case for", "your island."]),
]
TIERS = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"]
# The Social road unlocks each one "to buy from the Island Decorator" (skills hand-off: Social 1..10), so the line is
# gated as well as the item's use.
for _iid, _n, _k, _soc, _l in DECOR:
    LINE_REQ[("decorator", _iid)] = "skill:social:%d" % _soc
# The Bartender's slayer mixins need that slayer at level 8 to BUY, not only to drink (research 06; slayer request 14,
# wave 5): otherwise anyone can buy one and only drinking it says no.
for _iid, _type in (("ZOMBIE_BRAIN_MIXIN", "zombie"), ("SPIDER_EGG_MIXIN", "spider"), ("WOLF_FUR_MIXIN", "wolf"),
                    ("END_PORTAL_FUMES_MIXIN", "enderman"), ("GABAGOEY_MIXIN", "blaze")):
    LINE_REQ[("bartender", _iid)] = "slayer:%s:8" % _type


def decor_items():
    out = []
    for iid, name, kind, social, lore in DECOR:
        n = load_json(os.path.join(NEU, iid + ".json"))
        rarity = "COMMON"
        for l in reversed(n.get("lore", [])):
            w = strip(l).strip().split(" ")[0] if strip(l).strip() else ""
            if w in TIERS:
                rarity = w
                break
        mat = (n.get("itemid") or "minecraft:stone").split(":", 1)[-1].upper()
        if mat == "SKULL":
            mat = "PLAYER_HEAD"
        d = {"id": iid, "name": name, "material": mat, "rarity": rarity, "category": "COSMETIC",
             "lore": ["&7" + x if not x.startswith("&") and x else x for x in lore],
             "requirements": ["skill:social:%d" % social], "npcSell": 0,
             "maxStack": 1 if kind == "game" or iid == "ISLAND_NPC" else 64,
             # never "placeable" on a spawn egg: placing one would spawn the vanilla mob (core's guard denies it)
             "tags": ["placeable", "no_sell"] if kind == "place" and not mat.endswith("SPAWN_EGG") else ["no_sell"],
             "extra": {"decor": kind}}
        if mat == "PLAYER_HEAD":
            skin = skin_of(n)
            if skin:
                d["skull"] = skin
        if kind == "game":
            ab = {"TIC_TAC_TOE": ("shops_tic_tac_toe", "Challenge", 0),
                  "CONNECT_FOUR": ("shops_connect_four", "Challenge", 0),
                  "ROCK_PAPER_SHEARS": ("shops_rock_paper_shears", "Tournament", 60)}[iid]
            d["abilities"] = [{"id": ab[0], "name": ab[1], "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": ab[2], "lore": []}]
        out.append(d)
    return out


# Currency ids: neu "SKYBLOCK_<X>" -> ours. coins/bits are core economy; the rest need their owner's API.
CURRENCIES = {
    "COIN": "coins", "BIT": "bits", "PELT": "pelt", "NORTH_STAR": "north_star", "COPPER": "copper", "MOTE": "mote",
    "PEST": "pest", "BINGO_POINT": "bingo_point", "KERNEL": "kernel", "CARNIVAL_POINT": "carnival_point",
    "BRONZE_MEDAL": "bronze_medal", "SILVER_MEDAL": "silver_medal", "GOLD_MEDAL": "gold_medal", "GEM": "gem",
    "FLY": "fly", "SILVERFISH": "silverfish", "SPIDER": "spider",
}

# Builder stock. The research gives only "~1-10 coins per block" (research 03 §4.4), so these are TUNE:
# price = max(group base, ceil(reference NPC sell x 1.25)), and the runtime arbitrage floor still applies.
BUILDER_GROUPS = [
    ("stone", "Stone & Brick", "STONE_BRICKS", 3, [
        "stone", "cobblestone", "mossy_cobblestone", "smooth_stone", "stone_bricks", "mossy_stone_bricks",
        "cracked_stone_bricks", "chiseled_stone_bricks", "andesite", "polished_andesite", "diorite", "polished_diorite",
        "granite", "polished_granite", "bricks", "mud_bricks", "cobbled_deepslate", "polished_deepslate",
        "deepslate_bricks", "deepslate_tiles", "tuff", "tuff_bricks", "calcite", "sandstone", "cut_sandstone",
        "smooth_sandstone", "chiseled_sandstone", "red_sandstone", "cut_red_sandstone", "smooth_red_sandstone"]),
    ("fancy", "Fancy Stone", "QUARTZ_BLOCK", 6, [
        "quartz_block", "quartz_pillar", "chiseled_quartz_block", "quartz_bricks", "smooth_quartz", "prismarine",
        "prismarine_bricks", "dark_prismarine", "nether_bricks", "red_nether_bricks", "blackstone",
        "polished_blackstone", "polished_blackstone_bricks", "end_stone_bricks", "purpur_block", "purpur_pillar",
        "packed_mud", "terracotta", "basalt", "polished_basalt"]),
    ("wood", "Wood", "OAK_PLANKS", 2, [
        "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks",
        "mangrove_planks", "cherry_planks", "pale_oak_planks", "bamboo_planks", "bamboo_mosaic", "crimson_planks",
        "warped_planks", "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_jungle_log",
        "stripped_acacia_log", "stripped_dark_oak_log", "stripped_cherry_log", "oak_wood", "spruce_wood", "birch_wood"]),
    ("shapes", "Slabs & Stairs", "OAK_STAIRS", 2, [
        "oak_slab", "oak_stairs", "spruce_slab", "spruce_stairs", "birch_slab", "birch_stairs", "dark_oak_slab",
        "dark_oak_stairs", "stone_slab", "stone_stairs", "cobblestone_slab", "cobblestone_stairs", "stone_brick_slab",
        "stone_brick_stairs", "smooth_stone_slab", "sandstone_slab", "sandstone_stairs", "brick_slab", "brick_stairs",
        "deepslate_brick_slab", "deepslate_brick_stairs", "quartz_slab", "quartz_stairs", "cobblestone_wall",
        "stone_brick_wall", "brick_wall"]),
    ("glass", "Glass & Light", "GLASS", 3, [
        "glass", "glass_pane", "tinted_glass", "torch", "soul_torch", "lantern", "soul_lantern", "sea_lantern",
        "glowstone", "shroomlight", "end_rod", "redstone_lamp", "candle", "iron_chain", "iron_bars"]),
    ("colour", "Concrete", "WHITE_CONCRETE", 4, [
        c + "_concrete" for c in ["white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime",
                                  "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"]]),
    ("nature", "Nature", "GRASS_BLOCK", 2, [
        "dirt", "grass_block", "coarse_dirt", "podzol", "rooted_dirt", "mud", "moss_block", "moss_carpet", "clay",
        "sand", "red_sand", "gravel", "snow_block", "ice", "packed_ice", "oak_leaves", "spruce_leaves", "birch_leaves",
        "jungle_leaves", "azalea_leaves", "flowering_azalea_leaves", "cherry_leaves", "oak_sapling", "birch_sapling",
        "spruce_sapling", "cherry_sapling", "poppy", "dandelion", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
        "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "short_grass", "fern",
        "lily_pad", "vine", "glow_lichen", "pumpkin", "carved_pumpkin", "hay_block"]),
    ("decor", "Decoration", "BOOKSHELF", 5, [
        "bookshelf", "chiseled_bookshelf", "flower_pot", "ladder", "scaffolding", "oak_fence", "oak_fence_gate",
        "spruce_fence", "dark_oak_fence", "oak_door", "spruce_door", "oak_trapdoor", "spruce_trapdoor", "barrel",
        "crafting_table", "furnace", "smoker", "campfire", "soul_campfire", "anvil", "cauldron", "bell",
        "white_bed", "red_bed", "white_banner", "red_banner", "lectern", "loom", "composter", "beehive", "decorated_pot"]),
]
BUILDER_SINGLE_PRICE_CAP = 25

# 1.8-era vanilla names neu still uses that neither core's alias table nor crafting's map covers.
EXTRA_ALIASES = {
    "WOOD_SPADE": "WOODEN_SHOVEL", "WOOD_SWORD": "WOODEN_SWORD", "WOOD_PICKAXE": "WOODEN_PICKAXE",
    "WOOD_AXE": "WOODEN_AXE", "WOOD_HOE": "WOODEN_HOE", "GOLD_SPADE": "GOLDEN_SHOVEL", "GOLD_PICKAXE": "GOLDEN_PICKAXE",
    "GOLD_AXE": "GOLDEN_AXE", "GOLD_HOE": "GOLDEN_HOE", "GOLD_SWORD": "GOLDEN_SWORD", "GOLD_HELMET": "GOLDEN_HELMET",
    "GOLD_CHESTPLATE": "GOLDEN_CHESTPLATE", "GOLD_LEGGINGS": "GOLDEN_LEGGINGS", "GOLD_BOOTS": "GOLDEN_BOOTS",
    "IRON_SPADE": "IRON_SHOVEL", "DIAMOND_SPADE": "DIAMOND_SHOVEL", "STONE_SPADE": "STONE_SHOVEL",
    "CHAINMAIL_HELMET": "CHAINMAIL_HELMET", "ENDER_STONE": "END_STONE", "REDSTONE_TORCH_ON": "REDSTONE_TORCH",
    "WOOD_PLATE": "OAK_PRESSURE_PLATE", "STONE_PLATE": "STONE_PRESSURE_PLATE", "IRON_PLATE": "HEAVY_WEIGHTED_PRESSURE_PLATE",
    "GOLD_PLATE": "LIGHT_WEIGHTED_PRESSURE_PLATE", "DIODE": "REPEATER", "REDSTONE_COMPARATOR": "COMPARATOR",
    "MYCEL": "MYCELIUM", "QUARTZ": "QUARTZ", "SPECKLED_MELON": "GLISTERING_MELON_SLICE", "NETHER_STALK": "NETHER_WART",
    "BREWING_STAND_ITEM": "BREWING_STAND", "SULPHUR": "GUNPOWDER", "EXP_BOTTLE": "EXPERIENCE_BOTTLE",
}  # TUNE: nothing the Builder stocks costs more than this per block

# Talk-only hub townsfolk (lore NPCs). Anchors npc.lore.1..N are requested from the hub builder; each NPC
# spawns only when its anchor exists. Names and lines are ours. Skins: shopless hub NPCs of neu-repo.
TOWNSFOLK = [
    ("townsfolk_maren", "&fMaren", ["Welcome to the village, newcomer!", "The Bazaar is busiest at dusk. Prices move fast then."]),
    ("townsfolk_osric", "&fOld Ebbert", ["I've lived here since the first island floated up.", "Back then the graveyard was quiet. Not any more."]),
    ("townsfolk_pella", "&fPella", ["Have you seen the fairies by the pond?", "They say the souls hidden around the hub make you stronger."]),
    ("townsfolk_dorran", "&fDorran the Guard", ["Keep your blade sheathed in the village.", "Save the fighting for the crypts."]),
    ("townsfolk_juno", "&fJuno", ["I sold everything I farmed to the merchants yesterday.", "Merchants buy anything sellable. Bundle it in your inventory and click!"]),
    ("townsfolk_bram", "&fCobbett the Miner", ["The coal mine is just the start.", "Go deep enough and the dwarves will greet you."]),
    ("townsfolk_tilly", "&fTilly", ["Mum says never sell what you might need later.", "If you do, the merchant can sell it back for the same price - quickly!"]),
    ("townsfolk_ewart", "&fEwart", ["The banker pays interest, you know.", "Coins in a purse vanish when you die. Coins in a bank do not."]),
    ("townsfolk_sabra", "&fSabra the Fisher", ["The pond hides more than fish.", "Some say something enormous lives under the bayou."]),
    ("townsfolk_quill", "&fScribe Thaddeus", ["Every recipe you unlock is written in your Recipe Book.", "Collect more of a material to learn new crafts."]),
    ("townsfolk_hester", "&fHester", ["Daily limits? The merchants only stock so much a day.", "Six hundred and forty of anything, then come back tomorrow."]),
    ("townsfolk_rook", "&fHesketh", ["Psst. The auction house has bargains if you are patient.", "Buy it now, or bid and wait. Your choice."]),
]


# --------------------------------------------------------------------------------------------------

def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def strip(s):
    return re.sub("§.", "", s or "")


def norm_id(raw):
    """neu 'INK_SACK-3' -> 'INK_SACK:3'; trailing ':1.0' handled by split_amount."""
    return raw.replace("-", ":")


def split_amount(token):
    """neu: 'WHEAT:3' -> ('WHEAT', 3); 'X' -> ('X', 1). Call BEFORE norm_id: neu writes meta as '-' ('INK_SACK-3'),
    so in a raw neu token a ':' is always an amount."""
    if ":" in token:
        head, tail = token.rsplit(":", 1)
        try:
            v = float(tail)
            return head, v
        except ValueError:
            pass
    return token, 1.0


def skin_of(npc):
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', npc.get("nbttag", ""))
    return m.group(1) if m else None


def main():
    npcs = {}
    for f in glob.glob(os.path.join(NEU, "*_NPC.json")):
        d = load_json(f)
        npcs[d["internalname"]] = d

    # Material <-> legacy id, from core's alias table.
    valias = load_json(os.path.join(RES, "data", "core-items", "vanilla_aliases.json"))
    legacy_to_mat = {}
    for mat, legs in valias.items():
        for l in legs:
            legacy_to_mat.setdefault(l.replace("-", ":"), mat)
    crafting_alias_path = os.path.join(RES, "data", "crafting", "neu_aliases.json")
    crafting_alias = load_json(crafting_alias_path) if os.path.exists(crafting_alias_path) else {}
    known_blocks = set(l.strip() for l in open(os.path.join(RES, "gen", "known_blocks.txt"), encoding="utf-8") if l.strip())
    ref_items = {i["id"]: i for i in load_json(os.path.join(ROOT, "research", "data", "items.json"))["items"]}

    # Every id another module ships (advisory coverage only - the live self-test is the authority).
    known_ids = set()
    for f in glob.glob(os.path.join(RES, "data", "*", "*.json")):
        if os.sep + "shops" + os.sep in f:
            continue
        try:
            d = load_json(f)
        except Exception:
            continue
        if isinstance(d, list):
            for x in d:
                if isinstance(x, dict) and "id" in x and ("material" in x or "rarity" in x):
                    known_ids.add(x["id"])
    known_ids |= set(valias.keys()) | set(b.upper() for b in known_blocks)
    known_ids |= set(d[0] for d in DECOR)   # registered by this module (data/shops/items.json)

    def neu_item(i):
        p = os.path.join(NEU, i.replace(":", "-") + ".json")
        return load_json(p) if os.path.exists(p) else None

    def candidates(item_id):
        """Primary id first, then alternates. '@MAT' = resolve through core's vanillaId(Material)."""
        out = []
        if ";" in item_id:
            base, lvl = item_id.split(";", 1)
            ni = neu_item(item_id)
            if ni and "enchanted_book" in (ni.get("itemid") or ""):
                out.append("ENCHANTMENT_%s_%s" % (base, lvl))
        out.append(item_id)
        # legacy reference ids first: core converts vanilla pickups with this same table, so a shop item and a
        # picked-up item must resolve alike ('PUMPKIN' is core's id for CARVED_PUMPKIN, 'MELON' for MELON_SLICE)
        if item_id in legacy_to_mat:
            out.append("@" + legacy_to_mat[item_id])
        ca = crafting_alias.get(item_id)
        if ca and ca != item_id:
            out.append("@" + ca if ca in valias or ca.lower() in known_blocks else ca)
        if item_id in EXTRA_ALIASES:
            out.append("@" + EXTRA_ALIASES[item_id])
        if item_id in valias or item_id.lower() in known_blocks:
            out.append("@" + item_id)
        seen, res = set(), []
        for c in out:
            if c not in seen:
                seen.add(c)
                res.append(c)
        return res

    report = []
    coverage_missing = []
    shops_out = []
    used_src = set()

    def covered(cands):
        # '@X' always resolves at runtime when X is a real Material (core registers every vanilla material);
        # enchanting registers every book level IN CODE (ENCHANTMENT_<ENCH>_<LVL>), so no JSON lists them
        if any(c.startswith("ENCHANTMENT_") for c in cands):
            return True
        return any(c.startswith("@") and c[1:] in EXTRA_ALIASES.values() or (c[1:] if c.startswith("@") else c) in known_ids
                   for c in cands)

    for s in SHOPS:
        entries = []
        seen = set()
        island_ref = None
        skin = None
        for src in s["src"]:
            used_src.add(src)
            d = npcs.get(src)
            if d is None:
                report.append("MISSING neu file %s for shop %s" % (src, s["id"]))
                continue
            island_ref = island_ref or d.get("island")
            skin = skin or skin_of(d)
            for r in d.get("recipes", []):
                if r.get("type") != "npc_shop":
                    continue
                rid, ramt = split_amount(r["result"])
                rid = norm_id(rid)
                coins, bits, items, cur = 0.0, 0, [], []
                for c in r["cost"]:
                    cid, camt = split_amount(c)
                    cid = norm_id(cid)
                    if cid.startswith("SKYBLOCK_") and cid[9:] in CURRENCIES:
                        k = CURRENCIES[cid[9:]]
                        if k == "coins":
                            coins += camt
                        elif k == "bits":
                            bits += int(camt)
                        else:
                            cur.append({"id": k, "amount": int(math.ceil(camt))})
                    else:
                        items.append({"item": candidates(cid)[0], "alts": candidates(cid)[1:], "amount": int(math.ceil(camt))})
                key = (rid, int(ramt), coins, bits, json.dumps(items, sort_keys=True), json.dumps(cur, sort_keys=True))
                if key in seen:
                    continue
                seen.add(key)
                cands = candidates(rid)
                e = {"item": cands[0], "alts": cands[1:], "amount": max(1, int(round(ramt)))}
                if coins:
                    e["coins"] = coins
                if bits:
                    e["bits"] = bits
                if items:
                    e["items"] = items
                if cur:
                    e["currency"] = cur
                fix = PRICE_FIX.get((s["id"], rid))
                if fix and coins:
                    e["coins"] = fix[0]
                    e["note"] = fix[1]
                req = LINE_REQ.get((s["id"], rid))
                if req:
                    e["req"] = req
                if s["id"] in EMISSARY and rid in KEY_REP:
                    e["emissary"] = True
                if "gem" in [x["id"] for x in cur]:
                    report.append("DROPPED %s -> %s: costs gems (real money in the reference; no-pay-to-win)" % (s["id"], rid))
                    continue
                if s.get("groups"):
                    base = rid.split(":")[0]
                    e["group"] = base
                if not covered(cands):
                    coverage_missing.append("%s: %s" % (s["id"], rid))
                for it in items:
                    if not covered([it["item"]] + it["alts"]):
                        coverage_missing.append("%s: cost %s" % (s["id"], it["item"]))
                entries.append(e)

        if s["id"] == "builder":
            groups = []
            for gid, gname, gicon, base, mats in BUILDER_GROUPS:
                groups.append({"id": gid, "name": gname, "icon": gicon})
                for m in mats:
                    if m not in known_blocks:
                        report.append("BUILDER: %s is not a 26.1.2 block - skipped" % m)
                        continue
                    M = m.upper()
                    legacy = valias.get(M, [])
                    ref_sell = 0
                    for l in [M] + [x.replace("-", ":") for x in legacy]:
                        if l in ref_items and ref_items[l].get("npc_sell_price"):
                            ref_sell = ref_items[l]["npc_sell_price"]
                            break
                    price = min(BUILDER_SINGLE_PRICE_CAP, max(base, int(math.ceil(ref_sell * 1.25))))
                    entries.append({"item": "@" + M, "alts": [], "amount": 1, "coins": float(price), "group": gid, "tune": True})
            s = dict(s, groups_out=groups)

        out = {
            "id": s["id"], "name": s["name"], "island": s["island"], "anchor": s["anchor"], "mode": s["mode"],
            "category": s["cat"], "icon": s.get("icon", "EMERALD"), "greeting": s["hello"],
            "source": s["src"], "refIsland": island_ref,
        }
        if s.get("where"):
            out["where"] = s["where"]
        if s["id"] in SHOP_REQ:
            out["req"] = SHOP_REQ[s["id"]]
        if s["id"] in HUNT_DISCOUNT:
            out["discountAttr"] = HUNT_DISCOUNT[s["id"]]
        if s["id"] in EMISSARY:
            out["emissaryFaction"] = EMISSARY[s["id"]]
        if skin:
            out["skin"] = skin
        if s.get("groups"):
            out["groups"] = [{"id": g[0], "name": g[1], "icon": g[2]} for g in s["groups"]]
        if s.get("groups_out"):
            out["groups"] = s["groups_out"]
        out["entries"] = entries
        if not entries:
            report.append("EMPTY shop %s" % s["id"])
        shops_out.append(out)

    # skipped report
    report.append("")
    report.append("== neu-repo shops not stationed by this module ==")
    for k, d in sorted(npcs.items()):
        if not any(r.get("type") == "npc_shop" for r in d.get("recipes", [])):
            continue
        if k in used_src:
            continue
        why = SKIPPED.get(k) or SKIP_ISLANDS.get(d.get("island") or "", None) or "UNMAPPED"
        report.append("%-38s %-16s %s" % (k, d.get("island"), why))

    # townsfolk skins: shopless hub NPCs, deterministic
    shopless = sorted(k for k, d in npcs.items() if d.get("island") == "hub"
                      and not any(r.get("type") == "npc_shop" for r in d.get("recipes", [])) and skin_of(d))
    towns = []
    for i, (tid, tname, lines) in enumerate(TOWNSFOLK):
        skin = skin_of(npcs[shopless[(i * 5) % len(shopless)]]) if shopless else None
        towns.append({"id": tid, "name": tname, "island": "hub", "anchor": "npc.lore.%d" % (i + 1),
                      "lines": lines, "skin": skin})

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "shops.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(shops_out, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT_DIR, "townsfolk.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(towns, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT_DIR, "items.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(decor_items(), f, indent=1, ensure_ascii=False)
    ext = [dict(x, args=x.get("args", [])) for x in EXTERNAL]
    with open(os.path.join(OUT_DIR, "external.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(ext, f, indent=1, ensure_ascii=False)

    # names doc
    lines = ["# shops - renames", "",
             "Generated by `tools/gen/shops/gen_shops.py` (edit the CONFIG there, then re-run). Item names are owned by",
             "the modules that register the items; this table covers the merchants (NPCs) and townsfolk this module adds.",
             "Generic job titles (Farm Merchant, Builder, Librarian, Gold Forger...) stay generic.", "",
             "| id | reference name | our name |", "|---|---|---|"]
    for s in SHOPS:
        lines.append("| `shops_%s` | %s | %s |" % (s["id"], s["ref"], strip(s["name"].replace("&f", ""))))
    for (tid, tname, _l) in TOWNSFOLK:
        lines.append("| `%s` | (none - original townsfolk) | %s |" % (tid, tname.replace("&f", "")))
    lines += ["", "Island Decorator goods registered by this module (wave 5). Reference names from neu-repo:", "",
              "| id | reference name | our name |", "|---|---|---|"]
    for iid, name, _k, _s, _l in DECOR:
        n = load_json(os.path.join(NEU, iid + ".json"))
        lines.append("| `%s` | %s | %s |" % (iid, strip(n.get("displayname", iid)), name))
    lines += ["", "Places named in dialogue are described generically (\"the mage town\", \"the barbarian town\", \"the spider",
              "queen's altar\") rather than with the reference's place names."]
    with open(os.path.join(ROOT, "docs", "names", "shops.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    total = sum(len(s["entries"]) for s in shops_out)
    head = ["shops: %d merchants, %d stock lines, %d townsfolk" % (len(shops_out), total, len(towns)),
            "coverage (advisory, vs other modules' shipped data): %d stock/cost ids not found" % len(coverage_missing), ""]
    with open(os.path.join(os.path.dirname(__file__), "report.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(head + report + ["", "== ids not (yet) shipped by any module =="] + sorted(coverage_missing)) + "\n")
    print("\n".join(head))


if __name__ == "__main__":
    sys.exit(main())
