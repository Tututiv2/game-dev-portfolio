"""Sidecar subtitle output — SRT and WebVTT.

Sidecar export is a stream copy, so it finishes in about a second (§6). Neither
format carries the per-word animation — that's what .ass is for.
"""

from __future__ import annotations

from .model import Caption


def _stamp(t: float, sep: str = ",") -> str:
    t = max(0.0, t)
    ms = int(round(t * 1000))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1_000)
    return f"{h:02d}:{m:02d}:{s:02d}{sep}{ms:03d}"


def _body(cap: Caption) -> str:
    return "\n".join(" ".join(w.text for w in line) for line in cap.lines())


def build_srt(captions: list[Caption]) -> str:
    out = []
    for i, cap in enumerate(captions, 1):
        out.append(f"{i}\n{_stamp(cap.start)} --> {_stamp(cap.end)}\n{_body(cap)}\n")
    return "\n".join(out)


def build_vtt(captions: list[Caption]) -> str:
    out = ["WEBVTT", ""]
    for cap in captions:
        out.append(f"{_stamp(cap.start, '.')} --> {_stamp(cap.end, '.')}")
        out.append(_body(cap))
        out.append("")
    return "\n".join(out)
