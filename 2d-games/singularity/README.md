# The Singularity Chamber

A physics idle game: balls ricochet around a shrinking chamber, every wall hit pays out, and speed is both your income and your risk.

The Singularity Chamber starts with one ball bouncing inside a box. Each hit earns cash and plays a note pitched to the ball's speed; consecutive hits build a combo multiplier. Spend cash on more balls, faster balls, a contracting chamber and a gravity well that pulls everything to the centre, then prestige into Dark Matter for permanent upgrades. Push the balls too fast and they risk vaporising. The folder also contains a second game, **Zombies Incremental: Protocol Zero**, plus archived earlier versions of both.

## Features

- Up to 200 balls with ball-to-ball collisions, wall hits that pay out, critical hits with shockwaves, and a combo bar that decays
- A destructible cube that periodically spawns in the centre and bursts for a bonus
- Cash upgrades: Splitter (add a ball), Elasticity, Wall Crunch (contract the chamber), Gravity Well, Railgun, Cold Fusion (cash on ball collisions), No Friction, Quantum Tunnel and a timed Overclock
- **Burnout**: above a speed threshold, each ball has a chance to vaporise, which turns velocity upgrades into a trade-off
- Dark Matter prestige: each point adds 10% to every hit payout, and buys permanent upgrades such as Matter Stabilizer, Genesis Module, Replicator (auto-buy) and Time Dilation
- Procedural audio, a low-graphics mode and `localStorage` saves

## How it works

I went through both games in a bug-fix pass. The fixes in Singularity were the more interesting ones:

- **A crash on the core mechanic.** Hitting the cube called a `spawnParticle()` function that did not exist, freezing the game.
- **Prestige only worked once.** A `resetting` versus `isResetting` typo broke every prestige after the first and also left the audio permanently muted.
- **Pause doubled the game speed.** Each unpause started another `requestAnimationFrame` loop on top of the existing one.
- **A runaway multiplier.** A `state.` versus `this.` mix-up let the gravity payout multiplier grow without limit.
- **Frame-rate-dependent physics.** The simulation ran per frame, so it was 2.4 times faster on a 144 Hz display. It now uses delta time.
- **Saves.** Loading now survives corrupted JSON and missing fields, re-derives upgrade effects that were previously lost on load, and restores the ball count.

In Protocol Zero, auto-advanced bosses spawned with one twelfth of their HP (level loading overwrote the boss stats), and killing a boss, or skipping with Warp Drive, wiped the next sector's saved progress.

## Tech

- Single-file HTML games: inline CSS and vanilla JavaScript, Canvas2D, Web Audio
- No dependencies, no build step

## Running it

Open `Singularity.html` (or `Zombies Take 2.html` for Protocol Zero) in any modern browser.

`Singularity_broken.html` and `Zombies incremental.html` are earlier versions kept as archives.

**Status:** both games are playable. Protocol Zero has no save system yet.
