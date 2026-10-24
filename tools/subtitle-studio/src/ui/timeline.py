"""Timeline — ruler, video strip, audio waveform, caption track.

Laid out the way an editor expects: time runs left to right, tracks stack, and
the playhead is a single line through all of them. Click or drag anywhere to
scrub; the wheel zooms around the cursor.
"""

from __future__ import annotations

import bisect

from PySide6.QtCore import QPointF, QRectF, Qt, Signal
from PySide6.QtGui import (
    QColor, QFont, QFontMetricsF, QLinearGradient, QPainter, QPen, QPixmap,
)
from PySide6.QtWidgets import QFrame, QSizePolicy, QVBoxLayout, QWidget

from ..core.model import Caption
from . import style

RULER_H = 22
VIDEO_H = 46
AUDIO_H = 54
SUBS_H = 40
GAP = 6
LABEL_W = 34


def _fmt(seconds: float, coarse: bool) -> str:
    m, s = divmod(max(0.0, seconds), 60)
    if coarse:
        return f"{int(m)}:{int(s):02d}"
    return f"{int(m)}:{s:04.1f}"


class TimelineView(QWidget):
    seeked = Signal(float)
    caption_clicked = Signal(int, int)     # track index, caption index
    track_clicked = Signal(int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumHeight(RULER_H + VIDEO_H + AUDIO_H + SUBS_H + GAP * 4)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setMouseTracking(True)
        self.setCursor(Qt.PointingHandCursor)

        self.duration = 0.0
        self.position = 0.0
        # One entry per caption layer: (name, captions, enabled).
        self.tracks: list[tuple[str, list[Caption], bool]] = []
        self.active_track = 0
        self.peaks = None
        self.thumbs: list[QPixmap] = []
        self.thumb_interval = 1.0

        self._zoom = 1.0            # 1.0 = whole video fits
        self._offset = 0.0          # seconds at the left edge
        self._dragging = False
        self._hover_x = -1.0
        self._cache: QPixmap | None = None
        self._cache_key: tuple | None = None

    # -- data ----------------------------------------------------------------

    def set_duration(self, seconds: float) -> None:
        self.duration = max(0.0, seconds)
        self._zoom, self._offset = 1.0, 0.0
        self.update()

    def set_position(self, seconds: float) -> None:
        self.position = seconds
        self._follow()
        self.update()

    def set_tracks(self, tracks: list, active: int = 0) -> None:
        self.tracks = tracks
        self.active_track = active
        self.setMinimumHeight(self._needed_height())
        self.updateGeometry()
        self.update()

    def _needed_height(self) -> int:
        rows = max(1, len(self.tracks))
        return RULER_H + VIDEO_H + AUDIO_H + rows * SUBS_H + GAP * (3 + rows)

    def set_peaks(self, peaks) -> None:
        self.peaks = peaks
        self.update()

    def set_thumbs(self, pixmaps: list[QPixmap], interval: float) -> None:
        self.thumbs = pixmaps
        self.thumb_interval = max(0.05, interval)
        self.update()

    def clear(self) -> None:
        self.tracks, self.thumbs, self.peaks = [], [], None
        self.duration = self.position = 0.0
        self.update()

    # -- geometry ------------------------------------------------------------

    @property
    def _track_w(self) -> float:
        return max(1.0, self.width() - LABEL_W - 8)

    def _visible(self) -> float:
        return self.duration / self._zoom if self.duration else 1.0

    def x_of(self, t: float) -> float:
        return LABEL_W + (t - self._offset) / self._visible() * self._track_w

    def t_of(self, x: float) -> float:
        return self._offset + (x - LABEL_W) / self._track_w * self._visible()

    def _clamp_offset(self) -> None:
        self._offset = max(0.0, min(self._offset,
                                    max(0.0, self.duration - self._visible())))

    def _follow(self) -> None:
        """Keep the playhead on screen while zoomed in."""
        if self._zoom <= 1.0 or not self.duration:
            return
        span = self._visible()
        if self.position < self._offset + span * 0.08:
            self._offset = self.position - span * 0.08
        elif self.position > self._offset + span * 0.92:
            self._offset = self.position - span * 0.92
        self._clamp_offset()

    # -- interaction ---------------------------------------------------------

    def mousePressEvent(self, event) -> None:
        if event.button() != Qt.LeftButton:
            return
        self._dragging = True
        t = max(0.0, min(self.t_of(event.position().x()), self.duration))
        y = event.position().y()
        subs_top = RULER_H + GAP + VIDEO_H + GAP + AUDIO_H + GAP
        for row, (_name, captions, _on) in enumerate(self.tracks):
            top = subs_top + row * (SUBS_H + GAP)
            if top <= y <= top + SUBS_H:
                self.track_clicked.emit(row)
                for i, cap in enumerate(captions):
                    if cap.start <= t <= cap.end:
                        self.caption_clicked.emit(row, i)
                        break
                break
        self.seeked.emit(t)

    def mouseMoveEvent(self, event) -> None:
        self._hover_x = event.position().x()
        if self._dragging:
            t = max(0.0, min(self.t_of(self._hover_x), self.duration))
            self.seeked.emit(t)
        self.update()

    def mouseReleaseEvent(self, _event) -> None:
        self._dragging = False

    def leaveEvent(self, _event) -> None:
        self._hover_x = -1.0
        self.update()

    def wheelEvent(self, event) -> None:
        if not self.duration:
            return
        anchor = self.t_of(event.position().x())
        step = 1.25 if event.angleDelta().y() > 0 else 1 / 1.25
        self._zoom = max(1.0, min(self._zoom * step, 400.0))
        # Keep the time under the cursor pinned while zooming.
        self._offset = anchor - (event.position().x() - LABEL_W) / \
            self._track_w * self._visible()
        self._clamp_offset()
        self.update()

    # -- painting ------------------------------------------------------------

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        try:
            self._paint(p)
        finally:
            p.end()

    # -- static layer cache --------------------------------------------------
    #
    # The ruler, filmstrip, waveform and caption blocks only change when the
    # data, the zoom or the size changes — but they were being redrawn on every
    # playhead update, which meant ~15-20 ms of work per video frame and a
    # preview that could not keep up. Render them once into a pixmap; per frame
    # we then blit it and draw only the playhead and the live caption.

    def _static_key(self) -> tuple:
        return (
            self.width(), self.height(), round(self._zoom, 4),
            round(self._offset, 4), self.duration,
            id(self.peaks), len(self.thumbs), self.active_track,
            tuple((name, len(caps), on) for name, caps, on in self.tracks),
        )

    def invalidate(self) -> None:
        self._cache = None

    def _static_pixmap(self) -> QPixmap:
        key = self._static_key()
        if self._cache is not None and self._cache_key == key:
            return self._cache

        ratio = self.devicePixelRatioF()
        pm = QPixmap(int(self.width() * ratio), int(self.height() * ratio))
        pm.setDevicePixelRatio(ratio)
        pm.fill(QColor(style.PANEL))

        p = QPainter(pm)
        try:
            p.setRenderHint(QPainter.Antialiasing, True)
            y = 0.0
            self._paint_ruler(p, y)
            y += RULER_H + GAP
            self._paint_video(p, y)
            y += VIDEO_H + GAP
            self._paint_audio(p, y)
            y += AUDIO_H + GAP
            if self.tracks:
                for row, (name, captions, enabled) in enumerate(self.tracks):
                    self._paint_subs(p, y, row, name, captions, enabled)
                    y += SUBS_H + GAP
            else:
                self._paint_subs(p, y, 0, "S", [], True)
        finally:
            p.end()

        self._cache, self._cache_key = pm, key
        return pm

    def _live_caption_rects(self):
        """Where the caption under the playhead sits, per track."""
        y = RULER_H + GAP + VIDEO_H + GAP + AUDIO_H + GAP
        for _name, captions, enabled in self.tracks:
            if enabled:
                for cap in captions:
                    if cap.start <= self.position <= cap.end:
                        x0, x1 = self.x_of(cap.start), self.x_of(cap.end)
                        yield cap, QRectF(x0, y + 5,
                                          max(2.0, x1 - x0 - 1.5), SUBS_H - 10)
                        break
            y += SUBS_H + GAP

    def _paint(self, p: QPainter) -> None:
        if self.duration <= 0:
            p.fillRect(self.rect(), QColor(style.PANEL))
            p.setPen(QColor(style.TEXT_FAINT))
            p.drawText(self.rect(), Qt.AlignCenter, "Timeline")
            return

        p.drawPixmap(0, 0, self._static_pixmap())
        p.setRenderHint(QPainter.Antialiasing, True)

        f = p.font()
        f.setPixelSize(10)
        f.setWeight(QFont.Weight.Medium)
        p.setFont(f)
        metrics = QFontMetricsF(f)
        p.setClipRect(QRectF(LABEL_W, 0, self._track_w, self.height()))
        for cap, block in self._live_caption_rects():
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(style.ACCENT))
            p.drawRoundedRect(block, 4, 4)
            if block.width() > 26:
                p.setPen(QColor("#FFFFFF"))
                p.drawText(block.adjusted(5, 0, -5, 0),
                           Qt.AlignLeft | Qt.AlignVCenter,
                           metrics.elidedText(cap.text, Qt.ElideRight,
                                              block.width() - 10))
        p.setClipping(False)

        self._paint_playhead(p)

    def _label(self, p: QPainter, text: str, y: float, h: float) -> None:
        p.setPen(QColor(style.TEXT_FAINT))
        f = p.font()
        f.setPixelSize(9)
        f.setWeight(QFont.Weight.DemiBold)
        p.setFont(f)
        p.drawText(QRectF(4, y, LABEL_W - 8, h), Qt.AlignLeft | Qt.AlignVCenter, text)

    def _paint_ruler(self, p: QPainter, y: float) -> None:
        span = self._visible()
        # Choose a tick spacing that lands on human numbers.
        for step in (0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600):
            if span / step <= 12:
                break
        f = p.font()
        f.setPixelSize(9)
        f.setWeight(QFont.Weight.Normal)
        p.setFont(f)

        first = int(self._offset / step) * step
        t = first
        while t <= self._offset + span + step:
            x = self.x_of(t)
            if x >= LABEL_W - 1:
                p.setPen(QColor("#2E2E3A"))
                p.drawLine(QPointF(x, y + 13), QPointF(x, y + RULER_H))
                p.setPen(QColor(style.TEXT_FAINT))
                p.drawText(QPointF(x + 3, y + 10), _fmt(t, step >= 1))
            t += step

    def _paint_video(self, p: QPainter, y: float) -> None:
        self._label(p, "V", y, VIDEO_H)
        track = QRectF(LABEL_W, y, self._track_w, VIDEO_H)
        p.setClipRect(track)
        p.fillRect(track, QColor("#101018"))

        if self.thumbs:
            tw = self.thumbs[0].width() * (VIDEO_H / max(1, self.thumbs[0].height()))
            x = LABEL_W
            while x < track.right():
                t = self.t_of(x)
                idx = int(t / self.thumb_interval)
                if 0 <= idx < len(self.thumbs):
                    pm = self.thumbs[idx]
                    p.drawPixmap(QRectF(x, y, tw, VIDEO_H), pm,
                                 QRectF(pm.rect()))
                x += max(8.0, tw)
        else:
            p.setPen(QColor(style.TEXT_FAINT))
            p.drawText(track, Qt.AlignCenter, "…")

        p.setClipping(False)
        p.setPen(QColor(style.BORDER))
        p.drawRect(track)

    def _paint_audio(self, p: QPainter, y: float) -> None:
        self._label(p, "A", y, AUDIO_H)
        track = QRectF(LABEL_W, y, self._track_w, AUDIO_H)
        p.fillRect(track, QColor("#0F1418"))
        p.setClipRect(track)

        if self.peaks is not None and len(self.peaks):
            mid = y + AUDIO_H / 2
            n = len(self.peaks)
            grad = QLinearGradient(0, y, 0, y + AUDIO_H)
            grad.setColorAt(0.0, QColor("#3DD68C"))
            grad.setColorAt(0.5, QColor("#2FA76D"))
            grad.setColorAt(1.0, QColor("#3DD68C"))
            p.setPen(QPen(QColor("#35C783"), 1))
            span = self._visible()
            for px in range(int(track.left()), int(track.right())):
                t0 = self._offset + (px - LABEL_W) / self._track_w * span
                t1 = t0 + span / self._track_w
                i0 = max(0, min(n - 1, int(t0 / self.duration * n)))
                i1 = max(i0 + 1, min(n, int(t1 / self.duration * n)))
                amp = float(self.peaks[i0:i1].max()) if i1 > i0 else 0.0
                h = amp * (AUDIO_H / 2 - 3)
                p.drawLine(QPointF(px, mid - h), QPointF(px, mid + h))
        else:
            p.setPen(QColor(style.TEXT_FAINT))
            p.drawText(track, Qt.AlignCenter, "…")

        p.setClipping(False)
        p.setPen(QColor(style.BORDER))
        p.drawRect(track)

    def _paint_subs(self, p: QPainter, y: float, row: int, name: str,
                    captions: list[Caption], enabled: bool) -> None:
        active = row == self.active_track
        self._label(p, f"S{row + 1}" if self.tracks else "S", y, SUBS_H)
        track = QRectF(LABEL_W, y, self._track_w, SUBS_H)
        p.fillRect(track, QColor("#171128" if active else "#141020"))
        p.setClipRect(track)

        f = p.font()
        f.setPixelSize(10)
        f.setWeight(QFont.Weight.Medium)
        p.setFont(f)
        metrics = QFontMetricsF(f)

        base = QColor("#3A2F72") if enabled else QColor("#2A2A34")
        starts = [c.start for c in captions]
        lo = max(0, bisect.bisect_left(starts, self._offset) - 1)
        for cap in captions[lo:]:
            if cap.start > self._offset + self._visible():
                break
            x0, x1 = self.x_of(cap.start), self.x_of(cap.end)
            if x1 < LABEL_W:
                continue
            # Leave a sliver between blocks; consecutive captions nearly touch,
            # and without a gap 60 of them read as one solid bar.
            block = QRectF(x0, y + 5, max(2.0, x1 - x0 - 1.5), SUBS_H - 10)
            # No live highlight here: this goes into the cached static layer, and
            # the caption under the playhead is drawn fresh on top each frame.
            p.setPen(Qt.NoPen)
            p.setBrush(base)
            p.drawRoundedRect(block, 4, 4)
            if block.width() > 26:
                p.setPen(QColor("#C9C2E8" if enabled else "#6A6A78"))
                text = metrics.elidedText(cap.text, Qt.ElideRight,
                                          block.width() - 10)
                p.drawText(block.adjusted(5, 0, -5, 0),
                           Qt.AlignLeft | Qt.AlignVCenter, text)

        if not captions:
            p.setPen(QColor(style.TEXT_FAINT))
            p.drawText(track, Qt.AlignCenter,
                       name if name else "no captions yet")

        p.setClipping(False)
        p.setPen(QColor(style.ACCENT if active else style.BORDER))
        p.drawRect(track)

    def _paint_playhead(self, p: QPainter) -> None:
        if self._hover_x > LABEL_W:
            p.setPen(QPen(QColor(255, 255, 255, 40), 1))
            p.drawLine(QPointF(self._hover_x, RULER_H),
                       QPointF(self._hover_x, self.height()))

        x = self.x_of(self.position)
        if x < LABEL_W - 2 or x > self.width():
            return
        p.setPen(QPen(QColor("#FF4D6D"), 2))
        p.drawLine(QPointF(x, RULER_H - 6), QPointF(x, self.height()))
        p.setBrush(QColor("#FF4D6D"))
        p.setPen(Qt.NoPen)
        p.drawEllipse(QPointF(x, RULER_H - 6), 4.5, 4.5)


class TimelinePane(QFrame):
    """Framed wrapper so the timeline matches the other panels."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setProperty("role", "panel")
        self.view = TimelineView()
        root = QVBoxLayout(self)
        root.setContentsMargins(10, 8, 10, 8)
        root.addWidget(self.view)

    @property
    def seeked(self):
        return self.view.seeked

    @property
    def caption_clicked(self):
        return self.view.caption_clicked

    @property
    def track_clicked(self):
        return self.view.track_clicked
