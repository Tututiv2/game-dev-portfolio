import type { GameState } from "../types";
import { galacticNavUnit } from "./galacticNavDirection";

/**
 * In deep orbit, thrusting roughly toward the nav sector fills this to 1 → arrival (warp in main).
 */
export function stepGalacticCruise(
  s: GameState,
  delta: number,
  opts: { isDeep: boolean; lookX: number; lookY: number; lookZ: number; forwardInput: boolean }
): { arrived: boolean } {
  const u = s.universe;
  let p = u.galacticCruiseProgress;
  if (p < 0) p = 0;
  if (p > 1) p = 1;
  u.galacticCruiseProgress = p;

  if (!s.flight.active || !opts.isDeep || u.navTarget == null) {
    u.galacticCruiseProgress = p * Math.exp(-1.0 * delta);
    return { arrived: false };
  }
  const nt = u.navTarget;
  if (nt.x === u.galaxyX && nt.y === u.galaxyY) {
    u.galacticCruiseProgress = p * Math.exp(-1.0 * delta);
    return { arrived: false };
  }
  if (!opts.forwardInput) {
    u.galacticCruiseProgress = p * Math.exp(-0.8 * delta);
    return { arrived: false };
  }
  const nav = galacticNavUnit(u.galaxyX, u.galaxyY, nt.x, nt.y);
  let lx = opts.lookX;
  let ly = opts.lookY;
  let lz = opts.lookZ;
  const llen = Math.hypot(lx, ly, lz) || 1e-6;
  lx /= llen;
  ly /= llen;
  lz /= llen;
  const align = Math.max(0, lx * nav.x + ly * nav.y + lz * nav.z);
  if (align < 0.12) {
    u.galacticCruiseProgress = p * Math.exp(-1.0 * delta);
    return { arrived: false };
  }
  const rate = 0.2 * delta * (0.15 + 0.85 * align);
  if (p + rate >= 1) {
    u.galacticCruiseProgress = 0;
    return { arrived: true };
  }
  u.galacticCruiseProgress = p + rate;
  return { arrived: false };
}
