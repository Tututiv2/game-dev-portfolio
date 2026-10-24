import { describe, it, expect } from "vitest";
import {
  MAX_CHART_LY_STEP_PER_FRAME,
  METERS_PER_LY,
  STAR_SHELL_R_MAX_LY,
  STAR_SHELL_R_MIN_LY,
  addGposStepTowardPointLy,
  addWorldDisplacementMetersToGpos,
  DEEP_GPOS_TRAVEL_SCALE,
  getStarCatalogPositionLy,
  initialGalacticPositionLy,
  lightYearsDistance3D,
} from "../src/sim/universe/galacticSpace";
import { getStarDistanceToPlayerLy } from "../src/planet/starCatalog";
import { pickableStarUnitDirection } from "../src/planet/skydomeSeed";

describe("galactic chart (shell stars, O(1) per star)", () => {
  it("initialGalacticPositionLy is stable", () => {
    const a = initialGalacticPositionLy(3, 5, 99_123);
    const b = initialGalacticPositionLy(3, 5, 99_123);
    expect(a).toEqual(b);
  });

  it("shell star position is deterministic and on the anchor + w*R ray", () => {
    const anchor = initialGalacticPositionLy(2, 3, 9_001);
    const sk = 11;
    const seed = 42;
    const idx = 4_201;
    const w = pickableStarUnitDirection(sk, idx);
    const p = getStarCatalogPositionLy(idx, sk, seed, anchor, w);
    const q = getStarCatalogPositionLy(idx, sk, seed, anchor, w);
    expect(p).toEqual(q);
    const dx = p.x - anchor.x;
    const dy = p.y - anchor.y;
    const dz = p.z - anchor.z;
    const r = Math.hypot(dx, dy, dz);
    expect(r).toBeGreaterThanOrEqual(STAR_SHELL_R_MIN_LY - 1e-9);
    expect(r).toBeLessThanOrEqual(STAR_SHELL_R_MAX_LY + 1e-9);
    const wl = Math.hypot(w.x, w.y, w.z) || 1e-9;
    expect(dx / r).toBeCloseTo(w.x / wl, 5);
    expect(dy / r).toBeCloseTo(w.y / wl, 5);
    expect(dz / r).toBeCloseTo(w.z / wl, 5);
  });

  it("getStarDistanceToPlayerLy matches shell geometry at start (gpos = anchor)", () => {
    const anchor = initialGalacticPositionLy(1, 2, 77_777);
    const sk = 3;
    const gseed = 100;
    const idx = 42;
    const w = pickableStarUnitDirection(sk, idx);
    const star = getStarCatalogPositionLy(idx, sk, gseed, anchor, w);
    const d = getStarDistanceToPlayerLy(idx, sk, gseed, anchor, anchor);
    expect(d).toBeCloseTo(lightYearsDistance3D(star, anchor), 8);
  });

  it("addWorldDisplacementMetersToGpos converts m to ly (scale 1 = physical patch)", () => {
    const g0 = { x: 0, y: 0, z: 0 };
    const g1 = addWorldDisplacementMetersToGpos(g0, 0, METERS_PER_LY, 0, 1);
    expect(g1.x).toBe(0);
    expect(g1.y).toBeCloseTo(1, 5);
  });

  it("addGposStepTowardPointLy moves along catalog line", () => {
    const anchor = { x: 0, y: 0, z: 0 };
    const w = { x: 1, y: 0, z: 0 };
    const point = getStarCatalogPositionLy(7, 1, 1, anchor, w);
    const g0 = { x: point.x - 100, y: point.y, z: point.z };
    const g1 = addGposStepTowardPointLy(
      g0,
      METERS_PER_LY,
      0,
      0,
      point,
      w,
      w,
      1
    );
    expect(lightYearsDistance3D(point, g1)).toBeLessThan(lightYearsDistance3D(point, g0));
  });

  it("chart step is capped by MAX_CHART_LY_STEP_PER_FRAME", () => {
    const point = { x: 10_000, y: 0, z: 0 };
    const g0 = { x: 0, y: 0, z: 0 };
    const w = { x: 1, y: 0, z: 0 };
    const g1 = addGposStepTowardPointLy(
      g0,
      METERS_PER_LY * 1e6,
      0,
      0,
      point,
      w,
      w,
      1
    );
    const step = Math.hypot(g1.x - g0.x, g1.y - g0.y, g1.z - g0.z);
    expect(step).toBeLessThanOrEqual(MAX_CHART_LY_STEP_PER_FRAME + 1e-9);
  });

  it("addWorldDisplacementMetersToGpos applies travelScale in deep", () => {
    const g0 = { x: 0, y: 0, z: 0 };
    const g1 = addWorldDisplacementMetersToGpos(
      g0,
      0,
      1,
      0,
      DEEP_GPOS_TRAVEL_SCALE
    );
    const expected = DEEP_GPOS_TRAVEL_SCALE / METERS_PER_LY;
    expect(g1.y - g0.y).toBeCloseTo(expected, 5);
  });

  it("does not increase distance in one step (overshoot cap)", () => {
    const point = { x: 100, y: 0, z: 0 };
    const g0 = { x: 99.5, y: 0, z: 0 };
    const d0 = lightYearsDistance3D(point, g0);
    const g1 = addGposStepTowardPointLy(
      g0,
      METERS_PER_LY * 100,
      0,
      0,
      point,
      { x: 1, y: 0, z: 0 },
      { x: 1, y: 0, z: 0 },
      1
    );
    const d1 = lightYearsDistance3D(point, g1);
    expect(d1).toBeLessThanOrEqual(d0 + 1e-9);
  });

  it("lightYearsDistance3D", () => {
    expect(
      lightYearsDistance3D({ x: 0, y: 0, z: 0 }, { x: 3, y: 4, z: 0 })
    ).toBe(5);
  });
});
