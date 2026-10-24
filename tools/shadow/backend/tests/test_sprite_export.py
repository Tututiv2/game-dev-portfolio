"""Tests for the export-side helpers around the pure pixel pipeline.

These cover the seams that `test_spritekit.py` deliberately does not: input
validation, the download guard, and the generated integration snippet.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.main import (
    STORAGE_ROOT,
    build_canvas_snippet,
    fetch_source_video,
    parse_hex_color,
)


# --------------------------------------------------------------------------
# Key colour parsing
# --------------------------------------------------------------------------


def test_parse_hex_color_accepts_both_spellings():
    assert parse_hex_color("#FF00FF") == (255, 0, 255)
    assert parse_hex_color("00ff00") == (0, 255, 0)
    assert parse_hex_color("  #10203A  ") == (16, 32, 58)


@pytest.mark.parametrize("bad", ["", "#FFF", "green", "#GGGGGG", "#12345", None])
def test_parse_hex_color_refuses_junk_instead_of_defaulting_to_green(bad):
    """It used to `return (0, 255, 0)` for anything malformed.

    A typo in the key colour therefore keyed GREEN and the export succeeded
    against a colour nobody chose, with nothing anywhere saying so.
    """
    with pytest.raises(ValueError, match="Invalid key colour"):
        parse_hex_color(bad)


# --------------------------------------------------------------------------
# Source fetching
# --------------------------------------------------------------------------


def test_fetch_refuses_unsupported_schemes(tmp_path: Path):
    with pytest.raises(RuntimeError, match="Unsupported source URL scheme"):
        fetch_source_video("ftp://example.com/clip.mp4", tmp_path / "out.mp4")


def test_fetch_refuses_local_files_outside_the_asset_store(tmp_path: Path):
    """`urlretrieve` accepted file:// URLs, so an asset URL could name any file
    the server process was able to read."""
    outsider = tmp_path / "secrets.txt"
    outsider.write_text("not a video", encoding="utf-8")
    with pytest.raises(RuntimeError, match="must live under the asset store"):
        fetch_source_video(outsider.as_uri(), tmp_path / "out.mp4")


def test_fetch_allows_local_files_inside_the_asset_store():
    inside = STORAGE_ROOT / "clip-under-test.mp4"
    inside.parent.mkdir(parents=True, exist_ok=True)
    inside.write_bytes(b"pretend this is a video")
    destination = STORAGE_ROOT / "copied.mp4"
    try:
        fetch_source_video(inside.as_uri(), destination)
        assert destination.read_bytes() == b"pretend this is a video"
    finally:
        inside.unlink(missing_ok=True)
        destination.unlink(missing_ok=True)


# --------------------------------------------------------------------------
# The generated snippet
# --------------------------------------------------------------------------


def test_snippet_carries_the_real_fps_and_pivot():
    snippet = build_canvas_snippet(
        frame_width=128,
        frame_height=96,
        frame_count=7,
        fps=24,
        pivot_x=64,
        pivot_y=96,
    )
    assert "fps: 24" in snippet
    assert "pivotX: 64" in snippet
    assert "pivotY: 96" in snippet
    assert "frameCount: 7" in snippet


def test_snippet_does_not_hardcode_twelve_fps_or_the_phantom_offset():
    snippet = build_canvas_snippet(
        frame_width=64, frame_height=64, frame_count=4, fps=30, pivot_x=32, pivot_y=64
    )
    assert "fps: 12" not in snippet
    assert "offsetY = -12" not in snippet


def test_snippet_keeps_playback_state_per_instance():
    """The old snippet held frameIndex and lastTick in module scope, so every
    sprite drawn through it shared one playhead and a second instance simply
    could not work."""
    snippet = build_canvas_snippet(
        frame_width=64, frame_height=64, frame_count=4, fps=12, pivot_x=32, pivot_y=64
    )
    assert "let frameIndex" not in snippet
    assert "let lastTick" not in snippet
    assert "function createSprite" in snippet
    assert "sprite.frame" in snippet


def test_snippet_still_exposes_draw_sprite():
    """The smoke test greps for this; keep the entry point stable."""
    snippet = build_canvas_snippet(
        frame_width=64, frame_height=64, frame_count=4, fps=12, pivot_x=32, pivot_y=64
    )
    assert "function drawSprite" in snippet
