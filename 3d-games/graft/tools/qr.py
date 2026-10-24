#!/usr/bin/env python3
"""
Minimal QR encoder - byte mode, error-correction level L, versions 1..10.

Enough for any URL up to 271 bytes, which covers every tunnel address we will
ever print. No dependencies; the console renderer uses half-block characters so
a 29x29 code fits in ~15 terminal rows and still scans.

Verified against `npx qrcode` output - see tools/verify_qr.py.
"""
from __future__ import annotations

# --- GF(256) ---------------------------------------------------------------
EXP = [0] * 512
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    EXP[_i] = EXP[_i - 255]


def gf_mul(a: int, b: int) -> int:
    if a == 0 or b == 0:
        return 0
    return EXP[LOG[a] + LOG[b]]


def rs_generator(degree: int) -> list[int]:
    poly = [1]
    for i in range(degree):
        nxt = [0] * (len(poly) + 1)
        for j, coef in enumerate(poly):
            nxt[j] ^= gf_mul(coef, 1)
            nxt[j + 1] ^= gf_mul(coef, EXP[i])
        poly = nxt
    return poly


def rs_encode(data: list[int], ec_len: int) -> list[int]:
    gen = rs_generator(ec_len)
    rem = [0] * ec_len
    for byte in data:
        factor = byte ^ rem[0]
        rem = rem[1:] + [0]
        for i, g in enumerate(gen[1:]):
            rem[i] ^= gf_mul(g, factor)
    return rem


# --- version tables (ECC level L only) -------------------------------------
# version: (ec codewords per block, [(block count, data codewords per block), ...])
BLOCKS_L = {
    1:  (7,  [(1, 19)]),
    2:  (10, [(1, 34)]),
    3:  (15, [(1, 55)]),
    4:  (20, [(1, 80)]),
    5:  (26, [(1, 108)]),
    6:  (18, [(2, 68)]),
    7:  (20, [(2, 78)]),
    8:  (24, [(2, 97)]),
    9:  (30, [(2, 116)]),
    10: (18, [(2, 68), (2, 69)]),
}

ALIGN = {
    1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30],
    6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50],
}


def data_capacity_bytes(version: int) -> int:
    ec_len, groups = BLOCKS_L[version]
    total_data = sum(count * size for count, size in groups)
    header_bits = 4 + (8 if version < 10 else 16)
    return (total_data * 8 - header_bits) // 8


def pick_version(length: int) -> int:
    for version in range(1, 11):
        if length <= data_capacity_bytes(version):
            return version
    raise ValueError("payload too long for version 10 (max %d bytes)"
                     % data_capacity_bytes(10))


# --- bitstream -------------------------------------------------------------
def build_codewords(payload: bytes, version: int) -> list[int]:
    ec_len, groups = BLOCKS_L[version]
    total_data = sum(count * size for count, size in groups)

    bits: list[int] = []

    def put(value: int, width: int) -> None:
        for i in range(width - 1, -1, -1):
            bits.append((value >> i) & 1)

    put(0b0100, 4)                                  # byte mode
    put(len(payload), 8 if version < 10 else 16)    # char count
    for byte in payload:
        put(byte, 8)

    # terminator + byte align
    for _ in range(min(4, total_data * 8 - len(bits))):
        bits.append(0)
    while len(bits) % 8:
        bits.append(0)

    codewords = [int("".join(str(b) for b in bits[i:i + 8]), 2)
                 for i in range(0, len(bits), 8)]
    pad = [0xEC, 0x11]
    while len(codewords) < total_data:
        codewords.append(pad[(len(codewords) - len(bits) // 8) % 2])

    # split into blocks, compute ECC, then interleave
    blocks: list[list[int]] = []
    eccs: list[list[int]] = []
    pos = 0
    for count, size in groups:
        for _ in range(count):
            block = codewords[pos:pos + size]
            pos += size
            blocks.append(block)
            eccs.append(rs_encode(block, ec_len))

    out: list[int] = []
    for i in range(max(len(b) for b in blocks)):
        for block in blocks:
            if i < len(block):
                out.append(block[i])
    for i in range(ec_len):
        for ecc in eccs:
            out.append(ecc[i])
    return out


# --- matrix ----------------------------------------------------------------
def _place_function_patterns(size: int, version: int):
    """Returns (modules, reserved) - reserved marks every non-data cell."""
    modules = [[0] * size for _ in range(size)]
    reserved = [[False] * size for _ in range(size)]

    def finder(row: int, col: int) -> None:
        for dr in range(-1, 8):
            for dc in range(-1, 8):
                r, c = row + dr, col + dc
                if not (0 <= r < size and 0 <= c < size):
                    continue
                inside = 0 <= dr <= 6 and 0 <= dc <= 6
                ring = inside and (dr in (0, 6) or dc in (0, 6))
                core = 2 <= dr <= 4 and 2 <= dc <= 4
                modules[r][c] = 1 if (ring or core) else 0
                reserved[r][c] = True

    finder(0, 0)
    finder(0, size - 7)
    finder(size - 7, 0)

    # timing patterns
    for i in range(size):
        if not reserved[6][i]:
            modules[6][i] = 1 if i % 2 == 0 else 0
            reserved[6][i] = True
        if not reserved[i][6]:
            modules[i][6] = 1 if i % 2 == 0 else 0
            reserved[i][6] = True

    # alignment patterns
    centers = ALIGN[version]
    for r in centers:
        for c in centers:
            if (r < 8 and c < 8) or (r < 8 and c > size - 9) or (r > size - 9 and c < 8):
                continue
            for dr in range(-2, 3):
                for dc in range(-2, 3):
                    modules[r + dr][c + dc] = 1 if (abs(dr) == 2 or abs(dc) == 2
                                                    or (dr == 0 and dc == 0)) else 0
                    reserved[r + dr][c + dc] = True

    # dark module
    modules[size - 8][8] = 1
    reserved[size - 8][8] = True

    # format-info areas (values written later)
    for i in range(9):
        if not reserved[8][i]:
            reserved[8][i] = True
        if not reserved[i][8]:
            reserved[i][8] = True
    for i in range(8):
        reserved[8][size - 1 - i] = True
        reserved[size - 1 - i][8] = True

    # version info blocks (v7+)
    if version >= 7:
        for i in range(6):
            for j in range(3):
                reserved[size - 11 + j][i] = True
                reserved[i][size - 11 + j] = True

    return modules, reserved


def _place_data(modules, reserved, codewords, size) -> None:
    bits = []
    for cw in codewords:
        for i in range(7, -1, -1):
            bits.append((cw >> i) & 1)
    idx = 0
    upward = True
    col = size - 1
    while col > 0:
        if col == 6:      # skip the vertical timing column
            col -= 1
        rows = range(size - 1, -1, -1) if upward else range(size)
        for row in rows:
            for c in (col, col - 1):
                if reserved[row][c]:
                    continue
                modules[row][c] = bits[idx] if idx < len(bits) else 0
                idx += 1
        upward = not upward
        col -= 2


def _mask_fn(mask: int):
    return [
        lambda r, c: (r + c) % 2 == 0,
        lambda r, c: r % 2 == 0,
        lambda r, c: c % 3 == 0,
        lambda r, c: (r + c) % 3 == 0,
        lambda r, c: (r // 2 + c // 3) % 2 == 0,
        lambda r, c: (r * c) % 2 + (r * c) % 3 == 0,
        lambda r, c: ((r * c) % 2 + (r * c) % 3) % 2 == 0,
        lambda r, c: ((r + c) % 2 + (r * c) % 3) % 2 == 0,
    ][mask]


def _format_bits(mask: int) -> int:
    # ECC level L = 01
    data = (0b01 << 3) | mask
    rem = data
    for _ in range(10):
        rem = (rem << 1)
        if rem & 0x400:
            rem ^= 0x537
    return ((data << 10) | rem) ^ 0x5412


def _version_bits(version: int) -> int:
    rem = version
    for _ in range(12):
        rem <<= 1
        if rem & 0x1000:
            rem ^= 0x1F25
    return (version << 12) | rem


def _apply_format(modules, size, mask) -> None:
    fmt = _format_bits(mask)
    for i in range(15):
        bit = (fmt >> i) & 1
        # vertical strip beside the top-left finder
        if i < 6:
            modules[i][8] = bit
        elif i == 6:
            modules[7][8] = bit
        elif i == 7:
            modules[8][8] = bit
        elif i == 8:
            modules[8][7] = bit
        else:
            modules[8][14 - i] = bit
        # the duplicate copy
        if i < 8:
            modules[8][size - 1 - i] = bit
        else:
            modules[size - 15 + i][8] = bit


def _apply_version(modules, size, version) -> None:
    if version < 7:
        return
    bits = _version_bits(version)
    for i in range(18):
        bit = (bits >> i) & 1
        r, c = i // 3, i % 3
        modules[size - 11 + c][r] = bit
        modules[r][size - 11 + c] = bit


def _penalty(modules, size) -> int:
    score = 0
    # rule 1 - runs of 5+
    for line in list(modules) + [list(col) for col in zip(*modules)]:
        run, prev = 1, line[0]
        for cell in line[1:]:
            if cell == prev:
                run += 1
            else:
                if run >= 5:
                    score += 3 + (run - 5)
                run, prev = 1, cell
        if run >= 5:
            score += 3 + (run - 5)
    # rule 2 - 2x2 blocks
    for r in range(size - 1):
        for c in range(size - 1):
            q = modules[r][c] + modules[r][c + 1] + modules[r + 1][c] + modules[r + 1][c + 1]
            if q in (0, 4):
                score += 3
    # rule 3 - finder-like patterns
    pat_a = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0]
    pat_b = list(reversed(pat_a))
    for line in list(modules) + [list(col) for col in zip(*modules)]:
        for i in range(size - 10):
            window = list(line[i:i + 11])
            if window == pat_a or window == pat_b:
                score += 40
    # rule 4 - dark/light balance.
    # k = |ceil(pct/5) - 10|, NOT floor(|pct-50|/5). The two disagree whenever
    # the dark share sits just past a 5% step (52.48% scores 10 under the first
    # and 0 under the second), and that alone picked the wrong mask for v2.
    dark = sum(sum(row) for row in modules)
    pct = dark * 100.0 / (size * size)
    score += 10 * abs(-(-pct // 5) - 10)
    return int(score)


def encode(text: str) -> list[list[int]]:
    """Return the QR matrix as rows of 0/1."""
    payload = text.encode("utf-8")
    version = pick_version(len(payload))
    size = 17 + 4 * version
    codewords = build_codewords(payload, version)

    base, reserved = _place_function_patterns(size, version)
    _place_data(base, reserved, codewords, size)

    best = None
    for mask in range(8):
        cand = [row[:] for row in base]
        fn = _mask_fn(mask)
        for r in range(size):
            for c in range(size):
                if not reserved[r][c] and fn(r, c):
                    cand[r][c] ^= 1
        _apply_format(cand, size, mask)
        _apply_version(cand, size, version)
        pen = _penalty(cand, size)
        if best is None or pen < best[0]:
            best = (pen, cand)
    return best[1]


def to_ascii(matrix: list[list[int]], quiet: int = 2) -> str:
    """
    Fallback renderer using only ASCII.

    Windows consoles still default to cp1252, which cannot encode the block
    characters at all - and a QR that raises UnicodeEncodeError is a QR nobody
    can scan. Two characters per module keeps the aspect ratio square.

    Light modules are drawn and dark ones left blank, because a terminal is
    dark: the drawn cells have to be the LIGHT half of the code.
    """
    size = len(matrix)
    width = size + quiet * 2
    rows = [[0] * width for _ in range(quiet)]
    for row in matrix:
        rows.append([0] * quiet + list(row) + [0] * quiet)
    rows.extend([[0] * width for _ in range(quiet)])
    out = []
    for row in rows:
        out.append("".join("  " if cell else "##" for cell in row))
    return "\n".join(out)


def to_text(matrix: list[list[int]], quiet: int = 2) -> str:
    """Render with half-block chars: two matrix rows per terminal row."""
    size = len(matrix)
    width = size + quiet * 2
    rows = [[0] * width for _ in range(quiet)]
    for row in matrix:
        rows.append([0] * quiet + list(row) + [0] * quiet)
    while len(rows) % 2 or len(rows) < size + quiet * 2:
        rows.append([0] * width)

    out = []
    for i in range(0, len(rows), 2):
        top, bot = rows[i], rows[i + 1] if i + 1 < len(rows) else [0] * width
        line = []
        for c in range(width):
            t, b = top[c], bot[c]
            # dark module = light terminal cell (scanners want dark-on-light,
            # and most terminals are dark, so invert)
            if t and b:
                line.append(" ")
            elif t and not b:
                line.append("▄")
            elif b and not t:
                line.append("▀")
            else:
                line.append("█")
        out.append("".join(line))
    return "\n".join(out)


if __name__ == "__main__":
    import sys
    print(to_text(encode(sys.argv[1] if len(sys.argv) > 1 else "https://example.com")))
