# BUILD PROMPT — *Learn to Fly* 1 & 2, rebuilt without Flash

> **Hand this whole file to the build agent.** It is the spec, the tuning table, the art pipeline, the
> test plan and the phase gates. Read it top to bottom before writing a line of code.
>
> **Target:** a pixel-faithful, feel-faithful remake of Light Bringer Games' *Learn to Fly* (2009) and
> *Learn to Fly 2* (2011), in vanilla JS + Canvas2D, no build step, launched by double-clicking one
> `.bat`. It must be **identical enough to A/B against the original SWF side by side**, and
> **structured so the owner can then take it somewhere the original never went.**
>
> **Owner's stated goal, in their words:** *"want game to be like identical so i can continue it and make
> it different :) i've always wanted to make stuff past the wall."* Everything in §14 exists to serve
> that sentence. The wall is not the end of this codebase. It is the end of *their* game.

---

## §0 — The contract with you, the builder

1. **Do not stop at "it runs."** This ships when the acceptance tests in §12 pass and the calibration
   deltas in §11 are inside tolerance. A build that flies but does not *feel* like Learn to Fly has
   failed the only requirement that matters.
2. **Physics before pixels.** `sim/physics.js` must be correct and unit-tested against a headless
   Node runner before a single sprite is drawn. Art is a swap; feel is not.
3. **Every magic number lives in `src/data/tuning.js`.** Not inline. Not "just this one." If you find
   yourself typing a float into a physics file, it belongs in tuning.
4. **Every tunable is expressed in the unit the player sees.** Ramp tiers are stored as *exit speed in
   mph*, not as an acceleration you then integrate over a length you also invented. When the owner
   watches reference footage and says "the second ramp should be about 40," they must be able to open
   one file, find `40`, and change it.
5. **Ship a playable game at every phase gate.** No phase ends with a half-wired system.
6. **Flag uncertainty in code, don't paper over it.** Where this document says CALIBRATE, put
   `// CALIBRATE:` on the line. §11 is the procedure for resolving them. A number nobody has checked
   against the original must be *findable*.
7. **When this document and the original disagree, the original wins.** Change this document, record
   what you changed and why in `CALIBRATION.md`, then change the code.

### What is verified vs. what you must calibrate

This matters. Do not treat the two the same.

| | |
|---|---|
| **VERIFIED** — sourced from the game's documentation, wikis and community guides. Treat as fact. | The six LTF1 upgrades and what each does · controls · the 6,000 ft goal · money from height + distance with a 13 time multiplier · glider speed ceilings 50 / 100 / 225 mph · optimal glide sits at 34% on the resistance gauge · LTF1 ends with the iceberg killing you · LTF2's five obstacles and their order · LTF2's wall is 600 ft tall · LTF2's dual currency · LTF2's 48 medals and their unlock conditions · LTF2's secret shop items |
| **CALIBRATE** — I am specifying a complete, self-consistent, *playable* tuning that produces the right shape of game. The exact original values are not published anywhere scrapable. | Every constant in `tuning.js`: gravity, drag and lift coefficients, upgrade prices, cash rates, rotation speed, fuel burn, ramp tiers, obstacle HP |

Nothing in the CALIBRATE column is a guess you should be shy about. It is a **starting point that already
plays**, plus §11 which tells you how to pull it onto the original. That is a better deliverable than
invented numbers presented as gospel.

---

## §1 — What is being built

One codebase, one launcher, two complete games.

```
index.html    LAUNCHER
                  PLAY LEARN TO FLY          (2009, the original)
                  PLAY LEARN TO FLY 2        (2011, story / classic / arcade)
                  SIDE-BY-SIDE               (remake vs. the real SWF, §11)
                  SANDBOX                    (physics playground, every upgrade unlocked)
```

The launcher is not a placeholder. It is the first thing on screen in the YouTube video, so it gets
real art direction: falling snow, the penguin idling on an ice shelf, the two game logos as carved ice
slabs, a hover sound. Cheap to build, sets the tone in three seconds.

### The elevator description of each game

**Learn to Fly (2009).** A penguin reads that penguins cannot fly. It takes this personally. You launch
it off a ramp into the ocean, over and over. Each run earns money from how far and how high it went;
money buys six upgrades; each day you go further. Reach **6,000 feet** and you win — and then the game
kills you with an iceberg, which is the joke, and which is the entire premise of the sequel.

**Learn to Fly 2 (2011).** Same penguin, now with a grudge against that iceberg. Three modes. Story
mode gives you a **payload** and five things to destroy in order: **snowman — snow mound — rocky hill 
iceberg — the wall**. Classic mode is LTF1's distance chase with the better toy box. Arcade is scored
sub-challenges. Two currencies: cash from flights, **bonus points** from medals, and the good stuff
costs bonus points.

### The single most important design fact about this game

**There is no day limit.** Days are not a resource you run out of — they are your *score*. The community
challenge has always been "beat it in 1618 days," not "beat it before day 30." Do not add a fail
state. A player who takes 90 days has not lost; they have a worse run. LTF2's medals confirm this
directly: `Milestone 4 — play for 150 in-game days`.

If you build a day limit you have built a different, worse game.

---

## §2 — The nine feel rules (non-negotiable)

These are the things that, if you get them wrong, make it "a penguin flying game" instead of "Learn to
Fly." Every one of them is cheap. Every one of them is load-bearing.

1. **The launch is a hard cut to speed.** No easing in. The penguin is stationary on the ramp, you press
   go, and it is *already fast*. The camera snaps to it late, so the first half-second is a lurch.
2. **Left is up, right is down, and rotation is direct.** Holding left rotates the nose up at a constant
   rate. It is not a torque you apply to a rigid body — it is direct angular control with a fixed
   degrees-per-second. This is why the game is readable with two keys.
3. **Speed and height are a currency you trade back and forth.** Nose up: climb, bleed speed. Nose down:
   dive, gain speed. The whole skill ceiling of the game is knowing which one you need right now, and
   the answer is almost always "hold a shallow glide and let the numbers tick."
4. **The optimal line is boring and the player must be able to find it.** The community's own advice is
   *"fly at 3% resistance and the moment your altitude starts to drop, use the jet to hold height."*
   That must be true in your build. It is the acceptance test for the whole lift/drag model.
5. **Rockets are punctuation, not propulsion.** Fuel is small, thrust is big. You save it. Tapping it at
   the top of an arc to hold altitude is the correct play and must feel correct.
6. **Water is a second surface, not a floor.** Hit it shallow and fast and you *skip*, and skipping feels
   great and is worth distance. Hit it steep and you submerge, and that is usually the run ending.
7. **The run ends by exhaustion, not by a timer.** You come to rest. There is a beat of stillness before
   the results panel slides in. Do not cut to the shop.
8. **The results panel is the dopamine.** Numbers count up. Distance, max altitude, max speed, air time,
   the multiplier, then the cash total, in that order, each with a tick sound. This screen is why people
   pressed "again" for six hours in 2009.
9. **The shop is a wall of chunky buttons with visible next-tier stats.** You can always see what the
   next upgrade does and what it costs. No submenus. No tooltips-only. One screen.

---

## §3 — Tech and file layout

**Stack:** vanilla JS, ES modules, Canvas2D. **No build step. No npm install to play. No framework.**
Chosen so a change is one file edit and a refresh — which is the entire reason the owner picked it.

**Why not WebGL:** Flash was a 2D vector compositor. Canvas2D is the same shape of tool, and this game
draws maybe 300 things a frame. WebGL buys nothing here and costs the ability to read the render code.

### Layout

```
Learn to Fly/
  Play.bat                    starts a static server on :5500 and opens the browser
  index.html                  the launcher
  CLAUDE.md                   the map (write it LAST, from what you actually built)
  CALIBRATION.md              the running log of §11 — what was measured, what changed
  src/
    main.js                   boot, mode select, wires the loop
    core/
      loop.js                 fixed-timestep accumulator (§4.1) — the most important 40 lines
      input.js                keyboard, held-state, rebindable
      rng.js                  seeded PRNG (mulberry32) — determinism, §12
      save.js                 localStorage, versioned, migration-aware
      audio.js                WebAudio, unlocked on first gesture
      events.js               tiny pub/sub so medals can listen without physics knowing they exist
    sim/                       PURE. No DOM. No canvas. No imports outside sim/ and data/.
      physics.js              forces, integration, the flight model
      launch.js               the ramp
      water.js                skip, dive, resurface
      collision.js            ground, obstacles
      obstacles.js            LTF2 destructibles
      economy.js              cash, multipliers, prices
    game/
      state.js                the state machine (§5)
      run.js                  one flight, start to rest
      day.js                  the day loop
      shop.js
      medals.js
      challenges.js
      modes/
        ltf1.js
        ltf2-story.js
        ltf2-classic.js
        ltf2-arcade.js
    render/
      camera.js               follow, lookahead, zoom-out for the 600 ft wall
      render.js               the draw order
      parallax.js
      sprites.js             * the Shadow Studio sheet loader (§8)
      placeholder.js         * procedural vector art — ships FIRST, works with zero assets
      hud.js                  speed dial, altitude meter, resistance gauge, fuel
      ui.js                   shop, results, menus
      fx.js                   particles, snow, spray, explosions, screen shake
    data/
      tuning.js              * EVERY magic number in the project
      ltf1.upgrades.js
      ltf2.catalog.js
      ltf2.obstacles.js
      medals.data.js          all 48, verbatim (§7.6)
  assets/
    manifest.json             logical name — sheet + pivot + fps (§8.3)
    sheets/                   Shadow Studio exports land here
    audio/
  reference/
    README.md                 how to run the original next to the remake (§11)
  tests/
    run-tests.mjs             node tests/run-tests.mjs — no test framework, no deps
  tools/
    calibrate.mjs             sweeps tuning constants against recorded targets
```

### Three architectural rules that pay for themselves

**`sim/` imports nothing from `render/`, `game/` or the DOM.** This is what lets `node
tests/run-tests.mjs` run the real flight model with no browser. The moment physics touches `canvas` or
`document`, the test suite dies and the feel becomes unverifiable. Enforce it with a grep in the test
runner: if any file in `sim/` contains `document`, `window`, `canvas` or `requestAnimationFrame`, fail
the suite loudly.

**`placeholder.js` is not throwaway.** It draws every entity as flat vector shapes — the penguin as a
capsule with a beak triangle, the glider as a chevron, the iceberg as a jagged polygon. The game must be
100% playable and shippable with zero files in `assets/sheets/`. Studio art then swaps in per-entity via
the manifest. This is what stops "waiting on art" from ever blocking "tuning the game," and it means a
broken sprite export degrades to a shape instead of to a crash.

**One draw order, declared in one array.** `render.js` owns a literal list of layers, top to bottom.
Adding an effect means adding a line to that list, not hunting for where to insert a `ctx.drawImage`.

---

## §4 — The flight model

This is the game. Everything else is UI around it.

### §4.1 Fixed timestep — do this first, do not skip it

```js
const DT = 1 / 120;           // physics step, seconds
const MAX_STEPS = 8;          // spiral-of-death guard
let acc = 0, prev = performance.now();

function frame(now) {
  acc += Math.min((now - prev) / 1000, 0.25);   // clamp: alt-tab must not teleport the penguin
  prev = now;
  let steps = 0;
  while (acc >= DT && steps++ < MAX_STEPS) { sim.step(DT); acc -= DT; }
  render(acc / DT);            // alpha for interpolation
  requestAnimationFrame(frame);
}
```

**Why 120 Hz and not 60:** the penguin will exceed 100 m/s. At 60 Hz that is 1.7 m of travel per step,
which is enough to tunnel clean through the water surface, through a snowman, and through the 600 ft
wall's collision box. 120 Hz halves that, and §4.9 handles the rest with swept collision.

**Why the 0.25 clamp:** without it, alt-tabbing for thirty seconds delivers a 30-second `dt`, the loop
runs 3,600 steps in one frame, and the browser hangs. With it, a tabbed-out game simply loses time,
which is correct.

**Why interpolation matters here specifically:** at 120 Hz physics and 60 Hz display you are dropping
every other state. On a fast-moving sprite that reads as judder. Render at `alpha` between previous and
current state.

### §4.2 State and units

**Internal units are metres, seconds, radians. Always. Everywhere.** Convert to feet and mph *only* in
`hud.js`, at the moment of drawing. Mixing display units into the sim is the single most reliable way to
make this game impossible to tune, because you can no longer tell whether a wrong number is a physics
bug or a conversion bug.

```js
FT_PER_M  = 3.28084
MPH_PER_MS = 2.23694
```

```js
{
  x, y,            // metres. y is UP. y = 0 is sea level.
  vx, vy,          // m/s
  theta,           // radians, nose direction. 0 = +x (right/level). Positive = nose up.
  omega,           // rad/s, only non-zero while tumbling
  fuel,            // seconds of thrust remaining
  gliderHp,        // 0..1
  state,           // 'ramp' | 'flight' | 'tumble' | 'water' | 'submerged' | 'ground' | 'rest'
  bounces, maxAlt, maxSpeed, airTime, dist
}
```

### §4.3 The forces

Per step, in this order. Order matters for the drag term: drag must see the velocity *before* thrust is
added this step, or a full-throttle rocket produces a drag spike that reads as a stutter.

```
speed  = hypot(vx, vy)
vAngle = atan2(vy, vx)              // direction of travel
alpha  = wrapPi(theta - vAngle)     // ANGLE OF ATTACK — the heart of the model
```

**1 — Gravity.** `ay -= G`

**2 — Drag.** Opposes velocity. Quadratic in speed. Grows with the *square* of angle of attack, which is
what makes a tilted penguin bleed speed.

```
Cd = CD0 + K_INDUCED * alpha²                 // CD0 shrinks with the Air Resistance upgrade
aDrag = Cd * speed²
a -= aDrag * unit(v)
```

**3 — Lift.** Perpendicular to velocity, to the left of the travel direction (i.e. `vAngle + /2`).

```
Cl = CL_MAX * sin(2 * alpha)
aLift = GLIDER.lift * Cl * speed²
a += aLift * perp(unit(v))
```

> **Use `sin(2α)`, not a linear `α`.** It costs nothing and it gives you stall for free: lift peaks at
> α = 45°, falls to zero at 90°, and goes *negative* past that. A penguin pitched straight up loses lift
> and drops, exactly as it should, with no special-case stall code, no `if` ladder, and no possibility
> of NaN. A linear lift curve lets a player pitch to 89° and generate infinite climb, which is both
> wrong and the first exploit anyone finds.

**4 — Thrust.** Along the nose, while Space is held and fuel remains.

```
if (thrusting && fuel > 0) {
  a += ROCKET.thrust * (cos(theta), sin(theta))
  fuel -= dt
}
```

**5 — Integrate.** Semi-implicit Euler. Velocity first, then position from the *new* velocity. It is
stable under the drag term in a way explicit Euler is not, and it is four lines.

```
vx += ax*dt;  vy += ay*dt
x  += vx*dt;  y  += vy*dt
```

### §4.4 Rotation — direct, not torque

```
if (LEFT)  theta += ROT_RATE * dt
if (RIGHT) theta -= ROT_RATE * dt
theta = clamp(theta, -PI/2, PI/2)     // in flight. Tumbling ignores this and free-spins.
```

`ROT_RATE` is the most feel-critical single number in the game. Too slow and the game is unresponsive;
too fast and you cannot hold a 3° glide, which §2.4 says is the whole skill. Start at **70°/s**.
CALIBRATE against footage: time how long the original takes to go from full nose-up to full nose-down.

### §4.5 The three gauges, and what they actually read

LTF1's HUD is three dials plus a fuel bar. Two are obvious. The third is not, and it is the one the
community's strategy advice is phrased in.

| Gauge | Reads | Notes |
|---|---|---|
| **Speed dial** | `speed * MPH_PER_MS` | Needle sweeps a ~240° arc. Max of the arc is the current glider's speed ceiling — so the dial *rescales as you upgrade*, and "half way up the dial" stays meaningful advice at every tier. This is why the community says "keep speed about halfway up your dial" rather than naming an mph. |
| **Altitude meter** | `y * FT_PER_M` | A vertical bar plus a number. Also drives the money. |
| **Resistance %** | see below | **The tuning target.** |

**The resistance gauge.** Verified community strategy: *"aim around 4% air resistance"* and *"fly at 3%
resistance"* is the optimal glide. So whatever this gauge reads, **optimal glide must read 34% on it**.
That is your acceptance test; the formula is yours to pick. Two candidates:

```js
// (a) total drag deceleration as a fraction of gravity
resistancePct = 100 * DRAG_DISPLAY_SCALE * (Cd * speed²) / G

// (b) induced drag only — "how much am I fighting the air by being tilted"   <-- recommended
resistancePct = 100 * DRAG_DISPLAY_SCALE * (K_INDUCED * alpha² * speed²) / G
```

Recommend **(b)**: it reads ~0 in a clean glide and climbs fast when you over-pitch, which makes it an
*actionable* instrument rather than a speedometer in disguise — and that is what the strategy advice
implies.

**And (b) very nearly lands on the verified target with §16's constants untouched**, which is worth
checking by hand before you trust anything else in this document:

```
best-glide alpha            0.30 rad          (maximises sin(2a)/(CD0 + K*a^2))
level-flight speed there    lift = G:  0.115 * sin(0.6) * v^2 = 22   ->   v = 18.4 m/s
induced drag accel          K * a^2 * v^2 = 0.020 * 0.09 * 339 = 0.61 m/s^2
reads                       100 * 0.61 / 22 = 2.8%      with DRAG_DISPLAY_SCALE = 1.0
                            100 * 0.61 / 22 * 1.25 = 3.5%   set the scale to 1.25
```

That the drag model produces ~3% at best glide *before* any fudge factor is decent evidence the
lift/drag shape is right, not merely tuned to hit a number. Set `DRAG_DISPLAY_SCALE = 1.25`, confirm
the reading, and record it in `CALIBRATION.md`.

### §4.6 Gliders, and the fact that they break

Verified: glider 1 tops out around **50 mph**, glider 2 around **100 mph**, glider 3 around **225 mph**.

Implement as **structural integrity**, not a hard speed clamp:

```
if (speed*MPH_PER_MS > GLIDER.vMax) {
  gliderHp -= (over/GLIDER.vMax) * GLIDER_STRESS_RATE * dt
  // creaking sound, wings visibly flutter, HUD dial goes red
}
if (gliderHp <= 0) -> snap: lift = 0, state = 'tumble', debris particles, hard sound
```

> A hard clamp is the wrong choice and it is worth knowing why. Clamping makes an early glider feel
> *unbreakable*, which removes the tension that makes buying glider 2 a relief. Breaking makes speed a
> risk you manage, which is a better game and is what the sequel definitively does. **CALIBRATE
> whether LTF1 itself breaks gliders or merely caps them** — the wikis are ambiguous. Ship breaking,
> put it behind `TUNING.gliderBreaks`, and flip it if the reference footage says otherwise.

### §4.7 The ramp

Not simulated as a slope. Parameterised directly by what the player sees:

```
launch:
  y0     = RAMP_TIER[i].heightM       // start altitude
  speed0 = ACCEL_TIER[j].exitMph / MPH_PER_MS
  theta0 = RAMP_ANGLE                 // ~20° nose-up
```

There is a short scripted slide down the ramp (~0.6 s) for the animation and the sound, but the exit
speed is *set*, not integrated. This is rule §0.4 in action: the owner tunes "how fast does tier 3
launch me" by editing the number 54, not by solving for an acceleration.

### §4.8 Water — skip, dive, resurface

The penguin launches over ocean. Water is a surface with two behaviours, and getting this right is worth
a surprising amount of the game's charm.

```
on crossing y = 0 downward, with impact angle phi = |vAngle| below horizontal:

  SKIP    if phi < SKIP_ANGLE_MAX (~18°) and speed > SKIP_MIN_SPEED (~12 m/s)
           vy = -vy * SKIP_RESTITUTION (~0.55)
           speed *= SKIP_SPEED_LOSS (~0.88)
           bounces++, spray burst, "plink" pitched up per consecutive bounce
           each successive skip is shallower, so a good entry chains 5-10 of them

  DIVE    otherwise
           state = 'submerged'
           underwater: drag 12, buoyancy +1.6G upward, no lift, no thrust
           track depth for the Diving medals
           resurface, then either skip weakly or come to rest
```

**Chained skipping must be genuinely rewarding.** LTF2 gives it two medals (`Master Bouncer`, 100
bounces) and it is the most-shared clip of the game. Make the spray and the ascending pitch sell it.

### §4.9 Collision and the tunnelling problem

At 100 m/s and a 120 Hz step the penguin moves 0.83 m per step. A snowman is about 2 m wide. Point
sampling will miss it some of the time, which produces the worst possible bug class: an obstacle that
*usually* works.

**Sweep every step.** Treat the penguin as a segment from `prev` to `current` and test that segment
against every candidate AABB, not the endpoint. It is ten lines and it removes an entire category of
"sometimes it just flies through the wall" reports.

Same for the water surface: test the *crossing* of `y = 0`, not `y < 0`.

### §4.10 The run ends

`state = 'rest'` when the penguin is on ground or water **and** `speed < REST_SPEED (1.5 m/s)` for
`REST_TIME (0.4 s)`. The delay matters — without it a penguin that momentarily stalls at the top of a
bounce ends the run in mid-air.

Then: **hold for 0.9 seconds** with the camera settling before the results panel slides in (§2.7).

---

## §5 — State machine

One machine, in `game/state.js`, and every screen is a state. No screen owns the loop.

```
BOOT — LAUNCHER
LAUNCHER — INTRO(ltf1) | INTRO(ltf2) | SIDEBYSIDE | SANDBOX

INTRO      cutscene, skippable with any key
   DAY_START
DAY_START — "Day 7" card, 1.2s
   PRE_LAUNCH
PRE_LAUNCH penguin on the ramp, camera wide, "press SPACE to launch"
   LAUNCH
LAUNCH     scripted ramp slide, ~0.6s
   FLIGHT    the sim runs here
FLIGHT
   RESULTS — (on rest)
   VICTORY — (LTF1: crossed 6000 ft | LTF2: last obstacle destroyed)
RESULTS    counting-up panel
   SHOP
SHOP
   DAY_START (next day)
VICTORY
   ENDING   (LTF1: the iceberg gag | LTF2: the wall falls)
ENDING
   POST      §14. The seam. LTF1 default returns to LAUNCHER; the owner's content goes here.
```

**`PRE_LAUNCH` is not a formality.** It is where the player reads their new upgrades on the penguin and
gets the beat of anticipation that makes launch land. Camera pulled back, ramp fully in frame, wind
sound. One second of stillness before the game's loudest moment.

---

## §6 — LEARN TO FLY (2009) — full spec

### §6.1 Story and framing

The penguin reads **Kiwipedia** (the joke is right there in the name — keep it) which states that
penguins are flightless birds. It is insulted. It builds a ramp.

- **Intro:** three or four still frames with typed captions. The book. The penguin's reaction. The ramp
  under construction. Total under 12 seconds, skippable.
- **Between days:** short flavour captions on the day card. *"Day 12. The neighbours have stopped
  asking."* Write 20 of them; pick by day number so they are stable across reloads.
- **The goal:** 6,000 feet. Shown as a progress bar on the shop screen with your record marked.

### §6.2 The six upgrades — VERIFIED, this list is exact

| # | Name | What it does | Touches |
|---|---|---|---|
| 1 | **Ramp Height** | Start higher up | `y0` |
| 2 | **Acceleration** | Slipperier ramp — higher exit speed | `speed0` |
| 3 | **Air Resistance** | Reduces the speed penalty from drag — keep speed longer | `CD0` |
| 4 | **Glider** | Reduces how much you drop while flying; raises the speed ceiling | `GLIDER.lift`, `GLIDER.vMax` |
| 5 | **Rockets** | A boost when you need it | `ROCKET.thrust` |
| 6 | **Rocket Fuel** | Keeps them burning longer | `fuel` capacity |

**Five tiers each.** Prices rise steeply; the last tier of each is a genuine decision, not a formality.

```js
// data/ltf1.upgrades.js — ALL VALUES CALIBRATE
export const UPGRADES = {
  rampHeight:    { name: "Ramp Height",    unit: "ft",  tiers: [
    { heightM:  3, price:     0 },  { heightM:  8, price:   350 },
    { heightM: 15, price:  1200 },  { heightM: 24, price:  4000 },
    { heightM: 36, price: 12000 } ] },

  acceleration:  { name: "Acceleration",   unit: "mph", tiers: [
    { exitMph: 27, price:     0 },  { exitMph: 40, price:   300 },
    { exitMph: 54, price:  1100 },  { exitMph: 69, price:  3800 },
    { exitMph: 89, price: 11000 } ] },

  airResistance: { name: "Air Resistance", unit: "%",   tiers: [
    { cd0Mul: 1.00, price:    0 },  { cd0Mul: 0.86, price:   400 },
    { cd0Mul: 0.72, price: 1500 },  { cd0Mul: 0.58, price:  5000 },
    { cd0Mul: 0.45, price:14000 } ] },

  glider:        { name: "Glider",         unit: "",    tiers: [
    { label: "None",          lift: 0.030, vMaxMph:  35, price:     0 },
    { label: "Cardboard",     lift: 0.062, vMaxMph:  50, price:   500 },  // VERIFIED ~50
    { label: "Hang Glider",   lift: 0.088, vMaxMph: 100, price:  2500 },  // VERIFIED ~100
    { label: "Delta Wing",    lift: 0.115, vMaxMph: 225, price:  9000 },  // VERIFIED ~225
    { label: "Prototype",     lift: 0.140, vMaxMph: 400, price: 26000 } ] },

  rockets:       { name: "Rockets",        unit: "",    tiers: [
    { thrust:  0, price:    0 },  { thrust: 14, price:   800 },
    { thrust: 22, price: 3000 },  { thrust: 32, price: 10000 },
    { thrust: 46, price:28000 } ] },

  rocketFuel:    { name: "Rocket Fuel",    unit: "s",   tiers: [
    { seconds: 0.0, price:    0 },  { seconds: 2.5, price:   600 },
    { seconds: 4.5, price: 2200 },  { seconds: 7.5, price:  7500 },
    { seconds:12.0, price:21000 } ] },
};
```

> **The tier-1 glider must be reachable on day 23.** Verified community advice is *"buy acceleration
> first, then a glider as soon as possible."* If your prices make the first glider a day-6 purchase, the
> early game is five days of face-planting and the player quits. **This is a calibration gate, not a
> preference.** Test it: play days 13 with no knowledge and confirm a glider is affordable.

### §6.3 Economy — VERIFIED shape, CALIBRATE rates

```js
// sim/economy.js — pure, unit-tested
const distFt = dist * FT_PER_M;
const altFt  = maxAlt * FT_PER_M;

const base = DIST_RATE * distFt + ALT_RATE * altFt;      // CALIBRATE: 0.55 and 1.20
const mult = clamp(1 + airTime / TIME_MULT_SCALE, 1, 3); // VERIFIED range 1..3. CALIBRATE scale ~22
const cash = Math.round(base * mult);
```

**The invariant that must hold, and must be tested (§12):** *strictly better upgrades can never produce
a strictly worse expected payout.* If buying a better glider can lower your income, the game is broken
in a way players feel as "the upgrade was a trap" long before they can articulate it. Assert it in the
test suite by sweeping upgrade combinations through a scripted autopilot.

### §6.4 HUD layout

Top-left: **day counter**, **cash**. Top-right: **best distance** with a small flag marker.
Bottom-left: **speed dial** (round, needle, rescales per glider — §4.5). Bottom-centre: **fuel bar**,
segmented, one segment per second, drains right to left. Bottom-right: **altitude meter** (vertical bar
+ number) and the **resistance %** readout directly under it, because the player reads them together.

Centre-bottom, only while relevant: **distance so far**, large, semi-transparent.

### §6.5 The ending — do not soften it

Reaching 6,000 ft triggers `VICTORY`. The penguin celebrates. Then the camera pans and it flies
directly into an iceberg and dies.

**This is the correct ending and it must be played straight.** It is a verified fact about the original
(*"the iceberg that was your downfall in the first game"*), reviewers at the time called it harsh, and it
is the entire setup for the sequel and for §14. Do not add a "you win!" screen. Do not soften it. The
smash cut to black, then the stats card, then a single line: **"Penguins can't fly."** Then the LTF2
teaser unlocks on the launcher.

---

## §7 — LEARN TO FLY 2 (2011) — full spec

Roughly three times the systems. Build it *on* LTF1's sim — the flight model does not change, the
content around it does.

### §7.1 Story and framing

The penguin learned to fly. Then an iceberg killed it. **LTF2 is a revenge story**, and the wiki says so
in as many words: *"your objective this time is to get rid of the wall of ice that you crushed in the
previous game."*

- **The intro must call back to LTF1's ending directly** — replay the iceberg smash-cut, then the
  penguin waking up, then the blueprints. If the player finished LTF1 in this save
  (`ltf1.completed === true`), use their real final stats in the recap card. That one conditional is
  the strongest possible argument that these two games share a codebase, and it costs almost nothing.
- **Tone escalates from "stubborn bird" to "military-industrial penguin."** LTF1 is a ramp made of
  scrap. LTF2 is sleds, thrusters, payloads and a crash-test dummy. The shop art should carry that
  shift — LTF1's shop is a shed, LTF2's is a hangar.
- **The dummy is the joke.** The penguin does not ride the bomb. It watches from a safe distance with a
  clipboard. Show it standing at the launch site in `PRE_LAUNCH`.
- **The five obstacles are a difficulty curve told as a story** — a snowman, then something bigger,
  then something bigger, then the thing that killed you, then something that makes *that* look small.
  Keep them visible in order on the map/progress strip so the wall is looming from day one.

### §7.2 Three modes

| Mode | Goal | Ends when |
|---|---|---|
| **Story** | Destroy five obstacles, in order | The wall falls |
| **Classic** | Distance chase, LTF1-style, better gear | Player-set distance target reached |
| **Arcade** | Scored sub-challenges | Score accumulates; `Arcade King` = 12,500 total points across all arcade modes (VERIFIED) |

**Story mode replaces the penguin with a dummy** (VERIFIED) — because the payload destroys whatever is
carrying it, and the joke is that the penguin has learned enough to not be in the vehicle. Keep it. It
also cleanly justifies the bomb one-shotting your own rig.

### §7.3 The five obstacles — VERIFIED order, CALIBRATE numbers

```js
// data/ltf2.obstacles.js
[
  { id:'snowman',   name:'Snowman',    distFt:   180, heightFt:  12, hp:   400, needs:'impact' },
  { id:'mound',     name:'Snow Mound', distFt:   650, heightFt:  30, hp:  1800, needs:'impact' },
  { id:'hill',      name:'Rocky Hill', distFt:  1600, heightFt:  90, hp:  7000, needs:'impact' },
  { id:'iceberg',   name:'Iceberg',    distFt:  3400, heightFt: 260, hp: 26000, needs:'impact|bomb' },
  { id:'wall',      name:'The Wall',   distFt:  6000, heightFt: 600, hp:120000, needs:'bomb' },
]
```

`heightFt: 600` for the wall is **VERIFIED** — the medal `I.C.B.P.` is awarded for flying *over* the
600-foot wall, which is both a hard number and a confirmation that going over it must be possible. Build
the collision so it genuinely is.

**Damage model:** `damage = 0.5 * mass * speed² * PAYLOAD.penetration`, i.e. kinetic energy scaled by
what you are carrying. A bomb adds a flat `PAYLOAD.blast` on contact and destroys your own rig.

Obstacles show a **health bar and cracks that deepen** across attempts, and **damage persists between
days** — the run-to-run progress of chipping the iceberg down is the story mode's whole arc.

### §7.4 Gear catalogue

Four categories (VERIFIED): **Sleds**, **Gliders**, **Thrusters/Boosters**, **Payloads**.

- **Sleds** — launch speed and ground handling. Maps to LTF1's Acceleration.
- **Gliders** — lift and speed ceiling. Same `GLIDER` block.
- **Thrusters** — thrust and burn rate.
- **Payloads** — mass and penetration. **Bombs are one-use and destroy your rig on detonation**
  (VERIFIED) — so you carry one specifically to hit the wall and you accept ending the run there.

**Omega tier** (VERIFIED): the strongest booster, glider and sled in the game, unlocked through the
**Omega book** with **bonus points**, not cash. This is the long-tail goal.

### §7.5 Two currencies — VERIFIED

| | Earned from | Spent on |
|---|---|---|
| **Cash ($)** | Flights, challenges | Gear in the normal shop |
| **Bonus Points (BP)** | **Medals** and secrets | Attribute upgrades, Omega items, the secret shop |

And the distinction that makes it work, verbatim from the wiki: *"Unlike Challenges, [medals] stay
unlocked through all played games and reward Bonus Points instead of money."* **Medals are account-level
and persist across new games. Challenges are per-playthrough and pay cash.** Two save scopes — get this
right in `save.js` from the start, because retrofitting a persistence boundary is miserable.

### §7.6 The medals — all 48, VERIFIED, put them in `data/medals.data.js` verbatim

| # | Medal | Condition |
|---|---|---|
| 1 | Story Mode | Complete Story Mode |
| 2 | Classic Mode | Complete Classic Mode |
| 3 | Arcade King | 12,500 total points across all arcade modes |
| 4 | Pacifist 1 | 10 consecutive in-game days without damaging anything |
| 5 | Pacifist 2 | 25 consecutive in-game days without damaging anything |
| 6 | Skull Crusher | Crash into the ground with great force |
| 7 | Planet Crusher | Crash with extreme force (triggers a cutscene) |
| 8 | Reverse 1 | Fly backwards 250 total feet |
| 9 | Reverse 2 | Fly backwards 15,000 total feet |
| 10 | Diving 1 | Submerge 100 total feet |
| 11 | Diving 2 | Submerge 1,000 total feet |
| 12 | Bounty Hunter 1 | Earn 1,000 bonus points total |
| 13 | Bounty Hunter 2 | Earn 5,000 bonus points total |
| 14 | Give an inch... | Click your money in the shop |
| 15 | He'll take a yard | Click your money in the shop again |
| 16 | The Brick | Buy the secret glider from the catalogue |
| 17 | Revenge of the Brick | Destroy an obstacle using the Brick |
| 18 | It's Rob! | Buy the booster in the bonus shop; get resupplied with fuel mid-flight |
| 19 | Rise! | Buy the phoenix feather; get launched back up after landing |
| 20 | The Easy Way | Click the penguin's beak on the title screen |
| 21 | Achievement! | Click the achievement icon |
| 22 | High Flyer 1 | Reach 250 ft altitude |
| 23 | High Flyer 2 | Reach 1,000 ft altitude |
| 24 | Speedster 1 | Reach 100 mph |
| 25 | Speedster 2 | Reach 500 mph |
| 26 | Master Bouncer | Bounce 100 total times on water |
| 27 | C-C-C-Combo | Destroy all 5 obstacles in one run |
| 28 | Achiever 1 | Complete all achievements in one Story Mode game |
| 29 | Achiever 2 | Complete all achievements in one Classic Mode game |
| 30 | Rich Bird 1 | Accumulate $25,000 |
| 31 | Rich Bird 2 | Accumulate $100,000 |
| 32 | Duration 1 | Fly 60 total seconds |
| 33 | Duration 2 | Fly 5 total minutes |
| 34 | Duration 3 | Fly 25 total minutes |
| 35 | Duration 4 | Fly 1 total hour |
| 36 | Destruction 1 | Destroy 1 obstacle |
| 37 | Destruction 2 | Destroy each obstacle type at least once |
| 38 | Destruction 3 | Destroy 25 total obstacles |
| 39 | Destruction 4 | Destroy 75 total obstacles |
| 40 | I.C.B.P. | Fly over the 600-foot wall |
| 41 | Milestone 1 | Play 10 in-game days |
| 42 | Milestone 2 | Play 25 in-game days |
| 43 | Milestone 3 | Play 75 in-game days |
| 44 | Milestone 4 | Play 150 in-game days |
| 45 | Traveler 1 | Fly 500 total feet |
| 46 | Traveler 2 | Fly 5,000 total feet |
| 47 | Traveler 3 | Fly 50,000 total feet |
| 48 | Traveler 4 | Fly 150,000 total feet |

**Implement medals as pure listeners over the event bus.** `medals.js` subscribes to
`run:bounce`, `run:altitude`, `obstacle:destroyed`, `ui:clicked` and so on. **Nothing in `sim/` may
import `medals.js`.** Otherwise the physics gains a dependency on the achievement system and the
headless tests stop running.

Note how many of these medals are *cumulative across all runs forever* (Traveler, Duration, Destruction,
Diving, Reverse). Your save format needs a `lifetime` counters block from day one. Retrofitting it means
every existing player's totals start at zero.

### §7.7 The secret shop — VERIFIED, and it is the best part

A shady penguin sells things that should not exist. Keep the tone: they are *bits*, and they are why
people replay.

| Item | Effect | Medal |
|---|---|---|
| **The Brick** | A "glider" with no lift and enormous mass. Terrible. Destroys obstacles beautifully. | The Brick / Revenge of the Brick |
| **Phoenix Feather** | On landing, you are relaunched into the air by Zeus | Rise! |
| **R.O.B. 64** | Appears at high altitude and refuels you mid-flight | It's Rob! |

And two joke medals that are pure interaction easter eggs and must be wired even though they touch no
gameplay: **click your money in the shop, twice** (`Give an inch...`, `He'll take a yard`), and **click
the penguin's beak on the title screen** (`The Easy Way`). These cost twenty minutes and they are
exactly the kind of detail that makes a remake read as loving rather than mechanical.

---

## §8 — Art direction, and the Shadow Studio pipeline

The owner has **Shadow** (`D:\projects\Shadow`), whose **Studio** surface is an AI asset
pipeline built specifically for HTML game developers. Use it. This section specifies exactly how,
including the file contract, so `sprites.js` can be written *before* any art exists.

### §8.1 What Shadow Studio can and cannot do — read this before planning any art

Verified by reading the source on 2026-08-06, not the docs. The docs describe an intended pipeline;
this is the one that runs.

**Use the Sprite Forge tab (marked ◫) in Studio.** Not the FastAPI backend, not `SHADOW_DESIGNER/`,
not `frontend/`. Those last two were last touched in April and neither drives the current pipeline —
`SHADOW_DESIGNER` never called the backend at all and carries its own third copy of a chroma keyer.

```
  [WORKS]   text prompt  ->  image        ComfyUI + Flux. Local, free, ~20 s.
                              |
  [EXPENSIVE]  image -> frames          MiniMax H3, local. 25 MIN TO 4 HOURS
                              |          per clip, GPU held throughout.
                              |          Hero animations only. See the box below.
                              v
  [WORKS]   Sprite Forge  accepts:  a video file | a single image | A FOLDER OF FRAMES
                              |
                              v
            mask:   corridor  automatic, no key colour. Needs 3+ frames AND a
                              subject that MOVES - a still subject IS the background.
                    chroma    any flat backdrop. One frame is fine.
                              |
                              v
            despill -> soft alpha -> align -> pack
                              |
                              v
            sheet .png  +  metadata .json  +  a ready-to-paste .js loader
```

> ### ⚠ Generated animation exists, and it costs 25 minutes to 4 hours a clip
>
> **Sprite Forge → "Animate a still (MiniMax H3)"** turns one image into a folder of frames. It is
> real and it works, ported from the owner's own H3 setup and validated node-by-node against the live
> ComfyUI. But H3 is ~42.5 GB of weights streaming against 12 GB of VRAM: **measured at 24 minutes
> on an idle machine and 4 hours 10 minutes on a busy one**, GPU pinned throughout, machine unusable
> for games or streaming while it runs.
>
> It also has a 4-second minimum at 24 fps — **96+ frames to get the 8–16 a sprite loop uses.**
>
> So the decision, and it is already made for this project:
>
> - **The penguin is drawn in code.** Not a stopgap — `placeholder.js` (§3) IS the penguin's real
>   art. This game's whole look is flat shapes with thick outlines, so procedural vector art is
>   *closer to the 2009 original* than a diffusion model's output, it animates perfectly because you
>   control every frame, and it costs seconds instead of hours. **The owner has confirmed this
>   choice.** Do not spend a day of GPU rediscovering it.
> - **Static sprites come from Studio** — all 4 gliders, all 5 ramps, the iceberg, the wall, every
>   obstacle, clouds, the Kiwipedia book, the brick, debris. One image each from ComfyUI, then Sprite
>   Forge in **chroma** mode against flat magenta. One frame in, one clean frame out. That is
>   **roughly half the asset list in §8.5** and it is fast.
> - **Reserve H3 for at most one or two hero shots** — the intro cutscene, or the iceberg impact.
>   Budget half an hour each and run them when the machine is otherwise idle.
> - Sprite Forge also takes **any folder of frames sorted by filename**, so hand-drawn frames or a
>   ComfyUI batch work identically. Frame-to-frame coherence is your problem, not the forge's.

Also present and **not needed here**: `backend/app/pixelart.py`, and the **Asset Forge** and
**Character Forge**, which are 3D.

### §8.2 The exact file contract — read this before writing `sprites.js`

> **This section describes Studio's `version: 2` sprite contract**, which landed on 2026-08-06
> specifically so this project could rely on it. If you are reading a Studio export whose metadata has
> no `"version"` key, it predates that work — see §8.3.

From `Shadow/backend/app/main.py` and `Shadow/backend/app/spritekit.py`, the export is:

**1. The sheet** — one PNG, RGBA, **frames laid out in a single horizontal row**:

```
sheet_width  = frame_width * frame_count
sheet_height = frame_height
frame i occupies — (i * frame_width, 0, frame_width, frame_height)
```

**2. The sidecar JSON:**

```json
{
  "version": 2,
  "frame_width": 128,
  "frame_height": 128,
  "frame_count": 12,
  "fps": 24,
  "source_fps": 24.0,
  "pivot_x": 64,
  "pivot_y": 128,
  "offset_x": -64,
  "offset_y": -128,
  "masking_mode": "corridor_key",
  "alignment_mode": "union",
  "source_indices": [0, 2, 4, 6],
  "boxes": [[12, 30, 110, 128]],
  "dropped_frames": []
}
```

- **`pivot_x` / `pivot_y`** — the subject's anchor within a frame: **bottom-centre, its ground line.**
  This is measured from the actual subject, not assumed.
- **`offset_x` / `offset_y`** — simply `-pivot`. Draw at `(x + offset_x, y + offset_y)` and the pivot
  lands exactly on `(x, y)`.
- **`boxes`** — the tight content box per frame, in frame-local pixels. Free tight collision bounds.
- **`dropped_frames`** — frames the export could not use, each with a reason. **Read this.** A
  non-empty list means your animation is shorter than you asked for.

**3. Optionally** a ZIP of per-frame PNGs named `frame_000.png`, `frame_001.png`, 
**4.** A generated JS snippet carrying the real fps and pivot, with per-instance playback state.

### §8.3 What used to be wrong here, and why you still keep your own pivot

Four traps lived in this contract until 2026-08-06. They are **fixed in Studio now**, but they are
recorded because you may still open an older export, and because the last one is still your job.

| Was | Now |
|---|---|
| Each frame cropped to its own bbox and re-centred, which **deleted an animation's vertical motion** and drifted the pivot whenever the subject's size changed | `alignment_mode: "union"` is the default — one shared crop rect, all relative motion preserved |
| `offset_y: -12` — a placeholder copied out of the mock pipeline, carried everywhere as if measured | `offset_x` / `offset_y` are `-pivot`, computed from the subject |
| 12 fps hardcoded into the snippet, with no field anywhere to change it | `fps` in the metadata, defaulting to the clip's real rate |
| A hard binary mask — no partial alpha anywhere, so nothing could anti-alias, and the key colour stayed in every edge pixel as a coloured fringe | Soft alpha ramp keyed in chromaticity, plus despill |

**Still your job: keep a `pivot` override in `assets/manifest.json`.** Studio's pivot is the subject's
ground line, which is right for anything that stands on terrain — and wrong for the penguin, which
rotates about its *body centre* in flight. One field, and you are never fighting the exporter:

```json
"penguin_glide": { "pivot": [64, 70], "pivotSource": "override  body centre, not ground line" }
```

Default to Studio's `pivot_x`/`pivot_y` when the manifest does not override. Add a debug key (`P`)
that draws the pivot as a crosshair so it can be eyeballed and nudged in seconds — a wrong pivot is
invisible in a still and obvious in motion, which is the worst combination to debug by guesswork.

**If `"version"` is absent from the metadata, you are reading a pre-2026-08-06 export.** Do not trust
`offset_y`, treat `fps` as 12 regardless of what the animation wants, and expect the vertical motion
to have been flattened out of it. Re-export rather than compensating.

### §8.4 `assets/manifest.json` — the indirection layer

This file is what makes art swappable, and it is why the game ships playable with zero art.

```json
{
  "version": 1,
  "sheets": {
    "penguin_glide": {
      "png": "sheets/penguin_glide.png",
      "meta": "sheets/penguin_glide.json",
      "fps": 24,
      "loop": true,
      "pivot": [64, 70],
      "scale": 0.045,
      "placeholder": "penguinCapsule"
    }
  }
}
```

- `pivot` — pixels from the sheet frame's top-left. **Authoritative** (§8.3).
- `scale` — metres per pixel. Keeps the sim in metres and the art in pixels, and means re-exporting at
  a different resolution is a one-number fix.
- `placeholder` — the `placeholder.js` function to draw if the PNG is missing or fails to load.

**`sprites.js` must never throw on a missing sheet.** Missing or broken — log once, draw the
placeholder, keep playing. A failed art export must never be a crash.

### §8.5 The asset list, with the prompt to type into Studio for each

**Read §8.1 first.** There is no video generation, so the "Frames" column below is a *target*, not a
route — how you reach it depends on the row:

| Rows with **1 frame** | One image from ComfyUI → Sprite Forge, **chroma** mode. Works today. |
|---|---|
| **Multi-frame rows** | Either a folder of N generated stills (coherence is on you), or — for the penguin — **draw it in `placeholder.js` instead** and skip Studio entirely. §8.1 explains why that is the better answer here, not the lazier one. |

**Every prompt must specify a flat, saturated background colour that appears nowhere in the
subject** — that is what makes the chroma key clean, and green is a bad choice against snow-lit
blue-white art. **Use magenta `#FF00FF`.** (Corridor mode needs no key colour, but it also needs 3+
frames and real subject motion, so it is the wrong tool for a single generated still.)

#### Shared style block — prepend to every prompt

```
2009 flash game art style, thick black outlines, flat cel shading, limited palette,
bold simple shapes, side view, orthographic, no perspective, no background scenery,
solid flat magenta #FF00FF background, subject fully in frame with generous margin
```

> The "fully in frame with generous margin" clause is not optional. `asset-forge.md` records that
> diffusion models largely ignore "entire object visible" but do respond to distance and framing
> vocabulary — and a cropped subject exports as an amputated sprite.

#### LTF1 assets

| Slug | Frames | fps | Loop | Prompt (after the style block) |
|---|---|---|---|---|
| `penguin_idle` | 8 | 8 | — | a small cartoon emperor penguin standing, blinking, shifting its weight, determined expression |
| `penguin_ramp` | 6 | 18 | — | a cartoon penguin crouched low on a sled, bracing, wind pushing its feathers back |
| `penguin_glide` | 12 | 24 | — | a cartoon penguin gliding horizontally, flippers spread stiff like wings, body streamlined |
| `penguin_pitchup` | 6 | 24 | — | a cartoon penguin angling its body nose-upward, flippers flared to catch air |
| `penguin_pitchdown` | 6 | 24 | — | a cartoon penguin tucked into a steep nose-down dive, flippers swept back |
| `penguin_tumble` | 10 | 20 | — | a cartoon penguin tumbling out of control end over end, flippers flailing, panicked |
| `penguin_splash` | 8 | 20 | — | a cartoon penguin hitting water, white spray erupting around it |
| `penguin_cheer` | 10 | 14 | — | a cartoon penguin celebrating, flippers thrown up in triumph, tiny confetti |
| `penguin_dead` | 4 | 6 | — | a flattened cartoon penguin lying dazed, spiral eyes, comedic |
| `glider_1` | 1 | — | — | a crude cardboard hang glider wing, brown corrugated card, gaffer tape, side view |
| `glider_2` | 1 | — | — | a red and white fabric hang glider wing with aluminium tube frame, side view |
| `glider_3` | 1 | — | — | a sleek white delta wing glider, sharp swept geometry, side view |
| `glider_4` | 1 | — | — | an experimental prototype wing, carbon black with glowing cyan trim, side view |
| `glider_break` | 6 | 24 | — | a glider wing snapping in half, splinters and fabric shreds flying outward |
| `rocket_flame` | 6 | 30 | — | a cartoon rocket exhaust flame, orange to white hot core, flickering, side view |
| `ramp_1`..`ramp_5` | 1 | — | — | a wooden launch ramp on an ice shelf, side view, progressively taller and more elaborate scaffolding |
| `iceberg` | 1 | — | — | an enormous jagged blue-white iceberg wall, side view, imposing |
| `water_surface` | 8 | 12 | — | a tiling side-on band of cartoon ocean water with gentle wave crests |
| `splash_small` | 6 | 24 | — | a small white water splash burst, cartoon style |
| `snow_particle` | 1 | — | — | a single soft white snowflake dot |
| `cloud_a`..`cloud_c` | 1 | — | — | a fluffy flat white cartoon cloud, three distinct shapes |
| `kiwipedia_book` | 1 | — | — | an open encyclopedia book, cartoon style, a kiwi bird on the cover |

#### LTF2 additions

| Slug | Frames | fps | Loop | Prompt (after the style block) |
|---|---|---|---|---|
| `dummy_glide` | 12 | 24 | — | a crash-test dummy in penguin costume gliding, stiff limbs, blank painted face |
| `sled_1`..`sled_4` | 1 | — | — | a launch sled, side view, progressively from wooden toboggan to rocket-powered steel rail sled |
| `thruster_1`..`thruster_4` | 1 | — | — | a strap-on rocket booster, side view, progressively from firework to industrial engine |
| `payload_bomb` | 1 | — | — | a cartoon black spherical bomb with a lit fuse, strapped in a harness |
| `obs_snowman` | 1 | — | — | a cartoon snowman with a carrot nose and stick arms, side view |
| `obs_mound` | 1 | — | — | a large packed snow mound, side view |
| `obs_hill` | 1 | — | — | a rocky grey hill with snow on its crown, side view |
| `obs_iceberg` | 1 | — | — | a huge blue-white iceberg, side view, cracks visible |
| `obs_wall` | 1 | — | — | a colossal sheer wall of ice, 600 feet tall, side view, dwarfing everything |
| `explosion` | 10 | 30 | — | a cartoon explosion, orange fireball into white smoke ring |
| `debris_ice` | 1 | — | — | a shard of broken ice, cartoon style |
| `merchant_penguin` | 8 | 10 | — | a shifty cartoon penguin in a long trench coat, glancing side to side, coat held half open |
| `brick` | 1 | — | — | an ordinary red clay brick, no wings, comedically mundane |
| `phoenix_feather` | 6 | 16 | — | a glowing orange phoenix feather with drifting embers |
| `rob64` | 8 | 14 | — | a small friendly retro robot hovering with a thruster, waving |

Each obstacle also needs **three damage states** (pristine / cracked / crumbling). Generate the pristine
one and produce the other two in Studio by re-prompting with "with deep cracks spreading across it" and
"half destroyed, chunks missing, rubble at its base."

### §8.6 Sound

Not Studio's department, but the game needs it. WebAudio, unlocked on first gesture.

Minimum set: ramp rumble · launch whoosh · wind loop (volume and low-pass tracked to speed — this alone
does an enormous amount of work for the sense of velocity) · rocket loop · glider creak (fades in as
`gliderHp` drops) · glider snap · water skip (pitch rises per consecutive bounce) · splash · impact
thud · obstacle crack · explosion · UI click · purchase chime · results tick · medal fanfare.

---

## §9 — Save format

```js
{
  version: 3,
  ltf1:   { day, cash, upgrades: {...}, bestDistFt, bestAltFt, completed },
  ltf2:   { mode, day, cash, gear: {...}, attrs: {...}, obstacleHp: {...},
            challenges: {...}, completed },
  account:{ bp, medals: { id: unlockedAtISO },
            lifetime: { distFt, airTimeS, bounces, destroyed, diveFt, reverseFt, cashEarned } },
  settings:{ volume, muted, reducedMotion, keybinds }
}
```

- **`account` survives "new game." `ltf1` and `ltf2` do not.** This is §7.5's persistence boundary, and
  it is the one thing here that is genuinely hard to retrofit.
- **Version and migrate.** A `migrate(save)` chain from every past version. A player who loses a 150-day
  run to a schema change will not come back.
- **Guard the read.** `try/catch` around `JSON.parse`, validate the shape, fall back to a fresh save with
  a visible warning rather than a white screen.
- Autosave on entering `SHOP` and on medal unlock. Never mid-flight.

---

## §10 — Camera

- Follow the penguin with **velocity lookahead**: the focus point leads by `v * LOOKAHEAD_S` (~0.35 s),
  so at speed you see where you are going rather than where you have been.
- **Critically damped spring**, not a lerp. A lerp with a fixed factor is framerate-dependent and gets
  soggy at high speed.
- **Zoom out with altitude and with speed**, clamped. The 600 ft wall is taller than any sensible
  viewport — when it enters frame the camera must pull back enough to show its top, or the player
  cannot judge whether they will clear it (which the `I.C.B.P.` medal requires them to do).
- **Draw parallax layers at integer device pixels.** Sub-pixel parallax offsets shimmer, and at this
  game's speeds that shimmer is very visible against flat snow.
- **Keep world coordinates sane.** Distances reach 150,000 ft (medal 48) — 45,700 m. That is fine for
  float64, but do not accumulate camera position by repeated `+=` of small deltas over a long run 
  derive it from the penguin's absolute position each frame.

---

## §11 — Calibration against the original

This is how "as best as possible" becomes measurable instead of aspirational.

### The setup

*Learn to Fly* is a Flash game and Flash is gone, but the SWF runs today in **Ruffle**, an open-source
Flash Player emulator in Rust/WebAssembly. The Internet Archive hosts a large playable Flash collection
running on exactly this. The owner sources the SWF themselves (Internet Archive, Flashpoint Archive);
this document does not ship it.

```
reference/
  README.md          the procedure below
  ruffle/            the self-hosted Ruffle package (ruffle.rs — "self-hosted")
  ltf1.swf            owner supplies
  ltf2.swf            owner supplies
  reference.html     Ruffle embed, sized to match the remake's canvas exactly
```

The launcher's **SIDE-BY-SIDE** entry puts the original in the left half and the remake in the right,
same canvas size, same scale. This is also the single best shot in the YouTube video.

### The procedure

For each of six **calibration points** — (fresh save), (glider 1 only), (glider 2 + accel 2),
(glider 3 + accel 3 + rockets 2), (all tier 4), (all tier 5) — fly a scripted input sequence in both
builds and record:

| Metric | Tolerance |
|---|---|
| Exit speed off the ramp (mph) | ±3% |
| Apex altitude (ft) | ±5% |
| Total distance (ft) | ±5% |
| Air time (s) | ±8% |
| Cash awarded | ±10% |
| Resistance % in a held optimal glide | must read **34%** |
| Time from full nose-up to full nose-down | ±10% |

`tools/calibrate.mjs` takes the recorded originals as a JSON table and sweeps `tuning.js` constants to
minimise total error. **It does not need to be clever** — a coordinate-descent pass over eight constants
converges fine and takes seconds. What matters is that the loop exists, so tuning is measurement rather
than vibes.

**Log every change in `CALIBRATION.md`:** metric, original value, remake value before, constant changed,
value after. When the owner asks in three months why gravity is 22.4, the answer is in that file.

> **If the SWF cannot be sourced, this section still works** — substitute reference *footage*. Frame-step
> a longplay video, read the on-screen HUD numbers at known moments, and calibrate against those. Lower
> precision, same method. Do not skip calibration on the grounds that the original is unavailable.

---

## §12 — Verification

`node tests/run-tests.mjs`. No framework, no dependencies. It exits non-zero on failure, prints one line
per check.

### Structural

1. **`sim/` purity.** Grep every file in `sim/` for `document`, `window`, `canvas`,
   `requestAnimationFrame`, `localStorage`. Any hit fails the suite. *This test protects every test
   below it.*
2. **No stray magic numbers.** Grep `sim/` for numeric literals outside a small allowlist (`0`, `1`, `2`,
   `0.5`, `-1`). Everything else must come from `tuning.js`.
3. **Manifest integrity.** Every `placeholder` named in `manifest.json` exists as an exported function in
   `placeholder.js`. Every sheet referenced either exists on disk or has a placeholder.

### Physics

4. **No NaN, ever.** Run 10,000 random initial states through 3,000 steps each. Assert every field is
   finite at every step. This catches the `atan2(0,0)` and `sin(2α)` edge cases before a player does.
5. **Terminal velocity converges.** Nose-down, no thrust: speed approaches `sqrt(G/CD0)` and stays.
6. **Stall is real.** At α = 80°, lift must be *lower* than at α = 30°. This is the test that fails if
   someone "simplifies" `sin(2α)` to a linear curve.
7. **Glide ratio is in range.** With §16's constants, best-glide L/D per tier works out to **4.2, 8.8,
   12.4, 16.2, 19.8** — all at α — 0.30 rad (17°), because the optimising α depends only on
   `CD0/K_INDUCED` and is therefore the *same* for every glider. Assert within ±10%. If you change
   `CD0` or `K_INDUCED`, recompute these expected values rather than loosening the tolerance.
8. **Optimal glide reads 34%** on the resistance gauge (§4.5). **This is the headline feel test.**
9. **Determinism.** Same seed + same recorded input sequence — identical final state, bit for bit, twice
   in the same process and once after a reload. This is what makes replays, ghosts and regression tests
   possible at all.
10. **No tunnelling.** Fire the penguin at every obstacle at 250 mph from 200 random angles. Every one
    must register a hit. Zero misses allowed.
11. **Water behaves.** Shallow + fast always skips; steep always dives; a submerged penguin always
    resurfaces (never sinks forever).

### Economy

12. **Monotonicity.** Sweep every upgrade combination through a scripted autopilot. **A strictly better
    loadout may never yield strictly less cash.** (§6.3 — this catches the "trap upgrade" bug class.)
13. **Reachability.** Simulate a competent player from a fresh save. Glider 1 must be affordable by day
    3, and 6,000 ft must be reachable in **1625 days** — the community's own benchmark for the original
    is 1618. If your build takes 60 days, the price curve is wrong, not the player.
14. **Save round-trip.** Serialise — parse — deep-equal. Plus: every historical `version` migrates
    forward cleanly.

### Medals

15. All 48 exist in `medals.data.js` with unique ids.
16. Each is reachable — a scripted scenario per medal that unlocks it. This is tedious and it is the only
    way to discover that medal 40 is impossible because your wall collision is 3 ft too tall.

### By hand, by a human

17. Play days 110 of LTF1 with fresh eyes. Does the first glider feel like relief?
18. Play the side-by-side (§11). Watch the launch. Does the remake lurch the same way?
19. Chain five water skips. Does it feel as good as it should?
20. Fly over the 600 ft wall. Can you see enough to judge it?

---

## §13 — Phases and gates

Each gate is a thing you can *do*, not a thing that compiles. Do not start the next phase until the
current gate passes.

| Phase | Build | **Gate** |
|---|---|---|
| **0** | `Play.bat`, launcher shell, fixed-timestep loop, input, empty canvas | The loop holds 60 fps with a moving test dot; alt-tabbing for 60 s does not teleport it |
| **1** | `sim/physics.js` + headless tests. **No rendering at all.** | Tests 48 pass. Glide ratio and the 34% resistance target are hit **in Node** |
| **2** | `placeholder.js` vector art, camera, HUD. Fly with the keyboard. | A human flies a penguin with zero image files, and it already feels like Learn to Fly |
| **3** | Ramp, water skip/dive, ground, run end, results panel | Full flight loop start to rest. Skipping works and is fun |
| **4** | LTF1 economy, six upgrades, shop, day loop, save | **LTF1 is complete and winnable.** Test 13 passes |
| **5** | LTF1 intro, ending, the iceberg gag, sound | Someone plays it start to finish and reacts to the ending |
| **6** | Shadow Studio pipeline: `sprites.js`, manifest, first three sheets | One real animation replaces its placeholder with no code change outside the manifest |
| **7** | Remaining LTF1 art, parallax, particles, polish | Side-by-side against the SWF is *convincing* |
| **8** | §11 calibration pass, `CALIBRATION.md` | Every metric inside tolerance |
| **9** | LTF2 obstacles, payload, damage persistence, story mode | Story mode completable — the wall falls |
| **10** | LTF2 classic + arcade, gear catalogue, BP, Omega | All three modes playable |
| **11** | All 48 medals, challenges, secret shop, the joke easter eggs | Tests 1516 pass |
| **12** | LTF2 art pass, capture mode (§15), `CLAUDE.md`, portfolio registration | Ship |

**Phase 1 has no rendering on purpose.** It is genuinely tempting to draw something first, and it is the
wrong order: the moment there is a picture, tuning becomes "does it look right" instead of "does the
number say 3.5%," and the flight model never gets the attention it needs. Build the physics blind.

---

## §14 — PAST THE WALL

> *"i've always wanted to make stuff past the wall"*

This section exists so that when the owner reaches it, nothing is in the way. Everything above is a
faithful remake. This is where it stops being one, and the codebase must be *built* for that from phase
0 — not retrofitted at phase 12.

### The three seams to build in from the start

**1. `POST` is a real state, not a `return to menu`.** In §5, `ENDING — POST`. In the faithful build,
`POST` immediately returns to the launcher. It is a two-line stub. But it *exists*, it is in the state
machine, it is in the save file, and adding content there is adding content — not surgery on a finale.

**2. The world is generated from segments, never hardcoded.** `sim/world.js` exposes:

```js
world.segmentAt(xMetres)  { terrain, obstacles, props, biome, parallax }
```

For LTF1 this returns "ocean forever." For LTF2 it returns the five obstacles at their fixed distances
and ocean between. **Neither is special-cased in the physics or the renderer.** The moment the owner
wants a continent past the wall, it is a new segment provider, and the flight model, camera, collision
and HUD all already work there. Hardcoding `if (x > 6000)` anywhere in `sim/` forfeits this — treat it
as a build error.

**3. The upgrade tree is data, and the shop renders whatever data it is given.** Adding a seventh
upgrade category, or a tier 6, must be a row in `ltf1.upgrades.js` — never a change to `shop.js`. Test
this during phase 4 by adding a nonsense tier 6, confirming it renders and buys correctly, then deleting
it.

### What the owner might do with it — theirs to decide, listed so the seams are the right shape

Not a plan. A set of things the architecture above must not *prevent*:

- **The wall falls and there is land behind it.** Segments past 6,000 ft become terrain instead of ocean:
  cliffs, forest, a city. The same penguin, the same physics, a world that keeps going.
- **Altitude becomes the axis instead of distance.** Keep climbing. Thinner air (lower `CD0` *and* lower
  `CL_MAX` — less drag but also less lift, which is a genuinely interesting flight change), then the
  stratosphere, then no air at all and the rockets are all you have. This is roughly where *Learn to Fly
  3* went, and the segment system reaches it without a rewrite.
- **The penguin never lands.** An endless mode with fuel pickups, where the run ends when you touch
  anything.
- **Weather.** Wind as a velocity field in `segmentAt` — thermals to ride, headwinds to fight. Fits the
  flight model exactly, since the model already works in terms of airspeed vs. ground speed if you let
  it.
- **Two penguins.** The determinism from test 9 gives you ghost replays for free, which is 80% of a
  race mode.

**The single most important line in this section:** the faithful remake must be finished and shipped
*before* any of this is started. A remake that got interesting halfway through and became something else
is neither a remake nor the new thing — and the YouTube video needs the faithful one to exist.

---

## §15 — For the video

The stated purpose of this build is a YouTube video. These are cheap and they make the footage better.

- **Capture mode** — `F9` hides all HUD and UI. `F10` toggles the side-by-side. `F11` fullscreen.
- **Deterministic replay** — record the input stream per run (test 9 makes this exact). `F8` replays the
  last run. Lets the owner get a perfect take without re-flying it.
- **Slow motion** — `[` and `]` scale the timestep. A 0.25 glider snap or wall collapse is a great shot.
- **Freecam** — hold `C` to detach the camera and pan/zoom freely mid-replay.
- **A frame-accurate 60 fps lock** for recording — the fixed timestep already guarantees the sim is
  identical regardless of display rate, so recording at 60 while playing at 144 is safe.
- **Debug overlay** — `F3` shows α, Cd, Cl, resistance %, glide ratio, gliderHp live. Genuinely useful
  for explaining the flight model on camera, which is a great segment.

---

## §16 — `tuning.js` starting values

All CALIBRATE. All in one file. This is a complete, playable set — start here, then §11.

```js
export const TUNING = {
  // integration
  DT: 1/120, MAX_STEPS: 8, MAX_FRAME_DT: 0.25,

  // world
  G: 22.0,                    // m/s². Arcade-exaggerated on purpose; real 9.81 feels floaty here
  SEA_LEVEL_Y: 0,

  // aero
  CD0: 0.0022,                // baseline drag coeff  dive terminal  100 m/s  225 mph
  K_INDUCED: 0.020,           // drag growth with alpha²
  CL_MAX: 1.00,               // peak of the sin(2a) lift curve
  DRAG_DISPLAY_SCALE: 1.25,   // makes optimal glide read 3.5%  arithmetic worked in §4.5

  // control
  ROT_RATE: 70 * Math.PI/180, // rad/s. The single most feel-critical number in the game
  THETA_MIN: -Math.PI/2, THETA_MAX: Math.PI/2,

  // launch
  RAMP_ANGLE: 20 * Math.PI/180,
  RAMP_SLIDE_TIME: 0.6,

  // glider integrity
  gliderBreaks: true,
  GLIDER_STRESS_RATE: 0.55,   // hp/s at 2x the rated max speed

  // water
  SKIP_ANGLE_MAX: 18 * Math.PI/180,
  SKIP_MIN_SPEED: 12,
  SKIP_RESTITUTION: 0.55,
  SKIP_SPEED_LOSS: 0.88,
  WATER_DRAG_MUL: 12,
  BUOYANCY: 1.6,              //  G, upward

  // run end
  REST_SPEED: 1.5, REST_TIME: 0.4, RESULTS_DELAY: 0.9,

  // economy
  DIST_RATE: 0.55,            // $ per foot travelled
  ALT_RATE: 1.20,             // $ per foot of max altitude
  TIME_MULT_SCALE: 22,        // mult = clamp(1 + airTime/22, 1, 3)  VERIFIED range 1..3
  GOAL_FT: 6000,              // VERIFIED

  // camera
  LOOKAHEAD_S: 0.35, CAM_STIFFNESS: 9.0,
  ZOOM_MIN: 0.35, ZOOM_MAX: 1.0,
};
```

---

## §17 — Landmines

Things that will go wrong. Each one has cost somebody a day.

| # | Trap | Do this |
|---|---|---|
| 1 | `requestAnimationFrame` dt spikes on alt-tab; the penguin teleports through the world | Clamp frame dt to 0.25 s **and** cap substeps (§4.1) |
| 2 | Tunnelling through obstacles and the water surface at 100 m/s | Swept segment collision, not point sampling (§4.9) |
| 3 | Sprite rotates about the wrong point and wobbles | Manifest pivot overrides Studio's frame centre; debug crosshair on `P` (§8.3) |
| 4 | Applying Studio's `offset_*` **and** your own pivot, so sprites sit exactly double the pivot off | Pick one. The manifest pivot wins when present; otherwise use Studio's. Comment it in `sprites.js` (§8.3) |
| 5 | mph/ft leak into `sim/` and tuning becomes unfalsifiable | Metres and seconds internally, convert only in `hud.js` (§4.2) |
| 6 | Linear lift curve — pitch to 89° for infinite climb | `sin(2α)`. Test 6 exists to catch its removal (§4.3) |
| 7 | `atan2(0, 0)` at zero velocity — NaN — everything is NaN forever | Guard `speed < 1e-6`; test 4 sweeps for it |
| 8 | A lerp camera that is soggy at 144 Hz and snappy at 60 | Critically damped spring, dt-correct (§10) |
| 9 | Parallax shimmer against flat snow | Round layer offsets to integer device pixels (§10) |
| 10 | Audio silently never plays | WebAudio needs a user gesture; unlock on the launcher click, and show a muted indicator if the context is suspended |
| 11 | A save schema change wipes a 150-day run | Version + `migrate()` from day one (§9) |
| 12 | Medals stored per-playthrough, discovered at phase 11 | `account` vs `ltf1`/`ltf2` scope, built in phase 4 (§7.5) |
| 13 | The 600 ft wall does not fit on screen and `I.C.B.P.` is unjudgeable | Altitude-driven zoom-out, clamped (§10) |
| 14 | `sim/` imports the renderer; headless tests die; feel becomes unverifiable | Test 1 greps for it and fails the suite |
| 15 | Prices tuned by feel; first glider lands on day 6; early game is miserable | Test 13 — glider 1 by day 3, win in 1625 days |
| 16 | A missing sprite crashes the game | `sprites.js` falls back to `placeholder.js`, logs once, never throws (§8.4) |
| 17 | Canvas is blurry on a high-DPI display | Scale the backing store by `devicePixelRatio`, set CSS size separately |
| 18 | `imageSmoothingEnabled` wrong for the art style | Off for pixel art, on for smooth vector art. Put it in the manifest per sheet |
| 19 | Chroma key leaves a green fringe on white snow art | Key on **magenta `#FF00FF`**, never green (§8.5) |
| 20 | An old Studio export (no `"version"` in its metadata) is trusted as if it were the v2 contract | Check for `"version"`. Absent means flattened motion and a phantom `offset_y: -12` — re-export instead of compensating (§8.3) |

---

## §18 — Definition of done

- [ ] All 20 checks in §12 pass; `node tests/run-tests.mjs` exits 0
- [ ] Every §11 calibration metric inside tolerance; `CALIBRATION.md` complete
- [ ] LTF1 playable start to finish, including the iceberg ending
- [ ] LTF2 story, classic and arcade all completable
- [ ] All 48 medals implemented and individually reachable
- [ ] The game is playable with `assets/sheets/` **empty** (placeholder fallback intact)
- [ ] Side-by-side against the original is convincing to someone who played it in 2009
- [ ] Capture mode, replay and debug overlay all work (§15)
- [ ] The three §14 seams exist and are tested: `POST` state, segment world, data-driven shop
- [ ] `Play.bat` — playing, on a clean machine, with no install step
- [ ] `CLAUDE.md` written from what was actually built
- [ ] Registered in `../CLAUDE.md`'s project table **and** `../_brain/knowledge.json`, per the portfolio
      convention

---

### Sources

Verified facts in this document come from:
[Learn to Fly on Kongregate](https://www.kongregate.com/en/games/light_bringer777/learn-to-fly) ·
[JayIsGames review & walkthrough](https://jayisgames.com/review/learn-to-fly.php) ·
[Wikiversity: Games/Learn to Fly](https://en.wikiversity.org/wiki/Games/Learn_to_Fly) ·
[Armor Games — LTF guide in 1618 days](https://armorgames.com/community/thread/3986447/learn-to-fly-guide-in-16-18-days-it-really-works) ·
[Armor Games — all LTF2 achievements](https://armorgames.com/community/thread/12638241/all-achievements-in-learn-to-fly-2-and-how-to-get-them) ·
[CoolMathGames LTF2 strategy guide](https://www.coolmathgames.com/blog/how-to-play-learn-to-fly-2) ·
[Light Bringer Games](http://lightbringergames.com/) ·
[Ruffle](https://ruffle.rs/) ·
[Internet Archive Flash collection](https://archive.org/details/ruffle-emulator)

Shadow Studio's pipeline and file contract were read directly from the local source:
`Shadow/CLAUDE.md`, `Shadow/README.md`, `Shadow/backend/app/main.py` (lines 441512),
`Shadow/docs/asset-forge.md`.
