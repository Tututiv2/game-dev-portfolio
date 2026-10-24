import {
  AERIAL_ENEMY_SPEED,
  ENEMY_DMG_TO_CORE,
  ENEMY_SPEED,
  HIT_CORE_RADIUS,
  SENTRY_DMG_PER_TICK,
  SENTRY_RANGE,
} from "../../content/waves";
import type { GameState } from "../types";

/** Surface mobs: XZ only toward the command core. */
export function runSurfaceEnemyMovement(s: GameState): void {
  if (s.gameOver) return;
  for (const e of s.enemies) {
    if (e.y != null) continue;
    const tx = s.coreX;
    const tz = s.coreZ;
    const len = Math.hypot(tx - e.x, tz - e.z) || 1;
    const dx = (tx - e.x) / len;
    const dz = (tz - e.z) / len;
    e.x += dx * ENEMY_SPEED;
    e.z += dz * ENEMY_SPEED;
  }
}

/**
 * M5: 3D pursuit toward the flight core. Requires active flight. Drops strays if thrusters are off.
 */
export function runAerialEnemyMovement(s: GameState): void {
  if (s.gameOver) return;
  if (!s.flight.active) {
    s.enemies = s.enemies.filter((e) => e.y == null);
    return;
  }
  const tx = s.coreX;
  const ty = s.flight.y;
  const tz = s.coreZ;
  for (const e of s.enemies) {
    if (e.y == null) continue;
    const y0 = e.y;
    const len = Math.hypot(e.x - tx, y0 - ty, e.z - tz) || 1e-6;
    const dx = (tx - e.x) / len;
    const dy = (ty - y0) / len;
    const dz = (tz - e.z) / len;
    e.x += dx * AERIAL_ENEMY_SPEED;
    e.y = y0 + dy * AERIAL_ENEMY_SPEED;
    e.z += dz * AERIAL_ENEMY_SPEED;
  }
}

/**
 * Each sentry fires at the nearest enemy in range. Runs after movement so shots hit closing targets.
 */
export function runSentryFire(s: GameState): void {
  if (s.gameOver) return;
  if (s.enemies.length === 0) return;
  for (const t of s.buildings) {
    if (t.type !== "sentry") continue;
    let best: (typeof s.enemies)[0] | null = null;
    let bestD = Infinity;
    for (const e of s.enemies) {
      if (e.y != null) continue;
      const d = Math.hypot(e.x - t.x, e.z - t.z);
      if (d <= SENTRY_RANGE && d < bestD) {
        bestD = d;
        best = e;
      }
    }
    if (best) best.hp -= SENTRY_DMG_PER_TICK;
  }
  s.enemies = s.enemies.filter((e) => e.hp > 0);
}

export function runCoreCollisions(
  s: GameState,
  emit: (e: { type: "gameover" }) => void
): void {
  if (s.gameOver) return;
  if (s.flight.active) return;
  s.enemies = s.enemies.filter((e) => {
    if (e.y != null) return true;
    if (Math.hypot(e.x - s.coreX, e.z - s.coreZ) < HIT_CORE_RADIUS) {
      s.coreHp = Math.max(0, s.coreHp - ENEMY_DMG_TO_CORE);
      if (s.coreHp <= 0) {
        s.gameOver = true;
        emit({ type: "gameover" });
      }
      return false;
    }
    return true;
  });
}

/** M5: in flight, 3D ram damage from aerial hostiles. Surface collisions stay in {@link runCoreCollisions}. */
export function runAerialCoreCollisions(
  s: GameState,
  emit: (e: { type: "gameover" }) => void
): void {
  if (s.gameOver) return;
  if (!s.flight.active) return;
  s.enemies = s.enemies.filter((e) => {
    if (e.y == null) return true;
    const d = Math.hypot(e.x - s.coreX, (e.y ?? 0) - s.flight.y, e.z - s.coreZ);
    if (d < HIT_CORE_RADIUS) {
      s.coreHp = Math.max(0, s.coreHp - ENEMY_DMG_TO_CORE);
      if (s.coreHp <= 0) {
        s.gameOver = true;
        emit({ type: "gameover" });
      }
      return false;
    }
    return true;
  });
}
