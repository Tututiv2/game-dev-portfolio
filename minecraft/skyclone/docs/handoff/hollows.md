# hollows — hand-off

Two generated cave islands — **`crystal_hollows`** (shown as *Crystal Caverns*) and **`glacite_tunnels`**
(*Frozen Tunnels*) — plus their mobs, spawn rules, NPCs and the five crystal quests that feed the mining
module's Crystal Nucleus. Mining SYSTEMS (custom breaking, powder, heat, cold, the Nucleus sockets and loot,
commissions, mineshafts, corpses) are the mining module's; this module builds the places and the quests.

## What works (verified how)
| Thing | Verified |
|---|---|
| **Crystal Caverns generator** `com.skyclone.mod.hollows.gen.HollowsGenerator`: 448 x 448 x 161 (x/z -224..223, y 30..190) of solid mineable rock in a bedrock shell, ~27.7M blocks. Five zones: Jungle (NW), Mithril Deposits (NE), Relic Remnants (SE), Goblin Holdout (SW), Magma Fields (everything at y <= 64), Crystal Nucleus in the middle. Lobby-seeded cheese + spaghetti caves, an open magma layer with mounds, pillars, rock masses and lava lakes | `tools/preview.sh` **RESULT: OK** (0 unreachable anchors, 0 floating non-decorative components), cutaway + iso renders read (`tools/gen/hollows/view.sh`) |
| Structures: **Crystal Nucleus** (80-wide round hall; Entrance balcony with spawn, both portals, merchant + guide; a ledge spiralling down the wall to the floor; a dais with the five coloured sockets and a crystal spire; 4 quadrant tunnels + the Magma descent leave its floor), **Jungle Temple** (stepped pyramid, staircase, shrine on top, hidden room inside, jungle trees, pond, Odala's hut), **Mines of Varnas** (pillared deepslate hall, gold trim, minecart aisle, four Keeper niches, Jade pedestal, 24 buried-treasure spots), **Lost Relic City** (streets, ring road, plaza with a 23-tall automaton colossus, ~12 eroded ruins, the Professor's workshop, lava pools), **Goblin Holdout** (huts, fire pit, fishing pool, the King's Burrow throne hall), **Emberdeep** (black pillared hall, lava channels, arena where Magmaw rises), **Gang Hideout** (tents), **Dragon's Lair** (nest + egg), **3 Fairy Grottos** (the only Jasper) | previews + walk check |
| Network: every structure is walkable from spawn; walkways are LOCKED in the grid so caves never hole them — over chasms/lava they become railed bridges | walk check: every anchor reachable (the ignore list only skips landmark., structure. and socket. compass points) |
| **Frozen Tunnels generator** `TunnelsGenerator`: 300 x 300 x 95 frozen rock; Base Camp (spawn, portals, campfire, tents, 3 NPCs), timbered tunnels to a Junction, Old Lupo's Cave, the Frozen Depths, the Great Rimestone Lake | preview **RESULT: OK**, 0 floating |
| Gem veins by zone colour (see mapping below), mithril veins in the NE, ore pockets, rimestone/umber/tungsten/titanium veins in the tunnels | self-test `hollows.gem-blocks-match-mining` compares against `data/mining/blocks.json` |
| Fairy souls: **22** in the caverns, **11** in the tunnels (niches, corners, inside the temple, between the colossus' feet, in the nest...) | unit test + self-test |
| 30 mobs (`data/hollows/mobs.json`) generated from NEU drop pages by `tools/gen/hollows/gen_data.py`; 33 spawn rules (`spawns.json`) + 3 boss rules in code | self-tests `mobs-and-drops`, `spawn-rules` |
| **Amethyst**: Jungle Key (Odala trades Sludge Juice, or the Key Guardian drops it) -> Tidecarver Shrine on the temple -> Door Guardian (owned) -> crystal. A vanished guardian can be re-summoned free for 10 min | code review |
| **Jade**: any Keeper hands out a Metal Detector; held in the hall it reads distance in the action bar + beeps; right-click within 2 blocks digs the next missing Scavenged tool; give each Keeper theirs; 4/4 -> crystal | code review, `/sb hollows detector|dig` |
| **Sapphire**: Professor Gearhart menu, 6 automaton parts or an apparatus -> crystal | code review |
| **Amber**: King Grubnak takes any Goblin Egg -> crystal | code review |
| **Topaz**: Magmaw (200 HP, each player hit = 1 damage, per-hit drops from NEU, summons blazes); every player who hit it in the last 60 s gets the crystal | self-test `magmaw-numbers`, unit test |
| Glacite guardians give 50 Rimestone powder per kill via `Features.Mining.addPowder`; mining Amethyst can wake an Amethyst Leech | code review |
| Unit tests `plugin/src/test/java/com/skyclone/mod/hollows/HollowsPureTest.java` — 5 tests incl. full generation + walkability of BOTH islands | **5/5 pass** (JUnit launcher, run by hand) |
| `bash tools/check.sh hollows` | **CHECK OK** |

### Stained glass -> gemstone (what the mining module's BreakHandler reads)
red Ruby · orange Amber · purple Amethyst · lime Jade · light_blue Sapphire · yellow Topaz · magenta Jasper (grottos only) ·
black Onyx · blue Aquamarine · brown Citrine · green Peridot (the last four only in the Frozen Tunnels). Panes of the same
colours stand on vein floors. Structures never use these colours (windows are cyan glass), so nothing built is mineable
except the rock. Hard stone = stone + the terracottas/wools in mining's table; mithril = prismarine family + light_blue/gray wool.

## What is partial / not built
(Updated wave 5. The wave-3 list's lobby timer, inner temple, real Varnas digging and mineshaft guardians are DONE - see
"Wave 5" at the end.)
- **Lobbies are generations of ONE world**, not parallel instances: a 27.7M-block cave world per lobby/party is not
  affordable, so a closing lobby evacuates and rebuilds (several minutes during which the caverns refuse warps). Newcomers
  to a CLOSING lobby wait (or join a party member's); in the reference they would simply get another lobby at once.
  Ages run on wall-clock time from the moment the lobby's build finished, offline time included.
- Caverns Pass: no Gwendolyn NPC at the Dwarven Mines gate (that island is the mines module's) - the pass is sold from
  `/hollows` (slot 13) and `/hollows pass`. No Dulin permanent-access quest (research marks it "(?)"); the requirement
  dial and `hollows.pass-required false` cover a server that wants free entry.
- Butterflies are passive Allays; Abyssal/water worms etc. are the fishing module's (the lake/pool/lava pools exist).
- TUNE numbers without data: the Goblin Queen and Brood Guards, the Door Guardian (no NEU page), Magmaw's Emberdeep Blazes
  and Ember Bats, the Tidecarver Tribesmen, damage of most cavern mobs (NEU lore carries only Health for them). The Gang
  Grunt now uses NEU's 153,000 HP (was research 03's guessed 10k).
- Build time: the caverns are ~27.7M blocks - at core's 12 ms/tick that is several minutes of background building.
  **The caverns generator is v3 in wave 5**, so the next boot rebuilds the caverns once (the tunnels are unchanged).
- Wave 3 AND wave 5 code is compile- and unit-tested only: the Queen fight, stealth, dart traps, tracker, lobby clock,
  inner temple, buried chests, passes and the mask have not met a live server or client yet.

## Commands added (player + admin)
Player: `/hollows` (aliases `/crystalcaverns`, `/caverns`) — the guide menu (crystal checklist with live progress, Nucleus,
"where am I", **Find a Place**, **Bosses**, warp, Frozen Tunnels); `/hollows find` (tracker), `/hollows bosses` (boss board); `/tunnels` (`/frozentunnels`, `/basecamp`) — warp, Quartermaster shop, commissions, mineshafts.
NPC menus: Prospector Gundry (= `/hollows`), Odala (key trade), Keepers (status + detector), Professor (hand-over), Supply Keeper Dagny
(campfires + corpse keys, Base Camp `npc.camp_supplies`).
Admin (`/sb hollows ...`, RCON-safe): `newlobby` · `rebuild <crystal_hollows|glacite_tunnels>` · `lobby` ·
`crystal <p> <AMETHYST|JADE|SAPPHIRE|AMBER|TOPAZ|all>` · `spawn <p> <mobId>` · `magmaw` · `queen` · `detector <p>` · `dig <p>` ·
`keepers <p>` · `info <p>` · `reset <p>` · `tp <p> <anchor>` · `souls`.

## Dials added
`hollows.lobby` · `hollows.caverns-requirement` (skill:mining:12 & hotm:4) · `hollows.tunnels-requirement` (hotm:7) ·
`hollows.magmaw-hit-damage` (1) · `hollows.magmaw-respawn-seconds` (180, TUNE, restart to apply) · `hollows.magmaw-summon-seconds` ·
`hollows.magmaw-summon-cap` · `hollows.gang-boss-respawn-seconds` (900, restart to apply) · `hollows.thyst-chance` ·
`hollows.odala-sludge` (64, TUNE) · `hollows.temple-retry-minutes` · `hollows.guardian-powder` (50) · `hollows.campfire-price` ·
`hollows.key-materials` · `hollows.key-coins` · `hollows.sidebar-priority` · (wave 3) `hollows.goblin-queen-respawn-seconds` (300, restart
to apply) · `hollows.goblin-queen-guards` (3) · `hollows.goblin-queen-amber` (true) · `hollows.king-stealth` (true) ·
`hollows.king-spotted-seconds` (30) · `hollows.king-sight-range` (6) · `hollows.temple-traps` (true) · `hollows.temple-trap-beats` (3) ·
`hollows.temple-dart-damage` (150).

## Self-tests added
`hollows.mobs-and-drops` · `hollows.quest-items` · `hollows.anchors` (both islands, NPC/boss/portal anchors, >= 12 treasure spots) ·
`hollows.spawn-rules` (mob + area exist) · `hollows.fairy-souls` (10-40 per island) · `hollows.magmaw-numbers` (research 200 HP /
1 per hit / 5% / 4.5%) · `hollows.gem-blocks-match-mining` · (wave 3) `hollows.goblin-queen` (boss flag + behaviour, goblin family,
guaranteed egg, Brood Guard egg rate above the Holdout's, 3 guard waves); `hollows.anchors` now also checks every Find-a-Place
anchor on both islands, `boss.goblin_queen`, the merchant anchors, and the 4 temple traps.

## Requests (things you need from core or another module — the integrator reads this)
1. **mines**: register the warp `dwarven_mines` (both my islands have a `portal.dwarven_mines`), and give the Dwarven Mines a
   `portal.crystal_hollows` and `portal.glacite_tunnels` (my warps are `crystal_hollows` and `glacite_tunnels`).
2. **menus**: add a button for `/hollows` (Crystal Caverns guide) and `/tunnels` near Mining in the SkyBlock Menu / Fast Travel.
3. **mining**: the commission "Thyst Slayer" text uses the reference name; our mob is the *Amethyst Leech* (id `ch_thyst`, still
   matches). My Glacite mob ids are `gt_glacite_*` so your `glacite_slayer` commission counts them.
4. **fishing**: water pools at Jungle (pond), Goblin Holdout (`mobs.goblin_holdout` pool around -142,82,104), lava pools in the
   Relic City / Magma Fields, and the Frozen Tunnels lake (area `fishing.lake`) are ready for the CH / lake sea-creature tables.
5. **materials** also defines `DWARVEN_*` scavenged tools + `DWARVEN_METAL_DETECTOR`; mine register first (enable) with our lore.
   No conflict, just so nobody is surprised by the log line.

6. **mining** (answers your Request 12): hollows no longer registers NPCs on `npc.camp_commissions` / `npc.camp_excavator` /
   `npc.camp_quartermaster` (wave 1 did, so two bodies stood on each anchor). Our supply shop moved to `npc.camp_supplies`.
7. **mining**: mineshaft instances have no guardians. If you want the reference feel, spawn 1-2 of `gt_glacite_bowman|mage|caver|mutt`
   at instance creation (`Sky.mobs().spawn(id, instanceAnchor(...))`); they drop Rimestone powder via my kill hook already.
8. **shops** (answers your anchor request): `npc.gemcutter` (Caverns Entrance balcony), `npc.tribal_trader` (Odala's camp, by the fire),
   `npc.fossil_researcher` (Base Camp, east side) now exist - your secondary merchants can stand there.
9. **fishing**: new zone `goblin_queen_den` ("Goblin Queen's Den") contains `goblin` - it has no water, just so the keyword is known.

## Known risks
- **Build time / size**: 27.7M + 8.3M blocks. First boot builds them in the background for several minutes; players warping in
  before `isBuilt` are refused by core. If the integrator wants it smaller, shrink `HollowsGenerator.R` (all structures sit
  within |x|,|z| <= 205).
- NPC click on the Tidecarver Shrine / Keepers uses core mannequins; King Grubnak uses the GOBLIN_HELMET skull texture as an
  unsigned mannequin skin (falls back to the default skin if Paper rejects it).
- Spawner areas in caves are large boxes; on very porous noise some rules may report `no valid spot` until a player is near.

## Wave 3 (2026-10-04)
| Thing | Verified |
|---|---|
| **Duplicate Base Camp NPCs fixed**: wave 1 registered Brina/Oskar/Ilse on the same three `npc.camp_*` anchors as mining's Frosta/Brask/Hild, so two bodies stood stacked at each. Hollows now leaves those to mining; its supply shop is **Supply Keeper Dagny** on the new `npc.camp_supplies` | code; anchor `ok` in the walk report |
| **Merchant anchors** for shops: `npc.gemcutter`, `npc.tribal_trader` (caverns), `npc.fossil_researcher` (tunnels) - those merchants stop being "travelling" | walk report: all `ok` |
| **Goblin Queen's Den** (`gen/QueenDen.java`): mud-brick brood hall south of the Holdout (x -126..-100, z 183..203, floor 82), one lit tunnel from the fire pit, a hay-ringed egg clutch (turtle + sniffer eggs), crates, chandeliers, a fairy soul (caverns now 22). Zone `goblin_queen_den`, anchor `boss.goblin_queen`, area `mobs.goblin_queen_den` | preview RESULT OK, crop iso read, unit test |
| **Goblin Queen boss** (`GoblinQueen.java`, mob `ch_goblin_queen`, Lv200 / 250k HP, TUNE): a normal-damage fight; she screeches and calls Brood Guards at 75/50/25%; every player who hit her in the last 2 min gets the **Amber crystal** (research 03) + a Goblin Egg; her table rolls coloured eggs, goblin armour, rough amber. Respawn 300 s. **Brood Guards** (`ch_goblin_brood_guard`, 4 in the den) drop eggs at 6% vs 0.6% outside - the den is THE egg farm | self-test `hollows.goblin-queen`; data regenerated by `gen_data.py` (diff: only the 2 mobs + 1 rule added) |
| **King stealth** (`Stealth.java`, research 07 "avoiding goblin detection"): near the King's Burrow (door yard + throne hall) a goblin with line of sight within 6 blocks spots a player who is NOT sneaking: one shove, a SPOTTED message telling them to sneak, and King Grubnak refuses them for 30 s. The Queen and Brood Guards never count. Dial-off-able | compile; logic review |
| **Jungle Temple dart traps** (`TempleTraps.java` + generator): 4 dispensers set into the stair railings, alternating sides; while anyone is on the stairs they fire darts across in two alternating halves every 1.5 s, so there is always a gap to run through. A dart deals 150 (defense applies) and vanishes; nothing is placed in the world | unit test (4 dispenser blocks at the data positions), crop iso read |
| **Find a Place** (`Waypoints.java`, `/hollows find`, guide slot 29, tunnels menu slot 24): pick a landmark (12 in the caverns, 5 in the tunnels; Fairy Grottos deliberately hidden) and the action bar shows name, distance, compass direction and up/down while END_ROD sparks point the way; clears on arrival (6 blocks), leaving, or 10 min | unit tests (every waypoint anchor exists; compass N/E/S/W/SW/NE) |
| **Boss board** (`/hollows bosses`, guide slot 33): Magmaw / Goblin Queen / Gang Boss alive or down, how to fight each, click to track its lair | compile |
| Metal Detector dig now surfaces the cache as a client-side chest + totem burst for 3 s | compile |
| Amber quest text, King + Old Lupo dialogue point at the Queen's Den, the sneaking rule and Ilse | - |
| Unit tests `HollowsPureTest` | **6/6 pass** (JUnit launcher by hand) |
| `bash tools/check.sh hollows` | **CHECK OK** |

## Wave 5 (2026-10-06)
### Done
| Thing | Verified |
|---|---|
| **Lobby lifecycle** (`Lobbies.java`, pure `LobbyMath.java`; research 03 §12 / 07 §2.11): a lobby opens when its build finishes, stops taking NEWCOMERS on SkyBlock Day 19, closes as soon as fewer than 4 players remain after that, and is force-closed on Day 35 (6h20m / 11h40m at 20-min days). Closing = 60 s countdown (5 s granularity) to everyone inside, all sent to the Dwarven Mines, lobby number +1, caverns rebuilt with the new cave seed. A closing lobby still admits players who were already in it (`HollowsData.lobbySeen`) and **party members** of someone inside (`Features.Party`). While a new lobby builds, entry is refused with "being built". Opening time persists in table `hollows_lobby` (migration 1). Entry gate = requirement prefix `hollows_lobby` appended to the caverns requirement | unit test `lobbyLifecycleMatchesResearch` (day maths, stages, close rule, newcomer/party rule, 6h20m/11h40m); self-test `hollows.lobby-clock` |
| **Inner Jungle Temple** (`gen/JungleTemple.inner`, `TempleRun.java`): research - the Jungle Key "opens the entrance of the temple", inside are a parkour and the Kalhuiki Door Guardian. The shrine on top now takes the key and drops the player onto a start pad INSIDE the pyramid; a 5-platform zig-zag parkour over a 5-deep pit (one gold checkpoint plate) leads to the **Tidecarver Sanctum** (crying-obsidian door, amethyst on a pedestal); entering the sanctum with a paid key wakes YOUR Door Guardian; killing it gives the Amethyst crystal; the lodestone at the east end carries you out to the court. Falling = back to the last checkpoint. The interior is SEALED (no block of it is mineable), so the key is the only way in. A v2 island (no inner temple) keeps the old shrine fight | unit test `innerTempleIsAParkour`: start pad and guardian spot standable, sanctum NOT reachable by walking, reachable with jumps (<=3 blocks, +1 up), checkpoint on the route, inner air never leaks out of the pyramid; walk report still OK (`parkour.`/`temple.` anchors are walk-ignored for that reason); self-test `hollows.temple-parkour` |
| **Real Mines of Varnas treasure** (`gen/Varnas`, `VarnasDig.java`): every `varnas.treasure.N` spot (24) has a REAL chest buried one block under the hall floor, under a `cracked_deepslate_tiles` tile that looks like the rest of the floor (spots whose chest would touch a cave are skipped; never two side by side). The detector leads to YOUR spot; right-click on it highlights the tile; hit it 5 times (any item - the tiles are outside mining's block table, so its custom digging never interferes) and it breaks open; right-click the chest = the lid opens and the lost tool comes out (else a Jade cache). Other chests are "picked clean" for you. Tiles reseal after 30 s (sooner after looting) and ALL reseal on shutdown; a player standing in the hole is lifted out first | unit test `varnasTreasureIsReallyBuried` (chest under every spot, cover tile, sealed on 5 sides); self-test `hollows.varnas-treasure` checks the BUILT world for the chests |
| **Guardians at mineshafts**: mining already spawns the four Rimestone guardians over every shaft's corpses (our Request 7, `mining.mineshaft-guardians`). Faithful to research 03 §11.3 ("Mineshaft guardians") they no longer roam the Frozen Depths: the depths get Rimestone Walkers (`gt_walkers_depths`), and dial `hollows.depths-guardians` (off, restart) puts the old four rules back | data diff: 4 rules out, walkers + 2 tribe rules in |
| **Passes** (`Passes.java`): Crystal Caverns Pass (research: 10,000 coins / 6 h) bought from `/hollows pass` or guide slot 13 (confirm menu; extends; refuses while more than half is left); the Frozen Tunnels need mining's forged `SECRET_RAILROAD_PASS` once - it is punched (consumed) on first entry and access is permanent. Requirement prefixes `hollows_pass`, `hollows_railpass`, both dial-gated | self-test `hollows.passes` |
| **KALHUIKI_MASK lore made real** (`Kalhuiki.java`): new mob **Tidecarver Tribesman** (`ch_kalhuiki_tribesman`, family `ch_kalhuiki`, wears the mask; temple court + jungle). Masked players are never targeted by the tribe and their blows on one are cancelled. The Door Guardian is not a tribe member and still fights | self-test `hollows.kalhuiki-and-bats` |
| **Magmaw's Fire Bats** (NEU BAL_BOSS lore "summon Fire Bats and Blazes"): summon waves now alternate Emberdeep Blazes and 3 **Ember Bats** (`ch_magmaw_bat`, burning touch via behaviour `hollows_ember_bat`); both cleared on its death | self-test `hollows.kalhuiki-and-bats` |
| Research numbers: Odala's Jungle Key 64 -> **100** Sludge Juice (NEU ODAWA_NPC shop); Gang Grunt **153,000 HP** (NEU) | `gen_data.py` re-run, diff = 2 mobs added, grunt health |
| Small fixes: Old Lupo pointed at "Ilse" (renamed Dagny in wave 3: one name, one person); keeper/detector texts describe the buried chests | - |
| Unit tests `HollowsPureTest` | **9/9 pass** (JUnit launcher by hand) |
| `bash tools/check.sh hollows` / `tools/preview.sh ...HollowsGenerator` | **CHECK OK** / **RESULT: OK**, 0 non-decorative floating blocks |

Checked for effects that belong here (accessories / pets / reforges hand-offs): Jungle Pickaxe sludge is mining's (done there);
pets' Goblin "Free-range Eggs" is an `echo_item` pets already applies to ANY gain of the coloured eggs (so our goblin drops are
covered); Bal pet's `bal_cannibal` already matches Magmaw; reforges' Ambered/Blazing are mining's goblin/worm rolls. Only the
Kalhuiki mask was lore-only - now wired.

### Commands / dials / self-tests added in wave 5
Player: `/hollows pass`. Admin: `/sb hollows lobby [day <n> | close]`, `newlobby` (now the same close-and-rotate), `temple <p>`
(key paid + into the inner temple), `treasure <p>` (target spot, its buried block, open tiles), `pass <p> [hours]` (0 removes;
also sets the rail pass).
Dials: `hollows.lobby-rotation` (true) · `lobby-close-day` (19) · `lobby-shutdown-day` (35) · `lobby-close-below` (4) ·
`lobby-lock-newcomers` (true) · `lobby-close-countdown` (60, TUNE) · `temple-parkour` (true) · `treasure-hits` (5, TUNE) ·
`treasure-reseal-seconds` (30) · `depths-guardians` (false, restart) · `kalhuiki-mask` (true) · `pass-required` (true) ·
`pass-price` (10,000) · `pass-hours` (6) · `tunnels-rail-pass` (true). `odala-sludge` default 64 -> 100.
Self-tests: `hollows.lobby-clock`, `hollows.temple-parkour`, `hollows.varnas-treasure`, `hollows.kalhuiki-and-bats`, `hollows.passes`.

### Still left
- Live verification of everything above (no server/client this wave). First thing to look at on the integrator's boot:
  `/sb selftest hollows`, then `/sb hollows temple <p>` and `/sb hollows treasure <p>` with a real client.
- Party-scoped lobbies are only "a party member's closing lobby still admits you"; parallel lobbies are out of reach of
  a single shared world.
- A Gwendolyn-style pass NPC at the mines gate (Request 10) and the Dulin permanent-access quest.
- The guide's lobby lines are not shown in the sidebar beyond `(#N Day D)`.

### Requests (wave 5)
10. **mines**: if you want the reference feel, stand a pass seller by `portal.crystal_hollows` whose click runs
    `Sky.commands().run(p, "hollows", "pass")` (the confirm menu and purchase are ours).
11. **pets**: Goblin "Fetid Thief" is "+Mining Spread while in the Mines of Divan" in the reference; your data says the whole
    Crystal Hollows. Our zone for the hall is `mithril_varnas` (`"when": ["zone:mithril_varnas"]`), if your matcher reads zone ids.
12. **integrator**: lobby rotation REBUILDS the caverns (27.7M blocks, minutes). With the defaults a lobby older than Day 19
    that is empty at a boot closes ~60 s after boot. Set `hollows.lobby-rotation false` on a test server that restarts a lot.
13. **integrator**: `hollows.pass-required` and `hollows.tunnels-rail-pass` default ON (reference). Test accounts need
    `/sb hollows pass <p> 6` (or 10k coins / a forged rail pass) before `/warp crystal_hollows` / `glacite_tunnels`.
