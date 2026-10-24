"""Timeline assets and caption sound — waveform peaks, thumbnail strips, SFX.

All of it cached under %LOCALAPPDATA%\\SubtitleStudio, keyed by the source file,
so scrubbing a video you opened yesterday is instant.

No Qt in this file.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from . import ffmpeg

SFX_DIR = Path(__file__).resolve().parents[2] / "assets" / "sfx"
AUDIO_SUFFIXES = {".wav", ".mp3", ".ogg", ".flac", ".m4a", ".aac"}


def _key(path: str | Path, extra: str = "") -> str:
    p = Path(path)
    st = p.stat()
    raw = f"{p.resolve()}|{st.st_mtime_ns}|{st.st_size}|{extra}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]


def _cache(path: str | Path, extra: str = "") -> Path:
    d = ffmpeg.cache_dir() / "media" / _key(path, extra)
    d.mkdir(parents=True, exist_ok=True)
    return d


# ---------------------------------------------------------------------------
# Waveform
# ---------------------------------------------------------------------------


def waveform_peaks(video: str | Path, buckets: int = 2000) -> np.ndarray:
    """Return `buckets` peak amplitudes in 0..1 for the whole audio track.

    Decoded at 8 kHz mono, which is plenty for a drawn waveform and keeps a
    two-hour podcast down to a few megabytes of samples.
    """
    cache = _cache(video, f"wave{buckets}") / "peaks.npy"
    if cache.is_file():
        try:
            return np.load(cache)
        except Exception:                                  # noqa: BLE001
            pass

    out = subprocess.run(
        [ffmpeg.ffmpeg_path(), "-v", "error", "-i", str(Path(video).resolve()),
         "-vn", "-ac", "1", "-ar", "8000", "-f", "s16le", "-"],
        capture_output=True, creationflags=ffmpeg._NO_WINDOW,
    )
    if out.returncode != 0 or not out.stdout:
        return np.zeros(buckets, dtype=np.float32)

    samples = np.frombuffer(out.stdout, dtype="<i2").astype(np.float32) / 32768.0
    if samples.size == 0:
        return np.zeros(buckets, dtype=np.float32)

    usable = (samples.size // buckets) * buckets
    if usable == 0:
        peaks = np.zeros(buckets, dtype=np.float32)
        peaks[: samples.size] = np.abs(samples)
    else:
        peaks = np.abs(samples[:usable].reshape(buckets, -1)).max(axis=1)

    ceiling = float(peaks.max()) or 1.0
    peaks = (peaks / ceiling).astype(np.float32)
    np.save(cache, peaks)
    return peaks


# ---------------------------------------------------------------------------
# Thumbnail strip
# ---------------------------------------------------------------------------


@dataclass
class Filmstrip:
    files: list[Path]
    interval: float
    height: int


def filmstrip(video: str | Path, count: int = 60, height: int = 72) -> Filmstrip:
    """Evenly spaced frames across the whole video, for the timeline's video track."""
    info = ffmpeg.probe(video)
    count = max(4, min(count, 200))
    interval = max(0.2, info.duration / count)

    cache = _cache(video, f"strip{count}x{height}")
    manifest = cache / "strip.json"
    if manifest.is_file():
        try:
            data = json.loads(manifest.read_text())
            files = [cache / n for n in data["files"]]
            if files and all(f.is_file() for f in files):
                return Filmstrip(files, data["interval"], height)
        except Exception:                                  # noqa: BLE001
            pass

    subprocess.run(
        [ffmpeg.ffmpeg_path(), "-y", "-v", "error",
         "-i", str(Path(video).resolve()),
         "-vf", f"fps=1/{interval:.4f},scale=-1:{height}",
         "-q:v", "5", str(cache / "f_%04d.jpg")],
        capture_output=True, creationflags=ffmpeg._NO_WINDOW,
    )
    files = sorted(cache.glob("f_*.jpg"))
    manifest.write_text(json.dumps({"files": [f.name for f in files],
                                    "interval": interval}))
    return Filmstrip(files, interval, height)


# ---------------------------------------------------------------------------
# Caption sound effects
# ---------------------------------------------------------------------------


def available_sfx() -> dict[str, Path]:
    """Everything in assets/sfx — ours and anything the user dropped in."""
    out: dict[str, Path] = {}
    if SFX_DIR.is_dir():
        for p in sorted(SFX_DIR.iterdir()):
            if p.suffix.lower() in AUDIO_SUFFIXES:
                out[p.stem] = p
    return out


def load_sfx_mono(path: str | Path, rate: int = 48_000) -> np.ndarray:
    """Decode any sound file to mono float32 at `rate`."""
    path = Path(path)
    if path.suffix.lower() == ".wav":
        try:
            with wave.open(str(path), "rb") as f:
                if (f.getframerate() == rate and f.getsampwidth() == 2
                        and f.getnchannels() == 1):
                    raw = f.readframes(f.getnframes())
                    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768
        except Exception:                                  # noqa: BLE001
            pass

    out = subprocess.run(
        [ffmpeg.ffmpeg_path(), "-v", "error", "-i", str(path.resolve()),
         "-ac", "1", "-ar", str(rate), "-f", "s16le", "-"],
        capture_output=True, creationflags=ffmpeg._NO_WINDOW,
    )
    if out.returncode != 0 or not out.stdout:
        return np.zeros(0, dtype=np.float32)
    return np.frombuffer(out.stdout, dtype="<i2").astype(np.float32) / 32768


def build_sfx_track(
    times: list[float],
    sfx_path: str | Path,
    duration: float,
    dest: str | Path,
    gain: float = 0.8,
    rate: int = 48_000,
) -> Path:
    """Render a WAV with one hit at each timestamp.

    Placing the hits into one buffer beats asking ffmpeg for hundreds of
    `adelay` filters — a five-minute video can easily have 300 captions, and that
    many filter instances is slow to build and slower to run.
    """
    sound = load_sfx_mono(sfx_path, rate) * gain
    total = int((duration + 1.0) * rate)
    track = np.zeros(total, dtype=np.float32)

    for t in times:
        start = int(max(0.0, t) * rate)
        end = min(total, start + sound.size)
        if end > start:
            track[start:end] += sound[: end - start]

    peak = float(np.abs(track).max())
    if peak > 1.0:
        track /= peak                                   # only if we actually clipped

    dest = Path(dest)
    with wave.open(str(dest), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(rate)
        f.writeframes((np.clip(track, -1, 1) * 32767).astype("<i2").tobytes())
    return dest


def caption_sound_times(captions, mode: str, per_word: bool = False) -> list[float]:
    """When a sound should fire.

    Per-card by default. Per-word is only sane for the one-word-at-a-time look;
    on a five-word card it turns into a machine-gun.
    """
    if per_word or mode == "ONE_WORD":
        return [w.start for c in captions for w in c.words]
    return [c.words[0].start if c.words else c.start for c in captions]
