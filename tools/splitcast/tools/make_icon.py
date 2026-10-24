"""Draw SplitCast's icon and write splitcast.ico. No PIL, no assets.

Every other project in this portfolio draws its art in code rather than shipping
image files, and an icon is the easiest case for it: the whole design is a dark
rounded square with one line forking into two, which is literally what the app
does to a stream.

Antialiasing is analytic rather than supersampled - each pixel's colour comes
from its signed distance to the shape, so a 16x16 icon is as clean as the 256,
and there is no 1024x1024 intermediate to loop over in pure Python.

    py -3 tools/make_icon.py          writes splitcast.ico next to SplitCast.pyw
"""

import math
import os
import struct
import sys
import zlib

SIZES = (256, 64, 48, 32, 16)

BG = (0x15, 0x17, 0x1c)          # the app's own background
ACC = (0x7c, 0x5c, 0xff)         # the accent purple
HOT = (0x3d, 0xdc, 0x84)         # the "music" branch, green like the LIVE state


def _clamp(v, lo=0.0, hi=1.0):
    return lo if v < lo else hi if v > hi else v


def _rounded_rect(x, y, half, radius):
    """Signed distance to a rounded square centred on the origin."""
    dx = abs(x) - (half - radius)
    dy = abs(y) - (half - radius)
    ax, ay = max(dx, 0.0), max(dy, 0.0)
    outside = math.hypot(ax, ay)
    inside = min(max(dx, dy), 0.0)
    return outside + inside - radius


def _segment(px, py, ax, ay, bx, by, width):
    """Signed distance to a thick line segment with round caps."""
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    denom = vx * vx + vy * vy
    t = 0.0 if denom == 0 else _clamp((wx * vx + wy * vy) / denom)
    return math.hypot(wx - t * vx, wy - t * vy) - width


def _over(dst, src, alpha):
    return tuple(int(round(s * alpha + d * (1.0 - alpha))) for d, s in zip(dst, src))


def render(size):
    """RGBA bytes for one square icon."""
    px = bytearray()
    half = 0.5 * size
    # One pixel, in the -1..1 space the shapes are described in.
    unit = 2.0 / size
    edge = unit * 0.8            # AA width: a shade under one pixel

    stem_w = 0.10
    # A stem coming in from the left, splitting into two branches. Deliberately
    # asymmetric in COLOUR, not shape: one branch carries music and one does not,
    # which is the entire product in one glyph.
    junction = (-0.02, 0.0)
    for j in range(size):
        for i in range(size):
            x = (i + 0.5) * unit - 1.0
            y = (j + 0.5) * unit - 1.0

            colour = (0, 0, 0)
            alpha = 0.0

            plate = _rounded_rect(x, y, 0.94, 0.30)
            cov = _clamp(0.5 - plate / edge)
            if cov > 0:
                colour = BG
                alpha = cov

            stem = _segment(x, y, -0.62, 0.0, junction[0], junction[1], stem_w)
            up = _segment(x, y, junction[0], junction[1], 0.58, -0.42, stem_w)
            down = _segment(x, y, junction[0], junction[1], 0.58, 0.42, stem_w)

            for dist, rgb in ((min(stem, up), ACC), (down, HOT)):
                c = _clamp(0.5 - dist / edge)
                if c > 0:
                    colour = _over(colour, rgb, c)
                    alpha = max(alpha, c * cov if cov > 0 else 0.0)

            px += bytes((colour[0], colour[1], colour[2], int(round(alpha * 255))))
    return bytes(px)


def _png(size, rgba):
    """Minimal RGBA PNG. Windows has accepted PNG-compressed icon entries since
    Vista, which is what keeps the 256 from costing 256 KB as a raw bitmap."""
    raw = bytearray()
    stride = size * 4
    for row in range(size):
        raw.append(0)                       # filter: none
        raw += rgba[row * stride:(row + 1) * stride]

    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def build_ico(path, sizes=SIZES):
    images = [(s, _png(s, render(s))) for s in sizes]
    out = bytearray(struct.pack("<HHH", 0, 1, len(images)))
    offset = 6 + 16 * len(images)
    for size, blob in images:
        # 0 in the width/height byte means 256 - the format has one byte for it.
        out += struct.pack("<BBBBHHII", size if size < 256 else 0,
                           size if size < 256 else 0, 0, 0, 1, 32,
                           len(blob), offset)
        offset += len(blob)
    for _, blob in images:
        out += blob
    with open(path, "wb") as fh:
        fh.write(bytes(out))
    return path, [s for s, _ in images], len(out)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "splitcast.ico")
    path, sizes, nbytes = build_ico(target)
    print("wrote {}  sizes={}  {} bytes".format(path, sizes, nbytes))
