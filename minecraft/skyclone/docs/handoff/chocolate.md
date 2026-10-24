# chocolate — hand-off

New module (wave 5): the Chocolate Factory idle game and **Hopsworth's Hunt** (the reference's Hoppity's Hunt),
research/08 §6.6. Entry class `com.skyclone.mod.chocolate.ChocolateModule` — **the integrator must add it to
`core.ModuleList`** (anywhere after `accessories`, `bank`, `menus`; it only reads their services at use time).

## Wave 5 (2026-10-06)

### Done
- **Data, generated** (`tools/gen/chocolate/gen_chocolate.py` → `data/chocolate/{rabbits,items,factory,shop}.json`,
  `docs/names/chocolate.md`): all **577 rabbits** from NEU `hoppity.json` (244/142/94/53/28/11/5 by rarity, per-rarity
  CpS + multiplier, NEU specials for Dante/Galaxy/Zest Zephyr), island **residents** (64, SkyHanni), the 26 **factory**
  and 24 **shop milestones**, the 28 **Bounty Hunter** (Rabbit Hitman) slot costs, head textures for every rabbit,
  prestige multipliers (CF1..CF6 +0 / .1 / .25 / .5 / 1 / 1.5) and requirements (150M / 1B / 4B / 10B / 30B),
  chocolate talisman CpS (10..50). Every rabbit has OUR name (Greek-letter rabbits keep their plain-word names).
- **Factory menu** (`/cf`, aliases `/chocolatefactory` `/chocolate`), reference slot layout: cookie 13 (click; anti-
  autoclick cap), prestige 27 (+ confirm), 7 employees 28-34 (Bro..Dog, +1..+7 CpS/level, click = +1, shift = +10,
  caps 120..220 by tier, Rabbit Youth attribute raises Bro/Cousin/Sis), barn 35, Hand-Baked 37 (`500 × (L−1)`),
  Time Tower 38 (CF2+, 3 charges / 8 h, +0.1x per level for 1 h, left = activate, right = upgrade), Rabbit Shrine 39
  (CF3+, +1%/level rare odds, max 20), Coach Longstride 41 (CF4+, +0.01x/level, max 20), production breakdown 45,
  Collection 46, Shop 47, Back 48 (SkyBlock Menu), Hunt 50, Bounty Hunter 51, Leaderboard 52, Milestones 53.
  Live redraw every second; **stray rabbits** (20% per 30 s open, 15 s to click, 5-50 s of production) and **golden
  strays** (Jackpot 480 s, Chocolate Mountain 1,500 s, Golden Click, Goldenpaw = the El Dorado rabbit after 3).
- **Production** = (employees + rabbits + talisman + Rabbit Crew shard) × (1 + prestige + rabbits + coach + Boon
  Cookie 0.25 + Time Tower + Hot Chocolate Mixin 0.05) × (1 + Chocolate Speed shard), paid every second online and
  **offline on login** (the Time Tower window is split exactly — `ChocMath.produce`).
- **Prestige**: resets chocolate, employees, Hand-Baked; keeps rabbits, barn, tower/shrine/coach. Pays SkyBlock XP
  25 per tier (research/02: prestige 25 each), 10 per Mythic and Divine rabbit, keyed so it never pays twice.
- **Rabbits**: new = permanent CpS/multiplier (needs barn room); duplicate = seconds of production (rarity table) ×
  (Masked Hopper +20%, Foxy's `foxy.sweet_benevolence` +30%), or 5% of the time the tradeable `FACTION_RABBIT_*` item
  (60 registered, right-click "Move In" adds it). Mythic perks implemented: Time Tower charge, Tower duration, golden
  stray rate, stray rate, stray linger, click bonus, unique chance, duplicate bonus.
- **Hopsworth's Hunt**: calendar event `hoppitys_hunt` (Early Spring 1 → Late Spring 31, 93 days). Six meals
  (Breakfast 7am, Brunch 10am, Lunch 2pm, Dejeuner 5pm, Dinner 9pm, Supper 12am; side dishes are a dial). Each meal
  spawns ONE egg per listed island at one of up to 15 hiding places and lives until that meal spawns again; a player
  claims each meal once per spawn. Eggs are shared non-persistent `ItemDisplay` + `Interaction` entities shown only to
  players who still need them (right- or left-click). Egg = rarity roll (Shrine, Rabbit Luck shards), 10% island
  resident, talisman/Omega "unfound" re-roll, 0.1% Chocolate Dye. Missed meals go to the **Rabbit Bounty Hunter**
  (also across offline time); Egg Finder shard adds eggs; 0.5% of catches during the Hunt fish up an egg.
  Hiding places: `egg.*` anchors when an island has ≥3, otherwise near its other anchors on real ground.
- **Egg Seeker** (`EGGLOCATOR`): free once from Hopsworth, points a particle line + action bar at the nearest egg.
- **Hopsworth** (`/hoppity`, `/hopsworth`): today's eggs, Egg Seeker, collection, daily rabbit offer for coins
  (at the NPC, or anywhere once you gave him a **Steamed Chocolate Fish** → `Features.Abiphone.addContact("hoppity")`).
- **Chocolate Shop** (`/chocshop`): Egg Seeker, Steamed Chocolate Fish, Chocolate Dye, Chocolate Syringe, Barn Skin,
  the five chocolate talismans (once each, CF2..CF6), Masked Hopper's Cape (needs the Masked Hopper rabbit),
  Hot Chocolate Mixin (shown once alchemy registers it). Spending feeds shop milestones.
- **Collection** (`/rabbits`, paged, rarity filter, hints for unfound), **Milestones**, **Leaderboard** (DB table
  `chocolate_leaderboard`, all reads/writes on the DB thread), **Bounty Hunter** (`/bountyhunter`).
- Requirement prefix **`chocolate`**: `chocolate:prestige:<n>`, `chocolate:rabbit:<id>`, `chocolate:rabbits:<n>`.
- Sidebar section `chocolate.hunt` (priority 400, one line, only during the Hunt; Settings flag `sidebar.hoppity`)
  and tab widget `chocolate`.
- Verified: `bash tools/check.sh chocolate` → CHECK OK; **9 JUnit tests** (`ChocMathTest`) green when run with the
  JUnit launcher outside gradle; generator re-run is deterministic. The server was NOT started (lane rule).

### Still left
- Hopsworth is only placed when hub anchor `npc.hoppity` exists (see Requests); until then `/hoppity` is the way in
  and his daily offer needs the Abiphone contact.
- No SkyBlock Menu button yet (menus owns it — Request 2).
- Barn Skin is lore-only (no Garden barn-skin system to hook). Chocolate Dye is lore-only (no dye system).
- Reference features not built: the "Chocolate Factory" leaderboard by prestige tiers (ours is one all-time list),
  the City-faction event that hands out faction rabbits (ours drop from duplicates), Rabbit the Fish as a sea creature
  (ours: a flat chance per catch), rabbit "shards" from hunting beyond the attribute reads.

## Commands
Player: `/cf` (`/chocolatefactory`, `/chocolate`), `/hoppity` (`/hopsworth`), `/rabbits` (`/chocolaterabbits`),
`/chocshop` (`/chocolateshop`), `/bountyhunter` (`/rabbithitman`).
Admin `/sb chocolate`: `info <p>` · `give|set <p> <amount>` · `prestige <p> [level]` · `rabbit <p> <id|all|random [n]>` ·
`egg <p> [island]` (open an egg) · `eggs` (where every egg is) · `respawn-eggs` · `stray <p> [golden]` (factory open) ·
`tower <p>` · `bounty <p> <eggs>` · `upgrade <p> <employee|hand|barn|tower|shrine|coach> <level>` · `reset <p>` ·
`leaderboard`. Hunt on/off: `/sb calendar force hoppitys_hunt on|off`.

## Dials (`/sb dial list chocolate`)
`production-scale`, `cost-scale`, `cookie-bonus` (0.25), `stray-chance` (0.20), `stray-interval-seconds` (30),
`stray-life-seconds` (15), `golden-chance` (0.02), `golden-click-seconds` (3), `resident-chance` (0.10),
`unique-base` (0), `dye-chance` (0.001), `faction-chance` (0.05), `fish-egg-chance` (0.005), `offline-production`,
`offline-cap-hours` (0 = none), `click-cap` (20/s), `side-dishes`, `egg-spots` (15), `egg-finder-seconds` (750),
`leaderboard-minutes` (5), `tower-minutes-bonus` (0), `announce-hunt`, `hunt-islands`, `npc-island`, `npc-anchor`.

## Self-tests
`chocolate.catalog`, `chocolate.items-exist`, `chocolate.formulas`, `chocolate.egg-clock`, `chocolate.egg-roll`,
`chocolate.milestones`, `chocolate.menus-build`, `chocolate.hunt-islands`.

## TUNE (design values where the research has none)
Employee hire costs (50 / 200 / 600 / 1.5k / 3.5k / 7k / 14k) and growth 1.06; employee unlock tiers (Uncle CF3, Dog
CF5); level caps 100 + 20 × tier; barn growth 1.04; Time Tower / Shrine / Coach base costs and growth; egg rarity
odds (45/30/15/7/2.5/0.4/0.1%); duplicate payouts; resident chance; golden stray kinds/weights; talisman prices and
tiers; shop prices; Hopsworth's offer prices; mythic perks (except the data's flat numbers). Legendary rabbits give
0 CpS + 0.02x per NEU (research/08's table says +10 CpS; NEU wins, flip in the generator if wrong).

## Requests
1. **hub**: add anchor `npc.hoppity` (reference Hoppity stands at ~56, 70, −3 in the village). Optional for every
   island builder: `egg.<n>` anchors (≥3) for hand-picked egg hiding places.
2. **menus**: a "Chocolate Factory" button in the SkyBlock Menu (run `cf`), and the Settings flag `sidebar.hoppity`.
   The calendar menu already lists `hoppitys_hunt` like any registered event.
3. **accessories**: add requirement `chocolate:rabbit:zorro` to `ZORROS_CAPE` (lore: "Requires Masked Hopper in
   Hopsworth's Collection"); the chocolate talismans (`extra.hook = events`) are applied here via
   `Features.Accessories.has`.
4. **alchemy**: register `HOT_CHOCOLATE_MIXIN` (+0.05x factory while active; any effect id containing
   `hot_chocolate` is read from `Features.Potions.active`), requirement `chocolate:prestige:6`.
5. **hunting**: attribute keys read via `attributeLevel`: `chocolate_speed` (+0.25%/lvl), `rabbit_crew` (+5 CpS/lvl),
   `rabbit_youth` (+1 cap/lvl), `egg_finder` (one Bounty egg per 750 s / lvl), `legendary_rabbit_luck`,
   `mythic_rabbit_luck`, `divine_rabbit_luck` (+1%/lvl). Rename here if your ids differ.
6. **materials / minions**: give `ENCHANTED_TIME_CLOCK` an ability with id `time_warp`; chocolate already pays one
   hour of production on `AbilityUsed` for that id.
7. **core (optional)**: a `Features.Chocolate` (chocolate, spend, add, prestige) if any other module wants to price
   things in chocolate; today only this module reads it.

## Known risks
- Egg entities rely on per-player `showEntity/hideEntity`; untested with a real client (the Interaction box may need a
  different size on slopes). `/sb chocolate eggs` prints every egg and its spot for the live check.
- Egg hiding places are derived from anchors + a ground search; an island with few anchors gets few places, and a
  spot on a roof is possible. `egg.*` anchors fix both.
- Offline production is uncapped by default (`offline-cap-hours` 0); a long absence pays a lot by design.
