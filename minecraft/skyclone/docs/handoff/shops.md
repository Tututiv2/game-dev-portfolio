# shops — hand-off

Package `com.skyclone.mod.shops`, entry `ShopsModule`. Every NPC merchant of the reference's shop data, stationed at
island anchors, with the genre's shop menu, selling, buyback, daily limits, discounts, and a Merchant Directory.
Owns only the Island Decorator's ten goods (wave 5: `data/shops/items.json`, three of them playable minigames).

Data is generated, never typed: `py -3 tools/gen/shops/gen_shops.py` reads neu-repo `*_NPC.json` (stock, prices,
skins), core's `vanilla_aliases.json` + crafting's `neu_aliases.json` (id aliases, read-only) and `known_blocks.txt`,
and writes `plugin/src/main/resources/data/shops/shops.json` (76 merchants, 868 stock lines),
`townsfolk.json` (12 talk-only hub NPCs), `external.json` (10 merchants other modules run, for the directory),
`items.json` (the Decorator's goods), `docs/names/shops.md` and `tools/gen/shops/report.txt` (every neu shop
NOT stationed and why, plus every stock id no module ships yet). Our names, anchors and dialogue are the CONFIG
tables at the top of the script; every price comes from neu-repo except the Builder's (see TUNE below).

## What works (verified how)
- `bash tools/check.sh shops` -> **CHECK OK**.
- **14 unit tests pass** (`plugin/src/test/java/com/skyclone/mod/shops/ShopsPureTest.java`, run with a reflective
  runner + the junit-jupiter-api jar because this box has no JUnit launcher; `gradlew test` was not run). They run the
  real `Pricing` and the SHIPPED json: research numbers (640/day, x10 Shopping Spree, 3% Seal discount, 10-item /
  15-minute buyback, UTC-midnight reset), anti-arbitrage floor (3 wheat listed at 7 -> 18 because wheat sells for 6),
  neu prices survive generation (Wheat x3 = 7, Coal x2 = 8, Rookie Axe 12, Arrow 40, Rosetta's helmet 1,050, Squire
  Sword 5,000, Decent Coffee 5,000, Gold Pickaxe = 3 Gold Ingots, Librarian Sharpness I = `ENCHANTMENT_SHARPNESS_1` 30),
  legacy meta ids use `:` (`INK_SACK:3`), the two hub fish merchants merged without duplicates, Builder prices 1-25,
  no gem (real-money) costs, no Rift/Galatea/Lotus islands, no reference proper noun left in any merchant name or line.
- NOT verified live (agents do not start the server). The 9 self-tests below are what to run.

### Playtest fixes (2026-10-03, playtest-newplayer)
- **Shop Trading Options / Buyback had no Go Back.** Both called `m.back(..)` once, OUTSIDE the drawer, and the
  drawer's first line is `menu.chrome()`, which repaints the whole bottom row - and `open()` draws, so the arrow was
  wiped before anyone saw it. `back` now runs inside each drawer after `chrome()`. `shops.menus-build` asserts the
  Go Back slot has a handler after a draw, for every shop's options menu and for buyback. (Rule for any menu: a
  button set outside a drawer that calls `chrome()` does not survive the first draw.)
- **Two "Smooth Sandstone" lines in the Builder's Stone tab.** `@CUT_SANDSTONE` resolves (via core's alias) to the
  legacy id `SANDSTONE:2`, whose reference-game name is "Smooth Sandstone" - pre-1.13 smooth sandstone IS today's
  cut sandstone - beside the real `SMOOTH_SANDSTONE`. Shops does not own item names, so the menu now labels any line
  whose name another line of the same tab shares by the block it stands for ("Cut Sandstone", lore
  `Item name: Smooth Sandstone`), also in Trading Options (`Shops.ambiguousLines` / `lineName`,
  `ShopData.ambiguous`). Same fix covers `@SMOOTH_STONE_SLAB` -> `STEP` "Stone Slab" beside `@STONE_SLAB`.
- **Related, worse: three Builder lines sold a different item.** `@` resolution took core's alias blindly, and some
  aliases are custom items that only borrow the texture: Carved Pumpkin -> `PUMPKIN` (a second Pumpkin line),
  Blue Orchid -> `MOONFLOWER` (a farming collection item), Rose Bush -> `CADUCOUS_STEM`. `Shops.resolve` now accepts
  the alias only when it is made of that material AND is a vanilla spelling of it (core's auto def, id = material,
  or flagged `vanilla`), else sells core's plain def for the material. `Shops.available` also hides a second data
  id collapsing onto an item an earlier line of the same tab sells. New self-test `shops.distinct-lines` checks both
  across every shop and tab; two new unit tests (16 pass).

### Playtest fix (2026-10-03, playtest-loops): every book was "Enchanted Book"
- `shops.distinct-lines` reported 18 lines a player could not tell apart: the Librarian's 8 books, the Star
  Trader's 4, the Pearl Dealer's 2 (enchanting registers every book level as `ENCHANTED_BOOK` named just
  "Enchanted Book", the enchant only in lore), plus Pet Supplies' Farming / Mining Exp Boost sold at two rarities
  under one name. **Root:** shops titled lines by the item's own name and only knew how to disambiguate blocks.
- `Shops.displayName` now appends a **variant** when the def has `extra.enchant` + `extra.level` (how enchanting
  registers every book): "Enchanted Book (Sharpness I)", enchant name from `Sky.items().enchant(..)`. Because every
  shop title, cost line and bought / sold / daily-limit / buyback chat message goes through `displayName`, a book is
  never a bare "Enchanted Book" anywhere in a shop. `ShopMenus.labelled` renames the icon whenever
  `Shops.renamed(..)` (book, or any ambiguous line). Same-named lines that are not blocks are titled by rarity
  ("Farming Exp Boost (Common)" / "(Rare)"). Pure helpers `ShopData.withVariant` / `enchantLabel`.
- `shops.distinct-lines` now also builds each renamed icon and checks its ACTUAL title matches the label (not
  just the computed string). New unit test `enchantedBooksAreTitledByTheirEnchant` (17 pass).
- **Not ours:** the RNG Meter's "Enchanted Book" (Smite VI) is drawn by **slayer**, which uses the book's own name.
  The same root applies there (and anywhere a book def is shown by name: read `extra.enchant`/`extra.level`); a
  cleaner shared fix would be enchanting naming its book defs per level, which is enchanting's call.

### Wave 3 (2026-10-04)
All compile-verified (`bash tools/check.sh shops` -> CHECK OK) and unit-tested (**22 pass**, 5 new); NOT run live.
- **North Stars are spendable.** They were display-only ("can't be spent here yet") because no module exposed a
  currency API - but mayors carries them as a real ITEM (`NORTH_STAR`, given through loot, spent the same way at
  its own emporium). `Shops.currencyItem` maps a currency to a registered item (`north_star` -> `NORTH_STAR`, else
  the upper-cased id if a module ever registers one), and `Trade.buy` pays it exactly like an item cost (inventory,
  then sacks). All 18 Stargazer Eino lines (gift talismans, snow gear, winter books, Ice/Snow minion XII) now trade.
  Cost lines show the item's own name. Pelt lines stay unspendable but sit only on merchants farming runs (yielded).
- **The Essence Broker / Fossil Researcher could never be paid.** Their costs are `ESSENCE_<TYPE>` items, but
  reforges absorbs every essence item a player gains into its pouch (dial `reforges.absorb-essence`, default on), so
  nobody holds the item. `Trade.have/take` now also count and take from `Features.Essence` (inventory, sacks, pouch,
  in that order; all-or-nothing per take). A failed purchase refunds as ITEMS, which reforges re-absorbs, so nothing
  can vanish into a pouch that is not there. (`ESSENCE_FOSSIL` is still unregistered - see Requests.)
- **Requirement gates (shop + line).** `Shop.req` / `Entry.req` (generator tables `SHOP_REQ` / `LINE_REQ`, every
  gate cites research): the Mage / Barbarian Envoys trade only with their own faction (`faction:mage`), Kuudra keys
  need 1k / 3k / 7k / 12k reputation for Hot / Burning / Fiery / Infernal (research 04 §4.3; crimson Request 5).
  Shown as `✔/✖ Requires ...` lines on every gated stock icon, in Merchant Info and on the directory icon; a gated
  line says `Requirements not met!` instead of `Click to trade!`; `Trade.buy` refuses through
  `Sky.requirements().checkOrTell` before anything moves. Selling is never gated. Dial `shops.requirements`.
- **Coin printer removed: the Mage basic Kuudra Key cost 7,600** in the neu snapshot (every other key of both
  envoys matches research), and 4 basic keys craft a Hot Key (400,000). Now 200,000 (generator `PRICE_FIX`, with the
  reason in the entry's new `note`, shown by `/sb shops info`). Unit test `keyLadderIsNotACoinPrinter`.
- **Every stock line is an `NPC_SHOP` recipe** (`ShopRecipes`, bazaar Request 1): ids `shops:<shop>:<n>`, station =
  the merchant's name, inputs `COINS:<effective price, floored, whole coins>` + item/currency costs, `unlock` = the
  line's requirement. So `/recipe <item>` shows "Sold by Farm Merchant" with the cost and gate, an item's "used in"
  lists the trades that take it, the bazaar market makers' live ceiling follows the merchants, and the auction price
  guide can price by them. Registered once in postEnable after stationing; yielded merchants and any merchant whose
  name is already another module's NPC_SHOP station (mining's "Hollows Merchant" menu lists recipes BY station) are
  skipped. Dial `shops.register-recipes` (restart).
- **Known risk 2 fixed: which copy of a unique cost goes.** A trade that upgrades a unique item into another of the
  same category (Green Gift Talisman <- White, Ice minion XII <- XI, Super Cleaver <- Cleaver) takes the MOST
  upgraded copy and carries its reforge, enchants, books, stars, gems and module data onto the result ("Your
  upgrades carried over..."); any other trade takes the PLAINEST copy, so a player's reforged gear is never eaten when
  a bare copy would do. A failed purchase hands back the very stacks it took. Same rule and weights as crafting's
  supercraft (`Trade.upgradeWorth`).
- `/sb shops info` shows each line's requirement, note and whether a currency is spendable (and as which item), plus
  the shop gate and how many recipes were registered.
- Generator: coverage gaps fell from 248 to 81 ids (other modules shipped axes, Minnow Bait, Enchanted Bone Meal,
  drinks...), so `shops.starter-stock-exists` should now pass.

### Wave 5 (2026-10-06)
All compile-verified (`bash tools/check.sh shops` -> CHECK OK) and unit-tested (**28 pass**, 6 new; reflective runner
as before). NOT run live: the 5 new self-tests below are for the integrator.

**Done**
- **Hunting attribute discounts (hunting Request 4).** `Woodchucker` = Builder Discount, `Bloodbat` = Bartender
  Discount: `Shop.discountAttr` (generator `HUNT_DISCOUNT`) and `Trade.attributeDiscount` reads
  `Features.Hunting.attributeLevel(p, "value:<attr>")`. It ADDS to the best accessory discount
  (`Pricing.combinedDiscount`, clamped 0..100; TUNE: the reference does not say whether they stack, at most 3% + 10%).
  Shown in every cost line (`-13%`) and in Merchant Info with the split. Dial `shops.hunting-discounts`. The
  anti-arbitrage floor still applies after it.
- **Accessory discounts** (accessories Request 10: Shady Ring / Crooked Artifact / Seal) were already live; verified
  against accessories' `extra.hook = shops` list (exactly those three). Nothing in pets' or reforges' hand-offs
  belongs to shops (no pet perk or reforge touches NPC prices; their data was searched for NPC/merchant/shop text).
- **The Merchant Directory now holds every merchant.** Merchants whose spot another module's NPC holds (Librarian,
  Alchemist, the two pelt shops, the Hollows Merchant, Gold Forger...) were hidden; they are listed under the owner's
  NPC name ("Surveyor Pruitt") with "Click for directions!". **Merchants other modules run with their own menus** are
  listed from `external.json`: Community Shop (bank, `/cshop`), Pet Sitter, Medal Exchange, Chocolate Shop, Festival
  Candy Stall, Travelling Zoo, Carnival Stall, Pickaxe Smith, Faction Quartermaster, Dungeon Supplies. A click runs
  the owner's PLAYER command (a bare word from data, never player text), so the owner's own access rules apply; a row
  shows only while that command is registered. Dials `shops.directory-external`, `shops.directory-yielded`.
- **Elizabeth / Community Shop placement:** not missing. The bank registers `bank_community_shop` ("Rosamund") on hub
  `npc.community_shop`, which the hub layout has (`HubVillage`). Shops lists it in the directory, and the self-test
  `shops.directory-complete` fails if the hub has the anchor but nobody stands on it.
- **Abiphone (extras Request 5):** a merchant saved in the player's Abiphone (`hasContact(p, "shops_<id>")`) trades
  from `/merchants <id>` and the directory from anywhere - a phone call. Dial `shops.abiphone-remote`.
- **Currencies beyond coins/bits** (`Currencies.java`): item-carried (North Stars), **Motes through `Features.Rift`**
  the moment rift provides it, and a generic **`Features.Currencies`** looked up by name (`long balance(Player,String)`,
  `boolean spend(Player,String,long,String)`, optional `boolean supports(String)`), so pelts light up the day core adds
  that interface and farming provides it - no change here. Service currencies are spent LAST, all-or-nothing, after
  every other cost moved, so they never need a refund. Cost lines show the balance when a service can read it
  (copper through `Features.Garden.copper`).
- **Pelts, honestly:** pelts are real in the game - farming earns them (trapper) and spends them at its own two NPCs on
  the very anchors our surveyor / fur-trader copies would use (we yield). The directory now sends players to those
  NPCs. Our copies trade pelts only once a pelt API exists (above); until then a pelt line says "Pelts can't be spent
  here." plus where they CAN be spent. Reading farming's member key was rejected: `member(p, "farming", X)` with any
  other class makes core CONVERT and REPLACE farming's live object (ProfileServiceImpl.resolve), which would lose
  farming's in-flight writes.
- **Fossil essence (old Request 10) is answered:** reforges registers `ESSENCE_FOSSIL`, mining's excavator gives it,
  the pouch holds it; every Fossil Researcher line trades (self-test `shops.fossil-essence`).
- **Island Decorator goods registered here** (skills Request 16; no module owned them and the Decorator is ours):
  `PARKOUR_CONTROLLER`, `PARKOUR_POINT`, `PARKOUR_TIMES`, `SOCIAL_DISPLAY`, `EGG_HUNT`, `ISLAND_NPC`, `TIC_TAC_TOE`,
  `CONNECT_FOUR` ("Four in a Row"), `ROCK_PAPER_SHEARS` (**"Rock Paper Snips"**), `SHOWCASE_BLOCK`; neu material /
  rarity / skull, our lore, `skill:social:1..10` on the item AND on the Decorator's line (the Social road unlocks
  them "to buy"). **Playable:** Tic Tac Toe and Four in a Row - right-click a player to challenge, they accept by
  clicking the chat line (`/minigame accept <you>`) or clicking you back; both share one board menu, closing it
  forfeits. Rock Paper Snips - right-click to run a tournament for everyone within 30 blocks: each round the LEAST
  picked sign goes through, silent players drop out, the last two play classic rules (60 s cooldown). Nothing is
  wagered or paid out (no coin printer). Rules are pure (`Games.java`, unit-tested). The six placeables are blocks for
  the island (tag `placeable`; the spawn-egg Island NPC deliberately is NOT, or placing it would spawn a polar bear).
- **NPC_SHOP recipes follow the price dials live.** `ShopRecipes` keeps each registered recipe with its line and
  re-prices its `COINS:n` input in place on `shops.price-scale` / `shops.min-markup` change and once 2 s after boot
  (accessories lowers sell caps a tick after postEnable; the floor follows). `/sb shops reprice`.
- **Buyback is a core dupe-scan source** (`addDupeScanSource("shops_buyback")`, wave-5 hook): a unique item sold to a
  merchant lives only in buyback, so the same uuid in an inventory is caught. Dial `shops.buyback-dupe-scan`.
- **Known risk 2 closed:** core's `count`/`remove` only ever look at storage contents, never worn armour, so a worn
  unique cost cannot be taken.
- Report: enchanted books (`ENCHANTMENT_*`, registered by enchanting in code) no longer count as missing; 25 ids left.

**Still left (wave 5)**
- Pelts stay unspendable at OUR copies until `Features.Currencies` exists (Request 4); in play the farming NPCs
  already run both spots.
- The six placeable decorator goods are decoration only: parkour courses / times, social display, egg hunt, island
  NPC dialogue and the showcase's item display need the island module's placed-block tracking (Request 12).
- RPS: a player who closes the round menu cannot reopen it and drops out at the timer.
- 25 stock ids no module registers (Request 13).

### Merchants (76 on 15 islands)
- **Hub, CONTRACT anchors (11):** Farm / Mine / Lumber / Fish Merchant, Weaponsmith, Armorsmith (Rosetta's stock),
  Adventurer, Builder, Bartender, Librarian, Alchemist. Registered in `postEnable` unless another module already stands
  an NPC on that anchor — then we **yield** (enchanting registers the Librarian before us; alchemy may do the same
  for the Alchemist). The Community Shop (`npc.community_shop`) is the bank's, per its brief.
- **Hub, secondary (22):** fletcher, gravedigger, beekeeper, pet supplies, aquarist, tailor, broker, shady dealer,
  hedgewitch, phone vendor, decorator, owl keeper, scroll seller, hermit, snow shoveler, plumber, sly vendor,
  stone merchant, tinkerer, carpenter, wool weaver, pig shop.
- **Other islands (43):** barn 1, mushroom desert 4, park 3, gold mine 2 (Gold Forger = contract anchor), deep caverns 1,
  dwarven mines 4, glacite tunnels 1, crystal hollows 3 (`npc.hollows_merchant` = contract), spider's den 2, the end 2,
  crimson isle 13, backwater bayou 1, jerry's workshop 4, dungeon hub 2.
- **Secondary merchants register only when their island's layout HAS their anchor** (re-checked at `postEnable`, on
  every `IslandBuilt`, and once a minute), so nothing ever piles up at an island spawn and `ui.npc-anchors` stays
  green. Until an anchor exists the merchant is a **travelling merchant**: listed in `/merchants` and tradeable from
  there (on islands the player has `visited:`; the hub always). Dial `shops.remote-unstationed`.
- **Townsfolk:** 12 original talk-only hub NPCs (village gossip that doubles as hints: buyback, daily limits, bank
  interest, fairy souls...) on anchors `npc.lore.1..12`, registered only when the anchor exists.
- Skins: the neu-repo NPC skin textures (same precedent as the bank's NPCs and the materials heads).

### Shop menu (genre layout)
- 6 rows, stock in the inner 7x4, paged (45/53); category tabs on the top row for the Builder (8) and Wool Weaver (5).
- Each item is the real item (real lore) + `Cost` lines (coins with discount tag, bits, item costs, foreign currency),
  `Daily stock: N/640`, then `Click to trade!` / `Shift-Click to buy a stack!` / `Right-Click for more trading options!`.
- **Shop Trading Options**: x1/x5/x10/x32/x64 of the purchase with their cost, plus **Custom Amount** (sign/chat number).
- **Selling:** click any item in your own inventory to sell that whole stack at `ItemDef.npcSell` (reason
  `npc-sell:<ID> xN`); unique items at or above `shops.confirm-rarity` (EPIC) ask first. **Sell Items** box (slot 51):
  28 free slots, live total, one button sells everything sellable; valuables and unsellables stay and are handed back
  on close by core.
- **Buyback** (slot 50): newest sale shown, click to buy it back at exactly its sale price, right-click for all 10;
  sales expire after 15 minutes and on logout; a bought-back sale no longer counts toward today's sell cap.
- Slot 47 info (island, limit, restock timer, your discount, sold today), 48 Merchant Directory (or Go Back), 49 Close.
- Merchant Directory (`/merchants`): every merchant sorted by island, status (in person / travelling, where it will
  stand), stock count; island filter (left/right click), Buyback button; click to trade (if allowed) or browse.

### Rules (research 05 §4 + accessories)
- 640 items per item per merchant per UTC day (`shops.daily-limit`), x10 while the mayor perk named by
  `shops.spree-perk` is active; reset 00:00 UTC.
- Sell cap 500,000,000 coins per player per UTC day.
- Discount: Shady Ring 1% / Crooked Artifact 2% / Seal of the Family 3% (highest only, via `Features.Accessories.has`),
  on coin costs.
- **Anti-arbitrage floor:** a merchant never sells for less than `npcSell x amount x shops.min-markup` — the raw data
  has several printers (Farm Merchant wheat 2.33/each vs a 6-coin sell price, pumpkins 8 vs 10...). `/sb shops info <shop>`
  shows the lifted prices in red.
- Costs paid in order coins -> bits -> items (inventory first, then sacks); every check happens before anything moves,
  and any failure after coins moved refunds in the same tick. Purchases go through `Sky.loot().give(.., NPC_SHOP)`
  (never count for collections), in correctly sized stacks, unique items one by one (own uuid). Inventory space is
  checked first (a big order is trimmed to what fits).
- Per-player spam guard (`shops.click-interval-ms`), plus core's click debounce.
- First click on a merchant: its greeting (once per profile member), then the shop. Every merchant click completes
  the objective `talk_merchant` (progression).

## What is partial / not built
(Updated wave 5. Moved out of this list: fossil essence, recipes registered once at boot, the hidden yielded merchants.)
- **Pelts at our copies** (22 lines on the surveyor + fur trader) trade only through `Features.Currencies` (Request 4).
  Farming runs both spots with its own pelt shops and real pelts, so a player never meets our copies while farming
  stands there.
- **Decorator placeables** are decoration only (Request 12).
- **The faction gate covers EVERY envoy line** (exp bottles, Spore Harvester, Atominizer...), not only the keys: research
  says keys come from your own faction's emissary; the rest is our reading (TUNE: `SHOP_REQ` in the generator). Research
  has nothing newer.
- **25 stock ids are not registered by any module** (was 81; enchanted books now counted right). Unregistered lines
  are hidden at runtime; `/sb shops missing [shop]` lists them live, `tools/gen/shops/report.txt` as of generation.
- **Not stationed (by design, see report.txt):** Anita (farming - in the directory as Medal Exchange), SkyMart +
  Pesthunter (garden), Fear Mongerer / Tyashoi / Oringo / Baker / carnival (mayors events - three in the directory),
  Bingo (no bingo mode), Feast Baker (kernel event), all 30 Rift NPCs (rift, motes), Galatea / Lotus Atoll NPCs, and 8
  neu shops with no island. Elizabeth = the bank's Community Shop (stationed by bank, listed in the directory).
  Gem-priced lines are dropped (no pay-to-win).
- No per-item stock beyond the daily limit (the data has none). Gates exist only where research names one (envoys,
  Kuudra keys) or the Social road does (Decorator).
- The Builder's stock is our design: 207 vanilla blocks in 8 tabs priced `max(tab base, ceil(npcSell x 1.25))`,
  capped at 25 - research gives only "~1-10 coins per block". Marked `tune: true` in the json and `TUNE` in `/sb shops info`.
- Buyback lives in memory (cleared on logout as in the genre; a crash loses unbought sales, never duplicates them).
- Builder / Bartender attribute discount stacking with accessories is TUNE (additive).

## Commands added (player + admin)
| command | what |
|---|---|
| `/merchants [shop]` (`/merchantdirectory`, `/npcshops`) | Merchant Directory; with a shop id opens that shop (trade if allowed, else browse) |
| `/buyback` | recent sales, buy back (dial `shops.buyback-anywhere`) |
| NPC click | the merchant's shop (menu path: directory button in every shop, and the directory itself) |
| `/sb shops list` | every merchant: status (Stationed / Yielded / Pending / Disabled), anchor, resolvable stock |
| `/sb shops info <shop>` | every stock line: resolved id, listed vs effective price, costs, currency mapping, requirement, note, TUNE; shop gate; recipes registered |
| `/sb shops open <player> <shop>` | open a shop for a player (trading) |
| `/sb shops directory <player>` | open the directory for a player |
| `/sb shops buy <player> <shop> <ITEM\|#index> [bundles]` | run a real purchase as the player (RCON-testable) |
| `/sb shops sell <player> <ITEM_ID>` | sell the first stack of that id from the player's inventory |
| `/sb shops limits <player> [reset]` | daily counters / lifetime stats; reset clears today's |
| `/sb shops buyback <player> [index]` | list or execute a buyback |
| `/sb shops missing [shop]` | stock / cost ids no module registers |
| `/sb shops station` | re-check anchors now and place any merchant that can be placed |
| `/sb shops npcs` | stationed/yielded/pending counts, every pending anchor, townsfolk placed |
| `/minigame <accept/deny/forfeit> [player]` | wave 5: answer a Tic Tac Toe / Four in a Row challenge (also the chat line), or forfeit |
| decorator items | wave 5: right-click a player with Tic Tac Toe / Four in a Row; right-click Rock Paper Snips (menu path: buy at the Island Decorator, `/merchants decorator`) |
| `/sb shops external` | wave 5: the merchants other modules run, their command, live or hidden |
| `/sb shops reprice` | wave 5: re-price the NPC_SHOP recipes now |
| `/sb shops discount <player> [shop]` | wave 5: accessory + attribute discount for that player there |
| `/sb shops minigame <player> <other> <TIC_TAC_TOE/CONNECT_FOUR>` | wave 5: send a challenge as the player (RCON-testable) |

## Dials added
`shops.daily-limit` 640 · `shops.spree-multiplier` 10 · `shops.spree-perk` "diaz.shopping_spree" (TUNE: confirm with
mayors) · `shops.sell-cap` 500M · `shops.buyback-size` 10 · `shops.buyback-minutes` 15 · `shops.min-markup` 1.0 ·
`shops.use-sacks` true · `shops.remote-trade` false · `shops.remote-unstationed` true · `shops.confirm-rarity` EPIC ·
`shops.greet-first-time` true · `shops.max-custom-amount` 640 · `shops.click-interval-ms` 120 ·
`shops.discount-shady-ring` 1 · `shops.discount-crooked-artifact` 2 · `shops.discount-seal` 3 · `shops.townsfolk` true
(restart) · `shops.npcs` true (restart) · `shops.price-scale` 1.0 · `shops.selling` true · `shops.buyback-anywhere` true ·
`shops.register-recipes` true (restart; wave 3) · `shops.requirements` true (wave 3) · **wave 5:**
`shops.hunting-discounts` true · `shops.abiphone-remote` true · `shops.directory-external` true ·
`shops.directory-yielded` true · `shops.buyback-dupe-scan` true (restart).

## Self-tests added
`shops.data-valid` (70+ merchants, unique ids/anchors, every line has a cost, no gems, groups exist) ·
`shops.formulas` (640 / 6,400, 3% of 1,000 = 970, wheat floor 18, limit trimming, buyback 10 & 15 min, and the shipped
data's research prices: Wheat x3 = 7, Undead Sword 100, Rosetta helmet 1,050, Zombie Talisman 500) ·
`shops.no-arbitrage` (no resolvable line sells below its NPC sell value; logs how many were lifted) ·
`shops.starter-stock-exists` (every line of the 8 first-hour merchants resolves to a registered item; axes, Minnow Bait
and Enchanted Bone Meal now ship, so it should pass) ·
`shops.every-merchant-has-stock` · `shops.contract-anchors` (in built layouts) · `shops.npcs-stationed` ·
`shops.menus-build` (every shop, browse mode, trading options, sell box, buyback, directory — for a profile-less viewer;
Go Back present after a draw) · `shops.distinct-lines` (no two lines of one tab show the same name; every `@BLOCK`
line sells that block, not a custom item on its texture) · **wave 3:** `shops.currencies-spendable` (no merchant we run
shows a currency nobody can pay; the Stargazer's North Star lines resolve) · `shops.requirements-known` (every gate key
has a resolver - an unknown prefix fails closed, which would make the line unbuyable) · `shops.recipes` (`shops:`
NPC_SHOP recipes registered, every result/input exists, none priced below what merchants pay back, Wheat is "Sold by
Farm Merchant").

**Wave 5:** `shops.hunting-discounts` (exactly Builder/Bartender carry an attribute, 3%+10% = 13% -> 870 of 1,000,
capped at 100, keys exist in hunting's shards.json) · `shops.directory-complete` (external.json valid, Community Shop
listed, someone stands on hub `npc.community_shop`, every yielded and live external merchant listed once with an icon) ·
`shops.decor-items` (ten goods registered, Decorator shows all ten, RPS gated by Social 8 with its ability, board and
RPS rules) · `shops.fossil-essence` (every Fossil Researcher line payable) · `shops.recipe-reprice` (re-pricing is
idempotent, every shops recipe has a readable coin input, buyback source is side-effect free).

Unit tests (`ShopsPureTest`, 28; wave 5 added `attributeDiscountAddsToAccessoryDiscount`,
`builderAndBartenderCarryTheirHuntingDiscount`, `externalMerchantsAreValidAndIncludeTheCommunityShop`,
`decoratorStockIsRegisteredHereAndSocialGated`, `boardGamesFollowTheirRules`, `rockPaperSnipsLeastPickedWins`; was 22): wave 3 added `requirementKeysJoinShopAndLine`, `validateRejectsMalformedRequirement`,
`envoysAreFactionGatedWithResearchRep`, `keyLadderIsNotACoinPrinter`, `starTraderTradesInNorthStars`. Run: javac
ShopData + Pricing + the test against `plugin/build/classpath.txt` + junit-jupiter-api/opentest4j/apiguardian, then a
reflective runner (no JUnit launcher on this box).

Member data key `shops` (`Pricing.Ledger`): `day`, `bought{"shop|ITEM": n}`, `sold`, `met[]`, lifetime totals.

## Requests (things you need from core or another module — the integrator reads this)
1. **hub (builder):** anchors for the 22 secondary hub merchants, each standing on a full block where the text says:
   `npc.fletcher` (archery range by the weaponsmith), `npc.gravedigger` (graveyard), `npc.beekeeper` (village, flower
   house), `npc.pet_supplies` (next to the pet sitter), `npc.aquarist` (hub pond), `npc.tailor` (fashion shop),
   `npc.broker` (bank / auction house), `npc.shady_dealer` (wilderness, near the dark auction), `npc.hedgewitch`
   (crooked hut), `npc.phone_vendor`, `npc.decorator`, `npc.owl_keeper`, `npc.pig_shop` (village), `npc.scroll_seller`
   (foot of the wizard tower), `npc.hermit` (castle ruins), `npc.snow_shoveler` (mountain top), `npc.plumber` (sewer
   entrance), `npc.sly_vendor` (alley near the tavern), `npc.stone_merchant` (beside the blacksmith), `npc.tinkerer` and
   `npc.wool_weaver` (near the builder), `npc.carpenter` (foraging camp); plus **`npc.lore.1` .. `npc.lore.12`** where
   townsfolk would stand in the village. Missing ones are not errors: those merchants trade from `/merchants`.
2. **island builders** (anchor names; same rule): farming — barn `npc.windmill_operator`; mushroom_desert
   `npc.rancher`, `npc.chum_vendor`, `npc.surveyor`, `npc.fur_trader`. foraging — park `npc.merry_norseman`,
   `npc.strategist`, `npc.shaman`. mines — gold_mine `npc.iron_forger`; deep_caverns `npc.blaster`; dwarven_mines
   `npc.lamplighter`, `npc.pickwright`, `npc.foreman`, `npc.mine_cook`. hollows — glacite_tunnels
   `npc.fossil_researcher`; crystal_hollows `npc.gemcutter`, `npc.tribal_trader`. combatzones — spiders_den
   `npc.crystal_mender`, `npc.spider_tamer`; the_end `npc.end_archer`, `npc.pearl_dealer`. crimson — crimson_isle
   `npc.peddler_dragontail`, `npc.peddler_scarleton`, `npc.brewer_scarleton`, `npc.brewer_dragontail`,
   `npc.generator_smith_scarleton`, `npc.generator_smith_dragontail`, `npc.lampwright`, `npc.trophy_keeper`,
   `npc.phone_dealer`, `npc.frantic_engineer`, `npc.devotee`, `npc.mage_envoy`, `npc.barbarian_envoy`. fishing —
   backwater_bayou `npc.scrapper`. mayors — jerrys_workshop `npc.star_trader`, `npc.frost_vendor`, `npc.ice_carver`,
   `npc.frozen_trader`. dungeons — dungeon_hub `npc.dungeon_quartermaster`, `npc.essence_broker`.
3. **Any module that wants to run one of these spots itself** (crimson's faction envoys, fishing's trophy keeper, mines'
   gold forger, alchemy's Alchemist): register your NPC on that anchor in `enable()` (or in a `postEnable` of a module
   listed BEFORE shops) and shops stands down automatically. Registering in a later module's `postEnable` puts two NPCs
   on one spot.
4. **core + farming - Currencies (wave 5: the shops side is BUILT, it looks the interface up by name).** Add to
   `api.feature.Features`:
   `interface Currencies { default long balance(Player p, String id) { return 0; } default boolean spend(Player p, String id, long n, String reason) { return false; } default boolean supports(String id) { return false; } }`
   and have farming provide it for `"pelt"` (its `FarmData.pelts`). Shops' pelt lines start trading with no change
   here. (Previous text, north stars answered in wave 3 via the `NORTH_STAR` item: a narrow `Features.Currencies { long balance(Player, String id); boolean spend(Player, String id, long n, String reason); }`
   (or per-owner methods) so pelts (whoever builds the trapper loop), north stars (mayors), copper (garden: SkyMart is
   theirs, but `Features.Garden` has no spend either) can be spent here. Wire it in `Shops.currencySupported` + `Trade.buy`.
5. **Item owners** — ids shops sell that nobody registers yet (full live list: `/sb shops missing`): alchemy — the
   Bartender/crimson drinks (`CHEAP_COFFEE`, `TEPID_GREEN_TEA`, `PULPOUS_ORANGE_JUICE`, `BITTER_ICE_TEA`, `KNOCKOFF_COLA`,
   `DECENT_COFFEE`, `BLACK_COFFEE`, `HOT_CHOCOLATE`...) and the `*_MIXIN`s; materials — `ENCHANTED_BONE_MEAL`;
   foraging — `ROOKIE_AXE`, `PROMISING_AXE`, `SWEET_AXE`, `EFFICIENT_AXE`; fishing — `MINNOW_BAIT`, Junker baits/sinkers,
   trophy-hunter gear; minions — every `*_GENERATOR_12` / `GENERATOR_UPGRADE_STONE_*`; mining — `SUPERBOOM_TNT`,
   pickaxe chain, `BIOFUEL`; dungeons — Ophelia's line; crimson — kuudra keys etc. **Wave 3:** most of these shipped (81
   left, see report.txt): phones (`ABIPHONE_*`), decorator furniture, `THORNLEAF_SCYTHE`, `SPORE_HARVESTER`,
   `ATOMINIZER`/`CHARMINIZER`, `DUNGEONBREAKER`, `INFINITE_SUPERBOOM_TNT`, `SECRET_TRACKER`, `WINTER_FRAGMENT`...
6. **menus:** (done: FeatureIndex links `merchants`) a "Merchants" entry in the SkyBlock Menu (or Fast Travel) -> `Sky.commands().run(p, "merchants")`.
7. **mayors:** confirm the Shopping Spree perk id; it is the dial `shops.spree-perk` (default `diaz.shopping_spree`).
9. **materials (names):** `SANDSTONE:2` "Smooth Sandstone", `RED_SANDSTONE:2` "Red Smooth Sandstone" and `STEP`
   "Stone Slab" carry the 1.8 names of blocks that are now Cut Sandstone / Cut Red Sandstone / Smooth Stone Slab, next
   to the real modern blocks of those names. Renaming them (display only, ids unchanged) would make the item a buyer
   receives match the label shops now shows. **core (aliases):** `vanilla_aliases.json` maps `BLUE_ORCHID` ->
   `MOONFLOWER`, `ROSE_BUSH` -> `CADUCOUS_STEM`, `CARVED_PUMPKIN` -> `PUMPKIN` first, so vanilla pickups of those
   blocks convert into a collection item / plain pumpkin; shops no longer follows them, but pickups still do.
10. **hollows / mining (wave 3):** `ESSENCE_FOSSIL` - the Fossil Researcher's costs. Register it as an essence item (and
   give fossil essence where the reference does) and those lines trade, pouch included.
11. **crimson (wave 3, answering your Request 5):** the envoys now require `faction:<own>` for every line and
   `faction:<own>:<rep>` for keys 2-5. Your resolver checks rep without membership when a number is given; shops adds the
   membership key itself. If envoys should sell their non-key stock to everyone, drop `SHOP_REQ` and gate per line.
12. **island (wave 5):** the Decorator's placeables are registered here (`data/shops/items.json`, `extra.decor =
   "place"`, tag `placeable`): `PARKOUR_CONTROLLER/POINT/TIMES`, `SOCIAL_DISPLAY`, `EGG_HUNT`, `SHOWCASE_BLOCK`, plus
   `ISLAND_NPC` (spawn egg, not placeable). Their behaviour on the private island (courses + times, top-visitors
   hologram, guest egg hunt, an editable NPC, an item showcase) needs placed-block tracking, which is yours; read the
   item id on BlockPlaceEvent. If you would rather OWN the items, register them before shops (first registration wins)
   and tell us to drop `items.json`.
13. **Item owners, wave 5 (25 left):** alchemy `DR_PAPER`, `SLAYER_ENERGY_DRINK`, `TUTTI_FRUTTI_POISON`,
   `MUSHED_MUSHROOM_MIXIN`, `BLENDED_FISH_MIXIN`, `PIG_BRAIN_MIXIN`; extras/accessories `SEAL_TREAT_BAG`; farming
   `GLOWING_MUSHROOM_CAVE_TRAVEL_SCROLL`, `TRAPPER_DEN_TRAVEL_SCROLL`, `MOBYS_SHEARS`; crimson `THORNLEAF_SCYTHE`,
   `ATOMINIZER`, `CHARMINIZER`, `SPORE_HARVESTER`, `MOODY_GRAPPLESHOT`; dungeons `DUNGEONBREAKER`, `SECRET_TRACKER`,
   `INFINITE_SUPERBOOM_TNT`; weapons `ARROW_SWAPPER`; mining `INFINI_TORCH`; foraging `VIKING_TEAR`; fishing
   `BRONZE_SHIP_ENGINE`; pets/farming `SHINY_ORB`; mayors `GOLDEN_BOUNTY`, `GOLD_GIFT` (a cost of the Star Trader).
14. **extras (answering your Request 5):** done - contact id `shops_<shop id>` makes `/merchants <shop id>` a trade.
8. **core (info):** `NpcServiceImpl.checkAlive` looks a `spawnAt` copy up by its BASE id, so a copy whose base never
   spawns (instanced islands) is reported dead. Shops does not use `spawnAt`, but the island module will hit it.

## Known risks
1. Unregistered stock lines are hidden (25 ids as of wave 5); nothing breaks.
2. ~~Item costs take ANY copy of a unique input~~ - fixed in wave 3; the armour edge is closed too (core only counts
   storage contents).
3. **Anchor races:** a module that registers an NPC on one of these anchors in its own `postEnable` AFTER shops (list
   order) creates a second NPC on the spot. Request 3 says how to avoid it; `/sb shops list` shows who holds each anchor.
4. **Recipe registration volume:** ~800 `shops:` NPC_SHOP recipes join the registry; re-pricing replaces their `inputs`
   lists in place (core's indices key on item ids, which never change). `shops.register-recipes` false switches it off.
5. **Wave 5, never run live:** the shared two-player board menu (one `Menu` opened for both players - core's
   MenuService allows it, untested with a client), the chat click-to-accept line, and the RPS round timer.
6. **Decorator items could be registered twice** if the island module also registers them this wave; first
   registration wins (shops enables in ModuleList order) and core logs the second. Request 12.
7. `Features.Currencies` is looked up by NAME: if core adds it with a different shape, shops logs one warning and pelts
   stay unspendable (nothing breaks).

## Fix 2026-10-06 (integrator follow-up from crimson K17): envoy key discount
- The Mage and Barbarian Envoys are the reference's Emissaries, so their five Leviathan key lines now take the same
  coin discount as crimson's quartermaster: **-5/10/15/20% at 1k/3k/7k/12k reputation** with the envoy's faction.
- Data (`gen_shops.py`, regenerated; it reproduced shops.json byte-for-byte first): shop field `emissaryFaction`
  (mage / barbarian), and the flag `"emissary": true` on the 10 key lines.
- Reputation is read through crimson's requirement key `faction:<id>:<rep>`, so there is no crimson import.
  `Pricing.emissaryPercent` walks the thresholds from high to low. The discount comes off the coin price first; the
  accessory/attribute discount and the anti-arbitrage floor then apply as before. Cost lines show
  "incl. -N% faction reputation discount".
- Dial `shops.emissary-discount` "1000:5/3000:10/7000:15/12000:20" (same format as `crimson.emissary-discount`;
  empty = no discount).
- JUnit `ShopsPureTest.envoyKeysTakeTheEmissaryDiscount`. `bash tools/check.sh shops` CHECK OK.
