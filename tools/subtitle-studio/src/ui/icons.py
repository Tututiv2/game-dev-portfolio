"""Transport icons drawn with QPainter.

Not glyphs: ▶ ❚❚ 🔊 are missing from most UI typefaces, and a missing glyph
renders as a hollow box — which is exactly what a Play button must never look
like. Drawing them costs nothing and always works.
"""

from __future__ import annotations

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QPainter, QPainterPath, QPolygonF
from PySide6.QtWidgets import QPushButton

from . import style


class IconButton(QPushButton):
    KINDS = ("play", "pause", "volume", "muted", "exact")

    def __init__(self, kind: str = "play", size: int = 34, parent=None):
        super().__init__(parent)
        self._kind = kind
        self._size = size
        self.setFixedSize(size + 10, size)
        self.setCursor(Qt.PointingHandCursor)
        self.setFlat(True)
        self.setStyleSheet(
            "QPushButton { background: transparent; border: none; }")

    def set_kind(self, kind: str) -> None:
        if kind != self._kind:
            self._kind = kind
            self.update()

    def kind(self) -> str:
        return self._kind

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        try:
            self._paint(p)
        finally:
            p.end()

    def _paint(self, p: QPainter) -> None:
        p.setRenderHint(QPainter.Antialiasing, True)
        r = QRectF(self.rect())
        colour = QColor(style.TEXT if self.underMouse() else style.TEXT_DIM)
        if not self.isEnabled():
            colour = QColor(style.TEXT_FAINT)
        p.setPen(Qt.NoPen)
        p.setBrush(colour)

        cx, cy = r.center().x(), r.center().y()
        s = self._size * 0.42

        if self._kind == "play":
            tri = QPolygonF([QPointF(cx - s * 0.5, cy - s),
                             QPointF(cx + s * 0.85, cy),
                             QPointF(cx - s * 0.5, cy + s)])
            path = QPainterPath()
            path.addPolygon(tri)
            path.closeSubpath()
            p.drawPath(path)
        elif self._kind == "pause":
            w = s * 0.42
            p.drawRoundedRect(QRectF(cx - s * 0.62, cy - s, w, s * 2), 1.5, 1.5)
            p.drawRoundedRect(QRectF(cx + s * 0.2, cy - s, w, s * 2), 1.5, 1.5)
        elif self._kind in ("volume", "muted"):
            body = QPolygonF([
                QPointF(cx - s * 0.9, cy - s * 0.35),
                QPointF(cx - s * 0.35, cy - s * 0.35),
                QPointF(cx + s * 0.15, cy - s * 0.9),
                QPointF(cx + s * 0.15, cy + s * 0.9),
                QPointF(cx - s * 0.35, cy + s * 0.35),
                QPointF(cx - s * 0.9, cy + s * 0.35),
            ])
            path = QPainterPath()
            path.addPolygon(body)
            path.closeSubpath()
            p.drawPath(path)
            pen = p.pen()
            p.setBrush(Qt.NoBrush)
            from PySide6.QtGui import QPen

            p.setPen(QPen(colour, 1.6, Qt.SolidLine, Qt.RoundCap))
            if self._kind == "volume":
                p.drawArc(QRectF(cx + s * 0.1, cy - s * 0.6, s * 0.8, s * 1.2),
                          -60 * 16, 120 * 16)
            else:
                p.drawLine(QPointF(cx + s * 0.45, cy - s * 0.45),
                           QPointF(cx + s * 1.0, cy + s * 0.45))
                p.drawLine(QPointF(cx + s * 1.0, cy - s * 0.45),
                           QPointF(cx + s * 0.45, cy + s * 0.45))
            p.setPen(pen)
