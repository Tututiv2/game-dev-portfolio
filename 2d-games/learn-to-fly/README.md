# Learn to Fly (remake)

A from-scratch, Flash-free remake of *Learn to Fly* (2009) and *Learn to Fly 2* (2011), with a flight model you can test from the command line.

A penguin, a ramp, and a day-by-day grind of upgrades until it can fly. This is a non-commercial fan remake of Light Bringer Games' two Flash classics, rebuilt in vanilla JavaScript and Canvas2D with no dependencies and no asset files: every sprite is drawn in code and every sound is synthesised. For a game whose look is flat shapes with thick black outlines, drawing it in code is closer to the original than generated art, and it animates perfectly because every frame is under control.

## Features

- *Learn to Fly 1* and *Learn to Fly 2* (story, classic and arcade modes) in one build
- Six upgrade lines of five tiers each (ramp height, acceleration, air resistance, glider, rockets, rocket fuel), bought between runs
- Water entries that skip, dive and resurface (a good entry chains 13 skips)
- The LTF2 obstacle run, two currencies and all 48 medals
- A data-driven shop: a new upgrade category or a sixth tier is one row in a data file
- Video and debug tools: `F3` live aero overlay (angle of attack, Cl, Cd, L/D), `F8` exact replay of the last run, `F9` hide HUD, `[` `]` slow motion

## How it works

- **The flight model is pure, and a grep enforces it.** Nothing in `src/sim/` may touch the DOM, canvas, `requestAnimationFrame`, `localStorage` or `Math.random`; check 1 of the suite greps for all of them. That is what lets `node tests/run-tests.mjs` fly the shipping physics with no browser, so every claim about feel (glide ratios, monotonic upgrades, a full playthrough) is a headless simulation. A second check rejects numeric literals in `sim/` that do not come from `data/tuning.js`.
- **Physics.** A 120 Hz fixed timestep with interpolated rendering. `Cl = sin(2a)` and `Cd = Cd0 + K a^2`, which gives stall for free and puts best glide at the same 16.9 degree angle of attack for every glider. Drag and lift are integrated as exact solutions in the velocity frame rather than as an Euler pair, because with Euler a perpendicular force *adds* energy above 153 m/s, inside the fastest wing's own 400 mph rating: a player holding a hard turn would accelerate for free.
- **22 checks in about 12 seconds, and they found real bugs.** An energy check (energy must never rise without thrust) found a "submarine in the sky": a shallow dive sat at exactly y = 0, buoyancy reversed it before the water-crossing test fired, and the penguin climbed through open air at +0.6 G, worth 4,382 ft from an 8 m ramp. Another found a top-speed medal mathematically impossible, because terminal velocity in a powered dive is `sqrt((G + thrust) / Cd0)`, and the first glider unable to fly, since it stalled at 42 mph while tier-1 launches reached 40.
- **The economy is measured, not felt.** `tools/progression.mjs` plays a competent player day by day: glider by day 3 and 6,000 ft by day 17, against a community benchmark of 16 to 18 days for the original. Monotonicity holds over 213 strictly-better loadout pairs. The autopilot that stands in for the player was itself wrong three times, and each time it looked like a balance bug; one of those was a missing tuning constant that went `undefined`, then `NaN`, and silently produced no input at all.

## Tech

- Vanilla JavaScript ES modules, Canvas2D, Web Audio
- About 6,900 lines across 39 files, zero dependencies
- A dependency-free Node test runner and balance tools in `tools/`

## Running it

ES modules do not load from `file://`, so serve the folder. On Windows, double-click `Play.bat`; otherwise:

```
node tools/serve.mjs 5500      # or: python -m http.server 5500
```

Open `http://localhost:5500/`. Run the test suite with `node tests/run-tests.mjs` (there is nothing to install; `package.json` only sets `"type": "module"`).

**Status:** complete and playable. Numbers are pinned by measurements of this build but have not yet been calibrated against the original SWF, and the LTF2 classic and arcade modes have had less tuning than story.
