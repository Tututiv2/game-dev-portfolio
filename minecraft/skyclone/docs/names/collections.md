# collections — renames

Collections show the **item registry's** display name whenever the owning module registered the item
(so the mining module's name for `MITHRIL_ORE` wins over ours). The names below are only our
fallbacks, used when no module registered the item, plus the boss collections and reward labels this
module owns outright. Generic words (Wheat, Cocoa Beans, Gemstone, Umber, Tungsten, Sulphur, Fig Log,
Tender Wood, Moonflower, Wild Rose, Magmafish, Lotus, Agaricus Cap, Caducous Stem, Wilted Berberis,
Half-Eaten Carrot...) are kept. Source of truth: `tools/gen/collections/gen_collections.py`
(`FALLBACK_NAMES`, `BOSSES`, `KUUDRA`, `PET_NAMES`, `BOOK_NAMES`, `MINION_NAMES`).

## Collections (fallback names)

| id | reference name | our name |
|---|---|---|
| SEA_LUMIES | Sea Lumies | Sea Glimmers |
| RUBY_VEILSHROOM | Ruby Veilshroom | Ruby Hoodcap |
| VINESAP | Vinesap | Vine Resin |
| LUSHLILAC | Lushlilac | Velvet Lilac |
| HELIX_LOG | Helix Log | Spiral Log |
| GLACITE | Glacite | Rimestone |
| MITHRIL_ORE | Mithril | Starsteel |
| HEMOVIBE | Hemovibe | Pulseblood |
| TIMITE | Timite | Chronite |
| METAL_HEART | Living Metal Heart | Clockwork Heart |

## Boss collections (owned here)

| id | reference name | our name |
|---|---|---|
| BOSS_BONZO | Bonzo (Floor 1) | Grinko |
| BOSS_SCARF | Scarf (Floor 2) | Veilwright |
| BOSS_THE_PROFESSOR | The Professor (Floor 3) | The Lecturer |
| BOSS_THORN | Thorn (Floor 4) | Bramblemaw |
| BOSS_LIVID | Livid (Floor 5) | Lurid |
| BOSS_SADAN | Sadan (Floor 6) | Gorrak |
| BOSS_NECRON | Necron (Floor 7) | Morvane |
| BOSS_KUUDRA | Kuudra | Magma Leviathan |

## Boss reward labels (fallbacks; the dungeons/kuudra/reforges registry names win when registered)

| id | reference name | our name |
|---|---|---|
| RED_NOSE | Red Nose | Clown Nose |
| BONZO_MASK | Bonzo's Mask | Grinko's Mask |
| GOLD_BONZO_HEAD | Golden Bonzo Head | Golden Grinko Head |
| BONZO_STAFF | Bonzo's Staff | Grinko's Staff |
| DIAMOND_BONZO_HEAD | Diamond Bonzo Head | Diamond Grinko Head |
| RECOMBOBULATOR_3000 | Recombobulator 3000 | Rarity Amplifier |
| SCARF_THESIS | Scarf's Thesis | Veilwright's Thesis |
| GOLD_SCARF_HEAD | Golden Scarf Head | Golden Veilwright Head |
| DIAMOND_SCARF_HEAD | Diamond Scarf Head | Diamond Veilwright Head |
| GOLD_PROFESSOR_HEAD | Golden Professor Head | Golden Lecturer Head |
| DIAMOND_PROFESSOR_HEAD | Diamond Professor Head | Diamond Lecturer Head |
| GOLD_THORN_HEAD | Golden Thorn Head | Golden Bramblemaw Head |
| DIAMOND_THORN_HEAD | Diamond Thorn Head | Diamond Bramblemaw Head |
| LIVID_DAGGER | Livid Dagger | Lurid Dagger |
| GOLD_LIVID_HEAD | Golden Livid Head | Golden Lurid Head |
| DIAMOND_LIVID_HEAD | Diamond Livid Head | Diamond Lurid Head |
| GOLD_SADAN_HEAD | Golden Sadan Head | Golden Gorrak Head |
| DIAMOND_SADAN_HEAD | Diamond Sadan Head | Diamond Gorrak Head |
| GOLD_NECRON_HEAD | Golden Necron Head | Golden Morvane Head |
| DIAMOND_NECRON_HEAD | Diamond Necron Head | Diamond Morvane Head |
| (Kuudra pet) | Kuudra Pet | Magma Leviathan Pet |
| KUUDRA_TEETH | Kuudra Teeth | Leviathan Teeth |
| UNCOMMON_KUUDRA_CHUNK | Kuudra Chunk | Leviathan Chunk |
| RARE_KUUDRA_CHUNK | Meaty Kuudra Chunk | Meaty Leviathan Chunk |
| EPIC_KUUDRA_CHUNK | Hardened Kuudra Chunk | Hardened Leviathan Chunk |
| LEGENDARY_KUUDRA_CHUNK | Enriched Kuudra Chunk | Enriched Leviathan Chunk |

## Reward labels (fallbacks when the owning module has not registered the pet / enchant / minion)

| id | reference name | our name |
|---|---|---|
| MITHRIL_GOLEM (pet) | Mithril Golem | Starsteel Golem |
| GLACITE_GOLEM (pet) | Glacite Golem | Rimestone Golem |
| PRECURSOR_DRONE (pet) | Precursor Drone | Relic Drone |
| transylvanian (enchant) | Transylvanian | Bloodsong |
| habanero_tactics (enchant) | Habanero Tactics | Pepper Tactics |
| tabasco (enchant) | Tabasco | Red Sauce |
| thunderlord (enchant) | Thunderlord | Stormbringer |
| MITHRIL_GENERATOR (minion) | Mithril Minion | Starsteel Minion |
| (text) | Access to /bank (Requires Cookie Buff) | Remote /bank access (while boosted) |
