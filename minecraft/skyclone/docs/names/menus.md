# menus — renames

Reference proper nouns this module shows to players, and what we call them. Internal ids keep the
reference id. Every description in the menus (stats, features, islands, settings) is our own wording.

The menus module mostly shows names OTHER modules own: island display names come from
`IslandDef.displayName`, warp names from `registerWarp`, event names from `CalendarEvent.name`, the
mayor from `Features.Mayors.mayorName()`, pet names from the pets module's item defs, the cookie
name from the `BOOSTER_COOKIE` item def. The names below are only the **fallbacks** used while the
owning module has not registered its own.

## Islands (Fast Travel fallbacks, `TravelLayout.java`)

| id | reference name | our name |
|---|---|---|
| jerrys_workshop | Jerry's Workshop | Winter Workshop (same as accessories' lore) |
| kuudra | Kuudra's Hollow | Leviathan's Hollow (the kuudra module's own `IslandDef` name, which wins when kuudra is loaded; "Magmaw" is the Crystal Hollows boss / BAL pet, not this one) |
| private_island, hub, dungeon_hub, barn, garden, mushroom_desert, park, gold_mine, deep_caverns, dwarven_mines, crystal_hollows, spiders_den, the_end, crimson_isle, backwater_bayou, glacite_tunnels, rift | (same) | kept: descriptive/generic place words (CONTRACT §1 keeps "Hub", "Spider's Den") |

## Menu items and buttons

| id | reference name | our name |
|---|---|---|
| (SkyBlock Menu slot 13) | Your SkyBlock Profile / Stats & Equipment | Stats & Equipment (generic) |
| (SkyBlock Menu slot 51) | Booster Cookie | the `BOOSTER_COOKIE` item's registered name; fallback **Cookie Buff** |
| (SkyBlock Menu slot 40, ours) | — | SkyBlock Guide |
| (Calendar slot 51) | Mayor / Election | Mayor & Election (generic) |
| (Stat source) | Heart of the Mountain | kept as the generic system name until the mining module names it |
| (Stat source) | Century Cakes | kept: progression's name for the system |

## Feature index / SkyBlock Guide names (`FeatureIndex.java`, review pass 2026-10-02)

Shown only in the SkyBlock Guide and in "Click to open ...!" hints. Each one copies the name the
OWNING module already chose, so a player sees one name per system.

| id | reference name | our name |
|---|---|---|
| harvestfair | Jacob's Farming Contest | Harvest Fair (farming module's name) |
| winter | Jerry's Workshop / Season of Jerry | Winter Workshop (mayors module: "Season of Gerald") |
| weapons | (none) | Arsenal (weapons module's menu title) |
| cookie | Booster Cookie | "Cookie Buff & Bits" in the guide; the slot itself shows the bank's item name (Boon Cookie) and buff name (Boon Buff) |
| darkauction, mythology, zoo, spooky, newyear | Dark Auction, Mythological Ritual, Traveling Zoo, Spooky Festival, New Year Celebration | kept as the mayors module registers them (generic event words) |
| cshop | Community Shop | kept (generic; the bank's command description uses it) |
| phone (wave 5) | Abiphone | **Ringphone** (extras module's name, `docs/names/extras.md`) |
| galatea (wave 5) | Galatea | "Lotus Atoll & Torrent Canyon" (galatea module's two island names; "Galatea" is never shown) |
| chocolate (wave 5) | Chocolate Factory / Hoppity's Hunt | Chocolate Factory (generic, kept by chocolate) |
| hunting, attributes, soulflow, runes, vitality (wave 5) | Hunting, Attributes, Soulflow, Runic Pedestal, Vitality | kept: generic system words, as their owners register them |
| critter_safari (Fast Travel, wave 5) | Critter Safari | kept (hunting module's island name, a plain phrase) |

## Not shown anywhere

The reference NPC names that sit behind these menus in the reference game (Elizabeth for the bits
shop, the profile/booster NPCs) are never shown by this module.
