# alchemy - hand-off

Package `com.skyclone.mod.alchemy` (entry `AlchemyModule`), data `resources/data/alchemy/`, generator
`tools/gen/alchemy/gen_alchemy.py` (re-run it after editing the tables; it reads the NEU potion files for every
number), unit tests `src/test/java/com/skyclone/mod/alchemy/AlchemyTableTest.java`.

## What works (verified how)

Verified by `bash tools/check.sh alchemy` (CHECK OK) and the unit tests (7/7 green, run locally with the JUnit
platform launcher against the shipping `effects.json`). **Nothing here has been seen on the live server yet** -
the self-tests below are what the integrator should run first.

| Feature | Detail | Verified |
|---|---|---|
| **Every potion** | 48 effects, 214 effect-levels, all from the NEU item files (rarity per level, base duration, every number): Speed, Strength, Healing, Damage, Jump Boost, Resistance, Water Breathing, Regeneration (I-IX), Mana, Critical, Archery, Haste, Absorption, Adrenaline, Rabbit, Agility, Night Vision, Invisibility, Fire Resistance, Spelunker, Experience, Stun, Dodge, True Resistance, Spirit, Pet Luck, Knockback, Burning, Venomous, Wounded, Cold Resistance, Magic Find, Stamina, Slowness, Weakness, Poison, Blindness, 7 skill XP Boosts, plus Glowmash Tonic, Pungent Cheese Mist, Harvest Herald, Chilled Spirit Water. 425 items: `POTION_<EFFECT>;<lvl>`, `SPLASH_POTION_<EFFECT>;<lvl>` (every brewable level), `POTION_DUNGEON;1..7`, `GOD_POTION_2` (current), `GOD_POTION` (legacy), `POTION_AWKWARD`, and the Water Bottle `POTION` | unit test `researchNumbers`; self-test `alchemy.items-exist`, `alchemy.potion-lore-renders` |
| **Brewing** | Right-click ANY brewing stand block in a game world (hub alchemy shop at `npc.brewing_stand`, one placed on your island) or `/brew` -> "Brewing Stand" menu: ingredient slot 13, three bottle slots 38/40/42, progress bars, live status (what will come out, XP, time left, "Requires Cactus Collection III"). Water + Nether Wart -> Awkward; Awkward + ingredient -> potion at the ingredient's level (I / III / V); Glowstone family +1/+2/+3 levels (once, capped at the brewable max); Redstone family +8/+16/+40 min (once, not for instant/XP potions); Ench. Redstone Lamp = +16 min AND +3 levels; Gunpowder -> splash, Ench. Gunpowder -> wide splash; Fermented Spider Eye corrupts (Speed/Jump -> Slowness, Strength -> Weakness, Healing -> Damage, Night Vision -> Invisibility); Water + (Ench.) Fermented Spider Eye -> Weakness. 20 s per step (dial). One bottle per slot (a stack is never merged; shift-clicked/dropped stacks are spread one per slot, extras returned). Potion recipes are gated by the collection tiers in `collections.json` ("Resistance Potion Recipe" = `collection:CACTUS:3`, ...) | unit test `brewChain`, `everyIngredientBrewsSomething`, `everyBrewableLevelIsReachable`; self-test `alchemy.brew-chain` |
| **Per-block stands (wave 5)** | Every brewing stand block holds its own brew. On an instanced island you are a member of (private island, garden) the stand is SHARED by the co-op (profile shared key `alchemy_stands`, keyed by the block position relative to the instance slot, so it follows the island across grid slots); one viewer at a time. Any other stand (hub alchemy shop, someone else's island) keeps YOUR brew for that block only (`AlchemyData.stands`, `<island>\|x,y,z`), so strangers never share bottles. `/brew` = the portable stand (the old personal stand migrates into it). Every stand finishes in the background ("Your potions finished brewing! Collect them at the Hub"). Breaking an island stand returns its contents + waiting XP to the breaker; a stand in use cannot be broken | unit tests `standBookkeeping`, `islandStandSurvivesEveryGridSlot`, `xpIsPaidExactlyOncePerPotion`; self-test `alchemy.stand-bookkeeping` |
| **Alchemy XP** | Paid when the potion is TAKEN OUT of the stand (research 02 section 2.4.7: stored per bottle slot with the item's signature, paid once to whoever takes it). Per bottle per research 02 section 2.4.7 (Sugar 5, Enchanted Sugar 300, Enchanted Sugar Cane 15,000, Enchanted Blaze Rod 23,000 ...), Nether Wart 1, modifiers 5 each, x `alchemy.xp-multiplier`, via `SkillService.addXp(ALCHEMY)` so Alchemy Wisdom applies | `researchNumbers` |
| **Brewer perk** | The brewer's Alchemy level x 1% is stamped on each potion they brew (shown on the item: "Brewer bonus (+25% duration)") - skills' hand-off asked alchemy to implement this | `researchNumbers` (Speed I + redstone at Alchemy 50 = 990 s) |
| **Drinking** | Right-click to drink (vanilla drink animation, then our effect; the vanilla result is cancelled). Same effect type REPLACES, different types stack. Duration = (base + extension) x (1 + brewer%) x (1 + Potion Affinity%) x Parrot. Potion Affinity Talisman/Ring/Artifact +10/25/50% (via `Features.Accessories.has`), pets via `Features.Pets.abilityValue`: Parrot "Encore" `parrot_repeat` + Bingo "Long Brew" `bingo_consumer` (fallback when pets gives no number: Parrot +0.2%/level) | `researchNumbers` (+talisman = 1,089 s) |
| **Effects as stats** | StatProvider `potions` (Speed, Strength, Defense, Health Regen, Crit Chance/Damage, Mining Speed/Fortune, True Defense, Pet Luck, Cold Resistance, Magic Find, every Wisdom, Fishing Speed, Pest Chance, Farming Fortune; negative Speed for Slowness/Venomous; Wounded = -25..-100% Health Regen). Behaviour effects: Healing (HP/s), Mana (mana/s), Poison/Venomous (damage/s, never lethal), Absorption/Adrenaline (absorption on drink), Stamina (instant heal + mana), Damage (instant), Archery (+% projectile damage, modifier priority 350), Weakness (-flat damage), Dodge + Agility (chance a mob hit misses, "DODGED!"), Stun (chance to freeze a mob), Knockback, Burning (fire lasts longer), Experience (+% orbs), Water Breathing (chance to ignore a drowning hit), Fire Resistance (-10% fire/lava), Jump Boost / Rabbit / Night Vision / Invisibility / Blindness as vanilla effects | code review |
| **Timers** | Tick only while ONLINE (reference), persist through relog (member data); dial `alchemy.tick-offline` makes them run while offline too. Paused inside dungeons (`Features.Dungeons.inDungeon`), except dungeon potions | code review |
| **Splash** | Throw a splash potion: helpful effects go to every player within 4 blocks (6 for wide; dials), debuffs hit sky mobs only (Damage = instant magic damage, Poison/Venomous = damage over time capped at 30 s, Slowness/Venomous slow, Stun freezes). Owned mobs (another player's slayer boss) are skipped. Vanilla splash effects are zeroed | code review |
| **God Potion** | Right-click the skull: adds 12 h + up to 12 h by Alchemy level (x Parrot) - 28h 48m at Alchemy 50 with a level-100 Parrot, matching the reference lore; legacy one = flat 24 h; time STACKS. 23 effects; each counts as the higher of the God Potion and any regular potion; individual effects can be switched off from /effects | `researchNumbers` |
| **Dungeon potions** | `POTION_DUNGEON;1..7` bundles parsed from the NEU lore (20/40 min). Only drinkable inside a dungeon once a dungeons module is installed (dial) | unit test `godPotionAndBundles` |
| **Potion Bag** | `/potionbag`: potions only (non-potions bounce back), 9 slots at Nether Wart Collection II, +9 at V, VIII, X, XII (45), from `Features.Collections.tier(p, "NETHER_STALK")`; 9 slots when no collections module (dial). Stored as serialised stacks in member data, `saveNow` on close, `keepFreeSlotItems` | self-test `alchemy.potion-lore-renders` checks serialisation round-trip |
| **/effects** | Paged "Active Effects": every effect with description and time left, click -> confirm -> remove; God Potion entry opens a toggle list of its 23 effects | code review |
| **Brewing Guide** | `/brewguide`: every effect (brewable first) with unlock state; click for every level (desc, base duration, rarity) and every ingredient (base, level made, XP, requirement) plus corruption sources | code review |
| **Alchemist NPC** | `alchemy_alchemist` "Alchemist Wendel" at hub `npc.alchemist`: first click = 5 lines of dialogue (the last one points at Basic Brews), then his shop (the reference Alchemist's 12 offers and prices: Nether Wart 30, Brewing Stand 30, 8 Glass Bottles 50, Water Bottle 6, Sugar 4 ...). Click buys 1, right-click buys 16; 150 ms anti-spam; ledger reason `npc-shop: alchemist <id> x<n>`. Buttons to the Brewing Guide and your stand | code review |
| **Alchemy hub** | `/alchemy`: level, brewer bonus, affinity, lifetime stats, and buttons to the stand, guide, effects, bag, Alchemy skill | code review |
| **Tab widget** | `alchemy` (priority 160): "Active Effects: N" + the 3 longest (God Potion first). Dial `alchemy.tab-widget` | code review |
| **Features.Potions** | Provided. `active(p)` = effect id (lower_snake, e.g. `speed`, `true_defense`, `combat_xp_boost`) -> level in force (God Potion included). `apply(p, id, level, seconds)` replaces that effect (instant parts happen too) | code review |
| Recipe book | 61 `OTHER` recipes (station "Brewing Stand", inputs `POTION_AWKWARD` + ingredient, with the collection unlock) so `/recipe` can show how each potion is made | core self-test `recipes.ingredients-exist` |

## Playtest fixes (2026-10-03)

- **Shift-clicking a stack of Water Bottles filled the ingredient slot** (playtest-newplayer: 3 bottles all went
  to the top slot). Root cause: core's generic shift-click fills a menu's free slots in index order and the
  ingredient slot (13) comes before the bottle slots (38/40/42). Fix, inside alchemy: after every stand change
  `BrewingStand.arrange` applies the pure planner `Brew.arrange` - bottles move to the bottle slots one each, a
  stack dropped on one bottle slot is spread over the empty ones, an ingredient that landed in a bottle slot
  moves up when the top is free, and leftovers (a 4th+ bottle, a second ingredient, a non-brewing item in a
  bottle slot) go back to the player via `Sky.loot().give(.., STORAGE)` (never into sacks). Also runs on open,
  so a stand saved with a stacked slot is cleaned up. Unit tests `shiftClickedBottlesSpreadOnePerSlot`,
  `arrangeEdgeCases`, and `arrangeConservesEveryItem` (all 65,536 kind/amount combos: every item lands in
  exactly one place, bottle slots only ever hold one bottle, and arranging is idempotent - no dupe, no delete).
  10/10 unit tests green; `check.sh alchemy` OK. **Not yet re-checked in a real client.**

## Wave 3 (2026-10-04)

Driven by the shops hand-off (Request 5: the hub Bartender's drinks had no owner, so its shop showed missing
items) and the menus hand-off (Request 5: honour `tab.effects`).

- **Basic Brews - a new brewing route, end to end.** 9 brews registered from the NEU item files (rarity, skull
  texture, NPC sell price; every number asserted against the NEU lore by the generator): Cheap / Decent / Black
  Coffee (+5 / +8 / +12 Speed), Tepid Green Tea (+10% Defense), Pulpous Orange Juice (+5% healing), Bitter Iced Tea
  (+20% mana and experience), Knockoff Cola (+5% Strength), Carmine Premium (Wounded +10%), Red Thornleaf Tea
  (Resistance only: 5% of a monster's hit is reflected at it). A brew goes in a **bottle slot instead of an
  Awkward Potion**; it only reacts with the ingredients of potions it can boost (Cheap Coffee + Sugar = Speed I,
  Cheap Coffee + Blaze Powder = refused with "It only makes: Speed, Adrenaline, Rabbit, Agility, Spirit"). The
  potion carries the brew (`extra.brew`) through glowstone / redstone / gunpowder; Fermented Spider Eye keeps it only
  if the corrupted twin still benefits (coffee Speed -> plain Slowness). Lore: "Brewed with Cheap Coffee: +5
  Speed"; the stand's status line shows it too. Drinking or splashing stores it on the active effect
  (`AlchemyData.Active.brew`) and it boosts that effect while it is the one in force (a higher God Potion level
  wins and carries no brew). Stats via the StatProvider, healing / mana per second and instant, experience orbs,
  the Wounded cut, and the new `reflect` param (MONITOR on `SkyEvents.Damage`, sky mobs only, next tick, MAGIC).
  A brew never touches a negative value (Slowness' -Speed). Shift-clicked stacks of brews are spread one per
  bottle slot like Water Bottles (`Brew.arrange` reads them as bottles). Brewing Guide: a **Basic Brews** page
  (slot 50) and each potion's page lists the brews that make it. Recipe book: 47 extra `OTHER` recipes
  (brew + ingredient -> potion), generated from a Python mirror of `appliesTo` that a unit test checks against
  the Java rules both ways.
- **Tavern drinks**: `HOT_CHOCOLATE` (a material; Crimson Isle brewers sell it) and `SCORNCLAW_BREW` ("Bitterclaw
  Brew", right-click to drink, flavour only - the reference effect is not in the research).
- **Splash Weakness / Blindness on mobs now do something**: Weakness removes its flat damage from the mob's hits
  on players (DamageModifier, capped by `alchemy.splash-dot-cap-seconds`); Blindness gives vanilla blindness and
  makes the mob drop its target.
- **`tab.effects` honoured**: the Active Effects tab widget hides when the player turns it off in Settings
  (read from menus' PDC mirror `skyclone:setting.tab.effects`).
- Admin: `/sb alchemy simulate` accepts `brew:CHEAP_COFFEE` bottles and prints `brew=` on results.
- Verified: `check.sh alchemy` OK; **13/13 unit tests** (new: `basicBrews`, `everyBasicBrewBrewsWithEveryIngredientItBoosts`,
  `recipeBookMatchesTheBrewingRules`); new self-test `alchemy.basic-brews`; `alchemy.items-exist` and
  `alchemy.potion-lore-renders` extended (brew items exist with the `basic_brew` tag, a brewed potion round-trips,
  Cheap Coffee reads as a Basic Brew bottle). **Not seen on a server or in a client yet** (this phase may not
  start one). First live check: `/sb selftest alchemy`, then buy a Cheap Coffee from the Bartender, brew it with
  Sugar, drink it and look at Speed in `/stats`.

## What is partial / not built

- **Mixins** are the slayer module's (drunk directly as 36 h stacking buffs, Boon Cookie gated). Adding a mixin to
  a God Potion AT the brewing stand (the reference's other route) is not built: there is still no slayer service for
  mixins (Request 12). Mixins are therefore not listed in /effects either.
- **Ingredient mappings marked TUNE** (`tune: true` in `effects.json`): the research still does not name them.
  Critical = Flint, Spelunker = Mithril Ore (brewable to IV; V only from the God Potion), True Resistance = True
  Essence, Spirit = Foul Flesh, Pet Luck = Enchanted Rabbit Hide, Rabbit = Raw Rabbit / Enchanted Raw Rabbit,
  Cold Resistance = Enchanted Glacite, Adrenaline XP, Poison III = Enchanted Spider Eye, Blindness III = Enchanted
  Ink Sack, Enchanted Ghast Tear = Regeneration **V** (so VIII is reachable), and all seven XP Boost potions
  (research 08's "(?)" ingredients, 300 XP). Enchanted Redstone Lamp = both modifiers (research marks it "(?)").
- **Midnight Wit growth rate is a guess** (dial `alchemy.jyrre-minutes-per-int`, 10 min per +1 = 300 in 50 h): the
  research gives the cap (+300) and the durations, not the rate. Growth only counts the main inventory while online.
- **Not brewable** (registered for other modules to sell/drop; same as the reference): Magic Find I-IV, Stamina I-IV,
  the special potions, dungeon potions, God Potions.
- Wounded on a PLAYER only cuts the Health Regen stat (core's other heals are not hookable). Splash debuffs never hit
  players (no PvP in the genre). Stun = 1 s heavy slowness; Knockback scales the victim's velocity a tick after the hit.
- Potion Affinity / pet bonuses are applied on drink but cannot show on the item (lore is not per-viewer).
- Not seen in a real client: every menu, drinking, throwing, the God Potion click, the NPC, the per-block stands, the
  special potions. The self-tests build items and lore and run the brewing rules; menus were not opened on a server.

## Wave 5 (2026-10-06)

### Done
- **Per-block brewing stands** (the task; the old "personal stand" partial is gone). `BrewingStand` resolves a block
  to a `Ref` (ISLAND / PUBLIC / PORTABLE, see the table above); pure bookkeeping in `Stands` (keys, Witch brew
  time, XP-per-bottle, legacy migration). Anti-dupe: island stands take a lock (one viewer; "Someone else is using
  this brewing stand!"), a locked stand cannot be broken, background completion skips an open stand, every menu is
  `keepFreeSlotItems` + saved on every change and on close. Dial `alchemy.island-stands-shared` (false = island
  stands are per player too).
- **Alchemy XP on take-out** (research 02 section 2.4.7): each bottle slot stores the XP its brew earned and the item's
  signature; whoever takes it out (or swaps it) is paid once. A stand's status shows "Waiting Alchemy XP".
- **Special potions** (data from a prior pass of this wave, Java now): **Dread Draught** (`GREAT_SPOOK_POTION`, skull,
  right-click: Dread +3 Fear for 24 h - weapons' `Passives.fear` already reads `great_spook`); **Midnight Wit**
  bottles (`BOTTLE_OF_JYRRE` 10 min, `NEW_BOTTLE_OF_JYRRE` 60 min, `REFINED_BOTTLE_OF_JYRRE`): the bonus grows +1
  Intelligence per `alchemy.jyrre-minutes-per-int` minutes in the inventory (cap +300, lore shows it), the New bottle
  EVOLVES into the Refined one at its cap, the Refined one gives +300 for 60 m and +1 Intelligence for good for the
  first `alchemy.jyrre-refined-max` (5) drunk; **Recall Potion** (skull: in the Crystal Caverns the first right-click
  saves your spot on the potion, the next drinks it and teleports you back; sneak re-saves; dial
  `alchemy.recall-island`); **Secret I** (`POTION_SECRETS;1`, soulbound, ticks as a dungeon potion so it keeps
  running in dungeons; dungeons can read `active(p).get("secrets")`); **Gravity I Splash** (`POTION_RIFT_GRAVITY;1`,
  10:00, rift reads `active(p).get("rift_gravity")`). Brewing Guide says where each unbrewable potion comes from.
- **Pet and accessory hooks** (pets hand-off Request 10): Parrot "Encore" `parrot_repeat` and Bingo "Long Brew"
  `bingo_consumer` lengthen every potion; Parrot "Feathered Brew" `parrot_parrot_feather_infusion` lengthens God
  Potions; Witch "Quick Brew" `witch_alchemism` shortens the brew (fixed when the brew starts); Jellyfish "Strong
  Brew" `jellyfish_powerful_potions` makes dungeon potions (source "dungeon") stronger inside a dungeon (stats,
  healing and mana per second). Potion Affinity accessories were already wired. Reforges hand-off: nothing there
  belongs to alchemy.
- **Wounded splashed on a mob now works**: once a second, the health a Wounded mob GAINED is cut by the potion's %
  (core has no mob-heal hook, so alchemy watches the mob's health itself); damage is never touched.
- `Features.Potions.openEffects` / `openBag` overridden (core-wave5 defaults ran the commands).
- Admin: `/sb alchemy brew <p> status|finish|clear` covers every stand of the player; `/sb alchemy jyrre <p> <bonus>`
  sets the held bottle's bonus; `/sb alchemy give <p> great_spook 1` / `jyrre 1` give the special items.
- Verified: `check.sh alchemy` CHECK OK; **18/18 unit tests** (new: `specialPotions`, `standBookkeeping`,
  `everyEffectHasAnItemAndOnlyStandardIdsAreGenerated`, `islandStandSurvivesEveryGridSlot`,
  `xpIsPaidExactlyOncePerPotion` - 2,000 random take/put/swap runs never pay more than stored and pay all of it
  once emptied). Generator re-run: 52 effects, 218 levels, 443 items. **Not started on a server** (lane rule).
  First live check: `/sb selftest alchemy`, then place two stands on your island, brew in both, close, reopen.

### Still left
- Mixins into God Potions at the stand (needs a slayer service).
- TUNE ingredient rows and the Midnight Wit growth rate (no research yet).
- Live check of every menu and of the island-stand lock with two co-op members.

## Commands added (player + admin)

| Command | What |
|---|---|
| `/alchemy` | Alchemy hub menu |
| `/brew` | Your portable brewing stand (dial `alchemy.brew-anywhere`; always opens while it still holds something). Any brewing stand BLOCK opens that block's own stand |
| `/effects` (`/activeeffects`) | Active Effects |
| `/potionbag` (`/pbag`) | Potion Bag |
| `/brewguide` (`/potionguide`) | Brewing Guide |
| `/sb alchemy list [filter]` | Every effect with levels / brewable max |
| `/sb alchemy give <p> <effect> <lvl> [splash\|wide] [extMinutes]` | Give a potion (e.g. `give Steve speed 5 splash 8`) |
| `/sb alchemy apply <p> <effect> <lvl> <seconds>` | Apply an effect directly |
| `/sb alchemy clear <p>` / `effects <p>` | Remove all effects / print them (incl. what is in force) |
| `/sb alchemy god <p> <hours>` | Add God Potion time |
| `/sb alchemy brew <p> status\|finish\|clear` | Inspect, rush or empty EVERY stand of a player (portable, per-block, island) |
| `/sb alchemy jyrre <p> <bonus>` | Set the held Midnight Wit bottle's Intelligence bonus |
| `/sb alchemy bag <p>` | Potion Bag fill / size |
| `/sb alchemy open <p> hub\|stand\|bag\|effects\|guide\|shop` | Open a menu for a player (RCON) |
| `/sb alchemy simulate <ingredient> <bottle...>` | Run the brewing rules with no player: bottles `water`, `awkward`, `brew:CHEAP_COFFEE`, `speed:2[:extSec][:enh][:splash]`. E.g. `simulate GLOWSTONE_DUST speed:1 combat_xp_boost:3` |

## Dials added

`alchemy.brew-seconds` (20), `alchemy.brew-anywhere` (true), `alchemy.brewer-pct-per-level` (1.0),
`alchemy.affinity-talisman-pct` (10), `alchemy.affinity-ring-pct` (25), `alchemy.affinity-artifact-pct` (50),
`alchemy.parrot-pct-per-level` (0.2), `alchemy.god-base-hours` (12), `alchemy.god-alchemy-hours` (12),
`alchemy.god-legacy-hours` (24), `alchemy.splash-radius` (4), `alchemy.splash-radius-wide` (6),
`alchemy.xp-multiplier` (1.0), `alchemy.npc-enabled` (true, restart), `alchemy.dungeon-potions-dungeon-only` (true),
`alchemy.pause-in-dungeons` (true), `alchemy.tick-offline` (false), `alchemy.tab-widget` (true),
`alchemy.bag-fallback-slots` (9), `alchemy.splash-share` (true), `alchemy.splash-dot-cap-seconds` (30); wave 5:
`alchemy.jyrre-minutes-per-int` (10, TUNE), `alchemy.jyrre-refined-max` (5), `alchemy.recall-island` (crystal_hollows),
`alchemy.island-stands-shared` (true). `alchemy.parrot-pct-per-level` is now only the fallback when pets gives no number.

## Self-tests added

| id | checks |
|---|---|
| `alchemy.table-valid` | every effect has levels, corruption targets exist, every ingredient/God/bundle level exists |
| `alchemy.research-numbers` | Speed I/VIII = +5/+40, Strength I/VIII = +5/+75, Resistance VIII = +60, Critical IV = 25%/40%, Haste I = +50, Archery IV = +75%, Absorption VIII = 300, Mana VIII = 8/s, XP Boost III = 36:00, rarity I Common / VIII Epic, Ench. Sugar Cane = Speed V for 15,000 XP, Ench. Blaze Rod 23,000 XP, duration formula (990 s / 1,089 s), God Potion 28h 48m and legacy 24 h |
| `alchemy.brew-chain` | the whole chain water -> awkward -> Speed I -> II -> extended -> splash -> wide -> corrupted, caps, once-only modifiers, XP boosts ignore redstone, healing not extendable |
| `alchemy.items-exist` | every potion/splash id, every ingredient and modifier item, every shop item, God/dungeon/base potions; and that `POTION` is alchemy's Water Bottle |
| `alchemy.potion-lore-renders` | every potion item creates and renders effect lore; a modified splash potion round-trips through item data AND through the bag/stand serialisation |
| `alchemy.unlock-keys` | every recipe unlock is `collection:<ITEM>:<tier>` and (with collections installed) names a real collection |
| `alchemy.hub-anchors` | hub layout has `npc.alchemist` and `npc.brewing_stand` |
| `alchemy.basic-brews` | NEU numbers of all 9 brews (coffee +5/+8/+12 Speed, tea +10% Defense, cola +5% Strength, iced tea +20% mana, juice +5% healing, thornleaf 5% reflect), negatives untouched, coffee + sugar carries the brew through every modifier, coffee refuses Blaze Powder and Nether Wart, corruption drops it, thornleaf brews Resistance only |
| `alchemy.special-potions` | Dread +3 Fear 24 h, Midnight Wit scales (+120 = +120 Int, label "+120"), growth math capped at 300, Gravity 10:00 splash-only, Secret not brewable, Chilled Spirit Water +10/+25, no special potion reacts with a modifier, Wounded-mob heal cut (40 -> 30 at 25%, damage untouched) |
| `alchemy.stand-bookkeeping` | island keys are slot-relative, public keys per block, XP paid once to the taker (and not again when put back), Witch +100% = half time, the pre-wave-5 stand migrates once |
| `alchemy.special-items-behave` | Dread Draught is a skull potion (never a brewing bottle), Midnight Wit bonus from item data (50 x rate min = +50, Refined = +300, evolves to Refined), Secret ticks as a dungeon potion, Gravity is a thrown splash, Recall Potion is alchemy's |

## Requests (things you need from core or another module - the integrator reads this)

1. **hub (builder)**: anchors `npc.alchemist` (Alchemist stands here) and `npc.brewing_stand` with a real
   `brewing_stand` BLOCK at it (any brewing stand block in a game world opens the player's stand).
2. **menus**: SkyBlock Menu -> "Active Effects" = `Sky.commands().run(p, "effects")`; "Your Bags" -> "Potion Bag" =
   `run(p, "potionbag")`; anywhere you want brewing: `run(p, "alchemy")`. (Integrator: consider adding
   `openEffects(p)` / `openBag(p)` to `Features.Potions`; alchemy would implement them.)
3. **shops**: do NOT register a second Alchemist at `npc.alchemist` (alchemy owns it; if you want it, set
   `alchemy.npc-enabled false`). Bartender potions = alchemy ids, e.g. `POTION_SPEED;3`, `POTION_STRENGTH;3`,
   `POTION_MAGIC_FIND;1..4`, `POTION_STAMINA;1..4` (both unbrewable, so shops/loot are their only source).
   Crimson Isle bartender: `POTION_WISP_ICE;1`.
4. **progression / community shop (bits)**: sell `GOD_POTION_2` for 1,500 bits (research 08 section 2.4).
5. **dungeons (wave 2)**: Ophelia sells / chests drop `POTION_DUNGEON;1..7`; `Features.Dungeons.inDungeon` drives
   the pause and the dungeon-only rule. Mid-run "Healing/Buff Potion" ghost abilities may call
   `Features.Potions.apply`.
6. **garden / fishing / crimson**: reward/shop ids `POTION_HARVEST_HARBINGER;5`, `POTION_STINKY_CHEESE;1`,
   `POTION_MUSHED_GLOWY_TONIC;1`.
7. **accessories**: alchemy reads `POTION_AFFINITY_TALISMAN`, `RING_POTION_AFFINITY`, `ARTIFACT_POTION_AFFINITY`
   through `Accessories.has` (already your ids). **pets**: Parrot = active type `PARROT`.
8. **mining / materials**: brewing needs `MITHRIL_ORE`, `ENCHANTED_GLACITE`, `ENCHANTED_RABBIT`,
   `ENCHANTED_RABBIT_HIDE`, `ENCHANTED_GHAST_TEAR`, `GLASS_BOTTLE` (today only the pets module's fallback defines
   several of them). Self-test `alchemy.items-exist` lists any that go missing.
9. **materials**: alchemy registers `POTION` (Water Bottle, stack 64) in enable, so your `POTION` def is skipped
   (expected).
10. **shops (answered 2026-10-04, shops Request 5)**: alchemy now registers the Bartender / tavern drinks
    `CHEAP_COFFEE`, `TEPID_GREEN_TEA`, `PULPOUS_ORANGE_JUICE`, `BITTER_ICE_TEA`, `KNOCKOFF_COLA`, `DECENT_COFFEE`,
    `BLACK_COFFEE`, `HOT_CHOCOLATE`, `SCARLETON_PREMIUM`, `RED_THORNLEAF_TEA`, `SCORNCLAW_BREW`. The `*_MIXIN`s
    are slayer's. `/sb shops missing` should no longer list the drinks.
11. **crimson**: if you ever register these drink ids yourself, keep the `basic_brew` tag and `extra.alchemy`
    or brewing with them stops working (`alchemy.items-exist` flags it). Better: leave them to alchemy.
12. **slayer (optional, still open)**: to let a mixin be brewed INTO a God Potion at the stand (reference route) and to list
    active mixins in /effects, expose them through a service (e.g. `Features.Slayer`-style `mixins(Player)` ->
    id -> expiry, and `applyMixin(Player, id, millis)`). Until then mixins stay drink-only, which works.

## Known risks

1. **Drinking is a cancelled vanilla consume + manual removal.** If a client shows a desync (bottle reappears for a
   tick) it is cosmetic; if Paper's consume event ever stops reporting the hand, nothing is consumed (fails safe).
2. **Stand/bag contents are serialised ItemStacks in member data.** A Minecraft data-version bump is handled by
   Paper's `deserializeBytes`; a stack that cannot be read is logged and shown as an empty slot. The bag keeps the
   unreadable entry in data unless the player puts something else in that slot; the stand drops it on its next sync.
4. **Hub needs `npc.alchemist` / a brewing stand block.** Without them the Alchemist stands near hub spawn (core
   fallback) and brewing still works through `/brew` (dial on by default).
3. **TUNE ingredient mappings** (list above) are the most likely thing the owner will want changed - edit
   `ING(...)` in `tools/gen/alchemy/gen_alchemy.py` and re-run it.
5. **(wave 5) The island-stand lock trusts `Sky.menus().current(p)`.** A co-op stand is "in use" while its viewer's
   current menu is that stand's menu; a stale lock (crash, menu swapped) is dropped the moment it is checked. If two
   members ever DID see one stand at once, the second save wins (no item is created, but one view's changes are lost).
   Untested with two real clients.
6. **(wave 5) Island membership comes from `Features.PrivateIsland.canBuild`.** If that answers false for a co-op member
   (or the island module is missing), the stand falls back to a per-player PUBLIC stand - safe, just not shared.

## Playtest fix (2026-10-04, wave-4 loops)
Potion Dodge/Agility (`EffectEngine.modify`) no longer applies to hits tagged `ability` (slayer boss mechanics are tagged by the slayer module). Melee from mobs is still dodged.

## Requests (wave 5)
13. ~~**materials**: fallback "Refined Bottle of Midnight" vs alchemy's "... of Midnight Wit"~~ **Settled 2026-10-06**
    (names lane, wave-5 playtest naming #9): the bottles are now "Lost Bottle / Bottle / Refined Bottle of Midnight"
    and the effect "Midnight Wit", so both catalogues agree and nothing is named after Gerald the island helper.
    materials can still drop its copy (alchemy registers first).
14. **rift**: sell `POTION_RIFT_GRAVITY;1` and read `Sky.service(Features.Potions.class).active(p).get("rift_gravity")`
    to raise Puffling spawns. **dungeons**: drop `POTION_SECRETS;1`; read `active(p).get("secrets")` to show secrets.
15. **mayors (Dread festival)**: reward `GREAT_SPOOK_POTION`; Fear = 3 while `active(p)` has `great_spook`
    (weapons' `Passives.fear` already counts it). Your events already list `BOTTLE_OF_JYRRE`.
16. **mining**: sell/drop `RECALL_POTION` in the Crystal Caverns (it only works on island `alchemy.recall-island`).
17. **pets (FYI)**: `parrot_repeat`, `bingo_consumer`, `parrot_parrot_feather_infusion`, `witch_alchemism` and
    `jellyfish_powerful_potions` are now READ by alchemy; mark them "read by alchemy" in your info-only list.
