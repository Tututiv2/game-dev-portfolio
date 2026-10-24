# minions — renames

Generic words stay generic (Cobblestone Minion, Compactor, Budget Hopper, Catalyst, Tasty Cheese, Solar Panel,
Flycatcher, Diamond Spreading, Farm Crystal...). Internal ids keep the reference ids (`REVENANT_GENERATOR_5`,
`SUPER_COMPACTOR_3000`) and are never shown. Source of truth: `tools/gen/minions/gen_minions.py`
(`typ(...)` table, `OWN_NAMES`, `SKIN_RENAME`, `INFERNO_SPEC_NAME`). Every description is our own wording;
the numbers are the reference's.

## Minion types (all tiers follow: "<name> Minion I" ... "XII")

| id | reference name | our name |
|---|---|---|
| REVENANT_GENERATOR_1..12 | Revenant Minion | Ghoul Minion |
| TARANTULA_GENERATOR_1..12 | Tarantula Minion | Broodweaver Minion |
| VOIDLING_GENERATOR_1..11 | Voidling Minion | Void Wraith Minion |
| MITHRIL_GENERATOR_1..12 | Mithril Minion | Mithril Minion — at boot it takes the registered name of `MITHRIL_ORE` (so if the mining module calls it Starsteel, this becomes "Starsteel Minion") |
| INFERNO_GENERATOR_* ability | Rising Celsius | Rising Heat |

The other 57 types keep their generic names (Wheat, Cobblestone, Zombie, Oak, Fishing, Lily Pad, Vampire, Inferno...).

## Upgrades, fuels, items

| id | reference name | our name |
|---|---|---|
| SUPER_COMPACTOR_3000 | Super Compactor 3000 | Ultra Compactor 3000 |
| DWARVEN_COMPACTOR | Dwarven Super Compactor | Deepdelver Compactor |
| SLEEPY_HOLLOW | Sleepy Hollow | Drowsy Hollow |
| LESSER_SOULFLOW_ENGINE | Lesser Soulflow Engine | Lesser Soulstream Engine |
| SOULFLOW_ENGINE | Soulflow Engine | Soulstream Engine |
| INFERNO_FUEL_CRUDE_GABAGOOL | Inferno Minion Fuel (Crude Gabagool) | Inferno Fuel (Gourmet Meat) |
| INFERNO_FUEL_GLOWSTONE_DUST / _MAGMA_CREAM / _NETHER_STALK / _BLAZE_ROD | Inferno Minion Fuel | Inferno Fuel (Glowstone / Magma Cream / Nether Wart / Blaze Rod) |
| INFERNO_HEAVY_* (5) | Inferno Minion Fuel (Heavy) | Heavy Inferno Fuel (...) |
| INFERNO_HYPERGOLIC_* (5) | Inferno Minion Fuel (Hypergolic) | Hypergolic Inferno Fuel (...) |
| SUPER_JACOB_SYSTEM | Super Jacob Entertainment System | Super Fairground Entertainment System (wave 5) |
| JACOBS_PARTICIPATION_MEDAL | Jacob's Participation Medal | Harvest Fair Participation Medal (wave 5; farming calls the ticket "Harvest Fair Ticket") |
| GENERATOR_UPGRADE_STONE_REVENANT_12 | Revenant Minion XII Upgrade Stone | Ghoul Minion XII Upgrade Stone |
| GENERATOR_UPGRADE_STONE_TARANTULA_12 | Tarantula Minion XII Upgrade Stone | Broodweaver Minion XII Upgrade Stone |
| other GENERATOR_UPGRADE_STONE_*_12 | "<Type> Minion XII Upgrade Stone" | same, our type names |

## Minion skins

| id | reference name | our name |
|---|---|---|
| GRINCH_PERSONALITY | Grinch Minion Skin | Grump Minion Skin |
| FESTIVE_JERRY_PERSONALITY | Festive Jerry Minion Skin | Festive Gerald Minion Skin |
| SANTA_PERSONALITY | Santa Minion Skin | Winter Elf Minion Skin |
| CHOCO_RABBIT_PERSONALITY | Choco Rabbit Minion Skin | Cocoa Bunny Minion Skin |
| APOTHEJERRY_PERSONALITY | Apothejerry Minion Skin | Apothecary Minion Skin |

The other 46 skins are generic (Snowman, Penguin, Shark, Robot, Bee, Mummy, ...).

Free Will, Postcard, Minion Chair (crafting's furniture) and the Winter Crystal's "present" are generic words
and keep their names. The Harvest Fair lore never names Jacob or his Dull Plows game.

## Text that names no reference NPC

- Tier XII trades say "trade for it at a minion smith" (the shops module names those NPCs).
- Mayor bonuses show as "Mayor perk" (the mayors module names the mayor and the perk).
- "Mithril" (Mithril Infusion, Mithril Crystal) is kept as a generic fantasy-metal word, matching the mining contract.
