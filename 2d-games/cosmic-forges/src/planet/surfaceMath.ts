/**
 * Spherical "planetoid" in world space: center at (0, -PLANET_RADIUS, 0), radius PLANET_RADIUS.
 * Origin (0,0,0) lies on the surface. Gameplay sim uses flat XZ with Y derived for rendering.
 */
export const PLANET_RADIUS = 200;
export const PLANET_CENTER_Y = -PLANET_RADIUS;
/** Horizontal distance must stay < planet radius (gameplay + sqrt domain). */
export const MAX_HORIZ_R = 199.5;
/** Legacy alias: movement uses full surface via sphere reprojection. */
export const CORE_MOVE_MAX_R = MAX_HORIZ_R;
/** No-build disc around the mobile core */
export const CORE_EXCLUSION_R = 2.5;

/** World Y of the ground surface at horizontal (x, z) */
export function surfaceY(x: number, z: number): number {
  const r2 = x * x + z * z;
  const t = PLANET_RADIUS * PLANET_RADIUS - r2;
  if (t <= 0) return 0;
  return -PLANET_RADIUS + Math.sqrt(t);
}

/** Outward "up" from planet center, unit (for object orientation) */
export function surfaceUpComponents(x: number, z: number): { ux: number; uy: number; uz: number } {
  const y = surfaceY(x, z);
  const dy = y - PLANET_CENTER_Y;
  const len = Math.hypot(x, dy, z) || 1;
  return { ux: x / len, uy: dy / len, uz: z / len };
}

/** Clamps a horizontal position so the surface (sqrt) and spawns stay valid. */
export function clampHorizToSurface(x: number, z: number): { x: number; z: number } {
  const r = Math.hypot(x, z);
  if (r <= MAX_HORIZ_R || r < 1e-9) return { x, z };
  const k = MAX_HORIZ_R / r;
  return { x: x * k, z: z * k };
}
