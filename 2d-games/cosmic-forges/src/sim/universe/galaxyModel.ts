import { mix4 } from "./hash";

export const GALAXY_GRID = 256;

export function sectorId(gx: number, gy: number): number {
  return ((gx & 0xff) | ((gy & 0xff) << 8)) >>> 0;
}

export function starAtSector(gx: number, gy: number): { systemSeed: number; label: string } {
  const s = mix4(gx * 0x1f1f, gy * 0x3c3c, 0xdead, 0xbeef);
  const label = `★ ${(s & 0xffff).toString(16).toUpperCase()}`;
  return { systemSeed: s, label };
}

export const WARP_ENERGY = 8;

/**
 * 8-neighbor sectors around (gx, gy), streamed for UI.
 */
export function neighbors8(gx: number, gy: number): { x: number; y: number }[] {
  const o = [-1, 0, 1];
  const p: { x: number; y: number }[] = [];
  for (const i of o) for (const j of o) {
    p.push({ x: gx + i, y: gy + j });
  }
  return p;
}

export function inBounds(nx: number, ny: number): boolean {
  return nx >= 0 && ny >= 0 && nx < GALAXY_GRID && ny < GALAXY_GRID;
}
