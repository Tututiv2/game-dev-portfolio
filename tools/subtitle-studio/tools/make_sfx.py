#!/usr/bin/env python
"""Synthesise the built-in caption sounds into assets/sfx/.

Short, dry, and quiet by design: this plays once per caption, so anything with a
tail or a strong pitch becomes unbearable within ten seconds. Drop your own
.wav/.mp3 into assets/sfx/ and it shows up in the app's picker automatically.

    tools\\make_sfx.py
"""

from __future__ import annotations

import struct
import sys
import wave
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parents[1] / "assets" / "sfx"
RATE = 48_000


def write_wav(name: str, samples: np.ndarray) -> Path:
    samples = np.clip(samples, -1.0, 1.0)
    # 12 ms fade at both ends so nothing clicks on start/stop.
    fade = int(RATE * 0.012)
    if samples.size > fade * 2:
        samples[:fade] *= np.linspace(0, 1, fade)
        samples[-fade:] *= np.linspace(1, 0, fade)
    pcm = (samples * 32767).astype("<i2")

    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{name}.wav"
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(pcm.tobytes())
    return path


def t(duration: float) -> np.ndarray:
    return np.linspace(0, duration, int(RATE * duration), endpoint=False)


def pop() -> np.ndarray:
    """Round, bubbly. The default — a pitch drop reads as 'pop' more than any
    fixed tone does."""
    x = t(0.13)
    freq = 380 + 1100 * np.exp(-x * 42)
    body = np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-x * 30)
    return 0.55 * body


def tick() -> np.ndarray:
    """Dry click — filtered noise, almost no pitch. Good under fast speech."""
    x = t(0.045)
    rng = np.random.default_rng(7)
    noise = rng.standard_normal(x.size)
    smoothed = np.convolve(noise, np.ones(6) / 6, mode="same")
    return 0.35 * smoothed * np.exp(-x * 150)


def blip() -> np.ndarray:
    """Higher and cleaner — sits above a busy mix."""
    x = t(0.09)
    freq = 900 + 700 * np.exp(-x * 55)
    body = np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-x * 40)
    return 0.42 * body


def thud() -> np.ndarray:
    """Low and soft — for calm, serious edits."""
    x = t(0.18)
    freq = 130 + 190 * np.exp(-x * 26)
    body = np.sin(2 * np.pi * np.cumsum(freq) / RATE) * np.exp(-x * 18)
    return 0.6 * body


def whoosh() -> np.ndarray:
    """Air, no pitch — pairs with slide/typewriter styles."""
    x = t(0.22)
    rng = np.random.default_rng(11)
    noise = rng.standard_normal(x.size)
    kernel = np.ones(40) / 40
    band = np.convolve(noise, kernel, mode="same")
    env = np.sin(np.pi * np.clip(x / 0.22, 0, 1)) ** 2
    return 0.3 * band * env


SOUNDS = {"pop": pop, "tick": tick, "blip": blip, "thud": thud, "whoosh": whoosh}


def main() -> int:
    for name, fn in SOUNDS.items():
        path = write_wav(name, fn())
        print(f"  {name:<8} {path}  ({path.stat().st_size / 1024:.0f} KB)")
    print(f"\n{len(SOUNDS)} sounds in {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
