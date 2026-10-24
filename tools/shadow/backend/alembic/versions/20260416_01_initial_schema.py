"""initial schema

Revision ID: 20260416_01
Revises:
Create Date: 2026-04-16
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20260416_01"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "projects",
        sa.Column("id", sa.String(length=64), primary_key=True, nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "assets",
        sa.Column("id", sa.String(length=64), primary_key=True, nullable=False),
        sa.Column("project_id", sa.String(length=64), sa.ForeignKey("projects.id"), nullable=False),
        sa.Column("type", sa.String(length=32), nullable=False),
        sa.Column("url", sa.Text(), nullable=False),
        sa.Column("mime_type", sa.String(length=128), nullable=False),
        sa.Column("width", sa.Integer(), nullable=True),
        sa.Column("height", sa.Integer(), nullable=True),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.Column("frame_count", sa.Integer(), nullable=True),
        sa.Column("metadata_json", sa.JSON(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_assets_project_id", "assets", ["project_id"])
    op.create_index("ix_assets_type", "assets", ["type"])

    op.create_table(
        "generation_jobs",
        sa.Column("id", sa.String(length=64), primary_key=True, nullable=False),
        sa.Column("project_id", sa.String(length=64), sa.ForeignKey("projects.id"), nullable=False),
        sa.Column("kind", sa.String(length=32), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("progress", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("request_json", sa.JSON(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column("result_json", sa.JSON(), nullable=False, server_default=sa.text("'{}'")),
        sa.Column("output_asset_ids", sa.JSON(), nullable=False, server_default=sa.text("'[]'")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_generation_jobs_project_id", "generation_jobs", ["project_id"])
    op.create_index("ix_generation_jobs_kind", "generation_jobs", ["kind"])
    op.create_index("ix_generation_jobs_status", "generation_jobs", ["status"])

    op.create_table(
        "asset_relations",
        sa.Column("id", sa.String(length=64), primary_key=True, nullable=False),
        sa.Column("project_id", sa.String(length=64), sa.ForeignKey("projects.id"), nullable=False),
        sa.Column("parent_asset_id", sa.String(length=64), sa.ForeignKey("assets.id"), nullable=False),
        sa.Column("child_asset_id", sa.String(length=64), sa.ForeignKey("assets.id"), nullable=False),
        sa.Column("relation_type", sa.String(length=32), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_asset_relations_project_id", "asset_relations", ["project_id"])
    op.create_index("ix_asset_relations_parent_asset_id", "asset_relations", ["parent_asset_id"])
    op.create_index("ix_asset_relations_child_asset_id", "asset_relations", ["child_asset_id"])
    op.create_index("ix_asset_relations_relation_type", "asset_relations", ["relation_type"])


def downgrade() -> None:
    op.drop_index("ix_asset_relations_relation_type", table_name="asset_relations")
    op.drop_index("ix_asset_relations_child_asset_id", table_name="asset_relations")
    op.drop_index("ix_asset_relations_parent_asset_id", table_name="asset_relations")
    op.drop_index("ix_asset_relations_project_id", table_name="asset_relations")
    op.drop_table("asset_relations")

    op.drop_index("ix_generation_jobs_status", table_name="generation_jobs")
    op.drop_index("ix_generation_jobs_kind", table_name="generation_jobs")
    op.drop_index("ix_generation_jobs_project_id", table_name="generation_jobs")
    op.drop_table("generation_jobs")

    op.drop_index("ix_assets_type", table_name="assets")
    op.drop_index("ix_assets_project_id", table_name="assets")
    op.drop_table("assets")

    op.drop_table("projects")
