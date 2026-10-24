export const TAU = Math.PI * 2;

export const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
export const lerp = (a, b, t) => a + (b - a) * t;

/**
 * All simulation randomness goes through this one source so the headless test
 * can seed it. Without that, every tuning run was a single noisy sample and the
 * sweep results flipped between identical builds — which is worse than no data,
 * because it looks like data.
 */
let _rng = Math.random;
export const rnd = () => _rng();
export function setRng(fn) { _rng = fn || Math.random; }

export const rand = (a, b) => a + _rng() * (b - a);
export const randInt = (a, b) => (a + _rng() * (b - a + 1)) | 0;

/** Shortest signed angle from a to b. */
export function angDiff(a, b) {
  let d = (b - a) % TAU;
  if (d > Math.PI) d -= TAU;
  if (d < -Math.PI) d += TAU;
  return d;
}

/** Deterministic 0..1 from a pair of integers — used for field decoration so a
 *  regenerated chunk is byte-identical to the one it replaces. */
export function hash2(x, y) {
  let h = Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

export function mulberry32(seed) {
  let a = seed | 0;
  return function () {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Uniform spatial hash, rebuilt every frame.
 * Callers own their output buffer, so queries never clobber each other.
 */
export class Grid {
  constructor(cellSize) {
    this.cs = cellSize;
    this.buckets = new Map();
  }
  clear() {
    this.buckets.clear();
  }
  _key(cx, cy) {
    // Safe because every entry in a single frame sits within a few thousand px
    // of the machine — two live cells can never be 65536 cells apart.
    return ((cx & 0xffff) << 16) | (cy & 0xffff);
  }
  insert(i, x, y) {
    const k = this._key(Math.floor(x / this.cs), Math.floor(y / this.cs));
    const b = this.buckets.get(k);
    if (b === undefined) this.buckets.set(k, [i]);
    else b.push(i);
  }
  /** Fills `out` with candidate indices near (x,y); returns how many. */
  query(x, y, r, out) {
    const cs = this.cs;
    const x0 = Math.floor((x - r) / cs), x1 = Math.floor((x + r) / cs);
    const y0 = Math.floor((y - r) / cs), y1 = Math.floor((y + r) / cs);
    const cap = out.length;
    let n = 0;
    for (let cy = y0; cy <= y1; cy++) {
      for (let cx = x0; cx <= x1; cx++) {
        const b = this.buckets.get(this._key(cx, cy));
        if (b === undefined) continue;
        for (let j = 0; j < b.length && n < cap; j++) out[n++] = b[j];
      }
    }
    return n;
  }
}

/** 1,234 -> "1,234" */
export function fmt(n) {
  return Math.floor(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}
