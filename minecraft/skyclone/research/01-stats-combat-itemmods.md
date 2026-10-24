# 01 — Stats, Combat Math & Item Modifiers (Hypixel SkyBlock reference)

> Research note for the SkyBlock clone. Scope: every player stat, how damage in and out is computed,
> healing/mana/vitality, and **every way an item gets modified** (reforges, enchantments, potato books,
> recombobulator, stars, gemstones, enrichments, books/scrolls, accessory power + powers + tuning),
> plus rarity and the exact tooltip (lore) layout.
>
> **Snapshot date: 2026-10-02.** Values are current as of the **June 10 2026 "Healing Revamp"** (SkyBlock 0.26.1),
> which turned Vitality into a resource, flattened most %-max-HP healing, and renamed/reworked several
> enchantments. Where an older value is still widely quoted it is given as *old → new*.
>
> **Confidence legend:** plain = taken from a primary data source (Hypixel public API `resources/skyblock/items`,
> the NotEnoughUpdates community data repo `constants/*.json`, or the community wiki at
> hypixelskyblock.minecraft.wiki — the official wiki.hypixel.net was shut down in July 2026 and now redirects
> to a forum notice; the Fandom wiki refuses automated fetches). **(?)** = from memory or inferred; verify, or treat
> it as a free design choice.
>
> Data-sourced tables (blacksmith reforges, reforge stones, gemstone values, enchant XP costs, essence costs)
> were generated **mechanically** from NEU-REPO JSON. NEU is maintained by players and can lag the game by
> a patch or two, but its numbers are what the major mods display in-game.

---

## 0. Overview — how the pieces fit

A player's power is one number pipeline:

```
            ┌──────── PLAYER BASE (100 HP, 30 CC, 50 CD, 100 Speed, 20 SCC, ...) ─────────┐
            │  + permanent: skill levels, SkyBlock level, fairy souls, slayer levels,     │
            │    bestiary milestones, HOTM/HOTF perks, essence-shop perks, etc.           │
            │  + equipped: armor (4) + equipment (4: necklace/cloak/belt/gloves-bracelet) │
            │    + held item (weapon/tool) + pet + accessory bag + potions/buffs           │
            │  each ITEM's contribution =                                                 │
            │     base stats × (star multiplier) + potato books + reforge(rarity)          │
            │     + gemstones(quality × rarity) + enchant flat stats + art of war/peace … │
            │  accessory bag = Σ accessory stats + enrichments                             │
            │                 + POWER × multiplier(Accessory Power) + TUNING points        │
            │  then % modifiers (e.g. "+5% all stats" set bonuses, Renowned +1%)          │
            └───────────────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
   OUTGOING:  (5 + Damage) × (1 + Str/100) × [crit? (1 + CD/100)] × Additive × Multiplicative
              → boss damage cap / magic resistance → target Defense → floor() → × ferocity procs
   INCOMING:  mob damage × 100/(100+Defense) × other reductions;  true damage × 100/(100+TrueDef)
   SUSTAIN:   Health Regen tick, Vitality pool (healing abilities), Mana pool (abilities)
```

Item *rarity* is the hinge: reforge stats, gemstone stats, accessory power, enrichment eligibility and reforge
cost all key off rarity, and the Recombobulator moves an item up one rarity.

---

## 1. Rarity system

### 1.1 Tiers, colours, uses

| # | Rarity | § code | Hex | Typical use | Accessory Power | Random-reforge cost |
|---|---|---|---|---|---|---|
| 1 | COMMON | §f white | #FFFFFF | starter gear, vanilla items | 3 | 250 |
| 2 | UNCOMMON | §a green | #55FF55 | early crafts | 5 | 500 |
| 3 | RARE | §9 blue | #5555FF | early–mid crafts | 8 | 1,000 |
| 4 | EPIC | §5 dark purple | #AA00AA | mid game | 12 | 2,500 |
| 5 | LEGENDARY | §6 gold | #FFAA00 | late game | 16 | 5,000 |
| 6 | MYTHIC | §d light purple | #FF55FF | end game / recombed legendaries | 22 | 10,000 |
| 7 | DIVINE | §b aqua | #55FFFF | recombed Mythic (e.g. Divan's Drill); only mining reforges & mining gems have Divine rows | 28 | 15,000 |
| 8 | SPECIAL | §c red | #FF5555 | trophies, cosmetics, event items | 3 | 25,000 |
| 9 | VERY SPECIAL | §c red | #FF5555 | recombed Special | 5 | 50,000 |
| 10 | ULTIMATE | §4 dark red (?) | #AA0000 | skins only | 1 | — |
| 11 | ADMIN | §4 dark red | #AA0000 | admin items | 22 | — |

(Colour codes from NEU `misc.json` `tier_colors`; ULTIMATE/ADMIN codes from memory.)

Rules:
- **SPECIAL / VERY SPECIAL** get the **MYTHIC** row of reforge stats (with stronger item abilities), but count as only 3 / 5 MP in the accessory bag.
- **Enrichments** need LEGENDARY, MYTHIC, (DIVINE), SPECIAL or VERY SPECIAL accessories.
- Gemstone stat tables have columns COMMON→MYTHIC (+DIVINE for mining gems); SPECIAL items use the MYTHIC column (?).
- Rarity is upgraded by **one** step by a Recombobulator 3000 (once per item, §15). The Auto-Recombobulator accessory recombs some dungeon drops; such items cannot be recombed again.
- Dungeon drops that roll a **50% quality** are auto-upgraded one rarity (§16.3).
- Pets have their own rarity ladder (Common → Mythic); pets and pet items cannot be recombobulated normally.

### 1.2 Rarity footer line (last lore line)

Format: bold, rarity colour, `RARITY [DUNGEON ]TYPE`.

| Case | Footer (with § codes) |
|---|---|
| normal | `§6§lLEGENDARY SWORD` |
| dungeon item | `§6§lLEGENDARY DUNGEON SWORD` |
| recombobulated | `§d§l§ka§r §d§lMYTHIC DUNGEON SWORD §d§l§ka` — a scrambling (`§k`) "a" glyph on both ends, in the new rarity colour |
| accessory | `§5§lEPIC ACCESSORY` |
| armor | `§6§lLEGENDARY CHESTPLATE` |
| no type | `§f§lCOMMON` |

Type words: SWORD, BOW, LONGSWORD, WAND, FISHING ROD, FISHING WEAPON, PICKAXE, DRILL, AXE, HOE, SHOVEL, SHEARS, HELMET, CHESTPLATE, LEGGINGS, BOOTS,
NECKLACE, CLOAK, BELT, GLOVES, BRACELET, ACCESSORY, HATCESSORY, REFORGE STONE, POWER STONE, DEPLOYABLE, GAUNTLET, VACUUM, CHISEL, FISHING NET, COSMETIC,
PORTAL, BAIT, TRAVEL SCROLL … The live API's `category` field is the machine version of this word; its distribution today:
ACCESSORY 423, HELMET 269, COSMETIC 193, BOOTS 191, CHESTPLATE 191, LEGGINGS 183, SWORD 156, REFORGE_STONE 108, PET_ITEM 95, SACK 67,
FARMING_TOOL 48, BOW 44, PORTAL 42, BELT 41, TRAVEL_SCROLL 40, MUTATION 40, NECKLACE 37, CLOAK 34, AXE 29, GLOVES 25, FISHING_ROD 25, WAND 25,
PICKAXE 24, DUNGEON_PASS 22, BAIT 20, MEMENTO 18, FISHING_ROD_PART 18, DEPLOYABLE 18, … (5,655 item ids total).

---

## 2. The stat catalogue

### 2.1 Combat & general stats

| Stat | Symbol | Colour | Base | Cap | What it does exactly |
|---|---|---|---|---|---|
| Health | ❤ | §c red | 100 | none | Max HP. Action bar `current/max❤`. |
| Defense | ❈ | §a green | 0 | none | Normal damage taken × `100/(100+Def)`. Reduction % = `Def/(Def+100)`. |
| True Defense | ❂ | §f white | 0 | none | Same formula, only against **True Damage** (which ignores normal Defense). |
| Strength | ❁ | §c red | 0 | none | Melee/bow damage × `(1 + Str/100)`. |
| Crit Chance | ☣ | §9 blue | 30% | none (≥100% = always crit; >100% feeds Overload) | Chance a melee/bow hit crits. Abilities don't crit (unless the item says so). |
| Crit Damage | ☠ | §9 blue | 50% | none | Crit hits × `(1 + CD/100)`. |
| Bonus Attack Speed | ⚔ | §e yellow | 0% | 100% | Shortens attack cooldown / target invulnerability; 100% ≈ double hit rate (?). |
| Ferocity | ⫽ | §c red | 0 | 500 | Each 100 = one guaranteed extra strike of the same damage; the remainder is the % chance of one more. 250 Fer = 2 extra + 50% for a 3rd. |
| Swing Range | Ⓢ | §e yellow | 3 blocks | 15 | Melee reach. Longswords (category LONGSWORD) add range (Giant's Sword +1). |
| Intelligence | ✎ | §b aqua | 0 | none | Max Mana = `100 + Int`; ability damage × `(1 + Int/100 × scaling)`. |
| Ability Damage | ๑ | §c red | 0% | none | Ability (magic) damage × `(1 + AbilDmg/100)`. |
| Health Regen | ❣ | §c red | 100 | none | Regen per tick = `(MaxHP/100 + 1.5) × HealthRegen/100`. Doubled out of combat (June 2026). |
| Vitality | ♨ | §4 dark red | 100 | none | **Since June 2026:** max of the Vitality *resource* spent by healing abilities; regenerates 5% of max per second. (Before: a % multiplier on healing received.) |
| Mending | ☄ | §a green | 100 | none | Healing **you apply to other players** × Mending/100. |
| Speed | ✦ | §f white | 100 | 400 (raised to 500 by e.g. Young Dragon set / Black Cat pet (?)) | Walk speed %; vanilla walk speed × Speed/100. |
| Magic Find | ✯ | §b aqua | 0 (old data: 10) | 900 | Rare-drop chance × `(1 + MF/100)` (§9). |
| Pet Luck | ♣ | §d light purple | 0 | none | Pet-drop chance × `(1 + (MF + PetLuck)/100)`. Taming +1/level. |
| Sea Creature Chance | α | §3 dark aqua | 20% | none | Chance a catch is a sea creature. |
| *X* Wisdom | ☯ | §3 dark aqua | 0 | none | That skill's XP × `(1 + Wisdom/100)`. Combat, Farming, Fishing, Mining, Foraging, Enchanting, Alchemy, Carpentry, Runecrafting, Taming, Social, Hunting. |
| Mana (resource) | ✎ | §b aqua | 100 | 100 + Int | Spent by abilities; regen 2%/s of max. |
| Overflow Mana | ʬ | §3 dark aqua | 0 | 780 (current max) | Second pool used after Mana is empty; no natural regen; filled from Soulflow (Soulflow Pile/Battery/Supercell). |
| Absorption | (gold hearts) | §6 gold | 0 | — | Vanilla-style overshield HP, consumed first; HP shows gold. |
| Final Damage | ᚏ | — | — | — | Not a stat: the wiki's name for the number that actually lands (§4). |

### 2.2 Gathering / profession stats

| Stat | Symbol | Colour | Base | Cap | Effect |
|---|---|---|---|---|---|
| Mining Speed | ⸕ | §6 gold | 0 | none | Break time ≈ `30 × blockStrength / MiningSpeed` ticks (?) (mining file owns this). |
| Mining Fortune | ☘ | §6 gold | 0 | none | Every 100 = +1 guaranteed drop, remainder = chance. Sub-fortunes: Ore, Block, Dwarven Metal, Gemstone Fortune (add only on that block class). |
| Breaking Power | Ⓟ | §2 dark green | 0 | — | Gate: block needs BP ≥ its tier. |
| Mining Spread | ▚ | §e yellow | 0 | 10,000 | Every 100 = 1 extra adjacent block broken (not gemstones). |
| Gemstone Spread | ▚ | — | 0 | — | Spread for gemstone blocks. |
| Pristine | ✧ | §5 dark purple | 0 | none | % chance when mining a gemstone to get a higher quality (Rough → Flawed) (?). |
| Heat Resistance | ♨ | §c red | 0 | — | Slows Heat gain in the Magma Fields (burn at 90 Heat). |
| Cold Resistance | ❄ | §b aqua | 0 | — | Slows Cold gain in Glacite areas; Cold −0.5% mining speed per point, −25% speed cap per 25 Cold, death at 100. |
| Farming Fortune | ☘ | §6 gold | 0 | none | Crop drops × (1 + FF/100). Per-crop: Wheat, Carrot, Potato, Pumpkin, Sugar Cane, Melon Slice, Cactus, Cocoa Beans, Mushroom, Nether Wart, Sunflower, Moonflower, Wild Rose Fortune. |
| Overbloom | ✾ (?) | — | 0 | — | Chance for Rare Crops (Garden). |
| Bonus Pest Chance | ൠ | §2 dark green | 0 | — | Each 100 = one extra pest per spawn. |
| Foraging Fortune | ☘ | §6 gold | 0 | none | Log drops × (1 + FF/100). Sub: Fig, Mangrove, Helix Fortune. |
| Sweep | ∮ | §2 dark green | 0 | — | Logs broken per swing; tougher trees need more Sweep per log. |
| Timber | — | — | 0 | — | Chance to fell the whole tree on the first melee axe cut. |
| Fishing Speed | ☂ | §b aqua | 0 | 300 (Crimson Isle 350, Backwater Bayou 200, Lotus Atoll 250) | Shorter wait for a bite. |
| Double Hook Chance | ⚓ | §9 blue | 0 | 100 | Chance to catch two sea creatures. |
| Trophy Fish Chance | ♔ | §6 gold | 0 | 150 | Trophy fish odds. |
| Treasure Chance | ⛃ | §6 gold | 0 | — | Fishing treasure odds. |
| Pull | (?) | — | 0 | — | Fishing Net strength (Hunting). |
| Hunting Fortune | ☘ | — | 0 | — | Every 100 = +1 Attribute Shard. |
| Charm Chance | — | — | 0 | — | Chance mobs drop Attribute Shards. |
| Tracking | — | — | 0 | — | Chance to find Elusive mobs. |
| Respiration | ⚶ (?) | §3 dark aqua | 30 | — | Underwater breathing time. |
| Pressure Resistance | ❍ (?) | §9 blue | 0 | — | Reduces Pressure effects when diving. |
| Fear | ☠ | §5 dark purple | 0 | — | Great Spook event: more Primal Fears, less damage from them. |

### 2.3 Rift-only stats (a separate stat sheet inside the Rift)

| Stat | Base | Effect |
|---|---|---|
| Rift Time ф | 480 s | Time before you're kicked out; damage taken removes time. |
| Rift Damage | 20 | Your damage in the Rift (normal weapons don't work there). |
| Rift Intelligence | 0 | Rift mana + mana regen. |
| Mana Regen | 0 | Rift mana regen. |
| Rift Speed | 100 | Rift movement speed. |
| Hearts ♡ | 10 | Survivability vs certain Rift creatures. |

### 2.4 Effective Health

`EHP = Health × (1 + Defense/100)` (vs normal damage); vs true damage `Health × (1 + TrueDefense/100)`.
Example: 2,000 HP & 900 Def → EHP 20,000. The "Your SkyBlock Profile" menu item shows EHP.

---

## 3. Where stats come from (aggregation)

Every source adds into a **flat total per stat**, except a short list of **% "all stats" modifiers** applied at the end.
Suggested evaluation order:

1. **Base** (§2).
2. **Permanent progression** (other research files own the full tables):

| Source | Stat per level (NEU `bonuses.json`; verify against any newer skill revamp) |
|---|---|
| Farming skill | +2 HP/level for 1–14, +3 for 15–19, +4 for 20–25, +5 for 26+ |
| Fishing skill | same as Farming (HP) |
| Mining skill | +1 Def/level 1–14, +2 for 15+ |
| Foraging skill | +1 Str/level 1–14, +2 for 15+ |
| Combat skill | +0.5 Crit Chance/level; **"Warrior" perk +4% damage/level (1–50), +1%/level (51–60) = +210% additive at 60** |
| Enchanting skill | +1 Int/level 1–14, +2 for 15+, and +0.5% Ability Damage/level |
| Alchemy skill | +1 Int/level 1–14, +2 for 15+ |
| Carpentry skill | +1 HP/level |
| Taming skill | +1 Pet Luck/level |
| SkyBlock Level | +5 HP per level, +1 Strength every 5 levels (?) |
| Fairy Souls (exchanged 5 at a time at Tia) | per exchange +3 HP (+4 every 5th) (?), +1 Def (+2 every 5th) (?), +1 Str, +1 Speed every 10th (?) |
| Zombie slayer | +2/+2/+3/+3/+4/+4/+5/+5/+6 HP at LV1–9 (per level); LV8 also +Health Regen (wiki: +30) |
| Spider slayer | +1 CD (L1–4), +2 CD (L5–6), +1 CC (L7), +3 CD (L8) |
| Wolf slayer | L1 +1 Speed, L2 +2 HP, L3 +1 Speed, L4 +2 HP, L5 +1 CD, L6 +3 HP, L7 +2 CD, L8 +1 Speed, L9 +5 HP |
| Enderman slayer | L1 +1 HP, L2 +1 Int, L3 +2 HP, L4 +2 Int, L5 +3 HP, L6 +3 Int, L7 +4 HP, L8 +4 Int, L9 +5 HP |
| Pet score | +1 Magic Find at 10/25/50/75/100/130/175/225/275/325 pet score (max +10) |
| Bestiary, HOTM, HOTF, Garden, essence perks, Harp, Abiphone, Century Cakes … | see their research files |

3. **Armor** (helmet, chestplate, leggings, boots) — each fully modified item (layering in §12).
4. **Equipment** (necklace, cloak, belt, gloves/bracelet) — same modifiers as armor (reforge pool "EQUIPMENT").
5. **Held item** — weapon/tool stats only while held.
6. **Pet** — pet stats by level + pet item.
7. **Accessory Bag** — Σ accessory flat stats (only the highest tier of each accessory *family* counts) + enrichments + Power × multiplier + Power unique bonus + Tuning.
8. **Active effects** — potions (God Potion), Booster Cookie, Cake buffs, deployables (Orbs, Flares), set bonuses.
9. **% modifiers last**: e.g. Renowned reforge "+1% combat stats & MF" per piece, Superior Dragon set bonus (+% all stats (?)), Legion ultimate,
   Mastiff "+1% HP per 25 CD (max 40%)", dungeon stat boost (only dungeonised items, only in dungeons).
10. **Caps**: Speed 400/500, Attack Speed 100, Ferocity 500, MF 900, Swing Range 15, Fishing Speed (per area), Double Hook 100, Trophy 150.

Debuffs (Heavy armor −Speed, Cold, Mastiff −Def) are negative flat contributions.

---

## 4. Outgoing damage

### 4.1 Melee & bow

```
Initial  = (5 + WeaponDamage) × (1 + Strength/100)
if crit:   Initial × (1 + CritDamage/100)        // roll: rand()*100 < CritChance
Final    = Initial × AdditiveMultiplier × MultiplicativeMultiplier
Final    = Final × (1 − MagicResistance)         // mob-specific
Final    = applyBossCap(Final)                   // BOSS-tagged mobs only
Final    = Final × 100 / (100 + TargetDefense)
Final    = floor(Final)
```
- `WeaponDamage` = the **Damage** stat: the held weapon plus anything else granting +Damage (potato books, a few reforge stones, Tabasco …).
- The **5** is the bare-hand base.
- `AdditiveMultiplier = 1 + Σ(additive %)/100`; `MultiplicativeMultiplier = Π(factors)`.
- Order note from the wiki: the boss cap applies after the additive multipliers, but some multiplicative sources (e.g. Extra Infliction) apply after the cap; Defense order matters near the cap.

**Additive (%) sources** — sum them:

| Category | Sources & values |
|---|---|
| Combat skill | Warrior perk +4%/lvl (1–50) + 1%/lvl (51–60) → **+210%** at 60 |
| Sword enchants | Sharpness 5/10/15/20/30/40/50; Smite, Bane of Arthropods, Ender Slayer 5→50 vs their type; Cubism 5→40; Gravity 5→40 (airborne); Impaling 5→30 (aquatic); Smoldering 5→30 (infernal); Pyroclasm 10→80 (magmatic); Woodsplitter 5→40 (woodland); Prosecute +0.1→1% per 1% of target's **current** HP; Execute +0.2→1.25% per 1% **missing** HP; Giant Killer +0.1→1.2% per 1% max-HP the target has over you, cap 5→65%; Titan Killer +2→14% per 100 target Def, cap 6→80%; First Strike +25→125% first hit; Triple-Strike +10→50% first 3 hits; Combo +1→5% per kill (2→10 kills in 2→10 s); Swarm +2→10% per enemy within 10 blocks (max 10); **One For All +500%**; Spiked Hook 5→35% (fishing weapons); Habanero Tactics +20/25% with slayer weapons |
| Bow enchants | Power 8/16/24/32/40/50/65; Snipe +1→4% per 10 blocks travelled |
| Armor | Maxor's +5%/piece (bow); Magma Lord +30/piece; Thunder +20/piece; Taurus/Flaming/Moogma +10/piece; Tuxedo +75→150; Final Destination +100 vs Endermen; Crimson Dominus +10%/stack; Reaper +100 vs Undead; Terror Hydra Strike +2→15%/stack (all as listed on the wiki's additive list) |
| Pets | Golden Dragon (Legendary Treasure) up to +250; Lion +1.5→150% vs mobs that attacked you; Wither Skeleton +1→100% vs Wither; Ender Dragon +2→200% vs Ender; Tiger +1→100% vs isolated; Zombie +26.25→150% vs Undead; Megalodon up to +150% by missing HP; T-Rex +150% within 1.5 blocks; Giraffe +100% beyond 3 blocks; Kuudra pet +20%; Skeleton (bow) up to +75% (×2 in dungeons); Lion First Pounce multiplies First Strike/Triple-Strike/Combo by k% |
| Pet items | Ring of Love +100 (?); Draconic/Runeblade items vs dragons/woodland; Optical Lens +10% headshots; Kaleidoscopic Mineral +8% headshots |
| Classes/potions | Archery potion +k%; Spider Tamer vs spiders +3→15%; Berserk Lust for Blood +m%/hit (capped) |
| Attribute shards (Hunting) | Dominance (at full HP), Lifeline (<20% HP), Payback (after being hit), Combo (Grandma Wolf combo, max 50), Deadeye (ranged), Warrior (melee), Elite (vs bosses) +k% each; Shardsruler +3/level (max +30, +39 boosted) |
| Special | Hollow Wand Ichor Pool +30%/stack (max 3) |

**Multiplicative (×) sources** — multiply them:

| Category | Sources |
|---|---|
| Mob-type swords | Spider Sword ×2 / Recluse Fang ×2.5 / Tarantula Fang ×3 / Scorpion Foil ×3.5 / Sting ×4 vs Arthropods; End Sword ×2, Voidwalker ×2.5, Voidedge ×3, Vorpal ×3.5, Atomsplit ×4 vs Endermen; Undead Sword ×2, Revenant Falchion ×2.5, Reaper Falchion ×3, Halberd of the Shredded ×3.5 vs Undead; Pooch Sword ×2 vs Wolves; Necron's Blade ×1.5 vs Wither; Sword of Revelations ×1.5 vs Mythological; Daedalus Blade 1 + k% per bestiary tier; Soul Whip ×2, Flaming Flay ×3 vs sea creatures; Prismarine Blade ×3 in water; Spirit Sword vs Undead 1 + 2% per 1% missing HP; Blaze daggers (Firedust ×1.25 / Kindlebane ×1.5 / Pyrochaos ×2 vs Infernal and ×1.1/1.2/1.5 vs Wither; Twilight ×1.5 / Mawdredge ×2.5 / Deathripper ×3.5 vs Infernal and ×1.25/1.5/2 vs Skeletal); Hollow Wand Raging Wind ×1.2 |
| Bows | Wither Bow ×2 vs Wither; Savanna Bow ×2; Sulphur Bow ×2 (with sulphur); Undead/Super Undead/Death Bow ×2 vs Undead; Spirit Shortbow vs Undead 1 + 2% per 1% missing HP; Ice Spray Wand ×1.1 vs frozen |
| Armor procs | Tarantula every 4th strike ×2; Primordial every 3rd ×1.5; Monster Hunter ×1.25 / Monster Raider ×1.35 vs Monsters |
| Accessories | Crown of Avarice 1 + 0.015 per digit of coins consumed (≈ ×1.15); Implosion Belt ×1.25 explosion damage; Dragonfade Cloak ×1.01 vs dragons; Tarantula Talisman/Ring ×1.1/×1.15 on every 10th melee hit |
| Enchant procs | Overload "Mega Crit" +10→50% above 100% CC; Thunderlord every 3rd hit adds 8→60% of the hit as lightning; Thunderbolt every 3rd hit 4→30% to up to 10 enemies; Duplex 2nd arrow 4→20% + target takes ×1.1→1.5 fire damage |
| Pets | Hedgehog vs pests 1+k%; Bat (Spooky Festival) 1+k%; Rock (sitting) 1+k%; Wither Skeleton Death's Touch k%/3 over 3 s; Golem Last Stand ×1.4 and Toss ×5 every 5 hits; Magma Cube Salt Blade vs slimes 1+k%; Bal Cannibal 1+k% |
| Pet items | Book of Progression Extra Infliction 1+k%; Red Scarf (Loving) ×1.05 on abilities |
| Dungeon classes | Berserk 1+k% melee (+Bloodlust; Ragnarok ×1.5); Mage staff 30% + 0.9 × Int/1000; Archer class %; Twilight Arrow Poison ×1.1 to all incoming |
| Special | Glacial mob type takes 1,650 × Breaking Power from pickaxes/drills |

Worked examples (community wiki):
- Additive: 100 × (1 + (100 Prosecute VI at full HP + 250 Golden Dragon)/100) = 100 × 4.5 = **450**.
- Multiplicative: 100 × (1.15 Crown × 3.5 Halberd) = **402.5**.
- Combined: 100 × 4.025 × 4.5 = **1,811.25** → 1,811.
- Defense: 1,000 into a Tank Zombie (2,000 Def) → 1000 × 100/2100 = **47.62** → 47.

### 4.2 Crit roll
- `CritChance` in percent; base 30. ≥100 → every hit crits. Crit applies to melee + arrows; most **abilities never crit**.
- Overload adds a "Mega Crit" chance when CC > 100.
- Crit indicator `✧12,345✧` with cycling colours (§11.3).

### 4.3 Ferocity
- `extraHits = floor(Fer/100) + (rand()*100 < Fer % 100 ? 1 : 0)` → max 5 at the 500 cap.
- Each extra strike repeats the **same Final Damage** (same crit state) after a short stagger (~0.1–0.25 s (?)), shows a red slash particle and plays a
  "ferocity" sound; extra strikes ignore the mob's invulnerability ticks.
- Fatal Tempo (ultimate) raises Ferocity +10→50% per hit for 3 s (cap +200%).
- Ferocity does not repeat ability damage (?).

### 4.4 Attack speed & swing range
- Bonus Attack Speed shortens the attack cooldown / target invulnerability; 100% ≈ twice the hits per second (?). Shortbows (Terminator, Juju, Artisanal)
  fire on left-click with a cooldown scaled by Attack Speed (≈ `0.5 s / (1 + AS/100)` (?)).
- Swing Range base 3 blocks, cap 15.

### 4.5 Ability (magic) damage

```
AbilityInitial = BaseAbilityDamage × (1 + Int/100 × AbilityScaling) × (1 + AbilityDamage/100)
Final          = AbilityInitial × Additive(ability-applicable) × Multiplicative(ability-applicable)
                 → boss cap → defense → floor
```
- `BaseAbilityDamage` and `AbilityScaling` are per item (API `stats.WEAPON_ABILITY_DAMAGE` and `ability_damage_scaling`). From the live API:
  **Aspect of the Dragons** 12,000 / 0.1; **Giant's Sword** 100,000 / 0.05; Ice Spray Wand 17,000; Crypt Dreadlord Sword 500; Crypt Witherlord Sword 300;
  **Hyperion** Wither Impact ≈ 10,000 / 0.3 (?) (comes from the scrolls, not listed on the base item).
- Strength, Crit and Sharpness-type enchants don't apply unless the item says so.
- Enchanting skill +0.5% Ability Damage/level; Bizarre/Sighted power unique bonuses (+5/+3); Mage class bonus.
- Mana cost line `§8Mana Cost: §3<n>`; Ultimate Wise −10%/level; Mana Disintegrator −1% each (max 10) (?).

### 4.6 True damage
- Ignores Defense; reduced by True Defense: `taken = raw × 100/(100+TrueDefense)`.
- June 2026 turned many %-max-HP true-damage attacks flat: Dragon Rush 550 TD, Dragon fireball/lightning 200 TD, Blaze slayer T1 75–180 / T4 600–1,500,
  Quazii Helicopter 2,000, Typhoeus Fire Trail 1,800, Campfire challenges 20% true / 80% normal, Stormy dungeon modifier → regular damage (F5 2,000 … M7 70,000).

### 4.7 Boss damage cap & magic resistance
- BOSS mobs have a threshold `T`; if `dmg > T`: `dmg = T + 0.1 × (dmg − T)`. Example Apex Dragon (T = 100,000, Def 2,500): 600,000,000 → 60,090,000 → × 100/2600 ≈ **2,311,153**.
- Magic Resistance: `× (1 − MR)` (15% MR → ×0.85), applied before the cap.
- Everything is floored to an integer at the end.

### 4.8 Pseudocode (melee)

```js
function meleeHit(attacker, target, ctx) {
  const s = attacker.stats;                               // aggregated (§3)
  let dmg = (5 + s.damage) * (1 + s.strength / 100);
  const crit = Math.random() * 100 < s.critChance;
  if (crit) dmg *= 1 + s.critDamage / 100;
  dmg *= 1 + sumAdditive(attacker, target, ctx) / 100;   // Sharpness, Warrior, pets, …
  dmg *= productMultiplicative(attacker, target, ctx);   // mob-type swords, Crown, …
  dmg *= 1 - (target.magicResist ?? 0);
  if (target.isBoss && dmg > target.cap) dmg = target.cap + 0.1 * (dmg - target.cap);
  dmg *= 100 / (100 + target.defense);
  dmg = Math.floor(dmg);
  const fer = Math.min(s.ferocity, 500);
  const extra = Math.floor(fer / 100) + (Math.random() * 100 < fer % 100 ? 1 : 0);
  return { dmg, crit, strikes: 1 + extra };
}
```

---

## 5. Incoming damage & defense

| Formula | |
|---|---|
| Normal damage taken | `raw × 100/(100 + Defense)` × Π(other % reductions, e.g. Whale pet "5% DR with active Absorption", class reductions) |
| True damage taken | `raw × 100/(100 + TrueDefense)` |
| Reduction % | `Def/(Def+100)`: 100 Def = 50%, 300 = 75%, 900 = 90%, 2,000 = 95.2% |
| Fall damage | vanilla-style; Feather Falling +1 block safe height and −5% per level |
| Fire / explosion / projectile | Fire Protection: +2 True Defense vs fire per level; Blast Protection: +30 Defense vs explosions per level; Projectile Protection: +7 Defense vs projectiles per level |
| Thorns | reflects 3/6/9/12% (50% proc chance); conflicts with Reflection |
| Death | lose a share of the purse outside protected areas (half (?)); Bank ultimate keeps 10→50%; dungeons: no coin loss, you become a ghost |

---

## 6. Sustain: Health Regen, Vitality, Mending (post-June-2026)

### 6.1 Health Regen
- Every regen tick (wiki: **1 s**; older sources say 2 s (?)): `heal = (MaxHP/100 + 1.5) × HealthRegen/100` → base ≈ 1% max HP + 1.5.
- **Doubled while out of combat** (June 2026). "In combat" now ends when every mob that aggro'd you is dead.
- Sources: Rejuvenate (+1→7 per piece), Respite (+3→21 out of combat), Zombie slayer L8, Jellyfish pet (+1→100), Regeneration potions (halved June 2026),
  Healing potions (+5 HP/s per tier for 5 s), Wisp potion (+10), Lesser Orb of Healing (+25 HR, 15 mana/s and 1 Vitality/s drain, 60 s cap), Power Orbs (+25→125 HR plus 25→50 HP/s),
  Campfire badges (+1/tier while burning), Holy Dragon armor (200 → 75), Vampire Dentist Relic (+10 → +5), Discrite/Spotlite (300 → 150).

### 6.2 Vitality (resource)
- Pool max = Vitality stat (base 100); regenerates **5% of max per second** (100 → 5/s, 200 → 10/s).
- Spent by healing abilities: Zombie Sword family 25 Vit (now flat heals — changelog lists 320/420/500 HP; another summary lists 64/84/100 HP, treat as per-item data (?));
  Wands of Healing 25 Vit (Wand of Healing 60 HP/s for 5 s); Power Orbs 50 Vit; Flares 10 Vit; Wither Shield 50 Vit (refunds by shield use); Wither Cloak 30 Vit per layer (blocks 1,000 dmg/layer, stops Vitality regen);
  Mosquito Bow "Nasty Bite" 200 HP for 10 Vit; Gloomlock Grimoire 1,000 HP for 60 Vit.
- Converters (armor enchants I–X): **Hardened Vitality** 4→40% of Vitality spent → Defense (max 25); **Strong Vitality** 2→20% → Strength (max 15);
  **Vivacious Vitality** 1→10% → Attack Speed (max 5); **Vampiric Vitality** 0.55→1 HP per Vitality spent. (Replaced Hardened/Strong/Ferocious Mana and Mana Vampire.)
- **Drain** (weapon, I–VI): +0.5→3 Vitality per hit (wiki table; the changelog text says 0.1–0.7 (?)).
- Classes: Tank +10 + 0.5 Vit per class level; Healer +10 + 0.2 Vit/level and +10 + 0.5 Mending/level.
- Accessories: Healing Talisman +5 → +2 Vit, Healing Ring +10 → +4, Survivor Cube +5 → +3; Turtle pet 20/25 → 10/15.

### 6.3 Mending
- Healing you give others × Mending/100. Mender helmets 50/65/80 Mending (were 100). Healer's Circle 1% max HP/s (was 2%).

### 6.4 Lifesteal family (weapon)

| Enchant | Effect |
|---|---|
| Life Steal | 2.4 / 4.8 / 7.2 / 9.6 / 12 / 14.4 / 16.8 HP per hit (I–VII; flat since June 2026, was 0.5%×lvl of max HP) |
| Vampirism | 5/10/15/20/25/30/35 HP per kill (wiki table; the changelog says 7–49) |
| Drain | Vitality per hit (above) |
| Mana Steal | 0.25/0.5/0.75% max Mana per hit |

Life Steal, Mana Steal and Drain are mutually exclusive. Bloodsoaked reforge now = "+1 level to Life Steal, Vampirism, Syphon/Drain" (was ×1.15 healing).

---

## 7. Mana

| Rule | Value |
|---|---|
| Max Mana | `100 + Intelligence` |
| Regen | 2% of max per second, increased by pets, potions, deployables (Mana Flux / Overflux / Plasmaflux orbs), attributes |
| Over-cap | if Int drops, current mana is clamped to the new max |
| Not enough | ability fails with `§cYou do not have enough mana!` (?) and a fail sound |
| Percent costs | some abilities cost % of max mana |
| Overflow | `ʬ` pool used only once the main pool is empty; no natural regen; Soulflow accessories convert Soulflow → Overflow (max 780) |
| Display | action bar `§b800/800✎ Mana`, overflow appended `§3123ʬ` |
| On ability use | the Defense segment of the action bar shows `§b-300 Mana (§6Wither Impact§b)` for ~2 s |

---

## 8. Speed

- Speed 100 = vanilla walk speed; factor Speed/100. Cap 400 (some items raise to 500; Cold lowers the cap). Sprint ×1.3 (vanilla).
- Sources: Rogue Sword ability (+100 for 30 s (?)), Sugar Rush (+2/4/6), Speed potions, Black Cat pet, Young Dragon armor.

---

## 9. Drop-luck stats

| Mechanic | Formula |
|---|---|
| Magic Find | For "rare" drops (base chance below a threshold, ≈ <5% (?)): `chance × (1 + MF/100)`; cap 900 |
| Pet Luck | Pet drops: `chance × (1 + (MF + PetLuck)/100)` |
| Looting | +15/30/45/60/75% mob drop chance (I–V) |
| Luck | +5→35% armour-piece drop chance (I–VII) |
| Scavenger | +0.3→1.8 coins per monster level on kill |
| Champion | +3→10% Combat XP and +1.4→5 coins per kill (I–X, levels by kills) |
| Divine Gift | +2/+4/+6 Magic Find |
| Chat | `§b§lRARE DROP! §9Wither Blood §b(+167% ✯ Magic Find)` — drop messages show the MF that applied |

---

## 10. Gathering-fortune formula (shared)

`drops = baseDrops × (1 + Fortune/100)`, implemented as `floor(1 + F/100)` multiples plus a `(F % 100)%` chance of one more multiple.
Same for Mining (+ relevant sub-fortune), Farming (+ crop fortune), Foraging (+ log fortune) and Hunting Fortune (shards).

---

## 11. What the player sees (HUD)

### 11.1 Action bar (above the hotbar; refreshed every ~0.5–1 s and on change)

Default three segments separated by 5 spaces:

```
§c1,234/1,500❤     §a612§a❈ Defense     §b850/950✎ Mana
```

| Situation | Change |
|---|---|
| Absorption | health turns gold `§6` and current can exceed max: `§61,800/1,500❤` |
| Defense segment temporarily replaced (~2 s) | skill XP `§3+12.5 Combat (25,113/50,000)` (or `(54.3%)`); ability use `§b-300 Mana (§6Wither Impact§b)`; collection gains |
| Overflow mana | appended after mana: `§3150ʬ` |
| Vitality (post-2026) | design choice for the clone: show `§4100/100♨` only while not full (?) |
| Area resources | Rift `§a7m 32sф Left`; Magma Fields Heat; Glacite Cold; dungeon secrets `§7(3/7 Secrets)`; items with API `actionbar_resource` (8 items) |

### 11.2 Sidebar (scoreboard) — not a stat display
`§e§lSKYBLOCK` title, date/time/weather, `⏣ Location`, `Purse: §6coins`, `Bits`, objective, slayer quest progress. Stats are **not** on the sidebar.
Full stats live in **SkyBlock Menu → "Your SkyBlock Profile"** (player head): every stat in §2 order with its value; hovering explains it
("Defense: Reduces damage taken…"); sub-menus per category (Combat / Gathering / Wisdom / Misc).

### 11.3 Damage indicators (floating armor-stand text, rises and fades in ~1 s)

| Hit | Text |
|---|---|
| normal melee/arrow | `§71,234` (grey) |
| crit | `§f✧§f1§e2§63§c4§f✧` — `✧` on both ends, digits in a cycling white→yellow→gold→red pattern |
| ferocity strike | same number as the source hit, with a red slash particle (?) |
| ability/magic | grey number (no crit) (?) |
| fire / poison / lightning | coloured numbers (§6 / §2 / §e) (?) |

Mob nametag: `§8[§7Lv150§8] §cZealot §a12,000§f/§a13,000§c❤` — current HP shifts green→yellow→red with %; bosses `§c☠ §4Voidgloom Seraph §a50M§c❤` with k/M/B abbreviations.

### 11.4 Chat lines tied to stats (examples)
`§cYou do not have enough mana!` · `§cThis ability is on cooldown for 3s.` · `§b§lRARE DROP! §9Wither Blood §b(+167% ✯ Magic Find)` · `§cYou died and lost 1,234 coins!`.

---

## 12. Item modifiers — overview & layering

All modifications are stored per item instance (Hypixel: NBT `ExtraAttributes`; clone: a JSON blob, §23). They layer onto base stats:

```
statOnItem(stat) =
    base[stat] × (1 + starPct)               // §16: +2%/star outside dungeons;
                                             //      dungeon items inside Catacombs: +10%/star (+5% per master star in Master Mode)
                                             //      plus the dungeon quality roll and Catacombs-level boost (dungeons file)
  + potatoBooks[stat]                        // §14b: 10 Hot then 5 Fuming
  + reforge[name][rarity][stat]              // §13: rarity AFTER recomb
  + Σ gems[gem][quality][rarity][stat]       // §17
  + enchantFlat[stat]                        // Growth HP, Protection Def, Big Brain Int, Sugar Rush Speed …
  + artOfWar / artOfPeace / farmingForDummies / polarvoid …    // §19
  + transient procs (Counter-Strike, Soul Eater, Vitality converters …)
```

The lore shows the **sum** with coloured breakdowns (§21).

| Modifier | Applies to | Station | Max | Reversible? |
|---|---|---|---|---|
| Reforge (random) | weapons, armor, equipment, tools, rods, nets, vacuums | Blacksmith (Hub) "Reforge Item" | 1 | replaced by the next reforge |
| Reforge stone | per stone's item type | Reforge Anvil / The Hex | 1 | replaced |
| Enchantments | per item type (§14) | Enchantment Table, Anvil (books), The Hex | per enchant; 1 ultimate | overwritten, not removed |
| Hot Potato Book | weapons, armor | Anvil | 10 | no |
| Fuming Potato Book | same, after 10 HPB | Anvil | 5 | no |
| Recombobulator 3000 | nearly anything with a rarity | Anvil | 1 | no |
| Stars (essence) | dungeon items & `upgrade_costs` items | Essence upgrade menu | 5 / 10 (Kuudra) | no |
| Master stars | 5★ dungeon items | Anvil | 5 | no |
| Gemstones | items with `gemstone_slots` | Gemstone Grinder NPC / The Hex | per slot | yes, for a fee |
| Enrichment | Legendary+ accessories | consumed on use | 1 | swap with the Swapper |
| Art of War / Peace | weapon / armor | Anvil | 1 | no |
| Power + Tuning | the accessory bag | Maxwell | 1 power | change any time |
| Dye / Skin / Rune | cosmetic | Anvil / Rune Pedestal | — | yes |

---

## 13. Reforges

### 13.1 Rules
- **Blacksmith** (Hub): rolls a **random** reforge from the pool for the item's type. The first reforge ever costs **10 Coal** (tutorial); after that coins by the item's current rarity:

| Rarity | Random reforge cost |
|---|---|
| Common | 250 |
| Uncommon | 500 |
| Rare | 1,000 |
| Epic | 2,500 |
| Legendary | 5,000 |
| Mythic | 10,000 |
| Divine | 15,000 |
| Special | 25,000 |
| Very Special | 50,000 |

- The random roll never gives the reforge the item already has (?).
- **Reforge stones**: item + stone in the Reforge Anvil (or The Hex); coins by item rarity (per-stone table below); many stones historically also need a **Mining level**
  to apply (e.g. Pocket Iceberg: Mining 25 in the API). The stone is consumed.
- Reforge **stats scale with item rarity** (after Recombobulator). Some reforges also have a **reforge bonus** (ability text) shown in blue as `§9<Name> Bonus`.
- **Accessory reforges were removed (April 2022)** and replaced by Powers (§20). Old accessory reforges give no stats.
- Item type groups: SWORD (incl. longswords, daggers; "SWORD/ROD" = also fishing weapons), BOW, ARMOR, EQUIPMENT (necklace/cloak/belt/gloves/bracelet),
  PICKAXE (incl. drills & gauntlet), AXE, FARMING_TOOL (hoes, dicers, choppers), ROD, FISHING_NET, VACUUM; a few reforges are item-specific (internal-name lists).

### 13.2 Blacksmith (random) reforges — full stat table (NEU `reforges.json`, 50 entries)

Abbreviations: HP Health, Def Defense, Str Strength, Int Intelligence, CC Crit Chance, CD Crit Damage, AtkSpd Bonus Attack Speed, Spd Speed, Fer Ferocity,
MF Magic Find, SCC Sea Creature Chance, TD True Defense, MinSpd/MinFort Mining Speed/Fortune, FarmFort/ForgFort Farming/Foraging Fortune, FishSpd Fishing Speed,
xxWis Wisdom, Dmg Damage, Vit Vitality, Mend Mending, BrkPow Breaking Power. Rows by item rarity C/U/R/E/L/M (+D where present). SPECIAL/VERY SPECIAL use the M row.

| Reforge | Applies to | Stats by item rarity (C/U/R/E/L/M) | Bonus |
|---|---|---|---|
| **Clean** | Armor | C: HP+5 Def+5 CC+2<br>U: HP+7 Def+7 CC+4<br>R: HP+10 Def+10 CC+6<br>E: HP+15 Def+15 CC+8<br>L: HP+20 Def+20 CC+10<br>M: HP+25 Def+25 CC+12 |  |
| **Fierce** | Armor | C: Str+2 CD+4 CC+2<br>U: Str+4 CD+7 CC+3<br>R: Str+6 CD+10 CC+4<br>E: Str+8 CD+14 CC+5<br>L: Str+10 CD+18 CC+6<br>M: Str+12 CD+24 CC+8 |  |
| **Heavy** | Armor | C: Def+25 CD-1 Spd-1<br>U: Def+35 CD-2 Spd-1<br>R: Def+50 CD-2 Spd-1<br>E: Def+65 CD-3 Spd-1<br>L: Def+80 CD-5 Spd-1<br>M: Def+110 CD-7 Spd-1 |  |
| **Light** | Armor | C: HP+5 Def+1 CD+1 CC+1 AtkSpd+1 Spd+1<br>U: HP+7 Def+2 CD+2 CC+1 AtkSpd+2 Spd+2<br>R: HP+10 Def+3 CD+3 CC+2 AtkSpd+3 Spd+3<br>E: HP+15 Def+4 CD+4 CC+2 AtkSpd+4 Spd+4<br>L: HP+20 Def+5 CD+5 CC+3 AtkSpd+5 Spd+5<br>M: HP+25 Def+6 CD+6 CC+3 AtkSpd+6 Spd+6 |  |
| **Mythic** | Armor | C: HP+2 Def+2 Str+2 Int+20 CC+1 Spd+2<br>U: HP+4 Def+3 Str+4 Int+25 CC+2 Spd+2<br>R: HP+6 Def+6 Str+6 Int+30 CC+3 Spd+2<br>E: HP+8 Def+8 Str+8 Int+40 CC+4 Spd+2<br>L: HP+10 Def+10 Str+10 Int+50 CC+5 Spd+2<br>M: HP+12 Def+12 Str+12 Int+60 CC+6 Spd+2 |  |
| **Pure** | Armor | C: HP+2 Def+2 Str+2 Int+2 CD+2 CC+2 AtkSpd+1 Spd+1<br>U: HP+3 Def+3 Str+3 Int+3 CD+3 CC+4 AtkSpd+1 Spd+1<br>R: HP+4 Def+4 Str+4 Int+4 CD+4 CC+6 AtkSpd+2 Spd+1<br>E: HP+6 Def+6 Str+6 Int+6 CD+6 CC+8 AtkSpd+3 Spd+1<br>L: HP+8 Def+8 Str+8 Int+8 CD+8 CC+10 AtkSpd+4 Spd+1<br>M: HP+10 Def+10 Str+10 Int+10 CD+10 CC+12 AtkSpd+5 Spd+1 |  |
| **Smart** | Armor | C: HP+4 Def+4 Int+20<br>U: HP+6 Def+6 Int+40<br>R: HP+9 Def+9 Int+60<br>E: HP+12 Def+12 Int+80<br>L: HP+15 Def+15 Int+100<br>M: HP+20 Def+20 Int+120 |  |
| **Titanic** | Armor | C: HP+10 Def+10<br>U: HP+15 Def+15<br>R: HP+20 Def+20<br>E: HP+25 Def+25<br>L: HP+35 Def+35<br>M: HP+50 Def+50 |  |
| **Wise** | Armor | C: HP+6 Int+25 Spd+1<br>U: HP+8 Int+50 Spd+1<br>R: HP+10 Int+75 Spd+1<br>E: HP+12 Int+100 Spd+2<br>L: HP+15 Int+125 Spd+2<br>M: HP+20 Int+150 Spd+3 |  |
| **Double-Bit** | Axe | C: Spd+1 ForgFort+1<br>U: Spd+2 ForgFort+2<br>R: Spd+2 ForgFort+3<br>E: Spd+3 ForgFort+4<br>L: Spd+5 ForgFort+5<br>M: Spd+7 ForgFort+6 |  |
| **Great** | Axe | C: Str+2 CD+2 Spd+1<br>U: Str+4 CD+4 Spd+2<br>R: Str+6 CD+6 Spd+3<br>E: Str+9 CD+9 Spd+4<br>L: Str+12 CD+12 Spd+5<br>M: Str+16 CD+16 Spd+7 |  |
| **Lumberjack's** | Axe | C: Spd+1 ForgWis+0.5<br>U: Spd+2 ForgWis+0.75<br>R: Spd+3 ForgWis+1<br>E: Spd+5 ForgWis+1.25<br>L: Spd+7 ForgWis+2<br>M: Spd+9 ForgWis+2.5 |  |
| **Lush** | Axe | C: Spd+3 ForgFort+1<br>U: Spd+4 ForgFort+1<br>R: Spd+5 ForgFort+2<br>E: Spd+7 ForgFort+2<br>L: Spd+10 ForgFort+3<br>M: Spd+15 ForgFort+5 |  |
| **Rugged** | Axe | C: Str+4 CD+3<br>U: Str+6 CD+5<br>R: Str+9 CD+8<br>E: Str+13 CD+12<br>L: Str+18 CD+16<br>M: Str+24 CD+22 |  |
| **Awkward** | Bow | C: Int-5 CD+5 CC+10<br>U: Int-10 CD+10 CC+12<br>R: Int-18 CD+15 CC+15<br>E: Int-32 CD+22 CC+20<br>L: Int-50 CD+30 CC+25<br>M: Int-72 CD+35 CC+30 |  |
| **Deadly** | Bow | C: CD+5 CC+10<br>U: CD+10 CC+13<br>R: CD+18 CC+16<br>E: CD+32 CC+19<br>L: CD+50 CC+22<br>M: CD+78 CC+25 |  |
| **Fine** | Bow | C: Str+3 CD+2 CC+5<br>U: Str+7 CD+4 CC+7<br>R: Str+12 CD+7 CC+9<br>E: Str+18 CD+10 CC+12<br>L: Str+25 CD+15 CC+15<br>M: Str+33 CD+20 CC+18 |  |
| **Grand** | Bow | C: Str+25<br>U: Str+32<br>R: Str+40<br>E: Str+50<br>L: Str+60<br>M: Str+75 |  |
| **Hasty** | Bow | C: Str+3 CC+20<br>U: Str+5 CC+25<br>R: Str+7 CC+30<br>E: Str+10 CC+40<br>L: Str+15 CC+50<br>M: Str+20 CC+60 |  |
| **Neat** | Bow | C: Int+3 CD+4 CC+10<br>U: Int+6 CD+8 CC+12<br>R: Int+10 CD+14 CC+14<br>E: Int+15 CD+20 CC+17<br>L: Int+20 CD+30 CC+20<br>M: Int+30 CD+40 CC+25 |  |
| **Rapid** | Bow | C: Str+2 CD+35<br>U: Str+3 CD+45<br>R: Str+4 CD+55<br>E: Str+7 CD+65<br>L: Str+10 CD+75<br>M: Str+12 CD+90 |  |
| **Rich** | Bow | C: Int+3 CD+2 CC+10<br>U: Int+5 CD+4 CC+12<br>R: Int+6 CD+7 CC+14<br>E: Int+15 CD+10 CC+17<br>L: Int+20 CD+15 CC+20<br>M: Int+30 CD+20 CC+25 |  |
| **Unreal** | Bow | C: Str+3 CD+5 CC+8<br>U: Str+7 CD+10 CC+9<br>R: Str+12 CD+18 CC+10<br>E: Str+18 CD+32 CC+11<br>L: Str+25 CD+50 CC+13<br>M: Str+34 CD+70 CC+15 |  |
| **Astute** | Equipment | C: HP+1 Def+1 Int+3<br>U: HP+1 Def+2 Int+4<br>R: HP+2 Def+2 Int+5<br>E: HP+3 Def+3 Int+6<br>L: HP+4 Def+4 Int+8<br>M: HP+5 Def+5 Int+10 |  |
| **Blended** | Equipment | C: HP+1 Def+1 Int+1 CD+1 CC+1 AtkSpd+1 Spd+1<br>U: HP+1 Def+1 Str+1 Int+1 CD+2 CC+1 AtkSpd+1 Spd+1<br>R: HP+2 Def+1 Str+1 Int+1 CD+2 CC+2 AtkSpd+1 Spd+1<br>E: HP+2 Def+2 Str+2 Int+2 CD+2 CC+2 AtkSpd+1 Spd+1<br>L: HP+2 Def+3 Str+2 Int+2 CD+2 CC+3 AtkSpd+1 Spd+1<br>M: HP+3 Def+3 Str+2 Int+3 CD+2 CC+3 AtkSpd+1 Spd+1 |  |
| **Brilliant** | Equipment | C: HP+1 Int+5 Spd+1<br>U: HP+1 Int+6 Spd+1<br>R: HP+2 Int+7 Spd+2<br>E: HP+3 Int+9 Spd+2<br>L: HP+4 Int+12 Spd+2<br>M: HP+5 Int+15 Spd+2 |  |
| **Colossal** | Equipment | C: HP+3 Def+3<br>U: HP+4 Def+4<br>R: HP+6 Def+6<br>E: HP+8 Def+8<br>L: HP+10 Def+10<br>M: HP+12 Def+12 |  |
| **Hefty** | Equipment | C: Def+7 CD-2 Spd-1<br>U: Def+9 CD-2 Spd-1<br>R: Def+12 CD-3 Spd-1<br>E: Def+15 CD-3 Spd-1<br>L: Def+20 CD-4 Spd-1<br>M: Def+25 CD-5 Spd-1 |  |
| **Honored** | Equipment | C: HP+1 Def+1 Int+3 CC+1 Spd+1<br>U: HP+1 Def+2 Str+1 Int+3 CC+1 Spd+1<br>R: HP+2 Def+2 Str+2 Int+4 CC+1 Spd+1<br>E: HP+2 Def+3 Str+2 Int+5 CC+2 Spd+1<br>L: HP+3 Def+3 Str+3 Int+6 CC+2 Spd+1<br>M: HP+4 Def+4 Str+3 Int+7 CC+2 Spd+1 |  |
| **Menacing** | Equipment | C: CD+2 CC+1<br>U: CD+3 CC+1<br>R: CD+3 CC+1<br>E: CD+4 CC+1<br>L: CD+4 CC+2<br>M: CD+5 CC+2 |  |
| **Soft** | Equipment | C: HP+2 Def+1 CD+1 CC+1 AtkSpd+1 Spd+1<br>U: HP+3 Def+1 CD+1 CC+1 AtkSpd+1 Spd+1<br>R: HP+4 Def+1 CD+1 CC+1 AtkSpd+1 Spd+1<br>E: HP+5 Def+1 CD+2 CC+1 AtkSpd+1 Spd+1<br>L: HP+6 Def+2 CD+2 CC+1 AtkSpd+1 Spd+2<br>M: HP+7 Def+2 CD+2 CC+1 AtkSpd+2 Spd+2 |  |
| **Stained** | Equipment | C: HP+2 Def+2 CC+1<br>U: HP+3 Def+3 CC+1<br>R: HP+4 Def+4 CC+2<br>E: HP+5 Def+5 CC+2<br>L: HP+5 Def+6 CC+3<br>M: HP+7 Def+7 CC+4 |  |
| **Green Thumb** | Farming tool | C: Spd+1 FarmFort+1<br>U: Spd+2 FarmFort+2<br>R: Spd+2 FarmFort+3<br>E: Spd+3 FarmFort+4<br>L: Spd+5 FarmFort+5<br>M: Spd+7 FarmFort+6 |  |
| **Peasant's** | Farming tool | C: Spd+1 FarmWis+0.5<br>U: Spd+2 FarmWis+0.75<br>R: Spd+3 FarmWis+1<br>E: Spd+5 FarmWis+1.25<br>L: Spd+7 FarmWis+2<br>M: Spd+9 FarmWis+2.5 |  |
| **Robust** | Farming tool | C: FarmFort+2<br>U: FarmFort+3<br>R: FarmFort+4<br>E: FarmFort+6<br>L: FarmFort+8<br>M: FarmFort+10 |  |
| **Zooming** | Farming tool | C: Spd+5<br>U: Spd+8<br>R: Spd+12<br>E: Spd+16<br>L: Spd+20<br>M: Spd+25 |  |
| **Excellent** | Pickaxe/Drill | C: Spd+1 MinSpd+4<br>U: Spd+2 MinSpd+8<br>R: Spd+3 MinSpd+12<br>E: Spd+4 MinSpd+16<br>L: Spd+5 MinSpd+20<br>M: Spd+7 MinSpd+25 |  |
| **Fortunate** | Pickaxe/Drill | C: MinSpd+1 MinFort+1<br>U: MinSpd+2 MinFort+1<br>R: MinSpd+3 MinFort+1<br>E: MinSpd+4 MinFort+2<br>L: MinSpd+6 MinFort+2<br>M: MinSpd+8 MinFort+3 |  |
| **Prospector's** | Pickaxe/Drill | C: Spd+1 MinWis+0.5<br>U: Spd+2 MinWis+0.75<br>R: Spd+3 MinWis+1<br>E: Spd+5 MinWis+1.25<br>L: Spd+7 MinWis+2<br>M: Spd+9 MinWis+2.5 |  |
| **Sturdy** | Pickaxe/Drill | C: Def+3 MinSpd+3<br>U: Def+6 MinSpd+6<br>R: Def+9 MinSpd+9<br>E: Def+12 MinSpd+12<br>L: Def+15 MinSpd+15<br>M: Def+20 MinSpd+20 |  |
| **Unyielding** | Pickaxe/Drill | C: Spd+1 MinFort+1<br>U: Spd+2 MinFort+2<br>R: Spd+3 MinFort+3<br>E: Spd+5 MinFort+4<br>L: Spd+7 MinFort+5<br>M: Spd+9 MinFort+6 |  |
| **Epic** | Sword + Fishing weapon | C: Str+15 CD+10 AtkSpd+1<br>U: Str+20 CD+15 AtkSpd+2<br>R: Str+25 CD+20 AtkSpd+4<br>E: Str+32 CD+27 AtkSpd+7<br>L: Str+40 CD+35 AtkSpd+10<br>M: Str+50 CD+45 AtkSpd+15 |  |
| **Fair** | Sword + Fishing weapon | C: Str+2 Int+2 CD+2 CC+2 AtkSpd+2<br>U: Str+3 Int+3 CD+3 CC+3 AtkSpd+3<br>R: Str+4 Int+4 CD+4 CC+4 AtkSpd+4<br>E: Str+7 Int+7 CD+7 CC+7 AtkSpd+7<br>L: Str+10 Int+10 CD+10 CC+10 AtkSpd+10<br>M: Str+12 Int+12 CD+12 CC+12 AtkSpd+12 |  |
| **Fast** | Sword + Fishing weapon | C: AtkSpd+10<br>U: AtkSpd+20<br>R: AtkSpd+30<br>E: AtkSpd+40<br>L: AtkSpd+50<br>M: AtkSpd+60 |  |
| **Gentle** | Sword + Fishing weapon | C: Str+3 AtkSpd+8<br>U: Str+5 AtkSpd+10<br>R: Str+7 AtkSpd+15<br>E: Str+10 AtkSpd+20<br>L: Str+15 AtkSpd+25<br>M: Str+20 AtkSpd+30 |  |
| **Heroic** | Sword + Fishing weapon | C: Str+15 Int+40 AtkSpd+1<br>U: Str+20 Int+50 AtkSpd+2<br>R: Str+25 Int+65 AtkSpd+2<br>E: Str+32 Int+80 AtkSpd+3<br>L: Str+40 Int+100 AtkSpd+5<br>M: Str+50 Int+125 AtkSpd+7 |  |
| **Legendary** | Sword + Fishing weapon | C: Str+3 Int+5 CD+5 CC+5 AtkSpd+2<br>U: Str+7 Int+8 CD+10 CC+7 AtkSpd+3<br>R: Str+12 Int+12 CD+15 CC+9 AtkSpd+5<br>E: Str+18 Int+18 CD+22 CC+12 AtkSpd+7<br>L: Str+25 Int+25 CD+28 CC+15 AtkSpd+10<br>M: Str+32 Int+35 CD+36 CC+18 AtkSpd+15 |  |
| **Odd** | Sword + Fishing weapon | C: Int-5 CD+5 CC+10<br>U: Int-10 CD+10 CC+12<br>R: Int-18 CD+15 CC+15<br>E: Int-32 CD+22 CC+20<br>L: Int-50 CD+30 CC+25<br>M: Int-75 CD+40 CC+30 |  |
| **Sharp** | Sword + Fishing weapon | C: CD+20 CC+10<br>U: CD+30 CC+12<br>R: CD+40 CC+14<br>E: CD+55 CC+17<br>L: CD+75 CC+20<br>M: CD+90 CC+25 |  |
| **Spicy** | Sword + Fishing weapon | C: Str+2 CD+25 CC+1 AtkSpd+2<br>U: Str+3 CD+35 CC+1 AtkSpd+3<br>R: Str+4 CD+45 CC+1 AtkSpd+5<br>E: Str+7 CD+60 CC+1 AtkSpd+7<br>L: Str+10 CD+80 CC+1 AtkSpd+10<br>M: Str+12 CD+100 CC+1 AtkSpd+15 |  |

### 13.3 Reforge stones — full table (NEU `reforgestones.json`, 85 entries; stone rarity from the live API)

| Reforge | Stone (item id) | Stone rarity | Applies to | Apply cost C/U/R/E/L/M(/D) coins | Stats by item rarity | Reforge bonus |
|---|---|---|---|---|---|---|
| **Ancient** | Precursor Gear (`PRECURSOR_GEAR`) | EPIC | Armor | 10,000/20,000/30,000/40,000/50,000/60,000 | C: Str+4 CD+3 HP+7 Def+7 Int+6<br>U: Str+8 CC+5 HP+7 Def+7 Int+9<br>R: Str+12 CC+7 HP+7 Def+7 Int+12<br>E: Str+18 CC+9 HP+7 Def+7 Int+16<br>L: Str+25 CC+12 HP+7 Def+7 Int+20<br>M: Str+35 CC+15 HP+7 Def+7 Int+25 | Grants +1 ☠ Crit Damage per Catacombs level. |
| **Bustling** | SkyMart Brochure (`SKYMART_BROCHURE`) | RARE | Armor | 1,000/2,000/3,000/6,000/10,000/15,000 | C: FarmFort+1<br>U: FarmFort+2<br>R: FarmFort+4<br>E: FarmFort+6<br>L: FarmFort+8<br>M: FarmFort+10 |  |
| **Calcified** | Calcified Heart (`CALCIFIED_HEART`) | RARE | Armor | 20,000/40,000/80,000/150,000/300,000/600,000/800,000 | C: FishSpd+0.5 Def+3<br>U: FishSpd+1 Def+5<br>R: FishSpd+1.5 Def+7<br>E: FishSpd+2.5 Def+10<br>L: FishSpd+3.5 Def+13<br>M: FishSpd+4.5 Def+16<br>D: FishSpd+4.5 Def+16 | Grants +25❈ Defense against Sea Creatures. |
| **Candied** | Candy Corn (`CANDY_CORN`) | RARE | Armor | 20,000/40,000/80,000/150,000/300,000/600,000 | C: HP+1 Def+1<br>U: HP+2 Def+1<br>R: HP+4 Def+2<br>E: HP+6 Def+3<br>L: HP+8 Def+4<br>M: HP+10 Def+5 | Increases the chance to find candy during the Spooky Festival by +1%. |
| **Cubic** | Molten Cube (`MOLTEN_CUBE`) | RARE | Armor | 4,000/7,500/15,000/40,000/75,000/150,000 | C: Str+3 HP+5<br>U: Str+5 HP+7<br>R: Str+7 HP+10<br>E: Str+10 HP+15<br>L: Str+12 HP+20<br>M: Str+15 HP+25 | Decrease damage taken from Nether mobs by 2% |
| **Dimensional** | Titanium Tesseract (`TITANIUM_TESSERACT`) | RARE | Armor | 15,000/30,000/60,000/125,000/250,000/500,000/500,000 | C: MinSpd+10 MinWis+1<br>U: MinSpd+15 MinWis+1<br>R: MinSpd+20 MinWis+1<br>E: MinSpd+30 MinWis+2<br>L: MinSpd+40 MinWis+2<br>M: MinSpd+50 MinWis+3<br>D: MinSpd+50 MinWis+3 | Titanium Ore drops +10 Mithril Powder when mined. |
| **Empowered** | Sadan's Brooch (`SADAN_BROOCH`) | EPIC | Armor | 60,000/125,000/250,000/500,000/1,000,000/2,000,000 | C: HP+10 Def+10<br>U: HP+15 Def+15<br>R: HP+20 Def+20<br>E: HP+25 Def+25<br>L: HP+35 Def+35<br>M: HP+50 Def+50 | Grants +10☄ Mending while in Dungeons, which increases your healing on others.. |
| **Festive** | Frozen Bauble (`FROZEN_BAUBLE`) | EPIC | Armor | 25,000/75,000/150,000/250,000/400,000/600,000 | C: Int+5 SCC+0.05 FishSpd+2<br>U: Int+10 SCC+0.05 FishSpd+3<br>R: Int+15 SCC+0.1 FishSpd+4<br>E: Int+20 SCC+0.15 FishSpd+6<br>L: Int+25 SCC+0.2 FishSpd+8<br>M: Int+30 SCC+0.25 FishSpd+10 |  |
| **Giant** | Giant Tooth (`GIANT_TOOTH`) | EPIC | Armor | 60,000/125,000/250,000/500,000/1,000,000/2,000,000 | C: HP+50<br>U: HP+60<br>R: HP+80<br>E: HP+120<br>L: HP+180<br>M: HP+240 |  |
| **Groovy** | Mangrove Gem (`MANGROVE_GEM`) | RARE | Armor | 10,000/15,000/20,000/30,000/40,000/50,000/50,000 | C: ForgFort+4 ForgWis+1<br>U: ForgFort+6 ForgWis+2<br>R: ForgFort+8 ForgWis+3<br>E: ForgFort+10 ForgWis+4<br>L: ForgFort+12 ForgWis+5<br>M: ForgFort+15 ForgWis+6<br>D: ForgFort+15 ForgWis+6 |  |
| **Hyper** | End Stone Geode (`ENDSTONE_GEODE`) | RARE | Armor | 5,000/10,000/20,000/50,000/100,000/200,000 | C: Str+2 AtkSpd+2 Spd+1<br>U: Str+4 AtkSpd+3 Spd+1<br>R: Str+6 AtkSpd+4 Spd+2<br>E: Str+7 AtkSpd+5 Spd+2<br>L: Str+10 AtkSpd+6 Spd+3<br>M: Str+12 AtkSpd+7 Spd+5 | Gain +[1/2/3/4/5/6] ✦ Speed for 5s (values by rarity C/U/R/E/L/M) |
| **Jaded** | Jaderald (`JADERALD`) | RARE | Armor | 20,000/40,000/80,000/150,000/300,000/600,000 | C: MinSpd+5 MinFort+5<br>U: MinSpd+12 MinFort+10<br>R: MinSpd+20 MinFort+15<br>E: MinSpd+30 MinFort+20<br>L: MinSpd+45 MinFort+25<br>M: MinSpd+60 MinFort+30 |  |
| **Mantid** | Mantid Claw (`MANTID_CLAW`) | EPIC | Armor | 7,500/15,000/30,000/75,000/150,000/150,000/150,000 | C: PestCh+0.5 FarmFort+2<br>U: PestCh+0.5 FarmFort+4<br>R: PestCh+1 FarmFort+6<br>E: PestCh+1.5 FarmFort+8<br>L: PestCh+2 FarmFort+10<br>M: PestCh+2.5 FarmFort+12<br>D: PestCh+2.5 FarmFort+14 | Grants +0.25ൠ Bonus Pest Chance per ൠ Pest killed for 10 minutes. Caps at 5ൠ Bonus Pest Chance. |
| **Mossy** | Overgrown Grass (`OVERGROWN_GRASS`) | RARE | Armor | 20,000/40,000/80,000/150,000/300,000/600,000 | C: Spd+3 FarmFort+5<br>U: Spd+3 FarmFort+10<br>R: Spd+5 FarmFort+15<br>E: Spd+5 FarmFort+20<br>L: Spd+7 FarmFort+25<br>M: Spd+7 FarmFort+30 |  |
| **Necrotic** | Necromancer's Brooch (`NECROMANCER_BROOCH`) | RARE | Armor | 20,000/40,000/80,000/150,000/300,000/1,000,000 | C: Int+30<br>U: Int+60<br>R: Int+90<br>E: Int+120<br>L: Int+150<br>M: Int+200 |  |
| **Perfect** | Diamond Atom (`DIAMOND_ATOM`) | RARE | Armor | 25,000/50,000/150,000/300,000/600,000/800,000 | C: Def+25<br>U: Def+35<br>R: Def+50<br>E: Def+65<br>L: Def+80<br>M: Def+110 | Increases ❈ Defense by +2% |
| **Reinforced** | Rare Diamond (`RARE_DIAMOND`) | RARE | Armor | 2,500/5,000/10,000/25,000/50,000/100,000 | C: Def+25<br>U: Def+35<br>R: Def+50<br>E: Def+65<br>L: Def+80<br>M: Def+110 |  |
| **Renowned** | Dragon Horn (`DRAGON_HORN`) | EPIC | Armor | 60,000/125,000/250,000/500,000/1,000,000/2,000,000 | C: Str+3 CC+2 CD+3 AtkSpd+1 HP+2 Def+2 Spd+1 Int+3<br>U: Str+4 CC+4 CD+4 AtkSpd+1 HP+3 Def+3 Spd+1 Int+4<br>R: Str+6 CC+6 CD+6 AtkSpd+2 HP+4 Def+4 Spd+1 Int+6<br>E: Str+8 CC+8 CD+8 AtkSpd+3 HP+6 Def+6 Spd+1 Int+8<br>L: Str+10 CC+10 CD+10 AtkSpd+4 HP+8 Def+8 Spd+1 Int+10<br>M: Str+12 CC+12 CD+12 AtkSpd+5 HP+10 Def+10 Spd+1 Int+12 | Increases all Combat stats and ✯ Magic Find by +1%. |
| **Spiked** | Dragon Scale (`DRAGON_SCALE`) | RARE | Armor | 30,000/75,000/150,000/300,000/600,000/2,000,000 | C: Str+3 CC+2 CD+3 AtkSpd+1 HP+2 Def+2 Spd+1 Int+3<br>U: Str+4 CC+4 CD+4 AtkSpd+1 HP+3 Def+3 Spd+1 Int+4<br>R: Str+4 CC+6 CD+6 AtkSpd+2 HP+4 Def+4 Spd+1 Int+6<br>E: Str+8 CC+8 CD+8 AtkSpd+3 HP+6 Def+6 Spd+1 Int+8<br>L: Str+10 CC+10 CD+10 AtkSpd+4 HP+8 Def+8 Spd+1 Int+10<br>M: Str+12 CC+12 CD+12 AtkSpd+5 HP+10 Def+10 Spd+1 Int+12 |  |
| **Submerged** | Deep Sea Orb (`DEEP_SEA_ORB`) | EPIC | Armor | 50,000/150,000/350,000/600,000/750,000/800,000 | C: CC+2 SCC+0.5 FishSpd+1<br>U: CC+4 SCC+0.6 FishSpd+1<br>R: CC+6 SCC+0.7 FishSpd+2<br>E: CC+8 SCC+0.8 FishSpd+3<br>L: CC+10 SCC+0.9 FishSpd+4<br>M: CC+12 SCC+1 FishSpd+5 |  |
| **Sunny** | Sunstone (`SUNSTONE`) | RARE | Armor | 5,000/10,000/20,000/50,000/100,000/200,000/200,000 | C: Spd+2 FarmWis+1<br>U: Spd+3 FarmWis+2<br>R: Spd+4 FarmWis+3<br>E: Spd+6 FarmWis+4<br>L: Spd+8 FarmWis+5<br>M: Spd+10 FarmWis+6<br>D: Spd+10 FarmWis+6 |  |
| **Undead** | Premium Flesh (`PREMIUM_FLESH`) | RARE | Armor | 5,000/15,000/30,000/75,000/150,000/300,000 | C: Str+1 AtkSpd+1 HP+8 Def+6<br>U: Str+2 AtkSpd+2 HP+8 Def+8<br>R: Str+2 AtkSpd+3 HP+12 Def+12<br>E: Str+3 AtkSpd+4 HP+18 Def+18<br>L: Str+5 AtkSpd+5 HP+25 Def+25<br>M: Str+7 AtkSpd+6 HP+33 Def+33 | Decreases damage taken from Zombie Pigmen, Zombies, Withers, and Skeletons by 2%. |
| **Moil** | Moil Log (`MOIL_LOG`) | UNCOMMON | Axe | 1,000/5,000/10,000/15,000/50,000/75,000 | C: ForgWis+1<br>U: ForgWis+1<br>R: ForgWis+2<br>E: ForgWis+2<br>L: ForgWis+3<br>M: ForgWis+3 | Grants +[1/1/2/2/3/3]☯ Foraging Wisdom. (values by rarity C/U/R/E/L/M) |
| **Moonglade** | Moonglade Jewel (`MOONGLADE_JEWEL`) | RARE | Axe | 10,000/15,000/20,000/30,000/40,000/50,000/50,000 | C: ForgFort+4 ForgWis+1<br>U: ForgFort+6 ForgWis+2<br>R: ForgFort+8 ForgWis+3<br>E: ForgFort+10 ForgWis+4<br>L: ForgFort+12 ForgWis+5<br>M: ForgFort+15 ForgWis+6<br>D: ForgFort+15 ForgWis+6 |  |
| **Toil** | Toil Log (`TOIL_LOG`) | EPIC | Axe | 10,000/10,000/10,000/10,000/10,000/10,000 | C: Str+5 CD+5 ForgWis+1<br>U: Str+7 CD+7 ForgWis+2<br>R: Str+10 CD+10 ForgWis+3<br>E: Str+13 CD+13 ForgWis+4<br>L: Str+16 CD+16 ForgWis+5<br>M: Str+20 CD+20 ForgWis+6 | Grants +[1/2/3/4/5/6]☯ Foraging Wisdom. (values by rarity C/U/R/E/L/M) |
| **Bloodshot** | Shriveled Cornea (`SHRIVELED_CORNEA`) | LEGENDARY | Belt | 50,000/100,000/250,000/500,000/1,000,000/2,500,000 | C: CD+1 CC+1 Str+1<br>U: CD+2 CC+2 Str+2<br>R: CD+3 CC+3 Str+3<br>E: CD+4 CC+4 Str+4<br>L: CD+5 CC+5 Str+5<br>M: CD+6 CC+6 Str+6 |  |
| **Headstrong** | Kaleidoscopic Mineral (`SALMON_OPAL`) | RARE | Bow | 15,000/30,000/60,000/125,000/250,000/500,000 | C: Str+2 CC+10 CD+4<br>U: Str+5 CC+11 CD+8<br>R: Str+10 CC+12 CD+16<br>E: Str+16 CC+13 CD+28<br>L: Str+23 CC+15 CD+42<br>M: Str+33 CC+17 CD+60 | Deal +8% extra damage when arrows hit the head of a mob. |
| **Precise** | Optical Lens (`OPTICAL_LENS`) | RARE | Bow | 30,000/75,000/150,000/300,000/600,000/1,200,000 | C: Str+3 CC+8 CD+5<br>U: Str+7 CC+9 CD+10<br>R: Str+12 CC+10 CD+18<br>E: Str+18 CC+11 CD+32<br>L: Str+25 CC+13 CD+50<br>M: Str+34 CC+15 CD+70 | Deal +10% extra damage when arrows hit the head of a mob. |
| **Spiritual** | Spirit Stone (`SPIRIT_DECOY`) | RARE | Bow | 60,000/125,000/250,000/500,000/1,000,000/2,000,000 | C: Str+4 CC+7 CD+10<br>U: Str+8 CC+8 CD+15<br>R: Str+14 CC+9 CD+23<br>E: Str+20 CC+10 CD+37<br>L: Str+28 CC+12 CD+55<br>M: Str+38 CC+14 CD+75 | Grants a 10% chance to spawn a Spirit Decoy when you kill a enemy in a dungeon. |
| **Loving** | Red Scarf (`RED_SCARF`) | RARE | Chestplate | 30,000/75,000/150,000/300,000/600,000/1,200,000 | C: HP+4 Def+4 Int+20 AbilDmg%+5<br>U: HP+5 Def+5 Int+40 AbilDmg%+5<br>R: HP+6 Def+6 Int+60 AbilDmg%+5<br>E: HP+8 Def+7 Int+80 AbilDmg%+5<br>L: HP+10 Def+10 Int+100 AbilDmg%+5<br>M: HP+14 Def+14 Int+120 AbilDmg%+5 | Increases ability damage by +5% |
| **Blood-Soaked** | Presumed Gallon of Red Paint (`PRESUMED_GALLON_OF_RED_PAINT`) | RARE | Cloak | 7,500/15,000/30,000/75,000/150,000/300,000/400,000 | C: HP+6 Def+1 Vit+1<br>U: HP+8 Def+2 Vit+1<br>R: HP+8 Def+3 Vit+2<br>E: HP+9 Def+4 Vit+2<br>L: HP+10 Def+5 Vit+3<br>M: HP+12 Def+6 Vit+3<br>D: HP+14 Def+7 Vit+4 | Heal 1.15x more from Vampirism and Lifesteal. |
| **Blazing** | Blazen Sphere (`BLAZEN_SPHERE`) | RARE | Equipment | 20,000/40,000/80,000/150,000/300,000/300,000/300,000 | C: MinFort+3 HeatRes+1<br>U: MinFort+4 HeatRes+1<br>R: MinFort+5 HeatRes+2<br>E: MinFort+6 HeatRes+2<br>L: MinFort+7 HeatRes+3<br>M: MinFort+7 HeatRes+3<br>D: MinFort+8 HeatRes+3 | For every 1♨ Heat you have, gain a +0.05% chance to dig up Worms. |
| **Blooming** | Flowering Bouquet (`FLOWERING_BOUQUET`) | RARE | Equipment | 5,000/10,000/20,000/50,000/100,000/200,000 | C: Spd+4 FarmFort+1<br>U: Spd+4 FarmFort+2<br>R: Spd+5 FarmFort+3<br>E: Spd+5 FarmFort+4<br>L: Spd+6 FarmFort+5<br>M: Spd+6 FarmFort+6 |  |
| **Fortified** | Meteor Chunk (`METEOR_SHARD`) | RARE | Equipment | 7,500/15,000/30,000/75,000/150,000/300,000/400,000 | C: Def+12<br>U: Def+14<br>R: Def+17<br>E: Def+20<br>L: Def+25<br>M: Def+30<br>D: Def+36 |  |
| **Glistening** | Shiny Prism (`SHINY_PRISM`) | RARE | Equipment | 7,500/15,000/30,000/75,000/150,000/300,000/400,000 | C: Int+5 TD+2<br>U: Int+6 TD+2.5<br>R: Int+8 TD+3<br>E: Int+10 TD+3.5<br>L: Int+12 TD+4<br>M: Int+15 TD+4.5<br>D: Int+18 TD+4.5 |  |
| **Lunar** | Moonstone (`MOONSTONE`) | RARE | Equipment | 5,000/10,000/20,000/50,000/100,000/200,000/200,000 | C: Spd+1 FarmWis+1<br>U: Spd+1 FarmWis+1<br>R: Spd+2 FarmWis+2<br>E: Spd+2 FarmWis+2<br>L: Spd+3 FarmWis+3<br>M: Spd+4 FarmWis+4<br>D: Spd+4 FarmWis+4 |  |
| **Marshy** | Marshroom (`MARSHROOM`) | UNCOMMON | Equipment | 5,000/25,000/100,000/250,000/500,000/1,000,000 | C: SCC+0.1 ForgFort+0.5 FishSpd+0.2<br>U: SCC+0.2 ForgFort+1 FishSpd+0.4<br>R: SCC+0.3 ForgFort+1.5 FishSpd+0.6<br>E: SCC+0.5 ForgFort+2.5 FishSpd+1<br>L: SCC+0.7 ForgFort+3.5 FishSpd+1.4<br>M: SCC+0.9 ForgFort+4.5 FishSpd+1.8 | Increases Forest Whispers gain by +1%. |
| **Rooted** | Burrowing Spores (`BURROWING_SPORES`) | RARE | Equipment | 20,000/40,000/80,000/150,000/300,000/600,000 | C: HP+2 FarmFort+6<br>U: HP+5 FarmFort+9<br>R: HP+8 FarmFort+12<br>E: HP+11 FarmFort+15<br>L: HP+14 FarmFort+18<br>M: HP+17 FarmFort+21 |  |
| **Royal** | Dwarven Treasure (`DWARVEN_TREASURE`) | RARE | Equipment | 5,000/10,000/20,000/50,000/100,000/100,000/100,000 | C: MinSpd+4 MinFort+1<br>U: MinSpd+6 MinFort+1<br>R: MinSpd+8 MinFort+2<br>E: MinSpd+10 MinFort+2<br>L: MinSpd+14 MinFort+3<br>M: MinSpd+18 MinFort+3<br>D: MinSpd+18 MinFort+3 | Earn +15% ᠅ Mithril Powder from Mining Events in the Dwarven Mines. |
| **Snowy** | Terry's Snowglobe (`TERRY_SNOWGLOBE`) | RARE | Equipment | 10,000/25,000/50,000/100,000/200,000/300,000 | C: SCC+0.2 FishSpd+0.5<br>U: SCC+0.2 FishSpd+1<br>R: SCC+0.4 FishSpd+1.5<br>E: SCC+0.6 FishSpd+2<br>L: SCC+0.8 FishSpd+2.5<br>M: SCC+1 FishSpd+3 |  |
| **Squeaky** | Squeaky Toy (`SQUEAKY_TOY`) | RARE | Equipment | 7,500/15,000/30,000/75,000/150,000/150,000/150,000 | C: PestCh+0.5 FarmFort+2<br>U: PestCh+0.5 FarmFort+4<br>R: PestCh+1 FarmFort+6<br>E: PestCh+1.5 FarmFort+8<br>L: PestCh+2 FarmFort+10<br>M: PestCh+2.5 FarmFort+12<br>D: PestCh+2.5 FarmFort+12 | Decreases the spawn cooldown of Pests by 1%. |
| **Strengthened** | Searing Stone (`SEARING_STONE`) | RARE | Equipment | 7,500/15,000/30,000/75,000/150,000/300,000/400,000 | C: Def+3 Str+2<br>U: Def+4 Str+3<br>R: Def+5 Str+4<br>E: Def+6 Str+5<br>L: Def+8 Str+6<br>M: Def+10 Str+7<br>D: Def+12 Str+8 |  |
| **Thorny** | Blooming Thorns (`BLOOMING_THORNS`) | EPIC | Equipment | 20,000/40,000/80,000/150,000/300,000/600,000 | C: Overbloom+0.25 FarmFort+2<br>U: Overbloom+0.5 FarmFort+4<br>R: Overbloom+0.75 FarmFort+6<br>E: Overbloom+1 FarmFort+8<br>L: Overbloom+1.25 FarmFort+10<br>M: Overbloom+1.5 FarmFort+12 | Overbloom per tier of Thorns on all of your armor pieces. |
| **Waxed** | Blaze Wax (`BLAZE_WAX`) | RARE | Equipment | 7,500/15,000/30,000/75,000/150,000/300,000/400,000 | C: HP+5 CC+2<br>U: HP+6 CC+3<br>R: HP+8 CC+4<br>E: HP+10 CC+5<br>L: HP+12 CC+6<br>M: HP+15 CC+7<br>D: HP+20 CC+8 |  |
| **Blessed** | Blessed Fruit (`BLESSED_FRUIT`) | EPIC | Farming tool | 10,000/10,000/10,000/10,000/10,000/10,000 | C: Spd+5 FarmFort+5 FarmWis+1<br>U: Spd+7 FarmFort+7 FarmWis+2<br>R: Spd+9 FarmFort+9 FarmWis+3<br>E: Spd+13 FarmFort+13 FarmWis+4<br>L: Spd+16 FarmFort+16 FarmWis+5<br>M: Spd+20 FarmFort+20 FarmWis+6 | Grants a [0.1/0.13/0.16/0.19/0.22]% chance to drop an enchanted item when mining Crops. (values by rarity C/U/R/E/L) |
| **Bountiful** | Golden Ball (`GOLDEN_BALL`) | EPIC | Farming tool | 20,000/40,000/80,000/150,000/300,000/600,000 | C: Spd+1 FarmFort+1<br>U: Spd+2 FarmFort+2<br>R: Spd+3 FarmFort+3<br>E: Spd+5 FarmFort+5<br>L: Spd+8 FarmFort+7<br>M: Spd+13 FarmFort+10 | Grants +0.2 coins per crop. |
| **Deep Fried** | Hashbrown (`HASHBROWN`) | EPIC | Farming tool | 20,000/40,000/80,000/150,000/300,000 | C: FarmFort+3<br>U: FarmFort+6<br>R: FarmFort+10<br>E: FarmFort+15<br>L: FarmFort+20 | Increases the odds of finding Seasonings by +[5/10/15/20/25]%. (values by rarity C/U/R/E/L) |
| **Earthy** | Large Walnut (`LARGE_WALNUT`) | RARE | Farming tool | 5,000/10,000/20,000/50,000/100,000 | C: FarmFort+5<br>U: FarmFort+10<br>R: FarmFort+15<br>E: FarmFort+20<br>L: FarmFort+25 | Earn +5% Sowdust from farming. |
| **Overpriced** | Overpriced Drink (`OVERPRICED_DRINK`) | EPIC | Farming tool | 20,000/40,000/80,000/150,000/300,000 | C: FarmFort+5 Overbloom+1<br>U: FarmFort+10 Overbloom+2<br>R: FarmFort+15 Overbloom+3<br>E: FarmFort+20 Overbloom+5<br>L: FarmFort+25 Overbloom+7 |  |
| **Sticky** | Extremely Mild Adhesive (`EXTREMELY_MILD_ADHESIVE`) | RARE | Fishing net | 10,000/25,000/100,000/250,000/500,000 | U: Pull+5<br>R: Pull+7.5<br>E: Pull+10<br>L: Pull+15<br>M: Pull+20 | Grants +2.5 Hunting Fortune on Water Shards. |
| **Chomp** | Kuudra Mandible (`KUUDRA_MANDIBLE`) | EPIC | Fishing rod | 20,000/40,000/80,000/150,000/300,000/600,000 | C: Str+5 CC+5 FishSpd+2<br>U: Str+10 CC+10 FishSpd+3<br>R: Str+17 CC+17 FishSpd+5<br>E: Str+25 CC+25 FishSpd+7<br>L: Str+35 CC+35 FishSpd+9<br>M: Str+50 CC+50 FishSpd+11 | Each unique Lava Sea Creature killed with this rod in your inventory reduces their ❤ Health when fished by 1%. Capped at -20% health. |
| **Lucky** | Lucky Dice (`LUCKY_DICE`) | EPIC | Fishing rod | 20,000/40,000/80,000/150,000/300,000/600,000 | C: SCC+1 MF+1<br>U: SCC+2 MF+2<br>R: SCC+2 MF+3<br>E: SCC+3 MF+4<br>L: SCC+5 MF+5<br>M: SCC+7 MF+6 |  |
| **Pitchin'** | Pitchin' Koi (`PITCHIN_KOI`) | RARE | Fishing rod | 5,000/20,000/40,000/80,000/120,000/280,000 | C: FishSpd+1 SCC+1<br>U: FishSpd+2 SCC+1<br>R: FishSpd+4 SCC+2<br>E: FishSpd+6 SCC+3<br>L: FishSpd+8 SCC+4<br>M: FishSpd+10 SCC+5 |  |
| **Salty** | Salt Cube (`SALT_CUBE`) | UNCOMMON | Fishing rod | 2,500/10,000/20,000/40,000/80,000/120,000 | C: SCC+1<br>U: SCC+2<br>R: SCC+2<br>E: SCC+3<br>L: SCC+5<br>M: SCC+7 |  |
| **Stiff** | Hardened Wood (`HARDENED_WOOD`) | UNCOMMON | Fishing rod | 4,000/7,500/15,000/40,000/75,000/150,000 | C: SCC+1 Str+2<br>U: SCC+2 Str+4<br>R: SCC+2 Str+6<br>E: SCC+3 Str+8<br>L: SCC+5 Str+10<br>M: SCC+7 Str+12 |  |
| **Trashy** | Overflowing Trash Can (`OVERFLOWING_TRASH_CAN`) | UNCOMMON | Fishing rod | 2,500/10,000/20,000/40,000/80,000/120,000/500,000 | C: SCC+0.5 FishSpd+1<br>U: SCC+1 FishSpd+2<br>R: SCC+1.5 FishSpd+3<br>E: SCC+2 FishSpd+4<br>L: SCC+3 FishSpd+6<br>M: SCC+4 FishSpd+8<br>D: SCC+4 FishSpd+8 |  |
| **Treacherous** | Rusty Anchor (`RUSTY_ANCHOR`) | RARE | Fishing rod | 5,000/20,000/40,000/80,000/120,000/280,000 | C: Str+5 SCC+1<br>U: Str+10 SCC+2<br>R: Str+15 SCC+2<br>E: Str+20 SCC+3<br>L: Str+25 SCC+5<br>M: Str+30 SCC+7 |  |
| **Ridiculous** | Red Nose (`RED_NOSE`) | RARE | Helmet | 7,500/15,000/30,000/75,000/150,000/300,000 | C: CC+1 HP+10 Def+10<br>U: CC+2 HP+15 Def+15<br>R: CC+3 HP+20 Def+20<br>E: CC+4 HP+25 Def+25<br>L: CC+5 HP+35 Def+35<br>M: CC+6 HP+50 Def+50 | Fart when you sneak. Reduces your ☣ Crit Chance by 20% for 20s but grants +30 ❈ Defense for 5s and +50 mana. Requires at least 20% ☣ Crit Chance to activate. |
| **Ambered** | Amber Material (`AMBER_MATERIAL`) | EPIC | Pickaxe/Drill | 20,000/40,000/80,000/150,000/300,000/600,000/800,000 | C: MinSpd+25<br>U: MinSpd+35<br>R: MinSpd+50<br>E: MinSpd+75<br>L: MinSpd+100<br>M: MinSpd+150<br>D: MinSpd+200 | Increases your chances of finding Golden Goblins and Diamond Goblins while mining by +[10/15/20/25/30/40/50]%. (values by rarity C/U/R/E/L/M/D) |
| **Auspicious** | Dwarven Geode (`ROCK_GEMSTONE`) | RARE | Pickaxe/Drill | 20,000/40,000/80,000/150,000/300,000/600,000/600,000 | C: MinSpd+10 MinFort+3<br>U: MinSpd+20 MinFort+6<br>R: MinSpd+30 MinFort+9<br>E: MinSpd+40 MinFort+13<br>L: MinSpd+50 MinFort+16<br>M: MinSpd+75 MinFort+20<br>D: MinSpd+100 MinFort+25 | Grants +[0.4/0.5/0.6/0.7/0.8/0.9/1]% ☘ Mining Fortune. (values by rarity C/U/R/E/L/M/D) |
| **Fleet** | Diamonite (`DIAMONITE`) | RARE | Pickaxe/Drill | 15,000/30,000/60,000/125,000/250,000/500,000/500,000 | C: MinSpd+10 BlockFort+25<br>U: MinSpd+20 BlockFort+30<br>R: MinSpd+30 BlockFort+35<br>E: MinSpd+40 BlockFort+45<br>L: MinSpd+50 BlockFort+55<br>M: MinSpd+75 BlockFort+65<br>D: MinSpd+100 BlockFort+75 | Grants +[50/60/70/85/100/125/150]▚ Mining Spread when mining Blocks. (values by rarity C/U/R/E/L/M/D) |
| **Fruitful** | Black Diamond (`ONYX`) | RARE | Pickaxe/Drill | 100/250/500/1,000/2,500/5,000/10,000 | C: MinSpd+5 MinFort+2<br>U: MinSpd+10 MinFort+4<br>R: MinSpd+15 MinFort+6<br>E: MinSpd+25 MinFort+8<br>L: MinSpd+40 MinFort+10<br>M: MinSpd+55 MinFort+12<br>D: MinSpd+70 MinFort+15 | Grants +[25/30/35/40/50/60/70]❈ Defense while on Mining Islands. (values by rarity C/U/R/E/L/M/D) |
| **Glacial** | Frigid Husk (`FRIGID_HUSK`) | LEGENDARY | Pickaxe/Drill | 20,000/40,000/80,000/150,000/300,000/600,000/800,000 | C: MinFort+3<br>U: MinFort+6<br>R: MinFort+9<br>E: MinFort+13<br>L: MinFort+16<br>M: MinFort+20<br>D: MinFort+25 | Grants +[0.5/0.7/0.9/1.1/1.4/1.7/2]☘ Mining Fortune for every 1❄ Cold you have. (values by rarity C/U/R/E/L/M/D) |
| **Heated** | Scorched Topaz (`HOT_STUFF`) | LEGENDARY | Pickaxe/Drill | 20,000/40,000/80,000/150,000/300,000/600,000/600,000 | C: MinSpd+5 MinFort+3<br>U: MinSpd+10 MinFort+6<br>R: MinSpd+15 MinFort+9<br>E: MinSpd+25 MinFort+13<br>L: MinSpd+40 MinFort+16<br>M: MinSpd+55 MinFort+20<br>D: MinSpd+70 MinFort+25 | Grants +[0.3/0.4/0.5/0.6/0.7/0.8/1]☘ Mining Fortune and +[1/1.4/1.8/2.2/2.8/3.4/4]⸕ Mining Speed per 1♨ Heat you have. (values by rarity C/U/R/E/L/M/D) |
| **Lustrous** | Gleaming Crystal (`GLEAMING_CRYSTAL`) | EPIC | Pickaxe/Drill | 20,000/40,000/80,000/150,000/300,000/600,000/800,000 | C: MinSpd+10 MinFort+10<br>U: MinSpd+20 MinFort+20<br>R: MinSpd+30 MinFort+30<br>E: MinSpd+40 MinFort+40<br>L: MinSpd+50 MinFort+50<br>M: MinSpd+60 MinFort+60<br>D: MinSpd+75 MinFort+75 | Grants +[5/10/15/20/25/30/35]▚ Mining Spread during Mining Fiesta. (values by rarity C/U/R/E/L/M/D) |
| **Magnetic** | Lapis Crystal (`LAPIS_CRYSTAL`) | RARE | Pickaxe/Drill | 250/500/1,000/2,500/5,000/10,000/15,000 | C: MinSpd+10<br>U: MinSpd+20<br>R: MinSpd+30<br>E: MinSpd+40<br>L: MinSpd+50<br>D: MinSpd+60 | Earn +[25/35/45/55/65/80/100]% more Exp when mining. (values by rarity C/U/R/E/L/M/D) |
| **Mithraic** | Pure Mithril (`PURE_MITHRIL`) | RARE | Pickaxe/Drill | 15,000/30,000/60,000/125,000/250,000/500,000/500,000 | C: MinFort+3<br>U: MinFort+6<br>R: MinFort+9<br>E: MinFort+13<br>L: MinFort+16<br>M: MinFort+20<br>D: MinFort+25 | Grants +[10/15/20/25/30/40/50]☘ Mining Fortune when mining Mithril or Titanium. (values by rarity C/U/R/E/L/M/D) |
| **Refined** | Andesite Whetstone (`REFINED_AMBER`) | EPIC | Pickaxe/Drill | 10,000/10,000/10,000/10,000/10,000/10,000/10,000 | C: MinWis+1<br>U: MinWis+2<br>R: MinWis+3<br>E: MinWis+4<br>L: MinWis+5<br>M: MinWis+6<br>D: MinWis+7 | Grants a [0.1/0.13/0.16/0.19/0.22/0.26/0.3]% chance to drop an enchanted item when mining Ores. (values by rarity C/U/R/E/L/M/D) |
| **Scraped** | Pocket Iceberg (`POCKET_ICEBERG`) | EPIC | Pickaxe/Drill | 15,000/30,000/60,000/125,000/250,000/500,000/500,000 | C: BrkPow+1 MinWis+1<br>U: BrkPow+1 MinWis+1<br>R: BrkPow+1 MinWis+1<br>E: BrkPow+1 MinWis+2<br>L: BrkPow+1 MinWis+2<br>M: BrkPow+1 MinWis+2<br>D: BrkPow+1 MinWis+3 | Grants +25☘ Mining Fortune if the block you are mining has the same Ⓟ Breaking Power as this tool. |
| **Stellar** | Petrified Starfall (`PETRIFIED_STARFALL`) | EPIC | Pickaxe/Drill | 25,000/50,000/100,000/200,000/400,000/800,000/800,000 | C: MinSpd+5 MinFort+5<br>U: MinSpd+10 MinFort+10<br>R: MinSpd+15 MinFort+15<br>E: MinSpd+25 MinFort+20<br>L: MinSpd+40 MinFort+25<br>M: MinSpd+55 MinFort+35<br>D: MinSpd+70 MinFort+50 | Earn +[8/9/10/12/14/17/20]% Mithril Powder and +[25/30/35/45/55/65/75]☘ Mining Fortune when mining near a Fallen Star. (values by rarity C/U/R/E/L/M/D) |
| **Bulky** | Bulky Stone (`BULKY_STONE`) | EPIC | Sword | 20,000/40,000/80,000/150,000/300,000/600,000 | C: HP+4 Def+2<br>U: HP+6 Def+3<br>R: HP+9 Def+5<br>E: HP+12 Def+8<br>L: HP+15 Def+13<br>M: HP+20 Def+21 |  |
| **Fabled** | Dragon Claw (`DRAGON_CLAW`) | RARE | Sword | 60,000/125,000/250,000/500,000/1,000,000/2,000,000 | C: Str+30 CD+15<br>U: Str+35 CD+20<br>R: Str+40 CD+25<br>E: Str+50 CD+32<br>L: Str+60 CD+40<br>M: Str+75 CD+50 | Critical hits have a chance to deal up to 15% extra damage. |
| **Suspicious** | Suspicious Vial (`SUSPICIOUS_VIAL`) | RARE | Sword | 60,000/125,000/250,000/500,000/1,000,000/2,000,000 | C: CC+1 CD+30 Dmg+15<br>U: CC+2 CD+40 Dmg+15<br>R: CC+3 CD+50 Dmg+15<br>E: CC+5 CD+65 Dmg+15<br>L: CC+7 CD+85 Dmg+15<br>M: CC+10 CD+110 Dmg+15 | Increase weapon damage by +15 |
| **Withered** | Wither Blood (`WITHER_BLOOD`) | EPIC | Sword | 10,000/20,000/30,000/40,000/50,000/60,000 | C: Str+60<br>U: Str+75<br>R: Str+90<br>E: Str+110<br>L: Str+135<br>M: Str+170 | Grants +1 ❁ Strength per Catacombs level. |
| **Dirty** | Dirt Bottle (`DIRT_BOTTLE`) | RARE | Sword + Fishing weapon | 1,000/5,000/10,000/15,000/50,000/75,000 | C: Str+2 Fer+2 AtkSpd+2<br>U: Str+4 Fer+3 AtkSpd+3<br>R: Str+6 Fer+6 AtkSpd+5<br>E: Str+10 Fer+9 AtkSpd+10<br>L: Str+12 Fer+12 AtkSpd+15<br>M: Str+15 Fer+15 AtkSpd+20 |  |
| **Beady** | Beady Eyes (`BEADY_EYES`) | EPIC | Vacuum | 10,000/20,000/50,000/75,000/100,000/150,000 | C: Int+10 Dmg+5<br>U: Int+20 Dmg+10<br>R: Int+30 Dmg+15<br>E: Int+40 Dmg+20<br>L: Int+50 Dmg+25<br>M: Int+60 Dmg+30 | Grants +100☘ Farming Fortune on Pests. |
| **Buzzing** | Clipped Wings (`CLIPPED_WINGS`) | EPIC | Vacuum | 10,000/20,000/50,000/75,000/100,000/150,000/250,000 | C: FarmFort+2<br>U: FarmFort+3<br>R: FarmFort+5<br>E: FarmFort+7<br>L: FarmFort+9<br>M: FarmFort+11<br>D: FarmFort+11 | Doubles the ❁ Damage dealt by Vacuums. |
| **Warped** | Warped Stone (`AOTE_STONE`) | RARE | only: ASPECT_OF_THE_END, ASPECT_OF_THE_VOID | 5,000,000/5,000,000/5,000,000 | R: Dmg+165 Str+165 Int+65<br>E: Dmg+165 Str+165 Int+100<br>L: Dmg+165 Str+165 Int+150 |  |
| **Jerry's** | Jerry Stone (`JERRY_STONE`) | RARE | only: ASPECT_OF_THE_JERRY, ASPECT_OF_THE_JERRY_SIGNATURE | 1/2/3 | C: Str+25 CC+10 CD+50<br>U: Str+25 CC+10 CD+50<br>R: Str+25 CC+10 CD+50 |  |
| **Coldfused** | Entropy Suppressor (`ENTROPY_SUPPRESSOR`) | RARE | only: BURSTFIRE_DAGGER, BURSTMAW_DAGGER, FIREDUST_DAGGER, HEARTFIRE_DAGGER, HEARTMAW_DAGGER, MAWDUST_DAGGER | 60,000/125,000/250,000/500,000/1,000,000/2,000,000 | C: MF+2 Str+15 CD+20<br>U: MF+2 Str+20 CD+30<br>R: MF+2 Str+25 CD+40<br>E: MF+2 Str+35 CD+50<br>L: MF+2 Str+45 CD+60<br>M: MF+2 Str+55 CD+75 | Only if Wisp is equipped +75❁ Strength +55☠ Crit Damage Deal 2x to fire pillars, breaking one grants +30❂ True Defense and +1.15x damage for 60s. |
| **Erudite** | Daedalus' Notes (`DAEDALUS_NOTES`) | EPIC | only: DEIFIC_SPADE, ARCHAIC_SPADE, ANCESTRAL_SPADE | 500,000/1,000,000/2,000,000/2,000,000 | R: Spd+8<br>E: Spd+11<br>L: Spd+15<br>M: Spd+20 | Griffin Burrow chains always consist of 2 additional burrows. |
| **Greater Spook** | BOO_STONE (`BOO_STONE`) | ? | only: GREAT_SPOOK_HELMET, GREAT_SPOOK_CHESTPLATE, GREAT_SPOOK_LEGGINGS, GREAT_SPOOK_BOOTS, GREAT_SPOOK_BELT, GREAT_SPOOK_CLOAK, GREAT_SPOOK_NECKLACE, GREAT_SPOOK_GLOVES | 10,000/10,000 | E: Fear+4<br>L: Fear+5 | Grants +1 Fear. |
| **Gilded** | Midas Jewel (`MIDAS_JEWEL`) | RARE | only: MIDAS_SWORD, MIDAS_STAFF, STARRED_MIDAS_STAFF, STARRED_MIDAS_SWORD | 5,000,000/10,000,000 | L: Dmg+75 Str+75 AbilDmg%+8<br>M: Dmg+90 Str+90 AbilDmg%+10 | Upon killing an enemy you have a low chance to grant a random amount of coins to another player around you |
| **Majestic** | Morning Dew (`MORNING_DEW`) | EPIC | only: SAFARI_BELT | 5,000/25,000/100,000/500,000/1,000,000/2,000,000 | C: HuntWis+0.5 Sweep+0.5 ForgFort+2 HuntFort+0.5<br>U: HuntWis+1 Sweep+1 ForgFort+4 HuntFort+1<br>R: HuntWis+1.5 Sweep+1.5 ForgFort+6 HuntFort+1.5<br>E: HuntWis+2 Sweep+2 ForgFort+8 HuntFort+2<br>L: HuntWis+3 Sweep+3 ForgFort+12 HuntFort+3<br>M: HuntWis+4 Sweep+4 ForgFort+16 HuntFort+4 | Grants +50 Speed while in the Critter Safari. |
| **Fanged** | Full-Jaw Fanging Kit (`FULL_JAW_FANGING_KIT`) | RARE | only: iron_sword | 10,000/12,500/25,000/50,000/100,000/250,000 | C: Str+30 AtkSpd+2 CC+3 Vit+2<br>U: Str+35 AtkSpd+3 CC+4 Vit+3<br>R: Str+40 AtkSpd+4 CC+5 Vit+4<br>E: Str+50 AtkSpd+6 CC+7 Vit+6<br>L: Str+60 AtkSpd+9 CC+8 Vit+8<br>M: Str+65 AtkSpd+10 CC+10 Vit+10 | Every 7th melee hit on an enemy deals +100% damage. |

Reforge-stone items in the live item API that the NEU table doesn't cover yet (newer content — stats unknown; verify or design your own):
Mandraa (`MANDRAA`, RARE), Furball (`FURBALL`, RARE), Obsidian Tablet (`OBSIDIAN_TABLET`, RARE), Beating Heart (`BEATING_HEART`, RARE), End Stone Shulker (`END_STONE_SHULKER`, RARE), Acacia Birdhouse (`ACACIA_BIRDHOUSE`, RARE), Dark Orb (`DARK_ORB`, RARE), Ender Monocle (`ENDER_MONOCLE`, RARE), Scorched Books (`SCORCHED_BOOKS`, MYTHIC), Eccentric Painting (`ECCENTRIC_PAINTING`, LEGENDARY), Precious Pearl (`PRECIOUS_PEARL`, EPIC), Magma Urchin (`MAGMA_URCHIN`, EPIC), Horns of Torment (`HORNS_OF_TORMENT`, EPIC), Rock Candy (`ROCK_CANDY`, UNCOMMON), Luxurious Spool (`LUXURIOUS_SPOOL`, UNCOMMON), Glacite Chunk (`GLACITE_SHARD`, LEGENDARY), Hazmat Enderman (`HAZMAT_ENDERMAN`, LEGENDARY), Vitamin Death (`VITAMIN_DEATH`, LEGENDARY), Geometric Oddity (`GEOMETRIC_ODDITY`, RARE), Sunflower Butter (`SUNFLOWER_BUTTER`, RARE), Firestone (`FIRE_STONE`, EPIC), Bubba Blister (`BUBBA_BLISTER`, LEGENDARY), Fang-tastic Chocolate Chip (`CHOCOLATE_CHIP`, LEGENDARY), Displaced Leech (`DISPLACED_LEECH`, UNCOMMON)

---

## 14. Enchantments

### 14.1 Rules & stations
- **Enchantment Table** (bookshelf power as in vanilla; SkyBlock "Enchant Item" GUI): choose an enchant and a level up to its **table max**; costs XP levels (§14.9).
  Re-enchanting replaces the level; choosing a conflicting enchant replaces the conflicting one.
- **Anvil**: item + Enchanted Book applies it (XP-level cost shown in the GUI). Book + book of the **same enchant & level** → level +1; books combine for free and
  don't add anvil uses. Combining stops at the enchant's merge cap (normally the table max, sometimes +1); the top tiers (e.g. Sharpness VI/VII, Protection VI/VII,
  Growth VI/VII, Critical VI/VII, Power VI/VII) come from special sources (Dark Auction, slayer drops, Kuudra, dungeon chests, Bits shop, events). Exact per-tier sources
  are **(?)** — treat them as design space.
- Some enchants/levels need a minimum **Enchanting skill** level to apply (?).
- **The Hex** combines anvil, reforging, gemstones and books in one UI.
- **Ultimate enchants** (`§d§l` pink bold): only **one** per item; a new one overwrites the old. From dungeon chests, slayers, Kuudra, events.
- **Level-by-use enchants**: Champion (kills), Toxophilite (arrow kills), Hecatomb (S-runs), Cultivating (crops), Absorb (logs), Expertise (catches), Compact (blocks)
  — the book is level I and the enchant levels up by doing the activity; counters are stored on the item.

### 14.2 Conflicts
Protection / Blast / Fire / Projectile Protection · Life Steal / Mana Steal / Drain (Syphon) · Execute / Prosecute · First Strike / Triple-Strike ·
Giant Killer / Titan Killer · Thunderlord / Thunderbolt · Big Brain / Small Brain · Hardened / Strong / Vivacious / Vampiric Vitality · Rejuvenate / Respite ·
Reflection / Thorns · Silk Touch / Smelting Touch / Fortune · Depth Strider / Frost Walker · all Turbo-crop enchants with each other ·
**One For All** with every normal sword enchant (applying it wipes them) · all Ultimates with each other.

### 14.3 Sword / melee enchantments (current values, community wiki)

| Enchant | Table max | Max | Effect per level |
|---|---|---|---|
| Bane of Arthropods | V | VII | +5/10/15/20/30/40/50% vs Arthropods |
| Champion | – | X | +3/3.78/4.56/5.33/6.11/6.89/7.67/8.44/9.22/10% Combat XP and +1.4/1.8/2.2/2.6/3/3.4/3.8/4.2/4.6/5 coins per kill |
| Cleave | V | VI | Deal 5/10/15/20/25/30% of the hit to enemies within 3.5/4/4.5/5/5.5/6 blocks |
| Critical | V | VII | +10/20/30/40/50/70/100% Crit Damage |
| Cubism | V | VI | +5/10/15/20/30/40% vs Cubic (slimes, magma cubes, creepers) |
| Divine Gift | – | III | +2/4/6 Magic Find |
| Drain | V | VI | +0.5/1/1.5/2/2.5/3 Vitality per hit |
| Dragon Hunter | – | V | +8% damage vs Ender Dragons per level (?) — not in the current wiki list; may have been folded into Ender Slayer (?) |
| Ender Slayer | V | VII | +5/10/15/20/30/40/50% vs Ender mobs |
| Execute | V | VI | +0.2/0.4/0.6/0.8/1/1.25% per 1% **missing** target HP |
| Experience | III | V | 12.5/25/37.5/50/62.5% chance of double XP orbs |
| Fire Aspect | II | III | burn 3 s @3%/s · 4 s @6%/s · 4 s @9%/s of hit damage |
| First Strike | IV | V | +25/50/75/100/125% on the first hit on a mob |
| Giant Killer | V | VII | +0.1/0.2/0.3/0.4/0.6/0.9/1.2% per 1% max-HP the target has over you; cap 5/10/15/20/30/45/65% |
| Gravity | – | VI | +5/10/15/20/30/40% vs Airborne |
| Impaling | III | V | +5/10/15/20/30% vs Aquatic |
| Knockback | II | II | +3/+6 blocks |
| Lethality | V | VI | −1.2/2.4/3.6/4.8/6/9% target Defense per hit for 4 s, stacks 4× |
| Life Steal | III | VI–VII | 2.4/4.8/7.2/9.6/12/14.4/16.8 HP per hit |
| Looting | III | V | +15/30/45/60/75% drop chance |
| Luck | V | VII | +5/10/15/20/25/30/35% armour drop chance |
| Mana Steal | – | III | 0.25/0.5/0.75% max Mana per hit |
| Prosecute | V | VI | +0.1/0.2/0.3/0.4/0.7/1% per 1% of target **current** HP |
| Pyroclasm | – | VI | +10/20/30/40/60/80% vs Magmatic |
| Scavenger | III | VI | +0.3/0.6/0.9/1.2/1.5/1.8 coins per monster level |
| Sharpness | V | VII | +5/10/15/20/30/40/50% (older value set 5/10/15/20/30/45/65 still appears in old guides (?)) |
| Smite | V | VII | +5/10/15/20/30/40/50% vs Skeletal/Undead/Wither |
| Smoldering | – | V | +5/10/15/20/30% vs Infernal |
| Tabasco | – | III | +2/+3 Weapon Damage (II/III) |
| Thunderbolt | V | VII | every 3rd hit: 4/8/12/16/20/25/30% of the hit as lightning to up to 10 mobs |
| Thunderlord | V | VII | every 3rd hit: 8/16/24/32/40/50/60% of the hit as lightning |
| Titan Killer | V | VII | +2/4/6/8/10/12/14% per 100 target Def; cap 6/12/18/24/40/60/80% |
| Triple-Strike | IV | V | +10/20/30/40/50% on the first 3 hits |
| Vampirism | V | VI–VII | 5/10/15/20/25/30/35 HP per kill |
| Venomous | V | VII | per hit: −2/4/6/8/12/15/20% target speed and 0.2/0.4/0.6/0.8/1.2/1.6/2% damage over time |
| Vicious | – | V | +3/+4/+5 Ferocity (levels III–V only) |
| Woodsplitter (axes) | – | VI | +5/10/15/20/30/40% vs Woodland |
| Magmarizer | – | ? | new, sword/rod; effect not captured (?) |

### 14.4 Bow enchantments

| Enchant | Table max | Max | Effect |
|---|---|---|---|
| Power | V | VII | +8/16/24/32/40/50/65% bow damage |
| Punch | II | II | +3/+6 knockback |
| Flame | II | II | burn 3.5 s @3%/s · 4 s @6%/s |
| Infinite Quiver | V | X | 3% × level chance not to use an arrow (X = 30%) |
| Piercing | I | I | arrow passes through 1 extra enemy for 25% damage |
| Snipe | III | IV | +1/2/3/4% per 10 blocks travelled |
| Dragon Tracer (was Aiming) | V | V | homing 2/4/6/8/10 blocks |
| Overload | – | V | +1→5% CC and +10→50% CD; Mega Crit chance above 100% CC |
| Chance | III | V | bow looting: +15% per level (?) |
| Toxophilite | – | X | +3→10% XP and +3.7→10 Crit Chance (levels by arrow kills) |
| Gravity, Impaling, Smoldering, Cubism, Tabasco, Vicious, Divine Gift | – | as swords | |

### 14.5 Armor enchantments

| Enchant | Piece | Table max | Max | Effect |
|---|---|---|---|---|
| Protection | all | V | VII | +4/8/12/16/20/25/30 Defense |
| Growth | all | V | VII | +15/30/45/60/75/90/105 Health |
| Blast Protection | all | V | VII | +30→210 Defense vs explosions |
| Fire Protection | all | V | VII | +2→14 True Defense vs fire |
| Projectile Protection | all | V | VII | +7→49 Defense vs projectiles |
| Thorns | all | III | IV | reflect 3/6/9/12% (50% chance) |
| Rejuvenate | all | V | V (VII per changelog) | +1→5 (→7) Health Regen |
| Respite | all | V | V (VII per changelog) | +3→15 (→21) Health Regen out of combat |
| Hardened Vitality | all | – | X | 4→40% Vitality spent → Defense (max 25) |
| Strong Vitality | all | – | X | 2→20% → Strength (max 15) |
| Vivacious Vitality | all | – | X | 1→10% → Attack Speed (max 5) |
| Vampiric Vitality | all | – | X | 0.55→1 HP per Vitality spent |
| Ice Cold | all | – | V | +1→5 Cold Resistance |
| Pesterminator | all | – | VI | +2→12 Farming Fortune, +1→6 Bonus Pest Chance |
| Forest Pledge | all | – | VI | +6/8/10/12 Foraging Fortune (III–VI) |
| Scuba | all | – | VI | +1→6 Pressure Resistance |
| Big Brain | helmet | – | V | +15/20/25 Int (III–V) |
| Small Brain | helmet | – | V | −15/−20/−25 Int, +1/2/3 True Defense |
| Hecatomb | helmet | – | X | +0.28→1% Catacombs XP, +0.56→2% class XP, +2.6→8 HP per 10 Catacombs levels |
| Transylvanian | helmet | – | V | post-2026: +3 max HP per level per nearby enemy (max 10 enemies) (?) |
| Respiration | helmet | III | IV | +15/30/45/60 breath |
| Aqua Affinity | helmet | I | I | normal mining speed underwater |
| Counter-Strike | chestplate | – | V | +6/8/10 Defense for 7 s on the first hit taken (III–V) |
| True Protection | chestplate | – | I | +5 True Defense |
| Reflection | chestplate | – | V | +2→10 Int, +1→5 TD, reflects 2/5/10/20/30× the shooter's Int |
| Smarty Pants | leggings | V | V | +5/10/15/20/25 Int |
| Tidal | leggings | – | III | +5/10/15% Defense vs Sea Creatures |
| Feather Falling | boots | V | X | +1 block safe height and −5% fall damage per level |
| Depth Strider | boots | III | III | water slowness −33/67/100% |
| Frost Walker | boots | II | II | vanilla |
| Sugar Rush | boots | III | III | +2/4/6 Speed |
| Stealth | boots | – | VI | Hunting: timid creatures 10→60 |
| Great Spook | Great Spook set | I | I | +1 Fear |

### 14.6 Tool, farming & fishing enchantments

| Enchant | Items | Max | Effect |
|---|---|---|---|
| Efficiency | axe/pick/shovel/drill/shears/gauntlet | X (table V; VI–X via Silex) | +30/50/70/90/110/130/150/170/190/210 Mining Speed |
| Fortune | pick/drill/gauntlet | IV | +10/20/30/45 Mining Fortune |
| Silk Touch / Smelting Touch | tools | I | vanilla / auto-smelt |
| Experience | pick/drill | V | double-XP chance |
| Compact | pick/drill | X | +1→10 Mining Wisdom, 0.25→0.57% enchanted-drop chance |
| Prismatic (was Pristine) | pick/drill | V | +0.5→2.5 Pristine |
| Lapidary | pick/drill | V | +10→50 Gemstone Fortune, +20→100 Mining Speed |
| Paleontologist | pick/drill | V | 2.5→12.5% Suspicious Scrap chance |
| Absorb | axe | X | +1→10 Foraging Wisdom, +2→20 Foraging Fortune |
| Harvesting | farming tools | VI | +12.5/25/37.5/50/62.5/75 Farming Fortune |
| Cultivating | farming tools | X | +1→10 Farming Wisdom, +2→20 Farming Fortune |
| Turbo-<Crop> | farming tools | VII | +5→35 that crop's Fortune |
| Dedication | farming tools | IV | +0.5/0.75/1/2 FF per crop milestone |
| Delicate | farming tools | V | don't break stems/baby crops |
| Replenish | farming tools | I | auto-replant from inventory |
| Crop Fever | hoes/axes | V | small chance (0.1→0.5% per wiki ult page; 0.001→0.005% per enchant page (?)) of 60 s: +100 FF, +15 Overbloom, extra rolls |
| Angler | rods | VI | +1→6 Sea Creature Chance |
| Blessing | rods | VI | +2→12% GREAT/OUTSTANDING treasure chance |
| Caster | rods | VI | 5→30% chance not to use bait |
| Charm | rods | VI | 2→12% chance of a higher trophy tier |
| Corruption | rods | V | 1→5% corrupted sea creature |
| Expertise | rods | X | +0.6→6 SCC, +2→20 Fishing Wisdom |
| Frail | rods | VII | sea creatures spawn with −2.5→17.5% HP |
| Luck of the Sea | rods | VII | +0.5→3.5 Treasure Chance |
| Lure | rods | VI | −5→30% max wait |
| Magnet | rods | VI | +1→6 XP orbs per catch |
| Piscary | rods | VII | +1→7 Fishing Speed |
| Quick Bite | rods | V | fish swim 5→25% faster |
| Spiked Hook | fishing weapons | VII | +5→35% damage |
| Rainbow | shears | III | random wool colour / colour change |

### 14.7 Equipment enchantments

| Enchant | Items | Max | Effect |
|---|---|---|---|
| Cayenne | equipment | V | IV: +0.8 HP & +0.4 TD per digit; V: +1 / +0.5 per digit (digit of what is unclear — likely coins or a counter (?)) |
| Prosperity | equipment | V | +3/6/9/12/15 Health |
| Green Thumb | belt/necklace/bracelet/cloak | V | +0.05→0.25 Farming Fortune per unique Garden visitor served |
| Quantum | necklace | V | weekdays +1/2/3 Vitality (III–V, halved June 2026); weekends +1.2/1.6/2 to a random Wisdom |

### 14.8 Ultimate enchantments (one per item, `§d§l`)

| Ultimate | Items | Max | Effect per level | Source |
|---|---|---|---|---|
| One For All | melee | I | +500% damage; removes every other enchant | F7/M7 chests |
| Soul Eater | melee, bows | V | stores 2/4/6/8/10× the last killed mob's Damage, added as Strength on the next crit (cap 5k outside dungeons) | F7/M7 chests |
| Chimera | melee | V | copies 20/40/60/80/100% of the active pet's stats | Minos Inquisitor |
| Combo | melee | V | +1→5% per kill, up to 2→10 kills, within 2→10 s | dungeon chests |
| Swarm | melee | V | +2→10% per enemy within 10 blocks (max 10) | F6 chests |
| Ultimate Wise | ability weapons | V | −10→50% ability mana cost | dungeon chests |
| Ultimate Jerry | Aspect of the Jerry | V | +1,000→5,000% base damage | dungeon chests |
| Fatal Tempo | swords, bows, rods, axes, gauntlets | V | +10→50% Ferocity per hit for 3 s (cap 200%) | Kuudra |
| Inferno | swords, bows, rods, axes, gauntlets | V | every 10th hit traps the mob 5 s and deals 1.25→2.25× that hit over the duration | Kuudra |
| Rend | bows | V | left-click rips arrows from nearby mobs, each 5→25% of the last crit (max 5, 2 s cd) | F4 chests |
| Duplex (was Reiterate) | bows | V | second arrow 4→20% damage; target takes ×1.1→1.5 fire damage for 60 s | Inferno Demonlord |
| Bank | armor | V | keep 10→50% coins on death; mobs drop +0.5→2.5 coins | dungeon chests |
| Last Stand | armor | V | below 40% HP: +5→25% Defense and +2→10% Vitality regen (30 s cd) | F3+ chests |
| Legion | armor | V | +0.07→0.35% combat stats & MF per player within 30 blocks (max 20) | F5 chests |
| No Pain No Gain | armor | V | 20→100% chance for 10 XP orbs when hit | dungeon chests |
| Wisdom | armor | V | +1→5 Int per 5 XP levels (cap 20→100) | dungeon chests |
| Refrigerate | armor | V | 3→6% of mana used → Defense for 10 s (max 150/piece) | Einary |
| Habanero Tactics | armor | V (IV–V) | with slayer weapons: +20/25% damage, healing and Combat Wisdom bonuses | craft at Chili Pepper VIII |
| Bobbin' Time | armor | V (III–V) | +0.6/0.8/1% fishing stats & MF per bobber within 30 blocks (max 5) | Water Orbs / Bobbin' Scriptures |
| Sunset | armor | V | +1→5 Overbloom by day; −1→5% visitor cooldown at night | Lunar Moth |
| Flowstate | pick/drill/gauntlet | III | +1/2/3 Mining Speed per consecutive block (max 200 blocks, resets after 10 s idle) | Dwarven Mines events |
| First Impression | axes | V | +1→5 Sweep per log break, +2→10 on the first hit of a tree | Tree Gift |
| Missile | axes | V | −10→50% Throwing Axe sweep penalty | Tree Gift |
| Flash | rods | V | 1→5% instant bite | Thunder |
| The One | necklace | V (IV–V) | +0.5/1 HP and +0.1/0.2 Str per maxed collection | Riftstalker Bloodfiend |
| Crop Fever | hoes/axes | V | see §14.6 | Compacted Moonflower craft |

### 14.9 Enchanting XP-level costs (NEU `enchants.json`): cost per level I, II, III …; the "highest level" column is the number of priced tiers
(levels past the table max are anvil/book tiers) (?)

| Enchant (id) | Table max | Highest level in XP table | XP-level cost per level (I, II, III, ...) |
|---|---|---|---|
| `PROSECUTE` | 5 | 6 | 20, 25, 30, 40, 50, 200 |
| `XP_BOTTLE` | - | 1 | 0 |
| `aiming` | - | 5 | 10, 20, 30, 40, 50 |
| `angler` | 5 | 6 | 10, 20, 30, 40, 50, 75 |
| `aqua_affinity` | 1 | 1 | 15 |
| `bane_of_arthropods` | 5 | 7 | 10, 15, 20, 25, 30, 100, 200 |
| `bank` | - | 5 | 50, 100, 150, 200, 250 |
| `big_brain` | - | 5 | 0, 0, 60, 80, 100 |
| `blast_protection` | 5 | 7 | 10, 15, 20, 25, 30, 100, 200 |
| `blessing` | - | 5 | 10, 20, 30, 40, 50 |
| `bobbin_time` | - | 5 | 0, 0, 100, 120, 140 |
| `caster` | 5 | 6 | 20, 25, 30, 40, 50, 75 |
| `cayenne` | - | 5 | 100, 100, 100, 100, 200 |
| `champion` | - | 1 | 10 |
| `chance` | 3 | 5 | 15, 30, 45, 100, 200 |
| `charm` | - | 5 | 20, 25, 30, 40, 50 |
| `chimera` | - | 5 | 50, 100, 150, 200, 250 |
| `cleave` | 5 | 6 | 10, 20, 30, 40, 50, 200 |
| `combo` | - | 5 | 50, 100, 150, 200, 250 |
| `compact` | - | 1 | 25 |
| `corruption` | - | 5 | 20, 25, 30, 40, 50 |
| `counter_strike` | - | 5 | 0, 0, 60, 80, 100 |
| `critical` | 5 | 7 | 10, 20, 30, 40, 50, 75, 200 |
| `cubism` | 5 | 6 | 10, 20, 30, 40, 50, 200 |
| `dedication` | - | 4 | 20, 25, 30, 100 |
| `depth_strider` | 3 | 3 | 10, 20, 30 |
| `divine_gift` | - | 3 | 50, 100, 150 |
| `dragon_hunter` | - | 5 | 50, 100, 150, 200, 250 |
| `duplex` | - | 5 | 50, 100, 150, 200, 250 |
| `efficiency` | 5 | 5 | 10, 15, 20, 25, 30 |
| `ender_slayer` | 5 | 7 | 10, 20, 25, 30, 40, 75, 200 |
| `execute` | 5 | 6 | 20, 25, 30, 40, 50, 200 |
| `experience` | 3 | 4 | 15, 30, 45, 75 |
| `expertise` | - | 1 | 25 |
| `fatal_tempo` | - | 5 | 50, 100, 150, 200, 250 |
| `feather_falling` | 5 | 10 | 10, 15, 20, 25, 30, 60, 80, 100, 120, 140 |
| `ferocious_mana` | - | 10 | 30, 45, 60, 75, 90, 180, 240, 300, 360, 420 |
| `fire_aspect` | - | 2 | 15, 30 |
| `fire_protection` | 5 | 7 | 10, 15, 20, 25, 30, 100, 200 |
| `first_strike` | 4 | 5 | 20, 30, 40, 75, 200 |
| `flame` | - | 2 | 25, 50 |
| `flash` | - | 5 | 50, 100, 150, 200, 250 |
| `flowstate` | - | 3 | 50, 100, 150 |
| `fortune` | 3 | 3 | 15, 30, 45 |
| `frail` | 5 | 6 | 20, 25, 30, 40, 50, 75 |
| `frost_walker` | 2 | 2 | 10, 20 |
| `giant_killer` | 5 | 7 | 10, 20, 30, 40, 50, 100, 200 |
| `growth` | 5 | 7 | 10, 20, 30, 40, 50, 95, 199 |
| `habanero_tactics` | - | 5 | 0, 0, 0, 250, 300 |
| `hardened_mana` | - | 10 | 30, 45, 60, 75, 90, 180, 240, 300, 360, 420 |
| `harvesting` | 5 | 5 | 5, 10, 15, 20, 25 |
| `hecatomb` | - | 1 | 25 |
| `ice_cold` | - | 5 | 27, 41, 55, 68, 82 |
| `impaling` | - | 3 | 30, 40, 50 |
| `inferno` | - | 5 | 50, 100, 150, 200, 250 |
| `infinite_quiver` | 5 | 10 | 10, 15, 20, 25, 30, 60, 80, 100, 120, 140 |
| `knockback` | - | 2 | 15, 30 |
| `lapidary` | - | 5 | 25, 50, 100, 150, 200 |
| `last_stand` | - | 5 | 50, 100, 150, 200, 250 |
| `legion` | - | 5 | 50, 100, 150, 200, 250 |
| `lethality` | 5 | 6 | 20, 25, 30, 40, 50, 200 |
| `life_steal` | 3 | 5 | 20, 25, 30, 50, 200 |
| `looting` | 3 | 5 | 15, 30, 45, 100, 200 |
| `luck` | 5 | 7 | 10, 20, 30, 40, 50, 75, 200 |
| `luck_of_the_sea` | 5 | 6 | 10, 15, 20, 25, 30, 50 |
| `lure` | 5 | 6 | 10, 15, 20, 25, 30, 50 |
| `magnet` | 5 | 6 | 20, 25, 30, 40, 50, 75 |
| `mana_steal` | - | 3 | 20, 25, 30 |
| `mana_vampire` | - | 10 | 30, 45, 60, 75, 90, 180, 240, 300, 360, 420 |
| `no_pain_no_gain` | - | 5 | 50, 100, 150, 200, 250 |
| `one_for_all` | - | 1 | 50 |
| `overload` | - | 5 | 50, 100, 150, 200, 250 |
| `paleontologist` | - | 5 | 23, 45, 91, 136, 179 |
| `pesterminator` | - | 5 | 5, 9, 13, 18, 23 |
| `piercing` | - | 1 | 30 |
| `piscary` | 5 | 6 | 8, 16, 24, 36, 48, 60 |
| `power` | 5 | 7 | 10, 20, 30, 40, 50, 95, 200 |
| `pristine` | - | 5 | 25, 50, 100, 150, 200 |
| `projectile_protection` | 5 | 7 | 10, 15, 20, 25, 30, 100, 200 |
| `prosperity` | - | 5 | 100, 100, 100, 100, 200 |
| `protection` | 5 | 7 | 10, 15, 20, 25, 30, 100, 200 |
| `punch` | - | 2 | 15, 30 |
| `quantum` | - | 3 | 50, 50, 100 |
| `quick_bite` | - | 5 | 18, 23, 27, 36, 45 |
| `rainbow` | - | 1 | 10 |
| `reflection` | - | 5 | 20, 40, 60, 80, 100 |
| `refrigerate` | - | 5 | 50, 100, 150, 200, 250 |
| `rejuvenate` | - | 5 | 10, 20, 30, 40, 50 |
| `rend` | - | 5 | 50, 100, 150, 200, 250 |
| `replenish` | - | 1 | 50 |
| `respiration` | 3 | 3 | 10, 20, 30 |
| `respite` | - | 5 | 10, 20, 30, 40, 50 |
| `scavenger` | 3 | 5 | 10, 20, 30, 40, 50 |
| `sharpness` | 5 | 7 | 10, 15, 20, 25, 30, 100, 200 |
| `silk_touch` | 1 | 1 | 10 |
| `small_brain` | - | 3 | 60, 80, 100 |
| `smarty_pants` | - | 5 | 20, 40, 60, 80, 100 |
| `smelting_touch` | 1 | 1 | 5 |
| `smite` | 5 | 7 | 10, 15, 20, 25, 30, 100, 200 |
| `smoldering` | - | 5 | 10, 20, 30, 40, 50 |
| `snipe` | - | 3 | 20, 25, 30 |
| `soul_eater` | - | 5 | 50, 100, 150, 200, 250 |
| `spiked_hook` | 5 | 6 | 10, 20, 30, 40, 50, 75 |
| `strong_mana` | - | 10 | 30, 45, 60, 75, 90, 180, 240, 300, 360, 420 |
| `sugar_rush` | - | 3 | 20, 25, 30 |
| `sunder` | - | 5 | 5, 10, 15, 20, 25 |
| `swarm` | - | 5 | 50, 100, 150, 200, 250 |
| `syphon` | 3 | 5 | 20, 25, 30, 45, 200 |
| `tabasco` | - | 3 | 500, 500, 500 |
| `the_one` | - | 2 | 250, 300 |
| `thorns` | 3 | 3 | 15, 30, 45 |
| `thunderbolt` | 5 | 6 | 20, 25, 30, 40, 50, 200 |
| `thunderlord` | 5 | 7 | 20, 25, 30, 40, 50, 200, 0 |
| `tidal` | - | 3 | 25, 40, 55 |
| `titan_killer` | 5 | 7 | 10, 20, 30, 40, 50, 100, 200 |
| `toxophilite` | - | 1 | 25 |
| `transylvanian` | - | 2 | 100, 150 |
| `triple_strike` | 4 | 5 | 20, 30, 40, 75, 200 |
| `true_protection` | - | 1 | 40 |
| `turbo_cactus` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_cane` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_carrot` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_coco` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_melon` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_mushrooms` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_potato` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_pumpkin` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_warts` | - | 5 | 10, 20, 30, 40, 50 |
| `turbo_wheat` | - | 5 | 10, 20, 30, 40, 50 |
| `ultimate_wise` | - | 5 | 50, 100, 150, 200, 250 |
| `vampirism` | 5 | 6 | 20, 25, 30, 40, 50, 200 |
| `venomous` | 5 | 6 | 20, 25, 30, 40, 50, 200 |
| `vicious` | - | 5 | 0, 0, 60, 80, 100 |
| `wisdom` | - | 5 | 50, 100, 150, 200, 250 |

---

## 14b. Potato books (Hot & Fuming)

| Book | Weapons (sword/bow/longsword/fishing weapon) | Armor | Max | Source |
|---|---|---|---|---|
| **Hot Potato Book** (EPIC) | +2 Damage, +2 Strength each | +4 Health, +2 Defense each | 10 | Craft: 1 Enchanted Baked Potato + 3 Paper (Potato collection VIII); Catacombs chests. NPC sell 51,200 (wiki) / 38,400 (live API). Tradeable, not auctionable. |
| **Fuming Potato Book** (EPIC) | +2 Damage, +2 Strength each | +4 Health, +2 Defense each | 5, **only after 10 HPB** | Catacombs reward chests, all floors |
| Total at 15 | +30 Damage, +30 Strength | +60 Health, +30 Defense | | |

- Applied in the Anvil. Counter `hot_potato_count` 0–15 (>10 = fuming).
- Lore: potato contribution in **yellow** after the value: `§7Strength: §c+250 §e(+30)`.
- Legendary **Blaze pet** "Fusion-Style Potato" **doubles** potato-book stats.
- Not applicable to equipment, accessories or tools (?).

---

## 15. Recombobulator 3000

| Property | Value |
|---|---|
| Rarity | LEGENDARY, dungeon item, museum-donatable |
| Effect | Item rarity **+1** (Common→Uncommon→…→Legendary→Mythic→Divine; Special→Very Special). Once per item (`rarity_upgrades: 1`). |
| Knock-on effects | Reforge stats use the new rarity row; gemstone stats use the new rarity column; accessories gain MP (e.g. Legendary 16 → Mythic 22 MP); reforge/recomb costs at the new rarity; may make an accessory eligible for an Enrichment |
| Not allowed | pets & pet items (normally); items already upgraded by the Auto-Recombobulator accessory; a few items flagged `can_recombobulate: false` / `force_wipe_recomb` (API flags) |
| Lost when | item goes into a Sack or is sold to Bazaar (stackables) |
| Sources | Catacombs reward chests, all floors, ~0.4–7.7% depending on floor/chest; **guaranteed via Boss Collections** (each dungeon boss ×250 kills) |
| Trade | tradeable, not auctionable/NPC-sellable (wiki) — on the live game it is usually Bazaar/AH traded at ~6–8M coins (?) |
| Lore | Footer becomes `§d§l§ka§r §d§lMYTHIC DUNGEON SWORD §d§l§ka` (obfuscated "a" both sides). |

---

## 16. Stars (Essence upgrades), Master Stars, Kuudra stars

### 16.1 Regular stars (✪)

| Rule | Value |
|---|---|
| Where | Essence crafting: right-click an upgradeable item in **Malik's** essence menu / Anvil "Upgrade item" (?) |
| Max | **5** for dungeon items; **10** for Kuudra (Crimson-family) armor & some others; wiki: "other items up to 15" (?) |
| Bonus outside dungeons | **+2% of base stats per star** (5★ = +10%; 10★ = +20%) |
| Bonus inside Catacombs (dungeon items) | **+10% per star** (5★ = +50%), stacked with the Catacombs-level dungeon boost |
| Cost | essence of the item's type per star (API `upgrade_costs[n]`), some stars add items (Heavy Pearls on Kuudra armor ★8–10) and, per NEU data, coins on ★4 (10k) and ★5 (25k) — the **live API currently lists no coin cost** for Hyperion/Crimson stars, so coins were probably removed (?) |
| Dungeonize | non-dungeon items with `dungeon_item_conversion_cost` (e.g. AOTD 150 Dragon essence, Terminator 300, Midas 100 Gold) can be converted to dungeon items (gain Gear Score + in-dungeon stat boost) |
| Display | gold `§6✪` per star appended to the name: `§6Heroic Hyperion §6✪✪✪✪✪` |
| Kuudra 6–10 display | stars beyond 5 re-colour the existing 5 from the left (e.g. `§d✪✪§6✪✪✪` for 7★) (?) |

Essence types: **Wither, Undead, Dragon, Spider, Crimson, Ice, Gold, Diamond, Forest, Fossil(?), Safari**. Shops: Malik (Wither/Undead), Vulcan (Crimson),
Spider Tamer (Spider), Tyzzo (Dragon), Marigold & Gemma (Gold/Diamond), Frozen Alex (Ice), Rosemary & Researcher Beryl (Forest/Fossil), Archie (Safari).
Max held per type: 2,000,000,000 (since Sept 2024).

Sample star costs (NEU `essencecosts.json`; matches API `upgrade_costs` where both exist):

| Item id | Essence | Star costs (essence per star 1..N) | Extra items/coins |
|---|---|---|---|
| `HYPERION` | Wither | 150, 300, 500, 900, 1500 | ★4: coins 10000; ★5: coins 25000 |
| `VALKYRIE` | Wither | 150, 300, 500, 900, 1500 | ★4: coins 10000; ★5: coins 25000 |
| `SCYLLA` | Wither | 150, 300, 500, 900, 1500 | ★4: coins 10000; ★5: coins 25000 |
| `ASTRAEA` | Wither | 150, 300, 500, 900, 1500 | ★4: coins 10000; ★5: coins 25000 |
| `GIANTS_SWORD` | Wither | 75, 150, 300, 600, 1200 | ★4: coins 10000; ★5: coins 25000 |
| `LIVID_DAGGER` | Wither | 30, 60, 120, 250, 400 | ★4: coins 10000; ★5: coins 25000 |
| `SHADOW_FURY` | Wither | 50, 100, 200, 400, 800 | ★4: coins 10000; ★5: coins 25000 |
| `FLOWER_OF_TRUTH` | Wither | 50, 100, 200, 400, 800 | ★4: coins 10000; ★5: coins 25000 |
| `BONZO_STAFF` | Wither | 5, 10, 25, 50, 100 | ★4: coins 10000; ★5: coins 25000 |
| `POWER_WITHER_CHESTPLATE` | Wither | 100, 200, 350, 600, 1000 | ★4: coins 10000; ★5: coins 25000 |
| `POWER_WITHER_HELMET` | Wither | 50, 100, 200, 350, 500 | ★4: coins 10000; ★5: coins 25000 |
| `WISE_WITHER_CHESTPLATE` | Wither | 100, 200, 350, 600, 1000 | ★4: coins 10000; ★5: coins 25000 |
| `SHADOW_ASSASSIN_CHESTPLATE` | Wither | 35, 75, 150, 300, 500 | ★4: coins 10000; ★5: coins 25000 |
| `SUPERIOR_DRAGON_CHESTPLATE` | Dragon | 60, 100, 160, 240, 360 | ★4: coins 10000; ★5: coins 25000 |
| `STRONG_DRAGON_CHESTPLATE` | Dragon | 30, 50, 80, 120, 180 | ★4: coins 10000; ★5: coins 25000 |
| `YOUNG_DRAGON_CHESTPLATE` | Dragon | 30, 50, 80, 120, 180 | ★4: coins 10000; ★5: coins 25000 |
| `TERMINATOR` | Dragon | 100, 200, 300, 500, 750 | ★4: coins 10000; ★5: coins 25000 |
| `JUJU_SHORTBOW` | Dragon | 30, 60, 120, 200, 300 | ★4: coins 10000; ★5: coins 25000 |
| `ASPECT_OF_THE_DRAGON` | Dragon | 50, 100, 200, 300, 400 | ★4: coins 10000; ★5: coins 25000 |
| `MIDAS_SWORD` | Gold | 25, 50, 75, 125, 200 | ★4: coins 10000; ★5: coins 25000 |
| `CRIMSON_CHESTPLATE` | Crimson | 30, 35, 40, 45, 50, 55, 60, 70, 80, 90 | ★4: coins 10000; ★5: coins 25000; ★6: coins 50000; ★7: coins 100000; ★8: coins 250000, HEAVY_PEARL:2; ★9: coins 500000, HEAVY_PEARL:3; ★10: coins 1000000, HEAVY_PEARL:4 |
| `AURORA_CHESTPLATE` | Crimson | 30, 35, 40, 45, 50, 55, 60, 70, 80, 90 | ★4: coins 10000; ★5: coins 25000; ★6: coins 50000; ★7: coins 100000; ★8: coins 250000, HEAVY_PEARL:2; ★9: coins 500000, HEAVY_PEARL:3; ★10: coins 1000000, HEAVY_PEARL:4 |
| `TERROR_CHESTPLATE` | Crimson | 30, 35, 40, 45, 50, 55, 60, 70, 80, 90 | ★4: coins 10000; ★5: coins 25000; ★6: coins 50000; ★7: coins 100000; ★8: coins 250000, HEAVY_PEARL:2; ★9: coins 500000, HEAVY_PEARL:3; ★10: coins 1000000, HEAVY_PEARL:4 |
| `FERVOR_CHESTPLATE` | Crimson | 30, 35, 40, 45, 50, 55, 60, 70, 80, 90 | ★4: coins 10000; ★5: coins 25000; ★6: coins 50000; ★7: coins 100000; ★8: coins 250000, HEAVY_PEARL:2; ★9: coins 500000, HEAVY_PEARL:3; ★10: coins 1000000, HEAVY_PEARL:4 |
| `HOLLOW_CHESTPLATE` | Crimson | 30, 35, 40, 45, 50, 55, 60, 70, 80, 90 | ★4: coins 10000; ★5: coins 25000; ★6: coins 50000; ★7: coins 100000; ★8: coins 250000, HEAVY_PEARL:2; ★9: coins 500000, HEAVY_PEARL:3; ★10: coins 1000000, HEAVY_PEARL:4 |
| `REAPER_SCYTHE` | Undead | 200, 300, 400, 500, 600 | ★4: coins 10000; ★5: coins 25000 |
| `ICE_SPRAY_WAND` | Ice | 20, 50, 100, 200, 300 | ★4: coins 10000; ★5: coins 25000 |
| `ZOMBIE_KNIGHT_SWORD` | Undead | 20, 40, 80, 160, 320 | ★4: coins 10000; ★5: coins 25000 |
| `ADAPTIVE_CHESTPLATE` | Wither | 20, 50, 100, 200, 300 | ★4: coins 10000; ★5: coins 25000 |
| `PERFECT_CHESTPLATE_12` | Diamond | 30, 50, 80, 120, 180 | ★4: coins 10000; ★5: coins 25000 |

API example (live): Crimson Chestplate `upgrade_costs` = 30, 35, 40, 45, 50, 55, 60, 70(+2 Heavy Pearl), 80(+3), 90(+4) Crimson essence; prestige → Hot Crimson for 150 Crimson essence + 10 Kuudra Teeth.

### 16.2 Master Stars (➊–➎)

| Star | Requires | Effect | Source (base drop chance) |
|---|---|---|---|
| First Master Star | item at ✪✪✪✪✪ | +5% stats **in Master Mode** | Master Mode Floor III chests (~2.56%; 4.49% with S+) |
| Second | First applied | +5% (10% total) | M4 (~2.77% / 4.90%) |
| Third | Second | +5% (15%) | M5 (Obsidian ~0.29%, Bedrock ~0.88%) |
| Fourth | Third | +5% (20%) | M6 (~0.66% / 0.61%) |
| Fifth | Fourth | +5% (25%) | M7 (~0.15% / 0.31%) |

- Applied in the Anvil; wiki lists XP-level costs 300/350/400/450/500 (?).
- Display: red circled digits after the gold stars: `§6✪✪✪✪✪§c➎`.
- Stored as `upgrade_level` 6–10 (?).

### 16.3 Dungeon item quality & Catacombs boost (summary — dungeons file owns this)
- Dungeon drops roll a hidden quality: `Stat = Base × (1 + q)`, q ∈ 1%–50% (essence-shop "looting" perk raises the floor). 50% quality → auto +1 rarity.
- Floor tier of origin (F1…F7, M1…M7 → tiers 1–10) scales base stats (API `tiered_stats` arrays have 10 entries = per floor tier).
- In dungeons, dungeonised items get an extra boost from Catacombs level (Dungeon Stats Boost) (?: ~+5%/level early, tapering).
- Gear Score line: `§7Gear Score: §d<outside> §8(<in-dungeon>)`.

---

## 17. Gemstones

### 17.1 Types
| Gem | Stat | Symbol | Colour |
|---|---|---|---|
| Ruby | Health | ❤ | §c red |
| Amethyst | Defense | ❈ | §5 purple |
| Jade | Mining Fortune | ☘ | §a green |
| Amber | Mining Speed | ⸕ | §6 gold |
| Sapphire | Intelligence | ✎ | §b aqua |
| Topaz | Pristine | ✧ | §e yellow |
| Jasper | Strength | ❁ | §d pink |
| Opal | True Defense | ❂ | §f white |
| Onyx | Crit Damage | ☠ | §8 dark grey |
| Aquamarine | Fishing Speed (?) / SCC | ☂ | §3 dark aqua |
| Citrine | Foraging Fortune | ☘ | §4? (?) |
| Peridot | Farming Fortune | ☘ | §2 dark green |

Qualities: **Rough → Flawed → Fine → Flawless → Perfect** (colours §f, §a, §9, §5, §6). Crafting: 80 of a tier → 1 of the next (Rough→Flawed→Fine→Flawless);
**Perfect** is forged from 5 Flawless + that gem's Crystal (Forge). Sources: Crystal Hollows / Glacite / Smoldering Tomb gemstone blocks (3–5 Rough each by default), chests, fishing etc.

### 17.2 Stat per gem by quality × item rarity (NEU `gemstones.json`)

| Gem | Stat | Quality | C | U | R | E | L | M | D |
|---|---|---|---|---|---|---|---|---|---|
| Ruby | Health | Rough | 1 | 2 | 3 | 4 | 5 | 7 | - |
| Ruby | Health | Flawed | 3 | 4 | 5 | 6 | 8 | 10 | - |
| Ruby | Health | Fine | 4 | 5 | 6 | 8 | 10 | 14 | - |
| Ruby | Health | Flawless | 5 | 7 | 10 | 14 | 18 | 22 | - |
| Ruby | Health | Perfect | 6 | 9 | 13 | 18 | 24 | 30 | - |
| Amber | Mining Speed | Rough | 4 | 8 | 12 | 16 | 20 | 24 | 28 |
| Amber | Mining Speed | Flawed | 6 | 10 | 14 | 18 | 24 | 30 | 36 |
| Amber | Mining Speed | Fine | 10 | 14 | 20 | 28 | 36 | 45 | 54 |
| Amber | Mining Speed | Flawless | 14 | 20 | 30 | 44 | 58 | 75 | 92 |
| Amber | Mining Speed | Perfect | 20 | 28 | 40 | 60 | 80 | 100 | 120 |
| Topaz | Pristine | Rough | 0.4 | 0.4 | 0.4 | 0.4 | 0.4 | 0.4 | 0.5 |
| Topaz | Pristine | Flawed | 0.8 | 0.8 | 0.8 | 0.8 | 0.8 | 0.8 | 0.9 |
| Topaz | Pristine | Fine | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.3 |
| Topaz | Pristine | Flawless | 1.6 | 1.6 | 1.6 | 1.6 | 1.6 | 1.6 | 1.8 |
| Topaz | Pristine | Perfect | 2 | 2 | 2 | 2 | 2 | 2 | 2.2 |
| Jade | Mining Fortune | Rough | 2 | 4 | 6 | 8 | 10 | 12 | 14 |
| Jade | Mining Fortune | Flawed | 3 | 5 | 7 | 10 | 14 | 18 | 22 |
| Jade | Mining Fortune | Fine | 5 | 7 | 10 | 15 | 20 | 25 | 30 |
| Jade | Mining Fortune | Flawless | 7 | 10 | 15 | 20 | 27 | 35 | 44 |
| Jade | Mining Fortune | Perfect | 10 | 14 | 20 | 30 | 40 | 50 | 60 |
| Sapphire | Intelligence | Rough | 2 | 3 | 4 | 5 | 6 | 7 | - |
| Sapphire | Intelligence | Flawed | 5 | 5 | 6 | 7 | 8 | 10 | - |
| Sapphire | Intelligence | Fine | 7 | 8 | 9 | 10 | 11 | 12 | - |
| Sapphire | Intelligence | Flawless | 10 | 11 | 12 | 14 | 17 | 20 | - |
| Sapphire | Intelligence | Perfect | 12 | 14 | 17 | 20 | 24 | 30 | - |
| Amethyst | Defense | Rough | 1 | 2 | 3 | 4 | 5 | 7 | - |
| Amethyst | Defense | Flawed | 3 | 4 | 5 | 6 | 8 | 10 | - |
| Amethyst | Defense | Fine | 4 | 5 | 6 | 8 | 10 | 14 | - |
| Amethyst | Defense | Flawless | 5 | 7 | 10 | 14 | 18 | 22 | - |
| Amethyst | Defense | Perfect | 6 | 9 | 13 | 18 | 24 | 30 | - |
| Jasper | Strength | Rough | 1 | 1 | 1 | 2 | 3 | 4 | - |
| Jasper | Strength | Flawed | 2 | 2 | 3 | 4 | 4 | 5 | - |
| Jasper | Strength | Fine | 3 | 3 | 4 | 5 | 6 | 7 | - |
| Jasper | Strength | Flawless | 5 | 6 | 7 | 8 | 10 | 12 | - |
| Jasper | Strength | Perfect | 6 | 7 | 9 | 11 | 13 | 16 | - |
| Opal | True Defense | Rough | 1 | 1 | 1 | 2 | 2 | 3 | - |
| Opal | True Defense | Flawed | 2 | 2 | 2 | 3 | 3 | 4 | - |
| Opal | True Defense | Fine | 3 | 3 | 3 | 4 | 4 | 5 | - |
| Opal | True Defense | Flawless | 4 | 4 | 5 | 6 | 8 | 9 | - |
| Opal | True Defense | Perfect | 5 | 6 | 7 | 9 | 11 | 13 | - |
| Aquamarine | Fishing Speed | Rough | 0.5 | 0.5 | 1 | 1 | 1.5 | 2 | - |
| Aquamarine | Fishing Speed | Flawed | 1 | 1 | 1.5 | 1.5 | 2 | 2.5 | - |
| Aquamarine | Fishing Speed | Fine | 1.5 | 1.5 | 2 | 2 | 2.5 | 3 | - |
| Aquamarine | Fishing Speed | Flawless | 2 | 2 | 2.5 | 3 | 3.5 | 4 | - |
| Aquamarine | Fishing Speed | Perfect | 2.5 | 2.5 | 3.5 | 4 | 4.5 | 5 | - |
| Citrine | Foraging Fortune | Rough | 0.5 | 1 | 1.5 | 2 | 2.5 | 3 | - |
| Citrine | Foraging Fortune | Flawed | 1 | 1.5 | 2 | 2.5 | 3 | 4 | - |
| Citrine | Foraging Fortune | Fine | 1.5 | 2 | 3 | 4 | 5 | 6 | - |
| Citrine | Foraging Fortune | Flawless | 2 | 3 | 4 | 5 | 6 | 8 | - |
| Citrine | Foraging Fortune | Perfect | 3 | 4 | 5 | 6 | 8 | 10 | - |
| Onyx | Crit Damage | Rough | 1 | 1 | 2 | 2 | 3 | 4 | - |
| Onyx | Crit Damage | Flawed | 2 | 2 | 3 | 3 | 4 | 6 | - |
| Onyx | Crit Damage | Fine | 3 | 3 | 4 | 5 | 6 | 8 | - |
| Onyx | Crit Damage | Flawless | 4 | 5 | 6 | 7 | 8 | 10 | - |
| Onyx | Crit Damage | Perfect | 5 | 6 | 7 | 8 | 10 | 12 | - |
| Peridot | Farming Fortune | Rough | 0.5 | 1 | 1.5 | 2 | 2.5 | 3 | - |
| Peridot | Farming Fortune | Flawed | 1 | 1.5 | 2 | 2.5 | 3 | 4 | - |
| Peridot | Farming Fortune | Fine | 1.5 | 2 | 3 | 4 | 5 | 6 | - |
| Peridot | Farming Fortune | Flawless | 2 | 3 | 4 | 5 | 6 | 8 | - |
| Peridot | Farming Fortune | Perfect | 3 | 4 | 5 | 6 | 8 | 10 | - |

### 17.3 Slots
| Slot type | Accepts (?) where not in data | Symbol in lore |
|---|---|---|
| Single-gem slots (RUBY, AMETHYST, JADE, AMBER, SAPPHIRE, TOPAZ, JASPER, OPAL, ONYX, AQUAMARINE, CITRINE, PERIDOT) | only that gem | gem symbol |
| COMBAT | Ruby, Amethyst, Sapphire, Jasper, Onyx (+Opal? (?)) | ⚔ |
| OFFENSIVE | Sapphire, Jasper (?) | ☠ |
| DEFENSIVE | Ruby, Amethyst, Opal | ☤ |
| MINING | Jade, Amber, Topaz | ✦ |
| UNIVERSAL | any | ❂ |
| CHISEL | Citrine, Aquamarine, Peridot, Onyx (?) — applies a % of the gem's value by quality: Rough 30%, Flawed 40%, Fine 50%, Flawless 60%, Perfect 100% (NEU `chiselPercentages`, meaning unverified (?)) | ❂ (?) |

Slot counts across the live item API: COMBAT 354, AMETHYST 165, PERIDOT 143, AQUAMARINE 74, JASPER 45, SAPPHIRE 43, AMBER 43, RUBY 41, JADE 37, CITRINE 27, DEFENSIVE 14, UNIVERSAL 8, TOPAZ 7, CHISEL 6, OPAL 5, MINING 2, ONYX 1.

**Unlocking**: each slot on an item has an unlock cost (API `gemstone_slots[i].costs`; slots with no `costs` are free/pre-unlocked). Most common cost tiers:

| Slot | Unlock cost (coins + gems) | # items using this exact cost |
|---|---|---|
| COMBAT (endgame weapons/armor) | 250,000 + 1 each Flawless Jasper/Sapphire/Ruby/Amethyst | 225 |
| COMBAT (mid) | 50,000 + 5 each Fine Jasper/Sapphire/Ruby/Amethyst | 23 |
| COMBAT (mid weapons) | 100,000 + 40 each Fine Jasper/Sapphire/Ruby/Amethyst | 11 |
| COMBAT | 50,000 + 1 Fine Jasper / 100,000 + 4 Fine Jasper | 17 / 19 |
| JASPER | 50,000 + 20 Fine Jasper (1st), 100,000 + 40 Fine (2nd), 250,000 + 2 Flawless | 33 / 5 / 5 |
| SAPPHIRE | 50,000 + 20 Fine; 250,000 + 4 Flawless (Hyperion) | 12 / 5 |
| DEFENSIVE | 50,000 + 10 Fine Amethyst + 10 Fine Ruby; 250,000 + 2 Flawless each | 4 / 5 |
| UNIVERSAL | 250,000 + 20 Fine each of Amber/Amethyst/Jade/Jasper/Ruby/Sapphire/Topaz | 4 |
| PERIDOT (farming) | 50,000 + 20 Fine; 100,000 + 40 Fine; 250,000 + 1 Flawless; 1,000,000 + 2 Flawless | 56/45/24/12 |
| AQUAMARINE (fishing) | 50,000 + 1 Fine; 100,000 + 4 Fine; 250,000 + 40 Fine | 56/10/4 |
| CITRINE (foraging) | 50,000 + 20 Fine; 100,000 + 40 Fine; 250,000 + 40 Fine | 14/8/4 |
| Mining tools (Jade/Amber/Topaz) | Gemstone Chamber item (Divan armor); 5,000 + 1 Fine; 20,000 + 4 Fine | 9+9+4 / 8+4 / 8 |
| OPAL | 500,000 coins | 4 |
| AMETHYST (most armor sets) | free | 160 |

Examples: **Hyperion** = SAPPHIRE (250k + 4 Flawless Sapphire) + COMBAT (250k + 1 each Flawless); **Livid Dagger / Shadow Assassin / AOTD** = one JASPER (50k + 20 Fine Jasper);
**Giant's Sword** = 2 JASPER (50k+20 Fine, 100k+40 Fine); **Midas' Sword** = JASPER + COMBAT; **Chestplate of Divan** = 5 slots (Amber, Jade, Amber, Jade, Topaz) each via a Gemstone Chamber.

**Removal fee** (NEU `removalCosts`) — paid in coins when taking a gem out of a slot:

| Quality | Removal fee (coins) | Chisel % (?) |
|---|---|---|
| Rough | 1 | 30% |
| Flawed | 100 | 40% |
| Fine | 10,000 | 50% |
| Flawless | 100,000 | 60% |
| Perfect | 500,000 | 100% |

---

## 18. Enrichments (accessories)

| Enrichment | Stat |
|---|---|
| Speed | +1 Speed |
| Intelligence | +2 Intelligence |
| Critical Damage | +1 Crit Damage |
| Critical Chance | +1 Crit Chance |
| Strength | +1 Strength |
| Defense | +1 Defense |
| Health | +3 Health |
| Magic Find | +0.5 Magic Find |
| Ferocity | +0.3 Ferocity |
| Sea Creature Chance | +0.3 Sea Creature Chance |
| Attack Speed | +0.5 Bonus Attack Speed |

- Each costs **5,000 Bits** from Elizabeth (Community Shop), consumed on use. **One enrichment per accessory.** Only **LEGENDARY, MYTHIC, (DIVINE), SPECIAL, VERY SPECIAL** accessories.
- **Accessory Enrichment Swapper** changes the enrichment type on *all* enriched accessories at once.
- Max 65 enrichments counted per player (wiki; = number of eligible accessories at the time). Recombobulated Hocus-Pocus Cipher can't be enriched.
- Not affected by Accessory Power (flat).
- Lore: a line `§7Enrichment: §a+1 Strength ❁`(?) above the rarity footer (?); NBT `talisman_enrichment: "strength"`.

---

## 19. Other one-off upgrade items

| Item | Applies to | Effect | Max | Source |
|---|---|---|---|---|
| **The Art of War** (LEGENDARY) | swords/bows/fishing weapons | +5 Strength (`art_of_war_count`) | 1 | Dark Auction (?) |
| **The Art of Peace** (LEGENDARY) | armor | +40 Health | 1 | Kuudra (Infernal) (?) |
| Farming for Dummies | farming tools | +1 Farming Fortune each | 5 | Bazaar/Jacob (?) |
| Polarvoid Book | pickaxes/drills | +5 Mining Speed & +1 Mining Wisdom (?) each | 5 | Dwarven/Glacite (?) |
| Mana Disintegrator | items with mana abilities | −1% mana cost each | 10 | crafted (?) |
| Transmission Tuner | AOTE / AOTV | +1 teleport block each | 4 | crafted |
| Etherwarp Merger + Conduit | AOTV | adds Etherwarp (sneak-right-click teleport to block) | 1 | Enderman slayer |
| Wood Singularity | wooden weapons (e.g. Tactician's Sword, AOTJ (?)) | +1 tier of base damage (?) | 1 | Dark Auction (?) |
| Silex | pick/drill/axe | Efficiency VI→X (+1 level each) | to X | Dwarven Mines |
| Bookworm's Favorite Book / Jalapeno Book / Thunder in a Bottle … | various | see collection/items files (?) | | |
| Book of Stats | weapons | adds a `Kills: N` counter line | 1 | Bazaar |
| Wither Scrolls (Implosion, Shadow Warp, Wither Shield) | Necron's Blade family (Hyperion/Astraea/Scylla/Valkyrie) | adds abilities; 3 together = Wither Impact | 3 | F7 chests |
| Power Scrolls (Ruby/Sapphire/Jasper/Amethyst/Amber/Opal/Onyx/Topaz power scroll) | AOTE/AOTV/Hyperion family (`can_have_power_scroll`) | teleport gives a short stat buff of that gem's stat (?) | 1 | Gemstone mining / crafted (?) |
| Attributes / Attribute Shards (2025 Hunting rework) | some items (`can_have_attributes`) | see hunting/attributes file | — | Hunting |
| Dyes, Skins, Runes | cosmetic | appearance / particles only | — | various |
| Hot/Fuming Potato Books, Recombobulator, Essence, Gems, Reforge stones | see §§13–17 | | | |

---

## 20. Accessories, Accessory Power (Magical Power), Powers, Tuning

### 20.1 Accessory Power (MP)

| Accessory rarity | MP | | Special case | MP |
|---|---|---|---|---|
| Common | 3 | | Special | 3 |
| Uncommon | 5 | | Very Special | 5 |
| Rare | 8 | | Ultimate | 1 |
| Epic | 12 | | Admin | 22 |
| Legendary | 16 | | Hegemony Artifact | counts its rarity's MP twice (?) |
| Mythic | 22 | | Abicase | +1 MP per 2 Abiphone contacts (?) |
| Divine | 28 | | Rift Prism | +11 MP (consumed) (?) |

- Only **one accessory per family** counts (upgrade lines like Talisman→Ring→Artifact→Relic): the highest owned tier; duplicates give 0.
- Dungeon accessories (e.g. from Catacombs) count **double inside dungeons** (wiki note).
- Each MP grants **1 SkyBlock XP** (SkyBlock Level file).
- Current max cited: **2,361 MP in dungeons across 147 accessories**; 66 accessories enrichable.
- Accessory Bag: slots grow with Redstone collection and Bag upgrades (see collections/economy file).

### 20.2 Stat multiplier

`multiplier = 29.97 × (ln(0.0019 × MP + 1))^1.2` — power stats = **power base × multiplier**. (Revealed in-game by the "Maths Enjoyer" NPC next to Maxwell.)

| MP | Multiplier | Tuning points (MP/10) |
|---|---|---|
| 0 | 0.00 | 0 |
| 50 | 1.68 | 5 |
| 100 | 3.67 | 10 |
| 150 | 5.70 | 15 |
| 200 | 7.70 | 20 |
| 250 | 9.64 | 25 |
| 300 | 11.53 | 30 |
| 400 | 15.12 | 40 |
| 500 | 18.46 | 50 |
| 600 | 21.59 | 60 |
| 700 | 24.52 | 70 |
| 800 | 27.27 | 80 |
| 900 | 29.86 | 90 |
| 1000 | 32.31 | 100 |
| 1200 | 36.85 | 120 |
| 1400 | 40.96 | 140 |
| 1600 | 44.73 | 160 |
| 1800 | 48.21 | 180 |
| 2000 | 51.44 | 200 |
| 2361 | 56.74 | 236 |

### 20.3 Powers (selected at **Maxwell**, Hub; one active)

Base stats are per 1.00 multiplier; the "at 250 MP" column is ×9.64 (rounded).

| Power | Tier / unlock | Base (×1) | At 250 MP | Unique bonus (flat, not scaled) |
|---|---|---|---|---|
| Fortuitous | Starter | 3.35 HP, 1.2 Def, 4.8 Str, 4.35 CC, 4.8 CD | 32 HP, 12 Def, 46 Str, 42 CC, 46 CD | – |
| Pretty | Starter | 1.65 HP, 1.2 Def, 0.65 Spd, 4.8 Str, 10.8 Int, 0.475 CC, 1.2 CD | 16/12/6/46/104/5/12 | – |
| Protected | Starter | 11.75 HP, 10.8 Def, 2.4 Str, 0.475 CC, 1.2 CD | 113/104/23/5/12 | – |
| Simple | Starter | 5.02 HP, 3.6 Def, 1.2 Spd, 3.6 Str, 5.4 Int, 1.45 CC, 3.6 CD | 48/35/12/35/52/14/35 | – |
| Warrior | Starter | 3.35 HP, 1.2 Def, 8.4 Str, 2.4 CC, 6 CD | 32/12/81/23/58 | – |
| Commando | Intermediate (Combat XV) | 5.02 HP, 2.4 Def, 8.4 Str, 0.475 CC, 8.4 CD | 48/23/81/5/81 | – |
| Disciplined | Intermediate (Combat XV) | 5.02 HP, 2.4 Def, 7.2 Str, 1.45 CC, 7.2 CD | 48/23/69/14/69 | – |
| Inspired | Intermediate (Combat XV) | 1.65 HP, 1.2 Def, 4.8 Str, 16.2 Int, 0.95 CC, 3.6 CD | 16/12/46/156/9/35 | – |
| Ominous | Intermediate (Combat XV) | 5.02 HP, 0.95 Spd, 3.6 Str, 6.1 Int, 1.45 CC, 3.6 CD, 0.9 AtkSpd | 48/9/35/59/14/35/9 | – |
| Prepared | Intermediate (Combat XV) | 12.4 HP, 11.3 Def, 1.95 Str, 0.4 CC, 0.95 CD | 120/109/19/4/9 | – |

**Stone powers** (unlocked permanently by feeding Maxwell a Power Stone (9 of the same stone for some (?))). Values below are the two headline stats
shown on the wiki at 250 MP, divided back to ×1; each power also has 2–4 minor stats not captured here (?).

| Power | Stone (rarity) | Combat req | Main stats (×1) | Unique bonus | Stone source |
|---|---|---|---|---|---|
| Forceful | Acacia Birdhouse (R) | XX | 18.0 Str, 4.8 CD | +4 Ferocity | craft 384 Ench. Acacia Log |
| Bloody | Beating Heart (R) | XX | 10.8 Str, 10.8 CD | +10 Attack Speed (?) | Catacombs blood-room mobs |
| Bubba | Bubba Blister (L) | XXV | 10.8 CD, 4.8 Str | +2 Combat Wisdom | Riftstalker Bloodfiend II+ |
| Shaded | Dark Orb (R) | XX | 18.0 CD, 4.8 Str | +3 Attack Speed, +3 Ferocity | F5 chests |
| Sanguisuge | Displaced Leech (U) | X | 14.4 Str, 4.8 CD (+HP, CC) | +100 Intelligence | craft (64 Ench. Bone + Hemoglass) |
| Bizarre | Eccentric Painting (L) | XXV | 43.2 Int | +5 Ability Damage | Dark Auction |
| Adept | End Stone Shulker (R) | XX | 16.8 HP, 9.6 Def (+Int) | +100 HP, +50 Def | Ender Nodes |
| Sighted | Ender Monocle (R) | XV | 36.0 Int | +3 Ability Damage | Seers / Watchers (End) |
| Crumbly | Fang-tastic Chocolate Chip (L) | XX | 10.1 HP, 5.4 Int (+TD, AtkSpd, CC after 2026) | +25 Speed | Riftstalker Bloodfiend |
| Itchy | Furball (R) | XX | 8.4 CD, 7.2 Str (+AtkSpd, Spd) | +15 Str, +15 CD | Sven Packmaster III+ |
| Frozen | Glacite Chunk (L) | XX | 13.2 Def, 1.84 TD (2026: 0.55 Def / 0.45 TD split) | +10 TD, +25 CD | Glacite Mineshaft mobs |
| Slender | Hazmat Enderman (L) | XXX | 9.0 Int, 8.4 HP (+more) | +50 Str, +100 Def | Voidgloom Seraph III+ |
| Demonic | Horns of Torment (E) | XXV | 27.7 Int, 5.5 Str | +50 CD | craft (Raw Mutton IX) |
| Silky | Luxurious Spool (U) | XV | 22.8 CD, 0.6 Spd | +5 Attack Speed | Arachne / Broodmother |
| Hurtful | Magma Urchin (E) | XXV | 19.2 CD, 4.8 Str | +15 Attack Speed | Crimson Isle minibosses |
| Strong | Mandraa (R) | XXX | 12.0 Str, 12.0 CD | +25 Str, +25 CD | Kuudra (Burning+) |
| Mythical | Obsidian Tablet (R) | XX | 6.1 Int, 5.7 HP (+more) | +40 Str, +150 HP | craft (Obsidian IX) |
| Pleasant | Precious Pearl (E) | XXV | 14.4 Def, 13.4 HP | (?) | Crystal Nucleus bundle |
| Sweet | Rock Candy (U) | XV | 15.1 HP, 10.8 Def | +5 Speed | Spooky Festival |
| Scorching | Scorched Books (M) | XXXV | 9.6 CD, 8.4 Str (+AtkSpd) | +7 Ferocity | Inferno Demonlord II+ |
| Buttery | Sunflower Butter (R) | XX | 2.4 Spd, 2.16 AtkSpd | +5 Attack Speed | crafted |
| Healthy | Vitamin Death (L) | XXX | 33.6 HP | +200 HP | Master Mode "Healthy" mobs |

Model behind the numbers (inferred from the June 2026 changelog — use it to fill the missing minor stats): every power splits a budget of **1.0** across stats
(e.g. new Frozen = 0.55 Def + 0.45 TD; new Crumbly = 0.3 HP + 0.15 Int + 0.4 TD + 0.1 AtkSpd + 0.05 CC; new Sanguisuge = 0.6 Str + 0.1 HP + 0.2 CD + 0.1 CC).
`stat = share × 24 × weight × multiplier` with weights ≈ Str 1, CD 1, Def 1, HP 1.4, Int 1.5, CC 0.4, **TD 0.17** (was 0.27 before June 2026), Speed ≈ 0.5 (?), AtkSpd ≈ 0.9 (?).
Check: Sanguisuge Str 0.6×24×1 = 14.4 ✓; Frozen Def 0.55×24 = 13.2 ✓; TD 0.45×24×0.17 = 1.84 ✓; Warrior HP 0.1×24×1.4 = 3.36 ✓.
Mending and Vitality are excluded from powers (June 2026).

### 20.4 Tuning
- **1 Tuning Point per 10 MP.** Spend at Maxwell → "Stats Tuning" on: Health, Defense, Speed, Strength, Crit Damage, Crit Chance, Attack Speed, Intelligence.
- Per point (?): +5 HP, +1 Def, +1.5 Speed, +1 Str, +1 CD, +0.2 CC, +0.3 Attack Speed, +2 Int.
- Templates: first free, more 10,000 Bits each (was 30,000).

---

## 21. Tooltip (lore) format — line order

Canonical order, top to bottom (blank lines shown as `·`):

| # | Line | Format | Notes |
|---|---|---|---|
| 1 | Name | `<rarityColour>[<Reforge> ]<Name>[ §6✪✪✪✪✪][§c➊…➎]` | Reforge prefix (e.g. "Withered", "Fabled", "Heroic") uses rarity colour; pets `§7[Lvl 100] §6Golden Dragon` |
| 2 | Subtitle (some) | `§8Breaking Power 9` / `§8Dungeon Pass` / `§8Hatcessory` | tools show Breaking Power here |
| 3 | Gear Score | `§7Gear Score: §d1,234 §8(5,678)` | dungeon items only; grey = in-dungeon value |
| 4 | Offensive stats | `§7Damage: §c+310 §e(+30) §8(+1,240)`<br>`§7Strength: §c+200 §e(+30) §9(+50) §d(+24)`<br>`§7Crit Chance: §c+10%` … | value colour `§c` for Damage, Strength, Crit Chance, Crit Damage, Bonus Attack Speed (?). Breakdown parentheses: **§e yellow = potato books**, **§9 blue = reforge** (older UIs `§9(Withered +135)`), **§d pink = gemstones**, **§8 grey = in-dungeon value**. Percent stats print `%` (`+50%`). |
| 5 | Defensive/utility stats | `§7Health: §a+200`, `§7Defense: §a+130`, `§7Speed: §a+3`, `§7Intelligence: §a+350`, `§7True Defense: §a+…`, `§7Magic Find: §a+…`, `§7Ferocity: §a+30`, `§7Mining Speed: §a+…`, `§7Sea Creature Chance: §c+…%`, … | value `§a` green. Ordering in-game (?): Damage, Strength, Crit Chance, Crit Damage, Bonus Attack Speed, Ability Damage, Health, Defense, Speed, Intelligence, Magic Find, Pet Luck, True Defense, Ferocity, Mining Speed, Pristine, Mining Fortune, Farming Fortune, Foraging Fortune, Sea Creature Chance, Fishing Speed, Health Regen, Vitality, Mending, Swing Range |
| 6 | Gem slots | ` §5[§c❤§5] §9[§d❁§9] §7[§8⚔§7]` | one bracket per slot; bracket colour = gem quality colour, symbol = gem symbol/colour; locked slot = grey brackets with slot-type symbol |
| 7 | · | | |
| 8 | Enchantments | ≤ ~5 enchants (?): one per line with grey description; more: comma-separated, ~3 per line (?). Normal `§9Sharpness VII`, ultimate `§d§lChimera V` listed first; enchants **above** their normal max shown `§6` (?) | |
| 9 | · | | |
| 10 | Item ability | `§6Ability: Wither Impact §e§lRIGHT CLICK` then grey description, `§8Mana Cost: §3150`, `§8Cooldown: §a5s` | Multiple abilities each block; full-set armor: `§6Full Set Bonus: Name §7(4/4)` |
| 11 | · | | |
| 12 | Reforge bonus | `§9Withered Bonus` + grey text | only reforges with abilities |
| 13 | · | | |
| 14 | Misc | `§7Enrichment: …` (?), `§7Kills: §a12,345` (Book of Stats), `§8§l* §8Co-op Soulbound §8§l*` | |
| 15 | Reforgeable notice | `§8This item can be reforged!` | only when item has no reforge yet (?) |
| 16 | Requirement (if unmet) | `§4❣ §cRequires §aCombat Skill 22§c.` / `§4❣ §cRequires §5Catacombs Floor VII Completion§c.` | |
| 17 | Rarity footer | `§6§lLEGENDARY DUNGEON SWORD` (recomb: `§d§l§ka§r §d§lMYTHIC DUNGEON SWORD §d§l§ka`) | |

Full example (5★, recombobulated, fully upgraded Hyperion — numbers illustrative):
```
§dHeroic Hyperion §6✪✪✪✪✪§c➎
§7Gear Score: §d1,105 §8(3,400)
§7Damage: §c+310 §e(+30) §8(+1,240)
§7Strength: §c+230 §e(+30) §9(+50) §8(+920)
§7Bonus Attack Speed: §c+10% §9(+10%)
§7Intelligence: §a+656 §9(+125) §d(+30) §8(+2,624)
§7Ferocity: §a+30 §8(+120)
 §6[§b✎§6] §6[§d❁§6]
 
§d§lUltimate Wise V§9, Champion X, Cleave VI
§9Critical VII, Cubism VI, Divine Gift III
§9Dragon Hunter V, Ender Slayer VII, Execute VI
...
 
§dScroll Abilities:
§6Ability: Wither Impact §e§lRIGHT CLICK
§7Teleport §a10 blocks§7 ahead of you. Then implode dealing
§c13,000 §7damage to nearby enemies. Also applies the wither
§7shield scroll ability reducing damage taken and granting an
§7absorption shield for §e5 §7seconds.
§8Mana Cost: §3150
 
§9Heroic Bonus
§7Grants §a+50 §7✎ Intelligence ...
 
§4❣ §cRequires §5Catacombs Floor VII Completion§c.
§d§l§ka§r §d§lMYTHIC DUNGEON SWORD §d§l§ka
```

---

## 22. How it connects

| This system | Fed by (inputs) | Feeds (outputs) |
|---|---|---|
| Stat aggregation (§3) | skills, SkyBlock level, fairy souls, slayers, bestiary, HOTM/HOTF, garden, pets, armor/equipment/weapon, accessory bag, potions, consumables | every formula in §§4–10; Profile menu; action bar |
| Combat skill | XP from kills (× Combat Wisdom) | +0.5 CC/level, Warrior +4%/1% additive damage, power unlocks (Combat XV/XX/XXV/XXX/XXXV), item requirements |
| Enchanting skill | Enchanting XP (table, Experimentation Table) | Int, Ability Damage %, enchant-level gates |
| Damage pipeline (§4) | player stats, weapon, enchants, pets, armor sets, mob tags (Undead/Arthropod/Ender/Wither/Cubic/Aquatic/Airborne/Infernal/Magmatic/Woodland/Skeletal/Mythological), mob Defense, boss cap | mob death → drops (MF, Looting, Luck), coins (Scavenger, Champion), XP (Wisdom), slayer/bestiary progress, ferocity sounds/indicators |
| Defense / EHP (§5) | armor, Protection/Growth, gems (Amethyst/Ruby/Opal), powers, tuning | survival → dungeon/slayer viability |
| Health Regen / Vitality (§6) | Vitality stat, Rejuvenate/Respite, healing items, classes | healing abilities, Vitality converters (Def/Str/AtkSpd) |
| Mana (§7) | Intelligence (reforges Wise/Necrotic/Smart, Sapphire, powers, Big Brain/Smarty Pants, Wisdom ult) | abilities (Hyperion, AOTE teleport, wands), Ultimate Wise/Mana Disintegrator reduce cost |
| Reforges (§13) | coins (Blacksmith), reforge stones (drops: Kuudra, slayers, dungeons, mining, bazaar), Mining level gates | item stats; reforge bonuses |
| Enchantments (§14) | XP levels, books (Bazaar, Dark Auction, slayers, dungeons, Kuudra, events), Enchanting skill | item stats & procs; damage multipliers |
| Potato books | Potato collection VIII craft, dungeon chests | +Damage/Str or +HP/Def |
| Recombobulator | dungeon chests, boss collections | +1 rarity → reforge/gem/MP/enrichment |
| Stars / essence | Essence from dungeons (Wither/Undead), dragons (Dragon), Kuudra (Crimson), Crystal Hollows (Gold/Diamond), Jerry (Ice), Arachne/Spider slayer (Spider), foraging/fossil, safari | +2%/star; dungeon +10%/star; Kuudra armor 10★ |
| Master stars | Master Mode M3–M7 chests | +5% each in Master Mode |
| Gemstones | Crystal Hollows / Glacite / Dwarven mining (Pristine upgrades quality), coins for slot unlocks | combat & gathering stats, scaled by item rarity |
| Enrichments | Bits (Booster Cookies → Community Shop) | small flat stats on Legendary+ accessories |
| Accessory bag & Power | accessories from every system (collections, slayers, dungeons, events, crafting, Dark Auction), Redstone collection for bag slots, Power Stones from drops, Combat level gates | big flat stats via multiplier; tuning; SkyBlock XP (1/MP) |
| Damage indicators / action bar | outputs of §4–7 | UX feedback |

Dependency order for building the clone (each depends on the ones above it):
1. Stat model + aggregator + rarity enum + item instance schema.
2. Damage pipeline (melee → bow → ability), mob defense/types/boss cap, true damage, action bar & indicators.
3. Health regen, mana, vitality, absorption.
4. Enchant engine (books, table, anvil, conflicts, procs) → reforges → potato books → recombobulator.
5. Gemstones + slots → essence stars → master stars.
6. Accessories, accessory power, Maxwell powers/tuning, enrichments.
7. Lore renderer that reads every modifier (§21) — build it early and use it as the debug view.

---

## 23. Implementation notes

### 23.1 Item instance schema (mirrors Hypixel's `ExtraAttributes` keys where known)

```jsonc
{
  "id": "HYPERION",                    // item definition id (API "id")
  "uuid": "b3c1…",                     // unstackables only (API has_uuid / unstackable)
  "timestamp": 1727830000000,
  "rarity_upgrades": 1,                // recombobulated (0/1)
  "modifier": "heroic",                // reforge id (lowercase)
  "hot_potato_count": 15,              // 0–10 hot, 11–15 = +fuming
  "upgrade_level": 10,                 // stars: 1–5 regular, 6–10 = master stars on dungeon items (older key: dungeon_item_level) (?)
  "enchantments": { "ultimate_wise": 5, "sharpness": 7, "champion": 10 },
  "gems": {                            // slot key = <SLOTTYPE>_<index>
    "unlocked_slots": ["SAPPHIRE_0", "COMBAT_0"],
    "SAPPHIRE_0": "PERFECT",
    "COMBAT_0": { "quality": "PERFECT", "uuid": "…" }, "COMBAT_0_gem": "JASPER"
  },
  "talisman_enrichment": "strength",   // accessories
  "art_of_war_count": 1,
  "artOfPeaceApplied": 1,
  "farming_for_dummies_count": 5,
  "mana_disintegrator_count": 10,
  "tuned_transmission": 4,
  "ability_scroll": ["IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL"],
  "power_ability_scroll": "SAPPHIRE_POWER_SCROLL",
  "dungeon_item": 1,
  "item_tier": 7,                      // floor tier the drop came from (tiered_stats index)
  "baseStatBoostPercentage": 50,       // dungeon quality roll 1–50
  "stats_book": 12345,                 // Book of Stats kill counter
  "champion_combat_xp": 1.2e8,         // level-by-use counters
  "hecatomb_s_runs": 100, "expertise_kills": 15000, "compact_blocks": 1e6,
  "farmed_cultivating": 1e9, "toxophilite_combat_xp": 0,
  "dye_item": "DYE_NECRON", "skin": "…", "runes": { "BLOOD": 3 },
  "originTag": "CRAFTING_GRID", "donated_museum": false
}
```
(Key names are Hypixel's as seen by mods; a few are from memory (?). The clone can rename freely — keep the *information*.)

### 23.2 Item definition schema (from the public API, extend as needed)

```jsonc
{
  "id": "HYPERION", "name": "Hyperion", "material": "IRON_SWORD", "tier": "LEGENDARY",
  "category": "SWORD",                        // drives reforge pool & enchant table & lore footer word
  "stats": { "DAMAGE": 260, "STRENGTH": 150, "FEROCITY": 30, "INTELLIGENCE": 350 },
  "tiered_stats": { "DAMAGE": [/* 10 values by dungeon floor tier */] },   // dungeon drops only
  "ability_damage_scaling": 0.3, "stats.WEAPON_ABILITY_DAMAGE": 10000,
  "upgrade_costs": [[{ "type": "ESSENCE", "essence_type": "WITHER", "amount": 150 }], …],
  "dungeon_item_conversion_cost": { "essence_type": "DRAGON", "amount": 150 },
  "prestige": { "item_id": "HOT_CRIMSON_CHESTPLATE", "costs": [ … ] },
  "gemstone_slots": [{ "slot_type": "SAPPHIRE", "costs": [{ "type": "COINS", "coins": 250000 }, { "type": "ITEM", "item_id": "FLAWLESS_SAPPHIRE_GEM", "amount": 4 }] }],
  "requirements": [{ "type": "DUNGEON_TIER", "dungeon_type": "CATACOMBS", "tier": 7 }],
  "catacombs_requirements": [{ "type": "DUNGEON_SKILL", "dungeon_type": "CATACOMBS", "level": 12 }],
  "dungeon_item": true, "gear_score": 100, "sword_type": "DAGGER",
  "can_recombobulate": true, "cannot_reforge": false, "can_have_power_scroll": true, "can_have_attributes": false,
  "npc_sell_price": 500000, "soulbound": "COOP", "museum": true
}
```
Stat key names in the API: `DAMAGE, STRENGTH, CRITICAL_CHANCE, CRITICAL_DAMAGE, ATTACK_SPEED, FEROCITY, INTELLIGENCE, HEALTH, DEFENSE, TRUE_DEFENSE, WALK_SPEED,
MAGIC_FIND, PET_LUCK, SEA_CREATURE_CHANCE, FISHING_SPEED, MINING_SPEED, MINING_FORTUNE, FARMING_FORTUNE, FORAGING_FORTUNE, BREAKING_POWER, PRISTINE, SWING_RANGE,
HEALTH_REGENERATION, VITALITY, MENDING, ABILITY_DAMAGE_PERCENT, WEAPON_ABILITY_DAMAGE, HEAT_RESISTANCE, COLD_RESISTANCE, FEAR, RIFT_TIME, RIFT_DAMAGE, RIFT_INTELLIGENCE,
GEMSTONE_FORTUNE, ORE_FORTUNE, BLOCK_FORTUNE, DWARVEN_METAL_FORTUNE, SWEEP, PULL, …` (newer items use lowercase keys like `health`, `sea_creature_chance`,
`bonus_pest_chance`, `trophy_fish_chance`, `treasure_chance`, `respiration`, `pressure_resistance`, `<crop>_fortune`, `<skill>_wisdom` — normalise case on import).

### 23.3 Stat engine
- Keep stats as a `Map<StatId, number>`; compute **once per change event** (equip/unequip, inventory change, pet swap, potion, level-up, area change)
  and cache. Re-evaluate conditionals (vs-mob-type, full HP, in dungeon, in Master Mode, day/night, weekday) at hit time, not in the cache.
- Separate **flat** stats from **conditional damage modifiers** (`{kind:"additive"|"multiplicative", value, condition}`) — the damage pipeline only reads the latter.
- Percent stats (CC, CD, AtkSpd, Ability Damage) are stored as plain numbers (50 = 50%).
- Display rounding: whole numbers for most stats; 1 decimal for CC/MF/Pet Luck/SCC/Pristine/Ferocity when fractional (enrichments give 0.3/0.5).
- Caps applied at read time, not at aggregation (so the Profile menu can show "Speed: 450 (capped at 400)" (?)).

### 23.4 Timers
| Timer | Period |
|---|---|
| Health regen tick | 1 s (wiki) — out of combat ×2 |
| Mana regen | every 1 s, 2% of max (show to action bar each tick) |
| Vitality regen | every 1 s, 5% of max |
| Action bar refresh | every 0.5–1 s and immediately after damage/ability |
| Damage indicator lifetime | ~1 s (?) |
| Ferocity stagger | ~0.1–0.25 s between strikes (?) |
| Combat flag | cleared when all aggro'd mobs are dead (June 2026); fallback 5 s without damage (?) |
| Thunderlord/Thunderbolt | every 3rd consecutive hit on the same target (?) |
| Lethality stack | 4 s |
| Counter-Strike | 7 s |
| Combo window | 2→10 s by level |
| Fatal Tempo | 3 s |
| Inferno | trap 5 s |
| Last Stand | 30 s cooldown |
| Refrigerate | 10 s |

### 23.5 Edge cases
- `CritChance ≥ 100` → no roll; excess only matters for Overload.
- Negative Speed/Int from reforges (e.g. Awkward −Int) can push totals below base; clamp Speed ≥ some floor (vanilla 0) and Mana ≥ 0.
- One For All: on apply, delete every non-ultimate enchant on the item; block applying normal enchants afterwards.
- Applying an ultimate replaces the existing ultimate (warn in GUI).
- Recombobulating changes the reforge row and gem column immediately; re-render lore.
- Fuming books require exactly 10 Hot first; reject otherwise.
- Master star N requires N−1 and 5 regular stars.
- Gem in a COMBAT/UNIVERSAL slot uses the gem's own stat table at the *item's* rarity.
- Accessory families: only the highest tier counts for stats **and** MP; inactive duplicates shown greyed in the bag.
- Dungeon accessories ×2 MP inside dungeons (wiki).
- Boss cap is per-hit, before Defense.
- Floor every damage number at the end, never in the middle.
- Sack/Bazaar strip `rarity_upgrades` from stackable items.

### 23.6 GUI surfaces to build
- **Reforge Anvil / Blacksmith**: item slot, "Reforge Item" button showing cost; stone slot variant; preview of resulting stats.
- **Anvil**: two inputs + output preview + XP cost; handles books, potato books, recomb, master stars, art of war/peace, one-off upgrades.
- **Enchantment Table**: list of eligible enchants with level buttons and XP costs; greyed if Enchanting level too low.
- **Essence upgrade menu**: shows each star's cost, current essence, preview.
- **Gemstone Grinder**: slots with unlock costs, applied gem, remove fee.
- **Maxwell**: Powers grid (locked/unlocked), stat preview at your MP; Tuning page with +/- per stat and remaining points; Templates.
- **Accessory Bag**: paged grid, MP total, family dedupe indicator.
- **Your SkyBlock Profile**: stat list + per-stat breakdown.

---

## 24. Progression placement

| Stage | Typical stats | What the player is doing with item modifiers |
|---|---|---|
| **Early** (SkyBlock lvl 0–60, Combat 0–15, ~0–10M coins) | 200–600 HP, 50–300 Def, 30–150 Str, 30–60 CC, 50–150 CD, 0–100 Int, 0–50 MP | Blacksmith random reforges (Sharp/Spicy/Legendary on swords, Pure/Fierce/Clean on armor); table enchants to V (Sharpness V, Protection V, Growth V); first Hot Potato Books from crafting; accessories mostly Common/Uncommon from collections → **0–150 MP**, Starter powers (Warrior / Pretty / Protected); first gemstones (Rough/Flawed) only on items with free slots |
| **Mid** (Combat 15–30, Catacombs 10–25, slayers 4–6, ~10–300M) | 1,000–2,500 HP, 300–800 Def, 200–500 Str, 60–100 CC, 200–500 CD, 300–1,000 Int, 300–900 MP | Reforge stones (Withered/Fabled/Spiritual/Ancient/Necrotic/Renowned/Giant), books VI (Sharpness/Critical/Protection/Growth VI), 10 Hot Potato Books on everything, 5★ on dungeon gear, Fine/Flawless gems with unlocked COMBAT slots, Intermediate powers (Combat XV) and first stone powers (Combat XX–XXV), Ultimate Wise/Soul Eater/Chimera I–III, first Recombobulators on weapon/chest |
| **Late** (Combat 50–60, Catacombs 35–50, slayer 7–9, Kuudra Infernal, ~1B+) | 3,000–6,000 HP, 800–1,500 Def (bersek/mage run lower), 700–1,500 Str, 100%+ CC, 600–1,500 CD (+Soul Eater), 1,000–3,000 Int (mage), 1,200–2,000+ MP | Recombobulated everything, 5 Fuming Potato Books, master stars ➊–➎, Perfect gems in every slot, top-tier book enchants (VII), max ultimates (Chimera V, One For All on specific builds), Kuudra armor 10★ with Heavy Pearls, enrichments on every Legendary+ accessory (≈ 65 × 5,000 Bits), best stone power for the build (e.g. Silky / Bloody / Sighted / Bizarre / Healthy), full tuning |

Pacing hooks:
- Combat level gates powers (XV intermediate; X–XXXV stones).
- MP growth is the long-tail grind (147 accessories, each family a mini-goal).
- Recombs/FPB/master stars are the coin sinks of late dungeons; essence the sink of mid dungeons.
- Gem slot unlocks (50k → 250k coins + flawless gems) are the mining → combat economy bridge.

---

## 25. Open questions / lowest-confidence items

1. **Stone-power minor stats**: only the two headline stats per stone power were recoverable; use the share×weight model in §20.3 to fill the rest.
2. **Tuning point values** per stat (+5 HP, +1 Def, +1.5 Speed, +1 Str, +1 CD, +0.2 CC, +0.3 AtkSpd, +2 Int) are from memory.
3. **Book tier sources** (where Sharpness VI/VII etc. come from) and enchanting-skill level gates.
4. Star **coin costs**: NEU says ★4 10,000 and ★5 25,000 (Kuudra ★6–10 up to 1M); the live API lists none (likely removed).
5. Special-case MP (Hegemony, Abicase, Rift Prism) and Kuudra 6–10★ colouring.
6. Exact lore enchant-block layout thresholds and stat line ordering.
7. Health regen tick interval (1 s per current wiki vs 2 s in older sources).
8. Some 2026 enchant numbers disagree between the changelog and the enchant table (Drain 0.1–0.7 vs 0.5–3; Vampirism 7–49 vs 5–35; Rejuvenate/Respite max V vs VII). The enchant table (wiki) was used in §14.

---

## 26. Sources

- Hypixel public API — `https://api.hypixel.net/v2/resources/skyblock/items` (5,655 items; stats, `upgrade_costs`, `gemstone_slots`, `tiered_stats`, reforge-stone tiers).
- NotEnoughUpdates-REPO `constants/reforges.json`, `reforgestones.json`, `gemstones.json`, `enchants.json`, `essencecosts.json`, `misc.json`, `bonuses.json`.
- Community wiki (hypixelskyblock.minecraft.wiki): Stats, Damage Calculation, Final Damage, Enchantments, Ultimate Enchantments, Reforging, Hot/Fuming Potato Book,
  Recombobulator 3000, Essence, First Master Star, Dungeon Item, Gemstone, Enrichments, Accessory Power, Powers, Power Stone, Maxwell, Rarity, Mana, Health Regen, Vitality,
  Changelog/2026/June 10.
- Hypixel forum: "List of Power Stones in SkyBlock"; "Hypixel SkyBlock 0.26.1 — Healing Revamp" patch notes.
