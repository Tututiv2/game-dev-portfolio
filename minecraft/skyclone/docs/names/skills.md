# skills — renames

Internal ids (SkillType names, item ids, enchant ids) are unchanged. Everything below is what a
player SEES. Skill names themselves (Farming, Mining, Combat...) are generic words and stay.

## Perks (one per skill, one perk level per skill level)

| id | reference name | our name |
|---|---|---|
| FARMING perk | Farmhand | Harvester |
| MINING perk | Spelunker | Prospector |
| COMBAT perk | Warrior | Veteran |
| FORAGING perk | Logger | Timberjack |
| FISHING perk | Treasure Hunter | Beachcomber |
| ENCHANTING perk | Conjurer | Arcanist |
| ALCHEMY perk | Brewer | Distiller |
| TAMING perk | Zoologist | Beastkeeper |
| HUNTING perk | Charming | Snarewright |

Perk sentences and all 12 skill descriptions are rewritten in our own words (numbers kept); they
live in `tools/gen/skills/gen_skills.py` (`OURS`).

## People and places named in skill rewards

| id | reference name | our name |
|---|---|---|
| taming reward NPC | Fann (training sessions) | the Pet Trainer |
| social reward NPC | Amelia (island decorations shop) | the Island Decorator |
| taming cap NPC | George | the pet collector (cap hint text) |
| farming cap shop | Anita / Jacob's Farming Contest | "gold medals earned in farming contests" (cap hint text) |
| foraging cap source | Agatha / Fig & Mangrove log milestones | "high log collection milestones" (cap hint text) |
| the_end zone | Zealot Bruiser Hideout | the Bruiser Hideout in The End |
| (dropped) | Galatea (Foraging 12 access) | not shown: the island is not in this game |
| (dropped) | Lotus Atoll (Fishing 5 access) | not shown: the island is not in this game |

## Fallback names (shown only when the owning module has not registered the thing)

| id | reference name | our name |
|---|---|---|
| enchant `ultimate_jerry` | Ultimate Jerry | Ultimate Jester |
| enchant `pesterminator` | Pesterminator | Pest Bane |
| any reforge stone not registered | (its reference name) | "N new Reforge Stones" (no name shown) |
| any power stone not registered | (its reference name) | "N new Power Stones" (no name shown) |
| any sea creature not registered | (its reference name) | "N new Sea Creatures" (no name shown) |

Every other unlock name (islands, items, enchants, sea creatures) is read at runtime from the
module that owns it, so that module's rename is what players see.

## Runes (wave 3, `data/skills/runes.json`, `tools/gen/skills/gen_runes.py`)

Generic rune names (Blood, Snow, Zap, Hearts, Music, Rainbow, End, Pestilence...) are kept. Invented
proper nouns are renamed. Tier II / III items are `<id>_2` / `<id>_3`. Rune descriptions are our own wording.

| id | reference name | our name |
|---|---|---|
| `SOULTWIST_RUNE` | Soultwist Rune | Soulwring Rune |
| `ENDERSNAKE_RUNE` | Endersnake Rune | Voidcoil Rune |
| `LAVATEARS_RUNE` | Lavatears Rune | Magmaweep Rune |
| `COUTURE_RUNE` | Couture Rune | Tailored Rune |
| `DARKNESS_WITHIN_RUNE` | Darkness Within Rune | Inner Dark Rune |
| (block) | Runic Pedestal | Runic Pedestal (generic, kept) |

## Wave 5 (2026-10-06): Hub NPCs, training sessions

| id | reference name | our name |
|---|---|---|
| NPC `skills_runic_pedestal` | Dusk (Runic Pedestal, Mining District) | Runescribe Gloaming |
| NPC `skills_pet_trainer` | Fann (pet training sessions) | Trainer Brindle (the Pet Trainer) |
| training session `free/light/moderate/expert/ultra/turbo` | Free / Light / Moderate / Expert / Ultra / Turbo! Training | same (generic words, kept) |
| Carpentry road lines | "<X> Furniture Recipe" | "Furniture: <crafting's registered name>" (crafting owns the pieces and their names) |

All NPC dialogue lines are our own wording.

