# mining — renames

Every name a player sees that was a proper noun of the reference game. Internal ids keep the reference id.
All item descriptions, perk texts, NPC lines and event texts are written in our own words (numbers kept);
the generator `tools/gen/mining/gen_mining.py` is the single source of the item names and perk names.

Consistency notes: **Glacite -> Rimestone** matches materials and the bestiary; **Corleonite -> Gangsterite**
matches the name already chosen elsewhere; **Divan -> Varnas** matches the armor module's "Helmet of Varnas".
Mithril, Titanium, Umber, Tungsten, Hard Stone, the gem names and "Heart of the Mountain" are generic and kept.

## Items

| id | reference name | our name |
|---|---|---|
| STONK_PICKAXE | Stonk | Lucky Pick |
| PICKONIMBUS | Pickonimbus 2000 | Stratopick 2000 |
| MITHRIL_DRILL_1 | Mithril Drill SX-R226 | Mithril Drill MK-I |
| MITHRIL_DRILL_2 | Mithril Drill SX-R326 | Mithril Drill MK-II |
| TITANIUM_DRILL_1 | Titanium Drill DR-X355 | Titanium Drill TX-1 |
| TITANIUM_DRILL_2 | Titanium Drill DR-X455 | Titanium Drill TX-2 |
| TITANIUM_DRILL_3 | Titanium Drill DR-X555 | Titanium Drill TX-3 |
| TITANIUM_DRILL_4 | Titanium Drill DR-X655 | Titanium Drill TX-4 |
| GEMSTONE_DRILL_1 | Ruby Drill TX-15 | Ruby Drill R-15 |
| GEMSTONE_DRILL_2 | Gemstone Drill LT-522 | Gemstone Drill G-52 |
| GEMSTONE_DRILL_3 | Topaz Drill KGR-12 | Topaz Drill T-12 |
| GEMSTONE_DRILL_4 | Jasper Drill X | Jasper Drill J-X |
| DIVAN_DRILL | Divan's Drill | Varnas Drill |
| DIVAN_POWDER_COATING | Divan's Powder Coating | Varnas Powder Coating |
| DIVAN_FRAGMENT | Divan Fragment | Varnas Fragment |
| DIVAN_ALLOY | Divan's Alloy | Varnas Alloy |
| VOLTA | Volta | Charge Cell |
| GLACITE | Glacite | Rimestone |
| GLACITE_JEWEL | Glacite Jewel | Rimestone Jewel |
| GLACITE_AMALGAMATION | Glacite Amalgamation | Rimestone Amalgam |
| GLACITE_CHISEL | Glacite-Plated Chisel | Rimestone-Plated Chisel |
| GLACITE_LANTERN | Glacite Lantern | Rimestone Lantern |
| TREASURITE | Treasurite | Hoardite |
| CORLEONITE | Corleonite | Gangsterite |
| YOGGIE | Yoggie | Cinder Morsel |
| FTX_3070 | FTX 3070 | Cogitator Card |
| ROBOTRON_REFLECTOR | Robotron Reflector | Automaton Reflector |
| SUPERLITE_MOTOR | Superlite Motor | Featherweight Motor |
| BOB_OMB | Bob-omb | Blast Bomb |
| HELIX | Helix Fossil | Spiral Fossil |
| SECRET_RAILROAD_PASS | Secret Railroad Pass | Rimestone Rail Pass |
| FORGE_TRAVEL_SCROLL | Travel Scroll to the Dwarven Forge | Travel Scroll to the Forge |
| CRYSTAL_HOLLOWS_TRAVEL_SCROLL | Travel Scroll to the Crystal Hollows | Travel Scroll to the Crystal Caverns |
| BASE_CAMP_TRAVEL_SCROLL | Travel Scroll to the Dwarven Base Camp | Travel Scroll to the Base Camp |
| SAPPHIRE_CRYSTAL | Saphhire Crystal (typo in the data) | Sapphire Crystal |

## Heart of the Mountain perks

| id | reference name | our name |
|---|---|---|
| titanium_insanium | Titanium Insanium | Titanium Fever |
| pickobulus | Pickobulus | Pickblast |
| luck_of_the_cave | Luck of the Cave | Cave Luck |
| sky_mall | Sky Mall | Deep Market |
| speedy_mineman | Speedy Mineman | Speedy Miner |
| fortunate_mineman | Fortunate Mineman | Fortunate Miner |
| seasoned_mineman | Seasoned Mineman | Seasoned Miner |
| mole | Mole | Burrower |

## NPCs, mobs, places, events

| id | reference name | our name |
|---|---|---|
| mining_king (rotating) | King Yolkar / Brammor / Emkam / Kevin / Redros / Thormyr | King Borrik / Hallgrim / Dunmar / Ostwin / Gravik / Keldor |
| mining_commissions | Emissary / commission NPC | Emissary Torvald |
| mining_forge | Forge NPC | Forgemaster Kettrick |
| mining_hotm | (none) | Spirit of the Mountain |
| mining_hollows_merchant | (Odawa-style merchant) | Hollows Merchant Ondine |
| (menu) Pickaxe Smith | Bubu | Pickaxe Smith |
| (menu) Fetcher | Fetchur | Fetcher |
| (menu) Riddlewright | Puzzler | Riddlewright |
| mining_scatha | Scatha | Tunnel Wyrm |
| mining_raid_cutthroat (Goblin Raid) | Goblin Murderlover | Goblin Cutthroat (matches hollows' `ch_goblin_cutthroat`) |
| mining_raid_weakling(_bow) / _battler / _creepertamer / _golem | Weakling Goblin / Goblin Battler / Creepertamer / Goblin Golem | kept (descriptive, as in mines' list) |
| mining_worm | Worm | Cave Worm |
| (place, in text) | Crystal Hollows | Crystal Caverns |
| (place, in text) | Glacite Tunnels | Frozen Tunnels |
| mineshaft (island) | Glacite Mineshaft | Rimestone Mineshaft |
| (powder) | Glacite Powder | Rimestone Powder |
| (event) | Gone with the Wind | Tailwind |
| (event) | Mithril Gourmand | Mithril Feast |
| (event) | Mining Fiesta | Mining Jubilee |
| (commission) | Team Treasurite / Boss Corleone | Treasure Gang / Gang Boss |
| (commission) | Yog | Cinderling |
| (commission) thyst_slayer | Thyst Slayer | Amethyst Leech Slayer (matches hollows' mob `ch_thyst`) |
| mining_fetcher (NPC body, wave 5) | Fetchur | Fetcher |
| mining_riddlewright (NPC body, wave 5) | Puzzler | Riddlewright |
| mining_drill_mechanic (NPC body, wave 5) | Drill Mechanic | Drill Mechanic (generic job title, kept) |
| (pickaxe ability text) | Reduced To Atoms / Kinetic (Gemstone Gauntlet) | described in our own words in the lore, no ability names shown |
| (commission) | Glacite Walker | Rimestone Walker |
| FOSSIL_THE_FISH (item, wave 5) | Fossil the Fish | Stonefin the Fish (a named character; excavator curio) |
| DYE_FOSSIL (item, wave 5) | Fossil Dye | Fossil Dye (generic, kept) |
| mining_fossil_muncher (NPC body, wave 5; spawns once hollows adds anchor `npc.fossil_muncher`) | Fossil Muncher | Fossil Muncher (descriptive, kept) |
