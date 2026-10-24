import {
  AERIAL_ENEMIES_PER_WAVE,
  AERIAL_SPAWN_R_MAX,
  AERIAL_SPAWN_R_MIN,
  ENEMY_BASE_HP,
  FIRST_SPACE_WAVE_IN,
  SPACE_WAVE_EVERY,
  waveExtraHp,
} from "../../content/waves";
import { clampToFlightShell, isDeepOrbitFlight } from "../../planet/spaceFlight";
import type { GameState } from "../types";

/**
 * M5: timed spawns in deep orbit when space combat is enabled. Does not run economy or ground waves.
 */
export function runSpaceWaves(
  s: GameState,
  emit: (e: { type: "spaceWave"; wave: number }) => void
): void {
  if (s.gameOver) return;
  if (!s.spaceCombatEnabled || !s.flight.active) return;
  if (!isDeepOrbitFlight(s.flight, s.coreX, s.flight.y, s.coreZ)) return;

  if (s.spaceNextWaveAt === 0) {
    s.spaceNextWaveAt = s.tick + FIRST_SPACE_WAVE_IN;
    return;
  }
  if (s.tick < s.spaceNextWaveAt) return;

  s.spaceWaveIndex += 1;
  const extraHp = waveExtraHp(s.spaceWaveIndex) + Math.min(8, Math.floor(s.pollution / 40));
  const nSpawn = AERIAL_ENEMIES_PER_WAVE + Math.min(2, Math.floor(s.pollution / 50));
  for (let i = 0; i < nSpawn; i++) {
    const u = s.rng.nextFloat() * Math.PI * 2;
    const v = s.rng.nextFloat() * Math.PI * 2;
    const dirX = Math.cos(u) * Math.sin(v);
    const dirY = Math.sin(u) * Math.sin(v);
    const dirZ = Math.cos(v);
    const dist = AERIAL_SPAWN_R_MIN + s.rng.nextFloat() * (AERIAL_SPAWN_R_MAX - AERIAL_SPAWN_R_MIN);
    let x = s.coreX + dirX * dist;
    let y = s.flight.y + dirY * dist;
    let z = s.coreZ + dirZ * dist;
    const p = clampToFlightShell(x, y, z);
    x = p.x;
    y = p.y;
    z = p.z;
    const id = s.nextEntityId++;
    s.enemies.push({
      id,
      x,
      y,
      z,
      hp: ENEMY_BASE_HP + extraHp,
    });
  }
  s.spaceNextWaveAt = s.tick + SPACE_WAVE_EVERY;
  emit({ type: "spaceWave", wave: s.spaceWaveIndex });
}
