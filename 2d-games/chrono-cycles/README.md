# Chrono-Cycles

A physics-based idle game: spin ten concentric rings, turn RPM into resources, and zoom out through three dimensions of prestige.

Chrono-Cycles is built around a "Chrono Engine" of ten spinning rings. Clicking a ring applies torque; every completed rotation pays out that layer's resource; upgrades keep the rings turning on their own. Past the standard engine there is an Infinity dimension with a black hole at its centre, and a Reality dimension where the entire ten-ring game shrinks into a microcosm in the middle of the screen and powers one enormous outer ring.

This folder holds two versions of the game: `chrono-cycles/` (v8.3, the one to play) and `Factorio Idle.html` (v7.6, kept as an archive).

## Features

- 10 resource layers, each with three upgrade pillars: Perpetual Motor (minimum RPM), Kinetic Lubricant (speed multiplier) and Matter Compression (yield per lap)
- Chronon prestige for global bonuses, per-layer auto-buy toggles, achievements and random events (Solar Flare, Time Warp and others)
- At 1,000,000 RPM a ring breaks the "light speed barrier" and renders as a solid beam of light
- **Infinity dimension** (unlocked at 3,000 RPM on all ten layers): collect Infinity Essence from void particles, link background constellations, and craft permanent Artifacts in the Infinity Forge
- **Reality dimension**: total inner RPM is converted logarithmically into Reality Torque for the outer Reality Ring, which pays Reality Shards per full rotation
- Challenges, an in-game manual, synthesised Web Audio sound, and autosave every 30 seconds

## How it works

The newest version went through a full bug and performance review, with 14 fixes. The most interesting ones:

- **An exponential runaway.** The Frictionless challenge reward multiplied ring torque by 1.2 every *frame* instead of once, so the game ran away within seconds of completing it.
- **Challenges that destroyed progress.** Starting any challenge permanently wiped artifacts and purchased auto-buys, because the profile snapshot taken before the challenge left those fields out.
- **Number formatting.** `Format()` broke at 1e21 and displayed 100k as "0.10M".
- **Dead effects.** The Shard Multiplier reward and two artifacts (Void Crystal, Infinity Loop) did nothing; achievements were never saved; and the particle spawner stacked thousands of `setTimeout`s.
- **Performance.** The UI was doing a full DOM refresh every frame, including 1,000-iteration max-buy simulations. It now refreshes 10 times a second, and the Infinity display updates once per tick instead of once per absorbed particle (around 150 times a frame).

## Tech

- One self-contained HTML file (inline CSS and JavaScript, no dependencies)
- HTML5 Canvas for the rings and particle effects
- Web Audio API for synthesised sound
- `localStorage` saves with validation and a save on page close

## Running it

Open `chrono-cycles/index.html` in any modern browser. No build step or server is needed.

Press `~` in game for the developer console.
