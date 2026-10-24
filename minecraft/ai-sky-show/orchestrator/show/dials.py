"""Live dials: every tunable, changeable while recording (dashboard POST /api/dials).

    from show.dials import DIALS
    gap = DIALS["turn_min_gap_s"]          # always read at USE time, never cache
    DIALS.set("cinematic_threshold", 7)    # validates type, persists, notifies listeners
"""
from __future__ import annotations

import json
import threading
from typing import Any, Callable

from .config import CONFIG_DIR

DEFAULTS: dict[str, Any] = {
    "backend": "claude_code",
    "model": "claude-opus-5-5",
    "director_model": "claude-opus-5-5",
    "effort": "medium",
    "big_effort": "high",
    "director_effort": "low",
    "turn_min_gap_s": 6.0,
    "screenshot_every": 3,
    "max_actions_per_turn": 3,
    "voice_lines_per_min": 3.0,
    "voice_global_gap_s": 1.5,
    "voice_stale_s": 8.0,
    "cinematic_min_gap_s": 120.0,
    "cinematic_threshold": 6.0,
    "director_interval_s": 20.0,
    "episode_minutes": 30.0,
    "max_turns_per_hour": 900,
    "budget_usd_stop": 40.0,
    "summary_every_turns": 40,
    "paused": False,
    "tts_engine": "auto",
    # director / episode (builder C reads these; registered here so the dashboard can set them live)
    "director_llm": True,
    "director_prethreshold": 4.0,
    "director_cut_threshold": 5.0,
    "director_cut_seconds": 8.0,
    "director_cut_min_gap_s": 25.0,
    "director_stale_s": 12.0,
    "director_return_grace_s": 6.0,
    "director_meeting_poll_s": 5.0,
    "director_look_every": 3,
    "narrate_min_gap_s": 90.0,
    "intro_pause_others": True,
    "intro_join_max_s": 50.0,
}

PATH = CONFIG_DIR / "dials.json"


class Dials:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._v: dict[str, Any] = dict(DEFAULTS)
        self._listeners: list[Callable[[str, Any], None]] = []
        try:
            saved = json.loads(PATH.read_text(encoding="utf-8"))
            for k, v in saved.items():
                if k in DEFAULTS:
                    self._v[k] = self._coerce(k, v)
        except (OSError, ValueError):
            pass

    def _coerce(self, key: str, value: Any) -> Any:
        d = DEFAULTS[key]
        if isinstance(d, bool):
            if isinstance(value, str):
                return value.strip().lower() in ("1", "true", "yes", "on")
            return bool(value)
        if isinstance(d, int):
            return int(float(value))
        if isinstance(d, float):
            return float(value)
        return str(value)

    def __getitem__(self, key: str) -> Any:
        with self._lock:
            return self._v[key]

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return self._v.get(key, default)

    def set(self, key: str, value: Any) -> Any:
        if key not in DEFAULTS:
            raise KeyError(f"unknown dial {key!r}")
        v = self._coerce(key, value)
        with self._lock:
            self._v[key] = v
            snap = dict(self._v)
        try:
            PATH.write_text(json.dumps(snap, indent=2), encoding="utf-8")
        except OSError:
            pass
        for fn in list(self._listeners):
            try:
                fn(key, v)
            except Exception:
                pass
        return v

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._v)

    def on_change(self, fn: Callable[[str, Any], None]) -> None:
        self._listeners.append(fn)


DIALS = Dials()
