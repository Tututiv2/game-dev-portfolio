"""Draw the application icon from scratch. No image library, no asset files.

The rest of this project draws its own UI rather than shipping bitmaps, and the icon is the
same idea: a few hundred lines of arithmetic beats a dependency, and it means the icon can
be regenerated at any size forever.

Everything is rendered at 4x and box-downsampled, which is what gives smooth edges without
any anti-aliasing machinery -- supersampling *is* the anti-aliasing.

Output is a multi-resolution ``.ico`` with PNG payloads (supported by Windows since Vista),
because Windows picks a different size for the taskbar, alt-tab, the desktop and Explorer's
detail view, and letting it rescale one bitmap looks bad in at least two of those.
"""
from __future__ import annotations

import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SIZES = (16, 24, 32, 48, 64, 128, 256)
SS = 4  # supersampling factor

# Brand colours, matching launcher/ui/theme.py
BG_DARK = (0x10, 0x12, 0x17)
BG_LIGHT = (0x23, 0x27, 0x31)
EMBER = (0xFF, 0x7A, 0x2F)
EMBER_HOT = (0xFF, 0xC9, 0x7A)
EMBER_DEEP = (0xC9, 0x54, 0x1A)


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def rounded_box(x, y, w, h, radius):
    """Signed coverage of a rounded square: 1 inside, 0 outside."""
    cx = min(max(x, radius), w - radius)
    cy = min(max(y, radius), h - radius)
    dx, dy = x - cx, y - cy
    return (dx * dx + dy * dy) <= radius * radius


def flame(u, v):
    """A stylised flame in a unit box. ``u`` across, ``v`` up from the base.

    The width profile must **decrease monotonically** from base to tip. A symmetric
    profile such as ``sin(pi * v)`` is the obvious thing to reach for and it is wrong: it
    is zero at both ends, so it renders a lens -- pointed top *and* bottom -- which reads
    as a leaf, not a flame. Wide bottom, rounded foot, single point at the top.
    """
    if not (0.0 <= v <= 1.0):
        return False
    # Two competing terms: the first swells the body away from the foot, the second pulls
    # it to a point. Their product peaks low and then falls away concavely, which is the
    # teardrop a flame actually is. A single monotonic term gives straight sides -- a cone.
    width = 0.82 * (v + 0.12) ** 0.35 * (1.0 - v) ** 0.95
    if v < 0.15:
        # Round the foot off, rather than chopping it flat against the base.
        k = (0.15 - v) / 0.15
        width *= math.sqrt(max(0.0, 1.0 - k * k))
    # Lean: the tip drifts and curls, so it does not stand up like a spike.
    lean = 0.10 * math.sin(v * math.pi * 0.85) + 0.07 * v ** 2.4
    return abs(u - lean) <= width


def render(size: int) -> bytes:
    """Return ``size`` x ``size`` RGBA bytes."""
    big = size * SS
    acc = [[(0, 0, 0, 0)] * big for _ in range(big)]
    radius = big * 0.22
    glow_cx, glow_cy, glow_r = big * 0.5, big * 0.62, big * 0.52

    for py in range(big):
        row = acc[py]
        for px in range(big):
            if not rounded_box(px + 0.5, py + 0.5, big, big, radius):
                continue

            # Base plate: a soft vertical gradient, lighter at the top.
            t = py / max(1, big - 1)
            colour = lerp(BG_LIGHT, BG_DARK, t ** 0.8)

            # Ember glow rising from the lower middle.
            d = math.hypot(px - glow_cx, (py - glow_cy) * 1.15) / glow_r
            if d < 1.0:
                colour = lerp(colour, EMBER_DEEP, (1.0 - d) ** 2.6 * 0.75)

            # The flame itself, in flame-local coordinates.
            fu = (px - big * 0.5) / (big * 0.5)
            fv = 1.0 - (py / big - 0.10) / 0.80
            if flame(fu, fv):
                # Hotter towards the core and the base.
                core = max(0.0, 1.0 - abs(fu - 0.10 * fv) * 3.2)
                heat = min(1.0, core * 0.85 + (1.0 - fv) * 0.5)
                colour = lerp(EMBER, EMBER_HOT, heat)

            row[px] = (int(colour[0]), int(colour[1]), int(colour[2]), 255)

    # Box-downsample: this is the anti-aliasing.
    out = bytearray()
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for j in range(SS):
                srow = acc[y * SS + j]
                for i in range(SS):
                    pr, pg, pb, pa = srow[x * SS + i]
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            if a:
                out += bytes((r // a, g // a, b // a, a // (SS * SS)))
            else:
                out += b"\x00\x00\x00\x00"
    return bytes(out)


def png(width: int, height: int, rgba: bytes) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    stride = width * 4
    raw = b"".join(b"\x00" + rgba[y * stride:(y + 1) * stride] for y in range(height))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def build_ico(dest: Path) -> Path:
    images = []
    for size in SIZES:
        print(f"  rendering {size}x{size} (at {size * SS}px, downsampled)")
        images.append((size, png(size, size, render(size))))

    header = struct.pack("<HHH", 0, 1, len(images))
    offset = len(header) + 16 * len(images)
    entries, blobs = b"", b""
    for size, blob in images:
        dim = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(blob), offset)
        blobs += blob
        offset += len(blob)

    dest.write_bytes(header + entries + blobs)
    return dest


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "_public" / "icon.ico"
    out.parent.mkdir(parents=True, exist_ok=True)
    build_ico(out)
    print(f"\nwrote {out}  ({out.stat().st_size / 1024:.0f} KB, {len(SIZES)} sizes)")
    # A PNG alongside it, for the README and anywhere an .ico is not wanted.
    (out.parent / "icon.png").write_bytes(png(256, 256, render(256)))
    print(f"wrote {out.parent / 'icon.png'}")
