import type { Enemy, FlightState, GameState, UniverseState } from "../sim/types";
import { SeededRng } from "./rng";
import { createInitialState } from "../sim/initialState";
import { normalizeBuildingType } from "../sim/placeBuilding";
import { initialGalacticPositionLy } from "../sim/universe/galacticSpace";
import { sectorId, starAtSector } from "../sim/universe/galaxyModel";

export const SAVE_VERSION = 4 as const;
const STORAGE_KEY = "cosmic-forges-save-v4";

const PHASE1 = {
  pollution: 0,
  science: 0,
  techTier: 0,
  ferriumCarry: 0,
} as const;

export type SavePayloadV1 = {
  version: 1;
  seed: number;
  rng: number;
  state: {
    tick: number;
    ferrium: number;
    nextEntityId: number;
    coreHp: number;
    nextWaveAt: number;
    waveIndex: number;
    gameOver: boolean;
    buildings: { id: number; x: number; z: number; type: string }[];
    enemies: { id: number; x: number; z: number; hp: number; y?: number }[];
  };
};

type UniversePayload = {
  viewMode: string;
  systemSeed: number;
  bodyIndex: number;
  galaxyX: number;
  galaxyY: number;
  discovered: number[];
  ftlEnergy: number;
  skyParallax: number;
  navTarget?: { x: number; y: number } | null;
  galacticCruiseProgress?: number;
  pinnedStarIndex?: number | null;
  gposX?: number;
  gposY?: number;
  gposZ?: number;
};

type CoreStatePayload = {
  tick: number;
  ferrium: number;
  nextEntityId: number;
  coreHp: number;
  nextWaveAt: number;
  waveIndex: number;
  gameOver: boolean;
  buildings: { id: number; x: number; z: number; type: string }[];
  enemies: { id: number; x: number; z: number; hp: number; y?: number }[];
  pollution?: number;
  science?: number;
  techTier?: number;
  ferriumCarry?: number;
  coreX?: number;
  coreZ?: number;
  flight?: { active: boolean; y: number; speedMul: number; vx?: number; vy?: number; vz?: number };
  spaceCombatEnabled?: boolean;
  spaceNextWaveAt?: number;
  spaceWaveIndex?: number;
};

export type SavePayloadV2 = {
  version: 2;
  seed: number;
  rng: number;
  state: CoreStatePayload;
  universe: UniversePayload;
};

export type SavePayloadV3 = {
  version: 3;
  seed: number;
  rng: number;
  state: CoreStatePayload & {
    pollution: number;
    science: number;
    techTier: number;
    ferriumCarry: number;
    coreX: number;
    coreZ: number;
  };
  universe: UniversePayload;
};

export type SavePayloadV4 = {
  version: 4;
  seed: number;
  rng: number;
  state: CoreStatePayload & {
    pollution: number;
    science: number;
    techTier: number;
    ferriumCarry: number;
    coreX: number;
    coreZ: number;
  };
  universe: UniversePayload;
};

function parseEnemy(
  e: { id: number; x: number; z: number; hp: number; y?: number }
): Enemy {
  return {
    id: e.id,
    x: e.x,
    z: e.z,
    hp: e.hp,
    ...(typeof e.y === "number" && Number.isFinite(e.y) ? { y: e.y } : {}),
  };
}

function toPayload(state: GameState): SavePayloadV4 {
  return {
    version: 4,
    seed: state.seed,
    rng: state.rng.state >>> 0,
    state: {
      tick: state.tick,
      ferrium: state.ferrium,
      nextEntityId: state.nextEntityId,
      coreHp: state.coreHp,
      nextWaveAt: state.nextWaveAt,
      waveIndex: state.waveIndex,
      gameOver: state.gameOver,
      buildings: state.buildings.map((b) => ({ ...b })),
      enemies: state.enemies.map((e) => ({ ...e })),
      pollution: state.pollution,
      science: state.science,
      techTier: state.techTier,
      ferriumCarry: state.ferriumCarry,
      coreX: state.coreX,
      coreZ: state.coreZ,
      flight: {
        active: state.flight.active,
        y: state.flight.y,
        speedMul: state.flight.speedMul,
        vx: state.flight.vx,
        vy: state.flight.vy,
        vz: state.flight.vz,
      },
      spaceCombatEnabled: state.spaceCombatEnabled,
      spaceNextWaveAt: state.spaceNextWaveAt,
      spaceWaveIndex: state.spaceWaveIndex,
    },
    universe: {
      viewMode: state.universe.viewMode,
      systemSeed: state.universe.systemSeed,
      bodyIndex: state.universe.bodyIndex,
      galaxyX: state.universe.galaxyX,
      galaxyY: state.universe.galaxyY,
      discovered: [...state.universe.discovered],
      ftlEnergy: state.universe.ftlEnergy,
      skyParallax: state.universe.skyParallax,
      navTarget: state.universe.navTarget,
      galacticCruiseProgress: state.universe.galacticCruiseProgress,
      pinnedStarIndex: state.universe.pinnedStarIndex,
      gposX: state.universe.gposX,
      gposY: state.universe.gposY,
      gposZ: state.universe.gposZ,
    },
  };
}

function parseUniverse(u: UniversePayload): UniverseState {
  const dft = initialGalacticPositionLy(u.galaxyX, u.galaxyY, u.systemSeed);
  return {
    viewMode: u.viewMode as UniverseState["viewMode"],
    systemSeed: u.systemSeed,
    bodyIndex: u.bodyIndex,
    galaxyX: u.galaxyX,
    galaxyY: u.galaxyY,
    discovered: u.discovered,
    ftlEnergy: u.ftlEnergy,
    skyParallax: u.skyParallax,
    navTarget: u.navTarget && typeof u.navTarget.x === "number" && typeof u.navTarget.y === "number"
      ? { x: u.navTarget.x, y: u.navTarget.y }
      : null,
    galacticCruiseProgress: Math.max(
      0,
      Math.min(
        1,
        Number.isFinite(u.galacticCruiseProgress ?? 0) ? (u.galacticCruiseProgress ?? 0) : 0
      )
    ),
    pinnedStarIndex:
      u.pinnedStarIndex != null && Number.isFinite(u.pinnedStarIndex) && (u.pinnedStarIndex as number) >= 0
        ? (u.pinnedStarIndex as number) >>> 0
        : null,
    gposX: typeof u.gposX === "number" && Number.isFinite(u.gposX) ? u.gposX : dft.x,
    gposY: typeof u.gposY === "number" && Number.isFinite(u.gposY) ? u.gposY : dft.y,
    gposZ: typeof u.gposZ === "number" && Number.isFinite(u.gposZ) ? u.gposZ : dft.z,
  };
}

function parseCore(st: CoreStatePayload): {
  tick: number;
  ferrium: number;
  nextEntityId: number;
  coreHp: number;
  nextWaveAt: number;
  waveIndex: number;
  gameOver: boolean;
  buildings: GameState["buildings"];
  enemies: GameState["enemies"];
  pollution: number;
  science: number;
  techTier: number;
  ferriumCarry: number;
  coreX: number;
  coreZ: number;
  flight: FlightState;
  spaceCombatEnabled: boolean;
  spaceNextWaveAt: number;
  spaceWaveIndex: number;
} {
  return {
    tick: st.tick,
    ferrium: st.ferrium,
    nextEntityId: st.nextEntityId,
    coreHp: st.coreHp,
    nextWaveAt: st.nextWaveAt,
    waveIndex: st.waveIndex,
    gameOver: st.gameOver,
    buildings: st.buildings.map((b) => ({ ...b, type: normalizeBuildingType(String(b.type)) })),
    enemies: st.enemies.map((e) => parseEnemy(e)),
    pollution: st.pollution ?? PHASE1.pollution,
    science: st.science ?? PHASE1.science,
    techTier: st.techTier ?? PHASE1.techTier,
    ferriumCarry: st.ferriumCarry ?? PHASE1.ferriumCarry,
    coreX: st.coreX ?? 0,
    coreZ: st.coreZ ?? 0,
    flight: st.flight
      ? {
          active: Boolean(st.flight.active),
          y: Number.isFinite(st.flight.y) ? st.flight.y : 0,
          speedMul: Math.min(
            4,
            Math.max(0.25, Number.isFinite(st.flight.speedMul) ? st.flight.speedMul : 1)
          ),
          vx: typeof st.flight.vx === "number" && Number.isFinite(st.flight.vx) ? st.flight.vx : 0,
          vy: typeof st.flight.vy === "number" && Number.isFinite(st.flight.vy) ? st.flight.vy : 0,
          vz: typeof st.flight.vz === "number" && Number.isFinite(st.flight.vz) ? st.flight.vz : 0,
        }
      : { active: false, y: 0, speedMul: 1, vx: 0, vy: 0, vz: 0 },
    spaceCombatEnabled: Boolean(st.spaceCombatEnabled),
    spaceNextWaveAt: st.spaceNextWaveAt ?? 0,
    spaceWaveIndex: st.spaceWaveIndex ?? 0,
  };
}

function v1ToGameState(obj: SavePayloadV1): GameState {
  const r = new SeededRng(obj.seed);
  r.state = obj.rng >>> 0;
  const galaxyX = (obj.seed * 7) & 0xff;
  const galaxyY = (obj.seed * 3) & 0xff;
  const { systemSeed } = starAtSector(galaxyX, galaxyY);
  const g0 = initialGalacticPositionLy(galaxyX, galaxyY, systemSeed);
  const u: UniverseState = {
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
    gposX: g0.x,
    gposY: g0.y,
    gposZ: g0.z,
  };
  return {
    seed: obj.seed,
    tick: obj.state.tick,
    rng: r,
    ferrium: obj.state.ferrium,
    nextEntityId: obj.state.nextEntityId,
    coreHp: obj.state.coreHp,
    nextWaveAt: obj.state.nextWaveAt,
    waveIndex: obj.state.waveIndex,
    gameOver: obj.state.gameOver,
    coreX: 0,
    coreZ: 0,
    flight: { active: false, y: 0, speedMul: 1, vx: 0, vy: 0, vz: 0 },
    spaceCombatEnabled: false,
    spaceNextWaveAt: 0,
    spaceWaveIndex: 0,
    pollution: PHASE1.pollution,
    science: PHASE1.science,
    techTier: PHASE1.techTier,
    ferriumCarry: PHASE1.ferriumCarry,
    buildings: obj.state.buildings.map((b) => ({ ...b, type: normalizeBuildingType(String(b.type)) })),
    enemies: obj.state.enemies.map((e) => parseEnemy(e)),
    universe: u,
  };
}

function fromV2Or3(p: SavePayloadV2 | SavePayloadV3 | SavePayloadV4): GameState {
  const r = new SeededRng(p.seed);
  r.state = p.rng >>> 0;
  const c = parseCore(p.state);
  return {
    seed: p.seed,
    rng: r,
    ferrium: c.ferrium,
    tick: c.tick,
    nextEntityId: c.nextEntityId,
    coreHp: c.coreHp,
    nextWaveAt: c.nextWaveAt,
    waveIndex: c.waveIndex,
    gameOver: c.gameOver,
    buildings: c.buildings,
    enemies: c.enemies,
    pollution: c.pollution,
    science: c.science,
    techTier: c.techTier,
    ferriumCarry: c.ferriumCarry,
    coreX: c.coreX,
    coreZ: c.coreZ,
    flight: c.flight,
    spaceCombatEnabled: c.spaceCombatEnabled,
    spaceNextWaveAt: c.spaceNextWaveAt,
    spaceWaveIndex: c.spaceWaveIndex,
    universe: parseUniverse(p.universe),
  };
}

function fromPayload(p: unknown): GameState {
  if (!p || typeof p !== "object") throw new Error("Invalid save: not an object");
  const v = (p as { version: number }).version;
  if (v === 1) {
    return v1ToGameState(p as SavePayloadV1);
  }
  if (v === 2) {
    return fromV2Or3(p as SavePayloadV2);
  }
  if (v === 3) {
    const g = fromV2Or3(p as SavePayloadV3);
    const a = initialGalacticPositionLy(g.universe.galaxyX, g.universe.galaxyY, g.universe.systemSeed);
    g.universe.gposX = a.x;
    g.universe.gposY = a.y;
    g.universe.gposZ = a.z;
    return g;
  }
  if (v === 4) {
    return fromV2Or3(p as SavePayloadV4);
  }
  throw new Error("Unsupported save version. See docs/SAVES.md");
}

export function serializeState(state: GameState): string {
  return JSON.stringify(toPayload(state));
}

export function deserializeState(json: string): GameState {
  return fromPayload(JSON.parse(json) as unknown);
}

export function saveToLocalStorage(state: GameState): void {
  try {
    localStorage.setItem(STORAGE_KEY, serializeState(state));
  } catch {
    // Quota or disabled
  }
}

export function loadFromLocalStorageOrDefault(defaultSeed: number): GameState {
  try {
    const v4 = localStorage.getItem(STORAGE_KEY);
    if (v4) return deserializeState(v4);
    const v3 = localStorage.getItem("cosmic-forges-save-v3");
    if (v3) {
      const g = deserializeState(v3);
      saveToLocalStorage(g);
      return g;
    }
    const v2 = localStorage.getItem("cosmic-forges-save-v2");
    if (v2) {
      const g = fromPayload(JSON.parse(v2) as unknown);
      saveToLocalStorage(g);
      return g;
    }
    const legacy = localStorage.getItem("cosmic-forges-save-v1");
    if (legacy) {
      const g = v1ToGameState(JSON.parse(legacy) as SavePayloadV1);
      saveToLocalStorage(g);
      return g;
    }
  } catch {
    // ignore
  }
  return createInitialState(defaultSeed);
}

export { STORAGE_KEY };
