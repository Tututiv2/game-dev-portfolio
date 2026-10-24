import type { SeededRng } from "../core/rng";

export type BuildingKind = "extractor" | "purifier" | "relay" | "sentry";

export type Building = {
  id: number;
  x: number;
  z: number;
  type: BuildingKind;
};

export type Enemy = {
  id: number;
  x: number;
  z: number;
  /** M5: set for free-fly hostiles; omit for surface mobs. */
  y?: number;
  hp: number;
};

export type ViewMode = "surface" | "solar" | "galaxy";

/**
 * Free-flight (M0): third-person thrusters. While {@link FlightState.active} is true,
 * `coreX` / `coreZ` / `y` are a full 3D position; on foot, render derives Y from the planet math.
 */
export type FlightState = {
  active: boolean;
  /** World Y while in spaceflight. Ignored when not {@link active}. */
  y: number;
  /** 0.25 — 4 from HUD; scales base flight speed in the surface view. */
  speedMul: number;
  /** World velocity (m/s) while in flight; used in deep SOI for interstellar-style thrust and braking. */
  vx: number;
  vy: number;
  vz: number;
};

export type UniverseState = {
  viewMode: ViewMode;
  systemSeed: number;
  bodyIndex: number;
  galaxyX: number;
  galaxyY: number;
  /** discovered sector ids (packed gx|gy) */
  discovered: number[];
  ftlEnergy: number;
  skyParallax: number;
  /** M2: galactic “next destination” for HUD / on-sky arrow; does not move the ship. */
  navTarget: { x: number; y: number } | null;
  /**
   * Legacy field (no longer used for FTL). Kept for save compatibility; always 0 in new games.
   */
  galacticCruiseProgress: number;
  /** If set, star label stays on this skydome index until cleared (optional pin). */
  pinnedStarIndex: number | null;
  /**
   * Player position in the **galactic (light-year) chart** used for true star range on labels.
   * World flight (meters) in deep space nudges this; FTL re-anchors to the destination sector.
   * Huge volume; one triple per run — not a per-star simulation.
   */
  gposX: number;
  gposY: number;
  gposZ: number;
};

export type GameState = {
  seed: number;
  tick: number;
  rng: SeededRng;
  ferrium: number;
  nextEntityId: number;
  /** Command core — lose when 0 */
  coreHp: number;
  /** Surface / flight position. When `flight.active`, also use `flight.y` for 3D. */
  coreX: number;
  coreZ: number;
  flight: FlightState;
  nextWaveAt: number;
  waveIndex: number;
  gameOver: boolean;
  /** Phase 1: 0–200+; drives wave pressure */
  pollution: number;
  /** Unlocks tiers at SCIENCE_TIER_* thresholds */
  science: number;
  /** 0, 1, 2 = logistics + less pollution, auto from science */
  techTier: number;
  /** Fractional carry for ferrium (logistics / partial extractors) */
  ferriumCarry: number;
  buildings: Building[];
  enemies: Enemy[];
  universe: UniverseState;
  /** M4 — M5: when true, deep-orbit can spawn 3D hostiles. */
  spaceCombatEnabled: boolean;
  /**
   * M5: `0` = first visit to `runSpaceWaves` will arm `tick + delay`. Otherwise sim tick of next
   * space wave when {@link spaceCombatEnabled} and in deep SOI.
   */
  spaceNextWaveAt: number;
  /** M5: counter for space wave difficulty. */
  spaceWaveIndex: number;
};
