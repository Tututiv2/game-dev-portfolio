/**
 * Deterministic PRNG (mulberry32). Serialize `state` for saves.
 */
export class SeededRng {
  state: number;

  constructor(seed: number) {
    this.state = Math.floor(seed) >>> 0;
  }

  /** Unit interval [0, 1) */
  nextFloat(): number {
    return this.nextUint32() / 0x100000000;
  }

  nextUint32(): number {
    // Keep state in u32 so saves round-trip through JSON (no int53 drift).
    this.state = (this.state + 0x6d2b79f5) >>> 0;
    let t = this.state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return (t ^ (t >>> 14)) >>> 0;
  }

  nextInt(below: number): number {
    if (below <= 0) return 0;
    return Math.floor(this.nextFloat() * below);
  }
}
