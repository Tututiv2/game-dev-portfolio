"""YouTube live chat reader - every live broadcast on the channel, merged.

Twitch is NOT here on purpose. Twitch chat is IRC over WebSocket and a browser
can open it directly, anonymously, with no token - so the overlay page does that
itself and this module never touches it. YouTube is the opposite: its chat
endpoint refuses cross-origin requests, so the fetch has to happen server-side.
That asymmetry is the whole reason this file exists.

Why MULTIPLE broadcasts
-----------------------
Streamlabs' Dual Output makes TWO YouTube broadcasts - horizontal and vertical -
and each has its OWN chat. Reading only the one that `/live` resolves to means
everybody watching the vertical is invisible, which is worse than having no
YouTube chat at all: it looks like it works.

The vertical is not discoverable by badge-scraping - it does not carry a LIVE
badge on the streams tab and the primary's watch page does not name it. So
discovery is empirical: collect recent video ids from the channel and ask each
one whether it has a working live-chat continuation. That is slow-ish but it is
the only answer that cannot be wrong, and it runs once a minute, not per poll.

No API key and no OAuth. The Data API would need a key AND burns quota fast
(liveChatMessages.list costs 5 units against 10,000/day, so polling every 5 s
exhausts a day in about three hours). This uses the same innertube endpoint the
live-chat page itself calls, which has no quota.

Every failure path returns "no messages" rather than raising, because a broken
YouTube reader must never take down the Twitch half of a live overlay.
"""

import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

CLIENT_VERSION = "2.20240101.00.00"
POLL_SECONDS = 4.0
DISCOVER_SECONDS = 60.0     # re-scan for new/ended broadcasts this often
MAX_CANDIDATES = 48         # video ids to consider across the channel pages
MAX_LIVE = 4                # stop once this many live broadcasts are found
PROBE_WORKERS = 8           # candidates probed in parallel


def _get(url, timeout=15):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept-Language": "en-US,en;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def _post(url, payload, timeout=15):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={
        "User-Agent": UA,
        "Content-Type": "application/json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def live_video_id(handle):
    """The PRIMARY live video id, or None. Kept for callers that want one."""
    ids = live_video_ids(handle, limit=1)
    return ids[0] if ids else None


def _candidate_ids(handle):
    """Recent video ids from the channel, primary first, in page order.

    FOUR pages, not two. The vertical broadcast of a Dual Output stream does not
    appear near the top of /live or /streams - a real one was sitting past the
    14th candidate and was simply never tested, so the overlay showed only the
    horizontal chat and looked like it was working.
    """
    handle = (handle or "").strip().lstrip("@")
    if not handle:
        return []
    out = []
    for path in ("/live", "/streams", "/videos", ""):
        try:
            html = _get("https://www.youtube.com/@{}{}".format(handle, path))
        except (urllib.error.URLError, OSError):
            continue
        m = re.search(
            r'<link rel="canonical" href="https://www\.youtube\.com/watch\?v=([\w-]{11})"',
            html)
        if m and m.group(1) not in out:
            out.append(m.group(1))
        for mm in re.finditer(r'"videoId":"([\w-]{11})"', html):
            vid = mm.group(1)
            if vid not in out:
                out.append(vid)
        if len(out) >= MAX_CANDIDATES:
            break
    return out[:MAX_CANDIDATES]


def _renderers_from_actions(actions):
    """Pull liveChatTextMessageRenderer out of a list of chat actions."""
    out = []
    for action in actions or []:
        item = (action.get("addChatItemAction", {}) or {}).get("item", {}) or {}
        r = item.get("liveChatTextMessageRenderer")
        if r:
            out.append(r)
    return out


def chat_backlog(html, limit=6):
    """Messages already in the live-chat page when we attach.

    The continuation only returns what arrives AFTER it is issued, which is
    correct for an overlay but means it sits empty until somebody types - and
    that is indistinguishable from being broken. Seeding a few recent messages
    makes 'connected' visible immediately.
    """
    m = (re.search(r'window\["ytInitialData"\]\s*=\s*(\{.*?\});\s*</script>', html, re.S)
         or re.search(r'ytInitialData\s*=\s*(\{.*?\});', html, re.S))
    if not m:
        return []
    try:
        data = json.loads(m.group(1))
    except ValueError:
        return []
    lcr = (data.get("contents") or {}).get("liveChatRenderer") or {}
    return _renderers_from_actions(lcr.get("actions"))[-limit:]


def chat_session(video_id, want_backlog=False):
    """(api_key, continuation[, backlog]) for a video's live chat, or None.

    This is the definitive liveness test. Badges lie - the vertical broadcast of
    a Dual Output stream carries no LIVE badge anywhere - but a working
    continuation token cannot be faked.
    """
    try:
        html = _get("https://www.youtube.com/live_chat?is_popout=1&v=" + video_id)
    except (urllib.error.URLError, OSError):
        return None
    key = re.search(r'"INNERTUBE_API_KEY"\s*:\s*"([^"]+)"', html)
    cont = re.search(r'"continuation"\s*:\s*"([^"]+)"', html)
    if not key or not cont:
        return None
    if want_backlog:
        return key.group(1), cont.group(1), chat_backlog(html)
    return key.group(1), cont.group(1)


_channel_cache = {}


def channel_id(handle):
    """The UC... id for a handle, cached. None if it cannot be resolved."""
    handle = (handle or "").strip().lstrip("@")
    if not handle:
        return None
    if handle in _channel_cache:
        return _channel_cache[handle]
    cid = None
    for path in ("/live", "/about", ""):
        try:
            html = _get("https://www.youtube.com/@{}{}".format(handle, path))
        except (urllib.error.URLError, OSError):
            continue
        m = (re.search(r'"channelId":"(UC[\w-]{22})"', html)
             or re.search(r'"externalId":"(UC[\w-]{22})"', html))
        if m:
            cid = m.group(1)
            break
    _channel_cache[handle] = cid
    return cid


def owner_channel_id(video_id):
    """Which channel published a video, or None."""
    try:
        html = _get("https://www.youtube.com/watch?v=" + video_id)
    except (urllib.error.URLError, OSError):
        return None
    m = re.search(r'"channelId":"(UC[\w-]{22})"', html)
    return m.group(1) if m else None


def live_video_ids(handle, limit=MAX_LIVE):
    """Every live broadcast that belongs to THIS channel.

    Probed in parallel, because the candidate list is now wide enough that
    sequential HTTP would take a minute - and this runs on a timer while a
    stream is live.

    Ownership is verified, not assumed. A channel page carries recommended
    videos from other channels, and some of those are live - so scraping ids off
    the page and testing them for a chat happily attached to strangers'
    broadcasts and put a lofi rain stream's viewers on the overlay. Seeing
    someone else's chat on your own stream is the worst failure this can have,
    so a candidate that cannot be PROVEN yours is dropped.
    """
    mine = channel_id(handle)
    cands = _candidate_ids(handle)
    if not cands:
        return []

    results = {}

    def probe(idx_vid):
        idx, vid = idx_vid
        try:
            if not chat_session(vid):
                return
            if mine and owner_channel_id(vid) != mine:
                return
            results[idx] = vid
        except Exception:               # noqa: BLE001 - one bad probe is not fatal
            return

    try:
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=PROBE_WORKERS) as pool:
            list(pool.map(probe, list(enumerate(cands))))
    except Exception:                   # noqa: BLE001 - fall back to sequential
        for pair in enumerate(cands):
            probe(pair)

    # Page order is meaningful: the primary comes first.
    return [results[i] for i in sorted(results)][:limit]


def _parts_of(renderer):
    """A message as an ordered list of parts, not a flat string.

    YouTube sends custom/member emoji as image runs carrying thumbnail URLs.
    Flattening them to their `:shortcut:` text throws away the picture the
    viewer actually typed.
    """
    parts = []
    for run in (renderer.get("message", {}) or {}).get("runs", []) or []:
        if "text" in run:
            if run["text"]:
                parts.append({"t": "text", "v": run["text"]})
        elif "emoji" in run:
            emoji = run["emoji"] or {}
            thumbs = ((emoji.get("image", {}) or {}).get("thumbnails") or [])
            shortcuts = emoji.get("shortcuts") or []
            alt = shortcuts[0] if shortcuts else emoji.get("emojiId", "")
            if thumbs:
                parts.append({"t": "emote", "u": thumbs[-1].get("url", ""), "a": alt})
            elif alt:
                parts.append({"t": "text", "v": alt})
    return parts


def _plain(parts):
    return "".join(p["v"] if p["t"] == "text" else p.get("a", "")
                   for p in parts).strip()


class _Broadcast:
    """One live chat's continuation loop."""

    def __init__(self, video_id, on_message, on_dead, seen):
        self.video_id = video_id
        self.on_message = on_message
        self.on_dead = on_dead
        self.seen = seen                # shared across broadcasts
        self.key = None
        self.continuation = None
        self.alive = True
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _fetch(self):
        url = ("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key="
               + self.key)
        data = _post(url, {
            "context": {"client": {"clientName": "WEB",
                                   "clientVersion": CLIENT_VERSION}},
            "continuation": self.continuation,
        })
        cont = ((data.get("continuationContents", {}) or {})
                .get("liveChatContinuation", {}) or {})
        for c in cont.get("continuations") or []:
            for kind in ("invalidationContinuationData", "timedContinuationData",
                         "reloadContinuationData"):
                if kind in c:
                    self.continuation = c[kind].get("continuation", self.continuation)
                    break

        out = []
        for action in cont.get("actions", []) or []:
            item = (action.get("addChatItemAction", {}) or {}).get("item", {}) or {}
            r = item.get("liveChatTextMessageRenderer")
            if not r:
                continue
            mid = r.get("id")
            if not mid or mid in self.seen:
                continue
            self.seen.add(mid)
            parts = _parts_of(r)
            if not parts:
                continue
            out.append({
                "platform": "youtube",
                "id": mid,
                "video": self.video_id,
                "channel_id": r.get("authorExternalChannelId", ""),
                "author": (r.get("authorName", {}) or {}).get("simpleText", "?"),
                "parts": parts,
                "text": _plain(parts),
                "ts": time.time(),
            })
        if len(self.seen) > 6000:
            keep = list(self.seen)[-3000:]
            self.seen.clear()
            self.seen.update(keep)
        return out

    def _emit(self, renderer):
        mid = renderer.get("id")
        if not mid or mid in self.seen:
            return
        self.seen.add(mid)
        parts = _parts_of(renderer)
        if not parts:
            return
        self.on_message({
            "platform": "youtube",
            "id": mid,
            "video": self.video_id,
            # Banning on YouTube takes a CHANNEL id, not a display name - and a
            # display name is not even unique. Captured here or moderation is
            # impossible later.
            "channel_id": renderer.get("authorExternalChannelId", ""),
            "author": (renderer.get("authorName", {}) or {}).get("simpleText", "?"),
            "parts": parts,
            "text": _plain(parts),
            "ts": time.time(),
        })

    def _loop(self):
        session = chat_session(self.video_id, want_backlog=True)
        if not session:
            self.alive = False
            self.on_dead(self.video_id)
            return
        self.key, self.continuation, backlog = session
        for r in backlog:
            self._emit(r)
        backoff = 4.0
        while not self._stop.is_set():
            try:
                for msg in self._fetch():
                    self.on_message(msg)
                backoff = 4.0
            except Exception:               # noqa: BLE001 - never die
                # A dead continuation usually means the broadcast ended.
                session = chat_session(self.video_id)
                if not session:
                    self.alive = False
                    self.on_dead(self.video_id)
                    return
                self.key, self.continuation = session[0], session[1]
                self._stop.wait(min(backoff, 30))
                backoff = min(backoff * 2, 30)
                continue
            self._stop.wait(POLL_SECONDS)
        self.alive = False


class YouTubeChat:
    """Watches every live broadcast on a channel and merges their chats."""

    def __init__(self, handle, on_message=None, on_discover=None):
        self.handle = handle
        self.on_message = on_message or (lambda m: None)
        self.on_discover = on_discover or (lambda ids: None)
        self.status = "idle"
        self.video_ids = []
        self._readers = {}
        self._seen = set()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None

    @property
    def video_id(self):
        return self.video_ids[0] if self.video_ids else None

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._discover_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        with self._lock:
            for r in self._readers.values():
                r.stop()
            self._readers.clear()

    def _dead(self, video_id):
        with self._lock:
            self._readers.pop(video_id, None)
            self.video_ids = [v for v in self.video_ids if v != video_id]
            self._paint()

    def _paint(self):
        n = len(self._readers)
        if n == 0:
            self.status = "offline"
        elif n == 1:
            self.status = "live"
        else:
            self.status = "live x{}".format(n)

    def _discover_loop(self):
        while not self._stop.is_set():
            try:
                ids = live_video_ids(self.handle)
            except Exception:               # noqa: BLE001
                ids = []
            with self._lock:
                for vid in ids:
                    if vid not in self._readers:
                        r = _Broadcast(vid, self.on_message, self._dead, self._seen)
                        self._readers[vid] = r
                        r.start()
                self.video_ids = list(self._readers)
                self._paint()
            try:
                self.on_discover(list(self.video_ids))
            except Exception:
                pass
            # Offline is expected and cheap to re-check; do not hammer.
            self._stop.wait(DISCOVER_SECONDS if self._readers else 20.0)
