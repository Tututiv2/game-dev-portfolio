"""OBS control over obs-websocket v5 — stdlib only (CONTRACT §12).

Two layers:
  * `WebSocket`  — a minimal RFC 6455 client (handshake, masked client frames, fragmentation,
                   ping/pong, close). No third-party packages.
  * `OBSClient`  — obs-websocket v5: Hello -> Identify (auth challenge) -> Identified, then
                   request/response with request ids, events delivered to callbacks.
  * `OBSRig`     — the show's use of OBS: build the three scenes idempotently, lay out the 2x2
                   grid on whatever canvas OBS has, switch scenes, start/stop recording into the
                   episode folder (and PUT THE OWNER'S RECORD DIRECTORY BACK afterwards), mute the
                   ambient audio inputs during the episode and restore them.

Auth (obs-websocket protocol doc, "Creating an authentication string"):
    secret = base64( sha256( password + salt ) )
    auth   = base64( sha256( secret + challenge ) )

Window capture: OBS identifies a window as "title:class:exe" with ':' and '#' percent-ish encoded
('#' -> '#22', ':' -> '#3A'). Minecraft 26.x is a GLFW window (class GLFW30) and our launcher
starts it with java.exe (not javaw.exe — MinecraftLauncher/launcher/java.py). All five clients share
class+exe, so the capture uses priority 1 = "window title must match": a capture must never fall
back to grabbing a different client's window. ShowCam sets each title to "AISky - <Name>" (§11).
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import socket
import struct
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

OP_CONT, OP_TEXT, OP_BIN, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA

# obs-websocket v5 op codes
OBS_HELLO, OBS_IDENTIFY, OBS_IDENTIFIED, OBS_REIDENTIFY = 0, 1, 2, 3
OBS_EVENT, OBS_REQUEST, OBS_RESPONSE = 5, 6, 7
SUB_OUTPUTS = 1 << 6

SCENE_INTRO = "AISky Intro"
SCENE_GRID = "AISky Grid"
SCENE_CINE = "AISky Cinematic"
OVERLAY_INPUT = "AISky Overlay"
CAMERA_INPUT = "AISky Cap Camera"


class OBSError(RuntimeError):
    pass


class OBSAuthError(OBSError):
    pass


class OBSRequestError(OBSError):
    def __init__(self, request_type: str, code: int, comment: str):
        super().__init__(f"{request_type} failed ({code}): {comment}")
        self.request_type, self.code, self.comment = request_type, code, comment


# ----------------------------------------------------------------------------- pure helpers
def ws_accept(key: str) -> str:
    """Sec-WebSocket-Accept for a Sec-WebSocket-Key (RFC 6455 §1.3)."""
    return base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()


def obs_auth(password: str, salt: str, challenge: str) -> str:
    secret = base64.b64encode(hashlib.sha256((password + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((secret + challenge).encode()).digest()).decode()


def encode_frame(opcode: int, payload: bytes, *, mask: bool = True, fin: bool = True,
                 mask_key: bytes | None = None) -> bytes:
    head = bytearray([(0x80 if fin else 0) | (opcode & 0x0F)])
    n = len(payload)
    mbit = 0x80 if mask else 0
    if n < 126:
        head.append(mbit | n)
    elif n < 65536:
        head.append(mbit | 126)
        head += struct.pack("!H", n)
    else:
        head.append(mbit | 127)
        head += struct.pack("!Q", n)
    if mask:
        key = mask_key if mask_key is not None else os.urandom(4)
        head += key
        payload = bytes(b ^ key[i & 3] for i, b in enumerate(payload))
    return bytes(head) + payload


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("websocket closed by peer")
        buf += chunk
    return bytes(buf)


def read_frame(sock: socket.socket) -> tuple[bool, int, bytes]:
    """Read one frame -> (fin, opcode, unmasked payload)."""
    b0, b1 = _recv_exact(sock, 2)
    fin, opcode = bool(b0 & 0x80), b0 & 0x0F
    masked, n = bool(b1 & 0x80), b1 & 0x7F
    if n == 126:
        n = struct.unpack("!H", _recv_exact(sock, 2))[0]
    elif n == 127:
        n = struct.unpack("!Q", _recv_exact(sock, 8))[0]
    key = _recv_exact(sock, 4) if masked else None
    payload = _recv_exact(sock, n) if n else b""
    if key:
        payload = bytes(b ^ key[i & 3] for i, b in enumerate(payload))
    return fin, opcode, payload


def window_spec(title: str, cls: str = "GLFW30", exe: str = "java.exe") -> str:
    def enc(s: str) -> str:
        return s.replace("#", "#22").replace(":", "#3A")
    return f"{enc(title)}:{enc(cls)}:{enc(exe)}"


def grid_rects(canvas_w: int, canvas_h: int) -> list[tuple[int, int, int, int]]:
    """2x2 tiles by slot: (x, y, w, h)."""
    w, h = canvas_w // 2, canvas_h // 2
    return [(0, 0, w, h), (w, 0, w, h), (0, h, w, h), (w, h, w, h)]


def transform(x: float, y: float, w: float, h: float) -> dict:
    return {"positionX": float(x), "positionY": float(y), "alignment": 5, "rotation": 0.0,
            "boundsType": "OBS_BOUNDS_SCALE_INNER", "boundsAlignment": 0,
            "boundsWidth": float(max(1, w)), "boundsHeight": float(max(1, h)),
            "cropLeft": 0, "cropRight": 0, "cropTop": 0, "cropBottom": 0}


def read_obs_websocket_config() -> dict:
    """Read (never write) OBS's own websocket config: enabled/port/password."""
    p = Path(os.environ.get("APPDATA", "")) / "obs-studio" / "plugin_config" / "obs-websocket" / "config.json"
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


# ----------------------------------------------------------------------------- websocket
class WebSocket:
    def __init__(self, host: str, port: int, path: str = "/", subprotocol: str | None = None,
                 timeout: float = 5.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        lines = [f"GET {path} HTTP/1.1", f"Host: {host}:{port}", "Upgrade: websocket",
                 "Connection: Upgrade", f"Sec-WebSocket-Key: {key}", "Sec-WebSocket-Version: 13"]
        if subprotocol:
            lines.append(f"Sec-WebSocket-Protocol: {subprotocol}")
        self.sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
        resp = bytearray()
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(1)
            if not chunk:
                raise ConnectionError("handshake: connection closed")
            resp += chunk
            if len(resp) > 16384:
                raise ConnectionError("handshake: response too large")
        head = resp.decode("latin-1").split("\r\n")
        if " 101 " not in head[0] + " ":
            raise ConnectionError(f"handshake refused: {head[0]}")
        hdrs = {}
        for line in head[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                hdrs[k.strip().lower()] = v.strip()
        if hdrs.get("sec-websocket-accept") != ws_accept(key):
            raise ConnectionError("handshake: bad Sec-WebSocket-Accept")
        self._send_lock = threading.Lock()
        self.closed = False

    def settimeout(self, t: float | None) -> None:
        self.sock.settimeout(t)

    def send_text(self, text: str) -> None:
        with self._send_lock:
            self.sock.sendall(encode_frame(OP_TEXT, text.encode("utf-8")))

    def recv(self) -> tuple[int, bytes]:
        """Next complete message (text/binary/close); answers pings itself."""
        msg_op, parts = None, []
        while True:
            fin, op, payload = read_frame(self.sock)
            if op == OP_PING:
                with self._send_lock:
                    self.sock.sendall(encode_frame(OP_PONG, payload))
                continue
            if op == OP_PONG:
                continue
            if op == OP_CLOSE:
                self.closed = True
                try:
                    with self._send_lock:
                        self.sock.sendall(encode_frame(OP_CLOSE, payload[:2]))
                except OSError:
                    pass
                return OP_CLOSE, payload
            if op != OP_CONT:
                msg_op, parts = op, [payload]
            else:
                parts.append(payload)
            if fin:
                return msg_op if msg_op is not None else OP_TEXT, b"".join(parts)

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            try:
                with self._send_lock:
                    self.sock.sendall(encode_frame(OP_CLOSE, struct.pack("!H", 1000)))
            except OSError:
                pass
        try:
            self.sock.close()
        except OSError:
            pass


# ----------------------------------------------------------------------------- obs client
class OBSClient:
    def __init__(self, host: str = "127.0.0.1", port: int = 4455, password: str = "",
                 timeout: float = 5.0):
        self.host, self.port, self.password, self.timeout = host, port, password, timeout
        self.ws: WebSocket | None = None
        self.hello: dict = {}
        self._pending: dict[str, dict] = {}
        self._cv = threading.Condition()
        self._listeners: list[Callable[[str, dict], None]] = []
        self._reader: threading.Thread | None = None
        self.close_reason: str | None = None

    # -- lifecycle
    def connect(self) -> "OBSClient":
        ws = WebSocket(self.host, self.port, subprotocol="obsws.json", timeout=self.timeout)
        ws.settimeout(self.timeout)
        op, raw = ws.recv()
        if op == OP_CLOSE:
            raise OBSError(f"OBS closed during hello: {raw!r}")
        hello = json.loads(raw)
        if hello.get("op") != OBS_HELLO:
            raise OBSError(f"expected Hello, got {hello}")
        self.hello = hello["d"]
        ident: dict[str, Any] = {"rpcVersion": 1, "eventSubscriptions": SUB_OUTPUTS}
        auth = self.hello.get("authentication")
        if auth:
            if not self.password:
                ws.close()
                raise OBSAuthError("OBS requires a password (set obs_password in config/show.json)")
            ident["authentication"] = obs_auth(self.password, auth["salt"], auth["challenge"])
        ws.send_text(json.dumps({"op": OBS_IDENTIFY, "d": ident}))
        op, raw = ws.recv()
        if op == OP_CLOSE:
            code = struct.unpack("!H", raw[:2])[0] if len(raw) >= 2 else 0
            reason = raw[2:].decode("utf-8", "replace")
            ws.close()
            if code == 4009:
                raise OBSAuthError(f"OBS rejected the password ({reason or 'auth failed'})")
            raise OBSError(f"OBS closed the connection ({code}): {reason}")
        msg = json.loads(raw)
        if msg.get("op") != OBS_IDENTIFIED:
            raise OBSError(f"expected Identified, got {msg}")
        ws.settimeout(None)
        self.ws = ws
        self._reader = threading.Thread(target=self._read_loop, daemon=True, name="OBSReader")
        self._reader.start()
        return self

    def close(self) -> None:
        if self.ws:
            self.ws.close()

    @property
    def connected(self) -> bool:
        return bool(self.ws) and not self.ws.closed and self.close_reason is None

    def on_event(self, fn: Callable[[str, dict], None]) -> None:
        self._listeners.append(fn)

    def _read_loop(self) -> None:
        try:
            while True:
                op, raw = self.ws.recv()
                if op == OP_CLOSE:
                    self.close_reason = raw[2:].decode("utf-8", "replace") or "closed"
                    break
                try:
                    msg = json.loads(raw)
                except ValueError:
                    continue
                d = msg.get("d", {})
                if msg.get("op") == OBS_RESPONSE:
                    with self._cv:
                        self._pending[d.get("requestId", "")] = d
                        self._cv.notify_all()
                elif msg.get("op") == OBS_EVENT:
                    for fn in list(self._listeners):
                        try:
                            fn(d.get("eventType", ""), d.get("eventData") or {})
                        except Exception:
                            pass
        except (OSError, ConnectionError) as e:
            self.close_reason = str(e) or "connection lost"
        with self._cv:
            self._cv.notify_all()

    # -- requests
    def request(self, request_type: str, data: dict | None = None, timeout: float | None = None) -> dict:
        if not self.ws:
            raise OBSError("not connected")
        rid = uuid.uuid4().hex
        d: dict[str, Any] = {"requestType": request_type, "requestId": rid}
        if data is not None:
            d["requestData"] = data
        self.ws.send_text(json.dumps({"op": OBS_REQUEST, "d": d}))
        deadline = time.time() + (timeout or self.timeout)
        with self._cv:
            while rid not in self._pending:
                if self.close_reason:
                    raise OBSError(f"OBS connection closed: {self.close_reason}")
                left = deadline - time.time()
                if left <= 0:
                    raise OBSError(f"{request_type}: timed out")
                self._cv.wait(left)
            resp = self._pending.pop(rid)
        st = resp.get("requestStatus", {})
        if not st.get("result", False):
            raise OBSRequestError(request_type, st.get("code", 0), st.get("comment", ""))
        return resp.get("responseData") or {}

    def __call__(self, request_type: str, **data: Any) -> dict:
        return self.request(request_type, data or None)


# ----------------------------------------------------------------------------- the show's rig
class OBSRig:
    """Scenes, layout, scene switching, recording — idempotent and restoring what it touched."""

    def __init__(self, client: OBSClient, characters: list, overlay_url: str = "http://127.0.0.1:5650/overlay",
                 camera_title: str = "AISky - Camera"):
        self.c = client
        self.chars = list(characters)          # objects with .name and .slot
        self.overlay_url = overlay_url
        self.camera_title = camera_title
        self.canvas = (1920, 1080)
        self._items: dict[tuple[str, str], int] = {}
        self._prev_rec_dir: str | None = None
        self._prev_mutes: dict[str, bool] = {}
        self.focused: str | None = None
        self.record_path: str | None = None

    @staticmethod
    def cap_name(char_name: str) -> str:
        return f"AISky Cap {char_name}"

    @staticmethod
    def window_title(char_name: str) -> str:
        return f"AISky - {char_name}"

    # -- discovery
    def read_canvas(self) -> tuple[int, int]:
        v = self.c.request("GetVideoSettings")
        self.canvas = (int(v.get("baseWidth", 1920)), int(v.get("baseHeight", 1080)))
        return self.canvas

    def _scene_items(self, scene: str) -> dict[str, int]:
        items = self.c.request("GetSceneItemList", {"sceneName": scene}).get("sceneItems", [])
        out = {i["sourceName"]: int(i["sceneItemId"]) for i in items}
        for name, iid in out.items():
            self._items[(scene, name)] = iid
        return out

    def _window_settings(self, title: str) -> dict:
        return {"window": window_spec(title), "priority": 1, "method": 2, "cursor": False,
                "client_area": True, "compatibility": False, "capture_audio": False}

    def _overlay_settings(self) -> dict:
        w, h = self.canvas
        # reroute_audio = "Control audio via OBS": the overlay page's <audio> (the voice lines) becomes
        # an OBS audio source and is RECORDED. shutdown=false + restart_when_active=false keep the page
        # alive across scene switches (one shared input in all three scenes) so a caption mid-line
        # doesn't get cut by a scene change.
        return {"url": self.overlay_url, "width": w, "height": h, "reroute_audio": True,
                "shutdown": False, "restart_when_active": False, "fps_custom": False}

    def _ensure_item(self, scene: str, source: str, kind: str | None, settings: dict | None,
                     existing_inputs: set[str], scene_items: dict[str, int], enabled: bool = True) -> int:
        if source in scene_items:
            return scene_items[source]
        if source not in existing_inputs:
            r = self.c.request("CreateInput", {"sceneName": scene, "inputName": source, "inputKind": kind,
                                               "inputSettings": settings or {}, "sceneItemEnabled": enabled})
            existing_inputs.add(source)
        else:
            r = self.c.request("CreateSceneItem", {"sceneName": scene, "sourceName": source,
                                                   "sceneItemEnabled": enabled})
        iid = int(r["sceneItemId"])
        scene_items[source] = iid
        self._items[(scene, source)] = iid
        return iid

    def ensure_scenes(self) -> dict:
        """Create/repair the three scenes. Safe to run any number of times. Returns a summary."""
        self.read_canvas()
        W, H = self.canvas
        scenes = {s["sceneName"] for s in self.c.request("GetSceneList").get("scenes", [])}
        created = []
        for s in (SCENE_INTRO, SCENE_GRID, SCENE_CINE):
            if s not in scenes:
                self.c.request("CreateScene", {"sceneName": s})
                created.append(s)
        inputs = {i["inputName"] for i in self.c.request("GetInputList").get("inputs", [])}
        before = set(inputs)
        rects = grid_rects(W, H)
        # Grid: four captures tiled + overlay on top
        items = self._scene_items(SCENE_GRID)
        for ch in self.chars:
            name = self.cap_name(ch.name)
            iid = self._ensure_item(SCENE_GRID, name, "window_capture", self._window_settings(self.window_title(ch.name)),
                                    inputs, items)
            x, y, w, h = rects[ch.slot % 4]
            self.c.request("SetSceneItemTransform", {"sceneName": SCENE_GRID, "sceneItemId": iid,
                                                     "sceneItemTransform": transform(x, y, w, h)})
        # Intro (= single-player focus): every capture full-screen, exactly one enabled at a time
        items_i = self._scene_items(SCENE_INTRO)
        for ch in self.chars:
            name = self.cap_name(ch.name)
            iid = self._ensure_item(SCENE_INTRO, name, "window_capture", None, inputs, items_i, enabled=False)
            self.c.request("SetSceneItemTransform", {"sceneName": SCENE_INTRO, "sceneItemId": iid,
                                                     "sceneItemTransform": transform(0, 0, W, H)})
        iid = self._ensure_item(SCENE_INTRO, CAMERA_INPUT, "window_capture",
                                self._window_settings(self.camera_title), inputs, items_i, enabled=True)
        self.c.request("SetSceneItemTransform", {"sceneName": SCENE_INTRO, "sceneItemId": iid,
                                                 "sceneItemTransform": transform(0, 0, W, H)})
        # Cinematic: camera full-screen
        items_c = self._scene_items(SCENE_CINE)
        iid = self._ensure_item(SCENE_CINE, CAMERA_INPUT, "window_capture", None, inputs, items_c)
        self.c.request("SetSceneItemTransform", {"sceneName": SCENE_CINE, "sceneItemId": iid,
                                                 "sceneItemTransform": transform(0, 0, W, H)})
        # Overlay last in every scene, on top
        for scene, its in ((SCENE_GRID, items), (SCENE_INTRO, items_i), (SCENE_CINE, items_c)):
            iid = self._ensure_item(scene, OVERLAY_INPUT, "browser_source", self._overlay_settings(), inputs, its)
            self.c.request("SetSceneItemTransform", {"sceneName": scene, "sceneItemId": iid,
                                                     "sceneItemTransform": transform(0, 0, W, H)})
            self.c.request("SetSceneItemIndex", {"sceneName": scene, "sceneItemId": iid,
                                                 "sceneItemIndex": len(its) - 1})
        # keep settings current even on pre-existing inputs (names may have been renamed in cast.json)
        for ch in self.chars:
            self.c.request("SetInputSettings", {"inputName": self.cap_name(ch.name),
                                                "inputSettings": self._window_settings(self.window_title(ch.name)),
                                                "overlay": True})
        self.c.request("SetInputSettings", {"inputName": OVERLAY_INPUT, "inputSettings": self._overlay_settings(),
                                            "overlay": True})
        try:   # hear the voice lines locally too (optional — recording works either way)
            self.c.request("SetInputAudioMonitorType", {"inputName": OVERLAY_INPUT,
                                                        "monitorType": "OBS_MONITORING_TYPE_MONITOR_AND_OUTPUT"})
        except OBSError:
            pass
        return {"canvas": self.canvas, "created_scenes": created,
                "created_inputs": sorted(inputs - before)}

    # -- scenes
    def _item(self, scene: str, source: str) -> int | None:
        if (scene, source) not in self._items:
            self._scene_items(scene)
        return self._items.get((scene, source))

    def program(self, scene: str) -> None:
        self.c.request("SetCurrentProgramScene", {"sceneName": scene})

    def grid(self) -> None:
        self.program(SCENE_GRID)

    def cinematic(self) -> None:
        self.program(SCENE_CINE)

    def focus(self, char_name: str | None) -> None:
        """Intro scene showing one player's POV full-screen (None = the camera client)."""
        want = self.cap_name(char_name) if char_name else CAMERA_INPUT
        names = [self.cap_name(c.name) for c in self.chars] + [CAMERA_INPUT]
        # enable the new one first so there is never an empty frame
        iid = self._item(SCENE_INTRO, want)
        if iid is not None:
            self.c.request("SetSceneItemEnabled", {"sceneName": SCENE_INTRO, "sceneItemId": iid,
                                                   "sceneItemEnabled": True})
        for n in names:
            if n == want:
                continue
            other = self._item(SCENE_INTRO, n)
            if other is not None:
                self.c.request("SetSceneItemEnabled", {"sceneName": SCENE_INTRO, "sceneItemId": other,
                                                       "sceneItemEnabled": False})
        self.focused = char_name
        self.program(SCENE_INTRO)

    # -- audio
    def mute_ambient(self) -> None:
        """Mute OBS's global Desktop/Mic inputs for the episode (remembering their state)."""
        try:
            special = self.c.request("GetSpecialInputs")
        except OBSError:
            return
        for key in ("desktop1", "desktop2", "mic1", "mic2", "mic3", "mic4"):
            name = special.get(key)
            if not name:
                continue
            try:
                was = bool(self.c.request("GetInputMute", {"inputName": name}).get("inputMuted"))
                self._prev_mutes[name] = was
                self.c.request("SetInputMute", {"inputName": name, "inputMuted": True})
            except OBSError:
                pass

    def restore_mutes(self) -> None:
        for name, was in list(self._prev_mutes.items()):
            try:
                self.c.request("SetInputMute", {"inputName": name, "inputMuted": was})
            except OBSError:
                pass
        self._prev_mutes.clear()

    # -- recording
    def start_record(self, directory: Path | str) -> float:
        """Point recordings at the episode folder, start, return the epoch-seconds the file starts at."""
        directory = str(Path(directory).resolve())
        try:
            self._prev_rec_dir = self.c.request("GetRecordDirectory").get("recordDirectory")
            self.c.request("SetRecordDirectory", {"recordDirectory": directory})
        except OBSError:
            self._prev_rec_dir = None
        self.c.request("StartRecord")
        t_req = time.time()
        deadline = t_req + 10
        while time.time() < deadline:
            st = self.c.request("GetRecordStatus")
            if st.get("outputActive"):
                return time.time() - float(st.get("outputDuration", 0)) / 1000.0
            time.sleep(0.1)
        return t_req

    def stop_record(self) -> str | None:
        try:
            self.record_path = self.c.request("StopRecord", timeout=30).get("outputPath")
        finally:
            self.restore_record_dir()
        return self.record_path

    def restore_record_dir(self) -> None:
        if self._prev_rec_dir:
            try:
                self.c.request("SetRecordDirectory", {"recordDirectory": self._prev_rec_dir})
            except OBSError:
                pass
            self._prev_rec_dir = None


def connect_from_config(timeout: float = 3.0) -> OBSClient:
    """Connect using config/show.json; if its obs_password is empty, fall back to OBS's own
    websocket config (read-only) so the owner never has to copy a password around."""
    from .config import SHOW
    pw = SHOW.obs_password or read_obs_websocket_config().get("server_password", "")
    return OBSClient("127.0.0.1", int(SHOW.ports.get("obs", 4455)), pw, timeout=timeout).connect()
