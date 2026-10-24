# Space Engineers Lite

A browser block-builder in the spirit of *Space Engineers*: build stations and ships from blocks, fly them with thrusters, and walk on rotating, orbiting planets with real terrain.

Space Engineers Lite puts a small solar system in a browser tab. You spawn in a spacesuit with a jetpack, build grids out of blocks on large (2.5 m) or small (0.5 m) grid sizes, and anything with a cockpit can become a ship. Planets have per-planet gravity, atmospheres, spin and orbits, and noise-displaced terrain you can walk on. A separate universe editor lets you design your own systems and launch them from the start menu.

## Features

- Blocks: armor, beams, glass, thrusters, wheels, landing gear, batteries, lights, drills, cockpits and a gravity generator, each with its own mass
- Static grids (stations) and dynamic grids (ships); sitting in a cockpit converts a station into a ship
- Ship flight with per-direction thrust from the thrusters you placed, power draw, inertial dampeners and parking
- A spacesuit with a jetpack and suit dampeners, plus a head lamp; on foot you walk with planet gravity
- A default system with a sun, Terra, Luna, Marsus, Jupiterus, Saturnus and Neptunus; terrain styles for terra, rocky, barren, ice, gas and moon worlds
- Rotating planets with atmospheres you co-rotate with, the way *Space Engineers* couples you to a spinning world
- **Universe editor**: add bodies, set parents, orbits, spin and axial tilt, preview orbit lines, undo, copy and paste subtrees, and save to a local library that the start menu loads
- A dev menu (`M`) with live tuning sliders

## How it works

- **Gravity from every nearby body.** `getGravityInfo` sums gravity from each body within four radii and tracks the strongest one for "down". The distance is clamped near the core (to 0.92 of the radius), which removes an inverse-square blow-up that had produced accelerations above 1e8 m/s^2.
- **Terrain that agrees with the mesh.** Noise lives in the planet's local space and the surface query rotates back into world space, so walking on a spinning planet lines up with the rotated mesh.
- **Bugs fixed in a review pass.** Structures built on mountains were wrongly treated as dynamic and fell, because the static test used the base planet radius while Terra's relief reaches about +7 km; the same bug silently un-anchored hillside stations whenever a block was removed. Zero-length normalisations that cascaded `NaN` through physics and the solar system are now guarded, and damping that depended on frame rate now uses `pow(base, dt * 60)` so it behaves the same at 30, 60, 120 or 144 fps.
- **Performance.** Per-frame allocations were removed from the gravity, collision and terrain hot paths in favour of scratch objects, about 15 unconditional DOM writes per frame became compare-before-write, the build ghost no longer leaks GPU resources, and the editor's orbit rings are rebuilt only when they change instead of per body per frame.

## Tech

- Vanilla JavaScript ES modules (about 3,500 lines across 15 modules), no build step
- three.js r169 via an import map from unpkg
- Universe files as versioned JSON with validation and normalisation, stored in `localStorage`

## Running it

ES modules need `http://`, not `file://`. From the project folder:

```
python -m http.server 8080
```

Then open `http://localhost:8080/startmenu.html` (on Windows, `launch.bat` does both). An internet connection is needed for the three.js CDN.

Controls: WASD to move, Space/C up and down, Q/E roll, X jetpack, Z dampeners, G block library, 1-0 hotbar, R/T/Y rotate the block, B grid size, F to enter or leave a cockpit, P park, L head lamp.

`spacegame.html` is the earlier single-file version the modular game was built from, kept as an archive.

**Status:** playable sandbox. Raycasting against the full planet mesh every frame is the next performance target (it needs a BVH).
