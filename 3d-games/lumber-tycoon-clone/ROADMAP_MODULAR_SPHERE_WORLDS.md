# Roadmap: modular spherical worlds, ships, and progression

**Status:** design + phased implementation plan (not implemented yet).  
**Goals:** Option A — **true spherical planets**; **modular** code/data so future features plug in without rewriting core; shared **money**; **home + expedition** planet loop; **ship** built from blueprints with **seats / interior**; **stockpiles** for anchored wood in transit; **map editor** per planet; **save migration**; multiplayer kept **same planet per room** until later.

---

## 0. Modularity principles (apply to every layer)

These rules keep the codebase extensible as planets, ships, and modes multiply.

1. **Single responsibility modules (new files where possible)**  
   - `sphere-physics.js` — gravity field, surface queries, “up” vector, polar/tangent helpers.  
   - `world-registry.js` — `worlds[]`, `planetId`, active world, load/unload hooks, asset group roots.  
   - `ship-rig.js` — interior frame, seat occupancy, velocity sync for passengers vs hull.  
   - `stockpile-system.js` — hover + E, serialize plank/log dimensions, spawn static anchored replica.  
   - `travel-space-mode.js` — low/zero G, orbit vs landing transition volumes (optional second phase).  
   Keep `game.js` as orchestration: init, main loop, wiring — not all algorithms inline.

2. **Stable interfaces (minimize cross-file globals)**  
   Prefer a small facade, e.g. `window.LumberWorlds = { getActive(), setActive(id), forEachPlanet(...) }` or ES exports imported by `game.js`, instead of 50 functions on `LumberGame`.

3. **Data-driven over hard-coded**  
   Move `STORE`, Bob, clerk, sell zones, plot sites, and “second planet shop + teleporter” into **planet/world JSON** or `world-defs.js` keyed by `planetId`. `game.js` constants become defaults only.

4. **Feature flags / phases**  
   Each major capability behind a flag or explicit phase completion so you can ship “multi flat + warp” before “full space between planets” if needed.

5. **Deterministic save slices**  
   Each subsystem saves a **named slice** (`economy`, `worlds[planetId].dynamic`, …) with version fields for migrations.

---

## 1. Data model: `worlds[]` + `planetId`

**Decision:** `worlds[]` in map/save with **`planetId`** (string, stable, URL-safe) on entities that belong to a world.

### 1.1 Map editor export shape (target)

```json
{
  "version": <number>,
  "worlds": [
    {
      "planetId": "home",
      "label": "Home",
      "sphereRadius": 2500,
      "spawn": { "lat": 0, "lon": 0, "height": 1.7 },
      "sellSpots": [{ "x": 0, "y": 0, "z": 0, "r": 8 }],
      "plotSites": [],
      "trees": [],
      "props": [],
      "stores": [],
      "teleporters": []
    }
  ],
  "defaultPlanetId": "home"
}
```

- **Flat legacy map** (current `LT2_MapEditor` single blob): migration treats entire export as `worlds[0]` with `planetId: "home"` and optional `sphereRadius` from default.  
- **Editor UX:** dropdown “Active planet for editing”; export includes **all** worlds or “export active only” toggle.

### 1.2 Runtime in memory

- `worldRegistry.activePlanetId`  
- Per planet: Three.js `Group` root, Cannon bodies registered to that world’s static/dynamic sets (or tagged `userData.planetId`).  
- **Unload (client):** remove planet group from scene, dispose geometries/materials where owned, remove Cannon bodies for that planet from `world` (or move to a disabled island — see physics layer).

### 1.3 Spherical placement

- Authoring: **lat, lon, height above surface** (or **tangent frame** position relative to a chunk origin).  
- Runtime: convert to Cartesian in planet root space; **never** assume global `y` is up except inside legacy/local UI helpers.

---

## 2. Layer A — Spherical planet foundation (hardest core)

### A.1 Gravity (Cannon)

- Each dynamic step (or substep): for each body with `affectedByPlanetGravity`, set  
  `acceleration = G * normalize(planetCenter - body.position)`  
  (tune `G`; may differ per `planetId`).  
- **Sleeping:** disable or reduce custom gravity on sleeping bodies carefully so they don’t sink; align with Cannon sleep.

### A.2 Player controller

- **Up** = smoothed **surface normal** (from sphere raycast or heightfield sample).  
- Movement: project desired move onto tangent plane; handle **stepping** along curved surface (small spherecasts / snap to surface).  
- **Camera:** yaw/pitch in camera space; optional **roll** tied to horizon or locked upright for comfort (setting).  
- **Jump:** velocity += up * jumpSpeed (up from normal).

### A.3 Ground / static colliders

- **MVP:** giant **sphere shell** (inner radius = playable surface) with **inward** normals, or **sphere + noise** mesh as trimesh (expensive — start with coarse mesh, refine).  
- **Later:** chunked LOD mesh, heightfield in spherical coords.  
- Trees/props: spawn with orientation **aligned to normal** at (lat, lon).

### A.4 Logs, conveyors, chops

- Same gravity field as player.  
- **Stability:** damp angular velocity on logs when linear speed low; cap max spin; consider **CCD** for fast collisions.  
- Conveyors: belt direction = tangent × (local belt axis); verify **−local Z** world direction still correct when conveyor mesh is oriented to surface.

### A.5 Performance

- Broadphase: single world vs **islands** per planet (if multiple planets loaded — usually only **one** loaded at a time for gameplay).  
- **LOD:** distance in **surface arc length** or chord distance from camera.  
- Sleep distant dynamic bodies.

**Deliverable:** one planet id, walkable sphere, chop one tree, log rolls/slides believably, sell still works in tangent patch (temporary flat sell pad on sphere).

---

## 3. Layer B — Space between planets (second wave)

- **Low / zero G** region: separate movement integrator or kinematic **ship hull** moves world-space; player relative to ship interior (see Layer C).  
- **Transition volumes:** entering “orbit” reduces planet gravity influence, switches to ship-relative or free-flight controls.  
- **Freecam mid-flight:** allowed; define whether freecam is **world-fixed** or **ship-fixed** (recommend world-fixed camera pose, optional follow ship toggle).

**Deliverable:** two spheres in scene (or one loaded + one placeholder); fly path in between; no full economy — proof of transition.

---

## 4. Layer C — Ship from blueprints + seats + interior (no “lag out” at speed)

### C.1 Blueprint pieces (design)

- Extend blueprint catalog with **ship parts**: hull module, **seat**, **cockpit**, wall, floor, **stockpile pad**, thruster mount, etc.  
- Construction rules: same overlap-feed system or **snap grid** inside ship — decide per part category (document in `blueprint-library.js` metadata: `category: 'ship' | 'structure'`).

### C.2 Seat / cockpit enter

- **Enter seat (E):** switch player to **seated mode**: camera parented to seat, inputs drive **ship** entity (rotation/thrust).  
- **Cockpit-only path:** single mesh cockpit with no interior — still one `ShipRig` root for motion.

### C.3 Walking inside moving ship (relative motion fix)

**Problem:** world-space velocity mismatch when hull moves fast — passenger slides out.

**Approach (modular):**

- **`ShipRig` root** (`THREE.Group` + optional `CANNON.Body` for hull): hull velocity `vHull`, angular `ω`.  
- **Interior frame** child of hull: player **when inside** uses:
  - kinematic follower body **parented logic**: player position updated each frame as `worldPos = hull.localToWorld(localPos)` and velocity `v = vHull + ω × r` for physics handoff, **or**
  - **single compound** approach: interior colliders on hull body (complex).  
- Prefer **interior local coordinates** for player physics: simulate in hull rest frame with **fictitious forces** (centrifugal, Coriolis) when accuracy matters; for prototype, **kinematic interior** + smooth snap each frame may suffice for walking.  
- **Grabbed objects:** when crossing interior ↔ exterior airlock, transfer frame (explicit event `onExitShipInterior`).

**Deliverable:** ship accelerates; player walks in cabin without exponential drift; optional debug HUD shows relative vs world velocity.

---

## 5. Layer D — Stockpile (anchored storage, E to store)

### D.1 Behavior

- **Hover** carried plank/log over stockpile volume (ray + overlap test).  
- **E:** serialize **size + weight** (and `logSkin`, netId strip for save): dimensions from geometry params or mesh bounds; mass from Cannon body if dynamic.  
- **Spawn static replica** on stockpile slot (anchored `CANNON.Body` type STATIC or KINEMATIC parented to ship/planet root during travel).  
- Remove or consume original dynamic body from world.

### D.2 Persistence

- Stockpile contents in save under `worlds[planetId].stockpiles[]` or `ships[shipId].stockpiles[]` depending on whether stockpile is **world-fixed** or **on ship**.  
- Use stable **slot ids** inside blueprint instance.

**Deliverable:** one stockpile blueprint on ground + one on ship; save/load round-trip.

---

## 6. Layer E — Progression & economy (your spec)

- **Money:** global `economy.money` (single wallet), **not** duplicated per planet.  
- **Home vs expedition:** second planet = **new tree types** to harvest and bring back (inventory or log positions in ship stockpile / cargo).  
- **Plots:** second planet gets `plotSites` in map; **land store** instance near plots (data-driven NPC position).  
- **Adjacent store:** sells **teleporter** linking **plot A ↔ plot B** (same planet or cross-planet — you said “other plot”; define as `teleporter: { targetPlanetId, targetPlotSiteId }`).  
- **Sell zones:** per planet in map JSON (`sellSpots[]`); “behind the shops” is editor placement.

---

## 7. Layer F — Map editor upgrades

- Planet selector; load/switch active planet slice.  
- Export/import `worlds[]` + migration from legacy single-world JSON.  
- Visualize **sphere** (wireframe) and **surface normal** at cursor for placement.  
- Gizmo/conveyor rotation tools in **tangent space** (may need helper “align to surface” button).

---

## 8. Layer G — Save / load migration

- Bump `SAVE_KEY` internal `version`.  
- **`currentPlanetId` / `lastPlanetId`:** where player last was; on load, spawn at last or default.  
- **`worlds: { [planetId]: { trees, logs, sawmills, plotBlueprints, landState, stockpiles, … } }`** — pick one nesting style and stick to it.  
- **Player pose per planet:** `poses[planetId] = { x,y,z, q, yaw, pitch }` in **world-local** or spherical coords (document).  
- **Blueprint rows:** include `planetId` (or nest under world slice).  
- **`migrateSaveVnToVm`:** old save → `worlds.home` = entire previous blob; `planetId` on dynamics inferred as `home`.

---

## 9. Layer H — Multiplayer (phased)

- **Phase 1 (simplest):** same PeerJS room = **same `planetId`**; host authoritative; **no warp** while connected (or disconnect on warp).  
- **Phase 2:** host tracks `planetId` per client; sync subset of bodies; explicit “unsupported” UI if client tries cross-planet until implemented.

---

## 10. Layer I — UI / starmap

- **Starmap:** list or 3D dots; **distance** between planet centers (or between orbit radii) **computed from current world defs** so moving a planet in data updates UI automatically.  
- **Planet card:** name, **gravity hint** (e.g. “0.9g”), biome icon, resource teaser.  
- **Travel:** confirm screen if unloading current world dynamics.

---

## 11. Layer J — Content & vibes

- **Neon trees:** new procedural materials / `tree-procedural-branches` variants; `planetId` or `biomeId` in tree spawn data selects palette.  
- **Per-planet blueprint catalog:** either `blueprint-library.js` split by `export const CATALOG_BY_PLANET = { ... }` or `blueprints/home.json` merged at load — keep **one merge function** so game code stays dumb.

---

## 12. Layer K — Dev & QA

- Dev menu: **“Warp to planet X”** (sets active world + pose on sphere).  
- Manual checklist per planet template: sell, land buy, blueprint complete, sawmill, conveyor, chop, enter ship seat, stockpile E, save F, reload, multiplayer smoke.  
- Optional later: tiny automated test page (headless hard) — low priority.

---

## 13. Suggested implementation order (multi-layer plan)

| Phase | Name | Outcome |
|-------|------|--------|
| **P0** | **World registry + data** | `worlds[]`, `planetId`, editor selector, migrate map JSON; runtime swap roots **without** sphere physics yet (optional: flat placeholder per planet). |
| **P1** | **Sphere core** | `sphere-physics.js`, player on big radius sphere, gravity toward center, static ground collider, one tree type. |
| **P2** | **Gameplay port** | Chop, logs, grab, conveyors, sell, Bob/plots **data-driven** per planet; money global. |
| **P3** | **Save v2** | Nested `worlds`, poses per planet, migration from old save. |
| **P4** | **Stockpile module** | E-store, static replicas, save slice. |
| **P5** | **Ship rig + seat** | Hull velocity, interior walk, cockpit/seat enter/exit. |
| **P6** | **Blueprint-built ship** | Catalog + construction rules for ship parts. |
| **P7** | **Second planet content** | New trees (neon), shop + land + teleporter item. |
| **P8** | **Space between** | Orbit / low-G mode, second sphere, transitions. |
| **P9** | **Multiplayer planet rules** | Same-room planet lock + UI. |
| **P10** | **Polish** | LOD, audio per biome, starmap distance, unload assets. |

Dependencies: **P1** before **P2** for true sphere; **P3** early if you don’t want to redo dynamics twice. **P5** can start after **P1** on a test hull without blueprints.

---

## 14. Open decisions (resolve before coding subsystems)

1. **Single Cannon world vs multiple worlds** merged with offsets vs one world with all planets far apart.  
2. **Spherical coordinates in save** vs Cartesian + migration on load.  
3. **Teleporter** cross-planet vs same-planet only for v1.  
4. **Ship interior physics** fidelity vs kinematic snap (quality bar).  
5. **Freecam** world vs ship-attached for nausea / debugging.

---

## 15. File / module checklist (when implementation starts)

- [ ] `world-registry.js` — worlds[], active planet, load/unload  
- [ ] `sphere-physics.js` — gravity, normal, placement helpers  
- [ ] `world-defs.js` or merged map JSON — STORE/Bob/sell per planet  
- [ ] `ship-rig.js` — hull, interior, seat  
- [ ] `stockpile-system.js`  
- [ ] `travel-space-mode.js` (later)  
- [ ] `map-editor.js` / export — planet picker + `worlds[]`  
- [ ] `game.js` — thin integration  
- [ ] `save-migrate.js` — versioned migrations  
- [ ] `blueprint-library.js` (or split) — ship + stockpile defs  
- [ ] `neon-tree-settings.js` / tree spawn — biome hooks  

---

*Last updated: roadmap capture from design discussion; update this file as phases complete or decisions change.*
