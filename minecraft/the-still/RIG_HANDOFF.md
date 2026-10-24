# HANDOFF — THE STILL, and specifically THE RIG

> Rewritten 2026-08-04, after the seventh rig. Read `CLAUDE.md` first for the world.
> The previous version of this file described a rig assembled from hand-typed
> coordinate tables that nothing checked, and listed seven faults the owner found by
> walking it. **That rig is gone.** Placement is now a search with the constraints as
> code, and the faults are unit tests.
>
> **The one thing to keep, if you keep nothing else: do not hand-place a coordinate on
> this rig.** Add a row to the roster and let `RigLayout` decide where it goes. If it
> cannot fit, `RigLayoutTest` says so in a second instead of after a seven-minute
> rebuild and a walk.

---

## 0. Where it stands

Built and verified 2026-08-04, from an empty world:

```
35 buildings on 4 floors    19 on the deck, 7 / 5 / 4 above it
6 staircases                0->1 x3, 2->3, 0->2, 1->3
20,518,954 blocks           full setup world, ~7 min at a flat 20 TPS
118 unit tests              18 of them about the rig alone
/still verify rig           56 of 56 reachable on foot, 35,413 standable
                            blocks walked from spawn
/still verify opening       a new player can walk from the Landing to the winch
/still newplayer [who]      become a brand-new player again, so the gate is repeatable
```

Zero placement errors in the log. **Seen with a client once**, on 2026-08-04, and the
verdict was "not terrible" plus a list — every item on which is in §3b.

---

## 1. What changed, and why it was structural

The owner's seven faults, verbatim from the last rejection, and where each one now
lives:

| The fault | The cause | Now |
|---|---|---|
| "random stairs going nowhere" | `STAIR_LINKS`, 9 hand-typed rows, ends never checked | `RigLayout.climbable` proves both ends are floor and the run is clear, or there is no stair |
| "none of the platforms connect with each other" | one plate per module, `planCatwalk` written and never called | each building after the first must land against the floor already there; `everyFloorIsConnected` asserts one component per floor |
| "floating roofs" | pitched/vaulted drew a surface with no ends | gable ends, tympanums, and a terrace under every setback |
| "open roofs" | vaulted placed one block per column | the barrel fills its own vertical jumps; six forms, all closed |
| "interior as terrible as possible" | no ceilings, no stairs, props in walls, lamps in mid-air | storeys, an internal staircase, a prop table keyed on building id, lights last and anchored |
| "stairs going in buildings" | no footprint test | the run is tested against every building on every floor it passes |
| "buildings colliding with each other" | `MODULES`, 30 hand-typed `(x,z)` | there is no `(x,z)` column any more |

**The pattern was one cause with seven faces: unvalidated hand-typed tables.** Fixing
them one at a time would have produced an eighth rejection, because the next edit
breaks a different row and nobody finds out until somebody walks it.

---

## 2. The two things that do the checking

### `RigLayout` — the plan, proved in a second

`plugin/src/main/java/com/thestill/world/RigLayout.java`. **No Bukkit in it**, on
purpose: that is what lets `RigLayoutTest` run the real layout with no server.

It takes a list of `Slot(id, w, d, storeys, level)` and returns where everything
stands, which floors exist as cell sets, where the staircases are, and what it could
not fit. `StillWorld` owns materials and block placement; this owns integers.

**The vertical rule is the load-bearing idea.** Floors are 9 apart and a building is
taller than 9, so a two-storey shed on floor 1 is *in the way of floor 2*. A building
therefore reserves `Slot.span()` floors, not one. Forgetting that is how a plate ends
up cutting through somebody's roof, and it is why:

- **Placement runs highest floor first.** This is the opposite of the obvious order and
  it was found by a failing test: place the deck first and 19 sheds fill the platform,
  each one blocking floor 1, and floor 1 ends up with a single building on it. The
  floors that need a contiguous district take it; the working deck fills in around them.
- **A plate is an obstacle to what is under it, not to what stands on it.** That is the
  `Kind.BODY` / `Kind.PLATE` split, and it is the only reason two buildings on one floor
  can share a plate while a tall building on the floor below cannot punch through it.
- **A body reserves its RAW footprint.** The corridor between buildings is added once,
  by whoever is asking. Adding it on both sides gave every stair six blocks of clearance
  it never needed, and on a dense floor that was the difference between a staircase and
  none.

### `ClimbVerifier` + `/still verify rig` — the build, proved on foot

Ported from SmhMines. `/still verify rig` floods standable space from spawn *once* and
then asks every door, both ends of every staircase, and three widely-spaced points on
every floor whether they are in it.

**This is a different claim from the layout test and both are needed.** The layout
cannot see a rendering bug or a pass that overwrote a correct plate; a block counter
that increments on placement lies if something was drawn over it. The only way to know
is to re-read the world.

Conservative by design: iron bars are **not** passable, and any doubt resolves to "not
proven". A false failure costs a look; a false pass costs another rejected build.

---

## 3. What is where

All rig code is `plugin/src/main/java/com/thestill/world/`.

| Method / file | What it does |
|---|---|
| `RigLayout.compute` | placement, floors, bridging, stairs — the whole plan |
| `RigLayout.LANDMARKS` etc. | helideck, cranes, flare, lifeboats, jacket legs, machinery stairheads. **`StillWorld` reads these constants too**, so the builder and the collision test cannot drift |
| `ClimbVerifier.flood` | one walk, many questions |
| `StillWorld.MODULES` | the roster — id, name, sub, size, storeys, floor, materials, prop, locked |
| `StillWorld.planFloor` | draws a floor as the union it turned out to be, with legs that skip anything standing under them |
| `StillWorld.planStair` | five wide, one riser a step, solid to the floor below, plus its landing |
| `StillWorld.buildModule` | walls, glazing predicates, setback terrace |
| `StillWorld.planRoof` | six forms off the id; flat is one in six because the owner called flat roofs out |
| `StillWorld.furnish` | hollow → carpet → furniture → **stairwells** → **lights**, and that order is the point |
| `StillCommand.verifyRig` | `/still verify rig` |

Deleted, not disabled: `planRigLegacy`, `quarters`, `hut`, `marketUnitLegacy`, `deckAt`,
`planControlHouse`, `MARKET`, `STAIR_LINKS`, `MARKET_SLOTS`.

---

## 3a. What the in-world walk caught that the layout test could not

Three rounds of `/still verify rig`, three real faults — and every one of them was a
place where the **plan's idea of "connected" was not the world's**. This is the whole
argument for having both checks.

1. **A one-block ledge behind a railing.** Two plates overlapping in a single corner
   cell are connected on paper and are a one-block doorway in the world, and one
   railing post seals it. Fixes: plates must now meet at least `MIN_JOIN` wide, and a
   post is only placed on a straight run with floor two blocks behind it.
2. **A strip of floor pinned between a building and the edge.** *Plate connectivity
   walks through walls.* A building stands on its own plate, so the graph the test was
   checking was not the graph a player is in. Fix: the layout now keeps `walkable`
   (plate minus buildings, largest piece only) separate from `floors` (what to draw),
   and every reachability claim reads `walkable`.
3. **A staircase is a wall across the floor it leaves from.** It rises as a solid ramp
   and punches through every plate on the way up. One stair cut floor 1 in half; a
   second one then landed its head under another stair's ramp, fourteen blocks away and
   three blocks below it. Fixes: a candidate stair is rejected if cutting its run would
   split any floor it crosses, and two stairs whose height bands overlap may not share
   columns at all.

The generalisation, and it is the one worth carrying: **when you verify a plan, verify
the graph the player is in, not the one that was convenient to build.**

## 3b. The first client session, and the one bug behind half of it

The owner logged in on 2026-08-04. Verdict: *"not terrible"*. Then a list — and three
items on it turned out to be **one bug**.

> **THE DECK IS A DISC, and half the rig thought it was a square.**
>
> `planRigDeck` draws it with `disc(0, y, 0, 96, …)`. `RigLayout.onDeck` tested a
> square with a chamfered corner, which reaches 108 blocks out — twelve past the edge
> of a circle nothing else knew was a circle. Everything measured against the wrong
> shape ended up over the sea:
>
> - **"floating iron bars around the edge, following the rig's old perimeter"** — the
>   railing traced the square. Most of that perimeter is not deck, so most of the
>   railing was hanging in the air.
> - **"one leg of the heli stand is off the edge"** — the outer leg stood at (72, −68).
> - The cell block, which nobody had spotted yet: 37 blocks long, starting at (34, 66),
>   so its far half was over nothing. And a lifeboat davit. And the flare boom's tower.
>
> One shape, one function, and everything that needs it asks. `landmarksAreOnTheDeck`
> now holds every landmark to the same outline the buildings are held to, and
> `theDeckIsADisc` pins the shape itself.

The rest of the list, and what each one became:

| Reported | Done |
|---|---|
| "make the floating text bigger" | `TextDisplay` had no transformation, so it rendered at scale 1. Scale 2.4, and the view range raised from 0.55 (35 blocks — nearer than the fog) to 1.6 |
| "staircase to heli pad is not connected at all" | It was two blocks of floating strut ending two blocks *past* the pad's edge in mid-air. Now a five-wide ramp, solid to the deck, whose last tread is the pad's own rim block |
| "paths to all the shops" | A spanning tree computed in `RigLayout.routes` — one BFS from the plaza, every door traced back along it, so routes share trunks and read as streets. `everyDoorHasAPath` asserts one per door, which makes it a reachability proof as well as paving |
| "the plots are terrible" | Was a chequerboard of dark pads. Now a low kerb round each lot with a gate facing the lane, a lamp post on the corner, two-tone paving, and lanes in the same stone as the wayfinding routes so the quarter reads as streets continuing off the plaza |
| "the cells are terrible" | Moved on deck (see above) and out of the middle of the plot grid |
| "spawn in the plains and use the winch the first time" | Done — see below |
| "make the rig glow in the fog" | Done — see below |

### The opening moved to the bottom

**A new player now wakes up on the Landing, not on the rig.** `Onboarding` teleports to
`StillWorld.landingSpawn`, eight blocks back from D1's winch, looking at it.

Why it is better, and worth protecting: the rig was a lobby. It handed you safety and
asked you to jump out of it, and it left **the winch — the most dramatic thing in the
whole design — to be found by accident an hour later**. Starting at the bottom makes
the rig something you can see overhead and have to earn, and it puts the first winch
pull inside the first five minutes.

Two things had to be built to make it survive contact:

- **The winch pad.** The plains roll by about 24 blocks and the winch was placed at the
  depth's *nominal* ground height, so it was buried to the waist in a hillside. It now
  levels a 23-wide circle around itself: packed below, cleared above, lit on the rim.
- **`/still verify opening`.** Floods from the spawn and checks the lever is reachable.
  If that one walk does not exist nothing else about the server matters, and no rig
  check covers it.

### The rig glows, and it is the legs that do it

The fog closes to opaque well inside the hundred blocks between D1 and the deck, so the
rig cannot be seen from below however bright it is. What *can* be seen is something
close: **four columns of navigation lights climbing out of the cloud directly
overhead**, on the jacket legs. You do not see the rig — you see that there is one, and
which way is up. Which is what an offshore platform actually looks like from the water.

## 4. Rules this rewrite paid for

- **A coordinate nobody checked is a bug waiting for a player to find it.** Every fault
  above was a number that looked fine.
- **Place the constrained thing first.** Whatever needs a contiguous region gets it
  before whatever needs a hole its own size.
- **Pad once.** Clearance added at both the reservation and the query is clearance
  applied twice, and it starves every later search silently.
- **A stair's head must be floor, and one block PAST the head must also be floor.**
  "You reached it" and "you can step off it" are different claims. Three separate cave
  bugs on this server hid in exactly that gap.
- **Furniture a later pass erases is worse than no furniture**, because the counter
  still says it was placed. Props go against the near wall; the stairwell owns the far
  one.
- **Feed a wall the coordinate that varies along it.** The window predicate used `x` on
  every face, so on the two faces where `x` is constant it returned one answer for the
  whole wall: every window, or none.
- **No containers at spawn.** A barrel is what a crate looks like and also 27 slots of
  storage in a public building. `CRATE` is a stripped log — a worse crate and a better
  one. (Judgement call kept: functional workstations stay, because a smeltery without a
  blast furnace is not a smeltery.)
- **A light needs its own beam, and the top storey's ceiling is its roof** — which under
  a pitched or vaulted roof has no block over the middle of the room at all.
- **A verifier that samples the extremes samples the scenery.** The first version picked
  the min-x cell of each floor and failed on a stray ledge. Sample the *walkable
  interior*, and spread the samples — a check that fails on things nobody cares about is
  a check people learn to ignore.
- **One shape, one function.** Two definitions of the same outline is not duplication,
  it is a disagreement waiting to happen, and it will be found by a player rather than
  by you. This one cost three separate reports in a single sitting.
- **A landmark is a building that nobody validates.** The helideck, the cell block, the
  davits and the flare tower were all placed by hand next to the code that draws them,
  and every one of them was in the wrong place. They are reserved rects in the layout
  now, and the test holds them to the same rule as the roster.
- **Nominal ground is not ground.** Anything placed on generated terrain at a computed
  height has to level its own footing or it will be buried or on a stalk, and which one
  is a coin toss per instance.
- **A `TextDisplay` has no size until you give it one.** Default scale is 1 and default
  view range switches it off at 35 blocks.

## 5. Techniques already extracted — do not re-research this

Three research agents read the sibling projects. Everything they found is below; the
sibling projects are still the first place to look before writing anything new.

| Where | What is there |
|---|---|
| `../Minecraft game im making/` | **Shadow Prison — the deepest well.** `ui/FloatingTextService.java`. `prison/*Dresser.java` — 15 themed area builders. `themes/EmberwickSpawnCommand.java` — the roster-record + one parametric builder, which is the pattern this rig now uses. |
| `../MinecraftServerSkyBox/` | SmhMines — `spawn/ClimbVerifier.java` (ported), `build/BuildQueue.java`, an 880-block mountain spawn. |
| `../Minecraft Sword Art Online Prison Server/` | Aincrad — `editor/Shapes.java` (one traversal + a predicate), probe-table verification. |
| `../MinecraftServerGens/` | Astral Ring — banded-disc + row-RLE, hull depth from shrinking discs. |

**Applied here:** roster-record + one parametric builder; percentile ladder for
weathering; window/door predicates on the wall loop with different moduli per face;
carpet mottled ~38% bare; furniture from primitives; a prop table keyed on building id;
lights last with their own beam; hull depth from shrinking discs; two intersecting
systems (ribs × rings) with material at the intersection; constant-width ribs via
`halfWidthDeg = toDegrees(w / r)`; exempt your own routes from your own decoration pass.

**Still unspent, all cheap, all from the Dressers:**
- **Clockwork** — segmented pipe with deliberate gaps (`phase = floorMod(x+z, 9); if
  (phase >= 6) return;` plus a flange at `phase == 5`). *Partly used on the parapet
  roof; the deck and the machinery ring should get it too.*
- **Hive** — a waver on parametric runs, `round(1.8 * sin(t*PI*2.6 + index))`. Sag the
  cable trays and hose runs or they read CAD-straight.
- **Deep** — stacks that lean, 1 block per 6–8 of height; light bleeding through cracks
  rather than a lamp on top.
- **Neon** — `emitHeatSinks`: a grid where `height = 2 + floorMod(i + j, 3)`. Zero
  noise, jagged machinery silhouette, the cheapest convincing greeble there is.
- **Lego** — a world-aligned modulo lattice (`floorMod(x, PITCH) == 0`) with vetoes near
  curbs, lanes and pads, so bolt heads line up across the whole rig regardless of where
  each module sits.
- **Abyss** — one light on a chain, and darkness that reads as a wall. Resist lighting
  the sea.
- **Archive** — painted walking routes on the deck as wayfinding.
- **Arc-length indexing** — `int i = floor(angle * radius)` keeps trim spacing in
  *blocks*, not degrees. The deck's rings still space wrong at large radii.
- **Sheared coordinates** — `noise2(salt, x + y*3, z - y*3)` turns 2D noise into diagonal
  veins through a 3D volume. Would be excellent as rust on the legs.

---

## 6. Dev loop and landmines

```
cd plugin && ./gradlew.bat test              layout + world + economy tests
scripts\deploy.ps1                           build + validate + copy
scripts\start-server.ps1 -Detached -WaitReady
scripts\rcon.ps1 "still setup depths"        idempotent
scripts\rcon.ps1 "still setup world"         ~7 min, 20.4M blocks
scripts\rcon.ps1 "still verify rig"          THE CHECK. Seconds.
scripts\rcon.ps1 "still setup biomes"        seconds — fog only
```

A geometry change needs a full wipe: `rm -rf server/world/dimensions/thestill
server/plugins/TheStill/thestill.db*`. **`dials.yml` survives wipes** and a stale
override beats the code default silently — that cost a session once, with
`mine.auto-pickup`.

**Verification landmines, all paid for the hard way:**
- `execute if biome` reads the **worldgen biome source**, not chunk storage. It will
  tell you a correct `setBiome` failed. Read it back in-plugin instead.
- `forceload add` refuses **>256 chunks** in one command; every probe then answers "not
  loaded", which looks like a wall of failures.
- The console runs commands in the **overworld** unless you say `execute in
  thestill:depths`.
- **"The axis is air" is not "you can walk it."** Probe the floor and the headroom, and
  keep walking past the end of the thing you built.
- Chains are **climbable**; one hung in the drop shaft broke the opening move.
- `minecraft:chain` is `iron_chain` in 26.x and `Material.CHAIN` is gone from the enum.
- A counter that increments on placement **lies** if something overwrote it.

> *"An assertion that can pass while reality fails is worse than no assertion, because
> it stops anybody looking."*

---

## 7. Tone note

The owner is a builder with strong visual judgement and rejected six rigs, correctly,
every time. When they say something is bad, **ask which one building comes closest**
rather than rebuilding blind — the roster is a data table, so changing one row is
cheap, and rebuilding everything costs seven minutes and their patience.

What is still unjudged after the first client session: the **upper floors** and the
**interiors** — the owner was on the deck. And the rig is "good enough for now", which
is not the same as good.
