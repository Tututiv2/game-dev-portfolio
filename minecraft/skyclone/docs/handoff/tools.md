# Hand-off: tools (TestPilot + check.sh)

`tools/` is not a plugin package. It is `check.sh` / `preview.sh` / `gen_overview.py` and
`tools/testpilot/` (a Fabric 26.1.2 CLIENT mod + `pilot.py` + the playtest drivers). `bash tools/check.sh
tools` now checks it: TestPilot's own gradle build (its own project dir and lock, safe beside the plugin
agents), `NavTest`, `py_compile` of every driver, and the control-character scan.

## 2026-10-06 (wave-5 regression fix) - breath, dry fights, and a start inside a carpet

### [major] kill_nearest drowned the bot under the Bayou dock

Root cause: nothing in TestPilot knew about air. `kill_nearest` re-walked to the creature's own
coordinates every 3 s; the planner treated water as merely expensive (+4), so it routed along the bed
UNDER the deck (where the Trash Gobbler / Dumpster Diver sit, y 62-64); the walker only held jump when
the next waypoint was not lower, and between walks (the attack + cooldown) nothing held it at all. Under
a deck laid straight on the water, holding jump only presses the head into the planks. Fixed at three
levels (`Nav.java`, `Actions.java`, `Motion.java`, `StateDump.java`, `pilot.py`):
* **Planner**: a water cell where the head is under (`Nav.eyesInWater`, real fluid height) and there is
  no air straight up (`Nav.breathableAbove`: the first non-water block above has a collision box - a
  deck, a roof, ice, a waterlogged slab - or the water is deeper than 12) is never planned. Head-under
  cells that do have air cost +4 more. A swimmer's neighbours are asked from the water's surface
  (`Nav.waterTop`), so a pool is left by climbing a bank up to a block above the surface (vanilla's
  water-exit boost) - before, a 3-deep pool had no plan out at all, which would have left a bot knocked
  in by a sea creature standing on the bed.
* **Walker breath guard** (`walkTo.breathe`, runs before anything else each tick): eyes under water
  and air < 60% (`SURFACE_AT`) -> hold jump, and with no air straight up swim to `Nav.nearestAir`
  (nearest column with air within 10, reachable in a straight line past posts), else back to where the
  head was last above water; ends when the bar is full, then re-plans. Reply: `surfaced`,
  `underWaterTicks`, `air`, `maxAir`, `lowestAir`.
* **`dry:true`** (`/walkto`; `pilot.walk_to(dry=)`; CLI `walkto --dry`): never step INTO water - not
  planned, and `Motion.Body.dry` makes touching water a guard risk ("water") so the final approach and
  the wiggle stop on the dock/shore with `water:true`, `stoppedBy` "blocked: water - ...". A dry walk
  that starts in water still swims out. **`kill_nearest(stay_dry=True)` is the new default**: the
  creature is fought from the dock; when the walk ends at the water it waits 1.5 s for it to come
  close instead of re-walking on the spot, and a timeout says it stayed out of reach in the water.
* `/state` player: `inWater`, `underWater`, `air`, `maxAir`; entities: `inWater`.

### [major] /walkto from the Floor I start room: noRoute, 0 nodes expanded

The party is placed at y 71.0 inside the start room's carpet runner (top 71.0625). The start node was
at the feet, so every move out was swept/raised from inside the carpet, every one hit it, and the plan
was null. `Nav.startY`: when the player's own box overlaps something, plan from the top of the floor he
is sunk into, if that is within min(stepUp, 0.5) - a carpet, a snow layer; never a wall. A player
standing on something or mid-jump is unchanged. **For dungeons (not this module):** placing the party
on top of the carpet (y + 0.0625) is still the tidy fix; TestPilot no longer needs it.

Tests (NavTest +4, MotionTest +1; 43 unit tests, all green, NavWorldTest's real geometry unchanged):
`aPlayerSunkIntoACarpetStillHasAStartCell` (carpet runner, a carpet only under the start, a 3-layer
snow, plus the unchanged cases), `waterUnderADockHasNoAirAndIsNeverPlanned` (a dock pool fixture: the
bed under the deck is refused - one-plank deck: the answer is the deck above; two-plank deck: the open
water beside it), `aDryPlanNeverEntersWaterButSwimsOutOfIt`, `nearestAirFromUnderTheDockIsTheOpenWaterBesideIt`
(straight out; not through a row of posts; null in a sealed tank), `aDryWalkStopsOnTheShoreAndAnOrdinaryOneMayWadeIn`
(the guard's physics, walk and sprint off a shore and off the deck's end). Mutation-checked: with the
breathless rule removed the dock test fails; with `startY` removed the carpet test fails.

Not verified in a client (no server or client started). The breath guard's steering has no headless
copy (the Motion replica has no water physics). Rebuilt jar `tools/testpilot/build/libs/testpilot-1.0.0+mc26.1.2.jar`;
install as in README section 5 (old jars ignore `dry` and lack the air fields - `kill_nearest` then
behaves as before). To confirm: Bayou dock, fish until a Trash Gobbler surfaces under the deck,
`kill_nearest` it (expect no water entry, `water:true` walks, a kill when it comes up or a clean timeout);
`/walkto` out of the Floor I start room right after GO (expect a plan, `nodesExpanded` > 0).

## 2026-10-06 (wave-5 fix) - the edge guard now simulates the player's REAL body

### [minor] In Rat form the pilot walked off the north end of the Leviathan dock

PLAYTEST.md wave-5 "Still open" #4 said the guard "assumes normal walk speed". It did not: `Actions.body()`
already read MOVEMENT_SPEED, so the doubled speed was simulated. What it assumed was a normal **size**:
`Motion.Body` was always 0.6 x 1.8, and Rat "Shapeshift" sets SCALE 0.3 (0.18 x 0.54). The dock is cut
into a fixture (`nav/crimson_leviathan_dock.txt.gz`, `bash tools/navdump.sh
com.skyclone.mod.crimson.gen.CrimsonIsleGenerator crimson_leviathan_dock 146 58 -196 166 78 -160`) and it
shows the exact spot: the deck ends at z -185.0, but the end fence row stands one block further out
(arms at z -185.625..-185.375) over the void, a **0.375-wide strip of air** between deck and fence. A
person pressed against that fence still has 0.225 of deck underfoot; a rat drops through. The old guard
played a person's body, concluded "W into the fence ends standing", and let the rat walk in.
Reproduced headless: with a person-sized guard 11 of 27 rat walks fall at z -185.285 (the strip); with
the fix 0 of 27.

Root fix (`Motion.java`, `Actions.java`):
* `Motion.Body` carries `halfW` / `height`, set from `p.getBbWidth()` / `getBbHeight()` (SCALE and pose);
  the collision replay, the support checks, `nearVoid` and `edgeToward` all use it.
* The rest margin is explicit: `Motion.edgeMargin(speed)` = 0.15 at walk speed (exactly the old
  "0.15 core over ground" for a plain player - asserted), scaled with MOVEMENT_SPEED and capped at 0.35,
  because each tick the prediction is off costs a tick of motion. `Motion.stands()`: for a body narrower
  than twice the margin (a rat falls once its centre is 0.09 past the edge) the centre must stay
  `margin - halfW` INSIDE the edge. A sprinting rat stops about a block short of a drop.
* `Motion.JumpGate` (shared by `/walkto` and `/move`): Turbo Tail also grants flight, and two jump
  PRESSES within vanilla's 7-tick window toggle it - the walker's own step jumps land 7 ticks apart. The
  guard models walking and stands down while flying, and the next double tap ends the flight wherever it
  is. A held key (ladder, swimming) is untouched; a new press inside 9 ticks waits. `/move` reply
  `jumpsHeldBack`; `flyToggle:true` (pilot.py `move(..., fly_toggle=True)`) for a caller that means it.

Tests (`MotionTest`, 6 new; 38 unit tests in the suite, all green): `ratBodyIsItsOwnSizeAndSpeed` (2x sprint, a 0.5 slot
between wall posts stops a person and passes a rat, the rat falls where a person stands),
`edgeMarginIsUnchangedForAPlayerAndWiderForASmallFastBody`, `guardKeepsASprintingRatOnAnIsland`,
`ratPushingIntoTheDockEndFenceDoesNotDropThroughTheStrip` (unguarded rat drops, a person is held, a
person-sized guard lets the rat drop, the real guard holds it), `ratWalksOffTheLeviathanDockNeverFall`
(27 walks from three dock spots toward 9 void targets), `jumpGateNeverDoubleTapsWhileThePlayerMayFly`.
The headless walker takes a body shape (`walk(..., shape, guardSeesAPlayer)`).

**For crimson (not fixed here, not this module's files):** the dock's end fence row is placed at
`end - 1`, one block past the deck, so it floats over the void with that 0.375 strip in front of it; and
the rails alternate `crimson_fence` with `nether_brick_fence`, which do not connect, leaving a 0.75 gap
either side of every fourth post - wide enough for a plain player (0.6) to step through onto the rail
plank and off the side (`CSites.java`, the leviathan dock loop). Put the end row on the deck's last
block (z = `end`) and use one fence type per rail.

Not verified in a client (no server or client started). Rebuilt jar
`tools/testpilot/build/libs/testpilot-1.0.0+mc26.1.2.jar`; install it as in README section 5. To confirm:
Rat form (`/pets`, right-click the Rat), `/walkto` from the dock's middle toward a point 200 blocks north
with sprint - expect `blocked: edge` about a block short of the end fence, alive.

## 2026-10-04 (wave-4 endgame) - edge guard + bare-handed doors

### [major] `/walkto` walked off The End's island into the void (~20 times in a row)

The PLANNER never planned into the void (a column with no floor within `MAX_DROP` is not standable),
but the walker does not only follow plans: the final approach to the exact point, the 2 s straight
"wiggle free" after three re-plans that do not help, `path:false` and the straight-mode A/D sidestep
all just hold a key, and nothing checked the ground ahead. Root fix is one check every steering mode
goes through: `Nav.groundBelow` (the player's 0.6 footprint has a collision box, water or a ladder
within `Nav.SAFE_DROP` = 3.5 below the feet; a wall at body height counts, lava and unloaded chunks do
not) sampled by `Nav.edgeAhead` at a speed-scaled look-ahead (`0.3 + 1.6 x speed`, clamped 0.45-0.8:
sprint looks 0.75 ahead and slides ~0.34 after letting go) along BOTH the steering direction and the
current momentum. When it fires the walker lets go of W/sprint/strafe and taps S to brake, then: in
the wiggle it drops the wiggle and goes back to the plan; on the final approach / `path:false` it stops
with `edge:true`, `stoppedBy:"edge: ..."`; on a planned step (momentum at a corner, the world changed
under the plan) it re-plans, and stops after 5. A sidestep toward an edge is cancelled. A target off
the island now ends at the shore with `noRoute` (or `edge`). `edgeGuard:false` disables it.
New reply fields: `edgeStops`, `edge`. pilot.py: `walk_to(..., edge_guard=True)`.

Tests: `NavTest.edgeGuardSeesTheVoidAndNotAnOrdinaryStepDown` (void island, a 3-block step down is not
an edge, water beyond the edge is not, a footprint 0.1 on the block still stands),
`edgeLookaheadOutrunsTheSlideAndStillTurnsABridgeCorner` (sprint momentum round a one-wide bridge
corner must not trip it - the reason the look-ahead is capped at 0.8),
`planToAPointOffTheIslandStopsAtTheShore`. `NavWorldTest` now steers every leg of every real-geometry
plan (Tavern floors, Graveyard wall, lift sill, the Crystal Caverns spiral ledge) through the guard at
sprint look-ahead every 0.1 blocks: it never fires on a planned step.

### [major] Door clicks used the held item (Wither Impact fired mid-walk)

core's `AbilityListener` fires an item ability on RIGHT_CLICK_BLOCK as well as AIR, so a door click
with a Highlord in hand was a Wither Impact. Doors are now opened bare-handed, chosen by the pure
`DoorHand.choose` (tested in `DoorHandTest`): the hand is already empty; else an empty hotbar slot 1-8
(never slot 9, the SkyBlock Menu); else the held item is PARKED in an empty main-inventory slot with a
SWAP click on the player's own inventory menu (no screen opens), the walker waits 4 ticks so a refused
swap comes back from the server, and swaps it back after the door; else a hotbar item whose lore has
no "Ability" / "right click" / "click to" text; else the door is routed round like a locked one
(`doorsSkipped`). It re-checks the hand right before the click and never clicks with an ability item.
The selected slot is restored after every door, on finish and on cancel; if a screen opened while an
item was parked, the reply says where (`heldItemParkedIn`).

### Not verified

No server or client was started. Rebuilt jar: `tools/testpilot/build/libs/testpilot-1.0.0+mc26.1.2.jar`
(copy into the Fabric-26.1.2 instance's `mods/` while no TestPilot client runs, README section 5). To
confirm in a client: `/walkto` from The End's island toward a far coordinate (expect a stop at the shore,
`noRoute` or `edge`, alive); a walk through a hub door with a Highlord held and the hotbar full (expect
`doorsOpened` 1, no Wither Impact, the Highlord back in its slot and selected).

## 2026-10-04 (later) - wave-4 loops findings fixed

### [major] `/walkto` kept walking after a portal

The job never noticed the world change: it re-read `mc.player` every tick and steered for the OLD
target's coordinates in the NEW world (Dwarven Mines -> Glacite Tunnels: 110 blocks into the tunnels,
637 -> 296 HP, and the same on the way back). New `Actions.WorldWatch`, used by `/walkto` and `/move`:
the job ends when `mc.level` is no longer the level it started in (`stoppedBy: "world changed: a -> b"`,
`worldChanged: true`) or the player moved more than 8 blocks in one tick (a same-world /warp, lift or
void rescue: `stoppedBy: "teleported N blocks"`). `arrived` is false; the agent walks again from there.

### [minor] `/walkto` found no path in four places - three root causes

1. **Moves out of the start were swept from the start CELL's centre, not from the player.** A player
   pressed against a wall, fence or iron bars is in a cell whose centre is inside them, so every move
   hit the wall, the plan was null, and the walker re-planned the same nothing ("57 replans, 0
   waypoints"). Reproduced exactly on the real geometry: the Graveyard's west wall is a mossy
   cobblestone wall IN column x=140 (null from 140.01 and 140.99, fine from 139.5); the Pigmen's Den
   "lift cage" was the cage's SILL (slab on polished andesite, outside the iron bars, screenshot 036 at
   -3,100,-2) whose cell centre is inside the bars. `Nav.plan` now sweeps the start's moves from
   `from.x/from.z`. `/clearance` had the same flaw (cells along the ray, centre to centre - "0 in all 16
   directions"); it now slides the real box in quarter blocks (`Nav.standAtBox`).
2. **No ladders.** Every hub upper floor (the Tavern's guest rooms included), tower, well and mine
   scaffold is reached by a ladder through a hole, and the planner could neither climb nor descend
   one: from upstairs in the Tavern the plan dead-ended in the corner nearest the Graveyard. `Nav.plan`
   now climbs (feet in a ladder block -> one up, cost 2) and descends (ladder under the feet, cost 1),
   and steps off a floor onto the top of a ladder hole; no jumping or diagonals while hanging; a ladder
   is no longer a "floor" in `standAt`. The walker holds jump to climb and lets go of W to slide down
   (W into a wall on a ladder is a climb in vanilla). Ladders only - not vines (roofs) or scaffolding.
3. **No answer for "unreachable".** A target up a wall (the Crystal Caverns gems) got a partial plan to
   the closest point, then null re-plans and straight-line wandering until the timeout. Now a re-plan
   that gets nowhere (`Nav.leadsNowhere`) is retried once with 4x the node budget (a partial plan's
   "closest cell" can be a dead end), then the walker stands still and retries once a second; after
   three misses it stops with `noRoute: true` and a `stoppedBy` that says whether the target point
   itself is unstandable (<= 1.5 blocks away) or out of reach on foot.

The Crystal Caverns entrance itself was not a trap: from 25 sampled spots on the balcony the planner
reaches the Nucleus floor by the spiral ledge.

### New: real-geometry fixtures

`bash tools/navdump.sh <GeneratorClass> <name> x1 y1 z1 x2 y2 z2` runs an island generator (same seed as
the server) and writes the box, with its anchors, to `tools/testpilot/src/test/resources/nav/<name>.txt.gz`
(`tools/navdump/NavDump.java`). `NavWorldTest` loads them with real block states: `deep_lift3`
(Pigmen's Den lift station), `hub_tavern_graveyard` (x 40..175, z -70..45), `hollows_entrance`. Tests:
the sill and the wall cell plan with the fix and are null when planned from the cell centre (the old
behaviour); 80 seeded standing spots on both Tavern floors (a third pressed against something) all reach
the Graveyard through the legs loop, upstairs via the ladder, worst single plan 60-80 ms; 25 balcony spots
reach the Nucleus floor; an unreachable dome target ends closer and then "no route". `NavTest` gained
`laddersAreClimbedUpAndDown`. Fixtures are snapshots: re-dump after a generator change to the area.

`check.sh tools` also: compiles `NavDump`, requires `NavWorldTest`, and now uses **gradle's own exit
status** - it used to take grep's (`( gradle | grep -v ... ); RC=${PIPESTATUS[0]}` is the subshell's
status), so failing tests passed whenever gradle printed anything and an up-to-date build "failed".
Verified both ways (a deliberately broken assertion -> CHECK FAILED).

### Not verified

Nothing was run in a client (no server or client started). The new jar is
`tools/testpilot/build/libs/testpilot-1.0.0+mc26.1.2.jar`; it still has to be copied into the
Fabric-26.1.2 instance's `mods/` (README section 5) while no TestPilot client is running. To confirm:
`/walkto` through the Dwarven Mines -> Glacite Tunnels portal (expect `worldChanged`), from the Tavern's
upper floor to the Graveyard (ladder down), along the Graveyard's west wall, and off the lift sill.

## 2026-10-04 - wave-3 playtest findings fixed

### [major] `/walkto` stalled on a 15/16 step, on the Library's outer wall and on double doors

Root cause, three parts of one thing: the walker reasoned about **block cells and one point**, not the
player's body, and it had no idea of a route.

1. **The step test was a single point on the centre line** ("solid cell 0.8 ahead + two empty cells
   above = a step"). A `dirt_path` rise is 0.94 - over the 0.6 auto-step, so it must be jumped - and
   when only the side of the 0.6-wide hitbox was against the path block (walking past its corner, or a
   one-wide path), the point landed in open air, the walker decided "not a step" and sidestepped in
   alternation for minutes. Now `Nav.jumpNeeded` moves the player's **real bounding box** 0.35 ahead:
   blocked now, still blocked at `maxUpStep`, free at some lift <= 1.2 with headroom to rise there =
   jump. Slabs (auto-step), paths, farmland, full blocks are right; walls, door frames and 1.5-high
   fences are never jumped.
2. **Straight-line steering cannot get round a wall.** `/walkto` now plans: `Nav.plan` is an A* over
   standable cells (8 directions, jumps <= 1.2, drops <= 3, never cuts a corner, hazards avoided,
   water expensive). Every move is checked as a **swept** box between the two cell centres, because a
   closed door, a glass pane or a fence arm is a thin panel at a cell EDGE that a box standing at
   either centre misses (the first version of the planner walked through closed doors marked
   unopenable for exactly that reason - `NavTest` caught it). Goal beyond the 8000-node budget or
   unloaded chunks: it walks to the nearest explored cell and plans the next leg. Stuck 1.5 s:
   re-plan; three re-plans that do not help: 2 s of the old straight steering to wiggle free.
3. **Doors**: the planner treats closed wooden doors / fence gates as passable; the walker stops,
   looks at the door, right-clicks it with the client's own `useItemOn`, waits for it to swing, and
   also opens the **other leaf of a double door** (`Nav.partnerLeaf`: neighbour with the same facing
   and the other hinge). A door that will not stay open after 3 clicks (protected, iron) is added to
   an avoid set and routed round.

Speed: the first planner cost 110 us a node (VoxelShape.toAabbs() allocates on every call). Per-state
collision boxes are now cached (never for offset/context shapes: bamboo, scaffolding, powder snow,
block entities), as are hazards per block and stand heights per column within a plan: ~12 us a node,
`NavTest.fullBudgetSearchIsCheapEnoughForTheClientThread` keeps it under 300 ms (24 ms measured).

New: `/clearance {directions, max}` -> walkable distance per yaw plus `bestYaw`. `pilot.py`:
`walk_to(..., path=True)`, `clearance()`, `hold(needle)`; CLI `walkto --straight`, `clearance`, `hold`.

`NavTest` (10 tests, real block shapes after `Bootstrap.bootStrap()`, no client): dirt_path jump, the
off-centre step the old probe missed, slab = auto-step, wall/fence never jumped, plan round a 13-wide
wall, double door path + both leaves + route round when the doors are refused, open door leaves room at
the cell centre, partial plan, clearance, planner speed.

### [minor] wave3_pass harness: target_practice and pet_riding

* **target_practice** FAILed with a full hotbar (the `/give` bow went to the main inventory).
  `Run.hold_item` now uses `Pilot.hold`, which swaps an inventory item into the hotbar the way a
  player does (E, hover, number key = a SWAP click) into the first empty slot 1-8, never slot 9
  (SkyBlock Menu), and verifies it took. Also fixes `quiver_arrow`, which used the same helper.
* **pet_riding** inherited the previous check's spot beside the Archery Range and drove the Armadillo
  into the house. It now `/warp hub`s first, asks `/clearance` for the most open direction, and faces
  it before steering.

## Not verified

No server or client was started for this fix (other projects own the box's ports right now). The
geometry is covered by `NavTest`; the steering (waypoint following, door clicking, the double-door
partner, re-planning) has **not** been run in a real client. Next run of `wave3_pass.py
--only target_practice,pet_riding` and a `/walkto` out of the Barn square, into the Library and through
the Bank's double door confirms it.

**The installed jar is the old one.** Rebuilt at `tools/testpilot/build/libs/testpilot-1.0.0+mc26.1.2.jar`;
copy it into `MinecraftLauncher/data/instances/Fabric-26.1.2/mods/` (README section 5) while no TestPilot
client is running (AISkyShow drives the same instance - the jar is locked while a client is up). Old
jars answer 404 on `/clearance`, and `wave3_pass.py pet_riding` will then ERROR rather than FAIL.


## Requests (2026-10-04, wave4-endgame playtest)
Found by the wave-4 endgame playtest (PLAYTEST.md "Wave 4 endgame pass", open issue 3), not fixed:
1. **DONE (see "wave-4 endgame - edge guard" above).** **`/walkto` walks off island edges into the void.** Legs aimed at a point beyond The End's edge walked straight off, about
   20 times in a row (each a death and half the purse). Plan only through columns that have ground within a few blocks below.
2. **DONE (bare-handed doors, above).** **Door clicks use the held item.** With a Highlord (Wither Impact) in hand, opening a door on the path fired the ability:
   a 10-block teleport, 300 mana and a shield, mid-walk. Open doors with an empty hand / swap to a free slot first.
3. A 1-deep water pool whose rim is level with the surrounding floor is now jumpable (the Fairy room was fixed in the game),
   but the planner still treats water as near-impassable; in the old trap it sat at the pool's edge for the whole timeout.
