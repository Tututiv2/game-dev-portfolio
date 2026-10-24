"""Font discovery — BUILD_PROMPT.md §4.5.

In ASS you name a font by its FAMILY ("Anton"), not its filename
("Anton-Regular.ttf"), and a family that doesn't resolve silently falls back to
Arial. The user just sees "my font didn't apply". So rather than guessing, this
reads the real family names out of the `name` table of every bundled font file,
and checks the Windows registry for installed ones.

Pure stdlib — no fonttools dependency for what is 60 lines of struct parsing.
"""

from __future__ import annotations

import struct
import sys
from functools import lru_cache
from pathlib import Path

FONTS_DIR = Path(__file__).resolve().parents[2] / "assets" / "fonts"

# name table IDs we care about
_FAMILY = 1            # e.g. "Poppins ExtraBold" for non-RIBBI weights
_FULL_NAME = 4         # e.g. "Poppins ExtraBold"
_TYPO_FAMILY = 16      # e.g. "Poppins" — the weight-independent family


def _decode(raw: bytes, platform_id: int) -> str:
    try:
        if platform_id == 3:            # Windows: UTF-16BE
            return raw.decode("utf-16-be").strip("\x00").strip()
        return raw.decode("latin-1").strip()
    except UnicodeDecodeError:
        return ""


def family_names(path: Path) -> set[str]:
    """Every family-ish name a font file advertises."""
    names: set[str] = set()
    try:
        data = path.read_bytes()
        if len(data) < 12:
            return names

        num_tables = struct.unpack(">H", data[4:6])[0]
        name_off = name_len = 0
        for i in range(num_tables):
            rec = 12 + i * 16
            tag = data[rec:rec + 4]
            if tag == b"name":
                name_off, name_len = struct.unpack(">II", data[rec + 8:rec + 16])
                break
        if not name_off or name_off + 6 > len(data):
            return names

        count, string_off = struct.unpack(">HH", data[name_off + 2:name_off + 6])
        strings_base = name_off + string_off
        for i in range(count):
            rec = name_off + 6 + i * 12
            if rec + 12 > len(data):
                break
            plat, _enc, _lang, name_id, length, offset = struct.unpack(
                ">HHHHHH", data[rec:rec + 12]
            )
            if name_id not in (_FAMILY, _FULL_NAME, _TYPO_FAMILY):
                continue
            start = strings_base + offset
            value = _decode(data[start:start + length], plat)
            if value:
                names.add(value)
    except (OSError, struct.error):
        pass
    return names


@lru_cache(maxsize=1)
def bundled() -> dict[str, Path]:
    """Lowercased family name -> font file, for everything we ship."""
    out: dict[str, Path] = {}
    if not FONTS_DIR.is_dir():
        return out
    for path in sorted(FONTS_DIR.iterdir()):
        if path.suffix.lower() not in (".ttf", ".otf", ".ttc"):
            continue
        for name in family_names(path):
            out.setdefault(name.lower(), path)
    return out


@lru_cache(maxsize=1)
def installed() -> set[str]:
    """Lowercased font family names registered on this machine."""
    names: set[str] = set()
    if sys.platform != "win32":
        return names
    import winreg

    key_path = r"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts"
    for root in (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER):
        try:
            with winreg.OpenKey(root, key_path) as key:
                for i in range(winreg.QueryInfoKey(key)[1]):
                    raw = winreg.EnumValue(key, i)[0]
                    for part in raw.split("&"):
                        part = part.split("(")[0].strip()
                        if part:
                            names.add(part.lower())
        except OSError:
            continue
    return names


def available(name: str) -> bool:
    low = name.lower()
    if low in bundled():
        return True
    return any(n == low or n.startswith(low) for n in installed())


def resolve(preferred: str, fallbacks: list[str] | None = None) -> tuple[str, bool]:
    """Return (family_to_use, is_exact). Prefers bundled fonts, then installed."""
    for candidate in [preferred, *(fallbacks or [])]:
        if available(candidate):
            return candidate, candidate == preferred
    return preferred, False


def fonts_dir() -> str | None:
    """Path to pass as ffmpeg's `fontsdir=`, or None if we ship nothing."""
    return str(FONTS_DIR) if bundled() else None


def report() -> list[tuple[str, str, bool]]:
    """(theme font, resolved font, exact) for every theme — used by the UI."""
    from .themes import Theme

    rows = []
    for name in Theme.available():
        theme = Theme.load(name)
        resolved, exact = resolve(theme.font, theme.font_fallbacks)
        rows.append((theme.font, resolved, exact))
    return rows
