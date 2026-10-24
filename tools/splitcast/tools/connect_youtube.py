"""Connect YouTube from the console, without the GUI in the way.

Exists because the in-app button proved awkward for a specific, real reason: the
default browser is often NOT the one signed into the right Google account, and
every workaround (copy the link, retry) has to survive a failed first attempt.
Here the link is simply PRINTED - copy it into whichever browser is signed into
the channel, and this waits until a code actually arrives.

    py -3 tools/connect_youtube.py

Close SplitCast first; it holds the same callback port.
"""

import io
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from splitcast import config, youtube, youtubeauth   # noqa: E402


LINK_PAGE = """<!doctype html>
<meta charset="utf-8">
<title>SplitCast - connect YouTube</title>
<style>
  body { background:#0e1015; color:#e7e9ee; font-family:"Segoe UI",system-ui,sans-serif;
         display:grid; place-items:center; height:100vh; margin:0; text-align:center; }
  .box { max-width:34rem; padding:2rem 2.5rem; background:#171a22;
         border:1px solid #2a2e3a; border-radius:14px; }
  h1 { font-size:1.4rem; margin:0 0 .5rem; }
  p { color:#9aa2b4; line-height:1.55; }
  a.go { display:inline-block; margin-top:1.2rem; background:#ff4e45; color:#fff;
         text-decoration:none; font-weight:700; padding:.8rem 1.6rem; border-radius:.6rem; }
</style>
<div class="box">
  <h1>Connect YouTube to SplitCast</h1>
  <p>Make sure this browser is signed into the account that owns your
     YouTube channel, then click below.</p>
  <a class="go" href="__URL__">Sign in with Google</a>
  <p style="margin-top:1.2rem;font-size:.85em">Clicking the link avoids copying
     the address by hand, which is what breaks it.</p>
</div>
"""

# Built with os.path.join, never as literal path strings. A path containing
# the two characters backslash-b has been rewritten in transit into a raw
# BACKSPACE byte seven times in this project - invisible in an editor,
# invisible in a diff, and it silently turns the path into
# "Applicationrave.exe" so Brave is simply never found. Building the path
# from parts makes that failure impossible.
_BRAVE_TAIL = os.path.join("BraveSoftware", "Brave-Browser", "Application",
                           "brave.exe")
BROWSERS = [
    (os.path.join(os.environ.get("LOCALAPPDATA", ""), _BRAVE_TAIL), "Brave"),
    (os.path.join(os.environ.get("PROGRAMFILES", ""), _BRAVE_TAIL), "Brave"),
    (os.path.join(os.environ.get("PROGRAMFILES(X86)", ""), _BRAVE_TAIL), "Brave"),
]


def launch_in_browser(url):
    """Open the link in Brave if it is installed.

    Brave is tried FIRST here, not the system default: the default browser is
    the one that was already signed into the wrong account, which is the whole
    problem being worked around.
    """
    import subprocess
    for raw, name in BROWSERS:
        path = os.path.expandvars(raw)
        if "%" in path or not os.path.exists(path):
            continue
        try:
            subprocess.Popen([path, url], creationflags=0x00000008 | 0x08000000,
                             close_fds=True)
            return name
        except OSError:
            continue
    return None


def main():
    cfg = config.load()
    cid = cfg.get("youtube_client_id", "").strip()
    sec = cfg.get("youtube_client_secret", "").strip()
    if not cid:
        print("No Client ID saved. Put it in SplitCast section 7 first.")
        return 2

    verifier = youtube.make_verifier()
    url = youtube.authorize_url(cid, verifier)

    # A 383-character URL copied out of a Windows console arrives BROKEN: cmd
    # wraps long lines and the selection takes the wrap with it, so the pasted
    # URL is truncated or has newlines in it - which Google answers with a bare
    # "400. That's an error." Nothing here should require copying it by hand.
    link_file = os.path.join(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))), "connect-youtube.html")
    io.open(link_file, "w", encoding="utf-8").write(LINK_PAGE.replace("__URL__", url))

    launched = launch_in_browser(url)

    print("=" * 74)
    if launched:
        print("Opened in:", launched)
    print("If that is not the browser signed into your YouTube account, open")
    print("this file in the right one and click the button:")
    print()
    print("   " + link_file)
    print()
    print("(Do NOT copy the raw URL out of this window - it wraps and breaks.)")
    print("=" * 74)
    print("Waiting for Google... (Ctrl+C to give up)")
    print("A failed attempt is fine - this keeps listening, so just try again.")
    print()

    result = {}

    def work():
        try:
            result["tok"] = youtubeauth.connect(cid, sec, timeout=600,
                                                open_browser=False,
                                                verifier=verifier)
        except Exception as exc:                    # noqa: BLE001
            result["err"] = str(exc)

    t = threading.Thread(target=work, daemon=True)
    t.start()
    while t.is_alive():
        t.join(1.0)

    if "err" in result:
        print("FAILED:", result["err"])
        print()
        if "not completed the Google verification" in result["err"] or \
                "access_denied" in result["err"]:
            print("Most likely: your YouTube account is not in the Test users")
            print("list. Google Auth Platform > Audience > Test users > Add.")
        return 1

    tok = result["tok"]
    cfg = config.load()          # re-read: the GUI may have written since
    cfg["youtube_access"] = tok["access"]
    cfg["youtube_refresh"] = tok["refresh"]
    cfg["youtube_expires_at"] = tok["expires_at"]
    config.save(cfg)

    print("CONNECTED. Token saved.")
    print("  refresh token: stored (no need to do this again)")
    print("  access token expires:",
          time.strftime("%H:%M:%S", time.localtime(tok["expires_at"])),
          "- refreshed automatically")
    print()
    print("Restart SplitCast and section 7 will read 'connected'.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
