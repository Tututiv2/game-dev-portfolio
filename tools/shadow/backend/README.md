# Shadow Backend

FastAPI backend scaffold for Shadow.

## Quick start

1. Create a virtual environment:
   - Windows PowerShell: `python -m venv .venv`
2. Activate:
   - `.\.venv\Scripts\Activate.ps1`
3. Install deps:
   - `pip install -r requirements.txt`
4. Run API:
   - `uvicorn app.main:app --reload --port 8000`

## Database and migrations

- Configure `DATABASE_URL` in `.env` (defaults to local SQLite `shadow.db` if unset).
- Run migrations:
  - `alembic upgrade head`
- Create a new migration (later when schema changes):
  - `alembic revision -m "describe change"`
- One-time sync legacy JSON runtime state into DB:
  - `python -m app.scripts.sync_state_to_db`

## Available endpoints

- `GET /health`
- `POST /api/v1/t2i/generate`
- `POST /api/v1/i2v/generate`
- `GET /api/v1/jobs/{job_id}`
- `GET /api/v1/assets?project_id=...`
- `POST /api/v1/sprites/process`
- `GET /api/v1/exports/{job_id}`

These endpoints now run through provider adapters and persist runtime state locally while DB migration work continues.
