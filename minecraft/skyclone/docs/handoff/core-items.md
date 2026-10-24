# core-items — hand-off

Package `com.skyclone.core.items`, boot class `ItemsCore` (`boot(JavaPlugin)`, `afterModules()`, `shutdown()`).
Provides: `ItemService`, `RecipeService`, `StatService`, `VitalsService`, `AbilityService`.

## What works (verified how)

- `bash tools/check.sh core` -> **CHECK OK** (api + all of core compile together).
- **20 JUnit tests pass** (`plugin/src/test/java/com/skyclone/core/items/ItemsPureTest.java`, run with plain javac + the
  JUnit launcher from the gradle cache): ItemData codec round trip + determinism + leniency, potato-book math, reforge row
  by recombobulated rarity, a throwing stat contributor being skipped, exact lore text (wooden sword, plain material,
  recomb footer, stars + master stars, coloured stat parts, ability block, fractional mana, enchant block detailed and
  compact, gem row, contributor sections), recipe cell parsing (ids with ':'), lenient item/recipe JSON, vitals formulas,
  action-bar text, SNEAK_RIGHT_CLICK -> RIGHT_CLICK fallback.
- NOT verified live: the server has not been started (gate agent owns it). The 7 self-tests below are what to run.

### Items
- Registry: keep FIRST on duplicate ids and log `(from <module>)`. **One exception:** the auto vanilla defs. At boot
  every `Material` with `isItem()` gets a plain COMMON def (id = material name, `Text.titleCase` name, category
  guessed for swords/bows/tools/armour, max stack = vanilla max). A module def with the same id **replaces** it.
- `loadJson(module, file)` reads `data/<module>/<file>`, one entry at a time: a bad entry is logged (`id + reason`) and
  skipped, never thrown. Lenient: `Stat.parse` keys (unknown keys skipped, ONE warning per key for the whole server),
  `"stats":{"DAMAGE":35}` becomes `ItemDef.damage` when there is no explicit `"damage"`, lenient rarity / category
  (`FARMING_TOOL`->HOE, `SPADE`->SHOVEL...) / trigger (`"sneak right click"`). Missing name -> titleCase(id); null
  lists/sets/maps -> empty; ability without name -> titleCase(id); `maxStack` outside 0..99 -> 0.
- Bad `material` (unknown / block-only like `CARROTS` / legacy) -> logged, rendered as BARRIER.
- `create(id, n)`: n clamped to 1..effectiveMaxStack. Unique items get `uuid` + `created`. Stackables carry ONLY the
  `skyclone:id` tag (no data, no timestamp) so they stack with each other and with converted pickups.
  Stackable renders are cached per id (template) and invalidated on any registration, rename, contributor add, and
  once more in `afterModules()`.
- Stack meta: name + lore, PDC `skyclone:id` / `skyclone:data` (compact JSON, defaults omitted, sets sorted),
  glint for `def.glow`, tag `glow`, ids starting `ENCHANTED_`, or any enchant in data; every ItemFlag hidden;
  **attribute modifiers set to EMPTY** (no vanilla armour/attack numbers anywhere); unbreakable for unique damageables;
  `MAX_STACK_SIZE` component = `effectiveMaxStack()` (so a unique can never stack). A *stackable* def on a damageable
  material (e.g. a 64-stack item using `SHEARS` as its look) has DAMAGE / MAX_DAMAGE stripped first — vanilla forbids
  damageable + stackable. Leather / potion colour from `def.color` ("#RRGGBB" or "r,g,b"), skull texture via
  `Icons.applyTexture`.
- **Other modules' own PDC keys survive `update()`/`refresh()`**; everything else on the stack is rebuilt from
  (def, ItemData). So: keep instance state in `ItemData` (use `extra` for your own fields).
- `vanillaId(Material)`: (1) a module def tagged **`vanilla`** whose material is that Material, else (2) the first
  id in `data/core-items/vanilla_aliases.json` for that material that a MODULE registered, else (3) the material name.
  The alias file (288 materials) is generated from the NEU repo by `tools/gen/core-items/vanilla_aliases.py`:
  `OAK_LOG -> LOG`, `COCOA_BEANS -> INK_SACK:3`, `WHEAT_SEEDS -> SEEDS`, `CARROT -> CARROT_ITEM`,
  `WOODEN_SWORD -> WOOD_SWORD`, `GUNPOWDER -> SULPHUR`, `RED_SAND -> SAND:1` ... So when **materials** registers
  `LOG` with material `OAK_LOG`, every oak log a player picks up becomes `LOG` with no extra work.
- Conversion vanilla -> sky: on `ItemSpawnEvent` (block/mob drops become sky items before anyone touches them),
  `EntityPickupItemEvent` (LOWEST), and a coalesced next-tick sweep of the player's own inventory after
  click / drag / close / pickup / join. Never touches a Menu inventory. Join also re-renders every sky stack in
  inventory + ender chest (renames, new lore). NOT converted (their meta is content): shulker boxes, bundles,
  maps, books, potions, tipped arrows, enchanted books, fireworks, banners, player heads, spawn eggs, compasses,
  suspicious stew, goat horns; nor a MATERIAL stack with a custom name/lore/enchant/PDC. Damaged/enchanted vanilla
  tools and armour ARE converted (they come out repaired and with vanilla enchants dropped).
- `count` / `remove`: main inventory (36 storage slots), sky id OR unconverted vanilla of that id's material.
- `sheet()`: BASE (def stats + damage), REFORGE (`ReforgeDef.statsFor(effective rarity)`), POTATO (weapons: sword,
  longsword, bow, fishing weapon, gauntlet, dungeon weapon: +2 Damage (bonus) +2 Strength per book; armour: +4 Health
  +2 Defense per book; max 15), then every `ItemStatContributor` in registration order (a throwing one is skipped and
  logged at most every 5 minutes). Contributors receive a COPY of the data.
- `rename()` persists in `items_renames` (loaded at boot, written async), re-renders that id for everyone online.
- `fullSet` = all four armour pieces share a non-empty setId; `setPieces` counts.
- Anti-dupe (`DupeScanner`): every 30 s (dial) for all online players together + each join. Same uuid twice within
  ONE player's inventory/ender chest -> keep the older (`created`), delete the other. Same uuid across two players
  -> logged + admins pinged, deleted only if `items.dupe-delete-cross-player`. Every hit goes to `items_dupe_log`
  with both stacks' full data JSON, and to the console at SEVERE.
- Vanilla guards (`VanillaGuardListener`, all HIGHEST + ignoreCancelled, dial `items.vanilla-guards`). "Custom" =
  sky id different from `vanillaId(material)`. Blocks: placing a custom item (unless tag `placeable`), vanilla
  crafting with a custom item in the grid, anvil / smithing / grindstone on ANY sky item, the vanilla enchanting table
  on any sky item, furnace smelting or burning a custom item, eating a custom item (unless tag `consumable` or
  category CONSUMABLE/POTION), throwing a custom item (unless tag `throwable`), using a custom bone meal / bucket /
  egg / flint&steel / dye ... on a block (unless `placeable` or `usable`).

### Lore — exact format (research 01 §21)
```
<rarity><Reforge >Name[ &6✪✪✪✪✪][&c➊..➎]       (6-10 stars = five gold + ONE red circled digit)
&8Breaking Power 4                                  (when the sheet has BREAKING_POWER; not a stat line)
&7Damage: &c+120 &e(+20)                            (bonus part = potato/stars)
&7Strength: &c+75 &e(+20) &9(+15) &d[+10]           (potato / reforge / gems; percent stats get %)
 &9[&d❁&9] &8[⚔&8] &7[&8❈&7]                      (filled: quality colour + gem symbol; empty locked &8; empty unlocked &7)
AFTER_STATS
·
&d&lChimera I / &9Sharpness V / &7<description>     (<= items.enchant-detail-max (3) enchants: one per line + description)
&d&lChimera I&9, &9Sharpness V&9, &9Critical VI     (more: comma-joined, three per line; ultimates first)
AFTER_ENCHANTS
·
def.lore
·
&6Ability: <name> &e&lRIGHT CLICK / ability lore / &8Mana Cost: &350 / &8Cooldown: &a5s
   (mana in (0,1): "&8Mana Cost: &310% &8of max"; "%d" or "{damage}" in ability lore = ability.damage)
&9<Reforge bonusName> / bonusLore
&6Full Set Bonus: <setBonusName> / setBonusLore
BEFORE_FOOTER
·
&8This item can be reforged!                        (reforgeable and no reforge yet)
&8&l* &8Co-op Soulbound &8&l*                       (tag soulbound)
&4❣ &cRequires <Requirements.describe(key)>&c.      (every key; always shown — lore is not per-viewer)
&6&lLEGENDARY DUNGEON SWORD                         (recomb: &d&l&ka&r &d&lMYTHIC SWORD &d&l&ka)
```
Stat order: Strength, Crit Chance, Crit Damage, Bonus Attack Speed, Ability Damage, Health, Defense, Speed,
Intelligence, Magic Find, Pet Luck, True Defense, Ferocity, Mining Speed, Pristine, Mining/Farming/Foraging Fortune,
Sea Creature Chance, Fishing Speed, Health Regen, Vitality, Mending, Swing Range, then the rest in enum order.
Value colour `&c` for Strength, Crit Chance, Crit Damage, Bonus Attack Speed, Ability Damage, Ferocity; `&a` otherwise.
Blank lines collapse, no leading or trailing blank. "Reforgeable" = tag `reforgeable`, or category weapon / armour /
tool / equipment / accessory, unless tag `no_reforge`. Dungeon items say DUNGEON in the footer (no separate line).

### Recipes
- Registry, `loadJson`, `byType`, `producing`, `using`, `unlocked` (null unlock = true; requirement errors = locked).
- Duplicate recipe ids keep the first. Missing id -> `RESULT#n`.
- **Cell format:** `"ID:amount"`. Ids may contain ':' (`INK_SACK:3` is Cocoa Beans): a cell that IS a registered id
  means x1; otherwise the part after the LAST ':' is the amount. So write `"INK_SACK:3:16"` for 16 cocoa beans.
  `"COINS:1000"` is coins. Indexes rebuild lazily whenever recipes or the item registry change (materials registers
  last, and whether "X:3" is an id depends on it). `RecipeServiceImpl.ingredients(r)` gives the parsed map.

### Stats
- Providers in order: `armor`, `held`, `equipment`, then modules'. Recomputed every 10 ticks for every online
  player, and the tick after `invalidate` (armour change, held slot, swap hands, drop, pickup, any inventory click).
- `armor`: worn pieces whose category `isArmor()` (a talisman worn as a hat gives nothing) and whose requirements the
  wearer meets. `held`: main hand when category is a weapon / tool / rod / wand. `equipment`:
  `Features.Equipment.worn(p)` (null-safe; requirement-checked).
- `breakdown()` rows: `base`, then per provider flat value, plus `"<id> (%)"` / `"<id> (x)"` rows when a provider
  used `addPercent` / `multiply`.
- Side effects: `MOVEMENT_SPEED` base = vanilla default x Speed/100 (Speed capped at 400, dial), `ENTITY_INTERACTION_RANGE`
  = Swing Range (cap 15), `ATTACK_SPEED` base 1024 (no vanilla attack cooldown). Reset to vanilla on shutdown.
- A provider that throws is skipped for that pass and logged at most every 5 minutes.

### Vitals
- Pool: health / mana / absorption in member data `"vitals"` (`VitalsData`), so a relog keeps them. Fresh profile
  starts full. Before `ProfileReady` a transient pool is used.
- `maxHealth = max(1, HEALTH)`. `maxMana = INTELLIGENCE + MANA_POOL_BONUS` (INTELLIGENCE.base is 100, so this is the
  genre's 100 + Int).
- Hearts are a gauge: vanilla health = vanillaMax x hp/max (never below 0.5 while alive); health scale 20 half-hearts
  at < 125 HP growing one heart per step (125/165/230/300/400/500/650/800/1000/1250) to 40 (dial). Absorption shows
  as yellow hearts and turns the action-bar HP gold.
- Regen: health every 2 s (dial) by `(1.5 + max/100) x HealthRegen/100 x mult`, out-of-combat multiplier dial
  (default 1.0; research says the 2026 game doubles it). Mana every 1 s: 2% of max x (1 + ManaRegen/100).
  "In combat" = lost health in the last 5 s (dial).
- No hunger (food pinned at 20), all vanilla regen cancelled.
- Action bar every 5 ticks: `&c1,234/1,500❤     &a612❈ Defense     &b850/950✎ Mana`; `flash()` replaces the middle.
- **Damage convention** (combat core already does exactly this): `addAbsorption(p, -x)` first, then
  `setHealth(p, hp - rest)`, then `kill(p, cause)` at 0. A `setHealth(<= 0)` with no `kill` is caught at the end of
  the tick and kills with cause "died". `kill` is idempotent within a tick.
- Safety net (`vitals.vanilla-damage-net`): vanilla damage that combat did not cancel or zero (e.g. in a non-game
  world) is converted proportionally (vanilla points / vanilla max x custom max), its vanilla amount set to 0 (flash
  and knockback kept). VOID / KILL / WORLD_BORDER kill outright. If a vanilla death still happens: inventory and levels
  kept, nothing drops, auto-respawn at the island spawn, warning logged.
- `kill`: coins lost = half the purse (dial) except in a zone flagged SAFE or on a private island (dial);
  `SkyEvents.SkyDeath` can change `coinsLost`; deducted with reason `death: <cause>`; message
  `&c☠ &7You died &8(<cause>)&7 and lost &6<coins> &7coins!` (or `... &8(<cause>)&7.` when nothing was lost);
  instant respawn: full health and mana, absorption 0, fire/fall/velocity cleared, teleport to the island's `spawn`
  anchor read at call time. Private island -> `Features.PrivateIsland.home(p)`. Other INSTANCED islands (garden,
  dungeon runs) and non-game worlds -> that world's spawn (see risks).

### Abilities
- Triggers on the MAIN-hand sky item: RIGHT_CLICK (air, block, or a living entity — entity clicks respect
  cancellation so NPC clicks never fire abilities), SNEAK_RIGHT_CLICK (falls back to the RIGHT_CLICK ability when the
  item has none), LEFT_CLICK, SNEAK (on sneak press). One trigger per player per tick (the client double-sends).
  Block-protection cancels are ignored (abilities work in the protected hub).
- Order: handler registered? (no -> nothing, logged once) -> `meetsRequirements(tell)` -> cooldown
  (`&cThis ability is on cooldown for 3s.`) -> mana (cost < 1 = fraction of max; `&cYou do not have enough mana!` +
  sound + `&c&lNOT ENOUGH MANA` flash) -> handler -> on `true`: charge mana, start cooldown, flash
  `&b-50 Mana (&6Name&b)`, fire `AbilityUsed`. When an ability matched, the item's vanilla use is denied (no bow
  draw / block place).
- `registerSetTick(setId, minPieces, fn)`: every 20 ticks for each player with >= minPieces (1..4) pieces of the set.

## What is partial / not built
- No per-viewer lore (requirements are always listed, not only when unmet).
- No ability mana-cost modifier hook (Ultimate Wise etc.) — see requests.
- Overflow mana, Vitality resource, Rift hearts: not in vitals.
- Dupe scan covers inventory + ender chest only (not backpacks/storage, which storage owns).

## Commands added (all admin, `/sb ...`)
| Command | What |
|---|---|
| `/sb give <player> <id> [amount]` | give (tab-completes ids, case-insensitive); goes through `Sky.loot().give(ADMIN)` when loot is up |
| `/sb item info` | held item: id, def, rename, rarity, category, tags, abilities (+ missing handler), data JSON, sheet |
| `/sb item rename <id> <name...\|reset>` | persisted display-name override |
| `/sb item refresh [all]` | re-render held item, or every sky item of every online player |
| `/sb items [filter]` | paged browser of every item (module items first); click = 1, shift-click = a stack |
| `/sb stats [player]` | every non-zero stat with per-provider breakdown + health/mana |
| `/sb heal [player]` / `/sb mana [player]` | full heal / refill mana |
| `/sb dupes [count]` | recent `items_dupe_log` rows |

## Dials added
`items.convert-vanilla` true · `items.vanilla-guards` true · `items.dupe-scan-seconds` 30 · `items.dupe-delete` true ·
`items.dupe-delete-cross-player` false · `items.enchant-detail-max` 3 · `stats.recompute-ticks` 10 ·
`stats.speed-cap` 400 · `stats.swing-range-cap` 15 · `stats.attack-speed-attribute` 1024 · `stats.apply-attributes` true ·
`vitals.regen-interval-seconds` 2 · `vitals.regen-multiplier` 1 · `vitals.regen-out-of-combat-multiplier` 1 (TUNE) ·
`vitals.combat-seconds` 5 · `vitals.mana-regen-percent` 2 · `vitals.death-coin-loss` 0.5 ·
`vitals.death-safe-private-island` true · `vitals.disable-hunger` true · `vitals.heart-scale-max` 40 ·
`vitals.action-bar` true · `vitals.vanilla-damage-net` true · `abilities.flash-ticks` 40 · `abilities.enabled` true

## Self-tests added
`items.vanilla-defs` (every item Material resolves to a def) · `items.create-roundtrip` (stackables stack, converted
vanilla stacks with created, no data on fresh stackables, distinct uuids, unique max stack 1, update keeps uuid) ·
`items.potato-math` (+10 books sword 55/20/20; +15 books chestplate 60 HP / 30 Def) · `items.lore-format` (exact name,
recomb footer, damage + strength lines, reforge hint) · `recipes.ingredients-exist` (every result and ingredient id of
every registered recipe exists — a cross-module data check) · `stats.base-and-formulas` · `abilities.handlers-registered`
(every clickable ability on any item has a handler — expected to list gaps until weapon modules finish).

## Behaviours module authors must know (gotchas)
1. **Never `clone()` a unique item to hand out a copy** — same uuid = a dupe, and the scanner deletes one. Use
   `create(id)` for a new one. Menu icons may be clones (menus are never scanned).
2. Keep all per-stack state in `ItemData` (`extra` for module fields). `update(stack, d -> ...)` writes + re-renders in
   place; `data()` is a copy.
3. Tag the canonical vanilla-equivalent def `vanilla` if its id is not in the alias file (or is ambiguous).
4. Tags core understands now: `vanilla`, `placeable`, `consumable`, `throwable`, `usable`, `glow`, `reforgeable`,
   `no_reforge`, `soulbound`.
5. Lore contributors and stat contributors must be pure functions of (def, data, rarity): stackable renders are
   cached, and an impure line breaks stacking.
6. Item lore text is rendered at create/refresh time; after you change something lore depends on (e.g. a mayor),
   call `refresh(stack)` — or `/sb item refresh all`.
7. In ability lore use `%d` or `{damage}` for `AbilityDef.damage`.
8. Requirements are listed on every item that has them (lore is not viewer-specific).
9. `INTELLIGENCE` already includes its base 100. Magic damage formulas that read it should subtract `Stat.INTELLIGENCE.base`
   (combat's concern; see request 3).

## API change requests
1. **`VitalsService.damage(Player, double amount, String cause)`** (absorption -> health -> kill). Same as core-combat's
   request 1; the impl would be ~10 lines in `VitalsServiceImpl`.
2. **`AbilityService.addCostModifier(String id, ToDoubleBiFunction<Use, Double>)`** (or a mutable cost on a pre-use
   event) so Ultimate Wise / mana-cost reductions work.
3. **`Stat.INTELLIGENCE.base` should be 0 with "max mana = 100 + Int" documented on VitalsService** — today base 100
   makes `StatMap.total(INTELLIGENCE)` read 100 for a naked player, which doubles magic damage under the documented
   formula `1 + int/100 * scaling` unless combat subtracts the base. Vitals already handles either value.
4. **`SkyDeath.respawnAt` (nullable Location, mutable)** so dungeons/garden/rift can choose where a death lands
   (instanced islands' spawn anchors are per slot, which core cannot know).
5. **`VitalsService.actionBarSegment(String id, int priority, Function<Player,String>)`** for drill fuel, Rift time,
   dungeon secrets (research 08 §9 appends these to the bar).
6. Document the item tags listed above on `ItemDef.tags` javadoc.

## Known risks
- ~~**Instanced-island respawn**~~: fixed in integration round 3 (core fixer) - see "Death respawn" in
  `docs/handoff/core-world.md`. Deaths on an instanced island now respawn at the spawn of the slot they died in.
- **Vanilla-conversion breadth**: anything core does not know is content-bearing could lose meta when converted; the
  exclusion list is in `ItemServiceImpl.contentBearing`. Admin-renamed vanilla items (custom name) are not converted.
- **`ItemSpawnEvent` coverage of block drops** differs between Paper builds; the pickup + next-tick sweep is the
  backstop, so a vanilla stack can exist on the ground briefly.
- Every sky stack has EMPTY attribute modifiers: vanilla armour points and vanilla tool attack damage are gone by
  design; anything relying on vanilla armour values must use stats.
- Dupe deletion within one player trusts `created`; two copies with equal `created` keep the first scanned slot.

## Fixes after integration round 3 (core fixer, nice-to-have requests)
- **rift Request 5 — per-player stat override.** New `StatMap.set(stat, value)` / `unset` / `override(stat)` /
  `overrideView()` (api, additive). A provider calls `set` for the players it applies to and that stat's FINAL value
  is replaced (base, flat, percent and multipliers ignored); `total()` and `bonus()` honour it, `addAll` carries it,
  and when two providers set the same stat the later-registered provider wins. It lapses on the next recompute once
  the provider stops calling it, so there is no per-player state to clean up. `/sb stats` shows it as
  `<provider> (=) value`. The Rift can now replace main Intelligence / Speed / Magic Find inside a session by calling
  `into.set(...)` instead of `into.add(...)` in `RiftStats.contribute` — that change is rift's to make.
- Self-test `stats.base-and-formulas` and unit test `ItemsPureTest.statOverrideReplacesFinalValue` cover it.

## Fixes after playtest-loops (core fixer)
- **Armour swap no longer cuts health to the naked max.** `onStatsChanged` used to CLAMP health/mana to the new
  maximum after every stat recompute, so taking armour off took 596/596 to 117/117 and putting a set back on left
  117/596 for regen to grind back. Health and mana now carry their **fill fraction** across any change of max
  (`VitalsMath.rescale`): full stays full, off-and-back-on returns exactly the starting value, empty stays empty, so
  it is not a heal. Same for mana (an Intelligence piece or a held wand swapped out no longer drains the pool), the
  Rift's `StatMap.set` HEALTH override (leaving no longer strands you at the Rift max), and relog: `VitalsData` now
  persists `maxHealth` / `maxMana` (the maxima the pool was measured against; `-1` on old saves = clamp once), and
  `attachProfile` rescales from them instead of clamping. Death refill records the new maxima too.
- Unit test `VitalsRescaleTest` (6 cases incl. the reported 596 -> 117 -> 700); the `stats.base-and-formulas`
  self-test now also asserts the swap stays full.


## Fix pass (2026-10-04, wave4-endgame playtest)
`AbilityServiceImpl`: "You do not have enough mana!" goes to chat at most once per 3 s per player; the NOT ENOUGH MANA flash and sound still answer every click (an AI agent spamming an ability filled its chat with it).
