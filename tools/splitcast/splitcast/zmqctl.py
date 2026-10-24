"""A minimal ZeroMQ REQ client, stdlib only.

ffmpeg's `azmq` filter listens on a ZMQ REQ/REP socket and brokers commands to
named filters in the graph, which is the only way to change a running filter
without restarting the process. Restarting is not an option here: the relay is
the live stream, and a two-second blackout on both platforms is a much worse
price than a volume slider is worth.

pyzmq would do this in three lines and would also be the first dependency in the
project. The wire protocol needed for one REQ socket is small enough to write:
a fixed 64-byte greeting, a READY command, then length-prefixed frames.

Reference: ZMTP 3.1, https://rfc.zeromq.org/spec/23/
"""

import socket
import struct

SIGNATURE = b"\xff" + b"\x00" * 8 + b"\x7f"
VERSION = b"\x03\x01"
MECHANISM = b"NULL" + b"\x00" * 16      # 20 bytes, null-padded
AS_SERVER = b"\x00"
FILLER = b"\x00" * 31

MORE = 0x01
COMMAND = 0x04
LONG = 0x02


def _greeting():
    return SIGNATURE + VERSION + MECHANISM + AS_SERVER + FILLER


def _ready():
    """READY command announcing this peer is a REQ socket."""
    body = b"\x05READY"
    body += bytes([len(b"Socket-Type")]) + b"Socket-Type"
    body += struct.pack(">I", len(b"REQ")) + b"REQ"
    return bytes([COMMAND, len(body)]) + body


def _frame(payload, more=False):
    flags = MORE if more else 0
    if len(payload) < 256:
        return bytes([flags, len(payload)]) + payload
    return bytes([flags | LONG]) + struct.pack(">Q", len(payload)) + payload


def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("peer closed during handshake")
        buf += chunk
    return buf


def _recv_frame(sock):
    flags = _recv_exact(sock, 1)[0]
    if flags & LONG:
        size = struct.unpack(">Q", _recv_exact(sock, 8))[0]
    else:
        size = _recv_exact(sock, 1)[0]
    body = _recv_exact(sock, size) if size else b""
    return flags, body


def send(command, host="127.0.0.1", port=5571, timeout=2.0):
    """Send one filter command and return ffmpeg's reply text.

    Example: send("volume@music volume 0.35")

    ffmpeg replies "0 Success" on a command it accepted. Anything else is
    returned verbatim rather than raised - a rejected volume change must not
    take down the caller.
    """
    sock = socket.create_connection((host, port), timeout=timeout)
    try:
        sock.settimeout(timeout)
        sock.sendall(_greeting())
        _recv_exact(sock, 64)          # peer greeting
        sock.sendall(_ready())
        _recv_frame(sock)              # peer READY

        # A REQ socket prefixes its payload with an empty delimiter frame.
        sock.sendall(_frame(b"", more=True))
        sock.sendall(_frame(command.encode("utf-8")))

        parts = []
        while True:
            flags, body = _recv_frame(sock)
            if not (flags & COMMAND):
                parts.append(body)
            if not (flags & MORE):
                break
        return b"".join(parts).decode("utf-8", "replace").strip()
    finally:
        try:
            sock.close()
        except OSError:
            pass


def set_volume(value, host="127.0.0.1", port=5571, target="volume@music"):
    """Set the music branch volume live. Returns (ok, reply)."""
    try:
        reply = send("{} volume {:.4f}".format(target, float(value)), host, port)
    except (OSError, ConnectionError) as exc:
        return False, str(exc)
    return reply.startswith("0 "), reply
