# Shadow Designer - Step 1 Requirements Audit

This is the Step 1 baseline for aligning Shadow Designer with the root `README.md` product expectations.

## 1) Source of Truth

Primary spec:
- `D:/projects/Shadow/README.md`

Current implementation files reviewed:
- `D:/projects/Shadow/SHADOW_DESIGNER/main.js`
- `D:/projects/Shadow/SHADOW_DESIGNER/preload.js`
- `D:/projects/Shadow/SHADOW_DESIGNER/index.html`
- `D:/projects/Shadow/SHADOW_DESIGNER/renderer.js`

## 2) README Requirements Most Relevant to Shadow Designer

From Phase 3, 4, and 5 sections in `README.md`, the app should support:

1. Interactive preview canvas:
   - zoom, pan, grid/background preview
   - playback controls (play/pause/FPS/frame index)
2. Sprite cleanup workflow:
   - automatic masking/chroma pipeline integration
   - manual chroma controls with real-time preview
3. Frame editing workflow:
   - frame list/selector
   - enable/disable frames for loop definition
4. Alignment and export flow:
   - per-frame bounds/alignment
   - sprite sheet export and frame ZIP export
   - code export snippet for HTML5 Canvas integration
5. IDE-style workflow:
   - split layout (viewport + code editor)
   - file operations and local project workflow
   - near real-time asset-to-editor handoff

## 3) Current App Capability (Today)

Current Shadow Designer is a lightweight HTML wrapper that provides:
- choose/clear/reload single `.html` file
- drag/drop `.html` into window
- open-with HTML argument support
- single-instance app behavior

Missing almost all production behaviors expected by `README.md`:
- no integrated timeline/frame controls
- no masking controls
- no export pipeline
- no code integration panel
- no project/asset orchestration inside the app UI

## 4) Gap Summary

The current app is a launch shell, not a full Shadow production workflow UI.

To satisfy README expectations, Shadow Designer must evolve from:
- "HTML container"

into:
- "Sprite workflow workstation" with dedicated panels, state, and export actions.

## 5) Step 1 Completion Criteria

Step 1 is complete when:
- README requirements are mapped to implementation areas (done in this document)
- missing features are explicitly identified (done)
- next implementation slices can be executed without ambiguity (done)

## 6) Locked Scope for Step 2

Step 2 implementation target (next action):
- Replace current minimal shell UI with a product-aligned workspace frame:
  - left panel: project/assets/frame list
  - center: preview canvas area
  - right panel: controls (playback, mask/chroma, export)
  - bottom: code/export snippet panel
- This step builds structure and UX flow first (real processing hooks can be mocked initially).

---

Step 1 status: COMPLETED
