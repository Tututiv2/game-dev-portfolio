#!/usr/bin/env python3
"""
Phase 3, layer 3: the prop forge.

Spec section 17: props are "generated locally and free" -- a text prompt becomes a
triangle-budgeted, normal-baked, LOD'd mesh with no purchase and no API. The chain
is Flux schnell (image) -> Hunyuan3D 2.1 (mesh) -> mesh_pipeline.py (budget, UV,
bake, LODs, engine export), all on the local ComfyUI at 127.0.0.1:8188.

Run:
    python Tools/forge/forge_props.py               # everything not already made
    python Tools/forge/forge_props.py barrel_rusted # just one

Things that are load-bearing and were learned the hard way. Do not "simplify"
them:

  * Flux schnell is guidance-distilled. cfg MUST be 1.0 and steps 4. A normal
    cfg of 7 produces burnt garbage, and because the negative prompt is not
    applied at all, everything that matters has to be said POSITIVELY.
  * EmptySD3LatentImage, not EmptyLatentImage. Flux's latent is 16-channel; the
    4-channel SD node gives a shape error or noise.
  * DualCLIPLoader with type="flux". Flux conditions on CLIP-L *and* T5-XXL
    together; a single CLIPLoader silently under-conditions it.
  * Hunyuan3D must use the image encoder BUNDLED IN ITS OWN CHECKPOINT, loaded
    with ImageOnlyCheckpointLoader. Do NOT hand it a separate clip_vision file.
    This line used to say "Hunyuan3D needs clip_vision_l (1024-wide); sigclip is
    1152-wide and dies at sample time" -- true about sigclip, and completely the
    wrong conclusion. hunyuan_3d_v2.1.safetensors carries its own DINOv2-large
    under `conditioner.main_image_encoder.*`. CLIP-L is also 1024-wide, so
    substituting it raises no error at all; the model simply receives embeddings
    from an encoder it never saw in training and returns plausible-looking mush.
    A dimension match is not a model match, and "the other one crashed" is not
    evidence that this one is right.
  * Hunyuan3Dv2Conditioning returns BOTH conditionings from one node -- output 0
    is positive, output 1 is negative.
  * There is no text prompt in the mesh stage. It is conditioned purely on the
    image, which is why the "isolated object on flat white" framing does so much
    work. A photo with a background reconstructs the background as geometry.
  * Export FBX, not GLB. Unity 6 has no glTF importer, so a .glb lands as a
    DefaultAsset with no mesh and no error at all. See mesh_pipeline.py's
    engine_profile(), which this repo patched for exactly that reason.

Existing props are never silently overwritten: Unity keeps the old .meta and
GUID, so replacing a slug would turn everything already placed in a scene into a
different model. Delete the .fbx yourself to regenerate one.
"""

import glob
import json
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

COMFY = "http://127.0.0.1:8188"

# Set True once this run has actually queued work. The shutdown is gated on it.
#
# This script NEVER starts ComfyUI -- when the server is missing it tells you to
# start it by hand -- so every server it finds belongs to the user. An unconditional
# shutdown therefore kills a process it does not own. Concretely: with all six props
# already built, `python forge_props.py` prints "Every requested prop already exists",
# returns 0, and would taskkill a server that is midway through the user's own
# generation. The requirement is "stop ComfyUI when a batch finishes"; on the
# no-match, already-built and server-unreachable paths, no batch ran.
BATCH_STARTED = False

# --from-cache: re-run only the Blender half against the raw meshes already
# in _work/. No GPU, no ComfyUI, so the shutdown never fires either.
FROM_CACHE = False


class _SkipComfyCheck(Exception):
    pass

# Latent tokens for Hunyuan3D. See the note at the EmptyLatentHunyuan3Dv2 node.
LATENT_RESOLUTION = int(os.environ.get("FORGE_LATENT_RESOLUTION", "2048"))

# Flux runs at 768, not the usual 1024, and that is a hardware decision.
# Flux fp8 + T5-XXL is ~16 GB of weights against a 12.9 GB card, so ~2 GB is always
# offloaded. At 1024x1024 the activations on top of that pushed it into thrashing:
# a single sampling step did not complete in 12 minutes. At 768 it fits. The image
# is only a reference for Hunyuan3D, whose CLIP-vision encoder downsamples it to
# 384 anyway, so nothing downstream can tell the difference.
IMAGE_SIZE = 768

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORK = os.path.join(REPO, "Tools", "forge", "_work")
OUT = os.path.join(REPO, "Assets", "_Project", "Art", "Props")
MANIFEST = os.path.join(REPO, "Tools", "forge", "props.json")
PIPELINE = os.path.join(REPO, "Tools", "forge", "mesh_pipeline.py")
BLENDER = r"C:\Program Files\Blender Foundation\Blender 5.1\blender.exe"

MODELS = {
    "unet": "flux1-schnell-fp8-e4m3fn.safetensors",
    "clip_l": "clip_l.safetensors",
    "t5": "t5xxl_fp8_e4m3fn.safetensors",
    "vae": "flux-vae-bf16.safetensors",
    "hunyuan": "hunyuan_3d_v2.1.safetensors",
    # DELIBERATELY UNUSED by mesh_graph() now. Kept only so the mistake is
    # documented where someone would go looking to repeat it: the Hunyuan3D
    # encoder comes out of the checkpoint, not out of this file.
    "clip_vision_DO_NOT_USE_FOR_HUNYUAN": "clip_vision_l.safetensors",
}

# The framing sentence matters more than the subject words. Flux follows written
# prose better than a comma list, and since cfg is 1.0 the negative prompt does
# nothing, so the isolation has to be stated positively.
FRAMING = (
    "A single isolated object photographed alone on a plain flat white studio background. "
    "The entire object is visible with generous empty space around it, centred in frame, "
    "shown from a three-quarter angle with soft even lighting, sharp focus and fine detail. "
    "There is nothing else in the picture - no people, no hands, no scenery, no text."
)

# Props for Ashfall Depot. Triangle budgets are chosen per prop: a barrel reads
# fine at 1.5k, a rubble pile needs more silhouette to not look like a blob.
# (slug, subject, triangle budget, LONGEST-AXIS SIZE IN METRES)
#
# The size is not decoration. Hunyuan3D normalises every mesh it produces into a
# unit-ish box, so a barrel and an obelisk both come out ~1.97 m on their longest
# axis and everything lands in Unity at roughly twice life size. mesh_pipeline's
# --normalize-scale fits the LONGEST axis to a given number, so these are stated
# as the longest real dimension of the real object, not its height.
PROPS = [
    # 55-gallon drum: 0.88 m tall, 0.58 across. Height is the long axis.
    ("barrel_rusted", "a heavily rusted steel oil drum barrel, dented and weathered, industrial", 1500, 0.90),
    # A shipping crate you could sit on.
    ("crate_wooden", "a weathered wooden shipping crate made of rough planks, industrial storage", 1200, 0.90),
    # Waist-high rubble heap, wider than it is tall.
    ("debris_pile", "a pile of broken concrete rubble with twisted rebar sticking out", 2500, 1.60),
    # Skid-mounted industrial genset, long axis is its length.
    ("generator_unit", "a rusted industrial diesel generator unit with pipes, vents and a control panel", 3000, 2.00),
    # Euro pallet is 1.2 x 0.8; a stack keeps the 1.2 m long axis.
    ("pallet_stack", "a stack of worn wooden shipping pallets", 1500, 1.20),
    # Taller than a player, so it reads as a landmark.
    ("shrine_obelisk", "a weathered carved stone obelisk shrine covered in moss and runes", 2000, 2.50),
]


# ---------------------------------------------------------------------------
# ComfyUI plumbing
# ---------------------------------------------------------------------------

def post_json(path, payload):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(COMFY + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def get_json(path):
    with urllib.request.urlopen(COMFY + path, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def view_bytes(f):
    q = (f"/view?filename={urllib.parse.quote(f['filename'])}"
         f"&subfolder={urllib.parse.quote(f.get('subfolder', ''))}"
         f"&type={urllib.parse.quote(f.get('type', 'output'))}")
    with urllib.request.urlopen(COMFY + q, timeout=300) as r:
        return r.read()


def cutout(png_path):
    """Write an RGBA copy of a white-studio-background render with the background
    made transparent. Returns the new path.

    WHY THIS EXISTS -- it is the identified cause of all six failed props.
    Hunyuan3D 2.x expects the subject MASKED. Feeding it an opaque RGB frame means
    the white backdrop is part of the shape it is asked to reconstruct, and the
    reconstruction collapses toward the picture plane. `barrel_rusted_raw.glb`
    came back 0.0078 x 1.0117 x 0.786 m -- a 7.8 mm sheet -- straight out of the
    generator at 52,648 triangles, from a Flux image that is a perfectly good
    rusted drum. Nothing downstream can recover from that, which is why staged
    decimation improves shape fidelity in general and rescues none of these six.

    Done here in PIL rather than with a ComfyUI background-removal node on
    purpose: it needs no custom node to be installed, it is deterministic, and it
    can be checked offline against the cached images. The prompt already forces
    "a plain flat white studio background", so the matte is a threshold-and-fill
    rather than a learned segmentation.

    Flood-filling inward from the border matters. A plain "every near-white pixel
    is background" threshold also eats white highlights INSIDE the subject and
    punches holes through it; only background white is connected to the frame edge.
    """
    from PIL import Image
    import numpy as np

    out_path = os.path.splitext(png_path)[0] + "_cutout.png"
    img = Image.open(png_path).convert("RGB")
    rgb = np.asarray(img).astype(np.uint8)
    a = rgb.astype(np.int16)
    h, w, _ = a.shape

    # The backdrop is not flat white. It is a soft grey studio gradient with a
    # contact shadow, running roughly 200-250, so a fixed "near white" threshold
    # leaves most of it opaque -- the first version of this scored the barrel at
    # 81% subject coverage and visibly kept the whole background.
    #
    # Two-part test instead: LIGHT ENOUGH and NEUTRAL ENOUGH to be studio paper,
    # then connectivity from the frame edge. Connectivity is what makes it safe:
    # a white highlight in the middle of the subject satisfies the colour test but
    # is not joined to the border, so it keeps its alpha.
    light = a.min(axis=2) >= 195
    neutral = (a.max(axis=2) - a.min(axis=2)) <= 34
    candidate = light & neutral

    background = np.zeros((h, w), dtype=bool)
    background[0, :] = candidate[0, :]
    background[-1, :] = candidate[-1, :]
    background[:, 0] = candidate[:, 0]
    background[:, -1] = candidate[:, -1]

    # Morphological propagation rather than a per-pixel queue: same flood fill,
    # but each pass is one vectorised step over the whole image, so it finishes in
    # well under a second instead of grinding through 590k Python iterations.
    for _ in range(4 * max(h, w)):
        grown = background.copy()
        grown[1:, :] |= background[:-1, :]
        grown[:-1, :] |= background[1:, :]
        grown[:, 1:] |= background[:, :-1]
        grown[:, :-1] |= background[:, 1:]
        grown &= candidate
        if np.array_equal(grown, background):
            break
        background = grown

    alpha = np.where(background, 0, 255).astype(np.uint8)

    # FLATTEN THE RGB AS WELL, do not only set alpha.
    #
    # ComfyUI's LoadImage has separate IMAGE and MASK outputs, and the graph feeds
    # CLIPVisionEncode from ["3", 0] -- the IMAGE output, which is converted to RGB
    # with the alpha channel DISCARDED. An RGBA upload whose colour channels still
    # hold the original backdrop would therefore arrive at the encoder byte-identical
    # to the unmasked original, and this whole step would be an expensive no-op.
    #
    # So the background is also painted to a single flat white in the colour
    # channels. That is a real change even ignoring alpha: the Flux backdrop is a
    # soft gradient with a contact shadow under the subject, and a shadow is a depth
    # cue -- exactly the kind of thing an image-to-3D model can reconstruct as
    # geometry. Uniform white removes the cue entirely.
    flat = rgb.copy()
    flat[background] = 255

    # CROP TO THE SUBJECT, then re-centre on a square white canvas.
    #
    # CLIPVisionEncode downsamples to 384 px. The §17 prompt asks Flux for the
    # object "with generous empty space around it", which left the barrel covering
    # 39.6% of the frame -- so nearly two thirds of the conditioning budget was
    # spent describing blank paper. Filling the frame with the subject is free
    # resolution where it matters.
    subject = ~background
    ys, xs = np.where(subject)
    if len(ys):
        pad = int(0.06 * max(h, w))
        y0, y1 = max(0, ys.min() - pad), min(h, ys.max() + 1 + pad)
        x0, x1 = max(0, xs.min() - pad), min(w, xs.max() + 1 + pad)

        # Square it off around the subject's centre so the aspect ratio is not
        # distorted when the encoder resizes.
        side = max(y1 - y0, x1 - x0)
        cy, cx = (y0 + y1) // 2, (x0 + x1) // 2
        canvas_rgb = np.full((side, side, 3), 255, dtype=np.uint8)
        canvas_a = np.zeros((side, side), dtype=np.uint8)

        sy0, sx0 = max(0, cy - side // 2), max(0, cx - side // 2)
        sy1, sx1 = min(h, sy0 + side), min(w, sx0 + side)
        dy, dx = (side - (sy1 - sy0)) // 2, (side - (sx1 - sx0)) // 2
        canvas_rgb[dy:dy + (sy1 - sy0), dx:dx + (sx1 - sx0)] = flat[sy0:sy1, sx0:sx1]
        canvas_a[dy:dy + (sy1 - sy0), dx:dx + (sx1 - sx0)] = alpha[sy0:sy1, sx0:sx1]

        # Resize back to the ORIGINAL frame size. Handing the graph an arbitrary
        # crop dimension (696x696) made KSampler die with
        # "not enough values to unpack (expected 2, got 1)"; the same graph had
        # sampled a 768x768 frame minutes earlier without complaint. Keep the
        # framing win, keep the input geometry the model was fed before.
        canvas = Image.fromarray(np.dstack([canvas_rgb, canvas_a]), mode="RGBA")
        canvas = canvas.resize((w, h), Image.LANCZOS)
        arr = np.asarray(canvas)
        flat, alpha = arr[:, :, :3], arr[:, :, 3]

    Image.fromarray(np.dstack([flat, alpha]), mode="RGBA").save(out_path)

    coverage = float((alpha > 0).mean())
    return out_path, coverage


def upload_image(path):
    """POST a PNG into ComfyUI's input/ folder so LoadImage can find it."""
    boundary = "----shadowforge%d" % random.randint(0, 1 << 30)
    with open(path, "rb") as fh:
        blob = fh.read()

    body = b"".join([
        ("--%s\r\n" % boundary).encode(),
        ('Content-Disposition: form-data; name="image"; filename="%s"\r\n'
         % os.path.basename(path)).encode(),
        b"Content-Type: image/png\r\n\r\n",
        blob,
        ("\r\n--%s\r\n" % boundary).encode(),
        b'Content-Disposition: form-data; name="overwrite"\r\n\r\ntrue\r\n',
        ("--%s--\r\n" % boundary).encode(),
    ])

    req = urllib.request.Request(
        COMFY + "/upload/image", data=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(req, timeout=120) as r:
        info = json.loads(r.read().decode("utf-8"))
    return f"{info['subfolder']}/{info['name']}" if info.get("subfolder") else info["name"]


def free_models():
    """POST /free. Skipped entirely under --from-cache.

    That mode does no GPU work, so any ComfyUI listening is the owner's own, and
    /free carries {"unload_models": true, "free_memory": true} -- it would
    evict the models out from under whatever they are generating. Milder than the
    taskkill BATCH_STARTED already guards, and the same class of harm; worse,
    the caller swallows exceptions, so it would fail silently either way.
    """
    if FROM_CACHE:
        return
    """Unload everything ComfyUI is holding on the GPU.

    The card has 12 GB and Flux fp8 plus T5-XXL is about 16 GB, so Flux ALWAYS
    loads partially here -- around 2 GB offloaded even on an empty card. That is
    survivable. What is not survivable is Hunyuan3D's weights still being resident
    on top of it: sampling then degrades from tens of seconds per step to a
    measured 232 SECONDS per step, a roughly 50x slowdown, while still looking
    like it is working. Freeing between stages keeps the damage to the unavoidable
    part.

    /free returns 200 with an EMPTY body, so this must not try to parse JSON.
    """
    try:
        req = urllib.request.Request(
            COMFY + "/free",
            data=json.dumps({"unload_models": True, "free_memory": True}).encode("utf-8"),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            r.read()
        time.sleep(3)
        print("    freed GPU memory", flush=True)
    except Exception as exc:
        print(f"    (could not free GPU memory: {exc})", flush=True)


def run_graph(graph, want, label, timeout=1800):
    """Queue a graph and wait. `want` filters the output files we care about.

    Cold runs are genuinely slow: Flux fp8 plus T5-XXL do not both fit in 12 GB,
    so the first image can page for several minutes. The timeout is generous on
    purpose -- a forge that gives up at 60s just looks broken.
    """
    prompt_id = post_json("/prompt", {"prompt": graph})["prompt_id"]
    print(f"    queued {label} as {prompt_id}", flush=True)

    started = time.time()
    while time.time() - started < timeout:
        try:
            history = get_json(f"/history/{prompt_id}")
        except urllib.error.URLError:
            time.sleep(2)
            continue

        entry = history.get(prompt_id)
        if entry:
            status = (entry.get("status") or {}).get("status_str", "")
            if status == "error":
                raise RuntimeError(f"{label} failed inside ComfyUI: "
                                   f"{json.dumps(entry.get('status'))[:600]}")

            found = []
            for node_out in (entry.get("outputs") or {}).values():
                for items in node_out.values():
                    if not isinstance(items, list):
                        continue
                    for item in items:
                        if isinstance(item, dict) and item.get("filename") and want(item["filename"]):
                            found.append(item)
            if found:
                print(f"    {label} done in {time.time() - started:.0f}s", flush=True)
                return found
            if status == "success":
                raise RuntimeError(f"{label} finished but produced no matching file")
        time.sleep(2)

    raise TimeoutError(f"{label} exceeded {timeout}s")


# ---------------------------------------------------------------------------
# graphs (copied from Shadow's comfyBridge.ts, not written from memory)
# ---------------------------------------------------------------------------

def flux_graph(prompt, seed, prefix):
    return {
        "1": {"class_type": "UNETLoader",
              "inputs": {"unet_name": MODELS["unet"], "weight_dtype": "fp8_e4m3fn"}},
        "2": {"class_type": "DualCLIPLoader",
              "inputs": {"clip_name1": MODELS["clip_l"], "clip_name2": MODELS["t5"],
                         "type": "flux"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": MODELS["vae"]}},
        "4": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["2", 0]}},
        # schnell ignores the negative, but KSampler still requires the input.
        "5": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["2", 0]}},
        "6": {"class_type": "EmptySD3LatentImage",
              "inputs": {"width": IMAGE_SIZE, "height": IMAGE_SIZE, "batch_size": 1}},
        "7": {"class_type": "KSampler",
              "inputs": {"seed": seed, "steps": 4, "cfg": 1.0, "sampler_name": "euler",
                         "scheduler": "simple", "denoise": 1, "model": ["1", 0],
                         "positive": ["4", 0], "negative": ["5", 0], "latent_image": ["6", 0]}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["3", 0]}},
        "9": {"class_type": "SaveImage",
              "inputs": {"images": ["8", 0], "filename_prefix": prefix}},
    }


def mesh_graph(image_name, seed, prefix):
    return {
        # ImageOnlyCheckpointLoader, NOT CheckpointLoaderSimple — and this is the
        # whole ballgame.
        #
        # hunyuan_3d_v2.1.safetensors SHIPS ITS OWN IMAGE ENCODER: 439 tensors under
        # `conditioner.main_image_encoder.*`, and they are DINOv2-large, not CLIP —
        # patch_embeddings.projection is [1024, 3, 14, 14], position_embeddings is
        # [1, 1370, 1024] (1 cls + 37x37 patches = 518 px input), and every layer has
        # `layer_scale1/2.lambda1`, which is LayerScale, a DINOv2 hallmark that CLIP
        # ViT-L does not have.
        #
        # The old graph loaded a SEPARATE clip_vision_l.safetensors — CLIP ViT-L/14 at
        # 224/336 px, no layer scale — and fed that instead. Both are 1024-wide, so
        # cross-attention accepted it and NOTHING ERRORED. The DiT just got embeddings
        # from an encoder it was never trained against and sampled mush that loosely
        # tracks the picture: an 8 mm sheet, then a flat panel and a slab in an L.
        #
        # The comment that used to sit at the top of this file is the trap in
        # miniature: "Hunyuan3D needs clip_vision_l (1024-wide), sigclip is 1152 and
        # dies at sample time". SigCLIP failing was read as proof that CLIP-L was
        # right, when all it proved was that 1152 != 1024. Fitting dimensionally is
        # not the same as being the right model.
        #
        # ComfyUI already knows how to do this properly and needs no extra file:
        #   comfy/supported_models.py:1214  clip_vision_prefix =
        #       "conditioner.main_image_encoder.model."   (Hunyuan3Dv2_1 inherits it)
        #   comfy/clip_vision.py:135        detects `encoder.layer.23.layer_scale2
        #       .lambda1` and loads dino2_large.json
        # The checkpoint has exactly layers 0..23, so that branch matches.
        #
        # ImageOnlyCheckpointLoader returns (MODEL, CLIP_VISION, VAE), so the encoder
        # is output 1 and the VAE stays output 2 as before.
        "1": {"class_type": "ImageOnlyCheckpointLoader",
              "inputs": {"ckpt_name": MODELS["hunyuan"]}},
        "3": {"class_type": "LoadImage", "inputs": {"image": image_name}},
        "4": {"class_type": "CLIPVisionEncode",
              "inputs": {"clip_vision": ["1", 1], "image": ["3", 0], "crop": "center"}},
        # One node, two outputs: 0 = positive, 1 = negative.
        "5": {"class_type": "Hunyuan3Dv2Conditioning",
              "inputs": {"clip_vision_output": ["4", 0]}},
        "6": {"class_type": "EmptyLatentHunyuan3Dv2",
              # Shadow's default is 3072. Lowered because ComfyUI's Hunyuan3D 2.1
              # forward does an unconditional `context.chunk(2, dim=0)`
              # (comfy/ldm/hunyuan3dv2_1/hunyuandit.py:608), i.e. it REQUIRES the
              # positive and negative conditioning to arrive batched in a single
              # call. ComfyUI splits them into separate calls whenever it decides
              # memory is tight, and then chunk(2) gets 1 and raises
              # "not enough values to unpack (expected 2, got 1)" before step 1.
              #
              # Switching to the checkpoint's own DINOv2 encoder makes that far
              # more likely: DINOv2-large at 518 px emits 1370 context tokens
              # against CLIP-L's 257, so cross-attention needs roughly five times
              # the memory per call. Shrinking the latent buys that back.
              "inputs": {"resolution": LATENT_RESOLUTION, "batch_size": 1}},
        "7": {"class_type": "KSampler",
              "inputs": {"seed": seed, "steps": 30, "cfg": 5.0, "sampler_name": "euler",
                         "scheduler": "normal", "denoise": 1, "model": ["1", 0],
                         "positive": ["5", 0], "negative": ["5", 1], "latent_image": ["6", 0]}},
        "8": {"class_type": "VAEDecodeHunyuan3D",
              "inputs": {"samples": ["7", 0], "vae": ["1", 2],
                         "num_chunks": 8000, "octree_resolution": 256}},
        "9": {"class_type": "VoxelToMeshBasic", "inputs": {"voxel": ["8", 0], "threshold": 0.6}},
        "10": {"class_type": "SaveGLB", "inputs": {"mesh": ["9", 0], "filename_prefix": prefix}},
    }


# ---------------------------------------------------------------------------

def make_image(slug, subject, seed):
    """Stage 1 for one prop. Kept separate from the mesh stage on purpose --
    see run_all() for why the two stages are batched rather than interleaved."""
    work = os.path.join(WORK, slug)
    os.makedirs(work, exist_ok=True)

    prompt = f"{subject}. {FRAMING}"
    png = os.path.join(work, slug + ".png")
    sidecar = os.path.join(work, slug + ".seed")

    # If a cached image is reused, the seed that MADE it has to be reused too.
    # Reusing the pixels while recording a fresh random seed writes a manifest
    # that cannot reproduce its own output -- which is the exact failure the
    # "record the seed" rule exists to prevent.
    if os.path.exists(png) and os.path.exists(sidecar):
        with open(sidecar, encoding="utf-8") as fh:
            cached = int(fh.read().strip())
        print(f"[{slug}] image already generated (seed={cached}), reusing")
        return png, prompt, cached

    if os.path.exists(png):
        # Pixels with no recorded seed are not reproducible, so they are not
        # trustworthy provenance. Regenerate rather than lie about the seed.
        print(f"[{slug}] cached image has no recorded seed; regenerating")

    print(f"[{slug}] image, seed={seed}", flush=True)
    images = run_graph(flux_graph(prompt, seed, f"forge_{slug}"),
                       lambda f: f.lower().endswith(".png"), "flux image", timeout=900)
    with open(png, "wb") as fh:
        fh.write(view_bytes(images[0]))
    with open(sidecar, "w", encoding="utf-8") as fh:
        fh.write(str(seed))
    return png, prompt, seed


def forge(slug, subject, target_tris, real_size, seed, png, prompt, from_cache=False):
    final = os.path.join(OUT, slug + ".fbx")
    work = os.path.join(WORK, slug)
    os.makedirs(OUT, exist_ok=True)

    # 2. image -> mesh. Background removed FIRST — see cutout(). Sending the raw
    #    white-backdrop RGB is what produced six unusable props.
    raw_cached = os.path.join(work, slug + "_raw.glb")
    if from_cache:
        # Re-run only the Blender half, off the mesh Hunyuan3D already produced.
        # Rescaling, re-budgeting and re-baking need no GPU and no ComfyUI, and
        # every raw mesh is kept in _work/, so there is no reason to regenerate.
        if not os.path.exists(raw_cached):
            raise RuntimeError(f"{slug}: --from-cache but no {raw_cached}")
        print(f"[{slug}] from cache: {os.path.basename(raw_cached)} "
              f"(no GPU, no ComfyUI)", flush=True)
        return _budget_and_export(slug, subject, target_tris, real_size, seed,
                                  png, prompt, raw_cached, work)

    masked, coverage = cutout(png)
    print(f"[{slug}] cutout: subject covers {coverage * 100:.1f}% of frame", flush=True)
    if coverage > 0.97:
        print(f"[{slug}] WARNING: almost nothing was removed, so the backdrop is not "
              f"plain white and the mask is probably wrong. Hunyuan3D will see an "
              f"unmasked frame, which is the failure mode this step exists to avoid.",
              file=sys.stderr)
    elif coverage < 0.02:
        print(f"[{slug}] WARNING: the mask removed nearly everything — the subject is "
              f"gone. Check {masked}.", file=sys.stderr)

    print(f"[{slug}] mesh, budget={target_tris} tris", flush=True)
    uploaded = upload_image(masked)
    meshes = run_graph(mesh_graph(uploaded, seed, f"forge_{slug}"),
                       lambda f: f.lower().endswith((".glb", ".gltf")), "hunyuan3d mesh")
    raw = os.path.join(work, slug + "_raw.glb")
    with open(raw, "wb") as fh:
        fh.write(view_bytes(meshes[0]))

    return _budget_and_export(slug, subject, target_tris, real_size, seed,
                              png, prompt, raw, work)


def _budget_and_export(slug, subject, target_tris, real_size, seed, png, prompt,
                       raw, work):
    """Everything after Hunyuan3D: budget, UV, bake, scale, LODs, FBX export.

    Split out so `--from-cache` can re-run it against a raw mesh generated
    earlier. Rescaling six props costs a few seconds of Blender and no GPU at
    all, which matters because the scale was wrong on every prop and regenerating
    them would otherwise have meant another 20 minutes of GPU."""
    final = os.path.join(OUT, slug + ".fbx")

    # 3. mesh -> game-ready FBX. A generated mesh arrives around 200k triangles,
    #    so a budget is not optional.
    out_arg = os.path.join(work, slug + ".fbx")
    proc = subprocess.run(
        [BLENDER, "--background", "--factory-startup", "--python", PIPELINE, "--",
         "--input", raw, "--output", out_arg, "--engine", "unity",
         "--target-tris", str(target_tris), "--bake", "--lods", "0.5,0.25",
         "--normalize-scale", str(real_size)],
        capture_output=True, text=True, timeout=1800)

    result = None
    for line in proc.stdout.splitlines():
        if line.startswith("__SHADOW_MESH_RESULT__"):
            result = json.loads(line.split("__SHADOW_MESH_RESULT__", 1)[1].strip())
    if result is None:
        print(proc.stdout[-3000:])
        print(proc.stderr[-3000:], file=sys.stderr)
        raise RuntimeError(f"{slug}: mesh_pipeline produced no result line")
    if not result.get("ok"):
        raise RuntimeError(f"{slug}: mesh_pipeline failed: {result}")

    # Read the pipeline's own `output` field. It rewrites the extension to match
    # the engine, so assuming the path we passed is how you end up with nothing.
    produced = result["output"]

    # Clear this slug's leftovers BEFORE writing, so the base mesh and every LOD
    # are replaced together rather than one at a time. The GUIDs are PRESERVED,
    # not refreshed -- see below.
    #
    # THE .meta FILES ARE KEPT, AND THAT IS A REVERSAL.
    #
    # This used to delete each output's .meta as well, to force a fresh GUID. The
    # reasoning was that overwriting an .fbx in place keeps the old GUID while the
    # geometry changes underneath it -- a silent substitution. True, and it stopped
    # being the worse option the moment props were actually placed: PropDresser put
    # 22 instances into AshfallDepot.unity, and barrel_rusted's GUID alone appears
    # 128 times in that file. A fresh GUID does not protect those instances, it
    # ORPHANS them -- every placed prop becomes a missing-model reference,
    # recoverable only from git, in the one file this project intends hand dressing
    # to accumulate in.
    #
    # For a prop that is placed, "same asset, better mesh" is exactly what a
    # re-forge is for, so GUID stability is the property you want. The original
    # concern -- a half-regenerated set where the base is new and an LOD is stale --
    # is handled by clearing the whole slug's outputs together, which does not
    # require touching the sidecars.
    #
    # scene_reference_count() prints how many placements are about to change, so it
    # is loud rather than silent.
    clear_stale_outputs(slug)

    os.replace(produced, final)

    # LODs alongside the base mesh. No LODGroup is wired up -- that is dressing
    # work in the editor. At these budgets (1-3k tris) they buy little on a 56 m
    # map, but they are generated, correct, and cost a few tens of KB.
    lod_paths = []
    for lod in result.get("lods", []):
        src = lod.get("path")
        if not src or not os.path.exists(src):
            continue
        dst = os.path.join(OUT, f"{slug}_LOD{lod['level']}.fbx")
        os.replace(src, dst)
        lod_paths.append(os.path.relpath(dst, REPO))

    print(f"[{slug}] {result['sourceTris']} -> {result['finalTris']} tris, "
          f"normalMap={bool(result.get('normalMap'))}, lods={len(result.get('lods', []))}"
          + (f", bakeError={result['bakeError']}" if result.get("bakeError") else ""))

    return {
        "slug": slug, "subject": subject, "seed": seed, "prompt": prompt,
        "targetTris": target_tris, "sourceTris": result["sourceTris"],
        "finalTris": result["finalTris"], "lods": lod_paths,
        "bakeError": result.get("bakeError"), "output": os.path.relpath(final, REPO),
        # Persist the shape verdict, not just the counts. A triangle count and a
        # null bakeError are both true of a flat sheet, which is how six unusable
        # props got recorded as successes.
        "sourceFlatness": result.get("sourceFlatness"),
        "sourceSolidity": result.get("sourceSolidity"),
        "degenerateReasons": result.get("degenerateReasons"),
        "degenerate": result.get("degenerate"),
        "decimateStages": result.get("decimateStages"),
    }


SCENE_GLOB = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "Assets", "**", "*.unity")


def guid_of(fbx_path):
    """The GUID Unity assigned to an .fbx, read from its .meta, or None."""
    meta = fbx_path + ".meta"
    if not os.path.exists(meta):
        return None
    with open(meta, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("guid:"):
                return line.split(":", 1)[1].strip()
    return None


def scene_reference_count(guid):
    """How many times a GUID appears across every scene. (count, [scene names])."""
    if not guid:
        return 0, []
    total, scenes = 0, []
    for path in glob.glob(SCENE_GLOB, recursive=True):
        with open(path, encoding="utf-8", errors="replace") as fh:
            n = fh.read().count(guid)
        if n:
            total += n
            scenes.append(f"{os.path.basename(path)} x{n}")
    return total, scenes


def clear_stale_outputs(slug):
    """Delete one slug's .fbx outputs, DELIBERATELY KEEPING their .meta sidecars.

    Keeping the sidecar keeps the GUID, so anything already placed in a scene goes
    on pointing at this asset and simply renders the new mesh. Deleting it would
    mint a fresh GUID and turn every placement into a missing model -- see the long
    comment at the call site.

    Clearing the whole slug at once (base plus every LOD) is what stops a
    half-regenerated set, which is what the sidecar deletion was really for.
    """
    base = os.path.join(OUT, slug + ".fbx")
    guid = guid_of(base)
    refs, scenes = scene_reference_count(guid)
    if refs:
        print(f"[{slug}] WARNING: this prop is PLACED IN A SCENE -- {refs} reference(s) "
              f"in {', '.join(scenes)}. Its GUID {guid} is being kept, so every one of "
              f"those placements will render the NEW mesh. If the new mesh is a "
              f"different size or shape, the dressing will need revisiting.", flush=True)

    victims = sorted(set(
        glob.glob(os.path.join(OUT, slug + ".fbx"))
        + glob.glob(os.path.join(OUT, slug + "_LOD*.fbx"))
    ))

    removed = []
    for path in victims:
        if os.path.exists(path):
            os.remove(path)
            removed.append(os.path.basename(path))

    if removed:
        print(f"[{slug}] cleared stale outputs (sidecars kept, so GUIDs and any "
              f"existing placements survive): {', '.join(removed)}", flush=True)


def comfy_pid():
    """The PID of whatever is listening on ComfyUI's port, or None.

    The documented way to stop ComfyUI on this machine is
    ``taskkill /F /IM python.exe /T`` -- ``pkill`` does not match the process on
    Windows, and without ``/T`` the child that actually holds the CUDA context
    survives. But **this script is itself python**, so the by-image-name form
    kills the forge along with the server, in the middle of writing the manifest.
    Resolving the single PID that owns the port stops the server and nothing else.
    """
    port = COMFY.rsplit(":", 1)[-1]
    try:
        out = subprocess.run(["netstat", "-ano", "-p", "TCP"],
                             capture_output=True, text=True, timeout=30).stdout
    except Exception:
        return None

    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[3].upper() == "LISTENING" \
                and parts[1].endswith(":" + port):
            pid = parts[-1]
            if pid.isdigit() and int(pid) != os.getpid():
                return int(pid)
    return None


def stop_comfy():
    """Shut ComfyUI down at the end of a batch.

    While it is alive it pins roughly 16 GB of VRAM and holds the GPU at 100%,
    which makes the machine unusable for games or streaming. A finished batch
    must not leave it running -- one was once left up for hours after a batch.
    """
    pid = comfy_pid()
    if pid is None:
        print("ComfyUI is not listening on its port; nothing to stop.")
        return

    print(f"\nStopping ComfyUI (pid {pid}) and releasing its VRAM...", flush=True)
    subprocess.run(["taskkill", "/F", "/PID", str(pid), "/T"],
                   capture_output=True, text=True)

    for _ in range(20):
        time.sleep(0.5)
        try:
            get_json("/system_stats")
        except Exception:
            print("ComfyUI stopped. GPU released.")
            print("NOTE: if you had the ComfyUI web UI open in a browser, that tab has just "
                  "lost its server and will sit on 'Reconnecting'. Restart ComfyUI to get it "
                  "back, or run the forge with --keep-alive next time to leave it up.")
            return

    print("ComfyUI is STILL RESPONDING after taskkill. Stop it by hand before "
          "gaming or streaming:\n    taskkill /F /IM python.exe /T", file=sys.stderr)


def main():
    """Thin wrapper whose only job is to guarantee the GPU is released.

    The shutdown was first written as a try/finally around the two work stages,
    then patched onto one early return, and the no-match path still slipped
    through: `forge_props.py barrle_rusted` (a typo) printed its complaint and
    exited with ComfyUI up and ~16 GB of VRAM pinned. Patching exits one at a
    time loses that race every time somebody adds another `return`. Wrapping the
    whole of main is the only version that cannot be got wrong later.

    stop_comfy() is safe when nothing is listening -- it says so and returns --
    so this is correct even on the "server unreachable" path.
    """
    global FROM_CACHE
    keep_alive = "--keep-alive" in sys.argv[1:]
    FROM_CACHE = "--from-cache" in sys.argv[1:]
    try:
        return _run([a for a in sys.argv[1:]
                     if a not in ("--keep-alive", "--from-cache")])
    finally:
        if not BATCH_STARTED:
            print("No batch ran, so ComfyUI was left exactly as it was found.")
        elif keep_alive:
            print("\n--keep-alive: ComfyUI left running. It is still holding the GPU.")
        else:
            stop_comfy()


def _run(argv):
    wanted = set(argv)
    todo = [p for p in PROPS if not wanted or p[0] in wanted]
    if wanted and not todo:
        print(f"No prop matches {wanted}. Known: {[p[0] for p in PROPS]}")
        return 1

    if FROM_CACHE:
        print("--from-cache: rebuilding from the raw meshes in _work/, "
              "no GPU and no ComfyUI needed.")
    try:
        if FROM_CACHE:
            raise _SkipComfyCheck()
        get_json("/system_stats")
    except _SkipComfyCheck:
        pass
    except Exception:
        print(f"ComfyUI is not reachable at {COMFY}. Start it "
              f"(ComfyUI/run_nvidia_gpu.bat) and try again.", file=sys.stderr)
        return 2

    manifest = {}
    if os.path.exists(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as fh:
            manifest = {e["slug"]: e for e in json.load(fh).get("props", [])}

    # Skip anything already built. Unity keeps the old .meta and GUID, so silently
    # regenerating a slug turns everything already placed in a scene into a
    # different model.
    # --from-cache deliberately IGNORES this filter: the whole point of that mode
    # is to rebuild props that already exist (wrong scale, wrong budget) without
    # regenerating them. That makes a bare `--from-cache` with no slug argument
    # rewrite EVERY prop, including ones already placed in the map -- which is
    # survivable only because clear_stale_outputs() keeps the .meta sidecars and
    # therefore the GUIDs. It used to delete them, which would have turned all 22
    # placed prop instances into missing models.
    if not FROM_CACHE:
        todo = [p for p in todo if not os.path.exists(os.path.join(OUT, p[0] + ".fbx"))]
    if not todo:
        print("Every requested prop already exists. Delete an .fbx to regenerate it.")
        return 0

    # BATCH BY STAGE, and this is not a style choice.
    #
    # Flux fp8 plus T5-XXL is ~16 GB of weights and this card has 12 GB, so
    # ComfyUI evicts Flux to make room for Hunyuan3D and reloads it afterwards.
    # Doing prop-at-a-time (flux, mesh, flux, mesh...) pays that reload for EVERY
    # prop -- measured at ~9 minutes each. Running all the images first, then all
    # the meshes, pays it exactly twice for the whole batch.
    seeds = {slug: random.randint(1, 2 ** 31 - 1) for slug, _, _, _ in todo}

    # Say so BEFORE starting, not after. A cold first image is ~500 s of reading
    # weights off disk, so even a small batch takes tens of minutes, and for all
    # of it the GPU is at 100% with ~16 GB of VRAM pinned.
    if FROM_CACHE:
        print(f"Rebuilding {len(todo)} prop(s) from cached raw meshes. "
              f"No GPU, no ComfyUI, nothing pinned.", flush=True)
    else:
        print("=" * 72)
        print(f"  STARTING A GPU BATCH: {len(todo)} prop(s).")
    print("  This holds the GPU at 100% and pins ~16 GB of VRAM until it finishes.")
    print("  The machine will not be usable for games or streaming until then.")
    print(f"  Rough estimate: {8 + len(todo) * 2}-{12 + len(todo) * 4} minutes.")
    print("  ComfyUI is stopped automatically at the end (--keep-alive to leave it up).")
    print("=" * 72, flush=True)

    # From here on a batch is genuinely running, so the GPU becomes ours to
    # release. main()'s finally reads this flag; every exit path past this point,
    # including a crash or a Ctrl-C, stops the server.
    #
    # NOT under --from-cache. That mode never opens a socket to ComfyUI, so any
    # server running is one this process neither started nor used -- quite possibly
    # mid-generation on the owner's own job -- and taskkilling it is exactly the
    # thing BATCH_STARTED was added to prevent. The first version set this flag
    # unconditionally and would have killed it, contradicting the comment at the
    # top of this file that says --from-cache never fires the shutdown.
    global BATCH_STARTED
    if not FROM_CACHE:
        BATCH_STARTED = True

    made = 0
    print(f"--- stage 1: {len(todo)} image(s) (Flux stays resident) ---", flush=True)
    free_models()
    images = {}
    for slug, subject, _, _size in todo:
        png, prompt, used_seed = make_image(slug, subject, seeds[slug])
        # The seed actually used, which differs from the one drawn above whenever
        # a cached image was reused.
        seeds[slug] = used_seed
        images[slug] = (png, prompt)

    print(f"\n--- stage 2: {len(todo)} mesh(es) (Hunyuan3D stays resident) ---", flush=True)
    free_models()
    for slug, subject, tris, real_size in todo:
        png, prompt = images[slug]
        entry = forge(slug, subject, tris, real_size, seeds[slug], png, prompt,
                      from_cache=FROM_CACHE)
        if entry:
            manifest[slug] = entry
            made += 1
            with open(MANIFEST, "w", encoding="utf-8") as fh:
                json.dump({"props": sorted(manifest.values(), key=lambda e: e["slug"])},
                          fh, indent=2)

    print(f"\nForged {made} prop(s). Manifest: {os.path.relpath(MANIFEST, REPO)}")
    print("Render every prop before placing any of them: "
          "Tools > Aftermarket > Capture Map Screenshots writes a contact sheet to "
          "Screenshots/props/. Triangle counts and a null bakeError pass on garbage.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
