from __future__ import annotations

from sqlalchemy import JSON, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.sql import func

from app.db import Base


class Project(Base):
    __tablename__ = "projects"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(255), default="Shadow Project")
    created_at: Mapped[str] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[str] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    assets: Mapped[list["Asset"]] = relationship(back_populates="project")
    jobs: Mapped[list["GenerationJob"]] = relationship(back_populates="project")


class Asset(Base):
    __tablename__ = "assets"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    project_id: Mapped[str] = mapped_column(ForeignKey("projects.id"), index=True)
    type: Mapped[str] = mapped_column(String(32), index=True)
    url: Mapped[str] = mapped_column(Text)
    mime_type: Mapped[str] = mapped_column(String(128))
    width: Mapped[int | None] = mapped_column(Integer, nullable=True)
    height: Mapped[int | None] = mapped_column(Integer, nullable=True)
    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    frame_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[str] = mapped_column(DateTime(timezone=True), server_default=func.now())

    project: Mapped["Project"] = relationship(back_populates="assets")


class GenerationJob(Base):
    __tablename__ = "generation_jobs"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    project_id: Mapped[str] = mapped_column(ForeignKey("projects.id"), index=True)
    kind: Mapped[str] = mapped_column(String(32), index=True)
    status: Mapped[str] = mapped_column(String(32), index=True)
    progress: Mapped[int] = mapped_column(Integer, default=0)
    request_json: Mapped[dict] = mapped_column(JSON, default=dict)
    result_json: Mapped[dict] = mapped_column(JSON, default=dict)
    output_asset_ids: Mapped[list] = mapped_column(JSON, default=list)
    created_at: Mapped[str] = mapped_column(DateTime(timezone=True), server_default=func.now())
    started_at: Mapped[str | None] = mapped_column(DateTime(timezone=True), nullable=True)
    finished_at: Mapped[str | None] = mapped_column(DateTime(timezone=True), nullable=True)

    project: Mapped["Project"] = relationship(back_populates="jobs")


class AssetRelation(Base):
    __tablename__ = "asset_relations"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    project_id: Mapped[str] = mapped_column(ForeignKey("projects.id"), index=True)
    parent_asset_id: Mapped[str] = mapped_column(ForeignKey("assets.id"), index=True)
    child_asset_id: Mapped[str] = mapped_column(ForeignKey("assets.id"), index=True)
    relation_type: Mapped[str] = mapped_column(String(32), index=True)
    created_at: Mapped[str] = mapped_column(DateTime(timezone=True), server_default=func.now())
