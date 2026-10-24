/**
 * Unit direction in world space toward a galactic nav target — same math as
 * `worldDirectionToSector` in `render/galaxyToSky` (kept in sim for import rules).
 */
export function galacticNavUnit(ux: number, uy: number, tx: number, ty: number): { x: number; y: number; z: number } {
  const ddx = tx - ux;
  const ddy = ty - uy;
  if (Math.abs(ddx) < 1e-6 && Math.abs(ddy) < 1e-6) {
    return { x: 0, y: 1, z: 0 };
  }
  const len = Math.hypot(ddx, ddy) || 1e-6;
  const nx = ddx / len;
  const ny = ddy / len;
  const spread = 0.55 + 0.45 * (1 - Math.min(1, len / 8));
  const phi = 0.22 + 0.55 * spread;
  const theta = Math.atan2(ny, nx) * 0.9 + 0.18 * (len * 0.12);
  const sinPhi = Math.sin(phi);
  const x = sinPhi * Math.sin(theta);
  const y = Math.cos(phi);
  const z = sinPhi * Math.cos(theta);
  const l = Math.hypot(x, y, z) || 1e-6;
  return { x: x / l, y: y / l, z: z / l };
}
