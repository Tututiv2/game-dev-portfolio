"""spritekit - the pixel half of the sprite pipeline, as pure functions.

No cv2, no FastAPI, no disk, no network. Everything here takes numpy arrays and
returns numpy arrays.

**Why it is a separate module.** All of this used to live inside
``run_real_sprite_pipeline``, which needs a downloaded video file and a FastAPI
request object before a single line of it can execute. That made the part most
likely to be wrong - the keyer and the alignment maths - the part that could not
be tested at all. Here it runs on synthetic frames in milliseconds, which is the
same reasoning behind ``scripts/blender/character_assemble.py --selftest``: if
the plumbing is broken it is broken for a coloured rectangle too, and a coloured
rectangle takes no minutes to find out.

Colour work happens in **YCbCr with Cb/Cr centred on zero**, not RGB. That is
not a stylistic choice - see :func:`chroma_alpha`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Literal, Sequence

import numpy as np

__all__ = [
    "AlignmentMode",
    "AlignedFrames",
    "FrameReject",
    "ALIGNMENT_MODES",
    "to_ycbcr",
    "to_rgb",
    "chroma_alpha",
    "estimate_background",
    "corridor_alpha",
    "despill",
    "apply_alpha",
    "content_box",
    "union_box",
    "align_frames",
    "pack_sheet",
    "binary_dilate",
    "binary_erode",
    "binary_open",
    "binary_close",
    "fill_holes",
    "build_canvas_snippet",
]


# --------------------------------------------------------------------------
# Colour space
# --------------------------------------------------------------------------

# ITU-R BT.601. Written so Cb/Cr come out centred on 0 rather than 128 - the
# +128 offset exists only to make them fit in an unsigned byte, and carrying it
# through the maths below would mean subtracting it back out in every function.
_RGB_TO_YCBCR = np.array(
    [
        [0.299000, 0.587000, 0.114000],
        [-0.168736, -0.331264, 0.500000],
        [0.500000, -0.418688, -0.081312],
    ],
    dtype=np.float64,
)
_YCBCR_TO_RGB = np.linalg.inv(_RGB_TO_YCBCR)


def to_ycbcr(rgb: np.ndarray) -> np.ndarray:
    """(..., 3) RGB 0-255 -> (..., 3) float64 [Y, Cb, Cr], Cb/Cr centred on 0."""
    return np.asarray(rgb, dtype=np.float64) @ _RGB_TO_YCBCR.T


def to_rgb(ycbcr: np.ndarray) -> np.ndarray:
    """Inverse of :func:`to_ycbcr`, clipped to 0-255. Does not round."""
    return np.clip(np.asarray(ycbcr, dtype=np.float64) @ _YCBCR_TO_RGB.T, 0.0, 255.0)


def _chroma(ycbcr: np.ndarray) -> np.ndarray:
    return ycbcr[..., 1:]


# Below this luma, chroma is mostly sensor noise and dividing by it amplifies
# that noise without limit. Near-black pixels are therefore compared at a fixed
# floor instead, which costs the ability to key a near-black backdrop - a fair
# trade for not turning shadow grain into holes in the subject.
_LUMA_FLOOR = 16.0

# Chromaticity is a ratio, so distances in it are ~0..1. Scaling by this keeps
# `tolerance` in the same familiar 0..100 range the API already exposes.
_CHROMA_SCALE = 128.0


def _chromaticity(ycbcr: np.ndarray) -> np.ndarray:
    """Chroma divided by luma - invariant to how brightly the pixel is lit.

    Raw Cb/Cr is *not* brightness-invariant, which is the trap: scaling a colour
    toward black scales its chroma with it, so pure green sits at |chroma| 136
    and the same green at half brightness sits at 68 - a distance of 68 between
    two pixels of identical hue. Keying on raw Cb/Cr therefore still cannot
    separate a shadowed backdrop from a dark subject, which is the exact problem
    moving out of RGB was supposed to solve.

    Dividing by luma removes the scale factor: both greens above land on the
    same chromaticity, so a backdrop may be lit however unevenly it likes.
    """
    luma = np.maximum(ycbcr[..., :1], _LUMA_FLOOR)
    return ycbcr[..., 1:] / luma


# --------------------------------------------------------------------------
# Keying
# --------------------------------------------------------------------------


def chroma_alpha(
    rgb: np.ndarray,
    key_rgb: Sequence[float],
    tolerance: float = 36.0,
    softness: float = 18.0,
) -> np.ndarray:
    """Soft-edged alpha (float 0..1) from chroma distance to ``key_rgb``.

    Two things here are deliberate and both were wrong before.

    **Distance is measured in chromaticity, never in RGB.** A green backdrop in
    shadow is *dark green*: far from the key colour in RGB, but the same hue. An
    RGB keyer therefore has no threshold that both removes the shadowed backdrop
    and keeps a dark subject - it must either leave a grey halo where the shadow
    was, or eat the subject's dark regions. Comparing chroma *normalised by
    luma* removes that trade-off entirely (see :func:`_chromaticity`, which also
    records why raw Cb/Cr is not enough on its own): brightness may vary across
    the backdrop as much as it likes.

    **The alpha ramp is soft.** The previous implementation was
    ``np.where(diff <= threshold, 0, 255)``, which produces a hard binary mask
    and therefore a staircased edge on every sprite - there is no such thing as
    a partially covered pixel, so nothing can anti-alias. Ramping between
    ``tolerance`` and ``tolerance + softness`` gives real coverage values at the
    boundary, which is what makes an exported sprite composite cleanly over an
    arbitrary background instead of over the one it was keyed against.

    Args:
        tolerance: chroma distance fully keyed out. Raise to catch more of the
            backdrop; lower if the subject is being eaten.
        softness: width of the ramp, in the same units. 0 reproduces the old
            binary behaviour and is almost never what you want.
    """
    key = to_ycbcr(np.asarray(key_rgb, dtype=np.float64).reshape(3))
    distance = _CHROMA_SCALE * np.linalg.norm(
        _chromaticity(to_ycbcr(rgb)) - _chromaticity(key), axis=-1
    )
    inner = max(0.0, float(tolerance))
    outer = inner + max(1e-6, float(softness))
    return np.clip((distance - inner) / (outer - inner), 0.0, 1.0)


def estimate_background(frames_rgb: Sequence[np.ndarray]) -> np.ndarray:
    """Per-pixel median across frames - the backdrop, with the subject removed.

    Relies on the subject being the *minority* at any given pixel, which is true
    whenever it moves. It is not true for a subject that holds still, and
    :func:`corridor_alpha` says so in its docstring rather than pretending
    otherwise.
    """
    if not len(frames_rgb):
        raise ValueError("estimate_background needs at least one frame")
    return np.median(np.stack([np.asarray(f, dtype=np.float64) for f in frames_rgb]), axis=0)


def corridor_alpha(
    frames_rgb: Sequence[np.ndarray],
    sensitivity: float = 18.0,
    softness: float = 12.0,
    luma_weight: float = 0.10,
    despeckle: int = 1,
    close_holes: int = 2,
    fill: bool = True,
) -> tuple[list[np.ndarray], np.ndarray]:
    """Automatic background removal - no key colour, no green screen.

    This is *CorridorKey* as described in ``README.md`` phase 3: estimate the
    background across the clip, then classify each pixel per frame by how far it
    has moved from it.

    ``luma_weight`` is the whole reason this works on real footage, and its
    default is arithmetic rather than taste. A subject's shadow falling on the
    backdrop changes brightness and nothing else, so it must score below
    ``sensitivity`` or every clip exports with its own shadow welded to it:

        backdrop luma ~128, a hard 40% shadow  ->  luma delta 51
        sqrt(0.10) * 51 = 16.2                 ->  under sensitivity 18, ignored
        sqrt(0.35) * 51 = 30.2                 ->  over it, welded on

    while still leaving enough weight to find a subject that differs from the
    backdrop *only* in brightness (a grey prop on a grey backdrop, luma delta
    120: ``sqrt(0.10) * 120 = 37.9``, comfortably detected). At
    ``luma_weight=1.0`` every shadow becomes part of the subject; at 0.0 a
    subject with no colour difference becomes invisible.

    Morphology afterwards is not cosmetic: an opening removes the isolated
    single-pixel hits that sensor noise produces (which would otherwise blow up
    the content box and therefore the whole sheet), and a closing plus hole fill
    repairs the interior of a subject that happens to match the backdrop
    somewhere - a penguin's white belly against snow, say.

    Returns ``(alphas, background)``.

    **Known limit, stated rather than hidden:** a subject that never moves is
    indistinguishable from the backdrop by this method, because it *is* the
    median. Such a clip must be keyed with :func:`chroma_alpha`.
    """
    frames = [np.asarray(f, dtype=np.float64) for f in frames_rgb]
    if not frames:
        raise ValueError("corridor_alpha needs at least one frame")
    background = estimate_background(frames)
    bg_ycc = to_ycbcr(background)

    inner = max(0.0, float(sensitivity))
    outer = inner + max(1e-6, float(softness))
    w = max(0.0, float(luma_weight))

    alphas: list[np.ndarray] = []
    for frame in frames:
        frame_ycc = to_ycbcr(frame)
        # Chroma compared as chromaticity so that a shadow - which scales the
        # pixel toward black without changing its hue - contributes nothing
        # here, and is judged solely by the down-weighted luma term below.
        chroma_delta = _CHROMA_SCALE * np.linalg.norm(
            _chromaticity(frame_ycc) - _chromaticity(bg_ycc), axis=-1
        )
        luma_delta = frame_ycc[..., 0] - bg_ycc[..., 0]
        distance = np.sqrt(w * luma_delta**2 + chroma_delta**2)
        alpha = np.clip((distance - inner) / (outer - inner), 0.0, 1.0)

        solid = alpha >= 0.5
        if despeckle > 0:
            solid = binary_open(solid, despeckle)
        if close_holes > 0:
            solid = binary_close(solid, close_holes)
        if fill:
            solid = fill_holes(solid)

        # Morphology decides WHAT IS SUBJECT. It must not decide edge softness,
        # or every benefit of the soft ramp is thrown away one line after it was
        # computed. The keep zone is therefore dilated by a pixel, so the outer
        # half of the ramp - which by definition reads below the 0.5 threshold
        # morphology worked on - survives.
        keep = binary_dilate(solid, 1)
        alpha = np.where(keep, alpha, 0.0)

        # Pixels morphology ADDED back - a filled hole, a closed pinhole - have
        # a raw alpha that says "background", because matching the background is
        # exactly why they were holes. They must return fully opaque rather than
        # at whatever the ramp happened to read there, which would leave a
        # half-transparent patch in the middle of the sprite.
        alpha = np.where(solid & (alpha < 0.5), 1.0, alpha)
        alphas.append(alpha)
    return alphas, background


def despill(
    rgb: np.ndarray,
    key: np.ndarray | Sequence[float],
    strength: float = 1.0,
) -> np.ndarray:
    """Remove the key hue's contribution from each pixel's colour.

    Keying only writes the *alpha* channel. The RGB of every partially covered
    edge pixel still contains whatever bounced off the backdrop, so a sprite
    keyed against green composites with a green rim and one keyed against
    magenta with a magenta rim - visible against any background that is not the
    one it was shot on, and the single most common reason an exported sprite
    looks "cheap".

    Each pixel's chroma is projected onto the key's chroma direction and the
    positive part of that projection is subtracted. **Luminance is untouched**,
    so nothing gets brighter or darker - only less green (or less magenta).

    ``key`` may be one colour or a per-pixel array of them, which is what lets
    the corridor keyer despill against its own estimated background where there
    is no single key colour to point at.

    A subject that genuinely contains the key hue will lose saturation. That is
    inherent to despilling, not a bug, and it is why the guidance is to pick a
    key colour that appears nowhere in the subject.
    """
    ycc = to_ycbcr(rgb)
    key_ycc = to_ycbcr(np.asarray(key, dtype=np.float64))
    key_chroma = _chroma(key_ycc)

    norm = np.linalg.norm(key_chroma, axis=-1, keepdims=True)
    if np.all(norm < 1e-6):
        # An achromatic key (black/white/grey) has no hue to remove. Subtracting
        # a zero-length direction would be a divide by zero, and there is
        # genuinely nothing to do.
        return np.clip(np.asarray(rgb, dtype=np.float64), 0.0, 255.0)
    direction = key_chroma / np.maximum(norm, 1e-6)

    chroma = _chroma(ycc)
    lean = np.sum(chroma * direction, axis=-1, keepdims=True)
    corrected = chroma - direction * np.maximum(lean, 0.0) * float(strength)

    out = ycc.copy()
    out[..., 1:] = corrected
    return to_rgb(out)


def apply_alpha(rgb: np.ndarray, alpha: np.ndarray) -> np.ndarray:
    """Stack RGB (0-255 float) and alpha (0-1 float) into a uint8 RGBA frame."""
    rgb8 = np.clip(np.rint(np.asarray(rgb, dtype=np.float64)), 0, 255).astype(np.uint8)
    a8 = np.clip(np.rint(np.asarray(alpha, dtype=np.float64) * 255.0), 0, 255).astype(np.uint8)
    return np.dstack((rgb8, a8))


# --------------------------------------------------------------------------
# Morphology (pure numpy - see module docstring for why no cv2)
# --------------------------------------------------------------------------


def _grow(mask: np.ndarray) -> np.ndarray:
    """One 4-connected dilation step, built from the input rather than in place.

    Mutating ``out`` while reading it would let a hit cascade the full width of
    the image in a single pass instead of moving one pixel.
    """
    out = mask.copy()
    out[1:, :] |= mask[:-1, :]
    out[:-1, :] |= mask[1:, :]
    out[:, 1:] |= mask[:, :-1]
    out[:, :-1] |= mask[:, 1:]
    return out


def binary_dilate(mask: np.ndarray, radius: int = 1) -> np.ndarray:
    out = np.asarray(mask, dtype=bool)
    for _ in range(max(0, int(radius))):
        out = _grow(out)
    return out


def binary_erode(mask: np.ndarray, radius: int = 1) -> np.ndarray:
    return ~binary_dilate(~np.asarray(mask, dtype=bool), radius)


def binary_open(mask: np.ndarray, radius: int = 1) -> np.ndarray:
    """Erode then dilate - removes speckle smaller than the radius."""
    return binary_dilate(binary_erode(mask, radius), radius)


def binary_close(mask: np.ndarray, radius: int = 1) -> np.ndarray:
    """Dilate then erode - fills pinholes smaller than the radius."""
    return binary_erode(binary_dilate(mask, radius), radius)


def fill_holes(mask: np.ndarray, max_iterations: int | None = None) -> np.ndarray:
    """Fill regions of ``False`` not connected to the image border.

    Morphological reconstruction from the border. Propagation moves one pixel
    per pass, so cost scales with the image's longest dimension rather than with
    how many holes there are. Measured on this machine:

        320x240    2.5 ms        960x540   77 ms        1920x1080   684 ms

    which is comfortably inside an offline export and the reason no faster
    (cv2/scipy-dependent) implementation is used - keeping this module free of
    those imports is worth more than half a second per frame here.

    The iteration cap exists so that a pathological mask cannot turn into an
    unbounded wait; it is far above the convergence point for any real frame.
    """
    mask = np.asarray(mask, dtype=bool)
    free = ~mask
    if not free.any():
        return mask

    outside = np.zeros_like(mask)
    outside[0, :] = free[0, :]
    outside[-1, :] = free[-1, :]
    outside[:, 0] = free[:, 0]
    outside[:, -1] = free[:, -1]

    cap = max(mask.shape) * 2 if max_iterations is None else int(max_iterations)
    for _ in range(cap):
        grown = _grow(outside) & free
        if np.array_equal(grown, outside):
            break
        outside = grown
    return ~outside


# --------------------------------------------------------------------------
# Subject detection and alignment
# --------------------------------------------------------------------------

AlignmentMode = Literal[
    "union",
    "drift_corrected",
    "bottom_center",
    "both_centered",
    "vertical_only",
    "horizontal_only",
]

ALIGNMENT_MODES: tuple[str, ...] = (
    "union",
    "drift_corrected",
    "bottom_center",
    "both_centered",
    "vertical_only",
    "horizontal_only",
)


@dataclass(frozen=True)
class FrameReject:
    """A frame that produced no usable subject, and why."""

    index: int
    reason: str


@dataclass
class AlignedFrames:
    frames: list[np.ndarray]
    kept_indices: list[int]
    rejects: list[FrameReject] = field(default_factory=list)
    frame_width: int = 0
    frame_height: int = 0
    pivot_x: int = 0
    pivot_y: int = 0
    boxes: list[tuple[int, int, int, int]] = field(default_factory=list)
    mode: str = "union"


def content_box(alpha: np.ndarray, threshold: float = 0.5) -> tuple[int, int, int, int] | None:
    """Tight box around pixels at or above ``threshold``. ``None`` if empty.

    Returning ``None`` rather than the full frame is load-bearing - see
    :func:`align_frames`.
    """
    mask = np.asarray(alpha) >= threshold
    if not mask.any():
        return None
    rows = np.flatnonzero(mask.any(axis=1))
    cols = np.flatnonzero(mask.any(axis=0))
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1


def union_box(boxes: Iterable[tuple[int, int, int, int]]) -> tuple[int, int, int, int]:
    boxes = list(boxes)
    if not boxes:
        raise ValueError("union_box needs at least one box")
    return (
        min(b[0] for b in boxes),
        min(b[1] for b in boxes),
        max(b[2] for b in boxes),
        max(b[3] for b in boxes),
    )


def align_frames(
    frames_rgba: Sequence[np.ndarray],
    alphas: Sequence[np.ndarray],
    mode: AlignmentMode = "union",
    indices: Sequence[int] | None = None,
) -> AlignedFrames:
    """Crop every frame to a common canvas and report where the subject sits.

    ``union`` is the default, and the reason is worth stating because the old
    default was actively destructive. ``both_centered`` crops each frame to its
    own bounding box and re-centres it, which **deletes the animation**: a walk
    cycle's vertical bob is precisely a frame-to-frame change in where the
    subject sits, so centring every frame independently flattens it to a
    character sliding along a rail. Worse, when the subject's *size* changes -
    wings extending, a crouch - the bounding box changes with it, so the body
    centre drifts relative to the frame centre and the sprite appears to wobble
    on its own axis when rotated about that centre.

    ``union`` crops all frames to one shared rectangle, so every pixel keeps its
    true position relative to every other frame. Motion survives, the pivot is
    stable, and the cost is a little transparent padding.

    ``bottom_center`` is the middle ground for a walk cycle where the character
    should stay horizontally centred but the feet must not slide: it aligns each
    frame on the bottom-centre of its own box.

    The three legacy modes are kept and behave exactly as before, because
    existing exports reference them.

    **A frame whose mask is empty is rejected, never sized.** The previous code
    gave such a frame the *entire video frame* as its bounding box, which then
    won ``max()`` against every real box - so one over-keyed frame silently
    resized the whole sheet to full video resolution and left every sprite a
    small island in the middle of it, with no error and no warning. Same shape
    as landmine 31: a mask that matches nothing must complain, not succeed.
    """
    if mode not in ALIGNMENT_MODES:
        raise ValueError(f"Unknown alignment mode {mode!r}. Known: {', '.join(ALIGNMENT_MODES)}")
    if len(frames_rgba) != len(alphas):
        raise ValueError("frames and alphas must be the same length")

    src_indices = list(indices) if indices is not None else list(range(len(frames_rgba)))

    kept: list[int] = []
    kept_frames: list[np.ndarray] = []
    boxes: list[tuple[int, int, int, int]] = []
    rejects: list[FrameReject] = []

    for position, (frame, alpha) in enumerate(zip(frames_rgba, alphas)):
        box = content_box(alpha)
        source_index = src_indices[position]
        if box is None:
            rejects.append(
                FrameReject(
                    index=source_index,
                    reason=(
                        "the mask matched the entire frame - nothing was left of the subject. "
                        "Lower the tolerance, or check the key colour appears in the backdrop."
                    ),
                )
            )
            continue
        kept.append(source_index)
        kept_frames.append(frame)
        boxes.append(box)

    if not kept_frames:
        raise ValueError(
            "Every frame was fully keyed out - no subject survived in any of them. "
            "The tolerance is almost certainly too high, or the key colour is the subject."
        )

    if mode == "union":
        ux0, uy0, ux1, uy1 = union_box(boxes)
        width, height = ux1 - ux0, uy1 - uy0
        out = [f[uy0:uy1, ux0:ux1].copy() for f in kept_frames]
        local_boxes = [(b[0] - ux0, b[1] - uy0, b[2] - ux0, b[3] - uy0) for b in boxes]
        pivot_x, pivot_y = width // 2, height
    elif mode == "drift_corrected":
        # Horizontal drift removed, vertical motion kept. This is the mode most
        # AI-generated loops actually want: the subject wanders across frame
        # because nothing told the video model to hold it still, but its bob is
        # real animation. `union` keeps both, which pays for the drift with a
        # frame wide enough to contain the entire journey - a 320px clip whose
        # subject crosses it yields a 188px-wide frame holding a 32px subject.
        # `bottom_center` cancels both and flattens the bob with the drift.
        widths = [b[2] - b[0] for b in boxes]
        uy0, uy1 = min(b[1] for b in boxes), max(b[3] for b in boxes)
        width, height = max(widths), uy1 - uy0
        out, local_boxes = [], []
        for frame, box in zip(kept_frames, boxes):
            x0, y0, x1, y1 = box
            crop = frame[y0:y1, x0:x1]
            canvas = np.zeros((height, width, frame.shape[2]), dtype=frame.dtype)
            dx = (width - crop.shape[1]) // 2
            dy = y0 - uy0
            canvas[dy : dy + crop.shape[0], dx : dx + crop.shape[1]] = crop
            out.append(canvas)
            local_boxes.append((dx, dy, dx + crop.shape[1], dy + crop.shape[0]))
        pivot_x, pivot_y = width // 2, height
    elif mode == "bottom_center":
        widths = [b[2] - b[0] for b in boxes]
        heights = [b[3] - b[1] for b in boxes]
        width, height = max(widths), max(heights)
        out, local_boxes = [], []
        for frame, box in zip(kept_frames, boxes):
            x0, y0, x1, y1 = box
            crop = frame[y0:y1, x0:x1]
            canvas = np.zeros((height, width, frame.shape[2]), dtype=frame.dtype)
            dx = (width - crop.shape[1]) // 2
            dy = height - crop.shape[0]
            canvas[dy : dy + crop.shape[0], dx : dx + crop.shape[1]] = crop
            out.append(canvas)
            local_boxes.append((dx, dy, dx + crop.shape[1], dy + crop.shape[0]))
        pivot_x, pivot_y = width // 2, height
    else:
        widths = [b[2] - b[0] for b in boxes]
        heights = [b[3] - b[1] for b in boxes]
        width, height = max(widths), max(heights)
        out, local_boxes = [], []
        for frame, box in zip(kept_frames, boxes):
            x0, y0, x1, y1 = box
            crop = frame[y0:y1, x0:x1]
            canvas = np.zeros((height, width, frame.shape[2]), dtype=frame.dtype)
            dx = 0 if mode == "vertical_only" else (width - crop.shape[1]) // 2
            dy = 0 if mode == "horizontal_only" else (height - crop.shape[0]) // 2
            canvas[dy : dy + crop.shape[0], dx : dx + crop.shape[1]] = crop
            out.append(canvas)
            local_boxes.append((dx, dy, dx + crop.shape[1], dy + crop.shape[0]))
        pivot_x, pivot_y = width // 2, height

    return AlignedFrames(
        frames=out,
        kept_indices=kept,
        rejects=rejects,
        frame_width=width,
        frame_height=height,
        pivot_x=int(pivot_x),
        pivot_y=int(pivot_y),
        boxes=local_boxes,
        mode=mode,
    )


def pack_sheet(frames: Sequence[np.ndarray]) -> np.ndarray:
    """Pack equal-sized RGBA frames into one horizontal strip.

    The layout is part of the published contract (``sheet.layout == "horizontal"``)
    and every consumer indexes with ``frameIndex * frameWidth``, so this stays a
    single row.
    """
    if not len(frames):
        raise ValueError("pack_sheet needs at least one frame")
    height, width = frames[0].shape[:2]
    channels = frames[0].shape[2]
    for index, frame in enumerate(frames):
        if frame.shape[:2] != (height, width):
            raise ValueError(
                f"frame {index} is {frame.shape[1]}x{frame.shape[0]}, expected {width}x{height} - "
                "align_frames must run before pack_sheet"
            )
    sheet = np.zeros((height, width * len(frames), channels), dtype=frames[0].dtype)
    for index, frame in enumerate(frames):
        sheet[:, index * width : (index + 1) * width] = frame
    return sheet


# --------------------------------------------------------------------------
# Integration snippet
# --------------------------------------------------------------------------


def build_canvas_snippet(
    *,
    frame_width: int,
    frame_height: int,
    frame_count: int,
    fps: int,
    pivot_x: int,
    pivot_y: int,
) -> str:
    """The copy-paste Canvas2D integration snippet.

    Lives here, in the dependency-free module, rather than beside the FastAPI
    handlers - the desktop app's CLI needs it too and must not import a web
    framework to get a string. One implementation, three callers.

    The emitted code differs from its predecessor in three ways that matter:

    * **Per-instance state.** The old snippet held ``frameIndex`` and
      ``lastTick`` in module scope, so every sprite drawn through it shared one
      playhead. Two instances on screen was not a limitation anyone chose - it
      simply could not work, and the second sprite stuttered in lockstep with
      the first.
    * **Real fps**, from the clip or the export request, rather than 12
      hardcoded into the string.
    * **Pivot-relative drawing.** ``x, y`` is where the subject's ground line
      lands, which is what a game actually has to hand. The old snippet took a
      top-left corner and a mystery ``offsetY = -12``.
    """
    return f"""// Generated by Shadow Studio.
// Sheet layout: ONE horizontal row of {frame_count} frames, {frame_width}x{frame_height} each.
const SHEET = {{
  frameWidth: {frame_width},
  frameHeight: {frame_height},
  frameCount: {frame_count},
  fps: {fps},
  // The subject's anchor within a frame: bottom-centre, i.e. its ground line.
  pivotX: {pivot_x},
  pivotY: {pivot_y},
}};

// One of these per on-screen instance - the playhead is per sprite, not global.
function createSprite(sheetImage) {{
  return {{ image: sheetImage, time: 0, frame: 0 }};
}}

function updateSprite(sprite, dtSeconds) {{
  sprite.time += dtSeconds;
  const advanced = Math.floor(sprite.time * SHEET.fps);
  sprite.frame = ((advanced % SHEET.frameCount) + SHEET.frameCount) % SHEET.frameCount;
}}

// (x, y) is where you want the sprite's PIVOT to sit in the world.
function drawSprite(ctx, sprite, x, y) {{
  ctx.drawImage(
    sprite.image,
    sprite.frame * SHEET.frameWidth, 0, SHEET.frameWidth, SHEET.frameHeight,
    Math.round(x - SHEET.pivotX), Math.round(y - SHEET.pivotY),
    SHEET.frameWidth, SHEET.frameHeight,
  );
}}
"""
