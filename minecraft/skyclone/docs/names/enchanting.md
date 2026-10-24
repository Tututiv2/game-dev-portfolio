# enchanting — renames

Internal ids keep the reference game's ids (never shown). Every description is rewritten in our own
words (numbers kept); see `plugin/src/main/resources/data/enchanting/enchants.json` (`desc` / `short`)
and the generator `tools/gen/enchanting/gen_enchanting.py` (`RENAMES`, `DESC`). Enchant names that are
ordinary words (Sharpness, Protection, Chimera, Soul Eater, Wisdom, Legion, Combo, Bank, Rend, Duplex,
Crop Fever, The One...) are kept.

## Enchantments

| id | reference name | our name |
|---|---|---|
| thunderlord | Thunderlord | Stormlord |
| aiming | Dragon Tracer | Dragon Seeker |
| ultimate_jerry | Ultimate Jerry | Ultimate Jest |
| ultimate_bobbin_time | Bobbin' Time | Bobber's Chorus |
| ultimate_habanero_tactics | Habanero Tactics | Pepper Tactics |
| tabasco | Tabasco (a brand name) | Scoville |
| transylvanian | Transylvanian | Sanguine |
| pesterminator | Pesterminator | Pestbane |
| petalfall | Petalfall | Petal Shower |
| bug_blender | Bug Blender | Pest Pulper |
| counter_strike | Counter-Strike (a video game's name) | Riposte |
| great_spook | Great Spook | Dread |
| telekinesis | Telekinesis | (not implemented: auto-pickup is the default here) |

## Experimentation Table minigames

| id | reference name | our name |
|---|---|---|
| superpairs | Superpairs | Twin Tiles |
| chronomatron | Chronomatron | Echo Keys |
| ultrasequencer | Ultrasequencer | Number Vault |

Tier names (Beginner, High, Grand, Supreme, Transcendent, Metaphysical) are plain adjectives and are kept.

## Items and NPCs

| id | reference name | our name |
|---|---|---|
| ENCHANTMENT_<ENCHANT>_<LEVEL> / <ENCHANT>;<LEVEL> | Enchanted Book | Enchanted Book (generic) |
| ENCHANTED_BOOK | Enchanted Book | Enchanted Book (generic) |
| EXP_BOTTLE | Experience Bottle | Experience Bottle (generic) |
| GRAND_EXP_BOTTLE | Grand Experience Bottle | Grand Experience Bottle (generic) |
| TITANIC_EXP_BOTTLE | Titanic Experience Bottle | Titanic Experience Bottle (generic) |
| COLOSSAL_EXP_BOTTLE | Colossal Experience Bottle | Colossal Experience Bottle (generic) |
| EXPERIMENTATION_TABLE | Experimentation Table | Experimentation Table (generic) |
| enchanting_librarian (NPC) | Librarian | Librarian (generic; only registered when no other module put an NPC on `npc.librarian`) |

## Text that names reference things generically

- Ultimate Jest's item is described as "the jester's blade" and its "Applied To" line says "Special Weapon"
  (the item id `ASPECT_OF_THE_JERRY` belongs to the weapons module, which names it).
- Source lines in the reference books name NPCs/events (Dark Auction, Scorpius, Season of Jerry...). We do not
  copy them: the guide lists generic sources only (enchanting table, anvil, Experimentation Table, levels up by
  use, "rare loot, shops and the Bazaar").

## Experimentation Table ultra-rares (2026-10-06)

| id | reference name | our name |
|---|---|---|
| GOLDEN_BOUNTY | Golden Bounty | Golden Bounty (kept: ordinary words) |
| FATEFUL_STINGER | Fateful Stinger | Fateful Stinger (kept: ordinary words; the reference lore's Manticore line is not copied) |
