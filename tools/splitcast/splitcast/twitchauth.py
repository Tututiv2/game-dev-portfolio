"""One-shot local server that catches the Twitch OAuth token.

The implicit grant returns the token in the URL FRAGMENT (`...#access_token=`).
A fragment is never sent to the server as part of the request - that is the
point of it, and it is why this cannot be a plain redirect handler. So the page
served at the redirect reads `location.hash` in the browser and posts the token
back to this same server, which then shuts itself down.

Nothing is stored anywhere but the local config, and the token is scoped to
chat + moderation only. No password is involved at any stage.
"""

import json
import threading
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import twitch

LANDING = """<!doctype html>
<meta charset="utf-8">
<title>SplitCast - Twitch connected</title>
<style>
  body { background:#0e1015; color:#e7e9ee; font-family:"Segoe UI",system-ui,sans-serif;
         display:grid; place-items:center; height:100vh; margin:0; text-align:center; }
  .box { max-width:30rem; padding:2rem 2.5rem; background:#171a22;
         border:1px solid #2a2e3a; border-radius:14px; }
  h1 { font-size:1.4rem; margin:0 0 .6rem; }
  p  { color:#9aa2b4; line-height:1.5; margin:.4rem 0; }
  .ok { color:#3ddc84; font-weight:700; }
  .bad { color:#ff5c5c; font-weight:700; }
</style>
<div class="box">
  <h1 id="head">Connecting…</h1>
  <p id="msg">One moment.</p>
  <p style="margin-top:1rem">You can close this tab.</p>
</div>
<script>
// The token lives in the fragment, which the server never receives. Read it
// here and hand it back over a normal request.
const p = new URLSearchParams(location.hash.slice(1));
const token = p.get('access_token');
const err = p.get('error_description') || p.get('error');
const head = document.getElementById('head');
const msg = document.getElementById('msg');

if (token) {
  fetch('/auth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token: token })
  }).then(() => {
    head.textContent = 'Twitch connected';
    head.className = 'ok';
    msg.textContent = 'SplitCast can now send chat and moderate. Back to the app.';
  }).catch(() => {
    head.textContent = 'Could not reach SplitCast';
    head.className = 'bad';
    msg.textContent = 'Is it still open? Try Connect Twitch again.';
  });
} else {
  head.textContent = 'Not connected';
  head.className = 'bad';
  msg.textContent = err ? decodeURIComponent(err) : 'No token came back. Try again.';
}
</script>
"""


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *_a):
        pass

    def _send(self, body, ctype="text/html; charset=utf-8", code=200):
        raw = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            pass

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in ("/auth", "/", "/auth/"):
            self._send(LANDING)
            return
        self.send_error(404)

    def do_POST(self):
        if urllib.parse.urlparse(self.path).path != "/auth/token":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else "{}"
        try:
            token = (json.loads(raw) or {}).get("token")
        except ValueError:
            token = None
        self._send(json.dumps({"ok": bool(token)}), "application/json")
        if token:
            self.server.splitcast_token = token
            # Let the response finish before the server is torn down.
            threading.Thread(target=self.server.shutdown, daemon=True).start()


def connect(client_id, timeout=180, open_browser=True):
    """Run the login flow. Returns a Twitch client, or raises.

    Blocks until the browser hands a token back or `timeout` elapses, so the
    caller must run it off the UI thread.
    """
    if not client_id:
        raise twitch.TwitchError("no Twitch Client ID set")

    httpd = ThreadingHTTPServer(("127.0.0.1", twitch.REDIRECT_PORT), _Handler)
    httpd.splitcast_token = None
    httpd.timeout = 1
    thread = threading.Thread(target=httpd.serve_forever,
                              kwargs={"poll_interval": 0.3}, daemon=True)
    thread.start()

    url = twitch.authorize_url(client_id)
    if open_browser:
        webbrowser.open(url)

    waited = 0.0
    while httpd.splitcast_token is None and waited < timeout:
        thread.join(0.5)
        waited += 0.5
        if not thread.is_alive():
            break

    token = httpd.splitcast_token
    try:
        httpd.shutdown()
        httpd.server_close()
    except Exception:
        pass

    if not token:
        raise twitch.TwitchError(
            "no token came back - was the Authorize button clicked?")
    return twitch.Twitch.from_token(client_id, token)
