# 03 — World Map: Zones, Mobs, NPCs (Hypixel SkyBlock reference)

> Research file 3 of 9 for the SkyBlock clone. Scope: **every island/zone, its sub-areas, how you get there,
> every mob (level / HP / damage / XP / coins / drops), every notable NPC (role / shop / prices), the new-player
> objective chain, and how it all connects.** Original Hypixel names are used; renaming happens later.
>
> **Reliability legend**
> - Plain number = verified against a wiki page fetched while writing this (Oct 2026) or a value I am confident of.
> - `(?)` = from memory, believed right but not re-verified; treat as a sane default, not gospel.
> - `(~)` = deliberately approximate (sizes, coordinates, layout directions) — a builder needs the *feel*, not the block.
>
> **Wiki status (important for later agents):** the official wiki `wiki.hypixel.net` was **shut down July 2026**
> (every page now 302-redirects to the forum thread "End of the Official Hypixel Wiki"). The fandom wiki returns
> HTTP 402 to fetchers. The live community wiki is **`hypixelskyblock.minecraft.wiki`** — use that. Its location
> pages list areas and NPCs well but mob stats live on individual mob pages (e.g. `/w/Zealot`, `/w/Ghost`).
>
> **Version note:** values are "current" as of the 2025–2026 game. Three big 2026 changes are folded in:
> 1. **Hub rework** (SkyBlock 0.24.2, 24 Feb 2026; Alpha 30 Jan 2026) — Hub updated to MC 1.21.9+, split into
>    skill **districts**: Foraging Camp, Combat Settlement, Mining District, Fishing Outpost, Farm, Carnival.
> 2. **Galatea's second island Torrhus Canyon + Critter Safari** (Foraging/Hunting expansion; a third island
>    "Lunarise" is announced but unreleased).
> 3. **Lotus Atoll** (fishing island after Backwater Bayou; Alpha 6 May 2026, v0.24.5).

---

## 0. Overview

### 0.1 Units and stat conventions (so every agent reads numbers the same way)

| Thing | Convention |
|---|---|
| Mob "level" | Displayed as `[Lv15] Zombie`. A label used by the Bestiary; it does NOT drive formulas — HP/damage are per-mob constants. Many mobs exist at several fixed levels (e.g. Enderman Lv42/45/50), each a separate stat row. |
| Mob HP | Raw health points. A new player has **100 HP**; Combat-1 mobs are ~100 HP, endgame world mobs 1M–200M. |
| Mob damage | Damage per melee hit **before** the player's Defense reduction (`effective = dmg × (1 − def/(def+100))`). |
| Combat XP | Base skill XP per kill (before Combat Wisdom). |
| Coins | Coins credited per kill (scaled by Scavenger enchant/talisman). |
| Drop rates | `%` or `1/N`. Rare drops are announced in chat (RARE / VERY RARE / CRAZY RARE / PRAY RNGESUS). Magic Find multiplies rare-drop chances. |
| True Damage | Ignores Defense, reduced only by True Defense. |
| Rift HP | The Rift uses its own tiny scale (Rift hearts; mobs have tens–hundreds of HP). |

### 0.2 The world at a glance — every island

| # | Island / zone | Theme | Entry requirement | Main way in | Primary skill |
|---|---|---|---|---|---|
| 1 | Private Island | Personal build plot + minions | none (spawn) | `/warp home`, island portal | all (minions) |
| 2 | Hub | Town + skill districts + Graveyard/Crypts | none | `/warp hub`, portal on Private Island | all / Combat 1 |
| 3 | The Barn (Farming Islands) | Farmland, windmill | Farming I (?) — reached from the Hub Farm | road from Hub Farm, `/warp barn` | Farming |
| 4 | Mushroom Desert | Mesa, mushroom gorge, Trapper | **Farming V** | north warp pad at The Barn, `/warp desert` | Farming / Combat (trapper) |
| 5 | The Park | Five floating forests | **Foraging I + Lumber Jack quest** | path from Hub Foraging Camp, `/warp park` | Foraging |
| 6 | Gold Mine | Shallow open-pit mine | Mining I (?) | Hub Coal Mine → tunnel, `/warp gold` | Mining |
| 7 | Deep Caverns | 6 stacked cave layers | **Mining V** | Gold Mine pit / `/warp deep` + Lift Operator | Mining |
| 8 | Dwarven Mines | Huge underground dwarf city + mines | **Mining XII** + Rhys's quest (bottom of Deep Caverns) | Obsidian Sanctuary exit, `/warp mines`, `/warp forge` | Mining (HotM) |
| 9 | Glacite Tunnels + Mineshafts | Frozen tunnels below Dwarven Mines; instanced mineshafts | **HotM 7** (+ Secret Railroad Pass in 2026 build) | Dwarven Base Camp, `/warp camp` | Mining (Glacite) |
| 10 | Crystal Hollows | Instanced 5-zone gem cavern | **Mining XII + HotM 4**, 10,000-coin 6 h pass (permanent after Dulin) | Gwendolyn in Dwarven Mines, `/warp ch` / `/warp nucleus` | Mining (gemstones) |
| 11 | Spider's Den | Spider mountain + Arachne | **Combat I** | launchpad from Graveyard, `/warp spider` | Combat |
| 12 | The End | Floating end-stone + Dragon's Nest | **Combat XII**; Bruiser Hideout Combat XX; Void Sepulture **Combat XXV** | launchpad from Spider's Den, `/warp end`, `/warp drag` | Combat |
| 13 | Crimson Isle | Nether island, two factions, Kuudra | **Combat XXII** | launchpad in Spider's Den, `/warp nether`, `/warp kuudra` | Combat / lava Fishing |
| 14 | Jerry's Workshop | Winter island | Seasonal: Late Winter | portal in Hub during the season, `/warp winter` | Combat / winter Fishing |
| 15 | Dungeon Hub | Catacombs lobby | none to visit; floors gated by Catacombs level | `/warp dhub`, Hub Catacombs Entrance | Catacombs |
| 16 | The Garden | Personal 5×5-plot farm | **SkyBlock Level 5** + talk to Sam | `/warp garden` | Farming (Garden) |
| 17 | The Rift | Time-limited mirror dimension | Dimensional Infusion (free every 4 h, 3 stored) | Wizard Portal in the Wilderness | Rift (Motes / Rift Time) |
| 18 | Backwater Bayou | Swamp fishing island | **Fishing V** + talk to Captain Baha | Ship Navigator (Hub Fishing Outpost) | Fishing |
| 19 | Lotus Atoll | Frog/lily fishing island | **Fishing X** + talk to Researcher Gilbert | Ship Navigator | Fishing / Hunting |
| 20 | Galatea — Moonglade Marsh | Foraging island 2 (wetland) | **Foraging XII** | Travel Scroll to Galatea / from the Park (?) | Foraging / Hunting |
| 21 | Galatea — Torrhus Canyon | Desert canyon + springs | Foraging XII + **Heart of the Forest IV** | from Moonglade Marsh | Foraging / Hunting / diving |
| 22 | Critter Safari | Instanced hunting biomes | **Hunting XV** + Safari Ticket | Critter Safari Entrance (Torrhus Canyon) | Hunting |
| 23 | Dark Auction | Black-market auction room | **≥400,000 coins** in purse | Sirius' door in the Hub, on schedule | Economy |
| 24 | Kuudra's Hollow | Instanced 4-player boss | Kuudra Key + faction rep tiers | Crimson Isle, `/warp kuudra` | Combat |
| — | Removed | Blazing Fortress (→ Crimson Isle), Floating Islands, Kuudra's End, pre-2026 Hub areas | — | — | — |

### 0.3 Rough relative scale (for builders)

| Island | Footprint (~) | Vertical range (~) | Feel |
|---|---|---|---|
| Private Island | starts ~10×10 tree islet; build area up to ~240×240 (?) | y 0–255 | empty sky void, your stuff |
| Hub | ~600×600 playable | y 60–190 (Mountain top) | busy town radiating into districts |
| The Barn | ~250×250 | flat, y 70–90 | open farmland, windmill landmark |
| Mushroom Desert | ~400×400 | mesa terraces y 70–200 | layered red/orange plateaus, a gorge |
| The Park | 5 islands each ~120×120 | stepped; each forest higher/further | floating forests linked by launchpads |
| Gold Mine | ~150×150 | y 0–80, terraced | sunlit open-cast mine |
| Deep Caverns | one shaft ~120×120, 6 layers | y 150 → 10 | spiral descent, darker per layer |
| Dwarven Mines | ~700×700 | y 70–250 under a stone sky | vast cavern with a city, palace, cliffs |
| Glacite Tunnels | ~400×400 branching | below Dwarven Mines | frozen blue tunnels and lakes |
| Crystal Hollows | **~620×620** (x/z 202–823), y 31–188 | 4 quadrants + magma floor | jumbled giant cave, everything breakable |
| Spider's Den | ~300×300 | mountain y 60–190 | dark webbed mountain in the rain |
| The End | ~400×400 | y 0–100 floating end stone | purple void, obsidian pillars |
| Crimson Isle | ~800×800 | y 60–200 + volcano | red nether biomes, two rival towns |
| Garden | 5×5 grid of 96×96 plots = **480×480** | flat | personal farm, barn in centre |

---

## 1. Travel system (how islands connect mechanically)

### 1.1 Methods

| Method | How it works | Implementation note |
|---|---|---|
| Island portals | Walk-in portal frames on the Private Island and in hubs | Instant transfer to that island's spawn; a frame appears on the Private Island for every island you have unlocked. |
| Launchpads | Pads that fling the player along a scripted arc into another zone | Hub Graveyard→Spider's Den, Spider's Den→End, Spider's Den→Crimson Isle, Park forest↔forest, Barn→Mushroom Desert. Implement as teleport + 2–3 s flight animation. |
| `/warp <name>` & Fast Travel menu | GUI listing unlocked destinations | Island spawns unlock by visiting; sub-warps need **Travel Scrolls** (consumed on use, unlock permanently). |
| Ship Navigator | Captain Baha at the Hub Fishing Outpost | Destinations: Backwater Bayou (Fishing V), Lotus Atoll (Fishing X). |
| Lift / minecarts | Lift Operator in Deep Caverns; Ticket Masters in Dwarven Mines | Teleport between layers/stations already visited. |
| Abiphone | Phone contacts that open some NPC shops remotely | Removes walk-backs; not travel. |
| Rift portal | Wizard Portal in the Wilderness consumes a Dimensional Infusion | Rift has its own timer and ejects you to the Hub. |

### 1.2 Travel Scroll catalogue (?)

| Scroll → warp | Unlock source (?) |
|---|---|
| Hub Castle (`/warp castle`) | Elizabeth (Bits shop) |
| Hub Crypts (`/warp crypt`) | Elizabeth |
| Museum (`/warp museum`) | Elizabeth |
| Dark Auction (`/warp da`) | Elizabeth |
| Wizard Tower (`/warp wizard`) | Elizabeth |
| Jungle Island (`/warp jungle`) | Elizabeth |
| Howling Cave (`/warp howl`) | Elizabeth |
| Spider's Den Top of Nest (`/warp nest`) | Elizabeth |
| Arachne's Sanctuary (`/warp arachne`) | Elizabeth |
| Dragon's Nest (`/warp drag`) | Elizabeth |
| Void Sepulture (`/warp void`) | Elizabeth |
| Dwarven Forge (`/warp forge`) | Elizabeth |
| Dwarven Base Camp (`/warp camp`) | Elizabeth |
| Crystal Nucleus (`/warp nucleus`) | Elizabeth |
| Trapper's Den (`/warp trapper`) | Elizabeth |
| Crimson Isle: Dragontail, Scarleton, Smoldering Tomb, Kuudra Passage | Elizabeth |
| Galatea, Murkwater Loch, Torrhus Canyon, Torrhus Springs, Critter Safari Entrance | Galatea NPCs / Forest Essence perks (?) |
| Island spawns (Garden, Barn, Desert, Gold Mine, Deep Caverns, Dungeon Hub, Park, Spider's Den, End, Mines, Crimson Isle) | free once visited |

Price anchor (?): sub-warp scrolls ~300–900 Bits each — the Bits shop is the endgame sink for convenience.

### 1.3 Connection graph (physical)

```
                       [ The Rift ] <-(Wizard Portal, Wilderness)-+
                                                                  |
[Private Island] <==portal==> [ HUB ] --Farm road--> [The Barn] --pad--> [Mushroom Desert]
                               |  |  \
              Foraging Camp ---+  |   +--Graveyard pad--> [Spider's Den] --pad--> [The End]
                   |              |                              \
                [The Park]        |                               +--pad--> [Crimson Isle] --> [Kuudra's Hollow]
                   |              |
           (Foraging XII)    Mining District -> Coal Mine -> [Gold Mine] -> [Deep Caverns x6] -> [Dwarven Mines]
                   v                                                                             |          \
          [Galatea: Moonglade Marsh] -(HotF IV)-> [Torrhus Canyon] -> [Critter Safari]   [Glacite Tunnels]  [Crystal Hollows]
                                                                                                |
                                                                                       [Glacite Mineshafts]
  Hub Fishing Outpost --Ship--> [Backwater Bayou] --(Fishing X)--> [Lotus Atoll]
  Hub Catacombs Entrance --> [Dungeon Hub] --> Catacombs Entrance, F1..F7, M1..M7
  Hub (seasonal portal, Late Winter) --> [Jerry's Workshop]
  /warp garden (SB Lv5) --> [The Garden]
  Hub Wilderness (scheduled door, 400k purse) --> [Dark Auction]
```

---

## 2. New-player objective chain (the guided start)

SkyBlock guides players with a sidebar **"Objective:"** line plus (since 2023) the **SkyBlock Level / SkyBlock Guide**
task menu. The classic chain is below; the 2026 Hub rework added **per-district first-entry objectives** (verified for
the Mining District). Build the clone's onboarding from this.

### 2.1 Classic objective chain (Private Island → Hub) (?)

| Step | Objective (sidebar) | Trigger | Teaches |
|---|---|---|---|
| 1 | Break a Log | Break any log on the starting tree | Breaking blocks; collections |
| 2 | Craft a Workbench | Craft a crafting table | Crafting |
| 3 | Craft a Wood Pickaxe | Craft wooden pickaxe | Tools |
| 4 | Mine Cobblestone (?) | Mine from the island generator | Generators / collections |
| 5 | Place a Minion (?) | Place the starter Cobblestone Minion I | Minions = idle core |
| 6 | Talk to Jerry | Right-click Jerry | Island helper |
| 7 | Go to the Hub | Use the island portal | Hub is the centre |
| 8 | Talk to the Banker (?) | Open the Bank | Bank & interest |
| 9 | Talk to a Merchant (?) | Open any merchant shop | NPC buy/sell |
| 10 | Talk to the Lazy Miner (?) | Coal Mine NPC | Mining intro |
| 11 | Explore the Gold Mine / Deep Caverns (?) | Enter zones | Island chain |
| 12 | Visit The Barn (?) | Hub Farm road | Farming chain |
| 13 | Collect Fairy Souls (?) | Collect 5 → trade at Tia | Exploration reward |
| — | After that the line points at the next locked island ("Reach Combat I to visit the Spider's Den"). | | |

### 2.2 2026 district first-entry objectives

| District | First-entry objectives | Unlock |
|---|---|---|
| Mining District (verified) | 1. Purchase Mining Tools from the Mining Merchant · 2. Learn about Reforges at the Blacksmith · 3. Travel to the Coal Mine | Mining flow |
| Foraging Camp (verified) | Lumber Jack: collect **20 Oak Logs** → **Promising Axe**; Carpenter takes **64 Wool** → unlocks the **Carpentry** skill | Foraging + Carpentry; Park needs the Lumber Jack quest |
| Combat Settlement (verified) | Bartender gives the initial combat-training quest; Jax's Target Practice unlocks better arrows | Combat flow |
| Fishing Outpost (?) | Fisherman intro; Captain Baha's ship unlocks at Fishing V | Fishing flow |
| Farm (?) | Farmer intro → The Barn | Farming flow |

### 2.3 SkyBlock Level gates (persistent guide)

SkyBlock XP comes from tasks (Core, Event, Dungeon, Essence, Slaying, Skill, Misc, Story).

| SkyBlock Level | Gates |
|---|---|
| 5 | **The Garden** (verified) |
| other early levels | Auction House creation, Bazaar features, some NPC features (?) — design freely |

> Implementation advice: make objectives data — `{id, text, trigger(eventFilter), next}` — and let each island register
> its own first-entry objectives. That is how the 2026 rework behaves.

---

## 3. Private Island

| Field | Value |
|---|---|
| Spawn | Small oak-tree islet (~8×8 grass + a tree + a chest), island portal to the Hub, Jerry beside you |
| Build area | Limited at first; up to ~240×240 (?), y 0–255 |
| Minion slots | Start **5**; +1 per unique-minion crafting milestone; +2 from Community Shop; max **26** (?) |
| Visitors | Co-op members and `/visit` guests (guest limit raised in Community Shop (?)) |
| Fairy souls | 0 (?) |
| Mobs | None natural |

### 3.1 Jerry
| Role | Detail |
|---|---|
| Helper | Island tips; features in Mayor Jerry events |
| Portals | Frames appear as destinations unlock |

### 3.2 Starting kit (clone suggestion)
Cobblestone Minion I, a chest, the oak tree, Rookie tools path. Natural first builds: cobblestone generator, tree farm, minion pads.

---

## 4. The Hub

The Hub has two forms: **classic (2019–2025)** and the **2026 district rework**. Build the 2026 layout; classic names
are listed because most mob/NPC data still hangs off them.

### 4.1 Layout (for builders) (~)

```
                             N
             Ruins (Old Wolves)        Mountain (Weather Station on top)
                     \                  /         \
   Wilderness ------ Wizard Tower ------          Mining District -> Coal Mine -> Gold Mine
   (Rift portal,                                    (Blacksmith, Dusk, Smithmonger)
    Dark Auction door)        \                   /
                               \                 /
   Foraging Camp --------- [ VILLAGE: spawn, fountain, Bank, AH, Bazaar Alley, Community Center, Museum ] ---- Graveyard -> Crypts
   (Lumber Jack, -> Park)        |         \                                    (Pat, Romero, Catacombs entrance, pad to Spider's Den)
                       Combat Settlement     Farm / Farmhouse -> road to The Barn
                       (Archery Range, Library,
                        Thaumaturgist, Trade Center)
                                 \
                       Fishing Outpost (Fisherman's Hut, Ship)        Colosseum / Carnival
                             S
```
- **Village**: stone/plaster medieval town on a raised plaza with a fountain; everything radiates out. ~y70.
- **Mountain**: tallest landmark (~y190), spiral path, **Weather Station** on top.
- **Graveyard**: gloomy field of graves and a chapel; **Crypts** are catacombs beneath it.
- **Wilderness**: dense dark-forest edge; contains the Wizard Portal (Rift) and Sirius' Dark Auction door.
- **Ruins**: crumbled stone temple with wolves; Combat III recommended.
- **Colosseum**: sand arena for events; Carnival grounds nearby.
- **Hub Castle**: large castle (still a Travel Scroll destination).

### 4.2 Sub-areas (2026 list, verified names)

| Sub-area | Contents |
|---|---|
| Village | Spawn plaza with: Abiphones & Co., Auction House, Bank, Bazaar Alley, Builder's House, Community Center, Security Hall, Election Room, Fashion Shop, Flower House, Canvas Room, Museum, Hexatorum, Pet Care, Rabbit House, Sewer, Shen's Auction, Regalia Room (needs Shen's Regalia), Taylor's Shop |
| Forest | Light woodland between Village and Foraging Camp |
| Foraging Camp | **(-128, 74, -33)**. Lumber Jack, Carpenter, Lumber Merchant (only shop: axes + wood). 2 fairy souls. Gate to The Park |
| Combat Settlement | **(-51, 69, -77)**. Archery Range, Library, Thaumaturgist, Trade Center. 14 NPCs. 1 fairy soul |
| Archery Range | Jax — Target Practice; higher-tier arrows |
| Library | Librarian — enchanting intro + items |
| Thaumaturgist | Maxwell — accessory Powers |
| Trade Center | Trade NPCs |
| Graveyard | Graveyard Zombies, Zombie Villagers; Pat, Romero; Crypt entrances |
| Tavern | Bartender |
| Crypts | Crypt Ghouls, Golden Ghouls — Zombie Slayer grind |
| Catacombs Entrance | Gate to the Dungeon Hub |
| Mining District | **(2, 62, -122)**. Blacksmith, Dusk (Runecrafting), Mining Merchant, Smithmonger. 2 fairy souls |
| Blacksmith | Reforge anvil |
| Coal Mine | Coal + cobble; Lazy Miner; tunnel to Gold Mine |
| Farm / Farmhouse | Crop fields, Farm Merchant, road to The Barn |
| Fishing Outpost / Fisherman's Hut | Fish Merchant, Fisherman, **the Ship** |
| Carnival | Foxy-mayor event venue |
| Colosseum | Event arena |
| Mountain / Weather Station | Central landmark |
| Wizard Tower | Wizard — Rift infusions |
| Ruins | Wolves, Old Wolves |
| Unincorporated | Outskirts |
| Wilderness | Rift portal; Dark Auction entrance |
| Artist's Abode | Artist NPC |
| Dark Auction | Hidden room; ≥400,000 coins to enter |

Hub fairy souls: **80**.

### 4.3 Hub mobs

| Mob | Lv | HP | Dmg | Combat XP | Coins | Drops | Where / spawn |
|---|---|---|---|---|---|---|---|
| Graveyard Zombie | 1 | 100 | 20 | 6 | 1 | Rotten Flesh ×1 (100%), Potato 0–1, Carrot 0–1, Poisonous Potato 0–1 | Graveyard; day, more at night |
| Zombie Villager | 1 | 120 | 24 | 7 | 1 | same as above | Graveyard; **night only**, frequent |
| Crypt Ghoul | 30 | 2,000 | 350 | 32 (?) | 13 (?) | Rotten Flesh ×1–3; main value is Revenant (Zombie Slayer) XP | Crypts |
| Golden Ghoul | 60 | 45,000 | 800 | 50 (?) | 10 (?) | Gold Ingot ×1–3, Golden Powder (rare) (?) | Crypts, rare variant (?) |
| Wolf | 15 | 250 | 90 (?) | 15 (?) | 3 (?) | Bone ×1 | Ruins |
| Old Wolf | 50 | 15,000 | 800 (?) | 40 (?) | 15 (?) | Bone, rare wolf drops (?) | Ruins (rarer) |
| Rat | 1 (?) | low | — | 1 | 1 | Rat pet chance (?) | Sewer |
| Farm animals (Chicken/Cow/Pig/Sheep/Rabbit) | 1 | 50–100 (?) | 0 | Farming XP | 0 | raw meat, feather, leather, wool | Farm |
| **Mythological Ritual** (Mayor Diana event; dug from Griffin burrows anywhere in Hub) | | | | | | | |
| — Minos Hunter | 60 (?) | 125,000 (?) | — | — | — | Griffin Feather, coins | burrow |
| — Siamese Lynxes | 25 (?) | 250,000 (?) | — | — | — | Griffin Feather | must kill both close together |
| — Minotaur | 120 (?) | 3,000,000 (?) | — | — | — | Daedalus Stick (rare) | Rare+ Griffin |
| — Gaia Construct | 140 (?) | 1,500,000 (?) | — | — | — | Dwarf Turtle Shelmet (?) | hit-count shell |
| — Minos Champion | 175 (?) | 4,000,000 (?) | — | — | — | Minos Relic | Epic+ Griffin |
| — Minos Inquisitor | 750 (?) | 40,000,000 (?) | — | — | — | Chimera book (~1%), Dwarf Turtle Shelmet, Crochet Tiger Plushie, Antique Remedies | Legendary+ Griffin, very rare |
| Spooky Festival mobs | — | — | — | — | — | Candy | any island; Hub busiest |
| Revenant Horror T1–T5 (Zombie Slayer) | — | — | — | — | — | — | spawned by Maddox quest; Crypts canonical |

### 4.4 Hub NPCs (roles + shops)

| NPC | Location | Role | Shop / prices (?) |
|---|---|---|---|
| Banker | Bank | Deposit/withdraw; interest every 31 h | Account upgrades |
| Auction Master / Agent | Auction House | Auctions | — |
| Bazaar | Bazaar Alley | Buy/sell orders | — |
| Builder | Builder's House | Building blocks | ~1–10 coins per block |
| Elizabeth (Community Center) | Village | Bits shop, community upgrades | God Potion ~1,500 Bits, Kat Flower ~500, Heat Core ~3,000, Travel Scrolls (?) |
| Election NPCs | Election Room | Mayor/Minister vote each SB year | — |
| Museum curator | Museum | Donations → milestones | — |
| Shen | Shen's Auction | Mayor-perk auction / Shen's Regalia | bids |
| Taylor | Taylor's Shop | Cosmetics, special items | (?) |
| Kat | Pet Care | Pet rarity upgrades (time + coins + items) | — |
| Rabbit House NPCs | Rabbit House | Chocolate Factory / Hoppity's Hunt | — |
| Abiphones & Co. | Village | Sells Abiphones | Abiphone X Plus ~1,000,000 (?) |
| Farm Merchant | Farm | Farming goods | Wheat 7, Carrot 3, Potato 3, Melon 2, Pumpkin 8, Sugar Cane 5, Cocoa 5, Sand 4, Mushrooms 12, Bone 8, Rookie Hoe 10 (?) |
| Mining Merchant | Mining District | Tools/ores | Coal 4, Iron Ingot 5, Gold Ingot 6, Gravel 6, Cobblestone 3, Rookie Pickaxe 12 (?) |
| Lumber Merchant | Foraging Camp | Axes & logs | Each log 5, Rookie Axe 12, Promising Axe 35, Sweet Axe 100, Efficient Axe 100 (?) |
| Fish Merchant | Fishing Outpost | Fishing basics | Fishing Rod 100, Raw Fish 20, Raw Salmon 30, Clownfish 100, Pufferfish 40 (?) |
| Adventurer | Village / Combat Settlement | Mob drops & talismans | Rotten Flesh 8, Bone 8, String 10, Slimeball 14, Gunpowder 10; Zombie Talisman 500, Skeleton Talisman 500, Village Talisman 2,500, Mine Affinity Talisman 2,500, Intimidation Talisman 10,000, Scavenger Talisman 10,000 (?) |
| Weaponsmith | Combat Settlement | Starter weapons | Undead Sword 100, Spider Sword 100, End Sword 150, Cleaver 40, Flaming Sword 1,000, Hunter Knife 1,500, Bow 25, 64 Arrows 40 (?) |
| Rosetta | Combat Settlement | Starter armour | Rosetta's armour set (~1,000 total) (?) |
| Librarian | Library | Enchanting | Experience Bottle 30, basic Enchanted Books (?) |
| Alchemist | Village | Brewing | Nether Wart 10, Water Bottle 6, Sugar 4, Spider Eye 12 (?) |
| Bartender | Tavern | Potions + combat quest | Cheap Coffee 1,000, Decent Coffee 3,000, Black Coffee 6,000 (?) |
| Pat | Graveyard | Flint & gravel | Flint 6, Gravel 6 (?) |
| Jax | Archery Range | Arrows; Target Practice | Flint Arrow ~1 each; better arrows unlocked by practice |
| Blacksmith | Mining District | Reforging | cost by rarity: Common 250 → Mythic ~1,000,000 (?) |
| Smithmonger | Mining District | Reforge Stones | ~10k–1M (?) |
| Dusk | Mining District | Runecrafting intro | — |
| Maxwell | Thaumaturgist | Accessory Powers (Magical Power) | — |
| Jacobus | Combat Settlement | Accessory Bag upgrades | escalating coin costs (?) |
| Ozanne | Combat Settlement | Old (pre-Apr 2022) accessory reforge refunds | — |
| Lumber Jack | Foraging Camp | 20 Oak Logs → Promising Axe | — |
| Carpenter | Foraging Camp | 64 Wool → Carpentry | furniture recipes |
| Lazy Miner | Coal Mine | Mining intro quest | — |
| Wizard | Wizard Tower | Dimensional Infusion | 32 Grand Experience Bottles or 200 Bits |
| Sirius | Dark Auction | Black-market auctions | — |
| Maddox the Slayer | Tavern (?) | Slayer quests | quest fees per tier (see slayer file) |
| Captain Baha | Fishing Outpost | Ship Navigator | — |
| Fisherman | Fisherman's Hut | Fishing intro | — |
| Tia the Fairy | Village/Wilderness (?) | Trade 5 Fairy Souls → +stats (HP, Defense, Strength, Speed) | — |
| Romero | Graveyard | Lore / quest | — |
| Fear Mongerer | Village (Spooky) | Spooky shop | Candy prices |
| Baker | Village (New Year) | Free New Year Cake | — |
| Bingo NPC | Village | Bingo rewards | Bingo Points |
| Carnival NPCs | Carnival | Foxy-mayor event games | Carnival tickets |
| Artist | Artist's Abode | Paintings/cosmetics | — |

---

## 5. Farming Islands — The Barn

| Field | Value |
|---|---|
| Entry | Road from the Hub Farm (Farming I recommended (?)); `/warp barn` once visited |
| Layout (~) | Rolling flat farmland ~250×250 around a big **windmill** and a red barn. Large rectangular crop fields (wheat, carrot, potato, pumpkin, melon, cocoa on a jungle-log wall, sugar cane by a pond). Fenced pens: cows, pigs, chickens, sheep. North edge: **warp pad** to Mushroom Desert. |
| Fairy souls | 7 (?) |

### 5.1 Barn mobs (passive)
| Mob | Lv | HP | Dmg | XP | Coins | Drops |
|---|---|---|---|---|---|---|
| Cow | 1 | 100 (?) | 0 | Farming XP (?) | 0 | Raw Beef 1–2, Leather 0–2 |
| Pig | 1 | 100 (?) | 0 | Farming XP | 0 | Raw Porkchop 1–3 |
| Chicken | 1 | 50 (?) | 0 | Farming XP | 0 | Raw Chicken 1, Feather 0–2 |
| Sheep | 1 | 100 (?) | 0 | Farming XP | 0 | Raw Mutton 1–2, Wool 1 |
| Rabbit | 1 | 50 (?) | 0 | Farming XP | 0 | Raw Rabbit, Rabbit Hide, Rabbit's Foot (rare) |

> Killing farm animals grants **Farming XP**, not Combat XP — keep that rule.

### 5.2 Barn NPCs
| NPC | Role |
|---|---|
| Windmill Operator | Flavour / tips (?) |
| Farmhand (?) | Farming tools |
| Jacob | Farming Contests (every 3 SB days, 20 min); now mainly in the **Garden** |

---

## 6. Mushroom Desert

| Field | Value |
|---|---|
| Entry | **Farming V**; north warp pad at The Barn; `/warp desert`, `/warp trapper` |
| Layout (~) | ~400×400 badlands: tiered red/orange mesa plateaus up to ~y200 cut by the **Mushroom Gorge** (deep ravine of giant mushrooms + mooshrooms). An **Oasis** (pond, cane, cocoa) in the low centre. **Desert Settlement** at the entrance (sand + cactus). Caves beneath. |
| Fairy souls | **13** |

### 6.1 Sub-areas
| Sub-area | Content |
|---|---|
| Desert Settlement | Entrance; sand & cactus |
| Mushroom Gorge | Mushrooms, Mushroom Cows |
| Oasis | Sugar cane, cocoa, fishing |
| Shepherd's Keep | Sheep pens; Shepherd's shearing task |
| Trapper's Den | Trevor (281.5, 104, -542.5), Talbot, Tony |
| Jake's House | Plateau house (261.5, 184, -565.5); trades animals for axes & reforge stones |
| Archaeological Site | Treasure Hunter (200.5, 92, -437) |
| Glowing Mushroom Cave / Overgrown Mushroom Cave | Mushroom caves |
| Underground Lab | Hidden lore lab |
| Treasure Hunter Camp | Buried-treasure dig sites |

### 6.2 Mobs
| Mob | Lv | Drops |
|---|---|---|
| Rabbit | 1 | Rabbit Hide, Rabbit's Foot, Raw Rabbit |
| Sheep | 1 | White Wool, Raw Mutton |
| Mushroom Cow | 1 | Red Mushroom, Raw Beef, Leather |
| Trevor's quarry (Cow, Pig, Sheep, Chicken, Rabbit, Horse) | tiered | Pelts |

### 6.3 Trevor the Trapper — hunting loop
Talk to Trevor → he names an animal + rarity + a hint area → track it (it flees, glows when near) → kill → **Pelts**.

| Rarity | Animal HP (?) | Pelts (?) |
|---|---|---|
| Trackable | 100 | 5 |
| Untrackable | 500 | 10 |
| Undetected | 1,000 | 15 |
| Endangered | 5,000 | 20 |
| Elusive | 10,000 | 25 |

Short cooldown between quests (~60 s (?)). **Talbot** sells Pelt-priced items (?); **Tony** sells **Tier XII Farming Minion** upgrades (verified).

### 6.4 Other NPCs
| NPC | Role |
|---|---|
| Treasure Hunter | Sells hints (coins) to buried treasure chests |
| Jake | Animals → axes/reforge stones |
| Shepherd | Shear N sheep at once task |

---

## 7. The Park (Foraging island 1)

| Field | Value |
|---|---|
| Entry | **Foraging I + Lumber Jack's quest** (Hub Foraging Camp); `/warp park` |
| Layout (~) | Five floating forest islands in a chain, each a different tree biome and **gated by Foraging level**, linked by launchpads; each next island higher/further. Caves under the Spruce/Dark woods hold wolf spirits. |
| Fairy souls | **12** |

### 7.1 Forests
| Forest | Req | Logs | Content |
|---|---|---|---|
| Birch Park | Foraging I | Birch | Entrance; Charlie |
| Spruce Woods | Foraging II | Spruce | Snowy pines; Kelly; Howling Cave entrance |
| Dark Thicket | Foraging III | Dark Oak | Dense canopy; Ryan; Soul/Spirit caves |
| Savanna Woodland | Foraging IV | Acacia | Dry plateau; Melody's Plateau |
| Jungle Island | Foraging V | Jungle | Top island; Molbert; `/warp jungle` |

### 7.2 Sub-areas
| Sub-area | Content |
|---|---|
| Howling Cave / Soul Cave / Spirit Cave | Wolf-spirit caves |
| Trials of Fire | Campfire Badge challenge (deliver items across tiers → Campfire Talisman upgrades) |
| Melody's Plateau | Melody's Harp songs → Intelligence |

### 7.3 Mobs (Howling Cave, verified stats)
| Mob | Lv | HP | Dmg | XP (?) | Drops |
|---|---|---|---|---|---|
| Pack Spirit | 30 | 6,000 | 300 | 35 | Bone, logs, Hound pet (rare) (?) |
| Howling Spirit | 35 | 7,000 | 450 | 40 | Bone, Hound pet (rare) (?) |
| Soul of the Alpha | 55 | 31,150 | 1,425 | 100 | Hound pet, rare wolf drops (?) |

### 7.4 NPCs
| NPC | Where | Role |
|---|---|---|
| Charlie | Birch Park | Quest-giver |
| Kelly | Spruce Woods | Quest-giver |
| Ryan | Dark Thicket | Quest-giver (?) |
| Melody | Melody's Plateau | Harp minigame |
| Molbert | Jungle Island | Quest-giver |
| Gustave (?) | Trials of Fire | Campfire trials |

---

## 8. Gold Mine

| Field | Value |
|---|---|
| Entry | From the Hub **Coal Mine** tunnel (Mining I (?)); `/warp gold` |
| Layout (~) | ~150×150 sunlit **open-pit mine** with timber scaffolding, minecart rails and walkways on terraced walls; gold seams, a forge hut, a central pit leading down to Deep Caverns. |
| Ores | Gold (main), Iron, Coal, Cobblestone, Stone |
| Fairy souls | 12 (?) |
| Mobs | Essentially none hostile (?) — a safe learn-to-mine zone. |

### 8.1 NPCs
| NPC | Role | Shop (?) |
|---|---|---|
| Gold Forger | Gold gear | Golden armour pieces ~10–100 coins |
| Iron Forger | Iron/chain gear | Chainmail pieces ~100 each |
| Rusty | Recovers lost pickaxes | fee |

---

## 9. Deep Caverns

| Field | Value |
|---|---|
| Entry | **Mining V**; from Gold Mine; `/warp deep` |
| Layout (~) | One vertical spiral shaft ~120×120 with **six layers**, each darker and more dangerous; a **Lift** connects visited layers. Bottom = **Obsidian Sanctuary** with **Rhys**, gate to Dwarven Mines. |
| Fairy souls | 21 (?) |

### 9.1 Layers (verified names/ores/mobs)
| # | Layer | Ores | Mobs | Mood |
|---|---|---|---|---|
| 1 | Gunpowder Mines | Coal, Iron, Gold | Sneaky Creeper | grey stone, TNT crates |
| 2 | Lapis Quarry | Lapis | Lapis Zombie | blue quarry |
| 3 | Pigmen's Den | Redstone, Gold | Redstone Pigman | red-lit den |
| 4 | Slimehill | Emerald | Emerald Slime | green slime pools |
| 5 | Diamond Reserve | Diamond | Miner Zombie, Miner Skeleton | cold and deep |
| 6 | Obsidian Sanctuary | Diamond, Diamond Blocks, Obsidian | Diamond Zombie/Skeleton | black obsidian hall |

### 9.2 Mobs (stats (?), believed ±25%)
| Mob | Layer | Lv | HP | Dmg | XP | Coins | Drops |
|---|---|---|---|---|---|---|---|
| Sneaky Creeper | Gunpowder Mines | 3 | 120 | 80 (explosion) | 8 | 1 | Gunpowder ×1–3 |
| Lapis Zombie | Lapis Quarry | 7 | 200 | 50 | 12 | 2 | Rotten Flesh; **Lapis Armor piece ~1%** |
| Redstone Pigman | Pigmen's Den | 10 | 250 | 75 | 20 | 3 | Gold Nugget |
| Emerald Slime | Slimehill | 5 / 10 / 15 | 80 / 150 / 250 | 30 / 70 / 150 | 9–15 | 1–3 | Slimeball, Emerald (~5%) |
| Miner Zombie | Diamond Reserve | 15 | 250 | 100 | 20 | 4 | Rotten Flesh, **Miner Armor piece ~0.5%** |
| Miner Skeleton | Diamond Reserve | 15 | 250 | 100 | 20 | 4 | Bone, Miner Armor piece |
| Diamond Zombie | Obsidian Sanctuary | 20 | 300 | 150 | 25 | 5 | Diamond (rare) |
| Diamond Skeleton | Obsidian Sanctuary | 20 | 300 | 150 | 25 | 5 | Diamond (rare), Bone |

### 9.3 NPCs
| NPC | Role |
|---|---|
| Lift Operator | Lift to visited layers; free |
| **Rhys** | Bottom; quest that opens the **Dwarven Mines** |

---

## 10. Dwarven Mines

| Field | Value |
|---|---|
| Entry | **Mining XII** + **Rhys's** quest; `/warp mines`, `/warp forge`, `/warp camp` |
| Layout (~) | Colossal cavern (~700×700) with a stone sky. **Dwarven Village** hub at the entrance; **two main paths**: left over the **Palace Bridge** to the **Royal Palace** (gold-trimmed citadel), right to **The Forge** and the deep mines. Lava falls; mithril (grey/green/cyan blocks) coats the walls; titanium is rare white blocks. Ice in the north (Great Ice Wall); a sunken dark basin (**The Mist**). |
| Ores | Mithril tiers, Titanium, Coal, Iron, Gold, Lapis, Redstone, Emerald, Diamond, Hard Stone |
| Powders | **Mithril**, **Gemstone** (CH), **Glacite** (Glacite) |
| Events | Every **20 min** (10.4) |
| NPCs | **73** |
| Fairy souls | 11 (?) |

### 10.1 Sub-areas (~)
| Sub-area | Content |
|---|---|
| Dwarven Village | Banker Broadjaw, Bubu, Bulvar, Puzzler, Fetchur |
| The Forge / Forge Basin | Timed forge recipes |
| Royal Palace / Royal Quarters / Hanging Court | The King, commissions |
| Palace Bridge | Village ↔ Palace |
| Royal Mines, Cliffside Veins, Rampart's Quarry, Upper Mines, Far Reserve, Lava Springs | Mithril/titanium regions (commission targets) |
| Goblin Burrows | Goblin camp; Goblin Raids |
| Great Ice Wall | Ice Walkers |
| Aristocrat Passage / Barracks of Heroes / Grand Library / Divan's Gateway / Gateway | Lore halls & connectors |
| The Mist | Dark basin of **Ghosts** |
| Dwarven Tavern | Tavern NPCs |
| Dwarven Base Camp | Entrance to Glacite Tunnels |
| Crystal Hollows entrance | Gwendolyn |

### 10.2 Mobs
| Mob | Lv | HP | Dmg | XP | Drops | Where |
|---|---|---|---|---|---|---|
| Goblin | 25 (?) | 1,500 (?) | 400 (?) | 10 (?) | Goblin Egg (~1%?), coins | Goblin Burrows, Rampart's Quarry |
| Weakling Goblin | 20 (?) | 1,000 (?) | 200 (?) | 8 (?) | — | Goblin Burrows |
| Knifethrower | 25 (?) | 1,500 (?) | 500 ranged (?) | 10 | — | Goblin Burrows |
| Fireslinger | 25 (?) | 1,500 (?) | 600 fire (?) | 10 | — | Goblin Burrows |
| Ice Walker | 45 (?) | 3,000 (?) | 500 (?) | 30 (?) | Glacite Jewel (rare), ice | Great Ice Wall |
| Treasure Hoarder | 70 (?) | 15,000 (?) | 1,000 (?) | 50 | Treasurite, Starfall (?) | Upper Mines |
| Star Sentry | 50 (?) | **hit-based (10 hits)** | — | — | Starfall, Treasurite | Fallen Star event |
| **Golden Goblin** | 50 (?) | small, flees | 0 | — | coins / powder burst | rare spawn while mining (mini-boss chase) |
| Diamond Goblin | (?) | small, flees | 0 | — | diamond-themed rewards | rare, Goblin events |
| **Ghost** | **250** | **1,000,000** | **1,000 + 100 True** | **100** | Volta 0.2%, Sorrow 0.12%, Plasma 0.1%, Ghostly Boots 0.01%, 1M coins 0.01% | **The Mist**; only melee hurts them |
| Powder Ghast | (?) | (?) | — | — | large Mithril Powder burst | random event |
| Glacite Walker | (?) | (?) | — | — | Glacite | Base Camp side (2024+) |

### 10.3 Commissions (core loop)
- The **King** (rotates daily; Kings Yolkar, Brammor, Emkam, Redros, Erren, Thormyr, Kars (?)) gives commissions; 2 slots base, more via HotM perks. Completing → **HotM XP + Mithril Powder**; bonus for the first few each day (?).
- **Emissaries** (one per region; 7 zones) hand them in: Braum, Ceanna, Carlton, Wilson, Lilith, Fraiser, Eliza (?).

| Commission (examples) | Target (?) |
|---|---|
| Mithril Miner | Mine 350 Mithril |
| `<Area>` Mithril | Mine 250 Mithril in a named region |
| Titanium Miner | Mine 15 Titanium |
| `<Area>` Titanium | Mine 10 Titanium in a region |
| Goblin Slayer | Kill 100 Goblins |
| Ice Walker Slayer | Kill 50 Ice Walkers |
| Glacite Walker Slayer | Kill 50 |
| Treasure Hoarder Puncher | Damage Treasure Hoarders 10 times |
| Star Sentry Puncher | Damage 10 times |
| Golden Goblin Slayer | Kill 1 |
| Goblin Raid / Raffle / 2x Mithril Powder Collector / Lucky Raffle | Event participation |

### 10.4 Events (every 20 min, verified categories)
| Event | Category | Effect |
|---|---|---|
| 2x Powder | Passive | Double powder |
| Gone with the Wind | Passive | Mining Speed when mining with the wind |
| Better Together | Passive | Bonus near other players |
| Mithril Gourmand | Active | Collect Tasty Mithril |
| Goblin Raid | Active | Kill goblin horde within time limit |
| Raffle | Active | Tickets from mining → draw |
| Fallen Star | Random | Crashed star; team-mine its crystal; Star Sentries |
| Powder Ghast | Random | Ghast drops powder |

### 10.5 NPCs
| NPC | Role |
|---|---|
| The King | Commissions |
| Banker Broadjaw | Bank |
| Bubu | Pickaxes |
| Bulvar | Minions |
| Geo | Gemstone application/removal (coin fee) |
| Marigold | Gold Essence perks |
| Puzzler | Daily puzzle → Mithril Powder (~1,000 (?)) |
| Fetchur | Daily fetch item → Mithril Powder (?) |
| Forge NPC | Timed recipes: Refined Mithril, Mithril Plate, drills, Gemstone Mixture … (1 h – days) |
| Gwendolyn | **Crystal Hollows Pass: 10,000 coins / 6 h** |
| Ticket Masters | Minecart fast travel |
| Emissaries ×7 | Commission hand-in |

---

## 11. Glacite Tunnels & Glacite Mineshafts

| Field | Value |
|---|---|
| Entry | **Heart of the Mountain 7**, via **Dwarven Base Camp**. 2026: also a **Secret Railroad Pass** (forge: 1 Flawless Ruby Gemstone + 2 Refined Mithril + 8 Corleonite) |
| Location | Under/south of Dwarven Mines, ~(9, 121, 247) |
| Sub-areas | Dwarven Base Camp, **Grandpa Wolf's Cave**, **Great Glacite Lake**, Fossil Research Center (?) |
| Ores | Glacite, Umber, Tungsten, Mithril, Titanium, Hard Stone |
| Gemstones (exclusive) | **Onyx, Aquamarine, Citrine, Peridot** |
| Fairy souls | 2 |
| Recommended | HotM 8, 3,750+ Mining Speed, Divan armour, Silverfish pet, Titanium Drill DR-X555+ |

### 11.1 Cold (verified)
| Rule | Value |
|---|---|
| Mining Speed penalty | **−0.5% per Cold** |
| Movement Speed penalty | **−25% per 25 Cold** |
| Freeze | At **100 Cold** → frozen and returned to Dwarven Base Camp |
| Warnings | at 25 / 50 / 75 / 90 |
| Relief | Campfires / leaving (?) |

### 11.2 Mineshafts (verified spawn rules)
| Rule | Value |
|---|---|
| Spawn | **0.05% base (1 in 2,000 blocks)**, progressive counter |
| Counter cost per block | Mithril −2; Umber/Tungsten/Glacite −4; Gemstones/Titanium −8 |
| Portal | Open **30 s** |
| Contents | Instanced shaft themed by a gem/ore; **Frozen Corpses** (Lapis, Umber, Tungsten, Vanguard) opened with Tungsten/Umber/Skeleton keys → Glacite Powder, fossils, rare loot (?) |
| Types (?) | Named by gem (Topaz, Sapphire, Amethyst, Amber, Jade, Ruby, Onyx, Aquamarine, Citrine, Peridot, Titanium, Umber, Tungsten…); rare **Vanguard** shaft |

### 11.3 Mobs (?)
| Mob | Lv | HP | Notes |
|---|---|---|---|
| Glacite Walker | 60 | 40,000 | Tunnel mob |
| Glacite Bowman / Mage / Caver / Mutt / Brute | 60–100 | 50k–300k | Mineshaft guardians |

### 11.4 NPCs
| NPC | Role |
|---|---|
| Fossil Muncher (-26.5, 125, 299.5) | Daily fossil exchange → **25,000 Glacite Powder + 100 Fossil Essence** |
| Grandpa Wolf | Frozen Corpse milestones |
| Fossil excavator (?) | Excavate Suspicious Scrap → fossils |

---

## 12. Crystal Hollows

| Field | Value |
|---|---|
| Entry | **Mining XII + HotM 4**; **6 h pass 10,000 coins** (Gwendolyn); help **Dulin** for permanent access |
| Lobby lifecycle | Opens **Day 0**, locks to newcomers **Day 19**, forced shutdown **Day 35** |
| Size | ~620×620, y 31–188 |
| Block rule | Broken blocks become **air**; ore yields much higher than elsewhere |
| Gemstone Powder | Mainly from **treasure chests** that pop while mining |

### 12.1 Zones
| Zone (~ quadrant) | Gemstone | Structure | Crystal |
|---|---|---|---|
| **Jungle** (NW) | Amethyst | **Jungle Temple** (parkour/puzzle; Kalhuiki) | Amethyst |
| **Mithril Deposits** (NE) | Jade | **Mines of Divan** (Metal Detector hunt; 4 Keepers) | Jade |
| **Precursor Remnants** (SE) | Sapphire | **Lost Precursor City** (Automatons; Professor Robot) | Sapphire |
| **Goblin Holdout** (SW) | Amber | **Goblin Queen's Den** (needs Goblin Egg) | Amber |
| **Magma Fields** (bottom, y<64) | Topaz | **Khazad-dûm** (Bal) | Topaz |
| **Crystal Nucleus** (centre) | Ruby (everywhere) | Altar: place all 5 crystals → loot | — |
| **Fairy Grotto** (3 random) | **Jasper** | Hidden fairy caves | — |

### 12.2 Mobs (?)
| Mob | Zone | Lv | HP | Dmg | Drops |
|---|---|---|---|---|---|
| Automaton | Precursor | 100 | 15,000 | 1,000 | Robot parts (Electron Transmitter, FTX 3070, Robotron Reflector, Superlite Motor, Control Switch, Synthetic Heart) ~1–5% each |
| Sludge | Jungle | 5 / 10 / 100 | 5k / 10k / 25k | 200–800 | Sludge Juice |
| Yog | Magma Fields | 100 | 35,000 | 1,000 + fire | Yog armour pieces (rare) |
| Thyst | Jungle (from Amethyst) | 20 | few hits | 300 | — |
| Goblins (all kinds) | Goblin Holdout | 25–50 | 1.5k–5k | — | Goblin Eggs (Green/Red/Yellow/Blue) |
| Goblin Queen | Queen's Den | (?) | (?) | — | Amber Crystal |
| Team Treasurite (Grunt, Sebastian, Viper, Wendy) | Mithril Deposits | 50–100 | 10k–40k | 1,000 | Treasurite |
| Boss Corleone | Mithril Deposits (rare) | 150 | 1,000,000 | 2,000 | Corleonite (?), Treasurite |
| Key Guardian | Mines of Divan | 100 | (?) | — | key (?) |
| Kalhuiki Door Guardian | Jungle Temple | 100 | (?) | — | — |
| **Bal** | Khazad-dûm | 100 | hit-based, immune to most damage | lava | **Bal pet** (rare), Topaz Crystal |
| Butterfly | Fairy Grotto | 1 | 1 | 0 | (?) |
| Worm | anywhere deep | 5 | 5,000 | — | Worm Membrane |
| **Scatha** | rare Worm variant | 10 | 10,000 | — | **Scatha pet** |

### 12.3 NPCs
| NPC | Role |
|---|---|
| Professor Robot | 6 robot parts → Sapphire Crystal |
| Odawa | Jungle — Sludge Juice → Jungle Key (?) |
| Keepers ×4 | Mines of Divan — place Divan tools → Jade Crystal |
| Kalhuiki tribe | Jungle Temple |
| Dulin | Permanent access |

---

## 13. Spider's Den

| Field | Value |
|---|---|
| Entry | **Combat I**; launchpad from the **Graveyard**, portal, or Travel Scroll; `/warp spider`, `/warp nest`, `/warp arachne` |
| Layout (~) | ~300×300 dark **mountain** wrapped in cobwebs and gravel; low slopes = low-level spiders, a spiral path climbs to the **Top of the Nest** (Broodmother). Behind it: **Arachne's Burrow** (cave) and **Arachne's Sanctuary** (altar). Gravel quarry on a flank. Grandma's cottage at the base. Launchpads at the far side go to The End and the Crimson Isle. |
| Weather (verified) | **Rain every 40 min for 20 min** (+25 Fishing Speed); every **3rd** rain is a **Thunderstorm** that spawns **Toxic Rain Slimes** |
| Fairy souls | **19** |
| NPCs | 9 |

### 13.1 Sub-areas (verified)
| Sub-area | Purpose | NPCs |
|---|---|---|
| Spider Mound | Low-level spider slopes | — |
| Gravel Mines | Gravel/flint (vanilla break speed; Mining Speed does not apply) | Rick |
| Michael's Crib | Mob Types intro | Ike, Michael |
| Grandma's House | Bestiary unlock, quests | Bramass, Grandma Wolf, Shaggy |
| Archaeologist's Camp | Relics intro | Archaeologist |
| Top of the Nest | Broodmother, high-level spiders | — |
| Arachne's Burrow | Spider Slayer grind | — |
| Arachne's Sanctuary | Arachne altar | Spider Tamer |

### 13.2 Mobs (stats (?))
| Mob | Lv variants | HP | Dmg | XP | Drops | Where |
|---|---|---|---|---|---|---|
| Splitter Spider | 2 / 4 / 6 / 42 / 45 / 50 | 180 / 220 / 260 / 1,600 / 2,000 / 2,400 | 30–600 | 9–40 | String, Spider Eye; splits into 2 Silverfish on death | slopes / top |
| Silverfish (split) | 2 | 50 | 20 | 3 | — | spawned |
| Dasher Spider | 4 / 6 / 42 / 45 / 50 | 160 / 170 / 750 / 900 / 1,000 | 55–500 | 9–40 | String, Spider Eye; leaps | slopes / top |
| Weaver Spider | 3–6 / 42–50 | 160–1,100 | 40–400 | 9–35 | String; webs you | slopes |
| Voracious Spider | 10 / 42 | 1,000 / 1,800 | 100 / 400 | 25 / 40 | String, Spider Eye | slopes |
| Spider Jockey | 3 / 5 / 42 | 220 / 250 / 1,200 | 50 / 60 / 400 | 10–30 | Bone, String | slopes |
| Gravel Skeleton | 2 | 100 | 25 | 6 | Bone, Flint | Gravel Mines |
| Rain Slime | 8 | 300 | 60 | 15 | Slimeball | while raining |
| Toxic Rain Slime | (?) | (?) | (?) | — | Poison Sample (?) | thunderstorms only |
| Arachne's Keeper | 100 | 3,000 | 400 | 40 | String, **Arachne's Fragment** (rare) | Arachne's Burrow |
| Arachne's Brood | 100 | 4,000 | 500 | — | — | summoned by Arachne |
| **Broodmother** (zone boss) | 12 | **6,000** | 300 | 100 | spider loot stack; Tarantula-themed rare (?) | Top of Nest; scoreboard cycle Slain → Dormant → Soon → Awakening → Imminent → Alive (~10–15 min) |
| **Arachne** (summoned) | 300 (Fragments) / 500 (Calling) | 20,000 / 100,000 | 1,500 / 3,000 | 500 / 2,500 | Arachne's Belt / Cloak / Gloves / Necklace / Calling (equipment), Spider Talisman, Tarantula pet, Luxurious Spool | Arachne's Sanctuary; up to 4 players each place a summoning item |

### 13.3 NPCs
| NPC | Role / shop |
|---|---|
| Haymitch | Tutorial guidance |
| Shaggy | Arachne intro (shop) |
| Rick | **Promising Shovel for 2 Iron Ingots** |
| Spider Tamer | Essence shop (after Arachne) |
| Ike / Michael | Mob Types intro |
| Bramass / Grandma Wolf | Bestiary, quests |
| Archaeologist | Relics intro (hidden relics across the Den → reward) |

---

## 14. The End

| Field | Value |
|---|---|
| Entry | **Combat XII**; launchpad from Spider's Den (west side), portal, scroll; `/warp end`, `/warp drag`, `/warp void` |
| Layout (~) | ~400×400 floating end-stone archipelago. Spawn platform with the **Pearl Dealer**; obsidian pillars; a big basin = **Dragon's Nest** (altar with 8 eye frames, 6 crystal towers); a cave = **Zealot Bruiser Hideout**; a dark plaza = **Void Sepulture**; **Void Slate** (Draconic Altar). |
| Fairy souls | 12 (?) |

### 14.1 Sub-areas (verified)
| Location | Description | NPCs | Requirement |
|---|---|---|---|
| The End | Endermen, Endermites, Obsidian Defenders, Watchers | Guber, Lone Adventurer, Pearl Dealer | Combat XII |
| Dragon's Nest | Zealots, altar, dragon fights | Gregory | — |
| Zealot Bruiser Hideout | Summoning Eye farm | — | Combat XX |
| Void Sepulture | High-level Endermen (Voidlings) | — | **Combat XXV** |
| Void Slate | Draconic Altar, Dragon Essence shop | Tyzzo | — |

### 14.2 Mobs
| Mob | Lv | HP | Dmg | XP | Coins | Drops | Where |
|---|---|---|---|---|---|---|---|
| Enderman | 42 / 45 / 50 | 4,500 / 6,000 / 9,000 (?) | 500 / 600 / 700 (?) | 28 / 30 / 40 (?) | 6–10 | Ender Pearl 1–3, Enchanted Ender Pearl (rare), Ender Armor piece ~1% (?) | main |
| Endermite | 37 | 2,000 (?) | 400 (?) | 25 (?) | — | (?) | main |
| Nest Endermite | 50 | 2,200 (?) | 500 (?) | 30 (?) | — | — | Dragon's Nest |
| Obsidian Defender | 55 | 10,000 (?) | 200 (?) | 40 | 10 | Obsidian ×2–3 | main |
| Watcher | 55 | 9,500 (?) | 475 (?) | 40 | 10 | Ender Pearl; summons Endermen | main |
| **Zealot** (verified) | **55** | **13,000** | **1,250** | **40** | **15** | Ender Pearl ×2–9 (100%); **2%** spawn holding an **Ender Chest** → one of: Ench. Ender Pearl 40.65% / Ench. End Stone 24.39% / Ench. Obsidian 16.26% / Crystal Fragment 12.2% / Ench. Eye of Ender 6.5% | Dragon's Nest; **wave of 16 every 11 s** |
| Special Zealot | 55 | 2,000 (?) | 1,250 | 40 | — | **Summoning Eye 100%** — replaces **1/420** Zealot spawns | Dragon's Nest |
| Zealot Bruiser | 100 | 65,000 (?) | 2,500 (?) | 40 (?) | — | Summoning Eye **1/380** (verified) | Bruiser Hideout |
| Voidlings (Devotee / Radical / Extremist) | 100+ | 4M–10M (?) | 2,000–4,000 (?) | — | — | Null Sphere etc. (Voidgloom loot) | Void Sepulture |
| **Endstone Protector** (world boss) | 100 | **5,000,000** (?) | 1,500 (?) | — | — | **Tier Boost Core** (rare), Golem pet, Crystal Fragments | Spawns when the golem stage meter (driven by players mining End Stone) reaches stage 5; ~20 s warning |
| **Ender Dragon** | — | 14.3 | — | — | — | 14.4 | Dragon's Nest |

### 14.3 Ender Dragon variants (verified)
| Variant | HP | Dmg | Trait | Spawn chance |
|---|---|---|---|---|
| Young | 7,500,000 | 1,100 | Fastest (225% speed), lowest HP | 16% |
| Protector | 9,000,000 | 1,100 | Reduced damage taken | 16% |
| Wise | 9,000,000 | 2,200 | Stronger magic attacks | 16% |
| Unstable | 9,000,000 | 1,100 | Lightning/explosive | 16% |
| Strong | 9,000,000 | 1,100 | Amplified attacks | 16% |
| Old | 15,000,000 | 1,100 | Most HP, slowest | 16% |
| Superior | 12,000,000 | 1,650 | All abilities | **4%** |

- **Summon:** 8 Summoning Eyes on the altar (any mix of players).
- **6 Ender Crystals** heal **+0.3% HP/s** while linked; breaking one gives Crystal Fragments + credit.
- **Attacks (True Damage):** Rush 550 · Fireball 200/hit · Lightning 200; all **+50% below 50% HP**.

### 14.4 Dragon loot — "loot quality" weights (?)
Quality = **100 per eye placed** + damage-rank bonus (1st +200, 2nd +175, 3rd +150 …) + damage share. Drops are bought highest-weight-first with that quality.

| Drop | Weight (?) |
|---|---|
| Ender Dragon pet | 450 (plus its own % roll) |
| Aspect of the Dragons | 450 |
| Dragon Chestplate (type-specific) | 410 |
| Dragon Leggings | 360 |
| Dragon Helmet | 295 |
| Dragon Boots | 290 |
| Dragon Claw / Horn / Scale (variant specials) | 160–200 |
| Dragon Fragments (10 → armour piece) | 22 each |

### 14.5 NPCs
| NPC | Location | Role |
|---|---|---|
| Pearl Dealer | End spawn | End shop: Stonk, Silent Pearl, Ender Pearls … |
| Gregory | Dragon's Nest | **Decent Bow** and **Flint Arrows** |
| Lone Adventurer | The End | **Void Sword**; kill-Endermen tasks |
| Guber | The End | flavour / quests (?) |
| Tyzzo | Void Slate | **Dragon Essence** perks; Draconic Altar |

---

## 15. Crimson Isle

| Field | Value |
|---|---|
| Entry | **Combat XXII**; launch pad in Spider's Den; scrolls to Kuudra Passage, Smoldering Tomb, Dragontail, Scarleton, Crimson Isle |
| Layout (~) | ~800×800 red nether island under a smoky sky. Arrive at the **Stronghold** (black fortress). Two rival towns on opposite sides — **Dragontail** (Barbarians; rough stone/wood, dragon skulls) and **Scarleton** (Mages; red-gold spires, throne room). Between: **Crimson Fields** (path hub), **Burning Desert** (red dunes), **Mystic Marsh** (mycelium swamp), **Blazing Volcano** (lava lake), **Magma Chamber** (underground arena), **Smoldering Tomb**, **Ruins of Ashfang**, **The Dukedom**, **Courtyard**, **Dojo**, Kuudra Passage. |
| Fairy souls | 29 (?) |

### 15.1 Zones (verified)
| Zone | Description |
|---|---|
| Stronghold | Entrance; **Bladesoul** |
| Dragontail | Barbarian town: shops, bank, bazaar |
| Scarleton | Mage town: throne room, services |
| Mystic Marsh | Mushroom Bulls, mycelium |
| Burning Desert | Flaming Spiders, Magma Cube Riders |
| Blazing Volcano | Lava fishing; **erupts every 2–2.5 h** |
| Magma Chamber | **Magma Boss** |
| Crimson Fields | Magma Cubes; path hub |
| Smoldering Tomb | Blaze Slayer area |
| Ruins of Ashfang | **Ashfang** |
| The Dukedom | **Barbarian Duke X** |
| Courtyard | **Mage Outlaw** |
| Kuudra's Hollow | Kuudra instance |
| Dojo | Master Tao |

### 15.2 Mini-bosses (verified)
| Boss | Location | HP | Respawn |
|---|---|---|---|
| **Bladesoul** | Stronghold | **50,000,000** | 2 min |
| **Magma Boss** | Magma Chamber | **200,000,000** | 5 min |
| **Mage Outlaw** | Courtyard / Scarleton | **70,000,000** | 2 min |
| **Barbarian Duke X** | The Dukedom | **70,000,000** | 2 min |
| **Ashfang** | Ruins of Ashfang | **50,000,000** | 2 min |
Drops (?): faction reputation, themed armour/weapon pieces, essences. All count toward faction daily quests.

### 15.3 Regular mobs (?)
| Mob | Lv | HP | Where |
|---|---|---|---|
| Magma Cube | 75 | 75,000 | Crimson Fields |
| Magma Cube Rider | 90 | 300,000 | Burning Desert |
| Flaming Spider | 80 | 50,000 | Burning Desert |
| Blaze | 70 | 60,000 | Smoldering Tomb |
| Smoldering Blaze | 95 | 2,500,000 | Smoldering Tomb |
| Millenia-Aged Blaze | 110 | 30,000,000 | Smoldering Tomb (rare) |
| Wither Skeleton | 70 | 50,000 | Stronghold |
| Wither Spectre | 70 | 120,000 | Stronghold |
| Ghast | 85 | 150,000 | open sky |
| Mushroom Bull | 80 | 150,000 | Mystic Marsh |
| Faction mobs (Fire Mage, Kada Knight, Barbarian…) | 75–90 | 50k–500k | faction zones |
| Vanquisher | 100 | 42,000,000 | rare spawn on kills |

### 15.4 Lava fishing
- **Trophy Fish** (Odger): Sulphur Skitter, Obfuscated 1/2/3, Steaming-Hot Flounder, Gusher, Blobfish, Slugfish, Flyfish, Lava Horse, Mana Ray, Volcanic Stonefish, Vanille, Skeleton Fish, Moldfin, Soul Fish, Karate Fish, Golden Fish — Bronze/Silver/Gold/Diamond.
- **Lava sea creatures (?)**: Magma Slug, Moogma, Lava Leech, Pyroclastic Worm, Lava Flame, Fire Eel, Taurus, Plhlegblast, Thunder (~35M HP), **Lord Jawbus** (~100M HP), Fiery Scuttler, Ragnarok (eruptions).

### 15.5 Factions
| Faction | Leader | Town | Rep sources | Rewards |
|---|---|---|---|---|
| **Barbarians** | Chief Scorn | Dragontail | Daily quests, Barbarian Duke X | Faction shop, Kuudra Keys |
| **Mages** | Queen Nyx | Scarleton | Daily quests, Mage Outlaw | Faction shop, Kuudra Keys |
Rep tiers (?) line up with Kuudra: 1,000 / 3,000 / 7,000 / 12,000. Picking one faction makes the other hostile-ish.

### 15.6 Kuudra (verified)
| Tier | Requirement | SkyBlock XP | Wave interval |
|---|---|---|---|
| Basic | Elle introduction | 20 | 35 s |
| Hot | Main quest + **1,000** rep | 40 | 30 s |
| Burning | **3,000** rep + Hot | 60 | 25 s |
| Fiery | **7,000** rep + Burning | 80 | 20 s |
| Infernal | **12,000** rep + Fiery | 100 | 15 s |
Phases: supply crates → build the **ballista** → **fuel cells** → cannonballs; Burning+ adds an inside-the-stomach phase (break orbs with pickaxes); Infernal adds a final phase at 25% HP in a lower lava arena where deaths are final unless revived. Keys: **Nether Stars** at faction **Emissaries** + coins (?).

### 15.7 Dojo (Master Tao)
Seven trials (?): Force, Stamina, Mastery, Discipline, Swiftness, Control, Tenacity → belts **White → Yellow → Green → Blue → Brown → Black**.

### 15.8 Crimson NPCs (?)
| NPC | Role |
|---|---|
| Elle | Kuudra intro |
| Chief Scorn / Queen Nyx | Faction leaders |
| Emissaries | Kuudra Keys for Nether Stars |
| Odger | Trophy fish |
| Master Tao | Dojo |
| Town Bank / Bazaar NPCs | Services |

---

## 16. Jerry's Workshop (Winter Island)

| Field | Value |
|---|---|
| Entry | **Late Winter** each SkyBlock year (?); portal in the Hub; `/warp winter` |
| Layout (~) | Snowy toy-workshop village with lights; frozen **Jerry Pond** and **Reflective Pond** (winter fishing), **Mount Jerry** with the **Glacial Cave**, **Hot Springs**, **Sherry's Showroom**, **Einary's Emporium**, **Terry's/Gary's Shacks**. |
| Fairy souls | 5 (?) |

### 16.1 Season of Jerry (?)
Late Winter 24–26: waves of gift-stealing mobs attack the workshop; defend Jerry → **White / Green / Red Gifts** (Red = rarest loot).

### 16.2 Winter sea creatures / mobs (?)
| Creature | Lv | HP |
|---|---|---|
| Frozen Steve | 7 | 700 |
| Frosty the Snowman | 13 | 7,500 |
| Grinch | 21 | 2,000 |
| Nutcracker | 50 | 4,000 |
| Yeti | 175 | 2,000,000 (Baby Yeti pet) |
| Reindrake | 100 | 2,500,000 |

### 16.3 NPCs (?)
| NPC | Role |
|---|---|
| Jerry | Gifts / event |
| Einary | Einary's Emporium (gift-currency shop) |
| Sherry | Cosmetic showroom |
| Gary / Terry | Flavour/quests |

---

## 17. Dungeon Hub & the Catacombs (placement only)

| Field | Value |
|---|---|
| Entry | Hub Catacombs Entrance / `/warp dhub`; historically **Combat XV** to start runs (?) |
| Layout (~) | Gloomy black-stone courtyard with soul lanterns around a big portal hall; Mort at the gate, Croesus' vault, shop stalls, leaderboard statues. |
| Fairy souls | 7 (?) |

| Floor | Boss | Catacombs Lv gate (?) | Master Mode gate (?) |
|---|---|---|---|
| Entrance | The Watcher | 0 | — |
| F1 | Bonzo | 0 | M1: 24 |
| F2 | Scarf | 1 | M2: 26 |
| F3 | The Professor (+4 Guardians) | 3 | M3: 28 |
| F4 | Thorn | 5 | M4: 30 |
| F5 | Livid | 9 | M5: 32 |
| F6 | Sadan (+ Giants) | 14 | M6: 34 |
| F7 | Maxor → Storm → Goldor → Necron | 19 | M7: 36 (+ Wither King) |

| NPC | Role (?) |
|---|---|
| Mort | Start runs, classes |
| Ophelia | Consumables: Superboom TNT, Spirit Leap, Decoy, healing potions |
| Malik | Essence / star upgrades (?) |
| Croesus | Claim reward chests |

---

## 18. The Garden

| Field | Value |
|---|---|
| Entry | **SkyBlock Level 5** + **Sam**; private per profile; `/warp garden` |
| Layout (verified) | **24 plots in a 5×5 grid**, barn in the centre; **each plot 96×96** |
| Plot unlock | Tiers gated by Garden level **1/3/5/7**; **1 Compost** (plot 1) → **15 Compost Bundles** (plot 24); cumulative **12,103 Compost-equivalent** |
| Currencies | **Copper** (crop upgrades 5 → 4,000 per tier), **Compost**, Jacob's medals |
| Visitors | NPC visitors arrive over time and request items → Copper, Garden XP, rare rewards |

### 18.1 Crops by Garden level (verified)
| Garden Lv | Crop |
|---|---|
| 1 | Wheat |
| 2 | Carrot |
| 3 | Potato |
| 4 | Pumpkin |
| 5 | Sugar Cane |
| 6 | Melon |
| 7 | Cactus |
| 8 | Cocoa Beans |
| 9 | Mushroom |
| 10 | Nether Wart |
| 11–12 | Sunflower, Moonflower, Wild Rose |

### 18.2 Pests (14, verified)
Fly, Cricket, Locust, Rat, Field Mouse, Mosquito, Earthworm, Mite, Moth, Slug, Beetle, Firefly, Dragonfly, Praying Mantis. Spawn while breaking crops; killed with a Vacuum; too many alive applies a crop-yield penalty (?).

### 18.3 NPCs (verified names)
| NPC | Role |
|---|---|
| Sam | Unlocks the Garden; scythes for plot clearing |
| Jacob | Contests / crop milestones |
| Anita | Medal shop |
| Jeff, Carpenter, Feast Chef Ted | Garden features (?) |
| Pesthunter Phillip & Pamela | Pests, vacuums |

---

## 19. The Rift

| Field | Value |
|---|---|
| Entry (verified) | Wizard Portal (Wilderness) + a **Dimensional Infusion**: free 1 per **4 h** (store 3), or **32 Grand Experience Bottles** or **200 Bits** at the Wizard, a Rift Prism, or the Tal Ker Garden visitor |
| Rift Time | Base **480 s (8 min)**; drains while inside; paused in Wizard Tower (not ground floor), Rift Gallery, Mirrorverse; at 0 you are ejected |
| Currency | **Motes**. Orbs: **+25 Motes and +2 s** (Enigma's Crib: 10 Motes, no time) |
| Layout | A twisted **mirror of the Hub**, rotten and rearranged; rings open as Rift Time grows (Timecharms) |
| Souls | **Enigma Souls** |

### 19.1 Areas and gates (verified)
| Area | Gate |
|---|---|
| Wyld Woods, Wizard Tower (Rift), Broken Cage, Enigma's Crib, Pumpgrotto, Shifted Tavern, The Bastion, Rift Gallery | start |
| Black Lagoon (Lagoon Cave, Lagoon Hut, **Leeches Lair**) | **4:00** Rift Time |
| West Village (Cake House, Around Colosseum, Dolphin Trainer, Infested House; Dreadfarm, Great Beanstalk) | **Supreme Timecharm + 3:00** |
| Mirrorverse | **3:00** |
| Village Plaza (Lonely Terrace, Murder House, Barry Center/HQ, Empty Bank, Half-Eaten Cave, Taylor's, "Your" Island, Book in a Book, Déjà Vu Alley) | **Chicken N Egg Timecharm + "mrahcemiT esrevrorriM" + 8:00** |
| Living Cave (Living Stillness) | **SkyBlock Citizen Timecharm + 5:00** |
| Colosseum (Rift) | **Living Timecharm + 15:00** (time drains at half speed inside) |
| Barrier Street (Photon Pathway, Stillgore Château, Oubliette, Fairylosophy Tower) | **Globulate Timecharm + 10:00** |
| The Mountaintop (Continuum, Time Chamber, Time-Torn Isles, Rose's End, Walk of Fame) | **Vampiric Timecharm** |

### 19.2 Rift bosses (?)
| Mob | HP (Rift scale) | Where |
|---|---|---|
| Leech Supreme | ~1,000 (?) | Leeches Lair |
| Bacte | ~5,000 (?) | Rift Colosseum |
| Riftstalker Bloodfiend (Vampire Slayer) | hundreds–thousands (?) | Stillgore Château; damages your max health |

---

## 20. Backwater Bayou (Fishing island 1)

| Field | Value |
|---|---|
| Entry (verified) | **Fishing V** + **Captain Baha** at the Hub Fishing Outpost → Ship Navigator |
| Layout (~) | Polluted swamp: brown channels, mangroves, rickety boardwalks, a **junkyard** with pipes and smokestacks, underwater tunnels; bubbling **Hotspots**. |
| Mechanics (verified) | **Hotspots** (bobber inside → buffs + special creatures), **Junk fishing** (equip **Junk Sinker** → Rusty Coins, Busted Belt Buckles, Old Leather Boots, Moby-Ducks instead of treasure), **Rod Parts** (Roddy) |
| Completion | Jonesy's **38 chapter tasks** → **Pearl of the Swamp** (+1 Treasure Chance permanently) |

### 20.1 Sea creatures (verified)
| Creature | Rarity | Fishing req | Weight | Chance | HP (?) |
|---|---|---|---|---|---|
| Trash Gobbler | Common | 5 | 5,000 | 50% | ~5k |
| Dumpster Diver | Uncommon | 5 | 2,875 | 28.75% | ~10k |
| Banshee | Rare | 10 | 1,500 | 15% | ~40k |
| Bayou Sludge | Epic | 15 | 500 | 5% | ~250k |
| Alligator | Legendary | 20 | 100 | 1% | ~1.5M |
| Titanoboa | Mythic | 30 | 25 | 0.25% | ~20M |

### 20.2 NPCs (verified)
| NPC | Role |
|---|---|
| Junker Joel | Junk → upgrades; **Treasure Bait**, rod improvements |
| Roddy | Applies Rod Parts |
| Jonesy | 38 chapter tasks |
| Hattie | Explains Hotspots |
| Carly | Shows sea creatures |
| Captain Baha | Ship menu |
| Researcher Gilbert | Joins after **Fishing X** → unlocks Lotus Atoll |

---

## 21. Lotus Atoll (Fishing island 2, 2026)

| Field | Value |
|---|---|
| Entry (verified) | **Fishing X** + **Researcher Gilbert** (Bayou) → Ship Navigator. Alpha **6 May 2026 (v0.24.5)** |
| Layout (~) | Tropical ring atoll of lily pads and lotus flowers round a lagoon; a highland plateau; caves. |
| Sub-areas | **Lotus Eater's Cave** (entered by donating **Frogcoins**), **Lotus Highlands** (Highland Frogs; launcher NPC), **Tewtil Tunnel** |
| Mechanics | **Trophy Frogs** (Bronze/Silver/Gold/Diamond) → Lotus currency; **Exploding Lily Pads** (push pads together to size 8 → explosion → rewards + creatures); **Wormholes** visible with **Froggles** (catch Reality Hoppers); rain every **40 min for 20 min** (+25 Fishing Speed); Lotus Collection unlocks Froggles |
| Fairy souls | 6 |

| Sea creature (all Lv 10) | Rarity |
|---|---|
| Atoll Croaker | Common |
| Lotus Guardian | Uncommon |
| gorF | Rare |
| Drowned Captain | Epic |
| Puddle Jumper | Legendary |
| Frog Prince | Mythic |

Critters (nets): Lotusfish, Lotum, Tewtil, Flipflopper, Seashine.

| NPC | Role |
|---|---|
| Researcher Ribery | Trophy Frogs → Lotus currency |
| The Lotus Eater | **Lily Pad Minion** upgrade stones |
| Launcher NPC | To Lotus Highlands |

---

## 22. Galatea (Foraging island 2) — overview

| Field | Value |
|---|---|
| What | SkyBlock's second Foraging island group, introducing **Hunting**, **Attribute Shards**, **Heart of the Forest (HotF)** and multi-step **tree styles**. Released mid-2025 (Foraging update; changelog 23 Jun 2025 (?)); Torrhus Canyon added later (2026 (?)) |
| Islands | **Moonglade Marsh** (1st, released) · **Torrhus Canyon** (2nd, released) · **Lunarise** (3rd, announced, **unreleased**) |
| Entry | **Foraging XII** for Moonglade Marsh; **+ Heart of the Forest IV** for Torrhus Canyon |
| Travel scrolls | Travel Scroll to Galatea, to Murkwater Loch, to Torrhus Canyon, to Torrhus Springs, to Critter Safari Entrance |
| Fairy souls | **12 in Moonglade Marsh + 12 in Torrhus Canyon = 24** (caves, waterfalls, hidden spots) |

### 22.1 Galatea core mechanics (verified)
| Mechanic | Rules |
|---|---|
| **Heart of the Forest** | Skill tree (like HotM) granting Foraging + Hunting perks. HotF XP from **Tree Gifts** and **Agatha's Contests**; each HotF level gives a **Token of the Forest** to spend on perks. Unlocked by **Hina** |
| **Tree styles & toughness** | **Fig Tree** — toughness **10** — cut **Trunk → Branches**. **Mangrove Tree** — toughness **50** — **Branches → Trunk → Roots**. **Helix Tree** (Torrhus) — toughness **150** — **Beige Logs → Red Logs**. Cutting out of order is slower/invalid (?) |
| **Tree Gifts** | Reward bundle when a big tree is fully harvested; scales with participation; **33% participation = max reward** (encourages co-op felling) |
| **Agatha's Contests** | Timed foraging competitions at Murkwater Loch → rewards + HotF XP (the second island's equivalent is **Miria's** contests, which also award **Safari Tickets**) |
| **Hunting** | New skill: catch **critters** with **Lassos**, **Huntraps**, fishing nets and interactions. Critters drop **Attribute Shards** |
| **Attribute Shards** | Collectibles that are **syphoned** (applied for attribute levels), **fused** (combine into new shards) and **salvaged** at dedicated facilities |
| **Forest Essence** | Currency spent at **Rosemary** (Murkwater Outpost) for perks |

---

## 23. Galatea — Moonglade Marsh

| Field | Value |
|---|---|
| Entry | **Foraging XII** |
| Layout (~) | Misty wetland around a big central lake, **Murkwater Loch**. A treetop/plank village **Tangleburg** (bank, library) sits in giant figs; an **Evergreen Plateau** of conifers rises on one side; **North / South / West Reaches** are marshy outskirts. Underwater: **Murkwater Depths** and the **Dragon's Lair** (Jade Dragon). Ruins scattered: **Ancient Ruins**, **Forest Temple**, **Wyrmgrove Tomb**. ~33 named sub-locations. |

### 23.1 Sub-areas (verified names, layout (~))
| Sub-area | Feel / content |
|---|---|
| Murkwater Loch | Central lake, hub; Agatha's contests |
| Murkwater Outpost | Rosemary (Forest Essence perks) |
| Murkwater Depths | Underwater zone below the Loch |
| Dragon's Lair (Galatea) | Underwater lair; **Jade Dragon** sells the Jade Dragon pet |
| Tangleburg | Village; David Hunterborough (hunting), Banker Bonsai |
| Tangleburg's Path | Hina (unlocks HotF) |
| Tangleburg Library | Library services |
| Evergreen Plateau | Conifer highland |
| North / South / West Reaches | Marsh outskirts, critters |
| Ancient Ruins, Forest Temple, Wyrmgrove Tomb | Ruins / lore / puzzles |

### 23.2 Mobs (names verified; stats not published on the location page)
| Mob | Type (?) | Notes |
|---|---|---|
| Chillblade | Stray-type melee | (?) |
| Chillshot | Stray-type archer | (?) |
| Hydrospear | Drowned-type, trident | (?) |
| Seacurse | Drowned/phantom-type | (?) |
| Tidetot | small water mob | (?) |
| Stridersurfer | lava creature | also a Rare **lava** sea creature |
| Critters (~30) | Hunting targets | lasso/huntrap/net; drop shards |

### 23.3 Sea creatures (verified names/rarity)
| Creature | Rarity |
|---|---|
| Bogged | Common |
| Wetwing | Uncommon |
| Tadgang | Rare |
| Ent | Epic |
| The Loch Emperor | Legendary |
| Nessie | Mythic |
| Stridersurfer | Rare (lava only) |

### 23.4 NPCs (verified roles; 31–32 total)
| NPC | Where | Role / shop |
|---|---|---|
| Agatha | Murkwater Loch | Hosts contests; sells foraging items |
| Alan | (?) | Sells **Worn Huntaxe – Genesis** and **Sharpened Huntaxe – Dominus** |
| Albert | (?) | Sells **Pocket Black Holes** |
| Amaury | (?) | Sells axes and **Canopy Armor** |
| Auryon | (?) | Explains hunting |
| Banker Bonsai | Tangleburg | Bank |
| Coral | (?) | Rewards Fishing XP + SkyBlock XP |
| David Hunterborough | Tangleburg | Hunting lessons |
| Hina | Tangleburg's Path | Unlocks **Heart of the Forest** |
| Jade Dragon | Dragon's Lair | Sells **Jade Dragon pet** |
| Rosemary | Murkwater Outpost | Forest Essence perks |

---

## 24. Galatea — Torrhus Canyon

| Field | Value |
|---|---|
| Entry | **Foraging XII + Heart of the Forest IV**, from Moonglade Marsh |
| Layout (~) | A sandstone canyon: **Torrhus Heights** (upper plateau of Helix Trees and honey hives) descends by the **Spring Path** to **Torrhus Springs**, a deep spring pool with two depth bands (**Spring Shallows** and **Spring Depths** — Depths need Respiration + Pressure Resistance). Under the canyon: **Ant's Cave**. Also a sealed **Desert Temple** (puzzle), **Hotspot Haven** (fishing spot), and the **Critter Safari Entrance**. |
| Completion | **7 chapters, 63 tasks** → Foraging XP, **Desert Whispers** currency, **Safari Essence** |
| NPCs | 21 |

### 24.1 Zones (verified)
| Zone | Content |
|---|---|
| Torrhus Heights | Helix Trees (toughness 150), honey hives (Honeycomb), hunting |
| Spring Path | Connector down to the water |
| Torrhus Springs | Main fishing + diving location |
| Spring Shallows | Upper water band |
| Spring Depths | Lower band; Respiration + Pressure Resistance required |
| Ant's Cave | Underground pest-hunting |
| Hotspot Haven | Fishing hotspot area |
| Desert Temple | Puzzle-locked structure |
| Critter Safari Entrance | Gate to Critter Safari |

### 24.2 Fauna (verified examples)
31+ mobs/critters incl. **Beeheemoth, Bunbun, Mountain Goat, Pangolin, Water Snake**; sea creatures from **Haggard** (Common) to **Giant Isopod** (Mythic). Over 30 catchable critters with different capture methods.

### 24.3 NPCs (verified)
| NPC | Role |
|---|---|
| Helia | Quest progression (chapters) |
| Miria | Contests (award Safari Tickets) |
| Andrea | Safari milestones |
| Archie | Perks shop (Safari Essence) |
| Ant NPC | Minion upgrades |

---

## 25. Critter Safari (instanced hunting)

| Field | Value |
|---|---|
| Entry | **Hunting XV** + a **Safari Ticket** (from Miria's contests) |
| Tickets (verified) | **Basic** 32 capsules · **Economy** 40 capsules, +10% Safari Essence, +5 Hunting Wisdom · **Premium** 48, +25%, +15 · **First-Class** 56, +50%, +25, + bonus quest item |
| Biomes (verified) | **Cavern** (9 critters) · **Forest** (9; Birdfeeder mechanism) · **Haunted** (mansion; 10) · **Icy** (9; uses the Glacite **Cold** mechanic, reduced near campfires) |
| Critters | **37**, Common → Legendary; some hide, some need feeding, some spawn conditionally. Captured with **capsules** |
| Rewards | Shards, Hunting XP, **Safari Essence**; **Sparkling** variants **1/4,096** → **10× rewards** + Rainbow Feathers |
| Collection | **Critterdex** (introduced by "Hunter" Tobias) |
| Gear | **Safari Belt** gains attributes from successful biome hunts |
| Secret | Find **7 hidden bells** → cutscene → **Miracle Chance** |
| NPCs | 13 — mostly Hunter NPCs selling Attribute Shards; Archie (essence perks) |

---

## 26. Dark Auction

| Field | Value |
|---|---|
| Entry | **≥400,000 coins** in purse (2026 wiki); door in the Hub (Wilderness side) opens for the event; `/warp da` scroll |
| Schedule (?) | Every real hour at **:55**; the room opens a few minutes before |
| Host | **Sirius** |
| Format (?) | ~3 lots per DA, Dutch/English hybrid: players bid in the dark room; winner pays coins; unsold lots vanish |
| Lot pool (?) | Midas' Sword, Midas Staff, Hegemony Artifact, high-tier Enchanted Books, Recombobulator-tier items, rare pets/skins, "Sirius" specials |
| Implementation | Treat as a scheduled instanced room with a lot table `{item, minBid, weight}`; the purse gate is the only entry rule |

---

## 27. Calendar & world events (affect every island)

SkyBlock time (?) : **1 SB day = 20 real minutes**; **1 SB month = 31 SB days ≈ 10 h 20 m**; **1 SB year = 12 months ≈ 124 h (5 d 4 h)**. Months: Early/Mid/Late × Spring/Summer/Autumn/Winter.

| Event | When (?) | Where | What |
|---|---|---|---|
| Jacob's Farming Contest | every 3 SB days (1 h real), 20 min | Barn/Garden | 3 crops per contest; brackets → medals |
| Dark Auction | hourly :55 | Hub | Rare lots |
| Spooky Festival | Late Autumn 29–31 | all islands | Candy-dropping spooky mobs; Fear Mongerer shop |
| Season of Jerry | Late Winter 24–26 | Jerry's Workshop | Defend the workshop; gifts |
| New Year Celebration | Late Winter 29–31 | Hub | Free New Year Cake (Baker) |
| Traveling Zoo | Early Summer / Early Winter | Hub | Oringo sells pets |
| Election | yearly | Hub Election Room | Mayor (perks) + Minister |
| Mayor events | per mayor | varies | Diana → Mythological Ritual; Marina → Fishing Festival (sharks); Cole → Mining Fiesta; Foxy → Carnival / extra events; Derpy etc. |
| Hoppity's Hunt | Spring | all islands | Chocolate eggs |
| Dwarven Mines events | every 20 min | Dwarven Mines / CH | see 10.4 |
| Spider's Den rain/thunder | every 40 min (20 min) | Spider's Den | Rain Slimes, Toxic Rain Slimes |
| Lotus Atoll rain | every 40 min (20 min) | Lotus Atoll | +25 Fishing Speed |
| Blazing Volcano eruption | every 2–2.5 h | Crimson Isle | Ragnarok etc. |
| Broodmother / Endstone Protector / Magma Boss / Crimson mini-bosses | timers (see their sections) | — | World bosses |

---

## 28. Fairy souls per island (exploration reward)

| Island | Souls |
|---|---|
| Hub | **80** (2026) |
| Spider's Den | **19** |
| Mushroom Desert | **13** |
| The Park | **12** |
| Moonglade Marsh | **12** |
| Torrhus Canyon | **12** |
| Lotus Atoll | **6** |
| Glacite Tunnels | **2** |
| Gold Mine | 12 (?) |
| Deep Caverns | 21 (?) |
| Dwarven Mines | 11 (?) |
| The End | 12 (?) |
| Crimson Isle | 29 (?) |
| The Barn | 7 (?) |
| Dungeon Hub | 7 (?) |
| Jerry's Workshop | 5 (?) |
| Garden | 1 (?) |
| Rift | Enigma Souls instead (separate) |

**Tia the Fairy** trades every 5 souls for permanent +HP/+Defense/+Strength/+Speed (and Fairy Exchange rewards).

---

## 29. Removed / legacy locations (do not build, but know why)

| Location | Status |
|---|---|
| Blazing Fortress | Removed; replaced by **Crimson Isle** (2022) |
| Floating Islands | Removed |
| Kuudra's End | Removed (old Kuudra) |
| Pre-2026 Hub areas (old Forest, Weaponsmith Zone, old Fisherman's Hut placement) | Superseded by the 2026 districts |
| "Winter Island" (name) | Now Jerry's Workshop |

---

## 30. How it connects

### 30.1 By skill — the five "spines"

| Spine | Path (gate) |
|---|---|
| **Mining** | Hub Mining District → Coal Mine → **Gold Mine** → **Deep Caverns** (Mining V) → layers 1–6 → Rhys → **Dwarven Mines** (Mining XII) → Commissions/HotM → **Crystal Hollows** (HotM 4 + pass) → **Glacite Tunnels** (HotM 7 + Railroad Pass) → **Mineshafts** |
| **Combat** | Hub Graveyard (Lv1 zombies) → Crypts (Lv30 ghouls) → **Spider's Den** (Combat I) → **The End** (Combat XII) → Bruiser Hideout (XX) → **Crimson Isle** (XXII) → **Void Sepulture** (XXV) → **Kuudra** tiers (rep) |
| **Farming** | Hub Farm → **The Barn** → **Mushroom Desert** (Farming V; Trevor) → **The Garden** (SB Lv5; plots, pests, visitors, contests) |
| **Foraging / Hunting** | Hub Foraging Camp (Lumber Jack) → **The Park** (Foraging I–V forests) → **Galatea: Moonglade Marsh** (Foraging XII; HotF, Hunting) → **Torrhus Canyon** (HotF IV) → **Critter Safari** (Hunting XV) → (Lunarise, future) |
| **Fishing** | Hub Fishing Outpost → any water → **Backwater Bayou** (Fishing V) → **Lotus Atoll** (Fishing X) ; side branches: **Crimson Isle lava**, **Jerry's Workshop** winter, **Galatea** springs |
| **Dungeons** | Hub Catacombs Entrance → **Dungeon Hub** → Entrance → F1…F7 → M1…M7 |
| **Rift** | Wizard Portal → Rift (any level; time-gated internally) |

### 30.2 Cross-island item dependencies (what pulls players between places)

| Item / resource | Made / found in | Needed in |
|---|---|---|
| Summoning Eyes | The End (Zealots / Bruisers) | Dragon's Nest (dragons) |
| Dragon armour / fragments | Dragon's Nest | Combat everywhere |
| Mithril / Titanium / Refined Mithril | Dwarven Mines (+ Forge) | Forge recipes, Glacite pass, drills |
| Flawless Ruby + Corleonite | Crystal Hollows (Ruby gems; Boss Corleone) | **Secret Railroad Pass** for Glacite Tunnels |
| Robot parts / Divan tools / Goblin Egg / Jungle Key | Crystal Hollows zones | Crystal Nucleus |
| Nether Stars + faction rep | Crimson Isle | Kuudra Keys |
| Pelts | Mushroom Desert (Trevor) | Talbot's shop |
| Compost / Copper | Garden | Plot unlocks, crop upgrades |
| Jacob's medals | Contests (Barn/Garden) | Anita's shop |
| Dimensional Infusion (Grand XP bottles / Bits) | Hub Wizard | Rift entry |
| Attribute Shards | Galatea, Critter Safari, sea creatures | Attribute system (syphon/fuse) |
| Rod Parts / Treasure Bait / junk | Backwater Bayou | Fishing everywhere |
| Fossils / Glacite Powder | Glacite Tunnels / Mineshafts | HotM, fossil pets |
| Arachne's Calling/Fragments | Spider's Den (Keepers) | Arachne summon |
| Enchanted End Stone etc. | Endstone Protector / Zealots | crafting |

### 30.3 Physical adjacency (what borders what — for seamless clone worlds)

| Edge | Connection type |
|---|---|
| Private Island ↔ Hub | portal |
| Hub Village ↔ every Hub district | walkable roads |
| Hub Farm ↔ The Barn | walkable road / portal |
| The Barn ↔ Mushroom Desert | warp pad (Farming V) |
| Hub Foraging Camp ↔ The Park | path / portal (Lumber Jack quest) |
| The Park forests | launchpads, each Foraging-gated |
| The Park ↔ Galatea | Foraging XII; travel (?) |
| Moonglade Marsh ↔ Torrhus Canyon | walk/portal (HotF IV) |
| Torrhus Canyon ↔ Critter Safari | entrance (ticket) |
| Hub Mining District ↔ Coal Mine ↔ Gold Mine | walkable tunnels |
| Gold Mine ↔ Deep Caverns | pit / stairs |
| Deep Caverns layers | stairs + lift |
| Obsidian Sanctuary ↔ Dwarven Mines | minecart/tunnel (Mining XII + Rhys) |
| Dwarven Mines ↔ Glacite Tunnels | Dwarven Base Camp door |
| Dwarven Mines ↔ Crystal Hollows | Gwendolyn's gate (pass) |
| Glacite Tunnels ↔ Mineshafts | temporary portal |
| Hub Graveyard ↔ Spider's Den | launchpad |
| Spider's Den ↔ The End | launchpad |
| Spider's Den ↔ Crimson Isle | launchpad |
| Crimson Isle ↔ Kuudra's Hollow | Kuudra Passage |
| Hub Catacombs Entrance ↔ Dungeon Hub | portal |
| Hub Fishing Outpost ↔ Bayou ↔ Lotus Atoll | ship |
| Hub Wilderness ↔ Rift | Wizard Portal |
| Hub ↔ Jerry's Workshop | seasonal portal |

---

## 31. Implementation notes (for the build agents)

### 31.1 Zone data model
```
Zone {
  id, displayName, parentIsland, bounds (AABB or polygon list), music,
  requirement: { skill?: [name, level], hotm?: n, hotf?: n, sbLevel?: n, quest?: id, item?: id, purse?: coins },
  onFailMessage,                       // "You need Combat XII to enter The End"
  spawnTables: [SpawnRule], weather?: WeatherRule, hazards?: [Cold | RiftTime | Darkness | PressureDepth],
  fairySouls: [pos], warps: [WarpPoint], npcs: [npcId]
}
SpawnRule { mobId, level, points|area, maxAlive, respawnSeconds, timeOfDay?: day|night|any, weather?: rain|thunder }
WorldBoss  { mobId, arena, trigger: timer|meter|summon, respawnSeconds|meterMax, announceLeadSeconds }
```
- **Requirements are checked on zone ENTRY** (teleport back + chat message), not just at warps — players can walk in.
- Sub-areas inside an island are **named regions** shown in the scoreboard ("⏣ Graveyard"). The region name drives music, objectives, commission targets and Bestiary grouping — so every sub-area in this file must be a region polygon.

### 31.2 Mob spawning
- Use **spawn points with caps**, not vanilla spawning. Verified anchor: Zealots = **wave of 16 every 11 s** in the Dragon's Nest. Default suggestion elsewhere: low-level areas cap 10–20 alive per sub-area, respawn 10–30 s; elite areas cap 4–8, respawn 30–60 s.
- **Level variants are separate mob entries** (Enderman 42/45/50) sharing a model.
- **Replace-chance spawns** (Special Zealot 1/420, Ender-Chest Zealot 2%) = roll at spawn time.
- **Hit-count mobs** (Star Sentry, Bal, Gaia Construct shell) ignore damage numbers — model as `hitsToKill`.
- **Melee-only** (Ghost): flag `immuneTo: [magic, ranged]`.
- **Night-only / rain-only** spawns: Zombie Villager (night), Rain/Toxic Rain Slime (weather).
- **Splitting** (Splitter Spider → 2 Silverfish) = on-death spawn hook.

### 31.3 World bosses and timers
| Boss | Trigger | Value |
|---|---|---|
| Broodmother | timer with 5 visible stages | ~10–15 min (?) |
| Endstone Protector | community meter from End Stone mined | stage 5 → spawn, ~20 s warning (?) |
| Ender Dragon | 8 Summoning Eyes placed | player-driven |
| Arachne | summoning items at altar | player-driven |
| Crimson mini-bosses | respawn timers | Bladesoul/Mage Outlaw/Duke X/Ashfang **2 min**, Magma Boss **5 min** |
| Golden Goblin | random roll while mining | rare |
| Fallen Star | Dwarven event | every few events |
| Mythological creatures | dug from burrows | Diana only |

### 31.4 Instancing rules
| Zone | Instancing |
|---|---|
| Private Island, Garden | per-profile private world |
| Crystal Hollows | shared lobbies with **Day 0 open / Day 19 lock / Day 35 shutdown** lifecycle |
| Glacite Mineshafts | per-party instance, triggered by mining counter (1/2,000) with 30 s portal |
| Dungeons, Kuudra, Critter Safari | per-party instance |
| Rift | shared, but each player has a personal **Rift Time** countdown |
| Everything else | shared lobbies (Hypixel ~ 20–40 players per Hub lobby) |

### 31.5 Block regeneration
- Mining islands (Coal Mine, Gold Mine, Deep Caverns, Dwarven Mines, Glacite) — a mined ore becomes a placeholder (bedrock/stone) and **regenerates after a delay** (suggest 5–30 s by ore tier). **Crystal Hollows is the exception: mined blocks become air** (verified).
- Foraging islands — logs regrow on a timer; Galatea big trees are **multi-stage objects with toughness** (Fig 10 / Mangrove 50 / Helix 150) and a shared **participation** tally for Tree Gifts.
- Farming islands — crops regrow; Garden crops are player-planted.

### 31.6 Hazards per zone (all are simple per-zone rules)
| Hazard | Zone | Rule |
|---|---|---|
| Cold | Glacite Tunnels, Safari Icy biome | −0.5% Mining Speed/Cold; −25% move per 25; 100 = freeze → base camp |
| Rift Time | Rift | 480 s base; drains; paused in safe rooms; orbs +2 s |
| Pressure / breath | Torrhus Spring Depths | need Respiration + Pressure Resistance |
| Darkness | The Mist (Dwarven Mines) | blindness-like fog; Ghosts |
| Weather | Spider's Den, Lotus Atoll | rain 40/20 min cycles; thunder every 3rd (Den) |
| Volcano | Crimson Isle | eruption 2–2.5 h |
| Lobby timer | Crystal Hollows | 35-day lifecycle |

### 31.7 NPC shops
Model each shop as `{npcId, items: [{itemId, price, currency: coins|bits|motes|pelts|copper|essence|..., stock?: daily}], buyback: true}`. NPCs also **buy** most raw items (the "sell to NPC" floor price) — that floor is what anchors the economy (see the economy research file).

### 31.8 Building the feel (art direction per island)
| Island | Palette | Signature landmark |
|---|---|---|
| Hub | warm stone, oak, plaster, banners | fountain plaza + mountain |
| Barn | yellow wheat, red barn wood | windmill |
| Mushroom Desert | terracotta/red sand bands | gorge of giant mushrooms |
| Park | five tree palettes | launchpad chain of floating forests |
| Gold Mine | gold + timber | scaffolded open pit |
| Deep Caverns | grey → blue → red → green → cyan → black per layer | lift shaft |
| Dwarven Mines | mithril cyan/grey, gold trim, lava orange | Royal Palace + bridge |
| Glacite | packed ice, blue glacite | Great Glacite Lake |
| Crystal Hollows | glass-gem colours per quadrant | crystal-lit Nucleus |
| Spider's Den | dark stone, webs, rain | nest summit |
| The End | end stone, obsidian, purple | altar basin with crystal towers |
| Crimson Isle | netherrack, blackstone, magma, red | volcano + two rival towns |
| Galatea Marsh | mossy greens, misty water | central loch, treetop village |
| Torrhus Canyon | sandstone, honey yellow, spring blue | spring pool under canyon walls |
| Bayou | brown water, rust | junkyard smokestacks |
| Lotus Atoll | pink lotus, lily green, lagoon teal | ring atoll |
| Rift | sickly purple/green mirror of the Hub | broken Wizard Tower |
| Jerry's Workshop | snow + Christmas lights | Mount Jerry |

---

## 32. Progression placement (where a player should be, when)

| Stage | Typical gear / levels | Zones they live in | Key bosses | Unlocks next |
|---|---|---|---|---|
| **0 — Day one** (0–2 h) | starter tools, no armour, 100 HP | Private Island, Hub Village, Graveyard (Lv1 zombies), Hub Farm, Coal Mine, Foraging Camp | — | Combat I, Mining I, Foraging I, Farming I |
| **1 — Early** (2–10 h) | leather/iron/Rosetta armour, Undead/Spider Sword, Rookie→Promising tools | Gold Mine, Deep Caverns layers 1–4, The Park (Birch→Dark Thicket), The Barn, Spider's Den low slopes, Crypts (Lv30 Ghouls) | Broodmother (Lv12), Revenant T1 | Mining V (Deep Caverns), Farming V (Desert), Fishing V (Bayou), SB Lv5 (Garden) |
| **2 — Early-mid** (10–40 h) | Miner/Lapis armour, Hyperion-path not yet; first minions running | Deep Caverns 5–6, Mushroom Desert (Trevor), Garden (plots 1–5), Backwater Bayou, Howling Cave, Spider's Den top, Ruins (Old Wolves) | Arachne (Lv300), Sven/Tarantula T1–T2 | Mining XII (Dwarven Mines), Combat XII (End) |
| **3 — Mid** (40–150 h) | Mithril tools, Ender/Young-Dragon armour, AotD | Dwarven Mines (commissions, Forge), The End (Endermen → Zealots), Dragon's Nest, Dungeons F1–F5, The Rift (early rings), Crystal Hollows (HotM 4) | Ender Dragons, Golden Goblin, Ghosts (late mid), Leech Supreme | HotM 7, Foraging XII, Combat XXII, Fishing X |
| **4 — Mid-late** (150–400 h) | Divan / gemstone drills, Superior/Strong armour, F6–F7 gear | Crystal Hollows (all zones, Nucleus), Glacite Tunnels, Galatea Moonglade Marsh, Crimson Isle (factions, mini-bosses), Lotus Atoll, Zealot Bruiser Hideout | Bal, Boss Corleone, Crimson mini-bosses, Kuudra Basic–Burning, F7 | HotF IV, Combat XXV, Catacombs 24 |
| **5 — Late** (400 h+) | endgame armour/weapons, attributes | Glacite Mineshafts, Torrhus Canyon, Critter Safari (Hunting XV), Void Sepulture, Kuudra Fiery–Infernal, Master Mode, Rift endgame (Mountaintop) | Magma Boss, Vanquisher, Kuudra Infernal, M7 Wither King, Inquisitors | (Lunarise when released) |

### 32.1 Gate summary (single table for the requirements engine)

| Gate | Requirement |
|---|---|
| The Park | Foraging I + Lumber Jack quest |
| Park: Spruce / Dark Thicket / Savanna / Jungle | Foraging II / III / IV / V |
| Mushroom Desert | Farming V |
| Deep Caverns | Mining V |
| Dwarven Mines | Mining XII + Rhys quest |
| Crystal Hollows | Mining XII + HotM 4 + pass (10,000 coins / 6 h) |
| Glacite Tunnels | HotM 7 (+ Secret Railroad Pass) |
| Spider's Den | Combat I |
| Hub Ruins | Combat III recommended (not enforced) |
| The End | Combat XII |
| Zealot Bruiser Hideout | Combat XX |
| Crimson Isle | Combat XXII |
| Void Sepulture | Combat XXV |
| Kuudra Hot / Burning / Fiery / Infernal | 1,000 / 3,000 / 7,000 / 12,000 faction rep + previous tier |
| The Garden | SkyBlock Level 5 + talk to Sam |
| Backwater Bayou | Fishing V + Captain Baha |
| Lotus Atoll | Fishing X + Researcher Gilbert |
| Galatea (Moonglade Marsh) | Foraging XII |
| Torrhus Canyon | Foraging XII + HotF IV |
| Critter Safari | Hunting XV + Safari Ticket |
| Dark Auction | ≥400,000 coins purse |
| The Rift | Dimensional Infusion |
| Rift sub-areas | Rift Time thresholds + Timecharms (see 19.1) |
| Catacombs floors | Catacombs level (see 17) |
| Jerry's Workshop | Late Winter (seasonal) |

### 32.2 Suggested pacing targets for the clone (design, not Hypixel data)
- First island unlock (Spider's Den or Gold Mine/Deep Caverns) within **30 min** of play.
- Each later island should be reachable by a focused player in roughly **1.5–3× the time of the previous one**, matching Hypixel's curve where Dwarven Mines (Mining XII) arrives around 10–20 h and Crimson Isle (Combat XXII) around 50–150 h.
- Keep at least one **"social" world boss per island** (Broodmother, Endstone Protector, Fallen Star, Magma Boss, Tree Gifts) — they are what make a shared lobby feel alive.

---

## 33. Open questions / least-certain areas

1. **The exact 2026 Hub layout** (which compass direction each district sits in). Names and three coordinates are verified (Foraging Camp -128,74,-33; Combat Settlement -51,69,-77; Mining District 2,62,-122); the rest of the diagram is approximate.
2. **Most mid-tier mob stat lines** (Deep Caverns, Dwarven goblins, Crystal Hollows, Crimson regulars, Spider's Den variants) are from memory `(?)`; the verified anchors are Graveyard Zombie/Zombie Villager, Zealot, Ghost, Ender Dragons, Park wolf spirits, Crimson mini-boss HP, Bayou weights.
3. **NPC shop prices** are mostly `(?)` — the 2026 community wiki location pages do not list prices; individual NPC pages on hypixelskyblock.minecraft.wiki should be fetched per NPC when the shop agent builds it.
4. **Galatea mob stats** (Chillblade etc.) are not published on the location page; only names/types are verified.
5. Kuudra boss HP per tier and key coin costs are `(?)`.
