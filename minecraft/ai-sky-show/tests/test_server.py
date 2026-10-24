"""server.py: SSE broadcast + sticky replay, disconnect robustness, dials/control API, audio Range.
Runs a real ThreadingHTTPServer on an ephemeral port (127.0.0.1:0) - light, no other process."""
import http.client
import json
import socket
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "orchestrator"))

from show import dials as D  # noqa: E402
from show import server as S  # noqa: E402
from show.events import EventBus  # noqa: E402


class SSEReader:
    """Minimal SSE client over a raw socket so a test can also slam it shut."""

    def __init__(self, port, path="/stream"):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.sock.sendall(f"GET {path} HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n".encode())
        self.buf = b""

    def next(self, timeout=3.0):
        end = time.time() + timeout
        while time.time() < end:
            while b"\n\n" in self.buf:
                block, self.buf = self.buf.split(b"\n\n", 1)
                for line in block.decode().splitlines():
                    if line.startswith("data: "):
                        return json.loads(line[6:])
            self.sock.settimeout(max(0.05, end - time.time()))
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            self.buf += chunk
        return None

    def close(self):
        self.sock.close()


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="aisky_srv_"))
        cls.old_dials_path = D.PATH
        D.PATH = cls.tmp / "dials.json"              # never touch config/dials.json from a test
        cls.hub = S.Hub()
        cls.srv = S.ShowServer(port=0, hub=cls.hub, state=S.ShowState(), bus=EventBus()).start()
        cls.srv.set_audio_dir(cls.tmp / "audio")
        cls.port = cls.srv.port

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()
        D.PATH = cls.old_dials_path

    def req(self, method, path, body=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        data = json.dumps(body).encode() if body is not None else None
        c.request(method, path, body=data, headers={"Content-Type": "application/json", **(headers or {})})
        r = c.getresponse()
        raw = r.read()
        c.close()
        return r, raw

    # -- hub ----------------------------------------------------------------------------------
    def test_hub_sticky_merges_status_and_replays_status_first(self):
        h = S.Hub()
        h.push({"kind": "scene", "mode": "cinematic"})
        h.push({"kind": "status", "char": "Kaito", "purse": 10, "goal": "fight"})
        h.push({"kind": "status", "char": "Kaito", "purse": 25})
        h.push({"kind": "caption", "char": "Kaito", "text": "not sticky"})
        h.push({"kind": "clock", "elapsed_s": 5, "total_s": 1800})
        rep = h.replay()
        self.assertEqual([m["kind"] for m in rep], ["status", "scene", "clock"])
        self.assertEqual((rep[0]["purse"], rep[0]["goal"]), (25, "fight"))
        self.assertTrue(all(m["replay"] for m in rep))
        with self.assertRaises(ValueError):
            h.push({"no": "kind"})

    # -- SSE ----------------------------------------------------------------------------------
    def test_sse_replay_then_live_broadcast_to_two_clients(self):
        self.hub.push({"kind": "clock", "elapsed_s": 42, "total_s": 1800, "running": True})
        a, b = SSEReader(self.port), SSEReader(self.port)
        try:
            ra, rb = a.next(), b.next()
            self.assertEqual(ra["kind"], "clock")
            self.assertEqual(ra["elapsed_s"], 42)
            self.assertTrue(ra["replay"])
            self.assertEqual(rb["kind"], "clock")
            time.sleep(0.2)
            self.hub.push({"kind": "chat", "char": "Mochi", "text": "buy my stick"})
            for r in (a, b):
                m = r.next()
                while m and m.get("replay"):
                    m = r.next()
                self.assertEqual((m["kind"], m["text"]), ("chat", "buy my stick"))
        finally:
            a.close()
            b.close()

    def test_client_disconnect_is_dropped_and_server_survives(self):
        before = self.hub.clients()
        r = SSEReader(self.port)
        r.next()                       # ensure subscribed
        time.sleep(0.2)
        self.assertEqual(self.hub.clients(), before + 1)
        r.sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, b"\x01\x00\x00\x00\x00\x00\x00\x00")
        r.close()
        for i in range(30):            # writes to the dead socket fail -> unsubscribed
            self.hub.push({"kind": "ticker", "text": f"t{i}", "drama": 3})
            time.sleep(0.03)
            if self.hub.clients() == before:
                break
        self.assertEqual(self.hub.clients(), before)
        resp, raw = self.req("GET", "/api/health")
        self.assertEqual(resp.status, 200)

    # -- pages / static / audio ----------------------------------------------------------------
    def test_pages_served(self):
        for path, needle in (("/", b"Control Room"), ("/overlay", b"/static/overlay.js"),
                             ("/static/overlay.css", b"--display"), ("/static/overlay.js", b"EventSource")):
            resp, raw = self.req("GET", path)
            self.assertEqual(resp.status, 200, path)
            self.assertIn(needle, raw, path)
            self.assertEqual(resp.getheader("Cache-Control"), "no-store")

    def test_static_traversal_refused(self):
        for p in ("/static/../config/show.json", "/static/..%2Fconfig%2Fshow.json", "/audio/..%2Fx.wav",
                  "/audio/a/b.wav"):
            resp, _ = self.req("GET", p)
            self.assertEqual(resp.status, 404, p)

    def test_audio_full_and_range(self):
        f = self.srv.audio_dir / "clip_1.wav"
        f.write_bytes(bytes(range(256)) * 4)
        resp, raw = self.req("GET", "/audio/clip_1.wav")
        self.assertEqual((resp.status, len(raw), resp.getheader("Content-Type")), (200, 1024, "audio/wav"))
        self.assertEqual(resp.getheader("Accept-Ranges"), "bytes")
        resp, raw = self.req("GET", "/audio/clip_1.wav", headers={"Range": "bytes=10-19"})
        self.assertEqual(resp.status, 206)
        self.assertEqual(raw, bytes(range(10, 20)))
        self.assertEqual(resp.getheader("Content-Range"), "bytes 10-19/1024")
        resp, raw = self.req("GET", "/audio/clip_1.wav", headers={"Range": "bytes=1000-"})
        self.assertEqual((resp.status, len(raw)), (206, 24))
        resp, raw = self.req("GET", "/audio/clip_1.wav", headers={"Range": "bytes=-4"})
        self.assertEqual(raw, bytes(range(252, 256)))
        resp, _ = self.req("GET", "/audio/clip_1.wav", headers={"Range": "bytes=5000-"})
        self.assertEqual(resp.status, 416)
        resp, _ = self.req("GET", "/audio/missing.wav")
        self.assertEqual(resp.status, 404)

    # -- API ------------------------------------------------------------------------------------
    def test_dials_get_and_post(self):
        resp, raw = self.req("GET", "/api/dials")
        d = json.loads(raw)
        self.assertIn("voice_global_gap_s", d)
        old = d["voice_global_gap_s"]
        try:
            resp, raw = self.req("POST", "/api/dials", {"key": "voice_global_gap_s", "value": "2.5"})
            j = json.loads(raw)
            self.assertTrue(j["ok"])
            self.assertEqual(j["dials"]["voice_global_gap_s"], 2.5)
            self.assertTrue(D.PATH.is_file(), "persisted (to the test's temp path)")
            resp, raw = self.req("POST", "/api/dials", {"nope": 1, "voice_stale_s": "abc"})
            self.assertEqual(resp.status, 400)
            errs = json.loads(raw)["errors"]
            self.assertIn("nope", errs)
            self.assertIn("voice_stale_s", errs)
        finally:
            D.DIALS.set("voice_global_gap_s", old)

    def test_control_handlers(self):
        seen = []

        def h(action, body):
            if action == "restart_agent":
                seen.append(body.get("char"))
                return {"ok": True}
            return None
        S.on_control(h)
        try:
            resp, raw = self.req("POST", "/api/control", {"action": "restart_agent", "char": "Ren"})
            self.assertEqual(json.loads(raw)["ok"], True)
            self.assertEqual(seen, ["Ren"])
            resp, raw = self.req("POST", "/api/control", {"action": "no_such_thing"})
            self.assertFalse(json.loads(raw)["ok"])
            resp, raw = self.req("POST", "/api/control", {})
            self.assertEqual(resp.status, 400)
        finally:
            S._CONTROL.remove(h)

    def test_state_snapshot(self):
        resp, raw = self.req("GET", "/api/state")
        s = json.loads(raw)
        from show.config import SHOW
        self.assertEqual([c["name"] for c in s["chars"]], [c.name for c in SHOW.characters])
        for k in ("clock", "scene", "cost", "dials", "director", "overlay_clients"):
            self.assertIn(k, s)
        self.assertIn("max_turns_per_hour", s["cost"])

    def test_push_endpoint(self):
        resp, raw = self.req("POST", "/api/push", {"kind": "ticker", "text": "hello", "drama": 3})
        self.assertTrue(json.loads(raw)["ok"])
        self.assertEqual(self.hub.recent(1)[0]["text"], "hello")

    def test_turns_per_hour(self):
        st = S.ShowState()
        now = time.time()
        for i in range(30):
            st.note_turn("Kaito", now - 300 + i * 10)
        self.assertAlmostEqual(st.turns_per_hour(now), 360.0, delta=1)


class SourceHygiene(unittest.TestCase):
    """The portfolio has been bitten 4x by control bytes (\\b, NUL) written into source via a shell.
    They compile, look fine in an editor and never show in a diff - so scan for them."""

    FILES = ["orchestrator/show/server.py", "orchestrator/show/overlay_bridge.py", "orchestrator/show/tts.py",
             "overlay/overlay.html", "overlay/overlay.js", "overlay/overlay.css", "overlay/dashboard.html"]

    def test_no_control_characters(self):
        for rel in self.FILES:
            text = (ROOT / rel).read_text(encoding="utf-8")
            bad = [(i, hex(ord(ch))) for i, ch in enumerate(text) if ord(ch) < 32 and ch not in "\n\r\t"]
            self.assertEqual(bad, [], rel)

    def test_no_external_scripts(self):
        for rel in ("overlay/overlay.html", "overlay/dashboard.html"):
            html = (ROOT / rel).read_text(encoding="utf-8")
            self.assertNotIn('src="http', html, rel)
            self.assertNotIn("cdn", html.lower(), rel)


if __name__ == "__main__":
    unittest.main()
