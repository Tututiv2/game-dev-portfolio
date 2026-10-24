"""Compare two folders of PNGs, pixel for pixel.

Written for one specific question: the sky-light night weight was lowered, and
the claim is that the DAY side of the approved orbital gallery cannot move
because the day weight was held at exactly 0.35*uSkyAmt. A claim like that is
either true to the pixel or it is not, and "it looks the same" cannot tell you
which — so this reports the fraction of pixels that changed at all, the mean
absolute change over the ones that did, and the worst single pixel.

  python tools/imgdiff.py shots/_approved_backup shots

Prints one row per file that exists in both.
"""
import sys, os
from PIL import Image, ImageChops

a_dir, b_dir = sys.argv[1], sys.argv[2]
names = sorted(n for n in os.listdir(a_dir) if n.lower().endswith('.png'))

print()
print("  %-30s %10s %10s %8s %10s" % ("file", "px changed", "% changed", "mean d", "worst d"))
for n in names:
    pa, pb = os.path.join(a_dir, n), os.path.join(b_dir, n)
    if not os.path.exists(pb):
        print("  %-30s   (missing in %s)" % (n, b_dir)); continue
    A = Image.open(pa).convert('RGB')
    B = Image.open(pb).convert('RGB')
    if A.size != B.size:
        print("  %-30s   size %s vs %s" % (n, A.size, B.size)); continue
    d = ImageChops.difference(A, B)
    # per-pixel max over channels, so a change in any one channel counts
    bands = d.split()
    m = bands[0]
    for c in bands[1:]:
        m = ImageChops.lighter(m, c)
    hist = m.histogram()
    total = A.size[0] * A.size[1]
    changed = total - hist[0]
    ssum = sum(i * c for i, c in enumerate(hist))
    worst = max(i for i, c in enumerate(hist) if c) if changed else 0
    mean_over_changed = (ssum / changed) if changed else 0.0
    print("  %-30s %10d %9.2f%% %8.2f %10d"
          % (n, changed, 100.0 * changed / total, mean_over_changed, worst))
print()
