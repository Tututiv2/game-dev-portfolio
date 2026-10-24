"""Real text-to-image generation via Replicate.

This is the first NON-MOCK provider in the app. Until it existed, every "generated"
image in Shadow Studio was a placeholder from picsum.photos -- the adapter layer was
built and wired, but no provider ever called a real model.

Design notes worth keeping:

* **The image is downloaded to local storage, not left as a remote URL.** The mock
  provider returned a hotlink, which meant ``GET /api/v1/local-assets/{id}`` did not
  work for T2I output (only the sprite pipeline wrote real files). Downloading here
  makes every asset type uniform: one endpoint returns bytes for anything the app
  produced. Replicate's output URLs also expire, so persisting is required anyway.

* **``model`` is a free-form string passed straight through.** Replicate identifies a
  model as ``owner/name`` or ``owner/name:version``. Keeping it opaque means switching
  to a different (or better) pixel-art model is a config change, never a code change.

* **No SDK dependency.** ``httpx`` is already a requirement; the Replicate REST API is
  a POST plus a poll. Adding a vendor SDK for two HTTP calls would be the larger cost.
"""

from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx

from app.providers.base import T2IOutput

REPLICATE_API = "https://api.replicate.com/v1"

# Defaults chosen for game-sprite work rather than general illustration. Override with
# the REPLICATE_T2I_MODEL env var; anything Replicate hosts will work.
DEFAULT_MODEL = "black-forest-labs/flux-schnell"

ASPECT_TO_SIZE: dict[str, tuple[int, int]] = {
    "1:1": (1024, 1024),
    "16:9": (1344, 768),
    "9:16": (768, 1344),
    "4:3": (1152, 896),
    "3:4": (896, 1152),
}


class ReplicateT2IError(RuntimeError):
    """Raised for any failure that should surface to the caller as a failed job."""


class ReplicateT2IProvider:
    name = "replicate-t2i"

    def __init__(self, *, storage_root: Path | None = None) -> None:
        self.token = (os.getenv("REPLICATE_API_TOKEN") or "").strip()
        self.model = (os.getenv("REPLICATE_T2I_MODEL") or DEFAULT_MODEL).strip()
        self.timeout = float(os.getenv("REPLICATE_TIMEOUT_SECONDS") or 180)
        root = storage_root or Path(
            os.getenv("SHADOW_STORAGE_ROOT")
            or Path(__file__).resolve().parents[2] / ".shadow-assets"
        )
        self.image_dir = root / "images"
        self.image_dir.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------ public API

    def generate(self, request: Any, *, job_id: str) -> T2IOutput:
        if not self.token:
            raise ReplicateT2IError(
                "REPLICATE_API_TOKEN is not set. Add it to backend/.env "
                "(get one at https://replicate.com/account/api-tokens)."
            )

        model = (getattr(request, "model", "") or self.model).strip() or self.model
        aspect = getattr(request, "aspect_ratio", "1:1") or "1:1"
        width, height = ASPECT_TO_SIZE.get(aspect, ASPECT_TO_SIZE["1:1"])

        payload_input: dict[str, Any] = {
            "prompt": getattr(request, "prompt", ""),
            "width": width,
            "height": height,
        }
        negative = getattr(request, "negative_prompt", None)
        if negative:
            payload_input["negative_prompt"] = negative
        seed = getattr(request, "seed", None)
        if seed is not None:
            payload_input["seed"] = int(seed)

        image_url, raw = self._run_prediction(model, payload_input)
        local_path = self._download(image_url, job_id)

        return T2IOutput(
            provider=self.name,
            # Resolved to a real id by add_asset(); keeps T2I output downloadable
            # through the same endpoint as every other locally stored asset.
            url="{asset_base}/api/v1/local-assets/{asset_id}",
            mime_type="image/png",
            width=width,
            height=height,
            metadata={
                "local_path": str(local_path),
                "replicate_model": model,
                "replicate_prediction_id": raw.get("id"),
                "source_url": image_url,
            },
        )

    # ------------------------------------------------------------------ internals

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
            # Ask Replicate to block until the prediction finishes where it can, so the
            # common case needs no polling at all.
            "Prefer": "wait",
        }

    def _run_prediction(self, model: str, model_input: dict[str, Any]) -> tuple[str, dict]:
        if ":" in model:
            owner_name, version = model.split(":", 1)
            url = f"{REPLICATE_API}/predictions"
            body = {"version": version, "input": model_input}
        else:
            url = f"{REPLICATE_API}/models/{model}/predictions"
            body = {"input": model_input}

        with httpx.Client(timeout=self.timeout) as client:
            response = client.post(url, headers=self._headers(), json=body)
            if response.status_code >= 400:
                raise ReplicateT2IError(
                    f"Replicate rejected the request ({response.status_code}): {response.text[:400]}"
                )
            prediction = response.json()
            prediction = self._await_completion(client, prediction)

        status = prediction.get("status")
        if status != "succeeded":
            detail = prediction.get("error") or status
            raise ReplicateT2IError(f"Replicate prediction did not succeed: {detail}")

        image_url = self._first_output_url(prediction.get("output"))
        if not image_url:
            raise ReplicateT2IError(
                f"Replicate returned no image URL (output was {prediction.get('output')!r})."
            )
        return image_url, prediction

    def _await_completion(self, client: httpx.Client, prediction: dict) -> dict:
        """Poll until terminal. With Prefer: wait this usually returns immediately."""
        deadline = time.monotonic() + self.timeout
        while prediction.get("status") in {"starting", "processing"}:
            if time.monotonic() > deadline:
                raise ReplicateT2IError("Replicate prediction timed out.")
            poll_url = (prediction.get("urls") or {}).get("get")
            if not poll_url:
                raise ReplicateT2IError("Replicate response had no polling URL.")
            time.sleep(1.0)
            polled = client.get(poll_url, headers=self._headers())
            if polled.status_code >= 400:
                raise ReplicateT2IError(
                    f"Polling Replicate failed ({polled.status_code}): {polled.text[:200]}"
                )
            prediction = polled.json()
        return prediction

    @staticmethod
    def _first_output_url(output: Any) -> str | None:
        """Models disagree on output shape: a bare URL, a list, or {"image": url}."""
        if isinstance(output, str):
            return output
        if isinstance(output, list) and output:
            first = output[0]
            return first if isinstance(first, str) else None
        if isinstance(output, dict):
            for key in ("image", "url", "output"):
                value = output.get(key)
                if isinstance(value, str):
                    return value
        return None

    def _download(self, image_url: str, job_id: str) -> Path:
        target = self.image_dir / f"{job_id}-{uuid4().hex[:8]}.png"
        with httpx.Client(timeout=self.timeout, follow_redirects=True) as client:
            response = client.get(image_url)
            if response.status_code >= 400:
                raise ReplicateT2IError(
                    f"Could not download the generated image ({response.status_code})."
                )
            target.write_bytes(response.content)
        return target
