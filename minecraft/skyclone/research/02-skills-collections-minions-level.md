# 02 — Skills, Collections, Minions, Crafting & SkyBlock Level

> Research brief for the from-scratch SkyBlock clone. Scope: every skill, every collection, every minion,
> the crafting / enchanted-item compression system, SkyBlock XP & Level, Fairy Souls, Essence, and the
> other "progress counters" that feed SkyBlock XP. Written 2026-10-02.

## 0. Sources, freshness and confidence

| Source | What it gave | Freshness |
|---|---|---|
| `https://api.hypixel.net/v2/resources/skyblock/skills` (keyless) | Every skill, max level, cumulative XP per level, and the exact reward lines (perk, stat, coins, SB XP, unlocks) per level | Game version **0.27.1.18**, fetched 2026-10-02 — **authoritative** |
| `https://api.hypixel.net/v2/resources/skyblock/collections` (keyless) | All 90 resource collections, tier thresholds, and every tier's unlock list | Same version — **authoritative** |
| `https://api.hypixel.net/v2/resources/skyblock/items` (keyless) | Item ids, minion generator ids/tiers (719 minion items across 61 types) | Same version |
| hypixel-skyblock.fandom.com via `api.php?action=parse&prop=wikitext` | `Module:Minion/Data` (per-minion TBA, storage, recipe per tier, drops, minion skill XP), `Module:Collection/Data` (boss collections), skill pages (XP per action), fuel/upgrade/storage/crystal tables, SkyBlock Level reward & task tables, Fairy Souls, Essence, Co-op, Crafting | Community wiki, mostly current (2025–2026), some pages tagged "outdated" — flagged inline |
| wiki.hypixel.net | **Dead** — the official wiki was shut down in July 2026 (now redirects to a forum thread "End of the Official Hypixel Wiki - July 2026") | n/a |
| Own knowledge | Mechanics glue, menu/chat behaviour, offline rules | Marked **(?)** where not verified |

**Legend:** `(?)` = not verified against a source, treat as a reasonable default you may tune. Everything in a
generated table (skills XP, collection tiers/unlocks, minion TBA/storage/recipes) was produced by a script
straight from the API / wiki data module, not typed by hand.

**Naming:** original Hypixel names are used throughout; renaming for copyright happens later.

---

## 1. Overview — the progression web in one page

SkyBlock's long-term progression is a set of **counters** that only go up, each of which pays out at
thresholds. Almost every one of them also pays **SkyBlock XP**, which rolls up into the single
**SkyBlock Level** shown next to the player's name. The systems in this file are the backbone:

```
             gathering action (break crop/ore/log, kill mob, fish, brew, enchant, craft, pet XP, hunt)
                         │
         ┌───────────────┼────────────────────────┐
         ▼               ▼                        ▼
   SKILL XP         COLLECTION count         (item goes to inventory/sacks)
   (per action)     (per item obtained)            │
         │               │                        ▼
   skill level      collection tier ──► unlocks RECIPES (minions, enchanted items,
   ─ stats            (+4 SB XP each)           armour, tools, accessories, sacks),
   ─ perk (fortune)                             NPC trades, skill-XP lump sums,
   ─ coins                                      enchant XP discounts
   ─ SB XP (5/10/20/30)                               │
   ─ zone access                                     ▼
         │                                  CRAFTING (recipe book) ──► Carpentry XP
         │                                         │
         │                                  MINIONS (crafted from the collection item)
         │                                   ─ produce items offline → more collection + skill XP
         │                                   ─ each NEW unique minion tier crafted → SB XP + minion slots
         ▼                                         ▼
   SKILL AVERAGE                       SKYBLOCK XP (100 XP = 1 level) ◄── fairy souls, magical power,
   (cosmetic stat / gate)              SKYBLOCK LEVEL                     pet score, bestiary, slayer,
                                       ─ +5 HP / level, +1 STR / 5 levels  dungeons, museum, essence perks…
                                       ─ feature unlocks (Bazaar L7, Garden L5…)
                                       ─ prefix colour + emblems
```

Design truths worth keeping in the clone:

1. **Skills are per-player; collections are per-profile.** In a co-op, every member levels their own skills,
   but collection progress is pooled and unlocks apply to everyone.
2. **Minions are the bridge between "active" and "idle".** They feed the same collection and skill counters
   (at a heavily reduced skill-XP rate) while the player is offline.
3. **Collections gate crafting; crafting gates minions; minions gate minion slots.** Crafting a *new unique*
   minion tier is itself a progression counter (unique minions → more placeable minions → more output).
4. **SkyBlock XP is a "did you do everything" meter.** It rewards breadth (one-time tasks) more than grind.

---

## 2. Skills

### 2.0 Common rules (apply to every skill unless noted)

| Rule | Value |
|---|---|
| Number of skills | 12 in the API: Farming, Mining, Combat, Foraging, Fishing, Enchanting, Alchemy, Carpentry, Taming, Hunting (+ cosmetic Runecrafting, Social). Dungeoneering (Catacombs) is a 13th levelled track but lives in the dungeon system, not this API resource. |
| Level 0 | Every skill starts at level 0; 50 XP reaches level 1. |
| XP curve | One shared table for the 10 "real" skills (§2.1). Runecrafting and Social have their own shorter tables (§2.2, §2.3). |
| Max levels (API, current) | Farming 60, Mining 60, Combat 60, Foraging **57**, Fishing 50, Enchanting 60, Alchemy 50, Carpentry 50, Taming 60, Hunting 50, Runecrafting 25, Social 25. |
| Level caps that must be *unlocked* | **Farming** 50 → 60: one level per Gold medal crop in Jacob's Farming Contest, bought from Anita (cost table in §2.4.1). **Taming** 50 → 60: give George one of 10 specific pets (+1 cap each). **Foraging** 50 → max: +1 for Fig Log IX, +1 for Mangrove Log IX, +2 from Agatha's shop (Agatha Milestone 4) — the wiki says that totals 54, but the API now reports max **57**, so 3 more cap sources exist that are undocumented (?). Mining, Combat, Enchanting go to 60 with no cap gate. |
| Rewards on level-up | Coins (same table for all 10 real skills), SkyBlock XP (5 for L1–10, 10 for L11–25, 20 for L26–50, 30 for L51–60), a stat bonus, the skill's **perk** (one perk level per skill level), and sometimes access / recipe / reforge unlocks. |
| XP boost stat | **Wisdom** per skill (Farming Wisdom, Mining Wisdom, Combat Wisdom, …). `xp_gained = base_xp × (1 + wisdom / 100)`. Sources: Booster Cookie (+25), XP-boost potions / God Potion (+5…+20), pets (e.g. Rabbit +30 Farming Wisdom at L100), reforges, enchants (Cultivating, Compact, Expertise), shards. |
| Minion XP | Collecting items out of a minion grants a small, fixed "minion XP" per item (column "Minion XP" in §2.4 tables and in the minion table §4.11). Compacted items grant the sum of their inputs (Enchanted Wheat = 160 × 0.2 = 32). |
| Co-op minion XP | When a co-op member collects from a minion, the other members also receive skill XP from it (next time they are on the island) — see §4.7. |
| Skill Average | Mean of the levels of all skills **except Social, Runecrafting and Dungeoneering**, i.e. Farming, Mining, Combat, Foraging, Fishing, Enchanting, Alchemy, Carpentry, Taming and Hunting (10 skills). Shown in the Skills menu. Whether Hunting is included in-game was not independently verified (?) — the wiki's wording ("all skills except …") implies yes. Third-party tools often compute it with fractional progress; the in-game display uses whole levels (?) . |
| Skill XP from private-island mobs | Mobs spawned on the private island (minion mobs) give **no** combat XP when killed by the player. |

#### Skill → stat / perk summary

| Skill | Stat per level | Perk (per level) | Perk at max | Main XP sources |
|---|---|---|---|---|
| Farming | +2/+3/+4/+5 Health (L1–14/15–19/20–25/26+) | Farmhand: +4 Farming Fortune | +240 FF at 60 | Breaking fully-grown crops, killing farm animals, shearing, pests, Garden milestones |
| Mining | +1 Defense (L1–14), +2 (L15+) | Spelunker: +4 Mining Fortune | +240 MF at 60 | Breaking ores/stone, commissions, minions |
| Combat | +0.5 Crit Chance | Warrior: +4% damage vs mobs (L1–50), +1% (L51–60) | +210% at 60 | Killing mobs, slayers, bestiary milestones |
| Foraging | +1 Strength (L1–14), +2 (L15+) | Logger: +4 Foraging Fortune | +228 at 57 | Chopping logs, picking flowers |
| Fishing | +2/+3/+4/+5 Health | Treasure Hunter: +0.1 Treasure Chance | +5 at 50 | Fishing items, killing sea creatures |
| Enchanting | +0.5 Ability Damage and +1 Int (L1–14) / +2 Int (L15+) | Conjurer: +5% XP orbs | +300% at 60 | Spending XP levels on enchanting table/anvil, Experimentation Table |
| Alchemy | +1 Int (L1–14), +2 (L15+) | Brewer: +1% brewed potion duration | +50% at 50 | Brewing potions |
| Carpentry | +1 Health | — (unlocks furniture recipes, Quick Craft at L3) | — | Crafting (3% of ingredients' NPC sell value) |
| Taming | +1 Pet Luck | Zoologist: +1% pet XP, +0.2% Exp-Share rate | +60% pet XP, 12% share at 60 | Pets gaining XP |
| Hunting | +1 Hunting Fortune | Charming: +0.04 Charm Chance | +2 at 50 | Capturing / fusing / syphoning attribute shards, hunt milestones |
| Runecrafting (cosmetic) | — | — | — | Runic mobs, fusing runes (rank-gated on Hypixel — drop the gate in the clone) |
| Social (cosmetic) | — | — | — | Guests visiting your island, visiting others |

### 2.1 The universal skill XP curve (Farming, Mining, Combat, Foraging, Fishing, Enchanting, Alchemy, Carpentry, Taming, Hunting)

Source: `api.hypixel.net/v2/resources/skyblock/skills` (version 0.27.1.18, fetched 2026-10-02). Every one of the ten "real" skills shares this exact table; they only differ in how many rows they use (max level). Coins and SkyBlock XP rewards are also identical across these skills at the same level.

| Lvl | XP for this level | Cumulative XP | Coin reward | SB XP reward |
|---:|---:|---:|---:|---:|
| 1 | 50 | 50 | 100 | 5 |
| 2 | 125 | 175 | 250 | 5 |
| 3 | 200 | 375 | 500 | 5 |
| 4 | 300 | 675 | 750 | 5 |
| 5 | 500 | 1,175 | 1,000 | 5 |
| 6 | 750 | 1,925 | 2,000 | 5 |
| 7 | 1,000 | 2,925 | 3,000 | 5 |
| 8 | 1,500 | 4,425 | 4,000 | 5 |
| 9 | 2,000 | 6,425 | 5,000 | 5 |
| 10 | 3,500 | 9,925 | 7,500 | 5 |
| 11 | 5,000 | 14,925 | 10,000 | 10 |
| 12 | 7,500 | 22,425 | 15,000 | 10 |
| 13 | 10,000 | 32,425 | 20,000 | 10 |
| 14 | 15,000 | 47,425 | 25,000 | 10 |
| 15 | 20,000 | 67,425 | 30,000 | 10 |
| 16 | 30,000 | 97,425 | 40,000 | 10 |
| 17 | 50,000 | 147,425 | 50,000 | 10 |
| 18 | 75,000 | 222,425 | 65,000 | 10 |
| 19 | 100,000 | 322,425 | 80,000 | 10 |
| 20 | 200,000 | 522,425 | 100,000 | 10 |
| 21 | 300,000 | 822,425 | 125,000 | 10 |
| 22 | 400,000 | 1,222,425 | 150,000 | 10 |
| 23 | 500,000 | 1,722,425 | 175,000 | 10 |
| 24 | 600,000 | 2,322,425 | 200,000 | 10 |
| 25 | 700,000 | 3,022,425 | 225,000 | 10 |
| 26 | 800,000 | 3,822,425 | 250,000 | 20 |
| 27 | 900,000 | 4,722,425 | 275,000 | 20 |
| 28 | 1,000,000 | 5,722,425 | 300,000 | 20 |
| 29 | 1,100,000 | 6,822,425 | 325,000 | 20 |
| 30 | 1,200,000 | 8,022,425 | 350,000 | 20 |
| 31 | 1,300,000 | 9,322,425 | 375,000 | 20 |
| 32 | 1,400,000 | 10,722,425 | 400,000 | 20 |
| 33 | 1,500,000 | 12,222,425 | 425,000 | 20 |
| 34 | 1,600,000 | 13,822,425 | 450,000 | 20 |
| 35 | 1,700,000 | 15,522,425 | 475,000 | 20 |
| 36 | 1,800,000 | 17,322,425 | 500,000 | 20 |
| 37 | 1,900,000 | 19,222,425 | 550,000 | 20 |
| 38 | 2,000,000 | 21,222,425 | 600,000 | 20 |
| 39 | 2,100,000 | 23,322,425 | 650,000 | 20 |
| 40 | 2,200,000 | 25,522,425 | 700,000 | 20 |
| 41 | 2,300,000 | 27,822,425 | 750,000 | 20 |
| 42 | 2,400,000 | 30,222,425 | 800,000 | 20 |
| 43 | 2,500,000 | 32,722,425 | 850,000 | 20 |
| 44 | 2,600,000 | 35,322,425 | 900,000 | 20 |
| 45 | 2,750,000 | 38,072,425 | 1,000,000 | 20 |
| 46 | 2,900,000 | 40,972,425 | 1,000,000 | 20 |
| 47 | 3,100,000 | 44,072,425 | 1,000,000 | 20 |
| 48 | 3,400,000 | 47,472,425 | 1,000,000 | 20 |
| 49 | 3,700,000 | 51,172,425 | 1,000,000 | 20 |
| 50 | 4,000,000 | 55,172,425 | 1,000,000 | 20 |
| 51 | 4,300,000 | 59,472,425 | 1,000,000 | 30 |
| 52 | 4,600,000 | 64,072,425 | 1,000,000 | 30 |
| 53 | 4,900,000 | 68,972,425 | 1,000,000 | 30 |
| 54 | 5,200,000 | 74,172,425 | 1,000,000 | 30 |
| 55 | 5,500,000 | 79,672,425 | 1,000,000 | 30 |
| 56 | 5,800,000 | 85,472,425 | 1,000,000 | 30 |
| 57 | 6,100,000 | 91,572,425 | 1,000,000 | 30 |
| 58 | 6,400,000 | 97,972,425 | 1,000,000 | 30 |
| 59 | 6,700,000 | 104,672,425 | 1,000,000 | 30 |
| 60 | 7,000,000 | 111,672,425 | 1,000,000 | 30 |

Verified programmatically: cumulative XP is identical for all ten skills (no mismatches).

### 2.2 Runecrafting XP curve (cosmetic skill, max 25)

| Lvl | XP for level | Cumulative | Reward(s) |
|---:|---:|---:|---|
| 1 | 50 | 50 | Access to Level 1 Runes |
| 2 | 100 | 150 | Access to Level 2 Runes |
| 3 | 125 | 275 | Access to Level 3 Runes |
| 4 | 160 | 435 | Access to Level 4 Runes |
| 5 | 200 | 635 | Access to Level 5 Runes |
| 6 | 250 | 885 | Access to Level 6 Runes |
| 7 | 315 | 1,200 | Access to Level 7 Runes |
| 8 | 400 | 1,600 | Access to Level 8 Runes |
| 9 | 500 | 2,100 | Access to Level 9 Runes |
| 10 | 625 | 2,725 | Access to Level 10 Runes |
| 11 | 785 | 3,510 | Access to Level 11 Runes |
| 12 | 1,000 | 4,510 | Access to Level 12 Runes |
| 13 | 1,250 | 5,760 | Access to Level 13 Runes |
| 14 | 1,565 | 7,325 | Access to Level 14 Runes |
| 15 | 2,000 | 9,325 | Access to Level 15 Runes |
| 16 | 2,500 | 11,825 | Access to Level 16 Runes |
| 17 | 3,125 | 14,950 | Access to Level 17 Runes |
| 18 | 4,000 | 18,950 | Access to Level 18 Runes |
| 19 | 5,000 | 23,950 | Access to Level 19 Runes |
| 20 | 6,250 | 30,200 | Access to Level 20 Runes |
| 21 | 7,850 | 38,050 | Access to Level 21 Runes |
| 22 | 9,800 | 47,850 | Access to Level 22 Runes |
| 23 | 12,250 | 60,100 | Access to Level 23 Runes |
| 24 | 15,300 | 75,400 | Access to Level 24 Runes |
| 25 | 19,050 | 94,450 | Access to Level 25 Runes |

### 2.3 Social XP curve (cosmetic skill, max 25)

| Lvl | XP for level | Cumulative | Reward(s) |
|---:|---:|---:|---|
| 1 | 50 | 50 | Ability to purchase Parkour Blocks from Amelia; +100 Coins |
| 2 | 100 | 150 | Ability to purchase Social Display from Amelia; +250 Coins |
| 3 | 150 | 300 | Ability to purchase Egg Hunt from Amelia; +500 Coins |
| 4 | 250 | 550 | Unlock /social Commands; +750 Coins |
| 5 | 500 | 1,050 | Ability to purchase Island NPCs from Amelia; +1,000 Coins |
| 6 | 750 | 1,800 | Ability to purchase Tic Tac Toe from Amelia; +2,000 Coins |
| 7 | 1,000 | 2,800 | Ability to purchase Four in a Row from Amelia; +3,000 Coins |
| 8 | 1,250 | 4,050 | Ability to purchase Rock Paper Shears from Amelia; +4,000 Coins |
| 9 | 1,500 | 5,550 | Unlock Island Ranks; +5,000 Coins |
| 10 | 2,000 | 7,550 | Ability to purchase Showcase Block from Amelia; +7,500 Coins |
| 11 | 2,500 | 10,050 | +10,000 Coins |
| 12 | 3,000 | 13,050 | +15,000 Coins |
| 13 | 3,750 | 16,800 | +20,000 Coins |
| 14 | 4,500 | 21,300 | +25,000 Coins |
| 15 | 6,000 | 27,300 | +30,000 Coins |
| 16 | 8,000 | 35,300 | +40,000 Coins |
| 17 | 10,000 | 45,300 | +50,000 Coins |
| 18 | 12,500 | 57,800 | +65,000 Coins |
| 19 | 15,000 | 72,800 | +80,000 Coins |
| 20 | 20,000 | 92,800 | Unlock Island Naming; +100,000 Coins |
| 21 | 25,000 | 117,800 | +125,000 Coins |
| 22 | 30,000 | 147,800 | +150,000 Coins |
| 23 | 35,000 | 182,800 | +175,000 Coins |
| 24 | 40,000 | 222,800 | +200,000 Coins |
| 25 | 50,000 | 272,800 | +225,000 Coins |


### 2.4 Per-skill detail

The tables below are generated from the API reward lines. "Stats gained per level" is what each level adds;
the perk line shows the cumulative perk value. Coins per level follow §2.1.

#### Farming (max level 60)

_API description_: “Harvest crops and shear sheep to earn Farming EXP!”

**Perk:** Farmhand — at level 1: +4 Farming Fortune; at level 60: +240 Farming Fortune.

| Levels | Stats gained per level |
|---|---|
| 1–14 | +2 Health |
| 15–19 | +3 Health |
| 20–25 | +4 Health |
| 26–60 | +5 Health |

Cumulative stat totals — at level 50: 192 Health; at max (60): 242 Health.
Cumulative coin reward at max: 27,259,100; cumulative SkyBlock XP at max: 1,000.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 1 | +Access to The Barn |
| 5 | +Access to Mushroom Desert |

#### Mining (max level 60)

_API description_: “Dive into deep caves and find rare ores and valuable materials to earn Mining EXP!”

**Perk:** Spelunker — at level 1: +4 Mining Fortune; at level 60: +240 Mining Fortune.

| Levels | Stats gained per level |
|---|---|
| 1–14 | +1 Defense |
| 15–60 | +2 Defense |

Cumulative stat totals — at level 50: 86 Defense; at max (60): 106 Defense.
Cumulative coin reward at max: 27,259,100; cumulative SkyBlock XP at max: 1,000.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 1 | +Access to Gold Mine; Jerry Stone Reforge |
| 2 | Black Diamond Reforge |
| 3 | Overflowing Trash Can Reforge |
| 5 | +Access to Deep Caverns; Lapis Crystal Reforge; Moil Log Reforge |
| 8 | Rare Diamond Reforge |
| 9 | SkyMart Brochure Reforge |
| 10 | Extremely Mild Adhesive Reforge; Dirt Bottle Reforge |
| 11 | Sunstone Reforge; Moonstone Reforge |
| 12 | +Access to Crystal Hollows; +Access to Dwarven Mines; Moonglade Jewel Reforge; Hardened Wood Reforge; Large Walnut Reforge |
| 13 | Full-Jaw Fanging Kit Reforge; Flowering Bouquet Reforge |
| 14 | Dwarven Treasure Reforge; End Stone Geode Reforge |
| 15 | Beady Eyes Reforge; Diamonite Reforge; Clipped Wings Reforge; Kaleidoscopic Mineral Reforge; Geometric Oddity Reforge; Terry's Snowglobe Reforge |
| 16 | Premium Flesh Reforge |
| 17 | Red Nose Reforge |
| 18 | Pitchin' Koi Reforge; Pure Mithril Reforge |
| 19 | Necromancer's Brooch Reforge |
| 20 | Entropy Suppressor Reforge; Titanium Tesseract Reforge; Dragon Scale Reforge; Molten Cube Reforge; Frozen Bauble Reforge |
| 21 | Red Scarf Reforge; Squeaky Toy Reforge; Dwarven Geode Reforge |
| 22 | Dragon Claw Reforge; Optical Lens Reforge; Mantid Claw Reforge; Diamond Atom Reforge |
| 23 | Overgrown Grass Reforge; Suspicious Vial Reforge |
| 24 | Gleaming Crystal Reforge; Presumed Gallon of Red Paint Reforge; Searing Stone Reforge; Blaze Wax Reforge; Blooming Thorns Reforge; Lucky Dice Reforge; Burrowing Spores Reforge; Shiny Prism Reforge; Meteor Chunk Reforge; Spirit Stone Reforge |
| 25 | Giant Tooth Reforge; Andesite Whetstone Reforge; Bulky Stone Reforge; Midas Jewel Reforge; Sadan's Brooch Reforge; Dragon Horn Reforge; Toil Log Reforge; Blessed Fruit Reforge |
| 26 | Warped Stone Reforge; Shriveled Cornea Reforge; Petrified Starfall Reforge |
| 27 | Blazen Sphere Reforge |
| 30 | Amber Material Reforge; Wither Blood Reforge; Precursor Gear Reforge; Mangrove Gem Reforge; Golden Ball Reforge; Frigid Husk Reforge; Jaderald Reforge; Daedalus' Notes Reforge |
| 33 | Kuudra Mandible Reforge |

#### Combat (max level 60)

_API description_: “Fight mobs and special bosses to earn Combat EXP!”

**Perk:** Warrior — at level 1: Deal 4% more damage to mobs; at level 60: Deal 210% more damage to mobs.

| Levels | Stats gained per level |
|---|---|
| 1–60 | +0.5 Crit Chance |

Cumulative stat totals — at level 50: 25 Crit Chance; at max (60): 30 Crit Chance.
Cumulative coin reward at max: 27,259,100; cumulative SkyBlock XP at max: 1,000.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 1 | +Access to Spider's Den |
| 10 | Displaced Leech Power Stone |
| 12 | +Access to The End |
| 15 | Intermediate Accessory Bag Powers; Luxurious Spool Power Stone; Rock Candy Power Stone; Ender Monocle Power Stone |
| 20 | +Access to Zealot Bruiser Hideout; Acacia Birdhouse Power Stone; Sunflower Butter Power Stone; Glacite Chunk Power Stone; Furball Power Stone; Obsidian Tablet Power Stone; End Stone Shulker Power Stone; Beating Heart Power Stone; Dark Orb Power Stone; Fang-tastic Chocolate Chip Power Stone |
| 22 | +Access to Crimson Isle |
| 25 | Horns of Torment Power Stone; Bubba Blister Power Stone; Eccentric Painting Power Stone; Magma Urchin Power Stone; Precious Pearl Power Stone |
| 30 | Mandraa Power Stone; Hazmat Enderman Power Stone; Vitamin Death Power Stone |
| 35 | Scorched Books Power Stone |

#### Foraging (max level 57)

_API description_: “Cut trees and forage for other plants to earn Foraging EXP!”

**Perk:** Logger — at level 1: +4 Foraging Fortune; at level 57: +228 Foraging Fortune.

| Levels | Stats gained per level |
|---|---|
| 1–14 | +1 Strength |
| 15–57 | +2 Strength |

Cumulative stat totals — at level 50: 86 Strength; at max (57): 100 Strength.
Cumulative coin reward at max: 24,259,100; cumulative SkyBlock XP at max: 910.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 1 | +Access to The Park |
| 12 | +Access to Galatea |

#### Fishing (max level 50)

_API description_: “Visit your local pond to fish and earn Fishing EXP!”

**Perk:** Treasure Hunter — at level 1: +0.1 Treasure Chance; at level 50: +5 Treasure Chance.

| Levels | Stats gained per level |
|---|---|
| 1–14 | +2 Health |
| 15–19 | +3 Health |
| 20–25 | +4 Health |
| 26–50 | +5 Health |

Cumulative stat totals — at level 50: 192 Health; at max (50): 192 Health.
Cumulative coin reward at max: 17,259,100; cumulative SkyBlock XP at max: 700.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 1 | Squid Sea Creature; Sea Walker Sea Creature; Frozen Steve Sea Creature; Frosty Sea Creature |
| 5 | +Access to Lotus Atoll; Dumpster Diver Sea Creature; Frog Man Sea Creature; Trash Gobbler Sea Creature; Bogged Sea Creature; Nurse Shark Sea Creature |
| 6 | Jumpin' Jack Sea Creature; Medium Mithril Grubber Sea Creature; Mithril Grubber Sea Creature; Bloated Mithril Grubber Sea Creature; Large Mithril Grubber Sea Creature |
| 7 | Wetwing Sea Creature; Inkling Sea Creature; Sea Witch Sea Creature |
| 9 | Scarecrow Sea Creature; Tadgang Sea Creature; Sea Archer Sea Creature |
| 10 | Puddle Jumper Sea Creature; Banshee Sea Creature; Atoll Croaker Sea Creature; Oasis Sheep Sea Creature; gorF Sea Creature; Blue Shark Sea Creature; Snapping Turtle Sea Creature; Oasis Rabbit Sea Creature; Lotus Guardian Sea Creature; Drowned Captain Sea Creature; Frog Prince Sea Creature |
| 11 | Rider of the Deep Sea Creature |
| 12 | Ent Sea Creature |
| 13 | Catfish Sea Creature; Grinch Sea Creature |
| 14 | Fried Chicken Sea Creature; Carrot King Sea Creature; Nightmare Sea Creature |
| 15 | 9 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 16 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 17 | 5 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 18 | 2 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 19–20 | 3 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 21 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 22 | 2 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 24–25 | 2 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 26–27 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 28 | 2 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 30–31 | 2 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 33–34 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 35 | 3 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 36 | 2 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 37 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 40 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 45 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |
| 47 | 1 hidden ("???") sea creature(s) — names not exposed by the API until unlocked |

#### Enchanting (max level 60)

_API description_: “Enchant items to earn Enchanting EXP!”

**Perk:** Conjurer — at level 1: Gain 5% more experience orbs from any source; at level 60: Gain 300% more experience orbs from any source.

| Levels | Stats gained per level |
|---|---|
| 1–14 | +0.5 Ability Damage, +1 Intelligence |
| 15–60 | +0.5 Ability Damage, +2 Intelligence |

Cumulative stat totals — at level 50: 25 Ability Damage, 86 Intelligence; at max (60): 30 Ability Damage, 106 Intelligence.
Cumulative coin reward at max: 27,259,100; cumulative SkyBlock XP at max: 1,000.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 1 | Scavenger Enchantment |
| 2 | Infinite Quiver Enchantment; Sunder Enchantment; Harvesting Enchantment |
| 3 | Luck Enchantment; Cubism Enchantment |
| 4 | Cleave Enchantment; Angler Enchantment |
| 5 | Life Steal Enchantment; Scuba Enchantment; Feast Enchantment; Woodsplitter Enchantment; Growth Enchantment |
| 6 | Snipe Enchantment; Rainbow Enchantment |
| 7 | Replenish Enchantment; Sugar Rush Enchantment |
| 8 | Giant Killer Enchantment; Piscary Enchantment; Dragon Tracer Enchantment |
| 9 | Blessing Enchantment; Pyroclasm Enchantment; Critical Enchantment |
| 10 | Pesterminator Enchantment; First Strike Enchantment; Rejuvenate Enchantment; Experimentation Table Recipe |
| 11 | Ender Slayer Enchantment; Chance Enchantment |
| 12 | Dedication Enchantment; Impaling Enchantment; Forest Pledge Enchantment; Petalfall Enchantment |
| 13 | Tidal Enchantment; Magnet Enchantment |
| 14 | Lethality Enchantment; Frail Enchantment; Execute Enchantment; Missile Enchantment; Thunderlord Enchantment; First Impression Enchantment |
| 15 | Drain Enchantment; Caster Enchantment; Flowstate Enchantment; True Protection Enchantment; Vampirism Enchantment |
| 16 | Gravity Enchantment; Bank Enchantment |
| 17 | Piercing Enchantment; Venomous Enchantment |
| 18 | Spiked Hook Enchantment; Ultimate Jerry Enchantment |
| 19 | Triple-Strike Enchantment |
| 20 | Refrigerate Enchantment; Thunderbolt Enchantment; Mana Steal Enchantment; Ultimate Wise Enchantment |
| 21 | Big Brain Enchantment; Smarty Pants Enchantment; Small Brain Enchantment |
| 22 | Vampiric Vitality Enchantment; Hardened Vitality Enchantment; Prismatic Enchantment; Counter-Strike Enchantment; Vivacious Vitality Enchantment; Strong Vitality Enchantment; Lapidary Enchantment |
| 23 | Quick Bite Enchantment; Respite Enchantment; Smoldering Enchantment |
| 24 | Bobbin' Time Enchantment; Reflection Enchantment; Green Thumb Enchantment; Combo Enchantment |
| 25 | Corruption Enchantment; Charm Enchantment; The One Enchantment; Prosecute Enchantment |
| 26 | Vicious Enchantment |
| 27 | Paleontologist Enchantment; Sunset Enchantment; Wisdom Enchantment |
| 28 | Habanero Tactics Enchantment; Titan Killer Enchantment; Duplex Enchantment |
| 29 | No Pain No Gain Enchantment |
| 30 | Ice Cold Enchantment; Flash Enchantment; Last Stand Enchantment |
| 31 | Chimera Enchantment |
| 32 | Rend Enchantment; Crop Fever Enchantment |
| 33 | Overload Enchantment |
| 34 | Legion Enchantment |
| 35 | Swarm Enchantment |
| 36 | Soul Eater Enchantment |
| 37 | Fatal Tempo Enchantment; Inferno Enchantment |
| 38 | One For All Enchantment |

#### Alchemy (max level 50)

_API description_: “Brew potions to earn Alchemy EXP!”

**Perk:** Brewer — at level 1: Potions that you brew have a 1% longer duration; at level 50: Potions that you brew have a 50% longer duration.

| Levels | Stats gained per level |
|---|---|
| 1–14 | +1 Intelligence |
| 15–50 | +2 Intelligence |

Cumulative stat totals — at level 50: 86 Intelligence; at max (50): 86 Intelligence.
Cumulative coin reward at max: 17,259,100; cumulative SkyBlock XP at max: 700.

#### Carpentry (max level 50)

_API description_: “Craft items to earn Carpentry EXP!”

| Levels | Stats gained per level |
|---|---|
| 1–50 | +1 Health |

Cumulative stat totals — at level 50: 50 Health; at max (50): 50 Health.
Cumulative coin reward at max: 17,259,100; cumulative SkyBlock XP at max: 700.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 1 | Minion Chair Furniture Recipe; Dark Oak Chair Furniture Recipe |
| 2 | Flower Pot Furniture Recipe |
| 3 | Dark Oak Bench Furniture Recipe; Access to Quick Crafting System |
| 4 | Dark Oak Table Furniture Recipe |
| 5 | Armor Stand Furniture Recipe |
| 6 | Scarecrow Furniture Recipe |
| 7 | Desk Furniture Recipe |
| 8 | Bookcase Furniture Recipe |
| 9 | Small Shelves Furniture Recipe |
| 10 | Weapon Rack Furniture Recipe |
| 11 | Fire Pit Furniture Recipe |
| 12 | Tiki Torch Furniture Recipe |
| 13 | Furnace+ Furniture Recipe; Fireplace Furniture Recipe |
| 14 | Chest Storage Furniture Recipe |
| 15 | Weapon Rack+ Furniture Recipe |
| 16 | Large Bed Furniture Recipe; Hay Bed Furniture Recipe |
| 17 | Food Trough Furniture Recipe; Water Trough Furniture Recipe |
| 18 | Medium Shelves Furniture Recipe |
| 19 | Crafting Table+ Furniture Recipe |
| 20 | Redstone Chest+ Furniture Recipe; Emerald Chest+ Furniture Recipe; Ender Chest+ Furniture Recipe; End Stone Chest+ Furniture Recipe; Gold Chest+ Furniture Recipe; Wood Chest+ Furniture Recipe; Diamond Chest+ Furniture Recipe; Lapis Chest+ Furniture Recipe; Iron Chest+ Furniture Recipe |
| 21 | Brewing+ Furniture Recipe |
| 22 | Enchanting Table+ Furniture Recipe |
| 23 | Blacksmith+ Furniture Recipe |
| 24 | Skull Chest++ Furniture Recipe |
| 25 | Grandfather Clock Furniture Recipe |
| 26–50 | Furniture Recipe (COMING SOON) |

#### Taming (max level 60)

_API description_: “Level up pets to earn Taming EXP!”

**Perk:** Zoologist — at level 1: Gain 1% extra pet exp; at level 60: Gain 60% extra pet exp.

| Levels | Stats gained per level |
|---|---|
| 1–60 | +1 Pet Luck |

Cumulative stat totals — at level 50: 50 Pet Luck; at max (60): 60 Pet Luck.
Cumulative coin reward at max: 27,259,100; cumulative SkyBlock XP at max: 1,000.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 10 | +Free Training Session at Fann |
| 20 | +Light Training Session at Fann |
| 30 | +Moderate Training Session at Fann |
| 40 | +Expert Training Session at Fann |
| 50 | +Ultra Training Session at Fann |
| 60 | +Turbo! Training Session at Fann |

#### Hunting (max level 50)

_API description_: “Hunt various monsters to earn Hunting EXP!”

**Perk:** Charming — at level 1: +0.04 Charm Chance; at level 50: +2 Charm Chance.

| Levels | Stats gained per level |
|---|---|
| 1–50 | +1 Hunting Fortune |

Cumulative stat totals — at level 50: 50 Hunting Fortune; at max (50): 50 Hunting Fortune.
Cumulative coin reward at max: 17,259,100; cumulative SkyBlock XP at max: 700.

| Level | Special unlocks (beyond stats/coins/SB XP) |
|---:|---|
| 15 | +Access to Critter Safari |


#### 2.4.1 Farming — XP per action and level-cap purchase

XP is granted **per crop block broken, not per item dropped**, so Farming Fortune does not change XP.

| Item | XP (manual) | Minion XP (per item) | Where |
|---|---:|---:|---|
| Wheat | 4 | 0.3 (wiki skill page) / 0.2 (minion module) (?) | Village, Farm, The Barn, Garden |
| Potato | 4 | 0.1 | Farm, The Barn, Garden |
| Carrot | 4 | 0.1 | Farm, The Barn, Mountain, Garden |
| Melon | 4 | 0.1 | The Barn, Garden |
| Pumpkin | 4.5 | 0.3 | The Barn, Garden |
| Sugar Cane | 2 | 0.1 | Mushroom Desert, Garden |
| Cactus | 2 | 0.2 | Mushroom Desert, Garden |
| Red / Brown Mushroom | 6 | 0.3 (0.5 in minion module) (?) | Mushroom Desert, Garden |
| Cocoa Beans | 4 | 0.2 | Mushroom Desert, Jungle Island, Garden |
| Nether Wart | 4 | 0 (0.2 in minion module) (?) | Village, Stronghold, Garden |
| Mushroom Spore | 6 | — | Mystic Marsh (Crimson Isle) |
| Glowing Mushroom | 6 | — | Glowing Mushroom Cave |
| Moonflower / Sunflower / Wild Rose | 4 | 0.2 (?) | Garden |
| Raw Porkchop, Leather, Raw Chicken, Feather, Egg, Raw Rabbit, Rabbit Hide, Rabbit's Foot, Raw Mutton, Wool | 2 each (Raw Beef 1) | 0.1–0.2 | The Barn / Mushroom Desert animals |
| Pest kill | 1,500 (Field Mouse 6,000) | — | Garden |
| Garden crop milestone | 100–50,000 flat (not boosted by wisdom) | — | Garden |

Farming level cap (Anita, Farmhouse) — each step needs Gold medals from Jacob's contests in *distinct* crops plus Jacob's Tickets:

| Cap → | 51 | 52 | 53 | 54 | 55 | 56 | 57 | 58 | 59 | 60 |
|---|---|---|---|---|---|---|---|---|---|---|
| Gold medals | 1 | 1 | 1 | 1 | 1 | 2 | 2 | 2 | 2 | 3 |
| Jacob's Tickets | 0 | 50 | 50 | 100 | 100 | 150 | 150 | 200 | 250 | 500 |

#### 2.4.2 Mining — XP per block

| Block / item | XP (manual) | Minion XP | Where |
|---|---:|---:|---|
| Cobblestone / Stone | 1 | 0.1 | Coal Mine, Gold Mine, Deep Caverns, Dwarven Mines |
| Snowball | — | 0.1 | Snow Minion only |
| Ice | 0.2 (?) | 0.5 | Private Island, Jerry's Workshop |
| Netherrack | 0.5 | — | Crimson Isle |
| Sand | 3 | 0.2 | Mushroom Desert |
| Gravel | 4 | 0.2 | Gravel Mines |
| Nether Quartz | 5 | 0.3 | Crimson Isle |
| End Stone | 3 | 0.4 | The End |
| Coal | 5 | 0.3 | Coal Mine, Gold Mine, Gunpowder Mines, Crystal Hollows |
| Iron | 5 | 0.3 | Gold Mine, Gunpowder Mines, Crystal Hollows |
| Gold | 6 | 0.4 | Gold Mine, Gunpowder Mines, Crystal Hollows |
| Dwarven Gold (Block of Gold) | 20 | — | Dwarven Mines, Mines of Divan |
| Glowstone | 7 | 0.2 | Crimson Isle |
| Lapis Lazuli | 7 | 0.1 | Lapis Quarry, Crystal Hollows |
| Redstone | 7 | 0.2 | Pigmen's Den, Crystal Hollows |
| Emerald | 9 | 0.4 | Slimehill, Crystal Hollows |
| Diamond | 10 | 0.4 | Diamond Reserve, Obsidian Sanctuary, Crystal Hollows |
| Block of Diamond | 15 | 3.6 | Obsidian Sanctuary, Dwarven Mines |
| Obsidian | 20 | 0.4 | Obsidian Sanctuary, The End |
| Mithril | 45 | 0.4 | Dwarven Mines |
| Titanium | 100 | — | Dwarven Mines |
| Gemstone (any) | 70 (varies by gem/tier in current game (?)) | — | Crystal Hollows, Glacite Tunnels/Mineshafts |
| Commissions & commission milestones | flat amounts | — | Dwarven Mines / Crystal Hollows |

Mining also unlocks **reforge stones** at specific levels (full list in the Mining table above) — the clone's
reforge system should check `miningLevel >= requiredLevel` before applying a stone.

#### 2.4.3 Combat — XP sources

* Every mob type has a fixed Combat XP per kill (mob tables belong to the combat/bestiary research file).
* Slayer boss kills, Bestiary milestones, and Combat minion collection also grant Combat XP.
* **Combat Wisdom** multiplies it. Fishing XP from killing sea creatures is also boosted by Combat-XP buffs
  (slayer bonus, Combat XP potion, Viking's Tear, Hunter Ring).
* Warrior perk: +4% damage vs mobs per level for L1–50, then +1% per level for L51–60 (total +210%).

#### 2.4.4 Foraging — XP per action

| Item | XP | Where |
|---|---:|---|
| Flowers (any) | 1 | Flower House, The Park, Jungle |
| Oak / Birch / Spruce / Jungle / Acacia / Dark Oak Log | 6 | Hub Forest and The Park sub-zones |
| Fig Log | 15 | Galatea |
| Mangrove Log | 20 | Galatea |

Foraging minions give 0.1 XP per log (0.1–0.2 per flower). Foraging was reworked in 2025 (Galatea / "Foraging
update": Fig & Mangrove trees, Tree Gifts, Agatha, Heart of the Forest, Hunting skill) — expect further drift.

#### 2.4.5 Fishing — XP per catch and per sea creature

Ordinary catches (no sea creature): XP per item.

| Catch | Pool | Weight in pool | XP |
|---|---|---:|---:|
| Raw Fish (Cod) | normal | 60 | 25 |
| Raw Salmon | normal | 25 | 35 |
| Pufferfish | normal | 13 | 50 |
| Clownfish (Tropical Fish) | normal | 4 | 100 |
| Clay | normal | 6 | 30 |
| Coins / Fishing Bait / Golden Apple / Grand Experience Bottle / Music Disc / Sea Lantern / Sponge | GOOD CATCH | 5–15 each (pool total 88) | 160 |
| Prismarine Shard / Prismarine Crystals | GOOD CATCH | 5 each | 50 |
| Enchanted Clay / Diamond / Gold / Iron / Golden Apple (160–240), Enchanted Pufferfish (80), Titanic XP Bottle (40), Guardian Pet C/U/R/E/L (16/8/4/2/1), Music Rune I (3), Coins (8) | GREAT CATCH | (pool total 1,042) | 300 |

Treasure split: when a catch rolls "treasure", it is **89% Good / 10% Great / 1% Outstanding**. Treasure
Chance stat (Fishing perk +0.1/level) raises the chance a catch is treasure at all. Base fishing time:
`ticks = random(400 − 10·Lure, …)` scaled by Fishing Speed (?).

Sea creatures (water, normal pool) — what the Fishing level unlocks:

| Sea creature | Mob lvl | Fishing lvl req | Base weight | HP | Fishing XP on kill |
|---|---:|---:|---:|---:|---:|
| Squid | 1 | 1 | 1,200 | 50 | 75 |
| Sea Walker | 4 | 1 | 800 | 100 | 150 |
| Night Squid (Dark Bait, night) | 6 | 3 | 1,100 | 4,000 | 270 |
| Sea Guardian | 10 | 5 | 600 | 5,000 | 250 |
| Sea Witch | 15 | 7 | 700 | 6,000 | 400 |
| Sea Archer | 15 | 9 | 550 | 7,000 | 169 |
| Rider of the Deep | 20 | 11 | 400 | 20,000 | 338 |
| Catfish | 23 | 13 | 250 | 26,000 | 405 |
| Carrot King (Carrot Bait) | 25 | 14 | 300 | 32,000 | 810 |
| Agarimoo | 35 | 15 | 950 | 55,000 | 80 |
| Sea Leech | 30 | 16 | 160 | 60,000 | 675 |
| Guardian Defender | 45 | 17 | 130 | 76,000 | 1,013 |
| Deep Sea Protector | 60 | 18 | 88 | 150,000 | 1,350 |
| Water Hydra | 100 | 19 | 18 | 500,000 | 2,025 |

Selected location/event creatures (same columns): Oasis Rabbit / Sheep (10, 10, 300/700, 6k, 350);
Water Worm (CH, 20, 15, 300, 50k, 240); Poisoned Water Worm (25, 17, 300, 75k, 270); Abyssal Miner (150, 24, 90, 2M, 568);
Scarecrow (Spooky, 9, 9, 1000, 4.5k, 420); Nightmare (24, 14, 550, 35k, 820); Werewolf (50, 17, 250, 50k, 1,235);
Phantom Fisher (160, 21, 90, 1M, 2,525); Grim Reaper (190, 26, 25, 3M, 3,950); Nurse/Blue/Tiger/Great White Shark
(Fishing Festival; req 5/10/18/24; XP 405/810/1,013/2,025); Backwater Bayou: Trash Gobbler 175, Dumpster Diver 250,
Banshee 1,000, Bayou Sludge 1,000, Alligator 1,250, Titanoboa 10,000; Hotspot: Frog Man 200, Snapping Turtle 500,
Blue Ringed Octopus 5,000, Wiki Tiki 20,000; Galatea: Bogged 225, Tadgang 150, Wetwing 650, Ent 270, The Loch Emperor 3,375, Nessie 7,500.
Lava (Crimson Isle, req 27–45): Magma Slug 730, Moogma 950, Lava Leech 1,400, Pyroclastic Worm 1,100, Lava Flame 2,100,
Fire Eel 2,200, Taurus 4,300, Thunder 12,000, Lord Jawbus 40,000; Plhlegblast 5,000. (Winter creatures parsed
ambiguously from the wiki — Frozen Steve ~101 XP, Frosty ~203, Grinch ~405, Yeti 4,050, Nutcracker 950, Reindrake (?) .)

Sea creatures killed on the private island give **no** Fishing XP. Sea creatures despawn when their owner leaves.

#### 2.4.6 Enchanting — XP formula

Spending X vanilla XP levels at an Enchanting Table or Anvil grants `Enchanting XP = 3.5 × X^1.5`
(1 level → 3.5, 5 → 39.1, 10 → 110.7, 30 → 575.1, 64 → 1,792). Combining *books with books* gives none.
Experience Bottles fished up (Grand/Titanic) and the **Experimentation Table** (unlocked at Enchanting X —
recipe in the API row above) are the big XP sources. Collection tiers often unlock "<Enchant> Exp Discount (−25%)",
which reduces the XP-level cost of that enchant at the table.

#### 2.4.7 Alchemy — XP per brew

Brewing XP is collected when the potion is taken out of the brewing stand; it depends on the **first
ingredient** added to the Awkward Potion (per potion, ×3 potions per stand). Base: Nether Wart (into water) 1 XP,
Awkward Potion 5 XP.

| Potion | Ingredient → XP |
|---|---|
| Speed | Sugar 5 · Enchanted Sugar 300 · Enchanted Sugar Cane 15,000 |
| Jump Boost | Rabbit's Foot 12 · Enchanted Rabbit Foot 600 |
| Experience | Lapis Lazuli 5 |
| Wounded | Netherrack 5 |
| Night Vision | Golden Carrot 10 |
| Invisibility | Fermented Spider Eye 10 |
| Poison | Spider Eye 10 |
| Healing | Glistering Melon 10 · Enchanted Melon 250 · Enchanted Glistering Melon 500 |
| Fire Resistance | Magma Cream 10 |
| Water Breathing | Pufferfish 12 · Enchanted Pufferfish 600 |
| Regeneration | Ghast Tear 30 · Enchanted Ghast Tear 120 |
| Strength | Blaze Powder 10 · Enchanted Blaze Powder 500 · Enchanted Blaze Rod 23,000 |
| Weakness (water bottle base) | Fermented Spider Eye 10 · Enchanted Spider Eye 0 · Enchanted Fermented Spider Eye 15,000 |
| Blindness | Ink Sack 0 |
| Haste | Coal 5 |
| Burning | Red Sand 5 |
| Knockback | Slimeball 10 |
| Venomous | Poisonous Potato 20 |
| Stun | Obsidian 15 |
| Archery | Feather 10 |
| Absorption | Gold Ingot 5 · Enchanted Gold 300 · Enchanted Gold Block 15,000 |
| Dodge | Raw Salmon 5 |
| Resistance | Cactus 10 · Enchanted Cactus Green 250 · Enchanted Cactus 500 |
| Mana | Mutton 0 (all mana tiers 0) |
| Agility | Enchanted Cake 400 |

#### 2.4.8 Carpentry, Taming, Hunting, Runecrafting, Social — XP rules

| Skill | How XP is earned |
|---|---|
| Carpentry | Unlocked by giving the Carpenter NPC 64 Wool (rewards the Carpentry Table recipe). XP per craft = **3% of the combined NPC sell price of the ingredients**; most vanilla recipes give none. Level 3 unlocks **Quick Crafting** (3 "best craftable" slots next to the output; Hypixel gates it to VIP+ — drop that gate). |
| Taming | Earned when an equipped (summoned) pet gains pet XP. Pet XP itself comes from the player's skill XP: matching-type pets get full XP, non-matching get ×0.33; Carpentry/Taming/Dungeoneering/Runecrafting/Social give pets nothing. The Taming-XP-per-pet-XP ratio is not documented (?) — suggested default: Taming XP = 25% of pet XP gained (?). Cap 50 → 60 by giving George one each of: Epic Rift Ferret, Epic Slug, Epic Spirit, Epic Giraffe, Epic Jellyfish, Epic Bal, Epic Baby Yeti, Legendary Black Cat, Rare Frost Wisp, Mythic Enderman pet. Kat pet-rarity upgrades require Taming 10 (Rare→Epic), 20 (Epic→Legendary), 25 (Legendary→Mythic). |
| Hunting | Added June 2025. Capturing an attribute shard grants XP by shard rarity: Common 75 · Uncommon 150 · Rare 300 · Epic 500 · Legendary 1,000. Syphoning shards gives small amounts (Common ~10, Uncommon ~150 (?)). Also Trap-Hunt and Salt-Hunt milestones (David Hunterborough) and collection milestones (Lushlilac IV, Tender Wood VIII, Vinesap VIII). Methods: Lasso (forest), Fishing Net (water), Huntaxe + Pocket Black Hole (combat), Salts (kill with salt active → charm chance), Huntraps (passive). |
| Runecrafting | Killing Runic mobs (4× HP, 2× damage, purple name, rare spawn, guaranteed rune drop) and bosses; attempting a rune fusion at Dusk's Runic Pedestal (success or fail). Hypixel multiplies XP by rank (×1 default capped at L3, VIP ×1.1, VIP+ ×1.25, MVP ×1.5, MVP+ ×2, MVP++ ×3) — **do not copy the rank gating**. A rune can only be applied if Runecrafting level ≥ rune level. |
| Social | 10 XP per *unique* guest who visits your island; 2 XP/minute while hosting guests; 1 XP/minute while visiting someone else. Unlocks purchasable island features from Amelia (table §2.3). |

---

## 3. Collections

### 3.1 Rules

| Rule | Detail |
|---|---|
| What counts | Items the player **obtains directly**: mined, harvested, dropped by mobs they killed, fished, and items **collected from their minions**. Items bought from the Bazaar / NPC merchants, received in trades, or bought on the AH do **not** count. Compacting/crafting does not count again. Sacks: items going straight into a sack still count (?). |
| Counter | Per-profile integer per collection item. The first unit obtained **discovers** the collection (before that it shows as "???" / locked in the menu). |
| Tiers | Each collection has 4–12 tiers (Roman numerals). Reaching a tier is immediate and permanent; the chat announces it. |
| Rewards per tier | Always **+4 SkyBlock XP**, plus one or more of: recipe unlocks (minion recipes are at tier I for most collections), "Exp Discount (−25%)" for an enchant, lump-sum skill XP, trades with NPCs, travel scrolls / island portals. |
| Co-op | Collection totals are the **sum of all members' contributions**; the menu shows each member's share. Unlocks apply to all members instantly. |
| Leaderboard | After 25,000 of an item, the menu shows the player's global rank for that collection. |
| Menu path | SkyBlock Menu → Collection (Painting icon) → category (Farming, Mining, Combat, Foraging, Fishing, Rift, Boss) → item → tier list (green = unlocked, yellow = in progress with % bar, red = locked). Also: Crafted Minions sub-menu, `/collection`. |
| Totals (current API) | **90 resource collections, 790 tiers** (Farming 20, Mining 25, Combat 11, Foraging 15, Fishing 12, Rift 7) — the "Collections" SkyBlock-XP task lists max 3,024 SB XP (= 756 tiers), so the wiki total predates the newest collections (Galatea/Rift additions). |

### 3.1.1 Threshold summary (all collections)

| Collection | Category | Max tier | Thresholds |
|---|---|---:|---|
| Cactus | Farming | 9 | 100 / 250 / 500 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Carrot | Farming | 9 | 100 / 250 / 500 / 1,750 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Cocoa Beans | Farming | 9 | 75 / 200 / 500 / 2,000 / 5,000 / 10,000 / 20,000 / 50,000 / 100,000 |
| Feather | Farming | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Leather | Farming | 11 | 50 / 100 / 250 / 500 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Melon Slice | Farming | 9 | 250 / 500 / 1,250 / 5,000 / 15,000 / 25,000 / 50,000 / 100,000 / 250,000 |
| Moonflower | Farming | 8 | 1,000 / 5,000 / 10,000 / 25,000 / 100,000 / 250,000 / 500,000 / 1,000,000 |
| Mushroom | Farming | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Nether Wart | Farming | 12 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 75,000 / 100,000 / 250,000 |
| Potato | Farming | 9 | 100 / 200 / 500 / 1,750 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Pumpkin | Farming | 11 | 40 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 250,000 |
| Raw Chicken | Farming | 10 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Raw Mutton | Farming | 10 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Raw Porkchop | Farming | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Raw Rabbit | Farming | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Seeds | Farming | 7 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 25,000 |
| Sugar Cane | Farming | 9 | 100 / 250 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 20,000 / 50,000 |
| Sunflower | Farming | 8 | 1,000 / 5,000 / 10,000 / 25,000 / 100,000 / 250,000 / 500,000 / 1,000,000 |
| Wheat | Farming | 11 | 50 / 100 / 250 / 500 / 1,000 / 2,500 / 10,000 / 15,000 / 25,000 / 50,000 / 100,000 |
| Wild Rose | Farming | 8 | 1,000 / 5,000 / 10,000 / 25,000 / 100,000 / 250,000 / 500,000 / 1,000,000 |
| Coal | Mining | 10 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Cobblestone | Mining | 10 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 40,000 / 70,000 |
| Diamond | Mining | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Emerald | Mining | 9 | 50 / 100 / 250 / 1,000 / 5,000 / 15,000 / 30,000 / 50,000 / 100,000 |
| End Stone | Mining | 10 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 15,000 / 25,000 / 50,000 |
| Gemstone | Mining | 11 | 100 / 250 / 1,000 / 2,500 / 5,000 / 25,000 / 100,000 / 250,000 / 500,000 / 1,000,000 / 2,000,000 |
| Glacite | Mining | 9 | 100 / 1,000 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 250,000 / 500,000 |
| Glowstone Dust | Mining | 7 | 50 / 100 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 |
| Gold Ingot | Mining | 10 | 50 / 100 / 250 / 500 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 500,000 |
| Gravel | Mining | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 15,000 / 50,000 |
| Hard Stone | Mining | 7 | 50 / 1,000 / 5,000 / 50,000 / 150,000 / 300,000 / 1,000,000 |
| Ice | Mining | 11 | 50 / 100 / 250 / 500 / 1,000 / 5,000 / 10,000 / 50,000 / 100,000 / 250,000 / 500,000 |
| Iron Ingot | Mining | 12 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 200,000 / 400,000 |
| Lapis Lazuli | Mining | 10 | 250 / 500 / 1,000 / 2,000 / 10,000 / 25,000 / 50,000 / 100,000 / 150,000 / 250,000 |
| Mithril | Mining | 9 | 50 / 250 / 2,500 / 10,000 / 50,000 / 100,000 / 250,000 / 500,000 / 1,000,000 |
| Mycelium | Mining | 10 | 50 / 500 / 750 / 1,000 / 2,500 / 10,000 / 15,000 / 25,000 / 50,000 / 100,000 |
| Nether Quartz | Mining | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Netherrack | Mining | 5 | 50 / 250 / 500 / 1,000 / 5,000 |
| Obsidian | Mining | 10 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Red Sand | Mining | 8 | 50 / 500 / 2,500 / 10,000 / 15,000 / 25,000 / 50,000 / 100,000 |
| Redstone Dust | Mining | 9 | 100 / 250 / 750 / 1,500 / 2,500 / 5,000 / 25,000 / 100,000 / 250,000 |
| Sand | Mining | 7 | 50 / 100 / 250 / 500 / 1,000 / 2,500 / 5,000 |
| Sulphur | Mining | 9 | 200 / 1,000 / 2,500 / 5,000 / 10,000 / 15,000 / 25,000 / 50,000 / 100,000 |
| Tungsten | Mining | 9 | 100 / 1,000 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 250,000 / 500,000 |
| Umber | Mining | 9 | 100 / 1,000 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 250,000 / 500,000 |
| Blaze Rod | Combat | 8 | 50 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Bone | Combat | 10 | 50 / 100 / 250 / 500 / 1,000 / 5,000 / 10,000 / 25,000 / 50,000 / 150,000 |
| Chili Pepper | Combat | 9 | 10 / 25 / 75 / 250 / 500 / 1,000 / 2,500 / 5,000 / 10,000 |
| Ender Pearl | Combat | 9 | 50 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 15,000 / 25,000 / 50,000 |
| Ghast Tear | Combat | 7 | 20 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 |
| Gunpowder | Combat | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Magma Cream | Combat | 8 | 50 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Rotten Flesh | Combat | 10 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Slimeball | Combat | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Spider Eye | Combat | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| String | Combat | 9 | 50 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 |
| Acacia Log | Foraging | 9 | 50 / 100 / 250 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 25,000 |
| Birch Log | Foraging | 9 | 50 / 100 / 250 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 25,000 |
| Dark Oak Log | Foraging | 9 | 50 / 100 / 250 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 25,000 |
| Fig Log | Foraging | 9 | 500 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 150,000 |
| Helix Log | Foraging | 9 | 500 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 250,000 |
| Honeycomb | Foraging | 9 | 50 / 200 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 20,000 / 50,000 |
| Jungle Log | Foraging | 9 | 50 / 100 / 250 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 25,000 |
| Lushlilac | Foraging | 5 | 25 / 50 / 100 / 250 / 500 |
| Mangrove Log | Foraging | 9 | 500 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 150,000 |
| Oak Log | Foraging | 9 | 50 / 100 / 250 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 25,000 |
| Ruby Veilshroom | Foraging | 9 | 25 / 100 / 250 / 500 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 |
| Sea Lumies | Foraging | 10 | 25 / 50 / 100 / 250 / 500 / 750 / 1,000 / 2,500 / 5,000 / 10,000 |
| Spruce Log | Foraging | 9 | 50 / 100 / 250 / 500 / 1,000 / 2,000 / 5,000 / 10,000 / 25,000 |
| Tender Wood | Foraging | 9 | 10 / 25 / 50 / 100 / 200 / 400 / 600 / 800 / 1,000 |
| Vinesap | Foraging | 9 | 10 / 25 / 50 / 100 / 200 / 400 / 600 / 800 / 1,000 |
| Clay Ball | Fishing | 7 | 50 / 100 / 250 / 1,000 / 1,500 / 2,500 / 5,000 |
| Ink Sac | Fishing | 9 | 20 / 40 / 100 / 200 / 400 / 800 / 1,500 / 2,500 / 4,000 |
| Lily Pad | Fishing | 11 | 10 / 50 / 100 / 200 / 500 / 1,500 / 3,000 / 6,000 / 10,000 / 25,000 / 50,000 |
| Lotus | Fishing | 11 | 25 / 100 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 / 250,000 |
| Magmafish | Fishing | 12 | 20 / 100 / 500 / 1,000 / 5,000 / 15,000 / 30,000 / 50,000 / 75,000 / 100,000 / 250,000 / 500,000 |
| Prismarine Crystals | Fishing | 7 | 10 / 25 / 50 / 100 / 200 / 400 / 800 |
| Prismarine Shard | Fishing | 7 | 10 / 25 / 50 / 100 / 200 / 400 / 800 |
| Pufferfish | Fishing | 10 | 20 / 50 / 100 / 150 / 400 / 800 / 2,400 / 4,800 / 9,000 / 18,000 |
| Raw Cod | Fishing | 11 | 20 / 50 / 100 / 250 / 500 / 1,000 / 2,500 / 15,000 / 30,000 / 45,000 / 60,000 |
| Raw Salmon | Fishing | 9 | 20 / 50 / 100 / 250 / 500 / 1,000 / 2,500 / 5,000 / 10,000 |
| Sponge | Fishing | 9 | 20 / 50 / 100 / 250 / 500 / 1,000 / 1,500 / 2,000 / 4,000 |
| Tropical Fish | Fishing | 9 | 10 / 25 / 50 / 100 / 200 / 400 / 800 / 1,600 / 4,000 |
| Agaricus Cap | Rift | 4 | 20 / 60 / 100 / 200 |
| Caducous Stem | Rift | 4 | 20 / 60 / 150 / 500 |
| Half-Eaten Carrot | Rift | 4 | 50 / 400 / 1,000 / 3,500 |
| Hemovibe | Rift | 9 | 50 / 250 / 1,000 / 2,500 / 5,000 / 10,000 / 25,000 / 50,000 / 100,000 |
| Living Metal Heart | Rift | 4 | 1 / 20 / 60 / 100 |
| Timite | Rift | 6 | 25 / 50 / 100 / 250 / 500 / 750 |
| Wilted Berberis | Rift | 4 | 20 / 60 / 140 / 400 |


### Full unlock tables by category

_Every collection tier in the API carries a "+4 SkyBlock XP" line: confirmed for all tiers._

### 3.2 Farming collections (20 items)

| Collection (API id) | Tier | Amount | Unlocks (each tier ALSO gives +4 SkyBlock XP unless noted) |
|---|---:|---:|---|
| **Cactus** (`CACTUS`) | I | 100 | Cactus Minion Recipes |
|  | II | 250 | Cactus Helmet Recipe; Cactus Chestplate Recipe; Cactus Leggings Recipe; Cactus Boots Recipe |
|  | III | 500 | Resistance Potion Recipe |
|  | IV | 1,000 | Enchanted Cactus Green Recipe |
|  | V | 2,500 | Desert Island Recipe |
|  | VI | 5,000 | Piercing Exp Discount (-25%) |
|  | VII | 10,000 | Thorns Exp Discount (-25%) |
|  | VIII | 25,000 | Enchanted Cactus Recipe |
|  | IX | 50,000 | +50,000 Farming Experience |
| **Carrot** (`CARROT_ITEM`) | I | 100 | Carrot Minion Recipes |
|  | II | 250 | Simple Carrot Candy Recipe |
|  | III | 500 | Carrot Bait Recipe |
|  | IV | 1,750 | Enchanted Carrot Recipe |
|  | V | 5,000 | Enchanted Carrot on a Stick Recipe |
|  | VI | 10,000 | Great Carrot Candy Recipe |
|  | VII | 25,000 | Enchanted Golden Carrot Recipe |
|  | VIII | 50,000 | Sprout Helmet Recipe; Sprout Chestplate Recipe; Sprout Leggings Recipe; Sprout Boots Recipe |
|  | IX | 100,000 | Superb Carrot Candy Recipe |
| **Cocoa Beans** (`INK_SACK:3`) | I | 75 | Cocoa Beans Minion Recipes |
|  | II | 200 | +50 Farming Experience |
|  | III | 500 | Portal to Mushroom Island Recipe |
|  | IV | 2,000 | Enchanted Cocoa Beans Recipe |
|  | V | 5,000 | Travel Scroll to Mushroom Island Recipe |
|  | VI | 10,000 | Enchanted Cookie Recipe |
|  | VII | 20,000 | Adrenaline Potion Recipe |
|  | VIII | 50,000 | Enchanted Book (Replenish I) Recipe |
|  | IX | 100,000 | +30,000 Farming Experience |
| **Feather** (`FEATHER`) | I | 50 | Projectile Protection Exp Discount (-25%) |
|  | II | 100 | Feather Falling Exp Discount (-25%) |
|  | III | 250 | Archery Potion Recipe |
|  | IV | 1,000 | Feather Talisman Recipe |
|  | V | 2,500 | Enchanted Feather Recipe |
|  | VI | 5,000 | Dragon Tracer Exp Discount (-25%) |
|  | VII | 10,000 | Feather Ring Recipe |
|  | VIII | 25,000 | Snipe Exp Discount (-25%) |
|  | IX | 50,000 | Feather Artifact Recipe |
| **Leather** (`LEATHER`) | I | 50 | Cow Minion Recipes |
|  | II | 100 | Cow Hat Recipe; Milk Bucket Trade |
|  | III | 250 | [Lvl 1] Horse Recipe; Small Backpack Recipe |
|  | IV | 500 | Enchanted Leather Recipe |
|  | V | 1,000 | Enchanted Raw Beef Recipe |
|  | VI | 2,500 | Medium Backpack Recipe |
|  | VII | 5,000 | +5,000 Farming Experience |
|  | VIII | 10,000 | Saddle Recipe |
|  | IX | 25,000 | Large Backpack Recipe |
|  | X | 50,000 | +50,000 Farming Experience |
|  | XI | 100,000 | Greater Backpack Recipe |
| **Melon Slice** (`MELON`) | I | 250 | Melon Minion Recipes |
|  | II | 500 | +100 Farming Experience |
|  | III | 1,250 | +250 Farming Experience |
|  | IV | 5,000 | Enchanted Melon Slice Recipe |
|  | V | 15,000 | Enchanted Glistering Melon Slice Recipe |
|  | VI | 25,000 | Enchanted Melon Recipe |
|  | VII | 50,000 | +10,000 Farming Experience |
|  | VIII | 100,000 | +20,000 Farming Experience |
|  | IX | 250,000 | +50,000 Farming Experience; Juicy Nozzle Recipe |
| **Moonflower** (`MOONFLOWER`) | I | 1,000 | Enchanted Moonflower Recipe |
|  | II | 5,000 | Nightswitch Recipe |
|  | III | 10,000 | [Lvl 1] Crow Recipe |
|  | IV | 25,000 | Compacted Moonflower Recipe |
|  | V | 100,000 | +50,000 Farming Experience |
|  | VI | 250,000 | Moonstone Recipe |
|  | VII | 500,000 | Enchanted Book (Crop Fever I) Recipe |
|  | VIII | 1,000,000 | Moonlight Crystal Recipe |
| **Mushroom** (`MUSHROOM_COLLECTION`) | I | 50 | Mushroom Minion Recipes |
|  | II | 100 | Mushroom Helmet Recipe; Mushroom Chestplate Recipe; Mushroom Leggings Recipe; Mushroom Boots Recipe |
|  | III | 250 | [Lvl 1] Bat Recipe |
|  | IV | 1,000 | Magical Mushroom Soup Recipe |
|  | V | 2,500 | Brown Mushroom Block Recipe; Red Mushroom Block Recipe |
|  | VI | 5,000 | Enchanted Red Mushroom Recipe; Enchanted Brown Mushroom Recipe |
|  | VII | 10,000 | Night Vision Charm Recipe; Mystical Mushroom Soup Recipe |
|  | VIII | 25,000 | Enchanted Brown Mushroom Block Recipe; Enchanted Red Mushroom Block Recipe |
|  | IX | 50,000 | +50,000 Farming Experience |
| **Nether Wart** (`NETHER_STALK`) | I | 50 | Nether Wart Minion Recipes |
|  | II | 100 | Potion Bag |
|  | III | 250 | Potion Affinity Talisman Recipe |
|  | IV | 1,000 | Enchanted Nether Wart Recipe |
|  | V | 2,500 | +9 Potion Bag Slots |
|  | VI | 5,000 | +1,500 Farming Experience |
|  | VII | 10,000 | Potion Affinity Ring Recipe |
|  | VIII | 25,000 | +9 Potion Bag Slots |
|  | IX | 50,000 | Potion Affinity Artifact Recipe |
|  | X | 75,000 | +9 Potion Bag Slots |
|  | XI | 100,000 | Mutant Nether Wart Recipe |
|  | XII | 250,000 | +9 Potion Bag Slots |
| **Potato** (`POTATO_ITEM`) | I | 100 | Potato Minion Recipes |
|  | II | 200 | Portal to The Barn Recipe |
|  | III | 500 | Vaccine Talisman Recipe |
|  | IV | 1,750 | Enchanted Potato Recipe |
|  | V | 5,000 | Venomous Potion Recipe |
|  | VI | 10,000 | Travel Scroll to The Barn Recipe |
|  | VII | 25,000 | Enchanted Baked Potato Recipe |
|  | VIII | 50,000 | Hot Potato Book Recipe |
|  | IX | 100,000 | Tater Helmet Recipe; Tater Chestplate Recipe; Tater Leggings Recipe; Tater Boots Recipe |
| **Pumpkin** (`PUMPKIN`) | I | 40 | Pumpkin Minion Recipes |
|  | II | 100 | Farmer Orb Recipe |
|  | III | 250 | Enchanted Pumpkin Recipe |
|  | IV | 1,000 | Spooky Bait Recipe; Cubism Exp Discount (-25%) |
|  | V | 2,500 | Training Dummy Recipe |
|  | VI | 5,000 | +5,000 Farming Experience |
|  | VII | 10,000 | Lantern Helmet Recipe |
|  | VIII | 25,000 | Farm Crystal Recipe |
|  | IX | 50,000 | Farmer Boots Recipe |
|  | X | 100,000 | Polished Pumpkin Recipe |
|  | XI | 250,000 | Rancher's Boots Recipe |
| **Raw Chicken** (`RAW_CHICKEN`) | I | 50 | Chicken Minion Recipes |
|  | II | 100 | Bridge Egg Recipe |
|  | III | 250 | [Lvl 1] Chicken Recipe; Chicken Hat Recipe |
|  | IV | 1,000 | Enchanted Raw Chicken Recipe |
|  | V | 2,500 | Enchanted Egg Recipe |
|  | VI | 5,000 | +5,000 Farming Experience |
|  | VII | 10,000 | Enchanted Cake Recipe |
|  | VIII | 25,000 | Agility Potion Recipe |
|  | IX | 50,000 | Super Enchanted Egg Recipe |
|  | X | 100,000 | Omega Enchanted Egg Recipe |
| **Raw Mutton** (`MUTTON`) | I | 50 | Sheep Minion Recipes |
|  | II | 100 | Beginner Husbandry Sack Recipe |
|  | III | 250 | [Lvl 1] Sheep Recipe |
|  | IV | 1,000 | Mana Potion Recipe |
|  | V | 2,500 | Enchanted Raw Mutton Recipe; Small Husbandry Sack Recipe |
|  | VI | 5,000 | Rainbow Exp Discount (-25%) |
|  | VII | 10,000 | Medium Husbandry Sack Recipe |
|  | VIII | 25,000 | Enchanted Cooked Mutton Recipe |
|  | IX | 50,000 | Horns of Torment Recipe; Large Husbandry Sack Recipe |
|  | X | 100,000 | Large Enchanted Husbandry Sack Recipe |
| **Raw Porkchop** (`PORK`) | I | 50 | Pig Minion Recipes |
|  | II | 100 | +100 Farming Experience |
|  | III | 250 | [Lvl 1] Pigman Recipe; Enchanted Raw Porkchop Recipe |
|  | IV | 1,000 | +1,000 Farming Experience |
|  | V | 2,500 | Piggy Bank Recipe |
|  | VI | 5,000 | +5,000 Farming Experience |
|  | VII | 10,000 | Enchanted Cooked Porkchop Recipe |
|  | VIII | 25,000 | +25,000 Farming Experience |
|  | IX | 50,000 | Pigman Sword Recipe |
| **Raw Rabbit** (`RABBIT`) | I | 50 | Rabbit Minion Recipes |
|  | II | 100 | Rabbit Helmet Recipe; Rabbit Chestplate Recipe; Rabbit Leggings Recipe; Rabbit Boots Recipe |
|  | III | 250 | Rabbit Potion Recipe; [Lvl 1] Rabbit Recipe |
|  | IV | 1,000 | Enchanted Rabbit Foot Recipe; Enchanted Raw Rabbit Recipe |
|  | V | 2,500 | Luck Exp Discount (-25%) |
|  | VI | 5,000 | Enchanted Rabbit Hide Recipe |
|  | VII | 10,000 | Luck of the Sea Exp Discount (-25%); Enchanted Cooked Rabbit Recipe |
|  | VIII | 25,000 | Pet Luck Potion Recipe |
|  | IX | 50,000 | +50,000 Farming Experience |
| **Seeds** (`SEEDS`) | I | 50 | Dirt Trade |
|  | II | 100 | Clay Ball Trade |
|  | III | 250 | Long Grass Trade; Enchanted Seeds Recipe |
|  | IV | 1,000 | Fern Trade |
|  | V | 2,500 | Dead Bush Trade |
|  | VI | 5,000 | Double Tallgrass Trade |
|  | VII | 25,000 | Box of Seeds Recipe |
| **Sugar Cane** (`SUGAR_CANE`) | I | 100 | Sugar Cane Minion Recipes |
|  | II | 250 | Speed Talisman Recipe |
|  | III | 500 | Enchanted Sugar Recipe |
|  | IV | 1,000 | Speedster Helmet Recipe; Speedster Chestplate Recipe; Speedster Leggings Recipe; Speedster Boots Recipe |
|  | V | 2,000 | Speed Ring Recipe |
|  | VI | 5,000 | Enchanted Paper Recipe |
|  | VII | 10,000 | Magic For Pets In A Hurry Recipe |
|  | VIII | 20,000 | Enchanted Sugar Cane Recipe |
|  | IX | 50,000 | Speed Artifact Recipe |
| **Sunflower** (`DOUBLE_PLANT`) | I | 1,000 | Enchanted Sunflower Recipe |
|  | II | 5,000 | Sunflower Head Recipe; Sunflower Minion Recipes |
|  | III | 10,000 | Dayswitch Recipe |
|  | IV | 25,000 | Compacted Sunflower Recipe |
|  | V | 100,000 | Sunflower Oil Recipe |
|  | VI | 250,000 | Sunstone Recipe |
|  | VII | 500,000 | Sundial Recipe |
|  | VIII | 1,000,000 | Sunshine Crystal Recipe |
| **Wheat** (`WHEAT`) | I | 50 | Wheat Minion Recipes |
|  | II | 100 | Beginner Agronomy Sack Recipe; Farmhand Helmet Recipe; Farmhand Chestplate Recipe; Farmhand Leggings Recipe; Farmhand Boots Recipe |
|  | III | 250 | Harvesting Exp Discount (-25%) |
|  | IV | 500 | Enchanted Wheat Recipe |
|  | V | 1,000 | Small Agronomy Sack Recipe; Enchanted Bread Recipe |
|  | VI | 2,500 | Haymaker Helmet Recipe; Haymaker Chestplate Recipe; Haymaker Leggings Recipe; Haymaker Boots Recipe; Farming Island Recipe |
|  | VII | 10,000 | Farming Talisman Recipe |
|  | VIII | 15,000 | Medium Agronomy Sack Recipe |
|  | IX | 25,000 | +25,000 Farming Experience |
|  | X | 50,000 | Large Agronomy Sack Recipe |
|  | XI | 100,000 | Enchanted Hay Bale Recipe; Large Enchanted Agronomy Sack Recipe |
| **Wild Rose** (`WILD_ROSE`) | I | 1,000 | Enchanted Wild Rose Recipe |
|  | II | 5,000 | Thorny Vines Recipe |
|  | III | 10,000 | Plot Eraser Recipe |
|  | IV | 25,000 | Compacted Wild Rose Recipe |
|  | V | 100,000 | +50,000 Farming Experience |
|  | VI | 250,000 | Rosewater Flask Recipe |
|  | VII | 500,000 | Bachelor's Rose Recipe |
|  | VIII | 1,000,000 | Prickly Kiss Recipe |

### 3.3 Mining collections (25 items)

| Collection (API id) | Tier | Amount | Unlocks (each tier ALSO gives +4 SkyBlock XP unless noted) |
|---|---:|---:|---|
| **Coal** (`COAL`) | I | 50 | Coal Minion Recipes |
|  | II | 100 | Beginner Mining Sack Recipe; Smelting Touch Exp Discount (-25%) |
|  | III | 250 | Enchanted Coal Recipe; Haste Potion Recipe |
|  | IV | 1,000 | [Lvl 1] Wither Skeleton Recipe |
|  | V | 2,500 | Enchanted Charcoal Recipe; Small Mining Sack Recipe |
|  | VI | 5,000 | Travel Scroll to the Gold Mine Recipe |
|  | VII | 10,000 | Enchanted Coal Block Recipe; Medium Mining Sack Recipe |
|  | VIII | 25,000 | Enchanted Lava Bucket Recipe |
|  | IX | 50,000 | Large Mining Sack Recipe |
|  | X | 100,000 | Large Enchanted Mining Sack Recipe |
| **Cobblestone** (`COBBLESTONE`) | I | 50 | Cobblestone Minion Recipes |
|  | II | 100 | Stone Platform Recipe |
|  | III | 250 | [Lvl 1] Silverfish Recipe; Auto Smelter Recipe |
|  | IV | 1,000 | Enchanted Cobblestone Recipe |
|  | V | 2,500 | Compactor Recipe |
|  | VI | 5,000 | +1,000 Mining Experience |
|  | VII | 10,000 | Haste Ring Recipe |
|  | VIII | 25,000 | Hyper Furnace Recipe |
|  | IX | 40,000 | Haste Artifact Recipe |
|  | X | 70,000 | Super Compactor 3000 Recipe |
| **Diamond** (`DIAMOND`) | I | 50 | Diamond Minion Recipes |
|  | II | 100 | Execute Exp Discount (-25%) |
|  | III | 250 | Portal to the Deep Caverns Recipe |
|  | IV | 1,000 | Enchanted Diamond Recipe |
|  | V | 2,500 | Critical Exp Discount (-25%) |
|  | VI | 5,000 | Diamond Spreading Recipe |
|  | VII | 10,000 | Hardened Diamond Helmet Recipe; Hardened Diamond Chestplate Recipe; Hardened Diamond Leggings Recipe; Hardened Diamond Boots Recipe |
|  | VIII | 25,000 | Enchanted Diamond Block Recipe |
|  | IX | 50,000 | Perfect Helmet - Tier I Recipe; Perfect Chestplate - Tier I Recipe; Perfect Leggings - Tier I Recipe; Perfect Boots - Tier I Recipe |
| **Emerald** (`EMERALD`) | I | 50 | Emerald Minion Recipes |
|  | II | 100 | Talisman of Coins Recipe |
|  | III | 250 | Magnetic Talisman Recipe |
|  | IV | 1,000 | Enchanted Emerald Recipe |
|  | V | 5,000 | Emerald Ring Recipe |
|  | VI | 15,000 | Access to /bank (Requires Cookie Buff); Personal Bank Item Recipe |
|  | VII | 30,000 | Enchanted Emerald Block Recipe |
|  | VIII | 50,000 | Emerald Blade Recipe |
|  | IX | 100,000 | Emerald Armor Helmet Recipe; Emerald Armor Chestplate Recipe; Emerald Armor Leggings Recipe; Emerald Armor Boots Recipe |
| **End Stone** (`ENDER_STONE`) | I | 50 | End Stone Minion Recipes |
|  | II | 100 | End Biome Stick Recipe |
|  | III | 250 | Portal to The End Recipe; [Lvl 1] Endermite Recipe |
|  | IV | 1,000 | Enchanted End Stone Recipe |
|  | V | 2,500 | Silence Block Recipe |
|  | VI | 5,000 | Haste Block Recipe |
|  | VII | 10,000 | +10,000 Mining Experience |
|  | VIII | 15,000 | Travel Scroll to the End Recipe |
|  | IX | 25,000 | Catalyst Recipe |
|  | X | 50,000 | End Stone Sword Recipe |
| **Gemstone** (`GEMSTONE_COLLECTION`) | I | 100 | Small Gemstone Sack Recipe |
|  | II | 250 | Flawed Ruby Gemstone Recipe; Flawed Jade Gemstone Recipe; Flawed Sapphire Gemstone Recipe; Flawed Amethyst Gemstone Recipe; Flawed Amber Gemstone Recipe; Flawed Topaz Gemstone Recipe; Flawed Jasper Gemstone Recipe; Flawed Opal Gemstone Recipe; Flawed Onyx Gemstone Recipe; Flawed Citrine Gemstone Recipe; Flawed Aquamarine Gemstone Recipe; Flawed Peridot Gemstone Recipe |
|  | III | 1,000 | Medium Gemstone Sack Recipe |
|  | IV | 2,500 | Talisman of Power Recipe |
|  | V | 5,000 | Fine Ruby Gemstone Recipe; Fine Jade Gemstone Recipe; Fine Sapphire Gemstone Recipe; Fine Amethyst Gemstone Recipe; Fine Amber Gemstone Recipe; Fine Topaz Gemstone Recipe; Fine Jasper Gemstone Recipe; Fine Opal Gemstone Recipe; Fine Onyx Gemstone Recipe; Fine Citrine Gemstone Recipe; Fine Aquamarine Gemstone Recipe; Fine Peridot Gemstone Recipe |
|  | VI | 25,000 | Ring of Power Recipe; Large Gemstone Sack Recipe |
|  | VII | 100,000 | Ruby Power Scroll Recipe; Sapphire Power Scroll Recipe; Jasper Power Scroll Recipe; Amethyst Power Scroll Recipe; Amber Power Scroll Recipe; Opal Power Scroll Recipe |
|  | VIII | 250,000 | Enchanted Book (Prismatic I) Recipe; [Lvl 1] Precursor Drone Recipe |
|  | IX | 500,000 | Flawless Ruby Gemstone Recipe; Flawless Jade Gemstone Recipe; Flawless Sapphire Gemstone Recipe; Flawless Amethyst Gemstone Recipe; Flawless Amber Gemstone Recipe; Flawless Topaz Gemstone Recipe; Flawless Jasper Gemstone Recipe; Flawless Opal Gemstone Recipe; Flawless Onyx Gemstone Recipe; Flawless Citrine Gemstone Recipe; Flawless Aquamarine Gemstone Recipe; Flawless Peridot Gemstone Recipe |
|  | X | 1,000,000 | Artifact of Power Recipe; Perfect Ruby Gemstone Dwarven Forge Recipe; Perfect Jade Gemstone Dwarven Forge Recipe; Perfect Sapphire Gemstone Dwarven Forge Recipe; Perfect Amethyst Gemstone Dwarven Forge Recipe; Perfect Amber Gemstone Dwarven Forge Recipe; Perfect Topaz Gemstone Dwarven Forge Recipe; Perfect Jasper Gemstone Dwarven Forge Recipe; Perfect Opal Gemstone Dwarven Forge Recipe; Perfect Onyx Gemstone Dwarven Forge Recipe; Perfect Citrine Gemstone Dwarven Forge Recipe; Perfect Aquamarine Gemstone Dwarven Forge Recipe; Perfect Peridot Gemstone Dwarven Forge Recipe |
|  | XI | 2,000,000 | Gemstone Gauntlet Recipe |
| **Glacite** (`GLACITE`) | I | 100 | +1 Mining Fortune |
|  | II | 1,000 | Enchanted Glacite Recipe; Cold Resistance Potion Recipe |
|  | III | 5,000 | Glacite Amalgamation Dwarven Forge Recipe |
|  | IV | 10,000 | [Lvl 1] Glacite Golem Recipe |
|  | V | 25,000 | Frigid Husk Dwarven Forge Recipe |
|  | VI | 50,000 | +1 Mining Fortune |
|  | VII | 100,000 | +1 Mining Fortune |
|  | VIII | 250,000 | +1 Mining Fortune |
|  | IX | 500,000 | +10,000 Mining Experience |
| **Glowstone Dust** (`GLOWSTONE_DUST`) | I | 50 | Glowstone Minion Recipes |
|  | II | 100 | Portal to the Crimson Isle Recipe |
|  | III | 1,000 | Enchanted Glowstone Dust Recipe |
|  | IV | 2,500 | Travel Scroll to the Crimson Isle Recipe; Glowstone Gauntlet Recipe |
|  | V | 5,000 | Enchanted Glowstone Recipe |
|  | VI | 10,000 | Enchanted Redstone Lamp Recipe; Shiny Prism Recipe |
|  | VII | 25,000 | Vanquished Glowstone Gauntlet Recipe |
| **Gold Ingot** (`GOLD_INGOT`) | I | 50 | Gold Minion Recipes |
|  | II | 100 | Cleaver Recipe |
|  | III | 250 | Looting Exp Discount (-25%) |
|  | IV | 500 | Portal to the Gold Mine Recipe |
|  | V | 1,000 | Enchanted Gold Ingot Recipe |
|  | VI | 2,500 | Absorption Potion Recipe |
|  | VII | 5,000 | Scavenger Exp Discount (-25%) |
|  | VIII | 10,000 | Enchanted Gold Block Recipe |
|  | IX | 25,000 | Fortune Exp Discount (-25%) |
|  | X | 500,000 | Enchanted Clock Recipe |
| **Gravel** (`GRAVEL`) | I | 50 | Gravel Minion Recipes |
|  | II | 100 | Flint Shovel Recipe |
|  | III | 250 | Portal to the Spider's Den Recipe |
|  | IV | 1,000 | Sharpness Exp Discount (-25%) |
|  | V | 2,500 | Enchanted Flint Recipe |
|  | VI | 5,000 | First Strike Exp Discount (-25%) |
|  | VII | 10,000 | Critical Potion Recipe |
|  | VIII | 15,000 | Travel Scroll to Spider's Den Recipe |
|  | IX | 50,000 | +5,000 Mining Experience |
| **Hard Stone** (`HARD_STONE`) | I | 50 | Hard Stone Minion Recipes |
|  | II | 1,000 | Heat Helmet Recipe; Heat Chestplate Recipe; Heat Leggings Recipe; Heat Boots Recipe |
|  | III | 5,000 | Enchanted Hard Stone Recipe |
|  | IV | 50,000 | Scorched Topaz Dwarven Forge Recipe |
|  | V | 150,000 | Flamebreaker Helmet Recipe; Flamebreaker Chestplate Recipe; Flamebreaker Leggings Recipe; Flamebreaker Boots Recipe |
|  | VI | 300,000 | Concentrated Stone Recipe |
|  | VII | 1,000,000 | Silex Recipe |
| **Ice** (`ICE`) | I | 50 | Ice Minion Recipes |
|  | II | 100 | Ice Bait Recipe |
|  | III | 250 | Packed Ice Recipe |
|  | IV | 500 | Enchanted Ice Recipe |
|  | V | 1,000 | Magical Water Bucket Recipe |
|  | VI | 5,000 | Glacial Ring Recipe |
|  | VII | 10,000 | Enchanted Packed Ice Recipe; Ice Cube Recipe |
|  | VIII | 50,000 | Glacial Artifact Recipe |
|  | IX | 100,000 | Frozen Blaze Helmet Recipe; Frozen Blaze Chestplate Recipe; Frozen Blaze Leggings Recipe; Frozen Blaze Boots Recipe |
|  | X | 250,000 | Frozen Scythe Recipe |
|  | XI | 500,000 | Glacial Scythe Recipe |
| **Iron Ingot** (`IRON_INGOT`) | I | 50 | Iron Minion Recipes |
|  | II | 100 | Golem Hat Recipe; Prospecting Helmet Recipe; Prospecting Chestplate Recipe; Prospecting Leggings Recipe; Prospecting Boots Recipe |
|  | III | 250 | Protection Exp Discount (-25%) |
|  | IV | 1,000 | Enchanted Iron Ingot Recipe |
|  | V | 2,500 | Budget Hopper Recipe |
|  | VI | 5,000 | Golem Armor Helmet Recipe; Golem Armor Chestplate Recipe; Golem Armor Leggings Recipe; Golem Armor Boots Recipe |
|  | VII | 10,000 | Enchanted Iron Block Recipe |
|  | VIII | 25,000 | Golem Sword Recipe |
|  | IX | 50,000 | Enchanted Hopper Recipe; Personal Deletor 4000 Recipe |
|  | X | 100,000 | Personal Deletor 5000 Recipe |
|  | XI | 200,000 | Personal Deletor 6000 Recipe |
|  | XII | 400,000 | Personal Deletor 7000 Recipe |
| **Lapis Lazuli** (`INK_SACK:4`) | I | 250 | Lapis Minion Recipes |
|  | II | 500 | Experience Bottle Recipe |
|  | III | 1,000 | Lapis Pickaxe Recipe; Experience Exp Discount (-25%) |
|  | IV | 2,000 | Enchanted Lapis Lazuli Recipe |
|  | V | 10,000 | Grand Experience Bottle Recipe |
|  | VI | 25,000 | Experience Potion Recipe |
|  | VII | 50,000 | Enchanted Lapis Lazuli Block Recipe |
|  | VIII | 100,000 | Titanic Experience Bottle Recipe |
|  | IX | 150,000 | Experience Artifact Recipe |
|  | X | 250,000 | Textbook Recipe |
| **Mithril** (`MITHRIL_ORE`) | I | 50 | Mithril Minion Recipes |
|  | II | 250 | Enchanted Mithril Recipe |
|  | III | 2,500 | [Lvl 1] Mithril Golem Recipe; Spelunker Potion Recipe |
|  | IV | 10,000 | Mithril Crystal Recipe |
|  | V | 50,000 | +5,000 Mining Experience |
|  | VI | 100,000 | Dwarven Super Compactor Recipe |
|  | VII | 250,000 | Mithril Coat Recipe |
|  | VIII | 500,000 | Mithril Infusion Recipe |
|  | IX | 1,000,000 | Beacon I Recipe |
| **Mycelium** (`MYCEL`) | I | 50 | Mycelium Minion Recipes |
|  | II | 500 | Suspicious Stew Recipe |
|  | III | 750 | Enchanted Mycelium Recipe; [Lvl 1] Mooshroom Cow Recipe |
|  | IV | 1,000 | Mushroom Biome Stick Recipe |
|  | V | 2,500 | Mycelium Dust Recipe |
|  | VI | 10,000 | Corrupt Soil Recipe |
|  | VII | 15,000 | Enchanted Mycelium Cube Recipe |
|  | VIII | 25,000 | +10,000 Mining Experience |
|  | IX | 50,000 | Shimmering Light Hood Recipe; Shimmering Light Tunic Recipe; Shimmering Light Trousers Recipe; Shimmering Light Slippers Recipe |
|  | X | 100,000 | Gauntlet of Contagion Recipe |
| **Nether Quartz** (`QUARTZ`) | I | 50 | Quartz Minion Recipes |
|  | II | 100 | Night Saver Recipe |
|  | III | 250 | Day Saver Recipe |
|  | IV | 1,000 | Enchanted Nether Quartz Recipe |
|  | V | 2,500 | Minion Expander Recipe |
|  | VI | 5,000 | Enchanted Quartz Block Recipe |
|  | VII | 10,000 | Night Crystal Recipe |
|  | VIII | 25,000 | Day Crystal Recipe |
|  | IX | 50,000 | Solar Panel Recipe |
| **Netherrack** (`NETHERRACK`) | I | 50 | Nether Wart Island Recipe |
|  | II | 250 | Wounded Potion Recipe |
|  | III | 500 | Nether Brick Trade |
|  | IV | 1,000 | Enchanted Netherrack Recipe |
|  | V | 5,000 | Magical Lava Bucket Recipe |
| **Obsidian** (`OBSIDIAN`) | I | 50 | Obsidian Minion Recipes |
|  | II | 100 | Lethality Exp Discount (-25%) |
|  | III | 250 | Gravity Talisman Recipe |
|  | IV | 1,000 | Enchanted Obsidian Recipe |
|  | V | 2,500 | +1 Mining Fortune |
|  | VI | 5,000 | Stun Potion Recipe |
|  | VII | 10,000 | +1 Mining Fortune |
|  | VIII | 25,000 | +1 Mining Fortune |
|  | IX | 50,000 | Obsidian Tablet Recipe |
|  | X | 100,000 | +1 Mining Fortune; +50,000 Mining Experience |
| **Red Sand** (`SAND:1`) | I | 50 | Red Sand Minion Recipes |
|  | II | 500 | Enchanted Red Sand Recipe |
|  | III | 2,500 | [Lvl 1] Snail Recipe; Mesa Biome Stick Recipe |
|  | IV | 10,000 | Enchanted Red Sand Cube Recipe |
|  | V | 15,000 | Ancient Cloak Recipe |
|  | VI | 25,000 | +10,000 Mining Experience |
|  | VII | 50,000 | Berserker Helmet Recipe; Berserker Chestplate Recipe; Berserker Leggings Recipe; Berserker Boots Recipe |
|  | VIII | 100,000 | Everburning Flame Recipe |
| **Redstone Dust** (`REDSTONE`) | I | 100 | Redstone Minion Recipes |
|  | II | 250 | Enchanted Redstone Dust Recipe; +6 Accessory Bag Slots |
|  | III | 750 | Efficiency Exp Discount (-25%); +6 Accessory Bag Slots |
|  | IV | 1,500 | Weather Stick Recipe; +6 Accessory Bag Slots |
|  | V | 2,500 | Travel Scroll to Deep Caverns Recipe; +6 Accessory Bag Slots |
|  | VI | 5,000 | Personal Compactor 4000 Recipe; +6 Accessory Bag Slots |
|  | VII | 25,000 | Enchanted Redstone Block Recipe; Personal Compactor 5000 Recipe; +6 Accessory Bag Slots |
|  | VIII | 100,000 | Personal Compactor 6000 Recipe; +6 Accessory Bag Slots |
|  | IX | 250,000 | Personal Compactor 7000 Recipe; +6 Accessory Bag Slots |
| **Sand** (`SAND`) | I | 50 | Sand Minion Recipes |
|  | II | 100 | +250 Mining Experience |
|  | III | 250 | Soul Sand Trade |
|  | IV | 500 | Desert Biome Stick Recipe |
|  | V | 1,000 | Enchanted Sand Recipe |
|  | VI | 2,500 | Burning Potion Recipe |
|  | VII | 5,000 | Hard Glass Recipe |
| **Sulphur** (`SULPHUR_ORE`) | I | 200 | Match-Sticks Recipe |
|  | II | 1,000 | Enchanted Sulphur Recipe |
|  | III | 2,500 | Small Nether Sack Recipe |
|  | IV | 5,000 | Totem of Corruption Recipe |
|  | V | 10,000 | Medium Nether Sack Recipe |
|  | VI | 15,000 | Enchanted Sulphur Cube Recipe |
|  | VII | 25,000 | Sulphur Bow Recipe |
|  | VIII | 50,000 | Large Nether Sack Recipe |
|  | IX | 100,000 | Implosion Belt Recipe |
| **Tungsten** (`TUNGSTEN`) | I | 100 | +1 Mining Fortune |
|  | II | 1,000 | Enchanted Tungsten Recipe |
|  | III | 5,000 | Refined Tungsten Dwarven Forge Recipe |
|  | IV | 10,000 | Tungsten Key Dwarven Forge Recipe |
|  | V | 25,000 | +1 Mining Fortune |
|  | VI | 50,000 | Tungsten Plate Dwarven Forge Recipe |
|  | VII | 100,000 | +1 Mining Fortune |
|  | VIII | 250,000 | +1 Mining Fortune |
|  | IX | 500,000 | +1 Mining Fortune |
| **Umber** (`UMBER`) | I | 100 | +1 Mining Fortune |
|  | II | 1,000 | Enchanted Umber Recipe |
|  | III | 5,000 | Refined Umber Dwarven Forge Recipe |
|  | IV | 10,000 | Umber Key Dwarven Forge Recipe |
|  | V | 25,000 | +1 Mining Fortune |
|  | VI | 50,000 | Umber Plate Dwarven Forge Recipe |
|  | VII | 100,000 | +1 Mining Fortune |
|  | VIII | 250,000 | +1 Mining Fortune |
|  | IX | 500,000 | Umberella Recipe |

### 3.4 Combat collections (11 items)

| Collection (API id) | Tier | Amount | Unlocks (each tier ALSO gives +4 SkyBlock XP unless noted) |
|---|---:|---:|---|
| **Blaze Rod** (`BLAZE_ROD`) | I | 50 | Blaze Minion Recipes |
|  | II | 250 | Fire Aspect Exp Discount (-25%) |
|  | III | 1,000 | Enchanted Blaze Powder Recipe |
|  | IV | 2,500 | Fire Talisman Recipe; Blaze Belt Recipe |
|  | V | 5,000 | Flame Exp Discount (-25%) |
|  | VI | 10,000 | Enchanted Blaze Rod Recipe; Blaze Wax Recipe; [Lvl 1] Blaze Recipe |
|  | VII | 25,000 | Blaze Helmet Recipe; Blaze Chestplate Recipe; Blaze Leggings Recipe; Blaze Boots Recipe; Vanquished Blaze Belt Recipe |
|  | VIII | 50,000 | +10,000 Combat Experience |
| **Bone** (`BONE`) | I | 50 | Skeleton Minion Recipes |
|  | II | 100 | Enchanted Bone Meal Trade |
|  | III | 250 | [Lvl 1] Skeleton Recipe; Power Exp Discount (-25%) |
|  | IV | 500 | Skeleton Hat Recipe |
|  | V | 1,000 | Enchanted Bone Recipe |
|  | VI | 5,000 | +1,000 Combat Experience |
|  | VII | 10,000 | Hurricane Bow Recipe |
|  | VIII | 25,000 | Skeleton's Helmet Recipe |
|  | IX | 50,000 | Runaan's Bow Recipe |
|  | X | 150,000 | Enchanted Bone Block Recipe |
| **Chili Pepper** (`CHILI_PEPPER`) | I | 10 | Re-heated Gummy Polar Bear Recipe |
|  | II | 25 | Sulphuric Coal Recipe |
|  | III | 75 | Capsaicin Eyedrops Recipe |
|  | IV | 250 | Entropy Suppressor Recipe |
|  | V | 500 | Enchanted Book (Cayenne IV) Recipe |
|  | VI | 1,000 | Stuffed Chili Pepper Recipe |
|  | VII | 2,500 | Jalapeno Book Recipe |
|  | VIII | 5,000 | Enchanted Book (Habanero Tactics IV) Recipe |
|  | IX | 10,000 | Enchanted Book (Tabasco II) Recipe |
| **Ender Pearl** (`ENDER_PEARL`) | I | 50 | Enderman Minion Recipes; Silent Pearl Recipe |
|  | II | 250 | Enchanted Ender Pearl Recipe |
|  | III | 1,000 | Ender Slayer Exp Discount (-25%) |
|  | IV | 2,500 | Small Dragon Sack Recipe |
|  | V | 5,000 | Ender Bow Recipe |
|  | VI | 10,000 | Medium Dragon Sack Recipe; Enchanted Eye of Ender Recipe |
|  | VII | 15,000 | Teleport Pad Trade; Absolute Ender Pearl Recipe |
|  | VIII | 25,000 | Aspect of the End Recipe |
|  | IX | 50,000 | Large Dragon Sack Recipe; Saving Grace Recipe |
| **Ghast Tear** (`GHAST_TEAR`) | I | 20 | Ghast Minion Recipes |
|  | II | 250 | Giant Killer Exp Discount (-25%) |
|  | III | 1,000 | Enchanted Ghast Tear Recipe |
|  | IV | 2,500 | Vampirism Exp Discount (-25%); Ghast Cloak Recipe |
|  | V | 5,000 | Silver Fang Recipe |
|  | VI | 10,000 | Meteor Chunk Recipe |
|  | VII | 25,000 | Vanquished Ghast Cloak Recipe |
| **Gunpowder** (`SULPHUR`) | I | 50 | Creeper Minion Recipes |
|  | II | 100 | Creeper Hat Recipe |
|  | III | 250 | Blast Protection Exp Discount (-25%) |
|  | IV | 1,000 | Enchanted Gunpowder Recipe |
|  | V | 2,500 | Thunderlord Exp Discount (-25%) |
|  | VI | 5,000 | Enchanted Firework Rocket Recipe |
|  | VII | 10,000 | +10,000 Combat Experience |
|  | VIII | 25,000 | Creeper Pants Recipe |
|  | IX | 50,000 | Explosive Bow Recipe |
| **Magma Cream** (`MAGMA_CREAM`) | I | 50 | Magma Cube Minion Recipes |
|  | II | 250 | Fire Protection Exp Discount (-25%) |
|  | III | 1,000 | Nether Biome Stick Recipe; Enchanted Magma Cream Recipe |
|  | IV | 2,500 | Magma Necklace Recipe |
|  | V | 5,000 | Lava Bucket Trade |
|  | VI | 10,000 | Lava Talisman Recipe; Searing Stone Recipe |
|  | VII | 25,000 | Vanquished Magma Necklace Recipe |
|  | VIII | 50,000 | Whipped Magma Cream Recipe |
| **Rotten Flesh** (`ROTTEN_FLESH`) | I | 50 | Zombie Minion Recipes |
|  | II | 100 | Beginner Combat Sack Recipe; Zombie Pickaxe Recipe |
|  | III | 250 | [Lvl 1] Zombie Recipe; Smite Exp Discount (-25%) |
|  | IV | 1,000 | Enchanted Rotten Flesh Recipe |
|  | V | 2,500 | Zombie Hat Recipe; Small Combat Sack Recipe |
|  | VI | 5,000 | Zombie's Heart Recipe |
|  | VII | 10,000 | Zombie Sword Recipe; Medium Combat Sack Recipe |
|  | VIII | 25,000 | Zombie Chestplate Recipe; Zombie Leggings Recipe; Zombie Boots Recipe |
|  | IX | 50,000 | Large Combat Sack Recipe |
|  | X | 100,000 | Large Enchanted Combat Sack Recipe |
| **Slimeball** (`SLIME_BALL`) | I | 50 | Slime Minion Recipes |
|  | II | 100 | Slime Hat Recipe |
|  | III | 250 | Knockback Exp Discount (-25%) |
|  | IV | 1,000 | Knockback Potion Recipe |
|  | V | 2,500 | Enchanted Slimeball Recipe |
|  | VI | 5,000 | Punch Exp Discount (-25%) |
|  | VII | 10,000 | Launch Pad Recipe |
|  | VIII | 25,000 | Enchanted Slime Block Recipe |
|  | IX | 50,000 | Slime Bow Recipe |
| **Spider Eye** (`SPIDER_EYE`) | I | 50 | Cave Spider Minion Recipes |
|  | II | 100 | Spider Sword Recipe |
|  | III | 250 | Spider Hat Recipe |
|  | IV | 1,000 | Enchanted Spider Eye Recipe |
|  | V | 2,500 | Bane of Arthropods Exp Discount (-25%) |
|  | VI | 5,000 | Venomous Exp Discount (-25%) |
|  | VII | 10,000 | Enchanted Fermented Spider Eye Recipe |
|  | VIII | 25,000 | +25,000 Combat Experience |
|  | IX | 50,000 | Leaping Sword Recipe |
| **String** (`STRING`) | I | 50 | Spider Minion Recipes |
|  | II | 100 | Web Recipe |
|  | III | 250 | [Lvl 1] Spider Recipe; Quiver |
|  | IV | 1,000 | Enchanted String Recipe; Grappling Hook Recipe |
|  | V | 2,500 | Silk Touch Exp Discount (-25%) |
|  | VI | 5,000 | Infinite Quiver Exp Discount (-25%); +9 Quiver Slots |
|  | VII | 10,000 | +20,000 Combat Experience |
|  | VIII | 25,000 | Spider's Boots Recipe |
|  | IX | 50,000 | +9 Quiver Slots |

### 3.5 Foraging collections (15 items)

| Collection (API id) | Tier | Amount | Unlocks (each tier ALSO gives +4 SkyBlock XP unless noted) |
|---|---:|---:|---|
| **Acacia Log** (`LOG_2`) | I | 50 | Acacia Minion Recipes |
|  | II | 100 | Acacia Leaves Trade |
|  | III | 250 | Enchanted Acacia Log Recipe; Portal to Savanna Woodland Recipe |
|  | IV | 500 | Savanna Bow Recipe |
|  | V | 1,000 | Savanna Biome Stick Recipe |
|  | VI | 2,000 | +2,000 Foraging Experience |
|  | VII | 5,000 | Repelling Candle Recipe |
|  | VIII | 10,000 | +10,000 Foraging Experience |
|  | IX | 25,000 | Acacia Birdhouse Recipe |
| **Birch Log** (`LOG:2`) | I | 50 | Birch Minion Recipes |
|  | II | 100 | Beginner Foraging Sack Recipe; Birch Leaves Trade |
|  | III | 250 | Portal to Birch Park Recipe |
|  | IV | 500 | Sculptor's Axe Recipe |
|  | V | 1,000 | Small Foraging Sack Recipe; Enchanted Birch Log Recipe |
|  | VI | 2,000 | Birch Forest Biome Stick Recipe |
|  | VII | 5,000 | Medium Foraging Sack Recipe |
|  | VIII | 10,000 | Large Foraging Sack Recipe |
|  | IX | 25,000 | Large Enchanted Foraging Sack Recipe |
| **Dark Oak Log** (`LOG_2:1`) | I | 50 | Dark Oak Minion Recipes |
|  | II | 100 | Dark Oak Leaves Trade |
|  | III | 250 | Enchanted Dark Oak Log Recipe; Portal to Dark Thicket Recipe |
|  | IV | 500 | Roofed Forest Island Recipe |
|  | V | 1,000 | Roofed Forest Biome Stick Recipe |
|  | VI | 2,000 | +2,000 Foraging Experience |
|  | VII | 5,000 | Growth Exp Discount (-25%) |
|  | VIII | 10,000 | +10,000 Foraging Experience |
|  | IX | 25,000 | Helmet of Growth Recipe; Chestplate of Growth Recipe; Leggings of Growth Recipe; Boots of Growth Recipe |
| **Fig Log** (`FIG_LOG`) | I | 500 | Tiny Scaffolding Recipe |
|  | II | 1,000 | Enchanted Fig Log Recipe |
|  | III | 2,500 | Medium Scaffolding Recipe |
|  | IV | 5,000 | Fig Hew Recipe |
|  | V | 10,000 | Large Scaffolding Recipe |
|  | VI | 25,000 | Figstone Recipe; Figstone Splitter Recipe |
|  | VII | 50,000 | Sparrow Shard Recipe |
|  | VIII | 100,000 | Fig Cap Recipe; Figmail Recipe; Fig Trousers Recipe; Fig Striders Recipe |
|  | IX | 150,000 | +100,000 Foraging Experience; +1 Foraging Level Cap |
| **Helix Log** (`HELIX_LOG`) | I | 500 | Travel Scroll to Torrhus Canyon Recipe |
|  | II | 1,000 | Enchanted Helix Log Recipe |
|  | III | 2,500 | Lumberjack Talisman Recipe |
|  | IV | 5,000 | Portal to the Torrhus Canyon Recipe |
|  | V | 10,000 | Lumberjack Ring Recipe |
|  | VI | 25,000 | Helixis Recipe; Helix Chopper Recipe |
|  | VII | 50,000 | Hawk Shard Recipe |
|  | VIII | 100,000 | Lumberjack Artifact Recipe; Helix Helmet Recipe; Helix Chestplate Recipe; Helix Leggings Recipe; Helix Boots Recipe |
|  | IX | 250,000 | +150,000 Foraging Experience; +1 Foraging Level Cap |
| **Honeycomb** (`HONEYCOMB`) | I | 50 | Honeycomb Talisman Recipe; Fun-Sized Pot of Honeycomb Recipe |
|  | II | 200 | Enchanted Honeycomb Recipe |
|  | III | 500 | Honeycomb Ring Recipe; Family-Sized Pot of Honeycomb Recipe |
|  | IV | 1,000 | Honey Badger Shard Recipe |
|  | V | 2,000 | Honeycomb Artifact Recipe; Jumbo Pot of Honeycomb Recipe |
|  | VI | 5,000 | Enchanted Honeycomb Block Recipe |
|  | VII | 10,000 | Queen Bee Shard Recipe |
|  | VIII | 20,000 | Bee Saliva Recipe |
|  | IX | 50,000 | Honeycomb Necklace Recipe |
| **Jungle Log** (`LOG:3`) | I | 50 | Jungle Minion Recipes |
|  | II | 100 | Jungle Leaves Trade |
|  | III | 250 | [Lvl 1] Ocelot Recipe; Enchanted Jungle Log Recipe; Portal to Jungle Island Recipe |
|  | IV | 500 | Vines Trade |
|  | V | 1,000 | Jungle Biome Stick Recipe |
|  | VI | 2,000 | +2,000 Foraging Experience |
|  | VII | 5,000 | Treecapitator Recipe |
|  | VIII | 10,000 | +10,000 Foraging Experience |
|  | IX | 25,000 | +10,000 Foraging Experience |
| **Lushlilac** (`LUSHLILAC`) | I | 25 | Lushlilac Bonbon Recipe |
|  | II | 50 | Oceandy Recipe |
|  | III | 100 | Prime Lushlilac Bonbon Recipe |
|  | IV | 250 | +1,000 Hunting Experience; +1,000 Foraging Experience |
|  | V | 500 | Exalted Lushlilac Bonbon Recipe |
| **Mangrove Log** (`MANGROVE_LOG`) | I | 500 | Travel Scroll to Murkwater Loch Recipe |
|  | II | 1,000 | Enchanted Mangrove Log Recipe |
|  | III | 2,500 | Mangrove Locket Recipe |
|  | IV | 5,000 | Moonglade Belt Recipe |
|  | V | 10,000 | Mangrove Gem Recipe |
|  | VI | 25,000 | Mangcore Recipe |
|  | VII | 50,000 | Seagull Shard Recipe |
|  | VIII | 100,000 | Mangrove Grippers Recipe |
|  | IX | 150,000 | +110,000 Foraging Experience; +1 Foraging Level Cap |
| **Oak Log** (`LOG`) | I | 50 | Oak Minion Recipes |
|  | II | 100 | Leaflet Hat Recipe; Leaflet Tunic Recipe; Leaflet Pants Recipe; Leaflet Sandals Recipe; Oak Leaves Trade |
|  | III | 250 | Enchanted Oak Log Recipe |
|  | IV | 500 | Small Storage Recipe |
|  | V | 1,000 | Forest Biome Stick Recipe |
|  | VI | 2,000 | Medium Storage Recipe |
|  | VII | 5,000 | Wood Affinity Talisman Recipe |
|  | VIII | 10,000 | +10,000 Foraging Experience |
|  | IX | 25,000 | Large Storage Recipe |
| **Ruby Veilshroom** (`RUBY_VEILSHROOM`) | I | 25 | Travel Scroll to Torrhus Springs Recipe |
|  | II | 100 | Enchanted Ruby Veilshroom Recipe |
|  | III | 250 | Accretion Talisman Recipe |
|  | IV | 500 | June Bug Shard Recipe |
|  | V | 1,000 | Accretion Ring Recipe |
|  | VI | 2,500 | Veilshroom Bunch Recipe |
|  | VII | 5,000 | Accretion Artifact Recipe |
|  | VIII | 10,000 | Large Pocket Black Hole Recipe |
|  | IX | 25,000 | Veilshroom Bracelet Recipe |
| **Sea Lumies** (`SEA_LUMIES`) | I | 25 | Basic Fishing Net Recipe |
|  | II | 50 | Enchanted Sea Lumies Recipe |
|  | III | 100 | Bubbles of Air Recipe |
|  | IV | 250 | Enchanted Book (Scuba I) Recipe |
|  | V | 500 | Medium Fishing Net Recipe; Pressure Talisman Recipe |
|  | VI | 750 | Diver's Mask Recipe; Diver's Shirt Recipe; Diver's Trunks Recipe; Diver's Boots Recipe |
|  | VII | 1,000 | Pressure Ring Recipe |
|  | VIII | 2,500 | Abyssal Helmet Recipe; Abyssal Chestplate Recipe; Abyssal Leggings Recipe; Abyssal Boots Recipe |
|  | IX | 5,000 | Turbo Fishing Net Recipe; Pressure Artifact Recipe |
|  | X | 10,000 | Gigantic Fishing Net Recipe |
| **Spruce Log** (`LOG:1`) | I | 50 | Spruce Minion Recipes |
|  | II | 100 | Spruce Axe Recipe; Spruce Leaves Trade |
|  | III | 250 | Enchanted Spruce Log Recipe; [Lvl 1] Wolf Recipe; Portal to Spruce Woods Recipe |
|  | IV | 500 | +500 Foraging Experience |
|  | V | 1,000 | Taiga Biome Stick Recipe |
|  | VI | 2,000 | +2,000 Foraging Experience |
|  | VII | 5,000 | Woodcutting Crystal Recipe |
|  | VIII | 10,000 | +10,000 Foraging Experience |
|  | IX | 25,000 | +25,000 Foraging Experience |
| **Tender Wood** (`TENDER_WOOD`) | I | 10 | Small Huntrap Recipe |
|  | II | 25 | Small Fish Bowl Recipe |
|  | III | 50 | Medium Huntrap Recipe |
|  | IV | 100 | Enchanted Tender Wood Recipe; Medium Fish Bowl Recipe |
|  | V | 200 | Large Huntrap Recipe |
|  | VI | 400 | Large Fish Bowl Recipe |
|  | VII | 600 | Greater Huntrap Recipe |
|  | VIII | 800 | +50,000 Hunting Experience; +50,000 Foraging Experience |
|  | IX | 1,000 | Astral Huntrap Recipe |
| **Vinesap** (`VINESAP`) | I | 10 | Worn Huntaxe - Genesis Recipe; Abysmal Lasso Recipe |
|  | II | 25 | Vinerip Lasso Recipe |
|  | III | 50 | Sharpened Huntaxe - Dominus Recipe |
|  | IV | 100 | Enchanted Vinesap Recipe; Entangler Lasso Recipe |
|  | V | 200 | Reinforced Huntaxe - Cursus Recipe |
|  | VI | 400 | Everstretch Lasso Recipe |
|  | VII | 600 | Savage Huntaxe - Praedator Recipe |
|  | VIII | 800 | Marshroom Recipe; +50,000 Hunting Experience; +50,000 Foraging Experience |
|  | IX | 1,000 | Prime Huntaxe - Nex Titanum Recipe |

### 3.6 Fishing collections (12 items)

| Collection (API id) | Tier | Amount | Unlocks (each tier ALSO gives +4 SkyBlock XP unless noted) |
|---|---:|---:|---|
| **Clay Ball** (`CLAY_BALL`) | I | 50 | Clay Minion Recipes |
|  | II | 100 | Enchanted Clay Ball Recipe |
|  | III | 250 | Respiration Exp Discount (-25%) |
|  | IV | 1,000 | Frail Exp Discount (-25%) |
|  | V | 1,500 | Clay Bracelet Recipe |
|  | VI | 2,500 | +2,500 Fishing Experience |
|  | VII | 5,000 | Enchanted Clay Block Recipe |
| **Ink Sac** (`INK_SACK`) | I | 20 | Squid Hat Recipe |
|  | II | 40 | Dark Bait Recipe |
|  | III | 100 | Enchanted Ink Sac Recipe |
|  | IV | 200 | Deep Ocean Biome Stick Recipe |
|  | V | 400 | Caster Exp Discount (-25%) |
|  | VI | 800 | Blindness Potion Recipe |
|  | VII | 1,500 | Angler Exp Discount (-25%) |
|  | VIII | 2,500 | Bait Ring Recipe |
|  | IX | 4,000 | Ink Wand Recipe |
| **Lily Pad** (`WATER_LILY`) | I | 10 | Lily Pad Minion Recipes |
|  | II | 50 | Blobfish Hat Recipe |
|  | III | 100 | Healing Talisman Recipe |
|  | IV | 200 | Enchanted Lily Pad Recipe |
|  | V | 500 | Common Hook Recipe |
|  | VI | 1,500 | Whale Bait Recipe |
|  | VII | 3,000 | Rod of Champions Recipe |
|  | VIII | 6,000 | Healing Ring Recipe |
|  | IX | 10,000 | Rod of Legends Recipe |
|  | X | 25,000 | Condensed Lily Pad Recipe |
|  | XI | 50,000 | Frogspawn Bucket Recipe |
| **Lotus** (`LOTUS`) | I | 25 | Froggles Recipe |
|  | II | 100 | Red Sweater Recipe |
|  | III | 250 | Silver Lotus Recipe |
|  | IV | 1,000 | Silver Froggles Recipe |
|  | V | 2,500 | Lotus Sinker Recipe |
|  | VI | 5,000 | Gold Lotus Recipe |
|  | VII | 10,000 | Golden Froggles Recipe |
|  | VIII | 25,000 | Puddle Jumper Hook Recipe |
|  | IX | 50,000 | Trophy Line Recipe |
|  | X | 100,000 | Diamond Lotus Recipe |
|  | XI | 250,000 | Diamond Froggles Recipe |
| **Magmafish** (`MAGMA_FISH`) | I | 20 | Magmafish Hat Recipe |
|  | II | 100 | Hot Bait Recipe |
|  | III | 500 | Silver Magmafish Recipe |
|  | IV | 1,000 | Magma Rod Recipe |
|  | V | 5,000 | Corrupted Bait Recipe |
|  | VI | 15,000 | Small Lava Fishing Sack Recipe |
|  | VII | 30,000 | Gold Magmafish Recipe |
|  | VIII | 50,000 | Inferno Rod Recipe |
|  | IX | 75,000 | Medium Lava Fishing Sack Recipe |
|  | X | 100,000 | Diamond Magmafish Recipe |
|  | XI | 250,000 | Large Lava Fishing Sack Recipe |
|  | XII | 500,000 | Hellfire Rod Recipe |
| **Prismarine Crystals** (`PRISMARINE_CRYSTALS`) | I | 10 | Sea Lantern Hat Recipe |
|  | II | 25 | Light Bait Recipe |
|  | III | 50 | Enchanted Prismarine Crystals Recipe |
|  | IV | 100 | Aqua Affinity Exp Discount (-25%) |
|  | V | 200 | Guardian Chestplate Recipe |
|  | VI | 400 | Blessed Bait Recipe |
|  | VII | 800 | Blessing Exp Discount (-25%) |
| **Prismarine Shard** (`PRISMARINE_SHARD`) | I | 10 | Impaling Exp Discount (-25%) |
|  | II | 25 | Prismarine Blade Recipe |
|  | III | 50 | Enchanted Prismarine Shard Recipe |
|  | IV | 100 | Prismarine Sinker Recipe |
|  | V | 200 | Prismarine Bow Recipe |
|  | VI | 400 | Weather Node Recipe |
|  | VII | 800 | Prismarine Necklace Recipe |
| **Pufferfish** (`RAW_FISH:3`) | I | 20 | Spiked Bait Recipe |
|  | II | 50 | Beginner Fishing Sack Recipe |
|  | III | 100 | Pufferfish Hat Recipe; Spiked Hook Exp Discount (-25%) |
|  | IV | 150 | Enchanted Pufferfish Recipe; Cleave Exp Discount (-25%) |
|  | V | 400 | Small Fishing Sack Recipe |
|  | VI | 800 | Depth Strider Exp Discount (-25%) |
|  | VII | 2,400 | Medium Fishing Sack Recipe |
|  | VIII | 4,800 | Hotspot Bait Recipe |
|  | IX | 9,000 | Large Fishing Sack Recipe |
|  | X | 18,000 | Large Enchanted Fishing Sack Recipe |
| **Raw Cod** (`RAW_FISH`) | I | 20 | Fish Hat Recipe; Minnow Bait Recipe |
|  | II | 50 | Fishing Minion Recipes |
|  | III | 100 | Fishing Bag |
|  | IV | 250 | Pond Island Recipe |
|  | V | 500 | +2,500 Fishing Experience |
|  | VI | 1,000 | Enchanted Raw Cod Recipe |
|  | VII | 2,500 | +9 Fishing Bag Slots |
|  | VIII | 15,000 | Enchanted Cooked Cod Recipe |
|  | IX | 30,000 | +9 Fishing Bag Slots |
|  | X | 45,000 | +9 Fishing Bag Slots |
|  | XI | 60,000 | +9 Fishing Bag Slots |
| **Raw Salmon** (`RAW_FISH:1`) | I | 20 | Salmon Hat Recipe |
|  | II | 50 | Dodge Potion Recipe |
|  | III | 100 | Lure Exp Discount (-25%) |
|  | IV | 250 | Enchanted Raw Salmon Recipe |
|  | V | 500 | Fish Bait Recipe |
|  | VI | 1,000 | Salmon Helmet Recipe; Salmon Chestplate Recipe; Salmon Leggings Recipe; Salmon Boots Recipe |
|  | VII | 2,500 | +5,000 Fishing Experience |
|  | VIII | 5,000 | Enchanted Cooked Salmon Recipe |
|  | IX | 10,000 | +10,000 Fishing Experience |
| **Sponge** (`SPONGE`) | I | 20 | Sponge Trade |
|  | II | 50 | Sponge Sinker Recipe |
|  | III | 100 | Sea Creature Talisman Recipe |
|  | IV | 250 | Enchanted Sponge Recipe |
|  | V | 500 | Sponge Belt Recipe |
|  | VI | 1,000 | Sea Creature Ring Recipe |
|  | VII | 1,500 | Enchanted Wet Sponge Recipe; Stereo Pants Recipe |
|  | VIII | 2,000 | Sea Creature Artifact Recipe |
|  | IX | 4,000 | Sponge Helmet Recipe; Sponge Chestplate Recipe; Sponge Leggings Recipe; Sponge Boots Recipe |
| **Tropical Fish** (`RAW_FISH:2`) | I | 10 | Tropical Fish Hat Recipe |
|  | II | 25 | Water Bucket Trade |
|  | III | 50 | Magnet Exp Discount (-25%) |
|  | IV | 100 | Small Sack of Sacks; Enchanted Tropical Fish Recipe |
|  | V | 200 | +4 Small Sack of Sacks Slots |
|  | VI | 400 | +4 Small Sack of Sacks Slots |
|  | VII | 800 | Tropical Cloak Recipe; +4 Small Sack of Sacks Slots |
|  | VIII | 1,600 | +4 Small Sack of Sacks Slots |
|  | IX | 4,000 | +5 Small Sack of Sacks Slots |

### 3.7 Rift collections (7 items)

| Collection (API id) | Tier | Amount | Unlocks (each tier ALSO gives +4 SkyBlock XP unless noted) |
|---|---:|---:|---|
| **Agaricus Cap** (`AGARICUS_CAP`) | I | 20 | Agaricus Cap Bunch Recipe |
|  | II | 60 | Agaricus Soup Recipe |
|  | III | 100 | Agaricus Cap Cap Recipe |
|  | IV | 200 | Agaricus Chumcap Recipe |
| **Caducous Stem** (`CADUCOUS_STEM`) | I | 20 | Caducous Stem Bunch Recipe |
|  | II | 60 | Caducous Legume Recipe |
|  | III | 150 | Caducous Extract Recipe |
|  | IV | 500 | Caducous Feeder Recipe |
| **Half-Eaten Carrot** (`HALF_EATEN_CARROT`) | I | 50 | Nearly-Whole Carrot Recipe |
|  | II | 400 | Orange Chestplate Recipe |
|  | III | 1,000 | Exportable Carrots Recipe |
|  | IV | 3,500 | Nearly Coherent doR gnihsiF Recipe |
| **Hemovibe** (`HEMOVIBE`) | I | 50 | Vampire Minion Recipes |
|  | II | 250 | Blood Donor Talisman Recipe |
|  | III | 1,000 | Hemoglass Recipe |
|  | IV | 2,500 | Enchanted Book (Transylvanian IV) Recipe |
|  | V | 5,000 | Displaced Leech Recipe |
|  | VI | 10,000 | Blood Donor Ring Recipe |
|  | VII | 25,000 | Full-Jaw Fanging Kit Recipe |
|  | VIII | 50,000 | Presumed Gallon of Red Paint Recipe |
|  | IX | 100,000 | Hemobomb Recipe; Blood Donor Artifact Recipe |
| **Living Metal Heart** (`METAL_HEART`) | I | 1 | Snake-in-a-Boot Recipe |
|  | II | 20 | Living Metal Anchor Recipe |
|  | III | 60 | Bluetooth Ring Recipe |
|  | IV | 100 | Polarvoid Book Recipe |
| **Timite** (`TIMITE`) | I | 25 | Time Gun Recipe; Eon Pickaxe Recipe |
|  | II | 50 | Time Pocket Bag |
|  | III | 100 | Chrono Pickaxe Recipe |
|  | IV | 250 | Highlite Recipe |
|  | V | 500 | Satelite Recipe |
|  | VI | 750 | Discrite Recipe |
| **Wilted Berberis** (`WILTED_BERBERIS`) | I | 20 | Wilted Berberis Bunch Recipe |
|  | II | 60 | Berberis Blowgun Recipe |
|  | III | 140 | Jinxed Voodoo Doll Recipe |
|  | IV | 400 | Berberis Fuel Injector Recipe |


### 3.8 Boss collections (Dungeons + Kuudra)

Not in the API resource; from the wiki `Module:Collection/Data`. Progress = boss kills/completions. On Hypixel,
a normal-mode floor completion adds 1 and a Master Mode completion adds 2 (?); Kuudra adds by tier
(Basic 1 → Infernal 5) (?).

| Boss | Tier: required → reward (+SB XP) |
|---|---|
| Bonzo (F1) | I 25 → Red Nose (+15) · II 50 → Bonzo's Mask (+15) · III 100 → Golden Bonzo Head (+15) · IV 150 → Bonzo's Staff (+25) · V 250 → Recombobulator 3000 (+25) · VI 1,000 → Diamond Bonzo Head (+25) |
| Scarf (F2) | I 25 → Red Scarf · II 50 → Scarf's Thesis · III 100 → Golden Scarf Head · IV 150 → Adaptive Blade · V 250 → Recombobulator 3000 · VI 1,000 → Diamond Scarf Head (SB XP 15/15/15/25/25/25) |
| The Professor (F3) | I 25 → Suspicious Vial · II 50 → Adaptive Leggings · III 100 → Golden Professor Head · IV 150 → Adaptive Chestplate · V 250 → Recombobulator 3000 · VI 1,000 → Diamond Professor Head |
| Thorn (F4) | I 50 → Spirit Stone · II 100 → Golden Thorn Head · III 150 → Spirit Shortbow · IV 250 → Recombobulator 3000 · V 400 → Spirit Boots · VI 1,000 → Diamond Thorn Head |
| Livid (F5) | I 50 → Dark Orb · II 100 → Golden Livid Head · III 150 → Livid Dagger · IV 250 → Recombobulator 3000 · V 500 → Last Breath · VI 750 → Shadow Assassin Chestplate · VII 1,000 → Diamond Livid Head |
| Sadan (F6) | I 50 → Giant Tooth · II 100 → Golden Sadan Head · III 150 → Necromancer Lord Helmet · IV 250 → Recombobulator 3000 · V 500 → Necromancer Lord Chestplate · VI 750 → Necromancer Sword · VII 1,000 → Diamond Sadan Head |
| Necron (F7) | I 50 → Wither Blood · II 100 → Golden Necron Head · III 150 → Wither Helmet · IV 250 → Recombobulator 3000 · V 500 → Wither Leggings · VI 750 → Wither Chestplate · VII 1,000 → Diamond Necron Head |
| Kuudra | I 10 → Common Kuudra Pet + Crimson Essence + Kuudra Teeth (+10) · II 100 → Kuudra Chunk (+15) · III 500 → Meaty Kuudra Chunk (+20) · IV 2,000 → Hardened Kuudra Chunk (+25) · V 5,000 → Enriched Kuudra Chunk (+30) (each tier also Crimson Essence + Kuudra Teeth) |

Boss-collection SB XP is a separate SkyBlock-XP task ("Boss Collections", max 1,015 SB XP).

---

## 4. Minions

### 4.1 What a minion is

* A small NPC (player-head on an armour stand) placed on the **Private Island** only. It works in a
  **5×5 area** centred on itself (2 blocks each side; the minion's own column is excluded), at its own Y level
  for block/crop minions, and a 6-block vertical band (3 below, 2 above, 1 at eye level) for mob spawning.
* Every minion type is tied to one collection item. **Tier I's recipe is unlocked by that collection's tier I**
  (exceptions: Fishing → Raw Cod II, Sunflower → Sunflower II, slayer minions → slayer levels, Snow → Season of Jerry
  gifts, Flower → Dark Auction, Mycelium/Red Sand → Crimson Isle faction reputation).
* **Time Between Actions (TBA)** is the seconds between two actions. Block/crop/tree minions **alternate**:
  action 1 places/plants/spawns, action 2 harvests/kills. So a minion yields **one harvest every 2 × TBA**.
  (Example from the wiki: Cobblestone I, TBA 14 s → 1 cobblestone every 28 s.) Fishing minions and similar
  "no world interaction" minions produce on every action (?).
* Effective TBA with speed boosts (all boosts **add**): `TBA_eff = TBA_base / (1 + Σ speed%)`.
  Multiplier fuels (Tasty Cheese ×2, Catalyst ×3, Hyper Catalyst ×4, Inferno fuels) **do not** change TBA; they
  multiply each drop.
* **Production rate**: `items/hour = 3600 / (2 × TBA_eff) × avg_drops_per_harvest × multiplier` (block/crop/mob).
* **Max speed boost** documented: +175% (Foul Flesh 90% + 2× Flycatcher 40% + Mithril Infusion 10% + Mithril Crystal 10% + Mayor Cole's Prospection 25%, on a mining minion).
* Starting state: every new profile has a **Cobblestone Minion I** placed near Jerry.

### 4.2 Interface (per minion GUI)

| Slot | Count | What goes in |
|---|---:|---|
| Minion Skin | 1 | Cosmetic only |
| Fuel | 1 (one stack of one fuel type) | §4.4 |
| Automated Shipping | 1 | Budget Hopper (sells at 50% NPC price) / Enchanted Hopper (70%) — fires only when storage + attached chests are full |
| Upgrade | 2 | §4.5 |
| Storage | 1–15 slots of 64 (the "Storage (items)" row ÷ 64) | Produced items |
| Buttons | — | Collect All, Pick Up Minion (items go to inventory; non-infinite fuel is destroyed), Next Tier quick-upgrade (Quick Upgrade diamond), Ideal Layout, Recipes |

Minion name above head shows tier and status. Status bubbles: "My storage is full! :(" (stops working),
"This location isn't perfect! :(" (blocked layout), "I need water nearby" (?). Chat/hover messages:
"You can only place a maximum of # minions! / Click here to view your Minion progress..." when over the cap.

### 4.3 Minion slots (placeable minion cap)

"Unique minions" = number of distinct (type, tier) pairs the profile has **ever crafted** (crafting tier V
of Wheat counts as one new unique; tiers obtained by trade/upgrade at an NPC also count).

| Slot tier | Placeable minions | Unique minions crafted |
|---|---:|---:|
| I | 5 | 0 |
| II | 6 | 5 |
| III | 7 | 15 |
| IV | 8 | 30 |
| V | 9 | 50 |
| VI | 10 | 75 |
| VII | 11 | 100 |
| VIII | 12 | 125 |
| IX | 13 | 150 |
| X | 14 | 175 |
| XI | 15 | 200 |
| XII | 16 | 225 |
| XIII | 17 | 250 |
| XIV | 18 | 275 |
| XV | 19 | 300 |
| XVI | 20 | 350 |
| XVII | 21 | 400 |
| XVIII | 22 | 450 |
| XIX | 23 | 500 |
| XX | 24 | 550 |
| XXI | 25 | 600 |
| XXII | 26 | 650 |

* **+5 more** from the Community Shop "Minion Slots" upgrade (5 tiers, +1 each, +10 SB XP each) → **max 31**.
* Total distinct minion items in the current API: **719** (61 types). The wiki data module covers 60 types; the
  61st, **Lily Pad Minion** (tiers I–XII, unlocked at Lily Pad collection), is new and its stats are not yet
  documented (?).
* Each new unique tier crafted also grants SkyBlock XP: tiers I–VI +1 each, VII +2, VIII +3, IX +4, X +6, XI +12, XII +24
  (max 2,963 SB XP across all minions).
* Sitting a minion on a **Minion Chair** (Carpentry 1 furniture) stops it working and it does not count toward the cap.

### 4.4 Fuels

| Fuel | Duration (real time) | Effect | How obtained |
|---|---|---|---|
| Coal | 30 min | +5% speed | Mining coal |
| Block of Coal | 5 h | +5% | 9 coal |
| Enchanted Bread | 12 h | +5% | 60 Wheat (Wheat V) — cheapest per hour |
| Enchanted Coal | 24 h | +10% | 160 Coal (Coal IV) |
| Enchanted Charcoal | 36 h | +20% | 128 Coal + 32 any Log (Coal V) |
| Solar Panel | ∞ (daytime 06:00–19:00 SB time, island loaded only) | +25% | Glass + 6× (64 Enchanted Quartz) + planks (Quartz IX) |
| Enchanted Lava Bucket | ∞ | +25% | 3 Enchanted Iron + 2 Enchanted Coal Block (Coal VIII) |
| Magma Bucket | ∞ | +30% | 2 Enchanted Lava Bucket + Heat Core |
| Plasma Bucket | ∞ | +35% | 2 Magma Bucket + Heat Core |
| Everburning Flame | ∞ | +40% | Plasma Bucket + 2 Enchanted Sulphur Cube + Enchanted Red Sand Cube + 16 Flames |
| Hamster Wheel | 24 h | +50% | Sven Packmaster drop |
| Foul Flesh | 5 h | +90% | Revenant Horror (T2+) drop |
| Tasty Cheese | 1 h | ×2 drops | Rats / Rat pet |
| Catalyst | 3 h | ×3 drops | End Stone + Crystal Fragment (End Stone IX) |
| Hyper Catalyst | 6 h | ×4 drops | 8 Catalyst + Hyper Catalyst Upgrade (makes 8) |
| Inferno Minion Fuel (Rare / Epic / Legendary) | 24 h | ×10 / ×15 / ×20 drops, +10/15/20% speed (?); replaces 4/5 of output with a distillate specialty; Inferno minion only | Crafted from Crude Gabagool + distillate |

Fuel rules: one fuel type per minion, up to one stack; timed fuels tick in **real time including while offline**;
infinite fuels can be removed and reused; timed fuels are destroyed if removed or if the minion is picked up.

### 4.5 Upgrades (2 slots, permanent, removable)

| Upgrade | Applies to | Effect | Unlocked by |
|---|---|---|---|
| Minion Expander | Any | +1 block radius (7×7; two stacked = 9×9) and +5% speed each | Quartz V |
| Flycatcher | Any | +20% speed | Spider Slayer LVL 6 |
| Diamond Spreading | Any | Occasionally adds a Diamond (1 per 10 items produced (?)); does not slow the minion | Diamond VI |
| Potato Spreading | Any, **one per profile** | Occasionally adds a Potato | Shiny Pig rare drop |
| Krampus Helmet | Any, one per profile | Occasionally adds a Red Gift | Red Gift (Santa) |
| Sleepy Hollow | Any, one per profile | Occasionally adds Purple Candy | Vargul the Unearthed |
| Dayswitch / Nightswitch / Thorny Vines | Flower minion | Forces Sunflower / Moonflower / Wild Rose only, +20% speed | Sunflower III / Moonflower II / Wild Rose II |
| Enchanted Egg | Chicken | Each chicken always drops an egg | Raw Chicken V |
| Flint Shovel | Gravel | Always gives flint | Gravel II |
| Enchanted Shears | Sheep | Auto-shears sheep | — |
| Lesser Soulflow Engine | Any | −50% output, generates Soulflow | Enderman Slayer LVL 2 |
| Soulflow Engine | Any | −50% output, generates Soulflow; Voidling +3% speed per minion tier | Enderman Slayer LVL 5 |
| Corrupt Soil | Mob-spawning minions | Spawns Corrupted mobs (extra sulphur/corrupted fragment drops) | Mycelium VI |
| Auto Smelter | Smeltable outputs | Smelts output (ore→ingot, cobble→stone, sand→glass, log→charcoal) | Cobblestone III |
| Compactor | Compactable outputs | Compacts into **block** forms (9 iron → Block of Iron, 4 glowstone dust → Glowstone, 9 lapis → Lapis Block) | Cobblestone V |
| Super Compactor 3000 | Any | Compacts into **enchanted** forms (160 → 1) and chains up a tier when enough (160 Enchanted Glowstone Dust → Enchanted Glowstone); works even if the recipe isn't unlocked | Cobblestone X |
| Dwarven Super Compactor | Ores | Auto Smelter + Super Compactor in one slot | Mithril VI |
| Mithril Infusion (anvil, not a slot) | Any | Permanent +10% speed | Mithril VIII |

### 4.6 External modifiers

| Modifier | Effect | Notes |
|---|---|---|
| Minion Storage chests (placed touching the minion, not above) | Small 3 slots (64 Oak Log recipe, Oak Log IV); Medium 9 (Oak Log VI); Large 15 (Oak Log IX); X-Large 21 and XX-Large 27 (Minion Storage X-pender, 1,500 Bits each) | Minion fills its own storage first, then the chest, then the hopper sells |
| Farm Crystal | +10% speed to crop farming minions within 8 blocks | Pumpkin VIII |
| Woodcutting Crystal | +10% foraging minions, radius 12 | Spruce Log VII |
| Mithril Crystal | +10% mining minions, radius 40 | Mithril IV |
| Winter Crystal | +5% Snow minion, radius 16 | Red Gifts |
| Beacon I–V | +2/+4/+6/+8/+10% all minions in radius 120/125/130/135/140 (?) — needs power (Scorched Power Crystal +1% for 48 h) | Mithril IX / Forge |
| Pets at L100 | +30% to specific minions: Rabbit (farming), Ocelot (foraging + Flower), Magma Cube (Slime, Magma Cube), Mooshroom Cow (Mushroom, Mycelium), Snail (Red Sand), Chicken (Chicken), Pigman (Pig), Spider (Spider, Cave Spider, Tarantula) | Pet must be equipped and player on island (?) |
| Mayor perks | Cole "Prospection" +25% mining minions; Diana / others vary | Event-driven |

### 4.7 Offline behaviour, island loading and XP

* The island is **loaded** while the owner, a co-op member or a guest is on it; it unloads ~1 minute after everyone leaves.
* While unloaded, nothing ticks. When the island loads again, each minion **catches up**: it computes how
  many actions fit in the elapsed real time at its current effective TBA (fuel boost applied only for the time
  the fuel actually had left; Solar Panel contributes nothing offline), and adds the produced items to storage,
  stopping when storage (+ chests) is full, after which an automated-shipping item sells the overflow.
  Offline production assumes a **perfect layout** — layout tricks (e.g. a cobblestone generator next to a
  Cobblestone Minion that skips the place action, mobs falling to their death) only work while loaded.
* **Collection & skill XP are granted when items are collected out of the minion** (Collect All / pick up),
  not when they are produced. Picking up a minion with items gives the items but **no collection** credit.
  Items sold by hoppers give coins only (no collection, no XP) (?).
* Co-op: when one member collects, other members who are offline or off-island receive their share of the
  skill XP the next time they visit the private island.
* Mob minions: kills by minions don't count to Bestiary (since 0.19.2). Mobs from mob minions killed by the
  player give no Combat XP.
* Historical rule (2019): a minion performing the same action twice in a row got a −10% debuff on the next action.
  Not worth copying.

### 4.8 Placement and space requirements

* Placed by right-clicking the minion item on a block on the private island within the buildable area.
* Needs its 5×5 ring (2 blocks each side) to contain the right thing: Mining minions — air or their block at the
  minion's level; Farming crop minions — farmland/dirt (Nether Wart: soul sand, Cactus: sand, Sugar Cane: next to
  water (?), Cocoa: jungle logs); Foraging — dirt/grass at sapling spots (the minion plants saplings; a block 6 above
  or light ≤ 7 forces small "simple" trees); Mob minions — open space for mobs to stand; Fishing — water in range
  (?); Clay — water (?); Snow/Ice/Hard Stone/Mycelium/Red Sand — open area.
* If the area is wrong the minion shows "This location isn't perfect! :(" and only harvests what it can.
* Ideal layout is viewable from the minion GUI ("Ideal Layout" button).

### 4.9 Tier XII sources

| Minions | Where tier XII comes from |
|---|---|
| Cobblestone, Obsidian, Coal, Iron, Gold, Diamond, Lapis, Emerald, Redstone, Hard Stone, Quartz, Glowstone (?) | **Bulvar** (Dwarven Mines entrance): tier XI minion + 1,024 of the tier-XI recipe item (or 16 Concentrated Stone etc.) + 2,000,000 coins |
| Cocoa Beans, Pumpkin, Chicken, Mushroom, Cactus, Pig, Wheat, Cow, Sugar Cane, Melon, Nether Wart, Carrot, Potato, Sheep, Rabbit | **Tony's Shop** (Trapper's Den): tier XI + item + **75 Pelts** |
| Revenant, Tarantula | Revenant / Tarantula Minion XII Upgrade Stone from the **Bartender** |
| Flower | Recipe from the **Museum** |
| Snow, Ice | **Einary** (Jerry's Workshop) |
| Fishing, Clay | Fishing / Clay Minion XII Upgrade Stone from **Lukas the Aquarist** |
| Mycelium, Red Sand | Mage (Hilda) / Barbarian faction shop, with reputation (see per-minion table) |
| Mithril | Bulvar (?) |

### 4.10 Minion summary (all 60 documented types)

TBA = base time between actions in seconds; max storage in items (÷ 64 = slots).

| Minion | Category | Unlocked by | Produces per harvest (avg count; minion skill XP per item) | T-I TBA | Max-tier TBA | Max tier | Max storage |
|---|---|---|---|---:|---:|---:|---:|
| Cactus | Farming | Cactus I | Cactus ×3 (0.2 xp); Cactus Green ×3 (0.2 xp) | 27s | 12s | XII | 960 (15 slots) |
| Carrot | Farming | Carrot I | Carrot ×3 (0.1 xp) | 20s | 8s | XII | 960 (15 slots) |
| Chicken | Farming | Raw Chicken I | Raw Chicken ×1 (0.1 xp); Feather ×1 (0.2 xp); Egg ×1 (0.2 xp) | 26s | 12s | XII | 960 (15 slots) |
| Cocoa Beans | Farming | Cocoa Beans I | Cocoa Beans ×3 (0.2 xp) | 27s | 12s | XII | 960 (15 slots) |
| Cow | Farming | Leather I | Raw Beef ×1 (0.1 xp); Leather ×1 (0.2 xp) | 26s | 10s | XII | 960 (15 slots) |
| Melon | Farming | Melon Slice I | Melon Slice ×5 (0.1 xp) | 24s | 10s | XII | 960 (15 slots) |
| Mushroom | Farming | Mushroom I | Brown Mushroom ×0.5 (0.5 xp); Red Mushroom ×0.5 (0.5 xp) | 30s | 12s | XII | 960 (15 slots) |
| Nether Wart | Farming | Nether Wart I | Nether Wart ×2.5 (0.2 xp) | 50s | 27s | XII | 960 (15 slots) |
| Pig | Farming | Raw Porkchop I | Raw Porkchop ×1 (0.2 xp) | 26s | 10s | XII | 960 (15 slots) |
| Potato | Farming | Potato I | Potato ×3 (0.1 xp) | 20s | 8s | XII | 960 (15 slots) |
| Pumpkin | Farming | Pumpkin I | Pumpkin ×1 (0.3 xp) | 32s | 12s | XII | 960 (15 slots) |
| Rabbit | Farming | Raw Rabbit I | Raw Rabbit ×1 (0.1 xp); Rabbit's Foot ×0.35 (0.2 xp); Rabbit Hide ×0.35 (0.2 xp) | 26s | 10s | XII | 960 (15 slots) |
| Sheep | Farming | Raw Mutton I | Raw Mutton ×1 (0.1 xp); White Wool ×1 (0.1 xp) | 24s | 9s | XII | 960 (15 slots) |
| Sugar Cane | Farming | Sugar Cane I | Sugar Cane ×3 (0.1 xp) | 22s | 9s | XII | 960 (15 slots) |
| Sunflower | Farming | Sunflower II | Sunflower ×3 (0.1 xp); Moonflower ×3 (0.1 xp) | 24s | 14s | XI | 960 (15 slots) |
| Wheat | Farming | Wheat I | Wheat ×1 (0.2 xp); Seeds ×1.5 (0.1 xp) | 15s | 7s | XII | 960 (15 slots) |
| Coal | Mining | Coal I | Coal ×1 (0.3 xp) | 15s | 6s | XII | 960 (15 slots) |
| Cobblestone | Mining | Cobblestone I | Cobblestone ×1 (0.1 xp) | 14s | 6s | XII | 960 (15 slots) |
| Diamond | Mining | Diamond I | Diamond ×1 (0.4 xp) | 29s | 12s | XII | 960 (15 slots) |
| Emerald | Mining | Emerald I | Emerald ×1 (0.4 xp) | 28s | 12s | XII | 960 (15 slots) |
| End Stone | Mining | End Stone I | End Stone ×1 (0.4 xp) | 26s | 13s | XI | 960 (15 slots) |
| Glowstone | Mining | Glowstone Dust I | Glowstone Dust ×3 (0.2 xp) | 25s | 11s | XII | 960 (15 slots) |
| Gold | Mining | Gold Ingot I | Gold Ore ×1 (0.4 xp); Gold Ingot ×1 (0.4 xp) | 22s | 9s | XII | 960 (15 slots) |
| Gravel | Mining | Gravel I | Gravel ×1 (0.2 xp); Flint ×1 (0.2 xp) | 26s | 13s | XI | 960 (15 slots) |
| Hard Stone | Mining | Hard Stone I | Hard Stone ×2 (0 xp) | 14s | 6s | XII | 960 (15 slots) |
| Ice | Mining | Ice I | Ice ×1 (0.5 xp) | 14s | 6s | XII | 960 (15 slots) |
| Iron | Mining | Iron Ingot I | Iron Ore ×1 (0.3 xp); Iron Ingot ×1 (0.3 xp) | 17s | 7s | XII | 960 (15 slots) |
| Lapis | Mining | Lapis Lazuli I | Lapis Lazuli ×6 (0.1 xp) | 29s | 16s | XII | 960 (15 slots) |
| Mithril | Mining | Mithril I | Mithril ×2 (0.4 xp) | 80s | 50s | XII | 960 (15 slots) |
| Mycelium | Mining | Mycelium I | Mycelium ×1 (3.2 xp) | 26s | 11s | XII | 960 (15 slots) |
| Obsidian | Mining | Obsidian I | Obsidian ×1 (0.4 xp) | 45s | 21s | XII | 960 (15 slots) |
| Quartz | Mining | Nether Quartz I | Nether Quartz ×1 (0.3 xp) | 22.5s | 10s | XII | 960 (15 slots) |
| Red Sand | Mining | Red Sand I | Red Sand ×1 (3.2 xp) | 26s | 11s | XII | 960 (15 slots) |
| Redstone | Mining | Redstone Dust I | Redstone Dust ×4.5 (0.2 xp) | 29s | 16s | XII | 960 (15 slots) |
| Sand | Mining | Sand I | Sand ×1 (0.2 xp) | 26s | 13s | XI | 960 (15 slots) |
| Snow | Mining | Season of Jerry Gifts | Snowball ×4 (0.1 xp) | 13s | 5.8s | XII | 960 (15 slots) |
| Blaze | Combat | Blaze Rod I | Blaze Rod ×1 (0.3 xp) | 33s | 15s | XII | 960 (15 slots) |
| Cave Spider | Combat | Spider Eye I | Spider Eye ×1 (0.3 xp); String ×0.5 (0.2 xp) | 26s | 13s | XI | 960 (15 slots) |
| Creeper | Combat | Gunpowder I | Gunpowder ×1 (0.3 xp) | 27s | 14s | XI | 960 (15 slots) |
| Enderman | Combat | Ender Pearl I | Ender Pearl ×1 (0.3 xp) | 32s | 18s | XI | 960 (15 slots) |
| Ghast | Combat | Ghast Tear I | Ghast Tear ×1 (0.5 xp) | 50s | 30s | XII | 960 (15 slots) |
| Magma Cube | Combat | Magma Cream I | Magma Cream ×1.8 (0.2 xp) | 32s | 16s | XII | 960 (15 slots) |
| Skeleton | Combat | Bone I | Bone ×1 (0.2 xp) | 26s | 13s | XI | 960 (15 slots) |
| Slime | Combat | Slimeball I | Slimeball ×1.8 (0.2 xp) | 26s | 12s | XI | 960 (15 slots) |
| Spider | Combat | String I | String ×1 (0.2 xp); Spider Eye ×0.5 (0.3 xp) | 26s | 13s | XI | 960 (15 slots) |
| Vampire | Combat | Hemovibe I | Hemovibe ×1 (0 xp) | 190s | 95s | XI | 960 (15 slots) |
| Zombie | Combat | Rotten Flesh I | Rotten Flesh ×1 (0.3 xp); Poisonous Potato ×0.05 (0 xp); Potato ×0.01 (0.1 xp); Carrot ×0.01 (0.1 xp) | 26s | 13s | XI | 960 (15 slots) |
| Inferno | Slayer | Blaze Slayer III | Crude Gabagool ×1 (0 xp) | 1,013s | 697s | XI | 960 (15 slots) |
| Revenant | Slayer | Zombie Slayer V | Rotten Flesh ×3 (0.3 xp); Diamond ×0.2 (0.4 xp) | 29s | 8s | XII | 960 (15 slots) |
| Tarantula | Slayer | Spider Slayer V | String ×3.16 (0.2 xp); Spider Eye ×1 (0.3 xp); Iron Ingot ×0.2 (0.3 xp) | 29s | 10s | XI | 960 (15 slots) |
| Voidling | Slayer | Enderman Slayer IV | Nether Quartz ×0.4 (0.3 xp); Obsidian ×2.42 (0.4 xp); Enchanted Ender Pearl ×0.000625 (48 xp) | 45s | 24s | XI | 960 (15 slots) |
| Acacia | Foraging | Acacia Log I | Acacia Log ×4 (0.1 xp) | 48s | 27s | XI | 960 (15 slots) |
| Birch | Foraging | Birch Log I | Birch Log ×4 (0.1 xp) | 48s | 27s | XI | 960 (15 slots) |
| Dark Oak | Foraging | Dark Oak Log I | Dark Oak Log ×4 (0.1 xp) | 48s | 27s | XI | 960 (15 slots) |
| Flower | Foraging | Dark Auction | Dandelion ×0.35 (0.1 Foraging xp); Poppy ×0.15 (0.1 Foraging xp); Allium ×0.0417 (0.1 Foraging xp); Azure Bluet ×0.0417 (0.1 Foraging xp); Blue Orchid ×0.0417 (0.1 Foraging xp); Oxeye Daisy ×0.0417 (0.1 Foraging xp); Red Tulip ×0.0417 (0.1 Foraging xp); Orange Tulip ×0.0417 (0.1 Foraging xp); White Tulip ×0.0417 (0.1 Foraging xp); Pink Tulip ×0.0417 (0.1 Foraging xp); Lilac ×0.0417 (0.2 Foraging xp); Peony ×0.0417 (0.2 Foraging xp); Rose Bush ×0.0417 (0.2 Foraging xp) | 30s | 15s | XII | 960 (15 slots) |
| Jungle | Foraging | Jungle Log I | Jungle Log ×4 (0.1 xp) | 48s | 27s | XI | 960 (15 slots) |
| Oak | Foraging | Oak Log I | Oak Log ×4 (0.1 xp) | 48s | 27s | XI | 960 (15 slots) |
| Spruce | Foraging | Spruce Log I | Spruce Log ×4 (0.1 xp) | 48s | 27s | XI | 960 (15 slots) |
| Clay | Fishing | Clay Ball I | Clay Ball ×4 (0.1 xp) | 32s | 14s | XII | 960 (15 slots) |
| Fishing | Fishing | Raw Cod II | Raw Cod ×1 (0.5 xp); Raw Salmon ×0.5 (0.7 xp); Pufferfish ×0.24 (1 xp); Tropical Fish ×0.08 (2 xp); Prismarine Crystals ×0.06 (0.25 xp); Prismarine Shard ×0.06 (0.25 xp); Sponge ×0.06 (0.5 xp) | 75s | 30s | XII | 960 (15 slots) |


### 4.11 Per-minion tier tables (TBA, storage and exact upgrade recipe per tier)

Recipe notation: Tier I is crafted with 8 slots of `N× item` around the centre item shown in bold (usually a
wooden/stone tool or, for slayer minions, the base minion/special item). Every later tier is 8 slots of `N×`
the material around the **previous tier minion** in the centre (`prev. tier`). Where some outer slots are
overridden, the number of material slots is reduced and the overrides are listed (`B3=Zombie Minion IV` means
the bottom-middle slot holds that item). "TRADE" tiers are NPC exchanges, not crafts.

#### Cactus Minion (Farming)

- unlocked at **Cactus I** collection
- Produces per harvest: Cactus ×3 (0.2 xp); Cactus Green ×3 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and harvesting cactus. Requires sand nearby so cacti can be planted. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 27 | 27 | 25 | 25 | 23 | 23 | 21 | 21 | 18 | 18 | 15 | 12 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 16× Cactus around **Wooden Hoe** | 8 slots of 32× Cactus around prev. tier | 8 slots of 64× Cactus around prev. tier | 8 slots of 1× Enchanted Cactus Green around prev. tier | 8 slots of 3× Enchanted Cactus Green around prev. tier | 8 slots of 8× Enchanted Cactus Green around prev. tier | 8 slots of 16× Enchanted Cactus Green around prev. tier | 8 slots of 32× Enchanted Cactus Green around prev. tier | 8 slots of 64× Enchanted Cactus Green around prev. tier | 8 slots of 1× Enchanted Cactus around prev. tier | 8 slots of 2× Enchanted Cactus around prev. tier | TRADE at Tony: 32 Enchanted Cactus + 75 Pelt + tier XI minion |

#### Carrot Minion (Farming)

- unlocked at **Carrot I** collection
- Produces per harvest: Carrot ×3 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and harvesting carrots. Requires dirt or soil nearby so carrots can be planted. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 20 | 20 | 18 | 18 | 16 | 16 | 14 | 14 | 12 | 12 | 10 | 8 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 16× Carrot around **Wooden Hoe** | 8 slots of 32× Carrot around prev. tier | 8 slots of 64× Carrot around prev. tier | 8 slots of 1× Enchanted Carrot around prev. tier | 8 slots of 3× Enchanted Carrot around prev. tier | 8 slots of 8× Enchanted Carrot around prev. tier | 8 slots of 16× Enchanted Carrot around prev. tier | 8 slots of 32× Enchanted Carrot around prev. tier | 8 slots of 64× Enchanted Carrot around prev. tier | 8 slots of 1× Enchanted Golden Carrot around prev. tier | 8 slots of 2× Enchanted Golden Carrot around prev. tier | TRADE at Tony: 32 Enchanted Golden Carrot + 75 Pelt + tier XI minion |

#### Chicken Minion (Farming)

- unlocked at **Raw Chicken I** collection
- Produces per harvest: Raw Chicken ×1 (0.1 xp); Feather ×1 (0.2 xp); Egg ×1 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Chickens! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 18 | 18 | 15 | 12 |
| Storage (items) | 192 | 320 | 320 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Raw Chicken around **Wooden Sword** | 8 slots of 16× Raw Chicken around prev. tier | 8 slots of 32× Raw Chicken around prev. tier | 8 slots of 64× Raw Chicken around prev. tier | 8 slots of 1× Enchanted Raw Chicken around prev. tier | 8 slots of 2× Enchanted Raw Chicken around prev. tier | 8 slots of 4× Enchanted Raw Chicken around prev. tier | 8 slots of 8× Enchanted Raw Chicken around prev. tier | 8 slots of 16× Enchanted Raw Chicken around prev. tier | 8 slots of 32× Enchanted Raw Chicken around prev. tier | 8 slots of 64× Enchanted Raw Chicken around prev. tier | TRADE at Tony: 1,024 Enchanted Raw Chicken + 75 Pelt + tier XI minion |

#### Cocoa Beans Minion (Farming)

- unlocked at **Cocoa Beans I** collection
- Produces per harvest: Cocoa Beans ×3 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and harvesting cocoa beans. Requires jungle logs nearby so cocoa can be placed, or air nearby so jungle logs can be placed. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 27 | 27 | 25 | 25 | 23 | 23 | 21 | 21 | 18 | 18 | 15 | 12 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Cocoa Beans around **Wooden Hoe** | 8 slots of 20× Cocoa Beans around prev. tier | 8 slots of 40× Cocoa Beans around prev. tier | 8 slots of 64× Cocoa Beans around prev. tier | 8 slots of 1× Enchanted Cocoa Beans around prev. tier | 8 slots of 3× Enchanted Cocoa Beans around prev. tier | 8 slots of 8× Enchanted Cocoa Beans around prev. tier | 8 slots of 16× Enchanted Cocoa Beans around prev. tier | 8 slots of 32× Enchanted Cocoa Beans around prev. tier | 8 slots of 64× Enchanted Cocoa Beans around prev. tier | 8 slots of 1× Enchanted Cookie around prev. tier | TRADE at Tony: 16 Enchanted Cookie + 75 Pelt + tier XI minion |

#### Cow Minion (Farming)

- unlocked at **Leather I** collection
- Produces per harvest: Raw Beef ×1 (0.1 xp); Leather ×1 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Cows! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 17 | 17 | 13 | 10 |
| Storage (items) | 128 | 256 | 256 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Raw Beef around **Wooden Sword** | 8 slots of 16× Raw Beef around prev. tier | 8 slots of 32× Raw Beef around prev. tier | 8 slots of 64× Raw Beef around prev. tier | 8 slots of 1× Enchanted Raw Beef around prev. tier | 8 slots of 3× Enchanted Raw Beef around prev. tier | 8 slots of 8× Enchanted Raw Beef around prev. tier | 8 slots of 16× Enchanted Raw Beef around prev. tier | 8 slots of 32× Enchanted Raw Beef around prev. tier | 8 slots of 64× Enchanted Raw Beef around prev. tier | 8 slots of 64× Enchanted Leather around prev. tier | TRADE at Tony: 1,028 Enchanted Leather + 75 Pelt + tier XI minion |

#### Melon Minion (Farming)

- unlocked at **Melon Slice I** collection
- Produces per harvest: Melon Slice ×5 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and harvesting melons! Requires an open area to place melons. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 24 | 24 | 22.5 | 22.5 | 21 | 21 | 18.5 | 18.5 | 16 | 16 | 13 | 10 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 32× Melon Slice around **Wooden Hoe** | 8 slots of 64× Melon Slice around prev. tier | 8 slots of 16× Melon around prev. tier | 8 slots of 32× Melon around prev. tier | 8 slots of 64× Melon around prev. tier | 8 slots of 8× Enchanted Melon Slice around prev. tier | 8 slots of 16× Enchanted Melon Slice around prev. tier | 8 slots of 32× Enchanted Melon Slice around prev. tier | 8 slots of 64× Enchanted Melon Slice around prev. tier | 8 slots of 1× Enchanted Melon around prev. tier | 8 slots of 2× Enchanted Melon around prev. tier | TRADE at Tony: 32 Enchanted Melon + 75 Pelt + tier XI minion |

#### Mushroom Minion (Farming)

- unlocked at **Mushroom I** collection
- Produces per harvest: Brown Mushroom ×0.5 (0.5 xp); Red Mushroom ×0.5 (0.5 xp)
- In-game lore: “Place this minion and it will start generating and harvesting red and brown mushrooms! Requires an area that is suitable for mushrooms to be placed. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 30 | 30 | 28 | 28 | 26 | 26 | 23 | 23 | 20 | 20 | 16 | 12 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Red Mushroom around **Wooden Hoe** | 8 slots of 20× Red Mushroom around prev. tier | 8 slots of 40× Red Mushroom around prev. tier | 8 slots of 64× Red Mushroom around prev. tier | 8 slots of 1× Enchanted Red Mushroom around prev. tier | 8 slots of 2× Enchanted Red Mushroom around prev. tier | 8 slots of 4× Enchanted Red Mushroom around prev. tier | 8 slots of 8× Enchanted Red Mushroom around prev. tier | 8 slots of 16× Enchanted Red Mushroom around prev. tier | 8 slots of 32× Enchanted Red Mushroom around prev. tier | 8 slots of 64× Enchanted Red Mushroom around prev. tier | TRADE at Tony: 512 Enchanted Brown Mushroom + 512 Enchanted Red Mushroom + 75 Pelt + tier XI minion |

#### Nether Wart Minion (Farming)

- unlocked at **Nether Wart I** collection
- Produces per harvest: Nether Wart ×2.5 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and harvesting cocoa beans. Requires soul sand nearby so nether warts can be planted. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 50 | 50 | 47 | 47 | 44 | 44 | 41 | 41 | 38 | 38 | 32 | 27 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Nether Wart around **Wooden Hoe** | 8 slots of 20× Nether Wart around prev. tier | 8 slots of 40× Nether Wart around prev. tier | 8 slots of 64× Nether Wart around prev. tier | 8 slots of 1× Enchanted Nether Wart around prev. tier | 8 slots of 2× Enchanted Nether Wart around prev. tier | 8 slots of 4× Enchanted Nether Wart around prev. tier | 8 slots of 8× Enchanted Nether Wart around prev. tier | 8 slots of 16× Enchanted Nether Wart around prev. tier | 8 slots of 32× Enchanted Nether Wart around prev. tier | 8 slots of 64× Enchanted Nether Wart around prev. tier | TRADE at Tony: 1,024 Enchanted Nether Wart + 75 Pelt + tier XI minion |

#### Pig Minion (Farming)

- unlocked at **Raw Porkchop I** collection
- Produces per harvest: Raw Porkchop ×1 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Pigs! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 17 | 17 | 13 | 10 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Raw Porkchop around **Wooden Sword** | 8 slots of 16× Raw Porkchop around prev. tier | 8 slots of 32× Raw Porkchop around prev. tier | 8 slots of 64× Raw Porkchop around prev. tier | 8 slots of 1× Enchanted Raw Porkchop around prev. tier | 8 slots of 3× Enchanted Raw Porkchop around prev. tier | 8 slots of 8× Enchanted Raw Porkchop around prev. tier | 8 slots of 16× Enchanted Raw Porkchop around prev. tier | 8 slots of 32× Enchanted Raw Porkchop around prev. tier | 8 slots of 64× Enchanted Raw Porkchop around prev. tier | 8 slots of 1× Enchanted Cooked Porkchop around prev. tier | TRADE at Tony: 16 Enchanted Cooked Porkchop + 75 Pelt + tier XI minion |

#### Potato Minion (Farming)

- unlocked at **Potato I** collection
- Produces per harvest: Potato ×3 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and harvesting potatoes. Requires dirt or soil nearby so potatoes can be planted. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 20 | 20 | 18 | 18 | 16 | 16 | 14 | 14 | 12 | 12 | 10 | 8 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 16× Potato around **Wooden Hoe** | 8 slots of 32× Potato around prev. tier | 8 slots of 64× Potato around prev. tier | 8 slots of 1× Enchanted Potato around prev. tier | 8 slots of 3× Enchanted Potato around prev. tier | 8 slots of 8× Enchanted Potato around prev. tier | 8 slots of 16× Enchanted Potato around prev. tier | 8 slots of 32× Enchanted Potato around prev. tier | 8 slots of 64× Enchanted Potato around prev. tier | 8 slots of 1× Enchanted Baked Potato around prev. tier | 8 slots of 2× Enchanted Baked Potato around prev. tier | TRADE at Tony: 32 Enchanted Baked Potato + 75 Pelt + tier XI minion |

#### Pumpkin Minion (Farming)

- unlocked at **Pumpkin I** collection
- Produces per harvest: Pumpkin ×1 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and harvesting pumpkins! Requires an open area to place pumpkins. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 32 | 32 | 30 | 30 | 27 | 27 | 24 | 24 | 20 | 20 | 16 | 12 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Pumpkin around **Wooden Hoe** | 8 slots of 20× Pumpkin around prev. tier | 8 slots of 40× Pumpkin around prev. tier | 8 slots of 64× Pumpkin around prev. tier | 8 slots of 1× Enchanted Pumpkin around prev. tier | 8 slots of 2× Enchanted Pumpkin around prev. tier | 8 slots of 4× Enchanted Pumpkin around prev. tier | 8 slots of 8× Enchanted Pumpkin around prev. tier | 8 slots of 16× Enchanted Pumpkin around prev. tier | 8 slots of 32× Enchanted Pumpkin around prev. tier | 8 slots of 64× Enchanted Pumpkin around prev. tier | TRADE at Tony: 1,024 Enchanted Pumpkin + 75 Pelt + tier XI minion |

#### Rabbit Minion (Farming)

- unlocked at **Raw Rabbit I** collection
- Produces per harvest: Raw Rabbit ×1 (0.1 xp); Rabbit's Foot ×0.35 (0.2 xp); Rabbit Hide ×0.35 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Rabbits! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 17 | 17 | 13 | 10 |
| Storage (items) | 192 | 320 | 320 | 448 | 448 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Raw Rabbit around **Wooden Sword** | 8 slots of 16× Raw Rabbit around prev. tier | 8 slots of 32× Raw Rabbit around prev. tier | 8 slots of 64× Raw Rabbit around prev. tier | 8 slots of 1× Enchanted Raw Rabbit around prev. tier | 8 slots of 3× Enchanted Raw Rabbit around prev. tier | 8 slots of 8× Enchanted Raw Rabbit around prev. tier | 8 slots of 16× Enchanted Raw Rabbit around prev. tier | 8 slots of 32× Enchanted Raw Rabbit around prev. tier | 8 slots of 64× Enchanted Raw Rabbit around prev. tier | 8 slots of 1× Enchanted Cooked Rabbit around prev. tier | TRADE at Tony: 16 Enchanted Cooked Rabbit + 75 Pelt + tier XI minion |

#### Sheep Minion (Farming)

- unlocked at **Raw Mutton I** collection
- Produces per harvest: Raw Mutton ×1 (0.1 xp); White Wool ×1 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and slaying Sheep! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 24 | 24 | 22 | 22 | 20 | 20 | 18 | 18 | 16 | 16 | 12 | 9 |
| Storage (items) | 128 | 256 | 256 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Raw Mutton around **Wooden Sword** | 8 slots of 16× Raw Mutton around prev. tier | 8 slots of 32× Raw Mutton around prev. tier | 8 slots of 64× Raw Mutton around prev. tier | 8 slots of 1× Enchanted Raw Mutton around prev. tier | 8 slots of 3× Enchanted Raw Mutton around prev. tier | 8 slots of 8× Enchanted Raw Mutton around prev. tier | 8 slots of 16× Enchanted Raw Mutton around prev. tier | 8 slots of 32× Enchanted Raw Mutton around prev. tier | 8 slots of 64× Enchanted Raw Mutton around prev. tier | 8 slots of 1× Enchanted Cooked Mutton around prev. tier | TRADE at Tony: 16 Enchanted Cooked Mutton + 75 Pelt + tier XI minion |

#### Sugar Cane Minion (Farming)

- unlocked at **Sugar Cane I** collection
- Produces per harvest: Sugar Cane ×3 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and harvesting sugar canes! Requires an area that is suitable for sugar cane to be placed. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 22 | 22 | 20 | 20 | 18 | 18 | 16 | 16 | 14.5 | 14.5 | 12 | 9 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 16× Sugar Cane around **Wooden Hoe** | 8 slots of 32× Sugar Cane around prev. tier | 8 slots of 64× Sugar Cane around prev. tier | 8 slots of 1× Enchanted Sugar around prev. tier | 8 slots of 3× Enchanted Sugar around prev. tier | 8 slots of 8× Enchanted Sugar around prev. tier | 8 slots of 16× Enchanted Sugar around prev. tier | 8 slots of 32× Enchanted Sugar around prev. tier | 8 slots of 64× Enchanted Sugar around prev. tier | 8 slots of 1× Enchanted Sugar Cane around prev. tier | 8 slots of 2× Enchanted Sugar Cane around prev. tier | TRADE at Tony: 32 Enchanted Sugar Cane + 75 Pelt + tier XI minion |

#### Sunflower Minion (Farming)

- unlocked at **Sunflower II** collection
- Produces per harvest: Sunflower ×3 (0.1 xp); Moonflower ×3 (0.1 xp)
- In-game lore: “Place this minion and it will start generating sunflowers during the day, and moonflowers at night. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 24 | 23 | 22 | 21 | 20 | 19 | 18 | 17 | 16 | 15 | 14 |
| Storage (items) | 128 | 192 | 320 | 384 | 448 | 512 | 576 | 704 | 768 | 896 | 960 |
| Upgrade recipe | 8 slots of 16× Sunflower around **Wooden Hoe** | 8 slots of 32× Sunflower around prev. tier | 8 slots of 64× Sunflower around prev. tier | 8 slots of 1× Enchanted Sunflower around prev. tier | 8 slots of 3× Enchanted Sunflower around prev. tier | 8 slots of 8× Enchanted Sunflower around prev. tier | 8 slots of 16× Enchanted Sunflower around prev. tier | 8 slots of 32× Enchanted Sunflower around prev. tier | 8 slots of 64× Enchanted Sunflower around prev. tier | 8 slots of 1× Compacted Sunflower around prev. tier | 8 slots of 2× Compacted Sunflower around prev. tier |

#### Wheat Minion (Farming)

- unlocked at **Wheat I** collection
- Produces per harvest: Wheat ×1 (0.2 xp); Seeds ×1.5 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and harvesting wheat. Requires dirt or soil nearby so wheat can be planted. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 15 | 15 | 13 | 13 | 11 | 11 | 10 | 10 | 9 | 9 | 8 | 7 |
| Storage (items) | 128 | 256 | 256 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Wheat around **Wooden Hoe** | 8 slots of 20× Wheat around prev. tier | 8 slots of 40× Wheat around prev. tier | 8 slots of 64× Wheat around prev. tier | 8 slots of 1× Enchanted Wheat around prev. tier | 8 slots of 2× Enchanted Wheat around prev. tier | 8 slots of 4× Enchanted Wheat around prev. tier | 8 slots of 8× Enchanted Wheat around prev. tier | 8 slots of 16× Enchanted Wheat around prev. tier | 8 slots of 32× Enchanted Wheat around prev. tier | 8 slots of 64× Enchanted Wheat around prev. tier | TRADE at Tony: 1,024 Enchanted Wheat + 75 Pelt + tier XI minion |

#### Coal Minion (Mining)

- unlocked at **Coal I** collection
- Produces per harvest: Coal ×1 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and mining coal ore! Requires an open area to place coal ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 15 | 15 | 13 | 13 | 12 | 12 | 10 | 10 | 9 | 9 | 7 | 6 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Coal around **Wooden Pickaxe** | 8 slots of 20× Coal around prev. tier | 8 slots of 40× Coal around prev. tier | 8 slots of 64× Coal around prev. tier | 8 slots of 1× Enchanted Coal around prev. tier | 8 slots of 3× Enchanted Coal around prev. tier | 8 slots of 8× Enchanted Coal around prev. tier | 8 slots of 16× Enchanted Coal around prev. tier | 8 slots of 32× Enchanted Coal around prev. tier | 8 slots of 64× Enchanted Coal around prev. tier | 8 slots of 1× Enchanted Coal Block around prev. tier | TRADE at Bulvar: 16 Enchanted Coal Block + 2,000,000 Coin + tier XI minion |

#### Cobblestone Minion (Mining)

- unlocked at **Cobblestone I** collection
- Produces per harvest: Cobblestone ×1 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and mining cobblestone! Requires an open area to place cobblestone. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 14 | 14 | 12 | 12 | 10 | 10 | 9 | 9 | 8 | 8 | 7 | 6 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Cobblestone around **Wooden Pickaxe** | 8 slots of 20× Cobblestone around prev. tier | 8 slots of 40× Cobblestone around prev. tier | 8 slots of 64× Cobblestone around prev. tier | 8 slots of 1× Enchanted Cobblestone around prev. tier | 8 slots of 2× Enchanted Cobblestone around prev. tier | 8 slots of 4× Enchanted Cobblestone around prev. tier | 8 slots of 8× Enchanted Cobblestone around prev. tier | 8 slots of 16× Enchanted Cobblestone around prev. tier | 8 slots of 32× Enchanted Cobblestone around prev. tier | 8 slots of 64× Enchanted Cobblestone around prev. tier | TRADE at Bulvar: 1,024 Enchanted Cobblestone + 2,000,000 Coin + tier XI minion |

#### Diamond Minion (Mining)

- unlocked at **Diamond I** collection
- Produces per harvest: Diamond ×1 (0.4 xp)
- In-game lore: “Place this minion and it will start generating and mining diamond ore! Requires an open area to place diamond ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 29 | 29 | 27 | 27 | 25 | 25 | 22 | 22 | 19 | 19 | 15 | 12 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Diamond around **Wooden Pickaxe** | 8 slots of 20× Diamond around prev. tier | 8 slots of 40× Diamond around prev. tier | 8 slots of 64× Diamond around prev. tier | 8 slots of 1× Enchanted Diamond around prev. tier | 8 slots of 3× Enchanted Diamond around prev. tier | 8 slots of 8× Enchanted Diamond around prev. tier | 8 slots of 16× Enchanted Diamond around prev. tier | 8 slots of 32× Enchanted Diamond around prev. tier | 8 slots of 64× Enchanted Diamond around prev. tier | 8 slots of 1× Enchanted Diamond Block around prev. tier | TRADE at Bulvar: 16 Enchanted Diamond Block + 2,000,000 Coin + tier XI minion |

#### Emerald Minion (Mining)

- unlocked at **Emerald I** collection
- Produces per harvest: Emerald ×1 (0.4 xp)
- In-game lore: “Place this minion and it will start generating and mining emerald ore! Requires an open area to place emerald ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 28 | 28 | 26 | 26 | 24 | 24 | 21 | 21 | 18 | 18 | 14 | 12 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Emerald around **Wooden Pickaxe** | 8 slots of 20× Emerald around prev. tier | 8 slots of 40× Emerald around prev. tier | 8 slots of 64× Emerald around prev. tier | 8 slots of 1× Enchanted Emerald around prev. tier | 8 slots of 3× Enchanted Emerald around prev. tier | 8 slots of 8× Enchanted Emerald around prev. tier | 8 slots of 16× Enchanted Emerald around prev. tier | 8 slots of 32× Enchanted Emerald around prev. tier | 8 slots of 64× Enchanted Emerald around prev. tier | 8 slots of 1× Enchanted Emerald Block around prev. tier | TRADE at Bulvar: 16 Enchanted Emerald Block + 2,000,000 Coin + tier XI minion |

#### End Stone Minion (Mining)

- unlocked at **End Stone I** collection
- Produces per harvest: End Stone ×1 (0.4 xp)
- In-game lore: “Place this minion and it will start generating and mining end stone! Requires an open area to place end stone. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 19 | 19 | 16 | 16 | 13 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× End Stone around **Wooden Pickaxe** | 8 slots of 20× End Stone around prev. tier | 8 slots of 40× End Stone around prev. tier | 8 slots of 64× End Stone around prev. tier | 8 slots of 1× Enchanted End Stone around prev. tier | 8 slots of 2× Enchanted End Stone around prev. tier | 8 slots of 4× Enchanted End Stone around prev. tier | 8 slots of 8× Enchanted End Stone around prev. tier | 8 slots of 16× Enchanted End Stone around prev. tier | 8 slots of 32× Enchanted End Stone around prev. tier | 8 slots of 64× Enchanted End Stone around prev. tier |

#### Glowstone Minion (Mining)

- unlocked at **Glowstone Dust I** collection
- Produces per harvest: Glowstone Dust ×3 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and mining glowstone! Requires an open area to place glowstone. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 25 | 25 | 23 | 23 | 21 | 21 | 19 | 19 | 16 | 16 | 13 | 11 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 16× Glowstone Dust around **Wooden Pickaxe** | 8 slots of 32× Glowstone Dust around prev. tier | 8 slots of 64× Glowstone Dust around prev. tier | 8 slots of 1× Enchanted Glowstone Dust around prev. tier | 8 slots of 3× Enchanted Glowstone Dust around prev. tier | 8 slots of 8× Enchanted Glowstone Dust around prev. tier | 8 slots of 16× Enchanted Glowstone Dust around prev. tier | 8 slots of 32× Enchanted Glowstone Dust around prev. tier | 8 slots of 64× Enchanted Glowstone Dust around prev. tier | 8 slots of 1× Enchanted Glowstone around prev. tier | 8 slots of 2× Enchanted Glowstone around prev. tier | TRADE at Hilda/Marthos: 32 Enchanted Glowstone + 2,000,000 Coin + tier XI minion |

#### Gold Minion (Mining)

- unlocked at **Gold Ingot I** collection
- Produces per harvest: Gold Ore ×1 (0.4 xp); Gold Ingot ×1 (0.4 xp)
- In-game lore: “Place this minion and it will start generating and mining gold ore! Requires an open area to place gold ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 22 | 22 | 20 | 20 | 18 | 18 | 16 | 16 | 14 | 14 | 11 | 9 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Gold Ingot around **Wooden Pickaxe** | 8 slots of 20× Gold Ingot around prev. tier | 8 slots of 40× Gold Ingot around prev. tier | 8 slots of 64× Gold Ingot around prev. tier | 8 slots of 1× Enchanted Gold Ingot around prev. tier | 8 slots of 3× Enchanted Gold Ingot around prev. tier | 8 slots of 8× Enchanted Gold Ingot around prev. tier | 8 slots of 16× Enchanted Gold Ingot around prev. tier | 8 slots of 32× Enchanted Gold Ingot around prev. tier | 8 slots of 64× Enchanted Gold Ingot around prev. tier | 8 slots of 1× Enchanted Gold Block around prev. tier | TRADE at Bulvar: 16 Enchanted Gold Block + 2,000,000 Coin + tier XI minion |

#### Gravel Minion (Mining)

- unlocked at **Gravel I** collection
- Produces per harvest: Gravel ×1 (0.2 xp); Flint ×1 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and mining gravel! Requires an open area to place gravel. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 19 | 19 | 16 | 16 | 13 |
| Storage (items) | 128 | 256 | 256 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Gravel around **Wooden Shovel** | 8 slots of 20× Gravel around prev. tier | 8 slots of 40× Gravel around prev. tier | 8 slots of 64× Gravel around prev. tier | 8 slots of 1× Enchanted Flint around prev. tier | 8 slots of 2× Enchanted Flint around prev. tier | 8 slots of 4× Enchanted Flint around prev. tier | 8 slots of 8× Enchanted Flint around prev. tier | 8 slots of 16× Enchanted Flint around prev. tier | 8 slots of 32× Enchanted Flint around prev. tier | 8 slots of 64× Enchanted Flint around prev. tier |

#### Hard Stone Minion (Mining)

- unlocked at **Hard Stone I** collection
- Produces per harvest: Hard Stone ×2 (0 xp)
- In-game lore: “Place this minion and it will start generating and mining Hard Stone! Requires an open area to place Hard Stone. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 14 | 14 | 12 | 12 | 10 | 10 | 9 | 9 | 8 | 8 | 7 | 6 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 32× Hard Stone around **Wooden Pickaxe** | 8 slots of 64× Hard Stone around prev. tier | 8 slots of 1× Enchanted Hard Stone around prev. tier | 8 slots of 2× Enchanted Hard Stone around prev. tier | 8 slots of 4× Enchanted Hard Stone around prev. tier | 8 slots of 8× Enchanted Hard Stone around prev. tier | 8 slots of 16× Enchanted Hard Stone around prev. tier | 8 slots of 32× Enchanted Hard Stone around prev. tier | 8 slots of 64× Enchanted Hard Stone around prev. tier | 4 slots of 1× Concentrated Stone around prev. tier (slots A1,A3,C1,C3 left empty) | 8 slots of 1× Concentrated Stone around prev. tier | TRADE at Bulvar: 16 Concentrated Stone + 2,000,000 Coin + tier XI minion |

#### Ice Minion (Mining)

- unlocked at **Ice I** collection
- Produces per harvest: Ice ×1 (0.5 xp)
- In-game lore: “Place this minion and it will start generating and mining ice! Requires an open area to place ice. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 14 | 14 | 12 | 12 | 10 | 10 | 9 | 9 | 8 | 8 | 7 | 6 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Ice around **Wooden Pickaxe** | 8 slots of 20× Ice around prev. tier | 8 slots of 40× Ice around prev. tier | 8 slots of 64× Ice around prev. tier | 8 slots of 16× Packed Ice around prev. tier | 8 slots of 32× Packed Ice around prev. tier | 8 slots of 64× Packed Ice around prev. tier | 8 slots of 8× Enchanted Ice around prev. tier | 8 slots of 16× Enchanted Ice around prev. tier | 8 slots of 32× Enchanted Ice around prev. tier | 8 slots of 64× Enchanted Ice around prev. tier | TRADE at Einary: 1,024 Enchanted Ice + 1,000,000 Coin + 300 North Star + tier XI minion |

#### Iron Minion (Mining)

- unlocked at **Iron Ingot I** collection
- Produces per harvest: Iron Ore ×1 (0.3 xp); Iron Ingot ×1 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and mining iron ore! Requires an open area to place iron ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 17 | 17 | 15 | 15 | 14 | 14 | 12 | 12 | 10 | 10 | 8 | 7 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Iron Ingot around **Wooden Pickaxe** | 8 slots of 20× Iron Ingot around prev. tier | 8 slots of 40× Iron Ingot around prev. tier | 8 slots of 64× Iron Ingot around prev. tier | 8 slots of 1× Enchanted Iron Ingot around prev. tier | 8 slots of 3× Enchanted Iron Ingot around prev. tier | 8 slots of 8× Enchanted Iron Ingot around prev. tier | 8 slots of 16× Enchanted Iron Ingot around prev. tier | 8 slots of 32× Enchanted Iron Ingot around prev. tier | 8 slots of 64× Enchanted Iron Ingot around prev. tier | 8 slots of 1× Enchanted Iron Block around prev. tier | TRADE at Bulvar: 16 Enchanted Iron Block + 2,000,000 Coin + tier XI minion |

#### Lapis Minion (Mining)

- unlocked at **Lapis Lazuli I** collection
- Produces per harvest: Lapis Lazuli ×6 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and mining lapis ore! Requires an open area to place lapis ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 29 | 29 | 27 | 27 | 25 | 25 | 23 | 23 | 21 | 21 | 18 | 16 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 32× Lapis Lazuli around **Wooden Pickaxe** | 8 slots of 64× Lapis Lazuli around prev. tier | 8 slots of 1× Enchanted Lapis Lazuli around prev. tier | 8 slots of 3× Enchanted Lapis Lazuli around prev. tier | 8 slots of 8× Enchanted Lapis Lazuli around prev. tier | 8 slots of 16× Enchanted Lapis Lazuli around prev. tier | 8 slots of 32× Enchanted Lapis Lazuli around prev. tier | 8 slots of 64× Enchanted Lapis Lazuli around prev. tier | 8 slots of 1× Enchanted Lapis Lazuli Block around prev. tier | 8 slots of 2× Enchanted Lapis Lazuli Block around prev. tier | 8 slots of 4× Enchanted Lapis Lazuli Block around prev. tier | TRADE at Bulvar: 64 Enchanted Lapis Lazuli Block + 2,000,000 Coin + tier XI minion |

#### Mithril Minion (Mining)

- unlocked at **Mithril I** collection
- Produces per harvest: Mithril ×2 (0.4 xp)
- In-game lore: “Place this minion and it will start generating and mining Mithril! Requires an open area to place Mithril. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 80 | 80 | 75 | 75 | 70 | 70 | 65 | 65 | 60 | 60 | 55 | 50 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Mithril around **Wooden Pickaxe** | 8 slots of 20× Mithril around prev. tier | 8 slots of 40× Mithril around prev. tier | 8 slots of 64× Mithril around prev. tier | 8 slots of 1× Enchanted Mithril around prev. tier | 8 slots of 3× Enchanted Mithril around prev. tier | 8 slots of 8× Enchanted Mithril around prev. tier | 8 slots of 16× Enchanted Mithril around prev. tier | 8 slots of 32× Enchanted Mithril around prev. tier | 8 slots of 64× Enchanted Mithril around prev. tier | 8 slots of 1× Refined Mithril around prev. tier | TRADE at Bulvar: 16 Refined Mithril + 2,000,000 Coin + tier XI minion |

#### Mycelium Minion (Mining)

- unlocked at **Mycelium I** collection
- Produces per harvest: Mycelium ×1 (3.2 xp)
- In-game lore: “Place this minion and it will start generating and mining mycelium! Requires an open area to place mycelium . Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 25 | 24 | 23 | 22 | 21 | 20 | 19 | 18 | 16 | 13 | 11 |
| Storage (items) | 64 | 128 | 192 | 320 | 384 | 512 | 576 | 704 | 768 | 896 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Mycelium around **Wooden Shovel** | 8 slots of 32× Mycelium around prev. tier | 8 slots of 64× Mycelium around prev. tier | 8 slots of 2× Enchanted Mycelium around prev. tier | 8 slots of 4× Enchanted Mycelium around prev. tier | 8 slots of 8× Enchanted Mycelium around prev. tier | 8 slots of 16× Enchanted Mycelium around prev. tier | 8 slots of 32× Enchanted Mycelium around prev. tier | 8 slots of 64× Enchanted Mycelium around prev. tier | 8 slots of 1× Enchanted Mycelium Cube around prev. tier | 8 slots of 2× Enchanted Mycelium Cube around prev. tier | TRADE at Hilda: 32 Enchanted Mycelium Cube + 2,000,000 Coin + tier XI minion |
| Requirement | Mage rep 500 | Mage rep 1000 | Mage rep 1500 | Mage rep 2000 | Mage rep 3000 | Mage rep 4000 | Mage rep 5500 | Mage rep 6500 | Mage rep 7500 | Mage rep 9000 | Mage rep 10000 | Mage rep 12000 |

#### Obsidian Minion (Mining)

- unlocked at **Obsidian I** collection
- Produces per harvest: Obsidian ×1 (0.4 xp)
- In-game lore: “Place this minion and it will start generating and mining obsidian! Requires an open area to place obsidian. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 45 | 45 | 42 | 42 | 39 | 39 | 35 | 35 | 30 | 30 | 24 | 21 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Obsidian around **Wooden Pickaxe** | 8 slots of 20× Obsidian around prev. tier | 8 slots of 40× Obsidian around prev. tier | 8 slots of 64× Obsidian around prev. tier | 8 slots of 1× Enchanted Obsidian around prev. tier | 8 slots of 2× Enchanted Obsidian around prev. tier | 8 slots of 4× Enchanted Obsidian around prev. tier | 8 slots of 8× Enchanted Obsidian around prev. tier | 8 slots of 16× Enchanted Obsidian around prev. tier | 8 slots of 32× Enchanted Obsidian around prev. tier | 8 slots of 64× Enchanted Obsidian around prev. tier | TRADE at Bulvar: 1,024 Enchanted Obsidian + 2,000,000 Coin + tier XI minion |

#### Quartz Minion (Mining)

- unlocked at **Nether Quartz I** collection
- Produces per harvest: Nether Quartz ×1 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and mining quartz ore! Requires an open area to place quartz ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 22.5 | 22.5 | 21 | 21 | 19 | 19 | 17 | 17 | 14.5 | 14.5 | 11.5 | 10 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Nether Quartz around **Wooden Pickaxe** | 8 slots of 20× Nether Quartz around prev. tier | 8 slots of 40× Nether Quartz around prev. tier | 8 slots of 64× Nether Quartz around prev. tier | 8 slots of 1× Enchanted Nether Quartz around prev. tier | 8 slots of 3× Enchanted Nether Quartz around prev. tier | 8 slots of 8× Enchanted Nether Quartz around prev. tier | 8 slots of 16× Enchanted Nether Quartz around prev. tier | 8 slots of 32× Enchanted Nether Quartz around prev. tier | 8 slots of 64× Enchanted Nether Quartz around prev. tier | 8 slots of 1× Enchanted Quartz Block around prev. tier | TRADE at Hilda/Marthos: 16 Enchanted Quartz Block + 2,000,000 Coin + tier XI minion |

#### Red Sand Minion (Mining)

- unlocked at **Red Sand I** collection
- Produces per harvest: Red Sand ×1 (3.2 xp)
- In-game lore: “Place this minion and it will start generating and mining red sand! Requires an open area to place red sand. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 25 | 24 | 23 | 22 | 21 | 20 | 19 | 18 | 16 | 13 | 11 |
| Storage (items) | 64 | 128 | 192 | 320 | 384 | 512 | 576 | 704 | 768 | 896 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Red Sand around **Wooden Shovel** | 8 slots of 32× Red Sand around prev. tier | 8 slots of 64× Red Sand around prev. tier | 8 slots of 2× Enchanted Red Sand around prev. tier | 8 slots of 4× Enchanted Red Sand around prev. tier | 8 slots of 8× Enchanted Red Sand around prev. tier | 8 slots of 16× Enchanted Red Sand around prev. tier | 8 slots of 32× Enchanted Red Sand around prev. tier | 8 slots of 64× Enchanted Red Sand around prev. tier | 8 slots of 1× Enchanted Red Sand Cube around prev. tier | 8 slots of 2× Enchanted Red Sand Cube around prev. tier | TRADE at Marthos: 32 Enchanted Red Sand Cube + 2,000,000 Coin + tier XI minion |
| Requirement | Barbarian rep 500 | Barbarian rep 1000 | Barbarian rep 1500 | Barbarian rep 2000 | Barbarian rep 3000 | Barbarian rep 4000 | Barbarian rep 5500 | Barbarian rep 6500 | Barbarian rep 7500 | Barbarian rep 9000 | Barbarian rep 10000 | Barbarian rep 12000 |

#### Redstone Minion (Mining)

- unlocked at **Redstone Dust I** collection
- Produces per harvest: Redstone Dust ×4.5 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and mining redstone ore! Requires an open area to place redstone ore. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 29 | 29 | 27 | 27 | 25 | 25 | 23 | 23 | 21 | 21 | 18 | 16 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 16× Redstone Dust around **Wooden Pickaxe** | 8 slots of 32× Redstone Dust around prev. tier | 8 slots of 64× Redstone Dust around prev. tier | 8 slots of 1× Enchanted Redstone Dust around prev. tier | 8 slots of 3× Enchanted Redstone Dust around prev. tier | 8 slots of 8× Enchanted Redstone Dust around prev. tier | 8 slots of 16× Enchanted Redstone Dust around prev. tier | 8 slots of 32× Enchanted Redstone Dust around prev. tier | 8 slots of 64× Enchanted Redstone Dust around prev. tier | 8 slots of 1× Enchanted Redstone Block around prev. tier | 8 slots of 2× Enchanted Redstone Block around prev. tier | TRADE at Bulvar: 32 Enchanted Redstone Block + 2,000,000 Coin + tier XI minion |

#### Sand Minion (Mining)

- unlocked at **Sand I** collection
- Produces per harvest: Sand ×1 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and mining sand! Requires an open area to place sand. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 19 | 19 | 16 | 16 | 13 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Sand around **Wooden Shovel** | 8 slots of 20× Sand around prev. tier | 8 slots of 40× Sand around prev. tier | 8 slots of 64× Sand around prev. tier | 8 slots of 1× Enchanted Sand around prev. tier | 8 slots of 2× Enchanted Sand around prev. tier | 8 slots of 4× Enchanted Sand around prev. tier | 8 slots of 8× Enchanted Sand around prev. tier | 8 slots of 16× Enchanted Sand around prev. tier | 8 slots of 32× Enchanted Sand around prev. tier | 8 slots of 64× Enchanted Sand around prev. tier |

#### Snow Minion (Mining)

- obtained via Season of Jerry Gifts
- Produces per harvest: Snowball ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and shovelling snow! Requires an open area to place snow. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 13 | 13 | 12 | 12 | 11 | 11 | 9.5 | 9.5 | 8 | 8 | 6.5 | 5.8 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 0× Snow Block —  White Gift  - Obtained from  Season of Jerry | 8 slots of 4× Snow Block around prev. tier | 8 slots of 8× Snow Block around prev. tier | 8 slots of 16× Snow Block around prev. tier | 8 slots of 32× Snow Block around prev. tier | 8 slots of 64× Snow Block around prev. tier | 8 slots of 1× Enchanted Snow Block around prev. tier | 8 slots of 2× Enchanted Snow Block around prev. tier | 8 slots of 4× Enchanted Snow Block around prev. tier | 8 slots of 8× Enchanted Snow Block around prev. tier | 8 slots of 16× Enchanted Snow Block around prev. tier | TRADE at Einary: 1,024 Enchanted Snow Block + 2,000,000 Coin + 500 North Star + tier XI minion |

#### Blaze Minion (Combat)

- unlocked at **Blaze Rod I** collection
- Produces per harvest: Blaze Rod ×1 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and slaying Blaze! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 33 | 33 | 31 | 31 | 28.5 | 28.5 | 25 | 25 | 21 | 21 | 16.5 | 15 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Blaze Rod around **Wooden Sword** | 8 slots of 20× Blaze Rod around prev. tier | 8 slots of 40× Blaze Rod around prev. tier | 8 slots of 64× Blaze Rod around prev. tier | 8 slots of 1× Enchanted Blaze Powder around prev. tier | 8 slots of 3× Enchanted Blaze Powder around prev. tier | 8 slots of 8× Enchanted Blaze Powder around prev. tier | 8 slots of 16× Enchanted Blaze Powder around prev. tier | 8 slots of 32× Enchanted Blaze Powder around prev. tier | 8 slots of 64× Enchanted Blaze Powder around prev. tier | 8 slots of 1× Enchanted Blaze Rod around prev. tier | TRADE at Hilda/Marthos: 16 Enchanted Blaze Rod + 2,000,000 Coin + tier XI minion |

#### Cave Spider Minion (Combat)

- unlocked at **Spider Eye I** collection
- Produces per harvest: Spider Eye ×1 (0.3 xp); String ×0.5 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Cave Spiders! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 17 | 17 | 13 |
| Storage (items) | 128 | 256 | 256 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Spider Eye around **Wooden Sword** | 8 slots of 20× Spider Eye around prev. tier | 8 slots of 40× Spider Eye around prev. tier | 8 slots of 64× Spider Eye around prev. tier | 8 slots of 1× Enchanted Spider Eye around prev. tier | 8 slots of 3× Enchanted Spider Eye around prev. tier | 8 slots of 8× Enchanted Spider Eye around prev. tier | 8 slots of 16× Enchanted Spider Eye around prev. tier | 8 slots of 32× Enchanted Spider Eye around prev. tier | 8 slots of 64× Enchanted Spider Eye around prev. tier | 8 slots of 2× Enchanted Fermented Spider Eye around prev. tier |

#### Creeper Minion (Combat)

- unlocked at **Gunpowder I** collection
- Produces per harvest: Gunpowder ×1 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and slaying Creepers! BEWARE OF EXPLOSIONS! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 27 | 27 | 25 | 25 | 23 | 23 | 21 | 21 | 18 | 18 | 14 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Gunpowder around **Wooden Sword** | 8 slots of 20× Gunpowder around prev. tier | 8 slots of 40× Gunpowder around prev. tier | 8 slots of 64× Gunpowder around prev. tier | 8 slots of 1× Enchanted Gunpowder around prev. tier | 8 slots of 3× Enchanted Gunpowder around prev. tier | 8 slots of 8× Enchanted Gunpowder around prev. tier | 8 slots of 16× Enchanted Gunpowder around prev. tier | 8 slots of 32× Enchanted Gunpowder around prev. tier | 8 slots of 64× Enchanted Gunpowder around prev. tier | 8 slots of 2× Enchanted Firework Rocket around prev. tier |

#### Enderman Minion (Combat)

- unlocked at **Ender Pearl I** collection
- Produces per harvest: Ender Pearl ×1 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and slaying Endermen! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 32 | 32 | 30 | 30 | 28 | 28 | 25 | 25 | 22 | 22 | 18 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Ender Pearl around **Wooden Sword** | 8 slots of 16× Ender Pearl around prev. tier | 8 slots of 1× Enchanted Ender Pearl around prev. tier | 8 slots of 3× Enchanted Ender Pearl around prev. tier | 8 slots of 6× Enchanted Ender Pearl around prev. tier | 8 slots of 12× Enchanted Ender Pearl around prev. tier | 8 slots of 1× Enchanted Eye of Ender around prev. tier | 8 slots of 3× Enchanted Eye of Ender around prev. tier | 8 slots of 6× Enchanted Eye of Ender around prev. tier | 8 slots of 12× Enchanted Eye of Ender around prev. tier | 8 slots of 24× Enchanted Eye of Ender around prev. tier |

#### Ghast Minion (Combat)

- unlocked at **Ghast Tear I** collection
- Produces per harvest: Ghast Tear ×1 (0.5 xp)
- In-game lore: “Place this minion and it will start generating and slaying Ghasts! BEWARE OF EXPLOSIONS! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 50 | 50 | 47 | 47 | 44 | 44 | 41 | 41 | 38 | 38 | 32 | 30 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Ghast Tear around **Wooden Sword** | 8 slots of 16× Ghast Tear around prev. tier | 8 slots of 32× Ghast Tear around prev. tier | 8 slots of 64× Ghast Tear around prev. tier | 8 slots of 32× Enchanted Ghast Tear around prev. tier | 8 slots of 64× Enchanted Ghast Tear around prev. tier | 8 slots of 4× Silver Fang around prev. tier | 8 slots of 8× Silver Fang around prev. tier | 8 slots of 16× Silver Fang around prev. tier | 8 slots of 32× Silver Fang around prev. tier | 8 slots of 64× Silver Fang around prev. tier | TRADE at Hilda/Marthos: 1,024 Silver Fang + 2,000,000 Coin + tier XI minion |

#### Magma Cube Minion (Combat)

- unlocked at **Magma Cream I** collection
- Produces per harvest: Magma Cream ×1.8 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Magma Cubes! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 32 | 32 | 30 | 30 | 28 | 28 | 25 | 25 | 22 | 22 | 18 | 16 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Magma Cream around **Wooden Sword** | 8 slots of 20× Magma Cream around prev. tier | 8 slots of 40× Magma Cream around prev. tier | 8 slots of 64× Magma Cream around prev. tier | 8 slots of 1× Enchanted Magma Cream around prev. tier | 8 slots of 2× Enchanted Magma Cream around prev. tier | 8 slots of 4× Enchanted Magma Cream around prev. tier | 8 slots of 8× Enchanted Magma Cream around prev. tier | 8 slots of 16× Enchanted Magma Cream around prev. tier | 8 slots of 32× Enchanted Magma Cream around prev. tier | 8 slots of 64× Enchanted Magma Cream around prev. tier | TRADE at Hilda/Marthos: 1,024 Enchanted Magma Cream + 2,000,000 Coin + tier XI minion |

#### Skeleton Minion (Combat)

- unlocked at **Bone I** collection
- Produces per harvest: Bone ×1 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Skeletons! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 17 | 17 | 13 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Bone around **Wooden Sword** | 8 slots of 20× Bone around prev. tier | 8 slots of 40× Bone around prev. tier | 8 slots of 64× Bone around prev. tier | 8 slots of 1× Enchanted Bone around prev. tier | 8 slots of 2× Enchanted Bone around prev. tier | 8 slots of 4× Enchanted Bone around prev. tier | 8 slots of 8× Enchanted Bone around prev. tier | 8 slots of 16× Enchanted Bone around prev. tier | 8 slots of 32× Enchanted Bone around prev. tier | 8 slots of 64× Enchanted Bone around prev. tier |

#### Slime Minion (Combat)

- unlocked at **Slimeball I** collection
- Produces per harvest: Slimeball ×1.8 (0.2 xp)
- In-game lore: “Place this minion and it will start generating and slaying Slimes! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 19 | 19 | 16 | 16 | 12 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Slimeball around **Wooden Sword** | 8 slots of 20× Slimeball around prev. tier | 8 slots of 40× Slimeball around prev. tier | 8 slots of 64× Slimeball around prev. tier | 8 slots of 1× Enchanted Slimeball around prev. tier | 8 slots of 3× Enchanted Slimeball around prev. tier | 8 slots of 8× Enchanted Slimeball around prev. tier | 8 slots of 16× Enchanted Slimeball around prev. tier | 8 slots of 32× Enchanted Slimeball around prev. tier | 8 slots of 64× Enchanted Slimeball around prev. tier | 8 slots of 1× Enchanted Slime Block around prev. tier |

#### Spider Minion (Combat)

- unlocked at **String I** collection
- Produces per harvest: String ×1 (0.2 xp); Spider Eye ×0.5 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and slaying Spiders! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 17 | 17 | 13 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× String around **Wooden Sword** | 8 slots of 20× String around prev. tier | 8 slots of 40× String around prev. tier | 8 slots of 64× String around prev. tier | 8 slots of 1× Enchanted String around prev. tier | 8 slots of 2× Enchanted String around prev. tier | 8 slots of 4× Enchanted String around prev. tier | 8 slots of 8× Enchanted String around prev. tier | 8 slots of 16× Enchanted String around prev. tier | 8 slots of 32× Enchanted String around prev. tier | 8 slots of 64× Enchanted String around prev. tier |

#### Vampire Minion (Combat)

- unlocked at **Hemovibe I** collection
- Produces per harvest: Hemovibe ×1 (0 xp)
- In-game lore: “Place this minion and it will start generating and slaying Vampire Scions! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 190 | 190 | 175 | 175 | 160 | 160 | 140 | 140 | 117 | 117 | 95 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Hemovibe around **Bat Person Helmet** | 8 slots of 20× Hemovibe around **Vampire Minion I** | 8 slots of 40× Hemovibe around **Vampire Minion II** | 8 slots of 64× Hemovibe around **Vampire Minion III** | 8 slots of 1× Hemoglass around **Vampire Minion IV** | 8 slots of 2× Hemoglass around **Vampire Minion V** | 8 slots of 4× Hemoglass around **Vampire Minion VI** | 8 slots of 8× Hemoglass around **Vampire Minion VII** | 8 slots of 16× Hemoglass around **Vampire Minion VIII** | 8 slots of 32× Hemoglass around **Vampire Minion IX** | 8 slots of 64× Hemoglass around **Vampire Minion X** |

#### Zombie Minion (Combat)

- unlocked at **Rotten Flesh I** collection
- Produces per harvest: Rotten Flesh ×1 (0.3 xp); Poisonous Potato ×0.05 (0 xp); Potato ×0.01 (0.1 xp); Carrot ×0.01 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and slaying Zombies! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 26 | 26 | 24 | 24 | 22 | 22 | 20 | 20 | 17 | 17 | 13 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Rotten Flesh around **Wooden Sword** | 8 slots of 20× Rotten Flesh around prev. tier | 8 slots of 40× Rotten Flesh around prev. tier | 8 slots of 64× Rotten Flesh around prev. tier | 8 slots of 1× Enchanted Rotten Flesh around prev. tier | 8 slots of 2× Enchanted Rotten Flesh around prev. tier | 8 slots of 4× Enchanted Rotten Flesh around prev. tier | 8 slots of 8× Enchanted Rotten Flesh around prev. tier | 8 slots of 16× Enchanted Rotten Flesh around prev. tier | 8 slots of 32× Enchanted Rotten Flesh around prev. tier | 8 slots of 64× Enchanted Rotten Flesh around prev. tier |

#### Inferno Minion (Slayer)

- unlocked at **Blaze Slayer III** collection; ability (needs tier 1): Ability: Rising Celsius Each Inferno minion increases the speed of all Inferno minions by 18%.; ability (needs tier 10): Ability: Apex Minion When creating an Inferno Apex, create 2.
- Produces per harvest: Crude Gabagool ×1 (0 xp)
- In-game lore: “Place this minion and it will start generating and slaying Infernals! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 1,013 | 982 | 950 | 919 | 886 | 855 | 823 | 792 | 760 | 728 | 697 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Derelict Ashe around **Blaze Minion I** | 8 slots of 40× Derelict Ashe around prev. tier | 8 slots of 1× Molten Powder around prev. tier | 8 slots of 2× Molten Powder around prev. tier | 8 slots of 4× Molten Powder around prev. tier | 8 slots of 8× Molten Powder around prev. tier | 8 slots of 16× Molten Powder around prev. tier | 8 slots of 32× Molten Powder around prev. tier | 4 slots of 64× Molten Powder around prev. tier + B1=Inferno Vertex,4, A2=Inferno Vertex,4, C2=Inferno Vertex,4, B3=Inferno Vertex,4 | 4 slots of 64× Molten Powder around prev. tier + B1=Inferno Vertex,12, A2=Inferno Vertex,12, C2=Inferno Vertex,12, B3=Inferno Vertex,12 | 4 slots of 64× Molten Powder around prev. tier + B1=Inferno Apex, A2=Inferno Vertex,24, C2=Inferno Vertex,24, B3=Inferno Minion VIII |

#### Revenant Minion (Slayer)

- unlocked at **Zombie Slayer V** collection
- Produces per harvest: Rotten Flesh ×3 (0.3 xp); Diamond ×0.2 (0.4 xp)
- In-game lore: “Place this minion and it will start generating and struggling with Revenants! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 29 | 29 | 26 | 26 | 23 | 23 | 19 | 19 | 14.5 | 14.5 | 10 | 8 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Revenant Flesh around **Crystallized Heart** | 7 slots of 20× Revenant Flesh around prev. tier + B3=Zombie Minion I | 7 slots of 40× Revenant Flesh around prev. tier + B3=Zombie Minion II | 7 slots of 64× Revenant Flesh around prev. tier + B3=Zombie Minion III | 7 slots of 1× Revenant Viscera around prev. tier + B3=Zombie Minion IV | 7 slots of 2× Revenant Viscera around prev. tier + B3=Zombie Minion V | 7 slots of 4× Revenant Viscera around prev. tier + B3=Zombie Minion VI | 7 slots of 8× Revenant Viscera around prev. tier + B3=Zombie Minion VII | 7 slots of 16× Revenant Viscera around prev. tier + B3=Zombie Minion VIII | 7 slots of 32× Revenant Viscera around prev. tier + B3=Zombie Minion IX | 7 slots of 64× Revenant Viscera around prev. tier + B3=Zombie Minion X | TRADE at Bartender: 64 Revenant Viscera + 2,000,000 Coin + tier XI minion |

#### Tarantula Minion (Slayer)

- unlocked at **Spider Slayer V** collection
- Produces per harvest: String ×3.16 (0.2 xp); Spider Eye ×1 (0.3 xp); Iron Ingot ×0.2 (0.3 xp)
- In-game lore: “Place this minion and it will start generating and squashing Tarantulas! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 29 | 29 | 26 | 26 | 23 | 23 | 19 | 19 | 14.5 | 14.5 | 10 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Tarantula Web around **Enchanted Fermented Spider Eye** | 7 slots of 20× Tarantula Web around prev. tier + B3=Spider Minion I | 7 slots of 40× Tarantula Web around prev. tier + B3=Spider Minion II | 7 slots of 64× Tarantula Web around prev. tier + B3=Spider Minion III | 7 slots of 1× Tarantula Silk around prev. tier + B3=Spider Minion IV | 7 slots of 2× Tarantula Silk around prev. tier + B3=Spider Minion V | 7 slots of 4× Tarantula Silk around prev. tier + B3=Spider Minion VI | 7 slots of 8× Tarantula Silk around prev. tier + B3=Spider Minion VII | 7 slots of 16× Tarantula Silk around prev. tier + B3=Spider Minion VIII | 7 slots of 32× Tarantula Silk around prev. tier + B3=Spider Minion IX | 7 slots of 64× Tarantula Silk around prev. tier + B3=Spider Minion X |

#### Voidling Minion (Slayer)

- unlocked at **Enderman Slayer IV** collection
- Produces per harvest: Nether Quartz ×0.4 (0.3 xp); Obsidian ×2.42 (0.4 xp); Enchanted Ender Pearl ×0.000625 (48 xp)
- In-game lore: “Place this minion and it will start generating and slaying Voidlings! Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 45 | 45 | 42 | 42 | 39 | 39 | 35 | 35 | 30 | 30 | 24 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Null Sphere around **Enderman Minion I** | 7 slots of 20× Null Sphere around prev. tier + B3=Obsidian Minion I | 7 slots of 40× Null Sphere around prev. tier + B3=Enderman Minion II | 7 slots of 64× Null Sphere around prev. tier + B3=Obsidian Minion III | 7 slots of 1× Null Ovoid around prev. tier + B3=Enderman Minion IV | 7 slots of 2× Null Ovoid around prev. tier + B3=Obsidian Minion V | 7 slots of 4× Null Ovoid around prev. tier + B3=Enderman Minion VI | 7 slots of 8× Null Ovoid around prev. tier + B3=Obsidian Minion VII | 7 slots of 16× Null Ovoid around prev. tier + B3=Enderman Minion VIII | 7 slots of 32× Null Ovoid around prev. tier + B3=Obsidian Minion IX | 7 slots of 64× Null Ovoid around prev. tier + B3=Enderman Minion X |

#### Acacia Minion (Foraging)

- unlocked at **Acacia Log I** collection
- Produces per harvest: Acacia Log ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and chopping acacia logs! Requires an open area to plant trees. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 48 | 48 | 45 | 45 | 42 | 42 | 38 | 38 | 33 | 33 | 27 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Acacia Log around **Wooden Axe** | 8 slots of 20× Acacia Log around prev. tier | 8 slots of 40× Acacia Log around prev. tier | 8 slots of 64× Acacia Log around prev. tier | 8 slots of 1× Enchanted Acacia Log around prev. tier | 8 slots of 2× Enchanted Acacia Log around prev. tier | 8 slots of 4× Enchanted Acacia Log around prev. tier | 8 slots of 8× Enchanted Acacia Log around prev. tier | 8 slots of 16× Enchanted Acacia Log around prev. tier | 8 slots of 32× Enchanted Acacia Log around prev. tier | 8 slots of 64× Enchanted Acacia Log around prev. tier |

#### Birch Minion (Foraging)

- unlocked at **Birch Log I** collection
- Produces per harvest: Birch Log ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and chopping birch logs! Requires an open area to plant trees. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 48 | 48 | 45 | 45 | 42 | 42 | 38 | 38 | 33 | 33 | 27 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Birch Log around **Wooden Axe** | 8 slots of 20× Birch Log around prev. tier | 8 slots of 40× Birch Log around prev. tier | 8 slots of 64× Birch Log around prev. tier | 8 slots of 1× Enchanted Birch Log around prev. tier | 8 slots of 2× Enchanted Birch Log around prev. tier | 8 slots of 4× Enchanted Birch Log around prev. tier | 8 slots of 8× Enchanted Birch Log around prev. tier | 8 slots of 16× Enchanted Birch Log around prev. tier | 8 slots of 32× Enchanted Birch Log around prev. tier | 8 slots of 64× Enchanted Birch Log around prev. tier |

#### Dark Oak Minion (Foraging)

- unlocked at **Dark Oak Log I** collection
- Produces per harvest: Dark Oak Log ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and chopping dark oak logs! Requires an open area to plant trees. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 48 | 48 | 45 | 45 | 42 | 42 | 38 | 38 | 33 | 33 | 27 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Dark Oak Log around **Wooden Axe** | 8 slots of 20× Dark Oak Log around prev. tier | 8 slots of 40× Dark Oak Log around prev. tier | 8 slots of 64× Dark Oak Log around prev. tier | 8 slots of 1× Enchanted Dark Oak Log around prev. tier | 8 slots of 2× Enchanted Dark Oak Log around prev. tier | 8 slots of 4× Enchanted Dark Oak Log around prev. tier | 8 slots of 8× Enchanted Dark Oak Log around prev. tier | 8 slots of 16× Enchanted Dark Oak Log around prev. tier | 8 slots of 32× Enchanted Dark Oak Log around prev. tier | 8 slots of 64× Enchanted Dark Oak Log around prev. tier |

#### Flower Minion (Foraging)

- obtained via Dark Auction
- Produces per harvest: Dandelion ×0.35 (0.1 Foraging xp); Poppy ×0.15 (0.1 Foraging xp); Allium ×0.0417 (0.1 Foraging xp); Azure Bluet ×0.0417 (0.1 Foraging xp); Blue Orchid ×0.0417 (0.1 Foraging xp); Oxeye Daisy ×0.0417 (0.1 Foraging xp); Red Tulip ×0.0417 (0.1 Foraging xp); Orange Tulip ×0.0417 (0.1 Foraging xp); White Tulip ×0.0417 (0.1 Foraging xp); Pink Tulip ×0.0417 (0.1 Foraging xp); Lilac ×0.0417 (0.2 Foraging xp); Peony ×0.0417 (0.2 Foraging xp); Rose Bush ×0.0417 (0.2 Foraging xp)
- In-game lore: “Place this minion and it will start generating and harvesting flowers. Requires grass nearby so flowers can be planted. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 30 | 29 | 28 | 27 | 26 | 25 | 24 | 23 | 22 | 20 | 18 | 15 |
| Storage (items) | 960 | 960 | 960 | 960 | 960 | 960 | 960 | 960 | 960 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 0× Dandelion — Bought from<br /  Dark Auction | 8 slots of 20× Dandelion around prev. tier | 8 slots of 40× Dandelion around prev. tier | 8 slots of 64× Dandelion around prev. tier | 8 slots of 1× Enchanted Dandelion around prev. tier | 8 slots of 3× Enchanted Dandelion around prev. tier | 8 slots of 8× Enchanted Dandelion around prev. tier | 8 slots of 16× Enchanted Dandelion around prev. tier | 8 slots of 32× Enchanted Dandelion around prev. tier | 8 slots of 64× Enchanted Dandelion around prev. tier | 8 slots of 1× Enchanted Poppy around prev. tier | 8 slots of 2× Enchanted Poppy around prev. tier |

#### Jungle Minion (Foraging)

- unlocked at **Jungle Log I** collection
- Produces per harvest: Jungle Log ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and chopping jungle logs! Requires an open area to plant trees. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 48 | 48 | 45 | 45 | 42 | 42 | 38 | 38 | 33 | 33 | 27 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Jungle Log around **Wooden Axe** | 8 slots of 20× Jungle Log around prev. tier | 8 slots of 40× Jungle Log around prev. tier | 8 slots of 64× Jungle Log around prev. tier | 8 slots of 1× Enchanted Jungle Log around prev. tier | 8 slots of 2× Enchanted Jungle Log around prev. tier | 8 slots of 4× Enchanted Jungle Log around prev. tier | 8 slots of 8× Enchanted Jungle Log around prev. tier | 8 slots of 16× Enchanted Jungle Log around prev. tier | 8 slots of 32× Enchanted Jungle Log around prev. tier | 8 slots of 64× Enchanted Jungle Log around prev. tier |

#### Oak Minion (Foraging)

- unlocked at **Oak Log I** collection
- Produces per harvest: Oak Log ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and chopping oak logs! Requires an open area to plant trees. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 48 | 48 | 45 | 45 | 42 | 42 | 38 | 38 | 33 | 33 | 27 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Oak Log around **Wooden Axe** | 8 slots of 20× Oak Log around prev. tier | 8 slots of 40× Oak Log around prev. tier | 8 slots of 64× Oak Log around prev. tier | 8 slots of 1× Enchanted Oak Log around prev. tier | 8 slots of 2× Enchanted Oak Log around prev. tier | 8 slots of 4× Enchanted Oak Log around prev. tier | 8 slots of 8× Enchanted Oak Log around prev. tier | 8 slots of 16× Enchanted Oak Log around prev. tier | 8 slots of 32× Enchanted Oak Log around prev. tier | 8 slots of 64× Enchanted Oak Log around prev. tier |

#### Spruce Minion (Foraging)

- unlocked at **Spruce Log I** collection
- Produces per harvest: Spruce Log ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and chopping spruce logs! Requires an open area to plant trees. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 48 | 48 | 45 | 45 | 42 | 42 | 38 | 38 | 33 | 33 | 27 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Spruce Log around **Wooden Axe** | 8 slots of 20× Spruce Log around prev. tier | 8 slots of 40× Spruce Log around prev. tier | 8 slots of 64× Spruce Log around prev. tier | 8 slots of 1× Enchanted Spruce Log around prev. tier | 8 slots of 2× Enchanted Spruce Log around prev. tier | 8 slots of 4× Enchanted Spruce Log around prev. tier | 8 slots of 8× Enchanted Spruce Log around prev. tier | 8 slots of 16× Enchanted Spruce Log around prev. tier | 8 slots of 32× Enchanted Spruce Log around prev. tier | 8 slots of 64× Enchanted Spruce Log around prev. tier |

#### Clay Minion (Fishing)

- unlocked at **Clay Ball I** collection
- Produces per harvest: Clay Ball ×4 (0.1 xp)
- In-game lore: “Place this minion and it will start generating and mining clay! Requires an open area to place clay. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 32 | 32 | 30 | 30 | 27.5 | 27.5 | 24 | 24 | 20 | 20 | 16 | 14 |
| Storage (items) | 64 | 192 | 192 | 384 | 384 | 576 | 576 | 768 | 768 | 960 | 960 | 960 |
| Upgrade recipe | 8 slots of 10× Clay Ball around **Wooden Shovel** | 8 slots of 20× Clay Ball around prev. tier | 8 slots of 40× Clay Ball around prev. tier | 8 slots of 64× Clay Ball around prev. tier | 8 slots of 1× Enchanted Clay Ball around prev. tier | 8 slots of 2× Enchanted Clay Ball around prev. tier | 8 slots of 4× Enchanted Clay Ball around prev. tier | 8 slots of 8× Enchanted Clay Ball around prev. tier | 8 slots of 16× Enchanted Clay Ball around prev. tier | 8 slots of 32× Enchanted Clay Ball around prev. tier | 8 slots of 64× Enchanted Clay Ball around prev. tier | around prev. tier + B3=Clay Minion XII Upgrade Stone |

#### Fishing Minion (Fishing)

- unlocked at **Raw Cod II** collection
- Produces per harvest: Raw Cod ×1 (0.5 xp); Raw Salmon ×0.5 (0.7 xp); Pufferfish ×0.24 (1 xp); Tropical Fish ×0.08 (2 xp); Prismarine Crystals ×0.06 (0.25 xp); Prismarine Shard ×0.06 (0.25 xp); Sponge ×0.06 (0.5 xp)
- In-game lore: “Place this minion and it will start fishing. Requires water nearby. Minions also work when you are offline!”
| | I | II | III | IV | V | VI | VII | VIII | IX | X | XI | XII |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBA (s) | 75 | 75 | 67 | 67 | 59 | 59 | 51 | 51 | 43 | 43 | 35 | 30 |
| Storage (items) | 640 | 640 | 640 | 704 | 704 | 768 | 768 | 832 | 832 | 896 | 960 | 960 |
| Upgrade recipe | 8 slots of 8× Raw Cod around **Fishing Rod** | 8 slots of 16× Raw Cod around prev. tier | 8 slots of 32× Raw Cod around prev. tier | 8 slots of 64× Raw Cod around prev. tier | 8 slots of 1× Enchanted Raw Cod around prev. tier | 8 slots of 3× Enchanted Raw Cod around prev. tier | 8 slots of 8× Enchanted Raw Cod around prev. tier | 8 slots of 16× Enchanted Raw Cod around prev. tier | 8 slots of 32× Enchanted Raw Cod around prev. tier | 8 slots of 64× Enchanted Raw Cod around prev. tier | 8 slots of 1× Enchanted Cooked Cod around prev. tier | around prev. tier + B3=Fishing Minion XII Upgrade Stone |


---

## 5. Crafting

### 5.1 The crafting system

| Aspect | Rule |
|---|---|
| Where | A 3×3 grid available anywhere from the SkyBlock Menu (no need for a placed table), or a placed Crafting Table. The 2×2 inventory grid only does vanilla recipes and produces items **without SkyBlock lore/rarity** (a known bug — the clone should either block custom items in 2×2 or fully support them). |
| Unlocking | Custom recipes must be **unlocked** before they can be crafted: by collection tiers (most), skill levels, slayer levels, Hypixel ranks, or special sources. Vanilla recipes are always available. Super Compactor works without the recipe unlock. |
| Recipe forms | **Normal** (fixed positions; an item can have several valid layouts) and **Shapeless** (any arrangement, exact counts). Slots can hold **stacks** (e.g. "32× Diamond" in one slot) — SkyBlock recipes consume counts per slot, unlike vanilla. |
| Recipe Book | SkyBlock Menu → Recipe Book: every recipe grouped by category, locked ones show the unlock requirement. As of 2023 it held 870 recipes (Farming 144, Mining 236, Combat 83, Fishing 81, Foraging 52, Enchanting 59, Alchemy 69, Carpentry 1, Slayer 129, Special 16). |
| Quick Crafting | Carpentry 3: the 3 most expensive craftable outputs from inventory materials show beside the output slot. |
| XP | Crafting grants Carpentry XP = 3% of the ingredients' total NPC sell value. |
| Crafted Minions UI | `/craftedgenerators` (also Collection → minion head): grid of every minion type × tier, which are crafted, unique count, slots, next slot threshold. |

### 5.2 Enchanted items — the 160 rule and its exceptions

The standard compression is **160 of an item → 1 Enchanted item**, laid out as 5 slots of 32 (a plus shape,
or top row + two below). The next step is usually **160 Enchanted → 1 "block"/second-tier enchanted**
(so 25,600 raw items per second-tier item). Exceptions exist where the raw item stacks to 16 or is meant to
be cheaper/more expensive:

| Exception | Recipe |
|---|---|
| Enchanted Ender Pearl | 20 Ender Pearl (4 per slot, 5 slots — pearls stack to 16) |
| Enchanted Hard Stone | 576 Hard Stone; Concentrated Stone = 576 Enchanted Hard Stone |
| Enchanted Bread | 60 Wheat |
| Enchanted Ghast Tear | 5 Ghast Tear |
| Enchanted Sponge / Wet Sponge | 40 Sponge / 40 Enchanted Sponge |
| Enchanted Egg / Super Enchanted Egg | 144 Egg / 144 Enchanted Egg |
| Enchanted Glowstone | 192 Enchanted Glowstone Dust |
| Enchanted Poppy | 576 Poppy (?) |
| Enchanted Cookie | 128 Enchanted Cocoa Beans + 32 Wheat |
| Absolute Ender Pearl | 80 Enchanted Ender Pearl |
| Silver Fang | 25 Enchanted Ghast Tear |
| Hay Bale / Melon / Packed Ice / Snow Block | vanilla 9 / 9 / 9 / 4 |
| Multi-input specials | Revenant Viscera (128 Revenant Flesh + 32 Enchanted String), Tarantula Silk (128 Tarantula Web + 32 Enchanted Flint), Null Ovoid (128 Null Sphere + 32 Enchanted Obsidian), Molten Powder (128 Derelict Ashe + 1 Amalgamated Crimsonite), Enchanted Eye of Ender (16 Enchanted Ender Pearl + 64 Blaze Powder), Enchanted Firework Rocket (64 Enchanted Gunpowder + 16 Paper), Enchanted Fermented Spider Eye (64 Enchanted Spider Eye + 64 Brown Mushroom + 64 Sugar) |

### 5.3 Compression chain table (every chain the minion data knows about)

Generated from the `recipes` blocks of `Module:Minion/Data` (124 outputs). These are also exactly the
transformations a **Super Compactor 3000** performs. (Two wiki rows are suspicious and flagged:
"Enchanted Blaze Powder = 160 Blaze Rod" — in-game this may be 160 Blaze *Powder* (?); "Blaze Powder = 0.5 Blaze Rod"
is just vanilla 1 rod → 2 powder.)

| Output | Recipe (shapeless unless noted) |
|---|---|
| Absolute Ender Pearl | 80× Enchanted Ender Pearl |
| Amalgamated Crimsonite | 4× Enchanted Red Sand + 4× Enchanted Mycelium + 16× Enchanted Glowstone Dust + 16× Enchanted Magma Cream + 16× Enchanted Blaze Powder + 16× Enchanted Nether Wart |
| Blaze Powder | 0.5× Blaze Rod |
| Box of Seeds | 160× Enchanted Seeds |
| Compacted Moonflower | 160× Enchanted Moonflower |
| Compacted Sunflower | 160× Enchanted Sunflower |
| Concentrated Stone | 576× Enchanted Hard Stone |
| Enchanted Acacia Log | 160× Acacia Log |
| Enchanted Baked Potato | 160× Enchanted Potato |
| Enchanted Birch Log | 160× Birch Log |
| Enchanted Blaze Powder | 160× Blaze Rod |
| Enchanted Blaze Rod | 160× Enchanted Blaze Powder |
| Enchanted Bone | 160× Bone |
| Enchanted Bread | 60× Wheat |
| Enchanted Brown Mushroom | 160× Brown Mushroom |
| Enchanted Cactus | 160× Enchanted Cactus Green |
| Enchanted Cactus Green | 160× Cactus |
| Enchanted Carrot | 160× Carrot |
| Enchanted Clay Ball | 160× Clay Ball |
| Enchanted Coal | 160× Coal |
| Enchanted Coal Block | 160× Enchanted Coal |
| Enchanted Cobblestone | 160× Cobblestone |
| Enchanted Cocoa Beans | 160× Cocoa Beans |
| Enchanted Cooked Cod | 160× Enchanted Raw Cod |
| Enchanted Cooked Mutton | 160× Enchanted Raw Mutton |
| Enchanted Cooked Porkchop | 160× Enchanted Raw Porkchop |
| Enchanted Cooked Rabbit | 160× Enchanted Raw Rabbit |
| Enchanted Cooked Salmon | 160× Enchanted Raw Salmon |
| Enchanted Cookie | 128× Enchanted Cocoa Beans + 32× Wheat |
| Enchanted Dandelion | 160× Dandelion |
| Enchanted Dark Oak Log | 160× Dark Oak Log |
| Enchanted Diamond | 160× Diamond |
| Enchanted Diamond Block | 160× Enchanted Diamond |
| Enchanted Egg | 144× Egg |
| Enchanted Emerald | 160× Emerald |
| Enchanted Emerald Block | 160× Enchanted Emerald |
| Enchanted End Stone | 160× End Stone |
| Enchanted Ender Pearl | 20× Ender Pearl |
| Enchanted Eye of Ender | 16× Enchanted Ender Pearl + 64× Blaze Powder |
| Enchanted Feather | 160× Feather |
| Enchanted Fermented Spider Eye | 64× Enchanted Spider Eye + 64× Brown Mushroom + 64× Sugar |
| Enchanted Firework Rocket | 64× Enchanted Gunpowder + 16× Paper |
| Enchanted Flint | 160× Flint |
| Enchanted Ghast Tear | 5× Ghast Tear |
| Enchanted Glowstone | 192× Enchanted Glowstone Dust |
| Enchanted Glowstone Dust | 160× Glowstone Dust |
| Enchanted Gold Block | 160× Enchanted Gold Ingot |
| Enchanted Gold Ingot | 160× Gold Ingot |
| Enchanted Golden Carrot | 160× Enchanted Carrot |
| Enchanted Gunpowder | 160× Gunpowder |
| Enchanted Hard Stone | 576× Hard Stone |
| Enchanted Hay Bale | 160× Enchanted Wheat |
| Enchanted Ice | 160× Ice |
| Enchanted Iron Block | 160× Enchanted Iron Ingot |
| Enchanted Iron Ingot | 160× Iron Ingot |
| Enchanted Jungle Log | 160× Jungle Log |
| Enchanted Lapis Lazuli | 160× Lapis Lazuli |
| Enchanted Lapis Lazuli Block | 160× Enchanted Lapis Lazuli |
| Enchanted Leather | 160× Leather |
| Enchanted Magma Cream | 160× Magma Cream |
| Enchanted Melon | 160× Enchanted Melon Slice |
| Enchanted Melon Slice | 160× Melon Slice |
| Enchanted Mithril | 160× Mithril |
| Enchanted Moonflower | 160× Moonflower |
| Enchanted Mushroom | 160× Mushroom |
| Enchanted Mycelium | 160× Mycelium |
| Enchanted Mycelium Cube | 160× Enchanted Mycelium |
| Enchanted Nether Quartz | 160× Nether Quartz |
| Enchanted Nether Wart | 160× Nether Wart |
| Enchanted Oak Log | 160× Oak Log |
| Enchanted Obsidian | 160× Obsidian |
| Enchanted Packed Ice | 160× Enchanted Ice |
| Enchanted Poisonous Potato | 160× Poisonous Potato |
| Enchanted Poppy | 576× Poppy |
| Enchanted Potato | 160× Potato |
| Enchanted Prismarine Crystals | 160× Prismarine Crystals |
| Enchanted Prismarine Shard | 160× Prismarine Shard |
| Enchanted Pufferfish | 160× Pufferfish |
| Enchanted Pumpkin | 160× Pumpkin |
| Enchanted Quartz Block | 160× Enchanted Nether Quartz |
| Enchanted Raw Beef | 160× Raw Beef |
| Enchanted Raw Chicken | 160× Raw Chicken |
| Enchanted Raw Cod | 160× Raw Cod |
| Enchanted Raw Mutton | 160× Raw Mutton |
| Enchanted Raw Porkchop | 160× Raw Porkchop |
| Enchanted Raw Rabbit | 160× Raw Rabbit |
| Enchanted Raw Salmon | 160× Raw Salmon |
| Enchanted Red Mushroom | 160× Red Mushroom |
| Enchanted Red Sand | 160× Red Sand |
| Enchanted Red Sand Cube | 160× Enchanted Red Sand |
| Enchanted Redstone Block | 160× Enchanted Redstone Dust |
| Enchanted Redstone Dust | 160× Redstone Dust |
| Enchanted Rotten Flesh | 160× Rotten Flesh |
| Enchanted Sand | 160× Sand |
| Enchanted Seeds | 160× Seeds |
| Enchanted Slime Block | 160× Enchanted Slimeball |
| Enchanted Slimeball | 160× Slimeball |
| Enchanted Snow Block | 160× Snow Block |
| Enchanted Spider Eye | 160× Spider Eye |
| Enchanted Sponge | 40× Sponge |
| Enchanted Spruce Log | 160× Spruce Log |
| Enchanted String | 160× String |
| Enchanted Sugar | 160× Sugar Cane |
| Enchanted Sugar Cane | 160× Enchanted Sugar |
| Enchanted Sunflower | 160× Sunflower |
| Enchanted Tropical Fish | 160× Tropical Fish |
| Enchanted Wet Sponge | 40× Enchanted Sponge |
| Enchanted Wheat | 160× Wheat |
| Enchanted Wool | 160× White Wool |
| Hay Bale | 9× Wheat |
| Hemoglass | 160× Hemovibe |
| Melon | 9× Melon Slice |
| Molten Powder | 128× Derelict Ashe + 1× Amalgamated Crimsonite |
| Mutant Nether Wart | 160× Enchanted Nether Wart |
| Null Ovoid | 128× Null Sphere + 32× Enchanted Obsidian |
| Packed Ice | 9× Ice |
| Polished Pumpkin | 160× Enchanted Pumpkin |
| Refined Mithril | 160× Enchanted Mithril |
| Revenant Viscera | 128× Revenant Flesh + 32× Enchanted String |
| Silver Fang | 25× Enchanted Ghast Tear |
| Snow Block | 4× Snowball |
| Sugar | 1× Sugar Cane |
| Super Enchanted Egg | 144× Enchanted Egg |
| Tarantula Silk | 128× Tarantula Web + 32× Enchanted Flint |


Value math the clone must preserve: `NPC value(enchanted) ≈ 160 × NPC value(base)` for most items, otherwise
compacting becomes a coin printer or a coin sink. Skill/collection credit is only for the base items as
they are obtained, never re-granted on compaction.

---

## 6. SkyBlock XP & SkyBlock Level

### 6.1 Core rules

| Rule | Value |
|---|---|
| XP per level | **100 SkyBlock XP = 1 level**, flat (no curve). Shown as `[LVL]` before the name in chat and tab. |
| Cap | 500 was treated as the cap (a 2022 bug let vanilla XP push levels past 500, and levels above 500 gave no stats). Practical max today is somewhat below the sum of all tasks (?). |
| One-time tasks | Every task can be completed **once** (repeatable activities are converted into milestones with caps). |
| Stats | **+5 Health per level** and **+1 Strength per 5 levels**. |
| Menu | SkyBlock Menu → SkyBlock Leveling: progress bar to next level, categories (Core, Event, Dungeon, Essence Shop, Slaying, Skill Related, Miscellaneous, Story, Consumables) each with "x / max" and task lists, rewards track. |
| Chat | On level-up: a banner "SKYBLOCK LEVEL UP" with the new level, colour change if crossing a colour threshold, and any feature unlock (?) — exact text not verified. |

### 6.2 Level rewards

| Level | Reward |
|---:|---|
| 3 | Access to Community Shop (Elizabeth) |
| 5 | Access to the Garden; Access to Wardrobe |
| 6 | Auto-pickup of block and mob drops |
| 7 | Access to the Bazaar |
| 12 | Access to the Wizard Portal |
| 25 | Book of Progression (accessory) + Stacked Healing bonus |
| 50 | Defensive Strongarm bonus; daily coin trading limit 1B |
| 75 | Book of Progression → Uncommon; Extra Infliction bonus |
| 100 | Daily coin trading limit 10B |
| 150 / 225 / 300 / 400 | Book of Progression → Rare / Epic / Legendary / Mythic |

Prefix colour by level (`[LVL]` number colour; bracket is dark grey `&8`):

| Level | Colour (Minecraft code) |
|---:|---|
| 0–39 | Grey `&7` (?) |
| 40 | White `&f` |
| 80 | Yellow `&e` |
| 120 | Green `&a` |
| 160 | Dark Green `&2` |
| 200 | Aqua `&b` |
| 240 | Cyan `&3` |
| 280 | Blue `&9` |
| 320 | Pink `&d` |
| 360 | Purple `&5` |
| 400 | Gold `&6` |
| 440 | Red `&c` |
| 480 | Dark Red `&4` |

Level emblems (shown after the name, pick one): Diamond ♦ L10, Spade ♠ L20, Heart ❤ L30, Pristine ✧ L40,
Arc Reactor ⎊ L50, Marker ፠ L60, Badge ☬ L70, Star ⚝ L80, Boxes ⧉ L90, Jerry ꈔ L100, Globe ㋖ L120, Soulflow ⸎ L140,
Warning ⚠ L160, Mustache ꕁ L180, Helmet 〠 L200, Sideways Smiley ツ L250, Spaceship ⥈ L300, Toxic ☢ L350,
Biohazard ☣ L400, Florette ✾ L450, Golden Fleur De Lis ⚜ (gold) L500.
Achievement emblems also exist: skill L50 (grey) / L60 (gold) symbols — Mining ⸕, Farming ✿, Combat ❁, Foraging ⸙,
Fishing α, Enchanting ✎, Alchemy ☕, Carpentry ☖, Taming ♣, Social ℻ (L15/L25) — plus dungeon, slayer, HOTM, museum,
magical power (1,000 / 2,000 MP), trophy fish, Jacob, chocolate, etc.

### 6.3 Where SkyBlock XP comes from (task catalogue)

**Core**

| Task | Rate | Max SB XP |
|---|---|---:|
| Skill level-ups | L1–10: 5 · L11–25: 10 · L26–50: 20 · L51–60: 30 per level | 8,120 |
| Museum progression | per milestone | 3,522 |
| Fairy Souls | 10 per 5 souls exchanged | 530 |
| Accessory Bag | 1 per Magical Power | 2,007 |
| Pet Score | 3 per Pet Score point | 1,518 |
| Collections | 4 per collection tier | 3,024 (outdated; 790 tiers × 4 = 3,160 now) |
| Craft Minions | per new unique tier: I–VI 1, VII 2, VIII 3, IX 4, X 6, XI 12, XII 24 | 2,963 |
| Bank upgrades | Gold 20, Deluxe 25, Super Deluxe 30, Premier 35, Luxurious 40, Palatial 50 | 200 |
| Fast Travel unlocked | 15 per travel scroll | 300 |

**Event** — Mining Fiesta (1 per 5,000 ores, cap 1M ores → 200), Fishing Festival (1 per 50 sharks, cap 5,000 → 100),
Spooky Festival brackets (Wood 10 / Stone 20 / Iron 30 / Gold 40 / Diamond 50 / Emerald 75 → 225), Hoppity
(prestige 25 each → 125; Mythic rabbits 10 → 110; Divine rabbits 10 → 50), Jacob's contest gold medal per crop
(10 → 130), event perk shops (tiers 2/2/3/5/7 → 49 per shop), Witch's Stew (5 per brew, 20 max → 100).

**Dungeon** — Catacombs levels (1–39: 20, 40–50: 40 → 1,220), class levels (4 per level → 1,000), first completion
of each floor (Entrance–F4 20, F5–F7 30 → 190; Master Mode 50 each → 350).

**Essence Shop** — every perk tier bought: 2/2/3/5/7 for a 5-tier perk (19), 2/2/3/5/7/8/8/8/9/10 for a 10-tier perk
(62); shop totals Dragon 185, Ice 121, Spider 126, Undead 128, Wither 161, Crimson 194, Gold 188, Diamond 177, Forest 171.

**Slaying** — Slayer levels (L1 15 … → 3,825), Boss collections (1,015), Bestiary (1 per tier → 4,370), Mythological
creatures (1 per 100 kills → 100), first dragon kill per type (Superior 50, others 25 → 200), first slayer kill per
tier (25 each), Kuudra tiers (20/40/60/80/100 → 300), Arachne tiers (20 → 60), mob types (1 each → 33).

**Skill Related** — HOTM tiers (35 → 1,175), commission milestones (20 → 255), Crystal Nucleus runs (4, cap 50 → 200),
Peak of the Mountain (25 → 1,000), fossils (10 → 80), Rock/Dolphin pet milestones (20 → 100), Frozen Corpse milestones
(→140), Anita upgrades (10 per tier → 250), Garden level (10 → 140), visitors (3 → 45), offers (3 → 90), crop upgrades
(1 → 117), crop milestones (1 → 598), plots (5 → 120), composter (→305), greenhouse (5 → 100), DNA analysis (15 → 90),
farming chips (→410), Sowdust (1 per 1M → 250), Trophy Fish (Bronze 4, Silver 8, Gold 16, Diamond 32 per fish type → 60
each), Ship parts (→15), Heart of the Forest (35 → 545), Center of the Forest (25 → 255), Tree Gift milestones (4 → 56).

**Miscellaneous** — Accessory bag upgrades (2 → 198), Rift Timecharms (100 → 800), Accessory powers (15 → 315), 9f relays
(5 → 45), Taming cap increases (10 → 100), Hootie upgrades (→65), Dojo belts (White 20 / Yellow 30 / Green 50 / Blue 75 /
Brown 100 / Black 150 → 425), Harp songs, Abiphone contacts (10 → 800), Community Shop (Island Size 10, Minion Slots
10 each → 50, Guests Limit 10, Coins Allowance → 50), Personal Bank (25 → 110), Mage/Barbarian reputation (5 → 75 each),
Attribute levels (1 → 1,820), Starlyn Sisters (5 → 20), Moonglade Beacon (5 → 20), Fish Family (3 → 45), Carrolyn crops (5 → 35).

**Story** — Rift Guide objectives (3 → 360), objectives (5 → 212), chapters (Moonglade Marsh 140).

**Consumables** — one-time "eat this" items: Metaphysical Serum (5 → 15), Reaper Peppers (10 → 50), McGrubber's Burger
(5 → 25), Wriggling Larva, Festering Maggot, Refined Jyrre, Refined Dark Cacao, Spotlite, Dwarven O's cereals,
Moby-Duck, Brain Food, Filled Rosewater Flask, Mound of Seagrass (2–5 each).

---

## 7. Fairy Souls

| Aspect | Value |
|---|---|
| Count | **267** per the wiki (page flagged outdated; the SB XP cap of 530 = 53 exchanges × 5 = 265 souls exchangeable) (?) |
| What | Rainbow orbs hidden around every island (Hub, islands, Garden, Dungeons rooms, Rift excluded (?)). Right-click to collect, once each per profile. Chat: "SOUL! You found a Fairy Soul!" (?). |
| Special sources | Fishing: the 2nd, 9th and 24th treasure catches are guaranteed Fairy Souls; 2 more at 0.5% in the Fairy Grotto. Garden: first accepted offer from Tia and from Bruuh. Up to 9 in Dungeon rooms. One near the Vanguard Corpse in Fairy Glacite Mineshafts. |
| Exchange | In groups of **5** at Tia the Fairy (Wilderness, Hub). Each exchange: **+10 SkyBlock XP**. |
| Backpack slots | Unlocked at exchange #1, 2, 4, 6, 8, 10, 13, 16, 19, 22, 25, 28, 31, 34, 37 and 41 (16 backpack slots total). |
| History | Until patch 0.15 (2022) each exchange gave permanent stats (Health, Defense, Strength and some Speed (?)); those stat rewards were moved into the SkyBlock Level system. Use the current model. |
| Tracking | Quest Log in the SkyBlock Menu shows found / total per island. |

---

## 8. Essence

| Type | Obtained from | Shop NPC (location) |
|---|---|---|
| Wither | Dungeon reward chests, Dungeon Hub races, dungeon secrets, salvaging | Malik (Catacombs Entrance / Dungeon Hub) |
| Undead | Dungeon reward chests, Crypt Undead kills, salvaging | Malik |
| Crimson | Kuudra runs, salvaging | Vulcan (Forgotten Skull / Dungeon Hub) |
| Spider | Arachne, Lonely & Cellar Spiders, salvaging | Spider Tamer (Arachne's Sanctuary) |
| Dragon | Ender Dragons, Lost Adventurers, Draconic Altar sacrifices, salvaging | Tyzzo (Void Slate) |
| Gold | Dungeon secrets, King Midas, Golden Goblins, Golden Watcher undead, Crystal Hollows chests, Crystal Nucleus | Marigold (Royal Mines) |
| Diamond | Dungeon secrets, Angry Archaeologists, Diamond Goblins, CH chests, Crystal Nucleus | Gemma (Crystal Nucleus) |
| Ice | Winter sea creatures, Frozen Adventurers, salvaging | Frozen Alex (Jerry Pond) |
| Forest | Tree Gifts, cutting Fig & Mangrove trees, Agatha rewards | Rosemary (Murkwater Outpost) |
| Sun Gecko | Defeating the Sun Gecko (Rift) | Oruo the Almighty (Continuum) |

Uses: **star upgrades** (each star +2% stats; dungeon items get +10% per star inside the Catacombs; max 5 stars for
dungeon items, Crimson-essence items up to 15 per the wiki (?)), **dungeonizing** items, and permanent **Essence
Shop perks** (each perk tier also grants SkyBlock XP, §6.3). Essence is stored as a per-type counter (max 2 billion);
it can be bought on the Bazaar only with Catacombs 20 (Forest Essence exempt).

---

## 9. Other progress counters that feed this web (pointers)

| Counter | Feeds | Notes for the clone |
|---|---|---|
| Bestiary | Combat XP + SB XP (1 per tier, max 4,370) + bonus stats | Kills per mob family; minion kills excluded |
| Museum | SB XP (max 3,522) | Donated items |
| Magical Power | SB XP 1:1 (max 2,007) | From accessories in the Accessory Bag |
| Pet Score | SB XP ×3 (max 1,518) | Unique pets by rarity |
| Garden (level, milestones, visitors, plots, composter, greenhouse, DNA) | Farming XP + SB XP | Garden unlocks at SkyBlock Level 5 |
| Heart of the Mountain / Forest | SB XP per tier | Separate trees, other research file |
| Slayer levels | SB XP; unlock slayer minion recipes and upgrades (Flycatcher at Spider 6, Soulflow Engine at Enderman 2/5) | |
| Faction reputation (Crimson Isle) | Mycelium/Red Sand minion tiers | Mage / Barbarian |

---

## 10. How it connects

1. **Gather → Skill XP + Collection**. One player action fires two counters: the skill (XP per block/kill/catch,
   multiplied by wisdom) and the collection (per item obtained, fortune *does* add items). Fortune therefore speeds
   collections but not skills.
2. **Collection tier → Recipe unlock → Crafting**. Tier I of almost every collection unlocks its minion; mid tiers
   unlock the enchanted form (e.g. Wheat V → Enchanted Wheat/Bread), sacks, armour sets, accessories and travel
   scrolls; late tiers give lump-sum skill XP and second-tier compressions (Wheat XI → Enchanted Hay Bale).
3. **Crafting → Minions → Minion slots**. Minion tiers II–XI are crafted from 8× (raw → enchanted → second-tier
   enchanted) material around the previous tier. Each *new* (type, tier) crafted is a unique: +SB XP and progress
   toward the 22-step slot table. More slots → more minions → more offline output.
4. **Minions → Collection + Skill XP (+ coins via hoppers)**. Minion output is only credited when collected, at the
   tiny "minion XP" rates, but in huge volume — so minions are the main way to push collections like Ghast Tear,
   Lapis or Clay that are tedious by hand. Compactors make storage last longer but don't change credit.
5. **Skills → Stats + Fortune + Access**. Skill levels gate zones (Farming 1 Barn, 5 Mushroom Desert; Mining 1 Gold
   Mine, 5 Deep Caverns, 12 Dwarven Mines & Crystal Hollows; Combat 1 Spider's Den, 12 The End, 20 Zealot Bruiser
   Hideout, 22 Crimson Isle; Foraging 1 The Park, 12 Galatea; Fishing 5 Lotus Atoll; Hunting 15 Critter Safari),
   reforges (Mining), enchantments (Enchanting), pet rarity upgrades (Taming), and contribute flat stats (Health,
   Defense, Strength, Crit Chance, Intelligence, Pet Luck…) used by the combat system.
6. **Everything → SkyBlock XP → SkyBlock Level**. Skill levels (8,120 max), collections (~3,160), minions (2,963),
   fairy souls (530), plus dozens of one-time tasks. The level gives +5 HP/level, +1 STR per 5 levels and opens
   the Bazaar (7), Garden (5), Community Shop (3) — so early SB levels are an onboarding gate.
7. **Co-op multiplies the shared parts** (collections, minions, island) but not the personal ones (skills, SB level
   is per-member; SB XP from collections is credited to every member (?)).
8. **Essence + fairy souls + slayer/dungeon counters** sit beside this web and feed SB XP as well.

---

## 11. Implementation notes

### 11.1 Data shapes (suggested)

```jsonc
// static content (load from JSON at boot; generated from this file's tables)
"skills": {
  "FARMING": {
    "maxLevel": 60, "defaultCap": 50,           // cap raised by unlocks
    "xpTable": "STANDARD",                       // or "RUNECRAFTING" / "SOCIAL"
    "perk": { "name": "Farmhand", "stat": "FARMING_FORTUNE", "perLevel": 4 },
    "statRewards": [ { "from": 1, "to": 14, "stat": "HEALTH", "amount": 2 }, ... ],
    "levelUnlocks": { "1": ["ZONE:THE_BARN"], "5": ["ZONE:MUSHROOM_DESERT"] }
  }
},
"xpTables": { "STANDARD": [50, 175, 375, 675, ...cumulative...], "RUNECRAFTING": [...], "SOCIAL": [...] },
"collections": {
  "WHEAT": { "category": "FARMING", "tiers": [ { "amount": 50, "unlocks": ["RECIPE:WHEAT_GENERATOR_*"], "sbxp": 4 }, ... ] }
},
"minions": {
  "WHEAT": {
    "category": "FARMING", "collection": "WHEAT", "unlockTier": 1,
    "layout": "CROP", "block": "WHEAT", "requires": "FARMLAND",
    "drops": [ { "item": "WHEAT", "avg": 1, "xp": 0.2 }, { "item": "SEEDS", "avg": 1.5, "xp": 0.1 } ],
    "tiers": [ { "tier": 1, "tba": 15, "storage": 128, "recipe": { "ring": ["WHEAT", 10], "centre": "WOODEN_HOE" } }, ... ]
  }
}

// per profile (shared by co-op members)
"profile": {
  "collections": { "WHEAT": { "total": 12345, "byMember": { "<uuid>": 10000, "<uuid2>": 2345 }, "tier": 6 } },
  "craftedMinions": ["WHEAT_GENERATOR_1", "WHEAT_GENERATOR_2", ...],   // uniques
  "minionSlotsBonus": 2,                                              // community shop
  "island": { "minions": [ { "id": "uuid", "type": "WHEAT", "tier": 5, "pos": [x,y,z],
               "fuel": { "item": "ENCHANTED_LAVA_BUCKET", "count": 1, "expiresAt": null },
               "upgrades": ["SUPER_COMPACTOR_3000", "DIAMOND_SPREADING"], "shipping": "ENCHANTED_HOPPER",
               "skin": null, "storage": [ { "item": "ENCHANTED_WHEAT", "count": 3 }, ... ],
               "lastTick": 1790899209640, "actionPhase": "HARVEST", "partialProgress": 0.37 } ] },
  "fairySouls": { "found": ["HUB_12", ...], "exchanged": 7 },
  "essence": { "WITHER": 1200, "DRAGON": 50, ... }
}

// per member
"member": {
  "skills": { "FARMING": { "xp": 3022425.0, "cap": 50 } },
  "sbxp": { "total": 4520, "tasks": { "SKILL:FARMING:25": true, "COLLECTION:WHEAT:6": true, ... } },
  "pendingMinionXp": { "FARMING": 123.4 }       // co-op share awaiting island visit
}
```

* Store **cumulative XP** per skill, derive level by binary search on the cumulative table; clamp at `cap`
  (XP beyond the cap still accumulates (?) — Hypixel keeps counting XP past the visible max for leaderboards).
* Store SkyBlock XP as a **set of completed task keys** plus counter-based tasks (e.g. Magical Power), and derive
  the total, so re-computing after a balance change is idempotent and nothing is double-granted.
* Collection tier is derived from `total`, but persist `tier` too so the "you reached tier X" event fires exactly once.

### 11.2 Timers and the minion tick

* Do **not** tick minions every server tick. Store `lastTick` and compute production lazily: on island load,
  on GUI open, and on a slow timer (e.g. every 5–10 s) while the island is loaded.
* `elapsed = now − lastTick`; split into segments where the speed multiplier is constant (fuel expiring mid-span,
  Solar Panel day/night, island unloaded → Solar off). For each segment `actions = (elapsed_seg + carry) / TBA_eff`;
  `harvests = floor(actions / 2)` for alternating minions; keep fractional `carry`.
* Each harvest rolls drops (`avg` → use expected value offline for determinism, or roll RNG while loaded), then
  applies multipliers (Catalysts), then special upgrades (Diamond Spreading, etc.), then compactors, then pushes
  into storage → adjacent chests → shipping (sell to coins at 50%/70%) → stop when nothing can absorb.
* Cap catch-up per load to something sane (e.g. 7 days?) to avoid abuse via clock skew (?).
* Fuel expiry: store `expiresAt` absolute real-time; a stack of N timed fuels = N × duration, consumed one at a time.

### 11.3 What the player sees

| Event | Feedback |
|---|---|
| Skill XP gain | Action-bar text `+4 Farming (12,345/22,425)` (progress within current level) (?) and a short sound |
| Skill level-up | Chat box: "SKILL LEVEL UP Farming XII → XIII", list of rewards (perk value change, stat, coins, SB XP, unlocks), level-up sound |
| Collection tier | Chat: "COLLECTION LEVEL UP Wheat V" + rewards; first-ever item: "COLLECTION UNLOCKED" (?) |
| Recipe unlocked | Listed in the tier-up message; recipe appears in Recipe Book |
| SB level up | "SKYBLOCK LEVEL UP Level 23 → 24" + rewards (+5 HP, +1 STR at multiples of 5, unlocks) (?) |
| Minion full | Floating text "My storage is full! :(" above the minion; minion stops |
| Bad layout | "This location isn't perfect! :(" |
| Minion cap | "You can only place a maximum of # minions!" + clickable "Click here to view your Minion progress..." |
| New unique minion | Chat line announcing the new unique count / slot gained (?) |
| Fairy soul | Chat on pickup with found count; Tia's exchange menu |

### 11.4 Edge cases

* **Fortune vs XP:** Farming/Mining/Foraging XP is per block broken, not per drop; fortune only affects drops (and so collections).
* **Minion XP double-dipping:** credit skill XP + collection on *collection from the minion* only; never on production,
  never on compaction, never on hopper sale.
* **Pick-up exploit:** picking up a minion moves items to the inventory **without** collection credit.
* **Super Compactor without recipe:** allowed; it compacts even if the player hasn't unlocked the recipe.
* **Tier skip:** recipes require the previous tier minion in the centre, so tiers can't be skipped; uniques
  count each tier individually, and trading a minion to another player does not give the receiver a unique (they
  must craft it) (?).
* **Co-op removal:** a kicked member's contributions remain in the collection totals (?); their items are salvageable for 36 h.
* **Offline Solar Panel:** 0 boost offline.
* **Over-cap minions** after losing slots (e.g. leaving co-op that bought community upgrades): existing minions stay
  placed but new ones can't be placed until under the cap (?).
* **Level cap reached:** XP still accumulates (for leaderboards) but no rewards; when the cap is raised, rewards for
  all newly-reached levels are granted at once.
* **Mob minion mobs** must be killed by the minion to count; player kills give no XP; mob falls into void → no drop.
* **Island unload timing:** ~60 s after the last player leaves; guests also keep it loaded.

### 11.5 Hypixel-specific gates to drop or rethink

Rank gating (Runecrafting XP multipliers and L3 cap, Quick Crafting VIP), Bits-shop storage, Booster Cookie
(monetised), Mayor perks (needs the election system), Dark Auction-only Flower minion. Replace with in-game sources.

---

## 12. Progression placement

| Phase | Skills | Collections | Minions | SB Level |
|---|---|---|---|---|
| **Early (first 1–10 h)** | All real skills to ~10–15. Farming 1 → Barn, 5 → Mushroom Desert; Mining 1 → Gold Mine, 5 → Deep Caverns; Combat 1 → Spider's Den; Foraging 1 → The Park | Tier I–V of basic resources (Cobblestone, Coal, Wheat, Oak, Rotten Flesh…): unlocks minions, Enchanted Bread fuel, Small/Medium storage, Compactor (Cobblestone V), first armour sets & talismans | Cobblestone I given; craft T I–V of many types (cheap uniques → slots 5→9); fuel with Enchanted Bread/Coal; Budget Hopper | 0 → ~30: Community Shop (3), Garden + Wardrobe (5), auto-pickup (6), **Bazaar (7)**, Wizard Portal (12) |
| **Mid (10–100 h)** | 20–35: Mining 12 → Dwarven Mines/Crystal Hollows; Combat 12 → End, 20–22 → Crimson Isle; Enchanting 10 → Experimentation Table; Taming 10/20 → pet rarity upgrades; Foraging 12 → Galatea | Tiers VI–IX: Super Compactor (Cobblestone X), Enchanted Lava Bucket (Coal VIII), Enchanted Hopper (Iron IX), Large Storage (Oak IX), crystals, Catalyst (End Stone IX), sacks; slayer minions | T VII–XI on profitable types (Snow, Clay, Revenant, Tarantula, Enderman, Slime…); 15–22 slots; infinite fuels; Flycatcher; crystals/expanders | 30 → 150: Book of Progression (25), Strongarm (50), Infliction (75) |
| **Late (100 h+)** | 40–60 everywhere; Farming 50→60 via Jacob golds; Taming 50→60 via George; Foraging caps | Max tiers (100k–2M thresholds), Garden milestones; boss collections (Dungeons/Kuudra) | All uniques incl. Tier XII (Bulvar/Tony/Bartender/Einary/Lukas); 26 crafted + 5 community = 31 slots; Plasma/Everburning fuels, Hyper Catalysts, Beacons, Mithril Infusion | 150 → 400+: Book upgrades at 150/225/300/400; prefix colours through Red/Dark Red; emblems |

Suggested clone pacing rule of thumb: a new player should unlock the Bazaar (SB 7) within the first session —
SB XP from the first few skill levels (5 each), first collection tiers (4 each) and first minion uniques (1 each)
gets them there in roughly 60–120 minutes on Hypixel (?).
