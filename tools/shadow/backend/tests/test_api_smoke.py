from fastapi.testclient import TestClient
from urllib.parse import urlsplit

from app.main import app


client = TestClient(app)


def test_health_endpoint() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "shadow-backend"}


def test_create_t2i_job_returns_queued() -> None:
    response = client.post(
        "/api/v1/t2i/generate",
        json={
            "project_id": "project-1",
            "prompt": "Mage with staff",
            "negative_prompt": "blurry",
            "model": "t2i-default-v1",
            "aspect_ratio": "1:1",
            "seed": 1,
            "reference_asset_id": None,
        },
    )
    assert response.status_code == 202
    body = response.json()
    assert body["status"] == "queued"
    assert body["job_id"]


def test_t2i_job_progression_creates_asset() -> None:
    created = client.post(
        "/api/v1/t2i/generate",
        json={
            "project_id": "project-progression",
            "prompt": "Knight standing idle",
            "negative_prompt": "",
            "model": "t2i-default-v1",
            "aspect_ratio": "1:1",
            "seed": 7,
            "reference_asset_id": None,
        },
    )
    job_id = created.json()["job_id"]

    first_poll = client.get(f"/api/v1/jobs/{job_id}")
    assert first_poll.status_code == 200
    assert first_poll.json()["status"] == "running"

    second_poll = client.get(f"/api/v1/jobs/{job_id}")
    assert second_poll.status_code == 200
    assert second_poll.json()["status"] == "succeeded"

    assets = client.get("/api/v1/assets?project_id=project-progression")
    assert assets.status_code == 200
    assert len(assets.json()["items"]) == 1
    assert assets.json()["items"][0]["type"] == "image"
    assert assets.json()["items"][0]["metadata"]["provider"] == "mock-t2i"


def test_i2v_job_progression_creates_video_asset() -> None:
    project_id = "project-i2v"

    t2i = client.post(
        "/api/v1/t2i/generate",
        json={
            "project_id": project_id,
            "prompt": "Rogue idle base",
            "negative_prompt": "",
            "model": "t2i-default-v1",
            "aspect_ratio": "1:1",
            "seed": 3,
            "reference_asset_id": None,
        },
    )
    t2i_job_id = t2i.json()["job_id"]
    client.get(f"/api/v1/jobs/{t2i_job_id}")
    client.get(f"/api/v1/jobs/{t2i_job_id}")

    image_assets = client.get(f"/api/v1/assets?project_id={project_id}").json()["items"]
    source_image = image_assets[0]

    i2v = client.post(
        "/api/v1/i2v/generate",
        json={
            "project_id": project_id,
            "prompt": "Subtle breathing loop",
            "model": "i2v-default-v1",
            "reference_image_asset_id": source_image["id"],
            "duration_seconds": 3,
            "resolution": "1024x1024",
            "aspect_ratio": "1:1",
            "fps_hint": 12,
        },
    )
    i2v_job_id = i2v.json()["job_id"]
    client.get(f"/api/v1/jobs/{i2v_job_id}")
    done = client.get(f"/api/v1/jobs/{i2v_job_id}")
    assert done.json()["status"] == "succeeded"

    all_assets = client.get(f"/api/v1/assets?project_id={project_id}").json()["items"]
    video_assets = [item for item in all_assets if item["type"] == "video"]
    assert len(video_assets) == 1
    assert (
        video_assets[0]["metadata"]["reference_image_asset_id"]
        == source_image["id"]
    )
    assert video_assets[0]["metadata"]["provider"] == "mock-i2v"


def test_sprites_process_creates_export_bundle() -> None:
    project_id = "project-sprite"
    i2v = client.post(
        "/api/v1/i2v/generate",
        json={
            "project_id": project_id,
            "prompt": "Simple idle loop",
            "model": "i2v-default-v1",
            "reference_image_asset_id": "mock-image-id",
            "duration_seconds": 3,
            "resolution": "1024x1024",
            "aspect_ratio": "1:1",
            "fps_hint": 12,
        },
    )
    i2v_job_id = i2v.json()["job_id"]
    client.get(f"/api/v1/jobs/{i2v_job_id}")
    client.get(f"/api/v1/jobs/{i2v_job_id}")

    video_assets = [
        item
        for item in client.get(f"/api/v1/assets?project_id={project_id}").json()["items"]
        if item["type"] == "video"
    ]
    assert video_assets

    sprite_job = client.post(
        "/api/v1/sprites/process",
        json={
            "project_id": project_id,
            "source_video_asset_id": video_assets[0]["id"],
            "masking_mode": "chroma",
            "masking_options": {"key_color_hex": "#00FF00", "tolerance": 40},
            "frame_selection": {"enabled_indices": [0, 1, 2, 3]},
            "alignment_mode": "both_centered",
            "export_options": {
                "sheet_layout": "horizontal",
                "include_frames_zip": True,
                "generate_code_snippet": True,
            },
        },
    )
    sprite_job_id = sprite_job.json()["job_id"]
    first = client.get(f"/api/v1/jobs/{sprite_job_id}")
    assert first.json()["status"] == "running"
    second = client.get(f"/api/v1/jobs/{sprite_job_id}")
    assert second.json()["status"] == "succeeded"

    export = client.get(f"/api/v1/exports/{sprite_job_id}")
    assert export.status_code == 200
    export_json = export.json()
    assert export_json["frame"]["count"] == 4
    assert export_json["spritesheet_asset_id"]
    if export_json["code_snippet_asset_id"]:
        assets = client.get(f"/api/v1/assets?project_id={project_id}").json()["items"]
        snippet_assets = [
            item for item in assets if item["id"] == export_json["code_snippet_asset_id"]
        ]
        assert snippet_assets
        snippet = snippet_assets[0]["metadata"].get("snippet", "")
        assert "function drawSprite" in snippet


def test_sprite_mask_preview_endpoint() -> None:
    project_id = "project-preview"
    i2v = client.post(
        "/api/v1/i2v/generate",
        json={
            "project_id": project_id,
            "prompt": "Idle loop for mask preview",
            "model": "i2v-default-v1",
            "reference_image_asset_id": "mock-image-id",
            "duration_seconds": 3,
            "resolution": "1024x1024",
            "aspect_ratio": "1:1",
            "fps_hint": 12,
        },
    )
    i2v_job_id = i2v.json()["job_id"]
    client.get(f"/api/v1/jobs/{i2v_job_id}")
    client.get(f"/api/v1/jobs/{i2v_job_id}")

    video_assets = [
        item
        for item in client.get(f"/api/v1/assets?project_id={project_id}").json()["items"]
        if item["type"] == "video"
    ]
    preview = client.post(
        "/api/v1/sprites/preview-mask",
        json={
            "project_id": project_id,
            "source_video_asset_id": video_assets[0]["id"],
            "masking_mode": "chroma",
            "masking_options": {"key_color_hex": "#00FF00", "tolerance": 36},
            "frame_index": 0,
        },
    )
    assert preview.status_code == 200
    preview_url = preview.json()["url"]
    assert "/api/v1/preview-files/" in preview_url

    preview_path = urlsplit(preview_url).path
    preview_file = client.get(preview_path)
    assert preview_file.status_code == 200
    assert preview_file.headers["content-type"].startswith("image/png")
