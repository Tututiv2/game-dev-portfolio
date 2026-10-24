# hub - hand-off

The Hub island: a ~600x600 floating island (`sb_hub`, radius 300) built entirely by a pure generator,
plus the module that registers it, its warps, mobs, NPCs, fast travel, launch pads, district guides,
travel scrolls, the ship and admin tools.

## Wave 5 (2026-10-06) - townhouse residents with errands, every hub NPC has a purpose, the Lotus Atoll on the ship

**Generator version 9 -> 11** (10 was an unfinished start of this wave: it placed only 3 of its 8 residents), so the
hub rebuilds once on the next boot (about 25 s).

### Done
1. **Townhouses have people in them (`HubVillage.residents`, generator).** Eight townhouse anchors
   (`HubAnchors.RESIDENTS`): `npc.pet_trainer` (skills' Pet Trainer, skills request 13), `npc.hoppity` (chocolate's
   rabbit keeper, chocolate request 1) and `npc.resident.1-6` (ours). Each gets a townhouse of its own, spread at
   least 30 blocks apart where possible, at the back of the ground floor facing the door with a clear walk from the
   doorway (rugs count as floor), never beside a ladder. **The bug the first attempt hit:** the civic buildings of
   waves 2-4 had taken the big lots, so the village had only 3 townhouses left for 8 residents. A second infill pass
   now builds 7-9 wide cottages in the remaining gaps (a block closer to the lanes) until there are residents + 4
   homes: **12 townhouses, 8/8 placed** (layout data `hub.residents`, shown by the preview report and `/sb hub info`).
   `npc.runic_pedestal` (skills request 13) stands inside the Blacksmith's forge. Skills and chocolate place their
   NPCs on those anchors themselves (their hand-offs: "the moment the anchor exists").
2. **Townsfolk errands (`Errands` pure + `HubErrands`, data `data/hub/errands.json`, 10 errands).** Every hub NPC of
   ours that only talked now asks one small paid favour, the way the reference hub's townsfolk do (research 03 2.1
   step 10 "Talk to the Lazy Miner - mining intro"; Jamie's free Rogue Sword; Romero-style deliveries):

   | NPC | errand | steps | reward (TUNE) |
   |---|---|---|---|
   | Jessamy (resident 1, ref Jamie) | A Blade to Start With | none | Rogue Sword |
   | Andrin (resident 2, ref Andrew) | Andrin's Harvest Basket | bring 16 Carrots + 16 Potatoes | 500 coins, 40 Farming XP, 3 SB XP |
   | Duke Harrow (resident 3, ref Duke) | Stand Your Ground | slay 15 Graveyard Zombies | 750, 60 Combat XP, 3 |
   | Lorcan (resident 4, ref Liam) | Two Letters | talk to Old Wenna, then Castellan Merrow | 600, 3 |
   | Curator Ottoline Vane (resident 5, ref Madame Goldsworth) | A Connoisseur's Eye | hold a reforged item + carry a Village Affinity Talisman | 1,000, 5 |
   | Wilf (resident 6, ours) | Show Me Your Pet! | a summoned pet + ask the Pet Sitter | 500, 3 |
   | Dozing Miner Aurel | The Lazy Miner's Quota | mine 20 Coal + bring 10 | 300, 50 Mining XP, 3 |
   | Farmhand Prosper | First Harvest | harvest 32 Wheat | 300, 50 Farming XP, 3 |
   | Old Wenna | The Restless | 5 Zombie Villagers (night) + bring 10 Rotten Flesh | 500, 50 Combat XP, 3 |
   | Castellan Merrow | Wolves at the Sister Fort | slay 5 Wolves | 1,000, 100 Combat XP, 5 |

   First talk: the NPC's intro + the offer, then the errand starts (a box lists the steps). Event steps (`talk:`,
   `zone:`, `gather:`, `kill:`, `fish:`, `pad:`: the District Guide grammar and event feed, counted only while the
   errand runs) tick with a chat line; hand-in steps are checked when you talk to the giver: `bring:ITEM:n`
   (inventory, all-or-nothing, re-counted at hand-in), `pet:TYPE|*:level` (**`Features.Pets.activeType/activeLevel`**),
   `accessory:ID` (**`Features.Accessories.has`**), `reforge:id|*` (**the held item's `Sky.items().data(stack).reforge`**),
   `req:<key>`. Each pays once (coins x `hub.errand-coin-scale`, SkyBlock XP via `SkyLevel.award("hub_errand:<id>")`,
   skill XP via `SkillService.addXp(..., "hub:errand")`, items via `loot.give(REWARD)`). The sidebar shows the running
   errand's next step in the Hub (only where no District Guide line is showing). **`/errands`** (`/townsfolk`) lists
   all ten with live progress and rewards; a click points your compass at the giver; also on the Hub Map (slot 47).
3. **No hub NPC is description-only any more.** The Archmage (whose arch is now live) says so, points the compass at
   the Warden of the Arch and opens `/rift`; the Carnival Barker and Gamesmaster (mayors request 9) switch their
   lines while the carnival is on ("the Ringmaster beside me...") and then send you to the Carnival Grounds; off
   season they say what brings it (a mayor with the Carnival perk, the mayor now, the next start if the calendar
   knows it, `/election`). A unit test fails if an NPC of ours neither gives an errand nor opens anything (the
   lumber jack stand-in is exempt: foraging's Woodcutter, with the 20-log quest, takes that anchor). Another checks the
   troupe has standing room beside both carnival anchors (mayors request 9's second half).
4. **Requirement keys** for other modules: `hub:met:<key>` (talked to that hub NPC; key = NPC id, id without `hub_`,
   or the npcs.json `ref`, e.g. `hub:met:andrew`, `hub:met:madame_eleanor_q_goldsworth_iii`, `hub:met:lazy_miner`)
   and `hub:errand:<id>` (errand handed in).
5. **The Lotus Atoll is charted.** galatea registers warp `lotus_atoll` (requirement `galatea.atoll-requirement`,
   Fishing X), so the Ship Navigator's second voyage is live. Each voyage now lists what is there, its `/warp`, its
   real lock and "Still being built..." until the island is generated; Captain Marlowe's intro mentions the Atoll.
   Self-test `hub.ship`.
6. **Accessories / pets / reforges hand-offs read for hub effects:** none of the hooked effects nobody applies yet
   belongs to a hub system (Target Practice, packs, pads, guides, ship); the hub mobs already carry
   `extra.mobTypes` (accessories request 3). The three reads the errands use are listed above.

**Verified:** `bash tools/check.sh hub` CHECK OK. `tools/preview.sh` RESULT OK (all 8 residents and the pedestal
reachable; looked at the crop round the square: the two new cottages sit on the lawns facing the lanes). Unit tests
**51/51** (JUnit launcher, no gradle): new `ErrandsTest` 5 (the shipped errands validate against every module's
item ids, npcs.json and SkillType; every resident has an errand; no NPC of ours only talks; hand-in all-or-nothing;
trigger parsing incl. `kill:hub_wolf` not counting the Old Wolf; pet/reforge checks; the validator catches broken
data), `HubGeneratorTest.everyResidentHasATownhouse` (8/8, indoors, floor + head room, one per house) and
`carnivalTroupeHasRoomBesideTheBarkers`; `npcsCanBeClickedFromTheFront` covers the residents. **Not run live.**

**Verify live (next round):** after the v11 rebuild, `/sb hub info` says `8/8 in 12 townhouses`; `/sb selftest hub`
(new `hub.errands`, `hub.residents`, `hub.ship`). Click Jessamy (Rogue Sword once), take Lorcan's letters (click Old
Wenna and the Castellan: chat ticks), hand in; `/errands` shows progress; `/sb hub errand <p>` lists, `... <id>
complete|reset` for quick checks. Force the carnival and click the Barker. `/sail` with Fishing X: Lotus Atoll sails.

### Still left
- Errand rewards are TUNE (the reference publishes no townsfolk rewards); one-time only, no repeatable dailies.
- Target Practice medals still gate nothing at the Fletcher (shops request below).
- The sewer Rat pet's 1/5,000 is TUNE (no data in research or NEU).
- `hub:guidestep` for progression request 9 is not built: a requirement's `describe` has no player, so it cannot say
  "(3/10)". It needs a `Features` call; noted for core.
- Researcher Gilbert (the reference's second Lotus Atoll gate) is not built anywhere; Fishing X alone opens it.
- Pad launch feel and every wave 3-5 change are still unplayed in a client.

### Requests (wave 5)
- **garden:** gate the hub-home visitors on meeting them at home (research 08: "tied to its home NPC having been
  met"): Andrin `hub:met:andrew`, Duke Harrow `hub:met:duke`, Lorcan `hub:met:liam`, Curator Ottoline Vane
  `hub:met:madame_eleanor_q_goldsworth_iii`, Wizard `hub:met:wizard`, Lazy Miner `hub:met:lazy_miner`. The names
  already match yours.
- **shops:** gate the Fletcher's tipped arrows on `hub:practice:1..4` (Jax's Target Practice in the reference).
- **progression:** errands award SkyBlock XP under key prefix `hub_errand:`; the errand box does not repeat the XP
  line, so no quiet prefix is needed.

## Fix pass (2026-10-04, wave4-endgame) - warp landings: the Wizard Tower and the Dark Auction

**Generator version 8 -> 9**, so the hub rebuilds once on the next boot (about 25 s). Two anchors moved and
one zone grew; no blocks changed.

PLAYTEST open issue 14 (minor): `/warp wizard` landed facing the tower wall, and during the Spooky Festival
`/warp da` landed 1 block from a Lv50 Wraith (260 a hit).

1. **Why both happened.** Every open-ground warp came from `HubWorld.openSpot`, which takes the first open cell
   near a hand-typed point and gives it **yaw 180 (north) whatever stands there**. The wizard spot was 4-6
   blocks east of a 13-wide, 30-tall tower, so it filled the left of the screen; the DA spot faced along the
   hall's blank side wall. And the DA spot (-156.5, 179.5) was **outside the Dark Auction's SAFE zone** (the
   hall + 2) and inside the `wilderness` area, where the hub's wolves and the festival's six spooky rules
   (mayors `spawns.json`) spawn, so a Wraith could spawn on the landing and hit you there.
2. **`gen/Landing.java` (new, pure).** A landing is chosen, not found: open ground inside the district's own
   SAFE zone, as near a preferred point as the rules allow, turned to FACE the landmark (`yawTo`), standing at
   least `gap` blocks clear of it, with a clear line of sight to it, and nothing at eye level within 5 blocks
   anywhere in a +-45 degree view (`viewProblem`, used by the generator and the unit test alike).
   - `warp.wizard_tower` -> **(-85.5, 76, 119.5), yaw 63**: on the north-east road where you would walk in,
     facing the tower 9.8 blocks off (tower = `Landing.WIZARD_REACH` 6.5 from its centre, gap
     `Landing.WIZARD_GAP` 9), door side of the plot only (the north-west corner is the Rift arch and its Warden,
     a walk-in gate that spends an infusion).
   - `warp.dark_auction` -> **(-155.5, 74, 171.5), yaw 90**: on the road, on the door's axis, facing the
     hall's door 7 blocks off (`Landing.DA_GAP` 6), the Doorman just beside it.
3. **The Dark Auction's SAFE zone is the whole plot** (`HubPlan.DARK_AUCTION`, x -186..-156, z 160..186, y
   daY-4..daY+16) instead of the hall + 2: the forecourt and the landing are SAFE (no spawns, no mob may target
   or hit you, a mob that walks in is leashed back). The `dark_auction` AREA (the hall, which mayors'
   `DarkAuction.inHall` reads) is unchanged. The sidebar now says "Dark Auction" from the plot edge.
4. **The rule, for every warp.** `Landing.unsafeLandings(layout, spawnAreas)` (pure): no `warp.<district>`
   lands in ANOTHER district's spawn area unless the smallest zone there is SAFE. A district's own warp into its
   own hunting ground (`warp.graveyard`, `warp.wilderness`, `warp.ruins`) is allowed: that is where you asked to
   go (the Graveyard's quiet gate is the pack's job). New self-test **`hub.warp-landings`** runs it live
   against the hub's loaded rule areas (the festival uses the same Graveyard/Wilderness areas) and checks
   `warp.dark_auction` is in the Dark Auction zone.

**Verified:** `bash tools/check.sh hub` CHECK OK. `tools/preview.sh` RESULT OK (all anchors reachable; looked at
the crop of the Wizard Tower and the Dark Auction: both landings sit on the road that arrives there). Unit tests
**44/44** (JUnit launcher, no gradle): new `HubGeneratorTest.wizardAndDarkAuctionWarpsFaceTheirLandmark` (the
view rule; the ray straight ahead meets the tower 9+ blocks out; the ray from the DA landing meets the hall's
door; the Rift arch and Warden are 8+ blocks off; no NPC on either landing) and
`noWarpLandsInAnotherDistrictsSpawnAreaUnprotected` (reads hub + mayors `spawns.json`; before the fix the DA
landing was in zone `wilderness`, not SAFE). **Not run live**: no server or client was started.

**Verify live (next round):** after the v9 rebuild, `/warp wizard`: the tower in the middle of the screen
across open ground, the door on its left. Force the Spooky Festival at night, `/warp da` a few times: you land
facing the hall's door, the sidebar says Dark Auction, and no festival mob spawns in the forecourt (one that
wanders in is put back). `/sb selftest` includes `hub.warp-landings`.

**Left alone (not reported, noted):** other open-ground warps still face north. Measured with the same view
rule, `warp.ruins` has a pillar 2.8 blocks ahead (the playtest screenshot reads fine: it frames the ruins) and
`warp.summit`/`warp.forest` have a block within 3 blocks in the +-45 degree view. `Landing.place` can take any
of them over if a playtest flags one.

## Fix pass (2026-10-04, wave4-loops) - the Golden Ghoul keeps to its vault

**Generator version 7 -> 8**, so the hub rebuilds once on the next boot (about 25 s). Only the layout
changed (a new area and zone); the blocks are the same.

1. **The Crypt was a coin furnace for a Ghoul-slayer grinder (major).** 600 HP / 630 Defense, farming Crypt
   Ghouls for the T3 Ghoul bar: three deaths in about six minutes (2M, 1M, 500k coins, half the purse each),
   about 10% of a 2,400 XP bar. The Lv60 Golden Ghoul (45,000 HP, 800 a hit; reference numbers, kept) spawned
   anywhere in the `crypt` area and walked the main hall about 14 blocks past the landing, which is exactly
   where the ghouls are farmed. Fixes, at the root (where it lives), not its numbers:
   - **The vault.** `HubEast.goldenVault(g)`: area `crypt_vault` (x 195..205, z -67..-57, the existing
     dead-end room at the top of the first north branch, 47 blocks up a side corridor from the main hall)
     and a zone `crypt_vault` "Golden Ghoul's Vault" (the area + 2, not SAFE). `spawns.json` (and
     `tools/gen/hub/gen_mobs.py`, re-run: output byte-identical to the hand edit) spawns the Golden Ghoul in
     `crypt_vault` only. Crypt Ghouls still spawn anywhere in the Crypt, the vault included.
   - **`GoldenVault`** (new behaviour `hub_golden_vault`, on the Golden Ghoul after `hub_crypt_pack`): it
     targets and hits only a player standing in the vault (floor + 2 blocks, so the doorway counts) or one
     who hit it in the last 8 s (`GraveyardPack.ENGAGE_MS`); anyone else is dropped as a target and its
     swings on them are cancelled in `onAttack` (the guarantee). With no target and off its floor it paths
     home (every 2 s at most). Follow range `hub.golden-ghoul-aggro` (8) instead of the Crypt pack's 12
     (re-asserted every tick, since the Crypt pack's dial resets every member). It still counts toward the
     Crypt's chaser cap. The rule is `GoldenVault.mayFight` / `inVault`, pure. Dial `hub.golden-ghoul-vault`
     (true; false = the old roam-anywhere behaviour).
   - **Crypt tip** (`hub.crypt-tip`, true): walking into the Crypt or landing on its stair says, at most once
     every 20 min, what a death costs (half the purse, "N coins right now", bank first: the Banker via
     `/village`, or `/bank` with a Boon Buff; purse 1,000+), where the Golden Ghoul's vault is and that it
     leaves you alone outside it, and the live Crypt chaser cap. Walking into the vault says the Golden
     Ghoul's numbers and what a death would cost (every 5 min at most). Both pure
     (`GoldenVault.cryptTipLines`, `vaultLines`).
   - `/sb hub pack` also prints the vault box, the dial and how many Golden Ghouls are engaged.
     Self-test `hub.graveyard-pack` also checks the Golden Ghoul spawns in `crypt_vault` and carries
     `hub_golden_vault`, and `GoldenVault.vaultProblem(layout)` (pure: the vault is inside the crypt area,
     30+ blocks from `warp.crypt`, clear of the SAFE landing, and the smallest zone at its centre).

**Verified:** `bash tools/check.sh hub` CHECK OK. `tools/preview.sh` RESULT OK (`crypt_vault` area and zone
in the report). Unit tests 42/42 (JUnit platform launcher, no gradle): new `GoldenVaultTest` (the fight rule,
the doorway margin, the tips, the shipped spawn/mob data, with the reference's 800 / 45,000 pinned) and
`HubGeneratorTest.goldenGhoulVaultIsOffTheGrindHall` (vaultProblem null, the vault 30+ blocks off the main
hall, 80%+ of its floor standable). **Not run live**: no server or client was started.

**Verify live (next round):** after the v8 rebuild, `/warp crypt` with a mid-game profile and a purse: the
Crypt tip appears; grind the main hall for 10 minutes and the Golden Ghoul never arrives; `/sb hub pack`
shows the vault box; walk up the first north branch into the vault: the warning appears and the Golden Ghoul
fights; step back 3 blocks down the corridor: it stops and walks home. `/sb mob` or `/sb spawner list` shows
`hub_golden_ghoul` resolving `crypt_vault` (`no area/anchors in layout` = the hub has not rebuilt yet).

## Fix pass (2026-10-04, wave3-playtest) - Graveyard newcomers, the Forest's direction, the Rift arch, Target Practice

**No generator change** (version stays 7, no rebuild).

1. **Six Graveyard deaths for a new player (major).** Wooden Sword and no armour, then the iron set and an Iron
   Sword: 220, 110, 107, 652, 328 and 166 coins lost, Combat II after ~40 min. Three chasers at 20 a hit against
   110-125 HP was the cause. Two fixes:
   - **Newcomer chaser cap.** `GraveyardPack.capFor(cap, newcomerCap, rampLevel, combat)` (pure): the
     Graveyard pack lets **1** zombie chase a Combat 0-2 player, **2** at Combat 3-4, and the full
     `hub.graveyard-max-chasers` (3) from Combat 5 (the brief's first goal). Mobs you hit still always
     fight back, so you can pull more on purpose. The Crypt pack is unchanged. Combat level is cached for
     5 s per player (the verdict runs for every member every tick); without a skills module there is no
     allowance. `/sb hub pack` prints the ramp and each player's allowance. New dials
     `hub.graveyard-newcomer-chasers` (1, 0 = off) and `hub.graveyard-newcomer-combat` (5, 0 = off).
   - **Graveyard tip** at the gate. A player below that Combat level who walks into the Graveyard zone
     (or warps there) gets, at most once every 20 minutes, only the lines that apply
     (`GraveyardPack.tipLines`, pure): "dying here costs half your purse (N coins right now), bank them
     first: the Banker, /village" (purse 50+), "you have N Defense: the Armorsmith sells a cheap iron armour
     set" (Defense under 30), "fight with a sword: the Weaponsmith, or a Wooden Sword from the Recipe Book"
     (no sword in the inventory), and "take one zombie at a time; zombies you have not hit leave you alone at
     the west gate". Dial `hub.graveyard-tip` (true).
2. **Decorative trees with no way forward (minor).** `ForestHint` (new listener): a refused log/wood break in
   the hub OUTSIDE the `forest` area, still standing a tick later (so a real chop is never mistaken for a
   refusal, whatever foraging's dials say), says once per 30 s: "These trees are the Hub's decoration. Only
   the Forest can be chopped: it is N blocks to the <direction>, west of the Portal Plaza", and points the
   compass at `warp.forest`. Core's "You can't break blocks here!" still comes first. Dial `hub.forest-hint`
   (true). The objective hint itself is progression's (see Requests).
3. **Walking through the refused Rift arch (minor).** The 3 s cooldown also skipped the refusal, so after
   one push a player still holding forward walked through the one-block sheet. `TravelSpots.onMove` now
   runs at HIGH and a refused move INTO a walk-in gate from outside it is undone on every move
   (`e.setTo(from)`, keeping the player's view), the message stays throttled (5 s) and the sound and
   push come at most once a second. Pads keep the push (you can be standing on one already).
   `TravelSpots.holdsBack` is pure; `/sb hub launch` calls `trigger(null, ...)`.
4. **Target Practice paid Gold for not aiming (minor).** Shooting the four targets in turn reached 17: a dark
   target cost nothing and the light stayed put, so a fixed rotation was sure to reach it within three
   arrows. A penalty alone does not fix that (one point per cycle against one wrong arrow per cycle on
   average is a fair coin). `TargetPractice.step` (pure): the lit target +1 and another lights; a **dark
   target -1 (never below 0) AND the light moves** to any target but the one just hit, so every blind arrow
   is a one-in-three guess and the score drifts down. Unit-tested over 2,000 seeded 40-arrow rotations:
   under 8% reach Bronze, at most 0.2% Silver; an archer hitting the lit target 9 times in 10 still gets
   Gold. Dial `hub.practice-wrong-penalty` (1, 0 = old rule). Menu lore and Jory's intro say so.
   **The round no longer hides HP/Defense:** score, time and short notes ("Wrong target! -1", "GOLD!") are
   a core action-bar segment (`hub_practice`, priority 400) after health/defense/mana, instead of our own
   action bar every 5 ticks.

**Verified:** `bash tools/check.sh hub` CHECK OK. Unit tests **37/37** with the JUnit launcher (gradle not used):
`GraveyardPackTest` 9 (new `newcomersFaceOneZombieAndTheCapClimbsToCombatFive`, `graveyardTipSaysOnlyWhatIsMissing`),
`TargetPracticeTest` 4 (new `aDarkTargetCostsAPointAndMovesTheLight`, `blindRotationEarnsNothingAimingStillWins`, which
also replays the old rule and shows the rotation reaching Silver), `TravelSpotsNudgeTest` 4 (new
`aRefusedGateHoldsYouBackAPadOnlyNudges`), new `ForestHintTest` 2, `HubGeneratorTest` 17 (new
`theForestIsWestOfThePortalPlaza`), `HubGuidesDataTest` 1. Self-tests `hub.graveyard-pack` (the ramp is within
1..cap, monotone, full at the ramp level; the tip has its lines) and `hub.target-practice` (a dark target costs and
moves) extended. **Not run live**: no server or client was started.

**Final verification (2026-10-04, PLAYTEST.md "Wave 3 final verification"):** all four fixes seen in a real client. One
follow-up fixed there: the result box's accuracy was `score / arrows`, which the dark-target penalty broke (a hit then a
miss read 0%); `Session.hits` now counts lit-target arrows and accuracy is `hits / arrows`.

**Verify live (next round):** fresh profile at Combat 0 in the Graveyard: the tip prints once; `/sb hub pack` shows
`chased by 1 (allowed 1)`; hit a second zombie and it fights back. Swing at a Portal Plaza tree: the Forest line
and the compass. Hold W into the Rift arch without an infusion for 5 s: you stay on the east side. Target Practice
rotation without aiming: no medal; the HP bar stays visible during the round.

**Requests (this pass):**
- **progression:** the objective hints are yours. `slay_graveyard` could read "Zombies roam the Graveyard,
  east of the Village. Bank your coins and buy the Armorsmith's iron set first: a death costs half your
  purse." and `chop_forest` "Chop 20 logs in the Hub's Forest, west of the Portal Plaza (/hubmap)."
  The hub now says both in the world too (Graveyard tip, ForestHint), so this is wording only.

## Wave 3 (2026-10-04) - the Rift arch, Target Practice, clickable NPCs, upper floors, 80 fairy souls

**Generator version 6 -> 7**, so the hub rebuilds once on the next boot (about 25 s).

1. **The Rift arch is a real gate (rift Request 2).** The amethyst arch by the Wizard Tower was decor. It is
   now `gate.rift`: a 5-wide portal sheet in a bigger arch (`HubSouth.riftArch`), run by the existing
   `TravelSpots` walk-in gate code, so the Rift's own `rift:pass` requirement is checked first (a player
   without a Planar Infusion is nudged back with "Requires a Planar Infusion (talk to the Warden...)") and
   the visit is charged on arrival by the rift module. Only cells a vanilla portal would keep are filled
   (held on all four sides), so no neighbour update can pop the sheet. **The arch moved** from the
   south-west corner of the Wizard Tower plot to the north-west corner (x -112, z 113..121): the old spot
   sat on the road to the Dark Auction, and a live gate there would have spent an infusion on anyone
   walking to the auction. Floating sign "THE ARCH - The Rift / Needs a Planar Infusion (ask the Warden)",
   amethyst posts either side, a new anchor `npc.rift_warden` (open ground beside the arch, facing the
   tower) offered to the rift module. Listed in the Portal Directory's "Other Ways Out" row (slot 41,
   "Rift Arch: The Rift") and `/sb hub launch <p> rift`. Self-test `hub.travel-spots` tolerates a missing
   `rift` warp only while no `rift` island is registered.
2. **NPCs you can click from the front.** A new unit test (`npcsCanBeClickedFromTheFront`) walks every
   `npc.*` anchor and fails if anything stands at chest height in the two cells in front of it. It found
   six: the **Banker** and **Banker 2** (counter lanterns right in front of them, integration round 2's
   "the lantern takes the click"; lanterns now at the counter's ends and middle), the **Vault Keeper**
   (he stood IN the Auction House's north wall, in a window hole, facing brick: the vault and the keeper
   are now inside the room's corner), the **Bartender** (brewing stand moved one step aside), townsfolk
   **lore 10** (a lamp post in his face; moved to -50, 30) and the **Snow Shoveler** (faced the weather
   station wall; now faces the arrivals from the south).
3. **Target Practice at the Archery Range (research 03 section 2.2, "Jax's Target Practice").** New NPC
   **Range Master Corliss** (`npc.range_master`, behind the shooting line). Menu: Start / Stop, best score,
   rounds played, four medals. A round lasts `hub.practice-seconds` (60): one of the four targets glows
   for you alone (per-player block change to a lit redstone lamp + sea lantern, lime particles) and an
   arrow in it from behind the shooting line (`archery.line` area) scores and lights another, never the
   same one twice. Wrong target or from in front of the line: no score. Action bar shows score and time;
   leaving the range or quitting ends it. Medals Bronze 5 / Silver 10 / Gold 15 / Diamond 20 pay
   250 / 750 / 1,500 / 3,000 coins (`hub.practice-coin-scale`) and 5 SkyBlock XP each, **once** (TUNE:
   the reference publishes none). Arrows are the player's own, so the Fletcher's stall next to it
   matters. Several players can practise at once. Medals are a requirement key any module can use:
   **`hub:practice:<1-4>`** (and `hub:guide:<guideId>` for a finished District Guide). Generator: four
   `archery.target.N` anchors on the target blocks (walk check ignores `archery.`), areas `archery.line`
   and `archery`. The Archery Range is now in the **Village Directory** (`door.archery_range`, the gate
   in the east fence). Dials `hub.practice`, `hub.practice-seconds`, `hub.practice-coin-scale`. Admin
   `/sb hub practice [player] [start|stop|reset]`. Self-test `hub.target-practice`.
4. **Upper floors are furnished** (`gen/Upstairs.java`). The Tavern's first floor is a guest dormitory
   (beds down both walls with nightstands, a table and rugs), the Old Keep's is the castellan's study
   (a wall of shelves, a lectern desk, a bed, a runner), and every two-storey townhouse gets a bedroom
   (bed, chest, bookshelf, table, rug). Nothing is placed within a cell of the ladder; a unit test checks
   that no ladder anywhere in the hub tops out with nowhere to step off.
5. **A seventh District Guide, Wizard Tower** (`guides.json`): talk to the Archmage, visit the Scroll
   Seller, find the Dark Auction house, visit the Hedge Witch (1,500 coins + 5 SkyBlock XP, TUNE). No step
   costs anything (walking through the arch would spend an infusion, so it is not a step). The Hub Map's
   Wizard Tower entry now reads "the arch into the Rift", with the Warden listed.
6. **80 fairy souls, the reference count** (research 03 section 4.2; we had 42). `HubWorld.moreSouls` scatters
   38 more over open country (forest, ruins, farm, lake shore, wilderness, mountain flanks, the island's
   rim), each at least 30 blocks from every other soul, in a seeded order. It runs after the old soul pass,
   so `fairy_soul.1..42` keep their numbers and players' found souls stay found. All 80 pass the walk check;
   a unit test pins the count at 80, none inside a block. The progression module counts them from the layout,
   so its totals become 80 for the hub with no change there.

**Verified:** `bash tools/check.sh hub` CHECK OK. `tools/preview.sh` RESULT OK (229 anchors, 43 areas,
39 zones, 0 reservations refused, every anchor reachable incl. `gate.rift`, `npc.rift_warden`,
`npc.range_master`, `door.archery_range` and all 80 fairy souls); looked at the arch and range crops and dumped the arch plane
and the Tavern's upper floor block by block. Unit tests 26/26 with the JUnit launcher (gradle not used):
`HubGeneratorTest` 16 (new: `npcsCanBeClickedFromTheFront` - failed with 6 NPCs before the fixes,
`riftArchIsAWalkInGate`, `upperFloorsAreFurnishedAndLaddersStillLand`, `archeryRangeIsReadyForTargetPractice`),
`GraveyardPackTest` 7, new `TargetPracticeTest` 2 (it caught the first target never being target 1), new
`HubGuidesDataTest` 1 (every guide is a district, every trigger kind known, every talk anchor one the hub builds).
**Not run live**: no server or client was started.

**Verify live (next round):** walk into the Rift arch without an infusion (nudged back, message), then with
one (Rift, charged once); `/sb hub practice <p>` with a bow and arrows, shoot the glowing target from behind
the line (score ticks up, another lights, only you see the glow), finish with 5+ (Bronze box, coins, SB XP),
run again (no second payment); click the Banker at the counter; climb the Tavern ladder.

**Requests (wave 3):**
- **rift:** the arch is now a live gate at the Wizard Tower plot's north-west corner. Please stand the Warden
  of the Arch at the new anchor **`npc.rift_warden`** (beside the arch, facing the tower) when it exists,
  instead of the offset from `warp.wizard_tower`; until then set `rift.hub-npc-offset` to `-19,0,-19`,
  which lands on the same spot. The old offset (-17,0,8) is still open ground (unit-tested), just 20 blocks
  from the arch now.
- **progression (fairy souls):** the hub now has 80 `fairy_soul.*` anchors (was 42); numbers 1..42 are
  unchanged. Nothing to do unless something hard-codes the old total.
- **shops:** the Fletcher (`npc.fletcher`, at the Archery Range) can gate better arrows on Target Practice
  medals with the requirement keys `hub:practice:1..4` (Bronze..Diamond), like the reference's Jax.

## Fix pass (2026-10-03, playtest "loops") - the Crypt landing, the Tavern's west door, the sewer rail

**Generator version 5 -> 6**, so the hub rebuilds once on the next boot (about 25 s).

1. **`/warp crypt` killed a 600 HP / 700 Defense player within a second, twice (major).** Two causes
   stacked. `warp.crypt` stood 3 blocks into the hall, and the `crypt` spawn area starts at the stair's
   foot, so ghouls spawned on the landing; and the Crypt Ghouls / Golden Ghoul are vanilla zombies
   (35-block follow range plus "alert others") at 350 / 800 damage a hit, with nothing capping how many
   swing at once. Fixes:
   - **SAFE landing zone** `crypt_landing` ("Crypt Entrance", `HubEast.cryptLanding`): the whole stairwell
     under the mausoleum floor plus the hall up to 7 blocks past the stair's foot (x 168..189, z -10..-4,
     y C-1..G-1). Core then spawns no ghoul in it, returns any that walk in, and lets none target or hit a
     player standing in it (`combat.safe-zone-protects`). The spawn area is unchanged; the ghouls fill
     the remaining ~49 blocks of main hall plus the branches. Walking down from the mausoleum passes
     through the same landing. Discovery still works (the hub checks every zone round the player).
   - **Crypt pack** `hub_crypt_pack` on both ghouls (`mobs.json` and `tools/gen/hub/gen_mobs.py`; the re-run
     changed exactly those two entries). `GraveyardPack` is now two instances of the same rule,
     `GraveyardPack.INSTANCE` (Graveyard) and `GraveyardPack.CRYPT`, each with its own members, dials and
     arrival anchor. New dials: `hub.crypt-aggro-range` (12, live), `hub.crypt-max-chasers` (3),
     `hub.crypt-gate-radius` (8, round `warp.crypt`; a quiet buffer just past the SAFE edge).
     Worst case now: 3 ghouls x 350 = 1,050 raw a second, about 131 after 700 Defense.
   - `/sb hub pack` prints both packs. Self-test `hub.graveyard-pack` now also checks the crypt mobs carry
     `hub_crypt_pack`, the crypt aggro is at most 24, both arrival anchors resolve, and that `warp.crypt`
     lands in a SAFE zone that does not cover the whole spawn area (`GraveyardPack.arrivalProblem`, pure,
     shared with the unit test).
2. **The Tavern's only door was round the north side (minor).** Walking straight over from the square
   (west) to the Slayer Master met the west wall's windows (playtester at 58,21). `squareDoor` now cuts a
   double door in the west wall at z 19-20 with a walk to the east lane (x 50); inside, z 19-20 is a clear
   row the length of the room to the Slayer Master (76.5, 21.5), who faces it. `door.tavern` (Village
   Directory compass) is now this door; the north door stays. Tavern added to `HubAnchors.SQUARE_DOORS`,
   directory text updated.
3. **The sewer stairwell could be fallen into (minor).** The rail came from the stair loop and only
   covered the first four steps' sides; the last open row (z -15) had no rail on either side and the
   cross rail sat at z -18, two blocks past the hole. Both sides (z -16..-11) and the north edge (z -16)
   are railed now; only the south end, where the stair starts, is open.

**Verified:** `bash tools/check.sh hub` CHECK OK. `tools/preview.sh` RESULT OK (182 anchors, 39 zones,
0 reservations refused); looked at the sewer crop. Unit tests, 19/19 with a reflection runner (gradle not
used): `HubGeneratorTest` gained `cryptArrivalIsSafe`, `sewerStairwellCannotBeFallenInto` (no standable
street cell next to an open column dropping more than 2; it caught the first version of this fix, which
railed the sides but left the north edge) and `tavernWestDoorLeadsStraightToTheSlayerMaster`; the existing
`bigBuildingsHaveADoorFacingTheSquare` now covers the Tavern. `GraveyardPackTest` gained
`cryptMobsCarryTheCryptPack`. **Not run live**: no server or client was started.

**Verify live (next round):** `/warp crypt` with a mid-game profile, stand 10 s (no damage), walk east into
the hall, `/sb hub pack` shows the Crypt pack with `chased by` 3 at most; walk from the Graveyard gate to
the Library over the sewer; walk from the square straight to the Slayer Master.

## Fix pass (2026-10-03, playtest "newplayer") - doors, the Fairy Pond, the Village Directory

**Generator version 4 -> 5**, so the hub rebuilds once on the next boot (about 25 s, as for v4).

1. **Doors faced away from the square (major).** The Bank, Auction House, Bazaar and Builder's House
   each had one door, on the side their walk leads to, and a wall of windows facing the square; the
   playtester walked into glass at all four. The toolkit's `Builders.House` has one door, so
   `HubVillage.squareDoor` cuts a second one in the wall facing the square: a double door,
   a lintel, the two rows inside cleared (the Builder's wall of colour blocks, a Bazaar barrel), a
   torch each side and a paved walk to the square (Builder: straight down to the west avenue, beside
   its yard). Bank south (x -34/-33), Auction House south (x 34/35, off-centre because the middle is a
   display case), Bazaar north (x -35/-34), Builder's House east (z -31/-30). The old doors stay.
2. **The Hub Map had no Village buildings (major).** New anchors `door.<id>` on the step outside 15
   buildings (for the four above it is the square-side door; list in `HubAnchors.VILLAGE`). New
   **Village Directory**: the first icon on the Hub Map, `/village` (`/town`), `/sb hub village
   <player>`. Each place shows who is inside, which side its door is on, and its distance and
   direction; a click points your compass at the door and says it in chat. The Fairy Pond is in it
   (anchor `npc.fairy`). Data is `VillagePlace.java`.
3. **The Fairy Pond drowned a new player (minor).** The pond is sunk one block under the flat village,
   so the bed dropped away under a bank two blocks above your feet: pressing forward walked him
   along the bottom, head under water. `HubVillage.wadeable` raises the bed until every water column
   is at most one block below each neighbour (a dry neighbour counts with its ground). The ring next
   to the bank is now a dry moss lip level with the water, the next ring is one deep (head above
   water), the middle keeps its depth. The **fairy moved** from the south bank to the north-east bank
   (-68, 68), the side you reach first from the square, so nobody has to cross the pond.
4. **"About 19 hub NPCs not spawned" (unverified).** That is core's lazy spawning: an NPC spawns when
   its chunk loads, so far districts show "not spawned" while nobody is near. New `/sb hub npcs`
   says, per hub NPC, spawned / waiting for its chunk (with coordinates) / anchor missing. Only the
   last one, or "(chunk loaded!)" on a waiting NPC, is a fault. The preview reports every anchor
   present, so none should be missing.
5. Town Crier Hollis's lines now mention the square-side doors and the Village page.

**Verified:** `bash tools/check.sh hub` CHECK OK. `tools/preview.sh` RESULT OK: 182 anchors, all reachable,
0 reservations refused; looked at the crops (diagonal walks to the square, the bank's new door).
`HubGeneratorTest` has 9 tests, 2 of them new: `bigBuildingsHaveADoorFacingTheSquare` (a real door in
front of each anchor, two free cells inside, the door nearer the square than the building's middle) and
`fairyPondCanBeWadedOutOf` (the staircase rule over the whole pond, fairy on dry ground on the town side).
Both fail against the old generator (checked by removing the fix). Plus `GraveyardPackTest`: 15/15 pass
with a reflection runner (gradle not used, other agents in parallel). Self-tests `hub.anchors` (now
also the `door.*` anchors) and `hub.menus-build` (now also the Village Directory). **Not run live**: no
server or client was started.

**Requests:**
- **progression:** "Talk to the Fairy" says only "by the pond in the Hub". Suggest: "south-west of the
  square; /village points your compass there". "Talk to the Banker" can also say "/village".
- **fairysouls:** the fairy NPC anchor `npc.fairy` moved to the north-east bank. Nothing to change if you
  use the anchor.
- **core (npc list):** `/sb npc list` could print "(waiting for chunk)" instead of "(not spawned)" when
  the anchor resolves but the chunk is not loaded.

## Fix pass (2026-10-02, integration round) - the Graveyard at night

**Problem (integrator):** a fresh player (140 HP, no armour) teleported into the Graveyard at night died in
about 3 s, twice, and again with 320 Defense; `/sb mob killall` then found 28 mobs on one spot. One zombie
did the expected 20 per hit, so the cause was how many were hitting at once.

**Root cause:** the Graveyard mobs are vanilla ZOMBIE / ZOMBIE_VILLAGER entities, and vanilla gives those a
**35-block follow range**, and hitting one alerts every zombie inside that range. Over the 98 x 104 field a
35-block circle is about 40% of the field, so most of the night's 24 mobs converged on one player, nothing
limited how many could swing, and they were left standing where he died (the "one spot"). Core's
`mob.aggro-range` (16) only governs mobs that are not hostile in vanilla, so it never applied. The spawn
spots and the leash were fine: spots are random over the whole field and the leash only acts 24 blocks
outside it. Density alone would not have explained it (24 mobs spread evenly put about 2 inside a 16-block
circle, 9 inside a 35-block one).

**Fix:** a new hub behaviour, `GraveyardPack` (`hub_graveyard_pack`), on both Graveyard mobs
(`mobs.json`, and in `tools/gen/hub/gen_mobs.py` so a re-run keeps it; re-run verified byte-identical).
- **Short aggro:** FOLLOW_RANGE set to `hub.graveyard-aggro-range` (16). That also bounds vanilla's "alert
  others". A chase ends past range + 8.
- **Chaser cap:** at most `hub.graveyard-max-chasers` (3) chase one player at once: the nearest ones. The
  rest are held (`setAware(false)`: they stand still) until a slot frees. `onAttack` also cancels any hit from
  a mob outside the allowance, so the cap is a guarantee, not just AI. A mob the player hits is "engaged"
  for 8 s and always fights back, so you can still pull more on purpose.
- **Quiet arrival:** mobs neither chase nor hit a player within `hub.graveyard-gate-radius` (6) of the
  `warp.graveyard` anchor (the west gate, where `/warp graveyard` lands). Works off the existing anchor, so
  **no generator change and no rebuild**.
- **Density:** night extra zombies 6 -> 4 and zombie villagers 8 -> 6, so the night total is 20 (day 10).
  Research still holds: more at night, villagers night-only.
- Module disable wakes every held mob.

**Worst case now:** 3 attackers x 20 = 60 per second against a 100-140 HP player who is fighting back
(was about 10 at once). At 320 Defense: 3 x 4.8 = about 14 per second.

**New:** dials `hub.graveyard-aggro-range` (16, live), `hub.graveyard-max-chasers` (3, 0 = no limit),
`hub.graveyard-gate-radius` (6, 0 = off). Admin `/sb hub pack` (members, held, who is chased by how many).
Self-test `hub.graveyard-pack` (both mobs carry the behaviour, aggro <= 24, the night population puts no
more than the cap inside one aggro circle, the gate anchor exists). Unit test `GraveyardPackTest`
(6 tests: the decide rule, give-up, gate, engaged, the shipped density, the mob data); 6/6 pass with a
reflection runner (gradle locked). `bash tools/check.sh hub`: CHECK OK.

**Verify live (next round):** at night, `/sb hub tp <fresh player> warp.graveyard`, stand 5 s (gate: no
damage), walk to the middle, then `/sb hub pack` should show `chased by` 3 at most and the rest held.
Not done here: the Crypt Ghouls are also vanilla zombies with a 35-block follow range; they are Lv30 Combat
content in corridors, so they were left alone. Attach `hub_graveyard_pack` to them too if they swarm.

## Review pass (2026-10-02) - what changed

A second agent reviewed the module against the brief, research 03 (sections 1, 2.2, 4) and every other
module's hand-off requests, then fixed and extended it. **Generator version is now 2**, so the hub
rebuilds once on the next boot.

**Bugs fixed**
- **Name collision:** the Old Keep's NPC was "Castellan Brannoc", the same name as the slayer
  module's "Brannoc the Slayer". He is now **Castellan Merrow**.
- **Discovery missed districts:** `ZoneChange.to` is only the *smallest* zone. Walking straight into the
  chapel, or a warp landing inside a building, never "entered" the district. Discovery now checks every
  zone that contains the player.
- **Shop and recipe items nobody registered:** the shops module sells `HUB_WIZARD_TOWER_TRAVEL_SCROLL`
  (Scroll Seller) and `HUB_CASTLE_TRAVEL_SCROLL` (Hermit), and crafting has recipes for
  `HUB_CRYPTS_TRAVEL_SCROLL` and `MUSEUM_TRAVEL_SCROLL`. No module defined any of them. The hub now
  registers all five hub scrolls (plus `HUB_DA_TRAVEL_SCROLL`) and makes them work.
- **Requests from other modules that were still open:** `portal.jerrys_workshop` (mayors). Seasonal
  vendor spots `npc.baker`, `npc.fear_mongerer`, `npc.zoo`, `npc.mythology` and `npc.stonks` (mayors;
  until now these vendors were lined up in the election room). `npc.auction_vault` (auction; the vault
  keeper was never registered without it). `fishing.hotspot.1..3` on the lake (fishing).

**Breadth added (from research 03)**
- **Launch pads (section 1.1):** Graveyard to the Spider's Den, the Farm's middle track to the Barn, and
  the Foraging Camp to the Park. Each is a 5x5 slime pad with lit posts and a floating label. Stepping on
  one checks the warp's real requirements first; a locked pad nudges you off with the reason. An open pad
  launches you (particles, sound, no fall damage) and lands you on the island.
- **Coal Mine to Gold Mine gate (section 4.2, "tunnel to Gold Mine"):** a drift north out of the coal-mine
  chamber to a deepslate gate set in the rock. Walking into it warps you. It sits outside mining's ore
  area, so the frame can never be mined.
- **Seasonal gate:** an 11th, ice-framed gate in the middle of the Portal Plaza's north row, leading to
  `jerrys_workshop`. Mayors' `mayors:workshop` requirement decides when it opens.
- **Ship Navigator (section 1.1, Captain Baha):** **Captain Marlowe** on the moored ship opens `/sail`.
  It offers Backwater Bayou (the real warp and its requirements, plus the `hub.ship-requirement` dial)
  and Lotus Atoll ("not charted yet" until someone registers `lotus_atoll`).
- **District Guides (section 2.2, the 2026 per-district first-entry objectives):** six guides (Mining
  District, Foraging Camp, Graveyard, Farm, Fishing Outpost, Ruins), four steps each. The data is in
  `data/hub/guides.json`.
  - **Triggers:** talking to the NPC on a hub anchor (any module's NPC), entering a zone, Gather events,
    mob kills, catching fish, riding a pad.
  - **Progress:** steps count in any order. The sidebar section `hub.guide` (priority 350, 2 lines) shows
    the next step for the district you are in.
  - **Rewards:** coins plus one-time SkyBlock XP through `Features.SkyLevel.award`. Both are TUNE.
- **Travel Scrolls (section 1.2):** right-clicking a scroll unlocks that district's fast travel even if
  you never walked there. Five districts now use the reference warp ids: `/warp castle`, `crypt`, `da`,
  `wizard` and `museum`. The others stay `hub_<district>`. Museum is a new 18th district.
- **Mobs:** a Rabbit in the farm's small-animal pen (research 4.3 lists rabbits with the farm animals),
  and a Rat pet drop on sewer rats (research: "Rat pet chance (?)"; 1/5000 is TUNE).
- **Menus:**
  - The Hub Map gained Ship Navigator (46) and District Guides (52) buttons, each district's guide
    progress, and its `/warp` id.
  - The Portal Directory lists the seasonal gate and an "Other Ways Out" row with the 3 pads and the mine
    gate, each with its real lock state.

## District map (north = -z)

```
                       N
          Ruins (-160,-128)       MOUNTAIN (30,-190), weather station on top, switchback road
     Colosseum (-80,-145)            |         Mining District (64..126, -134..-92) -> Coal Mine (carved north)
  Forest (x<-176)  \                 |         /       ...Coal Mine chamber -> drift -> GOLD MINE GATE (77,-194)
  Foraging Camp -- PORTAL PLAZA -- VILLAGE (spawn 0,V,12) ----------- Graveyard (140..238, -76..28) + Crypts below
  (pad -> Park)    (11 gates)        |      \                           (pad -> Spider's Den at 234,-24)
          Wilderness (SW):         Lake (0,182) +     Farm (102..214, 96..206)  (pad -> Barn at 199,117)
          Wizard Tower, Dark       Fishing Outpost     Carnival (40..84, 198..234)
          Auction, Hedge Witch     + ship (Captain)
                       S
```
**Village (|x|,|z| <= 98):**
- **Centre:** plaza with a three-tier fountain (seasonal vendor spots on the plaza ring), four 7-wide
  avenues and four lanes.
- **Civic and trade:** Bank (NW), Auction House (NE, vault keeper's corner with a `vault` block), Museum
  (SE), Bazaar hall + alley stalls (SW), Community Center + phone shop, Election Hall + decorator.
- **Learning and leisure:** Library (enchanting ring of 15 shelves) + sewer entrance, Tavern (2 floors) +
  sly vendor alley, Pet Care + pet yard, Alchemist + Flower House.
- **Crafts and combat:** Builder's House + yard, Weaponsmith, Armorsmith, Archery Range, Adventurer,
  Tailor, Owl keeper, Thaumaturge's Spire.
- **Other:** Greenhouse + pig pen, Fairy Pond, and about 10 furnished townhouses.

Code: `mod/hub/gen/` (pure, no Bukkit):
- **Pass order:** `HubGenerator`.
- **The map:** `HubPlan`.
- **District passes:** `HubTerrain`, `HubVillage`, `HubNorth`, `HubEast`, `HubSouth`, `HubWest`,
  `HubExtras`, `HubWorld`, and **`HubTravel`** (pads + mine gate).
- **Names:** `HubAnchors` holds every expected name, shared by the tests.

Module: `HubModule`, `HubMenus`, `HubNpcs`, `PortalLabels`, `TravelSpots` (pads/gates listener),
`HubGuides` (district guides), `HubAdmin`, `HubSelfTests`, `HubDistrict`, `HubData`.

## What works (verified how)
- **Generator:** `bash tools/preview.sh com.skyclone.mod.hub.gen.HubGenerator` gives `RESULT: OK`.
  - **Layout:** 167 anchors, 40 areas, 38 zones; zero unreachable anchors or portals (every pad and the
    mine gate has a walk-checked anchor).
  - **Blocks:** 0 reservations refused, all 335 block names valid, 0 writes outside bounds, about 0.75 s
    to generate.
  - **Looked at:** crops of the new seasonal gate, the graveyard pad and the farm pad.
- **Unit test:** `HubGeneratorTest` (7 tests, now also checking the travel anchors/areas) runs the real
  generator. 7/7 pass, run with a reflection runner at -Xmx3g because gradle is locked by other agents.
- **Module:** `bash tools/check.sh hub` prints `CHECK OK`. Not run live (rule 10: no dev server).
- **Mob stats:** research 03 section 4.3 + NEU drop tables, generated by `tools/gen/hub/gen_mobs.py`
  (re-run after the rabbit/rat edit and the Graveyard pack fix: 12 mobs, 14 spawn rules). Drops whose item is not registered are
  pruned at postEnable and logged.

## What is partial / not built
- **Wave 3 (generator v7) is not run live.** The hub itself has been played (v5 and v6 fixes verified in a
  client on 2026-10-03); the first boot after this change rebuilds it (about 25 s). Pad launch feel
  (`hub.pad-power`, `hub.pad-flight-ticks`) is still untested in a client.
- **Mayors' events:** Carnival and Dark Auction events belong to mayors; the buildings and areas exist. The Barker and
  Gamesmaster react to the carnival (wave 5).
- **Guide triggers depend on other modules' events.** Gather `what` strings differ per module (mining
  sends the drop id "COAL", foraging the block name "OAK_LOG"), so matching is "contains" (`LOG` matches
  any log). If a module never fires `Gather`/`FishCaught` in the hub, that step never counts; admins can
  complete it with `/sb hub guide`.
- ~~Lotus Atoll has no island~~ - charted in wave 5 (galatea's `lotus_atoll` warp).
- ~~Townhouses have no NPCs or purpose~~ - wave 5: 8 residents, the 6 of ours with errands (rewards TUNE).
- **Target Practice** medals are TUNE numbers, and nothing yet sells better arrows behind them (shops request).
- ~~The rift Warden at its old offset~~ - the rift module reads `npc.rift_warden` now.
- **No sewer-rat pet chance from research:** the 1/5000 is TUNE. The Rat pet itself is mainly a Garden
  pest drop in the reference (research 08).

## Commands added (player + admin)
**Player commands:**
- `/hubmap` (`/hm`, `/districts`): Hub Map. 18 districts; the ones you have discovered warp you there,
  the rest give direction and distance and point your compass. Buttons for the Ship Navigator, the Portal
  Directory and the District Guides. Also opened by Town Crier Hollis and by the SkyBlock Menu (menus
  module).
- `/village` (`/town`): Village Directory. The Village's buildings and the Fairy Pond; a click points your compass
  at the door. Also the first icon on the Hub Map.
- `/portals`: Portal Directory. 11 gates plus the 3 pads and the mine gate, each with its lock state.
  Click a gate to warp, or click an exit to travel to its district. Also opened by Warden Ysolde.
- `/hubguides` (`/districtguides`): District Guides. Also reachable from the Hub Map.
- Target Practice: talk to Range Master Corliss at the Archery Range (Village Directory lists it).
- `/sail` (`/ship`): Ship Navigator. Also opened by Captain Marlowe, from the Hub Map and from the Portal
  Directory.
- `/warp castle|crypt|da|wizard|museum|hub_<district>`: district fast travel.

**Admin commands (`/sb hub ...`):**
- `info`: layout, build state, missing anchors, unwired portals.
- `tp <player> <anchor>`
- `discover <player> <district|all|reset>`
- `map <player>`, `portals <player>`, `guides <player>`, `ship <player>`: open that menu for a player.
- `guide <player> <id|all|reset> [step#]`: finishes a guide (and pays its reward) or wipes progress.
- `launch <player> <warp>`: rides a pad or the gate as if the player walked onto it. Requirements apply,
  and the command says why when the player is refused.
- `travel`: lists pads and gates and whether their warps exist.
- `spawns`, `anchors [prefix]`, `labels`.
- `village <player>`: open the Village Directory for a player.
- `npcs`: every hub NPC, spawned / waiting for its chunk / anchor missing.
- `practice [player] [start|stop|reset]`: Target Practice rounds running; start, stop or wipe one player's record.
- `pack`: the Graveyard and Crypt packs (members, held mobs, who is chased by how many).
- `errand <player> [id|all] [accept|complete|reset]` and `errands <player>` (wave 5).

**Player (wave 5):** `/errands` (`/townsfolk`): Townsfolk Errands, also from the Hub Map (slot 47) and every errand NPC.

## Dials added
- **Existing:** `hub.portal-labels` (true) · `hub.subwarps-need-discovery` (true) ·
  `hub.discovery-message` (true) · `hub.npcs` (true) · `hub.map-travel-anywhere` (true) ·
  `hub.spawn-scale` (1.0, live) · `hub.golden-ghoul-respawn` (180 s, TUNE, live) · `hub.golden-ghoul-vault`
  (true, live) · `hub.golden-ghoul-aggro` (8, live) · `hub.crypt-tip` (true, live).
- **New:**
  - `hub.travel-spots` (true)
  - `hub.pad-power` (1.5, TUNE)
  - `hub.pad-flight-ticks` (30, TUNE)
  - `hub.guides` (true)
  - `hub.guide-sidebar` (true)
  - `hub.guide-coin-scale` (1.0, TUNE)
  - `hub.ship-requirement` ("" by default. Research gates the Bayou ship at Fishing V, so set it to
    `skill:fishing:5` for reference behaviour. It is left blank so the ship matches the plaza's Bayou
    gate, which fishing left ungated.)

- **Wave 3:** `hub.practice` (true) · `hub.practice-seconds` (60, TUNE) · `hub.practice-coin-scale` (1.0, TUNE).
- **Wave 5:** `hub.errands` (true) · `hub.errand-sidebar` (true) · `hub.errand-coin-scale` (1.0, TUNE).
- **Wave-3 playtest fixes:** `hub.graveyard-newcomer-chasers` (1) · `hub.graveyard-newcomer-combat` (5) · `hub.graveyard-tip` (true) ·
  `hub.forest-hint` (true) · `hub.practice-wrong-penalty` (1, TUNE).

## Self-tests added
- **Existing:** `hub.anchors` · `hub.mob-stats` (research numbers) · `hub.mob-drops-exist` (now expects
  12 mobs) · `hub.spawn-areas` · `hub.npc-anchors` · `hub.zones` · `hub.subwarps` (+ compass maths) ·
  `hub.portals-wired` (fails until every gate's island module registers its warp; today `dungeon_hub`,
  wave 2) · `hub.menus-build` (now also the guides and ship menus and the pad/gate labels).
- **New:**
  - `hub.travel-spots`: every pad and gate has an area, an anchor and a registered warp, and is listed
    in the directory; the listener sees all four.
  - `hub.guides`: the data parses; every talk anchor, zone, mob and pad named exists; the trigger matcher
    behaves on known event keys.
  - `hub.scrolls`: all five scrolls are registered by us and point at a district whose warp exists.
- **Wave 5:** `hub.errands` (data vs live items/NPCs/skills, giver and talk anchors in the layout, the menu, `hub:met` /
  `hub:errand` describe) · `hub.residents` (every resident anchor built, someone at each `npc.resident.N`) · `hub.ship`.
- **Wave 3:** `hub.target-practice` (medal rules, target rotation, anchors/areas, the live target blocks are
  TARGET, the `hub:practice` requirement describes).

## Requests (things you need from core or another module - the integrator reads this)
1. **menus**: `FastTravelMenu.subWarps` shows only 14 warps per island, and the hub now has 19 (hub + 18
   districts). Page it, or have the hub's right-click open `/hubmap` (`Sky.commands().run(p, "hubmap")`).
   The 5 short warp ids `castle`, `crypt`, `da`, `wizard`, `museum` already have textures in your
   `textures.json`.
2. **every island module**: register the warps the gates, pads and ship use: `dungeon_hub` (wave 2) and,
   optionally, `lotus_atoll`. All the others are registered today. `/sb selftest hub.portals-wired` and
   `hub.travel-spots` list any gaps.
3. **shops**: optionally sell `HUB_DA_TRAVEL_SCROLL`. It is registered but has no source; the reference
   source is the Bits shop.
4. **mayors**: your seasonal vendors now have their own plaza anchors (no election-room fallback needed).
   The seasonal gate `portal.jerrys_workshop` is built.
5. **mining**: hub `coal_mine` and `ores.coal_mine` cover the carved mine. The new drift to the Gold Mine
   gate starts outside that area on purpose (its frame must never be mined).
6. **farming / foraging / fishing**: District Guides count your `Gather` (FARMING "WHEAT", FORAGING
   "*LOG*", MINING "COAL") and `FishCaught` events when they happen in the hub. Keep firing them there.
7. **rift (wave 2)**: the `npc.wizard` anchor is ready. Send the warp id, and a `portal.rift` area can be
   added at the arch.

## Known risks
1. **Hub rebuild on first boot after this change** (version 1 to 2, about 5M blocks): untimed live.
   Players wait in limbo until it finishes.
2. **Pads are new physics.** A launch is velocity plus a delayed warp. If a server's anti-cheat or core
   movement checks fight the velocity, the warp still happens after `hub.pad-flight-ticks`. Fall damage
   is cancelled for 10 s after a launch.
3. **Guide rewards are TUNE** (1,000-2,500 coins and 5 SkyBlock XP each; six guides pay at most 7,000
   coins per profile, once). Scale them with `hub.guide-coin-scale`.
4. **Graveyard pack holds mobs with `setAware(false)`.** If another plugin or module also toggles awareness on
   these mobs, the two will fight; nothing in this project does today.
5. **Spawn areas are big boxes** (ruins hill, wilderness); tune density with `hub.spawn-scale`. Old Wolf
   and Wolf damage (90/800) and rat/rabbit stats are research `(?)`/TUNE values.


## Fix pass (2026-10-04, wave4-endgame playtest)
`hub.guide` sidebar priority 350 -> **450** (below every live section: at 350 the guide pair hid the Spooky Festival's candy count in the Graveyard). progression's district Objective moved to 449 with it, so their order is unchanged. Open: `/warp wizard` lands facing the tower wall; during Spooky, `/warp da` lands next to festival night mobs.
