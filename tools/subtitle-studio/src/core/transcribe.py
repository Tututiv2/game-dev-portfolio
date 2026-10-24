"""faster-whisper wrapper — BUILD_PROMPT.md §3 and §9.

Two jobs: get the CUDA DLLs onto the search path before CTranslate2 loads, and
turn Whisper's output into the clean Word list the rest of the app expects.

No Qt in this file (the QThread worker in ui/ wraps these functions).
"""

from __future__ import annotations

import os
import sys
import sysconfig
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

from .model import Word

MODELS = [
    "tiny", "base", "small", "medium",
    "large-v3", "distil-large-v3",
]

# §3.4 — a segment Whisper isn't sure contains speech is usually a hallucination.
NO_SPEECH_MAX = 0.6
MIN_WORD_DURATION = 0.06

# Batched inference returns a handful of very long segments instead of many short
# ones, so "drop the whole segment" is a much blunter instrument there — binning a
# 30 s segment can silently delete a quarter of a short video. Only apply the
# repetition heuristic to segments short enough for it to be a safe call.
MAX_DROPPABLE_SEGMENT = 20.0


# ---------------------------------------------------------------------------
# §9 — the dependency landmine.  MUST run before ctranslate2 is imported.
# ---------------------------------------------------------------------------


def ensure_cuda_dlls() -> list[str]:
    """Put the pip-installed cuBLAS/cuDNN DLL folders on the DLL search path.

    Without this you get `Could not locate cudnn_ops64_9.dll`, or a hard process
    abort with no Python traceback at all, because pip installs those wheels but
    Windows never looks inside them. Layout differs between wheel versions
    (`nvidia/cudnn/bin` vs `nvidia/cudnn/bin/x64`), so glob rather than hardcode.
    """
    if sys.platform != "win32":
        return []

    roots: list[Path] = []
    for getter in (lambda: [sysconfig.get_paths()["purelib"]],
                   lambda: __import__("site").getsitepackages()):
        try:
            roots += [Path(p) for p in getter()]
        except Exception:
            pass

    added: list[str] = []
    seen: set[str] = set()
    for root in roots:
        nvidia = root / "nvidia"
        if not nvidia.is_dir():
            continue
        for dll in nvidia.glob("*/bin/**/*.dll"):
            folder = str(dll.parent)
            if folder in seen:
                continue
            seen.add(folder)
            try:
                os.add_dll_directory(folder)        # Python 3.8+ on Windows
            except OSError:
                continue
            os.environ["PATH"] = folder + os.pathsep + os.environ.get("PATH", "")
            added.append(folder)
    return added


CUDA_DLL_DIRS = ensure_cuda_dlls()


def cuda_available() -> bool:
    try:
        import ctranslate2

        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


def cuda_runtime_ok() -> tuple[bool, list[str]]:
    """Verify every CUDA DLL CTranslate2 imports can actually be loaded.

    This preflight exists because the failure it catches is NOT catchable any
    other way: with the wrong cuDNN major, CTranslate2 prints
    `Could not locate cudnn_ops64_9.dll` and aborts the process from native code
    — no Python exception, no traceback, nothing for try/except to see. So we
    read the DLL names straight out of the ctranslate2 binary's import table and
    load them ourselves first, while we still control the process.

    Returns (ok, missing_dll_names).
    """
    if sys.platform != "win32":
        return True, []

    import ctypes
    import re

    try:
        import ctranslate2

        binary = Path(ctranslate2.__file__).parent / "ctranslate2.dll"
        if not binary.is_file():
            return True, []                        # CPU-only wheel; nothing to check
        blob = binary.read_bytes()
    except Exception:
        return True, []                            # can't tell — let it try

    names = {
        n.decode("ascii")
        for n in re.findall(rb"(?:cudnn|cublas|cudart)[A-Za-z_]*64_\d+\.dll", blob)
    }
    missing = []
    for name in sorted(names):
        try:
            ctypes.WinDLL(name)
        except OSError:
            missing.append(name)
    return not missing, missing


# ---------------------------------------------------------------------------
# §3.4 — Whisper failure modes
# ---------------------------------------------------------------------------


def is_repetitive(text: str, max_reps: int = 3) -> bool:
    """True if the text is one n-gram looped — the classic silence hallucination."""
    tokens = text.split()
    if len(tokens) < 2 * (max_reps + 1) // 2:
        return False
    for n in range(1, min(7, len(tokens) // 2 + 1)):
        for start in range(0, len(tokens) - n):
            gram = tokens[start:start + n]
            reps, j = 1, start + n
            while tokens[j:j + n] == gram:
                reps += 1
                j += n
            if reps > max_reps:
                return True
    return False


def _clean_words(raw_words: Iterable, seg_start: float, seg_end: float) -> list[Word]:
    """Clamp into the segment, enforce end > start, never overlap (§3.4)."""
    out: list[Word] = []
    cursor = seg_start
    for rw in raw_words or []:
        text = (getattr(rw, "word", "") or "").strip()
        if not text:
            continue
        start = max(seg_start, min(float(getattr(rw, "start", cursor)), seg_end))
        end = max(seg_start, min(float(getattr(rw, "end", start)), seg_end))
        start = max(start, cursor)
        if end <= start:
            end = start + MIN_WORD_DURATION
        out.append(Word(text=text, start=start, end=end,
                        prob=float(getattr(rw, "probability", 1.0) or 1.0)))
        cursor = end
    return out


# ---------------------------------------------------------------------------
# Transcription
# ---------------------------------------------------------------------------


@dataclass
class TranscriptionResult:
    words: list[Word]
    language: str
    duration: float
    model: str
    device: str
    batched: bool = False
    dropped_segments: int = 0


# Approximate on-disk size per model, so a first-run download can show a real
# percentage instead of a spinner that looks like a hang (§3.2).
MODEL_SIZES_MB = {
    "tiny": 75, "base": 145, "small": 490, "medium": 1530,
    "large-v3": 3090, "distil-large-v3": 1510,
}


def model_cache_dir(name: str, cache_dir: str | None = None) -> Path:
    """Where huggingface_hub will put this model's snapshot."""
    root = Path(cache_dir) if cache_dir else (
        Path(os.environ.get("HF_HOME", Path.home() / ".cache" / "huggingface"))
        / "hub"
    )
    repo = (f"Systran/faster-distil-whisper-{name.replace('distil-', '')}"
            if name.startswith("distil-") else f"Systran/faster-whisper-{name}")
    return root / ("models--" + repo.replace("/", "--"))


def downloaded_mb(name: str, cache_dir: str | None = None) -> float:
    path = model_cache_dir(name, cache_dir)
    if not path.is_dir():
        return 0.0
    total = 0
    for f in path.rglob("*"):
        try:
            if f.is_file():
                total += f.stat().st_size
        except OSError:
            continue
    return total / 1e6


def is_downloaded(name: str, cache_dir: str | None = None) -> bool:
    """True if the model looks fully present, so we can skip the download UI."""
    expected = MODEL_SIZES_MB.get(name, 0)
    return downloaded_mb(name, cache_dir) >= expected * 0.92 if expected else \
        model_cache_dir(name, cache_dir).is_dir()


def load_model(name: str = "large-v3", prefer_gpu: bool = True,
               cache_dir: str | None = None):
    """Returns (model, device_label). Falls back to CPU rather than crashing (§9)."""
    from faster_whisper import WhisperModel

    if prefer_gpu and cuda_available():
        ok, missing = cuda_runtime_ok()
        if not ok:
            print(
                "[warn] CUDA is present but these DLLs will not load: "
                + ", ".join(missing)
                + "\n       This is the ctranslate2 / nvidia-cudnn-cu12 version"
                  " pairing (see requirements.txt). Using CPU instead.",
                file=sys.stderr,
            )
            prefer_gpu = False

    if prefer_gpu and cuda_available():
        try:
            return (
                WhisperModel(name, device="cuda", compute_type="float16",
                             download_root=cache_dir),
                "cuda",
            )
        except Exception as exc:                       # noqa: BLE001 — we want any failure
            print(f"[warn] CUDA load failed ({exc}); falling back to CPU.",
                  file=sys.stderr)

    return (
        WhisperModel(name, device="cpu", compute_type="int8", download_root=cache_dir),
        "cpu",
    )


def transcribe(
    audio_path: str | Path,
    model_name: str = "large-v3",
    language: str | None = None,
    task: str = "transcribe",
    prefer_gpu: bool = True,
    beam_size: int = 5,
    cache_dir: str | None = None,
    on_progress: Callable[[float], None] | None = None,
    model=None,
    device: str = "",
    batch_size: int = 8,
) -> TranscriptionResult:
    """Audio -> word stream with word-level timestamps.

    `batch_size > 0` uses BatchedInferencePipeline, which is roughly 1.5x faster
    than sequential decoding on this GPU (~21x realtime vs ~14x for large-v3) and
    produces the same words to within a fraction of a percent. Pass 0 to force
    the sequential path.
    """
    if model is None:
        model, device = load_model(model_name, prefer_gpu, cache_dir)

    common = dict(
        language=language,
        task=task,
        word_timestamps=True,          # REQUIRED — this is what enables karaoke
        condition_on_previous_text=False,   # curbs hallucination loops (§3.4)
        beam_size=beam_size,
    )

    batched = False
    if batch_size and device != "cpu":
        try:
            from faster_whisper import BatchedInferencePipeline

            pipeline = BatchedInferencePipeline(model=model)
            segments, info = pipeline.transcribe(
                str(audio_path), batch_size=batch_size, **common
            )
            batched = True
        except Exception as exc:                       # noqa: BLE001
            print(f"[warn] batched inference unavailable ({exc}); "
                  f"using sequential.", file=sys.stderr)

    if not batched:
        segments, info = model.transcribe(
            str(audio_path),
            vad_filter=True,               # Silero VAD strips silence
            vad_parameters=dict(min_silence_duration_ms=500),
            **common,
        )

    duration = float(getattr(info, "duration", 0.0) or 0.0)
    words: list[Word] = []
    dropped = 0

    # `segments` is a GENERATOR — iterating it is what actually does the work.
    for seg in segments:
        if on_progress and duration:
            on_progress(max(0.0, min(1.0, float(seg.end) / duration)))

        if float(getattr(seg, "no_speech_prob", 0.0) or 0.0) > NO_SPEECH_MAX:
            dropped += 1
            continue
        short_enough = (float(seg.end) - float(seg.start)) <= MAX_DROPPABLE_SEGMENT
        if short_enough and is_repetitive(seg.text or ""):
            dropped += 1
            continue

        cleaned = _clean_words(getattr(seg, "words", None),
                               float(seg.start), float(seg.end))
        if cleaned and words and cleaned[0].start < words[-1].end:
            shift = words[-1].end - cleaned[0].start
            for w in cleaned:
                w.start += shift
                w.end += shift
        words.extend(cleaned)

    if on_progress:
        on_progress(1.0)

    return TranscriptionResult(
        words=words,
        language=getattr(info, "language", "") or "",
        duration=duration,
        model=model_name,
        device=device,
        batched=batched,
        dropped_segments=dropped,
    )
