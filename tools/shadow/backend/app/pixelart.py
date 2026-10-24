"""Turn a high-resolution generated image into a real game sprite.

WHY THIS EXISTS: image models are bad at small canvases. Asking one for a 16x16 image
returns mush, because 256 pixels is far below the resolution its priors work at. The
approach that does work is to generate LARGE with a pixel-art style prompt, then reduce
deliberately -- and the reduction is where the sprite is actually made.

The pipeline, in order (each step exists to fix a specific failure of the previous one):

1. **Alpha recovery.** Generators rarely return transparency. Flood-fill from the border
   over near-uniform background so the subject is cut out, instead of trusting a
   fixed chroma colour that the model may have ignored.
2. **Trim + square.** Crop to the subject's bounding box and pad to a square, so the
   sprite fills the tile instead of floating in a corner at 16x16.
3. **Box-average downscale.** AREA resampling, NOT nearest-neighbour. Nearest at this
   ratio point-samples ~1 source pixel per output pixel and keeps whatever noise it
   lands on; area-averaging is what preserves the shape's silhouette.
4. **Palette quantization.** Median-cut to a small palette. This is what makes it read
   as pixel art rather than a tiny photograph -- it collapses the model's soft
   gradients into flat colour regions.
5. **Alpha hardening.** Downscaling produces semi-transparent edge pixels; Minecraft
   item textures want a crisp cut. Threshold alpha back to fully on/off.

Everything is tunable per call because a 16x16 pickaxe and a 64x64 wing want different
settings, and the right values are a matter of taste that only shows up in-game.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image


@dataclass
class PixelArtOptions:
    size: int = 16                      # output edge length in pixels
    colors: int = 16                    # palette size after quantization
    background_tolerance: int = 32      # 0-255; how close to the corner colour counts as background
    alpha_threshold: int = 128          # below this, a pixel becomes fully transparent
    trim: bool = True                   # crop to the subject before scaling
    pad_ratio: float = 0.06             # breathing room around the subject, as a fraction of the edge
    sharpen: bool = True                # harden alpha to on/off after scaling


# Presets, because the right settings are a property of the TARGET PACK, not of taste.
#
# Shadow Prison's resource pack is HD: every existing custom item texture is 256x256
# (prism_pickaxe, starfade_boots, the armor sets...), and its "3D" in-hand models are
# just the flat texture extruded via the sp_extruded_handheld_4 parent. So one 256x256
# texture drives both the inventory icon and the 3D held item, and generating at the
# vanilla 16x16 throws away almost everything the model produced for no reason.
#
# Quantization also has to scale with size: 16 colours reads as crisp pixel art on a
# 16x16 tile and as posterized banding on a 256x256 one.
PRESET_VANILLA = PixelArtOptions(size=16, colors=16)      # vanilla-style, maximum chunk
PRESET_MID = PixelArtOptions(size=64, colors=48)          # detailed but still obviously pixel art
PRESET_HD = PixelArtOptions(size=256, colors=96)          # matches this pack's existing items
PRESET_HD_SMOOTH = PixelArtOptions(size=256, colors=0)    # no palette reduction at all

PRESETS: dict[str, PixelArtOptions] = {
    "vanilla": PRESET_VANILLA,
    "mid": PRESET_MID,
    "hd": PRESET_HD,
    "hd_smooth": PRESET_HD_SMOOTH,
}


def pixelize_file(source: Path, target: Path, options: PixelArtOptions | None = None) -> Path:
    options = options or PixelArtOptions()
    image = Image.open(source).convert("RGBA")
    result = pixelize(image, options)
    target.parent.mkdir(parents=True, exist_ok=True)
    result.save(target, format="PNG")
    return target


def pixelize(image: Image.Image, options: PixelArtOptions | None = None) -> Image.Image:
    options = options or PixelArtOptions()
    image = image.convert("RGBA")

    if _is_fully_opaque(image):
        image = _cut_out_background(image, options.background_tolerance)

    if options.trim:
        image = _trim_to_subject(image, options.pad_ratio)

    image = _square(image)
    image = image.resize((options.size, options.size), Image.Resampling.BOX)

    if options.colors > 0:
        image = _quantize(image, options.colors)

    if options.sharpen:
        image = _harden_alpha(image, options.alpha_threshold)

    return image


# ---------------------------------------------------------------------- steps


def _is_fully_opaque(image: Image.Image) -> bool:
    alpha = np.asarray(image)[:, :, 3]
    return bool((alpha == 255).all())


def _cut_out_background(image: Image.Image, tolerance: int) -> Image.Image:
    """Flood-fill background from the border.

    Sampling the four corners rather than assuming a key colour is what makes this
    survive a model that ignored "on a white background" in the prompt. Only pixels
    CONNECTED to the border are removed, so a background-coloured region inside the
    subject (a white gem on a white background) is preserved.
    """
    rgba = np.asarray(image).astype(np.int16)
    height, width = rgba.shape[:2]
    corners = [rgba[0, 0, :3], rgba[0, width - 1, :3], rgba[height - 1, 0, :3], rgba[height - 1, width - 1, :3]]
    background = np.median(np.stack(corners), axis=0)

    distance = np.linalg.norm(rgba[:, :, :3] - background, axis=2)
    candidate = distance <= tolerance

    reachable = _flood_from_border(candidate)
    alpha = np.where(reachable, 0, 255).astype(np.uint8)
    out = rgba.astype(np.uint8).copy()
    out[:, :, 3] = alpha
    return Image.fromarray(out, mode="RGBA")


def _flood_from_border(mask: np.ndarray) -> np.ndarray:
    """Iterative 4-connected flood fill seeded from every border pixel in `mask`.

    Written with array dilation rather than a per-pixel stack: at 1024x1024 a Python
    flood fill is seconds, this is milliseconds, and it has no recursion limit.
    """
    reachable = np.zeros_like(mask, dtype=bool)
    reachable[0, :] |= mask[0, :]
    reachable[-1, :] |= mask[-1, :]
    reachable[:, 0] |= mask[:, 0]
    reachable[:, -1] |= mask[:, -1]

    while True:
        grown = reachable.copy()
        grown[1:, :] |= reachable[:-1, :]
        grown[:-1, :] |= reachable[1:, :]
        grown[:, 1:] |= reachable[:, :-1]
        grown[:, :-1] |= reachable[:, 1:]
        grown &= mask
        if np.array_equal(grown, reachable):
            return reachable
        reachable = grown


def _trim_to_subject(image: Image.Image, pad_ratio: float) -> Image.Image:
    alpha = np.asarray(image)[:, :, 3]
    rows = np.where(alpha.any(axis=1))[0]
    cols = np.where(alpha.any(axis=0))[0]
    if rows.size == 0 or cols.size == 0:
        return image  # fully transparent; nothing to trim toward

    top, bottom = int(rows[0]), int(rows[-1]) + 1
    left, right = int(cols[0]), int(cols[-1]) + 1
    pad = int(round(max(bottom - top, right - left) * pad_ratio))

    height, width = alpha.shape
    box = (
        max(0, left - pad),
        max(0, top - pad),
        min(width, right + pad),
        min(height, bottom + pad),
    )
    return image.crop(box)


def _square(image: Image.Image) -> Image.Image:
    width, height = image.size
    if width == height:
        return image
    edge = max(width, height)
    canvas = Image.new("RGBA", (edge, edge), (0, 0, 0, 0))
    canvas.paste(image, ((edge - width) // 2, (edge - height) // 2))
    return canvas


def _quantize(image: Image.Image, colors: int) -> Image.Image:
    """Median-cut the RGB channels while preserving alpha exactly.

    PIL's quantize() drops to a palette image, which mangles per-pixel alpha. So the
    alpha channel is detached, the colour reduced, then alpha reattached unchanged --
    hardening it is step 5's job, not this one's.
    """
    array = np.asarray(image)
    alpha = array[:, :, 3]
    rgb = Image.fromarray(array[:, :, :3], mode="RGB")
    reduced = rgb.quantize(colors=max(2, colors), method=Image.Quantize.MEDIANCUT).convert("RGB")
    merged = np.dstack((np.asarray(reduced), alpha))
    return Image.fromarray(merged, mode="RGBA")


def _harden_alpha(image: Image.Image, threshold: int) -> Image.Image:
    array = np.asarray(image).copy()
    alpha = array[:, :, 3]
    array[:, :, 3] = np.where(alpha >= threshold, 255, 0).astype(np.uint8)
    # Zero the colour of now-transparent pixels so the PNG has no fringe halo when a
    # renderer samples it with filtering.
    transparent = array[:, :, 3] == 0
    array[transparent, 0:3] = 0
    return Image.fromarray(array, mode="RGBA")


# ---------------------------------------------------------------------- prompting


PIXEL_ART_STYLE_SUFFIX = (
    "16-bit pixel art game item icon, crisp pixel edges, flat cel shading, "
    "bold readable silhouette, limited color palette, centered, "
    "plain solid white background, no text, no watermark, no drop shadow"
)

PIXEL_ART_NEGATIVE = (
    "photorealistic, 3d render, blurry, soft focus, gradient background, "
    "busy background, scene, landscape, text, watermark, signature, "
    "multiple objects, cropped, out of frame, "
    # Pixel-art checkpoints are trained largely ON sprite sheets, so left alone they
    # happily return a 2x2 grid of variations. Observed on the first real run: 1 of 3
    # samples came back as four separate item fragments. These terms plus the
    # connected-component check in score_sprite() are what stop that reaching the user.
    "sprite sheet, spritesheet, tiled, grid, collage, multiple views, "
    "item set, character sheet, turnaround, borders, frame, panel"
)


@dataclass
class SpriteScore:
    """Verdict on whether a pixelized candidate is worth showing a human."""

    ok: bool
    fill: float                 # share of the tile that is opaque
    largest_blob_share: float   # share of opaque mass in the biggest connected region
    colors: int
    reason: str = ""


def score_sprite(
    image: Image.Image,
    *,
    min_fill: float = 0.05,
    max_fill: float = 0.72,
    min_blob_share: float = 0.62,
    min_colors: int = 3,
    expect_parts: int = 1,
) -> SpriteScore:
    """Reject the two failure modes seen on the first real generation run.

    1. **No background found** -> the flood fill removed nothing and the tile is a solid
       block. Caught by max_fill: a real item icon leaves margin around its silhouette.
    2. **Sprite sheet / fragments** -> the model returned several small objects instead
       of one. Caught by min_blob_share: a single item is one connected region, whereas
       a 2x2 grid splits its mass across four.

    Cheap enough (16x16) to run on every candidate, which is the point -- the forge
    regenerates rejects silently so a human only ever chooses between good sprites.
    """
    array = np.asarray(image.convert("RGBA"))
    opaque = array[:, :, 3] > 0
    total = opaque.size
    fill = float(opaque.sum()) / total

    if fill < min_fill:
        return SpriteScore(False, fill, 0.0, 0, "almost empty - subject was cut away")
    if fill > max_fill:
        return SpriteScore(False, fill, 0.0, 0, "no clean background - tile is a solid block")

    # expect_parts is load-bearing, and this is the bug it fixes: a BOOTS icon is a PAIR,
    # so it is legitimately two disconnected regions. The one-blob rule was written to
    # catch sprite sheets and it rejected every correct pair the moment img2img started
    # producing them (2026-07-27: 6 of 6 good boots thrown away). The lesson generalises --
    # a structural filter must know how many pieces the ITEM CLASS is supposed to have.
    blob_share = _top_blobs_share(opaque, expect_parts)
    colors = int(len(np.unique(array[opaque][:, :3], axis=0)))

    if blob_share < min_blob_share:
        return SpriteScore(False, fill, blob_share, colors,
                           f"broke into more than {expect_parts} piece(s) - looks like a sprite sheet")
    if expect_parts == 2 and not _pair_is_balanced(opaque):
        return SpriteScore(False, fill, blob_share, colors,
                           "the two halves are lopsided - not a matching pair")
    if colors < min_colors:
        return SpriteScore(False, fill, blob_share, colors, "flat silhouette with no shading")

    return SpriteScore(True, fill, blob_share, colors)


def _top_blobs_share(mask: np.ndarray, parts: int) -> float:
    """Share of opaque mass in the `parts` largest connected regions."""
    counts = _blob_sizes(mask)
    total = float(counts.sum())
    if total <= 0:
        return 0.0
    biggest = np.sort(counts)[::-1][:max(1, parts)]
    return float(biggest.sum()) / total


def _pair_is_balanced(mask: np.ndarray, tolerance: float = 0.45) -> bool:
    """True when the two biggest regions are similar in size.

    A real pair of boots is two comparable shapes. One big blob plus a speck is a single
    boot with a stray pixel cluster, which would otherwise sail through the parts check.
    """
    counts = np.sort(_blob_sizes(mask))[::-1]
    if counts.size < 2 or counts[1] == 0:
        return False
    smaller, larger = float(counts[1]), float(counts[0])
    return (smaller / larger) >= tolerance


def _blob_sizes(mask: np.ndarray) -> np.ndarray:
    """Pixel count of every 4-connected region, largest first is NOT guaranteed."""
    labels = _label(mask)
    counts = np.bincount(labels.ravel())
    if counts.size:
        counts[0] = 0
    return counts


def _largest_blob_share(mask: np.ndarray) -> float:
    """Fraction of opaque pixels living in the biggest 4-connected region.

    Iterative label propagation rather than scipy.ndimage: it keeps this module free of
    a SciPy dependency, and at 16x16 it converges in a handful of passes.
    """
    total = int(mask.sum())
    if total == 0:
        return 0.0
    counts = _blob_sizes(mask)
    return float(counts.max()) / total


def _label(mask: np.ndarray) -> np.ndarray:
    """4-connected labelling by iterative max-propagation (no SciPy dependency)."""
    labels = np.where(mask, np.arange(mask.size).reshape(mask.shape) + 1, 0)
    while True:
        updated = labels.copy()
        updated[1:, :] = np.maximum(updated[1:, :], labels[:-1, :])
        updated[:-1, :] = np.maximum(updated[:-1, :], labels[1:, :])
        updated[:, 1:] = np.maximum(updated[:, 1:], labels[:, :-1])
        updated[:, :-1] = np.maximum(updated[:, :-1], labels[:, 1:])
        updated[~mask] = 0
        if np.array_equal(updated, labels):
            break
        labels = updated
    return labels


# Vanilla item icons have conventions an image model knows nothing about. A boots icon
# shows a MATCHING PAIR, not one shoe; a leggings icon is a front-facing pair of trousers;
# a helmet is front-on. Without these the model draws a single sneaker in profile, which
# is exactly what happened on the first boots batch (owner: "they look like boots, only 1
# shoe, should look like diamond boots item model").
#
# Keyed by Minecraft material so the caller passes what it already knows.
MATERIAL_SHAPE_HINTS: dict[str, str] = {
    "BOOTS": "a matching PAIR of boots side by side, both boots fully visible, front view",
    "LEGGINGS": "a single pair of armored leggings, front view, both legs visible",
    "HELMET": "a single helmet, front view, centered",
    "CHESTPLATE": "a single chestplate torso armor, front view, centered",
    "PICKAXE": "a single pickaxe seen from the side, head upper-left, handle lower-right",
    "AXE": "a single axe seen from the side, head upper-left, handle lower-right",
    "SHOVEL": "a single shovel seen from the side, blade down",
    "HOE": "a single hoe seen from the side",
    "SWORD": "a single sword seen from the side, blade pointing up-left, hilt lower-right",
    "BOW": "a single bow seen from the side, string facing right",
}


def shape_hint_for(material: str | None) -> str:
    """The icon-convention hint for a Minecraft material, or "" when unknown."""
    if not material:
        return ""
    upper = material.upper()
    for suffix, hint in MATERIAL_SHAPE_HINTS.items():
        if upper.endswith(suffix):
            return hint
    return ""


def build_sprite_prompt(subject: str) -> tuple[str, str]:
    """Wrap a bare subject ("molten obsidian pickaxe") into a sprite-shaped prompt.

    The style suffix does the heavy lifting: without "plain solid white background"
    the background cut-out in step 1 has nothing clean to flood from, and without
    "bold readable silhouette" the subject dissolves at 16x16.
    """
    subject = subject.strip().rstrip(".")
    return f"{subject}, {PIXEL_ART_STYLE_SUFFIX}", PIXEL_ART_NEGATIVE


def build_item_prompt(subject: str, material: str | None = None, extra: str = "") -> tuple[str, str]:
    """Sprite prompt plus the icon-shape convention for `material` and any user refinement.

    `extra` is free text typed in game ("more purple", "taller", "glowing runes"). It is
    placed AFTER the shape hint but BEFORE the style suffix, so a refinement can override
    the convention if that is what was asked for, while the style lock still comes last.
    """
    parts = [subject.strip().rstrip(".")]
    hint = shape_hint_for(material)
    if hint:
        parts.append(hint)
    if extra and extra.strip():
        parts.append(extra.strip())
    parts.append(PIXEL_ART_STYLE_SUFFIX)

    negative = PIXEL_ART_NEGATIVE
    if "PAIR" in hint:
        # A pair is wanted, so "multiple objects" in the negative would fight the prompt.
        negative = negative.replace("multiple objects, ", "")
    return ", ".join(parts), negative
