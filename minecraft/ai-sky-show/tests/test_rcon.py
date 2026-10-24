"""Headless tests for scripts/rcon.py against an in-process fake RCON server (no Minecraft)."""
from __future__ import annotations

import os
import socket
import struct
import sys
import threading
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import rcon  # noqa: E402


class FakeRconServer(threading.Thread):
    """Speaks the Source RCON protocol the way Paper does: splits long replies into 4096-byte packets
    with the command's id and answers an empty RESPONSE_VALUE packet afterwards (the end marker)."""

    def __init__(self, password: str = "pw", replies: dict[str, str] | None = None, pre_auth_empty: bool = False):
        super().__init__(daemon=True)
        self.password = password
        self.replies = replies or {}
        self.pre_auth_empty = pre_auth_empty
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(1)
        self.port = self.sock.getsockname()[1]
        self.received: list[str] = []

    @staticmethod
    def _read_exact(c: socket.socket, n: int) -> bytes:
        b = b""
        while len(b) < n:
            chunk = c.recv(n - len(b))
            if not chunk:
                raise ConnectionError
            b += chunk
        return b

    def run(self) -> None:
        c, _ = self.sock.accept()
        try:
            while True:
                (length,) = struct.unpack("<i", self._read_exact(c, 4))
                rid, ptype, body = rcon.decode_packet(self._read_exact(c, length))
                if ptype == rcon.AUTH:
                    if self.pre_auth_empty:
                        c.sendall(rcon.encode_packet(rid, rcon.RESPONSE, ""))
                    ok = body == self.password
                    c.sendall(rcon.encode_packet(rid if ok else -1, 2, ""))
                elif ptype == rcon.EXEC:
                    self.received.append(body)
                    reply = self.replies.get(body, f"ran {body}")
                    for i in range(0, max(1, len(reply)), 4096):
                        c.sendall(rcon.encode_packet(rid, rcon.RESPONSE, reply[i:i + 4096]))
                elif ptype == rcon.RESPONSE:
                    c.sendall(rcon.encode_packet(rid, rcon.RESPONSE, f"Unknown request {hex(ptype)}"))
        except (ConnectionError, OSError):
            pass
        finally:
            c.close()
            self.sock.close()


class PacketTests(unittest.TestCase):
    def test_roundtrip(self):
        pkt = rcon.encode_packet(7, rcon.EXEC, "show status")
        (length,) = struct.unpack("<i", pkt[:4])
        self.assertEqual(length, len(pkt) - 4)
        self.assertEqual(rcon.decode_packet(pkt[4:]), (7, rcon.EXEC, "show status"))

    def test_utf8_body(self):
        pkt = rcon.encode_packet(1, rcon.EXEC, "say é✓")
        self.assertEqual(rcon.decode_packet(pkt[4:])[2], "say é✓")

    def test_short_packet_rejected(self):
        with self.assertRaises(ValueError):
            rcon.decode_packet(b"\x00" * 5)


class ClientTests(unittest.TestCase):
    def test_command_and_leading_slash(self):
        srv = FakeRconServer(replies={"show status": "Episode: stopped"})
        srv.start()
        with rcon.Rcon(port=srv.port, password="pw") as r:
            self.assertEqual(r.command("/show status"), "Episode: stopped")
            self.assertEqual(r.command("show mark x"), "ran show mark x")
        self.assertEqual(srv.received, ["show status", "show mark x"])

    def test_multi_packet_reply_is_joined(self):
        long = "".join(f"line {i:05d}\n" for i in range(2000))   # ~22 KB -> 6 packets
        srv = FakeRconServer(replies={"sb dial list": long})
        srv.start()
        with rcon.Rcon(port=srv.port, password="pw") as r:
            self.assertEqual(r.command("sb dial list"), long)

    def test_strip_colour_codes(self):
        srv = FakeRconServer(replies={"x": "§aGreen §lbold"})
        srv.start()
        with rcon.Rcon(port=srv.port, password="pw") as r:
            self.assertEqual(r.command("x", strip=True), "Green bold")

    def test_bad_password(self):
        srv = FakeRconServer(password="right")
        srv.start()
        with self.assertRaises(rcon.RconError):
            rcon.Rcon(port=srv.port, password="wrong").connect()

    def test_empty_packet_before_auth_result(self):
        srv = FakeRconServer(pre_auth_empty=True)
        srv.start()
        with rcon.Rcon(port=srv.port, password="pw") as r:
            self.assertEqual(r.command("hello"), "ran hello")

    def test_connect_refused(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        with self.assertRaises(rcon.RconError):
            rcon.Rcon(port=port, password="pw", timeout=1).connect()

    def test_defaults_come_from_show_json(self):
        r = rcon.Rcon()
        cfg = rcon.show_config()
        self.assertEqual(r.port, int(cfg["ports"]["rcon"]))
        self.assertEqual(r.password, cfg["rcon_password"])


if __name__ == "__main__":
    unittest.main()
