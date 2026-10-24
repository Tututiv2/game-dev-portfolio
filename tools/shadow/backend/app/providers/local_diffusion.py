"""Local text-to-image on the machine's own GPU. No API, no key, no cost.

WHY LOCAL IS THE RIGHT DEFAULT HERE, not a fallback:

* **It is free forever.** A hosted API bills per image, and the whole point of the forge
  is to generate three variants every time you have an idea. Per-image cost quietly
  discourages the exact behaviour the tool exists to encourage.
* **It is better at this specific job.** The models genuinely good at game sprites are
  community pixel-art checkpoints and LoRAs. They are free downloads and generally are
  not offered by hosted APIs at all.
* **It is faster.** A 512x512 SD1.5 sample on a mid-range modern GPU beats the network
  round-trip, queue wait, and cold start of a hosted call.

The model is loaded lazily on first use and then cached in module state, because loading
weights takes seconds and generating takes about one. ``LOCAL_T2I_MODEL`` points at any
diffusers-compatible checkpoint on Hugging Face or on disk, so swapping in a better
pixel-art model later is a config change.
"""

from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.providers.base import T2IOutput

# A pixel-art-trained SD1.5 checkpoint: small (~2GB), fast, public (no HF token), and
# aimed at exactly the output we want. SDXL is sharper in general but slower, heavier,
# and its extra fidelity is thrown away by the downscale to 16x16 anyway.
DEFAULT_MODEL = "PublicPrompts/All-In-One-Pixel-Model"

ASPECT_TO_SIZE: dict[str, tuple[int, int]] = {
    "1:1": (512, 512),
    "16:9": (704, 384),
    "9:16": (384, 704),
    "4:3": (640, 480),
    "3:4": (480, 640),
}

_pipeline: Any = None
_pipeline_key: str | None = None
_img2img: Any = None
_load_lock = threading.Lock()


class LocalDiffusionError(RuntimeError):
    """Raised for setup problems that should surface as a failed job with a clear cause."""


class LocalDiffusionT2IProvider:
    name = "local-diffusion"

    def __init__(self, *, storage_root: Path | None = None) -> None:
        self.model = (os.getenv("LOCAL_T2I_MODEL") or DEFAULT_MODEL).strip()
        self.steps = int(os.getenv("LOCAL_T2I_STEPS") or 22)
        self.guidance = float(os.getenv("LOCAL_T2I_GUIDANCE") or 7.5)
        root = storage_root or Path(
            os.getenv("SHADOW_STORAGE_ROOT")
            or Path(__file__).resolve().parents[2] / ".shadow-assets"
        )
        self.image_dir = root / "images"
        self.image_dir.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------ public API

    def generate(self, request: Any, *, job_id: str) -> T2IOutput:
        """Text-to-image, or image-to-image when the request carries a reference.

        IMAGE-TO-IMAGE IS THE ANSWER TO SILHOUETTE PROBLEMS. A Minecraft boots icon shows
        a PAIR of boots; no amount of prompt wording reliably makes a model draw two of
        something in the right arrangement. Handing it an existing icon as the starting
        point does: the composition is inherited, and the prompt only has to restyle it.

        `strength` is how far it is allowed to move from the reference (0 = unchanged,
        1 = ignore it entirely). Around 0.55-0.7 keeps the two-boot layout and proportions
        while fully repainting colour, material and detail.
        """
        reference = getattr(request, "reference_image", None)
        if reference is not None:
            return self._generate_img2img(request, reference, job_id=job_id)

        pipe, device = _load_pipeline(self.model)

        aspect = getattr(request, "aspect_ratio", "1:1") or "1:1"
        width, height = ASPECT_TO_SIZE.get(aspect, ASPECT_TO_SIZE["1:1"])
        prompt = getattr(request, "prompt", "") or ""
        negative = getattr(request, "negative_prompt", None)
        seed = getattr(request, "seed", None)

        import torch

        generator = None
        if seed is not None:
            generator = torch.Generator(device=device).manual_seed(int(seed))

        result = pipe(
            prompt=prompt,
            negative_prompt=negative or None,
            width=width,
            height=height,
            num_inference_steps=self.steps,
            guidance_scale=self.guidance,
            generator=generator,
        )
        image = result.images[0]

        target = self.image_dir / f"{job_id}-{uuid4().hex[:8]}.png"
        image.save(target, format="PNG")

        return T2IOutput(
            provider=self.name,
            url="{asset_base}/api/v1/local-assets/{asset_id}",
            mime_type="image/png",
            width=width,
            height=height,
            metadata={
                "local_path": str(target),
                "local_model": self.model,
                "device": device,
                "steps": self.steps,
                "guidance": self.guidance,
                "seed": seed,
            },
        )


    def _generate_img2img(self, request: Any, reference: Any, *, job_id: str) -> T2IOutput:
        from PIL import Image
        import torch

        pipe, device = _load_img2img(self.model)

        if isinstance(reference, (str, Path)):
            reference = Image.open(reference)
        # The reference is a game icon with a transparent background. SD works in RGB, and
        # compositing onto WHITE rather than black matters: a black backing bleeds dark
        # halos into the subject's edges, which then survive the pixel pipeline as a dirty
        # outline. White matches the "plain solid white background" the prompt asks for.
        reference = reference.convert("RGBA")
        canvas = Image.new("RGBA", reference.size, (255, 255, 255, 255))
        canvas.alpha_composite(reference)
        init = canvas.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS)

        strength = float(getattr(request, "strength", None) or 0.65)
        seed = getattr(request, "seed", None)
        generator = None
        if seed is not None:
            generator = torch.Generator(device=device).manual_seed(int(seed))

        result = pipe(
            prompt=getattr(request, "prompt", "") or "",
            negative_prompt=getattr(request, "negative_prompt", None) or None,
            image=init,
            strength=max(0.05, min(0.95, strength)),
            num_inference_steps=max(self.steps, 25),
            guidance_scale=self.guidance,
            generator=generator,
        )
        image = result.images[0]
        target = self.image_dir / f"{job_id}-{uuid4().hex[:8]}.png"
        image.save(target, format="PNG")

        return T2IOutput(
            provider=self.name,
            url="{asset_base}/api/v1/local-assets/{asset_id}",
            mime_type="image/png",
            width=image.width,
            height=image.height,
            metadata={
                "local_path": str(target),
                "local_model": self.model,
                "device": device,
                "mode": "img2img",
                "strength": strength,
                "seed": seed,
            },
        )


# ---------------------------------------------------------------------- loading


def _load_pipeline(model_id: str):
    """Load once, reuse forever.

    Guarded by a lock because FastAPI can advance two jobs concurrently and loading the
    same multi-gigabyte checkpoint twice would either exhaust VRAM or thrash the disk.
    """
    global _pipeline, _pipeline_key

    if _pipeline is not None and _pipeline_key == model_id:
        return _pipeline, _pipeline_device()

    with _load_lock:
        if _pipeline is not None and _pipeline_key == model_id:
            return _pipeline, _pipeline_device()

        try:
            import torch
            from diffusers import StableDiffusionPipeline
        except ImportError as error:  # pragma: no cover - environment problem, not logic
            raise LocalDiffusionError(
                "Local generation needs torch + diffusers. Install with:\n"
                "  pip install torch --index-url https://download.pytorch.org/whl/cu124\n"
                "  pip install diffusers transformers accelerate safetensors"
            ) from error

        cuda = torch.cuda.is_available()
        device = "cuda" if cuda else "cpu"
        dtype = torch.float16 if cuda else torch.float32

        pipe = StableDiffusionPipeline.from_pretrained(
            model_id,
            torch_dtype=dtype,
            # These checkpoints ship a safety checker that costs VRAM and time and
            # frequently black-frames legitimate game art (weapons, gore-adjacent
            # fantasy items). This is a local single-user art tool, so it is off.
            safety_checker=None,
            requires_safety_checker=False,
        )
        pipe = pipe.to(device)
        pipe.set_progress_bar_config(disable=True)

        if cuda:
            # Slicing trades a little speed for a lot of headroom, which keeps a big
            # request from OOM-ing a GPU that is also running a game/server.
            pipe.enable_attention_slicing()

        _pipeline = pipe
        _pipeline_key = model_id
        return _pipeline, device


def _load_img2img(model_id: str):
    """Img2img shares the text2img pipeline's already-loaded weights.

    from_pipe() reuses the same UNet/VAE/text encoder objects rather than loading a
    second multi-gigabyte copy, so having both modes available costs no extra VRAM.
    """
    global _img2img

    base, device = _load_pipeline(model_id)
    if _img2img is not None:
        return _img2img, device

    with _load_lock:
        if _img2img is None:
            from diffusers import StableDiffusionImg2ImgPipeline

            pipe = StableDiffusionImg2ImgPipeline.from_pipe(base)
            pipe.set_progress_bar_config(disable=True)
            _img2img = pipe
    return _img2img, device


def _pipeline_device() -> str:
    try:
        return str(_pipeline.device.type)  # type: ignore[union-attr]
    except Exception:
        return "cpu"


def warmup(model_id: str | None = None) -> str:
    """Download + load the model without generating. Useful for a first-run check."""
    target = (model_id or os.getenv("LOCAL_T2I_MODEL") or DEFAULT_MODEL).strip()
    _, device = _load_pipeline(target)
    return f"{target} ready on {device}"
