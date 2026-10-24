"""Empty state."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QFrame, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget,
)


class DropZone(QWidget):
    clicked = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("Root")

        card = QFrame()
        card.setProperty("role", "panel")
        card.setMinimumSize(540, 310)
        card.setMaximumWidth(760)

        mark = QLabel("Subtitles, automatically")
        mark.setProperty("role", "h1")
        mark.setAlignment(Qt.AlignCenter)

        sub = QLabel("Drop a video anywhere in this window")
        sub.setProperty("role", "dim")
        sub.setAlignment(Qt.AlignCenter)

        detail = QLabel("Everything runs on your own PC. Nothing is uploaded, "
                        "there is no account, and it costs nothing to use.")
        detail.setProperty("role", "faint")
        detail.setAlignment(Qt.AlignCenter)
        detail.setWordWrap(True)

        browse = QPushButton("Choose a video")
        browse.setProperty("kind", "primary")
        browse.setCursor(Qt.PointingHandCursor)
        browse.clicked.connect(self.clicked)

        inner = QVBoxLayout(card)
        inner.setContentsMargins(48, 44, 48, 44)
        inner.setSpacing(13)
        inner.addStretch(1)
        inner.addWidget(mark)
        inner.addWidget(sub)
        inner.addSpacing(6)
        inner.addWidget(detail)
        inner.addSpacing(18)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(browse)
        row.addStretch(1)
        inner.addLayout(row)
        inner.addStretch(1)

        root = QVBoxLayout(self)
        root.addStretch(1)
        centre = QHBoxLayout()
        centre.addStretch(1)
        centre.addWidget(card)
        centre.addStretch(1)
        root.addLayout(centre)
        root.addStretch(1)
