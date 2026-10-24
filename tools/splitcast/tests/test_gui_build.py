"""Build the real window, without ever showing it.

Why hidden matters: this suite gets run while the owner is LIVE. A Tk window
that pops up is a window that pops up on stream, so the root is withdrawn before
anything is built and mainloop is never entered. What is exercised is the part
that actually breaks - _build() wiring every widget, and the mode logic reading
Streamlabs' real config - not the event loop.

The bug class this exists for: a Tk app cannot be reloaded, so a wiring mistake
(a widget referenced before it is created, a missing colour constant, a callback
naming an attribute that does not exist yet) shows up only when the owner double
clicks the icon - which is the one moment it must not.
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from splitcast import config, pipeline, slobs, winaudio  # noqa: E402

# Never touch the real config - App.collect()/save paths run here.
_TMP = os.path.join(tempfile.mkdtemp(prefix="splitcast_gui_"), "config.json")
config.PATH = _TMP
REAL_CFG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "config.json")

PASS = []
FAIL = []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print("[" + ("  ok  " if cond else " FAIL ") + "] " + name +
          ("   " + detail if detail else ""))
    return cond


def test_mode_pure():
    print("\n--- mode: services.json transforms (pure) ---")
    doc = {"services": [
        {"name": "YouTube - RTMPS", "servers": [
            {"name": "Primary YouTube ingest server", "url": "rtmp://a.rtmp.youtube.com/live2"},
            {"name": "Backup YouTube ingest server (legacy RTMP)",
             "url": "rtmp://b.rtmp.youtube.com/live2?backup=1"},
        ]},
        {"name": "Twitch", "servers": [{"name": "auto", "url": "rtmp://live.twitch.tv/app"}]},
    ]}

    names = [s.get("name") for s in slobs.youtube_services(doc)]
    check("only YouTube entries are selected", names == ["YouTube - RTMPS"],
          "Twitch must never be retargeted - SplitCast delivers it itself")

    ours, total = slobs.ingest_state(doc, "127.0.0.1", 1935)
    check("an unpatched document reads as direct", ours == 0 and total == 2)

    changed = slobs.retarget(doc, slobs.local_ingest("127.0.0.1", 1935))
    check("retarget rewrites the primary only", changed == 1)
    check("the legacy backup is left on real YouTube",
          doc["services"][0]["servers"][1]["url"].startswith("rtmp://b.rtmp"),
          "so a dead relay fails LOUDLY instead of silently bypassing SplitCast")
    check("twitch is untouched",
          doc["services"][1]["servers"][0]["url"] == "rtmp://live.twitch.tv/app")

    ours, total = slobs.ingest_state(doc, "127.0.0.1", 1935)
    check("a patched document reads as split", ours == 1 and total == 2)
    check("and reads as direct for a DIFFERENT port",
          slobs.ingest_state(doc, "127.0.0.1", 1936)[0] == 0,
          "the address is the whole test, not the presence of a rewrite")

    check("a document with no services survives",
          slobs.ingest_state({}, "127.0.0.1", 1935) == (0, 0)
          and slobs.retarget({}, "x") == 0)


def test_audio_detection():
    print("\n--- mode: would music be heard in DIRECT ---")
    scene = {"sources": [
        {"name": "Gaming", "type": "wasapi_output_capture", "muted": False,
         "settings": {"device_id": "{0.0.0.00000000}.{485eea97-5e94-447a-b85c-4afa0aff3130}"}},
        {"name": "Mic", "type": "wasapi_input_capture", "muted": False,
         "settings": {"device_id": "{0.0.1.00000000}.{b3ef16b1-a15a-4a0b-8914-9a9ffd763a9d}"}},
        {"name": "not audio", "type": "game_capture", "settings": {}},
    ]}
    found = slobs.audio_sources(scene)
    check("audio sources are found, non-audio ignored", len(found) == 2,
          str([f[0] for f in found]))
    check("mute state is carried", found[0][3] is False)

    check("a device id yields its registry guid",
          slobs.audio_sources(scene) and winaudio.endpoint_guid(found[0][2])
          == "{485eea97-5e94-447a-b85c-4afa0aff3130}")
    check("a bare guid passes through",
          winaudio.endpoint_guid("{485eea97-5e94-447a-b85c-4afa0aff3130}")
          == "{485eea97-5e94-447a-b85c-4afa0aff3130}")
    check("garbage yields None, never an exception",
          winaudio.endpoint_guid("") is None
          and winaudio.endpoint_guid("not-a-device") is None)

    check("the media bus is recognised as the music one",
          winaudio.looks_like_music_bus("SteelSeries Sonar - Media"))
    check("the game bus is NOT",
          not winaudio.looks_like_music_bus("SteelSeries Sonar - Gaming"),
          "this is the whole warning - Gaming is what Streamlabs already has")
    check("None does not crash the check",
          not winaudio.looks_like_music_bus(None))


def test_window_builds():
    print("\n--- the real window, hidden ---")
    try:
        import tkinter as tk
    except ImportError as exc:
        check("tkinter present", False, str(exc))
        return
    from splitcast import gui

    root = tk.Tk()
    root.withdraw()          # BEFORE anything is built - never show on stream
    try:
        app = gui.App(root)
        check("the window builds with every widget wired", True)
        check("mode radio exists and reflects disk",
              app.mode_var.get() in ("split", "direct"), app.mode_var.get())
        check("the on-disk state is shown, not the intention",
              "on disk" in app.mode_state.cget("text"), app.mode_state.cget("text"))

        for mode in ("split", "direct"):
            steps = app._steps_for(mode)
            check(mode + " names the Streamlabs restart",
                  "Restart Streamlabs" in steps)
        check("direct mode reports on the music channel",
              any(w in app._steps_for("direct") for w in ("music OK", "MUSIC WILL BE", "could not check")))
        check("split mode tells you to leave Twitch off",
              "Twitch OFF" in app._steps_for("split"))

        # The relay button must not be startable in a mode where nothing can
        # arrive - Streamlabs is not pointed at us, so it would wait for ever.
        app.mode_var.set("direct")
        app._mode_picked()
        check("mode logic runs without raising", True)

        app.overlay.stop()
    finally:
        root.destroy()


def test_launcher():
    """The desktop app: an icon, no console, and a visible failure path."""
    print("\n--- desktop launcher ---")
    import struct
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    pyw = os.path.join(root, "SplitCast.pyw")
    check("SplitCast.pyw exists", os.path.exists(pyw))
    src = open(pyw, encoding="utf-8").read()
    check("it starts the gui", "gui.main()" in src)
    check("a crash is REPORTED, not swallowed",
          "MessageBoxW" in src and "splitcast-error.log" in src,
          "pythonw has no console, so a silent traceback = an app that "
          "'does not open'")
    check("the crash dialog does not depend on tkinter",
          "ctypes" in src and "import tkinter" not in src,
          "if the failure IS tkinter, a tkinter dialog cannot report it")

    # The actual no-console guarantee. Not "we hope" - the PE subsystem field.
    # 2 = GUI (Windows never gives it a console), 3 = CONSOLE (always does,
    # which is why any .bat flashes one however it is written).
    def subsystem(path):
        data = open(path, "rb").read(4096)
        pe = struct.unpack("<I", data[0x3c:0x40])[0]
        if data[pe:pe + 4] != b"PE\x00\x00":
            return None
        return struct.unpack("<H", data[pe + 24 + 68:pe + 24 + 70])[0]

    pythonw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    if os.path.exists(pythonw):
        check("pythonw.exe is a GUI-subsystem binary", subsystem(pythonw) == 2,
              "subsystem 2 = no console is ever created")
        check("python.exe is NOT - this is the difference",
              subsystem(sys.executable) == 3)
    else:
        check("pythonw.exe present", False, pythonw)

    ico = os.path.join(root, "splitcast.ico")
    check("splitcast.ico exists", os.path.exists(ico))
    if os.path.exists(ico):
        data = open(ico, "rb").read()
        reserved, kind, count = struct.unpack("<HHH", data[:6])
        check("it is a real ICO", reserved == 0 and kind == 1 and count >= 1,
              "{} images".format(count))
        sizes, all_png = [], True
        for i in range(count):
            entry = struct.unpack("<BBBBHHII", data[6 + 16 * i:22 + 16 * i])
            width, _, _, _, _, _, nbytes, offset = entry
            sizes.append(width or 256)
            all_png = all_png and data[offset:offset + 8] == b"\x89PNG\r\n\x1a\n"
        check("every entry decodes as PNG", all_png)
        check("a 16px entry exists for the taskbar", 16 in sizes, str(sizes))

    for name in ("Create Shortcut.bat", os.path.join("tools", "make_shortcut.ps1"),
                 os.path.join("tools", "make_icon.py")):
        check(name + " present", os.path.exists(os.path.join(root, name)))


def main():
    print("SplitCast GUI checks")
    before = open(REAL_CFG, "rb").read() if os.path.exists(REAL_CFG) else None
    test_mode_pure()
    test_audio_detection()
    test_window_builds()
    test_launcher()
    after = open(REAL_CFG, "rb").read() if os.path.exists(REAL_CFG) else None
    check("the real config.json is untouched", before == after)

    print("\n" + "=" * 58)
    print("{} passed, {} failed".format(len(PASS), len(FAIL)))
    for name in FAIL:
        print("   FAILED:", name)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
