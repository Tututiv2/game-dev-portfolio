#!/usr/bin/env python
"""Drive the real app and capture what it looks like in each state.

This is how the UI gets checked — by running it, not by reading it. It builds the
real MainWindow, feeds it a real video, waits for the real ffmpeg preview render
to land, and saves the actual pixels.

    tools\\screenshot.py <video> <out-dir>
    tools\\screenshot.py <video> <out-dir> --visible --monitor 1

Renders offscreen by default so it never throws a window over your work.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("outdir")
    ap.add_argument("--visible", action="store_true")
    ap.add_argument("--monitor", type=int, default=None)
    args = ap.parse_args()

    if not (args.visible or args.monitor is not None):
        import os

        os.environ["QT_QPA_PLATFORM"] = "offscreen"

    from src.core.transcribe import ensure_cuda_dlls  # noqa: F401

    from PySide6.QtCore import QEventLoop, Qt, QTimer
    from PySide6.QtGui import QFont, QFontDatabase
    from PySide6.QtWidgets import QApplication

    from src.ui import style
    from src.ui.main_window import MainWindow

    out = Path(args.outdir)
    out.mkdir(parents=True, exist_ok=True)

    app = QApplication(sys.argv[:1])
    for path in (ROOT / "assets" / "fonts").glob("*.ttf"):
        QFontDatabase.addApplicationFont(str(path))
    app.setFont(QFont("Poppins", 10))
    app.setStyleSheet(style.qss())

    window = MainWindow()
    if args.monitor is not None:
        screens = app.screens()
        geo = screens[min(args.monitor, len(screens) - 1)].availableGeometry()
        window.move(geo.x() + 60, geo.y() + 60)
    window.show()

    shots: list[str] = []

    def settle(ms: int) -> None:
        """Spin the real event loop so renders and repaints actually happen."""
        loop = QEventLoop()
        QTimer.singleShot(ms, loop.quit)
        loop.exec()

    def shoot(name: str) -> None:
        app.processEvents()
        path = out / f"{name}.png"
        window.grab().save(str(path), "PNG")
        shots.append(name)
        print(f"  {name:<28} {path}", flush=True)

    print("\nDriving the app:\n")
    settle(400)
    shoot("01_empty")

    window.load_video(Path(args.video))
    window.player.audio.setVolume(0.0)   # never play sound out of someone's speakers
    settle(3500)                                   # probe + first preview render
    shoot("02_loaded")

    if window.state == "READY":
        for theme, mode in [("hormozi", "WORD_POP"),
                            ("beast", "ONE_WORD"),
                            ("karaoke_fill", "KARAOKE_HIGHLIGHT"),
                            ("neon", "WORD_POP"),
                            ("boxed", "KARAOKE_HIGHLIGHT"),
                            ("gradient_pop", "WORD_POP")]:
            window.style_panel.set_theme(theme)
            window.style_panel.set_mode(mode)
            settle(1400)
            shoot(f"03_{theme}_{mode.lower()}")

        # A slider change must restyle without re-running Whisper.
        window.style_panel.size_slider.set_value(14.0)
        window.style_panel.layout_changed.emit()
        settle(1400)
        shoot("04_big_text")

        window.style_panel.set_theme("hormozi")
        window.style_panel.size_slider.set_value(10.5)
        window.style_panel.layout_changed.emit()
        settle(1200)

        # Real playback: confirm the clock actually advances and captions follow.
        window._seek_to(2.0)
        settle(500)
        window.player.play()
        settle(2500)
        t1 = window.player.position()
        shoot("06_playing_a")
        settle(1500)
        t2 = window.player.position()
        shoot("06_playing_b")
        window.player.pause()
        print(f"  -> playback advanced {t1:.2f}s to {t2:.2f}s "
              f"({'OK' if t2 > t1 + 0.5 else 'STALLED'})")
        settle(600)

        dialog_shot(window, out, shots)
    else:
        print(f"  (state is {window.state}; no transcript to style)")

    settle(200)
    print(f"\n{len(shots)} screenshots in {out}\n")
    window.close()
    app.processEvents()
    import os

    os._exit(0)


def dialog_shot(window, out: Path, shots: list) -> None:
    """Capture the export dialog without blocking on exec()."""
    from PySide6.QtCore import QEventLoop, QTimer

    from src.ui.export_dialog import ExportDialog

    dialog = ExportDialog(window.video, has_nvenc=True, parent=window)
    dialog.show()
    loop = QEventLoop()
    QTimer.singleShot(700, loop.quit)
    loop.exec()
    path = out / "05_export.png"
    dialog.grab().save(str(path), "PNG")
    shots.append("05_export")
    print(f"  {'05_export':<28} {path}", flush=True)
    dialog.close()


if __name__ == "__main__":
    sys.exit(main())
