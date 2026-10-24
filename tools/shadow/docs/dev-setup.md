# Shadow Dev Setup (Kickoff)

This is the minimum setup plan to start implementation quickly and consistently.

## 1) Required Software

- Node.js LTS (for frontend)
- Python 3.11+ (for backend/workers)
- PostgreSQL 15+
- Redis (recommended for job queue)
- ffmpeg (for frame extraction)

## 2) Proposed Initial Structure

```text
Shadow/
  frontend/
  backend/
  docs/
```

## 3) Environment Variables (Draft)

## Backend

- `APP_ENV=development`
- `API_PORT=8000`
- `DATABASE_URL=postgresql://...`
- `REDIS_URL=redis://localhost:6379/0`
- `ASSET_STORAGE_MODE=local`
- `ASSET_LOCAL_ROOT=./.shadow-assets`
- `T2I_PROVIDER=...`
- `T2I_API_KEY=...`
- `I2V_PROVIDER=...`
- `I2V_API_KEY=...`

## Frontend

- `NEXT_PUBLIC_API_BASE_URL=http://localhost:8000/api/v1`

## 4) Kickoff Sequence

1. Create `frontend` and `backend` apps.
2. Confirm backend health endpoint.
3. Confirm frontend can call backend health endpoint.
4. Add DB migration tooling and run first migration.
5. Add queue worker and run a dummy background job.
6. Add first real endpoint: `POST /t2i/generate`.

## 5) Day 1 Smoke Checklist

- [ ] Backend starts locally.
- [ ] Frontend starts locally.
- [ ] DB connection succeeds.
- [ ] Queue/worker executes one test job.
- [ ] Health endpoint reachable from frontend.
- [ ] API contract docs committed and shared.

## 6) Branching and Delivery

- Use short-lived feature branches:
  - `feat/t2i-job-endpoint`
  - `feat/t2i-form-ui`
  - `feat/gallery-grid`
- Keep each PR focused on one vertical capability.
- Require lint + tests before merge.

## 7) First Demo Goal

By end of first implementation window, demo:
- enter prompt
- generate one image
- image appears in gallery with metadata

This is the first proof that Shadow's architecture is working end-to-end.
