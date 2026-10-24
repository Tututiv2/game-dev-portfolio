# farming — renames

Reference-game proper nouns the farming module shows to players, and what we call them. Internal ids keep the
reference id. Generic words (Melon Dicer, Cactus Knife, Fungi Cutter, Rookie Hoe, Hoe of Great Tilling, The Barn,
Mushroom Desert, Oasis, Mushroom Gorge, Trackable/Elusive...) stay. Where another module already renamed the same
thing, we use THEIR name so the game reads as one (Harvest Fair Ticket from materials, Cropling/Fermenta from armor,
Harvesta from accessories, Surveyor Pruitt / Fur-trader Alder / Rancher Jed from shops).

## Items

| id | reference name | our name |
|---|---|---|
| THEORETICAL_HOE_WHEAT_1/2/3 | Euclid's Wheat Hoe / Sickle Mk. I-III | Fibonacci's Wheat Sickle Mk. I-III |
| THEORETICAL_HOE_CARROT_1/2/3 | Gauss Carrot Hoe / Shovel Mk. I-III | Kepler's Carrot Shovel Mk. I-III |
| THEORETICAL_HOE_POTATO_1/2/3 | Pythagorean Potato Hoe / Shovel Mk. I-III | Archimedes' Potato Shovel Mk. I-III |
| THEORETICAL_HOE_CANE_1/2/3 | Turing Sugar Cane Hoe / Cutter Mk. I-III | Lovelace's Sugar Cane Cutter Mk. I-III |
| THEORETICAL_HOE_WARTS_1/2/3 | Newton Nether Warts Hoe / Cutter Mk. I-III | Leibniz's Nether Wart Cutter Mk. I-III |
| THEORETICAL_HOE_SUNFLOWER_1/2/3 | Eclipse Sickle Mk. I-III | Solstice Sickle Mk. I-III |
| THEORETICAL_HOE_WILD_ROSE_1/2/3 | Wild Rose Cutter Mk. I-III | Briarwood Rose Cutter Mk. I-III |
| COCO_CHOPPER(_2/_3) | Cocoa Chopper Mk. I-III | Cocoa Chopper Mk. I-III (generic, kept) |
| JACOBS_TICKET | Jacob's Ticket | Harvest Fair Ticket |
| CROPIE | Cropie | Cropling |
| FERMENTO | Fermento | Fermenta |
| SQUASH | Squash | Squash (generic, kept) |
| HELIANTHUS | Helianthus | Helianthus (botanical genus, kept) |
| FARMING_FOR_DUMMIES | Farming for Dummies | Farmer's Almanac |
| TALBOTS_THEODOLITE | Talbot's Theodolite | Surveyor's Theodolite |
| THEORETICAL_HOE | Mathematical Hoe Blueprint | Mathematical Hoe Blueprint (generic, kept) |

## NPCs

| id | reference name | our name |
|---|---|---|
| farming_host / farming_host_barn | Jacob | Harvest Warden Ambrose (Harvest Fair host; the same name the garden module gives Jacob's visitor) |
| farming_harvesta | Anita | Harvesta (Medal Exchange) |
| farming_trapper | Trevor (the Trapper) | Tavish |
| farming_surveyor | Talbot | Surveyor Pruitt |
| farming_fur_trader | Tony | Fur-trader Alder |
| (anchor npc.rancher, shops module) | Jake | Rancher Jed |
| farming_farmhand | Farmhand | Farmhand Brody |
| farming_guide | Farmer Jon | Old Farmer Tewksbury |
| farming_shepherd | Shepherd | Shepherd Maela |
| farming_treasure_hunter | Treasure Hunter | Digger Nim |

## Systems, places, text

| id | reference name | our name |
|---|---|---|
| contest (calendar event `farming_contest`) | Jacob's Farming Contest | Harvest Fair |
| shop menu | Anita's shop / Anita's upgrades | Harvesta's Medal Exchange |
| perk text | Farmhand (skill perk) | (skills module: Harvester) |
| zone desert.den | Trapper's Den | Trapper's Den (generic, kept) |
| zone desert.camp | Treasure Hunter Camp | Treasure Hunter Camp (generic, kept) |
| zone desert.grotto | Glowing Mushroom Cave | Glowing Grotto |
| zone desert.hollow | (none; nether wart lives here in our desert) | Soul Hollow |
| zone desert.keep | Shepherd's Keep | Shepherd's Keep (generic, kept) |
| ghost competitors | (none in the reference) | invented farmer names (Ada Barley, Odo Furrow...) |

## Wave 5 (2026-10-06, second pass)

| id | reference name | our name |
|---|---|---|
| mob `shiny_pig` (bestiary family `shiny_pig`) | Shiny Pig | Shiny Pig (generic, kept - also progression's bestiary name) |
| SHINY_ORB | Shiny Orb | Shiny Orb (generic, kept) |
| pet perk `pig_hamfisted` (shown in Shiny Pig lines) | Hamfisted | Ham Fist (pets module's name) |
| pet perk `pig_shining_stampede` (shown in /fortune potato) | Shining Stampede | Shine Rush (pets module's name) |
| pet perk `orchid_mantis_intelligent_specimen` (Tool Bench) | Intelligent Specimen | Bright Mind (pets module's name) |
| pet perk `rose_dragon_dragon_s_gluttony` (Overbloom page) | Dragon's Gluttony | Feast (pets module's name) |
| REFINED_DARK_CACAO_TRUFFLE (chat on eating) | Refined Dark Cacao Truffle | kept (materials' name) |
