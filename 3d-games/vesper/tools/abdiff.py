"""Diff two PNGs and report how much actually moved. Used by tests/uniform_ab.mjs."""
import sys
from PIL import Image, ImageChops
A = Image.open(sys.argv[1]).convert('RGB')
B = Image.open(sys.argv[2]).convert('RGB')
if A.size != B.size:
    print("  size mismatch %s vs %s" % (A.size, B.size)); sys.exit(1)
d = ImageChops.difference(A, B)
bands = d.split(); m = bands[0]
for c in bands[1:]:
    m = ImageChops.lighter(m, c)
h = m.histogram(); tot = A.size[0] * A.size[1]; ch = tot - h[0]
if not ch:
    print("    IDENTICAL — %d pixels, not one changed" % tot)
else:
    worst = max(i for i, c in enumerate(h) if c)
    mean = sum(i * c for i, c in enumerate(h)) / ch
    over2 = sum(c for i, c in enumerate(h) if i > 2)
    print("    %d/%d pixels differ (%.3f%%), mean delta %.2f, worst %d; "
          "%d px (%.3f%%) differ by more than 2/255"
          % (ch, tot, 100.0 * ch / tot, mean, worst, over2, 100.0 * over2 / tot))
