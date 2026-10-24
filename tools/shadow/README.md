# Shadow

Shadow is an AI-powered asset generation platform for HTML game developers, focused on creating high-quality 2D sprites, animation-ready frame data, and direct game-engine integration code.

This document is the working development blueprint for Shadow.

## Vision

Move from using third-party tools to owning the full asset pipeline:
- prompt-driven generation
- image-to-animation workflows
- masking and cleanup
- alignment and sprite sheet export
- direct copy/paste runtime code integration for HTML5 games

## Product Goal

Build a modular, scalable system where AI models and providers can be swapped without UI rewrites, while keeping the experience optimized for real-world 2D game production.

## Progress Snapshot (Current)

### Completed
- Core project scaffolding (`frontend`, `backend`, `docs`) is in place.
- FastAPI contract-aligned endpoints are live as MVP stubs.
- T2I flow works end-to-end in UI (submit, poll, gallery update).
- Gallery detail modal is implemented with preview, metadata, and actions.
- "Send to Animation" handoff and I2V mock generation flow are integrated.
- Handoff context now survives refresh via persisted local state.
- Sprite Export mock flow is integrated (video -> process -> export summary).
- T2I/I2V provider adapter layer is in place with mock providers wired through backend orchestration.
- Sprite processing does real frame extraction and keying via OpenCV. **The mock fallback is gone** —
  it used to be selected by a bare `except`, which returned a random stock photo as the sprite sheet
  and reported success (landmine 35). Set `SHADOW_SPRITES_MOCK=1` to choose it deliberately.
- Sprite frame selector is wired into export requests, and **fps now genuinely is too** — the snippet
  previously hardcoded 12 with no field anywhere on `SpriteProcessRequest` to influence it. It now
  defaults to the source clip's real frame rate.
- Sprite Export includes a mask preview, which now runs **the same keying code as the export** and
  finally honours `masking_mode` (it silently chroma-keyed regardless of the selection before).
- **The pixel pipeline is a pure, tested module** (`backend/app/spritekit.py`, 27 tests, 0.3 s, no
  video file or GPU needed): soft-edged alpha keyed in chromaticity so an unevenly-lit backdrop keys
  out cleanly, despill so edges stop compositing with a coloured rim, and motion-preserving alignment
  with a real computed pivot.
- **CorridorKey is implemented** (phase 3's automatic masking): median background estimate +
  chromaticity distance + morphology, no green screen required. Verified on a real 1080p clip with no
  keyable backdrop — 74% of the frame correctly classified as background.
- Sprite export panel now supports direct sheet/ZIP downloads and code snippet copy.
- Shadow prototype tab now includes split preview/editor plus local `.js` load/save.
- Desktop transition plan is documented in `docs/desktop-transition-plan.md`.
- SQLAlchemy + Alembic foundations are in place with initial schema migrated locally.
- Asset/job read paths are now DB-backed with runtime hydration and a JSON-to-DB sync utility.

- **The 2D pipeline is now reachable from the live desktop app** — a **Sprite Forge** tab (◫) in
  Studio, driving `scripts/sprites/sprite_pipeline.py` as a subprocess (the Asset Forge pattern), not
  the FastAPI backend. Same `spritekit` module either way. Verified over CDP in the running app:
  14/14, including a real clip forged through the live IPC. Masking controls, alignment modes, the
  pivot crosshair and the dropped-frames panel are all exposed here.
- **Dialogue is generated locally too** — a **Voice Forge** tab (◑) in Studio, driving IndexTTS2 for
  zero-shot voice cloning on the owner's GPU. Free, no API, no account. Its point is that timbre and
  performance are *separable*: the voice comes from a reference clip and the delivery from a second
  clip, eight sliders, or plain English (`"exhausted but resolute"`), so one recording yields a whole
  cast's worth of reads. Unlike the other forges its Python side is a **persistent worker** — 23.7 s
  to load the models against 5–8 s a line, so a barks file pays that once instead of thirty times.
  Verified over CDP in the running app: 26/26, including a real line synthesised through the live IPC.
  Needs a ~10 GB out-of-band install; the tab prints the commands. See `docs/voice-forge.md`.
- The `frontend/` web UI and `SHADOW_DESIGNER/` were both last touched in April and neither drives
  the current backend — `SHADOW_DESIGNER` never called it at all and carries its own third copy of a
  chroma keyer. Treat the Sprite Forge tab as the live surface.

### In Progress
- Replacing mock job behavior with real provider adapters and progressing from local persisted state to full DB models.
- Expanding I2V and sprite-processing pipeline toward production-ready outputs.
- Surfacing the new masking controls (softness, despill, luma_weight) and the `union` /
  `bottom_center` alignment modes in the Studio UI — the backend accepts them all; the UI still only
  sends key colour and tolerance.
- Showing `dropped_frames` in the export panel. The backend now reports which frames it could not use
  and why; nothing displays it yet, so a partial export still *looks* complete.

---

## Phase 1: Architecture and Technical Foundation

### Summary
Establish clean data flow between frontend, backend orchestration, database state, and external AI services before building advanced UX.

### Key Technical Decisions

#### Frontend (UI/UX)
- Use React or Next.js for SPA-level state complexity.
- Manage nested state for:
  - generation parameters
  - gallery selection and metadata
  - sprite editor and canvas interactions
- Use Tailwind CSS or Material UI to accelerate UI development.

#### Backend (API and Orchestration)
- Use Python with FastAPI or Django.
- Backend responsibilities:
  - receive and validate user prompts/settings
  - map app-level parameters to provider-specific API payloads
  - orchestrate asynchronous jobs
  - normalize and return results to frontend

#### Database (Persistence)
- Use PostgreSQL.
- Store:
  - users and projects
  - prompt history
  - generation settings
  - asset metadata (model, ratio, resolution, links)
  - workflow relationships (image -> video -> sprite sheet)

#### AI Provider Integration
- Start with external APIs (e.g., Replicate/OpenAI/other model endpoints).
- Implement a provider abstraction layer so UI and core app logic depend on internal interfaces, not provider-specific payloads.
- Ensure easy model/provider replacement later.

---

## Phase 2: Core Generation Pipeline (T2I -> I2V)

### Summary
Implement the core creative loop:
Text-to-Image generation -> gallery management -> send selected image into Image-to-Video generation.

### Implementation Steps

#### 1) Text-to-Image (T2I) Interface
- Build controls for:
  - model selection
  - aspect ratio
  - prompt
  - optional reference uploads
- Frontend sends a normalized generation request.
- Backend transforms request into provider-specific T2I API call.

#### 2) Image Gallery and Detail View
- Build gallery listing generated assets.
- Build detail view showing:
  - image preview
  - metadata
  - actions: reuse prompt, download, send to animation

#### 3) "Send to Animation" Orchestration
- On action click:
  - pass selected image ID + URL to video generation module
  - initialize I2V form state with that image as `reference_image`
- Preserve project context and metadata links.

#### 4) Image-to-Video (I2V) Pipeline
- Build I2V prompt/settings UI.
- Include settings modal for:
  - duration
  - resolution
  - aspect ratio
- Handle async job lifecycle:
  - submit job
  - poll provider for completion
  - update UI with job state and final video URL

---

## Phase 3: Sprite Animation Workflow (AutoSprite and Masking)

### Summary
Convert raw AI video output into transparent, game-ready animation frames and workflows.

### Implementation Steps

#### 1) Interactive Preview Canvas
- Use HTML5 `canvas`.
- Implement:
  - zoom
  - pan
  - optional background/grid preview
  - playback controls (play/pause/FPS/frame index)
- Maintain synchronized frame and playback state.

#### 2) Greenscreen Removal and Masking

##### CorridorKey (Automatic)
- Implement backend background subtraction/mask generation (provider feature or OpenCV pipeline).
- Generate alpha masks per frame.
- Expose mask preview in UI.

##### Classic Chroma (Manual)
- Implement user-driven color keying:
  - pick target color
  - adjust tolerance
  - preview transparency result in real-time

#### 3) Frame Editing
- Build frame list/selector panel.
- Support enabling/disabling frames to define animation loops.
- Reflect selection in playback and export pipelines.

---

## Phase 4: Sprite Analysis, Alignment, and Export

### Summary
Automate subject detection, frame alignment, sheet packing, and game-code output.

### Implementation Steps

#### 1) Subject Detection and Alignment
- For each masked frame:
  - detect non-transparent pixel bounds
  - calculate bounding box
- Build Auto Alignment modes:
  - Vertical Only
  - Horizontal Only
  - Both (Centered)
- Compute and store pixel offsets required for consistent animation placement.

#### 2) Sprite Sheet Packing and Asset Export
- Pack aligned frames into a sprite sheet image.
- Provide downloads for:
  - complete sprite sheet
  - ZIP of per-frame PNGs

#### 3) Code Export (Integrator)
- Generate HTML5 Canvas/JavaScript snippet from export metadata:
  - frame width/height
  - frame count
  - alignment offset
- Example integration pattern:
  - `ctx.drawImage(spritesheet, frameIndex * frameWidth, 0, frameWidth, frameHeight, x, y, frameWidth, frameHeight);`

---

## Phase 5: "Shadow" IDE Foundation

### Summary
Build the integrated coding environment where generated assets are consumed immediately in live gameplay development.

### Implementation Steps

#### 1) Multi-Agent Chat UI
- Build sidebar for model persona/provider selection.
- Support API key management for multiple LLM providers.

#### 2) Integrated Viewport
- Build split-pane layout:
  - top: live preview/runtime viewport
  - bottom: code editor
- Use Monaco Editor for code editing.
- Use `@react-three/fiber` or Phaser-React integration for live rendering context.

#### 3) Virtual File System
- Implement browser-based file management with:
  - File System Access API and/or browser FS library
- Enable direct save/load of project files (`.js`, `.html`, etc.) to local folders.

#### 4) "Sorceress" Asset Sync
- Create shared project asset store in database.
- Sync finalized assets and metadata from generator workflows to IDE explorer in near real-time.

---

## Suggested Cross-Phase Engineering Standards

- Build API contracts first (request/response schemas).
- Use background jobs for long-running AI tasks.
- Add retry/error-state UX for external provider failures.
- Keep every asset operation traceable through metadata lineage.
- Add integration tests for each handoff:
  - T2I -> Gallery
  - Gallery -> I2V
  - I2V -> Masking
  - Masking -> Alignment
  - Alignment -> Export
  - Export -> IDE sync

## Naming

Application name: **Shadow**

## Next Implementation Milestone

Start with Phase 1 plus the Phase 2 T2I vertical slice:
- one provider
- one generation form
- one gallery
- one "Send to Animation" action

This gives Shadow a usable end-to-end skeleton quickly while preserving long-term modularity.
