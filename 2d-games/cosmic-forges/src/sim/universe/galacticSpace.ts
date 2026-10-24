import { mix4 } from "./hash";

/** One light-year in meters (enough for travel bookkeeping). */
export const METERS_PER_LY = 9.460_730_472_580_800 as const;

/**
 * Meters of galactic “grid” per map sector. Keeps a ~256×256 sector chart within float64
 * without every star being simulated — only this anchor + procedural star points are used.
 */
export const SECTOR_LY = 4_200 as const;

/** @deprecated Old cube edge (shell model is canonical now). */
export const CATALOG_LY = 1_200_000 as const;

/** Shell radii (ly) from sector anchor; same **w** as the skydome. */
export const STAR_SHELL_R_MIN_LY = 4 as const;
export const STAR_SHELL_R_MAX_LY = 120 as const;

/** Max collinear gpos step per frame (ly) to avoid a one-frame “0 ly” snap. */
export const MAX_CHART_LY_STEP_PER_FRAME = 1.2 as const;

function u01(mix: number): number {
  return (mix >>> 0) / 0x1_0000_0000;
}

/**
 * Where the local system sits in the galactic (ly) frame when you’re in a sector.
 * Deterministic from sector + system seed; does not move until deep-space drift or FTL.
 */
export function initialGalacticPositionLy(
  galaxyX: number,
  galaxyY: number,
  systemSeed: number
): { x: number; y: number; z: number } {
  const s = systemSeed >>> 0;
  const jx = mix4(s, galaxyX, galaxyY, 0x1a2b);
  const jy = mix4(s, galaxyX, galaxyY, 0x3c4d);
  const jz = mix4(s, galaxyX, galaxyY, 0x5e6f);
  return {
    x: galaxyX * SECTOR_LY + u01(jx) * SECTOR_LY * 0.75,
    y: (u01(jy) - 0.5) * SECTOR_LY * 0.4,
    z: galaxyY * SECTOR_LY + u01(jz) * SECTOR_LY * 0.75,
  };
}

export function starShellRadiusLy(starIndex: number, skydomeSeed: number, gameSeed: number): number {
  const i = starIndex >>> 0;
  const t = mix4(i, skydomeSeed, gameSeed, 0x5ca1_55);
  return STAR_SHELL_R_MIN_LY + u01(t) * (STAR_SHELL_R_MAX_LY - STAR_SHELL_R_MIN_LY);
}

/**
 * Ly position of a pickable skydome star: **sector anchor** + **w** × **R**, where **w** is the same
 * unit direction as the rendered point and **R** is a deterministic shell radius.
 */
export function getStarCatalogPositionLy(
  starIndex: number,
  skydomeSeed: number,
  gameSeed: number,
  anchorLy: { x: number; y: number; z: number },
  worldUnitTowardStar: { x: number; y: number; z: number }
): { x: number; y: number; z: number } {
  const wl = Math.hypot(worldUnitTowardStar.x, worldUnitTowardStar.y, worldUnitTowardStar.z) || 1e-9;
  const wx = worldUnitTowardStar.x / wl;
  const wy = worldUnitTowardStar.y / wl;
  const wz = worldUnitTowardStar.z / wl;
  const R = starShellRadiusLy(starIndex, skydomeSeed, gameSeed);
  return {
    x: anchorLy.x + wx * R,
    y: anchorLy.y + wy * R,
    z: anchorLy.z + wz * R,
  };
}

export function lightYearsDistance3D(
  a: { x: number; y: number; z: number },
  b: { x: number; y: number; z: number }
): number {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  const dz = a.z - b.z;
  return Math.hypot(dx, dy, dz);
}

/**
 * Move `gpos` in chart space **along the line to `pointLy`**, from world motion toward the
 * skydome star. The **ly** step uses the true catalog unit vector.
 *
 * `d·worldUnitTowardStar` is motion toward the skydome, but only **W** (camera forward) aligns with
 * that; **Space** accelerates **radially** from the planet, so that dot is often small or the wrong
 * sign. `reticleUnitLook` (same ray as the star-peek / mouse) rewards **forward** motion while
 * aimed at the star, and a small “locked on” term when the reticle and `w` align so climb/hover
 * still makes chart progress and HUD distance can trend down.
 */
export function addGposStepTowardPointLy(
  gpos: { x: number; y: number; z: number },
  dxM: number,
  dyM: number,
  dzM: number,
  pointLy: { x: number; y: number; z: number },
  worldUnitTowardStar: { x: number; y: number; z: number },
  reticleUnitLook: { x: number; y: number; z: number },
  travelScale: number
): { x: number; y: number; z: number } {
  const sx = pointLy.x - gpos.x;
  const sy = pointLy.y - gpos.y;
  const sz = pointLy.z - gpos.z;
  const dist = Math.hypot(sx, sy, sz) || 1e-9;
  const uxl = sx / dist;
  const uyl = sy / dist;
  const uzl = sz / dist;
  const wx = worldUnitTowardStar.x;
  const wy = worldUnitTowardStar.y;
  const wz = worldUnitTowardStar.z;
  const ll0 = Math.hypot(reticleUnitLook.x, reticleUnitLook.y, reticleUnitLook.z) || 1e-9;
  const lx = reticleUnitLook.x / ll0;
  const ly = reticleUnitLook.y / ll0;
  const lz = reticleUnitLook.z / ll0;
  const dMag = Math.hypot(dxM, dyM, dzM);
  const base = dxM * wx + dyM * wy + dzM * wz;
  const dLook = dxM * lx + dyM * ly + dzM * lz;
  const aW = wx * lx + wy * ly + wz * lz;
  let alongM = base;
  if (aW > 0.45 && dLook > 0) {
    alongM = Math.max(alongM, dLook * aW);
  }
  if (aW > 0.6 && dMag > 1e-9) {
    const notHardBackward = dLook > -0.12 * dMag;
    if (notHardBackward && Math.abs(base) < 0.4 * dMag) {
      alongM = Math.max(alongM, dMag * 0.5 * aW * aW);
    }
  }
  // Never move backward along the line to the catalog point from mistaken signed dots (e.g. climb vs w); raw negative would increase HUD distance.
  alongM = Math.max(0, alongM);
  if (dMag > 1e-9 && aW > 0.2) {
    // Smallest closing rate when roughly aligned: capped to a fraction of this frame’s |d| so the step never overshoots the motion budget.
    const minClose = dMag * Math.min(0.55, 0.5 * aW * aW);
    alongM = Math.max(alongM, minClose);
  }
  const s = travelScale / METERS_PER_LY;
  /** Proposed collinear step in **ly** along **(pointLy − gpos)** — must be ≤ `dist` or we overshoot the target and land on the far side, which *increases* the Euclidean “distance to star” on the next frame. */
  let tLy = alongM * s;
  if (tLy > 0) {
    tLy = Math.min(tLy, dist * (1 - 1e-9), MAX_CHART_LY_STEP_PER_FRAME);
  }
  return {
    x: gpos.x + tLy * uxl,
    y: gpos.y + tLy * uyl,
    z: gpos.z + tLy * uzl,
  };
}

/**
 * Raw 3D **gpos** update: `gpos += d` in ly units (scaled). Use when no single target; can move you
 * arbitrarily in the chart. Prefer {@link addGposStepTowardPointLy} when closing on a catalog star.
 */
export function addWorldDisplacementMetersToGpos(
  gpos: { x: number; y: number; z: number },
  dxM: number,
  dyM: number,
  dzM: number,
  /** 1 = 1 real meter → 1 / {@link METERS_PER_LY} ly. Use {@link DEEP_GPOS_TRAVEL_SCALE} in deep SOI. */
  travelScale = 1
): { x: number; y: number; z: number } {
  const s = travelScale / METERS_PER_LY;
  return {
    x: gpos.x + dxM * s,
    y: gpos.y + dyM * s,
    z: gpos.z + dzM * s,
  };
}

/**
 * In deep space, each meter of sim motion counts this many “effective” meters in the ly chart
 * (1:1 would be unplayable at real scale). Tune to taste; distance is still plain Euclidean
 * in {@link getStarCatalogPositionLy} / `gpos` space.
 */
export const DEEP_GPOS_TRAVEL_SCALE = 6_000_000_000 as const;
