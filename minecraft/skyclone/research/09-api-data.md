# 09 — Raw API & Community Data

Downloaded 2026-10-02. Everything lives under `research/data/`. Total about 93 MB on disk (about 50 MB apparent size; NEU's 17,962 small files take up more disk than their bytes).

> **NAMING WARNING:** every name in this data is Hypixel's: item ids, display names, NPCs, mayors, islands, mobs and lore. **All of it has to be renamed before shipping.** That is a later, separate pass. Do NOT rename anything yet. Build against these ids as they are, and keep a single rename map so the pass is mechanical.

## Files saved

| File | Size | Source | Static or live |
|---|---|---|---|
| `skills.json` | 115 KB | `/v2/resources/skyblock/skills` | static (design data) |
| `collections.json` | 81 KB | `/v2/resources/skyblock/collections` | static |
| `items.json` | 4.9 MB | `/v2/resources/skyblock/items` | static |
| `election.json` | 4.6 KB | `/v2/resources/skyblock/election` | live snapshot |
| `bingo.json` | 5 KB | `/v2/resources/skyblock/bingo` | live snapshot (October 2026 card) |
| `bazaar.json` | 3.5 MB | `/v2/skyblock/bazaar` | live snapshot |
| `firesales.json` | 547 B | `/v2/skyblock/firesales` | live snapshot |
| `auctions_page0.json` | 2.3 MB | `/v2/skyblock/auctions?page=0` | live snapshot, page 0 of 43 |
| `neu-repo/` | 35 MB apparent, 17,962 files | NotEnoughUpdates-REPO `master` (MIT, Moulberry) | community-curated |
| `skyhanni-repo/` | 5.4 MB, 112 files | SkyHanni-REPO `main` (MIT, hannibal2) | community-curated |

**Failures: none.** Every endpoint returned HTTP 200 without a key. Before downloading I checked the repo sizes with the GitHub API: NEU is 62 MB and SkyHanni is 14 MB, both well under the 300 MB cap. Both were fetched as codeload zips and extracted, with the top-level `<repo>-<branch>/` folder stripped. The zips were then deleted.

Every Hypixel file has `success: true` and a `lastUpdated` (ms epoch) at the top level. The `firesales` file has no `lastUpdated`.

---

## 1. `skills.json`: skill XP curves and per-level rewards

Shape: `{success, lastUpdated, version, skills: {SKILL_ID: {name, description, maxLevel, levels:[...]}}}`

12 skills and their max levels:

| Skill | Max |
|---|---|
| FARMING | 60 |
| MINING | 60 |
| COMBAT | 60 |
| ENCHANTING | 60 |
| TAMING | 60 |
| FORAGING | 57 |
| FISHING | 50 |
| ALCHEMY | 50 |
| CARPENTRY | 50 |
| HUNTING | 50 |
| RUNECRAFTING | 25 |
| SOCIAL | 25 |

Example level (`totalExpRequired` is **cumulative**):
```json
{"level": 1, "totalExpRequired": 50.0, "unlocks": ["+Access to The Barn",
 "Farmhand I   Grants +4 \ue051 Farming Fortune, ...", "+2 \ue010 Health", "+100 Coins", "+5 SkyBlock XP"]}
```
FARMING 60 needs a total of 111,672,425 XP. The `unlocks` strings contain private-use glyphs (`\ue051` and similar, which are stat icons from Hypixel's resource pack) and `➜` arrows. Strip them or map them.

**Use:** skill-system seed. The XP table becomes `levels[].totalExpRequired`. Per-level stat rewards have to be parsed out of the `unlocks` strings, for example "+2 Health" or "Grants +4 Farming Fortune". The coin rewards and SkyBlock XP rewards are in the same strings.

## 2. `collections.json`: collection tiers and their unlock rewards

Shape: `{success, lastUpdated, version, collections: {CATEGORY: {name, items: {ITEM_ID: {name, maxTiers, tiers:[{tier, amountRequired, unlocks:[str]}]}}}}}`

There are **90 collections** across 6 categories:

| Category | Collections |
|---|---|
| FARMING | 20 |
| MINING | 25 |
| COMBAT | 11 |
| FORAGING | 15 |
| FISHING | 12 |
| RIFT | 7 |

maxTiers distribution: 9 tiers (44 collections), 10 (13), 11 (8), 7 (8), 8 (6), 4 (5), 12 (3), 5 (2), 6 (1).

Example:
```json
"WHEAT": {"name": "Wheat", "maxTiers": 11, "tiers": [
  {"tier": 1, "amountRequired": 50, "unlocks": ["Wheat Minion Recipes", "+4 SkyBlock XP"]},
  {"tier": 2, "amountRequired": 100, "unlocks": ["Beginner Agronomy Sack Recipe", "Farmhand Helmet Recipe", ...]}, ...]}
```
**Use:** collection system seed. The collection key is the item id that gets counted. The `unlocks` strings name recipes by display name ("Enchanted Wheat Recipe"), so you have to join them back to item ids through `items.json`'s `name` field or NEU's `displayname`. This file is what gates recipes. Recipes start locked until the matching collection tier is reached.

## 3. `items.json`: the item registry (most important Hypixel file)

Shape: `{success, lastUpdated, items: [ {id, name, material, tier?, category?, stats?, ...} ]}`

**5,655 items.** Fields vary by item. Every item has `id`, `name` and `material`. The most common optional fields:

| Field | Items | Field | Items |
|---|---|---|---|
| tier | 4,782 | stats | 1,376 |
| durability | 3,072 | museum_data | 1,060 |
| skin | 2,783 | requirements | 1,003 |
| category | 2,575 | generator / generator_tier | 719 |
| npc_sell_price | 2,436 | upgrade_costs | 545 |
| gemstone_slots | 499 | dungeon_item | 323 |
| soulbound | 383 | | |

Other fields seen: `glowing`, `unstackable`, `color` (leather RGB), `salvages`, `catacombs_requirements`, `tiered_stats`, `ability_damage_scaling`, `can_auction` / `can_trade`, `motes_sell_price`, `gear_score`, `item_model`, and a few more.

**By tier:** RARE 1,777 · EPIC 1,032 · (none) 873 · UNCOMMON 862 · LEGENDARY 717 · SPECIAL 187 · COMMON 126 · MYTHIC 48 · UNOBTAINABLE 30 · VERY_SPECIAL 2 · SUPREME 1. Items with no `tier` are COMMON.

**By category:**
- (none) 3,095. These are materials, minions and misc.
- Equipment and tools: ACCESSORY 423, HELMET 269, COSMETIC 193, BOOTS 191, CHESTPLATE 191, LEGGINGS 183, SWORD 156, REFORGE_STONE 108, PET_ITEM 95, SACK 67, FARMING_TOOL 48, BOW 44, PORTAL 42, BELT 41, TRAVEL_SCROLL 40, MUTATION 40, NECKLACE 37, CLOAK 34, AXE 29, GLOVES 25, FISHING_ROD 25, WAND 25, PICKAXE 24, DUNGEON_PASS 22, BAIT 20.
- Smaller groups (under 20 each): MEMENTO, FISHING_ROD_PART, DEPLOYABLE, CONSUMABLE, ARROW, BOOSTER, SPADE, DRILL, GARDEN_CHIP, BRACELET, CARNIVAL_MASK, SALT, LONGSWORD, VACUUM, WATERING_CAN, TRAP, CHISEL, FISHING_NET, LASSO, SHEARS, ARROW_POISON, CAPSULE, GAUNTLET.

**Minions:** 719 records have a `generator` field (61 minion types, mostly 11 or 12 tiers each) plus `generator_tier`.

**Stats keys are not normalised.** Both `DEFENSE` (559 items) and `defense` (111) appear, and so do `HEALTH` / `health`. Upper-case the keys on load. The main stats are DEFENSE, HEALTH, INTELLIGENCE, STRENGTH, DAMAGE, WALK_SPEED, CRITICAL_DAMAGE, CRITICAL_CHANCE, MINING_SPEED, MINING_FORTUNE, FARMING_FORTUNE, SEA_CREATURE_CHANCE, FEROCITY, MAGIC_FIND, TRUE_DEFENSE, BREAKING_POWER and ATTACK_SPEED. There are also Rift stats (`rift_*`) and per-crop fortunes.

Example:
```json
{"material": "IRON_SWORD", "name": "Arack", "category": "SWORD", "tier": "EPIC", "npc_sell_price": 5000,
 "stats": {"damage": 90}, "salvages": [{"type":"ESSENCE","essence_type":"SPIDER","amount":5}],
 "upgrade_costs": [[{"type":"ESSENCE","essence_type":"SPIDER","amount":15}], ...], "museum_data": {...}, "id": "ARACK"}
```
**Use:** item registry seed. It supplies id, vanilla `material` (+`durability` = legacy damage value), `tier` → rarity, `category` → item type, `stats`, `npc_sell_price`, `requirements` (skill/slayer/collection gates), `upgrade_costs` (dungeon star costs), `gemstone_slots`, and `skin` (base64 texture for SKULL_ITEM heads).

**Gaps:** this file has **no lore, no abilities and no recipes**. Those come from NEU (section 9).

## 4. `election.json`: mayor / minister snapshot

Shape: `{success, lastUpdated, mayor: {key, name, perks:[{name, description}], minister:{key, name, perk}, election:{year, candidates:[{key, name, perks:[{name, description, minister}], votes}]}}, current: {year, candidates}}`

Current snapshot: the mayor is Diana (`pets`), the minister is Cole (`mining`), and it is election year 516. The perk descriptions use `§` colour codes.

**Use:** design reference for the mayor system: perk names, the minister-perk flag and the candidate structure. It is not a full perk catalogue, because it only covers the candidates who are currently running.

## 5. `bingo.json`: the current bingo card

Shape: `{success, lastUpdated, id, name, start, end, modifier, goals:[...]}`

Card 58 ("October 2026") has **25 goals**. There are two kinds of goal:
- Community goals have `tiers: [2000000, ...]` and `progress`.
- Personal goals have only `lore`.

```json
{"id": "break_block_ores", "name": "Operation: Ore", "tiers": [2000000,4000000,6000000,8000000,10000000],
 "progress": 3718538, "lore": "§7Break §e10M Ore§7 Blocks.", "fullLore": [...]}
```
**Use:** goal-schema reference for a bingo or event system. For broader bingo data, see `skyhanni-repo/constants/Bingo.json` and `BingoRanks.json`.

## 6. `bazaar.json`: live order book (price seed)

Shape: `{success, lastUpdated, products: {PRODUCT_ID: {product_id, sell_summary:[{amount, pricePerUnit, orders}], buy_summary:[...], quick_status:{...}}}}`

**2,197 products.** Example `quick_status`:
```json
{"productId":"FACTION_RABBIT_WALKER","sellPrice":500000.05,"sellVolume":68,"sellMovingWeek":19,"sellOrders":7,
 "buyPrice":2499995.7,"buyVolume":23,"buyMovingWeek":52,"buyOrders":20}
```
**Use:**
- The product list tells you which items are bazaar-tradeable, i.e. the bazaar catalogue.
- `quick_status` buy and sell prices are a sane starting value for every material, which helps when seeding NPC prices and fake market depth.
- The summaries show what an order-book UI has to render: the top ~30 levels, aggregated by price.

Some product ids are not item ids. Enchantments appear as `ENCHANTMENT_SHARPNESS_5`, and essences as `ESSENCE_*`.

## 7. `firesales.json`: limited shop events

Shape: `{success, sales:[{item_id, start, end, amount, price}]}`. It currently lists the GLACITE_GUARDIAN_* skins at 650 gems, 950 stock each.

**Use:** schema for a time-limited, stock-capped premium shop. Note that a premium-currency shop must stay cosmetic only. The owner has a no-pay-to-win rule.

## 8. `auctions_page0.json`: one page of the live auction house

Shape: `{success, page, totalPages, totalAuctions, lastUpdated, auctions:[...]}`. There are 43 pages and 42,302 auctions in total. Only page 0 was saved, as instructed, which is **1,000 auctions**.

```json
{"uuid":"85f9...","auctioneer":"c5c3...","profile_id":"6212...","coop":[...],"start":1790926910152,"end":1790948510152,
 "item_name":"Satin Trousers","item_lore":"§7Health: §c+25\n...","extra":"Satin Trousers Leather Leggings",
 "categories":["armor"],"category":"armor","tier":"RARE","starting_bid":500,
 "item_bytes":"H4sIAAAA...","claimed":false,"highest_bid_amount":0,"bids":[],"bin":true, ...}
```
`item_bytes` holds the full item as gzipped base64 NBT, including enchantments, reforge, stars and gems. You can decode it with `nbtlib` or a small NBT reader.

**Use:** schema for the auction house. It shows BIN versus bid auctions, the coop seller list, and the AH category tabs (page 0 has `misc` 492, `armor` 229, `accessories` 158, `weapon` 78, `consumables` 21, `cosmetic` 10, plus `helmet_skins`, `other_skins`, `dyes` and `runes`). The `item_bytes` values are real examples of what a "fully modified" item carries.

The data contains Minecraft player UUIDs. Do not ship it, and do not show it anywhere. It is reference only.

---

## 9. `neu-repo/`: NotEnoughUpdates-REPO (MIT): **recipes, drops, NPC shops**

This is the most valuable folder. It has these parts:
- `items/` (**8,847 JSON files**): one file per item, pet tier, enchant level and NPC.
- `constants/` (40 files)
- `mobs/` (280 files: render JSON + skin PNGs)
- `mining/` (block hardness tables)
- `itemsOverlay/` (version-specific overrides)

### 9a. `items/<INTERNALNAME>.json`

Every file has `itemid` (vanilla, e.g. `minecraft:diamond_sword`), `displayname` (§-coloured), `nbttag` (SNBT string, includes skull textures + `ExtraAttributes.id`), `damage`, `lore` ([str], **the full item description including abilities and mana cost**), and `internalname` (= Hypixel id).

Optional fields:

| Field | Files | Meaning |
|---|---|---|
| `recipe` | 2,007 | 3×3 crafting grid |
| `recipes` | 1,387 | typed recipe list, see 9b |
| `crafttext` | | e.g. "Requires Wheat Collection IV", i.e. the unlock gate |
| `info` / `infoType` | | wiki links |
| `parent` | 452 | |
| `island`, `x`, `y`, `z` | 485 | NPC locations |
| `slayer_req` | 108 | |
| `reputation_req` | 24 | |
| `vanilla` | | |

File naming: pets are `PETNAME;<rarityIndex>.json`, where 0 is COMMON and 5 is MYTHIC. Enchanted books are `SHARPNESS;5.json`. There are 1,775 files with a `;` in the name. NPC files are `*_NPC.json` (518) and mob/drop pages are `*_MONSTER*` / `*_SC` (189+).

### 9b. Recipe types

Totals:

| Type | Count |
|---|---|
| Grid crafting (the old single `recipe` key) | 2,007 |
| `crafting` (in `recipes[]`) | 548 |
| `npc_shop` | 1,097 |
| `drops` | 430 |
| `katgrade` | 217 |
| `forge` | 120 |
| `trade` | 78 |

```json
// recipe (old single key) and type "crafting": slots A1..C3 = "ITEM_ID:count", "" = empty
{"A1":"","A2":"VINESAP:1","A3":"VINESAP:1","B1":"","B2":"VINESAP:1","B3":"VINESAP:1","C1":"VINESAP:1","C2":"","C3":"",
 "type":"crafting","count":1,"overrideOutputId":"ABYSMAL_LASSO"}
// npc_shop (lives in the NPC's file, e.g. ADVENTURER_NPC.json)
{"type":"npc_shop","cost":["SKYBLOCK_COIN:8.0"],"result":"ROTTEN_FLESH:1"}
// forge (Dwarven forge): duration in seconds
{"type":"forge","inputs":["GOLDEN_PLATE:1","FINE_AMBER_GEM:12"],"count":1,"overrideOutputId":"AMBER_MATERIAL","duration":21600}
// drops (mob pages): level, coins, xp, requirement text, drop table with chance strings
{"type":"drops","name":"§cAgarimoo","level":35,"coins":60,"xp":32,"render":"Mooshroom","extra":[...],
 "drops":[{"id":"RED_MUSHROOM:1","chance":"100%","extra":["§eDrop Count: §7x35-55"]}, ...]}
// trade (vanilla villager-style), katgrade (pet-sitter upgrade: coins, time sec, input pet -> output pet)
{"type":"trade","cost":"EMERALD:25","result":"ANVIL"}
{"type":"katgrade","coins":10000,"time":10800,"input":"ARMADILLO;0","output":"ARMADILLO;1","items":[]}
```
Ingredient format is always `ID:count`. `SKYBLOCK_COIN` stands for coins.

### 9c. `constants/`: system tables worth reading per feature

| File | Contents | Build use |
|---|---|---|
| `enchants.json` | enchant list, pools by item type, XP cost, max table levels | enchanting table + anvil |
| `reforges.json`, `reforgestones.json` | reforge → stats per rarity, stone → reforge, costs | reforge system |
| `pets.json`, `petnums.json` | pet XP curve, rarity offsets, per-pet stat scaling by level | pet system |
| `leveling.json` | skill XP (dup), runecrafting XP, **slayer XP + boss tiers**, catacombs XP | slayer + dungeons |
| `essencecosts.json`, `essenceshops.json` | star-upgrade essence costs per item; essence shop perks | dungeon gear upgrades |
| `gemstones.json`, `gemstonecosts.json` | gem types/stats per rarity, slot unlock costs | gemstone slots |
| `hotmlayout.json`, `hotflayout.json` | Heart of the Mountain / Forest perk trees | mining/foraging trees |
| `garden.json` | garden XP, crop milestones, visitors, plots, composter | garden |
| `bestiary.json` | mobs per island and kill brackets | bestiary |
| `misc.json` | base stats, tier colours, **minion data**, minion XP, talisman upgrades | player base stats, minions |
| `sacks.json` | which items each sack holds | sacks |
| `museum.json`, `sblevels.json`, `fairy_souls.json` (coords), `islands.json`, `zones.json`, `hoppity.json`, `trophyfish.json`, `calendar.json`, `abiphone.json`, `attribute_shards.json`, `dyes.json`, `george.json`, `skymart.json`, `carnivalshops.json`, `rngscore.json`, `bazaarstocks.json` | as named | feature-specific |
| `animatedskulls.json` | 5.6 MB of skull textures | cosmetic only |

### 9d. Other NEU folders

- `mobs/*.json`: render definitions only, e.g. `{"entity":"Player","modifiers":[{"type":"playerdata","skin":"neurepo:mobs/goblin.png"}]}`. The `.png` files beside them are mob skins. **The mob stats and drop tables are in the `items/*` files of type `drops`, not here.**
- `mining/blocks/*.json` (10) and `custom_mining_areas.json`: block strength and breaking-power tables for mining speed.

## 10. `skyhanni-repo/`: SkyHanni-REPO (MIT)

`constants/` has 60+ JSON files plus subfolders (`dungeons`, `events`, `fishing`, `foraging`, `misc`, `rift`, `island_graphs`, `community`). These are the ones most relevant to a clone:
- `SeaCreatures.json`: sea creatures by category, with rarity and fishing XP.
- `Slayer.json`, `SlayerProfitTrackerItems.json`
- `MinionXP.json`, `MinionDrops.json`, `InfernoMinionFuels.json`
- `Garden.json`, `TrophyFish.json`, `Pets.json`, `Enchants.json`, `Items.json`, `ItemDisplayNames.json`
- `CrimsonIsleReputation.json`, `AnitaUpgradeCosts.json`, `MaxwellPowers.json` (accessory power stats)
- `Warps.json`, `ArmorDrops.json`, `DianaDrops.json`, `GhostDrops.json`, `Bingo.json`, `BingoRanks.json`, `FameRank.json`
- `island_graphs/`: walkable node graphs per island

A lot of this repo is tooling for the mod (regexes, chat filters, disabled features) and can be ignored. It is a **secondary** source. Use it when NEU does not cover something, mainly sea creatures, minion drops and Maxwell powers.

---

## How a build agent should use this

| Build area | Primary source | Secondary |
|---|---|---|
| Item registry (id, material, rarity, type, stats, sell price) | `items.json` | NEU `items/*` for lore/abilities |
| Recipes (crafting grid) | NEU `items/*` → `recipe` + `recipes[type=crafting]` | — |
| Recipe unlock gates | `collections.json` unlock strings | NEU `crafttext` |
| NPC shops | NEU `recipes[type=npc_shop]` in `*_NPC.json` (with `island`/x/y/z) | — |
| Mob drops, levels, coins, XP | NEU `recipes[type=drops]` | SkyHanni `SeaCreatures.json`, `*Drops.json` |
| Forge | NEU `recipes[type=forge]` | — |
| Skills | `skills.json` | NEU `leveling.json` |
| Collections | `collections.json` | — |
| Minions | `items.json` (`generator`, `generator_tier`) + NEU `misc.json` minions | SkyHanni `MinionXP/MinionDrops.json` |
| Enchants / reforges / pets / gems / HOTM | NEU `constants/*` | SkyHanni |
| Slayer / dungeons XP | NEU `leveling.json`, `essencecosts.json` | — |
| Market (bazaar catalogue, starting prices) | `bazaar.json` | — |
| AH / firesale / election / bingo schemas | the matching snapshot files | — |

Practical notes:
- Ids line up. `items.json` `id` = NEU `internalname` = bazaar `product_id` (for items). `items.json` has 5,655 ids and NEU has 8,847, because NEU also has a file per pet tier, enchant level, NPC and mob page.
- Strip `§x` colour codes with `re.sub(r'§.', '', s)`. Treat `\ue000`–`\uf8ff` as stat icons.
- Upper-case `stats` keys on load (see section 3).
- `item_bytes` and NEU `nbttag` are NBT. You only need them if you want exact enchant or texture data.
- **Rename everything Hypixel-branded before shipping. That is a later pass, so not now.**

## Loader snippet

```python
import json, glob, os, re, base64, gzip, io
D = r"D:\projects\SkyblockClone\research\data"
def L(p): return json.load(open(os.path.join(D, p), encoding="utf-8"))   # always utf-8 (cp1252 console chokes)
strip = lambda s: re.sub(r"§.", "", s)

skills      = L("skills.json")["skills"]                  # {FARMING: {maxLevel, levels:[{level,totalExpRequired,unlocks}]}}
collections = L("collections.json")["collections"]        # {FARMING: {name, items:{WHEAT:{maxTiers,tiers}}}}
items       = {i["id"]: i for i in L("items.json")["items"]}
for i in items.values():
    i["stats"] = {k.upper(): v for k, v in i.get("stats", {}).items()}
election    = L("election.json")["mayor"]
bingo       = L("bingo.json")["goals"]
bazaar      = L("bazaar.json")["products"]                # {ID: {quick_status:{buyPrice,sellPrice,...}}}
firesales   = L("firesales.json")["sales"]
auctions    = L("auctions_page0.json")["auctions"]

neu = {}
for f in glob.glob(os.path.join(D, "neu-repo", "items", "*.json")):
    d = json.load(open(f, encoding="utf-8")); neu[d["internalname"]] = d
recipes = []                                              # (output_id, recipe_dict)
for iid, d in neu.items():
    if "recipe" in d: recipes.append((iid, {**d["recipe"], "type": "crafting"}))
    for r in d.get("recipes", []): recipes.append((r.get("overrideOutputId", iid), r))
neu_const = {os.path.basename(f)[:-5]: json.load(open(f, encoding="utf-8"))
             for f in glob.glob(os.path.join(D, "neu-repo", "constants", "*.json"))}
skyhanni = {os.path.basename(f)[:-5]: json.load(open(f, encoding="utf-8"))
            for f in glob.glob(os.path.join(D, "skyhanni-repo", "constants", "*.json"))}

# decode one auction's item NBT (raw bytes; parse with nbtlib if needed)
raw = gzip.GzipFile(fileobj=io.BytesIO(base64.b64decode(auctions[0]["item_bytes"]))).read()
```

## Caveats

- **Live snapshots go stale.** Bazaar, auctions, election, firesales and bingo were captured on 2026-10-02. Use them for schemas and order-of-magnitude prices, not as truth.
- **NEU is community-maintained.** Its recipes and drop chances are very good but not official. Drop `chance` values are strings. Most are percentages ("100%", "0.5%"), but some are bare decimals ("0.0002...") with no % sign, so parse both forms.
- **Licences:** NEU-REPO and SkyHanni-REPO are both MIT. The underlying game content (names, designs) is Hypixel's, which is why the rename pass exists.
- **Nothing was renamed.** That is deliberate.
