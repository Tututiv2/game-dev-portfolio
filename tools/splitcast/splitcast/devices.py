"""DirectShow audio device enumeration, by asking ffmpeg.

ffmpeg is the thing that will have to open the device, so ffmpeg is what gets
asked which devices exist. Anything else (WMI, the registry, a COM enumeration)
can report a device that ffmpeg then cannot address by that exact name.
"""

import subprocess

# Names Sonar gives its virtual endpoints. Only the two capture-side ones can
# ever be an ffmpeg input; the rest are render endpoints and are listed here
# purely so the GUI can explain why they are not offered.
SONAR_STREAM = "SteelSeries Sonar - Stream"  # prefix - dshow appends the driver name
SONAR_MIC = "SteelSeries Sonar - Microphone"

_NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW - keeps a console off the screen


def _run(argv, timeout=20):
    return subprocess.run(
        argv,
        capture_output=True,
        text=True,
        errors="replace",
        timeout=timeout,
        creationflags=_NO_WINDOW,
    )


def list_audio(ffmpeg):
    """Return the audio capture device names ffmpeg can open, in ffmpeg's order.

    -list_devices always exits non-zero ('Immediate exit requested') because the
    dummy input never opens. That is success here, so the return code is ignored
    and the output is what is read.
    """
    try:
        proc = _run([ffmpeg, "-hide_banner", "-list_devices", "true",
                     "-f", "dshow", "-i", "dummy"])
    except (OSError, subprocess.TimeoutExpired):
        return []
    return parse_devices(proc.stderr or "")


def parse_devices(text):
    """Pull '<name>" (audio)' lines out of ffmpeg's device dump.

    Split out from list_audio so the parser is testable against a captured dump
    with no ffmpeg and no sound card present.
    """
    names = []
    for line in text.splitlines():
        if not line.rstrip().endswith("(audio)"):
            continue
        first = line.find('"')
        last = line.rfind('"')
        if first == -1 or last <= first:
            continue
        name = line[first + 1:last]
        if name and name not in names:
            names.append(name)
    return names


def probe(ffmpeg, device, seconds=1):
    """Actually open the device and read from it.

    Presence in the list does not mean openable - Sonar's Stream endpoint only
    exists while Streamer Mode is on, and an exclusive-mode holder elsewhere can
    take a device that still enumerates fine. Returns (ok, detail).
    """
    argv = [ffmpeg, "-hide_banner", "-nostdin",
            "-f", "dshow", "-i", "audio=" + device,
            "-t", str(seconds), "-f", "null", "-"]
    try:
        proc = _run(argv, timeout=seconds + 20)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, str(exc)
    err = (proc.stderr or "").strip()
    if proc.returncode == 0:
        return True, _format_line(err) or "opened"
    tail = [ln for ln in err.splitlines() if ln.strip()]
    return False, tail[-1] if tail else "returncode {}".format(proc.returncode)


def _format_line(err):
    """The 'Stream #0:0: Audio: pcm_s16le, 48000 Hz, stereo' line, if present."""
    for line in err.splitlines():
        if "Audio:" in line and "Stream #" in line:
            return line.split("Audio:", 1)[1].strip()
    return ""


def find_stream_bus(names):
    """The music bus, by prefix. dshow reports it as
    'SteelSeries Sonar - Stream (SteelSeries Sonar Virtual Audio Device)', so an
    equality test against the short name silently finds nothing."""
    for n in names:
        if n.startswith(SONAR_STREAM):
            return n
    return None


def rank(names):
    """Best guess first. The Sonar Stream bus is the whole point of the setup, so
    it sorts to the top when it is there."""
    def key(n):
        low = n.lower()
        if n.startswith(SONAR_STREAM):
            return (0, n)
        if "cable" in low or "voicemeeter" in low:
            return (1, n)
        if "sonar" in low:
            return (2, n)
        return (3, n)
    return sorted(names, key=key)
