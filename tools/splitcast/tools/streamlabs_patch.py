"""Re-apply (or remove) the Streamlabs ingest patch.

WHY THIS EXISTS
---------------
Streamlabs will not send a stream to a local process by any supported route:

  * a "custom destination" is fanned out from Streamlabs' CLOUD, so its
    127.0.0.1 is their server's loopback and can never reach this machine;
  * service.json is rewritten from their cloud config at Go Live, and making it
    read-only does not help - the destination is held in memory;
  * platform destinations (YouTube) ARE dialled locally, and their address comes
    from this file.

So the one thing that works is pointing YouTube's ingest entries at SplitCast.
Streamlabs then hands its horizontal stream to the relay over localhost, and
SplitCast forwards it to the real YouTube and to Twitch (with music). The
vertical Dual Output stream is unaffected: its URL is hardcoded in the app
bundle, not read from here, so it goes straight to YouTube on its own.

This is a patch to a file Streamlabs owns. A Streamlabs update replaces it, and
the failure is SILENT - streaming simply goes to real YouTube and SplitCast sits
waiting, which looks like SplitCast being broken. Run `check` if that happens.

    py -3 tools/streamlabs_patch.py check     what state is it in
    py -3 tools/streamlabs_patch.py apply     point YouTube ingests at SplitCast
    py -3 tools/streamlabs_patch.py revert    restore the originals
"""

import io
import json
import os
import shutil
import sys

SERVICES = os.path.expandvars(
    r"%APPDATA%\slobs-client\plugin_config\rtmp-services\services.json")
BACKUP = SERVICES + ".splitcast-backup"

LOCAL = "rtmp://127.0.0.1:1935/live2"

# The backup entry is deliberately left pointing at the real YouTube. If the
# relay is down, streaming fails loudly on the primary rather than silently
# succeeding straight to YouTube with no music and no Twitch.
KEEP_REAL = "Backup YouTube ingest server (legacy RTMP)"


def _load(path):
    return json.load(io.open(path, encoding="utf-8"))


def _youtube_services(doc):
    for svc in doc.get("services", []):
        name = svc.get("name", "").lower()
        if name.startswith("youtube") and "hls" not in name:
            yield svc


def check():
    if not os.path.exists(SERVICES):
        print("services.json not found - is Streamlabs installed?")
        return 2
    doc = _load(SERVICES)
    patched = total = 0
    print("YouTube ingest entries:")
    for svc in _youtube_services(doc):
        for srv in svc.get("servers") or []:
            total += 1
            local = "127.0.0.1" in srv.get("url", "")
            patched += local
            print("  [{}] {:<44} {}".format(
                "SplitCast" if local else "  real   ", srv.get("name", "")[:44],
                srv.get("url", "")))
    print()
    print("backup present :", os.path.exists(BACKUP))
    if patched == 0:
        print("STATE: NOT PATCHED - Streamlabs will stream straight to YouTube")
        print("       and SplitCast will never receive anything. Run: apply")
        return 1
    print("STATE: patched ({} of {} entries)".format(patched, total))
    return 0


def apply():
    if not os.path.exists(SERVICES):
        print("services.json not found"); return 2
    if not os.path.exists(BACKUP):
        shutil.copy2(SERVICES, BACKUP)
        print("backed up ->", BACKUP)
    doc = _load(SERVICES)
    changed = 0
    for svc in _youtube_services(doc):
        for srv in svc.get("servers") or []:
            if srv.get("name") == KEEP_REAL:
                continue
            if srv.get("url") != LOCAL:
                srv["url"] = LOCAL
                changed += 1
    io.open(SERVICES, "w", encoding="utf-8").write(json.dumps(doc, indent=4))
    print("patched {} entry(ies) -> {}".format(changed, LOCAL))
    print()
    print("RESTART STREAMLABS - it only reads this file at startup.")
    return 0


def revert():
    if not os.path.exists(BACKUP):
        print("no backup found; nothing to restore"); return 2
    shutil.copy2(BACKUP, SERVICES)
    print("restored original services.json")
    print("RESTART STREAMLABS.")
    return 0


if __name__ == "__main__":
    cmd = (sys.argv[1] if len(sys.argv) > 1 else "check").lower()
    sys.exit({"check": check, "apply": apply, "revert": revert}.get(cmd, check)())
