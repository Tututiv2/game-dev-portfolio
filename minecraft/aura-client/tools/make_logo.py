"""Renders the AURA wordmark used on the custom title screen.

Run once after changing the look: py -3 tools/make_logo.py
The output is a committed asset; the mod never runs Python.

The texture is drawn at the size it appears on a 1080p screen at GUI scale 3
(the in-game code blits it at texWidth / guiScale), because the GUI pipeline
samples nearest-neighbour: a 1:1 blit is crisp, a big downscale shimmers.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 640, 170
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/auracapes/textures/gui/logo.png"
FONT = "C:/Windows/Fonts/seguibl.ttf"  # Segoe UI Black
TEXT = "AURA"
TRACK = 18  # letter spacing in px

font = ImageFont.truetype(FONT, 132)


def layout():
    widths = [font.getbbox(c)[2] - font.getbbox(c)[0] for c in TEXT]
    total = sum(widths) + TRACK * (len(TEXT) - 1)
    x = (W - total) // 2
    out = []
    for c, w in zip(TEXT, widths):
        out.append((x - font.getbbox(c)[0], c))
        x += w + TRACK
    return out


def mask():
    m = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(m)
    top = font.getbbox("A")[1]
    cap = font.getbbox("A")[3] - top
    y = (H - cap) // 2 - top
    for x, c in layout():
        d.text((x, y), c, font=font, fill=255)
    return m


def gradient(c0, c1):
    """Left-to-right colour ramp, violet into cyan - the 'aura'."""
    g = Image.new("RGBA", (W, H))
    px = g.load()
    for x in range(W):
        t = x / (W - 1)
        col = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
        for y in range(H):
            px[x, y] = col + (255,)
    return g


m = mask()
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))

# Two glows: a wide soft halo and a tight rim, both on the violet->cyan ramp.
ramp = gradient((139, 92, 246), (34, 211, 238))
for radius, strength in ((22, 0.55), (7, 0.85)):
    halo = m.filter(ImageFilter.GaussianBlur(radius)).point(lambda v, s=strength: int(v * s))
    layer = ramp.copy()
    layer.putalpha(halo)
    img = Image.alpha_composite(img, layer)

# Letters: white fading to pale lavender at the foot, so they read as lit
# from above rather than as flat stickers.
face = Image.new("RGBA", (W, H))
fp = face.load()
for y in range(H):
    t = y / (H - 1)
    col = (255, int(255 - 24 * t), int(255 - 6 * t), 255)
    for x in range(W):
        fp[x, y] = col
face.putalpha(m)
img = Image.alpha_composite(img, face)

img.save(OUT)
print("wrote", OUT, img.size)
