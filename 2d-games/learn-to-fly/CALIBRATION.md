# CALIBRATION.md

The running log §11 asks for. Every number that moved away from `BUILD_PROMPT.md`, what
measured it, and why — so that when the owner asks in three months why the Omega Drive is 58
and not 34, the answer is in this file rather than in someone's memory.

**§0.7 is the rule this file exists to serve:** when this document and the original disagree,
the original wins — change the document, record what changed and why, then change the code.
Everything below is a change to the document, recorded before the code moved.

> **The original SWF has not been sourced yet**, so nothing here is calibrated against
> *Learn to Fly* itself. What has been done is the other half: every number is now pinned by a
> measurement of *this* build, and `tools/calibrate.mjs` is ready to diff against real
> recordings the moment `reference/targets.json` exists. See `reference/README.md`.

---

## The tools

| | |
|---|---|
| `node tests/run-tests.mjs` | 22 checks. `--quick` shrinks the brute-force sweeps; `--only=N` runs one. |
| `node tools/progression.mjs` | Plays a fresh save day by day and prints the table §12.13 asserts on. `--rate=` and `--steep=` try a candidate economy without editing a data file. |
| `node tools/calibrate.mjs --sweep` | Coordinate descent over the two pacing knobs until both §12.13 gates hold. |
| `node tools/calibrate.mjs` | The six §11 calibration points, ready to diff against `reference/targets.json`. |
| `node tools/energy-probe.mjs` | Asserts total specific energy never rises un-thrusted. Kept because it is how the worst bug in the build was found. |

---

## D — deliberate deviations from the spec

### D-1 · Aero is integrated in the velocity frame, not as two Euler additions

§4.3 step 5 specifies semi-implicit Euler for all four forces. Drag and lift are instead
applied as the exact solutions of their own ODE over `dt`: drag scales the speed
(`v / (1 + cd·v·dt)`), lift rotates the direction (`ψ += lift·cl·v·dt`). Gravity and thrust
are still Euler.

**Why.** With the Euler pair, a perpendicular force *adds* energy at order `(a·dt)²/2v`. Net
growth begins above `v > 2·cd / (dt·(cd² + lift²cl²))`, which for the Prototype wing works out
at **153 m/s — 342 mph, inside its own 400 mph rating**. A player holding a hard turn up there
would accelerate for free. Rotating the velocity instead means lift cannot change speed at
all, at any `dt`, ever.

Verified unchanged: terminal velocity still converges to `sqrt(G/CD0)` within 1% (check 5),
and check 22 now watches the property directly.

### D-2 · `crossDown` treats its start bound as inclusive

`ay >= level && by < level`, not `ay > level`. A water skip leaves the penguin at exactly
`y = 0`, so a strict test misses the very next crossing — at the tail of a long chain, where
the rebound is smaller than one step of gravity, the penguin falls through the ocean.

### D-3 · `package.json` exists

Not in §3's layout. Node decides a `.js` file's module type from the nearest `package.json`,
so without `{"type":"module"}` the test runner cannot import the real sim at all. No
dependencies, nothing to install.

---

## B — bugs the checks caught, recorded because each one is a class

| | Found by | What it was |
|---|---|---|
| **B-1 The submarine in the sky** | `tools/energy-probe.mjs`, now check 22 | A dive entered with a tiny downward velocity sits at exactly `y = 0`, and buoyancy reverses `vy` before `y` ever goes negative — so the upward-crossing test never fires, the state stays `SUBMERGED`, and the penguin climbs through open air at +0.6 G with 12× drag. Worth **4,382 ft** from an 8 m ramp. Fixed by testing position, not crossing. |
| **B-2 A controller that was not running** | check 21 | `T.AP_PATH_GAIN` was never actually written to `tuning.js`. `undefined` arithmetic goes `NaN`, `clamp(NaN)` returns `NaN` because neither comparison is true, and the autopilot emitted no input at all — so it flew with the stick centred and the numbers looked plausible. **Two rounds of tuning were measured against it.** Check 21 now asserts every `T.X` in `src/` exists. |
| **B-3 Art drawn upside down** | the browser | `placeholder.js` is authored y-up like the world transform; every UI call site scaled `(k, k)`. The hang glider hung *below* the penguin. |
| **B-4 The ocean over the ice shelf** | the browser | Water was culled per 200 m segment while the shelf is 60 m wide, so the sea was painted over the ice the ramp stands on. Segments now carry exact solid spans. |
| **B-5 A ramp at 63 degrees** | the browser | The ramp was drawn from the *slide path*, which is a fixed 0.6 s and therefore ~7 m of a 41 m structure. The slide is a section near the top of the ramp, not the ramp. |
| **B-6 A wall you could only see the top of** | check 20 | Zooming out far enough to fit the 600 ft wall is only half of landmine 13 — the camera still centred on the penguin, so at 193 m up it framed sky and the base fell off the bottom. |

---

## C — calibrated values

### C-1 · Cash rates and the price ladder

Swept with `tools/calibrate.mjs --sweep` against §12.13's two gates. The knobs are
lexicographic, not independent: price steepening leaves every tier-1 price alone so it cannot
move the glider gate, while the cash rate moves both. So the rate is pinned first — at the
*leanest* income that still puts a glider in the player's hands by day 3, because a richer day
1 makes the whole early game mushy — and the back half is stretched after.

| | §16 | now |
|---|---|---|
| `DIST_RATE` | 0.55 | **2.12** |
| `ALT_RATE` | 1.20 | **4.53** |

Prices moved with them (`ltf1.upgrades.js`). The gates: **glider on day 3, 6,000 ft on day
17** — the community benchmark for the original is 16–18.

**Two gates passing is necessary and not sufficient.** An early candidate cleared both with
four identical 189 ft days in a row and then a three-day explosion at the end; that is §17.15's
failure mode wearing a green tick. Check 13 now also asserts at most five days with nothing to
buy, and `tools/progression.mjs` exists so the *shape* can be looked at rather than inferred.

### C-2 · Rockets are punctuation, not propulsion

§16's thrust and fuel made the rockets the single biggest distance driver in the game, ahead
of the glider — and §6.2 is clear that the glider is the one that matters. Measured: buying
Rockets 3 took a run from 4,931 ft to 8,665 ft, more than the entire rest of the shop
combined. Holding altitude in a best glide costs about `G/(L/D)` = 1.4 m/s², so §16's
*smallest* rocket was already ten times "hold height" and the largest was a second launch.

| | §16 | now |
|---|---|---|
| Thrust, m/s² | 14 / 22 / 32 / 46 | **6 / 10 / 15 / 22** |
| Fuel, s | 2.5 / 4.5 / 7.5 / 12.0 | **1.2 / 2.2 / 3.5 / 5.0** |

§2.5, in numbers: fuel is small, thrust is big, every tier is a save you plan.

### C-3 · The Omega Drive, 34 → 58 m/s²

Check 16 found **Speedster 2 unreachable**. The medal is VERIFIED at 500 mph; terminal in a
powered dive is `sqrt((G + thrust) / cd0)`, so the medal is a direct constraint on this
number. At 34, with Streamlining maxed, a dive caps at **440 mph**. Now 550. `Streamlining`
went from −7% to −8% per step at the same time.

### C-4 · The glider lift ladder, and why the L/D table moved

Check 17 measured the "relief" of buying the first glider at **1.19×**. The cause was exact:
a wing can only hold the penguin up above `sqrt(G / lift)`, and §16's Cardboard at 0.062
**stalls at 42 mph while tier-1 Acceleration launches at 40**. The first glider a player can
afford could not fly.

| tier | §16 lift | now | stall speed |
|---|---|---|---|
| Flippers | 0.030 | 0.030 | 60 mph — it never really flies, which is the point |
| Cardboard | 0.062 | **0.075** | 38 mph, under the 40 mph launch |
| Hang Glider | 0.088 | **0.095** | 34 mph |
| Delta Wing | 0.115 | 0.115 | **pinned** — §4.5 works its 3–4% arithmetic with exactly this |
| Prototype | 0.140 | **0.145** | 27 mph |

Best-glide L/D is *derived* (`lift × 141.19` at the current `CD0`/`K_INDUCED`), so check 7's
expected values were **recomputed to 4.2 / 10.6 / 13.4 / 16.2 / 20.5** rather than the
tolerance loosened — §12.7 is explicit about which of those two is allowed.

Relief is now **1.56× the distance and 1.92× the air time**. The check asserts on air time,
because that is what relief feels like in this game: not landing further along, but not
plummeting.

### C-5 · The resistance gauge reads 3.45% at best glide

§4.5 predicted 3.4% with `DRAG_DISPLAY_SCALE = 1.25` untouched. Measured: **3.45%**. No
change needed, and it is decent evidence the lift/drag *shape* is right rather than merely
tuned to hit a number.

The reading is glider-dependent by construction — a worse wing has to fly faster to hold
height, so it genuinely is fighting the air harder:

| Flippers | Cardboard | Hang Glider | Delta Wing | Prototype |
|---|---|---|---|---|
| 12.78% | 5.26% | 4.17% | **3.45%** | 2.75% |

All three real wings land in the neighbourhood the community advice names.

### C-6 · The autopilot is a model of a player, and it was wrong three times

Checks 12 and 13 are claims about what a *player* can earn, so they are only as good as the
player. Three findings, each of which read as a game-balance bug and was not:

1. **A fixed best-glide α phugoids.** At above-equilibrium speed it generates surplus lift, and
   the stronger the wing the more violent the oscillation. It reported the **Prototype as a 14%
   pay cut**. Fixed by adding a mode that holds the flight *path* and lets α fall where it must
   — which is exactly the argument for why a better wing is better.
2. **A fixed α is not a fixed manoeuvre.** The g it buys scales with `lift·v²`, so 0.55 rad is
   a nudge behind cardboard at 20 m/s and a **7 G snap** behind a Delta Wing at 40. Commanding
   it flat stood the penguin on its tail a quarter-second off the ramp and sent an entire
   rocket burn straight up: 291 ft of altitude, 89 ft of distance. Every command is now a path
   command.
3. **`AP_FLARE_ALT` was an absolute 26 m** while a mid-game run peaks at 12–15, so the
   autopilot spent the *entire flight* in the water flare and never glided once — which made
   Air Resistance look like a trap upgrade, because the glide ratio it buys was never used.
   Everything near the surface now keys off seconds-to-water.

### C-7 · Monotonicity holds on base pay; the time multiplier gets a stated tolerance

Over **213 strictly-better loadout pairs**, the worst regression is **1.6% of base pay and
3.6% of total**, and every one of them is a run that gained or lost a single water skip.

The residual is the `1 + airTime/22` multiplier, which rewards *loitering* — so less drag can
cover the same ground in less time and take a slightly smaller multiplier. That is a property
of §6.3's own formula rather than a trap upgrade, and check 12 states it as two bounds
(2.5% of base, 5% of total) rather than passing silently. If either ever needs raising, the
economy has a real problem; look at the mechanism, not the bound.

---

## Still open

- **The original.** Every §11 tolerance is unmeasured until `reference/targets.json` exists.
  `node tools/calibrate.mjs` prints the six calibration points and will diff them the moment
  it does.
- **`gliderBreaks`.** Shipped `true` (§4.6). The wikis are ambiguous about whether LTF1
  breaks gliders or merely caps them; it is one line in `tuning.js` if footage says otherwise.
- **`ROT_RATE = 70°/s`** is the single most feel-critical number in the game and nothing has
  measured it. §11's procedure: time the original from full nose-up to full nose-down. This
  build takes **2.57 s**.
