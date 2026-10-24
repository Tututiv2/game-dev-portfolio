from __future__ import annotations

from app.main import (
    Base,
    engine,
    hydrate_runtime_from_db,
    jobs,
    assets_by_project,
    sync_state_file_to_db,
)


def main() -> None:
    Base.metadata.create_all(bind=engine)
    sync_state_file_to_db()
    hydrate_runtime_from_db()
    print(
        f"Synced runtime state into DB. jobs={len(jobs)} projects_with_assets={len(assets_by_project)}"
    )


if __name__ == "__main__":
    main()
