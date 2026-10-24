import { mix4 } from "../sim/universe/hash";
import { starShellRadiusLy } from "../sim/universe/galacticSpace";

/** u in [0,1) from deterministic mix */
function u01(seed: number, i: number, salt: number): number {
  return (mix4(seed, i >>> 0, salt, 0x4b1d) & 0xfffffff) / 0x1000_0000;
}

/**
 * Star catalog: [wx, wy, wz, R_ly] × SKY_TOTAL_PICKABLE.
 * wx/wy/wz = unit direction from sector anchor (matches pickable directions).
 * R_ly = shell radius in light-years (deterministic per star).
 *
 * From this data you can compute a star's ly-space position as:
 *   anchorLy + (wx,wy,wz) * R_ly
 * and its distance to the player as:
 *   |(anchorLy + (wx,wy,wz) * R_ly) - gpos_ly|
 */
export function buildStarCatalog(skydomeSeed: number, gameSeed: number): Float32Array {
  const out = new Float32Array(SKY_TOTAL_PICKABLE * 4);
  let o = 0;
  for (let i = 0; i < SKY_MAIN_STARS; i++) {
    const d = seededStarUnitDirection(skydomeSeed, i, SALT_MAIN);
    const R = starShellRadiusLy(i, skydomeSeed, gameSeed);
    out[o++] = d.x;
    out[o++] = d.y;
    out[o++] = d.z;
    out[o++] = R;
  }
  for (let i = 0; i < SKY_DENSE_STARS; i++) {
    const idx = SKY_MAIN_STARS + i;
    const d = seededStarUnitDirection(skydomeSeed, idx, SALT_DENSE);
    const R = starShellRadiusLy(idx, skydomeSeed, gameSeed);
    out[o++] = d.x;
    out[o++] = d.y;
    out[o++] = d.z;
    out[o++] = R;
  }
  return out;
}

/**
 * Fill `out` (length count*3) with positions on a sphere of radius `r` (world units, camera-centered space).
 * Each triplet is deterministic from (skydomeSeed, index, saltBase).
 */
export function fillSeededSpherePoints(
  out: Float32Array,
  r: number,
  skydomeSeed: number,
  startIndex: number,
  count: number,
  saltBase: number
): void {
  for (let i = 0; i < count; i++) {
    const u = u01(skydomeSeed, startIndex + i, saltBase + 1) * Math.PI * 2;
    const c = u01(skydomeSeed, startIndex + i, saltBase + 2) * 2 - 1;
    const phi = Math.acos(c);
    const sinPhi = Math.sin(phi);
    const x = sinPhi * Math.cos(u) * r;
    const y = Math.cos(phi) * r;
    const z = sinPhi * Math.sin(u) * r;
    const j = i * 3;
    out[j] = x;
    out[j + 1] = y;
    out[j + 2] = z;
  }
}

/** Unit direction (x,y,z) for star index; same seeding as fillSeededSpherePoints at r=1. */
export function seededStarUnitDirection(
  skydomeSeed: number,
  globalIndex: number,
  saltBase: number
): { x: number; y: number; z: number } {
  const u = u01(skydomeSeed, globalIndex, saltBase + 1) * Math.PI * 2;
  const c = u01(skydomeSeed, globalIndex, saltBase + 2) * 2 - 1;
  const phi = Math.acos(c);
  const sinPhi = Math.sin(phi);
  const x = sinPhi * Math.cos(u);
  const y = Math.cos(phi);
  const z = sinPhi * Math.sin(u);
  const l = Math.hypot(x, y, z) || 1e-9;
  return { x: x / l, y: y / l, z: z / l };
}

export const SKY_MAIN_STARS = 9000;
export const SKY_DENSE_STARS = 12_000;
export const SKY_GALACTIC_POINTS = 500;
export const SKY_TOTAL_PICKABLE = SKY_MAIN_STARS + SKY_DENSE_STARS;

const SALT_MAIN = 0x11a0;
const SALT_DENSE = 0x22b0;

/** Build concatenated unit directions for picking (main then dense). Length (MAIN+DENSE)*3. */
export function buildPickableStarDirections(skydomeSeed: number): Float32Array {
  const out = new Float32Array(SKY_TOTAL_PICKABLE * 3);
  let o = 0;
  for (let i = 0; i < SKY_MAIN_STARS; i++) {
    const d = seededStarUnitDirection(skydomeSeed, i, SALT_MAIN);
    out[o++] = d.x;
    out[o++] = d.y;
    out[o++] = d.z;
  }
  for (let i = 0; i < SKY_DENSE_STARS; i++) {
    const d = seededStarUnitDirection(skydomeSeed, SKY_MAIN_STARS + i, SALT_DENSE);
    out[o++] = d.x;
    out[o++] = d.y;
    out[o++] = d.z;
  }
  return out;
}

/**
 * The skydome star with **maximum** dot(look, dir) — one of 21k+ points always “wins”. Use for
 * travel and HUD distance; {@link pickStarIndexAlongLook} is for strict (small cone) hovers/labels
 * and often returns `null` if you are not right on a point.
 */
export function pickStarIndexBestLook(
  lookX: number,
  lookY: number,
  lookZ: number,
  dirs: Float32Array
): { index: number; angle: number; dot: number } {
  const ll = Math.hypot(lookX, lookY, lookZ) || 1e-9;
  const lx = lookX / ll;
  const ly = lookY / ll;
  const lz = lookZ / ll;
  let bestI = 0;
  let bestDot = -2;
  const n = dirs.length / 3;
  for (let i = 0; i < n; i++) {
    const j = i * 3;
    const dot = lx * dirs[j]! + ly * dirs[j + 1]! + lz * dirs[j + 2]!;
    if (dot > bestDot) {
      bestDot = dot;
      bestI = i;
    }
  }
  const clamped = Math.min(1, Math.max(-1, bestDot));
  return { index: bestI, angle: Math.acos(clamped), dot: bestDot };
}

/** Best angular match to `look` (unit); returns `null` if the best match is still wider than `maxAngleRad` from the ray. */
export function pickStarIndexAlongLook(
  lookX: number,
  lookY: number,
  lookZ: number,
  dirs: Float32Array,
  maxAngleRad: number
): { index: number; angle: number } | null {
  const b = pickStarIndexBestLook(lookX, lookY, lookZ, dirs);
  if (b.angle > maxAngleRad) return null;
  return { index: b.index, angle: b.angle };
}

/** Unit direction for a pickable index — matches {@link buildPickableStarDirections} / GPU layout. */
export function pickableStarUnitDirection(
  skydomeSeed: number,
  globalIndex: number
): { x: number; y: number; z: number } {
  const i = globalIndex >>> 0;
  if (i < SKY_MAIN_STARS) return seededStarUnitDirection(skydomeSeed, i, SALT_MAIN);
  return seededStarUnitDirection(skydomeSeed, i, SALT_DENSE);
}
