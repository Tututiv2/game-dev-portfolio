# Shadow MVP Checklist

This checklist is designed for execution, not theory. It prioritizes the shortest path to a usable end-to-end product while keeping architecture modular for later expansion.

## MVP Definition

Shadow MVP is complete when a user can:
- generate an image from a prompt (T2I)
- view/select it in a gallery
- send that image into video generation (I2V)
- run basic masking + frame selection
- export a usable sprite sheet and copy HTML5 integration code

---

## 0) Project Setup and Guardrails (Day 0-2)

### Repo and Tooling
- [x] Create app workspace structure:
  - [x] `frontend/`
  - [x] `backend/`
  - [x] `docs/`
- [x] Initialize frontend app (React or Next.js).
- [x] Initialize backend app (FastAPI preferred for MVP).
- [ ] Add lint/format config:
  - [ ] frontend (ESLint + Prettier)
  - [ ] backend (ruff/black or equivalent)
- [x] Add `.env.example` for both frontend and backend.
- [ ] Define local dev script strategy (`npm run dev`, backend run command).

### Baseline Engineering Standards
- [ ] Create shared naming conventions doc.
- [ ] Define API error format (`code`, `message`, optional `details`).
- [ ] Define async job state model (`queued`, `running`, `succeeded`, `failed`).
- [ ] Add first CI pipeline:
  - [ ] lint
  - [ ] type checks
  - [ ] unit tests (minimum smoke tests)

### Definition of Done
- [ ] One-command local startup path documented.
- [ ] CI runs and passes for a clean branch.
- [ ] New contributors can clone and run in under 15 minutes.

---

## 1) Data Model and API Contracts (Day 2-4)

### Database (PostgreSQL)
- [x] Create initial schema:
  - [ ] `users` (or placeholder single-user mode)
  - [x] `projects`
  - [x] `assets` (image/video/frame/spritesheet)
  - [x] `generation_jobs`
  - [x] `asset_relations` (image -> video -> sheet lineage)
- [x] Add migrations strategy (Alembic or equivalent).
- [x] Create indexes for project and job queries.

### API Contracts
- [ ] Define OpenAPI-first contracts for:
  - [ ] `POST /t2i/generate`
  - [ ] `GET /assets?project_id=...`
  - [ ] `POST /i2v/generate`
  - [ ] `GET /jobs/{id}`
  - [ ] `POST /sprites/process` (mask + alignment + export start)
  - [ ] `GET /exports/{id}`
- [ ] Define request/response schemas with strict validation.
- [ ] Define stable frontend-facing model IDs and metadata format.

### Definition of Done
- [x] DB schema migrated locally.
- [x] API routes stubbed and documented.
- [x] Frontend can call stubs without breaking.

---

## 2) T2I Vertical Slice (Day 4-8)

### Backend
- [x] Implement provider adapter interface:
  - [x] `generate_image(prompt, settings) -> normalized result`
- [ ] Implement one provider adapter (MVP only).
- [ ] Persist job + asset metadata.
- [ ] Return normalized asset response with image URL and metadata.

### Frontend
- [x] Build T2I form:
  - [x] prompt input
  - [x] model selector (single provider option is okay for MVP)
  - [x] aspect ratio selector
  - [x] submit/loading/error states
- [x] Show generation status and final image result.
- [x] Save generated results into project gallery view.

### Definition of Done
- [x] User can submit prompt and get image reliably.
- [x] Failure states show actionable messages.
- [x] Generated image appears in gallery with metadata.

---

## 3) Gallery and "Send to Animation" (Day 8-10)

### Gallery UX
- [x] Build asset grid with selection state.
- [x] Build detail drawer/modal:
  - [x] preview
  - [x] metadata
  - [x] actions (download, use prompt, send to animation)

### Handoff Orchestration
- [x] Implement `Send to Animation` action:
  - [x] send selected `asset_id`
  - [x] pass image URL/reference into I2V state
- [x] Persist lineage relation in backend (`source_image_id` on I2V job).

### Definition of Done
- [x] One click from image detail initializes I2V form.
- [x] Handoff survives refresh (stored in local persisted state for MVP).

---

## 4) I2V Async Pipeline (Day 10-14)

### Backend
- [x] Implement I2V provider adapter interface.
- [x] Submit async generation jobs (mock progression).
- [x] Add polling endpoint/job status updates (mock progression).
- [x] Persist final video asset and metadata (local persisted backend state for MVP prototype).

### Frontend
- [x] Build I2V form with:
  - [x] prompt
  - [x] duration
  - [x] resolution
  - [x] aspect ratio
  - [x] reference image preview
- [x] Implement in-app job status polling with cancellation timeout handling.
- [x] Render final video in gallery/detail.

### Definition of Done
- [x] I2V jobs complete end-to-end with visible progress.
- [x] Failed jobs are clearly reported and retryable.
- [x] Video output is linked to source image.

---

## 5) AutoSprite MVP (Mask + Frames) (Day 14-20)

### Processing Pipeline
- [x] Decode video into frame sequence (OpenCV path with fallback).
- [x] Implement one masking mode first (choose one):
  - [ ] CorridorKey automatic, or
  - [x] Classic Chroma manual color key
- [x] Produce transparent PNG frames (OpenCV real path; mock fallback available).

### Editor UX
- [x] Build frame scrubber/selector.
- [x] Implement enable/disable frame toggles.
- [x] Implement playback with FPS control.
- [x] Show mask preview mode toggle.

### Definition of Done
- [ ] User can process video into transparent frames.
- [x] User can curate loop by disabling bad frames.
- [x] Playback preview reflects selected frames only.

---

## 6) Alignment + Sprite Sheet Export + Code Snippet (Day 20-24)

### Backend Export Logic
- [x] Detect non-transparent bounding boxes per frame.
- [x] Implement auto alignment mode (Centered/Both for MVP).
- [x] Pack frames into one sprite sheet.
- [x] Generate:
  - [x] sprite sheet PNG
  - [x] optional ZIP of individual frames
  - [x] metadata JSON (frame size/count/offsets)

### Code Export
- [x] Generate copyable HTML5 Canvas snippet using metadata.
- [x] Include configurable animation speed and loop logic.

### Frontend
- [x] Add export panel with:
  - [x] download sheet
  - [x] download frames ZIP
  - [x] copy code button

### Definition of Done
- [ ] Exported sprite sheet renders correctly in sample HTML5 canvas demo.
- [ ] Copied snippet runs with no manual edits besides file path.

---

## 7) Shadow Foundation (Thin MVP) (Day 24-28)

### Minimal IDE Shell
- [x] Add split layout:
  - [x] code editor pane (textarea MVP; Monaco upgrade pending)
  - [x] preview pane (simple HTML5 canvas runtime)
- [x] Load exported sprite metadata into preview sample.
- [x] Add basic file save/load (local-only is fine for MVP).

### Asset Sync (MVP)
- [ ] Show exported assets in IDE asset panel.
- [ ] Support selecting sprite sheet to auto-generate preview boilerplate.

### Definition of Done
- [ ] User can export in Shadow and immediately preview animation in Shadow pane.

---

## 8) QA, Reliability, and Release Gate (Day 28-30)

### Critical Test Matrix
- [ ] T2I success + provider failure + timeout
- [ ] Send-to-animation state integrity
- [ ] I2V success/failure and stale job recovery
- [ ] Masking edge cases (near-background colors, noisy backgrounds)
- [ ] Alignment accuracy with varying subject motion
- [ ] Export correctness (dimensions, frame count, offsets)

### Non-Functional Checks
- [ ] Ensure no API keys leak to frontend.
- [ ] Add request rate limits/server-side guardrails.
- [ ] Add asset size limits and validation.
- [ ] Add cleanup strategy for failed/incomplete jobs.

### Release Gate (Must Pass)
- [ ] End-to-end happy path demo recorded.
- [ ] Top 10 failure cases verified with acceptable UX messaging.
- [ ] No blocker/high-severity bugs open.
- [ ] README + setup docs accurate for a fresh machine.

---

## MVP Backlog Priorities (Strict Order)

- [ ] P0: T2I generate + gallery
- [ ] P0: Send image to I2V + async status
- [ ] P0: Frame extraction + one masking strategy
- [ ] P0: Sprite sheet export + code snippet output
- [ ] P1: Better alignment controls (vertical/horizontal-only modes)
- [ ] P1: Manual frame trim UX polish
- [ ] P1: Shadow minimal live preview integration
- [ ] P2: Multi-provider model routing
- [ ] P2: Advanced editor tools and collaborative workflows

---

## Immediate Next Actions (Start Tomorrow)

- [ ] Lock stack decisions: Next.js vs React SPA, FastAPI vs Django (recommend FastAPI for MVP).
- [ ] Scaffold frontend/backend repos and run both locally.
- [ ] Write API schema stubs for T2I, I2V, jobs, exports.
- [ ] Implement T2I adapter with one provider and test prompt-to-image path.
- [ ] Build first gallery page and wire real backend response.

If all five actions are completed, Shadow has a concrete execution base and can move into rapid feature iteration without architectural churn.
