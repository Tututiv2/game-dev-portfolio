# Execution plan — spherical worlds (agent checklist)

**Use this file as the ordered task list.** Design rationale and data shapes live in `ROADMAP_MODULAR_SPHERE_WORLDS.md`. Do not implement features not listed below.

**Rules**

1. Complete steps in **phase order** (P0 → P10). Do not skip a phase unless its **Done when** is already satisfied on `main`.
2. New logic goes in **named modules** from the roadmap (`world-registry.js`, `sphere-physics.js`, etc.); `game.js` wires imports and calls only.
3. After each phase: **save F**, reload, run that phase’s **Done when** checks.

**Blockers — resolve before coding the listed phase**

| Decision | Required before |
|----------|-----------------|
| Single Cannon `World` vs distant planets in one world | P1 |
| Save position: spherical vs Cartesian (document choice in code) | P3 |
| Teleporter: same-planet only vs cross-planet for v1 | P7 |
| Ship interior: kinematic snap vs fictitious forces | P5 |
| Freecam: world-fixed vs ship-fixed default | P5, P8 |

---

## P0 — World registry + map data (`worlds[]`, `planetId`)

### P0.1 Module shell

1. Add `world-registry.js` exporting: `createWorldRegistry()`, `getActivePlanetId()`, `setActivePlanetId(id)`, `getPlanetRoot(planetId)`, `registerPlanet({ id, threeRoot, unload })`, `unloadPlanet(planetId)` (no-op stub OK until P10).
2. Import from `game.js`; construct one registry at init; expose minimal facade on `window.LumberWorlds` **only if** `game.js` already uses that pattern for other systems — otherwise export-only.

**Done when:** Build loads; no gameplay change yet.

### P0.2 Map JSON shape

1. Define version field for map export (increment from current map editor version).
2. Implement **read path**: if JSON has `worlds[]`, use it; else **migrate** legacy object into `worlds: [{ planetId: 'home', ...legacyFields }]`.
3. Implement **write path** (editor): export `worlds[]`; legacy single-world export removed or behind “compat export” if you must keep one release.

**Done when:** Loading `LT2_MapEditor` legacy string still spawns identical layout under `planetId: 'home'`.

### P0.3 Map editor — planet selector

1. UI: dropdown of `planetId` / label for **active editing planet**.
2. Switching selection loads that world’s slice into the editor state (trees/props/plotSites/sellSpots for that slice only).
3. “Add planet” creates new entry with new `planetId` and empty arrays.

**Done when:** Two planets in one JSON; switching edits different content; export round-trips.

### P0.4 Runtime active planet (flat)

1. On game start: set `activePlanetId` from `defaultPlanetId` or first world.
2. Spawn only **active** world’s map content under that world’s `THREE.Group` root (child of scene).
3. Tag spawned bodies/meshes `userData.planetId = activePlanetId`.

**Done when:** Switch `activePlanetId` in dev (temporary hardcode or console) swaps visible geometry; physics still flat Y-down until P1.

---

## P1 — Sphere core (`sphere-physics.js`)

### P1.1 Constants and helpers

1. Add `sphere-physics.js`: `planetCenter`, `sphereRadius`, `gravityStrength` per active planet (from map world entry).
2. Export: `radialUp(worldPos)`, `projectOntoTangent(velocity, up)`, `surfacePointFromLatLon(lat, lon, height)`, `snapToSurface(worldPos, up)`.

**Done when:** Unit-level sanity (manual console calls or tiny dev assert): up points away from center.

### P1.2 Cannon gravity integration

1. Remove reliance on `world.gravity` as sole downward force for flagged bodies OR set gravity to zero and apply custom acceleration each `world.step` for `userData.affectedByPlanetGravity !== false` dynamics.
2. Player body included.

**Done when:** Dynamic box above “ground” falls toward center; no runaway at sleep (verify sleeping bodies).

### P1.3 Static collider MVP

1. Trimesh or large segmented **inner** sphere collider at `sphereRadius` (inward normals correct for Cannon).
2. Align planet `THREE.Group` origin with Cannon planet center.

**Done when:** Player walks on inner surface without falling through (tune step/substeps if needed).

### P1.4 Player controller + camera

1. Movement input projected on tangent plane using `radialUp(playerBody.position)`.
2. Jump adds velocity along `up`.
3. Camera: existing pointer-lock yaw/pitch; roll **configurable** (default locked upright unless roadmap setting added).

**Done when:** Walk 360° around sphere without drift; jump lands.

### P1.5 One tree on sphere

1. Spawn one test tree at fixed `(lat, lon)` with mesh+bark aligned to surface normal.
2. Chop produces one log; log uses same gravity flags.

**Done when:** Chop → log moves toward center then rests on surface; no console errors.

---

## P2 — Gameplay port (data-driven, still one active planet)

### P2.1 World defs

1. Add `world-defs.js` (or embedded in each `worlds[]` entry) for **per planet**: `STORE`-equivalent box, Bob, clerk, `sellSpots[]`, any `plotSites` references.
2. Replace hardcoded `STORE` / Bob / clerk **reads** with `getActiveWorldDef()`; keep numeric defaults for `home` matching current game so unmigrated maps behave the same.

**Done when:** Sell, Bob interaction, shop clerk work with defs from JSON for `home`.

### P2.2 Systems tagged by `planetId`

1. Conveyors, sawmills, plot blueprints, dynamic trees/logs: only spawn/update/query entities whose `userData.planetId === activePlanetId`.
2. Conveyor belt direction: verify world **−local Z** when conveyor is oriented to tangent frame.

**Done when:** Existing `home` map features behave as before on flat migration path; on sphere P1 collider, conveyors still push.

### P2.3 Money

1. Confirm single `money` variable (or `economy.money`) — **not** duplicated per planet; no code path subtracts “planet wallet.”

**Done when:** Grep shows no per-planet money fields introduced.

---

## P3 — Save v2 + migration

### P3.1 Schema

1. Bump save `version` in `game.js` (or dedicated `save-migrate.js`).
2. Add `currentPlanetId` (or `lastPlanetId` — pick one name, use consistently).
3. Nest dynamics: `worlds[planetId].trees`, `.logs`, `.sawmills`, `.plotBlueprints`, etc. — match nesting promised in roadmap; **one** style only.
4. Add `poses[planetId]` for player spawn when returning.

**Done when:** New save round-trip preserves two planets’ data independently.

### P3.2 `migrateSaveVnToVm`

1. Old flat save → all existing arrays/objects live under `worlds.home`.
2. `currentPlanetId = 'home'` if missing.
3. `poses.home` from legacy `player` if present.

**Done when:** Load old `LT2_Clone_Save` from disk backup; no throw; gameplay continues on home.

### P3.3 Blueprint rows

1. Each saved blueprint row includes `planetId` **or** lives only under `worlds[id].plotBlueprints` — consistent with P3.1.

**Done when:** Blueprint on expedition planet survives save reload on that planet.

---

## P4 — Stockpile (`stockpile-system.js`)

### P4.1 Detection + input

1. While player holds grab on plank/log, overlap test vs registered stockpile volumes (from blueprint instances or props).
2. **E**: if overlap valid, run store.

**Done when:** One manual stockpile zone works on home.

### P4.2 Serialize + static replica

1. Record geometry dimensions + mass + `logSkin` (and any id needed for multiplayer later).
2. Remove dynamic body from world; spawn **STATIC** (or kinematic parented) replica at slot transform.
3. Slot transform stable per stockpile instance.

**Done when:** Replica does not move under sphere gravity or small impulses.

### P4.3 Save

1. Persist stockpile contents under the same nesting as P3 (`worlds[id].stockpiles` or `ships[id]` — pick **one** for this phase; ship path can alias later).

**Done when:** Save/load restores replicas exactly.

---

## P5 — Ship rig + seat (`ship-rig.js`)

### P5.1 Minimal hull

1. `ShipRig`: `THREE.Group` + one `CANNON.Body` (or kinematic shell) with velocity/thrust API.
2. Interior child group for local coordinates.

**Done when:** Dev key can thrust hull in world space while empty.

### P5.2 Seat enter/exit

1. **E** near seat: player enters **seated mode** (inputs route to hull); camera parented to seat node.
2. **E** again (or Esc per existing pause rules): exit to standing in interior local pose.

**Done when:** Enter/exit without physics explosion.

### P5.3 Interior walking at velocity

1. Implement the choice from **Blockers** (kinematic snap vs fictitious forces).
2. Player world pose derived from `hull.localToWorld(localPos)` each frame while inside; velocity matches hull at handoff.

**Done when:** Hull moves fast; player does not slide out over 60s test; grabbed item rules documented (drop or snap).

---

## P6 — Blueprint-built ship

### P6.1 Catalog

1. Extend `blueprint-library.js` (or `CATALOG_BY_PLANET` merge) with ship-part entries: hull, seat, cockpit, stockpile pad, etc. Metadata: `category: 'ship'` where needed.

**Done when:** Dev can place ship parts via existing blueprint construction flow or documented alternate if construction differs.

### P6.2 Assembly rules

1. Document per-category construction (overlap feed vs snap); implement minimum for **one** buildable ship layout.

**Done when:** Player-built ship matches P5 hull API (seat drives same `ShipRig`).

---

## P7 — Second planet + progression content

### P7.1 Map + editor

1. Second `worlds[]` entry: new trees (see P7.2), `plotSites`, land NPC, adjacent shop selling **teleporter**, extra `sellSpots` behind shops.
2. Editor can author all of the above on that `planetId`.

**Done when:** Warp (dev) to second planet shows correct NPCs/zones from defs.

### P7.2 Trees

1. New tree variants / **neon** materials hooked by `planetId` or `biomeId` in spawn data.
2. Harvested wood distinguishable for “bring home” loop (existing carry/grab or stockpile — use what P4 provides).

**Done when:** Chop on planet B yields expected mesh; return to A with cargo still valid.

### P7.3 Teleporter item

1. Item definition + purchase flow (same shop pattern as existing items).
2. Use **Blockers** choice for same-planet vs cross-planet link; implement `targetPlanetId` + `targetPlotSiteId` in saved placed teleporter instance.

**Done when:** Use teleporter → correct plot on target world.

---

## P8 — Space between planets (`travel-space-mode.js`)

### P8.1 Low/zero G region

1. Define volume(s) between planet centers (or explicit trigger meshes).
2. Inside volume: reduce or zero radial gravity; apply space movement mode (separate from tangent walk).

**Done when:** Player can traverse from planet A surface to B surface without stuck in infinite fall.

### P8.2 Transitions

1. Enter/exit orbit/landing mode switches control scheme and gravity flags.
2. **Freecam** remains usable mid-flight per spec (no regression vs P1 camera module).

**Done when:** Freecam on during flight does not break ship interior state machine.

---

## P9 — Multiplayer planet rules

### P9.1 Phase 1 only

1. Host and client **same `planetId`**; if either warps, **disconnect** or hard-block with UI message (pick one, document in `multiplayer.js` / UI).
2. No cross-planet body sync in this phase.

**Done when:** Smoke test: two clients chop logs on same planet; warp attempt behaves as documented.

---

## P10 — Polish + unload + UI

### P10.1 Client unload inactive planet

1. `unloadPlanet(id)`: remove Three subtree, dispose owned assets, remove Cannon bodies tagged `planetId`.
2. Call when switching `activePlanetId` (and before load of next).

**Done when:** Memory stable over 10 warp cycles (manual devtools snapshot optional).

### P10.2 LOD / sleep

1. Distance metric documented (chord or arc); reduce tree/proxy cost for far chunks.
2. Tune sleep for distant dynamics.

**Done when:** Acceptable FPS on target machine with both planets’ content authored (stress map).

### P10.3 Starmap UI

1. List worlds from registry + map; show **computed distance** between planet centers from defs.
2. Card fields: name, gravity hint, biome icon, resource line (static strings from def OK).

**Done when:** Open map → correct data for two-planet fixture.

### P10.4 Audio

1. Hook per-planet ambient/def (optional one-shot switch on `setActivePlanetId`).

**Done when:** Switching planets changes ambient if assets exist; no error if missing.

### P10.5 Dev menu

1. “Warp to planet X” sets `activePlanetId`, runs unload/load, places player using `poses[id]` or spawn point.

**Done when:** Same as manual test in P0.4 but via UI.

### P10.6 Manual QA checklist

Run on **home** and **second** planet templates:

- [ ] Sell  
- [ ] Land buy  
- [ ] Blueprint complete  
- [ ] Sawmill  
- [ ] Conveyor  
- [ ] Chop + log  
- [ ] Ship seat enter/exit  
- [ ] Stockpile E store + reload  
- [ ] Save / load / F  
- [ ] Multiplayer smoke (P9 rules)

---

## Appendix — phase dependency graph

```
P0 ──► P1 ──► P2
        │
        └──► P5 ──► P6 ──► P8
        │
P3 ◄── ideally early after P2 stable (can parallel P4 with P2 if saves isolated)

P4 after P2 (needs grab + world ids)
P7 after P2 + P3 + P4 + P6 (content depends on mechanics)
P9 after P2 (minimal); tighten after P7/P8 if needed
P10 after P0 unload API exists; full pass after P7
```

---

*Update this file only when phases complete or acceptance criteria change.*
