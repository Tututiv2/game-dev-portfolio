# materials — hand-off

Package `com.skyclone.mod.materials`, entry `MaterialsModule`. The fallback CATALOGUE of the game: every generic
material, vanilla-derived resource and ENCHANTED_* / compacted chain, with NPC sell prices, skull textures and
compression recipes — plus a player-facing **Materials Catalogue** and a **Compress** tool.

Data is generated, never typed: `py -3 tools/gen/materials/gen_materials.py` reads `research/data/items.json`,
`neu-repo/items` (recipes, unlock text, modern item models, skull textures), `collections.json` (collection keys and
recipe unlock tiers) and `bazaar.json`, and writes `plugin/src/main/resources/data/materials/`:

| file | contents |
|---|---|
| `items_<family>.json` | 1,348 ItemDefs: blocks 311, combat 164, farming 86, fishing 99, foraging 52, mining 180, misc 456 (138 ENCHANTED_*, 236 textured heads) |
| `recipes.json` | 603 CRAFTING recipes (243 gated by `collection:` / `slayer:` keys), ids `materials:<RESULT>#n` |
| `catalogue.json` | 200 compression chains (base -> next forms), collection tier tables for the self-tests |
| `items_integration.json` | 27 fallbacks (19 + 8 in wave 5) for ids other modules need and nobody registers (X/Y/Z, Glacial Fragment, Stretching Sticks...), from `gen_integration.py`, which `gen_materials.py` now runs itself |
| `tools/gen/materials/report.txt` | what was skipped and why, every price clamp, every unlock disagreement |

## What works (verified how)
- `bash tools/check.sh materials` -> **CHECK OK**.
- **15 JUnit tests pass** (`plugin/src/test/java/com/skyclone/mod/materials/MaterialsDataTest.java`, run with plain javac +
  the JUnit launcher from the gradle cache; the integrator's `gradlew build` runs them too). They run the SHIPPED JSON
  through the pure `ChainMath`: every recipe cell resolves to a catalogue id or a real 26.1.2 item type; every
  ENCHANTED_* item has a recipe; research numbers come out of the data (Enchanted Diamond Block = 25,600 Diamond,
  Enchanted Ender Pearl = 20, Enchanted Hard Stone = 576, Concentrated Stone = 576 EHS, Enchanted Bread = 60 Wheat,
  Enchanted Egg = 144, Silver Fang = 25 Ench. Ghast Tear, Enchanted Sugar Cane = 25,600 cane, Enchanted Sponge = 40,
  Enchanted Diamond sells 1,280, Diamond 8); **no recipe sells for more than its inputs**; unlock keys are well-formed;
  no reference proper noun survives in a display name; core's `vanillaId()` resolved against the core alias file gives
  `OAK_LOG -> LOG`, `COCOA_BEANS -> INK_SACK:3`, `MELON -> MELON_BLOCK`, `MELON_SLICE -> MELON`, `COD -> RAW_FISH`...
  and **no vanilla material resolves to an item that looks like something else** (this caught Lotus claiming every
  poppy and Magmafish claiming every cod before it shipped).
- NOT verified live (agents do not start the server). The 9 self-tests below are what to run.

### How registration works (postEnable, after every module's enable)
- An id another module registered is **left alone** (`ownedElsewhere`); the catalogue copy is unused.
- An id that is only core's automatic vanilla def (id = Material, COMMON, no price/lore) is **upgraded** with the
  catalogue def (dial `materials.replace-vanilla`). Detected via core's `isAutoVanilla` (reflectively; falls back to
  "looks exactly like an automatic def"). That is what gives Diamond its 8-coin price and "Collection Item" line.
- Vanilla-equivalent items keep vanilla stack sizes (Ender Pearl 16, buckets 1) — dial `materials.vanilla-stack-sizes`.
- Legacy reference ids (`LOG`, `WOOD:2`, `INK_SACK:3`, `SULPHUR`, `SEEDS`, `CARROT_ITEM`, `RAW_FISH`...) are registered
  with their modern material, so core's alias table converts every vanilla pickup into the collection id.
- A recipe is registered only when no other module already makes that result and every ingredient exists;
  everything skipped is listed by `/sb materials skipped` with the reason.
- Prices: NPC price from the research data; 42 missing prices derived from inputs; **62 outputs clamped** (61 + Soulflow, wave 5) to the value
  of their inputs (Enchanted Obsidian 1,440 -> 1,120, Enchanted String 576 -> 480, fences, dyes...) because research
  02 §5.3 says compacting must never be a coin printer. The original number is kept in `extra.referencePrice`.
- Unlock tiers: `collections.json` wins over NEU's crafttext where they disagree (25 cases, listed in report.txt).

### Player features
- `/materials [search]` (aliases `/mats`, `/catalogue`): **Materials Catalogue** — 7 family pages (paged), search,
  Compression Guide (every chain with raw cost per step), Compress Materials. Per-item detail: 3x3 recipe preview with
  clickable ingredients, unlock state, "other recipes" cycler, **Craft from Inventory** (left = one, right = all, capped by
  dial), the whole chain strip with raw cost and NPC value at each step, Recipe Book button (`/recipe <id>` if crafting
  registered it), Bazaar button (if the bazaar knows the item).
- `/compress`: every compression you can do right now from your inventory (unlocked recipes only), left-click once,
  right-click all. Inputs are counted and removed at click time, results go through `Sky.loot().give(.., CRAFT)` in
  stack-sized chunks (collections never count them, sacks may absorb them); a mid-craft shortfall refunds and crafts
  nothing; per-player cooldown.

## What is partial / not built
- **Fixed 2026-10-03 (playtest-loops): 11 live self-tests red + accessories `SellCapTest` red.** X, Y, Z, Stretching Sticks,
  Enchanted Bone Meal, Condensed Fermenta, Glacial Fragment, Wood Singularity, Freshly-Minted/Blood coins, Avaricious
  Chalice, Velvet Lilac (LUSHLILAC), Geometric Oddity, RECORD_13, LUCKY_CLOVER, Hilt of Omens, Warden's Handle, Diamond Lotus
  were unregistered, so Synthesizer V1-3, 3 armour recipes, the Farm Merchant's starter stock etc. could not be crafted/sold.
  Root cause was NOT the `^[XYZ]$` exclusion (that is deliberate: these ids live in `items_integration.json`, integrator
  round 1, fix 8) - it was the lore regen earlier the same day: `gen_materials.py` began its write step with a blanket
  `os.remove(items_*.json)`, which deleted `items_integration.json`, a file it does not generate; the loader skipped the
  missing file without a word. Fixes: the generator now deletes only stale FAMILY files and calls `gen_integration.main()`
  itself, so one command always ships both halves (gen_integration skips any id a family file already defines, writes LF,
  and now carries the reference `glow`); `MaterialsCatalogue.load()` logs a missing data file and records it, and
  `materials.catalogue-loaded` FAILS on it; an id in two files is no longer listed twice in its family page. Regenerated
  data is byte-identical for every other file; the 19 integration defs match the lost copy except `glow` on Glacial
  Fragment / Necron Handle / Enchanted Bone Meal. JUnit `integrationFallbacksShip` pins the file and all 19 ids (verified
  it fails, and SellCapTest fails on SYNTHESIZER_V1-3, with the file removed). 14 materials + 6 accessories SellCap tests pass.
- **1,348 items, not 1,500+.** Excluded on purpose: 719 minions (minions module), 236 furniture, 339 unique/unstackable
  gadgets (no abilities in a fallback def), 426 UI/menu/ability/backpack/rift-reversed/cosmetic ids (patterns in
  `EXCLUDE_PATTERNS`), 4 legacy items, ENCHANTED_* ids with no recipe in the data (6, all legacy).
- **Fixed 2026-10-03 (playtest-newplayer):** 304 vanilla-derived items (Stick, Book, Bread, Glass Bottle, Brewing Stand,
  potions, buckets, music discs...) all read "A common building block.". Root cause: the lore fallback was keyed on the
  FAMILY alone, and `blocks` is where every vanilla item with no collection lands. The generator now asks what each item
  IS from its 26.1.2 material (`VANILLA_EXACT` + `VANILLA_PATTERNS` -> `vanilla_blurb()` in `gen_materials.py`) and only
  falls back to the family line when it has no opinion, so the building-block line is now on 76 real blocks only
  (planks, wool, terracotta, glass, stone variants, dirt...). Same pass gave Sugar / Golden Carrot / Fermented Spider Eye a
  line under their bare "Brewing Ingredient" header, and Sapling (foraging) / Cactus Green (farming) a real line. Only
  `lore` changed in the data (verified id-for-id against the previous JSON). The family page is renamed
  "Blocks and Vanilla Items" (blurb: building blocks, food, tools...) since that is what it holds. JUnit
  `vanillaItemsDescribeWhatTheyAre` pins it, and fails on the old JSON.
- 12 items still use PAPER as their look, on purpose: Paper, Enchanted Paper, the recommendation letter, the stock
  certificate, plus the 7 integration fallbacks (X/Y/Z, Glacial Fragment, Diamond Lotus, Velvet Lilac, Condensed
  Fermenta) whose looks belong to the modules that asked for them (`gen_integration.py`). 236 use reference skull textures.
- Item lore is our own words, never the reference descriptions. ~~32 misc items still read "A crafting material."~~
  **Done wave 5**: `MISC_MANUAL` describes every one; 0 generic left (JUnit `noMiscItemIsGeneric`).
- Chains follow ONE primary recipe per output (makes exactly one, most inputs), so alternative crafts (Enchanted Diamond
  from Blocks of Diamond -> 9) show only in the detail page's recipe cycler. By design (the reference's own recipe
  viewer does the same); not a gap.
- ~~The SkyBlock Menu entry~~ **done by menus** (`FeatureIndex` "Materials Catalogue" -> `/materials`).
- **Island blocks are lore only** (their behaviour needs placed-block tracking on the private island, which is the island
  module's): Day Saver / Night Saver / Weather Node / Haste Block / Silence Block. Their lore says what they are for.
- **Anvil items are lore only**: Prickly Creeper (+1 Thorns), and the Turbo Gourd / Enchanted Turbo Gourd pair is not in
  the catalogue at all (both are unique anvil upgrades for Turbo-Crop, enchanting's anvil). See Requests 9.
- **Refined Dark Cacao Truffle** (+30 Cocoa Beans Fortune 60m, +1 permanent up to 5) is lore only: per-crop fortunes are
  not a `Stat` (accessories keeps them in `extra.otherStats`); farming would have to read a per-crop bonus.

## Finish wave 2026-10-03
- Playtest note "304 vanilla items read 'A common building block.'" was already fixed in the data by the
  playtest-newplayer pass (re-verified: Stick, Book, Bread, Glass Bottle, Brewing Stand, Potion, buckets, discs all
  describe themselves; the line is on 76 real blocks only).
- **Misc lore**: the 422 misc items that all read "A crafting material." now say what KIND of thing they are
  (`MISC_KINDS` + `misc_blurb()` in the generator: boss fragment, fossil, gemstone, lamp, island decoration, organic
  produce, bait, food, brew, fuel, fertiliser, coin/token, machine part, metal stock, trophy, hide, fibre, powder,
  plant, magic, ice, liquid, critter, remedy, gift box, curiosity...; research flags `dungeon_item` / `museum` as the
  fallback). 390 described, 32 left generic (report.txt prints the split).
- **"Used to craft" line** on every item whose lore was only a fallback line (any family, 292 items): built from EVERY
  reference crafting recipe, whatever it makes (gear, talismans, minions as "Wheat Minion" without the tier), max two
  names + "and N more". Output names come from what other modules SHIP (their data JSON, then `docs/names/*.md`), so a
  renamed reference noun never comes back through lore (Gunther's Sneakers prints as Racer's Sneakers); `ID;N` ids
  (books/pets/shards) and names that still match a proper-noun list are skipped.
- **PAPER looks**: 18 custom items that were plain paper now have a vanilla look (`MANUAL_LOOK`: Fuel Canister ->
  cauldron, Chirping Stereo -> jukebox, GFX 3070 -> comparator, prizes -> gold ingot...).
- Only `lore` and those 18 `material` fields changed; `recipes.json`, `catalogue.json`, `items_integration.json` are
  byte-identical. New JUnit `miscItemsSayWhatTheyAre` (fails on the old JSON: 422 of 456 generic); 15/15 pass;
  `check.sh materials` OK. Not verified live (no server this wave).

## Wave 5 (2026-10-06)
Verified: `bash tools/check.sh materials` -> CHECK OK; **21/21 JUnit** (`MaterialsDataTest`, 6 new) run with plain javac +
the JUnit launcher from the gradle cache; `py -3 tools/gen/materials/gen_materials.py` regenerates everything in one run.
Data diff against the pre-wave JSON: only the fields listed below changed (recipe GRIDS, unlocks, amounts and
`catalogue.json` are identical; recipe ids changed only by the `vanilla:` prefix). NOT verified live (no server).

### Done
- **8 dead ingredients now resolve** (cross-referenced every data file of every module against every definition, data
  and Java): `GOLDEN_FRAGMENT`, `WINTER_FRAGMENT` (starred Midas/Daedalus/Glacial gear, the Snowman workshop trades),
  `VOLCANIC_ROCK` (the Courier's fragment trades, sacks), `SHIELD_DUST` (Witch-year trade, sacks), `PLASMA_NUCLEUS`
  (Plasmaflux power orb, crafting + slayer), `OMEGA_EGG` (Droplet Wisp), `EXPORTABLE_CARROTS` (Capsaicin Eyedrops, Rift
  economy), `COLOSSAL_EXP_BOTTLE_UPGRADE` (bits shop + Colossal Experience Bottle). Fallbacks in `items_integration.json`
  (now 27), our lore, registered only if absent; the three unique ones are one per slot (`ONE_PER_SLOT`; the older 19
  keep stacking because other modules' cells may want several). Self-test `materials.wave5-fallbacks`.
- **Live sell cap** (research 02 §5.3 at LIVE prices): after every module registered, any compression whose result
  materials owns is re-priced so it never sells for more than its inputs at the prices the server actually uses (an
  input another module registers may be cheaper than the research said). Pure rule `ChainMath.cappedPrice`; only
  materials' own defs are touched; old price kept in `extra.referencePrice`; "no input sells -> neither does the result"
  only when the research also says the inputs are unsellable (a module that forgot a price must not zero the enchanted
  form). Dial `materials.live-sell-cap`, admin `sellcap`, self-test `materials.live-sell-cap`. The generator applies the
  same unsellable rule: **Soulflow** (1 coin from 160 unsellable Raw Soulflow - the one "materials" printer accessories'
  SellCap replica flagged) is 0 now. Note: the soulflow module owns SOULFLOW since wave 5, so this copy is unused there.
- **Four consumables work** (`Consumables.java`; behaviour exists exactly while materials owns the def, because the
  ability id is on our generated def): **Chyme** right-click -> +90,000 exp to the active pet (`Features.Pets.addPetXp`);
  **Filet O' Fortune** (needs `hotm:4`) -> +20 Mining Fortune for 60m (a second one never shortens a running one);
  **Reaper Pepper** -> permanently +1 Health and +1 Cold Resistance, max 5; **Saving Grace** (carried, passive) -> a
  death that would cost coins costs none, eats one, and respawns you at `PrivateIsland.home`. State is member data
  `materials` (`reaperPeppers`, `filetUntil`, `savingGracesUsed`); stats via provider `materials.consumables`.
  Self-tests `materials.consumables-wired`, `materials.consumables-stats`, `materials.saving-grace`.
- **Wrong looks fixed** (`MATERIAL_FIX`): Player Head (`SKULL_ITEM:3`) was drawn as BAMBOO (the learned legacy table maps
  (SKULL_ITEM,3) to bamboo's 1.8 head); Wither Skeleton Skull and Dragon Head were plain player heads; Enchanted Golden
  Apple was a plain golden apple (now also claims enchanted-apple pickups). Self-test `materials.looks-and-names`.
- **Shops request 9**: `SANDSTONE:2` "Cut Sandstone", `RED_SANDSTONE:2` "Cut Red Sandstone", `RED_SANDSTONE:1`
  "Chiseled Red Sandstone", `STEP` "Smooth Stone Slab" - the name of the block the buyer actually gets.
- **Names other modules already ship**: Fermento -> Fermenta (garden), Zog Anvil -> Tamer's Anvil (pets),
  Gunthesizer Lichen -> Racer's Lichen (the racer is Gustav). `docs/names/materials.md` regenerated.
- **Crafting request 8**: 279 vanilla-from-vanilla crafts (Block of Diamond, planks, sticks, stairs, dyes...) now have ids
  `vanilla:materials/<RESULT>#n`, so crafting files them in the Vanilla book; collection-gated ones (Packed Ice, Cobweb)
  and every ENCHANTED_ craft stay `materials:` SkyBlock recipes. Self-test `materials.vanilla-recipes`.
- **Core request 4**: reads `ItemService.isAutoVanilla` through the API (no reflection), keeping the look-alike fallback.
- **32 generic misc items described** (`MISC_MANUAL`): 0 left on "A crafting material.".
- Read accessories/pets/reforges hand-offs for effects belonging to materials: none (accessories' personal compactor
  already uses its own engine; no pet or reforge acts on compression or catalogue prices).

### Still left
- Island blocks (Day/Night Saver, Weather Node, Haste/Silence Block), Prickly Creeper, the Cacao Truffle: lore only
  (see "What is partial"). Turbo Gourd pair not shipped (Request 9).
- Sources for the new fallbacks are other modules' (Request 8): until then Golden/Winter Fragment come only from the
  Snowman workshop trades, Volcanic Rock and Shield Dust only from the bazaar.
- Saving Grace works through `SkyDeath` (the death still happens, it just costs nothing and sends you home); the
  reference catches you BEFORE dying. A pre-lethal hook in core would make it exact.

## Commands added (player + admin)
| command | what |
|---|---|
| `/materials [search]` (`/mats`, `/catalogue`) | Materials Catalogue (families, search, guide, compress, details) |
| `/compress` | everything compressible from your inventory right now |
| `/sb materials report` | counts: data, registered, upgraded vanilla, owned elsewhere, recipes in/skipped, per family |
| `/sb materials info <id>` | owner, material, rarity, price, stack, family, recipes, uses, chain |
| `/sb materials chain <id>` | each step's raw cost in base units, sell value vs raw value, PRINTER flag |
| `/sb materials compress <player> <id> [times]` | runs the real compressor for a player (RCON-testable) |
| `/sb materials open <player> [family\|guide\|quick\|<id>]` | opens a catalogue menu for a player |
| `/sb materials unlocks on\|off` | flips the recipe-unlock dial live |
| `/sb materials skipped [n]` | skipped recipes with reasons |
| `/sb materials printers` | live check: registered recipes whose output sells for more than inputs |
| `/sb materials sellcap` | (wave 5) re-runs the live sell cap, lists every price it lowered (old -> new) |
| `/sb materials consumables <player> [pepper <n>\|filet <minutes>\|reset]` | (wave 5) peppers eaten, filet time left, graces used; set/reset |
| right-click Chyme / Filet O' Fortune / Reaper Pepper; carry a Saving Grace | (wave 5) the consumables (menu path: `/materials` -> Other Materials, or `/materials chyme`) |

## Dials added
`materials.register-items` true (restart) · `materials.replace-vanilla` true (restart) · `materials.register-recipes`
true (restart) · `materials.recipe-unlocks` true (live: false = all catalogue recipes unlocked) ·
`materials.compress-enabled` true · `materials.compress-max-per-click` 64 · `materials.compress-cooldown-ms` 250 ·
`materials.compress-from-sacks` false · `materials.vanilla-stack-sizes` true (restart).
Wave 5: `materials.live-sell-cap` true (restart; `sellcap` re-runs it) · `materials.consumables` true · `materials.chyme-pet-xp`
90000 · `materials.filet-mining-fortune` 20 · `materials.filet-minutes` 60 · `materials.reaper-pepper-cap` 5 ·
`materials.saving-grace-home` true (all live).

## Self-tests added
`materials.catalogue-loaded` · `materials.items-exist` (every catalogue and chain id is registered by someone) ·
`materials.recipes-ingredients-exist` (every result/ingredient of every recipe materials registered) ·
`materials.enchanted-have-recipes` (every ENCHANTED_* catalogue id has a recipe in the live registry) ·
`materials.research-numbers` (14 raw-cost formulas + Diamond/Enchanted Diamond prices, through the LIVE recipes) ·
`materials.no-coin-printer` · `materials.unlock-keys` (prefix, id exists, is a collection when the collections module
is installed, tier within that collection's tiers) · `materials.vanilla-pickups` (live `vanillaId` for 9 materials +
Ender Pearl stack 16) · `materials.menus-build` (main, 4 detail pages, 7 family pages build without a player).
Wave 5 (`MaterialsWave5Tests.java`): `materials.live-sell-cap` (pure rule on made-up recipes + nothing of ours sells over
its live inputs) · `materials.wave5-fallbacks` (8 ids registered, unique ones one per slot, a live recipe uses them) ·
`materials.looks-and-names` (7 fixed looks/names, data and live, pickups never land on another look) ·
`materials.vanilla-recipes` (>=100 Vanilla-book crafts, none touches ENCHANTED_) · `materials.consumables-wired` ·
`materials.consumables-stats` (pepper cap, filet on/off, expiry never shortens) · `materials.saving-grace` (SkyDeath
listener registered, passive ability on the def).

## Requests (integrator)
1. ~~**menus**: add a "Materials Catalogue" entry~~ DONE (FeatureIndex). Optional still: "Compress Materials" -> `"compress"`.
2. **crafting**: vanilla recipes must use the REFERENCE ids for vanilla materials that materials registers under
   legacy ids (`LOG`, `LOG:1`, `WOOD`, `WOOD:n`, `INK_SACK:n`, `SULPHUR`, `SEEDS`, `CARROT_ITEM`, `POTATO_ITEM`,
   `RAW_FISH`, `NETHER_STALK`, `ENDER_STONE`, `MYCEL`, `SNOW_BALL`, `RED_ROSE`, `YELLOW_FLOWER`, `MELON` = slice,
   `MELON_BLOCK` = block, `CLAY_BRICK` = brick item, `BRICK` = bricks block, ...). After materials registers them,
   picking up an oak log yields `LOG`, never `OAK_LOG`, so a recipe that asks for `OAK_LOG` can never be crafted.
   `/sb materials info <id>` shows the material behind any id. My own 603 recipes already use reference ids and
   include the plank/stick/vanilla block crafts the data knows.
3. **core (RecipeCells)**: there is no way to write "N of an id whose `ID:N` is itself an id" (2x `WOOL` vs
   `WOOL:2` = Magenta Wool). The generator found none in the data (it drops such recipes and logs them), but an escape
   such as `ID*N` would make the format total.
4. ~~**core (ItemService)**: expose `isAutoVanilla(String)`~~ DONE in core-wave5; materials calls it directly.
5. **shops / bazaar**: NPC *buy* prices must stay above the catalogue's NPC *sell* prices or buying raw + compressing +
   selling prints coins; `/sb materials printers` and the self-test only guard the sell side.

6. **garden**: two visitor wants are mistranslated ids that a player can never obtain. `RECORD_13` should be `GOLD_RECORD`
   (the reference id of Music Disc - 13; every vanilla 13 disc pickup converts to it) and `LUCKY_CLOVER` should be
   `PET_ITEM_LUCKY_CLOVER` (pets registers it; there is no plain LUCKY_CLOVER in the reference data). Fix the name map in
   `tools/gen/garden/gen_garden.py`. Until then materials keeps a fallback def for both so the id resolves, but the visitor
   asking for them cannot be served. (Still open in wave 5: `garden/visitors.json` still asks for both.)
7. **island** (wave 5): Day Saver / Night Saver (island time), Weather Node (island weather), Haste Block (+100 Speed
   standing on it), Silence Block (mutes island mobs) are catalogue items with lore only; their effect needs placed-block
   tracking on the private island. Register your own defs (yours win) or read the ids.
8. **mayors / shops** (wave 5): sources for the new fallbacks - Golden/Winter Fragment (Snowman workshop event drops),
   Volcanic Rock (reference: Courier's trade currency), Shield Dust (10% from shielded mobs in the Witch year). They are
   tradeable today (your trades and the bazaar) but nothing drops them.
9. **enchanting** (wave 5): Turbo Gourd -> Turbo-Crop V to VI, Enchanted Turbo Gourd -> VI to VII on a farming tool
   (anvil). Not shipped by materials because the anvil is yours; crafting already carries the Enchanted Turbo Gourd
   recipe and needs `TURBO_GOURD` registered. Also Prickly Creeper (+1 Thorns, once, needs Thorns III).
10. **crimson** (wave 5): Exportable Carrots (now registered by materials) turn in 3,000 at the carrot collector on the
    Crimson Isle for +5 Health and +12 Carrot Fortune, permanently (reference `item_specific`).
11. **farming** (wave 5): Refined Dark Cacao Truffle needs a per-crop fortune reader (+30 Cocoa Beans Fortune 60m, +1
    permanent, up to 5).
12. **core** (nice to have): a pre-lethal damage hook would let Saving Grace catch the player before the death, as the
    reference does, instead of through `SkyDeath`.

## Known risks
- **Legacy-id conversion is global.** Once materials registers `LOG`/`INK_SACK:3`/..., every vanilla pickup of those
  materials converts to the reference id. Any module (crafting, minions, shops) that uses modern names like
  `OAK_LOG`/`COCOA_BEANS` for those items will silently never match. Request 2.
- **Replacing core's automatic vanilla defs** changes stack sizes and adds prices/lore for up to 168 vanilla items (ids equal to a Material name); if a module
  registers one of those ids in `postEnable` (after materials) it now gets "duplicate ignored". Specialists must register
  in `enable()` as the contract says.
- **Price clamps** move 62 reference sell prices down to their input value (listed in report.txt); if the owner prefers
  the reference numbers, delete the clamp block in the generator — and accept that those recipes print coins.

## Fix 2026-10-06 (integrator follow-ups from the real-game audit lanes)
Regenerated with `py -3 tools/gen/materials/gen_materials.py` (it reproduced every file byte-for-byte first).
- **Delver's O's cereals** (`DWARVEN_OS_ORE_OATS / _BLOCK_BRAN / _GEMSTONE_GRAHAMS / _METALLIC_MINIS`): integration
  fallbacks for mining's Scatha and corpse loot. Right-click eats one: **+2 Ore / Block / Gemstone / Dwarven Metal
  Fortune for good, for the first 5 of each kind** (`Consumables.cereal`, member data `materials.cereals`). Dials
  `materials.cereal-cap` 5, `materials.cereal-fortune` 2. The brand is renamed (Dwarven O's -> Delver's O's).
- **NETHER_FORTRESS_BOSS_TRAVEL_SCROLL** "Dusty Travel Scroll to the Forgotten Skull" (category TRAVEL_SCROLL,
  `extra.warp` = `crimson_skull`) is registered so the Leviathan T5 chest can hand it out. **Reading it does nothing
  yet.** Request: crimson adds a fast-travel point at the Forgotten Skull and consumes the scroll on right-click
  (progression's TravelScrolls then awards its SkyBlock XP by itself).
- **SUMMONING_RING** is registered (Rare, one per slot, tag `dungeon_item`) so the F6 chest roll is no longer skipped.
  It is not an accessory in the reference; it is a held dungeon item. **Its Raise Souls ability is not implemented.**
  Request: the dungeons module owns it.
- **SHARD_OF_THE_SHREDDED** fallback renamed "Shredded Sinew" -> "Shredded Tendon" (`RENAME_WHOLE`), matching slayer.
- `docs/names/materials.md` now also lists the integration fallbacks whose names differ from the reference.
- Mining can now drop `DWARVEN_OS_METALLIC_MINIS` from corpses, but `tools/gen/mining/gen_mining.py` `CORPSE_SKIP`
  still leaves its weight out (that file belongs to the mining lane and was not touched).
- Tests: `MaterialsDataTest.auditFollowUpFallbacksShip`; `pureConsumables` checks the cereal stats and cap.
  `bash tools/check.sh materials` CHECK OK.

## Cross-module follow-ups (2026-10-06, integrator list in docs/FIX_BRIEF_2026-10-06.md)
- `SUMMONING_RING` now carries the Raise Souls ability (`weapons_raise_souls`) plus `extra.src = "weapons"`,
  `extra.w.souls = 2`, so the weapons module's soul machinery runs it (gen_integration.py: `extra_*` keys merge into
  `extra`). Test: `MaterialsDataTest.integrationRingAndScrollAreWired`.
