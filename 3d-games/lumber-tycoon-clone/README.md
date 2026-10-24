# Lumber Tycoon Clone

A 3D first-person lumber sandbox in the browser: chop trees, haul logs, mill planks, buy land, and build, with physics, peer-to-peer multiplayer and a set of in-browser editors.

Inspired by Roblox's *Lumber Tycoon 2*, this is a physics sandbox built on Three.js and cannon-es. You fell procedurally generated trees with an axe, carry and drop the logs, feed them through sawmills that turn them into planks, sell wood at the store's dock, and spend the money on land plots and shop items. It grew past a single map: worlds can be spherical planets with their own radial gravity, and the project ships four editors (map, prefab, universe and space background) that write the same JSON the game loads.

## Features

- First-person movement with pointer lock, chopping, grabbing and carrying logs, and a fly mode in the dev menu
- LT2-style procedural trees with branches, bark and cut-interior textures, tunable live through named presets
- Sawmills that convert logs to planks inside a conversion zone, conveyors that push along their local axis, and plank-fed building blueprints
- An economy: sell wood in the store's sell ring, buy land plots from an NPC, and buy shop items at the counter
- Spherical planets with multi-planet radial gravity and per-planet content, a deterministic star catalog for the night sky, and selective neon bloom
- **Editors**: a map editor (place trees, props, conveyors, plots and the sell zone with gizmos, multi-select, copy/paste and undo), a prefab editor for the meshes of trees, conveyors, shelves and ramps, a universe editor for arranging planets, and a live space-background editor with presets
- PeerJS multiplayer: a host creates a room, clients join by id, and player poses and log states sync with no dedicated server
- Versioned saves with migration from the original flat format to per-planet slices

## How it works

- **Map data versus prefab data.** The map editor owns placement (position, full rotation and scale of every instance); the prefab editor owns geometry. The game combines both at spawn time, so restyling a conveyor never moves one, and new prop kinds plug in through a single tool definition plus a spawn branch.
- **Modular planets on top of a large core.** `game.js` is about 11,700 lines, so the spherical-worlds work was planned as a phased roadmap with new logic in named modules (`sphere-physics.js`, `world-registry.js`, `world-defs.js`, `save-migrate.js`, `map-world-format.js`) and `game.js` only wiring them together. Legacy single-world maps migrate into a `worlds[]` array under a stable `planetId`.
- **Performance fixes from a review pass.** The bloom setting was being read from `localStorage` and JSON-parsed every frame; sawmill containment allocated two `Matrix4`s per log, per sawmill, per frame; and hidden sawmills on other planets each ran an 8,400-particle fog simulation invisibly. All three are fixed, along with per-frame clones in camera, ray, hover and plank math.
- **Bugs fixed in the same pass.** An undefined variable in the map editor crashed loading any saved map with terrain (and planet switching, import and undo), an unguarded save could throw mid-game on a full storage quota, and the tree-config preview leaked window listeners every time it opened.

## Tech

- JavaScript ES modules with an import map (no build step)
- Three.js r160, cannon-es physics, PeerJS, all loaded from CDNs
- Canvas-generated textures; one axe model by Starzama (Thingiverse), credited in `models/`

## Running it

The game must be served over HTTP; opening `index.html` from `file://` can break the import map. Any static server works, for example:

```
npm install
npm start            # serves on http://localhost:5173
```

or `python -m http.server`. Open `index.html` for the game; the editors are `map-editor.html`, `prefab-editor.html` and `universe-editor.html`, also linked from the in-game dev menu (backtick). An internet connection is needed for the CDN libraries.

**Status:** a working prototype. Multiplayer is best-effort sync rather than authoritative, and parts of the spherical-worlds roadmap (buildable ships with interiors, stockpiles for wood in transit) are still to come.
