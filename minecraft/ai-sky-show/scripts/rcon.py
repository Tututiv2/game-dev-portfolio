"""Minimal stdlib RCON client for the show server (127.0.0.1:25641 by default).

Usage:
    py -3 scripts/rcon.py "show status"
    py -3 scripts/rcon.py --strip "sb dial list skills"
    py -3 scripts/rcon.py --port 25631 --password skydev "sb info"      (any other server)
    py -3 scripts/rcon.py --ping        exit 0 when RCON answers (start_server.ps1 -WaitReady uses it)

Import:
    from rcon import Rcon
    with Rcon() as r:
        print(r.command("show status"))

Long replies: the server splits a reply into several 4096-byte packets with the same id. After the
command we send an empty RESPONSE_VALUE packet with another id; the server answers it only after it
has finished the command's reply, so its answer marks the end (same trick as SkyClone's rcon.ps1).
Only output printed WHILE the command runs comes back.

Exit codes: 0 ok, 1 connect/auth failure.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import struct
import sys

AUTH = 3
EXEC = 2
RESPONSE = 0

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SECTION_CODES = re.compile("§[0-9a-fk-orx]", re.IGNORECASE)


def show_config() -> dict:
    try:
        with open(os.path.join(ROOT, "config", "show.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def encode_packet(req_id: int, ptype: int, body: str) -> bytes:
    data = body.encode("utf-8")
    # length counts everything after the length field: id + type + body + two NULs
    return struct.pack("<iii", 4 + 4 + len(data) + 2, req_id, ptype) + data + b"\x00\x00"


def decode_packet(payload: bytes) -> tuple[int, int, str]:
    """payload = everything after the length field."""
    if len(payload) < 10:
        raise ValueError(f"short packet ({len(payload)} bytes)")
    req_id, ptype = struct.unpack("<ii", payload[:8])
    return req_id, ptype, payload[8:-2].decode("utf-8", "replace")


class RconError(Exception):
    pass


class Rcon:
    def __init__(self, host: str = "127.0.0.1", port: int | None = None, password: str | None = None,
                 timeout: float = 5.0) -> None:
        cfg = show_config()
        self.host = host
        self.port = port if port is not None else int(cfg.get("ports", {}).get("rcon", 25641))
        self.password = password if password is not None else str(cfg.get("rcon_password", ""))
        self.timeout = timeout
        self.sock: socket.socket | None = None
        self._next = 2

    # -- connection ------------------------------------------------------------------------
    def connect(self) -> "Rcon":
        try:
            self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError as e:
            raise RconError(f"could not connect to {self.host}:{self.port} - {e}") from e
        try:
            self.sock.settimeout(max(self.timeout, 30.0))
            self.sock.sendall(encode_packet(1, AUTH, self.password))
            rid, ptype, _ = self._read()
            if rid != 1 and ptype == RESPONSE:          # some servers send an empty RESPONSE first
                rid, ptype, _ = self._read()
            if rid == -1:
                raise RconError("authentication failed (wrong password)")
        except BaseException:
            self.close()
            raise
        return self

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.close()
            finally:
                self.sock = None

    def __enter__(self) -> "Rcon":
        return self.connect() if self.sock is None else self

    def __exit__(self, *exc) -> None:
        self.close()

    # -- io --------------------------------------------------------------------------------
    def _read_exact(self, n: int) -> bytes:
        assert self.sock is not None
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RconError(f"connection closed after {len(buf)} of {n} bytes")
            buf.extend(chunk)
        return bytes(buf)

    def _read(self) -> tuple[int, int, str]:
        (length,) = struct.unpack("<i", self._read_exact(4))
        if length < 10 or length > 1_048_576:
            raise RconError(f"implausible packet length {length}")
        return decode_packet(self._read_exact(length))

    def command(self, text: str, strip: bool = False) -> str:
        if self.sock is None:
            self.connect()
        assert self.sock is not None
        text = text.strip()
        if text.startswith("/"):
            text = text[1:]
        cmd_id, end_id = self._next, self._next + 1
        self._next += 2
        self.sock.sendall(encode_packet(cmd_id, EXEC, text))
        self.sock.sendall(encode_packet(end_id, RESPONSE, ""))
        parts: list[str] = []
        got_reply = False
        while True:
            try:
                rid, _, body = self._read()
            except (socket.timeout, TimeoutError):
                if got_reply:
                    break
                raise
            if rid == end_id:
                break
            if rid == cmd_id:
                parts.append(body)
                if not got_reply:
                    got_reply = True
                    self.sock.settimeout(3.0)
        self.sock.settimeout(max(self.timeout, 30.0))
        out = "".join(parts)
        return SECTION_CODES.sub("", out) if strip else out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="RCON client for the AISkyShow server")
    ap.add_argument("command", nargs="*", help="command to run (no leading slash needed)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--password", default=None)
    ap.add_argument("--timeout", type=float, default=5.0)
    ap.add_argument("--strip", action="store_true", help="remove colour codes")
    ap.add_argument("--ping", action="store_true", help="only connect + authenticate")
    a = ap.parse_args(argv)
    try:
        with Rcon(a.host, a.port, a.password, a.timeout) as r:
            if a.ping or not a.command:
                print(f"RCON ok ({r.host}:{r.port})")
                return 0
            out = r.command(" ".join(a.command), strip=a.strip)
    except (RconError, OSError) as e:
        print(f"RCON: {e}", file=sys.stderr)
        return 1
    if out:
        try:
            sys.stdout.write(out if out.endswith("\n") else out + "\n")
        except UnicodeEncodeError:
            sys.stdout.buffer.write((out + "\n").encode("utf-8", "replace"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
