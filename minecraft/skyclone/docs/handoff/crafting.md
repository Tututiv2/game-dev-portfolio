# crafting — hand-off

Package `com.skyclone.mod.crafting`, entry `CraftingModule`. Data in `resources/data/crafting/`, generators in
`tools/gen/crafting/` (`gen_vanilla.py`, `gen_skyblock.py`, `gen_furniture.py` — rerun them, never hand-edit the JSON).
Member data key **`crafting`** (`CraftData`). Provides no Features interface (none exists for crafting); other
modules reach it through its commands.

## Wave 5 (2026-10-06)

Worked the whole "partial / not built" list, behaviour gaps first. `bash tools/check.sh crafting` -> **CHECK OK**;
**37 JUnit tests pass** (24 -> 37: `LootTablesTest` 6, `FurnitureMathTest` 5, `TreePickTest` 2). Server not started
(lane rule): the Bukkit-side parts are compiled and covered by 3 new self-tests, not clicked.

### Done
- **Furniture does what its reference lore says** (it was decoration only). The NEU lore of all 37 pieces was read;
  each piece now carries a `function` in `furniture.json` (generator `gen_furniture.py`, pure rules in `FurnitureMath`):
  - **Storage** (12 pieces: the 9 Chest+, Chest Storage, Medium Shelves = 3 pages; Skull Chest++ = 6 pages, as the
    lore says; Chest Storage's count is TUNE). Right-click opens 45-slot pages with Previous/Next. Contents live in
    a new table **`crafting_furniture_store`** (one row per PLACED piece, keyed by a uuid now stored in the chunk
    record), so co-op members share it and an island rebuild does not take the items with it. Public-server rules:
    one viewer at a time (a second player is told "Someone else is using this"), only island builders can open it,
    a piece comes up only EMPTY and only when nobody has it open, and a piece whose blocks vanished (rebuild/admin)
    has its row **orphaned** and handed back to the owner on their next login (row deleted first: a crash can lose,
    never dupe). Every write is on close; `onFreeSlotChange` keeps the in-memory copy current; disable closes open
    pieces and waits up to 5 s for the writes. Registered as a core **dupe-scan source** (`crafting_furniture`).
  - **Item Showcase** (Armor Stand: 4 armour pieces + a sword/pickaxe/axe worn by a real armour stand; Weapon Rack:
    4 weapons as item displays on the rack; Weapon Rack+: 1 weapon + a **stats hologram** (rarity name, damage, top 4
    stats)). Slots only take the right gear (a wrong item goes back to the inventory). Displays are never-persistent
    entities rebuilt on chunk load and boot; the Armor Stand piece is now a slab pedestal with 2 blocks kept free
    (`space`) for the stand.
  - **Stations** with the real working block, answered by its owner: Crafting Table+ (our grid), **Furnace+** (a
    working vanilla furnace; its contents are handed back on pick-up), **Brewing+** (brewing stand -> alchemy's
    personal stand), **Enchanting Table+** (enchanting table, 2 bookshelves -> enchanting's table menu),
    **Blacksmith+** (anvil -> enchanting's Anvil menu). Brewing+/Enchanting+/Blacksmith+ builds changed (HEAD ->
    brewing_stand / enchanting_table / anvil); a pre-wave-5 placed copy keeps working, its changed block just reads
    as not-the-piece.
  - **Large Bed / Hay Bed**: "Use to set your spawn location!" -> runs island's `/setspawn` (own island only; the hay
    bed's carpet is still a seat). **Water Trough**: fills a plain bucket or glass bottle, endlessly.
    **Grandfather Clock**: tells the SkyBlock time and date. Item lore now matches the reference layout
    ("Furniture" / "Seat" / "Storage" / "Item Showcase" header lines + one line saying what it does).
  - Sneaking with a block in hand places against any piece (as on vanilla workstations).
- **How to Get sees loot rolled in code** (core declined `LootService.sourcesOf`). New pure `LootTables` reads the
  OTHER modules' published data, read-only: `slayer/slayers.json` (per-tier chances, first tier, slayer LVL),
  `dungeons/loot.json` (floor + cheapest chest + claim cost, Entrance..Master Mode VII), `kuudra/loot.json`
  (armour by tier prefix, weapons, equipment, rares with chance; the boss shown by OUR name, read from the
  `leviathan_basic` MobDef), `fishing/fishing.json` (catch share, treasure share by quality). New icons: Slayer Boss
  Drop (click -> /slayer), Dungeon Reward Chest, Boss Reward Chest, Fishing (click -> /fishing). Revenant Flesh, a
  Necron's Handle or a Burning Kuudra Core no longer say "No Known Source"; `/sb crafting sources <item>` prints
  them too. A missing file or changed shape just finds nothing.
- **Mutations sacks gated for real**: `gen_skyblock.py` now writes `garden:analyzer:N` (Crop Analyzer Milestone N)
  instead of `never:`; regenerated `skyblock_recipes.json` differs in exactly those 3 lines. Garden owns the prefix
  and its resolver does not parse `analyzer` yet, so they fail CLOSED (locked) until it does; we print "Crop
  Analyzer Milestone V" ourselves meanwhile (`CraftEngine.describe`). See Requests 6.
- **Raw Materials picks the recipe on purpose** (`CraftMath.treePick`, unit tested): never a vanilla recipe, never an
  UNPACKING on a crafting cycle (1 block -> 9 ingots), the packing direction is followed, and among the rest the
  cheapest inputs per item made wins (unpriceable last). Was: whichever recipe registered first. `/sb crafting
  tree` uses the same rule.
- `Requirements.known(prefix)` is on the api now: the `crafting.unlock-keys` reflection is gone.
- **Accessories / pets / reforges**: read their hand-offs for effects that belong to crafting. None do: Carpentry
  Wisdom is applied by skills' `addXp` automatically, the Personal Compactor compacts in accessories' own code (and
  the reference says it grants no Carpentry XP, which matches: it never goes through this engine), and no pet or
  reforge touches crafting. Nothing to wire.
- Admin: `/sb crafting furniture storage` (table ok, pieces loaded, showcases displayed), `furniture orphans <player>`
  (hand back orphaned furniture items now), `furniture list` shows each piece's function.

### Still left
- Nothing here was clicked in a client: storage pages, the one-viewer lock, showcase displays (positions/rotation
  were derived and unit-tested against the block rotation, not looked at), the hologram, Furnace+ contents on pick-up,
  bed/trough/clock, the new How to Get icons. First live check: `/sb selftest crafting`, then
  `/sb crafting furniture give <p> WOOD_CHEST` and `... ARMOR_SHOWCASE`, place, fill, pick up.
- Minion Chair "seats a single minion": needs minions (Requests 11); players can sit on it meanwhile.
- Teleport Pad trade still "Not available" (no module registers TELEPORT_PAD).
- Trade costs remain TUNE: NEU's `trade` recipes are villager/emerald trades, not the collection Trades.
- Mutations sacks stay locked until garden parses `garden:analyzer:N`.

## Wave 3 playtest fix (2026-10-04) — Craftable Now order

- **Minions missing from Craftable Now (fixed).** The playtest had 88 Oak Logs + a Wooden Axe and never saw Oak
  Minion I there. It WAS in the list — on the last page: the list was sorted by result value alone, a minion (like
  a fresh tool) has no NPC/bazaar price, so it sat behind every plank, slab, stair, fence and door the same logs
  make. New pure `CraftMath.sortCraftable` / `craftWorth`: SkyBlock recipes first, vanilla last; within each, by the
  better of the result's value and its ingredients' value (cheapest priced id per cell, as `SellCap` reads them),
  then result id. Quick Crafting (16/25/34) used the same value-only score and now uses the same order; it also
  checks the unlock before claiming a result, so a locked recipe can no longer hide an unlocked one for the same
  item. The Craftable Now button says "SkyBlock recipes first, vanilla last." Unit test
  `craftableNowPutsUnsellableMinionFirst` (24 tests). `bash tools/check.sh crafting` -> CHECK OK. Not clicked live.

## Wave 3 (2026-10-04) — what changed

- **How to Get page (new `Sources`).** The Recipe Book used to answer "Nothing crafts this item." for anything
  forged, bought, brewed or smelted, and clicking such an ingredient inside a recipe only listed what USES it —
  a dead end at the first Refined Mithril, Glass or Pickaxe Smith pickaxe in any recipe tree. Now every such click,
  `/recipe <item>` and Search land on **How to Get: <item>**: the crafting recipe (if any), every FORGE / NPC_SHOP /
  OTHER (brewing stand...) / SMELTING recipe other modules registered in `Sky.recipes()` (inputs with have/need
  ticks, forge time, unlock with ✔/✖), **vanilla furnace smelting** (read from the server's own `FurnaceRecipe`s
  on the first tick: Sand -> Glass, Cobblestone -> Stone, logs -> Charcoal, raw food...; click walks to the input's
  own page), **Mob Drop** (every MobDef whose loot table holds it, level + chance, best odds first), **Bazaar**
  (instant buy/sell, click opens it), **Auction House** (lowest BIN), **Collection Item**, else "No Known Source".
  Bottom row: Used In (grid), **Station Uses** (forge/brewing recipes that consume it, own paged list) and
  **Smelts Into**. Every recipe view gained a **How to Get** compass at slot 41, and Used In (43) also counts
  station uses (and opens them when no grid recipe uses the item). `/recipe` tab completion and Search include
  forged/smelted/shop items. Display only — nothing on these pages crafts, buys or forges.
- **Raw Materials walks through forged intermediates** (`Sources.forgePattern`: a FORGE recipe read as a shapeless
  pattern, coins dropped, unique results never decomposed), and **tracks leftovers** (`CraftMath.rawAll`): a recipe
  that makes 4 needed once in two branches is crafted once, and the ingredients of one recipe share spares. The
  old "estimate, ignores leftovers" caveat is gone. `/sb crafting tree` follows forge recipes too.
- **XP loops of any length.** `CraftMath.reversible` is now a strongly-connected-component cut (iterative Tarjan,
  `CraftMath.components`): any recipe whose result and one of its ingredients lie on a common cycle of the whole
  recipe graph pays no Carpentry XP — A -> B -> C -> A included, not only direct pairs. On today's data it cuts the
  same 17 recipes plus Gold Nugget (vanilla ingot <-> nugget closes it); the self-test `crafting.no-xp-loops` checks
  cycles too.
- **"Did you mean" in the Craft Item grid.** A grid that makes nothing used to say only "Check the Recipe Book".
  Now the result barrier lists up to 3 recipes that use EVERY item in the grid (`CraftMath.nearMisses`, pure):
  unlocked first, then fewest missing ingredient kinds, then fewest cells — "(check shape/amounts)" when every
  kind is already there (e.g. 9 single Diamonds -> Enchanted Diamond), "(+1 more item)" otherwise. Clicking it
  opens the top suggestion's recipe view (its back button returns to a fresh grid; the grid items go back to the
  inventory as with any menu switch). Recomputed only when the set of ids in the grid changes.
- **Crafter guard.** The vanilla Crafter block never fires `PrepareItemCraftEvent`, so core's vanilla guard did not
  see it: a hopper-fed Crafter could turn Enchanted Diamonds (DIAMOND underneath) into plain Diamond Blocks, or any
  cheap custom item with a valuable material into vanilla value. `CrafterCraftEvent` is now cancelled whenever the
  Crafter holds a custom sky item.
- **Data changes elsewhere noticed:** `ENCHANTED_BONE_MEAL` is now registered (materials `items_integration.json`),
  so the Bone II trade is live; only `TELEPORT_PAD` still shows "Not available".
- Admin: `/sb crafting sources [item]` (the How to Get page as text; no item = furnace recipe count). Self-test
  `crafting.sources`. Unit tests 19 -> 23 (`longerCyclesAreFound`, `rawTreeSharesLeftovers`, `nearMissesRankByMissingKinds`,
  `dropChanceReadsLikeTheReference`, ...). `bash tools/check.sh crafting` -> CHECK OK. Server not started (phase rule).

## Review pass (2026-10-02) — what changed

A second agent reviewed every file against CONTRACT, CORE_GUIDE, the brief and research 02/05/08, then fixed:

- **Infinite Carpentry XP loop (fixed).** materials registers block <-> ingot pairs as its OWN recipes
  (DIAMOND <-> DIAMOND_BLOCK, IRON, GOLD, EMERALD, REDSTONE, COAL, SLIME, WHEAT <-> HAY_BLOCK, WOOL re-dye: 17 found
  in the data). Being non-vanilla, both directions paid 3% XP, so crafting back and forth printed skill XP and the
  skill's coin rewards (Carpentry pays coins per level). `CraftMath.reversible` now finds every recipe another
  recipe undoes (or that consumes its own result); those pay **no** Carpentry XP. The value readout and
  `/sb crafting match` say why. Self-test `crafting.no-xp-loops` asserts no two XP-paying recipes undo each other.
- **Furniture dupe via stale records (fixed).** A placement record whose blocks were replaced (island rebuild,
  admin build mode) used to make ANY block later placed at that spot "furniture": breaking it handed back the
  whole piece and set the other positions to air. Records now check each position still holds the block the
  piece put there (`holds`); a fully stale record is dropped when something is placed over it, and pick-up only
  removes blocks that are still the piece's own.
- **Furniture containers fillable while holding furniture (fixed).** The "decoration only" guard returned early
  when the hand held a furniture item, so a furniture barrel/furnace opened and could be filled — contents were
  deleted on pick-up. Now always denied; off-hand clicks denied too. Hoppers can no longer feed or drain them.
- **Furniture lost/duped by its support (fixed).** Breaking the block under a torch/pot/carpet popped it off as a
  loose vanilla item and left a ghost record; water did the same. The block under furniture now refuses to
  break ("Pick up the furniture on top of this block first!") and liquids cannot flow into furniture.
- **Seat bug (fixed).** Warping while seated pulled the player back to the chair a tick later; now only a player
  still at the seat is lifted. Picking up a piece stands its sitters up first.
- **Off-hand vanilla table (fixed).** The off-hand pass of a right-click on a crafting table could open the
  vanilla table behind the Craft Item menu.
- **Vanilla remainders (added).** A plain Milk/Water/Lava Bucket or Honey Bottle consumed by a recipe (the cake)
  hands its empty container back, like vanilla. Enchanted Lava Buckets etc. are not plain and are fully consumed.
- **Supercraft picks the right copy (added).** For an upgrade recipe (same-category unique ingredient) the MOST
  upgraded copy in the inventory is taken and carried over; for any other recipe the PLAINEST copy is taken, so a
  player's enchanted gear is never eaten when a bare copy would do.
- **Gates became real (data).** 7 Diamond dungeon heads now unlock at `collection:BOSS_<BOSS>:<tier>`
  (collections owns those) and the Museum Travel Scroll at `museum:20` (progression owns it). Only the 3 Mutation
  sacks remain `never:` (no crop-analyzer resolver). `gen_skyblock.py` re-run; trades/aliases/collections output
  byte-identical.
- **Trades.** Shopping Spree (mayor perk `diaz.shopping_spree`, dial) multiplies the 640/day limit x10
  (research/05 §4.4, same perk id as shops). The 2 trades whose items no module registers (TELEPORT_PAD,
  ENCHANTED_BONE_MEAL) are now LISTED as "Not available" instead of silently missing (see Requests).
- **Performance.** Every block break, liquid flow and hopper move asked "is this furniture?" with 9 chunk-PDC
  lookups; now one set lookup per world (`marked`, kept from ChunkLoad/Unload and saves). Quick Crafting (a scan
  over every recipe) recomputes only when the inventory changed, after a craft, or every 5 s — not every second.
- **Robustness.** A CRAFTING recipe given only as `inputs` (no grid) compiles as shapeless instead of vanishing;
  `/craft` refuses before ProfileReady; Quick Craft clicks now say why they failed; `bypass` wording says it
  lasts until logout (it is cleared on profile unload). Category mapping knows `BOSS_*`, `hotf`, `trophy`.

## What works (verified how)

Verified: `bash tools/check.sh crafting` -> **CHECK OK**; **37 JUnit tests pass** (CraftingPureTest, SellCapTest, and
wave 5's LootTablesTest, FurnitureMathTest, TreePickTest)
(`plugin/src/test/java/com/skyclone/mod/crafting/CraftingPureTest.java`; run with plain javac + the JUnit
launcher, script in `$TMPDIR/skyclone/crafting/runtests.sh`). The server was NOT started (integrator's job):
everything Bukkit-side is compiled against the 26.1.2 API and covered by the 12 self-tests below.

- **Craft Item menu** (`/craft`, any crafting table right-click, the SkyBlock Menu's Crafting Table via
  `/craft`): 6 rows, free 3x3 grid at 10-12/19-21/28-30, result at 23, Quick Crafting at 16/25/34, status strip
  along the bottom (green = craftable, orange = right shape but a slot holds too few, red = nothing/locked),
  Return Items (38), Recipe Book (50), Go Back to the SkyBlock Menu (48) when `Features.SkyMenu` exists.
  Click = craft once, shift-click = craft max (dial cap). Core returns grid items on close (the menu does not
  call `keepFreeSlotItems`; self-test `crafting.grid-menu`). Shift-click from the inventory fills the grid (core).
  Sneak + block in hand still places a block on a table.
- **Matching engine** (pure, unit tested): per-cell AMOUNTS ("32 Diamonds in this slot", consumes exactly that
  per craft, the rest stays in the grid), shaped at any offset + optional mirror (dial), shapeless with
  augmenting-path matching (overlapping tag sets never false-negative), vanilla TAG cells ("any planks").
- **Canonical ids**: a log is "LOG" if materials registered it, "OAK_LOG" otherwise, "LOG:2" in reference data;
  `RecipeIndex.canon` folds them all onto the id the player's stacks actually carry, so recipes from every
  module match. A material whose id another module re-used for a different item (the reference "MELON" is a
  slice) is detected as SHADOWED and that vanilla recipe is skipped rather than producing the wrong item.
- **Vanilla recipes**: 946 shaped/shapeless recipes generated from the real 26.1.2 server jar (tags flattened),
  registered on the first tick as `vanilla:<mojang id>` (always unlocked, category Vanilla with
  building/equipment/redstone/misc filter). Re-dye, firework, book, stew and other component recipes skipped.
  Container remainders (buckets, honey bottles) are handed back.
- **Reference fill-in**: 2,199 reference 3x3 recipes (NEU) with unlock keys (954 from collection tier text,
  565 from craft text incl. `hotm:N`, `slayer:<type>:N`, `skill:<s>:N`, `collection:BOSS_*`, `museum:N`; 24 from
  slayer_req; 653 free; 3 `never:`). On the first tick each is registered ONLY when its result item exists, no
  module registered a CRAFTING recipe for it, the result is not tagged `no_recipe`, and every ingredient
  resolves. **Owners always win.**
- **Supercraft / Quick Craft** from inventory + sacks (dial): plan with tag-aware allocation, all-or-nothing
  take with refund, coin cells charged, output limited to inventory space.
- **Anti-dupe**: every craft re-reads the live grid/inventory on the handler tick, verifies, removes, THEN gives
  via `Sky.loot().give(..., CRAFT)`; unique results are created one by one (own uuid); the SkyBlock Menu star
  is never counted or taken; per-player craft rate limit (dial).
- **Upgrade carry-over** (deeper than the reference): crafting a unique item from a unique ingredient of the
  same category (armor tier ups, drills, minion tiers) keeps enchants, reforge, potato books, recomb, stars,
  gems and module `extra` data (dial); Supercraft takes the most-upgraded copy for it.
- **Carpentry XP** = 3% of the ingredients' NPC sell value per craft (research/02 §2.4.8), vanilla recipes give
  none (dial), reversible recipes give none (anti-loop). Quick Crafting unlocks at Carpentry III (research;
  paid-rank gate dropped; dial; unlocked when no skills module is installed).
- **Recipe Book** (`/recipes`, `/recipe <item>` with tab completion, name or id): 12 categories with unlocked
  counts and bars, locked-recipe hiding toggle, search (sign/chat input), Craftable Now, Recently Crafted,
  Favorites (right-click to pin), Trades. **Recipe view**: 3x3 preview (10-12/19-21/28-30, arrow 23, result 25,
  Supercraft 32 — research/08 §11.5), ingredient icons with have/need and click-through to their own recipe or
  "Used In", unlock state (14), value readout with NPC and Bazaar profit + Carpentry XP (16), Raw Materials
  breakdown (34), Used In (43), several recipes per item paged at 45/53, right-click result to pin, click to craft.
- **Trades** (`/trades`, Recipe Book): the 21 collection-unlocked trades of the reference, 640/day per trade
  (dial, x10 under Shopping Spree), costs TUNE and each priced above the result's NPC value (self-test enforces
  "no coin printing"); traded items use source NPC_SHOP so they never count toward collections.
- **Furniture** (`/furniture` = the Carpentry category): 37 pieces, one recipe per Carpentry level 1-25
  (research/02), placed as small rotated block builds where the player may build (BUILD zone + PrivateIsland
  canBuild), stored in the chunk's PDC, removed whole and handed back when any block is broken, immune to
  pistons/explosions/fire/liquids/hoppers, its support block protected, vanilla containers in a build are
  decoration (barrels never fill), chairs/benches/hay bed can be sat on. Wave 5: storage pages, showcases,
  working stations, beds, trough and clock (see Wave 5 above). Items registered only if no module
  registered the id.

## What is partial / not built

- **Partly tested live.** The new-player playtest (docs/PLAYTEST.md) drove the Craft Item grid (sticks, wooden
  pickaxe, 11 planks + 2 sticks returned on close), the Recipe Book, Craftable Now and Supercraft in a real client.
  Still never clicked: sign input for search, furniture (seat offsets, skull textures, off-hand/hopper guards),
  the Crafter guard, How to Get / Station Uses, and the grid's "did you mean" hint. Run `/sb selftest crafting`
  and the RCON smoke test below first.
- Trades costs and furniture recipes/builds are our design (TUNE) — the research has no table for them.
- 3 reference recipes (Small/Medium/Large Mutations Sack) are gated `garden:analyzer:N` (wave 5) and stay locked
  until garden's resolver parses that sub-key.
- 1 trade (Teleport Pad) shows as "Not available": no module registers that item (Enchanted Bone Meal now exists).
- Smelting is DISPLAYED (How to Get / Smelts Into, from the server's furnace recipes) but crafting registers no
  SMELTING recipes of its own: vanilla furnaces do the work (core converts the output on the next inventory click).
  Stonecutter / smithing are not modelled (the reference game has neither).
- How to Get, Station Uses and the forge-aware Raw Materials are compiled and covered by `crafting.sources`, not
  yet clicked in a client. Mob Drop lists MobDef loot tables; wave 5 adds slayer boss loot, dungeon and Kuudra
  reward chests and fishing from those modules' published JSON (not crimson/mining/garden/event loot rolled in code).
- The raw-material tree follows the cheapest packing recipe (wave 5, `CraftMath.treePick`), else the first Forge recipe.
- Furniture is block builds rather than the reference game's armor-stand models (the Armor Stand piece does use a
  real armour stand). Storage/showcase/station/bed/trough/clock behaviour is built (wave 5); Minion Chair is a
  player seat until minions can seat a minion.

## Commands added (player + admin)

Player: `/craft` (`/workbench`, `/wb`, `/craftingtable`), `/recipes` (`/recipebook`, `/rb`), `/recipe <item>`,
`/trades`, `/furniture`. Every one is also reachable by menu: SkyBlock Menu -> Crafting Table / Recipe Book
(menus module) -> Recipe Book -> Trades / Carpentry (furniture) / Craftable Now / Search.

Admin (`/sb crafting ...`, all print synchronously, all player-acting ones take a name):
`info` (counts, boot registration report, reversible recipes, furniture chunks, trade availability + limit) ·
`match <cell x9>` (cells `DIAMOND:32` or `-`, row by row: runs the real matcher headless) ·
`recipes <item>` · `craft <player> <item|recipeId> [times]` · `ingredients <player> <item> [times]` ·
`open|book|trades <player> [item]` · `trade <player> <tradeId> [times]` · `tradereset <player>` ·
`bypass <player> [on|off]` (ignore unlocks until they log out) · `tree <item> [amount]` ·
`sources [item]` · `furniture list | give <player> <id> [n] | place <player> <id>` (place = on the block they look at, no item
taken, same room/build checks) · `check` (unknown ingredients + coin-loop report) · `rebuild`.

RCON smoke test: `sb crafting info`, `sb crafting match - DIAMOND:64 - DIAMOND:64 DIAMOND:64 DIAMOND:64 - DIAMOND:64 -`,
`sb crafting ingredients <p> ENCHANTED_DIAMOND 2`, `sb crafting bypass <p> on`, `sb crafting craft <p> ENCHANTED_DIAMOND 2`,
`sb crafting match DIAMOND DIAMOND DIAMOND DIAMOND DIAMOND DIAMOND DIAMOND DIAMOND DIAMOND` (should report
"Can be crafted back: no Carpentry XP" when materials registers the block recipe), `sb selftest crafting`.

## Dials added

`crafting.use-sacks` true · `crafting.allow-mirror` true · `crafting.intercept-tables` true ·
`crafting.quick-craft-level` 3 · `crafting.quick-craft-slots` 3 · `crafting.carpentry-rate` 0.03 ·
`crafting.vanilla-carpentry-xp` false · `crafting.shift-craft-cap` 576 · `crafting.supercraft-cap` 640 ·
`crafting.fallback-recipes` true (restart) · `crafting.vanilla-recipes` true (restart) ·
`crafting.carry-upgrades` true · `crafting.trade-daily-limit` 640 · `crafting.trade-spree-multiplier` 10 ·
`crafting.trade-spree-perk` `diaz.shopping_spree` · `crafting.recent-size` 21 · `crafting.tree-depth` 6 ·
`crafting.sounds` true · `crafting.craft-rate-ms` 120 · `crafting.furniture` true.

## Self-tests added

`crafting.vanilla-recipes` (≥80% registered; sticks craft from oak AND birch planks at an offset, 3 crafts, 4 each) ·
`crafting.matcher-160-rule` (Enchanted Diamond = 160 Diamonds; a grid of 64s crafts it 2x) ·
`crafting.carpentry-formula` (160 Diamonds at 8 coins = 38.4 XP) ·
`crafting.ingredients-resolve` (every grid recipe's result/ingredients exist; says how many are ours) ·
`crafting.trades-valid` (21 trades, collection unlocks, none prints coins) ·
`crafting.furniture-valid` (37 pieces, items exist, block data parses, recipes registered) ·
`crafting.categories` (every collection unlock maps to a book category; 90 collections) ·
`crafting.menus-build` (book root, every category, a recipe view, Used In, Trades build for a profile-less viewer) ·
`crafting.no-coin-loops` (no always-unlocked recipe sells to NPCs for >2x its inputs — a data check across modules) ·
`crafting.no-xp-loops` (no two XP-paying recipes undo each other) ·
`crafting.grid-menu` (exactly the 9 grid slots are free, the menu does not keep them so core returns them on
close, result + quick slots drawn, grid empty on open, menu refreshes) ·
`crafting.sources` (furnace index has Sand -> Glass; a forged item's page shows its Forge recipe and Raw Materials can
walk through it; a recipe view links How to Get; an unknown item gets "No Known Source") ·
`crafting.unlock-keys` (every requirement prefix a grid recipe uses has a resolver; an unknown prefix fails
closed and would lock those recipes forever) ·
`crafting.furniture-functions` (wave 5: 12 storage, 3 showcases, 5 stations whose build holds the working block,
Skull Chest++ 6 pages, every non-cosmetic piece's lore says what it does) ·
`crafting.furniture-storage` (the `crafting_furniture_store` table exists, the item codec round-trips) ·
`crafting.loot-sources` (Revenant Flesh has a slayer source and its page shows it; dungeon and fishing tables read).

## Requests (things you need from core or another module — the integrator reads this)

1. **collections**: done on their side — `collection:LOG:2:4` style ids and `collection:BOSS_<BOSS>:<tier>` resolve.
2. **menus**: SkyBlock Menu Crafting Table -> `Sky.commands().run(p, "craft")`; Recipe Book -> `run(p, "recipes")`
   (optionally Trades -> `run(p, "trades")`). Their hand-off says this is wired. The member key `crafting`
   still carries `bookUnlocked`, `bookTotal`, `bookUnlockedPct` if they prefer our count.
3. **progression**: "Craft a Workbench" = `SkyEvents.ItemGain` with `source == LootSource.CRAFT` and
   `itemId.equals(Sky.items().vanillaId(Material.CRAFTING_TABLE))` (WORKBENCH if materials registers that alias).
4. **every item owner**: register your recipes as usual; the crafting module only fills results that have NO
   CRAFTING/CARPENTRY recipe on the first tick. To keep an existing item deliberately uncraftable, tag its def
   `no_recipe`.
5. **skills**: Carpentry XP arrives as `addXp(p, CARPENTRY, xp, "crafting:<RESULT_ID>")`; the `skill` resolver
   must answer `skill:carpentry:N` (furniture unlocks) and `skill:enchanting:10` / `skill:fishing:35`.
6. **garden**: wave 5 — the 3 Mutations sacks are now gated `garden:analyzer:N` (Crop Analyzer Milestone N, N = 1/3/5).
   Your `garden` resolver owns the prefix; please make it answer `parts[1].equals("analyzer")` with the
   analyzer level (`Greenhouse.analyzerLevel(d) >= N`) and describe it as "Crop Analyzer Milestone <roman N>".
   Until then the key fails closed (locked) and crafting names it itself.
7. **island (TELEPORT_PAD)**: referenced by the reference Trades (Ender Pearl VII) but no module registers it; the
   trade shows "Not available". (ENCHANTED_BONE_MEAL: done, materials registers it.)
10. **loot owners (slayer, dungeons, combatzones; nice to have)**: How to Get's "Mob Drop" reads `MobDef.drops`
    only. Drops rolled in code (slayer bosses, dungeon chests) are invisible to it; an api query such as
    `LootService.sourcesOf(itemId)` would let it list them.
8. **materials (nice to have)**: the 9 block <-> ingot recipes you register are vanilla recipes in the
   reference game; tagging them `"station":"Vanilla"` changes nothing here (the XP cut already covers them), but
   registering them with ids starting `vanilla:` would put them in the Vanilla book category.
9. **core (nice to have)**: `RecipeService` cannot express "x2 of an id whose `id:2` is ALSO an id" in a cell
   (e.g. oak `LOG` x2 vs birch `LOG:2`). Crafting works around it with exact side tables, but core's
   `using()` index can mis-read such cells; `/sb crafting info` reports how many ("Ambiguous cell texts").
   (`Requirements.known` — done in core wave 5; the reflection is gone.)
11. **minions (wave 5)**: the Minion Chair's reference use is "seats a single minion" (and a seated minion stops
    working and does not count toward the cap, research/02). Crafting places it as furniture with a `minion_seat`
    function; to make it real minions needs to let a placed minion be put on a chair block (crafting can expose
    `isFurniture(block)`-style info through an id/tag if you want it — ask).
12. **slayer / dungeons / kuudra / fishing (FYI, wave 5)**: How to Get reads your loot JSON read-only
    (`slayers.json` `types[].loot`, `loot.json` `floors.*.entries`, kuudra `loot.json` tiers, `fishing.json`
    `fish`/`lavaFish`/`treasure`). If you reshape those files, the page just shows fewer sources; tell the integrator
    so `LootTables` follows. A core `LootService.sourcesOf(itemId)` would replace this.

## Known risks

1. **Reference fill-in vs. module intent**: a module that forgets to register recipes gets the reference ones
   (good), but one that wanted an item uncraftable must tag it `no_recipe`, or players can craft it. 653 of the
   reference recipes have no unlock gate at all (the data has none: in the reference the gate is obtaining the
   materials) — e.g. sea-creature armour becomes craftable as soon as its drops exist.
2. **Live behaviour unverified**: menus, the table interception (incl. the off-hand pass), sacks integration,
   carry-over, remainders and furniture (placement, seats, the support/hopper/liquid guards) have only been
   compiled and unit-tested in their pure parts. Run `/sb selftest crafting` and the RCON smoke test above.
3. **Item-id spelling across modules**: canonicalisation assumes materials registers vanilla-equivalent items
   with `material` equal to the vanilla Material (e.g. LOG -> OAK_LOG). A module that registers a vanilla-looking
   id with a different material is treated as a custom item, and a vanilla recipe whose result id is taken that
   way is skipped (logged in the counts) rather than producing the wrong item.

## Integration round 2 (2026-10-02) - the sell cap
`crafting.no-coin-loops` failed with 55 recipes owned by other modules (Armor of Yog 4,000 -> 38,000, Ember armour,
Jungle Axe, Miner Outfit, Hotspot Bait...). Crafting owns the recipe index, so the rule is now APPLIED here as well as
reported: `SellCap` (pure, unit-tested in `SellCapTest`) runs once after late registration and lowers any result's
npcSell to `crafting.sell-cap-factor` (2.0, 0 = off) x what one craft's inputs sell for, as a fixpoint so a capped
input caps what is made from it; `crafting.sell-cap-gated` (true) also covers recipes behind an unlock. It only ever
lowers. Admin: `/sb crafting sellcap [dry]`.
**Valuation change, shared by the cap and the self-test:** an ingredient cell counts at its cheapest PRICED id, and
a cell with no priced id at all makes the recipe unjudgeable (skipped). npcSell 0 means "cannot be sold", not "free":
the first version counted it as free and zeroed IRON_INGOT (9 unsellable nuggets), planks, enchanted iron and every
armour made of mob drops (143 prices). With the fix 7 prices drop at boot (Hotspot Bait 5,000 -> 1,713, Jungle Axe
480 -> 386, Miner Outfit, Squid Hat). Live: `/sb selftest` 323 passed, 0 failed on three boots.

