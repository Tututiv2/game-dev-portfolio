"""Style sidebar — theme gallery, reveal mode, and layout controls.

Theme and reveal mode are two SEPARATE controls sitting side by side (§4.4), and
changing either re-renders from the cached transcript without ever touching
Whisper again (§4.6).
"""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import (
    QColor, QFont, QLinearGradient, QPainter, QPainterPath, QPixmap,
)
from PySide6.QtWidgets import (
    QButtonGroup, QComboBox, QFrame, QGridLayout, QHBoxLayout, QLabel,
    QPushButton, QScrollArea, QSizePolicy, QSlider, QTabWidget, QVBoxLayout,
    QWidget,
)

from ..core import media
from ..core.layers import EFFECT_PRESETS
from ..core.reveal import MODES
from ..core.themes import Theme
from . import style
from .layers_panel import LayersPanel

MODE_SHORT = {
    "WORD_POP": "Pop",
    "KARAOKE_HIGHLIGHT": "Karaoke",
    "ONE_WORD": "One word",
    "ONE_PHRASE": "Phrase",
    "TYPEWRITER": "Type",
    "WHOLE_CARD": "Plain",
}
MODE_HELP = {
    "WORD_POP": "Words appear one at a time and build up the line.",
    "KARAOKE_HIGHLIGHT": "The whole line sits there; the highlight travels across it.",
    "ONE_WORD": "One big word at a time, centred. The TikTok look.",
    "ONE_PHRASE": "Two or three words at a time, swapping as a group. "
                  "Punchy but easier to read than one word.",
    "TYPEWRITER": "Words appear progressively, calmly — no pop.",
    "WHOLE_CARD": "Plain captions, no animation at all.",
}


def _rounded(pixmap: QPixmap, radius: int = 7) -> QPixmap:
    """Round the thumbnail corners so cards don't look like pasted screenshots."""
    if pixmap.isNull():
        return pixmap
    out = QPixmap(pixmap.size())
    out.fill(Qt.transparent)
    p = QPainter(out)
    p.setRenderHint(QPainter.Antialiasing)
    path = QPainterPath()
    path.addRoundedRect(0, 0, pixmap.width(), pixmap.height(), radius, radius)
    p.setClipPath(path)
    p.drawPixmap(0, 0, pixmap)
    p.end()
    return out


class ThemeCard(QPushButton):
    """A clickable theme swatch: live thumbnail with the name laid over it.

    The label sits on the image rather than under it — it keeps ten cards inside
    the sidebar without pushing the Layout controls off the bottom, and it reads
    as a media picker instead of a form.
    """

    def __init__(self, name: str, label: str, parent=None):
        super().__init__(parent)
        self.name = name
        self.setCheckable(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedHeight(88)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self._thumb: QPixmap | None = None
        self._label = label
        self.setStyleSheet("QPushButton { background: transparent; border: none; }")

    def set_thumbnail(self, pixmap: QPixmap) -> None:
        self._thumb = pixmap
        self.update()

    def paintEvent(self, _event) -> None:
        # Anything that escapes a paintEvent leaves the QPainter active and
        # corrupts the backing store — the window then stops repainting entirely
        # and looks like a freeze. Always end the painter.
        p = QPainter(self)
        try:
            self._paint(p)
        finally:
            p.end()

    def _paint(self, p: QPainter) -> None:
        p.setRenderHint(QPainter.Antialiasing)
        r = self.rect().adjusted(1, 1, -1, -1)

        selected = self.isChecked()
        border = QColor(style.ACCENT) if selected else QColor(
            style.BORDER_HI if self.underMouse() else style.BORDER)

        path = QPainterPath()
        path.addRoundedRect(r.x(), r.y(), r.width(), r.height(), 9, 9)
        p.fillPath(path, QColor("#12121A"))

        p.save()
        p.setClipPath(path)
        if self._thumb and not self._thumb.isNull():
            scaled = self._thumb.scaled(r.size(), Qt.KeepAspectRatioByExpanding,
                                        Qt.SmoothTransformation)
            p.drawPixmap(r.x() - max(0, (scaled.width() - r.width()) // 2),
                         r.y() - max(0, (scaled.height() - r.height()) // 2),
                         scaled)
        else:
            p.fillRect(r, QColor("#101018"))
            p.setPen(QColor(style.TEXT_FAINT))
            p.drawText(r, Qt.AlignCenter, "…")

        # Scrim so the name stays readable over any footage.
        scrim = QLinearGradient(r.x(), r.y() + r.height() - 34,
                                r.x(), r.y() + r.height())
        scrim.setColorAt(0.0, QColor(0, 0, 0, 0))
        scrim.setColorAt(1.0, QColor(0, 0, 0, 205))
        p.fillRect(r.x(), r.y() + r.height() - 34, r.width(), 34, scrim)
        p.restore()

        p.setPen(QColor("#FFFFFF") if selected else QColor("#D8D8E4"))
        f = p.font()
        f.setPointSizeF(9.0)
        # PySide6 wants the Weight enum here, not a raw int.
        f.setWeight(QFont.Weight.DemiBold if selected else QFont.Weight.Medium)
        p.setFont(f)
        p.drawText(r.adjusted(9, r.height() - 26, -9, -6),
                   Qt.AlignLeft | Qt.AlignVCenter, self._label)

        p.setPen(border)
        p.drawPath(path)
        if selected:
            inner = QPainterPath()
            inner.addRoundedRect(r.x() + 1, r.y() + 1, r.width() - 2, r.height() - 2,
                                 8, 8)
            p.setPen(QColor(style.ACCENT))
            p.drawPath(inner)


class Section(QWidget):
    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self.body = QVBoxLayout(self)
        self.body.setContentsMargins(0, 0, 0, 0)
        self.body.setSpacing(9)
        head = QLabel(title.upper())
        head.setProperty("role", "section")
        self.body.addWidget(head)

    def add(self, widget_or_layout) -> None:
        if isinstance(widget_or_layout, QWidget):
            self.body.addWidget(widget_or_layout)
        else:
            self.body.addLayout(widget_or_layout)


class LabelledSlider(QWidget):
    changed = Signal(float)

    def __init__(self, label: str, lo: float, hi: float, value: float,
                 suffix: str = "", decimals: int = 0, parent=None):
        super().__init__(parent)
        self._lo, self._hi, self._decimals = lo, hi, decimals
        self._suffix = suffix

        self.name = QLabel(label)
        self.name.setProperty("role", "dim")
        self.value_label = QLabel("")
        self.value_label.setProperty("role", "mono")
        self.value_label.setAlignment(Qt.AlignRight)

        self.slider = QSlider(Qt.Horizontal)
        self.slider.setRange(0, 1000)
        self.slider.valueChanged.connect(self._on_change)

        top = QHBoxLayout()
        top.setContentsMargins(0, 0, 0, 0)
        top.addWidget(self.name)
        top.addStretch(1)
        top.addWidget(self.value_label)

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(3)
        root.addLayout(top)
        root.addWidget(self.slider)

        self.set_value(value)

    def _to_slider(self, v: float) -> int:
        return int((v - self._lo) / (self._hi - self._lo) * 1000)

    def value(self) -> float:
        raw = self._lo + self.slider.value() / 1000 * (self._hi - self._lo)
        return round(raw, self._decimals) if self._decimals else round(raw)

    def set_value(self, v: float) -> None:
        blocked = self.slider.blockSignals(True)
        self.slider.setValue(self._to_slider(v))
        self.slider.blockSignals(blocked)
        self._render_label()

    def _render_label(self) -> None:
        v = self.value()
        text = f"{v:.{self._decimals}f}" if self._decimals else f"{int(v)}"
        self.value_label.setText(f"{text}{self._suffix}")

    def _on_change(self) -> None:
        self._render_label()
        self.changed.emit(self.value())


class StylePanel(QScrollArea):
    """The whole right-hand sidebar."""

    theme_changed = Signal(str)
    mode_changed = Signal(str)
    layout_changed = Signal()
    sfx_changed = Signal(str)
    sfx_test_requested = Signal()
    effect_preset_chosen = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWidgetResizable(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)

        self._theme_name = "hormozi"
        self._mode = "WORD_POP"
        self._position = "bottom"
        self.cards: dict[str, ThemeCard] = {}

        inner = QWidget()
        inner.setObjectName("Root")
        outer = QVBoxLayout(inner)
        outer.setContentsMargins(4, 2, 10, 12)
        outer.setSpacing(14)

        # --- layers (above the tabs: they're the context for everything) ----
        self.layers_panel = LayersPanel()
        outer.addWidget(self.layers_panel)

        self.tabs = QTabWidget()
        self.tabs.setDocumentMode(True)
        outer.addWidget(self.tabs, 1)

        style_page, root = self._page()
        self.tabs.addTab(style_page, "Style")

        # --- themes ---------------------------------------------------------
        themes = Section("Style")
        grid = QGridLayout()
        grid.setSpacing(8)
        self._group = QButtonGroup(self)
        self._group.setExclusive(True)
        for i, name in enumerate(Theme.available()):
            theme = Theme.load(name)
            card = ThemeCard(name, theme.label)
            card.clicked.connect(lambda _=False, n=name: self._pick_theme(n))
            self._group.addButton(card)
            self.cards[name] = card
            grid.addWidget(card, i // 2, i % 2)
        themes.add(grid)
        root.addWidget(themes)

        # --- reveal mode ----------------------------------------------------
        modes = Section("Animation")
        holder = QFrame()
        holder.setProperty("role", "panel")
        mode_grid = QGridLayout(holder)
        mode_grid.setContentsMargins(5, 5, 5, 5)
        mode_grid.setSpacing(3)
        self._mode_group = QButtonGroup(self)
        self._mode_group.setExclusive(True)
        self._mode_buttons: dict[str, QPushButton] = {}
        for i, name in enumerate(MODES):
            btn = QPushButton(MODE_SHORT[name])
            btn.setProperty("kind", "segment")
            btn.setCheckable(True)
            btn.setCursor(Qt.PointingHandCursor)
            btn.setToolTip(MODE_HELP[name])
            btn.clicked.connect(lambda _=False, n=name: self._pick_mode(n))
            self._mode_group.addButton(btn)
            self._mode_buttons[name] = btn
            mode_grid.addWidget(btn, i // 3, i % 3)
        modes.add(holder)

        self.mode_help = QLabel(MODE_HELP[self._mode])
        self.mode_help.setProperty("role", "faint")
        self.mode_help.setWordWrap(True)
        modes.add(self.mode_help)
        root.addWidget(modes)

        root.addStretch(1)

        # --- layout tab -----------------------------------------------------
        layout_page, root = self._page()
        self.tabs.addTab(layout_page, "Layout")
        layout_sec = Section("Placement")
        pos_holder = QFrame()
        pos_holder.setProperty("role", "panel")
        pos_grid = QHBoxLayout(pos_holder)
        pos_grid.setContentsMargins(5, 5, 5, 5)
        pos_grid.setSpacing(3)
        self._pos_group = QButtonGroup(self)
        self._pos_buttons: dict[str, QPushButton] = {}
        for key, label in (("top", "Top"), ("center", "Middle"),
                           ("bottom", "Bottom"), ("bottom_left", "Left")):
            b = QPushButton(label)
            b.setProperty("kind", "segment")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            b.clicked.connect(lambda _c=False, k=key: self._pick_position(k))
            self._pos_group.addButton(b)
            self._pos_buttons[key] = b
            pos_grid.addWidget(b)
        layout_sec.add(pos_holder)

        self.size_slider = LabelledSlider("Text size", 3.0, 20.0, 10.5, "%", 1)
        self.words_slider = LabelledSlider("Words per line", 1, 10, 5)
        self.height_slider = LabelledSlider("Distance from edge", 2, 45, 16, "%")
        self.offset_slider = LabelledSlider("Timing nudge", -1.0, 1.0, 0.0, "s", 2)
        self.offset_slider.setToolTip(
            "Shift this layer earlier or later, so a second track can trail the "
            "first instead of landing on the same frame.")
        for s in (self.size_slider, self.words_slider, self.height_slider,
                  self.offset_slider):
            s.changed.connect(lambda _v: self.layout_changed.emit())
            layout_sec.add(s)
        root.addWidget(layout_sec)
        root.addStretch(1)

        # --- effects tab ----------------------------------------------------
        effects_page, root = self._page()
        self.tabs.addTab(effects_page, "Effects")
        effects = Section("Motion")

        preset_row = QHBoxLayout()
        preset_row.setSpacing(6)
        preset_label = QLabel("Preset")
        preset_label.setProperty("role", "dim")
        self.effect_combo = QComboBox()
        for name in EFFECT_PRESETS:
            self.effect_combo.addItem(name, name)
        self.effect_combo.setCurrentText("Default")
        self.effect_combo.activated.connect(
            lambda _i: self.effect_preset_chosen.emit(self.effect_combo.currentData()))
        preset_row.addWidget(preset_label)
        preset_row.addWidget(self.effect_combo, 1)
        effects.add(preset_row)

        self.pop_slider = LabelledSlider("Pop strength", 0.0, 3.0, 1.0, "x", 2)
        self.pop_slider.setToolTip(
            "How far each word grows as it lands.\n"
            "0 is completely still. 1 is the default.")
        self.speed_slider = LabelledSlider("Pop speed", 0.3, 3.0, 1.0, "x", 2)
        self.speed_slider.setToolTip(
            "How quickly the pop plays out. Higher is snappier;\n"
            "lower makes it linger.")
        self.tilt_slider = LabelledSlider("Tilt", 0.0, 15.0, 0.0, "°", 1)
        self.tilt_slider.setToolTip(
            "Each word arrives rotated by this much and straightens as it "
            "settles.")
        self.word_fade_slider = LabelledSlider("Word fade in", 0, 400, 0, " ms")
        self.card_fade_slider = LabelledSlider("Card fade", 0, 400, 60, " ms")
        self.card_fade_slider.setToolTip(
            "Fades the whole caption in and out at its edges.")
        for s in (self.pop_slider, self.speed_slider, self.tilt_slider,
                  self.word_fade_slider, self.card_fade_slider):
            s.changed.connect(lambda _v: self.layout_changed.emit())
            effects.add(s)

        note = QLabel("Effects apply to the selected layer, and are baked into "
                      "the export exactly as you see them here.")
        note.setProperty("role", "faint")
        note.setWordWrap(True)
        effects.add(note)
        root.addWidget(effects)
        root.addStretch(1)

        # --- sound tab ------------------------------------------------------
        sound_page, root = self._page()
        self.tabs.addTab(sound_page, "Sound")
        sound = Section("Caption sound")
        self.sfx_combo = QComboBox()
        self.sfx_combo.addItem("No sound", "")
        for name in media.available_sfx():
            self.sfx_combo.addItem(name.replace("_", " ").title(), name)
        self.sfx_combo.currentIndexChanged.connect(
            lambda _i: self.sfx_changed.emit(self.sfx_name()))
        sound.add(self.sfx_combo)

        self.sfx_volume = LabelledSlider("Sound volume", 0, 100, 70, "%")
        self.sfx_volume.changed.connect(lambda _v: self.sfx_changed.emit(
            self.sfx_name()))
        sound.add(self.sfx_volume)

        sfx_row = QHBoxLayout()
        sfx_row.setSpacing(6)
        self.sfx_test = QPushButton("Test")
        self.sfx_test.setProperty("kind", "ghost")
        self.sfx_test.setCursor(Qt.PointingHandCursor)
        self.sfx_test.clicked.connect(lambda: self.sfx_test_requested.emit())
        folder_btn = QPushButton("Add your own…")
        folder_btn.setProperty("kind", "ghost")
        folder_btn.setCursor(Qt.PointingHandCursor)
        folder_btn.setToolTip("Opens assets\\sfx — drop any .wav or .mp3 in there "
                              "and it appears in this list")
        folder_btn.clicked.connect(self._open_sfx_folder)
        sfx_row.addWidget(self.sfx_test)
        sfx_row.addWidget(folder_btn)
        sfx_row.addStretch(1)
        sound.add(sfx_row)

        hint = QLabel("Plays once per caption. On One word it plays per word.")
        hint.setProperty("role", "faint")
        hint.setWordWrap(True)
        sound.add(hint)
        root.addWidget(sound)
        root.addStretch(1)

        self.setWidget(inner)

    @staticmethod
    def _page():
        """A tab page plus the layout to fill it."""
        page = QWidget()
        page.setObjectName("Root")
        box = QVBoxLayout(page)
        box.setContentsMargins(2, 14, 2, 4)
        box.setSpacing(18)
        return page, box

    # -- sound ---------------------------------------------------------------

    def sfx_name(self) -> str:
        return self.sfx_combo.currentData() or ""

    def sfx_path(self):
        name = self.sfx_name()
        return media.available_sfx().get(name) if name else None

    def sfx_gain(self) -> float:
        return self.sfx_volume.value() / 100.0

    def refresh_sfx(self) -> None:
        current = self.sfx_name()
        self.sfx_combo.blockSignals(True)
        self.sfx_combo.clear()
        self.sfx_combo.addItem("No sound", "")
        for name in media.available_sfx():
            self.sfx_combo.addItem(name.replace("_", " ").title(), name)
        idx = self.sfx_combo.findData(current)
        self.sfx_combo.setCurrentIndex(max(0, idx))
        self.sfx_combo.blockSignals(False)

    def _open_sfx_folder(self) -> None:
        import subprocess

        media.SFX_DIR.mkdir(parents=True, exist_ok=True)
        subprocess.Popen(["explorer", str(media.SFX_DIR)])

    # -- api -----------------------------------------------------------------

    def theme_name(self) -> str:
        return self._theme_name

    def mode(self) -> str:
        return self._mode

    def set_theme(self, name: str, silent: bool = False) -> None:
        if name not in self.cards:
            return
        self._theme_name = name
        self.cards[name].setChecked(True)
        theme = Theme.load(name)
        self.size_slider.set_value(theme.size_pct)
        self.height_slider.set_value(theme.margin_v_pct)
        if not silent:
            self.theme_changed.emit(name)

    def set_mode(self, name: str, silent: bool = False) -> None:
        if name not in self._mode_buttons:
            return
        self._mode = name
        self._mode_buttons[name].setChecked(True)
        self.mode_help.setText(MODE_HELP[name])
        self.words_slider.setEnabled(name != "ONE_WORD")
        if not silent:
            self.mode_changed.emit(name)

    def set_thumbnail(self, name: str, pixmap: QPixmap) -> None:
        if name in self.cards:
            self.cards[name].set_thumbnail(_rounded(pixmap))

    def overrides(self) -> dict:
        return {
            "size_pct": self.size_slider.value(),
            "margin_v_pct": self.height_slider.value(),
            "max_words": int(self.words_slider.value()),
            "position": self._position,
            "time_offset": self.offset_slider.value(),
            "pop_strength": self.pop_slider.value(),
            "pop_speed": self.speed_slider.value(),
            "tilt_deg": self.tilt_slider.value(),
            "word_fade_ms": int(self.word_fade_slider.value()),
            "card_fade_ms": int(self.card_fade_slider.value()),
        }

    def position(self) -> str:
        return self._position

    def set_position(self, key: str, silent: bool = False) -> None:
        self._position = key or "bottom"
        button = self._pos_buttons.get(self._position)
        if button:
            button.setChecked(True)
        if not silent:
            self.layout_changed.emit()

    def _pick_position(self, key: str) -> None:
        self.set_position(key)

    def load_layer(self, layer) -> None:
        """Point every control at this layer, without firing change signals."""
        from ..core.themes import Theme as _Theme

        theme = _Theme.load(layer.theme)
        self.set_theme(layer.theme, silent=True)
        self.set_mode(layer.reveal, silent=True)
        self.size_slider.set_value(layer.size_pct
                                   if layer.size_pct is not None else theme.size_pct)
        self.height_slider.set_value(
            layer.margin_v_pct if layer.margin_v_pct is not None
            else theme.margin_v_pct)
        self.words_slider.set_value(layer.max_words)
        self.offset_slider.set_value(layer.time_offset)
        self.pop_slider.set_value(layer.pop_strength)
        self.speed_slider.set_value(layer.pop_speed)
        self.tilt_slider.set_value(layer.tilt_deg)
        self.word_fade_slider.set_value(layer.word_fade_ms)
        self.card_fade_slider.set_value(layer.card_fade_ms)
        self.set_position(layer.position or theme.position, silent=True)

    # -- internals -----------------------------------------------------------

    def _pick_theme(self, name: str) -> None:
        self._theme_name = name
        theme = Theme.load(name)
        # Picking a style adopts its sizing and placement; the sliders are then
        # yours to nudge. Keeping the old values would make most themes look wrong.
        self.size_slider.set_value(theme.size_pct)
        self.height_slider.set_value(theme.margin_v_pct)
        self.set_position(theme.position, silent=True)
        # Each theme carries a recommended mode, but the user's explicit mode
        # choice is never overridden — the two controls stay independent (§4.4).
        self.theme_changed.emit(name)

    def _pick_mode(self, name: str) -> None:
        self.set_mode(name)
