# reforges — hand-off

Package `com.skyclone.mod.reforges` (entry `ReforgesModule`), data `resources/data/reforges/`, generator
`tools/gen/reforges/gen_reforges.py` (re-run it after editing renames or bonus wording; it rewrites every data file
and `docs/names/reforges.md`).

## What works (verified how)

Verified by `bash tools/check.sh reforges` (CHECK OK, 175 files) and 13 JUnit tests
(`plugin/src/test/java/com/skyclone/mod/reforges/ReforgeDataTest.java`, run green with the JUnit launcher against the
shipping JSON). **Not yet opened by a real client** — the menus, NPCs and bonuses have only been compiled and unit-tested.

- **All 135 reforges as data** (research 01 §13): the 50 Blacksmith reforges and all 85 reforge stones from NEU
  `reforges.json` / `reforgestones.json`, each registered with core as a `ReforgeDef` (name prefix, blue `(+X)` stat
  lines, player stats). Stats per rarity incl. DIVINE rows; weapon-damage reforges (Suspicious +15, Warped +165,
  Gilded, Beady) add real weapon damage through our `ItemStatContributor`.
- **Blacksmith "Reforge Item"** (`/reforge`, hub NPC `npc.blacksmith` "Smith Ottmar"): free slot, random reforge from the
  item's category pool (never re-rolls the one it has), cost by rarity 250 … 50,000 (research table), a player's first
  reforge costs 10 Coal (genre tutorial, dial), possible-reforge list with stats at the item's rarity, green/red frame.
- **Reforge Anvil** (`/reforgeanvil`, hub NPC `npc.reforge_anvil`, and any real anvil block within 4 blocks of that
  anchor): item + modifier → live preview of the resulting item + cost. Handles **every reforge stone** (category /
  item-specific / rarity gates, per-rarity coin cost, Mining-level requirement from the stone), **Roasted/Smoking Potato
  Tomes** (10 then 5 more, research 01 §14b — the stats themselves are core's SheetMath), **Rarity Reshaper 3000**
  (recombobulator, once, not pets), **The Art of War** (+5 Str, weapons) / **The Art of Peace** (+40 HP, armor),
  **master stars 1-5** (dungeon items at 5 stars, in order), **accessory enrichments** (11 kinds, Legendary+ accessories).
- **Essence & Stars** (`/essence`): a per-player essence pouch for the 9 essence types; any `ESSENCE_*` item a player
  gains through `Sky.loot().give` is absorbed into it (ItemGain cancelled at LOW), right-clicking an essence item stores
  it. Star upgrades for all **545 items with reference `upgrade_costs`** (5-star dungeon ladders, 10-star crimson
  ladders, 15-star ladders, item costs like Heavy Pearls), star ladder view. Stars give **+2% of base stats per star**
  outside dungeons (research 01 §16.1) incl. weapon damage; master stars give nothing outside master mode.
- **Gemstone Grinder** (`/gemstones`): reference slot layouts for **499 items** (slot types incl. COMBAT/DEFENSIVE/
  OFFENSIVE/MINING/UNIVERSAL/CHISEL), locked slots with their coin+gem unlock costs, set a gem from the inventory
  (only gems the slot accepts, best first), remove for the quality fee (gem returned). Gem stats per quality ×
  effective rarity (NEU `gemstones.json`, chisel slots at the chisel %) via the stat contributor, so lore `[+x]` and
  player stats agree. At boot, items whose def declares no gem slots get their reference ones (dial).
- **Reforge Guide** (`/reforgeguide`): paged, filterable list of all 135 reforges with what they fit, stats at every
  rarity, stone + apply cost, and the bonus text. **Stone Merchant** (`/reforgeshop`, dial): every stone for coins,
  price = bazaar snapshot buy price floored by stone rarity (fallback so stones are obtainable while drops are thin).
- **Hub menu** `/reforges` links all of the above.
- **Reforge bonuses implemented as behaviour** (numbers read from the table at the item's effective rarity):
  Withered (+Str per Catacombs level), Ancient (+CD per Catacombs level per piece), Perfect (+2% Defense), Renowned
  (+1% combat stats & Magic Find per piece), Empowered (+Mending in dungeons), Fruitful (+Defense on mining islands),
  Auspicious (+% Mining Fortune), Hyper (+Speed for 5s after a kill), Ridiculous (sneak: −CC 20s, +Def 5s, +50 mana,
  needs 20% CC, cooldown dial), Fabled (crits up to +15%), Fanged (every 7th melee hit +100%), Headstrong/Precise
  (arrow headshots +8/+10%), Undead (−2%/piece from zombies, skeletons, withers, zombified piglins), Cubic (−2%/piece
  from Nether mobs), Calcified (+25 Defense/piece vs sea creatures), Gilded (kill → chance to shower a nearby player
  with coins), Blessed / Refined (chance for the enchanted form of a harvested crop / mined ore), Bountiful (+0.2
  coins per crop, batched every 5 s), Magnetic (+% Mining XP). Every bonus line is rendered per effective rarity by our
  lore contributor ("Fabled Bonus").
- **Items registered (118 since wave 3)**: 85 reforge stones (+ the Boo Stone), Roasted/Smoking Potato Tome, Rarity Reshaper 3000,
  The Art of War/Peace, five master stars, 11 enrichments, 9 essences. Every lore line is our own wording.
- **Recipes**: 27 generated (crafting + forge) for owned items, e.g. Roasted Potato Tome (Potato collection VIII,
  matches research), Blaze Wax, Searing Stone, Moonstone, forge stones (Diamondine, Pure Mithril, Pocket Iceberg...).
  Registered one tick after boot and ONLY when every ingredient exists (the rest are logged as skipped).

## Wave 3 (2026-10-04)

Verified by `bash tools/check.sh reforges` (CHECK OK, 185 files) and **16/16 JUnit tests** (3 new: `wave3Upgrades`,
`wave3BonusesAreData`, `allGemIds`), run with the JUnit launcher against the regenerated JSON. New live self-test
`reforges.wave3-upgrades`. Still **not driven by a real client** in this pass (no server/client allowed).

- **Sacks count everywhere a cost is paid.** `Gear.count/take` = inventory + `Features.Sacks`, inventory first, all or
  nothing (rolls back if a sack comes up short). Used by: star upgrade item costs (Heavy Pearls, Kuudra Teeth...), gem
  slot unlock costs (gems, Gemstone Chamber), the first-reforge Coal, and the **Gemstone Grinder**, which now lists
  gems from the Gemstone Sack too ("You have: 12 (9 in sacks)") and takes from it.
- **Eight more reforge bonuses are behaviour now** (data flag `bonusImpl` + `Bonuses.IMPLEMENTED`, a test keeps the
  two in step):
  - **Mithraic** (+30 MF on mithril/titanium) and **Scraped** (+25 MF when the block's breaking power equals the
    tool's) pay the extra drops that fortune would have paid: `amount x bonus / (100 + your MF)`, rolled, given as
    MINING loot on `SkyEvents.Gather`.
  - **Dimensional** (armor, per piece): mining Titanium adds Mithril Powder via `Features.Mining.addPowder`.
  - **Royal** (equipment): +15% of the block's base Mithril Powder while a Mining Jubilee is on (calendar
    `mining_fiesta` or the mayor perk of the same id; bonus text reworded to say so).
  - **Stellar**: near a Fallen Star (the mining module's crying-obsidian heart; scanned 12 blocks round, at most every
    5 s, only for players holding a Stellar tool in the Dwarven Mines; mining STARFALL proves it for 30 s): +55
    Mining Fortune on the sheet and +14% Mithril Powder.
  - **Lustrous**: +25 Mining Spread on the sheet during a Mining Jubilee.
  - **Fleet**: +100 Mining Spread while the block you are looking at is a plain block (STANDARD / HARD_STONE in the
    mining table for your island). The sheet is recomputed every 10 ticks, which is what makes this work.
  - **Candied** (armor, per piece): during the Spooky Festival (`spooky_festival` / `spooky_festival_extra`) a
    non-spooky kill rolls the EXTRA candy chance the reforge adds (10% base x pieces' %), Purple 1 in 10.
  - Block facts (breaking power, mithril powder, kind per island/material) come from `data/mining/blocks.json`,
    read by id in `MiningBlocks`; if the file is missing those bonuses do nothing and the self-test says so.
- **New anvil upgrades** (Reforge Anvil, numbers from the reference item lore):
  **Polarvoid Book** (drills, +10 Mining Speed each, max 5, +5 Mining Fortune once one is in; registered here,
  RARE; NEU recipe generated, held back until METAL_HEART/FROSTY_CRUX exist; rift economy lists it),
  **Book of Stats** (weapons count kills, farming tools count crops broken, lore `Kills: 1,234`; EPIC COSMETIC,
  tag bazaar; counts are batched and written to the stack every 2 s / on quit, matched by item uuid),
  **Wood Singularity** (axe, +25 Foraging Fortune; the item is registered by materials). Extra keys use the
  reference names: `polarvoid`, `stats_book`, `wood_singularity_count`.
- **`REVERSE_REFORGE_STONE_CORE`** registered (fishing request 8; name "eroC enotS derorriM", rename logged) so the
  gorF drop and the Lotus orb recipe can come back.
- **Coldfused** stat half: with any Wisp pet out (pet type ending `WISP`, NEU: "Only if Wisp is equipped"), +75
  Strength / +55 Crit Damage on the sheet; the fire-pillar half is the blaze slayer's, so it stays marked lore. Text
  fixed from "fire spirit pet" to "Wisp pet".
- **Grinder opens on the first empty unlocked slot** (and moves on after a gem is set), so the fitting gems are listed
  without an extra click.
- Admin `/sb reforges upgrade <p> <ITEM_ID>` runs the anvil's rules for any upgrade item on the held item (free), and
  `clear` also strips the new keys.
- `/essence` has doors to the Catacombs' **Essence Shop** and **Salvage** (`/dungeons essence|salvage`), since both
  now exist there and spend/fill the pouch through `Features.Essence`.
- Generator re-run; diff = exactly the 8 `bonusImpl` flags, Royal's and Coldfused's wording, the 3 new items and 1 recipe (Polarvoid).

## Wave 3 integrate fix (2026-10-04)

Verified by `bash tools/check.sh reforges` (CHECK OK, 185 files) and **17/17 JUnit tests** (new
`amberedAndBlazingAreBehaviour`) run with the JUnit launcher against the regenerated JSON. No server or client run.

- **Ambered** (pickaxe, held: treasure goblins +10..50% more often) and **Blazing** (equipment, per worn piece:
  +0.05% more worms per 1 Heat) are behaviour now (`bonusImpl` true, in `Bonuses.IMPLEMENTED`). Mining's own roll
  in `Finds.onBlock` is private, so `Bonuses.rareFinds` rolls only the EXTRA the bonus adds
  (`base x pct / 100`, which to first order is the same as multiplying mining's chance by `1 + pct/100`) on
  `SkyEvents.Gather`, under mining's own conditions: goblins on any Dwarven Mines block, worms on Hard Stone or
  gemstone in the crystal caverns (kind read from mining's `blocks.json` via `MiningBlocks.kind` while the block is
  still there - Gather fires before the break). The base chances and the diamond/Tunnel Wyrm shares are **mining's
  own dials, looked up by key** (`mining.goblin-chance`, `mining.diamond-goblin-ratio`, `mining.worm-chance`,
  `mining.scatha-ratio`), so tuning mining tunes these. Spawns are mining's own mob ids (`mining_golden_goblin`,
  `mining_diamond_goblin`, `mining_worm`, `mining_scatha`) owned by the player, so mining's kill rewards, bestiary
  and commissions apply unchanged. Like mining, a Mining Spread follow-up never rolls a find: only the block the
  player is looking at counts. Heat from `Features.Mining.heat`.
  - Not seen by the extra roll: mining's `rareMult` (Cave Luck, Tunnel Vision) and Sky Mall's x10 goblins - so the
    bonus is "+X% of the BASE chance". Request 12 says how mining can fold it in exactly.
  - Blazing's reference text ("+0.05% chance to dig up worms" per Heat) is read as RELATIVE to mining's worm chance;
    read as absolute it would be +5 percentage points per block at 100 Heat (17x the 0.3% base) on each of four
    equipment pieces. Text reworded to say so ("makes worms +0.05% more likely"). `// TUNE` if the owner wants more.
- **Fossil Essence**: the pouch/registration side is complete; the source is one line in mining's excavator table
  (request 15). Not added here because that file is mining's. `reforges.own-costs-resolve` now WARNs while it is
  missing (`MiningBlocks.excavatorGives` reads `data/mining/loot.json` by id).
- Generator re-run; data diff = Ambered/Blazing `bonusImpl` and Blazing's wording (reforges.json + the Blazing
  Sphere's lore in items.json). Nothing else changed.

## Wave 5 (2026-10-06)

Verified by `bash tools/check.sh reforges` (CHECK OK, 187 files) and **23/23 JUnit tests** (6 new: `wave5TeleportUpgrades`,
`wave5FarmingAndVacuumUpgrades`, `wave5Silex`, `wave5PowerScrolls`, `wave5Bonuses`, `wave5StatsAndOwners`), run with the
JUnit launcher against the regenerated JSON. 3 new live self-tests (below). **No server or client was started** (lane rule).
Generator re-run; data diff = exactly what is listed here (every other file byte-identical).

### Done
- **Bug fixed - Mana Disintegrators were paid twice.** Core reads `mana_disintegrator_count` itself since core-wave5
  (sec 6), and the wave-3 integrator also left a cost modifier in `ReforgesModule`, so 10 disintegrators cut 19%, not
  10%. The modifier is gone; core's read is the only one.
- **Ambered / Blazing double-pay guard (mining request, wave 4).** `Bonuses.rareFinds` now runs only while mining's
  dial `mining.reforges-extra-roll` is true (read by key, same default). True: mining leaves our share out and we
  roll it; false: mining pays the whole bonus and we roll nothing. Both sides read one dial, so the bonus is paid
  exactly once at either setting, in any order - the "delete first, then flip" ordering hazard is gone. Flipping it to
  false is now just better (mining's fold-in also sees Cave Luck / Sky Mall / Tunnel Vision); see request 17.
- **New Reforge Anvil upgrades** (`ItemMods.plan`, reference keys, so the reading module needs nothing):
  - **Transmission Tuner** on the two teleport blades, +1 each, max 4 -> `tuned_transmission` (weapons request 10).
  - **Phasewarp Merger** (`ETHERWARP_MERGER`) on the void blade, once -> `ethermerge = 1` (weapons request 10).
  - **Farmer's Almanac** (`FARMING_FOR_DUMMIES`) on farming's tools (def `extra.farmTool`) or a vacuum, +1 Farming
    Fortune each, cap = farming's dial `farming.almanac-max` (read by key) -> farming's own key `farm_almanac`, so
    farming's contributor and lore pick it up on its tools; the vacuum half (which farming's contributor skips) is
    added by ours, never both.
  - **Bookworm's Favorite Book** on a vacuum, +10 damage each, max 5 -> `bookworm_books`; shows in core's sheet as
    bracketed damage. Garden's vacuum does not read the sheet yet (request 18).
  - **Silex** (`SIL_EX`, registered here, EPIC, Hard Stone VII recipe from NEU): pickaxe or drill at Efficiency
    V..IX, +1 Efficiency level up to X (`ItemData.enchants.efficiency`; enchanting's Efficiency def already runs to X).
  - **Six power scrolls** (Ruby/Sapphire/Amber/Amethyst/Jasper/Opal, registered here, EPIC, Gemstone VII recipes from
    NEU) bind to any item with a RIGHT CLICK ability -> `power_ability_scroll = <id>`; a new scroll replaces the old.
    On every RIGHT CLICK ability use (`SkyEvents.AbilityUsed`): Ruby heals 1% of missing Health (5s cooldown), Sapphire
    +5 mana (5s cooldown), Amber +30 Mining Speed / Amethyst +10 Defense / Jasper +10 Strength / Opal +10 True Defense
    for 5s. The numbers live in each scroll's item `extra` (generator), read by `Bonuses.Scroll`. Lore line on the item.
- **Reforge bonuses that are behaviour now** (`bonusImpl` + `Bonuses.IMPLEMENTED`, the test keeps them in step):
  - **Mantid** (armor, per piece): each garden pest slain (`garden_pest_*`, or family/flag `pest`) adds a 10-minute
    stack; each Mantid piece gives +0.25 Bonus Pest Chance per live stack, up to +5 (TUNE: "per piece" is our reading
    of the lore). Garden already reads `Stat.PEST_CHANCE` when it rolls pests, so this needs nothing from garden.
  - **Chomp** (rod): every DISTINCT lava sea creature (`sea_creature` flag + `extra.lava`) slain while holding the rod is
    recorded ON THE ROD (`chomp_slain`, "with this rod"); each one trims 1% (max 20%) off the max Health of every lava
    sea creature that rod hooks (`FishCaught`, then the angler-owned spawn 2 ticks later via `MobService.setMaxHealth`).
  - **Spiritual** (bow): a 10% chance on a kill inside a dungeon to drop a glowing **Spirit Decoy** for
    `reforges.spirit-decoy-seconds` (5, TUNE); sky mobs within `reforges.spirit-decoy-radius` (8, TUNE) drop the
    summoner as a target and walk to the decoy, and cannot re-target the summoner near it. Core only lets sky mobs
    target players, so the decoy draws them by pathing, not by aggro. Non-persistent, removed on disable.
  - **Majestic** (Safari Belt): +50 Speed while a Critter Safari hunt runs, read from requirement key `safari:active`.
    Inert (and a boot WARN from `reforges.wave5-bonuses`) until hunting registers the `safari` prefix (request 16).
- **Hunting Fortune / Hunting Wisdom are real stats now** (core wave 5): Majestic carries both again (was dropped).
- **Stats with no `Stat` are carried, not dropped**: reforge rows have `otherStats` {RARITY: {fear|overbloom|pull: v}}
  (Greater Spook fear 4/5, Thorny and Overpriced overbloom, Sticky pull), `ReforgeData.otherStat(row, rarity, key)`,
  shown on the stone lore and in the Reforge Guide, so the owners read them by id (requests 19-21).
- **Blood-Soaked** is carried by enchanting's `CombatEnchants.lifeStealMult` (request 14 done there): the row is marked
  `bonusBy: enchanting` and `/sb reforges list` shows `bonus:enchanting` instead of `bonus:lore`.
- Admin: `/sb reforges bonuses <p>` (Mantid stacks, scroll buffs and cooldowns, Chomp count, decoys, reforged gear in
  effect); `clear` strips every anvil key (`ItemMods.ANVIL_KEYS`), including the new ones.
- Accessories request 2 checked: none of the 22 power stones is registered here; accessories are not given reforges
  because the reference removed accessory reforges in April 2022 (research 01 line 531) - recomb and enrichment only.

### Self-tests added (wave 5)
`reforges.wave5-anvil` (Silex V->VI on a real pickaxe/drill, 4 tuners then refusal + merger on the real void blade,
Bookworm +10 vacuum damage and almanac +1 FF in core's sheet on a real vacuum) · `reforges.wave5-bonuses` (Mantid
0.25/5 and stacking/expiry, Chomp 1%/20%, Greater Spook fear 5, Majestic Hunting Fortune in core's registered
reforge, Blood-Soaked owner, impl flags; WARNs while nobody answers `safari:active`) · `reforges.power-scrolls` (six
scrolls registered with the reference numbers, Ruby binds to a real RIGHT CLICK weapon and renders its lore line,
refused on a weapon without one). `reforges.stones-exist` also checks Silex and the scrolls.

### Dials added (wave 5)
`reforges.spirit-decoy-seconds` 5 (TUNE) · `reforges.spirit-decoy-radius` 8 (TUNE). Read by key (owned elsewhere):
`mining.reforges-extra-roll`, `farming.almanac-max`.

## What is partial / not built

- **Bonuses that are still lore only**, each because the mechanic lives inside another module with no hook (the
  request that would make each one real is listed): Beady (+100 FF vs pests) and Buzzing (vacuums x2) - garden's
  vacuum deals `rawDamage` from its own table, which no modifier sees (request 18); Squeaky (pests return 1% sooner)
  - garden's private `cooldown(p)` (request 18); Marshy (+1% Forest Whispers) - foraging's `Hotf.addWhispers`
  (request 20); Sticky (+2.5 hunting fortune on water shards) - hunting's `capture(..., toolHf, ...)` (request 16);
  Coldfused fire-pillar half - blaze slayer (request 22); Greater Spook Fear - weapons' `Passives.fear` (request 19);
  Thorny / Overpriced Overbloom - no Overbloom system exists; Earthy (sowdust), Deep Fried (seasonings), Erudite
  (burrow trails) - no such systems exist in this game yet. Moil/Toil/Loving/Suspicious are carried by their stat rows.
- The 24 newest reference stones whose stats the research does not know (Mandraa, Furball, Geometric Oddity...) are
  not included (NEU's `reforgestones.json` has exactly our 85; the item lore gives one rarity only).
- **Jalapeno Book** is not registered: it applies to deployables for "a small but permanent stat boost" and neither the
  research nor NEU gives the number, and no module runs deployable stats yet.
- Anvil upgrades cost no XP levels (research marks the master-star level costs `(?)`); `// TUNE` if wanted.
- Crimson-style 6-10 essence stars render with core's master-star glyphs (core's `stars()`); stats are right.
- Power scrolls fire on RIGHT CLICK abilities only (reference wording); SNEAK RIGHT CLICK abilities do not count.
- Mantid stacks and scroll buffs are in memory: a relog clears them (a 10-minute / 5-second buff).
- Done elsewhere, no longer ours: in-dungeon star scaling (dungeons, `8%/essence star + 5%/master star`), the
  Enrichment Swapper (accessories), Blood-Soaked (enchanting), Mana Disintegrator cost (core), Fossil Essence source
  (mining).

## Commands added (player + admin)

Player (all also reachable from `/reforges` and the two hub NPCs):
`/reforges` (alias `/reforging`) hub · `/reforge` (`/blacksmith`) · `/reforgeanvil` (`/ranvil`) · `/essence` (`/stars`) ·
`/gemstones` (`/gemgrinder`) · `/reforgeguide` · `/reforgeshop`.

Admin `/sb reforges ...` (all take a player name, so they work over RCON; they act on that player's HELD item):
`list [filter]` · `info <p>` (modifiers, pools, ladders, slots, live stat sheet) · `apply <p> <reforgeId>` ·
`random <p>` · `upgrade <p> <ITEM_ID>` (wave 3: the anvil's own rules, free) · `stars <p> <n>` · `potato <p> <n>` · `recomb <p> [0|1]` · `gem <p> <slot> <GEM_ID|none>` ·
`unlock <p> <slot>` · `essence <p> <TYPE> [add|set|take] [n]` · `givestones <p> [filter]` ·
`open <p> <hub|blacksmith|anvil|essence|gems|guide|shop>` · `bonuses <p>` (wave 5: live bonus state) · `clear <p>`.
Wave 5 anvil upgrades go through the same `/reforgeanvil` menu and `/sb reforges upgrade <p> <ITEM_ID>`.

## Dials added

`reforges.blacksmith-cost-mult` 1.0 · `reforges.stone-cost-mult` 1.0 · `reforges.first-reforge-coal` true ·
`reforges.first-reforge-coal-amount` 10 · `reforges.stone-skill-requirements` true · `reforges.stone-merchant` true ·
`reforges.stone-merchant-price-mult` 1.0 · `reforges.gem-removal-fee-mult` 1.0 · `reforges.gem-unlock-coin-mult` 1.0 ·
`reforges.gilded-chance` 0.05 (TUNE) · `reforges.gilded-max-coins` 500 (TUNE) · `reforges.ridiculous-cooldown-seconds` 20 ·
`reforges.anvil-block-radius` 4 · `reforges.fill-missing-gem-slots` true · `reforges.absorb-essence` true ·
`reforges.spirit-decoy-seconds` 5 (TUNE, wave 5) · `reforges.spirit-decoy-radius` 8 (TUNE, wave 5).

## Self-tests added

`reforges.data-complete` (50 + 85 reforges, 12 gem types, ≥500 star ladders, ≥450 gem layouts, every bonus text
filled, every implemented bonus has a row) · `reforges.stones-exist` (every stone, upgrade item, essence, enrichment
and shop id is a registered item) · `reforges.registered-with-core` · `reforges.formula-research-numbers` (Spicy L =
+10 Str +80 CD, blacksmith 250/5,000, Dragon Claw L 1,000,000, Perfect Ruby M = 30, Fine Jasper E = 5, removal
100,000, Hyperion ★5 = 1,500 Wither, Crimson 10 stars, Hyper M +6) · `reforges.live-item-sheet` (puts Spicy + 10
potato tomes + Art of War on a real registered sword and checks core's sheet; then Suspicious' +15 damage) ·
`reforges.hub-anchors` (`npc.blacksmith`, `npc.reforge_anvil`) · `reforges.menus-build` · `reforges.own-costs-resolve`
(star costs use only the 9 essence types we store; foreign missing cost items are logged as a WARN).
`reforges.wave3-upgrades` (Polarvoid on a real drill shows in core's sheet, the Book of Stats line renders, the mining block
table the mining bonuses read is present).

## Requests (things you need from core or another module — the integrator reads this)

1. **hub**: anchors `npc.blacksmith` and `npc.reforge_anvil` (already in CONTRACT §4) — please also place a real
   anvil block within 4 blocks of `npc.reforge_anvil`; right-clicking it opens the Reforge Anvil.
2. **menus**: add a "Reforging" button to the SkyBlock Menu → `Sky.commands().run(p, "reforges")`. There is no
   Features interface for reforges; the command is the door.
3. **mining**: gem items `{ROUGH,FLAWED,FINE,FLAWLESS,PERFECT}_{RUBY,AMETHYST,JADE,AMBER,SAPPHIRE,TOPAZ,JASPER,OPAL,ONYX,
   AQUAMARINE,CITRINE,PERIDOT}_GEM` and `GEMSTONE_CHAMBER` (Divan slot unlocks). Wave 3: Mithraic, Scraped, Dimensional,
   Royal, Stellar, Lustrous and Fleet are now done HERE from `SkyEvents.Gather`, `Features.Mining.addPowder` and your
   `blocks.json` (read by id) — your request 5 needs nothing more for those. Glacial/Heated/Blazing/Ambered are done too
   (see request 12).
4. **dungeons (wave 2)**: give essence with `Sky.loot().give(p, "ESSENCE_WITHER", n, LootSource.DUNGEON)` — it lands in
   the pouch automatically. You own the in-dungeon star boost (+10%/star, master stars +5% in master mode). Master
   stars and essences are registered here; if you register them too, first registration wins.
5. **accessories**: enrichments are an `ItemStatContributor` (Source.OTHER) on the accessory stack and
   `ItemData.extra.enrichment`; if your bag sums `Sky.items().sheet(stack)` they count, otherwise read the extra key.
   Recombobulated accessories are `ItemData.recombobulated`.
6. **shops / bits shop**: sell `TALISMAN_ENRICHMENT_*` for 5,000 bits (research 01 §18). **bazaar**: the stones,
   Roasted Potato Tome, Rarity Reshaper and essences carry tag `bazaar`.
7. **mob/drop owners** (combatzones, slayer, fishing, crimson, garden, mines...): drop the stones from your tables by
   id (e.g. `DRAGON_CLAW`, `DRAGON_HORN`, `DRAGON_SCALE` from dragons; `RED_NOSE`, `PREMIUM_FLESH`...). Star ladders
   reference crimson items (`HEAVY_PEARL`, `KUUDRA_TEETH`, `MAGMA_FISH_*` ...) — the WARN line at boot lists any no
   module registered.
8. **weapons / armor / farming**: set `ItemDef.dungeon` on dungeon gear (master stars key off it) and tag dicers /
   choppers `farming_tool` if they are not category HOE (they then get farming-tool reforges).
9. **enchanting**: potato tomes can be applied in your anvil AND in the Reforge Anvil — both just bump
   `ItemData.potatoBooks` with the same 10/15 caps, so there is no conflict.

10. **(wave 3) accessories**: the Accessory Enrichment Swapper is yours to run (you own the bag): set
    `ItemData.extra.enrichment` to the new id on every enriched accessory in the bag; our stat contributor and lore
    pick it up. Ids are the keys of `data/reforges/enrichments.json`.
11. **(wave 3) core items / weapons**: Mana Disintegrator (`-1% mana cost each, max 10`, research 01 §19) and
    Transmission Tuner (`+1 teleport block each, max 4`) need the ability code. If core reads
    `ItemData.extra.mana_disintegrator_count` in `AbilityServiceImpl` and weapons reads `tuned_transmission` in the
    blink handler, I will add both to the Reforge Anvil (two `case` lines in `ItemMods.plan`).
12. **(wave 3) mining**: DONE. Glacial/Heated read `Features.Mining.cold/heat` (integrator); Ambered and Blazing roll
    their extra on top of mining's own goblin/worm roll (see "Wave 3 integrate fix"). Optional, for exactness: if
    mining ever wants the bonus INSIDE its roll (so Cave Luck / Sky Mall x10 / Tunnel Vision multiply it too), sum
    the held pickaxe's Ambered % and the worn pieces' Blazing % x Heat in `Finds.onBlock`, then delete
    `Bonuses.rareFinds` here - never both, or the bonus pays twice.
13. **(wave 3) fishing**: `REVERSE_REFORGE_STONE_CORE` is registered now — put the gorF drop and the Lotus orb
    recipe back.

14. **(wave 3) enchanting**: Blood-Soaked (cloak, `ItemData.reforge == "blood_soaked"` on a worn equipment piece) makes
    life-stealing heal `bonus.<RARITY>.values[0]` times as much (1.15). Your `CombatEnchants` heals Life Steal /
    Syphon straight through `Sky.vitals().heal` with no event, so the multiply has to happen there.

15. **(wave 3 integrate) mining - Fossil Essence has no source.** `ESSENCE_FOSSIL` is registered here (10th pouch
    type) and the Fossil Researcher's lines trade in it, but the reference gives it from the **Fossil Excavator's
    treasure rolls** (research 07 §2.14: "Fossil Essence (10-40 each)"), and that table is mining's
    (`data/mining/loot.json` -> `excavator.table`). One data line fixes it at the root, no code on either side:
    `{"item": "ESSENCE_FOSSIL", "min": 10, "max": 40, "weight": 12}` (weight TUNE; the table totals 94 today).
    `MiningLoot.give` hands it to `Sky.loot().give`, our ItemGain listener (LOW) pours it into the pouch, and the
    dig summary line still prints. Until it lands, `reforges.own-costs-resolve` logs a WARN naming the gap.
    (The reference's other sources - the Fossil Muncher daily task, Peridot chisel gems' "+% Fossil Essence" - are
    mining's too.)

Wave 5 status of the old requests: 10 (Enrichment Swapper) done by accessories · 11 done (core reads the
disintegrator; tuner/merger now in our anvil) · 12/15 done by mining · 13 done by fishing · 14 done by enchanting.

16. **(wave 5) hunting** - two small reads, both by reforge id (no import):
    a. Register requirement prefix **`safari`** with `safari:active` = true while the player's Critter Safari hunt runs
       (`HuntingData.safariTicket != null && safariEndsAt > now`). Majestic (+50 Speed on the Safari Belt) is already
       coded here against that key and switches on by itself; `reforges.wave5-bonuses` WARNs until then.
    b. **Sticky** (fishing net stone): in `Hunt.capture(...)` when `how` is the net and the shard is a WATER shard, add
       `bonus.<RARITY>.values[0]` (2.5) of `data/reforges/reforges.json` row `sticky` to `toolHf` when the net's
       `ItemData.reforge == "sticky"` (rarity = `Sky.items().rarity(net)`); and its **Pull** is
       `otherStats.<RARITY>.pull` (5/7.5/10/15/20) of the same row - add it to the net's Pull in `Hunt.pull`.
17. **(wave 5) mining**: flip `mining.reforges-extra-roll` default to **false** (or `/sb dial set ... false`) whenever
    you like - safe in any order now (see Wave 5). Then mining pays the whole Ambered/Blazing bonus inside its roll.
18. **(wave 5) garden** - vacuums and pests (all read `ItemData` of the held vacuum / worn equipment):
    a. `Pests.vacuum`: `dps += Sky.items().sheet(hand).damageBonus()` (Beady's +25 and Bookworm's +10 each show there)
       and `dps *= 2` when `ItemData.reforge == "buzzing"`.
    b. **Beady**: +100 Farming Fortune against pests (`bonus.<R>.values[0]`) where pest drops are rolled with fortune.
    c. **Squeaky** (equipment): in `Pests.cooldown(p)`, `reduction += values[0] / 100` per worn Squeaky piece
       (`Features.Equipment.worn`, `ItemData.reforge == "squeaky"`).
19. **(wave 5) weapons**: **Greater Spook** - `Passives.fear(p)` should add `otherStats.<RARITY>.fear` (EPIC 4,
    LEGENDARY 5) of row `greater_spook` for each worn piece whose `ItemData.reforge == "greater_spook"`.
20. **(wave 5) foraging**: **Marshy** - in `Hotf.addWhispers`, multiply Forest Whispers by `1 + sum(values[0]) / 100`
    over worn equipment reforged `marshy` (1% each).
21. **(wave 5) garden / farming**: Overbloom (Thorny, Overpriced) is in `otherStats.<R>.overbloom` if an Overbloom
    mechanic is ever built.
22. **(wave 5) slayer (blaze)**: **Coldfused** fire-pillar half - while the held weapon is reforged `coldfused` and a
    Wisp pet is out (`Features.Pets.activeType` ends with `WISP`), fire pillars break twice as fast
    (`bonus.<R>.values[2]` = 2). The stat half is already ours.

## Known risks

-1. (wave 5) **Spirit decoys are vanilla armor stands** in the dungeon world (tagged `skyclone:reforges_decoy`,
   non-persistent, removed after 5 s and on disable). If core or dungeons sweep untagged entities in `sb_catacombs`, a
   decoy may vanish early - harmless. The decoy steers sky mobs by `Mob.getPathfinder().moveTo` and by cancelling
   `EntityTargetLivingEntityEvent` toward the summoner near it (HIGHEST); it never makes a mob target a non-player.
0. (wave 5) Chomp trims health 2 ticks after `FishCaught`; a fishing module that spawns the creature later than that
   would skip the trim (it only looks at angler-owned creatures under 3 s old).
0. (wave 3) Stellar scans up to 25x25x13 blocks once per 5 s per player holding a Stellar tool in the Dwarven Mines
   (loaded chunks only). Cheap, but it is a scan; `STAR_RADIUS` in `Bonuses` if it ever shows in a profile.

1. **Never driven by a real client.** The menus mutate the item sitting in a free slot (`Sky.items().update` on the
   stack from `menu.get(slot)`, then written back with `setItem`). If core's free-slot handling differs from what the
   guide describes, the first live test of `/reforge` will show it; `/sb reforges open <p> ...` + `/sb reforges info <p>`
   make that quick to check.
2. **Boot-time gem-slot fill mutates other modules' `ItemDef.gemSlots`** when they are empty (dial
   `reforges.fill-missing-gem-slots`). It never overwrites a non-empty list.
3. **Stone Merchant prices come from one bazaar snapshot** (some stones are cheap there, some are 69M). It is a
   fallback so the system is playable; turn it off with `reforges.stone-merchant false` once drops exist.
4. Item-id overlap: stones that are also mob drops (Dragon Claw/Horn/Scale...) may be registered by their dropping
   module too; core keeps the first registration.
5. Blacksmith pools assume sword reforges also fit fishing rods and wands (`SWORD/ROD` group); change
   `ReforgeData.typeMatches` if that is wrong for this game.


## Fix pass (2026-10-04, wave4-endgame playtest)
Reforge stone lore said "Combine with a armor/equipment piece": `gen_reforges.py` picks a/an by the phrase's first letter, and `data/reforges/items.json` was patched to match (38 lines).

## 2026-10-06: Leviathan-line star coins (real-game audit A1, done by the crimson lane)
`gen_reforges.py` now merges the COIN part of each essence star from NEU `constants/essencecosts.json` into
`stars.json` for the 100 Leviathan-line (Kuudra) armour pieces only: stars 4-10 = 10k/25k/50k/100k/250k/500k/1M,
Infernal 11-15 = 2.5M/5M/10M/25M/50M (the Hypixel API's `upgrade_costs` leave coins out). Regenerated: only
`stars.json` changed, exactly those 100 entries. `Gear.pay` already charges `{"coins": n}` cells. NEU lists coin
parts on ~530 more items of every essence type (dungeon, dragon, spider, ice...) - not applied; that is this module's call.

## Cross-module follow-ups (2026-10-06, integrator list in docs/FIX_BRIEF_2026-10-06.md)
- **Squeaky** bonus text/value 1% -> **2.5%** per piece at every rarity (`BONUS_VALUES` in gen_reforges.py; the garden applies
  `garden.squeaky-pct` itself).
- **Star coin costs on every starrable item**: the Kuudra-only filter is gone; NEU `essencecosts.json` coin parts are merged
  into all 527 items NEU lists (dungeon gear 10k/25k on stars 4-5, 10-star ladders 10k..1M, 15-star 2.5M..50M). A star that
  already carries a coin cell is skipped. Regenerated: `stars.json` (427 more entries), `reforges.json` + `items.json` (Squeaky).
- Test: `ReforgeDataTest.starCoinsFromNeuOnEveryLadder`.
