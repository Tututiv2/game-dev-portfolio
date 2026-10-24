/** Deterministic 32-bit mix (no SeededRng): purely for seeds and IDs. */
export function mix4(a: number, b: number, c: number, d: number): number {
  let x = (a ^ b ^ c ^ d) >>> 0;
  x = Math.imul(x ^ (x >>> 16), 0x7feb352d) >>> 0;
  x = Math.imul(x ^ (x >>> 15), 0x846ca68b) >>> 0;
  return (x ^ (x >>> 16)) >>> 0;
}

export function surfaceVisualSeed(s: { seed: number; universe: { systemSeed: number; bodyIndex: number } }): number {
  return mix4(s.seed, s.universe.systemSeed, s.universe.bodyIndex, 0x5f3c2a19);
}
