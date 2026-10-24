"""Video player: real playback, real audio, captions drawn live on top.

The frame timestamp comes from the decoded frame itself (`QVideoFrame.startTime`)
rather than from the player's position poll, because the poll updates only every
~100 ms — captions timed off it visibly lag the speech they belong to.
"""

from __future__ import annotations

import time
from pathlib import Path

from PySide6.QtCore import QRectF, Qt, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QImage, QPainter, QPen
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer, QVideoSink
from PySide6.QtWidgets import (
    QFrame, QHBoxLayout, QLabel, QPushButton, QSizePolicy, QSlider, QVBoxLayout,
    QWidget,
)

from ..core.model import Caption
from ..core.themes import Animation, Theme
from . import caption_render, style
from .icons import IconButton


def fmt_time(seconds: float) -> str:
    seconds = max(0.0, seconds)
    m, s = divmod(seconds, 60)
    h, m = divmod(int(m), 60)
    head = f"{h}:{m:02d}" if h else f"{m}"
    return f"{head}:{s:04.1f}"


class VideoCanvas(QWidget):
    """Paints the current frame letterboxed, with captions over it."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumSize(420, 240)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setAttribute(Qt.WA_OpaquePaintEvent, True)

        self._image: QImage | None = None
        self._exact: QImage | None = None     # libass render, when asked for
        self._message = "No video loaded"
        self.time = 0.0

        self.renders: list = []          # list[LayerRender], back to front
        self.show_captions = True
        self.playing = False

    # -- inputs --------------------------------------------------------------

    def set_image(self, image: QImage | None) -> None:
        self._image = image
        self.update()

    def set_exact(self, image: QImage | None) -> None:
        self._exact = image
        self.update()

    def set_message(self, text: str) -> None:
        self._message = text
        self._image = None
        self.update()

    def set_time(self, t: float) -> None:
        self.time = t
        self.update()

    # -- painting ------------------------------------------------------------

    def video_rect(self) -> QRectF:
        r = QRectF(self.rect())
        if self._image is None or self._image.isNull():
            return r
        iw, ih = self._image.width(), self._image.height()
        if iw <= 0 or ih <= 0:
            return r
        scale = min(r.width() / iw, r.height() / ih)
        w, h = iw * scale, ih * scale
        return QRectF(r.left() + (r.width() - w) / 2,
                      r.top() + (r.height() - h) / 2, w, h)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        try:
            self._paint(p)
        finally:
            p.end()

    def _paint(self, p: QPainter) -> None:
        p.fillRect(self.rect(), QColor("#07070B"))

        source = self._exact if self._exact is not None else self._image
        if source is None or source.isNull():
            p.setPen(QColor(style.TEXT_FAINT))
            p.drawText(self.rect(), Qt.AlignCenter, self._message)
            return

        # Smooth scaling of a 1080p/4K frame costs several milliseconds. During
        # playback the frame is on screen for 33 ms and moving, so fast scaling
        # is indistinguishable; when paused, quality is what matters.
        p.setRenderHint(QPainter.SmoothPixmapTransform, not self.playing)
        rect = self.video_rect()
        p.drawImage(rect, source)

        if self._exact is None and self.show_captions and self.renders:
            p.save()
            p.setClipRect(rect)
            caption_render.draw_layers(p, rect, self.renders, self.time)
            p.restore()

        p.setPen(QPen(QColor(255, 255, 255, 16), 1))
        p.drawRect(rect.adjusted(0, 0, -1, -1))


class Player(QFrame):
    """Canvas + transport."""

    position_changed = Signal(float)
    duration_changed = Signal(float)
    playing_changed = Signal(bool)
    exact_requested = Signal(float)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setProperty("role", "panel")

        self._duration = 0.0
        self._scrubbing = False
        self._muted_volume = 0.7
        self._last_frame_at = 0.0
        self._last_convert = 0.0
        self._min_frame_gap = 1 / 30.0      # never try to display faster than this

        self.canvas = VideoCanvas()

        self.audio = QAudioOutput()
        self.audio.setVolume(self._muted_volume)
        self.sink = QVideoSink()
        self.player = QMediaPlayer()
        self.player.setAudioOutput(self.audio)
        self.player.setVideoSink(self.sink)

        self.sink.videoFrameChanged.connect(self._on_frame)
        self.player.durationChanged.connect(self._on_duration)
        self.player.positionChanged.connect(self._on_position)
        self.player.playbackStateChanged.connect(self._on_state)
        self.player.errorOccurred.connect(self._on_error)

        # -- transport --------------------------------------------------------
        self.play_btn = IconButton("play", 32)
        self.play_btn.setToolTip("Play / pause  (Space)")
        self.play_btn.clicked.connect(self.toggle)

        self.time_label = QLabel("0:00.0")
        self.time_label.setProperty("role", "mono")
        self.total_label = QLabel("0:00.0")
        self.total_label.setProperty("role", "faint")

        self.scrub = QSlider(Qt.Horizontal)
        self.scrub.setRange(0, 1000)
        self.scrub.setEnabled(False)
        self.scrub.sliderPressed.connect(self._scrub_start)
        self.scrub.sliderReleased.connect(self._scrub_end)
        self.scrub.valueChanged.connect(self._scrub_move)

        self.vol_icon = IconButton("volume", 28)
        self.vol_icon.setToolTip("Mute")
        self.vol_icon.clicked.connect(self._toggle_mute)

        self.volume = QSlider(Qt.Horizontal)
        self.volume.setRange(0, 100)
        self.volume.setValue(70)
        self.volume.setFixedWidth(90)
        self.volume.setToolTip("Volume")
        self.volume.valueChanged.connect(self._on_volume)

        self.exact_btn = QPushButton("Exact")
        self.exact_btn.setProperty("kind", "ghost")
        self.exact_btn.setCheckable(True)
        self.exact_btn.setCursor(Qt.PointingHandCursor)
        self.exact_btn.setToolTip(
            "Render this frame the way the export will — through libass.\n"
            "Playback uses a fast redraw; this is the exact one.")
        self.exact_btn.toggled.connect(self._on_exact_toggled)

        bar = QHBoxLayout()
        bar.setContentsMargins(0, 0, 0, 0)
        bar.setSpacing(9)
        bar.addWidget(self.play_btn)
        bar.addWidget(self.time_label)
        bar.addWidget(self.scrub, 1)
        bar.addWidget(self.total_label)
        bar.addWidget(self.vol_icon)
        bar.addWidget(self.volume)
        bar.addWidget(self.exact_btn)

        root = QVBoxLayout(self)
        root.setContentsMargins(12, 12, 12, 10)
        root.setSpacing(9)
        root.addWidget(self.canvas, 1)
        root.addLayout(bar)

        # While paused, ask for an exact libass frame shortly after things settle.
        self._exact_timer = QTimer(self)
        self._exact_timer.setSingleShot(True)
        self._exact_timer.setInterval(220)
        self._exact_timer.timeout.connect(
            lambda: self.exact_requested.emit(self.position()))

    # -- source --------------------------------------------------------------

    def open(self, path: str | Path) -> None:
        self.player.setSource(QUrl.fromLocalFile(str(Path(path).resolve())))
        self.scrub.setEnabled(True)
        self.canvas.set_message("Loading…")
        # Nudge the decoder so frame one appears without pressing play.
        self.player.play()
        QTimer.singleShot(120, self.player.pause)
        QTimer.singleShot(200, lambda: self.seek(0.0))

    def close_source(self) -> None:
        self.player.stop()
        self.player.setSource(QUrl())
        self.canvas.set_message("No video loaded")

    # -- styling -------------------------------------------------------------

    def set_renders(self, renders: list) -> None:
        """`renders` is a list of LayerRender, drawn back to front."""
        self.canvas.renders = renders
        self.canvas.update()
        if self.exact_btn.isChecked():
            self._exact_timer.start()

    # -- transport -----------------------------------------------------------

    def is_playing(self) -> bool:
        return self.player.playbackState() == QMediaPlayer.PlayingState

    def play(self) -> None:
        self.canvas.set_exact(None)
        self.player.play()

    def pause(self) -> None:
        self.player.pause()
        if self.exact_btn.isChecked():
            self._exact_timer.start()

    def toggle(self) -> None:
        self.pause() if self.is_playing() else self.play()

    def position(self) -> float:
        return self.player.position() / 1000.0

    def duration(self) -> float:
        return self._duration

    def seek(self, seconds: float) -> None:
        if self._duration <= 0:
            return
        target = max(0.0, min(seconds, self._duration))
        self.player.setPosition(int(target * 1000))
        self.canvas.set_time(target)
        self._sync_labels(target)
        if not self.is_playing() and self.exact_btn.isChecked():
            self._exact_timer.start()

    def set_exact_image(self, image: QImage | None) -> None:
        if self.exact_btn.isChecked() and not self.is_playing():
            self.canvas.set_exact(image)

    # -- internals -----------------------------------------------------------

    def _on_frame(self, frame) -> None:
        if not frame.isValid():
            return

        # Converting a decoded frame to a QImage costs real milliseconds (more at
        # 4K), and it happens on the GUI thread. If the source runs at 60 fps, or
        # the window is large, doing it for every frame starves painting and the
        # preview crawls. Displaying fewer frames is the right trade: audio and
        # the clock stay exact, the picture just updates a bit less often.
        now = time.perf_counter()
        budget = max(self._min_frame_gap, self._last_convert * 2.0)
        if self.is_playing() and (now - self._last_frame_at) < budget:
            return
        self._last_frame_at = now

        image = frame.toImage()
        self._last_convert = time.perf_counter() - now
        if image.isNull():
            return
        self.canvas.set_image(image)
        # Frame-accurate time; the position poll is far too coarse for captions.
        micros = frame.startTime()
        if micros is not None and micros >= 0:
            t = micros / 1_000_000.0
            self.canvas.set_time(t)
            if self.is_playing():
                self._sync_labels(t)
                self.position_changed.emit(t)

    def _on_duration(self, ms: int) -> None:
        self._duration = max(0.0, ms / 1000.0)
        self.total_label.setText(fmt_time(self._duration))
        self.duration_changed.emit(self._duration)

    def _on_position(self, ms: int) -> None:
        t = ms / 1000.0
        if not self.is_playing():
            self.canvas.set_time(t)
        self._sync_labels(t)
        if not self._scrubbing:
            self.position_changed.emit(t)

    def _on_state(self, _state) -> None:
        playing = self.is_playing()
        self.canvas.playing = playing
        self.play_btn.set_kind("pause" if playing else "play")
        if playing:
            self.canvas.set_exact(None)
        else:
            self.canvas.update()      # repaint once at full quality
        self.playing_changed.emit(playing)

    def _on_error(self, _error, text: str) -> None:
        self.canvas.set_message(f"Can't play this file:\n{text}")

    def _sync_labels(self, t: float) -> None:
        self.time_label.setText(fmt_time(t))
        if self._duration > 0 and not self._scrubbing:
            blocked = self.scrub.blockSignals(True)
            self.scrub.setValue(int(t / self._duration * 1000))
            self.scrub.blockSignals(blocked)

    def _scrub_start(self) -> None:
        self._scrubbing = True

    def _scrub_end(self) -> None:
        self._scrubbing = False
        self.seek(self.scrub.value() / 1000 * self._duration)

    def _scrub_move(self, value: int) -> None:
        if not self._scrubbing or self._duration <= 0:
            return
        t = value / 1000 * self._duration
        self.time_label.setText(fmt_time(t))
        self.canvas.set_time(t)
        self.player.setPosition(int(t * 1000))
        self.position_changed.emit(t)

    def _on_volume(self, value: int) -> None:
        self.audio.setVolume(value / 100.0)
        self.vol_icon.set_kind("muted" if value == 0 else "volume")

    def _toggle_mute(self) -> None:
        if self.volume.value() > 0:
            self._muted_volume = self.volume.value()
            self.volume.setValue(0)
        else:
            self.volume.setValue(int(self._muted_volume) or 70)

    def _on_exact_toggled(self, on: bool) -> None:
        if on:
            self.pause()
            self._exact_timer.start()
        else:
            self.canvas.set_exact(None)

