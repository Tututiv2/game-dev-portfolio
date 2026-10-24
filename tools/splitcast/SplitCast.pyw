"""SplitCast, with no console window.

Double-click this file, or the Desktop shortcut that `Create Shortcut.bat`
makes. The `.pyw` extension is the whole trick: Windows associates it with
pythonw.exe, which is the same interpreter built as a GUI binary, so no console
is ever allocated. A .bat cannot do this - cmd.exe owns a console by definition
and it flashes up however the batch file is written.

WHAT THIS BUYS AND WHAT IT COSTS
--------------------------------
The console was not only noise; it was the error report. Without it a traceback
goes to a stderr nobody is reading, so a crash on startup becomes a program that
simply does not open - the worst possible failure, because it looks identical to
a double-click that did not register.

So every exception is caught, written to splitcast-error.log next to this file,
and shown in a message box. ctypes rather than tkinter for that box: if the
failure IS tkinter, a tkinter error dialog cannot report it.
"""

import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "splitcast-error.log")

MB_ICONERROR = 0x10


def _report(title, body):
    try:
        with open(LOG, "a", encoding="utf-8") as fh:
            fh.write(body + "\n" + "=" * 70 + "\n")
    except OSError:
        pass
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            None, body[-1500:] + "\n\nFull details: " + LOG, title, MB_ICONERROR)
    except Exception:
        # Nothing left to report with. Re-raising here would only produce the
        # silent exit this file exists to prevent, so let the log stand alone.
        pass


def main():
    sys.path.insert(0, HERE)
    try:
        from splitcast import gui
    except Exception:
        _report("SplitCast could not start",
                "SplitCast failed while loading.\n\n" + traceback.format_exc())
        return 1
    try:
        gui.main()
    except Exception:
        _report("SplitCast stopped",
                "SplitCast hit an error while running.\n\n" + traceback.format_exc())
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
