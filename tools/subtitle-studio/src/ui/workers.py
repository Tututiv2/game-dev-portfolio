"""Background threads — BUILD_PROMPT.md §7.

Every ffmpeg call and every transcription runs here. Nothing in this file may
block the GUI thread; anything that does makes Windows paint the window
"(Not Responding)".

`ui/` must not shell out to ffmpeg directly — these workers call `core/`.
"""

from __future__ import annotations

import threading
import traceback
from pathlib import Path

from PySide6.QtCore import QMutex, QMutexLocker, QThread, QWaitCondition, Signal

from ..core import ffmpeg
from ..core.ass_writer import build_ass
from ..core.grouping import Layout, group_words
from ..core.model import Caption
from ..core.themes import Animation, Theme


# ---------------------------------------------------------------------------
# Preview: latest-request-wins
# ---------------------------------------------------------------------------


class PreviewRenderer(QThread):
    """Renders one subtitled frame at a time, always the most recent request.

    Requests that arrive while a render is in flight overwrite the pending one
    instead of queueing, so dragging the scrubber never builds a backlog of
    stale frames that then have to drain.
    """

    rendered = Signal(bytes, float, int)     # png, time, request id
    failed = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._mutex = QMutex()
        self._wake = QWaitCondition()
        self._pending: dict | None = None
        self._stop = False
        self._seq = 0

    def request(self, video: str, ass_text: str, t: float,
                width: int, height: int) -> int:
        with QMutexLocker(self._mutex):
            self._seq += 1
            self._pending = dict(video=video, ass=ass_text, t=t,
                                 width=width, height=height, seq=self._seq)
            self._wake.wakeAll()
            return self._seq

    def stop(self) -> None:
        with QMutexLocker(self._mutex):
            self._stop = True
            self._wake.wakeAll()
        self.wait(3000)

    def run(self) -> None:
        while True:
            with QMutexLocker(self._mutex):
                while self._pending is None and not self._stop:
                    self._wake.wait(self._mutex)
                if self._stop:
                    return
                job, self._pending = self._pending, None

            try:
                png = ffmpeg.render_frame(
                    job["video"], job["ass"], job["t"],
                    width=job["width"], height=job["height"],
                )
                self.rendered.emit(png, job["t"], job["seq"])
            except Exception as exc:                       # noqa: BLE001
                self.failed.emit(str(exc))


class MediaAssetsWorker(QThread):
    """Builds the timeline's audio waveform and filmstrip.

    Both are cached on disk by source file, so this is only slow the first time a
    given video is opened.
    """

    peaks_ready = Signal(object)
    strip_ready = Signal(list, float)       # jpeg bytes, seconds between frames
    failed = Signal(str)

    def __init__(self, video: str, parent=None):
        super().__init__(parent)
        self.video = video
        self._cancel = False

    def cancel(self) -> None:
        self._cancel = True

    def run(self) -> None:
        from ..core import media

        try:
            peaks = media.waveform_peaks(self.video)
            if self._cancel:
                return
            self.peaks_ready.emit(peaks)
        except Exception as exc:                           # noqa: BLE001
            self.failed.emit(f"waveform: {exc}")

        try:
            strip = media.filmstrip(self.video)
            if self._cancel:
                return
            blobs = [p.read_bytes() for p in strip.files]
            self.strip_ready.emit(blobs, strip.interval)
        except Exception as exc:                           # noqa: BLE001
            self.failed.emit(f"filmstrip: {exc}")


# ---------------------------------------------------------------------------
# Transcription
# ---------------------------------------------------------------------------


class TranscribeWorker(QThread):
    """Audio extraction + Whisper. Emits coarse stages so the UI can say what
    is actually happening — a bare percentage during a 3 GB download reads as a
    hang."""

    stage = Signal(str)                 # human-readable step
    progress = Signal(float)            # 0..1, -1 for indeterminate
    done = Signal(object)               # TranscriptionResult
    failed = Signal(str)

    def __init__(self, video: str, model: str = "large-v3",
                 language: str | None = None, use_gpu: bool = True,
                 batch_size: int = 8, parent=None):
        super().__init__(parent)
        self.video = video
        self.model = model
        self.language = language
        self.use_gpu = use_gpu
        self.batch_size = batch_size
        self._cancel = False

    def cancel(self) -> None:
        self._cancel = True

    def run(self) -> None:
        try:
            self.stage.emit("Extracting audio")
            self.progress.emit(-1.0)
            audio = ffmpeg.extract_audio(self.video)
            if self._cancel:
                return

            from ..core import transcribe as tr

            # First run pulls ~3 GB from Hugging Face. A bare spinner through that
            # is indistinguishable from a hang, so watch the cache directory grow
            # and report real megabytes (§3.2).
            first_run = not tr.is_downloaded(self.model)
            watcher_stop = threading.Event()
            if first_run:
                target = tr.MODEL_SIZES_MB.get(self.model, 0)

                def watch() -> None:
                    while not watcher_stop.wait(0.6):
                        got = tr.downloaded_mb(self.model)
                        if target:
                            self.progress.emit(min(0.99, got / target))
                            self.stage.emit(
                                f"Downloading the {self.model} model — "
                                f"{got:,.0f} of ~{target:,.0f} MB (one time only)")
                        else:
                            self.stage.emit(
                                f"Downloading the {self.model} model — "
                                f"{got:,.0f} MB")

                threading.Thread(target=watch, daemon=True).start()
            else:
                self.stage.emit(f"Loading {self.model}")

            try:
                model, device = tr.load_model(self.model, self.use_gpu)
            finally:
                watcher_stop.set()
            if self._cancel:
                return

            self.stage.emit("Transcribing" + ("" if device == "cuda" else " on CPU"))

            def on_progress(frac: float) -> None:
                if self._cancel:
                    raise ffmpeg.Cancelled("cancelled")
                self.progress.emit(frac)

            result = tr.transcribe(
                audio, model_name=self.model, language=self.language,
                prefer_gpu=self.use_gpu, batch_size=self.batch_size,
                model=model, device=device, on_progress=on_progress,
            )
            if self._cancel:
                return
            self.done.emit(result)
        except ffmpeg.Cancelled:
            return
        except Exception as exc:                           # noqa: BLE001
            self.failed.emit(f"{exc}\n\n{traceback.format_exc(limit=3)}")


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------


class ExportWorker(QThread):
    """Burn-in and/or sidecar export."""

    stage = Signal(str)
    progress = Signal(float)
    done = Signal(str)                  # destination path
    failed = Signal(str)

    def __init__(self, video: str, ass_text: str, dest: str, info,
                 captions: list[Caption], burn: bool = True,
                 sidecars: tuple[str, ...] = (), encoder: str = "h264_nvenc",
                 cq: int = 19, sfx: str | None = None, sfx_gain: float = 0.8,
                 mode: str = "WORD_POP", mux: bool = False, parent=None):
        super().__init__(parent)
        self.video, self.ass_text, self.dest, self.info = video, ass_text, dest, info
        self.captions = captions
        self.burn, self.sidecars, self.mux = burn, sidecars, mux
        self.encoder, self.cq = encoder, cq
        self.sfx, self.sfx_gain, self.mode = sfx, sfx_gain, mode
        self._cancel = False

    def cancel(self) -> None:
        self._cancel = True

    def run(self) -> None:
        try:
            dest = Path(self.dest)
            stem = dest.with_suffix("")

            if self.sidecars:
                from ..core.srt_writer import build_srt, build_vtt

                self.stage.emit("Writing subtitle files")
                # A subtitle file sharing the video's basename is AUTO-LOADED by
                # VLC, MPC, mpv and friends. Next to a burned-in export that
                # means the player draws a second set of captions on top of the
                # ones already in the pixels — for the whole video. So when we
                # burn in, the sidecars go in a subfolder where no player will
                # pick them up.
                folder = dest.parent
                if self.burn or self.mux:
                    folder = dest.parent / "caption files (not for playback)"
                    folder.mkdir(parents=True, exist_ok=True)
                base = folder / dest.stem

                for kind in self.sidecars:
                    text = {"srt": build_srt, "vtt": build_vtt}.get(kind)
                    body = text(self.captions) if text else self.ass_text
                    Path(f"{base}.{kind}").write_text(body, encoding="utf-8")

            if self.mux:
                self.stage.emit("Adding a switchable subtitle track")
                ffmpeg.mux_subtitles(self.video, self.ass_text, dest,
                                     on_progress=self.progress.emit,
                                     should_cancel=lambda: self._cancel)

            if self.burn:
                sfx_track = None
                if self.sfx:
                    from ..core import media

                    self.stage.emit("Building the caption sounds")
                    times = media.caption_sound_times(self.captions, self.mode)
                    sfx_track = str(Path(ffmpeg.cache_dir()) / "sfx_track.wav")
                    media.build_sfx_track(times, self.sfx, self.info.duration,
                                          sfx_track, gain=self.sfx_gain)

                self.stage.emit("Burning subtitles in")
                ffmpeg.burn_subtitles(
                    self.video, self.ass_text, dest, self.info,
                    encoder=self.encoder, cq=self.cq,
                    on_progress=self.progress.emit,
                    should_cancel=lambda: self._cancel,
                    sfx_wav=sfx_track,
                )
            if self._cancel:
                return
            self.done.emit(str(dest if (self.burn or self.mux) else stem))
        except ffmpeg.Cancelled:
            return
        except Exception as exc:                           # noqa: BLE001
            self.failed.emit(str(exc))


# ---------------------------------------------------------------------------
# Theme thumbnails
# ---------------------------------------------------------------------------


class ThumbnailWorker(QThread):
    """Renders a small sample of each theme, using the user's own footage when
    there is any — seeing your actual video in the picker beats a stock swatch."""

    ready = Signal(str, bytes)          # theme name, png
    finished_all = Signal()

    W, H = 320, 180

    def __init__(self, theme_names: list[str], video: str | None,
                 at_time: float = 0.0, parent=None):
        super().__init__(parent)
        self.theme_names = theme_names
        self.video = video
        self.at_time = at_time
        self._cancel = False

    def cancel(self) -> None:
        self._cancel = True

    def run(self) -> None:
        from ..core.model import Word

        # The .ass must sit on the SAME timeline as the footage we sample, because
        # -copyts means the filter is evaluated at the source timestamp. With a
        # video we anchor the sample card at `at_time`; without one, at zero.
        base = self.at_time if self.video else 0.0
        words = [Word(text=w, start=base + i * 0.35, end=base + (i + 1) * 0.35,
                      prob=1.0)
                 for i, w in enumerate(["make", "it", "look", "good"])]
        sample_at = base + 1.15

        for name in self.theme_names:
            if self._cancel:
                return
            try:
                theme = Theme.load(name)
                mode = theme.default_reveal
                # A swatch is displayed about a sixth of full width, so text at
                # the theme's true percentage would come out a few pixels tall and
                # unreadable. Scale it up: the card is selling the colour, weight
                # and outline of the style, not its exact size.
                theme.size_pct = min(theme.size_pct * 2.1, 26.0)
                theme.outline_pct = theme.outline_pct * 1.7
                # Centre the sample: the card's name label sits along the bottom,
                # and bottom-anchored themes would print straight through it.
                theme.position = "center"
                cfg = Layout.for_video(self.W, self.H, max_words_per_card=4)
                cfg.max_chars_per_line = 16
                if mode == "ONE_WORD":
                    cfg.max_words_per_card = 1
                caps = group_words(words, cfg, duration=base + 5.0)
                ass = build_ass(caps, theme, Animation(), mode, self.W, self.H)
                png = ffmpeg.render_theme_swatch(
                    ass, self.W, self.H, t=sample_at, video=self.video,
                    video_time=sample_at,
                )
                self.ready.emit(name, png)
            except Exception:                              # noqa: BLE001
                continue
        self.finished_all.emit()
