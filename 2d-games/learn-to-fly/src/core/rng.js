// mulberry32 — small, fast, and repeatable across reloads and across Node/browser, which is
// what test 9 (determinism) needs and what makes ghost replays possible at all (§14).
//
// Nothing in sim/ calls Math.random. If a seeded stream is needed there it is passed in.

export function mulberry32(seed) {
  let a = seed >>> 0;
  return function next() {
    a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Convenience wrapper with the shapes the game and the tests actually ask for. */
export function makeRng(seed) {
  const next = mulberry32(seed);
  return {
    next,
    float(lo, hi) { return lo + next() * (hi - lo); },
    int(lo, hi) { return Math.floor(lo + next() * (hi - lo + 1)); },
    pick(arr) { return arr[Math.floor(next() * arr.length)]; },
    bool(p = 0.5) { return next() < p; },
  };
}

/** Cheap string → 32-bit seed, so "day 7" can be a stable seed for flavour text. */
export function hashSeed(str) {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}
