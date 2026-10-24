"""The now-playing overlay - a local web page you add to Streamlabs as a
Browser Source.

This is what the "extension" popular streamers use actually is, nine times out
of ten: a little web page pulled in as a browser source. Nothing is installed
into Twitch, nothing needs approval, and it renders inside your scene like any
other layer.

One consequence worth knowing up front: a browser source lives in the Streamlabs
scene, so it is burned into the SINGLE encode that both platforms receive. The
card will therefore show on YouTube too - which is why the tagline defaults to
"Music on Twitch!". On the platform with no music it reads as a pointer to the
one that has it. Putting the card on only one platform would mean re-encoding
video on that branch, which throws away the entire reason this design is cheap.
"""

import hashlib
import json
import urllib.parse
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import chat as chat_mod
from . import chat_page
from . import artwork
from . import nowplaying
from . import twitch as twitch_mod
from . import youtube as yt_mod

PAGE = r"""<!doctype html>
<meta charset="utf-8">
<title>SplitCast Now Playing</title>
<style>
  :root {
    --accent: __ACCENT__;
    --card: rgba(14, 15, 20, 0.82);
  }
  /* Everything below is in rem, and 1rem is 1/120th of the viewport width.
     That makes 1rem = 16px on a 1920-wide source and 32px on a 3840-wide one,
     so the SAME page fills a 4K browser source at native resolution instead of
     being rendered at 1080p and scaled up (which is what makes an overlay look
     soft on a 4K canvas). Set the browser source to your canvas size and the
     layout follows. */
  html { font-size: calc(100vw / 120); }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    width: 100%; height: 100%;
    background: transparent;
    overflow: hidden;
    font-family: "Segoe UI", Inter, system-ui, sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  #wrap {
    position: absolute;
    left: 2.125rem; bottom: 2.125rem;
    display: flex; flex-direction: column; gap: .5rem;
    align-items: flex-start;
    transform: translateX(-130%);
    opacity: 0;
    transition: transform .55s cubic-bezier(.22,1,.36,1), opacity .45s ease;
  }
  #wrap.show { transform: translateX(0); opacity: 1; }

  .card {
    display: flex; align-items: center; gap: .875rem;
    padding: .75rem 1.375rem .75rem .75rem;
    background: var(--card);
    border-radius: .875rem;
    border: .0625rem solid rgba(255,255,255,.08);
    border-left: .1875rem solid var(--accent);
    box-shadow: 0 .625rem 2.125rem rgba(0,0,0,.5);
    backdrop-filter: blur(.875rem);
    max-width: 32.5rem;
  }

  /* Spinning disc - stands in for album art, which the window title cannot give
     us. It only spins while something is playing, so it doubles as a state
     indicator you can read at a glance from across the room. */
  .disc {
    width: 2.875rem; height: 2.875rem; flex: 0 0 2.875rem;
    border-radius: 50%;
    background:
      radial-gradient(circle at 50% 50%, #0c0d11 0 15%, transparent 16%),
      repeating-radial-gradient(circle at 50% 50%,
        rgba(255,255,255,.07) 0 2px, transparent 2px 5px),
      conic-gradient(from 0deg, var(--accent), #2a2140 40%, var(--accent) 75%, #4b2fa8);
    box-shadow: inset 0 0 0 .0625rem rgba(255,255,255,.10);
    animation: spin 4.5s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }

  /* Real cover art when the lookup finds it; the spinning disc is the fallback,
     never both at once. */
  .art {
    width: 2.875rem; height: 2.875rem; flex: 0 0 2.875rem;
    border-radius: .5rem; object-fit: cover;
    box-shadow: 0 .125rem .625rem rgba(0,0,0,.55), inset 0 0 0 .0625rem rgba(255,255,255,.10);
    display: none;
  }
  .has-art .disc { display: none; }
  .has-art .art { display: block; }

  .meta { min-width: 0; display: flex; flex-direction: column; gap: .1875rem; }
  .row { display: flex; align-items: center; gap: .5625rem; }

  .eq { display: flex; align-items: flex-end; gap: .125rem; height: .8125rem; flex: 0 0 auto; }
  .eq i {
    display: block; width: .1875rem; border-radius: .0625rem;
    background: var(--accent);
    animation: bounce .9s ease-in-out infinite;
  }
  .eq i:nth-child(1) { height: 40%; animation-delay: -.1s; }
  .eq i:nth-child(2) { height: 90%; animation-delay: -.5s; }
  .eq i:nth-child(3) { height: 60%; animation-delay: -.8s; }
  .eq i:nth-child(4) { height: 100%; animation-delay: -.3s; }
  @keyframes bounce { 0%,100% { transform: scaleY(.35); } 50% { transform: scaleY(1); } }

  .title {
    font-size: 1.0625rem; font-weight: 650; color: #fff;
    letter-spacing: .00625rem; white-space: nowrap;
  }
  .artist {
    font-size: .8125rem; font-weight: 500; color: #a7adbd;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    max-width: 25rem;
  }

  /* Long titles scroll instead of being cut off. The animation is only attached
     when the text actually overflows - a permanently scrolling short title is
     the tell of a lazy overlay. */
  .clip { overflow: hidden; max-width: 25rem; }
  .clip.marquee .title {
    animation: slide 12s linear infinite;
    padding-right: 3rem;
  }
  @keyframes slide {
    0%, 12% { transform: translateX(0); }
    88%, 100% { transform: translateX(var(--shift, 0px)); }
  }

  .tag {
    margin-left: .375rem;
    font-size: .75rem; font-weight: 700; letter-spacing: .025rem;
    color: #fff;
    background: var(--accent);
    padding: .3125rem .8125rem; border-radius: 999px;
    box-shadow: 0 .375rem 1.125rem rgba(0,0,0,.4);
    text-transform: uppercase;
  }
</style>

<div id="wrap">
  <div class="card">
    <div class="disc" id="disc"></div>
    <img class="art" id="art" alt="">
    <div class="meta">
      <div class="row">
        <div class="eq"><i></i><i></i><i></i><i></i></div>
        <div class="clip" id="clip"><div class="title" id="title">-</div></div>
      </div>
      <div class="artist" id="artist">-</div>
    </div>
  </div>
  <div class="tag" id="tag">__TAGLINE__</div>
</div>

<script>
/* Layout scale. 1rem is 1/120th of the viewport width times this factor, so the
   overlay is a function of the canvas rather than a pixel size - and ?scale=2.5
   retunes it live in the preview without touching the config or restarting. */
(function () {
  const q = parseFloat(new URLSearchParams(location.search).get('scale'));
  const SCALE = (q > 0 && q < 10) ? q : __SCALE__;
  function fit() {
    document.documentElement.style.fontSize =
      (Math.max(window.innerWidth, 320) / 120 * SCALE) + 'px';
  }
  fit();
  addEventListener('resize', fit);
})();

/* The browser source caches this page, so an edit would otherwise need a manual
   "Refresh cache" in Streamlabs every time. /version is a hash of the page the
   server would serve RIGHT NOW - it only changes once SplitCast is actually
   running new code, so this reloads exactly when there is something new and
   never merely because a file on disk was touched. */
(function () {
  let mine = null;
  async function check() {
    try {
      const v = await (await fetch('/version', { cache: 'no-store' })).json();
      if (mine === null) mine = v.hash;
      else if (v.hash !== mine) location.reload();
    } catch (e) { /* server restarting - try again next tick */ }
    setTimeout(check, 5000);
  }
  check();
})();
const wrap = document.getElementById('wrap');
const elTitle = document.getElementById('title');
const elArtist = document.getElementById('artist');
const elClip = document.getElementById('clip');
const elTag = document.getElementById('tag');
const elArt = document.getElementById('art');
const card = document.querySelector('.card');

function setArt(url) {
  if (url) {
    // Only swap once the image has actually decoded, so a slow or dead CDN
    // shows the disc rather than an empty box.
    const probe = new Image();
    probe.onload = () => { elArt.src = url; card.classList.add('has-art'); };
    probe.onerror = () => card.classList.remove('has-art');
    probe.src = url;
  } else {
    card.classList.remove('has-art');
  }
}
let last = null;
// ?demo=1 shows a fake track. Without it the card is correctly invisible when
// nothing is playing, which is indistinguishable from a broken overlay.
const DEMO = new URLSearchParams(location.search).has('demo');

function render(track) {
  const key = track ? track.artist + ' :: ' + track.title : null;
  if (key === last) return;
  last = key;

  if (!track) { wrap.classList.remove('show'); return; }

  // Slide out, swap, slide back in - so a track change reads as a change
  // rather than as text quietly mutating in place.
  const wasShowing = wrap.classList.contains('show');
  const swap = () => {
    elTitle.textContent = track.title;
    elArtist.textContent = track.artist;
    setArt(track.art);
    elClip.classList.remove('marquee');
    elTitle.style.removeProperty('--shift');
    requestAnimationFrame(() => {
      const over = elTitle.scrollWidth - elClip.clientWidth;
      if (over > 8) {
        elTitle.style.setProperty('--shift', (-over - 48) + 'px'); // measured px, not rem
        elClip.classList.add('marquee');
      }
      wrap.classList.add('show');
    });
  };
  if (wasShowing) { wrap.classList.remove('show'); setTimeout(swap, 420); }
  else { swap(); }
}

async function tick() {
  try {
    const r = await fetch('/now', { cache: 'no-store' });
    const d = await r.json();
    if (d.tagline && d.tagline !== elTag.textContent) elTag.textContent = d.tagline;
    if (d.playing && d.art && last && !card.classList.contains('has-art')) {
      setArt(d.art);   // art arrived on a later poll than the track did
    }
    render(d.playing ? { artist: d.artist, title: d.title, art: d.art } : null);
  } catch (e) { /* server restarting - keep the last card up, do not flicker */ }
}
if (DEMO) {
  const fake = [
    { artist: 'krush', title: 'Stereo Love - Slowed To Perfection' },
    { artist: 'isq', title: 'pursuit - super slowed' },
    { artist: 'Daft Punk', title: 'Something About Us - Remastered 2021' }
  ];
  // Demo pulls real art through the real endpoint, so the preview shows what
  // the live card will actually look like.
  fetch('/now').catch(() => {});
  let i = 0;
  render(fake[i++]);
  setInterval(() => { render(fake[i % fake.length]); i++; }, 5000);
} else {
  tick();
  setInterval(tick, 1200);
}
</script>
"""


class _Handler(BaseHTTPRequestHandler):
    server_version = "SplitCast"

    def log_message(self, *_args):
        pass  # a browser source polls once a second; the console is not a log

    def _send(self, body, ctype):
        raw = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        # An overlay that serves a cached page is an overlay showing the wrong
        # song. Never cache anything here.
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            pass

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8", "replace"))
        except ValueError:
            return {}

    def do_POST(self):
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        overlay = getattr(self.server, "splitcast_overlay", None)
        if path == "/chat/ingest":
            buf = getattr(self.server, "splitcast_chat", None)
            body = self._read_json()
            result = buf.ingest(body) if buf else {"ok": False, "error": "no buffer"}
            self._send(json.dumps(result), "application/json; charset=utf-8")
            return
        if path not in ("/chat/send", "/chat/mod") or overlay is None:
            self.send_error(404)
            return
        body = self._read_json()
        try:
            if path == "/chat/send":
                result = overlay.send_chat(body.get("text", ""),
                                           body.get("platform", "twitch"))
            else:
                result = overlay.moderate(body.get("platform", "twitch"),
                                          body.get("action", ""),
                                          body.get("user", ""),
                                          body.get("seconds"),
                                          body.get("channel_id"))
        except Exception as exc:            # noqa: BLE001 - surface, never crash
            result = {"ok": False, "error": "{}: {}".format(type(exc).__name__, exc)}
        self._send(json.dumps(result), "application/json; charset=utf-8")

    def do_GET(self):
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        cfg = self.server.splitcast_cfg

        if path == "/now":
            track = nowplaying.current(cfg.get("overlay_process", "Spotify.exe"))
            art = (artwork.lookup_async(track["artist"], track["title"])
                   if track else None)
            payload = {
                "playing": bool(track),
                "artist": track["artist"] if track else "",
                "title": track["title"] if track else "",
                "art": art or "",
                "tagline": cfg.get("overlay_tagline", "Music on Twitch!"),
            }
            self._send(json.dumps(payload), "application/json; charset=utf-8")
            return

        if path == "/version":
            blob = (PAGE + chat_page.PAGE).encode("utf-8")
            self._send(json.dumps({"hash": hashlib.sha1(blob).hexdigest()[:12]}),
                       "application/json; charset=utf-8")
            return

        if path == "/chat/user":
            buf = getattr(self.server, "splitcast_chat", None)
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1]
                                      if "?" in self.path else "")
            name = (q.get("name") or [""])[0]
            msgs = buf.history(name) if buf else []
            self._send(json.dumps({"user": name, "messages": msgs,
                                   "count": len(msgs)}),
                       "application/json; charset=utf-8")
            return

        if path == "/chat":
            self._send(chat_page.build(cfg), "text/html; charset=utf-8")
            return

        if path == "/chat/poll":
            buf = getattr(self.server, "splitcast_chat", None)
            try:
                since = int((self.path.split("since=", 1) + ["0"])[1].split("&")[0])
            except (ValueError, IndexError):
                since = 0
            if buf is None:
                self._send(json.dumps({"messages": [], "cursor": 0,
                                       "status": "not started"}),
                           "application/json; charset=utf-8")
                return
            msgs, cursor = buf.since(since)
            self._send(json.dumps({"messages": msgs, "cursor": cursor,
                                   "status": buf.status(),
                                   "note": buf.note(),
                                   "reload": getattr(self.server, "splitcast_reload", "")}),
                       "application/json; charset=utf-8")
            return

        if path == "/":
            page = (PAGE
                    .replace("__TAGLINE__", _escape(cfg.get("overlay_tagline",
                                                            "Music on Twitch!")))
                    .replace("__ACCENT__", cfg.get("overlay_accent", "#9146FF"))
                    .replace("__SCALE__", str(float(cfg.get("overlay_scale", 1.7)))))
            self._send(page, "text/html; charset=utf-8")
            return

        self.send_error(404)


def _escape(text):
    return (str(text).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


HISTORY_PER_USER = 400   # messages kept per person
HISTORY_USERS = 500      # people remembered before evicting the stalest


class ChatBuffer:
    """Holds YouTube messages between polls.

    A monotonic cursor rather than timestamps: the page asks for everything
    after N and gets it exactly once. Timestamps would double-post or drop
    messages that share a second, which on a busy chat is most of them.
    """

    def __init__(self, cfg):
        self._lock = threading.Lock()
        self._msgs = []
        self._next = 1
        self._log = []
        self._history = {}
        self._ingested = set()
        self._yt = chat_mod.YouTubeChat(cfg.get("chat_youtube_handle", ""),
                                        on_message=self._add,
                                        on_discover=self._discovered)
        self._started = False

    def _discovered(self, ids):
        self._note = "watching {} broadcast(s): {}".format(len(ids), ", ".join(ids) or "none")

    def _add(self, msg):
        with self._lock:
            msg = dict(msg, seq=self._next)
            self._next += 1
            self._msgs.append(msg)
            if len(self._msgs) > 300:
                del self._msgs[:-150]
            self._remember(msg)

    def _remember(self, msg):
        """Per-user history, kept far longer than the live feed.

        The feed is a rolling window - it has to be, or the overlay grows without
        bound. But "show me everything this person said" is a different question
        and cannot be answered from a window, so history is stored separately and
        capped per user rather than globally. One spammer therefore cannot evict
        everybody else's history.
        """
        who = (msg.get("author") or "").lower().lstrip("@")
        if not who:
            return
        bucket = self._history.setdefault(who, [])
        bucket.append(msg)
        if len(bucket) > HISTORY_PER_USER:
            del bucket[:-HISTORY_PER_USER]
        if len(self._history) > HISTORY_USERS:
            # Evict whoever spoke longest ago, not arbitrarily.
            oldest = min(self._history,
                         key=lambda k: self._history[k][-1].get("ts", 0))
            self._history.pop(oldest, None)

    def ingest(self, msg):
        """A message the SERVER never sees - Twitch chat is read in the browser.

        Without this, history would silently be YouTube-only, which is worse
        than no history: it looks complete.
        """
        if not (msg.get("author") and msg.get("platform")):
            return {"ok": False, "error": "author and platform required"}
        with self._lock:
            mid = msg.get("id") or "{}|{}|{}".format(
                msg.get("platform"), msg.get("author"), msg.get("text", ""))
            if mid in self._ingested:
                return {"ok": True, "duplicate": True}
            self._ingested.add(mid)
            if len(self._ingested) > 8000:
                self._ingested = set(list(self._ingested)[-4000:])
            self._remember({
                "platform": msg.get("platform"),
                "author": msg.get("author"),
                "text": msg.get("text", ""),
                "parts": msg.get("parts") or [{"t": "text", "v": msg.get("text", "")}],
                "ts": float(msg.get("ts") or time.time()),
            })
        return {"ok": True}

    def history(self, author, limit=300):
        who = (author or "").lower().lstrip("@")
        with self._lock:
            return list(self._history.get(who, []))[-limit:]

    def start(self):
        if not self._started:
            self._started = True
            self._yt.start()

    def note(self):
        return getattr(self, "_note", "")

    def stop(self):
        self._yt.stop()

    def status(self):
        return self._yt.status

    def since(self, cursor):
        with self._lock:
            out = [m for m in self._msgs if m["seq"] > cursor]
            return out, (out[-1]["seq"] if out else max(cursor, self._next - 1))


class Overlay:
    """Runs the page on a background thread. Starting and stopping it never
    touches the relay - the overlay is decoration and must not be able to take
    the stream down with it."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.httpd = None
        self.thread = None
        self.chat = None
        self._tw = None
        self._yt_api = None

    # -- twitch write actions ---------------------------------------------
    def _twitch(self):
        """A Twitch client, or None if not connected. Built lazily so a missing
        or expired token costs a message rather than the overlay starting."""
        if getattr(self, "_tw", None):
            return self._tw
        cid = self.cfg.get("twitch_client_id", "")
        tok = self.cfg.get("twitch_token", "")
        if not cid or not tok:
            return None
        try:
            self._tw = twitch_mod.Twitch.from_token(cid, tok)
        except Exception:
            self._tw = None
        return self._tw

    def _youtube(self):
        """A YouTube client, or None. Refreshed tokens are persisted so a
        restart does not mean another login."""
        if getattr(self, "_yt_api", None):
            return self._yt_api
        cid = self.cfg.get("youtube_client_id", "")
        ref = self.cfg.get("youtube_refresh", "")
        if not cid or not ref:
            return None

        def remember(tok):
            self.cfg["youtube_access"] = tok.get("access", "")
            self.cfg["youtube_expires_at"] = tok.get("expires_at", 0)
            try:
                from . import config as _config
                _config.save(self.cfg)
            except Exception:
                pass

        self._yt_api = yt_mod.YouTube(
            cid, self.cfg.get("youtube_client_secret", ""),
            self.cfg.get("youtube_access", ""), ref,
            self.cfg.get("youtube_expires_at", 0), on_token=remember)
        return self._yt_api

    def _live_videos(self):
        buf = getattr(self, "chat", None)
        yt = getattr(buf, "_yt", None) if buf else None
        return list(yt.video_ids) if yt else []

    def send_chat(self, text, platform="twitch"):
        text = (text or "").strip()
        if not text:
            return {"ok": False, "error": "empty message"}
        sent, errors = [], []

        if platform in ("twitch", "all"):
            tw = self._twitch()
            if not tw:
                errors.append("twitch not connected")
            else:
                try:
                    tw.send(text)
                    sent.append("twitch")
                except Exception as exc:        # noqa: BLE001
                    errors.append("twitch: " + str(exc)[:120])

        if platform in ("youtube", "all"):
            yt = self._youtube()
            vids = self._live_videos()
            if not yt:
                errors.append("youtube not connected")
            elif not vids:
                errors.append("youtube: no live broadcast")
            else:
                # BOTH broadcasts. Horizontal and vertical have separate chats,
                # so sending to one leaves half the audience unanswered.
                for vid in vids:
                    try:
                        yt.send(vid, text)
                        sent.append("youtube:" + vid)
                    except Exception as exc:    # noqa: BLE001
                        errors.append("youtube {}: {}".format(vid, str(exc)[:100]))

        out = {"ok": bool(sent), "sent": sent}
        if errors:
            out["error"] = "; ".join(errors)
        api = self._youtube()
        if api:
            out["youtube_sends_left"] = api.sends_left()
        return out

    def moderate(self, platform, action, user, seconds=None, channel_id=None):
        if platform == "youtube":
            return self._moderate_youtube(action, user, seconds, channel_id)
        tw = self._twitch()
        if not tw:
            return {"ok": False, "error": "twitch not connected"}
        user = (user or "").lstrip("@")
        secs = int(seconds or self.cfg.get("default_timeout_seconds", 600))
        try:
            if action == "timeout":
                tw.timeout(user, secs)
            elif action == "ban":
                tw.ban(user)
            elif action == "unban":
                tw.unban(user)
            else:
                return {"ok": False, "error": "unknown action: " + str(action)}
        except Exception as exc:            # noqa: BLE001
            return {"ok": False, "error": str(exc)[:200]}
        return {"ok": True, "action": action, "user": user, "seconds": secs}

    def _moderate_youtube(self, action, user, seconds, channel_id):
        yt = self._youtube()
        if not yt:
            return {"ok": False, "error": "youtube not connected"}
        if not channel_id:
            # A display name is not a handle on YouTube and is not unique, so
            # there is nothing safe to fall back to.
            return {"ok": False,
                    "error": "no channel id for that user - moderation needs it"}
        vids = self._live_videos()
        if not vids:
            return {"ok": False, "error": "no live broadcast"}
        secs = int(seconds or self.cfg.get("default_timeout_seconds", 600))
        done, errors = [], []
        for vid in vids:
            try:
                if action == "timeout":
                    yt.ban(vid, channel_id, secs)
                elif action == "ban":
                    yt.ban(vid, channel_id, None)
                else:
                    return {"ok": False,
                            "error": "youtube supports timeout and ban only"}
                done.append(vid)
            except Exception as exc:            # noqa: BLE001
                errors.append(str(exc)[:120])
        out = {"ok": bool(done), "action": action, "user": user,
               "seconds": secs, "applied": done,
               "youtube_sends_left": yt.sends_left()}
        if errors:
            out["error"] = "; ".join(errors)
        return out

    @property
    def chat_url(self):
        return self.url + "chat"

    @property
    def url(self):
        return "http://127.0.0.1:{}/".format(self.cfg.get("overlay_port", 5570))

    def start(self):
        if self.httpd:
            return self.url
        # Re-read the chat settings from disk. These were previously frozen at
        # SplitCast launch, so a wrong YouTube handle could only be corrected by
        # restarting the whole app - which kills the relay, i.e. the stream. The
        # overlay must never be able to hold the stream hostage, so Stop/Start
        # Overlay now picks up config changes on its own.
        # Reload the chat modules from disk. Python caches imports, so a fix to
        # chat.py would otherwise need SplitCast restarted - which kills the
        # relay, i.e. the live stream, to correct an overlay. The overlay must
        # never be able to cost you a stream, so Stop/Start Overlay picks up new
        # code as well as new config.
        self._reload_note = ""
        try:
            import importlib
            importlib.reload(chat_mod)
            importlib.reload(chat_page)
            self._reload_note = "chat code reloaded from disk"
        except Exception as exc:
            # Never silent. A failed reload means Stop/Start Overlay quietly does
            # nothing, and the symptom is a fix that "did not work" with no clue
            # why - which is exactly what happened.
            self._reload_note = "!! chat code reload FAILED: {}: {}".format(
                type(exc).__name__, exc)

        try:
            from . import config as _config
            fresh = _config.load()
            for key in ("chat_youtube_handle", "chat_twitch_channel",
                        "chat_max_messages", "chat_font_size", "chat_message_ttl",
                        "overlay_tagline", "overlay_accent", "overlay_scale",
                        "chat_window_scale"):
                if key in fresh:
                    self.cfg[key] = fresh[key]
        except Exception:
            pass    # a stale config is still better than no overlay
        port = int(self.cfg.get("overlay_port", 5570))
        self.httpd = ThreadingHTTPServer(("127.0.0.1", port), _Handler)
        self.httpd.splitcast_cfg = self.cfg
        self.chat = ChatBuffer(self.cfg)
        self.chat.start()
        self.httpd.splitcast_chat = self.chat
        self.httpd.splitcast_reload = self._reload_note
        self.httpd.splitcast_overlay = self
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever,
                                       kwargs={"poll_interval": 0.4}, daemon=True)
        self.thread.start()
        return self.url

    def stop(self):
        if getattr(self, "chat", None):
            self.chat.stop()
            self.chat = None
        if not self.httpd:
            return
        try:
            self.httpd.shutdown()
            self.httpd.server_close()
        except Exception:
            pass
        self.httpd = None
        self.thread = None
