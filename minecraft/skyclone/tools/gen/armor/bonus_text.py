# -*- coding: utf-8 -*-
"""
Hand-written set-bonus text for the armor module (OUR words, the reference game's numbers).

Every entry is keyed by the reference set key (museum "armor_set_donation_xp" key, or the id
with the slot suffix stripped). Values are a list of bonuses:

    (kind, name, [lines])

kind:
  FULL    -> ItemDef.setBonusName / setBonusLore (core renders "Full Set Bonus: <name>")
  TIERED  -> description lines "&6Tiered Bonus: <name>" (scales with pieces worn)
  PIECE   -> description lines "&6Piece Bonus: <name>" (each piece on its own)
  ABILITY -> description lines "&6Ability: <name>" (passive / worn ability)

The behaviour for every bonus written here lives in com.skyclone.mod.armor (BonusStats,
BonusCombat, BonusListener). A bonus marked "(display)" in a comment is lore only: another
module owns the system it touches (see docs/handoff/armor.md).
"""

S = {
    "hp": "&c❤ Health", "def": "&a❈ Defense", "str": "&c❁ Strength", "spd": "&f✦ Speed",
    "int": "&b✎ Intelligence", "cd": "&9☠ Crit Damage", "cc": "&9☣ Crit Chance",
    "mf": "&b✯ Magic Find", "ms": "&6⸕ Mining Speed", "ff": "&6☘ Farming Fortune",
    "scc": "&3α Sea Creature Chance", "fs": "&b☂ Fishing Speed", "tc": "&6⛃ Treasure Chance",
    "regen": "&c❣ Health Regen", "spread": "&e▚ Mining Spread", "fer": "&c⫽ Ferocity",
}

BONUS = {
    # ---------------------------------------------------------------- dragons
    "YOUNG_DRAGON": [("FULL", "Young Blood", [
        "&7While you stand above &a50% &7of your", "&7max " + S["hp"] + "&7, gain &a+70 " + S["spd"] + "&7."])],
    "OLD_DRAGON": [("FULL", "Old Blood", [
        "&7The &aGrowth&7, &aProtection&7, &aFeather Falling&7,", "&aSugar Rush &7and &aTrue Protection &7enchants",
        "&7on this set grant &a50% &7more of their stats."])],
    "STRONG_DRAGON": [("FULL", "Strong Blood", [
        "&7Your &5teleporting blade &7deals &c+75", "&7extra base damage and teleports &a+2", "&7blocks further, and each teleport", "&7grants &c+5 " + S["str"] + " &7for &a5s&7."])],
    "UNSTABLE_DRAGON": [("FULL", "Unstable Blood", [
        "&7Every few seconds a bolt of lightning", "&7leaps from you to a nearby enemy,", "&7dealing &c3,000 &7damage."])],
    "WISE_DRAGON": [("FULL", "Wise Blood", ["&7Every ability costs only &b2/3 &7of its", "&7usual mana."])],
    "PROTECTOR_DRAGON": [("FULL", "Protective Blood", [
        "&7Each piece gains &a+1% " + S["def"], "&7for every &a1% &7of health you are missing."])],
    "HOLY_DRAGON": [("FULL", "Holy Blood", [
        "&7You and every player within &a10", "&7blocks gain &a+75 " + S["regen"] + "&7.", "&8Only one wearer's aura applies."])],
    "SUPERIOR_DRAGON": [("FULL", "Superior Blood", [
        "&7All of your combat stats and " + S["mf"], "&7are raised by &a5%&7, and the dragon-breath", "&7ability of your blade deals &c+50% &7damage."])],
    "END": [("PIECE", "Ender Resonance", ["&7While you are on &5The End&7, every", "&7stat on this piece is &adoubled&7."])],
    # ---------------------------------------------------------------- early combat
    "GOLEM_ARMOR": [("FULL", "Absorption", ["&7Killing an enemy wraps you in &6Absorption", "&6III &7(&e+60 &7absorption health) for &a20s&7."])],
    "MERCENARY": [("FULL", "Death Tax", ["&7Each kill of a &aLevel 10+ &7enemy pays", "&6+5 coins &7and heals you for &c20 ❤&7."])],
    "EMERALD_ARMOR": [("FULL", "Tank", ["&7Gain &c+1 ❤ Health &7and &a+1 ❈ Defense &7for", "&7every &a3,000 &7Emeralds in your collection.", "&8Maximum +350 each."])],
    "SPEEDSTER": [("FULL", "Bonus Speed", ["&7Grants &a+20 " + S["spd"] + "&7."])],
    "CACTUS": [("FULL", "Deflect", ["&7Thorns! &a33% &7of the damage an enemy", "&7deals to you is thrown back at it."])],
    "MUSHROOM": [("FULL", "Night Affinity", ["&7Grants permanent &bNight Vision &7while", "&7the whole set is worn."])],
    "RABBIT": [("FULL", "Springsneak &e&lSNEAK", ["&7Hold sneak to gain &aJump Boost II&7."])],
    "ZOMBIE": [("FULL", "Projectile Absorption", ["&7When a projectile hits you, heal &c10 ❤", "&7every second for &a5s&7."])],
    "GROWTH": [("PIECE", "Growth", ["&7Killing a monster heals &c1% &7of your max", "&7health and grows this piece by &c+1 ❤", "&7Health &8(max +100, once every 4s)&7."])],
    "ARMOR_OF_MAGMA": [("FULL", "Absorb", ["&7Every &a10 &7Magma Cubes you kill while", "&7wearing the set grant &c+1 ❤ Health &7and", "&b+1 ✎ Intelligence&7. &8Max +200 each."])],
    "CRYSTAL": [("FULL", "Refraction", ["&7This armor's stats shift between &a0%", "&7and &a200% &7depending on how bright", "&7the light around you is."])],
    "SPOOKY": [("FULL", "Candy Man", ["&7Raises your chance to find rare", "&dCandy &7by &a+5%&7."])],
    "BAT_PERSON": [("FULL", "Into the Night", ["&7Raises your chance to find rare", "&dCandy &7by &a+5%&7.", "&8The city needs a hero."])],
    "GREAT_SPOOK": [("FULL", "Fearsome", ["&7Monsters at or below your total &5Fear", "&7level run away from you.", "&8Brewed during the alchemist's trials."]),
                    ("FULL", "Spooky", ["&7Punch another player with the", "&5Great Spook Staff &7to give them a fright."])],
    "STARLIGHT": [("FULL", "Starpower", ["&7Your falling-star wand spell lasts", "&7twice as long and reaches &a+1 &7block", "&7further."])],
    "CHEAP_TUXEDO": [("FULL", "Dashing", ["&7Your max " + S["hp"] + " &7is fixed at &c75&7,", "&7but you deal &c+50% &7damage.", "&8Very stylish."])],
    "FANCY_TUXEDO": [("FULL", "Dashing", ["&7Your max " + S["hp"] + " &7is fixed at &c150&7,", "&7but you deal &c+100% &7damage.", "&8Very stylish."])],
    "ELEGANT_TUXEDO": [("FULL", "Dashing", ["&7Your max " + S["hp"] + " &7is fixed at &c250&7,", "&7but you deal &c+150% &7damage.", "&8Very stylish."])],
    "WEREWOLF": [("FULL", "Regenerative Howl", ["&7Whenever " + S["fer"] + " &7triggers, gain", "&a+50 " + S["def"] + " &7for &a5s &8(stacks up to 10)&7."])],
    "ARACHNE": [("TIERED", "Silkmother's Faithful", ["&7Each piece grants &c+5 ❤ Health &7and", "&a+5 ❈ Defense&7."])],
    "FAIRY": [("FULL", "Fairy's Outfit", ["&7Gain &c+1 ❤ Health &7for every &dFairy", "&dSoul &7you have found."])],
    "CHALLENGER": [("ABILITY", "Mythos' Might", ["&7Doubles this piece's stats in the &bHub", "&7while the &6Mythic Ritual &7is running."]),
                   ("TIERED", "Unearthed", ["&7Take &a5% &7less damage from mythic", "&7creatures for each piece worn."])],
    "MYTHOS": [("ABILITY", "Mythos' Might", ["&7Doubles this piece's stats in the &bHub", "&7while the &6Mythic Ritual &7is running."]),
               ("TIERED", "Unearthed", ["&7Take &a5% &7less damage from mythic", "&7creatures for each piece worn."]),
               ("TIERED", "Familiarity", ["&7Gain &b+2.5 " + S["mf"] + " &7per piece", "&7against mythic creatures."])],
    "MINOS_HUNTER": [("ABILITY", "Mythos' Might", ["&7Doubles this piece's stats in the &bHub", "&7while the &6Mythic Ritual &7is running."])],
    "ARMOR_OF_THE_RESISTANCE": [("FULL", "Power of the Resistance", ["&7Deal more damage to, and take less", "&7damage from, &cthe Usurper&7."])],
    "GENERALS_ARMOR_OF_THE_RESISTANCE": [("FULL", "Power of the Resistance", ["&7Deal more damage to, and take less", "&7damage from, &cthe Usurper&7."])],
    # ---------------------------------------------------------------- crimson-side (non-kuudra)
    "BLAZE": [("FULL", "Blazing Aura", ["&7Burns every enemy within &a5 &7blocks", "&7for &c3% &7of its max health each second.", "&8Max 500 damage/s, +100 per 5,000 Blaze", "&8Rods in your collection."])],
    "FROZEN_BLAZE": [("FULL", "Frozen Blazing Aura", ["&7Freezes every enemy within &a5 &7blocks", "&7for &c300 &7damage plus &c3% &7of its max", "&7health each second, and &bslows &7it.", "&8Max 500 damage/s, +100 per 5,000 Blaze", "&8Rods in your collection."])],
    "EMBER": [("FULL", "Nether Lord", ["&7You take no damage from &6fire &7or", "&6lava&7. Walking over lava turns it to",
                                        "&7obsidian under your feet for a few", "&7seconds.", "&8Not on islands, or with a fishing rod in hand."])],
    "BERSERKER": [("TIERED", "Berserk", ["&7With the full set your max " + S["hp"], "&7and " + S["def"] + " &7are &chalved&7."])],
    "RAMPART": [("FULL", "Bulwark of the Isle", ["&7On the &cCrimson Isle&7: &c+50 ❤ Health&7,", "&c+20 ❁ Strength &7and &9+15 ☠ Crit Damage&7."])],
    "SHIMMERING_LIGHT": [("TIERED", "Shimmer", ["&7Experience orbs from monsters and", "&7ores are worth &a+50% &7more per piece", "&8(+200% with the full set)&7."])],
    "REKINDLED_EMBER": [("TIERED", "Rekindle", ["&7Deal &c+50% &7damage per piece to", "&7enemies that are &6on fire&7, plus &c+1.2%", "&7for every second &oyou&7 have been", "&7burning &8(max +50%)&7.", "&7With &a2+ &7pieces, burning monsters set", "&7you alight with their blows."])],
    "THERMODYNAMIC": [("FULL", "Newton's Demise", ["&7Raises your attack speed limit by &e+50&7."])],
    # ---------------------------------------------------------------- mining
    "MINER_OUTFIT": [("FULL", "Beginner's Boost", ["&7Grants &6+40 ⸕ Mining Speed&7."])],
    "LAPIS_ARMOR": [("PIECE", "Magnetic", ["&7Experience orbs earned while mining", "&7are worth &a+50% &7more."])],
    "GLACITE": [("FULL", "Expert Miner", ["&7Grants &6+2 ⸕ Mining Speed &7for every", "&7Mining Skill level you have."])],
    "GOBLIN": [("FULL", "Smart Miner", ["&7Turns your " + S["int"] + " &7into mining", "&7power: &6+1 ⸕ Mining Speed &7for every", "&b15 ✎ &7removed."])],
    "HEAT": [("ABILITY", "Pressurized Coating", ["&7Triples this piece's " + S["def"] + " &7and", S["str"] + " &7on mining islands."]),
             ("FULL", "Heat Shield", ["&7You are immune to &cHeat&7."])],
    "TANK_MINER": [("ABILITY", "Depth Coating", ["&7Doubles this piece's " + S["def"] + " &7on", "&7mining islands."])],
    "ARMOR_OF_YOG": [("FULL", "Absorb", ["&7Every &a5 &7lava beasts you slay while", "&7wearing the set grant &6+1 ⸕ Mining Speed&7.", "&8Max +500."])],
    "MINERAL": [("TIERED", "Mineralworks", ["&7Grants &e+5 ▚ Mining Spread &7on ores and", "&7blocks for each piece worn."])],
    "GLOSSY_MINERAL": [("TIERED", "Glossy Mineralworks", ["&7Grants &e+7.5 ▚ Mining Spread &7on ores", "&7and blocks for each piece worn."])],
    "SORROW": [("TIERED", "Mist Aura", ["&7With &a2+ &7pieces, damage from ghosts", "&7is reduced by &a40%&7."])],
    "MITHRIL_COAT": [("ABILITY", "Mithril's Protection", ["&7No single hit can take more than &c40%", "&7of your max health. Briefly regenerate", "&7when this saves you."])],
    # ---------------------------------------------------------------- farming
    "MELON": [("TIERED", "Cropier Crops", ["&7Harvesting &eWheat&7, &eCarrots &7and &ePotatoes", "&7has a &a0.03% &7chance to drop a &aCropling&7.", "&7Grants up to &6+30 ☘ Farming Fortune&7."]),
              ("ABILITY", "Farmer's Grace", ["&7You never trample crops."])],
    "CROPIE": [("TIERED", "Squashbuckle", ["&7Harvesting &ePumpkins&7, &eMelons &7and &eCocoa", "&7has a &a0.01% &7chance to drop a &9Squash&7.", "&7Grants up to &6+45 ☘ Farming Fortune&7."]),
               ("ABILITY", "Farmer's Grace", ["&7You never trample crops."])],
    "SQUASH": [("TIERED", "Mento Fermenta", ["&7Harvesting &eCane&7, &eCactus&7, &eMushrooms &7and", "&eNether Wart &7has a &a0.005% &7chance to", "&7drop a &5Fermenta&7.", "&7Grants up to &6+60 ☘ Farming Fortune&7."]),
               ("ABILITY", "Farmer's Grace", ["&7You never trample crops."])],
    "FERMENTO": [("TIERED", "Feast", ["&7Harvesting flowers has a &a0.002% &7chance", "&7to drop a &6Helianthus&7, and every rare", "&7crop roll of the lower sets applies too.", "&7Grants up to &6+75 ☘ Farming Fortune&7."]),
                 ("ABILITY", "Farmer's Grace", ["&7You never trample crops."])],
    "HELIANTHUS": [("TIERED", "Feast", ["&7Every rare crop roll of the whole", "&7farming ladder applies at once.", "&7Grants up to &6+75 ☘ Farming Fortune&7."]),
                   ("ABILITY", "Farmer's Grace", ["&7You never trample crops."])],
    "RANCHERS": [("ABILITY", "Farmer's Grace", ["&7You never trample crops."])],
    # ---------------------------------------------------------------- fishing
    "ANGLER": [("TIERED", "Depth Champion", ["&7Take &a3% &7less damage from sea creatures", "&7for each piece worn."]),
               ("TIERED", "Deepness Within", ["&7Gain &c+1.5 ❤ Health &7per Fishing level", "&7for each piece worn."])],
    "BACKWATER": [("TIERED", "Swamp Soldier", ["&7Deal &c+5% &7damage to sea creatures for", "&7each piece worn."])],
    "SALMON": [("ABILITY", "Water Burst &e&lSNEAK", ["&7With the full set, sneak in water to", "&7surge forward. &8Costs 20 mana."])],
    "SALMON_NEW": [("ABILITY", "Water Burst &e&lSNEAK", ["&7With the full set, sneak in water to", "&7surge forward. &8Costs 20 mana."])],
    "SPONGE": [("FULL", "Absorb", ["&7Your " + S["def"] + " &7is &adoubled &7while you", "&7are in water."])],
    "SHARK_SCALE": [("FULL", "Absorb", ["&7Your " + S["def"] + " &7is &adoubled &7while you", "&7are in water."]),
                    ("TIERED", "Festival Fisher", ["&7Gain &3+1 α Sea Creature Chance &7per", "&7piece during the Fishing Festival, so", "&7more &bSharks &7bite."])],
    "DIVER": [("FULL", "One with the Fish", ["&7You can breathe underwater for as long", "&7as you like."]),
              ("TIERED", "Deep Sea Diver", ["&7Gain &6+0.5 ⛃ Treasure Chance &7per piece", "&7while underwater."])],
    "ABYSSAL": [("FULL", "One with the Fish", ["&7You can breathe underwater for as long", "&7as you like."]),
                ("TIERED", "Deep Sea Diver", ["&7Gain &6+1 ⛃ Treasure Chance &7per piece", "&7while underwater."])],
    "NUTCRACKER": [("PIECE", "Winter Cheer", ["&7Every stat on this piece is &adoubled &7in", "&bthe Winter Workshop&7."]),
                   ("FULL", "Battalion", ["&7You and every player within &a30 &7blocks", "&7gain &b+1 ✯ Magic Find &7and &a+50 ❈ Defense", "&7per nearby wearer &8(stacks up to 5)&7."])],
    "SNOW_SUIT": [("PIECE", "Winter Cheer", ["&7Every stat on this piece is &adoubled &7in", "&bthe Winter Workshop&7."]),
                  ("FULL", "Cold Thumb", ["&7Your snow cannon never runs out of", "&7snowballs."])],
    "THUNDER": [("TIERED", "Static Charge", ["&7Store &e1 &7static charge every &a30s &8(max 2)&7.", "&7Your next hit discharges them for &c+15%", "&7damage per charge.", "&7Magmatic creatures deal &a10% &7less damage", "&7to you and take &c1.2x &7damage."])],
    "MAGMA_LORD": [("TIERED", "Fireproof", ["&7With &a2+ &7pieces you are immune to the", "&7lava of the &cCrimson Isle&7.",
                                            "&8The Magma Lord Necklace counts as a piece."]),
                   ("TIERED", "Lord's Blessing", ["&7With all &a5 &7pieces (the set and the", "&7Magma Lord Necklace): &3+1 α Sea Creature",
                                                  "&3Chance&7, &b+20 ✯ Magic Find &7vs magmatic", "&7creatures, which deal &a15% &7less damage", "&7to you and take &c1.3x &7damage."])],
    "BRONZE_HUNTER": [("TIERED", "Peace Treaty", ["&7With &a2+ &7pieces sea creatures cannot", "&7hurt you and you cannot hurt them, and", "&7you no longer fish them up."]),
                      ("FULL", "Star Fisher", ["&7Gain &b+0.2 ☂ Fishing Speed &7for every", "&6✪ &7upgrade star on your armor."]),
                      ("TIERED", "Angler's Favor", ["&7Gain &6+0.5 ♔ Trophy Fish Chance &7per", "&7piece worn."])],
    "SILVER_HUNTER": [("TIERED", "Peace Treaty", ["&7With &a2+ &7pieces sea creatures cannot", "&7hurt you and you cannot hurt them, and", "&7you no longer fish them up."]),
                      ("FULL", "Star Fisher", ["&7Gain &b+0.4 ☂ Fishing Speed &7for every", "&6✪ &7upgrade star on your armor."]),
                      ("TIERED", "Angler's Favor", ["&7Gain &6+1 ♔ Trophy Fish Chance &7per", "&7piece worn."])],
    "GOLD_HUNTER": [("TIERED", "Peace Treaty", ["&7With &a2+ &7pieces sea creatures cannot", "&7hurt you and you cannot hurt them, and", "&7you no longer fish them up."]),
                      ("FULL", "Star Fisher", ["&7Gain &b+0.7 ☂ Fishing Speed &7for every", "&6✪ &7upgrade star on your armor."]),
                    ("TIERED", "Angler's Favor", ["&7Gain &6+1.5 ♔ Trophy Fish Chance &7per", "&7piece worn."])],
    "DIAMOND_HUNTER": [("TIERED", "Peace Treaty", ["&7With &a2+ &7pieces sea creatures cannot", "&7hurt you and you cannot hurt them, and", "&7you no longer fish them up."]),
                      ("FULL", "Star Fisher", ["&7Gain &b+1 ☂ Fishing Speed &7for every", "&6✪ &7upgrade star on your armor."]),
                       ("TIERED", "Angler's Favor", ["&7Gain &6+2 ♔ Trophy Fish Chance &7per", "&7piece worn."])],
    # ---------------------------------------------------------------- foraging
    "SNORKELING": [("TIERED", "Long Tuba", ["&7Hold your breath &a15s &7longer", "&7underwater for each piece worn."])],
    # ---------------------------------------------------------------- dungeon-drop starter sets (wave 1 copy)
    "ROTTEN": [("FULL", "Sieve Body", ["&7Arrows knock you back &a20% &7less."])],
    "HEAVY": [("FULL", "Vindicate", ["&7Gain &f+1 ✦ Speed &7for every &a50 ❈", "&aDefense &7you have."])],
    "SUPER_HEAVY": [("FULL", "Vindicate", ["&7Gain &f+1 ✦ Speed &7for every &a50 ❈", "&aDefense &7you have."])],
    "SKELETON_GRUNT": [("PIECE", "Marksman", ["&7Your arrows deal &c+5% &7damage."])],
    "PARTY": [("TIERED", "Carnival Craze", ["&7Earn &a+4% &7more carnival tokens."])],
}

# Abilities that belong to one ITEM (not to a set).
ITEM_LORE = {
    "SKELETON_HELMET": [("ABILITY", "Bone Shield", ["&7A ring of bones absorbs the next hit", "&7you take. One bone regrows every &a30s", "&8(max 3)&7."])],
    "GUARDIAN_CHESTPLATE": [("ABILITY", "Block Damage", ["&7At full health, the first hit you take", "&7is ignored. &8Cooldown: 60s"])],
    "CREEPER_LEGGINGS": [("ABILITY", "Detonate", ["&7Dropping below &c20% &7health blasts every", "&7nearby monster away from you.", "&8Cooldown: 60s"])],
    "SPIDER_BOOTS": [("ABILITY", "Double Jump &e&lSNEAK", ["&7Sneak in mid-air to leap again.", "&8Costs 50 mana."])],
    "SPRING_BOOTS": [("ABILITY", "To the Moon! &e&lSNEAK", ["&7Hold sneak to charge a jump; the longer", "&7you hold, the higher you launch."])],
    "MITHRIL_COAT": [("ABILITY", "Mithril's Protection", ["&7No single hit can take more than &c40%", "&7of your max health. Briefly regenerate", "&7when this saves you."])],
    "PUFFERFISH_HAT": [("ABILITY", "Spiky", ["&7Your attacks sometimes prick nearby", "&7enemies for &c10 &7damage plus &c20% &7of", "&7your " + S["str"] + "&7."])],
    "PUFFERFISH_HAT_CELEBRATION": [("ABILITY", "Spiky", ["&7Your attacks sometimes prick nearby", "&7enemies for &c10 &7damage plus &c20% &7of", "&7your " + S["str"] + "&7."])],
    "VAMPIRE_MASK": [("ABILITY", "Rejuvenate", ["&7Drains &c5 &7health per second from every", "&7monster within &a8 &7blocks into you."])],
    "VAMPIRE_WITCH_MASK": [("ABILITY", "Bat Swarm", ["&7Two bats follow you and dive at nearby", "&7enemies, exploding for &c3,000 &7damage.", "&7Your bats do not come out on your island."]),
                           ("ABILITY", "Rejuvenate", ["&7Drains &c5 &7health per second from every", "&7monster within &a8 &7blocks into you."])],
    "WITCH_MASK": [("ABILITY", "Bat Swarm", ["&7Two bats follow you and dive at nearby", "&7enemies, exploding for &c4,000 &7damage.", "&7Your bats do not come out on your island."])],
    "FISH_HAT": [("ABILITY", "Splash &e&lSNEAK", ["&7Sneak to splash water around you."])],
    "MAGMA_FISH_HAT": [("ABILITY", "Splash &e&lSNEAK", ["&7Sneak to splash magma around you."])],
    "SALMON_HAT": [("ABILITY", "Splash &e&lSNEAK", ["&7Sneak to splash water around you."])],
    "SALMON_HAT_CELEBRATION": [("ABILITY", "Splash &e&lSNEAK", ["&7Sneak to splash water around you."])],
    "SUNFLOWER_HEAD": [("ABILITY", "Sparkle", ["&7Spreads a little happiness around you."])],
    "CROWN_OF_GREED": [("ABILITY", "Indulgence", ["&7Mythic creatures drop twice the coins and", "&7you gain &b+10 ✯ Magic Find &7against them,", "&7but they hit you &c1.25x &7harder."])],
    "CROWN_OF_AVARICE": [("ABILITY", "Overindulgence", ["&7Mythic creatures hit you &c1.5x &7harder,", "&7but drop five times the coins."])],
    "CROWN_OF_AVARICE_CELEBRATION": [("ABILITY", "Overindulgence", ["&7Mythic creatures hit you &c1.5x &7harder,", "&7but drop twice the coins."])],
    "POTATO_CROWN": [("ABILITY", "Potato King", ["&7Worn by the victor of a war fought", "&7entirely over potatoes."])],
    "MUSIC_PANTS": [("ABILITY", "Beat Saver", ["&7Sometimes you reel in a music disc", "&7while fishing."])],
    "TAURUS_HELMET": [("ABILITY", "Bullheaded", ["&7Forged from the horns of a lava beast."])],
    "SLUG_BOOTS": [("ABILITY", "Bouncy &e&lSNEAK", ["&7Sneak as you land from a great height", "&7to bounce straight back up instead."])],
}

# Short, original flavour for pieces that have no bonus (keeps the tooltip from being bare).
FLAVOUR = {
    "HARDENED_DIAMOND": "&8Diamonds pressed until they gave up.",
    "PERFECT": "&8Forged without a single flaw.",
    "LEAFLET": "&8Stitched together from fresh leaves.",
    "FARM_SUIT": "&8More fortune, more crops.",
    "FARM_ARMOR": "&8Smells faintly of hay.",
    "PUMPKIN": "&8Freshly carved, still a little sticky.",
    "CELESTE": "&8Woven with a faint starlight thread.",
    "SQUIRE": "&8Every knight starts somewhere.",
    "CANOPY": "&8Leaf-light armour for treetop work.",
    "FIG": "&8Grown, not forged.",
    "HELIX_ARMOR": "&8Twisted like the trees it came from.",
    "DIVAN": "&8The gem slots are where its power lives.",
    "FLAME_BREAKER": "&8Tempered in the heart of the mountain.",
    "FLAMEBREAKER": "&8Ash-grey plates that never cool.",
    "BIOHAZARD": "&8Pests hate the smell.",
    "SEA_WALKER": "&8Dripping, but sturdy.",
    "HYDRA1": "&8Scales of a many-headed tide beast.",
    "IRON": "", "DIAMOND": "", "GOLD": "", "LEATHER": "", "CHAINMAIL": "",
}
