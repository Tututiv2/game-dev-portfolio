# 04 — Endgame Combat: Slayers, Dungeons (Catacombs), Kuudra

> Research reference for a from-scratch Hypixel SkyBlock clone. Original Hypixel names are used
> throughout; renaming for copyright happens later. Written 2026-10-02.
>
> **Sources.** The official wiki (wiki.hypixel.net) was shut down in July 2026 and now redirects to a
> forum post; the Fandom wiki returns HTTP 402 to scrapers. The numbers here were pulled from the
> community successor **hypixelskyblock.minecraft.wiki** (fetched 2026-10-02) where marked **[W]**, and
> from prior knowledge otherwise. Anything not confirmed is marked **(?)** — treat those as
> *design-plausible defaults*, not as Hypixel's real values. Where two sources disagree both are shown.
>
> **Confidence legend**
> - **[W]** — read from the wiki this session.
> - no mark — prior knowledge, believed correct (long-stable values).
> - **(?)** — uncertain; a reasonable default for the clone. Tune in playtest.

---

## Table of contents

1. Overview — what "endgame combat" is and how the three pillars differ
2. Slayers
   - 2.1 System flow (Maddox, quest, spawn, timer, rewards)
   - 2.2 Unlock chain and per-tier requirements
   - 2.3 Master table: cost / combat XP / slayer XP per tier
   - 2.4 Slayer level XP tables
   - 2.5 Revenant Horror (Zombie)
   - 2.6 Tarantula Broodfather (Spider)
   - 2.7 Sven Packmaster (Wolf)
   - 2.8 Voidgloom Seraph (Enderman)
   - 2.9 Inferno Demonlord (Blaze)
   - 2.10 Riftstalker Bloodfiend (Vampire)
   - 2.11 Loot model: weights, Magic Find, lootshare
   - 2.12 RNG Meter
   - 2.13 Maddox Batphone, Aatrox, Auto-Slayer, Slayer Sacks, Bartender
   - 2.14 Slayer-exclusive gear (recipes)
3. Dungeons — The Catacombs
   - 3.1 Dungeon Hub, Mort, entry, parties, Party Finder
   - 3.2 Floor list and requirements
   - 3.3 Catacombs skill XP table + dungeon stat boost
   - 3.4 Classes (Healer, Mage, Berserk, Archer, Tank) + class XP
   - 3.5 In-dungeon rules (mana, speed, potions, deaths, ghosts, revives)
   - 3.6 Map generation (grid, room shapes, doors, keys, algorithm)
   - 3.7 Room types (start, normal, puzzle, trap, miniboss, fairy, blood, rare, crypts)
   - 3.8 Secrets
   - 3.9 Puzzles — every puzzle and its solver logic
   - 3.10 Mobs (star mobs, minibosses, blood mobs, Mimic, Prince)
   - 3.11 Blessings
   - 3.12 Score system (S+ formula)
   - 3.13 Bosses Floor by floor (Entrance, F1–F7, M1–M7)
   - 3.14 Reward chests and loot
   - 3.15 Essence, Dungeon Stars, Master Stars, dungeonizing
   - 3.16 Catacombs XP per run, daily bonus, Dungeon RNG meter
4. Kuudra (Crimson Isle)
5. How it connects
6. Implementation notes (state machines, instancing, party logic, what the player sees)
7. Progression placement
8. Open questions / least-sure list

---

# 1. Overview

SkyBlock's endgame combat has three pillars. They are deliberately different *shapes* of content:

| Pillar | Shape | Group size | Instance? | Entry cost | What you farm | Key progression currency |
|---|---|---|---|---|---|---|
| **Slayers** | Spawn-on-demand boss in the open world | Solo (others can help; lootshare) | No — shared world zone | Coins per quest (2k–150k), Motes in the Rift | Boss drops, slayer XP, combat XP | Slayer XP (per slayer type, levels 1–9; Vampire 1–5) |
| **Dungeons (Catacombs)** | Procedurally generated room grid → boss arena | 1–5 (normally 5) | Yes — private instance per party | Free to enter; coins to open reward chests | Chest loot, essence, Catacombs/class XP | Catacombs XP (1–50+), class XP (5 classes, 1–50) |
| **Kuudra** | Fixed arena, wave defense + objectives + boss | 4 (party) | Yes — private instance | Free to enter; **Kuudra Key** (200k–3M + mats) to open paid chest | Armor/attributes, Crimson Essence, Kuudra Teeth, books | Faction reputation gates tiers |

Design pattern shared by all three:
1. **Pay or qualify to fight** (quest cost / floor requirement / reputation).
2. **A boss whose mechanics, not raw HP, are the challenge** (hit shields, attunements, puzzles, phases).
3. **A guaranteed "currency" drop + weighted rare-drop table** (Revenant Flesh / Undead+Wither Essence / Crimson Essence).
4. **A pity system** (RNG Meter for slayers and dungeons; key/chest rerolls for Kuudra).
5. **Level gates on crafting the gear made from the drops** (slayer level → recipes; Catacombs level → dungeon item requirements).

---

# 2. Slayers

## 2.1 System flow

**NPC:** *Maddox the Slayer*, in the Hub's Tavern/Slayer area (a "batphone" item lets you reach him remotely, §2.13). Vampire Slayer quests are started inside **The Rift** and paid in **Motes** (Rift currency).

**Flow of one quest:**

| Step | Rule |
|---|---|
| 1. Start | Player opens Maddox menu → picks slayer type → picks tier. Coins (or Motes) are deducted immediately. One active quest per player. |
| 2. Fill the bar | Kill mobs **of that slayer's family** (zombies for Revenant, spiders for Tarantula, wolves for Sven, endermen for Voidgloom, blazes/nether mobs for Inferno; Rift vampire area for Riftstalker). Each kill adds the mob's **Combat XP** to the quest bar. The bar target is the "Combat XP to spawn" number per tier (§2.3). Only kills in eligible areas count (Revenant: Graveyard/Crypts/any zombie; Tarantula: Spider's Den/any spider; Sven: Howling Cave/Ruins wolves; Voidgloom: The End; Inferno: Crimson Isle Smoldering Tomb/Burning Desert; Riftstalker: Stillgore Château, Rift). |
| 3. Spawn | When the bar fills, the boss spawns with a short animation **on the player** (near the last kill). The boss is **owned** by the quest player — its nametag shows "Spawned by: <name>". |
| 4. Fight | **3:00 time limit** (the boss despawns and the quest fails if not killed). Tier V Revenant / Tarantula likewise 3:00 (?). Riftstalker: 4:00 (?). |
| 5. Win | Boss drops loot (owner gets full table; helpers get **lootshare** — a reduced roll, §2.11). Player gains Slayer XP for that slayer type, plus Combat XP (the "+X Combat XP" listed per tier is the boss kill itself). |
| 6. Claim | Return to Maddox (or use Batphone/auto-slayer) to "claim" and start the next quest. Claiming is when Slayer XP is credited and level-ups are announced. |
| 7. Fail | Time out or death → quest failed, **no refund**, no slayer XP. |

**After-kill heal [W]:** after defeating a boss the player regenerates health for a few seconds: heal/sec scales by **tier (40 → 200 HP/s)**, duration scales by **the player's slayer level (6 → 10 s for level 0 → 9)**.

**Bits per kill [W]:** T1 = 2, T2 = 5, T3 = 8, T4 = 12, T5 = 15 bits (if the player has a Booster Cookie active).

**Kill credit:** only the owner completes the quest. Other players can damage the boss; they may receive a **lootshare** roll if they dealt ≥ a threshold of damage (?).

## 2.2 Unlock chain and per-tier requirements

| Slayer | Boss | Unlocked by [W] |
|---|---|---|
| Zombie | Revenant Horror | Default |
| Spider | Tarantula Broodfather | Defeat a **Tier II Revenant Horror** |
| Wolf | Sven Packmaster | Defeat a **Tier II Tarantula Broodfather** |
| Enderman | Voidgloom Seraph | Defeat a **Tier IV Sven Packmaster** |
| Blaze | Inferno Demonlord | Defeat a **Tier III Voidgloom Seraph** |
| Vampire | Riftstalker Bloodfiend | "Secure the Globulate Timecharm" (Rift progression); tier list on the wiki also shows **Sven Packmaster III** as requirement for T1 [W] |

**Per-tier gates (Revenant, confirmed [W]; assume the same for Tarantula/Sven (?)):**

| Tier | Requirement |
|---|---|
| I | none |
| II | Combat V |
| III | Combat X |
| IV | Combat XV |
| V | Combat XXV **and** that slayer's level VII |

Voidgloom/Inferno tiers are gated by having beaten the previous tier [W]. Riftstalker: T2 = T1 done, T3 = T2 done, T4 = T3 + Combat XV, T5 = T4 + Combat XXV [W].

## 2.3 Master table — cost, spawn requirement, rewards

| Slayer | Tier | Boss level | Health | DPS | Quest cost | Combat XP to spawn / combat XP reward* | Slayer XP |
|---|---|---|---|---|---|---|---|
| Revenant | I | 10 | 500 | 15 | 2,000 | 150 | 5 |
| Revenant | II | 70 | 20,000 | 25 [W] (50 older) | 7,500 | 1,440 | 25 |
| Revenant | III | 310 | 400,000 | 120 [W] | 20,000 | 2,400 | 100 |
| Revenant | IV | 610 | 1,500,000 | 400 [W] | 50,000 | 4,800 | 500 |
| Revenant | V (Atoned Horror) | 1,580 | 10,000,000 | 2,400 | 100,000 | 6,000 | 1,500 |
| Tarantula | I | 10 | 1,000 [W] (750 older) | 35 | 2,000 | 250 | 5 |
| Tarantula | II | 80 | 30,000 | 55 | 7,500 | 650 | 25 |
| Tarantula | III | 470 | 900,000 | 260 | 20,000 | 1,500 | 100 |
| Tarantula | IV | 770 | 2,400,000 | 530 | 50,000 | 3,000 | 500 |
| Tarantula | V (+Conjoined Brood) | 1,580 / 2,230 | 10M + 20M (=30M total) | 2,200 / 4,400 | 100,000 | 10,000 | 1,500 |
| Sven | I | 20 | 2,000 | 60 | 2,000 | 250 | 5 |
| Sven | II | 100 | 40,000 | 80 (+10 true) | 7,500 | 650 | 25 |
| Sven | III | 430 | 750,000 | 180 (+50 true) | 20,000 | 1,500 | 100 |
| Sven | IV | 700 | 2,000,000 | 440 (+200 true) | 50,000 | 3,000 | 500 |
| Voidgloom | I | 270 | 300,000 | 1,200 (older) / 800 [W] | 2,000 | 2,750 | 5 |
| Voidgloom | II | 1,730 | 12,000,000 | 5,000 / 2,720 [W] | 7,500 | 6,600 | 25 |
| Voidgloom | III | 3,530 | 50,000,000 | 12,000 / 7,650 [W] | 20,000 | 11,000 | 100 |
| Voidgloom | IV | 7,240 | 210,000,000 | 21,000 / 11,000 [W] | 50,000 | 22,000 | 500 |
| Inferno | I | (?) | 2,500,000 | 45/65/115 true (see §2.9) | 10,000 | 6,000 | 5 |
| Inferno | II | (?) | 10,000,000 | 65/130/190 true | 25,000 | 14,400 | 25 |
| Inferno | III | (?) | 45,000,000 | 80/160/320 true | 60,000 | 18,000 | 100 |
| Inferno | IV | (?) | 150,000,000 | 385/575/955 true | 150,000 | 36,000 | 500 |
| Riftstalker | I | (?) | 625 (Rift ❤-scale) | 0.5 ❤/s | 2,000 Motes | n/a (Rift) | 10 |
| Riftstalker | II | (?) | 1,100 | 1 ❤/s | 4,000 Motes | n/a | 25 |
| Riftstalker | III | (?) | 1,800 | 1 ❤/s | 5,000 Motes | n/a | 60 |
| Riftstalker | IV | (?) | 2,400 | 1 ❤/s | 7,000 Motes | n/a | 120 |
| Riftstalker | V | (?) | 3,000 | 1.5 ❤/s | 10,000 Motes | n/a | 150 |

\* The wiki lists "+X Combat XP" per tier; in the live game this number is both the bar the player must fill and the combat XP granted (?). **Clone recommendation:** use it as the spawn-bar target, and grant the same amount on boss kill.

> Two Voidgloom damage columns: the older community values (1,200/5,000/12,000/21,000) and the current
> wiki's (800/2,720/7,650/11,000). The wiki also notes "+2,000 (or +3,000) DPS while the hit shield is up"
> on T3–T4, which may explain the older, higher figures (base + shield bonus). Use the wiki values as base
> damage and add the shield bonus while shielded.

**Rift health scale:** the Rift uses its own tiny health pool (players have ~a handful of ❤ hearts; "Rift Time" is the life-timer). Riftstalker's HP (625–3,000) and damage in hearts (❤) are in that system, not overworld HP.

## 2.4 Slayer level XP tables

Values are **cumulative XP required to reach the level**.

| Level | Zombie [W] | Spider | Wolf [W] | Enderman [W] | Blaze | Vampire [W] |
|---|---|---|---|---|---|---|
| 1 | 5 | 5 (wiki page shows 10 (?)) | 10 | 10 | 10 | 20 |
| 2 | 15 | 25 | 30 | 30 | 30 | 75 |
| 3 | 200 | 200 | 250 | 250 | 250 | 240 |
| 4 | 1,000 | 1,000 | 1,500 | 1,500 | 1,500 | 840 |
| 5 | 5,000 | 5,000 | 5,000 | 5,000 | 5,000 | 2,400 (max) |
| 6 | 20,000 | 20,000 | 20,000 | 20,000 | 20,000 | — |
| 7 | 100,000 | 100,000 | 100,000 | 100,000 | 100,000 | — |
| 8 | 400,000 | 400,000 | 400,000 | 400,000 | 400,000 | — |
| 9 | 1,000,000 | 1,000,000 | 1,000,000 | 1,000,000 | 1,000,000 | — |

Blaze follows the Wolf/Enderman curve (?) — one summary claimed Blaze = Vampire curve; that is almost certainly an extraction error since Blaze has 9 levels.

**Level names (Zombie [W]):** Noob, Novice, Skilled, Destroyer, Bulldozer, Savage, Deathripper, Necromancer, Grim Reaper. Other slayers share levels 1–6 names and have unique 7–9 names (Spider: Pest Control Genius, Tarantula Exterminator, Spider Annihilator; Wolf: King Hunter, Pack Leader, Alpha Wolf; Enderman: Voidwracker, Tall Purple Hater, Definition of End; Blaze: Hellsplitter, Demon Eater, Gabagool King; Vampire L3–5: Alum, Heartpiercer, Lord of Night).

**SkyBlock XP per slayer level [W]:** 15, 25, 35, 50, 75, 100, 125, 150, 150 (Vampire: 15, 25, 35, 50, 75).

**Bosses to max (sanity check for pacing):** Zombie 9 = 1,000,000 XP = 2,000 T4 kills, or ~667 T5 kills. Enderman 9 = 2,000 T4 kills. With Aatrox's +25% this drops to 1,600.

## 2.5 Revenant Horror (Zombie Slayer)

### Stats — see §2.3. Arena: any zombie area; Graveyard/Coal Mine crypts in the Hub are typical.

### Abilities [W]

| Ability | Tier | Effect |
|---|---|---|
| **Life Drain** | I+ | Every few seconds (~every 3–5 s (?)) drains health from the player and heals the boss by the same amount. |
| **Pestilence** | II+ | AoE damage every second around the boss; **shreds player armor by 25%** (−25% Defense) while active. |
| **Enrage** | III+ | After **40 s** of fight the boss enters "Mad"; **5 s** later "Enraged" for **12 s** — faster movement and greatly increased damage (×~2 (?)). Cycle repeats. |
| (none new) | IV | Same kit as III, bigger numbers. |
| **Redemption** | V (Atoned Horror) | Natural regen **0.1% of max HP per second** (10,000/s at 10M). Throws **TNT at marked circles** on the ground near players; a TNT hitting a player **heals the boss 500,000**. Below **1/3 HP** it throws TNT twice as fast. |
| **Thermonuclear** | V | After **30 s** the boss stops moving; an **expanding bedrock circle** forms; after a **5 s countdown** a lightning barrage hits everything within **7 blocks** — **ignores protective abilities** (no Wither Shield / Bonzo-mask style negation). Player must leave the 7-block radius. |
| Immunities | V | Immune to **ability damage** (mage builds can't cheese it) and certain items. |

### Loot table [W] (weights; higher = more common; see §2.11 for converting weight → %)

| Item | Min Zombie lvl | T I | T II | T III | T IV | T V |
|---|---|---|---|---|---|---|
| Revenant Flesh | 0 | 1–3 (100%) | 9–18 (100%) | 30–50 (100%) | 50–64 (100%) | 63–64 (100%) |
| Foul Flesh | 0 | — | 1 · w2000 | 1–2 · w2000 | 2–3 · w2000 | 3–4 · w2000 |
| Undead Catalyst | 3 | — | w250 | w250 | w75 | w250 |
| Revenant Catalyst | 6 | — | w125 | w125 | w125 | w125 |
| Pestilence Rune I | 2 | — | w83 | w333 | w833 | w833 |
| Revenant Shard (attribute/Hunting shard) | 0 | — | — | w100 | w150 | w200 |
| Enchanted Book (Smite VI) | 4 | — | — | w50 | w50 | w50 |
| Beheaded Horror | 5 | — | — | w10 | w20 | w20 |
| Snake Rune I | 6 | — | — | — | w20 | w20 |
| Festering Maggot | 6 | — | — | — | w10 | w20 |
| Scythe Blade | 7 | — | — | — | w7 | w15 |
| Paragon Shard | 5 | — | — | — | w0.0025 | w0.005 |
| Revenant Viscera | 7 | — | — | — | — | 1–2 · w2000 |
| Shredded Sinew (formerly "Shard of the Shredded") | 7 | — | — | — | — | w8 |
| Severed Hand (Smite VII source) | 7 | — | — | — | — | w7 |
| Warden Heart | 7 | — | — | — | — | w2 |
| Matcha Dye | 0 | 1e-7 % | 4e-7 % | 1e-6 % | 2e-6 % | 4e-6 % |

Rarity labels used by Hypixel for display: **Guaranteed, Occasional, Rare, Extraordinary, Pray RNGesus, RNGesus Incarnate** — derived from the computed chance (Occasional ≥ ~5%, Rare 1–5%, Extraordinary 0.1–1%, Pray RNGesus 0.01–0.1%, RNGesus Incarnate < 0.01% (?)).

### Zombie Slayer level rewards [W]

| Lvl | Stat reward | Recipes / unlocks |
|---|---|---|
| 1 | +2 Health | Wand of Healing?, Zombie Ring (?), Revenant Viscera? |
| 2 | +2 Health | Zombie Ring (?) |
| 3 | +3 Health | **Revenant Falchion**, Undead Catalyst drop |
| 4 | +3 Health | Revenant Armor pieces (?), Smite VI drop |
| 5 | +4 Health | **Revenant Minion I**, Revenant Armor full, Beheaded Horror drop |
| 6 | +4 Health | **Reaper Falchion**, Reaper Mask (needs Cata 24), Revenant Catalyst drop, Zombie Artifact |
| 7 | +5 Health | **Reaper Scythe**, **Axe of the Shredded**, Reaper Armor (?), Scythe Blade drop, unlocks Tier V |
| 8 | +5 Health, +30 Health Regen | **Warden Helmet** (Warden Heart), Bartender: Zombie Brain Mixin |
| 9 | +6 Health | Reaper Orb, Halberd of the Shredded (?) |

Total Zombie slayer stat: **+34 Health** (+30 Health Regen at 8).

## 2.6 Tarantula Broodfather (Spider Slayer)

### Abilities [W]

| Ability | Tier | Effect |
|---|---|---|
| **Backstab** | I+ | +20% damage when hitting the player from behind (T5: **+80%**). |
| **Noxious Paralysis** | II+ | Hits apply −15% Vitality, −10% Speed (T5: **−75% Vitality and −75% Speed, disables Absorption from all sources**). |
| **Web of Lies** | III+ | At **66% and 33% HP** the boss becomes invulnerable and spawns **2 egg sacs (3 HP each)** — must be hit (each hit = 1 HP regardless of damage) to resume. T5: **4 egg sacs, 9 HP each**. |
| **Bat Barrage** | IV+ | Summons bats; each active bat deals **0.1% of the player's max HP** per tick (T5: **0.2% per second per bat**). |
| Jump/leap | all | Tarantula leaps at the player; it gets "stuck" if the player stands on high blocks (classic cheese, removed by leap targeting (?)). |
| **Till Death Do Us Part** | V | On "death" the Broodfather calls the **Primordial Broodmother** and fuses into the **Conjoined Brood**: **20M HP, 4,400 DPS, level 2,230**; keeps only passives (Backstab, Noxious Paralysis). Total effective HP **30M**. |

T5 requirements [W]: Combat XXV, Spider Slayer VII, 100,000 coins, rewards +10,000 Combat XP.

### Loot table [W] (displayed % at 0 Magic Find)

| Item | T I | T II | T III | T IV | T V |
|---|---|---|---|---|---|
| Tarantula Web | 1–3 (100%) | 9–18 | 16–32 | 32–48 | 64 |
| Tarantula Silk | — | — | — | — | 14.13% |
| Toxic Arrow Poison | 4–8 · 15.25% | 6–8 · 15.09% | 16–32 · 14.85% | 32–48 · 14.97% | 64 · 12.72% |
| Spider Catalyst | — | 1.05% | 2.06% | 0.62% | 0.18% |
| Bite Rune I | — | 0.69% | 2.67% | 6.45% | 5.52% |
| Tarantula Catalyst | — | — | 0.25% | 0.37% | 0.42% |
| Bane of Arthropods VI book | — | — | 0.21% | 0.42% | 0.35% |
| Fly Swatter | — | — | 0.082% | 0.17% | 0.21% |
| Tarantula Talisman | — | — | 0.082% | 0.17% | 0.21% |
| Vial of Venom | — | — | — | 0.083% | 0.14% |
| Digested Mosquito | — | — | — | 0.058% | 0.071% |
| Darkness Within Rune | — | — | — | 0.39% | 0.66% |
| Primordial Shard | — | — | — | — | 0.71% |
| Shriveled Wasp | — | — | — | — | 0.14% |
| Ensnared Snail | — | — | — | — | 0.042% |
| Primordial Eye | — | — | — | — | 0.014% |
| Paragon Shard | — | — | — | 0.25% | 0.50% |
| Brick Red Dye | 0.00001% | 0.00004% | 0.0001% | 0.0002% | 0.0004% |

### Spider Slayer level rewards [W]

| Lvl | Stat | Unlocks |
|---|---|---|
| 1 | +1 Crit Damage | Spider Ring; Tarantula Web & Toxic Arrow Poison drops |
| 2 | +1 CD | Recluse Fang, Tarantula Silk; Spider Catalyst drop |
| 3 | +1 CD | Medium Slayer Sack |
| 4 | +1 CD | **Tarantula Armor** (full set), Tarantula Fang; Bane VI + Tarantula Catalyst drops |
| 5 | +2 CD | **Tarantula Minion I**, Spider Shortbow (?); Fly Swatter drop |
| 6 | +2 CD | **Scorpion Foil**, Spider Artifact, Flycatcher; Tarantula Talisman drop |
| 7 | +2 CD + Survivor Cube | **Mosquito Shortbow**, Mosquito Pet, Tarantula Ring, **Primordial Armor**, Shriveled Bracelet; T5 drops |
| 8 | +3 CD, +5 Alchemy Wisdom | Sting, The Primordial, Shriveled Cornea, Spider Egg Mixin |
| 9 | +3 CD | Tarantula Minion XII Upgrade Stone; Bartender access |

Total **+16 Crit Damage**.

## 2.7 Sven Packmaster (Wolf Slayer)

### Abilities

| Ability | Tier | Effect |
|---|---|---|
| **Agile** | I+ | Small, fast wolf; high movement speed, hard to hit. |
| **True Damage** | II+ | Deals extra true damage per second (10 / 50 / 200 at T2 / T3 / T4) that ignores Defense. |
| **Call the Pups!** | III+ | At **50% HP** the boss becomes **invulnerable and immobile** and spawns **5 Pups sequentially**, each with **10% of the boss's max HP**; boss resumes when pups die. |
| Regen while pups up | III+ | Boss regenerates while pups are alive (?) |

Known cheese [W]: "Fighting in water prevents damage taken" — pups drown. **Clone note:** decide deliberately whether water should neutralise the fight.

### Loot table [W] (quantities; chances where known)

| Item | T I | T II | T III | T IV |
|---|---|---|---|---|
| Wolf Tooth | 1–3 (100%) | 9–18 | 30–50 | 50–64 |
| Hamster Wheel | — | 1 · ~16–17% | 2–4 | 4–5 |
| Spirit Rune I | 1 (rare) | 1 | 1 | — |
| Critical VI book | — | — | — | 1 (extraordinary) |
| Furball | — | — | 1 | 1 (extraordinary) |
| Red Claw Egg | — | — | — | 1 (legendary ~0.1% (?)) |
| Couture Rune I | — | — | — | 1 (~0.1% (?)) |
| Grizzly Bait / Overflux Capacitor | — | — | — | 1 (Wolf 7, ~0.05% (?)) |
| Paragon Shard | — | — | — | 1 (very rare) |
| Celeste Dye | 0.00001% | 0.00004% | 0.0001% | 0.0002% |

### Wolf Slayer level rewards [W]

| Lvl | Stat | Unlocks |
|---|---|---|
| 1 | +1 Speed | Red Claw Talisman; Wolf Tooth / Hamster Wheel drops |
| 2 | +2 Health | Radiant Power Orb, Golden Tooth, Large Slayer Sack; Spirit Rune drop |
| 3 | +1 Speed | **Maddox Batphone** (given + recipe), **Shaman Sword** |
| 4 | +2 Health | **Mastiff Armor**, Critical VI drop; Furball drop |
| 5 | +1 Crit Damage | Red Claw Ring, Edible Mace, Weird Tuba, Red Claw Artifact, Weirder Tuba; Red Claw Egg drop |
| 6 | +3 Health | **Mana Flux Power Orb**, **Pooch Sword**, **Armor of the Pack**; Couture Rune drop |
| 7 | +2 Crit Damage | Hunter Talisman, **Overflux Power Orb**; Overflux Capacitor & Grizzly Salmon drops |
| 8 | +1 Speed; perk "lose 5% less coins on death" | Hunter Ring, **Plasmaflux Power Orb**, Wolf Fur Mixin |
| 9 | +5 Health | — |

## 2.8 Voidgloom Seraph (Enderman Slayer)

Arena: The End (Void Sepulture / Zealot Bruiser Hideout). The boss is a giant enderman holding a block.

### Abilities [W]

| Ability | Tier | Effect |
|---|---|---|
| **Malevolent Hitshield** | I+ | Shield that absorbs **hits, not damage**: **15 / 30 / 60 / 100 hits** (T1–T4). Each hit removes 1 regardless of damage. The shield regenerates at HP thresholds (every 1/3 of HP (?)). While the shield is **NOT** active the boss takes **−75% Ferocity** (anti-ferocity-melee). While shielded on T3–T4, boss gains **+2,000 DPS** (another source: +3,000 every 3 s). |
| **Dissonance** | II+ | Boss teleports near players and deals **40% of base damage in a 6.5-block AoE**. |
| **Yang Glyphs** | II+ (wiki) / III+ (older) | Boss throws a **beacon** to a random nearby spot. It must be **broken (mined/clicked) within 5 s** or it detonates, dealing **max-HP-% true damage** to all players (practically a wipe). |
| **Nukekubi Fixations** | III+ | At **1/3 HP** spawns floating **heads** (skulls) that fixate on the player. Each deals **600 DPS (T3) / 1,500 DPS (T4)**, damage **doubling for each additional head alive**. Must be shot/hit down (they take few hits). |
| **Broken Heart Radiation** | IV | Boss becomes invulnerable and casts **12 rotating beams** around itself; contact deals **800 true damage** and **−12 Vitality for 90 s**. Player must stay in the gaps / outside. |
| Teleport | all | Teleports around like an enderman; arrows are deflected? No — Voidgloom **can** be hit by arrows (unlike vanilla endermen). |

### Loot (T IV percentages [W]; others (?))

| Item | Min Enderman lvl | Tiers | Notes / T4 chance |
|---|---|---|---|
| Null Sphere | 0 | all | Guaranteed: T1 2–3, T2 14–24, T3 60–80, T4 **105–155** |
| Twilight Arrow Poison | 1 | all | Occasional ~15% (?) |
| Endersnake Rune I | 1 | II+ | Rare (?) |
| Summoning Eye | 2 | II+ | ~1% (?) — same item Special Zealots drop; placed at the End altar to summon the Ender Dragon |
| Mana Steal I book | 3 | III+ | **4.23%** |
| Transmission Tuner | 3 | III+ | **2.12%** |
| Null Atom | 4 | III+ | **4.94%** |
| Hazmat Enderman (pet skin/ helmet?) | 5 | IV | ~0.1% (?) |
| Pocket Espresso Machine | 5 | IV | ~0.1% (?) |
| Smarty Pants I book | 5 | IV | ~0.5% (?) |
| End Rune I | 5 | IV | ~0.3% (?) |
| Handy Blood Chalice | 6 | IV | ~0.2% (?) |
| Sinful Dice | 6 | IV | **0.46%** |
| Exceedingly Rare Ender Artifact Upgrader | 7 | IV | ~0.02% (?) |
| Etherwarp Merger | 7 | IV | **0.42%** |
| Void Conqueror Enderman Skin | 7 | IV | ~0.03% (?) |
| **Judgement Core** | 7 | IV | **0.056%** — Terminator material |
| Enchant Rune I | 7 | IV | ~0.03% (?) |
| Ender Slayer VII book | 7 | IV | ~0.03% (?) |
| Byzantium Dye | 0 | all | 0.0002% |

### Enderman Slayer level rewards [W]

| Lvl | Stat | Unlocks |
|---|---|---|
| 1 | +1 Health | **Voidwalker Katana**, Null Ovoid, Soulflow, Soulflow Pile, Lesser Soulflow Engine |
| 2 | +2 Intelligence | — |
| 3 | +2 Health | **Final Destination Armor**, Soul Esoward, **Voidedge Katana** |
| 4 | +2 Intelligence | Voidling Minion I, Travel Scroll to Void Sepulture |
| 5 | +3 Health | Soulflow Battery/Engine, **Vorpal Katana**, **Gloomlock Grimoire**, **Juju Shortbow** |
| 6 | +5 Intelligence | Gyrokinetic Wand, Aspect of the Void, **Atomsplit Katana**, Sinseeker Scythe |
| 7 | +4 Health | Soulflow Supercell, **Etherwarp Conduit**, Ender Relic, **Terminator** |
| 8 | +4 Intelligence, **+15% RNGesus (rare) enchant chance** | Bartender: End Portal Fumes |
| 9 | +5 Health, +5 Tracking (?) | — |

## 2.9 Inferno Demonlord (Blaze Slayer)

Arena: Crimson Isle — Smoldering Tomb / Burning Desert. Spawn by killing blazes and other nether mobs.

### Requirements & rewards [W]

| Tier | Cost | Combat XP | Requirement | Slayer XP |
|---|---|---|---|---|
| I | 10,000 | 6,000 | Voidgloom T3 defeated | 5 |
| II | 25,000 | 14,400 | Inferno T1 | 25 |
| III | 60,000 | 18,000 | Inferno T2 | 100 |
| IV | 150,000 | 36,000 | Inferno T3 | 500 |

### Health and damage [W]

| Tier | HP | Immolate true dmg at 100% / 66% / 33% HP |
|---|---|---|
| I | 2.5M | 45 / 65 / 115 |
| II | 10M | 65 / 130 / 190 |
| III | 45M | 80 / 160 / 320 |
| IV | 150M | 385 / 575 / 955 |

### Demons spawned by Demonsplit [W]

| Demon | HP (T1/T2/T3/T4) | Damage (T1–T4) | Attunements | Signature ability |
|---|---|---|---|---|
| **Quazii** (wither skeleton) | 500k / 1.75M / 5M / 10M | 2,500 / 8,000 / 12,000 / 25,000 | Spirit ↔ Crystal | **ROFLcopter** beam: 1,500 true dmg, heals 20% |
| **Typhoeus** (pigman) | 500k / 1.75M / 5M / 10M | 2,500 / 8,000 / 12,000 / 25,000 | Ashen ↔ Auric | **Trail of Fire**: 1,350 true dmg on contact |

### Demonsplit thresholds [W]

| Tier | First split | Second split |
|---|---|---|
| I | 50% (1.25M) | — |
| II | 50% (5M) | — |
| III | 66.7% (30M) | 33.3% (15M) |
| IV | 66.7% (100M) | 33.3% (50M) |

During a split the main boss is invulnerable until both demons die.

### Abilities [W]

| Ability | Tier | Effect |
|---|---|---|
| **Immolate** | I+ | Constant true-damage aura scaling with boss HP% (table above). |
| **Demonsplit** | I+ | Spawns Quazii + Typhoeus; boss invulnerable until both are dead. |
| **Hellion Shield** | II+ | **99% damage reduction** unless hit with a **Blaze Slayer dagger whose current attunement matches** the shield's colour. Shield attunement **switches every 8 hits**, cycling **Ashen → Spirit → Auric → Crystal** (boss); Quazii only Spirit/Crystal; Typhoeus only Ashen/Auric. Wrong-attunement hits do 1% dmg. |
| **Fire Pillars** | II+ | Boss spawns a pillar that must take **8 hits within 7 s** or it explodes — **instant death** (party wipe in radius). |
| **DDR Apocalypse** (Fire Pits) | III+ | Lava/fire pits appear in patterns; standing in one deals **950 (T3) / 2,865 (T4) true damage per second**. |

### Daggers and attunement switching

Each dagger has **two attunements** toggled by **right-click (swap)**:

| Dagger line | Attunements | Tier progression |
|---|---|---|
| Firedust → Kindlebane → Pyrochaos | **Ashen / Auric** (?) (wiki lists Ashen) | Blaze 2 → 4 → 6 |
| Twilight → Mawdredge → Deathripper | **Spirit / Crystal** (?) (wiki lists Spirit) | Blaze 2 → 4 → 6 |

Gameplay: the player carries one dagger of each line (so all 4 attunements are reachable), watches the shield colour, swaps to the matching dagger/attunement, lands 8 hits, swaps again. **Clone design:** this is the "hit the right colour" rhythm mechanic; render the shield colour on the boss nametag AND a HUD indicator.

### Loot table [W]

| Item | Min lvl | Qty T1/T2/T3/T4 | Chance T1 / T2 / T3 / T4 |
|---|---|---|---|
| Derelict Ashe | 0 | 6–8 / 12–22 / 60–80 / 110–155 | 100% all |
| Enchanted Blaze Powder | 0 | 1 / 2–3 / 4–8 / 16–40 | 13.04 / 9.26 / 8.70 / 7.91% |
| Lavatears Rune I | 1 | 1 | 1.15 / 2.31 / — / — |
| Wisp's Ice-Flavored Water I | 1 | 1 / 2 / 5 | 3.09 / 2.90 / 2.64% |
| Bundle of Magma Arrows | 1 | 1 | — / 9.26 / 8.70 / 7.91% |
| Mana Disintegrator | 2 | 1 | 4.32 / 4.06 / 3.69% |
| Scorched Books | 2 | 1 | 2.47 / 2.32 / 2.11% |
| Inferno Demonlord Shard | 3 | 1 | 1.54 / 2.90 / 5.27% |
| Kelvin Inverter | 3 | 1 | 3.09 / 2.90 / 2.64% |
| Blaze Rod / Glowstone / Magma Cream / Nether Wart Distillate | 3 | 4 / 10–14 / 24–32 | one of the 5 distillates rolls together (shared slot) |
| Gabagool Distillate | 3 | 4 / 10–14 / 24–32 | 5.19 / 4.87 / 4.43% |
| Scorched Power Crystal | 4 | 1 | 3.48 / 3.16% |
| Archfiend Dice | 4 | 1 | 1.16 / 1.05% |
| Fire Aspect III book | 5 | 1 | 1.32% |
| Fiery Burst Rune I | 5 | 1 | 0.21% |
| Flawed Opal Gemstone | 5 | 240–400 | 2.90% |
| Duplex I book | 6 | 1 | 1.84% |
| High Class Archfiend Dice | 7 | 1 | 0.26% |
| Wilson's Engineering Plans | 7 | 1 | 0.09% |
| Subzero Inverter | 7 | 1 | 0.09% |
| Paragon Shard | 5 | 1 | T3–T4: 1/400 |
| Flame Dye | 1 | 1 | 1/10M, 1/2.5M, 1/1M, 1/500k |

(The wiki table truncates T4 percentages for many rows; scale T4 ≈ T3 × 0.9.)

**Archfiend Dice:** a gamble item (roll d6; 6 = big coin payout, 7 on High Class dice). **Note:** the owner's project rule is *no gambling* — for the clone, replace dice with a non-gambling reward (?).

### Blaze Slayer level rewards [W]

| Lvl | Stat | Unlocks |
|---|---|---|
| 1 | +3 Health | Droplet Wisp pet, Bundle of Magma Arrows, Lavatears Rune |
| 2 | +1 Strength | Molten Powder, **Firedust Dagger**, **Twilight Dagger**, Mana Disintegrator, Scorched Books |
| 3 | +4 Health | Wisp Upgrade Stone, Burststopper Talisman, Warning Flare, Inferno Minion I, Kelvin Inverter, Distillates |
| 4 | +1 True Defense | **Kindlebane Dagger**, **Mawdredge Dagger**, Demonslayer Gauntlet, Teleporter Pill, Travel Scroll to Smoldering Tomb, Scorched Power Crystal, Archfiend Dice |
| 5 | +5 Health | Wisp Upgrade Stone, Destruction Cloak, Tactical Insertion, Fire Aspect III, Fiery Burst Rune, Flawed Opal |
| 6 | +2 Strength | **Pyrochaos Dagger**, **Deathripper Dagger**, Blazetekk Ham Radio, Alert Flare, Duplex |
| 7 | +6 Health | Burststopper Artifact, Wisp Upgrade Stone, Annihilation Cloak, **SOS Flare**, High Class Archfiend Dice, Wilson's Engineering Plans, Subzero Inverter |
| 8 | +2 True Defense; +20% pet XP feeding Wisps Gabagool | Bartender: Gabagoey Mixin |
| 9 | +7 Health; −10 bits on Inferno Fuel Blocks | — |

## 2.10 Riftstalker Bloodfiend (Vampire Slayer)

Location: **The Rift → Stillgore Château**. Paid in **Motes**. Uses Rift health (❤). Unique in that its loot is mostly Rift items and enchanted-book bundles.

### Abilities [W]

| Ability | Tier | Effect |
|---|---|---|
| **Coagulation** | I+ | Drain every **1.5 s**: 0.75 / 1.5 / 1.5 / 1.5 / 2.25 ❤ per tick (T1–T5). |
| **Spectral Form** | I+ | Turns into a bat and teleports periodically or when far from the player. |
| **Mania** | I+ | Boss **invulnerable for 26 s**; a coloured ring appears; player must stand in the **safe coloured ring** or take 2 / 3 / 5 / 6 / 15 ❤. Triggers at tier-specific HP%. |
| **Twinclaws** | II+ | Frontal claw slam every **7 s**: 3 / 5 / 6 / 8 ❤ (T2–T5). Telegraphed; dodge to the side. |
| **Stonewrath** | II+ | Spawns **Clotgoyles** that reduce max health; a flying phase targets the player, then an explosive release destroys cadavers. |
| **Killer Springs** | III+ | A spring spawns on the player; must **click alternating buttons** to compress it within **15 s** or instant death. |
| **Impel** | III+ | On-screen command (**CLICK UP / CLICK DOWN / JUMP / SNEAK**) must be performed within **2.5 / 2 / 2 s** or take 4 / 5 / 20 ❤. |
| **Blood Ichor** | V | Spawns periodically; must be destroyed by **baiting Twinclaws into it** within **25 s**. |
| **Execute (Steak Stake)** | all | The boss cannot be killed by normal damage below a threshold (~20% HP (?)); the player must hit it with a **Steak Stake** (Super Sharp version for higher tiers) to finish. **Holy Ice** (Vampire 1) freezes/heals (?) |

### Loot [W]

| Item | Tier | Chance / qty |
|---|---|---|
| Coven Seal | all | Guaranteed, 8–10 (T1) up to 128 (T5) |
| Enchanted Book Bundle (Quantum) | II+ | 13.33% → 11.98% |
| Soultwist Rune I | II+ | 11.76% → 10.70% |
| Bubba Blister | III+ | 10% → 8.98% |
| Fang-tastic Chocolate Chip | III+ | 10% → 8.98% |
| Guardian Lucky Block | III+ | 6.25% → 5.99% |
| McGrubber's Burger | IV+ | 1.22% → 1.20% |
| Unfanged Vampire Part | IV+ | 1.22% → 1.20% |
| Enchanted Book Bundle (The One) | IV+ | rare (?) |
| Sangria Dye | all | 0.001% → 0.01% |

### Vampire Slayer level rewards [W]

| Lvl | XP | Stat | Unlocks |
|---|---|---|---|
| 1 | 20 | +20 Rift Time | Silver-Laced Karambit, Anti-Bite Scarf, Warm Wizard Face, Holy Ice |
| 2 | 75 | +30 Rift Time | **Steak Stake**, Juicy Healing Melon, Maddox Badphone (Rift batphone) |
| 3 | 240 | +45 Rift Time | Vampiric Timecharm, Bloodbadge, Leggings of the Coven, Perma-Jelled ... Gummy Polar Bear, Super Sharp 'n Stabby Steak Stake |
| 4 | 840 | +60 Rift Time | Silver-Twist Karambit, Luscious Healing Melon, Anti-Bite Scarf (Mythic), Warmer Wizard Face |
| 5 | 2,400 | +80 Rift Time | Lively Sepulture Chestplate, Vampire Dentist Relic |

## 2.11 Loot model — weights, Magic Find, lootshare

**Algorithm (recommended for clone; matches Hypixel's displayed behaviour):**

```
on boss kill (owner):
  for each GUARANTEED entry with minLevel <= playerSlayerLevel and tier in entry.tiers:
      give randint(qtyMin[tier], qtyMax[tier])
  # rare drops: Hypixel rolls a number of "drop rolls" per tier (T1=1? ... T4/T5 = more)  (?)
  eligible = [e for e in table if e is weighted and minLevel<=lvl and tier allowed]
  totalWeight = sum(e.weight[tier] for e in eligible)  + "nothing" weight (?)
  for roll in range(rollsPerTier[tier]):           # (?) e.g. T1:1 T2:1 T3:2 T4:3 T5:3
      pick e with probability e.weight/totalWeight
      # Magic Find only multiplies entries whose base chance < 5%  (Hypixel rule for "rare drops")
      apply RNG-meter multiplier if e is the selected meter item
      give e
```

- **Magic Find** (✯): Hypixel boosts rare drops (base chance < 5%) by `chance × (1 + MF/100)`. Slayer drops count as rare drops (they're affected by MF); guaranteed/occasional drops are not.
- **Displayed %**: the wiki percentages in §2.6/§2.9 already fold weights into a per-kill chance. The Revenant table is given in raw weights because that's how the wiki publishes it — convert with `weight / Σweights_eligible` and the clone should present the converted %.
- **Min level filter**: items below their min slayer level are removed from the pool *and the pool renormalises* — i.e. **levelling up actually dilutes the chance of common items**. This is why the wiki shows Undead Catalyst weight dropping at T4.
- **Lootshare:** helpers (non-owners) who deal ≥ some damage get one extra roll on a reduced "lootshare" pool, typically the guaranteed currency at a fraction (?) — Inferno's distillates are explicitly "Lootshare" items [W].

## 2.12 RNG Meter (slayer)

| Rule | Value |
|---|---|
| One meter per slayer type | yes |
| Player selects one target item (from that slayer's rare drops they are eligible for) | yes; selection is free; changing the selection **resets progress** (or keeps a partial %, depending on version) (?) |
| Meter fills with | **Slayer XP gained** from that slayer (after boosts) |
| Required meter XP per item | Scales with rarity — roughly `ceil( k / chance_at_highest_tier )`. Examples (?): Warden Heart ≈ 2,000,000+, Judgement Core ≈ 900,000+, Scythe Blade ≈ 200,000+, Sinful Dice ≈ 100,000 (?) |
| Effect while filling | The selected item's drop chance is multiplied by up to **×3 at 100%** (linear: `1 + 2 × progress`) (?) |
| When full | The **next boss** of a tier that can drop the item drops it guaranteed; meter resets to 0. |
| If item drops naturally | Meter resets to 0 (progress lost). |
| No item selected | XP still accumulates as "unassigned"? — Hypixel: XP is stored and applies when you select (?) |

Clone formula recommendation (data-driven, consistent): `requiredXP = round( slayerXP_T_max / p_T_max × 0.75 )` where `p` is the per-kill chance at the highest tier. For Judgement Core: 500 / 0.00056 × 0.75 ≈ **670,000**. Tune.

## 2.13 Maddox Batphone, Aatrox, Auto-Slayer, Slayer Sacks, Bartender

**Maddox Batphone [W]**
- Obtained at **Wolf Slayer III** (one given free) + recipe: **7 Enchanted Cobblestone, 1 Enchanted Iron Ingot, 1 Enchanted Redstone Dust**.
- Uncommon, Soulbound (co-op), not auctionable.
- Right-click: plays "Ring…" ×3, then a random success line with **[OPEN MENU]** (opens Maddox's menu remotely: start/claim quests).
- Cooldown: if called again too soon, random "no answer" joke line; third quick retry: "HEY IT'S NOT PICKING UP STOP TRYING!" (cooldown ≈ 20 s (?)).
- **No reception** in Dungeons and the Rift (Rift has its own *Maddox Badphone* at Vampire 2).

**Mayor Aatrox perks** (Mayor rotation — see doc 05):

| Perk | Effect |
|---|---|
| Slayer XP Buff | +25% Slayer XP |
| Pathfinder | Rare drops from slayer bosses are **2× more likely** (?) |
| SLASHED Pricing | Slayer quests cost **half** |
| (Paul's/Derpy's interplay) | Derpy: double slayer XP? (?) |

**Auto-Slayer** (?): a toggle unlocked at a slayer level (Zombie/Spider/etc 8 or via a perk) that automatically restarts the same quest after a kill, charging the cost from purse. Clone: unlock at **level 8 of that slayer**.

**Slayer Sacks:** Small (default (?)), Medium (Spider 3), Large (Wolf 2) — store slayer currencies (flesh, webs, teeth, null spheres, ashes) automatically.

**Bartender (Bar NPC):** at level 8–9 per slayer, mixins (potion-like consumables) become purchasable: Zombie Brain Mixin, Spider Egg Mixin, Wolf Fur Mixin, End Portal Fumes, Gabagoey Mixin.

## 2.14 Slayer-exclusive gear (key recipes)

Exact quantities are partly uncertain — treat starred quantities as defaults.

| Item | Slayer lvl | Type | Main recipe materials | Signature effect |
|---|---|---|---|---|
| Revenant Falchion | Zombie 3 | Sword | Revenant Flesh ×? + Enchanted Diamond (?) | +150% dmg vs zombies (?) |
| Reaper Falchion | Zombie 6 | Sword | Revenant Falchion + Revenant Viscera (?) | Bonus vs zombies, heal on hit |
| Reaper Scythe | Zombie 7 | Sword | Scythe Blade + Revenant Viscera + ... | Summon zombie minions (ability) |
| Axe of the Shredded | Zombie 7 | Sword | Shredded Sinew/Shard of the Shredded + Reaper Falchion (?) | Throw axe, heals per hit |
| Revenant Armor | Zombie 5 | Armor | Revenant Flesh + Enchanted Diamond (?) | Defense vs zombies |
| Reaper Mask | Zombie 6 + Cata 24 | Helmet | Beheaded Horror + Revenant Viscera (?) | Heal on kill in dungeons |
| Reaper Armor | Zombie 6/7 | Armor | Revenant Armor + Revenant Catalyst + viscera | Toggleable red/black mode, +stats |
| Warden Helmet | Zombie 7/8 | Helmet | **Warden Heart** + Revenant Viscera | Huge HP/def, −50% speed cap |
| Tarantula Armor | Spider 4 | Armor | Tarantula Silk (Tarantula Web→Silk) | crit dmg, mana regen |
| Scorpion Foil | Spider 6 | Sword | Tarantula Silk + Fly Swatter? | Damage scales with Bane enchant level |
| Recluse Fang | Spider 2 | Sword | Tarantula Web + ... | Grows with Spider kills |
| Mosquito Shortbow | Spider 7 | Bow | Digested Mosquito + Spider Shortbow | Extra damage at full mana |
| Primordial Armor | Spider 7 | Armor | Primordial Shard / Eye | T5 armor |
| Shaman Sword | Wolf 3 | Sword | Wolf Tooth + Golden Tooth (?) | Reduces damage taken by 20% (?) |
| Pooch Sword | Wolf 6 | Sword | Golden Tooth + Hamster Wheel? | +dmg vs wolves; scales with HP |
| Mastiff Armor | Wolf 4 | Armor | Wolf Tooth/Golden Tooth | Crit damage converted to HP |
| Armor of the Pack | Wolf 6 | Armor | Golden Teeth + Weak Wolf Catalyst (?) | +stats per nearby party member |
| Mana Flux / Overflux / Plasmaflux Power Orb | Wolf 6/7/8 | Deployable | Radiant orb + Overflux Capacitor + ... | AoE regen / stat field |
| Voidwalker → Voidedge → Vorpal → Atomsplit Katana | Enderman 1/3/5/6 | Sword | Null Sphere, Null Ovoid, Null Atom (Atomsplit) | Soulcry (ferocity buff), bonus vs endermen |
| Final Destination Armor | Enderman 3 | Armor | Null Ovoid + Null Sphere | Gains stats per Enderman kill (kill counter) |
| Juju Shortbow | Enderman 5 | Shortbow | Null Ovoid + Enchanted Eye of Ender + Enchanted Quartz Block + Enchanted String (?) | Shortbow (no draw), 3 arrows (?) |
| Gloomlock Grimoire | Enderman 5 | Wand | Null Atom + Gloomlock? | Trade HP for mana |
| Terminator | Enderman 7 (+Cata 27 req) | Shortbow | **Judgement Core ×4 (?)** + Null Blade ×? + Braided Griffin Feather + Tessellated Ender Pearl | Fires 3 arrows, Salvation (beam) |
| Etherwarp Conduit | Enderman 7 | AOTE/AOTV upgrade | Etherwarp Merger + ... | Shift-right-click teleport to looked-at block |
| Firedust/Kindlebane/Pyrochaos Dagger | Blaze 2/4/6 | Dagger | Derelict Ashe, Blaze rods, Kelvin/Subzero Inverters | Ashen/Auric attunements |
| Twilight/Mawdredge/Deathripper Dagger | Blaze 2/4/6 | Dagger | Derelict Ashe, Twilight Arrow Poison (?) | Spirit/Crystal attunements |
| Inferno Minion | Blaze 3 | Minion | Inferno Demonlord Shard? | Produces distillate-fuelled drops |
| Steak Stake / Super Sharp 'n Stabby | Vampire 2/3 | Rift tool | Coven Seal | Executes Riftstalker |

---

# 3. Dungeons — The Catacombs

## 3.1 Dungeon Hub, Mort, entry, parties, Party Finder

**Dungeon Hub** is its own island (warp `/warp dungeon_hub`). The Catacombs Entrance requires **Combat 15** [W]. Key NPCs/objects:

| NPC / object | Role |
|---|---|
| **Mort** | Stands in each run's start room; every run starts with Mort's dialogue and a 5-s ready countdown. In the Hub he introduces dungeons and gives the first map. |
| **Catacombs gate / portal** | Walk in to open the floor selector (Entrance, F1–F7, M1–M7 if unlocked). |
| **Party Finder** (board/NPC) | Lists public groups (below). |
| **Croesus** | Holds **unclaimed reward chests** from previous runs (if you leave before opening). Kept for a limited time (?). |
| **Ophelia** | Dungeon shop: dungeon potions, Superboom TNT, Spirit Leap, Decoy, Inflatable Jerry, arrows, Dungeon Sacks. |
| **Essence shop** | Spend essence on permanent perks (Undead: +HP in dungeons, Wither: +class XP, Spider: +damage vs ..., etc. (?)). |
| **Saul** | Gives **Saul's Recommendation** — allows solo (1-player) runs [W]. |
| **Mayor Paul** | Perk "EZPZ": +10 bonus score; "Marauder": cheaper chests; "Benediction": stronger blessings. |

**Party rules**

| Rule | Value |
|---|---|
| Party size | 1–5 (normal 5). Solo requires Saul's Recommendation [W]. |
| Class selection | Each player picks one class in the Catacombs menu before the run. **Duplicates allowed**; Mage cooldown reduction is halved with >1 Mage (?), other duplicates get "doubled strength" penalties/bonuses per wiki note (?). |
| Level gate | Every member must meet the floor's Catacombs requirement. |
| Start | Leader clicks floor → whole party teleported into a fresh instance; doors locked during a 5-s countdown. |

**Party Finder**

| Field | Values |
|---|---|
| Floor/mode | Entrance, F1–F7, M1–M7 |
| Min class level | 0–50 |
| Min Catacombs level | 0–50 |
| Class slots | Up to 5, leader may mark wanted classes |
| Note | 1 line free text |
| Listing | Public, newest first; auto-delisted when full or when the run starts |
| Join | Click → added to leader's party; class picked from free slots |

## 3.2 Floor list and requirements [W]

| Floor | Cata lvl | Boss | Map grid | Secret % req | Speed limit (normal / master) |
|---|---|---|---|---|---|
| Entrance | 0 (Combat 15) | none — Watcher/blood room | 4×4 | 30% (?) | 10 min (?) |
| F1 | 1 | Bonzo | 4×5 | 30% | 10 / 8 |
| F2 | 3 | Scarf | 5×5 | 40% | 10 / 8 |
| F3 | 5 | The Professor | 5×5 | 50% | 10 / 8 |
| F4 | 9 | Thorn | 6×5 | 60% | 12 / 8–10 (?) |
| F5 | 14 | Livid | 6×5 | 70% | 10 / 8 |
| F6 | 19 | Sadan | 6×6 | 85% | 12 / 8–10 (?) |
| F7 | 24 | Maxor → Storm → Goldor → Necron | 6×6 | 100% | 14 / 14 |
| M1 | 24 + F7 beaten | Bonzo | 4×5 | 100% | 8 |
| M2 | 26 | Scarf | 5×5 | 100% | 8 |
| M3 | 28 | Professor | 5×5 | 100% | 8 |
| M4 | 30 | Thorn | 6×5 | 100% | 8–10 (?) |
| M5 | 32 | Livid | 6×5 | 100% | 8 |
| M6 | 34 | Sadan | 6×6 | 100% | 8–10 (?) |
| M7 | 36 | Wither Lords + Wither King | 6×6 | 100% | 14 |

**Master Mode rules [W]:** mobs vastly stronger (bosses ×20–800 HP, see §3.13); 100% secrets required for full exploration; **completing the run grants every chest type regardless of score** with higher base quality; Master Stars and M-exclusive drops.

## 3.3 Catacombs skill — XP table and dungeon stat boost [W]

| Lvl | XP for level | Cumulative | Dungeon stat boost | Notable unlock |
|---|---|---|---|---|
| 1 | 50 | 50 | +15% | F1 |
| 2 | 75 | 125 | +20% | |
| 3 | 110 | 235 | +25% | F2 |
| 4 | 160 | 395 | +31% | |
| 5 | 230 | 625 | +38% | F3 |
| 6 | 330 | 955 | +46% | |
| 7 | 470 | 1,425 | +55% | |
| 8 | 670 | 2,095 | +65% | |
| 9 | 950 | 3,045 | +75% | F4 |
| 10 | 1,340 | 4,385 | +85% | |
| 11 | 1,890 | 6,275 | +95% | |
| 12 | 2,665 | 8,940 | +105% | |
| 13 | 3,760 | 12,700 | +115% | |
| 14 | 5,260 | 17,960 | +125% | F5 |
| 15 | 7,380 | 25,340 | +135% | |
| 16 | 10,300 | 35,640 | +145% | |
| 17 | 14,400 | 50,040 | +155% | |
| 18 | 20,000 | 70,040 | +165% | |
| 19 | 27,600 | 97,640 | +175% | F6 |
| 20 | 38,000 | 135,640 | +185% | Essence tradable on Bazaar |
| 21 | 52,500 | 188,140 | +195% | |
| 22 | 71,500 | 259,640 | +205% | |
| 23 | 97,000 | 356,640 | +215% | |
| 24 | 132,000 | 488,640 | +225% | F7 + M1 |
| 25 | 180,000 | 668,640 | +235% | |
| 26 | 243,000 | 911,640 | +245% | M2 |
| 27 | 328,000 | 1,239,640 | +255% | Terminator requirement |
| 28 | 445,000 | 1,684,640 | +265% | M3 |
| 29 | 600,000 | 2,284,640 | +275% | |
| 30 | 800,000 | 3,084,640 | +285% | M4 |
| 31 | 1,065,000 | 4,149,640 | +295% | |
| 32 | 1,410,000 | 5,559,640 | +305% | M5 |
| 33 | 1,900,000 | 7,459,640 | +315% | |
| 34 | 2,500,000 | 9,959,640 | +325% | M6 |
| 35 | 3,300,000 | 13,259,640 | +335% | |
| 36 | 4,300,000 | 17,559,640 | +345% | M7 |
| 37 | 5,600,000 | 23,159,640 | +355% | |
| 38 | 7,200,000 | 30,359,640 | +365% | |
| 39 | 9,200,000 | 39,559,640 | +375% | |
| 40 | 12,000,000 | 51,559,640 | +385% | |
| 41 | 15,000,000 | 66,559,640 | +395% | |
| 42 | 19,000,000 | 85,559,640 | +405% | |
| 43 | 24,000,000 | 109,559,640 | +415% | |
| 44 | 30,000,000 | 139,559,640 | +425% | |
| 45 | 38,000,000 | 177,559,640 | +435% | |
| 46 | 48,000,000 | 225,559,640 | +445% | |
| 47 | 60,000,000 | 285,559,640 | +455% | |
| 48 | 75,000,000 | 360,559,640 | +465% | |
| 49 | 93,000,000 | 453,559,640 | +475% | |
| 50 | 116,250,000 | 569,809,640 | +485% | |
| 50+ | 200,000,000 each | | cosmetic only | |

**Stat boost meaning:** while *inside the Catacombs*, base stats of a **dungeon item** are multiplied by `(1 + boost)` (plus star bonuses, §3.15). Outside, the item shows normal stats. Non-dungeon items get no boost.

Many dungeon items carry a **Catacombs level requirement** to equip [W]: e.g. Rotten/Skeleton Grunt armor lvl 1, Machine Gun Shortbow 2, Dreadlord Sword 3, Zombie Soldier Cutlass/Soulstealer Bow 5, Leaping Sword/Spider Queen's Stinger 8, Spirit Mask/Juju 15, Midas' Sword 17, Fel Sword/Ice Spray 20, Sinseeker Scythe/Flaming Flay 23, Reaper Mask 24, Midas Staff/Zombie Lord armor 25, Terminator 27.

The same XP table is used for each of the **5 class levels**.

## 3.4 Classes [W]

**Class XP:** the selected class earns the run's full XP; the other four classes passively earn **25%** (?). Levels 1–50 use the Catacombs table. **Class average** is a common Party Finder filter.

**Inputs:** every player gets a **Dungeon Orb** (hotbar). Right-click → **class ability**; drop (Q) → **Ultimate**. Ghosts get two ghost abilities.

### Healer

| Passive | Effect | Scaling |
|---|---|---|
| Renew | Healing abilities heal **+50%** | +1%/lvl |
| Healing Aura | Teammates within **8 blocks** heal **0.5% max HP/s** | +0.1% per 5 lvls |
| Revive | Fairy revives dead teammates; then gone for **100 s** | −1 s cd / lvl |
| Orbies | Kills: **15%** chance to drop an orb (heal **30%** max HP + random buff) | |
| Soul Tether | Tethered mate within **30 blocks** gets **0.5% of your max HP/s**; every **5 melee hits** heals both **1%** | |
| Overheal | Overflow healing → absorption up to **20%** of target's HP | +0.4%/lvl |
| Stats | | +0.2 Vitality, +0.5 Mending per lvl |

| Ability | Effect | Cooldown |
|---|---|---|
| **Healing Circle** | Circle heals **2% HP/s** | 2 s (+0.5% heal, +0.5 radius, +1 s dur per 5 lvls) |
| **Wish** (ult) | Heals whole party to full + absorption **20%** max HP | 120 s (+1% shield/lvl) |
| Ghost: Healing Potion | 10-block radius: **10% max HP + 100 HP** | |
| Ghost: Revive | Revive self after **50 s** | |

### Mage

| Passive | Effect |
|---|---|
| **Mage Staff** | Melee becomes a ranged beam (≤ **10 blocks**); damage = `(30% + 0.9 × Int/1000) × melee damage` |
| **Efficient Spells** | Abilities have **25%** shorter cooldown, +1% per 2 lvls → **50%** at 50 |
| Per level | **+5 Intelligence** (500 at 50); +1 Ability Damage per 5 lvls |

| Ability | Effect | Cooldown |
|---|---|---|
| **Guided Sheep** | Exploding sheep (100 → 2,000 dmg by level); **breaks weak walls & crypts** | 30 s |
| **Thunderstorm** (ult) | Lightning hits enemies in **10-block radius for 15 s** | 500 s (?) |
| Ghost: Instant Wall | 5×3 wall for 10 s | |
| Ghost: Fireball | 80 → 1,600 dmg | |

### Berserk

| Passive | Effect |
|---|---|
| **Bloodlust** | Next hit after a kill deals bonus damage (5 s); heals **3% missing HP**; −1 s Throwing Axe cd per proc |
| **Lust for Blood** | Each hit on the same enemy +3%/lvl damage, capped (+100% cap per 5 lvls) |
| **Indomitable** | Gain a % of Strength as Defense |
| **Weapon Master** | Melee hits up to **5 enemies** in a cone; +Swing Range |
| Per level | +0.75% melee damage, +0.4% Bloodlust, +1% Throwing Axe, +0.4 Speed / 5 lvls |

| Ability | Effect | Cooldown |
|---|---|---|
| **Throwing Axe** | Damage = **highest hit in the last minute** | 10 s |
| **Ragnarok** (ult) | **+100 Attack Speed, +400 Speed, +50% damage for 15 s**; 3 zombie minions (800 → 16,000 HP) | 60 s |
| Ghost: Buff Potion | +30 Strength to mates in 10 blocks (+2 per 5 lvls) | |
| Ghost: Ghost Axe | 50 → 1,000 dmg | |

### Archer

| Passive | Effect |
|---|---|
| **Doubleshot** | **50%** (+1%/lvl, 101% at 50) chance for an extra arrow |
| **Bone Plating** | Reduces next hit taken (scales with lvl and Crit Damage); **250 s** cd |
| **Bouncy Arrows** | +2%/lvl chance for arrows to bounce to another target |
| Per level | +1.6% arrow damage |

| Ability | Effect | Cooldown |
|---|---|---|
| **Explosive Shot** | 3 exploding arrows, **4-block radius**, damage = highest bow hit last minute | 40 s base, −2 s per 5 lvls (−20 s at 50) (?) |
| **Rapid Fire** (ult) | **5 arrows/s for 4 s** (+1 s per 10 lvls → **8 s**), **75%** of highest bow hit | 100 s (?) |
| Ghost: Healing Bow | Shot mate: **10% max HP + 50 Str for 10 s**; 10 s cd | |
| Ghost: Stun Bow | Stuns non-boss mobs; 5 s cd | |

### Tank

| Passive | Effect |
|---|---|
| Base | **+100 Health, +50 Defense, +10 Vitality** |
| **Protective Barrier** | Permanent **+25% Defense**; below **50% HP** → absorption **10%** max HP; 90 s cd |
| **Taunt** | Mobs prefer you while above 25% HP |
| **Diversion** | Divert **80%** of damage taken by mates within **30 blocks** to you |
| **Defensive Stance** | Immune to mob knockback |
| Per level 1–49 | +1 Defense, +0.1 Vitality, +0.2% Defense; L50: +2 Def, +2 Vit, +0.2% Def |
| Solo Tank | Defense bonus +50%; barrier triggers at 100% HP for 20%, 45 s cd |

| Ability | Effect | Cooldown |
|---|---|---|
| **Seismic Wave** | **20,000 + 10% per 50 Defense** damage in a line (+20k–50k/lvl, +100k at 50) | 15 s |
| **Castle of Stone** (ult) | **70% damage reduction for 20 s**; aggro all enemies in 10 blocks | 150 s |
| Ghost: Stun Potion | Stun mobs in 10 blocks | |
| Ghost: Absorption Potion | +200 absorption (+10/lvl) for 3 s, 10 blocks | |

**Class milestones (0–9):** earned during the run by class-specific contribution (Healer: healing done; Mage: ability damage; Berserk: melee damage; Archer: ranged damage; Tank: damage taken/absorbed). Per the wiki the Wooden chest requires reaching **Class Milestone 2** [W]. Clone: thresholds per floor per class; milestones boost class XP (?).

## 3.5 In-dungeon rules [W]

| Rule | Value |
|---|---|
| Mana on hit | Each melee hit restores **5 + 1% max mana** |
| Speed | Speed above 100 is multiplied by **0.67** |
| Potions | Most effects disabled except Stamina, Healing, Mana (and dungeon potions) |
| Item stats | Dungeon items: Catacombs boost + stars; non-dungeon items: raw stats |
| Death | Player becomes a **ghost** (invisible, flying, inventory replaced by ghost abilities, can still see map). Each death **−2 Skill** (first death −1 with Legendary Spirit pet). |
| Revive | Healer's Revive fairy; **Revive Stone** (secret pickup); Healer ghost self-revive (50 s); dead players are revived on boss-room entry (?) |
| Wipe | All players dead → run **fails**; no chests (?) |
| Leaving | Allowed; chest claim via Croesus (?) |
| Timer | Starts when the start door opens; runs until the boss dies |

## 3.6 Map generation

### Observed facts

| Property | Value |
|---|---|
| Grid | W×H room cells: 4×4 Entrance, 4×5 F1, 5×5 F2–F3, 6×5 F4–F5, 6×6 F6–F7 [W] |
| Cell size | **31×31 blocks with a 1-block gap → 32-block pitch** (?); doors centred on cell edges |
| Shapes | **1×1, 1×2, 1×3, 1×4, 2×2, L (3 cells)** [W]. L templates: Altar, Pirate, Chambers, Lava Ravine, Market, Melon, Well, Dino Site, Layers, Withermancer [W] |
| Templates | Hand-built schematics per shape (~100+ total), rotated 0/90/180/270 |
| Special rooms | Start (green), Blood (red), Fairy (pink), Puzzle (purple, ≥2 [W]), Trap (orange), Miniboss (yellow), Normal (brown), Rare (variant) |
| Doors | Normal (opens on approach), **Wither Door** (black; needs 1 Wither Key, consumes it), **Blood Door** (red; needs the Blood Key), Fairy door (pink, free) |
| Keys | Wither / Blood Keys dropped by a key mob or found in a room; picking one up gives it to the **whole party**; shown in sidebar |
| Magical Map | Slot 9; rooms coloured by type; **white ✔ cleared**, **green ✔ cleared + all secrets**, **red ✖ failed puzzle**, **?** adjacent unvisited |
| Crypts | Hidden tombs in normal rooms; break with explosives / Guided Sheep; Crypt Undead inside; +1 score each, max 5 |

### Proposed generation algorithm (clone) — reconstructed, not Hypixel's source

```
generate(floorCfg, seed):
  grid = W x H EMPTY
  1. START: random edge cell.
  2. BLOOD: edge cell maximising BFS distance from START.
  3. FAIRY: random cell, distance >= 2 from START and BLOOD.
  4. PUZZLES: floorCfg.puzzles (F1: 2, F2-F3: 2-3, F4+: 3-4 (?)) 1x1 cells.
     TRAP: 0-1 (F3+). MINIBOSS: 1-2 rooms (may be multi-cell).
  5. NORMAL fill: shuffle empty cells; for each try shapes by weight
       {1x1:30, 1x2:25, 1x3:12, 1x4:6, 2x2:12, L:15}; pick template + rotation.
  6. CONNECT: room-adjacency graph -> random spanning tree from START
       (randomised Prim), then add 15-25% extra edges as loops.
       Puzzle / Blood / Fairy / Trap rooms keep exactly 1 door (leaves).
  7. DOORS: tree edges -> doors. Edge into BLOOD = BLOOD DOOR.
       Choose K edges on the START->BLOOD path as WITHER DOORS
       (K: F1 1-2, F4 2-3, F7 3-5 (?)). Others NORMAL.
  8. KEYS: for each wither door i, place its key in a NORMAL room reachable
       from START without crossing door i or any later locked door.
       Blood Key in a room reachable after the last wither door.
  9. SECRETS: authored per template. total = sum.
 10. VALIDATE: BFS with lock/key simulation reaches BLOOD and all rooms; else reseed.
 11. POPULATE: room mobs (flag star mobs), minibosses, crypts, Mimic (F6+: one chest in a
     normal room is the Mimic), Prince (if a party member has the attribute).
```

## 3.7 Room types

| Type | Map colour | Count | Clear condition | Contents |
|---|---|---|---|---|
| Start | green | 1 | — | Mort, spawn, countdown |
| Normal | brown | rest | kill all **✯ star mobs** | mobs, secrets, crypts, key mob |
| Puzzle | purple | 2–4 | solve | puzzle; Blessing chest |
| Trap | orange | 0–1 | reach end | parkour, arrow traps, lava, secrets |
| Miniboss | yellow | 1–2 | kill miniboss | §3.10 |
| Fairy | pink | 1 | — | Fairy Soul, healing/revive fairy (?) |
| Blood | red | 1 | all Watcher mobs dead | Watcher, then boss portal |
| Rare | brown | 0–1 | as normal | Vinny 8-Ball, Pillars, Sand Dragon, Tombstone, Stone Window, Lava Pit, Mini Rail Track, Trinity, Hanging Vines [W] |
| Boss | — | 1 | kill boss | separate arena |

## 3.8 Secrets [W]

| Type | How | Typical reward |
|---|---|---|
| Chest | hidden behind walls/levers | potions, Revive Stone, Spirit Leaps, Superboom, Decoys, essence |
| Item pickup | item on the floor | Enchanted Ender Pearl, Spirit Leap, Dungeon Potion, Revive Stone |
| Bat | kill a bat | item |
| Wither Essence | click a wither skull | 1–3 Wither Essence |
| Lever / Redstone Key | pull lever / place key on skull | opens a hidden secret room |
| Dungeon Journal | pickup | lore collectible |
| Fairy Soul | Fairy room | fairy soul |

Each template has a fixed count (e.g. Crypt room 1×2 = 5 secrets [W]). An F7 run typically has 40–70 secrets (?). Weak walls (cracked bricks) break with Superboom TNT / Explosive Shot / Guided Sheep.

## 3.9 Puzzles — every puzzle and solver logic

A solved puzzle opens a chest with a **Tier V Blessing** [W]. Each **failed or unfinished** puzzle costs **14 Skill** [W].

| Puzzle | Floors [W] | Failable | Summary |
|---|---|---|---|
| Tic Tac Toe | all | yes | draw/beat the computer |
| Three Weirdos | all | yes | find the honest NPC's chest |
| Creeper Beams | all | no | 4 beams through a creeper |
| Water Board | all | no | route water to open 5 doors |
| Teleport Maze | all | no | find the pad route to the centre |
| Higher or Lower | F2+ | yes | kill 10 blazes in HP order |
| Boulder | F3+ | yes | push boulders to clear a path |
| Ice Path | F3+ | yes | slide a silverfish to the goal |
| Quiz | F4+ | yes | 3 trivia questions |
| Ice Fill | F7 | no | walk every ice tile once |
| Bomb Defuse | removed | yes | 2-player communication |

**Tic Tac Toe.** 3×3 board of item frames with a button under each cell. Computer is O and starts; player X. Player presses a button → mark placed → AI replies. AI 3-in-a-row → **fail**; draw or player win → **solved**. *Clone:* perfect minimax AI (player can always force a draw). *Solver/assist:* minimax best move.

**Three Weirdos.** 3 NPCs (random names), each by a chest; each gives one statement. One chest has the reward. Wrong chest → **fail**. Community solvers rely on a fixed set of lines that only the honest NPC says ("The reward is not in my chest!", "At least one of them is lying, and the reward is not in X's chest!", "My chest doesn't have the reward. We are all telling the truth!", "Both of them are telling the truth. Also, X has the reward in their chest!") — the honest NPC's own chest holds the reward (?). *Clone:* fix a rule (e.g. "exactly one NPC lies" or "exactly one tells the truth"), generate statements from a grammar, brute-force validate (3 chests × 2³ truth assignments) that exactly one answer is consistent.

**Creeper Beams.** Immobile charged creeper in the centre; sea lanterns on walls/floor/ceiling. Click/shoot lantern A then B → guardian beam A↔B. Need **4 different beams through the creeper** → creeper explodes → chest. *Solver:* for each lantern pair test segment ∩ creeper AABB; choose 4 disjoint pairs.

**Water Board.** Vertical maze; water source top; **6 coloured levers** (gold, terracotta, emerald, quartz, diamond, clay) each toggle a block set; a **water lever** starts/stops flow. 5 coloured doors guard the chest, **3 start closed**. Water reaching an outlet toggles that colour's door; need **all 5 open at once**. *Solver:* identify layout from colours present → precomputed lever list. *Clone:* simulate MC water (down first, sideways ≤7 toward nearest drop), BFS over 2⁶ lever states.

**Teleport Maze.** Small cells (9 / "seven rooms" [W]) with end-portal-frame pads teleporting between cells; one pad goes to the centre (chest). *Solver:* the correct pad's exit faces the centre; mods highlight it. *Clone:* random pad permutation with a guaranteed ≤5-hop route; colour hint.

**Higher or Lower (Blaze).** **10 blazes** with HP shown around a central pillar; chest at top or bottom. Kill in ascending HP order for one variant, descending for the other (?). Wrong blaze → **fail**. *Solver:* sort by HP, highlight next.

**Boulder.** 7×6 grid of 3×3×3 boulders; click a boulder's button to push it one cell away (if free). Clear a path to the chest; reset button exists. *Solver:* layouts are a fixed set → precomputed pushes; *Clone:* BFS over grid states.

**Ice Path.** Silverfish on packed ice; hitting it slides it until an obstacle; reach the goal. *Solver:* BFS over positions with 4 slide moves.

**Ice Fill.** Three boards (**3×3, 5×5, 7×7**) with obstacles; ice turns to packed ice when walked on; cover every tile exactly once; re-stepping resets (?). *Solver:* Hamiltonian-path DFS.

**Quiz (Ouija).** NPC asks **3 SkyBlock trivia questions**, 3 options each; stand on/pull the correct one; a wrong answer fails; reward can be **Blessing of Time** [W]. *Clone:* question bank (≥150) generated from the clone's own data so renamed items stay correct.

**Bomb Defuse (removed).** Two players separated by a wall: one reads modules, other operates ("Keep Talking" style). Removed because it required two coordinated players.

F7's **devices** (Simon Says, Lights, Arrow Align, Shoot the Target) and **terminals** live in the Goldor phase (§3.13), not in rooms.

## 3.10 Mobs

### Regular dungeon mobs (?)

| Mob | Floors | Notes |
|---|---|---|
| Crypt Ghoul / Zombie Grunt | E–F3 | basic melee zombies |
| Skeleton Grunt / Skeleton Soldier | E–F7 | melee/bow skeletons |
| Crypt Lurker / Crypt Souleater | E–F2 | zombies with soul effects |
| Lonely Spider / Cellar Spider | F1+ | spiders, guard secrets |
| Zombie Soldier / Knight / Commander | F2+ | heavier zombies; Commander leads a pack |
| Super Tank Zombie | F2+ | tank |
| Deathmite | F2+ | swarming silverfish |
| Skeleton Master / Skeletor / Skeletor Prime | F3+ | bows; Skeletor shield |
| Super Archer / Sniper | F4+ | long-range high damage |
| Withermancer | F5+ | wither skulls |
| Fels | F5+ | hide in walls/floor, tentacle attack |
| Zombie Lord / Skeleton Lord | F6+ | high HP |
| Wither Guard / Wither Miner | F7 | also in the Maxor phase |
| Crypt Undead | all | inside crypts, +1 score each (max 5) |

Scaling: base stats × floor multiplier; Master Mode ×~100–300 (?). Example [W]: Bonzo's summoned Undead = 45,000 HP (F1) vs 15M (M1).

**Star mobs (✯):** nametag ✯ marks the mobs required to clear a room.

### Minibosses

| Miniboss | Floors | Gimmick | Drops |
|---|---|---|---|
| **Lost Adventurer** | F1+ | Wears a dragon armor set and uses its bonus: Unstable (lightning), Young (speed), Superior (all), Holy (healing), Frozen Blaze (?) | that armor piece (dungeonized), essence |
| **Angry Archaeologist** | F3+ | Diamond armor + sword, throws healing/harming potions | gear, essence |
| **Shadow Assassin** | F4+ | Invisible until close, teleports behind you, heavy hits | SA gear, essence |
| **King Midas** | F2+ (rare) | Gold armor, Midas Sword, tanky | Midas items, coins |
| **Frozen Adventurer** | F4+ | Frozen Scythe ice beams | (?) |
| **Mimic** | F6–F7, M6–M7 | A chest in a normal room is a disguised mob (~1.5M HP (?)) | **+2 bonus score** [W] |
| **Prince** | any (attribute-gated) | Spawns if a player has the right attribute | **+1 bonus score** [W] |

### The Watcher (blood room)

| Step | Behaviour |
|---|---|
| 1 | Blood door opened → the **Watcher** (giant invulnerable floating head) greets the party. |
| 2 | Spawns **blood mobs** one at a time (every ~2–5 s (?)) from a per-floor pool, up to a cap (F1 ≈ 4–6, F7 ≈ 10–12 (?)). |
| 3 | Blood mobs: Revoker, Psycho, Reaper, Cannibal, Mute, Ooze, Putrid, Freak, Leech, Tear, Parasite, Flamer, Skull, Mr. Dead, Vader, Frost, Walker, Wandering Soul; on high floors miniature floor bosses (Bonzo, Scarf, Livid) appear as blood mobs. |
| 4 | After the last spawn: "That will be enough for now"; he moves to the back. When all blood mobs are dead the **boss door/portal opens** (Entrance: run completes). |
| 5 | "Blood camping" — one player kills blood mobs while others clear — is a valid strategy. |

## 3.11 Blessings

Party-wide buffs for the rest of the run from puzzle chests (Tier V) and some secrets; tiers stack additively.

| Blessing | Effect (approx per Tier V) (?) |
|---|---|
| Power | +Strength and +Crit Damage (≈ +10 Str, +10% CD) |
| Life | +Health % and Health Regen (≈ +15% HP) |
| Wisdom | +Intelligence and Speed (≈ +10 Int, +5 Spd) |
| Stone | +Defense and +damage (≈ +30 Def) |
| Time | All four, smaller values (Quiz) |

## 3.12 Score system [W]

**Score = Skill + Exploration + Speed + Bonus** (max 100 + 100 + 100 + 17).

| Component | Formula |
|---|---|
| **Skill** (≤100) | `100 − 2×deaths − 14×(failed or unfinished puzzles)`; first death −1 with Legendary Spirit pet |
| **Exploration** (≤100) | `floor(60 × clearedRooms/totalRooms) + floor(min(40, 40 × secretsFound / (req% × totalSecrets)))` |
| **Speed** (≤100) | 100 if time ≤ limit; beyond: −1 per **12 s** until 90, −1 per **24 s** until 80, −1 per **30 s** until 70, then −1 per **40 s** |
| **Bonus** (≤17) | +1 per crypt (max **5**), Mimic **+2**, Paul EZPZ **+10**, Prince **+1** (once/run) |

| Grade | Score |
|---|---|
| S+ | ≥ 300 |
| S | 270–299 |
| A | 230–269 |
| B | 160–229 |
| C | 100–159 |
| D | 0–99 |

Score feeds **chest availability** (Emerald A, Obsidian S, Bedrock S+ [W]), **chest quality** (S+ ×1.05), **Catacombs XP**, and the **dungeon RNG meter**.

**Sidebar during a run (clone should show):** Time Elapsed, Cleared %, Secrets x/y, Crypts x/5, Deaths, Puzzles (✔/✖/?), Keys (Wither n, Blood ✔), party list (class, HP), and a live **score estimate** (Hypixel hides it; mods show it — show it natively).

## 3.13 Bosses — floor by floor

Boss stats summary:

| Floor | Boss | HP (normal) | HP (master) | Damage (normal / master) | Phases |
|---|---|---|---|---|---|
| Entrance | — (Watcher) | — | — | — | Blood room only |
| F1 / M1 | **Bonzo** | 250,000 [W] | 200,000,000 [W] | 2,664 / 45,000 [W] | 2 |
| F2 / M2 | **Scarf** | 1,000,000 [W] | 375,000,000 [W] | (?) | 2 |
| F3 / M3 | **The Professor** | 3,000,000 [W] | 600,000,000 [W] | 6,640 / 150,000 [W] | 3 |
| F4 / M4 | **Thorn** | 4 Spirit-Bow hits [W] | 6 hits [W] | n/a (summons) | 1 (+Spirit Bear) |
| F5 / M5 | **Livid** | 7,000,000 [W] | 600,000,000 [W] | (?) | 1 (with clones) |
| F6 / M6 | **Sadan** | 40,000,000 [W] | 800,000,000 [W] | (?) | 3 |
| F7 / M7 | **Maxor** | 100,000,000 [W] | 800,000,000 [W] | (?) | P1 |
| F7 / M7 | **Storm** | 400,000,000 [W] | 1,000,000,000 [W] | (?) | P2 |
| F7 / M7 | **Goldor** | 750,000,000 [W] | 1,200,000,000 [W] | (?) | P3 |
| F7 / M7 | **Necron** | 1,000,000,000 [W] | 1,400,000,000 [W] | (?) | P4 |
| M7 | **Wither King** + 5 dragons | dragons ~1,000,000,000 each [W] | — | dragon 800,000 [W] | P5 |

Master-mode defense values [W]: Storm 1,200, Goldor 1,800, Necron 2,100, Apex Dragon 2,500.

### Entrance — no boss
Clear rooms → open Blood → kill the Watcher's mobs → run ends. Tutorial floor: few rooms, 1–2 puzzles, low HP mobs.

### F1 — Bonzo (circus arena, 7 pillars) [W]

| Phase | Trigger | Mechanics |
|---|---|---|
| Intro | party enters arena | dialogue; Bonzo holds the Dark Orb |
| **P1** | — | Runs around; **Wither Skull** projectiles (**1,598 dmg**); **Summon Undead** continuously (Undead **45,000 HP**, M1 15M). |
| Revival | P1 HP reaches 0 | Bonzo "revives himself and becomes much stronger" (Bonzo's Mask-style second life) — full HP again (?) |
| **P2** | after revival | **Balloon Attack** (skull-like, M1 **22,500 dmg**), **Balloon Barrage**: moves to arena centre and fires balloons in all directions; balloons can target pillar tops — use pillars as cover |
| End | P2 HP 0 | Death line, chests spawn |

M1: more frequent skeleton/undead spawns, steeper balloon angles.

### F2 — Scarf [W]

| Phase | Mechanics |
|---|---|
| **P1** | Scarf behind a barrier; 4 Undead minions fight: **Warrior** (500k / 75M HP, 2,880 / 54k dmg, charge), **Archer** (400k / 60M, 2,752 / 51.6k), **Mage** (400k / 60M, 2,432 / 45.6k), **Priest** (600k / 90M, 1,920 / 36k, **heals/revives allies — kill first**) |
| **P2** | All 4 dead → barrier drops; Scarf fights: **Skull Barrage** (explosive skulls), **Soul Sand Blizzard** (true damage + Slowness). Dead minions may be revived by Scarf (?) |
| M2 | Minions spawn **in pairs**; Scarf stays immune until **≥4 undead** have been killed |

### F3 — The Professor [W]

| Phase | Mechanics |
|---|---|
| **P1** | Professor behind barrier; **4 Guardians** on pedestals: **Healthy** (Giga Heal: restores all guardians to full unless **≥10 hits** interrupt it before the timer), **Reinforced** (tankiest; TNT; summons Rogue Guardians), **Chaos** (Anger meter; discharge damage when approached at high anger), **Laser** (focus beam **175 true dmg/s for ~10 s**, then overheats). All body-slam **1,500 dmg** if you stand on their pedestal. Guardians killed **respawn at 80% (M: 90%) HP after 10 s** unless all die within that window. Rogue Guardians spawn continuously and evolve into Elder variants. |
| **P2** | All guardians dead → Professor (3M / 600M) fights directly until **25% HP** |
| **P3** | Professor absorbs the Elder Guardians → **Guardian form** with combined abilities (healing, lasers, TNT); kill to win |

### F4 — Thorn [W]

| Element | Rule |
|---|---|
| Damage model | Thorn **can't be damaged normally**; it takes **4 Spirit Bow hits (M4: 6)** |
| Spirit animals | Thorn continuously summons: **Bull** (charge; 30% / 75% base dmg), **Wolf** (packs; **3.5× damage to Tanks**), **Bat** (attacks airborne players, ignores i-frames), **Rabbit** (launches players up), **Chicken** (lightning; becomes a mine on landing), **Sheep** (creeper-like explosion) |
| Spirit Bear | After **25 (M: 30)** spirit animals are killed → **Spirit Bear** (5M / 180M HP) spawns |
| Spirit Bow | Bear drops the **Spirit Bow**; whoever picks it up gets **one shot** (bow vanishes after 1 arrow or ~20 s (?)) |
| Hit | A hit **stuns Thorn 5 s**, then **1 s** before he can be hit again; he speeds up as he takes hits and turns away near death |
| Arena | Coloured circles: **green heals**, **orange ignites** (5%/s to spirit animals inside) |
| Loop | Kill 25 animals → bear → bow → shoot → repeat until 4/6 hits |

### F5 — Livid [W]

| Element | Rule |
|---|---|
| Setup | Livid uses the Dark Orb (Thorn's remains) |
| Clones | After **16 s**: blinds everyone **1 s** and spawns **8 identical clones** (from Endermen), each with a different nametag colour |
| Identify | The **real Livid's colour = the ceiling's wool colour** (wool block near the ceiling centre matches) |
| Clone kits | **Vendetta** (teleport + daggers), **Crossed** (Blindness), **Hockey** (charges), **Doctor** (Harming splash, true dmg), **Frog** (Poison), **Smile** (Slowness splash), **Scream** (knockback circle + true dmg), **Arcade** (erratic, bow) |
| Punish | Killing a fake: it **regenerates to full** and heals nearby clones **25%** max HP (M5: all Livids also gain damage) |
| Win | Real Livid dies (7M / 600M) — single phase |

### F6 — Sadan [W]

| Phase | Mechanics |
|---|---|
| **P1 Terracottas** | Sadan animates Terracottas (melee + ranged) for **2:00**; each Terracotta kill **−1 s**; killed ones **respawn after 10 s** (from flower pots) |
| **P2 Giants** | Four giants awake: **Diamond Giant** (sword, throws it), **L.A.S.R.** (laser, damage ramps over time), **Bigfoot** (Stomp, massive AoE), **Jolly Pink Giant** (boulder toss). Kill all 4 (HP ~25M each (?)) |
| **P3 Sadan** | Sadan merges into **The Giant One** with all four kits (wiki: 25M / 600M for the giant form (?); boss header 40M / 800M) |
| M6 | Four **Mutant Giants** (**600M HP each**) with all four abilities simultaneously; after the cutscene they respawn at pillars alongside Sadan |

### F7 — The Wither Lords (4 phases) + M7 Wither King (phase 5)

#### P1 — Maxor [W]

| Element | Rule |
|---|---|
| Arena | Factory floor; Wither Guards (3.5M / 300M HP, 20k / 225k dmg, long-range knockback bow) and Wither Miners (10M / 300M, 14,460 / 180k dmg) spawn |
| Energy Crystals | **2 crystals** on high ledges; carry each to a **podium** at the arena sides → a **laser** fires at the back |
| Laser 1 | Maxor in the laser → vulnerable **until he loses 75% HP**, then enraged & invulnerable |
| Laser 2+ | Crystals respawn; second laser → vulnerable with **no cap** — must laser **at least twice** |
| Exit | Maxor dies → drop to layer 2 |

#### P2 — Storm [W]

| Element | Rule |
|---|---|
| Arena | Main platform with **4 diorite crushers** (pillars) — the **red one is broken** (so 3 usable); **control pads** in corners; lava ring |
| Lightning | Storm throws fireballs; where no intact crusher shelters, **Giga Lightning**: **2 bursts of 75% of player HP as true damage** (F7). Wither Cloak Sword negates it in normal mode (not master) |
| Crush | Standing on a pad lowers that crusher. Storm under a falling crusher → **stunned & vulnerable 10 s** |
| Progress | Must be crushed by **≥2 pillars** AND take enough damage; using all 3 without enough damage **soft-locks** (clone: never allow a softlock — respawn pillars) |
| Adds | Wither Miners (10M), Withermancers (1.2M), Wither Guards (3.5M) on raised platforms, **one Shadow Assassin (20M) per pad** |

#### P3 — Goldor (terminals) [W]

| Element | Rule |
|---|---|
| Layout | Layer 3: **4 sections** separated by **3 gates**. S1, S3, S4: **4 terminals + 2 levers + 1 device**; S2: **5 terminals + 2 levers + 1 device** |
| Goldor | **Invulnerable** during terminals; walks the corridor throwing **Greatswords**; damaging him slows his walk (and his frenzy kills you if he catches up) |
| Gate | When a section's terminals+levers+device are all done → **5 s** delay → gate opens (or break it early with Superboom / Guided Sheep) |
| Hazards | Lava floor (lava bounce; looking ≤ −40° pitch gives a +30% "super bounce"); TNT traps overhead |
| Core | After all 4 sections: Goldor surrounded by swords, fires Wither Skulls fast — **now vulnerable**; kill to proceed |

**Terminals** (open a chest-GUI mini-game; any player may do any terminal):

| Terminal | GUI | Rule |
|---|---|---|
| Correct all the panes! | 3×5 of red/green panes | Click every **red** pane until all are green |
| Change all to same color! | 3×3 coloured panes | Each click cycles one pane through a 5-colour cycle (shift-click backwards); make all the same colour |
| Click in order! | 14 numbered panes | Click 1 → 14 in order |
| What starts with: "X"? | Grid of items | Click every item whose name starts with the letter |
| Select all the [COLOR] items! | Grid of items | Click every item of that dye colour |
| Click the button on time! (Melody) | Moving pane per row | Click when the moving pane aligns with the target column (4 rows) |

**Devices (one per section, order (?))**

| Section | Device | Rule |
|---|---|---|
| S1 | **Simon Says** | 4×4 button wall; sea lanterns flash a sequence; repeat it; sequence grows to 5 |
| S2 | **Lights** (Lever Lights) | Flip levers so every lamp is lit (each lever toggles a fixed set) |
| S3 | **Arrow Align** | 5×5 board of item-frame arrows; rotate each to point along the hidden path from start to end |
| S4 | **Shoot the Target** | 3×3 targets (emerald blocks); shoot each one that lights, 9 times |

Levers: 2 per section, scattered on walls; pull both.

#### P4 — Necron [W]

| Element | Rule |
|---|---|
| Arena | Bottom layer; Necron lifts players over a platform ringed by lava, then destroys the platform |
| Attacks | Wither-skull barrages from three heads (high knockback), fireballs, lightning, **4 floating diamond swords** launched with knockback, **rotating beams**: 4 horizontal groups rotate for **15 s**, **25% of player HP as true damage** on contact |
| Frenzy | At **80% HP** (and again at a lower threshold, wiki: 5% (?), likely 50% (?)) he teleports to centre and explodes repeatedly; **radius grows when he takes no damage, shrinks when damaged**; after ~10 s a section of the outer ring breaks |
| End | F7: Necron dies → chests. M7: → P5 |

#### P5 (M7 only) — The Wither King [W]

| Element | Rule |
|---|---|
| Relics | **5 coloured relics** must be carried to their matching **cauldrons** in the throne room; a mismatch **fails the run** |
| King | **Cannot be damaged**; attacks: **pull** (instant kill if too close), sweeping **beam**, lightning |
| Dragons | **Apex (green), Flame (orange), Ice (blue), Power (red), Soul (purple)** — each ~**1B HP**, **800k dmg**, **2,500 def** (Apex numbers [W]); spawn sequentially **every 30 s** at their statues. Flame: fireballs + dives; Ice: freezes ground/players; Power: TNT from centre; Soul: random teleports |
| Kill rule | A dragon killed **near its own coloured statue** destroys the statue permanently; killed elsewhere it **respawns** |
| Fail | **All 5 dragons alive simultaneously** → ritual lifts players, giant hand crushes them → run failed |
| Adds | Wither Husks (500M), Apostles (800M, 120k dmg) |
| Win | All 5 statues destroyed → Wither King yields → chests |

## 3.14 Reward chests and loot

### Chest tiers [W]

| Chest | Unlock | Base open cost | Floors |
|---|---|---|---|
| **Wood** | boss killed + Class Milestone 2 | free | all |
| **Gold** | boss killed | 25,000 (F1) → 100,000 (F7) | all |
| **Diamond** | boss killed | 50,000 → 250,000 | F2+ (?) |
| **Emerald** | score ≥ 230 (A) | 100,000 → 500,000 | F3+ (?) |
| **Obsidian** | score ≥ 270 (S) | 250,000 → 1,000,000 | F4+ (?) |
| **Bedrock** | score ≥ 300 (S+) | 2,000,000 | F5–F7 [W], M5–M7 |
| Master Mode | all chest types granted on completion regardless of score [W] | | M1–M7 |

**Total price to claim = base cost + the rare item's "coin cost"** (e.g. F7 Bedrock with Necron's Handle = 2M + 98M = **100M**).

| Rule [W] | Effect |
|---|---|
| One chest per run | you may claim **one** chest |
| **Dungeon Chest Key** | allows claiming a **second** chest (consumed) |
| **Kismet Feather** | **re-roll** one chest's contents once |
| Chest quality boosts | S+ ×1.05; Treasure Talisman/Ring/Artifact ×1.01–1.03 (applied twice); Boss Luck perk +1…+10; "Catacombs Box" attribute +1…+10 |
| Unclaimed | sent to **Croesus** in the Dungeon Hub |

**Chest roll algorithm (clone):** each chest has a *quality* value = baseQuality[floor][chest] × multipliers + flat bonuses. Each loot entry has a minimum quality and a weight. Roll 1 rare slot from entries with `minQuality ≤ quality` by weight; fill other slots with guaranteed essence/books. Higher-tier chests have higher base quality so they unlock the rarest items.

### F7 chest contents [W] (cost = coin cost on top of chest base)

| Chest (base) | Items (coin cost) | Essence |
|---|---|---|
| Wood (0) | Enchanted Books: Bank I, Ultimate Jerry I, Feather Falling VI, Infinite Quiver VI, Rejuvenate I | 10 Undead, 1–15 Wither |
| Gold (100k) | Wither Boots 2.4M, Hot Potato Book 0, Wither Catalyst 900k, Precursor Gear 400k, books 0 | 20 Undead, 18 Wither |
| Diamond (250k) | Wither Helmet 3.75M, Wither Boots 2.25M, Soul Eater I 750k, Precursor Gear 250k | 30 Undead, 21 Wither |
| Emerald (500k) | Wither Leggings 5.5M, Wither Cloak Sword 4M, Wither Blood 2M, Wither Helmet 3.5M | 40 Undead, 28 Wither |
| Obsidian (1M) | Wither Chestplate 9M, One For All I 1M, Recombobulator 3000 5M | 55 Undead, 35 Wither |
| Bedrock (2M) | **Necron's Handle 98M**, Implosion / Shadow Warp / Wither Shield scrolls 48M each, Auto Recombobulator 8M | 70 Undead, 50 Wither |

Wither armor pieces are the base for **Necron's / Storm's / Goldor's / Maxor's** armor (combined with the class essence/fragments); Necron's Handle + 3 scrolls → **Hyperion / Valkyrie / Astraea / Scylla** (Handle + Implosion + Shadow Warp + Wither Shield = Hyperion's Wither Impact).

### M7 notable drops (?)

| Item | Chest | Coin cost (?) |
|---|---|---|
| **Dark Claymore** | Bedrock | ~150M (?) |
| **Necron's Handle** | Bedrock/Obsidian | ~100M |
| **Fifth Master Star** | Bedrock/Obsidian | ~(?) |
| Master Skull – Tier 5 | Bedrock | (?) |
| Implosion / Shadow Warp / Wither Shield | Bedrock | ~50M each |
| Thunderlord VII book | Obsidian (?) | (?) |
| Necron Dye | Bedrock | ultra rare |
| Wither armor pieces, Wither Blood, Wither Catalyst, Precursor Gear, Auto Recomb | various | as F7 ×(?) |
| Dragon shards (Apex/Power) | Emerald [W] | (?) |

### Other floors' signature drops (costs (?))

| Floor | Signature drops |
|---|---|
| F1 / M1 | Bonzo's Staff, **Bonzo's Mask** (revive-once helmet), Red Nose, Bonzo Fragment |
| F2 / M2 | Scarf's Studies / Thesis (Intelligence talisman line), Red Scarf, Scarf Fragment, Adaptive Blade (?) |
| F3 / M3 | **Adaptive Armor** (class-adaptive), Adaptive Blade, Professor fragment (?); M3: **First Master Star** |
| F4 / M4 | **Spirit Wing**, **Spirit Bone**, Spirit Pet (Epic/Legendary), Spirit Sceptre (via Spirit Bone), Spirit Shortbow (?), Thorn Fragment; M4: **Second Master Star** |
| F5 / M5 | **Shadow Assassin Armor**, **Livid Dagger**, **Shadow Fury**, Last Breath, Warped Stone, Livid Fragment, Dark Orb (?); M5: **Third Master Star** |
| F6 / M6 | **Giant's Sword**, **Necromancer Lord Armor**, Necromancer Sword, Giant Tooth, Ancient Rose, Precursor Eye, Summoning Ring, Sadan's Brooch, Fel Skull, Recombobulator 3000 [W]; M6: **Fourth Master Star** |
| F7 / M7 | above; M7: **Fifth Master Star** |

Also from all chests: enchanted books (Ultimate Wise, Wisdom, Combo, Swarm, Rend, Last Stand, Legion, No Pain No Gain, Overload, Feather Falling VI, Infinite Quiver VI, Rejuvenate, Bank, Ultimate Jerry, Soul Eater, One For All), Hot Potato Books, Recombobulators, Dungeon Disc / Master Skulls (M-floors), essence.

## 3.15 Essence, Dungeon Stars, Master Stars, dungeonizing

### Essence types [W]

| Essence | Source |
|---|---|
| **Undead** | dungeon chests (guaranteed), Undead mobs |
| **Wither** | dungeon chests, wither-skull secrets |
| **Dragon** | Ender Dragon fights, Dragon-related |
| **Spider** | Spider's Den / Arachne / Tarantula (?) |
| **Ice** | Jerry Pond / Winter Island |
| **Gold**, **Diamond** | Crystal Hollows treasure chests |
| **Crimson** | Crimson Isle / Kuudra |
| Forest, Fossil, Safari | newer areas |

Cap: **2,000,000,000** per type [W]. Bazaar trading needs **Catacombs 20** [W].

### Star upgrades [W]

| Rule | Value |
|---|---|
| Normal stars | **5** per dungeon item, each costs essence (type per item) |
| Boost | **+10% stats per star while in the Catacombs** (outside: +2% per star) |
| Master stars | 1st–5th **Master Star** items (from M3–M7) applied as stars 6–10, **+5% each** |
| Combined max | +50% (5 stars) + 25% (5 master stars) = **+75%** |
| General's Medallion | +1% per digit of secrets counter (max +5%) |

**Example star cost curves (?)** (clone default): `cost(n) = base × [1, 2, 3.5, 6, 10]`. Typical bases (?): early dungeon gear 10–25 Undead; Livid Dagger 30 Wither (?); Wither armor / Hyperion ~150 Wither (?) → 150 / 300 / 525 / 900 / 1,500.

**Dungeonizing (?)** — a non-dungeon item can be converted (via an NPC + essence) to gain dungeon boosts; Hypixel replaced most of this with "dungeonized" auto-status. Clone: allow it only for slayer/drop gear, cost scales with rarity.

## 3.16 Catacombs XP per run, daily bonus, Dungeon RNG Meter

**Base XP per completion (?)** — commonly cited values; wiki confirms F7 base **30,000** [W], which is 1.5× the older 20,000, so Hypixel may have buffed all floors:

| Floor | Base XP (older, (?)) | Master | Base XP (?) |
|---|---|---|---|
| Entrance | 25 | — | — |
| F1 | 80 | M1 | 10,000 |
| F2 | 160 | M2 | 15,000 |
| F3 | 400 | M3 | 36,500 |
| F4 | 1,420 | M4 | 48,500 |
| F5 | 2,000 | M5 | 70,000 |
| F6 | 4,000 | M6 | 100,000 |
| F7 | 20,000 (wiki now 30,000) | M7 | 300,000 |

| Modifier | Value |
|---|---|
| Score | XP scales with score (full at S+; e.g. `XP × clamp(score/300)`) (?) |
| Daily bonus | **First 5 completions each day +40%** [W] |
| Class XP | selected class 100%, others 25% (?) |
| Boosts | Essence-shop perks, Wisdom-like "Catacombs Expert Ring" (?), Mayor Derpy/Paul (?) |
| Failed run | small fraction (?) |

**Dungeon RNG Meter (?):** one meter per floor (F1–F7, M1–M7). Choose an item from that floor's chest table. Each completed run adds meter XP = run score (only if score ≥ S (270) (?)). Full meter → the item appears guaranteed in the next run's chest (you still pay the chest's coin cost). Required XP scales with the item's rarity (e.g. Necron's Handle ≈ 1,000,000 meter XP (?)).

---

# 4. Kuudra (Crimson Isle)

## 4.1 Overview

Kuudra is a giant lava sea-monster fought in **Kuudra's Hollow** on the **Crimson Isle** (Nether). A party of up to **4** players (?) defends and builds a **ballista** on a platform in a lava lake while mob waves attack, then shoots Kuudra; higher tiers add a stun-from-inside phase and (Infernal) a final lair phase. Entry is through the NPC **Elle** at the Kuudra dock. Tiers are gated by **faction reputation** (Mage or Barbarian, see the Crimson Isle doc).

## 4.2 Tiers [W]

| Tier | Faction rep required | Kuudra "XP" reward (collection/skill (?)) | Mob wave interval | Boss collection points |
|---|---|---|---|---|
| **Basic** | none (Elle intro quest) | 20 | 35 s | 1 |
| **Hot** | 1,000 | 40 | 30 s | 2 |
| **Burning** | 3,000 | 60 | 25 s | 3 |
| **Fiery** | 7,000 | 80 | 20 s | 4 |
| **Infernal** | 12,000 | 100 | 15 s | 5 |

Basic/Hot also typically expect Combat ~20+ and Kuudra gear from lower tiers (?).

## 4.3 Kuudra Keys [W]

The run is free; the **paid reward chest** needs the tier's key (consumed).

| Key | Coins | Barbarian mats | Mage mats | Rep |
|---|---|---|---|---|
| Kuudra Key | 200,000 | 2 Enchanted Red Sand + 2 Nether Stars | 2 Enchanted Mycelium + 2 Nether Stars | 0 |
| Hot Kuudra Key | 400,000 | 6 E. Red Sand + 2 Nether Stars | 6 E. Mycelium + 2 Nether Stars | 1,000 |
| Burning Kuudra Key | 750,000 | 20 E. Red Sand + 2 Nether Stars | 20 E. Mycelium + 2 Nether Stars | 3,000 |
| Fiery Kuudra Key | 1,500,000 | 60 E. Red Sand + 2 Nether Stars | 60 E. Mycelium + 2 Nether Stars | 7,000 |
| Infernal Kuudra Key | 3,000,000 | 120 E. Red Sand + 2 Nether Stars | 120 E. Mycelium + 2 Nether Stars | 12,000 |

Crafting: **4 keys of a tier → 1 key of the next tier** [W]. (Bought from the faction's Emissary.)

## 4.4 Fight phases [W]

| # | Phase | Tiers | Objective | Details |
|---|---|---|---|---|
| 1 | **Supplies** | all | Bring supply crates to the centre | Crates are fished out of the lava with a rod / picked from spawn spots (?) and carried to the arena centre; placing one requires **standing still while a meter fills to 100%**. Need **6** supplies (?). Mobs harass carriers. |
| 2 | **Build (Ballista)** | all | Build the ballista to 100% | Players **sneak + click** supply piles to contribute build %; mobs attack builders and can **reset progress** on a pile. More builders = faster (?). |
| 3 | **Fuel & Fire** | all | Fill the ballista with **4 fuel cells** and fire at Kuudra | Fuel cells fished from the lava / dropped by mobs (?). Basic/Hot: each ballista shot does a big chunk of Kuudra's HP. **Burning+**: the ballista does very little unless Kuudra is **stunned** first. |
| 4 | **Stomach (Stun)** | Burning, Fiery, Infernal | Stun Kuudra from inside | A player enters Kuudra's belly — **launched by cannon** or **grabbed by a tentacle** — and **breaks the orbs inside with a pickaxe**; when all are broken Kuudra is **stunned** → ballista + party DPS window. Repeat fuel → stun → fire cycles. |
| 5 | **Lair** | Infernal only | Kill Kuudra's head | At **25% HP** the arena collapses and the fight moves **below into the Lair**; dodge tentacles, attack the head directly (no ballista). **Deaths here are permanent unless a Support-route player revives you.** |

**Kuudra HP (?):** Infernal Kuudra has **~800M HP**, of which the last **25% (200M)** is fought in the Lair without the ballista [search snippet]. Lower tiers: smaller pools — clone default Basic 10M, Hot 25M, Burning 60M, Fiery 150M, Infernal 800M (?), with ballista shots doing a fixed % of max HP in Basic/Hot and only during stun on Burning+.

**Mob waves [W]:** up to 20+ waves, interval per tier (35 → 15 s). Mobs: **Kuudra Followers**, **Landmines**, **Magma Cubes**, **Berserkers**, **Blights**, **Knockers**, and late **Dropships** (worth **150 tokens** each — destroy them before they land troops) and **Chaosmites** (Infernal).

**Tokens & perk shop:** kills and objectives award **tokens** spent mid-run in the **Kuudra perk shop** on **routes and upgrades** [W] — e.g. faster supply pickup, build speed, cannon access, pickaxe mining speed, auto-revive, and the **Support** route (revives in the Lair). Perks reset each run.

**Roles (community):** Supply/Builder, Cannon/Stun (goes in the belly), DPS (Terror/Crimson armor), Support/Healer (Infernal).

## 4.5 Loot [W]

**Free chest** (everyone): Crimson Essence — Basic 1–2, Hot 14–22, Burning 25–30, Fiery 80–120, Infernal 200.

**Paid chest** (needs key) — two rolls:

| Slot | Contents |
|---|---|
| **Slot 1** | **Armor pieces** of the five Kuudra sets (Crimson, Aurora, Terror, Fervor, Hollow — in the run's tier's prestige), Kuudra weapons, **attribute / pet shards**, Molten accessories (?) |
| **Slot 2** | Enchanted books (**Fatal Tempo**, **Inferno**, Mana enchants: Strong Mana, Ferocious Mana, Hardened Mana, Mana Vampire (?)), **Kuudra Teeth (5–100)**, **Crimson Essence (500–10,000)**, shards |
| Rare | **Wheel of Fate**, **Enrager**, **Burning Kuudra Core**, **Tentacle Dye**, **Kuudra Mandible** (Infernal; ~0.005% [W]), Hollow Wand / Aurora Staff / Fire Fury Staff (?) |

Chances per item are not published in the fetched data; clone defaults (?): armor piece 40% of slot 1, shard 50%, weapon 10%; slot 2: book 30%, teeth 35%, essence 35%; rares 0.01–0.5% scaling up with tier.

## 4.6 Kuudra armor sets

| Set | Faction / role | Signature stat |
|---|---|---|
| **Crimson** | Barbarian — melee/Strength | Strength, Crit Damage; set bonus scales damage |
| **Aurora** | Mage — Intelligence | Intelligence, mana regen; set bonus boosts ability damage |
| **Terror** | Archer — Crit | Crit Damage; set bonus adds ferocity/arrow hits (?) |
| **Fervor** | Attack speed | Attack Speed, Health |
| **Hollow** | Generalist / health & regen | Health, Mending |

**Prestige:** each piece exists in Basic → Hot → Burning → Fiery → Infernal tiers. Upgrade = **10 Crimson-Essence stars per tier** then **prestige** with **Kuudra Teeth + Crimson Essence** to the next tier (?), which resets stars but raises base stats.

## 4.7 Attributes & attribute shards

**Legacy model (2022–mid-2025)** — recommended for the clone because it's self-contained:

| Rule | Value |
|---|---|
| Item attributes | Each Kuudra armor piece / Crimson Isle item rolls **2 attributes** |
| Attribute levels | I–X |
| Combine | Fuse two items with the same attribute: level = combine (I+I → II, II+II → III …) → reaching level n needs **2ⁿ⁻¹ level-I** sources |
| Shards | **Attribute Shards** (single attribute) drop from Kuudra, Crimson fishing, slayer bosses (e.g. Revenant Shard, Inferno Demonlord Shard) and add to items of the same type |
| Pool (examples) | Arachno, Arachno Resistance, Attack Speed, Blazing, Blazing Resistance, Breeze, Combo, Deadeye, Dominance, Double Hook, Elite, Ender, Ender Resistance, Experience, Fisherman, Fishing Speed, Fortitude, Hunter, Ignition, Infection, Life Recovery, Life Regeneration, Lifeline, Magic Find, Mana Pool, Mana Regeneration, Mana Steal / Vitality, Midas Touch, Speed, Trophy Hunter, Undead, Undead Resistance, Veteran, Warrior |
| Effect | Each attribute has a per-level value (e.g. Mana Pool +20 Int/lvl (?), Dominance +1.5% damage at full HP/lvl (?)) |

**2025 rework (?)** — the Galatea/Foraging update moved attributes to a **per-player Attribute menu** fed by **shards** collected through a new **Hunting** skill (shards from mobs and fusion), and the old item attributes were converted. The Revenant/Tarantula loot tables listing "Revenant Shard", "Primordial Shard", "Paragon Shard" are from this new system. **Clone recommendation:** ship the legacy item-attribute model first (it ties Kuudra loot to gear progression); keep shard drops in loot tables as data so either model can consume them.

---

# 5. How it connects

```
           Combat skill (mobs everywhere) ───────────────┐
                 │ Combat 5/10/15/25 gates slayer tiers   │ Combat 15 gates Catacombs
                 ▼                                        ▼
  Hub mobs ─► SLAYERS (Maddox) ──drops──► slayer gear ─► stronger player ─► DUNGEONS
     Rev → Tara → Sven ─► Voidgloom ─► Inferno        (Reaper Mask needs Zombie 6 + Cata 24;
     (Vampire in Rift: Sven 3)                          Terminator needs Ender 7 + Cata 27;
         │                                              Juju needs Ender 5 + Cata 15)
         │ Slayer XP → levels → recipes, stat bonuses      │
         │                                                 ▼
         │                                   Catacombs XP → floor unlocks → Master Mode
         │                                   Chest loot (coins sink!) → Wither armor,
         │                                   Hyperion, Terminator mats, Master Stars
         ▼                                                 │
  Crimson Isle (Blaze slayer, factions, rep) ─────────────►│
         │ rep 1k/3k/7k/12k                                │
         ▼                                                 ▼
       KUUDRA ──► Kuudra armor + attributes ──► best-in-slot for Kuudra + slayers
         │        Kuudra Teeth, Crimson Essence, Fatal Tempo / Inferno books
         ▼
   Economy: every chest / key / quest is a COIN SINK; drops feed the Auction House and Bazaar.
```

| From | To | Link |
|---|---|---|
| Combat skill | Slayer tiers | Combat V/X/XV/XXV gate T2/T3/T4/T5 |
| Combat skill | Catacombs | Combat 15 to enter |
| Slayer drops | Dungeon gear | Reaper Mask (Beheaded Horror), Terminator (Judgement Cores), Juju, Atomsplit, Final Destination armor for Voidgloom |
| Slayer level | Recipes | each slayer level unlocks crafting recipes (§2.5–2.10) |
| Catacombs level | Item use | Cata level requirements on dungeon items (and some slayer items) |
| Dungeon chests | Coins | chests cost 25k–100M+ → main late-game coin sink |
| Dungeon drops | Every system | Hyperion (best mage weapon), Wither armor, Giant's Sword, Spirit Sceptre, Bonzo's Mask; Recombobulators; Hot Potato Books; Ultimate enchants |
| Essence | Stars | Wither/Undead/Dragon/Spider/Crimson essence upgrade gear |
| Crimson Isle factions | Kuudra | rep gates tiers; keys bought from the faction Emissary |
| Kuudra | Slayers/dungeons | Kuudra armor + attributes (Mana Pool, Dominance, Lifeline …) power slayer and dungeon builds |
| Mayors | All three | Aatrox (slayer XP/cost/drops), Paul (+10 score, cheaper chests), Derpy/Finnegan etc. (?) — see doc 05 |
| Bits | Booster Cookie | Slayer kills give bits (2–15) |
| SkyBlock XP / level | Profile | slayer levels (15–150 XP), catacombs/class levels, collection-style boss kills |
| Collections | Boss collections | Dungeon floor boss kills & Kuudra kills fill "boss collections" (unlock recipes like Bonzo items at N kills) (?) |
| Pets | Combat | Spirit pet (score), Golden Dragon / Ender Dragon (damage), Wisp pets (Blaze slayer), Mosquito pet (Spider 7) |

---

# 6. Implementation notes

## 6.1 Shared combat primitives (needed by all bosses)

| Primitive | Description |
|---|---|
| `BossEntity` | HP (long), damage, defense, level, nametag template, owner/party, timers map, `invulnerable: bool`, `shield: Shield?`, immunities (ability damage, melee, arrows) |
| `Shield` kinds | `HitShield(hitsRemaining)` (Voidgloom), `AttunementShield(colour, hitsToSwap=8, reduction=0.99)` (Inferno), `Invulnerable(reason)` (Sven pups, Web of Lies, Thorn, Goldor) |
| `Telegraph` | Ground circles/beams drawn with particles + title/subtitle text **before** damage lands (TNT circles, Twinclaws, Broken Heart beams, Thermonuclear ring) |
| `ExecuteRule` | Damage cannot reduce HP below X% unless the hit is from item Y (Steak Stake) |
| `ThresholdTrigger` | `onHpBelow(pct, once=true)` → callbacks (Demonsplit 66/33, Web of Lies 66/33, Pups 50, Nukekubi 33) |
| `TrueDamage` | Ignores Defense; still reduced by True Defense |
| `PercentMaxHpDamage` | Damage as % of the victim's max HP (Storm lightning 75%, Necron beam 25%, bats 0.1–0.2%) |
| `Boss bar` | Name + HP bar + phase name; for slayers also the quest **timer** |
| `Lootshare` | Damage contribution tracker per player |

## 6.2 Slayer quest state machine

```
IDLE --start(tier, pay)--> COLLECTING(bar=0/target)
COLLECTING --kill eligible mob--> COLLECTING(bar += mob.combatXp)
COLLECTING --bar >= target--> SPAWNING (2 s animation, lightning/particles, boss materialises)
SPAWNING --> FIGHTING (timer 180 s)
FIGHTING --boss HP 0 (and execute rules satisfied)--> BOSS_SLAIN (loot, heal-over-time)
FIGHTING --timer 0 | player death | player leaves zone-->  FAILED ("SLAYER QUEST FAILED!")
BOSS_SLAIN --claim at Maddox/Batphone or auto-slayer--> IDLE (+slayer XP, +RNG meter, level-up msgs)
FAILED --> IDLE (no refund)
```

**What the player sees:** chat line "SLAYER QUEST STARTED!"; sidebar "Slayer Quest: Revenant Horror IV — (1,234/4,800) Combat XP"; boss spawn title "☠ Revenant Horror IV" with "Spawned by: you" nametag; HP in nametag "☠ Revenant Horror 1.2M❤"; quest timer in the sidebar "Time left: 2:41"; on kill: "NICE! SLAYER BOSS SLAIN!", loot chat lines with rarity colour ("RARE DROP! (Beheaded Horror) (+X% ✯ Magic Find)"), "Zombie Slayer LVL 6 - Next LVL in 5,320 XP!", RNG meter bar in chat.

### Per-boss fight state machines

**Revenant T1–T4**
```
FIGHT: every 3-5 s LifeDrain; (T2+) Pestilence aura tick 1 s, armor shred 25%
       (T3+) t=40 s -> MAD (5 s, red particles) -> ENRAGED 12 s (speed↑, dmg×2 (?)) -> back to FIGHT, repeat each 40 s
```
**Revenant T5 (Atoned Horror)**
```
FIGHT: regen 0.1%/s; TNT volley every ~4 s at marked circles (2x rate under 33%), TNT hit => boss +500k HP
       every 30 s: THERMONUCLEAR: root boss, show expanding bedrock ring 7 blocks, 5 s countdown title,
                   lightning burst: kill/huge dmg inside 7 blocks (ignores protection) -> FIGHT
       ability damage immune
```
**Tarantula**
```
FIGHT (leap attacks, backstab, paralysis)
  hp<66% & tier>=3: WEB_OF_LIES -> invulnerable, spawn 2 (T5: 4) egg sacs (3 / 9 hit-HP) -> all broken -> FIGHT
  hp<33% & tier>=3: WEB_OF_LIES again
  tier>=4: BAT_BARRAGE periodic
  T5 hp=0: TILL_DEATH -> cutscene 2 s -> CONJOINED_BROOD (20M HP, 4400 dps, passives only) -> dead
```
**Sven**
```
FIGHT (fast, true dmg T2+)
  hp<50% & tier>=3: PUPS -> boss invulnerable+rooted; spawn pup 1..5 sequentially (each 10% boss max HP)
                    all pups dead -> FIGHT
```
**Voidgloom**
```
SHIELDED(hits=15/30/60/100) --each hit-> hits-1 ; 0 -> UNSHIELDED
UNSHIELDED (ferocity -75%) --hp crosses next third-> SHIELDED (refill)
parallel timers: DISSONANCE teleport+AoE (T2+); YANG_GLYPH every ~10 s (T2/3+): beacon 5 s fuse
  hp<33% & T3+: spawn NUKEKUBI heads (repeating), dmg doubling per alive head
  T4: periodically BROKEN_HEART (invulnerable, 12 rotating beams) ~8 s
```
**Inferno**
```
PHASE_A (Immolate aura by HP%) with HELLION shield (T2+): colour cycles Ashen->Spirit->Auric->Crystal per 8 matching hits
  FIRE_PILLAR (T2+): pillar spawns, needs 8 hits in 7 s else wipe
  DDR_APOCALYPSE (T3+): fire pit grid pattern
  hp hits split threshold -> DEMONSPLIT: boss invulnerable; spawn Quazii + Typhoeus (own attunement cycles)
         both dead -> PHASE_A continues
```
**Riftstalker**
```
FIGHT: Coagulation tick 1.5 s; Spectral Form teleports
  periodic TWINCLAWS (T2+, 7 s), IMPEL prompts (T3+), KILLER_SPRING (T3+, 15 s), STONEWRATH (T2+)
  hp thresholds -> MANIA (invulnerable 26 s, safe ring)
  T5: BLOOD_ICHOR spawns, 25 s to break via Twinclaws
  hp <= executeThreshold -> EXECUTABLE (only Steak Stake damage) -> dead
```

## 6.3 Dungeon run — instancing

| Concern | Recommendation |
|---|---|
| Instance allocation | Pre-allocate **N grid slots** in one void world, each slot 2,048×2,048 blocks apart (instance i at x = i·2048). Paste room schematics into the slot at run start (async paste, ~200 rooms max). Cheaper than a world per run. A world-per-instance (copy template folder) is simpler but heavier. |
| Generation | Server-side `DungeonGenerator(floorCfg, seed)` → layout JSON → paster. Keep **layout data** separate from block pasting so it can be unit-tested headless. |
| Boss arena | Per-floor fixed schematic placed beside the room grid; Blood room portal teleports into it. |
| Lifetime | Instance lives until all players have claimed/left + 60 s; then reset (clear blocks / re-paste on next use). |
| Timeouts | Hard cap ~45 min (?) → fail. |
| Disconnects | Player rejoining within the run's lifetime is put back in the instance (as ghost if they were dead). |
| Entity budget | Spawn room mobs **on first entry**, not at generation (keeps entity count low). |
| Map item | Server renders the magical map per player from layout + discovered-state. |
| Determinism | Seed stored with the run → reproducible for bug reports. |

## 6.4 Dungeon run state machine

```
QUEUED (party at portal, floor chosen, requirements checked)
  -> ALLOCATING (generate + paste)              [loading screen / "Starting in 5…"]
  -> COUNTDOWN (5 s, start-room door closed)
  -> CLEARING
       per-room state: HIDDEN -> ADJACENT(?) -> ENTERED (spawn mobs) -> CLEARED -> ALL_SECRETS
       wither doors: LOCKED -> (party has key & player clicks) -> OPEN (key--)
       puzzle rooms: UNSOLVED -> SOLVED | FAILED
  -> BLOOD_OPEN (blood key used: Watcher starts spawning)
  -> BLOOD_CLEARED (all blood mobs dead; portal opens)  [players may still clear rooms]
  -> BOSS (first player enters boss portal; at that moment room-clear% and secrets are still counted
           only if found before? — Hypixel counts until end of run; clone: freeze exploration at boss
           entry to keep the "clear first" incentive (?))
       floor-specific boss FSM (below)
  -> COMPLETE (boss dead): compute score; spawn chests; award XP; RNG meter
  -> CLAIMING (60 s+, players open chests; unopened -> Croesus)
  -> CLOSED (teleport to Dungeon Hub; free instance)
FAIL edges: all players dead | timeout | Wither King ritual | relic mismatch -> FAILED -> CLOSED
```

**F7/M7 boss FSM**
```
P1_MAXOR: guards/miners spawn; crystals spawn on ledges
   crystal placed x2 -> LASER_ON: maxor vulnerable (first time capped at 75% loss) -> ENRAGED (invuln) -> crystals respawn
   laserCount>=2 && hp<=0 -> P2
P2_STORM: lightning cycle; pads -> crusher drop; crushed -> STUNNED 10 s (vulnerable)
   crushedCount>=2 && hp<=0 -> P3   (guard: if crushersLeft==0 && hp>0 -> respawn crushers, never softlock)
P3_GOLDOR: section s=1..4: {terminals[], levers[2], device} all done -> GATE_DELAY 5 s -> s+1
   goldor walks the corridor; contact => heavy damage
   s==4 done -> GOLDOR_CORE (vulnerable) -> hp<=0 -> P4
P4_NECRON: platform destroy cutscene; attack rotation; hp<80% -> FRENZY (radius grows w/o damage); hp<X -> FRENZY 2
   hp<=0 -> (F7) COMPLETE  | (M7) P5
P5_WITHER_KING: RELICS (5 relics -> cauldrons; mismatch -> FAIL) -> DRAGONS
   every 30 s spawn next dragon (cycle); dragon killed in own statue zone -> statue destroyed
   aliveDragons==5 -> RITUAL -> FAIL ; statuesDestroyed==5 -> COMPLETE
```

## 6.5 Party logic

| Feature | Rule |
|---|---|
| Party | Leader, invites (60 s expiry), max 5 (dungeons) / 4 (Kuudra), transfer leader, kick, disband |
| Class pick | Per-player class stored on profile; leader can't force it; duplicates allowed |
| Requirement check | At queue time, every member must meet floor/tier gates; otherwise list who fails |
| Party Finder | Listing object {leader, floor, minClass, minCata, note, slots}; auto-delist when full / started |
| Shared state | Keys (wither/blood), blessings, puzzle state, score are **party-scoped**; inventory & class are player-scoped |
| Ghost players | Can move/spectate, use ghost abilities, can't open chests or pick up keys/secrets |
| Loot | Each player opens their **own** chest set (loot rolled per player); one claim each (+ key) |

## 6.6 What the player sees (UI checklist)

| Moment | UI |
|---|---|
| Start | Title "The Catacombs — Floor VII", Mort dialogue, 5-4-3-2-1 countdown |
| Room entered | Room name in action bar (template name), mobs spawn, star mobs glow (?) |
| Room cleared | Sound + map tick; "A Wither Key was picked up!" broadcast |
| Door | Black/red door block; "This door requires a Wither Key" if none |
| Puzzle | Room name "Puzzle: Creeper Beams"; on solve "PUZZLE SOLVED! … obtained Blessing of Power V"; on fail "PUZZLE FAIL! … " + red ✖ |
| Death | Chat "☠ <player> was killed by Withermancer and became a ghost." ; ghost hotbar |
| Blood | Watcher lines in chat; spawn sounds |
| Boss | Boss bar, phase titles, dialogue lines, mechanic titles ("TERMINAL 3/4 completed by …") |
| End | Score screen: "Team Score: 305 (S+)", breakdown Skill/Exploration/Speed/Bonus, XP gained per class, then chests spawn in the arena |
| Chest GUI | Chest contents shown with "Open Reward Chest — Cost: 2,000,000 coins + Necron's Handle 98,000,000"; Kismet reroll button; "Use Dungeon Chest Key" |
| Kuudra | Sidebar: phase, supplies 3/6, build %, fuel 2/4, tokens; tentacle warnings; stun timer |

## 6.7 Data files the build agents should create

| File | Contents |
|---|---|
| `slayers.json` | per slayer: unlock, tiers[{hp, dmg, cost, spawnXp, slayerXp, reqCombat, abilities[]}], levelXp[], levelRewards[], lootTable[] (weights/chances, minLevel, qty per tier) |
| `slayer_abilities.json` | ability id → params (timers, thresholds, damage) |
| `catacombs_levels.json` | XP table + stat boost per level |
| `classes.json` | passives, abilities, per-level scaling |
| `floors.json` | per floor: grid, puzzles count, wither doors, mob pool, miniboss pool, blood pool, boss id, speed limit, secret %, chest costs, loot tables |
| `rooms/*.json` + schematics | template id, shape, secrets, mob spawns, crypts, door sockets |
| `puzzles.json` | puzzle ids, floors, failable, variants |
| `kuudra.json` | tiers, rep, keys, waves, mobs, perk shop, loot |
| `attributes.json` | attribute list, per-level effects, shard sources |

---

# 7. Progression placement

Typical player journey (hours are rough playtime estimates for a competent player (?)):

| Stage | Combat lvl | Content | Gear target |
|---|---|---|---|
| Early (0–20 h) | 5–15 | Revenant T1–T3, Tarantula T1–T2 | Revenant Falchion, early dragon armor |
| Early-mid (20–60 h) | 15–20 | Catacombs Entrance–F3; Revenant T4, Tarantula T3, Sven T1–T3 | Zombie/Tarantula armor, Bonzo's Mask, Adaptive |
| Mid (60–150 h) | 20–25 | F4–F5 (Cata 9–19); Sven T4, Voidgloom T1–T2 | Spirit Sceptre, Shadow Assassin, Livid Dagger, Mastiff/Final Destination |
| Mid-late (150–400 h) | 25–30 | F6–F7 (Cata 19–30); Voidgloom T3, Inferno T1–T2; Kuudra Basic–Burning | Necron's armor, Giant's Sword, Juju, Kuudra armor, Atomsplit |
| Late (400–1,000 h) | 30–50 | M1–M5; Voidgloom T4, Inferno T3–T4, Revenant/Tarantula T5; Kuudra Fiery | Hyperion, Terminator, Reaper/Warden, master stars |
| Endgame (1,000 h+) | 50+ | M6–M7, Kuudra Infernal, slayer 9s, Cata 50 | Dark Claymore, Fifth Master Stars, Infernal Kuudra armor, max attributes |

**Pacing guidance for the clone:** each pillar should hand off to the next — slayer drops make the first dungeon floors comfortable; dungeon gear makes the next slayer tier possible; Crimson Isle rep (which needs slayer/dungeon power) opens Kuudra; Kuudra armor loops back into T4/T5 slayers and Master Mode.

---

# 8. Open questions / least-sure list

| # | Item | Why unsure | Default chosen |
|---|---|---|---|
| 1 | Dungeon reward chest per-floor costs & which chest tiers appear on F1–F4; M7 item coin costs; dungeon RNG meter XP rules | Only F7 costs and generic ranges were retrievable | Linear interpolation F1→F7; RNG meter fills with score on S/S+ runs |
| 2 | Kuudra HP per tier, supply count, paid-chest drop chances, perk-shop list; whether the 2025 attribute rework removed item attributes | No Kuudra boss page with numbers was retrievable; attribute rework details are hazy | Infernal 800M (200M lair); 6 supplies; legacy attribute model |
| 3 | Voidgloom damage (two conflicting sets), Blaze/Spider level-1 XP, Three Weirdos exact rule, Blaze-puzzle order direction, F7 device order, exact slayer recipe quantities | Sources conflict or omit | Wiki base dmg + shield bonus; Wolf curve for Blaze; generated logic puzzle |
| 4 | Catacombs XP per floor (wiki F7 = 30,000 vs older 20,000) | Possible 2024 buff | Older table ×1.5 if adopting the buff |
| 5 | Slayer RNG meter required XP per item | Not fetched | `slayerXP_max / p_max × 0.75` formula |
