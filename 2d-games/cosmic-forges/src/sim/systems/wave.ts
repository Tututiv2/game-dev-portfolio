import {
  ENEMIES_PER_WAVE,
  SPAWN_DISTANCE,
  WAVE_EVERY,
  ENEMY_BASE_HP,
  waveExtraHp,
} from "../../content/waves";
import { clampHorizToSurface } from "../../planet/surfaceMath";
import type { GameState } from "../types";

function waveIntervalTicks(pollution: number): number {
  return Math.max(85, WAVE_EVERY - Math.min(95, Math.floor(pollution / 2.2)));
}

function extraEnemiesFromPollution(pollution: number): number {
  return Math.min(5, Math.floor(pollution / 38));
}

/**
 * Spawns a wave; pressure rises with industry pollution and wave index.
 */
export function runWaves(s: GameState, emit: (e: { type: "wave"; wave: number }) => void): void {
  if (s.gameOver) return;
  // M5: deep-orbit aerial spawns use `runSpaceWaves` (not this). Ground waves stay off in flight.
  if (s.flight.active) return;
  if (s.tick < s.nextWaveAt) return;
  s.waveIndex += 1;
  const extraHp = waveExtraHp(s.waveIndex) + Math.min(12, Math.floor(s.pollution / 25));
  const nSpawn = ENEMIES_PER_WAVE + extraEnemiesFromPollution(s.pollution);
  for (let i = 0; i < nSpawn; i++) {
    const angle = s.rng.nextFloat() * Math.PI * 2;
    const rawX = s.coreX + Math.cos(angle) * SPAWN_DISTANCE;
    const rawZ = s.coreZ + Math.sin(angle) * SPAWN_DISTANCE;
    const { x: ex, z: ez } = clampHorizToSurface(rawX, rawZ);
    const id = s.nextEntityId++;
    s.enemies.push({
      id,
      x: ex,
      z: ez,
      hp: ENEMY_BASE_HP + extraHp,
    });
  }
  s.nextWaveAt = s.tick + waveIntervalTicks(s.pollution);
  emit({ type: "wave", wave: s.waveIndex });
}
