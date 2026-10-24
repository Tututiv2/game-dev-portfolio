"""One-shot local server that catches Google's OAuth authorization code.

Simpler than the Twitch flow: the code arrives in the QUERY STRING, which the
server does receive, so no page-side JavaScript is needed to recover it. The
code is then redeemed for tokens using the PKCE verifier generated at the start
of this login - the verifier never leaves this machine, so an intercepted code
is useless on its own.
"""

import threading
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import youtube

PAGE = """<!doctype html>
<meta charset="utf-8">
<title>SplitCast - YouTube {state}</title>
<style>
  body {{ background:#0e1015; color:#e7e9ee; font-family:"Segoe UI",system-ui,sans-serif;
         display:grid; place-items:center; height:100vh; margin:0; text-align:center; }}
  .box {{ max-width:32rem; padding:2rem 2.5rem; background:#171a22;
         border:1px solid #2a2e3a; border-radius:14px; }}
  h1 {{ font-size:1.4rem; margin:0 0 .6rem; color:{colour}; }}
  p  {{ color:#9aa2b4; line-height:1.5; }}
</style>
<div class="box">
  <h1>{heading}</h1>
  <p>{body}</p>
  <p style="margin-top:1rem">You can close this tab.</p>
</div>
"""


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *_a):
        pass

    def _page(self, state, colour, heading, body):
        raw = PAGE.format(state=state, colour=colour, heading=heading,
                          body=body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            pass

    def do_GET(self):
        parts = urllib.parse.urlparse(self.path)
        if parts.path not in ("/auth", "/", "/auth/"):
            self.send_error(404)
            return
        q = urllib.parse.parse_qs(parts.query)
        code = (q.get("code") or [None])[0]
        err = (q.get("error") or [None])[0]

        if code:
            self.server.splitcast_code = code
            self._page("connected", "#3ddc84", "YouTube connected",
                       "SplitCast can now send chat and moderate on YouTube.")
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return

        # A FAILED attempt must NOT close the door. The default browser is often
        # signed into the wrong account, so the first try errors and the user
        # then pastes the link into the right browser - which only works if we
        # are still listening. Record it and keep waiting.
        self.server.splitcast_error = err or "no code returned"
        self._page("not connected", "#ff5c5c", "Not connected",
                   "Google said: {}. Still listening - open the link in the "
                   "browser signed into your YouTube account and try again."
                   .format(err or "no authorization code"))


def connect(client_id, client_secret, timeout=180, open_browser=True,
            verifier=None):
    """Run the login. Returns dict with access/refresh/expires_at, or raises.

    `verifier` is accepted so the caller can build the authorize URL itself -
    the default browser is often not the one signed into the right account, so
    the URL has to be shown to the user, and the URL and the redemption must use
    the SAME verifier or the exchange fails with an opaque error.
    """
    if not client_id:
        raise youtube.YouTubeError("no Google Client ID set")

    verifier = verifier or youtube.make_verifier()
    httpd = ThreadingHTTPServer(("127.0.0.1", youtube.REDIRECT_PORT), _Handler)
    httpd.splitcast_code = None
    httpd.splitcast_error = None
    thread = threading.Thread(target=httpd.serve_forever,
                              kwargs={"poll_interval": 0.3}, daemon=True)
    thread.start()

    if open_browser:
        webbrowser.open(youtube.authorize_url(client_id, verifier))

    # Wait for a CODE, not merely for something to happen. Errors are recorded
    # but must not end the wait, or a retry in another browser has nowhere to go.
    waited = 0.0
    while httpd.splitcast_code is None and waited < timeout:
        thread.join(0.5)
        waited += 0.5
        if not thread.is_alive():
            break

    code, err = httpd.splitcast_code, httpd.splitcast_error
    try:
        httpd.shutdown()
        httpd.server_close()
    except Exception:
        pass

    if not code:
        if err:
            raise youtube.YouTubeError("google refused: " + str(err))
        raise youtube.YouTubeError(
            "no authorization code came back - was Allow clicked?")

    data = youtube.exchange_code(client_id, client_secret, code, verifier)
    if not data.get("refresh_token"):
        # Without it every restart would need a fresh login. prompt=consent is
        # meant to prevent this; if it still happens, say so plainly.
        raise youtube.YouTubeError(
            "google returned no refresh token - remove SplitCast at "
            "myaccount.google.com/permissions and connect again")
    import time as _t
    return {
        "access": data.get("access_token"),
        "refresh": data.get("refresh_token"),
        "expires_at": _t.time() + int(data.get("expires_in", 3600)),
    }
