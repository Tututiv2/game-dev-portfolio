# Shadow Desktop Transition Plan (Option A)

This plan keeps current web MVP velocity while guaranteeing a clean desktop app path after core workflows are stable.

## Goal

Ship MVP as web-first, then package Shadow into a native desktop application with minimal rework.

## Why Option A

- Fastest path to validated product workflows.
- Lower risk while AI pipeline logic is still changing.
- Desktop shell is added after API/workflow contracts stabilize.

## Target Desktop Experience

- Launch from local machine like a native app.
- Keep local project and asset folder access.
- Start/stop local Shadow services from app UI.
- Reuse existing React frontend and Python backend.

---

## Architecture Direction

Recommended stack for desktop phase:
- **Shell:** Tauri (preferred for lighter footprint) or Electron (fallback).
- **UI:** existing `frontend/` React app.
- **Backend:** existing `backend/` FastAPI service.
- **Worker:** existing Python job workers.

Desktop shell responsibilities:
- package UI build
- manage backend process lifecycle
- expose file-picker and local folder permissions
- handle auto-update and native install behavior

---

## Readiness Rules Before Packaging

These should be true before desktop implementation begins:
- [ ] T2I -> I2V -> sprite export happy path is stable.
- [ ] API contracts are mostly frozen.
- [ ] Asset storage abstraction exists (not hardcoded to one environment).
- [ ] Error states are normalized and user-visible.
- [ ] Local dev startup is one command per service.

---

## Phase D1: Desktop Compatibility Prep (while web MVP continues)

- [ ] Keep frontend API base configurable via env.
- [ ] Keep backend host/port configurable.
- [ ] Avoid browser-only APIs in core business modules.
- [ ] Add explicit storage interface boundary (`local`, `s3`, future `desktop-local`).
- [ ] Add health endpoint checks for shell startup.

Output: web codebase stays desktop-ready without branching.

## Phase D2: Shell Scaffold

- [ ] Create `desktop/` project (Tauri preferred).
- [ ] Load built frontend bundle inside desktop window.
- [ ] Add command to spawn backend process on app launch.
- [ ] Add graceful shutdown of backend when app exits.
- [ ] Add startup screen that waits for backend `GET /health`.

Output: Shadow opens as a native app and talks to local backend.

## Phase D3: Local Files and Assets

- [ ] Add native folder picker for project directory.
- [ ] Map selected directory to Shadow project context.
- [ ] Save exports directly into user-selected folder.
- [ ] Add "Open export folder" action in UI.
- [ ] Add permission-safe defaults per OS.

Output: desktop-native workflow feels like a real production tool.

## Phase D4: Packaging and Distribution

- [ ] Build signed installers (Windows first).
- [ ] Add release channel strategy (alpha/beta/stable).
- [ ] Add app versioning + migration checks.
- [ ] Add crash/error logs with opt-in diagnostics.
- [ ] Add update mechanism and rollback guidance.

Output: installable Shadow desktop releases.

---

## Risk Register

- Python runtime bundling complexity across OSes.
- ffmpeg/OpenCV binary packaging details.
- Local permissions and antivirus false positives.
- Update flow can break backend/frontend version compatibility.

## Mitigations

- Lock backend API version contract before packaging.
- Pin and verify binary dependencies in CI.
- Add compatibility check between shell version and API version.
- Stage rollout with alpha builds before broad release.

---

## Implementation Trigger

Start desktop packaging when:
- MVP checklist P0 items are complete, and
- at least one full export-to-game integration demo is stable.

Until then: keep building web-first while preserving desktop-ready boundaries.
