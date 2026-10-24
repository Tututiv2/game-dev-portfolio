# Project Neon: Warlord Update

A 2D mobile-base tower defence: drive your core across a neon grid, deploy on ore, and build before the heat brings the hives down on you.

You are the base. The core drives around a large grid map looking for Mass and Tech deposits. Press Space to deploy it, then build walls, extractors and turrets around it. Extractors are your income, and they also raise your heat signature: the more you drill, the more attention you draw from the hives scattered across the map. When it gets too hot, pack up and move. This single-file game is also the design source for my 3D remake, Neon Nomad 3D, which takes the same core loop onto a real planet.

## Features

- A mobile core that deploys (Space) and packs up to relocate
- Two resources: Mass near the start and Tech in the far reaches of the map (beyond 8,000 units from the centre)
- Build menu: walls, extractors, and three turrets (cannon, gun, missile)
- A research panel (`T`) that unlocks the gun and missile turrets with Tech
- A heat signature bar driven by active drills, plus a heat visualisation overlay (`H`)
- Enemy hives that spawn waves which attack your structures
- Optional sprite art with vector fallbacks for every structure

## How it works

I did a debugging and performance pass on this game, and the fixes are the interesting part:

- **The game froze on its first frame.** It referenced sprite files that did not exist, and `drawImage` on a broken image threw every frame, so the game stopped immediately after DEPLOY CORE. Drawing is now guarded and falls back to vector art, and the PNGs are picked up automatically if they are added.
- **Enemies never attacked turrets.** The obstacle check tested for type `'turret'`, which no entity has (they are `cannon`, `gun` and `missile`). Fixing it means turrets can now be attacked, as their HP bars always implied. The game is harder as a result.
- **Frame-rate-dependent speed.** Delta time was computed and then discarded, so everything ran per frame and the game played 2.4 times faster on a 144 Hz monitor. It now runs on a fixed 60 Hz accumulator timestep.
- **Smaller fixes.** Pressing Space re-clicked the focused DEPLOY button and stacked a second game instance; the canvas never resized with the window; the mousedown handler was being recreated 60 times a second; and extractor income did an O(2,400) search per drill per tick, now cached.

## Tech

- One self-contained HTML file (about 940 lines of HTML, CSS and vanilla JavaScript)
- Canvas2D rendering, class-based entities (`Turret`, `Enemy`, `Projectile`, `HeatSystem`, `TechTree`)

## Running it

Open `game.html` in any modern browser. No build step or server is needed.

**Status:** playable prototype. Hives have HP but cannot be destroyed yet, and the mobile core is invulnerable by design.
