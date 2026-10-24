#!/usr/bin/env python
"""SubtitleStudio — entry point.

    SubtitleStudio.bat            (what the owner double-clicks)
    .venv\\Scripts\\python app.py   (same thing, with a console)

Optional flags, used by tools/screenshot.py and for debugging:
    --open <video>      load a video immediately
    --screenshot <png>  render the window to a file and quit
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

# Must happen before anything imports ctranslate2 (BUILD_PROMPT.md §9).
from src.core.transcribe import ensure_cuda_dlls  # noqa: E402,F401

from PySide6.QtCore import Qt  # noqa: E402
from PySide6.QtGui import QFont, QFontDatabase, QIcon, QPixmap  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from src.ui import style  # noqa: E402
from src.ui.main_window import MainWindow  # noqa: E402


def load_bundled_fonts() -> None:
    """Register the shipped fonts with Qt so the UI can use Poppins too."""
    fonts_dir = ROOT / "assets" / "fonts"
    if not fonts_dir.is_dir():
        return
    for path in fonts_dir.glob("*.ttf"):
        QFontDatabase.addApplicationFont(str(path))


def app_icon() -> QIcon:
    """A small mark drawn in code — no binary asset to keep in sync."""
    from PySide6.QtGui import QColor, QPainter, QPainterPath

    pixmap = QPixmap(64, 64)
    pixmap.fill(Qt.transparent)
    p = QPainter(pixmap)
    p.setRenderHint(QPainter.Antialiasing)
    path = QPainterPath()
    path.addRoundedRect(4, 4, 56, 56, 14, 14)
    p.fillPath(path, QColor(style.ACCENT))
    p.setPen(Qt.NoPen)
    p.setBrush(QColor("#FFFFFF"))
    p.drawRoundedRect(15, 26, 34, 6, 3, 3)
    p.drawRoundedRect(15, 38, 22, 6, 3, 3)
    p.end()
    return QIcon(pixmap)


def main() -> int:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("video", nargs="?", help="video to open on launch")
    parser.add_argument("--open", dest="open_video")
    parser.add_argument("--screenshot")
    parser.add_argument("--screenshot-delay", type=int, default=2500)
    parser.add_argument("--visible", action="store_true",
                        help="with --screenshot, show a real window instead of "
                             "rendering offscreen")
    parser.add_argument("--monitor", type=int, default=None,
                        help="open on this screen (0 = primary). Use a second "
                             "monitor so the window never covers your work.")
    args = parser.parse_args()

    # Screenshots render offscreen by default: an automated capture must never
    # steal focus or throw a window over whatever the user is doing. --visible
    # (optionally with --monitor) opts back into a real window.
    if args.screenshot and not args.visible and args.monitor is None:
        import os

        os.environ["QT_QPA_PLATFORM"] = "offscreen"

    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.PassThrough)
    app = QApplication(sys.argv[:1])
    app.setApplicationName("SubtitleStudio")
    app.setWindowIcon(app_icon())

    load_bundled_fonts()
    app.setFont(QFont("Poppins", 10))
    app.setStyleSheet(style.qss())

    window = MainWindow()

    if args.monitor is not None:
        screens = app.screens()
        screen = screens[min(args.monitor, len(screens) - 1)]
        geo = screen.availableGeometry()
        window.resize(min(window.width(), geo.width() - 40),
                      min(window.height(), geo.height() - 60))
        window.move(geo.x() + (geo.width() - window.width()) // 2,
                    geo.y() + (geo.height() - window.height()) // 2)
    window.show()

    from PySide6.QtCore import QTimer  # noqa: E402

    target = args.open_video or args.video
    if target and Path(target).is_file():
        QTimer.singleShot(120, lambda: window.load_video(Path(target)))

    if args.screenshot:
        def shoot() -> None:
            try:
                pixmap = window.grab()
                ok = pixmap.save(args.screenshot, "PNG")
                print(f"screenshot {'saved' if ok else 'FAILED'}: {args.screenshot} "
                      f"({pixmap.width()}x{pixmap.height()})", flush=True)
            except Exception as exc:                        # noqa: BLE001
                print(f"screenshot error: {exc}", flush=True)
            app.quit()
            # Belt and braces: if a worker thread refuses to unwind, never leave a
            # stray window sitting on someone's desktop.
            QTimer.singleShot(2500, lambda: __import__("os")._exit(0))

        QTimer.singleShot(args.screenshot_delay, shoot)

    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
