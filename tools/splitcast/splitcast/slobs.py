"""Read the horizontal stream key out of Streamlabs' own config, BEFORE it
connects.

Why this exists
---------------
SplitCast learns YouTube's key from the RTMP `publish` command as it goes past
(rtmpsplit.py), and then has to relaunch ffmpeg to use it. That relaunch drops
the connection Streamlabs has just made - which is documented as "a blip at the
very start of a stream" and was believed to be harmless.

It is not harmless. Measured on 2026-08-28:

    22:13:58.246  horizontal  starting
    22:13:58.264  SplitCast adopts the key and kills ffmpeg   <-- config.json
    22:13:58.565  horizontal  reconnect
    22:13:58.658  horizontal  deactivate
    22:13:58.703  vertical    stopping                        <-- collateral
    22:13:58.750  vertical    stop
    22:14:00.727  horizontal  reconnect_success

The horizontal reconnects, so the blip really is a blip. The VERTICAL does not:
Streamlabs reads the horizontal's deactivate as the stream failing and tears the
vertical output down with it, and nothing ever brings it back. So the vertical
YouTube broadcast sits on "scheduled" for the whole session while the horizontal
plays on both platforms - which is exactly the reported symptom.

The relaunch itself is not the problem. Relaunching while NOTHING is connected
costs nothing at all. The problem is purely that we learn the key 18 ms too
late, because we learn it from the connection it invalidates.

So learn it earlier. Streamlabs writes each display's ingest settings to disk at
Go Live, and it does so with time to spare - 6.5 seconds before the horizontal
opened its socket, in the run above:

    22:13:51.749  service.json.tmp   horizontal -> rtmp://127.0.0.1:1935/live2
    22:13:56.635  service1.json      vertical   -> rtmp://a.rtmp.youtube.com/live2

Adopting from the file turns a mid-connection relaunch into a pre-connection one,
and the vertical is never touched.

The one rule that makes this safe
---------------------------------
`service1.json` holds the VERTICAL's key, for a DIFFERENT YouTube broadcast.
Publishing the horizontal to it would put the wrong rendition on the wrong
broadcast - worse than the bug being fixed. So a file is trusted only when its
server is *our own listen address*: the key we adopt is by construction the key
of the stream that is about to arrive at our door. The vertical's file names
a.rtmp.youtube.com and is rejected without ever being read for its key.

`service.json` is currently marked read-only on this machine (an experiment from
2026-08-25, kept because it is harmless), so Streamlabs' write lands as
`service.json.tmp` and the rename never completes. Both names are read and the
newest wins, so it works either way and nothing here depends on that flag.
"""

import io
import json
import os
import shutil

# Only files Streamlabs writes per display. `.bak` and `.splitcast-backup` are
# deliberately NOT in this set: they are stale by definition, and one of them
# still names 127.0.0.1 with the placeholder key from an old experiment, so it
# would pass the address check on content that means nothing.
NAMES = ("service.json", "service.json.tmp",
         "service1.json", "service1.json.tmp",
         "service2.json", "service2.json.tmp")


def config_dir():
    return os.path.join(os.environ.get("APPDATA", ""), "slobs-client")


def parse_service(text):
    """One Streamlabs service file -> {"server":..., "key":..., "service":...}.

    Returns None for anything unreadable. Pure: takes text, not a path.
    """
    try:
        doc = json.loads(text)
    except (ValueError, TypeError):
        return None
    if not isinstance(doc, dict):
        return None
    settings = doc.get("settings")
    if not isinstance(settings, dict):
        return None
    return {
        "server": str(settings.get("server") or ""),
        "key": str(settings.get("key") or ""),
        "service": str(settings.get("service") or ""),
    }


def _split_authority(server):
    """host, port from an rtmp:// URL, without urlparse.

    urlparse would do, but it returns None for a missing port and raises on a
    malformed one, and this runs against a file another program owns.
    """
    rest = server.split("://", 1)[-1]
    authority = rest.split("/", 1)[0]
    if ":" in authority:
        host, _, port = authority.rpartition(":")
        try:
            return host, int(port)
        except ValueError:
            return authority, 1935
    return authority, 1935


def points_at(server, host, port):
    """Is this service file aimed at OUR listener?

    This is the whole safety property. The vertical display's file names
    a.rtmp.youtube.com, so it can never be mistaken for the horizontal's.
    """
    if not server:
        return False
    shost, sport = _split_authority(server)
    return shost == str(host) and sport == int(port)


def is_youtube(entry):
    """Streamlabs writes `"service": "YouTube - RTMPS"` for a platform
    destination. A `rtmp_custom` file has no service name at all - which is what
    the abandoned 2026-08-25 experiment left behind, pointing at 127.0.0.1 with
    the placeholder key `stream`. Requiring the name keeps that out.
    """
    return "youtube" in (entry.get("service") or "").lower()


def read_key(host, port, directory=None):
    """The YouTube key Streamlabs is about to publish to us with, or None.

    Newest matching file wins. A key from a previous Go Live is not a hazard:
    if it is stale, the publish-command path in rtmpsplit.py still catches the
    mismatch and behaves exactly as it does today. This can only improve on
    that, never do worse.
    """
    directory = directory or config_dir()
    best = None
    best_mtime = -1.0
    for name in NAMES:
        path = os.path.join(directory, name)
        try:
            mtime = os.path.getmtime(path)
            with open(path, "r", encoding="utf-8") as fh:
                entry = parse_service(fh.read())
        except (OSError, UnicodeDecodeError):
            continue
        if not entry or not entry["key"]:
            continue
        if not points_at(entry["server"], host, port):
            continue
        if not is_youtube(entry):
            continue
        if mtime > best_mtime:
            best, best_mtime = entry["key"], mtime
    return best


# ===================================================================== MODE
#
# Two ways to reach two platforms, and the switch between them is one file.
#
#   SPLIT   Streamlabs -> 127.0.0.1 -> our relay -> Twitch (with music)
#                                                -> YouTube (bit-for-bit)
#           Two uploads. Music on one platform only. Needs the patch below.
#
#   DIRECT  Streamlabs -> Streamlabs' cloud -> both platforms
#           One upload. Whatever audio Streamlabs encoded goes to both, so
#           music is on both or on neither. Needs the patch REMOVED.
#
# The patch points YouTube's ingest entries at our listener. It is the only
# supported route in: a "custom destination" is fanned out from Streamlabs'
# CLOUD, so its 127.0.0.1 is their server's loopback, and service.json is
# rewritten from cloud config at Go Live. Platform destinations are the only
# ones dialled locally, and their address comes from this file.
#
# THE TRAP: Streamlabs reads services.json ONCE, at startup. Switching mode
# therefore always costs a Streamlabs restart, and skipping it is silent - in
# SPLIT the stream fails to connect, in DIRECT it goes to real YouTube with no
# music and SplitCast sits waiting, looking broken.

SERVICES_REL = os.path.join("plugin_config", "rtmp-services", "services.json")

# Left pointing at the real YouTube on purpose. If the relay is down, streaming
# fails LOUDLY on the primary rather than silently succeeding straight to
# YouTube with no music and no Twitch.
KEEP_REAL = "Backup YouTube ingest server (legacy RTMP)"


def services_path():
    return os.path.join(config_dir(), SERVICES_REL)


def backup_path():
    return services_path() + ".splitcast-backup"


def local_ingest(host, port):
    return "rtmp://{}:{}/live2".format(host, port)


def youtube_services(doc):
    """The YouTube entries in a services.json document. Pure."""
    for svc in (doc or {}).get("services", []):
        name = (svc.get("name") or "").lower()
        if name.startswith("youtube") and "hls" not in name:
            yield svc


def ingest_state(doc, host, port):
    """(pointing_at_us, total) across every YouTube ingest entry. Pure."""
    local = local_ingest(host, port)
    ours = total = 0
    for svc in youtube_services(doc):
        for srv in svc.get("servers") or []:
            total += 1
            ours += srv.get("url") == local
    return ours, total


def retarget(doc, url, keep=KEEP_REAL):
    """Point every YouTube ingest entry at `url`. Returns how many changed.

    Mutates `doc` and touches nothing else in it, so the file keeps whatever
    else Streamlabs put there. Pure apart from that mutation.
    """
    changed = 0
    for svc in youtube_services(doc):
        for srv in svc.get("servers") or []:
            if keep and srv.get("name") == keep:
                continue
            if srv.get("url") != url:
                srv["url"] = url
                changed += 1
    return changed


def read_services():
    with io.open(services_path(), encoding="utf-8") as fh:
        return json.load(fh)


def current_mode(host, port):
    """"split", "direct", or "unknown" if services.json cannot be read."""
    try:
        ours, total = ingest_state(read_services(), host, port)
    except (OSError, ValueError):
        return "unknown"
    return "split" if ours else "direct"


def set_mode(name, host, port):
    """Switch between split and direct. Returns (ok, detail).

    Going back to direct restores the ORIGINAL file rather than rewriting URLs,
    so whatever Streamlabs shipped - including any entry we never touched - is
    what comes back.
    """
    path = services_path()
    if not os.path.exists(path):
        return False, "services.json not found - is Streamlabs installed?"
    backup = backup_path()

    if name == "direct":
        if not os.path.exists(backup):
            return False, "no backup to restore; leaving services.json alone"
        shutil.copy2(backup, path)
        return True, "restored Streamlabs' own YouTube ingests"

    if name != "split":
        return False, "unknown mode " + repr(name)

    if not os.path.exists(backup):
        shutil.copy2(path, backup)
    doc = read_services()
    changed = retarget(doc, local_ingest(host, port))
    with io.open(path, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(doc, indent=4))
    return True, "pointed {} YouTube ingest(s) at {}".format(
        changed, local_ingest(host, port))


# ------------------------------------------------- what Streamlabs captures
#
# Only matters in DIRECT mode, and it is the thing most likely to be missed.
# Streamlabs encodes the audio sources in its scene collection; the music sits
# on a Sonar channel that is deliberately NOT one of them, because in SPLIT
# mode the music is added later, by the relay. Switch to DIRECT without adding
# it and the stream is simply silent where the music should be - nothing errors,
# nothing warns, it is just missing.

def scene_collection_path():
    """The scene collection Streamlabs last wrote, or None."""
    folder = os.path.join(config_dir(), "SceneCollections")
    best, best_mtime = None, -1.0
    try:
        names = os.listdir(folder)
    except OSError:
        return None
    for name in names:
        if not name.endswith(".json") or name == "manifest.json":
            continue
        path = os.path.join(folder, name)
        try:
            mtime = os.path.getmtime(path)
        except OSError:
            continue
        if mtime > best_mtime:
            best, best_mtime = path, mtime
    return best


def audio_sources(doc):
    """[(name, type, device_id, muted)] for every audio capture in a scene
    collection document. Pure."""
    found = []

    def walk(node):
        if isinstance(node, dict):
            kind = node.get("type")
            if isinstance(kind, str) and "wasapi" in kind:
                settings = node.get("settings") or {}
                found.append((str(node.get("name") or ""), kind,
                              str(settings.get("device_id") or ""),
                              bool(node.get("muted"))))
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(doc)
    return found


def captured_audio():
    """Audio sources Streamlabs is set up to encode, or [] if unreadable."""
    path = scene_collection_path()
    if not path:
        return []
    try:
        with io.open(path, encoding="utf-8") as fh:
            return audio_sources(json.load(fh))
    except (OSError, ValueError):
        return []
