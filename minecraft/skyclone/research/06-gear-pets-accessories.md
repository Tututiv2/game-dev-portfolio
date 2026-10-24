# 06 — Gear, Pets & Accessories (the items themselves)

> Research reference for the SkyBlock clone. Original Hypixel names are used; renaming happens later.
> **Data provenance (read this first):**
> - **Stats, tiers, requirements, essence-upgrade costs, gemstone slots** come straight from Hypixel's public
>   keyless API `https://api.hypixel.net/v2/resources/skyblock/items` (pulled 2026-10-02, 5,655 items). These are
>   *current live values* and the most trustworthy numbers in this file.
> - **Ability text, set bonuses, recipes, forge timers, Kat upgrade costs, pet stat scaling and pet level curve**
>   come from the community NotEnoughUpdates item repository (`NotEnoughUpdates-REPO`, master, same date), which
>   mirrors the in-game lore of every item. Ability numbers quoted from lore are the *base* (un-scaled) values.
> - Accessory slots / Accessory Power / power stones came from the official wiki (hypixelskyblock.minecraft.wiki).
> - Anything from memory that could not be verified is marked **(?)**. Do not "fix" a (?) by guessing — check the
>   wiki or the API when you implement it.
> - Note: in 2025 Hypixel renamed **Magical Power → Accessory Power** in item lore. Both names mean the same stat
>   in this document.

Stat abbreviations used in every table:

| Abbrev | Stat | Abbrev | Stat | Abbrev | Stat |
|---|---|---|---|---|---|
| HP | Health | Def | Defense | TrueDef | True Defense |
| Str | Strength | Dmg | weapon Damage | CC / CD | Crit Chance / Crit Damage (%) |
| Int | Intelligence (= +1 max mana each) | AtkSpd | Bonus Attack Speed (%) | Fer | Ferocity (% chance of extra hit) |
| Spd | Speed | MF | Magic Find | AbilDmg% | Ability Damage % |
| AbilDmg(base) | the base damage number of a weapon's ability (API field `WEAPON_ABILITY_DAMAGE`) | Vit / Mend | Vitality / Mending | Reach | Swing Range |
| MinSpd / MinFort | Mining Speed / Mining Fortune | BP | Breaking Power | GemFort | Gemstone Fortune |
| FarmFort / ForFort | Farming / Foraging Fortune | SCC | Sea Creature Chance | FishSpd | Fishing Speed |
| Sweep | foraging "logs per swing" stat (2025 foraging update) | HeatRes/ColdRes/PressRes | zone resistances | PestCh | Bonus Pest Chance |

Rarity letters: **C** Common, **U** Uncommon, **R** Rare, **E** Epic, **L** Legendary, **M** Mythic, **D** Divine,
**S** Special, **VS** Very Special. `?` in a tier column = the API leaves vanilla-ish items untiered (treat as Common).

Base player stats with nothing equipped (NEU constants): **HP 100, Def 0, Str 0, Speed 100, CC 30%, CD 50%,
Int 0 (mana pool = 100 + Int), AtkSpd 0, Fer 0, SCC 20%, MF 0 (?)**. Speed cap 400 by default (500 with Black Cat /
Young Dragon / Cactus Knife etc. which raise the cap).

---

## 1. Overview — what an "item" is

Every piece of gear in SkyBlock is the same data object with optional layers stacked on top. A build agent should
model these layers explicitly, because almost every progression system in the game is "add another layer to an
item you already own":

| Layer | What it adds | Where it comes from | Applies to |
|---|---|---|---|
| Base item | category, rarity, base stats, ability, set membership, requirements | crafted / dropped / bought | all |
| Rarity upgrade (Recombobulator 3000) | +1 rarity tier, once per item → bigger reforge stats and more Accessory Power | rare drop / Bits shop | armor, weapons, tools, accessories, equipment |
| Reforge | a stat bundle that scales by rarity (+ sometimes an ability) | Blacksmith (basic) or Reforge Anvil + reforge stone | armor, weapons, tools, accessories, equipment |
| Enchantments | per-enchant effects, incl. one Ultimate enchant | Enchanting table, books, anvil | armor, weapons, tools |
| Hot Potato Books (10) + Fuming Potato Books (5 more) | weapon **+2 Dmg +2 Str** per book; armor **+2 Def +4 HP** per book (max 15 total) | crafted (3 paper + ench. baked potato) / Fuming from Dungeons | armor, weapons |
| Gemstones | stat per gem per slot, slot-type locked (Ruby=HP, Jasper=Str, Sapphire=Int, Amethyst=Def, Topaz=Pristine, Jade/Amber=Mining, Onyx=CD, Peridot=Fortune/ForFort, Opal=TrueDef, Citrine/Aquamarine etc.) | Crystal Hollows / Glacite gems; slots unlocked with coins + gems (cost listed per item in the API) | many end-game items |
| Dungeon stars (1–5) & Master stars (6–10) | each star +10% of the item's *dungeon-boosted* stats (?) ; master stars +5% each (?) | Essence (amount per star in API `upgrade_costs`) / Master Stars from Master Mode | dungeon & Kuudra items |
| Kuudra armor tier | Basic → Hot → Burning → Fiery → Infernal: base stats ×~1.26/1.58/2.0/2.52 (see §3.6) | Kuudra Essence + Heavy Pearls; Kuudra tier completion | Crimson Isle Kuudra armor |
| Art of War / Art of Peace | +5 Str on a weapon / +40 HP on armor (?) | rare books | weapon / armor |
| Scrolls (Necron's Blade family) | Implosion, Shadow Warp, Wither Shield → combine into Wither Impact | Master Mode 7 | Hyperion/Valkyrie/Astraea/Scylla |
| Enrichment | +1 fixed stat on a Legendary/Mythic accessory | Rift (Motes) | accessories |
| Attributes / Shards (2025 Hunting update) | per-shard passive bonuses (huge list: `ATTRIBUTE_SHARD_*` in NEU repo) | Hunting with Huntaxes / Fusion | player-level, not per item (?) |

Item categories present in the live API (`category` field) and their counts: ACCESSORY 423, HELMET 269,
CHESTPLATE 191, LEGGINGS 183, BOOTS 191, SWORD 156, LONGSWORD 6, BOW 44, ARROW 12, WAND 25, PICKAXE 24, DRILL 11,
AXE 29, FARMING_TOOL 48, FISHING_ROD 25, FISHING_ROD_PART 18, PET_ITEM 95, NECKLACE 37, CLOAK 34, BELT 41,
GLOVES 25, BRACELET 10, GAUNTLET 1, TRAVEL_SCROLL 40, SACK 67, DEPLOYABLE 18, REFORGE_STONE 108, VACUUM 5,
WATERING_CAN 5, CHISEL 4, SPADE 11. Weapon sub-types (`sword_type`): DAGGER, KATANA, SCYTHE, KARAMBIT.

---

## 2. The gear progression path

What a "typical" player wears and wields from a fresh profile to endgame. Coin ranges are rough 2025 economy
values and only indicate relative cost. "Req" = the hard requirement on the item itself.

### 2.1 Combat — by playstyle

| Stage (rough SB level / Cata / coins) | Melee (Berserk) | Mage | Archer | Tank |
|---|---|---|---|---|
| **0. Fresh profile** (SB 0–10, <1M) | Wooden/Stone → **Rogue Sword** (+100 Spd ability) → **Undead Sword** / **Cleaver** / **Prismarine Blade**; armor **Leaflet**, **Farm Suit**, **Mushroom**, **Golem**, **Hardened Diamond**, **Lapis**; free talismans | **Celeste Armor** (Combat 4) + **Celeste Wand** → **Starlight Armor/Wand** (Combat 8) | Bow → **Savanna Bow** (all damage ×2) / **Prismarine Bow** / **Ender Bow** | **Golem Armor**, **Hardened Diamond**, **Emerald Armor** (+1 HP/Def per 3,000 emeralds, max 350) |
| **1. Early** (SB 10–60, Combat 10–20, 1–20M) | **Raider Axe**/**Tactician's Sword** → **Aspect of the End** (also the movement item) → **Revenant Falchion** (Zombie 3) / **Recluse Fang**; armor **Ender** (×2 in End), **Young/Unstable/Strong/Old Dragon**, **Revenant**, **Tarantula**, **Mercenary**, **Squire**; pet Tiger/Wolf/Lion/Hound | **Wise Dragon** (2/3 mana cost) + **Aspect of the Dragons** (12,000 dmg cone) or **Ink Wand**/**Golem Sword**; pet Sheep | **Hurricane Bow** (up to 5 arrows) → **Runaan's Bow** (3 arrows) → **Spider/Mosquito Shortbow** later; **Skeleton Master** from F1–F3; pet Skeleton/Black Cat | **Protector / Holy Dragon**, **Rampart**, **Mastiff** (Wolf 4) ; pet Golem/Turtle |
| **2. Dungeons F1–F5** (Cata 5–25, 20–200M) | **Adaptive Blade** (F2) → **Shadow Fury** / **Livid Dagger** (F5) + **Shadow Assassin** set (F5); **Superior Dragon** for general; **Giant's Sword** (F6, 100k AoE); **Flower of Truth** (F6) | **Bonzo's Staff** (F1) → **Spirit Sceptre** (F4) → **Midas Staff** / **Yeti Sword**; **Bonzo's Mask** / **Spirit Mask** helmet; **Wither Goggles** (F6) + Storm armor later | **Bonemerang** (F4) / **Spirit Shortbow** (F4) → **Last Breath** (F5) → **Juju Shortbow** (Enderman 5, Cata 15); **Skeleton Master/Lord** set | **Adaptive**/**Zombie Knight**/**Super Heavy** early, **Shadow Assassin** or **Frozen Blaze** mid |
| **3. Late** (F7, Kuudra Burning–Fiery, T4 slayers; 200M–2B) | **Hyperion/Valkyrie** with Wither Impact or **Necron's Blade**; **Necron's Armor**; vs. Endermen **Atomsplit Katana**, vs. Blazes daggers (**Pyrochaos/Deathripper**), vs. Revenant **Halberd of the Shredded**; Kuudra: **Crimson** armor tiers | **Hyperion** (Wither Impact spam), **Storm's Armor** + **Wither Goggles**; Kuudra **Aurora** armor + Aurora Staff / **Hollow Wand**; **Fire Fury/Fire Veil** | **Terminator** (Enderman 7, Cata 27) in **Necron/Maxor** or **Terror** armor | **Goldor's Armor** + **Astraea**; Kuudra **Fervor** armor; Wither Cloak Sword |
| **4. Endgame** (Master Mode M7, Infernal Kuudra, 2B+) | 10-star (5 master stars) Hyperion/Valkyrie, Infernal Crimson, **Golden Dragon** Lv200, Hegemony + 1,500+ Accessory Power | Infernal Aurora / 10-star Storm, Hyperion, Golden Dragon or Parrot | 10-star Terminator, Infernal Terror / Maxor, Ender Dragon or Golden Dragon | Infernal Fervor / 10-star Goldor |

Typical **pet** per role across the same stages: melee Tiger → Enderman/Lion → **Ender Dragon** → **Golden Dragon**;
mage Sheep → Guardian/Parrot → Golden Dragon (Int build) ; archer Skeleton/Black Cat → Ender Dragon; tank
Golem → Turtle → Ankylosaurus; utility **Black Cat** (Speed + MF), **Spirit** (dungeon ghost), **Griffin**
(Diana event), **Blaze** (Blaze armor ×1.4).

### 2.2 Skilling paths (non-combat)

| Skill | Early | Mid | Late / end |
|---|---|---|---|
| Mining | Rookie/Stone/Iron → **Lapis Pickaxe** (+25% double XP) / **Zombie Pickaxe**; **Miner's Outfit** (+40 MinSpd), **Lapis Armor** (+50% mining XP per piece) | **Mithril Pickaxe** (HotM 2) → **Mithril Drill SX-R226** → **Titanium Drill DR-X355…655** with **Goblin**/**Glacite**/**Mineral** armor; **Mithril Golem** pet | **Divan's Drill** (HotM 7, 1,800 MinSpd 150 MinFort) / **Gemstone (Topaz/Jasper) drills** with **Amber-Polished Engine** + **Perfectly-Cut Fuel Tank**; **Divan's** or **Sorrow** armor; Scatha / Bal / Glacite Golem pets |
| Farming | Rookie/Iron Hoe, **Farm Suit** → **Farm Armor** / **Farmhand** (+20 FarmFort set); **Rabbit** / **Bee** pet | **Melon** (Farming 20) → **Cropie** (30) → **Squash** (35); **Theoretical hoes Mk I/II** (Garden 1–12); **Elephant** (+150 FarmFort Lv100 L) | **Fermento** (Farming 40, +130 FarmFort set) ; hoes **Mk III**; **Mooshroom Cow** / **Elephant** Mythic; Fermento Artifact; Rancher's Boots |
| Fishing | Fishing Rod → **Challenging Rod** → **Rod of Champions**; **Angler** / **Backwater** armor; **Squid** pet | **Rod of Legends** → **Rod of the Sea**; **Sponge** / **Shark Scale** / **Diver's**; Blue Whale / Flying Fish | Lava: **Magma Rod → Inferno → Hellfire Rod**, **Thunder** (Fishing 36) → **Magma Lord** (Fishing 45); **Abyssal**; pets Ammonite / Flying Fish (Mythic) / Megalodon; rod parts Titan Line, Hotspot Hook |
| Foraging | **Rookie Axe** → **Spruce (Jungle) Axe** → **Treecapitator**; **Leaflet** | **Canopy** armor (Foraging 12); **Fig Hew** → **Figstone Splitter** (Galatea) | **Helix Chopper** (Foraging 20, Sweep 50 + up to 40); Monkey / Giraffe / Ocelot pets |

---

## 3. Armor

### 3.1 Rules every armor set shares

- 4 slots: Helmet, Chestplate, Leggings, Boots. Piece share of a set's stats is almost always
  **H ≈ 22%, C ≈ 33%, L ≈ 28%, B ≈ 17%** (e.g. Necron Def 100/140/125/85).
- Set bonuses have three shapes in the current game — model all three:
  - **Full Set Bonus (n/4)** — only active with all pieces (some sets are 3-piece: Tuxedos, Revenant, Tarantula).
  - **Tiered Bonus (k/4)** — scales with how many pieces are worn (Kuudra armor, farming armor, fishing armor).
  - **Piece Bonus** — each piece independently (Lapis "+50% mining XP", Shadow Assassin "Pursuit", slayer "Bulwark").
- "**2× on island**" sets: Ender (End), Nutcracker/Snow Suit (Jerry's Workshop), Rampart (Crimson Isle extra stats),
  Mithril Golem pet-like conditionals. Implement as a stat multiplier conditioned on `location`.
- Dungeon armor has a **"Dungeon Stat Boost"**: stats inside the Catacombs scale with Catacombs level / floor (?),
  plus stars. Items with `tiered_stats` in the API (e.g. Rotten, Heavy, Skeleton Master) roll a quality between
  the min and max shown (`70-152` = the stat at the lowest vs highest quality roll).

### 3.2 Curated key sets (full stats from the live API)

Columns: per-piece stats are listed H / C / L / B; the set total is what you get with all four.

#### Early game

| Set | Tier | Req | Set total | Per piece (H / C / L / B) | Set bonus | Source |
|---|---|---|---|---|---|---|
| Leaflet | C | — | HP 195, ForFort 12 | HP 70,ForF 3 / HP 80,ForF 3 / HP 20,ForF 3 / HP 25,ForF 3 | none (2025: +ForFort) | craft from Oak Leaves (Foraging collection) |
| Farm Suit | C | Farming 3 (?) | low Def + FarmFort (?) | — | tip lore only | Hub Farm merchant / craft |
| Farmhand | C | Farming 3 | Def 40, FarmFort 20 | each Def 10, FarmFort 5 | — | Garden/early farming |
| Mushroom | C | Farming 7 | HP 55, Def 15 | HP 20 / Def10 HP10 / Def5 HP10 / HP15 | **Night Affinity**: permanent Night Vision | craft from mushrooms |
| Cactus | C | Farming 5 | Def 65, HP 35 | 10/5 · 25/15 · 20/10 · 10/5 | **Deflect**: rebound 33% of damage taken | 5/8/7/4 Cactus |
| Miner's Outfit | U | — | Def 60, MinSpd 40 | Def 15, MinSpd 10 each | **Beginner's Boost**: +40 MinSpd | Gold Mine/Deep Caverns |
| Lapis Armor | U | Mining 5 | Def 120, MinSpd 80, MinFort 8 | 25/40/35/20 Def; 20 MinSpd, 2 MinFort each | **Magnetic** (piece): +50% XP when mining | Lapis Zombies, Deep Caverns |
| Golem Armor | R | — | Def 250, HP 205 | Def45 HP45 / Def90 HP65 / Def75 HP55 / Def40 HP40 | **Absorption**: Absorption III for 20s on kill | Iron collection recipe |
| Hardened Diamond | R | — | Def 330 | 60 / 120 / 95 / 55 | none | Enchanted Diamond collection |
| Emerald Armor | E | — | Def 270 | 50 / 100 / 75 / 45 | **Tank**: +1 HP & +1 Def per 3,000 Emerald collection (max +350 each) | 5/8/7/4 Ench. Emerald Block |
| Speedster | U | — | Def 210, Spd 60 | 45/70/60/35 Def, 15 Spd each | **Bonus Speed**: +20 Spd | Sugar Cane collection |
| Celeste | U | Combat 4 | Def 70, HP 70, Int 150 | Int 30/60/40/20 | none | Hub mage shop / Wizard |
| Squire | U | Combat 4 | Def 115, HP 115, Str 20 | — | none | Hub weaponsmith shop |
| Starlight | R | Combat 8 | Def 110, HP 110, Int 225 | Int 45/90/60/30 | **Starpower**: Starfall duration ×2, +1 range | shop |
| Mercenary | R | Combat 8 | Def 200, HP 200, Str 40 | — | **Death Tax**: +5 coins and heal 20 HP per kill (lvl 10+ mobs) | shop |
| Ember | E | Combat 8 | Def 180, HP 200, Int 25 | — | **Nether Lord**: immune to lava/fire, lava → obsidian under you | 5/8/7/4 Ember Fragment |
| Spooky | E | — | Def 135 | 25/50/35/25 | **Candy Man**: +5% rare candy | Spooky Festival |
| Crystal | E | — | Def 100, Int 345 | — | **Refraction**: stats 0–200% by light level | Crystal Fragments (Dragon's Nest) |
| Armor of Magma | E | — | Def 85, HP 270 | — | **Absorb**: +1 HP and Int per 10 Magma Cubes killed (max 200 each) | Magma Cube drops |
| Fairy's Outfit | R | — | Spd 40 (+1 HP/Def tiny) | — | **Fairy's Outfit**: +1 HP per Fairy Soul | Fairy Souls (?) |

#### End / Dragon armor (Dragon's Nest)

All dragon sets: Legendary, drop from the matching Ender Dragon (Young/Old/Strong/Unstable/Wise/Superior/Protector/Holy)
or crafted from 5/8/7/4 of that dragon's fragments (10 fragments / piece shape: helmet 5, chest 8, legs 7, boots 4
on the craft grid). Requirements listed below are the item requirement (Combat X + Catacombs Y).

| Set | Req | Set total | Per piece (H / C / L / B) | Full set bonus |
|---|---|---|---|---|
| Young Dragon | Combat 16, Cata 10 | Spd 80, Def 500, HP 350 | Def 110/160/140/90, HP 70/120/100/60, Spd 20 each | **Young Blood**: +70 Speed while above 50% HP; +100 speed cap |
| Old Dragon | Combat 16, Cata 10 | Def 500, HP 450 | Def 110/160/140/90, HP 90/150/130/80 | **Old Blood**: strengthens Growth, Protection, Feather Falling, Sugar Rush, True Protection enchants (×?) |
| Unstable Dragon | Combat 16, Cata 10 | Def 500, HP 350, CD 60, CC 20, Int 25 | CD 15 + CC 5 per piece; helmet +25 Int | **Unstable Blood**: sometimes strikes nearby mobs with lightning |
| Strong Dragon | Combat 16, Cata 12 | Def 500, HP 350, Str 100 | Str 25 per piece | **Strong Blood**: AOTE +75 Dmg; Instant Transmission +2 range, +3 s, +5 Str per cast |
| Wise Dragon | Combat 16, Cata 12 | Def 500, HP 350, Int 350 | Int 125/75/75/75 | **Wise Blood**: abilities cost 2/3 mana |
| Protector Dragon | Combat 16, Cata 12 | Def 600, HP 350 | Def 135/185/165/115 | **Protective Blood**: each piece +1% Def per 1% missing HP |
| Holy Dragon | Combat 16, Cata 10 | Def 500, HP 545, Mend 40 | Mend 10 each | **Holy Blood**: +75 Health Regen to you and players within 10 blocks (once) |
| Superior Dragon | Combat 20, Cata 14 | Spd 12, Def 600, HP 450, Str 40, CD 40, CC 8, Int 100 | per piece Str 10 CD 10 CC 2 Int 25 Spd 3 | **Superior Blood**: +5% all Combat stats & MF; AOTD ability +50% damage |
| Ender | Combat 12 | Def 170, HP 90 | Def 35/60/50/25, HP 20/30/25/15 | **all stats ×2 on the End island** (piece effect) |
| Final Destination | Enderman Slayer 3 | Def 400, HP 600, Int 400 | Def 100 each; HP 140/200/160/100; Int 100 each | **Vivacious Darkness** (sneaking): +30 Str, +20 AtkSpd, +10 Spd, Int ×1.25, +200 Fer & +100% dmg vs Endermen; **Enderman Bulwark** piece bonus (+20 per 100 Endermen kills) |

#### Slayer armor

| Set | Req | Set total | Per piece | Bonus |
|---|---|---|---|---|
| Revenant (3 pcs: C/L/B) | Zombie Slayer 4 | Def 150, HP 400 | C Def70 HP180 / L Def50 HP120 / B Def30 HP100 | **Trolling the Reaper** (3/3): healing wands +50%, +100 Def vs Undead; **Zombie Bulwark** +20 Def vs zombies per 50 kills per piece |
| Reaper | Zombie Slayer 7, Cata 24 | Def 250, HP 550, Int 850, Str 75, Vit 60, Mend 60 | Reaper Mask H (Vit/Mend 60, Int 100) + C/L/B Int 250 Str 25 | **Enrage** (SNEAK): +100 Spd, +100 Dmg, +100 Str for 6s, 25s CD; plus Trolling the Reaper (+100% vs Undead but 1% to others) |
| Tarantula (H/C/L + Tarantula Boots) | Spider Slayer 4, Cata 16 | Def 270, HP 325, Int 260 (+boots Spd 5, Def 70, HP 70, Int 50) | — | **Octodexterity**: every 4th strike +100% dmg and Venom (−40% healing, 4s); **Spider Bulwark**; **Radioactive**: +1 CD per 10 Str (max 1,000 Str) |
| Mastiff | Wolf Slayer 4 | HP 1,400, Int 150, CD 60 | HP 300/500/400/200; CD 15 each | **Absolute Unit**: −20% dmg from Animals; +1% HP per 25 CD (max 40%); +1 CD per 2 Def; heal 150 HP when hit (−5 Vit, 1s CD); **Def capped at 300** |
| Armor of the Pack | Wolf Slayer 6 | — | — | **Armor of the Pack**: +35 Str & +80 Def per other wearer within 30 blocks (max 3) |
| Final Destination | Enderman Slayer 3 | see End table | | |
| Blaze / Frozen Blaze | Combat 22 (Frozen: + Cata 20) | Blaze: Spd 8, Def 465, Str 140 (incl. 2 helmet variants); Frozen: Spd 8, Def 520, Str 160 | — | **Blazing Aura**: 3% of mobs' max HP/s within 5 blocks (max 500 dmg/s, +100 per 5,000 Blaze Rod collection). Frozen adds 300 base dmg + Slowness I |

#### Dungeon armor (Catacombs)

All are **dungeon items** (stars with essence). Requirements are "Floor N completion" (`F7 done`) or Catacombs level.

| Set | Req | Set total | Per piece (H / C / L / B) | Bonus | Drop |
|---|---|---|---|---|---|
| Rotten (tiered) | Cata 1 | HP 365–792, Str 55–120 | rolls | **Sieve Body**: +20% knockback resist vs arrows | Entrance/F1 |
| Heavy / Super Heavy | Cata 2 / 15 | Def up to 640 / 880; Spd −20 / −40 | rolls | **Vindicate**: +1 Speed per 50 Def | F1–F3 |
| Skeleton Grunt / Soldier / Master / Lord | Cata 1/9/12/25 | Master: CD 88–180, CC 8–16, Def/HP rolls | rolls | arrow dmg +5% (piece) / +25% / +25% ; Lord: +5 Str & +10 CD per minute in dungeon | F1–F7 |
| Zombie Soldier / Knight / Commander / Lord | Cata 9/14/18/25 | rolls | rolls | Knight: +50 Def with Zombie Knight Sword; Commander: +5 HP per 50 zombies (max 500); Lord: +10 Str +10 Def per minute | F2–F7 |
| Adaptive | F3 | Def 245–285, HP 515–555, Str 60, Int 60 (2 variants) | Str 15 Int 15 each | **Efficient Training**: +2% stats per 5 Cata levels; class bonus — Berserk +20 Str; Healer +5 Mend +40 HP; Mage +50 Int; Tank +30 Def & −5%/piece dmg from repeat attacker; Archer +5 CC +15 CD | F3 (Professor) |
| Shadow Assassin | F5 | Spd 28–32, Def 330, HP 735–775, Str 100–120, CD 100 | Str 25–30 & CD 25 each | **Pursuit** (piece): invisibility + 20 Spd 10s on teleport (3s CD); **Shadow Assassin**: +1 Str per mob killed in the run | F5 (Livid) |
| Necron's (POWER_WITHER) | F7 | Def 450, HP 815, Str 160, CD 120, Int 60 | Def 100/140/125/85, HP 180/260/230/145, Str 40, CD 30 each | **Witherborn**: spawns a wither minion every 30s (max 1) that explodes on enemies (all 5 Wither sets share this) | F7 / M7 |
| Storm's (WISE_WITHER) | F7 | Def 370, HP 815, Int 1,150 | Int 400/250/250/250 | Witherborn | F7 |
| Goldor's (TANK_WITHER) | F7 | Def 860, HP 970, Int 90 | Def 180/275/240/165 | Witherborn | F7 |
| Maxor's (SPEED_WITHER) | F7 | Spd 120, Def 370, HP 815, CD 180, Int 45 | Spd 30 & CD 45 each | Witherborn | F7 |
| Wither (plain) | F7 | Def 720, HP 930, Int 60 | — | Witherborn | F7 |
| Werewolf | Cata 20 | Def 700, Fer 40, Int 200 | Fer 10, Int 50 each | **Regenerative Howl**: on Ferocity proc +50 Def for 5s (10 stacks) | Wolf slayer drops (?) |
| Perfect (Tier I–XIII) | Cata 7–21 | Def only, e.g. Tier XIII set Def 1,460 | +20 Def per tier | none | Crimson/Dwarven? Forge (?) |
| Crypt Witherlord | Cata 18 | Spd 15, Def 280 | — | −5% dmg from Withers | F-floor drop |
| Spirit Boots / Spirit Mask | F4 / Cata 15 | Boots Spd 25–35, Def 50, HP 150, Str 25–35, Int 50; Mask Spd 15–25, Def 50–75, HP 100, Int 20–25 | — | Mask **Second Wind**: on death gain +50 Spd and immunity 3s, 30s CD | F4 Thorn |
| Bonzo's Mask | F1 | Def 100, HP 100–125, Int 100–150 | — | **Clownin' Around**: on death immunity + 20 Str 3s and full heal (Catacombs only), CD 360s scaling with Cata | F1 |
| Wither Goggles | F6 | Int 300, AbilDmg% +45 | — | — | F6 (?) |
| Precursor Eye | F6 | Def 222, HP 222, Int 222 | — | **Eye Beam** (SNEAK): 4,000 dmg laser, +100%/s up to 5s, 40 mana +25%/s | F6 |
| Warden Helmet | Zombie Slayer 8 | Def 100, HP 300 | — | **Brute Force**: halves Speed, +20% base weapon dmg per 25 Speed | Revenant T5 |

Dungeon star essence costs (live API `upgrade_costs`) — examples; every dungeon item lists its own:

| Item | ★1 | ★2 | ★3 | ★4 | ★5 | Essence |
|---|---|---|---|---|---|---|
| Hyperion / Valkyrie / Scylla / Astraea / Necron armor pieces | 150 | 300 | 500 | 900 | 1,500 (+10k/25k coins at ★4/★5 for blades) | Wither |
| Terminator | 100 | 200 | 300 | 500 | 750 | Dragon |
| Skeleton Lord chestplate | 25 | 50 | 100 | 200 | 400 | Undead |

Master Stars 1st–5th (★6–★10) are separate consumable items from Master Mode chests (M1–M5 (?)), applied
in order.

#### Crimson Isle / Kuudra armor (5 tiers each)

Four Kuudra sets (Crimson, Aurora, Terror, Fervor, Hollow — Hollow is the 5th "support" set) each exist in
**Basic (Kuudra None), Hot, Burning, Fiery, Infernal**. Requirement: Combat 22 / 27 / 32 / 37 / 42 + matching Kuudra
tier completion. Upgrading costs Kuudra Teeth/Essence + Heavy Pearls (?) at the Anvil. All are dungeon-style
(star with Crimson Essence).

| Set | Basic total | Hot | Burning | Fiery | Infernal | Bonus (Basic → Infernal) |
|---|---|---|---|---|---|---|
| **Crimson** (melee) | Def 210, HP 725, Str 120, CD 80, Int 30 | Def 264 HP 914 Str 152 CD 100 | Def 333 HP 1,150 Str 192 CD 128 | Def 420 HP 1,450 Str 240 CD 160 | Def 530 HP 1,828 Str 304 CD 200 | **Dominus** (tiered): every 1.5s melee hit = 1 stack (decay 1/4s); each stack +0.05 Reach (Burning: +0.1 Reach +1 Fer); at 10 stacks a swipe for 0.5× (→0.75× …) Swipe Damage |
| **Aurora** (mage) | Def 210, HP 725, Int 800, AbilDmg% 16 (helmet) | Int 1,010, AbilDmg 20 | Int 1,271, AbilDmg 25 | Int 1,600, AbilDmg 32 | Int 2,016, AbilDmg 40 | **Arcane Energy**: 1 stack/s while dealing magic dmg (decay 1/4s); at 10 stacks SNEAK → 3 (Burning: 4) homing missiles × 5,000 dmg |
| **Terror** (archer) | Spd 48, Def 210, HP 725, CD 200 | Spd 60 CD 252 | Spd 76 CD 316 | Spd 96 CD 400 | Spd 120 CD 504 | **Hydra Strike**: 1 stack / 0.2s of arrow hits; each stack +2% dmg +1% arrow speed (Burning +3.5%/+2%); at 10 stacks shoot +2 arrows at 20% (Burning 40%) dmg |
| **Fervor** (tank) | Def 440, HP 815, TrueDef 20 | Def 554 HP 1,028 TD 24 | Def 699 HP 1,294 TD 32 | Def 880 HP 1,630 TD 40 | Def 1,109 HP 2,054 TD 48 | **Intimidate**: on attack 50% chance to pull aggro from 3 mobs |
| **Hollow** (support) | Spd 20, Def 230, HP 800, Int 385, Mend 20 | ×1.26 | ×1.58 | ×2.0 | Def 561 HP 1,952 Int 970 Mend 48 | **Spirit**: nearby players' attacks grant stacks (max 10/20) spent by the **Hollow Wand** |

Rule of thumb verified from the API numbers: Hot ≈ Basic × 1.26, Burning × 1.585, Fiery × 2.0, Infernal × 2.52.

#### Mining armor

| Set | Tier | Req | Set total | Bonus |
|---|---|---|---|---|
| Goblin | R | — | HP 395, MinSpd 40, MinFort 16, Int −4 | **Smart Miner**: Int → MinSpd (+1 per 15 Int removed) |
| Heat | R | Hard Stone coll 2 | Def 200, HP 80, Str 60, MinSpd 60, MinFort 40, HeatRes 80 | **Heat Shield**: immune to Heat |
| Flamebreaker / Yog | E | Hard Stone coll 5 | Yog: Def 60 HP 200 MinSpd 100 MinFort 80 HeatRes 40 | Yog: **Absorb** +1 MinSpd per 5 Yogs killed (max 500) |
| Glacite | E | — | Spd 50, Def 415, TrueDef 30, MinSpd 40, MinFort 20 | **Expert Miner**: +2 MinSpd per Mining level |
| Mineral | E | — | Spd 50, Def 390, BlockFort 100 | **Mineralworks** (tiered): +Mining Spread on ores/blocks |
| Divan's | L | HotM 6 | per piece Def 130, HP 100–200, MinFort 30, MinSpd 80, HeatRes 10 | no set bonus; gemstone slots carry the power. Forge 24h each: 5/8/7/4 Divan Fragments + 10 Gemstone Mixture + 1 Flawless Ruby |
| Sorrow | L | HotM 5 | MF 20, MinFort 80, TrueDef 240, MinSpd 200 | **Mist Aura** (2-piece): damage from ghosts ×0.6 |

#### Farming armor (Garden era)

| Set | Tier | Req | Set total | Tiered bonus (k/4) |
|---|---|---|---|---|
| Melon | U | Farming 20 | (see appendix) | **Cropier Crops**: wheat/carrot/potato 0.03% → Cropie (12% in Greenhouse); +FarmFort per piece |
| Cropie | U | Farming 30 | FarmFort 90, Spd 15, HP 550, Def 120, PestCh 50 | **Squashbuckle**: pumpkin/melon/cocoa 0.01% → Squash (4% in Greenhouse) |
| Squash | R | Farming 35 | FarmFort 110, Spd 18, HP 600, Def 140, PestCh 60 | **Mento Fermento**: cane/cactus/mushroom/wart 0.005% → Fermento |
| Fermento | E | Farming 40 | FarmFort 130, Spd 22, HP 650, Def 160, PestCh 70 | **Feast**: flowers 0.002% → Helianthus; also counts as Tater+Cropie+Squash tiers |
| Rancher's Boots | E | Farming 21 | Spd 50, Def 70, HP 100 | lets you set a max speed (utility) |
| Biohazard | U | Garden 5 | HP 80, Def 50, PestCh 66 | — |

Crafting chain: Melon piece + 20 Cropie + 8 Ench. Hay Bale + 6 Box of Seeds (+ extras) → Cropie piece; similarly
Cropie → Squash → Fermento.

#### Fishing armor

| Set | Tier | Req | Set total | Bonus |
|---|---|---|---|---|
| Angler | C | — | Def 105, SCC 4 | **Depth Champion**: −3% dmg from sea creatures per piece; **Deepness Within**: +6 HP per Fishing level (?) |
| Backwater | U | Fishing 5 | HP 140, Def 70, SCC 8, Treasure 0.4 | **Swamp Soldier**: +5% dmg to sea creatures per piece |
| Salmon | R | Fishing 13 | HP 430, Def 150, SCC 6 | **Water Burst**: sneak in water to dash (20 mana) |
| Sponge | E | Fishing 17 | Def 450, SCC 8 | **Absorb**: Def ×2 in water |
| Diver's | E | Fishing 20 | HP 650, Def 280, SCC 8, PressRes 60 | **One with the Fish**: breathe underwater; Treasure Chance underwater (tiered) |
| Shark Scale | L | Fishing 24 | HP 650, Def 550, SCC 10 | Def ×2 in water; Festival Fisher (shark chance) |
| Abyssal | L | Fishing 22 | HP 600, Def 600, SCC 8, PressRes 80 | as Diver's, larger |
| Nutcracker | L | Fishing 28 | HP 355, Def 145, SCC 6 (×2 on Jerry's Workshop) | **Battalion**: +1 MF +50 Def to everyone within 30 blocks (stack 5) |
| Thunder | E | Fishing 36 | HP 850, Def 750, SCC 16, Str 100 | **Static Charge**: 1 charge / 30s (cap 2), each +15% to next hit; Magmatic −10% taken, ×1.2 dealt |
| Magma Lord | L | Fishing 45 | HP 950, Def 750, SCC 18, Str 40, CD 140 | **Fireproof** (2 pcs), **Lord's Blessing** (5 pcs incl. gauntlet?): +1 SCC, +20 MF vs Magmatic; −15% taken, ×1.3 dealt |
| Trophy Hunter (Bronze/Silver/Gold/Diamond) | U–L | Trophy fishing tier | HP 250 / 350 / 450 / 550 | **Star Fisher**: +0.2 / ? / ? / +1 FishSpd per ✪ |

#### Misc notable sets

| Set | Tier | Set total | Bonus |
|---|---|---|---|
| Cheap / Fancy / Elegant Tuxedo (C/L/B only) | E/L/L | CD 100 Int 100 / CD 150 Int 300 Spd 10 / CD 200 Int 500 Spd 10 | **Dashing**: max HP set to 75 / 150 / 250; deal +50% / +100% / +150% damage (glass-cannon) |
| Berserker | E (Combat 22) | Str 170, CD 170 | **Berserk**: HP and Def halved, health-cost abilities −20% |
| Shimmering Light | R (Combat 22) | Def 350, HP 400, Int 350 | **Shimmer**: +200% XP orbs from mobs/ores |
| Rampart | R (Combat 22) | Def 200, HP 500, Int 30 | +50 HP, +20 Str, +15 CD on the Crimson Isle |
| Bat Person | L | Def 85 | +5% rare Candy chance |
| Snow Suit | E | Def 125, HP 310 (×2 on Workshop) | Frosty Snow Cannon unlimited ammo |
| Zombie (vanilla-ish) | ? | Def 105, HP 480 (3-pc) | heal 10/s for 5s when hit by a projectile |
| Growth (?) | R | — | heal 1% HP on kill; each piece +1 HP per kill (max +100) |
| Canopy | U (Foraging 12) | HP 340, Def 115, Sweep 24 | — |

### 3.3 Full armor table

Every armor-category item in the live API, grouped into sets, is in **Appendix A** (325 set rows). Set bonuses and
per-piece recipes for every multi-piece set are in **Appendix A2**.

---

## 4. Weapons

### 4.1 Weapon types and how they behave

| Type | Swing | Notes for implementation |
|---|---|---|
| Sword | melee, vanilla attack cooldown removed; `ATTACK_SPEED` stat shortens hit delay | most weapons; can carry right-click ability |
| Longsword (Dark Claymore, Giant's Sword…) | melee with **+Reach** stat | 6 items in API |
| Dagger (`sword_type`) | melee; Blaze-slayer daggers have **attunement** (swap Ashen/Auric or Spirit/Crystal to pierce Hellion Shields) | Livid + 6 Blaze daggers |
| Katana | melee; Enderman slayer, **Soulcry** ability (Ferocity vs Endermen, costs Soulflow) | 4 |
| Scythe | melee or ability | Reaper/Frozen/Glacial |
| Bow | charge-to-shoot; damage scales with draw | arrows add flat damage (Flint +1 … Emerald-tipped +20, Magma Arrow +40 & +10 CC) |
| **Shortbow** | no draw time — fires on click at a fixed cooldown set by Attack Speed (`Shortbow: Instantly shoots!`) | Terminator, Juju, Spirit, Mosquito, Artisanal, Machine Gun, Dragon, Spider |
| Wand / Staff | no melee damage relevance; ability only, scales with **Int** | ability damage = base × (1 + Int/100 × scaling) × (1 + AbilDmg%) |

**Ability-damage scaling factors** (API `ability_damage_scaling`): AOTD 0.1, Pigman Sword 0.1, Ice Spray 0.1,
Giant's Sword/Giant's Eye 0.05, Spirit Sceptre 0.2, Bonzo Staff 0.2, Jerry-chine 0.2, Aurora (Runic) Staff 0.2,
Staff of the Rising Sun 0.2, Vampire Witch Mask 0.2, Pumpkin Bomb/Launcher 0.2, everything else 0.3 (Midas Staff,
Yeti Sword, Fire Veil, Fire Fury, Frozen/Glacial Scythe, Hellstorm, Starlight, Celeste, Alchemist's Staff,
Dreadlord, Implosion/Shadow Warp/Wither Impact scrolls, Staff of the Volcano).

### 4.2 Key weapons (curated)

All stats from the live API. "Ability" quotes the lore at base values.

#### Swords — early/mid

| Weapon | Tier | Stats | Req | Ability / passive | Source |
|---|---|---|---|---|---|
| Rogue Sword | C | Dmg 20 | — | **Speed Boost** (RMB): +100 Spd for 30s; 41 mana, 5s CD | Hub shop (Co-op Soulbound) |
| Undead Sword | C | Dmg 30 | — | +100% dmg to Undead | Hub shop |
| Spider Sword | C | Dmg 30 | — | +100% dmg to Arthropods | 2 Spider Eye + stick |
| End Sword | U | Dmg 35 | — | +100% dmg to Ender mobs | 2 Ender Pearl + stick |
| Cleaver → Super → Hyper → Giant Cleaver | U→R→E→L | 40/10 → 105/20/CD20 → 175/100/CD100 → 235/120/CD120 | — / — / F3 / F6 | **Cleave**: hit spreads to mobs within 3 / 3 / 4 / 5 blocks | Hub / dungeons |
| Prismarine Blade | U | Dmg 50, Str 25 | — | +200% dmg in water | 64 Prismarine Shard |
| Flaming Sword | U | Dmg 50, Str 20 | — | ignite 3s | Hub |
| Raider Axe | R | Dmg 80, Str 50 | — | +20 coins/kill (lvl 10+), +1 Dmg / 500 kills (max 35), +1 Str / 500 wood collection (max 100) | Hub shop |
| Tactician's Sword | R | Dmg 50, CC 20 | — | +15 Dmg per Combat collection at tier VII+ (11 total) | Combat collections |
| Golem Sword | R | Dmg 80, Str 125, Def 25 | — | **Iron Punch**: 250 base magic dmg hexagon; 70 mana, 3s CD | 2 Ench. Iron Block |
| **Aspect of the End** | R | Dmg 100, Str 100 | — | **Instant Transmission**: teleport 8 blocks, +50 Spd 3s; 50 mana | 32 Ench. Eye of Ender + 1 Ench. Diamond |
| Aspect of the Void | E | Dmg 120, Str 100 | — | Instant Transmission (45 mana) + **Ether Transmission** if Etherwarp Merger applied (57 blocks, 180 mana, 1 Soulflow) | 32 Null Ovoid + AOTE |
| Recluse → Tarantula Fang → Scorpion Foil → Sting | U→R→E→L | 60/30/CD10 → 90/45/20 → 120/60/30 → 150/75/40 | Spider 2/4/6/8 | +150/200/250/300% vs Arthropods, Webcutter; Sting always crits | Spider slayer drops |
| Revenant Falchion → Reaper Falchion → Halberd of the Shredded | R→E→L | 90/50/Int100 → 120/100/Int200 → 140/115 | Zombie 3/6/8 | +150/200/250% vs Undead; heal 10/25 per hit; Halberd **Throw**: 10% melee dmg, each consecutive throw ×2 dmg & ×2 mana (max 16×), 20 mana | Zombie slayer |
| Shaman Sword → Pooch Sword | E→L | 100/20/Spd5 → 160/80/AtkSpd5/Spd10 | Wolf 3/6 | Shaman +1 Dmg per 50 max HP; Pooch +20 Dmg per Wolf Slayer level, +200% vs Wolves (+100% more with Pack/Mastiff set) | Wolf slayer |
| Voidwalker → Voidedge → Vorpal → Atomsplit Katana | U→R→E→L | 105/40/CD15 → 155/60/25/Int50 → 190/80/30/Int200 → 305/100/50/Int300 | — / Ender 3/5/6 | +150/200/250/300% vs Endermen; **Soulcry**: +200/300/400 Fer vs Endermen 4s; 200 mana, 1–2 Soulflow, 4s CD | Enderman slayer |
| Blaze daggers: Firedust/Twilight → Kindlebane/Mawdredge → Pyrochaos/Deathripper | R→E→L | 90/45/CD15 → 130/55/20/AtkSpd10 → 160/75/25/CC10/AtkSpd20 | Blaze 2/4/6 | ×1.2–3.5 vs Infernal; **Attunement** swap to pierce Hellion Shields; Burning Vengeance (repeat last 8 hits after 6s) / Wraith Drain (heal 200 + 4% HP) | Blaze slayer |
| Ragnarock Axe | R | Dmg 200, Str 100 | Combat 22 | **Ragnarock**: channel; after 3s without damage gain 1.5× weapon Str for 10s; 500 mana, 20s CD | 20 Hallowed Skull |
| Emerald Blade | E | Dmg 130 | — | dmg bonus scales with purse coins (cap 2B); formula `2.5 × purse^(1/4)` (?) | 2 Ench. Emerald Block |
| Midas' Sword | L | Dmg 170 | Combat 20, Cata 17 | **Greed**: Str & Dmg bonus by Dark Auction price, max +120 at ≥50M | Dark Auction |
| Ink Wand | E | Dmg 130, Str 90 | — | **Ink Bomb**: 10,000 dmg + blindness; 60 mana, 30s CD | 64 Ench. Ink Sack |
| Leaping Sword → Silk-Edge | E→L | 150/100/CD25 → 175/125/25 | Cata 8/13 | **Leap**: 350/400 dmg on landing + freeze 1s; 50 mana, 1s CD | Ench. Fermented Spider Eye |
| End Stone Sword | E | Dmg 120, Str 80 | — | **Extreme Focus** (End only): consume all mana; per 100 mana: −1% dmg taken (max 20%) and +25% next hit (max 500%) | 128 Ench. End Stone |
| **Aspect of the Dragons** | L | Dmg 225, Str 100 | Combat 18, Cata 12 | **Dragon Rage**: 12,000 dmg cone + big knockback; 100 mana | Dragon drop |
| Pigman Sword | L | Dmg 200, Str 100, CD 30, CC 5, Int 300 | Cata 18 | **Burning Souls**: +75 Def 5s and flame vortex up to 30,000 over 5s; 400 mana, 5s CD | 48 Ench. Grilled Pork |
| Yeti Sword | L | Dmg 150, Str 170, Int 50 | Cata 22 | **Terrain Toss**: up to 15,000 dmg; 250 mana, 1s CD | Packed Ice + Hilt of True Ice |
| Sword of Revelations → Daedalus Blade | E→L | 180/50 → (copies pet combat stats) | — | Daedalus: +4 Dmg per Taming level, +1% dmg & +0.2 MF vs Mythological per bestiary tier | Diana (Mythological Ritual) |

#### Dungeon weapons

| Weapon | Tier | Stats | Req | Ability | Drop |
|---|---|---|---|---|---|
| Bonzo's Staff | R | Dmg 160, Int 250 | F1 | **Showtime**: balloons explode for up to 1,000; 90 mana | F1 |
| Adaptive Blade | E | Dmg 170 | F2 | class-adaptive: Berserk +10 Dmg +75 Str +10 Spd; Mage +100 Int +10 Dmg; Tank +100 Def +5 Vit +5 TD; Healer +10 Mend; Archer marks for +10% arrow dmg | F2 |
| Spirit Sceptre | L | Dmg 180, Int 300 | F4 | **Guided Bat**: 2,000 dmg; 200 mana | F4 (3 Spirit Wing + 6 Ench. Lapis Block) |
| Spirit Sword | E | Dmg 210, Str 50 | F4 | +2% vs Undead per 1% missing HP | F4 |
| Bone Reaver → Felthorn Reaper | E→L | 235/90 → 275/CD115 | F4 / F6 | **Reaving Strike**: arc for 125% / 135% melee dmg | crafted |
| Livid Dagger | L | Dmg 210, Str 60, AtkSpd 50, CD 50, CC 100 | F5 | **Throw**: 150 mana 5s; crits +100% dmg from behind | F5 |
| Shadow Fury | L | Dmg 300, Str 130, CD 30, Spd 30 | F5 | **Shadow Fury**: teleport to up to 5 enemies within 12 blocks, rooting them; 15s CD | F5 |
| **Giant's Sword** | L | Dmg 500, Reach 1 | F6 | **Giant's Slam**: 100,000 dmg AoE; 100 mana, 30s CD (scaling 0.05) | F6 |
| Necromancer Sword | L | Dmg 250, Str 125, Def 125 | F6 | **Raise Souls**: absorb souls of kills, summon as allies (max 2) | F6 |
| Flower of Truth → Bouquet of Lies | L | 160/300 → 220/300/CD50 | F6 | Heat-Seeking Rose (ricochet ×3) / Petal Barrage (3 roses, 5 foes, +2% per 10% missing HP) ; 1s CD | F6 (9 Golem Poppy) |
| Precursor Eye / Giant's Eye Sword | L | — / Dmg 500 | F6 | Eye Beam 4,000 dmg, +100%/s for 5s | F6 |
| Dark Claymore | L | Dmg 500, Str 100, CD 100, Reach 2 | F7 | — | F7 |
| Necron's Blade (Unrefined) | L | Dmg 260, Str 110, Fer 30, Int 50 | F7 | +50% vs Wither mobs | 24 Wither Catalyst + Necron's Handle |
| **Hyperion** | L | Dmg 260, Str 150, Fer 30, Int 350 | F7 | +1 Dmg & +2 Int per Cata level; scrolls (below) | 8 Giant Fragment (Laser) + Necron's Blade |
| Valkyrie | L | Dmg 270, Str 145, Fer 60, Int 60 | F7 | +1 Dmg & +1 Str per Cata level | 8 Giant Fragment (Diamond) + blade |
| Astraea | L | Dmg 270, Str 150, Def 250, TD 20, Fer 30, Int 50 | F7 | +1 Dmg & +2 Def per Cata level | 8 Giant Fragment (Boulder) + blade |
| Scylla | L | Dmg 270, Str 150, CD 35, CC 12, Fer 30, Int 50 | F7 | +1 Dmg & +1 CD per Cata level | 8 Giant Fragment (Bigfoot) + blade |
| Wither Cloak Sword | E | Dmg 190, Str 135, Def 250 | F7 | **Creeper Veil**: 10s layered shield, each layer blocks up to 1,000 dmg for 30 Vitality; can't attack while up | F7 |
| Midas Staff | L | Dmg 130, Str 150, Int 50 | Cata 25 | **Molten Wave**: up to 12,000 dmg; 500 mana, 1s CD; Greed bonus up to +26,000 ability dmg at 100M paid | Dark Auction |

**Necron's Blade scrolls** (from Master Mode 7, combined in anvil):

| Scroll | Ability (base) | Mana | CD |
|---|---|---|---|
| Implosion | 10,000 base dmg to nearby enemies (lore shows 35,173.8 at a reference Int), scaling 0.3 | 300 | 10s |
| Shadow Warp | distortion 10 blocks ahead pulls enemies; recast within 5s to detonate for the same dmg | 300 | 10s |
| Wither Shield | −10% dmg taken 5s + absorption shield scaling with Cata level | 150 | 10s |
| **All three → Wither Impact** | teleport 10 blocks, implode (10,000 base), apply Wither Shield; this is the Hyperion "spam" ability | 300 (?) | ~0.15s (?) |

#### Slayer / Crimson / Kuudra / misc late weapons

| Weapon | Tier | Stats | Req | Ability |
|---|---|---|---|---|
| Sword of Bad Health | U | Dmg 200, Str 50, CD 50 | Combat 22 | +5 Str per 5% total HP (max +100) for 5s, 5s CD |
| Blade of the Volcano | R | Dmg 220, Str 70, CD 20 | Combat 22 | +100 Str vs enemies in lava |
| Enrager | E | Dmg 270, Str 150 | Combat 25 | **Enrage**: taunt 10 blocks, −10% their dmg 10s; 500 mana, 20s CD |
| Fire Freeze Staff | E | Dmg 50, Str 20 | Combat 22 | circle r5; after 5s mobs frozen 10s; 500 mana, 10s CD |
| Fire Fury Staff | E | Dmg 100, Int 300 | Combat 22 | **Firestorm**: up to 42,000 over 10s r7; 1,000 mana, 20s CD |
| Fire Veil Wand | E | Dmg 50, Int 200 | Combat 22 | **Fire Veil**: 15,000 dmg/s around you for 5s; 300 mana, 1s CD |
| Staff of the Volcano | R | Dmg 60, Int 150 | Combat 22 | **Explode**: 24,000 dmg r4 + burn; 100 mana, 30s CD |
| Hellstorm Wand | E | Dmg 120, Str 50, Int 100 | Combat 25 | +5 AbilDmg but take double dmg 30s; 100 mana, 30s CD |
| Aurora (Runic) Staff | L | Dmg 20, Int 250 | Combat 22, Kuudra | **Runic Zap**: 10,000 to first enemy (reduced >10 blocks); 10 mana, 1s CD; swap rune with LMB |
| Hollow Wand | L | Dmg 150, Str 20, Int 120 | Combat 22, Kuudra | combo spells from Spirit stacks: Spirit Spark (10), Hollowed Rush (15), Raging Wind (20), Ichor Pool (30) |
| Tormentor | L | Dmg 525, Str 30, CD 100 | Combat 42, Infernal Kuudra | take +50% dmg while held; **Chain of Agony** toggle: melee hits chain to 5 enemies; 200 mana, 0.5s CD |
| Gyrokinetic Wand | E | — | Enderman 6 | **Gravity Storm** (LMB): pull all mobs; 1,200 mana, 10 Soulflow, 30s; **Cells Alignment** (RMB): split incoming dmg over 3s; 220 mana, 2 Soulflow, 10s |
| Sinseeker Scythe | E | Dmg 100, Str 100 | Enderman 6, Cata 23 | **Sinrecall Transmission**: zap a 4-block line, recast within 1s for 1.5× mana or warp back; 260 mana |
| Soul Whip → Flaming Flay | E→L | 145/175 → 200/250/Fer25 | Cata 23 + Fishing 26 / 47 | **Flay**: whip arc dealing melee dmg; 2×/3× vs Sea Creatures; Flay heals 1% per SC hit (max 25%) |
| Jerry-chine Gun | E | Dmg 80, Int 200 | — | **Rapid-fire**: 500 dmg bullet + self-knockback; each shot +30 mana, reset after 4s (scaling 0.2) |
| Wand of Healing → Mending → Restoration → Atonement | U→R→E→L | — | Zombie 1/3/6/7 | heal 60 / 80 / 100 / 120 HP per second for 5 / 5 / 6 / 6s; 1s CD; don't stack |
| Wand of Strength | E | Dmg 50, Str 20 | Combat 22 | spend 10% max HP to give allies +30 Str 10s |
| Ice Spray Wand | R | Int 118–250 | Cata 20 | 17,000 dmg cone + freeze 5s (+10% dmg taken); 50 mana, 5s CD |
| Frozen → Glacial Scythe | R→E | — | Cata 10 | **Ice Bolt**: 1,000 / 1,500 dmg (+3-block explosion for Glacial); 50/75 mana |
| Starlight Wand | R | — | Combat 8 | **Starfall**: 300 dmg/s in 3 blocks for 5s; 100 mana, 2s CD |
| Celeste Wand | U | — | Combat 4 | **Lightning Strike**: 40 dmg (base 250 field) to nearest mob ≤10 blocks; 70 mana |
| Alchemist's Staff | R | Dmg 50, Str 20, Int 20 | Combat 22 | **Coin Conversion**: costs 100,000 coins/cast, 10,000 dmg |

#### Bows

| Bow | Tier | Stats | Req | Ability / passive | Source |
|---|---|---|---|---|---|
| Bow | C | Dmg 30 | — | — | vanilla craft |
| Savanna Bow | U | Dmg 50 | — | all damage ×2 | 3 Ench. Acacia + 3 Ench. String |
| Prismarine Bow | U | Dmg 50, Str 25 | — | consume Prismarine Shards: +300% vs Squids/Guardians, can hit sea creatures | 48 Prismarine Shard |
| Ender Bow | R | Dmg 60 | — | **Ender Warp** (LMB): shoot a pearl; landing deals 10% of mobs' HP in 8 blocks (max 500); 50 mana, 5s | 12 Ench. Ender Pearl |
| Artisanal Shortbow | R | Dmg 40 | Combat 4 | shortbow | shop |
| Explosive Bow | E | Dmg 100, Str 20 | — | explosion hits all mobs for full dmg | 3 Ench. Firework Rocket |
| Hurricane Bow | E | Dmg 120, Str 50 | — | **Tempest**: kills unlock Double Shot (20), Triple (50), Quadruple (100), Penta (250) (?) | 96 Ench. Bone + 96 String |
| Magma / Slime / Sulphur Bow | E | 100/100 ; 100/CD50 ; 200/100 | — / — / Combat 22 | consume Magma Cream/Slime/Sulphur: ×2 / ×5 vs slimes / ×2 dmg per shot | collections |
| Runaan's Bow | L | Dmg 160, Str 50 | Cata 8 | **Triple Shot**: 2 extra arrows at 40% dmg, homing | 192 Ench. Bone + 192 Ench. String |
| Spider Shortbow → Mosquito Shortbow | E→L | 50/25/CC10 → 319/151/CD39/Vit20 | Spider 3, Cata 10 / Spider 7, Cata 13 | 2×/3× vs Egg Sacs; Mosquito **Nasty Bite** (LMB): heal 189 HP on hit | Spider slayer |
| Machine Gun Shortbow | R | Dmg 75–159 | Cata 2 | **Rapid Fire**: 5 arrows/s for 8s at 70% dmg; 100s CD | dungeons |
| Bonemerang | L | Dmg 270, Str 130 | F4 | **Swing**: thrown bone deals arrow dmg, ×2 on return, pierces 10 | 5 Spirit Bone + 10 Ench. Bone Block + 15 Ench. String |
| Spirit Shortbow | L | Dmg 210, Str 100 | F4 | +2% vs Undead per 1% missing HP | F4 |
| Last Breath | L | Dmg 200, Str 180, CD 50 | F5 | −10% of target's max Def per hit, stacks 5× | F5 |
| Death Bow / Super Undead Bow | E | 300 / 220 | F6 / F3 | +100% vs Undead, 50% / 25% bounce | dungeons |
| Souls Rebound | E | Dmg 450 | Cata 8 | marks for 5s, then bursts all marked dmg +10% | Arachne / Spider's Den |
| Venom's Touch / Spider Queen's Stinger | E | 250/100 ; 360 | Cata 8 | 5-arrow volley + venom / aura 360 dmg, arrows pierce | Spider's Den |
| Dragon Shortbow | E | Dmg 120, Str 40, CD 50, CC 15 | Cata 8 | +15% vs Dragons, Dragon Tracer V | Dragon event |
| **Juju Shortbow** | E | Dmg 310, Str 40, CD 110, CC 10 | Enderman 5, Cata 15 | hits 3 mobs on impact; can damage Endermen | 32 Ench. Eye of Ender, 192 Ench. String, 32 Null Ovoid, 32 Ench. Quartz Block |
| **Terminator** | L | Dmg 310, Str 50, AtkSpd 40, CD 250 | Enderman 7, Cata 27 | **3 arrows at once**; **Crit Chance ÷ 4**; **Salvation** (LMB): beam piercing 5, always crits, 1 Soulflow (fires after 3 hits charge (?)) | 8 Tessellated Ender Pearl, 3 Null Blade, 128 Tarantula Silk, 1 Judgement Core, 4 Braided Griffin Feather |

Arrows (Target Practice unlock, Combat req): Flint +1, Reinforced Iron +5, Gold-tipped +10, Redstone-tipped +15,
Emerald-tipped +20, Magma +40 & +10 CC; utility arrows (Armorshred −5% target Def, Bouncy 50% bounce ×4 −15%/bounce,
Explosive, Glue (root 1.5s), Icy (Slowness I 3s), Nansorb (Piercing → +7.5% per pierce)).

All 219 sword/longsword/bow/arrow/wand entries with ability text and recipes are in **Appendix B**.

---

## 5. Tools

### 5.1 Pickaxes

| Pickaxe | Tier | BP | MinSpd | Other | Req | Source |
|---|---|---|---|---|---|---|
| Wooden | C | 1 | 70 | Dmg 15 | — | craft |
| Stone | C | 2 | 110 | | — | craft |
| Rookie | C | 2 | 150 | OreFort 5 | — | Hub shop |
| Iron | C | 3 | 160 | | — | craft |
| Zombie | C | 3 | 190 | **Rotten**: +5 MinFort per Undead within 30 blocks (max 25) | — | 3 Rotten Flesh + 2 sticks |
| Golden | C | 1 | 250 | | — | craft |
| Diamond | U | 4 | 220 | | — | craft |
| Lapis | U | 4 | 200 | 25% chance double XP from ores | — | 512 Lapis + Iron Pick |
| Promising | U | 3 | 50 (+10/100 blocks, max +250) | | — | 3 Iron Block |
| Fractured / Bandaged / **Mithril** / Refined Mithril | U/U/U/R | 5 | 225 / 250 / 300 / 350 | MinFort 2/4/7/10 | — / HotM 2 / HotM 2 / HotM 3 | Dwarven Mines |
| Titanium / Polished Titanium / Rusty Titanium | R/R/U | 6/6/5 | 140 / 200 / 100 | +50 / +100 / +25 MinFort on Titanium | HotM 3/4/— | Dwarven |
| Jungle Pickaxe | U | 5 | 330 | Sludge Juice drops | HotM 4 | Crystal Hollows |
| Stonk | E | 1 | 380 | Efficiency VI; End Stone no Endermites, 5% extra drop | — | trade |
| Pickonimbus 2000 | E | 8 | 750 | breaks after 2,000 uses | HotM 4 | Crystal Hollows drop |

Breaking Power gates which blocks can be mined (Mithril BP 4–5, Titanium 5–6, Gemstones 6–9 by gem, Glacite tunnels
up to 10 (?)).

### 5.2 Drills

Drills are pickaxes that consume **fuel** (default tank 3,000 fuel (?); 1 fuel per block (?)) and accept 3 parts at
the **Drill Mechanic**: Fuel Tank, Drill Engine, Upgrade Module. Refuel with Volta / Oil Barrel (+10,000) or Biofuel
(+3,000).

| Drill | Tier | BP | MinSpd | MinFort / GemFort | Pristine | Req | Forge recipe |
|---|---|---|---|---|---|---|---|
| Mithril Drill SX-R226 | R | 5 | 450 | 15 | — | HotM 2 | 4h: 3 Refined Mithril, Drill Engine, Fuel Tank; +20% Mithril Powder |
| Mithril Drill SX-R326 | R | 6 | 600 | 20 | — | HotM 3 | upgrade: Golden Plate + Mithril Plate; +40% Mithril Powder |
| Ruby Drill TX-15 | R | 7 | 150 (+800 on gems) | GemFort 60 | 0.5 | HotM 4 | 4h: Drill Engine, Fuel Tank, 6 Fine Ruby; +5% Gemstone Powder |
| Gemstone Drill LT-522 | R | 8 | 300 (+800) | GemFort 80 | 1 | HotM 4 | + 3 Gemstone Mixture; +10% GP |
| Topaz Drill KGR-12 | E | 9 | 450 (+800) | GemFort 100 | 1.5 | HotM 5 | + Flawless Topaz, 3 Mixture, 8 Magma Core; +15% GP |
| Jasper Drill X | E | 9 | 600 (+800) | GemFort 120 | 2 | HotM 6 | + Flawless Jasper, 100 Treasurite, 16 Magma Core; +20% GP |
| Titanium Drill DR-X355 | E | 7 | 700 | 25 | — | HotM 5 | 4h: Fuel Tank, Engine, 8 Ref. Mithril, 8 Ref. Titanium, 6 Golden Plate |
| Titanium DR-X455 / X555 / X655 | E/E/L | 8/9/9 | 900 / 1,200 / 1,600 | 40 / 70 / 120 | — | HotM 5/5/6 | successive upgrades (Ref. Titanium, Ref. Diamond, Mithril Plate, Plasma, Corleonite…) |
| **Divan's Drill** | M | 10 | 1,800 | 150 | — | HotM 7 | Titanium DR-X655 + Divan's Alloy + 50,000,000 coins |

Drill parts:

| Part | Slot | Effect | Req | Recipe |
|---|---|---|---|---|
| Mithril-Infused Fuel Tank | Fuel | 10,000 fuel, −2% pickaxe ability CD | — | 24h: 5 Ref. Diamond, 10 Ref. Mithril, 5 Fuel Tank |
| Titanium-Infused Fuel Tank | Fuel | 25,000, −4% | — | + 10 Ref. Titanium, Mithril tank |
| Gemstone Fuel Tank | Fuel | 50,000, −6% | — | + 10 Mixture, 4 Precursor Apparatus |
| Perfectly-Cut Fuel Tank | Fuel | 100,000, −10% | HotM 7 | + 16 Apparatus, 25 Mixture, 32 Plasma |
| Mithril-Plated Drill Engine | Engine | +75 MinSpd, +5 MinFort | — | 24h: Mithril Plate + 2 Drill Engine |
| Titanium-Plated Engine | Engine | +150 / +15 | — | + 8 Ref. Titanium |
| Ruby-Polished Engine | Engine | +250 / +30 | HotM 5 | + Perfect Ruby, 4 Apparatus |
| Sapphire-Polished Engine | Engine | +400 / +50 | HotM 6 | + 3 Perfect Sapphire, 16 Plasma, 8 Apparatus |
| Amber-Polished Engine | Engine | +600 / +100 | HotM 7 | + 5 Perfect Amber, 32 Plasma, 16 Apparatus |
| Goblin Omelette | Upgrade | 25% chance to not consume fuel | — | 18h: 96 Goblin Egg |
| Pesto Goblin Omelette | Upgrade | 75% no-fuel chance | HotM 5 | + Flawless Jade, 96 Green Goblin Egg |
| Spicy Goblin Omelette | Upgrade | +20% Powder from all sources | HotM 5 | + Flawless Ruby, 96 Red Egg |
| Sunny Side Goblin Omelette | Upgrade | +50 MinFort, 4× fuel use | HotM 5 | + Flawless Topaz, 96 Yellow Egg |
| Blue Cheese Goblin Omelette | Upgrade | +1 level to all unlocked HotM perks | HotM 5 | + Flawless Sapphire, 96 Blue Egg |
| Starfall Seasoning | Upgrade | +25 MinSpd +10 MinFort (×2 within 20 blocks of a Fallen Star) | HotM 2 | 18h: 16 Treasurite, 64 Starfall |

Chisels (Glacite fossil excavation): Chisel 16 charges → Reinforced 18 (HotM 8, +60% highlight) → Glacite-Plated 20
(HotM 9) → Perfect 22 (HotM 10).

### 5.3 Axes (foraging) — post-2025 "Sweep" model

The 2025 foraging update (Galatea) replaced "breaks N logs" with a **Sweep** stat (≈ logs broken per swing (?)) and
added **Foraging Fortune** to axes.

| Axe | Tier | Stats | Req | Ability |
|---|---|---|---|---|
| Wooden/Stone/Iron/Gold/Diamond Axe | C–U | Dmg 10–30 | — | — |
| Rookie Foraging Axe | C | Dmg 20, Efficiency I | — | — |
| Efficient / Sweet / Promising Axe | U | Dmg 25 | — | 5 planks / 20% apple / Efficiency I after 50 blocks |
| Spruce ("Jungle") Axe | U | Sweep 4 | — | **Throwing Axe**: throw at a tree on Foraging Islands → sweep 50% of normal logs, 1s CD; +50 ForFort in The Park |
| Treecapitator | E | Sweep 25 | — | Throwing Axe; +100 ForFort in The Park. Recipe: 32 Ench. Jungle Log + Spruce Axe |
| Seriously Damaged / Decent Axe | U / R | Sweep 10 / 24, ForFort 5 | Foraging 12 / 15 | Throwing Axe |
| Fig Hew | U | Dmg 30, Sweep 15, ForFort 20, Str 10 | Foraging 12 | **Frenzy**: +1 Sweep per 2,000 Galatea logs (max +20) |
| Figstone Splitter | R | Dmg 50, Sweep 25, ForFort 35, Str 15 | Foraging 15 | +1 Sweep / 10,000 logs (max 20) |
| Helix Chopper | E | Dmg 75, Sweep 50, ForFort 50, Str 25 | Foraging 20 | +1 Sweep / 20,000 logs (max 40) |
| Huntaxes (Genesis → Dominus → Cursus → Praedator → Nex Titanum) | C→L | Dmg 5→25 | — | Hunting tool: **Absorptio** copies 5/10/15/20/25% of a weapon's combat stats; **Vis Temperata** caps each hit at 15/20/25/33/50% of the target's max HP, never lethal (for catching shards) |

### 5.4 Hoes and farming tools

Garden-era crop tools ("Theoretical hoes" — Euclid's Wheat, Gauss Carrot, Pythagorean Potato, Turing Sugar Cane,
Newton Nether Wart, Eclipse Sickle (sunflower/moonflower), Wild Rose Cutter; plus Melon Dicer, Pumpkin Dicer, Cactus
Knife, Cocoa Chopper, Fungi Cutter) each come in **Mk. I / II / III**:

| Mk | Tier | Req | Upgrade recipe |
|---|---|---|---|
| I | U | Farming 10 + Garden level (crop-specific, 1–12) | Garden Desk / SkyMart (copper) |
| II | R | Farming 15 | 64 Jacob's Ticket + crop item (e.g. 20 Ench. Hay Bale) + Mk I |
| III | E | Farming 20 | 256 Jacob's Ticket + 4× crop item + Mk II |

Current lore shows a **tool level bar** ("Level 1 → 2 (0/1k)") — tools level from crops farmed. The older model
(still the best documented): Mk I/II/III give +10/+25/+50 crop-specific fortune, plus the **Logarithmic Counter**
(+16 FarmFort per digit of crops broken beyond 3 digits (?)) and **Collection Analysis** (+8 per digit of the crop
collection (?)), and the **Cultivating** enchant (+1..+12 FarmFort and wisdom by crops broken (?)). Treat the leveling
numbers as **(?)** and confirm before implementing. Helpers: Gardening Hoe/Axe (Basic +10 FarmFort; Advanced +15 with
Replenish), Rookie Hoe (+5), SkyMart Vacuums (100→150→200→300→400 pest dmg/s; FarmFort 5→25), HydroCan watering cans
(150→350 water/s, 3k→20k tank).

### 5.5 Fishing rods and rod parts

Since the 2025 Backwater Bayou update, every rod takes **3 parts from Roddy**: Hook, Line, Sinker. Old special rods
(Speedster, Farmer's, Ice, Winter, Yeti, Phantom, Auger, Shredder, Chum, Sponge, Prismarine) are "broken" and are
converted by Roddy into a new rod (their abilities moved into parts — e.g. the Shredder became the **Shredded Line**).

| Rod | Tier | Dmg / Str | FishSpd | SCC | Other | Req | Source |
|---|---|---|---|---|---|---|---|
| Fishing Rod | C | 15 / 15 | 5 | 1 | | — | vanilla craft |
| Challenging Rod | U | 75 / 75 | 35 | 2 | Treasure 0.5 | Fishing 5 | Fishing merchant |
| Rod of Champions | R | 100 / 100 | 50 | 4 | | Fishing 15 | 8 Ench. Lily Pad + Challenging |
| Rod of Legends | E | 125 / 125 | 65 | 6 | | Fishing 20 | 128 Ench. Lily Pad + Champions |
| Rod of the Sea | L | 150 / 150 | 80 | 8 | | Fishing 24 | 4 Ench. Shark Fin + 4 Great White Tooth + Legends |
| Starter Lava Rod | U | 50 / 50 | 10 | — | Trophy 5% | Fishing 10 | Crimson Isle |
| Topaz Rod (lava) | R | 100 / 100 | 50 | 2 | | Fishing 19, HotM 5 | 12h forge: 3 Bejeweled Handle + 2 Flawless Topaz |
| Magma Rod | R | 175 / 175 | 40 | 6 | Trophy 15% | Fishing 27 | 10 Magmafish (Silver) |
| Inferno Rod | E | 200 / 200 | 50 | 10 | Trophy 20% | Fishing 30 | 16 Gold Magmafish + Magma Rod |
| Hellfire Rod | L | 225 / 225 | 60 | 14 | Trophy 25% | Fishing 35 | 80 Gold (or 4 Diamond) Magmafish + Inferno |
| Giant Fishing Rod | L | — | — | — | Double Hook 10 | — | admin auction only |

| Rod part | Type | Effect | Req |
|---|---|---|---|
| Common Hook | Hook | more common catches (?) | Fishing 10 |
| Hotspot Hook | Hook | +20% chance of Hotspot sea creatures in a Hotspot | Fishing 20 |
| Spooky Hook | Hook | +20% Spooky sea creatures during Spooky Festival | Fishing 21 |
| Puddle Jumper Hook | Hook | +400% Puddle Jumper chance | Fishing 25, Lotus coll 8 |
| Treasure Hook | Hook | only items & Treasure | Fishing 25 |
| Speedy Line | Line | +10 FishSpd | Fishing 5 |
| Shredded Line | Line | +250 Dmg, +50 Fer | Fishing 20 |
| Trophy Line | Line | +5 Trophy Chance | Fishing 20, Lotus coll 9 |
| Titan Line | Line | +2 Double Hook Chance | Fishing 30 |
| Junk / Stingy / Chum / Festive / Icy / Prismarine / Sponge / Lotus Sinker | Sinker | +10 Treasure (but junk) / 10% bait save / materialise Chum / gifts / Ice / prismarine / sponge / Lotus | Fishing 5–15 |
| Hotspot Sinker | Sinker | Fishing Hotspot bonuses +100% | Fishing 20 |

---

## 6. Pets

### 6.1 The pet object

```
Pet = { type: "ENDER_DRAGON", tier: "LEGENDARY", exp: 25353230, heldItem: "PET_ITEM_TIER_BOOST"|null,
        candyUsed: 0..10, skin: null, active: bool }
```
- One **active** pet at a time (summoned); its stats and abilities apply. Pets in the menu do nothing unless an
  ability says "active even when not summoned" (Bingo, Spirit).
- Each pet has a **skill type** (COMBAT, MINING, FARMING, FISHING, FORAGING, ALCHEMY, ENCHANTING, "ALL" for Bingo).
- **Rarities available differ per pet** (e.g. Ender Dragon only Epic/Legendary; Golden Dragon only Legendary).
  Higher rarity = higher stat numbers and **more abilities** (abilities unlock by rarity: typically 1 at Common/Uncommon,
  2 at Rare/Epic, 3 at Legendary, 4 at Mythic).
- Max level 100; **Golden / Jade / Rose Dragon go to 200** (levels 101–200 each need 1,886,700 XP).
- Pet stats scale **linearly** with level: `stat(L) = stat(1) + (stat(100) − stat(1)) × (L − 1) / 99`. Same for
  ability numbers. Dragons 101–200 continue on the "101:200:1" curve.

### 6.2 Level curve (exact)

XP to go from level `L` to `L+1` = `PET_LEVELS[offset + L − 1]`, where `offset` = Common 0, Uncommon 6, Rare 11,
Epic 16, **Legendary 20, Mythic 20**. Bingo uses offset 0 for every rarity; Reindeer earns ×2 XP.

```
PET_LEVELS = [100,110,120,130,145,160,175,190,210,230,250,275,300,330,360,400,440,490,540,600,660,730,800,880,960,
1050,1150,1260,1380,1510,1650,1800,1960,2130,2310,2500,2700,2920,3160,3420,3700,4000,4350,4750,5200,5700,6300,7000,
7800,8700,9700,10800,12000,13300,14700,16200,17800,19500,21300,23200,25200,27400,29800,32400,35200,38200,41400,44800,
48400,52200,56200,60400,64800,69400,74200,79200,84700,90700,97200,104200,111700,119700,128200,137200,146700,156700,
167700,179700,192700,206700,221700,237700,254700,272700,291700,311700,333700,357700,383700,411700,441700,476700,
516700,561700,611700,666700,726700,791700,861700,936700,1016700,1101700,1191700,1286700,1386700,1496700,1616700,
1746700,1886700]
```

Cumulative XP to reach a level:

| Reach level | Common | Uncommon | Rare | Epic | Legendary / Mythic |
|---|---|---|---|---|---|
| 2 | 100 | 175 | 275 | 440 | 660 |
| 5 | 460 | 805 | 1,265 | 2,070 | 3,070 |
| 10 | 1,340 | 2,320 | 3,735 | 6,100 | 8,870 |
| 20 | 4,955 | 8,820 | 14,115 | 22,300 | 31,510 |
| 30 | 14,425 | 25,020 | 38,665 | 59,000 | 83,730 |
| 40 | 37,065 | 61,720 | 96,165 | 155,000 | 227,730 |
| 50 | 89,285 | 157,720 | 254,665 | 403,000 | 573,730 |
| 60 | 233,285 | 405,720 | 629,665 | 953,000 | 1,302,730 |
| 70 | 579,285 | 955,720 | 1,410,665 | 2,052,500 | 2,747,230 |
| 80 | 1,308,285 | 2,055,220 | 2,957,665 | 4,234,500 | 5,619,230 |
| 90 | 2,752,785 | 4,237,220 | 6,034,665 | 8,641,500 | 11,686,230 |
| 95 | 3,946,285 | 6,035,720 | 8,643,165 | 12,625,000 | 17,219,730 |
| **100** | **5,624,785** | **8,644,220** | **12,626,665** | **18,608,500** | **25,353,230** |
| 200 (Golden/Jade/Rose Dragon) | — | — | — | — | **214,023,230** computed as 25,353,230 + 100 × 1,886,700 (NEU `custom_pet_leveling` appends 100 steps of 1,886,700). Community figures quote ~210M — treat the exact total as (?) |

When a pet is upgraded to a higher rarity (Kat or Tier Boost) its **XP is kept and the level is recomputed** with
the new offset (so it drops in level).

### 6.3 Pet XP sources and rules

Rules per the official wiki (2025). The active pet gains XP whenever you gain skill XP:

`petXP = skillXP × typeFactor × skillFactor × tamingFactor × (1 + Σ petXpBoost%)`

| Factor | Value |
|---|---|
| typeFactor | **1** if the skill matches the pet's type; **1/3** if it does not |
| skillFactor | **Fishing ×1.5**; **Alchemy and Enchanting ÷4** when the pet is not of that type; all others ×1 |
| tamingFactor | **×1.01 per Taming level** (Taming 60 → ×1.60) |
| Pet XP boost items | held Exp Boost items: skill-specific +20% / +30% / +40% / +50% (C/U/R/E), All Skills +10% (C) and higher at higher rarities (?), All Skills Super-Boost +20%; Beastmaster Crest +0.x% |

| Other source | Effect |
|---|---|
| Pet Cake | +10,000 pet XP |
| **Carrot Candy** (Pet Candy) | Simple 20,000 · Great 200,000 · Superb 750,000 · Ultimate 1,500,000 XP; **max 10 candies per pet lifetime** (candy count lowers resale value) |
| **Exp Share** | 1 Exp Share slot by default (+2 more only while Diana is mayor). Pets in slots receive a % of the active pet's XP gains: Taming +0.2% per level (max 12%), "Why Not More" attribute 1–10%, **Exp Share** pet item +15%, Diana's "Sharing is Caring" +10%; additive, **max 47%** |
| Kat / Tier Boost | no XP change; rarity changes and level is recomputed from the same XP |


### 6.4 Pet items (held item, one per pet)

Selected; full list (95 entries) in **Appendix D**.

| Item | Rarity | Effect |
|---|---|---|
| All Skills Exp Boost | C/U/R/E/L | +10% (C) … pet XP for all skills (higher tiers more (?)) |
| Combat/Farming/Fishing/Foraging/Mining Exp Boost | C/U/R/E/L | +20% / +30% / +40% / +50% (C/U/R/E) pet XP for that skill; Legendary higher (?) |
| Big Teeth → Bigger Teeth | C… / U | +5 CC (C) … / Bigger +10 CC |
| Iron Claws → Gold Claws | C / U | pet CD & CC +40% / +50% |
| Sharpened Claws → Serrated Claws | C / R | +15 CD (C) / +25 CD |
| Hardened Scales → Reinforced Scales | U / R | +25 Def / +40 Def |
| Textbook | L | pet Int +100% |
| Antique Remedies → Hephaestus Remedies | E / L | pet Str +80% / +100% |
| Minos Relic → Hephaestus Relic | E / L | **all pet stats +33.3% / +50%** |
| Lucky Clover → Poignant Lucky Clover | E / L | +7 MF & +7 Overbloom / +13 & +13 |
| Tier Boost | L | pet rarity +1 tier (consumed in slot, removable) |
| Exp Share | E | +15% Exp Share rate |
| Quick Claw | L | +1 MinSpd & +1 MinFort per 2 pet levels |
| Crochet Tiger Plushie → Hephaestus Plushie | E / L | +35 / +50 AtkSpd |
| Dwarf Turtle Shelmet → Hephaestus Shelmet | E / L | +10 / +20 TrueDef + knockback immunity |
| Cretan Urn → Hephaestus Urn | E / L | +5 / +10 Pet Luck |
| Washed-up Souvenir → Hephaestus Souvenir | E / L | +5 / +10 SCC |
| Saddle | U | +30 Speed |
| Spooky Cupcake | U | +30 Str, +20 Spd |
| Reaper Gem | L | +8 Fer for 5s on kill |
| Four-Eyed Fish (Griffin only) | E | +2,000 coins per Griffin burrow, +13 MF, +55 Def |
| Flying Pig (Pig only) | R | +40 Potato Fortune, +20 Spd |
| Bubblegum | R | placed Deployables last 2× |
| Unalloyed Speed | E | +50 speed cap |
| Burnt Texts | L | +5 Double Hook, −50 FishSpd |
| Snake Eyes | L | +1.1 Treasure Chance |
| Edible Seaweed | R | +10 FishSpd |
| Winding Ivy | R | +10 Sweep |
| Barrel of Riches / Lotus Crown | E / L | Trophy fish +10% Gold / +10% Diamond |
| Titanium Minecart | R | +33.3 MinFort on Titanium |
| Vampire Fang, Bejeweled Collar, Jerry 3D Glasses, Mixed Mite Gel… | — | upgrade-specific / pet-specific (?) — see Appendix D & Kat table |

Hephaestus items are made from 3 of the Epic version + 1 Hephaestus Anvil; Claws/Teeth/Scales upgrades from 18 Ench.
Gold Block + base item + Zog Anvil.

### 6.5 Kat (pet sitter) upgrades

Kat raises a pet one rarity at a time for coins + items + real time; **Kat Flower** skips 1 day, **Kat Bouquet** 5 days.
The XP is kept. Full table for all 63 upgradable pets in **Appendix E**. Examples:

| Pet | Upgrade path |
|---|---|
| Ender Dragon | E→L: 400,000,000 coins, 20 days, 8 Summoning Eye |
| Blue Whale | C→U 15k/1d; U→R 75k/2d; R→E 900k/7d + 1 Ench. Cooked Fish; E→L 9M/12d + 8 Ench. Cooked Fish |
| Elephant | C→U 15k/6h; U→R 75k/20h; R→E 900k/5d; E→L 14M/10d; L→M 12.5M/1h + Trunk Polish |
| Enderman | C→U 1k/1d; U→R 5k/2d; R→E 10k/6d; E→L 40M/12d + 8 Ench. Eye of Ender; L→M 1M/1h + Enderman Cortex Rewriter |
| Bee | free dippers: Small/Medium/Large/Giant Honey Dipper per step; L→M 10M + Royal Jelly |

Pattern: Mythic is always a 1-hour, 1M–12.5M step that needs a pet-specific item.

### 6.6 Pet Score and other pet-collection effects

- **Pet Score** = Σ over each unique pet type of its highest owned rarity value (C1, U2, R3, E4, L5, M6). Each point
  also grants 3 SkyBlock XP.
- Pet Score → permanent Magic Find:

| Pet Score | 10 | 25 | 50 | 75 | 100 | 130 | 175 | 225 | 275 | 325 | 375 | 450 | 500 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Magic Find | +1 | +2 | +3 | +4 | +5 | +6 | +7 | +8 | +9 | +10 | +11 | +12 | +13 |

- Taming level = **+1 Pet Luck** per level and ×1.01 pet XP per level.


### 6.7 Key pets at a glance (Lv100, highest rarity)

| Pet | Type | Rarities | Lv100 stats (top rarity) | Headline abilities |
|---|---|---|---|---|
| Golden Dragon | Combat | L (Lv200) | Str 25→50, AtkSpd 25→50, MF 5→10 (Lv1→Lv100) | +11.1 Str & +2.2 MF per digit of Gold collection; +0.5% Str per 5 MF (max 5%); +0.25% dmg per 1M coins banked (max 250%); Midas items +10% |
| Ender Dragon | Combat | E, L | CC 10, CD 50, Str 50 | +200% vs Ender mobs; AOTD +50 Dmg +30 Str; **+10% all combat stats & MF** (L) |
| Tiger | Combat | C–L | Fer 25, CC 5, CD 50, Str 15 | +30% Ferocity; melee −55% healing; +100% dmg vs isolated targets |
| Black Cat | Combat | L, M | Int 100, Spd 125, Pet Luck 15, MF 15 | +100 Speed & speed cap; +15 Pet Luck; +15 MF; +15% combat collection drops |
| Griffin | Combat | C–M | CC 10, CD 50, Str 50, AtkSpd 25 | Mythological ritual spawns; +15% Str above 85% HP; +20 MF vs Mythological |
| Spirit | Combat | E, L | Int 100, Spd 30 | dungeon ghost assistance, ghost CDs −50%, first death penalty reduced |
| Golem | Combat | E, L | HP 150, Str 50, Reach 1 | **Last Stand** (<20% HP: −20% dmg taken, 40% max HP shield, +40% dmg, 60s CD); 25% ricochet |
| Turtle | Combat | E, L | Def 150, HP 75, TD 15 | +30% Def (+10% standing still); +2.5% Def to 4 players; knockback immune |
| Phoenix | Combat | E, L | Int 150, Str 60 | **Rekindle** (cheat death 4s, 60s CD); 4th melee strike ignites for 13× CD/s; fly on island; no coin loss on death |
| Sheep | Alchemy | C–L | AbilDmg 20, Int 100 | −20% mana cost; 10% shield after 10s no damage; +25% mana in dungeons |
| Guardian | Enchanting | C–M | Def 50, Int 100 | laser zaps for 120× Int every 3s; +30 Enchanting Wisdom; +30% mana regen |
| Elephant | Farming | C–M | HP 100, Int 75, FarmFort 50 | +20 Def per 100 Speed; +1 HP per 10 Def; **+150 FarmFort** (L); +20% Sowdust (M) |
| Bee | Farming | C–M | Str 30, Int 50, Spd 10, Farm/Forage/Mine Fortune 40 | Hive: per nearby player +10 Int +8 Str +5 Def (max 15) |
| Mithril Golem | Mining | C–M | TD 50, MinFort 25 | +200 MinSpd on Mithril; +20% combat stats on mining islands; +20% Mithril Powder |
| Scatha | Mining | R–L | MinSpd 100, MinFort 125 | +50% treasure chests; +125 GemFort on drills; +20% Gemstone Powder |
| Silverfish | Mining | C–L | Def 100, MinFort 20 | +50% mining XP; +30 Mining Wisdom; +150 MinSpd & Haste III |
| Megalodon | Fishing | E, L | Str 50, MF 10, Fer 10, SCC 10, FishSpd 40 | up to +150% dmg by target's missing HP |
| Baby Yeti | Fishing | C–M | Str 75, FishSpd 50, SCC 5 | Yeti Sword +100 Dmg/Int, −50% CD; +20% combat & fishing stats on Jerry's Workshop |
| Wolf | Combat | C–L | CD 10, HP 50, Spd 20, TD 10 | −30% dmg from wolves; +15 CD per nearby wolf (max 10); +30 Combat Wisdom |
| Bingo | All | C–M | HP 100, Str 35, Spd 75 | +25% collection items; +250% HotM/HotF XP; +15% skill & slayer XP; **+40% base stats of active pet**; works unsummoned (Bingo profiles) |

All 88 pets with every ability (Lv100 numbers at every rarity) are in **Appendix F**.

---

## 7. Accessories (talismans, rings, artifacts, relics)

### 7.1 Rules

- Accessories work from the **Accessory Bag** (or inventory). Only **one accessory per family** counts — the highest
  tier you own (Talisman → Ring → Artifact → Relic). Family upgrade map example (NEU `talisman_upgrades`):
  `SPEED_TALISMAN → SPEED_RING → SPEED_ARTIFACT → SPEED_RELIC`, `CANDY_TALISMAN → RING → ARTIFACT → RELIC`,
  `BROKEN_PIGGY_BANK → CRACKED_PIGGY_BANK → PIGGY_BANK`, `POTION_AFFINITY_TALISMAN → RING_POTION_AFFINITY →
  ARTIFACT_POTION_AFFINITY`, … Duplicates and lower family members give **no** stats and **no** Accessory Power.
- Many accessories in 2025+ only grant **Accessory Power** plus a niche effect; the stat power comes from the
  **selected Power** (Maxwell) scaled by total Accessory Power.

### 7.2 Accessory Power (formerly Magical Power)

| Rarity | Accessory Power |
|---|---|
| Common | 3 |
| Uncommon | 5 |
| Rare | 8 |
| Epic | 12 |
| Legendary | 16 |
| Mythic | 22 |
| Divine | 28 |
| Special | 3 |
| Very Special | 5 |

Special cases: **Hegemony Artifact** counts double (32 at Legendary); **Abicase** = +1 AP per 2 Abiphone contacts;
**Rift Prism** = 8 (11 once consumed at Erihann); dungeon accessories (e.g. Bonzo/Scarf/Livid fragments) give
**double AP inside dungeons**; Recombobulating an accessory raises its rarity and therefore its AP. Each AP gained also
gives **1 SkyBlock XP**. As of 0.27 the wiki counted 147 accessories / 2,121 base AP max (number grows each update).

**Stat multiplier from AP** (applied to the selected Power's stat table):
`M(AP) = 29.97 × (ln(0.0019 × AP + 1))^1.2`  → M(100) = 3.675, M(250) = 9.642, M(500) = 18.462, M(1000) = 32.312, M(1500) = 42.889, M(2000) = 51.441 (computed).

**Tuning points**: 1 point per 10 AP (confirmed), spent at Maxwell on 8 stats. Per point (from memory, verify): +5 HP, +1 Def, +1.5 Speed, +1 Str, +2 Int,
+0.2 CC, +1 CD, +0.3 AtkSpd (?).

**Enrichments** (Legendary/Mythic accessories only, one per accessory, from the Rift):
+0.5 AtkSpd, +1 CC (?), +1 CD, +1 Def, +0.3 Fer, +3 HP, +2 Int, +0.5 MF, +0.3 SCC, +1 Str, +1 Speed.
Enrichment Swapper changes all of them at once.

### 7.3 Accessory Bag slots

| Source | Slots |
|---|---|
| Base | 9 |
| Redstone collection II / III / IV / V / VI / VII / VIII / IX | +6 / 12 / 18 / 24 / 30 / 36 / 42 / 48 (cumulative) |
| Redstone Miner (Pigmen's Den) — redstone exchange | +4 |
| Community Center account upgrades (Elizabeth) I–VI | +2 each, +12 total |
| **Jacobus** — buys slot pairs for coins | up to +198 (≈1.76B coins total) |
| Accessory Size attribute (Hideonring shard) | +1 per level, max +10 |
| **Max** | **281** |

(These are the current-wiki numbers; older versions used 3 + Redstone collection steps up to 45 — the clone may pick
whichever curve fits its economy.)

### 7.4 Powers (Maxwell) and power stones

Combine **9 of the same Power Stone** at Maxwell to unlock a Power. One Power is selected at a time. Values below are
the stat bonus **at 250 AP**; scale by `M(AP) / M(250)` (= M(AP)/9.642) for other totals. The "unique bonus" is flat
and does not scale.

| Stone | Power | Stats @250 AP | Unique bonus | Obtained | Req |
|---|---|---|---|---|---|
| Displaced Leech | Sanguisuge | CC 9.26, CD 46.28, HP 32.4, Str 138.85 | +100 Int | 16 Ench. Bone + Hemoglass (Rift) | Combat 10 |
| Ender Monocle | Sighted | Int 347.11 | +3 AbilDmg | Seers (End) | Combat 15 |
| Luxurious Spool | Silky | Spd 5.79, CD 219.84 | +5 AtkSpd | Arachne / Broodmother | Combat 15 |
| Rock Candy | Sweet | Spd 11.57, Def 104.13, HP 145.79 | +5 Spd | Spooky mobs | Combat 15 |
| Acacia Birdhouse | Forceful | CD 46.28, Str 173.56, HP 16.2 | +4 Fer | 64 Ench. Acacia Log | Combat 20 |
| Beating Heart | Bloody | CD 104.13, Str 104.13, Int 34.71 | +10 AtkSpd | Blood room mobs / bats | Combat 20 |
| Dark Orb | Shaded | Spd 5.79, CD 173.56, Str 46.28 | +3 AtkSpd, +3 Fer | F5 chests | Combat 20 |
| End Stone Shulker | Adept | Def 92.56, Int 34.71, HP 161.99 | +100 HP, +50 Def | Ender Nodes | Combat 20 |
| Fang-tastic Chocolate Chip | Crumbly | HP 97.19, CC 4.63, AtkSpd 6.94, Int 52.07, TD 15.74 | +25 Spd | Riftstalker Bloodfiend | Combat 20 |
| Furball | Itchy | Spd 5.79, CD 80.99, Str 69.42, AtkSpd 20.83 | +15 Str, +15 CD | Sven T3+ | Combat 20 |
| Glacite Chunk | Frozen | TD 17.7, Def 127.27 | +10 TD, +25 CD, +25 Str | Glacite Mineshaft mobs | Combat 20 |
| Obsidian Tablet | Mythical | Def 39.34, HP 55.08, CD 39.34, Spd 9.26, Str 39.34, Int 59.01, CC 15.74 | +40 Str, +150 HP | 24 Ench. Obsidian + Summoning Eye | Combat 20 |
| Sunflower Butter | Buttery | Spd 23.14, AtkSpd 20.83 | +5 AtkSpd | recipe | Combat 20 |
| Bubba Blister | Bubba | Def −92.56, HP 48.6, CD 104.13, TD 7.87, Str 46.28, AtkSpd 17.36, CC 9.26 | +2 Combat Wisdom | Riftstalker | Combat 25 |
| Eccentric Painting | Bizarre | CD −23.14, Int 416.54, Str −23.14 | +5 AbilDmg | Dark Auction | Combat 25 |
| Horns of Torment | Demonic | Str 53.22, Int 267.28 | +50 CD | 24 Ench. Mutton + 32 Horn of Taurus | Combat 25 |
| Magma Urchin | Hurtful | Str 46.28, CD 185.13 | +15 AtkSpd | Crimson Isle minibosses | Combat 25 |
| Precious Pearl | Pleasant | HP 129.59, Def 138.85 | — | Nucleus Bundle | Combat 25 |
| Hazmat Enderman | Slender | CD 57.85, HP 80.99, AtkSpd 10.41, Spd 5.79, Int 86.78, Str 57.85, Def 57.85 | +50 Str, +100 Def | Voidgloom T3+ | Combat 30 |
| Mandraa | Strong | Str 115.7, CD 115.7 | +25 Str, +25 CD | Kuudra Burning+ | Combat 30 |
| Vitamin Death | Healthy | HP 323.97 | +200 HP | Healthy mobs (Catacombs) | Combat 30 |
| Scorched Books | Scorching | CD 92.56, Str 80.99, AtkSpd 17.36 | +7 Fer | Inferno Demonlord T2+ | Combat 35 |

Starter powers that need no stone also exist (Protected, Simple, Warrior, Commando, Disciplined, Inspired, Ominous,
Prepared, Pretty, Fortuitous, Bizarre-like) unlocked by Combat level / free (?) — numbers not verified.

### 7.5 Notable accessories (curated)

Full list (423 rows, with effect text, requirement and recipe) is **Appendix C**. Highlights by purpose:

| Accessory | Family tiers | Effect | Source |
|---|---|---|---|
| Speed Talisman → Ring → Artifact → Relic | C/U/R/E | +1/+3/+5/+? Speed (?) | Sugar Cane collection |
| Zombie Talisman → Ring → Artifact | C/R/E | −5%/−10%/−15% dmg from zombies (?) | Rotten Flesh collection / Revenant |
| Wolf / Tarantula / Spider / Red Claw / Hunter families | various | slayer CD/HP/damage bonuses | Slayer drops |
| Feather Talisman → Ring → Artifact | C/U/R | fall damage reduction; Artifact +10 blocks, −15% dmg | Feather collection |
| Potion Affinity Talisman → Ring → Artifact | C/U/R | potion duration +10/25/50% (?) | Nether Wart collection |
| Intimidation Talisman → … Relic | C… | mobs ≤ level N don't target you (Artifact: ≤25) | Combat |
| Candy Talisman → … Relic | C… | candy drop +% during Spooky Festival | Spooky |
| Emerald Ring → Artifact | U/R | +X coins per minute (Artifact +20/min) | Emerald collection |
| Haste Ring | R | permanent Haste II, +10 MinSpd | 128 Ench. Cobblestone |
| Day / Night Crystal | R | +5 Str & Def during day / night | Quartz |
| Treasure Talisman → Ring → Artifact | R/E/L | +1%/+2%/+3% dungeon/treasure chests (?) | Dungeons |
| Bat Person Talisman → Ring → Artifact | C/U/R | +10 Def/Str/Int per summoned bat (Artifact) | Spooky Festival |
| Pocket Espresso Machine, Cat/ Lynx / Cheetah Talisman | — | ability CD, speed | various |
| Personal Compactor 4000/5000/6000/7000 | U→L | auto-compacts items into enchanted forms (1/3/7/12 slots (?)) | crafted |
| Personal Deletor 4000→7000 | U→L | auto-deletes chosen items | crafted |
| Hegemony Artifact | L | counts double AP | Dark Auction |
| Bits Talisman / Bits shop accessories | R | 10% chance double Bits | Bits |
| Burststopper Talisman / Artifact | R/E | incoming hits ≥50% HP ×0.95 | Blaze slayer |
| Blood God Crest, Ender Artifact, Wither Relic, Seal of the Family, Pandora's Box, Tiaras… | E–M | stat-heavy late accessories (?) | late content |
| Fermento Artifact | R | +30 to every crop fortune | Farming (Condensed Fermento) |
| Abicase | R | +1 AP per 2 Abiphone contacts | Abiphone shop |
| Rift Prism | R | free Rift entry; consume for permanent 11 AP | Rift |

### 7.6 Other equipment slots (Rift-era "equipment")

Four extra slots: **Necklace, Cloak, Belt, Gloves/Bracelet/Gauntlet**. Each item has stats and often a set-like
bonus with matching armor (e.g. Molten Necklace/Cloak/Belt/Bracelet for Crimson Isle, Lotus set for farming visitors,
Delirium / Vanquished / Thunderbolt / Dungeon "Adaptive Belt"). Full list with stats in **Appendix C2**.

---

## 8. Misc important items

| Item | Tier | What it does | Numbers | Source |
|---|---|---|---|---|
| Aspect of the End / Void | R / E | movement: Instant Transmission 8 blocks (+tuners), AOTV Etherwarp | AOTE 50 mana, AOTV 45 mana; Etherwarp (with Etherwarp Merger/Conduit) 57 blocks, 180 mana, 1 Soulflow; **Transmission Tuner** +1 range each, max 4 | craft / Enderman slayer |
| Etherwarp Conduit | E | standalone Ether Transmission | 57 blocks, 180 mana, 1 Soulflow; req Enderman 7 | 24 Null Ovoid + 16 Ref. Titanium |
| Grappling Hook | U | grapple movement | 2s cooldown | 3 Ench. String + 2 Stick |
| Jerry-chine Gun | E | movement + damage (self-knockback) | 500 dmg, +30 mana per consecutive shot | Jerry's Workshop |
| Spirit Leap | R | dungeons only: teleport to a teammate, 1s immunity | 5s CD | dungeons |
| Infinityboom TNT | E | breaks crypts/weak walls | 2s CD | dungeons |
| Bonzo's / Spirit Mask | R / E | cheat death (see armor) | 360s / 30s CD | F1 / F4 |
| Travel Scrolls (~40) | R / E | permanently unlock a `/warp` destination | e.g. Spider's Den: 128 Ench. Flint + 16 Ench. Ender Pearl; Crystal Hollows: 10h forge (48 Flawed + 80 Fine Ruby, 16 Ench. Pearl, 50k coins) | crafts / NPCs |
| Backpacks | U→L | Storage menu pages | Small 9 (80 Leather), Medium 18 (512 Leather + Small), Large 27 (48 Ench. Leather), Greater 36 (96 Ench. Leather), Jumbo 45 (Jumbo Upgrade) | craft |
| Ender Chest pages | — | 9 pages of 45 slots in `/storage` (unlocked with gems / upgrades (?)) | | |
| Sacks (67) | C→L | auto-store collection items; tiers Beginner/Small/Medium/Large/Enchanted | e.g. Small Mining Sack 8 Ench. Coal + Beginner sack | collections |
| Personal Compactor / Deletor | U→L | accessory: auto-compact / auto-delete | | crafted |
| Builder's Wand | L | extend connected block faces; internal storage | | Hub (Builder) (?) |
| Royal Pigeon | L | open Commissions anywhere | 5s CD | Dwarven Mines |
| Training Weights | U | hold to gain up to +50 Str (?) for 2 min after removal | | Hub |
| Recombobulator 3000 | L | +1 rarity, once per item | | Dungeons / Bits shop |
| Hot / Fuming Potato Book | E | +2 Dmg +2 Str (weapon) / +2 Def +4 HP (armor); 10 HPB + 5 FPB | | craft / dungeons |
| Kat Flower / Kat Bouquet | S | skip 1 / 5 days of Kat | | events |
| Pet Cake | C | +10,000 pet XP | | events |
| Fuel: Volta, Oil Barrel, Biofuel | R/U/R | +10,000 / +10,000 / +3,000 drill or machine fuel | | Dwarven / Garden |

---

## 9. How it connects

| This system | Feeds / is fed by | Concretely |
|---|---|---|
| Armor & weapon **requirements** | Skills (Combat/Mining/Farming/Fishing/Foraging levels), Slayer levels, Catacombs level & floor completions, HotM tier, Garden level, Kuudra tier completion, Crimson Isle reputation, collections, Trophy Fishing tier | `requirements[]` on every item (types: SKILL, SLAYER, DUNGEON_SKILL, DUNGEON_TIER, HEART_OF_THE_MOUNTAIN, GARDEN_LEVEL, KUUDRA_COMPLETION, CRIMSON_ISLE_REPUTATION, COLLECTION, TROPHY_FISHING, TARGET_PRACTICE, ANY_OF/ONE_OF, CHOCOLATE_FACTORY, PROFILE_AGE…). An item you can't meet still exists in inventory but grants nothing / can't be used. |
| **Recipes** | Collections unlock recipes; Forge (Dwarven Mines) runs timed recipes; NPC shops; Kat; Roddy (rod parts); Drill Mechanic | Many end items are "N of the previous tier + upgrade material" chains (Fang → Foil → Sting, Falchion → Halberd, AOTE → AOTV, Rod of Champions → Legends → Sea). |
| **Drops** | Slayer bosses (RNG meter), Dungeon chests (F1–F7, M1–M7), Kuudra chests, Dragon fight loot, Dark Auction, Sea creatures, Mythological ritual, events | The best gear is almost never crafted from scratch — it is a rare drop plus a crafting step. |
| **Stats → damage** | Combat formula agent | Weapon Dmg + Str + CD + CC + AtkSpd + Fer from gear/pets/accessories/powers sum into player stats. Ability damage reads Int + AbilDmg%. |
| **Mana** | Int stat, Wise Dragon (2/3 cost), Sheep pet (−20%), mana regen | ability costs listed per weapon |
| **Soulflow** | Enderman slayer resource consumed by Katana Soulcry, Terminator Salvation, Etherwarp, Gyrokinetic Wand | Raw Soulflow → Soulflow in accessory "Soulflow Pile/Battery/Supercell" (?) |
| **Essence** | Dungeons (Wither/Undead/Dragon/Spider/Ice/Gold/Diamond), Kudra (Crimson) | stars on dungeon/Kuudra items |
| **Pets ↔ Skills** | Skill XP → pet XP; pet abilities buff skill stats (Fortune, Speed, Wisdom) | see §6.3 |
| **Accessories ↔ everything** | collections, slayers, dungeons, events, Rift, Bits shop, Abiphone | Accessory Power total drives the Power stat multiplier and tuning points; also SkyBlock XP |
| **Economy** | Auction House / Bazaar price everything above | Midas items literally scale with the coins paid; Emerald Blade with purse; Golden Dragon with bank balance |
| **Locations** | many items only work/2× in a place | Ender (End), Nutcracker (Workshop), Rampart (Crimson Isle), Treecapitator throw (Foraging Islands), Mithril Golem (mining islands), Crystal Armor (light level) |

---

## 10. Implementation notes

### 10.1 Item definition shape (static data)

Mirror the Hypixel API record and extend it with the fields the API leaves in lore. Keep it data-driven so 5,000+
items can be loaded from JSON.

```ts
type Rarity = "COMMON"|"UNCOMMON"|"RARE"|"EPIC"|"LEGENDARY"|"MYTHIC"|"DIVINE"|"SPECIAL"|"VERY_SPECIAL";
type Stat = "HEALTH"|"DEFENSE"|"STRENGTH"|"DAMAGE"|"CRITICAL_CHANCE"|"CRITICAL_DAMAGE"|"INTELLIGENCE"
  |"WALK_SPEED"|"ATTACK_SPEED"|"FEROCITY"|"MAGIC_FIND"|"TRUE_DEFENSE"|"ABILITY_DAMAGE_PERCENT"
  |"MINING_SPEED"|"MINING_FORTUNE"|"BREAKING_POWER"|"FARMING_FORTUNE"|"FORAGING_FORTUNE"|"SWEEP"
  |"SEA_CREATURE_CHANCE"|"FISHING_SPEED"|"VITALITY"|"MENDING"|"SWING_RANGE"|"PRISTINE"|"PET_LUCK"| string;

interface ItemDef {
  id: string;                    // "HYPERION"
  name: string;
  material: string;              // vanilla model / texture key
  category: "SWORD"|"BOW"|"WAND"|"HELMET"|...;
  subType?: "DAGGER"|"KATANA"|"SCYTHE"|"SHORTBOW"|"LONGSWORD";
  tier: Rarity;
  stats: Partial<Record<Stat, number>>;
  tieredStats?: Partial<Record<Stat, number[]>>;   // dungeon quality rolls (min..max)
  requirements?: Requirement[];  // see §9
  dungeonItem?: boolean;         // can be starred, gets Dungeon Stat Boost
  upgradeCosts?: Cost[][];       // per star, e.g. [[{essence:"WITHER",amount:150}], ...]
  gemstoneSlots?: { type: "RUBY"|"SAPPHIRE"|"COMBAT"|"MINING"|..., unlockCost?: Cost[] }[];
  abilities?: AbilityDef[];
  setId?: string;                // "POWER_WITHER" — joins pieces into a set
  passives?: PassiveDef[];       // "+50% dmg vs Wither", "+1 Dmg per Cata level", "2x stats in The End"
  recipe?: CraftRecipe | ForgeRecipe | ShopRecipe;
  soulbound?: "SOLO"|"COOP";
  museum?: { category: string; donationXp: number };
  npcSellPrice?: number;
  accessory?: { family?: string; familyRank?: number; accessoryPower?: number /* derived from tier */ };
  drill?: { fuelCapacity: number; partSlots: ("FUEL_TANK"|"ENGINE"|"MODULE")[] };
  rod?: { lava?: boolean; partSlots: ("HOOK"|"LINE"|"SINKER")[] };
}
```

### 10.2 Item instance shape (per owned item, the NBT `ExtraAttributes` equivalent)

```ts
interface ItemInstance {
  uuid: string; id: string;
  rarityUpgrades?: 0|1;          // recombobulated
  reforge?: string;              // "fabled"
  enchantments?: Record<string, number>;
  hotPotatoCount?: number;       // 0..15 (10 HPB + 5 FPB)
  stars?: number;                // 0..10 (5 normal + 5 master)
  gems?: Record<string, { type: string; quality: "ROUGH"|"FLAWED"|"FINE"|"FLAWLESS"|"PERFECT" }>;
  kuudraTier?: "BASIC"|"HOT"|"BURNING"|"FIERY"|"INFERNAL";
  scrolls?: string[];            // ["IMPLOSION_SCROLL","SHADOW_WARP_SCROLL","WITHER_SHIELD_SCROLL"]
  drillParts?: { tank?: string; engine?: string; module?: string }; fuel?: number;
  rodParts?: { hook?: string; line?: string; sinker?: string };
  counters?: Record<string, number>; // kills, logs cut, blocks mined, crops broken — many passives read these
  enrichment?: string; artOfWar?: boolean;
  dungeonQuality?: number; baseStatBoostPercent?: number; // dungeon drops
  transmissionTuners?: number; ethermerge?: boolean;
  donatedMuseum?: boolean; originTag?: string;
}
```

Stat pipeline per item (order matters):
`base stats (or tieredStats roll) → Kuudra tier multiplier → + reforge(rarity) → + hot potato → + gems → + enchant
flat stats → × star multiplier (dungeon only, inside dungeon) → × location multiplier (Ender ×2 in End etc.) →
+ passives that read counters`. Then sum all items + pet + accessories + power + tuning + skills/slayers into the
player stat block, then apply percentage modifiers (Superior +5%, Ender Dragon +10%, Renowned, etc.).

### 10.3 Abilities

```ts
interface AbilityDef {
  id: string;                     // "INSTANT_TRANSMISSION"
  name: string;
  trigger: "RIGHT_CLICK"|"LEFT_CLICK"|"SNEAK"|"SNEAK_RIGHT_CLICK"|"ON_HIT"|"ON_KILL"|"ON_SHOOT"|"PASSIVE"|"FULL_SET";
  manaCost?: number; manaCostPctOfMax?: number;   // Staff of Rising Sun: 2% of total mana
  healthCost?: number;                             // Wand of Strength 10% HP, Ragnarock channel…
  soulflowCost?: number; coinCost?: number;        // Terminator Salvation 1; Alchemist's Staff 100,000 coins
  cooldown?: number;                               // seconds
  baseDamage?: number; scaling?: number;           // ability_damage_scaling (0.05..0.3)
  params?: Record<string, number>;                 // range, duration, radius, pierce, stacks…
  script: string;                                  // key into an effect registry
}
```
- Ability damage = `baseDamage × (1 + Int/100 × scaling) × (1 + AbilDmg%/100) × multipliers` and **cannot crit**
  unless the ability says so (Terminator beam "always crits"). (Exact multiplier stacking belongs to the combat
  formula doc.)
- Mana cost modifiers multiply: Wise Dragon ×2/3, Sheep −20% (L), Wise Blood etc.
- Escalating costs need per-player transient state: Jerry-chine (+30 mana per shot, reset 4s), Halberd throw (×2
  per consecutive throw up to 16×), Eye Beam (+25% mana/s), Sinseeker (1.5× on recast).
- Stack systems (Kuudra Dominus/Arcane Energy/Hydra Strike/Spirit, Tarantula Octodexterity 4th hit, Phoenix 4th
  hit, Golem/Phoenix cheat-death cooldowns) → a generic `StackTracker {stacks, max, gainEvery, decayAfter}` per
  player per bonus.
- Cheat-death effects (Bonzo Mask, Spirit Mask, Phoenix Rekindle, Golem Last Stand) are evaluated in a single
  "lethal damage" hook with a priority order and their own cooldowns.

### 10.4 Set bonuses

```ts
interface SetDef {
  id: string;                       // "SUPERIOR_DRAGON"
  pieces: string[];                 // item ids; Tuxedo/Revenant have 3
  bonuses: {
    kind: "FULL"|"TIERED"|"PIECE";
    piecesRequired?: number;        // FULL: 4 (or 3); TIERED: scales with count
    perPieceValues?: number[];      // TIERED: value at 1..4 pieces (Kuudra, farming sets)
    effect: string;                 // effect registry key
    params: Record<string, number>; // e.g. { combatStatPct: 5, mfPct: 5, aotdBonus: 50 }
  }[];
}
```
Evaluate equipped armor once per equip change; cache which set bonuses are active. Some bonuses read *another*
slot (Zombie Knight sword + armor, Pooch Sword + Pack/Mastiff, Starlight armor + Starlight Wand, Aurora Staff +
Aurora armor, Hollow Wand + Hollow armor stacks, Blaze pet ×1.4 Blaze armor, Zombie pet +25% Undead armor stats).
Model those as `requires: {slot, itemOrSet}` conditions on the bonus.

### 10.5 Pets

```ts
interface PetDef {
  type: string; skill: "COMBAT"|"MINING"|...; rarities: Rarity[]; maxLevel: 100|200;
  statsByRarity: Record<Rarity, { lvl1: Partial<Record<Stat,number>>; lvl100: Partial<Record<Stat,number>> }>;
  abilities: { name: string; minRarity: Rarity; numbersByRarity: Record<Rarity, number[]>; script: string }[];
  katUpgrades?: { from: Rarity; to: Rarity; coins: number; seconds: number; items: Cost[] }[];
}
```
`level = levelFromXp(exp, offset(rarity))` using the §6.2 table. Interpolate stats/ability numbers linearly
between lvl1 and lvl100 (and 100→200 for dragons). Pet item modifiers apply after interpolation (Minos/Hephaestus
Relic +33.3%/+50% of pet stats, Textbook ×2 Int, Gold Claws ×1.5 CC/CD).
Appendix F was generated straight from NEU `petnums.json`, which already has `lvl1`/`lvl100` per rarity — import
that file rather than re-typing numbers.

### 10.6 Accessories

```ts
interface AccessoryState { owned: ItemInstance[]; selectedPower: string; tuning: Record<Stat, number>; }
function activeAccessories(bag) { // one per family, highest rank, ignore duplicates
  group by family → keep max(familyRank) → drop non-accessories }
function accessoryPower(active) { Σ AP[tier(item after recomb)] ; Hegemony ×2 ; Abicase floor(contacts/2) ;
  + rift prism 11 if consumed ; ×2 for dungeon accessories while in dungeon }
statsFromPower = POWER_TABLE[selectedPower].stats × M(AP)/M(250)  + unique bonus (flat)
tuningPoints = floor(AP / 10)
```

### 10.7 Useful raw sources for the importer

- `https://api.hypixel.net/v2/resources/skyblock/items` — 5,655 items: `id, name, material, tier, category,
  stats, tiered_stats, requirements, catacombs_requirements, upgrade_costs, gemstone_slots, dungeon_item,
  ability_damage_scaling, sword_type, museum_data.armor_set_donation_xp (gives the SET KEY for armor)`.
- `NotEnoughUpdates-REPO/items/<ID>.json` — lore (ability text), `recipe` (A1..C3 grid), `recipes[]`
  (`forge` with duration seconds, `npc_shop`, `katgrade` with coins/time/items, `trade`, `drops`).
- `NotEnoughUpdates-REPO/constants/petnums.json` (pet stat scaling), `pets.json` (level table, rarity offsets,
  custom leveling), `misc.json` (`talisman_upgrades` = accessory families, `base_stats`), `bonuses.json`
  (pet score → MF, skill/slayer stat rewards), `essencecosts.json`, `reforgestones.json`, `gemstones.json`.

---

## 11. Progression placement

| Phase in the clone | Systems that must exist | Items to ship in that phase |
|---|---|---|
| **P0 – Hub & private island** | basic stats, rarity, simple abilities (mana), shops, crafting grid, collections | vanilla tools/weapons, Rogue/Undead/Spider/End swords, Rookie tools, Leaflet/Farm Suit/Mushroom/Cactus/Lapis/Miner's/Golem/Hardened Diamond/Speedster/Emerald armor, Celeste/Starlight/Squire/Mercenary shop sets, starter talismans (Speed, Zombie, Skeleton, Village, Mine/Farming affinity, Potion affinity, Feather), Small/Medium backpacks, Beginner sacks, Grappling Hook |
| **P1 – Skills & islands** | set bonuses (FULL/PIECE), pets (level curve, Kat), accessory bag + families + AP, travel scrolls | AOTE, Raider/Tactician, Ink Wand, Golem Sword, Hurricane/Explosive/Savanna/Magma/Slime bows, Treecapitator, Lapis/Promising/Diamond picks, Challenging→Champions rods, Angler/Backwater/Salmon/Sponge armor, Ember/Crystal/Spooky/Bat Person, common pets (Rabbit, Chicken, Pig, Bee, Wolf, Tiger, Lion, Squid, Silverfish, Ocelot, Monkey, Blue Whale, Sheep), pet items (Exp boosts, claws, teeth, scales) |
| **P2 – The End & Slayers** | Dragon fight loot, slayer RNG meters, stack/passive tracker, Soulflow | Ender armor, 8 Dragon sets, AOTD, Ender Bow, Revenant/Tarantula/Mastiff/Final Destination/Reaper armor, slayer weapon ladders (Falchion→Halberd, Fang→Sting, Shaman→Pooch, Katanas, Blaze daggers), Wand of Healing ladder, slayer accessories, Ender Dragon/Enderman/Ghoul pets |
| **P3 – Dungeons** | dungeon items (stars, essence, quality rolls, Dungeon Stat Boost), class bonuses, scrolls, Recombobulator, Hot/Fuming books | F1–F7 sets & weapons (§3.2 / §4.2), Bonzo/Spirit masks, Spirit Sceptre, Livid Dagger, Shadow Fury, Giant's Sword, Necron/Storm/Goldor/Maxor, Necron's Blade → Hyperion/Valkyrie/Astraea/Scylla, Terminator/Juju, Bonemerang, Last Breath, Wither Cloak, Spirit pet, Treasure talismans |
| **P4 – Dwarven Mines / Crystal Hollows / Glacite** | HotM, Forge timers, drills with parts & fuel, gemstones & gem slots, chisels | Mithril/Titanium pickaxes, all drills & parts, Goblin/Heat/Yog/Flamebreaker/Glacite/Mineral/Divan's/Sorrow armor, Mithril Golem/Scatha/Bal/Mole/Glacite Golem pets, Royal Pigeon, gemstone sacks |
| **P5 – Crimson Isle & Kuudra** | 5-tier Kuudra armor upgrades, attunement daggers, lava fishing, trophy fishing | Crimson/Aurora/Terror/Fervor/Hollow × 5 tiers, Aurora Staff, Hollow Wand, Fire Veil/Fury/Freeze, Staff of the Volcano, Tormentor, lava rods, Thunder/Magma Lord, Trophy Hunter armor, Burststopper, Kuudra pet |
| **P6 – Garden** | Garden level, visitors, copper, crop tools Mk I–III, pests | Theoretical hoes & dicers, Melon→Cropie→Squash→Fermento, vacuums, watering cans, Elephant/Mooshroom/Rabbit upgrades, Fermento Artifact |
| **P7 – Endgame & events** | Master Mode (master stars), Dark Auction, Mythological Ritual, Rift (enrichments, Motes), power stones/Maxwell powers, Jacobus slots, Hegemony | Midas Sword/Staff, Hegemony, Golden Dragon (Lv200), Griffin + Daedalus Blade, Tuxedo sets, Tiaras/relics, all power stones, enrichments, Infernal Kuudra gear, 10-star dungeon gear |

---

# Appendices (generated from live data)

- **A** — every armor piece grouped into sets: tier, requirement, set total, per-piece stats (API).
- **A2** — set bonus text + per-piece recipe/forge for every multi-piece set (NEU lore).
- **B** — every sword, longsword, bow, arrow and wand: stats, requirement, recipe, ability text.
- **C** — every accessory (talisman/ring/artifact/relic): tier, stats, effect, requirement, recipe.
- **C2** — equipment (necklace / cloak / belt / gloves / bracelet / gauntlet).
- **D** — every pet item.
- **E** — Kat upgrade costs for every pet.
- **F** — every pet: type, rarities, Lv1→Lv100 stats at top rarity, every ability at Lv100 top rarity, and the
  per-rarity Lv100 numbers (ability numbers listed in the order the placeholders appear in the ability text).
- **G** — every tool (pickaxes, drills, axes, farming tools, rods, rod parts, vacuums, watering cans, chisels).

Notes on the generated tables: `Recipe "-"` means the item is a drop, a shop purchase whose NEU record lacks a
`recipes` entry, or an upgrade done by an NPC. Admin/test items (tier `X`/UNOBTAINABLE) were filtered out of
C/C2/D. Stats that show a range (`70-152`) are dungeon quality rolls. `AbilDmg(base)` is the API's
`WEAPON_ABILITY_DAMAGE` (the ability's base damage). Items prefixed `STARRED_` (Master Mode variants) were skipped in
B and A2 to avoid duplicates.


## Appendix A — All armor, grouped into sets (live API)

| Set | First piece ID | Tier | Pcs | Requirement | Full-set stat total | Per piece (H/C/L/B) |
|---|---|---|---|---|---|---|
| Abyssal | `ABYSSAL_HELMET` | L | 4 | Fishing 22 | HP +600, Def +600, PressRes +80, Resp +20, SCC +8 | H: HP +130, Def +120, PressRes +20, Resp +5, SCC +2 / C: HP +200, Def +200, PressRes +20, Resp +5, SCC +2 / L: HP +150, Def +170, PressRes +20, Resp +5, SCC +2 / B: HP +120, Def +110, PressRes +20, Resp +5, SCC +2 |
| Adaptive | `STARRED_ADAPTIVE_HELMET` | E | 4 | F3 done | Def +285, HP +555, Str +60, Int +60 | H: Def +60, HP +120, Str +15, Int +15 / C: Def +95, HP +180, Str +15, Int +15 / L: Def +75, HP +155, Str +15, Int +15 / B: Def +55, HP +100, Str +15, Int +15 |
| Adaptive | `ADAPTIVE_HELMET` | E | 4 | F3 done | Def +245, HP +515, Str +60, Int +60 | H: Def +50, HP +110, Str +15, Int +15 / C: Def +85, HP +170, Str +15, Int +15 / L: Def +65, HP +145, Str +15, Int +15 / B: Def +45, HP +90, Str +15, Int +15 |
| Agaricus Cap | `AGARICUS_CAP_CAP` | R | 1 | - | - | H: - |
| Angler | `ANGLER_HELMET` | ? | 4 | - | Def +105, SCC +4 | H: Def +20, SCC +1 / C: Def +40, SCC +1 / L: Def +30, SCC +1 / B: Def +15, SCC +1 |
| Anti-Bite Scarf | `ANTI_BITE_SCARF_2` | M | 1 | Vampire Slayer 4 | - | H: - |
| Anti-Bite Scarf | `ANTI_BITE_SCARF` | L | 1 | Vampire Slayer 1 | - | H: - |
| Arachne's | `ARACHNE_HELMET` | R | 4 | - | Def +180, HP +260 | H: Def +40, HP +55 / C: Def +60, HP +85 / L: Def +50, HP +75 / B: Def +30, HP +45 |
| Armor of Magma | `ARMOR_OF_MAGMA_HELMET` | E | 4 | - | Def +85, HP +270 | H: Def +15, HP +50 / C: Def +30, HP +100 / L: Def +25, HP +75 / B: Def +15, HP +45 |
| Aurora | `AURORA_HELMET` | L | 4 | Combat 22, Kuudra None | Def +210, HP +725, Int +800, AbilDmg% +16 | H: Def +50, HP +160, Int +275, AbilDmg% +16 / C: Def +65, HP +230, Int +175 / L: Def +55, HP +205, Int +175 / B: Def +40, HP +130, Int +175 |
| Backwater | `BACKWATER_HELMET` | U | 4 | Fishing 5 | HP +140, Def +70, SCC +8, Treasure +0.4 | H: HP +30, Def +15, SCC +2, Treasure +0.1 / C: HP +50, Def +25, SCC +2, Treasure +0.1 / L: HP +40, Def +20, SCC +2, Treasure +0.1 / B: HP +20, Def +10, SCC +2, Treasure +0.1 |
| Bat Person | `BAT_PERSON_HELMET` | L | 4 | - | Def +85 | H: Def +15 / C: Def +30 / L: Def +25 / B: Def +15 |
| Berserker | `BERSERKER_HELMET` | E | 4 | Combat 22 | Str +170, CD +170 | H: Str +35, CD +35 / C: Str +60, CD +60 / L: Str +50, CD +50 / B: Str +25, CD +25 |
| Big Spring | `BIG_SPRING_BOOTS` | ? | 1 | - | Def +10 | B: Def +10 |
| Bigger Spring | `BIGGER_SPRING_BOOTS` | ? | 1 | - | Def +10 | B: Def +10 |
| Biohazard | `BIOHAZARD_HELMET` | U | 4 | Garden 5 | HP +80, Def +50, PestCh +66 | H: HP +20, Def +10, PestCh +16.5 / C: HP +20, Def +20, PestCh +16.5 / L: HP +20, Def +15, PestCh +16.5 / B: HP +20, Def +5, PestCh +16.5 |
| Blaze | `BLAZE_HELMET` | E | 5 | Combat 22 | Spd +8, Def +465, Str +140 | H: Spd +2, Def +90, Str +30 / H: Str +20 / C: Spd +2, Def +165, Str +30 / L: Spd +2, Def +130, Str +30 / B: Spd +2, Def +80, Str +30 |
| Blobfish | `BLOBFISH_HAT` | U | 1 | - | Str +25, Int -10 | H: Str +25, Int -10 |
| Bonzo's | `STARRED_BONZO_MASK` | R | 1 | F1 done | Def +100, HP +125, Int +150 | H: Def +100, HP +125, Int +150 |
| Bonzo's | `BONZO_MASK` | R | 1 | F1 done | Def +100, HP +100, Int +100 | H: Def +100, HP +100, Int +100 |
| Bouncy | `BOUNCY_HELMET` | R | 4 | Cata 12 | - | H: HP 65-180, CC 5-10 / C: HP 120-333, CC 5-10 / L: HP 105-291, CC 5-10 / B: HP 55-153, CC 5-10 |
| Bronze Hunter | `BRONZE_HUNTER_HELMET` | U | 4 | Trophy Bronze | HP +250 | H: HP +50 / C: HP +100 / L: HP +75 / B: HP +25 |
| Burned | `BURNED_PANTS` | R | 1 | - | - | L: - |
| Burning Aurora | `BURNING_AURORA_HELMET` | L | 4 | Combat 32, Kuudra Burning | Def +333, HP +1150, Int +1271, AbilDmg% +25 | H: Def +79, HP +254, Int +437, AbilDmg% +25 / C: Def +103, HP +365, Int +278 / L: Def +87, HP +325, Int +278 / B: Def +64, HP +206, Int +278 |
| Burning Crimson | `BURNING_CRIMSON_HELMET` | L | 4 | Combat 32, Kuudra Burning | Def +333, HP +1150, Str +192, CD +128, Int +48 | H: Def +79, HP +254, Str +48, CD +32, Int +24 / C: Def +103, HP +365, Str +48, CD +32, Int +8 / L: Def +87, HP +325, Str +48, CD +32, Int +8 / B: Def +64, HP +206, Str +48, CD +32, Int +8 |
| Burning Fervor | `BURNING_FERVOR_HELMET` | L | 4 | Combat 32, Kuudra Burning | Def +699, HP +1294, Int +48, TrueDef +32 | H: Def +159, HP +286, Int +24, TrueDef +8 / C: Def +222, HP +421, Int +8, TrueDef +8 / L: Def +183, HP +333, Int +8, TrueDef +8 / B: Def +135, HP +254, Int +8, TrueDef +8 |
| Burning Hollow | `BURNING_HOLLOW_HELMET` | L | 4 | Combat 32, Kuudra Burning | Spd +32, Def +359, HP +1250, Int +611, Mend +32 | H: Spd +8, Def +86, HP +273, Int +206, Mend +8 / C: Spd +8, Def +109, HP +391, Int +135, Mend +8 / L: Spd +8, Def +94, HP +352, Int +135, Mend +8 / B: Spd +8, Def +70, HP +234, Int +135, Mend +8 |
| Burning Terror | `BURNING_TERROR_HELMET` | L | 4 | Combat 32, Kuudra Burning | Spd +76, Def +333, HP +1150, CD +316, Int +48 | H: Spd +19, Def +79, HP +254, CD +79, Int +24 / C: Spd +19, Def +103, HP +365, CD +79, Int +8 / L: Spd +19, Def +87, HP +325, CD +79, Int +8 / B: Spd +19, Def +64, HP +206, CD +79, Int +8 |
| Cactus | `CACTUS_HELMET` | ? | 4 | Farming 5 | Def +65, HP +35 | H: Def +10, HP +5 / C: Def +25, HP +15 / L: Def +20, HP +10 / B: Def +10, HP +5 |
| Canopy | `CANOPY_HELMET` | U | 4 | Foraging 12 | HP +340, Def +115, Sweep +24 | H: HP +60, Def +25, Sweep +6 / C: HP +120, Def +40, Sweep +6 / L: HP +110, Def +30, Sweep +6 / B: HP +50, Def +20, Sweep +6 |
| Celeste | `CELESTE_HELMET` | U | 4 | Combat 4 | Def +70, Int +150, HP +70 | H: Def +15, Int +30, HP +15 / C: Def +25, Int +60, HP +25 / L: Def +20, Int +40, HP +20 / B: Def +10, Int +20, HP +10 |
| Chainmail | `CHAINMAIL_HELMET` | U | 4 | - | Def +69 | H: Def +12 / C: Def +30 / L: Def +20 / B: Def +7 |
| Challenger's | `CHALLENGER_HELMET` | E | 4 | - | Str +60, HP +240, Def +130 | H: Str +10, HP +50, Def +25 / C: Str +25, HP +80, Def +40 / L: Str +20, HP +70, Def +35 / B: Str +5, HP +40, Def +30 |
| Cheap Tuxedo Jacket | `CHEAP_TUXEDO_CHESTPLATE` | E | 3 | - | CD +100, Int +100 | C: CD +50, Int +50 / L: CD +25, Int +25 / B: CD +25, Int +25 |
| Chicken | `CHICKEN_HEAD` | ? | 1 | - | HP +20 | H: HP +20 |
| Chicken Leggs | `CHICKEN_LEGGS` | R | 1 | - | - | L: - |
| Clover | `CLOVER_HELMET` | L | 1 | - | - | H: - |
| Cow | `COW_HEAD` | ? | 1 | - | Def +5, HP +10 | H: Def +5, HP +10 |
| Creeper | `CREEPER_HAT` | ? | 1 | - | Def +5, HP +5, Str +5, CD +5, CC +5, Int +5 | H: Def +5, HP +5, Str +5, CD +5, CC +5, Int +5 |
| Crimson | `CRIMSON_HELMET` | L | 4 | Combat 22, Kuudra None | Def +210, HP +725, Int +30, Str +120, CD +80 | H: Def +50, HP +160, Int +15, Str +30, CD +20 / C: Def +65, HP +230, Int +5, Str +30, CD +20 / L: Def +55, HP +205, Int +5, Str +30, CD +20 / B: Def +40, HP +130, Int +5, Str +30, CD +20 |
| Cropie | `CROPIE_HELMET` | U | 4 | Farming 30 | FarmFort +90, Spd +15, HP +550, Def +120, PestCh +50 | H: FarmFort +20, Spd +3, HP +110, Def +30, PestCh +12.5 / C: FarmFort +25, Spd +4, HP +165, Def +30, PestCh +12.5 / L: FarmFort +25, Spd +4, HP +165, Def +30, PestCh +12.5 / B: FarmFort +20, Spd +4, HP +110, Def +30, PestCh +12.5 |
| Crown of Avarice | `CROWN_OF_AVARICE` | L | 1 | - | Def +100, HP +200 | H: Def +100, HP +200 |
| Crown of Avarice | `CROWN_OF_AVARICE_CELEBRATION` | L | 1 | - | Def +100, HP +200 | H: Def +100, HP +200 |
| Crown of Greed | `CROWN_OF_GREED` | L | 1 | - | Def +50, HP +100 | H: Def +50, HP +100 |
| Crypt Witherlord | `CRYPT_WITHERLORD_HELMET` | E | 4 | Cata 18 | Spd +15, Def +280 | H: Spd 5-6, Def 70-80 / C: Spd +5, Def +120 / L: Spd +5, Def +95 / B: Spd +5, Def +65 |
| Crystal | `CRYSTAL_HELMET` | E | 4 | - | Def +100, Int +345 | H: Def +20, Int +65 / C: Def +35, Int +120 / L: Def +30, Int +100 / B: Def +15, Int +60 |
| Crystallized Heart | `CRYSTALLIZED_HEART` | R | 1 | Zombie Slayer 3, Cata 8 | Def +10, HP +50, Int +50, Vit +40, Mend +40 | H: Def +10, HP +50, Int +50, Vit +40, Mend +40 |
| Dark Goggles | `DARK_GOGGLES` | R | 1 | - | AbilDmg% +25, Int +150 | H: AbilDmg% +25, Int +150 |
| Dashing | `DASHING_BOOTS` | U | 1 | - | Spd +30, Def +1 | B: Spd +30, Def +1 |
| Deadgehog | `DEADGEHOG_HELMET` | R | 1 | - | Int +150 | H: Int +150 |
| Diamond | `DIAMOND_HELMET` | U | 4 | - | Def +100 | H: Def +15 / C: Def +40 / L: Def +30 / B: Def +15 |
| Diamond Bonzo Head | `DIAMOND_BONZO_HEAD` | S | 1 | F1 boss coll 1000 | Def +20, HP +50, Str +10 | H: Def +20, HP +50, Str +10 |
| Diamond Froggles | `FROGGLES_DIAMOND` | L | 1 | Fishing 25 | FishSpd +4 | H: FishSpd +4 |
| Diamond Hunter | `DIAMOND_HUNTER_HELMET` | L | 4 | Trophy Diamond | HP +550 | H: HP +125 / C: HP +175 / L: HP +150 / B: HP +100 |
| Diamond Livid Head | `DIAMOND_LIVID_HEAD` | S | 1 | F5 boss coll 1000 | Def +60, HP +160, Str +30 | H: Def +60, HP +160, Str +30 |
| Diamond Necron Head | `DIAMOND_NECRON_HEAD` | S | 1 | F7 boss coll 1000 | Def +80, HP +220, Str +40 | H: Def +80, HP +220, Str +40 |
| Diamond Professor Head | `DIAMOND_PROFESSOR_HEAD` | S | 1 | F3 boss coll 1000 | Def +40, HP +100, Str +20 | H: Def +40, HP +100, Str +20 |
| Diamond Sadan Head | `DIAMOND_SADAN_HEAD` | S | 1 | F6 boss coll 1000 | Def +70, HP +190, Str +35 | H: Def +70, HP +190, Str +35 |
| Diamond Scarf Head | `DIAMOND_SCARF_HEAD` | S | 1 | F2 boss coll 1000 | Def +30, HP +70, Str +15 | H: Def +30, HP +70, Str +15 |
| Diamond Thorn Head | `DIAMOND_THORN_HEAD` | S | 1 | F4 boss coll 1000 | Def +50, HP +130, Str +25 | H: Def +50, HP +130, Str +25 |
| Diver's | `DIVER_HELMET` | E | 4 | Fishing 20 | HP +650, Def +280, SCC +8, PressRes +60, Resp +12 | H: HP +150, Def +60, SCC +2, PressRes +15, Resp +3 / C: HP +200, Def +100, SCC +2, PressRes +15, Resp +3 / L: HP +175, Def +80, SCC +2, PressRes +15, Resp +3 / B: HP +125, Def +40, SCC +2, PressRes +15, Resp +3 |
| Eleanor's | `ELEANOR_CAP` | L | 4 | - | HP +265 | H: HP +50 / C: HP +100 / L: HP +75 / B: HP +40 |
| Elegant Tuxedo Jacket | `ELEGANT_TUXEDO_CHESTPLATE` | L | 3 | - | CD +200, Int +500, Spd +10 | C: CD +100, Int +300 / L: CD +50, Int +100 / B: Spd +10, CD +50, Int +100 |
| Ember | `EMBER_HELMET` | E | 4 | Combat 8 | Def +180, HP +200, Int +25 | H: Def +35, HP +40, Int +5 / C: Def +60, HP +65, Int +10 / L: Def +55, HP +60, Int +5 / B: Def +30, HP +35, Int +5 |
| Ember Ash | `FLAMEBREAKER_HELMET` | U | 4 | Hard_Stone coll 5 | MinFort +20, MinSpd +60 | H: MinFort +5, MinSpd +15 / C: MinFort +5, MinSpd +15 / L: MinFort +5, MinSpd +15 / B: MinFort +5, MinSpd +15 |
| Emerald Armor | `EMERALD_ARMOR_HELMET` | E | 4 | - | Def +270 | H: Def +50 / C: Def +100 / L: Def +75 / B: Def +45 |
| Emperor Head | `EMPEROR_HEAD` | U | 3 | - | - | H: - / L: - / B: - |
| Emperor Robes | `EMPEROR_ROBES` | ? | 1 | - | Def +500 | C: Def +500 |
| Ender | `END_HELMET` | E | 4 | Combat 12 | Def +170, HP +90 | H: Def +35, HP +20 / C: Def +60, HP +30 / L: Def +50, HP +25 / B: Def +25, HP +15 |
| Enderman | `ENDERMAN_HAT` | ? | 2 | - | Str +31, Def +1, HP +1, Int -99 | H: Str +30 / H: Def +1, HP +1, Str +1, Int -99 |
| etalptsehC norI | `ETALPTSEHC_NORI` | R | 1 | - | - | C: - |
| Exceedingly Comfy Sneakers | `EXCEEDINGLY_COMFY_SNEAKERS` | E | 1 | - | - | B: - |
| Fairy's Fedora | `FAIRY_HELMET` | R | 4 | - | Spd +40, Def +4, HP +4, Int -4 | H: Spd +10, Def +1, HP +1, Int -1 / C: Spd +10, Def +1, HP +1, Int -1 / L: Spd +10, Def +1, HP +1, Int -1 / B: Spd +10, Def +1, HP +1, Int -1 |
| Fallen Star | `FALLEN_STAR_HAT` | R | 4 | - | Def +30, HP +50, Int +2 | H: Def +30, HP +50, Int +2 / C: - / C: - / C: - |
| Fancy Tuxedo Jacket | `FANCY_TUXEDO_CHESTPLATE` | L | 3 | - | CD +150, Int +300, Spd +10 | C: CD +80, Int +150 / L: CD +35, Int +75 / B: Spd +10, CD +35, Int +75 |
| Farmer | `FARMER_BOOTS` | U | 1 | Farming 18 | Spd +10, Def +20, HP +40 | B: Spd +10, Def +20, HP +40 |
| Farmhand | `FARM_SUIT_HELMET` | ? | 4 | Farming 3 | Def +40, FarmFort +20 | H: Def +10, FarmFort +5 / C: Def +10, FarmFort +5 / L: Def +10, FarmFort +5 / B: Def +10, FarmFort +5 |
| Femurgrowth | `FEMURGROWTH_LEGGINGS` | E | 1 | - | - | L: - |
| Fermento | `FERMENTO_HELMET` | E | 4 | Farming 40 | FarmFort +130, Spd +22, HP +650, Def +160, PestCh +70 | H: FarmFort +30, Spd +5, HP +130, Def +40, PestCh +17.5 / C: FarmFort +35, Spd +6, HP +195, Def +40, PestCh +17.5 / L: FarmFort +35, Spd +6, HP +195, Def +40, PestCh +17.5 / B: FarmFort +30, Spd +5, HP +130, Def +40, PestCh +17.5 |
| Fervor | `FERVOR_HELMET` | L | 4 | Combat 22, Kuudra None | Def +440, HP +815, Int +30, TrueDef +20 | H: Def +100, HP +180, Int +15, TrueDef +5 / C: Def +140, HP +265, Int +5, TrueDef +5 / L: Def +115, HP +210, Int +5, TrueDef +5 / B: Def +85, HP +160, Int +5, TrueDef +5 |
| Fiery Aurora | `FIERY_AURORA_HELMET` | L | 4 | Combat 37, Kuudra Fiery | Def +420, HP +1450, Int +1600, AbilDmg% +32 | H: Def +100, HP +320, Int +550, AbilDmg% +32 / C: Def +130, HP +460, Int +350 / L: Def +110, HP +410, Int +350 / B: Def +80, HP +260, Int +350 |
| Fiery Crimson | `FIERY_CRIMSON_HELMET` | L | 4 | Combat 37, Kuudra Fiery | Def +420, HP +1450, Str +240, CD +160, Int +60 | H: Def +100, HP +320, Str +60, CD +40, Int +30 / C: Def +130, HP +460, Str +60, CD +40, Int +10 / L: Def +110, HP +410, Str +60, CD +40, Int +10 / B: Def +80, HP +260, Str +60, CD +40, Int +10 |
| Fiery Fervor | `FIERY_FERVOR_HELMET` | L | 4 | Combat 37, Kuudra Fiery | Def +880, HP +1630, Int +60, TrueDef +40 | H: Def +200, HP +360, Int +30, TrueDef +10 / C: Def +280, HP +530, Int +10, TrueDef +10 / L: Def +230, HP +420, Int +10, TrueDef +10 / B: Def +170, HP +320, Int +10, TrueDef +10 |
| Fiery Hollow | `FIERY_HOLLOW_HELMET` | L | 4 | Combat 37, Kuudra Fiery | Spd +40, Def +449, HP +1562, Int +770, Mend +40 | H: Spd +10, Def +107, HP +342, Int +260, Mend +10 / C: Spd +10, Def +137, HP +488, Int +170, Mend +10 / L: Spd +10, Def +117, HP +439, Int +170, Mend +10 / B: Spd +10, Def +88, HP +293, Int +170, Mend +10 |
| Fiery Terror | `FIERY_TERROR_HELMET` | L | 4 | Combat 37, Kuudra Fiery | Spd +96, Def +420, HP +1450, CD +400, Int +60 | H: Spd +24, Def +100, HP +320, CD +100, Int +30 / C: Spd +24, Def +130, HP +460, CD +100, Int +10 / L: Spd +24, Def +110, HP +410, CD +100, Int +10 / B: Spd +24, Def +80, HP +260, CD +100, Int +10 |
| Fig | `FIG_HELMET` | R | 4 | Foraging 15 | HP +445, Def +135, ForFort +120, Sweep +40 | H: HP +80, Def +30, ForFort +30, Sweep +10 / C: HP +160, Def +45, ForFort +30, Sweep +10 / L: HP +140, Def +35, ForFort +30, Sweep +10 / B: HP +65, Def +25, ForFort +30, Sweep +10 |
| Final Destination | `FINAL_DESTINATION_HELMET` | L | 4 | Enderman Slayer 3 | Def +400, HP +600, Int +400 | H: Def +100, HP +140, Int +100 / C: Def +100, HP +200, Int +100 / L: Def +100, HP +160, Int +100 / B: Def +100, HP +100, Int +100 |
| Fish | `FISH_HAT` | ? | 1 | - | HP +5 | H: HP +5 |
| Flamebreaker | `FLAME_BREAKER_HELMET` | E | 4 | Hard_Stone coll 5 | Def +205, HP +205, MinFort +60, MinSpd +100, HeatRes +20 | H: Def +40, HP +40, MinFort +15, MinSpd +25, HeatRes +5 / C: Def +70, HP +70, MinFort +15, MinSpd +25, HeatRes +5 / L: Def +60, HP +60, MinFort +15, MinSpd +25, HeatRes +5 / B: Def +35, HP +35, MinFort +15, MinSpd +25, HeatRes +5 |
| Flex | `FLEX_HELMET` | L | 1 | - | - | H: - |
| Froggles | `FROGGLES` | U | 1 | Fishing 5 | FishSpd +1 | H: FishSpd +1 |
| Frozen Blaze | `FROZEN_BLAZE_HELMET` | L | 4 | Combat 22, Cata 20 | Spd +8, Def +520, Str +160 | H: Spd +2, Def +105, Str +40 / C: Spd +2, Def +180, Str +40 / L: Spd +2, Def +140, Str +40 / B: Spd +2, Def +95, Str +40 |
| Ghast Head | `GHAST_HEAD` | ? | 4 | - | HP +550 | H: HP +100 / C: HP +200 / L: HP +150 / B: HP +100 |
| Ghostly | `GHOST_BOOTS` | R | 1 | - | - | B: - |
| Glacite | `GLACITE_HELMET` | E | 4 | - | Spd +50, Def +415, TrueDef +30, MinSpd +40, MinFort +20 | H: Spd +10, Def +70, TrueDef +5, MinSpd +10, MinFort +5 / C: Spd +15, Def +150, TrueDef +10, MinSpd +10, MinFort +5 / L: Spd +15, Def +125, TrueDef +10, MinSpd +10, MinFort +5 / B: Spd +10, Def +70, TrueDef +5, MinSpd +10, MinFort +5 |
| Glossy Mineral | `GLOSSY_MINERAL_HELMET` | L | 4 | - | Spd +90, Def +480, BlockFort +200 | H: Spd +20, Def +90, BlockFort +50 / C: Spd +25, Def +150, BlockFort +50 / L: Spd +25, Def +150, BlockFort +50 / B: Spd +20, Def +90, BlockFort +50 |
| Goblin | `GOBLIN_HELMET` | R | 4 | - | HP +395, Int -4, MinSpd +40, MinFort +16 | H: HP +70, Int -1, MinSpd +10, MinFort +4 / C: HP +140, Int -1, MinSpd +10, MinFort +4 / L: HP +125, Int -1, MinSpd +10, MinFort +4 / B: HP +60, Int -1, MinSpd +10, MinFort +4 |
| Gold Hunter | `GOLD_HUNTER_HELMET` | E | 4 | Trophy Gold | HP +450 | H: HP +100 / C: HP +150 / L: HP +125 / B: HP +75 |
| Golden | `GOLD_HELMET` | ? | 4 | - | Def +55 | H: Def +10 / C: Def +25 / L: Def +15 / B: Def +5 |
| Golden Bonzo Head | `GOLD_BONZO_HEAD` | S | 1 | F1 done | Def +10, HP +20, Str +5 | H: Def +10, HP +20, Str +5 |
| Golden Froggles | `FROGGLES_GOLD` | E | 1 | Fishing 15 | FishSpd +3 | H: FishSpd +3 |
| Golden Livid Head | `GOLD_LIVID_HEAD` | S | 1 | F5 done | Def +30, HP +60, Str +15 | H: Def +30, HP +60, Str +15 |
| Golden Necron Head | `GOLD_NECRON_HEAD` | S | 1 | F7 done | Def +40, HP +80, Str +15 | H: Def +40, HP +80, Str +15 |
| Golden Professor Head | `GOLD_PROFESSOR_HEAD` | S | 1 | F3 done | Def +20, HP +40, Str +10 | H: Def +20, HP +40, Str +10 |
| Golden Sadan Head | `GOLD_SADAN_HEAD` | S | 1 | F6 done | Def +35, HP +70, Str +15 | H: Def +35, HP +70, Str +15 |
| Golden Scarf Head | `GOLD_SCARF_HEAD` | S | 1 | F2 done | Def +15, HP +30, Str +10 | H: Def +15, HP +30, Str +10 |
| Golden Thorn Head | `GOLD_THORN_HEAD` | S | 1 | F4 done | Def +25, HP +50, Str +15 | H: Def +25, HP +50, Str +15 |
| Goldor's | `TANK_WITHER_HELMET` | L | 4 | F7 done | Def +860, HP +970, Int +90 | H: Def +180, HP +210, Int +30 / C: Def +275, HP +310, Int +20 / L: Def +240, HP +260, Int +20 / B: Def +165, HP +190, Int +20 |
| Golem | `GOLEM_HAT` | ? | 1 | - | - | H: - |
| Golem Armor | `GOLEM_ARMOR_HELMET` | R | 4 | - | Def +250, HP +205 | H: Def +45, HP +45 / C: Def +90, HP +65 / L: Def +75, HP +55 / B: Def +40, HP +40 |
| Great Spook | `GREAT_SPOOK_HELMET` | E | 4 | - | Fear +20 | H: Fear +5 / C: Fear +5 / L: Fear +5 / B: Fear +5 |
| Gunther's Sneakers | `GUNTHER_SNEAKERS` | R | 1 | - | - | B: - |
| Happy | `HAPPY_MASK` | U | 1 | - | - | H: - |
| Hardened Diamond | `HARDENED_DIAMOND_HELMET` | R | 4 | - | Def +330 | H: Def +60 / C: Def +120 / L: Def +95 / B: Def +55 |
| Haymaker | `FARM_ARMOR_HELMET` | C | 4 | Farming 10 | Def +60, FarmFort +40 | H: Def +15, FarmFort +10 / C: Def +15, FarmFort +10 / L: Def +15, FarmFort +10 / B: Def +15, FarmFort +10 |
| Heat | `HEAT_HELMET` | R | 4 | Hard_Stone coll 2 | Def +200, HP +80, MinFort +40, MinSpd +60, Str +60, HeatRes +80 | H: Def +50, HP +20, MinFort +10, MinSpd +15, Str +15, HeatRes +20 / C: Def +50, HP +20, MinFort +10, MinSpd +15, Str +15, HeatRes +20 / L: Def +50, HP +20, MinFort +10, MinSpd +15, Str +15, HeatRes +20 / B: Def +50, HP +20, MinFort +10, MinSpd +15, Str +15, HeatRes +20 |
| Heavy | `HEAVY_HELMET` | R | 4 | Cata 2 | Spd -20 | H: Spd -5, Def 62-130 / C: Spd -5, Def 112-235 / L: Spd -5, Def 92-195 / B: Spd -5, Def 57-120 |
| Helianthus | `HELIANTHUS_HELMET` | L | 4 | Farming 50 | FarmFort +150, Spd +26, HP +690, Def +200, PestCh +80 | H: FarmFort +35, Spd +6, HP +140, Def +50, PestCh +20 / C: FarmFort +40, Spd +7, HP +205, Def +50, PestCh +20 / L: FarmFort +40, Spd +7, HP +205, Def +50, PestCh +20 / B: FarmFort +35, Spd +6, HP +140, Def +50, PestCh +20 |
| Helix | `HELIX_ARMOR_HELMET` | E | 4 | Foraging 25 | HP +540, Def +165, ForFort +180, Sweep +80 | H: HP +100, Def +35, ForFort +45, Sweep +20 / C: HP +190, Def +55, ForFort +45, Sweep +20 / L: HP +165, Def +45, ForFort +45, Sweep +20 / B: HP +85, Def +30, ForFort +45, Sweep +20 |
| Helmet of Divan | `DIVAN_HELMET` | L | 4 | HotM 6 | Def +540, HP +510, MinFort +120, MinSpd +320, HeatRes +40 | H: Def +130, HP +100, MinFort +30, MinSpd +80, HeatRes +10 / C: Def +130, HP +200, MinFort +30, MinSpd +80, HeatRes +10 / L: Def +170, HP +130, MinFort +30, MinSpd +80, HeatRes +10 / B: Def +110, HP +80, MinFort +30, MinSpd +80, HeatRes +10 |
| Helmet of Growth | `GROWTH_HELMET` | R | 4 | - | Def +145, HP +280 | H: Def +30, HP +50 / C: Def +50, HP +100 / L: Def +40, HP +80 / B: Def +25, HP +50 |
| Helmet of the Pack | `HELMET_OF_THE_PACK` | E | 4 | Wolf Slayer 6 | Def +280, HP +300 | H: Def +65, HP +70 / C: Def +95, HP +100 / L: Def +75, HP +80 / B: Def +45, HP +50 |
| Helmet of the Rising Sun | `GENERALS_ARMOR_OF_THE_RESISTANCE_HELMET` | E | 4 | - | Def +45, HP +45 | H: Def +10, HP +10 / C: Def +15, HP +15 / L: Def +12, HP +12 / B: Def +8, HP +8 |
| Helmet of the Rising Sun | `ARMOR_OF_THE_RESISTANCE_HELMET` | E | 4 | - | Def +45, HP +45 | H: Def +10, HP +10 / C: Def +15, HP +15 / L: Def +12, HP +12 / B: Def +8, HP +8 |
| Hollow | `HOLLOW_HELMET` | L | 4 | Combat 22, Kuudra None | Spd +20, Def +230, HP +800, Int +385, Mend +20 | H: Spd +5, Def +55, HP +175, Int +130, Mend +5 / C: Spd +5, Def +70, HP +250, Int +85, Mend +5 / L: Spd +5, Def +60, HP +225, Int +85, Mend +5 / B: Spd +5, Def +45, HP +150, Int +85, Mend +5 |
| Holy Dragon | `HOLY_DRAGON_HELMET` | L | 4 | Combat 16, Cata 10 | Def +500, HP +545, Mend +40 | H: Def +110, HP +110, Mend +10 / C: Def +160, HP +180, Mend +10 / L: Def +140, HP +155, Mend +10 / B: Def +90, HP +100, Mend +10 |
| Hot Aurora | `HOT_AURORA_HELMET` | L | 4 | Combat 27, Kuudra Hot | Def +264, HP +914, Int +1010, AbilDmg% +20 | H: Def +63, HP +202, Int +347, AbilDmg% +20 / C: Def +82, HP +290, Int +221 / L: Def +69, HP +258, Int +221 / B: Def +50, HP +164, Int +221 |
| Hot Crimson | `HOT_CRIMSON_HELMET` | L | 4 | Combat 27, Kuudra Hot | Def +264, HP +914, Str +152, CD +100, Int +37 | H: Def +63, HP +202, Str +38, CD +25, Int +19 / C: Def +82, HP +290, Str +38, CD +25, Int +6 / L: Def +69, HP +258, Str +38, CD +25, Int +6 / B: Def +50, HP +164, Str +38, CD +25, Int +6 |
| Hot Fervor | `HOT_FERVOR_HELMET` | L | 4 | Combat 27, Kuudra Hot | Def +554, HP +1028, Int +37, TrueDef +24 | H: Def +126, HP +227, Int +19, TrueDef +6 / C: Def +176, HP +334, Int +6, TrueDef +6 / L: Def +145, HP +265, Int +6, TrueDef +6 / B: Def +107, HP +202, Int +6, TrueDef +6 |
| Hot Hollow | `HOT_HOLLOW_HELMET` | L | 4 | Combat 27, Kuudra Hot | Spd +24, Def +288, HP +1001, Int +485, Mend +24 | H: Spd +6, Def +69, HP +219, Int +164, Mend +6 / C: Spd +6, Def +88, HP +313, Int +107, Mend +6 / L: Spd +6, Def +75, HP +281, Int +107, Mend +6 / B: Spd +6, Def +56, HP +188, Int +107, Mend +6 |
| Hot Terror | `HOT_TERROR_HELMET` | L | 4 | Combat 27, Kuudra Hot | Spd +60, Def +264, HP +914, CD +252, Int +37 | H: Spd +15, Def +63, HP +202, CD +63, Int +19 / C: Spd +15, Def +82, HP +290, CD +63, Int +6 / L: Spd +15, Def +69, HP +258, CD +63, Int +6 / B: Spd +15, Def +50, HP +164, CD +63, Int +6 |
| Infernal Aurora | `INFERNAL_AURORA_HELMET` | L | 4 | Combat 42, Kuudra Infernal | Def +530, HP +1828, Int +2016, AbilDmg% +40 | H: Def +126, HP +403, Int +693, AbilDmg% +40 / C: Def +164, HP +580, Int +441 / L: Def +139, HP +517, Int +441 / B: Def +101, HP +328, Int +441 |
| Infernal Crimson | `INFERNAL_CRIMSON_HELMET` | L | 4 | Combat 42, Kuudra Infernal | Def +530, HP +1828, Str +304, CD +200, Int +77 | H: Def +126, HP +403, Str +76, CD +50, Int +38 / C: Def +164, HP +580, Str +76, CD +50, Int +13 / L: Def +139, HP +517, Str +76, CD +50, Int +13 / B: Def +101, HP +328, Str +76, CD +50, Int +13 |
| Infernal Fervor | `INFERNAL_FERVOR_HELMET` | L | 4 | Combat 42, Kuudra Infernal | Def +1109, HP +2054, Int +77, TrueDef +48 | H: Def +252, HP +454, Int +38, TrueDef +12 / C: Def +353, HP +668, Int +13, TrueDef +12 / L: Def +290, HP +529, Int +13, TrueDef +12 / B: Def +214, HP +403, Int +13, TrueDef +12 |
| Infernal Hollow | `INFERNAL_HOLLOW_HELMET` | L | 4 | Combat 42, Kuudra Infernal | Spd +52, Def +561, HP +1952, Int +970, Mend +48 | H: Spd +13, Def +134, HP +427, Int +328, Mend +12 / C: Spd +13, Def +171, HP +610, Int +214, Mend +12 / L: Spd +13, Def +146, HP +549, Int +214, Mend +12 / B: Spd +13, Def +110, HP +366, Int +214, Mend +12 |
| Infernal Terror | `INFERNAL_TERROR_HELMET` | L | 4 | Combat 42, Kuudra Infernal | Spd +120, Def +530, HP +1828, CD +504, Int +77 | H: Spd +30, Def +126, HP +403, CD +126, Int +38 / C: Spd +30, Def +164, HP +580, CD +126, Int +13 / L: Spd +30, Def +139, HP +517, CD +126, Int +13 / B: Spd +30, Def +101, HP +328, CD +126, Int +13 |
| Iron | `IRON_HELMET` | ? | 4 | - | Def +77 | H: Def +12 / C: Def +30 / L: Def +25 / B: Def +10 |
| Kalhuiki | `KALHUIKI_MASK` | ? | 1 | - | Def +50 | H: Def +50, HP 100-10 |
| Krampus | `KRAMPUS_HELMET` | E | 1 | - | - | H: - |
| Lantern | `ENCHANTED_JACK_O_LANTERN` | U | 1 | Farming 15 | Def +10, HP +20 | H: Def +10, HP +20 |
| Lapis Armor | `LAPIS_ARMOR_HELMET` | U | 4 | Mining 5 | Def +120, MinSpd +80, MinFort +8 | H: Def +25, MinSpd +20, MinFort +2 / C: Def +40, MinSpd +20, MinFort +2 / L: Def +35, MinSpd +20, MinFort +2 / B: Def +20, MinSpd +20, MinFort +2 |
| Leaflet | `LEAFLET_HELMET` | ? | 4 | - | ForFort +12, HP +195 | H: ForFort +3, HP +70 / C: ForFort +3, HP +80 / L: ForFort +3, HP +20 / B: ForFort +3, HP +25 |
| Leather | `LEATHER_HELMET` | ? | 4 | - | Def +35 | H: Def +5 / C: Def +15 / L: Def +10 / B: Def +5 |
| Leggings of the Coven | `LEGGINGS_OF_THE_COVEN` | L | 1 | Vampire Slayer 3 | - | L: - |
| Lively Sepulture | `LIVELY_SEPULTURE_CHESTPLATE` | L | 1 | Vampire Slayer 5 | - | C: - |
| Magma Cube Head | `MAGMA_CUBE_HEAD` | ? | 1 | - | Def +25, HP +25 | H: Def +25, HP +25 |
| Magma Lord | `MAGMA_LORD_HELMET` | L | 4 | Fishing 45 | HP +950, Def +750, SCC +18, Str +40, CD +140 | H: HP +225, Def +175, SCC +4.5, Str +10, CD +35 / C: HP +275, Def +225, SCC +4.5, Str +10, CD +35 / L: HP +250, Def +200, SCC +4.5, Str +10, CD +35 / B: HP +200, Def +150, SCC +4.5, Str +10, CD +35 |
| Magmafish | `MAGMA_FISH_HAT` | U | 1 | - | Int +50 | H: Int +50 |
| Mastiff | `MASTIFF_HELMET` | E | 4 | Wolf Slayer 4 | HP +1400, Int +150, CD +60 | H: HP +300, Int +125, CD +15 / C: HP +500, CD +15 / L: HP +400, CD +15 / B: HP +200, Int +25, CD +15 |
| Maxor's | `SPEED_WITHER_HELMET` | L | 4 | F7 done | Spd +120, Def +370, HP +815, CD +180, Int +45 | H: Spd +30, Def +80, HP +180, CD +45, Int +15 / C: Spd +30, Def +120, HP +260, CD +45, Int +10 / L: Spd +30, Def +105, HP +230, CD +45, Int +10 / B: Spd +30, Def +65, HP +145, CD +45, Int +10 |
| Mender | `MENDER_HELMET` | R | 2 | - | HP +350 | H: HP +80 / H: HP +270 |
| Mender Fedora | `MENDER_FEDORA` | E | 1 | F3 done | HP +220 | H: HP +220 |
| Mercenary | `MERCENARY_HELMET` | R | 4 | Combat 8 | Def +200, HP +200, Str +40 | H: Def +40, HP +40, Str +10 / C: Def +70, HP +70, Str +10 / L: Def +60, HP +60, Str +10 / B: Def +30, HP +30, Str +10 |
| Metal | `METAL_CHESTPLATE` | E | 1 | F3 done | Def +230 | C: Def +230 |
| Miner | `TANK_MINER_HELMET` | R | 4 | - | Def +60, MinSpd +40 | H: Def +15, MinSpd +10 / C: Def +15, MinSpd +10 / L: Def +15, MinSpd +10 / B: Def +15, MinSpd +10 |
| Mineral | `MINERAL_HELMET` | E | 4 | - | Spd +50, Def +390, BlockFort +100 | H: Spd +10, Def +70, BlockFort +25 / C: Spd +15, Def +125, BlockFort +25 / L: Spd +15, Def +125, BlockFort +25 / B: Spd +10, Def +70, BlockFort +25 |
| Minos Hunter | `MINOS_HUNTER_HELMET` | U | 4 | - | - | H: - / C: - / L: - / B: - |
| Mithril Coat | `MITHRIL_COAT` | E | 1 | - | Spd +15, Def +125 | C: Spd +15, Def +125 |
| Mole | `MOLE_HAT` | C | 4 | - | HP +270, Def +75, ForFort +24 | H: HP +35, Def +10, ForFort +6 / C: HP +100, Def +30, ForFort +6 / L: HP +45, Def +15, ForFort +6 / B: HP +90, Def +20, ForFort +6 |
| Mushroom | `MUSHROOM_HELMET` | ? | 4 | Farming 7 | HP +55, Def +15 | H: HP +20 / C: Def +10, HP +10 / L: Def +5, HP +10 / B: HP +15 |
| Mythos | `MYTHOS_HELMET` | L | 4 | - | Str +120, HP +480, Def +260 | H: Str +20, HP +100, Def +50 / C: Str +50, HP +160, Def +80 / L: Str +40, HP +140, Def +70 / B: Str +10, HP +80, Def +60 |
| Necromancer Lord | `NECROMANCER_LORD_HELMET` | L | 4 | F6 done | Def +780, HP +930, Int +45 | H: Def +160, HP +200, Int +15 / C: Def +250, HP +300, Int +10 / L: Def +220, HP +250, Int +10 / B: Def +150, HP +180, Int +10 |
| Necron's | `POWER_WITHER_HELMET` | L | 4 | F7 done | Def +450, HP +815, Str +160, CD +120, Int +60 | H: Def +100, HP +180, Str +40, CD +30, Int +30 / C: Def +140, HP +260, Str +40, CD +30, Int +10 / L: Def +125, HP +230, Str +40, CD +30, Int +10 / B: Def +85, HP +145, Str +40, CD +30, Int +10 |
| Netherite | `NETHERITE_HELMET` | E | 1 | - | Def +40, HP +40, MinFort +10, MinSpd +25 | H: Def +40, HP +40, MinFort +10, MinSpd +25 |
| Not-Dead-Gehog | `NOT_DEADGEHOG_MASK` | U | 1 | - | - | H: - |
| Nutcracker | `NUTCRACKER_HELMET` | L | 4 | Fishing 28 | HP +355, Def +145, SCC +6 | H: HP +80, Def +35, SCC +1.5 / C: HP +115, Def +45, SCC +1.5 / L: HP +85, Def +35, SCC +1.5 / B: HP +75, Def +30, SCC +1.5 |
| Obsidian | `OBSIDIAN_CHESTPLATE` | E | 1 | Combat 14 | Def +250 | C: Def +250 |
| Old Dragon | `OLD_DRAGON_HELMET` | L | 4 | Combat 16, Cata 10 | Def +500, HP +450 | H: Def +110, HP +90 / C: Def +160, HP +150 / L: Def +140, HP +130 / B: Def +90, HP +80 |
| Orange | `ORANGE_CHESTPLATE` | E | 1 | - | - | C: - |
| Party Top | `PARTY_HAT` | U | 1 | - | - | H: - |
| Perfect Boots - Tier I | `PERFECT_BOOTS_1` | R | 1 | Cata 7 | Def +90 | B: Def +90 |
| Perfect Boots - Tier II | `PERFECT_BOOTS_2` | R | 1 | Cata 8 | Def +110 | B: Def +110 |
| Perfect Boots - Tier III | `PERFECT_BOOTS_3` | R | 1 | Cata 9 | Def +130 | B: Def +130 |
| Perfect Boots - Tier IV | `PERFECT_BOOTS_4` | E | 1 | Cata 10 | Def +150 | B: Def +150 |
| Perfect Boots - Tier IX | `PERFECT_BOOTS_9` | L | 1 | Cata 17 | Def +250 | B: Def +250 |
| Perfect Boots - Tier V | `PERFECT_BOOTS_5` | E | 1 | Cata 11 | Def +170 | B: Def +170 |
| Perfect Boots - Tier VI | `PERFECT_BOOTS_6` | E | 1 | Cata 12 | Def +190 | B: Def +190 |
| Perfect Boots - Tier VII | `PERFECT_BOOTS_7` | E | 1 | Cata 14 | Def +210 | B: Def +210 |
| Perfect Boots - Tier VIII | `PERFECT_BOOTS_8` | L | 1 | Cata 15 | Def +230 | B: Def +230 |
| Perfect Boots - Tier X | `PERFECT_BOOTS_10` | L | 1 | Cata 18 | Def +270 | B: Def +270 |
| Perfect Boots - Tier XI | `PERFECT_BOOTS_11` | L | 1 | Cata 19 | Def +290 | B: Def +290 |
| Perfect Chestplate - Tier I | `PERFECT_CHESTPLATE_1` | R | 1 | Cata 7 | Def +160 | C: Def +160 |
| Perfect Chestplate - Tier II | `PERFECT_CHESTPLATE_2` | R | 1 | Cata 8 | Def +180 | C: Def +180 |
| Perfect Chestplate - Tier III | `PERFECT_CHESTPLATE_3` | R | 1 | Cata 9 | Def +200 | C: Def +200 |
| Perfect Chestplate - Tier IV | `PERFECT_CHESTPLATE_4` | E | 1 | Cata 10 | Def +220 | C: Def +220 |
| Perfect Chestplate - Tier IX | `PERFECT_CHESTPLATE_9` | L | 1 | Cata 17 | Def +320 | C: Def +320 |
| Perfect Chestplate - Tier V | `PERFECT_CHESTPLATE_5` | E | 1 | Cata 11 | Def +240 | C: Def +240 |
| Perfect Chestplate - Tier VI | `PERFECT_CHESTPLATE_6` | E | 1 | Cata 12 | Def +260 | C: Def +260 |
| Perfect Chestplate - Tier VII | `PERFECT_CHESTPLATE_7` | E | 1 | Cata 14 | Def +280 | C: Def +280 |
| Perfect Chestplate - Tier VIII | `PERFECT_CHESTPLATE_8` | L | 1 | Cata 15 | Def +300 | C: Def +300 |
| Perfect Chestplate - Tier X | `PERFECT_CHESTPLATE_10` | L | 1 | Cata 18 | Def +340 | C: Def +340 |
| Perfect Chestplate - Tier XI | `PERFECT_CHESTPLATE_11` | L | 1 | Cata 19 | Def +360 | C: Def +360 |
| Perfect Helmet - Tier I | `PERFECT_HELMET_1` | R | 1 | Cata 7 | Def +110 | H: Def +110 |
| Perfect Helmet - Tier II | `PERFECT_HELMET_2` | R | 1 | Cata 8 | Def +130 | H: Def +130 |
| Perfect Helmet - Tier III | `PERFECT_HELMET_3` | R | 1 | Cata 9 | Def +150 | H: Def +150 |
| Perfect Helmet - Tier IV | `PERFECT_HELMET_4` | E | 1 | Cata 10 | Def +170 | H: Def +170 |
| Perfect Helmet - Tier IX | `PERFECT_HELMET_9` | L | 1 | Cata 17 | Def +270 | H: Def +270 |
| Perfect Helmet - Tier V | `PERFECT_HELMET_5` | E | 1 | Cata 11 | Def +190 | H: Def +190 |
| Perfect Helmet - Tier VI | `PERFECT_HELMET_6` | E | 1 | Cata 12 | Def +210 | H: Def +210 |
| Perfect Helmet - Tier VII | `PERFECT_HELMET_7` | E | 1 | Cata 14 | Def +230 | H: Def +230 |
| Perfect Helmet - Tier VIII | `PERFECT_HELMET_8` | L | 1 | Cata 15 | Def +250 | H: Def +250 |
| Perfect Helmet - Tier X | `PERFECT_HELMET_10` | L | 1 | Cata 18 | Def +290 | H: Def +290 |
| Perfect Helmet - Tier XI | `PERFECT_HELMET_11` | L | 1 | Cata 19 | Def +310 | H: Def +310 |
| Perfect Helmet - Tier XII | `PERFECT_HELMET_12` | L | 4 | Cata 21 | Def +1380 | H: Def +330 / C: Def +380 / L: Def +360 / B: Def +310 |
| Perfect Helmet - Tier XIII | `PERFECT_HELMET_13` | L | 4 | Cata 21 | Def +1460 | H: Def +350 / C: Def +400 / L: Def +380 / B: Def +330 |
| Perfect Leggings - Tier I | `PERFECT_LEGGINGS_1` | R | 1 | Cata 7 | Def +140 | L: Def +140 |
| Perfect Leggings - Tier II | `PERFECT_LEGGINGS_2` | R | 1 | Cata 8 | Def +160 | L: Def +160 |
| Perfect Leggings - Tier III | `PERFECT_LEGGINGS_3` | R | 1 | Cata 9 | Def +180 | L: Def +180 |
| Perfect Leggings - Tier IV | `PERFECT_LEGGINGS_4` | E | 1 | Cata 10 | Def +200 | L: Def +200 |
| Perfect Leggings - Tier IX | `PERFECT_LEGGINGS_9` | L | 1 | Cata 17 | Def +300 | L: Def +300 |
| Perfect Leggings - Tier V | `PERFECT_LEGGINGS_5` | E | 1 | Cata 11 | Def +220 | L: Def +220 |
| Perfect Leggings - Tier VI | `PERFECT_LEGGINGS_6` | E | 1 | Cata 12 | Def +240 | L: Def +240 |
| Perfect Leggings - Tier VII | `PERFECT_LEGGINGS_7` | E | 1 | Cata 14 | Def +260 | L: Def +260 |
| Perfect Leggings - Tier VIII | `PERFECT_LEGGINGS_8` | L | 1 | Cata 15 | Def +280 | L: Def +280 |
| Perfect Leggings - Tier X | `PERFECT_LEGGINGS_10` | L | 1 | Cata 18 | Def +320 | L: Def +320 |
| Perfect Leggings - Tier XI | `PERFECT_LEGGINGS_11` | L | 1 | Cata 19 | Def +340 | L: Def +340 |
| Pig | `PIG_MASK` | L | 1 | - | Spd +1 | H: Spd +1 |
| Potato | `POTATO_CROWN` | L | 1 | - | Spd +1 | H: Spd +1 |
| Precursor Eye | `PRECURSOR_EYE` | L | 1 | F6 done | Def +222, HP +222, Int +222 | H: Def +222, HP +222, Int +222 |
| Primordial | `PRIMORDIAL_HELMET` | L | 4 | Spider Slayer 7, Cata 16 | Def +410, HP +480, Int +390, CD +40, Spd +15 | H: Def +100, HP +120, Int +100, CD +10 / C: Def +115, HP +150, Int +110, CD +10 / L: Def +105, HP +135, Int +105, CD +10 / B: Spd +15, Def +90, HP +75, Int +75, CD +10 |
| Prospecting | `MINER_OUTFIT_HELMET` | U | 4 | - | Def +100, MinSpd +60 | H: Def +15, MinSpd +15 / C: Def +40, MinSpd +15 / L: Def +30, MinSpd +15 / B: Def +15, MinSpd +15 |
| Protector Dragon | `PROTECTOR_DRAGON_HELMET` | L | 4 | Combat 16, Cata 12 | Def +600, HP +350 | H: Def +135, HP +70 / C: Def +185, HP +120 / L: Def +165, HP +100 / B: Def +115, HP +60 |
| Pufferfish | `PUFFERFISH_HAT_CELEBRATION` | ? | 1 | - | HP +20, Str +10 | H: HP +20, Str +10 |
| Pufferfish | `PUFFERFISH_HAT` | ? | 1 | - | HP +20, Str +10 | H: HP +20, Str +10 |
| Rabbit | `RABBIT_HAT` | U | 5 | - | Spd +25, Int +5, Def +100 | H: Spd +5, Int +5 / H: Def +15, Spd +5 / C: Def +40, Spd +5 / L: Def +30, Spd +5 / B: Def +15, Spd +5 |
| Racing | `RACING_HELMET` | L | 1 | - | - | H: - |
| Rampart | `RAMPART_HELMET` | R | 4 | Combat 22 | Def +200, HP +500, Int +30 | H: Def +45, HP +100, Int +15 / C: Def +60, HP +175, Int +5 / L: Def +55, HP +150, Int +5 / B: Def +40, HP +75, Int +5 |
| Rancher's | `RANCHERS_BOOTS` | E | 1 | Farming 21 | Spd +50, Def +70, HP +100 | B: Spd +50, Def +70, HP +100 |
| Reaper | `REAPER_MASK` | L | 4 | Zombie Slayer 7, Cata 24 | Def +250, HP +550, Int +850, Vit +60, Mend +60, Str +75 | H: Def +100, HP +150, Int +100, Vit +60, Mend +60 / C: Def +70, HP +180, Str +25, Int +250 / L: Def +50, HP +120, Str +25, Int +250 / B: Def +30, HP +100, Str +25, Int +250 |
| Red Sweater | `RED_SWEATER` | R | 1 | - | TrophyCh +4 | C: TrophyCh +4 |
| Rekindled Ember | `REKINDLED_EMBER_HELMET` | E | 4 | Combat 22 | Def +230, HP +785, Int +30, Str +120, CD +75 | H: Def +50, HP +175, Int +15, Str +30, CD +20 / C: Def +70, HP +250, Int +5, Str +30, CD +20 / L: Def +70, HP +220, Int +5, Str +30, CD +20 / B: Def +40, HP +140, Int +5, Str +30, CD +15 |
| Retro-Encabulating Visor | `RETRO_ENCABULATING_VISOR` | M | 1 | - | - | H: - |
| Revenant | `REVENANT_CHESTPLATE` | E | 3 | Zombie Slayer 4 | Def +150, HP +400 | C: Def +70, HP +180 / L: Def +50, HP +120 / B: Def +30, HP +100 |
| Revived Heart | `REVIVED_HEART` | E | 1 | Zombie Slayer 6, Cata 15 | Def +35, HP +100, Int +100, Vit +50, Mend +50 | H: Def +35, HP +100, Int +100, Vit +50, Mend +50 |
| Rosetta's | `ROSETTA_HELMET` | U | 4 | - | Def +110 | H: Def +20 / C: Def +40 / L: Def +30 / B: Def +20 |
| Rotten | `ROTTEN_HELMET` | R | 4 | Cata 1 | - | H: HP 70-152, Str 10-22 / C: HP 125-271, Str 20-43 / L: HP 110-239, Str 15-33 / B: HP 60-130, Str 10-22 |
| Salmon | `SALMON_HAT_CELEBRATION` | ? | 1 | - | Spd +2, Str +15 | H: Spd +2, Str +15 |
| Salmon | `SALMON_HAT` | ? | 5 | - | Spd +2, Str +15, HP +430, Def +150, SCC +6 | H: Spd +2, Str +15 / H: HP +100, Def +30, SCC +1.5 / C: HP +130, Def +55, SCC +1.5 / L: HP +115, Def +45, SCC +1.5 / B: HP +85, Def +20, SCC +1.5 |
| Salmon | `SALMON_HELMET_NEW` | R | 4 | Fishing 13 | HP +430, Def +150, SCC +6 | H: HP +100, Def +30, SCC +1.5 / C: HP +130, Def +55, SCC +1.5 / L: HP +115, Def +45, SCC +1.5 / B: HP +85, Def +20, SCC +1.5 |
| Sea Archer | `SEA_ARCHER_HELMET` | U | 1 | - | Def +20, HP +20 | H: Def +20, HP +20 |
| Sea Lantern | `SEA_LANTERN_HAT` | U | 1 | - | - | H: - |
| Sea Walker | `SEA_WALKER_HELMET` | U | 4 | - | Def +20, HP +20 | H: Def +20, HP +20 / C: - / L: - / B: - |
| Shadow Assassin | `SHADOW_ASSASSIN_HELMET` | E | 4 | F5 done | Spd +28, Def +330, HP +735, Str +100, CD +100 | H: Spd +7, Def +70, HP +160, Str +25, CD +25 / C: Spd +7, Def +110, HP +240, Str +25, CD +25 / L: Spd +7, Def +95, HP +210, Str +25, CD +25 / B: Spd +7, Def +55, HP +125, Str +25, CD +25 |
| Shadow Assassin | `STARRED_SHADOW_ASSASSIN_HELMET` | L | 4 | F5 done | Spd +32, Def +330, HP +775, Str +120, CD +100 | H: Spd +8, Def +70, HP +170, Str +30, CD +25 / C: Spd +8, Def +110, HP +250, Str +30, CD +25 / L: Spd +8, Def +95, HP +220, Str +30, CD +25 / B: Spd +8, Def +55, HP +135, Str +30, CD +25 |
| Shadow Goggles | `SHADOW_GOGGLES` | E | 1 | F3 done | AbilDmg% +35, Int +200 | H: AbilDmg% +35, Int +200 |
| Shark Scale | `SHARK_SCALE_HELMET` | L | 4 | Fishing 24 | HP +650, Def +550, SCC +10 | H: HP +150, Def +125, SCC +2.5 / C: HP +200, Def +175, SCC +2.5 / L: HP +175, Def +150, SCC +2.5 / B: HP +125, Def +100, SCC +2.5 |
| Shimmering Light | `SHIMMERING_LIGHT_HOOD` | R | 4 | Combat 22 | Def +350, HP +400, Int +350, Spd +10 | H: Def +70, HP +80, Int +70, Spd +10 / C: Def +120, HP +130, Int +120 / L: Def +100, HP +120, Int +100 / B: Def +60, HP +70, Int +60 |
| Silver Froggles | `FROGGLES_SILVER` | R | 1 | Fishing 10 | FishSpd +2 | H: FishSpd +2 |
| Silver Hunter | `SILVER_HUNTER_HELMET` | R | 4 | Trophy Silver | HP +350 | H: HP +75 / C: HP +125 / L: HP +100 / B: HP +50 |
| Skeleton | `SKELETON_HAT` | ? | 1 | - | Spd +2, AbilDmg(base) +50, Int +10 | H: Spd +2, AbilDmg(base) +50, Int +10 |
| Skeleton Grunt | `SKELETON_GRUNT_HELMET` | E | 4 | Cata 1 | - | H: Def 33-59, HP 14-30, CD 15-32 / C: Def 38-80, HP 33-60, CD 15-32 / L: Def 44-84, HP 22-46, CD 15-32 / B: Def 20-43, HP 23-36, CD 15-32 |
| Skeleton Lord | `SKELETON_LORD_HELMET` | E | 4 | Cata 25 | - | H: Def 32-68, HP 16-34, CD 19-50, CC 1-2 / C: Def 52-110, HP 26-56, CD 19-50, CC 1-2 / L: Def 48-102, HP 24-51, CD 19-50, CC 1-2 / B: Def 28-60, HP 14-29, CD 19-50, CC 1-2 |
| Skeleton Master | `SKELETON_MASTER_HELMET` | E | 4 | Cata 12 | - | H: Def 26-54, HP 16-34, CD 22-45, CC 2-4 / C: Def 42-88, HP 26-56, CD 22-45, CC 2-4 / L: Def 38-82, HP 24-51, CD 22-45, CC 2-4 / B: Def 22-48, HP 14-29, CD 22-45, CC 2-4 |
| Skeleton Soldier | `SKELETON_SOLDIER_HELMET` | E | 4 | Cata 9 | - | H: Def 26-54, HP 16-34, CD 16-34, CC 2-4 / C: Def 42-88, HP 26-56, CD 17-35, CC 2-4 / L: Def 38-82, HP 24-51, CD 17-35, CC 2-4 / B: Def 22-48, HP 14-29, CD 17-35, CC 2-4 |
| Skeleton's | `SKELETON_HELMET` | R | 4 | - | Def +235, HP +220, Spd +5, Int +50 | H: Def +75 / C: Def +50, HP +20 / L: Def +65, HP +200 / B: Spd +5, Def +45, Int +50 |
| Skeletor | `SKELETOR_HELMET` | E | 4 | Cata 15 | - | H: Def 25-50, HP 15-31, CD 20-42, CC 2-4 / C: Def 40-81, HP 25-51, CD 20-43, CC 2-4 / L: Def 36-74, HP 22-47, CD 20-43, CC 2-4 / B: Def 20-44, HP 13-27, CD 20-42, CC 2-4 |
| Slime | `SLIME_HAT` | ? | 1 | - | HP +50 | H: HP +50 |
| Snake-in-a-Boot | `SNAKE_IN_A_BOOT` | L | 1 | - | - | B: - |
| Sniper | `SNIPER_HELMET` | R | 1 | Cata 8 | Def +10, HP +100 | H: Def +10, HP +100 |
| Snorkeling Visor | `SNORKELING_HELMET` | U | 4 | Foraging 16 | HP +300, Def +300, PressRes +40 | H: HP +65, Def +60, PressRes +10 / C: HP +100, Def +100, PressRes +10 / L: HP +75, Def +85, PressRes +10 / B: HP +60, Def +55, PressRes +10 |
| Snow Suit | `SNOW_SUIT_HELMET` | E | 4 | - | Def +125, HP +310 | H: Def +30, HP +70 / C: Def +40, HP +100 / L: Def +30, HP +75 / B: Def +25, HP +65 |
| Sorrow | `SORROW_HELMET` | L | 4 | HotM 5 (or Island) | MF +20, MinFort +80, TrueDef +240, MinSpd +200 | H: MF +5, MinFort +20, TrueDef +50, MinSpd +50 / C: MF +5, MinFort +20, TrueDef +80, MinSpd +50 / L: MF +5, MinFort +20, TrueDef +70, MinSpd +50 / B: MF +5, MinFort +20, TrueDef +40, MinSpd +50 |
| Space | `DCTR_SPACE_HELM` | S | 1 | - | - | H: - |
| Speedster | `SPEEDSTER_HELMET` | U | 4 | - | Spd +60, Def +210 | H: Spd +15, Def +45 / C: Spd +15, Def +70 / L: Spd +15, Def +60 / B: Spd +15, Def +35 |
| Spider | `SPIDER_HAT` | ? | 1 | - | CC +25 | H: CC +25 |
| Spirit | `THORNS_BOOTS` | E | 1 | F4 done | Spd +25, Def +50, HP +150, Str +25, Int +50 | B: Spd +25, Def +50, HP +150, Str +25, Int +50 |
| Spirit | `SPIRIT_MASK` | E | 1 | Cata 15 | Spd +15, Def +50, HP +100, Int +20 | H: Spd +15, Def +50, HP +100, Int +20 |
| Spirit | `STARRED_SPIRIT_MASK` | L | 1 | Cata 15 | Spd +25, Def +75, HP +100, Int +25 | H: Spd +25, Def +75, HP +100, Int +25 |
| Spirit | `STARRED_THORNS_BOOTS` | L | 1 | F4 done | Spd +35, Def +50, HP +150, Str +35, Int +50 | B: Spd +35, Def +50, HP +150, Str +35, Int +50 |
| Sponge | `SPONGE_HELMET` | E | 4 | Fishing 17 | Def +450, SCC +8 | H: Def +100, SCC +2 / C: Def +150, SCC +2 / L: Def +125, SCC +2 / B: Def +75, SCC +2 |
| Spooky | `SPOOKY_HELMET` | E | 4 | - | Def +135 | H: Def +25 / C: Def +50 / L: Def +35 / B: Def +25 |
| Spring | `SPRING_BOOTS` | R | 1 | - | - | B: - |
| Sprout | `PUMPKIN_HELMET` | U | 4 | Farming 15 | Def +80, FarmFort +60 | H: Def +20, FarmFort +15 / C: Def +20, FarmFort +15 / L: Def +20, FarmFort +15 / B: Def +20, FarmFort +15 |
| Squash | `SQUASH_HELMET` | R | 4 | Farming 35 | FarmFort +110, Spd +18, HP +600, Def +140, PestCh +60 | H: FarmFort +25, Spd +4, HP +120, Def +35, PestCh +15 / C: FarmFort +30, Spd +5, HP +180, Def +35, PestCh +15 / L: FarmFort +30, Spd +5, HP +180, Def +35, PestCh +15 / B: FarmFort +25, Spd +4, HP +120, Def +35, PestCh +15 |
| Squid | `SQUID_HAT` | ? | 2 | - | Spd +1, HP +100, SCC +1 | H: Spd +1 / B: HP +100, SCC +1 |
| Squire | `SQUIRE_HELMET` | U | 4 | Combat 4 | Def +115, HP +115, Str +20 | H: Def +25, HP +25, Str +5 / C: Def +40, HP +40, Str +5 / L: Def +30, HP +30, Str +5 / B: Def +20, HP +20, Str +5 |
| Starlight | `STARLIGHT_HELMET` | R | 4 | Combat 8 | Def +110, Int +225, HP +110 | H: Def +25, Int +45, HP +25 / C: Def +40, Int +90, HP +40 / L: Def +30, Int +60, HP +30 / B: Def +15, Int +30, HP +15 |
| Steel | `STEEL_CHESTPLATE` | E | 1 | F6 done | Def +330 | C: Def +330 |
| Stereo | `MUSIC_PANTS` | E | 1 | - | Def +35, Treasure +1 | L: Def +35, Treasure +1 |
| Stone | `STONE_CHESTPLATE` | R | 1 | - | Def +130 | C: Def +130 |
| Storm's | `WISE_WITHER_HELMET` | L | 4 | F7 done | Def +370, HP +815, Int +1150 | H: Def +80, HP +180, Int +400 / C: Def +120, HP +260, Int +250 / L: Def +105, HP +230, Int +250 / B: Def +65, HP +145, Int +250 |
| Strong Dragon | `STRONG_DRAGON_HELMET` | L | 4 | Combat 16, Cata 12 | Def +500, HP +350, Str +100 | H: Def +110, HP +70, Str +25 / C: Def +160, HP +120, Str +25 / L: Def +140, HP +100, Str +25 / B: Def +90, HP +60, Str +25 |
| Sunflower Head | `SUNFLOWER_HEAD` | U | 1 | - | FarmFort +10 | H: FarmFort +10 |
| Super Heavy | `SUPER_HEAVY_HELMET` | E | 4 | Cata 15 | Spd -40 | H: Spd -10, Def 70-180 / C: Spd -10, Def 112-290 / L: Spd -10, Def 98-250 / B: Spd -10, Def 63-160 |
| Superior Dragon | `SUPERIOR_DRAGON_HELMET` | L | 4 | Combat 20, Cata 14 | Spd +12, Def +600, HP +450, Str +40, CD +40, CC +8, Int +100 | H: Spd +3, Def +130, HP +90, Str +10, CD +10, CC +2, Int +25 / C: Spd +3, Def +190, HP +150, Str +10, CD +10, CC +2, Int +25 / L: Spd +3, Def +170, HP +130, Str +10, CD +10, CC +2, Int +25 / B: Spd +3, Def +110, HP +80, Str +10, CD +10, CC +2, Int +25 |
| Tarantula | `TARANTULA_HELMET` | E | 3 | Spider Slayer 4, Cata 16 | Def +270, HP +325, Int +260 | H: Def +80, HP +100, Int +80 / C: Def +100, HP +120, Int +100 / L: Def +90, HP +105, Int +80 |
| Tarantula | `TARANTULA_BOOTS` | E | 1 | Spider Slayer 4, Cata 16 | Spd +5, Def +70, HP +70, Int +50 | B: Spd +5, Def +70, HP +70, Int +50 |
| Tater | `MELON_HELMET` | U | 4 | Farming 20 | FarmFort +70, Spd +10, HP +500, Def +100, PestCh +40 | H: FarmFort +15, Spd +2, HP +100, Def +25, PestCh +10 / C: FarmFort +20, Spd +3, HP +150, Def +25, PestCh +10 / L: FarmFort +20, Spd +3, HP +150, Def +25, PestCh +10 / B: FarmFort +15, Spd +2, HP +100, Def +25, PestCh +10 |
| Taurus | `TAURUS_HELMET` | R | 4 | Fishing 35 | HP +750, Def +650, SCC +12, CD +35, Str +45 | H: HP +175, Def +150, SCC +3, CD +20 / C: HP +225, Def +200, SCC +3, Str +25 / L: HP +200, Def +175, SCC +3, CD +15 / B: HP +150, Def +125, SCC +3, Str +20 |
| Terror | `TERROR_HELMET` | L | 4 | Combat 22, Kuudra None | Spd +48, Def +210, HP +725, CD +200, Int +30 | H: Spd +12, Def +50, HP +160, CD +50, Int +15 / C: Spd +12, Def +65, HP +230, CD +50, Int +5 / L: Spd +12, Def +55, HP +205, CD +50, Int +5 / B: Spd +12, Def +40, HP +130, CD +50, Int +5 |
| The Builder's Clay | `DUECES_BUILDER_CLAY` | S | 1 | - | - | H: - |
| Thermodynamic | `THERMODYNAMIC_HELMET` | L | 4 | Combat 30 | Spd +100, Def +700, AtkSpd +20 | H: Spd +25, Def +135, AtkSpd +5 / C: Spd +25, Def +240, AtkSpd +5 / L: Spd +25, Def +200, AtkSpd +5 / B: Spd +25, Def +125, AtkSpd +5 |
| Thunder | `THUNDER_HELMET` | E | 4 | Fishing 36 | HP +850, Def +750, SCC +16, Str +100 | H: HP +200, Def +175, SCC +4, Str +25 / C: HP +250, Def +225, SCC +4, Str +25 / L: HP +225, Def +200, SCC +4, Str +25 / B: HP +175, Def +150, SCC +4, Str +25 |
| Tiki | `TIKI_MASK` | L | 1 | Fishing 40 | HP +300, Def +100, SCC +2 | H: HP +300, Def +100, SCC +2 |
| Tropical Fish | `CLOWNFISH_HAT` | U | 1 | - | Int +50 | H: Int +50 |
| Ugly | `UGLY_BOOTS` | M | 1 | - | - | B: - |
| Unstable Dragon | `UNSTABLE_DRAGON_HELMET` | L | 4 | Combat 16, Cata 10 | Def +500, HP +350, CD +60, CC +20, Int +25 | H: Def +110, HP +70, CD +15, CC +5, Int +25 / C: Def +160, HP +120, CD +15, CC +5 / L: Def +140, HP +100, CD +15, CC +5 / B: Def +90, HP +60, CD +15, CC +5 |
| Used Detransfigured Face | `DETRANSFIGURED_MASK` | E | 1 | - | - | H: - |
| Vampire | `VAMPIRE_MASK` | R | 1 | - | Def +111 | H: Def +111 |
| Vampire Witch | `VAMPIRE_WITCH_MASK` | E | 1 | - | Def +222, HP +132, AbilDmg(base) +3000 | H: Def +222, HP +132, AbilDmg(base) +3000 |
| Vanguard | `VANGUARD_HELMET` | L | 4 | - | ColdRes +4 | H: ColdRes +1 / C: ColdRes +1 / L: ColdRes +1 / B: ColdRes +1 |
| Velvet Top | `VELVET_TOP_HAT` | R | 4 | - | HP +100, Def +40 | H: HP +25, Def +10 / C: HP +25, Def +10 / L: HP +25, Def +10 / B: HP +25, Def +10 |
| W Logo | `S_LOGO_CHESTPLATE` | M | 1 | - | - | C: - |
| Warden | `WARDEN_HELMET` | L | 1 | Zombie Slayer 8 | Def +100, HP +300 | H: Def +100, HP +300 |
| Warm Wizard Face | `WARM_WIZARD_FACE` | L | 1 | Vampire Slayer 1 | - | H: - |
| Warmer Wizard Face | `WARM_WIZARD_FACE_2` | M | 1 | Vampire Slayer 4 | - | H: - |
| Watcher Eye | `WATCHER_EYE` | E | 1 | - | - | H: - |
| Water Hydra | `HYDRA1_CHESTPLATE` | U | 3 | - | - | C: - / L: - / B: - |
| Water Hydra Head | `WATER_HYDRA_HEAD` | E | 1 | - | HP +100, Def +100, SCC +2 | H: HP +100, Def +100, SCC +2 |
| Werewolf | `WEREWOLF_HELMET` | L | 4 | Cata 20 | Def +700, Fer +40, Int +200 | H: Def +135, Fer +10, Int +50 / C: Def +240, Fer +10, Int +50 / L: Def +200, Fer +10, Int +50 / B: Def +125, Fer +10, Int +50 |
| Wise Dragon | `WISE_DRAGON_HELMET` | L | 4 | Combat 16, Cata 12 | Def +500, HP +350, Int +350 | H: Def +110, HP +70, Int +125 / C: Def +160, HP +120, Int +75 / L: Def +140, HP +100, Int +75 / B: Def +90, HP +60, Int +75 |
| Witch | `WITCH_MASK` | R | 1 | - | Def +111, HP +66, AbilDmg(base) +4000 | H: Def +111, HP +66, AbilDmg(base) +4000 |
| Wither | `WITHER_HELMET` | L | 4 | F7 done | Def +720, HP +930, Int +60 | H: Def +100, HP +200, Int +30 / C: Def +250, HP +300, Int +10 / L: Def +220, HP +250, Int +10 / B: Def +150, HP +180, Int +10 |
| Wither Goggles | `WITHER_GOGGLES` | E | 1 | F6 done | AbilDmg% +45, Int +300 | H: AbilDmg% +45, Int +300 |
| Wizard Face | `WIZARD_FACE` | M | 1 | - | - | H: - |
| Wizardman | `WIZARDMAN_LEGGINGS` | M | 1 | - | - | L: - |
| Wyld Helmet on a | `WYLD_HELMET` | U | 4 | - | - | H: - / C: - / L: - / B: - |
| Yog | `ARMOR_OF_YOG_HELMET` | E | 4 | Hard_Stone coll 5 | Def +60, HP +200, MinSpd +100, MinFort +80, HeatRes +40 | H: Def +15, HP +50, MinSpd +25, MinFort +20, HeatRes +10 / C: Def +15, HP +50, MinSpd +25, MinFort +20, HeatRes +10 / L: Def +15, HP +50, MinSpd +25, MinFort +20, HeatRes +10 / B: Def +15, HP +50, MinSpd +25, MinFort +20, HeatRes +10 |
| Young Dragon | `YOUNG_DRAGON_HELMET` | L | 4 | Combat 16, Cata 10 | Spd +80, Def +500, HP +350 | H: Spd +20, Def +110, HP +70 / C: Spd +20, Def +160, HP +120 / L: Spd +20, Def +140, HP +100 / B: Spd +20, Def +90, HP +60 |
| Zombie | `ZOMBIE_HAT` | ? | 4 | - | Def +105, HP +480 | H: Def +10 / C: Def +40, HP +200 / L: Def +30, HP +160 / B: Def +25, HP +120 |
| Zombie Commander | `ZOMBIE_COMMANDER_HELMET` | E | 4 | Cata 18 | - | H: Def 22-58, HP 39-102, Str 8-22, Int 4-11 / C: Def 35-90, HP 59-154, Str 8-22, Int 4-11 / L: Def 30-77, HP 51-134, Str 8-22, Int 4-11 / B: Def 17-45, HP 32-80, Str 8-22, Int 4-11 |
| Zombie Knight | `ZOMBIE_KNIGHT_HELMET` | E | 4 | Cata 14 | - | H: Spd 5-8, Def 54-117, Str 10-22 / C: Spd 5-8, Def 96-209, Str 20-43 / L: Spd 5-8, Def 85-184, Str 15-33 / B: Spd 5-8, Def 46-100, Str 10-22 |
| Zombie Lord | `ZOMBIE_LORD_HELMET` | E | 4 | Cata 25 | - | H: Def 25-65, HP 56-145, Str 7-18, Int 7-18 / C: Def 39-100, HP 85-220, Str 7-18, Int 7-18 / L: Def 33-85, HP 73-190, Str 7-18, Int 7-18 / B: Def 19-50, HP 45-115, Str 7-18, Int 7-18 |
| Zombie Soldier | `ZOMBIE_SOLDIER_HELMET` | E | 4 | Cata 9 | - | H: Def 43-100, HP 21-50 / C: Def 81-190, HP 43-100 / L: Def 71-165, HP 34-80 / B: Def 36-85, HP 17-40 |
| Zombie's Heart | `ZOMBIE_HEART` | R | 1 | - | HP +50, Vit +30, Mend +30 | H: HP +50, Vit +30, Mend +30 |

## Appendix A2 — Set bonuses and piece recipes (NEU lore)

Format: **SET_KEY** [tier] req … — bonus text (`(0/4)` = pieces required; `//` separates lore paragraphs). `src` lists each piece's recipe (H/C/L/B).

- **ABYSSAL** [L] req Fishing 22 — Full Set Bonus: One with the Fish (0/4) Grants the ability to breathe permanently while underwater. // Tiered Bonus: Deep Sea Diver (0/4) Grants +0 Treasure Chance while underwater.
  - src: H: craft: 256x GILL_MEMBRANE, 32x ENCHANTED_SEA_LUMIES, 2x DIVER_FRAGMENT, 1x DIVER_HELMET | C: craft: 256x GILL_MEMBRANE, 32x ENCHANTED_SEA_LUMIES, 2x DIVER_FRAGMENT, 1x DIVER_CHESTPLATE | L: craft: 256x GILL_MEMBRANE, 32x ENCHANTED_SEA_LUMIES, 2x DIVER_FRAGMENT, 1x DIVER_LEGGINGS | B: craft: 256x GILL_MEMBRANE, 32x ENCHANTED_SEA_LUMIES, 2x DIVER_FRAGMENT, 1x DIVER_BOOTS
- **ADAPTIVE** [E] req F3 done — Full Set Bonus: Efficient training (0/4) Every 5 Catacombs levels, this armor piece gains +2% stats. // Grants additional bonuses based on your selected dungeon class! // Berserk: +20 Strength Healer: +5 Mending, +40 Health Mage: +50 Intelligence Tank: +30 Defense and reduces damage taken by 5% for each piece you have equipped if you are hit by the same monster within 10 seconds. Archer: +5 Crit Chance, +15 Crit Damage
  - src: H: - | C: - | L: - | B: -
- **ANGLER** [?] req - — Tiered Bonus: Depth Champion (0/8) Reduces the damage taken from Sea Creatures by 3%. // Tiered Bonus: Deepness Within (0/4) Gain 6❤ per Fishing level. Fishing: Level 00
  - src: H: - | C: - | L: - | B: -
- **ARACHNE** [R] req - — Tiered Bonus: Arachne's Faithful (0/8) Grants +5❤ Health and +5❈ Defense. // This item is Arachnal Ж! // * Co-op Soulbound *
  - src: H: - | C: - | L: - | B: -
- **ARMOR_OF_MAGMA** [E] req - — Full Set Bonus: Absorb (0/4) Every 10 Magma Cubes killed gives the wearer +1  Health and  Intelligence while wearing the set. Max 200 each.
  - src: H: - | C: - | L: - | B: -
- **ARMOR_OF_THE_PACK** [E] req Wolf Slayer 6 — Full Set Bonus: Armor of the Pack (0/4) Gain +35 Strength and +80 Defense for each Armor of the Pack wearer within 30 blocks. Max of 3 players!
  - src: H: craft: 3x GOLDEN_TOOTH, 128x ENCHANTED_MUTTON | C: craft: 128x ENCHANTED_MUTTON, 4x GOLDEN_TOOTH | L: craft: 192x ENCHANTED_MUTTON, 1x GOLDEN_TOOTH | B: craft: 2x GOLDEN_TOOTH, 64x ENCHANTED_MUTTON
- **ARMOR_OF_THE_RESISTANCE** [E] req - — Full Set Bonus: Power of the Resistance (0/4) Provides a damage bonus while fighting against Dante, and reduces damage taken from Dante.
  - src: H: - | C: - | L: - | B: -
- **ARMOR_OF_YOG** [E] req Hard_Stone coll 5 — Full Set Bonus: Absorb (0/4) Every 5 Yogs killed grants the wearer +1 Mining Speed. (Max 500). // Yogs Killed: 0
  - src: H: craft: 40x YOGGIE, 1x FLAME_BREAKER_HELMET | C: craft: 40x YOGGIE, 1x FLAME_BREAKER_CHESTPLATE | L: craft: 40x YOGGIE, 1x FLAME_BREAKER_LEGGINGS | B: craft: 40x YOGGIE, 1x FLAME_BREAKER_BOOTS
- **AURORA** [L] req Combat 22, Kuudra None — Tiered Bonus: Arcane Energy (0/4) Every 1s, dealing Magic Damage from an ability grants 1 stack of Ѫ Arcane Energy. Lose 1 stack after 4s of not gaining a stack. // Ability: Homing Missiles SNEAK At 10 stacks, sneak to reset your stacks and shoot 3 homing missiles dealing 5,000 Damage each.
  - src: H: - | C: - | L: - | B: -
- **BACKWATER** [U] req Fishing 5 — Tiered Bonus: Swamp Soldier (0/8) Increases damage dealt to Sea Creatures by 5%.
  - src: H: - | C: - | L: - | B: -
- **BAT_PERSON** [L] req - — Full Set Bonus: Bat Powers Activate! turns you into a true vigilante! Grants a +5% chance to find rare Candy. // You are the hero this world needs.
  - src: H: craft: 5x SPOOKY_SHARD, 1x SPOOKY_HELMET | C: craft: 8x SPOOKY_SHARD, 1x SPOOKY_CHESTPLATE | L: craft: 7x SPOOKY_SHARD, 1x SPOOKY_LEGGINGS | B: craft: 4x SPOOKY_SHARD, 1x SPOOKY_BOOTS
- **BERSERKER** [E] req Combat 22 — Tiered Bonus: Berserk (0/4) Reduces your health and defense by half and reduces the cost of health-consuming abilities by 20%.
  - src: H: craft: 40x MAGMA_CHUNK, 50x BURNING_EYE, 2x ENCHANTED_RED_SAND_CUBE | C: craft: 5x ENCHANTED_RED_SAND_CUBE, 50x BURNING_EYE, 40x MAGMA_CHUNK | L: craft: 4x ENCHANTED_RED_SAND_CUBE, 50x BURNING_EYE, 40x MAGMA_CHUNK | B: craft: 2x ENCHANTED_RED_SAND_CUBE, 40x MAGMA_CHUNK
- **BIOHAZARD** [U] req Garden 5 — -
  - src: H: - | C: - | L: - | B: -
- **BLAZE** [E] req Combat 22 — Full Set Bonus: Blazing Aura (0/4) Damages mobs within 5 blocks for 3% of their max  Health per second. // Max 500 damage/s Blaze Rod Collection: 0 +100 per 5,000 rods
  - src: H: craft: 5x ENCHANTED_BLAZE_ROD | H: - | C: craft: 8x ENCHANTED_BLAZE_ROD | L: craft: 7x ENCHANTED_BLAZE_ROD | B: craft: 4x ENCHANTED_BLAZE_ROD
- **BOUNCY** [R] req Cata 12 — Full Set Bonus: Bouncing Arrow (0/4) Your arrows have a 25% chance to bounce to another target after it hits something.
  - src: H: - | C: - | L: - | B: -
- **BRONZE_HUNTER** [U] req Trophy Bronze — Full Set Bonus: Star Fisher (0/4) Grants +0.2 Fishing Speed per ✪ // * Soulbound *
  - src: H: - | C: - | L: - | B: -
- **BURNING_AURORA** [L] req Combat 32, Kuudra Burning — Tiered Bonus: Arcane Energy (0/4) Every 1s, dealing Magic Damage from an ability grants 1 stack of Ѫ Arcane Energy. Lose 1 stack after 4s of not gaining a stack. // Ability: Homing Missiles SNEAK At 10 stacks, sneak to reset your stacks and shoot 4 homing missiles dealing 5,000 Damage each.
  - src: H: - | C: - | L: - | B: -
- **BURNING_CRIMSON** [L] req Combat 32, Kuudra Burning — Tiered Bonus: Dominus (0/4) Every 1.5s, melee attacks grant 1 stack of ᝐ Dominus. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +0.1 Swing Range and +1 Ferocity. // At 10 stacks swipe in a random direction dealing 0.75x Swipe Damage to mobs in its path.
  - src: H: - | C: - | L: - | B: -
- **BURNING_FERVOR** [L] req Combat 32, Kuudra Burning — Full Set Bonus: Intimidate (0/4) Attacking a mob has a 50% chance to direct aggro from 3 mobs to you.
  - src: H: - | C: - | L: - | B: -
- **BURNING_HOLLOW** [L] req Combat 32, Kuudra Burning — Tiered Bonus: Spirit (0/4) Every 3s, up to 5 nearby player's attacks grant 1 stack of ⚶ Spirit. (Max 20 Stacks) Lose 1 after 4s of not gaining a stack. // Stacks can be used with a Hollow Wand to cast spells.
  - src: H: - | C: - | L: - | B: -
- **BURNING_TERROR** [L] req Combat 32, Kuudra Burning — Tiered Bonus: Hydra Strike (0/4) Every 0.2s, arrow attacks grant 1 stack of ⁑ Hydra Strike. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +3.5% Damage and +2% Arrow Speed. // At 10 stacks shoot +2 arrows that deal 40% Arrow Damage.
  - src: H: - | C: - | L: - | B: -
- **CACTUS** [?] req Farming 5 — Full Set Bonus: Deflect (0/4) Rebound 33% of the damage you take back at your enemy.
  - src: H: craft: 5x CACTUS | C: craft: 8x CACTUS | L: craft: 7x CACTUS | B: craft: 4x CACTUS
- **CANOPY** [U] req Foraging 12 — -
  - src: H: - | C: - | L: - | B: -
- **CELESTE** [U] req Combat 4 — -
  - src: H: - | C: - | L: - | B: -
- **CHAINMAIL** [U] req - — -
  - src: H: - | C: - | L: - | B: -
- **CHALLENGER** [E] req - — Ability: Mythos' Might Grants 2x this armor's stats while in The Hub during Diana's Mythological Ritual. // Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%.
  - src: H: craft: 32x ENCHANTED_GOLD, 4x GRIFFIN_FEATHER, 64x ANCIENT_CLAW | C: craft: 48x ENCHANTED_GOLD, 4x GRIFFIN_FEATHER, 64x ANCIENT_CLAW | L: craft: 128x ANCIENT_CLAW, 16x ENCHANTED_GOLD, 4x GRIFFIN_FEATHER | B: craft: 64x ENCHANTED_GOLD, 4x GRIFFIN_FEATHER
- **CHEAP_TUXEDO** [E] req - — Full Set Bonus: Dashing (0/3) Max health set to 75❤. Deal +50% damage! Very stylish.
  - src: C: - | L: - | B: -
- **CRIMSON** [L] req Combat 22, Kuudra None — Tiered Bonus: Dominus (0/4) Every 1.5s, melee attacks grant 1 stack of ᝐ Dominus. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +0.05 Swing Range. // At 10 stacks swipe in a random direction dealing 0.5x Swipe Damage to mobs in its path.
  - src: H: - | C: - | L: - | B: -
- **CROPIE** [U] req Farming 30 — Tiered Bonus: Squashbuckle (0/4) Farming Pumpkins, Melons, and Cocoa Beans have a 0.01% chance of dropping a Squash. Inside the Greenhouse gives a 4.0% chance to drop one from any crop grown inside. Grants 0 Farming Fortune.
  - src: H: craft: 20x CROPIE, 8x ENCHANTED_HAY_BALE, 6x BOX_OF_SEEDS, 1x MELON_HELMET | C: craft: 20x CROPIE, 1x MELON_CHESTPLATE, 6x BOX_OF_SEEDS, 8x ENCHANTED_HAY_BALE, 45x ENCHANTED_BAKED_POTATO | L: craft: 20x CROPIE, 8x ENCHANTED_HAY_BALE, 6x BOX_OF_SEEDS, 1x MELON_LEGGINGS, 30x ENCHANTED_GOLDEN_CARROT | B: craft: 20x CROPIE, 16x ENCHANTED_HAY_BALE, 1x MELON_BOOTS
- **CRYPT_WITHERLORD** [E] req Cata 18 — Reduces damage from Withers by 5% // 18.
  - src: H: - | C: - | L: - | B: -
- **CRYSTAL** [E] req - — Full Set Bonus: Refraction (0/4) The stats of this armor change from 0 to 200% depending on the current light level. // Current Light Level 0 (0%)
  - src: H: craft: 5x CRYSTAL_FRAGMENT | C: craft: 8x CRYSTAL_FRAGMENT | L: craft: 7x CRYSTAL_FRAGMENT | B: craft: 4x CRYSTAL_FRAGMENT
- **DIAMOND** [U] req - — -
  - src: H: craft: 5x DIAMOND | C: craft: 8x DIAMOND | L: craft: 7x DIAMOND | B: craft: 4x DIAMOND
- **DIAMOND_HUNTER** [L] req Trophy Diamond — Full Set Bonus: Star Fisher (0/4) Grants +1 Fishing Speed per ✪ // * Soulbound *
  - src: H: - | C: - | L: - | B: -
- **DIVAN** [L] req HotM 6 — -
  - src: H: forge (24h): FLAWLESS_RUBY_GEM:1, GEMSTONE_MIXTURE:10, DIVAN_FRAGMENT:5 | C: forge (24h): GEMSTONE_MIXTURE:10, FLAWLESS_RUBY_GEM:1, DIVAN_FRAGMENT:8 | L: forge (24h): DIVAN_FRAGMENT:7, FLAWLESS_RUBY_GEM:1, GEMSTONE_MIXTURE:10 | B: forge (24h): GEMSTONE_MIXTURE:10, FLAWLESS_RUBY_GEM:1, DIVAN_FRAGMENT:4
- **DIVER** [E] req Fishing 20 — Full Set Bonus: One with the Fish (0/4) Grants the ability to breathe permanently while underwater. // Tiered Bonus: Deep Sea Diver (0/4) Grants +0 Treasure Chance while underwater.
  - src: H: craft: 8x ENCHANTED_SEA_LUMIES, 32x GILL_MEMBRANE, 1x SNORKELING_HELMET | C: craft: 8x ENCHANTED_SEA_LUMIES, 32x GILL_MEMBRANE, 1x SNORKELING_CHESTPLATE | L: craft: 8x ENCHANTED_SEA_LUMIES, 32x GILL_MEMBRANE, 1x SNORKELING_LEGGINGS | B: craft: 8x ENCHANTED_SEA_LUMIES, 32x GILL_MEMBRANE, 1x SNORKELING_BOOTS
- **ELEANOR** [L] req - — Tiered Bonus: Riches (0/4) Gain 1.5x Scavenger Coins. // Scavenger Coins Gained: 0 // A beautifully tailored tunic, featuring intricate stitching and a soft, radiant finish. // * Soulbound * X Rift-Transferable X
  - src: H: - | C: - | L: - | B: -
- **ELEGANT_TUXEDO** [L] req - — Full Set Bonus: Dashing (0/3) Max health set to 250❤. Deal +150% damage! Very stylish.
  - src: C: - | L: - | B: -
- **EMBER** [E] req Combat 8 — Full Set Bonus: Nether Lord (0/4) Grants immunity to all damage from Lava and Fire. Walking over Lava creates Obsidian under your feet. Disabled in some zones and near Fishing Hotspots.
  - src: H: craft: 5x EMBER_FRAGMENT | C: craft: 8x EMBER_FRAGMENT | L: craft: 7x EMBER_FRAGMENT | B: craft: 4x EMBER_FRAGMENT
- **EMERALD_ARMOR** [E] req - — Full Set Bonus: Tank (0/4) Increases  Health by +1 and  Defense by +1 for every 3,000 Emeralds in your collection. Max 350 each.
  - src: H: craft: 5x ENCHANTED_EMERALD_BLOCK | C: craft: 8x ENCHANTED_EMERALD_BLOCK | L: craft: 7x ENCHANTED_EMERALD_BLOCK | B: craft: 4x ENCHANTED_EMERALD_BLOCK
- **EMPEROR** [U] req - — -
  - src: H: - | L: - | B: -
- **END** [E] req Combat 12 — All stats on this armor piece are multiplied by 2x while on the End Island! // * Co-op Soulbound *
  - src: H: - | C: - | L: - | B: -
- **ENDERMAN** [?] req - — * Co-op Soulbound *
  - src: H: - | H: trade
- **FAIRY** [R] req - — Full Set Bonus: Fairy's Outfit (0/4) Gain 1❤ per Fairy Soul. You found: 1 Fairy Soul
  - src: H: - | C: - | L: - | B: -
- **FALLEN_STAR** [R] req - — -
  - src: H: trade | C: - | C: - | C: -
- **FANCY_TUXEDO** [L] req - — Full Set Bonus: Dashing (0/3) Max health set to 150❤. Deal +100% damage! Very stylish.
  - src: C: - | L: - | B: -
- **FARM_ARMOR** [C] req Farming 10 — -
  - src: H: craft: 8x ENCHANTED_WHEAT, 1x FARM_SUIT_HELMET | C: craft: 8x ENCHANTED_WHEAT, 1x FARM_SUIT_CHESTPLATE | L: craft: 8x ENCHANTED_WHEAT, 1x FARM_SUIT_LEGGINGS | B: craft: 8x ENCHANTED_WHEAT, 1x FARM_SUIT_BOOTS
- **FARM_SUIT** [?] req Farming 3 — ⚑ TIP! The more  Farming Fortune you have, the more crops you will drop.
  - src: H: craft: 40x WHEAT | C: craft: 64x WHEAT | L: craft: 56x WHEAT | B: craft: 32x WHEAT
- **FERMENTO** [E] req Farming 40 — Tiered Bonus: Feast (0/4) Farming Wild Rose, Sunflower, and Moonflower have a 0.002% chance of dropping a Helianthus. Inside the Greenhouse gives a 0.8% chance to drop one from any crop grown inside. Also combines the Tiered Bonuses of wearing 0 pieces of the Tater Armor, Cropie Armor, and Squash Armor. Grants 0 Farming Fortune.
  - src: H: craft: 2x CONDENSED_FERMENTO, 32x ENCHANTED_SUGAR_CANE, 16x ENCHANTED_HUGE_MUSHROOM_2, 1x SQUASH_HELMET, 16x ENCHANTED_HUGE_MUSHROOM_1 | C: craft: 2x CONDENSED_FERMENTO, 1x SQUASH_CHESTPLATE, 16x ENCHANTED_HUGE_MUSHROOM_2, 32x ENCHANTED_SUGAR_CANE, 16x ENCHANTED_HUGE_MUSHROOM_1, 30x MUTANT_NETHER_STALK, 25x ENCHANTED_CACTUS | L: craft: 2x CONDENSED_FERMENTO, 32x ENCHANTED_SUGAR_CANE, 16x ENCHANTED_HUGE_MUSHROOM_2, 1x SQUASH_LEGGINGS, 16x ENCHANTED_HUGE_MUSHROOM_1, 40x MUTANT_NETHER_STALK | B: craft: 2x CONDENSED_FERMENTO, 16x ENCHANTED_HUGE_MUSHROOM_2, 1x SQUASH_BOOTS, 16x ENCHANTED_HUGE_MU
- **FERVOR** [L] req Combat 22, Kuudra None — Full Set Bonus: Intimidate (0/4) Attacking a mob has a 50% chance to direct aggro from 3 mobs to you.
  - src: H: - | C: - | L: - | B: -
- **FIERY_AURORA** [L] req Combat 37, Kuudra Fiery — Tiered Bonus: Arcane Energy (0/4) Every 1s, dealing Magic Damage from an ability grants 1 stack of Ѫ Arcane Energy. Lose 1 stack after 4s of not gaining a stack. // Ability: Homing Missiles SNEAK At 10 stacks, sneak to reset your stacks and shoot 5 homing missiles dealing 5,000 Damage each.
  - src: H: - | C: - | L: - | B: -
- **FIERY_CRIMSON** [L] req Combat 37, Kuudra Fiery — Tiered Bonus: Dominus (0/4) Every 1.5s, melee attacks grant 1 stack of ᝐ Dominus. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +0.1 Swing Range and +2 Ferocity. // At 10 stacks swipe in a random direction dealing 0.88x Swipe Damage to mobs in its path.
  - src: H: - | C: - | L: - | B: -
- **FIERY_FERVOR** [L] req Combat 37, Kuudra Fiery — Full Set Bonus: Intimidate (0/4) Attacking a mob has a 50% chance to direct aggro from 3 mobs to you.
  - src: H: - | C: - | L: - | B: -
- **FIERY_HOLLOW** [L] req Combat 37, Kuudra Fiery — Tiered Bonus: Spirit (0/4) Every 3s, up to 5 nearby player's attacks grant 1 stack of ⚶ Spirit. (Max 30 Stacks) Lose 1 after 4s of not gaining a stack. // Stacks can be used with a Hollow Wand to cast spells.
  - src: H: - | C: - | L: - | B: -
- **FIERY_TERROR** [L] req Combat 37, Kuudra Fiery — Tiered Bonus: Hydra Strike (0/4) Every 0.2s, arrow attacks grant 1 stack of ⁑ Hydra Strike. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +4.2% Damage and +2.5% Arrow Speed. // At 10 stacks shoot +2 arrows that deal 50% Arrow Damage.
  - src: H: - | C: - | L: - | B: -
- **FIG** [R] req Foraging 15 — -
  - src: H: craft: 5x FIGSTONE, 1x CANOPY_HELMET | C: craft: 8x FIGSTONE, 1x CANOPY_CHESTPLATE | L: craft: 7x FIGSTONE, 1x CANOPY_LEGGINGS | B: craft: 4x FIGSTONE, 1x CANOPY_BOOTS
- **FINAL_DESTINATION** [L] req Enderman Slayer 3 — Full Set Bonus: Vivacious Darkness (0/4) while sneaking: ⁍ +30 Strength ⁍ +20 Attack Speed ⁍ +10 Speed ⁍ Multiply  Intelligence by 1.25x ⁍ +200 Ferocity against Endermen ⁍ +100% damage against Endermen // Piece Bonus: Enderman Bulwark Kill Endermen to accumulate defense against them. Piece Bonus: +0 Next Upgrade: +20 (0/100)
  - src: H: craft: 3x NULL_OVOID, 128x ENCHANTED_REDSTONE | C: craft: 256x ENCHANTED_REDSTONE, 4x NULL_OVOID | L: craft: 3x NULL_OVOID, 256x ENCHANTED_REDSTONE | B: craft: 128x ENCHANTED_REDSTONE, 2x NULL_OVOID
- **FLAMEBREAKER** [U] req Hard_Stone coll 5 — An old charred shadow of the real thing leaving behind streaks of coal on the wearer from its shoddy construction. // * Co-op Soulbound *
  - src: H: - | C: - | L: - | B: -
- **FLAME_BREAKER** [E] req Hard_Stone coll 5 — -
  - src: H: craft: 100x ENCHANTED_HARD_STONE | C: craft: 160x ENCHANTED_HARD_STONE | L: craft: 140x ENCHANTED_HARD_STONE | B: craft: 80x ENCHANTED_HARD_STONE
- **FROZEN_BLAZE** [L] req Combat 22, Cata 20 — Full Set Bonus: Frozen Blazing Aura (0/4) Damages mobs in a 5 block range for 300 base damage + 3% of their max  Health every second and applies Slowness I for 4 seconds. // Max 500 damage/s Blaze Rod Collection: 0 +100 per 5,000 rods
  - src: H: craft: 64x ENCHANTED_PACKED_ICE, 1x BLAZE_HELMET | C: craft: 64x ENCHANTED_PACKED_ICE, 1x BLAZE_CHESTPLATE | L: craft: 64x ENCHANTED_PACKED_ICE, 1x BLAZE_LEGGINGS | B: craft: 64x ENCHANTED_PACKED_ICE, 1x BLAZE_BOOTS
- **GENERALS_ARMOR_OF_THE_RESISTANCE** [E] req - — Full Set Bonus: Power of the Resistance (0/4) Provides a damage bonus while fighting against Dante, and reduces damage taken from Dante.
  - src: H: - | C: - | L: - | B: -
- **GHAST** [?] req - — -
  - src: H: - | C: - | L: - | B: -
- **GLACITE** [E] req - — Full Set Bonus: Expert Miner (0/4) Grants +2 Mining Speed per Mining Skill level unlocked.
  - src: H: - | C: - | L: - | B: -
- **GLOSSY_MINERAL** [L] req - — Tiered Bonus: Glossy Mineralworks (0/4) Grants +0 Mining Spread on Ores and Blocks.
  - src: H: craft: 16x GLOSSY_GEMSTONE, 1x MINERAL_HELMET | C: craft: 16x GLOSSY_GEMSTONE, 1x MINERAL_CHESTPLATE | L: craft: 16x GLOSSY_GEMSTONE, 1x MINERAL_LEGGINGS | B: craft: 16x GLOSSY_GEMSTONE, 1x MINERAL_BOOTS
- **GOBLIN** [R] req - — Full Set Bonus: Smart Miner (0/4) Converts your Intelligence into Mining Speed. +1 Mining Speed for every 15 Intelligence removed.
  - src: H: - | C: - | L: - | B: -
- **GOLD** [?] req - — -
  - src: H: craft: 5x GOLD_INGOT | C: craft: 8x GOLD_INGOT | L: craft: 7x GOLD_INGOT | B: craft: 4x GOLD_INGOT
- **GOLD_HUNTER** [E] req Trophy Gold — Full Set Bonus: Star Fisher (0/4) Grants +0.7 Fishing Speed per ✪ // * Soulbound *
  - src: H: - | C: - | L: - | B: -
- **GOLEM_ARMOR** [R] req - — Full Set Bonus: Absorption (0/4) Grants the wearer Absorption 3 for 20 seconds when they kill an enemy.
  - src: H: craft: 50x ENCHANTED_IRON | C: craft: 80x ENCHANTED_IRON | L: craft: 70x ENCHANTED_IRON | B: craft: 40x ENCHANTED_IRON
- **GREAT_SPOOK** [E] req - — Full Set Bonus: Spooky (0/4) Lets you spook other players while worn by punching them with the Great Spook Staff. // Full Set Bonus: Fearsome (0/4) Monsters at or below your total Fear level will run away from you. // Obtained during the Alchemist experiments!
  - src: H: - | C: - | L: - | B: -
- **GROWTH** [R] req - — Ability: Growth Heals you for 1%  Health after killing a Monster, and also increases the  Health bonus of a piece of the armor by 1 (Max 100) Cooldown: 4s // Bonus HP: 0/100
  - src: H: craft: 320x ENCHANTED_DARK_OAK_LOG | C: craft: 512x ENCHANTED_DARK_OAK_LOG | L: craft: 448x ENCHANTED_DARK_OAK_LOG | B: craft: 256x ENCHANTED_DARK_OAK_LOG
- **HARDENED_DIAMOND** [R] req - — -
  - src: H: craft: 5x ENCHANTED_DIAMOND | C: craft: 8x ENCHANTED_DIAMOND | L: craft: 7x ENCHANTED_DIAMOND | B: craft: 4x ENCHANTED_DIAMOND
- **HEAT** [R] req Hard_Stone coll 2 — Full Set Bonus: Heat Shield (0/4) You are immune to  Heat.
  - src: H: craft: 256x HARD_STONE, 1x TANK_MINER_HELMET | C: craft: 256x HARD_STONE, 1x TANK_MINER_CHESTPLATE | L: craft: 256x HARD_STONE, 1x TANK_MINER_LEGGINGS | B: craft: 256x HARD_STONE, 1x TANK_MINER_BOOTS
- **HEAVY** [R] req Cata 2 — Full Set Bonus: Vindicate (0/4) Grants +1 walk speed for every 50 defense that you have.
  - src: H: - | C: - | L: - | B: -
- **HELIANTHUS** [L] req Farming 50 — Tiered Bonus: Feast (0/4) Combines the Tiered Bonuses of wearing 0 pieces of the Tater Armor, Cropie Armor, Squash Armor, and Fermento Armor. Grants 0 Farming Fortune.
  - src: H: craft: 2x CONDENSED_HELIANTHUS, 64x COMPACTED_WILD_ROSE, 64x COMPACTED_SUNFLOWER, 1x FERMENTO_HELMET, 64x COMPACTED_MOONFLOWER | C: craft: 2x CONDENSED_HELIANTHUS, 1x FERMENTO_CHESTPLATE, 64x COMPACTED_SUNFLOWER, 64x COMPACTED_WILD_ROSE, 64x COMPACTED_MOONFLOWER | L: craft: 2x CONDENSED_HELIANTHUS, 64x COMPACTED_WILD_ROSE, 64x COMPACTED_SUNFLOWER, 1x FERMENTO_LEGGINGS, 64x COMPACTED_MOONFLOWER | B: craft: 2x CONDENSED_HELIANTHUS, 64x COMPACTED_SUNFLOWER, 1x FERMENTO_BOOTS, 64x COMPACTED_MOONFLOWER
- **HELIX_ARMOR** [E] req Foraging 25 — -
  - src: H: craft: 5x HELIXIS, 1x FIG_HELMET | C: craft: 7x HELIXIS, 1x FIG_CHESTPLATE | L: craft: 6x HELIXIS, 1x FIG_LEGGINGS | B: craft: 4x HELIXIS, 1x FIG_BOOTS
- **HOLLOW** [L] req Combat 22, Kuudra None — Tiered Bonus: Spirit (0/4) Every 3s, up to 5 nearby player's attacks grant 1 stack of ⚶ Spirit. (Max 10 Stacks) Lose 1 after 4s of not gaining a stack. // Stacks can be used with a Hollow Wand to cast spells.
  - src: H: - | C: - | L: - | B: -
- **HOLY_DRAGON** [L] req Combat 16, Cata 10 — Full Set Bonus: Holy Blood (0/4) Grants +75 Health Regen to you and all players within 10 blocks. Effect only applies once!
  - src: H: craft: 50x HOLY_FRAGMENT | C: craft: 80x HOLY_FRAGMENT | L: craft: 70x HOLY_FRAGMENT | B: craft: 40x HOLY_FRAGMENT
- **HOT_AURORA** [L] req Combat 27, Kuudra Hot — Tiered Bonus: Arcane Energy (0/4) Every 1s, dealing Magic Damage from an ability grants 1 stack of Ѫ Arcane Energy. Lose 1 stack after 4s of not gaining a stack. // Ability: Homing Missiles SNEAK At 10 stacks, sneak to reset your stacks and shoot 4 homing missiles dealing 5,000 Damage each.
  - src: H: - | C: - | L: - | B: -
- **HOT_CRIMSON** [L] req Combat 27, Kuudra Hot — Tiered Bonus: Dominus (0/4) Every 1.5s, melee attacks grant 1 stack of ᝐ Dominus. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +0.1 Swing Range. // At 10 stacks swipe in a random direction dealing 0.62x Swipe Damage to mobs in its path.
  - src: H: - | C: - | L: - | B: -
- **HOT_FERVOR** [L] req Combat 27, Kuudra Hot — Full Set Bonus: Intimidate (0/4) Attacking a mob has a 50% chance to direct aggro from 3 mobs to you.
  - src: H: - | C: - | L: - | B: -
- **HOT_HOLLOW** [L] req Combat 27, Kuudra Hot — Tiered Bonus: Spirit (0/4) Every 3s, up to 5 nearby player's attacks grant 1 stack of ⚶ Spirit. (Max 15 Stacks) Lose 1 after 4s of not gaining a stack. // Stacks can be used with a Hollow Wand to cast spells.
  - src: H: - | C: - | L: - | B: -
- **HOT_TERROR** [L] req Combat 27, Kuudra Hot — Tiered Bonus: Hydra Strike (0/4) Every 0.2s, arrow attacks grant 1 stack of ⁑ Hydra Strike. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +2.8% Damage and +1.5% Arrow Speed. // At 10 stacks shoot +2 arrows that deal 30% Arrow Damage.
  - src: H: - | C: - | L: - | B: -
- **HYDRA1** [U] req - — -
  - src: C: - | L: - | B: -
- **INFERNAL_AURORA** [L] req Combat 42, Kuudra Infernal — Tiered Bonus: Arcane Energy (0/4) Every 1s, dealing Magic Damage from an ability grants 1 stack of Ѫ Arcane Energy. Lose 1 stack after 4s of not gaining a stack. // Ability: Homing Missiles SNEAK At 10 stacks, sneak to reset your stacks and shoot 5 homing missiles dealing 5,000 Damage each.
  - src: H: - | C: - | L: - | B: -
- **INFERNAL_CRIMSON** [L] req Combat 42, Kuudra Infernal — Tiered Bonus: Dominus (0/4) Every 1.5s, melee attacks grant 1 stack of ᝐ Dominus. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +0.1 Swing Range, +2 Ferocity and +10% Damage. // At 10 stacks swipe in a random direction dealing 1x Swipe Damage to mobs in its path.
  - src: H: - | C: - | L: - | B: -
- **INFERNAL_FERVOR** [L] req Combat 42, Kuudra Infernal — Full Set Bonus: Intimidate (0/4) Attacking a mob has a 50% chance to direct aggro from 3 mobs to you.
  - src: H: - | C: - | L: - | B: -
- **INFERNAL_HOLLOW** [L] req Combat 42, Kuudra Infernal — Tiered Bonus: Spirit (0/4) Every 3s, up to 5 nearby player's attacks grant 1 stack of ⚶ Spirit. (Max 40 Stacks) Lose 1 after 4s of not gaining a stack. // Stacks can be used with a Hollow Wand to cast spells.
  - src: H: - | C: - | L: - | B: -
- **INFERNAL_TERROR** [L] req Combat 42, Kuudra Infernal — Tiered Bonus: Hydra Strike (0/4) Every 0.2s, arrow attacks grant 1 stack of ⁑ Hydra Strike. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +5% Damage and +3% Arrow Speed. // At 10 stacks shoot +2 arrows that deal 60% Arrow Damage.
  - src: H: - | C: - | L: - | B: -
- **IRON** [?] req - — -
  - src: H: craft: 5x IRON_INGOT | C: craft: 8x IRON_INGOT | L: craft: 7x IRON_INGOT | B: craft: 4x IRON_INGOT
- **LAPIS_ARMOR** [U] req Mining 5 — Piece Bonus: Magnetic Earn +50% more Exp when mining.
  - src: H: - | C: - | L: - | B: -
- **LAVA_SEA_CREATURE** [R] req Fishing 35 — Damage taken from  Magmatic mobs is reduced by 5% and damage dealt is increased by 1.1x.
  - src: H: - | C: - | L: - | B: -
- **LEAFLET** [?] req - — -
  - src: H: craft: 5x LOG | C: craft: 8x LOG | L: craft: 7x LOG | B: craft: 4x LOG
- **LEATHER** [?] req - — -
  - src: H: craft: 5x LEATHER | C: craft: 8x LEATHER | L: craft: 7x LEATHER | B: craft: 4x LEATHER
- **MAGMA_LORD** [L] req Fishing 45 — Tiered Bonus: Fireproof (0/2) Provides immunity to Crimson Isle Lava. // Tiered Bonus: Lord's Blessing (0/5) Grants +1 Sea Creature Chance. Grants +20 Magic Find on  Magmatic mobs. // Damage taken from  Magmatic mobs is reduced by 15% and damage dealt is increased by 1.3x.
  - src: H: craft: 5x MAGMA_LORD_FRAGMENT | C: craft: 8x MAGMA_LORD_FRAGMENT | L: craft: 7x MAGMA_LORD_FRAGMENT | B: craft: 4x MAGMA_LORD_FRAGMENT
- **MASTIFF** [E] req Wolf Slayer 4 — Full Set Bonus: Absolute Unit (0/4) - Receive -20% damage from  Animal mobs. - Gain +1%❤ Health per 25 Crit Damage. (Max 40% HP). - Gain +1 Crit Damage per 2 Defense. - Heal 150❤ when hit, consuming 5 Vitality. (1s cooldown). - Your  Defense is capped at 300.
  - src: H: craft: 4x GOLDEN_TOOTH, 64x ENCHANTED_GOLD, 1x GROWTH_HELMET | C: craft: 4x GOLDEN_TOOTH, 64x ENCHANTED_GOLD, 1x GROWTH_CHESTPLATE | L: craft: 4x GOLDEN_TOOTH, 64x ENCHANTED_DIAMOND, 1x GROWTH_LEGGINGS | B: craft: 4x GOLDEN_TOOTH, 64x ENCHANTED_DIAMOND, 1x GROWTH_BOOTS
- **MELON** [U] req Farming 20 — Tiered Bonus: Cropier Crops (0/4) Farming Wheat, Carrots, and Potatoes has a 0.03% chance of dropping a Cropie. Inside the Greenhouse gives a 12.0% chance to drop one from any crop grown inside. Grants 0 Farming Fortune.
  - src: H: craft: 256x ENCHANTED_POTATO, 1x PUMPKIN_HELMET | C: craft: 256x ENCHANTED_POTATO, 1x PUMPKIN_CHESTPLATE | L: craft: 256x ENCHANTED_POTATO, 1x PUMPKIN_LEGGINGS | B: craft: 256x ENCHANTED_POTATO, 1x PUMPKIN_BOOTS
- **MENDER** [R] req - — Grants +80 Mending and  Vitality while in Dungeons.
  - src: H: - | H: -
- **MERCENARY** [R] req Combat 8 — Full Set Bonus: Death Tax (0/4) Earn +5 coins and heal 20❤ each kill. Only against level 10+ mobs!
  - src: H: - | C: - | L: - | B: -
- **MINERAL** [E] req - — Tiered Bonus: Mineralworks (0/4) Grants +0 Mining Spread on Ores and Blocks.
  - src: H: craft: 5x REFINED_MINERAL, 1x HARDENED_DIAMOND_HELMET | C: craft: 8x REFINED_MINERAL, 1x HARDENED_DIAMOND_CHESTPLATE | L: craft: 7x REFINED_MINERAL, 1x HARDENED_DIAMOND_LEGGINGS | B: craft: 4x REFINED_MINERAL, 1x HARDENED_DIAMOND_BOOTS
- **MINER_OUTFIT** [U] req - — Full Set Bonus: Beginner's Boost (0/4) Grants +40 Mining Speed.
  - src: H: craft: 5x IRON_BLOCK | C: craft: 8x IRON_BLOCK | L: craft: 7x IRON_BLOCK | B: craft: 4x IRON_BLOCK
- **MINOS_HUNTER** [U] req - — -
  - src: H: - | C: - | L: - | B: -
- **MONSTER_HUNTER** [R] req - — Ability: Block Damage If you are at full  Health, the first damage you take will be nullified. Cooldown: 60s
  - src: H: craft: 320x ENCHANTED_BONE | C: craft: 256x PRISMARINE_SHARD, 256x PRISMARINE_CRYSTALS | L: craft: 224x ENCHANTED_GUNPOWDER | B: craft: 256x ENCHANTED_STRING
- **MUSHROOM** [?] req Farming 7 — Full Set Bonus: Night Affinity (0/4) Grants the wearer permanent Night Vision while worn.
  - src: H: craft: 5x RED_MUSHROOM | C: craft: 8x RED_MUSHROOM | L: craft: 7x RED_MUSHROOM | B: craft: 4x RED_MUSHROOM
- **MYTHOS** [L] req - — Ability: Mythos' Might Grants 2x this armor's stats while in The Hub during Diana's Mythological Ritual. // Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. // Tiered Bonus: Familiarity (0/4) Grants +0 Magic Find on  Mythological mobs.
  - src: H: craft: 4x MYTHOS_FRAGMENT, 64x ENCHANTED_ANCIENT_CLAW, 1x CHALLENGER_HELMET | C: craft: 4x MYTHOS_FRAGMENT, 32x ENCHANTED_GOLD_BLOCK, 1x CHALLENGER_CHESTPLATE | L: craft: 4x MYTHOS_FRAGMENT, 64x GRIFFIN_FEATHER, 1x CHALLENGER_LEGGINGS | B: craft: 4x MYTHOS_FRAGMENT, 32x ENCHANTED_ANCIENT_CLAW, 1x CHALLENGER_BOOTS
- **NECROMANCER_LORD** [L] req F6 done — Full Set Bonus: Soul Whisper (0/4) Increase the damage of your necromancer summoned mobs by 20%. // Allows the wearer to store and summon an additional 1 souls in their necromancer weapon.
  - src: H: - | C: - | L: - | B: -
- **NUTCRACKER** [L] req Fishing 28 — Full Set Bonus: Cold Thumb (0/4) Allows the wearer to shoot unlimited snowballs when using Frosty the Snow Cannon. // Full Set Bonus: Battalion (0/4) Grants +1 Magic Find and +50 Defense to everyone within 30 blocks. Stacks up to 5 times. // Each piece grants a 7.5% bonus gift chance for every present you earn from the Gift Attack minigame! // All stats of this armor piece are doubled while on Jerry's Workshop!
  - src: H: craft: 15x WALNUT, 1x SNOW_SUIT_HELMET | C: craft: 24x WALNUT, 1x SNOW_SUIT_CHESTPLATE | L: craft: 21x WALNUT, 1x SNOW_SUIT_LEGGINGS | B: craft: 12x WALNUT, 1x SNOW_SUIT_BOOTS
- **OLD_DRAGON** [L] req Combat 16, Cata 10 — Full Set Bonus: Old Blood (0/4) Increases the strength of Growth, Protection, Feather Falling, Sugar Rush, and True Protection while worn.
  - src: H: craft: 50x OLD_FRAGMENT | C: craft: 80x OLD_FRAGMENT | L: craft: 70x OLD_FRAGMENT | B: craft: 40x OLD_FRAGMENT
- **PARK** [C] req - — * Soulbound *
  - src: H: - | C: - | L: - | B: -
- **PERFECT_TIER_12** [L] req Cata 21 — -
  - src: H: craft: 4x ENCHANTED_DIAMOND_BLOCK, 1x PERFECT_HELMET_11 | C: craft: 4x ENCHANTED_DIAMOND_BLOCK, 1x PERFECT_CHESTPLATE_11 | L: craft: 4x ENCHANTED_DIAMOND_BLOCK, 1x PERFECT_LEGGINGS_11 | B: craft: 4x ENCHANTED_DIAMOND_BLOCK, 1x PERFECT_BOOTS_11
- **PERFECT_TIER_13** [L] req Cata 21 — -
  - src: H: - | C: - | L: - | B: -
- **POWER_WITHER** [L] req F7 done — Full Set Bonus: Witherborn (0/4) Spawns a wither minion every 30 seconds up to a maximum 1 wither. Your withers will travel to and explode on nearby enemies.
  - src: H: craft: 8x GIANT_FRAGMENT_DIAMOND, 1x WITHER_HELMET | C: craft: 8x GIANT_FRAGMENT_DIAMOND, 1x WITHER_CHESTPLATE | L: craft: 8x GIANT_FRAGMENT_DIAMOND, 1x WITHER_LEGGINGS | B: craft: 8x GIANT_FRAGMENT_DIAMOND, 1x WITHER_BOOTS
- **PRIMORDIAL** [L] req Spider Slayer 7, Cata 16 — Piece Bonus: Spider Bulwark Kill Spiders to accumulate defense against them. Piece Bonus: +0 Next Upgrade: +20 (0/50) // This item is Arachnal ! // Extra Bonus: Radioactive Grants +1.5 Crit Damage per 10 Strength. (Max 1,000 Strength)
  - src: H: craft: 6x ENCHANTED_IRON_BLOCK, 1x TARANTULA_CATALYST, 1x TARANTULA_HELMET, 64x TARANTULA_SILK | C: craft: 6x ENCHANTED_IRON_BLOCK, 1x TARANTULA_CATALYST, 1x TARANTULA_CHESTPLATE, 64x TARANTULA_SILK | L: craft: 6x ENCHANTED_IRON_BLOCK, 1x TARANTULA_CATALYST, 1x TARANTULA_LEGGINGS, 64x TARANTULA_SILK | B: craft: 6x ENCHANTED_IRON_BLOCK, 1x TARANTULA_CATALYST, 1x TARANTULA_BOOTS, 64x TARANTULA_SILK
- **PROTECTOR_DRAGON** [L] req Combat 16, Cata 12 — Full Set Bonus: Protective Blood (0/4) Increases the defense of each armor piece by +1%  Defense for each missing percent of HP.
  - src: H: craft: 50x PROTECTOR_FRAGMENT | C: craft: 80x PROTECTOR_FRAGMENT | L: craft: 70x PROTECTOR_FRAGMENT | B: craft: 40x PROTECTOR_FRAGMENT
- **PUMPKIN** [U] req Farming 15 — -
  - src: H: craft: 64x ENCHANTED_CARROT, 1x FARM_ARMOR_HELMET | C: craft: 64x ENCHANTED_CARROT, 1x FARM_ARMOR_CHESTPLATE | L: craft: 64x ENCHANTED_CARROT, 1x FARM_ARMOR_LEGGINGS | B: craft: 64x ENCHANTED_CARROT, 1x FARM_ARMOR_BOOTS
- **RABBIT** [U] req - — Full Set Bonus: Springsneak SNEAK While sneaking, you have permanent Jump Boost II.
  - src: H: - | H: craft: 5x RABBIT_HIDE | C: craft: 8x RABBIT_HIDE | L: craft: 7x RABBIT_HIDE | B: craft: 4x RABBIT_HIDE
- **RAMPART** [R] req Combat 22 — Grants +50❤ Health, +20❁ Strength, and +15☠ Crit Damage while on the Crimson Isle. // * Co-op Soulbound *
  - src: H: - | C: - | L: - | B: -
- **REAPER** [L] req Zombie Slayer 7, Cata 24 — Full Set Bonus: Enrage SNEAK Enrage for 6s gaining 100 Speed, 100 Damage, and 100 Strength. Cooldown: 25s // Full Set Bonus: Trolling The Reaper (0/3) Healing Wands heal +50%. Gain +100 against  Undead mobs. Deal +100% damage to  Undead mobs but 1% to all other mobs. // Piece Bonus: Zombie Bulwark Kill Zombies to accumulate defense against them. Piece Bonus: +0 Next Upgrade: +20 (0/50) // This item is Undead !
  - src: H: craft: 128x REVENANT_VISCERA, 1x BEHEADED_HORROR, 32x ENCHANTED_STRING, 1x REVIVED_HEART | C: craft: 6x ENCHANTED_DIAMOND_BLOCK, 1x REVENANT_CATALYST, 1x REVENANT_CHESTPLATE, 64x REVENANT_VISCERA | L: craft: 6x ENCHANTED_DIAMOND_BLOCK, 1x REVENANT_CATALYST, 1x REVENANT_LEGGINGS, 64x REVENANT_VISCERA | B: craft: 6x ENCHANTED_DIAMOND_BLOCK, 1x REVENANT_CATALYST, 1x REVENANT_BOOTS, 64x REVENANT_VISCERA
- **REKINDLED_EMBER** [E] req Combat 22 — Tiered Bonus: Rekindle (0/4) Increase all of your outgoing burning damage by 200%. For each second you are on fire additionally increase all burning damage by 1.2% up to 50%. When wearing 2 or more armor pieces of this set, melee attacks from ignited mobs will set you on fire!
  - src: H: craft: 5x REKINDLED_EMBER_FRAGMENT | C: craft: 8x REKINDLED_EMBER_FRAGMENT | L: craft: 7x REKINDLED_EMBER_FRAGMENT | B: craft: 4x REKINDLED_EMBER_FRAGMENT
- **REVENANT** [E] req Zombie Slayer 4 — Full Set Bonus: Trolling The Reaper (0/3) Healing Wands heal +50%. Gain +100 against  Undead mobs. // Piece Bonus: Zombie Bulwark Kill Zombies to accumulate defense against them. Piece Bonus: +0 Next Upgrade: +20 (0/50) // This item is Undead !
  - src: C: craft: 128x ENCHANTED_DIAMOND, 4x REVENANT_VISCERA | L: craft: 3x REVENANT_VISCERA, 64x ENCHANTED_DIAMOND | B: craft: 64x ENCHANTED_DIAMOND, 2x REVENANT_VISCERA
- **ROSETTA** [U] req - — Growth I Grants +15  Health.
  - src: H: - | C: - | L: - | B: -
- **ROTTEN** [R] req Cata 1 — Full Set Bonus: Sieve Body (0/4) Gain an additional 20% knockback resistance to arrows. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **SALMON** [?] req - — Ability: Water Burst  SNEAK When wearing the full set, grants the wearer with the ability to burst forward when sneaking in water. Mana Cost: 20
  - src: H: craft: 8x RAW_FISH-1 | H: - | C: - | L: - | B: -
- **SALMON_NEW** [R] req Fishing 13 — Ability: Water Burst  SNEAK When wearing the full set, grants the wearer with the ability to burst forward when sneaking in water. Mana Cost: 20
  - src: H: craft: 5x ENCHANTED_RAW_SALMON | C: craft: 8x ENCHANTED_RAW_SALMON | L: craft: 7x ENCHANTED_RAW_SALMON | B: craft: 4x ENCHANTED_RAW_SALMON
- **SEA_WALKER** [U] req - — -
  - src: H: - | C: - | L: - | B: -
- **SEYMOUR** [R] req - — -
  - src: H: - | C: - | L: - | B: -
- **SHADOW_ASSASSIN** [E] req F5 done — Piece Bonus: Pursuit On teleport, gain invisibility and +20 Speed for 10 seconds. Cooldown: 3s // Full Set Bonus: Shadow Assassin (0/4) Grants +1 Strength for every mob you kill in The Catacombs. (Strength resets after each run)
  - src: H: - | C: - | L: - | B: -
- **SHARK_SCALE** [L] req Fishing 24 — Full Set Bonus: Absorb (0/4) Doubles your  Defense while in water. // Tiered Bonus: Festival Fisher (0/4) Grants a +0% chance to catch Sharks during the Fishing Festival.
  - src: H: craft: 2x ENCHANTED_SHARK_FIN, 1x SPONGE_HELMET | C: craft: 2x ENCHANTED_SHARK_FIN, 1x SPONGE_CHESTPLATE | L: craft: 2x ENCHANTED_SHARK_FIN, 1x SPONGE_LEGGINGS | B: craft: 2x ENCHANTED_SHARK_FIN, 1x SPONGE_BOOTS
- **SHIMMERING_LIGHT** [R] req Combat 22 — Tiered Bonus: Shimmer (0/4) Increases all Experience Orbs gained from monsters and ores by 200%.
  - src: H: craft: 150x X, 2x ENCHANTED_MYCELIUM_CUBE | C: craft: 5x ENCHANTED_MYCELIUM_CUBE, 60x Y, 10x Z | L: craft: 4x ENCHANTED_MYCELIUM_CUBE, 10x Z, 60x Y | B: craft: 2x ENCHANTED_MYCELIUM_CUBE, 100x X
- **SILVER_HUNTER** [R] req Trophy Silver — Full Set Bonus: Star Fisher (0/4) Grants +0.4 Fishing Speed per ✪ // * Soulbound *
  - src: H: - | C: - | L: - | B: -
- **SKELETON_GRUNT** [E] req Cata 1 — Increase the damage you deal with arrows by 5%. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **SKELETON_LORD** [E] req Cata 25 — Full Set Bonus: Dungeon Lord (0/4) Gains +5❁ Strength and +10☠ Crit Damage every minute spent in the Dungeon. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **SKELETON_MASTER** [E] req Cata 12 — Full Set Bonus: Skeleton Master (0/4) Increase the damage you deal with arrows by 25%. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **SKELETON_SOLDIER** [E] req Cata 9 — Full Set Bonus: Skeleton Soldier (0/4) Increase the damage you deal with arrows by an extra 25%. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **SKELETOR** [E] req Cata 15 — Full Set Bonus: Skeletor (0/4) Grants +1❁ Strength and +1☠ Crit Damage for every 10 Skeletor kills // Each piece grants a 1 second cooldown reduction on bone plating! // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **SNORKELING** [U] req Foraging 16 — Tiered Bonus: Long Tuba (0/4) Grants +2 Respiration.
  - src: H: - | C: - | L: - | B: -
- **SNOW_SUIT** [E] req - — Full Set Bonus: Cold Thumb (0/8) Allows the wearer to shoot unlimited snowballs when using Frosty the Snow Cannon. // Each piece grants a 5% bonus gift chance for every present you earn from the Gift Attack minigame! // All stats of this armor piece are doubled while on Jerry's Workshop!
  - src: H: - | C: - | L: - | B: -
- **SORROW** [L] req HotM 5 (or Island) — Piece Set Bonus: Mist Aura Hides the wearer in a guise of mist, multiplying damage from ghosts by 0.6x.
  - src: H: craft: 15x SORROW | C: craft: 24x SORROW | L: craft: 21x SORROW | B: craft: 12x SORROW
- **SPEEDSTER** [U] req - — Full Set Bonus: Bonus Speed (0/4) Increases  Speed by +20.
  - src: H: craft: 5x ENCHANTED_SUGAR | C: craft: 8x ENCHANTED_SUGAR | L: craft: 7x ENCHANTED_SUGAR | B: craft: 4x ENCHANTED_SUGAR
- **SPEED_WITHER** [L] req F7 done — Full Set Bonus: Witherborn (0/4) Spawns a wither minion every 30 seconds up to a maximum 1 wither. Your withers will travel to and explode on nearby enemies.
  - src: H: craft: 8x GIANT_FRAGMENT_BIGFOOT, 1x WITHER_HELMET | C: craft: 8x GIANT_FRAGMENT_BIGFOOT, 1x WITHER_CHESTPLATE | L: craft: 8x GIANT_FRAGMENT_BIGFOOT, 1x WITHER_LEGGINGS | B: craft: 8x GIANT_FRAGMENT_BIGFOOT, 1x WITHER_BOOTS
- **SPONGE** [E] req Fishing 17 — Full Set Bonus: Absorb (0/4) Doubles your  Defense while in water.
  - src: H: craft: 5x ENCHANTED_WET_SPONGE | C: craft: 8x ENCHANTED_WET_SPONGE | L: craft: 7x ENCHANTED_WET_SPONGE | B: craft: 4x ENCHANTED_WET_SPONGE
- **SPOOKY** [E] req - — Full Set Bonus: Candy Man (0/4) Grants a +5% chance to find rare Candy.
  - src: H: - | C: - | L: - | B: -
- **SQUASH** [R] req Farming 35 — Tiered Bonus: Mento Fermento (0/4) Farming Sugar Cane, Cacti, Mushrooms, and Nether Warts has a 0.005% chance of dropping a Fermento. Inside the Greenhouse gives a 2.0% chance to drop one from any crop grown inside. Grants 0 Farming Fortune.
  - src: H: craft: 20x SQUASH, 8x POLISHED_PUMPKIN, 48x ENCHANTED_MELON_BLOCK, 1x CROPIE_HELMET | C: craft: 20x SQUASH, 1x CROPIE_CHESTPLATE, 48x ENCHANTED_MELON_BLOCK, 8x POLISHED_PUMPKIN, 30x ENCHANTED_COOKIE | L: craft: 20x SQUASH, 8x POLISHED_PUMPKIN, 48x ENCHANTED_MELON_BLOCK, 1x CROPIE_LEGGINGS, 20x ENCHANTED_COOKIE | B: craft: 20x SQUASH, 12x POLISHED_PUMPKIN, 1x CROPIE_BOOTS
- **SQUID** [?] req - — Your shoes spit out Squid ink!
  - src: H: craft: 8x INK_SACK | B: -
- **SQUIRE** [U] req Combat 4 — -
  - src: H: - | C: - | L: - | B: -
- **STAR** [X] req - — -
  - src: H: - | C: - | L: - | B: -
- **STARLIGHT** [R] req Combat 8 — Full Set Bonus: Starpower (0/4) Increases the duration of Starfall by 2x and its range by +1.
  - src: H: - | C: - | L: - | B: -
- **STRONG_DRAGON** [L] req Combat 16, Cata 12 — Full Set Bonus: Strong Blood (0/4) Improves Aspect of the End ⋗ +75 Damage // Instant Transmission: ⋗ +2 teleport range ⋗ +3 seconds ⋗ +5 Strength on cast
  - src: H: craft: 50x STRONG_FRAGMENT | C: craft: 80x STRONG_FRAGMENT | L: craft: 70x STRONG_FRAGMENT | B: craft: 40x STRONG_FRAGMENT
- **SUPERIOR_DRAGON** [L] req Combat 20, Cata 14 — Full Set Bonus: Superior Blood (0/4) Increases all Combat stats and  Magic Find by 5% and Aspect of the Dragons ability deals 50% more damage.
  - src: H: craft: 50x SUPERIOR_FRAGMENT | C: craft: 80x SUPERIOR_FRAGMENT | L: craft: 70x SUPERIOR_FRAGMENT | B: craft: 40x SUPERIOR_FRAGMENT
- **SUPER_HEAVY** [E] req Cata 15 — Full Set Bonus: Vindicate (0/4) Grants +1 walk speed for every 50 defense that you have.
  - src: H: - | C: - | L: - | B: -
- **TANK_MINER** [R] req - — Ability: Depth Coating Doubles this piece's  Defense while on Mining Islands.
  - src: H: - | C: - | L: - | B: -
- **TANK_WITHER** [L] req F7 done — Full Set Bonus: Witherborn (0/4) Spawns a wither minion every 30 seconds up to a maximum 1 wither. Your withers will travel to and explode on nearby enemies.
  - src: H: craft: 8x GIANT_FRAGMENT_BOULDER, 1x WITHER_HELMET | C: craft: 8x GIANT_FRAGMENT_BOULDER, 1x WITHER_CHESTPLATE | L: craft: 8x GIANT_FRAGMENT_BOULDER, 1x WITHER_LEGGINGS | B: craft: 8x GIANT_FRAGMENT_BOULDER, 1x WITHER_BOOTS
- **TARANTULA** [E] req Spider Slayer 4, Cata 16 — Full Set Bonus: Octodexterity (0/4) Every 4th strike, deal +100%  Damage and apply Venom reducing healing by 40% for 4 seconds. // Piece Bonus: Spider Bulwark Kill Spiders to accumulate defense against them. Piece Bonus: +0 Next Upgrade: +20 (0/50) // This item is Arachnal ! // Extra Bonus: Radioactive Grants +1 Crit Damage per 10 Strength. (Max 1,000 Strength)
  - src: H: craft: 3x TARANTULA_SILK, 128x ENCHANTED_IRON | C: craft: 256x ENCHANTED_IRON, 4x TARANTULA_SILK | L: craft: 3x TARANTULA_SILK, 256x ENCHANTED_IRON
- **TERROR** [L] req Combat 22, Kuudra None — Tiered Bonus: Hydra Strike (0/4) Every 0.2s, arrow attacks grant 1 stack of ⁑ Hydra Strike. Lose 1 stack after 4s of not gaining a stack. // Each stack grants +2% Damage and +1% Arrow Speed. // At 10 stacks shoot +2 arrows that deal 20% Arrow Damage.
  - src: H: - | C: - | L: - | B: -
- **THERMODYNAMIC** [L] req Combat 30 — Full Set Bonus: Newton's Demise (0/4) Grants +50 Attack Speed Cap.
  - src: H: craft: 32x CANE_KNOT, 256x ENCHANTED_SUGAR_CANE, 1x SPEEDSTER_HELMET | C: craft: 32x CANE_KNOT, 256x ENCHANTED_SUGAR_CANE, 1x SPEEDSTER_CHESTPLATE | L: craft: 32x CANE_KNOT, 256x ENCHANTED_SUGAR_CANE, 1x SPEEDSTER_LEGGINGS | B: craft: 32x CANE_KNOT, 256x ENCHANTED_SUGAR_CANE, 1x SPEEDSTER_BOOTS
- **THUNDER** [E] req Fishing 36 — Tiered Bonus: Static Charge (0/5) Gains 1 static charge every 30 seconds. Hitting a target will cause a discharge, adding 15% damage to that hit for each charge collected. // Maximum Charge Capacity: 2 // Damage taken from  Magmatic mobs is reduced by 10% and damage dealt is increased by 1.2x.
  - src: H: craft: 5x THUNDER_SHARDS | C: craft: 8x THUNDER_SHARDS | L: craft: 7x THUNDER_SHARDS | B: craft: 4x THUNDER_SHARDS
- **TITAN** [X] req - — -
  - src: H: - | C: - | L: - | B: -
- **UNSTABLE_DRAGON** [L] req Combat 16, Cata 10 — Full Set Bonus: Unstable Blood (0/4) Sometimes strikes nearby mobs with lightning. It's unstable!
  - src: H: craft: 50x UNSTABLE_FRAGMENT | C: craft: 80x UNSTABLE_FRAGMENT | L: craft: 70x UNSTABLE_FRAGMENT | B: craft: 40x UNSTABLE_FRAGMENT
- **VANGUARD** [L] req - — -
  - src: H: - | C: - | L: - | B: -
- **WEREWOLF** [L] req Cata 20 — Full Set Bonus: Regenerative Howl (0/4) Upon activating  Ferocity gain 50 Defense for 5s (up to 10 stacks).
  - src: H: craft: 20x WEREWOLF_SKIN | C: craft: 32x WEREWOLF_SKIN | L: craft: 28x WEREWOLF_SKIN | B: craft: 16x WEREWOLF_SKIN
- **WISE_DRAGON** [L] req Combat 16, Cata 12 — Full Set Bonus: Wise Blood (0/4) Abilities have 2/3 of the mana cost.
  - src: H: craft: 50x WISE_FRAGMENT | C: craft: 80x WISE_FRAGMENT | L: craft: 70x WISE_FRAGMENT | B: craft: 40x WISE_FRAGMENT
- **WISE_WITHER** [L] req F7 done — Full Set Bonus: Witherborn (0/4) Spawns a wither minion every 30 seconds up to a maximum 1 wither. Your withers will travel to and explode on nearby enemies.
  - src: H: craft: 8x GIANT_FRAGMENT_LASER, 1x WITHER_HELMET | C: craft: 8x GIANT_FRAGMENT_LASER, 1x WITHER_CHESTPLATE | L: craft: 8x GIANT_FRAGMENT_LASER, 1x WITHER_LEGGINGS | B: craft: 8x GIANT_FRAGMENT_LASER, 1x WITHER_BOOTS
- **WITHER** [L] req F7 done — Full Set Bonus: Witherborn (0/4) Spawns a wither minion every 30 seconds up to a maximum 1 wither. Your withers will travel to and explode on nearby enemies.
  - src: H: - | C: - | L: - | B: -
- **WYLD** [U] req - — Rift Time: +15s
  - src: H: - | C: - | L: - | B: -
- **YOUNG_DRAGON** [L] req Combat 16, Cata 10 — Full Set Bonus: Young Blood (0/4) Gain +70 Walk Speed while you are above 50% HP. +100 Walk Speed Cap
  - src: H: craft: 50x YOUNG_FRAGMENT | C: craft: 80x YOUNG_FRAGMENT | L: craft: 70x YOUNG_FRAGMENT | B: craft: 40x YOUNG_FRAGMENT
- **ZOMBIE** [?] req - — Full Set Bonus: Projectile Absorption (0/3) Heals the wearer for 10 per second for 5 seconds when hit by a projectile.
  - src: H: craft: 8x ROTTEN_FLESH | C: craft: 4x ENCHANTED_ROTTEN_FLESH, 4x ZOMBIE_HEART | L: craft: 3x ZOMBIE_HEART, 4x ENCHANTED_ROTTEN_FLESH | B: craft: 2x ENCHANTED_ROTTEN_FLESH, 2x ZOMBIE_HEART
- **ZOMBIE_COMMANDER** [E] req Cata 18 — Full Set Bonus: Training (0/4) Every 50 Zombies killed gives the wearer +5 ❤ Health while wearing the set. Max 500. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **ZOMBIE_KNIGHT** [E] req Cata 14 — Full Set Bonus: Zombie Knight (0/4) Gains +50❈ Defense when used with the Zombie Knight Sword // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **ZOMBIE_LORD** [E] req Cata 25 — Full Set Bonus: Dungeon Lord (0/4) Gains +10❁ Strength and +10❈ Defense every minute spent in the Dungeon. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -
- **ZOMBIE_SOLDIER** [E] req Cata 9 — Full Set Bonus: Shoal (0/4) Gain +30❈ Defense for each Zombie Soldier Set within 30 blocks. // This item is Undead ༕!
  - src: H: - | C: - | L: - | B: -

## Appendix B — All weapons: swords, longswords, bows, arrows, wands

Format: **Name** (`ID`, tier) — stats — requirement — recipe, then the lore (ability text, base numbers).

- **Basic Chicken Axe** (`BASIC_CHICKEN_AXE`, C) — Dmg +10 — req: - — recipe: -
  - Chickens killed with this drop 1 additional Feather and Raw Chicken.
- **Basic Cow Axe** (`BASIC_COW_AXE`, C) — Dmg +10 — req: - — recipe: -
  - Cows killed with this drop 1 additional Raw Beef.
- **Basic Mooshroom Axe** (`BASIC_MUSHROOM_COW_AXE`, C) — Dmg +10 — req: - — recipe: -
  - Mooshrooms killed with this drop 1 additional Red Mushroom and Leather.
- **Basic Pig Axe** (`BASIC_PIG_AXE`, C) — Dmg +10 — req: - — recipe: -
  - Pigs killed with this drop 1 additional Raw Porkchop.
- **Basic Rabbit Axe** (`BASIC_RABBIT_AXE`, C) — Dmg +10 — req: - — recipe: -
  - Rabbits killed with this drop 1 additional Raw Rabbit, Rabbit Hide, and Rabbit's Foot.
- **Basic Sheep Axe** (`BASIC_SHEEP_AXE`, C) — Dmg +10 — req: - — recipe: -
  - Sheep killed with this drop 1 additional White Wool and Raw Mutton.
- **Advanced Chicken Axe** (`CHICKEN_AXE`, U) — Dmg +20 — req: Farming 15 — recipe: -
  - Chickens killed with this drop 2 additional Feather and Raw Chicken.
- **Advanced Cow Axe** (`COW_AXE`, U) — Dmg +20 — req: Farming 15 — recipe: -
  - Cows killed with this drop 2 additional Raw Beef.
- **Advanced Mooshroom Axe** (`MUSHROOM_COW_AXE`, U) — Dmg +20 — req: Farming 15 — recipe: -
  - Mooshrooms killed with this drop 2 additional Red Mushroom and Leather.
- **Advanced Pig Axe** (`PIG_AXE`, U) — Dmg +20 — req: Farming 15 — recipe: -
  - Pigs killed with this drop 2 additional Raw Porkchop.
- **Advanced Rabbit Axe** (`RABBIT_AXE`, U) — Dmg +20 — req: Farming 15 — recipe: -
  - Rabbits killed with this drop 2 additional Raw Rabbit, Rabbit Hide, and Rabbit's Foot.
- **Advanced Sheep Axe** (`SHEEP_AXE`, U) — Dmg +20 — req: Farming 15 — recipe: -
  - Sheep killed with this drop 2 additional White Wool and Raw Mutton.
- **Aspect of the Jerry, Signature Edition** (`ASPECT_OF_THE_JERRY_SIGNATURE`, U) — Dmg +2 — req: - — recipe: -
  - Sharpness X Increases melee damage dealt by 50%. // Ability: Parley  RIGHT CLICK Channel your inner Jerry. Cooldown: 5s
- **Aspect of the Leech** (`ASPECT_OF_THE_LEECH_1`, U) — - — req: - — recipe: craft: 128x LIL_PAD, 8x LEECH_SUPREME_FRAGMENT
  - Ability: Weird Transmission  RIGHT CLICK Teleport 3 blocks ahead of you. Mana Cost: 60
- **Celeste Wand** (`CELESTE_WAND`, U) — AbilDmg(base) +250 — req: Combat 4 — recipe: -
  - Ability: Lightning Strike  RIGHT CLICK Strikes lightning up to 10 blocks away, dealing 40 damage to the nearest monster. Mana Cost: 70
- **Cleaver** (`CLEAVER`, U) — Dmg +40, Str +10 — req: - — recipe: craft: 4x GOLD_INGOT, 2x STICK
  - Ability: Cleave When hitting an entity, monsters in a 3 block range will be hit for a portion of that damage too.
- **Decent Bow** (`DECENT_BOW`, U) — Dmg +45 — req: - — recipe: -
- **Diamond Sword** (`DIAMOND_SWORD`, U) — Dmg +35 — req: - — recipe: craft: 2x DIAMOND, 1x STICK
- **Emerald-tipped Arrow** (`EMERALD_TIPPED_ARROW`, U) — Dmg +20 — req: Combat 18, Target_Practice — recipe: -
- **End Sword** (`END_SWORD`, U) — Dmg +35 — req: - — recipe: craft: 2x ENDER_PEARL, 1x STICK
  - Deals +100% damage to  Ender mobs.
- **Flaming Sword** (`FLAMING_SWORD`, U) — Dmg +50, Str +20 — req: - — recipe: -
  - Ignites enemies for 3s.
- **Harvesting Sword** (`HARVESTING_SWORD`, U) — Dmg +50 — req: - — recipe: -
- **Hunter Knife** (`HUNTER_KNIFE`, U) — Spd +40, Dmg +50 — req: - — recipe: trade
  - * Co-op Soulbound *
- **Prismarine Blade** (`PRISMARINE_BLADE`, U) — Dmg +50, Str +25 — req: - — recipe: craft: 64x PRISMARINE_SHARD, 1x STICK
  - Deals +200% damage while in water.
- **Prismarine Bow** (`PRISMARINE_BOW`, U) — Dmg +50, Str +25 — req: - — recipe: craft: 48x PRISMARINE_SHARD, 3x STRING
  - Ability: Arrow Infusion  ON SHOOT Consumes Prismarine Shards in your Inventory or Quiver which can harm sea creatures and deal +300% damage to Squids, Guardians and Elder Guardians.
- **Recluse Fang** (`RECLUSE_FANG`, U) — Dmg +60, Str +30, CD +10 — req: Spider Slayer 2 — recipe: craft: 16x TARANTULA_WEB, 1x SPIDER_CATALYST, 1x SPIDER_SWORD
  - Deal +150%  Damage to  Arthropod mobs. // Ability: Webcutter This weapon can be used to clear webs left by the Tarantula Broodfather!
- **Redstone-tipped Arrow** (`REDSTONE_TIPPED_ARROW`, U) — Dmg +15 — req: Combat 13, Target_Practice — recipe: -
- **Savanna Bow** (`SAVANA_BOW`, U) — Dmg +50 — req: - — recipe: craft: 3x ENCHANTED_ACACIA_LOG, 3x ENCHANTED_STRING
  - All damage dealt with this bow is doubled.
- **Squire Sword** (`SQUIRE_SWORD`, U) — Str +10, Dmg +50 — req: Combat 4 — recipe: -
- **Stun Bow** (`ARCHER_DUNGEON_ABILITY_2`, U) — - — req: - — recipe: -
- **Sword of Bad Health** (`SWORD_OF_BAD_HEALTH`, U) — Dmg +200, Str +50, CD +50 — req: Combat 22 — recipe: -
  - Ability: Bad Health  RIGHT CLICK Gain +5 Strength for every 5% of total HP you have for 5s. Capped at +100 Strength. Cooldown: 5s
- **Venomous Sword** (`VENOMOUS_SWORD`, U) — Dmg +30 — req: - — recipe: -
- **Voidwalker Katana** (`VOIDWALKER_KATANA`, U) — Dmg +105, Str +40, CD +15 — req: - — recipe: craft: 32x NULL_SPHERE, 1x STICK
  - Deal +150% damage to Endermen. Receive 3% less damage from Endermen when held.
- **Wand of Healing** (`WAND_OF_HEALING`, U) — - — req: Zombie Slayer 1 — recipe: craft: 16x REVENANT_FLESH, 1x STICK
  - Ability: Small Heal  RIGHT CLICK Heal 60❤ per second for 5s. Wand heals don't stack. Cooldown: 1s
- **Wither Bow** (`WITHER_BOW`, U) — Dmg +30 — req: - — recipe: -
  - Deals +100% damage to  Wither mobs.
- **Wyld Sword** (`WYLD_SWORD`, U) — - — req: - — recipe: -
  - Rift Damage: +2 // Could become anything. It's a wyldcard!
- **Armorshred Arrow** (`ARMORSHRED_ARROW`, R) — Dmg +5 — req: Combat 22, Sand coll 7, Target_Practice — recipe: -
  - Passive: Defense Reduction Hits ignore 5% of target's ❈ Defense.
- **Artisanal Shortbow** (`ARTISANAL_SHORTBOW`, R) — Dmg +40 — req: Combat 4 — recipe: -
  - Shortbow: Instantly shoots!
- **Aspect of the End** (`ASPECT_OF_THE_END`, R) — Dmg +100, Str +100 — req: - — recipe: craft: 32x ENCHANTED_EYE_OF_ENDER, 1x ENCHANTED_DIAMOND
  - Ability: Instant Transmission  RIGHT CLICK Teleport 8 blocks ahead of you and gain +50  Speed for 3 seconds. Mana Cost: 50
- **Aspect of the Leech** (`ASPECT_OF_THE_LEECH_2`, R) — - — req: - — recipe: craft: 2x TRANSMISSION_TUNER, 1x ASPECT_OF_THE_LEECH_1; craft: 2x TRANSMISSION_TUNER, 1x ASPECT_OF_THE_LEECH_1
  - Ability: Weird Transmission  RIGHT CLICK Teleport 4 blocks ahead of you. Mana Cost: 60
- **Blade of the Volcano** (`BLADE_OF_THE_VOLCANO`, R) — Dmg +220, Str +70, CD +20 — req: Combat 22 — recipe: -
  - A powerful blade forged in the depths of the Blazing Volcano. // Gain +100 Strength against enemies who are in lava.
- **Bonzo's Staff** (`BONZO_STAFF`, R) — Dmg +160, AbilDmg(base) +1000, Int +250 — req: F1 done — recipe: -
  - Ability: Showtime  RIGHT CLICK Shoots balloons that create a large explosion on impact, dealing up to 1,000 damage. Mana Cost: 90
- **Bouncy Arrow** (`BOUNCY_ARROW`, R) — Dmg +5 — req: Combat 20, Slime_Ball coll 7, Target_Practice — recipe: -
  - Passive: Bouncy! Arrows have a 50% chance to bounce to another target. Bounces up to 4 targets, each bounce dealing 15% less damage.
- **Conjuring** (`CONJURING_SWORD`, R) — Dmg 68-130, Str 36-70, Int 85-180 — req: Cata 13 — recipe: -
  - Reduces the cooldown of Guided Sheep by 5 seconds, and prevents Guided Sheep from spawning randomly while holding this item.
- **Dreadlord Sword** (`CRYPT_DREADLORD_SWORD`, R) — Dmg 52-100, Str 23-45, AbilDmg(base) +500, Int 85-180 — req: Cata 3 — recipe: -
  - Ability: Dreadlord  RIGHT CLICK Shoot a skull that deals 504 damage. Mana Cost: 40
- **Edible Mace** (`EDIBLE_MACE`, R) — Dmg +125, Str +25 — req: Wolf Slayer 5 — recipe: craft: 18x ENCHANTED_MUTTON, 18x GOLDEN_TOOTH
  - Ability: ME SMASH HEAD  RIGHT CLICK Your next attack deals double damage and weakens  Animal mobs, making them deal -35% damage for 30 seconds. Debuff doesn't stack. Mana Cost: 100
- **Electric Wand** (`ELECTRIC_WAND_ITEM`, R) — Dmg +10 — req: - — recipe: -
- **Ender Bow** (`ENDER_BOW`, R) — Dmg +60 — req: - — recipe: craft: 12x ENCHANTED_ENDER_PEARL, 3x STRING
  - Ability: Ender Warp  LEFT CLICK Shoots an Ender Pearl. Upon landing you deal damage to all Monsters in a 8 block radius for 10% of their  Health. (Max 500 damage) Mana Cost: 50 Cooldown: 5s
- **Explosive Arrow** (`EXPLOSIVE_ARROW`, R) — Dmg +3 — req: Combat 23, Coal coll 7, Target_Practice — recipe: -
  - Passive: Explosive This arrow explodes after 2s or on impact. // The explosion deals the arrow's damage split to mobs within 2.5 blocks. // You will lose 10% of your ❤ Health if you are in the explosion.
- **Firedust Dagger** (`FIREDUST_DAGGER`, R) — Dmg +90, Str +45, CD +15 — req: Blaze Slayer 2 — recipe: craft: 30x MAGMA_FISH_SILVER, 1x MOLTEN_POWDER, 1x STICK
  - Deal 1.2x damage to  Infernal mobs. Deal 1.1x damage to  Wither mobs. Gain +15☯ Combat Wisdom against  Infernal mobs. // Ability: Attunement  RIGHT CLICK Swap between Ashen and Auric mode in order to attack through Hellion Shields. // Attuned: Ashen // Ability: Burning Vengeance ﬗ On hit, repeat the last 8 hits of dealt damage after 6s if the Hellion Shield has switched attunement.
- **Frozen Scythe** (`FROZEN_SCYTHE`, R) — Dmg +80, AbilDmg(base) +1000 — req: Cata 10 — recipe: craft: 64x ENCHANTED_PACKED_ICE, 2x STICK
  - Ability: Ice Bolt  RIGHT CLICK Shoots 1 Ice Bolt that deals 1,000 damage and slows enemies hit for 5 seconds! Mana Cost: 50
- **Gateway Wand** (`GATEWAY_WAND`, R) — - — req: - — recipe: -
- **Glue Arrow** (`GLUE_ARROW`, R) — Dmg +15 — req: Combat 24, Spider Slayer 3, Target_Practice — recipe: -
  - Passive: Stuck in goo Prevents victims from walking for 1.5s when hit. 6s cooldown per victim
- **Golem Sword** (`GOLEM_SWORD`, R) — Def +25, Dmg +80, Str +125, AbilDmg(base) +250 — req: - — recipe: craft: 2x ENCHANTED_IRON_BLOCK, 1x STICK
  - Ability: Iron Punch  RIGHT CLICK Punch the ground, damaging enemies in a hexagon around you for 250 base Magic Damage. Mana Cost: 70 Cooldown: 3s
- **Healing Bow** (`ARCHER_DUNGEON_ABILITY_3`, R) — - — req: - — recipe: -
- **Healing Circle** (`HEALING_CIRCLE_ITEM`, R) — Dmg +10 — req: - — recipe: -
- **Ice Spray Wand** (`ICE_SPRAY_WAND`, R) — Dmg 52-100, AbilDmg(base) +17000, Int 118-250 — req: Cata 20 — recipe: -
  - Ability: Ice Spray  RIGHT CLICK Produces a cone of ice in front of the caster that deals 17,000 damage to mobs and freezes them in place for 5 seconds! Frozen mobs take 10% increased damage! Mana Cost: 50 Cooldown: 5s
- **Icy Arrow** (`ICY_ARROW`, R) — Dmg +24 — req: Combat 21, Ice coll 7, Target_Practice — recipe: -
  - Passive: Muscle Freeze Applies Slowness 1 for 3s to targets.
- **Leech Sword** (`LEECH_SWORD`, R) — - — req: - — recipe: craft: 12x DEADGEHOG_SPINE, 4x LEECH_SUPREME_FRAGMENT, 1x WYLD_SWORD
  - Regens 3 Mana on hit.
- **Machine Gun Shortbow** (`MACHINE_GUN_BOW`, R) — Dmg 75-159, Str 20-44, CD 12-26 — req: Cata 2 — recipe: -
  - Ability: Rapid Fire  RIGHT CLICK Shoots 5 Arrows per second for 8 seconds! Arrows deal 70% of what they would normally deal. Cooldown: 100s // Shortbow: Instantly shoots!
- **Mercenary Sword** (`MERCENARY_AXE`, R) — Str +20, Dmg +70 — req: Combat 4 — recipe: -
- **Nansorb Arrow** (`NANSORB_ARROW`, R) — Dmg +5 — req: Combat 25, Cactus coll 7, Target_Practice — recipe: -
  - Replaces the damage reduction from the Piercing enchant to Skewer. // Passive: Skewer Hits through Piercing deal +7.5% damage than the previous one, up to 5 victims.
- **Polymorph Wand** (`POLYMORPH_WAND`, R) — - — req: - — recipe: -
- **Ragnarock** (`RAGNAROCK_AXE`, R) — Dmg +200, Str +100 — req: Combat 22 — recipe: craft: 20x HALLOWED_SKULL, 1x STICK
  - Ability: Ragnarock  RIGHT CLICK Begin a channel. After not taking damage for 3s, gain 1.5x this weapon's  Strength for 10s. Mana Cost: 500 Cooldown: 20s
- **Raider Sword** (`RAIDER_AXE`, R) — Dmg +80, Str +50 — req: - — recipe: -
  - Earn +20 coins from monster kills (Level 10+ only) // +1 Damage per 500 kills Max +35 // +1 Strength per 500 wood Sums wood collections, max +100 Wood collections: 0
- **Revenant Falchion** (`REVENANT_SWORD`, R) — Dmg +90, Str +50, Int +100 — req: Zombie Slayer 3, Cata 12 — recipe: craft: 1x CRYSTALLIZED_HEART, 1x UNDEAD_CATALYST, 1x UNDEAD_SWORD
  - Deals +150% damage to  Undead mobs.
- **Seismic Wave** (`SEISMIC_WAVE_STICK`, R) — Dmg +10 — req: - — recipe: -
- **Self-Recursive Pickaxe** (`SELF_RECURSIVE_PICKAXE`, R) — - — req: - — recipe: craft: 6x NULLIFIED_METAL, 24x WILTED_BERBERIS, 1x TACTICIAN_MURDER_WEAPON; craft: 6x NULLIFIED_METAL, 24x WILTED_BERBERIS, 1x LEECH_SWORD
  - Rift Damage: +5 // Allows you to break living metal ore and, potentially, living metal faces. // Regens 4 Mana on hit.
- **Silent Death** (`SILENT_DEATH`, R) — Dmg 85-180, Str 31-67, CD 9-20, CC 1-2 — req: Cata 20 — recipe: -
  - Ability: Shadowstep  RIGHT CLICK Teleport behind the enemy you are looking at, gaining +25 Strength for 10 seconds. Max range of 20 blocks. Cooldown resets on kills. Cooldown: 60s
- **Sniper Bow** (`SNIPER_BOW`, R) — Dmg 94-200, CD 1-10 — req: Cata 10 — recipe: -
  - Allows you to shoot arrows much further!
- **Soulstealer Bow** (`CRYPT_BOW`, R) — Str +50, Int 157-333 — req: Cata 5 — recipe: -
  - Replaces the arrows that you shoot with exploding wither skulls!
- **Staff of the Volcano** (`STAFF_OF_THE_VOLCANO`, R) — Dmg +60, AbilDmg(base) +24000, Int +150 — req: Combat 22 — recipe: -
  - Ability: Explode  RIGHT CLICK Creates an explosion around you dealing 24,000 damage and setting all mobs on fire in a 4 block radius over 1s. Mana Cost: 100 Cooldown: 30s
- **Starlight Wand** (`STARLIGHT_WAND`, R) — AbilDmg(base) +300 — req: Combat 8 — recipe: -
  - Ability: Starfall  RIGHT CLICK Shower stars in a 3 blocks area for 5s, dealing 300 damage per second to mobs within. Mana Cost: 100 Cooldown: 2s
- **Steak Stake** (`STEAK_STAKE`, R) — - — req: Vampire Slayer 2 — recipe: craft: 18x COVEN_SEAL, 3x WILTED_BERBERIS_BUNCH
  - Ability: A bit of Impalement  LEFT CLICK Stab a Vampire at 20%❤ to instantly vanquish it. (Does not actually hurt the vampire)
- **Stinger Bow** (`STINGER_BOW`, R) — Dmg 47-100 — req: Cata 12 — recipe: -
  - Ability: Sting While in Dungeons, shooting your teammates grants them 20 speed and 30 strength for 30 seconds but deals 1 damage. Mana Cost: 100
- **Super Cleaver** (`SUPER_CLEAVER`, R) — Dmg +105, Str +20, CD +20 — req: - — recipe: -
  - Ability: Cleave When hitting an entity, monsters in a 3 block range will be hit for a portion of that damage too.
- **Tactician's Murder Weapon** (`TACTICIAN_MURDER_WEAPON`, R) — - — req: - — recipe: -
  - Rift Damage: +4 // Regens 4 Mana on hit. // Gain +2 for every Ramero murdered with this sword. Rameros murdered: 1
- **Tactician's Sword** (`TACTICIAN_SWORD`, R) — Dmg +50, CC +20 — req: - — recipe: trade
  - Gains +15 Damage for each Combat collection at Tier VII or higher. // Your Collections: 0/11
- **Tarantula Fang** (`TARANTULA_FANG`, R) — Dmg +90, Str +45, CD +20 — req: Spider Slayer 4 — recipe: craft: 16x TARANTULA_SILK, 1x TARANTULA_CATALYST, 1x RECLUSE_FANG
  - Deal +200%  Damage to and gain +15☯ Combat Wisdom against  Arthropod mobs. // Ability: Webcutter This weapon can be used to clear webs left by the Tarantula Broodfather!
- **Test Rift Wand** (`TEST_WAND_WEAPON`, R) — - — req: - — recipe: -
- **The Alchemist's Staff** (`ALCHEMISTS_STAFF`, R) — Dmg +50, Str +20, AbilDmg(base) +10000, Int +20 — req: Combat 22 — recipe: -
  - Ability: Coin Conversion  RIGHT CLICK Spend coins to cast a projectile which deals 10,000 damage on hit and knocks enemies back. Coin Cost: 100,000 // * Soulbound *
- **Tribal Spear** (`TRIBAL_SPEAR`, R) — Str +100 — req: - — recipe: -
  - Ability: Thwack  RIGHT CLICK Throw the spear like an arrow which will return to you.
- **Twilight Dagger** (`MAWDUST_DAGGER`, R) — Dmg +90, Str +45, CD +15 — req: Blaze Slayer 2 — recipe: craft: 5x ENCHANTED_SULPHUR, 1x MOLTEN_POWDER, 1x STICK
  - Deal 1.5x damage to  Infernal mobs. Deal 1.2x damage to  Skeletal mobs. Gain +15☯ Combat Wisdom against  Infernal mobs. // Ability: Attunement  RIGHT CLICK Swap between Spirit and Crystal mode in order to attack through Hellion Shields. // Attuned: Spirit // Ability: Wraith Drain On hit, heal for 200 + 4%❤ after 6s if the Hellion Shield has switched attunement.
- **Undead Bow** (`UNDEAD_BOW`, R) — Dmg +80 — req: - — recipe: -
  - Deals +100% damage to  Undead mobs.
- **Voidedge Katana** (`VOIDEDGE_KATANA`, R) — Dmg +155, Str +60, CD +25, Int +50 — req: Enderman Slayer 3 — recipe: craft: 1x REFINED_MITHRIL, 1x NULL_OVOID, 1x VOIDWALKER_KATANA
  - Deal +200% damage to Endermen. Receive 6% less damage from Endermen when held. Gain +15☯ Combat Wisdom against Endermen. // Ability: Soulcry  RIGHT CLICK Gain +200 Ferocity against Endermen for 4s Soulflow Cost: 1 Mana Cost: 200 Cooldown: 4s
- **Wand of Farming** (`FARMING_WAND`, R) — - — req: - — recipe: -
  - Allows you to farm the peculiar crops from the rift while held.
- **Wand of Mending** (`WAND_OF_MENDING`, R) — - — req: Zombie Slayer 3 — recipe: craft: 1x REVENANT_VISCERA, 1x WAND_OF_HEALING, 12x ENCHANTED_DARK_OAK_LOG
  - Ability: Medium Heal  RIGHT CLICK Heal 80❤ per second for 5s. Wand heals don't stack. Cooldown: 1s
- **Zombie Soldier Cutlass** (`ZOMBIE_SOLDIER_CUTLASS`, R) — Dmg 52-100, AtkSpd +100, Str 26-50 — req: Cata 5 — recipe: -
  - Ability: Love Tap Heals you for +10 Health when you hit an entity while in Dungeons!
- **Zombie Sword** (`ZOMBIE_SWORD`, R) — Dmg +100, Str +50, Int +50 — req: Cata 18 — recipe: craft: 2x ZOMBIE_HEART, 1x STICK
  - Ability: Instant Heal  RIGHT CLICK Heal for 320❤ and heal players within 7 blocks for 64❤.
- **Adaptive Blade** (`STONE_BLADE`, E) — Dmg +170 — req: F2 done — recipe: -
  - As a weapon created by Scarf, it automatically adapts to its user inside Dungeons. // Berserk: +10 Damage, +75 Strength, +10 Speed Healer: +10 Mending & tethered players and players within 10 blocks are healed for 33% of your Life Steal, Drain, and Vampirism healing. Mage: +100 Intelligence, +10 Damage & melee attacks restore 25% additional mana. Tank: +100 Defense, +5 Vitality, +5 True Defense Archer: Your melee attacks cause enemies to take 10% more damage from your arrows for 5 seconds.
- **Aiming Bow** (`AIMING_BOW`, E) — Dmg +100 — req: - — recipe: -
- **Anti-Sentient Pickaxe** (`ANTI_SENTIENT_PICKAXE`, E) — - — req: - — recipe: -
  - Rift Damage: +5 // Allows you to break living metal ore and Youngite. // This pickaxe is not a particular fan of sentient pickaxes.
- **Arack** (`ARACK`, E) — Dmg +90 — req: - — recipe: -
  - Ability: Venom Mold Grants +100 Strength while poisoned.
- **Aspect of the Leech** (`ASPECT_OF_THE_LEECH_3`, E) — - — req: - — recipe: craft: 2x SUPER_LEECH_MODIFIER, 1x ASPECT_OF_THE_LEECH_2
  - Ability: Weird Transmission  RIGHT CLICK Teleport 5 blocks ahead of you. Mana Cost: 50
- **Aspect of the Void** (`ASPECT_OF_THE_VOID`, E) — Dmg +120, Str +100 — req: - — recipe: craft: 32x NULL_OVOID, 1x ASPECT_OF_THE_END
  - Ability: Instant Transmission  RIGHT CLICK Teleport 8 blocks ahead of you and gain +50  Speed for 3 seconds. Mana Cost: 45
- **Bingo Blaster** (`BINGO_BLASTER`, E) — AbilDmg% +20, AbilDmg(base) +625, Int +300 — req: - — recipe: -
  - Ability: Bingo Blast  RIGHT CLICK Shoots out a piercing ray of water that travels up to 15 blocks. Deals 865.2 damage to the first enemy hit. Damage is halved for each subsequent enemy hit. Mana Cost: 125 Cooldown: 1s // * Soulbound *
- **Bone Reaver** (`BONE_REAVER`, E) — Dmg +235, Str +90, Reach +0.5 — req: F4 done — recipe: craft: 20x ENCHANTED_BONE_BLOCK, 5x SPIRIT_BONE, 1x SPIRIT_SWORD
  - Ability: Reaving Strike  RIGHT CLICK Slash in a huge arc, dealing 125% melee damage to all enemies hit! // Deals +1% damage to Undead monsters for every 1% of your missing health.
- **Chrono Pickaxe** (`CHRONO_PICKAXE`, E) — - — req: - — recipe: craft: 64x TIMITE, 1x EON_PICKAXE
  - Allows you to break living metal, Obsolite, Timite, and Youngite. // This pickaxe is not a particular fan of the like, overall vibe.
- **Crypt Witherlord Sword** (`CRYPT_WITHERLORD_SWORD`, E) — Dmg 62-120, Str 50-60, AbilDmg(base) +300, Int 113-240 — req: Cata 18 — recipe: -
  - Ability: Witherlord  RIGHT CLICK Shoot 3 skulls each dealing 304.5 Damage. Mana Cost: 40 Cooldown: 3s
- **Death Bow** (`DEATH_BOW`, E) — Dmg +300 — req: F6 done — recipe: -
  - Deals +100% damage to  Undead mobs. // Your arrows have a 50% chance to bounce to another target after it hits something.
- **Dragon Shortbow** (`DRAGON_SHORTBOW`, E) — Dmg +120, Str +40, CD +50, CC +15 — req: Cata 8 — recipe: -
  - Dragon Tracer V Arrows home towards dragons if they are within 10 blocks. // Some of your enchantments require a higher Enchanting level! // Ability: Dragon Stalker Deal 15% bonus damage to Dragons. // Shortbow: Instantly shoots! // * Co-op Soulbound *
- **Earthen Blade** (`EARTH_SHARD`, E) — Def 73-96, Dmg 105-137 — req: Cata 19 — recipe: -
  - This shard automatically adapts to its user inside Dungeons, allowing you to perform your class ability. Reduces the cooldown of Seismic Wave by 2s.
- **Ember Rod** (`EMBER_ROD`, E) — Dmg +80, Str +35, AbilDmg(base) +30, Int +50 — req: Combat 8 — recipe: -
  - Ability: Fire Blast  RIGHT CLICK Shoot 3 Fireballs which deal 30 damage in rapid succession in front of you! Mana Cost: 150 Cooldown: 30s
- **Emerald Blade** (`EMERALD_BLADE`, E) — Dmg +130 — req: - — recipe: craft: 2x ENCHANTED_EMERALD_BLOCK, 1x STICK
  - A powerful blade made from pure Emeralds. This blade becomes stronger as you carry more coins in your purse (Capped at 2B coins) // Receive Curse of Greed when striking, CANCELLING any effect modifying your coins loss on death! // Current Damage Bonus: 0.0
- **End Stone Bow** (`END_STONE_BOW`, E) — Dmg +140 — req: Combat 15 — recipe: -
  - Ability: Extreme Focus  LEFT CLICK Consumes all your mana, and your next hit will deal that much more damage!
- **End Stone Sword** (`END_STONE_SWORD`, E) — Dmg +120, Str +80 — req: - — recipe: craft: 128x ENCHANTED_ENDSTONE, 1x STICK
  - Ability: Extreme Focus  RIGHT CLICK While in the The End, consume all of your remaining  Mana and gain the following buffs for every 100 Mana consumed: - Damage Reduction: 1% (Max 20%, last 5s) - Next Hit Damage: +25% (Max 500%) Mana Cost: 20
- **Enrager** (`ENRAGER`, E) — Dmg +270, Str +150 — req: Combat 25 — recipe: -
  - Ability: Enrage  RIGHT CLICK Taunt enemies in a 10 block radius and reduce their damage against you by 10% for 10s. Mana Cost: 500 Cooldown: 20s
- **Eon Pickaxe** (`EON_PICKAXE`, E) — - — req: - — recipe: craft: 32x YOUNGITE, 1x ANTI_SENTIENT_PICKAXE
  - Rift Damage: +5 // Allows you to break living metal ore, Timite, and Youngite. // This pickaxe is not a particular fan of time passing.
- **Explosive Bow** (`EXPLOSIVE_BOW`, E) — Dmg +100, Str +20 — req: - — recipe: craft: 3x ENCHANTED_FIREWORK_ROCKET, 3x STRING
  - Ability: Explosive Shot Creates an explosion on impact! Every Monster caught in this explosion takes the full damage of the weapon! Acts as Superboom TNT!
- **Fel Sword** (`FEL_SWORD`, E) — Dmg +190, Str +135 — req: Cata 20 — recipe: craft: 32x FEL_PEARL, 1x STICK
  - Every 100 Kills with this sword grants +1 Weapon Damage, up to 100 extra. Current Kills: 0 (+0 Damage) // Dungeon Healer Bonuses: - Increases healing from your Healing Circle by 10%. - Reduces the cooldown of your Wish Ultimate by 10 seconds.
- **Fire Freeze Staff** (`FIRE_FREEZE_STAFF`, E) — Dmg +50, Str +20 — req: Combat 22 — recipe: craft: 5x SPELL_POWDER, 20x WITHER_SOUL, 1x STICK
  - Ability: Fire Freeze  RIGHT CLICK Creates a circle with a radius of 5 blocks. After 5s, all mobs inside are frozen for 10s. Mana Cost: 500 Cooldown: 10s
- **Fire Fury Staff** (`FIRE_FURY_STAFF`, E) — Dmg +100, Int +300, AbilDmg(base) +42000 — req: Combat 22 — recipe: craft: 20x COMPACT_OOZE, 20x REKINDLED_EMBER_FRAGMENT, 1x STICK
  - Ability: Firestorm  RIGHT CLICK Shoots a projectile that on impact creates a firestorm, dealing up to 42,000 damage over 10 seconds to mobs in a 7 block radius. Mana Cost: 1,000 Cooldown: 20s
- **Fire Veil Wand** (`FIRE_VEIL_WAND`, E) — AbilDmg(base) +15000, Dmg +50, Int +200 — req: Combat 22 — recipe: craft: 10x LUMINO_FIBER, 10x SPELL_POWDER, 1x STICK
  - Ability: Fire Veil  RIGHT CLICK Creates a veil of fire around you for 5s, dealing 15,000 damage per second to mobs within. Mana Cost: 300 Cooldown: 1s
- **Ghoul Buster** (`GHOUL_BUSTER`, E) — Dmg +140, Str +90, CD +25 — req: - — recipe: craft: 32x ECTOPLASM, 8x SPOOKY_SHARD, 64x PURPLE_CANDY
  - Ability: Spooktacular Deal 100% more damage to  Spooky mobs during the Spooky Festival.
- **Glacial Scythe** (`GLACIAL_SCYTHE`, E) — Dmg +120, Int +30, AbilDmg(base) +1500 — req: Cata 10 — recipe: craft: 192x ENCHANTED_PACKED_ICE, 40x GLACIAL_FRAGMENT, 1x FROZEN_SCYTHE, 64x BLUE_ICE_HUNK
  - Ability: Ice Bolt  RIGHT CLICK Shoots 1 Ice Bolt that deals 1,500 damage and slows enemies hit for 5 seconds! When hitting the ground, also creates an explosion of 3 blocks, dealing the same damage. Mana Cost: 75
- **Gyrokinetic Wand** (`GYROKINETIC_WAND`, E) — - — req: Enderman Slayer 6 — recipe: craft: 96x NULL_OVOID, 30x ENCHANTED_EYE_OF_ENDER, 30x ENCHANTED_BLAZE_ROD
  - Ability: Gravity Storm  LEFT CLICK Create a large rift at the aimed location, pulling all mobs together. Regen mana 10x slower for 3s after cast. Soulflow Cost: 10 Mana Cost: 1,200 Cooldown: 30s // Ability: Cells Alignment  RIGHT CLICK Apply Aligned to yourself for 6s, plus 4 nearby players on grouped islands. (Catacombs, etc.) // ||| Aligned Splits incoming damage and applies it over 3s. Soulflow Cost: 2 Mana Cost: 220 Cooldown: 10s
- **Hellstorm Wand** (`HELLSTORM_STAFF`, E) — Dmg +120, Str +50, AbilDmg(base) +12000, Int +100 — req: Combat 25 — recipe: -
  - Ability: Hellstorm  RIGHT CLICK Gain 5 Ability Damage, but take double damage for 30s. Mana Cost: 100 Cooldown: 30s
- **Hurricane Bow** (`HURRICANE_BOW`, E) — Dmg +120, Str +50 — req: - — recipe: craft: 96x ENCHANTED_BONE, 96x STRING
  - Ability: Tempest The more kills you get using this bow the more powerful it becomes! Reach 250 kills to unlock its full potential. Next Upgrade: Double Shot (0/20)
- **Hyper Cleaver** (`HYPER_CLEAVER`, E) — Dmg +175, Str +100, CD +100 — req: F3 done — recipe: -
  - Ability: Cleave When hitting an entity, monsters in a 4 block range will be hit for a portion of that damage too.
- **Ink Wand** (`INK_WAND`, E) — Dmg +130, Str +90, AbilDmg(base) +10000 — req: - — recipe: craft: 64x ENCHANTED_INK_SACK, 1x STICK
  - Ability: Ink Bomb  RIGHT CLICK Shoot an ink bomb in front of you dealing 10,000 damage and giving blindness! Mana Cost: 60 Cooldown: 30s
- **Jerry-chine Gun** (`JERRY_STAFF`, E) — Dmg +80, AbilDmg(base) +500, Int +200 — req: - — recipe: -
  - Ability: Rapid-fire  RIGHT CLICK Shoots a Jerry bullet, dealing 500 damage on impact and knocking you back. // Each shot costs +30 mana more than the previous, resetting after 4s of not firing.
- **Juju Shortbow** (`JUJU_SHORTBOW`, E) — Dmg +310, Str +40, CD +110, CC +10 — req: Enderman Slayer 5, Cata 15 — recipe: craft: 32x ENCHANTED_EYE_OF_ENDER, 192x ENCHANTED_STRING, 32x NULL_OVOID, 32x ENCHANTED_QUARTZ_BLOCK
  - Hits 3 mobs on impact. Can damage endermen. // Shortbow: Instantly shoots!
- **Kindlebane Dagger** (`BURSTFIRE_DAGGER`, E) — Dmg +130, Str +55, CD +20, AtkSpd +10 — req: Blaze Slayer 4 — recipe: craft: 1x MAGMA_FISH_GOLD, 8x MOLTEN_POWDER, 1x FIREDUST_DAGGER
  - Deal 1.5x damage to  Infernal mobs. Deal 1.2x damage to  Wither mobs. Gain +30☯ Combat Wisdom against  Infernal mobs. // Ability: Attunement  RIGHT CLICK Swap between Ashen and Auric mode in order to attack through Hellion Shields. // Attuned: Ashen // Ability: Burning Vengeance ﬗ On hit, repeat the last 8 hits of dealt damage after 6s if the Hellion Shield has switched attunement.
- **Leaping Sword** (`LEAPING_SWORD`, E) — Dmg +150, Str +100, CD +25, AbilDmg(base) +350 — req: Cata 8 — recipe: craft: 24x ENCHANTED_FERMENTED_SPIDER_EYE, 1x STICK
  - Ability: Leap  RIGHT CLICK Leap into the air and deal 350 damage to nearby enemies upon landing on the ground. Damaged enemies will also be frozen for 1 second. Mana Cost: 50 Cooldown: 1s
- **Magma Arrow** (`MAGMA_ARROW`, E) — Dmg +40, CC +10 — req: Combat 25 — recipe: -
- **Magma Bow** (`MAGMA_BOW`, E) — Dmg +100, Str +100 — req: - — recipe: -
  - Ability: Arrow Infusion  ON SHOOT Consumes 1 Magma Cream from the Inventory or Quiver to double the damage per shot.
- **Mawdredge Dagger** (`BURSTMAW_DAGGER`, E) — Dmg +130, Str +55, CD +20, AtkSpd +10 — req: Blaze Slayer 4 — recipe: craft: 1x ENCHANTED_SULPHUR_CUBE, 8x MOLTEN_POWDER, 1x MAWDUST_DAGGER
  - Deal 2.5x damage to  Infernal mobs. Deal 1.5x damage to  Skeletal mobs. Gain +30☯ Combat Wisdom against  Infernal mobs. // Ability: Attunement  RIGHT CLICK Swap between Spirit and Crystal mode in order to attack through Hellion Shields. // Attuned: Spirit // Ability: Wraith Drain On hit, heal for 200 + 4%❤ after 6s if the Hellion Shield has switched attunement.
- **Ornate Zombie Sword** (`ORNATE_ZOMBIE_SWORD`, E) — Dmg +110, Str +60, Int +50 — req: Cata 18 — recipe: craft: 1x ENCHANTED_GOLD_BLOCK, 1x GOLDEN_POWDER, 1x ZOMBIE_SWORD
  - Ability: Instant Heal  RIGHT CLICK Heal for 420❤ and heal players within 7 blocks for 84❤.
- **Reaper Falchion** (`REAPER_SWORD`, E) — Dmg +120, Str +100, Int +200 — req: Zombie Slayer 6, Cata 18 — recipe: craft: 1x REVIVED_HEART, 1x REVENANT_CATALYST, 1x REVENANT_SWORD
  - Heal 10❤ per hit. Deal +200% damage to  Undead mobs. Gain +45☯ Combat Wisdom against  Undead mobs.
- **Scorpion Foil** (`SCORPION_FOIL`, E) — Dmg +120, Str +60, CD +30 — req: Spider Slayer 6 — recipe: craft: 32x TARANTULA_SILK, 8x TARANTULA_CATALYST, 1x TARANTULA_FANG
  - Deal +250%  Damage to and gain +30☯ Combat Wisdom against  Arthropod mobs. // Ability: Webcutter This weapon can be used to clear webs left by the Tarantula Broodfather!
- **Shaman Sword** (`SHAMAN_SWORD`, E) — Spd +5, Dmg +100, Str +20 — req: Wolf Slayer 3 — recipe: craft: 2x GOLDEN_TOOTH, 8x ENCHANTED_IRON, 4x ENCHANTED_BONE
  - Deal +1 Damage per 50 max ❤. Receive -20% damage from  Animal mobs. Gain +30☯ Combat Wisdom against  Animal mobs.
- **Silver-Laced Karambit** (`SILVER_LACED_KARAMBIT`, E) — - — req: Vampire Slayer 1 — recipe: craft: 12x SILVER_FANG, 1x SELF_RECURSIVE_PICKAXE, 12x COVEN_SEAL
  - Rift Damage: +5 // Regens 5 Mana and 0.5❤ on hit. // Gain +3 Rift Damage against Vampires.
- **Silver-Twist Karambit** (`SILVERTWIST_KARAMBIT`, E) — - — req: Vampire Slayer 4 — recipe: craft: 64x SILVER_FANG, 1x SILVER_LACED_KARAMBIT, 1x BLOODBADGE
  - Rift Damage: +6 // Regens 6 Mana and 0.5❤ on hit. // Gain +5 Rift Damage against Vampires.
- **Skeleton Lord Bow** (`SKELETON_LORD_BOW`, E) — Dmg 109-159, Str 18-44, CD 36-26 — req: Cata 30 — recipe: -
- **Slime Bow** (`SLIME_BOW`, E) — Dmg +100, CD +50 — req: - — recipe: craft: 15x ENCHANTED_SLIME_BLOCK, 3x ENCHANTED_STRING
  - Ability: Arrow Infusion  ON SHOOT Will consume Slimeballs in your Inventory or Quiver to deal 5x damage to Magma Cubes and Slimes.
- **Soul Whip** (`SOUL_WHIP`, E) — Dmg +145, Str +175 — req: Cata 23, Fishing 26 — recipe: craft: 12x ECTOPLASM, 8x SOUL_FRAGMENT
  - Deals 2x damage to Sea Creatures. // Ability: Flay  RIGHT CLICK Flay your whip in an arc, dealing your melee damage to all enemies in its path.
- **Souls Rebound** (`SOULS_REBOUND`, E) — Dmg +450 — req: Cata 8 — recipe: craft: 192x ENCHANTED_GHAST_TEAR, 192x SOUL_STRING, 10x DARK_QUEENS_SOUL_DROP
  - Your arrows mark enemies you hit for 5 seconds. Marked Enemies don't take damage from you, once the mark expires the target will receive a burst of damage equal to all the damage dealt during the mark increased by up to 10%. // This item is Arachnal !
- **Spider Queen's Stinger** (`SPIDER_QUEENS_STINGER`, E) — Dmg +360 — req: Cata 8 — recipe: craft: 12x ENCHANTED_DARK_OAK_LOG, 48x SOUL_STRING, 6x ENCHANTED_SPIDER_EYE
  - Arrows shot using this bow have an aura around them that deals 360 Damage to nearby enemies instead of dealing impact damage. Arrows travel through enemies. // This item is Arachnal !
- **Spider Shortbow** (`SCORPION_BOW`, E) — Dmg +50, Str +25, CC +10 — req: Spider Slayer 3, Cata 10 — recipe: craft: 3x SPIDER_CATALYST, 3x TARANTULA_WEB
  - Ability: Eggsecute Arrows fired from this weapon deal 2x damage to Egg Sacs spawned by the Tarantula Broodfather! // Shortbow: Instantly shoots!
- **Spirit Bow** (`BOSS_SPIRIT_BOW`, E) — Dmg +1 — req: - — recipe: -
  - An extremely fragile but powerful bow, capable of damaging Thorn, despite him being a spirit!
- **Spirit Sword** (`SPIRIT_SWORD`, E) — Dmg +210, Str +50 — req: F4 done — recipe: -
  - Deals +2% damage to  Undead Mobs for every 1% of your missing health. // Spirit Item: When turned into a ghost, this item becomes a Ghost Ability. // Spirit Ability: Spirit Bomb Shoots a spirit that deals 8000 damage on impact. Cooldown: 10s
- **Staff of the Rising Moon** (`STAFF_OF_THE_RISING_MOON`, E) — AbilDmg(base) +1 — req: - — recipe: -
  - Ability: Ray of Hope  RIGHT CLICK Launches an explosive moon towards the target location, dealing 1❤ Heart of damage to Wizardman when hit. Mana Cost: 1
- **Staff of the Rising Sun** (`HOPE_OF_THE_RESISTANCE`, E) — AbilDmg(base) +500 — req: - — recipe: -
  - Ability: Ray of Hope  RIGHT CLICK Channels your energy towards the target location, exploding on impact for 503.5 damage. the only item that can deal damage to Dante.
- **Sulphur Bow** (`SULPHUR_BOW`, E) — Dmg +200, Str +100 — req: Combat 22 — recipe: craft: 150x ENCHANTED_STRING, 100x ENCHANTED_SULPHUR, 50x BEZOS
  - Ability: Arrow Infusion  ON SHOOT Consumes 1 Sulphur from the Inventory or Quiver to double the damage per shot.
- **Super Sharp 'n Stabby Steak Stake** (`SHARP_STEAK_STAKE`, E) — - — req: Vampire Slayer 3 — recipe: craft: 8x CRUXMOTION, 1x STEAK_STAKE
  - Ability: A bit of Impalement  LEFT CLICK Stab a Vampire at 20%❤ to instantly vanquish it. (Does not actually hurt the vampire) // Ability: Bloodcrave After vanquishing a Vampire, your next hit will deal 2x  Damage and your next Vampire kill will award 1.5x Combat Exp.
- **Super Undead Bow** (`SUPER_UNDEAD_BOW`, E) — Dmg +220 — req: F3 done — recipe: -
  - Deals +100% damage to  Undead mobs. // Your arrows have a 25% chance to bounce to another target after it hits something.
- **Sword of Revelations** (`SWORD_OF_REVELATIONS`, E) — Dmg +180, Str +50 — req: - — recipe: craft: 12x GRIFFIN_FEATHER, 8x ENCHANTED_ANCIENT_CLAW, 1x ENCHANTED_GOLD_BLOCK, 1x HILT_OF_REVELATIONS
  - Deals 1.5x damage to  Mythological mobs and grants +5 Magic Find on them.
- **Venom's Touch** (`VENOMS_TOUCH`, E) — Dmg +250, Str +100 — req: Cata 8 — recipe: craft: 120x ENCHANTED_RED_MUSHROOM, 48x SOUL_STRING, 10x ENCHANTED_SPIDER_EYE; craft: 120x ENCHANTED_BROWN_MUSHROOM, 48x SOUL_STRING, 16x ENCHANTED_SPIDER_EYE
  - Fires a volley of 5 arrows. Arrows apply venom to all enemies hit dealing 0 Damage every second for 3 seconds. // This item is Arachnal !
- **Void Sword** (`VOID_SWORD`, E) — Dmg +125 — req: Cata 8 — recipe: -
  - Gain +20 Strength per piece of Ender Armor worn. // Current Bonus: 0 // * Co-op Soulbound *
- **Vorpal Katana** (`VORPAL_KATANA`, E) — Dmg +190, Str +80, CD +30, Int +200 — req: Enderman Slayer 5 — recipe: craft: 1x NULL_EDGE, 8x NULL_OVOID, 8x REFINED_MITHRIL, 1x VOIDEDGE_KATANA
  - Deal +250% damage to Endermen. Receive 9% less damage from Endermen when held. Gain +30☯ Combat Wisdom against Endermen. // Ability: Soulcry  RIGHT CLICK Gain +300 Ferocity against Endermen for 4s Soulflow Cost: 1 Mana Cost: 200 Cooldown: 4s
- **Wand of Recall** (`WAND_OF_RECALL`, E) — - — req: - — recipe: -
- **Wand of Restoration** (`WAND_OF_RESTORATION`, E) — - — req: Zombie Slayer 6 — recipe: craft: 16x REVENANT_VISCERA, 1x WAND_OF_MENDING, 64x ENCHANTED_DARK_OAK_LOG
  - Ability: Big Heal  RIGHT CLICK Heal 100❤ per second for 6s. Wand heals don't stack. Cooldown: 1s
- **Wand of Strength** (`WAND_OF_STRENGTH`, E) — Dmg +50, Str +20 — req: Combat 22 — recipe: craft: 20x SPELL_POWDER, 20x HALLOWED_SKULL, 1x STICK
  - Ability: Life Blood  RIGHT CLICK Use 10% of your max health to boost your nearby allies by +30 Strength for 10 seconds. Cooldown: 1s
- **Wither Cloak Sword** (`WITHER_CLOAK`, E) — Def +250, Dmg +190, Str +135 — req: F7 done — recipe: -
  - Ability: Creeper Veil  RIGHT CLICK Spawns a layered veil that negates damage for 10s, as follows: // ➤ Lose a layer on hit, consuming 30 Vitality and block up to 1,000 damage. ➤ The damage blocked scales with your Dungeon Stat Boost while in The Catacombs. ➤ When the damage exceeds a layer's limit, another layer is consumed, until all damage is accounted for. ➤ When you run out of  Vitality, consume all  Vitality and take slight knockback. ➤ You cannot attack or regenerate  Vitality while the veil is up. // RIGHT CLICK while active to deactivate. Cooldown is halved on deactivation. Cooldown: 10s
- **Wizard Wand** (`WIZARD_WAND`, E) — - — req: - — recipe: -
  - A private joke amongst Wizards, but you wouldn't understand. // Ability: Time Warp!  RIGHT CLICK Teleport through time (a work in progress). // Purchased by: [MVP+] Eisengolem Purchased for: 666,666,666  coins // Auction #1 Bid #1 July 2022
- **Zombie Commander Whip** (`ZOMBIE_COMMANDER_WHIP`, E) — Dmg +65, Str +5 — req: Cata 18 — recipe: -
  - Ability: Commander Whip Every 1 Zombies killed during a dungeon run by this weapon gives the wielder +1  Strength. Zombies Killed: 0 Bonus Strength: 0
- **Zombie Knight Sword** (`ZOMBIE_KNIGHT_SWORD`, E) — Dmg 82-130, Str 21-40 — req: Cata 14 — recipe: -
  - Full Set Bonus: Zombie Knight Gains +30 Strength when used with the Zombie Knight Armor
- **§4Sin§5seeker Scythe** (`SINSEEKER_SCYTHE`, E) — Dmg +100, Str +100 — req: Enderman Slayer 6, Cata 23 — recipe: craft: 16x NULL_ATOM, 32x NULL_OVOID, 1x SINFUL_DICE
  - Ability: Sinrecall Transmission  RIGHT CLICK Zap a line 4 blocks forward. // Recast within 1s (1.5x mana) or warp back to starting point. // Mobs crossing the line(s) receive a melee hit from what you're holding. Mana Cost: 260
- **Aspect of the Dragons** (`ASPECT_OF_THE_DRAGON`, L) — Dmg +225, Str +100, AbilDmg(base) +12000 — req: Combat 18, Cata 12 — recipe: -
  - Ability: Dragon Rage  RIGHT CLICK All Monsters in front of you take 12,000 damage. Hit monsters take large knockback. Mana Cost: 100
- **Astraea** (`ASTRAEA`, L) — Def +250, Dmg +270, Str +150, Fer +30, TrueDef +20, Int +50 — req: F7 done — recipe: craft: 8x GIANT_FRAGMENT_BOULDER, 1x NECRON_BLADE
  - Deals +50% damage to  Wither mobs. Grants +1  Damage and +2  Defense per Catacombs level.
- **Atomsplit Katana** (`ATOMSPLIT_KATANA`, L) — Dmg +305, Str +100, CD +50, Int +300 — req: Enderman Slayer 6 — recipe: craft: 2x NULL_BLADE, 1x VORPAL_KATANA
  - Deal +300% damage to Endermen. Receive 12% less damage from Endermen when held. Gain +45☯ Combat Wisdom against Endermen. // Ability: Soulcry  RIGHT CLICK Gain +400 Ferocity against Endermen for 4s Soulflow Cost: 2 Mana Cost: 200 Cooldown: 4s
- **Aurora Staff** (`RUNIC_STAFF`, L) — Dmg +20, AbilDmg(base) +10000, Int +250 — req: Combat 22, Kuudra None — recipe: -
  - Runic Item Use with Aurora Armor! Cast Runic Zap while matching the rune of your targets. Left-Click To Swap Rune // Ability: Runic Zap  RIGHT CLICK Fires a beam of energy, hitting the first enemy on its path for 10,000. Deals reduced damage past 10 blocks. Mana Cost: 10 Cooldown: 1s
- **Bonemerang** (`BONE_BOOMERANG`, L) — Dmg +270, Str +130 — req: F4 done — recipe: craft: 15x ENCHANTED_STRING, 10x ENCHANTED_BONE_BLOCK, 5x SPIRIT_BONE
  - Ability: Swing  RIGHT CLICK Throw the bone a short distance, dealing the damage an arrow would. // Deals double damage when coming back. Pierces up to 10 foes.
- **Bouquet of Lies** (`BOUQUET_OF_LIES`, L) — Dmg +220, Str +300, CD +50 — req: F6 done — recipe: craft: 12x FEL_ROSE, 1x FLOWER_OF_TRUTH
  - Ability: Petal Barrage  RIGHT CLICK Fire a barrage of 3 roses that ricochets between enemies, hitting up to 5 foes. For every 10% of your health missing, your roses deal 2% more damage. Cooldown: 1s
- **Cutlass** (`CUTLASS`, L) — Dmg +1, AtkSpd +100 — req: - — recipe: -
- **Daedalus Blade** (`DAEDALUS_AXE`, L) — - — req: - — recipe: craft: 48x ENCHANTED_GOLD_BLOCK, 2x DAEDALUS_STICK, 1x SWORD_OF_REVELATIONS
  - Gains +4 Damage per Taming level and copies the base Combat stats of your active pet. // Grants +1%  Damage and +0.2 Magic Find on  Mythological mobs per Bestiary tier you have across all of them. //  Mythological Bestiary Tiers: 0 +0%  Damage +0 Magic Find
- **Dark Claymore** (`DARK_CLAYMORE`, L) — Dmg +500, Str +100, CD +100, Reach +2 — req: F7 done — recipe: -
  - That thing was too big to be called a sword, it was more like a large hunk of stone. // Completion.
- **Deathripper Dagger** (`HEARTMAW_DAGGER`, L) — Dmg +160, Str +75, CD +25, CC +10, AtkSpd +20 — req: Blaze Slayer 6 — recipe: craft: 16x ENCHANTED_SULPHUR_CUBE, 64x MOLTEN_POWDER, 16x INFERNO_VERTEX, 1x BURSTMAW_DAGGER
  - Deal 3.5x damage to  Infernal mobs. Deal 2x damage to  Skeletal mobs. Gain +45☯ Combat Wisdom against  Infernal mobs. // Ability: Attunement  RIGHT CLICK Swap between Spirit and Crystal mode in order to attack through Hellion Shields. // Attuned: Spirit // Ability: Wraith Drain On hit, heal for 200 + 4%❤ after 6s if the Hellion Shield has switched attunement.
- **Felthorn Reaper** (`FELTHORN_REAPER`, L) — Dmg +275, CD +115, Reach +1 — req: F6 done — recipe: craft: 5x FEL_ROSE, 1x FEL_SKULL, 1x BONE_REAVER
  - Ability: Reaving Strike  RIGHT CLICK Slash in a huge arc, dealing 135% melee damage to all enemies hit! // Deals +1% damage for every 1% of missing health on the target.
- **Flaming Flay** (`FLAMING_FLAY`, L) — Dmg +200, Str +250, Fer +25 — req: Cata 23, Fishing 47 — recipe: craft: 1x BRAIDED_GRIFFIN_FEATHER, 1x SOUL_WHIP, 1x HOTSPOT_WATER_ORB, 1x BRIMSTONE_HANDLE, 1x LAVA_WATER_ORB
  - Deals 3x damage to Sea Creatures. // Ability: Flay  RIGHT CLICK Flay your whip in an arc, dealing your melee damage to all enemies in its path. Heal 1%  for each Sea Creature hit, up to 25 .
- **Florid Zombie Sword** (`FLORID_ZOMBIE_SWORD`, L) — Dmg +150, Str +80, Int +100 — req: Cata 18 — recipe: craft: 24x HEALING_TISSUE, 1x ORNATE_ZOMBIE_SWORD
  - Ability: Instant Heal  RIGHT CLICK Heal for 500❤ and heal players within 8 blocks for 100❤.
- **Flower of Truth** (`FLOWER_OF_TRUTH`, L) — Dmg +160, Str +300 — req: F6 done — recipe: craft: 9x GOLEM_POPPY
  - Ability: Heat-Seeking Rose  RIGHT CLICK Shoots a rose that ricochets between enemies, damaging up to 3 of your foes! Damage multiplies as more enemies are hit. Cooldown: 1s
- **Giant Cleaver** (`GIANT_CLEAVER`, L) — Dmg +235, Str +120, CD +120 — req: F6 done — recipe: -
  - Ability: Cleave When hitting an entity, monsters in a 5 block range will be hit for a portion of that damage too.
- **Giant's Eye Sword** (`GIANTS_EYE_SWORD`, L) — Dmg +500, AbilDmg(base) +100000, Reach +1 — req: F6 done — recipe: -
  - Ability: Eye Beam  RIGHT CLICK Fire a laser in front of you dealing 4000 damage and costing 40 mana. The damage increases by 100% every second for 5 seconds and the mana cost increases by 25% every second. You can right-click again to de-activate the laser. Damage of the laser is increased by x1.5 while wearing Precursor Eye. // Catacombs Floor VI Completion.
- **Giant's Sword** (`GIANTS_SWORD`, L) — Dmg +500, AbilDmg(base) +100000, Reach +1 — req: F6 done — recipe: -
  - Ability: Giant's Slam  RIGHT CLICK Slam your sword into the ground dealing 100,000 damage to nearby enemies. Mana Cost: 100 Cooldown: 30s
- **Great Spook Sword** (`GREAT_SPOOK_SWORD`, L) — Dmg 0 — req: - — recipe: -
  - Gains +1 Damage and +1 Strength for every Fear you have. // Obtained during the Alchemist experiments!
- **Halberd of the Shredded** (`AXE_OF_THE_SHREDDED`, L) — Dmg +140, Str +115 — req: Zombie Slayer 8, Cata 25 — recipe: craft: 256x REVENANT_VISCERA, 4x SHARD_OF_THE_SHREDDED, 1x REAPER_SWORD
  - Heal 25❤ per hit. Deal +250% damage to  Undead mobs. Gain +45☯ Combat Wisdom against  Undead mobs. // Ability: Throw  RIGHT CLICK Throw your Halberd, damaging all enemies in its path, dealing 10% melee damage. Consecutive throws cost 2x more mana and deal 2x more damage than the previous. (max 16x) Mana Cost: 20
- **Hollow Wand** (`HOLLOW_WAND`, L) — Dmg +150, Str +20, Int +120, AbilDmg(base) +15000 — req: Combat 22, Kuudra None — recipe: -
  - Ability: Hollow Spirit LEFT/RIGHT CLICK Left Click to cast a stack of [✤] and Right Click to cast a stack of [✦]. Combine stacks to cast different spells! // ⊳ [✤✤] Spirit Spark (10 ⚶) ⊳ [✦✦] Hollowed Rush (15 ⚶) ⊳ [✦✤] Raging Wind (20 ⚶) ⊳ [✤✦] Ichor Pool (30 ⚶) Type '/wandinfo' for detailed ability info.
- **Horseman's Scythe** (`HORSEMAN_SCYTHE`, L) — Dmg +100 — req: - — recipe: -
- **Hyperion** (`HYPERION`, L) — Dmg +260, Str +150, Fer +30, Int +350 — req: F7 done — recipe: craft: 8x GIANT_FRAGMENT_LASER, 1x NECRON_BLADE
  - Deals +50% damage to  Wither mobs. Grants +1  Damage and +2  Intelligence per Catacombs level.
- **Last Breath** (`LAST_BREATH`, L) — Dmg +200, Str +180, CD +50 — req: F5 done — recipe: -
  - Reduces the defense of your target by 10% of their max  Defense on hit, stacking up to 5 times.
- **Livid Dagger** (`LIVID_DAGGER`, L) — Dmg +210, Str +60, AtkSpd +50, CD +50, CC +100 — req: F5 done — recipe: -
  - Ability: Throw  RIGHT CLICK Throw your dagger at your enemies! Mana Cost: 150 Cooldown: 5s // Your Critical Hits deal 100% more damage if you are behind your target.
- **Midas Staff** (`MIDAS_STAFF`, L) — Dmg +130, Str +150, AbilDmg(base) +6000, Int +50 — req: Cata 25 — recipe: -
  - Ability: Molten Wave  RIGHT CLICK Cast a wave of molten gold in the direction you are facing! Deals up to 12,000 damage. Mana Cost: 500 Cooldown: 1s // Ability: Greed The ability damage bonus of this item is dependent on the price paid for it at the Dark Auction! The maximum bonus of this item is 26,000 if the bid was 100,000,000 Coins or higher!
- **Midas' Sword** (`MIDAS_SWORD`, L) — Dmg +170 — req: Combat 20, Cata 17 — recipe: -
  - Ability: Greed The strength and damage bonus of this item is dependent on the price paid for it at the Dark Auction! The maximum bonus of this item is 120 if the bid was 50,000,000 Coins or higher!
- **Mosquito Shortbow** (`MOSQUITO_BOW`, L) — Dmg +319, Str +151, CD +39, Vit +20 — req: Spider Slayer 7, Cata 13 — recipe: craft: 2x TARANTULA_CATALYST, 128x TARANTULA_SILK, 1x DIGESTED_MOSQUITO, 1x SCORPION_BOW
  - Ability: Eggsecute Arrows fired from this weapon deal 3x damage to Egg Sacs spawned by the Tarantula Broodfather! // Ability: Nasty Bite  LEFT CLICK Shoot an enhanced shot, healing you for 189❤ Health on hit. // Shortbow: Instantly shoots!
- **Necromancer Sword** (`NECROMANCER_SWORD`, L) — Def +125, Dmg +250, Str +125 — req: F6 done — recipe: -
  - Ability: Raise Souls  RIGHT CLICK Monsters you kill using this item will drop their soul. You can click on their souls on the ground using this item to absorb them and then spawn them to fight by your side. // Mana cost is based on the power of the monsters that you summon. Shift right-click to view and remove souls from this item. If your summoned monster dies the soul will be removed. Cooldown: 1s // Max Souls: 2
- **Necron's Blade (Unrefined)** (`NECRON_BLADE`, L) — Dmg +260, Str +110, Fer +30, Int +50 — req: F7 done — recipe: craft: 24x WITHER_CATALYST, 1x NECRON_HANDLE
  - Deals +50% damage to  Wither mobs.
- **Pigman Sword** (`PIGMAN_SWORD`, L) — Dmg +200, Str +100, CD +30, CC +5, AbilDmg(base) +30000, Int +300 — req: Cata 18 — recipe: craft: 48x ENCHANTED_GRILLED_PORK, 1x STICK
  - Ability: Burning Souls  RIGHT CLICK Gain +75 Defense for 5s and cast vortex of flames towards enemies, dealing up to 30,000 over 5 seconds. Mana Cost: 400 Cooldown: 5s
- **Pooch Sword** (`POOCH_SWORD`, L) — Spd +10, Dmg +160, Str +80, AtkSpd +5 — req: Wolf Slayer 6 — recipe: craft: 48x GOLDEN_TOOTH, 1x SHAMAN_SWORD
  - Deal +20 Damage per Wolf Slayer level Deal +200%  Damage against Wolves Take -20% damage from  Animal mobs Gain +45☯ Combat Wisdom against  Animal mobs // Ability: Pack Mentality Deal +100%  Damage against Wolves when wearing a full set of Armor of the Pack or Mastiff Armor.
- **Pyrochaos Dagger** (`HEARTFIRE_DAGGER`, L) — Dmg +160, Str +75, CD +25, CC +10, AtkSpd +20 — req: Blaze Slayer 6 — recipe: craft: 8x MAGMA_FISH_GOLD, 64x MOLTEN_POWDER, 16x INFERNO_VERTEX, 1x BURSTFIRE_DAGGER
  - Deal 2x damage to  Infernal mobs. Deal 1.5x damage to  Wither mobs. Gain +45☯ Combat Wisdom against  Infernal mobs. // Ability: Attunement  RIGHT CLICK Swap between Ashen and Auric mode in order to attack through Hellion Shields. // Attuned: Ashen // Ability: Burning Vengeance ﬗ On hit, repeat the last 8 hits of dealt damage after 6s if the Hellion Shield has switched attunement.
- **Reaper Scythe** (`REAPER_SCYTHE`, L) — Spd +10, Dmg +333 — req: Zombie Slayer 7, Cata 20 — recipe: craft: 1x SCYTHE_BLADE, 64x REVENANT_VISCERA, 3x REAPER_SWORD
  - Ability: Raise Souls  RIGHT CLICK Monsters you kill using this item will drop their soul. You can click on their souls on the ground using this item to absorb them and then spawn them to fight by your side. // Mana cost is based on the power of the monsters that you summon. Shift right-click to view and remove souls from this item. If your summoned monster dies the soul will be removed. Cooldown: 1s // Max Souls: 3
- **Runaan's Bow** (`RUNAANS_BOW`, L) — Dmg +160, Str +50 — req: Cata 8 — recipe: craft: 192x ENCHANTED_BONE, 192x ENCHANTED_STRING
  - Ability: Triple Shot Shoots 3 arrows at a time! The 2 extra arrows deal 40% of the damage and home to targets.
- **Scylla** (`SCYLLA`, L) — Dmg +270, Str +150, CD +35, CC +12, Fer +30, Int +50 — req: F7 done — recipe: craft: 8x GIANT_FRAGMENT_BIGFOOT, 1x NECRON_BLADE
  - Deals +50% damage to  Wither mobs. Grants +1  Damage and +1  Crit Damage per Catacombs level.
- **Shadow Fury** (`SHADOW_FURY`, L) — Spd +30, Dmg +300, Str +130, CD +30 — req: F5 done — recipe: -
  - Ability: Shadow Fury  RIGHT CLICK Rapidly teleports you to up to 5 enemies within 12 blocks, rooting each of them and allowing you to hit them. Cooldown: 15s
- **Silk-Edge Sword** (`SILK_EDGE_SWORD`, L) — Dmg +175, Str +125, CD +25, AbilDmg(base) +400 — req: Cata 13 — recipe: craft: 24x ENCHANTED_FERMENTED_SPIDER_EYE, 1x SILKY_LICHEN, 1x LEAPING_SWORD
  - Ability: Leap  RIGHT CLICK Leap into the air and deal 400 damage to nearby enemies upon landing on the ground. Damaged enemies will also be frozen for 1 second. Mana Cost: 50 Cooldown: 1s
- **Spirit Sceptre** (`BAT_WAND`, L) — Dmg +180, AbilDmg(base) +2000, Int +300 — req: F4 done — recipe: craft: 3x SPIRIT_WING, 6x ENCHANTED_LAPIS_LAZULI_BLOCK
  - Ability: Guided Bat  RIGHT CLICK Shoots a guided spirit bat, following your aim and exploding for 2,000 damage. Mana Cost: 200
- **Spirit Shortbow** (`ITEM_SPIRIT_BOW`, L) — Dmg +210, Str +100 — req: F4 done — recipe: -
  - Deals +2% damage to  Undead Mobs for every 1% of your missing health. // Spirit Item: When turned into a ghost, this item becomes a Ghost Ability. // Spirit Ability: Spirit Bomb Shoots a spirit that deals 8000 damage on impact. // Shortbow: Instantly shoots!
- **Staff of the Rising Sun** (`GENERALS_HOPE_OF_THE_RESISTANCE`, L) — AbilDmg(base) +1000 — req: - — recipe: -
  - Ability: Ray of Hope  RIGHT CLICK Channels your energy towards the target location, exploding on impact for 2,519.3 damage. Costs 2% of your total mana to use. This is the only item that can deal damage to Dante.
- **Sting** (`STING`, L) — Dmg +150, Str +75, CD +40 — req: Spider Slayer 8 — recipe: craft: 256x TARANTULA_SILK, 4x SHRIVELED_WASP, 1x SCORPION_FOIL
  - Deal +300%  Damage to and gain +45☯ Combat Wisdom against  Arthropod mobs! // Ability: Webcutter This weapon can be used to clear webs left by the Tarantula Broodfather! // Ability: Stinger Attacks from this sword will always critically strike!
- **Terminator** (`TERMINATOR`, L) — Dmg +310, Str +50, AtkSpd +40, CD +250 — req: Enderman Slayer 7, Cata 27 — recipe: craft: 8x TESSELLATED_ENDER_PEARL, 3x NULL_BLADE, 128x TARANTULA_SILK, 1x JUDGEMENT_CORE, 4x BRAIDED_GRIFFIN_FEATHER
  - Shoots 3 arrows at once. Can damage endermen. // Divides your  Crit Chance by 4! // Ability: Salvation  LEFT CLICK Shoot a beam, penetrating up to 5 enemies. The beam always crits. Soulflow Cost: 1⸎ // Shortbow: Instantly shoots!
- **Tormentor** (`TORMENTOR`, L) — Dmg +525, Str +30, CD +100 — req: Combat 42, Kuudra Infernal — recipe: -
  - Ability: Angered You take +50% damage while holding this weapon. // Ability: Chain of Agony  RIGHT CLICK Toggles On/Off on use Melee hits arc with cursed energy, chaining to up to 5 nearby enemies. Mana Cost: 200 Cooldown: 0.5s
- **Valkyrie** (`VALKYRIE`, L) — Dmg +270, Str +145, Fer +60, Int +60 — req: F7 done — recipe: craft: 8x GIANT_FRAGMENT_DIAMOND, 1x NECRON_BLADE
  - Deals +50% damage to  Wither mobs. Grants +1  Damage and +1  Strength per Catacombs level.
- **Wand of Atonement** (`WAND_OF_ATONEMENT`, L) — - — req: Zombie Slayer 7 — recipe: craft: 64x REVENANT_VISCERA, 1x WAND_OF_RESTORATION, 64x ENCHANTED_DARK_OAK_LOG
  - Ability: Huge Heal  RIGHT CLICK Heal 120❤ per second for 6s. Wand heals don't stack. Cooldown: 1s
- **Yeti Sword** (`YETI_SWORD`, L) — Dmg +150, Str +170, AbilDmg(base) +15000, Int +50 — req: Cata 22 — recipe: craft: 384x ENCHANTED_PACKED_ICE, 32x ENCHANTED_IRON_BLOCK, 1x HILT_OF_TRUE_ICE
  - Ability: Terrain Toss  RIGHT CLICK Throws a chunk of terrain in the direction you are facing! Deals up to 15,000 damage. Mana Cost: 250 Cooldown: 1s
- **Time Shuriken** (`TIME_KNIFE`, S) — - — req: - — recipe: -
  - upkeep your combo. // Why are you reading this? Go fight Sun Gecko! // * Soulbound *
- **Aspect of the Jerry** (`ASPECT_OF_THE_JERRY`, ?) — Dmg +1 — req: - — recipe: craft: 2x ASSISTANT, 1x STICK
  - Ability: Parley  RIGHT CLICK Channel your inner Jerry. Cooldown: 5s
- **Bow** (`BOW`, ?) — Dmg +30 — req: - — recipe: craft: 3x STICK, 3x STRING
- **Fancy Sword** (`FANCY_SWORD`, ?) — Dmg +20 — req: - — recipe: -
  - Ability: Bejeweled Blade Deals +150% damage to mobs on Mining Islands.
- **Flint Arrow** (`ARROW`, ?) — Dmg +1 — req: - — recipe: craft: 1x FLINT, 1x STICK, 1x FEATHER
- **Gold-tipped Arrow** (`GOLD_TIPPED_ARROW`, ?) — Dmg +10 — req: Combat 8, Target_Practice — recipe: -
- **Golden Sword** (`GOLD_SWORD`, ?) — Dmg +20 — req: - — recipe: craft: 2x GOLD_INGOT, 1x STICK
- **Iron Sword** (`IRON_SWORD`, ?) — Dmg +30 — req: - — recipe: craft: 2x IRON_INGOT, 1x STICK
- **Reinforced Iron Arrow** (`REINFORCED_IRON_ARROW`, ?) — Dmg +5 — req: Combat 4, Target_Practice — recipe: -
- **Rogue Sword** (`ROGUE_SWORD`, ?) — Dmg +20 — req: - — recipe: trade
  - Ability: Speed Boost  RIGHT CLICK Grants +100 Speed for 30s. Mana Cost: 41 Cooldown: 5s // * Co-op Soulbound *
- **Spider Sword** (`SPIDER_SWORD`, ?) — Dmg +30 — req: - — recipe: craft: 2x SPIDER_EYE, 1x STICK
  - Deals +100% damage to  Arthropod mobs. // This item is Arachnal !
- **Stone Sword** (`STONE_SWORD`, ?) — Dmg +25 — req: - — recipe: craft: 2x COBBLESTONE, 1x STICK
- **Undead Sword** (`UNDEAD_SWORD`, ?) — Dmg +30 — req: - — recipe: -
  - Deals +100% damage to  Undead mobs.
- **Wooden Sword** (`WOOD_SWORD`, ?) — Dmg +20 — req: - — recipe: craft: 2x WOOD, 1x STICK
- **woB** (`MIRRORED_BOW`, ?) — - — req: - — recipe: craft: 3x MIRRORED_STRING, 3x MIRRORED_STICK
## Appendix C — All accessories

| Item | ID | Tier | Stats | Effect | Req | Source/recipe |
|---|---|---|---|---|---|---|
| Accretion Talisman | ACCRETION_TALISMAN | C | - | Increases the speed of your Pocket Black Holes by 5%. | - | craft: 2x ENCHANTED_HELIX_LOG, 2x ENCHANTED_RUBY_VEILSHROOM |
| Agarimoo Talisman | AGARIMOO_TALISMAN | C | FishSpd +1 | - | - | craft: 9x AGARIMOO_TONGUE |
| Anita's Talisman | ANITA_TALISMAN | C | - | Grants a random +5 Farming Fortune stat during Jacob's Farming Contest. | - | - |
| Applicant's Statement | APPLICANT_STATEMENT | C | TrophyCh +1 | A single-page printout outlining why the candidate's application should be accepted. Why are these a thing? Just read the application itself... | Trophy Bronze | - |
| Archaeologist's Compass | ARCHAEOLOGIST_COMPASS | C | - | nearest Relic! / Relics Found: 28/28 | - | - |
| Big Brain Talisman | BIG_BRAIN_TALISMAN | C | Int +1 | - | - | - |
| Bingo Talisman | BINGO_TALISMAN | C | - | B.I.N.G.O. | - | - |
| Bioanalysis Talisman | BIOANALYSIS_TALISMAN | C | - | Increases the chance for crops to mutate by 5%. | - | - |
| Blood Donor Talisman | BLOOD_DONOR_TALISMAN | C | HPRegen +1 | Do your part bro. | - | craft: 108x HEMOVIBE |
| Book of Progression | BOOK_OF_PROGRESSION | C | - | This book, imbued with great power, upgrades as you do. / A wise novel once thought to be the cure to all problems. | - | - |
| Carnival Mask Bag | CARNIVAL_MASK_BAG | C | - | Holds Carnival Masks. Each unique Mask within this bag grants +1 Intelligence. / Current Bonus: +6 Intelligence | - | - |
| Copper Talisman | COPPER_TALISMAN | C | - | have a 4% higher chance to arrive. | - | - |
| Cropie Talisman | CROPIE_TALISMAN | C | WheatFortune +10, CarrotFortune +10, PotatoFortune +10 | - | - | craft: 256x CROPIE |
| Crux Talisman | CRUX_TALISMAN_1 | C | - | Defeat shies to gain +5 Rift Time and +5 Crux Fortune per kill milestone. Crux Fortune boosts the chance for bonus crux drops. / Kill Milestones - Shy: 0/10 kills | - | craft: 8x SHAME_CRUX |
| Farmer Orb | FARMER_ORB | C | - | Increases the regrowth rate of nearby crops on the public islands, regrowing an extra crop every 3 seconds. | - | craft: 96x PUMPKIN |
| Freshly Baked Talisman | FRESHLY_BAKED_TALISMAN | C | - | This item has x2  Overbloom during Feast events. | - | - |
| Future Calories Talisman | FUTURE_CALORIES | C | Tracking +0.5 | - | - | craft: 384x ENCHANTED_RAW_FISH, 48x MAGMA_FISH_SILVER, 1x PRE_DIGESTION_FISH |
| Glacial Talisman | GLACIAL_TALISMAN | C | - | Grants +5☯ Mining Wisdom on Jerry's Workshop. | - | - |
| Gratitude Talisman | GRATITUDE_TALISMAN | C | - | Visitors appear 1% faster while you're in The Garden. | - | craft: 5x VISITORS_GRATITUDE |
| Great Spook Talisman | GREAT_SPOOK_TALISMAN | C | Fear +2 | Obtained during the Alchemist experiments! | - | - |
| Honeycomb Talisman | HONEYCOMB_TALISMAN | C | - | Trees smeared with Honeycomb attract mobs 5% faster. | - | craft: 64x HONEYCOMB |
| IQ Point | IQ_POINT | C | - | Increases your total  Intelligence by 1%. | - | - |
| Junk Talisman | JUNK_TALISMAN | C | - | Grants +2.5 Treasure Chance while on the Backwater Bayou. / One man's trash is another man's treasure. | - | - |
| Lumberjack Talisman | LUMBERJACK_TALISMAN | C | - | Reduces the cooldown for throwing your Foraging Axe by 5%. | - | craft: 9x ENCHANTED_HELIX_LOG |
| Moonglade Talisman | MOONGLADE_TALISMAN | C | FigFortune +5, MangroveFortune +5 | - | - | - |
| Netherrack-Looking Sunshade | NETHERRACK_LOOKING_SUNSHADE | C | - | Makes you invisible to Dive Ghasts. | - | - |
| Nibble Chocolate Stick | NIBBLE_CHOCOLATE_STICK | C | - | Grants +2% chance to find a Chocolate Rabbit that you haven't found yet and grants +10 Chocolate per second. / A delightful treat from the Factory. Its crisp taste sparks joy with every bite. | - | - |
| Odger's Bronze Tooth | ODGERS_BRONZE_TOOTH | C | SCC +0.5, TrophyCh +1 | A perfectly fine tooth, besides some wear and tear... | Trophy Bronze | - |
| Organ Donor Talisman | ORGAN_DONOR_TALISMAN | C | ColdRes +0.5 | Sends out a Ding when within 20 blocks of an unlooted Frozen Corpse! / It's an opt-out system, after all. | - | - |
| Pandora's Box | PANDORAS_BOX | C | - | Complete challenges to earn stats and level up the Pandora's Box. / Challenges completed: 0/20 You receive +1  and +1  per 2 challenges you complete. / Note! You must have this item in your inventory or accessory bag when completing a challenge! | - | - |
| Pressure Talisman | PRESSURE_TALISMAN | C | PressRes +10 | - | - | craft: 8x ENCHANTED_SEA_LUMIES, 1x DEEP_ROOT |
| Respiration Talisman | RESPIRATION_TALISMAN | C | Resp +5 | You hold your breath as you enter the Rift, allowing you to delay loss of Rift Time by 10 seconds. | - | craft: 9x PUFF_CRUX |
| Runeblade Talisman | RUNEBLADE_TALISMAN | C | - | Your Axes deal 5% more  Damage to  Woodland mobs. | - | - |
| Safety Badge | SAFETY_BADGE | C | Def +3, Int +1 | The holder of this badge is a certified Safety Advocate! | - | - |
| Small Fish Bowl | SMALL_FISH_BOWL | C | - | Increase the odds of fishing a rare | - | craft: 4x ENCHANTED_FIG_LOG, 4x TENDER_WOOD, 1x ENCHANTED_RAW_FISH |
| Talisman of the Century | CENTURY_TALISMAN | C | - | Increases your chances of finding Cake Souls when eating Century Cakes by 1%. / What's better than a party? A party with free goodies! | - | - |
| Torrhus Talisman | TORRHUS_TALISMAN | C | HelixFortune +5 | - | - | - |
| Trapper Crest | TRAPPER_CREST | C | - | Earn x1.1 Pelts from tracker quests. / Collect 100 Pelts to upgrade this accessory to UNCOMMON. / Collected: 0 pelts | - | - |
| Voter's Badge | VOTER_BADGE | C | - | Grants +3 votes in Mayor Elections. / Complete 3 more unique Mayor Visitor offers to tier up! | - | - |
| White Gift Talisman | WHITE_GIFT_TALISMAN | C | - | Gain +5% more Coins and Skill XP from gifts. | - | - |
| Witch's Talisman | WITCH_TALISMAN | C | AlchWis +0.5 | Stinks up your entire accessory bag - somebody clearly didn't wash their hands after making a stew. | - | - |
| 2 IQ Points | TWO_IQ_POINT | U | - | Increases your total  Intelligence by 2%. | - | craft: 9x IQ_POINT |
| Accretion Ring | ACCRETION_RING | U | - | Increases the speed of your Pocket Black Holes by 10%. | - | craft: 16x ENCHANTED_HELIX_LOG, 16x ENCHANTED_RUBY_VEILSHROOM, 1x ACCRETION_TALISMAN |
| Agarimoo Ring | AGARIMOO_RING | U | FishWis +1, FishSpd +2 | - | - | craft: 96x AGARIMOO_TONGUE, 1x AGARIMOO_TALISMAN |
| Anita's Ring | ANITA_RING | U | - | Grants a random +15 Farming Fortune stat during Jacob's Farming Contest. | - | - |
| Bat Person Ring | BAT_PERSON_RING | U | - | Gain +5  Defense,  Strength, and  Intelligence for each bat you have summoned. / Works with Spirit Sceptre, Witch Mask, and the Bat Pet! | - | craft: 4x SPOOKY_SHARD, 32x ECTOPLASM, 1x BAT_PERSON_TALISMAN |
| Beastmaster Crest | BEASTMASTER_CREST_UNCOMMON | U | - | Pet Exp Boost: +0.2% / Gains stats based on how many  Mythological mobs and Minos followers you've slain. / Your kills: 0/25 | - | craft: 8x GRIFFIN_FEATHER, 80x ANCIENT_CLAW, 1x BEASTMASTER_CREST_COMMON |
| Bingo Ring | BINGO_RING | U | - | B.I.N.G.O. | - | - |
| Bioanalysis Ring | BIOANALYSIS_RING | U | - | Increases the chance for crops to mutate by 10%. | - | - |
| Blood Donor Ring | BLOOD_DONOR_RING | U | HPRegen +2 | There's like 5 liters in you. | - | craft: 96x HEMOGLASS, 1x BLOOD_DONOR_TALISMAN |
| Blood God Sigil | BLOOD_GOD_SIGIL | U | - | Tracks a counter for each mob killed while worn. / Gain +2 Strength for each digit on the counter. (Max 7) / Counter: None yet! | - | craft: 32x ENCHANTED_GRILLED_PORK, 64x ENCHANTED_BAKED_POTATO, 1x BLOOD_GOD_CREST |
| Broken Piggy Bank | BROKEN_PIGGY_BANK | U | - | It broke! | - | - |
| Campfire Adept Badge I | CAMPFIRE_TALISMAN_4 | U | - | Gain +5 Health Regen while burning. / Trial of Fire 5 "Fire guy!" | - | - |
| Campfire Adept Badge II | CAMPFIRE_TALISMAN_5 | U | - | Gain +6 Health Regen while burning. / Trial of Fire 6 "Fire guy!" | - | - |
| Campfire Adept Badge III | CAMPFIRE_TALISMAN_6 | U | - | Gain +7 Health Regen while burning. / Trial of Fire 7 "Fire guy!" | - | - |
| Campfire Adept Badge IV | CAMPFIRE_TALISMAN_7 | U | - | Gain +8 Health Regen while burning. / Trial of Fire 8 "Fire guy!" | - | - |
| Candy Talisman | CANDY_TALISMAN | U | - | Increases the drop chance of candies from mobs by 5% during the Spooky Festival. | - | - |
| Cat Talisman | CAT_TALISMAN | U | Spd +1 | - | - | trade |
| Chumming Talisman | CHUMMING_TALISMAN | U | FishSpd +1, FishWis +1 | Listen, it's for real chums of Moby, ok? | - | - |
| Combo Mania | COMBO_MANIA_TALISMAN | U | - | Ability: Combo Mania Combos in the Time Chamber take +0.5 seconds to expire. | - | - |
| Copper Ring | COPPER_RING | U | - | have a 8% higher chance to arrive. | - | - |
| Cracked Piggy Bank | CRACKED_PIGGY_BANK | U | - | Saves 75% of your coins on death. Only when in inventory or Accessory Bag. Very fragile! / Triggers when losing 20k+ coins. | - | - |
| Crux Ring | CRUX_TALISMAN_2 | U | - | Defeat crux mobs to gain +5 Rift Time and +5 Crux Fortune per kill milestone. Crux Fortune boosts the chance for bonus crux drops. / Kill Milestones - Shy: 0/10 kills | - | craft: 16x SHADOW_CRUX, 1x CRUX_TALISMAN_1 |
| Draconic Talisman | DRACONIC_TALISMAN | U | HP +1 | Increases damage dealt to Dragons by 2%. / Created from ripping The End of its fauna. | - | craft: 2x RITUAL_RESIDUE, 96x MITE_GEL, 32x ABSOLUTE_ENDER_PEARL |
| Dull Shark Tooth Necklace | DULL_SHARK_TOOTH_NECKLACE | U | Str +4 | Grants +10% chance to get Shark Teeth during Fishing Festivals. | - | craft: 4x NURSE_SHARK_TOOTH, 1x RAGGEDY_SHARK_TOOTH_NECKLACE |
| Dwarven Metal Talisman | DWARVEN_METAL | U | DwMetFort +15 | Dwarven Metal Fortune: +15 | HotM 8 | forge (24h): REFINED_TUNGSTEN:4.0, REFINED_UMBER:4.0, GLACITE_AMALGAMATION:4.0 |
| Emerald Ring | EMERALD_RING | U | - | Get +5 coins every minute! | - | craft: 32x ENCHANTED_EMERALD |
| Emperor's Talisman | EMPEROR_TALISMAN | U | - | - | - | craft: 4x DIVER_FRAGMENT |
| Farming Talisman | FARMING_TALISMAN | U | FarmFort +3 | - | - | craft: 80x ENCHANTED_WHEAT, 16x ENCHANTED_SEEDS |
| Feather Ring | FEATHER_RING | U | - | Increases how high you can fall before taking fall damage by 7 blocks. Fall damage is also reduced by 5%. | - | craft: 7x ENCHANTED_FEATHER, 1x FEATHER_TALISMAN |
| Freshly Baked Ring | FRESHLY_BAKED_RING | U | - | This item has x2  Overbloom during Feast events. | - | - |
| Glacial Ring | GLACIAL_RING | U | - | Grants +10☯ Mining Wisdom on Jerry's Workshop. | - | craft: 20x GLACIAL_FRAGMENT, 128x ENCHANTED_ICE, 1x GLACIAL_TALISMAN |
| Gratitude Ring | GRATITUDE_RING | U | - | Visitors appear 3% faster while you're in The Garden. | - | craft: 8x VISITORS_GRATITUDE, 1x GRATITUDE_TALISMAN |
| Gravity Talisman | GRAVITY_TALISMAN | U | - | Gives between +1 and +10  Strength and  Defense the closer you are to the spawn of an Island! | - | craft: 144x OBSIDIAN |
| Great Spook Ring | GREAT_SPOOK_RING | U | Fear +4 | Obtained during the Alchemist experiments! | - | - |
| Green Gift Talisman | GREEN_GIFT_TALISMAN | U | - | Gain +10% more Coins and Skill XP from gifts. | - | - |
| Green Jerry Talisman | JERRY_TALISMAN_GREEN | U | Int -1 | Wear to prove you love Jerry, or wear to prove you hate Jerry. | - | - |
| Harmonious Surgery Toolkit | HARMONIOUS_SURGERY_TOOLKIT | U | - | If you touch a larva which another player touched, you both gain +1 Larva Silk once it is harvested. | - | - |
| Healing Ring | HEALING_RING | U | Vit +4 | - | - | craft: 4x ENCHANTED_WATER_LILY, 1x HEALING_TALISMAN |
| Honeycomb Ring | HONEYCOMB_RING | U | - | Trees smeared with Honeycomb attract mobs 10% faster. | - | craft: 4x ENCHANTED_HONEYCOMB, 1x HONEYCOMB_TALISMAN |
| Hunter Talisman | HUNTER_TALISMAN | U | CombWis +2 | - | Wolf Slayer 7 | craft: 64x GOLDEN_TOOTH, 32x ENCHANTED_ROTTEN_FLESH |
| Intimidation Ring | INTIMIDATION_RING | U | - | Monsters at or below Level 5 will no longer target you. | - | - |
| Jake's Plushie | JAKE_PLUSHIE | U | HP +2 | A mystery was solved! / Increases the number of pelts obtained when hunting animals by +1. | - | - |
| Jungle Amulet | JUNGLE_AMULET | U | GemFort +10 | - | - | - |
| Junk Ring | JUNK_RING | U | - | Grants +5 Treasure Chance while on the Backwater Bayou. / One man's trash is another man's treasure. | - | - |
| Lava Talisman | LAVA_TALISMAN | U | - | Provides immunity against damage from most Lava. | - | craft: 9x ENCHANTED_MAGMA_CREAM |
| Luck Talisman | LUCK_TALISMAN | U | - | - | - | - |
| Lucky Hoof | LUCKY_HOOF | U | - | Gain 1% chance to find Trick or Treat Chests. | - | - |
| Lumberjack Ring | LUMBERJACK_RING | U | - | Reduces the cooldown for throwing your Foraging Axe by 10%. | - | craft: 64x TENDER_WOOD, 64x ENCHANTED_HELIX_LOG, 1x LUMBERJACK_TALISMAN |
| Lush Talisman | LUSH_TALISMAN | U | HP +1 | - | - | craft: 576x LUSH_BERBERIS |
| Magnetic Talisman | MAGNETIC_TALISMAN | U | - | Increase the item pick up range by 3x. | - | craft: 128x EMERALD, 32x REDSTONE |
| Master Skull - Tier 3 | MASTER_SKULL_TIER_3 | U | - | Grants +3%  Health and  Strength while in Master Mode Dungeons. | - | craft: 4x MASTER_SKULL_TIER_2 |
| Master Skull - Tier 4 | MASTER_SKULL_TIER_4 | U | - | Grants +4%  Health and  Strength while in Master Mode Dungeons. | - | craft: 4x MASTER_SKULL_TIER_3 |
| Mediocre Ring of Love | WEDDING_RING_2 | U | - | Grants a 1 in 10,000,000,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Medium Fish Bowl | MEDIUM_FISH_BOWL | U | - | Increase the odds of fishing a rare | - | craft: 4x FIGSTONE, 128x TENDER_WOOD, 1x SMALL_FISH_BOWL |
| Miniaturized Tubulator | MINIATURIZED_TUBULATOR | U | HP +1 | Don't hate the player, hate the game. - Ice T | - | - |
| Moonglade Ring | MOONGLADE_RING | U | FigFortune +10, MangroveFortune +10 | - | - | - |
| New Year Cake Bag | NEW_YEAR_CAKE_BAG | U | - | Holds New Year Cakes. Each unique New Year Cake within this bag grants +1 Health. / Current Bonus: +0 Health | - | - |
| Odger's Silver Tooth | ODGERS_SILVER_TOOTH | U | SCC +1, TrophyCh +2 | A perfectly fine tooth, besides a broken chunk... | Trophy Silver | - |
| Organ Donor Ring | ORGAN_DONOR_RING | U | ColdRes +1 | Sends out a Ding when within 20 blocks of an unlooted Frozen Corpse! / It's an opt-out system, after all. | - | - |
| Personal Compactor 4000 | PERSONAL_COMPACTOR_4000 | U | - | Automatically turns certain materials in your inventory into their enchanted form. / Does not grant Carpentry XP! | - | craft: 28x ENCHANTED_REDSTONE, 1x SUPER_COMPACTOR_3000 |
| Personal Deletor 4000 | PERSONAL_DELETOR_4000 | U | - | Automatically delete specific items on pickup. | - | craft: 112x ENCHANTED_IRON, 1x ENCHANTED_LAVA_BUCKET, 1x ENCHANTED_REDSTONE_BLOCK |
| Pesthunter Badge | PESTHUNTER_BADGE | U | PestCh +20 | your  Bonus Pest Chance stat. / Enabled: On | - | - |
| Piggy Bank | PIGGY_BANK | U | - | Saves your coins from death. Only when in inventory or Accessory Bag. Fragile! / Triggers when losing 20k+ coins. | - | craft: 8x ENCHANTED_PORK, 1x BROKEN_PIGGY_BANK; craft: 8x ENCHANTED_PORK, 1x CRACKED_PIGGY_BANK; craft: 40x ENCHANTED_PORK, 1x CHEST |
| Potato Ring | POTATO_RING | U | Spd +2 | Your potato minions farm 10% faster while wearing on your island. / Forged by the Potato King after winning the Great Potato War. | - | craft: 64x ENCHANTED_BAKED_POTATO, 1x POTATO_TALISMAN |
| Potion Affinity Ring | RING_POTION_AFFINITY | U | - | Increases the duration of any consumed Potion by 25%. | - | craft: 8x ENCHANTED_NETHER_STALK, 1x POTION_AFFINITY_TALISMAN |
| Pressure Ring | PRESSURE_RING | U | PressRes +20 | - | - | craft: 16x DEEP_ROOT, 16x ENCHANTED_SEA_LUMIES, 1x PRESSURE_TALISMAN |
| Pulse Ring | PULSE_RING | U | - | Grants an additional 1% charge when filling empty thunder bottles. / Charge up by combining a Thunder in a Bottle (Full) with this item in an anvil. / Charge: 0 Thunder / Reach 150k Charge for +1 Rarity! | - | craft: 256x ORB_OF_ENERGY |
| Red Claw Talisman | RED_CLAW_TALISMAN | U | CD +1 | - | Wolf Slayer 1 | craft: 8x WOLF_TOOTH, 1x ENCHANTED_BONE |
| Respiration Ring | RESPIRATION_RING | U | Resp +10 | You hold your breath as you enter the Rift, allowing you to delay loss of Rift Time by 20 seconds. | - | craft: 28x PUFF_CRUX, 1x RESPIRATION_TALISMAN |
| Ring of Coins | RING_OF_COINS | U | - | More coins start appearing around you occasionally on public islands. Lucky! | - | craft: 4x FRESHLY_MINTED_COINS, 1x COIN_TALISMAN |
| Ring of Power | POWER_RING | U | - | Allows you to harness the power of 4 Gemstones, although only half of the normal power can be harnessed! | - | craft: 7x FINE_RUBY_GEM, 1x GEMSTONE_MIXTURE, 1x POWER_TALISMAN |
| Ring of the Century | CENTURY_RING | U | - | Increases your chances of finding Cake Souls when eating Century Cakes by 2%. / What's better than a party? A party with free goodies and an accessory upgrade! | - | craft: 8x TOKEN_OF_THE_CENTURY, 1x CENTURY_TALISMAN |
| Rubbish Ring of Love | WEDDING_RING_3 | U | - | Grants a 1 in 1,000,000,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Runeblade Ring | RUNEBLADE_RING | U | - | Your Axes deal 10% more  Damage to  Woodland mobs. | - | craft: 8x RUNEBLADE_TALISMAN |
| Scavenger Ring | SCAVENGER_RING | U | - | Grants +0.6 coins per level of the killed enemy. | - | craft: 4x BLOOD_STAINED_COINS, 8x FRESHLY_MINTED_COINS, 1x SCAVENGER_TALISMAN |
| Sea Creature Ring | SEA_CREATURE_RING | U | SCC +0.2 | Take 10% less damage from Sea Creatures. | - | craft: 6x SPONGE, 2x ENCHANTED_SPONGE, 1x SEA_CREATURE_TALISMAN |
| Seal Talisman | SEAL_TALISMAN | U | SCC +0.5, Treasure +0.1 | - | - | - |
| Shady Ring | SHADY_RING | U | - | Get 1% off on most shops. | - | - |
| Smooth Chocolate Bar | SMOOTH_CHOCOLATE_BAR | U | - | Grants +4% chance to find a Chocolate Rabbit that you haven't found yet and grants +20 Chocolate per second. / Crafted in the Factory, its smoothness melts hearts and tastes like a sweet escape. | - | - |
| Soul Campfire Adept Badge I | SOUL_CAMPFIRE_TALISMAN_4 | U | - | Grants +5 Defense while burning. / Trial of Blue Flames 5 "Fire guy!" | - | - |
| Soul Campfire Adept Badge II | SOUL_CAMPFIRE_TALISMAN_5 | U | - | Grants +6 Defense while burning. / Trial of Blue Flames 6 "Fire guy!" | - | - |
| Soul Campfire Adept Badge III | SOUL_CAMPFIRE_TALISMAN_6 | U | - | Grants +7 Defense while burning. / Trial of Blue Flames 7 "Fire guy!" | - | - |
| Soul Campfire Adept Badge IV | SOUL_CAMPFIRE_TALISMAN_7 | U | - | Grants +8 Defense while burning. / Trial of Blue Flames 8 "Fire guy!" | - | - |
| Soulflow Pile | SOULFLOW_PILE | U | - | Internalized: 0⸎ Soulflow / Ability: Overflow Mana When leaving combat, convert up to 5⸎ Soulflow into 200 Overflow Mana. / Enabled: On / Soulflow from abilities is drained from your internalized ⸎. | Enderman Slayer 2 | craft: 90x NULL_SPHERE |
| Speed Ring | SPEED_RING | U | Spd +3 | - | - | craft: 96x ENCHANTED_SUGAR, 1x SPEED_TALISMAN |
| Spider Talisman | SPIDER_TALISMAN | U | - | Reduces the damage taken from  Arthropod mobs by 5%. | - | - |
| Squash Ring | SQUASH_RING | U | WheatFortune +20, CarrotFortune +20, PotatoFortune +20, MelonFortune +20, PumpkinFortune +20, CocoaBeansFortune +20 | Melon Slice Fortune: +20 Cocoa Beans Fortune: +20 | - | craft: 128x SQUASH, 1x CROPIE_TALISMAN |
| Student's Studies | STUDENT_STUDIES | U | TrophyCh +2 | A hastily-written infodump explaining the key differences between various frog species. It uses the wrong citation style and is thus deemed worthless, regardless of the information contained within. | Trophy Silver | - |
| Talisman of Space | TALISMAN_OF_SPACE | U | - | - | - | - |
| Test Bucket Please Ignore | TEST_BUCKET_PLEASE_IGNORE | U | TrueDef +0.5 | Was it all a test? | - | - |
| Tiny Dancer | TINY_DANCER | U | CD +1 | - | - | - |
| Titanium Talisman | TITANIUM_TALISMAN | U | MinSpd +15 | - | HotM 2 | forge (14h): REFINED_TITANIUM:2 |
| Torrhus Ring | TORRHUS_RING | U | HelixFortune +10 | - | - | - |
| VIP Voter's Badge | VOTER_BADGE_VIP | U | - | Grants +6 votes in Mayor Elections. / Complete 5 more unique Mayor Visitor offers to tier up! | - | - |
| Vaccine Ring | VACCINE_RING | U | - | Reduces the effect of poison by 25%. | - | craft: 8x ARACHNE_FANG, 1x VACCINE_TALISMAN |
| Witch's Ring | WITCH_RING | U | AlchWis +1 | Stinks up your entire accessory bag - somebody clearly didn't wash their hands after making a stew. | - | - |
| Wolf Paw | WOLF_PAW | U | Spd +1 | - | - | trade |
| Wolf Talisman | WOLF_TALISMAN | U | - | Reduces the damage taken from  Animal mobs by 5%. | - | - |
| Wood Affinity Talisman | WOOD_TALISMAN | U | - | Increases your  Speed by +10 while held in the Forest, Graveyard, Wilderness, The Park, Moonglade Marsh, and Torrhus Canyon. | - | craft: 8x LEAVES, 1x ENCHANTED_OAK_LOG |
| Zombie Ring | ZOMBIE_RING | U | - | Reduces the damage taken from  Undead mobs by 10%. | Zombie Slayer 2 | craft: 64x REVENANT_FLESH, 1x ZOMBIE_TALISMAN |
| Abicase | ABICASE | R | - | - | - | - |
| Accretion Artifact | ACCRETION_ARTIFACT | R | - | Increases the speed of your Pocket Black Holes by 15%. | - | craft: 16x HELIXIS, 2x VEILSHROOM_BUNCH, 1x ACCRETION_RING |
| Agarimoo Artifact | AGARIMOO_ARTIFACT | R | FishWis +1, FarmWis +1, FishSpd +3 | - | - | craft: 512x AGARIMOO_TONGUE, 1x AGARIMOO_RING |
| Anguish Talisman | ANGUISH_TALISMAN | R | MF +1, TrueDef +0.5 | Why are we still here? Just to suffer? | - | craft: 16x SORROW, 16x PLASMA |
| Anita's Artifact | ANITA_ARTIFACT | R | - | Grants a random +25 Farming Fortune stat during Jacob's Farming Contest. | - | - |
| Artifact of Coins | ARTIFACT_OF_COINS | R | - | Even more coins start appearing around you occasionally on public islands. Lucky! | - | craft: 16x FRESHLY_MINTED_COINS, 1x RING_OF_COINS |
| Artifact of Power | POWER_ARTIFACT | R | - | Allows you to harness the power of 7 Gemstones, although only half of the normal power can be harnessed! | - | craft: 32x GEMSTONE_MIXTURE, 1x POWER_RING |
| Artifact of the Century | CENTURY_ARTIFACT | R | - | Increases your chances of finding Cake Souls when eating Century Cakes by 3%. / What's better than a party? A party with free goodies and two accessory upgrades! | - | craft: 16x TOKEN_OF_THE_CENTURY, 1x CENTURY_RING |
| Atmospheric Filter | ATMOSPHERIC_FILTER | R | - | While on your Garden, grants a buff depending on the current SkyBlock season. ✧ Spring Grants +25 Farming Fortune. ✧ Summer Grants +20☯ Farming Wisdom. ✧ Autumn  Pests spawn 15% more often. Gain 5% more Copper from Garden Visitors. | - | - |
| Bait Ring | BAIT_RING | R | - | 5% chance not to consume bait. | - | craft: 288x ENCHANTED_INK_SACK |
| Bat Person Artifact | BAT_PERSON_ARTIFACT | R | - | Gain +10  Defense,  Strength, and  Intelligence for each bat you have summoned. / Works with Spirit Sceptre, Witch Mask, and the Bat Pet! | - | craft: 16x SPOOKY_SHARD, 128x ECTOPLASM, 1x BAT_PERSON_RING |
| Bat Talisman | BAT_TALISMAN | R | Spd +1, HP +1, Int +1 | - | - | - |
| Beastmaster Crest | BEASTMASTER_CREST_RARE | R | - | Pet Exp Boost: +0.3% / Gains stats based on how many  Mythological mobs and Minos followers you've slain. / Your kills: 0/25 | - | craft: 32x GRIFFIN_FEATHER, 256x ANCIENT_CLAW, 1x BEASTMASTER_CREST_UNCOMMON |
| Bingo Artifact | BINGO_ARTIFACT | R | - | B.I.N.G.O. | - | - |
| Bioanalysis Artifact | BIOANALYSIS_ARTIFACT | R | - | Increases the chance for crops to mutate by 15%. | - | - |
| Bits Talisman | BITS_TALISMAN | R | - | Whenever you gain bits, grants a 10% chance to gain double! | - | - |
| Blaze Talisman | BLAZE_TALISMAN | R | TrueDef +3 | Reduces the damage taken from  Infernal mobs by 5%. | - | craft: 8x MILLENIA_OLD_BLAZE_ASHES, 8x ENCHANTED_BLAZE_ROD |
| Blood Donor Artifact | BLOOD_DONOR_ARTIFACT | R | HPRegen +3 | The human body produces 2-3 million red blood cells per SECOND. | - | craft: 32x HEMOBOMB, 1x BLOOD_DONOR_RING |
| Blue Gift Talisman | BLUE_GIFT_TALISMAN | R | - | Gain +15% more Coins and Skill XP from gifts. | - | - |
| Blue Jerry Talisman | JERRY_TALISMAN_BLUE | R | Int -2 | Wear to prove you love Jerry, or wear to prove you hate Jerry. | - | craft: 5x JERRY_TALISMAN_GREEN |
| Bluetooth Ring | BLUETOOTH_RING | R | Int -1, Spd -2 | Deal +1 Damage if there are at least 6 players on the island you're on. / But wait! There's more... Adds +3 Damage bonus to the Blazetekk™ Ham Radio. | - | craft: 32x METAL_HEART |
| Bucket of Dye | BUCKET_OF_DYE | R | - | Increases your chance of dropping Dyes by 1%. / ✿ Chocolate Dye Donated | - | - |
| Burning Kuudra Core | BURNING_KUUDRA_CORE | R | - | Grants +1 Hunter Fortune. / A remnant of the core of Kuudra! | - | - |
| Burststopper Talisman | BURSTSTOPPER_TALISMAN | R | TrueDef +2, Str +1 | If an incoming hit would deal at least 50% of your ❤, multiply its damage by 0.95. | Blaze Slayer 3 | craft: 4x WHIPPED_MAGMA_CREAM, 128x MOLTEN_POWDER, 6x REFINED_TITANIUM |
| Campfire Cultist Badge I | CAMPFIRE_TALISMAN_8 | R | - | Gain +9 Health Regen while burning. / Trial of Fire 9 "Fire guy!" | - | - |
| Campfire Cultist Badge II | CAMPFIRE_TALISMAN_9 | R | - | Gain +10 Health Regen while burning. / Trial of Fire 10 "Fire guy!" | - | - |
| Campfire Cultist Badge III | CAMPFIRE_TALISMAN_10 | R | - | Gain +11 Health Regen while burning. / Trial of Fire 11 "Fire guy!" | - | - |
| Campfire Cultist Badge IV | CAMPFIRE_TALISMAN_11 | R | - | Gain +12 Health Regen while burning. / Trial of Fire 12 "Fire guy!" | - | - |
| Campfire Cultist Badge V | CAMPFIRE_TALISMAN_12 | R | - | Gain +13 Health Regen while burning. / Trial of Fire 13 "Fire guy!" | - | - |
| Candy Ring | CANDY_RING | R | - | Increases the drop chance of candies from mobs by 10% during the Spooky Festival. | - | - |
| Classy Ring of Love | WEDDING_RING_6 | R | - | Grants a 1 in 100,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Copper Artifact | COPPER_ARTIFACT | R | - | have a 12% higher chance to arrive. | - | - |
| Crooked Artifact | CROOKED_ARTIFACT | R | - | Get 2% off on most shops. | - | trade |
| Crux Artifact | CRUX_TALISMAN_3 | R | - | Defeat crux mobs to gain +5 Rift Time and +5 Crux Fortune per kill milestone. Crux Fortune boosts the chance for bonus crux drops. / Kill Milestones - Shy: 0/10 kills | - | craft: 16x VOLT_CRUX, 1x CRUX_TALISMAN_2 |
| Day Crystal | DAY_CRYSTAL | R | - | Increases your  Strength and  Defense by +5 during the Day. | - | craft: 4x ENCHANTED_QUARTZ, 1x ENCHANTED_QUARTZ_BLOCK |
| Devour Ring | DEVOUR_RING | R | - | Heal 10❤ when killing  Undead mobs. Cooldown: 0.5s | Zombie Slayer 5 | craft: 2x ENCHANTED_RAW_CHICKEN, 8x ENCHANTED_RAW_SALMON, 39x REVENANT_VISCERA |
| Draconic Ring | DRACONIC_RING | R | HP +2 | Increases damage dealt to Dragons by 3%. / Created from ripping The End of its fauna. | - | craft: 4x RITUAL_RESIDUE, 96x MITE_GEL, 1x DRACONIC_TALISMAN, 1x TESSELLATED_ENDER_PEARL |
| Elite Voter's Badge | VOTER_BADGE_ELITE | R | - | Grants +9 votes in Mayor Elections. / Complete 8 more unique Mayor Visitor offers to tier up! | - | - |
| Emerald Artifact | EMERALD_ARTIFACT | R | - | Get +20 coins every minute! | - | craft: 36x ENCHANTED_EMERALD_BLOCK, 1x EMERALD_RING, 1x AVARICIOUS_CHALICE |
| Emperor's Ring | EMPEROR_RING | R | - | - | - | craft: 4x EMPEROR_TALISMAN |
| Eternal Hoof | ETERNAL_HOOF | R | - | Gain 2% chance to find Trick or Treat Chests. | - | craft: 8x SOUL_FRAGMENT, 1x LUCKY_HOOF |
| Feather Artifact | FEATHER_ARTIFACT | R | - | Increases how high you can fall before taking fall damage by 10 blocks. Fall damage is also reduced by 15%. | - | craft: 128x ENCHANTED_FEATHER, 1x FEATHER_RING |
| Fermento Artifact | FERMENTO_ARTIFACT | R | WheatFortune +30, CarrotFortune +30, PotatoFortune +30, MelonFortune +30, PumpkinFortune +30, CocoaBeansFortune +30, SugarCaneFortune +30, CactusFortune +30, MushroomFortune +30, NetherStalkFortune +30 | Sugar Cane Fortune: +30 Melon Slice Fortune: +30 Cocoa Beans Fortune: +30 Nether Wart Fortune: +30 | - | craft: 8x CONDENSED_FERMENTO, 1x SQUASH_RING |
| Fish Affinity Talisman | FISH_AFFINITY_TALISMAN | R | FishSpd +10 | - | - | - |
| Freshly Baked Artifact | FRESHLY_BAKED_ARTIFACT | R | - | This item has x2  Overbloom during Feast events. | - | - |
| Frozen Chicken | FROZEN_CHICKEN | R | Spd +1 | - | - | trade |
| General's Medallion | GENERAL_MEDALLION | R | - | Increases the base stats of your dungeon items by 1% while in The Catacombs for each digit on your secret count. (Max 5%) / Counter: 0 Bonus: +0% / Once worn proudly, now covered in filth and soot. | - | - |
| Glacial Artifact | GLACIAL_ARTIFACT | R | - | Grants +15☯ Mining Wisdom on Jerry's Workshop. | - | craft: 40x GLACIAL_FRAGMENT, 8x ENCHANTED_PACKED_ICE, 1x GLACIAL_RING |
| Gratitude Artifact | GRATITUDE_ARTIFACT | R | - | Visitors appear 5% faster while you're in The Garden. | - | craft: 16x VISITORS_GRATITUDE, 1x GRATITUDE_RING |
| Great Spook Artifact | GREAT_SPOOK_ARTIFACT | R | Fear +6 | Obtained during the Alchemist experiments! | - | - |
| Grizzly Paw | GRIZZLY_PAW | R | Spd +1 | - | - | - |
| Haste Ring | HASTE_RING | R | MinSpd +10 | Grants permanent Haste II. | - | craft: 128x ENCHANTED_COBBLESTONE |
| Honed Shark Tooth Necklace | HONED_SHARK_TOOTH_NECKLACE | R | Str +6 | Grants +15% chance to get Shark Teeth during Fishing Festivals. | - | craft: 4x BLUE_SHARK_TOOTH, 1x DULL_SHARK_TOOTH_NECKLACE |
| Honeycomb Artifact | HONEYCOMB_ARTIFACT | R | - | Trees smeared with Honeycomb attract mobs 15% faster. | - | craft: 32x ENCHANTED_HONEYCOMB, 1x HONEYCOMB_RING |
| Hunter Ring | HUNTER_RING | R | CombWis +5 | - | Wolf Slayer 7 | craft: 224x GOLDEN_TOOTH, 1x GRIZZLY_BAIT, 1x HUNTER_TALISMAN |
| Intimidation Artifact | INTIMIDATION_ARTIFACT | R | - | Monsters at or below Level 25 will no longer target you. | - | - |
| Junk Artifact | JUNK_ARTIFACT | R | - | Grants +7.5 Treasure Chance while on the Backwater Bayou. / One man's trash is another man's treasure. | - | - |
| Kuudra's Kidney | KUUDRAS_KIDNEY | R | - | You pick up crates 10% faster. You build the ballista 5% faster. | - | craft: 9x KUUDRA_TENTACLE |
| Large Fish Bowl | LARGE_FISH_BOWL | R | - | Doubles the odds of fishing a rare | - | craft: 16x FIGSTONE, 4x ENCHANTED_TENDER_WOOD, 1x MEDIUM_FISH_BOWL |
| Lumberjack Artifact | LUMBERJACK_ARTIFACT | R | - | Reduces the cooldown for throwing your Foraging Axe by 15%. | - | craft: 128x VINESAP, 4x HELIXIS, 1x LUMBERJACK_RING |
| Lush Ring | LUSH_RING | R | HP +3 | - | - | craft: 64x ENCHANTED_LUSH_BERBERIS, 1x LUSH_TALISMAN |
| Lynx Talisman | LYNX_TALISMAN | R | Spd +2 | - | - | trade |
| Master Skull - Tier 5 | MASTER_SKULL_TIER_5 | R | - | Grants +6%  Health and  Strength while in Master Mode Dungeons. | - | craft: 4x MASTER_SKULL_TIER_4 |
| Master's Thesis | MASTER_THESIS | R | TrophyCh +3 | A well-written paper, complete with labeled diagrams. It explores frog biodiversity and details key efforts made in the name of conservation. | Trophy Gold | - |
| Mineral Talisman | MINERAL_TALISMAN | R | MinFort +3 | - | - | craft: 9x REFINED_MINERAL |
| Modest Ring of Love | WEDDING_RING_4 | R | - | Grants a 1 in 100,000,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Moonglade Artifact | MOONGLADE_ARTIFACT | R | FigFortune +15, MangroveFortune +15 | - | - | - |
| Night Crystal | NIGHT_CRYSTAL | R | - | Increases your  Strength and  Defense by +5 during the Night. | - | craft: 4x ENCHANTED_QUARTZ, 1x ENCHANTED_QUARTZ_BLOCK |
| Odger's Gold Tooth | ODGERS_GOLD_TOOTH | R | SCC +1.5, TrophyCh +3 | A perfectly fine tooth, besides its weight... | Trophy Gold | - |
| Old Boot | OLD_BOOT | R | - | - | - | - |
| Organ Donor Artifact | ORGAN_DONOR_ARTIFACT | R | ColdRes +1.5 | Sends out a Ding when within 20 blocks of an unlooted Frozen Corpse! / It's an opt-out system, after all. | - | - |
| Personal Compactor 5000 | PERSONAL_COMPACTOR_5000 | R | - | Automatically turns certain materials in your inventory into their enchanted form. / Does not grant Carpentry XP! | - | craft: 84x ENCHANTED_REDSTONE, 1x PERSONAL_COMPACTOR_4000 |
| Personal Deletor 5000 | PERSONAL_DELETOR_5000 | R | - | Automatically delete specific items on pickup. | - | craft: 224x ENCHANTED_IRON, 1x PERSONAL_DELETOR_4000 |
| Pesthunter Ring | PESTHUNTER_RING | R | PestCh +40 | your  Bonus Pest Chance stat. / Enabled: On | - | - |
| Pig's Foot | PIGS_FOOT | R | Spd +1 | Like a Rabbit's Foot, but more useless. | - | trade |
| Potion Affinity Artifact | ARTIFACT_POTION_AFFINITY | R | - | Increases the duration of any consumed Potion by 50%. | - | craft: 256x ENCHANTED_NETHER_STALK, 1x RING_POTION_AFFINITY |
| Pressure Artifact | PRESSURE_ARTIFACT | R | PressRes +30 | - | - | craft: 64x DEEP_ROOT, 32x ENCHANTED_SEA_LUMIES, 1x PRESSURE_RING |
| Red Claw Ring | RED_CLAW_RING | R | CD +3 | - | Wolf Slayer 5 | craft: 96x ENCHANTED_LEATHER, 8x GOLDEN_TOOTH, 1x RED_CLAW_TALISMAN |
| Refined Ring of Love | WEDDING_RING_5 | R | - | Grants a 1 in 1,000,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Respiration Artifact | RESPIRATION_ARTIFACT | R | Resp +15 | You hold your breath as you enter the Rift, allowing you to delay loss of Rift Time by 30 seconds. | - | craft: 128x PUFF_CRUX, 1x RESPIRATION_RING |
| Rich Chocolate Chunk | RICH_CHOCOLATE_CHUNK | R | - | Grants +6% chance to find a Chocolate Rabbit that you haven't found yet and grants +30 Chocolate per second. / From the Factory's secret reserves, its rich flavor is a deep dive into indulgence. | - | - |
| Rift Prism | RIFT_PRISM | R | - | While in your accessory bag, you may join the Rift at no cost. / Talk with Erihann in the Wizard Tower of the Hub to consume this accessory. | - | - |
| Ring of Space | RING_OF_SPACE | R | - | - | - | - |
| Runeblade Artifact | RUNEBLADE_ARTIFACT | R | - | Your Axes deal 15% more  Damage to  Woodland mobs. | - | craft: 9x RUNEBLADE_RING |
| Scarf's Studies | SCARF_STUDIES | R | - | Gain 2% more Dungeons class experience. / The collection of his studies, beautiful handwriting! | - | - |
| Scavenger Artifact | SCAVENGER_ARTIFACT | R | - | Grants +0.75 coins per level of the killed enemy. | - | craft: 4x BLOOD_SOAKED_COINS, 16x FRESHLY_MINTED_COINS, 1x SCAVENGER_RING |
| Sea Creature Artifact | SEA_CREATURE_ARTIFACT | R | SCC +0.3 | Take 15% less damage from Sea Creatures. | - | craft: 64x ENCHANTED_SPONGE, 1x SEA_CREATURE_RING |
| Seal Ring | SEAL_RING | R | SCC +1, Treasure +0.2 | - | - | - |
| Soul Campfire Cultist Badge I | SOUL_CAMPFIRE_TALISMAN_8 | R | - | Grants +18❈ Defense while burning. / Trial of Blue Flames 9 "Fire guy!" | - | - |
| Soul Campfire Cultist Badge II | SOUL_CAMPFIRE_TALISMAN_9 | R | - | Grants +10 Defense while burning. / Trial of Blue Flames 10 "Fire guy!" | - | - |
| Soul Campfire Cultist Badge III | SOUL_CAMPFIRE_TALISMAN_10 | R | - | Grants +11 Defense while burning. / Trial of Blue Flames 11 "Fire guy!" | - | - |
| Soul Campfire Cultist Badge IV | SOUL_CAMPFIRE_TALISMAN_11 | R | - | Grants +12 Defense while burning. / Trial of Blue Flames 12 "Fire guy!" | - | - |
| Soul Campfire Cultist Badge V | SOUL_CAMPFIRE_TALISMAN_12 | R | - | Grants +13 Defense while burning. / Trial of Blue Flames 13 "Fire guy!" | - | - |
| Soulflow Battery | SOULFLOW_BATTERY | R | - | Internalized: 0⸎ Soulflow / Ability: Overflow Mana When leaving combat, convert up to 4⸎ Soulflow into 400 Overflow Mana. / Enabled: On / Soulflow from abilities is drained from your internalized ⸎. | Enderman Slayer 5 | craft: 8x NULL_OVOID, 1x SOULFLOW_PILE |
| Speed Artifact | SPEED_ARTIFACT | R | Spd +5 | - | - | craft: 48x ENCHANTED_SUGAR_CANE, 1x SPEED_RING |
| Spider Ring | SPIDER_RING | R | - | Reduces the damage taken from  Arthropod mobs by 10%. | Spider Slayer 1 | craft: 64x TARANTULA_WEB, 1x SPIDER_TALISMAN |
| Survivor Cube | SURVIVOR_CUBE | R | Vit +3 | - | Spider Slayer 7 | - |
| Titanium Ring | TITANIUM_RING | R | MinSpd +30 | - | HotM 3 | forge (20h): TITANIUM_TALISMAN:1, REFINED_TITANIUM:6 |
| Torrhus Artifact | TORRHUS_ARTIFACT | R | HelixFortune +15 | - | - | - |
| Treasure Talisman | TREASURE_TALISMAN | R | - | Grants +1% extra loot to end of dungeon chests. | - | - |
| Vaccine Artifact | VACCINE_ARTIFACT | R | - | Reduces the effect of poison by 50%. | - | craft: 8x POISON_SAMPLE, 1x VACCINE_RING |
| Witch's Artifact | WITCH_ARTIFACT | R | AlchWis +1.5 | Stinks up your entire accessory bag - somebody clearly didn't wash their hands after making a stew. | - | - |
| Wolf Ring | WOLF_RING | R | - | Reduces the damage taken from  Animal mobs by 10%. | - | craft: 14x ENCHANTED_BONE, 1x WOLF_TALISMAN, 1x WEAK_WOLF_CATALYST |
| Zombie Artifact | ZOMBIE_ARTIFACT | R | - | Reduces the damage taken from  Undead mobs by 15%. | Zombie Slayer 7 | craft: 32x ENCHANTED_IRON, 16x ENCHANTED_DIAMOND, 48x REVENANT_VISCERA, 1x ZOMBIE_RING |
| Anguish Ring | ANGUISH_RING | E | MF +2, TrueDef +1 | Why are we still here? Just to suffer? | - | craft: 32x SORROW, 32x PLASMA, 1x ANGUISH_TALISMAN |
| Argofay Trinket | ARGOFAY_TRINKET | E | - | - | - | - |
| Artifact of Space | ARTIFACT_OF_SPACE | E | - | - | - | - |
| Bat Ring | BAT_RING | E | Spd +2, HP +3, Int +2 | - | - | - |
| Beastmaster Crest | BEASTMASTER_CREST_EPIC | E | - | Pet Exp Boost: +0.4% / Gains stats based on how many  Mythological mobs and Minos followers you've slain. / Your kills: 0/25 | - | craft: 128x GRIFFIN_FEATHER, 4x ENCHANTED_ANCIENT_CLAW, 1x BEASTMASTER_CREST_RARE |
| Bingo Relic | BINGO_RELIC | E | - | B.I.N.G.O. | - | - |
| Bluertooth Ring | BLUERTOOTH_RING | E | Int -2, Spd -3 | Deal +2 Damage if there are at least 6 players on the island you're on. / But wait! There's more... Adds +4 Damage bonus to the Blazetekk™ Ham Radio. / Though it may not be bluer visually, it is in spirit. | - | craft: 128x BLUE_RING, 2x GOBLIN_EGG_BLUE, 16x BLUE_SHARK_TOOTH, 1x BLUETOOTH_RING |
| Burststopper Artifact | BURSTSTOPPER_ARTIFACT | E | TrueDef +3, Str +2 | If an incoming hit would deal at least 50% of your ❤, multiply its damage by 0.9. | Blaze Slayer 7 | craft: 128x MOLTEN_POWDER, 80x WHIPPED_MAGMA_CREAM, 1x BURSTSTOPPER_TALISMAN |
| Campfire Scion Badge I | CAMPFIRE_TALISMAN_13 | E | - | Gain +14 Health Regen while burning. / Trial of Fire 14 "Fire guy!" | - | - |
| Campfire Scion Badge II | CAMPFIRE_TALISMAN_14 | E | - | Gain +15 Health Regen while burning. / Trial of Fire 15 "Fire guy!" | - | - |
| Campfire Scion Badge III | CAMPFIRE_TALISMAN_15 | E | - | Gain +16 Health Regen while burning. / Trial of Fire 16 "Fire guy!" | - | - |
| Campfire Scion Badge IV | CAMPFIRE_TALISMAN_16 | E | - | Gain +17 Health Regen while burning. / Trial of Fire 17 "Fire guy!" | - | - |
| Campfire Scion Badge V | CAMPFIRE_TALISMAN_17 | E | - | Gain +18 Health Regen while burning. / Trial of Fire 18 "Fire guy!" | - | - |
| Campfire Scion Badge VI | CAMPFIRE_TALISMAN_18 | E | - | Gain +19 Health Regen while burning. / Trial of Fire 19 "Fire guy!" | - | - |
| Campfire Scion Badge VII | CAMPFIRE_TALISMAN_19 | E | - | Gain +20 Health Regen while burning. / Trial of Fire 20 "Fire guy!" | - | - |
| Campfire Scion Badge VIII | CAMPFIRE_TALISMAN_20 | E | HPRegen +0.5 | Gain +21 Health Regen while burning. / Trial of Fire 21 "Fire guy!" | - | - |
| Candy Artifact | CANDY_ARTIFACT | E | - | Increases the drop chance of candies from mobs by 15% during the Spooky Festival. | - | - |
| Catacombs Expert Ring | CATACOMBS_EXPERT_RING | E | - | Increases Catacombs dungeon experience by +10%. | - | craft: 24x WITHER_CATALYST |
| Cheetah Talisman | CHEETAH_TALISMAN | E | Spd +3 | - | - | trade |
| Crux Relic | CRUX_TALISMAN_4 | E | - | Defeat crux mobs to gain +5 Rift Time and +5 Crux Fortune per kill milestone. Crux Fortune boosts the chance for bonus crux drops. / Kill Milestones - Shy: 0/10 kills | - | craft: 16x SCRIBE_CRUX, 1x CRUX_TALISMAN_3 |
| Defective Monitor | DEFECTIVE_MONITOR | E | - | Obtained from a Kloon who doesn't seem too stable. | - | - |
| Draconic Artifact | DRACONIC_ARTIFACT | E | HP +3 | Increases damage dealt to Dragons by 5%. / Created from ripping The End of its fauna. | - | craft: 12x RITUAL_RESIDUE, 2x TESSELLATED_ENDER_PEARL, 128x MITE_GEL, 1x DRACONIC_RING |
| Emperor's Artifact | EMPEROR_ARTIFACT | E | - | - | - | craft: 4x EMPEROR_RING |
| Ender Artifact | ENDER_ARTIFACT | E | - | Reduces the damage taken from  Ender mobs by 20%. | - | trade |
| Eternal Crystal | ETERNAL_CRYSTAL | E | - | - | - | - |
| Experience Artifact | EXPERIENCE_ARTIFACT | E | - | Increase the experience orbs you gain by 25% while this item is in your inventory or accessory bag. | - | craft: 9x ENCHANTED_LAPIS_LAZULI_BLOCK |
| Exquisite Ring of Love | WEDDING_RING_7 | E | - | Grants a 1 in 10,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Fiery Kuudra Core | FIERY_KUUDRA_CORE | E | - | Grants +2 Hunter Fortune. / A fragment of the core of Kuudra. | - | craft: 4x BURNING_KUUDRA_CORE |
| Freshly Baked Relic | FRESHLY_BAKED_RELIC | E | - | This item has x2  Overbloom during Feast events. | - | - |
| Fried Frozen Chicken | FRIED_FROZEN_CHICKEN | E | Spd +1, HeatRes +1, ColdRes +1 | Fried Frozen?! There is no such thing! | - | craft: 128x ENCHANTED_GLACITE, 256x FRIED_FEATHER, 128x ENCHANTED_BLAZE_POWDER, 1x FROZEN_CHICKEN |
| Ganache Chocolate Slab | GANACHE_CHOCOLATE_SLAB | E | - | Grants +8% chance to find a Chocolate Rabbit that you haven't found yet and grants +40 Chocolate per second. / A Factory masterpiece - its divine taste transcends reality, offering a heavenly escape. | - | - |
| Glossy Mineral Talisman | GLOSSY_MINERAL_TALISMAN | E | MinFort +6 | - | - | craft: 16x GLOSSY_GEMSTONE, 1x MINERAL_TALISMAN |
| Haste Artifact | HASTE_ARTIFACT | E | MinSpd +25 | Grants permanent Haste III. | - | craft: 256x ENCHANTED_COBBLESTONE, 1x HASTE_RING |
| Helianthus Relic | HELIANTHUS_RELIC | E | FarmFort +40 | - | - | craft: 8x CONDENSED_HELIANTHUS, 1x FERMENTO_ARTIFACT |
| Hocus-Pocus Cipher | HOCUS_POCUS_CIPHER | E | - | While in the rift: +65 Rift Time +5 Intelligence +5 Mana Regen / This item CANNOT be reforged! | - | - |
| Intimidation Relic | INTIMIDATION_RELIC | E | - | Monsters at or below Level 30 will no longer target you. | - | craft: 8x SCARE_FRAGMENT, 1x INTIMIDATION_ARTIFACT |
| Invaluable Ring of Love | WEDDING_RING_8 | E | - | Grants a 1 in 1,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Kuudra Follower Artifact | KUUDRA_FOLLOWER_ARTIFACT | E | - | Reduces damage taken from Kuudra Followers by 50%. | - | - |
| Kuudra's Lung | KUUDRAS_LUNG | E | - | You pick up crates 20% faster. You build the ballista 10% faster. | - | craft: 16x KUUDRA_TENTACLE, 1x KUUDRAS_KIDNEY |
| Lush Artifact | LUSH_ARTIFACT | E | HP +5, HPRegen +1 | - | - | craft: 512x ENCHANTED_LUSH_BERBERIS, 1x LUSH_RING |
| Magic 8 Ball | MAGIC_8_BALL | E | - | Every SkyBlock Season, the Magic 8 Ball will grant one of the following bonuses: / +25 Farming Fortune +1☯ Farming Wisdom ✧ Reply hazy, try again. +25 Foraging Fortune +1☯ Foraging Wisdom ✧ Don't count on it. +25 Mining Fortune +1☯ Mining Wisdom ✧ As I s... | - | - |
| Master Skull - Tier 6 | MASTER_SKULL_TIER_6 | E | - | Grants +8%  Health and  Strength while in Master Mode Dungeons. | - | craft: 4x MASTER_SKULL_TIER_5 |
| Master Skull - Tier 8 | MASTER_SKULL_TIER_8 | E | - | - | - | - |
| Melody's Hair | MELODY_HAIR | E | Int +6 | A single strand of hair from Melody's head. / Left-Click: Tune up! Right-Click: Play note! | Melody_Hair | - |
| Moonlight Crystal | MOONLIGHT_CRYSTAL | E | - | Increases your  Strength and  Defense by +10 during the Night. | - | craft: 32x COMPACTED_MOONFLOWER, 1x NIGHT_CRYSTAL |
| Nether Artifact | NETHER_ARTIFACT | E | - | Reduces the damage taken from  Infernal mobs by 5%. / While in the Crimson Isle, you will receive the damage reduction from all mobs. | - | - |
| Odger's Diamond Tooth | ODGERS_DIAMOND_TOOTH | E | SCC +2, TrophyCh +4 | A perfectly fine tooth, besides its radiant brightness... | Trophy Diamond | - |
| Perma-Jelled Garlic-Flavored Re-Heated Gummy Polar Bear | GARLIC_FLAVORED_GUMMY_BEAR | E | - | Gain +3 Combat Exp from mob kills in the Rift. | Vampire Slayer 3 | craft: 16x NEARLY_WHOLE_CARROT, 16x BACTE_FRAGMENT, 8x BLOODBADGE, 16x REHEATED_GUMMY_POLAR_BEAR |
| Personal Compactor 6000 | PERSONAL_COMPACTOR_6000 | E | - | Automatically turns certain materials in your inventory into their enchanted form. / Does not grant Carpentry XP! | - | craft: 7x ENCHANTED_REDSTONE_BLOCK, 1x PERSONAL_COMPACTOR_5000 |
| Personal Deletor 6000 | PERSONAL_DELETOR_6000 | E | - | Automatically delete specific items on pickup. | - | craft: 448x ENCHANTED_IRON, 1x PERSONAL_DELETOR_5000 |
| Pesthunter Artifact | PESTHUNTER_ARTIFACT | E | PestCh +60 | your  Bonus Pest Chance stat. / Enabled: On | - | - |
| PhD's Grimoire | PHD_GRIMOIRE | E | TrophyCh +4 | A brilliant treatise documenting the physiognomy of various frog species, their impact on surrounding wildlife, and...tears in the fabric of reality beyond our conception? What? | Trophy Diamond | - |
| Punchcard Artifact | PUNCHCARD_ARTIFACT | E | - | Ability: Punch in!  LEFT CLICK Punch another player while this is in your inventory to both regain +25ф Rift Time. Usable once per player per rift visit up to 20 players. | - | - |
| Purple Gift Talisman | PURPLE_GIFT_TALISMAN | E | - | Gain +20% more Coins and Skill XP from gifts. | - | - |
| Purple Jerry Talisman | JERRY_TALISMAN_PURPLE | E | Int -3 | Wear to prove you love Jerry, or wear to prove you hate Jerry. | - | craft: 5x JERRY_TALISMAN_BLUE |
| Red Claw Artifact | RED_CLAW_ARTIFACT | E | CD +5 | - | Wolf Slayer 5 | craft: 256x ENCHANTED_LEATHER, 54x GOLDEN_TOOTH, 1x RED_CLAW_RING, 1x RED_CLAW_EGG |
| Relic of Coins | RELIC_OF_COINS | E | - | A lot of coins start appearing around you occasionally on public islands. You may even pick up a Stock of Stonks! | - | craft: 64x FRESHLY_MINTED_COINS, 1x ARTIFACT_OF_COINS |
| Relic of Power | POWER_RELIC | E | - | Allows you to harness the power of 12 Gemstones, although only half of the normal power can be harnessed! | HotM 10 | forge (8h): POWER_ARTIFACT:1.0, PERFECT_PLATE:1.0, GLACITE_AMALGAMATION:32.0 |
| Scarf's Thesis | SCARF_THESIS | E | - | Gain 4% more Dungeons class experience. / A research on how to preserve and enhance class abilities in death. | - | craft: 4x SCARF_STUDIES |
| Seal of the Family | SEAL_OF_THE_FAMILY | E | - | Get 3% off on most shops. | - | - |
| Sharp Shark Tooth Necklace | SHARP_SHARK_TOOTH_NECKLACE | E | Str +8 | Grants +20% chance to get Shark Teeth during Fishing Festivals. | - | craft: 4x TIGER_SHARK_TOOTH, 1x HONED_SHARK_TOOTH_NECKLACE |
| Soul Campfire Scion Badge I | SOUL_CAMPFIRE_TALISMAN_13 | E | - | Grants +14 Defense while burning. / Trial of Blue Flames 14 "Fire guy!" | - | - |
| Soul Campfire Scion Badge II | SOUL_CAMPFIRE_TALISMAN_14 | E | - | Grants +15 Defense while burning. / Trial of Blue Flames 15 "Fire guy!" | - | - |
| Soul Campfire Scion Badge III | SOUL_CAMPFIRE_TALISMAN_15 | E | - | Grants +16 Defense while burning. / Trial of Blue Flames 16 "Fire guy!" | - | - |
| Soul Campfire Scion Badge IV | SOUL_CAMPFIRE_TALISMAN_16 | E | - | Grants +17 Defense while burning. / Trial of Blue Flames 17 "Fire guy!" | - | - |
| Soul Campfire Scion Badge V | SOUL_CAMPFIRE_TALISMAN_17 | E | - | Grants +18 Defense while burning. / Trial of Blue Flames 18 "Fire guy!" | - | - |
| Soul Campfire Scion Badge VI | SOUL_CAMPFIRE_TALISMAN_18 | E | - | Grants +19 Defense while burning. / Trial of Blue Flames 19 "Fire guy!" | - | - |
| Soul Campfire Scion Badge VII | SOUL_CAMPFIRE_TALISMAN_19 | E | - | Grants +20 Defense while burning. / Trial of Blue Flames 20 "Fire guy!" | - | - |
| Soul Campfire Scion Badge VIII | SOUL_CAMPFIRE_TALISMAN_20 | E | - | Grants +21 Defense while burning. / Trial of Blue Flames 21 "Fire guy!" | - | - |
| Soulflow Supercell | SOULFLOW_SUPERCELL | E | - | Internalized: 0⸎ Soulflow / Ability: Overflow Mana When leaving combat, convert up to 4⸎ Soulflow into 600 Overflow Mana. / Enabled: On / Soulflow from abilities is drained from your internalized ⸎. | Enderman Slayer 7 | craft: 192x NULL_OVOID, 2x NULL_ATOM, 1x SOULFLOW_BATTERY |
| Speed Relic | SPEED_RELIC | E | Spd +7, AtkSpd +1, MinSpd +1, FishSpd +1 | - | - | craft: 256x ENCHANTED_SUGAR_CANE, 64x CANE_KNOT, 1x SPEED_ARTIFACT |
| Spider Artifact | SPIDER_ARTIFACT | E | - | Reduces the damage taken from  Arthropod mobs by 15%. | Spider Slayer 6 | craft: 32x ENCHANTED_EMERALD, 32x TARANTULA_SILK, 1x SPIDER_RING |
| Spiked Atrocity | SPIKED_ATROCITY | E | - | 10% chance not to consume bait. | - | craft: 128x ENCHANTED_INK_SACK, 128x SPIKED_BAIT, 1x BAIT_RING |
| Sunshine Crystal | SUNSHINE_CRYSTAL | E | - | Increases your  Strength and  Defense by +10 during the Day. | - | craft: 32x COMPACTED_SUNFLOWER, 1x DAY_CRYSTAL |
| Supreme Voter's Badge | VOTER_BADGE_SUPREME | E | - | Grants +12 votes in Mayor Elections. | - | - |
| Tarantula Talisman | TARANTULA_TALISMAN | E | - | Every 10th melee hit on the same enemy deals +10% damage. | Spider Slayer 6 | - |
| Titanium Artifact | TITANIUM_ARTIFACT | E | MinSpd +45 | - | HotM 4 | forge (36h): REFINED_TITANIUM:12, TITANIUM_RING:1 |
| Treasure Ring | TREASURE_RING | E | - | Grants +2% extra loot to end of dungeon chests. | - | craft: 8x TREASURE_TALISMAN |
| Warding Trinket | WARDING_TRINKET | E | - | - | - | - |
| Wither Artifact | WITHER_ARTIFACT | E | - | Reduces the damage taken from  Wither mobs by 20%. | - | trade |
| Anguish Artifact | ANGUISH_ARTIFACT | L | MF +3, TrueDef +1.5 | Why are we still here? Just to suffer? | - | craft: 64x SORROW, 64x PLASMA, 1x ANGUISH_RING |
| Artifact of Control | ARTIFACT_OF_CONTROL | L | - | Holding this artifact will grant you 2x voting power in Mayor Elections! | - | - |
| Auto Recombobulator | AUTO_RECOMBOBULATOR | L | - | Grants a 1% chance to automatically recombobulate drops you get from enemies. | - | - |
| Bat Artifact | BAT_ARTIFACT | L | Spd +3, HP +5, Int +3 | - | - | - |
| Beastmaster Crest | BEASTMASTER_CREST_LEGENDARY | L | - | Pet Exp Boost: +0.5% / Gains stats based on how many  Mythological mobs and Minos followers you've slain. / Your kills: 0/25 | - | craft: 256x GRIFFIN_FEATHER, 32x ENCHANTED_ANCIENT_CLAW, 1x BEASTMASTER_CREST_EPIC |
| Bingo Heirloom | BINGO_HEIRLOOM | L | - | - | - | - |
| Campfire God Badge I | CAMPFIRE_TALISMAN_21 | L | HPRegen +1 | Gain +22 Health Regen while burning. / Trial of Fire 22 "Fire guy!" | - | - |
| Campfire God Badge II | CAMPFIRE_TALISMAN_22 | L | HPRegen +1.5 | Gain +23 Health Regen while burning. / Trial of Fire 23 "Fire guy!" | - | - |
| Campfire God Badge III | CAMPFIRE_TALISMAN_23 | L | HPRegen +2 | Gain +24 Health Regen while burning. / Trial of Fire 24 "Fire guy!" | - | - |
| Campfire God Badge IV | CAMPFIRE_TALISMAN_24 | L | HPRegen +2.5 | Gain +25 Health Regen while burning. / Trial of Fire 25 "Fire guy!" | - | - |
| Campfire God Badge IX | CAMPFIRE_TALISMAN_29 | L | HPRegen +5 | Gain +30 Health Regen while burning. / Trial of Fire 30 "Fire guy!" | - | - |
| Campfire God Badge V | CAMPFIRE_TALISMAN_25 | L | HPRegen +3 | Gain +26 Health Regen while burning. / Trial of Fire 26 "Fire guy!" | - | - |
| Campfire God Badge VI | CAMPFIRE_TALISMAN_26 | L | HPRegen +3.5 | Gain +27 Health Regen while burning. / Trial of Fire 27 "Fire guy!" | - | - |
| Campfire God Badge VII | CAMPFIRE_TALISMAN_27 | L | HPRegen +4 | Gain +28 Health Regen while burning. / Trial of Fire 28 "Fire guy!" | - | - |
| Campfire God Badge VIII | CAMPFIRE_TALISMAN_28 | L | HPRegen +4.5 | Gain +29 Health Regen while burning. / Trial of Fire 29 "Fire guy!" | - | - |
| Candy Relic | CANDY_RELIC | L | - | Increases the drop chance of candies from mobs by 20% during the Spooky Festival. | - | - |
| Crux Heirloom | CRUX_TALISMAN_5 | L | - | Defeat crux mobs to gain +5 Rift Time and +5 Crux Fortune per kill milestone. Crux Fortune boosts the chance for bonus crux drops. / Kill Milestones - Shy: 0/10 kills | - | craft: 24x FROSTY_CRUX, 1x CRUX_TALISMAN_4 |
| Ender Relic | ENDER_RELIC | L | - | Reduces the damage taken from  Ender mobs by 25%. | Enderman Slayer 7 | craft: 128x ENCHANTED_OBSIDIAN, 96x ENCHANTED_EYE_OF_ENDER, 1x ENDER_ARTIFACT, 1x EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER |
| Freshly Baked Heirloom | FRESHLY_BAKED_HEIRLOOM | L | - | This item has x2  Overbloom during Feast events. | - | - |
| Gold Gift Talisman | GOLD_GIFT_TALISMAN | L | - | Gain +25% more Coins and Skill XP from gifts. | - | - |
| Golden Jerry Artifact | JERRY_TALISMAN_GOLDEN | L | Int -4 | Wear to prove you love Jerry, or wear to prove you hate Jerry. | - | craft: 5x JERRY_TALISMAN_PURPLE |
| Hegemony Artifact | HEGEMONY_ARTIFACT | L | - | Counts for twice the Accessory Power in your Accessory Bag. | - | - |
| Infernal Kuudra Core | INFERNAL_KUUDRA_CORE | L | - | Grants +3 Hunter Fortune. / The dark core of Kuudra! | - | craft: 4x FIERY_KUUDRA_CORE |
| Jacobus Register | JACOBUS_REGISTER | L | HP +10, PetLuck +1 | - | - | - |
| Kuudra Follower Relic | KUUDRA_FOLLOWER_RELIC | L | - | Reduces damage taken from Kuudra Followers by 65%. | - | - |
| Kuudra's Heart | KUUDRAS_HEART | L | - | You pick up crates 30% faster. You build the ballista 15% faster. | - | craft: 32x KUUDRA_TENTACLE, 1x KUUDRAS_LUNG |
| Legendary Ring of Love | WEDDING_RING_9 | L | - | Grants a 1 in 100 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| Master Skull - Tier 10 | MASTER_SKULL_TIER_10 | L | - | - | - | - |
| Master Skull - Tier 7 | MASTER_SKULL_TIER_7 | L | - | Grants +10%  Health and  Strength while in Master Mode Dungeons. | - | craft: 4x MASTER_SKULL_TIER_6 |
| Master Skull - Tier 9 | MASTER_SKULL_TIER_9 | L | - | - | - | - |
| Personal Compactor 7000 | PERSONAL_COMPACTOR_7000 | L | - | Automatically turns certain materials in your inventory into their enchanted form. / Does not grant Carpentry XP! | - | craft: 21x ENCHANTED_REDSTONE_BLOCK, 1x PERSONAL_COMPACTOR_6000 |
| Personal Deletor 7000 | PERSONAL_DELETOR_7000 | L | - | Automatically delete specific items on pickup. | - | craft: 7x ENCHANTED_IRON_BLOCK, 1x PERSONAL_DELETOR_6000 |
| Pesthunter Relic | PESTHUNTER_RELIC | L | PestCh +80 | your  Bonus Pest Chance stat. / Enabled: On | - | - |
| Prestige Chocolate Realm | PRESTIGE_CHOCOLATE_REALM | L | - | Grants +10% chance to find a Chocolate Rabbit that you haven't found yet and grants +50 Chocolate per second. / The Factory's pinnacle creation - its epic taste shatters expectations, offering a taste of utopia. | - | - |
| Razor-sharp Shark Tooth Necklace | RAZOR_SHARP_SHARK_TOOTH_NECKLACE | L | Str +10 | Grants +25% chance to get Shark Teeth during Fishing Festivals. | - | craft: 4x GREAT_WHITE_SHARK_TOOTH, 1x SHARP_SHARK_TOOTH_NECKLACE |
| Reaper Orb | REAPER_ORB | L | - | Gain a stack of 2 Strength for every mob killed in the last 5 seconds. | Zombie Slayer 8 | craft: 8x SCYTHE_BLADE |
| Ring of Broken Love | RING_OF_BROKEN_LOVE | L | - | This time around, fate really got in the way of love. | - | - |
| Ring of Eternal Love | RING_OF_ETERNAL_LOVE | L | - | Ramero and Joliet's love transcends the boundaries of time, space, and fate itself. | - | - |
| Satelite | SATELITE | L | - | High altitude, slow attitude. The procrastinator's dream stone. / Ability: Bullet Time Slow down the loss of  Rift Time by 20% in elevated areas. | - | craft: 64x YOUNGITE, 32x TIMITE, 32x OBSOLITE, 1x HIGHLITE |
| Scarf's Grimoire | SCARF_GRIMOIRE | L | - | Gain 6% more Dungeons class experience. / Dark and powerful spells, includes his favorite "Barrier" that his Professor taught him. | - | craft: 4x SCARF_THESIS |
| Shen's Regalia | SHENS_REGALIA | L | - | According to the rumors, Shen himself is crafting special items. Have this in your inventory to gain access to a unique room hidden below the Auction House. | - | - |
| Soul Campfire God Badge I | SOUL_CAMPFIRE_TALISMAN_21 | L | Mend +2 | Grants +22 Defense while burning. / Trial of Blue Flames XXII "Fire guy!" | - | - |
| Soul Campfire God Badge II | SOUL_CAMPFIRE_TALISMAN_22 | L | Mend +3 | Grants +23 Defense while burning. / Trial of Blue Flames 23 "Fire guy!" | - | - |
| Soul Campfire God Badge III | SOUL_CAMPFIRE_TALISMAN_23 | L | Mend +4 | Grants +24 Defense while burning. / Trial of Blue Flames 24 "Fire guy!" | - | - |
| Soul Campfire God Badge IV | SOUL_CAMPFIRE_TALISMAN_24 | L | Mend +5 | Grants +25 Defense while burning. / Trial of Blue Flames 25 "Fire guy!" | - | - |
| Soul Campfire God Badge IX | SOUL_CAMPFIRE_TALISMAN_29 | L | Mend +10 | Grants +30 Defense while burning. / Trial of Blue Flames 30 "Fire guy!" | - | - |
| Soul Campfire God Badge V | SOUL_CAMPFIRE_TALISMAN_25 | L | Mend +6 | Grants +26 Defense while burning. / Trial of Blue Flames 26 "Fire guy!" | - | - |
| Soul Campfire God Badge VI | SOUL_CAMPFIRE_TALISMAN_26 | L | Mend +7 | Grants +27 Defense while burning. / Trial of Blue Flames 27 "Fire guy!" | - | - |
| Soul Campfire God Badge VII | SOUL_CAMPFIRE_TALISMAN_27 | L | Mend +8 | Grants +28 Defense while burning. / Trial of Blue Flames 28 "Fire guy!" | - | - |
| Soul Campfire God Badge VIII | SOUL_CAMPFIRE_TALISMAN_28 | L | Mend +9 | Grants +29 Defense while burning. / Trial of Blue Flames 29 "Fire guy!" | - | - |
| Tarantula Ring | TARANTULA_RING | L | - | Every 10th melee hit on the same enemy deals +15% damage. | Spider Slayer 7 | craft: 64x TARANTULA_SILK, 1x TARANTULA_CATALYST, 64x SOUL_STRING, 1x TARANTULA_TALISMAN, 1x DIGESTED_MOSQUITO |
| Titanium Relic | TITANIUM_RELIC | L | MinSpd +60 | - | HotM 5 | forge (72h): TITANIUM_ARTIFACT:1, REFINED_TITANIUM:20 |
| Treasure Artifact | TREASURE_ARTIFACT | L | - | Grants +3% extra loot to end of dungeon chests. | - | craft: 9x TREASURE_RING |
| Vampire Dentist Relic | VAMPIRE_DENTIST_RELIC | L | HPRegen +5, Vit +1 | Apply a natural regen tick when you deal melee damage. (20s cooldown) | Vampire Slayer 5 | craft: 8x BLOODBADGE, 1x UNFANGED_VAMPIRE_PART |
| Wither Relic | WITHER_RELIC | L | - | Reduces the damage taken from  Wither mobs by 25%. | - | craft: 8x WITHER_CATALYST, 1x WITHER_ARTIFACT |
| Crux Chronomicon | CRUX_TALISMAN_6 | M | - | Defeat crux mobs to gain +5ф Rift Time and +5 Crux Fortune per kill milestone. Crux Fortune boosts the chance for bonus crux drops. / Kill Milestones - Shy: 0/10 kills | - | craft: 32x SPLATTER_CRUX, 1x CRUX_TALISMAN_5 |
| Crab Hat of Celebration | PARTY_HAT_CRAB | S | MF +1 | - | - | - |
| Crab Hat of Celebration - 2022 Edition | PARTY_HAT_CRAB_ANIMATED | S | MF +1 | - | - | - |
| Sloth Hat of Celebration | PARTY_HAT_SLOTH | S | MF +1 | - | - | - |
| Bat Person Talisman | BAT_PERSON_TALISMAN | ? | - | Gain +2  Defense,  Strength, and  Intelligence for each bat you have summoned. / Works with Spirit Sceptre, Witch Mask, and the Bat Pet! | - | - |
| Beastmaster Crest | BEASTMASTER_CREST_COMMON | ? | - | Pet Exp Boost: +0.1% / Gains stats based on how many  Mythological mobs and Minos followers you've slain. / Your kills: 0/25 | - | craft: 4x GRIFFIN_FEATHER, 40x ANCIENT_CLAW, 1x MILK_BUCKET |
| Blood God Crest | BLOOD_GOD_CREST | ? | - | Tracks a counter for each mob killed while worn. / Gain +1 Strength for each digit on the counter. (Max 7) / Counter: None yet! | - | trade |
| Campfire Initiate Badge I | CAMPFIRE_TALISMAN_1 | ? | - | Gain +2 Health Regen while burning. / Trial of Fire 2 "Fire guy!" | - | - |
| Campfire Initiate Badge II | CAMPFIRE_TALISMAN_2 | ? | - | Gain +3 Health Regen while burning. / Trial of Fire 3 "Fire guy!" | - | - |
| Campfire Initiate Badge III | CAMPFIRE_TALISMAN_3 | ? | - | Gain +4 Health Regen while burning. / Trial of Fire 4 "Fire guy!" | - | - |
| Celestial Starstone | CRUX_TALISMAN_7 | Sup | - | Defeat crux mobs to gain +5 Rift Time and +5 Crux Fortune per kill milestone. Crux Fortune boosts the chance for bonus crux drops. / Kill Milestones - Shy: 0/10 kills | - | craft: 128x PUFF_CRUX, 1x CRUX_TALISMAN_6 |
| Compass Talisman | COMPASS_TALISMAN | ? | - | - | - | - |
| Dante's Ring | DANTE_RING | ? | - | Found in a hidden warehouse. Not very useful but still pretty nice to wear. | - | - |
| Dante's Talisman | DANTE_TALISMAN | ? | - | Official medal proving your eternal support for Dante. / Happy Mask for entry into official buildings. | - | trade |
| Feather Talisman | FEATHER_TALISMAN | ? | - | Increases how high you can fall before taking fall damage by 5 blocks. | - | craft: 108x FEATHER |
| Fire Talisman | FIRE_TALISMAN | ? | - | Provides immunity against damage from Fire. / While in the Crimson Isle, grants a 20% damage reduction instead. | - | craft: 9x ENCHANTED_BLAZE_POWDER |
| Handy Blood Chalice | HANDY_BLOOD_CHALICE | ? | - | ⁍ Gain +20 Ferocity / Enabled: Off | Enderman Slayer 5 | - |
| Healing Talisman | HEALING_TALISMAN | ? | Vit +2 | - | - | craft: 144x WATER_LILY |
| Intimidation Talisman | INTIMIDATION_TALISMAN | ? | - | Level 1 monsters will no longer target you. | - | trade |
| King Talisman | KING_TALISMAN | ? | - | Commissions grant +10 HOTM Exp. | - | - |
| Master Skull - Tier 1 | MASTER_SKULL_TIER_1 | ? | - | Grants +1%  Health and  Strength while in Master Mode Dungeons. | - | - |
| Master Skull - Tier 2 | MASTER_SKULL_TIER_2 | ? | - | Grants +2%  Health and  Strength while in Master Mode Dungeons. | - | craft: 4x MASTER_SKULL_TIER_1 |
| Mine Affinity Talisman | MINE_TALISMAN | ? | - | Grants +25 Speed while on Mining Islands. | - | - |
| Night Vision Charm | NIGHT_VISION_CHARM | ? | - | Grants Night Vision while this item is in your inventory. | - | craft: 4x ENCHANTED_RED_MUSHROOM, 4x ENCHANTED_BROWN_MUSHROOM |
| Pocket Espresso Machine | POCKET_ESPRESSO_MACHINE | ? | - | ⁍ Gain +100 Intelligence / Enabled: Off | Enderman Slayer 4 | - |
| Potato Talisman | POTATO_TALISMAN | ? | Spd +1 | Your potato minions farm 5% faster while wearing on your island. / Forged by the Potato King after winning the Great Potato War. | - | trade |
| Potion Affinity Talisman | POTION_AFFINITY_TALISMAN | ? | - | Increases the duration of any consumed Potion by 10%. | - | craft: 144x NETHER_STALK |
| Raggedy Shark Tooth Necklace | RAGGEDY_SHARK_TOOTH_NECKLACE | ? | Str +2 | Grants +5% chance to get Shark Teeth during Fishing Festivals. | - | craft: 8x SHARK_FIN, 1x NURSE_SHARK_TOOTH |
| Runebook | RUNEBOOK | ? | - | Tracks a counter for each Runic mob killed while in your accessory bag or inventory. / Increase rarity and gain +1☯ Runecrafting Wisdom for each digit on the counter. (Max 4 digits) / Counter: None yet! | - | - |
| Scavenger Talisman | SCAVENGER_TALISMAN | ? | - | Grants +0.5 coins per level of the killed enemy. | - | trade |
| Sea Creature Talisman | SEA_CREATURE_TALISMAN | ? | SCC +0.1 | Take 5% less damage from Sea Creatures. | - | craft: 18x SPONGE |
| Skeleton Talisman | SKELETON_TALISMAN | ? | - | Reduces the damage taken from  Skeletal mobs by 5%. | - | trade |
| Soul Campfire Initiate Badge I | SOUL_CAMPFIRE_TALISMAN_1 | ? | - | Grants +2 Defense while burning. / Trial of Blue Flames 2 "Fire guy!" | - | - |
| Soul Campfire Initiate Badge II | SOUL_CAMPFIRE_TALISMAN_2 | ? | - | Grants +3 Defense while burning. / Trial of Blue Flames 3 "Fire guy!" | - | - |
| Soul Campfire Initiate Badge III | SOUL_CAMPFIRE_TALISMAN_3 | ? | - | Grants +4 Defense while burning. / Trial of Blue Flames 4 "Fire guy!" | - | - |
| Speed Talisman | SPEED_TALISMAN | ? | Spd +1 | - | - | craft: 108x SUGAR_CANE |
| Talisman of Coins | COIN_TALISMAN | ? | - | Coins start appearing around you occasionally on public islands. Lucky! | - | craft: 20x EMERALD, 5x GOLD_INGOT |
| Talisman of Power | POWER_TALISMAN | ? | - | Allows you to harness the power of 2 Gemstones, although only half of the normal power can be harnessed! | - | craft: 45x FLAWED_RUBY_GEM |
| Vaccine Talisman | VACCINE_TALISMAN | ? | - | Reduces the effect of poison by 10%. | - | craft: 9x POISONOUS_POTATO |
| Village Affinity Talisman | VILLAGE_TALISMAN | ? | - | Increases your  Speed by +10 while held in the Village. | - | - |
| Zombie Talisman | ZOMBIE_TALISMAN | ? | - | Reduces the damage taken from  Undead mobs by 5%. | - | trade |
| §eShiny Yellow Rock | WEDDING_RING_0 | ? | - | Grants a 1 in 1,000,000,000,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |
| §eYellow Rock of Love | WEDDING_RING_1 | ? | - | Grants a 1 in 100,000,000,000 chance to deal +100% damage. / True love is tough to find, but finding it hits you hard! | - | - |

## Appendix C2 — Equipment (necklace, cloak, belt, gloves, bracelet, gauntlet)

| Item | ID | Tier | Slot | Stats | Effect | Req | Source/recipe |
|---|---|---|---|---|---|---|---|
| Angler Belt | ANGLER_BELT | C | Belt | FishSpd +1, SCC +0.5 | Tiered Bonus: Depth Champion (0/8) Reduces the damage taken from Sea Creatures by 3%. / Tiered Bonus: Deepness Within (0/4) Gain 6❤ per Fishing level. Fishing: Level 0 | - | - |
| Angler Bracelet | ANGLER_BRACELET | C | Bracelet | FishSpd +1, SCC +0.5 | Tiered Bonus: Depth Champion (0/8) Reduces the damage taken from Sea Creatures by 3%. / Tiered Bonus: Deepness Within (0/4) Gain 6❤ per Fishing level. Fishing: Level 0 | - | - |
| Angler Cloak | ANGLER_CLOAK | C | Cloak | FishSpd +1, SCC +0.5 | Tiered Bonus: Depth Champion (0/8) Reduces the damage taken from Sea Creatures by 3%. / Tiered Bonus: Deepness Within (0/4) Gain 6❤ per Fishing level. Fishing: Level 0 | - | - |
| Angler Necklace | ANGLER_NECKLACE | C | Necklace | FishSpd +1, SCC +0.5 | Tiered Bonus: Depth Champion (0/8) Reduces the damage taken from Sea Creatures by 3%. / Tiered Bonus: Deepness Within (0/4) Gain 6❤ per Fishing level. Fishing: Level 0 | - | - |
| David's Cloak | DAVIDS_CLOAK | C | Cloak | HP +50, MF +2 | The more you Hunt, the stronger this cloak gets! / Talk to David to view your progress! | - | - |
| Safari Belt | SAFARI_BELT | C | Belt | - | The more Safari Milestones you have, the stronger this belt gets! / Talk to Andrea to view your progress! | - | - |
| White Belt | DOJO_WHITE_BELT | C | Belt | HP +10, Def +5 | Reduces damage taken by 0.5%. | - | - |
| Backwater Belt | BACKWATER_BELT | U | Belt | FishSpd +2, SCC +1, Treasure +0.1 | Tiered Bonus: Swamp Soldier (0/8) Increases damage dealt to Sea Creatures by 5%. | Fishing 5 | - |
| Backwater Cloak | BACKWATER_CLOAK | U | Cloak | FishSpd +2, SCC +1, Treasure +0.1 | Tiered Bonus: Swamp Soldier (0/8) Increases damage dealt to Sea Creatures by 5%. | Fishing 5 | - |
| Backwater Gloves | BACKWATER_GLOVES | U | Gloves | FishSpd +2, SCC +1, Treasure +0.1 | Tiered Bonus: Swamp Soldier (0/8) Increases damage dealt to Sea Creatures by 5%. | Fishing 5 | - |
| Backwater Necklace | BACKWATER_NECKLACE | U | Necklace | FishSpd +2, SCC +1, Treasure +0.1 | Tiered Bonus: Swamp Soldier (0/8) Increases damage dealt to Sea Creatures by 5%. | Fishing 5 | - |
| Green Belt | DOJO_GREEN_BELT | U | Belt | HP +20, Def +10 | Reduces damage taken by 3%. | - | - |
| Luminous Bracelet | LUMINOUS_BRACELET | U | Bracelet | FishSpd +1, SCC +1 | Earn +20 Coins and +10 Fishing XP when depositing Chum in someone ELSE's bucket. | Fishing 12 | - |
| Mithril Belt | MITHRIL_BELT | U | Belt | MinSpd +20, MinFort +5 | Grants +5 Mining Fortune while mining Mithril. / Full Set Bonus: Dwarf Wannabe (0/4) Grants +30 Mining Speed and +5 Mining Fortune while in the Dwarven Mines. | HotM 2 | forge (1h): ENCHANTED_MITHRIL:3.0 |
| Mithril Cloak | MITHRIL_CLOAK | U | Cloak | MinSpd +20, MinFort +5 | Grants +5 Mining Fortune while mining Mithril. / Full Set Bonus: Dwarf Wannabe (0/4) Grants +30 Mining Speed and +5 Mining Fortune while in the Dwarven Mines. | HotM 2 | forge (1h): ENCHANTED_MITHRIL:3.0 |
| Mithril Gauntlet | MITHRIL_GAUNTLET | U | Gloves | MinSpd +20, MinFort +5 | Grants +5 Mining Fortune while mining Mithril. / Full Set Bonus: Dwarf Wannabe (0/4) Grants +30 Mining Speed and +5 Mining Fortune while in the Dwarven Mines. | HotM 2 | forge (1h): ENCHANTED_MITHRIL:3.0 |
| Mithril Necklace | MITHRIL_NECKLACE | U | Necklace | MinSpd +20, MinFort +5 | Grants +5 Mining Fortune while mining Mithril. / Full Set Bonus: Dwarf Wannabe (0/4) Grants +30 Mining Speed and +5 Mining Fortune while in the Dwarven Mines. | HotM 2 | forge (1h): ENCHANTED_MITHRIL:3.0 |
| Party Belt | PARTY_BELT | U | Belt | - | Tiered Bonus: Carnival Craze (0/5) Gain +4% more Carnival Tokens. | - | - |
| Party Cloak | PARTY_CLOAK | U | Cloak | - | Tiered Bonus: Carnival Craze (0/5) Gain +4% more Carnival Tokens. | - | - |
| Party Gloves | PARTY_GLOVES | U | Gloves | - | Tiered Bonus: Carnival Craze (0/5) Gain +4% more Carnival Tokens. | - | - |
| Party Necklace | PARTY_NECKLACE | U | Necklace | - | Tiered Bonus: Carnival Craze (0/5) Gain +4% more Carnival Tokens. | - | - |
| Silkrider Safety Belt | SILKRIDER_SAFETY_BELT | U | Belt | - | Monster melee attacks reduce 1 less  Rift Time. | - | - |
| §eYellow Belt | DOJO_YELLOW_BELT | U | Belt | HP +15, Def +5 | Reduces damage taken by 1%. | - | - |
| Arachne's Belt | ARACHNE_BELT | R | Belt | Def +5, HP +10 | Tiered Bonus: Arachne's Faithful (0/8) Grants +5 Health and +5 Defense. / This item is Arachnal ! | - | - |
| Arachne's Cloak | ARACHNE_CLOAK | R | Cloak | Def +5, HP +10 | Tiered Bonus: Arachne's Faithful (0/8) Grants +5 Health and +5 Defense. / This item is Arachnal ! | - | - |
| Arachne's Gloves | ARACHNE_GLOVES | R | Gloves | Def +5, HP +10 | Tiered Bonus: Arachne's Faithful (0/8) Grants +5 Health and +5 Defense. / This item is Arachnal ! | - | - |
| Arachne's Necklace | ARACHNE_NECKLACE | R | Necklace | Def +5, HP +10 | Tiered Bonus: Arachne's Faithful (0/8) Grants +5 Health and +5 Defense. / This item is Arachnal ! | - | - |
| Balloon Snake | BALLOON_SNAKE | R | Necklace | HP +10, Int +10 | Grants Jump Boost II while equipped. | F1 done | - |
| Blaze Belt | BLAZE_BELT | R | Belt | Def +7, Str +7 | Ability: Blazing Palisade Gain a 10% chance to reflect Fireballs. | Combat 22 | craft: 12x ENCHANTED_BLAZE_POWDER, 25x BLAZE_ASHES |
| Blue Belt | DOJO_BLUE_BELT | R | Belt | HP +25, Def +15 | Reduces damage taken by 4%. | - | - |
| Clay Bracelet | CLAY_BRACELET | R | Bracelet | FishSpd +2, TrophyCh +1 | - | - | craft: 96x CLAY_BALL, 4x ENCHANTED_CLAY_BALL |
| Disinfestor Gloves | DISINFESTOR_GLOVES | R | Gloves | - | - | - | - |
| Frozen Amulet | FROZEN_AMULET | R | Necklace | SCC +1, FishSpd +3, Treasure +0.5 | - | - | - |
| Ghast Cloak | GHAST_CLOAK | R | Cloak | HP +7, Def +7 | Ability: Blazing Restoration Restores +5 Health every second while worn. | Combat 22 | craft: 192x ENCHANTED_GHAST_TEAR, 10x TENTACLE_MEAT |
| Glowstone Gauntlet | GLOWSTONE_GAUNTLET | R | Gloves | Int +10, MinFort +5 | - | Combat 22 | craft: 9x ENCHANTED_LEATHER, 12x ENCHANTED_GLOWSTONE_DUST |
| Ichthyic Belt | ICHTHYIC_BELT | R | Belt | HP +12, FishSpd +6 | - | - | craft: 16x BLUE_SHARK_TOOTH, 32x NURSE_SHARK_TOOTH, 4x ENCHANTED_SHARK_FIN |
| Ichthyic Cloak | ICHTHYIC_CLOAK | R | Cloak | HP +10, FishSpd +6 | - | - | craft: 16x BLUE_SHARK_TOOTH, 32x NURSE_SHARK_TOOTH, 4x ENCHANTED_SHARK_FIN |
| Ichthyic Gloves | ICHTHYIC_GLOVES | R | Gloves | HP +8, FishSpd +6 | - | - | craft: 12x BLUE_SHARK_TOOTH, 32x NURSE_SHARK_TOOTH, 4x ENCHANTED_SHARK_FIN |
| Leech Belt | LEECH_BELT | R | Belt | - | Monster melee attacks reduce 1 less  Rift Time. | - | craft: 8x LEECH_SUPREME_FRAGMENT, 1x SILKRIDER_SAFETY_BELT |
| Magma Necklace | MAGMA_NECKLACE | R | Necklace | HP +15 | Ability: Cube Fanatic Gain 10% more Magma Cream when killing Magma Cubes. | Combat 22 | craft: 12x ENCHANTED_MAGMA_CREAM, 20x MAGMA_CHUNK |
| Mangrove Grippers | MANGROVE_GRIPPERS | R | Gloves | ForFort +5, Sweep +1 | - | Foraging 12 | craft: 6x ENCHANTED_SEA_LUMIES, 6x MANGCORE |
| Mangrove Locket | MANGROVE_LOCKET | R | Necklace | ForFort +5, Sweep +1 | - | Foraging 12 | craft: 20x ENCHANTED_MANGROVE_LOG, 32x SEA_LUMIES |
| Moonglade Belt | MANGROVE_VINE | R | Belt | ForFort +5, Sweep +1 | Grants +5% more points towards Starlyn Contests. | Foraging 12 | craft: 64x STRETCHING_STICKS, 64x ENCHANTED_MANGROVE_LOG, 1x ENCHANTED_SEA_LUMIES |
| Peony Belt | LOTUS_BELT | R | Belt | FarmFort +5, HP +10 | Piece Bonus: Salesperson Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1 (0/1) | Garden 3, Farming 10 | - |
| Peony Bracelet | LOTUS_BRACELET | R | Bracelet | FarmFort +5, HP +10 | Piece Bonus: Salesperson Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1 (0/1) | Garden 2, Farming 10 | - |
| Peony Cloak | LOTUS_CLOAK | R | Cloak | FarmFort +5, HP +10 | Piece Bonus: Salesperson Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1 (0/1) | Garden 5, Farming 10 | - |
| Peony Necklace | LOTUS_NECKLACE | R | Necklace | FarmFort +5, HP +10 | Piece Bonus: Salesperson Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1 (0/1) | Garden 4, Farming 10 | - |
| Pesthunter's Belt | PESTHUNTERS_BELT | R | Belt | PestCh +5 | Decreases the spawn cooldown of  Pests by 10%. / Tiered Bonus: Eradicator (0/4) Grants +0 Farming Fortune when vacuuming up  Pests | Garden 5 | - |
| Pesthunter's Cloak | PESTHUNTERS_CLOAK | R | Cloak | PestCh +5 | Decreases the spawn cooldown of  Pests by 10%. / Tiered Bonus: Eradicator (0/4) Grants +0 Farming Fortune when vacuuming up  Pests | Garden 5 | - |
| Pesthunter's Gloves | PESTHUNTERS_GLOVES | R | Gloves | PestCh +5 | Decreases the spawn cooldown of  Pests by 10%. / Tiered Bonus: Eradicator (0/4) Grants +0 Farming Fortune when vacuuming up  Pests | Garden 5 | - |
| Pesthunter's Necklace | PESTHUNTERS_NECKLACE | R | Necklace | PestCh +5 | Decreases the spawn cooldown of  Pests by 10%. / Tiered Bonus: Eradicator (0/4) Grants +0 Farming Fortune when vacuuming up  Pests | Garden 5 | - |
| Prismarine Necklace | PRISMARINE_NECKLACE | R | Necklace | FishSpd +2, TrophyCh +1 | - | - | craft: 4x ENCHANTED_PRISMARINE_SHARD, 1x ENCHANTED_PRISMARINE_CRYSTALS |
| Scoville Belt | SCOVILLE_BELT | R | Belt | Def +30, Str +15 | Gain +50 Defense per ignited enemy within a 10 block radius up to a maximum of +1,000 Defense. | Combat 22 | craft: 20x COMPACT_OOZE, 5x REKINDLED_EMBER_FRAGMENT |
| Snow Belt | SNOW_BELT | R | Belt | HP +5, Int +5, Spd +2 | Full Set Bonus: Cold Thumb (0/8) Allows the wearer to shoot unlimited snowballs when using Frosty the Snow Cannon. / Each piece grants a 5% bonus gift chance for every present you earn from the Gift Attack minigame! / All stats of this armor piece are doubl... | - | - |
| Snow Cloak | SNOW_CLOAK | R | Cloak | HP +5, Int +5, Spd +2 | Full Set Bonus: Cold Thumb (0/8) Allows the wearer to shoot unlimited snowballs when using Frosty the Snow Cannon. / Each piece grants a 5% bonus gift chance for every present you earn from the Gift Attack minigame! / All stats of this armor piece are doubl... | - | - |
| Snow Gloves | SNOW_GLOVES | R | Gloves | HP +5, Int +5, Spd +2 | Full Set Bonus: Cold Thumb (0/8) Allows the wearer to shoot unlimited snowballs when using Frosty the Snow Cannon. / Each piece grants a 5% bonus gift chance for every present you earn from the Gift Attack minigame! / All stats of this armor piece are doubl... | - | - |
| Snow Necklace | SNOW_NECKLACE | R | Necklace | HP +5, Int +5, Spd +2 | Full Set Bonus: Cold Thumb (0/8) Allows the wearer to shoot unlimited snowballs when using Frosty the Snow Cannon. / Each piece grants a 5% bonus gift chance for every present you earn from the Gift Attack minigame! / All stats of this armor piece are doubl... | - | - |
| Sponge Belt | SPONGE_BELT | R | Belt | FishSpd +2, TrophyCh +1 | - | - | craft: 64x SPONGE, 6x ENCHANTED_SPONGE |
| Titanium Belt | TITANIUM_BELT | R | Belt | MinSpd +30, MinFort +10 | Grants +10 Mining Fortune while mining Mithril and Titanium. / Full Set Bonus: True Dwarf (0/4) Grants +50 Mining Speed and +40 Mining Fortune while in the Dwarven Mines. | HotM 3 | forge (4.5h): REFINED_TITANIUM:1.0, REFINED_MINERAL:16.0, MITHRIL_BELT:1.0 |
| Titanium Cloak | TITANIUM_CLOAK | R | Cloak | MinSpd +30, MinFort +10 | Grants +10 Mining Fortune while mining Mithril and Titanium. / Full Set Bonus: True Dwarf (0/4) Grants +50 Mining Speed and +40 Mining Fortune while in the Dwarven Mines. | HotM 3 | forge (4.5h): MITHRIL_CLOAK:1.0, REFINED_TITANIUM:1.0, REFINED_MINERAL:16.0 |
| Titanium Gauntlet | TITANIUM_GAUNTLET | R | Gloves | MinSpd +30, MinFort +10 | Grants +10 Mining Fortune while mining Mithril and Titanium. / Full Set Bonus: True Dwarf (0/4) Grants +50 Mining Speed and +40 Mining Fortune while in the Dwarven Mines. | HotM 3 | forge (4.5h): REFINED_MINERAL:16.0, MITHRIL_GAUNTLET:1.0, REFINED_TITANIUM:1.0 |
| Titanium Necklace | TITANIUM_NECKLACE | R | Necklace | MinSpd +30, MinFort +10 | Grants +10 Mining Fortune while mining Mithril and Titanium. / Full Set Bonus: True Dwarf (0/4) Grants +50 Mining Speed and +40 Mining Fortune while in the Dwarven Mines. | HotM 3 | forge (4.5h): REFINED_TITANIUM:1.0, MITHRIL_NECKLACE:1.0, REFINED_MINERAL:16.0 |
| Tropical Cloak | CLOWNFISH_CLOAK | R | Cloak | FishSpd +2, TrophyCh +1 | - | - | craft: 3x ENCHANTED_CLOWNFISH, 128x RAW_FISH-2 |
| Adaptive Belt | ADAPTIVE_BELT | E | Belt | Str +5, HP +10, Def +5 | Grants additional bonuses based on your selected dungeon class! / Berserk: +10 Strength Healer: +10 Health, +5 Mending Mage: +25 Intelligence Tank: +5 Health, +10 Defense Archer: +2 Crit Chance, +5 Crit Damage | F2 done | - |
| Adaptive Belt | STARRED_ADAPTIVE_BELT | E | Belt | Str +10, HP +15, Def +10 | Grants additional bonuses based on your selected dungeon class! / Berserk: +10 Strength Healer: +10 Health, +5 Mending Mage: +25 Intelligence Tank: +5 Health, +10 Defense Archer: +2 Crit Chance, +5 Crit Damage | F2 done | craft: 8x SCARF_FRAGMENT, 1x ADAPTIVE_BELT |
| Amber Necklace | AMBER_NECKLACE | E | Necklace | MinSpd +30, MinFort +10 | Grants +30 Mining Fortune while mining Amber. / Full Set Bonus: Gemstone Gatherer (0/4) Grants +25 Mining Fortune while in the Crystal Hollows. | HotM 5 | forge (24h): GLOSSY_GEMSTONE:32.0, FLAWLESS_AMBER_GEM:2.0 |
| Amethyst Gauntlet | AMETHYST_GAUNTLET | E | Gloves | MinSpd +30, MinFort +10 | Grants +30 Mining Fortune while mining Amethyst. / Full Set Bonus: Gemstone Gatherer (0/4) Grants +25 Mining Fortune while in the Crystal Hollows. | HotM 5 | forge (24h): GLOSSY_GEMSTONE:32.0, FLAWLESS_AMETHYST_GEM:2.0 |
| Ancient Cloak | ANCIENT_CLOAK | E | Cloak | HP +15, MinFort +10 | Health-consuming abilities deal 10% more damage. | Combat 22 | craft: 6x ENCHANTED_RED_SAND_CUBE, 64x WITHER_SOUL |
| Blossom Belt | BLOSSOM_BELT | E | Belt | FarmFort +7, HP +15 | Piece Bonus: Florist Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1.5 (0/1) | Garden 7, Farming 20 | - |
| Blossom Bracelet | BLOSSOM_BRACELET | E | Bracelet | FarmFort +7, HP +15 | Piece Bonus: Florist Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1.5 (0/1) | Garden 6, Farming 20 | - |
| Blossom Cloak | BLOSSOM_CLOAK | E | Cloak | FarmFort +7, HP +15 | Piece Bonus: Florist Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1.5 (0/1) | Garden 9, Farming 20 | - |
| Blossom Necklace | BLOSSOM_NECKLACE | E | Necklace | FarmFort +7, HP +15 | Piece Bonus: Florist Complete Garden Visitor Offers to gain  Farming Fortune. / Piece Bonus: +0 Next Upgrade: +1.5 (0/1) | Garden 8, Farming 20 | - |
| Bone Necklace | STARRED_BONE_NECKLACE | E | Necklace | Def +45, CC +5 | Ability: Gladiator's Will Gain +3 Defense for each enemy within 10 blocks up to +30 Defense. Range increases to 30 blocks and the cap increases to +60 Defense when you play as a Tank in dungeons. / Increases the range of your Diversion passive by 15 blocks. | F4 done | craft: 8x THORN_FRAGMENT, 1x BONE_NECKLACE |
| Bone Necklace | BONE_NECKLACE | E | Necklace | Def +35, CC +3 | Ability: Gladiator's Will Gain +3 Defense for each enemy within 10 blocks up to +30 Defense. Range increases to 30 blocks and the cap increases to +60 Defense when you play as a Tank in dungeons. / Increases the range of your Diversion passive by 15 blocks. | F4 done | craft: 96x SOUL_STRING, 2x SPIRIT_BONE, 1x SPIRIT_WING |
| Brown Belt | DOJO_BROWN_BELT | E | Belt | HP +30, Def +20 | Reduces damage taken by 5%. | - | - |
| Challenger's Belt | CHALLENGER_BELT | E | Belt | Str +5, HP +20, Def +10 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. | - | craft: 32x ENCHANTED_GOLD, 4x GRIFFIN_FEATHER, 64x ANCIENT_CLAW |
| Challenger's Bracelet | CHALLENGER_BRACELET | E | Bracelet | Str +5, HP +20, Def +10 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. | - | craft: 32x ENCHANTED_GOLD, 4x GRIFFIN_FEATHER, 64x ANCIENT_CLAW |
| Challenger's Cloak | CHALLENGER_CLOAK | E | Cloak | Str +5, HP +20, Def +10 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. | - | craft: 32x ENCHANTED_GOLD, 64x ANCIENT_CLAW, 4x GRIFFIN_FEATHER |
| Challenger's Necklace | CHALLENGER_NECKLACE | E | Necklace | Str +5, HP +20, Def +10 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. | - | craft: 4x GRIFFIN_FEATHER, 64x ANCIENT_CLAW, 32x ENCHANTED_GOLD |
| Delirium Necklace | DELIRIUM_NECKLACE | E | Necklace | HP +1, Def +1, Str +1, Int +1, CD +1, Spd +1, Fer +1, MF +1, SCC +1 | Increase the fire damage you receive by 8% per +1 Strength. | Combat 22 | craft: 20x GAZING_PEARL, 5x REKINDLED_EMBER_FRAGMENT |
| Demonslayer Gauntlet | DEMONLORD_GAUNTLET | E | Gloves | Def +20, HP +40, CD +25, TrueDef +20 | Deal 1.15x damage against  Infernal Mobs. | Blaze Slayer 4 | craft: 64x ENCHANTED_GLOWSTONE, 96x MOLTEN_POWDER, 192x ENCHANTED_RABBIT_HIDE |
| Destruction Cloak | DESTRUCTION_CLOAK | E | Cloak | HP +60, Str +15, CD +15, MF +1, ForWis +1 | Heal 15❤ every 1s while worn. | Blaze Slayer 5 | craft: 3x WOOD_SINGULARITY, 192x MOLTEN_POWDER, 8x ENCHANTED_SULPHUR_CUBE |
| Dragonfade Cloak | DRAGONFADE_CLOAK | E | Cloak | HP +30, Def +5, Str +5 | All stats on this piece of equipment are multiplied by 3x while on The End! / Multiplies damage dealt to Dragons by 1.01x. | Combat 15 | craft: 3x RITUAL_RESIDUE, 64x MITE_GEL, 32x ABSOLUTE_ENDER_PEARL |
| Dragonfuse Glove | DRAGONFUSE_GLOVE | E | Gloves | HP +30, Def +5, CD +5 | While equipped, upgrades the Aspect of the Dragons with: +35 Damage +50 Strength Very reduced ability knockback | Combat 15 | craft: 4x RITUAL_RESIDUE, 72x MITE_GEL, 48x ABSOLUTE_ENDER_PEARL |
| Dwarven Handwarmers | DWARVEN_HANDWARMERS | E | Gloves | MinSpd +45, MinFort +30, ColdRes +5 | - | HotM 8 | forge (4h): FLAWLESS_JADE_GEM:1.0, UMBER_PLATE:1.0, FLAWLESS_AMBER_GEM:1.0, TUNGSTEN_PLATE:1.0 |
| Ender Belt | ENDER_BELT | E | Belt | HP +15, Def +10 | All stats on this piece of equipment are multiplied by 2x while on the End Island! | Combat 12 | - |
| Ender Cloak | ENDER_CLOAK | E | Cloak | HP +15, Def +10 | All stats on this piece of equipment are multiplied by 2x while on the End Island! | Combat 12 | - |
| Ender Gauntlet | ENDER_GAUNTLET | E | Gloves | HP +15, Def +10 | All stats on this piece of equipment are multiplied by 2x while on the End Island! | Combat 12 | - |
| Ender Necklace | ENDER_NECKLACE | E | Necklace | HP +15, Def +10 | All stats on this piece of equipment are multiplied by 2x while on the End Island! | Combat 12 | - |
| Enigma Cloak | ENIGMA_CLOAK | E | Cloak | - | Find Enigma Souls across the Rift Dimension. / Each adds +20ф Rift Time to this item. / Every 4 Souls, go back to Enigma for more upgrades. / You found: 0/42 Souls | - | - |
| Finwave Belt | FINWAVE_BELT | E | Belt | HP +18, HPRegen +1, FishSpd +10 | - | - | craft: 12x TIGER_SHARK_TOOTH, 2x GREAT_WHITE_TOOTH_MEAL, 1x ICHTHYIC_BELT |
| Finwave Cloak | FINWAVE_CLOAK | E | Cloak | HP +15, HPRegen +1, FishSpd +10 | - | - | craft: 2x GREAT_WHITE_TOOTH_MEAL, 1x ICHTHYIC_CLOAK, 24x TIGER_SHARK_TOOTH |
| Finwave Gloves | FINWAVE_GLOVES | E | Gloves | HP +12, HPRegen +1, FishSpd +10 | - | - | craft: 2x GREAT_WHITE_TOOTH_MEAL, 1x ICHTHYIC_GLOVES, 18x TIGER_SHARK_TOOTH |
| Flaming Fist | FLAMING_FIST | E | Gloves | Str +10, HP +30, Def +20, CD +10, Fer +1 | When equipped, melee attacks fire three flaming fists dealing 10,000 fire damage over time. | Combat 22 | - |
| Gauntlet of Contagion | GAUNTLET_OF_CONTAGION | E | Gloves | HP +20, Def +10, Str +15 | Ability: Contaminate Killing an enemy causes an explosion dealing 10% of their total  Health as damage to all enemies within 2 blocks. Enemies in the blast radius will also be contaminated causing them to explode on death. | Combat 22 | craft: 3x ENCHANTED_MYCELIUM_CUBE, 36x LEATHER_CLOTH |
| Great Spook Belt | GREAT_SPOOK_BELT | E | Belt | Fear +5 | Obtained during the Alchemist experiments! | - | - |
| Great Spook Cloak | GREAT_SPOOK_CLOAK | E | Cloak | Fear +5 | Obtained during the Alchemist experiments! | - | - |
| Great Spook Gloves | GREAT_SPOOK_GLOVES | E | Gloves | Fear +5 | Obtained during the Alchemist experiments! | - | - |
| Great Spook Necklace | GREAT_SPOOK_NECKLACE | E | Necklace | Fear +5 | Obtained during the Alchemist experiments! | - | - |
| Honeycomb Necklace | HONEYCOMB_NECKLACE | E | Necklace | ForFort +25, Sweep +5 | - | Foraging 30 | craft: 4x HELIXIS, 2x ENCHANTED_HONEYCOMB_BLOCK, 1x MANGROVE_LOCKET, 1x VIAL_OF_SPRING_WATER |
| Implosion Belt | IMPLOSION_BELT | E | Belt | Def +70 | Ability: Consolidated Increases all explosion damage dealt by 25%. | Combat 22 | craft: 128x MATCH_STICKS, 4x ENCHANTED_SULPHUR_CUBE |
| Jade Belt | JADE_BELT | E | Belt | MinSpd +30, MinFort +10 | Grants +30 Mining Fortune while mining Jade. / Full Set Bonus: Gemstone Gatherer (0/4) Grants +25 Mining Fortune while in the Crystal Hollows. | HotM 5 | forge (24h): GLOSSY_GEMSTONE:32.0, FLAWLESS_JADE_GEM:2.0 |
| Lava Shell Necklace | LAVA_SHELL_NECKLACE | E | Necklace | HP +100, Int +50, Def +30 | Sets your  Health Regen and  Mending to 0, and you can no longer receive healing! | Combat 22 | craft: 8x LAVA_SHELL, 5x GAZING_PEARL |
| Molten Belt | MOLTEN_BELT | E | Belt | HP +30, Def +20, Str +20 | Tiered Bonus: Molten Core (0/4) Gain 5% damage resistance while fighting Kuudra. | Combat 22 | - |
| Molten Bracelet | MOLTEN_BRACELET | E | Bracelet | HP +30, Def +20, Str +20 | Tiered Bonus: Molten Core (0/4) Gain 5% damage resistance while fighting Kuudra. | Combat 22 | - |
| Molten Cloak | MOLTEN_CLOAK | E | Cloak | HP +30, Def +20, Str +20 | Tiered Bonus: Molten Core (0/4) Gain 5% damage resistance while fighting Kuudra. | Combat 22 | - |
| Molten Necklace | MOLTEN_NECKLACE | E | Necklace | HP +30, Def +20, Str +20 | Tiered Bonus: Molten Core (0/4) Gain 5% damage resistance while fighting Kuudra. | Combat 22 | - |
| Pelt Belt | PELT_BELT | E | Belt | FarmWis +1 | Gain +30 Speed while in the Mushroom Desert. Earn +1 pelt from tracker quests. | - | - |
| Pest Vest | PEST_VEST | E | Cloak | PestCh +10 | Decreases the spawn cooldown of  Pests by 15%. | - | - |
| Sapphire Cloak | SAPPHIRE_CLOAK | E | Cloak | MinSpd +30, MinFort +10 | Grants +30 Mining Fortune while mining Sapphire. / Full Set Bonus: Gemstone Gatherer (0/4) Grants +25 Mining Fortune while in the Crystal Hollows. | HotM 5 | forge (24h): FLAWLESS_SAPPHIRE_GEM:2.0, GLOSSY_GEMSTONE:32.0 |
| Scourge Cloak | SCOURGE_CLOAK | E | Cloak | HP +25, CD +20 | Fire damage taken is increased by 10,000%. Increases your next hit by 20% of the last fire damage you took. | Combat 22 | craft: 20x MUTATED_BLAZE_ASHES, 20x COMPACT_OOZE, 5x REKINDLED_EMBER_FRAGMENT |
| Shadow Assassin Cloak | SHADOW_ASSASSIN_CLOAK | E | Cloak | Str +20, Spd +3 | Piece Bonus: Bloodrush On teleport: Your next melee hit within 5s deals 10% more damage. Cooldown: 3s | F5 done | - |
| Shriveled Bracelet | SHRIVELED_BRACELET | E | Gloves | CD +15, Str +10 | Grants +5☯ Combat Wisdom and +10 Magic Find on  Arthropod mobs. | Spider Slayer 7 | craft: 128x SOUL_STRING, 64x TARANTULA_SILK, 1x SHRIVELED_WASP |
| Soulweaver Gloves | SOULWEAVER_GLOVES | E | Gloves | Str +10, CD +10 | While in The Catacombs, summon a Haunted Skull that slowly revolves around you every 15 seconds or when you kill a mob with melee damage. / When an enemy is hit by a Haunted Skull, they are stunned for 2 seconds and deal 5% less damage. | F6 done | - |
| Synthesizer v1 | SYNTHESIZER_V1 | E | Necklace | - | Can synthesize X items increasing the stats of this necklace. / Current Bonuses: 0/30  Health | Combat 22 | craft: 320x X |
| Synthesizer v2 | SYNTHESIZER_V2 | E | Necklace | - | Can synthesize X and Y items increasing the stats of this necklace. / Current Bonuses: 0/30  Health 0/30  Defense | Combat 22 | craft: 512x Y, 1x SYNTHESIZER_V1 |
| Synthesizer v3 | SYNTHESIZER_V3 | E | Necklace | - | Can synthesize X, Y, and Z items increasing the stats of this necklace. / Current Bonuses: 0/40  Health 0/40  Defense 0/60  Intelligence | Combat 22 | craft: 512x Z, 1x SYNTHESIZER_V2 |
| Tera Shell Necklace | TERA_SHELL_NECKLACE | E | Necklace | HP +80, Def +30, Int +20 | You can no longer catch Trophies while wearing this Necklace. / Telling Trophy Fish to pound sand somehow elicits their deepest, darkest fears. | Combat 22 | craft: 4x ENCHANTED_RED_SAND_CUBE, 4x ENCHANTED_MYCELIUM_CUBE, 5x GAZING_PEARL |
| Thunderbolt Necklace | THUNDERBOLT_NECKLACE | E | Necklace | HP +40, Def +30, SCC +1.5 | Tiered Bonus: Static Charge (0/5) Gains 1 static charge every 30 seconds. Hitting a target will cause a discharge, adding 15% damage to that hit for each charge collected. / Maximum Charge Capacity: 2 / Damage taken from  Magmatic mobs is reduced by 5% and... | Fishing 36 | craft: 5x THUNDER_SHARDS |
| Torrhus Belt | TORRHUS_BELT | E | Belt | ForFort +10, Sweep +2 | Grants +10% more points towards Starlyn Contests. | Foraging 24 | craft: 2x HELIXIS, 16x BOTTLED_MISCHIEF, 40x ENCHANTED_RUBY_VEILSHROOM, 1x MANGROVE_VINE |
| Vanquished Blaze Belt | VANQUISHED_BLAZE_BELT | E | Belt | Def +20, Str +5 | Ability: Blazing Palisade Gain a 20% chance to reflect Fireballs. / Piece Bonus: Blaze Consumer Kill Blazes to accumulate  Strength. Piece Bonus: +0 Next Upgrade: +1 (0/1,000) | Combat 22 | craft: 2x ENCHANTED_BLAZE_ROD, 1x BLAZE_BELT, 3x CORRUPTED_NETHER_STAR |
| Vanquished Ghast Cloak | VANQUISHED_GHAST_CLOAK | E | Cloak | HP +10, Def +10 | Ability: Blazing Restoration Restores +15 Health every second while worn. / Piece Bonus: Ghast Blaster Kill Ghasts to accumulate  Defense. Piece Bonus: +0 Next Upgrade: +1 (0/1,000) | Combat 22 | craft: 3x CORRUPTED_NETHER_STAR, 256x SILVER_FANG, 1x GHAST_CLOAK |
| Vanquished Glowstone Gauntlet | VANQUISHED_GLOWSTONE_GAUNTLET | E | Gloves | Int +15, MinFort +7 | Piece Bonus: Glowing Mine Glowstone to accumulate  Intelligence. Piece Bonus: +0 Next Upgrade: +1 (0/1,000) | Combat 22 | craft: 3x CORRUPTED_NETHER_STAR, 4x ENCHANTED_GLOWSTONE, 1x GLOWSTONE_GAUNTLET |
| Vanquished Magma Necklace | VANQUISHED_MAGMA_NECKLACE | E | Necklace | HP +20 | Ability: Cube Fanatic Gain 20% more Magma Cream when killing Magma Cubes. / Piece Bonus: Magma Cube Absorber Kill Magma Cubes to accumulate  Crit Damage. Piece Bonus: +0 Next Upgrade: +1 (0/1,000) | Combat 22 | craft: 256x ENCHANTED_MAGMA_CREAM, 1x MAGMA_NECKLACE, 3x CORRUPTED_NETHER_STAR |
| Veilshroom Bracelet | VEILSHROOM_BRACELET | E | Bracelet | ForFort +25, Sweep +5 | - | Foraging 30 | craft: 4x HELIXIS, 2x VEILSHROOM_BUNCH, 1x MANGROVE_GRIPPERS, 1x VIAL_OF_SPRING_WATER |
| Vermin Belt | VERMIN_BELT | E | Belt | - | Monster melee attacks reduce 1 less  Rift Time. | - | - |
| %%light_purple%%Rift Necklace | RIFT_NECKLACE_OUTSIDE | L | Necklace | - | A very powerful piece of equipment originating from the Rift Dimension. / Becomes more powerful as you fill the Rift Gallery. Secured: 8/8 Timecharms | - | - |
| Annihilation Cloak | ANNIHILATION_CLOAK | L | Cloak | HP +80, Str +20, CD +20, MF +2, ForWis +2 | Heal 25❤ every 1s while worn. | Blaze Slayer 7 | craft: 2x WOOD_SINGULARITY, 1x DESTRUCTION_CLOAK, 256x MOLTEN_POWDER, 8x INFERNO_VERTEX, 16x ENCHANTED_SULPHUR_CUBE |
| Black Belt | DOJO_BLACK_BELT | L | Belt | HP +35, Def +25 | Reduces damage taken by 6%. | - | - |
| Gemstone Gauntlet | GEMSTONE_GAUNTLET | L | Gauntlet | BP +8, Dmg +300, MinSpd +800, Str +50 | Breaking Power 8 / Ability: Reduced To Atoms On Mining Islands, mobs hit by this Gauntlet will drop the same Gemstones as your filled Gemstone Slots. Cooldown: 2s / Ability: Kinetic Killing mobs on Mining Islands reduces your Forge Timers by 0s. (+0.5s per ... | HotM 6 | craft: 8x FLAWLESS_RUBY_GEM, 16x ENCHANTED_GOLD_BLOCK |
| Gillsplash Belt | GILLSPLASH_BELT | L | Belt | HP +30, HPRegen +2, FishSpd +14 | - | - | craft: 2x MAGMA_FISH_DIAMOND, 6x LAVA_SHELL, 1x FINWAVE_BELT |
| Gillsplash Cloak | GILLSPLASH_CLOAK | L | Cloak | HP +25, HPRegen +2, FishSpd +14 | - | - | craft: 2x MAGMA_FISH_DIAMOND, 1x FINWAVE_CLOAK, 4x LAVA_SHELL |
| Gillsplash Gloves | GILLSPLASH_GLOVES | L | Gloves | HP +20, HPRegen +2, FishSpd +14 | - | - | craft: 2x MAGMA_FISH_DIAMOND, 1x FINWAVE_GLOVES, 3x LAVA_SHELL |
| Golden Belt | GOLDEN_BELT | L | Belt | - | Ability: Gravity Falls Increases your total  Rift Time by 5% but it goes down 3% faster. / Monster melee attacks reduce 1 less  Rift Time. | - | - |
| Golden Fleece | GOLDEN_FLEECE | L | Cloak | Str +20, CD +15, TrueDef +10 | Grants +5 Magic Find on  Mythological mobs. / Ability: Empyrean Intuition Grants +15 Tracking on Griffin Burrows. | - | craft: 128x ENCHANTED_WOOL, 1x SHIMMERING_WOOL, 256x ENCHANTED_GOLD_BLOCK, 1x BRAIDED_GRIFFIN_FEATHER |
| Magma Lord Necklace | MAGMA_LORD_GAUNTLET | L | Necklace | Str +10, HP +45, Fer +2, SCC +2.5 | Tiered Bonus: Fireproof (0/2) Provides immunity to Crimson Isle Lava. / Tiered Bonus: Lord's Blessing (0/5) Grants +1 Sea Creature Chance. Grants +20 Magic Find on  Magmatic mobs. / Damage taken from  Magmatic mobs is reduced by 15% and damage dealt is ... | Fishing 45 | craft: 6x MAGMA_LORD_FRAGMENT |
| Manticore Claw | MANTICORE_CLAW | L | Gloves | Str +20, CD +10, AtkSpd +2.5, Fer +4 | Ability: Chimeric Core Copies 10% of your active pet's Combat stats. | - | craft: 320x ENCHANTED_ANCIENT_CLAW, 1x MANTI_CORE, 64x ENCHANTED_GOLD_BLOCK |
| Mythos Belt | MYTHOS_BELT | L | Belt | Str +15, HP +25, Def +15 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. / Tiered Bonus: Mythological Greed (0/4) Grants +0% more Coins ... | - | craft: 32x ENCHANTED_ANCIENT_CLAW, 16x ENCHANTED_GOLD_BLOCK, 1x CHALLENGER_BELT, 64x GRIFFIN_FEATHER, 4x MYTHOS_FRAGMENT |
| Mythos Bracelet | MYTHOS_BRACELET | L | Bracelet | Str +15, HP +25, Def +15 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. / Tiered Bonus: Mythological Greed (0/4) Grants +0% more Coins ... | - | craft: 16x ENCHANTED_GOLD_BLOCK, 4x MYTHOS_FRAGMENT, 32x ENCHANTED_ANCIENT_CLAW, 64x GRIFFIN_FEATHER, 1x CHALLENGER_BRACELET |
| Mythos Cloak | MYTHOS_CLOAK | L | Cloak | Str +15, HP +25, Def +15 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. / Tiered Bonus: Mythological Greed (0/4) Grants +0% more Coins ... | - | craft: 16x ENCHANTED_GOLD_BLOCK, 4x MYTHOS_FRAGMENT, 32x ENCHANTED_ANCIENT_CLAW, 1x CHALLENGER_CLOAK, 64x GRIFFIN_FEATHER |
| Mythos Necklace | MYTHOS_NECKLACE | L | Necklace | Str +15, HP +25, Def +15 | Ability: Mythos' Might Grants 2x this equipment's stats while in The Hub during Diana's Mythological Ritual. / Tiered Bonus: Unearthed (0/8) Reduces damage taken from  Mythological mobs by 0%. / Tiered Bonus: Mythological Greed (0/4) Grants +0% more Coins ... | - | craft: 16x ENCHANTED_GOLD_BLOCK, 32x ENCHANTED_ANCIENT_CLAW, 4x MYTHOS_FRAGMENT, 64x GRIFFIN_FEATHER, 1x CHALLENGER_NECKLACE |
| Pendant of Divan | DIVAN_PENDANT | L | Necklace | MinSpd +100, MinFort +25, ColdRes +10 | Tiered Bonus: Legacy (0/1) Grants +0.25 Pristine and +2.5 Gemstone Spread while inside the Glacite Mineshafts. / An item treasured by the greatest Dwarf who ever lived. Why would he leave it behind? | HotM 10 | forge (168h): PERFECT_PLATE:1.0, DIVAN_FRAGMENT:10.0, SHATTERED_PENDANT:1.0 |
| Shadow Assassin Cloak | STARRED_SHADOW_ASSASSIN_CLOAK | L | Cloak | Str +25, Spd +5 | Piece Bonus: Bloodrush On teleport: Your next melee hit within 5s deals 10% more damage. Cooldown: 3s | F5 done | craft: 8x LIVID_FRAGMENT, 1x SHADOW_ASSASSIN_CLOAK |
| Shen's Ringalia | SHENS_RINGALIA | L | Bracelet | - | No matter the place or when, anyone marked by Shen will gain control. | - | - |
| The Primordial | THE_PRIMORDIAL | L | Belt | Str +15, CD +20 | Ability: Forced Metamorphosis When killing a Slayer Boss or Slayer Miniboss, have a 10% chance to cocoon them! Cocooned enemies will quickly hatch, and can be killed again. / An eye torn from the Primordial Brood sits dead center, patiently observing. Are y... | Spider Slayer 8 | craft: 192x SOUL_STRING, 128x TARANTULA_SILK, 2x SHRIVELED_WASP, 1x PRIMORDIAL_EYE |
| Zorro's Cape | ZORROS_CAPE | L | Cloak | FarmFort +10, FarmWis +1, Str +10, Fer +2 | The stats of this Cape double during Jacob's Farming Contest. Additionally, you have a 20% chance to obtain an extra medal from contests. / Not all Rabbits wear capes. | Easter_Rabbit | - |
| %%light_purple%%Rift Necklace | RIFT_NECKLACE_INSIDE | M | Necklace | - | Hearts: +1 Rift Damage: +1 Mana Regen: +25% / The most powerful necklace ever created by Elise MDCCCII. / Becomes more powerful as you fill the Rift Gallery. Secured: 8/8 Timecharms | - | - |
| Sparkling Amulet | SPARKLING_AMULET | S | Necklace | - | Increases your chance of finding SPARKLING  Critters by 2x. Sparkling Amulet bonuses do not stack. | - | - |

## Appendix D — All pet items

| Item | ID | Tier | Stats | Effect | Req | Source/recipe |
|---|---|---|---|---|---|---|
| All Skills Exp Boost | PET_ITEM_ALL_SKILLS_BOOST_UNCOMMON | U | - | - | - | - |
| All Skills Exp Super-Boost | ALL_SKILLS_SUPER_BOOST | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +20% pet exp for all skills. / Right-click on your summoned pet to give i... | - | craft: 18x ENCHANTED_GOLD_BLOCK, 1x PET_ITEM_ALL_SKILLS_BOOST_COMMON, 1x ZOG_ANVIL |
| Big Teeth | PET_ITEM_BIG_TEETH_UNCOMMON | U | - | - | - | - |
| Bigger Teeth | BIGGER_TEETH | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +10 Crit Chance. / Right-click on your summoned pet to give it this item! | - | craft: 18x ENCHANTED_GOLD_BLOCK, 1x PET_ITEM_BIG_TEETH_COMMON, 1x ZOG_ANVIL |
| Combat Exp Boost | PET_ITEM_COMBAT_SKILL_BOOST_UNCOMMON | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +30% pet exp for Combat. / Right-click on your summoned pet to give it th... | - | - |
| Farming Exp Boost | PET_ITEM_FARMING_SKILL_BOOST_UNCOMMON | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +30% pet exp for Farming. / Right-click on your summoned pet to give it t... | - | - |
| Fishing Exp Boost | PET_ITEM_FISHING_SKILL_BOOST_UNCOMMON | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +30% pet exp for Fishing. / Right-click on your summoned pet to give it t... | - | - |
| Foraging Exp Boost | PET_ITEM_FORAGING_SKILL_BOOST_UNCOMMON | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +30% pet exp for Foraging. / Right-click on your summoned pet to give it ... | - | - |
| Gold Claws | GOLD_CLAWS | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Increases the pet's  Crit Damage by 50% and  Crit Chance by 50%. / Right-clic... | - | craft: 18x ENCHANTED_GOLD_BLOCK, 1x PET_ITEM_IRON_CLAWS_COMMON, 1x ZOG_ANVIL |
| Hardened Scales | PET_ITEM_HARDENED_SCALES_UNCOMMON | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +25 Defense. / Right-click on your summoned pet to give it this item! | - | - |
| Iron Claws | PET_ITEM_IRON_CLAWS_UNCOMMON | U | - | - | - | - |
| Mining Exp Boost | PET_ITEM_MINING_SKILL_BOOST_UNCOMMON | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +30% pet exp for Mining. / Right-click on your summoned pet to give it th... | - | - |
| Saddle | PET_ITEM_SADDLE | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +30 Speed. / Right-click on your summoned pet to give it this item! | - | craft: 24x ENCHANTED_LEATHER, 8x ENCHANTED_IRON |
| Sharpened Claws | PET_ITEM_SHARPENED_CLAWS_UNCOMMON | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +15 Crit Damage. / Right-click on your summoned pet to give it this item! | - | - |
| Small Sloth Claw | SMALL_SLOTH_CLAW | U | - | Use at Kat to upgrade Sloth Pets from Common to Uncommon. | - | - |
| Spooky Cupcake | PET_ITEM_SPOOKY_CUPCAKE | U | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +30 Strength and +20 Speed. / Right-click on your summoned pet to give... | - | - |
| All Skills Exp Boost | PET_ITEM_ALL_SKILLS_BOOST_RARE | R | - | - | - | - |
| Big Teeth | PET_ITEM_BIG_TEETH_RARE | R | - | - | - | - |
| Bubblegum | PET_ITEM_BUBBLEGUM | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Your pet fuses its power with placed Deployables to give them 2x duration. / Ri... | - | - |
| Combat Exp Boost | PET_ITEM_COMBAT_SKILL_BOOST_RARE | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +40% pet exp for Combat. / Right-click on your summoned pet to give it th... | - | - |
| Dead Cat Food | DEAD_CAT_FOOD | R | - | Use on a mythological cat to transform this item. / Pss-pss-pss-pss Good cats only! | - | - |
| Edible Seaweed | EDIBLE_SEAWEED | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +10 Fishing Speed. / Right-click on your summoned pet to give it this i... | - | - |
| Farming Exp Boost | PET_ITEM_FARMING_SKILL_BOOST_RARE | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +40% pet exp for Farming. / Right-click on your summoned pet to give it t... | - | - |
| Fishing Exp Boost | PET_ITEM_FISHING_SKILL_BOOST_RARE | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +40% pet exp for Fishing. / Right-click on your summoned pet to give it t... | - | - |
| Flying Pig | PET_ITEM_FLYING_PIG | R | - | Consumed on use / This pet item may only be used on a Pig pet. / Grants +40 Potato Fortune and +20 Speed. / Right-click on your summoned pet to give it this item! | - | - |
| Foraging Exp Boost | PET_ITEM_FORAGING_SKILL_BOOST_RARE | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +40% pet exp for Foraging. / Right-click on your summoned pet to give it ... | - | - |
| Grandma's Knitting Needle | GRANDMAS_KNITTING_NEEDLE | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +5%  Magic Find when getting a combo of 50 or above. / Right-click on y... | - | - |
| Hardened Scales | PET_ITEM_HARDENED_SCALES_RARE | R | - | - | - | - |
| Iron Claws | PET_ITEM_IRON_CLAWS_RARE | R | - | - | - | - |
| Medium Sloth Claw | MEDIUM_SLOTH_CLAW | R | - | Use at Kat to upgrade Sloth Pets from Uncommon to Rare. | - | - |
| Mining Exp Boost | PET_ITEM_MINING_SKILL_BOOST_RARE | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +40% pet exp for Mining. / Right-click on your summoned pet to give it th... | - | - |
| Reinforced Scales | REINFORCED_SCALES | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +40 Defense. / Right-click on your summoned pet to give it this item! | - | craft: 18x ENCHANTED_GOLD_BLOCK, 1x PET_ITEM_HARDENED_SCALES_UNCOMMON, 1x ZOG_ANVIL |
| Rubber Snorkel | RUBBER_SNORKEL | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants 5 Respiration, doubled while on Foraging Islands. / Right-click on your... | - | - |
| Serrated Claws | SERRATED_CLAWS | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +25 Crit Damage. / Right-click on your summoned pet to give it this item! | - | craft: 18x ENCHANTED_GOLD_BLOCK, 1x PET_ITEM_SHARPENED_CLAWS_UNCOMMON, 1x ZOG_ANVIL |
| Sharpened Claws | PET_ITEM_SHARPENED_CLAWS_RARE | R | - | - | - | - |
| Titanium Minecart | PET_ITEM_TITANIUM_MINECART | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +33.3 Mining Fortune while mining Titanium Ore. / Right-click on your s... | - | - |
| Winding Ivy | WINDING_IVY | R | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +10 Sweep. / Right-click on your summoned pet to give it this item! | - | - |
| All Skills Exp Boost | PET_ITEM_ALL_SKILLS_BOOST_EPIC | E | - | - | - | - |
| Antique Remedies | ANTIQUE_REMEDIES | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Increases the pet's  Strength by 80%. / Right-click on your summoned pet to gi... | - | - |
| Barrel of Riches | BARREL_OF_RICHES | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Trophies are +10% more likely to be GOLD. / Right-click on your summoned pet to... | - | craft: 7x MANGCORE, 1x ENCHANTED_GOLD_BLOCK, 1x LOTUS_WATER_ORB |
| Big Teeth | PET_ITEM_BIG_TEETH_EPIC | E | - | - | - | - |
| Brown Bandana | BROWN_BANDANA | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gain +0.2 Bonus Pest Chance for every Pest Bestiary tier unlocked. / Right-cli... | - | - |
| Combat Exp Boost | PET_ITEM_COMBAT_SKILL_BOOST_EPIC | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +50% pet exp for Combat. / Right-click on your summoned pet to give it th... | - | - |
| Cretan Urn | CRETAN_URN | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +5 Pet Luck. / Right-click on your summoned pet to give it this item! | - | - |
| Crochet Tiger Plushie | CROCHET_TIGER_PLUSHIE | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +35 Attack Speed. / Right-click on your summoned pet to give it this item! | - | - |
| Dwarf Turtle Shelmet | DWARF_TURTLE_SHELMET | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +10 True Defense and makes the pet's owner immune to knockback. / Right... | - | - |
| Exp Share | PET_ITEM_EXP_SHARE | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / While equipped in an Exp Share slot this pet gains +15% Exp Share rate. / Right... | - | craft: 72x ENCHANTED_GOLD, 1x PET_ITEM_EXP_SHARE_DROP |
| Farming Exp Boost | PET_ITEM_FARMING_SKILL_BOOST_EPIC | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +50% pet exp for Farming. / Right-click on your summoned pet to give it t... | - | trade |
| Fishing Exp Boost | PET_ITEM_FISHING_SKILL_BOOST_EPIC | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +50% pet exp for Fishing. / Right-click on your summoned pet to give it t... | - | - |
| Foraging Exp Boost | PET_ITEM_FORAGING_SKILL_BOOST_EPIC | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +50% pet exp for Foraging. / Right-click on your summoned pet to give it ... | - | - |
| Four-Eyed Fish | FOUR_EYED_FISH | E | - | Consumed on use / This pet item may only be used on a Griffin pet. / Gain +2,000 coins when digging up any Griffin Burrow. / Grants +13 Magic Find and +55 Defense. / Right-click on your summoned pet to give it this item! | - | craft: 384x ENCHANTED_SPIDER_EYE, 1x PET_ITEM_LUCKY_CLOVER, 1x PRE_DIGESTION_FISH, 1x REINFORCED_SCALES |
| Hardened Scales | PET_ITEM_HARDENED_SCALES_EPIC | E | - | - | - | - |
| Iron Claws | PET_ITEM_IRON_CLAWS_EPIC | E | - | - | - | - |
| Large Sloth Claw | LARGE_SLOTH_CLAW | E | - | Use at Kat to upgrade Sloth Pets from Rare to Epic. | - | - |
| Lucky Clover | PET_ITEM_LUCKY_CLOVER | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +7 Magic Find and +7 Overbloom. / Right-click on your summoned pet to ... | - | craft: 256x ENCHANTED_EMERALD, 1x PET_ITEM_LUCKY_CLOVER_DROP |
| Mining Exp Boost | PET_ITEM_MINING_SKILL_BOOST_EPIC | E | - | - | - | - |
| Minos Relic | MINOS_RELIC | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Increases all pet stats by 33.3%. / Right-click on your summoned pet to give it... | - | - |
| Sharpened Claws | PET_ITEM_SHARPENED_CLAWS_EPIC | E | - | - | - | - |
| Unalloyed Speed | UNALLOYED_SPEED | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +50 Max Speed Cap. / Right-click on your summoned pet to give it this item! | - | craft: 512x ENCHANTED_SUGAR, 32x CANE_KNOT |
| Washed-up Souvenir | WASHED_UP_SOUVENIR | E | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +5 Sea Creature Chance. / Right-click on your summoned pet to give it t... | - | - |
| All Skills Exp Boost | PET_ITEM_ALL_SKILLS_BOOST_LEGENDARY | L | - | - | - | - |
| Big Teeth | PET_ITEM_BIG_TEETH_LEGENDARY | L | - | - | - | - |
| Burnt Texts | BURNT_TEXTS | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +5 Double Hook Chance, but reduces your  Fishing Speed by 50. / Right-... | - | - |
| Combat Exp Boost | PET_ITEM_COMBAT_SKILL_BOOST_LEGENDARY | L | - | - | - | - |
| Farming Exp Boost | PET_ITEM_FARMING_SKILL_BOOST_LEGENDARY | L | - | - | - | - |
| Fishing Exp Boost | PET_ITEM_FISHING_SKILL_BOOST_LEGENDARY | L | - | - | - | - |
| Foraging Exp Boost | PET_ITEM_FORAGING_SKILL_BOOST_LEGENDARY | L | - | - | - | - |
| Giant Sloth Claw | GIANT_SLOTH_CLAW | L | - | Use at Kat to upgrade Sloth Pets from Epic to Legendary. | - | - |
| Hardened Scales | PET_ITEM_HARDENED_SCALES_LEGENDARY | L | - | - | - | - |
| Hephaestus Plushie | HEPHAESTUS_PLUSHIE | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +50 Attack Speed. / Right-click on your summoned pet to give it this item! | - | craft: 3x CROCHET_TIGER_PLUSHIE, 1x HEPHAESTUS_ANVIL |
| Hephaestus Relic | HEPHAESTUS_RELIC | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Increases all pet stats by 50%. / Right-click on your summoned pet to give it t... | - | craft: 3x MINOS_RELIC, 1x HEPHAESTUS_ANVIL |
| Hephaestus Remedies | HEPHAESTUS_REMEDIES | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Increases this pet's  Strength by 100%. / Right-click on your summoned pet to ... | - | craft: 3x ANTIQUE_REMEDIES, 1x HEPHAESTUS_ANVIL |
| Hephaestus Shelmet | HEPHAESTUS_SHELMET | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +20 True Defense and makes the pet's owner immune to knockback. / Right... | - | craft: 3x DWARF_TURTLE_SHELMET, 1x HEPHAESTUS_ANVIL |
| Hephaestus Souvenir | HEPHAESTUS_SOUVENIR | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +10 Sea Creature Chance. / Right-click on your summoned pet to give it ... | - | craft: 3x WASHED_UP_SOUVENIR, 1x HEPHAESTUS_ANVIL |
| Hephaestus Urn | HEPHAESTUS_URN | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +10 Pet Luck. / Right-click on your summoned pet to give it this item! | - | craft: 3x CRETAN_URN, 1x HEPHAESTUS_ANVIL |
| Iron Claws | PET_ITEM_IRON_CLAWS_LEGENDARY | L | - | - | - | - |
| Lotus Crown | LOTUS_CROWN | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Trophies are +10% more likely to be DIAMOND. / Right-click on your summoned pet... | - | craft: 2x CONDENSED_WATER_LILY, 1x PRINCE_CROWN_JEWEL, 5x ENCHANTED_GOLD_BLOCK, 1x LOTUS_WATER_ORB |
| Mining Exp Boost | PET_ITEM_MINING_SKILL_BOOST_LEGENDARY | L | - | - | - | - |
| Poignant Lucky Clover | POIGNANT_LUCKY_CLOVER | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +13 Magic Find and +13 Overbloom. / Right-click on your summoned pet t... | - | - |
| Quick Claw | PET_ITEM_QUICK_CLAW | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Every 2 pet levels, you gain +1 Mining Speed and +1 Mining Fortune. / Right-c... | - | - |
| Reaper Gem | REAPER_GEM | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gain 8 Ferocity for 5s on kill. / Right-click on your summoned pet to give it ... | - | - |
| Sharpened Claws | PET_ITEM_SHARPENED_CLAWS_LEGENDARY | L | - | - | - | - |
| Snake Eyes | SNAKE_EYES | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +1.1 Treasure Chance. / Right-click on your summoned pet to give it thi... | - | - |
| Textbook | PET_ITEM_TEXTBOOK | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Increases the pet's  Intelligence by 100%. / Right-click on your summoned pet ... | - | craft: 8x ENCHANTED_LAPIS_LAZULI_BLOCK, 64x ENCHANTED_PAPER |
| Tier Boost | PET_ITEM_TIER_BOOST | L | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Boosts the rarity of your pet by 1 tier! / Right-click on your summoned pet to ... | - | craft: 10x ENCHANTED_IRON_BLOCK, 1x PET_ITEM_TIER_BOOST_DROP |
| All Skills Exp Boost | PET_ITEM_ALL_SKILLS_BOOST_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +10% pet exp for all skills. / Right-click on your summoned pet to give i... | - | - |
| Big Teeth | PET_ITEM_BIG_TEETH_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Grants +5 Crit Chance. / Right-click on your summoned pet to give it this item! | - | - |
| Combat Exp Boost | PET_ITEM_COMBAT_SKILL_BOOST_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +20% pet exp for Combat. / Right-click on your summoned pet to give it th... | - | - |
| Farming Exp Boost | PET_ITEM_FARMING_SKILL_BOOST_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +20% pet exp for Farming. / Right-click on your summoned pet to give it t... | - | - |
| Fishing Exp Boost | PET_ITEM_FISHING_SKILL_BOOST_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +20% pet exp for Fishing. / Right-click on your summoned pet to give it t... | - | - |
| Foraging Exp Boost | PET_ITEM_FORAGING_SKILL_BOOST_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +20% pet exp for Foraging. / Right-click on your summoned pet to give it ... | - | - |
| Hardened Scales | PET_ITEM_HARDENED_SCALES_COMMON | ? | - | - | - | - |
| Iron Claws | PET_ITEM_IRON_CLAWS_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Increases the pet's  Crit Damage by 40% and  Crit Chance by 40%. / Right-clic... | - | - |
| Mining Exp Boost | PET_ITEM_MINING_SKILL_BOOST_COMMON | ? | - | Consumed on use / Pet Items can boost pets in many powerful ways! A pet can only hold one Pet Item, but you can swap it at any time! The pet must be visible to apply the item! / Gives +20% pet exp for Mining. / Right-click on your summoned pet to give it th... | - | - |
| Sharpened Claws | PET_ITEM_SHARPENED_CLAWS_COMMON | ? | - | - | - | - |

## Appendix E — Kat upgrade costs

Times: h = hours, d = days. Rarity letters C/U/R/E/L/M.

| Pet | Kat upgrades (from->to: coins, time, items) |
|---|---|
| ARMADILLO | C->U: 10,000 coins, 3h ; U->R: 30,000 coins, 10h ; R->E: 250,000 coins, 2d ; E->L: 1,000,000 coins, 5d ; L->M: 1,000,000 coins, 1h, FROZEN_SCUTE:1 |
| BABY_YETI | C->U: 50,000 coins, 1d, ICE_HUNK:32 ; U->R: 100,000 coins, 2d, ICE_HUNK:64 ; R->E: 250,000 coins, 3d, ICE_HUNK:128 ; E->L: 500,000 coins, 4d, BLUE_ICE_HUNK:8 ; L->M: 1,000,000 coins, 1h, POCKET_SIZED_IGLOO:1 |
| BAL | E->L: 2,000,000 coins, 10d, YOGGIE:100 |
| BAT | C->U: 5,000 coins, 0.0833333h, RED_MUSHROOM:256, BROWN_MUSHROOM:256 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_RED_MUSHROOM:4, ENCHANTED_BROWN_MUSHROOM:4 ; R->E: 25,000 coins, 0.25h, ENCHANTED_RED_MUSHROOM:8, ENCHANTED_BROWN_MUSHROOM:8 ; E->L: 50,000 coins, 0.5h, ENCHANTED_RED_MUSHROOM:16, ENCHANTED_BROWN_MUSHROOM:16 ; L->M: 100,000 coins, 1h, ENCHANTED_RED_MUSHROOM:32, ENCHANTED_BROWN_MUSHROOM:32, PET_ITEM_VAMPIRE_FANG:1 |
| BEE | C->U: 0 coins, 0.0166667h, SMALL_HONEY_DIPPER:1 ; U->R: 0 coins, 1h, MEDIUM_HONEY_DIPPER:1 ; R->E: 0 coins, 1d, LARGE_HONEY_DIPPER:1 ; E->L: 0 coins, 3d, GIANT_HONEY_DIPPER:1 ; L->M: 10,000,000 coins, 1h, ROYAL_JELLY:1 |
| BLACK_CAT | L->M: 1,000,000 coins, 1h, BLACK_WOOLEN_YARN:1 |
| BLAZE | C->U: 250,000 coins, 1d, ENCHANTED_BLAZE_POWDER:128 ; U->R: 500,000 coins, 2d, ENCHANTED_BLAZE_POWDER:256 ; R->E: 1,000,000 coins, 3d, ENCHANTED_BLAZE_POWDER:512 ; E->L: 2,500,000 coins, 5d, ENCHANTED_BLAZE_ROD:8 |
| BLUE_WHALE | C->U: 15,000 coins, 1d ; U->R: 75,000 coins, 2d ; R->E: 900,000 coins, 7d, ENCHANTED_COOKED_FISH:1 ; E->L: 9,000,000 coins, 12d, ENCHANTED_COOKED_FISH:8 |
| CHICKEN | C->U: 500 coins, 0.00277778h, RAW_CHICKEN:128, FEATHER:128 ; U->R: 2,500 coins, 0.00555556h, RAW_CHICKEN:256, FEATHER:256 ; R->E: 5,000 coins, 0.00833333h, RAW_CHICKEN:512, FEATHER:512 ; E->L: 10,000 coins, 0.0125h, ENCHANTED_RAW_CHICKEN:8, ENCHANTED_FEATHER:8 |
| CROW | C->U: 250,000 coins, 1d, ENCHANTED_MOONFLOWER:128 ; U->R: 1,000,000 coins, 2d, COMPACTED_MOONFLOWER:4, VEILSHROOM:2 ; R->E: 5,000,000 coins, 3d, COMPACTED_MOONFLOWER:8, DUSKBLOOM:4 ; E->L: 10,000,000 coins, 5d, COMPACTED_MOONFLOWER:16, DO_NOT_EAT_SHROOM:8 |
| DOLPHIN | C->U: 100,000 coins, 1d ; U->R: 1,000,000 coins, 2d ; R->E: 10,000,000 coins, 7d ; E->L: 50,000,000 coins, 14d, ENCHANTED_RAW_FISH:16 |
| ELEPHANT | C->U: 15,000 coins, 6h ; U->R: 75,000 coins, 20h ; R->E: 900,000 coins, 5d ; E->L: 14,000,000 coins, 10d ; L->M: 12,500,000 coins, 1h, TRUNK_POLISH:1 |
| ENDERMAN | C->U: 1,000 coins, 1d ; U->R: 5,000 coins, 2d ; R->E: 10,000 coins, 6d ; E->L: 40,000,000 coins, 12d, ENCHANTED_EYE_OF_ENDER:8 ; L->M: 1,000,000 coins, 1h, ENDERMAN_CORTEX_REWRITER:1 |
| ENDERMITE | C->U: 5,000 coins, 0.0833333h, ENDER_STONE:512 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_ENDSTONE:8 ; R->E: 25,000 coins, 0.25h, ENCHANTED_ENDSTONE:16, MITE_GEL:32 ; E->L: 50,000 coins, 0.5h, ENCHANTED_ENDSTONE:32, MITE_GEL:64 ; L->M: 100,000 coins, 1h, MIXED_MITE_GEL:1, ENCHANTED_ENDSTONE:64 |
| ENDER_DRAGON | E->L: 400,000,000 coins, 20d, SUMMONING_EYE:8 |
| FLYING_FISH | R->E: 250,000 coins, 5d ; E->L: 1,000,000 coins, 10d, ENCHANTED_RAW_FISH:64 ; L->M: 1,000,000 coins, 1h, RADIOACTIVE_VIAL:1 |
| FROG | C->U: 10,000 coins, 1h, SMALL_FROG_TREAT:1 ; U->R: 50,000 coins, 2h, MEDIUM_FROG_TREAT:1 ; R->E: 100,000 coins, 3h, LARGE_FROG_TREAT:1 ; E->L: 200,000 coins, 4h, GIANT_FROG_TREAT:1 ; L->M: 1,000,000 coins, 1h, BUCKET_OF_FROGSPAWN |
| GHOUL | E->L: 5,000,000 coins, 10d, REVENANT_FLESH:512 |
| GIRAFFE | C->U: 15,000 coins, 1d, ENCHANTED_ACACIA_LOG:1 ; U->R: 75,000 coins, 2d, ENCHANTED_ACACIA_LOG:16 ; R->E: 900,000 coins, 7d, ENCHANTED_ACACIA_LOG:128 ; E->L: 9,000,000 coins, 12d, ENCHANTED_ACACIA_LOG:512 |
| GLACITE_GOLEM | C->U: 250,000 coins, 1d, ENCHANTED_GLACITE:128 ; U->R: 500,000 coins, 2d, ENCHANTED_GLACITE:256 ; R->E: 1,000,000 coins, 3d, ENCHANTED_GLACITE:512 ; E->L: 2,500,000 coins, 5d, GLACITE_AMALGAMATION:8 |
| GOLEM | E->L: 10,000,000 coins, 20d, ENCHANTED_IRON_BLOCK:8 |
| GRIFFIN | C->U: 100 coins, 0.0166667h, GRIFFIN_UPGRADE_STONE_UNCOMMON:1 ; U->R: 10,000 coins, 1h, GRIFFIN_UPGRADE_STONE_RARE:1 ; R->E: 250,000 coins, 1d, GRIFFIN_UPGRADE_STONE_EPIC:1 ; E->L: 500,000 coins, 3d, GRIFFIN_UPGRADE_STONE_LEGENDARY:1 ; L->M: 1,000,000 coins, 1h, GRIFFIN_UPGRADE_STONE_MYTHIC:1 |
| GUARDIAN | C->U: 20,000 coins, 1h ; U->R: 100,000 coins, 6h ; R->E: 500,000 coins, 2d ; E->L: 3,000,000 coins, 5d, ENCHANTED_PRISMARINE_SHARD:64 ; L->M: 1,000,000 coins, 1h, GUARDIAN_LUCKY_BLOCK:1 |
| HERMIT_CRAB | C->U: 10,000 coins, 1h, RUSTY_COIN:4 ; U->R: 50,000 coins, 2h, RUSTY_COIN:16 ; R->E: 200,000 coins, 6h, BUSTED_BELT_BUCKLE:8 ; E->L: 1,000,000 coins, 2d, OLD_LEATHER_BOOT:1 ; L->M: 1,000,000 coins, 1h, SCUTTLER_SHELL:1 |
| HORSE | C->U: 500 coins, 0.00277778h, LEATHER:128 ; U->R: 2,500 coins, 0.00555556h, LEATHER:256 ; R->E: 5,000 coins, 0.00833333h, LEATHER:512 ; E->L: 10,000 coins, 0.0125h, ENCHANTED_LEATHER:8 |
| HOUND | E->L: 5,000,000 coins, 10d, WOLF_TOOTH:512 |
| JELLYFISH | E->L: 15,000,000 coins, 10d, ENCHANTED_SLIME_BALL:16 |
| JERRY | C->U: 500 coins, 0.00277778h, ASSISTANT:1 ; U->R: 2,500 coins, 0.00555556h, ASSISTANT:1 ; R->E: 5,000 coins, 0.00833333h, ASSISTANT:1 ; E->L: 10,000 coins, 0.0125h, ASSISTANT:1 ; L->M: 25,000 coins, 1h, PET_ITEM_TOY_JERRY:1 |
| LION | C->U: 15,000 coins, 1d ; U->R: 75,000 coins, 2d ; R->E: 900,000 coins, 7d ; E->L: 14,000,000 coins, 12d, ENCHANTED_RAW_BEEF:1024 |
| MAGMA_CUBE | C->U: 2,500 coins, 2h ; U->R: 5,000 coins, 20h ; R->E: 100,000 coins, 5d ; E->L: 500,000 coins, 10d, ENCHANTED_MAGMA_CREAM:16 |
| MEGALODON | E->L: 10,000,000 coins, 20d |
| MITHRIL_GOLEM | C->U: 5,000 coins, 0.0833333h, MITHRIL_ORE:512 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_MITHRIL:8 ; R->E: 25,000 coins, 0.25h, ENCHANTED_MITHRIL:16 ; E->L: 50,000 coins, 0.5h, ENCHANTED_MITHRIL:32 ; L->M: 100,000 coins, 1h, PET_ITEM_PURE_MITHRIL_GEM:1, ENCHANTED_MITHRIL:64 |
| MONKEY | C->U: 15,000 coins, 1d ; U->R: 75,000 coins, 2d ; R->E: 900,000 coins, 7d ; E->L: 17,000,000 coins, 12d |
| MOOSHROOM_COW | C->U: 25,000 coins, 1h, ENCHANTED_MYCELIUM:16 ; U->R: 50,000 coins, 3h, ENCHANTED_MYCELIUM:32 ; R->E: 100,000 coins, 6h, DIGESTED_MUSHROOMS:32, ENCHANTED_MYCELIUM:64 ; E->L: 250,000 coins, 12h, ENCHANTED_MYCELIUM:128, DIGESTED_MUSHROOMS:64 |
| MOSQUITO | C->U: 250,000 coins, 1d, TARANTULA_SILK:8 ; U->R: 500,000 coins, 2d, TARANTULA_SILK:16 ; R->E: 1,000,000 coins, 3d, TARANTULA_SILK:32 ; E->L: 2,500,000 coins, 5d, TARANTULA_SILK:64 |
| OCELOT | C->U: 5,000 coins, 0.0833333h, LOG-3:512 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_JUNGLE_LOG:8 ; R->E: 25,000 coins, 0.25h, ENCHANTED_JUNGLE_LOG:16 ; E->L: 50,000 coins, 0.5h, ENCHANTED_JUNGLE_LOG:32 |
| ORCHID_MANTIS | C->U: 250,000 coins, 1d, FLORAL_GELATIN:16 ; U->R: 500,000 coins, 2d, FLORAL_GELATIN:32 ; R->E: 1,000,000 coins, 3d, FLORAL_GELATIN:48 ; E->L: 2,500,000 coins, 5d, FLORAL_GELATIN:64 |
| PARROT | E->L: 15,000,000 coins, 10d, ENCHANTED_FEATHER:16 |
| PHOENIX | E->L: 100,000,000 coins, 20d, ENCHANTED_BLAZE_POWDER:1024 |
| PIG | C->U: 100 coins, 0.0166667h, SMALL_PIG_EAR:1 ; U->R: 10,000 coins, 1h, MEDIUM_PIG_EAR:1 ; R->E: 250,000 coins, 1d, LARGE_PIG_EAR:1 ; E->L: 500,000 coins, 3d, GIANT_PIG_EAR:1 |
| PIGMAN | C->U: 25,000 coins, 1h, ENCHANTED_PORK:16 ; U->R: 50,000 coins, 3h, ENCHANTED_PORK:32 ; R->E: 100,000 coins, 6h, ENCHANTED_PORK:64 ; E->L: 250,000 coins, 12h, ENCHANTED_PORK:128 |
| RABBIT | C->U: 5,000 coins, 0.0833333h, RABBIT:512, RABBIT_HIDE:256, RABBIT_FOOT:256 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_RABBIT:8, ENCHANTED_RABBIT_HIDE:4, ENCHANTED_RABBIT_FOOT:4 ; R->E: 25,000 coins, 0.25h, ENCHANTED_RABBIT:16, ENCHANTED_RABBIT_HIDE:8, ENCHANTED_RABBIT_FOOT:8 ; E->L: 50,000 coins, 0.5h, ENCHANTED_RABBIT:32, ENCHANTED_RABBIT_HIDE:16, ENCHANTED_RABBIT_FOOT:16 ; L->M: 100,000 coins, 1h, ENCHANTED_RABBIT:64, ENCHANTED_RABBIT_HIDE:32, ENCHANTED_RABBIT_FOOT:32, PET_ITEM_CHOCOLATE_SYRINGE:1 |
| RAT | L->M: 1,000,000 coins, 1h, RAT_JETPACK:1 |
| ROCK | C->U: 100,000 coins, 1d ; U->R: 1,000,000 coins, 2d ; R->E: 10,000,000 coins, 7d ; E->L: 50,000,000 coins, 14d, ENCHANTED_COBBLESTONE:64 |
| SCATHA | R->E: 125,000,000 coins, 7d, ENCHANTED_HARD_STONE:64 ; E->L: 250,000,000 coins, 14d, ENCHANTED_HARD_STONE:256 |
| SEAL | C->U: 100 coins, 0.0166667h, SMALL_SEAL_PLUSHIE:1 ; U->R: 10,000 coins, 1h, MEDIUM_SEAL_PLUSHIE:1 ; R->E: 250,000 coins, 1d, LARGE_SEAL_PLUSHIE:1 ; E->L: 500,000 coins, 3d, GIANT_SEAL_PLUSHIE:1 |
| SHEEP | C->U: 5,000 coins, 0.0833333h, MUTTON:512, WOOL:512 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_MUTTON:8, ENCHANTED_WOOL:8 ; R->E: 25,000 coins, 0.25h, ENCHANTED_MUTTON:16, ENCHANTED_WOOL:16 ; E->L: 50,000 coins, 0.5h, ENCHANTED_MUTTON:32, ENCHANTED_WOOL:32 |
| SILVERFISH | C->U: 500 coins, 0.00277778h, COBBLESTONE:128 ; U->R: 1,000 coins, 0.00555556h, COBBLESTONE:256 ; R->E: 5,000 coins, 0.00833333h, COBBLESTONE:512 ; E->L: 10,000 coins, 0.0125h, ENCHANTED_COBBLESTONE:8 |
| SKELETON | C->U: 5,000 coins, 0.0833333h, BONE:512 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_BONE:8 ; R->E: 25,000 coins, 0.25h, ENCHANTED_BONE:16 ; E->L: 50,000 coins, 0.5h, ENCHANTED_BONE:32 |
| SLOTH | C->U: 25,000 coins, 1h, SMALL_SLOTH_CLAW:1 ; U->R: 100,000 coins, 4h, MEDIUM_SLOTH_CLAW:1 ; R->E: 250,000 coins, 12h, LARGE_SLOTH_CLAW:1 ; E->L: 1,000,000 coins, 1d, GIANT_SLOTH_CLAW:1 |
| SLUG | E->L: 10,000,000 coins, 7d, ENCHANTED_COMPOST:1 |
| SNAIL | C->U: 25,000 coins, 1h, ENCHANTED_RED_SAND:16 ; U->R: 50,000 coins, 3h, ENCHANTED_RED_SAND:32 ; R->E: 100,000 coins, 6h, BURNING_EYE:32, ENCHANTED_RED_SAND:64 ; E->L: 250,000 coins, 12h, BURNING_EYE:64, ENCHANTED_RED_SAND:128 |
| SNOWMAN | L->M: 1,000,000 coins, 1h, MAGIC_TOP_HAT:1 |
| SPIDER | C->U: 5,000 coins, 0.0833333h, STRING:512, SPIDER_EYE:256 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_STRING:8, ENCHANTED_SPIDER_EYE:4 ; R->E: 25,000 coins, 0.25h, ENCHANTED_STRING:16, ENCHANTED_SPIDER_EYE:8 ; E->L: 50,000 coins, 0.5h, ENCHANTED_STRING:32, ENCHANTED_SPIDER_EYE:16 ; L->M: 100,000 coins, 1h, ENCHANTED_STRING:64, ENCHANTED_SPIDER_EYE:32, FAKE_NEUROSCIENCE_DEGREE:1 |
| SPIRIT | E->L: 5,000,000 coins, 10d, ENCHANTED_GHAST_TEAR:64 |
| SQUID | C->U: 20,000 coins, 1h ; U->R: 100,000 coins, 6h ; R->E: 500,000 coins, 2d ; E->L: 3,000,000 coins, 5d, ENCHANTED_INK_SACK:64 |
| TARANTULA | E->L: 5,000,000 coins, 10d, TARANTULA_WEB:512 ; L->M: 1,000,000 coins, 1h, FAKE_NEUROSCIENCE_DEGREE:1 |
| TIGER | C->U: 15,000 coins, 1d ; U->R: 75,000 coins, 2d ; R->E: 900,000 coins, 7d ; E->L: 14,000,000 coins, 12d, ENCHANTED_RAW_CHICKEN:1024 |
| TURTLE | E->L: 15,000,000 coins, 10d, ENCHANTED_RAW_FISH:16 |
| WITCH | C->U: 100 coins, 0.0166667h, SMALL_WITCH_CAULDRON ; U->R: 10,000 coins, 1h, MEDIUM_WITCH_CAULDRON ; R->E: 250,000 coins, 1d, LARGE_WITCH_CAULDRON ; E->L: 500,000 coins, 3d, GIANT_WITCH_CAULDRON |
| WITHER_SKELETON | C->U: 25,000 coins, 1h, ENCHANTED_COAL:16 ; U->R: 50,000 coins, 3h, ENCHANTED_COAL:32 ; R->E: 100,000 coins, 6h, ENCHANTED_COAL:64 ; E->L: 250,000 coins, 12h, ENCHANTED_COAL:128 |
| WOLF | C->U: 5,000 coins, 0.0833333h, LOG-1:512 ; U->R: 10,000 coins, 0.166667h, ENCHANTED_SPRUCE_LOG:8 ; R->E: 25,000 coins, 0.25h, ENCHANTED_SPRUCE_LOG:16 ; E->L: 50,000 coins, 0.5h, ENCHANTED_SPRUCE_LOG:32 |
| ZOMBIE | C->U: 500 coins, 0.00277778h, ROTTEN_FLESH:128 ; U->R: 1,000 coins, 0.00555556h, ROTTEN_FLESH:256 ; R->E: 2,500 coins, 0.00833333h, ROTTEN_FLESH:512 ; E->L: 5,000 coins, 0.0125h, ENCHANTED_ROTTEN_FLESH:8 |

## Appendix F — All pets (NEU petnums + lore, Lv100 values)

#### Ammonite (`AMMONITE`) — Fishing pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Sea Creature Chance 0.06→6
- **Heart of the Sea**: Grants +1 Sea Creature Chance to your pet for each Heart of the Mountain level.
- **Expert Cave Fisher**: Grants +0.5 Double Hook Chance for each Heart of the Mountain level while in the Crystal Hollows.
- **Gift of the Ammonite**: Each Mining and Fishing level grants +0.5 Fishing Speed, +2 Speed and +2 Defense.

#### Ankylosaurus (`ANKYLOSAURUS`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Health 1.5→150, Defense 0.5→50, True Defense 0.15→15
- **Armored Tank**: Gain 50% of your  Defense as  Strength. (Max +500)
- **Unyielding**: Increase the effectiveness of Last Stand and Lifeline by 50%.
- **Clubbed Tail**: Every 5th hit deals 50% of your final damage to enemies within 5 blocks. Enemies hit deal 10% less damage for 10s.

#### Armadillo (`ARMADILLO`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Defense 2→200
- Mining Mount
- Ridable
- **ride it! Moves faster based on your**:  Speed.
- Tunneller
- **breaks all blocks in its path using**: your held item.
- **Rolling Miner**: Every 20 seconds, the next Gemstone you mine gives 2x drops
- **Long Claws**: Grants +300 Mining Spread while mining Hard Stone.
- **Well-Worked**: Consumes 10% less energy when tunneling.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [] stats(Defense 200) · U: [] stats(Defense 200) · R: [40] stats(Defense 200) · E: [30] stats(Defense 200) · L: [20, 300] stats(Defense 200) · M: [20, 300, 10] stats(Defense 200)

#### Baby Yeti (`BABY_YETI`) — Fishing pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Strength 0.75→75, Fishing Speed 0.5→50, Sea Creature Chance 0.05→5, Cold Resistance 0→10
- **Yeti Fury**: Buffs the Yeti Sword by 100  Damage and  Intelligence and reduces its cooldown by 50%.
- **Cold Breeze**: Increases Combat Stats and Fishing Stats by 20% while on Jerry's Workshop.
- **Frosty Familiarity**: Grants +10 Magic Find against Winter Sea Creatures.
- **Family Gathering**: Grants 10 Tracking while on Jerry's Workshop.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50, 20] stats(Strength 50,Fishing Speed 50,Sea Creature Chance 5) · U: [75, 35] stats(Strength 50,Fishing Speed 50,Sea Creature Chance 5) · R: [75, 35, 10] stats(Strength 50,Fishing Speed 50,Sea Creature Chance 5) · E: [100, 50, 20] stats(Strength 75,Fishing Speed 50,Sea Creature Chance 5) · L: [100, 50, 20, 10] stats(Strength 75,Fishing Speed 50,Sea Creature Chance 5) · M: [100, 50, 20, 10] stats(Strength 75,Fishing Speed 50,Sea Creature Chance 5,Cold Resistance 10)

#### Bal (`BAL`) — Mining pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Mining Fortune 1→100, Heat Resistance 1.5→150
- **Furnace**: Grants +3 Pristine while in the Magma Fields.
- **Dispersion**: While in the Crystal Hollows, killing mobs reduces your  Heat by 4.
- **Cannibal**: Increases damage dealt to Bal by 100%.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [2, 2] stats(Mining Fortune 100,Heat Resistance 150) · L: [3, 4, 100] stats(Mining Fortune 100,Heat Resistance 150)

#### Bat (`BAT`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Intelligence 1→100, Speed 0.05→5, Sea Creature Chance 0.05→5
- **Candy Lover**: Increases drop chance of candies from mobs by 20%.
- **Nightmare**: During night, gain 30  Intelligence, 50  Speed, and Night Vision.
- **Wings of Steel**: Deals +50% damage to Spooky enemies during the Spooky Festival.
- **Sonar**: Grants a +25% chance to catch Spooky Sea Creatures during the Spooky Festival.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Intelligence 100,Speed 5) · U: [10] stats(Intelligence 100,Speed 5) · R: [15, 20, 40] stats(Intelligence 100,Speed 5) · E: [20, 30, 50] stats(Intelligence 100,Speed 5) · L: [20, 30, 50, 50] stats(Intelligence 100,Speed 5) · M: [20, 30, 50, 50, 25] stats(Intelligence 100,Speed 5,Sea Creature Chance 5)

#### Bee (`BEE`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Strength 5.25→30, Intelligence 0.5→50, Speed 0.1→10, Farming Fortune 0.4→40, Foraging Fortune 0.4→40, Mining Fortune 0.4→40
- **Hive**: For each player within 25 blocks:  +10 Intelligence  +8 Strength  +5 Defense Max 15 players
- **Busy Buzz Buzz**: Grants +40 of each to your pet:  Farming Fortune  Foraging Fortune  Mining Fortune
- **Honey Harvester**: You have a 0.02% chance to find a Honey Jar when farming crops.
- **Powered by Pollen**: Grants +160 Sunflower, Moonflower, and Wild Rose Fortune while in The Garden.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [3, 3, 2] stats(Intelligence 50,Speed 10,Strength 30) · U: [6, 5, 3] stats(Intelligence 50,Speed 10,Strength 30) · R: [6, 5, 3, 20] stats(Intelligence 50,Speed 10,Strength 30,Farming Fortune 20,Foraging Fortune 20,Mining Fortune 20) · E: [10, 8, 5, 30] stats(Strength 30,Intelligence 50,Speed 10,Farming Fortune 30,Foraging Fortune 30,Mining Fortune 30) · L: [10, 8, 5, 30, 0.02] stats(Strength 30,Intelligence 50,Speed 10,Farming Fortune 30,Foraging Fortune 30,Mining Fortune 30) · M: [10, 8, 5, 40, 0.02, 160] stats(Strength 30,Intelligence 50,Speed 10,Farming Fortune 40,Foraging Fortune 40,Mining Fortune 40)

#### Bingo (`BINGO`) — All pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Health 1→100, Strength 0.25→35, Speed 0.75→75
- All Skills
- **Lucky Looting**: Gain 25% more collection items from any source!
- **Climber**: Gain 250% more HOTM and HOTF XP.
- **Fast Learner**: Gain 15% more Skill Experience and Slayer Experience
- **Chimera**: Increases the base stats of your active pet by 40%.
- **Scavenger**: Gain 1 more coins per monster level on kill
- **Consumer**: Potion effects you obtain will have 50% more time
- **Power Of Completion**: Gain +2❁ Strength, +1☣ Crit Chance, and +5❤ Health per completed Personal Bingo Goal in the current Bingo Event
- **This pet's perks are active even**: when the pet is not summoned!
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [25, 250] stats(Health 100,Strength 25,Speed 75) · U: [25, 250, 15] stats(Health 100,Strength 25,Speed 75) · R: [25, 250, 15, 40] stats(Health 100,Strength 35,Speed 75) · E: [25, 250, 15, 40, 1] stats(Health 100,Strength 35,Speed 75) · L: [25, 250, 15, 40, 1, 50] stats(Health 100,Strength 35,Speed 75) · M: [25, 250, 15, 40, 1, 50] stats(Health 100,Strength 35,Speed 75)

#### Black Cat (`BLACK_CAT`) — Combat pet — rarities: Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Intelligence 1→100, Speed 1.25→125, Pet Luck 0.15→15, Magic Find 0.15→15
- **Hunter**: Increases your  Speed and speed cap by +100.
- **Omen**: Grants +15 Pet Luck.
- **Supernatural**: Grants +15 Magic Find.
- **Looting**: Gain 15% more collection items from monsters!
- Ability numbers @Lv100 by rarity (in order of appearance above): L: [100, 15, 15] stats(Intelligence 100,Speed 125,Pet Luck 15,Magic Find 15) · M: [100, 15, 15, 15] stats(Intelligence 100,Speed 125,Pet Luck 15,Magic Find 15)

#### Blaze (`BLAZE`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Defense 0.3→30, Intelligence 1→100
- **Nether Embodiment**: Increases Combat and Miscellaneous stats by 10% while on the Crimson Isle.
- **Bling Armor**: Upgrades Blaze Armor stats and ability by 40%.
- **Fusion-Style Potato**: Double effects of hot potato books
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [5] stats(Defense 30,Intelligence 100) · U: [7] stats(Defense 30,Intelligence 100) · R: [7, 40] stats(Defense 30,Intelligence 100) · E: [10, 40] stats(Defense 30,Intelligence 100) · L: [10, 40] stats(Defense 30,Intelligence 100)

#### Blue Whale (`BLUE_WHALE`) — Fishing pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Health 2→200
- **Ingest**: If you have any absorption active, gain +250% Damage Reduction.
- **Bulk**: Gain 1 Defense per 20 Max  Health.
- **Archimedes**: Gain +20% Max  Health.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50] stats(Health 200) · U: [100] stats(Health 200) · R: [150, 1] stats(Health 200) · E: [200, 1] stats(Health 200) · L: [250, 1, 20] stats(Health 200)

#### Chicken (`CHICKEN`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Speed 0.5→50, Farming Fortune 0.5→50
- **Free Range**: Grants +100 Farming Fortune while on Public Islands.
- **Eggstra Loot**: Chickens always drop an Egg when killed. Grants a 100% chance for animals to drop an additional item.
- **Light Feet**: Reduces fall damage by 100%.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50] stats(Speed 50,Farming Fortune 50) · U: [75] stats(Speed 50,Farming Fortune 50) · R: [100, 50] stats(Speed 50,Farming Fortune 50) · E: [100, 100] stats(Speed 50,Farming Fortune 50) · L: [100, 100, 100] stats(Speed 50,Farming Fortune 50)

#### Crow (`CROW`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 1.5→150, Ability Damage 0.2→20
- **Quick Hands**: Lowers the cooldown of your Weapon abilities by 15.
- **Camouflage**: After casting an ability, increase your  Defense by +20 for 20 seconds. Capped at 500 Defense
- **Insightful**: Gives a 15 chance to not consume Mana when using an ability.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [5] stats(Intelligence 150,Ability Damage 20) · U: [10] stats(Intelligence 150,Ability Damage 20) · R: [10, 15] stats(Intelligence 150,Ability Damage 20) · E: [15, 20] stats(Intelligence 150,Ability Damage 20) · L: [15, 20, 15] stats(Intelligence 150,Ability Damage 20)

#### Dolphin (`DOLPHIN`) — Fishing pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 1→100, Sea Creature Chance 0.05→5
- **Pod Tactics**: Grants +10 Fishing Speed for each player within 30 blocks, up to 5 players.
- **Echolocation**: Grants +10 Sea Creature Chance.
- **Splash Surprise**: Stun Sea Creatures for 5s after fishing them up.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [6] stats(Intelligence 100,Sea Creature Chance 5) · U: [8] stats(Intelligence 100,Sea Creature Chance 5) · R: [8, 7] stats(Intelligence 100,Sea Creature Chance 5) · E: [10, 10] stats(Intelligence 100,Sea Creature Chance 5) · L: [10, 10] stats(Intelligence 100,Sea Creature Chance 5)

#### Droplet Wisp (`DROPLET_WISP`) — ? pet — rarities: Uncommon
- Stats @Uncommon Lv1→Lv100: Health 1→100, Damage 0.1→10
- Gabagool Pet, feed to gain XP
- **Drophammer**: Lets you break fire pillars 2x faster, healing you for 15% of your max ❤ over 3s.
- **Bulwark**: Kill Blazes to gain defense against them and demons. Bonus: +0 & +0 Next Upgrade: +30 & +3 (0/100)
- **Blaze Slayer**: Gain 1.3x Combat XP against Blazes.

#### Eerie (`EERIE`) — Combat pet — rarities: Common, Rare, Legendary
- Stats @Legendary Lv1→Lv100: Speed 0.1→10, Intelligence 0.5→50
- Edition: #???
- **Fearnesy**: Fear from Great Spook Armor in your wardrobe applies to you, even if you aren't wearing it.
- **Fearama**: Increases damage dealt to Primal Fears and Spooky Mobs by 0.4% for every Fear you have.
- **Fearcreasing**: Gives +1 Fear for every 10 Primal Fears killed, up to 150 Primal Fears. Primal Fear Kills: (111/150)
- * Soulbound *
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [] stats(Speed 10,Intelligence 50) · R: [1] stats(Speed 10,Intelligence 50) · L: [0.4, 1] stats(Speed 10,Intelligence 50)

#### Elephant (`ELEPHANT`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Health 1→100, Intelligence 0.75→75, Farming Fortune 0.5→50
- **Stomp**: Gain 20 Defense for every 100  Speed.
- **Walking Fortress**: Gain 1 Health for every 10  Defense.
- **Trunk Efficiency**: Grants +150 Farming Fortune, which increases your chance for multiple drops.
- **Abundant Harvest**: Earn +20% Sowdust while farming.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Health 100,Intelligence 75) · U: [15] stats(Health 100,Intelligence 75) · R: [15, 0.75] stats(Health 100,Intelligence 75) · E: [20, 1] stats(Health 100,Intelligence 75) · L: [20, 1, 150] stats(Health 100,Intelligence 75) · M: [20, 1, 150, 20] stats(Health 100,Intelligence 75,Farming Fortune 50)

#### Enderman (`ENDERMAN`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Crit Damage 0.75→75
- **Enderian**: Take 30% less damage from  Ender mobs
- **Teleport Savvy**: Buffs Transmission abilities, granting 50 weapon damage for 5s on use
- **Zealot Madness**: Increases your odds to find a special Zealot by 25%.
- **Enderman Slayer**: Gain 1.5x Combat XP against Endermen.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(CDamage 75) · U: [20] stats(CDamage 75) · R: [20, 40] stats(CDamage 75) · E: [30, 50] stats(CDamage 75) · L: [30, 50, 25] stats(CDamage 75) · M: [30, 50, 25, 1.5] stats(CDamage 75)

#### Endermite (`ENDERMITE`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Intelligence 1.5→150, Pet Luck 0.1→10
- **More Stonks**: Gain more exp orbs for breaking end stone and gain a +100% chance to get an extra block dropped
- **Daily Commuter**: Transmission Abilities cost 40% less mana
- **Mite Bait**: Gain a 3% chance to dig up a bonus Nest Endermite per +1 Pet Luck (Stacks above 100%)
- **Sacrificer**: Increases the odds of rolling for bonus items in the Draconic Altar by 10%.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50] stats(Intelligence 100,Pet Luck 10) · U: [75] stats(Intelligence 150,Pet Luck 10) · R: [75, 30] stats(Intelligence 150,Pet Luck 10) · E: [100, 40] stats(Intelligence 150,Pet Luck 10) · L: [100, 40, 3] stats(Intelligence 150,Pet Luck 10) · M: [100, 40, 3, 10] stats(Intelligence 150,Pet Luck 10)

#### Ender Dragon (`ENDER_DRAGON`) — Combat pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Crit Chance 0.1→10, Crit Damage 0.5→50, Strength 0.5→50
- **End Strike**: Deal 200% more damage to ⊙ Ender mobs.
- **One With The Dragon**: Buffs the Aspect of the Dragons sword by 50 ❁ Damage and 30 ❁ Strength.
- **Superior**: Increases all Combat stats and  Magic Find by 10%.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [200, 50, 30] stats(CChance 10,CDamage 50,Strength 50) · L: [200, 50, 30, 10] stats(CChance 10,CDamage 50,Strength 50)

#### Flying Fish (`FLYING_FISH`) — Fishing pet — rarities: Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Defense 0.5→50, Strength 0.5→50
- **Quick Reel**: Grants +80 Fishing Speed.
- **Lava Bender**: Gives 100  Strength and  Defense when near water or lava
- **Magmatic Diver**: Increases the stats of Diver Armor, Magma Lord Armor, and Abyssal Armor by 20%.
- **Rapid Decay**: Increases the chance to activate the Flash Enchantment by 50%.
- Ability numbers @Lv100 by rarity (in order of appearance above): R: [60, 80] stats(Defense 50,Strength 50) · E: [75, 100] stats(Defense 50,Strength 50) · L: [80, 100, 20] stats(Defense 50,Strength 50) · M: [80, 100, 20, 50] stats(Defense 50,Strength 50)

#### Frog (`FROG`) — Foraging pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Speed 0.5→50, Fishing Speed 0.4→40, Trophy Chance 0.05→5, Respiration 0.1→10, Sweep 0.1→10
- **Hop**: Grants 80 Foraging Fortune for 20 seconds every time you jump.
- **Happy Tree Friends**: Grants 10 Foraging Fortune and 2 Fishing Speed for every other Frog Pet on the island, up to 10 frogs.
- **Hunting Enjoyer**: Increases your chance of fishing up Water Shards by 50% while on Foraging Islands.
- **Home Sweet Home**: Increases your chance of catching GOLD and DIAMOND Trophy Frogs by 10%.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [40] stats(Speed 50,Fishing Speed 40,Respiration 10,Sweep 10) · U: [60] stats(Speed 50,Fishing Speed 40,Respiration 10,Sweep 10) · R: [60, 5] stats(Speed 50,Fishing Speed 40,Respiration 10,Sweep 10) · E: [80, 10] stats(Speed 50,Fishing Speed 40,Respiration 10,Sweep 10) · L: [80, 10, 50] stats(Speed 50,Fishing Speed 40,Respiration 10,Sweep 10) · M: [80, 10, 2, 50, 10] stats(Speed 50,Fishing Speed 40,Trophy Chance 5,Respiration 10,Sweep 10)

#### Frost Wisp (`FROST_WISP`) — ? pet — rarities: Rare
- Stats @Rare Lv1→Lv100: Health 2.5→250, True Defense 0.15→15, Intelligence 0.5→50, Damage 0.15→15
- Gabagool Pet, feed to gain XP
- **Icehammer**: Lets you break fire pillars 2x faster, healing you for 25% of your max ❤ over 3s.
- **Bulwark**: Kill Blazes to gain defense against them and demons. Bonus: +0 & +0 Next Upgrade: +30 & +3 (0/100)
- **Blaze Slayer**: Gain 1.4x Combat XP against Blazes.
- **Extinguish**: While in combat on the Crimson Isle, spawn a pool every 8s. Bathing in it heals 15%❤ now and 4%❤/s for 8s.

#### Ghoul (`GHOUL`) — Combat pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Health 1→100, Ferocity 0.05→5, Intelligence 0.75→75, Vitality 0.15→15, Mending 0.25→25
- **Undead Slayer**: Gain 1.5x Combat XP against Zombies.
- **Army of the Dead**: Increases the amount of souls you can store by 2 and the chance of getting a mob's soul by 20%.
- **Reaper Soul**: Reduces the summoning cost of mobs by 30% and increases their damage output by 20%. Increases the health of all summoned mobs by 100%.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [1.5, 20] stats(Health 100,Ferocity 5,Intelligence 75,Vitality 15,Mending 25) · L: [1.5, 20, 30, 20, 100] stats(Health 100,Ferocity 5,Intelligence 75,Vitality 15,Mending 25)

#### Giraffe (`GIRAFFE`) — Foraging pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Crit Chance 0.05→5, Health 1→100, Swing Range 0.01→1
- **Good Heart**: Grants +200 Health Regen
- **Higher Ground**: Increases your  Crit Damage and  Strength by 0.15% for every 0.1 Swing Range over 3 (up to 6)
- **Long Neck**: Increases your melee damage by 100% if you are more than 3 blocks away from the target
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [100] stats(CChance 5,Health 100,Swing Range 1) · U: [150] stats(CChance 5,Health 100,Swing Range 1) · R: [150, 0.15] stats(CChance 5,Health 100,Swing Range 1) · E: [200, 0.15] stats(CChance 5,Health 100,Swing Range 1) · L: [200, 0.15, 100] stats(CChance 5,Health 100,Swing Range 1)

#### Glacial Wisp (`GLACIAL_WISP`) — ? pet — rarities: Epic
- Stats @Epic Lv1→Lv100: Health 4→400, True Defense 0.3→30, Intelligence 1.25→125, Damage 0.2→20
- Gabagool Pet, feed to gain XP
- **Icehammer**: Lets you break fire pillars, which heals you for 40% of your max ❤ over 3s.
- **Bulwark**: Kill Blazes to gain defense against them and demons. Bonus: +0 & +0 Next Upgrade: +30 & +3 (0/100)
- **Blaze Slayer**: Gain 1.45x Combat XP against Blazes.
- **Extinguish**: While in combat on the Crimson Isle, spawn a pool every 8s. Bathing in it heals 20%❤ now and 7%❤/s for 8s.

#### Glacite Golem (`GLACITE_GOLEM`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Mining Speed 1.25→125, Cold Resistance 0.1→10
- **Powder-powered**: Gain 20% more Glacite Powder from most sources.
- **Iceborn**: Gain +100  Mining Fortune while in the Glacite Mineshafts.
- **Frozen Perfection**: Gain +1  Pristine for every Frozen Corpse you've looted in the current Glacite Mineshaft.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Mining Speed 125,Cold Resistance 10) · U: [15] stats(Mining Speed 125,Cold Resistance 10) · R: [15, 75] stats(Mining Speed 125,Cold Resistance 10) · E: [20, 100] stats(Mining Speed 125,Cold Resistance 10) · L: [20, 100, 1] stats(Mining Speed 125,Cold Resistance 10)

#### Goblin (`GOBLIN`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Ore Fortune 1→100, Magic Find 0.07→7
- **Grunt Work**: Gain +250 Mining Speed when mining Ores.
- **Fetid Thief**: Gain +100 Mining Spread while in the Mines of Divan.
- **Free-range Eggs**: Increases the chance of finding rare goblin eggs by 50%.

#### Golden Dragon (`GOLDEN_DRAGON`) — Combat pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Bonus Attack Speed 25→50, Strength 25→50, Magic Find 5→10
- **Gold's Power**: Increase the potency of Midas' Sword and Midas Staff's Greed Ability by 10%.
- **Shining Scales**: Grants +11.1 Strength and +2.2 Magic Find to your pet for each digit in your Gold Collection. (Max 100M collection)
- **Dragon's Greed**: Grants +0.5% Strength per 5 Magic Find. (Max +5%)
- **Legendary Treasure**: Gain 0.25% damage for every million coins in your bank. (Max 250%)
- **Symbiosis**: If you own a level 200 Golden Dragon, gain +5 coins per monster kill for every other unique maxed Combat Pet that you own.
- Leveling: levels 101–200 (curve `101:200:1`); numbers shown are Lv100, Lv200 roughly double the scaling stat parts

#### Golem (`GOLEM`) — Combat pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Health 1→150, Strength 0.05→50, Swing Range 0.01→1
- **Last Stand**: While at less than 20% HP, reduce incoming damage by 20%. Additionally, gain a temporary shield equal to 40% of your maximum health and deal 40% more damage. (Lasts 12s, 60s cooldown)
- **Ricochet**: Your iron plating causes 25% of attacks to ricochet and hit the attacker
- **Toss**: Every 5 hits, throw the enemy up into the air and deal 5x damage (5s cooldown)
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [25] stats(Health 150,Strength 50,Swing Range 1) · L: [25] stats(Health 150,Strength 50,Swing Range 1)

#### Grandma Wolf (`GRANDMA_WOLF`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Health 1→100, Strength 0.25→25
- **Kill Combo**: Gain buffs for combo kills. Effects stack as you increase your combo.
- **5 Combo (lasts 10s)**:   +3%  Magic Find 10 Combo (lasts 8s)   +10 coins per kill 15 Combo (lasts 6s)   +3%  Magic Find 20 Combo (lasts 5s)   +15☯ Combat Wisdom 25 Combo (lasts 4s)   +3%  Magic Find 30 Combo (lasts 3s)   +10 coins per kill
- **This pet's perks are active even**: when the pet is not summoned!
- * Soulbound *
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10, 8, 6, 5, 4, 3] stats(Health 100,Strength 25) · U: [10, 8, 6, 5, 4, 3] stats(Health 100,Strength 25) · R: [10, 8, 6, 5, 4, 3] stats(Health 100,Strength 25) · E: [10, 8, 6, 5, 4, 3] stats(Health 100,Strength 25) · L: [10, 8, 6, 5, 4, 3] stats(Health 100,Strength 25)

#### Griffin (`GRIFFIN`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Crit Chance 0.1→10, Crit Damage 0.5→50, Strength 0.5→50, Bonus Attack Speed 0.25→25
- **Odyssey**: 12 types of  Mythological mobs can spawn from Griffin Burrows. Their stats scale with your Griffin’s rarity.
- **Sacred Strength**: Gain +15%  Strength when above 85%  Health.
- **King of Kings**: Grants +20 Magic Find on  Mythological mobs.
- **Ancient Earth**: Grants +1 Tracking on Griffin Burrows for each burrow excavated in your current chain.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [] stats(CChance 10,CDamage 50,Strength 50,AtkSpd 25) · U: [] stats(CChance 10,CDamage 50,Strength 50,AtkSpd 25) · R: [10] stats(CChance 10,CDamage 50,Strength 50,AtkSpd 25) · E: [15] stats(CChance 10,CDamage 50,Strength 50,AtkSpd 25) · L: [15, 20] stats(CChance 10,CDamage 50,Strength 50,AtkSpd 25) · M: [15, 20] stats(CChance 10,CDamage 50,Strength 50,AtkSpd 25)

#### Guardian (`GUARDIAN`) — Enchanting pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Defense 0.5→50, Intelligence 1→100
- **Laserbeam**: Zaps your enemies for 120x your  Intelligence every 3s.
- **Enchanting Wisdom Boost**: Grants +30☯ Enchanting Wisdom.
- **Mana Pool**: Regenerate 30% extra mana, doubled when near or in water
- **Lucky Seven**: Gain +7% chance to find ultra rare books in Superpairs.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [2] stats(Defense 50,Intelligence 100) · U: [6] stats(Defense 50,Intelligence 100) · R: [10, 25] stats(Defense 50,Intelligence 100) · E: [15, 30] stats(Defense 50,Intelligence 100) · L: [20, 30, 30] stats(Defense 50,Intelligence 100) · M: [120, 30, 30, 7] stats(Defense 50,Intelligence 100)

#### Hedgehog (`HEDGEHOG`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Speed 0.15→15
- **Spiky Quills**: Deal 100% more damage to  Pests.
- **Fearsome Farmer**: Grants +100 Farming Fortune and +35 Overbloom on  Pests.
- **Hunter's Insight**: Grants +0.7 Farming Fortune per Pest Bestiary Tier.

#### Hermit Crab (`HERMIT_CRAB`) — Fishing pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Defense 0.2→20, Sea Creature Chance 0.02→2, Fishing Speed 0.2→20
- **Comfort Zone**: Grants +40 Fishing Speed for 30s upon catching Treasure.
- **Seafloor Scalper**: Treasure catches are 10% more likely to be GREAT or OUTSTANDING.
- **Crab Rave**: Grants +0.2 Treasure Chance for each player with a Hermit Crab Pet within 30 blocks, up to 5 players.
- **Hotspot Hazard**: Increases the chance of catching Hotspot Sea Creatures by 50%.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [20] stats(Defense 20,Sea Creature Chance 2,Fishing Speed 20) · U: [30] stats(Defense 20,Sea Creature Chance 2,Fishing Speed 20) · R: [30, 7.5] stats(Defense 20,Sea Creature Chance 2,Fishing Speed 20) · E: [40, 10] stats(Defense 20,Sea Creature Chance 2,Fishing Speed 20) · L: [40, 10, 0.2] stats(Defense 20,Sea Creature Chance 2,Fishing Speed 20) · M: [40, 10, 0.2, 50] stats(Defense 20,Sea Creature Chance 2,Fishing Speed 20)

#### Horse (`HORSE`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 0.5→50, Speed 1→100
- **High Stride**: Grants permanent Jump Boost IV.
- **Stampede**: Mob kills grant a stack of +20  Speed and +5  Strength for 5s. (Max 20 stacks)
- **Trample**: After falling 20 or more blocks, absorb your fall damage and deal 5% of your weapon's  Damage for every block fallen to mobs within 3 blocks.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [] stats(Intelligence 50,Speed 100) · U: [] stats(Intelligence 50,Speed 100) · R: [20, 5] stats(Intelligence 50,Speed 100) · E: [20, 5] stats(Intelligence 50,Speed 100) · L: [20, 5, 5] stats(Intelligence 50,Speed 100)

#### Hound (`HOUND`) — Combat pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Bonus Attack Speed 0.25→25, Ferocity 0.05→5, Strength 0.4→40, Speed 0.1→10
- **Scavenger**: Gain +5 coins per monster kill
- **Finder**: Increases the chance for monsters to drop their armor by 30%.
- **Pack Slayer**: Gain 1.5x Combat XP against Wolves.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [5, 30] stats(AtkSpd 25,Ferocity 5,Strength 40,Speed 10) · L: [5, 30, 1.5] stats(AtkSpd 25,Ferocity 5,Strength 40,Speed 10)

#### Jade Dragon (`JADE_DRAGON`) — Foraging pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Foraging Fortune 25→50, Strength 25→50, Magic Find 5→10
- **Forest Power**: Adds 100 Strength and 50 Speed to all your Axes.
- **Jade Scale**: Grants 15 Foraging Fortune and 4 Sweep for every digit in your Mangrove Collection. (Max 10M collection)
- **Dragon's Pride**: Grants 1 Foraging Fortune per 5 Sweep.
- **Apex Predator**: Increase your total  Sweep by 0.1% for every Maxed out Attribute you unlocked.
- **Symbiosis**: If you own a level 200 Jade Dragon, grants +4 Foraging Fortune for every other unique maxed Foraging Pet that you own.
- Leveling: levels 101–200 (curve `101:200:1`); numbers shown are Lv100, Lv200 roughly double the scaling stat parts

#### Jellyfish (`JELLYFISH`) — Alchemy pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Health 2→200, Health Regen 1→100
- **Radiant Scyphozoa**: While in dungeons, reduces the mana cost of Power Orbs by 50%.
- **Stored Energy**: While in dungeons, for every 2,000 HP you heal teammates the cooldown of Wish is reduced by 1s, up to 30s.
- **Powerful Potions**: While in dungeons, increase the effectiveness of Dungeon Potions by 50%.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [50, 1] stats(Health 200,Health Regen 100) · L: [50, 1, 50] stats(Health 200,Health Regen 100)

#### Jerry (`JERRY`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Intelligence 1→100
- **Jerry**: Gain 50% chance to deal your regular damage
- **Jerry**: Gain 100% chance to receive a normal amount of drops from mobs
- **Jerry**: Actually adds 50 damage to the Aspect of the Jerry
- **Jerry**: Tiny chance to find Jerry Candies when killing mobs
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [] stats(Intelligence 100) · U: [] stats(Intelligence 100) · R: [] stats(Intelligence 100) · E: [] stats(Intelligence 100) · L: [10] stats(Intelligence 100) · M: [50] stats(Intelligence 100)

#### Kuudra (`KUUDRA`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Health 4→400, Strength 0.4→40
- **Crimson**: Grants 20% extra Crimson Essence.
- **Wither Bait**: Increases the odds of finding a vanquisher by 20%.
- **Kuudra Fortune**: Grants +100☘ Mining Fortune while on the Crimson Isle.
- **Trophy Bait**: Grants +20 Trophy Chance while on the Crimson Isle.
- **Kuudra Specialist**: Increases all damage to Kuudra and his minions by 20%.
- **This pet's perks are active even**: when the pet is not summoned!
- This pet automatically has Exp share!
- **This pet only gains XP on the**: Crimson Isle!
- * Soulbound *
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10, 10] stats(Health 400,Strength 40) · U: [15, 15] stats(Health 400,Strength 40) · R: [15, 15, 50] stats(Health 400,Strength 40) · E: [20, 20, 100, 20] stats(Health 400,Strength 40) · L: [20, 20, 100, 20, 20] stats(Health 400,Strength 40)

#### Lion (`LION`) — Foraging pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Speed 0.25→25, Strength 0.5→50, Ferocity 0.05→5
- **Primal Force**: Adds +20  Damage and +20  Strength to your weapons.
- **First Pounce**: First Strike, Triple-Strike, and Combo are 100% more effective.
- **King of the Jungle**: Deal +150%  Damage against mobs that have attacked you.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [3, 3] stats(Speed 25,Strength 50,Ferocity 5) · U: [5, 5] stats(Speed 25,Strength 50,Ferocity 5) · R: [10, 10, 75] stats(Speed 25,Strength 50,Ferocity 5) · E: [15, 15, 100] stats(Speed 25,Strength 50,Ferocity 5) · L: [20, 20, 100, 150] stats(Speed 25,Strength 50,Ferocity 5)

#### Magma Cube (`MAGMA_CUBE`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Defense 0.33→33.33, Health 0.5→50, Strength 0.2→20
- **Slimy Minions**: Slime and Magma Cube minions work 30% faster while on your island
- **Salt Blade**: Deal 25% more damage to slimes
- **Hot Ember**: Buffs the stats of Rekindled Ember Armor by 50%.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [20] stats(Defense 33.3,Health 50,Strength 20) · U: [25] stats(Defense 33.33,Health 50,Strength 20) · R: [25, 20] stats(Defense 33.33,Health 50,Strength 20) · E: [30, 25] stats(Defense 33.33,Health 50,Strength 20) · L: [30, 25, 50] stats(Defense 33.33,Health 50,Strength 20)

#### Mammoth (`MAMMOTH`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Defense 0.5→50, Cold Resistance 0.1→10
- **Wooly Coat**: Gain a 100% chance for mobs to not inflict ❄ Cold when damaging you in the Glacite Mineshafts.
- **Tusk Luck**: Gain +0.5 Magic Find for every 100  Mining Fortune, doubled in the Glacite Tunnels and Glacite Mineshafts.
- **Corpse Crusher**: Gain +30 Mining Fortune for each Frozen Corpse looted in your current Glacite Mineshaft.

#### Megalodon (`MEGALODON`) — Fishing pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Strength 0.5→50, Magic Find 0.1→10, Ferocity 5.05→10, Sea Creature Chance 5.05→10, Fishing Speed 10.3→40
- **Blood Scent**: Deal up to +150%  Damage based on the enemy's missing health
- **Enhanced Scales**: Doubles the pet's base stats during the Fishing Festival.
- **Feeding Frenzy**: Grants a +20% chance to catch Sharks during the Fishing Festival.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [150] stats(Strength 50,Magic Find 10,Ferocity 10,Sea Creature Chance 10,Fishing Speed 40) · L: [150, 20] stats(Strength 50,Magic Find 10,Ferocity 10,Sea Creature Chance 10,Fishing Speed 40)

#### Mithril Golem (`MITHRIL_GOLEM`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: True Defense 0.5→50, Mining Fortune 0.25→25
- **Mithril Affinity**: Grants +200 Mining Speed when mining Mithril.
- **Subterranean Battler**: Increases all Combat Stats by +20% on Mining Islands.
- **The Smell Of Powder**: Grants +20% ᠅ Mithril Powder from all sources.
- **Refined Senses**: Grants +10%  Magic Find while on Mining Islands.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [100] stats(True Defense 50,Mining Fortune 25) · U: [150] stats(True Defense 50,Mining Fortune 25) · R: [150, 10] stats(True Defense 50,Mining Fortune 25) · E: [200, 20] stats(True Defense 50,Mining Fortune 25) · L: [200, 20, 20] stats(True Defense 50,Mining Fortune 25) · M: [200, 20, 20, 10] stats(True Defense 50,Mining Fortune 25)

#### Mole (`MOLE`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Mining Speed 1.5→150, Intelligence 1→100, Magic Find 0.05→5
- **Archaeologist**: Scavenged Items appear 25% more often while in the Mines of Divan.
- **Magnetic Nose**: Automatons drop their parts 50% more frequently.
- **Nucleic Veins**: The Crystal Nucleus has a 100% chance to drop an additional item.

#### Monkey (`MONKEY`) — Foraging pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 0.5→50, Speed 0.2→20
- **Treeborn**: Grants +60  Foraging Fortune, which increases your chance at double logs.
- **Vine Swing**: Grants 100 Speed while in The Park.
- **Evolved Axes**: Grants +10 Sweep while in The Park.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [40] stats(Intelligence 50,Speed 20) · U: [50] stats(Intelligence 50,Speed 20) · R: [50, 75] stats(Intelligence 50,Speed 20) · E: [60, 100] stats(Intelligence 50,Speed 20) · L: [60, 100, 10] stats(Intelligence 50,Speed 20)

#### Mooshroom Cow (`MOOSHROOM_COW`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Farming Fortune 1→100, Health 1→100
- **Mushroom Eater**: You have a 100% chance to drop a Mushroom when farming crops.
- **Farming Strength**: Grants +0.7 Farming Fortune for every 20  Strength you have.
- **Bovine Blessing**: You have a 0.02% chance to find Tasty Cheese or Dung when farming crops.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50] stats(Farming Fortune 100,Health 100) · U: [100] stats(Farming Fortune 100,Health 100) · R: [100, 30] stats(Farming Fortune 100,Health 100) · E: [100, 20] stats(Farming Fortune 100,Health 100) · L: [100, 20, 0.02] stats(Farming Fortune 100,Health 100)

#### Mosquito (`MOSQUITO`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Bonus Pest Chance 0.5→50, Speed 0.2→20
- **Smooth Jazz**: Pest Vinyls are 50% more effective.
- **Buzzin' Barterer**: Gain +2 Sugar Cane Fortune for every unique visitor you've served in The Garden. Your Bonus: 1.7 Sugar Cane Fortune Capped at 175 Fortune
- **Bloodsucker's Betrayal**: When collected, Pest Traps will catch the next pest 20% faster.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [25] stats(Bonus Pest Chance 50,Speed 20) · U: [25] stats(Bonus Pest Chance 50,Speed 20) · R: [35, 1] stats(Bonus Pest Chance 50,Speed 20) · E: [50, 2] stats(Bonus Pest Chance 50,Speed 20) · L: [50, 2, 20] stats(Bonus Pest Chance 50,Speed 20)

#### Ocelot (`OCELOT`) — Foraging pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Speed 0.5→50, Ferocity 0.1→10
- **Foraging Wisdom Boost**: Grants +30☯ Foraging Wisdom.
- **Tree Hugger**: Foraging minions work 30% faster while on your island
- **Tree Essence**: Gain a 30% chance to get exp from breaking a log
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [20] stats(Speed 50,Ferocity 10) · U: [25] stats(Speed 50,Ferocity 10) · R: [25, 25] stats(Speed 50,Ferocity 10) · E: [30, 30] stats(Speed 50,Ferocity 10) · L: [30, 30, 30] stats(Speed 50,Ferocity 10)

#### Orchid Mantis (`ORCHID_MANTIS`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Speed 0.3→30, Overbloom 0.15→15
- **Intelligent Specimen**: Earn +20% more Farming Tool Exp.
- **Swift Sickles**: Convert every 3  Speed you have above 100 into +1 Farming Fortune.
- **Orchid Nectar**: You have a 0.02% chance to find Jelly or Plant Matter when farming crops.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Speed 30,Overbloom 15) · U: [20] stats(Speed 30,Overbloom 15) · R: [20, 0.5] stats(Speed 30,Overbloom 15) · E: [20, 1] stats(Speed 30,Overbloom 15) · L: [20, 1, 0.02] stats(Speed 30,Overbloom 15)

#### Owl (`OWL`) — Taming pet — rarities: Legendary
- **Training Refunds**: The more coins you spend on Fann's Sessions, the less coins they will cost in the future. (Max 5% off).
- **Next Milestone: 1% off**:                      0/1M
- **Efficient Trainer**: Makes training sessions at Fann more efficient when added into a session.
- **Fast Learner**: Passively grants +5☯ Taming Wisdom.
- **This pet's perks are active even**: when the pet is not summoned!

#### Parrot (`PARROT`) — Alchemy pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Crit Damage 0.1→10, Intelligence 1→100
- **Flamboyant**: Adds 20 levels to intimidation accessories
- **Repeat**: Boosts potions duration by 40%.
- **Bird Discourse**: Gives +30 Strength to players within 20 blocks Doesn't stack
- **Parrot Feather Infusion**: When summoned or in your Pets menu, boost the duration of consumed God Potions and Mixins by 20%.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [15, 40] stats(CDamage 10,Intelligence 100) · L: [20, 40, 30, 20] stats(CDamage 10,Intelligence 100)

#### Penguin (`PENGUIN`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Sea Creature Chance 0.1→10, Cold Resistance 0.15→15
- **Thick Blubber**: Each time you catch a Sea Creature, reduce your ❄ Cold by 5.
- **Chilly Reception**: Grants +1 Cold Resistance for each player within 30 blocks, up to 10 players.
- **Subzero Hero**: Grants +75 Fishing Speed while in the Glacite Tunnels.

#### Phoenix (`PHOENIX`) — Combat pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 51→150, Strength 10.5→60
- **Rekindle**: Before death, become immune and gain 20  Strength for 4 seconds 60s cooldown
- **Fourth Flare**: On 4th melee strike, ignite mobs, dealing 13x your  Crit Damage each second for 4 seconds
- **Magic Bird**: You may always fly on your private island and garden
- **Eternal Coins**: Don't lose coins from death.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [20, 4, 13, 4] stats(Intelligence 150,Strength 60) · L: [20, 4, 13, 4] stats(Intelligence 150,Strength 60)

#### Pig (`PIG`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Speed 0.15→15, Potato Fortune 0.2→20
- **Hamfisted**: Increases Coin gain from Shiny Pigs by 50%.
- **Shining Stampede**: Grants +5 Potato Fortune per Shiny Pig Bestiary tier.
- **Pig Parade**: Increases the base stats of this pet by 100% during the Year of the Pig.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [20] stats(Speed 15,Potato Fortune 20) · U: [40] stats(Speed 15,Potato Fortune 20) · R: [40, 4] stats(Speed 15,Potato Fortune 20) · E: [50, 5] stats(Speed 15,Potato Fortune 20) · L: [50, 5, 100] stats(Speed 15,Potato Fortune 20)

#### Pigman (`PIGMAN`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Ferocity 0.05→5, Defense 0.5→50, Strength 0.5→50
- **Bacon Farmer**: Pig minions work 30% faster while on your island
- **Pork Master**: Buffs the Pigman Sword by 40  Damage and 25  Strength.
- **Giant Slayer**: Deal +50% damage to monsters Level 50+ and +75% damage to monsters Level 100+.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Defense 50,Strength 50,Ferocity 5) · U: [20] stats(Defense 50,Strength 50,Ferocity 5) · R: [20, 30, 15] stats(Defense 50,Strength 50,Ferocity 5) · E: [30, 40, 25] stats(Ferocity 5,Defense 50,Strength 50) · L: [30, 40, 25] stats(Ferocity 5,Defense 50,Strength 50)

#### Precursor Drone (`PRECURSOR_DRONE`) — Combat pet — rarities: Common
- Stats @Common Lv1→Lv100: Sea Creature Chance 0.1→10, Mining Fortune 0.3→30, Foraging Fortune 0.3→30
- **Contraband**: Catching a Sea Creature has a 10% chance to also give you Treasure.
- **Grungle**: You can now ONLY throw your Foraging Axe, but it has no throwing penalty anymore.
- **Mining Off Camera**: While mining, each collection progress grants a 0.005% chance to drop a random enchanted mining item.

#### Rabbit (`RABBIT`) — Farming pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Health 1→100, Speed 0.2→20
- **Happy Feet**: Jump potions also give +50 speed
- **Farming Wisdom Boost**: Grants +30☯ Farming Wisdom.
- **Efficient Farming**: Farming minions work 30% faster while on your island
- **Chocolate Injections**: Increases Chocolate Factory production by +0.05x. Duplicate Chocolate Rabbits that you find grant +33% Chocolate.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [30] stats(Health 100,Speed 20) · U: [40] stats(Health 100,Speed 20) · R: [40, 25] stats(Health 100,Speed 20) · E: [50, 30] stats(Health 100,Speed 20) · L: [50, 30, 30] stats(Health 100,Speed 20) · M: [50, 30, 30, 0.05, 33] stats(Health 100,Speed 20)

#### Rat (`RAT`) — Combat pet — rarities: Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Crit Damage 0.25→25, Health 1.25→125, Strength 0.6→60
- Combat Morph
- Morph
- morph into it!
- **CHEESE!**: As a Rat, you smell CHEESE nearby! Yummy!
- **Rat's Blessing**: Has a chance to grant a random player +7 Magic Find for 60 seconds after finding a yummy piece of Cheese! If the player gets a drop during this buff, you have a 20% chance to get it too.
- **Extreme Speed**: The Rat is TWO times faster AND can fly!
- Ability numbers @Lv100 by rarity (in order of appearance above): L: [7, 60] stats(CDamage 25,Health 125,Strength 60) · M: [7, 60] stats(CDamage 25,Health 125,Strength 60)

#### Reindeer (`REINDEER`) — Fishing pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Health 1→100, Sea Creature Chance 0.05→5, Fishing Speed 0.25→25
- **Winter Sprint**: Gain double pet EXP.
- **Infused**: Gives +75 Fishing Speed and +5 Treasure Chance while on Jerry's Workshop.
- **Snow Power**: Grants +10% bonus gift chance during the Gift Attack event.
- **Icy Wind**: Grants +20% chance of getting double Ice Essence.

#### Rift Ferret (`RIFT_FERRET`) — ? pet — rarities: Epic
- Stats @Epic Lv1→Lv100: Speed 0.5→50, Intelligence 0.02→2
- **Orbs are Fun**: Gain +10% experience from XP Orbs.
- *** Soulbound ***: X Rift-Transferable X

#### Rock (`ROCK`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Defense 2→200, True Defense 0.1→10
- Mining Mount
- Ridable
- ride it!
- **Sailing Stone**: Sneak to move your rock to your location (15s cooldown)
- **Fortify**: While sitting on your rock, gain +25% defense
- **Steady Ground**: While sitting on your rock, deal +1.3x damage
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [] stats(Defense 200,True Defense 10) · U: [] stats(Defense 200,True Defense 10) · R: [20] stats(Defense 200,True Defense 10) · E: [25] stats(Defense 200,True Defense 10) · L: [25, 1.3] stats(Defense 200,True Defense 10)

#### Rose Dragon (`ROSE_DRAGON`) — Farming pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Farming Fortune 20.4→40, Speed 51→100
- **Garden Power**: Grants +3 Farming Fortune per Farming level.
- **Rosy Scales**: Grants +0.15 Farming Fortune and +0.1 Speed per Crop Milestone.
- **Dragon's Gluttony**: Grants +40 Overbloom.
- **Spiritual Perfection**: Gain 20% more Copper from Garden Visitors and from analyzing Mutations.
- **Symbiosis**: If you own a level 200 Rose Dragon, grants +3 Farming Fortune for every other unique maxed Farming Pet that you own.
- Leveling: levels 101–200 (curve `101:200:1`); numbers shown are Lv100, Lv200 roughly double the scaling stat parts

#### Scatha (`SCATHA`) — Mining pet — rarities: Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Mining Speed 1→100, Mining Fortune 1.25→125
- **Burrowing**: Grants a +50% chance to find Treasure Chests while mining.
- **Drill Infusion**: Grants +125☘ Gemstone Fortune to Drills.
- **Bejeweled Eyes**: Earn +20% Gemstone Powder from all sources.
- Ability numbers @Lv100 by rarity (in order of appearance above): R: [30] stats(Mining Speed 100,Mining Fortune 125) · E: [40, 100] stats(Mining Speed 100,Mining Fortune 125) · L: [50, 125, 20] stats(Mining Speed 100,Mining Fortune 125)

#### Seal (`SEAL`) — Fishing pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Fishing Speed 0.35→35, Sea Creature Chance 0.05→5, Treasure Chance 0.01→1
- **Showboater**: Increases your chance of catching Bouncy Beach Balls and Giant Bouncy Beach Balls during the Year of the Seal by 100%.
- **Peak Performance**: Gain a 5% chance to materialize some Treasure Bait in your inventory upon catching Treasure. Materializes Golden Bait instead during the Year of the Seal.
- **Amphibious**: Increases the base stats of this pet by 100% during the Year of the Seal.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50] stats(Fishing Speed 35,Sea Creature Chance 5,Treasure Chance 1) · U: [80] stats(Fishing Speed 35,Sea Creature Chance 5,Treasure Chance 1) · R: [80, 4] stats(Fishing Speed 35,Sea Creature Chance 5,Treasure Chance 1) · E: [100, 5] stats(Fishing Speed 35,Sea Creature Chance 5,Treasure Chance 1) · L: [100, 5, 100] stats(Fishing Speed 35,Sea Creature Chance 5,Treasure Chance 1)

#### Sheep (`SHEEP`) — Alchemy pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Ability Damage 0.2→20, Intelligence 1→100
- **Mana Saver**: Reduces the mana cost of abilities by 20%.
- **Overheal**: Gives a 10% shield after not taking damage for 10s
- **Dungeon Wizard**: Increases your total mana by 25% while in dungeons
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Ability Damage 20,Intelligence 100) · U: [15] stats(Ability Damage 20,Intelligence 100) · R: [15, 7.5] stats(Ability Damage 20,Intelligence 100) · E: [20, 10] stats(Ability Damage 20,Intelligence 100) · L: [20, 10, 25] stats(Ability Damage 20,Intelligence 100)

#### Silverfish (`SILVERFISH`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Defense 1→100, Mining Fortune 0.2→20
- **Magnetic**: Earn +50% more Exp when mining.
- **Experienced Burrower**: Grants +30☯ Mining Wisdom.
- **Dexterity**: Grants +150 Mining Speed and permanent Haste 3.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [30] stats(Defense 100,Mining Fortune 20) · U: [35] stats(Defense 100,Mining Fortune 20) · R: [40, 20] stats(Defense 100,Mining Fortune 20) · E: [50, 25] stats(Defense 100,Mining Fortune 20) · L: [50, 30, 150, 3] stats(Defense 100,Mining Fortune 20)

#### Skeleton (`SKELETON`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Crit Chance 0.15→15, Crit Damage 0.3→30
- **Bone Arrows**: Increase arrow damage by 75% which is doubled while in dungeons
- **Combo**: Gain a combo stack for every bow hit granting +3  Strength. Max 20 stacks, stacks disappear after 8 seconds.
- **Skeletal Defense**: Your skeleton shoots an arrow dealing 30x your  Crit Damage when a mob gets close to you (5s cooldown).
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [15] stats(CChance 15,CDamage 30) · U: [20] stats(CChance 15,CDamage 30) · R: [35, 15] stats(CChance 15,CDamage 30) · E: [50, 17] stats(CChance 15,CDamage 30) · L: [75, 20] stats(CChance 15,CDamage 30)

#### Skeleton Horse (`SKELETON_HORSE`) — Combat pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 0.75→75, Speed 1.5→150
- **High Stride**: Grants permanent Jump Boost IV.
- **Stampede**: Mob kills grant a stack of +1  Attack Speed and +10  Strength for 5s. (Max 20 stacks)
- **Trample**: After falling 20 or more blocks, absorb your fall damage and deal 7% of your weapon's  Damage for every block fallen to mobs within 3 blocks.

#### Sloth (`SLOTH`) — Foraging pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Sweep 0.25→25, Foraging Fortune 0.25→25
- **Slow Start**: Every 60s, your next cut gains +25 Sweep and +50 Foraging Fortune.
- **Stronk Arm**: Gains +40 Sweep and +100 Foraging Fortune on Axe throws.
- **Starlyn's Favorite**: Cutting Trees give +20% more points towards Starlyn Contests.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [15, 30] stats(Sweep 25,Foraging Fortune 25) · U: [20, 40] stats(Sweep 25,Foraging Fortune 25) · R: [20, 40, 20, 50] stats(Sweep 25,Foraging Fortune 25) · E: [25, 50, 40, 100] stats(Sweep 25,Foraging Fortune 25) · L: [25, 50, 40, 100, 20] stats(Sweep 25,Foraging Fortune 25)

#### Slug (`SLUG`) — Farming pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Defense 0.2→20, Intelligence 0.25→25
- **Slow and Steady**: When fishing in the Crimson Isle, Slugfish take 50% less time to catch.
- **Pest Friends**: Grants +40 Bonus Pest Chance.
- **Repugnant Aroma**: When farming in a plot affected by a Sprayonator, gain +100 Farming Fortune.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [50, 40] stats(Defense 20,Intelligence 25) · L: [50, 40, 100] stats(Defense 20,Intelligence 25)

#### Snail (`SNAIL`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 1→100, Defense 1→100
- **Red Sand Enjoyer**: Red Sand Minions work 30% faster while on your Private Island.
- **Slow and Steady**: Convert every 2 Speed you have above 100 into +1 Block Fortune.
- **Slimy Reach**: Grants +400 Mining Spread while mining Blocks.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Intelligence 100,Defense 100) · U: [20] stats(Intelligence 100,Defense 100) · R: [20, 3] stats(Intelligence 100,Defense 100) · E: [30, 3] stats(Intelligence 100,Defense 100) · L: [30, 2, 400] stats(Intelligence 100,Defense 100)

#### Snowman (`SNOWMAN`) — Combat pet — rarities: Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Crit Damage 0.25→25, Damage 0.25→25, Strength 0.25→25
- Fight alongside your pet Snowman!
- **Blizzard**: Enemies within 16 blocks are slowed by 25% and deal 10% less damage.
- **Frostbite**: Your freezing aura slows enemy attacks causing you to take 10% reduced damage
- **Snow Cannon**: Shoots a snowball towards an enemy when you attack dealing 20% of your last dealt melee damage, capped at 200,000. (1s cooldown)
- **Ouch!**: Your snowballs have 50% chance of dealing double damage!
- Ability numbers @Lv100 by rarity (in order of appearance above): L: [16, 10, 10, 20] stats(CDamage 25,Damage 25,Strength 25) · M: [16, 10, 10, 20] stats(CDamage 25,Damage 25,Strength 25)

#### Spider (`SPIDER`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Crit Chance 0.5→10, Strength 0.1→50
- **One With The Spider**: Applies +15 Strength to all Arachnal  weapons, armor, and equipment you have equipped.
- **Web-weaver**: Upon hitting a monster, it becomes slowed by 40%.
- **Spider Whisperer**: Spider, Cave Spider and Tarantula minions work 30% faster while on your island.
- **Web Battlefield**: Killing mobs grants +0.06 Strength and +0.01 Magic Find for 40s to all players staying within 20 blocks of where they died. Stacks up to 10 times.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [3] stats(CChance 10,Strength 50) · U: [6] stats(CChance 10,Strength 50) · R: [9, 40] stats(CChance 10,Strength 50) · E: [12, 40] stats(CChance 10,Strength 50) · L: [15, 40, 30] stats(CChance 10,Strength 50) · M: [15, 40, 30] stats(CChance 10,Strength 50)

#### Spinosaurus (`SPINOSAURUS`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Fishing Speed 0.5→50, Sea Creature Chance 0.05→5, Trophy Chance 0.05→5
- **Sharp Attitude**: Sea Creatures spawn with 10% of their maximum health missing.
- **Pursuit**: Increases the chance of catching GOLD and DIAMOND tier Trophy Fish by 10%.
- **Primordial Fisher**: Increases this pet's base stats by 50% during rain.

#### Spirit (`SPIRIT`) — Combat pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 1→100, Speed 0.3→30
- **Spirit Assistance**: Spawns and assists you when you are a ghost in Dungeons.
- **Spirit Cooldowns**: Reduces the cooldown of your ghost abilities in dungeons by 50%.
- **Half Life**: If you are the first player to die in a dungeon, the score penalty for that death is reduced to 1.
- **This pet's perks are active even**: when the pet is not summoned!
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [50] stats(Intelligence 100,Speed 30) · L: [50] stats(Intelligence 100,Speed 30)

#### Squid (`SQUID`) — Fishing pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Health 0.5→50, Intelligence 0.5→50
- **More Ink**: Gain a 100% chance to get double drops from squids
- **Ink Specialty**: Buffs the Ink Wand by 40  Damage and 20  Strength.
- **Fishing Wisdom Boost**: Grants +30☯ Fishing Wisdom.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50] stats(Health 50,Intelligence 50) · U: [75] stats(Health 50,Intelligence 50) · R: [75, 30, 15] stats(Health 50,Intelligence 50) · E: [100, 40, 20] stats(Health 50,Intelligence 50) · L: [100, 40, 20, 30] stats(Health 50,Intelligence 50)

#### Subzero Wisp (`SUBZERO_WISP`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Health 6→600, True Defense 0.35→35, Intelligence 2.5→250, Damage 0.25→25
- Gabagool Pet, feed to gain XP
- **Icehammer**: Lets you break fire pillars 2x faster, healing you for 50% of your max ❤ over 3s.
- **Bulwark**: Kill Blazes to gain defense against them and demons. Bonus: +0 & +0 Next Upgrade: +30 & +3 (0/100)
- **Blaze Slayer**: Gain 1.5x Combat XP against Blazes.
- **Extinguish**: While in combat on the Crimson Isle, spawn a pool every 8s. Bathing in it heals 25%❤ now and 10%❤/s for 8s.
- **Ephemeral Stability**: Regenerate mana 40% faster

#### Tarantula (`TARANTULA`) — Combat pet — rarities: Epic, Legendary, Mythic
- Stats @Mythic Lv1→Lv100: Crit Chance 0.1→10, Crit Damage 0.3→30, Strength 0.1→10
- **Webbed Cells**: Tarantula Broodfather's  Vitality reduction is 30% less effective against you.
- **Eight Legs**: Decreases the mana cost of Spider, Tarantula, Primordial and Spirit Boots by 50%.
- **Arachnid Slayer**: Gain 1.5x Combat XP against Spiders.
- **Web Battlefield**: Killing mobs grants +0.06 Strength and +0.01 Magic Find for 40s to all players staying within 20 blocks of where they died. Stacks up to 10 times.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [30, 50] stats(CChance 10,CDamage 30,Strength 10) · L: [30, 50, 1.5] stats(CChance 10,CDamage 30,Strength 10) · M: [30, 50, 1.5] stats(CChance 10,CDamage 30,Strength 10)

#### Tiger (`TIGER`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Ferocity 0.25→25, Crit Chance 0.05→5, Crit Damage 0.5→50, Strength 5.1→15
- **Merciless Swipe**: Gain +30%  Ferocity.
- **Hemorrhage**: Melee attacks reduce healing by 55% for 10s.
- **Apex Predator**: Deal +100% damage against targets with no other mobs within 15 blocks.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(Ferocity 25,CChance 5,CDamage 50,Strength 15) · U: [20] stats(Ferocity 25,CChance 5,CDamage 50,Strength 15) · R: [20, 30] stats(Ferocity 25,CChance 5,CDamage 50,Strength 15) · E: [30, 55] stats(Ferocity 25,CChance 5,CDamage 50,Strength 15) · L: [30, 55, 100] stats(Ferocity 25,CChance 5,CDamage 50,Strength 15)

#### Turtle (`TURTLE`) — Combat pet — rarities: Epic, Legendary
- Stats @Legendary Lv1→Lv100: Defense 100.5→150, Health 50.25→75, True Defense 0.15→15
- **Turtle Tactics**: Gain +30%  Defense and an additional +10%  Defense when standing still.
- **Genius Amniote**: Grants +2.5%  Defense to 4 players within 50 blocks of you.
- **Unflippable**: Gain immunity to knockback
- **Turtle Shell**: When under 40% maximum HP, you take 25% less damage. Gain +20 Vitality after taking 10 hits.
- Ability numbers @Lv100 by rarity (in order of appearance above): E: [30, 2.5] stats(Defense 150,Health 75,True Defense 15) · L: [30, 2.5, 25, 20] stats(Defense 150,Health 75,True Defense 15)

#### T-Rex (`TYRANNOSAURUS`) — ? pet — rarities: Legendary
- Stats @Legendary Lv1→Lv100: Strength 0.75→75, Crit Chance 0.05→5, Ferocity 0.25→25
- **Close Combat**: Deal 100% more damage to enemies within 1.5 blocks.
- **Ferocious Roar**: Attacks have a 10% chance to stun the target (10s cooldown).
- **Tyrant**: Combat stats granted by pet items on this pet are increased by 100%.

#### Witch (`WITCH`) — Alchemy pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Intelligence 1→100, Alchemy Wisdom 0.05→5
- **Toil and Trouble**: Increases your chance of dropping Ingredients during the Year of the Witch by 100%.
- **Alchemism**: Reduces how long Potions take to brew by 50%.
- **Witching Hour**: Increases the base stats of this pet by 100% during the Year of the Witch.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [50] stats(Intelligence 100,Alchemy Wisdom 5) · U: [75] stats(Intelligence 100,Alchemy Wisdom 5) · R: [75, 40] stats(Intelligence 100,Alchemy Wisdom 5) · E: [100, 50] stats(Intelligence 100,Alchemy Wisdom 5) · L: [100, 50, 100] stats(Intelligence 100,Alchemy Wisdom 5)

#### Wither Skeleton (`WITHER_SKELETON`) — Mining pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Crit Chance 0.05→5, Crit Damage 0.25→25, Defense 0.25→25, Intelligence 0.25→25, Strength 0.25→25
- **Stronger Bones**: Take 30% less damage from  Skeletal mobs.
- **Wither Blood**: Deal 125% more damage to  Wither mobs.
- **Death's Touch**: Upon hitting an enemy inflict the wither effect for 200% damage over 3 seconds Does not stack
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(CChance 5,CDamage 25,Defense 25,Intelligence 25,Strength 25) · U: [20] stats(CChance 5,CDamage 25,Defense 25,Intelligence 25,Strength 25) · R: [20, 100] stats(CChance 5,CDamage 25,Defense 25,Intelligence 25,Strength 25) · E: [30, 125] stats(CChance 5,CDamage 25,Defense 25,Intelligence 25,Strength 25) · L: [30, 125, 200] stats(CChance 5,CDamage 25,Defense 25,Intelligence 25,Strength 25)

#### Wolf (`WOLF`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Crit Damage 0.1→10, Health 0.5→50, Speed 0.2→20, True Defense 0.1→10
- **Alpha Dog**: Take 30% less damage from wolves
- **Pack Leader**: Gain 15  Crit Damage for every nearby wolf monster Max 10 wolves
- **Combat Wisdom Boost**: Grants +30☯ Combat Wisdom.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [10] stats(CDamage 10,Health 50,Speed 20,True Defense 10) · U: [20] stats(CDamage 10,Health 50,Speed 20,True Defense 10) · R: [20, 10] stats(CDamage 10,Health 50,Speed 20,True Defense 10) · E: [30, 15] stats(CDamage 10,Health 50,Speed 20,True Defense 10) · L: [30, 15, 30] stats(CDamage 10,Health 50,Speed 20,True Defense 10)

#### Zombie (`ZOMBIE`) — Combat pet — rarities: Common, Uncommon, Rare, Epic, Legendary
- Stats @Legendary Lv1→Lv100: Crit Damage 0.3→30, Health 1→100
- **Bite Shield**: Reduce the damage taken from zombies by 25%.
- **Rotten Blade**: Deal 150% more damage to  Undead mobs
- **Living Dead**: Increases all stats on Undead ༕ armor by 25%.
- Ability numbers @Lv100 by rarity (in order of appearance above): C: [15] stats(CDamage 30,Health 100) · U: [20] stats(CDamage 30,Health 100) · R: [20, 150] stats(CDamage 30,Health 100) · E: [25, 150] stats(CDamage 30,Health 100) · L: [25, 150, 25] stats(CDamage 30,Health 100)


## Appendix G — All tools (pickaxes, drills, axes, farming tools, rods, rod parts, vacuums, cans, chisels)

- **Chum Sinker** (`CHUM_SINKER`, C) — - — req: Fishing 5 — recipe: -
  - Materializes Chum in your inventory whenever you catch something. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **Common Hook** (`COMMON_HOOK`, C) — - — req: Fishing 10 — recipe: craft: 2x ENCHANTED_WATER_LILY, 1x ENCHANTED_RAW_FISH
  - Increases the chance to catch // Talk to Roddy in the Backwater Bayou to apply this Hook to a Fishing Rod.
- **Festive Sinker** (`FESTIVE_SINKER`, C) — - — req: Fishing 5 — recipe: -
  - Grants a small chance to catch Gifts during the Season of Jerry. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **HydroCan™ 1000** (`HYDRO_CAN_1000`, C) — - — req: - — recipe: -
  - Use it on plants in the Greenhouse to keep them watered. // Ability: Hydrate  HOLD RIGHT CLICK Aim and hold in the direction of a plant to water it. Dispenses 150 water per second. // Ability: Fill  RIGHT CLICK When aiming at a water source, fills your can back up with water. // Water: 0/3k
- **Icy Sinker** (`ICY_SINKER`, C) — - — req: Fishing 5 — recipe: -
  - Materializes Ice in your inventory whenever you catch something. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **Prismarine Sinker** (`PRISMARINE_SINKER`, C) — - — req: Fishing 5 — recipe: craft: 16x WATER_LILY, 8x PRISMARINE_CRYSTALS, 8x PRISMARINE_SHARD
  - Materializes Prismarine Shard or Prismarine Crystals in your inventory whenever you catch something. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **SkyMart Vacuum** (`SKYMART_VACUUM`, C) — FarmFort +5, Dmg +100 — req: Garden 5 — recipe: -
  - Ability: Vacuum  HOLD RIGHT CLICK Deals 100  Damage per second to nearby  Pests. // Ability: Pest Tracker  LEFT CLICK Leads you in the direction of the nearest  Pest. Cooldown: 1s // Vacuum Bag: 0 Pests Vacuum  Pests in your Garden to increase!
- **Sponge Sinker** (`SPONGE_SINKER`, C) — - — req: Fishing 5 — recipe: craft: 16x WATER_LILY, 8x SPONGE
  - Materializes Sponge in your inventory whenever you catch something. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **Worn Huntaxe - Genesis** (`VENATOR_GENESIS`, C) — Dmg +5 — req: - — recipe: craft: 3x VINESAP, 2x STRETCHING_STICKS
  - Hunting Tool // Ability: Absorptio  RIGHT CLICK This weapon lets you absorb 5% of the basic combat stats of any weapon. // Ability: Vis Temperata Each strike of this weapon has its damage capped at 15% of the enemy's max Health. This weapon cannot cause a fatal blow. // Ex tenebris, primus venator surgit.
- **Bandaged Mithril Pickaxe** (`BANDAGED_MITHRIL_PICKAXE`, U) — BP +5, Dmg +30, MinSpd +250, MinFort +4 — req: HotM 2 — recipe: -
  - Breaking Power 5
- **Basic Gardening Axe** (`BASIC_GARDENING_AXE`, U) — FarmFort +10 — req: Garden 4 — recipe: -
- **Basic Gardening Hoe** (`BASIC_GARDENING_HOE`, U) — FarmFort +10 — req: Garden 1, Farming 5 — recipe: -
- **Cactus Knife Mk. I** (`CACTUS_KNIFE`, U) — - — req: Farming 10, Garden 7 — recipe: -
  - Farming Tool // Slices through cactus instantly! // Ability: Swift Slicer Increases your speed cap by 100 while in The Garden. // Level 1 -> 2   (0/1k) 0%
- **Carnival Rod** (`CARNIVAL_FISHING_ROD`, U) — - — req: - — recipe: -
  - Used to catch fish in the Catch a Fish game. // * Soulbound *
- **Challenging Rod** (`CHALLENGE_ROD`, U) — Dmg +75, Str +75, FishSpd +35, SCC +2, Treasure +0.5 — req: Fishing 5 — recipe: -
  - ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Cocoa Chopper Mk. I** (`COCO_CHOPPER`, U) — - — req: Farming 10, Garden 8 — recipe: -
  - Farming Tool // Cocoa Beans Fortune: +4 // Level 1 -> 2   (0/1k) 0%
- **Diamond Axe** (`DIAMOND_AXE`, U) — Dmg +30 — req: - — recipe: craft: 3x DIAMOND, 2x STICK
- **Diamond Hoe** (`DIAMOND_HOE`, U) — - — req: - — recipe: craft: 2x DIAMOND, 2x STICK
- **Diamond Pickaxe** (`DIAMOND_PICKAXE`, U) — BP +4, Dmg +30, MinSpd +220 — req: - — recipe: craft: 3x DIAMOND, 2x STICK
  - Breaking Power 4
- **Dirt Rod** (`DIRT_ROD`, U) — FishSpd +20 — req: - — recipe: -
  - This rod allows you to fish on dirt. // At least you won't get wet! // Purchased by: [MVP+] Moulberry Purchased for: 666,666,666  coins // Auction #1 Bid #1 July 2022 // ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Eclipse Sickle Mk. I** (`THEORETICAL_HOE_SUNFLOWER_1`, U) — - — req: Farming 10, Garden 11 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Efficient Axe** (`EFFICIENT_AXE`, U) — Dmg +25 — req: - — recipe: -
  - Drops 5 Planks from chopping Logs.
- **Euclid's Wheat Sickle Mk. I** (`THEORETICAL_HOE_WHEAT_1`, U) — - — req: Farming 10, Garden 1 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Farmer's Rod** (`FARMER_ROD`, U) — Dmg +50, Str +20, FishSpd +60 — req: Fishing 8 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Fig Hew** (`FIG_AXE`, U) — Dmg +30, Sweep +15, ForFort +20, Str +10 — req: Foraging 12 — recipe: craft: 30x ENCHANTED_FIG_LOG, 8x STRETCHING_STICKS
  - Ability: Frenzy Gains 1 Sweep every 2,000 Logs cut on Galatea. (max 20) Current Bonus: +0 Next Upgrade: +1 (0/2,000) // Logs Cut: 0
- **Fractured Mithril Pickaxe** (`FRACTURED_MITHRIL_PICKAXE`, U) — BP +5, Dmg +30, MinSpd +225, MinFort +2 — req: - — recipe: -
  - Breaking Power 5
- **Fungi Cutter Mk. I** (`FUNGI_CUTTER`, U) — - — req: Farming 10, Garden 9 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Gauss Carrot Shovel Mk. I** (`THEORETICAL_HOE_CARROT_1`, U) — - — req: Farming 10, Garden 2 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Ghost Axe** (`GHOST_THROWING_AXE`, U) — - — req: - — recipe: -
- **HydroCan™ Turbo 2000** (`HYDRO_CAN_TURBO_2000`, U) — - — req: - — recipe: -
  - Use it on plants in the Greenhouse to keep them watered. // Ability: Hydrate  HOLD RIGHT CLICK Aim and hold in the direction of a plant to water it. Dispenses 200 water per second. // Ability: Fill  RIGHT CLICK When aiming at a water source, fills your can back up with water. // Water: 0/5k
- **Jungle Pickaxe** (`JUNGLE_PICKAXE`, U) — BP +5, Dmg +50, MinSpd +330 — req: HotM 4 — recipe: -
  - Breaking Power 5 // Gives a chance to drop Sludge Juice when mining in the Jungle of the Crystal Hollows.
- **Junk Sinker** (`JUNK_SINKER`, U) — - — req: Fishing 5 — recipe: -
  - Grants +10 Treasure Chance while in the Backwater Bayou but replaces all Treasure catches with Junk! // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **Lapis Pickaxe** (`LAPIS_PICKAXE`, U) — BP +4, Dmg +20, MinSpd +200 — req: - — recipe: craft: 512x INK_SACK-4, 1x IRON_PICKAXE
  - Breaking Power 4 // Grants a 25% chance to gain double experience when mining ores.
- **Melon Dicer Mk. I** (`MELON_DICER`, U) — - — req: Farming 10, Garden 6 — recipe: -
  - Farming Tool // Melon Slice Fortune: +4 // Level 1 -> 2   (0/1k) 0%
- **Mithril Pickaxe** (`MITHRIL_PICKAXE`, U) — BP +5, Dmg +40, MinSpd +300, MinFort +7 — req: HotM 2 — recipe: -
  - Breaking Power 5
- **Newton Nether Wart Cutter Mk. I** (`THEORETICAL_HOE_WARTS_1`, U) — - — req: Farming 10, Garden 10 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Promising Axe** (`PROMISING_AXE`, U) — Dmg +25 — req: - — recipe: craft: 3x IRON_BLOCK, 2x STICK
  - Gains Efficiency I after breaking 50 blocks! // Blocks Broken: 0
- **Promising Hoe** (`PROMISING_HOE`, U) — - — req: - — recipe: -
- **Promising Pickaxe** (`PROMISING_PICKAXE`, U) — BP +3, Dmg +20, MinSpd +50 — req: - — recipe: craft: 3x IRON_BLOCK, 2x STICK
  - Breaking Power 3 // Ability: Stored Potential Grants +10 Mining Speed for every 100 blocks mined. (Max +250 Mining Speed) // Blocks Mined: 0
- **Pumpkin Dicer Mk. I** (`PUMPKIN_DICER`, U) — - — req: Farming 10, Garden 4 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Pythagorean Potato Shovel Mk. I** (`THEORETICAL_HOE_POTATO_1`, U) — - — req: Farming 10, Garden 3 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Rusty Titanium Pickaxe** (`RUSTY_TITANIUM_PICKAXE`, U) — BP +5, Dmg +55, MinSpd +100 — req: - — recipe: -
  - Breaking Power 5 // Grants +25 Mining Fortune when mining Titanium Ore.
- **Seriously Damaged Axe** (`SERIOUSLY_DAMAGED_AXE`, U) — ForFort +5, Sweep +10, Dmg +10 — req: Foraging 12 — recipe: -
  - Ability: Throwing Axe  RIGHT CLICK While on Foraging Islands, throw your Axe at a tree! It will sweep 50% of the logs it normally would! Cooldown: 1s
- **Sharpened Huntaxe - Dominus** (`SILVA_DOMINUS`, U) — Dmg +10 — req: - — recipe: craft: 48x VINESAP, 1x VENATOR_GENESIS, 8x STRETCHING_STICKS
  - Hunting Tool // Ability: Absorptio  RIGHT CLICK This weapon lets you absorb 10% of the basic combat stats of any weapon. // Ability: Vis Temperata Each strike of this weapon has its damage capped at 20% of the enemy's max Health. This weapon cannot cause a fatal blow. // Silva vocat, Dominus respondet.
- **SkyMart Turbo Vacuum** (`SKYMART_TURBO_VACUUM`, U) — FarmFort +10, Dmg +150 — req: Garden 5 — recipe: -
  - Ability: Vacuum  HOLD RIGHT CLICK Deals 150  Damage per second to nearby  Pests. // Ability: Pest Tracker  LEFT CLICK Leads you in the direction of the nearest  Pest. Cooldown: 1s // Vacuum Bag: 0 Pests Vacuum  Pests in your Garden to increase!
- **Speedster Rod** (`SPEEDSTER_ROD`, U) — Dmg +30, Str +15, FishSpd +45 — req: Fishing 6 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod! // * Co-op Soulbound *
- **Speedy Line** (`SPEEDY_LINE`, U) — - — req: Fishing 5 — recipe: -
  - Grants +10 Fishing Speed. // Talk to Roddy in the Backwater Bayou to apply this Line to a Fishing Rod.
- **Spruce Axe** (`JUNGLE_AXE`, U) — Sweep +4 — req: - — recipe: craft: 96x LOG-1, 2x STICK
  - Ability: Throwing Axe  RIGHT CLICK While on Foraging Islands, throw your Axe at a tree! It will sweep 50% of the logs it normally would! Cooldown: 1s // Ability: Park Enjoyer Gain +50 Foraging Fortune while in The Park.
- **Starter Lava Rod** (`STARTER_LAVA_ROD`, U) — Dmg +50, Str +50, FishSpd +10, TrophyCh +5 — req: Fishing 10 — recipe: -
  - Lava Rod // Trophy Chance: +5% // ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod. // * Co-op Soulbound *
- **Stingy Sinker** (`STINGY_SINKER`, U) — - — req: Fishing 5 — recipe: -
  - Grants a 10% chance to not consume Bait. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **Sweet Axe** (`SWEET_AXE`, U) — Dmg +25 — req: - — recipe: -
  - 20% chance of dropping an Apple when chopping logs.
- **Turing Sugar Cane Cutter Mk. I** (`THEORETICAL_HOE_CANE_1`, U) — - — req: Farming 10, Garden 5 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Wild Rose Cutter Mk. I** (`THEORETICAL_HOE_WILD_ROSE_1`, U) — - — req: Farming 10, Garden 12 — recipe: -
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Advanced Gardening Axe** (`ADVANCED_GARDENING_AXE`, R) — FarmFort +15 — req: Garden 6 — recipe: -
  - Replenish I Upon breaking crops, nether wart, cocoa, wild rose, or sunflower, automatically replant from materials in your inventory.
- **Advanced Gardening Hoe** (`ADVANCED_GARDENING_HOE`, R) — FarmFort +15 — req: Garden 3, Farming 7 — recipe: -
  - Replenish I Upon breaking crops, nether wart, cocoa, wild rose, or sunflower, automatically replant from materials in your inventory.
- **Cactus Knife Mk. II** (`CACTUS_KNIFE_2`, R) — - — req: Farming 15, Garden 7 — recipe: craft: 64x JACOBS_TICKET, 32x ENCHANTED_CACTUS, 1x CACTUS_KNIFE
  - Farming Tool // Slices through cactus instantly! // Ability: Swift Slicer Increases your speed cap by 100 while in The Garden. // Level 1 -> 2   (0/1k) 0%
- **Chum Rod** (`CHUM_ROD`, R) — Dmg +55, Str +40, SCC +2, FishSpd +70 — req: Fishing 5 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Cocoa Chopper Mk. II** (`COCO_CHOPPER_2`, R) — - — req: Farming 15, Garden 8 — recipe: craft: 64x JACOBS_TICKET, 40x ENCHANTED_COOKIE, 1x COCO_CHOPPER
  - Farming Tool // Cocoa Beans Fortune: +4 // Level 1 -> 2   (0/1k) 0%
- **Decent Axe** (`DECENT_AXE`, R) — ForFort +5, Sweep +24, Dmg +20 — req: Foraging 15 — recipe: -
  - Ability: Throwing Axe  RIGHT CLICK While on Foraging Islands, throw your Axe at a tree! It will sweep 50% of the logs it normally would! Cooldown: 1s
- **Eclipse Sickle Mk. II** (`THEORETICAL_HOE_SUNFLOWER_2`, R) — - — req: Farming 15, Garden 11 — recipe: craft: 64x JACOBS_TICKET, 20x COMPACTED_MOONFLOWER, 20x COMPACTED_SUNFLOWER, 1x THEORETICAL_HOE_SUNFLOWER_1
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Euclid's Wheat Sickle Mk. II** (`THEORETICAL_HOE_WHEAT_2`, R) — - — req: Farming 15, Garden 1 — recipe: craft: 64x JACOBS_TICKET, 20x ENCHANTED_HAY_BALE, 1x THEORETICAL_HOE_WHEAT_1
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Figstone Splitter** (`FIGSTONE_AXE`, R) — Dmg +50, Sweep +25, ForFort +35, Str +15 — req: Foraging 15 — recipe: craft: 4x FIGSTONE, 1x FIG_AXE
  - Ability: Super Frenzy Gains 1 Sweep every 10,000 Logs cut on Galatea. (max 20) Current Bonus: +0 Next Upgrade: +1 (0/10,000) // Logs Cut: 0
- **Fungi Cutter Mk. II** (`FUNGI_CUTTER_2`, R) — - — req: Farming 15, Garden 9 — recipe: craft: 64x JACOBS_TICKET, 10x ENCHANTED_HUGE_MUSHROOM_2, 10x ENCHANTED_HUGE_MUSHROOM_1, 1x FUNGI_CUTTER
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Gauss Carrot Shovel Mk. II** (`THEORETICAL_HOE_CARROT_2`, R) — - — req: Farming 15, Garden 2 — recipe: craft: 64x JACOBS_TICKET, 64x ENCHANTED_GOLDEN_CARROT, 1x THEORETICAL_HOE_CARROT_1
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Gemstone Drill LT-522** (`GEMSTONE_DRILL_2`, R) — BP +8, Dmg +75, MinSpd +300, GemFort +80, Pristine +1 — req: HotM 4 — recipe: forge (0.008333h): GEMSTONE_DRILL_1:1, GEMSTONE_MIXTURE:3
  - Breaking Power 8 // Grants +10% Gemstone Powder, and +800 Mining Speed when mining // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Hotspot Sinker** (`HOTSPOT_SINKER`, R) — - — req: Fishing 20 — recipe: craft: 16x SEARED_ESCARGOT, 8x SINGED_POWDER, 1x ENCHANTED_CLAY_BLOCK
  - Increases the bonuses of Fishing Hotspots by 100%. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **HydroCan™ Ultra 3000** (`HYDRO_CAN_ULTRA_3000`, R) — - — req: - — recipe: -
  - Use it on plants in the Greenhouse to keep them watered. // Ability: Hydrate  HOLD RIGHT CLICK Aim and hold in the direction of a plant to water it. Dispenses 250 water per second. // Ability: Fill  RIGHT CLICK When aiming at a water source, fills your can back up with water. // Ability: Aquaburst Spreads water in a larger area at once. // Water: 0/10k
- **Ice Rod** (`ICE_ROD`, R) — Dmg +30, FishSpd +15 — req: Fishing 3 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Lotus Sinker** (`LOTUS_SINKER`, R) — - — req: Fishing 15, Lotus coll 5 — recipe: craft: 32x ENCHANTED_WATER_LILY, 16x LOTUS_SILVER
  - Materializes Lotus in your inventory whenever you catch something. // Talk to Roddy in the Backwater Bayou to apply this Sinker to a Fishing Rod.
- **Magma Rod** (`MAGMA_ROD`, R) — Dmg +175, Str +175, FishSpd +40, SCC +6, TrophyCh +15 — req: Fishing 27 — recipe: craft: 3x STICK, 10x MAGMA_FISH_SILVER
  - Lava Rod // Trophy Chance: +15% // ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Melon Dicer Mk. II** (`MELON_DICER_2`, R) — - — req: Farming 15, Garden 6 — recipe: craft: 64x JACOBS_TICKET, 64x ENCHANTED_MELON_BLOCK, 1x MELON_DICER
  - Farming Tool // Melon Slice Fortune: +4 // Same as the Melon Dicer Mk. I, just better! // Level 1 -> 2   (0/1k) 0%
- **Mithril Drill SX-R226** (`MITHRIL_DRILL_1`, R) — BP +5, Dmg +65, MinSpd +450, MinFort +15 — req: HotM 2 — recipe: forge (4h): REFINED_MITHRIL:3, DRILL_ENGINE:1, FUEL_TANK:1
  - Breaking Power 5 // Grants +20% Mithril Powder. // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Mithril Drill SX-R326** (`MITHRIL_DRILL_2`, R) — BP +6, Dmg +65, MinSpd +600, MinFort +20 — req: HotM 3 — recipe: forge (0.008333h): GOLDEN_PLATE:1, MITHRIL_DRILL_1:1, MITHRIL_PLATE:1
  - Breaking Power 6 // Grants +40% Mithril Powder. // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Moby's Shears** (`MOBYS_SHEARS`, R) — - — req: - — recipe: -
  - Collects +1 Glowing Mushroom!
- **Newton Nether Wart Cutter Mk. II** (`THEORETICAL_HOE_WARTS_2`, R) — - — req: Farming 15, Garden 10 — recipe: craft: 64x JACOBS_TICKET, 60x MUTANT_NETHER_STALK, 1x THEORETICAL_HOE_WARTS_1
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Polished Titanium Pickaxe** (`REFINED_TITANIUM_PICKAXE`, R) — BP +6, Dmg +65, MinSpd +200 — req: HotM 4 — recipe: -
  - Breaking Power 6 // Grants +100 Mining Fortune when mining Titanium Ore.
- **Puddle Jumper Hook** (`PUDDLE_JUMPER_HOOK`, R) — - — req: Fishing 25, Lotus coll 8 — recipe: craft: 2x LOTUS_GOLD, 32x ENCHANTED_RAW_FISH
  - Increases the chance to catch a Puddle Jumper by 400%. // Talk to Roddy in the Backwater Bayou to apply this Hook to a Fishing Rod.
- **Pumpkin Dicer Mk. II** (`PUMPKIN_DICER_2`, R) — - — req: Farming 15, Garden 4 — recipe: craft: 64x JACOBS_TICKET, 20x POLISHED_PUMPKIN, 1x PUMPKIN_DICER
  - Farming Tool // Someone once said, II is always better than I! // Level 1 -> 2   (0/1k) 0%
- **Pythagorean Potato Shovel Mk. II** (`THEORETICAL_HOE_POTATO_2`, R) — - — req: Farming 15, Garden 3 — recipe: craft: 64x JACOBS_TICKET, 60x ENCHANTED_BAKED_POTATO, 1x THEORETICAL_HOE_POTATO_1
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Refined Mithril Pickaxe** (`REFINED_MITHRIL_PICKAXE`, R) — BP +5, Dmg +50, MinSpd +350, MinFort +10 — req: HotM 3 — recipe: -
  - Breaking Power 5
- **Reinforced Chisel** (`REINFORCED_CHISEL`, R) — - — req: HotM 8 — recipe: forge (0.008333h): REFINED_TUNGSTEN:2, BEJEWELED_HANDLE:1, REFINED_UMBER:2, CHISEL:1
  - Highlight Chance: +60% // Excavator alongside a Suspicious Scrap to begin the excavation process! // Charges: 18
- **Reinforced Huntaxe - Cursus** (`CURSUS_FERAE`, R) — Dmg +15 — req: - — recipe: craft: 192x VINESAP, 1x SILVA_DOMINUS, 16x STRETCHING_STICKS
  - Hunting Tool // Ability: Absorptio  RIGHT CLICK This weapon lets you absorb 15% of the basic combat stats of any weapon. // Ability: Vis Temperata Each strike of this weapon has its damage capped at 25% of the enemy's max Health. This weapon cannot cause a fatal blow. // Fera fugit, cursus sequitur.
- **Rod of Champions** (`CHAMP_ROD`, R) — Dmg +100, Str +100, FishSpd +50, SCC +4 — req: Fishing 15 — recipe: craft: 8x ENCHANTED_WATER_LILY, 1x CHALLENGE_ROD
  - ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Ruby Drill TX-15** (`GEMSTONE_DRILL_1`, R) — BP +7, Dmg +75, MinSpd +150, GemFort +60, Pristine +0.5 — req: HotM 4 — recipe: forge (4h): DRILL_ENGINE:1, FUEL_TANK:1, FINE_RUBY_GEM:6
  - Breaking Power 7 // Grants +5% Gemstone Powder, and +800 Mining Speed when mining // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Shredded Line** (`SHREDDED_LINE`, R) — - — req: Fishing 20 — recipe: -
  - Grants +250 Damage and +50 Ferocity. // Talk to Roddy in the Backwater Bayou to apply this Line to a Fishing Rod.
- **SkyMart Hyper Vacuum** (`SKYMART_HYPER_VACUUM`, R) — FarmFort +15, Dmg +200 — req: Garden 5 — recipe: -
  - Ability: Vacuum  HOLD RIGHT CLICK Deals 200  Damage per second to nearby  Pests. // Ability: Pest Tracker  LEFT CLICK Leads you in the direction of the nearest  Pest. Cooldown: 1s // Vacuum Bag: 0 Pests Vacuum  Pests in your Garden to increase!
- **Titanium Pickaxe** (`TITANIUM_PICKAXE`, R) — BP +6, Dmg +60, MinSpd +140 — req: HotM 3 — recipe: -
  - Breaking Power 6 // Grants +50 Mining Fortune when mining Titanium Ore.
- **Topaz Rod** (`POLISHED_TOPAZ_ROD`, R) — Dmg +100, Str +100, FishSpd +50, SCC +2 — req: Fishing 19, HotM 5 — recipe: forge (12h): BEJEWELED_HANDLE:3.0, FLAWLESS_TOPAZ_GEM:2.0
  - Lava Rod // ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Trophy Line** (`TROPHY_LINE`, R) — - — req: Fishing 20, Lotus coll 9 — recipe: craft: 6x LOTUS_GOLD
  - Grants +5 Trophy Chance. // Talk to Roddy in the Backwater Bayou to apply this Line to a Fishing Rod.
- **Turing Sugar Cane Cutter Mk. II** (`THEORETICAL_HOE_CANE_2`, R) — - — req: Farming 15, Garden 5 — recipe: craft: 64x JACOBS_TICKET, 40x ENCHANTED_SUGAR_CANE, 1x THEORETICAL_HOE_CANE_1
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Wild Rose Cutter Mk. II** (`THEORETICAL_HOE_WILD_ROSE_2`, R) — - — req: Farming 15, Garden 12 — recipe: craft: 64x JACOBS_TICKET, 40x COMPACTED_WILD_ROSE, 1x THEORETICAL_HOE_WILD_ROSE_1
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Winter Rod** (`WINTER_ROD`, R) — Dmg +50, Str +50, FishSpd +75 — req: Fishing 10 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **AquaMaster™ X** (`AQUAMASTER_X`, E) — - — req: - — recipe: -
  - Use it on plants in the Greenhouse to keep them watered. // Ability: Hydrate  HOLD RIGHT CLICK Aim and hold in the direction of a plant to water it. Dispenses 300 water per second. // Ability: Fill  RIGHT CLICK When aiming at a water source, fills your can back up with water. // Ability: Aquaburst Spreads water in a larger area at once. // Water: 0/15k
- **Cactus Knife Mk. III** (`CACTUS_KNIFE_3`, E) — - — req: Farming 20, Garden 7 — recipe: craft: 256x JACOBS_TICKET, 128x ENCHANTED_CACTUS, 1x CACTUS_KNIFE_2
  - Farming Tool // Slices through cactus instantly! // Ability: Swift Slicer Increases your speed cap by 100 while in The Garden. // Level 1 -> 2   (0/1k) 0%
- **Cocoa Chopper Mk. III** (`COCO_CHOPPER_3`, E) — - — req: Farming 20, Garden 8 — recipe: craft: 256x JACOBS_TICKET, 160x ENCHANTED_COOKIE, 1x COCO_CHOPPER_2
  - Farming Tool // Cocoa Beans Fortune: +4 // Level 1 -> 2   (0/1k) 0%
- **Eclipse Sickle Mk. III** (`THEORETICAL_HOE_SUNFLOWER_3`, E) — - — req: Farming 20, Garden 11 — recipe: craft: 256x JACOBS_TICKET, 80x COMPACTED_MOONFLOWER, 80x COMPACTED_SUNFLOWER, 1x THEORETICAL_HOE_SUNFLOWER_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Euclid's Wheat Sickle Mk. III** (`THEORETICAL_HOE_WHEAT_3`, E) — - — req: Farming 20, Garden 1 — recipe: craft: 256x JACOBS_TICKET, 80x ENCHANTED_HAY_BALE, 1x THEORETICAL_HOE_WHEAT_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Fungi Cutter Mk. III** (`FUNGI_CUTTER_3`, E) — - — req: Farming 20, Garden 9 — recipe: craft: 256x JACOBS_TICKET, 40x ENCHANTED_HUGE_MUSHROOM_2, 40x ENCHANTED_HUGE_MUSHROOM_1, 1x FUNGI_CUTTER_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Gauss Carrot Shovel Mk. III** (`THEORETICAL_HOE_CARROT_3`, E) — - — req: Farming 20, Garden 2 — recipe: craft: 256x JACOBS_TICKET, 256x ENCHANTED_GOLDEN_CARROT, 1x THEORETICAL_HOE_CARROT_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Glacite-Plated Chisel** (`GLACITE_CHISEL`, E) — - — req: HotM 9 — recipe: forge (0.008333h): REINFORCED_CHISEL:1.0, BEJEWELED_HANDLE:1.0, GLACITE_AMALGAMATION:8.0, MITHRIL_PLATE:1.0
  - Excavator alongside a Suspicious Scrap to begin the excavation process! // Charges: 20
- **Helix Chopper** (`HELIX_CHOPPER`, E) — Dmg +75, Sweep +50, ForFort +50, Str +25 — req: Foraging 20 — recipe: craft: 3x HELIXIS, 1x FIGSTONE_AXE, 8x FLEXBONE
  - Ability: Mega Frenzy Gains 1 Sweep every 20,000 Logs cut on Galatea. (max 40) Current Bonus: +0 Next Upgrade: +1 (0/20,000) // Logs Cut: 0
- **Hotspot Hook** (`HOTSPOT_HOOK`, E) — - — req: Fishing 20 — recipe: craft: 1x CONDENSED_WATER_LILY, 8x BROKEN_RADAR, 16x INK_SPLAT
  - Grants a +20% chance to catch Hotspot Sea Creatures while fishing in a Hotspot. // Talk to Roddy in the Backwater Bayou to apply this Hook to a Fishing Rod.
- **Inferno Rod** (`INFERNO_ROD`, E) — Dmg +200, Str +200, FishSpd +50, SCC +10, TrophyCh +20 — req: Fishing 30 — recipe: craft: 16x MAGMA_FISH_GOLD, 1x MAGMA_ROD
  - Lava Rod // Trophy Chance: +20% // ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **InfiniVacuum™** (`INFINI_VACUUM`, E) — FarmFort +20, Dmg +300 — req: Garden 5 — recipe: -
  - Ability: Vacuum  HOLD RIGHT CLICK Deals 300  Damage per second to nearby  Pests. // Ability: Pest Tracker  LEFT CLICK Leads you in the direction of the nearest  Pest. Cooldown: 1s // Vacuum Bag: 0 Pests Vacuum  Pests in your Garden to increase!
- **Jasper Drill X** (`GEMSTONE_DRILL_4`, E) — BP +9, Dmg +75, MinSpd +600, GemFort +120, Pristine +2 — req: HotM 6 — recipe: forge (0.008333h): TREASURITE:100, FLAWLESS_JASPER_GEM:1, GEMSTONE_DRILL_3:1, MAGMA_CORE:16
  - Breaking Power 9 // Grants +20% Gemstone Powder, and +800 Mining Speed when mining // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Melon Dicer Mk. III** (`MELON_DICER_3`, E) — - — req: Farming 20, Garden 6 — recipe: craft: 256x JACOBS_TICKET, 256x ENCHANTED_MELON_BLOCK, 1x MELON_DICER_2
  - Farming Tool // Melon Slice Fortune: +4 // Almost identical to the Melon Dicer Mk. II, but it has Mk. III in the name! // Level 1 -> 2   (0/1k) 0%
- **Newton Nether Wart Cutter Mk. III** (`THEORETICAL_HOE_WARTS_3`, E) — - — req: Farming 20, Garden 10 — recipe: craft: 256x JACOBS_TICKET, 240x MUTANT_NETHER_STALK, 1x THEORETICAL_HOE_WARTS_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Pickonimbus 2000** (`PICKONIMBUS`, E) — BP +8, Dmg +30, MinSpd +750 — req: HotM 4 — recipe: -
  - Breaking Power 8 // A powerful tool that breaks after 2,000 uses.
- **Pumpkin Dicer Mk. III** (`PUMPKIN_DICER_3`, E) — - — req: Farming 20, Garden 4 — recipe: craft: 256x JACOBS_TICKET, 80x POLISHED_PUMPKIN, 1x PUMPKIN_DICER_2
  - Farming Tool // "III is better than the II version" - CEO of PumpkinDicer LLC. // Level 1 -> 2   (0/1k) 0%
- **Pythagorean Potato Shovel Mk. III** (`THEORETICAL_HOE_POTATO_3`, E) — - — req: Farming 20, Garden 3 — recipe: craft: 256x JACOBS_TICKET, 240x ENCHANTED_BAKED_POTATO, 1x THEORETICAL_HOE_POTATO_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Rod of Legends** (`LEGEND_ROD`, E) — Dmg +125, Str +125, FishSpd +65, SCC +6 — req: Fishing 20 — recipe: craft: 128x ENCHANTED_WATER_LILY, 1x CHAMP_ROD
  - ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Savage Huntaxe - Praedator** (`APEX_PRAEDATOR`, E) — Dmg +20 — req: - — recipe: craft: 3x ENCHANTED_VINESAP, 1x CURSUS_FERAE, 32x STRETCHING_STICKS
  - Hunting Tool // Ability: Absorptio  RIGHT CLICK This weapon lets you absorb 20% of the basic combat stats of any weapon. // Ability: Vis Temperata Each strike of this weapon has its damage capped at 33% of the enemy's max Health. This weapon cannot cause a fatal blow. // Supra omnes, Apex Praedator solus regnat.
- **Spooky Hook** (`PHANTOM_HOOK`, E) — - — req: Fishing 21 — recipe: -
  - Grants a +20% chance to catch Spooky Sea Creatures during the Spooky Festival. // Talk to Roddy in the Backwater Bayou to apply this Hook to a Fishing Rod.
- **Stonk** (`STONK_PICKAXE`, E) — BP +1, MinSpd +380 — req: - — recipe: trade
  - Breaking Power 1 // Efficiency VI Increases how quickly your tool breaks blocks. // When mining End Stone with this pickaxe, Endermites won't spawn, experience will be dropped! 5% chance to drop an extra End Stone block.
- **Stranded Treecapitator** (`STRANDED_TREECAPITATOR`, E) — - — req: - — recipe: -
  - A forceful Gold Axe which can break a large amount of logs in a single hit! Only available for Stranded players. Cooldown: 2s
- **Titanium Drill DR-X355** (`TITANIUM_DRILL_1`, E) — BP +7, Dmg +75, MinFort +25, MinSpd +700 — req: HotM 5 — recipe: forge (4h): FUEL_TANK:1, DRILL_ENGINE:1, REFINED_MITHRIL:8, REFINED_TITANIUM:8, GOLDEN_PLATE:6
  - Breaking Power 7 // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Titanium Drill DR-X455** (`TITANIUM_DRILL_2`, E) — BP +8, Dmg +75, MinFort +40, MinSpd +900 — req: HotM 5 — recipe: forge (0.008333h): MITHRIL_PLATE:5, REFINED_TITANIUM:12, REFINED_DIAMOND:10, TITANIUM_DRILL_1:1
  - Breaking Power 8 // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Titanium Drill DR-X555** (`TITANIUM_DRILL_3`, E) — BP +9, Dmg +75, MinFort +70, MinSpd +1200 — req: HotM 5 — recipe: forge (0.008333h): REFINED_DIAMOND:20, ENCHANTED_IRON_BLOCK:2, PLASMA:20, TITANIUM_DRILL_2:1, REFINED_TITANIUM:16, MITHRIL_PLATE:10
  - Breaking Power 9 // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Topaz Drill KGR-12** (`GEMSTONE_DRILL_3`, E) — BP +9, Dmg +75, MinSpd +450, GemFort +100, Pristine +1.5 — req: HotM 5 — recipe: forge (0.008333h): FLAWLESS_TOPAZ_GEM:1, GEMSTONE_MIXTURE:3, GEMSTONE_DRILL_2:1, MAGMA_CORE:8
  - Breaking Power 9 // Grants +15% Gemstone Powder, and +800 Mining Speed when mining // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Treecapitator** (`TREECAPITATOR_AXE`, E) — Sweep +25 — req: - — recipe: craft: 32x ENCHANTED_JUNGLE_LOG, 1x JUNGLE_AXE
  - Ability: Throwing Axe  RIGHT CLICK While on Foraging Islands, throw your Axe at a tree! It will sweep 50% of the logs it normally would! Cooldown: 1s // Ability: Park Enthusiast Gain +100 Foraging Fortune while in The Park.
- **Turing Sugar Cane Cutter Mk. III** (`THEORETICAL_HOE_CANE_3`, E) — - — req: Farming 20, Garden 5 — recipe: craft: 256x JACOBS_TICKET, 160x ENCHANTED_SUGAR_CANE, 1x THEORETICAL_HOE_CANE_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Wild Rose Cutter Mk. III** (`THEORETICAL_HOE_WILD_ROSE_3`, E) — - — req: Farming 20, Garden 12 — recipe: craft: 256x JACOBS_TICKET, 160x COMPACTED_WILD_ROSE, 1x THEORETICAL_HOE_WILD_ROSE_2
  - Farming Tool // Level 1 -> 2   (0/1k) 0%
- **Yeti Rod** (`YETI_ROD`, E) — Dmg +150, Str +130, FishSpd +75 — req: Fishing 25 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **AquaMaster™ Hydromax** (`AQUAMASTER_HYDROMAX`, L) — - — req: - — recipe: -
  - Use it on plants in the Greenhouse to keep them watered. // Ability: Hydrate  HOLD RIGHT CLICK Aim and hold in the direction of a plant to water it. Dispenses 350 water per second. // Ability: Fill  RIGHT CLICK When aiming at a water source, fills your can back up with water. // Ability: Aquaburst EX Spreads water in a larger area at once. // Water: 0/20k
- **Auger Rod** (`AUGER_ROD`, L) — Dmg +135, Str +90, FishSpd +110 — req: Fishing 25 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Giant Fishing Rod** (`GIANT_FISHING_ROD`, L) — DblHook +10 — req: - — recipe: -
  - Purchased by: [YOUTUBE] Nullzee Purchased for: 666,666,666  coins // Auction #1 Bid #1 July 2022 // ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Hellfire Rod** (`HELLFIRE_ROD`, L) — Dmg +225, Str +225, FishSpd +60, SCC +14, TrophyCh +25 — req: Fishing 35 — recipe: craft: 80x MAGMA_FISH_GOLD, 1x INFERNO_ROD; craft: 4x MAGMA_FISH_DIAMOND, 1x INFERNO_ROD
  - Lava Rod // Trophy Chance: +25% // ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **InfiniVacuum™ Hooverius** (`INFINI_VACUUM_HOOVERIUS`, L) — FarmFort +25, Dmg +400 — req: Garden 5 — recipe: -
  - Ability: Vacuum  HOLD RIGHT CLICK Deals 400  Damage per second to nearby  Pests. // Ability: Pest Tracker  LEFT CLICK Leads you in the direction of the nearest  Pest. Cooldown: 1s // Ability: Stereo Harmony  SNEAK LEFT CLICK Plays vinyls at a frequency only  Pests can hear. Certain Pests are more likely to appear when certain vinyls are playing. // Now Playing: None // Vacuum Bag: 0 Pests Vacuum  Pests in your Garden to increase!
- **Perfect Chisel** (`PERFECT_CHISEL`, L) — - — req: HotM 10 — recipe: forge (0.008333h): BEJEWELED_HANDLE:1.0, GLACITE_CHISEL:1.0, PERFECT_PLATE:1.0
  - Excavator alongside a Suspicious Scrap to begin the excavation process! // Charges: 22
- **Phantom Rod** (`PHANTOM_ROD`, L) — Dmg +200, Str +125, FishSpd +100 — req: Fishing 21 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Prime Huntaxe - Nex Titanum** (`NEX_TITANUM`, L) — Dmg +25 — req: - — recipe: craft: 6x ENCHANTED_VINESAP, 1x APEX_PRAEDATOR, 64x STRETCHING_STICKS
  - Hunting Tool // Ability: Absorptio  RIGHT CLICK This weapon lets you absorb 25% of the basic combat stats of any weapon. // Ability: Vis Temperata Each strike of this weapon has its damage capped at 50% of the enemy's max Health. This weapon cannot cause a fatal blow. // Titanum ruina, mundi mutatio.
- **Rod of the Sea** (`ROD_OF_THE_SEA`, L) — Dmg +150, Str +150, FishSpd +80, SCC +8 — req: Fishing 24 — recipe: craft: 4x ENCHANTED_SHARK_FIN, 4x GREAT_WHITE_SHARK_TOOTH, 1x LEGEND_ROD
  - ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Shredder** (`THE_SHREDDER`, L) — Dmg +120, Fer +50, FishSpd +115 — req: Fishing 20 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Titan Line** (`TITAN_LINE`, L) — - — req: Fishing 30 — recipe: craft: 1x CONDENSED_WATER_LILY, 1x BAYOU_WATER_ORB, 1x TITANOBOA_SHED
  - Grants +2 Double Hook Chance. // Talk to Roddy in the Backwater Bayou to apply this Line to a Fishing Rod.
- **Titanium Drill DR-X655** (`TITANIUM_DRILL_4`, L) — BP +9, Dmg +75, MinFort +120, MinSpd +1600 — req: HotM 6 — recipe: forge (0.008333h): GEMSTONE_MIXTURE:16, FLAWLESS_RUBY_GEM:1, MITHRIL_PLATE:10, REFINED_TITANIUM:32, CORLEONITE:30, TITANIUM_DRILL_3:1, REFINED_DIAMOND:5
  - Breaking Power 9 // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **Treasure Hook** (`TREASURE_HOOK`, L) — - — req: Fishing 25 — recipe: -
  - Only allows you to catch items and Treasure. // Talk to Roddy in the Backwater Bayou to apply this Hook to a Fishing Rod.
- **Divan's Drill** (`DIVAN_DRILL`, M) — BP +10, Dmg +75, MinFort +150, MinSpd +1800 — req: HotM 7 — recipe: forge (0.008333h): TITANIUM_DRILL_4:1, SKYBLOCK_COIN:50000000, DIVAN_ALLOY:1
  - Breaking Power 10 // Increases fuel capacity with part installed. // Drill Engine: Not Installed Increases  Mining Speed with part installed. // Applies a passive upgrade with part installed. // Apply Drill Parts to this Drill by talking to a Drill Mechanic!
- **%%green%%Axe Preview %%gray%%(Right-Click)** (`AXE_PREVIEW`, S) — Sweep +5 — req: - — recipe: -
- **Elle's Lava Rod** (`ELLES_LAVA_ROD`, S) — Dmg +50, Str +50, FishSpd +8 — req: - — recipe: -
  - Lava Rod
- **Elle's Pickaxe** (`ELLES_PICKAXE`, S) — BP +5, Dmg +10, MinSpd +250 — req: - — recipe: -
  - Breaking Power 5
- **Icebreaker** (`ICEBREAKER`, S) — - — req: - — recipe: -
  - Quest Item // Found in the Icy Biome. Perhaps it has a use there? // This item will vanish from your inventory after leaving the Critter Safari!
- **Pioneer Pickaxe** (`ALPHA_PICK`, S) — BP +1, MinSpd +1 — req: - — recipe: -
  - Breaking Power 1 // The very first pickaxe model! Invented by the famous Thomas Pickson.
- **Game Fixer** (`GAME_FIXER`, VS) — - — req: - — recipe: -
- **Chisel** (`CHISEL`, ?) — - — req: - — recipe: -
  - Excavator alongside a Suspicious Scrap to begin the excavation process! // Charges: 16
- **Fishing Rod** (`FISHING_ROD`, ?) — Dmg +15, Str +15, FishSpd +5, SCC +1 — req: - — recipe: craft: 3x STICK, 2x STRING
  - ථ Hook NONE ꨃ Line NONE ࿉ Sinker NONE // Talk to Roddy in the Backwater Bayou to apply parts to this rod.
- **Golden Axe** (`GOLD_AXE`, ?) — Dmg +20 — req: - — recipe: craft: 3x GOLD_INGOT, 2x STICK
- **Golden Hoe** (`GOLD_HOE`, ?) — - — req: - — recipe: craft: 2x GOLD_INGOT, 2x STICK
- **Golden Pickaxe** (`GOLD_PICKAXE`, ?) — BP +1, Dmg +15, MinSpd +250 — req: - — recipe: craft: 3x GOLD_INGOT, 2x STICK
  - Breaking Power 1
- **Iron Axe** (`IRON_AXE`, ?) — Dmg +25 — req: - — recipe: craft: 3x IRON_INGOT, 2x STICK
- **Iron Hoe** (`IRON_HOE`, ?) — - — req: - — recipe: craft: 2x IRON_INGOT, 2x STICK
- **Iron Pickaxe** (`IRON_PICKAXE`, ?) — BP +3, Dmg +25, MinSpd +160 — req: - — recipe: craft: 3x IRON_INGOT, 2x STICK
  - Breaking Power 3
- **Prismarine Rod** (`PRISMARINE_ROD`, ?) — Dmg +15, Str +10, FishSpd +15 — req: Fishing 3 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Rookie Farming Axe** (`ROOKIE_FARMING_AXE`, ?) — FarmFort +5 — req: Farming 1 — recipe: -
  -  Farming Fortune increases your chance of dropping multiple crops when farming!
- **Rookie Foraging Axe** (`ROOKIE_AXE`, ?) — Dmg +20 — req: - — recipe: -
  - Efficiency I Increases how quickly your tool breaks blocks.
- **Rookie Hoe** (`ROOKIE_HOE`, ?) — FarmFort +5 — req: Farming 1 — recipe: -
  -  Farming Fortune increases your chance of dropping multiple crops when farming!
- **Rookie Pickaxe** (`ROOKIE_PICKAXE`, ?) — BP +2, Dmg +15, MinSpd +150, OreFort +5 — req: - — recipe: -
  - Breaking Power 2
- **Sculptor's Axe** (`SCULPTORS_AXE`, ?) — - — req: - — recipe: craft: 192x LOG-2, 2x STICK
  - Right-click on any log on your island to turn it into a 6 sided log!
- **Shears** (`SHEARS`, ?) — - — req: - — recipe: craft: 2x IRON_INGOT
- **Sponge Rod** (`SPONGE_ROD`, ?) — Dmg +20, Str +15, FishSpd +30 — req: Fishing 5 — recipe: -
  - This rod is broken and cannot be used to fish. // Bring it to Roddy in the Backwater Bayou to convert it into a new rod!
- **Stone Axe** (`STONE_AXE`, ?) — Dmg +15 — req: - — recipe: craft: 3x COBBLESTONE, 2x STICK
- **Stone Hoe** (`STONE_HOE`, ?) — - — req: - — recipe: craft: 2x COBBLESTONE, 2x STICK
- **Stone Pickaxe** (`STONE_PICKAXE`, ?) — BP +2, Dmg +20, MinSpd +110 — req: - — recipe: craft: 3x COBBLESTONE, 2x STICK
  - Breaking Power 2
- **Wooden Axe** (`WOOD_AXE`, ?) — Dmg +10 — req: - — recipe: craft: 3x WOOD, 2x STICK
- **Wooden Hoe** (`WOOD_HOE`, ?) — - — req: - — recipe: craft: 2x WOOD, 2x STICK
- **Wooden Pickaxe** (`WOOD_PICKAXE`, ?) — BP +1, Dmg +15, MinSpd +70 — req: - — recipe: craft: 3x WOOD, 2x STICK
  - Breaking Power 1
- **Zombie Pickaxe** (`ZOMBIE_PICKAXE`, ?) — BP +3, MinSpd +190 — req: - — recipe: craft: 3x ROTTEN_FLESH, 2x STICK
  - Breaking Power 3 // Ability: Rotten Grants +5 Mining Fortune per Undead mob within 30 blocks of you. (Max 25 Mining Fortune)
