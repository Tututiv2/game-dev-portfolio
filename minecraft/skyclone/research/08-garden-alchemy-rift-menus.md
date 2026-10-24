# 08 — Garden & Farming, Alchemy, Enchanting, Minor Skills, The Rift, Misc Progression, UI/Menus

> Research reference for the from-scratch SkyBlock clone. Original Hypixel names are used throughout;
> renaming happens later. **Convention:** any number followed by **(?)** is from memory or inference and
> was NOT confirmed against a live source during this pass — treat it as a sensible default to tune, not a
> fact. Numbers without (?) were confirmed against the community wiki (`hypixelskyblock.minecraft.wiki`,
> which replaced the official wiki when that shut down in **July 2026**) on 2026-10-02.
>
> Colour codes use Minecraft legacy section codes (`§a` green, `§b` aqua, `§c` red, `§d` pink, `§e` yellow,
> `§6` gold, `§5` dark purple, `§9` blue, `§3` dark aqua, `§2` dark green, `§7` gray, `§8` dark gray, `§f` white,
> `§l` bold, `§o` italic, `§r` reset). Slot positions in chest GUIs are given as **slot index 0–53**
> (row-major, 9 per row, top-left = 0) unless stated otherwise.

---

## 0. Overview

This file covers the "everything that isn't combat, mining, fishing, slayers or dungeons" layer of SkyBlock,
plus the UI chrome that makes the whole game feel like SkyBlock:

| System | One-line summary | Where it lives | Primary currency / output |
|---|---|---|---|
| **The Garden** | A personal 480×480 farming island of 24 plots + a barn, levelled by Garden XP | `/warp garden`, unlocked at SkyBlock Level 5 | Crops, **Copper**, Compost, Garden XP |
| **Visitors** | NPCs arrive at the Garden asking for crops; pay Copper + XP + rare items | The Garden | Copper, Farming XP, Garden XP |
| **Composter** | Idle machine: Organic Matter + Fuel → Compost (plot unlock currency) | The Garden | Compost / Compost Bundles |
| **Pests** | Mobs that spawn while farming, cut Farming Fortune, killed with Vacuums | The Garden | Pest drops, Bits, Farming XP |
| **Greenhouse** | Offline crop-growing grid with adjacency effects and mutations (added Dec 2025) | Garden, Garden VII | Crops, Ethereal Vine, Chips |
| **Jacob's Farming Contest** | 20-minute 3-crop competition every 3 SkyBlock days | Anywhere you farm | Medals, Jacob's Tickets, farming-level cap |
| **Alchemy** | Brewing-stand potions; skill levelled by brewing | Any brewing stand | Potions, Alchemy XP |
| **Enchanting** | Table (XP levels → enchants), anvil (combine), Experimentation Table (minigames) | Private island / Hub library | Enchants, Enchanting XP |
| **Carpentry / Taming / Runecrafting / Social** | Minor "cosmetic" skills | Various | Skill XP, small stats |
| **The Rift** | A separate dimension with its own stat block (Rift Time, Hearts) and currency (Motes) | Wizard Tower → Rift | Motes, Enigma Souls, Vampire slayer |
| **Bestiary / Museum / Harp / Abiphone / Fairy Souls / Chocolate Factory / Century Cakes / Bits** | Long-tail collectible progression | Various | SkyBlock XP, permanent stats, cosmetics |
| **UI / menus** | SkyBlock Menu, stat screens, storage, sidebar, action bar, tab list, chat formats | Everywhere | The *feel* |

**Design truth to preserve:** almost every one of these systems feeds **SkyBlock XP** (the account level) and
almost every one is *idle-friendly* — Composter, Greenhouse, Visitors, Chocolate Factory and Booster-Cookie
Bits all accumulate while the player is doing something else or is offline. The clone should keep that
"many small clocks ticking" texture; it is the reason players log in daily.

---

## 1. Farming & The Garden

### 1.1 Farming skill basics

| Property | Value |
|---|---|
| Skill cap | **50 base**, raised by **+1 per crop** you have earned a **GOLD medal (or better)** in during a Jacob's Contest, up to **60** |
| Skill perk | **Farmhand** — +4 Farming Fortune per level (wiki: "+4 per level — up to +240 at Farming 60") |
| Stat reward per level | +2 Health per level 1–14, +3 per level 15–19, +4 per level 20–25, +5 per level 26–60 (?) (general skill pattern) |
| Coin reward per level | Scales with standard skill coin table (see Skills file) |
| XP source | Breaking fully-grown crops; Garden visitors; pests (1,500 Farming XP each); Greenhouse harvests (320 each) |
| Wisdom stat | **Farming Wisdom ☯** — +1% Farming XP per point |
| Jacob's Contest requirement | Farming **X (10)** |

#### Farming XP per crop broken (?)

| Crop | Farming XP per block (?) | Notes |
|---|---|---|
| Wheat | 4 | Also drops Seeds |
| Carrot | 4 | |
| Potato | 4 | Rare Poisonous Potato drop |
| Pumpkin | 4.5 | Block is the crop; stem stays |
| Sugar Cane | 4 (per block, 2 drops) | Must break the upper blocks only to keep the base |
| Melon | 4 | Block drops slices |
| Cactus | 4 | |
| Cocoa Beans | 4 | Grows on jungle logs |
| Red / Brown Mushroom | 6 | Garden mushrooms grow on mycelium / podzol |
| Nether Wart | 4 | Soul sand |
| Sunflower | ? (assume 4) | Added with the 2025 Garden update; Garden XI |
| Moonflower | ? (assume 4) | Garden XI; night/day counterpart of Sunflower (?) |
| Wild Rose | ? (assume 4) | Garden XII |

> **Implementation default:** if the exact XP for the three new flowers is unknown, use 4.0 and expose it in
> config. The meaningful balance lever is drops × fortune, not XP per break.

### 1.2 Crop list (Garden crops)

There are **13 Garden crops** (Mushroom counts once; Red and Brown share it). Each is unlocked at a Garden Level.

| # | Crop | Garden level to unlock | Base drops per break | Pest it attracts | Specialised tool line | Rare/related items |
|---|---|---|---|---|---|---|
| 1 | Wheat | I (1) | 1 Wheat + 1–2 Seeds | Fly | Euclid's Wheat Hoe | Box of Seeds, Enchanted Hay Bale |
| 2 | Carrot | II (2) | 1–4 | Cricket | Gauss Carrot Hoe | Cropie (contest drop), Enchanted Golden Carrot |
| 3 | Potato | III (3) | 1–4 | Locust | Pythagorean Potato Hoe | Enchanted Baked Potato |
| 4 | Pumpkin | IV (4) | 1 | Rat | Pumpkin Dicer | Polished Pumpkin, Squash (contest drop) |
| 5 | Sugar Cane | V (5) | 2 | Mosquito | Turing Sugar Cane Hoe | Enchanted Sugar Cane |
| 6 | Melon (Slice) | VI (6) | 3–7 | Earthworm | Melon Dicer | Enchanted Melon Block |
| 7 | Cactus | VII (7) | 2 | Mite | Cactus Knife | Enchanted Cactus |
| 8 | Cocoa Beans | VIII (8) | 3 | Moth | Coco Chopper | Enchanted Cookie |
| 9 | Mushroom (Red/Brown) | IX (9) | 1 | Slug | Fungi Cutter | Mushroom blocks/Enchanted Mushroom |
| 10 | Nether Wart | X (10) | 2–3 | Beetle | Newton Nether Warts Hoe | Fermento (contest drop), Mutant Nether Wart |
| 11 | Sunflower | XI (11) | 2 | Dragonfly | (? new tool line) | Condensed Helianthus / Helianthus armor line |
| 12 | Moonflower | XI (11) | 2 | Firefly | (? new tool line) | |
| 13 | Wild Rose | XII (12) | 2 | Praying Mantis | (? new tool line) | Rose Dragon pet (?) |

Non-Garden farming collections that are NOT Garden crops (farmed on Farming Islands / The Barn): **Seeds**,
**Raw Chicken, Feather, Leather, Raw Beef, Raw Porkchop, Mutton, Raw Rabbit** (animal drops from The Barn).

#### Crop growth on The Garden

| Mechanic | Value |
|---|---|
| Crop Growth stat | Every **1 Crop Growth = +1% growing speed**, cap **+210** |
| Garden-level contribution | **+10 Crop Growth per Garden level above I** → +40 at V, +90 at X, +140 at XV |
| Water | Soil must be fully moist; **−40% growth speed** on soil that is not |
| Garden crops regrow | Yes — Garden crops regrow ~instantly relative to vanilla so a player can loop a farm (?) — on Garden, broken crops replant automatically when a tool with "replenish"-style behaviour is used (cocoa/wart/carrot/potato/wheat) (?) |
| Replenish enchant | Axe/hoe enchant: broken crop is automatically replanted using one seed from inventory (pre-Garden mechanic, still exists) |

> **Clone default:** make Garden crops regrow on a timer (`baseGrowSeconds / (1 + cropGrowth/100)`), and let
> Replenish replant instantly. A player should be able to walk a lane and back without waiting — the design
> intent of "farm design" in SkyBlock is lanes of 1–3 crop rows with the player walking back and forth.

### 1.3 Farming Fortune

**Formula (applies to every crop and also Mining/Foraging Fortune):**
`drops = baseDrops × (1 + fortune/100)` where the fractional part is a chance of one extra.
Wiki phrasing: *"260 fortune yields 2 guaranteed extra drops plus a 60% chance for a third."* Concretely:

```
extraMultiplier = fortune / 100           // e.g. 2.60
guaranteed     = floor(extraMultiplier)   // 2
chance         = extraMultiplier - guaranteed  // 0.60
drops = baseDrops * (1 + guaranteed + (rand() < chance ? 1 : 0))
```

There are two kinds of fortune:
- **Farming Fortune ☘** — applies to every crop.
- **Crop Fortune** (e.g. *Wheat Fortune ☘*) — applies only to one crop; stacks additively with Farming Fortune.

#### Sources of Farming Fortune

| Source | Amount | Max |
|---|---|---|
| Farming skill (Farmhand) | +4 / level | +240 at Farming 60 |
| Anita's "Extra Farming Fortune" upgrade (Jacob's Tickets) | +4 / tier | +60 at 15 tiers |
| Garden plots owned | +3 / plot | +72 with all 24 plots |
| Garden (Pest) Bestiary | per tier | +102 with 17 Pest Bestiary families/tiers |
| Crop Analyzer milestones (Greenhouse) | +5 / level | +30 |
| Garden Crop Upgrades (Desk) | +5 **Crop** Fortune / tier | +45 Crop Fortune (9 tiers) |
| Rookie Hoe / Axe | +5 | |
| Basic Gardening Hoe / Axe | +10 | |
| Advanced Gardening Hoe / Axe | +15 | |
| Binghoe | +25, +3 per SkyBlock Level (max 25 levels counted) | |
| Specialised tools (Euclid's etc.) | +4 to +200 Crop Fortune depending on tier/upgrades | |
| Harvesting enchant (tools) | Harvesting VI = +75 (I=+12.5 … VI=+75) (?) per-level | +75 |
| Cultivating enchant (tools) | +2 per level (?) | +20 at X |
| Turbo-Crop enchant (tools, per crop) | +5 per level of that crop's Turbo (?) | +25 at V |
| Dedication enchant (tools) | Farming Fortune × crop milestone tier (?) | Scales with milestone |
| Green Thumb V (armor/equipment) | Scales with unique Visitors served | +137 on full equipment at 137 unique visitors |
| Crop Fever I–V | +100 | (?) burst-style ability |
| Pesterminator VI (armor) | +48 on a full armor set (+12/piece) (?) | |
| Farming Talisman | +3 | |
| Helianthus Relic | +40 | |
| Peony set pieces | +5 to +20 each | |
| Blossom set pieces | +7 to +29.5 each | |
| Helianthus Armor (full) | +225 (+120 with Mossy, +80 with 8 Perfect Peridot) | |
| Fermento Armor (full) | +205 | |
| Squash Armor (full) | +170 | |
| Cropie Armor (full) | +135 | |
| Melon Armor / Farm Suit / Farm Armor / Rancher's Boots / Farmer Boots | small early-game values (Farm Suit +5/piece (?), Rancher's Boots +5–25 (?)) | |
| Mooshroom Cow pet | +100 (mushroom-focused) | |
| Elephant pet | +150 to +200 | |
| Rose Dragon pet [Lv200] | +40 base, up to +336.7 with perks | |
| Chicken pet | +50 (up to +150 on public islands) | |
| Bee pet | +40 (+80 with Bee Mask) | |
| Slug pet | +100 when farming in a Sprayonator plot | |
| Century Cake (Chocolate) | +5 | 48 h buff |
| Peridot gemstones on tools/armor | per gem quality (Rough +0.5 … Perfect +10 per slot) (?) | |

**Theoretical max** (wiki): +3,059.2 Farming Fortune, +637 Crop Fortune → ~3,696.2 total = 36 bonus drops + 96.2% chance of a 37th.

> **Balance note for the clone:** the curve goes from ~0 FF at hour 1 to ~1,500 FF mid-game and ~2,500+
> late. Crop prices must therefore be tiny per unit (wheat ≈ 6 coins NPC, ~0.5 coins/drop effective for
> enchanted forms) so that a 3,000-FF player does not print money. Pricing lives in the economy file.

### 1.4 Farming tools

#### Tool tiers

| Tool | How obtained | Fortune | Notes |
|---|---|---|---|
| Wooden/Stone/Iron/Diamond Hoe | Vanilla crafting | 0 | Starter |
| Rookie Hoe | Hub (Farm Merchant) | +5 | |
| Basic Gardening Hoe / Axe | SkyMart (Copper) | +10 | Generic, all crops |
| Advanced Gardening Hoe / Axe | SkyMart (Copper) | +15 | Generic, all crops |
| Euclid's Wheat Hoe Tier I / II / III | Wheat collection recipe → upgraded with Jacob's tickets / enchanted materials (?) | Tier I +10, II +25, III +50 Wheat Fortune (?) | Hoe line |
| Gauss Carrot Hoe I–III | Carrot collection | same tiering (?) | |
| Pythagorean Potato Hoe I–III | Potato collection | same tiering (?) | |
| Turing Sugar Cane Hoe I–III | Sugar Cane collection | same tiering (?) | |
| Newton Nether Warts Hoe I–III | Nether Wart collection | same tiering (?) | |
| Melon Dicer 1.0 / 2.0 / 3.0 | Melon collection | +? Melon Fortune; ability **Dicer**: chance to drop a block of melons | Axe line |
| Pumpkin Dicer 1.0 / 2.0 / 3.0 | Pumpkin collection | +? Pumpkin Fortune; same Dicer ability | Axe line |
| Cactus Knife | Cactus collection | +? Cactus Fortune | |
| Coco Chopper | Cocoa collection | +? Cocoa Fortune | Axe |
| Fungi Cutter | Mushroom collection | +? Mushroom Fortune; right-click toggles Red/Brown mode for +bonus on chosen colour | |
| Binghoe | Bingo shop | +25 (+3 per SB level, max 25) | |
| Sunflower / Moonflower / Wild Rose tools | (? 2025) | (?) | Verify when building |

#### The two counter mechanics on specialised tools

| Mechanic | Appears on | How it works | Bonus |
|---|---|---|---|
| **Counter** + **Logarithmic Counter** | Hoe line (Tier II+) | Tool tracks *crops broken with this tool* (stored on the item, shown as `Counter: 1,234,567 Wheat`) | **+16 ☘ Crop Fortune for every digit of the counter** (minus a small offset at low counts) (?). 1,234,567 (7 digits) → +112 |
| **Collection Analysis** | Hoe line Tier III (?) | Reads the player's **collection** for that crop | **+8 ☘ Crop Fortune per digit** of the collection count (?) |
| **Cultivating** enchant | Axes/dicers/knives/choppers (and books from Bits shop) | Counts every crop broken; enchant auto-levels at thresholds | Level thresholds below |

**Cultivating thresholds** (?):

| Level | Crops broken | Bonus (?) |
|---|---|---|
| I | 1,000 | +2 FF, +1 Farming Wisdom |
| II | 5,000 | +4 FF, +2 FW |
| III | 25,000 | +6 FF, +3 FW |
| IV | 100,000 | +8 FF, +4 FW |
| V | 300,000 | +10 FF, +5 FW |
| VI | 1,500,000 | +12 FF, +6 FW |
| VII | 5,000,000 | +14 FF, +7 FW |
| VIII | 20,000,000 | +16 FF, +8 FW |
| IX | 50,000,000 | +18 FF, +9 FW |
| X | 100,000,000 | +20 FF, +10 FW |

Lore format on a counter tool:
```
§7Counter: §e1,234,567 Wheat
§7Logarithmic Counter
§7Gain §6+16☘ Wheat Fortune §7for every digit on
§7the counter.
§8(+112 currently)
```

#### Tool upgrades & reforges

| Item | Effect | Cap |
|---|---|---|
| Farming For Dummies | +1 Farming Fortune per book | 5 per tool |
| Hot Potato / Fuming Potato Books | Not applicable to hoes (weapons/armor only) | — |
| Reforge **Blessed** (tool) | +Farming Fortune scaling with rarity (≈ Common +5 → Mythic +20) (?) | |
| Reforge **Bountiful** (tool) | +Farming Fortune and a small chance per crop to drop coins (?) | |
| Reforge **Mossy** (armor) | Large FF on Helianthus armor (+120 on full set) | |
| Reforge **Bustling** / **Rooted** / **Blooming** (armor, ?) | FF / crop-specific perks | |
| Peridot gemstone slots | +FF per gem (Rough → Perfect) | 1–2 slots per tool |
| **Garden Chips** (2025+) | Socketable items with names like **Hypercharge Chip, Quickdraw Chip, Evergreen Chip, Synthesis Chip, Vermin Vaporizer Chip, Overdrive (?), Sowledge (?)** — each a farming-equipment perk; Evergreen Chip is a *multiplicative* +2–60% Greenhouse yield on base crops | (?) exact slot rules |
| Overclocker 3000 | Rare Greenhouse drop; tool/chip upgrade item (?) | |

### 1.5 Garden overview

| Property | Value |
|---|---|
| Access requirement | **SkyBlock Level 5** |
| Size | 5×5 grid of **96×96** plots (?) — 24 player plots + 1 centre **Barn** plot → 480×480 blocks |
| Plots | **24** |
| Garden levels | **15** (I–XV) |
| Currencies introduced | **Copper**, Compost, Compost Bundles, Garden XP |
| Barn | Central spawn plot holding the **Desk** (main garden GUI), Composter, SkyMart, NPCs |
| NPCs present | Anita, Carpenter, Feast Chef Ted (?), Jacob, Jeff, Sam, Pesthunter Phillip, Pesthunter Pamela |
| Commands | `/warp garden`, `/desk`, `/plottp <name>`, `/tptoplot <n>` (?) |
| Building | Players can place blocks in plots (water, slabs, trapdoors, light) to design farms; plots have a fixed depth (?) |
| Plot clearing | New plots are covered in grass/flowers/trees; clearing them yields Garden-only drops (Overgrown Grass etc.) (?) |
| Per-plot bonus | **+3 Farming Fortune** and **+5 SkyBlock XP** per plot owned |

#### Garden levels (XP table — confirmed)

| Level | XP for this level | Cumulative XP |
|---|---|---|
| I | 0 | 0 |
| II | 70 | 70 |
| III | 70 | 140 |
| IV | 140 | 280 |
| V | 240 | 520 |
| VI | 600 | 1,120 |
| VII | 1,500 | 2,620 |
| VIII | 2,000 | 4,620 |
| IX | 2,500 | 7,120 |
| X | 3,000 | 10,120 |
| XI | 10,000 | 20,120 |
| XII | 10,000 | 30,120 |
| XIII | 10,000 | 40,120 |
| XIV | 10,000 | 50,120 |
| XV | 10,000 | 60,120 |

**Garden XP sources:** crop milestones (biggest early), accepting visitor offers, unique-visitor milestones,
visitors-accepted milestones, pests (?).

#### Garden level rewards (cumulative snapshots — confirmed)

| At level | Visitors unlocked (cumulative) | Crops | Pest types | Crop upgrade tiers | Barn skins | SkyBlock XP | Crop Growth |
|---|---|---|---|---|---|---|---|
| V | +78 | 5 | 6 | I–II | Medieval | 40 | +40 |
| X | +120 | 10 | 11 | I–V | 3 | 90 | +90 |
| XV | +137 | 13 | 14 | I–IX | 5 | 140 | +140 |

Per-level pattern: **+10 SkyBlock XP and +10 Crop Growth per level** above I; unlock the crop of that level;
unlock new visitors; unlock the crop-upgrade tier for even levels.

### 1.6 Plots and plot costs (confirmed)

Plot cost depends on the plot's **group** (its Garden-level requirement) and how many plots you've already
bought **in that group**, not on the plot number. Paid at the Desk with **Compost** (or **Compost Bundles**).

| Group | Garden level req. | Purchase # in group → cost |
|---|---|---|
| A (4 plots) | I | 1, 2, 4, 8 Compost |
| B (4 plots) | III | 16, 24, 32, 48 Compost |
| C (4 plots) | V | 64, 96, 128 Compost, then **1 Compost Bundle** |
| D (12 plots) | VII | 1, 2, 2, 3, 3, 4, 5, 7, 8, 10, 12, 15 Compost Bundles |

Total: 15 + 120 + 288 Compost + 1 Bundle (A–C) and **72 Bundles** for group D. A **Compost Bundle** is a
compacted stack of Compost (assume **160 Compost per Bundle** (?)).

**Plot layout** — 5×5 around the Barn (numbering below is the clone default; Hypixel's actual plot IDs are a
spiral-ish layout (?)). Group A plots are the four orthogonal neighbours of the Barn, group B the four diagonals,
groups C/D the outer ring:

```
 +------+------+------+------+------+
 | D 21 | D 13 | C  9 | D 14 | D 22 |
 +------+------+------+------+------+
 | D 15 | B  5 | A  1 | B  6 | D 16 |
 +------+------+------+------+------+
 | C 10 | A  2 | BARN | A  3 | C 11 |
 +------+------+------+------+------+
 | D 17 | B  7 | A  4 | B  8 | D 18 |
 +------+------+------+------+------+
 | D 23 | D 19 | C 12 | D 20 | D 24 |
 +------+------+------+------+------+
```

Plots can be **renamed** and assigned an icon (Configure Plots), and **teleported to** from the Desk.
The Desk's Plots screen shows exactly this 5×5 picture as a chest GUI (see §11.13).

### 1.7 Crop Upgrades (confirmed costs)

Bought at the Desk with **Copper**, per crop, 9 tiers. Each tier: **+5 Crop Fortune** (that crop only) and
**+1 SkyBlock XP**.

| Tier | Garden level req. | Copper | Cumulative |
|---|---|---|---|
| I | II | 5 | 5 |
| II | IV | 10 | 15 |
| III | VI | 20 | 35 |
| IV | VIII | 50 | 85 |
| V | X | 100 | 185 |
| VI | XII | 500 | 685 |
| VII | XIII | 1,000 | 1,685 |
| VIII | XIV | 2,000 | 3,685 |
| IX | XV | 4,000 | 7,685 |

Maxing all 13 crops = **99,905 Copper**.

### 1.8 Crop Milestones

Harvesting a cumulative amount of a crop **on The Garden** (or anywhere? — Garden only (?)) advances that
crop's milestone. Viewed at Desk → Crop Milestones. Each crop has its own track.

| Property | Value |
|---|---|
| Tiers per crop | **46** (?) |
| Reward per tier | **Garden XP** (main early Garden XP source) + SkyBlock XP on some tiers (?); feeds the **Dedication** enchant |
| Scaling | Each crop's thresholds are the Wheat thresholds × a crop multiplier that compensates for base drops (?) |

Wheat thresholds (?) — clone default:

| Tier | Wheat (this tier) | Tier | Wheat (this tier) |
|---|---|---|---|
| 1 | 30 | 16 | 50,000 |
| 2 | 50 | 17 | 75,000 |
| 3 | 80 | 18 | 100,000 |
| 4 | 170 | 19 | 175,000 |
| 5 | 330 | 20 | 250,000 |
| 6 | 670 | 21 | 350,000 |
| 7 | 1,330 | 22 | 500,000 |
| 8 | 2,500 | 23 | 750,000 |
| 9 | 3,500 | 24 | 1,000,000 |
| 10 | 5,000 | 25 | 1,300,000 |
| 11 | 6,500 | 26 | 1,600,000 |
| 12 | 8,000 | 27 | 2,000,000 |
| 13 | 10,000 | 28 | 2,300,000 |
| 14 | 20,000 | 29 | 2,600,000 |
| 15 | 35,000 | 30–46 | +3,000,000 each (?) |

Crop multipliers for milestone thresholds (?) (based on base drops): Wheat ×1, Pumpkin ×1, Mushroom ×1,
Carrot ×3, Potato ×3, Nether Wart ×2.5, Cocoa ×3, Sugar Cane ×2, Cactus ×2, Melon ×5, Sunflower/Moonflower/Wild Rose ×2.

Milestone tier N gives **Garden XP = 10 × something** (?) — clone default: tier N grants `10 + 5×floor(N/5)` Garden XP.

Chat message on reaching a milestone:
```
§r  §a§lGARDEN MILESTONE §eWheat §838➜§e39
§r    §a§lREWARDS
§r      §8+§2100 §7Garden Experience
§r      §8+§b5 SkyBlock XP
```

### 1.9 Copper

| Property | Value |
|---|---|
| Earned from | **Accepting visitor offers** (main), Jacob's? no; pest kills? no (?); selling? no |
| Spent on | **SkyMart** items, **Crop Upgrades** |
| Sidebar | `Copper: 1,234` (`§c` red/copper-coloured) shown while on the Garden |
| Tradeable | No (profile-bound counter) |

### 1.10 Visitors

| Mechanic | Value |
|---|---|
| Max present | **5** on the Garden at once; if the player is on the Garden, a **6th queues** hidden until one is resolved |
| Spawn interval | One every **15 minutes** while online (?); **offline** visitors accumulate at a slower rate (1/hr (?)) up to the cap |
| Interval reductions | Visitor-speed perks: Garden level, Green Bandana?, Mayor Finnegan (?) — clone: −5% per 5 Garden levels |
| Total visitor NPCs | ~**140** unique (137 counted by Green Thumb at time of writing) |
| Rarities | **Uncommon, Rare, Legendary, Mythic, Special** |
| Requirements | Garden level (I–XV) plus per-visitor gates: talked to an NPC, quest done, faction rep, mayor elected, seasonal event, etc. (only Garden level applies on Stranded) |
| Interaction | Right-click the visitor → offer GUI: requested items, rewards, **Accept** (green terracotta) / **Refuse** (red terracotta) |
| Refusing | Visitor leaves; no penalty except it counts as not served (?) |
| Request content | 1–2 item types (raw crops for early, enchanted crops / Box of Seeds / Mutant Nether Wart / special items later); amount scales with Garden level and the visitor's rarity |
| Rewards | **Copper**, **Farming XP**, **Garden XP**, sometimes **Bits**, sometimes a **bonus item** (rare) |
| Visitor's Logbook | Desk → Visitors log: every visitor ever seen, times served, last offer |

#### Rarity tuning (clone defaults (?))

| Rarity | Spawn weight | Garden XP | Copper (?) | Request size multiplier |
|---|---|---|---|---|
| Uncommon | 70 | 1 | 15–40 | 1.0 |
| Rare | 22 | 2 | 30–60 | 1.3 |
| Legendary | 7 | 5 | 50–100 | 1.8 |
| Mythic | 1 | 10 | 100–200 | 2.5 |
| Special | event-driven | 10 | 100–250 | special |

> **Clone formula suggestion:** set request size so that its **NPC coin value ≈ 1–4 minutes of the player's
> current farming income**, then `copper = round(requestValueCoins / 3,000)` clamped to the rarity range.
> Visitors should never be a coin-positive trade versus selling the crops; they are a Copper/XP trade.

#### Rare visitor drops (confirmed)

| Item | Chance per accepted offer |
|---|---|
| Hypercharge Chip | 3% |
| Quickdraw Chip | 3% |
| Flowering Bouquet | 2% |
| Green Bandana | 0.4% (RARE visitors and above only) |
| Overgrown Grass | 0.1% |
| Dedication IV book, Cultivating I book, Replenish book, Delicate book, Fruit Bowl, Jungle Key, Space Helmet piece (Spaceman) … | visitor-specific, typically 0.04%–5% |

#### Visitor milestones

| Track | Tiers | Range | Rewards |
|---|---|---|---|
| Unique Visitors Served | 32 | 1 → 300 cumulative uniques (?) — note only ~140 exist, later tiers future-proof | Farming XP, Garden XP, SkyBlock XP |
| Offers Accepted | 11 | → 300+ accepted (?) | Farming XP, Garden XP, SkyBlock XP |

#### Example visitors

| Name | Rarity | Requirement | Usually asks for |
|---|---|---|---|
| Jacob | Uncommon | Talked to Jacob | Wheat |
| Carpenter | Rare | Garden VII | Melon Slice or Cocoa Beans |
| Jerry | Legendary | First visit only; later visits accept "Any" | Bread |
| Mayor Aatrox | Legendary | Garden III + Aatrox elected | Enchanted Cactus |
| Taylor | Mythic | Garden XIV | Deepfries, Salted Seeds, Enchanted Potatoes |
| Ludleth | Mythic | Garden XV + 100 unique visitors | Compacted Sunflower |
| Spaceman | Special | Garden XV + 75 visitors | Any crop except Seeds; drops Space Helmet pieces |
| Farmer Jon, Andrew, Liam, Fear Mongerer, Gwendolyn, Tammy, Rhys, Shifty, Wizard, Puzzler, Bartender, Lumber Jack, Duke, Royal Resident, Seymour, Madame Eleanor Q. Goldsworth III, Tia the Fairy, Lazy Miner, Old Man Garry, Elle, Geonathan Greatforge… | Mixed | Each tied to its home NPC having been met (?) | Mixed |

**Offer GUI layout** (3 rows, 27 slots) (?):

| Slot | Item |
|---|---|
| 13 | Visitor head — name, rarity tag, times visited |
| 29 → (3-row: 11) | **Accept Offer** — green terracotta; lore lists `Items Required:` and `Rewards:` |
| 15 | **Refuse Offer** — red terracotta |
| others | Black stained glass panes |

Accept lore:
```
§aAccept Offer
§7Items Required:
§r §aEnchanted Wheat §8x12
§r
§7Rewards:
§r §8+§32.5k §7Farming XP
§r §8+§24 §7Garden Experience
§r §8+§c37 Copper
§r §8+§b1 Bits
§r
§eClick to give!
```

### 1.11 Composter (confirmed)

| Property | Value |
|---|---|
| Output | 1 **Compost** every **10 minutes** (base) |
| Consumption per Compost | **4,000 Organic Matter + 2,000 Fuel** (base) |
| Base caps | **40,000 Organic Matter**, **250,000 Fuel** |
| Runs offline | Yes |
| Input | Insert crops/items as Organic Matter, fuel items as Fuel (GUI) |

#### Organic Matter values (selected)

| Item | OM |
|---|---|
| Wheat / Seeds / Pumpkin / Red Mushroom (and Brown) | 1 |
| Potato / Poisonous Potato / Nether Wart | 0.33 |
| Carrot | 0.29 |
| Melon Slice | 0.2 |
| Cactus, Sugar Cane, Cocoa (?) | 0.5 (?) |
| Enchanted versions | base × 160 (?) |
| Box of Seeds | 25,600 (?) |
| Cropie | 2,500 |
| Squash | 10,000 |
| Fermento | 20,000 |
| Condensed Fermento | 180,000 |
| Condensed Helianthus | 270,000 |

#### Fuel values (?)

| Item | Fuel |
|---|---|
| Biofuel | 3,000 |
| Oil Barrel | 10,000 |
| Volta | 10,000 |
| Enchanted Coal (?) | 1,000 |
| Enchanted Block of Coal (?) | 160,000 ... (verify; clone default 10,000) |

#### Composter upgrades

| Upgrade | Garden req. | Effect per tier | Tiers | Max |
|---|---|---|---|---|
| Composter Speed | II | +20% production speed | 25 | +500% (6×) |
| Multi Drop | IV | +3% chance of an extra Compost | 25 | 75% |
| Fuel Cap | VI | +30,000 Fuel cap | 20 | 850,000 |
| Organic Matter Cap | VIII | +30,000 OM cap | 25 | 790,000 |
| Cost Reduction | X | −1% OM & Fuel per Compost | 25 | −25% (3,000 OM + 1,500 Fuel) |

Upgrade costs are paid in **Copper + crops/enchanted crops** (?); clone default: tier n costs `round(10 × 1.35^n)` Copper plus an enchanted-crop stack.

Maxed composter ≈ **63 Compost/hour**, eating ~108,000 OM + 54,000 Fuel per hour.

### 1.12 Pests (confirmed unless marked)

| Pest | Linked crop | Garden lvl | Sprayonator material that attracts it | Signature drop |
|---|---|---|---|---|
| Fly | Wheat | 5 | Dung | Beady Eyes (3%), Pretty Fly Vinyl (5%) |
| Cricket | Carrot | 5 | Honey Jar | Chirping Stereo (1%), Cricket Choir Vinyl (5%) |
| Locust | Potato | 5 | Plant Matter | Locust Larva |
| Rat | Pumpkin | 5 | Tasty Cheese | **Rat pet** |
| Mosquito | Sugar Cane | 5 | Compost | Clipped Wings |
| Earthworm | Melon | 6 | Compost | Bookworm's Favorite Book |
| Mite | Cactus | 7 | Tasty Cheese | Atmospheric Filter |
| Moth | Cocoa Beans | 8 | Honey Jar | Wriggling Larva |
| Slug | Mushroom | 9 | Plant Matter | **Slug pet** |
| Beetle | Nether Wart | 10 | Dung | Pesterminator I book (7%) |
| Dragonfly | Sunflower | 11 | Jelly | Vermin Vaporizer Chip |
| Firefly | Moonflower | 11 | Jelly | Fire in a Bottle |
| Praying Mantis | Wild Rose | 12 | Jelly | Mantid Claw |
| Lunar Moth | Sunflower/Moonflower/Wild Rose | 5 | Moondew | Sunset I book (35%) |
| Field Mouse | Random | 5 | Squeaky Toy / Squeaky Mousemat | Squeaky Toy (5%), Squeaky Mousemat (1%) |

**Universal drops (all pests except Field Mouse):** 1,000 coins, **1,500 Farming XP**, 10 Experience, and one
of Compost / Honey Jar / Dung / Plant Matter / Tasty Cheese / Jelly at 10% each. **5 Bits per kill** (× Fame Rank multiplier).

#### Spawning

| Mechanic | Value |
|---|---|
| Base spawn chance | **0.2% (1 in 500) per crop broken** while the pest cooldown is off |
| Base cooldown | **5 minutes** after a spawn |
| Cooldown reductions | Finnegan −20%, Pesthunter's Necklace −10%, Pesthunter's Cloak −10%, Pest Vest −15%, Pesthunter's Belt −10%, Pesthunter's Gloves −10%, Squeaky reforge −2.5% each |
| Cooldown bounds | min **1 min 10 s** (with Eradicator) / 2 min 10 s without; max 20 min |
| Pest Repellent | ×2 cooldown; **Pest Repellent MAX** ×4 (don't stack; MAX wins); lasts 1 h (?) |
| Offline spawning | **1 pest per hour** offline |
| Pest Trap | Spawns ~1 pest per 15 minutes into a trap (?) |
| Pest type selection | Weighted: base weight **100** per eligible pest; a **sprayed** plot adds **+1,000** weight to the pests its spray attracts; the crop being broken biases the pick (?) |
| Health | 600 (only damageable by **Vacuums**) |
| Where | Spawns on the plot you are farming, flies around it; plot shows `ൠ x2` in sidebar/tab |
| Bonus Pest Chance stat | Each 100 = +1 guaranteed extra pest per spawn roll ("fortune for pests") (?) |
| Salty Sprayonator / Autumn / Atmospheric Filter | can raise spawn chance up to 0.636% (1 in 157) |

#### Farming Fortune penalty by live pests (confirmed)

| Bonus Pest Chance | 0 pests | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|---|
| 0–99 | — | — | — | −5% | −15% | −30% | −50% | −75% | Max |
| 100–199 | — | — | −5% | −15% | −30% | −50% | −75% | Max | — |
| 200–299 | — | −5% | −15% | −30% | −50% | −75% | Max | — | — |
| 300–399 | −5% | −15% | −30% | −50% | −75% | Max | — | — | — |
| 400–499 | −15% | −30% | −50% | −75% | Max | — | — | — | — |
| 500+ | −30% | −50% | −75% | Max | — | — | — | — | — |

(The "free" pest allowance before penalties starts is 3 at 0–99 BPC, rising to 8 at 500+.)

Rare-drop scaling: `chance = base × (1 + Overbloom/100)`; pets `× (1 + Overbloom/100 + PetLuck/600)`.

#### Vacuums (?)

| Vacuum | Source | Pest range (blocks) | Damage / hit (?) | Bonus Pest Chance |
|---|---|---|---|---|
| SkyMart Vacuum | SkyMart (Copper) | 5 | 100 | — |
| Turbo Vacuum | SkyMart / craft | 7.5 | 150 | +10 |
| Hyper Vacuum | Craft (pest drops) | 10 | 200 | +25 |
| InfiniVacuum | Craft | 12.5 | 300 | +50 |
| InfiniVacuum Hooverius | Craft (top tier) | 15 | 600 | +75 |

Vacuum: hold right-click → particle beam that "sucks" the pest; left-click ability **Pest Tracker** shows a
particle line to the nearest pest (?). Pest kills also show in chat:
```
§6§lPEST! §7You killed a §6Fly §7in §aPlot §7- §b4§7! §8(+1,500 Farming XP)
```
Spawn chat:
```
§6§lYUCK! §7A §6ൠ Pest §7has appeared in §aPlot §7- §b4§7!
§6§lEWW! §62 §6ൠ Pests §7have spawned in §aPlot §7- §b4§7!
```

#### Sprays

| Item | Effect |
|---|---|
| Sprayonator | Right-click a plot to spray it with the selected material; lasts **30 minutes** (?) |
| Spray materials | Compost, Honey Jar, Dung, Plant Matter, Tasty Cheese, Jelly, Moondew, Squeaky Toy/Mousemat |
| Effect | +1,000 spawn weight for the two pests tied to that material; certain sprays also +FF or +crop growth on that plot (?) |
| Slug pet | +100 FF while in a sprayed plot |

### 1.13 SkyMart

Opened from the Desk or the SkyMart NPC (Sam (?)) in the Barn. Mostly **Copper**, some coins/Gems.

| Category | Item | Price (?) |
|---|---|---|
| Tools | Basic Gardening Hoe | 5 Copper |
| Tools | Basic Gardening Axe | 5 Copper |
| Tools | Advanced Gardening Hoe | 25 Copper |
| Tools | Advanced Gardening Axe | 25 Copper |
| Tools | Euclid's / Gauss / Pythagorean / Turing / Newton Hoe Tier I | 100 Copper each (?) |
| Tools | Melon Dicer, Pumpkin Dicer, Cactus Knife, Coco Chopper, Fungi Cutter | 100 Copper each (?) |
| Pests | SkyMart Vacuum | 100 Copper (?) |
| Pests | Sprayonator | 25 Copper (?) |
| Pests | Pest Repellent | 40 Copper (?) |
| Pests | Pest Trap | 100 Copper (?) |
| Farming | Biofuel / composter fuel | coins (?) |
| Farming | Farming For Dummies | 50 Copper (?) |
| Farming | Garden Scythe (clears plots) | 100 Copper (?) |
| Farming | Jungle/mycelium/podzol/soul-sand blocks for farms | coins / small Copper |
| Cosmetic | Barn Skins: Trading Post, Autumn Hut, Bamboo, Hive, Castle, Cozy Cottage, Cube, Windmill, Tavern, Melon, Lucky | 250–10,000 Copper (?) |
| Cosmetic | Greenhouse Skins | Copper (?) |
| Accessories | Fermento/Squash related? no; Pesthunter's line via Phillip | — |
| Upgrades | Copper-for-Dyes / Rune? (?) | — |

Barn skins from milestones: **Medieval, Sunny, Red, Cabin, Mansion Heights**. Others seen: Frog, Jerry,
Pinwheel House, Mushroom.

### 1.14 Pesthunter Phillip / Pamela

| NPC | Function |
|---|---|
| Pesthunter Phillip | Exchange **Pests killed** for the Pesthunter accessory/armor line (Necklace, Cloak, Belt, Gloves) and Pest Vest; tracks "pests killed" counter (?) |
| Pesthunter Pamela | Newer NPC (2025) — Pest bestiary/trap related shop (?) |

### 1.15 Greenhouse (current — added SkyBlock 0.24, Dec 2025)

| Property | Value |
|---|---|
| Unlock | Garden **VII**, complete the Carpenter's offer, obtain the **Greenhouse Blueprint** |
| Concept | A grid of planting slots where crops grow **offline** in timed stages; each crop buffs/debuffs **orthogonally adjacent** crops |
| Base stage time | **4 hours** (14,400 s) |
| Formula | `T = 14400 / (1 + 0.025c + 0.0025g + 0.001a + U)` where c = unique crops planted (max 12), g = Crop Growth stat, a = Greenhouse Speed attribute, U = 0.05·u (u = 0–8) or 0.50 (u = 9) |
| Minimum stage time | 1 h 41 m 3 s |
| Water | After each stage a crop loses 2–3 Water Level; negative water → failure chance; Water Retain/Drain effects modify loss |
| Farming XP | **320** per harvested crop |
| Extra greenhouses | Secondary: 100 Ethereal Vine + 10 Compost Bundle; Tertiary: 150 Ethereal Vine + 20 Compost Bundle |
| Mutations | Specific arrangements create mutations (identified with the **Crop Analyzer**); harvesting them drops **Ethereal Vine** |
| Crop Analyzer milestones | +5 Farming Fortune per level, max +30 |
| Cosmetics | Greenhouse Skins at the Desk |

#### Greenhouse crops

| Crop | Base yield | Growth cycles | Adjacent effect |
|---|---|---|---|
| Wheat | 72 wheat + 1 seed | 8 | Harvest Boost (+20%) |
| Carrot | 175 | 8 | XP Boost (+20%) |
| Potato | 150 | 8 | Immunity |
| Pumpkin | 68 | 11 | Bonus Drops |
| Sugar Cane | 136 | 8 | Improved XP Boost (+30%), Harvest Loss (−20%) |
| Melon Slice | 320 | 11 | Water Retain (+50%) |
| Cactus | 132 | 8 | Improved Water Retain (+100%), Harvest Loss (−20%) |
| Cocoa Beans | 120 | 6 | Immunity |
| Red Mushroom | 30 | 6 | Improved Harvest Boost (+30%), Water Drain (−30%) |
| Brown Mushroom | 30 | 6 | Improved Harvest Boost (+30%), Water Drain (−30%) |
| Nether Wart | 108 | 8 | Improved Harvest Boost (+30%), XP Loss (−20%) |
| Sunflower | 232 | 15 | Bonus Drops |
| Moonflower | 232 | 15 | Bonus Drops |
| Wild Rose | 200 | 15 | Effect Spread |

Effects: Harvest Boost +20%, Improved +30%, XP Boost +20%, Improved +30%, Water Retain +50%, Improved +100%,
**Immunity** (blocks negatives), **Effect Spread** (propagates neighbours' effects), Bonus Drops (rare loot pool),
Harvest Loss −20%, XP Loss −20%, Water Drain −30%. Effects lock once a crop is harvestable; all are additive
except the **Evergreen Chip** (multiplicative).

Yield multipliers: Plant Yield Upgrade +2–20%, Evergreen Chip +2–60%, adjacency boosts, **+3% per unique crop** (max +36%).

**Harvest Bounty** (Pumpkin/Sunflower/Moonflower with Bonus Drops):

| Item | Chance |
|---|---|
| Overclocker 3000 | 0.05% |
| Iridium | 0.2% |
| Burrowing Spores | 0.2% |
| Overgrown Grass | 0.1% |
| Evergreen Chip | 3% |
| Synthesis Chip | 3% |
| Ethereal Vine | 5% |

> **Clone note:** the Greenhouse is a self-contained puzzle (a tile grid with neighbour effects). It can be
> built as a standalone 9×N chest GUI — no world blocks needed — which makes it one of the cheapest
> late-game systems to ship. Grid size (?) — clone default 5×5 = 25 slots.

### 1.16 Jacob's Farming Contest (confirmed)

| Property | Value |
|---|---|
| Frequency | Every **3 SkyBlock days** (= every 60 real minutes) |
| Duration | 1 SkyBlock day = **20 real minutes** |
| Crops | **3** random crops from the farming collection set per contest |
| Requirement | Farming **X**, talked to Jacob once, **≥100** of the contest crop collected during the contest |
| Scoring | Amount of the crop **collected** (after fortune) during the 20 minutes; ranked against all participants of that contest |
| Where | Anywhere (Garden or Farming Islands) |
| Sidebar | Shows contest name, crop, time left, collected, current bracket |

| Bracket | Top % (normal) | Top % (Finnegan **GOATed** perk) | Medals |
|---|---|---|---|
| BRONZE | 60% | 70% | 1 Bronze |
| SILVER | 30% | 40% | 1 Silver |
| GOLD | 10% | 20% | 1 Gold |
| PLATINUM | 5% | 10% | 1 Gold + 1 Bronze |
| DIAMOND | 2% | 5% | 1 Gold + 1 Silver |

| Bracket | Jacob's Tickets | Carnival Tickets | Turbo-Crop book | Bits |
|---|---|---|---|---|
| BRONZE | 10 | 1 | Turbo-Crop I | 40 |
| SILVER | 15 | 1 | Turbo-Crop I | 50 |
| GOLD | 25 | 2 | Turbo-Crop I | 60 |
| PLATINUM | 30 | 2 | Turbo-Crop I | 70 |
| DIAMOND | 35 | 3 | Turbo-Crop I | 80 |

Participants who collect ≥100 but place below Bronze get a participation reward (Jacob's Tickets ×1–5 (?)).

- **Unique Gold**: first Gold+ in a crop raises the Farming level cap by 1 (cap 60).
- Turbo-Crop IV/V application require **Bronze/Silver personal best** in that crop.
- **Personal Bests** are tracked per crop; Anita sells a "Personal Best" bonus (+Crop Fortune when beating PB (?)).
- **Contest drops**: while farming during a contest the player can find **Cropie** (Carrot/Potato/Wheat contests),
  **Squash** (Pumpkin/Melon/Cocoa) and **Fermento** (Wart/Cane/Cactus/Mushroom) (?) — these craft the Cropie/Squash/Fermento armor ladder and Melon/Fermento artifacts.

**Anita's shop** (Jacob's Tickets + medals) (?):

| Item | Cost |
|---|---|
| Extra Farming Fortune (15 tiers, +4 FF each) | tickets + Bronze→Gold medals escalating |
| Extra Personal Best (Double Drops?) | tickets |
| Jacob's Tickets → Farming XP boost | |
| Lantern Helmet, Farmer's / Melon / Pumpkin armor recipes | medals |
| Cropie/Squash/Fermento talisman upgrades (Cropie Talisman → Squash Ring → Fermento Artifact) | medals + items |
| Turbo-Crop books | tickets |
| Jacob's Ticket cosmetics, dyes (Pure Yellow?), barn skins | |

Contest-start chat:
```
§e[NPC] Jacob§f: §bThe Farming Contest has started!
§eCrops: §aWheat§e, §aCarrot§e, §aCactus
```
End chat summary lists your amount, bracket and position.

### 1.17 Barn & Farming Islands (non-Garden)

| Island | Contents |
|---|---|
| The Barn | Wheat / Carrot / Potato / Pumpkin / Melon fields + animal pens (cows, pigs, chickens, sheep, rabbits) |
| Mushroom Desert | Cactus, sugar cane, mushrooms, cocoa (Trapper NPC hunts) |
| Farming Islands (overall) | Jacob's contest also counts here; pre-Garden farming lived here |

Anita/Jacob originally lived on the Farming Islands; since the Garden update they are in the Garden barn.

---

## 2. Alchemy

### 2.1 Alchemy skill

| Property | Value |
|---|---|
| Cap | **50** |
| XP source | Brewing potions (XP per brew depends on the ingredient tier); selling/using? no |
| Perk | **Brewer** — +1% duration on potions you brew, per level (+50% at 50) (?) |
| Stat per level | +1 Intelligence (levels 1–14), +2 Intelligence (15+) (?) |
| Wisdom | Alchemy Wisdom ☯ |
| Collection gates | Brewing ingredients' enchanted forms unlock via their collections (Sugar Cane → Enchanted Sugar, etc.) |

**Alchemy XP per brew (clone default (?))**: base ingredient 10 XP, enchanted ingredient (level III) 30 XP,
double-enchanted (level V) 60 XP, modifiers 5 XP each; ×3 when brewing a full stand of 3 bottles.

### 2.2 Brewing stand mechanics

SkyBlock uses the vanilla brewing-stand GUI, re-skinned with custom recipes:

| Step | Input | Output |
|---|---|---|
| 1. Base | Water Bottle + **Nether Wart** | Awkward Potion |
| 2. Effect | Awkward Potion + effect ingredient | Potion of X at the ingredient's level (I / III / V…) |
| 3. Level modifier | Potion + Glowstone-family | Level +1 / +2 / +3 (up to the potion's max) |
| 4. Duration modifier | Potion + Redstone-family | +8 / +16 / +40 minutes |
| 5. Splash | Potion + Gunpowder | Splash potion (thrown, affects players in radius) |
| (corrupt) | Potion + Fermented Spider Eye | Corrupts into the inverse (Healing → Damage, Night Vision → Invisibility, Speed → Slowness, etc.) |

| Property | Value |
|---|---|
| Bottles per stand | 3 |
| Brew time | 20 s per step (vanilla) (?) — reduced by stand upgrades/Alchemy? (clone: 20 s flat) |
| Fuel | Not required in SkyBlock (?) |
| Potion stacking rule | Effects of the **same type don't stack**; drinking a new one **replaces** level & duration with the new potion's |
| Different effects | Stack (one of each type) |
| Duration base | Most potions **3:00** before modifiers and Alchemy/Potion Affinity bonuses |
| Dungeons | Entering a dungeon **pauses** all active potion effects (Dungeon potions run instead) |
| Potion name shows | `Speed V Potion` (lore: effect line, duration `(8:00)`, "Enhanced" lines for modifiers) |

#### Modifier tables (confirmed)

| Level modifier | Effect |
|---|---|
| Glowstone Dust | +1 level |
| Enchanted Glowstone Dust | +2 levels |
| Enchanted Glowstone | +3 levels |

| Duration modifier | Effect |
|---|---|
| Redstone | +8 minutes |
| Enchanted Redstone | +16 minutes |
| Enchanted Redstone Block | +40 minutes |
| Enchanted Redstone Lamp | +16 minutes **and** +3 levels (?) (as listed by wiki fetch) |

| Splash modifier | Effect |
|---|---|
| Gunpowder | Converts to splash |
| Enchanted Gunpowder | Splash with increased radius (?) |

A potion can hold **one** level modifier and **one** duration modifier (?) — clone default: each modifier
type can be applied once.

Duration bonuses that apply on drink:
- Brewer perk (Alchemy): up to +50% (?)
- **Potion Affinity Talisman / Ring / Artifact**: +10% / +25% / +50% (?)
- **Parrot pet** (Legendary): + potion duration % (?)
- Mayor perks (e.g. "Mythological Ritual"? no — Aatrox/Cole? none) (?)

### 2.3 Potion list

Base ingredient → level I; first enchanted form → level III; second enchanted form → level V (then glowstone to
VI–VIII). "Max" = highest brewable level (God Potion may exceed).

| Potion | Max | Ingredient I | Ingredient III | Ingredient V | Effect per level |
|---|---|---|---|---|---|
| **Speed** | VIII | Sugar | Enchanted Sugar | Enchanted Sugar Cane | +5 Speed per level (+5 → +40) |
| **Strength** | VIII | Blaze Powder | Enchanted Blaze Powder | Enchanted Blaze Rod | +5, +12.5, +20, +30, +40, +50, +60, +75 Strength (?) (I and VIII confirmed: +5…+75) |
| **Healing** (instant) | VIII | Glistering Melon | Enchanted Melon | Enchanted Glistering Melon | Instant heal: 20, 50, 100, 150, 200, 300, 400, 500 HP (?) |
| **Damage** (instant, corrupt Healing) | VIII | (Healing + F.S.E.) | | | Instant damage mirror of Healing (?) |
| **Jump Boost** | IV | Rabbit's Foot | Enchanted Rabbit Foot | — | Vanilla jump boost levels |
| **Resistance** | VIII | Cactus | Enchanted Cactus Green | Enchanted Cactus | +5 → +60 Defense (I=+5, VIII=+60; intermediate +10/+15/+20/+30/+40/+50 (?)) |
| **Water Breathing** | VI | Pufferfish | Enchanted Pufferfish | — | Underwater breathing; levels extend effect (?) |
| **Regeneration** | VIII (IX on God Potion?) | Ghast Tear | Enchanted Ghast Tear | — | +5 Health Regen per level (?) |
| **Mana** | VIII | Raw Mutton | Enchanted Mutton | Enchanted Cooked Mutton | +1 Mana/second per level (?) |
| **Critical** | IV | Flint | Enchanted Flint (?) (wiki listed Ench Glowstone family as level mods) | — | CC/CD: I 10%/10%, II 15%/20%, III 20%/30%, IV 25%/40% |
| **Archery** | IV | Feather | Enchanted Feather (?) | — | +12.5%, +25%, +50%, +75% Arrow damage (?) |
| **Haste** | IV | Coal | Enchanted Coal | — | +50 Mining Speed per level (?) |
| **Absorption** | VIII | Gold Ingot | Enchanted Gold | Enchanted Gold Block | +20 Absorption per level (?) |
| **Adrenaline** | VIII | Cocoa Beans | Enchanted Cocoa Beans | Enchanted Cookie | +20 Absorption & +5 Speed per level (?) |
| **Rabbit** | VI | Raw Rabbit | Enchanted Raw Rabbit | — | Jump Boost + Speed (+10 Speed per level (?)) |
| **Agility** | IV | Enchanted Cake | — | — | +10 Speed & +? dodge per level (?) |
| **Night Vision** | I (III on God (?)) | Golden Carrot | — | — | Night vision |
| **Invisibility** | I | Night Vision + F.S.E. | — | — | Invisible (mobs still target in SB) |
| **Fire Resistance** | I | Magma Cream | — | — | Immune to fire/lava ticks |
| **Spelunker** | V | Enchanted Gold (?) / Enchanted Diamond (?) | | | +5 Mining Fortune per level (?) |
| **Experience** | IV | Enchanted Lapis Lazuli (?) | | | +X% experience orbs (I +5% … IV +20%) (?) |
| **Stun** | IV | Enchanted Obsidian (?) | | | Chance to stun mobs on hit (?) |
| **Dodge** | IV | Salmon (?) | | | Chance to dodge melee (?) |
| **True Resistance** | IV | Enchanted Rabbit Hide (?) | | | +5/+10/+15/+20 True Defense (?) |
| **Spirit** | IV | Enchanted ? | | | +10 Speed & +10% Crit Damage per level (?) |
| **Pet Luck** | VII | Enchanted ? (?) | | | +X Pet Luck (?) |
| **Knockback** | II | Slime Ball | | | Increases knockback dealt |
| **Burning** | IV | Red Sand (?) | Enchanted Red Sand (?) | | Sets attacked mobs on fire (?) |
| **Venomous** | IV | Enchanted Spider Eye (?) | | | Poisons attacked mobs (?) |
| **Wounded** | IV | Enchanted Netherrack (?) | | | Mobs hit heal less (?) |
| **Cold Resistance** | IV (?) | (?) | | | +Cold Resistance (Glacite Tunnels) |
| **Mushed Glowy Tonic** | IV (?) | Glowy Chum Bait (?) | | | +Fishing Speed (?) |
| **Slowness / Weakness / Poison / Blindness** | IV–VIII | F.S.E. on Speed / Strength / etc. | | | Debuffs; typically splash, aimed at mobs/players in PvP minigames |
| **Combat / Mining / Foraging / Fishing / Farming / Enchanting / Alchemy XP Boost** | III | Enchanted Rotten Flesh / Enchanted Cobblestone / Enchanted Oak Log / Enchanted Raw Fish / Enchanted Wheat or Hay Bale / Enchanted Lapis? / Enchanted Nether Wart? (?) | | | +5% / +10% / +20% skill XP (?). **Not affected by Redstone**; Glowstone allowed |
| **Dungeon I–VII** | VII | Dungeon chest loot only | | | Bundle of combat effects for the dungeon run |
| **Wisp's Ice-Flavored Water** | I | Crimson Isle (Bartender) | | | Grants Fire-related immunity + stats (?) |
| **God Potion** | — | Community Shop / Bazaar | | | See §2.4 |

> Rarity of a potion item: **I Common, II Common (?), III Uncommon, IV Uncommon (?), V Rare, VI Rare, VII Epic, VIII Epic**
> (wiki: levels I through VIII span Common → Epic; God Potion is SPECIAL).

### 2.4 God Potion

| Property | Value |
|---|---|
| Source | Community Shop **1,500 Bits** (also Bazaar/AH) |
| Duration | Long (**24 h** (?)); multiple potions **stack duration** (consuming adds time) (?) |
| Ticks offline | No — counts only while online (?) |
| Effects (approx (?)) | Speed VIII, Strength VIII, Resistance VIII, Regeneration IX, Mana VIII, Critical IV, Archery IV, Haste IV, Jump Boost IV, Absorption VIII, Adrenaline VIII, Water Breathing VI, Night Vision III, Fire Resistance I, Rabbit VI, Agility IV, Spelunker V, Experience IV, Dodge IV, True Resistance IV, Spirit IV, Pet Luck VII, Stun IV, Burning IV, Magic Find (?), Mushed Glowy Tonic? |
| Not included | Skill XP boost potions (separate), Dungeon potions |
| Interaction | Consumed (not brewed); while active its effects override lower levels of the same effects |

### 2.5 Potion Bag

| Property | Value |
|---|---|
| Access | SkyBlock Menu → Your Bags → Potion Bag (or `/potionbag` (?)) |
| Slots | 9 → 18 → 27 → 36 → 45 unlocked by Nether Wart / Alchemy collection tiers (?) |
| Accepts | Potions only |
| Purpose | Storage; drinking from the bag via right-click consumes the potion (?) |

### 2.6 Splash potions

| Property | Value |
|---|---|
| Effect radius | ~4 blocks (vanilla) (?) |
| Shared to others | Yes — party/co-op members in radius get the effect (key use: dungeon "pot sharing") |
| Duration | Same as drink version (?) |
| Debuffs | Splash Slowness/Poison/Weakness exist mainly for mob kiting and minigames |

---

## 3. Enchanting

### 3.1 Enchanting skill

| Property | Value |
|---|---|
| Cap | **60** |
| XP sources | Enchanting at the table (XP levels spent → Enchanting XP), Experimentation Table, Bazaar? no, Enchanted book combining (?) |
| Perk | **Conjurer** — +4% Experience orbs from any source per level (?) |
| Stat per level | +1 Intelligence (1–14), +2 Intelligence (15+) (?) and Ability Damage +0.5% (?) |
| Unlocks | Each enchant has an Enchanting level requirement to appear on the table; Experimentation Table craft at **X** |

### 3.2 Enchanting Table (GUI)

Right-click any enchanting table → custom 6-row GUI. Layout (?):

```
 slot:  0  1  2  3  4  5  6  7  8
 row0: [P][P][P][P][P][P][P][P][P]       P = gray pane
 row1: [P][P][P][E][E][E][E][E][P]       E = available enchant (book icon)
 row2: [P][ITEM][P][E][E][E][E][E][P]    ITEM = slot 19, drop item to enchant
 row3: [P][P][P][E][E][E][E][E][P]
 row4: [P][B][P][<][P][P][P][>][P]       B = Bookshelf Power indicator (bookshelf), < > = pages
 row5: [P][P][P][P][X][P][P][G][P]       X = close (barrier), G = Enchantments Guide (book)
```

Flow: put item in slot 19 → right side lists every enchant valid for that item type that the player's
Enchanting level allows → click enchant → **level selection screen** (one enchanted book per level, I…max)
showing the cost → click a level → XP levels deducted, enchant applied (overwrites lower level of same enchant,
removes conflicting enchants after confirmation).

**Bookshelf power:** count of bookshelves around the table (max 15); each enchant level has a minimum bookshelf
power (higher levels need more) (?). Clone default: `requiredPower = min(15, 3 × (level − 1))`.

#### XP level costs (clone default (?))

Hypixel lists a per-enchant, per-level XP cost on the level button. Observed pattern for most "normal"
enchants:

| Level | Typical cost (XP levels) (?) | Typical bookshelf power (?) |
|---|---|---|
| I | 10 | 0 |
| II | 15 | 3 |
| III | 20 | 6 |
| IV | 25 | 9 |
| V | 30 | 12 |
| VI (table-max for a few like Growth/Protection?) | 40 | 15 |

Examples (?): Sharpness I–V 10/15/20/25/30; Protection I–V 10/15/20/25/30; Efficiency I–V 10/15/20/25/30;
Critical I–V 10/15/20/25/30; Growth I–V 10/15/20/25/30; Smelting Touch I 15 (?); Experience I–III 10/15/20.
**Telekinesis** was removed (auto-pickup is default) — do not implement.

**Enchanting XP earned** = XP levels spent × (enchant-specific multiplier) (?) — clone: `levelsSpent × 30` Enchanting XP.

Lore of a level button:
```
§9Sharpness V
§7Increases melee damage dealt by §a30%§7.
§7
§7Cost
§331 Exp Levels
§7
§eClick to enchant!
```

#### Enchant tiers by source (summary — full list lives in the Enchantments file)

| Source | Examples |
|---|---|
| Table | Sharpness V, Protection V, Growth V, Critical V, Efficiency V, Fortune III, Power V, Smite V, Bane V, Looting III, Scavenger III, Harvesting V, Cubism V, Ender Slayer V, Execute V, Experience III, Fire Aspect II, First Strike IV, Giant Killer V, Impaling III, Knockback II, Lethality V, Life Steal III, Luck V, Thunderlord V, Venomous V, Vampirism V |
| Anvil only (combine) | Level VI and VII of many enchants (Sharpness VI from two V's; VII via two VI's where allowed) |
| Drops / shops only | Ultimate enchants (one per item), Dungeon books, Experimentation books, Bits stacking enchants (Expertise, Compact, Cultivating, Champion, Hecatomb), Garden books (Dedication, Green Thumb, Turbo-Crop, Pesterminator, Replenish, Delicate) |

### 3.3 Anvil

GUI (6 rows) (?):

| Slot | Content |
|---|---|
| 29 | Left input (item to upgrade) |
| 33 | Right input (sacrifice: book / same item / Hot Potato Book / Recombobulator / Farming For Dummies…) |
| 13 | Preview output |
| 22 | **Combine Items** (anvil icon) — lore shows `Cost: N Exp Levels` |
| 11, 12, 14, 15, 20, 24 | Red panes when invalid, green when valid (indicator lights around the inputs) |
| 49 | Close |

**Combining rules:**

| Case | Result |
|---|---|
| Book + item, enchant not on item | Enchant added (if compatible) |
| Book + item, item has lower level | Item takes the book's level |
| Book level == item level, both ≥ table-max | +1 level **only if** that enchant allows anvil-upgrade to that level (data field `anvilMax`) |
| Two identical books, same level L | One book at L+1 (subject to `anvilMax`) — this is how Sharpness VI, Protection VI, Growth VI, etc. are made |
| Two books, different levels | Higher level kept (no +1) |
| Conflicting enchants (Sharpness / Smite / Bane of Arthropods; Ultimate enchants one-per-item; Thunderlord/Thunderbolt?) | Rejected (red panes) |
| Hot Potato Book | +2 Damage & +2 Strength (weapons) / +4 HP & +2 Def (armor) per book, max **10** |
| Fuming Potato Book | Same as HPB, extends cap to **15** |
| Recombobulator 3000 | +1 rarity, once |
| Farming For Dummies | +1 FF, max 5 |
| Book of Stats / Art of War / Art of Peace / Wood Singularity / Transmission Tuner / Mana Disintegrator / Jalapeño / Bookworm's Favorite Book / etc. | One-time anvil upgrades |
| Same item type + same item | Merge enchants (like vanilla) (?) |
| Rename | Not via anvil in SkyBlock (?) |

**Anvil XP cost** (?): `sum over resulting enchants that changed of (newLevel × 2)` + 1 per upgrade book; clone default.

Combine chat: none; success plays anvil sound.

### 3.4 Experimentation Table

| Property | Value |
|---|---|
| Recipe | 1 Experience Bottle, 8 Oak Logs, 6 Lapis Lazuli, 1 Glass Bottle — craftable at **Enchanting X** |
| Placement | Private Island; **max 1** placed |
| Also accessible | `/et` via Booster Cookie (?) |
| Daily reset | 12:00 AM GMT, or 24 h after consuming the last charge |
| Structure | Two optional **add-ons** (Chronomatron, Ultrasequencer) + the main **Superpairs** |
| Add-on payoff | Each add-on round reached gives extra **Superpairs clicks** (and Enchanting XP) |

#### Tiers (?)

| Tier | Enchanting level req. (?) | Superpairs grid (?) | Base clicks (?) | Chronomatron max rounds (?) | Ultrasequencer max (?) |
|---|---|---|---|---|---|
| Beginner | 0 | 3×4 | — | 5 | 5 |
| High | 10 | 3×6 | — | 7 | 7 |
| Grand | 15 | 4×7 | — | 9 | 9 |
| Supreme | 20 | 4×7 | — | 11 | 12 |
| Transcendent | 25 | 4×7 | — | 13 | 15 |
| Metaphysical | 30 | 5×7 (Superpairs only) | — | 15 | 18 |

#### Superpairs (main)

- Grid of face-down tiles (stained glass of one colour). Click one → revealed; click a second → if they match,
  the pair is **won** (stays revealed, item added to rewards); else both flip back after a short delay.
- Limited **clicks** (base by tier + bonus from add-ons). When clicks run out, you claim everything matched.
- Special tiles (?): **Instant Find** (reveals and claims a random pair), **Gained +2/+3 Clicks**,
  **XP tiles** (Enchanting XP).
- Rewards pool: **Experience Bottles → Grand → Titanic → Colossal** Experience Bottles, enchanted books like
  **Big Brain III–V, Feather Falling VI–X, Infinite Quiver VI–X, Rainbow, Experience? Hecatomb?** (tier-gated), Enchanted Clock (?), **Dyes**? (?) — clone: tier loot tables.
- Enchanting XP per tile & on completion.

#### Chronomatron (add-on)

- 3- or 4-row grid of coloured terracotta/glass "keys" (each colour a different note pitch).
- Round n: the game flashes a sequence of n colours (with matching sounds); the player repeats it by clicking.
- Each correct round extends the sequence by 1. One mistake ends the game. Rewards scale with rounds cleared.
- Bonus Superpairs clicks: +1 per X rounds (clone: +1 at 1/3, 2/3 and full of max rounds (?)).

#### Ultrasequencer (add-on)

- Grid of panes; numbered items (1, 2, 3 …) appear in random slots for ~2 s, then all are hidden.
- Player clicks the slots in ascending order 1 → n. Each successful round adds a number.
- Mistake ends the game. Same click/XP reward scaling as Chronomatron.

---

## 4. Carpentry, Taming, Runecrafting, Social, (Hunting)

| Skill | Cap | XP from | Perk / reward | Notes |
|---|---|---|---|---|
| **Carpentry** | 50 | Crafting items (any crafting-table recipe gives XP based on materials (?)); furniture crafting | **+1 Health per level** (?) (perk "Home Maker" (?)) | "Cosmetic" skill — does not count toward skill average (?) — current Hypixel: Carpentry counts? (?) |
| **Taming** | 50 → **60** (cap raise via pet-related unlocks (?)) | Pets gain XP → owner gets Taming XP when pet XP is earned (?) | **Zoologist**: +1% pet XP per level (?) ; +1 Pet Luck per level (?) | Pets menu shows Pet Score (unique pets by rarity → Magic Find) |
| **Runecrafting** | 25 | Fusing runes at the **Runic Pedestal**; killing mobs with rune-applied gear (?) | Unlocks higher rune levels | Cosmetic (runes = particle effects). Does not count to skill average |
| **Social** | 25 | Co-op members/guests skilling on your island, visiting islands (?) | none (cosmetic) | Shared XP; does not count to skill average |
| **Hunting** (2025, Galatea) | (?) | Catching creatures/shards; Attributes system | Hunting Fortune etc. | Covered by the Foraging/Galatea file; listed here only for menu completeness |

**Runes** (?): Blood, Zap, Lava, Hearts, Ice, Snow, Spirit, Smokey, Music, Rainbow, Golden, Fire Spiral, Bite, Clouds,
Dragon, Enchant, End, Gem, Hot, Jerry, Lightning, Magical, Redstone, Slimy, Soultwist, Sparkling, Wake, Grand
Searing, etc. Each has levels **I, II, III**; two identical runes at the pedestal fuse into the next level.
A rune is applied to a weapon / armor / bow by combining at the pedestal (one rune per item).

---

## 5. The Rift

A separate dimension with its **own inventory rules, own stat block, own currency and a timer**. It is a
self-contained "adventure island" bolted onto the main game, and it is a good template for any future
"other dimension" content in the clone.

### 5.1 Entry & exit

| Property | Value |
|---|---|
| Entrance | **Wizard Portal** in the Wizard Tower (Hub, Wilderness by the mountain) |
| Entry cost | **Dimensional Infusion** — 1 free every **4 hours**, max **3** stored |
| Alternative entry costs | 32 Grand Experience Bottles; **200 Bits**; **Rift Prism** accessory (permanent); Tal Ker Garden visitor offer |
| Arrival | **Enigma's Crib** (Wyld Woods), the tutorial/start area |
| Exit | Run out of Rift Time, or use the exit porthole/“Leave the Rift” option (?) |
| Inventory | Main-world items **do not** enter the Rift (inventory swaps to a Rift inventory) except **Rift-Transferable** items; Rift items do not leave except **Rift-Exportable** ones |
| Fall damage | None in the Rift |

### 5.2 Rift stats (the only stats that apply)

| Stat | Symbol (?) | Base (?) | Meaning |
|---|---|---|---|
| **Rift Time** | `ф` (green) | **480 s (8 min)** | Your timer. Drains 1/s while in the Rift. At 0 you are ejected |
| **Hearts** | `❤` (red) | 5 (?) | Rift health pool — damage is dealt in whole hearts; running out costs Rift Time / ejects (?) |
| **Rift Damage** | `❁` (?) | 1 (?) | Damage dealt to Rift mobs |
| Speed | `✦` | 100 | Same as main |
| Intelligence | `✎` | 100 (?) | Mana pool in the Rift |
| Mana Regen | — | — | |
| Magic Find | `✯` | 0 | |

- Rift Time **freezes** in the Wizard Tower (except ground floor), the **Rift Gallery** and the **Mirrorverse**.
- Below **10 s** remaining: warning, and the player becomes immune to time penalties (grace).
- Many Rift interactions **cost Rift Time** (gates, NPC services, hits from certain mobs drain time).
- **Rift Time is increased by**: Timecharms (permanent), Rift armor/accessories, Century Cake `ekaC yrutneC tfiR` (+10),
  picking up **Mote orbs** (+2 s each), and Rift-specific items.

### 5.3 Motes

| Property | Value |
|---|---|
| Earned | Mote orbs (**25 Motes + 2 s Rift Time** each; 10 Motes in Enigma's Crib), killing Rift mobs, selling Rift items to the **Motes Grubber**, Rift bosses/slayer |
| Spent | Rift shops (Rift gear, Timecharm-related services), Motes Grubber sells? , Vampire slayer quests (?) |
| Lifetime Motes | Tracked; some unlocks key off lifetime motes (?) |
| Sidebar | `Motes: 12,345` (light purple `§d` (?)) replacing Purse while in the Rift |

### 5.4 Areas

| Area | Highlights |
|---|---|
| **Wyld Woods** | Start (Enigma's Crib), Rift Gallery, Shifted Tavern, Fairylosopher, Odonata bottles, Bughunter, the first Timecharm puzzles |
| **Black Lagoon** | Leech-infested swamp, Lagoon Cave, dolphin/Kat-like quests |
| **West Village** | Mirrorverse entrance (**Mirrorverse**: inverted puzzle dungeon — upside-down parkour, lava maze, dance room, Turbulator puzzle — awards the *mrahcemiT esrevrorriM*) |
| **Dreadfarm** | Rift farming area (Wilted Berberis, Agaricus Cap mushrooms), *Chicken N Egg* timecharm |
| **Village Plaza** | Hub of the Rift: Motes Grubber, Barry Center, Cake House, Vermin, the Tax Collector (?), *SkyBlock Citizen* timecharm (bureaucracy questline) |
| **Living Cave** | Living Metal mechanics, Living Metal Suit, *Living* timecharm |
| **Colosseum** | Blobbercyst boss → *Globulate* timecharm |
| **Stillgore Château** | **Vampire Slayer** (Oubliette, Stillgore Château crypts), *Vampiric* timecharm |
| **The Mountaintop** | Late area, Time-torn Isles, Wizardman? *Celestial* timecharm |

### 5.5 Collectibles

| Collectible | Count | Reward |
|---|---|---|
| **Enigma Souls** | **52** | Found around the Rift; each unlocks Enigma progress (Rift Time / Motes / perks at milestones (?)) — shown in Rift's "Enigma Soul" counter |
| Fairy Souls (Rift) | **1** | Counts to the main Fairy Soul total |
| **Timecharms** | **8** | Supreme, Chicken N Egg, mrahcemiT esrevrorriM, SkyBlock Citizen, Living, Globulate, Vampiric, Celestial — each a puzzle/boss reward; each permanently adds Rift Time (+? s each (?)) and SkyBlock XP |
| Montezuma pet | 1 | Rift pet assembled from **Soul Pieces** found in the Rift (?) — Rift-only pet |

### 5.6 Vampire Slayer (Riftstalker Bloodfiend)

| Tier | Requirement (?) | Boss Hearts (?) | Cost (?) | Slayer XP (?) |
|---|---|---|---|---|
| I | Enter Stillgore Château | 20 ❤ | 2,000 coins / Motes | 10 |
| II | Vampire Lvl 1 | 50 ❤ | 4,000 | 25 |
| III | Vampire Lvl 2 | 100 ❤ | 6,000 | 60 |
| IV | Vampire Lvl 3 | 200 ❤ | 10,000 | 120 |
| V | Vampire Lvl 4 | 400 ❤ | 15,000 | 150 |

Max Vampire Slayer level: **5** (?).

Mechanics (?):

| Mechanic | Description | Counter |
|---|---|---|
| **Steak Stake** execute | At ≤20% hearts the boss can only be finished with the Steak Stake (melee) | Swap to stake |
| **Twinclaws** | Telegraphed double strike ("TWINCLAWS" title) — big damage | Use **Holy Ice** / dodge in time |
| **Mania** | Ground circles appear; standing in them drains | Move out |
| **Ichor** | Blood pools/orbs that heal the boss | Destroy / avoid |
| **Killer Springs** | Spring blocks that explode | Break them |
| **Bloodfiend's Bite** | Heals boss from you | Anti-Bite Scarf accessory reduces |

Notable drops (?): Unfanged Vampire Part, Covenant Seal, Bubba Blister, Fang-tastic Chocolate Chip, Guardian Lucky Claw,
McGrubber's Burger (exportable), Vampire Dentist Relic, Soultwist Rune, The One (ultimate book?) ...

### 5.7 Rift-only items (examples)

| Item | Type | Notes |
|---|---|---|
| Rift Prism | Accessory (exportable) | Free Rift entry |
| Vampire Mask | Helmet (exportable) | Main-game use |
| Anti-Bite Scarf | Rift accessory | Vampire slayer |
| Crux Talisman / Ring / Artifact (?) | Rift accessory | Scales with Rift mob kills |
| Silkwire Stick, Leech Supreme, Nearly Coherent Rod (?) | Rift weapons | |
| Steak Stake, Holy Ice | Slayer tools | |
| Living Metal Suit / Anchor (?) | Rift armor | Living Cave |
| Wyld/Leech/? armors (?) | Rift armor | |
| McGrubber's Burger | Exportable consumable (?) | |

---

## 6. Misc progression

### 6.1 Bestiary

| Property | Value |
|---|---|
| Scope | **~360 mobs** in **~250 families**, grouped by island: Private Island, Hub, Farming Islands, Garden (pests), Spider's Den, The End, Crimson Isle, Deep Caverns, Dwarven Mines, Crystal Hollows, The Park, Moonglade Marsh, Torrhus Canyon, Critter Safari (Galatea biomes), Fishing, Catacombs, Mythological, Jerry, Kuudra, Slayer minis |
| Family tier | Each family levels from kills; each family has a **bracket** (1–8 (?)) that sets kill thresholds, and a **max tier** |
| Example bracket thresholds (confirmed snapshot) | Tier 1 kills: bracket 1 = 20, 2 = 40, 3 = 60, 4 = 100, 5 = 200, 6 = 400, 7 = 800, 8 = 1,400 — continuing through a top tier where bracket 1 reaches **1,000,000** cumulative kills |
| Per-tier rewards (cumulative example, standard family at Tier XX) | +70 Strength, +70 Magic Find (vs that mob), +40% coins (?) vs that mob, +10 XP orbs, +20 SkyBlock XP |
| Farming/Hunting families | Farming Fortune / Hunting Fortune instead (Pest bestiary up to **+102 FF**) |
| Milestones | Every 10 family tiers → +10 SkyBlock XP and +1 Health (?) (wiki: "Every 10 milestones grants +10 SkyBlock XP and +1 Health"); Combat XP lumps at milestones V through C+ ranging 5,000 → 1,000,000 Combat XP |

Clone default bracket kill table (cumulative kills to reach tier n, bracket 1) (?):
`[20, 40, 60, 100, 200, 400, 800, 1400, 2000, 3000, 6000, 12000, 20000, 30000, 40000, 50000, 60000, 72000, 86000, 100000, 200000, 400000, 600000, 800000, 1000000]`;
bracket k multiplies thresholds by `[1, 2, 3, 5, 10, 20, 40, 70][k-1]/1` (?) — or just store per-bracket arrays in data.

Bestiary GUI: SkyBlock Menu → Combat skill? / Bestiary in Your Skills → islands grid → families grid (mob heads) with
lore `Kills: 1,234 / Tier V → VI: 1,500`, progress bar `§2§m━━━━━━━━§f§m━━━━━━§r §e1,234§6/§e1,500`.

### 6.2 Museum

| Property | Value |
|---|---|
| Location | Hub Village (-73, 76, 80), curator **Madame Eleanor Q. Goldsworth III** |
| Unavailable on | Stranded, Bingo profiles |
| Categories | Combat (Weapons), Armor Sets, Farming, Mining, Fishing, Foraging, Dungeoneering, Hunting (skill-split, newer) + **Special Items** (no XP, max 48, not soulbound) — older layout: Weapons / Armor Sets / Rarities / Special Items |
| Reward per donation | **+1 to +25 SkyBlock XP** per item (Rogue Sword +1 … SOS Flare +25) |
| Soulbinding | First donation makes the item **Co-op Soulbound** |
| Retrieval | **Cannot be retrieved** (current) — items are effectively consumed into the museum (older versions allowed borrowing back) |
| Sets | Armor/equipment sets must be complete |
| Item metadata | Display shows creation date, donation date, and position number (<50,000); first-1,000 items show creation number |
| Milestones | Donation XP milestones grant extra rewards (SkyBlock XP, cosmetics, **Museum Display**/Armor stands, a talisman?) (?) |
| Appraisal | "Museum Value" appraisal — total coin value of donations (shown on profile viewers) (?) |

Museum GUI: 6-row; top row category tabs (sword, chestplate, hoe, pickaxe, rod, sapling, skull, bow…); each
category paginated with item icons: donated = real item, missing = gray dye `§cNot donated` with "Click to donate".

### 6.3 Harp (Melody)

| Property | Value |
|---|---|
| NPC / item | **Melody** (Hub, Wizard Tower area? (?)); buy **Melody's Harp** (~2,500 coins (?)) |
| Gameplay | 6-row GUI. 7 columns (?) of "lanes"; coloured notes (stained clay/wool) fall one row per beat from the top; the bottom row is a row of **quartz "keys"**; when a note reaches the key row the player must click that key. Misses fail the song (Perfect completions tracked) |
| Rewards | First completion of each song: **+Intelligence** (permanent) and SkyBlock XP; perfect all songs → **Melody's Hair** accessory (?) |

| Song (?) | Difficulty | Intelligence (?) |
|---|---|---|
| Hymn to the Joy | Easy | +1 |
| Frère Jacques | Easy | +1 |
| Amazing Grace | Easy | +1 |
| Brahms' Lullaby | Medium | +2 |
| Happy Birthday to You | Medium | +2 |
| Greensleeves | Medium | +2 |
| Geothermy? | Medium | +3 |
| Minuet | Hard | +3 |
| Joy to the World | Hard | +3 |
| Godly Imagination | Hard | +3 |
| La Vie en Rose | Expert | +3 |
| Through the Campfire | Expert | +? |
| Pachelbel | Expert | +? |

Total ≈ +26 Intelligence (?).

### 6.4 Abiphone

| Property | Value |
|---|---|
| What | An in-game phone item (right-click) that stores **NPC contacts**; calling a contact gives a service, dialogue, or quest |
| Models (?) | Abiphone X Plus (Special Edition), XI Ultra (Style), XII Mega (Color), XIII Pro (Giga), XIV Enormous (Black / Purple), Flip Dragon? — each bigger contact capacity and cosmetic case |
| Contacts | Added by talking to an NPC with the phone (some require conditions). Capacity grows by model (≈ 6 → 20 (?)) and **Operator Chips** (+1 each, up to 10 (?)) |
| Services (examples) | Call **Elizabeth** (Bits shop/Community Shop), **Trevor** (Trapper), **Kat** (pet upgrades), **Bingo**, **Dusk**, **Jacob** (contest info), **Ophelia** (dungeon shop), **Pesthunter** (?), **Rusty** (lost items) |
| Apps | Ringtones, Snake minigame, Contacts directory, Settings |
| Abicases | Phone cases from the Community Shop with small perks (e.g. +5% Bits?, -x% Abiphone cost) (?) |
| Missed calls | NPCs sometimes call you (Jerry, quests) |

### 6.5 Fairy Souls

| Property | Value |
|---|---|
| Total | **289** (current) |
| Exchange | Talk to **Tia the Fairy** (Hub, Wilderness/Fairy Pond) — exchange in groups of **5** |
| Reward per exchange | **+10 SkyBlock XP** (permanent stat rewards were removed in 0.15) |
| Backpack slot unlocks | After **5, 10, 20, 30, 40, 50, 65, 80, 95, 110, 125, 140, 155, 170, 185, 200, 220** souls (17 thresholds → backpack slots 2–18) |
| Pickup | Pink particle "fairy" head; right-click/walk into to collect; chat `§d§lSOUL! §bYou found a §dFairy Soul§b!` |

Per-island distribution (?) — clone layout target (sums to ~289):

| Island | Souls (?) |
|---|---|
| Hub | 81 |
| The Barn / Farming Islands | 7 |
| Mushroom Desert | 8 |
| Gold Mine | 12 |
| Deep Caverns | 21 |
| Dwarven Mines | 11 |
| Crystal Hollows | 0 |
| Spider's Den | 19 |
| The End | 12 |
| The Park | 11 |
| Crimson Isle | 29 |
| Jerry's Workshop | 5 |
| Dungeon Hub | 7 |
| Private Island | 0 |
| The Garden | 1 (?) |
| The Rift | 1 |
| Backwater Bayou | 3 (?) |
| Galatea | ~30 (?) |
| Dungeons (Catacombs) / other | remainder |

### 6.6 Hoppity / Chocolate Factory

(Added Spring 2024; the wiki page is marked "recently added or being updated" — treat as current but volatile.)

| Property | Value |
|---|---|
| Access | SkyBlock Menu → Calendar? / `/cf`, `/chocolatefactory`; an idle clicker in a chest GUI |
| Click | 1 Chocolate per click base, **Hand-Baked Chocolate** upgrade to 10/click; cost `500 × (L−1)` per level |
| Employees | Rabbit **Bro** +1 CpS/level, **Cousin** +2, **Sis** +3, **Daddy** +4, **Granny** +5, **Uncle** +6, **Dog** +7 |
| Rabbit Barn | Capacity for unique Chocolate Rabbits (persists through prestige); L2 costs 5,000 Chocolate |
| Time Tower | CF level 2+: 3 charges per 8 h; each a temporary multiplier (+0.1× → +1.5× at levels 1–15) |
| Rabbit Shrine | CF level 3+: +2% rare-rabbit odds per level (max +20% at 20) |
| Coach Jackrabbit | CF level 4+: +0.01× multiplier per level (max +0.2×) |
| Prestige | CF2 150M (1.1×), CF3 1B (1.25×), CF4 4B (1.5×), CF5 10B (2×), CF6 30B (2.5×) |
| Stray rabbits | 20% chance every 30 s while the GUI is open; clickable for 15 s; give 5–50 s of production; Golden Rabbit variants (Jackpot 480× CpS, Chocolate Mountain 1,500× CpS) |
| Hoppity's Hunt | Seasonal (SkyBlock **Spring**) egg hunt: **Chocolate Breakfast / Lunch / Dinner** eggs (plus Brunch / Déjeuner / Supper (?)) spawn on islands at set SkyBlock times; finding one grants a random **Chocolate Rabbit** (new unique → permanent CpS + multiplier; duplicate → chocolate) |
| Hitman | Stores missed eggs (up to 28 slots) |
| Chocolate Shop | Spend chocolate (lifetime-spent milestones) on accessories, items, cosmetics |
| Rabbit rarities (?) | Common (+1 CpS, +0.002×), Uncommon (+2, +0.003×), Rare (+4, +0.004×), Epic (+10, +0.005×), Legendary (+10, +0.02×), Mythic (unique ability each), Divine (event) |
| Max output | ~6,300 base CpS at CF6; ~45,840 CpS averaged with Time Tower cycles |

### 6.7 Century Cakes (confirmed)

| Property | Value |
|---|---|
| When | Every **100 SkyBlock years** (Century celebration); Simon NPC, Raffle of the Century, Century Cake Bundles, Gift Bags of the Century |
| Duration | **48 hours**, counts while offline; re-eating resets the timer |
| Stacking | Different cakes stack (one buff per cake type) |

| Cake | Bonus |
|---|---|
| Crab-Colored | +10 Health |
| Pet Rock | +3 Defense |
| Fruit | +2 Strength |
| Potato-Style | +10 Speed |
| Barry | +5 Intelligence |
| Sea Emperor | +1 Sea Creature Chance |
| Next Dungeon Floor | +1 Magic Find |
| Latest Update | +2 Ferocity |
| Streamer's | +1 Pet Luck |
| Chocolate | +5 Farming Fortune |
| Cloudy | +5 Foraging Fortune |
| Hype | +5 Mining Fortune |
| Undead | +1 Vitality |
| Not-a-lie | +1 True Defense |
| ekaC yrutneC tfiR | +10 Rift Time |
| Corpse-Flavored | +1 Cold Resistance |
| Seven Seas | +1 Treasure Chance |
| Starborn | +1 Hunting Fortune |
| Long-Expired | +5 Sweep |
| Hephaestus' | +1 Tracking |

(Older single "New Year Cake" — one per SkyBlock year, numbered by year, a collector item — is separate.)

### 6.8 Bits, Booster Cookies & the Community Shop

| Property | Value |
|---|---|
| Booster Cookie | From Gems: **1 = 325 Gems**, 6 = 1,950, 12 = 3,900; one free after Brigette's account-security quest |
| Cookie duration | **4 days** per cookie (stacks), counts offline |
| Cookie perks | +20% skill XP? (wisdom bonus +25 (?)), +15 Magic Find (?), keep coins on death, `/ah`, `/bz`, `/anvil`, `/et`, `/enchant`, `/wardrobe`? commands anywhere, AFK immunity, insta-sell, **Bits generation** |
| Bits accrual (?) | Each cookie adds **4,800 × Fame multiplier** to a "Bits Available" pool; the pool pays out **250 Bits per 30 minutes online** (?) while a cookie is active |
| Other Bits | Pests (5 each), Jacob's contests (40–80), visitors (occasional), fishing/garden events |
| Fame Rank | Rank from lifetime Bits/Gems spent (?): New Player 1×, Settler 1.1×, Citizen 1.2×, Contributor 1.3×, Philanthropist 1.4×, Patron 1.6×, Famous Player 1.8×, Attaché 1.9×, Ambassador 2×, Stateperson 2.04×, Senator 2.08×, Dignitary 2.12×, Councilor 2.16×, Minister 2.2×, Premier 2.22×, Chancellor 2.24×, Supreme 2.26× (?) |
| Shop NPC | **Elizabeth** (Hub, Community Center) — also via Abiphone |

#### Community Shop (Bits) — selected items

| Item | Bits |
|---|---|
| God Potion | **1,500** |
| Kismet Feather | **1,350** |
| Enrichments (Accessory Enrichment) | **5,000** |
| Pure Black / Pure White Dye | **250,000** |
| Accessory Enrichment Swapper | 200 (?) |
| Hologram | 2,000 (?) |
| Builder's Wand | 12,000 (?) |
| Block Zapper | 5,000 (?) |
| Jumbo Backpack | 4,000 (?) |
| Jumbo Backpack Upgrade | 4,000 (?) |
| Minion Storage X-pender | 1,500 (?) |
| Pocket Sack-in-a-Sack | 8,000 (?) |
| Dungeon / Rune / Flower / Dwarven / Crystal Hollows Sack | 14,000 each (?) |
| Expertise / Compact / Cultivating / Champion / Hecatomb (stacking enchant books) | 4,000 each (?) |
| Kat Flower / Kat Bouquet | 500 / 2,500 (?) |
| Heat Core | 3,000 (?) |
| Hyper Catalyst Upgrade | 300 (?) |
| Ultimate Carrot Candy Upgrade | 8,000 (?) |
| Colossal Experience Bottle Upgrade | 1,200 (?) |
| Matriarch's Perfume | 1,200 (?) |
| Inferno Fuel Block | 75 (?) |
| Autopet Rules 2-Pack | 21,000 (?) |
| Abicases | 7,500–? (?) |
| Speed Enhancer (?), Magma Fish? | (?) |
| Dimensional Infusion / Rift entry | 200 |

Community Shop tabs: **Upgrades** (Community Center account upgrades: Minion slots, Co-op slots, Guests count,
Island size, Coins allowance — paid in Gems/coins over real-time builds) / **Booster Cookies** (Gems) /
**Bits Shop** / **Fire Sales** (limited cosmetics) / **City Projects** (?) / **Ranks** (Gems).

---

## 7. Stat symbols & colours (needed by every UI below)

| Stat | Symbol | Colour | Stat | Symbol | Colour |
|---|---|---|---|---|---|
| Health | ❤ | `§c` red | Mining Speed | ⸕ | `§6` gold |
| Defense | ❈ | `§a` green | Mining Fortune | ☘ | `§6` gold |
| Strength | ❁ | `§c` red | Farming Fortune | ☘ | `§6` gold |
| Intelligence | ✎ | `§b` aqua | Foraging Fortune | ☘ | `§6` gold |
| Crit Chance | ☣ | `§9` blue | Pristine | ✧ | `§5` purple |
| Crit Damage | ☠ | `§9` blue | Sea Creature Chance | α | `§3` dark aqua |
| Bonus Attack Speed | ⚔ | `§e` yellow | Fishing Speed | ☂ | `§b` aqua |
| Speed | ✦ | `§f` white | Magic Find | ✯ | `§b` aqua |
| True Defense | ❂ | `§f` white | Pet Luck | ♣ | `§d` pink |
| Ferocity | ⫽ | `§c` red | Wisdom (any) | ☯ | `§3` dark aqua |
| Ability Damage | ๑ | `§c` red | Health Regen | ❣ | `§c` red |
| Vitality | ♨ | `§4` dark red | Mending | ☄ | `§a` green |
| Rift Time | ф | `§a` green | Hearts (Rift) | ❤ | `§c` red |
| Pests | ൠ | `§6`/`§4` | Swing Range | Ⓢ | `§e` |
| Cold Resistance | ❄ | `§b` | Bonus Pest Chance | ൠ | `§2` dark green |

Numbers are formatted with commas (`1,234,567`); large coin numbers in lore/sidebar often abbreviated
(`1.2M`, `34.5k`, `2.1B`) in specific contexts (AH prices, Bazaar, visitor rewards).

---

## 8. Sidebar scoreboard (line-by-line)

Exactly 15 lines max. Default overworld layout:

```
§e§lSKYBLOCK                      ← title; "SKYBLOCK CO-OP" on co-op profiles; ♲ for Ironman, Ⓑ Bingo, ☀ Stranded (?)
§710/02/26 §8m123AB               ← real date MM/DD/YY (gray) + server id (dark gray; m = mini, M = mega)
                                  ← blank
 Late Spring 14th                 ← §f season + day with ordinal (1st/2nd/3rd/4th…31st)
 §72:30pm §e☀                     ← time in 10-min steps; ☀ (yellow) day, ☽ (aqua) night
 §7⏣ §bVillage                    ← location: ⏣ + area name (coloured per island; ൠ appended on infested Garden plots)
                                  ← blank
Purse: §61,234,567 §e(+1,250)     ← "Purse" in white, value gold; "(+x)" yellow flash on gain; "Piggy:" if Piggy Bank
Bits: §b12,345 §3(+250)           ← only if > 0 or cookie active (?)
                                  ← blank
Objective                         ← quest objective header (white)
§eTalk to Jacob                   ← current objective (yellow)
                                  ← blank
§ewww.hypixel.net                 ← footer (yellow); clone uses its own domain
```

**Context-specific blocks** replace/insert lines between Purse/Bits and the footer:

| Context | Lines |
|---|---|
| Garden | `Copper: §c1,234`, `Sowdust: §2...` (?), `Plot - §b4 §4ൠ§7x2`, `Pests: §6...`, `Visitors: §b3` (?) |
| Jacob's Contest active | `Jacob's Contest` (yellow, top), `◆ §eWheat §a14m 32s`, ` Collected §e12,345`, ` §6GOLD §fwith §e10,234` (current bracket and threshold) |
| Contest upcoming | `Jacob's Contest` + `Starts in 3m 12s` + crop icons (?) |
| The Rift | Purse line becomes `Motes: §d1,234`; adds `§aф Left: §a7m 43s` (Rift time) (?) |
| Dwarven Mines | `Mithril Powder`, `Gemstone Powder`, `Commissions` list |
| Crimson Isle | `Reputation`, faction, `Kuudra` timer |
| Dungeon | Cleared %, time, team list with HP |
| Slayer quest | `Slayer Quest`, `§cRevenant Horror IV`, `§e(1,234/2,400) §cCombat XP` → `Slay the boss!` |
| Events | `Spooky Festival 12:34`, `Election Booth Opens` , `Traveling Zoo`, `Dark Auction` countdowns |
| Cookie Buff | `Cookie Buff` lines (?) |

Date system: 12 months — **Early Spring, Spring, Late Spring, Early Summer, Summer, Late Summer, Early
Autumn, Autumn, Late Autumn, Early Winter, Winter, Late Winter** — each **31 days**. **1 SkyBlock day = 20
real minutes** (so 1 SB hour = 50 real seconds; time shown in 10-SB-minute steps = every 8.33 real s). **1 SB year
= 372 days = 124 real hours (5 d 4 h)**. Day/night: day 6:00am–7:00pm (?).

---

## 9. Action bar

Default (overworld), centred above the hotbar, refreshed every tick-ish:

```
§c1,234/1,234❤     §a567§a❈ Defense     §b850/850✎ Mana
```

| Situation | Middle segment replaced by |
|---|---|
| Skill XP gained | `§3+12.5 Farming (1,234,567/3,600,000)` (or `(34.5%)` with a setting) — for ~3 s |
| Ability used | `§b-150 Mana (§6Instant Transmission§b)` |
| Not enough mana | `§c§lNOT ENOUGH MANA` |
| Absorption | Health shows gold: `§61,434/1,234❤` |
| Overflow/special | `§6⚔ 3 Combo` etc. per item |
| Rift | `§c4/5❤     §a7m 43sф Left     §b200/200✎ Mana` (?) |
| Drill / fuel | `§2⛏ Drill Fuel: 12,345/20,000` appended (?) |
| Sacks | Nothing — sacks report in chat every 30 s |
| Pet XP | not shown |
| Low health | Health segment flashes |

Effective Health / Mana values show current/max; max mana = 100 + Intelligence.

---

## 10. Tab list (widget layout)

Hypixel's modern tab list is **4 columns × 20 rows** of "widgets" (player list + configurable info widgets,
managed via `/widgets` (?)). Typical layout:

| Column 1 | Column 2 | Column 3 | Column 4 |
|---|---|---|---|
| `§a§lPlayers §f(24)` then player names `[312] §bTutut §6⚒` (SB level in brackets, emblem) | more players | `§3§lInfo` | `§6§lAccount Info` |
| … | … | `Area: §bHub` | `Profile: §aMango §7(Co-op)` |
| … | … | `Server: §8m123AB` | `SB Level: §8[§b312§8]` |
| … | … | `Gems: §a1,234` | `Bank: §61.2B§7/§62.5M` (bank/personal) |
| … | … | `Fairy Souls: §d240§7/§d289` | `Interest: §e12h 30m` |
| … | … | blank | `§e§lSkills:` |
| … | … | `§e§lStats:` | ` Farming 60: §aMAX` |
| … | … | ` Speed: §f✦412` | ` Combat 52: §a34.5%` |
| … | … | ` Strength: §c❁350` | blank |
| … | … | ` Crit Chance: §9☣65%` | `§e§lEvent:` |
| … | … | ` Crit Damage: §9☠245%` | ` Spooky Festival: §e2d 3h` |
| … | … | ` Attack Speed: §e⚔30%` | `§e§lPet:` |
| … | … | blank | ` §8[Lvl 100] §6Elephant` |
| … | … | `§e§lCookie Buff` | ` §7XP: §bMAX LEVEL` |
| … | … | ` §f2d 14h 3m` | `§e§lActive Effects` ` Speed VIII 3:12` |

**Garden-specific widgets:** `Garden Level: §eXII` (+progress), `Copper: §c1,234`, `Pests: Alive §6ൠ 3` + `Infested Plots: 4, 7`,
`Visitors (3): Jacob, Fear Mongerer, Tammy`, `Composter: §aTime Left: 4m 12s` / `Organic Matter`, `Fuel`, `Jacob's Contest: Wheat, Carrot, Cactus (§e12m)`,
`Crop Milestones: Wheat 34 (48%)`, `Pest Traps`, `Greenhouse: Ready` (?).

Header/footer text: header `§bYou are playing on §e§lMC.HYPIXEL.NET`; footer shows active effects count, cookie buff,
`§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET`.

---

## 11. Menus (chest GUIs)

General conventions (apply to every SkyBlock GUI):

| Convention | Rule |
|---|---|
| Size | Almost always **6 rows (54 slots)**; small dialogs 3–4 rows |
| Filler | **Black stained glass panes** (`§r` blank name) everywhere not functional; gray panes in some sub-menus |
| Navigation | **Go Back** = arrow at slot **48** (lore `§7To <previous menu>`); **Close** = barrier at slot **49**; prev/next page = arrows at **45** / **53** |
| Titles | Plain text, e.g. `SkyBlock Menu`, `Your Skills`, `Farming Skill`, `Collections`, `Pets (1/3)` |
| Lore last line | Call to action: `§eClick to view!`, `§eClick to open!`, `§eClick to purchase!`, `§cYou don't have enough Copper!` |
| Disabled feature | Item shown with `§cLocked` / red text explaining requirement |
| Sound | Click sounds on every action; "villager no" on fail |
| Shift-click | Moves stacks into storage-style menus |

### 11.1 SkyBlock Menu (Nether Star, hotbar slot 9; `/sbmenu`)

Confirmed layout (2026; the profile button was renamed **"Stats & Equipment"** on 2026-07-22):

```
 slot:   0   1   2   3   4   5   6   7   8
 row0:  [ ] [ ] [ ] [ ] [ ] [ ] [ ] [ ] [ ]
 row1:  [ ] [ ] [ ] [ ] [ST][ ] [ ] [ ] [ ]      13 = Stats & Equipment (player head)
 row2:  [ ] [SK][CO][RB][LV][QU][CA][SG][ ]      19–25
 row3:  [ ] [ ] [BG][PE][CT][WA][BK][ ] [ ]      29–33
 row4:  [ ] [ ] [ ] [ ] [ ] [ ] [ ] [ ] [ ]
 row5:  [ ] [ ] [FT][PM][XX][SE][BC][ ] [ ]      47–51
```

| Slot | Icon | Name | Opens / does |
|---|---|---|---|
| 13 | Player head | **Stats & Equipment** (formerly "Your SkyBlock Profile") | Stat categories + equipment |
| 19 | Diamond Sword | **Your Skills** | Skills menu; lore shows Skill Average |
| 20 | Painting | **Collections** | Collections menu; lore `Collections Unlocked: 72.5%` |
| 21 | Book | **Recipe Book** | Recipe book; lore `Recipe Book Unlocked: 45.3%` |
| 22 | SkyBlock XP item (green bottle) | **SkyBlock Leveling** | Level, tasks, emblems; lore `Your SkyBlock Level: [312]` + progress |
| 23 | Book and Quill | **Quests & Chapters** (Quest Log) | Active/completed quests |
| 24 | Clock | **Calendar and Events** | Calendar with upcoming events, current SB date |
| 25 | Chest | **Storage** | Ender Chest + Backpacks |
| 29 | Accessory-bag skull (leather bag) | **Your Bags** | Accessory Bag, Potion Bag, Fishing Bag, Quiver, Sacks, Sack of Sacks |
| 30 | Bone | **Pets** | Pets menu (appears after first pet) |
| 31 | Crafting Table | **Crafting Table** | Portable 3×3 crafting + Quick Crafting slots |
| 32 | Leather chestplate (dyed) | **Wardrobe** | Wardrobe |
| 33 | Gold-ish skull / chest | **Personal Bank** | Bank (when Personal Bank item unlocked) |
| 47 | Map / compass skull | **Fast Travel** | Fast Travel menu |
| 48 | Name Tag | **Profile Management** | Switch/create profiles (5 max (?)) |
| 49 | Barrier | **Close** | |
| 50 | Redstone Torch | **Settings** | Settings |
| 51 | Cookie | **Booster Cookie** | Cookie status; Community-Shop shortcuts (only when active) |

Older/extra entries seen in other versions (clone can include): **Trades** (emerald, villager trades), **Museum**,
**Island Management** (private-island settings), **Active Effects** (potion list), **Bazaar shortcut**.

### 11.2 Stats & Equipment (profile)

6 rows (?):

| Slot (?) | Item |
|---|---|
| 1, 10, 19, 28 | Equipped **Helmet, Chestplate, Leggings, Boots** (or empty-slot pane `§cEmpty Helmet Slot`) |
| 2, 11, 20, 29 | **Equipment**: Necklace, Cloak, Belt, Gloves/Bracelet |
| 47 | Active **Pet** |
| 4 | Player head (name, SkyBlock level, rank) (?) |
| 13 | **Combat Stats** (diamond sword) |
| 14 | **Gathering Stats** (iron pickaxe / gold hoe) |
| 15 | **Wisdom Stats** (enchanted book) |
| 16 | **Misc Stats** (clock / feather) |
| 22–25 | Category breakdown entries (?) |
| 48 / 49 | Go Back / Close |

Category lore example (Combat):
```
§cCombat Stats
§7Gives you a better chance at fighting
§7strong monsters.
§r
§c ❤ Health §f1,234
§a ❈ Defense §f567
§c ❁ Strength §f350
§b ✎ Intelligence §f412
§9 ☣ Crit Chance §f65%
§9 ☠ Crit Damage §f245%
§e ⚔ Bonus Attack Speed §f30%
§c ๑ Ability Damage §f12.5%
§f ❂ True Defense §f45
§c ⫽ Ferocity §f12
§4 ♨ Vitality §f100
§a ☄ Mending §f100
§c ❣ Health Regen §f120
§r
§eClick for stat breakdown!
```
Clicking a stat → **stat breakdown** sub-menu listing every source (Base, Skills, Armor, Pet, Accessories, Potion,
Fairy/Bestiary, Cake, etc.) with its contribution, plus caps (e.g. Speed cap 400/500/600 with Cheetah/Black Cat).

Gathering lore: Mining Speed, Mining Fortune, Farming Fortune, Foraging Fortune, Pristine, Breaking Power, Fishing Speed,
Sea Creature Chance, Treasure Chance, Bonus Pest Chance, Hunting Fortune. Wisdom lore: each `X Wisdom ☯`.
Misc: Speed, Magic Find, Pet Luck, Cold Resistance, Swing Range, Rift Time (in Rift).

### 11.3 Skills menu ("Your Skills")

| Slot (?) | Item | Notes |
|---|---|---|
| 4 | Diamond Sword "Your Skills" | `Skill Average: 52.4` + description |
| 19 | Golden Hoe | Farming |
| 20 | Stone Pickaxe | Mining |
| 21 | Stone Sword | Combat |
| 22 | Jungle Sapling | Foraging |
| 23 | Fishing Rod | Fishing |
| 24 | Enchanting Table | Enchanting |
| 25 | Brewing Stand | Alchemy |
| 29 | Crafting Table | Carpentry |
| 30 | Magma Cream | Runecrafting (`§8(Cosmetic)`) |
| 31 | Emerald | Social (`§8(Cosmetic)`) |
| 32 | Spawn Egg / bone | Taming |
| 33 | Lead (?) | Hunting |
| 34 | Wither skull (?) | Dungeoneering → opens Catacombs stats |
| 48 / 49 | Go Back / Close | |

Skill icon lore:
```
§aFarming XXIV
§7Harvest crops and shear sheep to earn
§7Farming XP!
§r
§7Progress to Level XXV: §e34.5%
§2§m              §f§m          §r §e1,234,567§6/§e3,600,000
§r
§7Level XXV Rewards:
§r  §eFarmhand XXV
§r    §fGrants §a+100 §6☘ Farming Fortune§f, which
§r    §fincreases your chance for multiple crops.
§r  §8+§a2 §c❤ Health
§r  §8+§b5 SkyBlock XP
§r  §8+§620,000 §7Coins
§r
§eClick to view!
```
Progress bar: 20 characters; `§2§m` (dark green strikethrough spaces) for filled, `§f§m` for empty (?).

**Single-skill page** (e.g. `Farming Skill`): 6 rows; levels laid out as a **zig-zag "snake"** of panes running
down column 0, along, up column 2, etc. Each level is a stained-glass pane: **lime = reached, yellow = in progress,
red = locked**; every 5th level shown with a distinct item (?). Hovering shows that level's rewards. Two pages
(1–25, 26–50/60). Slot 0 = skill icon with summary; 48/49 back/close; 50 next page.

Clone default snake order for 25 levels (?): `9,18,27,28,29,20,11,2,3,4,13,22,31,32,33,24,15,6,7,8,17,26,35,44,53`.

### 11.4 Collections menu

| Slot (?) | Item | Opens |
|---|---|---|
| 4 | Painting "Collections" | Overall % unlocked |
| 20 | Golden Hoe | **Farming Collections** |
| 21 | Stone Pickaxe | **Mining Collections** |
| 22 | Stone Sword | **Combat Collections** |
| 23 | Jungle Sapling | **Foraging Collections** |
| 24 | Fishing Rod | **Fishing Collections** |
| 25 | Mycelium / Rift item | **Rift Collections** (?) |
| 31 | Wither Skull | **Boss Collections** (dungeon bosses) |
| 48 / 49 | Go Back / Close | |

Category page: one icon per collection item (unknown items shown as gray dye `§c???` "Find this item to add it to
your collection!"). Lore:
```
§eWheat VII
§7View all your Wheat Collection
§7progress and rewards!
§r
§7Progress to Wheat VIII: §e45.2%
§2§m        §f§m            §r §e22,600§6/§e50,000
§r
§7Co-op Contributions:
§r §bTutut§7: §e18,000
§r §aMango§7: §e4,600
§r
§7Wheat VIII Rewards:
§r  §aEnchanted Hay Bale Recipe
§r  §8+§b4 SkyBlock XP
§r
§eClick to view!
```
Tier page: row of tiers I…max as items/dyes (lime dye = unlocked, yellow = current, gray = locked) with rewards lore.

### 11.5 Recipe Book

| Element | Behaviour |
|---|---|
| Root | Category icons by skill/collection (Farming, Mining, Combat, Fishing, Foraging, Enchanting, Alchemy, Carpentry, Slayer, Special) |
| Category page | Grid of craftable items; locked recipes are gray dye with `§cLocked` + `Requires Wheat Collection VI` |
| Recipe view | 3×3 grid at slots **10, 11, 12, 19, 20, 21, 28, 29, 30**, arrow at **23**, result at **25**, "Supercraft" (crafting table) button at **32** (?) which crafts directly from inventory + sacks |
| Lore of result | Full item lore + `§eClick to view recipe!` |

### 11.6 Storage

Title `Storage`, 6 rows (?):

| Slots | Content |
|---|---|
| 4 | Ender Chest header (ender chest item) `§aEnder Chest` |
| 9–17 | **Ender Chest pages 1–9** — purple stained glass `§aEnder Chest Page 1` (`§eLeft-click to open!`); locked pages = black/gray pane `§cLocked Page` + unlock requirement |
| 22 | Backpacks header (chest) `§aBackpacks` |
| 27–44 | **Backpack slots 1–18** — the equipped backpack's head icon; empty slot = brown stained glass `§eEmpty Backpack Slot` "Drop a backpack here"; locked = gray pane `§cLocked Backpack Slot` `Requires N Fairy Souls` |
| 48 / 49 | Go Back / Close |

| Item | Value |
|---|---|
| Ender chest page size | 45 slots (5 rows) + nav row |
| Ender chest pages | 9; first pages free, further pages unlocked by SkyBlock Level rewards / ranks (?) |
| Backpack sizes | Small **9**, Medium **18**, Large **27**, Greater **36**, Jumbo **45** |
| Backpack slots | 18, unlocked by Fairy Soul thresholds (§6.5); slot 1 free (?) |
| Backpack page nav | Inside a page, bottom row has buttons to jump to other pages (?) |
| Commands | `/ec <n>`, `/bp <n>`, `/storage` |

### 11.7 Wardrobe

Title `Wardrobe (1/2)`, 6 rows:

| Rows | Content |
|---|---|
| Row 0 (slots 0–8) | Helmets of wardrobe slots 1–9 (or gray pane `Slot N: Empty`) |
| Row 1 (9–17) | Chestplates |
| Row 2 (18–26) | Leggings |
| Row 3 (27–35) | Boots |
| Row 4 (36–44) | **Slot buttons**: dye — `§aSlot 1: Equipped` (lime), `§eSlot 2: Ready` (pink/purple (?)), `§7Slot 3: Empty` (gray), `§cSlot 4: Locked` (red, rank requirement) |
| Row 5 | 45 prev page, 48 Go Back, 49 Close, 53 next page |

| Property | Value |
|---|---|
| Slots | 18 (2 pages × 9) |
| Unlock (?) | Default 2; VIP 5; VIP+ 7; MVP 9; MVP+ 18 (?) — clone: tie to SkyBlock level instead (non-P2W rule) |
| Click | Swaps the full set; armor moved is stored in wardrobe (not inventory) |
| Commands | `/wardrobe` (cookie or rank) |

### 11.8 Your Bags

| Slot (?) | Bag | Unlock (?) | Capacity |
|---|---|---|---|
| 19 | **Accessory Bag** | Redstone Collection II | 3 → up to ~102 slots via Redstone collection + Jacob/visitor/Rift upgrades (?) |
| 20 | **Potion Bag** | Nether Wart collection (?) | 9 → 45 |
| 21 | **Fishing Bag** | Raw Fish collection (?) | 9 → 45 |
| 22 | **Quiver** | String collection (?) / always (?) | 27 → 45 (arrows only) |
| 23 | **Sack of Sacks** | Recipe | Holds sacks |
| 24 | **Sacks** | Sacks you own | Per-item capacity |
| 48 / 49 | Go Back / Close | | |

#### Accessory Bag

| Property | Value |
|---|---|
| GUI | `Accessory Bag (1/3)` — 45 slots per page, bottom nav: 45 prev, 48 back, 49 close, **50 Accessory Bag Thaumaturgy** (Tuning + Power), 53 next |
| Duplicates | Same accessory family counts once (highest tier) |
| **Magical Power** by rarity | Common **3**, Uncommon **5**, Rare **8**, Epic **12**, Legendary **16**, Mythic **22**, Special **3**, Very Special **5** |
| Special cases | Hegemony Artifact counts double; Abicase MP = contacts/2 (?); Rift Prism +11 MP |
| Power (Maxwell) | Selected power gives stats × `29.97 × (ln(0.0019·MP + 1))^1.2` (?) + unique bonus |
| Tuning | 1 tuning point per 10 MP; allocate to stats (e.g. +1 Strength per point, +1 Health? per point, varies) |
| Enrichments | +1 stat per accessory (Legendary+ only), applied via Bits-shop Enrichment |

#### Sacks

| Property | Value (?) |
|---|---|
| Types | Agronomy (farming), Husbandry (animal), Mining, Combat, Foraging, Fishing, Slayer, Dungeon, Rune, Flower, Candy, Gemstone, Dwarven, Crystal Hollows, Nether, Garden? |
| Sizes | Small (640/item), Medium (2,240/item), Large (20,160/item); Enchanted Small/Medium/Large (?) |
| Pickup | Items go straight into the sack if a sack holds that item type |
| Chat | Every 30 s: `§6[Sacks] §a+1,234 §eitems§e. §8(Last 30s.)` — hover lists items; also `§c-56` for removals |
| GUI | Each item shows `Stored: 12,345/20,160` and click to withdraw a stack / right-click all |
| Commands | `/sacks`, `/gfs <item> <amount>` (get from sacks) |

### 11.9 Pets menu

Title `Pets (1/3)`, 6 rows:

| Slots | Content |
|---|---|
| 4 | Bone "Pets" — `Pet Score: 312` (+ Magic Find from score), summary, active pet |
| 10–16, 19–25, 28–34, 37–43 | Pet items (28 per page) — lore shows level, stats, abilities, `Progress to Level 85: 34%`, `§eLeft-click to summon!`, `§eRight-click to convert to item` |
| 45 | Previous page |
| 47 | Pet Score Rewards (?) |
| 48 | Go Back |
| 49 | Close |
| 50 | **Convert Pet to an Item** toggle (dye on/off) |
| 51 | **Hide Pets** toggle |
| 52 | **Autopet Rules** |
| 53 | Next page |

Pet item name format: `§7[Lvl 100] §6Elephant` (name coloured by rarity); summoned pet lore has `§a§lSUMMONED` / `§cClick to despawn!`.

Chat:
```
§aYou summoned your §6Elephant§a!
§aYou despawned your §6Elephant§a!
§aYour §6Elephant §aleveled up to level §9100§a!
```

### 11.10 Fast Travel

Title `Fast Travel`, 6 rows; each island is a skull/icon. **Left-click** = warp to the island spawn, **right-click** =
open that island's sub-warps (if any). Locked islands show `§cRequires the Travel Scroll to X` or a progression
requirement.

| Slot (?) | Island | Sub-warps / requirement |
|---|---|---|
| 10 | Private Island | `/is` |
| 11 | **Hub** | Village, Castle (scroll), Crypts (scroll), Museum (scroll), Da (Dark Auction) (scroll), Wizard Tower? |
| 12 | Dungeon Hub | Catacombs Entrance? |
| 13 | **The Barn** | Farming Islands |
| 14 | **The Garden** | SkyBlock Level 5; Plot warps? |
| 15 | Mushroom Desert (?) | scroll |
| 16 | **The Park** | Jungle (scroll), Howling Cave (scroll) |
| 19 | **Gold Mine** | |
| 20 | **Deep Caverns** | |
| 21 | **Dwarven Mines** | Forge, Base Camp (scrolls) |
| 22 | **Crystal Hollows** | Nucleus (scroll) |
| 23 | **Spider's Den** | Top of Nest (scroll), Arachne's Sanctuary (?) |
| 24 | **The End** | Dragon's Nest (scroll), Void Sepulture |
| 25 | **Crimson Isle** | Kuudra, Smoldering Tomb, Forge? (scrolls) |
| 28 | **Jerry's Workshop** | Only during Season of Jerry (Late Winter) |
| 29 | Backwater Bayou | (?) |
| 30 | Galatea | (?) |
| 31 | The Rift | Requires infusion (?) — typically not on fast travel |
| 48 / 49 / 50 | Back / Close / Paper "Island Browser"? | |

Lore: `§aHub`, `§7/warp hub`, `§r`, `§eLeft-click to warp!`, `§eRight-click to choose a warp!`.

### 11.11 Settings

Title `Settings`, 6 rows, categories (?):

| Category icon | Contains |
|---|---|
| Personal | Player list visibility, Chat filters, Damage indicators, Show stats on items, Sound toggles |
| Comms (Abiphone) | Allow calls, DND |
| Item Pickup | Auto-pickup, sack pickup message frequency, "Sacks message" on/off |
| Notifications | Skill XP action bar format (amount vs %), Pest warnings, Visitor arrival message |
| Combat | Show damage numbers, Hit indicators |
| Mining / Fishing / Farming | Island-specific toggles (e.g. "Garden: Show visitor list", "Pests: warn when 4+") |
| Profile | Co-op settings, Ironman toggles, Guest permissions |
| Island | Private island visitor permissions, PvP, guest limits |
| Bank | Bank notifications |
| Language | (?) |
| 48 / 49 | Back / Close |

Each setting is a pair: icon (slot n) + toggle dye below/next to it (`§aEnabled` lime dye / `§cDisabled` gray dye), click to flip.

### 11.12 Calendar

`Calendar and Events`: 6 rows. Current month shown as a grid of 31 days (clock/paper items), today highlighted;
upcoming events (Spooky Festival in Autumn 29–31, Season of Jerry Late Winter 24–26, Traveling Zoo, Election,
Hoppity's Hunt in Spring, Fishing Festival (with Marina), Mining Fiesta (Cole), Jacob's Contests (every 3 days), Dark
Auction (every SB hour 55?), New Year Celebration Late Winter 29–31, Century celebration). Lore shows "Starts in: 2h 13m".

### 11.13 The Desk (Garden) — `/desk`

Title `Desk`, 6 rows (?):

| Slot (?) | Item | Opens |
|---|---|---|
| 4 | Grass block "The Garden" | Garden level, XP progress, copper, rewards next level |
| 10 | Map | **Configure Plots** (5×5 plot picture; buy / rename / teleport) |
| 11 | Wheat | **Crop Milestones** |
| 12 | Gold hoe | **Crop Upgrades** |
| 13 | Composter skull | **Composter Upgrades** |
| 14 | Villager head | **Visitor's Logbook** / Visitor milestones |
| 15 | Emerald | **SkyMart** |
| 16 | Painting | **Barn Skins** / Greenhouse skins |
| 20 | Ender pearl / compass | **Teleport to Plot** |
| 21 | Vacuum | **Pests** (infested plots, pest bestiary shortcut, repellent status) |
| 22 | Glass | **Greenhouse** (after Garden VII + blueprint) |
| 23 | Book | **Garden Levels** (rewards per level) |
| 48 / 49 | Back / Close | |

**Configure Plots** GUI: the 5×5 plot map drawn in slots `2–6, 11–15, 20–24, 29–33, 38–42` with the Barn at **22**.
Owned plots = grass block named `Plot - 4` (`§eClick to teleport!`, `§eRight-click to configure!`); purchasable =
`§aClick to unlock` with `Cost: 16 Compost`; locked = barrier `§cRequires Garden III`.

### 11.14 Composter GUI

| Slot (?) | Content |
|---|---|
| 13 | Composter info — time left, compost waiting, `§eClick to collect!` |
| 10–12, 19–21 (?) | Organic Matter gauge (green panes filling) + "Insert crops from inventory" button |
| 14–16, 23–25 (?) | Fuel gauge (oil/brown panes) + "Insert fuel" button |
| 31 | Compost output stack |
| 49 | Close |

### 11.15 Chat message formats

**Skill level up**
```
§3§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
 §b§lSKILL LEVEL UP §3Farming §824➜§325
 
 §a§lREWARDS
  §eFarmhand XXV
   §fGrants §a+100 §6☘ Farming Fortune§f, which
   §fincreases your chance for multiple crops.
  §8+§a2 §c❤ Health
  §8+§620,000 §7Coins
  §8+§b5 SkyBlock XP
§3§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
```
(Level numbers are Roman numerals by default; a setting switches to Arabic.)

**Collection level up**
```
§e§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
 §6§lCOLLECTION LEVEL UP §eWheat §8VI➜§eVII
 
 §a§lREWARDS
  §aEnchanted Hay Bale §7Recipe
  §8+§b4 SkyBlock XP
§e§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
```
Unlocking a brand-new collection item: `§6§lCOLLECTION UNLOCKED §eSugar Cane`.

**SkyBlock level up**
```
§3§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
 §bSkyBlock Level Up §8[§b120§8] ➜ §8[§b121§8]
 
 §a§lREWARDS
  §8+§c5 §c❤ Health
  §8+§a1 §c❁ Strength (every 5 levels)
§3§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
```
SkyBlock XP gain (small): `§b+5 SkyBlock XP §7(Garden Plot Unlocked)§b` (?).

**Rare drops** (tier word by drop chance; Magic Find shown when it applied):

| Chance band (?) | Prefix |
|---|---|
| ≤ 5% | `§6§lRARE DROP! ` |
| ≤ 1% | `§9§lVERY RARE DROP! ` |
| ≤ 0.1% | `§5§lVERY RARE DROP! ` (purple) / `§d§lCRAZY RARE DROP! ` |
| ≤ 0.01% | `§d§lCRAZY RARE DROP! ` |
| ≤ 0.001% (?) | `§c§lINSANE DROP! ` / `§d§lPRAY RNGESUS DROP! ` (slayer RNG meter style) |
| Slayer | `§b§lRARE DROP! ` … `§d§lCRAZY RARE DROP! (Warden Heart) §b(+250% ✯ Magic Find)` |

```
§6§lRARE DROP! §9Enchanted Book §b(+120% ✯ Magic Find)
§9§lVERY RARE DROP!  §5Beady Eyes §b(+95% ✯ Magic Find)
```
**Pet drop:**
```
§6§lPET DROP! §6Rat §b(+300% ✯ Magic Find)
§6§lPET DROP! §5Slug §b(+210% ✯ Magic Find)
```
**Garden / farming**
```
§6§lYUCK! §7A §6ൠ Pest §7has appeared in §aPlot §7- §b4§7!
§a§lGARDEN MILESTONE §eWheat §838➜§e39
§r  §a§lGARDEN LEVEL UP §2Garden §8XI➜§2XII
§a§lOFFER ACCEPTED §8with §aJacob §8(§fUNCOMMON§8)
§a[NPC] §aJacob§f: Thanks! Here, have some §cCopper§f!
§aA new visitor has arrived at your §2Garden§a: §fFear Mongerer§a!
§e[NPC] Jacob§f: §bThe Farming Contest is starting in 5 minutes!
§2§lCOMPOSTER! §7Your Composter has produced §a5 Compost§7!   (?)
```
**Misc**
```
§d§lSOUL! §bYou found a §dFairy Soul§b!
§6§lBOOSTER COOKIE! §7...                                     (?)
§aYou have §d1 §aunclaimed Bits!                                  (?)
§b+250 Bits from Cookie Buff! §8(4,550 remaining)               (?)
§aYour §ePotion of Speed VIII §aeffect has ended.               (?)
§eYou drank a §aSpeed VIII Potion§e!                           (?)
```
NPC dialogue format: `§e[NPC] Name§f: message` (NPC name yellow, text white); multi-line dialogues stagger ~1 s per line;
clickable choices `§e§l[YES]` / `§c§l[NO]` (?).

Party/guild formats belong to the social file.

---

## 12. How it connects

### 12.1 Resource-flow map

```
             ┌────────────── Farming XP ──────────────┐
             │                                          ▼
 [Break crops] ──drops×fortune──► [Crops] ──► Bazaar/NPC coins
      │   │                          │  │
      │   │                          │  └──► Enchanted crops ──► Visitors' requests ──► COPPER ──► SkyMart / Crop Upgrades
      │   │                          │                               │                              (+Crop Fortune)
      │   │                          │                               └──► Garden XP ──► Garden Level ──► crops, plots tiers,
      │   │                          │                                                                 visitors, crop growth
      │   │                          └──► Composter (Organic Matter) + Fuel ──► COMPOST ──► Plots (+3 FF, +5 SB XP each)
      │   └──► Crop Milestones ──► Garden XP, Dedication enchant
      │
      ├──► 0.2%/crop ──► PESTS ──► (vacuum) ──► FF penalty removed, 1,500 Farming XP, Bits, spray mats, pets, books
      │                                              └──► Pesthunter Phillip gear ──► shorter pest cooldown
      └──► Jacob's Contest ──► medals/tickets ──► Anita (+FF tiers), farming cap 50→60, Turbo-Crop, contest drops ──► armor
```

### 12.2 Cross-system links (table)

| From | To | Link |
|---|---|---|
| Farming collections | Recipe Book | Enchanted crops, hoes, armor recipes unlock at collection tiers |
| Farming collections | Alchemy | Sugar Cane → Speed, Melon → Healing, Cactus → Resistance, Cocoa → Adrenaline, Nether Wart → every potion base |
| Garden | SkyBlock XP | Garden levels (+10 each), plots (+5 each), crop upgrades (+1 each), milestones, visitor milestones |
| Garden visitors | Every island | Visitor requirements gate on having met NPCs elsewhere → Garden rewards exploration |
| Pests | Bestiary | Pest families feed the Garden bestiary (+102 FF) |
| Jacob's Contest | Farming skill cap | Gold medals → cap 50→60 |
| Alchemy | Combat / Dungeons / Mining | Potions are a core combat buff (Strength/Crit/Speed) and Haste/Spelunker for mining |
| Enchanting | Every gear item | Enchants are the biggest flat stat block on weapons/tools |
| Experimentation Table | Enchanting XP, books | Daily minigame → Titanic/Colossal bottles + rare books |
| Rift | Main game | Exportable items (Rift Prism +11 MP, Vampire Mask), Rift Fairy Soul, Century cake Rift Time, Rift motes nothing out |
| Fairy Souls | Storage | Backpack slots |
| Museum | SkyBlock XP | Donations |
| Bestiary | Combat stats | Strength/MF per family; Health per milestone |
| Bits | God Potion, Kismet, Enrichments, stacking enchants (Cultivating!) | Cookie → Bits → shop items that feed farming and combat |
| Harp | Intelligence | Permanent INT |
| Chocolate Factory | Chocolate shop accessories | MP via accessories (?) |
| Century Cakes | All stats | 48 h buffs |

### 12.3 SkyBlock XP feed (for the leveling file)

| Source | SB XP (confirmed / ?) |
|---|---|
| Garden level | +10 per level (140 at XV) |
| Garden plot | +5 per plot (120 for 24) |
| Crop upgrade tier | +1 each (117 for 13×9) |
| Fairy Soul exchange | +10 per 5 souls (~570 for 285) |
| Museum donation | +1 to +25 per item |
| Bestiary | +10 per 10 family tiers (?) / per-tier SB XP |
| Harp songs | SB XP per song (?) |
| Timecharms / Enigma souls | SB XP each (?) |
| Visitor milestones | SB XP per tier |
| Crop milestones | SB XP on some tiers (?) |
| Skill levels | +5 per level (1–10), +10 (11–25), +20 (26–50), +30 (51–60) (?) |
| Collection tiers | +4 per tier (?) |

---

## 13. Implementation notes (for build agents)

### 13.1 Data-first

Everything above is **tables** — implement them as data files, never as code branches:

| Data file (suggested) | Rows | Key fields |
|---|---|---|
| `crops.json` | 13 Garden crops + non-Garden farming items | id, gardenLevel, baseDropsMin/Max, farmingXp, pestId, organicMatter, milestoneMultiplier, toolLine |
| `garden_levels.json` | 15 | xp, rewards (crop, visitors, cropUpgradeTier, sbXp, cropGrowth, barnSkin) |
| `plots.json` | 24 | id, group, gridX, gridY |
| `plot_costs.json` | 4 groups | gardenLevel, costs[] (compost or bundles) |
| `crop_upgrades.json` | 9 | gardenLevel, copper |
| `crop_milestones.json` | 46 tiers × multipliers | thresholds, gardenXp |
| `visitors.json` | ~140 | id, name, skin, rarity, gardenLevel, requirements[], requestPool[], bonusDrops[] |
| `composter_upgrades.json` | 5 × ≤25 | perTierEffect, gardenLevel, costs[] |
| `organic_matter.json` / `fuel.json` | items | value |
| `pests.json` | 15 | crop, gardenLevel, sprayMaterial, drops[] (chance, item) |
| `pest_penalty.json` | 6×9 matrix | as §1.12 |
| `vacuums.json` | 5 | range, damage, bpc |
| `skymart.json` | items | price, currency, requirement |
| `greenhouse_crops.json` | 14 | yield, cycles, effects[] |
| `jacob.json` | brackets, rewards, anita shop | |
| `potions.json` | ~40 | id, maxLevel, ingredients by level, statsPerLevel[], baseDuration, rarityByLevel |
| `brew_modifiers.json` | glowstone/redstone/gunpowder family | |
| `enchant_table.json` | per enchant per level | xpCost, bookshelfPower, enchantingLevelReq, anvilMax |
| `experiments.json` | tiers × 3 games | req, rounds, clicks, lootTable |
| `rift_*.json` | areas, timecharms, enigma souls, vampire tiers, rift items | |
| `bestiary.json` | families | island, mobs[], bracket, maxTier, rewardType |
| `museum.json` | donatable items | category, sbXp, setMembers[] |
| `harp_songs.json` | 13 | notes[], int, sbXp |
| `fairy_souls.json` | 289 | island, x, y, z |
| `century_cakes.json` | 20 | stat, amount |
| `community_shop.json` | items | bits, category |
| `menus/*.json` | every GUI | slot → {icon, name, lore template, action} |

### 13.2 GUI framework

- Build **one chest-GUI engine** with: a 54-slot layout, filler pane default, `back`/`close`/`prev`/`next` at fixed slots
  (48/49/45/53), lore templating with `{placeholders}`, progress-bar helper (20-char strikethrough bar), and a
  number formatter (`1,234,567` / `1.2M`). Every menu in §11 is then just a JSON layout + handlers.
- Progress bar helper: `bar(cur, max, width=20)` → `§2§m{filled spaces}§f§m{empty spaces}§r §e{cur}§6/§e{max}`.
- Roman numeral helper with a per-player "use Arabic numerals" setting.
- Every list GUI (pets, collections, museum, visitors log, recipe book, bestiary) uses the same **paged grid**
  component: inner 7×4 (slots 10–16, 19–25, 28–34, 37–43) = 28 per page.

### 13.3 Scoreboard / action bar / tab

- Scoreboard is **line-template driven**: base template + context blocks (Garden, Contest, Rift, Slayer…) with
  priorities so it never exceeds 15 lines. Update every 1 s (date/time every 8.33 s real).
- Action bar: one string builder fed by Health/Defense/Mana with **override slots** (skill XP, ability, warnings) that
  expire after N ticks.
- Tab list: widgets as data (`title`, `lines[]`), 4 columns × 20 rows; Garden widgets only when on the Garden.

### 13.4 Garden engine

- Plots are 96×96 regions on a per-player world/instance; store owned plots, names, icons, sprays (`{material, expiresAt}`), pest list.
- Crop regrowth: tick crops per plot only while the owner (or co-op) is present; on rejoin, fast-forward by elapsed time
  (cap at fully grown). Crop Growth stat scales timers.
- **Pest roll** lives in the crop-break handler: `if now > pestCooldownEnd && rand() < spawnChance → spawn`, then pick pest
  by weights (base 100, +1,000 for sprayed attractants), honour Bonus Pest Chance for multi-spawns, set cooldown.
  Apply the FF penalty from the matrix to fortune at break time.
- **Visitors**: queue with `nextVisitorAt`; offline catch-up at the slower rate; max 5 + 1 hidden queue. Generate request on
  arrival (not on accept) so the offer is stable.
- **Composter**: pure function of elapsed time: `compost = min(floor(elapsed / interval), OM / costOM, fuel / costFuel)` —
  never tick it; compute lazily on open/collect.
- **Greenhouse**: also lazy — store `plantedAt`, stage index, water; compute stages on open.
- **Jacob's contest**: global schedule from SkyBlock calendar; contest crops chosen by seeded RNG per contest id so every
  server agrees; brackets computed at end from all participants (needs a shared store — or for single-server clone,
  simulate a population curve: fake percentile thresholds from a distribution scaled by contest crop).

### 13.5 Potions

- Active effects: `Map<effectId, {level, endsAt, source}>`; new drink of same id replaces; God Potion is a bundle that
  writes many effects with its own `endsAt` and is shown as one line in the effects menu.
- Pause list: entering dungeon instance → freeze `endsAt` (store remaining) and restore on exit.
- Durations increased multiplicatively by Brewer + Potion Affinity at **drink** time, not brew time (?) — pick one and document it.

### 13.6 Enchanting & anvil

- Enchants data includes `maxTable`, `anvilMax`, `conflicts[]`, `itemTypes[]`, `isUltimate`. Anvil validation: compatible
  type, conflicts, ultimate-one-per-item, level rules. Return preview + cost; apply on click.
- Experimentation minigames are pure GUI state machines — easy, isolated, testable. Give them a seeded RNG and a
  replay log for anti-cheat (clicks timed server-side).

### 13.7 Rift

- Separate **inventory profile** and **stat profile**; on entry swap inventory + stat calculator; on exit swap back.
  Rift-Transferable / Rift-Exportable flags on items.
- Rift Time is a countdown stat ticking 1/s except in freeze zones (region flags); ejection at 0.
- Motes are a separate currency with its own sidebar line.

### 13.8 Anti-P2W note (project rule)

The owner's rule is **no pay-to-win, cosmetic-only purchases** (see project memory). Therefore in the clone:
- Booster Cookies / Bits must be **earnable in-game** (e.g. from daily play, visitors, contests) rather than bought with
  real money, or the Bits shop must be cosmetic-only. Flag this to the economy/shop agents.
- Wardrobe slots and ender-chest pages gated by Hypixel *ranks* should be gated by **SkyBlock level** instead.
- God Potion/Kismet from Bits are gameplay power — they must come from an earnable currency.

### 13.9 Testability

- Every formula here (fortune drops, pest penalty, composter output, greenhouse timer, plot cost, crop upgrade
  totals, visitor copper) is a pure function — write unit tests against the confirmed tables (Garden XP cumulative
  60,120; plot D total 72 bundles; crop upgrades total 7,685; maxed composter ≈ 63/h; Greenhouse min 1h41m3s).

---

## 14. Progression placement

When each system enters a typical player's journey (hours are rough real-time estimates for a non-sweaty player).

| Phase | SkyBlock level | Hours (?) | Systems introduced from this file |
|---|---|---|---|
| **Day 1 — Hub & Island** | 0–5 | 0–3 | SkyBlock Menu, sidebar, action bar, Skills/Collections menus, Recipe Book, first potions (Speed/Strength from Hub vendors), Fairy Souls (Hub), Calendar, basic enchanting table use (Sharpness/Protection I–III) |
| **Early** | 5–20 | 3–15 | **Garden unlock (SB 5)**: Garden I–V, plots group A/B, Composter, first visitors, Crop Upgrades I–II; Farming Islands; Alchemy to ~15; Accessory Bag (Redstone II); Storage & first backpacks (fairy souls); Wardrobe; Pets menu with first pet; Jacob's contests at Farming 10; Museum first donations |
| **Early-mid** | 20–60 | 15–60 | Garden VI–X, pests from Garden V (spawning starts at 5), SkyMart vacuum/sprayonator, specialised hoes Tier I–II, Cultivating; Enchanting X → **Experimentation Table**; Potion Bag; Sacks; Bestiary tiers grind; Harp early songs; Abiphone basic; Booster cookie → Bits |
| **Mid** | 60–150 | 60–250 | Garden XI–XIII (Sunflower/Moonflower/Wild Rose), group C/D plots, Composter upgrades, Cropie→Squash armor, Pesthunter gear; **Greenhouse (Garden VII + blueprint)**; Alchemy 50, God Potions; Superpairs Supreme/Transcendent; **The Rift** (any time after mid-early, but Vampire slayer & late timecharms here); Museum filling; Chocolate Factory CF2–CF4 (seasonal) |
| **Late** | 150–300 | 250–1,000 | Garden XIV–XV, Crop upgrades VI–IX (Copper sink), Fermento → Helianthus armor, Farming 60 via 10 gold medals, maxed composter, Pest bestiary maxed, Secondary/Tertiary greenhouse, Rift fully done (52 Enigma souls, 8 timecharms, Vampire V), Metaphysical Superpairs, all Harp songs, high Fame rank |
| **Endgame / collectors** | 300+ | 1,000+ | Theoretical-max FF chasing, Museum completion, Bestiary completion, Century Cakes (every ~1.4 real years), Chocolate Factory CF6, Pure dyes (250k Bits) |

### Gating checklist (clone defaults)

| Feature | Gate |
|---|---|
| The Garden | SkyBlock Level 5 |
| Garden crop N | Garden level N (Sunflower/Moonflower XI, Wild Rose XII) |
| Pests | Garden V (?) and crops broken on the Garden |
| Greenhouse | Garden VII + Carpenter's offer + Greenhouse Blueprint |
| Jacob's Contest | Farming X + talked to Jacob |
| Experimentation Table | Enchanting X |
| Rift | Dimensional Infusion (free every 4 h) |
| Vampire Slayer | Reach Stillgore Château |
| Accessory Bag | Redstone Collection II (?) |
| Potion Bag | Nether Wart collection (?) |
| Backpack slots | Fairy Soul thresholds |
| Chocolate Factory | Any time (seasonal Hunt in Spring) |
| Museum | Hub access (not Stranded/Bingo) |
| Bits | Active Booster Cookie (clone: make earnable) |

---

## 15. Open questions / least-confident items

| Topic | What is uncertain | Suggested default |
|---|---|---|
| Visitor spawn interval & copper formula | Exact base interval (15 min?), rarity weights, copper per offer | §1.10 clone defaults; tune by playtest |
| Crop milestone tiers & thresholds | 46 tiers? multipliers per crop? Garden XP per tier | Wheat table × multiplier; expose in config |
| Enchanting table XP costs & bookshelf power | Hypixel's per-enchant exact costs | 10/15/20/25/30 pattern |
| Experimentation Table tier requirements, grid sizes, click counts, loot | Not confirmed | §3.4 table |
| Potion stat values for mid levels, ingredients for niche potions (Spirit, Stun, Pet Luck, True Resistance, Experience, Spelunker) | Not confirmed | Linear interpolation between confirmed endpoints |
| God Potion duration/offline behaviour | 24 h? stacks? | 24 h, online-only, stacks |
| Bits accrual rate & Fame multipliers | Not confirmed | 4,800 × mult per cookie, 250 per 30 min |
| Vampire slayer HP/cost/XP | Not confirmed | §5.6 |
| Fairy Soul per-island split | Only total 289 confirmed | §6.5 |
| SkyMart prices | Not confirmed | §1.13 |
| Wardrobe / Ender Chest unlock rules | Rank-based on Hypixel | Tie to SB level (anti-P2W) |
| Slot indices of sub-menus (Skills, Collections, Fast Travel, Desk, Stats) | Only SkyBlock Menu confirmed | §11 layouts |

