#!/usr/bin/env python
"""SubtitleStudio M1 — the pipeline, no UI (BUILD_PROMPT.md §8).

Video in -> transcript -> caption cards -> .ass -> burned MP4 out.

    python subtitle.py clip.mp4
    python subtitle.py clip.mp4 --theme beast --mode ONE_WORD
    python subtitle.py clip.mp4 --seconds 20          # quick look at the first 20s
    python subtitle.py --list-themes

The transcript is cached in <video>.subproj, so every run after the first is
pure string work plus an encode — changing theme or reveal mode never re-runs
Whisper (§4.6).
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from src.core import ffmpeg
from src.core.ass_writer import build_ass
from src.core.grouping import Layout, group_words
from src.core.model import Caption, Layer, Project, Word
from src.core.reveal import MODES, MODE_LABELS
from src.core.srt_writer import build_srt, build_vtt
from src.core.themes import Animation, Theme


def log(msg: str = "") -> None:
    print(msg, flush=True)


def progress_bar(label: str):
    state = {"last": -1.0, "t0": time.time()}

    def cb(frac: float) -> None:
        pct = int(frac * 100)
        if pct <= state["last"] and frac < 1.0:
            return
        state["last"] = pct
        # ASCII only: a double-clicked .bat gets a cp437 console, where box-drawing
        # characters come out as mojibake.
        bar = "=" * (pct // 3) + "-" * (33 - pct // 3)
        elapsed = time.time() - state["t0"]
        end = "\n" if frac >= 1.0 else "\r"
        print(f"  {label:<12} [{bar}] {pct:3d}%  {elapsed:5.1f}s", end=end, flush=True)

    return cb


# ---------------------------------------------------------------------------


def list_themes() -> None:
    log("Themes:")
    for name in Theme.available():
        theme = Theme.load(name)
        family, exact = theme.resolved_font()
        note = "" if exact else f"  (font '{theme.font}' not installed -> {family})"
        log(f"  {name:<14} {theme.label:<14} default mode: {theme.default_reveal}{note}")
    log("\nReveal modes:")
    for mode, label in MODE_LABELS.items():
        log(f"  {mode:<18} {label}")


def get_words(video: Path, args) -> tuple[list[Word], str]:
    """Transcribe, or reuse the cached transcript (§4.6)."""
    proj_path = video.with_suffix(video.suffix + ".subproj")

    if proj_path.is_file() and not args.retranscribe:
        project = Project.load(proj_path)
        # The CLI works on one track; a multi-layer project made in the app opens
        # here as its first layer.
        words = project.layers[0].words if project.layers else []
        if words:
            extra = (f", {len(project.layers)} layers in the app"
                     if len(project.layers) > 1 else "")
            log(f"  reusing transcript  {proj_path.name}  "
                f"({len(words)} words, {project.language or '?'}{extra}) "
                f"-- no GPU work needed")
            return words, project.language

    log("  extracting audio (16 kHz mono)...")
    audio = ffmpeg.extract_audio(video)

    from src.core import transcribe as tr

    if tr.CUDA_DLL_DIRS:
        log(f"  CUDA DLLs registered ({len(tr.CUDA_DLL_DIRS)} folders)")
    device = "cuda" if (tr.cuda_available() and not args.cpu) else "cpu"
    log(f"  loading model '{args.model}' on {device}"
        + ("  (first run downloads ~3 GB)" if device else ""))

    t0 = time.time()
    result = tr.transcribe(
        audio,
        model_name=args.model,
        language=args.language,
        prefer_gpu=not args.cpu,
        batch_size=0 if args.no_batch else args.batch,
        on_progress=progress_bar("transcribe"),
    )
    took = time.time() - t0
    speed = (result.duration / took) if took else 0
    log(f"  {len(result.words)} words, language '{result.language}', "
        f"{took:.1f}s for {result.duration:.0f}s of audio  ({speed:.1f}x realtime"
        f"{', batched' if result.batched else ''})")
    if result.dropped_segments:
        log(f"  dropped {result.dropped_segments} segment(s) as silence/hallucination")
    return result.words, result.language


def main() -> int:
    p = argparse.ArgumentParser(description="SubtitleStudio M1 pipeline")
    p.add_argument("video", nargs="?", help="input video file")
    p.add_argument("-o", "--output", help="output video (default: <name>_subtitled.mp4)")
    p.add_argument("--theme", default="hormozi", help="preset name (--list-themes)")
    p.add_argument("--mode", default=None, choices=list(MODES),
                   help="reveal mode; defaults to the theme's recommendation")
    p.add_argument("--model", default="large-v3", help="whisper model size")
    p.add_argument("--language", default=None, help="force a language, e.g. en")
    p.add_argument("--cpu", action="store_true", help="force CPU (~20x slower)")
    p.add_argument("--batch", type=int, default=8, help="batched inference size")
    p.add_argument("--no-batch", action="store_true",
                   help="sequential decoding: slower, more segments, safer filters")
    p.add_argument("--retranscribe", action="store_true", help="ignore cached transcript")
    p.add_argument("--seconds", type=float, default=0.0,
                   help="only render the first N seconds (quick preview)")
    p.add_argument("--max-words", type=int, default=None, help="max words per card")
    p.add_argument("--size", type=float, default=None,
                   help="font size as %% of video height, overrides the theme")
    p.add_argument("--encoder", default="h264_nvenc", help="h264_nvenc | libx264 | ...")
    p.add_argument("--cq", type=int, default=19, help="quality (lower = better)")
    p.add_argument("--no-burn", action="store_true", help="write sidecars only, no encode")
    p.add_argument("--list-themes", action="store_true")
    args = p.parse_args()

    if args.list_themes:
        list_themes()
        return 0
    if not args.video:
        p.print_help()
        return 2

    video = Path(args.video).expanduser().resolve()
    if not video.is_file():
        log(f"error: no such file: {video}")
        return 1

    theme = Theme.load(args.theme)
    if args.size:
        theme.size_pct = args.size
    mode = args.mode or theme.default_reveal
    family, exact = theme.resolved_font()
    if not exact:
        log(f"  note: font '{theme.font}' is not installed; using '{family}'")

    log(f"\nSubtitleStudio  ·  {video.name}")
    log(f"  theme '{theme.label}'  ·  mode {mode}\n")

    info = ffmpeg.probe(video)
    log(f"  {info.width}x{info.height} @ {info.fps:g}fps  ·  {info.duration:.1f}s  "
        f"·  {info.video_codec}/{info.audio_codec or 'no audio'}")
    if not info.has_audio:
        log("error: this file has no audio track to transcribe.")
        return 1

    words, language = get_words(video, args)
    if not words:
        log("error: no speech found.")
        return 1

    cfg = Layout.for_video(info.width, info.height, max_words_per_card=args.max_words)
    if mode == "ONE_WORD":
        cfg.max_words_per_card = 1          # §4.3 mode C
    captions = group_words(words, cfg, info.duration)
    log(f"  {len(captions)} caption cards  "
        f"(<={cfg.max_words_per_card} words, <={cfg.max_chars_per_line} chars/line)")

    # Cache the transcript so a restyle never touches the GPU again. Writing a
    # single layer here would throw away extra layers made in the app, so keep
    # any that already exist and only refresh the first one's words.
    proj_path = video.with_suffix(video.suffix + ".subproj")
    existing = Project.load(proj_path).layers if proj_path.is_file() else []
    if existing:
        existing[0].words = words
        existing[0].theme, existing[0].reveal = theme.name, mode
        existing[0].max_words = cfg.max_words_per_card
        project_layers = existing
    else:
        project_layers = [Layer(name="Captions", theme=theme.name, reveal=mode,
                                words=words, max_words=cfg.max_words_per_card)]
    Project(source=str(video), layers=project_layers, language=language,
            model=args.model).save(proj_path)

    ass = build_ass(captions, theme, Animation(), mode, info.width, info.height)
    n_events = ass.count("\nDialogue:")

    # Subtitle files that share a video's basename are auto-loaded by VLC, MPC
    # and mpv. Alongside a burned-in export that shows TWO sets of captions for
    # the whole video, so they only sit next to the video when we aren't burning.
    if args.no_burn:
        side_dir, base = video.parent, video.stem
    else:
        side_dir = video.parent / "caption files (not for playback)"
        side_dir.mkdir(parents=True, exist_ok=True)
        base = video.stem
    stem = side_dir / base
    Path(f"{stem}.ass").write_text(ass, encoding="utf-8")
    Path(f"{stem}.srt").write_text(build_srt(captions), encoding="utf-8")
    Path(f"{stem}.vtt").write_text(build_vtt(captions), encoding="utf-8")
    log(f"  {n_events} ASS events -> {Path(f'{stem}.ass').name}, .srt, .vtt"
        + ("" if args.no_burn else f"  (in '{side_dir.name}')"))

    if args.no_burn:
        log("\ndone (sidecars only).")
        return 0

    dest = Path(args.output) if args.output else Path(f"{stem}_subtitled.mp4")
    extra = ["-t", str(args.seconds)] if args.seconds else []
    total = args.seconds or info.duration
    log(f"\n  burning in with {args.encoder} (cq {args.cq})"
        + (f", first {args.seconds:g}s only" if args.seconds else ""))

    t0 = time.time()
    try:
        ffmpeg.burn_subtitles(
            video, ass, dest, info,
            encoder=args.encoder, cq=args.cq,
            on_progress=progress_bar("encode"),
            extra_video_args=extra,
        )
    except ffmpeg.FFmpegError as exc:
        if args.encoder.endswith("_nvenc"):
            log(f"\n  NVENC failed, retrying on CPU...\n  {str(exc)[:300]}")
            ffmpeg.burn_subtitles(video, ass, dest, info, encoder="libx264",
                                  cq=args.cq, on_progress=progress_bar("encode"),
                                  extra_video_args=extra)
        else:
            raise
    took = time.time() - t0
    size_mb = dest.stat().st_size / 1e6

    log(f"\n  {dest}")
    log(f"  {size_mb:.1f} MB in {took:.1f}s  ({total / took:.1f}x realtime)")
    log("\ndone.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log("\ncancelled.")
        sys.exit(130)
    except ffmpeg.FFmpegError as exc:
        log(f"\nffmpeg error:\n{exc}")
        sys.exit(1)
