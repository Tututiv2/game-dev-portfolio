# Sprite Editor Regression Checklist

Run this checklist after any changes to the editor, exporter, or mission rendering paths.

## Automated

- Run `npm run test:sprite-editor`
- Run `node --check game.js`

## Manual Editor Checks

- Open `Sprite Pipeline Editor` from main menu.
- Create a new project and save it.
- Import a PNG sprite sheet.
- Try each import preset:
  - `Single Row Frames`
  - `ChatGPT Sheet (rows = anims, cols = frames)`
  - `Direction Rows (rows = dirs, cols = frames)`
- Select `idle` animation and set at least 2 frames in one direction.
- Enable `Onion-skin previous frame` and `Ghost next frame`, verify preview overlays.
- Enable `Symmetry mode`, drag the vertical axis guide in preview, and verify mirrored direction updates.
- Use `Ctrl+Z`, `Ctrl+Y`, and `Ctrl+Shift+Z` to verify undo/redo stack behavior.
- Use arrow keys (and `Shift`+arrow) to nudge active frame rect and verify updates.
- Edit collision footprint fields and confirm rectangle updates in preview.
- Save to a snapshot slot and restore from it.
- Run `Run Validation` and confirm errors/warnings render.
- Run `Run Smoke Test` and confirm result card updates.
- Export atlas + manifest and confirm export readout shows deterministic paths:
  - `data/generated/<slug>.png`
  - `data/generated/<slug>.json`
- Click `Register Runtime Asset` and verify runtime status card updates.

## Manual Runtime Checks

- Enter any mission and spawn/select units.
- Confirm:
  - If runtime sprite exists for unit type, sprite draws.
  - If no runtime sprite exists, iso-diamond fallback still draws.
- Validate command and movement systems still work:
  - selection
  - move commands
  - attack commands
  - mission pause/save/load

## Save/Load and Persistence Checks

- Save sprite project, reload page, load sprite project, ensure metadata persists.
- Export assets, reload page, ensure runtime sprite registry can be reloaded.
- Save mission runtime, reload mission runtime, ensure no crash with runtime sprite hooks.
