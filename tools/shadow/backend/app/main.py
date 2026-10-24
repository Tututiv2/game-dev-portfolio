from __future__ import annotations

import json
import os
import shutil
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlparse
from urllib.request import url2pathname, urlopen
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from PIL import Image
from pydantic import BaseModel, Field
from app.db import Base, engine
from app.persistence import (
    get_job as load_job_from_db,
    list_assets as list_assets_from_db,
    load_assets_by_project,
    load_jobs,
    upsert_asset,
    upsert_job,
)
from app.providers import get_i2v_provider, get_t2i_provider


JobStatus = Literal["queued", "running", "succeeded", "failed", "cancelled"]
JobKind = Literal["t2i", "i2v", "sprites_process"]
AssetType = Literal[
    "image",
    "video",
    "frames_zip",
    "spritesheet",
    "metadata_json",
    "code_snippet",
]


class ErrorEnvelope(BaseModel):
    code: str
    message: str
    details: dict[str, Any] = Field(default_factory=dict)


class GenerateT2IRequest(BaseModel):
    project_id: str
    prompt: str = Field(min_length=1, max_length=2000)
    negative_prompt: str | None = None
    model: str
    aspect_ratio: Literal["1:1", "16:9", "9:16", "4:3", "3:4"]
    seed: int | None = None
    reference_asset_id: str | None = None


class GenerateI2VRequest(BaseModel):
    project_id: str
    prompt: str = Field(min_length=1, max_length=2000)
    model: str
    reference_image_asset_id: str
    duration_seconds: int = Field(ge=1, le=8)
    resolution: str
    aspect_ratio: Literal["1:1", "16:9", "9:16", "4:3", "3:4"]
    fps_hint: int | None = Field(default=12, ge=1, le=60)


class MaskingOptions(BaseModel):
    key_color_hex: str | None = None
    tolerance: int | None = Field(default=None, ge=0, le=100)
    # Width of the soft alpha ramp. 0 restores the old binary mask, which
    # cannot anti-alias an edge - available, but never the default.
    softness: int | None = Field(default=None, ge=0, le=100)
    # Remove the key hue bounced onto the subject. Off only if the subject is
    # legitimately the key colour and the fringe is preferable.
    despill: bool = True
    # corridor_key only: how much a pure brightness change counts as subject.
    # See spritekit.corridor_alpha for why the default is what it is.
    luma_weight: float | None = Field(default=None, ge=0.0, le=1.0)


class FrameSelection(BaseModel):
    enabled_indices: list[int]


class ExportOptions(BaseModel):
    sheet_layout: Literal["horizontal"] = "horizontal"
    include_frames_zip: bool = True
    generate_code_snippet: bool = True
    # Playback rate baked into the metadata and the generated snippet. Defaults
    # to the source clip's own rate; 12 used to be hardcoded into the snippet
    # with no way to influence it from anywhere.
    fps: int | None = Field(default=None, ge=1, le=60)


AlignmentModeName = Literal[
    "union", "bottom_center", "both_centered", "vertical_only", "horizontal_only"
]


class SpriteProcessRequest(BaseModel):
    project_id: str
    source_video_asset_id: str
    masking_mode: Literal["chroma", "corridor_key"]
    masking_options: MaskingOptions = Field(default_factory=MaskingOptions)
    frame_selection: FrameSelection
    # "union" replaces "both_centered" as the default deliberately: centring
    # each frame on its own bounding box deletes the animation's vertical
    # motion. See spritekit.align_frames.
    alignment_mode: AlignmentModeName = "union"
    export_options: ExportOptions = Field(default_factory=ExportOptions)


class SpriteMaskPreviewRequest(BaseModel):
    project_id: str
    source_video_asset_id: str
    masking_mode: Literal["chroma", "corridor_key"]
    masking_options: MaskingOptions = Field(default_factory=MaskingOptions)
    frame_index: int = Field(default=0, ge=0)


class CreateJobResponse(BaseModel):
    job_id: str
    status: JobStatus


class JobResponse(BaseModel):
    job_id: str
    project_id: str
    kind: JobKind
    status: JobStatus
    progress: int
    created_at: str
    started_at: str | None
    finished_at: str | None
    output_asset_ids: list[str]


class AssetResponse(BaseModel):
    id: str
    project_id: str
    type: AssetType
    url: str
    mime_type: str
    width: int | None = None
    height: int | None = None
    duration_ms: int | None = None
    frame_count: int | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    created_at: str


class ListAssetsResponse(BaseModel):
    items: list[AssetResponse]


class ExportResponse(BaseModel):
    job_id: str
    project_id: str
    status: JobStatus
    spritesheet_asset_id: str
    frames_zip_asset_id: str | None
    metadata_asset_id: str
    code_snippet_asset_id: str | None
    alignment: dict[str, int]
    frame: dict[str, int]
    sheet: dict[str, int | str]
    # Added rather than replacing anything above, so existing consumers keep
    # working while gaining a real pivot and an honest account of what was
    # dropped on the way through.
    pivot: dict[str, int] | None = None
    dropped_frames: list[dict[str, Any]] = Field(default_factory=list)
    fps: int | None = None


class SpriteMaskPreviewResponse(BaseModel):
    preview_id: str
    url: str


app = FastAPI(title="Shadow API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

jobs: dict[str, JobResponse] = {}
assets_by_project: dict[str, list[AssetResponse]] = {}
exports_by_job: dict[str, ExportResponse] = {}
job_requests: dict[str, dict[str, Any]] = {}
preview_files: dict[str, tuple[Path, str]] = {}

# SHADOW_STORAGE_ROOT lets the test suite point at a throwaway folder. Without it, running
# pytest deleted the real state.json and wiped every asset row. See backend/tests/conftest.py.
STORAGE_ROOT = Path(os.getenv("SHADOW_STORAGE_ROOT") or Path(__file__).resolve().parents[1] / ".shadow-assets")
STORAGE_ROOT.mkdir(parents=True, exist_ok=True)
ASSET_BASE_URL = os.getenv("ASSET_BASE_URL", "http://localhost:8000")
STATE_FILE = STORAGE_ROOT / "state.json"


def now_iso() -> str:
    return (
        datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def persist_state() -> None:
    payload = {
        "jobs": {job_id: job.model_dump() for job_id, job in jobs.items()},
        "assets_by_project": {
            project_id: [asset.model_dump() for asset in assets]
            for project_id, assets in assets_by_project.items()
        },
        "exports_by_job": {
            job_id: export.model_dump() for job_id, export in exports_by_job.items()
        },
        "job_requests": job_requests,
    }
    temp_path = STATE_FILE.with_suffix(".json.tmp")
    temp_path.write_text(json.dumps(payload), encoding="utf-8")
    temp_path.replace(STATE_FILE)


def load_state() -> None:
    if not STATE_FILE.exists():
        return
    try:
        payload = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        jobs.clear()
        jobs.update(
            {
                job_id: JobResponse.model_validate(job_data)
                for job_id, job_data in payload.get("jobs", {}).items()
            }
        )
        assets_by_project.clear()
        assets_by_project.update(
            {
                project_id: [
                    AssetResponse.model_validate(item)
                    for item in project_assets
                ]
                for project_id, project_assets in payload.get(
                    "assets_by_project", {}
                ).items()
            }
        )
        exports_by_job.clear()
        exports_by_job.update(
            {
                job_id: ExportResponse.model_validate(export_data)
                for job_id, export_data in payload.get("exports_by_job", {}).items()
            }
        )
        job_requests.clear()
        job_requests.update(payload.get("job_requests", {}))
    except Exception:
        # Keep startup resilient if persisted state is malformed.
        pass


def hydrate_runtime_from_db() -> None:
    db_jobs = load_jobs()
    db_assets = load_assets_by_project()

    if db_jobs:
        jobs.clear()
        jobs.update({job_id: JobResponse.model_validate(data) for job_id, data in db_jobs.items()})
    if db_assets:
        assets_by_project.clear()
        assets_by_project.update(
            {
                project_id: [AssetResponse.model_validate(item) for item in assets]
                for project_id, assets in db_assets.items()
            }
        )


def sync_state_file_to_db() -> None:
    if not STATE_FILE.exists():
        return
    try:
        payload = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return

    for job_id, job_data in payload.get("jobs", {}).items():
        job = JobResponse.model_validate(job_data)
        upsert_job(
            job_id=job_id,
            project_id=job.project_id,
            kind=job.kind,
            status=job.status,
            progress=job.progress,
            created_at=job.created_at,
            started_at=job.started_at,
            finished_at=job.finished_at,
            output_asset_ids=job.output_asset_ids,
            request_json=payload.get("job_requests", {}).get(job_id, {}),
        )
    for _, project_assets in payload.get("assets_by_project", {}).items():
        for item in project_assets:
            asset = AssetResponse.model_validate(item)
            upsert_asset(
                asset_id=asset.id,
                project_id=asset.project_id,
                asset_type=asset.type,
                url=asset.url,
                mime_type=asset.mime_type,
                width=asset.width,
                height=asset.height,
                duration_ms=asset.duration_ms,
                frame_count=asset.frame_count,
                metadata_json=asset.metadata,
                created_at=asset.created_at,
            )


def add_asset(
    *,
    project_id: str,
    asset_type: AssetType,
    url: str,
    mime_type: str,
    width: int | None = None,
    height: int | None = None,
    duration_ms: int | None = None,
    frame_count: int | None = None,
    metadata: dict[str, Any] | None = None,
) -> AssetResponse:
    asset_id = str(uuid4())
    resolved_url = url.replace("{asset_id}", asset_id)
    asset = AssetResponse(
        id=asset_id,
        project_id=project_id,
        type=asset_type,
        url=resolved_url,
        mime_type=mime_type,
        width=width,
        height=height,
        duration_ms=duration_ms,
        frame_count=frame_count,
        metadata=metadata or {},
        created_at=now_iso(),
    )
    assets_by_project.setdefault(project_id, []).insert(0, asset)
    upsert_asset(
        asset_id=asset.id,
        project_id=asset.project_id,
        asset_type=asset.type,
        url=asset.url,
        mime_type=asset.mime_type,
        width=asset.width,
        height=asset.height,
        duration_ms=asset.duration_ms,
        frame_count=asset.frame_count,
        metadata_json=asset.metadata,
        created_at=asset.created_at,
    )
    persist_state()
    return asset


def get_asset_by_id(project_id: str, asset_id: str) -> AssetResponse | None:
    for asset in assets_by_project.get(project_id, []):
        if asset.id == asset_id:
            return asset
    return None


def get_asset_global(asset_id: str) -> AssetResponse | None:
    for project_assets in assets_by_project.values():
        for asset in project_assets:
            if asset.id == asset_id:
                return asset
    return None


_HEX_DIGITS = set("0123456789abcdefABCDEF")


def parse_hex_color(color: str) -> tuple[int, int, int]:
    """Parse ``#RRGGBB``. Raises on anything else.

    This used to ``return (0, 255, 0)`` for malformed input, so one typo in the
    key colour silently keyed **green** instead: the export succeeded, against
    a colour nobody chose, and nothing anywhere said so. Same family as
    landmine 6 - a fallback that produces plausible output from a failed input
    is worse than an error, because nobody goes looking for it.
    """
    cleaned = (color or "").strip().lstrip("#")
    if len(cleaned) != 6 or any(character not in _HEX_DIGITS for character in cleaned):
        raise ValueError(
            f"Invalid key colour {color!r}. Expected a 6-digit hex colour such as #FF00FF."
        )
    return tuple(int(cleaned[index : index + 2], 16) for index in (0, 2, 4))


# A clip that will not fit in memory as decoded frames is not a sprite source,
# and an unbounded download can fill the disk while reporting progress.
_MAX_SOURCE_BYTES = 512 * 1024 * 1024
_FETCH_TIMEOUT_SECONDS = 30


def fetch_source_video(url: str, destination: Path) -> None:
    """Fetch the source clip to ``destination``.

    Replaces ``urlretrieve``, which has none of the three guards here. It
    accepts ``file://``, so an asset URL could name any file the server process
    can read; it takes no timeout, so an unresponsive host parks a worker
    indefinitely; and it has no size cap, so one wrong URL fills the disk.
    """
    parsed = urlparse(url)

    if parsed.scheme in ("http", "https"):
        with urlopen(url, timeout=_FETCH_TIMEOUT_SECONDS) as response:  # noqa: S310
            written = 0
            with destination.open("wb") as handle:
                while chunk := response.read(1 << 20):
                    written += len(chunk)
                    if written > _MAX_SOURCE_BYTES:
                        raise RuntimeError(
                            f"Source video exceeds {_MAX_SOURCE_BYTES // (1024 * 1024)} MB; "
                            "refusing to download further."
                        )
                    handle.write(chunk)
        return

    if parsed.scheme in ("", "file"):
        local = Path(url2pathname(parsed.path) if parsed.scheme == "file" else url).resolve()
        root = STORAGE_ROOT.resolve()
        if root != local and root not in local.parents:
            raise RuntimeError(
                f"Refusing to read {local} - local sources must live under the asset store."
            )
        shutil.copyfile(local, destination)
        return

    raise RuntimeError(f"Unsupported source URL scheme {parsed.scheme!r} for {url!r}.")


def decode_frames(video_path: Path, indices: list[int]) -> tuple[dict[int, Any], float]:
    """Decode the requested frame indices, plus the clip's real frame rate.

    Decodes **sequentially** rather than seeking per frame. The previous
    implementation called ``cap.set(CAP_PROP_POS_FRAMES, i)`` once per wanted
    frame, which is both slow and, for most inter-frame codecs, inaccurate: a
    seek lands on the nearest keyframe, so asking for frames 10, 11, 12 of an
    H.264 clip can return the same picture three times. Reading forward once and
    keeping the wanted indices is faster and exact.

    Returns a **map** keyed by source index, not a list, so the caller can
    reassemble the user's chosen frame order. Sequential decoding requires
    visiting indices in ascending order; returning them that way would silently
    re-sort a deliberately non-monotonic animation loop (a ping-pong cycle, say)
    into a different animation.
    """
    import cv2

    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise RuntimeError("Could not open source video.")

    source_fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
    wanted = {max(0, index) for index in indices}
    if not wanted:
        capture.release()
        raise RuntimeError("No frames were selected for extraction.")

    frames: dict[int, Any] = {}
    position = 0
    last_wanted = max(wanted)
    while position <= last_wanted:
        ok, frame_bgr = capture.read()
        if not ok:
            break
        if position in wanted:
            frames[position] = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        position += 1
    capture.release()

    if not frames:
        raise RuntimeError(
            f"None of the requested frames ({min(wanted)}-{last_wanted}) exist in a clip "
            f"that decoded {position} frames."
        )
    return frames, source_fps


def build_canvas_snippet(
    *,
    frame_width: int,
    frame_height: int,
    frame_count: int,
    fps: int,
    pivot_x: int,
    pivot_y: int,
) -> str:
    """Re-export of ``spritekit.build_canvas_snippet``.

    The implementation lives in the dependency-free module because there are now
    **three** callers: this API, the mock pipeline, and the desktop app's CLI -
    and the CLI must not import FastAPI to obtain a string. Keeping the body
    here and "just copying it" into the CLI is precisely the drift that
    landmine 38 is about.

    Imported lazily so a minimal install without numpy can still serve the API,
    matching how the rest of the pixel work is imported here.
    """
    from app.spritekit import build_canvas_snippet as _build

    return _build(
        frame_width=frame_width,
        frame_height=frame_height,
        frame_count=frame_count,
        fps=fps,
        pivot_x=pivot_x,
        pivot_y=pivot_y,
    )


def run_real_sprite_pipeline(
    *,
    job_id: str,
    project_id: str,
    source_video_url: str,
    request: SpriteProcessRequest,
) -> ExportResponse:
    try:
        import numpy as np

        from app import spritekit as sk
    except ImportError as error:
        raise RuntimeError("OpenCV and NumPy are required for sprite processing.") from error

    project_dir = STORAGE_ROOT / project_id
    sprites_dir = project_dir / "sprites"
    frames_dir = project_dir / "frames" / job_id
    metadata_dir = project_dir / "metadata"
    snippets_dir = project_dir / "snippets"
    zips_dir = project_dir / "zips"
    for directory in (sprites_dir, frames_dir, metadata_dir, snippets_dir, zips_dir):
        directory.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as temp_dir_str:
        temp_dir = Path(temp_dir_str)
        source_video_path = temp_dir / f"{job_id}.mp4"
        fetch_source_video(source_video_url, source_video_path)

        indices = request.frame_selection.enabled_indices or [0, 1, 2, 3, 4, 5]
        options = request.masking_options
        decoded, source_fps = decode_frames(source_video_path, indices)

        # decode_frames returns a map so the caller's frame ORDER survives; a
        # deliberately non-monotonic loop (a ping-pong cycle) must not come back
        # re-sorted into a different animation.
        ordered_indices = [index for index in indices if index in decoded]
        dropped: list[dict[str, Any]] = [
            {"index": index, "reason": "the clip has no frame at this index"}
            for index in indices
            if index not in decoded
        ]
        frames_rgb = [decoded[index].astype(np.float64) for index in ordered_indices]

        if request.masking_mode == "corridor_key":
            alphas, background = sk.corridor_alpha(
                frames_rgb,
                luma_weight=(0.10 if options.luma_weight is None else options.luma_weight),
            )
            # No single key colour exists here, so each pixel despills against
            # the backdrop actually estimated behind it.
            despill_targets: list[Any] = [background] * len(frames_rgb)
        else:
            key_rgb = parse_hex_color(options.key_color_hex or "#00FF00")
            alphas = [
                sk.chroma_alpha(
                    frame,
                    key_rgb,
                    tolerance=36 if options.tolerance is None else options.tolerance,
                    softness=18 if options.softness is None else options.softness,
                )
                for frame in frames_rgb
            ]
            despill_targets = [np.array(key_rgb, dtype=np.float64)] * len(frames_rgb)

        rgba_frames = [
            sk.apply_alpha(sk.despill(frame, target) if options.despill else frame, alpha)
            for frame, alpha, target in zip(frames_rgb, alphas, despill_targets)
        ]

        aligned = sk.align_frames(
            rgba_frames, alphas, mode=request.alignment_mode, indices=ordered_indices
        )
        dropped.extend({"index": r.index, "reason": r.reason} for r in aligned.rejects)

        aligned_frames = aligned.frames
        max_width, max_height = aligned.frame_width, aligned.frame_height
        fps = request.export_options.fps or (
            round(source_fps) if source_fps and source_fps > 0 else 12
        )
        fps = max(1, min(60, int(fps)))

        saved_frame_paths: list[Path] = []
        for frame_index, frame in enumerate(aligned_frames):
            frame_path = frames_dir / f"frame_{frame_index:03d}.png"
            Image.fromarray(frame, mode="RGBA").save(frame_path)
            saved_frame_paths.append(frame_path)

        sheet_width = max_width * len(aligned_frames)
        sheet_height = max_height
        sheet_image = Image.new("RGBA", (sheet_width, sheet_height), (0, 0, 0, 0))
        for frame_index, frame in enumerate(aligned_frames):
            frame_image = Image.fromarray(frame, mode="RGBA")
            sheet_image.paste(frame_image, (frame_index * max_width, 0))

        sheet_path = sprites_dir / f"{job_id}.png"
        sheet_image.save(sheet_path)

        metadata_payload = {
            "version": 2,
            "frame_width": max_width,
            "frame_height": max_height,
            "frame_count": len(aligned_frames),
            "fps": fps,
            "source_fps": round(source_fps, 3) if source_fps else None,
            # The subject's anchor within a frame - bottom-centre, i.e. the
            # ground line. Rotate and position about THIS, not the frame centre.
            "pivot_x": aligned.pivot_x,
            "pivot_y": aligned.pivot_y,
            # Draw offsets that put the pivot exactly on the (x, y) handed to
            # drawImage. These were previously a hardcoded (0, -12) copied from
            # the mock pipeline - a fabricated number presented as measurement.
            "offset_x": -aligned.pivot_x,
            "offset_y": -aligned.pivot_y,
            "masking_mode": request.masking_mode,
            "alignment_mode": aligned.mode,
            "source_indices": aligned.kept_indices,
            "boxes": [list(box) for box in aligned.boxes],
            "dropped_frames": dropped,
        }
        metadata_path = metadata_dir / f"{job_id}.json"
        metadata_path.write_text(json.dumps(metadata_payload, indent=2), encoding="utf-8")

        snippet_text = build_canvas_snippet(
            frame_width=max_width,
            frame_height=max_height,
            frame_count=len(aligned_frames),
            fps=fps,
            pivot_x=aligned.pivot_x,
            pivot_y=aligned.pivot_y,
        )
        snippet_path = snippets_dir / f"{job_id}.txt"
        snippet_path.write_text(snippet_text, encoding="utf-8")

        frames_zip_path: Path | None = None
        if request.export_options.include_frames_zip:
            frames_zip_path = zips_dir / f"{job_id}.zip"
            with zipfile.ZipFile(frames_zip_path, mode="w", compression=zipfile.ZIP_DEFLATED) as archive:
                for frame_path in saved_frame_paths:
                    archive.write(frame_path, arcname=frame_path.name)

        spritesheet_asset = add_asset(
            project_id=project_id,
            asset_type="spritesheet",
            url=f"{ASSET_BASE_URL}/api/v1/local-assets/{{asset_id}}",
            mime_type="image/png",
            width=sheet_width,
            height=sheet_height,
            frame_count=len(aligned_frames),
            metadata={
                "source_video_url": source_video_url,
                "local_path": str(sheet_path),
            },
        )
        metadata_asset = add_asset(
            project_id=project_id,
            asset_type="metadata_json",
            url=f"{ASSET_BASE_URL}/api/v1/local-assets/{{asset_id}}",
            mime_type="application/json",
            metadata={
                "local_path": str(metadata_path),
            },
        )

        frames_zip_asset = None
        if frames_zip_path is not None:
            frames_zip_asset = add_asset(
                project_id=project_id,
                asset_type="frames_zip",
                url=f"{ASSET_BASE_URL}/api/v1/local-assets/{{asset_id}}",
                mime_type="application/zip",
                frame_count=len(aligned_frames),
                metadata={
                    "local_path": str(frames_zip_path),
                },
            )

        code_asset = None
        if request.export_options.generate_code_snippet:
            code_asset = add_asset(
                project_id=project_id,
                asset_type="code_snippet",
                url=f"{ASSET_BASE_URL}/api/v1/local-assets/{{asset_id}}",
                mime_type="text/plain",
                metadata={
                    "snippet": snippet_text,
                    "local_path": str(snippet_path),
                },
            )

        exports_by_job[job_id] = ExportResponse(
            job_id=job_id,
            project_id=project_id,
            status="succeeded",
            spritesheet_asset_id=spritesheet_asset.id,
            frames_zip_asset_id=frames_zip_asset.id if frames_zip_asset else None,
            metadata_asset_id=metadata_asset.id,
            code_snippet_asset_id=code_asset.id if code_asset else None,
            alignment={"offset_x": -aligned.pivot_x, "offset_y": -aligned.pivot_y},
            frame={"width": max_width, "height": max_height, "count": len(aligned_frames)},
            pivot={"x": aligned.pivot_x, "y": aligned.pivot_y},
            dropped_frames=dropped,
            fps=fps,
            sheet={
                "layout": "horizontal",
                "width": sheet_width,
                "height": sheet_height,
                "source_video_url": source_video_url,
            },
        )
        persist_state()
        return exports_by_job[job_id]


def run_mock_sprite_pipeline(
    *,
    job_id: str,
    project_id: str,
    source_video_url: str,
    request: SpriteProcessRequest,
) -> ExportResponse:
    frame_count = len(request.frame_selection.enabled_indices) or 6
    frame_width = 256
    frame_height = 256
    sheet_width = frame_width * frame_count
    sheet_height = frame_height

    spritesheet_asset = add_asset(
        project_id=project_id,
        asset_type="spritesheet",
        url=f"https://picsum.photos/seed/sheet-{job_id}/{sheet_width}/{sheet_height}",
        mime_type="image/png",
        width=sheet_width,
        height=sheet_height,
        frame_count=frame_count,
        metadata={
            "source_video_url": source_video_url,
            "masking_mode": request.masking_mode,
            "alignment_mode": request.alignment_mode,
            "pipeline_mode": "mock",
        },
    )
    metadata_asset = add_asset(
        project_id=project_id,
        asset_type="metadata_json",
        url=f"https://shadow.local/mock/metadata/{job_id}.json",
        mime_type="application/json",
        metadata={
            "frame_width": frame_width,
            "frame_height": frame_height,
            "frame_count": frame_count,
            "offset_x": 0,
            "offset_y": -12,
        },
    )

    frames_zip_asset = None
    if request.export_options.include_frames_zip:
        frames_zip_asset = add_asset(
            project_id=project_id,
            asset_type="frames_zip",
            url=f"https://shadow.local/mock/frames/{job_id}.zip",
            mime_type="application/zip",
            frame_count=frame_count,
            metadata={"source_video_url": source_video_url},
        )

    code_asset = None
    if request.export_options.generate_code_snippet:
        code_asset = add_asset(
            project_id=project_id,
            asset_type="code_snippet",
            url=f"https://shadow.local/mock/snippet/{job_id}.txt",
            mime_type="text/plain",
            metadata={
                "pipeline_mode": "mock",
                "snippet": build_canvas_snippet(
                    frame_width=frame_width,
                    frame_height=frame_height,
                    frame_count=frame_count,
                    fps=request.export_options.fps or 12,
                    pivot_x=frame_width // 2,
                    pivot_y=frame_height,
                ),
            },
        )

    exports_by_job[job_id] = ExportResponse(
        job_id=job_id,
        project_id=project_id,
        status="succeeded",
        spritesheet_asset_id=spritesheet_asset.id,
        frames_zip_asset_id=frames_zip_asset.id if frames_zip_asset else None,
        metadata_asset_id=metadata_asset.id,
        code_snippet_asset_id=code_asset.id if code_asset else None,
        alignment={"offset_x": 0, "offset_y": -12},
        frame={"width": frame_width, "height": frame_height, "count": frame_count},
        sheet={
            "layout": "horizontal",
            "width": sheet_width,
            "height": sheet_height,
            "source_video_url": source_video_url,
        },
    )
    persist_state()
    return exports_by_job[job_id]


def create_mask_preview(
    *,
    source_video_url: str,
    frame_index: int,
    masking_options: MaskingOptions,
    masking_mode: str = "chroma",
) -> tuple[Path, str]:
    """Render one frame keyed exactly the way the export will key it.

    Preview and export used to run two separate copies of the masking maths, so
    tuning the tolerance against the preview tuned it against a *different
    algorithm* than the one that would build the sheet. Both now call
    ``spritekit``, which is the only arrangement in which a preview means
    anything.

    ``masking_mode`` was also being ignored entirely here - the request model
    has carried the field since the beginning and this function never took it,
    so the preview was always a chroma key regardless of what was selected.
    """
    try:
        import numpy as np

        from app import spritekit as sk
    except ImportError as error:
        raise RuntimeError("OpenCV and NumPy are required for mask preview.") from error

    preview_id = str(uuid4())
    preview_dir = STORAGE_ROOT / "previews"
    preview_dir.mkdir(parents=True, exist_ok=True)
    preview_path = preview_dir / f"{preview_id}.png"

    with tempfile.TemporaryDirectory() as temp_dir_str:
        temp_dir = Path(temp_dir_str)
        source_video_path = temp_dir / f"{preview_id}.mp4"
        fetch_source_video(source_video_url, source_video_path)

        if masking_mode == "corridor_key":
            # A background estimate needs a spread of frames - there is no
            # median of one - so sample around the requested index.
            sample = sorted({max(0, frame_index + step) for step in (-8, -4, 0, 4, 8)})
            decoded, _ = decode_frames(source_video_path, sample)
            order = [index for index in sample if index in decoded]
            frames = [decoded[index].astype(np.float64) for index in order]
            alphas, background = sk.corridor_alpha(
                frames,
                luma_weight=(
                    0.10 if masking_options.luma_weight is None else masking_options.luma_weight
                ),
            )
            position = order.index(frame_index) if frame_index in order else 0
            frame_rgb, alpha, target = frames[position], alphas[position], background
        else:
            decoded, _ = decode_frames(source_video_path, [frame_index])
            frame_rgb = decoded[frame_index].astype(np.float64)
            key_rgb = parse_hex_color(masking_options.key_color_hex or "#00FF00")
            alpha = sk.chroma_alpha(
                frame_rgb,
                key_rgb,
                tolerance=36 if masking_options.tolerance is None else masking_options.tolerance,
                softness=18 if masking_options.softness is None else masking_options.softness,
            )
            target = np.array(key_rgb, dtype=np.float64)

        rgb = sk.despill(frame_rgb, target) if masking_options.despill else frame_rgb
        Image.fromarray(sk.apply_alpha(rgb, alpha), mode="RGBA").save(preview_path)
    return preview_path, preview_id


def advance_job(job_id: str) -> JobResponse:
    job = jobs[job_id]
    if job.status == "queued":
        next_job = job.model_copy(
            update={
                "status": "running",
                "progress": 30,
                "started_at": now_iso(),
            }
        )
        jobs[job_id] = next_job
        upsert_job(
            job_id=next_job.job_id,
            project_id=next_job.project_id,
            kind=next_job.kind,
            status=next_job.status,
            progress=next_job.progress,
            created_at=next_job.created_at,
            started_at=next_job.started_at,
            finished_at=next_job.finished_at,
            output_asset_ids=next_job.output_asset_ids,
            request_json=job_requests.get(next_job.job_id, {}),
        )
        persist_state()
        return next_job

    if job.status == "running":
        req = job_requests.get(job_id, {})
        output_asset_ids = list(job.output_asset_ids)

        if job.kind == "t2i":
            t2i_req = GenerateT2IRequest.model_validate(req)
            if not output_asset_ids:
                provider = get_t2i_provider()
                generated = provider.generate(t2i_req, job_id=job_id)
                image_asset = add_asset(
                    project_id=job.project_id,
                    asset_type="image",
                    url=generated.url,
                    mime_type=generated.mime_type,
                    width=generated.width,
                    height=generated.height,
                    metadata={
                        "prompt": t2i_req.prompt,
                        "negative_prompt": t2i_req.negative_prompt,
                        "model": t2i_req.model,
                        "aspect_ratio": t2i_req.aspect_ratio,
                        "seed": t2i_req.seed,
                        "provider": generated.provider,
                        **generated.metadata,
                    },
                )
                output_asset_ids.append(image_asset.id)

        if job.kind == "i2v":
            i2v_req = GenerateI2VRequest.model_validate(req)
            if not output_asset_ids:
                provider = get_i2v_provider()
                generated = provider.generate(i2v_req, job_id=job_id)
                video_asset = add_asset(
                    project_id=job.project_id,
                    asset_type="video",
                    url=generated.url,
                    mime_type=generated.mime_type,
                    width=generated.width,
                    height=generated.height,
                    duration_ms=generated.duration_ms,
                    metadata={
                        "prompt": i2v_req.prompt,
                        "model": i2v_req.model,
                        "aspect_ratio": i2v_req.aspect_ratio,
                        "resolution": i2v_req.resolution,
                        "reference_image_asset_id": i2v_req.reference_image_asset_id,
                        "provider": generated.provider,
                        **generated.metadata,
                    },
                )
                output_asset_ids.append(video_asset.id)

        if job.kind == "sprites_process":
            sprites_req = SpriteProcessRequest.model_validate(req)
            source_video = get_asset_by_id(
                sprites_req.project_id, sprites_req.source_video_asset_id
            )
            source_video_url = source_video.url if source_video else ""
            if not source_video_url:
                raise RuntimeError("Source video not found for sprite processing.")
            if not output_asset_ids:
                # ⚠ There used to be a bare `except Exception:` here that fell
                # back to run_mock_sprite_pipeline. The mock returns a random
                # picsum.photos stock image as the "sprite sheet" and reports
                # status="succeeded" - so a missing OpenCV, an unreachable
                # video, or a tolerance that ate every frame all handed the user
                # a photograph off the internet and called it their animation.
                #
                # Landmine 6 already wrote this rule after the same defect in
                # shadow:generatePatch: a mock may never stand in for a failed
                # real call at runtime. Mock-first is a development stage, so it
                # stays reachable - but only when explicitly asked for.
                if os.getenv("SHADOW_SPRITES_MOCK") == "1":
                    export_result = run_mock_sprite_pipeline(
                        job_id=job_id,
                        project_id=job.project_id,
                        source_video_url=source_video_url,
                        request=sprites_req,
                    )
                else:
                    export_result = run_real_sprite_pipeline(
                        job_id=job_id,
                        project_id=job.project_id,
                        source_video_url=source_video_url,
                        request=sprites_req,
                    )
                output_asset_ids = [
                    export_result.spritesheet_asset_id,
                    export_result.metadata_asset_id,
                ]
                if export_result.frames_zip_asset_id:
                    output_asset_ids.append(export_result.frames_zip_asset_id)
                if export_result.code_snippet_asset_id:
                    output_asset_ids.append(export_result.code_snippet_asset_id)

        next_job = job.model_copy(
            update={
                "status": "succeeded",
                "progress": 100,
                "finished_at": now_iso(),
                "output_asset_ids": output_asset_ids,
            }
        )
        jobs[job_id] = next_job
        upsert_job(
            job_id=next_job.job_id,
            project_id=next_job.project_id,
            kind=next_job.kind,
            status=next_job.status,
            progress=next_job.progress,
            created_at=next_job.created_at,
            started_at=next_job.started_at,
            finished_at=next_job.finished_at,
            output_asset_ids=next_job.output_asset_ids,
            request_json=job_requests.get(next_job.job_id, {}),
        )
        persist_state()
        return next_job

    return job


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "shadow-backend"}


@app.post("/api/v1/t2i/generate", response_model=CreateJobResponse, status_code=202)
def generate_t2i(payload: GenerateT2IRequest) -> CreateJobResponse:
    job_id = str(uuid4())
    job = JobResponse(
        job_id=job_id,
        project_id=payload.project_id,
        kind="t2i",
        status="queued",
        progress=0,
        created_at=now_iso(),
        started_at=None,
        finished_at=None,
        output_asset_ids=[],
    )
    jobs[job_id] = job
    job_requests[job_id] = payload.model_dump()
    upsert_job(
        job_id=job.job_id,
        project_id=job.project_id,
        kind=job.kind,
        status=job.status,
        progress=job.progress,
        created_at=job.created_at,
        started_at=job.started_at,
        finished_at=job.finished_at,
        output_asset_ids=job.output_asset_ids,
        request_json=job_requests[job_id],
    )
    persist_state()
    return CreateJobResponse(job_id=job_id, status="queued")


@app.post("/api/v1/i2v/generate", response_model=CreateJobResponse, status_code=202)
def generate_i2v(payload: GenerateI2VRequest) -> CreateJobResponse:
    job_id = str(uuid4())
    job = JobResponse(
        job_id=job_id,
        project_id=payload.project_id,
        kind="i2v",
        status="queued",
        progress=0,
        created_at=now_iso(),
        started_at=None,
        finished_at=None,
        output_asset_ids=[],
    )
    jobs[job_id] = job
    job_requests[job_id] = payload.model_dump()
    upsert_job(
        job_id=job.job_id,
        project_id=job.project_id,
        kind=job.kind,
        status=job.status,
        progress=job.progress,
        created_at=job.created_at,
        started_at=job.started_at,
        finished_at=job.finished_at,
        output_asset_ids=job.output_asset_ids,
        request_json=job_requests[job_id],
    )
    persist_state()
    return CreateJobResponse(job_id=job_id, status="queued")


@app.get("/api/v1/jobs/{job_id}", response_model=JobResponse)
def get_job(job_id: str) -> JobResponse:
    job = jobs.get(job_id)
    if not job:
        job_data = load_job_from_db(job_id)
        if job_data:
            hydrated = JobResponse.model_validate(job_data)
            jobs[job_id] = hydrated
            job = hydrated
    if not job:
        raise HTTPException(
            status_code=404,
            detail={
                "error": ErrorEnvelope(
                    code="job_not_found",
                    message=f"Job '{job_id}' was not found.",
                ).model_dump()
            },
        )
    return advance_job(job_id)


@app.get("/api/v1/assets", response_model=ListAssetsResponse)
def list_assets(
    project_id: str = Query(...), asset_type: AssetType | None = Query(default=None, alias="type")
) -> ListAssetsResponse:
    items = [
        AssetResponse.model_validate(item)
        for item in list_assets_from_db(project_id=project_id, asset_type=asset_type)
    ]
    # Keep runtime cache aligned for direct-lookup helper functions.
    assets_by_project[project_id] = items
    return ListAssetsResponse(items=items)


@app.post("/api/v1/sprites/process", response_model=CreateJobResponse, status_code=202)
def process_sprites(payload: SpriteProcessRequest) -> CreateJobResponse:
    job_id = str(uuid4())
    job = JobResponse(
        job_id=job_id,
        project_id=payload.project_id,
        kind="sprites_process",
        status="queued",
        progress=0,
        created_at=now_iso(),
        started_at=None,
        finished_at=None,
        output_asset_ids=[],
    )
    jobs[job_id] = job
    job_requests[job_id] = payload.model_dump()
    upsert_job(
        job_id=job.job_id,
        project_id=job.project_id,
        kind=job.kind,
        status=job.status,
        progress=job.progress,
        created_at=job.created_at,
        started_at=job.started_at,
        finished_at=job.finished_at,
        output_asset_ids=job.output_asset_ids,
        request_json=job_requests[job_id],
    )
    persist_state()
    return CreateJobResponse(job_id=job_id, status="queued")


@app.get("/api/v1/exports/{job_id}", response_model=ExportResponse)
def get_export(job_id: str) -> ExportResponse:
    export = exports_by_job.get(job_id)
    if not export:
        raise HTTPException(
            status_code=404,
            detail={
                "error": ErrorEnvelope(
                    code="export_not_ready",
                    message="Export is not ready for this job yet.",
                ).model_dump()
            },
        )
    return export


@app.get("/api/v1/local-assets/{asset_id}")
def get_local_asset(asset_id: str) -> FileResponse:
    asset = get_asset_global(asset_id)
    if not asset:
        raise HTTPException(status_code=404, detail="Asset not found.")
    local_path = asset.metadata.get("local_path")
    if not isinstance(local_path, str):
        raise HTTPException(status_code=404, detail="Asset is not locally stored.")
    path = Path(local_path)
    if not path.exists():
        raise HTTPException(status_code=404, detail="Asset file missing from storage.")
    return FileResponse(path=path, media_type=asset.mime_type, filename=path.name)


@app.post("/api/v1/sprites/preview-mask", response_model=SpriteMaskPreviewResponse)
def preview_sprite_mask(payload: SpriteMaskPreviewRequest) -> SpriteMaskPreviewResponse:
    source_video = get_asset_by_id(payload.project_id, payload.source_video_asset_id)
    if not source_video:
        raise HTTPException(status_code=404, detail="Source video asset not found.")
    try:
        preview_path, preview_id = create_mask_preview(
            source_video_url=source_video.url,
            frame_index=payload.frame_index,
            masking_options=payload.masking_options,
            masking_mode=payload.masking_mode,
        )
    except Exception as error:
        raise HTTPException(status_code=400, detail=str(error)) from error

    preview_files[preview_id] = (preview_path, "image/png")
    return SpriteMaskPreviewResponse(
        preview_id=preview_id,
        url=f"{ASSET_BASE_URL}/api/v1/preview-files/{preview_id}",
    )


@app.get("/api/v1/preview-files/{preview_id}")
def get_preview_file(preview_id: str) -> FileResponse:
    record = preview_files.get(preview_id)
    if not record:
        raise HTTPException(status_code=404, detail="Preview not found.")
    path, media_type = record
    if not path.exists():
        raise HTTPException(status_code=404, detail="Preview file missing.")
    return FileResponse(path=path, media_type=media_type, filename=path.name)


Base.metadata.create_all(bind=engine)
sync_state_file_to_db()
hydrate_runtime_from_db()
if not jobs and not assets_by_project:
    load_state()
