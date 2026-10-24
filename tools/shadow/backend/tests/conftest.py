import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# ---------------------------------------------------------------------------
# Redirect ALL storage to a throwaway location BEFORE app.main / app.db are
# imported. Both read their paths at import time, so this must happen first.
#
# Why this exists: the autouse fixture below deletes state.json and every row in
# the database. It used to run against the REAL ones - backend/shadow.db and
# backend/.shadow-assets/ - so simply running `pytest` destroyed the asset
# library, with no backup (both are gitignored). It had already happened once.
# ---------------------------------------------------------------------------
_TEST_STORAGE = Path(tempfile.mkdtemp(prefix="shadow-tests-"))
os.environ["SHADOW_STORAGE_ROOT"] = str(_TEST_STORAGE)
os.environ["DATABASE_URL"] = f"sqlite:///{(_TEST_STORAGE / 'test-shadow.db').as_posix()}"

import pytest

from app.main import (  # noqa: E402  - imported after the env vars above are set
    STATE_FILE,
    STORAGE_ROOT,
    assets_by_project,
    exports_by_job,
    job_requests,
    jobs,
    preview_files,
)
from app.db import DATABASE_URL, Base, engine, get_session  # noqa: E402
from app.models import Asset, AssetRelation, GenerationJob, Project  # noqa: E402


def _assert_isolated() -> None:
    """Refuse to run if anything still points at real data.

    A guard, not a comment. If a future change moves where these paths are
    resolved, the suite fails loudly here instead of silently deleting the
    owner's assets again.
    """
    if _TEST_STORAGE not in STORAGE_ROOT.parents and STORAGE_ROOT != _TEST_STORAGE:
        raise RuntimeError(
            f"Tests are not isolated: STORAGE_ROOT is {STORAGE_ROOT}, expected {_TEST_STORAGE}. "
            "Refusing to run - this would delete real assets."
        )
    if str(_TEST_STORAGE.as_posix()) not in DATABASE_URL:
        raise RuntimeError(
            f"Tests are not isolated: DATABASE_URL is {DATABASE_URL}. "
            "Refusing to run - this would wipe the real database."
        )


_assert_isolated()
Base.metadata.create_all(bind=engine)


@pytest.fixture(autouse=True)
def reset_runtime_state() -> None:
    _assert_isolated()
    jobs.clear()
    assets_by_project.clear()
    exports_by_job.clear()
    job_requests.clear()
    preview_files.clear()
    if STATE_FILE.exists():
        STATE_FILE.unlink()
    with get_session() as session:
        session.query(AssetRelation).delete()
        session.query(Asset).delete()
        session.query(GenerationJob).delete()
        session.query(Project).delete()
