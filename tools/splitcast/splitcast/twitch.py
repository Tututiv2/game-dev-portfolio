"""Twitch write access: send chat, time out, ban, delete messages.

NO PASSWORD. Ever. This uses OAuth, which is the whole point of OAuth: the owner
clicks Authorize on Twitch's own page, and what comes back is a token scoped to
exactly the four things below, revocable at twitch.tv/settings/connections
without touching the account password. A password would grant everything
forever; a token grants these and can be pulled at any moment.

Why an API and not chat commands
--------------------------------
Typing `/ban someone` in chat used to work over IRC. Twitch REMOVED chat
commands from IRC in 2023 - they are Helix (Twitch's REST API) calls now. So the
buttons do exactly what typing the command used to do, over HTTP instead. The
user-visible behaviour is identical; only the transport changed.

Implicit grant is used rather than the authorization-code flow because the code
flow needs a CLIENT SECRET, and a secret embedded in a desktop app that anybody
can open in a text editor is not a secret. Implicit needs no secret: the token
arrives in the URL fragment, which never leaves the browser as part of a
request, so a tiny local page reads it and hands it back.
"""

import json
import time
import urllib.error
import urllib.parse
import urllib.request

AUTH_BASE = "https://id.twitch.tv/oauth2/authorize"
VALIDATE = "https://id.twitch.tv/oauth2/validate"
REVOKE = "https://id.twitch.tv/oauth2/revoke"
HELIX = "https://api.twitch.tv/helix"

# Exactly what the buttons need and nothing more. Every extra scope is power
# handed over for no reason.
SCOPES = [
    "user:write:chat",                  # send messages
    "user:bot",                         # required alongside user:write:chat
    "channel:bot",                      # send into your own channel
    "moderator:manage:banned_users",    # timeout / ban / unban
    "moderator:manage:chat_messages",   # delete a single message
]

REDIRECT_PORT = 5583
REDIRECT_URI = "http://localhost:{}/auth".format(REDIRECT_PORT)


class TwitchError(Exception):
    pass


def authorize_url(client_id):
    return AUTH_BASE + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": REDIRECT_URI,
        "response_type": "token",
        "scope": " ".join(SCOPES),
        "force_verify": "true",
    })


def _request(url, token, client_id, method="GET", body=None, timeout=10):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Client-Id", client_id)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8", "replace")[:300]
        except Exception:
            pass
        raise TwitchError("{} {} - {}".format(exc.code, exc.reason, detail))
    except urllib.error.URLError as exc:
        raise TwitchError(str(exc))


def validate(token):
    """Who does this token belong to, and is it still good?

    Twitch requires tokens be validated hourly; doing it on demand also turns a
    silently-expired token into a clear message instead of a failed button.
    """
    req = urllib.request.Request(VALIDATE)
    req.add_header("Authorization", "OAuth " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError:
        return None
    except urllib.error.URLError as exc:
        raise TwitchError(str(exc))


def revoke(token, client_id):
    body = urllib.parse.urlencode({"client_id": client_id, "token": token}).encode()
    req = urllib.request.Request(REVOKE, data=body, method="POST")
    try:
        urllib.request.urlopen(req, timeout=10)
        return True
    except Exception:
        return False


class Twitch:
    """Authenticated actions against one channel."""

    def __init__(self, client_id, token, broadcaster_id=None, user_id=None,
                 login=None):
        self.client_id = client_id
        self.token = token
        self.broadcaster_id = broadcaster_id
        self.user_id = user_id
        self.login = login
        self._user_cache = {}

    # -- identity ---------------------------------------------------------
    @classmethod
    def from_token(cls, client_id, token):
        info = validate(token)
        if not info:
            raise TwitchError("token is not valid (expired or revoked)")
        me = cls(client_id, token, user_id=info.get("user_id"),
                 login=info.get("login"))
        me.broadcaster_id = me.user_id      # streaming to your own channel
        return me

    def scopes(self):
        info = validate(self.token)
        return (info or {}).get("scopes", [])

    def user_id_for(self, login):
        """Resolve a display name to a numeric id. Cached - a chat overlay
        clicks the same handful of names over and over."""
        key = (login or "").lower().lstrip("@")
        if not key:
            return None
        if key in self._user_cache:
            return self._user_cache[key]
        data = _request(HELIX + "/users?login=" + urllib.parse.quote(key),
                        self.token, self.client_id)
        items = data.get("data") or []
        uid = items[0]["id"] if items else None
        self._user_cache[key] = uid
        return uid

    # -- actions ----------------------------------------------------------
    def send(self, message):
        return _request(HELIX + "/chat/messages", self.token, self.client_id,
                        method="POST", body={
                            "broadcaster_id": self.broadcaster_id,
                            "sender_id": self.user_id,
                            "message": message,
                        })

    def timeout(self, login, seconds=600, reason=""):
        """A timeout IS a ban with a duration - same endpoint, one extra field.
        Keeping them separate in the UI while sharing this call means the two
        cannot drift apart."""
        return self._ban(login, seconds, reason)

    def ban(self, login, reason=""):
        return self._ban(login, None, reason)

    def _ban(self, login, seconds, reason):
        uid = self.user_id_for(login)
        if not uid:
            raise TwitchError("no such twitch user: " + str(login))
        data = {"user_id": uid}
        if seconds:
            data["duration"] = int(seconds)
        if reason:
            data["reason"] = reason[:500]
        url = "{}/moderation/bans?broadcaster_id={}&moderator_id={}".format(
            HELIX, self.broadcaster_id, self.user_id)
        return _request(url, self.token, self.client_id, method="POST",
                        body={"data": data})

    def unban(self, login):
        uid = self.user_id_for(login)
        if not uid:
            raise TwitchError("no such twitch user: " + str(login))
        url = "{}/moderation/bans?broadcaster_id={}&moderator_id={}&user_id={}".format(
            HELIX, self.broadcaster_id, self.user_id, uid)
        return _request(url, self.token, self.client_id, method="DELETE")

    def delete_message(self, message_id):
        url = "{}/moderation/chat?broadcaster_id={}&moderator_id={}&message_id={}".format(
            HELIX, self.broadcaster_id, self.user_id, message_id)
        return _request(url, self.token, self.client_id, method="DELETE")
