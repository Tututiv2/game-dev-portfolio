"""Reveal modes — BUILD_PROMPT.md §4.3.

HOW words arrive on screen. Completely independent of the theme (§4.4): nothing
here picks a colour or a font, and nothing in themes.py decides which words are
visible.

Every mode is `(card, theme, anim, pos_tag) -> list[DialogueEvent]`, dispatched
from MODES. They deliberately are NOT one function with an if-ladder.

Event-count contract (asserted by the unit tests), for a card of N words:

    KARAOKE_HIGHLIGHT   N + lead_in? + tail?
    WORD_POP            N + tail?
    ONE_WORD            N + tail?
    TYPEWRITER          N + tail?
    WHOLE_CARD          1

`lead_in?` is 1 when the card starts measurably before its first word, `tail?` is
1 when it outlives its last word. WORD_POP/TYPEWRITER/ONE_WORD have no lead-in
event because nothing is visible yet at that point.

Pure functions. No Qt, no ffmpeg, no GPU.
"""

from __future__ import annotations

from dataclasses import dataclass

from .model import Caption
from .themes import Animation, Theme

EPS = 0.011          # a hair over one centisecond, the ASS timestamp resolution


@dataclass
class DialogueEvent:
    start: float
    end: float
    text: str
    layer: int = 0
    style: str = "Sub"


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------


def word_spans(card: Caption) -> list[tuple[float, float]]:
    """Contiguous span per word.

    Each word's event is held until the NEXT word begins, so a pause mid-card
    does not make the card blink out. The final span stops at the last word's own
    end; whatever remains of the card is covered by the tail event.
    """
    words, n = card.words, len(card.words)
    spans: list[tuple[float, float]] = []
    for i, w in enumerate(words):
        start = max(w.start, card.start)
        end = words[i + 1].start if i + 1 < n else max(w.end, start + 0.05)
        if end <= start:
            end = start + 0.05
        spans.append((start, end))
    return spans


def _assemble(card: Caption, tags: list[str], theme: Theme, prefix: str) -> str:
    """Join the words, honouring the card's line breaks.

    The separator space is emitted under an explicit `\\r` reset, never under the
    previous word's overrides. Otherwise a scaled ('popped') word widens the space
    after it too, and the rest of the line twitches.
    """
    line_starts = {i for i in card.line_starts if i > 0}
    line_break = theme.line_break_tag()
    out = [prefix]
    for i, word in enumerate(card.words):
        if i:
            out.append("{\\r}" + (line_break if i in line_starts else " "))
        out.append(tags[i] + theme.transform(word.text))
    return "".join(out)


def _lead_in(card: Caption) -> tuple[float, float] | None:
    if card.words[0].start - card.start > EPS:
        return card.start, card.words[0].start
    return None


def _tail(card: Caption) -> tuple[float, float] | None:
    last = card.words[-1].end
    if card.end - last > EPS:
        return last, card.end
    return None


# ---------------------------------------------------------------------------
# Mode A — KARAOKE_HIGHLIGHT: full sentence sits there, highlight travels
# ---------------------------------------------------------------------------


def karaoke_highlight(card, theme, anim, pos_tag) -> list[DialogueEvent]:
    n = len(card.words)
    base = [theme.inactive_tags()] * n
    events: list[DialogueEvent] = []

    if (span := _lead_in(card)) is not None:
        events.append(DialogueEvent(*span, _assemble(card, base, theme, pos_tag)))

    for i, (start, end) in enumerate(word_spans(card)):
        tags = list(base)
        tags[i] = theme.active_tags(i, animate=True, anim=anim)
        events.append(DialogueEvent(start, end, _assemble(card, tags, theme, pos_tag)))

    if (span := _tail(card)) is not None:
        events.append(DialogueEvent(*span, _assemble(card, base, theme, pos_tag)))
    return events


# ---------------------------------------------------------------------------
# Mode B — WORD_POP: words appear one at a time and accumulate
# ---------------------------------------------------------------------------


def word_pop(card, theme, anim, pos_tag) -> list[DialogueEvent]:
    """Words 0..i visible, i+1.. TRANSPARENT — not omitted.

    Omitting the future words would narrow the line, and because the card is
    centre-anchored the whole caption would jitter left-and-right on every single
    word. Rendering them at \\alpha&HFF& keeps every word's position locked from
    the first frame. This is the single most important line in the file (§4.3).
    """
    n = len(card.words)
    inactive, hidden = theme.inactive_tags(), theme.hidden_tags()
    events: list[DialogueEvent] = []

    for i, (start, end) in enumerate(word_spans(card)):
        tags = [inactive] * i
        tags.append(theme.active_tags(i, animate=True, anim=anim))
        tags += [hidden] * (n - i - 1)
        events.append(DialogueEvent(start, end, _assemble(card, tags, theme, pos_tag)))

    if (span := _tail(card)) is not None:
        events.append(
            DialogueEvent(*span, _assemble(card, [inactive] * n, theme, pos_tag))
        )
    return events


# ---------------------------------------------------------------------------
# Mode C — ONE_WORD: only the current word, centred and large
# ---------------------------------------------------------------------------


def one_word(card, theme, anim, pos_tag) -> list[DialogueEvent]:
    """No transparency trick here — re-centring IS the intended effect."""
    events: list[DialogueEvent] = []
    for i, (start, end) in enumerate(word_spans(card)):
        tags = theme.active_tags(i, animate=True, anim=anim)
        events.append(
            DialogueEvent(start, end, pos_tag + tags + theme.transform(card.words[i].text))
        )

    if (span := _tail(card)) is not None:
        last = len(card.words) - 1
        tags = theme.active_tags(last, animate=False)
        events.append(
            DialogueEvent(*span, pos_tag + tags + theme.transform(card.words[last].text))
        )
    return events


# ---------------------------------------------------------------------------
# Mode D — TYPEWRITER: progressive reveal, no pop
# ---------------------------------------------------------------------------


def typewriter(card, theme, anim, pos_tag) -> list[DialogueEvent]:
    n = len(card.words)
    inactive, hidden = theme.inactive_tags(), theme.hidden_tags()
    fade = rf"\alpha&HFF&\t(0,{anim.word_fade_ms},\alpha&H00&)" if anim.word_fade_ms else ""
    events: list[DialogueEvent] = []

    for i, (start, end) in enumerate(word_spans(card)):
        newest = "{" + r"\r" + fade + "}" if fade else inactive
        tags = [inactive] * i + [newest] + [hidden] * (n - i - 1)
        events.append(DialogueEvent(start, end, _assemble(card, tags, theme, pos_tag)))

    if (span := _tail(card)) is not None:
        events.append(
            DialogueEvent(*span, _assemble(card, [inactive] * n, theme, pos_tag))
        )
    return events


# ---------------------------------------------------------------------------
# Mode E — WHOLE_CARD: no per-word anything
# ---------------------------------------------------------------------------


def whole_card(card, theme, anim, pos_tag) -> list[DialogueEvent]:
    """Standard broadcast captioning, and the fallback whenever word timings are
    missing or the user has hand-edited the text past the point where they line
    up. Always keep this path working (§4.3)."""
    n = len(card.words)
    prefix = pos_tag + ("{" + anim.fade_tag + "}" if anim.fade_tag else "")
    text = _assemble(card, [theme.inactive_tags()] * n, theme, prefix)
    return [DialogueEvent(card.start, card.end, text)]


# ---------------------------------------------------------------------------
# Word state at a moment in time
# ---------------------------------------------------------------------------

BASE, ACTIVE, HIDDEN = "base", "active", "hidden"


def word_state(card: Caption, t: float, mode: str = "WORD_POP") -> list[str]:
    """What each word looks like at time `t`: base, active, or hidden.

    This is the same decision the ASS writers make, expressed once so the live
    preview cannot drift away from the exported file. `hidden` still occupies
    layout space — see the transparency trick in Mode B.
    """
    n = len(card.words)
    if not n:
        return []
    if mode == "WHOLE_CARD":
        return [BASE] * n

    spans = word_spans(card)
    index: int | None = None
    for i, (start, end) in enumerate(spans):
        if start <= t < end:
            index = i
            break

    before_first = t < spans[0][0]
    after_last = t >= card.words[-1].end

    if mode == "KARAOKE_HIGHLIGHT":
        states = [BASE] * n
        if index is not None and not after_last:
            states[index] = ACTIVE
        return states

    if mode == "ONE_WORD":
        shown = 0 if before_first else (index if index is not None else n - 1)
        return [ACTIVE if i == shown else HIDDEN for i in range(n)]

    if mode == "ONE_PHRASE":
        shown = 0 if before_first else (index if index is not None else n - 1)
        return [ACTIVE if i == shown else
                BASE if abs(i - shown) <= 1 else HIDDEN
                for i in range(n)]

    # WORD_POP and TYPEWRITER accumulate.
    if before_first:
        return [HIDDEN] * n
    if after_last or index is None:
        return [BASE] * n
    return [BASE if i < index else ACTIVE if i == index else HIDDEN
            for i in range(n)]


def caption_at(captions: list[Caption], t: float) -> Caption | None:
    for cap in captions:
        if cap.start <= t <= cap.end:
            return cap
    return None


# ---------------------------------------------------------------------------
# Mode C sub-variant — ONE_PHRASE
# ---------------------------------------------------------------------------


def one_phrase(card, theme, anim, pos_tag) -> list[DialogueEvent]:
    """2-3 words at a time, swapping as a group (§4.3 Mode C sub-variant).

    Calmer than ONE_WORD but still punchy. The whole group is on screen for the
    span of its words, and the word being spoken is the active one — so it reads
    as a phrase with a travelling highlight rather than a flashing single word.
    """
    n = len(card.words)
    inactive, hidden = theme.inactive_tags(), theme.hidden_tags()
    events: list[DialogueEvent] = []

    for i, (start, end) in enumerate(word_spans(card)):
        tags = []
        for j in range(n):
            if j == i:
                tags.append(theme.active_tags(j, animate=True, anim=anim))
            else:
                tags.append(inactive if abs(j - i) <= 1 else hidden)
        events.append(DialogueEvent(start, end, _assemble(card, tags, theme, pos_tag)))

    if (span := _tail(card)) is not None:
        last = n - 1
        tags = [inactive if j >= last - 1 else hidden for j in range(n)]
        events.append(DialogueEvent(*span, _assemble(card, tags, theme, pos_tag)))
    return events


MODES = {
    "KARAOKE_HIGHLIGHT": karaoke_highlight,
    "WORD_POP": word_pop,
    "ONE_WORD": one_word,
    "ONE_PHRASE": one_phrase,
    "TYPEWRITER": typewriter,
    "WHOLE_CARD": whole_card,
}

MODE_LABELS = {
    "KARAOKE_HIGHLIGHT": "Karaoke highlight — full line, highlight travels",
    "WORD_POP": "Word pop — words appear one at a time",
    "ONE_WORD": "One word — single word, centred and big",
    "ONE_PHRASE": "One phrase — a few words at a time, swapping as a group",
    "TYPEWRITER": "Typewriter — progressive, no pop",
    "WHOLE_CARD": "Whole card — plain captions, no animation",
}


def render_card(
    card: Caption,
    theme: Theme,
    anim: Animation,
    pos_tag: str,
    mode: str = "WORD_POP",
) -> list[DialogueEvent]:
    if not card.words:
        return []
    try:
        fn = MODES[mode]
    except KeyError:
        raise ValueError(
            f"Unknown reveal mode {mode!r}. Known: {', '.join(MODES)}"
        ) from None
    return fn(card, theme, anim, pos_tag)
