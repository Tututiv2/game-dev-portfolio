import { SeededRng } from "../core/rng";
import { FIRST_WAVE_AT } from "../content/waves";
import { initialGalacticPositionLy } from "./universe/galacticSpace";
import { starAtSector, sectorId } from "./universe/galaxyModel";
import type { GameState, UniverseState } from "./types";

export const CORE_MAX_HP = 100;

export const START_FERRIUM = 80;

function defaultUniverse(seed: number): UniverseState {
  const galaxyX = (seed * 7) & 0xff;
  const galaxyY = (seed * 3) & 0xff;
  const { systemSeed } = starAtSector(galaxyX, galaxyY);
  const g = initialGalacticPositionLy(galaxyX, galaxyY, systemSeed);
  console.log(
    `[init] universe gpos=(${g.x.toFixed(1)},${g.y.toFixed(1)},${g.z.toFixed(1)})`,
    `sector=(${galaxyX},${galaxyY}) systemSeed=${systemSeed}`
  );
  return {
    viewMode: "surface",
    systemSeed,
    bodyIndex: 0,
    galaxyX,
    galaxyY,
    discovered: [sectorId(galaxyX, galaxyY)],
    ftlEnergy: 100,
    skyParallax: 0,
    navTarget: null,
    galacticCruiseProgress: 0,
    pinnedStarIndex: null,
    gposX: g.x,
    gposY: g.y,
    gposZ: g.z,
  };
}

export function createInitialState(seed: number): GameState {
  return {
    seed: seed >>> 0,
    tick: 0,
    rng: new SeededRng(seed),
    ferrium: START_FERRIUM,
    nextEntityId: 1,
    coreHp: CORE_MAX_HP,
    coreX: 0,
    coreZ: 0,
    flight: { active: false, y: 0, speedMul: 1, vx: 0, vy: 0, vz: 0 },
    nextWaveAt: FIRST_WAVE_AT,
    waveIndex: 0,
    gameOver: false,
    pollution: 0,
    science: 0,
    techTier: 0,
    ferriumCarry: 0,
    buildings: [],
    enemies: [],
    universe: defaultUniverse(seed >>> 0),
    spaceCombatEnabled: false,
    spaceNextWaveAt: 0,
    spaceWaveIndex: 0,
  };
}
