#!/usr/bin/env python
"""End-to-end test of the real application.

Drives MainWindow the way a person would — open a video, transcribe it for real
on the GPU, restyle it, export it — and checks the files that come out. Nothing
is stubbed; this runs actual Whisper and actual ffmpeg.

    tools\\verify_app.py <video>

Renders offscreen, so it never puts a window on your screen. Exits non-zero if
any step fails.
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  — {detail}" if detail else ""),
          flush=True)
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--keep", action="store_true", help="keep the temp workspace")
    args = ap.parse_args()

    from src.core import layers as core_layers
    from src.core.transcribe import ensure_cuda_dlls  # noqa: F401

    from PySide6.QtCore import QEventLoop, QTimer
    from PySide6.QtGui import QFont, QFontDatabase
    from PySide6.QtWidgets import QApplication

    from src.ui import style
    from src.ui.main_window import MainWindow

    work = Path(tempfile.mkdtemp(prefix="verify_app_"))
    video = work / "clip.mp4"
    shutil.copy2(args.video, video)          # fresh copy => no cached .subproj

    app = QApplication(sys.argv[:1])
    for path in (ROOT / "assets" / "fonts").glob("*.ttf"):
        QFontDatabase.addApplicationFont(str(path))
    app.setFont(QFont("Poppins", 10))
    app.setStyleSheet(style.qss())

    window = MainWindow()
    window.show()

    def settle(ms: int) -> None:
        loop = QEventLoop()
        QTimer.singleShot(ms, loop.quit)
        loop.exec()

    def wait_until(predicate, timeout_ms: int, poll: int = 200) -> bool:
        waited = 0
        while waited < timeout_ms:
            if predicate():
                return True
            settle(poll)
            waited += poll
        return predicate()

    print("\nEnd-to-end app test\n")
    print(f"  workspace: {work}\n")

    # --- 1. open ------------------------------------------------------------
    window.load_video(video)
    settle(1500)
    check("video opens and probes", window.info is not None
          and window.state == "LOADED",
          f"state={window.state}")
    check("no stale transcript", not window._has_words())

    # --- 2. transcribe for real --------------------------------------------
    print("\n  transcribing (real Whisper on the GPU, please wait)…", flush=True)
    t0 = time.time()
    window._transcribe()
    ok = wait_until(lambda: window.state == "READY" or
                    (window.state == "LOADED" and not window._transcriber.isRunning()),
                    timeout_ms=600_000)
    took = time.time() - t0
    check("transcription completes",
          window.state == "READY" and window._has_words(),
          f"{len(window.active_layer().words)} words in {took:.0f}s")
    check("caption cards were built", len(window.captions) > 0,
          f"{len(window.captions)} cards")
    check("project file saved", (work / "clip.mp4.subproj").is_file())

    # --- 2b. layers ---------------------------------------------------------
    panel = window.style_panel.layers_panel
    check("starts with one layer", len(window.layers) == 1,
          window.layers[0].name)

    panel.add_layer()
    settle(700)
    check("a second layer can be added", len(window.layers) == 2,
          " + ".join(l.name for l in window.layers))
    check("new layer inherits the words",
          len(window.layers[1].words) == len(window.layers[0].words))
    check("new layer differs in style or place",
          (window.layers[1].theme != window.layers[0].theme
           or window.layers[1].position != window.layers[0].position),
          f"{window.layers[1].theme} @ {window.layers[1].position}")

    doc = window._build_ass((1920, 1080))
    check("both layers reach the .ass",
          "Style: Sub0," in doc and "Style: Sub1," in doc)
    check("layers get distinct z-order",
          {int(l.split(":")[1].split(",")[0]) for l in doc.splitlines()
           if l.startswith("Dialogue:")} == {0, 10})

    check("timeline shows one track per layer",
          len(window.timeline.view.tracks) == 2,
          f"{len(window.timeline.view.tracks)} tracks")
    check("overlay draws both layers", len(window._render_layers()) == 2)

    window.layers[1].enabled = False
    window._push_style()
    settle(300)
    doc_off = window._build_ass((1920, 1080))
    check("hiding a layer removes it from output",
          "Style: Sub1," not in doc_off and len(window._render_layers()) == 1)
    window.layers[1].enabled = True

    panel._select(1)
    settle(400)
    check("selecting a layer switches the style controls",
          window.active_index == 1
          and window.style_panel.theme_name() == window.layers[1].theme,
          f"panel shows {window.style_panel.theme_name()}")
    panel._select(0)
    settle(300)

    # --- 3. restyle must not re-run Whisper --------------------------------
    words_before = list(window.active_layer().words)
    t0 = time.time()
    window.style_panel.set_theme("neon")
    window.style_panel.set_mode("KARAOKE_HIGHLIGHT")
    settle(1500)
    restyle_time = time.time() - t0
    check("restyle keeps the same words",
          window.active_layer().words == words_before)
    check("restyle is fast (no GPU work)", restyle_time < 5.0,
          f"{restyle_time:.1f}s")

    # --- 4. every theme x mode renders --------------------------------------
    from src.core.reveal import MODES
    from src.core.themes import Theme

    broken: list[str] = []
    for theme_name in Theme.available():
        for mode in MODES:
            window.style_panel.set_theme(theme_name, silent=True)
            window.style_panel.set_mode(mode, silent=True)
            try:
                doc = window._build_ass((640, 360))
                if "Dialogue:" not in doc:
                    broken.append(f"{theme_name}/{mode}: no events")
            except Exception as exc:                       # noqa: BLE001
                broken.append(f"{theme_name}/{mode}: {exc}")
    check("all theme x mode combinations build", not broken,
          f"{len(Theme.available()) * len(MODES)} combinations"
          if not broken else "; ".join(broken[:3]))

    # --- 5. transcript editing ---------------------------------------------
    window.style_panel.set_theme("hormozi", silent=True)
    window.style_panel.set_mode("WORD_POP", silent=True)
    window._rebuild()
    settle(400)
    if window.captions:
        first = window.captions[0]
        original = first.text
        window.transcript._retime(first, ["EDITED", "TEXT", "HERE"])
        window._on_transcript_edited()
        settle(600)
        check("edited text reaches the word stream",
              any(w.text == "EDITED" for w in window.active_layer().words),
              f"{original!r} -> {first.text!r}")
        check("edit keeps timings inside the card",
              first.words[0].start >= 0 and first.words[-1].end <= first.end + 0.01)

    # --- 5b. split and merge cards ------------------------------------------
    before_count = len(window.captions)
    target_words = len(window.captions[1].words)
    if target_words > 1:
        window._on_split(1, 1)
        settle(400)
        check("splitting a card makes one more",
              len(window.captions) == before_count + 1,
              f"{before_count} -> {len(window.captions)}")
        check("split switches the layer to manual cards",
              window.active_layer().manual_cards)

        # A slider nudge must NOT silently undo the manual boundary.
        window.style_panel.words_slider.set_value(4)
        window._on_layout_changed()
        settle(400)
        check("manual card breaks survive a layout change",
              len(window.captions) == before_count + 1,
              f"{len(window.captions)} cards")

        window._on_merge(1)
        settle(400)
        check("merging puts it back", len(window.captions) == before_count,
              f"{len(window.captions)} cards")

        window._on_reset_cards()
        settle(400)
        check("reset returns to automatic grouping",
              not window.active_layer().manual_cards)

    # --- 5c. effects --------------------------------------------------------
    import re

    def active_doc() -> str:
        """Only the selected layer — a preset applies to that one, and the other
        layer's own motion would otherwise show up in the search."""
        return core_layers.build_ass_for([window.active_layer()], 1920, 1080,
                                         window.info.duration)

    def pop_from(doc: str) -> int:
        m = re.search(r"\\fscx(\d+)\\fscy\1\\t\(0,", doc)
        return int(m.group(1)) if m else 100

    window._on_effect_preset("Subtle")
    settle(300)
    subtle = pop_from(active_doc())
    window._on_effect_preset("Big")
    settle(300)
    big = pop_from(active_doc())
    check("effect presets change how far words travel", big < subtle,
          f"Big springs from {big}%, Subtle from {subtle}%")

    window._on_effect_preset("None")
    settle(300)
    check("the None preset removes all motion", r"\t(" not in active_doc(),
          "no animation tags emitted")

    window._on_effect_preset("Tilt")
    settle(300)
    check("tilt reaches the output", r"\frz" in active_doc())

    window._on_effect_preset("Default")
    settle(300)
    anim = core_layers.animation_for(window.active_layer())
    travel = max(anim.scale_at(ms) for ms in range(0, anim.pop_ms, 5)) - \
        min(anim.scale_at(ms) for ms in range(0, anim.pop_ms, 5))
    check("default pop is visible but restrained", 0.20 < travel < 0.45,
          f"{travel*100:.0f}% size change")

    # --- 6. real playback ---------------------------------------------------
    window._seek_to(2.0)
    settle(600)
    window.player.play()
    settle(2500)
    t_a = window.player.position()
    settle(1500)
    t_b = window.player.position()
    window.player.pause()
    check("video plays at real speed", t_b > t_a + 0.8,
          f"{t_a:.2f}s -> {t_b:.2f}s in 1.5s of wall clock")
    check("audio output is connected",
          window.player.audio is not None and window.player.audio.volume() > 0,
          f"volume {window.player.audio.volume():.2f}")
    # From here on, stay silent: an automated test must never play sound out of
    # the speakers of someone who is working.
    window.player.audio.setVolume(0.0)
    check("timeline follows the playhead",
          abs(window.timeline.view.position - t_b) < 0.6,
          f"timeline at {window.timeline.view.position:.2f}s")
    settle(300)

    # --- 6b. timeline assets ------------------------------------------------
    ok_assets = wait_until(
        lambda: window.timeline.view.peaks is not None
        and len(window.timeline.view.thumbs) > 0, 120_000)
    peaks = window.timeline.view.peaks
    check("waveform and filmstrip built", ok_assets,
          f"{0 if peaks is None else len(peaks)} peaks, "
          f"{len(window.timeline.view.thumbs)} thumbnails")

    # --- 6c. captions are drawn by the live overlay -------------------------
    from PySide6.QtGui import QImage, QPainter
    from PySide6.QtCore import QRectF
    from src.ui import caption_render

    canvas = QImage(640, 360, QImage.Format_ARGB32)
    canvas.fill(0xFF000000)
    painter = QPainter(canvas)
    drew = caption_render.draw_at(
        painter, QRectF(0, 0, 640, 360), window.captions,
        window.current_theme(), window.style_panel.mode(),
        window.captions[0].start + 0.25)
    painter.end()
    lit = sum(1 for y in range(0, 360, 4) for x in range(0, 640, 4)
              if (canvas.pixel(x, y) & 0xFFFFFF) > 0x303030)
    check("live overlay actually paints captions", drew and lit > 40,
          f"{lit} lit sample points")

    # --- 7. export: sidecar only (stream copy, must be quick) ---------------
    from src.ui.workers import ExportWorker

    dest = work / "out_sidecar.mp4"
    ass = window._build_ass((window.info.width, window.info.height))
    t0 = time.time()
    worker = ExportWorker(str(video), ass, str(dest), window.info, window.captions,
                          burn=False, sidecars=("ass", "srt", "vtt"))
    worker.start()
    wait_until(lambda: not worker.isRunning(), 60_000)
    side_time = time.time() - t0
    check("sidecar export writes .srt", (work / "out_sidecar.srt").is_file())
    check("sidecar export writes .ass", (work / "out_sidecar.ass").is_file())
    check("sidecar export is fast", side_time < 10.0, f"{side_time:.1f}s")

    # --- 7b. burned-in export must NOT leave an auto-loading sidecar --------
    # A subtitle file sharing the video's basename is picked up automatically by
    # VLC/MPC/mpv, and the player then draws captions ON TOP of the ones already
    # burned into the pixels — two sets, for the whole video.
    burn_dir = work / "burnsafe"
    burn_dir.mkdir()
    burn_dest = burn_dir / "clip_subtitled.mp4"
    sider = ExportWorker(str(video), ass, str(burn_dest), window.info,
                         window.captions, burn=True,
                         sidecars=("ass", "srt", "vtt"),
                         encoder="h264_nvenc", cq=30)
    sider.start()
    wait_until(lambda: not sider.isRunning(), 600_000)
    strays = [p.name for p in burn_dir.iterdir()
              if p.suffix.lower() in (".ass", ".srt", ".vtt")
              and p.stem == burn_dest.stem]
    check("burned-in export leaves no auto-loading sidecar", not strays,
          "clean" if not strays else f"would double up: {strays}")
    check("requested sidecars still written, out of the way",
          any((burn_dir / "caption files (not for playback)").glob("*.srt")))

    # --- 8. export: burn-in --------------------------------------------------
    dest2 = work / "out_burned.mp4"
    t0 = time.time()
    burner = ExportWorker(str(video), ass, str(dest2), window.info, window.captions,
                          burn=True, sidecars=(), encoder="h264_nvenc", cq=23)
    errors: list[str] = []
    burner.failed.connect(errors.append)
    burner.start()
    wait_until(lambda: not burner.isRunning(), 600_000)
    burn_time = time.time() - t0
    size = dest2.stat().st_size if dest2.is_file() else 0
    check("burn-in export produces a video", size > 100_000,
          f"{size/1e6:.1f} MB in {burn_time:.0f}s"
          + (f" — {errors[0][:120]}" if errors else ""))

    # --- 8b. export with caption sounds mixed in ----------------------------
    from src.core import media

    sfx = media.available_sfx().get("pop")
    if sfx:
        dest3 = work / "out_sfx.mp4"
        short = [c for c in window.captions if c.end < 12.0]
        sfx_worker = ExportWorker(
            str(video), ass, str(dest3), window.info, short,
            burn=True, sidecars=(), encoder="h264_nvenc", cq=28,
            sfx=str(sfx), sfx_gain=0.8, mode=window.style_panel.mode())
        sfx_errors: list[str] = []
        sfx_worker.failed.connect(sfx_errors.append)
        sfx_worker.start()
        wait_until(lambda: not sfx_worker.isRunning(), 600_000)
        check("export with caption sounds succeeds", dest3.is_file()
              and dest3.stat().st_size > 100_000,
              sfx_errors[0][:140] if sfx_errors else f"{len(short)} sound hits")

        if dest3.is_file():
            from src.core import ffmpeg as ff

            sfx_info = ff.probe(dest3)
            check("sound-mixed export still has audio", sfx_info.has_audio,
                  sfx_info.audio_codec)
            check("sound mixing didn't change the duration",
                  abs(sfx_info.duration - window.info.duration) < 1.0,
                  f"{sfx_info.duration:.1f}s vs {window.info.duration:.1f}s")

    # --- 8c. switchable subtitle track (no re-encode) -----------------------
    dest_mkv = work / "out_track.mkv"
    t0 = time.time()
    muxer = ExportWorker(str(video), ass, str(dest_mkv), window.info,
                         window.captions, burn=False, mux=True, sidecars=())
    mux_errors: list[str] = []
    muxer.failed.connect(mux_errors.append)
    muxer.start()
    wait_until(lambda: not muxer.isRunning(), 120_000)
    mux_time = time.time() - t0
    check("switchable subtitle track muxes", dest_mkv.is_file()
          and dest_mkv.stat().st_size > 100_000,
          mux_errors[0][:140] if mux_errors else f"{mux_time:.1f}s, no re-encode")
    check("muxing is much faster than burning", mux_time < max(3.0, burn_time / 2),
          f"{mux_time:.1f}s vs {burn_time:.1f}s to burn")

    if size:
        from src.core import ffmpeg as ff

        out_info = ff.probe(dest2)
        check("exported video keeps resolution",
              (out_info.width, out_info.height) ==
              (window.info.width, window.info.height),
              f"{out_info.width}x{out_info.height}")
        check("exported video keeps audio", out_info.has_audio)
        check("exported duration matches source",
              abs(out_info.duration - window.info.duration) < 1.0,
              f"{out_info.duration:.1f}s vs {window.info.duration:.1f}s")

    # --- 9. reopen restores without re-transcribing -------------------------
    # Release the first player's decoder before opening a second one: two live
    # QMediaPlayers on the same file exhaust the d3d11 frame pool and the second
    # never gets a picture.
    window.player.close_source()
    settle(600)

    window2 = MainWindow()
    window2.show()
    window2.load_video(video)
    settle(1800)
    check("reopening restores the transcript",
          window2.state == "READY" and len(window2.active_layer().words) > 0,
          f"{len(window2.active_layer().words)} words, state={window2.state}")
    check("restored edit survived",
          any(w.text == "EDITED" for w in window2.active_layer().words))
    check("both layers were restored", len(window2.layers) == 2,
          " + ".join(f"{l.name}/{l.theme}" for l in window2.layers))

    # --- summary ------------------------------------------------------------
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n  {passed}/{len(results)} checks passed")
    if not args.keep:
        window.close()
        window2.close()
        app.processEvents()
        shutil.rmtree(work, ignore_errors=True)
    else:
        print(f"  workspace kept: {work}")
    print()

    failed = [n for n, ok, _ in results if not ok]
    if failed:
        print("  FAILED: " + ", ".join(failed) + "\n")
    os._exit(1 if failed else 0)


if __name__ == "__main__":
    sys.exit(main())
