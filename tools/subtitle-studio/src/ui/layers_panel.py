"""Layer strip — pick which caption track the style controls are editing.

Deliberately small: a row per layer with a visibility dot, the name, and the
style it's using. Everything else about a layer is edited by the panels below,
which always act on whichever row is selected.
"""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QFrame, QHBoxLayout, QInputDialog, QLabel, QPushButton, QVBoxLayout, QWidget,
)

from ..core.model import Layer
from ..core.reveal import MODE_LABELS
from . import style


class LayerRow(QFrame):
    selected = Signal()
    toggled = Signal(bool)

    def __init__(self, layer: Layer, index: int, active: bool, parent=None):
        super().__init__(parent)
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedHeight(42)
        self._active = active
        self._enabled_state = layer.enabled

        self.dot = QPushButton("")
        self.dot.setFixedSize(16, 16)
        self.dot.setCursor(Qt.PointingHandCursor)
        self.dot.setToolTip("Show or hide this layer")
        self.dot.clicked.connect(lambda: self.toggled.emit(not self._enabled_state))
        self._paint_dot()

        name = QLabel(layer.name)
        name.setStyleSheet(
            f"color: {style.TEXT if active else style.TEXT_DIM};"
            f"font-weight: {600 if active else 500};"
            f"background: transparent;")

        detail = QLabel(f"{layer.theme.replace('_', ' ').title()}  ·  "
                        f"{MODE_LABELS.get(layer.reveal, layer.reveal).split(' —')[0]}")
        detail.setStyleSheet(f"color: {style.TEXT_FAINT}; font-size: 10px;"
                             f"background: transparent;")

        text = QVBoxLayout()
        text.setContentsMargins(0, 0, 0, 0)
        text.setSpacing(0)
        text.addWidget(name)
        text.addWidget(detail)

        row = QHBoxLayout(self)
        row.setContentsMargins(10, 4, 10, 4)
        row.setSpacing(9)
        row.addWidget(self.dot)
        row.addLayout(text, 1)

        # Scope by objectName: QLabel inherits QFrame, so a bare `QFrame { border }`
        # selector draws a box around every label inside the row too.
        self.setObjectName("LayerRow")
        self.setStyleSheet(
            f"QFrame#LayerRow {{ background: {'#221C40' if active else style.PANEL};"
            f" border: 1px solid {style.ACCENT if active else style.BORDER};"
            f" border-radius: 8px; }}")

    def _paint_dot(self) -> None:
        on = self._enabled_state
        self.dot.setStyleSheet(
            f"QPushButton {{ border-radius: 8px;"
            f" background: {style.ACCENT if on else 'transparent'};"
            f" border: 2px solid {style.ACCENT if on else style.BORDER_HI}; }}")

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.LeftButton:
            self.selected.emit()
        super().mousePressEvent(event)


class LayersPanel(QWidget):
    changed = Signal()                 # any structural change
    active_changed = Signal(int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.layers: list[Layer] = []
        self.active = 0

        head = QHBoxLayout()
        head.setContentsMargins(0, 0, 0, 0)
        title = QLabel("LAYERS")
        title.setProperty("role", "section")
        head.addWidget(title)
        head.addStretch(1)

        for text, slot, tip, width in (
            ("Add", self.add_layer,
             "Add a caption layer using the same transcript", 46),
            ("Copy", self.duplicate_layer, "Duplicate the selected layer", 50),
            ("Remove", self.delete_layer, "Delete the selected layer", 66),
        ):
            btn = QPushButton(text)
            btn.setProperty("kind", "ghost")
            btn.setCursor(Qt.PointingHandCursor)
            btn.setToolTip(tip)
            btn.clicked.connect(slot)
            btn.setFixedWidth(width)
            btn.setStyleSheet("padding: 5px 4px;")
            head.addWidget(btn)

        self.rows_box = QVBoxLayout()
        self.rows_box.setContentsMargins(0, 0, 0, 0)
        self.rows_box.setSpacing(5)

        self.hint = QLabel(
            "Press Add for a second caption track — e.g. one big word up top "
            "over a line along the bottom. Each layer keeps its own style.")
        self.hint.setProperty("role", "faint")
        self.hint.setWordWrap(True)

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(8)
        root.addLayout(head)
        root.addLayout(self.rows_box)
        root.addWidget(self.hint)

    # -- api -----------------------------------------------------------------

    def set_layers(self, layers: list[Layer], active: int = 0) -> None:
        self.layers = layers
        self.active = max(0, min(active, len(layers) - 1))
        self._rebuild_rows()

    def active_layer(self) -> Layer | None:
        if 0 <= self.active < len(self.layers):
            return self.layers[self.active]
        return None

    # -- actions -------------------------------------------------------------

    def add_layer(self) -> None:
        if not self.layers:
            return
        from ..core.layers import default_name

        source = self.layers[self.active]
        # A new layer captions the same speech, so it inherits the words but not
        # the look — otherwise it lands exactly on top of the one below it.
        fresh = Layer(
            name=default_name(self.layers),
            theme="beast" if source.theme != "beast" else "clean_pop",
            reveal="ONE_WORD" if source.reveal != "ONE_WORD" else "WORD_POP",
            words=[w for w in source.words],
            max_words=source.max_words,
            position="top" if (source.position or "bottom") != "top" else "center",
        )
        self.layers.append(fresh)
        self.active = len(self.layers) - 1
        self._rebuild_rows()
        self.active_changed.emit(self.active)
        self.changed.emit()

    def duplicate_layer(self) -> None:
        current = self.active_layer()
        if current is None:
            return
        from ..core.layers import default_name

        clone = current.copy_with(name=default_name(self.layers))
        self.layers.insert(self.active + 1, clone)
        self.active += 1
        self._rebuild_rows()
        self.active_changed.emit(self.active)
        self.changed.emit()

    def delete_layer(self) -> None:
        if len(self.layers) <= 1:
            return                       # never leave a project with no layers
        del self.layers[self.active]
        self.active = max(0, self.active - 1)
        self._rebuild_rows()
        self.active_changed.emit(self.active)
        self.changed.emit()

    def rename_active(self, parent=None) -> None:
        current = self.active_layer()
        if current is None:
            return
        name, ok = QInputDialog.getText(parent or self, "Rename layer",
                                        "Name:", text=current.name)
        if ok and name.strip():
            current.name = name.strip()
            self._rebuild_rows()
            self.changed.emit()

    def refresh(self) -> None:
        self._rebuild_rows()

    # -- internals -----------------------------------------------------------

    def _select(self, index: int) -> None:
        if index == self.active:
            return
        self.active = index
        self._rebuild_rows()
        self.active_changed.emit(index)

    def _toggle(self, index: int, on: bool) -> None:
        self.layers[index].enabled = on
        self._rebuild_rows()
        self.changed.emit()

    def _rebuild_rows(self) -> None:
        while self.rows_box.count():
            item = self.rows_box.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        for i, layer in enumerate(self.layers):
            row = LayerRow(layer, i, i == self.active)
            row.selected.connect(lambda idx=i: self._select(idx))
            row.toggled.connect(lambda on, idx=i: self._toggle(idx, on))
            self.rows_box.addWidget(row)

        # The prompt is only useful until they've made a second layer.
        self.hint.setVisible(len(self.layers) < 2)
