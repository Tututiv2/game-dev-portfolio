"""What is Spotify playing right now.

Read straight off the Spotify window title via ctypes. No Spotify API, which
means no developer-app registration, no OAuth redirect and no token to expire
mid-stream - the three reasons every 'now playing' guide turns into an afternoon.

Spotify writes 'Artist - Track' into its window title while playing, and falls
back to a bare 'Spotify Premium' / 'Spotify' / 'Spotify Free' when it is paused
or stopped. That is the entire protocol, and it has been stable for years.

parse_title() is pure and is where every judgement lives, so the awkward cases
(a track whose own name contains a dash, an ad, a paused client) are testable
with no Spotify running and no Windows at all.
"""

import ctypes
import sys
from ctypes import wintypes

# Titles that mean "nothing is playing". Compared lowercase.
IDLE_TITLES = {
    "spotify",
    "spotify premium",
    "spotify free",
    "spotify - web player",
}

AD_PREFIXES = ("advertisement", "spotify - advertisement")

DEFAULT_PROCESS = "Spotify.exe"


def parse_title(title):
    """'Artist - Track' -> {'artist': ..., 'title': ...}, else None.

    Splits on the FIRST ' - ' only. Track names carry dashes far more often than
    artist names do ('Song - Remastered 2011', 'Track - Radio Edit'), so the tail
    belongs to the title and anything else mangles a good share of any library.
    """
    text = (title or "").strip()
    if not text:
        return None
    low = text.lower()
    if low in IDLE_TITLES:
        return None
    if any(low.startswith(p) for p in AD_PREFIXES):
        return None
    if " - " in text:
        artist, _, song = text.partition(" - ")
        artist, song = artist.strip(), song.strip()
        if artist and song:
            return {"artist": artist, "title": song}
        return None
    # A title with no separator is not a track; Spotify only ever writes its own
    # name in that shape. Treating it as a song would put "Spotify" on stream.
    return None


# --------------------------------------------------------------------------
# Windows plumbing. Everything below is inert on any other platform.
# --------------------------------------------------------------------------

_IS_WINDOWS = sys.platform == "win32"

if _IS_WINDOWS:
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

    _ENUM_PROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    user32.EnumWindows.argtypes = [_ENUM_PROC, wintypes.LPARAM]
    user32.EnumWindows.restype = wintypes.BOOL
    user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND,
                                                ctypes.POINTER(wintypes.DWORD)]

    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.QueryFullProcessImageNameW.argtypes = [
        wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR,
        ctypes.POINTER(wintypes.DWORD)]
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]


def _exe_name(pid):
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ""
    try:
        size = wintypes.DWORD(1024)
        buf = ctypes.create_unicode_buffer(size.value)
        if kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)):
            return buf.value.rsplit("\\", 1)[-1]
        return ""
    finally:
        kernel32.CloseHandle(handle)


def window_titles(process_name=DEFAULT_PROCESS):
    """Every visible, non-empty window title belonging to that process.

    Spotify runs seven processes and only one of them owns the titled window, so
    this cannot just take the first match - it collects them all and lets the
    caller pick.
    """
    if not _IS_WINDOWS:
        return []
    found = []
    target = process_name.lower()

    def visit(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        text = buf.value.strip()
        if not text:
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if _exe_name(pid.value).lower() == target:
            found.append(text)
        return True

    try:
        user32.EnumWindows(_ENUM_PROC(visit), 0)
    except Exception:
        return []
    return found


def current(process_name=DEFAULT_PROCESS):
    """The track playing now, or None.

    Prefers a title that parses as a track. A paused Spotify leaves 'Spotify
    Premium' sitting there, and any other window it owns (a settings dialog, the
    mini player) would otherwise win the race depending on enumeration order.
    """
    for title in window_titles(process_name):
        track = parse_title(title)
        if track:
            return track
    return None
