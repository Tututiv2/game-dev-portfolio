"""Minimal NBT reader (big-endian, gzipped base64) for the auction sample's item_bytes.

Only what the generator needs: parse a compound tree into Python dicts/lists.
"""
import base64
import gzip
import io
import struct


class _R:
    def __init__(self, b):
        self.b = b
        self.i = 0

    def take(self, n):
        v = self.b[self.i:self.i + n]
        self.i += n
        return v

    def u8(self):
        return self.take(1)[0]

    def i8(self):
        return struct.unpack('>b', self.take(1))[0]

    def i16(self):
        return struct.unpack('>h', self.take(2))[0]

    def i32(self):
        return struct.unpack('>i', self.take(4))[0]

    def i64(self):
        return struct.unpack('>q', self.take(8))[0]

    def f32(self):
        return struct.unpack('>f', self.take(4))[0]

    def f64(self):
        return struct.unpack('>d', self.take(8))[0]

    def string(self):
        n = struct.unpack('>H', self.take(2))[0]
        return self.take(n).decode('utf-8', errors='replace')


def _payload(r, t):
    if t == 1:
        return r.i8()
    if t == 2:
        return r.i16()
    if t == 3:
        return r.i32()
    if t == 4:
        return r.i64()
    if t == 5:
        return r.f32()
    if t == 6:
        return r.f64()
    if t == 7:
        n = r.i32()
        return list(r.take(n))
    if t == 8:
        return r.string()
    if t == 9:
        et = r.u8()
        n = r.i32()
        return [_payload(r, et) for _ in range(n)]
    if t == 10:
        out = {}
        while True:
            tt = r.u8()
            if tt == 0:
                return out
            name = r.string()
            out[name] = _payload(r, tt)
    if t == 11:
        n = r.i32()
        return [r.i32() for _ in range(n)]
    if t == 12:
        n = r.i32()
        return [r.i64() for _ in range(n)]
    raise ValueError('bad tag %d' % t)


def decode_item_bytes(b64):
    raw = gzip.GzipFile(fileobj=io.BytesIO(base64.b64decode(b64))).read()
    r = _R(raw)
    t = r.u8()
    r.string()
    return _payload(r, t)
