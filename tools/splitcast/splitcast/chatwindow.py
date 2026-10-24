"""Opens the combined chat as a desktop window on the second monitor.

Deliberately NOT a second chat client. Writing one in tkinter would mean
hand-rolling a WebSocket client (there is none in the stdlib), decoding emote
PNGs, and maintaining a second copy of every parsing rule - all to end up with
the same thing the page already does. So this runs the SAME page in a
chromeless browser window, and the app is a window manager rather than a client.

Two flags carry it:

* --app=<url> gives a window with no tabs, no address bar and no browser
  chrome. It looks like an application because, framed that way, it is one.

* --user-data-dir=<own folder> is load-bearing, not hygiene. Without it the
  flags are handed to the ALREADY RUNNING browser, which opens a normal tab in
  an existing window and ignores the sizing entirely - the app window simply
  never appears. A separate profile forces a separate process.
"""

import os
import subprocess

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROFILE = os.path.join(HERE, ".chatwindow-profile")

# Order is preference, not availability. Any Chromium build supports --app.
CANDIDATES = [
    r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe",
    r"%PROGRAMFILES%\Google\Chrome\Application\chrome.exe",
    r"%PROGRAMFILES(X86)%\Google\Chrome\Application\chrome.exe",
    r"%LOCALAPPDATA%\BraveSoftware\Brave-Browser\Application\brave.exe",
    r"%PROGRAMFILES%\BraveSoftware\Brave-Browser\Application\brave.exe",
    r"%PROGRAMFILES(X86)%\BraveSoftware\Brave-Browser\Application\brave.exe",
    r"%PROGRAMFILES(X86)%\Microsoft\Edge\Application\msedge.exe",
    r"%PROGRAMFILES%\Microsoft\Edge\Application\msedge.exe",
]


def find_browser():
    for raw in CANDIDATES:
        path = os.path.expandvars(raw)
        if "%" not in path and os.path.exists(path):
            return path
    return None


def build_args(browser, url, width=520, height=900):
    return [
        browser,
        "--app=" + url,
        "--user-data-dir=" + PROFILE,
        "--window-size={},{}".format(width, height),
        "--no-first-run",
        "--no-default-browser-check",
        # An overlay window has no business restoring "pages didn't load" bubbles
        # or asking to be the default browser.
        "--disable-session-crashed-bubble",
        "--disable-features=Translate,InfobarScreenshot",
    ]


def open_window(cfg, url=None):
    """Launch the dock window. Returns (ok, detail)."""
    browser = find_browser()
    if not browser:
        return False, ("no Chromium browser found - install Chrome, Brave or Edge, "
                       "or just open the URL in any browser")
    if url is None:
        port = int(cfg.get("overlay_port", 5570))
        scale = float(cfg.get("chat_window_scale", 1.0))
        url = "http://127.0.0.1:{}/chat?dock=1&scale={}".format(port, scale)

    args = build_args(browser, url,
                      int(cfg.get("chat_window_width", 520)),
                      int(cfg.get("chat_window_height", 900)))
    try:
        os.makedirs(PROFILE, exist_ok=True)
        # Detached: the chat window must outlive whatever started it, and must
        # never inherit this process's console.
        subprocess.Popen(args, creationflags=0x00000008 | 0x08000000,
                         close_fds=True)
    except OSError as exc:
        return False, str(exc)
    return True, os.path.basename(browser)
