import { describe, it, expect } from "vitest";
import {
  buildPickableStarDirections,
  pickStarIndexAlongLook,
  pickStarIndexBestLook,
  seededStarUnitDirection,
} from "../src/planet/skydomeSeed";

describe("skydome pick determinism", () => {
  it("buildPickableStarDirections is stable for a fixed seed", () => {
    const a = buildPickableStarDirections(99_123);
    const b = buildPickableStarDirections(99_123);
    expect(a.length).toBe(b.length);
    for (let i = 0; i < a.length; i++) {
      expect(a[i]).toBe(b[i]);
    }
  });

  it("seededStarUnitDirection matches pick for a few indices", () => {
    const seed = 7;
    const dirs = buildPickableStarDirections(seed);
    for (const i of [0, 1, 9001, 15_000]) {
      const d0 = dirs[i * 3]!;
      const d1 = dirs[i * 3 + 1]!;
      const d2 = dirs[i * 3 + 2]!;
      const c =
        i < 9_000
          ? seededStarUnitDirection(seed, i, 0x11a0)
          : seededStarUnitDirection(seed, i, 0x22b0);
      expect(c.x).toBeCloseTo(d0, 5);
      expect(c.y).toBeCloseTo(d1, 5);
      expect(c.z).toBeCloseTo(d2, 5);
    }
  });

  it("pickStarIndexAlongLook returns the look direction when aligned", () => {
    const seed = 42;
    const dirs = buildPickableStarDirections(seed);
    const j = 1234;
    const lx = dirs[j * 3]!;
    const ly = dirs[j * 3 + 1]!;
    const lz = dirs[j * 3 + 2]!;
    const p = pickStarIndexAlongLook(lx, ly, lz, dirs, 0.01);
    expect(p).not.toBeNull();
    expect(p!.index).toBe(j);
  });

  it("pickStarIndexBestLook always returns the same index as max-dot search (never null)", () => {
    const seed = 12;
    const dirs = buildPickableStarDirections(seed);
    const b = pickStarIndexBestLook(0.2, -0.7, 0.4, dirs);
    expect(b.dot).toBeGreaterThan(-1.01);
    const p = pickStarIndexAlongLook(0.2, -0.7, 0.4, dirs, Math.PI);
    expect(p).not.toBeNull();
    expect(p!.index).toBe(b.index);
  });
});
