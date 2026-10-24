# Multiplayer — scope and loading rules

Multiplayer is **a project goal**, not a stretch feature. Titles like **Space Engineers** stay playable in MP by separating **authoritative simulation**, **per-session worlds**, and **tight interest management** (nobody’s client simulates or streams the whole universe). This project can follow the same *ideas* in a smaller stack: **TypeScript sim + browser clients + a dedicated or listen-server process** that runs ticks and replicates only what each client needs.

## What we borrow from “good” sandbox MP (e.g. Space Engineers)

| Idea | In Space-like games | In Cosmic Forges (direction) |
|------|---------------------|-------------------------------|
| **Authoritative sim** | Server owns physics/build state; clients predict or interpolate | One **server (or host) authoritative `GameState` per world session**; clients apply snapshots/deltas, render locally |
| **One world session** | You join a server/world, not the entire galaxy in one sim | A **replicated `GameState` / instance id** per MP session; travel changes instance, not “sim all sectors at once” |
| **No distant load** | Distant players/grids are low detail or not simulated | **No full load of other players’ stars/planets** (see below); only **your** `systemSeed` + body get full 3D/economy |
| **Small replication sets** | Position, grid state, not full voxels for everyone | **Player/core/build IDs + transform + economy flags**; procedural content stays **seeded from server-stored sector/system** so clients don’t need assets for regions they’re not in |
| **Dedicated / listen** | Optional dedicated server for stability | Vite/ browser for client; **Node (or similar) for headless tick** is a natural fit for “Space Engineers style” later |

**Bottom line:** Multiplayer is achievable here if we **never** require each browser to run full sims for worlds they aren’t in, and we keep **replication and loading** tied to a **single active instance** per client (plus a shared model when you’re literally in the same place as someone else).

---

The sim and renderer are already built around a **single authoritative `GameState` per client** in single-player; networking extends that to **one authoritative state on the server per session**, with clients receiving only what’s relevant.

## What “don’t load their planets/stars” means

- **You only load and simulate one “presence” at a time** — the star system / body / surface tied to **your** `UniverseState` (`systemSeed`, `galaxyX` / `galaxyY`, `bodyIndex`, and derived visuals like `surfaceVisualSeed` from the hash helpers).
- **Other players** are not represented by full copies of *their* procedural solar map, *their* skydome seed, or *their* planet mesh in your process unless you are explicitly in a **shared instance** (same server shard / same visit) and the design calls for it.
- In the common case, remote users are **lightweight replicates**: sector or coarse position, name, state flags, ship pose — not a second `WorldView` or `SolarView` for “everyone’s home star.”

## Concrete rules (implementations must follow)

1. **No eager procedural gen for remote homes**  
   Do not call `starAtSector` / body catalog / `WorldView` rebuild for another player’s `(galaxyX, galaxyY)` or `systemSeed` just because they’re online. If the UI needs a label (e.g. “Player X — Sector 12,34”), use **text from server state** or a tiny icon, not a full 3D load.

2. **One loaded instance per client (default)**  
   `applyUniverseStyle`, skydome rebuild, and solar surface are driven by **local** `GameState` only. A future `joinInstance(id)` (or similar) may swap that state; until then, never mount parallel Three.js scenes for other sessions.

3. **Visiting another system** is a **travel** action  
   If the game later allows “warp to friend,” that becomes *your* `tryWarpTo` / instance handoff: you **unload** the previous system’s render state and **load** the destination — still one world at a time, not two.

4. **Determinism**  
   `systemSeed` and procedural pipelines stay seed-driven; the server (or host) should be authoritative for which `systemSeed` / sector you’re in so all clients in the same instance agree, without every client precomputing everyone else’s sky.

5. **Network payload**  
   Prefer sending **instance id + small player vectors** over sending mesh assets or full planet state for players who aren’t in your current scene.

## Galactic distance on star labels (huge, cheap)

- Procedural “stars” you pick in the skydome have a **fixed position** in a **very large (ly) 3D chart** and your ship holds **`gpos` in ly** in the same space — label distance is **true Euclidean** separation in that chart, computed in **O(1)** (no N-body, no loading other sectors). `src/sim/universe/galacticSpace.ts` is the source of truth. The 3D view remains a local patch; the chart is a separate layer for *range readout* and travel accounting.

## Relation to “scene origin shift”

Floating-origin tricks apply **per instance** in your own loaded space. You do not need a single shared world origin across all players in the universe — each client’s scene is for **their** current instance only, which matches the “don’t load their stars” rule.
