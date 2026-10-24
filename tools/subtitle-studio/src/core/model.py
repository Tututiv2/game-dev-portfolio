"""Internal data model — BUILD_PROMPT.md §3.5.

Everything downstream of transcription reads ONLY these types. No Qt, no ffmpeg,
no faster-whisper imports in this file.

A project holds one or more LAYERS. Each layer is an independent caption track
with its own words, style, motion and position, so you can stack (say) a Hormozi
line along the bottom and a single big word up top. Layers render in order, back
to front.
"""

from __future__ import annotations

import json
from copy import deepcopy
from dataclasses import asdict, dataclass, field
from pathlib import Path

SCHEMA_VERSION = 2


@dataclass
class Word:
    text: str
    start: float          # seconds
    end: float
    prob: float = 1.0     # 0..1 confidence; used to tint low-confidence words

    @property
    def duration(self) -> float:
        return self.end - self.start


@dataclass
class Caption:
    """One on-screen card."""

    words: list[Word]
    start: float                              # may have lead-in padding
    end: float                                # may have tail padding
    line_starts: list[int] = field(default_factory=list)
    # ^ indices into `words` that begin a new visual line. Index 0 is implicit.
    style: str = "default"

    @property
    def text(self) -> str:
        return " ".join(w.text for w in self.words)

    def lines(self) -> list[list[Word]]:
        cuts = sorted(set(self.line_starts) - {0})
        out, prev = [], 0
        for c in cuts:
            if 0 < c <= len(self.words):
                out.append(self.words[prev:c])
                prev = c
        out.append(self.words[prev:])
        return [ln for ln in out if ln]


@dataclass
class Layer:
    """One caption track: its own words, its own look, its own place on screen."""

    name: str = "Captions"
    theme: str = "hormozi"
    reveal: str = "WORD_POP"
    words: list[Word] = field(default_factory=list)
    enabled: bool = True
    max_words: int = 5

    # None means "whatever the theme says". Set by the sliders.
    size_pct: float | None = None
    margin_v_pct: float | None = None
    position: str | None = None

    # Nudge this track earlier/later, for a second track that should trail the
    # first rather than land on the same frame.
    time_offset: float = 0.0

    # --- effects ---------------------------------------------------------
    # How hard words spring in. 0 = no movement at all, 1 = the default pop,
    # higher = exaggerated.
    pop_strength: float = 1.0
    # How quickly the pop plays out. 1 = default, 2 = twice as fast.
    pop_speed: float = 1.0
    # Degrees the word arrives rotated by, unwinding as it settles.
    tilt_deg: float = 0.0
    # Fade each word in over this many milliseconds (0 = appear instantly).
    word_fade_ms: int = 0
    # Fade the whole card in and out at its edges.
    card_fade_ms: int = 60

    # Once the user splits or merges a card by hand, `manual_cards` turns on and
    # `card_breaks` (word indices that start a card) becomes authoritative — so
    # their edit isn't silently undone by the next slider nudge. An empty
    # `card_breaks` with `manual_cards` on legitimately means "one single card".
    manual_cards: bool = False
    card_breaks: list[int] = field(default_factory=list)

    def copy_with(self, **changes) -> "Layer":
        clone = deepcopy(self)
        for key, value in changes.items():
            setattr(clone, key, value)
        return clone

    def shifted_words(self) -> list[Word]:
        if not self.time_offset:
            return self.words
        return [Word(w.text, w.start + self.time_offset,
                     w.end + self.time_offset, w.prob) for w in self.words]


@dataclass
class Project:
    """Persisted as a .subproj JSON file next to the video."""

    source: str
    layers: list[Layer] = field(default_factory=lambda: [Layer()])
    language: str = ""
    model: str = ""
    sfx: str = ""
    sfx_gain: float = 0.7
    schema: int = SCHEMA_VERSION

    # -- persistence ---------------------------------------------------------

    def save(self, path: str | Path) -> Path:
        path = Path(path)
        path.write_text(
            json.dumps(asdict(self), indent=2, ensure_ascii=False), encoding="utf-8"
        )
        return path

    @classmethod
    def load(cls, path: str | Path) -> "Project":
        raw = json.loads(Path(path).read_text(encoding="utf-8"))

        if raw.get("schema", 1) >= 2 and "layers" in raw:
            layers = [
                Layer(
                    name=lay.get("name", "Captions"),
                    theme=lay.get("theme", "hormozi"),
                    reveal=lay.get("reveal", "WORD_POP"),
                    words=[Word(**w) for w in lay.get("words", [])],
                    enabled=lay.get("enabled", True),
                    max_words=lay.get("max_words", 5),
                    size_pct=lay.get("size_pct"),
                    margin_v_pct=lay.get("margin_v_pct"),
                    position=lay.get("position"),
                    time_offset=lay.get("time_offset", 0.0),
                    pop_strength=lay.get("pop_strength", 1.0),
                    pop_speed=lay.get("pop_speed", 1.0),
                    tilt_deg=lay.get("tilt_deg", 0.0),
                    word_fade_ms=lay.get("word_fade_ms", 0),
                    card_fade_ms=lay.get("card_fade_ms", 60),
                    manual_cards=lay.get("manual_cards", False),
                    card_breaks=list(lay.get("card_breaks", [])),
                )
                for lay in raw["layers"]
            ]
        else:
            # Schema 1 stored a single flat caption list. Fold it into one layer
            # so older projects keep opening.
            words = [Word(**w) for c in raw.get("captions", [])
                     for w in c.get("words", [])]
            layers = [Layer(name="Captions", theme=raw.get("theme", "hormozi"),
                            reveal=raw.get("reveal", "WORD_POP"), words=words)]

        return cls(
            source=raw["source"],
            layers=layers or [Layer()],
            language=raw.get("language", ""),
            model=raw.get("model", ""),
            sfx=raw.get("sfx", ""),
            sfx_gain=raw.get("sfx_gain", 0.7),
            schema=SCHEMA_VERSION,
        )
