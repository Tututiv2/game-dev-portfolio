"""Transcript list — click to seek, double-click to fix a word.

Whisper gets names, numbers and jargon wrong; that's expected and the transcript
is meant to be edited (§3.4). Editing text never invalidates the word timings we
already have, because the edit is applied to the words in place.
"""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QAbstractItemView, QFrame, QHBoxLayout, QInputDialog, QLabel, QListWidget,
    QListWidgetItem, QMenu, QVBoxLayout,
)

from ..core.model import Caption, Word
from . import style

LOW_CONFIDENCE = 0.55


def _fmt(seconds: float) -> str:
    """Captions live at sub-second resolution — whole seconds would show two
    consecutive cards as the same time."""
    m, s = divmod(seconds, 60)
    return f"{int(m)}:{s:04.1f}"


class TranscriptPane(QFrame):
    seek_requested = Signal(float)
    edited = Signal()
    split_requested = Signal(int, int)      # card index, word index
    merge_requested = Signal(int)           # card index (joins with the next)
    reset_cards_requested = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setProperty("role", "panel")
        self._captions: list[Caption] = []

        header = QHBoxLayout()
        title = QLabel("TRANSCRIPT")
        title.setProperty("role", "section")
        self.count_label = QLabel("")
        self.count_label.setProperty("role", "faint")
        header.addWidget(title)
        header.addStretch(1)
        header.addWidget(self.count_label)

        self.list = QListWidget()
        self.list.setSelectionMode(QAbstractItemView.SingleSelection)
        self.list.setVerticalScrollMode(QAbstractItemView.ScrollPerPixel)
        self.list.itemClicked.connect(self._on_click)
        self.list.itemDoubleClicked.connect(self._on_edit)
        self.list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.list.customContextMenuRequested.connect(self._on_menu)

        hint = QLabel("Click to jump  ·  double-click to edit  ·  "
                      "right-click to split or merge")
        hint.setProperty("role", "faint")

        root = QVBoxLayout(self)
        root.setContentsMargins(14, 12, 10, 12)
        root.setSpacing(9)
        root.addLayout(header)
        root.addWidget(self.list, 1)
        root.addWidget(hint)

    # -- api -----------------------------------------------------------------

    def set_captions(self, captions: list[Caption]) -> None:
        self._captions = captions
        self.list.clear()
        for i, cap in enumerate(captions):
            item = QListWidgetItem(f"{_fmt(cap.start)}   {cap.text}")
            item.setData(Qt.UserRole, i)
            if any(w.prob < LOW_CONFIDENCE for w in cap.words):
                item.setToolTip("Whisper wasn't confident here — worth a look")
                item.setForeground(Qt.yellow)
            self.list.addItem(item)
        self.count_label.setText(f"{len(captions)} cards")

    def highlight_at(self, t: float) -> None:
        """Follow playback position without stealing focus."""
        for i, cap in enumerate(self._captions):
            if cap.start <= t <= cap.end:
                if self.list.currentRow() != i:
                    blocked = self.list.blockSignals(True)
                    self.list.setCurrentRow(i)
                    self.list.scrollToItem(self.list.item(i),
                                           QAbstractItemView.PositionAtCenter)
                    self.list.blockSignals(blocked)
                return

    # -- internals -----------------------------------------------------------

    def _on_click(self, item: QListWidgetItem) -> None:
        idx = item.data(Qt.UserRole)
        if idx is not None and 0 <= idx < len(self._captions):
            self.seek_requested.emit(self._captions[idx].start + 0.05)

    def _on_menu(self, point) -> None:
        item = self.list.itemAt(point)
        if item is None:
            return
        index = item.data(Qt.UserRole)
        if index is None or not (0 <= index < len(self._captions)):
            return
        cap = self._captions[index]

        menu = QMenu(self)
        menu.setStyleSheet(self.styleSheet())

        if len(cap.words) > 1:
            split = menu.addMenu("Split before…")
            for i in range(1, len(cap.words)):
                action = split.addAction(cap.words[i].text)
                action.triggered.connect(
                    lambda _c=False, ci=index, wi=i: self.split_requested.emit(ci, wi))
        else:
            disabled = menu.addAction("Split (needs 2+ words)")
            disabled.setEnabled(False)

        merge = menu.addAction("Merge with the next card")
        merge.setEnabled(index < len(self._captions) - 1)
        merge.triggered.connect(lambda _c=False, ci=index:
                                self.merge_requested.emit(ci))

        menu.addSeparator()
        edit = menu.addAction("Edit words…")
        edit.triggered.connect(lambda _c=False, it=item: self._on_edit(it))
        reset = menu.addAction("Reset all cards to automatic")
        reset.triggered.connect(lambda _c=False: self.reset_cards_requested.emit())

        menu.exec(self.list.viewport().mapToGlobal(point))

    def _on_edit(self, item: QListWidgetItem) -> None:
        idx = item.data(Qt.UserRole)
        if idx is None or not (0 <= idx < len(self._captions)):
            return
        cap = self._captions[idx]
        text, ok = QInputDialog.getText(self, "Edit caption",
                                        "Words (spaces separate them):",
                                        text=cap.text)
        if not ok or not text.strip():
            return
        self._retime(cap, text.split())
        item.setText(f"{_fmt(cap.start)}   {cap.text}")
        self.edited.emit()

    @staticmethod
    def _retime(cap: Caption, new_words: list[str]) -> None:
        """Re-fit edited text onto the card's existing time span.

        If the word count is unchanged we keep every original timing exactly —
        that's the common case (a spelling fix) and it must not degrade sync.
        Otherwise we distribute the card's span across the new words weighted by
        length, which keeps the karaoke highlight plausible rather than exact.
        """
        old = cap.words
        if len(new_words) == len(old):
            for w, text in zip(old, new_words):
                w.text = text
            return

        span_start = old[0].start
        span_end = max(old[-1].end, span_start + 0.2)
        total = sum(max(1, len(w)) for w in new_words)
        cursor = span_start
        rebuilt: list[Word] = []
        for text in new_words:
            share = (span_end - span_start) * max(1, len(text)) / total
            rebuilt.append(Word(text=text, start=cursor,
                                end=min(cursor + share, span_end), prob=1.0))
            cursor += share
        cap.words = rebuilt
        cap.line_starts = [i for i in cap.line_starts if 0 < i < len(rebuilt)]
