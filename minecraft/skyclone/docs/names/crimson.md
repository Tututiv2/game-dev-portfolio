# crimson - renames

Ids never change (they are the reference game's, so every module means the same thing); only what players
see does. Display names live in `tools/gen/crimson/*.py` (items, mobs, NPCs, quests) and in the generator
(`plugin/src/main/java/com/skyclone/mod/crimson/gen/`, zone names). Every description and line of dialogue
is written fresh; numbers are the reference's.

## Places (zones on `crimson_isle`)

| id | reference name | our name |
|---|---|---|
| island `crimson_isle` | Crimson Isle | Crimson Isle (kept: plain colour + "isle", like "Spider's Den") |
| zone `crimson_drakehold` | Dragontail | Drakehold |
| zone `crimson_drakehold_hall` | (Chief Scorn's hall) | Warchief's Hall |
| zone `crimson_carmine` | Scarleton | Carmine Spire |
| zone `crimson_carmine_palace` | (Queen Nyx's throne room) | Throne Hall |
| zone `crimson_ashfang` | Ruins of Ashfang | Ruins of the Twin Pyres |
| zone `crimson_blade_yard` | (Bladesoul's spot at the Stronghold) | Wraith Yard |
| zone `crimson_dock` | Kuudra's Hollow / Kuudra Passage | Leviathan's Hollow |
| zone `crimson_lava_hut` | (lava fishing spot) | Cinder Shore |
| zone `crimson_belly` (wave 3) | Belly of the Beast | kept (descriptive words) |
| zones `crimson_stronghold`, `crimson_fields`, `crimson_volcano`, `magma_chamber`, `crimson_marsh`, `crimson_desert`, `crimson_tomb`, `crimson_dukedom`, `crimson_courtyard`, `crimson_dojo`, `crimson_skull` | Stronghold, Crimson Fields, Blazing Volcano, Magma Chamber, Mystic Marsh, Burning Desert, Smoldering Tomb, The Dukedom, Courtyard, Dojo, Forgotten Skull | kept (descriptive words) |
| warps `crimson_drakehold`, `crimson_carmine`, `crimson_tomb` | Dragontail / Scarleton / Smoldering Tomb travel scrolls | Drakehold / Carmine Spire / Smoldering Tomb |

## NPCs

| id | reference name | our name |
|---|---|---|
| `barbarian_leader` | Chief Scorn | Warchief Grathe |
| `mage_leader` | Queen Nyx | Archmage Seraphine |
| `dojo_master` | Master Tao | Sensei Kiyoshi |
| `crimson_armorsmith` | Vulcan (essence / Kuudra armor smith) | Forgemaster Ignatius |
| `leviathan_dock` | Elle | Dockhand Nell |
| `crimson_guide` | Rollim (isle guide role; also his daily +25 reputation for 7 days, audit K18) | Watchman Orrin |
| `mage_shop` | Hilda (mage faction shop) | Arcanist Vela |
| `barbarian_shop` | (barbarian faction shop) | Quartermaster Hrolf |
| `drakehold_banker` / `carmine_banker` | town bankers | Coffer-keeper Mordaunt / Treasurer Lucienne |
| `drakehold_bazaar` / `carmine_bazaar` | town bazaar agents | Trader Kolm / Broker Amaranth |
| `drakehold_lore` / `carmine_lore` | flavour townsfolk | Old Bjarka / Apprentice Lio |

(The peddlers, brewers, generator smiths, envoys, trophy keeper, devotee, lampwright, phone dealer and
frantic engineer standing on this island are the **shops** module's NPCs and renames.)

## Mobs and bosses

| id | reference name | our name |
|---|---|---|
| `crimson_bladesoul` | Bladesoul | Edgewraith |
| `crimson_mage_outlaw` | Mage Outlaw | Rogue Pyromancer |
| `crimson_barbarian_duke` | Barbarian Duke X | Duke Brakkus the Tenth |
| `crimson_ashfang` | Ashfang | Cindermaw |
| `crimson_magma_boss` | Magma Boss / Magma Cube Boss | Magma Colossus |
| `crimson_millennia_blaze` | Millennia-Aged Blaze | Aeon-Old Blaze |
| `crimson_bezal` | Bezal | Scorchling |
| `crimson_matcho` | Matcho | Tinderling |
| `crimson_cinderbat` | Cinderbat | Emberwing Bat |
| `crimson_kada_knight` | Kada Knight | Ashen Knight |
| `crimson_barbarian_brute` | Goliath Barbarian | Barbarian Brute |
| (wave 2 boss) | Kuudra | the Magma Leviathan |
| `crimson_blaze`, `crimson_mutated_blaze`, `crimson_smoldering_blaze`, `crimson_flare`, `crimson_magma_cube`, `crimson_ghast`, `crimson_magma_cube_rider`, `crimson_flaming_spider`, `crimson_wither_skeleton`, `crimson_wither_spectre`, `crimson_mushroom_bull`, `crimson_fire_mage`, `crimson_barbarian`, `crimson_vanquisher` | Blaze, Mutated Blaze, Smoldering Blaze, Flare, Pack Magma Cube, Dive Ghast, Magma Cube Rider, Flaming Spider, Wither Skeleton, Wither Spectre, Mushroom Bull, Fire Mage, Barbarian, Vanquisher | kept (generic creature words) |

## Items

| id | reference name | our name |
|---|---|---|
| `KUUDRA_TIER_KEY` | Kuudra Key | Leviathan Key |
| `KUUDRA_HOT_TIER_KEY` | Hot Kuudra Key | Hot Leviathan Key |
| `KUUDRA_BURNING_TIER_KEY` | Burning Kuudra Key | Burning Leviathan Key |
| `KUUDRA_FIERY_TIER_KEY` | Fiery Kuudra Key | Fiery Leviathan Key |
| `KUUDRA_INFERNAL_TIER_KEY` | Infernal Kuudra Key | Infernal Leviathan Key |
| set bonus of the `CRIMSON_*` line | Dominus | Dominion |
| `[HOT_/BURNING_/FIERY_/INFERNAL_]{CRIMSON,AURORA,TERROR,FERVOR,HOLLOW}_{HELMET,CHESTPLATE,LEGGINGS,BOOTS}` | same | kept: plain English words (crimson, aurora, terror, fervor, hollow; hot/burning/fiery/infernal) |
| `RUNIC_STAFF`, `HOLLOW_WAND`, `TORMENTOR` | Aurora Staff, Hollow Wand, Tormentor | kept (plain words) |
| other set bonuses | Arcane Energy, Hydra Strike, Fervor / Ground Pound / Intimidate, Spirit | kept (generic words) |

## Systems

| id | reference name | our name |
|---|---|---|
| faction ranks 0..4 | (reference rank titles) | Outsider, Ally, Trusted, Honored, Champion |
| Dojo trials | Test of Force / Stamina / Mastery / Discipline / Swiftness / Control / Tenacity | kept (plain words; the trials themselves are our own designs) |
| faction quest types | (reference daily quests) | Slay, Champion Hunt, Delivery, Lava Fishing, Lava Beasts, Dojo Trial |
| quest type `KUUDRA` (wave 3) | Kuudra quests | Leviathan Hunt |
| Heavy Pearl collection (wave 3) | Matriarch's Lair, Belly of the Beast, the Matriarch | zone `crimson_belly` "Belly of the Beast", "The Matriarch" (both kept: plain descriptive words). The lair, the pearl spots, the daily allowance and every line of text are our own; `HEAVY_PEARL` / `MATRIARCH_PARFUM` names belong to the materials module |

## Wave 5 (2026-10-06)

| id | reference name | our name |
|---|---|---|
| `X`, `Y`, `Z` | X, Y, Z ("One/Two/Three Dimensional") | kept (single letters; descriptions rewritten: "A shape that only knows a single dimension...") |
| `SULPHUR_ORE` blocks (sponge) on the isle | Sulphur Ore | kept (generic mineral; the item name is materials') |
| opal veins in the Smoldering Tomb (area `gather.tomb_opal`) | Opal Gemstone | kept (generic gem; `ROUGH_OPAL_GEM` is mining's) |
| mini-boss spoils | (reference mini-boss drops) | "<Faction> spoils", "rival champion" - our own words and tables |
| `/synthesizer` menu | Synthesizer ("Right-click to open!") | kept (the item's name is accessories'; the menu text is ours) |
| attribute names read from hunting | Matriarch Cubs, Crimson Serendipity, Essence of Crimson, Ghastly Distraction | the hunting module's names (docs/names/hunting.md), not ours |
