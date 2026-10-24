# Cosmic Forges — master build spec (for AI agents and contributors)

> Copy of the canonical build prompt. See [AGENTS.md](../AGENTS.md) for the stable project index.

## Role

You are a senior game engineer + software architect. Build a **browser-native** (HTML + JS/TS) game that *feels* like conquering and industrializing space at impossible scales, with **3D camera and units** where it matters, and **2D/abstract UI** for management. The design must be **modular, testable, and expandable** (content packs, simulation layers, plugin-style systems).

## Non-negotiable engineering

1. **AGENTS.md** (repo root): Maintain a living index of: project purpose, folder map, build/run (`npm run dev` etc.), test commands, **simulation invariants** (what must never desync), **save format version** and migration notes, and "agent checklist" (what to read before changing X).
2. **Modularity**: Clear boundaries:
   - `core/` — fixed timestep game loop, event bus, RNG contracts, id schemes, save/load.
   - `sim/` — pure logic: resources, production chains, power, logistics graphs, AI factions, **abstract scale layers** (no rendering).
   - `render/` — Three.js (or Babylon.js) **world view** + separate **UI** layer (DOM or canvas 2D for overlays). Rendering reads sim state; sim never imports Three.
   - `content/` — data-driven: JSON/TOML/TS objects for buildings, items, biomes, enemies, tech, dimensions; **no** hardcoded planet types in game logic.
   - `workers/` (optional) — heavy pathfinding, chunk generation, long tick batches off the main thread.
3. **Expandability**: New mechanics = new `System` class registered in the loop + optional content defs. Favor **composition** over inheritance for entities.
4. **Scale strategy (performance)**: You cannot "simulate the galaxy" as Factorio simulates belts. Use **tiered fidelity**:
   - **Tactical** (3D or top-down local): a planet surface / moon / station where belts, combat, and building placement run.
   - **Strategic** (map / graph): star systems as nodes, fleets as edges or icons; abstract production ("output per hour" not per belt).
   - **Meta** (galaxy / cluster): aggregated stats, fog of war, procedural seeds, "claims" and megastructures.
   - **Transcendent** (universe / multiverse / dimension): **narrative + numeric ascension** + new rulesets (e.g. alternate physics constants as modifiers), not real-time N-body for everything.
5. **Determinism / multiplayer-ready**: Seeded RNG per layer, actions as ordered commands, replayable tick log (even if v1 is single-player only).

## Game vision: progression layers (unlocks, not all at once in v1)

Player advances through **jurisdictions of scale** in order, each adding systems:

1. **Planet** — land, resource nodes, build grid or free placement, local biters/aliens, pollution, defense.
2. **Solar system** — multiple bodies, orbits as simplified timing (not full physics), stations, transfer routes, light-delay optional flavor.
3. **Galaxy** — sectors, FTL lanes, empires, megastructures (Dyson scaffolds, ring worlds as abstract or instanced 3D set pieces).
4. **Universe** — branes, constants tuning, "rewrite laws" as endgame tech with costs.
5. **Multiverse** — parallel runs / alternate timelines as branches (prestige or parallel colonies with trade-offs).
6. **Dimensions** — new coordinate topologies, enemy ecology, and resource types; visual shader shifts in 3D (not a second full game).

## Factorio-style pressure (enemies and logistics)

- **Waves and evolution**: Enemies scale with *player footprint* (pollution, energy signature, interstellar industry) and **time**.
- **Strategic raiders** at larger scales: pirate fleets, elerium leeches, dimensional breaches—not only ground bugs.
- **Logistics as gameplay**: belt-like throughput on planet; **spacelanes** and **quantum couriers** at higher tiers; use **bottlenecks and blackouts** as fail states.
- **Rebuilding after loss** must be fun: blueprint ghosts, insurance caches, emergency beacons.

## 3D presentation (browser constraints)

- Use **WebGL** via **Three.js** (simpler ecosystem) or **Babylon** (heavier, strong tooling). Default: **Three.js** + `EffectComposer` for post, **InstancedMesh** for swarms.
- **3D for**: planet/moon surface sectors, key megastructures, battle vignettes, dimension transitions.
- **Not 3D for**: most menus, full galaxy map (use stylized 3D points or 2D map + parallax). Avoid per-frame full simulation of millions of entities in JS; **LOD + merge + culling** mandatory.
- **Controls**: orbit camera, WASD pan on surface, snap/build mode, **pause and speed** (1x–64x) with deterministic sim.

## UX pillars

- **Clarity at chaos**: one primary next goal; alerts with priority; "what broke production?" dashboard.
- **Satisfying loops**: place → power → smelt → science → better defense → push frontier.
- **Readable art direction**: low-poly or stylized PBR, strong silhouettes, color-coded biomes and threats.

## Content depth ideas (pick for MVP subset)

- Tech trees with **soft gates** (not pure linear).
- Factions: trade, war, subversion.
- Hazards: solar flares, void storms, dimensional corrosion.
- Win conditions (optional): stabilize multiverse, defeat Primordial faction, or infinite score.

## Phased delivery (do not build everything in v1)

- **Phase 0**: Prototype — one 3D test scene, one resource, one buildable, one enemy wave, save/load, AGENTS.md.
- **Phase 1**: One **planet** loop + surface combat + **pollution** + tech + basic logistics.
- **Phase 2**: **Solar system** abstract layer + two bodies + space routes.
- **Phase 3**: **Galaxy** map + fog + empire AI stub.
- **Phase 4+**: Universe / multiverse / dimension as **meta** layers + content packs.

## Quality bar

- TypeScript, eslint, unit tests for `sim/`, at least one integration test for save round-trip.
- Performance budget: 60 FPS on mid-range laptop on Phase 1 scene with conservative entity counts; degrade gracefully.
- License-friendly assets: Kenney, ambientCG, or procedural placeholders; document sources in [CREDITS.md](CREDITS.md).

## What to deliver first

Repository skeleton + AGENTS.md + `core` loop + `sim` tick + one `render` scene + one vertical slice. Then iterate phases.

When uncertain, **prefer data-driven design and pluggable systems** over special cases.
