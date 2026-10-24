#!/usr/bin/env python
"""Prove the pop animation is actually visible.

"The font barely changes size as words appear" is measurable: render the same
word at several points across its pop and compare how tall it is on screen. A
pop that only shrinks 12% over 90 ms is 2-3 frames of a few pixels — this
measures it instead of arguing about it.

    tools\\verify_pop.py
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

W, H = 1280, 720


def glyph_height(ass: str, t: float) -> tuple[int, int]:
    """(height, width) of the drawn text at time t, on black."""
    with tempfile.TemporaryDirectory(prefix="pop_") as tmp:
        vf = ffmpeg.subtitle_filter(tmp, ass)
        out = subprocess.run(
            [ffmpeg.ffmpeg_path(), "-y",
             "-f", "lavfi", "-i", f"color=c=black:s={W}x{H}:r=60:d=10",
             "-vf", vf, "-ss", f"{t:.3f}", "-frames:v", "1",
             "-pix_fmt", "gray", "-f", "rawvideo", "-loglevel", "error", "-"],
            cwd=tmp, capture_output=True, creationflags=ffmpeg._NO_WINDOW,
        )
    if out.returncode != 0 or len(out.stdout) != W * H:
        return 0, 0
    mask = np.frombuffer(out.stdout, dtype=np.uint8).reshape(H, W) > 90
    rows = np.flatnonzero(mask.any(axis=1))
    cols = np.flatnonzero(mask.any(axis=0))
    if rows.size == 0:
        return 0, 0
    return int(rows[-1] - rows[0] + 1), int(cols[-1] - cols[0] + 1)


def main() -> int:
    anim = Animation()
    # A single word, alone, so nothing else is on screen to measure.
    words = [Word("POP", 1.0, 2.0, 1.0)]
    caps = group_words(words, Layout.for_video(W, H, max_words_per_card=1), 10.0)
    ass = build_ass(caps, Theme.load("hormozi"), anim, "WORD_POP", W, H)

    print(f"\nPop curve: {anim.pop_from}% -> {anim.pop_over}% "
          f"at {anim.pop_rise_ms}ms -> 100% by {anim.pop_ms}ms\n")
    print("  measured on screen (libass, the real export path):\n")

    samples = [0.0, 0.03, 0.06, 0.09, 0.13, 0.18, 0.26, 0.40, 0.80]
    heights = []
    for offset in samples:
        h, w = glyph_height(ass, 1.0 + offset)
        heights.append(h)
        expect = anim.scale_at(offset * 1000) * 100
        bar = "#" * max(0, int(h / 3))
        print(f"    +{int(offset*1000):4d} ms   height {h:4d}px   "
              f"curve says {expect:5.1f}%   {bar}")

    settled = heights[-1] or 1
    smallest = min(h for h in heights if h) if any(heights) else 0
    biggest = max(heights)
    growth = (biggest - smallest) / settled * 100
    print(f"\n  smallest {smallest}px, biggest {biggest}px, settled {settled}px")
    print(f"  visible size change: {growth:.0f}% of the final height")

    ok = growth >= 25 and biggest > settled
    print(f"\n  {'PASS' if ok else 'FAIL'} — the word visibly springs up and "
          f"overshoots\n" if ok else
          f"\n  FAIL — change is only {growth:.0f}%, that reads as a twitch\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
