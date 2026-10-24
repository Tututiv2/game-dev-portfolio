// Pure scalar helpers. No DOM, no tuning, no state. Kept separate so physics.js reads
// as physics rather than as arithmetic.

export const TAU = Math.PI * 2;

export function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

export function lerp(a, b, t) { return a + (b - a) * t; }

/** Wrap an angle into (-PI, PI]. */
export function wrapPi(a) {
  let x = (a + Math.PI) % TAU;
  if (x < 0) x += TAU;
  return x - Math.PI;
}

export function sign(v) { return v < 0 ? -1 : v > 0 ? 1 : 0; }

/**
 * Does segment (ax,ay)->(bx,by) intersect the AABB {x0,y0,x1,y1}?
 * Slab method. Returns the entry parameter t in [0,1], or -1 for no hit.
 * This is §4.9 — the thing that stops a 100 m/s penguin flying through a snowman.
 */
export function segmentAabb(ax, ay, bx, by, x0, y0, x1, y1) {
  const dx = bx - ax, dy = by - ay;
  let tmin = 0, tmax = 1;

  for (let axis = 0; axis < 2; axis++) {
    const p = axis === 0 ? ax : ay;
    const d = axis === 0 ? dx : dy;
    const lo = axis === 0 ? x0 : y0;
    const hi = axis === 0 ? x1 : y1;
    if (Math.abs(d) < Number.EPSILON) {
      if (p < lo || p > hi) return -1;          // parallel and outside the slab
      continue;
    }
    let t1 = (lo - p) / d;
    let t2 = (hi - p) / d;
    if (t1 > t2) { const s = t1; t1 = t2; t2 = s; }
    if (t1 > tmin) tmin = t1;
    if (t2 < tmax) tmax = t2;
    if (tmin > tmax) return -1;
  }
  return tmin;
}

/**
 * Parameter t in [0,1] at which segment (ax,ay)->(bx,by) crosses the horizontal line
 * y = level travelling DOWNWARD, or -1. Tests the crossing, not `y < level` (§4.9).
 *
 * The start bound is INCLUSIVE and the end bound is EXCLUSIVE, and that asymmetry is
 * load-bearing: a water skip leaves the penguin at exactly y = 0, so a strict `ay > level`
 * misses the very next crossing and the penguin falls through the ocean. It only shows up
 * at the tail of a long skip chain, where the rebound is smaller than one step of gravity.
 */
export function crossDown(ay, by, level) {
  if (ay >= level && by < level) {
    const d = ay - by;
    return d < Number.EPSILON ? 0 : (ay - level) / d;
  }
  return -1;
}

/** As above, travelling UPWARD. */
export function crossUp(ay, by, level) {
  if (ay < level && by >= level) {
    const d = by - ay;
    return d < Number.EPSILON ? 0 : (level - ay) / d;
  }
  return -1;
}
