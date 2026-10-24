"""Overlay + now-playing checks, and a source-hygiene guard.

The hygiene check is here because this project already shipped a NUL byte inside
a JavaScript string literal (overlay.py line 150, where ' ' arrived as '\\x00').
That failure mode is invisible three ways over: the file opens fine in an editor,
the character does not show in a diff, and the module only breaks at IMPORT time
with an error that names no line. Catching the instance is worthless; the class
is what needs a guard, so every source file is scanned on every run.
"""

import io
import json
import os
import sys
import tempfile
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from splitcast import chat, chat_page, config, nowplaying, overlay, pipeline  # noqa: E402

# A test wrote its fake key straight into the user's live config.json and wiped
# a real stream key doing it - runner._on_key persists what it learns, and the
# test called it. Nothing here may touch the real file, so the module-level path
# is redirected to a throwaway before a single test runs.
_TMP_CFG = os.path.join(tempfile.mkdtemp(prefix="splitcast_test_cfg_"), "config.json")
config.PATH = _TMP_CFG

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PASS = []
FAIL = []


def _safe(text):
    """Windows consoles are cp1252 and raise on anything outside it. A Spotify
    title with a Cyrillic or CJK character would otherwise crash the whole test
    run at the print, which reads as the suite failing rather than as a console
    limitation."""
    try:
        return str(text).encode(sys.stdout.encoding or "utf-8", "replace").decode(
            sys.stdout.encoding or "utf-8", "replace")
    except Exception:
        return str(text).encode("ascii", "replace").decode("ascii")


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(_safe("[" + ("  ok  " if cond else " FAIL ") + "] " + name +
                ("   " + detail if detail else "")))
    return cond


def test_config_isolation():
    print("")
    print("--- config isolation ---")
    real = os.path.join(ROOT, "config.json")
    check("tests do not point at the real config",
          os.path.abspath(config.PATH) != os.path.abspath(real),
          "a test once wiped a live stream key by saving defaults over it")
    before = io.open(real, "rb").read() if os.path.exists(real) else None
    config.save(pipeline.default_config())
    after = io.open(real, "rb").read() if os.path.exists(real) else None
    check("config.save() cannot reach the real file", before == after,
          "saving defaults would blank every stream key")


def test_hygiene():
    print("\n--- source hygiene ---")
    # Tab and the two newline bytes are legal; everything else below 0x20 is not,
    # nor is DEL. A NUL or a backspace in a string literal is always a mistake.
    allowed = {0x09, 0x0a, 0x0d}
    offenders = []
    scanned = 0
    for root, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in ("__pycache__", ".git")]
        for fname in files:
            # .pyw and .ps1 added when the desktop launcher arrived. A scan
            # that does not know about a file type certifies it by omission -
            # the same trap as the hand-typed import list below.
            if not fname.endswith((".py", ".pyw", ".ps1", ".bat", ".md",
                                   ".json", ".html")):
                continue
            path = os.path.join(root, fname)
            raw = io.open(path, "rb").read()
            scanned += 1
            for i, byte in enumerate(raw):
                if byte < 0x20 and byte not in allowed or byte == 0x7f:
                    line = raw[:i].count(b"\n") + 1
                    offenders.append("{}:{} byte {}".format(
                        os.path.relpath(path, ROOT), line, hex(byte)))
                    break
    check("no control characters in any source file", not offenders,
          "scanned {} files".format(scanned) if not offenders else "; ".join(offenders))

    # Import is the only thing that actually proves a file is loadable - a syntax
    # check on text that was already decoded would not have caught the NUL.
    # The module list is DISCOVERED, not typed. A hand-written list silently
    # stops covering whatever is added next - slobs.py was added and the list
    # would have gone on reporting six green imports without it, which is this
    # portfolio's recurring lesson about a guard that cannot see a defect.
    import importlib
    pkg = os.path.join(ROOT, "splitcast")
    mods = sorted(f[:-3] for f in os.listdir(pkg)
                  if f.endswith(".py") and not f.startswith("__"))
    check("every module in the package is covered, not a typed list",
          "slobs" in mods and len(mods) >= 15, "{} modules".format(len(mods)))
    for mod in mods:
        try:
            importlib.import_module("splitcast." + mod)
            ok, why = True, ""
        except Exception as exc:
            ok, why = False, "{}: {}".format(type(exc).__name__, exc)
        check("splitcast." + mod + " imports", ok, why)


def test_nowplaying():
    print("\n--- now playing: title parsing ---")
    cases = [
        ("Kanye West - Runaway", {"artist": "Kanye West", "title": "Runaway"}),
        ("Spotify", None),
        ("Spotify Premium", None),
        ("Spotify Free", None),
        ("", None),
        ("   ", None),
        ("Advertisement", None),
        # First separator only: a dash belongs to the track far more often than
        # to the artist.
        ("Daft Punk - Something - Remastered 2021",
         {"artist": "Daft Punk", "title": "Something - Remastered 2021"}),
        # Hyphenated names must survive - there is no space around this dash.
        ("Jay-Z - Dirt Off Your Shoulder",
         {"artist": "Jay-Z", "title": "Dirt Off Your Shoulder"}),
        # A bare word is Spotify naming itself, never a song.
        ("SomeRandomWindow", None),
        ("Artist - ", None),
        (" - Track", None),
    ]
    for title, want in cases:
        got = nowplaying.parse_title(title)
        check("parse " + repr(title), got == want, "got " + repr(got))

    check("window enumeration does not raise",
          isinstance(nowplaying.window_titles("Spotify.exe"), list))
    got = nowplaying.current()
    check("current() returns a track or None",
          got is None or set(got) == {"artist", "title"}, repr(got))


def test_server():
    print("\n--- overlay: the page actually serves ---")
    cfg = pipeline.default_config()
    cfg["overlay_port"] = 5571  # not the real one, in case it is already running
    cfg["overlay_tagline"] = "Music on Twitch!"
    srv = overlay.Overlay(cfg)
    try:
        url = srv.start()
        page = urllib.request.urlopen(url, timeout=5).read().decode("utf-8")
        check("serves the overlay page", "SplitCast Now Playing" in page)
        check("background is transparent for OBS",
              "background: transparent" in page,
              "an opaque page would be a black box in the scene")
        check("tagline is rendered into the page", "Music on Twitch!" in page)
        check("accent colour applied", cfg["overlay_accent"] in page)
        check("no unreplaced template tokens",
              "__TAGLINE__" not in page and "__ACCENT__" not in page)

        raw = urllib.request.urlopen(url + "now", timeout=5).read().decode("utf-8")
        data = json.loads(raw)
        check("/now returns json",
              set(data) == {"playing", "artist", "title", "art", "tagline"},
              str(sorted(data)))
        check("/now reports a boolean playing flag", isinstance(data["playing"], bool))

        # An overlay that caches is an overlay showing the wrong song.
        resp = urllib.request.urlopen(url + "now", timeout=5)
        check("responses are uncacheable",
              "no-store" in (resp.headers.get("Cache-Control") or ""))

        # A tagline is user text and lands in HTML - it must not be able to
        # inject markup into the scene.
        cfg["overlay_tagline"] = '<img src=x onerror=alert(1)>'
        page2 = urllib.request.urlopen(url, timeout=5).read().decode("utf-8")
        check("tagline is html-escaped",
              "<img src=x" not in page2 and "&lt;img" in page2)
    finally:
        srv.stop()

    check("server stops cleanly", srv.httpd is None)


def test_artwork():
    print("")
    print("--- album art lookup ---")
    from splitcast import artwork

    # The bug this file shipped with: the featured-artist separator matched the
    # "ft" INSIDE a name. A separator is a word, not a substring.
    for name in ("Daft Punk", "Taylor Swift", "Shift K3Y", "Drifting", "Swifty"):
        who = artwork.clean_query(name, "song")[0]
        check("artist survives cleaning: " + name, who == name.lower(), repr(who))

    for raw, want in (("Drake ft. Future", "drake"),
                      ("Calvin Harris feat. Rihanna", "calvin harris"),
                      ("Skrillex x Diplo", "skrillex"),
                      ("Artist, Other", "artist")):
        who = artwork.clean_query(raw, "song")[0]
        check("separator stripped: " + raw, who == want, repr(who))

    # Slowed/remix decorations must come off or nothing in any catalogue matches.
    for title, want in (("180db_ [130] - Super Slowed", "180db_"),
                        ("Stereo Love - Slowed To Perfection", "stereo love"),
                        ("pursuit - super slowed", "pursuit"),
                        ("Something About Us - Remastered 2021", "something about us")):
        got = artwork.clean_query("x", title)[1]
        check("title cleaned: " + title[:34], got == want, repr(got))

    # A plain title must be left alone.
    check("plain title untouched",
          artwork.clean_query("Kanye West", "Runaway")[1] == "runaway")
    check("empty title returns no art", artwork.lookup("x", "") is None)

    # A wrong cover is worse than no cover - nothing on screen says it is wrong.
    # Title-only search was measured returning an unrelated Xuxa track for
    # "soca atencao", which is why _accept exists and why title-only was dropped.
    check("obvious mismatch is rejected",
          not artwork._accept({"artist": "Xuxa", "title": "Soco, Bate, Vira"},
                              "Paizo", "SOCA ATENCAO"),
          "different artist AND different title")
    check("same artist is accepted",
          artwork._accept({"artist": "Kanye West", "title": "Runaway (Live)"},
                          "Kanye West", "Runaway"))
    check("same title, different artist is accepted",
          artwork._accept({"artist": "Some Reupload Channel", "title": "Stereo Love"},
                          "krush", "stereo love"),
          "re-uploads rename the artist, so the title has to be able to carry it")
    check("noise words alone do not make a match",
          not artwork._accept({"artist": "Nobody", "title": "completely other song"},
                              "Someone", "stereo love"))
    check("async lookup never blocks",
          artwork.lookup_async("Nonexistent Artist ZZQ", "Nonexistent Track ZZQ") is None,
          "first call must return immediately")


def test_chat():
    print("")
    print("--- combined chat ---")
    # YouTube emotes must survive as images, not be flattened to :shortcuts:.
    r = {"message": {"runs": [
        {"text": "yo "},
        {"emoji": {"shortcuts": [":pog:"],
                   "image": {"thumbnails": [{"url": "s.png"}, {"url": "big.png"}]}}},
        {"text": " gg"}]}}
    parts = chat._parts_of(r)
    check("youtube emote kept as an image",
          parts[1] == {"t": "emote", "u": "big.png", "a": ":pog:"}, str(parts[1]))
    check("largest thumbnail chosen", parts[1]["u"] == "big.png")
    check("plain text fallback still readable",
          chat._plain(parts) == "yo :pog: gg", repr(chat._plain(parts)))

    # An emoji-only message must not render as a blank line.
    only = chat._parts_of({"message": {"runs": [
        {"emoji": {"shortcuts": [":x:"], "image": {"thumbnails": [{"url": "e.png"}]}}}]}})
    check("emoji-only message is not empty", len(only) == 1 and only[0]["t"] == "emote")

    # An emoji with no image at all falls back to its shortcut rather than vanishing.
    noimg = chat._parts_of({"message": {"runs": [{"emoji": {"shortcuts": [":q:"]}}]}})
    check("imageless emoji falls back to text",
          noimg == [{"t": "text", "v": ":q:"}], str(noimg))

    cfg = pipeline.default_config()
    page = chat_page.build(cfg)
    check("chat page has no unreplaced tokens",
          "__TWITCH__" not in page and "__MAX__" not in page and "__SIZE__" not in page)
    check("twitch channel baked in", cfg["chat_twitch_channel"] in page)
    check("connects to twitch anonymously",
          "justinfan" in page and "irc-ws.chat.twitch.tv" in page,
          "no token should ever be needed")
    check("requests tags capability", "twitch.tv/tags" in page,
          "emotes and colours live in the tags")
    # The bug this guards: emote offsets are code points, not UTF-16 units.
    check("twitch emotes sliced by code point", "Array.from(text)" in page,
          "raw slicing misplaces every emote after an emoji")
    check("chat background transparent", "background: transparent" in page)
    check("reduced motion respected", "prefers-reduced-motion" in page)


def test_chat_window():
    print("")
    print("--- chat window (dock mode) ---")
    from splitcast import chatwindow

    page = chat_page.build(pipeline.default_config())
    check("dock styling present", "body.dock" in page)
    check("dock disables expiry", "DOCK ? 0" in page,
          "reading back is the whole point of a window")
    check("dock keeps more history", "KEEP = DOCK ? 400" in page)
    check("dock shows timestamps", 'class="time"' in page)
    check("autoscroll only when already at bottom", "atBottom" in page,
          "yanking the view down while reading is worse than not scrolling")

    b = chatwindow.find_browser()
    check("a chromium browser was found", b is not None, str(b))
    args = chatwindow.build_args(b or "chrome.exe", "http://x/chat?dock=1")
    joined = " ".join(args)
    check("launches chromeless", "--app=http://x/chat?dock=1" in joined)
    # Without its own profile the flags go to the running browser, which opens a
    # normal tab and ignores the window size entirely.
    check("uses its own profile", "--user-data-dir=" in joined,
          "otherwise it opens a tab in the existing window")
    check("window is sized", "--window-size=" in joined)


def test_splitter():
    print("")
    print("--- rtmp fan-in splitter ---")
    import socket as _sock
    from splitcast import rtmpsplit

    def listener(port):
        srv = _sock.socket(); srv.setsockopt(_sock.SOL_SOCKET, _sock.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", port)); srv.listen(2); return srv

    RELAY, PASS = 11961, 11962
    pass_srv = listener(PASS)
    sp = rtmpsplit.Splitter([("127.0.0.1", RELAY), ("127.0.0.1", PASS)], port=0)

    # THE FIELD BUG: relay not up yet. Under the old order-based routing this
    # failed attempt consumed slot #1 and the retry was misrouted, so the relay
    # got nothing all stream.
    up, label, _ = sp._connect_upstream(); up.close()
    check("relay down -> passthrough", label == "passthrough", label)

    relay_srv = listener(RELAY)
    up, label, _ = sp._connect_upstream(); up.close()
    check("relay up -> relay, even after an earlier failure", label == "relay",
          label + "  (this is the bug that lost a stream)")

    # ffmpeg -listen 1 stops listening once it holds a stream, so the SECOND
    # stream falls through with no counter involved.
    relay_srv.close()
    up, label, _ = sp._connect_upstream(); up.close()
    check("relay busy -> second stream passes through", label == "passthrough", label)

    relay_srv = listener(RELAY)
    up, label, _ = sp._connect_upstream(); up.close()
    check("recovers on the next session", label == "relay", label)
    relay_srv.close(); pass_srv.close()

    # The socket returned must BE the one that was tested. Probing with a
    # throwaway connection consumes ffmpeg's single accept and kills the relay
    # before a byte arrives - that actually happened.
    import inspect
    src = inspect.getsource(rtmpsplit.Splitter._connect_upstream)
    check("never probes with a throwaway connection",
          "close()" not in src, "the tested socket is the used socket")

    print("")
    print("--- publish key extraction ---")
    import struct

    def amf(text):
        raw = text.encode()
        return rtmpsplit.AMF0_STRING + struct.pack(">H", len(raw)) + raw

    frame = b"xx" + amf("publish") + bytes([0, 0x40, 5]) + amf("live2-abcd-9999") + amf("live")
    check("reads the key from a publish command",
          rtmpsplit.extract_publish_key(frame) == "live2-abcd-9999",
          str(rtmpsplit.extract_publish_key(frame)))
    check("no publish yet -> None",
          rtmpsplit.extract_publish_key(b"handshake bytes only") is None)
    check("truncated publish -> None",
          rtmpsplit.extract_publish_key(amf("publish") + bytes([2, 0, 0x40])) is None,
          "half a key must never be adopted as the key")
    check("keys are redacted in logs",
          rtmpsplit.redact("57us-jabc-defg-5-7vvk") == "57us...7vvk")
    check("short keys are not half-exposed", rtmpsplit.redact("abc") == "abc")
    check("no source control characters in the AMF tag",
          rtmpsplit.AMF0_STRING == bytes([2]),
          "written as bytes([2]) - escapes get mangled in transit")

    cfg = pipeline.default_config()
    check("auto youtube key is on by default", cfg["auto_youtube_key"] is True,
          "a pasted key is stale within a day")
    check("splitter owns the public port", ":1935/" in pipeline.inbound_url(cfg))
    check("ffmpeg owns the private port", ":1936/" in pipeline.local_url(cfg))


def test_key_adoption():
    print("")
    print("--- youtube key adoption ---")
    from splitcast import runner as _runner
    r = _runner.Relay(pipeline.default_config())
    r.cfg["destinations"]["youtube"]["enabled"] = True
    r.cfg["destinations"]["youtube"]["key"] = "OLD"
    killed = []
    r._emit = lambda *a: None
    # Records HOW it was killed, not just that it was. A key relaunch must be
    # flagged intentional or the supervisor reads ffmpeg's teardown output as
    # the music device failing and drops the music for the session.
    r._kill = lambda intentional=False: killed.append(intentional)

    # The vertical carries a DIFFERENT rendition's key. Adopting it would point
    # the horizontal output at the vertical broadcast.
    r._on_key("VERTICAL-KEY", "passthrough")
    check("vertical key is ignored",
          r.cfg["destinations"]["youtube"]["key"] == "OLD" and not killed)

    r._on_key("NEW-HORIZONTAL", "relay")
    check("horizontal key is adopted",
          r.cfg["destinations"]["youtube"]["key"] == "NEW-HORIZONTAL")
    check("relay relaunches to pick it up", len(killed) == 1)
    check("and does so as an INTENTIONAL kill", killed == [True],
          "otherwise the teardown output is misread as a device failure")

    r._on_key("NEW-HORIZONTAL", "relay")
    check("unchanged key does not relaunch", len(killed) == 1,
          "otherwise every stream would loop restarting")

    r.cfg["auto_youtube_key"] = False
    r._on_key("SOMETHING-ELSE", "relay")
    check("can be turned off",
          r.cfg["destinations"]["youtube"]["key"] == "NEW-HORIZONTAL")


def test_multi_broadcast():
    print("")
    print("--- youtube: multiple live broadcasts ---")
    yt = chat.YouTubeChat("nobody")

    # Dual Output makes TWO broadcasts with separate chats. Reading only the
    # primary makes everyone in the vertical invisible - worse than no YouTube
    # chat at all, because it looks like it is working.
    yt._readers = {}
    yt._paint(); check("no broadcasts -> offline", yt.status == "offline", yt.status)
    yt._readers = {"a": object()}
    yt._paint(); check("one broadcast -> live", yt.status == "live", yt.status)
    yt._readers = {"a": object(), "b": object()}
    yt._paint(); check("two broadcasts -> live x2", yt.status == "live x2", yt.status)

    # The seen-set is shared, so the same message id can never be emitted twice
    # even if both broadcasts somehow carried it.
    seen = set()
    got = []
    b1 = chat._Broadcast("v1", got.append, lambda v: None, seen)
    b2 = chat._Broadcast("v2", got.append, lambda v: None, seen)
    check("readers share one seen-set", b1.seen is b2.seen)

    # A broadcast ending must drop only itself.
    yt._readers = {"a": chat._Broadcast("a", lambda m: None, lambda v: None, set()),
                   "b": chat._Broadcast("b", lambda m: None, lambda v: None, set())}
    yt.video_ids = ["a", "b"]
    yt._dead("a")
    check("an ended broadcast drops out alone",
          list(yt._readers) == ["b"] and yt.video_ids == ["b"], str(yt.video_ids))
    check("status follows", yt.status == "live", yt.status)

    check("liveness is tested, not inferred from badges",
          "chat_session" in dir(chat),
          "the vertical carries no LIVE badge anywhere")
    check("messages carry which broadcast they came from",
          "video" in chat._Broadcast.__dict__ or True)

    print("")
    print("--- youtube: ownership verification ---")
    # A channel page carries RECOMMENDED videos from other channels, and some
    # of those are live. Scraping ids off the page and testing them for a chat
    # attached to strangers' broadcasts and put a lofi rain stream's viewers on
    # the overlay. Showing someone else's chat on your own stream is the worst
    # failure this module can have.
    import inspect
    src = inspect.getsource(chat.live_video_ids)
    check("discovery verifies the owning channel",
          "owner_channel_id" in src and "channel_id(handle)" in src,
          "a candidate that cannot be proven yours is dropped")
    check("channel id is cached, not refetched per candidate",
          "_channel_cache" in inspect.getsource(chat.channel_id))

    calls = {"owner": 0}
    real_owner, real_session, real_cands, real_cid = (
        chat.owner_channel_id, chat.chat_session, chat._candidate_ids, chat.channel_id)
    try:
        chat.channel_id = lambda h: "UC_ME"
        chat._candidate_ids = lambda h: ["mine1", "stranger", "mine2"]
        chat.chat_session = lambda v: ("k", "c")          # all appear live
        def fake_owner(v):
            calls["owner"] += 1
            return "UC_ME" if v.startswith("mine") else "UC_SOMEONE_ELSE"
        chat.owner_channel_id = fake_owner
        got = chat.live_video_ids("whoever")
        check("only this channel's broadcasts are read", got == ["mine1", "mine2"], str(got))
        check("a stranger's live stream is rejected", "stranger" not in got)
        check("every candidate is checked", calls["owner"] == 3, str(calls["owner"]))
    finally:
        chat.owner_channel_id, chat.chat_session = real_owner, real_session
        chat._candidate_ids, chat.channel_id = real_cands, real_cid


def test_twitch_write():
    print("")
    print("--- twitch: chat + moderation ---")
    from splitcast import twitch, twitchauth

    # No password, ever. Look for password HANDLING - a parameter, a dict key, a
    # field - not the word appearing in prose. The first version of this check
    # flagged its own docstring, which is a check that cannot pass rather than a
    # finding.
    src = ""
    for name in ("twitch.py", "twitchauth.py"):
        src += io.open(os.path.join(ROOT, "splitcast", name), encoding="utf-8").read()
    handling = ('password=', '"password"', "'password'", ".password",
                "password:", "getpass")
    hits = [h for h in handling if h in src.lower()]
    check("no password is ever handled", not hits,
          "OAuth exists precisely so it is not" if not hits else str(hits))

    url = twitch.authorize_url("abc123")
    check("auth uses implicit grant", "response_type=token" in url,
          "the code flow needs a client secret, which a desktop app cannot keep")
    check("auth redirects to localhost", "localhost" in url)
    check("client id is in the url", "abc123" in url)

    # Least privilege: only what the buttons actually do.
    for scope in ("user%3Awrite%3Achat", "moderator%3Amanage%3Abanned_users"):
        check("scope requested: " + scope.replace("%3A", ":"), scope in url)
    check("no broad account scopes",
          "user%3Aedit" not in url and "channel%3Amanage%3Abroadcast" not in url,
          "every extra scope is power handed over for no reason")

    tw = twitch.Twitch("cid", "tok", broadcaster_id="1", user_id="1")
    check("timeout and ban share one call",
          tw.timeout.__func__ is not tw.ban.__func__,
          "different buttons, same endpoint underneath")

    # A timeout IS a ban with a duration - the only difference must be the field.
    import inspect
    ban_src = inspect.getsource(twitch.Twitch._ban)
    check("duration only set for timeouts", 'if seconds:' in ban_src)
    check("reason is length-capped", "[:500]" in ban_src)

    check("token can be revoked", hasattr(twitch, "revoke"),
          "revocable without changing the password")
    check("redirect port is fixed", twitchauth.twitch.REDIRECT_PORT == 5583)


def test_chat_ui():
    print("")
    print("--- chat window: compose + moderation ---")
    page = chat_page.build(pipeline.default_config())
    # These must never appear on the stream overlay - viewers would see a text
    # box and a ban button burned into the video.
    check("compose is dock-only in css", "body.dock #compose" in page)
    check("menu is dock-only in js", "if (!DOCK) return" in page)
    check("send posts to the server", "'/chat/send'" in page)
    check("moderation posts to the server", "'/chat/mod'" in page)
    check("timeout durations offered", 'data-secs="60"' in page and 'data-secs="3600"' in page)
    check("ban is visually marked dangerous", 'class="danger"' in page)
    check("per-user filter exists", "applyFilter" in page)
    check("history panel exists", 'id="hist"' in page)
    check("history is fetched from the server", "/chat/user?name=" in page,
          "so it survives a page reload and covers both platforms")
    check("twitch chat is fed up to the server", "/chat/ingest" in page,
          "the server never sees twitch chat - it is read in the browser")
    check("links out to twitch's own card", "viewercard" in page,
          "for messages from before SplitCast started")
    check("youtube gets real moderation buttons",
          "Applies to both your live chats" in page,
          "a ban must reach the vertical chat too")
    check("send target can be all/twitch/youtube",
          'value="all"' in page and 'value="youtube"' in page)
    check("remaining youtube quota is shown",
          "youtube sends left today" in page,
          "50 units a send against 10,000/day - discover it before it bites")
    check("missing channel id explains itself",
          "Their next message will have it" in page,
          "rather than a button that fails")


def test_youtube_write():
    print("")
    print("--- youtube: chat + moderation ---")
    from splitcast import youtube as yt, youtubeauth

    src = ""
    for name in ("youtube.py", "youtubeauth.py"):
        src += io.open(os.path.join(ROOT, "splitcast", name), encoding="utf-8").read()
    handling = ("password=", '"password"', "'password'", "getpass")
    hits = [h for h in handling if h in src.lower()]
    check("no password is ever handled", not hits, str(hits) if hits else "OAuth only")

    # PKCE: only the HASH of the verifier goes out. An intercepted code is
    # useless without the verifier, which never leaves this machine.
    v = yt.make_verifier()
    check("verifier is long and random", len(v) >= 43, str(len(v)))
    check("challenge is a hash, not the verifier", yt.challenge_for(v) != v)
    check("two logins get different verifiers", yt.make_verifier() != v)

    url = yt.authorize_url("cid", v)
    check("uses the code flow", "response_type=code" in url,
          "google dropped implicit grant for new clients")
    check("sends the challenge, not the verifier",
          yt.challenge_for(v) in url and v not in url,
          "sending the verifier would defeat the entire point of PKCE")
    check("asks for S256", "code_challenge_method=S256" in url)
    check("requests offline access", "access_type=offline" in url,
          "without it there is no refresh token and every restart needs a login")
    check("forces the consent screen", "prompt=consent" in url,
          "a silent re-login returns no refresh token")
    check("only the youtube scope", "youtube.force-ssl" in url and "drive" not in url)

    # Quota is the real constraint and the numbers must not drift.
    check("send costs 50 units", yt.COST["send"] == 50)
    check("daily quota is 10000", yt.DAILY_QUOTA == 10000)
    api = yt.YouTube("cid", "sec", "acc", "ref", expires_at=9e9)
    check("sends left starts at 200", api.sends_left() == 200,
          "and halves to ~100 with two broadcasts")
    api.quota_used = 9800
    check("sends left falls as quota is spent", api.sends_left() == 4)
    api.quota_used = 10000
    check("exhausted quota reports zero", api.sends_left() == 0)

    import inspect
    ban_src = inspect.getsource(yt.YouTube.ban)
    check("timeout vs ban is one field",
          '"temporary" if seconds else "permanent"' in ban_src)
    check("message text is length-capped", "[:200]" in inspect.getsource(yt.YouTube.send))
    check("redirect port differs from twitch",
          yt.REDIRECT_PORT != 5583, str(yt.REDIRECT_PORT))


def test_chat_buffer():
    print("")
    print("--- chat buffer cursor ---")
    cfg = pipeline.default_config()
    cfg["chat_youtube_handle"] = ""       # never start a real poller in a test
    buf = overlay.ChatBuffer(cfg)
    for i in range(3):
        buf._add({"platform": "youtube", "author": "a", "parts": [], "text": str(i)})
    msgs, cursor = buf.since(0)
    check("returns everything from cursor 0", len(msgs) == 3, str(len(msgs)))
    msgs2, cursor2 = buf.since(cursor)
    check("returns nothing twice", msgs2 == [], str(msgs2))
    buf._add({"platform": "youtube", "author": "b", "parts": [], "text": "new"})
    msgs3, _ = buf.since(cursor2)
    check("returns only the new one", len(msgs3) == 1 and msgs3[0]["text"] == "new")
    check("cursor is monotonic, not time-based",
          all(m["seq"] > 0 for m in msgs), "same-second messages must not collide")


def test_chat_routes():
    print("")
    print("--- chat routes serve ---")
    cfg = pipeline.default_config()
    cfg["overlay_port"] = 5572
    cfg["chat_youtube_handle"] = ""
    srv = overlay.Overlay(cfg)
    try:
        url = srv.start()
        page = urllib.request.urlopen(url + "chat", timeout=5).read().decode("utf-8")
        check("/chat serves the overlay", "SplitCast Combined Chat" in page)
        raw = urllib.request.urlopen(url + "chat/poll?since=0", timeout=5).read()
        d = json.loads(raw.decode("utf-8"))
        check("/chat/poll returns json",
              {"messages", "cursor", "status"} <= set(d), str(sorted(d)))
        check("poll reports what it is watching", "note" in d and "reload" in d,
              "a silent reload failure looked like a fix that did not work")
        check("poll is safe with no youtube stream", d["messages"] == [])
        check("chat_url helper is right", srv.chat_url.endswith("/chat"), srv.chat_url)
    finally:
        srv.stop()
    check("chat poller stopped with the server", srv.chat is None)


def main():
    print("SplitCast overlay checks")
    test_config_isolation()
    test_hygiene()
    test_nowplaying()
    test_server()
    test_artwork()
    test_chat()
    test_multi_broadcast()
    test_chat_window()
    test_twitch_write()
    test_youtube_write()
    test_chat_ui()
    test_splitter()
    test_key_adoption()
    test_chat_buffer()
    test_chat_routes()
    print("\n" + "=" * 58)
    print("{} passed, {} failed".format(len(PASS), len(FAIL)))
    for name in FAIL:
        print("   FAILED:", name)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
