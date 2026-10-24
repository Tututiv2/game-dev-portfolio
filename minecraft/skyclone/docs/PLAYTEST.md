# SkyClone — playtests

One section per pass. Each pass plays the real game in a real 26.1.2 client driven by **TestPilot**
(`tools/testpilot/`, never OS input), on the dev server (port 25630). Screenshots live in
`docs/playtest/<pass>/`.

---

## Pass "newplayer" — 2026-10-03 (about 01:35–02:45, server time)

**Who:** `NewbieNP` (fresh profile, played from first join), `NewbieNP2` (second client on
`TESTPILOT_PORT=25621`, for the auction/trade tests), `NewbieNP3` (fresh profile, to verify the
join fix). Screenshots: `docs/playtest/newplayer/p2_*.png` (the `01_..41_` files in the same folder
are from an earlier pass that was cut short on 2026-10-02 and never written up).

**Rules kept:** TestPilot only. Rocket League was not running at any launch. `focuswatch.py` ran
the whole session (`--any-java`): **0 violations**, the test clients never became the foreground
window (log: `docs/playtest/newplayer/focus_watch.jsonl`). One slip: the first launch of the second client used `TESTPILOT_POS=200,200`, which is on
the **primary** monitor; it was killed and relaunched on the secondary monitor about 30 s later
(it never took focus). Do not pass `TESTPILOT_POS` without knowing the monitor layout.

**Gates:** `tools/check.sh all` CHECK OK (847 files) · `gradlew build` and `gradlew test` green, before
and after the fixes · boot 35/35 modules, 0 FAILED · the hub was rebuilt twice for the well fix
(5.13M blocks, 24 s each).

### What was played

**First hour, no admin help.** Join → private island (survival mode, confirmed) → Gerald (dialogue,
then a menu: starter kit = Rookie Pickaxe, Foraging Axe, Hoe, Bread x8; a second claim is refused) →
chop the oak (logs drop as items, +6 Foraging each) → 2x2 inventory craft planks → crafting table →
placed it → **Craft Item** menu (sticks, wooden pickaxe; items left in the grid come back on close) →
**Recipe Book** (categories, Craftable Now, recipe page, Supercraft from inventory) → mine 5
cobblestone by the pond → SkyBlock Menu → **Skills** and **Collections** menus → place the
Cobblestone Minion (menu, layout, production, storage) → walk through the island portal to the Hub →
Hub Guide (Town Crier, Hub Map with directions) → Banker (deposit half, withdraw half, transaction
log) → Builder merchant (sell planks, buyback, buy stone, buy-amount menu) → Bazaar NPC (refused,
level 7) → Auction House (create BIN, price typed in chat, duration, confirm) → Fairy (by the pond) →
first Fairy Soul → Farm (Hub Map compass directions) → harvest wheat (Farming I) → Graveyard zombies
(died once, see issues). The chain then asks for Combat II (about 30 zombie kills); I stopped grinding
there.

Movement: walking measured 12.3 blocks in 62 ticks = 4.0 blocks/s, i.e. vanilla. The 7x speed bug is
gone.

**Then, with admin shortcuts** (`op`, `/sb hub discover`, `/sb give`, `/sb progression xp` to level 8,
`/xp`): two-client **auction** (race cancel-vs-buy, bid auction, seller claim, buyer claim after
relog), two-client **/trade** (items + coins, race accept-vs-withdraw, disconnect mid-trade), **Bazaar**
(instant buy, instant sell with a typed amount, buy order, sell offer that crossed the book, resting
sell offer, cancel both with refunds), **Accessory Bag**, **Storage** (ender pages, backpack slot,
backpack contents), **Wardrobe** (store a set, equip, unequip, try to pull a worn piece out),
**Sacks** (insert inventory, take out), **Pets** (learn a pet item, summon, convert back), **Blacksmith**
reforge (first reforge costs 10 Coal), **Enchanting table** (Efficiency I), **Alchemist** shop and
brewing stand (3 Awkward Potions, brewing continues while closed), minion collection after 40 min.

### Dupe / loss attempts (all held)

| Attempt | Result |
|---|---|
| Close the Craft Item grid with 11 planks + 2 sticks in it | all returned |
| Stage an item in Create Auction, close, reopen; shift-click spam the staged slot 10x | item kept safely in the slot, then returned once |
| Seller cancels while buyer buys the same BIN (two clients, same tick) | one winner (cancel), buyer told "auction has ended", no coins moved |
| Seller spams Claim All / claim | paid once |
| Trade: accept while the other side pulls an item back (same tick) | accept invalidated ("trade just changed") |
| Trade: kill the other client mid-trade | both sides' items returned; the killed player got "Returned 3 items from a trade that did not finish" on relog |
| Accessory bag: close with a talisman on the cursor; shift in/out spam 12x | counts conserved (3 talismans) |
| Ender chest: switch page with an item on the cursor | item returned to inventory |
| Backpack: **disconnect with an item on the cursor** | item back in inventory on relog, backpack contents intact, `/sb storage scan` clean |
| Wardrobe: click / shift-click a worn piece shown in the wardrobe | locked |
| Bazaar: double-click Submit Buy Order | one order |
| Sacks: right-click "fill inventory" spam | exact counts |
| Pets: convert-to-item click spam | one item |
| Reforge: close with the item in the slot | returned |

### Fixed during this pass

| # | Module | Problem | Fix (verified how) |
|---|---|---|---|
| 1 | progression | **"Go to the Hub" completed on the first join**, before the player had even reached the island (a new player logs in in the overworld/hub for a moment while the island is made; the fire-and-forget island trigger caught it). The step was skipped for good, so after Place a Minion the chain said "Talk to the Banker — the Bank is in the Village" to a player still on their island | `Objectives.onIsland` ignores the first island of a session (`from == null`); the poll still completes the step if it is current. Verified with a fresh account `NewbieNP3`: no completion on join, `go_to_hub` pending, completed when they actually warped to the Hub |
| 2 | progression data | Pickaxe hint said "Any pickaxe of your own works", but the starter kit's Rookie Pickaxe (stone) does not count — only a Wooden Pickaxe does | hint now says how to make one (3 planks over 2 sticks) |
| 3 | gen `Builders.well` (+ hub v4) | **The village well is a trap.** `footing()` turned the lower water block into cobblestone (water is not solid), so the shaft was 1 deep under a rim 2 blocks above the player's feet: nobody can jump out, and the Hub forbids breaking blocks — only `/hub` gets you out. It sits on the straight walk from the Village to the Farm and the Fairy Pond (I fell in) | shaft is 2 deep and has a ladder up the inside of the north rim; hub generator bumped to 4 and rebuilt. Verified: fell in, climbed the ladder out. **Other islands that use `Builders.well`** (private island template, Barn, Mushroom Desert, Spider's Den, Bayou, Marsh, Garden, Dwarven Mines, hub extras) get the fix only when their generator version is next bumped — on the private island a player can at least dig out |
| 4 | bazaar `MarketMaker.shopPrice` | **Money printer.** The bots capped their asks at the raw NPC shop price, which for Wheat is 2.3 while every merchant pays 6.0 (also Carrot, Potato, Pumpkin — the four "printers" the shops module already floors). I bought 64 wheat for 147.2; the same Bazaar page shows "NPC sell price: 6 coins", i.e. 384 at any merchant (merchants sell at `ItemDef.npcSell`; I did not complete the merchant sale in the client) | the ceiling never goes below the NPC sell price. After restart: Wheat book 5.7 / 6.0, Carrot 2.9 / 3.0 |
| 5 | reforges | "reforged your Wooden Pickaxe into **a Excellent** Wooden Pickaxe" | a/an chosen from the name |

### Open issues

| Module(s) | Severity | What I did | Expected | Actual |
|---|---|---|---|---|
| core loot + island + mining + collections | **major** | Chopped 5 oak logs and mined 6 cobblestone/stone on the private island, picked the drops up, opened Collections and Skills | Oak Log and Cobblestone collections progress; Mining XP for cobblestone (the reference gives both on the island) | Collections "Discovered 0/90"; Mining 0 XP (Foraging XP did arrive, because foraging has its own private-island handler). Vanilla block drops picked up on a BUILD island never fire `ItemGain`, and `DigEngine.mineable` excludes the private island. Consequence for a new player: the Cobblestone Minion's Quick Upgrade needs Cobblestone Collection I, which only the minion's own output can give (it did, after 40 min). Needs placed-block tracking so place-and-break cannot farm collections |
| hub (geometry) | major | Walked from the square to the Bank, Bazaar, Builder and Auction House | Doors face the street the player arrives from | Each building shows a wall of windows to the square; doors are round the side (Bank east x=-22, Bazaar east x=-24, AH west x=22, Builder north z=-22). I walked into glass at all four. The Hub Map lists districts but not the Village buildings |
| hub / progression | minor | "Talk to the Fairy: a fairy lives by the pond in the Hub" | Findable | The Hub Map has no Fairy Pond entry and the hint has no direction. Walking in from the village crosses the sunken pond; its banks are 2 blocks high on the north side and I drowned from 105 to 28 HP in about 10 s before finding the east bank |
| progression / bazaar | minor | Followed "Visit the Bazaar" at SkyBlock Level 0 | A way forward | The agent only says "Requires SkyBlock Level 7!" (the gate is research-faithful). Say how to earn SkyBlock XP (/sblevel) |
| combatzones / progression | minor | "Slay Zombies in the Graveyard" with the starter kit (no sword; I had a Wooden Pickaxe, 0 Defense) in daytime | Survivable | Killed by Lv1 Graveyard Zombies after 3 kills (each takes 9–12 hits); lost 279 coins (half the purse). With a Stone Sword and 15 Defense it was fine. The starter kit or the step's hint should give or suggest a sword |
| auction | minor | Listed a Wooden Sword (COMMON) | Profit possible | COMMON items are capped at 5x their NPC value (5 coins) while the cheapest duration fee is 20 coins: every COMMON listing loses money |
| accessories, storage | minor | Added 3 talismans to the bag; put armour into a wardrobe slot | Info icons update | "Accessory Power 0 / Slots 0/9" and "Slot 1: Empty" stay stale until the menu is reopened |
| alchemy | minor | Shift-clicked a stack of 3 Water Bottles into the brewing stand | One per bottle slot | All 3 went into the ingredient (top) slot |
| materials (generator) | minor | Read lore of Stick, Book, Glass Bottle, Brewing Stand, Bread... | A description that fits | 304 vanilla-derived items say "A common building block." (`items_blocks.json`, `tools/gen/materials`) |
| shops | minor | Builder, Stone & Brick tab | Distinct names | Two "Smooth Sandstone" lines: `SANDSTONE:2` (cut sandstone, 4 coins) and `SMOOTH_SANDSTONE` (3 coins) |
| shops | minor | Right-click a shop line | Go Back | "Shop Trading Options" has only Close |
| bazaar | minor | Sell offer at 2.2 that crossed the book | Accurate text | "13 sold instantly at a better price!" — it filled at the same price |
| bank | minor | Withdraw half of 102.5 | Consistent rounding | Shown "Withdrew 51.2", "51.3 remain"; purse 203.75 shown 203.7 |
| island / mining | minor | First join | — | "+35 SkyBlock XP (Heart of the Mountain Tier 1)" arrives before the player has done anything |
| core ui | minor | Sidebar | — | Footer is the placeholder `www.skyclone.example` |
| hub | unverified | `/sb npc list` | — | About 19 hub NPCs report "not spawned" (lumber merchant, fishing guide, portal keeper, ship captain, carnival, contest host, dark auction...). Probably chunk-lazy spawning; not checked in the client |

### Verdict

The first hour **is a game**: the objective chain is clear and paced (each step names the next, the
sidebar always shows it), crafting/recipe book/minion/bank/merchant/AH/bazaar menus are complete,
readable and safe, and every free-slot menu survived the dupe attempts, including disconnects. Survival
mode and normal walking speed are confirmed. What hurts a new player most is **the Hub itself**: doors
on the far side of buildings, a fairy behind a pond you can drown in, and (now fixed) a well you could
not leave. The one structural gap is that **nothing gathered by hand on the private island counts
toward collections**, and cobblestone gives no Mining XP there, so the island part of the tutorial
earns less than it should. The Bazaar money printer is fixed.

**State left behind:** `online-mode=true` restored and the server restarted and left running;
`NewbieNP` and `NewbieNP2` de-opped (`NewbieNP3` was never opped); all three test clients killed.
Their profiles (a level-8 `NewbieNP` with items from `/sb give`) remain in `skyclone.db`.

---

## Pass "loops" — 2026-10-03 (about 03:00–05:25, server time)

**Who:** `LoopsLP` (one client, TestPilot on 25620). Screenshots: `docs/playtest/loops/NN_*.png`.

**Rules kept:** TestPilot only. Rocket League was not running at any of the 7 launches. `focuswatch.py --any-java`
ran the whole session (about 2 h 20 min): **0 violations**, the client never became the foreground window
(`docs/playtest/loops/focus_watch.jsonl`). Only SkyClone's server (port 25630, its own PID each restart) was stopped
or started; ShadowPrison2 on 25610 was never touched.

**Gates:** `tools/check.sh all` CHECK OK (851 files) before; every touched module re-checked after each fix.
`gradlew build`: 520 of 521 unit tests pass, before and after my changes; the one red test is pre-existing
(`accessories.SellCapTest`, see issues). Boot 35/35 modules, 0 FAILED, on all 7 deploys. Live `/sb selftest`:
348 passed, 11 failed, all 11 "ingredient/item id not registered" data gaps (see issues).

**Admin shortcuts used** (everything else was played in the client): coins, Combat 20 → 24, Mining 5 → 12,
Enchanting 20, Farming 10, Fishing 15, HOTM tier 3 → 4, Garden V, gear by `/sb give` (Voidstep Blade, Young /
Superior Dragon armour, Titanium Pickaxe, Gemstone Drill, Wheat Sickle, rods, vacuum), the T3 slayer bars and the
second half of the Spider T1 bar by `/sb slayer progress`, the Mithril commission completed and the Forge timer
finished by admin, God Potion + pet level 100, and the slayer damage/HP dials (restored to 1 afterwards).

### What was played

**Slayer.** Brannoc (2 clicks: dialogue, then the menu) → Slayer menu → Ghoul Horror tier page (5 tiers with HP,
damage, abilities, XP, cost, locks) → **T1 Zombie**: confirm, 150 Combat XP of Graveyard Zombies (23 kills, 68 s),
"SLAYER QUEST STARTED" box, boss spawn with boss bar + timer, kill, level-up box with recipe unlocks, Ghoul Flesh
loot. **T3 Zombie** via `/slayer`: bar, boss (400k), Life Drain, Pestilence; RNG Meter (per slayer, select Foul
Flesh, progress 210/3,093, ×1.1 boost). **T1 Spider** in the Spider's Den (9 kills by hand, rest admin), boss
one-shot, loot. **T3 Spider** (dials 0.2 HP / 0.25 dmg): Web Cocoon at 66% with egg sacs ("WEB COCOON — pop the
egg sacs", invulnerable until popped, "vulnerable again"), kill, level II. **Pets:** learned a Tiger pet item,
`/pets`, summon, it levels from Combat XP exactly (8 zombies × 6 = 48/660 at Legendary), Taming levels from pet XP.
**Enchanting:** Library table (bookshelf power 15/15, skill gates), Smite V for 30 levels (lore + stats update);
**anvil**: Critical VI book onto the sword (12 levels). **Reforge:** Anvil-keeper Orla → Blacksmith, first reforge
10 Coal, then 1,000 coins at RARE, possible-reforges list, a/an correct.

**Mining.** Gold Mine → walked into the Deep Caverns portal → Gunpowder Mines: gold ore breaks in ~4.5 s with an
Iron Pickaxe, +6 Mining XP, Fortune drops, bedrock placeholder, regen within ~30 s. `/mines` and `/lift` menus
(visited-layers rule explained), lift to the Obsidian Sanctuary → Dwarven Mines (sidebar Mithril Powder + Tailwind
event). Emissary → commissions (2 slots, milestones) → minecart to Royal Mines → 12 Mithril mined by hand (~2 s
each, +2 powder, counted 12/250) → completed by admin → claimed at the Emissary (+10,000 Mining XP, +1,300 HOTM XP
incl. daily bonus, +1,000 powder, new commission rolled). **HOTM:** spent tokens on Mining Speed, Mining Fortune,
Precision Mining, Mining Speed Boost (adjacency rule enforced, ability selected). **Forge:** Refined Mithril
(160 Enchanted Mithril, 6 h) started → finished by admin → claimed. **Crystal Hollows** (HOTM 4): 6 Amethyst with a
Gemstone Drill, ~4 s each, Gemstone Powder in sidebar, fuel shown on the action bar, Rough Amethyst drops.

**Farming.** Barn → Wheat Fields with Fibonacci's Wheat Sickle: drops 1–2 wheat + seeds per crop (1.44/block in
the simulator, matches the /fortune breakdown), instant replant to age 0, Farming XP, collection level-ups, tool
level-up. **Harvest Fair** (`/sb calendar force farming_contest on`): sign up with Harvest Warden Ambrose (menu with
live NPC leaderboards, upcoming fairs, medals, rules), farm wheat → sidebar "○ Wheat 481 · BRONZE #23/41" →
forced off → results box, participation ticket, personal best.

**Garden.** `/warp garden` (instance built in ~8 s) → Desk → Configure Plots (24 plots, gates, compost costs) →
plot 4 → Farm Presets → Wheat Lanes (5,000 coins, laid out instantly) → `/plottp 4`. **Composter:** added 4,185
organic matter + 6,000 fuel, skipped 11 min by admin, collected 1 Compost. **Visitor:** Plumber Jem arrived ~1 min
after the first visit, offer (160 Wheat → 120 Farming XP, 2 Garden XP, 14 Copper), accepted, two visitor
milestones. **Pest** (Garden V, admin spawn): Fly over plot 4, sidebar "Plot - 4 ൠ x1", killed with the Garden
Vacuum (+1,500 Farming XP, +1,000 coins, +5 bits).

**Fishing.** Hub lake with Rod of Champions: bites shown as "!!!", 3 catches, Raw Cod, a **Sea Witch** ("NEW SEA
CREATURE!"), killed for drops. **Backwater Bayou** (after finding open water past the shore plants): 6/6 bites at
11–18 s, Pufferfish, a **Dumpster Diver** (Bayou pool), killed. **Crimson Isle** (Combat 22 gate) → Cinder Shore
lava pool next to a hotspot (sidebar "✦ Hotspot: +1% ⛃") with the Starter Lava Rod: 8/8 bites, Magmafish, a
**Trophy Fish** (Plain Vanille SILVER, "NEW!"), `/trophyfish` menu with every fish's condition.

### Fixed during this pass

| # | Module | Problem | Fix (verified how) |
|---|---|---|---|
| 1 | progression (sidebar) | **No module's sidebar section ever showed.** The frame head is 9 of 15 lines and the always-present Objective (priority 100) took 3 more, leaving 1 line: the active slayer quest, Mithril/Gemstone powder, the Harvest Fair score, garden pests, crimson faction were all silently dropped | Objective is now the filler (priority 900): it shows when there is room, otherwise the live section wins (as in the reference). Verified: "Ghoul Horror III (0/2.4k) Combat XP", "Mithril: 22", "Wheat 481 BRONZE #23/41", "Plot - 4 ൠ x1", and the Objective still shows when nothing else is active |
| 2 | core combat (`MobSpawner.leash`) | **Crypt Ghouls (Lv30) and the Golden Ghoul (Lv60, 45k HP) followed me out of the Crypt and roamed the Village for good** (found at the Library door and the square). The leash only returns a stray to a cached spot in a *loaded* chunk; once the player leaves the area its chunks unload, the cache is cleared every 30 s, `pick()` returns null and the leash did nothing | A stray with nowhere loaded to go is despawned; its rule refills it when someone is near. Verified after restart: 0 ghouls outside the Crypt over several minutes in the Graveyard |
| 3 | core combat (`mob.leash-vertical`, new dial, 8) | **Lv60 Golden Ghoul and Crypt Ghouls walked the Lv1 Graveyard**: the Crypt box (y61–63) lies 13 blocks under the Graveyard, inside a 3-D 24-block leash sphere. My T3 boss fight on the surface died in ~10 s to them; an earlier death "killed by Crypt Ghoul" right after a T1 kill was the same thing | New dial: more than 8 blocks above/below the area box is a stray whatever the horizontal distance (0 = off). Verified: 15–17 ghouls, 0 on the surface over 4 samples |
| 4 | mining, fishing, pets, rift (17 call sites) | **Menus built with `Sky.menus().paged(...)` but never opened**, so the button does nothing, silently: Forge "Forge Recipes" and every empty forge slot ("Click to forge!"), the **Pickaxe Smith**, drill part pickers, the Mining Guide, the **Sea Creature Guide** (`/seacreatures`), Fishing Hotspots (`/hotspots`), Baits, "Fishing Here", Preferred Bait, the rod-part picker, Pet Supplies, the autopet island picker, pet item pickers, Rift soul lists | `.open(p)` appended at each site (string-aware script + 2 by hand). Verified: Forge Recipes opens (5 pages), Refined Mithril started from a slot and claimed; `/seacreatures` and `/hotspots` open |
| 5 | core combat (`MobListener`) | **Witch potions bypassed Defense.** Splash potions from sky mobs applied vanilla Instant Damage/Poison; the vitals safety net converts vanilla damage proportionally (1/34 hearts → 20 of 693 HP), ignoring Defense, and Poison ticks down to the vanilla floor. A Lv15 Sea Witch took a 693 HP / 706 Defense player to 2 HP, and killed one that kept fishing | Potions thrown by sky mobs no longer apply vanilla effects; a harmful one hits once for the mob's damage through the normal pipeline (Defense applies, safe zones respected). Verified: 25 s next to a Sea Witch → two hits of 4, no drain |
| 6 | mining (DigEngine, Finds, MiningEvents) + mayors (Mythology) | Seven hints sent with raw `sendActionBar`, overwritten by core's vitals bar every 5 ticks: "You need a tool with Breaking Power N", "drill out of fuel", lock-picking, raffle ticket, Tasty Mithril, burrow distance. Mining a gem with too little BP did nothing at all, with no message | `Sky.vitals().flash(p, ..., 40)`. Verified: "You need a tool with Ⓟ Breaking Power 7 to mine Amethyst Gemstone!" now shows |
| 7 | mining generator | Fuel tank lore read "-%d%% Pickaxe Ability cooldown" (format never applied) | `% cd` in `gen_mining.py`, regenerated; diff = exactly the 4 lore lines (-2/-4/-6/-10%) |
| 8 | slayer | "You are now a Apprentice!", "You are now a Skilled!" | "New rank: Apprentice!" (names mix nouns and adjectives) |
| 9 | progression, collections | "BESTIARY Fly I➜I (new family!)", "COLLECTION LEVEL UP Seeds ➜I" | shows "0➜I" |

### Open issues

| Module(s) | Severity | What I did | Expected | Actual |
|---|---|---|---|---|
| weapons + dungeons generators, core items | **major** | Held the Wyrmbreath Blade (AOTD) in the Graveyard | The reference gates dungeon gear by Catacombs level **only inside dungeons** | `"catacombs:12"` is in the item's normal `requirements`, so outside dungeons it gives no stats and every hit prints "Requires Catacombs Level 12!" (attack refused). 44 items carry such a requirement. Needs a separate dungeon-only requirement key |
| hub (crypt) + core spawner | **major** | `/warp crypt` with 600 HP / 700 Defense (twice) | A survivable arrival | Landed in the Golden Ghoul + Crypt Ghoul pack and died within ~1 s both times (half the purse each). The Graveyard has a "quiet arrival" gate radius and a chaser cap (`hub_graveyard_pack`); the Crypt has neither (all ghouls are vanilla zombies with a 35-block follow range) |
| mines (Goblin Burrows station) | **major** | Minecart to Goblin Burrows | A safe station | Died within seconds on arrival to a **Lv200 Goblin Cutthroat** (2,500 damage, 60k HP) among ~30 goblins. Stations should be SAFE zones or have an arrival gate |
| slayer (Ghoul Horror tuning) | major (pacing) | T3 Zombie with 600 HP, 700 Def | Lore "Damage: 120 per second" | Life Drain alone is 1.5× tier DPS as **true** damage every 4 s (180 at T3, a TUNE guess): ~45 true dps on top of melee + Pestilence, so 600 HP dies in ~13 s whatever the Defense. The tooltip should state it; the number should be reviewed |
| combatzones (Spider's Den) | major (pacing) | Spider T1 (250 Combat XP) by hand | Steady spiders | 25 low-tier spiders spread over 12 anchors across the island; after 9 kills nothing respawned within 40 blocks for ~4 minutes, and many sit on terrain the player cannot reach. ~8 minutes for 102/250 |
| materials generator + accessories/armor/crafting/garden/shops/skills/weapons/foraging/collections data | major | `gradlew build`, `/sb selftest` | Green | 11 live self-tests fail on unregistered ids: `X`/`Y`/`Z` (excluded on purpose by `^[XYZ]$` in `gen_materials.py` while Synthesizer and 3 armour recipes need them — also the red `SellCapTest`), `ENCHANTED_BONE_MEAL` (Farm Merchant starter stock), `CONDENSED_FERMENTO`, `GLACIAL_FRAGMENT`, `STRETCHING_STICKS` (excluded), `WOOD_SINGULARITY`, `FRESHLY_MINTED_COINS`, `AVARICIOUS_CHALICE`, `LUSHLILAC`, `GEOMETRIC_ODDITY`, `RECORD_13`, `LUCKY_CLOVER`, `HILT_OF_REVELATIONS`, `NECRON_HANDLE`, `LOTUS_DIAMOND`. Those recipes are uncraftable |
| hub (Tavern, Library) | minor | Walked to Brannoc and the Library | Doors facing the approach | Same as the newplayer pass: Tavern door is round the north side (68.5, 15.5), the front is glass; a sewer opening sits on the straight line from the Graveyard to the Library (fell in) |
| mining (forge) | minor | Forge Recipes | All 120 recipes | "forge recipes registered 76, skipped 44" (missing ingredient/result items) |
| shops | minor | `/sb selftest` | — | `shops.distinct-lines`: 18 Librarian lines all show as "Enchanted Book" (no enchant name) — same for the RNG Meter entry "Enchanted Book" (it is Smite VI) |
| farming (contest) | minor | Forced the fair off with 14 min left | — | Live sidebar said BRONZE #23/41; results said #37 of 41, no medal: the NPC farmers jump to their full 20-minute scores at the end while the live ranking compares partial ones. Fine for a natural end; misleading mid-contest |
| fishing (Sea Witch) | unverified | Kept casting with a Sea Witch alive | — | Died once and ended at 2 HP once before fix #5; not reproduced after the fix |
| mining | minor | `/sb mining forge` with no arguments | Usage | "Bad arguments: Index 0 out of bounds for length 0" |
| core vitals | minor | Swapped armour sets | — | Taking armour off clamps health to the lower max (596 → 117) and it stays low after putting the new set on |
| crimson/core | minor | Died in lava | — | "You died (lava)" while mob deaths say "(killed by X)"; capitalise / "burned in lava" |
| (self-inflicted, not a bug) | — | — | — | Vanilla `/clear <p> leather_chestplate` also removes worn Superior Dragon pieces (leather base items); a full inventory drops overflow on the ground, which inflated my first drop measurements |

### Verdict

The owner's priority loops **are all playable end to end** and most feel like the reference: every menu I
opened is complete and readable (slayer tiers with real odds, RNG meter, HOTM tree with adjacency, commissions,
forge, composter, visitors, plots and presets, contest board, trophy guide), rewards are visible as boxes and
action-bar ticks, numbers are reference-faithful (T1/T3 HP, Smite 30%, gold 600 strength, BP 7 for amethyst,
1–2 wheat per crop, pet XP curves). The custom block breaking feels right (timing, powder, fuel, regen), crops
replant instantly, bites come every 11–18 s, and sea creatures, trophy fish and lava fishing all work.

What made the session feel *unfair* rather than hard was the world around the loops, and the worst of it is fixed:
high-level mobs leaking out of their areas into beginner zones and the Village (#2, #3), witch potions that ignored
Defense (#5), the sidebar that never showed your quest, powder or contest (#1), and a dozen buttons that did
nothing (#4). Still open and worth doing next: arrival points that drop you into Lv60–200 mobs (Crypt, Goblin
Burrows), dungeon-only Catacombs requirements applied everywhere (AOTD and 43 others are dead weight), the T3
Ghoul's true-damage Life Drain, sparse Spider's Den spawns, and the ~15 missing ingredient items behind 11 red
self-tests.

**State left behind:** `online-mode=true` restored and the server restarted and left running with the fixes;
`LoopsLP` de-opped; the test client killed. `LoopsLP`'s profile (Combat 24, Mining 13, HOTM 4, Garden V, mid-game
gear from `/sb give`) remains in `skyclone.db`. The slayer dials were reset to 1.

---

## Pass "endgame" — 2026-10-03 (about 05:38–07:20, server time)

**Who:** `EndgameEG` (one client, TestPilot on 25620). Screenshots: `docs/playtest/endgame/NN_*.png`, every SkyBlock
Menu page as `menu_NN_*.png`, every warp landing as `warps/<warp id>.png` (contact sheets `warps/sheet_0..6.png`).

**Rules kept:** TestPilot only. Rocket League was not running at any of the 3 launches. `focuswatch.py --any-java` ran the
whole session: **0 violations** (`docs/playtest/endgame/focus_watch.jsonl`; the only foreground changes were the owner's
Chrome and Windows Terminal). Only SkyClone's server (25630) was stopped and started; ShadowPrison2 (25610) was not touched.

**Gates:** `tools/check.sh all` CHECK OK (853 files) before and after; `gradlew build` (with tests) green before and after;
boot 35/35 modules, 0 FAILED, on all 3 deploys; live `/sb selftest` after the fixes: **362 passed, 0 failed** (the 11
red ones from the "loops" pass are gone).

**Admin shortcuts used** (everything else was played in the client): all skills 30, Catacombs 20 then 30, coins, gear by
`/sb give` / `/sb armor give` / `/sb dungeons gear` (Superior Dragon, Morvane's set, Highlord + the three scrolls, Hexbound
Shortbow, Summoning Eyes, arrows), F7 marked complete with `/sb dungeons end complete` (for the Morvane's requirement),
`/sb dungeons skip boss` for the floor bosses, Kuudra `phase build|ballista|dps|win`, Enderman Slayer 5, HOTM 7, the Delver's
pass, `/sb hub discover all`, a God Potion and `/sb heal` loops for the miniboss / dragon / Kuudra tests, the Edgewraith HP
dial (restored to 1), End zealot/enderman spawn rules paused for the eye placement (resumed), Spooky Festival forced on
then cleared.

### What was played

**Dungeons.** Catacombs menu, Classes (selected Berserk), Catacombs Gate, Floor I solo **three times room by room**: the
map item renders (start room, `?` neighbours, white/green ticks, doors, player arrow), rooms spawn star mobs, a key
holder drops the **Wither Key** as a floating coal block that is picked up by walking over it, a secret (Revive Stone),
**Tic Tac Toe** played and tied ("PUZZLE SOLVED ... Blessing of Life V", +135 HP), **Water Board** solved (Blessing of
Power V), the Fallen Explorer miniboss (lost the DPS race twice). Run info menu (score parts, keys, puzzles). Scroll
Altar: bound Implosion + Shadow Warp + Wither Shield to the Highlord ("Wither Impact is awake!", 56k per cast). **Grinko
beaten** (first life, the revive, second life with balloons; the barrage dodged into the gaps between its rays) in 27 s:
completion box, Catacombs/class XP, boss collection, Wood + Gold chests, **Vault Keeper**, opened the Gold chest for 25k
(2 books + essence). **F2 Veilwright beaten** (students, the Priest raises the Mage, "Class... dismissed"). F3 Lecturer
(four guardians, Healthy heals, Laser locks on), F4 Bramblemaw (spirit animals, chicken mine), F5 Lurid, F6 Gorrak
(terracotta timer) and **F7** Gravax all start and run their scripts; a solo bot dies in all of them (F7 within 15 s even
at 3.6k in-run HP). F7 forced complete: Gold + Diamond chests offered (Gold 2.5M because it rolled Wither Boots +
Ultimate Wise II). **Highest floor completed solo: F2.**

**Crimson Isle.** Pledged to the Mages (confirm), faction menu, quest board (5 quests), Dojo menu, **Test of Control**
(604 pts, grade B, "QUEST COMPLETE ... +10 Mages Reputation"), mini-boss menu with live status and directions,
**Edgewraith** killed (HP dial lowered for the test): bestiary, +10 rep, "EDGEWRAITH DOWN! It returns in 2m".

**Kuudra (Basic).** `/warp kuudra`, hunt board (5 tiers, crew, chests, records, guide, loot preview), Basic, the arena (a
good-looking lava dome). Carried **3 of 6 supply crates** by crouching (lift meter, set-down meter, INTERRUPTED on hits),
**built** the ballista to 6% by crouching, **fired** the loaded ballista ("DIRECT HIT! The volley tears 750k health",
100% to 85%), DPS phase with the beached beast, forced win: summary box (time, damage, brood, tokens, deaths 18),
`/kuudra chests`, free chest claimed (Crimson Essence); the paid chest asks for a Leviathan Key.

**The End.** Warp, End Gate, walk to the Dragon's Nest, placed **8 Summoning Eyes** ("The ground begins to shake..."),
**Protector Dragon** with crystal beams. Shot it with the Hexbound Shortbow (13.7k crits land) — see the open issue: it
froze in place far from the nest. Admin kill: loot box with damage ranking, Loot Quality 1000, **Protector Dragon
Chestplate**; the sleeping eyes turned into Remnants of the Eye.

**The Rift.** Paid with a Planar Infusion, separate inventory (only the SkyBlock Menu star crosses), **time drain**
(7m 55s to 5m 32s, -30 s on death), a **Riddle Soul** (1/52, +3 SkyBlock XP), a **mote orb** (+25 motes), the leave box.

**Mayors / calendar.** Election booth (5 candidates, perks, live % bars), voted Coralie (confirm, booth updates);
Calendar (date grid with every event); **Dark Auction** opened on its own schedule while I was in the Rift: bid 20M, won
the Eccentric Painting Bundle; **Spooky Festival** forced: sidebar section, Dread Peddler shop, 1 candy from 9 zombie kills.

**Menus.** Every SkyBlock Menu icon opened its page (21 of 21; Personal Bank refuses away from a Banker, as designed);
`/pv` profile viewer (armour, equipment slots, stat pages, skills/slayers/dungeons/progress summaries); Settings (7
categories; toggled Skill XP Popup amount/percent and Rare Drop Messages off/on; `/sb menus setting` shows the stored values).

**Fast travel and the islands.** All 61 warps listed in "All Warps"; **55 destinations warped to** (locked ones with
`/sb warpto`), each landing screenshotted and judged: every island is a real, built place — the Hub districts (village,
farm, graveyard, crypt entrance, colosseum, carnival tents, ruins, keep, weather station, wizard tower), Barn, Mushroom
Desert, the Park's floating forests, Dwarven village / Forge / base camp, Crystal Caverns, Frozen Tunnels, Gold Mine,
Deep Caverns, Spider's Den (rain, Broodmother), The End (purpur gate, nest with obsidian towers), Crimson Isle (four
towns), Bayou, Moonmarsh, Winter Workshop, Dungeon Hub, Rift. No floating junk, holes or empty voids at any landing.
Torrent Canyon refuses even an admin warp (HOTF IV zone gate), which is correct.

### Fixed during this pass

| # | Module | Problem | Fix (verified how) |
|---|---|---|---|
| 1 | core combat (`MobListener`) | **Vanilla on-hit effects from sky mobs bypassed Defense.** F1's Cellar Spiders are cave spiders: their vanilla Poison ticked through the vitals safety net at 5% of MAX health per tick, ignoring Defense (944 HP / 678 Def: about 45 HP a tick while the bite itself did 11). My first F1 run failed in under a minute to a Lv20 spider pack. Same class: wither skeletons' Wither, husks' Hunger, strays' Slowness arrows | Any `EntityPotionEffectEvent` with cause ATTACK/ARROW on a player in the same tick as a hit from a sky mob (or its projectile) is cancelled; the hit itself still lands through the pipeline (the same pattern as fishing's Sea Creature guard). Verified: two Cellar Spiders, a bite for 11, no ticking afterwards, HP regenerates |
| 2 | dungeons data + `tools/gen/dungeons/gen_content.py` | **All 53 boss-fight mobs shared one bestiary family** (`dungeon_boss_fight`), named after the first one: killing F2 students or F4 spirit animals printed "BESTIARY Grinko" and "+1% damage vs Grinko" | `family` = the mob's own id (generator line and the 53 rows of `boss_mobs.json`). Verified: boot, 362/362 self-tests (not re-observed in a fight) |
| 3 | mayors (`Claims`, `MayorsModule`) | **A Dark Auction prize won inside the Rift vanished.** It was handed to the live inventory, which in the Rift is the Rift inventory, and stayed parked in the Rift on leaving (the 20M Eccentric Painting Bundle is still there for `EndgameEG`) | Rewards for a player in the Rift go to the claim queue ("Your reward waits for you outside the Rift.") and are handed over 2 s after they leave it (a flush never runs inside the Rift). Verified live: won a Nether Artifact in the Rift, left, "You collected 1 event reward" |
| 4 | mayors (`SpookyFestival`) | Start message read "Mobs drop Candy for the next / SkyBlock days." (number missing) | "for the next three SkyBlock days" |

### Open issues

| Module(s) | Severity | What I did | Expected | Actual |
|---|---|---|---|---|
| combatzones (dragon AI) | **major** | Placed 8 eyes, fought the Protector Dragon | It circles the nest and swoops into reach | After about 40 s it **froze** at (-48, 104, -12), about 110 blocks from the nest, in vanilla DragonPhase 2 (landing approach) and never moved again (sampled for minutes). Melee is impossible and the bow only works from under it, yet its **Rush keeps hitting every player within 110 blocks of the podium for 550 true damage**. Vanilla dragon pathing uses node rings around the world origin; on our island the landing path fails. Needs a watchdog (no movement for N s: reset the phase or steer toward the podium) or custom flight |
| core loot (inventory full) | major | Collected the Rift-won DA lot with a full inventory | Prize goes to a stash/claim | "Your inventory is full!" and the prize lay on the floor as an item entity named "skull's Head". On a public server a 10M prize on the ground is a theft/loss risk (owner protection not verified) |
| dungeons (balance) | major | F1 Fallen Explorer with Superior Dragon (1,079 HP / 678 Def) and a Voidstep Blade, twice | A miniboss that takes a while | It hits about once a second for about 50 plus a 480-raw lightning every 6 s: dead in 12–18 s while the player needed about 17 s for its 40k HP. `BalanceTest` counts hits-to-kill but not attack rate, so "19 hits" reads safe and is not. Room mobs and Grinko felt right |
| dungeons (Tic Tac Toe) | major | Played the board | Every button reachable | The top row's buttons are at floor+6: 5.0–5.2 blocks from the eye, beyond the 4.5-block reach. Only a jump-click reaches them |
| kuudra (solo) | major (pacing) | Basic hunt solo, 11 min | Hard but doable | Brood waves never pause, every hit cancels the 1.4 %/s build channel (750 ms), Magma Spit / Tentacle Slam knock you around: 18 deaths. In the DPS phase the beast beaches at (0.5, 60, -22.5), 13 blocks past the platform rail **in the lava lake**, so melee cannot reach it. Crouching 1.5 blocks from a FUEL CELL did not pick it up (unverified: my pick-up distance may be wrong) |
| dungeons F3–F7 solo | note | Each boss floor with Morvane's set + Highlord | — | All scripts run, but a solo player dies in 15–40 s from F3 up (no healer, no party). Faithful to party content; the owner should know solo stops at F2 |
| dungeons | minor | Balloon barrage / Lecturer laser | A named cause | Death messages read "killed by a monster" / "struck down" (`Kit.hit` passes no source) |
| dungeons | minor | Wither Shield | A 5 s shield | The absorption never decays: 1,544/944 HP persisted for minutes and across warps out of the dungeon |
| dungeons | minor | Water Board | A puzzle | My first single-lever probe solved it: the generator accepts boards with a one-lever solution |
| dungeons | minor | Class menu | — | Tank lists "Taunt: mobs prefer you while above 25% HP", which the handoff says is not built |
| dungeons | minor | F1 finish | One line per award | "+20 SkyBlock XP (Completed Floor I)" and "+20 SkyBlock XP (first clear)" for the same award (not paid twice). Sidebar "Cleared %" lags the run-info menu (6% vs 12%) |
| crimson | unverified | Lowered the boss HP dial, admin-killed and respawned the Edgewraith, fought it | One kill | Two "EDGEWRAITH DOWN!" lines, two bestiary ticks, +20 rep: most likely my admin spawn plus the rule's own respawn alive at once |
| core death | minor | Died in combat zones 5 times | — | Half the purse each time (faithful), shown with decimals: "lost 6,247,670.5 coins!" |
| combatzones (End) | minor | Right-clicked a frame that already held my eye | — | It takes the eye back while holding a Summoning Eye, so an accidental click un-places it. Sidebar "Eyes placed" and the dragon line are dark purple / dark gray on the dark sidebar |
| kuudra | minor | Joined a hunt | Hunt Perks in hotbar slot 8 | It lands in inventory slot 15 (the SkyBlock Menu star owns slot 8). The boss collection is named "Vorgath" while the beast is "Magma Leviathan" |
| menus (fast travel) | minor | All Warps | One entry per place | Aliases are listed separately, some locked while their alias is open (Dwarven Mines x3, The Forge x2, Crystal Caverns x2, Base Camp x2). "The Moonmarsh" has no description. `mining_forge` lands exactly on the Forge NPC |
| menus / collections | minor | SB Menu, `/pv` | Count matches the chat | "Collections Unlocked 1/790, Discovered 1" after "COLLECTION UNLOCKED" for Coal, Ender Pearl, Gunpowder, Bone and four boss collections |
| menus | minor | Personal Bank icon in the Hub | — | "You can only bank at a Banker in the Hub!" while standing in the Hub (the rule is "at a Banker") |
| progression | minor | Skipped Gerald | — | "OBJECTIVE COMPLETE Visit The End / The Barn / The Park..." fire while the sidebar keeps "Talk to your Island Helper" |
| mayors | minor | Voted; bid in the DA | Feedback | No chat line for a vote (the booth updates). One bid button read "Bid: 11,000,000" while the bid placed was 10,000,000 (unverified) |

### Verdict

The endgame **exists and works end to end**: a dungeon run from the gate to a paid reward chest, seven scripted boss
floors that all start and run their mechanics, puzzles that are real puzzles, classes and the scroll blade, the Vault
Keeper, factions with a quest that completes from a Dojo trial, mini-bosses with respawn timers, a Kuudra hunt whose
phases (supplies, build, ballista, DPS, chests) all advance, the dragon altar and its loot box, the Rift with its own
inventory and draining time, an election, a calendar, a Dark Auction and a Spooky Festival — and every warp lands on a
real, finished place. The menus are complete and readable everywhere.

What would hurt a real player: the **dragon freezes far from the nest** (the fight cannot be finished without an admin),
**prizes fall on the floor** when the inventory is full, the **F1 miniboss is a DPS race** a mid-game player loses, the
**Tic Tac Toe top row is out of reach**, and **solo Kuudra** is a slog with a beast that beaches in lava. Two silent
problems are fixed: vanilla poison from cave spiders (5% max HP a tick through any Defense) and Rift-swallowed auction prizes.

**State left behind:** `online-mode=true` restored and the server restarted and left running with the fixes; `EndgameEG`
de-opped; the test client killed. `EndgameEG`'s profile (skills 30, Catacombs 30, HOTM 7, Morvane's set, F1/F2/F7 clears,
Mages, a Basic Kuudra clear, the Eccentric Painting Bundle parked in its Rift inventory) remains in `skyclone.db`. The
`crimson.boss-hp-scale` dial and the paused End spawn rules were restored; the Spooky Festival force was cleared.

---

## Final verification — 2026-10-03 (about 07:30–08:10, server time)

**Who:** `FinalFV` (fresh profile, one client, TestPilot on 25620). Screenshots: `docs/playtest/final/NN_*.png`,
smoke test in `docs/playtest/final/smoke/`.

**Rules kept:** TestPilot only. Rocket League was not running at launch. `focuswatch.py --any-java` ran the whole session:
**0 violations** (the only foreground window was the owner's Resolve; client window `exstyle 0x8040110`, `noactivate: true`;
log `docs/playtest/final/focus_watch.jsonl`). Only SkyClone's server (25630) was stopped and started; ShadowPrison2 (25610)
was not touched.

**Gates:** `tools/check.sh all` CHECK OK (857 files) · `gradlew build` green, **565 unit tests, 0 failures** · boot
**35/35 modules, 0 FAILED** (twice) · live `/sb selftest` **366 passed, 0 failed** · `smoke_test.py` **SMOKE OK 6/6**.
Nothing needed fixing to get there; no code was changed in this pass.

### Blocker / major issues: claimed fixed vs. seen in the client

| Pass | Issue | Claimed fixed? | Verified? | What I saw |
|---|---|---|---|---|
| newplayer | Island gathering never counted toward collections | yes (core-world) | **yes** | Fresh profile: 3 oak logs → "COLLECTION UNLOCKED Oak Log", 4 cobblestone → "COLLECTION UNLOCKED Cobblestone", Collections "Discovered 2/90", Cobblestone 4/50. Placed a cobblestone and broke it: count stayed 4/50 (no place-and-break farming) |
| newplayer | ...and cobblestone gives no Mining XP on the island | no (request to mining, not done) | **still broken** | Skills after mining 4 cobblestone: Mining 0/50 (Foraging 18/50 arrived). No `private_island` handler exists in `mod/mining` |
| newplayer | Hub doors face away from the square | yes (hub v5) | **yes** | Bank: walked from the square through the new south double door to Banker Odell (`05_bank_inside`). Square-side double doors present at the Auction House, Bazaar, Builder's House and Tavern (west door). Village Directory (`/village`) lists 16 places with door side, distance and compass |
| loops | Wyrmbreath Blade (AOTD) refused outside dungeons ("Requires Catacombs 12") | yes (weapons) | **yes** | Graveyard zombie one-shot with it, no requirement message; lore "Requires Catacombs Level 12 in dungeons." |
| loops | `/warp crypt` lands in the Golden Ghoul pack, death in ~1 s | yes (hub v6) | **yes** | 839 HP / 667 Def: 12 s on the landing, 0 damage, nearest ghoul 17 blocks. Walked 9 blocks into the hall: one ghoul engaged, 80 HP lost, Crypt pack max chasers 3 |
| loops | Goblin Burrows minecart station drops you among goblins | yes (mines v2) | **yes** | Rode the minecart from the Village: sidebar "Goblin Burrows Station" (SAFE), 20 s, 0 damage, goblins wander 6–29 blocks away and never hit |
| loops | Ghoul Horror T3 Life Drain = 180 true dmg every 4 s, tooltip silent | yes (slayer) | **yes** | Tier III tooltip lists "Life Drain: 120 every 4s / Pestilence: 36 per second nearby / Defense reduces all of these." Live: "LIFE DRAIN! -19❤" (Defense applied), 839 → 426 HP in ~17 s standing idle (melee + aura + drain), i.e. a real fight instead of a 13 s death |
| loops | Spider's Den: nothing respawned for 4 min | yes (combatzones v2) | **yes** | Low ground a (46 blocks from spawn): pack of 10 spiders, killed all; 6 back within 5 s and 9 within 10 s, all inside 20 blocks |
| loops | 11 red self-tests (unregistered ingredient ids) | yes (endgame pass) | **yes** | 366/366 |
| endgame | Protector Dragon froze 110 blocks from the nest | yes (combatzones: own flight) | **yes** | Tracked 150 s: circles at r≈36 over the podium, perches over the podium every ~20 s (body 2–7 blocks off-centre, y≈64–67), never stood still. Standing inside the altar ring, 4 of 7 melee swings landed on the perched body (damagers 1, 5,904 damage on the kill box) |
| endgame | Prize dropped on the floor with a full inventory | yes (core: item stash) | **yes** | Full inventory + `/sb give` 1,600 Enchanted Diamond + a Voidstep Blade: no item entities on the ground, `/viewstash` shows 384 diamonds + the blade; "Pick Up Everything" returned all 385 with the blade's PDC (uuid) intact |
| endgame | F1 Fallen Explorer wins the DPS race | yes (dungeons retune 25k/140) | **yes** | In an F1 run, 844 HP / 667 Def with the Wyrmbreath Blade: killed it in ~13 s, lost ~165 HP. `/sb dungeons balance F1` duel line agrees |
| endgame | Tic Tac Toe top row out of reach | yes (rows at h 6/4/2) | **yes** | Found a board in a fresh F1 run (buttons y 72/74/76 over floor 70). Standing on the floor in front of each column, both top-corner buttons clicked at 4.2 blocks; the board answered each move |
| endgame | Kuudra beast beaches in the lava, out of reach | yes (kuudra) | **yes** | DPS phase: walked through the rail gap onto the shore (z −11), 12/12 swings hit the Magma Leviathan. Hunt Perks now sits in hotbar slot 8 |
| endgame | Kuudra solo pacing (brood never pauses, hits cancel channels) | yes (kuudra) | not verified live | Only unit tests; the second hunt could not start while the first sat in Victory |

### New or still-open problems found in this pass

| Module(s) | Severity | What happened |
|---|---|---|
| combatzones (End) | **major** | `/warp drag` (Dragon's Nest) lands 2.8 blocks from a **Lv55 Zealot**; two of them killed an 844 HP / 667 Def player in ~3 s with no input, three times in a row (also after admin tp to the nest). Same class as the Crypt and Goblin Burrows arrivals fixed in this round: the nest warp needs a SAFE landing or an arrival gate. (The previous pass paused the zealot rules, which is why it was not seen) |
| mining | major (part of the newplayer issue) | Mining XP on the private island is still 0 (see table) |
| mining | minor | "+35 SkyBlock XP (Heart of the Mountain Tier 1)" still arrives on first join |
| core loot | minor | One stash notice per stack: a single give of 1,600 items printed six "Your inventory is full!" lines |
| collections | minor | Kuudra's boss collection still unlocks as "Vorgath" (kuudra asked collections to rename it) |
| core ui | minor | Sidebar footer is still `www.skyclone.example` |
| collections | minor | Collections header "Unlocked 0/790" while the Mining/Foraging pages each show 1 unlocked |

**State left behind:** `online-mode=true` restored and the server restarted and left running (35/35 modules); `FinalFV`
de-opped (an accidental `op list` over RCON was undone at once; `ops.json` is empty); the test client killed; the
`combatzones.dragon-ability-seconds` dial reset to 6 and the End zealot / nest-endermite spawn rules resumed. `FinalFV`'s
profile (skills 25, Catacombs 20, Superior Dragon set, Wyrmbreath + two Voidstep Blades) remains in `skyclone.db`.

---

## Wave 3 new-player pass — 2026-10-04 (02:08–03:20 and 08:40–10:25, server time)

**Who:** `Wave3NP`, one fresh profile played from first join, TestPilot on `TESTPILOT_PORT=25629`. The first half (02:08–03:20)
was a session that hit its usage limit before writing anything up; its tool log was recovered and its findings are folded in
below (screenshots `01_..51_`). The second half continued the SAME profile (`52_..102_`), so this is one new player's first
~3 h 40 min of real play. Admin help was used only after the play (marked below). Screenshots: `docs/playtest/wave3/`.

**Rules kept:** TestPilot only. Rocket League was not running at any of the 4 launches. `focuswatch.py` ran both halves:
**0 violations** (`focus_watch.jsonl`; the only foreground windows were the owner's own Minecraft 26.2 client and Windows
Terminal; every test window had `exstyle 0x8040110`, `noactivate: true`). The owner's running 26.2 client and the gradle
daemon were never touched; only SkyClone's server (25630) was stopped and started (4 restarts).

**Gates:** `tools/check.sh all` CHECK OK (902 files) before and after · `gradlew build` green before and after (**674 unit
tests, 0 failures**) · boot 35/35 modules, 0 FAILED on every deploy · live `/sb selftest` at the end: **401 passed, 1 failed**
— `accessories.dungeon-xp` ("the API has no SkyEvents.DungeonXpGain yet", accessories request 12), not touched by this pass.

### What a fresh player did (no admin)

Join → Gerald → starter kit → chopped the oak (Oak Log collection + Foraging XP) → 2x2 planks → crafting table → **Recipe
Book**: Craftable Now → Supercraft sticks, Wooden Pickaxe, Wooden Sword → 10 cobblestone (**Mining XP on the island now
works**, the open issue from the newplayer/final passes) → SkyBlock Menu → placed the Cobblestone Minion → Portal Isle
(menu, dark frames refuse and nudge, labels) → **first Fairy Soul** (by the bridge) → `/hub` → Village Directory → Banker
(deposit) → Builder (sold moss stone) → Bazaar NPC (Level 7 gate, now with "how to earn SkyBlock XP") → Auction House → Fairy
→ Farm → wheat → Graveyard (3 deaths with the Wooden Sword and no armour; bought the 90-coin iron set at the Armorsmith;
**Combat II** after ~40 min of fighting) → rejoined: **collected the minion** (64 cobble, Cobblestone I) → Librarian →
Blacksmith (Mining District) → Coal Mine → **Mining I unlocks the Gold Mine** → walked through the gate: **Gold Mine reached**
→ gold needs Breaking Power 3 (clear action-bar message) → mined iron → **Recipe Book Supercraft of an Iron Pickaxe** → gold →
Hub Forest (Foraging IV) → crafted and placed an **Oak Minion I** (2/5 minions) → **Slayer: talked to Brannoc, started Ghoul
Horror I for 2,000 coins** (bar filled 150/150, the boss spawned and killed the player: quest failed, fee lost) → Farm →
**Farming I opens the Barn pad** → Barn: potatoes and carrots, **Farming V** → sold 19 stacks for 3,302 coins through the
Sell Box → Pet Sitter (no pets for sale) → **Auction House: bought a Sand Minion I BIN for 176 coins** → Portal Isle: walked
into the now-lit Gold Mine portal.

Where the profile stands after ~3 h 40 min of play: **SkyBlock Level 3 (336 XP, 15 of it from the admin-assisted Target
Practice below)**, objectives 27/36, collections 11/90 (12 tiers), Farming 5, Foraging 4, Combat 3, Mining 2, 3 minions.

| Goal from the brief | Reached? |
|---|---|
| Combat 5 | **no** — Combat III (426 XP of 1,175). Lv1 Graveyard mobs give 6 XP; with iron armour + Iron Sword the bot needed ~20 s and a rest per kill; 6 deaths in total |
| A minion placed and collected | **yes** (Cobblestone collected; Oak + Sand placed) |
| First slayer quest started | **yes** (Ghoul Horror I; failed at the boss) |
| The Gold Mine reached | **yes** (via the Coal Mine gate, after Mining I) |
| 5 collections unlocked | **yes** (11) |
| A Bazaar trade | **no** — the Bazaar needs SkyBlock Level 7 (research-faithful). At this pace (≈90 SkyBlock XP/hour) that is ~4 more hours |
| An item crafted from the Recipe Book | **yes** (Supercraft: sticks, Wooden Pickaxe/Sword/Axe, Iron Pickaxe, Iron Sword, Oak Minion I) |
| A Fairy Soul clicked | **yes** (1/339) |
| A pet | **no** — nothing sells pets early; the cheapest pet on the AH was a Pigman at 25,800 coins (purse peaked ~8.6k) |

### Wave 3 changes spot-checked in the client (admin help: op, `/sb rift infusions`, `/sb hub tp`, `/give bow`, `/sb pets give`)

| Feature (hand-off) | Result |
|---|---|
| hub: Rift arch refusal nudge (`TravelSpots.nudge`, all.md) | **works**: walking in without an infusion prints "Requires a Planar Infusion (talk to the Warden of the Arch in the Wizard Tower)!" and pushes the player 1.5 blocks back EAST, the side they came from (traced tick by tick). Holding W afterwards walks straight through the sheet during the 3 s refusal cooldown and leaves you behind the arch (harmless). With an infusion: "ENTERED THE RIFT" box, Rift inventory |
| hub: Target Practice (Range Master Jory) | **works**: menu, medals, `GO!` title, action-bar score/timer, 60 s round, result box: **Score 17, Gold**, +2,500 coins, +15 SkyBlock XP. Note: shooting the four targets in rotation without looking reaches Gold (a wrong target only counts as a miss), and the round's action bar replaces the HP/Defense bar |
| pets: riding (Rock seat, Armadillo mount) | **works**: "You are riding your Rock/Armadillo. Sneak to get off.", sneak dismounts; the Armadillo steered at **~6.9 blocks/s** on open ground (`wave3_pass.py` reported FAIL at 2.2 blocks in 2 s only because it rides into the Archery Range house) |
| island: Portal Isle (generator v4) | **works** (no admin): labels, dark frames refuse and nudge, `/is portals` menu ("Click to walk to this portal!"), the Gold Mine portal lit itself after the visit and walking in warped there |

`wave3_pass.py` itself: its `target_practice` check FAILs on a full hotbar (the `/give` bow lands in the inventory) and its
pet check rides into a wall; neither is a game bug (`report.md` / `report.json` in the same folder).

### Fixed during this pass

| # | Module | Problem | Fix (verified how) |
|---|---|---|---|
| 1 | foraging (`Chopper.handles`) | **Hub Forest trunks left un-choppable stumps.** A log only counts if its connected logs touch leaves; cutting a trunk at eye level (one log per swing at 0 Sweep) leaves the stump below touching no leaves, so every later swing at it was silently cancelled until the 60 s regrowth. Chopping at eye height gave **2 logs from 22 swings** | a log that already belongs to a pending (regrowing) tree skips the leaf test — its tree passed it when first cut. Verified: cut a mid-trunk log (+1), then the stump (+2 with fortune) |
| 2 | core world (`ProtectionListener.onBreak`) | Breaking a protected block (e.g. the decorative trees round the Portal Plaza, which look exactly like the Forest's) was silent: the block popped back with no reason and "Chop logs in the Forest" counted nothing | "You can't break blocks here!" (throttled by `world.deny-message-seconds`; hardness-0 blocks like grass stay silent). Verified in the Portal Plaza |
| 3 | core foundation (`RequirementsImpl.joinAnd`) | Barn launch pad label and refusal: "Requires Farming Skill 1 and Farming Skill 1!" (island + warp requirement merged) | duplicate parts are named once. Verified: label "Requires Farming Skill 1" |
| 4 | crafting (`RecipeIndex.sortName`) | Recipe category pages sorted tiers alphabetically: Minion I, II, III, IV, **IX**, V, VI… | roman-numeral tiers sort by value; search still matches the plain name. Verified: Foraging Recipes page 1 reads I..XII |
| 5 | core combat (dial `loot.full-message-cooldown-seconds` 3 → 20) | ~25 "Your inventory is full! Items went to your item stash" lines in 3 minutes of farming: a flood for a human and a context-eater for an AI agent | default 20 s (still a dial) |
| 6 | progression (`Objectives.announceNext`) | Two steps finishing inside the 30-tick delay (Talk to the Fairy, then Find a Fairy Soul already done) printed "New Objective: Explore the Farm" twice | one pending announcement per player (compile + unit tests; not re-observed live) |
| 7 | progression data (`gen_progression.py`) | Hints with no directions: Librarian, Blacksmith, Slayer Master; "Visit The Barn" did not say the pad needs Farming I (the player arrives at Farming 0 after "Harvest Wheat") | each hint says where (and `/village` / `/hubmap`); the Barn hint says "needs Farming I: harvest some wheat first". Regenerated; diff = those 4 lines |
| 8 | progression data (first half) | "Explore the Farm" / "Travel to the Coal Mine" did not complete on arrival (area box vs the warp landing) | `zones` added to both triggers; pickaxe hint says how to make one. Verified: Coal Mine completed on entry |
| 9 | core world (`ZoneTracker`, first half) | "NEW AREA DISCOVERED!" as the bold title ran off both screen edges at 1280x720 | zone name is the title, the banner the subtitle. Verified (screenshot 63) |
| 10 | hub (`HubGuides`, first half) | A District Guide's sidebar section followed the player round the whole Hub and hid the main Objective (e.g. "Guide: Graveyard » Talk to the Bartender" in the Village) | the guide shows only inside its district (see open issue 1 for what is left) |

### Open issues

| Module(s) | Severity | What I did | Expected | Actual |
|---|---|---|---|---|
| progression / hub (sidebar) | **major (AI show)** | Walked into the Mining District, Farm, Foraging Camp, Wizard Tower with an objective running | The main objective stays visible | Inside any district with an unfinished District Guide the sidebar shows the guide ("Guide: Mining District » Visit the Mine Merchant") **instead of** the Objective: the 15-line sidebar has room for one section. An agent reading the sidebar loses its objective exactly where most objectives happen. Suggest: show the guide only when the current objective is in that district, or as one line under the Objective |
| bazaar / progression (pacing) | **major (AI show)** | Played ~3 h 40 min | Bazaar within the first session | SkyBlock Level 7 is ~4 more hours away (336 XP now, ≈90/hour). Research-faithful, but for "Can 4 AIs beat SkyBlock" the Bazaar — the centre of the economy — stays shut for the first episodes. A show-only dial for the gate may be worth having |
| hub combat (Graveyard) | **major (AI show)** | Fought with the Wooden Sword, then the iron set + Iron Sword | A fair beginner zone | 6 deaths across the session (half the purse each: 220, 110, 107, 652, 328, 166 coins). Three chasers at 20 damage against 110–125 HP leave no margin for slow reactions. Combat II took ~40 min, Combat III ~1 h. Faithful numbers, but an AI agent will die here repeatedly; the "Slay Zombies" hint could say "buy the Armorsmith's iron set first (90 coins) and bank your coins" |
| slayer | minor | Bar filled while fighting Graveyard mobs at 59% HP | A moment to prepare | The boss spawns at once beside you in the pack; dying fails the quest and keeps the 2,000-coin fee ("Slayer bosses do not forgive", faithful). A new player's first slayer is likely a 2,000-coin loss |
| TestPilot `/walkto` (affects AISkyShow) | major (AI show) | Walked north out of the Barn square | Steps over a 15/16 block | Stuck for minutes at a `dirt_path` step (0.94 high): `/walkto` only jumps "real 1-block steps". Moving with `jump` cleared it. Same class: doors (README), the Library's outer wall, the Bank's double door (both leaves must be opened). Agents driving TestPilot will stall on these. **Fixed in code 2026-10-04 (tools)**: hitbox-based jump test + A* path planning round walls + opens both door leaves; `NavTest` green, not yet re-walked in a client, jar not reinstalled - see `docs/handoff/tools.md` |
| island (Portal Isle bridge) | minor | Walked the spruce bridge in straight lines (first half) | Cross it | Fell into the void twice at the diagonal turn (-2076, 29): the 2-wide diagonal is ~1.4 wide where it steps. The `/is portals` "walk to this portal" button avoids it |
| hub (Portal Plaza) | minor | Chopped the Plaza's trees | — | They look like Forest trees but are protected (now with a message, fix 2). The hint "Chop 20 logs in the Hub's Forest" could say "west of the Portal Plaza" |
| hub (Rift arch) | minor | Held W into the arch without an infusion | Bounced out | Pushed back once, then walked through the sheet during the 3 s cooldown and stood behind the arch |
| hub (Target Practice) | minor | Shot the 4 targets in turn without aiming at the lit one | Skill needed | Gold medal (17): wrong targets cost nothing. The round's action bar also hides HP |
| progression (SkyBlock XP menu) | minor | `/sblevel` | Counts what was done | "Fairy Souls 0/530" after a soul (XP only on exchange, faithful) and "Fast Travel Unlocked 0/300" after 9 districts (only scrolls count): reads as broken to a newcomer |
| mining | minor | First join | — | "+35 SkyBlock XP (Heart of the Mountain Tier 1)" still arrives before the player has done anything |
| core ui | minor | Sidebar | — | Footer is still the placeholder `www.skyclone.example` |
| mayors (calendar chat) | minor (AI show) | Played | — | "[Calendar] X starts in 4m 43s!" / "has started!" for every event, plus "DARK AUCTION! … Bring at least 400,000 coins" to a 0-coin player: frequent chat noise an agent must filter |
| progression | minor | Crafted the Oak Minion | One line | "+1 SkyBlock XP (New unique minion: Oak Minion I)" and "New unique minion: Oak Minion I! (1 crafted) +1 SkyBlock XP" for the same award |
| auction | minor | Bought from an NPC agent | — | The agent is "[Agent] Hollis Crane"; the Town Crier is also "Hollis" |
| gold mine | minor | Gate arrival | — | You land facing a stone plinth 2 blocks away (screenshot 65) |
| recipe book | minor | Craftable Now with 88 oak logs + a Wooden Axe in the inventory | Minions listed | Minion recipes never appear in Craftable Now; only in the skill category pages |

### Verdict for the show

The first two hours **are playable and readable without help**: every step of the objective chain names the next one, the
Recipe Book's Supercraft makes crafting trivial for an agent (no grid needed), merchants, the Sell Box, Bank, AH, minions and
the Portal Isle all work, island Mining XP is fixed, and the four wave-3 features spot-checked all work in a real client.
What will hurt four AIs on camera, in order: **the sidebar hiding the Objective inside District Guide districts** (open 1),
**TestPilot `/walkto` stalling on 15/16 steps and double doors** (agents will look stuck), **Graveyard deaths** that halve the
purse and make the 2,000-coin first slayer a likely loss, and **the Bazaar being ~6 hours away** at Level 7. Two silent
failures an agent could never have diagnosed are fixed: un-choppable Forest stumps and protected-block breaks with no message.

**State left behind:** `online-mode=true` restored, server restarted and left running (35/35 modules, `ops.json` empty);
`Wave3NP` de-opped; its test client killed. `Wave3NP`'s profile remains in `skyclone.db` (SkyBlock Level 3, Farming 5, three
minions, a LEGENDARY Rock and Armadillo, a bow and 2,500 coins from the admin-assisted spot checks). No dials are overridden.

## Wave 3 final verification — 2026-10-04 (10:44–11:10, server time)

**Who:** the final verifier, after the Wave 3 integration, the new-player pass and the fixers (hand-off sections dated
2026-10-04 "wave-3 playtest" in accessories, auction, bazaar, core-ui, crafting, hub, island, mayors, mines, mining,
progression, slayer, tools). One fresh dev account `W3Final`, TestPilot on `TESTPILOT_PORT=25629`, admin help over RCON
(op for the session). Screenshots and the harness re-run: `docs/playtest/wave3final/`.

**Rules kept:** TestPilot only, no OS input. Rocket League was not running at either launch. The OS foreground window was
the owner's DaVinci Resolve / Brave the whole time (`focus_watch.jsonl`: no Minecraft window ever came to the front; checked
again with `GetForegroundWindow` when the smoke test complained, see below). Only SkyClone's server (25630) was stopped and
started (3 restarts); nothing on 25610 / 25620-25624 / 25640 / 5650 was touched. The rebuilt TestPilot jar (with
`/clearance`, path planning, `/usehold`) was copied into the Fabric-26.1.2 instance's `mods/` per README section 5 while no
TestPilot client was running.

**Gates:** `tools/check.sh all` CHECK OK (907 files) before and after this round's edits · `tools/check.sh tools` OK ·
`gradlew build` green (**689 unit tests in 81 classes, 0 failures**) · boot **35/35 modules, 0 FAILED** on all three boots
(the `gold_mine` v2 rebuild ran on the first: 890k blocks, 2 s) · live `/sb selftest` **406 passed, 0 failed** on the first
boot and again on the final online-mode boot (the `accessories.dungeon-xp` red is gone; the API gap is one boot WARN line).

| Issue (wave-3 pass) | Claimed fixed by | Verified in the client? |
|---|---|---|
| [major] District Guide hid the Objective in hub districts | progression (`progression.objective.district`) | **yes** — Mining District and Farm: `Objective / Talk to your Island Helper / Guide: Mining District /hubguides`; Village square: plain Objective (shot 01) |
| [major] Graveyard deaths for newcomers | hub (newcomer chaser cap + gate tip) | **yes** — the tip prints only what applies (0 Defense, no sword, one-at-a-time; no purse line at 0 coins); `/sb hub pack` "newcomers 1, full at Combat 5"; standing in the pack at Combat 0, exactly **one** zombie (id 517) closed to melee range over 50 samples while five others held off. Note: that one zombie still kills a 0-Defense player in ~5 s (faithful numbers) |
| [major] TestPilot `/walkto` stalled on 15/16 steps, Library wall, double doors | tools (A*, hitbox jump test, door leaves) | **yes, partly** — Village → Librarian (79 waypoints, 0 replans), Village → Banker (46), Barn spawn → Windmill Operator north over the dirt paths (119 waypoints, 1 replan, 33 s): all arrived. The hub's Bank and Library doors are generated OPEN and protected (a right-click does not close them), so the double-door opener was never needed live; it is covered by `NavTest` only |
| [major] Bazaar ~6 h away at Level 7 (AI show) | bazaar (`bazaar.access-requirement` words/numbers) | **yes** — default refuses "Requires SkyBlock Level 7!"; `3` refuses "Requires SkyBlock Level 3!"; `none` opens the Bazaar Agent's menu (`/bz` away from the NPC still wants the Boon Buff, as designed). Dial **reset** to `sblevel:7` afterwards |
| slayer boss rose on top of a hurt player | slayer (boss GATHERS until 75% Health + 3 s calm, max 60 s) | **yes** — bar filled at 42%: title "GHOUL HORROR STIRS / Step out of the fight and heal up", action bar `Boss stirs \| Health 42%/75% \| rises in 60s`, sidebar "The boss stirs... heal up"; the boss rose the second Health reached 75% (shots 09-10) |
| Portal Isle bridge: fell off at the diagonal | island (generator v5 thick-line bridge) | **yes** — fresh island: walked the whole bridge straight on the centre line and at 0.9 / 1.0 / -0.9 / -1.2 blocks off it, never below the deck |
| Rift arch: walked through during the refusal cooldown | hub (`TravelSpots.holdsBack`) | **yes** — held W into the sheet for 6 s without an infusion: x stopped at -110.97 (sheet at -112), message twice (5 s throttle). With an infusion: entered (harness PASS) |
| Target Practice paid Gold for not aiming; round hid HP | hub (dark target -1 and the light moves; core action-bar segment) | **yes** — lit hit +1 and a new light; dark hit "Wrong target! -1" (never below 0: "Wrong target!"); HP/Defense/Mana stay on the action bar beside `PRACTICE n \| 50s`. **Bug found and fixed here:** the result box computed accuracy as score/arrows, so with the penalty a hit then a miss read "accuracy 0%"; now hits/arrows (re-verified: 2 of 2 = 100%) |
| Pet riding check rode into a wall | tools (`/clearance` facing) | **yes** — harness PASS, Armadillo steered 13.1 blocks in 2 s |
| Target Practice harness: full hotbar | tools (`Pilot.hold`) | **yes** (bow swapped into slot 1) — but the harness still never fired: it looks for the glow from the Range Master, 34 blocks from targets `/findblocks` can see 24 of. **Patched here** (scout down-range, shoot from the line), compile-checked, not re-run |
| Gold Mine arrival faced a stone plinth | mines (GoldMineGenerator v2, spawn (6,64)) | **yes** — obelisk at the left edge, clear view north up the camp (shot 11) |
| "+35 SkyBlock XP (HOTM Tier 1)" on first join | mining (`hotmAwakened`) | **yes** — nothing on join; the Tier I box (+35) arrives on the first arrival in the Dwarven Mines, once |
| Sidebar footer `www.skyclone.example` | core-ui (`server.footer` = `{server}`) | **yes** — footer reads `SKYCLONE` |
| `/sblevel` "Fairy Souls 0/530", "Fast Travel 0/300" read as broken | progression (XP wording, `TravelScrolls`) | **yes** — "0/530 XP", souls found / not yet exchanged / "paid per exchange of 5 souls"; reading the Old Keep scroll printed `+15 SkyBlock XP (Travel Scroll: ...)` and Fast Travel went to 15/300 XP |
| New minion printed its award twice | progression (quiet prefixes) | **yes** — Oak Minion I supercraft: one line `New unique minion: Oak Minion I! (1 crafted) +1 SkyBlock XP` |
| Minions never in Craftable Now | crafting (`CraftMath.sortCraftable`) | **yes** — with 88 Oak Logs + a Wooden Axe and Oak Log I: Oak Minion I is slot 1 of page 1, before planks (shot 15) |
| Decorative Plaza trees: no way forward | hub (`ForestHint`) + core (deny message) | **yes** — "You can't break blocks here!" then "These trees are the Hub's decoration... 61 blocks to the west, west of the Portal Plaza. (Your compass points there now.)"; the log was restored |
| Dark Auction invitation to a 0-coin player | mayors (`DarkAuction.canEnter`) | **yes** — the 10:55 auction sent `W3Final` (0 coins) no invitation |
| `[Agent] Hollis Crane` vs Town Crier Hollis | auction (new name pool) | data + self-test `auction.agent-names-unique` PASS; not looked at in the AH |
| "Explore the Farm" announced twice | progression (one pending announcement) | not reproduced (needs two steps inside 30 ticks); unit-tested only |

**Also fixed this round:** the accessories boot WARN read "give no XP until the API has no ...DungeonXpGain yet" (now
"give no XP: the API has no ... yet"); the "Chop logs in the Forest" objective hint now says where the Forest is ("west of
the Portal Plaza (/hubmap gives directions). The Plaza's own trees are decoration.") — regenerated, diff = that one line.

### Still open

| Module | Severity | What |
|---|---|---|
| menus (calendar chat) | minor (AI show) | **Not fixed** (mayors request 14 is still open): in 25 minutes a hub player got `[Calendar] Dark Auction starts in 4m 57s!` / `has started!`, `Spider's Den Rain` and `Spider's Den Thunderstorm` `starts in` / `has started!` — all real-time recurring events, all while standing in the Hub. Agents can turn them off per player in `/settings` (NOTIFY_START / NOTIFY_REMIND); the module-level fix (skip real-time recurring events, or default them off) is a design call for menus |
| accessories / dungeons | known | Catacombs Expert Ring / Professor's books give no XP until the API has `SkyEvents.DungeonXpGain` (request 12) |
| TestPilot | minor (tools) | `/ping` reported `focused: true, osFocused: true` for the test client while `GetForegroundWindow` said the owner's DaVinci Resolve was in front and the focus log never saw a Minecraft window; so `smoke_test.py` FAILs its "not focused" step (the other 5 steps PASS). Either the flag goes stale after some in-game action or GLFW's focus attribute disagrees with the OS here; worth a look before AISkyShow trusts that flag |
| pets / progression | minor | giving a LEGENDARY level-100 pet printed `+3 SkyBlock XP (Pet Score)` ten times in one tick (admin-sized jump, but one summed line would do) |
| accessories | note | every boot: `lowered 1 NPC sell prices ... {DWARVEN_HANDWARMERS=2500 -> 0}` — its forge inputs (flawless gems, plates) have no NPC price, and SellCap counts an unpriced input as 0 by design, so the Handwarmers sell for nothing |
| scripts/rcon.ps1 | minor (tools) | the first `sb selftest` after a boot printed `RCON: connection closed after 0 of 4 bytes` while polling; polling `sb selftest report` separately worked |

**State left behind:** `online-mode=true` restored, server restarted and **left running** (35/35 modules, selftest 406/0,
`ops.json` empty); `W3Final` de-opped; its client killed; the focus watcher stopped. `W3Final`'s profile remains in
`skyclone.db` (SkyBlock Level 0, a Cobblestone and an Oak Minion I, Oak Log I, HOTM Tier I, a LEGENDARY Rock and Armadillo).
`bazaar.access-requirement` was reset to its default; this round left no dial override behind (the two that differ from
their defaults, `calendar.epoch-millis` and `crimson.leviathan-placeholder = false`, were already in `dials.yml`, plus a few
set equal to their defaults). The `wave3_pass.py` re-run's report was moved
to `docs/playtest/wave3final/wave3_pass_rerun.md` and the original `docs/playtest/wave3/report.md` restored.


## Wave 4 loops pass — 2026-10-04 (14:41–17:30, server time)

**Who:** `W4Loops`, one fresh dev account played through the owner's priority loops on the current build, TestPilot on
`TESTPILOT_PORT=25629`. Screenshots: `docs/playtest/wave4-loops/001_..091_*.png`.

**Rules kept:** TestPilot only, no OS input. Rocket League was not running at launch. `focuswatch.py` ran the whole session
(about 2 h 50 min, 3 client launches): **0 violations**, the only foreground windows were Windows Terminal and Chrome, every
test window had `exstyle 0x8040110`, `noactivate: true` (`focus_watch.jsonl`). Only SkyClone's server (25630) was stopped and
started (4 restarts); nothing on 25610 / 25620-25624 / 25640 / 5650 was touched, and the other `java.exe` on the machine was
left alone.

**Gates:** `tools/check.sh all` CHECK OK (910 files) before and after · `gradlew build` green before and after (**693 unit
tests, 0 failures**) · boot 35/35 modules, 0 FAILED on every deploy · live `/sb selftest` **409 passed, 0 failed** on the last
fix deploy. `bazaar.access-requirement` is `sblevel:5` (the owner's default): it was set to `sblevel:99` for one click to read
the refusal and then **reset** (no override left in `dials.yml`).

**Admin shortcuts used** (everything else was played in the client): coins, Combat 15 → 22, Mining 5 → 12, Farming 10,
Fishing 15, Enchanting 20 + 120 XP levels, HOTM tier 4 then 7 (the Forge and the Frozen Tunnels gate), Garden V, gear by
`/sb give` (Zombie Sword, Unstable then Superior Dragon armour, Shadow Fury, Wheat Sickle, Ruby Drill, Rod of Champions,
Starter Lava Rod, scrap + chisel), pet items, a God Potion, the T3 Ghoul bar (`/sb slayer progress`) after ~10 minutes of
real Crypt grinding, the Refined Mithril timer, a composter fill/skip, the garden's `random_tick_speed` raised for 25 s so
the preset wheat could ripen (restored to 3), `/sb mines unlock` after the walker could not leave a lift cage, and one
`slayer.boss-damage-scale 0.6` T3 fight (restored to 1).

### What was played

**Slayer (~70 min).** Brannoc → Slayer menu → Ghoul tier page (HP, DPS, Life Drain/Pestilence bill, XP, cost, locks) → **T1**:
22 Graveyard kills (3 min, one 80 s gap with nothing in range), the bar filled, "The boss stirs" box with the exact conditions,
boss rose at full health, killed, level-up box (rank, +Health, recipe), Ghoul Flesh. (With the Unstable Dragon set the boss
died in 2 s to the set's lightning: faithful, the gear was too good for T1.) **Enchanting** on the Shadow Fury at the Library
table: Smite V, Critical V, a Book → Life Steal III, the **anvil** combined it (6 levels). **Reforge** at Smith Bram: first
reforge refused without 10 Coal, bought Coal at the Mine Merchant, then Fast → Legendary → Sharp (5,000 coins each, stat lines
show the reforge part). **T3** (20,000 coins): `/warp crypt` now lands in a SAFE corridor (old major fixed), Crypt Ghouls give
32 Combat XP each; the bar filled while hurt in the safe Crypt Entrance → "Boss stirs | leave the safe area", walked out to the
Graveyard, it rose at 66% Health after the 60 s cap. Two real T3 fights with Superior Dragon + the enchanted Sharp Shadow Fury
(~7.5k dps): the boss lost 265k of 400k, the player 598 HP in ~35 s against Life Drain, Pestilence (-25% Defense seen) and
melee → died twice, quest failed, half the purse each time. Faithful "Challenging", but see open issue 4. Third fight (God
Potion, damage dial 0.6) killed it in 24 s → **RNG meter**: set Foul Flesh, 115/3,093, boost x1. **Broodweaver T2** in the
Spider's Den: 650 Combat XP in **2 min 10 s** (the old "sparse Den" major is gone), boss 4 s, Spider II (Wolf unlocked).
**Fenris T2** in the Ruins: 650 Combat XP in 3 min, boss 3 s, Wolf I, Hamster Wheel drop.

**Mining (~45 min).** Mine Merchant → Promising Pickaxe (35 coins) → `/warp gold_mine` → **132 gold/iron in 4 min**
(Mining 5 → 6) → walked into the Deep Caverns portal (Lapis Quarry, lift menu with the visited rule) → walked the haul road to
the Pigmen's Den → `Requires Mining Skill 12!` at the Sanctuary gate (faithful) → **Dwarven Mines**: HOTM Tier I box on
arrival, Emissary (2 Titanium commissions), Pickwright → Fractured Mithril + Rusty Titanium pickaxes, minecart to the Far
Reserve, 69 mithril in 4 min (+2 powder each), 18 titanium in 2.5 min → `COMMISSION COMPLETE! ... claim it` chat line →
claimed (+10,000 Mining XP, Mining XIII, powder). **HOTM**: token on Mining Speed, five powder levels (adjacency kept).
**Forge**: everything locked at HOTM 1 (says why), at HOTM 4 Refined Mithril from 160 Enchanted Mithril → finished → claimed.
**Crystal Caverns** with a Ruby Drill: amethyst at 3.7 s each, +2 Gemstone Powder, fuel on the action bar. **Fossil
Excavator** (Frozen Tunnels, HOTM 7): scrap + chisel, 16 charges on the grid, `EXCAVATION COMPLETE` (Enchanted Glacite,
1,324 Rimestone Powder, 12 Fossil Essence, Rimestone Jewels).

**Farming (~20 min).** A real Harvest Fair (Wheat/Mushroom/Carrot) was running when I reached the Barn. Harvesting before
signing up prints exactly what to do ("Talk to Harvest Warden Ambrose (Hub farm or The Barn) to sign up"); signed up at the
Barn's Fairgrounds, farmed wheat for 6 minutes with the Wheat Sickle: sidebar `○ Wheat 1,952 BRONZE #15/41`, results box
**#15 of 41 BRONZE** (the live rank matched at a natural end), medal, 40 bits, 10 Harvest Fair Tickets, a Turbo-Wheat I book,
Farming XI, tool level 2, a Cropling. **Harvesta's exchange**: bought a Hoe of Great Tilling (1 Bronze + 5 tickets).

**Garden (~25 min).** `/warp garden` builds the instance and prints a 5-step welcome; plot 4 is free. Desk → Configure Plots →
plot 4 → Farm Presets → Wheat Lanes (5,000 coins, laid instantly). Harvested ~300 ripe wheat (ripe crops replant themselves),
Garden milestones, a visitor (Farmer Joss: 123 Wheat → 92 Farming XP, 2 Garden XP, 15 Copper, two visitor milestones,
SkyBlock 11 → 12). Composter: added organic matter from the inventory, filled/skipped by admin, collected 1 Compost.
**Pest** at Garden V: a Fly on plot 4 (sidebar `Plot - 4 ൠ x1`); 30 sword hits did nothing (fixed, #6) → Copper Market →
Garden Vacuum (5,000 coins, confirm screen) → vacuumed in 6 s, +1,500 Farming XP, bestiary.

**Fishing (~25 min).** Hub pond: bites every 11–18 s, Raw Cod, a **Sea Guardian** (NEW SEA CREATURE line), killed.
**Backwater Bayou**: the docks are a long fenced pier and every forward cast lands on the deck (open issue 9); casting
sideways and slightly up over the rail works: bites at 14–16 s, a **Trash Gobbler**. **Crimson Isle**: Cinder Shore lava pool
with the Starter Lava Rod (hotspot `+2% ⚓` on the sidebar): 30 bites, **6 trophy fish in 12 casts** (Blobfish BRONZE/GOLD,
Slugfish, Plain Vanille SILVER, all "NEW!" + SkyBlock XP), a Fried Chicken; `/trophyfish` shows the title ladder, pity and the
lava streak. Before fix #8 the same pool killed the player twice (Lv100 Tinderlings).

**Pets (~10 min).** Learned a Tiger and an Armadillo from items (`Successfully added ... to your pet menu!`), `/pets`,
summoned; **Armadillo ride** (`/pet ride`, sneak off) at **21.7 blocks/s** (see open issue 11); Tiger levelled 1 → 6 from 23
Graveyard kills and kept levelling from fishing/mining XP; Taming V–VI.

**Bazaar at SkyBlock Level 5** (the profile reached Level 5 by playing these loops, then 14). The Bazaar Agent opens the
categories; **Sell Inventory Now** listed 15 kinds with the after-tax total (510,873 coins) and sold them; Enchanted Mithril
product page (price history, flip margin, book); **instant buy** of a stack (99,078 coins); a **Buy Order** 64 @ top +0.1
(93,312 coins) that later reported "13% filled (8/64)" and "1 order ready to claim". The gate refusal (dial raised for one
click) reads `Requires SkyBlock Level 99!` + the player's level, XP needed and where SkyBlock XP comes from.

### Fixed during this pass

| # | Module | Problem | Fix (verified how) |
|---|---|---|---|
| 1 | skills (`Runes.onKill`) | **Every slayer-quest mob paid "boss" Runecrafting XP.** The check was `flags.contains("boss") \|\| def.slayer != null`, and `MobDef.slayer` is the QUEST tag carried by every Graveyard Zombie, Crypt Ghoul, spider and wolf: Runecrafting I after four Crypt Ghouls, VI by the end of the slayer loop, and the action bar showed `+15 Runecrafting` instead of the Combat XP on every kill | bosses only. Verified after redeploy: a Graveyard kill flashes `+7 Combat (5,052/500,000)` |
| 2 | progression (`FairySouls.ensure`) | **Fairy-soul heads leaked**: six item displays + six click boxes stacked on hub soul 61 (by the Bazaar) after ~30 minutes. A chunk can stay loaded while its entities are not accessible; `Bukkit.getEntity` answered null, the watchdog forgot the head and spawned another on top | `ensure()` removes any head of that soul still standing in the chunk before spawning. Verified after restart + several warps away and back: `execute ... if entity` counts 1 at each of three hub souls |
| 3 | slayer (`BossFight.attacking`) + alchemy (`EffectEngine.modify`) | **A God Potion made a T3 Ghoul harmless**: Dodge IV + Agility IV give 64% "mob attacks miss", and the boss's own Life Drain and Pestilence are dealt as `Kind.MOB` from the boss, so they were dodged too (no drain in a whole fight) | boss mechanic damage is tagged `ability` (+ `slayer_mechanic`); potion Dodge skips `ability` hits. Verified: God Potion, T2 Ghoul, no attacks from me: melee still `DODGED!`, `LIFE DRAIN!` lands every ~4 s |
| 4 | slayer (`SlayerCatalog.mobPlural`) | "Slay 650 Combat XP worth of Wolfs.", "Kill wolfs to summon it." | plural helper (Wolves, Endermen). Verified in the Fenris menu |
| 5 | mining (`Commissions` claim box) | The box listed `+900 daily bonus HOTM XP` and then `+1,300 HOTM XP` (the total), which reads as 2,200 | base line first, bonus on its own line, no total. Verified: `+400 HOTM XP / +900 daily bonus HOTM XP` |
| 6 | garden (`Pests` behaviour) | Hitting a pest with a sword: 30 clean hits, no damage, **no message** (only a vacuum hurts pests, faithful, but silent) | action-bar hint, 10 s throttle: "Only a Vacuum hurts pests! (Copper Market at the barn)". Verified (shot 089) |
| 7 | garden (`CropListener.milestone`) | First crop milestone read `GARDEN MILESTONE Wheat I➜I` | shows `0➜I`; verified `I➜II` … `VI➜VII` after `/sb garden milestone` |
| 8 | crimson data (`spawns.json` + `tools/gen/crimson/gen_mobs.py`) | **The Cinder Shore lava-fishing pool is a death trap**: the two Lv100 Tinderlings (1,300 damage, 300k HP) spawned in `mobs.volcano_base`, 20 blocks from the pool, and chased in. A 700 HP / 700 Defense Fishing 15 player died there twice (once before the first cast), half the purse each | the Tinderling rule spawns on `mobs.volcano_rim` (70+ blocks away and ~40 up). Verified: 30 casts at the pool, no Tinderling within 48 blocks, no damage |
| 9 | bazaar (`BazaarModule.command`) | `/bz` one block from the Bazaar Agent: "Requires the Boon Buff! Visit the Bazaar in the Hub to trade there instead." | within 6 blocks of `npc.bazaar`, `/bz` counts as clicking the agent (away from it the Boon Buff rule is unchanged). Verified both |

### Open issues

| # | Module(s) | Severity | What I did | Expected | Actual |
|---|---|---|---|---|---|
| 1 | crimson (generator layout) | **major (AI show)** | Lava fishing at the Cinder Shore pool after fix #8 | A safe starter lava-fishing spot | The pool (`fishing.lava.hut`, x -103..-93, z -81..-71) is inside the `mobs.mystic_marsh` spawn box (x -215..-90, z -195..-58): a **Lv80 Mushroom Bull (150k HP, 800 damage, aggressive)** walked up to 13 blocks while I fished. Needs the marsh box trimmed or the Cinder Shore zone made SAFE (generator change + rebuild, so not done here) |
| 2 | hub (Crypt) | major (pacing) | Crypt Ghoul grinding for the T3 bar with 600 HP / 630 Defense | A dangerous but workable grind | The Lv60 Golden Ghoul (45k HP, 800 damage) patrols ~14 blocks inside the safe corridor; three deaths in ~6 minutes (2M, 1M, 500k coins), each while standing still or retreating. Faithful numbers; for an agent the Crypt is a coin furnace. The T3 bar (2,400 XP = 75 ghouls) was ~10% filled in that time |
| 3 | mining / TestPilot | major (AI show) | Dwarven Mines → Glacite Tunnels with `/walkto` aimed at the portal | Land in the Base Camp | Landing is correct (spawn 0.5,131,-109.5), but **`/walkto` does not stop when the world changes**: it kept walking towards the old target and dragged the player 110 blocks into the tunnels (cold + mobs, 637 → 296 HP), and the same on the way back. Agents that walk into portals will wander. Tools fix: stop `/walkto` on dimension change |
| 4 | slayer (tuning) | major (pacing) | T3 Ghoul, Superior Dragon (598 HP, 630 → 472 Defense with Pestilence) + reforged/enchanted Shadow Fury, Life Steal III | A winnable Challenging fight | ~35 HP/s incoming (melee + Life Drain + Pestilence + a few Graveyard zombies) against 598 HP: dead in 35–47 s at ~70% boss HP, twice (fee lost, half the purse). Matches the stated numbers; it is the HP pool a T3 player needs (~1,500+) that the gear ladder before T3 does not give. With a God Potion it was trivial before fix #3 |
| 5 | TestPilot `/walkto` | minor (AI show) | Several walks | Arrive | (a) From inside the Tavern to the Graveyard: 57 replans, 0 waypoints, 90 s (legs via the door worked). (b) Along the Graveyard's west wall (x=140, z -53..-41): repeated "0 waypoints" from (140,76,-15) to (160,76,-20) — the same leg had worked an hour earlier. (c) Stuck inside the Pigmen's Den lift cage (clearance 0 in all 16 directions) for 7.5 minutes. (d) Crystal Caverns entrance: gems 15 blocks above the walkway, no route out of the entrance hall found |
| 6 | pets / progression | minor | Learned two pets | One line | `+3 SkyBlock XP (Pet Score)` four times for two pets (one line per score point; the wave-3 note said admin-only, it happens in normal play) |
| 7 | names | minor (AI show) | Read NPC names | Distinct names | "Bram" is the hub blacksmith (Smith Bram), the current Mayor (Bram), NPC farmers "Bram Barley"/"Bram Clover", and the Forge has "Forgemaster Brann"; "Ranger Hollis" in the Spider's Den beside Town Crier Hollis |
| 8 | core (item stash) | minor (AI show) | Farmed with a full inventory | Rewards reachable | Harvest Fair Tickets and Fossil Essence went to the stash **behind 4,000+ wheat and seeds** (FIFO): Harvesta's exchange said "Harvest Fair Tickets: 0" until six `/pickupstash` rounds. Also "Your inventory is full!" 17 times in 6 minutes of farming (20 s throttle). **Fixed in code 2026-10-04, not yet verified live** (`docs/handoff/core-combat.md`): pickup order is rewards → rarer → smaller piles; bulk overflow is one chat line per episode + every 300 s, action-bar flash between |
| 9 | fishing / bayou | minor | Cast forward from the Bayou Docks pier | Water | The docks are a long fenced pier; forward casts land on the deck (0 bites in 15 casts), only a sideways cast over the rail reaches water |
| 10 | enchanting | minor | Right-clicked the enchanting table holding the Shadow Fury | Table opens | It opens, but the sword's ability also fires ("No enemies nearby!") |
| 11 | pets (riding) | minor (balance) | Rode the Lv1 Armadillo with ~350 Speed | — | 21.7 blocks/s on open ground (5x walking); with high Speed it outruns chunk loading quickly |
| 12 | slayer (RNG meter) / farming rewards | minor (known) | RNG meter list, Harvest Fair reward box | Named books | "Enchanted Book" without the enchantment (Smite VI; "+1 Enchanted Book" in the Fair box was Turbo-Wheat I) — open since the first loops pass |
| 13 | mining / AI show | minor | Commissions | Visible progress | Commission progress is only in `/commissions`: neither the sidebar nor the tab list shows it (the reference shows it in tab). The completion chat line is there |
| 14 | garden (Copper Market) | note | — | — | Blossom Bracelet: item says "Requires Garden Level VI" but the shop says "Requires Garden VIII" (shop unlock vs use gate; may be faithful) |

### Verdict

Every priority loop **runs end to end on the current build**, and most of them feel right: the slayer flow (menu → quest box →
bar → "the boss stirs" wait → fight → level-up box, RNG meter) is clear and the Den and Ruins bars fill in 2–3 minutes; Gold
Mine → Deep Caverns → Dwarven Mines reads as a ladder with honest gates (BP, Mining 12, HOTM 7); commissions, HOTM, the Forge,
gems and the Fossil Excavator all pay out with readable boxes; a natural Harvest Fair worked from sign-up to medal with a live
rank that matched the result; the Garden onboarding is the best first five minutes in the game; fishing bites every 11–21 s
on all three islands and trophy fish came quickly; the Bazaar at Level 5 sells, buys and fills orders.

What still makes it *unfair* rather than hard, in order for the show: **high-level mobs next to beginner activities** (the
lava-fishing pool inside a Lv80 spawn box; the Golden Ghoul at the Crypt mouth), **T3 slayer needing an HP pool the gear ladder
does not give**, and two TestPilot walker gaps that will make agents look lost (**`/walkto` carries on after a portal**, and it
cannot leave some enclosed rooms). Nine bugs were fixed in this pass, three of which an agent could never have diagnosed: the
Runecrafting XP leak that hid every Combat XP flash, the silent pest immunity, and the God-Potion dodge of boss abilities.

**State left behind:** `online-mode=true` restored, server restarted and **left running** with all fixes (35/35 modules, `ops.json`
empty); `W4Loops` de-opped; its client killed. `W4Loops`' profile remains in `skyclone.db` (SkyBlock Level 14, Combat 22,
Mining 14, HOTM 7, Farming 11, Garden V, slayers Zombie 2 / Spider 2 / Wolf 1, Tiger + Armadillo, one open Bazaar buy order,
~2.4M coins). No dial differs from its default because of this pass (`bazaar.access-requirement` override reset, slayer damage
dial and the garden's `random_tick_speed` restored).

## Wave 4 endgame pass — 2026-10-04 (17:57–20:15, server time)

**Who:** `W4Endgame`, one fresh dev account given endgame admin gear, TestPilot on `TESTPILOT_PORT=25629`. Screenshots:
`docs/playtest/wave4-endgame/NNN_*.png` (198), every warp landing as `warps/<warp id>.png` with contact sheets
`warps/sheet_0..3.png`.

**Rules kept:** TestPilot only, no OS input. Rocket League was not running at any of the 4 launches. `focuswatch.py` ran the
whole session: **0 violations**; the only foreground window was the owner's Chrome, and every test window had `exstyle
0x8040110`, `noactivate: true` (`focus_watch.jsonl`). Only SkyClone's server (25630) was stopped and started (3 restarts);
nothing on 25610 / 25620-25624 / 25640 / 5650 was touched. `bazaar.access-requirement` was never changed (`sblevel:5`).

**Gates:** `tools/check.sh all` CHECK OK (912 files) before and after · `gradlew build` green before and after (**723 unit
tests, 0 failures**) · boot 35/35 modules, 0 FAILED on every deploy · live `/sb selftest` **412 passed, 0 failed** on the
first boot and again on the fix deploy. The boot WARN `armor bonus code refers to items ... [ASPECT_OF_THE_DRAGONS]` is gone.

**Admin shortcuts used** (everything else was played in the client): all skills 30, Catacombs 20 then 30, Enderman Slayer 7
(the shortbow's requirement), coins (and refunds after the deaths below), gear by `/sb armor give` / `/sb give` / `/sb dungeons
gear scrolls` (Superior Dragon set, Highlord, Wyrmbreath Blade, Oblivion Shortbow, arrows, Summoning Eyes), `/sb dungeons skip
boss` for the F1 re-run and the F2/F3 boss looks, `dungeons.solo-boss-damage-scale 0.3` for one F2 and the F3 attempt
(reset), a God Potion, `crimson.boss-hp-scale 0.002` + an admin heal loop for the mini-boss attempts (restored to its
`dials.yml` value 1.0), Kuudra `phase ballista|dps|win`, the End zealot and nest-endermite rules paused for the eye placement
(resumed), `/sb combatzones dragon kill` after a real fight, `/sb rift infusions`, the Spooky Festival forced on and cleared,
`/sb warpto` for the warp tour, `/sb foraging kit`, and one admin teleport out of the Fairy-room trap (fix 2).

### What was played

**Dungeons.** Catacombs menu, Classes, Catacombs Gate (every floor's card, Master Mode locked behind F7). **Floor I solo
twice, room by room**: map item (start, `?` neighbours, ticks, wither/blood doors, player arrow), star mobs, a Prince
("A PRINCE FALLS! +1 bonus score"), secrets (bats, items, a healing draught), crypt-key pickups by walking over them, two
**wither doors** opened with keys, the **Blood Key**, the Fallen Explorer miniboss, two **Fairy** rooms, **Creeper Beams**
solved (4/4 beams, "PUZZLE SOLVED ... Blessing of Wisdom V"), the **blood door**, the Onlooker's waves, the portal, and
**Grinko beaten with Wither Impact in 50 s** (first life, revive, balloons): completion box (score 179 B, Catacombs/class
XP, Grinko collection), **Vault Keeper Orlin -> Gold Chest opened for 755k** (Necromancer's Brooch, Balloon Snake, essence).
The first F1 attempt at the boss died in 8 s in melee to Grinko + two Circus Undead (see open issue 1). **Floor II**: Mage
class (Guided Sheep on F, Thunderstorm ultimate on Sneak+F), **Three Strangers** solved by logic, then the Veilwright fight
three times (died in ~10 s, ~10 s and, at 0.3 boss damage, ~60 s; "The Priest raises the Warrior!" seen). **Floor III**: the
Lecturer's four guardians, Laser Guardian lock-on, Healthy Guardian heal; died in ~20 s. Scroll Altar: bound the three
scrolls ("Wither Impact is awake!"), Wither Shield absorption now decays (old minor fixed).

**Crimson Isle.** Pledged to the Barbarians (confirm screen, FACTION JOINED box), quest board (5 quests incl. a Dojo trial),
Isle Guide, **Dojo -> Test of Force** three times (the first two ended early or scored 0 because the dummies walked out of the
arena, fix 4; after the fix a full 30 s round scored 244 and the quest tracked 244/400), mini-boss menu with directions,
**Rogue Pyromancer, Cindermaw and Edgewraith** attempted (open issue 4).

**Kuudra (Basic).** Hunt board (5 tiers, locks by reputation, crew, records, guide, loot preview), a Basic hunt: carried a
supply crate ("Lifting ... 66%", "Carrying supplies!", "Setting down ... (hit - slowed)"), knocked down by Lv100 Followers
while setting it down (the crate dropped where I fell), then admin phases: ballista (fuel 4/4), Kill, Victory: summary box,
+20 SkyBlock XP, **collection "Magma Leviathan"** (old "Vorgath" minor fixed), free chest claimed (Crimson Essence), paid chest
preview (Aurora Chestplate, 560 essence) asks for a Leviathan Key, Hunt Again button.

**The End.** `/warp drag`: **arrival is now safe** (old major: zealots stayed 14-22 blocks away, no damage over 12 s). Walked
to the altar, died twice to the nest's 16 Zealots (faithful), paused their rules, **placed 8 Summoning Eyes** ("The ground
begins to shake..."), the **Unstable Dragon (9M)** circled the nest (never froze), Rushed for 550, a crystal was destroyed; my
arrows did 1.5M before I died; admin kill: DRAGON DOWN box with the damage ranking, Loot Quality 1000, Wyrmbreath Blade.

**The Rift.** Walked into the **Wizard Tower arch** with an infusion: "ENTERED THE RIFT / Paid with: a Planar Infusion (2 left)",
Rift inventory (only the SkyBlock Menu star crossed), **time drain** on the action bar, Riddler Nyx (+3 SkyBlock XP, the Riddle
Souls menu with per-area hint pages), **two Riddle Souls** (1/52, 2/52), a **mote orb** (+25 Motes), left through the Cradle
portal: "LEFT THE RIFT" box, main inventory back.

**Mayors / calendar.** Election booth (5 candidates, perks, live %), voted Osric: **chat confirmation now** (old minor fixed).
**Spooky Festival** forced: start box, sidebar section, the Dread Peddler shop (23 offers + leaderboard + rewards), candy from
Graveyard mobs (5 green + 1 purple). **Dark Auction** on its real schedule: "[Calendar] Dark Auction starts in 4m 58s! Use
/darkauction to enter." (the new per-player alert policy at work: Important only, purse 80M), joined at :55, bid 500k on a
Turtle pet, won it; with a full inventory it went to the **item stash** (old "prize on the floor" major fixed).

**Moonmarsh.** `/warp moonmarsh` (Knotwick), chopped Fig logs with the Fig Hatchet (Fig Log collection, Foraging XP), two
**Velvet Lilac** -> the **Starfall (Marsh) Contest** sidebar went 0 -> 18 points; `/foraging` contest icon explains the scoring.

**Menus.** Every SkyBlock Menu icon (26) opened its page; Personal Bank away from a Banker now explains where Bankers are.
`/pv` (armour, equipment slots, stat pages, skills, slayers, dungeons, progress, collections, coins). **Settings**: Notifications
page (reminder lead time cycled 5 -> 10 -> 5, start messages toggled), `/sb menus setting` shows the stored values; the new
**calendar-alert scope**: `/eventalerts` status and the bell in Festivals & Events cycling Important -> Everything -> Off ->
Important (fix 9).

**Fast travel and the islands.** All Warps now lists **52** places with aliases merged ("Also: /warp ...", old minor fixed) and
a description for the Moonmarsh (old minor fixed). **51 warped to** (the Rift by its arch), each landing screenshotted and
judged: every island is a real, finished, lit place; no holes or floating junk. Notes in the open issues (Howling Cave, Wizard
Tower, DA during Spooky).

### Fixed during this pass

| # | Module | Problem | Fix (verified how) |
|---|---|---|---|
| 1 | armor (`SetIds`) | **The Superior Dragon set's "+50% Wyrmbreath breath" never applied**: the set code compared against `ASPECT_OF_THE_DRAGONS`, but the item is `ASPECT_OF_THE_DRAGON` (the boot log warned about it every start) | id corrected (also in the dial's description). Verified: the boot WARN is gone; the bonus itself not measured |
| 2 | dungeons (`Tiles` fairy_grove) | **The Fairy room is a trap.** The rim stood on the floor (fy+1) round a water ring sunk into it (fy): a player who steps in stands 2 blocks under the rim top and cannot jump or swim out (tried every direction with sprint+jump: max y 71.9). Walking up to the Fairy is the natural move. I had to be teleported out | rim set into the floor: a one-block step down, out with a normal jump. Verified in a fresh F1: stepped into the ring (y 70.2) and walked out |
| 3 | dungeons (`DungeonRun.unstickClimber`) | **A Cellar Spider (cave spider) climbed into a top corner under the ceiling, 10 blocks up**: only a bow from directly below could reach it, and a room only clears when every star mob dies (a melee player is stuck; the room may hold the key) | a room spider within 3 blocks of the ceiling is put back on its room's nearest mob spot (checked every second). Compile + boot only (the second run had no climber) |
| 4 | crimson (`Dojo.force`) | **Test of Force dummies walked out of the arena** through the open south gate (one reached z -68, 18 blocks past the wall); chasing one ends the trial ("Ended early: you left the arena") | dummies outside the arena box are put back on the mat. Verified: two full rounds, 0 of 4 dummies outside over 30 s; a played round scored 244 and the faction quest counted it |
| 5 | hub + progression (sidebar priorities) | **The Spooky Festival's candy counter vanished in the Graveyard**, where candy is farmed: the District Guide pair (`progression.objective.district` 349, `hub.guide` 350) outranked live sections (`mayors` and `fishing` 400) and the frame has room for one section | 449 / 450: below every live section, still in the same order. Verified: Graveyard shows "Spooky Festival / Candy" during the festival and "Objective / Guide: Graveyard" after it |
| 6 | dungeons (`DungeonRun.secret`) | The action bar read "1/2 Secrets ... 1/2 Secrets" (the flash repeated the HUD segment) | flash "✦ Secret found! (n/N this run)". Verified |
| 7 | progression (`PMath.DEFAULT_QUIET_AWARDS`) | Every bestiary tier printed its box (which lists "+1 SkyBlock XP") **and** "+1 SkyBlock XP (Bestiary)" | `bestiary:` is quiet (dial default + unit test). Verified: 8 kills, 1 box, 0 extra lines |
| 8 | reforges (data + `gen_reforges.py`) | 38 reforge stones read "Combine with a armor/equipment piece" | article by vowel in the generator; the JSON patched to match (diff = those 38 lines). Verified on the Necromancer's Brooch |
| 9 | mayors (`EventBoard`) | The Calendar Alerts bell printed the current choice's meaning as one long unwrapped line and then the Important-only text again, whatever was selected | the three choices, the current one marked with ▶. Verified while cycling |
| 10 | kuudra (`KuudraModule`) | **"☠ Leviathan Basic VICTORY" stayed at the top of the sidebar (priority 50) on every island after a won hunt**, plus "25 ⛃ tokens" on the action bar (a won hunt keeps its members for Hunt Again) | both hidden outside the arena once the hunt is won. Verified: arena shows both, the Hub shows neither |
| 11 | core items (`AbilityServiceImpl`), dungeons (`WitherBlades`), weapons (`Bows`) | Spamming an ability with no mana / a shortbow with no arrows printed one chat line per click (dozens a minute; an AI agent's context fills with them) | chat at most once per 3 s per player; the action bar flashes NOT ENOUGH MANA / NO ARROWS on every click. Verified: 25 clicks -> 2 lines, 20 clicks -> 2 lines |
| 12 | combatzones (`DragonFight`) | "+10 SkyBlock XP (Slay a Unstable Dragon)" | "an" before a vowel. Compile only |

### Open issues

| # | Module(s) | Severity | What I did | Expected | Actual |
|---|---|---|---|---|---|
| 1 | dungeons (boss floors, solo) | **major (AI show)** | F1-F3 bosses solo with Superior Dragon (983 HP / 678-741 Def), Highlord + Wither Impact | A beatable F2, a fair arrival | **Grinko's first life raises Circus Undead (45k HP, 1,200 raw) within ±3 of himself and walks onto the arrival point**: in melee I died in 8 s; with Wither Impact spam he died in 50 s. **F2 Veilwright: died in ~10 s three times** (Undead Mage 2,432 raw = ~312 after Defense, several at once), ~60 s even at `solo-boss-damage-scale 0.3`. **F3: lost 30% HP within 2 s of arriving**, dead in ~20 s. The endgame pass of 2026-10-03 cleared F2 solo with the same gear; I could not. Faithful party content, but four AI agents will stop at F1 |
| 2 | foraging / park (Howling Cave) | major (AI show) | `/warp howl` | A safe landing | Lands among 4+ Lv30 Pack Wraiths at 1-3 blocks (HP drops at once). Same class as the Crypt / Goblin Burrows / Dragon's Nest arrivals fixed earlier; needs a generator/spawn-box change |
| 3 | TestPilot `/walkto` | major (AI show) | Walked legs toward a Hub coordinate while (by my mistake) still in The End | Stop at the edge | The walker walked straight off the island into the void, ~20 times in a row. It also right-clicks doors with the held item, so with a Highlord in hand **Wither Impact fired mid-walk** (teleport, 300 mana, shield). Agents walking across islands can suicide or burn abilities. Tools fix: never plan a step whose column has no ground; open doors with an empty hand or a sneak-click |
| 4 | crimson (mini-bosses) | note (balance) / minor | Rogue Pyromancer, Cindermaw, Edgewraith with 983 HP / 741 Def, a God Potion, then an admin heal every second | — | Dead in ~1-10 s every time (lost half the purse 4 times; refunded). Lv200 bosses are endgame and that may be faithful. Minor: Cindermaw's split **Smoldering Blazes (Lv95, 2.5M HP) ignore `crimson.boss-hp-scale`** and fly out over the void (I followed one off the edge); there is no boss-damage dial for testing |
| 5 | kuudra (solo) | major (pacing, known) | Basic hunt, carried a crate | Doable solo | Lv100 Followers knocked me down while setting the crate on a pad (the meter slows when hit, it does not stop); the crate fell where I fell. After VICTORY an arrow already in flight killed me: "You died (projectile)" with no source name |
| 6 | dungeons (Creeper Beams) | minor | Linked the 4 beams | Every lantern clickable | Lanterns at floor+6 against the wall reach only from straight below (pitch -90); from 1 block away the crosshair misses. The first click of a pair gives no feedback ("Lantern selected") |
| 7 | dungeons / weapons | minor | Opened the Three Strangers chest holding the Highlord | Chest opens | It opens **and** Wither Impact fires (shield, mana). Same class as the open "Shadow Fury fires on the enchanting table" |
| 8 | dungeons (blood room) | unverified | Opened the blood door in F1 | Steady waves | 4 blood mobs came and died within ~10 s, then ~2 minutes with nothing in reach before "You have proven yourselves" |
| 9 | mayors (Dark Auction) | minor (AI show) | Left the DA room after round 1 and warped across 15 islands | Quiet | All later rounds ("[Auction] Round 3/5 ...", "No bids. ... withdrawn", "The Dark Auction is over") still reached me everywhere |
| 10 | mayors (Spooky sidebar) | minor | Read the sidebar | Labelled counts | "Candy: 5 1 (10 pts)" - green and purple only by colour |
| 11 | menus (settings) | minor | `/settings` Notifications | The new alert scope there | Important / Everything / Off is only in `/eventalerts` and the Festivals & Events bell (mayors Request 15 is still open); `/sb menus setting` still says `notify.event_reminders ... read by menus` while mayors sends the lines now |
| 12 | menus (SkyBlock Menu) | minor | Clicked Bazaar and Auction House on the Moonmarsh | Same rule | Bazaar: "Requires the Boon Buff!"; Auction House opens anywhere without it |
| 13 | core loot (stash notices) | minor (AI show) | `/sb foraging kit` (17 axes) into a full inventory | One line | 17 lines "Your inventory is full! X went to your item stash" (the bulk throttle covers stacks, not one-per-unique-item gives; a DA lot or a reward kit does the same) |
| 14 | hub (warp landings) | minor | Warp tour | Facing open ground | `/warp wizard` lands facing the tower wall; during the Spooky Festival `/warp da` lands 1 block from a Lv50 Wraith (festival night mobs) |
| 15 | combatzones (End) | minor | Placed 8 eyes | Click any frame | Standing on the centre pillar 4 of 8 frames miss; some frames need standing ~3 blocks out on the lower ring. Workable, fiddly for an agent |
| 16 | crimson (Stronghold -> Dojo) | unverified | Walked 190 blocks from the arrival to the Dojo | — | 978 -> 412 HP with no chat line and no mob within 16 blocks at arrival; not reproduced on the later walks I watched |
| 17 | admin tools | minor (tools) | `/sb warpto garden W4Endgame` | Garden | Stayed on the previous island (instanced island); `/warp garden` works (2 s) |

### Verdict

The endgame **runs end to end and most of it reads well**: a full F1 from the gate to a paid Gold chest, two puzzles solved by
playing them, classes and their ultimates, the Scroll Altar, the Vault Keeper; a faction pledge, a Dojo trial that now plays
its full round and feeds the quest; a Kuudra hunt from crates to chests; eight eyes and a dragon that circles and pays by damage;
the Rift through the new arch, with souls, motes and a clean exit; an election vote with feedback, a Spooky Festival with its
shop and candy, a real Dark Auction whose invitation reached exactly the player who could afford it; the Moonmarsh contest;
26 menu icons, the profile viewer, settings, and 51 warp landings that are all real places. Four earlier majors are confirmed
fixed in the client (Dragon's Nest arrival, prizes to the stash, Kuudra collection name, warp aliases).

What will still hurt four AIs on camera, in order: **solo boss floors stop at F1** (Grinko only falls to ability spam; F2/F3 kill
a full Superior Dragon player in seconds), **arrivals inside mob packs** (Howling Cave), **the TestPilot walker walking off edges
and firing held abilities on door clicks**, and Lv200 Crimson mini-bosses that are out of reach for anyone below true endgame.
Two traps an agent could never diagnose are fixed: the Fairy-room pool you cannot climb out of, and Dojo dummies that walk out
of the arena and end the trial.

**State left behind:** `online-mode=true` restored, the server restarted on the final build and **left running** (35/35
modules, `/sb selftest` 412 passed / 0 failed, `ops.json` empty); `W4Endgame` de-opped; its client killed; the focus watcher
stopped. `W4Endgame`'s profile remains in `skyclone.db` (skills 30, Catacombs 30, Enderman Slayer 7, Barbarians, Dojo 244, two
Basic Kuudra clears, a Turtle pet in the item stash, ~79.5M coins, SkyBlock Level 32). No dial differs from its default because
of this pass (`dungeons.solo-boss-damage-scale` reset, `crimson.boss-hp-scale` back to its `dials.yml` value 1.0, the End spawn
rules resumed, the Spooky Festival force cleared).

## Wave 4 final verification — 2026-10-05 (01:58–03:15, server time)

**Who:** `W4Verify`, a fresh dev account, TestPilot on `TESTPILOT_PORT=25629` (jar from 2026-10-04 20:22, already installed,
md5 identical to the build). Screenshots: `docs/playtest/wave4-final/001_..050_*.png`. An earlier attempt the evening before
(`W4Final`, `docs/playtest/wave4final/`, 20:36–21:01) was interrupted and predates the last fixes (core `AbilityListener`,
01:55); nothing from it is counted here.

**Rules kept:** TestPilot only, no OS input. Rocket League was not running at launch. `focuswatch.py` ran the whole session:
**0 violations** (`exstyle 0x8040110`, `noactivate: true`; the foreground was the owner's Terminal, Chrome, Brave, Prism and
Explorer). Only SkyClone's server (25630) was stopped and started; no other server, port or process was touched (nothing was
listening on 25610 / 25620-25624 / 25640 / 5650 at the start; the owner's `watcher.py` was left running). No dial was changed.

**Gates:** `tools/check.sh all` **CHECK OK (914 files)** · `gradlew build` green (**741 unit tests, 0 failures**) · boot
**35/35 modules, 0 FAILED** · live `/sb selftest` **415 passed, 0 failed** (and again after the final restart). Every
generator bump from the fixers had already rebuilt (hub v9, park v2, the_end v4, backwater_bayou v2, crimson v3).

**Admin shortcuts used:** SkyBlock XP (400, then +100), `/sb give` (Voidstep Blade = `ASPECT_OF_THE_END`, Highlord, Shadow Fury,
rods), `/sb armor give` (Young Dragon, Superior Dragon), `/sb pets item`, `/sb alchemy apply speed`, skills 30 (Combat 22 /
Mining 14 for the T3 fight), `/sb enchanting apply` + `/sb reforges apply`, Catacombs 30 + Mage, `/sb dungeons start <n> force` +
`skip boss`, `/sb mining hotm tier 7`, `/sb warpto` (howl, the End, Dwarven Mines, garden), RCON `tp` beside the Glacite portal and
the Graveyard wall, `/sb kuudra start|phase win|end`, `/sb mayors da start`, `/sb mayors spooky start|end`, `/sb foraging kit`, and
`setblock` to close two hub doors the walker had opened (restored to closed).

| # | Issue (pass, severity) | Claimed fixed by | Verified in the client |
|---|---|---|---|
| 1 | Bazaar gate at SkyBlock Level 5 (owner's `sblevel:5`) | bazaar (dial default) | **Yes.** Level 4: clicking the Bazaar Agent refuses, "Requires SkyBlock Level 5! … 100 more SkyBlock XP" (001). Level 5: the same click opens `Bazaar ➜ Farming` (002) |
| 2 | Right-clicking an enchanting table holding a RIGHT_CLICK ability sword fires it (loops #10, minor) | core `AbilityListener` (interactable blocks skip the ability) | **Yes.** Holding the Voidstep Blade (`ASPECT_OF_THE_END`): the click opens `Enchant Item`, no teleport, mana 100/100 (003). In the air the same item still blinks 7 blocks for 50 mana |
| 3 | Lv80 Mushroom Bulls reach the Cinder Shore lava pool (loops #1, major) | crimson generator v3, NO_MOBS zones | **Yes.** `/sb island zones`: Cinder Shore and Belly of the Beast `[NO_MOBS]`. 60 s at the pool edge: nearest bull 40 blocks, no damage (011); bulls were seen only at x <= -129 |
| 4 | Golden Ghoul roams the Crypt grind hall (loops #2, major) | hub v8 vault + `hub_golden_vault` | **Yes.** `/warp crypt` prints the Crypt tip (death cost, vault, chaser cap); 90 s in the main hall: the Golden Ghoul stayed in its vault 55-62 blocks away. Stepping into the vault prints its warning and it fights; after stepping out it stayed home (14-17 blocks). (The test player then died to ordinary Crypt Ghouls in the corridor - faithful) |
| 5 | `/walkto` keeps walking after a portal (loops #3, major) | TestPilot `WorldWatch` | **Yes.** Walking through the Dwarven Mines -> Frozen Tunnels portal: stopped after 1.2 s with `worldChanged: true`, "world changed: sb_dwarven_mines -> sb_glacite_tunnels", standing still at the Base Camp (019) |
| 6 | T3 Ghoul unwinnable with the wave-4 kit (loops #4, major) | slayer (stated DPS = whole bill) | **Yes.** Tier tooltip lists "of which: Melee 87 / Life Drain 60 every 4s / Pestilence 18" and "Against you: ~23❤/s … last ~36s" (028). Real fight, Superior Dragon + Sharp Shadow Fury (Smite V, Critical V, Life Steal III), 785 HP / 645 Def (the playtest had 598 HP): **won in 48 s, lowest Health 442 (56%)** |
| 7 | `/walkto` cannot leave enclosed spots (loops #5, minor) | TestPilot (start from the real position, ladders, `noRoute`) | **Partly.** Graveyard west wall: from x 140.99 pressed against the wall (z -47.5 and -15.5) and from 139.3 all three walks arrived (27/19/40 waypoints, 0 replans). **Tavern upper floor -> Graveyard: still stuck** - 6 legs, 4 minutes, 94-98-waypoint plans and 7-10 replans each, the player circling the ladder hole at (59, 80, 17) and never descending (050) |
| 8 | Pet Score `+3 SkyBlock XP` once per point (loops #6, minor) | pets | **Yes.** Three rises, three lines: `+9 (Pet Score 3)`, `+6 (Pet Score 5)`, `+9 (Pet Score 8)` |
| 9 | Stash rewards behind bulk + overflow chat spam (loops #8; endgame #13, minor) | core stash order + `StashBatch` | **Yes (burst).** `/sb foraging kit` into a full inventory: ONE line, "17 items went to your item stash: Golden Axe, Iron Axe, Rookie Foraging Axe and 14 more. [PICK UP]" |
| 10 | Bayou Docks forward casts land on the deck (loops #9, minor) | fishing Bayou v2 | **Yes.** From `fishing.spot.docks`, 5 casts at yaw ±0/10/20: every bobber ~10 blocks out on water (`execute if block ... water` passed for all five), no "landed on" hint |
| 11 | Armadillo 21.7 b/s at Lv1 (loops #11, minor) | pets `mountAttr` | **Yes.** Lv1 at 100 Speed: 4.4 b/s (walking pace); at 290 Speed: **13.3 b/s** (cap 15; the old formula gave ~18) |
| 12 | Enchanted books unnamed (loops #12, minor) | slayer | **Yes.** RNG meter: "Enchanted Book (Smite VI)" |
| 13 | Commission progress only in `/commissions` (loops #13, minor) | mining (tab priority + sidebar line) | **Yes.** Sidebar `Commissions: 0%, 0%`; tab footer lists both commissions (049) |
| 14 | Solo boss floors stop at F1 (endgame #1, major) | dungeons party scaling, arrival grace, add share, Grinko ring, Lecturer window | **F1, F2 yes; F3 no.** Superior Dragon (1,010 HP / 678 Def), Highlord with Wither Impact, Mage, a simple bot that spams Wither Impact at the nearest target: **F1 cleared, 0 deaths, lowest Health 732**; **F2 cleared in 97 s, 0 deaths, lowest 259**. No damage in the first 3.4 s after the portal on any floor (arrival grace). **F3: died after ~46 s** of the Lecturer fight (playtest: ~20 s): two "Healthy Guardian restored every guardian" and three Laser lock-ons, then "killed by The Lecturer"; the bot ran out of mana for most of it. A player who kills the guardians in turn may do better; not shown |
| 15 | `/warp howl` lands in a wraith pack (endgame #2, major) | foraging Park v2, SAFE "Cave Mouth" | **Yes.** Lands at (-5.5, 59, -115.5) facing into the cave, sidebar "Cave Mouth"; 15 s: no damage, nearest Pack Wraith 22.4 blocks (014-016) |
| 16 | `/walkto` walks off island edges (endgame #3, major) | TestPilot edge guard | **NO - still walks off.** On the player's own Private Island (no mobs), `walk_to(spawn.x - 300, y, spawn.z + 40, sprint)` started from the ledge (979.5, 72, -2086.3) where a `walk_to(spawn.z - 300)` ends: it climbed the rim to y 74, stepped down at (973, 73, -2086.4) and fell into the void - **3 of 3 repeats**, `edgeStops: 0`, `waypoints: 7`, `replans: 0` (a planned step; tracks kept in the session). The rim there is a jagged diagonal taper (the column at x 971, z -2087 has nothing from y 70 to 74). Other directions stopped at the shore with `noRoute` or `edgeStops: 2`. In The End the same kind of long walk ended 1x "fell into the void" (and 2x killed by Zealots/Endermen) |
| 17 | Door clicks fire the held ability (endgame #3, major) | TestPilot bare-handed doors | **Yes.** Hub dark-oak door, Highlord held, hotbar full: 3 of 3 walks `doorsOpened: 1`, mana stayed 542/542, no chat, the Highlord back in slot 3 and selected afterwards |
| 18 | Crimson mini-bosses / split blazes / silent HP bleed (endgame #4, #16) | crimson (damage dial, split scaling + leash, no vanilla fireballs/ignite) | **Bleed: yes** - walked the arrival -> Dojo road (30 s): 1,010/1,010 the whole way, no fireballs seen. Split blazes: self-test `crimson.cindermaw-split` PASS. Boss fights not re-attempted |
| 19 | Kuudra solo: knocked down mid-carry, stray arrow after VICTORY (endgame #5, major pacing) | kuudra (brood damage x0.6 solo, channel grace, crate onto pad, shelter after VICTORY) | **Partly.** Basic Follower hits measured **~30** at 678 Def (was ~48). After `phase win`: 15 s in the hollow, **0 damage**. **Not verified: a crate delivery** - the bot never completed a lift, and the hunter was knocked down **10 times in ~3 minutes** (Followers, Berserkers, Inferno Magma Cubes), several times right after being dragged back to the platform. Solo Basic supplies are still not doable for an agent |
| 20 | End altar: 4 of 8 frames out of reach (endgame #15, minor) | combatzones End v4 | **Yes.** Standing on `altar.dragon` (52.5, 63, -41.5) the crosshair lands on all 8 frames (3.7-3.9 blocks) |
| 21 | DA round news reaches a player who left (endgame #9, minor) | mayors | **Yes.** In the room: "Round 1/5 ..." arrives; on closing the menu: "You stepped out of the Dark Auction - round news stops here"; then 200 s on the Hub farm: 0 auction lines |
| 22 | Spooky sidebar colour-only counts (endgame #10, minor) | mayors | **Yes.** "Spooky Festival / Candy: 0 Green, 0 Purple / Points: 0" (048) |
| 23 | Calendar Alerts missing from `/settings`; Bazaar vs AH rule (endgame #11, #12, minor) | menus | **Yes.** Settings -> Notifications has "Calendar Alerts ... Currently: Important only"; SkyBlock Menu Bazaar and Auction House icons both say "... or from anywhere with the Boon Buff." |
| 24 | `/warp wizard` faces the wall; `/warp da` beside festival mobs (endgame #14, minor) | hub v9 `Landing` | **Yes.** Wizard: the tower centred across open ground, yaw 63 (046). DA: facing the hall door with the Doorman beside it, sidebar "Dark Auction" (047); nearest mob a Lv15 wolf at 18 blocks |
| 25 | `/sb warpto garden` silent on a first visit (endgame #17, minor) | core world | **Yes.** RCON reply "Building W4Verify's instance of The Garden first...", the player sees "Preparing The Garden - the first visit builds it...", arrives after ~16 s, log `[world] warpto garden W4Verify: arrived` |
| 26 | Mana / no-arrows chat spam (endgame #11, minor) | core items, weapons, dungeons | **Yes (mana).** During the F2/F3 Wither Impact spam: "You do not have enough mana!" at most once every ~3.1 s |

**Not re-checked this pass:** Creeper Beams lantern reach, Wither Impact on the Three Strangers chest, blood-room stragglers
(dungeon rooms; the boss floors were entered by `skip boss`), the name changes (loops #7), Blossom Bracelet gates (self-test
`garden.market-gates` PASS), the Fairy-room exit and ceiling spiders.

### Still broken

1. **TestPilot `/walkto` still walks off an island edge** (#16, major for the AI show). Deterministic on the Private Island:
   from the ledge (979.5, 72, -2086.3) (where a walk toward `spawn.z - 300` ends with `noRoute`), a sprint walk toward
   `spawn.x - 300, spawn.z + 40` steps off the jagged rim at about (972, 73, -2086.5): 3/3 deaths, `edgeStops: 0`, on a planned
   step. The same ledge is also a trap the other way: walking back to spawn from it answers `noRoute` at once.
2. **TestPilot `/walkto` cannot get down the Tavern ladder** (#7, minor): from the upper floor (61.5, 80, 19.5) to the Graveyard,
   six 40 s legs circled the ladder hole at (59, 80, 17) without descending.
3. **F3 solo is still a loss** for a Wither Impact spammer with Superior Dragon + Highlord (~46 s, up from ~20 s). F1 and F2 are
   now clearable solo with the same kit.
4. **Kuudra Basic solo supplies** are still out of reach for an agent: 10 knock-downs in about 3 minutes, including right after
   the drag back to the platform. Follower damage is down to ~30 per hit and nothing hurts after VICTORY, as claimed.

**State left behind:** `online-mode=true` restored (written byte-for-byte, the motd intact), the server restarted on the same
build and **left running** (35/35 modules, `/sb selftest` 415 passed / 0 failed, `ops.json` empty); `W4Verify` and the earlier
`W4Final` de-opped; both clients killed; the focus watcher stopped. `W4Verify`'s profile remains in `skyclone.db` (skills 30,
Catacombs 30 Mage, HOTM 7, Zombie Slayer 3, Superior Dragon, Highlord, a Basic Kuudra clear, the Garden built). No dial was
changed; the Spooky Festival force was ended; the two hub doors the walker opened are closed again. `SKYCLONE.html` regenerated
(`tools/gen_overview.py`: 2,018 names, 23 islands).

## TestPilot walker fix (2026-10-05)

Fixes "Still broken" #1 (`/walkto` walks off island edges, major) and #2 (cannot get down the Tavern ladder, minor) from the
wave-4 final verification above. TestPilot only (`tools/testpilot/`); no plugin code changed.

**Why it fell.** The old edge guard looked for floor under the box a little way ahead, and only while on the ground. The
islet's north rim on the Private Island is a one-wide staircase ledge stepping down 74 -> 73 -> 72 with the void past its
end; every step DOWN puts the player in the air for ~5 ticks and a sprint carries ~1.4 blocks past the lip - off the end
of a one-block ledge, with the guard switched off because the player was airborne. **Fix:** `Motion.java`, a replica of
the client's own movement physics (friction, air drag, sprint, sprint-jump kick, gravity, auto-step, ladders, real block
shapes; pinned to vanilla's walk/sprint/jump/ladder numbers by `MotionTest`). Every tick the walker plays its keys
forward and tones them down (no sprint, no jump, let go, brake) unless braking right after still ends standing on
something - and never with the body's core over a drop deeper than 3.5. No sprinting within 1.5 blocks of such a drop.
A target past an edge ends `stoppedBy: "blocked: edge - ..."`, `edge: true`. `/move` goes through the same guard
(the AI show's stuck-recovery answers a stop at an edge with "move forward, jump, sprint"). The planner no longer drops
into a pit it cannot climb out of for a block of progress (the ledge above was exactly that: "no route" back to spawn),
and a partial plan must beat the player's own spot by a block (live: two equal dips on the islet's west rim ping-ponged
for the whole 40 s timeout).

**Why the ladder failed.** The hole counted as "reached" from 0.4 away and the walker stopped pushing there, standing on
the rim: the ladder's panel takes 3/16 of a one-block hole, so the body only drops in inside a 0.21-wide window. **Fix:**
`Nav.grabPoint` finds the spot where the whole body clears the panel, the walker creeps onto it (speed limit on both
axes) until it is on the ladder, slides, and does not press W toward the exit until its head clears the floor above (W
into that floor climbs). Vines count as climbable now; arrival with a `y` also needs the same storey (within 3 blocks).

**Verified live** (fresh dev user `WalkFix`, TestPilot port 25629, focus watcher running; screenshots + json in
`docs/playtest/walker-fix/`):

| Test | Result |
|---|---|
| The playtest's exact walk: from the islet ledge (island-relative -44.5, 72, -38.3) toward (spawn.x - 300, spawn.z + 40), sprint | **3/3 no fall** (before: 3/3 fell). Stops at the ledge's last block, `blocked: edge` |
| 5 sprint walks from spawn toward points 300 blocks out past the west / north rims (100-215 deg) + 2 from the islet (west, north) | **7/7 no fall**, all `blocked: edge` in 4-18 s; largest free fall in any walk 2.7 blocks (a planned 3-block step) |
| **Edge total** | **10 walks toward off-edge targets, 0 falls** (plus 4 raw `/move` forward/jump/sprint bursts off the ledge end: 0 falls) |
| Tavern ladder (59, 80, 17), upstairs -> ground floor below, from the playtest's spot (61.5, 80, 19.5) | **3/3 arrived**, ~3.1 s each, 0 replans |
| Same, from two other upstairs spots / to another ground-floor point | 2/2 arrived |
| Ground floor -> upstairs (up the ladder) | 2/2 arrived, ~3.4 s |
| Tavern upstairs -> Graveyard (160.5, 76, -20.5), the leg that circled for 4 minutes | arrived in 23 s, 109 waypoints, 0 replans |

Gates: `bash tools/check.sh tools` CHECK OK (32 unit tests: `MotionTest` 8 new - physics vs vanilla, the island guard,
the step-down creep, the playtest walk replayed headless on a box cut from the real Private Island
(`src/test/resources/nav/private_island_nw.txt.gz`: falls without the guard, holds with it), 10 off-edge walks, the
Tavern ladder both ways, guard cost ~20 us a tick). **Still imperfect:** knockback can still throw a player off (the
guard governs keys only); soul sand / cobwebs / slime are not modelled; walks toward a far target past a long jagged rim
can take ~12-18 s of rim-walking before "blocked: edge"; the screenshots look out over the void, so they show the
coordinates more than the edge.

## Wave 5 newcontent pass — 2026-10-06 (11:04–12:16 and 15:35–16:00, server time)

Fresh dev user **W5New** in a real client through TestPilot (port 25629; focus watcher ran the whole time, 0
violations in `focus*.jsonl`). The session was cut by a usage limit at 12:16 and resumed at 15:35 from the same server
and the same profile. Gates: `bash tools/check.sh all` CHECK OK (1104 files), `gradlew build` BUILD SUCCESSFUL (all unit
tests), live `/sb selftest` **648 passed, 0 failed** before and after the fixes. Admin was used to reach mid/late
content (skills 30, later 50; HotF tier 7; HotM 7; slayers 7; trophy titles; coins). 80 screenshots in
`docs/playtest/wave5-newcontent/` (numbered in play order; cited below by number).

### What was played, and how it went
| Area | Played | Result |
|---|---|---|
| Hunting | `/hunting` menu, Habitats, Lasso (Foxtrot, 006), Hunting Box, syphon to Foraging Speed I (008), Fusion Foxtrot + Ringlurker -> Gleediver (010), Black Hole + Huntaxe on a Parched (011-012, shard caught), Huntrap in the Wilderness (caught 2x Rana after 20 m), Lushlilac Bonbon (+0.62 Charm), Fishing Net in the Lotus Lagoon (Reeflotl, 063) | works; 2 fixes (canopy critters, habitats) |
| Lotus Atoll | warp, walk, pier fishing (Lotus Guardian, Atoll Croaker, Grof, Trophy Frog BRONZE), Professor Quennell + Frog Ledger + release for Lotus (016-018), launch pad to the Highlands (019-020), Frogcoin wishing pool -> blessings -> Lotus Eater awake + shop (021-022) | works; launch pad fixed |
| Torrent Canyon | warp (HotF IV gate), Canyon Journal + Calla (024), Torrent Springs fishing (Haggard, Sprawl), Parched (Lv58, 45k HP) | works; **drowning at the Springs** (open #1) |
| Chocolate Factory | `/cf`, clicks, hire/promote Rabbit Bro, production breakdown; Hopsworth's Hunt forced: Breakfast + Supper eggs found in the Hub, Egg Seeker compass (026-029) | works |
| Extras | Ringphone bought, contact added, **call from the phone opens Dialer Pim's shop remotely** (030, 073); City Projects funded Pier Planks (+2 Fishing Speed, 031); Fire Sale bought a skin for Bits (032) and applied it with `/skins` (059); Harp Ode to Joy 100% -> +1 Intelligence (033-034) | works; 3 fixes |
| Soulstream | internalize 383, Soul Howl (Voidedge Katana) and Phasewarp spend it (035, 078) | works; message fix |
| Dwarven Mines | minecart Village -> Forge and Forge -> Deepking's Gateway, 448 blocks in 19 s (036-037) | works |
| Crystal Caverns | pass bought (10,000 coins), lobby forced to Day 19: 60/30/10 s warnings, sent to the Dwarven Mines, re-entry waits for a new lobby (038-039) | works; rename fix |
| Minions | Medium Storage placed beside a Cobblestone Minion = a real chest that opens the minion's storage (040-041) | works |
| Rift | Riddler Nyx, soul 1 (walk in), soul 6 (Thicket Flower Pots in order) (042-044) | works |
| Carnival | Fox perk forced, tickets claimed, warp, Fruit Digging (046-047), Catch a Fish (2 caught, 071-072) | works |
| Kuudra | `/kuudra` menu + questline gate, Basic hunt opening scene (048-052), forced win -> LEVIATHAN SLAIN, chests (069-070) | works; name-card fix |
| Wardrobe | armour wardrobe swap; Equipment Wardrobe now really equips (053, 060) | fixed |
| Effects that were lore-only | Shriveled Bracelet +10 MF vs arthropods only (`/sb progression bestiary vs`: 10 vs spider, 0 vs zombie); Rat Shapeshift + Turbo Tail (054); Ember "Nether Lord" lava -> obsidian (055); Magma Lord 5/5 with the necklace = Lord's Blessing (064); Gold Hunter Peace Treaty: Sea Creature Chance 41.5% -> 0%; Witch Mask Bat Swarm killed a Graveyard Zombie on its own (065); Bat Person Artifact +10 Def/Str/Int per bat (mask's 2 bats = +20, + Bat pet = +30); Anita Artifact +25 Farming Fortune only while a Harvest Fair runs; Soulstream Pile unlocking the internalized pool | all work |

### Fixed during play (rebuilt, redeployed, re-checked in the client)
1. **hunting - land critters spawned on tree canopies.** The spawn spot was the first solid block from the top, usually
   leaves, so Foxtrots stood 12 blocks up where nobody could see or lasso them (005). `Critters.spot` skips leaves.
2. **hunting - Habitats said "Island: Galatea", and two habitats pointed at the wrong island.** Lore now uses the island's
   display name; `canyon` moved to `torrhus_canyon/helix_grove` ("Torrent Heights") and `lagoon` to `lotus_atoll`
   (galatea built both as their own islands) - `habitats.json` + `tools/gen/hunting/gen_hunting.py`. Verified 061, and 6
   critters live in the Lotus Lagoon.
3. **Reference island names leaked through title-cased ids** in the phone ("Found on: Galatea"), the harp, baits, fishing
   menus and salts. One `islandName()` helper per file reads `IslandDef.displayName` (extras Phone/Harp, fishing
   Baits/FishMenus, hunting HuntingMenus).
4. **galatea - stepping on the Atoll launch pad did nothing** (only clicking Launcher Hobb launched). Stepping onto the
   slime now launches the same way (`Atoll.onPadStep`). Verified.
5. **galatea - menu title "Galatea Travel"** (reference proper noun) -> "Outer Islands".
6. **"Abiphone" shown to players** in Hopsworth's Number, two merchant lore lines and the Bits Shop category -> "Ringphone"
   (chocolate, shops, bank + both generators); the hunting and materials generators' rename tables said "Callstone" /
   "Callphone" and now say "Ringphone" too, so a regen stays consistent.
7. **bank - the Community Shop never led to City Projects or Fire Sales** (extras built both). Buttons at slots 38/42 (062).
8. **extras - `/skins` did nothing**: three paged menus were built and never `.open(p)`-ed. A scan of every
   `Sky.menus().paged(` statement found no other case. Verified (skin applied, lore line "Skin: ...").
9. **"Crystal Hollows" shown to players** (docs/names says Crystal Caverns): travel menu, Deepking's Gateway line, the
   island guide, the bestiary family, the Mining skill unlock line and 13 accessory/fishing/hunting/pet lore lines, in the
   JSON and in the generators (progression, skills, accessories, fishing, pets, hunting rename table). Verified in
   `/islandguides`.
10. **kuudra - the opening name card said "Basic | 0 health"** (the boss had not spawned yet). `Run.expectedBossMax()`;
    now "Basic | 5M health" solo.
11. **Equipment Wardrobe could only "Take Set"** (storage request 9 was open): accessories' `Features.Equipment` provider
    now has `equipped(p, slot)` / `setEquipped(p, slot, item)`, storage's bridge binds to them by itself
    (`/sb storage info`: "bridge live"). Equip and unequip verified (060).
12. **armor - `/armor me` said "You are not wearing any armor."** while four pieces were worn whose requirements were not
    met. Now "No Usable Armor - you do not meet the requirements of the armor you are wearing" (080).
13. **soulflow - "You don't have enough Soulstream!" with 376 internalized.** The pool is locked without a Soulstream
    accessory (reference rule), but nothing said so. A second line explains it and points at `/soulflow` (079).

### Still open
| # | Module | Severity | Did / expected / actual |
|---|---|---|---|
| 1 | galatea (world) + tools/testpilot | **major for the AI show** | Fished at the Torrent Springs spot (-7.5, 68, 25.5). Expected: a sea creature fights you on the bank. Actual: the spot is a one-block bank over a ~4-deep spring; a Sprawl's or Haggard's knockback puts you on the bottom (075-076) and you drown in ~9 s once air runs out (077). Reproduced 3 times (once with `/sb fishing seacreature W5New sc_sprawl`), half the purse lost each time. A human swims out; a TestPilot pilot never surfaces. Suggest: TestPilot holds jump while its head is under water, and/or a shallow ledge or rail at the spot. |
| 2 | hunting | minor | Water critters in the Lotus Lagoon spend most of their time 8-12 blocks down and 20-60 blocks from the pier, while a net reaches ~7 blocks: a shore player waits ~1 min for one to drift close (it did, 063). Keeping water critters within a few blocks of the surface would make nets usable from the pier. |
| 3 | hunting (admin) | minor | `/sb hunting spawn W5New SHARD_INKLOTL` -> "Unknown critter": the Inklotl's shard id is the reference's `SHARD_SEPIALOT`. Admin tools could accept the display name. |
| 4 | tools/testpilot | minor | In Rat form (2x speed) the edge guard let the pilot walk off the north end of the Leviathan dock into the void (12:04, half purse). The guard's physics assume normal walk speed. |
| 5 | armor | cosmetic | Witch Mask Bat Swarm's EXPLOSION particle at a target 1-2 blocks away fills a third of the screen for a moment (065). **FIXED 2026-10-06** (bat-sized burst, see handoff/armor.md) |
| 6 | extras / shops | cosmetic | Dialer Pim on the phone: "I keep every model in the back", but he sells only the Ringphone Basic; the other 17 models are at Phone Dealer Ude on the Crimson Isle. |
| 7 | soulflow | unverified | Phasewarp toward a block 17 blocks away landed 8 blocks along the line (078); may have been terrain in the way. |

Not covered this pass: Critter Safari, Rock / Armadillo pets, Rat Cheese Nose, Great Spook (needs a second player),
Organ Donor (Glacite mineshafts), Zombie Shootout booth, a full Kuudra run (only the opening and a forced win), Chocolate
prestige / Time Tower, more than two Rift souls.

Finish: W5New de-opped, its client killed (only that one), `online-mode=true` restored, server restarted and running.

## Wave 5 regression pass — 2026-10-06 (16:12–17:31, server time)

Fresh dev user **W5Reg** (and **W5Reg2** for the trade) in real clients through TestPilot (ports 25629 / 25628; the
2026-10-06 16:09 TestPilot jar with the rat-body edge guard was installed first). `focuswatch.py` ran the whole session:
**0 violations** (test windows `exstyle 0x8040110`, `noactivate: true`; the foreground was the owner's own 26.2 client,
Brave, Streamlabs, Spotify, Terminal). Rocket League was not running at any launch. Only SkyClone's server was stopped and
started (5 restarts to deploy fixes); the owner's client was never touched. Gates: `bash tools/check.sh all` CHECK OK
(1104 files) before and after; `gradlew build` green (**1064 unit tests, 0 failures**) before and after; boot 40/40
modules, 0 FAILED on every deploy; live `/sb selftest` **650 passed, 0 failed** before and after the fixes.
87 screenshots in `docs/playtest/wave5-regression/` (cited by number), focus log `focus.jsonl` beside them.

### Regression checklist (what worked in wave 4 — does it still?)
| Area | Played | Result |
|---|---|---|
| First 25 minutes, no admin | Gerald (001-004) → starter kit → chopped the plaza oak → 2x2 planks + workbench (006) → Recipe Book "Craftable Now" Supercraft sticks, Wooden Pickaxe, Wooden Sword (007-008) → quarry mound: 5 cobblestone, Mining XP (009-010) → SkyBlock Menu (011) → Cobblestone Minion on pad 1 (012) → walked through the island portal to the Hub (013) → Village directory → Banker Odell, deposit half (014-016) → Builder, sold cobblestone (017) → Bazaar Agent refuses at Level 0 with how-to (018) → Auctioneer (019) → Wren the Fairy (020) → first Fairy Soul (021) → Farm (024) → 10 wheat (025) → Graveyard (026-027) | **works**. 15 objectives in ~25 min, every step names the next one. 86 SkyBlock XP by then |
| Minion | Back after ~20 min: menu (028) shows production/h and "Storage full in"; Collect All → 24 cobblestone | works (collecting is silent, as in the reference) |
| Bazaar at Level 5 | Admin XP to 500 → level-up box lists "Access to the Bazaar" → Agent opens `Bazaar ➜ Farming` (029) → Sell Inventory Now with its review screen (030) → instant-buy 1 Coal (031) | works |
| Slayer T2 | Brannoc → Ghoul Horror tiers with the per-component damage bill (033) → Tier II paid 7,500 → Graveyard kills count (36/1.4k, 035) → bar full: "stirs, heal up" → boss rises (038) → killed in 1:03, lowest Health 604/612 (Superior Dragon, Undead Sword Smite V) → Zombie Slayer II box (039) | works |
| Mining commission | Delver's pass (admin) → `/warp mines` → Royal Mines → mithril mined counts 1/250 → admin-complete → claimed at Emissary Torvald: Mining/HotM XP, powder, daily bonus (085) | works; **the Goblin Raid killed the player 11 times** (fixed in part, open #1) and the pickaxe hint was wrong (fixed) |
| Barn farming | `/warp barn` (048) → Potato Field, Potato collection + Farming XP (049) | works |
| Garden visitor | `/warp garden` builds the instance (050) → admin-spawned visitor Tarlow → 172 wheat → OFFER ACCEPTED + two visitor milestones (051-052) | works |
| Bayou fishing | Bait-seller Mae → Sponge Rod (600) → dock: 4/4 casts bit in 14-21 s, Trash Gobbler + Dumpster Diver (056) | works; **the bot drowned chasing the sea creatures under the dock** (open #2) |
| Dungeon F1 solo | Sexton Abel → Catacombs Gate → Floor I → GO (062). With the Undead Sword (Berserk) killed in room 2 by a starred Crypt Souleater (066). With wave 4's kit (Highlord + Implosion/Shadow Warp/Wither Shield bound at the Scroll Altar, Mage): 5 rooms explored, 56% cleared, a Wither Key dropped; then admin `skip <p> boss` → **Grinko killed in 20 s, 0 deaths, lowest Health 398/684**, score 209 (B), Wood + Gold chests (071-072), Wood Chest opened | works (same as wave 4); TestPilot "no route" from the start room (open #3) |
| Auction House | Create BIN: picked the Undead Sword, typed the price **in chat** while the sign editor was open (the chat fallback works; TestPilot cannot type on a sign), confirmed (073-075); an AH agent bought it for 60 coins; bought a Bat Person Talisman BIN for 472 (077) | works |
| `/trade` (second client) | `/trade W5Reg2` → "[CLICK TO TRADE]" → `/trade W5Reg` back opens both screens (078) → talisman offered → "getting nothing back" double-accept within 5 s → TRADE COMPLETED on both sides, item moved (079-082) | works |

**No wave-4 feature regressed.** Nothing that passed in the wave-4 passes failed here.

### Fixed during the pass (rebuilt, redeployed, re-checked where noted)
1. **mining — Goblin Raid death loop** (`MiningEvents.raidTarget`). Raid goblins (Lv40, 250 damage) were spawned 3 at a
   time on EVERY player on the island every 15 s, wherever they stood and however many were already on them. Walking
   from the Dwarven Village to the Royal Mines during a raid: **11 deaths in ~150 s, 520,000 coins lost** (044) - every
   respawn walked back into the same pack. Now no raid spawn on a player in a SAFE / NO_MOBS zone, for 30 s after a
   death, or with 3+ raid mobs already within 16 blocks (TUNE constants). Re-checked: the same walk during a forced raid
   arrived with 0 damage (045); in the Royal Mines Station (SAFE) nothing spawned. **Not enough** - see open #1.
2. **mining — wrong Breaking Power hint** (`DigEngine.bpHint`). A Mithril Pickaxe (Breaking Power 5) at HotM 1 was told
   "You need a tool with Breaking Power 4 to mine Mithril!" (its unmet `hotm:2` requirement zeroes the power). Now "You
   can't use your Mithril Pickaxe yet: requires Heart of the Mountain Tier 2!" - verified (086).
3. **mines — the `/warp mines` refusal said only "Requires the Delver's pass to the Dwarven Mines!"** Now adds "(Delver
   Thornley, bottom of the Deep Caverns, Mining XII)" (the dial value).
4. **island — `/is home` printed the help.** The welcome box reads "/is home" as plain text (the grey word is a
   description) and an agent types exactly that. `home`/`go`/`warp`/`tp` now go home - verified.
5. **fishing — `/warp bayou` "Unknown destination"**: the reference's warp id now works as an alias of `backwater_bayou`
   (still one entry in Fast Travel - verified).
6. **progression — kill objectives at a district's edge.** A Graveyard Zombie killed at the west gate (x 135.8; the area
   starts at 141) counted nothing toward "Slay Zombies in the Graveyard" (027). Kill objectives now accept the mob or the
   killer within 8 blocks of the area (`Objectives.KILL_AREA_MARGIN`). Compile + unit tests only (the profile was past it).
7. **progression — gate wording** "You are [0] SkyBlock Level (60/100 XP)" → "You are SkyBlock Level [0] (60/100 XP)".
8. **progression hints** (`objectives.json` + `gen_progression.py`): "Mine Cobblestone" said "A lava and water pair makes
   an endless generator" - the island has no generator, it has a regrowing quarry; it now points at the quarry mound
   south of the minion terrace. "Talk to a Merchant" now names the Builder/Weaponsmith/Armorsmith/Adventurer and `/village`.
9. **combatzones — "Slay a Undead / a Aquatic / a Arthropod monster"** → "an" before a vowel (`MobTypeGuide`).
10. **dungeons — the GO! subtitle ran off both edges at 1280x720** (062) → "Find the keys, open the Blood door".
11. **bazaar — "24h volume:0 traded"** → "24h volume: 0 traded".

### Still open
| # | Module | Severity | Did / expected / actual |
|---|---|---|---|
| 1 | mining (Goblin Raid) | **major for the AI show** | After fix 1, forced a raid while standing in the Royal Mines with Superior Dragon (689 HP, 644 Def): **killed by the Goblin Warlord in 9 s** (087) - `start()` spawns a Lv90, 15k HP, 700-damage Warlord beside every eligible player. Six Lv40 raid goblins also killed the same player in ~16 s while mining (047). The reference raid (NEU bestiary "Goblin Raiders") is mostly **Lv5 weaklings** with a few strong goblins, in a raid zone players go to; here every raid mob is Lv40+ and it hunts every player on the island. Suggest: a raid zone (or opt-in), mostly weaklings, the Warlord rare. Events start every few minutes, so an agent mining in the Dwarven Mines will keep dying to this |
| 2 | tools/testpilot (+ fishing) | **major for the AI show** | Same class as wave-5 newcontent #1, now at the Bayou dock (the main fishing spot): sea creatures surface **under the dock** (Trash Gobbler y 64.5, Dumpster Diver y 62-63), `kill_nearest` follows them into the water, the bot never surfaces: "You died (drowned)", half the purse (98,937). TestPilot should hold jump with its head under water (and/or attack from the deck) |
| 3 | tools/testpilot + dungeons | major for the AI show | Right after GO in Floor I, `/walkto` from the spawn spot answers `noRoute` with **0 nodes expanded**, twice in two runs: the start room has a green carpet runner and the player is placed at y 71.0, inside the carpet block (its top is 71.0625), so the planner has no start cell. One `/move` forward fixes it. Either dungeons place the party on top of the carpet, or `Nav` accepts a start cell whose floor is a carpet/snow layer |
| 4 | dungeons (balance, solo) | minor | With the Undead Sword (Smite V) + Superior Dragon, room 2's starred Crypt Souleater killed a 683-HP player in ~30 s; F1 solo is only safe with the Highlord + Wither Impact kit, as in wave 4 |
| 5 | dungeons | cosmetic | Reward chests title the book plain "Enchanted Book" (Combo I only in the lore), and the open line in chat says just "Enchanted Book"; slayer/shops already title books "Enchanted Book (Smite VI)" |
| 6 | auction | cosmetic | After a successful BIN purchase the view reopens on "Auction not found": reads like an error right after "You purchased ..." |
| 7 | progression (pacing) | note | ~86 SkyBlock XP in the first 25 minutes of objectives; the Bazaar (Level 5 = 500 XP) is ~2 hours in at that pace |
| 8 | sidebar / chat noise | note | At the Farm during a Harvest Fair the Objective is replaced by the fair (faithful priority rule; the objective still prints in chat); Brannoc repeats his three intro lines on every click; each Bestiary tier-up is an 8-line box (one dungeon room produced ~10) |
| 9 | naming | cosmetic | "Bottle of Gerald's Wit" (AH) shares its name with Gerald the island helper |

Admin used: SkyBlock XP to 500, Combat 12 → 20 / Mining 12 / Farming 10 / Fishing 15, Zombie Slayer 1 + a T1 kill, gear
(`/sb give`, `/sb armor give SUPERIOR_DRAGON`, enchants, Highlord + scrolls), coins, `/sb slayer progress`, Delver's pass,
HotM tier 2, commission complete, garden visitor spawn + 172 wheat, Catacombs 30 Mage, `/sb dungeons skip <p>` (keys,
then boss), forced Goblin Raids. **State left behind:** `online-mode=true` restored (written byte-for-byte), server
restarted on the final build and running (40/40 modules), `ops.json` empty (W5Reg de-opped, W5Reg2 never opped), both
test clients killed (only those), focus watcher stopped. W5Reg / W5Reg2 profiles remain in `skyclone.db`.

## Wave 5 final verification — 2026-10-06 (17:46–18:22, server time)

Fresh dev user **W5Final** in one real client through TestPilot (port 25629; the 17:44 TestPilot jar with the breath
guard / `dry` walks / carpet start was installed first). `focuswatch.py` ran the whole session: **0 violations** (test
window `exstyle 0x8040110`, `noactivate: true`; the foreground was the owner's own 26.2 client and Explorer). Rocket
League was not running. Only SkyClone's server was stopped/started. 17 screenshots + `focus.jsonl` in
`docs/playtest/wave5-final/`.

### Gates
| Check | Result |
|---|---|
| `bash tools/check.sh all` | **CHECK OK (1109 files)**; `check.sh tools` CHECK OK |
| `gradlew build` | BUILD SUCCESSFUL, **1085 unit tests in 121 classes, 0 failures** |
| Boot | **40 / 40 modules enabled, 0 FAILED**, 0 islands queued (galatea's v2 islands were built at 16:13) |
| `/sb selftest` | **669 passed, 0 failed** (6.2 s of main thread over 57 ticks) |
| `tools/gen_overview.py` | the five wave-5 modules added to `MODULES` (group "Wave 5") and Lotus Atoll / Torrent Canyon / Critter Safari to `ISLANDS`; SKYCLONE.html rewritten (3151 names, 26 islands) |

### Wave-5 fixes re-verified in the client
| Claimed fix | Did | Verdict |
|---|---|---|
| mining — Goblin Raid is one district (regression #1) | `/sb mining event start dwarven_mines GOBLIN_RAID 3` four times, Superior Dragon (594 HP, 642 Def). Standing in the Dwarven Village: **0 raiders**, nothing spawned on the player. Boss bar "GOBLIN RAID in Far Reserve" (007). Raiders appear inside the district only (first seen 6-12 blocks from the player, sampled every 3 s); roster over the last two runs 22 Weaklings / 5 Battlers / 1 Creepertamer / 2 Cutthroats - no Warlord. In Far Reserve, fighting with a Voidstep Blade: 14 kills (12 Weaklings, a Battler, the Creepertamer), Bestiary "Goblin Raiders", a Green Goblin Egg drop | **fixed as designed** (no island-wide hunt, mostly weaklings). Still lethal to stay in - see open #1 |
| galatea — wading shelf at Torrent Springs (newcontent #1) | Walked north off the spot 10 blocks: water 1 deep the whole way, head never under (009). Haggard fought from the bank with the default `kill_nearest`: killed in 60 s, air never dropped, no death | **fixed** |
| TestPilot — breath + `dry` (regression #2) | Bayou dock, a Trash Gobbler moved under the deck (y 63). Default (`stay_dry`): no water entry, clean timeout "stayed out of reach in the water" (013). `stay_dry=False`: chased it under the dock and **killed it in 25 s, lowest air 149/300, no drowning** (014) | **fixed** for both modes during walks - see open #2 for between walks |
| TestPilot — Floor I start inside the carpet (regression #3) | Solo F1, GO at feet y 71.0: three `/walkto`s from the spawn spot all arrived, 12 nodes expanded each (016) | **fixed** |
| dungeons — solo F1 rooms (regression #4, minor) | Undead Sword + Superior Dragon, 3 min in F1: no death, lowest Health 369/594; the Fallen Explorer (25k HP at x0.5) took 115 hits without dying in 25 s. `/sb dungeons balance F1` prints room pack 144 HP solo | **improved** (not a full clear; the harness did not explore far) |
| hunting — admin spawn by display name (newcontent #3, minor) | `/sb hunting spawn W5Final Inklotl` and `SHARD_INKLOTL` both -> "Spawned critter_sepialot" | **fixed** |

Not re-verified live (self-tests / unit tests only): titled Enchanted Books in reward chests, "Auction not found" after a
BIN purchase, the slayer master's intro once, the Bestiary one-box-per-burst, the sidebar overflow to tab, the XP Guide,
Dialer Pim's line, water critters near the Lotus pier, the Rat edge guard (headless `MotionTest` only).

### Still open
| # | Module | Severity | Did / expected / actual |
|---|---|---|---|
| 1 | mining (Goblin Raid) | **major for the AI show** | A player who STAYS in the raided district still dies: idle in Far Reserve, killed by the Creepertamer after ~100 s (12 Weaklings + Creepertamer around him); fighting back in Far Reserve (14 kills), killed after ~2 min ("projectile"). Superior Dragon, 594 HP / 642 Def both times. Leaving the district is safe (verified), and the boss bar names it, so an agent that reads the bar can walk away; one that keeps mining dies. Suggest: cap Lv60+ raiders near one player (the heavy cap is 1 per raid, Battlers are not "heavy"), or a short despawn when a player is under 25% Health |
| 2 | tools/testpilot | major for the AI show | The breath guard only runs inside a walk. After `kill_nearest(stay_dry=False)` returns (or while it attacks between walks), the bot sinks: Torrent Springs past the shelf, **air reached -4 (drowning damage) at y 62** while the kill timed out; Bayou, after the kill the bot was at y 61, air 126 and falling while idle. A later `/walkto` then surfaced it (`surfaced` 1-2). Also, from the Bayou water the walker reached a pocket at (67.5, 64, 6.5) and answered `noRoute` to the dock, the spawn and the shore (015) - only a teleport got it out. Default `stay_dry` never gets there |
| 3 | mining / mines (world) | minor | Upper Mines: a native **Gloomshade** (Lv60) killed the same Superior Dragon player during a raid there in seconds (it was 538+ HP at the previous 3 s sample) |
| 4 | fishing (admin) | minor | `/sb fishing seacreature` spawns the creature on top of the player: at the Springs bank it appeared inside the ground at y 65 under the player (011), so a real catch at the bobber was not reproduced |

Admin used: Superior Dragon, Mining 12 / Combat 20, Delver's pass, HotM 2, Voidstep Blade / Undead Sword / Highlord /
Mimic Dagger / Challenging Rod, forced Goblin Raids, `/sb fishing seacreature` + an RCON `tp` of the Trash Gobbler under
the dock, Catacombs 10, `sb dungeons start W5Final F1 force`. My own mistake, not a game bug: one RCON `tp` without
`execute in` sent the player to the overworld void, and one into the Royal Mines station roof (suffocated). **State left
behind:** `online-mode=true` restored, server restarted and **running** (40/40 modules, PID 2636), `ops.json` empty (W5Final
de-opped), the W5Final client killed (only that one), focus watcher stopped. The W5Final profile remains in `skyclone.db`.
