"""ffmpeg / ffprobe wrapper — BUILD_PROMPT.md §6.

Binary discovery, media probing, audio extraction, and the burn-in call.
Contains the fix for the Windows filter-path trap (§6.2): never pass an absolute
Windows path to `ass=`; cwd into a short ASCII temp dir and pass a bare filename.

No Qt in this file.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

# Fallback if ffmpeg is somehow not on PATH (verified present on the target machine).
_FALLBACK_DIR = Path(r"C:\Users\you\Documents\FFmpeg\bin")

# Hide the console window when we're launched from a GUI (M2+).
_NO_WINDOW = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0


class FFmpegError(RuntimeError):
    pass


def _resolve(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    candidate = _FALLBACK_DIR / f"{name}.exe"
    if candidate.is_file():
        return str(candidate)
    raise FFmpegError(
        f"Could not find {name}. Install ffmpeg and put it on PATH, or place it in "
        f"{_FALLBACK_DIR}."
    )


def ffmpeg_path() -> str:
    return _resolve("ffmpeg")


def ffprobe_path() -> str:
    return _resolve("ffprobe")


def cache_dir() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "SubtitleStudio"
    base.mkdir(parents=True, exist_ok=True)
    return base


# ---------------------------------------------------------------------------
# Probing
# ---------------------------------------------------------------------------


@dataclass
class MediaInfo:
    path: str
    width: int
    height: int
    fps: float
    duration: float
    has_audio: bool
    audio_codec: str = ""
    video_codec: str = ""

    @property
    def is_vertical(self) -> bool:
        return self.height > self.width


def _parse_rate(value: str) -> float:
    if not value or value == "0/0":
        return 0.0
    if "/" in value:
        num, den = value.split("/", 1)
        return float(num) / float(den) if float(den) else 0.0
    return float(value)


def probe(path: str | Path) -> MediaInfo:
    """Read real dimensions/fps/duration. Never guess these — §2."""
    path = str(Path(path).resolve())
    out = subprocess.run(
        [
            ffprobe_path(), "-v", "error",
            "-print_format", "json",
            "-show_format", "-show_streams",
            path,
        ],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        creationflags=_NO_WINDOW,
    )
    if out.returncode != 0:
        raise FFmpegError(f"ffprobe failed on {path}:\n{out.stderr.strip()}")

    data = json.loads(out.stdout)
    video = next((s for s in data["streams"] if s.get("codec_type") == "video"), None)
    audio = next((s for s in data["streams"] if s.get("codec_type") == "audio"), None)
    if video is None:
        raise FFmpegError(f"No video stream in {path}")

    duration = float(data.get("format", {}).get("duration") or 0.0)
    if not duration:
        duration = float(video.get("duration") or 0.0)

    # Rotation metadata: a phone video is stored landscape with a 90° flag.
    width, height = int(video["width"]), int(video["height"])
    rotation = 0
    for sd in video.get("side_data_list", []) or []:
        if "rotation" in sd:
            rotation = abs(int(sd["rotation"])) % 180
    if rotation == 90:
        width, height = height, width

    fps = _parse_rate(video.get("avg_frame_rate", "")) or _parse_rate(
        video.get("r_frame_rate", "")
    )

    return MediaInfo(
        path=path,
        width=width,
        height=height,
        fps=round(fps, 4) or 30.0,
        duration=duration,
        has_audio=audio is not None,
        audio_codec=(audio or {}).get("codec_name", ""),
        video_codec=video.get("codec_name", ""),
    )


# ---------------------------------------------------------------------------
# Running with progress
# ---------------------------------------------------------------------------

_OUT_TIME = re.compile(r"out_time_ms=(\d+)")


class Cancelled(RuntimeError):
    """Raised when a caller's should_cancel() returned True mid-run."""


def run_with_progress(
    args: list[str],
    total_seconds: float = 0.0,
    on_progress: Callable[[float], None] | None = None,
    cwd: str | Path | None = None,
    should_cancel: Callable[[], bool] | None = None,
) -> None:
    """Run ffmpeg, parsing `-progress pipe:1` rather than regexing stderr (§6.1).

    `should_cancel` is polled on every progress line so the UI can offer a Cancel
    that actually kills the encode instead of just hiding the dialog.
    """
    proc = subprocess.Popen(
        args,
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace",
        creationflags=_NO_WINDOW,
    )
    assert proc.stdout is not None
    cancelled = False
    for line in proc.stdout:
        if should_cancel and should_cancel():
            cancelled = True
            proc.kill()
            break
        m = _OUT_TIME.search(line)
        if m and on_progress and total_seconds > 0:
            secs = int(m.group(1)) / 1_000_000
            on_progress(max(0.0, min(1.0, secs / total_seconds)))
    proc.wait()
    stderr = proc.stderr.read() if proc.stderr else ""
    if cancelled:
        raise Cancelled("cancelled")
    if proc.returncode != 0:
        raise FFmpegError(
            f"ffmpeg exited {proc.returncode}\n"
            f"command: {' '.join(args)}\n\n{stderr.strip()[-4000:]}"
        )
    if on_progress:
        on_progress(1.0)


# ---------------------------------------------------------------------------
# Audio extraction (§3.1)
# ---------------------------------------------------------------------------


def extract_audio(src: str | Path, force: bool = False) -> Path:
    """16 kHz mono PCM, cached by source path+mtime+size so re-runs are free."""
    src = Path(src).resolve()
    stat = src.stat()
    key = f"{src}|{stat.st_mtime_ns}|{stat.st_size}"
    import hashlib

    digest = hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]
    out_dir = cache_dir() / "cache" / digest
    out_dir.mkdir(parents=True, exist_ok=True)
    wav = out_dir / "audio.wav"

    if wav.is_file() and wav.stat().st_size > 1024 and not force:
        return wav

    run_with_progress([
        ffmpeg_path(), "-y", "-i", str(src),
        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
        "-nostats", "-loglevel", "error",
        str(wav),
    ])
    if not wav.is_file() or wav.stat().st_size < 1024:
        raise FFmpegError(f"No audio could be extracted from {src}")
    return wav


# ---------------------------------------------------------------------------
# Burn-in (§6.1 / §6.2)
# ---------------------------------------------------------------------------


def _fonts_dir(explicit: str | Path | None) -> str | None:
    """Default to the fonts we ship, so themes render the same on any machine."""
    if explicit is not None:
        return str(explicit)
    from . import fonts

    return fonts.fonts_dir()


def _render_scratch() -> Path:
    """A short, ASCII, space-free directory to run the filtergraph from (§6.2)."""
    import uuid

    d = cache_dir() / "render" / uuid.uuid4().hex[:8]
    d.mkdir(parents=True, exist_ok=True)
    return d


def subtitle_filter(
    scratch: str | Path, ass_text: str, fonts_dir: str | Path | None = None
) -> str:
    """Stage the .ass (and our fonts) in `scratch` and return the -vf argument.

    Everything that burns subtitles goes through here, so the §6.2 bare-filename
    rule and the bundled fonts can never be forgotten in one code path and not
    another. Run ffmpeg with cwd=scratch.
    """
    scratch = Path(scratch)
    (scratch / "subs.ass").write_text(ass_text, encoding="utf-8")
    vf = "ass=subs.ass"
    resolved = _fonts_dir(fonts_dir)
    if resolved and Path(resolved).is_dir():
        shutil.copytree(resolved, scratch / "fonts", dirs_exist_ok=True)
        vf += ":fontsdir=fonts"
    return vf


def burn_subtitles(
    src: str | Path,
    ass_text: str,
    dest: str | Path,
    info: MediaInfo | None = None,
    encoder: str = "h264_nvenc",
    cq: int = 19,
    preset: str = "p5",
    fonts_dir: str | Path | None = None,
    on_progress: Callable[[float], None] | None = None,
    extra_video_args: Iterable[str] = (),
    should_cancel: Callable[[], bool] | None = None,
    sfx_wav: str | Path | None = None,
) -> Path:
    """Burn `ass_text` into `src`, writing `dest`. Returns the output path.

    The .ass is written into a scratch dir and referenced by BARE FILENAME with the
    subprocess cwd set there. Do not "fix" this by escaping an absolute path — on
    Windows the drive-letter colon is a filtergraph argument separator and there is
    no escaping that survives every ffmpeg build. See §6.2.
    """
    src = Path(src).resolve()
    dest = Path(dest).resolve()
    dest.parent.mkdir(parents=True, exist_ok=True)
    info = info or probe(src)

    scratch = _render_scratch()
    vf = subtitle_filter(scratch, ass_text, fonts_dir)
    args = [ffmpeg_path(), "-y", "-i", str(src)]

    mixing_sfx = bool(sfx_wav and Path(sfx_wav).is_file() and info.has_audio)
    if mixing_sfx:
        args += ["-i", str(Path(sfx_wav).resolve())]

    args += ["-vf", vf]

    if encoder.endswith("_nvenc"):
        # NVENC uses -cq, NOT -crf (§6.1).
        args += [
            "-c:v", encoder, "-preset", preset, "-tune", "hq",
            "-rc", "vbr", "-cq", str(cq), "-b:v", "0",
            "-maxrate", "120M", "-bufsize", "240M",
        ]
    elif encoder == "prores_ks":
        args += ["-c:v", "prores_ks", "-profile:v", "3"]
    else:
        args += ["-c:v", encoder, "-crf", str(cq), "-preset", "medium"]

    if encoder != "prores_ks":
        args += ["-pix_fmt", "yuv420p"]      # or half of all players show green/black
    args += list(extra_video_args)
    args += ["-fps_mode", "cfr"]             # VFR screen recordings drift otherwise

    if mixing_sfx:
        # `duration=first` keeps the original audio's length authoritative — the
        # sfx track is padded a second long so a caption at the very end isn't
        # clipped, and without this the whole file would grow by that padding.
        args += [
            "-filter_complex",
            "[0:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[a0];"
            "[1:a]aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[a1];"
            "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]",
            "-map", "0:v", "-map", "[aout]",
            "-c:a", "aac", "-b:a", "192k",
        ]
    else:
        args += ["-c:a", "copy"] if info.has_audio else ["-an"]
    if dest.suffix.lower() in (".mp4", ".mov"):
        args += ["-movflags", "+faststart"]
    args += ["-progress", "pipe:1", "-nostats", "-loglevel", "error", str(dest)]

    try:
        run_with_progress(args, info.duration, on_progress, cwd=scratch,
                          should_cancel=should_cancel)
    except FFmpegError as exc:
        # Audio stream copy can fail when the container can't carry the codec.
        if "-c:a" in args and "copy" in args and "codec" in str(exc).lower():
            args[args.index("copy")] = "aac"
            run_with_progress(args, info.duration, on_progress, cwd=scratch,
                              should_cancel=should_cancel)
        else:
            raise
    finally:
        shutil.rmtree(scratch, ignore_errors=True)

    return dest


def mux_subtitles(
    src: str | Path,
    ass_text: str,
    dest: str | Path,
    on_progress: Callable[[float], None] | None = None,
    should_cancel: Callable[[], bool] | None = None,
) -> Path:
    """Add a switchable subtitle TRACK without re-encoding anything (§6).

    MKV carries ASS styling intact. MP4/MOV can only hold `mov_text`, which drops
    every colour, font and animation — so we refuse rather than hand back a file
    that silently looks nothing like the preview.
    """
    src, dest = Path(src).resolve(), Path(dest).resolve()
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.suffix.lower() != ".mkv":
        raise FFmpegError(
            "A switchable subtitle track keeps its styling only in an .mkv file.\n"
            "MP4 can only store plain text subtitles, which would lose every "
            "colour, font and animation.\n\n"
            "Choose an .mkv filename, or use Burn captions into the video."
        )

    scratch = _render_scratch()
    (scratch / "subs.ass").write_text(ass_text, encoding="utf-8")
    info = probe(src)
    try:
        run_with_progress(
            [ffmpeg_path(), "-y", "-i", str(src), "-i", "subs.ass",
             "-map", "0", "-map", "1",
             "-c", "copy", "-c:s", "ass",
             "-metadata:s:s:0", "title=Captions",
             "-progress", "pipe:1", "-nostats", "-loglevel", "error", str(dest)],
            info.duration, on_progress, cwd=scratch, should_cancel=should_cancel,
        )
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return dest


def render_theme_swatch(
    ass_text: str,
    width: int,
    height: int,
    t: float,
    video: str | Path | None = None,
    video_time: float = 0.0,
    fonts_dir: str | Path | None = None,
) -> bytes:
    """A small sample of a theme, over the user's footage if we have any.

    NOTE the two timelines: `t` is where the subtitle sits in the .ass, and
    `video_time` is where to sample the footage. When both are in play the caller
    must have built the .ass around `video_time` already (see ThumbnailWorker) —
    with `-copyts` the ass filter is evaluated at the SOURCE timestamp, not at
    zero, so a card written at t=1.15 would simply not be on screen at 0:42.
    """
    scratch = _render_scratch()
    vf = subtitle_filter(scratch, ass_text, fonts_dir)
    try:
        if video and Path(video).is_file():
            src_args = ["-ss", f"{video_time:.3f}", "-copyts",
                        "-i", str(Path(video).resolve())]
            chain = f"scale={width}:{height}:force_original_aspect_ratio=increase," \
                    f"crop={width}:{height},{vf}"
        else:
            src_args = [
                "-f", "lavfi",
                "-i", f"gradients=s={width}x{height}:c0=0x171724:c1=0x2E1B44"
                      f":speed=0.01:d=10",
                "-ss", f"{t:.3f}",          # output-side seek keeps filter time
            ]
            chain = vf
        out = subprocess.run(
            [ffmpeg_path(), "-y", *src_args, "-vf", chain, "-frames:v", "1",
             "-f", "image2pipe", "-vcodec", "png", "-loglevel", "error", "-"],
            cwd=str(scratch), capture_output=True, creationflags=_NO_WINDOW,
        )
        if out.returncode != 0:
            raise FFmpegError(out.stderr.decode("utf-8", "replace")[-1500:])
        return out.stdout
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


def render_frame(
    src: str | Path,
    ass_text: str,
    t: float,
    fonts_dir: str | Path | None = None,
    width: int = 0,
    height: int = 0,
) -> bytes:
    """One PNG frame with subtitles burned in, for the scrub preview (§5).

    Pass width/height to render at preview size — scaling BEFORE the ass filter
    is both faster and correct, because every theme size is a percentage of the
    frame height, so the proportions are identical at any resolution. Build the
    .ass at the same resolution you pass here.
    """
    scratch = _render_scratch()
    vf = subtitle_filter(scratch, ass_text, fonts_dir)
    if width and height:
        vf = f"scale={width}:{height},{vf}"
    try:
        out = subprocess.run(
            [
                ffmpeg_path(), "-y",
                # -ss before -i is the fast seek, but on its own it REBASES the
                # output timestamps to zero — so the `ass` filter renders the
                # subtitle state at t=0 no matter where you seeked, and every
                # preview frame silently shows the wrong words. -copyts keeps the
                # original timeline so the filter sees the real time.
                "-ss", f"{t:.3f}", "-copyts",
                "-i", str(Path(src).resolve()),
                "-vf", vf, "-frames:v", "1",
                "-f", "image2pipe", "-vcodec", "png",
                "-loglevel", "error", "-",
            ],
            cwd=str(scratch), capture_output=True, creationflags=_NO_WINDOW,
        )
        if out.returncode != 0:
            raise FFmpegError(out.stderr.decode("utf-8", "replace")[-2000:])
        return out.stdout
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
