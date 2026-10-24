# 05 — Economy, Living-World Calendar, Mayors & Profile Types

> Research reference for the SkyBlock clone. Scope: coins (purse/bank/interest/death loss),
> player trading, Bazaar, Auction House, NPC shops, Dark/Shen's/Stonks auctions, Booster Cookie,
> bits, fame and the Community Shop, the SkyBlock calendar and every recurring event, the
> mayor/minister election, co-op profiles and the restricted profile types (Ironman, Stranded, Bingo).
>
> **Sourcing (gathered 2026-10-02).** The official wiki (wiki.hypixel.net) was **shut down in July 2026**
> (Hypixel staff thread "End of the Official Hypixel Wiki", July 2026; it now 302-redirects to that thread).
> The Fandom wiki returns HTTP 402 to fetchers. Numbers below come from the community wiki at
> `hypixelskyblock.minecraft.wiki` (fetched today), the **live keyless Hypixel API**
> (`/v2/resources/skyblock/election`, `/v2/skyblock/bazaar`, sampled today: current SkyBlock year **517**),
> and prior knowledge. Anything not confirmed by a fetch today and that I am not confident of is marked **(?)**.
> Hypixel names are used as-is; renaming for copyright happens downstream.
>
> **Conventions.** "SB" = SkyBlock (in-game) time; "real" = wall-clock time. "Purse" = coins carried;
> "Bank" = coins stored. Rarity names: COMMON < UNCOMMON < RARE < EPIC < LEGENDARY < MYTHIC < DIVINE < SPECIAL.

---

## 0. Overview — the shape of the economy

SkyBlock is a **single currency (coins)** economy with three secondary currencies that do not trade
directly for coins (bits, gems, event currencies like Jacob's Tickets/medals/candy/copper/north stars).
Coins enter the world from **NPC faucets** and leave through **NPC sinks**; everything between players
(Bazaar, Auction House, /trade) is a **transfer** that the server taxes, which makes the player markets
themselves a sink.

```
           FAUCETS (coins created)                         SINKS (coins destroyed)
  NPC sell (by far #1) -------------------+       +---- NPC shop purchases (incl. 640/day limits)
  Mob coin drops, Scavenger, Bank interest |       |---- Bazaar tax 1.0-1.25% (x4 under Derpy)
  Daily Coins Allowance 10k-50k            |       |---- AH listing fee 1-5% + duration fee + 1% claim tax
  Skill/collection/quest/event rewards     v       v---- Slayer quest fees, reforges, forge, upgrades
                                    +---------------+    Bank account upgrades (5M .. 200M)
                                    |  PLAYER COINS |    Dark/Shen's/Stonks auctions, Zoo pets
                                    | purse + bank  |    Death: lose 50% of purse
                                    +---------------+    Dungeon chests, Kuudra, essence costs...
                                       ^         ^
                     transfers (taxed) |         | transfers (taxed)
                    Bazaar order book  |         | Auction House (auction + BIN)   /trade (untaxed, capped/day)
```

The **calendar** is a 5-day-4-hour real-time year that schedules everything: bank interest
(4×/year), the election (1×/year), festivals (Spooky, Jerry, New Year, Zoo ×2, Jacob's every real hour,
Dark Auction every real hour) — and the **mayor** chosen each year rewrites economic constants
(Derpy ×4 taxes, Diaz ×10 NPC buy limits, Aatrox ½ slayer cost, Paul 20% cheaper dungeon chests,
Finnegan wider contest brackets, Foxy/Marina/Cole extra events). So the calendar and the election are
the **live-ops layer of the economy**: they are how the game moves prices without patches.

### Key numbers at a glance

| Thing | Value |
|---|---|
| SB year (real) | **124 h = 5 d 4 h** (446,400 s) |
| SB month (real) | 10 h 20 min (37,200 s); 31 SB days |
| SB day (real) | **20 min** (1,200 s) |
| SB hour (real) | 50 s |
| SB epoch | Year 1, Early Spring 1, 00:00 = **Unix 1560275700** (2019-06-11 17:55:00 UTC) |
| Death coin loss | **50 % of purse** (bank untouched) |
| Bank interest | every SB season (3 months = **31 real h**), banded/regressive, capped per tier |
| Bazaar tax | **1.25 %** on seller proceeds → 1.125 % / 1.0 % with Bazaar Flipper I/II |
| Bazaar orders | **14** concurrent (+7 per Flipper tier, max 28) |
| AH BIN listing fee | 1 % (<10M) / 2 % (10M–100M) / 2.5 % (≥100M) |
| AH auction listing fee | 5 % of starting bid + duration fee |
| AH claim tax | 1 % on collections > 1M, never pushes payout below 1M |
| AH active listings | 14 (+3 per co-op member, max 35) |
| NPC daily buy limit | **640 per item per merchant** (6,400 under Diaz), resets 00:00 UTC |
| NPC daily sell cap | **500,000,000 coins/day**, resets 00:00 UTC |
| /trade daily coin cap | 50M (<SB Lvl 50) / 1B (Lvl 50+) / 10B (Lvl 100+) |
| Booster Cookie | 4 real days, 325 gems, 4,800 bits × fame multiplier |
| Election | 1/year; 5 candidates; winner = Mayor (all perks), 2nd = Minister (1 perk) |

---

## 1. Coins

### 1.1 Purse vs Bank

| Property | Purse | Bank (co-op/main) | Personal bank (co-op member) |
|---|---|---|---|
| Where coins live | On the player (scoreboard "Purse: N"; "Piggy: N" if a Piggy Bank accessory is held) | Profile-wide account | Per-member sub-account inside a co-op profile |
| Shared in co-op? | No — each member has their own purse | **Yes** — all members deposit/withdraw | No — only that member |
| Cap | No documented hard cap (stored as a 64-bit double in practice (?)) | Tier max balance (50M → 60B, §1.2) | **20 % (1/5) of the main bank's capacity** |
| Lost on death? | **Yes, 50 %** (see §1.5) | **Never** | Never |
| Earns interest? | No | Yes (§1.3) | Yes, scaled; wiki cites "10 % interest" (?) — treat as 1/5 of main-bank scale (?) |
| Spent directly by | NPC shops, AH bids/BIN, Bazaar, trade, fees, Dark Auction (purse only) | Nothing — must be withdrawn first | Nothing — must be withdrawn first |
| Access | Always | Banker NPC (Hub bank building); remote access via cookie/Abiphone (?) | Same as bank |

Design consequence: **purse = risk, bank = safety + slow growth**. Every coin the player carries into
combat is half-exposed; this is the main reason the bank and the cookie's "keep coins on death" matter.

### 1.2 Bank account tiers

Upgrades are bought at the Banker. Each tier needs coins + Enchanted Gold Blocks + Gold collection +
a Museum milestone (from the community wiki, 2026):

| Tier | Max balance | Upgrade cost | Requirements | Max interest / season |
|---|---|---|---|---|
| Starter | 50,000,000 | — (default) | — | 250,000 |
| Gold | 100,000,000 | 5,000,000 coins + 1 Enchanted Gold Block | Gold Collection 100K | 300,000 |
| Deluxe | 250,000,000 | 10,000,000 coins + 5 Enchanted Gold Block | Gold 250K, Museum Milestone 3 | 350,000 |
| Super Deluxe | 500,000,000 | 25,000,000 coins + 20 Enchanted Gold Block | Gold 500K, Museum Milestone 6 | 390,000 |
| Premier | 1,000,000,000 | 50,000,000 coins + 50 Enchanted Gold Block | Gold 1M, Museum Milestone 10 | 500,000 |
| Luxurious | 6,000,000,000 | 100,000,000 coins + 100 Enchanted Gold Block | Gold 2M, Museum Milestone 14 | 1,000,000 |
| Palatial | 60,000,000,000 | 200,000,000 coins + 200 Enchanted Gold Block | Gold 3M, Museum Milestone 18 | 1,500,000 |

> Historical note: older values (pre-2023 (?)) were Starter 10M / Gold 20M / Deluxe 50M / Super Deluxe 100M /
> Premier 200M. Some third-party pages still quote those. The table above is current.
> "Gold collection" requirement text: the wiki lists the collection thresholds as 100K/250K/500K/1M/2M/3M —
> these sit above the top Gold collection *tier* thresholds I recall, so they may be raw collected-count
> gates rather than tier unlocks (?).
> For the clone, treat them as *collection count* gates and tune to your own collection curve.

### 1.3 Interest brackets (regressive)

Interest is **banded like income tax in reverse**: the first 10M earns the most, every higher band
earns less, and each tier only unlocks bands up to its cap. Paid on the bank balance at the moment of
payout (not an average) (?).

| Band (balance range) | Rate | Coins from full band | Unlocked at tier |
|---|---|---|---|
| 0 – 10M | 2 % | 200,000 | Starter |
| 10M – 15M | 1 % | 50,000 | Starter (Starter stops at 15M) |
| 15M – 20M | 1 % | 50,000 | Gold (Gold's 10–20M band is a single 1 % band) |
| 20M – 30M | 0.5 % | 50,000 | Deluxe |
| 30M – 50M | 0.2 % | 40,000 | Super Deluxe |
| 50M – 160M | 0.1 % | 110,000 | Premier |
| 160M – 5.16B | 0.01 % | 500,000 | Luxurious |
| 5.16B – 55.16B | 0.001 % | 500,000 | Palatial |

Cumulative caps: Starter 250k · Gold 300k · Deluxe 350k · Super Deluxe 390k · Premier 500k ·
Luxurious 1.0M · Palatial 1.5M per season — i.e. ×4 per SB year (every 124 real h).

**Museum bonus:** each Museum milestone adds +2 % to interest earned ("up to 72 % more" per wiki = 36
milestones (?); one other source says +60 % at milestone 30). Apply as a multiplier on the computed payout.

**Timing:** paid at the **start of each SB season** — Early Spring 1, Early Summer 1, Early Autumn 1,
Early Winter 1 — i.e. every 3 SB months = **31 real hours**. Players do not need to be online; the
payout is credited to the bank and logged in the bank's transaction history ("Interest: +N coins").

```
interest(balance, tier, museumMilestones):
  total = 0
  for band in BANDS where band.unlockTier <= tier:
      covered = clamp(balance - band.lo, 0, band.hi - band.lo)
      total += covered * band.rate
  return floor(total * (1 + 0.02 * museumMilestones))
```

### 1.4 Bank UI and rules (what the player sees)

| Element | Behaviour |
|---|---|
| Banker NPC (Hub, bank building) | Opens "Bank" GUI: Deposit, Withdraw, Recent transactions, Information (current tier, cap, next interest ETA), Upgrade account |
| Deposit options | "Your whole purse", "Half your purse", "Specific amount" (sign input) |
| Withdraw options | "Everything", "Half", "20%", "Specific amount" |
| Transaction log | Last ~10 entries: who (co-op member name), +/−, amount, time ago; interest rows show "Bank Interest" |
| Co-op | Every member sees and can withdraw the shared balance — the classic co-op scam vector (a member empties the bank and leaves). Personal banks exist to mitigate this |
| Over-cap deposit | Refused / truncated to the remaining capacity |
| Interest ETA | "Interest in: Xh Ym" countdown to next season start |

### 1.5 Death coin loss

| Rule | Value |
|---|---|
| Base loss | **50 % of purse** on death |
| No loss when dying on | Private Island (incl. void falls), Dungeons, Trials of Fire (also: Kuudra/rift/Garden? — Rift uses its own currency (?)) |
| Booster Cookie active | **0 % loss** |
| Piggy Bank accessory | Absorbs the loss when it would be **> 20,000 coins**; degrades Piggy → Cracked → Broken, repaired for coins (?) |
| Legendary Phoenix pet | 100 % protection |
| "Bank" enchant V on 2 armor pieces | Fully negates (lower tiers partial) (?) |
| Wolf Slayer level 8 | −5 % loss (i.e. lose 45 %) |
| Bank coins | Never lost |

Design note for the clone: death loss is the **single most important reason to use the bank** and the
**main reason the Booster Cookie is valuable**. It is a large coin sink on new players and nearly zero on
veterans (who carry little and run cookies). Keep it.

### 1.6 Coin sources (faucets) — full list

| Source | Typical scale | Notes |
|---|---|---|
| **NPC selling** | dominant faucet | Every sellable item has a fixed NPC sell price; capped at 500M coins sold/day/player. Enchanted items sell for 160× base, etc. |
| Mob coin drops | 1–1,000s per kill | Scaled by Scavenger enchant, Bestiary, pets, Midas gear |
| Minion output + hoppers | passive | Budget Hopper sells at 50 %, Enchanted Hopper at 90 % of NPC price (?) — minions selling automatically to NPC |
| Bank interest | ≤ 250k–1.5M / 31 h | §1.3 |
| Daily Coins Allowance | 10,000–50,000 / day | Profile upgrade I–V (Community Shop, free, 5–25 day build times) |
| Skill level-up rewards | up to 1,000,000 per level | Each skill level grants coins |
| Talisman/Ring/Artifact of Coins; Emerald Ring/Artifact | +5 / +20 coins per minute (Emerald Ring/Artifact) | Passive accessories |
| Quests, festival rewards, gifts | variable | Spooky leaderboard up to 1M, Jerry gifts, etc. |
| Dungeon/Kuudra/slayer reward chests | variable | Coins sometimes inside, but chests mostly cost coins |
| Diana's Mythological creatures | variable | Coin drops during the Diana mayor term |
| Scorpius "Bribe" | 50,000–1,000,000 | Paid to voters if Scorpius wins |
| Freshly-Minted Coins | — | From Stonks stock exchange (server-wide limit) |
| Pets/abilities (e.g. Midas/Scavenger effects) | — | Multipliers on mob coins |

### 1.7 Coin sinks — full list

| Sink | Scale | Notes |
|---|---|---|
| NPC shop purchases | huge in aggregate | Bounded by the 640/day/item limit |
| Bazaar tax | 1.0–1.25 % of every seller's proceeds | ×4 under Derpy (?) |
| AH listing fees | 1–5 % + duration fee | Non-refundable even if cancelled/unsold |
| AH claim tax | 1 % over 1M | Capped so payout ≥ 1M |
| Death loss | 50 % purse | §1.5 |
| Slayer quest start fees | T1 2,000 · T2 7,500 · T3 20,000 · T4 50,000 · T5 100,000 (?) | Halved by Aatrox's SLASHED Pricing |
| Reforging (Blacksmith) | 250 – 5,000,000+ by rarity | Reforge stones also cost coins to apply |
| Forge (Dwarven Mines) | coin component per recipe | Cole's Molten Forge reduces time only |
| Dungeon reward chests | 0 – ~4M per chest (Paul: −20 %) | Plus Kismet Feathers to reroll |
| Kuudra paid chests | keys cost coins + materials | |
| Bank upgrades | 5M → 200M | §1.2 |
| Minion upgrades/crafts | coin component for high tiers | |
| Dark Auction / Shen's / Stonks bids | 100k – 100M+ per item | §5 |
| Traveling Zoo pets | 10,000 → 10,000,000 + items | §6.6 |
| Pet leveling at Kat, pet items, Fann training | coins | Kat upgrades rarity for coins + materials |
| Gemstone slot unlocks, enrichments, recombobulation applications | coins | |
| Carnival, Spooky shop? | event currency, not coins | |
| Account upgrades (gems/free) | **no coins** | |
| Cosmetic/QoL purchases | coins | e.g. New Year Cake Bag 250,000 |

### 1.8 Player-to-player trading (/trade)

| Rule | Value |
|---|---|
| Command | `/trade <player>`; right-click (sneak+click (?)) a player also offers a trade |
| Max distance | **9 blocks** (same server instance) |
| Item slots per side | **16** (was 12 before July 2020) |
| Coins in trade | via a "Coin transaction" button → sign input or presets (e.g. 1k/100k/1M/10M/half purse (?)); coins come from **purse** |
| Daily coin limit | **50,000,000** under SkyBlock Level 50; **1,000,000,000** at Level 50+; **10,000,000,000** at Level 100+ |
| Untradeable | Soulbound items; Co-op Soulbound (only within the co-op); Sacks; (anything with `soulbound` NBT) |
| Restricted profiles | Ironman, Stranded, Bingo cannot trade outside their co-op |
| Accept flow | Both press "Accept"; **any change by either side resets both accepts**; after a change, the accept button is locked for a short cooldown (3 s (?)) so a last-second swap cannot be clicked through |
| Warnings | Trades containing high coin values display the amount in the confirm button lore; a large disparity / first-time partner may prompt an extra confirmation (?) |
| Request expiry | ~60 s (?) |
| Tax | **None** (trading is a pure transfer — which is why the daily cap exists) |

**Safety rules (what Hypixel enforces + what a clone must enforce):**

1. **Reset-on-change**: any slot or coin change un-accepts both sides; re-accept only after cooldown.
2. **Atomic commit**: the swap is a single transaction; both inventories must still contain the offered
   items and have room for what they receive at commit time (re-validate, don't trust the GUI state).
3. **Display the exact item**: show full lore including stars, reforge, enchants, recomb, pet level — the
   classic scam is a look-alike item (e.g. a reforged/renamed common with a rare's display name). Clones
   should forbid custom item renames entirely or show the **true item ID** under the name.
4. **Coin readout in words** ("12,500,000 coins (12.5M)") to defeat the "1.25M vs 12.5M" decimal scam.
5. **Daily coin cap** to slow real-money-trading (RMT) and stolen-account liquidation.
6. **Trade log** retained server-side (both UUIDs, item UUIDs, coins, timestamp) for staff restores.
7. No trading while in combat / dungeons / during server shutdown countdown.

---

## 2. Bazaar

The Bazaar is a **commodity exchange**: fungible, stackable, non-unique items (enchanted materials,
gemstones, essences, enchanted books, event drops, booster cookies...) traded through a per-product
**order book**. Non-fungible items (armor, weapons with reforges/enchants, pets) go to the Auction House.

### 2.1 Access

| Rule | Value |
|---|---|
| Unlock | **SkyBlock Level 7** |
| Physical access | Bazaar building in the Hub (Bazaar Agent NPCs) |
| Remote | `/bazaar` / `/bz` anywhere **with an active Booster Cookie** |
| Restricted | Ironman: only instant-buy Booster Cookies; Stranded: separate "Special Bazaar" (?); Bingo: disabled |

### 2.2 Order types

| Type | Who it's for | Mechanic | Fee |
|---|---|---|---|
| **Instant Buy** | buyer, impatient | Fills immediately against the **lowest Sell Offers**, walking up the book | none |
| **Instant Sell** | seller, impatient | Fills immediately against the **highest Buy Orders**, walking down the book | **tax** on proceeds |
| **Buy Order** | buyer, patient | Coins escrowed now; filled when sellers instant-sell into it | none (coins refunded on cancel) |
| **Sell Offer** | seller, patient | Items escrowed now; filled when buyers instant-buy from it | **tax** on proceeds |

The **spread** = lowest sell offer − highest buy order. Patient players capture the spread ("bazaar
flipping": place a buy order at top+0.1, then a sell offer at bottom−0.1).

### 2.3 Limits and numbers

| Parameter | Value |
|---|---|
| Max concurrent orders + offers | **14**; +7 with Bazaar Flipper I, +14 with II → **28** max |
| Max quantity per buy order | **71,680** of a stackable (= 1,120 stacks of 64); **256** for unstackables |
| Buy order presets | 64 / 160 / 1,024 (stackables); 1 / 4 / 64 (unstackables); or custom |
| Instant buy max per click | 71,680 for essence; **2,240** most items; 560 ender pearls; 35 unstackables (wiki) |
| Instant buy quote | Charged up-front at **4 % above** the current cheapest sell offer; any difference refunded after fill |
| Max unit price (buy order) | **500,000,000** coins per item |
| Max value on sell offer at once | **1,000,000,000** coins worth of items |
| Price tick | **0.1 coin** (prices are stored to one decimal) |
| Order expiry | **7 days** (unfilled remainder refunded / returned to claim) (wiki; older behaviour was no expiry (?)) |
| Tax | **1.25 %** of proceeds on Instant Sell and on filled Sell Offers; −0.125 % per Bazaar Flipper tier → **1.0 %**; ×4 under Derpy (?) |
| Price-protection reference | **7-day average price** of the product |
| Instant-buy warning | Confirm prompt if price > **2×** the 7-day average (prompt repeats at most every 5 min) |
| Instant-sell warning | Confirm prompt if price < **50 %** of the 7-day average (5-min cooldown; skipped for "sell inventory/sacks") |
| Sell offer ceiling | Cannot list above **10×** the 7-day average |

### 2.4 Matching / fill priority

- **Price–time priority.** Best price first; within the same price, **oldest order first (FIFO)**.
- Instant Buy consumes the cheapest sell offers in ascending price order; Instant Sell consumes the
  highest buy orders in descending price order. A large instant order can walk several price levels.
- An order can be **partially filled**; the filled part sits in the order as "claimable" until the owner
  opens *Manage Orders* and claims it (items to inventory/sacks, coins to purse).
- **Orders never cross.** A Buy Order priced at or above the lowest Sell Offer is not allowed to sit on
  the book — the GUI's "Top Order +0.1" helper places it at best bid + 0.1, below the ask. (Whether a
  crossing limit order auto-executes instantly is unclear (?) — design the clone to reject or convert it
  to an instant buy.)
- Cancelling: buy orders refund the **unfilled coins** (plus claim filled items); sell offers return
  the **unsold items** (plus claim coins). No fee on cancel.
- Flipping an order: from Manage Orders a filled buy order can be turned straight into a sell offer
  ("Flip order") (?).

### 2.5 Categories

Five top tabs, each with ~10–20 product groups (sub-pages hold tiered variants, e.g.
Wheat → Wheat, Enchanted Wheat, Hay Bale, Enchanted Hay Bale, Tightly-Tied Hay Bale):

| Tab | Contents |
|---|---|
| **Farming** | crops & enchanted crops, animal drops, mushrooms, Garden items (compost, etc.) |
| **Mining** | ores, enchanted ores/blocks, gemstones (rough→perfect), Mithril, Titanium, glacite, refined minerals |
| **Combat** | mob drops (rotten flesh, bone, string, spider eye, ender pearl, blaze, magma cream, ghast tear), slayer drops, essences (?) |
| **Woods & Fishes** | logs & enchanted logs, fish & sea creature drops, shark fins |
| **Oddities** | booster cookies, enchanted books, recombobulators, kismet feathers, stock items, event drops, essences, misc |

Product count: the live API returns **~1,400+ product IDs (?)** (the fetch summariser said "100+"; the real
list is much longer — every enchant book level is its own product, e.g. `ENCHANTMENT_SHARPNESS_6`).

### 2.6 NPC prices as a floor (and ceiling)

- **Floor:** every bazaar item that NPCs buy has an NPC sell price. If the best Buy Order drops below it,
  nobody sells into the Bazaar — they sell to an NPC instead. So **max(best buy order) ≥ NPC sell price**
  in steady state for any item with real supply. Arbitrage bots instantly buy below NPC and dump to NPC,
  enforcing it (bounded by the 500M/day NPC sell cap).
- **Ceiling:** items that an NPC *sells* (e.g. some raw materials, glass, sand) cannot sustainably trade
  above the NPC buy price + effort, bounded by the 640/day NPC buy limit (6,400 under Diaz).
- **Minion economics anchor:** minion output is valued at max(Bazaar sell, NPC sell), which ties
  passive income to the Bazaar.

### 2.7 Bazaar GUIs (what the player sees)

1. **Main** (`/bz`): 5 category tabs down the left (Farming, Mining, Combat, Woods & Fishes, Oddities),
   product group icons in the grid, a **search** (sign input), "Manage Orders" (book & quill), "Sell
   inventory now" (chest — sells everything sellable to buy orders), "Sell sacks now", and
   "Bazaar settings".
2. **Product group** → list of tiers (each with buy/sell price lore).
3. **Product page**: Buy Instantly / Sell Instantly / Create Buy Order / Create Sell Offer, with the top
   5–7 levels of each side of the order book in lore ("1,280x @ 4.2 coins · 3 orders"), and the
   last-hour volume (?).
4. **Create Buy Order** → pick amount (presets / custom) → pick price: "Same as Top Order",
   "Top Order +0.1", "5 % of spread", "Custom price" → confirm (shows total and tax note).
5. **Manage Orders**: each order shows `BUY/SELL`, item, amount, filled %, unit price, "claim" state,
   expiry; click to claim or cancel.

### 2.8 Live API shape (sampled 2026-10-02)

`GET https://api.hypixel.net/v2/skyblock/bazaar` (keyless):

```json
{
  "success": true,
  "lastUpdated": 1790930709552,
  "products": {
    "FACTION_RABBIT_WALKER": {
      "product_id": "FACTION_RABBIT_WALKER",
      "sell_summary": [ {"amount": 1, "pricePerUnit": 500000.1, "orders": 1}, {"amount": 1, "pricePerUnit": 500000.0, "orders": 1} ],
      "buy_summary":  [ {"amount": 1, "pricePerUnit": 2499995.7, "orders": 1}, {"amount": 2, "pricePerUnit": 2499995.8, "orders": 1} ],
      "quick_status": {
        "productId": "FACTION_RABBIT_WALKER",
        "sellPrice": 500000.05, "sellVolume": 68, "sellMovingWeek": 0, "sellOrders": 0,
        "buyPrice": 2499995.7,  "buyVolume": 0,  "buyMovingWeek": 0,  "buyOrders": 0
      }
    }
  }
}
```

(The `*MovingWeek`/`*Orders`/`buyVolume` numbers in that example are placeholders — the sample only
returned `sellPrice` and `sellVolume` values for that product; the field set is real.)

**Gotcha — the naming is from the *instant* trader's perspective:**

| API field | Actually contains | Sorted |
|---|---|---|
| `sell_summary` | **Buy orders** (what you get if you *instant-sell*) | price **descending** |
| `buy_summary` | **Sell offers** (what you pay if you *instant-buy*) | price **ascending** |
| `quick_status.sellPrice` | weighted top-of-book of buy orders (≈ instant-sell price) | — |
| `quick_status.buyPrice` | weighted top-of-book of sell offers (≈ instant-buy price) | — |
| `sellVolume` / `buyVolume` | total items across all buy orders / sell offers | — |
| `sellMovingWeek` / `buyMovingWeek` | items traded on that side over the trailing 7 days | — |
| `sellOrders` / `buyOrders` | count of orders on that side | — |

Summaries are aggregated by price level (top ~30 levels (?)). The example shows a 5× spread on a thin
product — exactly the case a small-server clone must design for (§2.9).

### 2.9 Design note — seeding a Bazaar on a small server (NPC market makers)

With 10–200 players, most order books would be empty or absurd (like the 5× spread above). Fix with
**server-owned market-maker (MM) bots** that are ordinary order-book participants:

| MM parameter | Suggested rule |
|---|---|
| Fair value `F` | Start from the item's **recipe value** (sum of inputs × craft multiplier) or a designer-set anchor; floor at NPC sell price × 1.05; then drift `F` toward the 24h VWAP of *player* trades with half-life ~6 h |
| Quotes | Post a bid at `F × (1 − s/2)` and an ask at `F × (1 + s/2)`; spread `s` = 6–12 % for commodities, 15–30 % for rare event items |
| Depth | Quote `d` units per side, where `d` ≈ expected 1-hour player volume (e.g. 640 for common mats) |
| Inventory band | MM holds an inventory target `I*`; if inventory > I*, skew both quotes down (sell more), if < I*, skew up. Skew = `k × (I − I*)/I*`, k ≈ 0.05 |
| Bid floor | **Never bid below NPC sell price** — the floor is then visible on the book |
| Ask ceiling | For items with an NPC seller, never ask above NPC buy price × 1.1 |
| Rate limit | Max fill per player per product per hour (e.g. 2,240) so a single player can't drain the MM |
| Sink alignment | MM pays tax like everyone else; MM coin P&L is a **visible faucet/sink** metric — if it bleeds, widen the spread |
| Fade-out | As player volume on a product rises, MM depth shrinks (e.g. `d = max(d_min, d0 × (1 − playerShare))`), so real players set prices on busy items |
| Craftable arbitrage | MM fair value of a crafted product ≥ inputs × ratio, preventing infinite arb loops between tiers |
| Event shocks | Scripted MM adjustments on event days (e.g. Jerry gifts flood in → MM lowers `F` gradually rather than collapsing) |

Additional seeding tricks: a "Merchant Guild" NPC that posts standing buy orders at NPC-sell+1 % for
every farmable (guaranteed liquidity for new players), and periodic **NPC sell offers for
progression-gating materials** to keep early game moving.

---

## 3. Auction House

For **unique / non-stackable** items (gear, pets, accessories, cosmetics, minions with upgrades…).

### 3.1 Access

| Rule | Value |
|---|---|
| Location | Auction House building in the Hub (Auction Master / Auction Agents NPCs) |
| Remote | `/ah` anywhere with an active Booster Cookie |
| Unlock | SkyBlock Level requirement — likely SB Level 6 (?) (exact gate not confirmed) |
| Ironman | **Cosmetic-only** Auction House (buy cosmetics only), added Oct 2025 |
| Bingo / Stranded | Disabled |

### 3.2 Auction vs BIN

| | Auction (bid) | BIN (Buy It Now) |
|---|---|---|
| Price | Starting bid; winner = highest bid at end | Fixed price; first buyer wins instantly |
| Listing fee | **5 % of starting bid** + duration fee | **1 %** if price < 10M; **2 %** 10M–100M; **2.5 %** ≥ 100M (+ duration fee (?)) |
| Durations | presets **1 h, 6 h, 12 h, 24 h, 48 h**; custom up to **336 h (14 days)** | same presets (?) |
| Duration fee (preset) | 1 h: 20 · 6 h: 45 · 12 h: 100 · 24 h: 350 · 48 h: 1,200 coins (?) | |
| Duration fee (max custom 14 d) | **55,200 coins** | |
| Grace period | — | **20 s** after listing during which it cannot be bought (anti-snipe of mispriced listings) |
| Cancel | Only while **no bids** | Any time before sold (listing fee **not** refunded) |
| End | Highest bidder claims item; seller claims coins | Seller claims coins after sale; unsold returns to seller after duration |
| Max price | **50,000,000,000** coins (fee then 2.5B auction / 1.25B BIN) | |

### 3.3 Bidding rules

| Rule | Value |
|---|---|
| First bid | ≥ starting bid |
| Min increment | **≥ 2.5 % above the current top bid** per the current wiki (older behaviour was +15 % (?)) |
| Funds | Bid coins are **taken from the purse immediately** (escrow); when outbid, coins become claimable back (or auto-refund to purse (?)) |
| Anti-snipe | A bid placed with < **2 min** left sets the remaining time to **2 min** |
| Self-bid | Cannot bid on own/co-op's auctions |
| Bid history | "View Bids" (gold nugget) menu shows active bids + "Claim All" |
| Delisting from browse | Listings stop being shown in the browser at **4 min 45 s** remaining (?: wiki phrasing; possibly cache related) |

### 3.4 Claim / collection tax

- When a seller **collects** coins from an auction or BIN sale above **1,000,000 coins**, a tax of up to
  **1 %** is taken, **capped so the payout never drops below 1,000,000**.
  `payout = price > 1e6 ? max(1e6, price × 0.99) : price`.
- **Derpy (QUAD TAXES):** the collection tax is **×4 (4 %)**; creation and duration fees unchanged.

### 3.5 Listing limits & restrictions

| Rule | Value |
|---|---|
| Max active listings | **14** base; **+3 per additional co-op member**; **35** max (8-player co-op) |
| COMMON-rarity price rule | Starting price for COMMON items cannot exceed **5× their NPC sell price** (anti-money-laundering of junk items) |
| Untradeable | Soulbound / Co-op Soulbound; some event items; items with "Auction House restricted" flag |
| Co-op | Auctions and slots are **shared** across co-op; any member can claim |
| Minimum price | 1 coin (?) |

### 3.6 Browser, search, filters (what the player sees)

| Control (icon) | Options |
|---|---|
| Category tabs (left column) | **Weapons, Armor, Accessories, Consumables, Blocks, Tools & Misc** |
| Search (Oak Sign) | Free text on item name (sign input; also `/ahs <query>` with cookie) |
| Rarity filter (Eye of Ender) | No filter / COMMON … SPECIAL |
| Sort (Hopper) | Highest Bid, Lowest Bid, Ending Soon, Most Bids (+ Random (?)) |
| BIN filter (Powered Rail) | Show All / BIN Only / Auctions Only |
| Item upgrades filter (?) | Stars / dungeon stars (newer UI) (?) |
| Pagination | 24 results per page (?) with next/prev arrows |
| Your auctions (golden horse armor / chest) | "Manage Auctions": create, view, claim |
| Your bids (gold nugget) | "View Bids" |
| Create auction | Drop item in slot → set price (sign) → set duration (clock) → toggle Auction/BIN → confirm (shows fee) |

### 3.7 AH API shape (for the clone's own API)

Hypixel's `/v2/skyblock/auctions?page=N` (paged, ~1,000 per page) returns per auction:
`uuid, auctioneer (uuid), profile_id, coop[uuids], start, end (ms), item_name, item_lore, extra,
category, tier, starting_bid, item_bytes (base64 gzipped NBT), claimed, claimed_bidders[],
highest_bid_amount, bin (bool), bids[{auction_id, bidder, profile_id, amount, timestamp}], last_updated`.
`/v2/skyblock/auctions_ended` gives the last 60 s of sales (price, buyer, seller, bin) — the source
community "lowest BIN" trackers use.

---

## 4. NPC Shops

### 4.1 Rules

| Rule | Value |
|---|---|
| Daily **buy** limit | **640 per item** (per merchant — merchants selling the same item have separate limits); **6,400** under Diaz "Shopping Spree" (10×) |
| Daily **sell** cap | **500,000,000 coins/day** per player |
| Reset | **00:00 UTC** |
| Sell price | Identical at every merchant; any merchant buys any sellable item |
| Discounts | e.g. Seal of the Family accessory, "Bartender Discount" attribute (?) |
| Bulk buy | Shift-click (or right-click (?)) buys a full stack where allowed; GUI shows "Buy 1 / Buy stack" |
| Cookie | With a Booster Cookie, items can be sold directly from Trade/crafting menus and Material stash insta-sold |

### 4.2 Buyback

Selling to a merchant puts the item in that player's **buyback slot** (a single slot per shop, top-right
of the shop GUI (?)), re-purchasable for exactly what it sold for. It holds the **last sold item/stack
only** (?) and is cleared on logout/server change (?). Purpose: undo misclicks. Clone: keep a
**10-entry per-player buyback list** (more forgiving) priced at sell price, cleared after 15 min.

### 4.3 Merchant roster (Hub — post Hub Revamp, roughly)

| Merchant | Sells |
|---|---|
| Farm Merchant | crops, sand, bone meal, hoes, wheat seeds |
| Mine Merchant | coal, iron, gold, gravel, cobblestone, pickaxes, (?) |
| Lumber Merchant | logs, axes, saplings |
| Fish Merchant | fishing rods, raw fish, sponge (?) |
| Combat/Adventurer Merchant | mob drops, basic talismans (Zombie/Skeleton/Village/Mine affinity) |
| Builder | construction blocks |
| Librarian | XP bottles, basic enchanted books |
| Bartender | brews (potions), Revenant minion XII (?) |
| Weaponsmith / Armorsmith | basic weapons/armor |
| Alchemist | potion ingredients, brewing stands |
| Pat (Mushroom), Rosetta (armor), etc. | specialist goods |
| Banker / Auction / Bazaar agents | services, not item shops |

### 4.4 NPC "Trades" (collection-unlocked item→item trades)

A separate menu (Recipe Book → Trades): unlocked by collection tiers, e.g. **Water Bucket for 12 coins**,
2 Coal for Oak Log. Share the same **640/day** limit (6,400 under Diaz). Trading for resources **does not
add collection progress**.

---

## 5. Special auctions (Dark Auction, Shen's Auction, Stonks Auction)

### 5.1 Dark Auction

| Rule | Value |
|---|---|
| When | **Every real hour at :55** (= 00:00 SB time on every third SB day) |
| Where | Wilderness (Hub), Sirius' auction room at ~(91, 75, 176) |
| Entry | Must be **holding ≥ 400,000 coins in purse** on entry |
| Rounds | **5** items (Scorpius "Darker Auctions": perk text says rounds go to **7**; the wiki item table has rounds 6–8 (?)) |
| Round timing | 5 s before bidding opens; then a **10 s** bid window, **reset to 10 s on every bid** |
| Bid input | Predetermined bid buttons (fixed steps above the current top bid; exact steps not confirmed (?)) |
| Payment | Purse only; non-winning bids refunded; winner charged automatically |
| Remote access | "Travel Scroll to Dark Auction" (itself sold in the DA); Sirius' phone number (Abiphone contact) (?) |

Item pool (rounds 1–4 draw from the general pool; round 5 is always an enchanted book; the Flower
Minion can appear in any round):

| Item | Min bid |
|---|---|
| Ender Artifact | 20,000,000 |
| Wither Artifact | 10,000,000 |
| Nether Artifact | 10,000,000 |
| Magic 8 Ball | 20,000,000 |
| Eccentric Painting Bundle | 20,000,000 |
| Hocus-Pocus Cipher | 10,000,000 |
| Dark Purple Dye (0.25 % per eligible round, R1/R3/R4) | 20,000,000 |
| Midas' Sword | 100,000 |
| Sirius' Personal Phone Number | 1,000,000 |
| Travel Scroll to Dark Auction | 1,000,000 |
| EPIC Turtle / Parrot / Jellyfish pet | 500,000 / 1,000,000 / 10,000,000 |
| LEGENDARY Turtle / Parrot / Jellyfish pet | 1,000,000 / 1,000,000 / 10,000,000 |
| Round 5 books: Sharpness VI, Giant Killer VI, Protection VI, Growth VI, Power VI | 500,000 each |
| Round 5 book: Vicious V | 10,000,000 |
| Flower Minion (any round) | 2,500,000 |
| **Darker Auction (Scorpius) R6–7:** book bundles (Vicious, Big Brain), Protection/Sharpness/Power/Growth/Giant Killer VII, Counter-Strike V | 10,000,000 each |
| **Darker Auction R8:** Plasma Nucleus / Hegemony Artifact | 20,000,000 / 100,000,000 |
| Midas Staff (Darker, any round) | 100,000 |

Midas' Sword / Midas Staff scale their damage with the **price paid** for them — an intentional
"burn coins for power" sink that also makes the DA a whale arena.

### 5.2 Shen's Auction (annual, beneath the Hub Auction House)

| Track | Unlock | Contents |
|---|---|---|
| **Shen's Auction** | Museum Milestone 10 | Always the same 3: **Artifact of Control**, **Pandora's Box**, **Shen's Regalia** |
| **Shen's Special** | Museum Milestone 20 | Rotating pair of items (fishing gear, helmets, wands, rods…) |

- A new auction **every SB year** (every 124 real h); limited stock per item; top bidders claim after it ends.
- **Diaz "Volume Trading":** quantity per Shen's Auction **×2**, and **+2 extra Shen's Special** auctions.
- Ironman has its own Ironman-only Shen's with **50 % stock** (since Oct 2025). Bingo/Stranded excluded.
- Artifact of Control **doubles the owner's election votes** (feeds the 224-vote max, §8.3).

### 5.3 Stonks Auction (Diaz "Stock Exchange")

| Rule | Value |
|---|---|
| Active | Only while **Diaz** is elected with the Stock Exchange perk |
| Host | Richard (NPC) |
| Min bid | **2,000,000 coins** |
| Format | Ranked bidding: every participant gets **≥ 1 Stock of Stonks**; higher bids → more stocks (exact ranking table not confirmed (?)) |
| Use of stocks | Trade to Liz (Trade Center): 1 → Freshly-Minted Coins (server-wide limit **5,000 per 4 h**); 3 → Golden Fragment; 20 → Golden Bounty; 50 → Avaricious Chalice |
| Tradeable | Stock of Stonks is tradeable |

---

## 6. Booster Cookie, Bits, Fame, Gems and the Community Shop

### 6.1 Booster Cookie

| Property | Value |
|---|---|
| Rarity | LEGENDARY consumable |
| Duration | **4 real days**, **counts down while offline**; multiple cookies **stack** duration |
| Price | **325 gems** (6 for 1,950; 12 for 3,900) at Elizabeth; tradeable on the Bazaar for coins (several million (?)) |
| Free cookies | 1 per account (Brigette's security quest / Security Hall); Security Hall quests once per profile |

Effects while active:

| Effect | Value |
|---|---|
| Wisdom | **+25 ☯ on every Wisdom stat** (= +25 % XP in every skill) |
| Magic Find | **+15** |
| Coins on death | **Keep 100 %** |
| Bits | Unlocks bit generation (§6.2) |
| Remote GUIs | `/ah`, `/bz` anywhere; quick-access commands for other menus (anvil, enchanting, etc.) (?) |
| Sell anywhere | Sell items directly from Trades/crafting menus; insta-sell Material stash to Bazaar |
| Fly | Permafly on private island and Garden |
| AFK | AFK-kick immunity on own island and Garden |
| Potions | Toggle individual potion effects on/off (timers keep running) |
| Chat | Link items in chat |
| Chocolate Factory | +0.25× production |
| Misc | Consume Mixins directly; call Abiphone contacts |

### 6.2 Bits

| Rule | Value |
|---|---|
| Pool per cookie | **4,800 × fame multiplier** added to "Bits Available" when eaten |
| Generation | Every **30 min (online)**: 250 × multiplier (= 1/19.2 of a cookie's pool) moves Available → balance |
| Offline | On login, at most **one** payout if ≥ 30 min passed; offline time does not generate |
| Drain time | **~9.6 real hours online** per cookie (19.2 payouts × 30 min) |
| Other sources | Dungeon F7 25 / M7 100; Arachne 5–10; Magma Boss / Ashfang 30; Slayer T5 15; Jacob's contest 40–80 by medal (Diamond 80); Trophy Fish Diamond 30; Faction quest rank S 60; Starlyn contest Divine 105 — all × fame multiplier (?) |
| Spent at | Elizabeth's Bits Shop |
| Fame | Each bit spent = **1 fame** |

Bits Shop (partial, prices in bits):

| Item | Bits |
|---|---|
| God Potion | 1,500 |
| Kismet Feather | 1,350 |
| Enrichment (any stat) | 5,000 |
| Pure Black / Pure White Dye | 250,000 |
| Tuning Template (max 8) | 10,000 each |
| Pet slot upgrade (×2) | 5,000 |
| Dye application | 100 |
| Fann's Turbo Training | 500 |
| Dimensional Infusion | 200 |
| Builder's Wand, Block Zapper, Bits Talisman, Inferno Fuel Block, Rune Sack and other sacks, stacking-enchant books (Expertise, Compact, Cultivating, Champion, Hecatomb), Abicases, Autopet Rules, Hologram, Ditto Blob (?) | various (not confirmed today) |

Bits are **account-bound** (not tradeable), but many bits purchases (God Potion, Kismet Feather,
enrichments, dyes) **are** tradeable → bits convert to coins via the AH. "Coins per bit" is a
community-tracked figure (historically ~1,000–2,000 coins/bit (?)).

### 6.3 Fame ranks

Fame: **1 per bit spent**, **200 per gem spent** in the Community Shop, **200 per City Project contribution**.

| Rank | Fame | Bits mult | Election votes |
|---|---|---|---|
| New Player | 0 | 1.0× | 1 |
| Settler | 20,000 | 1.1× | 1 |
| Citizen | 80,000 | 1.2× | 2 |
| Contributor | 200,000 | 1.3× | 3 |
| Philanthropist | 400,000 | 1.4× | 5 |
| Patron | 800,000 | 1.6× | 10 |
| Famous Player | 1,500,000 | 1.8× | 20 |
| Attaché | 3,000,000 | 1.9× | 25 |
| Ambassador | 10,000,000 | 2.0× | 50 |
| Statesperson | 20,000,000 | 2.04× | 75 |
| Senator | 33,000,000 | 2.08× | 100 |
| Dignitary | 50,000,000 | 2.12× | 100 |
| Councilor | 72,000,000 | 2.16× | 100 |
| Minister | 100,000,000 | 2.2× | 100 |
| Premier | 135,000,000 | 2.22× | 100 |
| Chancellor | 178,000,000 | 2.24× | 100 |
| Supreme | 230,000,000 | 2.26× | 100 |
| Overseer | 250,000,000 | 2.28× | 100 |
| Regent | 290,000,000 | 2.3× | 100 |
| Viceroy | 330,000,000 | 2.32× | 100 |
| Sovereign | 370,000,000 | 2.34× | 100 |
| Archon | 410,000,000 | 2.36× | 100 |
| Imperator | 450,000,000 | 2.38× | 100 |
| Paragon | 500,000,000 | 2.4× | 100 |

### 6.4 Community Shop (Elizabeth, Community Center)

| Tab | Content |
|---|---|
| Booster Cookie | buy with gems |
| Bits Shop | §6.2 |
| **Account & Profile Upgrades** | timed "construction" upgrades — one profile upgrade and one account upgrade building at a time; gems skip the timer |
| City Projects | community-wide contribution projects (long-lived, give perks/cosmetics; contributions give fame) |
| Fire Sales | limited-time cosmetics for gems |

**Profile upgrades** (per profile):

| Upgrade | Tiers | Effect | Cost / build time |
|---|---|---|---|
| Island Size | I–X | 161×161 → 241×241 | I free, 2 d; II–VI 175 gems, 4–10 d; VII–X 200 gems, 10 d |
| Minion Slots | I–V | +1 … +5 | free, 1–24 d |
| Guests Limit | I–V | +1 … +4 (?) guest slots | I free, 2 d; II–V 50 gems, 4–16 d |
| Co-op Slots | I–III | +1 … +3 members (5 → 8) | 600 gems each, instant |
| Coins Allowance | I–V | 10k → 50k coins/day | free, 5–25 d |

**Account upgrades** (all profiles):

| Upgrade | Tiers | Effect | Cost / build time |
|---|---|---|---|
| Armor Wardrobe Slots | I–IX | +1 … +9 | I free; II–IX 250–500 gems; 6 d each |
| Equipment Wardrobe Slots | I–IX | +1 … +9 | 250 gems, 6 d |
| Loadout Slots | I–IX | +1 … +9 | 500 gems, 6 d |
| HotM / HotF slots | I–III | +1 … +3 | 150 gems, 6 d |
| Ender Chest Pages | I–VIII | +45 slots/page (VIII +36) | I free; II–VIII 75–150 gems; 6 d |
| Accessory Bag | I–VI | +2 … +12 slots | free, 4–18 d |
| Sack of Sacks | I–VIII | more sack slots | I–II free; III–VIII 100–300 gems; 12 d |
| Magic Find | I–V | +1 … +5 MF | free, 1–16 d |
| **Bazaar Flipper** | I–II | I: +7 orders, −0.125 % tax (free, 5 d) · II: +14 orders, −0.25 % tax (100 gems, 10 d) | |
| Garden Farming Fortune | I–X | +4 … +40 FF | free, 3–30 d |

Totals (wiki): everything costs **6,050 gems** if you wait; **382 days** of profile-upgrade and **244 days**
of account-upgrade build time; skipping every timer ≈ **80,904** more gems.

Profile slots: 2 default; VIP → 3; MVP+ → 4; 5th slot **2,750 gems**; 6th reserved for Bingo during events.

### 6.5 What replaces gems in a clone (no real money)

Hypixel's gem economy does three jobs: (1) a **real-money → coin bridge** (buy cookies with gems, sell
them on the Bazaar), (2) **time-skips** for upgrades, (3) **cosmetics**. A no-real-money clone splits these:

| Hypixel | Clone replacement |
|---|---|
| Gems (real money) | **Earned token currency** (working name "Tokens", rename later): daily login streak (e.g. 5/day), weekly quests (50), every 10 SkyBlock levels (25), event placements (Spooky top tiers, Jacob Diamond), voting in the election (5) |
| Booster Cookie for 325 gems | Cookie from the Community Shop for **tokens**, *or* for **coins at a floating price** (start ~2M; adjust ±2 %/day toward a target cookies-sold/day) → a **major coin sink** that keeps cookies liquid on the Bazaar |
| Gem time-skips | Remove, or allow skips for tokens at a steep rate; keep build timers as pacing |
| Fire Sales | Token-priced cosmetics, or coin-priced → strongest late-game sink |
| Co-op slots (600 gems) | Tokens, or coins (e.g. 25M each) |
| Fame (200/gem) | Fame from bits spent + tokens spent + City Project contributions |

Keep the **cookie as the hub item**: every active player wants one, it is bought with earnable currency,
and its Bazaar price becomes the server's **inflation index** (watch it on the admin dashboard).

---

## 7. The SkyBlock calendar

### 7.1 Time units

| Unit | Real time | Seconds |
|---|---|---|
| SB hour | 50 s | 50 |
| SB day (24 h) | 20 min | 1,200 |
| SB week (7 d) | 2 h 20 min | 8,400 |
| SB month (31 d) | 10 h 20 min | 37,200 |
| SB season (3 months) | 31 h | 111,600 |
| SB year (12 months, 372 d) | 5 d 4 h | 446,400 |

Day/night: daytime 06:00–19:00 SB, night 19:00–06:00 (Crimson Isle ghasts spawn 21:00–05:00).

### 7.2 Months

| # | Month | Real offset from year start |
|---|---|---|
| 1 | Early Spring | 0 h |
| 2 | Spring | 10 h 20 m |
| 3 | Late Spring | 20 h 40 m |
| 4 | Early Summer | 31 h |
| 5 | Summer | 41 h 20 m |
| 6 | Late Summer | 51 h 40 m |
| 7 | Early Autumn | 62 h |
| 8 | Autumn | 72 h 20 m |
| 9 | Late Autumn | 82 h 40 m |
| 10 | Early Winter | 93 h |
| 11 | Winter | 103 h 20 m |
| 12 | Late Winter | 113 h 40 m |

### 7.3 Conversion

```
EPOCH = 1560275700            // Unix seconds = Year 1, Early Spring 1, 00:00 (2019-06-11 17:55:00 UTC)
t     = now_unix - EPOCH
year  = floor(t / 446400) + 1
month = floor((t % 446400) / 37200) + 1          // 1..12
day   = floor((t % 37200) / 1200) + 1            // 1..31
hour  = floor((t % 1200) / 50)                   // 0..23
min   = floor((t % 50) * 60 / 50)                // 0..59 (in-game clock shows 10-minute steps)
```

Check: 2026-10-02 → t ≈ 230.7M s → year **517**, matching the live election API.
SB day boundaries fall at real **:15, :35, :55** past each hour (epoch was 17:55 UTC) — which is why the
Dark Auction (:55) lands on an SB midnight.

### 7.4 Annual calendar of recurring events

| SB date | Event | SB duration | Real duration | Notes |
|---|---|---|---|---|
| Every 3rd SB day | **Jacob's Farming Contest** | 1 day | **20 min, every real hour** | §7.10 |
| Every 3rd SB day, 00:00 | **Dark Auction** | minutes | every real hour at :55 | §5.1 |
| Days 7, 14, 21, 28 of each month, 00:00–06:00 | Cult of the Fallen Star | 6 SB h | 5 min | Dwarven Mines gathering (?) |
| Early Spring 1 / Early Summer 1 / Early Autumn 1 / Early Winter 1 | **Bank interest** | instant | every 31 h | §1.3 |
| Early Spring 1 → Late Spring 31 | Hoppity's Hunt (Chocolate Factory egg hunt) | 3 months | 31 h | not core economy (?) |
| Late Spring 27 | **New Mayor takes office** (voting closes) | — | — | §8 |
| Early Summer 1–3 | **Traveling Zoo** | 3 days | 1 h | Oringo, Hub Village |
| Late Summer 27 | **Voting opens** | until Late Spring 27 | **93 h** | §8 |
| Autumn 29–31 | **Spooky Festival** | 3 days | 1 h | §7.6 |
| Early Winter 1–3 | **Traveling Zoo** | 3 days | 1 h | 2nd zoo of the year |
| Late Winter (month) | Jerry's Workshop open | ~month | ~8 h before + 1 h after main event (wiki) | §7.7 |
| Late Winter 24–26 | **Season of Jerry** (Gift Attack) | 3 days | 1 h | §7.7 |
| Late Winter 29–31 | **New Year Celebration** | 3 days | 1 h | §7.8 |
| Mayor: Marina | Fishing Festival | days 1–3 of every month | 1 h × 12 | §7.9 |
| Mayor: Cole | Mining Fiesta | 7 days × 4–5 | 2 h 20 m each | §7.9 |
| Mayor: Diana | Mythological Ritual | whole term | 124 h | Griffin pet, burrows |
| Mayor: Foxy | Carnival (Chivalrous Carnival) | whole year | 124 h | Hub Carnival |
| Mayor: Finnegan | Grand Feast (year-long Harvest Feast) | whole year | 124 h | Grand Bakery, Kernels |
| Mayor: Diaz | Stonks Auction | term | — | §5.3 |
| Mayor: Jerry | Perkpocalypse rotation | every 18 SB days | every 6 h | §8.5 |
| Every SB year | Shen's Auction | — | — | §5.2 |
| Real-world late October | extra Spooky Festivals | — | — | live-ops |
| Real month, days 1–7 | **Bingo** event | — | 7 d (14 d Extreme/Secret) | §9.4 |

### 7.5 Real-time cadence summary (scheduler cheat-sheet)

| Cadence | Events |
|---|---|
| Every 1 real h | Jacob's contest (20-min window), Dark Auction (:55) |
| Every 10 h 20 m (SB month) | Fishing Festival when Marina (first ~1 h of each month) |
| Every 31 h (SB season) | Bank interest |
| Every 62 h (half SB year) | Traveling Zoo |
| Every 124 h (SB year) | Spooky Festival, Season of Jerry, New Year, election result + new Mayor, Shen's Auction |
| Every 8 SB years (~41 real days) | A special mayor candidate is on the ballot |
| Every 24 SB years (~124 real days) | Each individual special mayor returns |
| Every real month | Bingo (first 7 days) |
| 00:00 UTC daily | NPC buy/sell limits, Coins Allowance (?) reset |

### 7.6 Spooky Festival

| Rule | Value |
|---|---|
| Date | **Autumn 29–31** (1 real hour); extra festivals around real Halloween |
| Candy drop | Any mob: **10 %** Green Candy; each candy has **10 %** to be Purple instead |
| Points | Green = **1**, Purple = **5** |
| Candy chance boosts | Spooky / Bat Person armor full sets +5 % each; Candy Relic +20 %; Candy Artifact +15 %; Bat pet up to +20 % → max **94 %**, **124 %** with Foxy's Sweet Benevolence |
| Spooky mobs | Night spawns in Graveyard, Wilderness, Spider's Den, Obsidian Sanctuary (Scary Jerry, Trick or Treater, Wraith, Phantom Spirit; Headless Horseman boss (?)) |
| Trick or Treat chests | 1 % (normal mob) / 3 % (spooky mob); either a hostile surprise or 1–3 items |
| Shop | **Fear Mongerer** sells for candy: Bat Person armor, Spooky Bait, Candy talisman line, Spooky Pie, Horseman's Candle, Bat Talisman, dyes, Witch Mask… (candy prices not confirmed (?)) |
| Leaderboard | **Top 50** (≥ 5,000 pts): Golden Treat, **1,000,000 coins**, Skeleton Horse pet, Witch Mask, Vampirism VI, Spooky disc, 50 Carnival tickets · **Emerald (5,000)**: as top 50 minus extras, 35 tickets · **Diamond (2,000)**: **500,000 coins**, Witch Mask, books, 25 tickets · **Gold (1,750)**, **Iron (1,500)**, lower tiers with smaller coin/book rewards (?) |
| Unclaimed rewards | expire after **10 SB years** |

### 7.7 Season of Jerry / Jerry's Workshop

| Rule | Value |
|---|---|
| Workshop (Winter Island) | Open in Late Winter; wiki: ~8 real h before the main event and 1 h after |
| Main event | **Late Winter 24**, 1 real hour — "Defend Jerry's Workshop" (Gift Attack) |
| Format | **5 rounds** with ~8 min of breaks in total; lava-themed attackers; Magma Cubes destroy rewards and can only be damaged with **snowballs** |
| Rewards | Presents at the end of each round by damage dealt: **White, Green, Red gifts** (Bazaar-tradeable → a big yearly supply shock); North Stars (?) for the Workshop shop |
| Gifts | A gift must be given **to another player** to open — a social mechanic; recipient gets the loot, giver progresses gift-giving rewards (?) |
| Access | `/savethejerrys`, Jerry on the Private Island, SkyBlock Menu, Jerry in the Village |
| Island content | Reindrake/Yeti sea creatures, Glacial Cave, Ice minion, Einary's shop (?) |

### 7.8 New Year Celebration

| Rule | Value |
|---|---|
| Date | **Late Winter 29–31** (1 real hour) |
| Cake | Baker (next to Jerry in the Village) gives **1 free New Year Cake per SB year**, stamped with the year |
| Cake Bag | **250,000 coins**; holds **54** cakes; **+1 Health per unique cake** inside |
| Market | Old-year cakes trade on the AH (collector market — missed years become scarce) |
| Anomaly | Year-71 cakes were issued as "Year 0" (bug) — now a collectible |

### 7.9 Mayor-scheduled festivals + the Zoo

**Fishing Festival** (Marina; Foxy's Extra Event can add one; Jerry's Perkpocalypse can rotate it in)

| Rule | Value |
|---|---|
| When | SB days **1–3 of every month** (12×/year, ~1 real h each); Foxy extra: Late Summer 22 (?) |
| Sharks | **15 %** base chance to replace any sea creature; boosts: Shark Bait +20 %, Shark Scale armor +20 %, Megalodon pet +10.1–20 %, Doug's Shark Sonar +1–5 % (up to ~+80 %) |

| Shark | Fishing lvl | HP | Drops |
|---|---|---|---|
| Nurse Shark | 5 | 2,500 | 2–4 Shark Fin, 0–1 Nurse Shark Tooth |
| Blue Shark | 10 | 25,000 | 4–8 Shark Fin, 0–1 Blue Shark Tooth |
| Tiger Shark | 18 | 250,000 | 8–16 Shark Fin, 0–1 Tiger Shark Tooth, rare Megalodon pet |
| Great White Shark | 24 | 1,500,000 | 16–32 Shark Fin, 0–1 Great White Shark Tooth, rare Legendary Megalodon |

**Mining Fiesta** (Cole)

| Rule | Value |
|---|---|
| When | Perk text: 5 events × 7 SB days. Wiki: 4 actually happen — Early Summer 1, Late Summer 1, Autumn 1, Early Winter 1 (the Spring one falls before Cole takes office on Late Spring 27) |
| Duration | 7 SB days = **2 h 20 min** each (11 h 40 min total) |
| Effects (public islands) | **+75 ☯ Mining Wisdom**; **2× base drops** from blocks, ores, pure ores, gemstones, Dwarven metals |
| Special drops | Refined Minerals (ores) / Glossy Gemstones (gemstone blocks): `chance = BreakingPower / 2000 × (1 + Σmultipliers)` → 0.05 % (Coal, BP 1) … 0.45 % (Glacite/Tungsten/Umber, BP 9) |

**Traveling Zoo** (always on, not mayor-dependent)

| Rule | Value |
|---|---|
| When | Early Summer 1–3 and Early Winter 1–3 → every **2 d 14 h** real, **1 h** each |
| NPC | Oringo, Hub Village |
| Stock | **3 of 6** pets: one **LEGENDARY** on a fixed rotation + two random pets at random rarity COMMON–EPIC |
| Legendary rotation (wiki) | Giraffe (Winter 517) · Elephant (Summer 517) · Tiger (Winter 518) · Blue Whale (Summer 518) · Monkey (Winter 519) · Lion (Summer 519) — 6-step cycle |
| Prices | Coins + items scaled by rarity, e.g. **Lion COMMON: 10,000 coins + 64 Raw Beef**; **Blue Whale LEGENDARY: 10,000,000 coins + 8 Enchanted Cooked Cod** |

### 7.10 Jacob's Farming Contest

| Rule | Value |
|---|---|
| Frequency | **Every 3 SB days = every real hour** |
| Duration | **1 SB day = 20 real minutes** |
| Crops | **3 random** per contest from the farming-collection crops: Wheat, Carrot, Potato, Pumpkin, Melon, Mushroom, Cactus, Sugar Cane, Nether Wart, Cocoa Beans (newer Garden crops may now be included (?)) |
| Schedule | The calendar shows upcoming contests and their crops in advance (Jacob's calendar view) |
| Requirements | **Farming X (10)**; talked to Jacob once; **≥ 100** of the contest crop collected to place |
| Scoring | Amount of one contest crop collected during the window (a player is ranked in whichever crop they farm; only one crop counts per contest (?)) |

| Medal | Bracket (normal) | Jacob's Tickets | Bits (× fame mult) |
|---|---|---|---|
| Participation | ≥ 100 crops | small (?) | — |
| **Bronze** | top **60 %** | 10 | 40 (?) |
| **Silver** | top **30 %** | 15 | 50 (?) |
| **Gold** | top **10 %** | 25 | 60 (?) |
| **Platinum** | top **5 %** | 30 | 70 (?) |
| **Diamond** | top **2 %** | 35 | **80** |

- Every bracket also gives **1 Turbo-Crop I book** and **1 Carnival Ticket**.
- **Finnegan "GOATed":** brackets include **up to 10 % more players**.
- Platinum/Diamond give medals plus unique emblems.
- **Unique Gold:** the first Gold-or-better per crop raises the **Farming level cap by 1** (Farming 50 → 60
  through Anita) — a long-tail progression lock tied to the contest.
- **Anita's shop** (Jacob's Tickets + medals): Farming level-cap upgrades, extra contest/Garden Farming
  Fortune per tier, Cropie/Squash/Fermento armor & talisman line, Turbo books, cosmetics (prices not confirmed (?)).
- Personal bests per crop are tracked in the contest menu.

---

## 8. Mayors, Ministers and the Election

### 8.1 Term & schedule

| Rule | Value |
|---|---|
| Term | **1 SB year = 124 real hours** (5 d 4 h) |
| Voting opens | **Late Summer 27, 00:00** (election booth / "Election Room" in the Hub Community Center area) |
| Voting closes / new Mayor | **Late Spring 27, 00:00** of the following year → voting window = 9 SB months = **93 real hours** |
| Overlap | The sitting Mayor keeps governing while the next election runs (from Late Summer 27 to Late Spring 27) |
| Candidates | **5** per election; regular candidates are drawn at random, **excluding the sitting Mayor** (and the sitting Minister, see below) |
| Special candidate | Every **8 SB years** a special mayor takes one of the 5 slots (4 regulars + special, shown rightmost) |
| Special rotation | **Scorpius → Derpy → Jerry → Scorpius …** (each returns every 24 SB years ≈ 124 real days). Wiki-documented years: Jerry 496 → 520; Scorpius 504 → 528; Derpy 512 → 536 |
| Result | 1st place = **Mayor** (all of their perks on the ballot); 2nd place = **Minister** (exactly one perk — the one flagged `minister`) |
| Minister lockout | The Minister **cannot run in the next election**, unless Diaz's **Long Term Investment** is active (then the elected Minister appears next election with all perks) |
| Special mayor wins | **No Minister** is appointed; "Mayor Nancy" placeholder is shown |
| Ties | Unknown (wiki) — clone: tie → earlier vote timestamp of reaching the total, or random seeded by year |
| Track record | No special mayor has ever lost an election |

### 8.2 How a candidate's perks are chosen (verified against the live API)

- Each **regular** mayor has a pool of 3–4 perks (Paul 3, others 4).
- For each election, each candidate runs with a **rolled subset of 1–4 perks** — in Year 516: Diana 3,
  Finnegan 1, Marina 3, Foxy 3, Cole 4; in Year 517: Marina 3, Finnegan 1, Diaz 1, Foxy 3, Aatrox 1.
  The count does **not** depend on votes (Finnegan had 1 perk with 127k votes; Diaz 1 with 15k).
- **Exactly one** of the candidate's on-ballot perks is flagged `minister: true`; that is the perk they
  bring if they finish 2nd. The minister perk is re-rolled each time the candidate appears.
- **Special** mayors always run with **all** of their perks.
- Year 516 result for scale: Diana 794,530 votes (56 %), Cole 359,297 (25 %) → Diana mayor, Cole minister
  (Mining XP Buff); ~1.42M total votes cast.

### 8.3 Voting

| Rule | Value |
|---|---|
| Who | One ballot **per account** (not per profile); a SkyBlock-level gate may apply (?) |
| Change vote | Allowed any time while voting is open |
| Weight | **Votes per ballot by Fame rank** (1 → 100, table §6.3) |
| Boosts | Voter's Badge upgrades; **Artifact of Control doubles votes**; max **224 votes** per account |
| Rewards for voting | Scorpius bribe (if he wins and you voted for him); vote-streak / "voter" cosmetics (?) |
| UI | Election booth GUI: 5 candidate heads (perks in lore, minister perk marked), your current vote, live vote % bars per candidate, time remaining |

### 8.4 Regular mayors (current perk pools)

| Mayor | Type | Perk | Effect | Notes |
|---|---|---|---|---|
| **Aatrox** | Slayer | SLASHED Pricing | Starting Slayer quests is **half price** | |
| | | Slayer XP Buff | **+25 %** Slayer XP | |
| | | Pathfinder | Rare drops **20 % more often** | was minister perk in Y517 ballot |
| | | (4th perk) | Acquire Slayer quest bosses spawn faster / "MOAR SLAYERS" (?) | wiki list shows 3 |
| **Cole** | Mining | Prospection | Mining minions work **25 % faster** | |
| | | Mining XP Buff | **+60 ☯ Mining Wisdom** on public islands | current Minister (Y516) |
| | | Mining Fiesta | Schedules **5** Mining Fiestas × 7 SB days: 2× drops, +75 Mining Wisdom | 4 actually occur |
| | | Molten Forge | Forge time **−25 %** | |
| **Diana** | Pets | Huntress' Intuition | **+10 Tracking** (?: older perk "Lucky!" +25 Pet Luck) | not on the Y516 ballot |
| | | Mythological Ritual | Sells the **Griffin pet**; Mythological Creatures & burrows (Hub), unique loot | current Mayor |
| | | Pet XP Buff | **+35 %** pet XP | |
| | | Sharing is Caring | **+2 Exp Share slots** (3 active total) and **+10 %** Exp Share rate | |
| **Diaz** | Economist | Long Term Investment | The elected Minister appears in the next election with all their perks | |
| | | Volume Trading | Shen's Auction quantity **×2**, **+2** extra Shen's Special auctions | |
| | | Stock Exchange | Stonks Auction for Stock of Stonks | |
| | | Shopping Spree | NPC daily buy limits **×10** (640 → 6,400), incl. NPC Trades | |
| **Finnegan** | Farming | Pest Eradicator | Pesthunter Phillip's bonus lasts **60 min**; pest spawn cooldown **−20 %** | |
| | | GOATed | Jacob's contest brackets include **up to 10 % more** players | |
| | | Blooming Business | Garden Visitors give Fine Flour, appear more often, higher rarity; **+10 % Copper** | |
| | | Grand Feast | Harvest Feast becomes **year-long**; opens the Grand Bakery to spend Kernels | |
| **Foxy** | Events | Sweet Benevolence | **+30 %** Candy, Gifts and Chocolate from duplicate event items | |
| | | Extra Event | Schedules an **extra** Spooky Festival, Mining Fiesta **or** Fishing Festival (rolled per ballot) | |
| | | A Time for Giving | Party Chests & Party Gifts obtainable during active events | |
| | | Chivalrous Carnival | The **Carnival** in the Hub is open the whole year | |
| **Marina** | Fishing | Fishing XP Buff | **+50 ☯ Fishing Wisdom** on public islands | |
| | | Luck of the Sea 2.0 | **+15 Sea Creature Chance** | |
| | | Fishing Festival | Fishing Festival on the **first 3 days of every month** (sharks) | |
| | | Double Trouble | **+0.1 Double Hook Chance per 1 Sea Creature Chance** | |
| **Paul** | Dungeons | Marauder | Dungeon reward chests **20 % cheaper** | 3 perks, not yet reworked |
| | | EZPZ | **+10 bonus score** in Dungeons | |
| | | Benediction | Blessings **25 % stronger** | |
| **Barry** (retired after Year 464) | Magic | Arcane Catalyst | Spells **+15 %** damage (hidden: −15 % mana cost) | historical |
| | | Astral Negotiator | Enchanting & anvil XP cost **−15 %** (hidden: +1 MF per year not elected, max 20) | |
| | | Magic XP Boost | **+15** Alchemy & Enchanting Wisdom (hidden: +2 Superpairs clicks) | |

### 8.5 Special mayors

| Mayor | Appears | Perks |
|---|---|---|
| **Derpy** | every 24 SB years (512, 536 …) | **QUAD TAXES!!!** — pay **4×** taxes (AH collection tax 1 % → 4 %; creation/duration fees unchanged; Bazaar tax ×4 (?)) · **TURBO MINIONS!!!** — minions **2× output** · **DOUBLE MOBS HP!!!** — all monsters **2× health** · **MOAR SKILLZ!!!** — **+50 %** skill XP |
| **Jerry** | 496, 520 … | **Perkpocalypse** — activates **all perks of another mayor**, rotating every **18 SB days (6 real h)** (excludes specials, retired mayors and Diaz) · **Statspocalypse** — all Combat & Misc stats **+10 %** · **Jerrypocalypse** — hidden Jerries appear from logging, farming, mining and mob kills |
| **Scorpius** | 504, 528 … | **Bribe** — if he wins and you voted for him, receive **50,000–1,000,000 coins** (scaled by progression (?)) · **Darker Auctions** — Scorpius intrudes in Dark Auctions: more rounds (7 per perk text / 8 per item table) and special items (§5.1) |

### 8.6 One-time mayors (live-ops precedents worth copying)

| Mayor | When | What happened |
|---|---|---|
| **Technoblade** | memorial (2022) | Blood God's Blessing +50 % skill XP; Shrine of the Blood God (Shiny Pigs); Unlimited Speed (+50, uncapped; bugged, removed); Potato Crown's Radiance (farming minions 2× — bugged to all minions); Anarchy (no AH/Bazaar taxes, removed) |
| **Dante** | Year 128, replacing Derpy (April Fools) | Perks swapped after election: **double AH/Bazaar taxes**, **10 % coin tax on shops**, **5 coins/min to chat**, **2-coin toll to travel**; "hired protection", cancelled events, reduced sunlight — an authoritarian satire that doubled as a mass coin sink |
| **Aura** | Year 464 (Nov 2025) | Hidden perks: **Fundraising** (taxes and costs vastly increased); **Universal Income** (every hour, 100 % of Fundraising coins redistributed equally to players); **Minion Union** (minions +50 % slower); **Work Better** (everyone luckier); **Work Harder** (mobs +10 % dmg/HP); **Work Smarter** (+50 % skill/dungeon/slayer XP). Some ministers had perks disabled. Barry retired the same year |

Clone takeaway: a **taxing-and-redistributing** mayor (Aura) and a **toll** mayor (Dante) are proven
event templates for wealth redistribution and inflation control.

### 8.7 Election API shape (`GET /v2/resources/skyblock/election`, keyless)

```json
{
  "success": true,
  "lastUpdated": 1790930709552,
  "mayor": {
    "key": "pets", "name": "Diana",
    "perks": [ {"name": "Pet XP Buff", "description": "Gain §a35% §7more pet XP.", "minister": false}, "..." ],
    "minister": { "key": "mining", "name": "Cole",
                  "perk": {"name": "Mining XP Buff", "description": "Get §a+60☯ Mining Wisdom §7on public islands.", "minister": true} },
    "election": { "year": 516,
      "candidates": [ {"key": "pets", "name": "Diana", "perks": ["..."], "votes": 794530}, "..." ] }
  },
  "current": { "year": 517,
    "candidates": [ {"key": "fishing", "name": "Marina", "perks": ["..."], "votes": 362980}, "..." ] }
}
```

Keys are the mayor *type* (`pets`, `mining`, `fishing`, `farming`, `events`, `economist`, `slayer`,
`dungeons` (Paul), plus specials). Descriptions carry Minecraft `§` colour codes. `current` is present
only while voting is open.

---

## 9. Profiles: Co-op, Ironman, Stranded, Bingo

### 9.1 Profile basics

| Rule | Value |
|---|---|
| Profile slots | 2 default; VIP 3; MVP+ 4; 5th = 2,750 gems; 6th reserved for Bingo |
| Names | Auto-assigned fruit/veg (Apple, Banana, Blueberry, Coconut, Cucumber, Grapes, Kiwi, Lemon, Lime, Mango, Orange, Papaya, Pear, Peach, Pineapple, Pomegranate, Raspberry, Strawberry, Tomato, Watermelon, Zucchini); cannot be renamed |
| Delete | 5-second confirm wait; solo deletion is permanent |
| Game modes | **Classic**, **Ironman**, **Stranded**, **Bingo** (temporary) — chosen at creation |

### 9.2 Co-op

| Rule | Value |
|---|---|
| Max members | **5**, +1/+2/+3 via Co-op Slots I–III (600 gems each) → **8** |
| Create / add | `/coop <p1> <p2>…` (new co-op profile) or `/coopadd <player>` (add to existing); invitees must be online in SkyBlock |
| **Shared** | Island (builds, chests, minions & minion slots, unique minion crafts), **Collections** (members see each other's contribution), **main Bank**, Auction House slots (+3 per member) and auctions, Museum (?), Garden (?) |
| **Individual** | Purse, inventory/ender chest/storage, **Skills** (minion XP is split among members), SkyBlock level, slayers, dungeons, pets, accessories, bits (account), **personal bank** (20 % of main capacity) |
| Co-op soulbound | Items flagged co-op soulbound can be traded only inside the co-op |
| Kick | `/coopkick <player>` → vote among members (all voters must be on the profile); in a **2-player co-op**, you can only kick the other after **35+ days** of their inactivity |
| Leave | Switch to another profile, then leave/delete; remaining members can `/coopsalvage <player>` the leaver's purse + inventory within **36 hours** |
| Chat | `/coopchat` (`/cc`), toggle `/chat coop` |
| Island permissions | Per-member toggles for build/break/chests/minions (?) and guest permissions |

Co-op scam surface (a clone must design for it): shared bank withdrawal, shared minion/chest access,
salvage of a kicked member's purse. Mitigations: personal banks, per-member withdrawal limits/logs,
role permissions (Owner/Member) for bank and island chests.

### 9.3 Ironman

| Rule | Value |
|---|---|
| Trading | **No trading** with players outside the co-op |
| Auction House | **Cosmetic-only** AH (buying cosmetics), since Oct 2025 |
| Bazaar | "Special Bazaar": only **instant-buy Booster Cookies** |
| Gifts | Only from other Ironman players |
| Shen's | Ironman-only Shen's Auction at **50 % stock** |
| NPC limits | Same 640/day NPC buy limits as Classic (makes Diaz valuable for Ironman) |
| Co-op | Allowed; members share collections as normal |
| Conversion | Convertible to Classic after **35 days** (Profile Management); not the reverse |
| Badge | ♻ icon after the username |

### 9.4 Stranded

| Rule | Value |
|---|---|
| World | Only the **Private Island** and **the Garden** — no Hub or other islands |
| Markets | No AH; special Bazaar (?); no trading outside the co-op |
| NPCs | **Summoned** to the island by milestones (skill levels, obtaining items) instead of found in the world |
| Resources | Villager trading (cure Zombie Villagers on your island), minions |
| Mobs | Some hostile mob levels match the player's Combat level |
| Requirements | Dungeon and Faction requirements on items/minions ignored |
| Status | **End of Life since August 13, 2025** (one final maintenance update) |
| Badge | ☀ |

### 9.5 Bingo

| Rule | Value |
|---|---|
| Schedule | **First 7 days of every real month**; Extreme / Secret Bingo run **14 days** in place of the normal one |
| Profile | Temporary 6th profile slot, fresh start |
| Card | **5×5 = 25 goals**: **20 Personal** (difficulty 1–5, hidden) + **5 Community** (on the main diagonal, Tier I–V, shared progress across all bingo players) |
| Points | Personal goal **1**; each row/column **5**; community diagonal **10**; community-goal contribution **1–15** by tier and contribution rank |
| Bingo ranks | Rank I **50** pts, II **100**, III **150**, IV **200** (purchasable; unlock perks/cosmetics, Bingo pet tiers (?)) |
| Disabled | **Auction House, Bazaar, Museum, Gem Shop, Trading** |
| Carry-over | Up to **10 slots** of items (non-soulbound) transferable to a main profile; Bingo NPC stays in the Village **3 days** after the event |
| Shop | Bingo Shop (Bingo Points): Bingo talisman line (Bingo Talisman/Ring/Artifact/Relic), Bingo pet, cosmetic hats/dyes (?) |
| Badge | Ⓑ |

---

## 10. How it connects

```
                          ┌──────────── CALENDAR (5d4h year) ────────────┐
                          │ hourly: Jacob contest, Dark Auction           │
                          │ 31h: bank interest   62h: Zoo                 │
                          │ 124h: Spooky, Jerry, New Year, ELECTION       │
                          └──────┬───────────────────────────┬────────────┘
                                 │ schedules                  │ elects
                                 v                            v
    EVENT DROPS (candy, gifts, shark fins, ──► BAZAAR ◄── MAYOR + MINISTER perks rewrite constants:
    refined minerals, cakes, Zoo pets)          ▲  │       taxes (Derpy), NPC limits (Diaz), slayer
                                                │  │       cost (Aatrox), chest cost (Paul), brackets
    SKILLS/COLLECTIONS/MINIONS ── items ────────┘  │       (Finnegan), extra events (Foxy/Marina/Cole)
            │                                       │
            │ NPC sell price = floor                ▼
            └──► NPC SHOPS (faucet: sell; sink: buy, 640/day) ──► COINS (purse) ──► BANK (interest, safe)
                                                    ▲                │ 50% on death unless COOKIE
                                                    │                v
    AUCTION HOUSE (unique gear, pets) ◄─────────────┴────── listing fee, claim tax (sinks)
                                                              │
    BOOSTER COOKIE ──► +25 wisdom, +15 MF, keep coins, /ah /bz remote, BITS ──► Bits Shop items ──► AH (bits→coins)
          ▲ gems (clone: tokens or coins)                        │
          └── bought from Community Shop ◄── FAME (bits+gems spent) ──► ELECTION VOTES (1..100, max 224)
```

| Link | Mechanism | Numbers |
|---|---|---|
| Skills → coins | Every gathering skill produces sellable items; NPC sell is the floor, Bazaar the market | NPC sell cap 500M/day |
| Collections → markets | Collections unlock recipes (enchanted items) whose ratios set Bazaar price ladders (e.g. 160 base → 1 enchanted) | Bazaar price ≥ NPC sell |
| Minions → Bazaar | Passive production; Derpy doubles output, Cole +25 % mining minions → visible Bazaar price drops during those terms | ×2 / ×1.25 |
| Calendar → supply shocks | Spooky (candy, pumpkin items), Jerry (gifts), Fishing Festival (shark fins), Mining Fiesta (2× ores, refined minerals) | 1-hour events, yearly |
| Mayor → sinks | Derpy ×4 tax; Aatrox ½ slayer fees; Paul −20 % chest cost; Diaz ×10 NPC buy limit; Dante/Aura precedent for redistribution | |
| Election → fame/cookies | Votes scale with fame; fame comes from spending bits (cookies) and gems | up to 100 (224 with boosts) |
| Cookie → everything | Removes death loss, remote AH/Bazaar (liquidity), +25 wisdom (progress), bits (secondary economy) | 4 days, 4,800 bits base |
| Museum → bank | Museum milestones unlock bank tiers and +2 % interest per milestone; Shen's Auction gated on milestones 10/20 | |
| Jacob → Farming cap | Unique Golds raise Farming cap 50 → 60; medals/tickets buy Farming Fortune | top 10 % per contest |
| SkyBlock Level → markets | Bazaar at SB 7; trade coin cap 50M / 1B / 10B at SB <50 / 50 / 100 | |
| Profiles → economy access | Ironman/Stranded/Bingo cut off AH/Bazaar/trade, forcing self-sufficiency | |
| Co-op → AH slots, bank | +3 AH slots per member; shared bank (scam surface) | 14 → 35 slots |

---

## 11. Implementation notes

### 11.1 Data shapes (suggested)

```ts
// Money
type Coins = bigint;                       // store in integer "centi-coins" (×10) to support 0.1 bazaar ticks exactly
interface Wallet { profileId: UUID; memberId: UUID; purse: Coins; }
interface Bank   { profileId: UUID; tier: 'STARTER'|'GOLD'|'DELUXE'|'SUPER_DELUXE'|'PREMIER'|'LUXURIOUS'|'PALATIAL';
                   balance: Coins; personal: Record<MemberId, Coins>; lastInterestYearSeason: number; }
interface LedgerEntry { id: ULID; ts: number; kind: 'NPC_SELL'|'NPC_BUY'|'BZ_FILL'|'BZ_TAX'|'AH_FEE'|'AH_CLAIM'|'AH_TAX'
                        |'TRADE'|'DEATH_LOSS'|'INTEREST'|'BANK_DEP'|'BANK_WD'|'EVENT_REWARD'|'ADMIN';
                        from: Account|'WORLD'; to: Account|'WORLD'; amount: Coins; ref?: string; }   // WORLD = faucet/sink

// Items
interface ItemStack { itemId: string; count: number; uuid?: UUID /* required for non-stackables */; nbt: ItemAttrs; }

// Bazaar
interface BzOrder { id: ULID; productId: string; side: 'BUY'|'SELL'; owner: MemberRef; unitPrice: Coins /*centi*/;
                    amount: number; filled: number; claimedFilled: number; createdAt: number; expiresAt: number;
                    escrowCoins?: Coins; isMarketMaker?: boolean; }
interface BzProduct { productId: string; category: 'FARMING'|'MINING'|'COMBAT'|'WOODS_FISHES'|'ODDITIES';
                      group: string; npcSellPrice?: Coins; npcBuyPrice?: Coins; avg7d: Coins;
                      bids: PriceLevel[] /*desc*/; asks: PriceLevel[] /*asc*/; }

// Auction House
interface Auction { id: UUID; seller: MemberRef; profileId: UUID; item: ItemStack; bin: boolean;
                    startingBid: Coins; topBid?: { bidder: MemberRef; amount: Coins; ts: number };
                    bids: Bid[]; createdAt: number; buyableAt: number /* +20s for BIN */; endsAt: number;
                    state: 'ACTIVE'|'SOLD'|'EXPIRED'|'CANCELLED'; sellerClaimed: boolean; buyerClaimed: boolean;
                    feesPaid: Coins; }

// Calendar & election
interface SbTime { year: number; month: 1|2|3|4|5|6|7|8|9|10|11|12; day: number; hour: number; minute: number; }
interface ScheduledEvent { id: string; when: (t: SbTime) => boolean; durationSbDays: number; mayorGate?: PerkId; }
interface Election { year: number; candidates: { mayorKey: string; perks: PerkId[]; ministerPerk: PerkId; votes: number }[];
                     ballots: Map<AccountId, { mayorKey: string; weight: number; ts: number }>; }
interface ActiveGovernment { year: number; mayor: string; perks: PerkId[]; minister?: { key: string; perk: PerkId }; }
```

**Perks as data, not code branches.** Every number a mayor can change should be a lookup through one
`EconomyConstants` table resolved against the active government, e.g.
`constants.bazaarTax(member)` = `base 0.0125 − 0.00125×flipperTier` × (Derpy ? 4 : 1). That keeps
Jerry's Perkpocalypse (swap all perks every 6 h) a one-line change.

### 11.2 Anti-dupe

| Risk | Rule |
|---|---|
| Item dupes via concurrent GUIs | Every non-stackable item has a **UUID**; a UUID may exist in exactly one container. Moving it is a single DB transaction that asserts the source row still holds it |
| Coin dupes | Coins only move through `ledger.transfer(from, to, amount)` inside a transaction with row locks on both wallets; **sum of all balances + sinks − faucets** reconciled nightly |
| Claim twice (AH/Bazaar) | Claims are **idempotent**: a claim flips `sellerClaimed=true` with `UPDATE … WHERE sellerClaimed=false` and pays only if 1 row changed |
| Cross-server races | One authoritative economy service (or DB) — game servers never hold a cached purse they can spend locally. Bazaar matching is single-writer per product (actor/queue per productId) |
| Disconnect mid-trade | The trade is either committed atomically or not at all; on disconnect, nothing moves |
| Server crash | Write-ahead: escrow is taken *before* an order/auction is visible; recovery replays from the ledger |
| Rollbacks | Ledger is append-only; admin restore = compensating entries, never edits |
| Stackable-to-unique conversion | When a Bazaar item is crafted into a unique item, assign the UUID at craft time on the server |

### 11.3 Anti-scam

| Scam | Mitigation |
|---|---|
| Trade item swap at the last second | Reset both accepts on any change + 3 s accept cooldown (?) + re-validate at commit |
| Look-alike items / renamed items | Disallow anvil renaming of display names (or show true item ID + rarity + "Renamed" tag) |
| Coin-decimal confusion (1.25M vs 12.5M) | Show full digits + abbreviation + words |
| AH mispriced BIN sniping by bots | 20 s BIN grace period |
| Bait listings (BIN 100M on a common) | COMMON items capped at 5× NPC sell price |
| Bazaar fat-finger | 2× / 50 % of 7-day-average confirmation prompts; sell offers ≤ 10× average |
| Co-op bank drain | Personal banks, withdrawal log, owner role |
| Money laundering / RMT | Trade daily coin cap by SB level; AH/Bazaar logs; alerts on value-asymmetric trades between unrelated accounts |
| Fake "middleman" / "trust trades" | Server-side escrowed trades already remove the need; display a warning on trades where one side is empty |

### 11.4 Concurrency & performance

- **Bazaar:** an in-memory order book per product (price → FIFO queue) owned by one worker; persist each
  fill as a ledger + order-row update in one transaction; publish snapshots (`sell_summary`/`buy_summary`/
  `quick_status`, top 30 levels) every 10–20 s to a cache for GUIs and the public API. GUIs read snapshots;
  only order placement/cancel/claim hits the worker.
- **Instant buy pricing:** reserve `quote × 1.04` coins in escrow, execute against the live book, refund the
  difference — exactly Hypixel's behaviour and race-safe.
- **AH:** listings in a DB with indexes on (category, rarity, bin, endsAt, lowest price per itemId);
  the browser is served from a cache refreshed every ~60 s (Hypixel's API is ~60 s stale). Bids take a row
  lock on the auction; anti-snipe extension is computed inside the same transaction.
- **Ending auctions:** a scheduler sweeps `endsAt <= now AND state='ACTIVE'` every second, marks SOLD/EXPIRED
  — claiming is separate and lazy.
- **Calendar:** derive SB time from wall clock + epoch (never store a ticking counter); events are pure
  functions of SbTime; a scheduler fires `onStart/onEnd` hooks with **at-least-once** semantics and
  idempotent handlers (e.g. interest keyed by `year*4+season`).
- **Interest:** batch job at each season boundary; per-profile row `lastInterestKey` prevents double pay.
- **Daily limits:** counters keyed by `(member, itemId, utcDate)` with TTL; check-and-increment atomically.
- **Election:** ballots keyed by account; vote weight snapshotted at **close** (so fame changes mid-election
  are applied) or at cast time (?) — pick one and document it. Tally incrementally for live % bars.

### 11.5 What the player sees (GUI checklist)

| Screen | Must show |
|---|---|
| Scoreboard (sidebar) | SB date ("Late Summer 27th"), SB time with ☀/☽, location, **Purse**, Bits, current event countdown ("Spooky Festival: 12m"), objective |
| Bank (Banker) | Balance / cap, tier, next interest ETA, deposit/withdraw options, transaction log, personal bank toggle |
| Bazaar | categories, search, product order books (top levels), instant buy/sell with total and tax, order creation wizard, Manage Orders with fill % and claim |
| Auction House | browse with 6 categories, search, rarity, sort, BIN filter; create (fee preview), manage, bids, claim all |
| Trade | 16 slots per side, coin field, both "Accept" states, cooldown indicator, partner's SB level, warnings |
| Calendar (SkyBlock Menu → Calendar & Events) | Current date, month grid, upcoming events with real-time countdowns, upcoming Jacob crops, current Mayor + Minister perks |
| Election booth | Candidates, perks (minister perk marked), vote %, your vote, time left |
| Community Shop | Cookie, bits shop, upgrades with build timers, fame rank & next rank |
| Death message | "You died and lost 12,345 coins!" (or "Your Booster Cookie saved your coins") |

---

## 12. Progression placement

| Stage | Economy & calendar features unlocked / relevant |
|---|---|
| **First hour** (SB Lvl 0–3) | Purse, NPC selling (main income), NPC shops & buyback, death loss lesson, Bank (Starter, deposit early), calendar on scoreboard, first Jacob contest seen (needs Farming 10) |
| **Early game** (SB Lvl 3–10) | **Auction House** (buy first gear), **Bazaar at SB 7** (sell enchanted mats), Coins Allowance upgrades, first Spooky/Jerry/New Year cake, Traveling Zoo (cheap COMMON pets), voting (1 vote) |
| **Early-mid** (SB Lvl 10–50) | Booster Cookie (free one, then buy) → bits, fame; Jacob contests (Farming 10+) and medals; Bazaar flipping; bank → Gold tier; trade cap 50M/day; Mining Fiesta, Fishing Festival (sharks from Fishing 5) |
| **Mid** (SB Lvl 50–150) | Trade cap 1B/day (SB 50); Deluxe/Super Deluxe bank (Museum 3/6); Dark Auction (≥ 400k entry); Bazaar Flipper II; Anita's Farming cap; Shen's Auction (Museum 10); Zoo LEGENDARY pets (10M) |
| **Late** (SB Lvl 150+) | Trade cap 10B/day (SB 100+); Premier → Palatial bank; Shen's Special (Museum 20); Darker Auctions; Fame ranks with 50–100 votes; Artifact of Control (224 votes) |
| **Side modes** | Ironman (self-sufficiency, convertible after 35 days), Bingo (monthly 7-day sprint, 10 slots carry-over), Stranded (EOL — skip in clone or treat as a later "hardcore island" mode) |

**Recommended build order for the clone's economy team:**
1. Ledger + wallets + bank (with interest job) + death loss.
2. NPC shops with daily limits and buyback (gives the floor every other market needs).
3. SB calendar service (pure function of wall clock) + scoreboard date.
4. Auction House (simpler correctness than Bazaar).
5. Bazaar with market-maker bots (§2.9).
6. Trade with safety rules.
7. Election + `EconomyConstants` perk resolution (start with 4 mayors: Diaz, Derpy, Marina, Finnegan).
8. Events (Jacob hourly → Spooky → Jerry/New Year → Zoo → mayor festivals).
9. Cookie, bits, fame, Community Shop with token currency.
10. Profile types (Co-op first; Bingo second; Ironman third).

---

## 13. Open questions / low-confidence items

| Item | Status |
|---|---|
| Bank Gold-collection thresholds (100K–3M) and personal-bank interest rule | From wiki text; unusual — verify (?) |
| Museum interest bonus cap (+60 % vs +72 %) | Sources disagree (?) |
| AH duration fees for presets (20/45/100/350/1,200) and whether BIN pays duration fee | From memory (?) |
| AH minimum bid increment (2.5 % per current wiki vs historic 15 %) | Using 2.5 % |
| Bazaar tax ×4 under Derpy | Confirmed only for AH collection tax (?) |
| Trade accept cooldown length, request expiry | Not documented (?) |
| Dark Auction bid steps; Darker Auction round count (7 vs 8) | Not documented (?) |
| Jacob's bits per medal (only Diamond 80 confirmed), Anita/Jacob shop prices | (?) |
| Fear Mongerer candy prices; lower Spooky leaderboard tiers | (?) |
| Bazaar product count (~1,400?) | (?) |
| Aatrox 4th perk | (?) |
