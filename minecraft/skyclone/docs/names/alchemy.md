# alchemy - renames

Item ids keep the reference ids (`POTION_SPEED;5`, `GOD_POTION_2`); only display names change.
Kept as-is (generic words): every plain effect name (Speed, Strength, Healing, Damage, Jump Boost, Resistance,
Water Breathing, Regeneration, Mana, Critical, Archery, Haste, Absorption, Adrenaline, Rabbit, Agility,
Night Vision, Invisibility, Fire Resistance, Spelunker, Experience, Stun, Dodge, True Resistance, Spirit,
Pet Luck, Knockback, Burning, Venomous, Wounded, Cold Resistance, Magic Find, Stamina, Slowness, Weakness,
Poison, Blindness, "<Skill> XP Boost"), Water Bottle, Awkward Potion, Dungeon Potion, God Potion
(descriptive, not a proper noun), Brewing Stand, Potion Bag, Alchemist (the job title).

Every potion description is our own wording; the numbers are the reference's (NEU item files).

| kind | id | reference name | our name |
|---|---|---|---|
| potion | `POTION_MUSHED_GLOWY_TONIC;1` | Mushed Glowy Tonic | Glowmash Tonic I |
| potion | `POTION_STINKY_CHEESE;1` | Douce Pluie de Stinky Cheese | Pungent Cheese Mist I |
| potion | `POTION_HARVEST_HARBINGER;5` | Harvest Harbinger Potion | Harvest Herald V Potion |
| potion | `POTION_WISP_ICE;1` | Wisp's Ice-Flavored Water | Chilled Spirit Water I |
| npc | `alchemy_alchemist` | Alchemist | Alchemist Wendel |
| effect id | `mushed_glowy_tonic` | Mushed Glowy Tonic | Glowmash Tonic |
| effect id | `stinky_cheese` | Douce Pluie de Stinky Cheese | Pungent Cheese Mist |
| effect id | `harvest_harbinger` | Harvest Harbinger | Harvest Herald |
| effect id | `wisp_ice` | Wisp's Ice-Flavored Water | Chilled Spirit Water |
| item | `SCARLETON_PREMIUM` | Scarleton Premium (a Crimson Isle town) | Carmine Premium (crimson renamed the town Carmine Spire) |
| item | `KNOCKOFF_COLA` | KnockOff(TM) Cola | Knockoff Cola |
| item | `SCORNCLAW_BREW` | Scornclaw Brew | Bitterclaw Brew |

Kept as-is (generic drink names): Cheap Coffee, Decent Coffee, Black Coffee, Tepid Green Tea, Pulpous Orange Juice,
Bitter Iced Tea, Red Thornleaf Tea, Hot Chocolate. Their lore is our own wording; the numbers are NEU's.

## Wave 5 (2026-10-06) - special potions

| kind | id | reference name | our name |
|---|---|---|---|
| item | `GREAT_SPOOK_POTION` | Great Spook Potion | Dread Draught (effect `great_spook` = "Dread", the same word enchanting uses for Great Spook) |
| effect id | `great_spook` | Great Spook (+3 Fear) | Dread |
| item | `BOTTLE_OF_JYRRE` | Lost Bottle of Jyrre | Lost Bottle of Midnight |
| item | `NEW_BOTTLE_OF_JYRRE` | Bottle of Jyrre | Bottle of Midnight (was "Bottle of Gerald's Wit" until 2026-10-06: Gerald is the island helper and the Mayor, and Jyrre is not him - wave-5 playtest naming #9) |
| item | `REFINED_BOTTLE_OF_JYRRE` | Refined Bottle of Jyrre | Refined Bottle of Midnight (now the same name as materials' fallback catalogue, which settles the old conflict) |
| effect id | `jyrre` | Jyrre (Intelligence) | Midnight Wit (label "Midnight Wit +120") |
| item | `RECALL_POTION` | Recall Potion | Recall Potion (generic, kept) |
| ability | (RECALL_POTION) | Ancient Recall | Echo Recall |
| potion | `POTION_RIFT_GRAVITY;1` | Gravity I Splash Potion ("spawning Puffs") | Gravity I Splash Potion ("Pufflings", the rift module's name) |
| potion | `POTION_SECRETS;1` | Secret I Potion | Secret I Potion (generic, kept) |
