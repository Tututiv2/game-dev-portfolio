#!/usr/bin/env python3
"""
Generate GRAFT's PWA icon set. Real PNGs, written by hand with zlib + struct.

No Pillow, no asset on disk: the mark is drawn from maths, which is the same
rule the game follows -- nothing here needs downloading before it works.

The mark is the game in one shape: a small planet, and a sprout grafted onto
it. One high-resolution master is rendered and every shipped size is box
filtered down from it, so one expensive pass covers them all.

Run: py -3 tools/mkicons.py
"""
from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "icons"

MASTER = 1024          # master render size for the plate icons
MASK_MASTER = 640      # maskable master (mark is smaller, so it needs less)
SS = 2                 # supersample factor

# palette -- the game's own, from app/style.css
SKY_TOP = (0x10, 0x1d, 0x33)
SKY_BOT = (0x06, 0x09, 0x12)
ROCK_LIT = (0x2e, 0x5f, 0x7e)
ROCK_DARK = (0x08, 0x14, 0x22)
MOSS = (0x3d, 0x93, 0x77)
GROW = (0x7f, 0xe8, 0xb0)
GROW_TIP = (0xd4, 0xff, 0xe6)
NEON = (0xff, 0x4f, 0xd8)

# composition, in units of half the icon (origin at the centre, +y up)
PLANET_C = (0.0, -0.40)
PLANET_R = 0.80
LIGHT = (-0.46, 0.62, 0.64)

STEM_A = (0.0, 0.33)
STEM_B = (0.035, 0.78)
STEM_W = 0.058
# A leaf is placed by where it JOINS the stem, not by its own centre: floating
# leaves were the first version's whole problem, and a centre is exactly the
# number that does not say whether they touch.
LEAF_L = (-0.173, 0.609, 0.235, 0.092, 2.618)   # cx, cy, rx, ry, angle
LEAF_R = (0.200, 0.766, 0.245, 0.095, 0.663)
SPARK = (0.56, 0.32, 0.125)


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return (lerp(c1[0], c2[0], t), lerp(c1[1], c2[1], t), lerp(c1[2], c2[2], t))


def over(dst, src, a):
    return (lerp(dst[0], src[0], a), lerp(dst[1], src[1], a), lerp(dst[2], src[2], a))


def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    dd = vx * vx + vy * vy
    t = 0.0 if dd <= 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / dd))
    return math.hypot(wx - vx * t, wy - vy * t), t


def ellipse_dist(px, py, cx, cy, rx, ry, ang):
    """Cheap signed distance to a rotated ellipse. Exact enough for an icon."""
    dx, dy = px - cx, py - cy
    c, s = math.cos(ang), math.sin(ang)
    ux, uy = dx * c + dy * s, -dx * s + dy * c
    k = math.hypot(ux / rx, uy / ry)
    return (k - 1.0) * min(rx, ry)


def render(size: int, scale: float, bleed: bool):
    """Render one square icon at `size` px. Returns rows of RGB bytes."""
    n = size * SS
    half = n / 2.0
    corner = 0.235                      # rounded-square radius, in half-units
    inv = 1.0 / (SS * SS)

    # px per half-unit, used to turn a normalised distance into a coverage
    ppu = half

    pcx, pcy = PLANET_C[0] * scale, PLANET_C[1] * scale
    pr = PLANET_R * scale
    ax, ay = STEM_A[0] * scale, STEM_A[1] * scale
    bx, by = STEM_B[0] * scale, STEM_B[1] * scale
    sw = STEM_W * scale
    llc = (LEAF_L[0] * scale, LEAF_L[1] * scale, LEAF_L[2] * scale, LEAF_L[3] * scale, LEAF_L[4])
    lrc = (LEAF_R[0] * scale, LEAF_R[1] * scale, LEAF_R[2] * scale, LEAF_R[3] * scale, LEAF_R[4])
    spx, spy, spr = SPARK[0] * scale, SPARK[1] * scale, SPARK[2] * scale

    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            ar = ag = ab = 0.0
            for sy in range(SS):
                yy = -(((py * SS + sy) + 0.5 - half) / half)      # +y up, -1..1
                for sx in range(SS):
                    xx = ((px * SS + sx) + 0.5 - half) / half

                    # ---- sky plate -------------------------------------
                    col = mix(SKY_TOP, SKY_BOT, (1.0 - yy) * 0.5)

                    # ---- planet ----------------------------------------
                    dx, dy = xx - pcx, yy - pcy
                    d = math.hypot(dx, dy)
                    if d < pr + 0.10:
                        # soft atmosphere just outside the limb
                        if d > pr:
                            g = max(0.0, 1.0 - (d - pr) / 0.10) ** 2
                            col = over(col, GROW, g * 0.16)
                        cov = min(1.0, max(0.0, (pr - d) * ppu + 0.5))
                        if cov > 0.0:
                            k = min(1.0, d / pr)
                            nz = math.sqrt(max(0.0, 1.0 - k * k))
                            nx, ny = dx / pr, dy / pr
                            lam = nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]
                            shade = 0.10 + 0.90 * max(0.0, lam) ** 0.85
                            body = mix(ROCK_DARK, ROCK_LIT, shade)
                            # the part facing the sun is the part that grows
                            body = over(body, MOSS, max(0.0, lam - 0.55) * 1.1)
                            # a lit rim, so the limb reads at 32 px
                            rim = max(0.0, (k - 0.86) / 0.14) ** 2 * max(0.0, lam)
                            body = over(body, GROW, rim * 0.55)
                            col = over(col, body, cov)

                    # ---- sprout ----------------------------------------
                    if -0.55 * scale < xx < 0.60 * scale and yy > 0.20 * scale:
                        ds, t = seg_dist(xx, yy, ax, ay, bx, by)
                        w = sw * (1.0 - 0.45 * t)                # tapers upward
                        a = min(1.0, max(0.0, (w - ds) * ppu + 0.5))
                        if a > 0.0:
                            col = over(col, mix(GROW, GROW_TIP, t), a)
                        for (cx, cy, rx, ry, ang), tip in ((llc, 0.0), (lrc, 1.0)):
                            de = ellipse_dist(xx, yy, cx, cy, rx, ry, ang)
                            a = min(1.0, max(0.0, -de * ppu + 0.5))
                            if a > 0.0:
                                shade = 0.25 + 0.75 * min(1.0, max(0.0, (yy - cy) / max(ry, 1e-6) * 0.5 + 0.5))
                                col = over(col, mix(GROW, GROW_TIP, shade * 0.55 + tip * 0.1), a)

                    # ---- spark -----------------------------------------
                    fx, fy = abs(xx - spx) / spr, abs(yy - spy) / spr
                    if fx < 1.0 and fy < 1.0:
                        f = math.sqrt(fx) + math.sqrt(fy)
                        edge = 2.0 / max(spr * ppu, 1.0)
                        a = min(1.0, max(0.0, (1.0 - f) / edge))
                        if a > 0.0:
                            col = over(col, NEON, a * 0.92)

                    # ---- plate mask ------------------------------------
                    if bleed:
                        inside = 1.0
                    else:
                        qx, qy = abs(xx) - (1.0 - corner), abs(yy) - (1.0 - corner)
                        if qx > 0 and qy > 0:
                            dd = math.hypot(qx, qy) - corner
                        else:
                            dd = max(qx, qy) - corner
                        inside = min(1.0, max(0.0, 0.5 - dd * ppu))

                    ar += col[0] * inside
                    ag += col[1] * inside
                    ab += col[2] * inside

            row += bytes((int(max(0, min(255, ar * inv))),
                          int(max(0, min(255, ag * inv))),
                          int(max(0, min(255, ab * inv)))))
        rows.append(bytes(row))
    return rows


def downscale(rows, src: int, dst: int):
    """Area-average box filter; handles non-integer ratios."""
    if src == dst:
        return rows
    out = []
    scale = src / dst
    for y in range(dst):
        y0, y1 = y * scale, (y + 1) * scale
        iy0, iy1 = int(y0), min(src, int(math.ceil(y1)))
        row = bytearray()
        for x in range(dst):
            x0, x1 = x * scale, (x + 1) * scale
            ix0, ix1 = int(x0), min(src, int(math.ceil(x1)))
            sr = sg = sb = wsum = 0.0
            for yy in range(iy0, iy1):
                wy = min(y1, yy + 1) - max(y0, yy)
                if wy <= 0:
                    continue
                srow = rows[yy]
                for xx in range(ix0, ix1):
                    wx = min(x1, xx + 1) - max(x0, xx)
                    if wx <= 0:
                        continue
                    w = wx * wy
                    off = xx * 3
                    sr += srow[off] * w
                    sg += srow[off + 1] * w
                    sb += srow[off + 2] * w
                    wsum += w
            if wsum <= 0:
                wsum = 1.0
            row += bytes((int(sr / wsum + 0.5), int(sg / wsum + 0.5), int(sb / wsum + 0.5)))
        out.append(bytes(row))
    return out


def write_png(path: Path, rows) -> int:
    height = len(rows)
    width = len(rows[0]) // 3

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    raw = b"".join(b"\x00" + row for row in rows)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    path.write_bytes(png)
    return len(png)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)

    print("rendering plate master %dx%d (ss=%d)..." % (MASTER, MASTER, SS))
    master = render(MASTER, scale=0.94, bleed=False)
    for size, name in ((512, "icon-512.png"), (192, "icon-192.png"),
                       (180, "apple-touch-icon.png"), (32, "favicon-32.png")):
        n = write_png(OUT / name, downscale(master, MASTER, size))
        print("  %-22s %dx%d  %d bytes" % (name, size, size, n))

    # Maskable icons are cropped to a circle by the launcher, so the mark has
    # to live inside the middle 80% and the plate has to reach every corner.
    print("rendering maskable master %dx%d..." % (MASK_MASTER, MASK_MASTER))
    mask = render(MASK_MASTER, scale=0.62, bleed=True)
    for size, name in ((512, "maskable-512.png"), (192, "maskable-192.png")):
        n = write_png(OUT / name, downscale(mask, MASK_MASTER, size))
        print("  %-22s %dx%d  %d bytes" % (name, size, size, n))

    print("done -> %s" % OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
