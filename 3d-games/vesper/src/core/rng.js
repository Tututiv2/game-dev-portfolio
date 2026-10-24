/* ============================================================================
   DETERMINISM IS THE SAVE FORMAT.

   Everything in VESPER is a pure function of (seed, index, gameTime). A world
   is not stored; it is recomputed. That is why a save file is tiny and why the
   same seed gives the same universe forever.

   THE RULE THAT BREAKS SAVES IF YOU IGNORE IT:
   adding or reordering an rng() draw shifts every later draw in that stream.
   Append new draws at the END of a stream, or open a fresh one with a new salt
   (`mulberry32(seed ^ 0xSOMETHING)`). Never insert in the middle.
   ========================================================================= */

/** classic mulberry32 — a closure, so never call this in a hot loop */
export function mulberry32(a) {
  return function () {
    a |= 0; a = a + 0x6D2B79F5 | 0;
    let t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

export function hashStr(s) {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

/** allocation-free integer lattice hash -> [0,1). Runs millions of times. */
export function h3(x, y, z, s) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) +
           Math.imul(z | 0, 2147483647) + Math.imul(s | 0, 1013904223)) | 0;
  h = Math.imul(h ^ h >>> 13, 1274126177);
  return ((h ^ h >>> 16) >>> 0) / 4294967296;
}

/** a small helper bundle so callers read like prose instead of arithmetic */
export function stream(seed) {
  const r = mulberry32(seed >>> 0);
  return {
    f: r,
    range: (a, b) => a + (b - a) * r(),
    int: (a, b) => a + ((r() * (b - a + 1)) | 0),
    chance: (p) => r() < p,
    pick: (arr) => arr[(r() * arr.length) | 0],
    /** weighted pick over a parallel array of weights */
    weighted: (arr, w) => {
      let tot = 0; for (let i = 0; i < w.length; i++) tot += w[i];
      let roll = r() * tot;
      for (let i = 0; i < arr.length; i++) { roll -= w[i]; if (roll <= 0) return arr[i]; }
      return arr[arr.length - 1];
    },
    /** a point on the unit sphere, uniformly distributed */
    sphere: () => {
      const z = r() * 2 - 1, a = r() * Math.PI * 2, s = Math.sqrt(1 - z * z);
      return [Math.cos(a) * s, z, Math.sin(a) * s];
    },
  };
}
