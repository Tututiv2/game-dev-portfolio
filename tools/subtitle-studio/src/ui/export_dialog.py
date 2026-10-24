"""Export panel — BUILD_PROMPT.md §6, trimmed to what a person actually chooses.

The Resolve Deliver page is the model, but Resolve is a full NLE; here the only
decisions that matter are where it goes, whether the captions are baked into the
pixels, and how good it needs to look. Everything else has a right answer and is
chosen for you.
"""

from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QButtonGroup, QCheckBox, QDialog, QFileDialog, QFrame, QHBoxLayout, QLabel,
    QLineEdit, QProgressBar, QPushButton, QRadioButton, QVBoxLayout, QWidget,
)

from . import style

QUALITY = {
    "Good": 25,
    "High": 21,
    "Best": 17,
}


class ExportDialog(QDialog):
    """Collects the choices, then shows progress in place."""

    start_requested = Signal(dict)
    cancel_requested = Signal()

    def __init__(self, source: Path, has_nvenc: bool = True,
                 sfx_name: str = "", parent=None):
        super().__init__(parent)
        self.setWindowTitle("Export")
        self.setMinimumWidth(520)
        self.setStyleSheet(style.qss())

        self._source = source
        self._running = False

        title = QLabel("Export")
        title.setProperty("role", "h1")

        # --- destination ----------------------------------------------------
        self.path_edit = QLineEdit(str(source.with_name(source.stem + "_subtitled.mp4")))
        browse = QPushButton("Browse")
        browse.setProperty("kind", "ghost")
        browse.clicked.connect(self._browse)
        dest_row = QHBoxLayout()
        dest_row.setSpacing(8)
        dest_row.addWidget(self.path_edit, 1)
        dest_row.addWidget(browse)

        # --- what to produce ------------------------------------------------
        self.burn_radio = QRadioButton("Burn captions into the video")
        self.burn_radio.setChecked(True)
        burn_help = QLabel("Baked into the pixels — plays anywhere, can't be "
                           "switched off. Re-encodes the video.")
        burn_help.setProperty("role", "faint")
        burn_help.setWordWrap(True)

        self.sidecar_radio = QRadioButton("Subtitle file only")
        side_help = QLabel("Writes .srt / .ass next to the video and leaves the "
                           "video untouched. Takes about a second.")
        side_help.setProperty("role", "faint")
        side_help.setWordWrap(True)

        self.mux_radio = QRadioButton("Switchable subtitle track (.mkv)")
        mux_help = QLabel("Keeps the styling but lets the viewer turn captions "
                          "off, like a DVD. No re-encode, so it's quick — "
                          "must be saved as .mkv.")
        mux_help.setProperty("role", "faint")
        mux_help.setWordWrap(True)

        group = QButtonGroup(self)
        group.addButton(self.burn_radio)
        group.addButton(self.sidecar_radio)
        group.addButton(self.mux_radio)
        group.buttonClicked.connect(self._sync_enabled)

        self.also_srt = QCheckBox("Also save separate subtitle files")
        self.also_srt.setChecked(False)
        self.also_srt.setToolTip(
            "Writes .ass / .srt / .vtt alongside the video.\n"
            "When you burn captions in, these go into a subfolder — a subtitle "
            "file sharing the video's name gets auto-loaded by VLC and friends, "
            "and you'd see two sets of captions at once.")

        # --- quality --------------------------------------------------------
        self.quality_buttons: dict[str, QPushButton] = {}
        quality_row = QHBoxLayout()
        quality_row.setSpacing(4)
        qgroup = QButtonGroup(self)
        for name in QUALITY:
            b = QPushButton(name)
            b.setProperty("kind", "segment")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            b.setChecked(name == "High")
            qgroup.addButton(b)
            self.quality_buttons[name] = b
            quality_row.addWidget(b)
        quality_holder = QFrame()
        quality_holder.setProperty("role", "panel")
        qh = QHBoxLayout(quality_holder)
        qh.setContentsMargins(5, 5, 5, 5)
        qh.addLayout(quality_row)

        detail = "GPU encoding (NVENC)" if has_nvenc else "CPU encoding"
        if sfx_name:
            detail += f"   ·   caption sound: {sfx_name} (mixed into the audio)"
        self.encoder_label = QLabel(detail)
        self.encoder_label.setProperty("role", "faint")
        self.encoder_label.setWordWrap(True)
        self._has_nvenc = has_nvenc

        # --- progress -------------------------------------------------------
        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.setVisible(False)
        self.status = QLabel("")
        self.status.setProperty("role", "dim")
        self.status.setVisible(False)

        # --- buttons --------------------------------------------------------
        self.cancel_btn = QPushButton("Cancel")
        self.cancel_btn.setProperty("kind", "ghost")
        self.cancel_btn.clicked.connect(self._on_cancel)
        self.go_btn = QPushButton("Export")
        self.go_btn.setProperty("kind", "primary")
        self.go_btn.setDefault(True)
        self.go_btn.clicked.connect(self._on_go)

        buttons = QHBoxLayout()
        buttons.addStretch(1)
        buttons.addWidget(self.cancel_btn)
        buttons.addWidget(self.go_btn)

        root = QVBoxLayout(self)
        root.setContentsMargins(22, 20, 22, 18)
        root.setSpacing(14)
        root.addWidget(title)
        root.addWidget(self._label("SAVE TO"))
        root.addLayout(dest_row)
        root.addWidget(self._label("OUTPUT"))
        root.addWidget(self.burn_radio)
        root.addWidget(burn_help)
        root.addWidget(self.sidecar_radio)
        root.addWidget(side_help)
        root.addWidget(self.mux_radio)
        root.addWidget(mux_help)
        root.addWidget(self.also_srt)
        self.quality_section = QWidget()
        qs = QVBoxLayout(self.quality_section)
        qs.setContentsMargins(0, 0, 0, 0)
        qs.setSpacing(8)
        qs.addWidget(self._label("QUALITY"))
        qs.addWidget(quality_holder)
        qs.addWidget(self.encoder_label)
        root.addWidget(self.quality_section)
        root.addWidget(self.progress)
        root.addWidget(self.status)
        root.addLayout(buttons)

    @staticmethod
    def _label(text: str) -> QLabel:
        lab = QLabel(text)
        lab.setProperty("role", "section")
        return lab

    # -- interaction ---------------------------------------------------------

    def _browse(self) -> None:
        path, _ = QFileDialog.getSaveFileName(
            self, "Export to", self.path_edit.text(),
            "MP4 video (*.mp4);;Matroska (*.mkv);;QuickTime (*.mov)")
        if path:
            self.path_edit.setText(path)

    def _sync_enabled(self) -> None:
        self.quality_section.setVisible(self.burn_radio.isChecked())
        if self.mux_radio.isChecked():
            # A styled subtitle track only survives in Matroska, so point the
            # filename there rather than failing after they press Export.
            path = Path(self.path_edit.text())
            if path.suffix.lower() != ".mkv":
                self.path_edit.setText(str(path.with_suffix(".mkv")))
        self.adjustSize()

    def _on_go(self) -> None:
        if self._running:
            return
        quality = next(n for n, b in self.quality_buttons.items() if b.isChecked())
        # Only write sidecars when they were actually asked for. Emitting them
        # next to a burned-in video is what made players show captions twice.
        sidecars: list[str] = []
        if self.sidecar_radio.isChecked():
            sidecars = ["ass", "srt", "vtt"]
        elif self.also_srt.isChecked():
            sidecars = ["ass", "srt", "vtt"]
        self.start_requested.emit({
            "dest": self.path_edit.text(),
            "burn": self.burn_radio.isChecked(),
            "mux": self.mux_radio.isChecked(),
            "sidecars": tuple(sidecars),
            "cq": QUALITY[quality],
            "encoder": "h264_nvenc" if self._has_nvenc else "libx264",
        })

    def _on_cancel(self) -> None:
        if self._running:
            self.cancel_requested.emit()
        else:
            self.reject()

    # -- state ---------------------------------------------------------------

    def set_running(self, running: bool) -> None:
        self._running = running
        self.progress.setVisible(running)
        self.status.setVisible(running)
        self.go_btn.setEnabled(not running)
        self.go_btn.setText("Exporting…" if running else "Export")
        self.cancel_btn.setText("Stop" if running else "Cancel")
        self.path_edit.setEnabled(not running)

    def set_progress(self, frac: float, text: str = "") -> None:
        if frac < 0:
            self.progress.setRange(0, 0)
        else:
            self.progress.setRange(0, 1000)
            self.progress.setValue(int(frac * 1000))
        if text:
            self.status.setText(text)
