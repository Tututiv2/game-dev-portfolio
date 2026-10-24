"""Independent brute-force verification of the Town review-slice chunk mask.

Reproduces the union rule from docs/floor-01-layout.json:first_review_slice
(town_core rectangle + 3 corridor buffers, deduplicated) using nothing but
plain arithmetic, so the exact chunk count/bounds can be checked before any
generation code depends on them. Run with: python scripts/verify_chunk_mask.py
"""
import math

CHUNK = 16

TOWN_CORE_BLOCK_BOUNDS = (-640, 3904, 639, 5039)  # (minX, minZ, maxX, maxZ) inclusive

CORRIDORS = {
    "north_spine_corridor": ((0, 4000), (-80, 3160), 128),
    "northwest_corridor": ((-380, 4090), (-840, 3520), 128),
    "northeast_corridor": ((380, 4090), (960, 3380), 128),
}

EXPECTED_TOWN_CORE_COUNT = 5680
# Corrected 2026-07-23: the union rule below (unchanged radius/segments) mechanically produces
# 7,912 chunks, not the originally-asserted 6,290; docs were corrected to match. See
# docs/ARCHITECTURE-DECISION.md #5 and docs/floor-01-layout.json:known_corrections.
CLAIMED_TOTAL = 7912
CLAIMED_X_BOUNDS = (-60, 67)
CLAIMED_Z_BOUNDS = (190, 314)


def block_range_to_chunk_range(lo, hi):
    return math.floor(lo / CHUNK), math.floor(hi / CHUNK)


def chunk_center(cx, cz):
    return cx * CHUNK + CHUNK / 2, cz * CHUNK + CHUNK / 2


def point_segment_distance(px, pz, ax, az, bx, bz):
    dx, dz = bx - ax, bz - az
    seglen2 = dx * dx + dz * dz
    t = 0.0 if seglen2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (pz - az) * dz) / seglen2))
    cx, cz = ax + t * dx, az + t * dz
    return math.hypot(px - cx, pz - cz)


def compile_mask(search_pad=400):
    min_x, min_z, max_x, max_z = TOWN_CORE_BLOCK_BOUNDS
    town_core_x = block_range_to_chunk_range(min_x, max_x)
    town_core_z = block_range_to_chunk_range(min_z, max_z)

    chunks = set()
    for cx in range(town_core_x[0], town_core_x[1] + 1):
        for cz in range(town_core_z[0], town_core_z[1] + 1):
            chunks.add((cx, cz))
    town_core_count = len(chunks)

    all_x = [p for seg in CORRIDORS.values() for p in (seg[0][0], seg[1][0])]
    all_z = [p for seg in CORRIDORS.values() for p in (seg[0][1], seg[1][1])]
    search_cx = block_range_to_chunk_range(min(all_x) - search_pad, max(all_x) + search_pad)
    search_cz = block_range_to_chunk_range(min(all_z) - search_pad, max(all_z) + search_pad)

    per_corridor_new = {}
    for name, ((ax, az), (bx, bz), buf) in CORRIDORS.items():
        added = 0
        for cx in range(search_cx[0], search_cx[1] + 1):
            for cz in range(search_cz[0], search_cz[1] + 1):
                pcx, pcz = chunk_center(cx, cz)
                if point_segment_distance(pcx, pcz, ax, az, bx, bz) <= buf:
                    if (cx, cz) not in chunks:
                        chunks.add((cx, cz))
                        added += 1
        per_corridor_new[name] = added

    return chunks, town_core_count, per_corridor_new


def main():
    chunks, town_core_count, per_corridor_new = compile_mask()
    xs = [c[0] for c in chunks]
    zs = [c[1] for c in chunks]
    total = len(chunks)

    print(f"town_core: {town_core_count} chunks (spec: {EXPECTED_TOWN_CORE_COUNT})")
    for name, added in per_corridor_new.items():
        print(f"  {name}: +{added} net-new chunks")
    print(f"TOTAL deduplicated: {total} (doc claims {CLAIMED_TOTAL})")
    print(f"X bounds: {min(xs)}..{max(xs)} (doc claims {CLAIMED_X_BOUNDS[0]}..{CLAIMED_X_BOUNDS[1]})")
    print(f"Z bounds: {min(zs)}..{max(zs)} (doc claims {CLAIMED_Z_BOUNDS[0]}..{CLAIMED_Z_BOUNDS[1]})")

    assert town_core_count == EXPECTED_TOWN_CORE_COUNT
    bounds_match = (min(xs), max(xs)) == CLAIMED_X_BOUNDS and (min(zs), max(zs)) == CLAIMED_Z_BOUNDS
    count_match = total == CLAIMED_TOTAL

    print()
    if bounds_match and count_match:
        print("PASS: mask compiler reproduces the exact locked mask (7,912 chunks).")
    elif bounds_match and not count_match:
        print("DISCREPANCY: bounds match exactly but the total chunk count does not.")
        print("See docs/ARCHITECTURE-DECISION.md #5 - this was previously flagged and resolved once already.")
    else:
        print("DISCREPANCY: bounds do not match either — investigate before trusting this script.")

    assert count_match, f"mask total regressed: got {total}, expected {CLAIMED_TOTAL}"
    assert bounds_match, "mask bounds regressed"


if __name__ == "__main__":
    main()
