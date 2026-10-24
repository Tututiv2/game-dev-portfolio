# bazaar — hand-off

Package `com.skyclone.mod.bazaar`, entry `BazaarModule`. A per-product **order-book exchange** (research 05 s2):
Instant Buy / Instant Sell walking the book, Buy Orders (coins escrowed) and Sell Offers (items escrowed) with
price-time priority and partial fills, 1.25% seller tax, claims, cancels, 7-day expiry, price protection, price history
— plus **server market makers** and **simulated traders** so the market is liquid with two players online.

Data is generated, never typed: `py -3 tools/gen/bazaar/gen_bazaar.py` reads `research/data/bazaar.json` (2,197
products), `items.json`, `collections.json`, the NEU recipes/NPC shops and (read-only) the shops module's
`data/shops/shops.json`, and writes `plugin/src/main/resources/data/bazaar/products.json` (id, category, group,
reference price, snapshot bid/ask, NPC sell, NPC shop price, weekly volume, `mm` flag, `mmSides`) and `groups.json`
(6 categories, 281 groups). `tools/gen/bazaar/report.txt` lists every placement and every price derivation/clamp.
`explore.py` re-audits unplaced products.

## Wave 5 (2026-10-06)
### Done
- **Seeding trick: Merchant Guild supply drops** (research 05 s2.9, "periodic NPC sell offers for progression-gating
  materials to keep early game moving"). Built in three layers:
  - *Which materials* is derived, not typed: `gen_bazaar.py` `seed_materials()` walks every NEU crafting/forge recipe
    whose output is gated at an EARLY tier (slayer <= 5, HotM <= 4, skill <= 20, Catacombs <= 15, from items.json
    `requirements` + NEU `crafttext`), unwraps crafted ingredients down to the drops they are made of, keeps drops that
    are not collection roots, cost 20..100k coins and trade >= 20k/week, and ranks by how many early items they gate.
    Top 30 get `seed` (units per drop = the largest early craft's need, 16..640) and `seedFor` (3 gated items) in
    `products.json`; every other field is byte-identical to before. Result: Jacob's tickets, titanium, Null Spheres,
    wolf teeth, glacite jewels, derelict ashe, Revenant flesh, Tarantula web, treasurite, gill membrane, the eight
    dragon fragments, stretching sticks, starfall, sludge juice, catalysts... (`report.txt`, lines `SEED`). The
    thresholds are `// TUNE` constants at the top of that section.
  - *Live*: new `Seeder` + pure `Seeding`. Every `bazaar.seed-interval-minutes` (30) the guild REPLACES its one
    standing Sell Offer per material with a fresh batch (never accumulates), priced at the market maker's un-skewed
    ask x `seed-price-percent` (100), floored one tick over the maker's best bid and over the NPC sell price, never
    above an NPC shop (then skipped), batch capped at `seed-max-value` (2M coins). Why it is not redundant with the
    market maker: an emptied maker skews its price UP and restocks with a 6 h half-life, and on a busy product it
    fades out; a player who buys every offer and relists at 10x would own a progression material. The guild's drop
    returns at a fair price every interval regardless. Guild orders are `Order.guild` (also `bot`): not persisted,
    share the per-player 2,240/hour allowance with the makers, survive the makers' re-quotes (`clearBots` skips them),
    and their fills are booked to the guild (`Market.afterFills`), not to the maker's stock. Guild sales only remove
    coins (`/sb bazaar seed` shows the total).
  - *Money safety*: the maker's bid is capped one tick under the guild's ask (`MarketMaker.quote`), and the guild's
    ask is a direct source in the recipe-cap cost graph (`computeCaps` + new `lowerExternal`, which re-clamps every
    output and re-quotes the product itself if its bid reached the new ask). So "buy from the guild, sell to the bots"
    and "buy from the guild, craft, sell to the bots" both lose.
  - UI: product page shows "Merchant Guild: N left at X coins / Restocks every 30m, next in ... / Needed for <registered
    names>"; the How-it-works book has a line. Admin: `/sb bazaar seed [list|now|off <item>]`, plus a line in `info`.
  - Dials: `bazaar.seed-enabled, seed-interval-minutes (30), seed-price-percent (100), seed-batch-mult (1),
    seed-max-value (2,000,000), seed-products` (edits: `ID:n` add/resize, `ID` add at 64, `-ID` remove; `none` = no edits).
  - Self-tests: `bazaar.seed-catalogue`, `bazaar.seed-no-arbitrage`, `bazaar.seed-never-accumulates`.
    Unit tests: new `SeedingPureTest` (6: price floors over 20,000 random inputs, value-capped batch, dial grammar,
    timer, guild orders survive a re-quote / count against the allowance / keep time priority, the shipped data).
- **Essence buy gate** (research 02 s8: "can be bought on the Bazaar only with Catacombs 20, Forest Essence exempt").
  Instant Buy and Buy Orders of `ESSENCE_*` need `bazaar.essence-buy-requirement` (`catacombs:20`, dungeons' prefix;
  fails open if no module owns it) unless listed in `bazaar.essence-buy-exempt` (`ESSENCE_FOREST`). Selling is free.
  The wizards refuse up front. Pure `Gate.essenceGated`, unit-tested.
- **Instant Buy 4% hold** (research s2.3: "charged up-front at 4% above the current cheapest sell offer; any difference
  refunded"): `bazaar.instant-buy-reserve-percent` (4). The purse must cover the hold; the refund is hold - paid; the
  amount picker shows the hold. Pure `Pricing.instantBuyHold`.
- **Unstackable order limits** (research s2.3): one order of a non-stacking item (enchanted books) is capped at
  `bazaar.max-order-unstackable` (256), and the Buy Order presets are 1 / 4 / 64 for them, 64 / 160 / 1,024 for
  stackables (16-stack items used to get a "stack" preset of 16). `Market.maxOrder`, `Pricing.orderCap/orderPresets`.
- **Event shocks**: `mythological_ritual` (mayors' forced event) added to the default dial: Ancient Claw, Enchanted
  Ancient Claw, Griffin Feather x0.8 while it runs (the ritual floods them).
- **Accessories / pets / reforges hand-offs**: read for effects that belong to the Bazaar. None of them changes a
  Bazaar rule (no accessory or pet in research 06 touches tax, slots or prices; Bazaar Flipper is the bank's
  Community Shop and is wired). The one rule that does touch us, research 01 s20 "Sack/Bazaar strip rarity_upgrades
  from stackable items", already holds: we remove by id through core and the buyer receives a fresh stack, so a
  recombobulated or reforged stackable never crosses the book with its data. Reforge stones, potato tomes, Rarity
  Reshaper and essences arrive as `bazaar`-tagged items (Oddities -> Miscellaneous, bid-only) as reforges asked.
- `check.sh bazaar` CHECK OK; **31/31** pure tests (`BazaarPureTest` 25 + `SeedingPureTest` 6, plain javac + JUnit
  5.11.4 launcher). Server NOT started (lane rule): the 16 self-tests and `/sb bazaar seed now` + `seed list` are the
  integrator's live check.

### Still left
- Never run live: the guild drop, its product-page lines, the essence gate message and the 4% hold have not been
  clicked by a client. Watch `/sb bazaar seed list` for products skipped as "no module registers this item" (the
  seeded drops must exist in the slayer/dragon/mining/fishing modules to be sold).
- Guild interval, batch rule, price percent and the generator's "early" thresholds are `// TUNE` (research gives the
  trick, not the numbers). The seeded list is drops only; if a server stalls on a CRAFTED material, add it with
  `bazaar.seed-products ID:n`.
- Stranded's Special Bazaar still mirrors Ironman's (research marks it "(?)"; Stranded is end-of-life in the reference).
- The remaining items of "What is partial / not built" below.

## Finish wave 2026-10-03
- **Better-price message (playtest)**: confirmed fixed (see the fix pass below) — placement only says "at a better
  price" when `Pricing.improvement` is > 0 and then names the amount; a same-price cross says "at your price." The
  sell side compares the PRE-tax gross against `limit x qty`, so tax can never hide or fake an improvement.
- **Special Bazaar (Ironman + Stranded)**, research s9.3/9.4. New dial `bazaar.special-modes` (ironman,stranded):
  those profiles get a 4-row "Special Bazaar" page instead of the category browser — just the products in
  `bazaar.ironman-products` (Booster Cookie) with live buy price, click to Instant Buy, plus Manage Orders when they
  still have orders from before (claims/cancels always work). Product pages, `/bz <item>`, other modules' "View on
  Bazaar" and every Back button route to it; selling/orders are refused with "<Mode> profiles can only buy ... from
  the Special Bazaar!". `bazaar.blocked-modes` default is now just `bingo`. `bazaar.special-anywhere-modes`
  (stranded) skips the remote (cookie) requirement, because Stranded can never visit the Hub.
- **Event shocks** (research s2.9 "scripted MM adjustments on event days"): new pure `EventShocks` + dial
  `bazaar.event-shocks` (`event=ID,ID:factor;...`). While a calendar event is active the market makers quote its
  products at `fair x factor`; the multiplier GLIDES in and out with half-life `bazaar.event-shock-glide-minutes`
  (20), so prices drift rather than collapse, and the stored fair value never carries the shock (nothing to undo,
  a restart just glides back in). Defaults: Season of Gerald gifts x0.75, Winter Workshop snow/ice x0.85, Spooky
  Festival candy/ectoplasm x0.7, Fishing Festival shark parts x0.8. Bid caps (recipe/reference) still apply on top;
  the simulated traders' fair band uses the shocked fair too. `/sb bazaar shocks` lists every shock, whether its
  event is running and each product's current multiplier; `/sb bazaar price` shows it.
- **Merchant Guild floor** (research s2.9 seeding trick): `bazaar.mm-guild-premium` (1%) — market makers bid NPC sell
  +1% on every two-sided FARMING product, so a new player's first harvest always beats the NPC. Never applied when it
  would reach 99% of the NPC shop price (no buy-from-shop/sell-to-guild loop); recipe and reference caps still win.
- Self-tests `bazaar.event-shocks` (every named event is on the calendar, every product exists, guild floor sits
  between NPC sell and NPC shop) and `bazaar.special-bazaar` (product list real, Stranded not both blocked and
  special). Unit test `eventShocksParseStackAndGlide`: **22/22 pure tests pass**; `check.sh bazaar` OK.
- Not run live (owner streaming; no server started).

## Fix pass (2026-10-04, wave-3 playtest) — the Level 7 gate for the AI show
Finding (major, AI show): after ~3h40m the profile is SkyBlock Level 3 (~90 XP/hour), so the research Level 7 gate
keeps the Bazaar shut for the first episodes. The gate already WAS a dial (`bazaar.access-requirement`), so a second
"show-only" dial would just be two knobs for one rule. The root was that the dial's off position could not be reached
in game: its description said "Empty = everyone", but `/sb dial set <key>` refuses an empty value (so a ruleset's
`""` is rejected as Usage), and the obvious words `none` / `off` / `""` were stored literally and handed to the
requirement registry, which fails CLOSED on an unknown prefix — the Bazaar shut for EVERYONE, the opposite of the ask.
- New pure `Gate.key(raw)`: `none/off/open/everyone/0/-/""/''` (case-insensitive, quotes stripped) = no requirement;
  a bare number N = `sblevel:N`; anything else passes through as a requirement key. `Bz.access` checks `Gate.key(...)`.
- So the show (AISkyShow `rulesets.yml`, `dials:` of a ruleset — that project's file, not edited here) can write
  `bazaar.access-requirement: 3` (Bazaar at SkyBlock Level 3, i.e. inside the first session) or `none`, and
  `/show ruleset normal` resets it to research `sblevel:7`. Research default unchanged.
- `/sb bazaar info` prints the live gate ("Access: sblevel:3 (bazaar.access-requirement = '3')" / "everyone").
- Self-test `bazaar.access-gate`: both access dials resolve to a requirement prefix some module owns (a typo would lock
  the Bazaar for everyone) and the open words / numbers map as above. Pure test `accessGateWordsAndLevels`
  (23/23 in `BazaarPureTest`). Not run live (no server per instructions).

## Fix pass (2026-10-03, playtest-newplayer) — "better price" said on a same-price cross
- **Symptom**: a sell offer at 2.2 that crossed a 2.2 bid said "13 sold instantly at a better price!".
- **Root cause**: `placeBuy`/`placeSell` printed "better price" whenever ANY quantity crossed, never comparing the
  fills against the player's own limit; the confirm-menu lore (`TradeMenus.confirm`) made the same blanket claim.
- **Fix**: new pure `Pricing.improvement(buy, limit, qty, actual)` = tenths saved (buy) / earned extra (sell) versus
  `limit x qty`, never negative. Placement now says "filled/sold instantly at your price." when it is 0, and
  "at a better price, saving/earning X coins" with the real amount when it is not. The confirm lore uses the same
  helper on the quote and drops "the rest waits on the book" when the whole order fills.
- **Test**: `BazaarPureTest.crossingAtOwnPriceIsNotABetterPrice` (21/21 pure tests pass; `check.sh bazaar` OK).

## Fix pass (2026-10-02, integration round) — `bazaar.no-craft-arbitrage` failed
Symptom: the bots' bid on a craftable item sat above the cost of its inputs at the bots' asks (BEJEWELED_HANDLE on
early boots, METEOR_SHARD 359,488 vs 309,432 on the last), changing from boot to boot. Two root causes, both fixed:
1. **The recipe graph was frozen at our postEnable.** `parseRecipes()` cached `Sky.recipes().all()` once, in
   `MarketMaker.start()`, but modules enabled after bazaar register recipes in THEIR postEnable (crafting's `sb:`
   reference recipes, minions, mining forge; materials adds items that change how `ID:n` cells parse). Those outputs
   had no recipe cap at all, while the self-test read the full registry. Which item tripped it depended on module
   order. Now the graph is keyed on recipe+item registry size and rebuilt (plus an NPC-shop rescan) on the first
   tick it changes, followed by an immediate re-clamp of every bid. The self-test also fails outright if the caps
   were built from a stale registry (`MarketMaker.capsCurrent()`).
2. **Caps were a once-per-cycle snapshot.** An input re-quoted cheaper mid-cycle (drift, skew; and at boot hours of
   drift) left already-posted output bids above the new input cost until the next cycle. Now the cap is read FRESH
   at quote time from a new pure `CostGraph`, which `quote()` lowers the moment an ask drops; that lowering
   propagates through every recipe chain and book ladder and returns every product whose cap may have fallen, which
   `drainReclamp()` re-quotes if its bid on the book is now above the cap (same tick, budget 200/tick; unlimited at
   boot, after a registry rebuild, and after `/sb bazaar mm refresh`). Costs only fall between rebuilds, so a bid
   clamped against them stays valid until a lowering names it.
- `CostGraph.java` (new, pure, unit-tested) replaces the inline `caps`/`acq`/`Parsed` code; book ladders are just
  2-to-1 recipes in it. A gain cycle (A -> 2B, B -> A) is bounded by `MAX_LOWERINGS` per relaxation.
- `MmMath`: a cap below one tick (0.1 coin) now posts no bid instead of the 1-tick minimum (which was above the cap).
- Unit tests: +5 in `BazaarPureTest` (cheapest-way cap, transitive re-clamp naming, 2,000 random input re-quotes
  never letting a bid pay for craft-and-sell, sub-tick cap, gain cycle terminates). 20/20 pass; `check.sh bazaar` OK.
- Not verified on a live server yet (the next integration round boots it).

## Review pass (2026-10-02) — what changed and why
A second agent reviewed the module end to end. Fixes, most severe first:
1. **Cheap rare items from the bots (data bug).** The generator priced a wide-spread snapshot by "the smaller
   number", so a 0.1-coin troll buy order set the price: ~110 market-made products (Fire Protection VI, Shadevine,
   Small Pig Ear, Molten Cube, Furball, End Stone Shulker, Toil Log...) would have been SOLD by the bots for 0.2-1.5
   coins. Now a lowball snapshot (bid under 1/20 of the ask, or bids with nobody selling) is re-derived from
   recipes/book neighbours and never valued above its own ask; if it cannot be derived the product is **one-sided**
   (`mmSides`): `"ask"` = bots sell near the real ask and bid no more than the NPC price; `"bid"` = bots only bid the
   exact NPC price and sell nothing (items priced from NPC guesses, and items other modules tag `bazaar`). Result:
   1,844 market-made products (173 ask-only, 11 bid-only), 353 player-only.
2. **Arbitrage loops the caps missed.** Bids were capped only by a recipe's DIRECT inputs at the bots' asks. Now the
   cap is the cheapest way to MAKE the item: a fixed point relaxed over every crafting/forge recipe (chains, and
   reversible ones like coal <-> coal block), NPC shop prices, and **enchanted-book ladders** (two level-N books
   combine into N+1 in our anvil, so bid(N+1) <= 2 x cost(N)). (Superseded: see the fix pass above — the graph is
   rebuilt when the registry grows and kept current between cycles.)
3. **Two-account price pumping.** Player trades pull a product's anchor; two accounts trading a thin item at 10x
   could drag the bots' BID up and then sell into it. Bids are now capped at the reference price
   (`bazaar.mm-bid-max-ref`, 1.0) and the player VWAP is clamped to ref/3..3x before it pulls anything.
4. **Simulated traders could hand out cheap items.** They filled any player order that beat the bots, so on a
   one-sided product a buy order at NPC+0.1 got filled from thin air. They now trade only INSIDE the bots' fair band
   and only on two-sided products.
5. **Resting orders bypassed the 2,240/hour cap.** A sell offer below the bots' bid was bought by the next re-quote
   with no limit. Those crossings now count toward the owner's hourly allowance (owners at the cap are skipped).
6. **Group pages showed 28 products and silently dropped the rest** (Shards has 321, Miscellaneous 131, Crimson 74,
   Fishing Gear 69, Dungeons 50...). Group pages are paged (prev/next at 45/53); Go Back returns to the right page.
7. **NPC shop ceilings were nearly empty**: the shops module does not register NPC_SHOP recipes. The generator now
   reads `data/shops/shops.json` and bakes 31 more ceilings into `npcBuy`, valuing entries that also take items at
   those items' reference prices (entries priced in other currencies are skipped: a ceiling that ignores part of the
   price would be too LOW). A null `inputs` on an NPC_SHOP recipe would also have thrown in `start()` and stopped
   every market maker: guarded.
8. **Research rules the builder could not wire are wired now**, because the hooks exist: remote `/bz` needs the bank's
   `cookie` key (fails open only if the bank module is absent); **Bazaar Flipper** tiers come from the bank's
   `cshop:bazaar_flipper:<tier>` (-0.125% tax and +7 order slots per tier; each order stores its owner's rate in a new
   `tax_base` column — DB migration v2 — so an offline fill charges the right rate); the mayor tax perk defaults to
   `derpy.quad_taxes` (x4); **profile modes**: Bingo and Stranded cannot open the Bazaar, Ironman may only Instant Buy
   Booster Cookies (claims and cancels always work; wizards refuse up front); the instant-buy cap scales with stack
   size (2,240 = 35 stacks, so 560 for ender pearls; essence up to 71,680); market-maker depth **fades out** as players
   trade a product (research s2.9).
9. Smaller: another module's "View on Bazaar" button bypassed the remote-access rule; claiming an item no module
   registers anymore would mark it claimed into nothing; unsold items from a sacks sale went to the inventory instead
   of back to the sacks; immediate fills on order placement never told the player what they received; the menus
   module's "Bazaar Order Alerts" setting (PDC `skyclone:setting.notify.bazaar_filled`) is honoured; null-rarity
   guards; the per-player rate-limit map is cleared on unload; the `mm-invariants` self-test's NPC-floor branch could
   never run (every bid now has a cap) and was rewritten.

## What works (verified how)
- `bash tools/check.sh bazaar` → **CHECK OK**; `bash tools/check.sh all` → **CHECK OK** (694 files).
- **15 JUnit tests pass** (`plugin/src/test/java/com/skyclone/mod/bazaar/BazaarPureTest.java`, run with plain javac +
  the JUnit 5.11.4 launcher jars from the gradle cache): research tax numbers (1.25% of 1,000 = 12.5; seller gets
  987.5), price parsing, price-time priority across levels with partial fills and maker proceeds net of tax, limit
  walks, the per-player market-maker allowance, self-trade filtering, level aggregation, **20,000 random
  market-maker quotes** that never cross, never exceed an NPC shop price or a recipe cap, never bid under the NPC
  floor, never offer more than their stock; skew direction; mean reversion + restock half-life; one-sided quoting;
  depth fade-out; each maker paying its own tax rate (Flipper); price history; the SHIPPED catalogue JSON (2,197
  products, each in exactly one group of its own category, prices sane, research numbers like Enchanted Diamond NPC
  1,280 survive) and that no two-sided product is priced off a lowball snapshot.
- NOT run live (agents do not start the server). The 10 self-tests and the `/sb bazaar` admin commands below are the
  integrator's live check.

### Money safety (public server)
- One writer: the books live in memory on the main thread; no check-then-act can race.
- Order of every operation: validate → take the player's side (coins via `withdraw`, items removed + **`p.saveData()`
  immediately** so a crash cannot return them while the order exists; sacks → `saveNow`) → match → **queue order rows
  before crediting anyone** (single FIFO DB thread, same queue core's economy uses) → credit. A crash can lose a
  payout, never pay twice.
- Orders store the profile id; claims only pay into that profile. Unique (unstackable) items are never traded here.
- Rate limit per player (`bazaar.action-cooldown-ms`), order slot limit (14, +7 per Flipper tier), quantity and price
  ceilings, sell-offer ceiling at 10x the 7-day average, value cap 1B, self-trades skipped (no wash trading the
  history), master kill switch, profile-mode rules.
- `/sb bazaar audit` and self-test `bazaar.book-integrity` check books vs order index and every counter.

### Market makers (research s2.9) and simulated flow
- One virtual trader per priced product: fair value from the snapshot (`bazaar.mm-price-scale`), mean-reverting random
  walk, pulled toward (clamped) player-trade VWAP, virtual stock with restock half-life and inventory skew; 3 levels
  per side; spread 6–28% by liquidity; depth from reference volume, capped by value, fading as players trade.
- Invariants (unit-tested and self-tested): bid < ask; best bid >= NPC sell price unless an arbitrage cap is lower;
  ask <= NPC shop price (live `NPC_SHOP` recipes, research data and the shops catalogue); **bid <= the cheapest way to
  make the item** (recipes, chains, NPC shops, book ladders) and **<= the reference price**; one-sided products quote
  only their trusted side; per-player 2,240 units/product/hour against bots, crossings included.
- Re-quotes are spread over ticks (a slice per tick). A re-quote that reaches a player's order fills it at the
  player's price and counts toward that player's hourly allowance. Simulated traders (reference volume x
  `bazaar.flow-scale`, <= 64/min/side) fill only player orders that **beat** the market maker and stay inside its fair
  band, on two-sided products only — that is what makes patient orders fill on a quiet server.

### Player features
- `/bz` (`/bazaar`) and the hub NPC `bazaar_agent` at `npc.bazaar`: six tabs (Farming, Mining, Combat, Woods & Fishes,
  Oddities, **Enchantments**), paged groups, **paged** group pages, product page with Buy Instantly / Sell Instantly /
  Create Buy Order / Create Sell Offer, top-7 levels each side, 24h sparkline chart, 7-day average, flip margin, full
  order book view (15 levels each side, live), recipe link, favourites. The SkyBlock Menu links here (menus module).
- Wizards with the genre's presets: amounts (stack / 160 / 1,024 / custom; inventory / +sacks / custom) and prices
  (Same as Top Order, +0.1, 5% of Spread; Same as Best Offer, −0.1, 10% of Spread; custom) and a confirm screen that
  previews instant fills at a better price (marketable orders fill immediately, the rest rests).
- Manage Orders (live-refreshing): fill bar, claim (left), cancel with confirm (right), **flip** a filled buy order
  into a sell offer (shift), Claim All, slots used.
- Sell Inventory Now / Sell Sacks Now with a full review list. Price warnings (2x / 50% of the 7-day average, 5-min
  cooldown), big-purchase confirmation, fill notifications (clickable), "orders ready to claim" on join.
- More hub: Favourites, Recently Viewed, Trade History (DB log), lifetime stats, Market Movers (rising, falling, most
  traded), Settings (6 toggles + confirm threshold), How it works.
- `Features.Bazaar` provided: `isBazaarItem`, `instantBuy` (best ask), `instantSell` (best bid), `open(p, id)` (same
  access rules as `/bz`).

## What is partial / not built
- The Special Bazaar matches the research's Ironman rule (Instant Buy Booster Cookies only); the research marks
  Stranded's version "(?)", so Stranded uses the same list. Change `bazaar.ironman-products` if that turns out wrong.
- ~~Seeding trick not built~~ built in wave 5 (Merchant Guild supply drops). Event shocks cover five default events
  (wave 5 added the Mythological Ritual); others (Volcano Eruption, Dark Auction...) can be added to the dial without code.
- Which of the 2,197 products are live depends entirely on the other modules' items; ~131 odd products sit in
  Oddities -> Miscellaneous. Shelf placement of a few items is heuristic (see report.txt).
- Sell Offers in sacks-only mode and "claim into sacks" depend on `Features.Sacks.add/remove` behaving as documented.
- Market-maker economics (`flow-scale`, `depth-scale`, volatility, fade, event factors) are `// TUNE` — the reference
  volumes come from a ~100k-player server.
- Never run live: menus (incl. the Special Bazaar page), sign/chat input, sacks integration, the NPC and all
  self-tests are untested on a server.

## Commands added (player + admin)
| command | what |
|---|---|
| `/bz [item words]` (`/bazaar`) | open the Bazaar (last category); with words: search, or the product if exact |
| `/bz orders\|history\|settings\|sellinv\|sellsacks\|movers\|favourites\|recent\|help` | jump to that screen |
| `/sb bazaar info` | catalogue/live counts, escrow totals, market-maker P&L, flow, switches |
| `/sb bazaar price <item>` / `book <item>` | prices, reference, MM state / top 8 levels each side |
| `/sb bazaar buy\|sell <player> <item> <n>` | instant trade for a player (RCON-testable) |
| `/sb bazaar order <player> buy\|sell <item> <n> <price>` | place an order for a player |
| `/sb bazaar orders\|claim\|cancel <player> [id]` | inspect (incl. slots, Flipper tier, tax rate, mode block) / claim / cancel |
| `/sb bazaar sellall <player> [inv\|sacks\|both]` | Sell-All for a player |
| `/sb bazaar open <player> [item]` | open the menu for a player |
| `/sb bazaar mm refresh [item] \| reset <item> \| set <item> <fair>` | drive market makers |
| `/sb bazaar flow <item> <sell> <buy>` | push simulated traders now (test that orders fill) |
| `/sb bazaar expire [all]`, `audit`, `search <words>`, `live [filter]` | housekeeping / checks |
| `/sb bazaar shocks` | event shocks: which are active, each product's current multiplier |
| `/sb bazaar seed [list\|now\|off <item>]` | Merchant Guild: status / every material with its offer, sales and skip reason / drop now / withdraw one |

## Dials added
`bazaar.enabled, access-requirement (sblevel:7; a bare number N = SkyBlock Level N, none/off/0 = everyone; fails open only if the sblevel owner is missing), remote-requirement
(cookie, fails open only if the bank module is missing), tax-percent (1.25), mayor-tax-perk (derpy.quad_taxes),
mayor-tax-mult (4), blocked-modes (bingo,stranded), ironman-products (BOOSTER_COOKIE), flipper-1-requirement
(cshop:bazaar_flipper:1), flipper-2-requirement (cshop:bazaar_flipper:2), flipper-tax-step (0.125), flipper-slots (7),
max-orders (14), max-order-amount (71,680), max-instant-amount (2,240 for a 64-stack, scaled by stack size),
max-unit-price (500M), max-offer-value (1B), order-expiry-days (7), warn-buy-multiple (2), warn-sell-fraction (0.5),
warn-cooldown-seconds (300), offer-ceiling-multiple (10), action-cooldown-ms (250), block-self-trade, mm-enabled,
mm-price-scale, mm-refresh-seconds (60), mm-levels (3), mm-step-percent, mm-spread-mult, mm-depth-scale, mm-depth-min,
mm-depth-max, mm-level-max-value, mm-player-hourly-cap (2,240), mm-restock-hours (6), mm-reversion-hours,
mm-volatility, mm-skew (0.05), mm-npc-floor, mm-recipe-caps, mm-player-weight, mm-inventory-hours, mm-bid-max-ref (1.0),
mm-fade-max (0.75), flow-enabled, flow-scale, flow-max-per-minute, partial-notify-seconds, log-keep-days, mm-guild-premium (1),
event-shocks, event-shock-glide-minutes (20), special-modes (ironman,stranded), special-anywhere-modes (stranded),
seed-enabled, seed-interval-minutes (30), seed-price-percent (100), seed-batch-mult (1), seed-max-value (2M), seed-products,
essence-buy-requirement (catacombs:20), essence-buy-exempt (ESSENCE_FOREST), max-order-unstackable (256),
instant-buy-reserve-percent (4)` (all `bazaar.`-prefixed; 66 dials). `blocked-modes` default is now `bingo`.

## Self-tests added
`bazaar.catalogue`, `bazaar.live-products`, `bazaar.tax-and-matching`, `bazaar.mm-invariants` (bid<ask, shop ceiling,
NPC floor, every cap incl. the reference cap, one-sided quoting), `bazaar.no-craft-arbitrage`, `bazaar.book-ladder`
(bid for book N+1 <= two book N), `bazaar.book-integrity`, `bazaar.npc-anchor`, `bazaar.icons-build`,
`bazaar.all-reachable` (every live product sits on a group page; instant cap scales with stack size),
`bazaar.event-shocks`, `bazaar.special-bazaar`, `bazaar.access-gate`, `bazaar.seed-catalogue`, `bazaar.seed-no-arbitrage`,
`bazaar.seed-never-accumulates` (wave 5).

## Requests (things you need from core or another module — the integrator reads this)
1. **shops**: optionally also register NPC shop items as `Recipe.Type.NPC_SHOP` with `COINS:x` inputs. The generator
   bakes `data/shops/shops.json` prices into `products.json`, but only at generation time: if the shops data changes,
   re-run `py -3 tools/gen/bazaar/gen_bazaar.py` (or register the recipes, which the live ceiling follows).
2. Modules that own tradeable materials not in the research snapshot: tag them `bazaar` (they appear in Oddities →
   Miscellaneous; the market maker only bids their NPC price until someone prices them in the generator).
3. Already provided by other modules and now wired through dial defaults: bank `cookie` and
   `cshop:bazaar_flipper:<n>`, mayors `derpy.quad_taxes`, menus' SkyBlock Menu entry and alert setting.
   Self-test `bazaar.npc-anchor` confirms hub `npc.bazaar` live.

## Known risks
- **Economy faucet tuning**: market-maker bids and simulated buyers pay up to reference-game prices (often far above
  NPC sell). Bounded by stock/headroom, restock, per-player hourly caps (crossings included), the reference cap and
  arbitrage caps, but untested at scale; watch `/sb bazaar info` P&L (negative = coins created) and tune
  `mm-depth-scale`, `flow-scale`, `mm-price-scale`, `mm-bid-max-ref`.
- **Arbitrage outside what the caps model**: caps cover crafting/forge recipes, NPC shops (coins and items-at-ref) and
  book ladders. Other conversions a module adds (e.g. an NPC that trades items for items not in shops.json, a
  "salvage" that turns an item back into its inputs) are not modelled.
- **Main-thread disk write per item-escrowing trade**: `p.saveData()` (vanilla player file) runs on every sell offer /
  instant sell so a crash cannot return escrowed items. A small file and rate-limited per player, but synchronous I/O.
- **Never run live**: menus, sign/chat input, sacks integration and the NPC have not been clicked by a real client.

## Playtest fix (2026-10-04, wave-4 loops)
`/bz` within 6 blocks of the hub's `npc.bazaar` counts as clicking the agent (it used to say "Requires the Boon Buff! Visit the Bazaar in the Hub" one block from the agent); away from it the remote rule is unchanged. Verified both. The access gate is the owner's `sblevel:5` default; its refusal names the level, the XP still needed and where SkyBlock XP comes from.
