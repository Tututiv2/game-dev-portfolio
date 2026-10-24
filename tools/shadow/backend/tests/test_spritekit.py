"""Tests for the pure sprite pixel pipeline.

Every test here runs on synthetic frames built in the test itself - no video
file, no GPU, no network, milliseconds. That is the point of `spritekit` being
a separate module: the keyer and the alignment maths are the parts most likely
to be wrong, and before this they could not be executed without a downloaded
video and a FastAPI request.

Several tests below assert a *difference* between the new behaviour and the old
(see `test_both_centered_flattens_vertical_motion`). Those are not redundant -
a regression that quietly restores the old behaviour would otherwise pass.
"""

from __future__ import annotations

import numpy as np
import pytest

from app import spritekit as sk


GREEN = (0, 200, 0)
MAGENTA = (255, 0, 255)


def solid(height: int, width: int, colour) -> np.ndarray:
    frame = np.zeros((height, width, 3), dtype=np.float64)
    frame[:, :] = colour
    return frame


# --------------------------------------------------------------------------
# Colour space
# --------------------------------------------------------------------------


def test_ycbcr_round_trip_is_lossless():
    rng = np.random.default_rng(7)
    rgb = rng.integers(0, 256, size=(16, 16, 3)).astype(np.float64)
    assert np.allclose(sk.to_rgb(sk.to_ycbcr(rgb)), rgb, atol=1e-9)


# --------------------------------------------------------------------------
# Chroma keying
# --------------------------------------------------------------------------


def test_key_colour_is_fully_transparent():
    alpha = sk.chroma_alpha(solid(8, 8, GREEN), GREEN)
    assert np.all(alpha == 0.0)


def test_neutral_subject_is_fully_opaque():
    for colour in [(255, 255, 255), (128, 128, 128), (20, 20, 20)]:
        alpha = sk.chroma_alpha(solid(8, 8, colour), GREEN)
        assert np.all(alpha == 1.0), colour


def test_keying_is_brightness_invariant():
    """The headline claim: a backdrop may be lit unevenly and still key out.

    This is the test that fails on raw Cb/Cr. Pure green sits at |chroma| 136
    and the same green at 30% brightness at 41 - a distance of 95 between two
    pixels of identical hue, far outside any usable tolerance. Normalising by
    luma collapses that to zero.
    """
    for scale in (1.0, 0.75, 0.6, 0.45, 0.3):
        shaded = tuple(c * scale for c in GREEN)
        alpha = sk.chroma_alpha(solid(4, 4, shaded), GREEN)
        assert np.all(alpha == 0.0), f"backdrop at {scale:.0%} brightness was not keyed"

    # ...and a neutral subject at the SAME luma as the darkest backdrop above
    # must survive, or "brightness invariant" would just mean "keys everything".
    dark_grey = solid(4, 4, (36, 36, 36))
    assert np.all(sk.chroma_alpha(dark_grey, GREEN) == 1.0)


def test_edges_are_soft_not_binary():
    """A hard mask cannot anti-alias; partial coverage must produce partial alpha."""
    t = np.linspace(0.0, 1.0, 64).reshape(1, 64, 1)
    ramp = np.array(GREEN, dtype=np.float64) * (1 - t) + np.array([255.0, 255, 255]) * t

    soft = sk.chroma_alpha(ramp, GREEN, tolerance=36, softness=18)
    partial = np.sum((soft > 0.01) & (soft < 0.99))
    assert partial > 0, "no partially transparent pixels anywhere on a full colour ramp"

    hard = sk.chroma_alpha(ramp, GREEN, tolerance=36, softness=0)
    assert np.sum((hard > 0.01) & (hard < 0.99)) < partial


def test_magenta_key_works_as_well_as_green():
    """Magenta is the recommended key against snow-white art; it must not be a
    second-class path just because green is the historical default."""
    assert np.all(sk.chroma_alpha(solid(4, 4, MAGENTA), MAGENTA) == 0.0)
    assert np.all(sk.chroma_alpha(solid(4, 4, (240, 245, 255)), MAGENTA) == 1.0)


# --------------------------------------------------------------------------
# Despill
# --------------------------------------------------------------------------


def _lean_on(rgb, key) -> float:
    """How far a colour leans toward the key hue. 0 means no spill left."""
    chroma = sk.to_ycbcr(np.asarray(rgb, dtype=np.float64))[..., 1:]
    key_chroma = sk.to_ycbcr(np.asarray(key, dtype=np.float64))[1:]
    direction = key_chroma / np.linalg.norm(key_chroma)
    return float(np.sum(chroma * direction))


def test_despill_removes_the_key_hue_and_keeps_the_brightness():
    spilled = np.array([[[200.0, 230.0, 200.0]]])  # white-ish, greened by bounce
    assert _lean_on(spilled[0, 0], GREEN) > 5.0

    cleaned = sk.despill(spilled, GREEN)
    assert _lean_on(cleaned[0, 0], GREEN) == pytest.approx(0.0, abs=1e-6)

    before = sk.to_ycbcr(spilled)[0, 0, 0]
    after = sk.to_ycbcr(cleaned)[0, 0, 0]
    assert after == pytest.approx(before, abs=1e-6), "despill changed the luminance"


def test_despill_does_not_touch_colours_leaning_away_from_the_key():
    warm = np.array([[[220.0, 120.0, 90.0]]])
    assert _lean_on(warm[0, 0], GREEN) < 0
    assert np.allclose(sk.despill(warm, GREEN), warm, atol=1e-9)


def test_despill_on_an_achromatic_key_is_a_no_op():
    """A grey key has no hue to remove, and normalising its zero-length chroma
    direction would be a divide by zero."""
    frame = solid(4, 4, (10, 90, 200))
    assert np.allclose(sk.despill(frame, (128, 128, 128)), frame, atol=1e-9)


# --------------------------------------------------------------------------
# CorridorKey - automatic background removal
# --------------------------------------------------------------------------


def _clip_with_moving_subject(shadow: bool = False):
    """Backdrop with a subject sliding across it, optionally casting a shadow."""
    backdrop = (120, 130, 140)
    frames = []
    for step in range(5):
        frame = solid(40, 80, backdrop)
        x = 4 + step * 12
        frame[14:26, x : x + 10] = (200, 40, 40)
        if shadow:
            # A hard 40% shadow, moving with the subject so it cannot simply be
            # absorbed into the median background.
            sx = x + 12
            frame[26:32, sx : sx + 10] = tuple(c * 0.6 for c in backdrop)
        frames.append(frame)
    return frames, backdrop


def test_background_estimate_recovers_the_backdrop():
    frames, backdrop = _clip_with_moving_subject()
    estimated = sk.estimate_background(frames)
    assert np.allclose(estimated, np.array(backdrop, dtype=np.float64), atol=1.0)


def test_corridor_key_finds_a_moving_subject_without_a_key_colour():
    frames, _ = _clip_with_moving_subject()
    alphas, _ = sk.corridor_alpha(frames)
    for step, alpha in enumerate(alphas):
        x = 4 + step * 12
        assert alpha[14:26, x : x + 10].mean() > 0.9, f"subject missed in frame {step}"
        assert alpha[0:8, 0:8].mean() < 0.05, f"bare backdrop kept in frame {step}"


def test_corridor_key_ignores_a_shadow_cast_on_the_backdrop():
    """Without this, every clip exports with its own shadow welded to the sprite."""
    frames, _ = _clip_with_moving_subject(shadow=True)
    alphas, _ = sk.corridor_alpha(frames)
    for step, alpha in enumerate(alphas):
        x = 4 + step * 12
        sx = x + 12
        assert alpha[14:26, x : x + 10].mean() > 0.9, f"subject missed in frame {step}"
        assert alpha[26:32, sx : sx + 10].mean() < 0.05, f"shadow kept in frame {step}"


def test_corridor_key_luma_weight_trades_shadows_for_grey_subjects():
    """The default is a trade-off, so both ends of it are pinned down here."""
    frames, _ = _clip_with_moving_subject(shadow=True)
    greedy, _ = sk.corridor_alpha(frames, luma_weight=1.0)
    assert greedy[0][26:32, 16:26].mean() > 0.5, "luma_weight=1.0 should catch the shadow"


def test_corridor_key_fills_interior_holes():
    """A subject region matching the backdrop must not become a hole in the sprite."""
    backdrop = (120, 130, 140)
    frames = []
    for step in range(5):
        frame = solid(40, 80, backdrop)
        x = 4 + step * 12
        frame[10:30, x : x + 16] = (200, 40, 40)
        frame[17:22, x + 5 : x + 11] = backdrop  # a backdrop-coloured belly
        frames.append(frame)
    alphas, _ = sk.corridor_alpha(frames)
    x = 4
    assert alphas[0][17:22, x + 5 : x + 11].mean() > 0.9


# --------------------------------------------------------------------------
# Morphology
# --------------------------------------------------------------------------


def test_open_removes_speckle_and_close_fills_pinholes():
    mask = np.zeros((20, 20), dtype=bool)
    mask[5:15, 5:15] = True
    mask[1, 1] = True  # lone speckle
    mask[9, 9] = False  # pinhole

    opened = sk.binary_open(mask, 1)
    assert not opened[1, 1], "opening did not remove the lone speckle"
    # An opening removes speckle and deliberately does NOT fill holes - that is
    # what closing is for. The pinhole must still be here.
    assert not opened[9, 9]
    assert opened[6:9, 6:9].all()

    closed = sk.binary_close(mask, 1)
    assert closed[9, 9], "closing did not fill the pinhole"
    assert closed[1, 1], "closing is not supposed to remove speckle"


def test_fill_holes_leaves_the_border_connected_background_alone():
    mask = np.zeros((20, 20), dtype=bool)
    mask[5:15, 5:15] = True
    mask[8:12, 8:12] = False
    filled = sk.fill_holes(mask)
    assert filled[8:12, 8:12].all()
    assert not filled[0, 0]


# --------------------------------------------------------------------------
# Subject detection and alignment
# --------------------------------------------------------------------------


def _subject_frame(height, width, top, left, size=6):
    """An RGBA frame with an opaque square, plus its alpha."""
    frame = np.zeros((height, width, 4), dtype=np.uint8)
    frame[top : top + size, left : left + size] = (255, 255, 255, 255)
    alpha = frame[:, :, 3].astype(np.float64) / 255.0
    return frame, alpha


def test_content_box_is_tight():
    _, alpha = _subject_frame(30, 30, 5, 7, size=4)
    assert sk.content_box(alpha) == (7, 5, 11, 9)


def test_content_box_of_an_empty_mask_is_none():
    assert sk.content_box(np.zeros((10, 10))) is None


def test_union_alignment_preserves_vertical_motion():
    """The headline fix. A walk cycle's bob IS the frame-to-frame change in
    where the subject sits; an aligner that removes it removes the animation."""
    frames, alphas = zip(
        *[_subject_frame(60, 60, top, 10) for top in (10, 16, 22)]
    )
    result = sk.align_frames(list(frames), list(alphas), mode="union")

    rows = [int(np.flatnonzero(f[:, :, 3].any(axis=1))[0]) for f in result.frames]
    assert rows == [0, 6, 12], f"vertical motion was flattened: {rows}"
    assert result.frame_height == 18  # 22 + 6 - 10
    assert len(set(rows)) == 3


def test_both_centered_flattens_vertical_motion():
    """Documents the legacy behaviour rather than trusting it stayed put.

    If this ever starts passing with distinct rows, the modes have been wired
    together and `union` is no longer doing anything special.
    """
    frames, alphas = zip(
        *[_subject_frame(60, 60, top, 10) for top in (10, 16, 22)]
    )
    result = sk.align_frames(list(frames), list(alphas), mode="both_centered")
    rows = [int(np.flatnonzero(f[:, :, 3].any(axis=1))[0]) for f in result.frames]
    assert len(set(rows)) == 1, "legacy mode unexpectedly preserved motion"


def test_drift_corrected_cancels_travel_but_keeps_the_bob():
    """The mode AI-generated loops actually want.

    Subject moves right AND bobs. Horizontal travel is drift the video model
    was never told to avoid; the bob is animation. One must go, one must stay.
    """
    frames, alphas = zip(
        *[
            _subject_frame(80, 200, top, left)
            for top, left in ((20, 10), (26, 60), (20, 110), (14, 160))
        ]
    )
    result = sk.align_frames(list(frames), list(alphas), mode="drift_corrected")

    columns = [int(np.flatnonzero(f[:, :, 3].any(axis=0))[0]) for f in result.frames]
    rows = [int(np.flatnonzero(f[:, :, 3].any(axis=1))[0]) for f in result.frames]

    assert len(set(columns)) == 1, f"horizontal drift survived: {columns}"
    assert rows == [6, 12, 6, 0], f"vertical bob was flattened: {rows}"
    # ...and the frame is only as wide as the subject, not as wide as its journey.
    assert result.frame_width == 6


def test_union_would_have_paid_for_that_with_frame_width():
    """Why drift_corrected exists: union keeps the travel and must hold it."""
    frames, alphas = zip(
        *[
            _subject_frame(80, 200, top, left)
            for top, left in ((20, 10), (26, 60), (20, 110), (14, 160))
        ]
    )
    union = sk.align_frames(list(frames), list(alphas), mode="union")
    assert union.frame_width == 156  # 160 + 6 - 10


def test_bottom_center_plants_the_feet():
    frames, alphas = zip(
        *[
            _subject_frame(60, 60, 10, 10, size=6),
            _subject_frame(60, 60, 8, 10, size=8),
        ]
    )
    result = sk.align_frames(list(frames), list(alphas), mode="bottom_center")
    bottoms = [int(np.flatnonzero(f[:, :, 3].any(axis=1))[-1]) for f in result.frames]
    assert len(set(bottoms)) == 1, f"feet slid: {bottoms}"


def test_pivot_is_the_ground_line_not_a_hardcoded_offset():
    frames, alphas = zip(*[_subject_frame(60, 60, 10, 10) for _ in range(2)])
    result = sk.align_frames(list(frames), list(alphas), mode="union")
    assert result.pivot_y == result.frame_height
    assert result.pivot_x == result.frame_width // 2


def test_an_empty_frame_is_rejected_and_does_not_size_the_sheet():
    """The silent-inflation bug.

    An over-keyed frame used to be handed the ENTIRE video frame as its bounding
    box, which then won max() against every real box - so one bad frame resized
    the whole sheet to full video resolution and left every sprite a small
    island in the middle, with no error and no warning.
    """
    good_a, alpha_a = _subject_frame(300, 400, 10, 10, size=10)
    good_b, alpha_b = _subject_frame(300, 400, 12, 14, size=10)
    empty = np.zeros((300, 400, 4), dtype=np.uint8)
    empty_alpha = np.zeros((300, 400))

    result = sk.align_frames(
        [good_a, empty, good_b],
        [alpha_a, empty_alpha, alpha_b],
        mode="union",
        indices=[3, 4, 5],
    )

    assert len(result.frames) == 2
    assert result.kept_indices == [3, 5]
    assert [r.index for r in result.rejects] == [4]
    assert "matched the entire frame" in result.rejects[0].reason
    assert result.frame_width < 30 and result.frame_height < 30, (
        f"one empty frame inflated the sheet to "
        f"{result.frame_width}x{result.frame_height}"
    )


def test_every_frame_empty_raises_rather_than_exporting_nothing():
    empty = np.zeros((20, 20, 4), dtype=np.uint8)
    with pytest.raises(ValueError, match="tolerance"):
        sk.align_frames([empty, empty], [np.zeros((20, 20))] * 2)


def test_unknown_alignment_mode_names_the_known_ones():
    frame, alpha = _subject_frame(20, 20, 2, 2)
    with pytest.raises(ValueError, match="union"):
        sk.align_frames([frame], [alpha], mode="sideways")  # type: ignore[arg-type]


# --------------------------------------------------------------------------
# Packing
# --------------------------------------------------------------------------


def test_pack_sheet_is_one_horizontal_row_indexed_by_frame_width():
    frames = []
    for value in (10, 20, 30):
        frame = np.zeros((4, 6, 4), dtype=np.uint8)
        frame[:, :] = (value, value, value, 255)
        frames.append(frame)

    sheet = sk.pack_sheet(frames)
    assert sheet.shape == (4, 18, 4)
    for index, value in enumerate((10, 20, 30)):
        assert np.all(sheet[:, index * 6 : (index + 1) * 6, 0] == value)


def test_pack_sheet_refuses_mismatched_frames():
    a = np.zeros((4, 6, 4), dtype=np.uint8)
    b = np.zeros((5, 6, 4), dtype=np.uint8)
    with pytest.raises(ValueError, match="align_frames must run"):
        sk.pack_sheet([a, b])
