"""Themes — BUILD_PROMPT.md §4.5.

A theme is purely *how the text looks*. It never decides which words are visible;
that is the reveal mode's job (§4.4). Themes only ever produce ASS tag strings.

Pure functions + dataclasses. No Qt, no ffmpeg.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from . import fonts

PRESET_DIR = Path(__file__).resolve().parents[2] / "presets"


# ---------------------------------------------------------------------------
# Colour helpers.  ASS is &HBBGGRR — backwards from RGB.  Getting this wrong is
# a classic bug, so nothing outside this file should build a colour by hand.
# ---------------------------------------------------------------------------


def ass_colour(hex_rgb: str) -> str:
    """'#FFCC00' -> '&H00CCFF&' (BGR)."""
    h = hex_rgb.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    if len(h) != 6:
        raise ValueError(f"Bad colour {hex_rgb!r}, expected #RRGGBB")
    r, g, b = h[0:2], h[2:4], h[4:6]
    return f"&H{b}{g}{r}&".upper()


def ass_style_colour(hex_rgb: str, opacity: float = 1.0) -> str:
    """Style-line form, which carries alpha: &HAABBGGRR."""
    return f"&H{_alpha_byte(opacity)}{ass_colour(hex_rgb)[2:-1]}".upper()


def _alpha_byte(opacity: float) -> str:
    """1.0 opaque -> '00', 0.0 invisible -> 'FF'."""
    return f"{max(0, min(255, round((1.0 - opacity) * 255))):02X}"


def ass_alpha(opacity: float) -> str:
    return f"&H{_alpha_byte(opacity)}&"


HIDDEN = r"\alpha&HFF&"     # the layout-locking tag — see §4.3 Mode B


# ---------------------------------------------------------------------------
# Font availability
# ---------------------------------------------------------------------------


def resolve_family(preferred: str, fallbacks: list[str]) -> tuple[str, bool]:
    """Return (family_to_use, exact). Prefers the fonts we ship, then installed.

    A missing family silently renders as Arial and the user just sees "my font
    didn't apply" — so callers should surface `exact == False` (§4.5).
    """
    return fonts.resolve(preferred, fallbacks)


# ---------------------------------------------------------------------------
# Theme
# ---------------------------------------------------------------------------


@dataclass
class Theme:
    name: str = "custom"
    label: str = "Custom"

    # Typeface
    font: str = "Montserrat"
    font_fallbacks: list[str] = field(default_factory=lambda: ["Arial Black", "Arial"])
    bold: bool = True
    italic: bool = False
    casing: str = "none"                 # none | upper | lower | title
    size_pct: float = 7.0                # % of video HEIGHT — never pixels (§4.5)
    letter_spacing_pct: float = 0.0
    line_spacing_pct: float = 0.0

    # Colour
    base_colour: str = "#FFFFFF"
    active_colour: str | list[str] = "#FFD400"
    outline_colour: str = "#000000"
    outline_opacity: float = 1.0         # border_style 3 draws the PLATE in this colour
    back_colour: str = "#000000"
    back_opacity: float = 1.0            # BackColour is the SHADOW colour — at 0 the
    #                                      shadow silently disappears, which reads as
    #                                      "why does my outline look flat"
    gradient_colour: str = ""            # non-empty enables the two-layer fake (§4.5 #10)

    # Decoration
    border_style: int = 1                # 1 = outline+shadow, 3 = opaque box
    outline_pct: float = 0.55
    shadow_pct: float = 0.25
    blur: float = 0.0

    # Highlight behaviour — how the ACTIVE word differs from the rest
    highlight: str = "colour"            # colour | dim | scale | underline
    inactive_opacity: float = 1.0        # 'dim' variant drops this to ~0.55
    active_scale: int = 100              # 'scale' variant raises this

    # Placement
    position: str = "bottom"             # bottom | center | top | bottom_left
    margin_v_pct: float = 12.0
    margin_h_pct: float = 6.0

    # Recommended pairing (the UI may override; §4.4)
    default_reveal: str = "WORD_POP"

    # ---- loading ----------------------------------------------------------

    @classmethod
    def from_dict(cls, data: dict) -> "Theme":
        known = {f for f in cls.__dataclass_fields__}
        return cls(**{k: v for k, v in data.items() if k in known})

    @classmethod
    def load(cls, name: str) -> "Theme":
        path = PRESET_DIR / f"{name}.json"
        if not path.is_file():
            raise FileNotFoundError(f"No theme preset {name!r} in {PRESET_DIR}")
        return cls.from_dict(json.loads(path.read_text(encoding="utf-8")))

    @staticmethod
    def available() -> list[str]:
        return sorted(p.stem for p in PRESET_DIR.glob("*.json"))

    # ---- derived ----------------------------------------------------------

    def resolved_font(self) -> tuple[str, bool]:
        return resolve_family(self.font, self.font_fallbacks)

    def font_size(self, res_y: int) -> int:
        return max(8, round(self.size_pct / 100 * res_y))

    def transform(self, text: str) -> str:
        if self.casing == "upper":
            return text.upper()
        if self.casing == "lower":
            return text.lower()
        if self.casing == "title":
            return text.title()
        return text

    def active_colour_at(self, index: int) -> str:
        """Theme 2 cycles colours per word, so this takes an index (§4.5)."""
        if isinstance(self.active_colour, list) and self.active_colour:
            return self.active_colour[index % len(self.active_colour)]
        if isinstance(self.active_colour, list):
            return self.base_colour
        return self.active_colour

    # ---- ASS emission -----------------------------------------------------

    def style_line(self, res_x: int, res_y: int, name: str = "Sub") -> str:
        family, _ = self.resolved_font()
        size = self.font_size(res_y)
        spacing = round(self.letter_spacing_pct / 100 * size, 2)
        outline = round(self.outline_pct / 100 * res_y, 2)
        shadow = round(self.shadow_pct / 100 * res_y, 2)
        align = {"bottom": 2, "center": 5, "top": 8, "bottom_left": 1}.get(self.position, 2)
        margin_v = round(self.margin_v_pct / 100 * res_y)
        margin_h = round(self.margin_h_pct / 100 * res_x)

        fields = [
            name, family, size,
            ass_style_colour(self.base_colour),                       # Primary
            ass_style_colour(self.active_colour_at(0)),               # Secondary
            ass_style_colour(self.outline_colour, self.outline_opacity),   # Outline / plate
            ass_style_colour(self.back_colour, self.back_opacity),         # Shadow
            -1 if self.bold else 0,
            -1 if self.italic else 0,
            0, 0,                    # Underline, StrikeOut
            100, 100,                # ScaleX, ScaleY
            spacing, 0,              # Spacing, Angle
            self.border_style, outline, shadow,
            align, margin_h, margin_h, margin_v,
            1,                       # Encoding (1 = default)
        ]
        return "Style: " + ",".join(str(f) for f in fields)

    def line_break_tag(self) -> str:
        r"""How to break a line, honouring `line_spacing_pct`.

        ASS has no line-gap tag: a line's height comes from the largest font on
        it. So to add space we break twice with a spacer line whose only content
        is a space at a reduced size — that line's height IS the extra gap.
        """
        if self.line_spacing_pct <= 0:
            return r"\N"
        scale = max(5, min(100, round(self.line_spacing_pct)))
        return rf"\N{{\r\fscy{scale}\fscx{scale}}} \N{{\r}}"

    def position_tag(self, res_x: int, res_y: int, n_lines: int = 1) -> str:
        """Explicit \\pos beats margins for predictable results (§4.3)."""
        align = {"bottom": 2, "center": 5, "top": 8, "bottom_left": 1}.get(self.position, 2)
        margin_v = self.margin_v_pct / 100 * res_y
        margin_h = self.margin_h_pct / 100 * res_x
        x = margin_h if align == 1 else res_x / 2
        if align in (1, 2):
            y = res_y - margin_v
        elif align == 8:
            y = margin_v
        else:
            y = res_y / 2
        tag = rf"\an{align}\pos({round(x)},{round(y)})"
        if self.line_spacing_pct:
            tag += rf"\fsp0"          # placeholder; libass has no direct line-gap tag
        return tag

    # Per-word tag strings. Each starts with \r so it fully replaces the previous
    # word's overrides rather than accumulating them.

    def inactive_tags(self) -> str:
        tags = r"\r"
        if self.inactive_opacity < 1.0:
            tags += rf"\1a{ass_alpha(self.inactive_opacity)}"
        return "{" + tags + "}"

    def hidden_tags(self) -> str:
        """Fully transparent but still occupying layout space (§4.3 Mode B)."""
        return "{" + r"\r" + HIDDEN + "}"

    def active_tags(self, index: int = 0, animate: bool = True,
                    anim: "Animation | None" = None) -> str:
        tags = r"\r"
        if self.highlight in ("colour", "underline"):
            tags += rf"\c{ass_colour(self.active_colour_at(index))}"
        if self.highlight == "underline":
            tags += r"\u1"
        if self.blur:
            tags += rf"\blur{self.blur}"
        scale = self.active_scale if self.highlight == "scale" else 100
        # BorderStyle 3 draws an opaque plate behind each run, and the plate scales
        # with the glyphs — a popped word leaves a stub of box hanging below the
        # line. Plate themes get their emphasis from colour instead.
        if self.border_style == 3:
            return "{" + tags + "}"

        anim = anim or Animation()
        if animate and anim.pop_ms > 0 and (anim.pop_from != 100
                                            or anim.pop_over != 100
                                            or scale != 100):
            # Two \t stages: spring up to the overshoot, then settle. One stage
            # from oversized down to 100% is the thing that looked like a twitch.
            peak = max(anim.pop_over, scale)
            tags += (rf"\fscx{anim.pop_from}\fscy{anim.pop_from}"
                     rf"\t(0,{anim.pop_rise_ms},\fscx{peak}\fscy{peak})"
                     rf"\t({anim.pop_rise_ms},{anim.pop_ms},\fscx100\fscy100)")
        elif scale != 100:
            tags += rf"\fscx{scale}\fscy{scale}"

        if animate and anim.tilt_deg:
            tags += (rf"\frz{anim.tilt_deg:g}"
                     rf"\t(0,{anim.pop_ms},\frz0)")
        if animate and anim.word_fade_ms:
            tags += (rf"\alpha&HFF&"
                     rf"\t(0,{anim.word_fade_ms},\alpha&H00&)")
        return "{" + tags + "}"


@dataclass
class Animation:
    """How things move. Independent of theme and of reveal mode (§4.3).

    The pop is a GROW-AND-SETTLE, not a shrink: the word springs up from small,
    overshoots slightly, then eases back to its real size. (Starting oversized
    and merely shrinking 12% over 90 ms was two or three frames of a few pixels
    and read as a twitch; springing 62 -> 118% read as too much. The default
    below sits between them.)

    Everything here is expressed as a curve the ASS tags can also describe, so
    the live preview and the exported file move identically.
    """

    pop_from: int = 78            # % of full size the word springs up from
    pop_over: int = 108           # % it overshoots to
    pop_rise_ms: int = 70         # time to reach the overshoot
    pop_ms: int = 200             # total animation, ending back at 100%
    tilt_deg: float = 0.0         # degrees the word is rotated on arrival
    word_fade_ms: int = 0         # fade each word in over this long
    card_fade_in_ms: int = 60
    card_fade_out_ms: int = 60

    @property
    def pop_scale(self) -> int:
        """Back-compatible alias — the peak size the word reaches."""
        return self.pop_over

    @property
    def moves(self) -> bool:
        return self.pop_ms > 0 and (self.pop_from != 100 or self.pop_over != 100
                                    or self.tilt_deg)

    @staticmethod
    def _ease(k: float) -> float:
        k = max(0.0, min(1.0, k))
        return 1 - (1 - k) ** 2

    def scale_at(self, elapsed_ms: float) -> float:
        """Size multiplier this many milliseconds after the word appeared."""
        if elapsed_ms < 0:
            return self.pop_from / 100.0
        if elapsed_ms >= self.pop_ms:
            return 1.0
        if elapsed_ms < self.pop_rise_ms:
            k = self._ease(elapsed_ms / max(1.0, self.pop_rise_ms))
            return (self.pop_from + (self.pop_over - self.pop_from) * k) / 100.0
        k = self._ease((elapsed_ms - self.pop_rise_ms)
                       / max(1.0, self.pop_ms - self.pop_rise_ms))
        return (self.pop_over + (100 - self.pop_over) * k) / 100.0

    def tilt_at(self, elapsed_ms: float) -> float:
        """Rotation in degrees, unwinding to zero over the pop."""
        if not self.tilt_deg or elapsed_ms >= self.pop_ms:
            return 0.0
        k = self._ease(max(0.0, elapsed_ms) / max(1.0, self.pop_ms))
        return self.tilt_deg * (1.0 - k)

    def word_alpha_at(self, elapsed_ms: float) -> float:
        if not self.word_fade_ms or elapsed_ms >= self.word_fade_ms:
            return 1.0
        return max(0.0, elapsed_ms) / self.word_fade_ms

    @property
    def fade_tag(self) -> str:
        if not (self.card_fade_in_ms or self.card_fade_out_ms):
            return ""
        return rf"\fad({self.card_fade_in_ms},{self.card_fade_out_ms})"


def load_all() -> dict[str, Theme]:
    return {n: Theme.load(n) for n in Theme.available()}
