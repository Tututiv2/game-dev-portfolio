# dungeons — hand-off (part A: the framework, part B: bosses, gear and loot)

The module is now one owner's work in two passes. **Part B is summarised first**; part A's original hand-off follows
unchanged below it (its "partial" list is superseded where part B says so). Nothing has run on the live server (the
dev server was off-limits for both passes): the integrator runs `/sb selftest dungeons`.


## Real-game audit, lane 6 (2026-10-06, AUDIT_REAL_GAME_2026-10-06.md C9 C10 C11 C17)
Source: the wiki floor pages (hypixelskyblock.minecraft.wiki, raw infobox `base_xp`/`base_xp2` and the chest tables).
- **C9 base XP** (`gen_dungeons.py` FLOORS/MASTER): E 50, F1 80, F2 160, F3 400, F4 2,400, F5 2,400, F6 4,880 (wiki
  "5k"; 4,880 from SkyblockMoney combat.md), F7 30,000; M3 60,000 (was 36,500). M4/M6/M7 have no wiki value - kept.
  F4 = F5 = 2,400 is what the wiki says; flagged as odd, not contradicted.
- **C10/C17 F6 per-chest tables** (`gen_content.py` F6_WIKI): F6 now carries the wiki's own table for every chest -
  `LootTables.Entry.weights`/`costs` (chest -> weight %, chest -> coin cost). With `weights` set an entry rolls only in
  the chests it names at exactly that weight (no x4/x2 lean) and charges that chest's cost; F6 chests roll ONE item
  (the wiki's percentages sum to 100 per chest). Giant's Sword: Bedrock only, 23M + 2M = 25M, 1/531 (wiki 1/533;
  Summoning Ring is not registered by anyone, so its 0.375% renormalises away). Recombobulator: Obsidian 5M + 1M = 6M
  (1/74) and Bedrock 4M + 2M; never Gold. `LootTables.firstRollChance` gives the exact odds. Other floors keep the
  legacy minChest/weight scheme (the format is ready for them; F7's per-chest table is not ported).
- **Chest base costs** all floors (wiki): F1 25k/50k/100k/250k, F2 50k/100k/200k/500k, F3+ 100k/250k/500k/1M, Bedrock
  2M (was a linear ramp, e.g. F6 Obsidian 880k). Master Mode keeps x2 (unverified).
- **C11 essence**: fixed amounts per chest (wiki F5/F6/F7 all chests, F1-F4 Wood). Research's "1-15 Wither" in F7 Wood
  was the separate 1x Wither Essence roll (now an F6 entry, 0.85%); F6 Wood = 10 Undead + 12 Wither, F7 = 10 + 15.
  The audit's "10-15 / 12-15" upper ends are the Catacombs Looting essence perk (+5%/level, `RewardChests.build`).
  `floors.json` woodWitherMax is now the exact amount; `DungeonsModule.freeChest` gives it unrandomised.
- Tests: `RealGameAuditTest` (4: XP table, F6 prices, Wood essence, Giant's Sword odds arithmetic + 400k dice).
  `bash tools/gen/dungeons/run_tests.sh` 57/57. Not fixed: chest AVAILABILITY per floor (the wiki lists Obsidian on F1;
  `BossLootTest` still asserts research's F1 = Wood+Gold), S+ luck lifts Giant's Sword only ~x1.06-1.2 vs the wiki's
  1/323 at max boosts.

## Wave 5 (2026-10-06) — Master Mode, stars, dungeon accessories and pets, the XP event, class gaps

Verified: `bash tools/check.sh dungeons` = **CHECK OK (266 files)**; **48 JUnit tests pass** (`bash tools/gen/dungeons/run_tests.sh`;
new `DungeonPerksTest` x7, `StarredWeaponsTest` x2). 6 new live self-tests (below). **Nothing run on the server or in a
client** (lane rule): the integrator runs `/sb selftest dungeons.w5` and `/sb dungeons perks <p>`.

### Done
| What | Where | Behaviour |
|---|---|---|
| **`SkyEvents.DungeonXpGain` fired** | `Progress.addCataXp/addClassXp(p, xp, floor, master, source)` + `Progress.fire` | Fired before every Catacombs (track `catacombs`) and class (track = `DClass.name()`) XP grant; the listeners' `amount` is what is added (negative/NaN = 0/original). Runs pass source `run`, `/sb dungeons xp|classxp` pass `admin` (accessories' Catacombs Expert Ring / Professor's books skip admin). The completion box now prints the XP actually added |
| **`Features.Dungeons.masterMode(p)`** | `Progress.masterMode` | True while p is in a Master Mode run: accessories' Master Skulls 1-7 now apply there |
| **Master stars only in Master Mode** (bug) | `model/DungeonPerks.starFraction`, `ClassSystem.boostFraction` | Stars 6-10 gave +5% each on every floor; now only in an M run (research 01 sec 16.2). Essence stars unchanged (+8% here + reforges' 2% = 10%), capped 5+5 |
| **General's Medallion** | `ClassSystem.medallion`, `boostFraction`; `DungeonRun.secret` | Dungeon gear base stats +1% per digit of lifetime secrets found (max 5%) while the accessory is owned (`Accessories.has`). The lifetime counter now counts each secret as it is found (failed runs too; it used to be added only on a completion). Shown on the Floor Records page; `/sb dungeons secrets <p> <n>` sets it |
| **Bone Necklace Diversion** | `ClassSystem.diversionRange/wears` | A Tank wearing `BONE_NECKLACE`/`STARRED_BONE_NECKLACE` (`Features.Equipment.worn`) diverts from 45 blocks instead of 30. Treasure Talisman/Ring/Artifact were already read (`RewardChests.luck`); Soulweaver Gloves are applied by accessories |
| **Pet perks** (pets' info-only list) | `ClassSystem.petValue/storedCharge/ghostAlly/ghostAbility/classStats`, `DungeonRun.hasSpiritPet` | Spirit **Half Life** now Legendary/Mythic only (research: "Legendary Spirit pet"); Spirit **Restless** shortens both ghost-ability cooldowns by a%; Spirit **Ghost Ally**: a ghost with a Spirit pet has a spirit striking the nearest run mob within 12 blocks every 2 s for max(200, 25% best hit) (TUNE); Jellyfish **Stored Charge**: every 2,000 health a Healer heals on allies while Wish is cooling down trims a s off it (max 30 s per Wish); Jellyfish **Strong Brew**: dungeon potion stats x(1+a%) |
| **Second ghost ability per class** (research 04 sec 3.4) | `ClassSystem.ghostSecond` (the Ultimate input while a ghost: Sneak+F or drop the map) | Mage **Instant Wall** (5x3 glass for 10 s, air only), Berserk **Ghost Axe** (50 -> 1,000 by level), Archer **Healing Bow** (the mate you look at: +10% HP, +50 Strength 10 s; 10 s cd), Tank **Stun Potion** (10 blocks), Healer: time until the self-revive. First abilities fixed to research: Buff Potion +30 Str +2/5 lvls, Absorption Potion lasts 3 s, Stun Bow 5 s cd |
| **Class passives** | `ClassSystem.classStats/afterDamage/healAlly/soulTether` | Berserk **Indomitable** (10% +0.2%/lvl of Strength as Defense, TUNE %); Healer **Overheal** (overflow healing -> absorption up to 20% +0.4%/lvl, 30 s); **Soul Tether** to research: 0.5% of the HEALER's max HP/s to the most hurt mate within 30 (dial default 20 -> 30), every 5 Healer melee hits heal both 1%; **Solo Tank**: +50% Defense, barrier on any hit for 20%, 45 s; **Ragnarok** +400 Speed (was +100). Class menu lore updated |
| **M6 Mutant Giants** | `GorrakFight.spawnGiant/giantKits/theGiantOne/onMobKill` | On Master VI the four giants are Mutant Giants (nameplate via `MobService.setDisplayName`, `dungeons.m6-mutant-giant-hp` 600M) using all four kits (the laser stays the Beam Giant's); in Gorrak's last phase they rise again at their pillars beside him and come back `m6-mutant-giant-seconds` (30, TUNE) after each death |
| **M7 Wither King adds + Master Defense** | `WitherLordsFight.kingAdds/masterLordDefense/onDamage` | P5: every `m7-king-add-seconds` (40) a Wither Husk (500M) or Hollow Apostle (800M, 120k; new `dboss_apostle`) near a random player, never inside the pull, max 3. Master Tempestra / Aurik / Morvane take damage through 1,200 / 1,800 / 2,100 Defense (dial `m7-lord-defense`) |
| **Morvane's lift** | `WitherLordsFight.enter(4)` | P4 opens with the party lifted (Levitation 1.5 s, Slow Falling 6 s) and set down on his platform |
| **Lava bounce** (F7 P3 "super bounce") | `DungeonRun.lavaBounce` (every 2 ticks) | Lava anywhere in a run throws a living member up, puts the fire out and costs 5% max HP (once per 0.5 s, TUNE); looking down (pitch >= 40) bounces 30% higher |
| **Floor gate** | `DungeonMenus.lockReason`, `DungeonPerks.previousFloor` | Floors II-VII need the floor below completed, Master II-VII the Master floor below (M1 keeps its F7 requirement). Dial `dungeons.require-previous-floor` (true); `/sb dungeons start <p> <floor> force` skips it |
| **Starred dungeon weapons** (part B "not built") | `gear/StarredWeapons`, `data/dungeons/starred_weapons.json` (`tools/gen/dungeons/gen_starred.py`) | 9 items (Shadow Fury, Last Breath, Spirit Shortbow, Spirit Sceptre, Bonemerang, Felthorn Reaper, Bonzo's Staff, Adaptive Blade, Ice Spray Wand): one tick after boot the weapons module's live base def is copied (abilities, `extra` passives, gem slots) with the reference's starred-minus-base deltas and the ⚚ mark; NEU crafting recipe (base + 8 boss fragments, unlocked by the floor's completion). An id already registered is left alone |
| **Lurid / Morvane Dyes** (part B "not built") | `data/dungeons/dyes.json`, loot.json (`gen_content.py` `DYES`) | `DYE_LIVID` in F5/M5 and `DYE_NECRON` in F7/M7 Bedrock chests; cosmetic, never on the RNG meter (weights TUNE) |
| Boss Guide | `PartB.GUIDE` | M6 mutants and M7 adds mentioned |

New dials: `dungeons.require-previous-floor` true · `m6-mutant-giant-hp` 600M · `m6-mutant-giant-seconds` 30 ·
`m7-king-add-seconds` 40 · `m7-lord-defense` true; `soul-tether-range` default 20 -> 30 (research).
New admin: `/sb dungeons perks <p>` (alias `w5`: Master Mode, Medallion %, Diversion reach, held item's in-run boost,
pet perk values, Stored Charge / tether state, starred weapons registered) · `/sb dungeons secrets <p> <n>`.
New self-tests: `dungeons.w5-xp-event` (a throwaway listener doubles a `selftest`-track event and the doubled amount
comes back) · `w5-master-mode-service` · `w5-stars-and-medallion` · `w5-floor-gate` · `w5-content` (apostle, dyes in the
F5/M5/F7/M7 tables) · `w5-starred-weapons` (each registered, dungeon, >= base damage, has its recipe).

### Still left (wave 5)
- **Never run live.** Watch first: the starred weapons' one-tick-late registration (a module caching item lists at
  postEnable will not see them), the ghost Instant Wall in boss arenas (air only, 10 s), lava bounce velocity feel, the
  M7 Master Defense (x1/22 on Morvane: very slow on purpose; `m7-lord-defense` false to compare).
- **"Potions disabled in dungeons"** still not built: no potions API can suppress effects (request 7 below).
- Not built: necromancer souls / Summoning Ring (Soul Whisper stays "+20% vs undead in runs"), Catacombs passes, the "X
  the Fish" cosmetics, Saul's solo item; Lurid's copies still share one HP value; dragons are scaled Phantoms.
- TUNE numbers added this wave: Indomitable %, Ghost Ally damage, lava bounce damage, dye weights, mutant respawn
  time, king add cadence, the Overheal level step's base. Research gives none of these.
- The essence shop already spends the reforges pouch first (`Features.Essence`, integrator) - the part B note below
  about essence ITEMS only is out of date.

### Requests (wave 5)
7. **alchemy / potions**: the reference disables most potion effects inside the Catacombs (Stamina, Healing, Mana and
   dungeon potions excepted). Check `Sky.service(Features.Dungeons.class).inDungeon(p)` where your effect stats apply,
   or give `Features.Potions` a way to suppress an effect so dungeons can do it.
8. **weapons**: `STARRED_*` copies of your fallback dungeon weapons are registered by us one tick after boot and carry
   `extra.starredFrom = <base id>`; if any ability or passive matches the held item by id, accept the starred id too.
9. **pets (FYI)**: now read here: `jellyfish_stored_energy`, `jellyfish_powerful_potions`, `spirit_spirit_cooldowns`,
   Spirit "Ghost Ally" (active type SPIRIT while a ghost), Half Life (SPIRIT + Legendary). Mark them read by dungeons.
10. **accessories (FYI)**: `DungeonXpGain` is fired, `masterMode` is provided, General's Medallion and Bone Necklace are
    applied here - their `hook` lines can be marked done.

## Finish wave 2026-10-03 — Prince, Soul Tether, Ragnarok zombies, duplicate classes, Spirit first death

Verified: `bash tools/check.sh dungeons` = **CHECK OK (261 files)**; **35 JUnit tests pass** (`CataMathTest.scoreParts`
now also asserts the Prince bonus and the Spirit first-death skill score). Nothing run on the server or in a client.

The six playtest notes handed to this wave (Wither Shield never decays, nameless scripted deaths, Taunt in the lore
but not built, doubled "+20 SkyBlock XP", sidebar Cleared % lag, one-lever Water Boards) were **already fixed** by the
playtest-endgame pass below (rows 3-8) and were re-checked in code: `TimedShields` (Wither Shield 5 s), `Kit.scripted`
source, `ClassSystem.taunt/stance`, the removed first-clear line, the 2-tick HUD signature, `WATER_MIN_LEVERS = 2`.
They still need a live run to be called closed.

| What | Where | Behaviour |
|---|---|---|
| **Prince** | `run/DungeonRun.spawnRoomMobs/makePrince/princeCrowns/onMobKill/score` | Each room mob (not a key holder) has a `dungeons.prince-chance` (0.02) chance to be a Prince: x3 HP, glowing, gold particle crown; players within 30 blocks are told. The first Prince killed by a player: "A PRINCE FALLS!" and **+1 bonus score** (`CataMath.bonusScore`'s prince slot, which nothing fed before). Once per run; after that no more Princes spawn |
| **Healer Soul Tether** | `classes/ClassSystem.soulTether` (from `runSecond`), lore in `DClass` | Each second each living Healer tethers to the most hurt living ally within `dungeons.soul-tether-range` (20): pink line, ally mends 1% (+0.1% / 5 lvls) max HP, Healer half that. Counts toward Healer milestone healing. TUNE |
| **Ragnarok zombies** | `ClassSystem.ragnarokZombies`, lore in `DClass` | Ragnarok also raises 3 allied zombies for 15 s: invulnerable props (cleaned with the run), vanilla AI goals stripped (they can never go for a player), they walk to the nearest run mob within 12 blocks and hit it once a second for 40% of the Berserk's best hit this minute (min 100), credited to the Berserk via `Sky.combat().rawDamage`. TUNE |
| **Duplicate-class penalty** | `ClassSystem.dup`, `classStats`, `outgoing`, `ability`; `DungeonRun.go`; class menu | Two or more (non-left) members on one class: that class's level stats, passive damage bonuses (Berserk/Archer per-level %) and ability damage x `dungeons.duplicate-class-scale` (0.5; 1 = off). Affected players get a chat line at GO; the class menu says so. Ultimates/cooldowns untouched |
| **Spirit pet first death** | `DungeonRun.onDeath/hasSpiritPet/score` | If the run's FIRST death is a player whose active pet (`Features.Pets.activeType`) is `SPIRIT`, that death costs 1 Skill score instead of 2 (`CataMath.skillScore`'s existing flag). Announced in chat. No pets service = no rule |

New dials: `dungeons.prince-chance`, `dungeons.duplicate-class-scale`, `dungeons.soul-tether-range`.
Watch live: the Ragnarok zombies rely on Paper's `Bukkit.getMobGoals().removeAllGoals` + `Pathfinder.moveTo` (a zombie
with no goals still navigates); if the core cancels non-sky-mob spawns in dungeon worlds they simply will not appear
(the Mage's Guided Sheep uses the same spawn path, so it should be fine).

## Fixer pass (playtest-endgame) — the miniboss duel, reach, shields, named deaths

From the real-client endgame playtest (`docs/PLAYTEST.md`, "Open issues", dungeons rows). Verified: `bash tools/check.sh
dungeons` = **CHECK OK (260 files)**; **35 JUnit tests pass** (`bash tools/gen/dungeons/run_tests.sh`; new
`BalanceTest.f1MinibossDuelIsWonAtDefaults` + `rateArithmetic`, `PuzzleLogicTest.ticTacToeButtonsInReach`, the water
test now also refuses one-lever boards). Nothing was run on the server or in a client.

| # | Where | Problem (playtest) | Fix |
|---|---|---|---|
| 1 | `model/Balance`, `Behaviours`, `mobs.json` + `tools/gen/dungeons/gen_dungeons.py` | **F1 miniboss won a DPS race.** Fallen Explorer 40k HP / 400 dmg swung every second plus a 1.2x lightning every 6 s: a 1,079 HP / 678 Def player died in 12-18 s and needed ~17 s to kill it (lost twice). `BalanceTest` counted hits ("19") and passed | Balance now models **time**: `Pattern` (melee every 1 s = vanilla cooldown, special factor/cadence, regen) per behaviour, `secondsToDie`, `secondsToKill`, `margin`; `Behaviours` reads its cadences/factors from `Balance.pattern(...)` so the test measures what ships. `Ref` gained a DPS (MID 2,000/s: below the playtest's measured ~2,350). New assert: the 588/630 reference **outlasts every F1 miniboss at least 2x** (and the old 40k/400 must fail it). Minibosses retuned as a family (HP x0.625, dmg x0.35, ratios kept): Fallen Explorer **25k / 140**, Gilded Monarch 50k / 175, Furious Excavator 28k / 160, Shade Stalker 22k / 210, Frostbound Explorer 31k / 175. F1 duel now: reference lasts 26 s, kills it in 13 s; the playtest character lasts ~50 s, kills it in ~11 s. `/sb dungeons balance <floor>` prints a "duel" line per miniboss (lasts / kills-it-in, per reference) |
| 2 | `puzzle/PuzzleBuilders.ticTacToe` | Top-row buttons 4.75 blocks above a standing eye (h 7), past the 4.5 reach: jump-click only | Rows at h **6/4/2** (`tttRowH`); test checks every row against a standing player's reach |
| 3 | `boss/Kit.hit/hitTrue/pct`, `run/DungeonRun.bossSource`, `run/DefaultBoss` | Scripted attacks passed no source: deaths read "killed by a monster" / "struck down" | Every scripted hit goes through `Kit.scripted(...)` with the **nearest living boss body as source** ("killed by Grinko"); `Kit.scripted()` is true meanwhile so `ClassSystem.mobScale` does not apply the body's melee multiplier (a skull would have shrunk to a quarter) and `ClassSystem.incoming` keeps its old behaviour (no Tank diversion of scripted hits). Side effect, intended: the defensive modifiers that need an attacker now see scripted hits too (Wither Shield's 10% cut, wither-armour and chestplate-aura reductions in `GearEffects`). The stand-in boss's shockwave uses `Kit.hit` too. No living body (between phases) -> no source, as before |
| 4 | `gear/TimedShields` (new), `WitherBlades.shield`, `ClassSystem` (Barrier, Wish, Castle party shield), `RunManager.cleanupPlayer` | Wither Shield absorption never decayed (1,544/944 HP for minutes, across warps): the core's absorption pool only drains on damage | `TimedShields.grant(p, amount, ticks)`: at expiry takes back what is left of that grant (never more than it gave or than the pool holds). Wither Shield 5 s (its lore), class shields 30 s (TUNE). Quitting or leaving a run ends all pending shields |
| 5 | `puzzle/GridPuzzles.water` | A board solved by ONE lever was accepted (1-in-6 guess; the first probe solved it) | `Water.minLevers()`; the generator requires >= `WATER_MIN_LEVERS` (2); `solve()` returns a fewest-lever solution |
| 6 | `classes/ClassSystem.taunt/stance`, `run/RunListener.onTarget/onQuit` | Tank lore listed **Taunt**, not built (and **Defensive Stance**, same) | Built both. Taunt: a run mob (not a boss body) targeting a non-Tank re-targets the nearest living Tank within 16 blocks above 25% HP. Stance: knockback-resistance modifier (`skyclone:dungeons_tank_stance`) on living Tanks in the run, refreshed each second, removed on leave/quit (attribute modifiers are saved with the player). Scripted `Kit.knock` pushes still move a Tank (they are mechanics) |
| 7 | `run/DungeonRun.complete` | "+20 SkyBlock XP (first clear)" printed under the service's own "+20 SkyBlock XP (Completed Floor I)" | Our line removed (the SkyLevel service prints the award) |
| 8 | `run/DungeonRun.tick2` | Sidebar Cleared % lagged the run-info menu (6% vs 12%): the sidebar redraws once a second on its own | A HUD signature (map version, secrets, crypts, keys, cleared %) is checked every 2 ticks; on change every member's sidebar is refreshed at once |
| 9 | `DDials` | (owner note) solo dies in 15-40 s from F3 up; the only lever was `boss-damage-scale`, which also hits parties | New dial **`dungeons.solo-boss-damage-scale` (1.0)**: multiplies every boss-floor attack (scripted via `Kit`, bodies and adds via `ClassSystem.mobScale`) while the run is down to one member. Default 1.0 = unchanged, faithful party content; **owner's call** - at 1.0 solo realistically stops at F2. *(Superseded by the wave4-endgame fixer pass: now a party-size curve, default 0.4.)* |

**For the owner:** F2+ minibosses are still sized for players past the F1 reference (the `BalanceTest.report` duel lines
show the 588/630 reference losing from F2 up, e.g. F2 Fallen Explorer: lasts 19 s, needs 23 s). That is the floor
progression, not asserted. Watch for: Taunt sticks mobs on the Tank only when a target event fires (vanilla re-targets
on acquiring/losing a target), so a mob already chasing someone keeps going until it re-targets.

## Fixer pass (integrate-w2-1) — balance, the boss portal, `/dungeons chests`

Verified: `bash tools/check.sh dungeons` = **CHECK OK (254 files)**; **32 JUnit tests pass** (`bash tools/gen/dungeons/run_tests.sh`;
new `BalanceTest` and `PortalCrossTest`). Nothing was run on the server.

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | `boss/FightBase.spawnBoss`, `run/DefaultBoss`, `boss/LuridFight` clones | **The boss body swung the floor's full headline damage every hit.** F1: 2,664 raw -> 364 through 630 Defense, so Grinko killed the integration player (588 HP) in two swings, or one swing after a 218 skull: the "one-shot" | New dial **`dungeons.boss-melee-share` (0.25)**: a body's contact hit = headline x share x `boss-damage-scale`. Scripted attacks still use the headline (skull 0.6x, barrage 0.96x). The stand-in boss's shockwave no longer passes the boss as source (the run's mob multiplier would have shrunk it a second time) and now follows `boss-damage-scale` |
| 2 | `run/BossCtx.spawnExact` | `boss-damage-scale` said "every boss attack" but adds (Circus Undead, Lecturer guardians, spirit animals, terracottas, giants, Veilwright's students, Wither Lords' guards...) hit at their raw number whatever it was set to | Adds' damage multiplier includes `boss-damage-scale` |
| 3 | `run/DungeonRun.spawnMob`, `BossCtx` | Boss bodies and adds also got `mob-damage-scale` (room dial), so lowering room mobs also weakened every boss | `spawnMob(..., roomDials)`; bosses and adds pass false (HP was already overwritten by the boss path) |
| 4 | `model/Balance` (new, pure), `/sb dungeons balance <floor> [hp def]` (new) | The F1 complaint could only be argued in the client | The core's own arithmetic (`floor(raw x 100/(100+Def))`) as a pure class. The command prints, at the CURRENT dials, what the floor's hardest room mob, miniboss, blood mob, boss body and boss attacks take off you (your live stat sheet), the 588/630 reference and a 500/250 "F1 entry" reference, in hits-to-kill. `BalanceTest` asserts F1 at the default dials vs 588/630: body >= 5 hits (7), every Grinko attack >= 2 (skull 3, barrage 2), Circus Undead >= 3 (4), every room mob >= 15, miniboss >= 6, blood mob >= 8 (Rotter 11) |
| 5 | `run/DungeonRun.forcePortal`, `DungeonAdmin.skip` | **Portal walk-through did nothing (1 in 4).** `skip <p> boss` during the 5 s ready countdown opened the portal and teleported the party to it, but `tick2` only watches the portal once the run is CLEARING. Walking back through worked because GO had happened by then | `DungeonRun.makeLive()`: every admin skip cuts a countdown short (calls `go()`), and refuses while the run is still PREPARING/PLACING (the bars would be placed back) or already in the boss/over. `forcePortal` also calls `go()` from a countdown |
| 6 | `run/DungeonRun.inPortal`, `gen/Geo.crossed` | The portal was tested at ONE point every 2 ticks; a fast dash or a short teleport (AOTE-style) across the 3-deep box could jump it | The path since the last sample (quarter-block steps, moves over 12 blocks test only the end point) |
| 7 | `loot/RewardChests.croesus`, `loot/RngMeterMenu.floor` | **`/dungeons chests` opened nothing.** `Sky.menus().paged(...)` BUILDS a menu and returns it; nothing called `.open(p)`. So the Vault Keeper NPC, `/dungeons chests` and the Catacombs-menu button did nothing whenever there WAS a stored run (only the empty case opened), and an RNG Meter floor page never opened | `.open(p)` on both. In a COMPLETE run's claim window `/dungeons chests` also says the chests stand on pedestals in the boss room (and lists them); a profile still loading now says so |

**Balance at the default dials (TUNE, owner's call; `BalanceTest.report` prints this):**
F1 vs 588 HP / 630 Def: body 91 (7 hits), skull 218 (3), barrage 350 (2), Circus Undead 164 (4), Rotter 57 (11), Zombie Grunt 16 (37).
F1 vs 500 HP / 250 Def: body 190 (3), skull 456 (2), barrage 730 (**1** — the hide-behind-a-pillar attack), Undead 342 (2), Rotter 120 (5), Grunt 34 (15).
Room, blood and miniboss numbers were left alone: by the core's arithmetic a Zombie Grunt takes 16 a hit off the 588/630
player (37 hits to kill), so the first-room death in the integration log was many hits (the driven client stood still
between screenshots), not one. If a live run disagrees, run `/sb dungeons balance F1` INSIDE the run: it reads the stat
sheet the combat code uses, and a mismatch with these numbers would point at a stat that is not reaching Defense.
Lower `boss-damage-scale` (all scripted boss attacks on every floor) only if the 500/250 player is meant to survive the
F1 barrage in the open.

## Review pass (after part B) — what was fixed

A reviewer read part B's code, data and this hand-off against the brief and research 04 §3.13-3.16, then fixed what
follows. Verified: `bash tools/check.sh dungeons` = **CHECK OK (252 files)**; **24 JUnit tests pass**
(`bash tools/gen/dungeons/run_tests.sh`; new `ReviewFixTest`: a reroll keeps the RNG-meter item x200 seeds on F7/M7/F5,
a forced item never lands below its chest tier, no chest repeats an entry); new live self-test `dungeons.b-no-coin-loops`.
Still nothing has run on a server or in a client.

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | `shop/GatekeeperShop` | **Coin printer.** The Gatekeeper sold Fate Feathers at 1M while the bazaar's market makers bid ~1.31M for them (reference price): buy, insta-sell, repeat, ~300k a feather. | An `NPC_SHOP` recipe (`COINS:<price>`) for both the feather and the Chest Key, so the bazaar's own rule caps its bots' bid at 97% of the shop price; and the shop price is `max(dial, bazaar instant-sell x 1.05)` at click time, so lowering the dial later cannot reopen it. A price that rose since the menu opened re-shows it instead of charging more. Self-test `dungeons.b-no-coin-loops` checks both items plus every Provisioner item vs salvage-to-essence |
| 2 | `shop/GatekeeperShop` salvage | Salvaging accepted **bazaar goods** (the table holds 150 ids from every module), an arbitrage valve against the bots (HELIX -> 25 Fossil essence sat within 7% of HELIX's ask). Without a uuid, `removeOne` could also destroy a **starred** copy after the player confirmed an unstarred one | Bazaar products (`bazaar` tag or `Features.Bazaar.isBazaarItem`) are refused ("sell it there"); the non-uuid path removes one plain copy and skips starred/recombobulated stacks |
| 3 | `boss/BramblemawFight` | The Spirit Bow was the weapons module's `BOSS_SPIRIT_BOW` **sky item**: a real weapon that could be dropped, handed over, or kept by anyone who quit/warped out mid-fight. A holder who died or quit took the only bow with them (another 25 animals and a bear). A full inventory silently ate it | A plain vanilla bow + arrow tagged run-only (`Hooks.CARRY`); passes to the nearest living player if the holder dies/quits/leaves before shooting (`FIRED` stops a second bow after the shot); a full inventory frees a hotbar slot and drops what was there for the player |
| 4 | `boss/WitherLordsFight` | Energy Crystals and relics were **sky items given through `Sky.loot()`** - a sack could swallow them (podium/cauldron then never find them = soft-lock), and a carrier who **quit or left** took the crystal/relic out of the fight for good (P1/P5 soft-lock until the 45-min timeout) | Tagged vanilla stacks given straight to the inventory; the fight tracks carriers (`crystalBy`, `carrying`) and returns a crystal/relic to its ledge/pedestal whenever its carrier is no longer alive in the run (checked every step and on death). Crystals are stripped when P1 ends. The Hollow King is frozen on his throne (his pull reads the fixed `p5.king` point) |
| 5 | `boss/Hooks`, `run/RunManager.cleanupPlayer` | Nothing guaranteed boss-fight items stayed in the run | `Hooks.CARRY` items cannot be dropped or moved into another inventory, and `cleanupPlayer` (leave, close, rejoin after the run is gone) strips them |
| 6 | `run/DungeonRun.complete/fail` | **Leech exploit:** a member who left the run (`/dungeon leave`, a warp) still got the full Catacombs/class XP, the completion, boss collection AND a stored set of reward chests when the party finished | Members with `left` are skipped for XP and excluded from `RunResult.members` (no chests) |
| 7 | `run/DungeonRun.complete` | Adds, giants, guards and room stragglers kept fighting for the whole 120 s chest-claim window | `clearHostiles()` removes every remaining run mob the moment the run completes (unindexed first, so no kill events) |
| 8 | `run/RunManager.prepare/startBoss` | The arena was chosen at prepare time but the fight was looked up again at boss start: flipping `dungeons.real-bosses` mid-run put a real fight in the default arena, where every `spawnBoss` at a missing point returns null and the fight calls `complete()` = **free chests** | The fight chosen with the arena is pinned on the run (`run.boss`) |
| 9 | `loot/RewardChests` | A Fate Feather reroll dropped the **RNG-meter item** even though the meter had already been spent on it. Keys and feathers in the dungeon **sack** (storage accepts both) were invisible to the chest menu. A chest-block entry of a closed run could answer clicks in a reused slot | Reroll passes the chest's `rngItem` back in as forced; key/feather count and take fall back to `Features.Sacks`; stale entries are dropped (closed runs too) and the sweep checks `CLOSED`; reroll saves the profile |
| 10 | `loot/RngMeterMenu` | Clear-then-select skipped the "switching resets the meter" rule, so XP banked on one item could be moved onto a cheaper one and claim it at once | Clearing empties the meter as well; any switch with XP in it resets; the lore says so |
| 11 | `boss/LecturerFight` | Guardian HP applied the `boss-hp-scale` dial twice (bossHp() already scaled, then `spawnExact` scaled again) | Guardians start from the floor's research HP |
| 12 | `run/RunManager.recover` | Two `Sky.db().query(...).join()` on the main thread at boot | Both queries run on the DB thread and hop back with `runTask`; map ids already in use are not pooled twice |
| 13 | `gear/WitherBlades.bind` | The bind wrote to the stack returned by `getItemInMainHand()` and trusted it to be a mirror | Removes the scroll first, re-reads the held slot, writes the updated blade back explicitly (refunds the scroll if the blade moved) |
| 14 | `gear/GearEffects` | Eight per-player timer maps were never cleaned | Dropped on quit (the cheat-death cooldowns are kept so relogging cannot reset them) |
| 15 | `boss/WitherLordsFight` terminals | A terminal solved after the fight moved on still counted | Ignored unless the fight is live and in P3 |

**Reviewer's open notes (not changed):**
- A boss entity that *vanishes* (removed by something else, not killed) is reported by the framework's `checkMobs` as a
  kill with no killer, which wins that fight. Arenas are closed and chunks are ticketed, so this needs an outside cause;
  watch for it in the first live run.
- Every fight answers "the boss mob failed to spawn" with `c.complete()` (a free clear). `dungeons.b-boss-mobs` guards
  the data; if it ever fails live, fix the data before players run.
- The Hollow King's pull (instant death within 6 blocks of his throne) reaches within ~6 blocks of the Power statue,
  where players kill that dragon. By design, but expect it to be the first M7 complaint.
- The Gatekeeper's `NPC_SHOP` recipes are registered at boot with the dial values then; the click-time guard covers a
  dial lowered later, but the bazaar bots' cap only refreshes on the next boot.

## Part B — what works (verified how)

Verified: `bash tools/check.sh dungeons` = **CHECK OK (252 files)**; **21 JUnit tests pass** (`bash tools/gen/dungeons/run_tests.sh`:
A's 15 + `BossLootTest` 6: chest unlock rules and research costs, deterministic tier-respecting rolls, the RNG meter,
every arena's points for F and M floors, 1,000 Lights / Arrow Align boards solvable). All seven arenas **previewed and
read** (`bash tools/preview.sh com.skyclone.mod.dungeons.gen.arena.ArenaPreviewGenerator <1..8>`, 8 = M7): walkability
OK, 0 floating components, all block names valid. Data comes from `tools/gen/dungeons/gen_content.py` (re-run after edits).

- **Seven scripted boss floors** (`boss/`, research 04 sec 3.13; Master Mode = the same fight with master HP/damage):
  - **F1 Grinko** (circus tent, 7 pillars): dark-orb skulls (0.6 x boss damage = 1,598 on F1), Circus Undead (45k / 15M),
    a refused death (3 s immune, full HP, faster, bigger), then balloons and the **Balloon Barrage** from the ring
    centre that the pillars block.
  - **F2 Veilwright** (court + barred balcony): Warrior (charge), Archer, Mage (fire circles), Priest (heals, raises a
    fallen student once) at research HP; M2 pairs and "immune until 4 killed"; bars drop, Skull Barrage, Soul Sand
    Blizzard (10% max-HP true + Slowness), raises two students at 50%.
  - **F3 The Lecturer**: Healthy (Giga Heal unless hit 10x), Reinforced (TNT circles, Rogue/Elder Guardians), Chaos
    (anger from nearby players, then a discharge), Laser (10 s focus beam, 175 true/s, then a cooldown), pedestal
    body-slam 1,500, 10 s respawn at 80/90% unless all four die in the window; then the Lecturer; Guardian form at 25%.
  - **F4 Bramblemaw**: immune except to Spirit-Bow arrows (4 / 6 hits, dials); six spirit animals with tricks (bull
    charge, rabbit launch, sheep explode, bat, chicken mine); 25 kills (30 on M4) then the Spirit Bear; its bow appears
    in the killer's hands (one arrow, 20 s); a hit stuns it 5 s and speeds it up; green circles heal players, orange
    circles burn animals.
  - **F5 Lurid**: 16 s alone, then blindness and eight coloured copies with their kits (Vendetta, Crossed, Hockey,
    Doctor, Frog, Smile, Scream, Arcade); the **ceiling wool block** shows the real one's colour; a "killed" copy springs
    back and heals every Lurid 25% (M5: they also hit harder).
  - **F6 Gorrak**: 2:00 of terracottas from 8 (12) planters, -1 s per kill, 10 s regrowth; four giants in alcoves
    (sword line, ramping laser, stomp ring, boulders); then the Giant One with all four kits.
  - **F7 Wither Lords** (one 160x200 arena, five sealed chambers; the party is moved between them): **Gravax** (carry
    two Energy Crystals to the podiums; the laser pins him near the centre; the first laser caps him at 25%;
    guards/miners), **Tempestra** (corner pads drop crushers 3 s later; crushed = stunned 10 s at full damage,
    otherwise she takes 25%; she cannot die before 2 crushes; **Giga Lightning** 2 x 75% max-HP true unless sheltering
    under an unused crusher, or holding a Wither Cloak on normal mode; a Shade Stalker per pad; pads reset when all are
    used, so no soft-lock), **Aurik** (immune, follows the party; four corridors, each needing its **terminals
    (4/5/4/4), 2 levers and a device** before its gate opens 5 s later; six terminal mini-games: panes, colours, order,
    starts-with, colour-select, melody; four devices: Simon Says, Lights, Arrow Align, Shoot the Target - punching the
    lit target from range also counts; the core behind the last gate makes him vulnerable), **Morvane** (skull
    barrages, fire circles, lightning, flying swords, 15 s **rotating beams** at shin height = 25% max HP unless you
    jump, frenzies at 80/50% whose blast radius grows unless he is being hit, each breaking a stretch of platform into
    lava) and on **M7 the Hollow King** (five relics to five cauldrons - a wrong one fails the run; a dragon every 30 s
    at its statue; killed within 18 blocks of its statue it shatters the statue, elsewhere it comes back; five alive at
    once fails the run; five statues down = win).
  - Every fight: titles on phase changes, a boss bar with HP and the current phase or timer, telegraphed rings before
    every area hit, `[BOSS]` dialogue. The framework's boss-damage dispatch moved from MONITOR to **HIGH** so fights can
    cancel or clamp a hit before it lands.
- **Reward chests** (`loot/`, research 04 sec 3.14): Wood (boss + class milestone 2), Gold, Diamond (F2+), Emerald
  (score 230), Obsidian (270), Bedrock (300, F5+); Master Mode grants all. Base costs Gold 25k->100k, Diamond 50k->250k,
  Emerald 100k->500k, Obsidian 250k->1M, Bedrock 2M (linear between F1 and F7, x2 on Master - TUNE). F7 contents and coin
  costs exactly as research (Warden's Handle 98M + 2M = 100M); other floors' pools are NEU's per-floor RNG lists, with
  chest placement and coin cost derived from the meter value (TUNE, `gen_content.py`). Essence per chest = research's
  F7 amounts scaled by floor. Rolled **per player**; one chest per run; a **Dungeon Chest Key** opens another; a **Fate
  Feather** (Kismet) rerolls one chest per run. Real chests on coloured pedestals at the arena's `chests` point (the
  blood room for the Entrance). Everything a player was offered is **stored in their profile at once**, so it survives
  a crash, and unopened chests wait at the **Vault Keeper** (Croesus, `npc.croesus`, `/dungeons chests`) for 3 days
  (dials). Mayor `paul.marauder` discounts chests. Golden/Diamond boss heads appear in chests only once the player's
  boss collection reaches tier III / VI (the collections module's reward table). S runs add 1 to the helmet's
  Hecatomb progress (enchanting's request).
- **RNG meter** (`/dungeons rng`): one per floor; pick an item from that floor's table; runs scoring 270+ add their
  score; full = guaranteed in the best chest that can hold it; a natural drop also resets it.
- **Dungeon gear** (123 items, `data/dungeons/gear.json`): Zombie Soldier/Knight/Commander/Lord, Skeleton
  Soldier/Master/Lord, Bonelord (Skeletor), Crypt Witherlord, Bouncy, Adaptive (+starred), Shade Stalker (+starred),
  Necromancer Lord, Wither and the four lord sets, Grinko's Mask (+starred), Spirit Mask/Boots (+starred), Mender
  Helmet/Fedora/Crown, Dark/Shadow/Wither Goggles, Stone/Metal/Steel Chestplate, Sniper Helmet, Forerunner Eye, 14 boss
  heads, the three wither scrolls, Dungeon Chest Key, Fate Feather, Endless Soul Leap, Energy Crystal, five relics.
  Research stats (lowest quality roll), requirements mapped (`catacombs:N`, `dungeon_completion:F<n>:1`,
  `collection:BOSS_*:<tier>`), `dungeon: true` (in-run stat boost and stars), NEU colours/skins/gem slots,
  `extra.upgrade_costs` (reforges' star ladders key off the same ids), 27 recipes (fragment upgrades to the starred
  pieces, the four lord sets from giant fragments).
  **What it does** (`gear/GearEffects`): Phalanx (+30 Defense per nearby full-set ally), Oathbound (+50 Defense with the
  knight sword), Drill Sergeant (+5 HP per 50 zombies, max 500), Lord of the Halls (per minute in a run, 10 min cap
  TUNE), Volley/Marksman (+5% arrows per piece, +25% full), Bone Collector (+1 Str/CD per 10 Bonelords), Ricochet (25%
  bounce), Ever Learning (+2% per 5 Catacombs levels per piece + class bonus), Silent Hunt (+1 Str per kill in the run)
  and Sinew (+10 Str for 10 s after a teleport), Soul Whisper (+20% vs undead in runs - see partial), wither damage
  reduction (5% / 10% per piece), Witherborn imps every 30 s, Mender stats in dungeons, boss heads x2 on their floor,
  Stone/Metal/Steel aura (5/8/10%, 30 blocks for Tanks), Sniper Helmet range bonus, **Last Laugh** (Grinko's Mask cheats
  death in runs), **Second Wind** (Spirit Mask), **Spirit Glide** (sneak, 250 mana, 60 s), **Eye Beam** (sneak, ramping
  beam, mana spent on hit). Room mobs now **drop their gear** (Zombie Soldier -> Zombie Soldier armour/cutlass,
  Bonelord -> Bonelord armour, Sniper -> Sniper Helmet/bow ... ~1% a piece, TUNE; `DROPS` in `gen_dungeons.py`).
- **Wither scrolls** (`gear/WitherBlades`, `/dungeons scrolls`): bind Implosion / Shadow Warp / Wither Shield to the
  weapons module's Warden's Blade, Highlord, Shieldmaiden, Starward or Riptide; right-click casts them; all three =
  Wither Impact (300 mana, 0.15 s). The lore shows what is bound.
- **Essence shop** (`npc.essence_shop`, `/dungeons essence`): every Undead and Wither perk with NEU's level costs
  (several effect sizes TUNE, written in `shop/EssenceShop`). **Gatekeeper's Stock** (the "Mort shop",
  `/dungeons stock`): Fate Feather and Chest Key for coins (dials), and **salvaging** dungeon gear into essence
  (`data/dungeons/salvage.json`).
- **Boss Guide** (`/dungeons bosses`): what each floor's boss does and how to beat it.
- The Catacombs menu gained RNG Meter, Unclaimed Chests, Essence Shop, Gatekeeper's Stock, Scroll Altar, Boss Guide and
  Boss Collections buttons.

## Part B — partial / not built
- **Never run live.** Every fight is code-complete and data-checked, but none has been played. Expect tuning.
- **Balance**: boss HP is research's (dial `dungeons.boss-hp-scale`); scripted attack sizes are fractions of the
  floor's boss damage (dial `dungeons.boss-damage-scale`); add HP where research has none (the Lecturer's guardians at
  30/50% of boss HP, giants 25M, spirit animals, terracottas) is TUNE.
- Simplified mechanics: Gravax is pulled into the laser when he comes within 7 blocks of the centre (no laser-line
  geometry); crushers are a 20-tick block drop, not an animation; Aurik steps to the current section when he falls 35+
  blocks behind; the Hollow King's dragons are scaled Phantoms; Lurid's copies share one HP value with the real one;
  the Spirit Bow appears in the bear-killer's inventory (no dropped bow to race for).
- Not built: Livid/Necron dyes, the cosmetic "X the Fish" items, M6's mutant giants respawning beside Gorrak, Necron's
  platform-lifting cutscene, Goldor's lava "super bounce", Summoning Ring / necromancer souls (so Soul Whisper is "+20%
  vs undead in runs" instead of boosting summons), Echoes of the Lost (given our own effect: Magic Find in runs), the
  starred dungeon weapons (`STARRED_SHADOW_FURY`...; weapons owns the base ones), Catacombs passes.
- **The essence shop pays with essence ITEMS** from the inventory, because the essence pouch belongs to the reforges
  module and has no API (Request 1). The shop's **"Essence as items"** toggle makes chest and salvage essence arrive as
  items (right-click one to put it into the star pouch); default off (dial `dungeons.essence-as-items-default`).
- The armour module still registers Rotten / Skeleton Grunt / Heavy / Super Heavy (its wave-1 copies); part B did not
  re-register them (Request 2).

## Part B — commands
| Command | What |
|---|---|
| `/dungeons chests` (`croesus`, `vault`, `unclaimed`) | Unclaimed reward chests (also the Vault Keeper NPC) |
| `/dungeons rng` | RNG meter per floor |
| `/dungeons essence` (`perks`) | Essence shop (also the Essence Keeper NPC) |
| `/dungeons stock` / `/dungeons salvage` | Gatekeeper's Stock / salvage dungeon gear |
| `/dungeons scrolls` (`altar`) | Bind wither scrolls to the held blade |
| `/dungeons bosses` (`guide`) | Boss Guide |
| `/sb dungeons chests <p> <floor> [score]` | Give a stored run's chests as if just finished (tests chests, keys, rerolls, the Vault Keeper) |
| `/sb dungeons loot <floor> <chest> [n]` | Print n rolls of one chest |
| `/sb dungeons rng <p> <floor> <xp>` / `perk <p> <perk> <lvl>` | Set RNG meter XP / an essence perk |
| `/sb dungeons gear <p> <set id or scrolls or keys>` | Give a whole set, the scrolls + a blade, or feathers + keys |
| `/sb dungeons phase <p>` | Print the boss fight's state |
| `/sb dungeons balance <floor> [hp def]` | (fixer) Hits-to-kill of the floor's room/blood/miniboss mobs, boss body and boss attacks at the current dials, vs your live stats and two reference players |
| (part A) `/sb dungeons start <p> F7 force`, then `/sb dungeons skip <p> boss` | Jump straight to a boss floor |

## Part B — dials
`dungeons.boss-hp-scale` 1 · `boss-damage-scale` 1 · `boss-melee-share` 0.25 (fixer) · `boss-add-melee-share` 0.25 · `solo-boss-hp-scale` 0.3 · `solo-boss-damage-scale` 0.4 · `boss-arrival-grace-seconds` 3 (wave4-endgame) · `chest-cost-scale` 1 · `rng-meter-scale` 1 · `chest-essence-scale` 1 ·
`boss-luck-weight` 0.02 · `salvage-scale` 1 · `witherborn-damage` 20000 · `scroll-damage` 10000 · `kismet-price` 1M ·
`chest-key-price` 600k · `rng-min-score` 270 · `croesus-days` 3 · `croesus-max` 60 · `chest-claim-seconds` 120 ·
`f4-spirit-hits` 4 · `m4-spirit-hits` 6 · `f4-spirit-kills` 25 · `f6-terracotta-seconds` 120 · `f7-storm-crushes` 2 ·
`f5-clone-seconds` 16 · `real-bosses` true (off = the stand-in boss) · `real-chests` true (off = free essence) ·
`essence-as-items-default` false · `gatekeeper-sells-keys` true.

## Part B — self-tests
`dungeons.b-gear-loaded` (every gear def registered, armour flagged dungeon) · `dungeons.b-boss-mobs` ·
`dungeons.b-arenas` (all 7 arenas, F and M: size cap and every point the fight reads) · `dungeons.b-loot` (every floor
has Wood/Gold and existing items; research numbers: handle 98M, F7 Bedrock 2M, Obsidian needs S, Master grants all) ·
`dungeons.b-recipes` (ingredients exist) · `dungeons.b-devices-solvable` · `dungeons.b-shops` ·
`dungeons.b-bosses-registered` (F1-F7 and the reward hook) · `dungeons.b-no-coin-loops` (review: Gatekeeper items vs the
bazaar's instant sell and NPC sell; Provisioner items vs salvaging them into essence at the bazaar's price).

## Part B — requests
1. **reforges / api**: a `Features.Reforges` (or `Features.Essence`) with `long essence(Player, String type)` and
   `boolean takeEssence(Player, String type, long n)`, so the essence shop can spend the star pouch directly (reforges
   offered exactly this in its Request 3). Until then the shop takes essence items.
2. **armor**: keep Rotten / Skeleton Grunt / Heavy / Super Heavy in `KEEP_DUNGEON_SETS` - part B did NOT register them.
   Nothing to do unless you want them dungeon-owned (then remove them and tell dungeons to add them to gear.json).
3. **collections**: dungeons credits `BOSS_*` on completion (part A). Boss MobDef ids are `dboss_*` with our names (no
   `bonzo`/`f1` tokens), so `collections.boss-from-kills` cannot double count; turning it off is still cleaner.
4. **core (optional)**: never auto-remove untagged entities in `sb_catacombs`. Part B adds ItemDisplays, TextDisplays,
   Interaction boxes and ItemFrames (all registered as run props and removed when the run closes).
5. **weapons**: part B binds scrolls to your `NECRON_BLADE` / `HYPERION` / `VALKYRIE` / `ASTRAEA` / `SCYLLA`
   (`ItemData.extra.dungeon_scrolls`) and casts on right-click. If those blades ever get their own right-click ability,
   tell dungeons so the two do not both fire.
6. **owner**: the Necron naming split ("Warden's" in weapons/materials/reforges vs "Morvane" in collections); see
   `docs/names/dungeons.md`.

## Part B — known risks
- **Unproven live.** Likeliest first-boot issues: Interaction-entity clicks (crystals, terminals, relics), the item-frame
  rotation in Arrow Align (the arrow sprite's mapping is assumed: `NONE` = up-right), lever/button placement by
  block-state string, Phantom dragons and no-AI Giants/Guardians under core's mob handling, the Spirit-Bow arrow reaching
  `ProjectileHitEvent` with its tag, and F7 placement time (the arena is ~180k blocks on top of a 6x6 run; at 8 ms/tick
  expect ~10 s of "Preparing the Catacombs").
- **Difficulty**: research boss HP (F1 250k ... M7 dragons 1B) with scripted hits sized off the floor's boss damage can
  be brutal for a fresh player; both scale dials exist. The Hollow King's pull is an instant death within 6 blocks by design.
- **Essence UX**: until Request 1, players who keep essence in the pouch cannot buy perks; the shop says so and offers
  the toggle.

---

# Part A (the framework) — original hand-off

### What works (verified how)

Verified: `bash tools/check.sh dungeons` = CHECK OK (221 files); **15 JUnit tests pass** (`bash tools/gen/dungeons/run_tests.sh`,
javac + the JUnit launcher, no gradle); both generators previewed and READ (`previews/dungeon_hub/`,
`previews/catacombs_sample/`), walkability OK. **Never run on the live server** (rule: no dev server) - the integrator
runs `/sb selftest dungeons`.

- **Dungeon Hub island** (`dungeon_hub`, `DungeonHubGenerator`, 220x220): a dark floating island with a paved square
  and fountain, the **Catacombs Mausoleum** to the north whose great arch is the entrance (zone/area `catacombs_gate`:
  walking in opens the floor selector, gated by `dungeons.entrance-requirement` = Combat 15), the **Gatekeeper**
  (Mort-equivalent) at the forecourt, the **Party Board** (west), the **Class Altar** with five class pedestals (east),
  the **Provisioner**'s house + stall (south-east), a vault reserved for unclaimed chests (south-west, `npc.croesus`),
  `npc.essence_shop` reserved by the altar, graves, dead trees, soul lanterns, `portal.hub` by the spawn. Warp
  `dungeon_hub` registered (the hub's `portal.dungeon_hub` now works). Preview: all 7 anchors + portal reachable.
- **Parties** (`/party invite|accept|deny|leave|kick|transfer|disband|list|warp|chat|finder`, alias `/p`, `/pc`): 1-5
  (dial), 60 s invites with a clickable accept, leader transfer on leave, auto-disband, rate-limited invites, sanitised
  chat. In memory (like the genre). Solo works everywhere (a party of one).
- **Party Finder** (`/pf`, Party Board NPC, Catacombs menu): listings with floor, min Catacombs, min class level, wanted
  classes, note (sign/chat input, sanitised, 40 chars); join adds you to the party; delisted when full, when the
  leader leaves/changes, or when the run starts.
- **Classes** (Healer, Mage, Berserk, Archer, Tank; `/class`, altar, menu): every passive in research 3.4 that can be
  expressed (Healer aura/Renew/Revive/Orbies, Mage beam + Efficient Spells + Int per level, Berserk Bloodlust/Lust for
  Blood/Weapon Master cleave, Archer Doubleshot/Bone Plating/Bouncy Arrows, Tank base stats/Protective Barrier/
  Diversion), all five abilities (F or right-click the map) and Ultimates (Sneak+F or drop the map), ghost abilities,
  class XP (selected 100%, others 25%), levels 1-50 on the Catacombs table, class milestones 0-9.
- **Catacombs levels** (`CataMath`): the exact reference table (level 50 = 569,809,640, unit-tested), stat boost 15%..485%,
  level-up boxes, SkyBlock XP per level (`catacombs:<lvl>` keys) and per class level, first-clear SkyBlock XP per floor.
- **Dungeon item scaling**: inside a run, `ItemDef.dungeon` armour and held weapons get `base x (Catacombs boost +
  8%/essence star + 5%/master star)` (reforges already applies the 2%/star that works everywhere); weapon damage too.
  Also research 3.5: mana on every melee hit (5 + 1%), speed above 100 x0.67, all dungeon mob damage x floor scale.
- **The run** (`RunManager`, `DungeonRun`): a **fresh map every run**. `PlanGenerator` implements research 3.6 step by
  step (start on an edge, blood furthest, fairy, puzzles/trap/minibosses, normal rooms 1x1/1x2/1x3/1x4/2x2/L by weight,
  randomised spanning tree with special rooms as leaves, 15-25% loop doors only inside a lock region, wither doors on
  the start->blood path, keys placed before their door, key-simulating validation; unit-tested on 25 seeds x 15 floors).
  `RunBuilder` turns it into blocks (32-block cells, 2-thick walls, 3x4 doors, coal-block wither doors, red blood door,
  9 normal room templates that adapt to any shape, 5 special rooms, 10 puzzle rooms, the boss arena east of the grid).
  Generation runs off-thread; core's instance slot (`catacombs`, key = run id) is prepared in parallel; `Placer`
  writes the blocks at 8 ms/tick (dial) with async chunk loads and plugin chunk tickets for the whole run, so dungeon
  mobs never unload. A 6x6 run is ~180k blocks.
- **In the run**: 5 s countdown behind barriers in the start room (with Sexton Abel), the **Dungeon Map** in slot 9
  (core's star is suppressed via `skyclone:no_sbmenu`; a real filled map with a custom renderer: rooms reveal as entered,
  `?` neighbours, white/green ticks, red crosses, coloured doors, teammate arrows), room entry spawns that room's star
  mobs (scaled by floor), one of them glowing = the key holder; keys drop as floating blocks and are picked up for the
  whole party; wither/blood doors open by click or by walking up with the key; **secrets** (chests, item pedestals,
  bats, wither-skull essence, levers, cracked walls hiding chests) with a reward table; **crypts** opened by
  explosives (+1 bonus each, spawns Crypt Undead); a **Mimic** chest on floors 6-7; **trap** room (lava crossing,
  darts); **minibosses** (Fallen Explorer, Gilded Monarch, Furious Excavator, Shade Stalker, Frostbound Explorer, each
  with its gimmick); **Fairy** room heals and revives ghosts; the **Blood Chamber**: the blood door wakes The Onlooker,
  who spawns the floor's blood mobs every 3 s; when they are all dead the boss portal opens (the Entrance ends there).
- **Death**: a ghost (invisible, flying, untargetable, cannot hit/open/loot), no coins lost, -2 Skill; revived by a
  Healer's passive (100 s - level), a Healer ghost's self-revive (50 s), a Revive Stone, the Fairy room, or stepping
  through the boss portal. Whole party dead = fail. Falling out of the map = rescue.
- **Score** (research 3.12): Skill/Exploration/Speed/Bonus, grades D..S+, live in the sidebar; mayor perk
  `paul.ezpz` adds its value. **Completion**: score box, Catacombs + class XP (`floor.baseXp` x score factor, +40% for
  the first 5 completions a day), boss collection (`Features.Collections.add(p, "BOSS_*", 1 or 2)`), records, then the
  reward hook, then everyone goes back after `claimSeconds`. Failed runs pay 10% XP.
- **Puzzles** (all ten listed in research; Bomb Defuse was removed from the reference): Tic Tac Toe (minimax, proven
  unbeatable in a unit test), Three Strangers (generated statements, exactly one consistent chest), Creeper Beams
  (mirrored lantern pairs, segment-through-creeper test), Water Board (6 levers XOR 5 door outlets, always solvable),
  Teleport Maze (3x3 sealed chambers, 2-5 hop route), Higher or Lower (10 blazes, kill order by HP), Boulder (7x6
  grid, push-away physics, BFS-proven solvable, reset button), Ice Path (13x13 slide puzzle, BFS 4-14 moves), Ice Fill
  (3/5/7 boards built from a guaranteed walk, reset on a repeated tile, gates), Quiz (questions generated from THIS
  game's registries: rarities, sell prices, skill caps, stat symbols, ultimates, bosses). Solve = Tier V blessing
  (Quiz = Time) + room cleared; failable ones cost 14 Skill.
- **Consumables & shop**: Soul Leap (teleport menu), Megablast TNT (explosion: weak walls/crypts), Decoy and Inflatable
  Dummy (lure mobs), Revive Stone, Dungeon Potions I-VII (run-long buff). The Provisioner sells them at the NEU
  Ophelia prices and lists the dungeon gear upgrade lines (Cleaver, Undead Bow, Goggles, Mender, Stone Chestplate...)
  automatically once part B registers those items.
- **Fallback boss** (`DefaultBoss` + `DefaultArena`): until part B registers a floor, its boss is the floor's boss mob
  with the research HP/damage, a boss bar, a telegraphed shockwave at 66%/33% and minion waves, so F1-F7 and M1-M7 are
  all completable today.
- **Persistence/safety**: per-player `DData` (profile key `dungeons`); active run ids in `dungeons_active` and released
  on the next boot (no leaked slots after a crash); map ids pooled in `dungeons_maps`; quitting keeps your place (rejoin
  puts you back); leaving the run world (any warp) = leaving the run; map item cannot be moved/dropped/stored; Revive
  Stones vanish at run end (dial); every coin move has a ledger reason.

### What is partial / not built
- Boss floors F1-F7 / M1-M7 and the reward chests (Wood..Bedrock, Kismet, chest keys, RNG meter, Croesus, Mort shop,
  essence shop): **part B**. Until then: the fallback boss and a free chest of research-scaled Undead/Wither essence.
- Dungeon weapons/armour/accessories (and the armour module's four starter-set copies): **part B**.
- Room templates are procedural (9 normal templates x any shape, decorated per cell), not ~100 hand-built schematics;
  no "rare" rooms. More can be added with `Tiles.register`.
- Not built: Berserk Indomitable, Archer Healing Bow ghost ability (replaced by a stun), Saul's solo item (solo is
  simply free), dungeon potion effects beyond stats, the "potions disabled in dungeons" rule, General's Medallion.
  Blessings come from puzzles and (Tier I) from secrets only. (Built since: Tank Taunt + Defensive Stance in the
  playtest-endgame pass; Ragnarok zombies, Prince, duplicate-class penalty, Spirit-pet first death and Healer Soul
  Tether in the finish wave 2026-10-03.)
- Fall damage is off inside runs (`NO_FALL` zone) - a deliberate kindness, not reference behaviour.
- Never driven by a real client: the map renderer, item-display pickups, puzzle entity clicks and ghost flight are
  unproven live.

### Commands added (player + admin)
| Command | What |
|---|---|
| `/dungeons` (`/catacombs`, `/cata`, `/dungeon`) `[gate\|start <floor>\|classes\|stats\|shop\|leave\|info\|hub]` | Catacombs menu and its pages; `leave` exits a run |
| `/dungeonhub` (`/dh`) | Warp to the Dungeon Hub |
| `/class` | Class selection |
| `/party ...` (`/p`), `/pc <msg>` | Party system |
| `/pf` (`/partyfinder`) | Party Finder |
| `/sb dungeons start <p> <floor> [force]` | Start a run for p's party (force skips requirements and the hub check) |
| `/sb dungeons end <p> [complete\|fail\|close]` | End p's run |
| `/sb dungeons skip <p> keys\|clear\|blood\|boss` (also `/sb dungeons keys <p>`, `boss <p>`) | Testing shortcuts |
| `/sb dungeons xp <p> <n>` / `classxp <p> <class> <n>` / `level <p> <lvl>` / `class <p> <class>` / `reset <p>` | Progress |
| `/sb dungeons runs` / `info <p>` / `plan <floor> [seed]` / `kit <p>` | Inspect runs, print a generated map, give test consumables |

Menu paths: SkyBlock Menu -> Dungeons (the menus module runs `/dungeons`); Dungeon Hub NPCs (Gatekeeper, Party Board,
Class Altar, Provisioner); the hub's Catacombs Guide (`npc.dungeon_guide`) warps you to the Dungeon Hub; the arch.

### Dials added
`dungeons.max-runs` 8 · `party-max` 5 · `countdown-seconds` 5 · `claim-seconds` 90 · `fail-close-seconds` 10 ·
`timeout-minutes` 45 · `invite-seconds` 60 · `place-budget-ms` 8 · `skill-score-floor` 20 · `daily-bonus-runs` 5 ·
`blood-delay-seconds` 4 · `healer-revive-seconds` 100 · `ghost-self-revive-seconds` 50 · `catacombs-cap` 50 ·
`puzzle-fail-penalty` 14 · `xp-scale` 1 · `class-other-share` 0.25 · `mob-hp-scale` 1 · `mob-damage-scale` 1 ·
`fallback-boss-hp-scale` 1 · `stat-boost-scale` 1 · `star-boost` 0.10 · `master-star-boost` 0.05 ·
`speed-above-100` 0.67 · `fail-xp-share` 0.1 · `min-score-xp-factor` 0.1 · `mana-on-hit` 5 · `secret-reward-scale` 1 ·
`blessing-scale` 1 · `class-ability-scale` 1 · `require-hub` true · `solo` true · `ignore-requirements` false (TESTING) ·
`remove-run-items` true · `announce-secrets` true · `mimic` true · `entrance-requirement` "skill:combat:15" ·
`watcher-name` "The Onlooker".

### Self-tests added
`dungeons.floors-loaded` · `dungeons.xp-table` (research numbers) · `dungeons.mobs-exist` (every pool, miniboss, blood
and boss mob) · `dungeons.items-exist` (+ no free shop items) · `dungeons.plans-valid` (3 seeds x 15 floors: solvable,
blood is a leaf, puzzles, no empty cell) · `dungeons.blueprints-build` (E/F1/F5/F7: secrets, Mort spot, arena, portal,
puzzle chests, no tile crashed, mob spots) · `dungeons.puzzles-solvable` · `dungeons.hub-layout` (anchors, areas,
`npc.dungeon_guide` in the hub, warp) · `dungeons.requirements` (resolvers + service).
Unit tests (`plugin/src/test/java/com/skyclone/mod/dungeons/`, 15): `CataMathTest`, `PlanAndBlueprintTest`,
`PuzzleLogicTest` (Tic Tac Toe exhaustively unbeatable, Strangers unique x300, Water x200, Teleport x200, Boulder,
Ice Path x100, Ice Fill with an independent solver).

### Requests (things you need from core or another module — the integrator reads this)
1. **armor**: delete the four dungeon starter sets from `KEEP_DUNGEON_SETS` once part B registers them (your Request 6).
2. **weapons**: your `weapons.catacombs-fallback` should now see `Features.Dungeons` and keep `catacombs:N` as is - nothing
   to do unless a requirement prefix differs.
3. **menus**: the SkyBlock Menu "Dungeons" link already resolves to `/dungeons` (and `/catacombs`). Fast Travel lists
   `dungeon_hub` (registered). Nothing else needed.
4. **collections**: boss completions now call `Features.Collections.add(p, "BOSS_BONZO".."BOSS_NECRON", 1)` (2 on Master
   Mode) for EVERY party member. The fallback boss mobs are named `dungeon_guardian_1..7` on purpose (no `_f1_`/`_bonzo_`
   token), so your kill heuristic does not double-count them. Once dungeons is live, consider turning
   `collections.boss-from-kills` off (part B's real boss ids may carry tokens).
5. **core (optional)**: an API to list instance slots by island would let a module verify its slots; today dungeons
   keeps its own `dungeons_active` table. Also: puzzle props are vanilla entities (Villager, Creeper, Silverfish, Bat,
   ArmorStand, Sheep) spawned in `sb_catacombs` - please never auto-remove untagged entities in game worlds.
6. **mayors**: `paul.ezpz` (+score) and `paul.benediction` (+% blessings) are read; `paul.marauder` is for part B's chests.

### Known risks
- **Unproven live** (no client ran it): the most likely first-boot issues are the map renderer (`MapCanvas.setPixelColor`
  each redraw), item-display pickup distances, puzzle entity clicks (cancel order vs core's combat listener on
  non-sky entities), and the placement speed on a busy server (8 ms/tick shared; a 6x6 run is ~180k blocks ≈ 4-6 s).
- **Balance is TUNE** where research had no numbers: room mob base stats, floor HP/damage scales, Master Mode x60/x6,
  fallback boss damage, secret table, blessing values, class milestone thresholds. All are dials or data in
  `gen_dungeons.py`. Fallback bosses use the research HP (F1 250k, F7 100M), which is the real game's toughness.
- **Ghosts use survival + flight + invisibility** (not spectator) so ghost abilities work; our listeners cancel their
  damage, targeting and interactions. A module that heals/targets players by its own logic may still touch a ghost.

---

### Extension points (adding a floor, a room tile or a menu button)

Everything below is a stable extension point. B works in the same module (`com.skyclone.mod.dungeons.*`), so B may
import any class here; the `ext` package is the intended surface.

### 1. Boss floors - `ext.BossFloor`, `ext.BossArena`, `ext.BossContext`, `ext.Bosses`
(Part B added `BossContext.spawnExact(mobId, at, maxHealth, damage)` and `addProp(entity)`, and `Bosses.enabled` for the `dungeons.real-bosses` dial. The seven real fights in `boss/` are the worked examples now.)
```java
Bosses.register("F1", new GrinkoFight());   // F-key covers M1 too unless "M1" is registered separately
```
- `BossArena` (PURE, no Bukkit): `id()`, `sizeX()`, `sizeZ()` (<= 160 x 200, `Geo.ARENA_MAX_X/Z`), and
  `build(VoxelCanvas c, int ox, int floorY, int oz, long seed, FloorDef floor, Points out)`. Write the arena with its
  min corner at (ox, floorY, oz), y range floorY-30 .. floorY+60 (floorY = 70, players stand at 71). Record points with
  `out.put("name", x, y, z)` (standing positions, template coords): **`spawn` (required: portal arrivals), `boss`,
  `chests`**, plus anything the fight needs. Runs off-thread at run start; it can be previewed by copying
  `gen/CatacombsSampleGenerator` (it already places `DefaultArena`).
- `BossFloor` (runtime): `arena()`, `start(ctx)` (first player through the portal), `tick(ctx)` every 5 ticks,
  `onMobKill(ctx, mob, def, killer)`, `onDamage(ctx, damageCtx)` (any hit on a boss/summon - immunities, caps),
  `onPlayerDeath(ctx, p)`, `stop(ctx)` (always, on close). **One instance serves every run: keep state in
  `ctx.state()`** (a per-run map).
- `BossContext`: `floor()`, `master()`, `world()`, `point(name)` / `points()` (world Locations), `arenaBox()`,
  `players()` / `alive()` / `isGhost(p)`, `spawnMob(id, at)` (scaled by floor hp/dmg, tracked, removed on close),
  `spawnBoss(id, at, maxHealth, damageMultiplier)` (exact HP: use `floor().bossHp`; `floor().bossDamage / def.damage` as
  multiplier), `bossBar(title, progress, color)` / `hideBossBar()`, `broadcast`, `title`, `bossSay(name, line)`,
  **`complete()`** (boss dead -> score, XP, collections, reward hook, return timer) and `fail(reason)`, `steps()`.
- `FloorDef` gives you `bossName`, `bossHp`, `bossDamage`, `bossCollection`, `master`, `number`, `hpScale`, `dmgScale`,
  `mobPool`. Fallback boss mobs `dungeon_guardian_1..7` exist (`data/dungeons/mobs.json`); register your own boss mob
  ids in your own data file and use them in `spawnBoss`. Do NOT put floor/boss tokens (`_f1_`, `_bonzo_`) in boss mob ids: the framework already credits the
  collection on `complete()`, and the collections module's kill heuristic would count the kill a second time.
- Players arrive at `spawn` (ghosts are revived on the way through). The framework hides the boss bar on
  complete/fail. `DefaultBoss` (run package) is a ~100-line worked example.

### 2. Loot / reward chests - `ext.RewardHook`, `ext.RunResult`
```java
RewardHook.set(result -> RewardChests.spawn(result));   // replaces the framework's free-chest fallback
RewardHook.onFail(runId -> ...);                         // optional
```
`RunResult`: `runId`, `floor`, `score`, `grade`, `skill/exploration/speed/bonus`, `seconds`, `members`,
`classes` (uuid -> class key), `deaths`, **`milestones` (uuid -> 0..9; research: the Wood chest needs 2)**,
`secretsFound/totalSecrets`, `cryptsOpened`, `mimicKilled`, **`chestSpot`** (the arena's `chests` point, the blood room
for the Entrance) and **`claimSeconds`** (raise it if players need longer to open chests). Called once, main thread,
after score/XP. The live run is `RunManager.get().run(result.runId)` (still open until the claim timer ends) - e.g. to
add props with `run.addProp(entity)` so they are cleaned up. Coins: `Sky.economy().withdraw(p, cost, "dungeons: chest ...")`.
Essence: `Sky.loot().give(p, "ESSENCE_UNDEAD", n, LootSource.DUNGEON)` (reforges' pouch absorbs it). Mayor
`paul.marauder` (`perkValue`) = chest discount %.

### 3. Room tiles - `gen.Tiles`, `gen.RoomTile`, `gen.RoomCtx`
`Tiles.register(RoomTile)` (or `Tiles.tile(id, name, type, weight, fits, ctx -> {...})`). PURE. The shell (floor at
y70, walls, ceiling y82, door openings) exists before `build(ctx)`; decorate inside, keep `ctx.doorZones` clear, and fill
`ctx.info`: `mob(x, z)` spots, `chestSecret/itemSecret/batSecret/essenceSecret/leverSecret/weakWallAlcove`, `crypt(x, z, axis)`,
points `info.points.put(name, {x,y,z})`. `ctx.frame()`/`fset(u,h,v)` author single-cell rooms relative to the door.
`fits(room, floor)` lets a tile target shapes (`room.shape`: "1x1".."L"), floors or puzzle ids. A tile id that already
exists is replaced.

### 4. Menu buttons and NPC anchors - `ext.DungeonHooks`
`DungeonHooks.menuButton(id, slot, p -> icon, p -> click)` adds a button to `/dungeons` (free slots 28-34, 37-43; 31
is the "current run" button during a run). Reserved Dungeon Hub anchors for your NPCs: **`npc.croesus`** (inside the
vault, south-west) and **`npc.essence_shop`** (by the class altar); register them with
`NpcDef.of(id, name, "dungeon_hub", "npc.croesus")`. The Gatekeeper (`dungeon_gatekeeper`) opens `/dungeons`; a Mort shop
button belongs in `DungeonHooks`.

### 5. Items B should register (ids the framework already references)
- The Provisioner lists these automatically when they exist: `CLEAVER`, `SUPER_CLEAVER`, `HYPER_CLEAVER`, `GIANT_CLEAVER`,
  `UNDEAD_BOW`, `SUPER_UNDEAD_BOW`, `DEATH_BOW`, `DARK_GOGGLES`, `SHADOW_GOGGLES`, `WITHER_GOGGLES`, `MENDER_HELMET`,
  `MENDER_FEDORA`, `MENDER_CROWN`, `STONE_CHESTPLATE`, `METAL_CHESTPLATE`, `STEEL_CHESTPLATE` (prices in
  `DungeonItems.SHOP`, from NEU Ophelia).
- Set `"dungeon": true` on dungeon gear so the in-run stat boost and stars apply (`ClassSystem.boostFraction`).
- `DUNGEON_CHEST_KEY`, `KISMET_FEATHER` are bazaar products nobody registers yet.
- Weapons registers 46 dungeon weapons as fallbacks only if the id is free: register yours in `enable()` (dungeons
  enables before weapons' postEnable) and they win.

### 6. Useful internals
- `RunManager.get().runOf(player)` -> `DungeonRun` (or null): `floor`, `state`, `members` (`Member`: class, level,
  ghost, deaths, damage stats), `rooms`, `blessings`, `score(false)`, `broadcast`, `at(int[])` (template -> world),
  `spawnMob(...)`, `explode(by, loc, radius)` (opens crypts/weak walls), `revive(p, loc)`, `complete()`, `fail()`.
- `Features.Dungeons` is provided (`catacombsLevel`, `classLevel`, `inDungeon`); requirement keys `catacombs:<lvl>`,
  `class:<class>:<lvl>`, `dungeon_completion:<floor>:<count>`.
- Data/generator: `tools/gen/dungeons/gen_dungeons.py` (floors, mobs, items; re-run after edits). Tests:
  `bash tools/gen/dungeons/run_tests.sh`. Preview a run: `bash tools/preview.sh com.skyclone.mod.dungeons.gen.CatacombsSampleGenerator <seed>`.


## Fix pass (2026-10-04, wave4-endgame playtest)
1. **Fairy Grove trap** (`Tiles` fairy_grove): the rim stood on the floor round a water ring sunk into it, a 2-block climb nobody can make (tested every direction with sprint+jump). The rim is now set into the floor (a 1-block step). Verified live.
2. **Ceiling spiders** (`DungeonRun.unstickClimber`, every second): a star-mob spider within 3 blocks of `Geo.CEIL_Y` goes back to its room's nearest mob spot, because a Cellar Spider parked in a top corner could only be shot from straight below and the room never cleared for a melee player. Compile + boot only.
3. The secret flash said "1/2 Secrets" next to RunHud's own "1/2 Secrets"; it now says "✦ Secret found! (n/N this run)".
4. `WitherBlades`: "You do not have enough mana!" at most once per 3 s per player (+ action-bar NOT ENOUGH MANA every click).
5. ~~Open (PLAYTEST wave 4 endgame #1)~~ fixed in the fixer pass below.

## Fixer pass (wave4-endgame) — solo boss floors, Creeper Beams reach, Wither Impact on puzzle chests, blood stragglers

From `docs/PLAYTEST.md` wave-4 endgame open issues 1, 6, 7, 8. Verified: `bash tools/check.sh dungeons` = **CHECK OK (263
files)**; **39 JUnit tests pass** (`bash tools/gen/dungeons/run_tests.sh`; new `BalanceTest.partyScaleArithmetic`,
`soloArrivalIsSurvivableAtDefaults`, `soloKillTimeIsBounded`, `PuzzleLogicTest.creeperBeamLanternsInReach`). **Nothing
was run on the server or in a client** - the numbers below are the model's; a solo F1-F3 run is the live check.

| # | Where | Problem (playtest) | Fix |
|---|---|---|---|
| 1 | `model/Balance.partyScale`, `DungeonRun.partyHp/partyDmg/activeMembers`, `FightBase.bossHp`, `WitherLordsFight.lordHp`, `BossCtx.spawnExact`, `Kit.scripted`, `ClassSystem.mobScale` | **Solo boss floors stopped at F1** (983 HP / 741 Def, Highlord + Wither Impact: F2 died in ~10 s x3, F3 lost 30% in 2 s). Root: research HP and every add on one target are sized for FIVE players; the only lever was a solo damage dial at 1.0 | **Party-size scaling.** Boss-floor health (bosses + adds, read at spawn) and damage (scripted, bodies, adds, read per hit) scale from new dial **`dungeons.solo-boss-hp-scale` (0.3)** / **`solo-boss-damage-scale` (now 0.4, was 1.0)** at one member linearly to 1.0 at five (members still in the run; ghosts count, leavers do not). 1.0 on both = the old behaviour for every size. Bramblemaw's hit-count HP is untouched (it is not `bossHp`) |
| 2 | `BossContext.spawnExact(..., addShare)`, `BossCtx` | F2's four students each swung their full research figure (2,432-2,880) **every second** on top of their charges, arrows and fire circles; Circus Undead 1,200 a second each | New dial **`dungeons.boss-add-melee-share` (0.25)**: an add's CONTACT hit is its research figure x share (the same reasoning as `boss-melee-share` for the body); its scripted attacks keep the full number. Lurid's copies pass `addShare=false` (they already use the body's share). Affects every floor's adds, parties included (owner's call: 1.0 restores) |
| 3 | `GrinkoFight.undeadCap/undeadSpot` | **Circus Undead rose within 3 blocks of Grinko**, who chases the party, i.e. on the arrival point; four at once | They rise on a ring at r 21 (between pillars and benches) at the one of 14 spots farthest from every living player, never within 10 blocks of the arrival. Cap 4 (6 Master) scales with party size, at least 2 (solo 2) |
| 4 | `DungeonRun.inArrivalGrace`, `Member.bossArrivedAt`, `Kit.scripted`, `ClassSystem.mobScale` | F3: 30% HP gone within 2 s of arriving | New dial **`dungeons.boss-arrival-grace-seconds` (3)**: no boss-floor hit lands on a player in their first 3 s after the portal (also a revived ghost walking back in) |
| 5 | `LecturerFight` | The Laser Guardian locked on the instant the guardians woke and its 175 true/s beam ignored walls; "all four within 10 s" is impossible for one player killing them in turn | First lock waits 4 s (`LASER_FIRST_STEPS`); the beam needs line of sight (grey line = blocked); the respawn window is `Balance.guardianWindowSeconds` = 10 s x (5/members)^2 (10 s at 5, ~16 s at 4, ~62 s at 2, 250 s solo), shown in the title, the fall message and the Boss Guide |
| 6 | `puzzle/PuzzleBuilders.beams` (`BEAM_LOW/HIGH/SUM`, `beamPairH`), `Puzzles.BeamsPuzzle` | Creeper Beams lanterns at floor+6..8 against the wall: clickable only looking straight up from below; the first click of a pair said nothing | Creeper one block lower (2-high plinth, stands at h 3); every lantern at h 2..5, partner at 7-h (the pair's midpoint still crosses the creeper's chest). First click: chime + "Lantern selected. Now click the lantern on the far side of the creeper." + END_ROD on the selected lantern every second; clicking it again deselects (said so) |
| 7 | `gear/WitherBlades.clickTaken`, `run/RunListener.onInteract` (NORMAL -> LOW) | Opening the Three Strangers chest with the Highlord also fired Wither Impact | No cast when: the click was already consumed by the dungeon (run block clicks now run at LOW and cancel first; `useItemInHand == DENY`), the clicked block is interactable (chest, door, lever, button, workstation - vanilla semantics), or a puzzle/boss entity click was cancelled within 250 ms. Sneak + right-click on such a block still casts. The weapons-module Shadow Fury / enchanting-table case is not ours |
| 8 | `DungeonRun.bloodStragglers`, `RunMob.born` | (unverified) F1 blood room: 4 mobs died in ~10 s, then ~2 min with nothing in reach before "You have proven yourselves" | A blood mob alive 15 s with no living player within 12 blocks glows; after 25 s with nobody within 16 blocks, or once it has left the Blood Chamber, the watcher drags it beside the nearest player in the chamber (else onto the blood spawn): "Do not keep my guests waiting." Root cause of the stray mob not observed; this makes any stray findable and reachable |

**The model** (`BalanceTest`, prints `[balance] solo Fn opening`): the playtest character with EVERYTHING the opening
puts on the field hitting him at once (no dodging, no healing): F1 1.4 s -> **12.6 s**, F2 0.7 s -> **5.1 s**, F3 1.1 s ->
**7.1 s**. Kill time at the measured Wither Impact rate (Grinko 2 x 250k in 50 s = 10k/s): F1 15 s, F2 87 s, F3 216 s
(guardians in turn inside the 250 s solo window). `/sb dungeons balance <floor>` now also prints the HP/damage
multipliers for each party size. Watch live: Wither Shield (100 + 25 x Catacombs absorption every 5 s) is most of a
solo player's sustain and is not in the model; melee-only solo F2/F3 is still hard. The four-AI party (4 members) gets
HP x0.83 / damage x0.85 plus the add contact share.

## Fixer pass (wave5-regression) - solo F1 rooms, titled books

From `docs/PLAYTEST.md` wave-5 regression open #4 and #5. Verified: `bash tools/check.sh dungeons` = **CHECK OK (269
files)**; **53 JUnit tests pass** (new `BalanceTest.f1RoomPacksAreSurvivableSolo`, `packArithmetic`, `ItemTitlesTest` x3);
4 new live self-tests in `Wave5RegTests` (`dungeons.w5r-book-titles`, `w5r-room-party-scale`, `w5r-f1-pack-model`,
`w5r-room-aggro`). **Nothing was run on the server or in a client** - a solo F1 with the Undead Sword is the live check.

| # | Where | Problem (playtest) | Fix |
|---|---|---|---|
| 4 | `DungeonRun.spawnMob/roomSide/roomHp/roomDmg/packAggro`, `ClassSystem.mobScale`, `model/Balance.EARLY/packTaken`, `DungeonAdmin.balance` | Superior Dragon + Undead Sword (Smite V) solo: killed in F1 room 2 (last hit a starred Crypt Souleater, ~30 s). Root, two parts: (a) every mob of a room aggroed at once - vanilla follow range 35 over 32-block cells - and a pack fight costs landed x kill-time x n(n+1)/2, i.e. it grows with the SQUARE of how many swing together; (b) room numbers are party content (the boss floor already scaled by party size, the rooms did not). Model at the measured 320 DPS (the same player's 20k Ghoul Horror II in 63 s): the whole 2-cell room cost **2,011 HP of 683** | New dial **`dungeons.room-aggro-range` (12)**: room mobs and minibosses get that follow range at spawn (0 = vanilla), so they engage in packs of about one cell. New dials **`dungeons.solo-room-hp-scale` / `solo-room-damage-scale` (0.5 / 0.5)**, linear to 1.0 at five members (`Balance.partyScale`) like the boss floor's: health at spawn, damage per hit, for kinds star / mini / blood / mimic / crypt (not boss bodies, adds or puzzle entities). Now an average F1 pack costs **144 HP**, the worst (three Souleaters) **284**. Neither lever alone meets the bar (packs at party numbers 575, solo numbers with the whole room 503) and the test asserts that. A party of four gets x0.875; five is untouched. `/sb dungeons balance <floor>` prints the room pack cost per party size |
| 5 | `model/ItemTitles` (pure), `loot/LootNames`, `RewardChests` (chest icons, open lines, RNG lines), `RngMeterMenu` (list icons, "set to"), `run/Secrets` | Reward chests and the open line said plain "Enchanted Book" (Combo I only in lore) | Books are titled from the book def's `extra.enchant/level` + the enchant def's name: "Enchanted Book (Combo I)" in the chest menu, every chat line, the RNG meter and secret rewards. The item itself keeps the reference name |

Watch live: the TUNE values (0.5 / 0.5 / 12) are the model's; a 12-block aggro may let a ranged Skeleton Grunt idle at
the far side of a big room until the player walks up, which is the intent (packs), but confirm the room still clears.

## Cross-module follow-ups (2026-10-06, integrator list in docs/FIX_BRIEF_2026-10-06.md)
- **F1 chests**: Diamond / Emerald / Obsidian exist from F1 (the wiki floor pages price them on F1: 50k/100k/250k, already
  in `WIKI_BASE`; research's F2+/F3+/F4+ were "(?)"). `FIRST_FLOOR` in gen_content.py; Bedrock stays F5+, the Entrance
  keeps Wood + Gold. Loot placement on F1-F3 re-spread over the wider chest list. `BossLootTest` aligned.
- Regenerating also picked up `SUMMONING_RING` in F6 Obsidian/Bedrock (materials registers it now).
- **Ananke Feather not wired**: NEU says only "a large amount of progress to an RNG Meter of your choosing", no number;
  kuudra.md has none either. Needs an amount before it can be wired (e.g. a % of the meter goal as a TUNE dial).
- `bash tools/gen/dungeons/run_tests.sh` 57/57.
