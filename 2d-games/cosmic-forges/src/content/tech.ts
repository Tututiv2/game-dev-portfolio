/** Phase 1 — light tech / logistics. Tier unlocks are automatic at science thresholds. */

export const SCIENCE_TIER_1 = 120;
export const SCIENCE_TIER_2 = 400;

export const TIER_LABELS = ["Frontier", "Stabilized", "Optimized"] as const;

/** Max extractors at full output before overflow penalty */
export function logisticsCap(tier: number): number {
  return 4 + tier * 4; // 4, 8, 12
}

/** Portion of overflow extractors that still output (0–1) */
export const LOGISTICS_OVERFLOW_EFF = 0.45;

/** Pollution produced per extractor per sim tick (scaled by runPollution) */
export const POLLUTION_PER_EXTRACTOR_TICK = 0.04;

/** Natural decay + tier bonus (per tick) */
export function pollutionDecay(tier: number): number {
  return 0.12 + tier * 0.06;
}

/** Reduces how fast pollution accumulates (after tier 0) */
export function pollutionGenMult(tier: number): number {
  return Math.max(0.35, 1.0 - tier * 0.22);
}

/** Passive science from relay buildings (per sim tick) */
export const RELAY_SCIENCE_PER_TICK = 0.07;
/** Per relay (per sim tick) — small industrial footprint */
export const RELAY_POLLUTION_PER_TICK = 0.012;
/** Scrub from each purifier (per sim tick) */
export const PURIFIER_SCRUB_PER_TICK = 0.14;
/** Sentry power plant / active cooling (per sim tick) */
export const SENTRY_POLLUTION_PER_TICK = 0.006;
