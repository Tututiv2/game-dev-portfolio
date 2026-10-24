# Stronghold Crusader 2.5D (Prototype)

This is a browser-first prototype inspired by **Stronghold Crusader** core loops:

- Isometric tile map
- Economy chains (`source -> process -> product`)
- Popularity and population logic
- Auto worker assignment from campfire
- A* pathfinding for peasant movement

## Run It (Fastest)

Open `index.html` directly in your browser.

No install needed.

## Optional: Run With NPM

If you want local hosting and a base for multiplayer later:

1. Install Node.js.
2. In this folder, run:
   - `npm install`
   - `npm start`
3. Open `http://localhost:3000`

`npm start` here launches a tiny Node server (and websocket endpoint) so you can extend toward realtime multiplayer.

## Sprite Editor

- Open the main menu and launch `Sprite Pipeline Editor`.
- The editor saves to local storage, validates directional animations, enforces naming-pattern tokens, supports import presets, snapshot restore points, onion-skin/ghost preview, draggable symmetry axis guides, and exports deterministic runtime paths under `data/generated/*`.
- Keyboard workflow in editor:
  - `Ctrl+C` copy active frame metadata
  - `Ctrl+V` paste active frame metadata
  - `Ctrl+Z` undo
  - `Ctrl+Y` or `Ctrl+Shift+Z` redo
  - Arrow keys nudge frame rect (`Shift` for 8px steps)
- Symmetry mode mirrors frame edits across a vertical axis (configured as percent of sheet width).
- Run smoke checks with:
  - `npm run test:sprite-editor`
- Starter seamless prototype textures live in `data/generated/textures`.
- Additional production texture sources are listed in `docs/texture-sources.md`.

## Item Creator

- Open main menu and launch `Item Creator`.
- Build simple models from primitive parts (`box` + `cylinder`) with a large centered 3D viewport, transform tools, background color, and wireframe option.
- Save/load projects in local storage and export the item model as JSON for runtime tooling.

## Next Up

- Wall/gatehouse passability rules and choke-point combat
- Flow field movement for large armies
- Scenario editor validation and terrain overlays
- Multiplayer desync diagnostics + reconnect flow
- Campaign mission authoring for `m7` and `m8` tuning passes
