"""Word stream -> caption cards — BUILD_PROMPT.md §4.1.

This file decides whether the captions *read* well, which matters more than the
styling. Pure functions only: no Qt, no ffmpeg, no GPU, no video file. Unit-test
this hard.
"""

from __future__ import annotations

from dataclasses import dataclass

from .model import Caption, Word

SENTENCE_END = (".", "?", "!", "…", ".\"", "?\"", "!\"", ".'", "?'", "!'")
CLAUSE_END = (",", ";", ":", "—")


@dataclass
class Layout:
    max_words_per_card: int = 5
    max_chars_per_line: int = 42          # auto-picked from aspect ratio, see for_video()
    max_lines_per_card: int = 2
    max_card_duration: float = 3.0
    pause_break: float = 0.45             # gap between words that forces a new card
    break_on_sentence_end: bool = True
    min_on_screen: float = 0.7
    lead_in: float = 0.05                 # card appears slightly before the first word
    tail: float = 0.15                    # and lingers slightly after the last
    orphan_max_chars: int = 8             # a trailing word this short counts as an orphan

    @classmethod
    def for_video(cls, width: int, height: int, **overrides) -> "Layout":
        """24 chars/line for vertical 9:16, 42 for horizontal (§4.1)."""
        vertical = height > width
        base = cls(max_chars_per_line=24 if vertical else 42)
        for k, v in overrides.items():
            if v is not None and hasattr(base, k):
                setattr(base, k, v)
        return base

    @property
    def max_chars_per_card(self) -> int:
        return self.max_chars_per_line * self.max_lines_per_card


# ---------------------------------------------------------------------------
# 1. Split the word stream into groups
# ---------------------------------------------------------------------------


def _chars(words: list[Word]) -> int:
    return sum(len(w.text) for w in words) + max(0, len(words) - 1)


def _ends_sentence(word: Word) -> bool:
    return word.text.rstrip().endswith(SENTENCE_END)


def _split_into_groups(words: list[Word], cfg: Layout) -> list[list[Word]]:
    groups: list[list[Word]] = []
    current: list[Word] = []

    for word in words:
        if not current:
            current = [word]
            continue

        prev = current[-1]
        gap = word.start - prev.end
        span = word.end - current[0].start

        must_break = (
            gap > cfg.pause_break
            or (cfg.break_on_sentence_end and _ends_sentence(prev))
            or len(current) >= cfg.max_words_per_card
            or _chars(current + [word]) > cfg.max_chars_per_card
            or span > cfg.max_card_duration
        )
        if must_break:
            groups.append(current)
            current = [word]
        else:
            current.append(word)

    if current:
        groups.append(current)
    return groups


# ---------------------------------------------------------------------------
# 2. Never orphan (§4.1)
# ---------------------------------------------------------------------------


def _fix_orphans(groups: list[list[Word]], cfg: Layout) -> list[list[Word]]:
    """A card holding one short word reads as a mistake — pull a word back."""
    for i in range(1, len(groups)):
        card, prev = groups[i], groups[i - 1]
        if len(card) != 1 or len(card[0].text) > cfg.orphan_max_chars:
            continue
        if len(prev) < 3:
            continue                       # taking one would just move the problem
        donor = prev[-1]
        if _ends_sentence(donor):
            continue                       # don't drag a word across a sentence boundary
        if donor.start - prev[-2].end > cfg.pause_break:
            continue                       # nor across a real pause
        if _chars(card) + len(donor.text) + 1 > cfg.max_chars_per_card:
            continue
        groups[i] = [donor] + card
        groups[i - 1] = prev[:-1]
    return [g for g in groups if g]


# ---------------------------------------------------------------------------
# 3. Balanced line breaks (§4.1)
# ---------------------------------------------------------------------------


def balance_lines(words: list[Word], cfg: Layout) -> list[int]:
    """Return the word indices that start a new line.

    Splits at the point that minimizes the length difference between lines rather
    than at the raw character limit — ragged captions look amateur.
    """
    if len(words) < 2 or _chars(words) <= cfg.max_chars_per_line:
        return []

    n_lines = min(
        cfg.max_lines_per_card,
        max(2, -(-_chars(words) // cfg.max_chars_per_line)),   # ceil division
    )
    if n_lines < 2:
        return []

    if n_lines == 2:
        best, best_score = None, None
        for cut in range(1, len(words)):
            a, b = _chars(words[:cut]), _chars(words[cut:])
            over = max(0, a - cfg.max_chars_per_line) + max(0, b - cfg.max_chars_per_line)
            # Overflow dominates; among legal splits, prefer the most even one.
            # Nudge toward breaking after punctuation, which reads more naturally.
            punct = -1 if words[cut - 1].text.rstrip().endswith(CLAUSE_END) else 0
            score = (over * 100, abs(a - b) + punct)
            if best_score is None or score < best_score:
                best, best_score = cut, score
        return [best] if best else []

    # 3+ lines: greedy fill, good enough — max_lines_per_card is 2 by default.
    cuts, line_len = [], 0
    for i, word in enumerate(words):
        add = len(word.text) + (1 if line_len else 0)
        if line_len and line_len + add > cfg.max_chars_per_line:
            cuts.append(i)
            line_len = len(word.text)
        else:
            line_len += add
    return cuts[: n_lines - 1]


# ---------------------------------------------------------------------------
# 4. Timing: padding, minimum duration, no overlaps (§4.1)
# ---------------------------------------------------------------------------


def _apply_timing(caps: list[Caption], cfg: Layout, duration: float) -> list[Caption]:
    for cap in caps:
        cap.start = max(0.0, cap.words[0].start - cfg.lead_in)
        cap.end = cap.words[-1].end + cfg.tail
        if duration > 0:
            cap.end = min(cap.end, duration)
        if cap.end - cap.start < cfg.min_on_screen:
            cap.end = cap.start + cfg.min_on_screen
            if duration > 0 and cap.end > duration:
                cap.end = duration
                cap.start = max(0.0, cap.end - cfg.min_on_screen)

    # Resolve overlaps by agreeing a single boundary between neighbours, rather
    # than only pulling the earlier card's end back. When two cards are spoken
    # back-to-back their padding regions collide (a's +tail vs b's -lead_in) and
    # trimming only `a` still leaves them overlapping by up to lead_in seconds.
    for a, b in zip(caps, caps[1:]):
        if a.end <= b.start:
            continue
        boundary = max(a.words[-1].end, b.start)      # never cut a word short
        boundary = min(boundary, b.words[0].start)    # never start after b is spoken
        a.end = b.start = boundary
    for cap in caps:
        if cap.end <= cap.start:
            cap.end = cap.start + 0.05
    return caps


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def _finish(groups: list[list[Word]], cfg: Layout, duration: float) -> list[Caption]:
    caps = [
        Caption(
            words=g,
            start=g[0].start,
            end=g[-1].end,
            line_starts=balance_lines(g, cfg),
        )
        for g in groups if g
    ]
    return _apply_timing(caps, cfg, duration)


def group_words(
    words: list[Word], cfg: Layout | None = None, duration: float = 0.0
) -> list[Caption]:
    """Turn a flat word stream into on-screen cards."""
    cfg = cfg or Layout()
    words = [w for w in words if w.text.strip()]
    if not words:
        return []
    return _finish(_fix_orphans(_split_into_groups(words, cfg), cfg), cfg, duration)


def group_at_breaks(
    words: list[Word], breaks: list[int], cfg: Layout | None = None,
    duration: float = 0.0,
) -> list[Caption]:
    """Group at exactly these word indices — no automatic rules at all.

    Used once the user has split or merged a card by hand. Their boundaries have
    to survive a re-group, otherwise the next slider nudge silently undoes the
    edit, so from then on this layer's card boundaries are theirs and the
    automatic rules stop applying.
    """
    cfg = cfg or Layout()
    words = [w for w in words if w.text.strip()]
    if not words:
        return []

    cuts = sorted({b for b in breaks if 0 < b < len(words)})
    bounds = [0, *cuts, len(words)]
    groups = [words[a:b] for a, b in zip(bounds, bounds[1:])]
    return _finish(groups, cfg, duration)


def breaks_from(captions: list[Caption]) -> list[int]:
    """The word indices that start each card — the inverse of group_at_breaks."""
    out, index = [], 0
    for cap in captions:
        if index:
            out.append(index)
        index += len(cap.words)
    return out
