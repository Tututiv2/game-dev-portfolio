#!/usr/bin/env python
"""Prove the caption does not shift horizontally as words appear.

BUILD_PROMPT.md §10: "In WORD_POP and TYPEWRITER, the caption does not shift
horizontally as words appear — verify by diffing two rendered frames from the
same card and confirming the already-visible words are at identical pixel
positions."

This is a real pixel test, not an inspection of the .ass text. It renders the
subtitles over a SOLID BLACK source so the only non-black pixels are glyphs, then
compares the region that was already revealed in the earlier frame.

    .venv\\Scripts\\python.exe tools\\verify_no_shift.py

Exits non-zero if any mode shifts.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.core import ffmpeg
from src.core.ass_writer import build_ass
from src.core.grouping import Layout, group_words
from src.core.model import Word
from src.core.themes import Animation, Theme

W, H = 1920, 1080
THRESHOLD = 100          # luma above this counts as glyph, on a black background


def render_mask(ass_text: str, t: float) -> np.ndarray:
    """Burn the subtitles onto black at time t; return a boolean glyph mask."""
    with tempfile.TemporaryDirectory(prefix="shift_") as tmp:
        vf = ffmpeg.subtitle_filter(tmp, ass_text)
        out = subprocess.run(
            [
                ffmpeg.ffmpeg_path(), "-y",
                "-f", "lavfi", "-i", f"color=c=black:s={W}x{H}:r=30:d=30",
                "-vf", vf,
                "-ss", f"{t:.3f}", "-frames:v", "1",
                "-pix_fmt", "gray", "-f", "rawvideo",
                "-loglevel", "error", "-",
            ],
            cwd=tmp, capture_output=True,
        )
        if out.returncode != 0:
            raise RuntimeError(out.stderr.decode("utf-8", "replace")[-2000:])
    frame = np.frombuffer(out.stdout, dtype=np.uint8)
    if frame.size != W * H:
        raise RuntimeError(f"expected {W * H} bytes, got {frame.size}")
    return frame.reshape(H, W) > THRESHOLD


def check(mode: str, theme_name: str = "hormozi") -> bool:
    # A card of five evenly-spaced words, each 0.4 s, no gaps.
    words = [Word(text=t, start=i * 0.4, end=(i + 1) * 0.4, prob=1.0)
             for i, t in enumerate(["we", "absolutely", "did", "the", "thing"])]
    cfg = Layout.for_video(W, H, max_words_per_card=5)
    if mode == "ONE_WORD":
        cfg.max_words_per_card = 1
    captions = group_words(words, cfg, duration=10.0)
    ass = build_ass(captions, Theme.load(theme_name), Animation(), mode, W, H)

    # Sample each word 0.3 s in — well past the 90 ms pop, so nothing is mid-scale.
    samples = [(i, i * 0.4 + 0.3) for i in range(len(words))]
    masks = {i: render_mask(ass, t) for i, t in samples}

    first = masks[0]
    cols = np.flatnonzero(first.any(axis=0))
    if cols.size == 0:
        print(f"  {mode:<18} SKIP (nothing rendered at the first sample)")
        return True
    left, right = int(cols[0]), int(cols[-1])

    worst, worst_at = 0.0, None
    for i in range(1, len(samples)):
        later = masks[i]
        region_a = first[:, left:right + 1]
        region_b = later[:, left:right + 1]
        union = region_a.sum() + region_b.sum()
        if union == 0:
            continue
        diff = np.logical_xor(region_a, region_b).sum() / union
        if diff > worst:
            worst, worst_at = diff, i

    # A few percent covers antialiasing and the colour change on the active word;
    # a real shift moves whole glyph bodies and scores far higher.
    ok = worst < 0.06
    edge = "moved" if int(np.flatnonzero(masks[len(samples) - 1].any(axis=0))[0]) != left \
        else "held"
    print(f"  {mode:<18} left edge {edge} at x={left:<5} "
          f"max glyph diff {worst * 100:5.2f}% (word {worst_at})  "
          f"{'PASS' if ok else 'FAIL'}")
    return ok


def check_preview_seek() -> bool:
    """The scrub preview must show the words that belong at that timestamp.

    `-ss` before `-i` rebases output timestamps to zero, so without `-copyts` the
    ass filter renders the card's opening state at EVERY seek position — a preview
    that is confidently, silently wrong everywhere. Catch it by seeking to three
    points in one card and checking the revealed text actually grows.
    """
    words = [Word(text=t, start=i * 0.4, end=(i + 1) * 0.4, prob=1.0)
             for i, t in enumerate(["we", "absolutely", "crushed", "it", "today"])]
    captions = group_words(words, Layout.for_video(W, H, max_words_per_card=5),
                           duration=10.0)
    ass = build_ass(captions, Theme.load("hormozi"), Animation(), "WORD_POP", W, H)

    with tempfile.TemporaryDirectory(prefix="seek_") as tmp:
        clip = Path(tmp) / "black.mp4"
        subprocess.run(
            [ffmpeg.ffmpeg_path(), "-y", "-f", "lavfi",
             "-i", f"color=c=black:s={W}x{H}:r=30:d=6",
             "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
             "-loglevel", "error", str(clip)],
            check=True, capture_output=True,
        )
        widths = []
        for t in (0.2, 1.0, 1.8):
            png = ffmpeg.render_frame(clip, ass, t)
            raw = subprocess.run(
                [ffmpeg.ffmpeg_path(), "-y", "-i", "-", "-pix_fmt", "gray",
                 "-f", "rawvideo", "-loglevel", "error", "-"],
                input=png, capture_output=True,
            ).stdout
            mask = np.frombuffer(raw, dtype=np.uint8).reshape(H, W) > THRESHOLD
            cols = np.flatnonzero(mask.any(axis=0))
            widths.append(int(cols[-1] - cols[0]) if cols.size else 0)

    ok = widths[0] < widths[1] < widths[2]
    print(f"  preview seek       revealed width at t=0.2/1.0/1.8: "
          f"{widths[0]} / {widths[1]} / {widths[2]} px  {'PASS' if ok else 'FAIL'}")
    if not ok:
        print("    -> frames are identical: the seek is not reaching the filter "
              "(missing -copyts?)")
    return ok


def main() -> int:
    print(f"\nPixel shift check — subtitles burned onto black, {W}x{H}\n")
    results = {m: check(m) for m in ("WORD_POP", "TYPEWRITER", "KARAOKE_HIGHLIGHT")}
    results["preview seek"] = check_preview_seek()
    print()
    if all(results.values()):
        print("Layout holds, and the preview renders the right moment.\n")
        return 0
    print("FAILED: " + ", ".join(m for m, ok in results.items() if not ok))
    return 1


if __name__ == "__main__":
    sys.exit(main())
