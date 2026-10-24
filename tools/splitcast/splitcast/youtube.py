"""YouTube write access: send chat messages, time out, ban.

NO PASSWORD. Google's own consent screen, a scoped token, revocable at
myaccount.google.com/permissions.

Why this is heavier than Twitch
-------------------------------
Google dropped the implicit grant for new clients, so this uses the
authorization-code flow with PKCE. PKCE exists precisely for apps that cannot
keep a secret: a random verifier is generated per login, only its SHA-256 hash
goes out in the authorize URL, and the verifier itself is presented when
redeeming the code. An attacker who intercepts the code cannot use it without
the verifier, which never left this machine.

Installed-app clients are still issued a "client secret" that is not actually
secret - Google documents this - so it is stored, used, and given no more
reverence than the client id.

QUOTA IS THE REAL CONSTRAINT
----------------------------
Every call costs units against 10,000/day:

    liveChatMessages.insert   50     sending one message
    liveChatBans.insert       50     ban or timeout
    videos.list                1     finding a broadcast's chat id

Which means roughly 200 sends a day - and with Dual Output there are TWO live
chats, so a message to "YouTube" is 100 units and the practical ceiling is about
100 messages. Twitch has no equivalent limit. The remaining quota is tracked and
surfaced rather than discovered when a send silently starts failing.
"""

import base64
import hashlib
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

AUTH_BASE = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
REVOKE_URL = "https://oauth2.googleapis.com/revoke"
API = "https://www.googleapis.com/youtube/v3"

SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl"

REDIRECT_PORT = 5584
REDIRECT_URI = "http://localhost:{}/auth".format(REDIRECT_PORT)

DAILY_QUOTA = 10000
COST = {"send": 50, "ban": 50, "lookup": 1}


class YouTubeError(Exception):
    pass


# --------------------------------------------------------------------- PKCE

def make_verifier():
    return base64.urlsafe_b64encode(os.urandom(48)).decode().rstrip("=")


def challenge_for(verifier):
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).decode().rstrip("=")


def authorize_url(client_id, verifier):
    return AUTH_BASE + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": SCOPE,
        "code_challenge": challenge_for(verifier),
        "code_challenge_method": "S256",
        "access_type": "offline",     # we need a refresh token
        "prompt": "consent",          # or a re-login returns no refresh token
    })


# --------------------------------------------------------------------- HTTP

def _form(url, fields, timeout=15):
    data = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8", "replace")[:300]
        except Exception:
            pass
        raise YouTubeError("{} {} - {}".format(exc.code, exc.reason, detail))
    except urllib.error.URLError as exc:
        raise YouTubeError(str(exc))


def exchange_code(client_id, client_secret, code, verifier):
    fields = {
        "client_id": client_id,
        "code": code,
        "code_verifier": verifier,
        "grant_type": "authorization_code",
        "redirect_uri": REDIRECT_URI,
    }
    if client_secret:
        fields["client_secret"] = client_secret
    return _form(TOKEN_URL, fields)


def refresh_token(client_id, client_secret, refresh):
    fields = {
        "client_id": client_id,
        "refresh_token": refresh,
        "grant_type": "refresh_token",
    }
    if client_secret:
        fields["client_secret"] = client_secret
    return _form(TOKEN_URL, fields)


def revoke(token):
    try:
        _form(REVOKE_URL, {"token": token})
        return True
    except Exception:
        return False


class YouTube:
    """Authenticated actions. Refreshes its own access token."""

    def __init__(self, client_id, client_secret, access, refresh, expires_at=0,
                 on_token=None):
        self.client_id = client_id
        self.client_secret = client_secret
        self.access = access
        self.refresh = refresh
        self.expires_at = expires_at or 0
        self.on_token = on_token or (lambda d: None)
        self.quota_used = 0
        self._chat_ids = {}

    # -- token ------------------------------------------------------------
    def _token(self):
        # 60 s of slack: a token that expires mid-request is a failed send.
        if self.access and time.time() < self.expires_at - 60:
            return self.access
        if not self.refresh:
            raise YouTubeError("not signed in (no refresh token)")
        data = refresh_token(self.client_id, self.client_secret, self.refresh)
        self.access = data.get("access_token")
        self.expires_at = time.time() + int(data.get("expires_in", 3600))
        self.on_token({"access": self.access, "expires_at": self.expires_at})
        return self.access

    def _call(self, path, params=None, body=None, method="GET", cost=1):
        url = API + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self._token())
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                self.quota_used += cost
                raw = r.read().decode("utf-8", "replace")
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as exc:
            detail = ""
            try:
                detail = exc.read().decode("utf-8", "replace")[:400]
            except Exception:
                pass
            # Quota exhaustion is the single most likely failure here, and
            # "403 Forbidden" would send someone hunting for a permissions bug.
            if exc.code == 403 and "quota" in detail.lower():
                raise YouTubeError(
                    "YouTube daily quota exhausted (10,000 units). Sending costs "
                    "50 per chat. It resets at midnight Pacific.")
            raise YouTubeError("{} {} - {}".format(exc.code, exc.reason, detail))
        except urllib.error.URLError as exc:
            raise YouTubeError(str(exc))

    # -- discovery --------------------------------------------------------
    def live_chat_id(self, video_id):
        """The chat id for a broadcast. Cached - it never changes for a video."""
        if video_id in self._chat_ids:
            return self._chat_ids[video_id]
        data = self._call("/videos", {"part": "liveStreamingDetails",
                                      "id": video_id}, cost=COST["lookup"])
        items = data.get("items") or []
        cid = None
        if items:
            cid = (items[0].get("liveStreamingDetails") or {}).get("activeLiveChatId")
        self._chat_ids[video_id] = cid
        return cid

    # -- actions ----------------------------------------------------------
    def send(self, video_id, text):
        chat_id = self.live_chat_id(video_id)
        if not chat_id:
            raise YouTubeError("that broadcast has no active chat")
        return self._call("/liveChat/messages", {"part": "snippet"}, body={
            "snippet": {
                "liveChatId": chat_id,
                "type": "textMessageEvent",
                "textMessageDetails": {"messageText": text[:200]},
            }
        }, method="POST", cost=COST["send"])

    def ban(self, video_id, channel_id, seconds=None):
        """Timeout when seconds is given, permanent ban otherwise - the same
        endpoint, one field apart, exactly like Twitch."""
        chat_id = self.live_chat_id(video_id)
        if not chat_id:
            raise YouTubeError("that broadcast has no active chat")
        snippet = {
            "liveChatId": chat_id,
            "type": "temporary" if seconds else "permanent",
            "bannedUserDetails": {"channelId": channel_id},
        }
        if seconds:
            snippet["banDurationSeconds"] = int(seconds)
        return self._call("/liveChat/bans", {"part": "snippet"},
                          body={"snippet": snippet}, method="POST",
                          cost=COST["ban"])

    def quota_left(self):
        return max(0, DAILY_QUOTA - self.quota_used)

    def sends_left(self):
        return self.quota_left() // COST["send"]
