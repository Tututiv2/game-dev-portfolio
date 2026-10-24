"""Draw captions with Qt, for live playback.

Why this exists: the export is rendered by libass, and the old preview rendered
single frames through libass too — correct, but ~180 ms each, which is a
slideshow, not playback. To show captions over video playing at real speed with
sound, they have to be drawn by the same painter as everything else.

So this mirrors the ASS output rather than replacing it. Word states come from
`reveal.word_state()` — the exact function the ASS writers use — so the two can
never disagree about *which* words show. What can differ slightly is typographic
detail (hinting, the exact spread of an outline). `tools/compare_overlay.py`
measures that difference, and the Exact button in the app renders the current
frame through libass when you want to be certain.
"""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import (
    QBrush, QColor, QFont, QFontMetricsF, QLinearGradient, QPainter, QPainterPath,
    QPainterPathStroker, QPen,
)

from ..core.model import Caption
from ..core.reveal import ACTIVE, BASE, HIDDEN, caption_at, word_state
from ..core.themes import Animation, Theme


@dataclass
class _Piece:
    text: str
    state: str
    index: int
    advance: float          # laid-out width, already multiplied by `scale`
    scale: float = 1.0
    raw_advance: float = 0.0   # unscaled — the painter is scaled when we draw


def _pop_scale(piece: "_Piece", theme: Theme, anim: Animation, cap: Caption,
               t: float) -> float:
    """How much the active word is enlarged right now (1.0 = not at all).

    Uses `Animation.scale_at`, the same curve the ASS tags describe, so the
    preview and the exported file pop identically.
    """
    if piece.state != ACTIVE or theme.border_style == 3:
        return 1.0
    word = cap.words[piece.index]
    elapsed = (t - word.start) * 1000.0
    if elapsed >= anim.pop_ms:
        return 1.0
    return anim.scale_at(elapsed)


def _qcolour(hex_rgb: str, alpha: float = 1.0) -> QColor:
    c = QColor(hex_rgb)
    c.setAlphaF(max(0.0, min(1.0, alpha)))
    return c


def _card_alpha(cap: Caption, anim: Animation, t: float) -> float:
    """Card-level fade at the edges — the Qt equivalent of ASS `\\fad`."""
    a = 1.0
    if anim.card_fade_in_ms:
        into = (t - cap.start) * 1000.0
        if into < anim.card_fade_in_ms:
            a = min(a, max(0.0, into) / anim.card_fade_in_ms)
    if anim.card_fade_out_ms:
        left = (cap.end - t) * 1000.0
        if left < anim.card_fade_out_ms:
            a = min(a, max(0.0, left) / anim.card_fade_out_ms)
    return a


# Rebuilding a QFont, its metrics and a glyph outline for every word on every
# frame is what made playback stutter: a card sits on screen for ~40 frames and
# the shapes are identical in all of them. These caches are keyed by everything
# that can change the result, so they're safe to reuse across frames.


@lru_cache(maxsize=64)
def _font_for(family: str, pixel_size: int, bold: bool, italic: bool,
              spacing: float) -> QFont:
    font = QFont(family)
    font.setPixelSize(max(6, pixel_size))
    font.setBold(bold)
    font.setItalic(italic)
    if spacing:
        font.setLetterSpacing(QFont.AbsoluteSpacing, spacing)
    return font


@lru_cache(maxsize=64)
def _metrics_for(key: tuple) -> QFontMetricsF:
    return QFontMetricsF(_font_for(*key))


@lru_cache(maxsize=1024)
def _glyph_path(text: str, key: tuple) -> QPainterPath:
    path = QPainterPath()
    path.addText(0, 0, _font_for(*key), text)
    return path


@lru_cache(maxsize=1024)
def _outline_path(text: str, key: tuple, width_x10: int) -> QPainterPath:
    """The outline as a fillable shape.

    Filling a pre-stroked path is markedly cheaper than asking the painter to
    stroke a text outline with a wide round pen on every frame.
    """
    stroker = QPainterPathStroker()
    stroker.setWidth(width_x10 / 10.0 * 2)
    stroker.setJoinStyle(Qt.RoundJoin)
    stroker.setCapStyle(Qt.RoundCap)
    return stroker.createStroke(_glyph_path(text, key))


def font_key(theme: Theme, frame_height: float) -> tuple:
    family, _ = theme.resolved_font()
    size = max(6.0, theme.size_pct / 100.0 * frame_height)
    spacing = (theme.letter_spacing_pct / 100.0 * size
               if theme.letter_spacing_pct else 0.0)
    return (family, int(round(size)), theme.bold, theme.italic, round(spacing, 2))


def build_font(theme: Theme, frame_height: float) -> QFont:
    return _font_for(*font_key(theme, frame_height))


def _lines_of(cap: Caption, states: list[str]) -> list[list[_Piece]]:
    cuts = sorted({i for i in cap.line_starts if 0 < i < len(cap.words)})
    bounds = [0, *cuts, len(cap.words)]
    lines: list[list[_Piece]] = []
    for a, b in zip(bounds, bounds[1:]):
        if b > a:
            lines.append([_Piece(cap.words[i].text, states[i], i, 0.0)
                          for i in range(a, b)])
    return lines


def draw_caption(
    painter: QPainter,
    frame: QRectF,
    cap: Caption,
    theme: Theme,
    mode: str,
    t: float,
    anim: Animation | None = None,
) -> None:
    """Paint one caption card inside `frame` as it looks at time `t`."""
    anim = anim or Animation()
    states = word_state(cap, t, mode)
    if not states or all(s == HIDDEN for s in states):
        if mode not in ("ONE_WORD",):
            return

    key = font_key(theme, frame.height())
    font = _font_for(*key)
    metrics = _metrics_for(key)
    painter.save()
    painter.setFont(font)
    painter.setRenderHint(QPainter.Antialiasing, True)
    painter.setRenderHint(QPainter.TextAntialiasing, True)

    space = max(metrics.horizontalAdvance(" "), metrics.height() * 0.18)
    lines = _lines_of(cap, states)
    for line in lines:
        for piece in line:
            piece.text = theme.transform(piece.text)
            piece.scale = _pop_scale(piece, theme, anim, cap, t)
            piece.raw_advance = metrics.horizontalAdvance(piece.text)
            # Reserve the SCALED width. libass reflows around a scaled word, so if
            # we laid out at unscaled width the popped word would grow straight
            # into the one after it and read as a missing space.
            piece.advance = piece.raw_advance * piece.scale

    line_height = metrics.height() * 1.08
    block_height = line_height * len(lines)

    margin_v = theme.margin_v_pct / 100.0 * frame.height()
    margin_h = theme.margin_h_pct / 100.0 * frame.width()
    position = theme.position
    if position in ("bottom", "bottom_left"):
        top = frame.bottom() - margin_v - block_height
    elif position == "top":
        top = frame.top() + margin_v
    else:
        top = frame.top() + (frame.height() - block_height) / 2

    outline_w = theme.outline_pct / 100.0 * frame.height()
    shadow_off = theme.shadow_pct / 100.0 * frame.height()

    for row, line in enumerate(lines):
        width = sum(p.advance for p in line) + space * max(0, len(line) - 1)
        if position == "bottom_left":
            x = frame.left() + margin_h
        else:
            x = frame.left() + (frame.width() - width) / 2
        baseline = top + row * line_height + metrics.ascent()

        for piece in line:
            if piece.state != HIDDEN:
                _draw_word(painter, piece, theme, anim, cap, t,
                           QPointF(x, baseline), metrics, key,
                           outline_w, shadow_off, frame)
            x += piece.advance + space

    painter.restore()


def _draw_word(painter: QPainter, piece: _Piece, theme: Theme, anim: Animation,
               cap: Caption, t: float, origin: QPointF, metrics: QFontMetricsF,
               key: tuple, outline_w: float, shadow_off: float,
               frame: QRectF) -> None:
    active = piece.state == ACTIVE
    scale = piece.scale
    elapsed = (t - cap.words[piece.index].start) * 1000.0

    alpha = 1.0
    if not active and theme.inactive_opacity < 1.0:
        alpha = theme.inactive_opacity
    if active:
        alpha *= anim.word_alpha_at(elapsed)
    # Card-level fade at the very start and end, mirroring \fad in the ASS.
    alpha *= _card_alpha(cap, anim, t)
    if alpha <= 0.01:
        return

    if active and theme.highlight in ("colour", "underline"):
        fill = _qcolour(theme.active_colour_at(piece.index), alpha)
    else:
        fill = _qcolour(theme.base_colour, alpha)

    path = _glyph_path(piece.text, key)

    painter.save()
    painter.translate(origin)
    tilt = anim.tilt_at(elapsed) if active else 0.0
    if tilt:
        # \frz rotates about the text origin too, so match that.
        painter.rotate(-tilt)
    if scale != 1.0:
        # Scale from the baseline origin, which is where \fscx grows from, so the
        # reserved width above matches what actually gets drawn.
        painter.scale(scale, scale)

    # Opaque plate (BorderStyle 3), drawn per word so it hugs the text.
    if theme.border_style == 3:
        pad_x = metrics.horizontalAdvance(" ") * 0.55
        pad_y = metrics.height() * 0.12
        plate = QRectF(-pad_x, -metrics.ascent() - pad_y,
                       piece.raw_advance + pad_x * 2,
                       metrics.height() + pad_y * 2)
        painter.fillRect(plate, _qcolour(theme.outline_colour,
                                         theme.outline_opacity))
    elif outline_w > 0.05:
        # Fill a pre-stroked, cached outline shape rather than stroking the text
        # with a wide pen every frame — the stroke was most of the paint cost.
        stroke = _outline_path(piece.text, key, int(round(outline_w * 10)))
        painter.setPen(Qt.NoPen)
        if theme.blur:
            glow = _outline_path(piece.text, key, int(round(outline_w * 26)))
            painter.setBrush(_qcolour(theme.outline_colour, 0.30 * alpha))
            painter.drawPath(glow)
        if shadow_off > 0.05:
            painter.save()
            painter.translate(shadow_off, shadow_off)
            painter.setBrush(_qcolour(theme.back_colour, 0.75 * alpha))
            painter.drawPath(stroke)
            painter.drawPath(path)
            painter.restore()
        painter.setBrush(_qcolour(theme.outline_colour, alpha))
        painter.drawPath(stroke)

    if theme.gradient_colour:
        grad = QLinearGradient(0, -metrics.ascent(), 0, metrics.descent())
        grad.setColorAt(0.0, _qcolour(theme.gradient_colour, alpha))
        grad.setColorAt(1.0, fill)
        brush = QBrush(grad)
    else:
        brush = QBrush(fill)

    painter.setPen(Qt.NoPen)
    painter.setBrush(brush)
    painter.drawPath(path)

    if active and theme.highlight == "underline":
        bar = QRectF(0, metrics.descent() * 0.45, piece.raw_advance,
                     max(2.0, metrics.height() * 0.07))
        painter.fillRect(bar, fill)

    painter.restore()


def draw_at(painter: QPainter, frame: QRectF, captions: list[Caption],
            theme: Theme, mode: str, t: float,
            anim: Animation | None = None) -> bool:
    """Draw whichever caption belongs at `t`. Returns True if one was drawn."""
    cap = caption_at(captions, t)
    if cap is None:
        return False
    draw_caption(painter, frame, cap, theme, mode, t, anim)
    return True


def draw_layers(painter: QPainter, frame: QRectF, renders, t: float) -> int:
    """Draw every layer, back to front. `renders` is a list of LayerRender.

    Same order as the ASS output, so what you watch is what gets written.
    """
    drawn = 0
    for layer in renders:
        if not layer.captions:
            continue
        if draw_at(painter, frame, layer.captions, layer.theme, layer.mode, t,
                   layer.anim):
            drawn += 1
    return drawn
