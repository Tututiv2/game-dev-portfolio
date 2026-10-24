/** First wave scheduled at this tick (after core exists). */
export const FIRST_WAVE_AT = 60;

export const WAVE_EVERY = 200;

export const ENEMIES_PER_WAVE = 4;

export const SPAWN_DISTANCE = 22;

/** Ticks to reach ~1 unit at default speed; tuned with movement. */
export const ENEMY_BASE_HP = 12;

export const ENEMY_DMG_TO_CORE = 6;

export const ENEMY_SPEED = 0.1;

export const HIT_CORE_RADIUS = 0.4;

/** Sentry turret: range and damage per sim tick to nearest in-range target */
export const SENTRY_RANGE = 12;
export const SENTRY_DMG_PER_TICK = 2.4;

/** Wave difficulty scales slightly with index */
export function waveExtraHp(waveIndex: number): number {
  return Math.min(30, Math.floor(waveIndex * 1.2));
}

// --- M5: deep-orbit space waves (3D) ---

export const FIRST_SPACE_WAVE_IN = 90;
export const SPACE_WAVE_EVERY = 200;
export const AERIAL_SPAWN_R_MIN = 50;
export const AERIAL_SPAWN_R_MAX = 95;
export const AERIAL_ENEMIES_PER_WAVE = 2;
export const AERIAL_ENEMY_SPEED = 0.11;
