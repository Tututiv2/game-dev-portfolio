"""Build a shareable zip of the launcher -- code only, none of your stuff.

The launcher and its ``data/`` folder are deliberately separate: the code knows how to
download and install, and everything it downloads or that you personally chose lives in
``data/``. So sharing is a matter of shipping one and never the other. Whoever unzips this
gets an empty launcher and picks their own versions and mods.

What would be embarrassing to ship is exactly what this refuses to ship: instances, mod
jars, worlds, logs, the account store (which holds a DPAPI-sealed refresh token -- useless
on another machine, but still nobody's business), and the internal notes that are full of
local paths and server addresses.

Belt and braces: after selecting files, every text file going into the archive is scanned
for the owner's username, home directory, local server addresses and Windows drive paths.
If any turn up the build fails rather than quietly shipping them.
"""
from __future__ import annotations

import os
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from launcher.paths import APP_NAME, APP_VERSION  # noqa: E402

#: Shipped. Everything the launcher needs to run on a machine that has never seen it.
#: ``tests/`` is deliberately left out: its fixtures are the owner's real username, paths
#: and server addresses, and a friend has no use for the suite anyway.
INCLUDE_DIRS = ("launcher",)
INCLUDE_FILES = ("run_ui.py", f"{APP_NAME}.bat")

#: Never shipped, whatever else changes.
EXCLUDE_DIRS = {"data", "_public", "tools", "__pycache__", ".git", ".idea", ".vscode"}
EXCLUDE_SUFFIXES = {".pyc", ".pyo", ".log", ".zip"}

#: Internal notes: real paths, real server addresses, a real mod list.
EXCLUDE_FILES = {"CLAUDE.md", "KNOWN_LIMITATIONS.md", "README.md"}

#: Strings that legitimately contain the owner's *public* GitHub handle and must not be
#: mistaken for a leak: the repository this is published from, and the update endpoint.
#: These are already on the public internet by design. They are masked out before the
#: patterns below run, so ``C:\\Users\\you\\`` is still caught while
#: ``github.com/Tututiv2/lovely`` is not.
PUBLIC_IDENTIFIERS = (
    "https://github.com/Tututiv2/lovely",
    "github.com/Tututiv2/lovely",
    "Tututiv2/lovely",
)

#: Anything matching these must not appear in a text file in the archive.
LEAKS = (
    (re.compile(r"Tutut", re.I), "the owner's username"),
    (re.compile(r"[A-Za-z]:\\\\?[Uu]sers\\"), "a Windows user directory"),
    (re.compile(r"gameZ|MyFireServer|Ai Game"), "a local project path"),
    (re.compile(r"localhost:255\d\d"), "a local server address"),
    (re.compile(r"outlook\.com|@live\."), "an email address"),
    (re.compile(r"secondwatch", re.I), "a private mod name"),
)

INSTALL_NOTE = f"""{APP_NAME} - a Minecraft launcher
==================================

WHAT IS IT
    Every Minecraft version installs into its own folder, so mods from one
    version can never break another. Vanilla, Fabric, Quilt, Forge, NeoForge.
    It downloads the right Java for each version by itself.

WHAT YOU NEED
    Python 3.10 or newer.  https://www.python.org/downloads/
    IMPORTANT: tick "Add python.exe to PATH" in the installer.
    That is the only thing you have to install. Everything else - Java, the
    game files - the launcher fetches itself.

HOW TO RUN IT
    Double-click  {APP_NAME}.bat

    First launch makes a "data" folder next to it. That is where your
    instances, mods, saves and downloads live. Move or delete that folder and
    nothing else is affected.

MAKING AN INSTANCE
    + New  ->  name it, pick a Minecraft version, pick a mod loader  ->  Create
    Then "Mods" opens that instance's own mods folder. Drop jars in there.
    Mods you put in one instance cannot affect any other instance.

SIGNING IN
    You need your own Microsoft account that owns Minecraft: Java Edition.
    Click the account chip, top right. You will get a code to type into a
    Microsoft sign-in page in your browser - the launcher never sees your
    password.

    Note: this launcher is pending Microsoft's app review. Until that is
    approved, sign-in returns an error saying the app is not approved yet.
    That is a status, not a bug, and there is nothing you can do to speed it up.

IT WILL NOT TOUCH YOUR EXISTING GAME
    Your normal %appdata%\\.minecraft folder is never written to. The launcher
    refuses to use it, deliberately.

Not affiliated with Mojang or Microsoft.
"""


def wanted(path: Path) -> bool:
    rel = path.relative_to(ROOT)
    if any(part in EXCLUDE_DIRS for part in rel.parts):
        return False
    if path.suffix.lower() in EXCLUDE_SUFFIXES:
        return False
    if path.name in EXCLUDE_FILES:
        return False
    return True


def collect() -> list[Path]:
    files: list[Path] = []
    for name in INCLUDE_DIRS:
        for path in sorted((ROOT / name).rglob("*")):
            if path.is_file() and wanted(path):
                files.append(path)
    for name in INCLUDE_FILES:
        path = ROOT / name
        if path.is_file():
            files.append(path)
        else:
            print(f"  ! missing {name}")
    return files


def scan_for_leaks(files: list[Path]) -> list[str]:
    problems = []
    for path in files:
        if path.suffix.lower() not in (".py", ".bat", ".md", ".txt", ".json", ".cfg"):
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        # Mask the deliberately-public identifiers, preserving length so reported line
        # numbers stay accurate.
        for public in PUBLIC_IDENTIFIERS:
            text = text.replace(public, "#" * len(public))
        for pattern, what in LEAKS:
            for match in pattern.finditer(text):
                line = text[:match.start()].count("\n") + 1
                problems.append(
                    f"{path.relative_to(ROOT)}:{line}  {what}  -> {match.group(0)!r}")
    return problems


#: The one file a public build rewrites, and the exact edit it makes.
DEV_GATE_FILE = Path("launcher") / "accounts" / "local.py"
DEV_GATE_FROM = "PUBLIC_BUILD = False"
DEV_GATE_TO = "PUBLIC_BUILD = True"


def seal_dev_gate(text: str) -> str:
    if DEV_GATE_FROM not in text:
        raise SystemExit(
            f"Cannot seal the dev gate: {DEV_GATE_FROM!r} not found in {DEV_GATE_FILE}. "
            "Refusing to ship a public build that might still enable the offline profile.")
    return text.replace(DEV_GATE_FROM, DEV_GATE_TO, 1)


def build(strict: bool = True, public: bool = False) -> Path:
    files = collect()
    print(f"{len(files)} files selected"
          + ("  (public build: dev profile sealed off)" if public else ""))

    problems = scan_for_leaks(files)
    if problems:
        print(f"\n{len(problems)} thing(s) that should not be shared:\n")
        for p in problems[:40]:
            print("   ", p)
        if strict:
            raise SystemExit(
                "\nRefusing to build. Fix those, or run with --force if they are "
                "false positives.")
        print("\n--force given; shipping anyway.\n")
    else:
        print("leak scan: clean")

    dest = ROOT / f"{APP_NAME}-{APP_VERSION}.zip"
    sealed = False
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in files:
            rel = path.relative_to(ROOT)
            arcname = str(Path(APP_NAME) / rel)
            if public and rel == DEV_GATE_FILE:
                zf.writestr(arcname, seal_dev_gate(path.read_text(encoding="utf-8")))
                sealed = True
            else:
                zf.write(path, arcname)
    if public and not sealed:
        raise SystemExit(f"public build never reached {DEV_GATE_FILE}; aborting")
        zf.writestr(str(Path(APP_NAME) / "HOW TO INSTALL.txt"), INSTALL_NOTE)
        public_readme = ROOT / "_public" / "README.md"
        if public_readme.is_file():
            zf.write(public_readme, str(Path(APP_NAME) / "README.md"))

    size = dest.stat().st_size
    print(f"\nbuilt {dest.name}  ({size / 1024:.0f} KB)")
    return dest


if __name__ == "__main__":
    build(strict="--force" not in sys.argv, public="--public" in sys.argv)
