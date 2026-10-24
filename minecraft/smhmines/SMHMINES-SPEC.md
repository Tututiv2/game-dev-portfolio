# SmhMines — Reverse-Engineered Spec (Season 1 Alpha)

> Source: 38 in-game screenshots taken 2026-08-01 by the owner (`smhTututi`, `[Owner]` rank) on the
> live Minehut server `smhmines.minehut.gg`. Store: `smhmines.tebex.io`.
> Everything below is **observed**, not invented. Uncertain items are marked `?`.
> This is the source of truth for the rebuild until the owner corrects it.

---

## 1. The core idea (what makes this different)

There is **no economy plugin**. No money, no `/sell`, no rankup command, no token enchant GUI.

- The **raw ore IS the currency.**
- **Vanilla villager trade GUIs are the entire shop UI.**
- Tools **upgrade in place**: you hand the villager your current tool + material, and get the same
  tool back one tier higher. The tool is the save file.
- Every item is **Unbreakable** — no durability, no repair loop.

The whole game is one long **material chain**:

```
mine a cube of ore  →  compress it  →  trade ore + old tool for a better tool
      ↑                                              ↓
      └──────── the better tool opens the next cube ─┘
```

That's it. It runs in ~800 MB / 1024 MB RAM on Minehut free tier because there is almost no
custom server logic — it's item NBT + villager trades + a static build.

---

## 2. World layout

Void world, everything floats. Skybox is purple/blue with a custom cloud layer.

### Spawn hub
A large flat white/quartz platform with a central raised octagonal dais and a vertical yellow
beacon beam. Four paths lead off it:

| Direction | Leads to | Status |
|---|---|---|
| **Left** | The first ~4 **Starter Mines**, with **crates** in between them | Built |
| **Straight ahead** | — | **PvP — direction confirmed, content unbuilt** |
| **Right** | **TRADERS** (the big domed hall) | Built |
| **Hard left, at the gold block** | A fourth path "behind" spawn to somewhere else | **Bosses — direction confirmed, content unbuilt** |

Owner's direction 2026-08-01: **"yes pvp and bosses, want there to be pvp but dont know where."**
So both dead paths have a *destination* now but no design. The rebuild ends both at **sealed
gates** — built, lit, signposted, and closed — so the hub geometry is final and the content can
land later without rework. This also retroactively explains the gem-tier **Max Health** attribute:
that gear was being balanced against something that hits back.

Signs at the junction read `Starter Mines <——` and `Mines ——>`.

### Mines
Mines are **small solid cubes of a single block type**, floating on their own platforms in the void.
Not weighted-composition cuboids — one cube = one material. Observed near spawn: a coal-ore cube,
a wood/log cube, others further down the row.

**Confirmed by the owner 2026-08-01: the cubes are TINY and reset FREQUENTLY.** They look large in
the screenshots only because they're photographed close up. This is a significant design fact —
a small cube on a fast timer means:
- the reset is cheap enough to do synchronously; no async slicing or FAWE needed
- the loop is "clear the cube, mine it out in seconds, it's back" — closer to an idle-game tick
  than to a prison mine you spend ten minutes in
- pacing comes from the **compression ratios**, not from mine size

The live server ran **MineResetLite** (confirmed via `/plugins`), which is timer- or
percentage-based per mine.

### Planets
Above spawn, giant **deepslate spheres** arranged in an **upward spiral**. Reached by **elytra**.

- An **Elytra Trader** sits somewhere inside the Traders hall and sells an elytra for
  **compressed steel**. That trade is the gate between the ground game and the sky game.
- Each planet is hollow. You enter through a doorway on top and descend an internal
  **purpur spiral staircase**, with a landing at each level.
- Each landing holds **one ore cube + its merchant pair** (Merchant + Compresser on pedestals).

**Planet 1 — descending order:**

| Depth | Ore | Cube appearance |
|---|---|---|
| 1 (top) | **Emerald** | deepslate emerald ore |
| 2 | **Sapphire** | blue crystal ore (custom) |
| 3 | **Ruby** | red crystal ore (custom) |
| 4 | **Amethyst** | purple crystal ore (custom) |

More planets with further tiers exist above/beyond. Not yet captured.

---

## 3. The Traders hall

Domed glass building signed **TRADERS**. Inside, each material tier gets its own themed stall
(colour-coded: wood = green/oak, stone = grey, coal = black, gold = yellow, diamond = cyan…).

Each material tier has up to four villagers, named in green above their head:

| Villager | Sells |
|---|---|
| **`<Mat>` Toolsmith** | Pickaxes + Axes (+ shovels?) |
| **`<Mat>` Weaponsmith** | Swords |
| **`<Mat>` Merchant** | Armor (helmet / chest / legs / boots) |
| **`<Mat>` Compresser** | Compressed + Super Compressed materials |

Observed stalls: **Wood, Stone, Coal, Steel, Gold, Diamond** (ground) and
**Emerald, Sapphire, Ruby, Amethyst** (planet 1).

### Standard trade-slot layout
The stalls follow a consistent 7-slot pattern with a **red barrier item as a visual separator**
between item categories:

- **Toolsmith:** 3 pickaxe rows → barrier → 3 axe rows
- **Weaponsmith:** 3–6 sword rows → barrier
- **Merchant:** 4 armor pieces (base) → barrier → 4 armor pieces (upgrade)

---

## 4. The Compresser (the economy's whole backbone)

Observed on the ground-tier Compresser:

| Input | Output |
|---|---|
| 16 wood | Compressed Wood |
| 24 stone | Compressed Stone |
| 32 coal | **Compressed Coal** *(Unbreakable)* |
| 48 iron | Compressed Iron |
| **2 iron + 2 coal** | **Steel** *(Unbreakable)* ← the only two-input recipe seen |
| 64 + 48 gold | Compressed Gold |
| 16 diamond | Compressed Diamond |

Gem-tier compressers do two steps:

| Compresser | Input | Output |
|---|---|---|
| Emerald | 32 emerald | Compressed Emerald |
| Emerald | 16 compressed | **Super Compressed Emerald** |
| Sapphire | 32 sapphire | Compressed Sapphire |
| Sapphire | 24 compressed | **Super Compressed Sapphire** |

**Steel = 2 iron + 2 coal** is the design's smartest move — it forces you to work two mines at
once instead of grinding one, and compressed steel is the elytra gate.

---

## 5. Tool progression (observed exactly)

Tools carry a tier number in the display name, e.g. `Wooden Pickaxe [2]`, and gain enchants +
attribute lines as they climb. Each tier has a flavour line in the lore.

### Wood
**Toolsmith**
| Trade | Result |
|---|---|
| 8 wood | `Wooden Pickaxe [1]` |
| pickaxe + 64 wood | `[2]` — Efficiency 2, +5 Mining Efficiency, 2 atk, 1.2 spd |
| pickaxe + 8 compressed wood | `[3]` |
| 16 wood | `Wooden Axe [1]` |
| axe + 4 compressed wood | `[2]` — Efficiency 2, +5 Mining Efficiency, 7 atk, 0.8 spd |
| axe + 8 compressed wood | `[3]` |

**Weaponsmith** — `Wooden Sword`, 6 tiers, 4 atk / 1.6 spd throughout:
8 wood → `[1]`; +16 wood → `[2]`; +48 wood → `[3]`; +64 wood → `[4]`;
+2 compressed → `[5]`; +4 compressed → `[6]` *(Sharpness 5)*

**Merchant** — `Wooden <piece>`, lore *"Splinters ruined the armor"*:
16 wood → `[1]` (+3 Armor chest); +48 wood → `[2]` (Protection 2);
+6 compressed wood → `[3]` (Protection 5)

### Stone — lore *"Forged with Burning Hands"*
**The previous tier's maxed tool is the ingredient for the next tier's tool.**

| Trade | Result |
|---|---|
| `Wooden Pickaxe [3]` (Eff 5, +26 Mining Eff) + 64 stone | `Stone Pickaxe [1]` — 3 atk |
| + 8 compressed stone | `[2]` |
| + 16 compressed stone | `[3]` |

Weaponsmith: sword + 64 stone → `Stone Sword`; +8 compressed → `[2]`; +16 compressed → `[3]`
(Sharpness 5, 5 atk).
Merchant: wooden armor + 64 stone → `[1]`; + 5 compressed stone → `[2]`
(Protection 2, +1 Armor, +1 Toughness).

### Coal — lore *"Forged in Darkness"*
Merchant: stone armor + 32 coal → coal armor; + 4 compressed coal → `[2]`
(Protection 2, +1 Armor, **+2 Toughness**).
Weaponsmith: sword + 32 coal → `Coal Sword`; +3 compressed → `[2]` (Sharpness 3, 5 atk);
+6 compressed → `[3]`.

### Steel — lore *"Smelted into Refurnished Iron"*
Toolsmith: pickaxe + 1 steel → `Steel Pickaxe [1]` (4 atk); + 4 steel → `[2]`; + 8 steel → `[3]`.
Same 1 / 4 / 8 ladder for the axe.

### Gold — lore *"Precision was Needed to Craft This"*
Toolsmith: pickaxe + 1 gold → `Gold Pickaxe [1]` (5 atk); +4; +8.
Merchant armor is renamed: **`Crown Chestplate [2]`** — Protection 2, +2 Armor, +3 Toughness.

### Diamond — lore *"Crafted by the Rarest Materials"*
Merchant armor renamed: **`Crystalied Chestplate [2]`** — Protection 2, +3 Armor, +3 Toughness.
*(spelling "Crystalied" is as-shipped)*

---

## 6. Gem tier (planets) — where the numbers explode

Flat pricing per merchant: **armor = N gems each, tools/weapons = M gems each.**

| Tier | Armor | Tools/Weapons | Lore |
|---|---|---|---|
| Emerald | 4 | 6 | *"Crystalised with Gemstones"* |
| Sapphire | 6 | 8 | *"Crystals Found Down Below"* |
| Ruby | 8 | 10 | *"Made With Blood Crystals"* |
| Amethyst | 10 | 12 | *"Pretty Little Crystals"* |

Observed items:

| Item | Stats |
|---|---|
| Emerald Helmet | Protection 5, +3 Armor, +4 Toughness |
| Emerald Sword | Sharpness 10, **Area 1**, 7 atk, +0.5 Sweeping |
| Emerald Pickaxe | Efficiency 8, Fortune 1, **+65 Mining Efficiency**, 5 atk |
| Emerald Axe | Efficiency 8, Fortune 1, +65 Mining Efficiency, 9 atk, 1.0 spd |
| Sapphire Chestplate | Protection 5, +4 Armor, +4 Toughness, **+1.5 Max Health** |
| Ruby Helmet | Protection 5, **+5 Armor**, +4 Toughness, +1.5 Max Health |
| Ruby Blade | **Sharpness 15**, **Area 3**, 7 atk, +0.75 Sweeping |
| Amethyst Pickaxe | **Efficiency 15, Fortune 4, +226 Mining Efficiency** |

**`Area N` was a custom enchant** (on swords — an AoE sweep radius, from CrazyEnchantments).
It was the only non-vanilla enchant in the entire game.

> ⛔ **CUT FROM THE REBUILD.** Owner's call, 2026-08-01: *"remove that stuff completely honestly
> lol i dont want it."* Consequence: the rebuild needs **no custom enchant system at all** —
> every enchant in SmhMines is now vanilla (Efficiency, Fortune, Sharpness, Protection) plus
> attribute modifiers. Do not build an enchant framework. If sword tiers need to feel different,
> use Sharpness levels and attribute modifiers, not a new enchant.

**Mining Efficiency curve:** 5 → 26 → 65 → 226. Roughly ×3–4 per tier. That's the pacing dial.

Gem-tier armor also starts granting **Max Health**, which vanilla armor never does — the gem tier
is where the game stops being about mining speed and starts being about survivability. (Against
what? See open questions.)

---

## 7. Presentation

- **Sidebar scoreboard:** `SmhMines SEASON 1 Alpha` — player name + ping, date/time, X/Y/Z
  location, server RAM, online count, jumps, playtime, `IP: smhmines.minehut.gg`,
  `Discord: type /discord`, `smhmines.tebex.io`. Numbered right-hand column 0–13.
- **Tab list header:** `SmhMines` / `>> W_ <player>! <<` / online players / online staff / ping /
  used memory / rank+name+ping row / time / `Visit our webpage smhmines.tebex.io`.
- **Rank tag:** `[Owner]` in red with prefix icons.
- Villager names are green floating text. Trade GUIs are renamed to the villager's name.

---

## 8. Open questions for the owner

1. **Do the mine cubes regenerate?** Timer, percentage, per-block, or are they just enormous?
2. **What is the sky path gated on besides the elytra** — do you need a certain tool tier to break
   emerald/sapphire/ruby/amethyst at all?
3. **What are the crates** between the starter mines, and what's in them?
4. **What is `Area N` actually doing** — sweep radius, or does it break blocks?
5. **Why does gem armor grant Max Health** — are there mobs/bosses, or was that the plan for the
   two unbuilt paths?
6. **What was straight-ahead and hard-left going to be?**
7. **What plugin(s) drive this** — custom, or config-only (villager NBT + a mine-reset plugin)?
8. How many planets exist, and what tiers come after Amethyst?

---

## 9. What the rebuild has to preserve

The thing that makes SmhMines *SmhMines* and not generic skyblock:

1. **No currency.** Ore is money. Never add a `$` balance.
2. **The tool is the progress bar.** You upgrade the same item forever; it is never replaced,
   only transmuted.
3. **Villager trades are the whole UI.** No custom GUIs, no `/shop`.
4. **Everything unbreakable.** Durability would break the entire fantasy.
5. **The compresser is the pacing valve.** All difficulty tuning happens in the compression ratios.
6. **Steel (2 iron + 2 coal) is the model for every interesting recipe** — cross-mine dependencies,
   not single-resource grinds.
