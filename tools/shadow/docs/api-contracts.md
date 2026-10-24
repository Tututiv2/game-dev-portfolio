# Shadow API Contracts (MVP v0)

This document defines frontend-facing API contracts for the MVP. These schemas are provider-agnostic and stable.

## Conventions

- Base path: `/api/v1`
- Content type: `application/json`
- IDs are UUID strings.
- Timestamps are ISO 8601 UTC strings.
- Long-running operations return a `job_id`.

## Error Envelope

All non-2xx responses:

```json
{
  "error": {
    "code": "string_code",
    "message": "Human-readable description",
    "details": {}
  }
}
```

## Job Status Enum

- `queued`
- `running`
- `succeeded`
- `failed`
- `cancelled`

---

## 1) Create T2I Job

`POST /api/v1/t2i/generate`

### Request

```json
{
  "project_id": "3f4f8180-7e15-4ad2-a65f-a65ea3f7c8f1",
  "prompt": "Pixel art mage idle pose, blue cloak, clean silhouette",
  "negative_prompt": "blurry, extra limbs",
  "model": "t2i-default-v1",
  "aspect_ratio": "1:1",
  "seed": 12345,
  "reference_asset_id": null
}
```

### Response (202)

```json
{
  "job_id": "4dd8442a-0c08-4ba7-8644-f98025c2e5d1",
  "status": "queued"
}
```

---

## 2) Create I2V Job

`POST /api/v1/i2v/generate`

### Request

```json
{
  "project_id": "3f4f8180-7e15-4ad2-a65f-a65ea3f7c8f1",
  "prompt": "Character breathing idle loop, subtle robe movement",
  "model": "i2v-default-v1",
  "reference_image_asset_id": "85c3d063-f3f9-4f17-8429-77e8a4f6b2c8",
  "duration_seconds": 3,
  "resolution": "1024x1024",
  "aspect_ratio": "1:1",
  "fps_hint": 12
}
```

### Response (202)

```json
{
  "job_id": "45d9af8c-2f56-4de8-9a35-6b4ca03ff3c1",
  "status": "queued"
}
```

---

## 3) Get Job Status

`GET /api/v1/jobs/{job_id}`

### Response (200, running)

```json
{
  "job_id": "45d9af8c-2f56-4de8-9a35-6b4ca03ff3c1",
  "project_id": "3f4f8180-7e15-4ad2-a65f-a65ea3f7c8f1",
  "kind": "i2v",
  "status": "running",
  "progress": 42,
  "created_at": "2026-04-16T10:30:00Z",
  "started_at": "2026-04-16T10:30:05Z",
  "finished_at": null,
  "output_asset_ids": []
}
```

### Response (200, succeeded)

```json
{
  "job_id": "45d9af8c-2f56-4de8-9a35-6b4ca03ff3c1",
  "project_id": "3f4f8180-7e15-4ad2-a65f-a65ea3f7c8f1",
  "kind": "i2v",
  "status": "succeeded",
  "progress": 100,
  "created_at": "2026-04-16T10:30:00Z",
  "started_at": "2026-04-16T10:30:05Z",
  "finished_at": "2026-04-16T10:31:12Z",
  "output_asset_ids": [
    "90e16f3a-9f56-4622-a57e-4d2b459fd80e"
  ]
}
```

---

## 4) List Assets

`GET /api/v1/assets?project_id={project_id}&type={optional}`

### Response (200)

```json
{
  "items": [
    {
      "id": "85c3d063-f3f9-4f17-8429-77e8a4f6b2c8",
      "project_id": "3f4f8180-7e15-4ad2-a65f-a65ea3f7c8f1",
      "type": "image",
      "url": "https://cdn.example.com/projects/.../images/85c3....png",
      "mime_type": "image/png",
      "width": 1024,
      "height": 1024,
      "duration_ms": null,
      "frame_count": null,
      "metadata": {
        "prompt": "Pixel art mage idle pose, blue cloak, clean silhouette",
        "model": "t2i-default-v1",
        "aspect_ratio": "1:1"
      },
      "created_at": "2026-04-16T10:29:40Z"
    }
  ]
}
```

---

## 5) Start Sprite Processing

`POST /api/v1/sprites/process`

### Request

```json
{
  "project_id": "3f4f8180-7e15-4ad2-a65f-a65ea3f7c8f1",
  "source_video_asset_id": "90e16f3a-9f56-4622-a57e-4d2b459fd80e",
  "masking_mode": "chroma",
  "masking_options": {
    "key_color_hex": "#00FF00",
    "tolerance": 36
  },
  "frame_selection": {
    "enabled_indices": [0, 1, 2, 3, 4, 5]
  },
  "alignment_mode": "both_centered",
  "export_options": {
    "sheet_layout": "horizontal",
    "include_frames_zip": true,
    "generate_code_snippet": true
  }
}
```

### Response (202)

```json
{
  "job_id": "d247aa17-9baf-4bac-9609-5cbf1b74d55c",
  "status": "queued"
}
```

---

## 6) Get Sprite Export Bundle

`GET /api/v1/exports/{job_id}`

Returns 404 until job completes.

### Response (200)

```json
{
  "job_id": "d247aa17-9baf-4bac-9609-5cbf1b74d55c",
  "project_id": "3f4f8180-7e15-4ad2-a65f-a65ea3f7c8f1",
  "status": "succeeded",
  "spritesheet_asset_id": "ef28dcd0-6bf4-4f12-ac96-1595bd46f2c3",
  "frames_zip_asset_id": "4733f8e2-1211-44ad-bb12-4f0033807054",
  "metadata_asset_id": "cfd4d6e5-84f5-49e9-b495-5a7efdc50f57",
  "code_snippet_asset_id": "3935ba10-4ef8-414f-a632-f45be84a742e",
  "alignment": {
    "offset_x": 0,
    "offset_y": -25
  },
  "frame": {
    "width": 256,
    "height": 256,
    "count": 6
  },
  "sheet": {
    "layout": "horizontal",
    "width": 1536,
    "height": 256
  }
}
```

---

## 7) Asset Relations (Optional Read Endpoint)

`GET /api/v1/assets/{asset_id}/lineage`

### Response (200)

```json
{
  "asset_id": "ef28dcd0-6bf4-4f12-ac96-1595bd46f2c3",
  "ancestors": [
    {
      "asset_id": "90e16f3a-9f56-4622-a57e-4d2b459fd80e",
      "type": "video"
    },
    {
      "asset_id": "85c3d063-f3f9-4f17-8429-77e8a4f6b2c8",
      "type": "image"
    }
  ]
}
```

---

## Validation Rules (MVP Defaults)

- `prompt` required, max 2000 chars.
- `aspect_ratio` one of: `1:1`, `16:9`, `9:16`, `4:3`, `3:4`.
- `duration_seconds` range: 1-8 for I2V MVP.
- `resolution` one of model-supported values.
- `tolerance` for chroma range: 0-100.
- `enabled_indices` must be unique, ascending, non-negative.

## Notes

- These contracts are intentionally simple and can be expanded with pagination, auth scopes, and websocket push updates in later phases.
