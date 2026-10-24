"""Turning Layers into something renderable.

One place that knows how a `Layer` (words + preferences) becomes grouped captions
and a resolved `Theme`, so the UI, the exporter and the CLI can't drift apart.

No Qt, no ffmpeg.
"""

from __future__ import annotations

from copy import deepcopy

from .ass_writer import LayerRender, build_ass_layers
from .grouping import Layout, breaks_from, group_at_breaks, group_words
from .model import Caption, Layer
from .themes import Animation, Theme

# Modes that dictate how many words a card may hold, regardless of the slider.
MODE_WORD_CAP = {"ONE_WORD": 1, "ONE_PHRASE": 3}


def resolve_theme(layer: Layer) -> Theme:
    """The layer's theme with its per-layer overrides applied."""
    theme = deepcopy(Theme.load(layer.theme))
    if layer.size_pct is not None:
        theme.size_pct = layer.size_pct
    if layer.margin_v_pct is not None:
        theme.margin_v_pct = layer.margin_v_pct
    if layer.position:
        theme.position = layer.position
    return theme


def layout_for(layer: Layer, width: int, height: int) -> Layout:
    cfg = Layout.for_video(width, height)
    cfg.max_words_per_card = max(1, int(layer.max_words))
    cap = MODE_WORD_CAP.get(layer.reveal)
    if cap:
        cfg.max_words_per_card = min(cfg.max_words_per_card, cap)
    return cfg


def layer_captions(layer: Layer, width: int, height: int,
                   duration: float) -> list[Caption]:
    if not layer.words:
        return []
    cfg = layout_for(layer, width, height)
    words = layer.shifted_words()
    if layer.manual_cards:
        return group_at_breaks(words, layer.card_breaks, cfg, duration)
    return group_words(words, cfg, duration)


DEFAULT_ANIM = Animation()

# Starting points for the Effects tab. Each is (strength, speed, tilt, word fade).
EFFECT_PRESETS: dict[str, tuple[float, float, float, int]] = {
    "None":     (0.0, 1.0, 0.0, 0),
    "Subtle":   (0.5, 1.1, 0.0, 0),
    "Default":  (1.0, 1.0, 0.0, 0),
    "Bouncy":   (1.7, 0.9, 0.0, 0),
    "Tilt":     (1.2, 1.0, 6.0, 0),
    "Fade in":  (0.4, 1.0, 0.0, 120),
    "Big":      (2.4, 0.8, 0.0, 0),
}


def animation_for(layer: Layer) -> Animation:
    """Build the motion curve for this layer from its effect settings.

    Strength scales how far the word travels in size; speed scales how long it
    takes. Strength 0 means genuinely no movement, not a tiny one.
    """
    strength = max(0.0, min(4.0, layer.pop_strength))
    speed = max(0.25, min(4.0, layer.pop_speed))
    rise = max(16, int(round(DEFAULT_ANIM.pop_rise_ms / speed)))
    total = max(rise + 16, int(round(DEFAULT_ANIM.pop_ms / speed)))

    if strength == 0.0:
        pop_from = pop_over = 100
    else:
        pop_from = int(round(100 - (100 - DEFAULT_ANIM.pop_from) * strength))
        pop_over = int(round(100 + (DEFAULT_ANIM.pop_over - 100) * strength))
        pop_from = max(5, pop_from)

    return Animation(
        pop_from=pop_from,
        pop_over=pop_over,
        pop_rise_ms=rise,
        pop_ms=total,
        tilt_deg=layer.tilt_deg,
        word_fade_ms=max(0, int(layer.word_fade_ms)),
        card_fade_in_ms=max(0, int(layer.card_fade_ms)),
        card_fade_out_ms=max(0, int(layer.card_fade_ms)),
    )


def apply_preset(layer: Layer, name: str) -> None:
    preset = EFFECT_PRESETS.get(name)
    if not preset:
        return
    layer.pop_strength, layer.pop_speed, layer.tilt_deg, layer.word_fade_ms = preset


def to_render(layer: Layer, width: int, height: int, duration: float,
              anim: Animation | None = None) -> LayerRender:
    return LayerRender(
        captions=layer_captions(layer, width, height, duration),
        theme=resolve_theme(layer),
        mode=layer.reveal,
        anim=anim or animation_for(layer),
    )


def visible(layers: list[Layer]) -> list[Layer]:
    return [lay for lay in layers if lay.enabled and lay.words]


def build_ass_for(layers: list[Layer], width: int, height: int,
                  duration: float, anim: Animation | None = None) -> str:
    """Every enabled layer, stacked, as one .ass document."""
    renders = [to_render(lay, width, height, duration,
                         anim or animation_for(lay))
               for lay in layers if lay.enabled]
    return build_ass_layers(renders, width, height)


def _current_breaks(layer: Layer, captions: list[Caption]) -> list[int]:
    return list(layer.card_breaks) if layer.manual_cards else breaks_from(captions)


def split_card(layer: Layer, captions: list[Caption], card_index: int,
               word_index: int) -> None:
    """Break `card_index` in two, just before its `word_index`-th word."""
    if not (0 <= card_index < len(captions)):
        return
    card = captions[card_index]
    if len(card.words) < 2:
        return
    offset = sum(len(c.words) for c in captions[:card_index])
    point = offset + max(1, min(word_index, len(card.words) - 1))
    layer.card_breaks = sorted(set(_current_breaks(layer, captions)) | {point})
    layer.manual_cards = True


def merge_with_next(layer: Layer, captions: list[Caption], card_index: int) -> None:
    """Join `card_index` to the card after it."""
    if card_index >= len(captions) - 1:
        return
    boundary = sum(len(c.words) for c in captions[:card_index + 1])
    layer.card_breaks = sorted(set(_current_breaks(layer, captions)) - {boundary})
    layer.manual_cards = True


def reset_cards(layer: Layer) -> None:
    """Hand the card boundaries back to the automatic rules."""
    layer.card_breaks = []
    layer.manual_cards = False


def default_name(existing: list[Layer]) -> str:
    used = {lay.name for lay in existing}
    for i in range(2, 40):
        candidate = f"Layer {i}"
        if candidate not in used:
            return candidate
    return "Layer"
