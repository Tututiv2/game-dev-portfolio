#!/usr/bin/env python
"""Render every theme (or the whole theme x mode matrix) into one contact sheet.

BUILD_PROMPT.md §8 M5: "generate them programmatically into a contact sheet
rather than clicking through them by hand."

    tools\\contact_sheet.py                     # 10 themes, each in its default mode
    tools\\contact_sheet.py --matrix            # all 50 combinations
    tools\\contact_sheet.py --video clip.mp4    # use real footage as the backdrop

Writes contact_sheet.png next to the project.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.core import ffmpeg
from src.core.ass_writer import build_ass
from src.core.grouping import Layout, group_words
from src.core.model import Word
from src.core.reveal import MODES
from src.core.themes import Animation, Theme

W, H = 1280, 720
SAMPLE = ["we", "absolutely", "crushed", "it", "today"]

# Word 3 spans 1.2-1.6s and its pop animation lasts 90 ms, so sample mid-pop —
# at 1.3 the scale has already settled and the scale-only themes look inert.
SAMPLE_T = 1.25


def label_overlay(doc: str, text: str) -> str:
    """Burn the combination's name into the tile, using libass itself so we don't
    depend on ffmpeg's drawtext finding a font."""
    style = ("Style: Label,Arial,26,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,"
             "0,0,0,0,100,100,0,0,1,2,0,7,24,24,20,1")
    doc = doc.replace("[Events]", f"{style}\n\n[Events]")
    event = (f"Dialogue: 9,0:00:00.00,9:59:59.00,Label,,0,0,0,,"
             f"{{\\an7\\pos(24,18)}}{text}")
    return doc.rstrip("\n") + "\n" + event + "\n"


def make_captions(mode: str):
    words = [Word(text=t, start=i * 0.4, end=(i + 1) * 0.4, prob=1.0)
             for i, t in enumerate(SAMPLE)]
    cfg = Layout.for_video(W, H, max_words_per_card=5)
    if mode == "ONE_WORD":
        cfg.max_words_per_card = 1
    return group_words(words, cfg, duration=10.0)


def render_tile(theme_name: str, mode: str, out: Path, video: str | None) -> None:
    theme = Theme.load(theme_name)
    doc = label_overlay(
        build_ass(make_captions(mode), theme, Animation(), mode, W, H),
        f"{theme.label}  /  {mode}",
    )
    with tempfile.TemporaryDirectory(prefix="sheet_") as tmp:
        subs = ffmpeg.subtitle_filter(tmp, doc)
        if video:
            src = ["-ss", "8", "-i", str(Path(video).resolve())]
            vf = f"scale={W}:{H},{subs}"
        else:
            src = ["-f", "lavfi", "-i",
                   f"gradients=s={W}x{H}:c0=0x141a2e:c1=0x3d1b4d:speed=0.01:d=5"]
            vf = subs
        r = subprocess.run(
            [ffmpeg.ffmpeg_path(), "-y", *src,
             "-vf", vf, "-ss", str(SAMPLE_T), "-frames:v", "1",
             "-loglevel", "error", str(out)],
            cwd=tmp, capture_output=True,
        )
        if r.returncode != 0:
            raise RuntimeError(f"{theme_name}/{mode}: "
                               f"{r.stderr.decode('utf-8', 'replace')[-800:]}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--matrix", action="store_true", help="all 50 combinations")
    ap.add_argument("--video", help="use a frame of this video as the backdrop")
    ap.add_argument("-o", "--output", default="contact_sheet.png")
    args = ap.parse_args()

    themes = Theme.available()
    combos = ([(t, m) for t in themes for m in MODES] if args.matrix
              else [(t, Theme.load(t).default_reveal) for t in themes])
    cols = len(MODES) if args.matrix else 2

    with tempfile.TemporaryDirectory(prefix="sheetdir_") as tmp:
        tiles = []
        for i, (theme_name, mode) in enumerate(combos):
            out = Path(tmp) / f"tile_{i:03d}.png"
            print(f"  [{i + 1:2d}/{len(combos)}] {theme_name} / {mode}", flush=True)
            render_tile(theme_name, mode, out, args.video)
            tiles.append(out)

        rows = -(-len(tiles) // cols)
        dest = Path(args.output).resolve()
        r = subprocess.run(
            [ffmpeg.ffmpeg_path(), "-y",
             "-i", str(Path(tmp) / "tile_%03d.png"),
             "-vf", f"scale=640:-1,tile={cols}x{rows}:margin=8:padding=8:color=0x0b0b0f",
             "-frames:v", "1", "-loglevel", "error", str(dest)],
            capture_output=True,
        )
        if r.returncode != 0:
            print(r.stderr.decode("utf-8", "replace")[-2000:])
            return 1

    print(f"\n  {dest}  ({cols}x{rows} grid, {len(tiles)} tiles)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
