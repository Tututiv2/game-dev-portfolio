"""Fan-in proxy: accept several inbound RTMP connections on one port, and learn
the stream key each one publishes with.

Why this exists
---------------
Two problems, one solution.

1. ffmpeg's `-listen 1` accepts exactly ONE connection and then stops listening.
   Streamlabs' Dual Output opens two - horizontal and vertical - so the second
   is refused and vertical silently never goes live.

2. YouTube's "Create New Event" mints a NEW broadcast, with a NEW ingest key,
   at every single Go Live. A key pasted into SplitCast is therefore correct
   exactly once: it works the day it is pasted and silently publishes to the
   wrong stream every day after, while the real broadcast sits on "upcoming"
   waiting for data that never arrives. Nobody should have to keep on top of
   that, so the key is DISCOVERED rather than configured.

The key is already in the stream - RTMP sends it in the `publish` command as a
plain AMF0 string - so we read it out of the bytes as they pass and never alter
them. The client handshakes with the REAL upstream, not with us.

Terminating the handshake ourselves was tried first, so that the key could be
known BEFORE choosing an upstream. It does not work: a client sends `publish`
only after its `connect` has been acknowledged, so seeing it means implementing
a real RTMP server - window ack size, peer bandwidth, AMF `_result` - which is
far more surface area than this needs. Worse, the probe used to test whether
ffmpeg was listening WAS ffmpeg's one and only accept, killing the relay before
a byte arrived.

So: pipe first, learn the key in flight, and hand it to the runner. The runner
relaunches the relay with the right key and the client reconnects - a blip at
the very start of a stream, before the broadcast has any data in it.

Routing is by WHAT IS ACTUALLY LISTENING, never by arrival order. Order was the
first design and it broke in the field: ffmpeg had not started yet, the first
connection failed upstream, and that failure consumed slot #1 - so the retry was
misrouted and the relay received nothing. The upstream is chosen fresh for every
connection, from live state.
"""

import os
import socket
import threading
import time

BUF = 65536
HANDSHAKE_SIZE = 1536          # C1/S1/C2/S2 are each exactly this
RTMP_VERSION = 3
PUBLISH_SCAN_LIMIT = 65536     # give up looking for the key after this much
PUBLISH_TIMEOUT = 10.0

# AMF0 type tag for a string. Written as bytes([2]) rather than an escape: this
# project has had five separate cases of an escape being rewritten in transit
# and landing in a source file as a raw control byte - invisible in an editor,
# invisible in a diff. An explicit integer cannot be mangled.
AMF0_STRING = bytes([2])


def _recv_exact(sock, n, deadline=None):
    buf = b""
    while len(buf) < n:
        if deadline is not None:
            left = deadline - time.time()
            if left <= 0:
                raise TimeoutError("handshake timed out")
            sock.settimeout(left)
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("peer closed during handshake")
        buf += chunk
    return buf


def server_handshake(sock, deadline):
    """Answer a client's RTMP handshake as if we were the server.

    The 'simple' handshake: echo the peer's C1 back as S2, send random bytes as
    S1. No digest/HMAC - that is only demanded by a few CDNs, and both
    Streamlabs and ffmpeg accept simple.
    """
    c0c1 = _recv_exact(sock, 1 + HANDSHAKE_SIZE, deadline)
    c1 = c0c1[1:]
    s1 = os.urandom(HANDSHAKE_SIZE)
    sock.sendall(bytes([RTMP_VERSION]) + s1 + c1)
    _recv_exact(sock, HANDSHAKE_SIZE, deadline)     # C2
    return True


def client_handshake(sock, deadline):
    """Perform the client half against a real upstream."""
    c1 = os.urandom(HANDSHAKE_SIZE)
    sock.sendall(bytes([RTMP_VERSION]) + c1)
    s0s1s2 = _recv_exact(sock, 1 + HANDSHAKE_SIZE * 2, deadline)
    s1 = s0s1s2[1:1 + HANDSHAKE_SIZE]
    sock.sendall(s1)                                # C2 echoes S1
    return True


def extract_publish_key(buf):
    """The stream key from an RTMP `publish` command, or None.

    AMF0 encodes a string as 0x02, a 2-byte big-endian length, then the bytes.
    The publish command is the string "publish", a transaction number, a null,
    and then the stream name - so the first AMF0 string after "publish" is the
    key. Whatever the client sent is kept verbatim, query string and all,
    because the upstream expects exactly that.
    """
    i = buf.find(b"publish")
    if i < 0:
        return None
    j = buf.find(AMF0_STRING, i + 7)
    if j < 0 or j + 3 > len(buf):
        return None
    n = (buf[j + 1] << 8) | buf[j + 2]
    if n <= 0 or j + 3 + n > len(buf):
        return None
    key = buf[j + 3:j + 3 + n].decode("utf-8", "replace")
    return key if key and len(key) <= 250 else None


def redact(key):
    if not key:
        return "(none)"
    return key if len(key) <= 8 else key[:4] + "..." + key[-4:]


class Splitter:
    def __init__(self, routes, host="127.0.0.1", port=1935, on_event=None,
                 swap=False, on_key=None):
        """routes:     [(relay_host, relay_port), (passthrough_host, port)]
        on_key:     optional callable(key, label) called once per connection as
                    soon as the publish key is seen on the wire.
        """
        self.routes = list(routes)
        self.swap = bool(swap)
        self.host, self.port = host, port
        self.on_event = on_event or (lambda *_: None)
        self.on_key = on_key
        self.last_key = None
        self._srv = None
        self._thread = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._live = 0

    def live_connections(self):
        """How many streams are being carried right now.

        The runner asks before it relaunches the relay to pick up a new key.
        Zero means a relaunch is free; anything else means killing ffmpeg would
        drop a connection Streamlabs has already made - and Streamlabs answers
        that by tearing down the OTHER display, which is the whole bug this
        number exists to prevent.
        """
        with self._lock:
            return self._live

    # -- lifecycle --------------------------------------------------------
    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._srv.bind((self.host, self.port))
        self._srv.listen(8)
        self._srv.settimeout(0.5)
        self._stop.clear()
        self._thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._thread.start()
        self.on_event("log", "splitter listening on {}:{}".format(self.host, self.port))

    def stop(self):
        self._stop.set()
        try:
            if self._srv:
                self._srv.close()
        except OSError:
            pass
        if self._thread:
            self._thread.join(timeout=3)
        self._srv = None

    # -- internals --------------------------------------------------------
    def _read_until_publish(self, client, deadline):
        """Buffer post-handshake bytes until the publish key appears."""
        buf = bytearray()
        while len(buf) < PUBLISH_SCAN_LIMIT:
            left = deadline - time.time()
            if left <= 0:
                break
            client.settimeout(left)
            try:
                data = client.recv(BUF)
            except (socket.timeout, TimeoutError):
                break
            if not data:
                break
            buf += data
            key = extract_publish_key(bytes(buf))
            if key:
                return bytes(buf), key
        return bytes(buf), None

    def _connect_upstream(self):
        """Open the correct upstream and RETURN THAT SOCKET.

        Never probe-and-close: ffmpeg's `-listen 1` accepts exactly one
        connection, so a throwaway test connection consumes it and the real
        stream is then refused. The socket we test with is the socket we use.

        Relay first - if it is accepting, this is the first stream and belongs
        to it. A refusal means it already holds one, so this passes through.
        """
        relay, passthrough = self.routes[0], self.routes[-1]
        order = ([(relay, "relay"), (passthrough, "passthrough")] if not self.swap
                 else [(passthrough, "passthrough"), (relay, "relay")])
        last = None
        for addr, label in order:
            try:
                return socket.create_connection(addr, timeout=6), label, addr
            except OSError as exc:
                last = exc
        raise OSError(last or "no upstream reachable")

    def _accept_loop(self):
        while not self._stop.is_set():
            try:
                client, addr = self._srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=self._handle, args=(client, addr),
                             daemon=True).start()

    def _handle(self, client, addr):
        upstream = None
        label = "?"
        try:
            upstream, label, (uhost, uport) = self._connect_upstream()
        except OSError as exc:
            self.on_event("log", "!! no upstream for stream from {}: {}".format(
                addr[0], exc))
            try:
                client.close()
            except OSError:
                pass
            return

        self.on_event("log", "stream -> {} ({}:{})".format(label, uhost, uport))
        with self._lock:
            self._live += 1
        try:
            upstream.settimeout(None)
            client.settimeout(None)
            a = threading.Thread(target=self._pump,
                                 args=(client, upstream, label), daemon=True)
            b = threading.Thread(target=self._pump,
                                 args=(upstream, client), daemon=True)
            a.start(); b.start()
            a.join(); b.join()
        finally:
            with self._lock:
                self._live = max(0, self._live - 1)
            for s_ in (client, upstream):
                try:
                    if s_:
                        s_.close()
                except OSError:
                    pass
            self.on_event("log", "stream to {} ended".format(label))

    def _pump(self, src, dst, label=None):
        """Relay bytes verbatim. When label is set this is the client->upstream
        direction, so the publish key is watched for until it is found."""
        seen = bytearray() if label else None
        done = label is None
        try:
            while True:
                data = src.recv(BUF)
                if not data:
                    break
                dst.sendall(data)
                if not done:
                    seen += data
                    if len(seen) > PUBLISH_SCAN_LIMIT:
                        done = True
                        continue
                    key = extract_publish_key(bytes(seen))
                    if key:
                        done = True
                        seen = bytearray()
                        self.last_key = key
                        self.on_event("log", "{} stream key: {} (len {})".format(
                            label, redact(key), len(key)))
                        if self.on_key:
                            try:
                                self.on_key(key, label)
                            except Exception as exc:   # noqa: BLE001
                                self.on_event("log", "!! on_key failed: " + str(exc))
        except OSError:
            pass
        finally:
            try:
                dst.shutdown(socket.SHUT_WR)
            except OSError:
                pass
