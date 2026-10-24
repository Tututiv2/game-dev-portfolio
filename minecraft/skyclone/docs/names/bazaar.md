# bazaar — renames

The Bazaar shows product names from the module that REGISTERS each item (it never names items itself), and
enchanted books by their enchant's registered name. The only player-facing names it invents are its NPC and its
shelf (group) labels. "Bazaar" itself is a generic word and stays.

| id | reference name | our name |
|---|---|---|
| `bazaar_agent` (NPC at hub `npc.bazaar`) | Bazaar Agent / Bazaar NPCs | Bazaar Agent (generic job title, kept) |
| group `KUUDRA` | Kuudra (loot) | Leviathan Loot |
| group `CRIMSON` | Crimson Isle (loot) | Crimson Isle Loot (island id is shared by contract; rename here if the crimson module renames the island) |
| group `MYTHOLOGICAL` | Mythological Ritual / Diana drops | Mythological Loot (generic) |
| group `SLAYER_*` | Revenant / Tarantula / Sven / Voidgloom / Inferno Demonlord / Riftstalker drops | Zombie / Spider / Wolf / Enderman / Blaze / Vampire Slayer Drops (by mob type, no boss names) |
| group `SHARDS` | Attribute / Hunting Shards | Hunting Shards (generic) |
| group `FOSSILS` | Glacite fossils etc. | Fossils & Relics |
| category `WOODS_FISHES` | Woods & Fishes | Woods & Fishes (generic, kept) |
| category `ODDITIES` | Oddities | Oddities (generic, kept) |
| category `ENCHANTMENTS` | (books live in Oddities in the reference) | Enchantments (our sixth tab) |
| seller of the supply drops (wave 5) | (none: research s2.9 design note, "Merchant Guild") | Merchant Guild (a generic phrase from our own research doc, not a reference name). Its "Needed for" lore lists items by their REGISTERED names |

Collection-based groups ("Wheat & Seeds", "Diamond", "Mithril"...) are titled at runtime from the REGISTERED names of
their root items (`groups.json` `titleIds`), so any rename an owning module makes shows up on the shelf automatically.

Descriptions in every menu (help book, warnings, button lore) are written from scratch; no reference text is copied.
