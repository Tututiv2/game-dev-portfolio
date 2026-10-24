from __future__ import annotations

from datetime import datetime
from typing import Any

from app.db import get_session
from app.models import Asset, GenerationJob, Project


def parse_iso_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def to_iso(value: datetime | None) -> str | None:
    if not value:
        return None
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_project(project_id: str) -> None:
    with get_session() as session:
        if session.get(Project, project_id):
            return
        session.add(Project(id=project_id, name=f"Shadow {project_id}"))


def upsert_job(
    *,
    job_id: str,
    project_id: str,
    kind: str,
    status: str,
    progress: int,
    created_at: str,
    started_at: str | None,
    finished_at: str | None,
    output_asset_ids: list[str],
    request_json: dict[str, Any] | None = None,
    result_json: dict[str, Any] | None = None,
) -> None:
    ensure_project(project_id)
    with get_session() as session:
        row = session.get(GenerationJob, job_id)
        if not row:
            row = GenerationJob(id=job_id, project_id=project_id, kind=kind, status=status)
            session.add(row)
        row.progress = progress
        row.status = status
        row.request_json = request_json or row.request_json or {}
        row.result_json = result_json or row.result_json or {}
        row.output_asset_ids = output_asset_ids
        row.created_at = parse_iso_datetime(created_at) or row.created_at
        row.started_at = parse_iso_datetime(started_at)
        row.finished_at = parse_iso_datetime(finished_at)


def upsert_asset(
    *,
    asset_id: str,
    project_id: str,
    asset_type: str,
    url: str,
    mime_type: str,
    width: int | None,
    height: int | None,
    duration_ms: int | None,
    frame_count: int | None,
    metadata_json: dict[str, Any],
    created_at: str,
) -> None:
    ensure_project(project_id)
    with get_session() as session:
        row = session.get(Asset, asset_id)
        if not row:
            row = Asset(id=asset_id, project_id=project_id, type=asset_type, url=url, mime_type=mime_type)
            session.add(row)
        row.url = url
        row.mime_type = mime_type
        row.width = width
        row.height = height
        row.duration_ms = duration_ms
        row.frame_count = frame_count
        row.metadata_json = metadata_json
        row.created_at = parse_iso_datetime(created_at) or row.created_at


def load_assets_by_project() -> dict[str, list[dict[str, Any]]]:
    with get_session() as session:
        rows = session.query(Asset).order_by(Asset.created_at.desc()).all()

    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        grouped.setdefault(row.project_id, []).append(
            {
                "id": row.id,
                "project_id": row.project_id,
                "type": row.type,
                "url": row.url,
                "mime_type": row.mime_type,
                "width": row.width,
                "height": row.height,
                "duration_ms": row.duration_ms,
                "frame_count": row.frame_count,
                "metadata": row.metadata_json or {},
                "created_at": to_iso(row.created_at) or "",
            }
        )
    return grouped


def load_jobs() -> dict[str, dict[str, Any]]:
    with get_session() as session:
        rows = session.query(GenerationJob).all()

    mapped: dict[str, dict[str, Any]] = {}
    for row in rows:
        mapped[row.id] = {
            "job_id": row.id,
            "project_id": row.project_id,
            "kind": row.kind,
            "status": row.status,
            "progress": row.progress,
            "created_at": to_iso(row.created_at) or "",
            "started_at": to_iso(row.started_at),
            "finished_at": to_iso(row.finished_at),
            "output_asset_ids": row.output_asset_ids or [],
        }
    return mapped


def get_job(job_id: str) -> dict[str, Any] | None:
    with get_session() as session:
        row = session.get(GenerationJob, job_id)
        if not row:
            return None
        return {
            "job_id": row.id,
            "project_id": row.project_id,
            "kind": row.kind,
            "status": row.status,
            "progress": row.progress,
            "created_at": to_iso(row.created_at) or "",
            "started_at": to_iso(row.started_at),
            "finished_at": to_iso(row.finished_at),
            "output_asset_ids": row.output_asset_ids or [],
        }


def list_assets(project_id: str, asset_type: str | None = None) -> list[dict[str, Any]]:
    with get_session() as session:
        query = session.query(Asset).filter(Asset.project_id == project_id)
        if asset_type:
            query = query.filter(Asset.type == asset_type)
        rows = query.order_by(Asset.created_at.desc()).all()

    return [
        {
            "id": row.id,
            "project_id": row.project_id,
            "type": row.type,
            "url": row.url,
            "mime_type": row.mime_type,
            "width": row.width,
            "height": row.height,
            "duration_ms": row.duration_ms,
            "frame_count": row.frame_count,
            "metadata": row.metadata_json or {},
            "created_at": to_iso(row.created_at) or "",
        }
        for row in rows
    ]
