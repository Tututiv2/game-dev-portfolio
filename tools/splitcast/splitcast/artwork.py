"""Album art for the now-playing card.

The window title gives an artist and a track and nothing else, so the art has to
be looked up. This uses the **iTunes Search API**: public, no key, no OAuth, no
quota worth worrying about. Spotify's own API would need a developer app plus a
client secret for something the owner would have to register and keep - a lot of
ceremony for one image.

The load-bearing part is not the request, it is `clean_query()`. This library is
mostly slowed/remixed edits ("- Super Slowed", "- Slowed To Perfection",
"[130]"), and none of those strings exist in any catalogue. Searched raw they
return nothing and the card silently falls back to the placeholder disc, which
looks exactly like a broken lookup. Stripped, the underlying song is found.

Every failure returns None. Art is decoration; it must never break the overlay.
"""

import io
import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request

ENDPOINT = "https://itunes.apple.com/search"
TIMEOUT = 6

# Decorations that are part of a re-upload's name, never part of the release.
# Ordered longest-first so "slowed to perfection" wins over "slowed".
NOISE = [
    "slowed to perfection", "super slowed", "slowed + reverb", "slowed and reverb",
    "slowed & reverb", "sped up", "speed up", "nightcore", "slowed", "reverb",
    "bass boosted", "remastered", "radio edit", "extended mix", "official audio",
    "official video", "lyrics", "audio", "hq", "hd",
]

_cache = {}
_lock = threading.Lock()
NEG_TTL = 900.0     # do not re-ask for something that was not found for 15 min
POS_TTL = 86400.0


def clean_query(artist, title):
    """Reduce a re-upload's title to something a catalogue can match."""
    text = (title or "").lower()
    # Bracketed chunks are almost always tempo/version tags: [130], (Remix).
    text = re.sub(r"\[[^\]]*\]", " ", text)
    text = re.sub(r"\([^)]*\)", " ", text)
    # Trailing " - <decoration>" segments.
    for _ in range(3):
        m = re.match(r"^(.*?)\s+-\s+([^-]*)$", text)
        if not m:
            break
        tail = m.group(2).strip()
        if any(n in tail for n in NOISE):
            text = m.group(1)
        else:
            break
    for n in NOISE:
        text = text.replace(n, " ")
    text = re.sub(r"[^\w\s']", " ", text)
    text = re.sub(r"\s+", " ", text).strip()

    # Whitespace is REQUIRED on both sides. Without the leading \s+ this
    # matched the "ft" inside "Daft Punk" and returned "da" - and it would
    # equally eat Taylor Swift, Shift K3Y, Drift. A separator is a word,
    # not a substring.
    who = re.sub(r"\s+(feat\.?|ft\.?|x|vs\.?|&|with)\s+.*$", "",
                 (artist or "").lower()).strip()
    who = who.split(",")[0].strip()
    who = re.sub(r"[^\w\s']", " ", who)
    who = re.sub(r"\s+", " ", who).strip()
    return who, text


def _json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "SplitCast/1.0"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def _deezer(term):
    """Deezer: public, no key. Measured better than iTunes on both ends of this
    library - it carries the actual slowed re-uploads ("Paizo - SOCA ATENCAO -
    Super Slowed" is in its catalogue verbatim) AND it returns the real artist
    for mainstream tracks where iTunes hands back tribute covers."""
    url = "https://api.deezer.com/search?" + urllib.parse.urlencode(
        {"q": term, "limit": 5})
    for item in (_json(url).get("data") or []):
        album = item.get("album") or {}
        art = album.get("cover_xl") or album.get("cover_big") or album.get("cover_medium")
        if art:
            yield {"artist": (item.get("artist") or {}).get("name", ""),
                   "title": item.get("title", ""), "art": art}


def _itunes(term):
    url = ENDPOINT + "?" + urllib.parse.urlencode(
        {"term": term, "entity": "song", "limit": 5, "media": "music"})
    for item in (_json(url).get("results") or []):
        raw = item.get("artworkUrl100") or item.get("artworkUrl60") or ""
        if raw:
            yield {"artist": item.get("artistName", ""),
                   "title": item.get("trackName", ""),
                   "art": raw.replace("100x100bb", "600x600bb")
                             .replace("60x60bb", "600x600bb")}


def _words(text):
    return set(re.findall(r"[\w']+", (text or "").lower()))


def _accept(cand, artist, title):
    """A result is only used if it plausibly IS the track.

    Title-only searching was tried and rejected: it always returns something,
    confidently, and for "soca atencao" it returned an unrelated Xuxa song. A
    WRONG cover is worse than the fallback disc, because nothing on screen says
    it is wrong. So either the artist matches, or most of the title words do.
    """
    a_want, t_want = _words(artist), _words(title)
    a_got, t_got = _words(cand["artist"]), _words(cand["title"])
    if a_want and (a_want & a_got):
        return True
    if t_want and len(t_want & t_got) >= max(1, int(len(t_want) * 0.6)):
        return True
    return False


def lookup(artist, title):
    """Artwork URL for a track, or None. Cached; never raises."""
    if not title:
        return None
    key = ((artist or "") + "|" + title).lower()
    now = time.time()
    with _lock:
        hit = _cache.get(key)
        if hit and now - hit[1] < (POS_TTL if hit[0] else NEG_TTL):
            return hit[0]

    who, what = clean_query(artist, title)
    raw_term = ((artist or "") + " " + title).strip()
    clean_term = (who + " " + what).strip()

    # Raw first: a re-upload's own name is often in Deezer verbatim, and that is
    # the most accurate possible match. Cleaned second, for when it is not.
    # Title-only is LAST and is only safe because _accept() validates the result.
    # On its own it always returns something confident and often wrong; behind
    # the validator it rescues the common case where the "artist" in the window
    # title is a re-upload channel rather than the act that made the song.
    attempts = [(_deezer, raw_term), (_deezer, clean_term),
                (_itunes, clean_term), (_deezer, what), (_itunes, what)]
    art = None
    for provider, term in attempts:
        if not term or art:
            continue
        try:
            for cand in provider(term):
                if _accept(cand, artist, title) or _accept(cand, who, what):
                    art = cand["art"]
                    break
        except Exception:  # noqa: BLE001 - art is decoration, never fatal
            continue

    if not art:
        _log_miss(artist, title, who, what)

    with _lock:
        _cache[key] = (art, now)
        if len(_cache) > 400:
            for k in list(_cache)[:200]:
                _cache.pop(k, None)
    return art


_inflight = set()


def lookup_async(artist, title):
    """Cached art if we have it, else None and a background fetch is started.

    The overlay polls once a second; a synchronous lookup would stall that poll
    for up to the full timeout on every new track, so the first poll after a
    song change returns no art and the next one has it. A visibly late image
    beats a visibly frozen overlay.
    """
    if not title:
        return None
    key = ((artist or "") + "|" + title).lower()
    with _lock:
        hit = _cache.get(key)
        if hit and time.time() - hit[1] < (POS_TTL if hit[0] else NEG_TTL):
            return hit[0]
        if key in _inflight:
            return None
        _inflight.add(key)

    def work():
        try:
            lookup(artist, title)
        finally:
            with _lock:
                _inflight.discard(key)

    threading.Thread(target=work, daemon=True).start()
    return None


MISS_LOG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "art_misses.txt")


def _log_miss(artist, title, who, what):
    """Record tracks no provider could match.

    Coverage is the one thing here that cannot be reasoned about - it depends
    entirely on what this particular library contains. A list of real misses is
    worth more than any amount of guessing at which cleaning rule to add next.
    """
    try:
        with io.open(MISS_LOG, "a", encoding="utf-8") as fh:
            # Built from chr() rather than escapes: this project has now had a
            # NUL, two mangled regexes and this line all arrive from escapes
            # that were rewritten in transit. chr(9)/chr(10) cannot be.
            row = chr(9).join([artist or "", title or "", who or "", what or ""])
            fh.write(row + chr(10))
    except OSError:
        pass
