# Cosmic Forges

A browser-native industrial space game: build on a planet, defend it from waves, then fly off it toward any star in the sky.

Cosmic Forges is a factory and survival sim written in TypeScript, with a deterministic simulation core and Three.js used only where 3D earns its place. You place extractors, purifiers, relays and sentries on a world, manage pollution as waves of enemies respond to your footprint, and climb a tech ladder. Above the surface, the sky is not wallpaper: every one of its 21,000 stars has a real position in a light-year-scale galactic chart, and you can pin one and fly toward it.

## Features

- Surface building: extractors, purifiers, tier-gated relays and sentries, with ore, science, pollution and tech tiers
- Timed enemy waves on the surface, plus aerial hostiles and deep-orbit space waves
- Space flight from the surface into deep orbit, FTL travel between sectors, a solar-system view and a 2D galaxy map
- 21,000 skydome stars (9,000 main plus 12,000 dense) that are real catalog stars: click one to pin it, and the HUD shows the true distance as you close in
- Versioned saves (currently v3) with migrations from v1 and v2
- An in-game Dev panel for live tuning of the skydome and the system map's three-level LOD

## How it works

- **The simulation is pure and deterministic.** `src/core/` and `src/sim/` never import Three.js; rendering lives entirely in `src/render/` and is not allowed to mutate `GameState`. All randomness goes through a `SeededRng` whose state is saved, so the same state and the same command sequence replay identically tick for tick. New mechanics are small `*System` modules registered in `tick.ts`.
- **Distances in O(1).** The player's position is tracked in light-year space, and each star's position is `anchor + direction * shellRadius` (4 to 120 ly). The label distance is a plain Euclidean distance in that chart, so the game never has to simulate or load another sector to tell you how far away it is.
- **Multiplayer is designed in, not bolted on.** The plan is one authoritative `GameState` per session with clients loading only their own instance. Other players are lightweight replicated state, never a second procedural world.
- **Fixes from a review pass.** The fixed-step loop had a spiral of death: after one lag spike or hidden tab, the backlog grew without bound and the sim stepped at its maximum forever. `WorldView` rebuilt every building and enemy as a new mesh, geometry and material every frame without disposing them, an unbounded GPU leak, now replaced by pooled per-entity meshes with shared resources. The dynamic star catalog had never actually been built (a flag was set before the seed existed), so star labels and approach scaling silently did nothing. All 26 tests, the type check and the linter pass after the fixes.

## Tech

- TypeScript, Vite, Three.js r170
- Vitest (26 tests across sim, saves, universe generation, galactic space and star picking)
- ESLint with typescript-eslint

## Running it

`node_modules` and `dist/` are not included, so install first:

```
npm install
npm run dev        # http://localhost:5173
npm test           # Vitest
npm run build      # typecheck + production build to dist/
```

**Status:** early. The vertical slice (ore, extractors, waves, core HP, deterministic tick, save/load) and the Phase 1 systems are in; the larger scale layers (megastructures, galaxy-and-beyond progression) are design only.
