# Shadow Architecture (MVP)

This document defines the implementation architecture for the first shippable version of Shadow.

## 1) System Overview

Shadow is split into four major layers:
- **Frontend App (React/Next.js):** generation UI, gallery, editor canvas, export UX
- **Backend API (FastAPI):** request validation, orchestration, job lifecycle, asset metadata
- **Worker Pipeline (Python jobs):** long-running AI calls, frame processing, sprite exports
- **PostgreSQL + Object Storage:** relational metadata and binary asset storage

## 2) Core Design Principles

- **Provider-agnostic orchestration:** all external model APIs are hidden behind adapters.
- **Async-first for generation:** any T2I/I2V job is treated as asynchronous.
- **Lineage tracking:** every derived asset keeps a source relationship.
- **Deterministic exports:** sprite sheet metadata must always reproduce runtime behavior.
- **Frontend stability:** UI uses normalized internal schemas, not raw provider payloads.

## 3) High-Level Data Flow

1. User submits T2I form.
2. Backend creates `generation_job` and queues worker task.
3. Worker calls provider adapter and stores output image.
4. Worker writes `asset` record and marks job as `succeeded`.
5. Frontend polls `GET /jobs/{id}` and refreshes gallery.
6. User chooses image -> "Send to Animation".
7. I2V flow repeats with lineage (`source_asset_id`).
8. User runs sprite process (mask, frame select, alignment, packing).
9. Export service writes sprite sheet, metadata, and code snippet.

## 4) Service Boundaries

### Frontend responsibilities
- Form handling, local UI state, validation hints
- Polling and display of async statuses
- Canvas-based preview interactions (zoom/pan/playback)
- Export actions and copy-code UX

### Backend responsibilities
- Auth/session (single-user mode allowed in MVP)
- API schema validation and normalization
- Job submission and state transitions
- Asset metadata storage and lineage
- Signed URLs (if needed) for private asset access

### Worker responsibilities
- Provider API calls with retries and timeout policies
- Video frame extraction
- Mask generation/chroma key
- Bounding box detection and alignment
- Sprite sheet packing and metadata generation

## 5) Suggested Runtime Components

- **API app:** FastAPI
- **Queue:** Redis-backed queue (RQ/Celery) or DB-backed queue for early MVP
- **Worker:** Python process consuming queue jobs
- **Storage:** local disk in dev, S3-compatible bucket in staging/prod
- **DB:** PostgreSQL
- **ORM + migrations:** SQLAlchemy + Alembic (initial schema added)

## 6) Canonical Domain Entities

### Project
- logical workspace for all assets and jobs

### Asset
- image, video, frame sequence, sprite sheet, metadata JSON, code snippet text

### GenerationJob
- tracks status and links to output assets

### AssetRelation
- explicit parent-child mapping (image -> video -> frames -> sheet)

## 7) Database Shape (MVP Draft)

## `projects`
- `id` (uuid, pk)
- `name` (text)
- `created_at` (timestamp)
- `updated_at` (timestamp)

## `assets`
- `id` (uuid, pk)
- `project_id` (uuid, fk projects)
- `type` (enum: image, video, frames_zip, spritesheet, metadata_json, code_snippet)
- `storage_key` (text)
- `mime_type` (text)
- `width` (int, nullable)
- `height` (int, nullable)
- `duration_ms` (int, nullable)
- `frame_count` (int, nullable)
- `metadata_json` (jsonb)
- `created_at` (timestamp)

## `generation_jobs`
- `id` (uuid, pk)
- `project_id` (uuid, fk projects)
- `kind` (enum: t2i, i2v, sprites_process)
- `status` (enum: queued, running, succeeded, failed, cancelled)
- `provider` (text)
- `provider_job_id` (text, nullable)
- `request_json` (jsonb)
- `result_json` (jsonb, nullable)
- `error_code` (text, nullable)
- `error_message` (text, nullable)
- `started_at` (timestamp, nullable)
- `finished_at` (timestamp, nullable)
- `created_at` (timestamp)

## `asset_relations`
- `id` (uuid, pk)
- `project_id` (uuid, fk projects)
- `parent_asset_id` (uuid, fk assets)
- `child_asset_id` (uuid, fk assets)
- `relation_type` (enum: source_for, derived_from, export_of)
- `created_at` (timestamp)

## 8) Adapter Interface Contract

All providers implement internal contracts:
- `TextToImageAdapter.generate(request) -> NormalizedImageResult`
- `ImageToVideoAdapter.generate(request) -> NormalizedVideoJobResult`
- `ImageToVideoAdapter.poll(provider_job_id) -> NormalizedJobStatus`

The rest of the system never depends on raw provider schemas.

## 9) Job Lifecycle Rules

- New jobs start at `queued`.
- Worker sets `running` when execution starts.
- On success, write output assets first, then mark `succeeded`.
- On failure, write normalized error and mark `failed`.
- Polling endpoint never returns provider-specific internal details.

## 10) File and Asset Storage Conventions

Use deterministic storage keys:
- `projects/{project_id}/images/{asset_id}.png`
- `projects/{project_id}/videos/{asset_id}.mp4`
- `projects/{project_id}/frames/{asset_id}.zip`
- `projects/{project_id}/sprites/{asset_id}.png`
- `projects/{project_id}/metadata/{asset_id}.json`
- `projects/{project_id}/snippets/{asset_id}.txt`

## 11) Security and Reliability Baseline

- Keep provider API keys on backend only.
- Enforce upload size/type limits.
- Add per-project rate limiting for generation endpoints.
- Add retries with capped backoff for transient provider failures.
- Add cleanup process for abandoned temporary files.

## 12) First Build Sequence

1. Implement DB models and migrations.
2. Implement `POST /t2i/generate` + `GET /jobs/{id}`.
3. Wire T2I frontend and gallery.
4. Add `POST /i2v/generate` and lineage linking.
5. Add sprite process endpoint and export pipeline.

This sequence creates value early while keeping technical debt controlled.

## 13) Desktop Roadmap Compatibility

Shadow is intentionally built web-first with desktop packaging in mind (Option A).
- Keep API host configurable in frontend env.
- Keep backend process independently runnable.
- Keep storage and provider integration behind interfaces.

Detailed transition steps are documented in `docs/desktop-transition-plan.md`.
