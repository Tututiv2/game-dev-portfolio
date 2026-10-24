"""Preview pane — the video frame with subtitles actually burned in (§5).

Deliberately NOT a Qt reimplementation of ASS rendering: the preview is produced
by the same libass path as the final export, so it cannot disagree with it. A
preview that lies is worse than no preview.
"""

from __future__ import annotations

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QColor, QPainter, QPen, QPixmap
from PySide6.QtWidgets import (
    QFrame, QHBoxLayout, QLabel, QPushButton, QSizePolicy, QSlider, QVBoxLayout,
    QWidget,
)

from . import style


def _fmt_time(seconds: float) -> str:
    seconds = max(0.0, seconds)
    m, s = divmod(int(seconds), 60)
    return f"{m}:{s:02d}.{int((seconds % 1) * 10)}"


class FrameView(QWidget):
    """Letterboxed frame with a soft checker-free backdrop and a subtle border."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._pixmap: QPixmap | None = None
        self._message = "No video loaded"
        self.setMinimumSize(480, 270)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)

    def set_pixmap(self, pixmap: QPixmap | None) -> None:
        self._pixmap = pixmap
        self.update()

    def set_message(self, text: str) -> None:
        self._message = text
        self._pixmap = None
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        try:
            self._paint(p)
        finally:
            p.end()

    def _paint(self, p: QPainter) -> None:
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        rect = self.rect()

        p.fillRect(rect, QColor("#08080C"))

        if self._pixmap is None or self._pixmap.isNull():
            p.setPen(QColor(style.TEXT_FAINT))
            p.drawText(rect, Qt.AlignCenter, self._message)
            return

        scaled = self._pixmap.scaled(rect.size(), Qt.KeepAspectRatio,
                                     Qt.SmoothTransformation)
        x = (rect.width() - scaled.width()) // 2
        y = (rect.height() - scaled.height()) // 2
        p.drawPixmap(x, y, scaled)

        p.setPen(QPen(QColor(255, 255, 255, 18), 1))
        p.drawRect(x, y, scaled.width() - 1, scaled.height() - 1)


class PreviewPane(QFrame):
    """Frame view + scrubber + looping animation preview.

    Emits `seeked` (debounced) as the user drags, and `play_requested` when they
    want to actually watch the captions animate — which is the only way to judge
    per-word timing, since a still frame cannot show it.
    """

    seeked = Signal(float)
    play_requested = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setProperty("role", "panel")
        self._duration = 0.0
        self._position = 0.0
        self._dragging = False

        # looping clip playback
        self._frames: list[bytes] = []
        self._frame_index = 0
        self._clip_start = 0.0
        self._clip_fps = 15.0
        self._playing = False
        self._play_timer = QTimer(self)
        self._play_timer.timeout.connect(self._advance)

        self.view = FrameView()

        self.slider = QSlider(Qt.Horizontal)
        self.slider.setRange(0, 1000)
        self.slider.setEnabled(False)
        self.slider.sliderPressed.connect(lambda: setattr(self, "_dragging", True))
        self.slider.sliderReleased.connect(self._release)
        self.slider.valueChanged.connect(self._slider_moved)

        self.time_label = QLabel("0:00.0")
        self.time_label.setProperty("role", "mono")
        self.duration_label = QLabel("0:00.0")
        self.duration_label.setProperty("role", "faint")

        self.hint = QLabel("")
        self.hint.setProperty("role", "faint")
        self.hint.setAlignment(Qt.AlignCenter)

        self.play_btn = QPushButton("▶  Play")
        self.play_btn.setProperty("kind", "ghost")
        self.play_btn.setCursor(Qt.PointingHandCursor)
        self.play_btn.setEnabled(False)
        self.play_btn.setToolTip("Watch the captions animate (Space)")
        self.play_btn.clicked.connect(self._on_play_clicked)

        bar = QHBoxLayout()
        bar.setContentsMargins(0, 0, 0, 0)
        bar.setSpacing(10)
        bar.addWidget(self.play_btn)
        bar.addWidget(self.time_label)
        bar.addWidget(self.slider, 1)
        bar.addWidget(self.duration_label)

        root = QVBoxLayout(self)
        root.setContentsMargins(14, 14, 14, 12)
        root.setSpacing(10)
        root.addWidget(self.view, 1)
        root.addLayout(bar)
        root.addWidget(self.hint)

        # Debounce: coalesce a drag into ~15 renders/sec rather than one per pixel.
        self._debounce = QTimer(self)
        self._debounce.setSingleShot(True)
        self._debounce.setInterval(65)
        self._debounce.timeout.connect(lambda: self.seeked.emit(self._position))

    # -- state ---------------------------------------------------------------

    def set_duration(self, seconds: float) -> None:
        self._duration = max(0.0, seconds)
        self.slider.setEnabled(self._duration > 0)
        self.duration_label.setText(_fmt_time(self._duration))

    def position(self) -> float:
        return self._position

    def set_position(self, seconds: float, emit: bool = True) -> None:
        if self._duration <= 0:
            return
        self._position = max(0.0, min(seconds, self._duration))
        self.time_label.setText(_fmt_time(self._position))
        blocked = self.slider.blockSignals(True)
        self.slider.setValue(int(self._position / self._duration * 1000))
        self.slider.blockSignals(blocked)
        if emit:
            self._debounce.start()

    def set_hint(self, text: str) -> None:
        self.hint.setText(text)

    # -- internals -----------------------------------------------------------

    def _slider_moved(self, value: int) -> None:
        if self._duration <= 0:
            return
        self._position = value / 1000 * self._duration
        self.time_label.setText(_fmt_time(self._position))
        self._debounce.start()

    def _release(self) -> None:
        self._dragging = False
        self._debounce.start(0)

    # -- clip playback -------------------------------------------------------

    def set_play_enabled(self, enabled: bool) -> None:
        self.play_btn.setEnabled(enabled)

    def is_playing(self) -> bool:
        return self._playing

    def _on_play_clicked(self) -> None:
        if self._playing:
            self.stop()
        else:
            self.play_requested.emit()

    def set_preparing(self, preparing: bool) -> None:
        self.play_btn.setText("Rendering…" if preparing else
                              ("■  Stop" if self._playing else "▶  Play"))
        self.play_btn.setEnabled(not preparing)

    def play_frames(self, frames: list[bytes], start: float, fps: float) -> None:
        if not frames:
            return
        self._frames = frames
        self._clip_start, self._clip_fps = start, fps
        self._frame_index = 0
        self._playing = True
        self.play_btn.setText("■  Stop")
        self.play_btn.setEnabled(True)
        self.slider.setEnabled(False)
        self._play_timer.start(int(1000 / max(1.0, fps)))
        self._advance()

    def stop(self) -> None:
        if not self._playing:
            return
        self._play_timer.stop()
        self._playing = False
        self._frames = []
        self.play_btn.setText("▶  Play")
        self.slider.setEnabled(self._duration > 0)
        # Land back exactly where playback stopped, so the still frame agrees.
        self.seeked.emit(self._position)

    def _advance(self) -> None:
        if not self._frames:
            return
        data = self._frames[self._frame_index]
        pixmap = QPixmap()
        if pixmap.loadFromData(data, "JPG"):
            self.view.set_pixmap(pixmap)

        t = self._clip_start + self._frame_index / self._clip_fps
        self._position = min(t, self._duration) if self._duration else t
        self.time_label.setText(_fmt_time(self._position))
        blocked = self.slider.blockSignals(True)
        if self._duration > 0:
            self.slider.setValue(int(self._position / self._duration * 1000))
        self.slider.blockSignals(blocked)

        self._frame_index = (self._frame_index + 1) % len(self._frames)
