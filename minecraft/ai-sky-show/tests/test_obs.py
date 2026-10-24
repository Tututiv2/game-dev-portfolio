"""Builder C: the stdlib obs-websocket v5 client against a fake OBS server thread.

Covers RFC 6455 framing (7/16/64-bit lengths, masking, fragmentation, ping), the v5 Hello ->
Identify auth challenge, request/response, auth failure (close 4009), and the show rig's scene
build: idempotent, 2x2 grid read off the canvas, record directory restored afterwards.
"""
from __future__ import annotations

import base64
import hashlib
import json
import socket
import struct
import sys
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace

ORCH = Path(__file__).resolve().parents[1] / "orchestrator"
if str(ORCH) not in sys.path:
    sys.path.insert(0, str(ORCH))

from show import obs as O  # noqa: E402


class FakeOBS(threading.Thread):
    """A tiny in-memory OBS: scenes, inputs, scene items, recording, mutes."""

    def __init__(self, password: str = "", canvas=(1920, 1080), big_hello: bool = False):
        super().__init__(daemon=True)
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(4)
        self.port = self.srv.getsockname()[1]
        self.password = password
        self.salt, self.challenge = "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=", "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
        self.canvas = canvas
        self.big_hello = big_hello
        self.scenes: list[str] = ["Scene"]
        self.inputs: dict[str, dict] = {"Desktop Audio": {"kind": "wasapi_output_capture", "settings": {}},
                                        "Mic/Aux": {"kind": "wasapi_input_capture", "settings": {}}}
        self.items: dict[str, list[dict]] = {"Scene": []}
        self.mutes = {"Desktop Audio": False, "Mic/Aux": True}
        self.rec_dir = "D:/owner/videos"
        self.recording = False
        self.program = "Scene"
        self.log: list[tuple[str, dict]] = []
        self.identified_auth = None
        self._next_item = 1

    # -- framing (server side: unmasked frames out, masked frames in)
    def send(self, conn, obj, fragment=False):
        data = json.dumps(obj).encode()
        if fragment and len(data) > 10:
            a, b = data[:10], data[10:]
            conn.sendall(O.encode_frame(O.OP_TEXT, a, mask=False, fin=False))
            conn.sendall(O.encode_frame(O.OP_PING, b"hi", mask=False))      # control frame mid-message
            conn.sendall(O.encode_frame(O.OP_CONT, b, mask=False, fin=True))
        else:
            conn.sendall(O.encode_frame(O.OP_TEXT, data, mask=False))

    def recv(self, conn):
        while True:
            fin, op, payload = O.read_frame(conn)
            if op == O.OP_PONG:
                continue
            if op == O.OP_CLOSE:
                return None
            return json.loads(payload)

    def run(self):
        while True:
            try:
                conn, _ = self.srv.accept()
            except OSError:
                return
            threading.Thread(target=self.session, args=(conn,), daemon=True).start()

    def session(self, conn):
        try:
            req = b""
            while b"\r\n\r\n" not in req:
                req += conn.recv(1024)
            hdr = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip()
                   for l in req.decode().split("\r\n")[1:] if ":" in l}
            assert hdr.get("sec-websocket-protocol") == "obsws.json"
            conn.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Accept: {O.ws_accept(hdr['sec-websocket-key'])}\r\n"
                          "Sec-WebSocket-Protocol: obsws.json\r\n\r\n").encode())
            hello = {"obsWebSocketVersion": "5.5.0", "rpcVersion": 1}
            if self.password:
                hello["authentication"] = {"challenge": self.challenge, "salt": self.salt}
            if self.big_hello:
                hello["padding"] = "x" * 70000            # forces the 64-bit length path
            self.send(conn, {"op": 0, "d": hello})
            ident = self.recv(conn)
            if ident is None:                         # client gave up (e.g. no password to offer)
                return
            assert ident["op"] == 1 and ident["d"]["rpcVersion"] == 1
            if self.password:
                self.identified_auth = ident["d"].get("authentication")
                secret = base64.b64encode(hashlib.sha256((self.password + self.salt).encode()).digest()).decode()
                want = base64.b64encode(hashlib.sha256((secret + self.challenge).encode()).digest()).decode()
                if self.identified_auth != want:
                    conn.sendall(O.encode_frame(O.OP_CLOSE, struct.pack("!H", 4009) + b"Authentication failed.", mask=False))
                    return
            self.send(conn, {"op": 2, "d": {"negotiatedRpcVersion": 1}})
            n = 0
            while True:
                msg = self.recv(conn)
                if msg is None:
                    return
                d = msg["d"]
                n += 1
                ok, data, comment = self.handle(d["requestType"], d.get("requestData") or {})
                resp = {"op": 7, "d": {"requestType": d["requestType"], "requestId": d["requestId"],
                                       "requestStatus": {"result": ok, "code": 100 if ok else 600, "comment": comment}}}
                if data:
                    resp["d"]["responseData"] = data
                self.send(conn, resp, fragment=(n % 5 == 0))
        except (OSError, ConnectionError, AssertionError):
            pass
        finally:
            conn.close()

    # -- requests
    def handle(self, t, d):
        self.log.append((t, d))
        if t == "GetVersion":
            return True, {"obsVersion": "31.0.0", "obsWebSocketVersion": "5.5.0"}, ""
        if t == "GetVideoSettings":
            return True, {"baseWidth": self.canvas[0], "baseHeight": self.canvas[1]}, ""
        if t == "GetSceneList":
            return True, {"scenes": [{"sceneName": s} for s in self.scenes]}, ""
        if t == "CreateScene":
            if d["sceneName"] in self.scenes:
                return False, None, "exists"
            self.scenes.append(d["sceneName"])
            self.items[d["sceneName"]] = []
            return True, {}, ""
        if t == "GetInputList":
            return True, {"inputs": [{"inputName": n, "inputKind": v["kind"]} for n, v in self.inputs.items()]}, ""
        if t == "GetSceneItemList":
            return True, {"sceneItems": [dict(i) for i in self.items[d["sceneName"]]]}, ""
        if t in ("CreateInput", "CreateSceneItem"):
            name = d.get("inputName") or d.get("sourceName")
            if t == "CreateInput":
                if name in self.inputs:
                    return False, None, "input exists"
                self.inputs[name] = {"kind": d["inputKind"], "settings": d.get("inputSettings", {})}
            elif name not in self.inputs:
                return False, None, "no such source"
            iid = self._next_item
            self._next_item += 1
            self.items[d["sceneName"]].append({"sourceName": name, "sceneItemId": iid, "enabled": d.get("sceneItemEnabled", True),
                                               "sceneItemIndex": len(self.items[d["sceneName"]])})
            return True, {"sceneItemId": iid}, ""
        if t == "SetSceneItemTransform":
            for i in self.items[d["sceneName"]]:
                if i["sceneItemId"] == d["sceneItemId"]:
                    i["transform"] = d["sceneItemTransform"]
            return True, {}, ""
        if t == "SetSceneItemEnabled":
            for i in self.items[d["sceneName"]]:
                if i["sceneItemId"] == d["sceneItemId"]:
                    i["enabled"] = d["sceneItemEnabled"]
            return True, {}, ""
        if t == "SetSceneItemIndex":
            its = self.items[d["sceneName"]]
            it = next(i for i in its if i["sceneItemId"] == d["sceneItemId"])
            its.remove(it)
            its.insert(d["sceneItemIndex"], it)
            for k, i in enumerate(its):
                i["sceneItemIndex"] = k
            return True, {}, ""
        if t == "SetInputSettings":
            self.inputs[d["inputName"]]["settings"].update(d["inputSettings"])
            return True, {}, ""
        if t == "SetInputAudioMonitorType":
            return True, {}, ""
        if t == "SetCurrentProgramScene":
            self.program = d["sceneName"]
            return True, {}, ""
        if t == "GetSpecialInputs":
            return True, {"desktop1": "Desktop Audio", "desktop2": None, "mic1": "Mic/Aux"}, ""
        if t == "GetInputMute":
            return True, {"inputMuted": self.mutes[d["inputName"]]}, ""
        if t == "SetInputMute":
            self.mutes[d["inputName"]] = d["inputMuted"]
            return True, {}, ""
        if t == "GetRecordDirectory":
            return True, {"recordDirectory": self.rec_dir}, ""
        if t == "SetRecordDirectory":
            self.rec_dir = d["recordDirectory"]
            return True, {}, ""
        if t == "StartRecord":
            self.recording = True
            return True, {}, ""
        if t == "GetRecordStatus":
            return True, {"outputActive": self.recording, "outputDuration": 250}, ""
        if t == "StopRecord":
            self.recording = False
            return True, {"outputPath": self.rec_dir + "/2026-10-04 20-00-00.mkv"}, ""
        return False, None, f"unknown request {t}"

    def close(self):
        self.srv.close()


def chars():
    return [SimpleNamespace(name=n, slot=i) for i, n in enumerate(["Kaito", "Ren", "Mochi", "Yuki"])]


class TestFraming(unittest.TestCase):
    def test_rfc6455_accept_example(self):
        # RFC 6455 section 1.3 worked example
        self.assertEqual(O.ws_accept("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")

    def test_rfc6455_masked_hello_example(self):
        # RFC 6455 section 5.7: masked "Hello" with key 37 fa 21 3d
        f = O.encode_frame(O.OP_TEXT, b"Hello", mask=True, mask_key=bytes([0x37, 0xfa, 0x21, 0x3d]))
        self.assertEqual(f, bytes([0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58]))
        self.assertEqual(O.encode_frame(O.OP_TEXT, b"Hello", mask=False), bytes([0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f]))

    def test_lengths_roundtrip(self):
        a, b = socket.socketpair()
        try:
            for n in (0, 125, 126, 65535, 65536, 70000):
                payload = bytes(i % 251 for i in range(n))
                a.sendall(O.encode_frame(O.OP_BIN, payload, mask=True))
                fin, op, got = O.read_frame(b)
                self.assertEqual((fin, op, got), (True, O.OP_BIN, payload), n)
        finally:
            a.close()
            b.close()

    def test_auth_string(self):
        # independent computation of the obs-websocket recipe
        pw, salt, ch = "supersecretpassword", "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=", "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
        secret = base64.b64encode(hashlib.sha256((pw + salt).encode()).digest())
        want = base64.b64encode(hashlib.sha256(secret + ch.encode()).digest()).decode()
        self.assertEqual(O.obs_auth(pw, salt, ch), want)

    def test_window_spec_escaping(self):
        self.assertEqual(O.window_spec("AISky - Kaito"), "AISky - Kaito:GLFW30:java.exe")
        self.assertEqual(O.window_spec("a:b#c"), "a#3Ab#22c:GLFW30:java.exe")

    def test_grid_rects(self):
        self.assertEqual(O.grid_rects(1920, 1080), [(0, 0, 960, 540), (960, 0, 960, 540), (0, 540, 960, 540), (960, 540, 960, 540)])
        self.assertEqual(O.grid_rects(3840, 2160)[3], (1920, 1080, 1920, 1080))


class TestClient(unittest.TestCase):
    def setUp(self):
        self.fake = FakeOBS(password="hunter2")
        self.fake.start()

    def tearDown(self):
        self.fake.close()

    def test_auth_and_requests(self):
        c = O.OBSClient("127.0.0.1", self.fake.port, "hunter2", timeout=3).connect()
        try:
            self.assertEqual(c.request("GetVersion")["obsVersion"], "31.0.0")
            for _ in range(7):                     # some responses arrive fragmented with a ping inside
                self.assertIn("scenes", c.request("GetSceneList"))
            with self.assertRaises(O.OBSRequestError):
                c.request("Bogus")
        finally:
            c.close()
        self.assertIsNotNone(self.fake.identified_auth)

    def test_wrong_password(self):
        with self.assertRaises(O.OBSAuthError):
            O.OBSClient("127.0.0.1", self.fake.port, "nope", timeout=3).connect()

    def test_missing_password(self):
        with self.assertRaises(O.OBSAuthError):
            O.OBSClient("127.0.0.1", self.fake.port, "", timeout=3).connect()

    def test_big_message(self):
        big = FakeOBS(big_hello=True)
        big.start()
        try:
            c = O.OBSClient("127.0.0.1", big.port, "", timeout=3).connect()
            self.assertEqual(len(c.hello["padding"]), 70000)
            c.close()
        finally:
            big.close()


class TestRig(unittest.TestCase):
    def test_ensure_scenes_idempotent_and_layout(self):
        fake = FakeOBS(canvas=(3840, 2160))
        fake.start()
        try:
            c = O.OBSClient("127.0.0.1", fake.port, "", timeout=3).connect()
            rig = O.OBSRig(c, chars())
            s1 = rig.ensure_scenes()
            self.assertEqual(set(s1["created_scenes"]), {O.SCENE_INTRO, O.SCENE_GRID, O.SCENE_CINE})
            self.assertEqual(s1["canvas"], (3840, 2160))
            n_inputs = len(fake.inputs)
            self.assertEqual(n_inputs, 2 + 4 + 1 + 1)        # 4 players, camera, overlay
            s2 = O.OBSRig(c, chars()).ensure_scenes()
            self.assertEqual(s2["created_scenes"], [])
            self.assertEqual(s2["created_inputs"], [])
            self.assertEqual(len(fake.inputs), n_inputs)
            self.assertEqual(len(fake.items[O.SCENE_GRID]), 5)
            # grid tiles from the canvas, overlay on top and full canvas
            grid = {i["sourceName"]: i for i in fake.items[O.SCENE_GRID]}
            t = grid["AISky Cap Yuki"]["transform"]
            self.assertEqual((t["positionX"], t["positionY"], t["boundsWidth"], t["boundsHeight"]), (1920, 1080, 1920, 1080))
            self.assertEqual(fake.items[O.SCENE_GRID][-1]["sourceName"], O.OVERLAY_INPUT)
            ov = fake.inputs[O.OVERLAY_INPUT]
            self.assertEqual(ov["kind"], "browser_source")
            self.assertTrue(ov["settings"]["reroute_audio"])            # voice lines get recorded
            self.assertFalse(ov["settings"]["shutdown"])
            self.assertEqual((ov["settings"]["width"], ov["settings"]["height"]), (3840, 2160))
            cap = fake.inputs["AISky Cap Kaito"]
            self.assertEqual(cap["kind"], "window_capture")
            self.assertEqual(cap["settings"]["window"], "AISky - Kaito:GLFW30:java.exe")
            self.assertEqual(cap["settings"]["priority"], 1)             # title must match, never another client
            # one shared input per capture across scenes
            self.assertEqual(sum(1 for s in fake.items.values() for i in s if i["sourceName"] == "AISky Cap Ren"), 2)

            # focus: exactly one enabled in the intro scene
            rig.focus("Mochi")
            en = [i["sourceName"] for i in fake.items[O.SCENE_INTRO] if i["enabled"] and i["sourceName"] != O.OVERLAY_INPUT]
            self.assertEqual(en, ["AISky Cap Mochi"])
            self.assertEqual(fake.program, O.SCENE_INTRO)
            rig.focus(None)
            en = [i["sourceName"] for i in fake.items[O.SCENE_INTRO] if i["enabled"] and i["sourceName"] != O.OVERLAY_INPUT]
            self.assertEqual(en, [O.CAMERA_INPUT])
            rig.grid()
            self.assertEqual(fake.program, O.SCENE_GRID)

            # recording into the episode folder, owner's directory restored afterwards
            rig.mute_ambient()
            self.assertTrue(fake.mutes["Desktop Audio"])
            t0 = rig.start_record(Path("C:/tmp/ep1"))
            self.assertTrue(fake.recording)
            self.assertIn("ep1", fake.rec_dir)
            self.assertIsInstance(t0, float)
            path = rig.stop_record()
            self.assertIn("ep1", path)
            self.assertEqual(fake.rec_dir, "D:/owner/videos")
            rig.restore_mutes()
            self.assertEqual(fake.mutes, {"Desktop Audio": False, "Mic/Aux": True})
            c.close()
        finally:
            fake.close()


if __name__ == "__main__":
    unittest.main()
