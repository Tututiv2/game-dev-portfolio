"""The application window and its state machine.

Laid out like an editor: player on top, timeline underneath with video, audio and
subtitle tracks, style on the right.

    EMPTY        -> drop a video
    LOADED       -> video is open and playable; style is already adjustable
    TRANSCRIBING -> Whisper is running (cancellable)
    READY        -> captions exist

Style is deliberately NOT gated on having a transcript. You can pick a look while
the video plays, using a sample caption, and it's still there when the real words
arrive.

The transcript is immutable input to the styling stage: once words exist, every
theme, mode and slider change is pure redraw and never touches the GPU (§4.6).
"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path

from PySide6.QtCore import Qt, QTimer, QUrl
from PySide6.QtGui import QImage, QPixmap
from PySide6.QtMultimedia import QSoundEffect
from PySide6.QtWidgets import (
    QComboBox, QFileDialog, QHBoxLayout, QLabel, QMainWindow, QMessageBox,
    QProgressBar, QPushButton, QSplitter, QStackedWidget, QVBoxLayout, QWidget,
)

from ..core import ffmpeg, layers as core_layers, media
from ..core.grouping import Layout
from ..core.model import Caption, Layer, Project, Word
from ..core.themes import Animation, Theme
from .drop_zone import DropZone
from .export_dialog import ExportDialog
from .player import Player
from .style_panel import StylePanel
from .timeline import TimelinePane
from .transcript import TranscriptPane
from .workers import (
    ExportWorker, MediaAssetsWorker, PreviewRenderer, ThumbnailWorker,
    TranscribeWorker,
)

VIDEO_SUFFIXES = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".wmv", ".flv",
                  ".mpg", ".mpeg", ".ts", ".m2ts"}

SAMPLE_WORDS = ["this", "is", "how", "your", "captions", "will", "look"]


def _even(n: float) -> int:
    return max(2, int(n) // 2 * 2)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("SubtitleStudio")
        self.resize(1440, 940)
        self.setMinimumSize(1100, 720)
        self.setAcceptDrops(True)

        self.video: Path | None = None
        self.info: ffmpeg.MediaInfo | None = None
        self.layers: list[Layer] = [Layer()]
        self.active_index = 0
        self.captions: list[Caption] = []      # active layer's cards, for the list
        self.language = ""
        self.state = "EMPTY"
        self._preview_size = (960, 540)
        self._transcriber: TranscribeWorker | None = None
        self._exporter: ExportWorker | None = None
        self._thumbs: ThumbnailWorker | None = None
        self._assets: MediaAssetsWorker | None = None
        self._last_seq = 0
        self._sfx_last_index = -1
        self._sound = QSoundEffect(self)

        self._build_ui()

        self.renderer = PreviewRenderer(self)
        self.renderer.rendered.connect(self._on_exact_frame)
        self.renderer.start()

        QTimer.singleShot(80, lambda: self._start_thumbnails(None))

    # ------------------------------------------------------------------ UI --

    def _build_ui(self) -> None:
        root = QWidget()
        root.setObjectName("Root")
        self.setCentralWidget(root)

        self.title_label = QLabel("SubtitleStudio")
        self.title_label.setProperty("role", "h2")
        self.file_label = QLabel("")
        self.file_label.setProperty("role", "faint")

        self.open_btn = QPushButton("Open video")
        self.open_btn.setCursor(Qt.PointingHandCursor)
        self.open_btn.clicked.connect(self._choose_file)

        self.export_btn = QPushButton("Export")
        self.export_btn.setProperty("kind", "primary")
        self.export_btn.setCursor(Qt.PointingHandCursor)
        self.export_btn.setEnabled(False)
        self.export_btn.clicked.connect(self._open_export)

        header = QHBoxLayout()
        header.setContentsMargins(18, 13, 18, 10)
        header.setSpacing(14)
        header.addWidget(self.title_label)
        header.addWidget(self.file_label, 1)
        header.addWidget(self.open_btn)
        header.addWidget(self.export_btn)

        self.drop_zone = DropZone()
        self.drop_zone.clicked.connect(self._choose_file)

        self.player = Player()
        self.player.position_changed.connect(self._on_position)
        self.player.exact_requested.connect(self._request_exact)
        self.player.playing_changed.connect(self._on_playing)

        self.timeline = TimelinePane()
        self.timeline.seeked.connect(self._seek_to)
        self.timeline.caption_clicked.connect(self._on_caption_clicked)

        self.transcript = TranscriptPane()
        self.transcript.seek_requested.connect(self._seek_to)
        self.transcript.edited.connect(self._on_transcript_edited)
        self.transcript.split_requested.connect(self._on_split)
        self.transcript.merge_requested.connect(self._on_merge)
        self.transcript.reset_cards_requested.connect(self._on_reset_cards)

        centre = QSplitter(Qt.Vertical)
        centre.addWidget(self.player)
        centre.addWidget(self.timeline)
        centre.setStretchFactor(0, 5)
        centre.setStretchFactor(1, 1)
        centre.setSizes([560, 210])
        centre.setCollapsible(0, False)

        self.style_panel = StylePanel()
        self.style_panel.setFixedWidth(348)
        self.style_panel.theme_changed.connect(self._on_theme_changed)
        self.style_panel.mode_changed.connect(self._on_mode_changed)
        self.style_panel.layout_changed.connect(self._on_layout_changed)
        self.style_panel.sfx_changed.connect(self._on_sfx_changed)
        self.style_panel.sfx_test_requested.connect(self._test_sfx)
        self.style_panel.effect_preset_chosen.connect(self._on_effect_preset)
        self.style_panel.layers_panel.changed.connect(self._on_layers_changed)
        self.style_panel.layers_panel.active_changed.connect(
            self._on_active_layer_changed)
        self.timeline.track_clicked.connect(self._on_track_clicked)

        right = QSplitter(Qt.Vertical)
        right.addWidget(self.style_panel)
        right.addWidget(self.transcript)
        right.setStretchFactor(0, 3)
        right.setStretchFactor(1, 2)
        right.setSizes([520, 300])

        editor = QWidget()
        editor.setObjectName("Root")
        editor_layout = QHBoxLayout(editor)
        editor_layout.setContentsMargins(14, 0, 14, 0)
        editor_layout.setSpacing(14)
        editor_layout.addWidget(centre, 1)
        editor_layout.addWidget(right)

        self.pages = QStackedWidget()
        self.pages.addWidget(self.drop_zone)
        self.pages.addWidget(editor)

        self.status_label = QLabel("Drop a video to begin")
        self.status_label.setProperty("role", "dim")

        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.setFixedWidth(260)
        self.progress.setVisible(False)

        self.model_combo = QComboBox()
        for label, value in (("Best quality", "large-v3"),
                             ("Fast", "distil-large-v3"),
                             ("Fastest", "small")):
            self.model_combo.addItem(label, value)
        self.model_combo.setToolTip(
            "Which speech model to use.\n"
            "Best quality is large-v3. Fast is about 6x quicker with very "
            "similar English accuracy.\nEach one downloads once, the first time "
            "you pick it.")
        self.model_combo.setFixedWidth(130)

        self.action_btn = QPushButton("Generate subtitles")
        self.action_btn.setProperty("kind", "primary")
        self.action_btn.setCursor(Qt.PointingHandCursor)
        self.action_btn.setEnabled(False)
        self.action_btn.clicked.connect(self._on_action)

        action_bar = QHBoxLayout()
        action_bar.setContentsMargins(18, 10, 18, 13)
        action_bar.setSpacing(14)
        action_bar.addWidget(self.status_label, 1)
        action_bar.addWidget(self.progress)
        action_bar.addWidget(self.model_combo)
        action_bar.addWidget(self.action_btn)

        layout = QVBoxLayout(root)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addLayout(header)
        layout.addWidget(self.pages, 1)
        layout.addLayout(action_bar)

    # --------------------------------------------------------------- state --

    def _set_state(self, state: str) -> None:
        self.state = state
        self.pages.setCurrentIndex(0 if state == "EMPTY" else 1)
        busy = state == "TRANSCRIBING"

        self.open_btn.setEnabled(not busy)
        # Style stays live even before there's a transcript — you can set the look
        # against a sample caption while the video plays.
        self.style_panel.setEnabled(state != "EMPTY")
        self.transcript.setEnabled(state == "READY")
        self.export_btn.setEnabled(state == "READY")
        self.progress.setVisible(busy)

        self.action_btn.setEnabled(state in ("LOADED", "TRANSCRIBING", "READY"))
        self.action_btn.setText({
            "EMPTY": "Generate subtitles",
            "LOADED": "Generate subtitles",
            "TRANSCRIBING": "Stop",
            "READY": "Regenerate",
        }[state])
        self.action_btn.setProperty(
            "kind", "ghost" if state in ("TRANSCRIBING", "READY") else "primary")
        self.action_btn.style().unpolish(self.action_btn)
        self.action_btn.style().polish(self.action_btn)

    def _status(self, text: str) -> None:
        self.status_label.setText(text)

    # ---------------------------------------------------------------- file --

    def dragEnterEvent(self, event) -> None:
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event) -> None:
        for url in event.mimeData().urls():
            path = Path(url.toLocalFile())
            if path.suffix.lower() in VIDEO_SUFFIXES:
                self.load_video(path)
                return
        self._status("That file type isn't a video I can read.")

    def _choose_file(self) -> None:
        exts = " ".join(f"*{s}" for s in sorted(VIDEO_SUFFIXES))
        path, _ = QFileDialog.getOpenFileName(self, "Open video", "",
                                              f"Video files ({exts});;All files (*)")
        if path:
            self.load_video(Path(path))

    def load_video(self, path: Path) -> None:
        try:
            info = ffmpeg.probe(path)
        except Exception as exc:                            # noqa: BLE001
            QMessageBox.warning(self, "Can't open that file", str(exc)[:600])
            return
        if not info.has_audio:
            QMessageBox.warning(self, "No audio",
                                "This file has no audio track, so there is "
                                "nothing to transcribe.")
            return

        self.video, self.info = path, info
        self.layers = [Layer()]
        self.active_index = 0
        self.captions = []
        self._sfx_last_index = -1

        scale = min(1280 / info.width, 720 / info.height, 1.0)
        self._preview_size = (_even(info.width * scale), _even(info.height * scale))

        self.file_label.setText(
            f"{path.name}   ·   {info.width}x{info.height}   ·   "
            f"{info.fps:g}fps   ·   {self._fmt_duration(info.duration)}")

        self.player.open(path)
        self.timeline.view.clear()
        self.timeline.view.set_duration(info.duration)
        self.transcript.set_captions([])

        if info.is_vertical:
            self.layers[0].max_words = 3
        self.style_panel.layers_panel.set_layers(self.layers, 0)
        self.style_panel.load_layer(self.layers[0])

        self._set_state("LOADED")
        self._push_style()
        self._start_assets(str(path))
        self._start_thumbnails(str(path))

        if self._try_restore():
            total = sum(len(lay.words) for lay in self.layers[:1])
            self._status(f"Restored an earlier project — {total} words, "
                         f"{len(self.layers)} layer"
                         f"{'s' if len(self.layers) != 1 else ''}. Press play.")
        else:
            self._status("Press play to watch it, or Generate subtitles to caption it.")

    @staticmethod
    def _fmt_duration(seconds: float) -> str:
        m, s = divmod(int(seconds), 60)
        h, m = divmod(m, 60)
        return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"

    def _project_path(self) -> Path:
        return self.video.with_suffix(self.video.suffix + ".subproj")

    def _try_restore(self) -> bool:
        path = self._project_path()
        if not path.is_file():
            return False
        try:
            project = Project.load(path)
            if not any(lay.words for lay in project.layers):
                return False
            self.layers = project.layers
            self.active_index = 0
            self.language = project.language
            if project.sfx:
                idx = self.style_panel.sfx_combo.findData(project.sfx)
                if idx >= 0:
                    self.style_panel.sfx_combo.setCurrentIndex(idx)
                self.style_panel.sfx_volume.set_value(project.sfx_gain * 100)
            self.style_panel.layers_panel.set_layers(self.layers, 0)
            self.style_panel.load_layer(self.layers[0])
            self._push_style()
            self._set_state("READY")
            return True
        except Exception:                                   # noqa: BLE001
            return False

    def _save_project(self) -> None:
        if not self.video or not self._has_words():
            return
        try:
            Project(source=str(self.video), layers=self.layers,
                    language=self.language, sfx=self.style_panel.sfx_name(),
                    sfx_gain=self.style_panel.sfx_gain()).save(self._project_path())
        except Exception:                                   # noqa: BLE001
            pass

    # ----------------------------------------------------------- transcribe --

    def _on_action(self) -> None:
        if self.state == "TRANSCRIBING":
            if self._transcriber:
                self._transcriber.cancel()
            self._status("Stopping…")
            return
        if self.state in ("LOADED", "READY"):
            self._transcribe()

    def _transcribe(self) -> None:
        if not self.video:
            return
        self.player.pause()
        self._set_state("TRANSCRIBING")
        self.progress.setRange(0, 0)
        self._status("Starting…")

        self._transcriber = TranscribeWorker(
            str(self.video), model=self.model_combo.currentData() or "large-v3")
        self._transcriber.stage.connect(self._status)
        self._transcriber.progress.connect(self._on_transcribe_progress)
        self._transcriber.done.connect(self._on_transcribed)
        self._transcriber.failed.connect(self._on_transcribe_failed)
        self._transcriber.finished.connect(self._on_transcribe_finished)
        self._transcriber.start()

    def _on_transcribe_progress(self, frac: float) -> None:
        if frac < 0:
            self.progress.setRange(0, 0)
        else:
            self.progress.setRange(0, 1000)
            self.progress.setValue(int(frac * 1000))

    def _on_transcribed(self, result) -> None:
        self.language = result.language
        if not result.words:
            self._status("No speech was found in this video.")
            self._set_state("LOADED")
            return
        # Every layer captions the same speech, so they all get the words — each
        # with its own copy, so editing one layer's text can't disturb another.
        for layer in self.layers:
            layer.words = deepcopy(result.words)
        self._push_style()
        self.style_panel.layers_panel.refresh()
        self._set_state("READY")
        self._save_project()
        self._status(f"{len(result.words)} words · {len(self.captions)} captions · "
                     f"{result.language or 'unknown language'} · "
                     f"{'GPU' if result.device == 'cuda' else 'CPU'} — press play.")

    def _on_transcribe_failed(self, message: str) -> None:
        self._set_state("LOADED")
        self._status("Transcription failed.")
        QMessageBox.critical(self, "Transcription failed", message[:1500])

    def _on_transcribe_finished(self) -> None:
        if self.state == "TRANSCRIBING":
            has = self._has_words()
            self._set_state("READY" if has else "LOADED")
            if not has:
                self._status("Stopped.")

    # -------------------------------------------------------------- styling --

    def active_layer(self) -> Layer:
        return self.layers[min(self.active_index, len(self.layers) - 1)]

    def _apply_panel_to_layer(self) -> None:
        """Push the style controls onto whichever layer is selected."""
        layer = self.active_layer()
        over = self.style_panel.overrides()
        layer.theme = self.style_panel.theme_name()
        layer.reveal = self.style_panel.mode()
        layer.size_pct = over["size_pct"]
        layer.margin_v_pct = over["margin_v_pct"]
        layer.max_words = int(over["max_words"])
        layer.position = over["position"]
        layer.time_offset = over["time_offset"]
        layer.pop_strength = over["pop_strength"]
        layer.pop_speed = over["pop_speed"]
        layer.tilt_deg = over["tilt_deg"]
        layer.word_fade_ms = over["word_fade_ms"]
        layer.card_fade_ms = over["card_fade_ms"]

    def current_theme(self) -> Theme:
        """The active layer's resolved theme — used by the thumbnail sampler."""
        return core_layers.resolve_theme(self.active_layer())

    def _layout_config(self) -> Layout:
        assert self.info
        return core_layers.layout_for(self.active_layer(),
                                      self.info.width, self.info.height)

    def _sample_words(self) -> list[Word]:
        """Stand-in words so the style is visible before anything is transcribed.

        Repeated across the video rather than placed once, so you can hit play,
        watch it move, and try styles against your actual footage without waiting
        for a transcript.
        """
        if not self.info:
            return []
        gap = len(SAMPLE_WORDS) * 0.32 + 1.4
        words: list[Word] = []
        start = 0.0
        while start < min(self.info.duration, 900.0):
            words += [Word(text=w, start=start + i * 0.32,
                           end=start + (i + 1) * 0.32, prob=1.0)
                      for i, w in enumerate(SAMPLE_WORDS)]
            start += gap
        return words

    def _render_layers(self) -> list:
        """Every enabled layer, grouped and themed, ready to draw or export."""
        if not self.info:
            return []
        if not self._has_words():
            sample = Layer(name="Sample", theme=self.style_panel.theme_name(),
                           reveal=self.style_panel.mode(),
                           words=self._sample_words(),
                           max_words=int(self.style_panel.overrides()["max_words"]),
                           size_pct=self.style_panel.overrides()["size_pct"],
                           margin_v_pct=self.style_panel.overrides()["margin_v_pct"],
                           position=self.style_panel.overrides()["position"])
            return [core_layers.to_render(sample, self.info.width,
                                          self.info.height, self.info.duration)]
        return [core_layers.to_render(lay, self.info.width, self.info.height,
                                      self.info.duration)
                for lay in self.layers if lay.enabled]

    def _has_words(self) -> bool:
        return any(lay.words for lay in self.layers)

    def _push_style(self) -> None:
        renders = self._render_layers()
        self.player.set_renders(renders)

        tracks = []
        for lay in self.layers:
            caps = core_layers.layer_captions(
                lay, self.info.width, self.info.height,
                self.info.duration) if self.info else []
            tracks.append((lay.name, caps, lay.enabled))
        self.timeline.view.set_tracks(tracks, self.active_index)

        active_caps = tracks[self.active_index][1] if tracks else []
        self.captions = active_caps
        self.transcript.set_captions(active_caps)

    def _rebuild(self) -> None:
        self._apply_panel_to_layer()
        self._push_style()

    def _build_ass(self, res: tuple[int, int]) -> str:
        if not self._has_words():
            renders = self._render_layers()
            from ..core.ass_writer import build_ass_layers

            return build_ass_layers(renders, res[0], res[1])
        return core_layers.build_ass_for(self.layers, res[0], res[1],
                                         self.info.duration if self.info else 0.0)

    def _on_theme_changed(self, _name: str) -> None:
        self._rebuild()
        self.style_panel.layers_panel.refresh()
        self._save_project()

    def _on_mode_changed(self, _name: str) -> None:
        self._rebuild()          # ONE_WORD / ONE_PHRASE change the grouping too
        self.style_panel.layers_panel.refresh()
        self._save_project()

    def _on_layout_changed(self) -> None:
        self._rebuild()

    def _on_effect_preset(self, name: str) -> None:
        core_layers.apply_preset(self.active_layer(), name)
        self.style_panel.load_layer(self.active_layer())
        self._push_style()
        self._status(f"Motion preset: {name}")
        self._save_project()

    def _on_layers_changed(self) -> None:
        self.layers = self.style_panel.layers_panel.layers
        self._push_style()
        self._save_project()

    def _on_active_layer_changed(self, index: int) -> None:
        self.active_index = index
        self.style_panel.load_layer(self.active_layer())
        self._push_style()

    def _on_transcript_edited(self) -> None:
        layer = self.active_layer()
        layer.words = [w for c in self.captions for w in c.words]
        self._push_style()
        self._save_project()

    def _on_split(self, card_index: int, word_index: int) -> None:
        core_layers.split_card(self.active_layer(), self.captions,
                               card_index, word_index)
        self._push_style()
        self._status("Card split — this layer's card breaks are now manual "
                     "(right-click to reset).")
        self._save_project()

    def _on_merge(self, card_index: int) -> None:
        core_layers.merge_with_next(self.active_layer(), self.captions, card_index)
        self._push_style()
        self._status("Cards merged — this layer's card breaks are now manual "
                     "(right-click to reset).")
        self._save_project()

    def _on_reset_cards(self) -> None:
        core_layers.reset_cards(self.active_layer())
        self._push_style()
        self._status("Card breaks back to automatic.")
        self._save_project()

    # -------------------------------------------------------------- playback --

    def _on_position(self, t: float) -> None:
        self.timeline.view.set_position(t)
        self.transcript.highlight_at(t)
        if self.player.is_playing():
            self._maybe_play_sfx(t)

    def _on_playing(self, playing: bool) -> None:
        if playing:
            self._sfx_last_index = self._caption_index_at(self.player.position())

    def _seek_to(self, t: float) -> None:
        self.player.seek(t)
        self.timeline.view.set_position(t)
        self.transcript.highlight_at(t)
        self._sfx_last_index = self._caption_index_at(t)
        if not self.captions:
            self._push_style()      # sample caption follows the playhead

    def _on_caption_clicked(self, track: int, index: int) -> None:
        if track != self.active_index:
            self._on_track_clicked(track)
        if 0 <= index < len(self.captions):
            self._seek_to(self.captions[index].start + 0.02)

    def _on_track_clicked(self, track: int) -> None:
        if 0 <= track < len(self.layers) and track != self.active_index:
            self.style_panel.layers_panel.set_layers(self.layers, track)
            self._on_active_layer_changed(track)

    def keyPressEvent(self, event) -> None:
        key = event.key()
        if key == Qt.Key_Space and self.video:
            self.player.toggle()
            return
        if key in (Qt.Key_Left, Qt.Key_Right) and self.video:
            step = 0.1 if event.modifiers() & Qt.ShiftModifier else 1.0
            self._seek_to(self.player.position() +
                          (-step if key == Qt.Key_Left else step))
            return
        if key == Qt.Key_Home and self.video:
            self._seek_to(0.0)
            return
        if key == Qt.Key_End and self.video and self.info:
            self._seek_to(max(0.0, self.info.duration - 0.1))
            return
        super().keyPressEvent(event)

    # ------------------------------------------------------------------ sfx --

    def _caption_index_at(self, t: float) -> int:
        for i, cap in enumerate(self.captions):
            if cap.start <= t <= cap.end:
                return i
        return -1

    def _on_sfx_changed(self, _name: str) -> None:
        path = self.style_panel.sfx_path()
        if path:
            self._sound.setSource(QUrl.fromLocalFile(str(path)))
        self._sound.setVolume(self.style_panel.sfx_gain())

    def _test_sfx(self) -> None:
        path = self.style_panel.sfx_path()
        if not path:
            self._status("Pick a sound first.")
            return
        self._sound.setSource(QUrl.fromLocalFile(str(path)))
        self._sound.setVolume(self.style_panel.sfx_gain())
        self._sound.play()

    def _maybe_play_sfx(self, t: float) -> None:
        if not self.style_panel.sfx_path():
            return
        index = self._caption_index_at(t)
        if index >= 0 and index != self._sfx_last_index:
            self._sfx_last_index = index
            if self._sound.isLoaded():
                self._sound.play()

    # -------------------------------------------------- exact (libass) frame --

    def _request_exact(self, t: float) -> None:
        if not self.video:
            return
        self._last_seq = self.renderer.request(
            str(self.video), self._build_ass(self._preview_size), t,
            *self._preview_size)

    def _on_exact_frame(self, png: bytes, _t: float, seq: int) -> None:
        if seq != self._last_seq:
            return
        image = QImage()
        if image.loadFromData(png, "PNG"):
            self.player.set_exact_image(image)

    # ------------------------------------------------------ timeline assets --

    def _start_assets(self, video: str) -> None:
        if self._assets and self._assets.isRunning():
            self._assets.cancel()
            self._assets.wait(1500)
        self._assets = MediaAssetsWorker(video, self)
        self._assets.peaks_ready.connect(self.timeline.view.set_peaks)
        self._assets.strip_ready.connect(self._on_strip)
        self._assets.start()

    def _on_strip(self, blobs: list, interval: float) -> None:
        pixmaps = []
        for blob in blobs:
            pm = QPixmap()
            if pm.loadFromData(blob, "JPG"):
                pixmaps.append(pm)
        self.timeline.view.set_thumbs(pixmaps, interval)

    def _start_thumbnails(self, video: str | None) -> None:
        if self._thumbs and self._thumbs.isRunning():
            self._thumbs.cancel()
            self._thumbs.wait(1500)
        at = (self.info.duration * 0.35) if (video and self.info) else 0.0
        self._thumbs = ThumbnailWorker(list(self.style_panel.cards), video, at, self)
        self._thumbs.ready.connect(self._on_thumbnail)
        self._thumbs.start()

    def _on_thumbnail(self, name: str, png: bytes) -> None:
        pixmap = QPixmap()
        if pixmap.loadFromData(png, "PNG"):
            self.style_panel.set_thumbnail(name, pixmap)

    # --------------------------------------------------------------- export --

    def _open_export(self) -> None:
        if not self.video or not self.captions:
            return
        self.player.pause()
        sfx = self.style_panel.sfx_name()
        dialog = ExportDialog(self.video, has_nvenc=True,
                              sfx_name=sfx.replace("_", " ").title() if sfx else "",
                              parent=self)
        dialog.start_requested.connect(lambda opts: self._start_export(dialog, opts))
        dialog.cancel_requested.connect(self._cancel_export)
        dialog.exec()

    def _start_export(self, dialog: ExportDialog, opts: dict) -> None:
        assert self.info and self.video
        dest = Path(opts["dest"])
        ass = self._build_ass((self.info.width, self.info.height))
        sfx = self.style_panel.sfx_path()

        dialog.set_running(True)
        dialog.set_progress(-1 if not opts["burn"] else 0.0, "Preparing…")

        self._exporter = ExportWorker(
            str(self.video), ass, str(dest), self.info, self.captions,
            burn=opts["burn"], mux=opts.get("mux", False),
            sidecars=opts["sidecars"],
            encoder=opts["encoder"], cq=opts["cq"],
            sfx=str(sfx) if sfx else None,
            sfx_gain=self.style_panel.sfx_gain(),
            mode=self.style_panel.mode(), parent=self)
        self._exporter.stage.connect(lambda s: dialog.set_progress(
            dialog.progress.value() / 1000, s))
        self._exporter.progress.connect(lambda f: dialog.set_progress(f, ""))
        self._exporter.done.connect(lambda p: self._export_done(dialog, p))
        self._exporter.failed.connect(lambda m: self._export_failed(dialog, m))
        self._exporter.finished.connect(lambda: dialog.set_running(False))
        self._exporter.start()

    def _cancel_export(self) -> None:
        if self._exporter:
            self._exporter.cancel()

    def _export_done(self, dialog: ExportDialog, path: str) -> None:
        dialog.set_running(False)
        dialog.accept()
        self._status(f"Exported to {path}")
        box = QMessageBox(self)
        box.setWindowTitle("Export finished")
        box.setText("Your video is ready.")
        box.setInformativeText(path)
        box.setIcon(QMessageBox.Information)
        show = box.addButton("Show in folder", QMessageBox.ActionRole)
        box.addButton("Close", QMessageBox.AcceptRole)
        box.exec()
        if box.clickedButton() is show:
            import subprocess

            target = Path(path)
            subprocess.Popen(["explorer", "/select,", str(target)]
                             if target.is_file()
                             else ["explorer", str(target.parent)])

    def _export_failed(self, dialog: ExportDialog, message: str) -> None:
        dialog.set_running(False)
        QMessageBox.critical(self, "Export failed", message[:1500])

    # ----------------------------------------------------------------- exit --

    def closeEvent(self, event) -> None:
        self.player.pause()
        for worker in (self._transcriber, self._exporter, self._thumbs, self._assets):
            if worker and worker.isRunning():
                worker.cancel()
                worker.wait(2000)
        self.renderer.stop()
        self._save_project()
        super().closeEvent(event)
