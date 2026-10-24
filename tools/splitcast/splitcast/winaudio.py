"""Windows audio endpoint names, straight from the registry.

Why this exists
---------------
Streamlabs records an audio source as a device GUID, not a name:

    {0.0.0.00000000}.{485eea97-5e94-447a-b85c-4afa0aff3130}

which says nothing about whether that is the game channel or the music one. The
friendly name lives in the registry under the same GUID, so this turns the one
into the other and lets the app state a fact instead of an assumption.

It matters for exactly one question, asked when switching to DIRECT mode: is the
music channel among the things Streamlabs encodes? In SPLIT mode it deliberately
is NOT - the relay adds music afterwards, to one platform only. Switch to DIRECT
having never added it and the stream is simply silent where the music should be.
Nothing errors. Nothing warns. It is just missing, which is the worst way for a
feature to fail.

winreg only, no COM, no dependencies. Read-only throughout: nothing here writes
to the registry.
"""

import re

try:
    import winreg
except ImportError:                                   # not Windows
    winreg = None

RENDER = r"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render"

# The full friendly name ("SteelSeries Sonar - Gaming"). The other candidate,
# `{b3f8fa53-...},6`, is the DESCRIPTION and returns "SteelSeries Sonar Virtual
# Audio Device" for all six Sonar channels - identical for every one of them,
# so it cannot tell the music bus from the game bus and is useless here.
FRIENDLY_NAME = "{a45c254e-df1c-4efd-8020-67d146a850e0},2"

DEVICE_STATE_ACTIVE = 1

_GUID = re.compile(r"\{[0-9a-fA-F-]{36}\}\s*$")


def endpoint_guid(device_id):
    """The trailing GUID of a Streamlabs/OBS device id, or None.

    `{0.0.0.00000000}.{485eea97-...}` -> `{485eea97-...}`, which is the registry
    key name. A plain GUID is passed through, so either form works.
    """
    if not device_id:
        return None
    match = _GUID.search(str(device_id))
    return match.group(0) if match else None


def render_endpoints(active_only=True):
    """{guid: friendly name} for the machine's render endpoints.

    Returns {} rather than raising anywhere it cannot read - this is a
    convenience lookup, and a stream must never depend on it.
    """
    if winreg is None:
        return {}
    out = {}
    try:
        root = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, RENDER)
    except OSError:
        return {}
    try:
        index = 0
        while True:
            try:
                guid = winreg.EnumKey(root, index)
            except OSError:
                break
            index += 1
            try:
                dev = winreg.OpenKey(root, guid)
                if active_only:
                    state, _ = winreg.QueryValueEx(dev, "DeviceState")
                    if state != DEVICE_STATE_ACTIVE:
                        continue
                props = winreg.OpenKey(dev, "Properties")
                name, _ = winreg.QueryValueEx(props, FRIENDLY_NAME)
            except OSError:
                continue
            if name:
                out[guid] = str(name)
    finally:
        try:
            winreg.CloseKey(root)
        except OSError:
            pass
    return out


def name_for(device_id, endpoints=None):
    """Friendly name for a Streamlabs device id, or None."""
    guid = endpoint_guid(device_id)
    if not guid:
        return None
    table = render_endpoints() if endpoints is None else endpoints
    return table.get(guid)


def looks_like_music_bus(name):
    """Is this endpoint the one Spotify plays into?

    Sonar's channels are named "SteelSeries Sonar - <Channel>", and Media is the
    music one on this machine (verified: Spotify holds its audio session there).
    Matching on the channel word rather than the whole string so a Sonar rename
    or a different vendor's "Media" bus still reads correctly.
    """
    low = (name or "").lower()
    return "media" in low or "music" in low
