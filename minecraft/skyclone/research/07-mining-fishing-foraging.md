# 07 — Mining, Fishing, Foraging (gathering skills reference)

> Research note for the SkyBlock clone. Target: build agents implementing the three
> "gathering" pillars straight from this file. Original Hypixel names are used here;
> renaming happens later.
>
> **Sources & confidence.** The official wiki (wiki.hypixel.net) shut down in July 2026 and
> now redirects to a forum post; the Fandom wiki returns HTTP 402 to automated fetches. Numbers
> marked as *wiki* were pulled on 2026-10-02 from the community wiki that replaced it,
> `hypixelskyblock.minecraft.wiki`. Everything else is from knowledge of the game up to mid-2026.
> **Anything followed by (?) is uncertain — treat it as a tuning placeholder, not as gospel.**
> A clone does not need Hypixel's exact numbers; it needs a *consistent* system. Where a value is
> uncertain, the shape of the formula is usually what matters, and the shape is given.
>
> **Recent updates worth knowing about (they changed a lot):**
> - **Glacite / Dwarven Mines update (Apr 2024)**: added Glacite Tunnels, Glacite Mineshafts,
>   Frozen Corpses, Fossils, Glacite Powder, and HOTM tiers 8–10 (the tree grew from 7 to 10).
> - **HOTM rework + Mining stat rework (late 2024)**: many perks reshuffled. Mining Fortune was split
>   into Ore / Block / Dwarven Metal / Gemstone fortune. **Mining Spread** and **Gemstone Spread**
>   replaced the old "Efficient Miner" area-break perk. Abilities were given levels 1–3.
> - **Fishing update (2024)**: Backwater Bayou, **Hotspots**, modular **rod parts** (hook / line /
>   sinker), new Crimson Isle hotspot creatures (Fried Chicken, Fiery Scuttler, Ragnarok…).
> - **Foraging update / Galatea (mid-2025)**: the **Galatea** archipelago (Moonglade Marsh, then
>   Torrhus Canyon), the **Sweep** stat (replacing the "logs per Treecapitator swing" rule),
>   tree **toughness** and **cutting order**, **Tree Gifts**, **Heart of the Forest** (a HOTM-style
>   tree paid in **Forest / Desert Whispers**), Agatha's/Miria's **Starlyn Contests**, and the
>   **Hunting** skill with **Shards** + attribute fusion (Hunting is mostly covered elsewhere).
> - **2026**: Fossils trimmed (Helix and Claw fossils removed 2026-04-30 *wiki*); new fishing zones
>   *Lotus Atoll* and *Torrhus Canyon* sea creatures appear on the wiki (?).

---

## Table of contents
1. Overview — how the three skills are shaped
2. MINING
   - 2.1 Breaking Power tiers
   - 2.2 Block Strength table
   - 2.3 Mining Speed → ticks-to-break formula
   - 2.4 Mining Fortune family, Pristine, Spread
   - 2.5 Mining areas & ore regeneration
   - 2.6 Powders
   - 2.7 Heart of the Mountain (tiers, tokens, every perk, abilities, Core)
   - 2.8 Commissions & milestones
   - 2.9 Dwarven Mines (King, Forge, daily NPCs, Sky Mall)
   - 2.10 Mining events
   - 2.11 Crystal Hollows (zones, gemstones, structures, crystals, Nucleus, Heat, chests)
   - 2.12 Gemstones (tiers, stats, slots)
   - 2.13 Glacite Tunnels, Cold, Mineshafts, Corpses
   - 2.14 Fossils & the Excavator
   - 2.15 Pickaxes, drills, drill parts, fuel
   - 2.16 The Forge (key recipes / timings)
   - 2.17 Mining skill
3. FISHING
   - 3.1 The catch loop
   - 3.2 Fishing Speed formula
   - 3.3 Sea Creature Chance & weights
   - 3.4 Sea creatures — every table
   - 3.5 Treasure catches
   - 3.6 Lava fishing & rods (water vs lava)
   - 3.7 Trophy Fishing
   - 3.8 Fishing Festival / sharks
   - 3.9 Baits
   - 3.10 Rod parts (Fishing update)
   - 3.11 Hotspots
   - 3.12 Fishing skill & gear
4. FORAGING
   - 4.1 Logs & XP
   - 4.2 Tree areas (Hub, Park, Galatea)
   - 4.3 Sweep, Foraging Fortune, Treecapitator
   - 4.4 Galatea trees: toughness, cutting order, Tree Gifts
   - 4.5 Heart of the Forest
   - 4.6 Starlyn Contests (Agatha / Miria)
   - 4.7 Foraging skill, axes, armor
5. How it connects
6. Implementation notes (custom block breaking, regen, fishing hook, what the player sees)
7. Progression placement

---

# 1. Overview

All three skills share one skeleton, and a build team should implement that skeleton ONCE:

| Concept | Mining | Fishing | Foraging |
|---|---|---|---|
| Action | Hold left-click on a block | Cast, wait, reel | Hold left-click on a log (or throw axe) |
| "How fast" stat | **Mining Speed** (ticks to break) | **Fishing Speed** (ticks until bite) | **Sweep** (logs per break, also break speed) + Foraging speed |
| "How much" stat | **Mining Fortune** (+ Ore/Block/Metal/Gemstone Fortune) | **Treasure Chance**, **Double Hook Chance** | **Foraging Fortune** (+ per-tree fortunes on Galatea) |
| "Which tier" gate | **Breaking Power** (tool) | Fishing level (creature unlocks), rod type (water vs lava) | Foraging level / HotF tier (area access), **Toughness** vs Sweep |
| "Rare thing" stat | Pristine (flawed gems), Luck of the Cave, Tunnel Vision | **Sea Creature Chance** + rare-creature weight modifiers | Tree Gift loot luck ("Luck of the Forest") |
| Second-currency tree | **Heart of the Mountain** (3 powders) | (none — rod parts + pets + armor) | **Heart of the Forest** (2 whispers) |
| Daily loop | Commissions (4 bonus/day) | Hotspots, Trophy fish, festivals | Starlyn Contests (3 bonus/day), Tree Gifts |
| Combat hook | Goblins, Automatons, Corleone, Bal, Corpses | **Sea creatures are mobs you kill** | Galatea critters / Hunting |
| Wisdom stat | Mining Wisdom | Fishing Wisdom | Foraging Wisdom |

Generic stat semantics (copy these exactly, they are used everywhere in SkyBlock):
- **"X Fortune"**: `drops = base × (1 + X/100)`; the fractional part is a probability of one more.
  e.g. 250 fortune on a 1-drop block → 3 guaranteed + 50% chance of a 4th.
- **"X Wisdom"**: `xp = base × (1 + X/100)`.
- **"X Chance" stats** cap at 100 *except* Sea Creature Chance, Crit Chance, Bonus Pest Chance
  (*wiki*: "SCC, Crit Chance and Bonus Pest Chance have no cap"). SCC over 100 still means 100% —
  it is "uncapped" only in the sense that the number is allowed to display/stack past 100 (?).

---

# 2. MINING

## 2.1 Breaking Power (BP)

Each pickaxe/drill has a **Breaking Power** number; each block has a minimum BP. If tool BP < block
BP, the block simply cannot be broken (Hypixel shows the cracking animation never progressing, and
an action-bar/chat hint *"You need a tool with a Breaking Power of X to mine Y"*).

*wiki* BP requirement tiers:

| BP | Blocks |
|---|---|
| 0 | Most vanilla blocks (dirt, sand, gravel, wood, etc.) |
| 1 | Stone, Cobblestone, Coal Ore, End Stone, Netherrack, Chloronite (?) |
| 2 | Iron Ore, Lapis Lazuli Ore |
| 3 | Gold, Diamond, Emerald, Redstone Ore + all **Pure** ore blocks (Pure Coal/Iron/Lapis/Diamond/Emerald/Gold/Redstone) |
| 4 | **Hard Stone**, **Mithril**, Obsidian |
| 5 | **Titanium** |
| 6 | **Ruby** gemstone (block + pane), Anthill (?) |
| 7 | Amber, Amethyst, Jade, Sapphire, Opal (?) |
| 8 | Topaz, Jasper (?) |
| 9 | Aquamarine, Onyx, Citrine, Peridot, **Glacite**, **Umber**, **Tungsten**, Sulphur Ore (?) |
| 10 | Divan's Drill tier (no block currently requires 10; it's headroom) (?) |

The exact 7/8/9 split for the gemstones is uncertain (the wiki page lumps "7–9" together); the
safe rule is **Ruby 6, Amber/Amethyst/Jade/Sapphire 7, Topaz/Jasper/Opal 8, Glacite-tunnel
gems + Glacite/Umber/Tungsten 9** (?).

Tool BP ladder (the main progression spine):

| Tool | BP | Notes |
|---|---|---|
| Wooden / Gold pickaxe | 1 | vanilla tier |
| Stone pickaxe | 2 | |
| Iron pickaxe | 3 | |
| Diamond pickaxe | 4 | |
| Mithril Pickaxe | 5 | Dwarven Mines early |
| Refined Mithril Pickaxe | 6 | Forge |
| Titanium Pickaxe | 6 (?) | |
| Refined Titanium Pickaxe | 7 (?) | |
| Stonk | 4 (?) (instamines with Efficiency 6 on some blocks) | |
| Pickonimbus 2000 | 7 | Crystal Hollows chest drop; 5000 durability, breaks |
| Mithril Drill SX-R226 / SX-R326 | 5 / 6 | *wiki* |
| Ruby Drill TX-15 | 7 | *wiki* |
| Titanium Drill DR-X355 / X455 | 7 / 8 | *wiki* |
| Gemstone Drill LT-522 | 8 | *wiki* |
| Topaz Drill KGR-12, Jasper Drill X | 9 | *wiki* |
| Titanium Drill DR-X555 / X655 | 9 | *wiki* |
| Divan's Drill | 10 | *wiki* |
| Bingonimbus 2000 | 7 (?) | Bingo-profile pickaxe |

## 2.2 Block Strength

Hypixel replaces vanilla hardness with a per-block **Block Strength**. *wiki* values:

| Block | Strength | Instamine speed (see 2.3) | Category |
|---|---:|---:|---|
| Netherrack | 8 | 241 | standard |
| Stone | 15 | 451 | standard |
| Cobblestone | 20 | 601 | standard |
| End Stone | 30 | 901 | standard |
| Coal / Iron / Gold / Lapis / Redstone / Emerald / Diamond / Nether Quartz ore | 30 | 1,800 | ore (60× rule) |
| Hard Stone (stone variant) | 50 | 1,501 | standard |
| Hard Stone (terracotta / clay variant) | 100 | 3,001 | standard |
| Obsidian | 500 | 30,001 | 60× rule |
| Mithril — Gray wool / Cyan clay / Prismarine variants | 500 | 30,001 | dwarven metal |
| Mithril — Light Blue wool ("high-quality") | 1,500 | 90,001 | dwarven metal |
| Titanium (polished diorite) | 2,000 | 120,001 | dwarven metal |
| **Pure ore blocks** (Pure Coal/Iron/…, Dwarven Mines/Deep Caverns block forms) | 600 | 36,000 | ore |
| Ruby gemstone | 2,300 | 138,001 | gemstone |
| Amber / Amethyst / Jade / Opal / Sapphire | 3,000 | 180,001 | gemstone |
| Topaz | 3,800 | 228,001 | gemstone |
| Jasper | 4,800 | 288,001 | gemstone |
| Aquamarine / Citrine / Onyx / Peridot | 5,200 | 312,001 | gemstone |
| Umber | 5,600 | 336,001 | dwarven metal |
| Tungsten | 5,600 | 336,001 (?) | dwarven metal |
| Glacite | 6,000 | 360,001 | dwarven metal |
| Sand / gravel / dirt / clay (vanilla) | ~ vanilla hardness ×30 (?) | — | standard |
| Wood logs (when mined, not foraged) | 20 (?) | — | standard |

Notes:
- **Gemstone panes** (stained glass panes) and **blocks** (stained glass) share the gem's strength;
  panes drop fewer rough gems (?) — implement as: block = 1 base drop, pane = 1 base drop but
  smaller vein contribution (?).
- Mithril has 3 visual grades in the Dwarven Mines: **gray wool → dark prismarine / prismarine
  bricks → light blue wool**. Old wiki values were gray 500 / prismarine 800 / light-blue 1500;
  the current wiki lists 500 / 500 / 1500. Use 500/800/1500 if you want the three grades to feel
  different (?).

## 2.3 Mining Speed → ticks to break (*wiki*)

```
ticks = round( BlockStrength × 30 / MiningSpeed )      // 20 ticks = 1 second
seconds = ticks / 20
```

- **Softcap / floor: 4 ticks (0.2 s).** You can never break a non-instamine block faster than 4 ticks.
  Speed needed to reach the floor: `ceil(20 × BlockStrength / 3)` (e.g. Coal 30 → 200; the wiki
  table lists 201 because of rounding at 4.5).
- **Instamine (0 ticks, block breaks on first click):**
  - standard blocks: `MiningSpeed > 30 × BlockStrength`
  - obsidian, ores, pure ores, gemstones, dwarven metals: `MiningSpeed > 60 × BlockStrength`
  - this is a **jump** from 4 ticks to 0 — nothing in between. Instamining stone (451 speed) is
    the classic "insta-mine cobble" breakpoint for minions/collections.
- Worked examples (round half-up):

| Block (strength) | 500 speed | 1,000 | 2,000 | 4,000 | 8,000 |
|---|---|---|---|---|---|
| Hard Stone (50) | 3→**4 floor** | 4 | 4 (instamine >1,500) | instamine | instamine |
| Mithril gray (500) | 30 t (1.5 s) | 15 t | 8 t | 4 t | 4 t (floor) |
| Titanium (2,000) | 120 t (6 s) | 60 t | 30 t | 15 t | 8 t |
| Ruby (2,300) | 138 t | 69 t | 35 t | 17 t | 9 t |
| Jade (3,000) | 180 t | 90 t | 45 t | 23 t | 11 t |
| Glacite (6,000) | 360 t (18 s) | 180 t | 90 t | 45 t | 23 t |

**Mining Speed sources (orders of magnitude, mid-late game):**

| Source | Mining Speed |
|---|---|
| Base player stat | 0 (vanilla hand still breaks BP-0 blocks) |
| Efficiency enchant I–V (+VI from Stonk/Efficiency books via Silex) | +10 base +20 per level → Eff V = 110, Eff X (Silex ×5) = 210 (?) |
| HOTM "Mining Speed" perk | up to +1,020 (*wiki*) |
| HOTM "Speedy Mineman" | up to +2,040 (*wiki*) |
| HOTM "Gemstone Speed" (gem-only) | up to +755 (*wiki*) |
| HOTM "Strong Arm" (dwarven metals) | up to +505 (*wiki*) |
| HOTM "Mining Master" (mineshaft) | up to +404 (*wiki*) |
| Seasoned Mineman (first 2,500 gems/day) | +250 (*wiki*) |
| Mining Speed Boost ability | ×3 / ×3.5 / ×4 (+200% / +250% / +300%) (*wiki* range) |
| Drills | 450 → 1,800 (see 2.15) |
| Armor (Mineral, Glacite, Divan) | +40 to +100 per piece (?) |
| Amber gemstones (slotted) | +4 Rough … +20 Perfect per gem (+ rarity scaling) (?) |
| Pets (Silverfish, Scatha, Mole?) | Scatha: +? ; Silverfish: +? (?) |
| Mining Speed Potion / Haste | Haste I/II/III = +50/+100/+150 (?) |
| Sky Mall daily buff | +100 |
| Mineshaft Mayhem buff | +200 (?) |

Pickobulus, Mining Speed Boost, etc. are covered in 2.7.

## 2.4 Mining Fortune family, Pristine, Spread

### Fortune split (post-2024)
| Stat | Applies to |
|---|---|
| **Mining Fortune** | everything mineable (ores, stone, metals, gems) |
| **Ore Fortune** | vanilla ores and pure ores |
| **Block Fortune** | stone/cobble/end stone/netherrack/hard stone etc. (?) |
| **Dwarven Metal Fortune** | Mithril, Titanium, Umber, Tungsten, Glacite |
| **Gemstone Fortune** | gemstones only |

Total relevant fortune = Mining Fortune + matching specialised fortune.
`drops = baseDrop × (1 + totalFortune/100)`, fractional part rolled.

Fortune sources: Fortune enchant I–IV (+10/+20/+30/+45 mining fortune) (?), HOTM Mining Fortune
(+102 max *wiki*), Fortunate Mineman (+153 *wiki*), Ore Fortune perk (+105 *wiki*), Gemstone
Fortune perk (+104 *wiki*), Eager Adventurer (+105 dwarven-metal fortune *wiki*), Crystalline
(+204 mining fortune in mineshafts *wiki*), Jade gemstones, Mining skill "Spelunker"
(+4 Mining Fortune per level), Sky Mall (+50), Maniac Miner ability (+5…+15 per block mined,
stacking, 20 mana per block *wiki*), Seasoned Mineman (+150 gemstone fortune *wiki*), drills
(+15 → +150).

### Pristine (*wiki*)
> "Every 1 Pristine grants a +1% chance to drop a **Flawed** Gemstone per Rough Gemstone initially
> yielded upon mining."

The wiki gives the *expected rough-equivalent* formula:
```
E[rough] = baseDrop × (1 + Pristine × 0.79/100) × (1 + (MiningFortune + GemstoneFortune)/100)
```
(0.79 because a Flawed = 80 Rough, minus the 1 rough it replaces.)

Pristine sources (*wiki*): **Prismatic** enchant I–V = +0.5/1/1.5/2/2.5; **Topaz** gemstones
(Rough +0.4, Flawed +0.8, Fine +1.2, Flawless +1.6, Perfect +2.0; +0.1–0.2 on Legendary items);
HOTM **Blockhead** max +1.1 (the current wiki lists Blockhead under "Glacite Fortune" at max
level 10); Bal pet +2/+3 in Magma Fields; Glacite Golem pet +1 per Frozen Corpse looted (max +4);
Pendant of Divan +0.25 in Mineshafts; attribute "Deep Technique".

### Mining Spread / Gemstone Spread (post-2024)
Replaced the old "Efficient Miner" perk. Semantics (?):
- **Mining Spread**: when you break a block, `floor(spread/100)` adjacent blocks of the same
  type also break, plus `(spread mod 100)%` chance of one more. Adjacent = random face-neighbour
  of the same block type.
- **Gemstone Spread**: same but for gemstones only (often appears as "+X Gemstone Spread" in
  mineshafts via Steady Hand).
- **Hard Stone Spread**: HOTM perk, up to +401.76 on hard stone (*wiki*).
- **Sheer Force** ability: +200 Mining Spread for 20–30 s (*wiki*).
Spread blocks give normal drops and powder but **do not** progress mineshaft pity (?).

## 2.5 Mining areas & ore regeneration

| Area | Requirement | Mineable | Regenerates? |
|---|---|---|---|
| Hub — Coal Mine | none | Coal, stone | Yes — ore → **bedrock** (or stone) for ~(?) s, then back |
| Gold Mine | Mining 1 (?) / Combat? via Hub | Gold, iron, coal, stone | Yes |
| Deep Caverns — Gunpowder Mines | Mining 5 (?) | Coal/iron/gold ore | Yes |
| Deep Caverns — Lapis Quarry | Mining 5 (?) | Lapis | Yes |
| Deep Caverns — Pigmen's Den | Mining 6 (?) | Redstone | Yes |
| Deep Caverns — Slimehill | Mining 8 (?) | Emerald | Yes |
| Deep Caverns — Diamond Reserve | Mining 10 (?) | Diamond | Yes |
| Deep Caverns — Obsidian Sanctuary | Mining 12 (?) | Diamond blocks, obsidian | Yes |
| Spider's Den / The End | combat islands | End stone, obsidian (End) | Yes |
| **Dwarven Mines** | Mining 12 + Rhys quest *wiki* | Mithril, Titanium, all ores, Pure ore blocks, Hard Stone (?) | Yes — mined block → **bedrock**, respawns after a delay, mithril grade may change |
| **Crystal Hollows** | Mining 12 + HOTM 3–4 (*wiki* says HOTM IV) + Pass | Hard Stone, gemstones, mithril, ores | **No** — the lobby is a one-shot instance that degrades until it closes |
| **Glacite Tunnels** | HOTM 7 + Secret Railroad Pass | Glacite, Umber, Tungsten, Aquamarine/Onyx/Citrine/Peridot, Mithril, Titanium, Hard Stone | Yes (?) — veins regrow |
| **Glacite Mineshafts** | random spawn from Tunnels | variant-specific (see 2.13) | No (instanced, single run) |
| Crimson Isle | Combat 24 | Sulphur Ore, Opal? (?) | Yes |

**Regen model to implement (best reconstruction):**
| Area | Placeholder | Delay |
|---|---|---|
| Hub/Deep Caverns ores | ore → stone (Hypixel visibly uses stone/bedrock) | 5–10 s (?) |
| Dwarven Mines mithril/ores | → bedrock | ~5 s (?) |
| Dwarven Mines titanium | → bedrock, may come back as mithril | ~5 s (?) |
| Glacite Tunnels metals & gems | → bedrock / packed ice? | ~15–30 s (?) |
| Crystal Hollows | none — stays air | never |

**Titanium spawning**: in the Dwarven Mines, each mithril block mined has a small chance to turn a
nearby stone block into Titanium (polished diorite). HOTM **Titanium Insanium** sets this chance:
2.1% at lvl 1 → 7.1% at max (*wiki*). Sky Mall "5x Titanium drops" multiplies titanium.

## 2.6 Powders

| Powder | Symbol colour | Where | Main sources |
|---|---|---|---|
| **Mithril Powder** | dark green | Dwarven Mines (also CH) | mining Mithril (base +1/block? plus Core bonus), commissions (500–1,000), Fetchur (2,500), Puzzler (2,500), Powder Ghast, events |
| **Gemstone Powder** | pink | Crystal Hollows | **Hard Stone** treasure chests (main source), commissions (1,000–2,000), mining gemstones (?) |
| **Glacite Powder** | aqua | Glacite Tunnels / Mineshafts | mining Glacite/Umber/Tungsten (+ base via Core lvl 8 "+3 base"), commissions (1,500–3,000), mineshaft exit (1,000 + 500/scrap + 250/corpse + 10/cold) *wiki*, Fossil Muncher daily 25,000 *wiki*, excavator (1,000–10,000/drop) |

Modifiers: **2x Powder** event (doubles all three), **Powder Buff** HOTM perk (+51% *wiki*),
Seasoned Mineman (×3 for first 2,500 gems/day *wiki*), Sky Mall +15% powder (?), "Daily
Powder"/"Front Loaded" style bonuses for first N blocks (pre-rework names — may be gone) (?).

Approximate HOTM totals to max every perk (*web search, wiki*): **12.7 M Mithril, 31.2 M Gemstone,
34.5 M Glacite**.

## 2.7 Heart of the Mountain (HOTM)

A skill tree opened with `/hotm` (or the Mining menu). You earn **HOTM XP** (from commissions —
not from mining itself), level the Heart through 10 tiers, and each tier grants **Tokens of the
Mountain**. A token unlocks a perk at level 1; further perk levels cost **powder**. Unlocking a
perk requires an adjacent unlocked perk (it's a grid/graph, like a skill tree in an ARPG).
**Reset**: you can reset the tree (refunds all powder + tokens) for a coin fee (?), and since the
rework a reset is free for a period (?).

### 2.7.1 Tiers (*wiki*)

| Tier | HOTM XP to reach | Cumulative | Tokens granted (?) | SkyBlock XP | Other rewards |
|---|---:|---:|---:|---:|---|
| 1 | 0 | 0 | 1 | 35 | +1 Forge Slot |
| 2 | 3,000 | 3,000 | 2 | 45 | +1 Forge Slot (2 total), **Forge access**; 1st pickaxe ability |
| 3 | 9,000 | 12,000 | 2 | 60 | +1 Forge Slot (?); Crystal Hollows (older req) |
| 4 | 25,000 | 37,000 | 2 | 60 | +1 Forge Slot (?); Crystal Hollows (current req *wiki*) |
| 5 | 60,000 | 97,000 | 2 | 90 | Forge Slot; **Mining Helix** emblem |
| 6 | 100,000 | 197,000 | 2 | 110 | Forge Slot; **Golden Helix** emblem; tier-6 abilities |
| 7 | 150,000 | 347,000 | 2 | 130 | Forge Slot; **Glacite Tunnels access** |
| 8 | 210,000 | 557,000 | 2 | 180 | Forge Slot |
| 9 | 290,000 | 847,000 | 2 | 210 | Forge Slot |
| 10 | 400,000 | 1,247,000 | 2 | 240 | Forge Slot; **Diamond Helix** emblem; tier-10 abilities |

The *wiki* fetch returned the **SkyBlock XP** column labelled as "tokens" (35/45/60…) — those
are not tokens. Token counts above follow the HotF tree, which is a copy of HOTM's shape
(1 then +2 per tier) (?). Forge slots end at 7 total? The wiki row listing "+2 Forge Slots" each
tier is suspicious; Hypixel's Forge has **7 slots** max, unlocking roughly one per tier 1–7 (?).

Max tokens ≈ 19 from tiers + 4 from Core of the Mountain (lvl 5: +1, lvl 7: +1, lvl 10: +2) = **23** (?).

### 2.7.2 Core of the Mountain (center perk, formerly "Peak of the Mountain") (*wiki*)

| Core lvl | Cost | Reward |
|---|---|---|
| 1 | 1 token (free/auto) | — |
| 2 | 50,000 Mithril | +1 Pickaxe Ability Level |
| 3 | 100,000 Mithril | +1 Commission Slot |
| 4 | 200,000 Gemstone | +1 base Mithril Powder when mining Mithril |
| 5 | 300,000 Gemstone | +1 Token of the Mountain |
| 6 | 400,000 Gemstone | +2 base Gemstone Powder (when mining gems) |
| 7 | 500,000 Gemstone | +1 Token of the Mountain |
| 8 | 750,000 Glacite | +3 base Glacite Powder when mining Glacite |
| 9 | 1,000,000 Glacite | +10% chance for Glacite Mineshafts to spawn |
| 10 | 1,250,000 Glacite | +2 Tokens of the Mountain; Glacite Tunnels daily +5,000 Glacite Powder (?) |

Totals: 150k Mithril + 1.4M Gemstone + 3.0M Glacite.

### 2.7.3 Cost formulas

Every leveled perk uses `cost(nextLevel) = floor((nextLevel + 1) ^ e)` powder, where the exponent
`e` sets how steep it is. Exponents used (*wiki*): **2.17, 2.3, 2.45, 2.6, 3.0, 3.05, 3.07, 3.1,
3.2, 3.3, 4.0**, plus `(nextLevel + 7) ^ 5` for the short 10-level "keystone" perks (Blockhead).

Quick reference for a few exponents (cost of the level *you are buying*):

| Next level | e=2.3 | e=3.0 | e=3.07 | e=3.2 | e=4.0 |
|---:|---:|---:|---:|---:|---:|
| 2 | 12 | 27 | 29 | 33 | 81 |
| 5 | 61 | 216 | 244 | 309 | 1,296 |
| 10 | 248 | 1,331 | 1,574 | 2,150 | 14,641 |
| 20 | 1,099 | 9,261 | 11,460 | 17,025 | 194,481 |
| 50 | 8,460 | 132,651 | 174,678 | 291,222 | (max 20) |
| 100 | 40,732 | (max 50) | (max 50) | (max 50) | — |

### 2.7.4 Every perk

Placement by tier is the best reconstruction of the post-2024 tree; *wiki* marks values pulled
from the current wiki; everything else (?). "Effect at max" is the number the player brags about.

**Mithril-powder perks (tiers 1–4)**

| Perk | Tier | Max lvl | Cost exp | Effect per level | At max |
|---|---|---:|---|---|---|
| Mining Speed | 1 | 50 | 3.0 | +20 Mining Speed | +1,020 *wiki* |
| Mining Fortune | 2 | 50 | 3.05 | +2 Mining Fortune | +102 *wiki* |
| Quick Forge | 2 (?) | 20 | 4.0 (?) | −(10% + 0.5%/lvl) forge time; max −30% | −30% *wiki* (listed as "Efficient Miner" by the fetch — likely mislabeled) |
| Titanium Insanium | 2 (?) | 50 | 3.1 | +0.1% titanium spawn chance (base 2%) | 7.1% *wiki* |
| Mining Speed Boost (ability) | 2 | 3 (ability lvls) | token | +200/250/300% speed for 10/15/20 s, 120 s cd | *wiki* |
| Pickobulus (ability) | 2 | 3 | token | throw pick → 3-block-radius explosion that mines (no gems?), cd 60→50→40 s | *wiki* |
| Luck of the Cave | 3 | 45 (?) | 3.07 | +1% chance for rare Dwarven events (Goblins, Golden/Diamond goblin, Powder Ghast, Fallen Star) | +51% *wiki* |
| Daily Powder / Front Loaded | 3 (?) | 1 (?) | — | first N blocks/day give bonus powder (pre-rework; may be removed) (?) | |
| Sky Mall | 3 | 1 | token | daily random buff (see 2.9) | *wiki* |
| Efficient Miner / Mining Spread | 3–4 | 100 | 2.6 | +3 Mining Spread (?) | +303 Mining Spread *wiki* |
| Old-School / Lonesome Miner | 4 (?) | 45–50 | 3.07 | +5% → +27.5% combat stats on mining islands | *wiki* |
| Goblin Killer | 4 (?) | 1 | token | Golden/Diamond goblins drop 2x powder, Goblin Raid kills count double (?) | |
| Seasoned Mineman | 4 (?) | 100 (?) | 2.3 | +0.1 Mining Wisdom (?) — *wiki* fetch muddled this with a "first 2,500 gems/day ×3 powder, +150 gem fortune, +250 speed" buff | |

**Gemstone-powder perks (tiers 4–7)**

| Perk | Tier | Max | Cost exp | Effect / level | At max |
|---|---|---:|---|---|---|
| Ore Fortune | 4–5 | 20 | 4.0 | +5 Ore Fortune | +105 *wiki* |
| Gemstone Fortune | 5 | 20 | 4.0 | +5 Gemstone Fortune | +104 *wiki* |
| Gemstone Speed | 5 | 140 | 2.3 | +5 speed on gemstones | +755 *wiki* |
| Hard Stone Spread | 5 | 200 | 2.17 | +2 spread on hard stone | +401.76 *wiki* |
| Great Explorer | 5 | 20 | 4.0 | +4% treasure-chest find (scaling 20%→100%), −1 lock per 4 lvls (1→5 locks removed) | *wiki* |
| Mining Wisdom (formerly "Professional"/"Mining Experience") | 5 | 100 | 2.3 | +0.1 Mining Wisdom | +15.1 *wiki* |
| Subterranean Fisher | 6 | 40 (?) | 3.07 | +0.5 Fishing Speed & +0.1 SCC on mining islands | +25.5 FS, +5.1 SCC *wiki* |
| Keep It Cool | 6 | 50 | 3.07 | +0.4 Heat resistance | +20.4 *wiki* |
| Lonesome Miner | 6 | 45 | 3.07 | +0.5% combat stats on mining islands | +27.5% *wiki* |
| Speedy Mineman | 6–7 | 50 | 3.2 | +40 Mining Speed | +2,040 *wiki* |
| Powder Buff | 6–7 | 50 | 3.2 | +1% powder gain | +51% *wiki* |
| Fortunate Mineman | 6–7 | 50 | 3.2 | +3 Mining Fortune | +153 *wiki* |
| No Stone Unturned | 7 | 1 | token | +30 Magic Find on all mining islands (?) | *wiki* |
| Seasoned Mineman (gem buff) | 7 (?) | 1 | token | first 2,500 gems mined/day: ×3 powder, +150 gem fortune, +250 speed | *wiki* |
| Tunnel Vision (ability) | 6 | 3 | token | +30/40/50% rare-event chance (goblins, stars, ghasts, worms) for 30 s; cd 120→110 s | *wiki* |
| Maniac Miner (ability) | 6 | 3 | token | +1 BP; each block +5/10/15 Mining Fortune (stacking) for 25/30/35 s; 20 mana per block; cd 120 s | *wiki* |
| Anomalous Desire / Vein Seeker (old abilities) | — | — | — | removed/merged in the rework (?) | |

**Glacite-powder perks (tiers 8–10)**

| Perk | Tier | Max | Cost exp | Effect / level | At max |
|---|---|---:|---|---|---|
| Rags to Riches | 8 | 50 | 3.05 | +0.5% Suspicious Scrap chance | +25.5% *wiki* |
| Strong Arm | 8 | 100 | 2.3 | +5 Mining Speed on dwarven metals (hidden: +500 on pure ores, +2,500 on hard stone while airborne (?)) | +505 *wiki* |
| Steady Hand | 8 | 100 | 2.6 | +0.1 Gemstone Spread in mineshafts | +10.1 *wiki* |
| Warm Hearted | 8 | 50 | 3.1 | +0.4 Cold Resistance | +20.4 *wiki* |
| Metal Head | 9 | 20 | 4.0 | +0.75% mineshaft find chance (?) | +15.75% *wiki* |
| Mineshaft Mayhem | 9 | 1 | token | on mineshaft entry, random: +5% scrap chance / +100 fortune / +200 speed / +10 cold res / −25% ability cd (?) | *wiki* |
| Eager Adventurer | 9 | 20 | 4.0 | +5 Dwarven Metal Fortune | +105 *wiki* |
| Crystalline | 9 | 50 | 3.05 | +4 Mining Fortune in mineshafts | +204 *wiki* |
| Gifts from the Departed | 9 | 100 | 2.45 | +0.2% extra corpse loot | +20.2% *wiki* |
| Mining Master | 10 | 100 | 2.3 | +4 Mining Speed in mineshafts (also +1 Pristine at lvl 10 per Pristine page (?)) | +404 *wiki* |
| Dead Man's Chest | 10 | 50 | 3.3 | +0.5% (frozen) corpse spawn rate | +25.5% *wiki* |
| Vanguard Seeker | 10 | 50 | 3.1 | +1% Vanguard corpse find chance | +51% *wiki* |
| Blockhead ("Glacite fortune" keystone) | 10 | 10 | (n+7)^5 | +0.1 Pristine (?) | +1.1 *wiki* |
| Gemstone Infusion (ability) | 10 | 3 | token | gemstone stats on your gear ×2 for 20/25/30 s; cd 120 s | *wiki* |
| Sheer Force (ability) | 10 | 3 | token | +200 Mining Spread for 20/25/30 s; cd 120 s | *wiki* |

**Pickaxe ability rules**: one selected ability at a time; right-click with pickaxe/drill
activates; cooldown persists across tool swaps; Core lvl 2 adds +1 ability level to the selected
ability; ability names show in the tool lore as `Ability: X  RIGHT CLICK`.

## 2.8 Commissions (*wiki*)

Talk to the **King** (rotates daily: King Yolkar? no — the 6 Dwarven Kings rotate) or use an
**Emissary** / Royal Pigeon to claim. You hold **2 slots**, **3 after 100 commissions**
(milestone III), **4 with Core of the Mountain lvl 3**. Completing one instantly rerolls a new one.

**Daily bonus**: the first **4** commissions each day give **+900 HOTM XP** extra (until HOTM is
maxed), then at max HOTM they give **+1,600 Mithril / +3,200 Gemstone / +4,000 Glacite Powder**
depending on which island the commission is on.

### Rewards by island
| Island | Mining XP | HOTM XP | Powder |
|---|---|---|---|
| Dwarven Mines | 5,000–10,000 | 100–400 (scales with tier) | 500–1,000 Mithril |
| Crystal Hollows | 10,000–20,000 | 400 | 1,000–2,000 Gemstone |
| Glacite Tunnels | 12,500–25,000 | 750 | 1,500–3,000 Glacite |

### Dwarven Mines commissions
| Commission | Target |
|---|---|
| Mithril Miner | mine 350 Mithril |
| [Area] Mithril (Upper Mines / Royal Mines / Lava Springs / Cliffside Veins / Rampart's Quarry) | mine 250 in area |
| Titanium Miner | mine 15 Titanium |
| [Area] Titanium | mine 10 in area |
| Goblin Slayer | slay 100 Goblins |
| Glacite Walker Slayer (ex-Ice Walker) | slay 50 |
| Treasure Hoarder Puncher | damage 10 times |
| Star Sentry Puncher (event) | punch 10 times |
| Goblin Raid (event) | participate |
| Goblin Raid Slayer (event) | slay 20 |
| Elusive Goblin Slayer | slay 1 Golden/Diamond goblin (?) |
| Raffle (event) | participate |
| Lucky Raffle (event) | deposit 20 tickets |
| 2x Mithril Powder Collector (event) | collect 500 powder during 2x |
| Starter (first ever): First Event, Mithril Miner 50/150, Titanium Miner 2, Mines Slayer (kill 50) | |

### Crystal Hollows commissions
| Commission | Target |
|---|---|
| [Gem] Gemstone Collector (Ruby/Amber/Sapphire/Jade/Amethyst/Topaz) | collect 1,000 |
| Hard Stone Miner | mine 1,000 |
| Automaton Slayer | slay 13 |
| Sludge Slayer | slay 25 |
| Team Treasurite Member Slayer | slay 13 |
| Goblin Slayer | slay 13 |
| Yog Slayer | slay 13 |
| Thyst Slayer | slay 5 |
| Chest Looter | open 3 treasure chests |
| [Gem] Crystal Hunter (Amethyst/Jade/Amber/Sapphire/Topaz) | find 1 crystal |
| Boss Corleone Slayer | slay 1 |

### Glacite Tunnels commissions
| Commission | Target |
|---|---|
| [Material] Collector (Glacite, Umber, Tungsten, Aquamarine, Onyx, Citrine, Peridot) | collect 1,500 |
| Mineshaft Explorer | discover 1 mineshaft |
| Corpse Looter | loot 2 corpses |
| Scrap Collector | obtain 1 Suspicious Scrap |
| Maniac Slayer (? — "Glacite Walker"-type mob) | slay 10 |

### Commission milestones
| # | Commissions | Reward |
|---|---:|---|
| I | 5 | Emissaries unlocked, Royal Compass?, +100k Mining XP, +20 SB XP |
| II | 25 | +200k Mining XP, +30 SB XP |
| III | 100 | **+1 Commission Slot**, +400k Mining XP, +30 SB XP |
| IV | 250 | Travel Scroll to Dwarven Mines, +800k Mining XP, +50 SB XP |
| V | 500 | **Royal Pigeon** (claim anywhere), +1.2M Mining XP, +50 SB XP |
| VI | 750 | Travel Scroll to Crystal Nucleus, +1.6M Mining XP, +75 SB XP |

## 2.9 Dwarven Mines — systems around the mining

| System | What it does | Numbers |
|---|---|---|
| **King** (rotating) | commission hand-in; each day a different King (Yolkar, Brammor, Emkam, Kevin, Redros, Thormyr…) gives a small buff (?) | — |
| **The Forge** (Forge Basin) | timed crafting in up to 7 slots (2.16) | Quick Forge −30% time |
| **Fetchur** | daily random item request | 2,500 Mithril Powder + 20,000 coins *wiki* (older: 3,000 MP) |
| **Puzzler** | daily arrow-direction puzzle | 2,500 Mithril Powder *wiki* (older: 1,000) |
| **Fossil Muncher** (Base Camp) | daily fossil task | 25,000 Glacite Powder + 100 Fossil Essence *wiki* |
| **Sky Mall** (HOTM perk) | changes each SkyBlock day (20 real min): one of **+100 Mining Speed, +50 Mining Fortune, +15% powder, −20% pickaxe cooldown, 10× Golden/Diamond goblin chance, 5× Titanium drops** | (?) for exact values |
| **Golden Goblin / Diamond Goblin** | rare spawns while mining (Luck of the Cave) | drop large Mithril powder, Golden goblin ~1/? ; Diamond goblin rarer |
| **Powder Ghast** | rare event — shoot it; each hit grants Mithril powder | ~(?) |
| **Fallen Star** | a star crashes; mine it with others for Starfall + Star Sentries | — |
| **Treasure Hoarder** | mob in Upper Mines; drops Treasurite | — |
| **Glacite Walker** | mob near Great Ice Wall; drops Glacite | — |
| **Goblin Burrows** | Goblins, Knifethrowers, Fireslingers; Goblin Eggs | — |
| **The Mist** | dark area; Ghosts (invisible, 1M HP, drop Sorrow, Ghostly Boots) | Ghost ~1,000,000 HP (?) |
| **Emissaries** | NPCs around the mines to claim commissions | — |
| **Mining Hat / Royal Pigeon** | claim/remote access | — |

## 2.10 Mining events (*wiki* + knowledge)

Events trigger roughly **every 20 minutes** (one SkyBlock day) per lobby, last ~**10–20 min**
(?), and are announced in chat + boss bar. Each lobby picks one at random among eligible.

| Event | Type | Islands | Effect / goal | Reward |
|---|---|---|---|---|
| **2x Powder** | passive | DM, CH, GT | all powder ×2 | — |
| **Gone with the Wind** | passive | DM, CH, GT (?) | a wind direction is shown; mining while facing it gives bonus Mining Speed (?) | — |
| **Better Together** | passive | DM, CH, GT | +Mining Speed/Fortune per other player in same zone (max 5) | — |
| **Mithril Gourmand** | active | DM | mining mithril in an area drops **Tasty Mithril**; give 200 to Don | event reward box (?) |
| **Raffle** | active | DM | **Raffle Tickets** spawn while mining in a zone; deposit at Raffle Box before end | weighted draw: Mithril powder/coins/rare (?) |
| **Goblin Raid** | active | DM | goblin hordes spawn in a zone; kill within **5 minutes** | Goblin Raid "Gold/Silver/Bronze" tiers by kills (?) |
| **Fallen Star** | random | DM | Star Sentries + Starfall drops | Starfall (Mining XP + Sky Mall?) |
| **Powder Ghast** | random | DM | shoot ghast several times; powder per hit | Mithril Powder |
| **Goblin Raid (CH variant)** / **Team Treasurite** | — | CH (?) | — | — |

## 2.11 Crystal Hollows (CH)

### Access & lobby lifecycle
- Requirements (*wiki*): **Mining 12 + HOTM 4** (older: HOTM 3). Entry via Dwarven Mines.
- **Crystal Hollows Pass**: 10,000 coins for **6 hours** (?) (older: 10k coins per 2h? — current wiki says 6h);
  permanent access after Dulin's quest (?).
- **Lobby lifecycle**: lobby opens Day 0; on **SkyBlock Day 19** it stops accepting new players;
  closes when <4 players remain after that; force-closed at **Day 35** (*wiki*). Blocks do not
  regenerate — the world is a one-shot procedural instance. (1 SB day = 20 real minutes → ~6.3h to
  day 19, ~11.7h hard cap.)
- **Heat** (Magma Fields, below ~y64 (?)): a 0–100 meter. Rises faster the deeper you are; at
  100 you burn/die. Heat resistance (Keep It Cool, Magma armor (?), Ice/Glacite items) slows it.

### Zones (5 quadrants + center)
| Zone | Gemstone | Structure (holds its crystal) | Crystal |
|---|---|---|---|
| **Jungle** (NW (?)) | Amethyst | **Jungle Temple** — parkour + Kalhuiki Door Guardian; need **Jungle Key** | **Amethyst Crystal** |
| **Mithril Deposits** (NE (?)) | Jade | **Mines of Divan** — use a **Metal Detector** to find 4 **Scavenged** items (Lapis Sword, Golden Hammer, Emerald Hammer, Diamond Axe), give to the 4 Keepers | **Jade Crystal** |
| **Precursor Remnants** (SE (?)) | Sapphire | **Lost Precursor City** — give 6 **Automaton parts** (Electron Transmitter, FTX 3070, Robotron Reflector, Superlite Motor, Control Switch, Synthetic Heart) to Professor Robot, or a **Precursor Apparatus** | **Sapphire Crystal** |
| **Goblin Holdout** (SW (?)) | Amber | **Goblin Queen's Den** — give any **Goblin Egg** to **King Yolkar** while avoiding goblin detection | **Amber Crystal** |
| **Magma Fields** (bottom layer, all quadrants) | Topaz | **Khazad-dûm** — kill **Bal** (boss) | **Topaz Crystal** |
| **Crystal Nucleus** (center) | Ruby (Ruby spawns everywhere) | place all 5 crystals | Nucleus loot |
| **Fairy Grotto** (random) | **Jasper** (only source) | — | — |

Other CH content: **Dragon's Lair** (Golden Dragon pet egg? (?)), **Corleone's** hideout + **Team
Treasurite** (Mithril Deposits) — **Boss Corleone**; **Butterflies** (Butterfly → Butterfly pet? (?));
**Odawa** NPC (sells Jungle Key ingredients?), **Professor Robot**, **Xalx**, **Kalhuiki**,
**Yogs** (Magma Fields, drop Yoggie), **Thyst** (spawn when mining amethyst), **Sludges** (Jungle),
**Automatons** (Precursor), **Goblins** (Holdout), **Worms**/**Scatha** (spawn from mining hard
stone/gems in Mithril Deposits/Precursor; Scatha drops Scatha pet).

**Worms & Scatha** (classic): mining Hard Stone has a small chance to spawn a **Worm** (~?%); 1/? of
those is a **Scatha** (rare, drops pet). Tunnel Vision buffs this.

### Crystal Nucleus loot (*wiki* + knowledge)
Placing all 5 crystals at the Nucleus gives a **loot bundle**: Gemstone Powder, Mining XP, chance of
**Gemstone Mixture**, **Divan Fragment**, **Jungle Heart**, **Treasurite**, **Superlite Motor**,
fossils (?), rare enchanted books (?). Counts toward "Crystal Nucleus runs" (a common grind loop).
Crystals then reset for that player (you can redo per lobby (?)).

### Treasure chests (Hard Stone → chest)
- Mining **Hard Stone** has a chance to spawn a **Treasure Chest** (client-visible chest block
  near the broken block) — the main **Gemstone Powder** source.
- Opening it is a **lockpick minigame**: particles appear at one point on the chest; you must
  click that spot `N` times (N = locks, base 5 (?)); **Great Explorer** removes up to 5 locks
  (*wiki*) and increases find rate +20%→+100%.
- Loot: **Gemstone Powder** (hundreds), rough/flawed gems, Goblin Eggs (all colours), Sludge
  Juice, Jungle Heart, Wishing Compass, Oil Barrel, Treasurite, Ascension Rope, **Pickonimbus
  2000**, **Prehistoric Egg**, **Bob-omb**, Yoggie, Control Switch, FTX 3070, Electron Transmitter,
  Robotron Reflector, Superlite Motor, Synthetic Heart (?).

## 2.12 Gemstones

### Quality tiers
| Tier | Made from | Gem stat value (typical, ×rarity) |
|---|---|---|
| Rough | mined directly | base |
| Flawed | 80 Rough (craft) | ~2× |
| Fine | 80 Flawed (craft) | ~3× |
| Flawless | 80 Fine (craft) | ~4× |
| Perfect | 5 Flawless + coins at the Forge, long timer (?) | ~5× |

(So 1 Flawless = 512,000 Rough.)

### Gem → stat, block strength, where
| Gem | Stat when slotted | Strength | Found |
|---|---|---:|---|
| Ruby ❤ | Health | 2,300 | Crystal Hollows everywhere; Mineshafts |
| Amber ⸕ | Mining Speed | 3,000 | CH Goblin Holdout; Mineshafts |
| Sapphire ✎ | Intelligence | 3,000 | CH Precursor Remnants; Mineshafts |
| Jade ☘ | Mining Fortune | 3,000 | CH Mithril Deposits; Mineshafts |
| Amethyst ❈ | Defense | 3,000 | CH Jungle; Mineshafts |
| Topaz ✧ | Pristine | 3,800 | CH Magma Fields; Mineshafts |
| Jasper ❁ | Strength | 4,800 | CH Fairy Grotto only; Mineshafts (?) |
| Opal ❂ | True Defense | 3,000 | Crimson Isle (Mystic Marsh/volcano veins) (?); Mineshafts |
| Onyx ☠ | Crit Damage | 5,200 | Glacite Tunnels |
| Aquamarine ☂ | Fishing Speed (?) | 5,200 | Glacite Tunnels |
| Citrine ☘ | Foraging Fortune (?) | 5,200 | Glacite Tunnels |
| Peridot ☘ | Farming Fortune (?) | 5,200 | Glacite Tunnels |

Gem slots: items have typed slots (e.g. `[Ruby]`, `[Combat]` accepting Ruby/Amethyst/Sapphire/
Jasper/Onyx, `[Mining]` accepting Amber/Jade/Topaz, `[Universal]`, `[Chisel]`...). Unlocking a
locked slot costs coins + gemstones. Applied/removed at the **Gemstone Grinder** (Dwarven Mines)
for coins.

## 2.13 Glacite Tunnels & Glacite Mineshafts

### Glacite Tunnels (*wiki*)
- **Access**: HOTM 7, and forge a **Secret Railroad Pass** with Dulin: 1 Flawless Ruby,
  2 Refined Mithril, 8 Corleonite.
- **Hub**: Dwarven Base Camp (Fossil Muncher, Grandpa Wolf — Frozen Corpse milestones, Campfire
  that warms you).
- **Cold**: an accumulating negative meter while in the tunnels.
  | Cold | Effect |
  |---|---|
  | −25 | warning "BRRR! It's getting really cold in here!" |
  | −50 | slowness / reduced mining speed (?) |
  | −75 | movement freezes / heavy penalties |
  | −90 | final warning |
  | −100 | death → sent back to Base Camp |
  Cold builds per block mined / time (?); Cold Resistance (Warm Hearted, gear, Campfire) slows it.
  Mineshafts endure cold too (10 Glacite Powder per Cold on exit).
- **Ores**: Glacite (6,000 strength), Umber (5,600), Tungsten (5,600), Aquamarine/Onyx/Citrine/
  Peridot (5,200), plus Mithril, Titanium, Hard Stone.
- **Mobs**: Glacite Walkers / Ice Walkers, Glacite Bowman/Caver/Mage (?).
- **Great Glacite Lake** — a fishing spot (Frozen Lake creatures? (?)).
- Recommended entry kit (*wiki*): ~3,750 Mining Speed, Armor of Divan, Silverfish pet, DR-X555+.

### Mineshaft spawning (*wiki*)
- Base **0.05% (1/2,000)** per block mined in the Tunnels, with a **pity counter**: start 2,000,
  each mined block subtracts its **quality** (Glacite/dwarven metal = 4, gemstones = 8–10,
  hard stone = 1?) — when it hits 0 a mineshaft is guaranteed.
- Modifiers: Core lvl 9 +10%, **Metal Head** +15.75%.
- When it spawns: a message + a **ladder/entrance** appears; you (and party) teleport in.

### Mineshaft variants (*wiki*)
- **17 variants**, each 1/17 (5.88%) base. If the *last block you mined* matches a variant's type,
  that variant becomes **1/3 (33.33%)**.
- Types: **Titanium, Tungsten, Umber, Ruby, Sapphire, Amber, Jade, Amethyst, Topaz, Jasper (?),
  Opal (?), Onyx, Aquamarine, Citrine, Peridot**, plus specials **Fairy** (Vanguard corpses, Pure
  Gold veins?) and **Littlefoot's Den** (?). Hypixel also has a **Crystal/Gemstone** "Type 2"
  version of many variants (larger, more corpses) (?).

### Frozen Corpses (*wiki*)
| Corpse | Key needed | Drops (rolls) |
|---|---|---|
| **Lapis** | none | 3–6 (avg 4.5): gem fragments, Goblin Eggs, enchanted materials |
| **Umber** | **Umber Key** | 4–7 (avg 5.5): higher gems, Ice Cold book, etc. |
| **Tungsten** | **Tungsten Key** | 4–7 (avg 5.5) |
| **Vanguard** | **Skeleton Key** — Fairy mineshaft only | 5–8 (avg 6.5): premium (Vanguard armor pieces?, Glacite Amalgamation, Bejeweled Collar) (?) |
Keys are forged / bought from the base camp (?). Grandpa Wolf tracks corpse milestones.

### Mineshaft exit reward (*wiki*)
`1,000 Glacite Powder + 500 per Suspicious Scrap + 250 per looted corpse + 10 per Cold endured`.

## 2.14 Fossils & the Fossil Excavator (*wiki*)

- **Suspicious Scrap**: chance while mining in the Glacite Tunnels (Rags to Riches +25.5%), from
  corpses, or 100 Fossil Essence at the Fossil Essence shop. Consumed per excavation.
- **Fossil Excavator** (Base Camp): a hidden grid (9 × 6 = 54 cells (?)). Each click with a
  **Chisel** spends a charge and reveals one cell. Chisel tiers: Chisel → Reinforced → Glacite-Plated
  → Perfect (more charges, ~15/20/25/30 (?)) with gem slots:
  | Gem in chisel | Effect |
  |---|---|
  | Aquamarine | 30%→100% chance of +1 chisel charge at start |
  | Citrine | highlights treasure cells |
  | Onyx | spawns extra treasure |
  | Peridot | +% Fossil Essence |
- Each excavation: **10% chance a fossil is present**; all fossils equally rare. **6 fossils**
  remain after **Helix** and **Claw** were removed on 2026-04-30 (*wiki*). Remaining (?):
  **Spine, Clubbed, Ugly, Footprint, Webbed, Tusk**. A fossil must be uncovered to some % (e.g.
  revealing its full shape) to be awarded.
- Non-fossil loot: 10–14 rolls from a weighted pool — gemstones, Fossil Essence (10–40 each),
  enchanted materials, Glacite Powder (1,000–10,000 per roll), Paleontologist books, **Fossil the
  Fish** pet. Every roll has a **1/500,000** chance to become a **Fossil Dye**.
- Fossils are donated to **Dr. Stone** (rewards per fossil; pets/Fossil Essence shop unlocks) (?).

## 2.15 Pickaxes, drills, drill parts, fuel

### Drills (*wiki*)
| Drill | Rarity | BP | Mining Speed | Fortune | Damage | HOTM req (?) |
|---|---|---:|---|---|---:|---|
| Mithril Drill SX-R226 | Rare | 5 | 450 | +15 MF | 65 | 2 |
| Mithril Drill SX-R326 | Rare | 6 | 600 | +20 MF | 65 | 3 |
| Ruby Drill TX-15 | Rare | 7 | 150 (+800 on gems) | +60 Gem F | 75 | 3 |
| Gemstone Drill LT-522 | Rare | 8 | 300 (+800 gems) | +80 Gem F | 75 | 4 |
| Topaz Drill KGR-12 | Epic | 9 | 450 (+800 gems) | +100 Gem F | 75 | 5 |
| Jasper Drill X | Epic | 9 | 600 (+800 gems) | +120 Gem F | 75 | 6 |
| Titanium Drill DR-X355 | Epic | 7 | 700 | +25 | 75 | 4 |
| Titanium Drill DR-X455 | Epic | 8 | 900 | +40 | 75 | 5 |
| Titanium Drill DR-X555 | Epic | 9 | 1,200 | +70 | 75 | 6 |
| Titanium Drill DR-X655 | Legendary | 9 | 1,600 | +120 | 75 | 7 |
| Divan's Drill | Mythic | 10 | 1,800 | +150 | 75 | 7 |

Divan's Drill is forged from a **Divan's Alloy** (Mines of Divan / Nucleus) + DR-X655 (?).

### Drill mechanics
- Drills have **Fuel** instead of durability. Base tank **3,000** fuel (?).
- Fuel cost per block (*wiki*): **1** for most blocks; **2** for Aquamarine/Onyx/Citrine/Peridot;
  **3** for Glacite/Tungsten/Umber. Empty drill = can't mine.
- Refuel by combining with fuel items at an anvil / Jotraeline: Coal 50? (?), Enchanted Coal
  ~3,200 (?), Enchanted Charcoal (?), Biofuel 3,000 (?), Oil Barrel 10,000, **Volta** 10,000, Goblin
  Egg (?), Sunflower Oil (?).
- Three **part** sockets, applied at **Jotraeline Greatforge** (*wiki* names, (?) stats):

| Part type | Parts (tier order) | Effect |
|---|---|---|
| **Fuel Tank** | Mithril-Infused (10,000), Titanium-Infused (25,000), Gemstone (50,000), Perfectly-Cut (100,000) | max fuel (?) |
| **Drill Engine** | Mithril-Plated (+50), Titanium-Plated (+100), Ruby-Polished (+150), Sapphire-Polished (+200), Amber-Polished (+250 Mining Speed) | mining speed (?) |
| **Upgrade Module** | Goblin Omelette (+? Mining Fortune), Spicy (+? ), Pesto (+? ), Sunny Side (+? ), Blue Cheese Goblin Omelette (+1 ability level), Tungsten Regulator (?) | misc (?) |

### Pickaxe enchants worth implementing
| Enchant | Effect |
|---|---|
| Efficiency I–V (VI–X via Silex) | +Mining Speed (10 + 20/lvl) (?) |
| Fortune I–IV | +10/20/30/45 Mining Fortune (?) |
| Smelting Touch | auto-smelt |
| Pristine → **Prismatic** I–V | +0.5–2.5 Pristine |
| Compact I–X | auto-compacts drops into enchanted forms, +Mining Wisdom at high lvl (?) |
| Experience | +XP orbs |
| Lapidary I–V | +Gemstone Fortune (?) |
| Ice Cold I–V | Cold resistance (Glacite) (?) |
| Paleontologist | fossil-related (?) |

### Reforges (mining)
Reforge stones: **Fruitful** (+fortune), **Auspicious** (Rock Gemstone; +fortune/speed),
**Refined** (Refined Amber; speed + Pristine?), **Fleet** (Diamonite; +Mining Speed),
**Stellar** (Petrified Starfall), **Mithraic** (Pure Mithril), **Heated** (Hot Stuff) (?).

## 2.16 The Forge (Dwarven Mines)

| Fact | Value |
|---|---|
| Slots | unlocked by HOTM tiers (up to 7 (?)) |
| Time reduction | Quick Forge up to −30%; Mole/other pets (?) |
| Pay-to-skip | **not** allowed (no pay-to-win) |

Representative recipes/timers (?):
| Item | Inputs | Time |
|---|---|---|
| Refined Mithril | 160 Enchanted Mithril | 6 h |
| Refined Titanium | 16 Enchanted Titanium | 12 h |
| Refined Diamond | 2 Enchanted Diamond Blocks | 8 h |
| Fuel Tank | 5 Enchanted Coal Block (?) | 10 h (?) |
| Bejeweled Handle | 3 Glacite Jewel | 30 min |
| Drill Engine | 1 Enchanted Iron Block, 3 Enchanted Redstone Block, 6 Golden Plate, 5 Treasurite, 1 Refined Diamond | 30 h |
| Golden Plate | 1 Glacite Jewel, 8 Enchanted Gold Block, 1 Refined Diamond | 6 h |
| Mithril Drill SX-R226 | Drill Engine + Fuel Tank + 3 Refined Mithril | 4 h |
| Titanium Drill DR-X355 | Drill Engine + Fuel Tank + Gold Plates + Refined Titanium | 64 h |
| Perfect Gemstone | 5 Flawless + 500,000 coins (?) | 20 h (?) |
| Divan's Drill | DR-X655 + Divan's Alloy | 60 h (?) |
| Ruby Drill / Gemstone drills | drill + gem chamber/gemstone mixture | — |

## 2.17 Mining skill

| Fact | Value |
|---|---|
| Max level | 60 (levels 51–60 unlocked by… (?)) |
| Per-level stat | **Spelunker: +4 Mining Fortune per level** (240 at 60) |
| Per-level defense | +1 Defense per level (+2 at 15+?) (?) |
| XP per block | Stone/Cobble 1, Coal 5, Iron 5, Gold 6, Lapis 7, Redstone 7, Emerald 9, Diamond 10, Obsidian 20, End Stone 3, Mithril 45–60 by grade (?), Titanium 100, Hard Stone ~1–5 (?), gemstones 20–40 (?), Glacite/Umber/Tungsten ~? (?) |
| Wisdom | Mining Wisdom from HOTM, armor, potions, Booster Cookie (+25 to all) (?) |

# 3. FISHING

## 3.1 The catch loop

```
cast ─► bobber lands in liquid (water OR lava, rod must match)
     ─► WAIT  (ticks from Fishing Speed formula, 3.2)
     ─► BITE  : bobber dips, "!!!" appears above it (red, armor-stand nametag), splash/flame particles, sound
     ─► player reels within the bite window (~1–1.5 s (?)); if late → nothing, re-cast
     ─► roll 1: Sea Creature?   P = SCC% (capped at 100%)
          yes ─► roll 2: Double Hook?  P = Double Hook Chance% → spawn 2 creatures
               ─► roll 3: which creature? weighted pick among eligible (3.3)
               ─► creature spawns at the bobber and is flung toward the player
          no  ─► roll 4: Treasure?  P = Treasure Chance% (3.5) → Good / Great / Outstanding catch
               no ─► normal fish (Raw Cod / Salmon / Pufferfish / Clownfish, or lava: Magmafish/trophy)
     ─► Trophy fish roll (lava on Crimson Isle only, independent, 3.7)
     ─► Fishing XP: per item / per creature killed
```

Rules:
- **Sea Creature cap**: 60 per server lobby (30 on Galatea) and **10 per player**; at cap you
  catch items instead (*wiki*).
- **Despawn**: a creature despawns after **6 minutes** unkilled, or immediately if its owner leaves (*wiki*).
- **Damage rules**: sea creatures can't be hit by arrows except from the Prismarine Bow; **fishing
  rods deal 2× damage to sea creatures** (*wiki*). Many fishers kill with the rod (e.g. Rod of the Sea).
- **Location penalty**: SCC is **÷4** on the Private Island and the Garden (*wiki*).
- Creature ownership: only the fisher (and party?) gets kill credit/drops (?).

## 3.2 Fishing Speed (*wiki*)

```
BaseTicks = random(200, 400 − 200 × LureCatchTimeReduction)      // Lure enchant shortens the max end
Ticks     = BaseTicks − (FishingSpeed / FishingSpeedCap) × BaseTicks
            = BaseTicks × (1 − FS / Cap)
if FS ≥ Cap: Ticks = 0   (Flash enchant makes the floor 1 tick)
seconds   = Ticks / 20
```

Fishing Speed cap by location (*wiki*):
| Location | Cap |
|---|---:|
| Backwater Bayou | 200 |
| Lotus Atoll | 250 |
| Crimson Isle | 350 |
| Everywhere else | 300 |

So with 150 FS at a cap-300 spot you wait 5–10 s → 2.5–5 s. Note the ticks are **after** the bobber
settles; plus a short "approach" animation. Lure: −5 s per level in vanilla; on Hypixel
`LureCatchTimeReduction` is a fraction per level (e.g. Lure VI ≈ 0.6?) (?).

Fishing Speed sources (*wiki*, (?) marked):
| Source | FS |
|---|---:|
| Fishing Rod (basic) | +5 |
| Challenging Rod | +35 |
| Rod of Champions | +50 |
| Rod of Legends | +65 |
| Rod of the Sea | +80 |
| Starter Lava Rod / Topaz / Magma / Inferno / Hellfire | +10 / +50 / +40 / +50 / +60 |
| Minnow / Fish / Whale / Spooky / Ice / Frozen / Hot / Worm bait | +25 / +45 / +30 / +25 / +25 / +50 / +15 / +60 |
| Flying Fish pet (Leg/Mythic) | up to +80 |
| Reindeer pet | +25 (+75 infused on Jerry's Workshop) |
| Fish Affinity Talisman | +10 |
| Pitchin' reforge (rods) | +1 … +10 by rarity |
| Hotspot buff | +15 (×2 with Hotspot Sinker) |
| Subterranean Fisher (HOTM, mining islands) | up to +25.5 |
| Forest Fisher (HotF, foraging islands) | +0.5/lvl, max 40 → +20 |
| Aquamarine gemstones (?) | small |

## 3.3 Sea Creature Chance (SCC) & weights (*wiki*)

> "X Sea Creature Chance means an X% chance of fishing up a Sea Creature. At 100 or above, a Sea
> Creature is guaranteed." **Base SCC = 20.**

Which creature (*wiki*): `P(c) = weight(c) / Σ weight(all eligible c)`. Eligible = your Fishing
level ≥ creature level requirement **and** location/liquid/time/event conditions met.

Weight modifiers (*wiki*):
| Modifier | Effect |
|---|---|
| **Whale Bait** | ×1.25 weight for creatures with base weight < 400 (i.e. the rares) |
| **Light Bait** (day) / **Dark Bait** (night) | ×1.25 rare weights conditionally |
| **Common Hook** | ×1.25 COMMON creatures |
| **Puddle Jumper Hook** | ×5 Puddle Jumper |
| **Squid Hat** | ×2 Squid / Inkling |
| **Shark Bait** | +20% shark replacement chance (festival) |
| **Spooky Bait** | +15% spooky creature chance (Spooky Festival) |
| **Hotspot Bait / Hotspot Hook** | +20% / +? hotspot creature chance (hotspot creatures base 15%, up to 85% combined) |
| Carrot Bait | enables **Carrot King** (5% replace) |
| Empty Chumcap Bucket (?) | enables **Agarimoo** (15% replace) |
| Worm Bait | in CH only, only worms bite |
| Corrupted Bait | Crimson Isle: −50% FS, only Obfuscated trophy fish chain |

"Replacement" creatures (sharks, spooky, hotspot, Carrot King, Agarimoo) work as: when a sea creature
is rolled, with probability p it's replaced by a creature from the special pool.

SCC sources (*wiki*):
| Source | SCC |
|---|---:|
| Base | 20 |
| Rods: Fishing Rod / Challenging / Champions / Legends / Sea | +1 / +2 / +4 / +6 / +8 |
| Lava rods: Topaz / Magma / Inferno / Hellfire | +2 / +6 / +10 / +14 |
| **Angler** enchant (rod) I–VI | +1 … +6 |
| **Expertise** (kill-counter rod enchant) | +0.6 … +6 |
| Armor: Magma Lord set | +18 (4.5/pc) |
| Armor: Thunder set | +16 (4/pc) |
| Armor: Lava Sea Creature (Slug boots, Moogma leggings, Flaming chestplate, Taurus helmet) | +12 (3/pc) |
| Armor: Shark Scale set | +10 (2.5/pc) |
| Armor: Abyssal / Diver's / Backwater / Sponge | +8 each set |
| Pets: Megalodon | up to +10 (×2 during Fishing Festival with Hephaestus Souvenir (?)) |
| Pets: Dolphin / Ammonite | up to +15 each (Ammonite scales with HOTM/fishing (?)) |
| Pets: Penguin | up to +10 |
| Spiked Bait | +6 |
| Ice Bait / Frozen Bait (Jerry's Workshop) | +4 / +8 |
| Glowy Chum Bait | +3 |
| Mayor Marina perk "Luck of the Sea 2.0" | +15 |
| Bobbin' Time V (rod ultimate) | +0.6–1% per active bobber (max +5) |
| Hotspot buff | +5 (×2 Hotspot Sinker) |
| Subterranean Fisher (HOTM) | up to +5.1 on mining islands |
| Forest Fisher (HotF) | +0.1/lvl → +4 on foraging islands |
| Practical max (wiki) | ~234 (permanent + temporary) |

## 3.4 Sea creatures — every table

Columns: Lvl = Fishing level required; W = base weight; P = default chance within its pool (*wiki*);
HP = health (*wiki* where marked, else (?)); XP = Fishing XP on kill ((?) unless marked).
Rarity in brackets.

### 3.4.1 Regular water (most islands: Hub, Spider's Den, End?, Park, Mines, etc.)

| Creature | Lvl | W | P | HP | XP | Notable drops |
|---|---:|---:|---:|---:|---:|---|
| Squid [C] | 1 | 1,200 | 27.93% | 120 (?) | ~105 (?) | Ink Sac, Lily Pad (?) |
| Sea Walker [C] | 1 | 800 | 18.62% | 1,500 (?) | ~136 (?) | Rotten Flesh |
| Night Squid [C] (night only, + Dark Bait (?)) | 6 | ? | — | 6,000 (?) | ? | Squid Boots, Ink Sac, **Lucky Clover Core**? (?) |
| Sea Guardian [C] | 5 (?) | ? | — | 5,000 (?) | ? | Prismarine Shards/Crystals |
| Sea Witch [U] | 7 | 700 | 16.29% | 2,500 (?) | ? | Fairy armor? Raw Fish (?) |
| Sea Archer [U] | 9 | 550 | 12.80% | 4,000 (?) | ? | Bones, Enchanted Bone (?) |
| Rider of the Deep [U] | 11 | 400 | 9.31% | 20,000 (?) | ? | Enchanted Feather, Enchanted Rotten Flesh (?) |
| Catfish [R] | 13 | 250 | 5.82% | 26,000 (?) | ? | Raw Fish, Enchanted Raw Fish (?) |
| Carrot King [R] (Carrot Bait, 5% replace) | 14 | — | 5% | 50,000 (?) | ? | Lucky Rabbit's Foot, Carrot King pet? (?) |
| Sea Leech [R] | 16 | 160 | 3.72% | 60,000 (?) | ? | Sponge, Fishing books (?) |
| Guardian Defender [E] | 17 | 130 | 3.03% | 70,000 (?) | ? | Enchanted Prismarine, Lily Pad (?) |
| Deep Sea Protector [E] | 18 | 88 | 2.05% | 150,000 (?) | ? | Enchanted Iron, Sponge (?) |
| **Water Hydra** [L] | 19 | 18 | 0.42% | **500,000** *wiki* | **2,025** *wiki* | 20 Raw Cod, 16–18 Lily Pad, 5 Sponge, Fish Affinity Talisman 30%, Water Hydra Head 14%, Aquamarine Dye 1/50,000 *wiki*; at 50% HP splits into a 2nd Hydra with 50% HP |
| Sea Emperor [L] | 24 (?) | ~? | — | 800,000 (?) | ? | Flipper pet? Emperor's Skull (?) — **may have been moved/replaced by "The Loch Emperor" on Galatea** in the Foraging update (?) |
| Agarimoo [R] (Chumcap bucket, 15% replace) | 15 | — | 15% | 20,000 (?) | ? | Agarimoo Tongue, Chumcap (?) |
| Oasis Rabbit / Oasis Sheep (Mushroom Desert Oasis) | 10 | 300 / 700 | — | ~4,000 (?) | ? | Rabbit Hide / Wool (?) |
| Mithril Grubber (Dwarven Mines water) | 6 | 385 | — | 6,000 (?) | ? | Mithril / Mithril powder; bloated/large variants (?) |

**Sum of base weights** for the classic 10 = 4,296; the P column is computed from that.

### 3.4.2 Crystal Hollows (worms & lava)

| Creature | Liquid | Lvl | W | HP | Notable |
|---|---|---:|---:|---:|---|
| Water Worm | water (Goblin Holdout) | 15 | 300 | 20,000 (?) | Worm Membrane |
| Poisoned Water Worm | water | 17 | 300 | 25,000 (?) | Worm Membrane |
| Flaming Worm | lava (Precursor Remnants) | 19 | 180 | 50,000 (?) | Worm Membrane |
| Lava Blaze | lava (Magma Fields) | 20 | 36 | 400,000 (?) | Lava Blaze armor? (?) |
| Lava Pigman | lava (Magma Fields) | 22 | 36 | 450,000 (?) | — |
| Abyssal Miner | water (Mithril/Precursor/Jungle) | 24 | 90 | 2,000,000 (?) | **Abyssal** armor pieces? (?) |

### 3.4.3 Crimson Isle lava (needs a **lava rod**; Combat 24 to reach the island)

| Creature | Lvl | W | P | HP | XP | Notable drops |
|---|---:|---:|---:|---:|---:|---|
| Magma Slug | 27 | 1,600 | 34.42% | 500,000 (?) | ? | Magmafish, Lava Shell (Slug Boots) |
| Moogma | 28 | 1,200 | 25.82% | 750,000 (?) | ? | Moogma Pelt (Moogma Leggings) |
| Lava Leech | 30 | 600 | 12.91% | 1,000,000 (?) | ? | Lump of Magma (?) |
| Pyroclastic Worm | 31 | 400 | 8.61% | 1,200,000 (?) | ? | Flaming Heart (Flaming Chestplate) |
| Lava Flame | 33 | 360 | 7.75% | 1,200,000 (?) | ? | Flaming Heart (?) |
| Fire Eel | 34 | 280 | 6.02% | 1,500,000 (?) | ? | Fire Eel? (?) |
| Taurus | 35 | 160 | 3.44% | 2,000,000 (?) | ? | Horn of Taurus (Taurus Helmet) |
| **Thunder** | 36 | 40 | 0.86% | 35,000,000 (?) | ? | **Thunder Shards** (Thunder armor), Thunder in a Bottle |
| **Lord Jawbus** | 45 | 8 | 0.17% (1/581) | **100,000,000** *wiki* | **40,000** *wiki* | 16–32 Silver Magmafish, **Magma Lord Fragment** (100%), Lord Jawbus Shard 2%, Bobbin' Scriptures 1%, Radioactive Vial 0.5%, Carmine Dye 1/50,000; 10,000 coins *wiki*; Lvl 600, 10,000 dmg, fireballs 2,500, laser 30,000; "sphere mode" every 20M HP lost below 80M *wiki*; requires title **Adept Trophy Fisher** |
| Plhlegblast (Plhlegblast Pool only) | 36 | — | 0.1% | 500,000,000 (?) | ? | — |

### 3.4.4 Crimson Isle lava **hotspot** creatures (Fishing update)

| Creature | Lvl | W | P | HP |
|---|---:|---:|---:|---:|
| Fried Chicken | 14 | 5,000 | 50% | (?) |
| Volcanic Snail | 17 | 2,875 | 28.75% | (?) |
| Fireproof Witch | 22 | 1,500 | 15% | (?) |
| Magma Pillar | 31 | 500 | 5% | (?) |
| Fiery Scuttler | 37 | 100 | 1% | (?) |
| Ragnarok | 47 | 25 | 0.25% | (?) (top-tier, multi-hundred-million (?)) |

### 3.4.5 Water hotspot creatures (any island with water hotspots)

| Creature | Lvl | W | P | HP |
|---|---:|---:|---:|---:|
| Frog Man | 5 | 5,000 | 50% | (?) |
| Inkling | 7 | 2,875 | 28.75% | (?) |
| Snapping Turtle | 10 | 1,500 | 15% | (?) |
| Manta Ray | 19 | 500 | 5% | (?) |
| Blue Ringed Octopus | 35 | 100 | 1% | (?) |
| Wiki Tiki | 40 | 25 | 0.25% | (?) |

The **5,000 / 2,875 / 1,500 / 500 / 100 / 25** ladder (50 / 28.75 / 15 / 5 / 1 / 0.25 %) is the
template every post-2024 pool uses — **use this ladder as the default for any new themed pool**.

### 3.4.6 Backwater Bayou (Fishing update)

| Creature | Lvl | W | P |
|---|---:|---:|---:|
| Trash Gobbler | 5 | 5,000 | 50% |
| Dumpster Diver | 5 | 2,875 | 28.75% |
| Banshee | 10 | 1,500 | 15% |
| Bayou Sludge | 15 | 500 | 5% |
| Alligator | 20 | 100 | 1% |
| Titanoboa | 30 | 25 | 0.25% |

Bayou FS cap is 200. Junker Joel (NPC) sells Treasure Bait; junk items (Junk Sinker) feed a trade loop (?).

### 3.4.7 Galatea — Moonglade Marsh (Foraging update) and newer pools (*wiki*, (?) on placement)

| Creature | Liquid | Lvl | W | P |
|---|---|---:|---:|---:|
| Bogged | water | 5 | 5,000 | 50% |
| Wetwing | water | 7 | 2,875 | 28.75% |
| Tadgang | water | 9 | 1,500 | 15% |
| Ent | water | 12 | 500 | 5% |
| The Loch Emperor | water | 20 | 100 | 1% |
| Nessie | water | 25 | 25 | 0.25% |
| Stridersurfer | lava | 17 | 200 | 100% (only lava creature there) |

Torrhus Canyon (wiki lists, released after the first Galatea patch (?)): Haggard, Brineling,
Sprawl, Torrid, Silkbreeze, Giant Isopod — all Lvl 15, standard 5,000→25 ladder.
Lotus Atoll (?): Atoll Croaker, Lotus Guardian, gorF, Drowned Captain, Puddle Jumper, Frog Prince —
Lvl 10, standard ladder; FS cap 250. **These two areas are new enough that their placement in the
progression is unknown — treat as optional late content.**

### 3.4.8 Event pools

**Winter Island / Jerry's Workshop** (only during the Season of Jerry, Late Winter):
| Creature | Lvl | W | P | HP (?) |
|---|---:|---:|---:|---:|
| Frozen Steve | 1 | 5,000 | 50% | 700 |
| Frosty | 1 | 2,875 | 28.75% | 7,000 |
| Grinch | 13 | 1,500 | 15% | 100 (gives gifts/coins) |
| Nutcracker | 28 | 500 | 5% | 4,000,000 |
| Yeti | 25 | 100 | 1% | 2,000,000 — drops **Baby Yeti pet** (Epic/Leg), Yeti Rod? |
| Reindrake | 35 | 25 | 0.25% | 2,500,000 — drops Reindeer pet? (?) |

**Spooky Festival** (any water; event): (HP (?))
| Creature | Lvl | W | P | HP (?) |
|---|---:|---:|---:|---:|
| Jumpin' Jack (?) | 6 | 5,000 | 50% | ? |
| Scarecrow | 9 | 2,875 | 28.75% | 9,000 |
| Nightmare | 14 | 1,500 | 15% | 30,000 |
| Werewolf | 17 | 500 | 5% | 50,000 |
| Phantom Fisher | 21 | 100 | 1% | 1,000,000 — Deep Sea Orb, Phantom Rod |
| Grim Reaper | 26 | 25 | 0.25% | 3,000,000 — Deep Sea Orb, Soul Fragment |

**Fishing Festival** — see 3.8.

## 3.5 Treasure catches

- Treasure is rolled only when **no sea creature** was rolled (*wiki*).
- Base Treasure Chance (?) ≈ 0; **Fishing skill gives +0.1 per level (+5 at 50)** (*wiki*);
  Pearl of the Swamp +1; Challenging Rod +0.5; Seal pet up to +1; baits (Golden +4, Treasure +2);
  Hotspot +1; Treasure Hook; armor (?). Practical max ~54 (*wiki*, Year of the Seal).
- Quality: **Good Catch** (common pool), **Great Catch** (rarer), **Outstanding Catch** (rarest)
  — weights (?) e.g. 85% / 13% / 2%. **Blessed Bait** +50% chance that a treasure is Great or
  Outstanding.
- Treasure pools by island (examples): coins, Prismarine, Sponge, Enchanted books (Luck of the
  Sea, Lure, Angler, Caster…), Fishing Exp Bottles, Ice Rod (Jerry), **Lucky Clover Core**, etc. (?)

## 3.6 Lava fishing & rods: water vs lava

| | Water rods | Lava rods |
|---|---|---|
| Liquid | water only; bobber floats | lava only; bobber floats on lava (custom — vanilla would burn) |
| Rods | Fishing Rod → Challenging → Champions → Legends → Rod of the Sea; specials: Giant Fishing Rod (+10% Double Hook), Phantom Rod, Ice Rod, Yeti Rod, Shredder, Auger Rod (?), Dirt Rod, Farmer's Rod, Grappling Hook (not fishing) | Starter Lava Rod → Topaz Rod → Magma Rod → Inferno Rod → Hellfire Rod |
| Where | everywhere with water | Crimson Isle (main), Crystal Hollows Magma Fields/Precursor lava, Moonglade (Stridersurfer) |
| Exclusive | sea creature pools, treasure | **Trophy Fish**, lava sea creatures, Magmafish |

Rod stats (*wiki*):
| Rod | Rarity | Lvl | Dmg | Str | FS | SCC | Special |
|---|---|---:|---:|---:|---:|---:|---|
| Fishing Rod | C | — | 15 | 15 | 5 | 1 | — |
| Challenging Rod | U | 10 | 75 | 75 | 35 | 2 | +0.5 Treasure |
| Rod of Champions | R | 15 | 100 | 100 | 50 | 4 | — |
| Rod of Legends | E | 20 | 125 | 125 | 65 | 6 | — |
| Rod of the Sea | L | 24 | 150 | 150 | 80 | 8 | — |
| Giant Fishing Rod | L | — | — | — | — | — | +10% Double Hook |
| Starter Lava Rod | U | 10 | 50 | 50 | 10 | — | +5 Trophy Chance |
| Topaz Rod | R | 19 | 100 | 100 | 50 | 2 | — |
| Magma Rod | R | 27 | 175 | 175 | 40 | 6 | +15 Trophy Chance |
| Inferno Rod | E | 30 | 200 | 200 | 50 | 10 | +20 Trophy Chance |
| Hellfire Rod | L | 35 | 225 | 225 | 60 | 14 | +25 Trophy Chance |

Rod enchants: Angler (SCC), Caster (chance to not consume bait), Frail (reduced creature HP?),
Luck of the Sea (treasure), Lure (wait time), Magnet (+XP orbs), Spiked Hook (dmg), Blessing (?),
Impaling (dmg vs sea creatures), Expertise (kill-counter SCC/wisdom), Piscary (fishing XP?),
Charm (?), Corruption (?), Quick Bite (FS), Flash (min tick 1), **Bobbin' Time** (ultimate,
SCC per bobber). Reforges: **Pitchin'** (FS), **Salty** (SCC), **Treacherous** (SCC+str?),
**Lucky** (?).

## 3.7 Trophy Fishing (Crimson Isle) (*wiki*)

Caught **in lava on the Crimson Isle**, rolled **independently** of sea creatures (?) on each
non-creature catch. Each trophy fish has a **Trophy Chance** gate (its own base %) and a
**condition**. If several qualify, rarer first (?).

| Fish | Rarity | Base chance | Condition / location |
|---|---|---:|---|
| Blobfish | Common | 25% | anywhere |
| Gusher | Common | 20% | Blazing Volcano, 10–15 min after an eruption |
| Steaming-Hot Flounder | Common | 20% | bobber within 2 blocks of a geyser (Blazing Volcano) |
| Sulphur Skitter | Common | 30% | within 4 blocks of Sulphur Ore |
| Obfuscated 1 | Common | 25% | using **Corrupted Bait** |
| Flyfish | Uncommon | 8% | Blazing Volcano, bobber ≥8 blocks below player |
| Obfuscated 2 | Uncommon | 20% | using **Obfuscated 1** as bait |
| Slugfish | Uncommon | 15% | bobber active ≥20 s (10 s with Slug pet) |
| Lavahorse | Rare | 4% | anywhere |
| Mana Ray | Rare | variable | ≥1,200 Mana (chance scales with mana (?)) |
| Obfuscated 3 | Rare | 10% | using **Obfuscated 2** as bait |
| Vanille | Rare | 8% | **Starter Lava Rod with no enchants** |
| Volcanic Stonefish | Rare | 4% | Blazing Volcano only |
| Karate Fish | Epic | 2% | Dojo only |
| Moldfin | Epic | 2% | Mystic Marsh / Scarleton |
| Skeleton Fish | Epic | 2% | Burning Desert / Dragontail |
| Soul Fish | Epic | 2% | Stronghold only |
| **Golden Fish** | Legendary | special | appears after ~8 minutes of continuous lava fishing; you must keep casting near it and then hook it (?) |

**Tiers** (each catch rolls a tier):
| Tier | Base chance | Pity |
|---|---:|---|
| Diamond | 0.2% | guaranteed at **600** catches (of that fish? (?)) |
| Gold | 2% | guaranteed at **100** |
| Silver | 25% | — |
| Bronze | remainder | — |

Trophy Chance stat (from lava rods +5/+15/+20/+25, Hot Bait +5, hotspot +5, Trophy Line, armor)
multiplies each fish's base chance (?) — e.g. `chance = base × (1 + TrophyChance/100)` (?).

**Filleting** (Odger's) turns trophy fish into **Magmafish** — Golden Fish: 400/700/1,000/1,300
for Bronze/Silver/Gold/Diamond (*wiki*); lower rarities proportionally less (?). Magmafish feeds
lava-fishing gear (Magma Lord armor, Hellfire Rod upgrades).

**Trophy Fisher titles / armor** (?): Novice → **Adept** (needed for Lord Jawbus) → Expert → Master
Trophy Fisher, by catching every fish at Bronze/Silver/Gold/Diamond. Rewards: Bronze / Silver /
Gold / Diamond **Hunter** armor sets (+Trophy Chance), Odger's bronze→diamond tooth necklaces (?).

## 3.8 Fishing Festival / sharks (*wiki*)

- Triggered by **Mayor Marina** (Fishing Festival perk): the **first 3 SkyBlock days of each SkyBlock
  month** (12 per SB year; each SB day = 20 min → ~1 h per festival). Some other mayor perks (and
  Jerry?) can add festivals.
- During a festival, **15% base chance a rolled sea creature is replaced by a shark**; Shark Bait
  +20%, Shark Scale armor +10/+20%, etc. — up to 80% (*wiki*).

| Shark | Lvl | W (within shark pool) | P | HP | Drops |
|---|---:|---:|---:|---:|---|
| Nurse Shark | 5 | 2,875 | 57.79% | 2,500 | 2–4 Shark Fin; 0–1 Nurse Shark Tooth |
| Blue Shark | 10 | 1,500 | 30.15% | 25,000 | 4–8 Shark Fin; 0–1 Blue Shark Tooth |
| Tiger Shark | 18 | 500 | 10.05% | 250,000 | 8–16 Shark Fin; 0–1 Tiger Shark Tooth; **Epic Megalodon** pet (?) |
| Great White Shark | 24 | 100 | 2.01% | 1,500,000 | 16–32 Shark Fin; 0–1 Great White Shark Tooth; **Legendary Megalodon** pet (?) |

Uses: **Shark Scale** armor (+10 SCC, ×2 in festival?), shark-tooth **necklaces** (+5% → +25% tooth
drop), Megalodon pet (SCC), Shark Fin → Enchanted Shark Fin, Edible Mace? (?).

## 3.9 Baits (*wiki*)

One bait is consumed per cast (unless Caster enchant / Stingy Sinker); first bait in inventory is used.

| Bait | Rarity | Effect | Recipe / source |
|---|---|---|---|
| Minnow Bait | C | +25 FS | Raw Salmon + 2 Raw Cod |
| Fish Bait | C | +45 FS | Raw Salmon (coll V) + 3 Raw Cod |
| Light Bait | C | ↑ rare SC chance by day (×1.25) | 2 Prismarine Crystals + 2 Raw Cod |
| Dark Bait | C | ↑ rare SC chance by night (×1.25) | 2 Ink Sac + Raw Cod |
| Spiked Bait | C | +6 SCC | 5 Pufferfish + Raw Cod |
| Spooky Bait | C | +25 FS, +15% spooky SC (festival) | Lily Pad + Pumpkin + Raw Cod |
| Carrot Bait | C | chance of Carrot King | 3 Carrot + Raw Cod |
| Corrupted Bait | C | −50% FS, Obfuscated trophy chain | 5 Magmafish + Corrupted Fragment |
| Blessed Bait | U | +50% chance treasure is Great/Outstanding | Prismarine Crystals, Gold, Gold Block, Raw Cod |
| Ice Bait | U | +25 FS, +4 SCC (Jerry's only) | 6 Ice + Raw Cod / Glacial Cave |
| Shark Bait | U | +20% shark chance (festival) | 2 Shark Fin |
| Glowy Chum Bait | U | +25 FS, +3 SCC, +2 Chum from SC | Moby / Glacial Cave |
| Hot Bait | U | +15 FS, +5 Trophy Chance | 2 Magmafish + Blaze Ashes |
| Worm Bait | U | +60 FS, only worms (CH only) | Worm Membrane + Clay |
| Golden Bait | U | +4 Treasure Chance | Seal's Treat Bag |
| Wooden Bait | U | next GOOD CATCH = Rare Fishing Shard | Rosemary (Galatea) |
| Frozen Bait | R | +50 FS, +8 SCC (Jerry's only) | Glacial Cave treasure |
| Whale Bait | R | +30 FS, ×1.25 rare SC weight (<400) | many baits combined |
| Treasure Bait | R | +10 FS, +2 Treasure Chance | Junker Joel |
| Hotspot Bait | R | +20% hotspot SC chance | Ench. Pufferfish + 32 Scorched Crab Stick |

## 3.10 Rod parts (Fishing update, 2024) (*wiki* names; effects (?))

Applied by **Roddy** (Backwater Bayou). Three slots: **Hook**, **Line**, **Sinker**.

| Slot | Part | Effect (?) |
|---|---|---|
| Hook | Common Hook | ×1.25 weight on COMMON sea creatures (*wiki*) |
| Hook | Hotspot Hook | +hotspot creature chance |
| Hook | Spooky Hook | +spooky creature chance |
| Hook | Treasure Hook | only treasure, no sea creatures (?) / +treasure |
| Hook | Puddle Jumper Hook | ×5 Puddle Jumper (*wiki*) |
| Line | Speedy Line | +Fishing Speed |
| Line | Shredded Line | +damage |
| Line | Titan Line | +Double Hook Chance (?) |
| Line | Trophy Line | +Trophy Chance (?) |
| Sinker | Junk Sinker | junk items → bayou economy |
| Sinker | Prismarine Sinker | +prismarine drops |
| Sinker | Sponge Sinker | +sponge drops |
| Sinker | Chum Sinker | +Chum |
| Sinker | Festive Sinker | gifts on Jerry's |
| Sinker | Icy Sinker | Jerry's bonus |
| Sinker | Stingy Sinker | chance not to consume bait |
| Sinker | Hotspot Sinker | doubles hotspot buff (*wiki*) |
| Sinker | Lotus Sinker | Lotus Atoll (?) |

## 3.11 Fishing Hotspots (*wiki*)

- Rings in water (lava on Crimson Isle). Each lasts **~4 minutes** then moves.
- Fishing inside one grants **one** random buff: **+15 FS, +5 SCC, +2 Double Hook, +5 Trophy, or
  +1 Treasure** (doubled by Hotspot Sinker).
- Hotspot creatures: **15% base** replace chance, up to **85%** with Hotspot Bait/Hook etc.
- Count per island: Spider's Den 1, The Park 1, Backwater Bayou 2, Jerry's Workshop 2, Lotus Atoll 2,
  Torrhus Canyon 2, **Hub 3, Crimson Isle 3**.
- **Hotspot Radar** item points to the nearest hotspot.

## 3.12 Fishing skill & gear

| Fact | Value |
|---|---|
| Max level | 50 |
| Per-level perk | **Treasure Hunter: +0.1 Treasure Chance/level** (*wiki*: +5 at 50); +2 Health/level (?) |
| XP from | catching items (fish ~ 5–25 XP (?)), killing sea creatures (Hydra 2,025, Jawbus 40,000 *wiki*) |
| Armor progression | Angler (early) → Sponge / Diver's / Shark Scale / Backwater → Thunder / Magma Lord (lava) → Abyssal (CH) |
| Pets | Squid, Baby Yeti, Flying Fish, Dolphin, Megalodon, Ammonite, Penguin, Reindeer, Spinosaurus (?), Slug |
| Accessories | Sea Creature Talisman→Ring→Artifact (−5/−10/−15% dmg from SC), Fish Affinity Talisman, Pearl of the Swamp |
| Double Hook Chance | Giant Fishing Rod +10%, hotspot +2, Titan Line (?), armor (?) |

# 4. FORAGING

## 4.1 Logs & Foraging XP (*wiki*)

| Log / block | Where | Foraging XP | BP | Notes |
|---|---|---:|---:|---|
| Oak | Hub Forest, Private Island | 6 | 0 | |
| Birch | Park — Birch Park | 6 | 0 | |
| Spruce | Park — Spruce Woods | 6 | 0 | |
| Dark Oak | Park — Dark Thicket | 6 | 0 | |
| Acacia | Park — Savanna Woodland | 6 | 0 | |
| Jungle | Park — Jungle Island | 6 | 0 | |
| **Fig Log** | Galatea — Moonglade Marsh (Evergreen Plateau) | 15 | ? | toughness 10 |
| **Mangrove Log** | Galatea — Murkwater Loch | 20 | ? | toughness 50 |
| **Helix Log** | Galatea — Torrhus Canyon (Torrhus Heights) | 25 | ? | toughness 150 |
| Flowers | anywhere | 1 | 0 | |
| Lushlilac / Sea Lumies / Ruby Veilshroom (Galatea resources) | Galatea | 15–16 | — | "island resources" (Collector perk doubles) |
| Honeycomb | Galatea hives | ? | — | Beekeeper mechanic |
| Vinesap / Tender Wood / Deep Root | from Tree Gifts | — | — | crafting mats |

Log collections (Oak/Birch/Spruce/Dark Oak/Acacia/Jungle; Fig/Mangrove/Helix) gate recipes and
the foraging level cap past 50 (*wiki*: Fig IX 150k, Mangrove IX 150k, Helix IX 250k).

## 4.2 Tree areas

| Area | Requirement (*wiki*) | Trees | Notes |
|---|---|---|---|
| Hub — Forest | none | Oak | Lumber Jack NPC sells axes (?) |
| Private Island | none | any you plant | vanilla-like; Foraging minions |
| **The Park — Birch Park** | Foraging 1 | Birch | entry; Charlie (quests), Vanessa (rain 5,000 coins / 1 min) |
| The Park — Spruce Woods | Foraging 2 | Spruce | Kelly (accepts spruce logs) |
| The Park — Dark Thicket | Foraging 3 | Dark Oak | |
| The Park — Savanna Woodland | Foraging 4 | Acacia | Melancholic Viking shop |
| The Park — Jungle Island | Foraging 5 | Jungle | Trials of Fire (Ryan), Howling Cave (wolves → Wolf Slayer) |
| **Galatea — Moonglade Marsh** | **Foraging 12** (*wiki*) | Fig, Mangrove | Tangleburg hub, Agatha contests, Murkwater Depths (underwater), 12 fairy souls |
| **Galatea — Torrhus Canyon** | Foraging 12 + **HotF 4** | Helix | Miria contests, Critter Safari (Hunting), 12 fairy souls |
| Galatea — Lunarise | unreleased (*wiki*) | ? | — |

Park trees **regrow** after being cut (Hypixel restores the whole tree after a delay; exact timer
not documented — use ~60–120 s (?)). Big Park trees ≈ 35 logs; log piles 25–30 (*wiki*).

## 4.3 Sweep, Foraging Fortune, Treecapitator

**Sweep** (post-Foraging-update stat) (*wiki*):
- On **zero-toughness** trees (Hub/Park/Private Island): `logsCut = clamp(1 + Sweep, 1, 36)` — i.e.
  Sweep = extra logs broken per swing, max 35 extra.
- On **Galatea** trees (toughness > 0): progressively more Sweep is needed per extra log; cap is
  **20 extra logs**. Wrong cutting order: **−50% logs** (multiplicative).
- **Throwing an axe**: −50% Sweep (reduced by **Missile** enchant, removed by **Precursor Drone**
  pet; **Axe Toss** ability removes it for 10 s).
- Sweep also affects **wood breaking speed**, like Mining Speed does for stone (*wiki*).
- Sources: Treecapitator +25, Helix Chopper +50, Fig armor set +40, Helix armor set +80, David's
  Cloak 0–100, Monkey pet (Leg, lvl 100) +10 on the Park, HotF Sweep perk +50, Half Full/Empty
  +0.4/player, Forest Speed/Strength (share of Speed/Strength), Mana Toss (+1 per 100 mana spent).

Clone design formula for toughness (Hypixel's exact one is not published) (?):
```
effectiveSweep = Sweep × (throwing ? 0.5 : 1) × (wrongOrder ? 0.5 : 1)
extraLogs      = min(capExtra, floor( effectiveSweep / (1 + toughness / 10) ))
                 // toughness 0 → 1 log per Sweep point, cap 35
                 // Fig (10)    → 1 log per 2 Sweep,     cap 20
                 // Mangrove(50)→ 1 log per 6 Sweep,     cap 20
                 // Helix (150) → 1 log per 16 Sweep,    cap 20  (→ 320 Sweep to cap)
```
Tune against the gear numbers above (Helix armor 80 + Chopper 50 + HotF 50 + cloak 100 ≈ 280).

**Treecapitator** (pre-update rules, still the mental model):
- Recipe: Spruce Axe? + 32 Enchanted Jungle Log, needs **Jungle Log VII** (*wiki*).
- Breaks the clicked log + connected logs (flood-fill), historically **max 35**, **2 s cooldown**
  (Monkey pet −1%/lvl… up to −50%) (?). Post-update it is "+25 Sweep" and the number of logs comes
  from Sweep (*wiki*), throw cooldown 1 s (*wiki*).
- Older axes: Jungle Axe (break 10 logs, cd 2 s) (?), Efficient Axe, Promising Axe (+Efficiency
  as you use it).

**Foraging Fortune**: `logs × (1 + FF/100)`. Sources: Foraging skill **Logger +4/level** (*wiki*,
+228 at 57), HotF Foraging Fortune +3/lvl (×50 → +150), Forest Starborn donations, Foraging
Fortune Booster (Sawyer), per-tree fortunes (+50 Fig/Mangrove/Helix daily random), Citrine gems
(?), armor, Moonglade reforges, pets (Monkey/Ocelot?), Half Full/Empty.

## 4.4 Galatea trees: toughness, cutting order, Tree Gifts (*wiki*)

| Tree | Location | Toughness | Cutting order ("style") |
|---|---|---:|---|
| Fig | Moonglade upper (Evergreen Plateau) | 10 | Trunk → Branches |
| Mangrove | Murkwater Loch | 50 | Branches → Trunk → Roots |
| Helix | Torrhus Canyon | 150 | Beige logs → Red logs |

Trees are **shared world objects**: many players chop the same tree. When it is fully cut, every
contributor receives a **Tree Gift** scaled by their share and the tree's **size multiplier
(1×–20×)**:

| Your % of the tree | Gift |
|---|---|
| < 10% | nothing |
| ≥ 10% | normal |
| ≥ 20% | +50% |
| ≥ 33% | ×2 |

Tree Gift guaranteed contents (*wiki*):
| Tree | Forest Essence | Foraging XP | HotF XP | Whispers | Other |
|---|---:|---:|---:|---|---|
| Fig | 2 | 100 | 10 (11 w/ Galatea Training) | 20–40 Forest (+200 w/ Tree Whisperer) | 0–1 Tender Wood |
| Mangrove | 3 | 500 | 20 (22) | 20–40 Forest (+200) | 0–1 Vinesap |
| Helix | 4 | 1,500 | 30 (33) | 75–150 **Desert** (+200) | — |

Rare gift rolls (*wiki*): Fig — Phanpyre 11.8%, Stretching Sticks 10%, Phanflare 7.1%, Dreadwing
1.2%, Tree the Fish 0.05% (1/2,000). Mangrove — Phanpyre, Phanflare, Deep Root 4%, Dreadwing.
Helix — Firefox 15%, Groundhog 15%, Drybark 5%, Puck 5%, Grizzly Bear 1%, Forest's Favor, Fading
Blue Rune. (Many of these names are **Hunting shards / critters** — the gift is the bridge into
the Hunting/attribute system.)

**Forest Essence** is spent at **Rosemary** (Murkwater Outpost) for perks/upgrades/baits.

## 4.5 Heart of the Forest (HotF) (*wiki*)

The foraging twin of HOTM. Currencies: **Forest Whispers** (from Moonglade) and **Desert
Whispers** (from Torrhus). HotF XP from **Tree Gifts** and **Starlyn Contests**.

| Tier | XP to reach | Cumulative | Tokens | SB XP | Reward |
|---|---:|---:|---:|---:|---|
| 1 | 0 | 0 | +1 | 35 | |
| 2 | 3,000 | 3,000 | +2 | 45 | axe abilities |
| 3 | 9,000 | 12,000 | +2 | 60 | |
| 4 | 25,000 | 37,000 | +2 | 75 | **Torrhus Canyon access** |
| 5 | 60,000 | 97,000 | +2 | 90 | Foraging Clover emblem |
| 6 | 100,000 | 197,000 | +2 | 110 | tier-6 ability |
| 7 | 150,000 | 347,000 | +2 | 130 | Golden Foraging Clover emblem |
| 8 | 200,000 | 547,000 | +2 | 180 | daily bonus switches to whispers |

**Center of the Forest** (core): L1 free (+1 token); L2 50,000 FW (+1 axe ability lvl); L3 100,000
FW (gift/log whisper bonuses); L4 200,000 DW (+5 Tracking on Tree Gifts); L5 300,000 DW (+1 token).

**Axe abilities**:
| Ability | Tier | Effect | Duration | Cooldown (lvl 1→5) |
|---|---|---|---|---|
| Damage Boost | 2 | axe deals ×2 damage to Galatea creatures | 10 s | 120 → 100 s |
| Axe Toss | 2 | axe throwing has no Sweep penalty | 10 s | 120 → 100 s |
| Mana Toss | 6 | throwing consumes 100% mana; +1 Sweep per 100 mana | 15 → 35 s | 60 → 56 s |

**Perks** (cost = `floor((next+1)^e)` whispers):
| Perk | Currency | Max | e | Effect / level |
|---|---|---:|---|---|
| Sweep | Forest | 50 | 3.0 | +1 Sweep |
| Luck of the Forest | Forest | 40 | 3.07 | +0.5% Tree Gift loot |
| Foraging Fortune | Forest | 50 | 3.05 | +3 Foraging Fortune |
| Collector | Forest | 50 | 2.9 | +2% double drop on berries/island resources |
| Pressure Resistance | Forest | 50 | 2.9 | +1 Pressure Resistance (underwater depths) |
| Hunter's Luck | Forest | 50 | 3.2 | +1 Hunting Fortune |
| Galatea's Might | Forest | 50 | 3.2 | +0.5% combat stats on Galatea |
| Iron Lungs | Desert | 50 | 2.9 | +1 Respiration |
| 250 Gifts | Desert | 40 | 3.07 | first 250 gifts/day: +1% loot, +20 whispers |
| Daily Wishes | Desert | 100 | `200 + 18n` (linear) | first log type daily: +400 FW / +800 DW |
| Forest Fisher | Desert | 40 | 3.07 | +0.5 FS, +0.1 SCC on foraging islands |
| Strength Boost | Desert | 50 | 3.1 | +2 Strength on foraging islands |
| Speed Boost | Desert | 50 | 3.1 | +2 Speed on foraging islands |
| Starlyn Supreme | Desert | 100 | 2.6 | +0.2% contest points |
| Efficient Forager | Desert | 100 | 2.6 | +0.1 Foraging Wisdom |
| Half Full **xor** Half Empty | Desert | 25 | 4.1 | +1 FF and +0.4 Sweep per island player (cap 5) — mutually exclusive |
| Ricochet | Desert | 10 | 5.5 | +1% thrown axe bounces to another tree |
| Forest Speed | Desert | 50 | 3.4 | +0.2% of Speed as FF/Sweep (Speed capped 500) |
| Forest Strength | Desert | 50 | 3.4 | +0.1% of Strength as FF/Sweep (Strength capped 1,000) |
| Essence Fortune | Desert | 50 | 3.2 | +0.5% double Forest Essence |
| Timber (keystone) | Desert | 10 | `(n+7)^5` | +0.1 Timber |
| Two-for-One | Desert | 100 | 2.45 | +0.25% extra Safari Ticket (Miria) |
| Daily random A | token | 1 | — | +50 Fig/Mangrove/Helix Fortune or +10 Sweep |
| Daily random B (Honeycomb) | token | 1 | — | hives refill 20% faster / critters 20% faster / +2 honeycomb / 25% 2nd critter |

Totals to max (*wiki*): **14,767,237 Forest Whispers + 52,672,500 Desert Whispers**, 35 tokens (?).

## 4.6 Starlyn Contests (*wiki*)

- **Agatha** (Moonglade Marsh) and **Miria** (Torrhus Canyon) each run a contest **every SkyBlock
  day (20 real minutes)**.
- Points (Agatha): Tree Gift 10; per 100 Fig logs 3; per 100 Mangrove logs 6; hunting 3–45; sea
  creature 10; treasure 25; Lushlilac 9; Sea Lumies 2.
  Points (Miria): Tree Gift 10; per 100 Helix logs 9; hunting shards 9–200; Outstanding treasure 750.
- Brackets (Agatha / Miria points): Common 25/50, Uncommon 250, Rare 1,000, Epic 2,000/2,500,
  Legendary 3,000/5,000, Mythic 5,000/10,000, Divine 8,000/15,000, Special 12,000/20,000 (last three
  hidden). Epic+ have 10–20% "Prize" chance.
- Rewards per bracket: Coupons 10–45, Whispers 2,500–20,000, Forest Essence 10–90, Foraging XP
  10k–75k, Bits 15–120, HotF XP 100–750 (Moonglade) / 150–900 (Torrhus); Miria: Safari tickets
  (Economy → Premium → First-Class) for the **Critter Safari**.
- **First 3 contests per day**: +1,200 HotF XP (or +1,200 Forest Whispers once HotF 8).
- Coupons buy items + **Foraging level-cap raises** at Agatha/Miria's shops.

## 4.7 Foraging skill, axes, armor

| Fact | Value |
|---|---|
| Levels | 0–50 base; cap raised toward **54–57** via log collections & Agatha/Miria shops (*wiki* lists 57 — "(?)" on exact cap) |
| Per-level | **Logger: +4 Foraging Fortune**, +1 Strength (+2 at higher tiers) — totals +228 FF / +100 Str at 57 (*wiki*) |
| Wisdom | Ocelot pet +30, God Potion/Foraging potion +20, Booster Cookie +25, Efficient Forager +10, Moonglade reforge |
| Axes (classic) | Rookie Axe, Promising Axe, Sweet Axe (apples), Efficient Axe, Jungle Axe, **Treecapitator** |
| Axes (Galatea) | Seriously Damaged Axe → Decent Axe → … (sold by Amaury/SwampCut Inc.) → **Fig Hew? / Mangrove …? / Helix Chopper** (?) |
| Armor (Galatea) | Canopy armor (Amaury), **Fig armor** (+40 Sweep set), **Helix armor** (+80 Sweep set), Snorkeling armor (Nemo, underwater) |
| Pets | Monkey (Sweep on Park, treecap cd), Ocelot (wisdom), Precursor Drone (no throw penalty) |

---

# 5. How it connects

```
                         ┌──────────── COLLECTIONS (unlock recipes, minions) ───────────┐
                         │                                                              │
 MINING ── ores/gems/metals ──► Bazaar ──► coins ──► gear ──► more Mining Speed/Fortune ┘
   │            │
   │            └─► GEMSTONES ─► slotted into ALL gear (combat stats: Ruby HP, Jasper Str, Onyx CD,
   │                              Sapphire Int; utility: Aquamarine FS, Citrine FF, Peridot Farm F)
   ├─ commissions ─► HOTM XP ─► tokens ─► perks paid in POWDER ─► speed/fortune ─► faster mining
   ├─ Forge (HOTM-gated) ─► Refined metals ─► DRILLS, Divan armor, Glacite/Mineral armor
   ├─ Mining mobs (Goblins, Automatons, Corleone, Bal, Ghosts, Glacite Walkers) ─► COMBAT skill + Bestiary
   ├─ Treasure chests / Nucleus ─► pet items, Pickonimbus, Prehistoric Egg, Divan fragments
   ├─ Mineshafts ─► Frozen Corpses ─► Vanguard/keys, Suspicious Scrap ─► FOSSILS ─► Dr. Stone pets
   ├─ HOTM Subterranean Fisher ─────────────────────┐
   └─ Mining level ─► Spelunker fortune             │
                                                    ▼
 FISHING ── sea creatures = mobs ─► COMBAT XP? + Fishing XP + Bestiary + drops ─► armor sets
   │        (Thunder, Magma Lord, Shark Scale, Sponge, Diver's), pets (Megalodon, Baby Yeti, Flying Fish)
   ├─ trophy fish ─► Magmafish ─► lava gear; titles gate Lord Jawbus
   ├─ treasure ─► enchanted books, Lucky Clover Core
   ├─ festivals tied to MAYORS (Marina) and SEASONS (Jerry's Workshop, Spooky)
   ├─ Hotspots (all islands) ─► reasons to move around the world
   └─ CH worms ─► Worm Membrane ─► Worm Bait / Scatha pet line (mining ↔ fishing)
                                                    ▲
 FORAGING ── logs ─► collections, minions, Treecapitator     │ (Forest Fisher +FS/SCC on foraging islands)
   ├─ Galatea trees ─► Tree Gifts ─► Hunting shards/critters ─► ATTRIBUTE FUSION (Hunting skill)
   ├─ Tree Gifts + Starlyn Contests ─► HotF XP + Whispers ─► HotF perks (Sweep/FF/Strength/Speed)
   ├─ Contests also score FISHING and HUNTING ─► multi-skill daily loop on Galatea
   └─ Forest Essence ─► Rosemary upgrades; Coupons ─► foraging level cap
```

Key cross-links for the build team:
1. **Gemstones are the bridge from mining to every other system** (gear slots). Mining must ship
   before the gemstone-slot system has content to slot.
2. **Sea creatures are combat**. They use the same mob/stat/damage pipeline as slayers & bestiary.
   Fishing XP is granted on kill (and some Combat XP (?)).
3. **Powder trees and HotF use the same engine**: `(tree, currencyTypes[], tiers[], perks[{costExp,
   maxLvl, effect(lvl), requires[]}], core{levels[]}, abilities[])`. Build it once, data-drive both.
4. **SkyBlock-day clock (20 min)** drives: mining events, Sky Mall, Starlyn contests, CH lobby age,
   Fishing Festival (Marina), Jerry season.
5. **Mayors** (another doc): Cole (mining: +XP/+powder/"Mining Fiesta" (?)), Marina (Fishing
   Festival + SCC + "Luck of the Sea 2.0"), Finnegan/Foxy (?) hooks. Expose a `MayorPerk` hook on
   each skill's multiplier pipeline.
6. **Wisdom/Fortune/Speed pipelines** should be a single stat system with "context tags" (island,
   block type, gemstone, mineshaft) because so many perks are conditional ("on mining islands",
   "in mineshafts", "on dwarven metals", "on Galatea").
7. **Pets** are shared: Ammonite (fishing + mining scaling), Scatha/Silverfish/Mole/Bal/Glacite Golem
   (mining), Monkey/Ocelot/Precursor Drone (foraging).

---

# 6. Implementation notes (Paper / Java, custom server)

## 6.1 Custom block breaking (Mining Speed + Block Strength)

**What Hypixel does (1.8-era design, still visible on modern clients):** the client must never
break blocks by itself (or it would predict vanilla hardness). Hypixel gives the player a
**permanent hidden Mining Fatigue** effect on mining islands so client-side progress never
completes, and the server drives the cracking animation by sending **Block Break Animation**
packets (`PacketPlayOutBlockBreakAnimation`, today `ClientboundBlockDestructionPacket`: entity id,
position, stage 0–9, or −1/10 to clear). When server-side progress hits 100% the server breaks
the block and sends the block change + break particles/sound.

**On modern Paper (26.x) do this instead of mining fatigue:**
1. **Freeze client prediction**: set the player's `Attribute.BLOCK_BREAK_SPEED` base value to `0`
   (attribute exists since 1.20.5; it multiplies vanilla dig speed). The client still sends
   *start digging* and keeps swinging, but never finishes on its own. (Fallback for older
   versions: Mining Fatigue amplifier ≥ 3 with `particles=false, icon=false` — note Mining Fatigue
   also slows arm swing, which looks wrong; the attribute does not.)
   - Keep `0` only where custom breaking applies; restore `1.0` elsewhere (Private Island may want
     vanilla, or the same engine — pick one and be consistent).
2. **Detect start**: `BlockDamageEvent` (fires on START_DESTROY_BLOCK). **Abort**: Paper's
   `BlockDamageAbortEvent`. These are not enough on their own — with speed 0 the client may not
   re-send a start when it re-targets, so also:
3. **Per-player dig state** ticked every server tick:
   ```
   DigState { Block target; float progress; int lastSwingTick; int sourceId = player.getEntityId(); }
   onSwing (PlayerAnimationEvent ARM_SWING) → lastSwingTick = now
   tick():
     if now - lastSwingTick > 6  → abort (clear animation, progress = 0)   // player stopped holding
     hit = player.rayTraceBlocks(blockInteractionRange)                     // must still look at it
     if hit.block != target      → abort, start on new block if swinging
     if toolBP < block.BP        → show hint once, do not progress
     ticksToBreak = max(4, round(strength * 30 / miningSpeed))              // 2.3; recompute EVERY tick
     if miningSpeed > instaThreshold(block) → break now
     progress += 1f / ticksToBreak
     broadcast stage = floor(progress * 10) - 1 → player.sendBlockDamage(loc, progress, sourceId)
                                                    for every player within 32 blocks
     if progress >= 1 → breakBlock(); progress = 0
   ```
   Recomputing every tick matters: Mining Speed Boost, Sky Mall, Gone-with-the-Wind, and tool
   swaps all change speed mid-break.
4. **Instamine**: if speed exceeds the threshold at the *first* swing, break in the same tick
   (Hypixel players can "hold to mine" a whole wall at instamine speed — each new targeted block
   breaks immediately as the crosshair reaches it; no 4-tick floor).
5. **breakBlock()**:
   - Drops: compute `baseDrop × fortune` (+ Pristine roll for gems), give **directly to inventory
     / sacks** (Hypixel sends mining drops to inventory since Telekinesis was folded into the base
     game). Overflow → drop at feet with "Inventory full" warning.
   - Powder, Mining XP (× wisdom), collection, commission/contest/bestiary progress — publish one
     `GatherEvent(player, blockKind, amount, context)` and let every system subscribe.
   - Visuals: `world.playEffect(loc, Effect.STEP_SOUND, blockData)` (vanilla break particles +
     sound for the *original* block), then set the placeholder. Clear the destruction animation.
   - **Spread** (2.4): queue neighbour breaks next tick, not recursively in the same tick.
   - Rare-event rolls: treasure chest (CH hard stone), worm spawn, goblin spawn, mineshaft pity,
     Suspicious Scrap, Titanium conversion.
6. **Multi-miner**: each miner uses their own `sourceId` (their entity id) in the destruction
   packet; two people mining the same block see two independent cracks (Hypixel behaves this way:
   progress is per player, first to 100% wins).
7. **Breaking Power hint**: action bar `§cYou need a tool with ⛏ Breaking Power X to mine this!`
   once per block per 2 s.
8. **Pickaxe abilities**: right-click with tool → ability; Pickobulus = thrown item display that
   explodes after ~1 s, breaking all eligible blocks in radius (respecting BP, no gems?) and
   granting drops/powder; Mining Speed Boost = temporary multiplier in the stat pipeline.

## 6.2 Block regeneration

```
RegenEntry { World w; int x,y,z; BlockData original; BlockData placeholder; long restoreAtTick; RegenRule rule; }
PriorityQueue<RegenEntry> by restoreAtTick ; Map<BlockPos, RegenEntry> index (dedupe)
```
- On break: set placeholder (bedrock in Dwarven Mines/Glacite, stone/cobble for vanilla ores,
  air in CH/no-regen zones), enqueue with the area's delay.
- Each tick pop due entries; skip/push if a player's hitbox occupies the block (or restore and
  nudge the player up). Batch block changes per chunk (`setBlockData(..., applyPhysics=false)`).
- **Regen rules per region** (data-driven): `{blockKinds, placeholder, delayMinTicks,
  delayMaxTicks, replacementTable}` — e.g. Dwarven Mines mithril restores as a weighted random
  grade (gray 60% / prismarine 30% / light-blue 10% (?)); Titanium reverts to stone (?) or mithril.
- **Persistence**: on plugin disable restore everything synchronously; also write the queue to the
  DB every N seconds so a crash doesn't leave bedrock scars (or rebuild from a pristine
  schematic of the area on startup — simplest and crash-proof).
- **Chunk unload**: let the entry fire anyway (setting a block loads the chunk) or restore on
  `ChunkLoadEvent` — avoid force-loading hundreds of chunks.
- **Titanium spawn**: on mithril break with chance `p` (Titanium Insanium), pick a random stone
  (Dwarven "stone" = polished andesite? (?)) within 2 blocks of the broken block and set it to
  Titanium (polished diorite); it is itself a regen-managed block that reverts after ~(?) s if
  unmined.
- **Crystal Hollows**: instanced world per lobby (generate from noise or copy one of N templates);
  no regen; lobby TTL = 35 SB days; close-to-new at day 19 (*wiki*).
- **Mineshafts**: instanced per party from a variant template; destroyed on exit.

## 6.3 Fishing hook logic

Paper gives most of this:
- `PlayerFishEvent` states: `FISHING` (cast), `BITE`, `CAUGHT_FISH`, `CAUGHT_ENTITY`, `IN_GROUND`,
  `FAILED_ATTEMPT` (reeled with nothing), `REEL_IN`, `LURED` (fish approaching (?)).
- `FishHook` API: `setWaitTime(min,max)` / `setMinWaitTime` / `setMaxWaitTime`, `setApplyLure(false)`,
  `setLureTime`, `setSkipLureAnimation(true)` (Paper), `getState()` (`UNHOOKED`, `HOOKED_ENTITY`, `BOBBING`).

Water flow:
1. On `FISHING`: read the rod (must be a water rod), compute `ticks = BaseTicks × (1 − FS/cap)`
   with the island's cap (3.2), call `hook.setApplyLure(false)` and `hook.setWaitTime(ticks)`
   (+ consume bait, apply bait/rod/hotspot modifiers into a `CastContext` stored on the hook's PDC).
2. On `BITE`: show the bite tell — a **TextDisplay "!!!"** (red/bold) ~0.5 blocks above the bobber,
   splash particle + sound. Hypixel's tell is exactly this "!!!" marker. Start a bite window
   (vanilla ~20–40 ticks).
3. On `CAUGHT_FISH`: `event.getCaught()` is the vanilla item entity — remove it. Run the roll chain
   (3.1). Give items **to inventory**; spawn sea creatures at `hook.getLocation()` with velocity
   toward the player (`(player − hook).normalize() × 0.6 + (0,0.4,0)`), tag owner, start 6-min
   despawn timer, enforce caps (60/lobby, 10/player).
4. On `FAILED_ATTEMPT` / `REEL_IN` without bite: nothing (optionally "too early" message).
5. Remove "!!!" marker on any reel or hook despawn.

**Lava fishing** (vanilla bobbers do not fish in lava) — custom:
- When a lava rod is cast, run a per-tick task on that hook: once its bounding box enters lava,
  set velocity to 0, `setGravity(false)`, snap Y to the lava surface + 0.1 and make it bob with a
  small sine; make it fire-immune / invulnerable (hooks are not normally destroyed by lava, but
  verify on your version (?)).
- Run your own wait timer (same formula, Crimson cap 350), then the same "!!!" + `LAVA`/`FLAME`
  particles + `BLOCK_LAVA_POP` sound; on the player's next `REEL_IN` within the window, resolve
  the catch via the same roll chain but with lava pools + **trophy fish roll** (3.7, using hook
  context: distance to geyser/sulphur ore, depth below player, time bobber has been out, bait).
- Water rod in lava / lava rod in water: no bite, ever (optionally an action-bar hint).
- **Golden Fish** (3.7): a per-player timer starting at first lava cast; after ~8 min spawn a
  visible golden fish entity swimming near the bobber; catching it requires reeling with the
  bobber close to it (?).

**Sea creature mobs**: use the shared custom-mob engine (HP, damage, level nametag
`[Lv100] Water Hydra 500k/500k❤`), plus fishing-specific rules: rods deal ×2, arrows immune
(except Prismarine Bow), Hydra split at 50%, Jawbus sphere phases. Fishing XP + Bestiary credit
on kill to the owner.

## 6.4 Foraging (Treecapitator / Sweep)

```
onLogBreak(player, log):
   n = logsFromSweep(player, tree)            // 4.3; zero-toughness: 1 + Sweep, cap 36
   set = BFS from log over 26-neighbourhood (incl. diagonals), same log family, ignore placed-by-player logs,
         order by BFS depth, take first n
   break them over a few ticks (Hypixel's treecap visibly "eats" the tree in a quick wave), each with
   break particles; give logs × FF to inventory; Foraging XP per log; schedule tree regrowth (Park)
```
- Park regrowth: store the whole tree's original blocks (logs+leaves) at first cut; restore
  together after the delay so trees never regrow half-cut.
- Galatea trees: each tree is an object `{id, sizeMult 1–20, toughness, styleOrder[], logs[],
  contributors: Map<UUID, int>}`; per-player share → Tree Gift on completion; respawn the tree
  (same spot or a new one) after a delay.

## 6.5 What the player sees (UX checklist)

| Moment | Feedback |
|---|---|
| Mining a block | per-player crack animation stages 0–9; action bar shows `+X ᠅ Mithril Powder` on each block, skill XP gains as `+12.5 Mining (45.2%)` |
| Can't mine | action bar BP warning |
| Rare drop | chat `RARE DROP! Pickonimbus 2000 (+ 150% ✯ Magic Find)`, sound (note pling) |
| Pristine | chat/action bar `PRISTINE! You found ❈ Flawed Amethyst Gemstone x2!` |
| Treasure chest spawn | chest block appears, chat "You uncovered a treasure chest!", particles on lock point |
| Mineshaft | big title + sound, party prompt, entrance ladder |
| Event start | boss bar with timer + chat |
| HOTM level up | title "HEART OF THE MOUNTAIN LEVEL UP!" + tokens |
| Fishing bite | "!!!" above bobber + splash |
| Sea creature | chat message unique per creature ("A Water Hydra has come to test your strength." / "You hear a massive rumble beneath your feet..." for Thunder/Jawbus); legendary+ broadcast to the party |
| Double hook | chat "It's a Double Hook!" |
| Treasure | "GOOD CATCH! You found a …" / "GREAT CATCH!" / "OUTSTANDING CATCH!" |
| Trophy fish | `TROPHY FISH! You caught a Blobfish BRONZE.` |
| Treecap | logs vanish in a wave; action bar `+35 Foraging XP` per tree |
| Tree Gift | "TREE GIFT!" + loot list in chat |
| Contest | scoreboard line with points / bracket |

Sidebar (scoreboard) for mining islands: area name, powder counts (Mithril / Gemstone / Glacite),
current event + timer, commissions with % progress, Heat (CH) or Cold (Glacite).

---

# 7. Progression placement

| Stage | Mining | Fishing | Foraging |
|---|---|---|---|
| **Day 1 (fresh)** | Hub Coal Mine with wooden→stone→iron pick; vanilla ores; Mining 1–5 | basic rod in Hub; Squid/Sea Walker; Fishing 1–7 | Hub Forest oak with any axe; Foraging 1–5 → Park unlocks |
| **Early (hours 2–10)** | Gold Mine → **Deep Caverns** (Lapis 5? → Obsidian Sanctuary 12?); Mining 5–12; Stonk/diamond pick | Challenging Rod (10), Rod of Champions (15); Sea Witch→Catfish; Angler/Lure books; Fishing 10–15 | Park 1→5 levels, Jungle Axe, **Treecapitator** (Jungle Log VII); Foraging 10–20 |
| **Early-mid** | **Dwarven Mines** (Mining 12): commissions → HOTM 1–3, Mithril Pickaxe → Mithril Drill (HOTM 2), Forge; Mithril/Titanium | Rod of Legends (20), Sea Leech/Defender/Protector; first Water Hydra at 19; Shark festivals; Spooky/Jerry events | Foraging 20–30 via Park; minions |
| **Mid** | **Crystal Hollows** (HOTM 3–4): gemstones, Nucleus runs, Pickonimbus, Gemstone drills, Gemstone Powder; HOTM 4–6 | Rod of the Sea (24); CH worms (Worm Membrane); Shark Scale; Dolphin/Megalodon pets; trophy fishing begins with Starter Lava Rod (Crimson Isle, Combat 24) | Foraging 30–40; Monkey pet |
| **Mid-late** | HOTM 7 → **Glacite Tunnels**: Glacite/Umber/Tungsten + 4 new gems; Mineshafts & corpses; Fossils; Titanium DR-X555/655 | **Crimson Isle lava**: Magma Rod (27) → Inferno (30) → Hellfire (35); Thunder (36); Thunder/Magma Lord armor; Diamond trophy grind | **Galatea** at Foraging 12 (early in level terms, but Galatea gear is mid-game in power): Fig/Mangrove, HotF 1–4, Agatha contests |
| **Late** | HOTM 8–10, Divan's Drill (BP 10), Divan armor, Perfect gems, Vanguard corpses, max Pristine | **Lord Jawbus** (45, needs Adept Trophy Fisher), Ragnarok (47), Water Hydra/Emperor farming, Bobbin' Time | Torrhus Canyon (HotF 4), Helix trees, HotF 5–8, Foraging 50→57, Helix armor/Chopper |
| **Endgame** | maxed HOTM (12.7M/31.2M/34.5M powder), mining events farming, Bal/Scatha pets | all Diamond trophies, Hellfire maxed, festival Megalodon | maxed HotF (14.8M FW + 52.7M DW), hunting/attributes |

Suggested build order for the clone (dependency-driven):
1. **Stat pipeline with context tags** + custom block-break engine (6.1) + regen (6.2) — everything
   in mining and foraging sits on these.
2. Hub/Deep Caverns ores + Mining skill + Fortune → validates the loop end to end.
3. Fishing engine (6.3) with the regular water pool + treasure + baits — independent of mining.
4. Dwarven Mines + commissions + HOTM engine (data-driven, reused for HotF) + Forge + drills.
5. Park + Treecapitator/Sweep.
6. Crystal Hollows (instanced worlds — biggest single feature), gemstones + slots.
7. Crimson Isle lava fishing + trophy fish (needs combat island to exist).
8. Glacite Tunnels + mineshafts + corpses + fossils.
9. Galatea + HotF + Tree Gifts + contests (needs Hunting system from the other doc).
10. Events (mining events, Fishing Festival, Jerry, Spooky) once the SkyBlock calendar exists.

---

## Appendix A — Formula cheat sheet

| What | Formula |
|---|---|
| Ticks to break | `max(4, round(strength × 30 / miningSpeed))`; instamine if `speed > 30×strength` (standard) / `> 60×strength` (ores, obsidian, gems, metals) |
| Fortune drops | `base × (1 + fortune/100)`, fractional part = chance of +1 |
| Pristine | per rough gem, `Pristine%` chance to upgrade it to Flawed (≈ ×80) |
| HOTM/HotF perk cost | `floor((nextLevel + 1) ^ e)`; keystones `(nextLevel + 7)^5` |
| Fishing wait | `BaseTicks ~ U(200, 400 − 200×lure)`, `ticks = BaseTicks × (1 − FS/cap)`, cap 300 default |
| Sea creature? | `min(SCC, 100)%` (SCC base 20; ÷4 on Private Island/Garden) |
| Which creature | `weight / Σ eligible weights`, ×1.25 rare boosts, replacement pools (shark 15%, hotspot 15%, spooky, Carrot King 5%, Agarimoo 15%) |
| Standard pool ladder | 5,000 / 2,875 / 1,500 / 500 / 100 / 25 → 50 / 28.75 / 15 / 5 / 1 / 0.25 % |
| Trophy tier | Diamond 0.2%, Gold 2%, Silver 25%, else Bronze; pity Gold at 100, Diamond at 600 |
| Mineshaft spawn | pity counter 2,000 − Σ block quality; base 1/2,000; variant 1/17, or 1/3 if matches last block |
| Mineshaft exit powder | `1,000 + 500×scrap + 250×corpses + 10×cold` |
| Sweep (zero toughness) | `logs = clamp(1 + Sweep, 1, 36)` |
| Tree Gift share | <10% none, ≥10% ×1, ≥20% ×1.5, ≥33% ×2; × tree size 1–20 |

## Appendix B — Things deliberately left to other research docs
- Skill XP curves (levels 1–60 XP table), SkyBlock XP, Bestiary milestones.
- Minions (mining/foraging/fishing minions), Collections tiers & recipes.
- Pets (full stat tables), Mayors (Cole, Marina) perk numbers, Hunting skill & shard fusion.
- Bazaar item list (enchanted forms: Enchanted Mithril = 160 Mithril, etc.).
