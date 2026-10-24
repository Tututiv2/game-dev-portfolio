export const EXTRACTOR = {
  id: "extractor" as const,
  cost: 40,
  minTier: 0,
  /** Produce one ferrium every N simulation ticks, per building */
  outputEveryTicks: 20,
} as const;

/** Cleans industrial fallout from extractors. No ferrium. */
export const PURIFIER = {
  id: "purifier" as const,
  cost: 50,
  minTier: 0,
} as const;

/** Uplink: passive science, tiny pollution. Unlocks at Stabilized (tier 1). */
export const RELAY = {
  id: "relay" as const,
  cost: 95,
  minTier: 1,
} as const;

/** Point defense: each fires at nearest target in range (tier 1+). */
export const SENTRY = {
  id: "sentry" as const,
  cost: 75,
  minTier: 1,
} as const;

export type BuildingId =
  | (typeof EXTRACTOR)["id"]
  | (typeof PURIFIER)["id"]
  | (typeof RELAY)["id"]
  | (typeof SENTRY)["id"];

const ORDER: Record<BuildingId, number> = { extractor: 0, purifier: 1, relay: 2, sentry: 3 };
const ID_LIST: BuildingId[] = ["extractor", "purifier", "relay", "sentry"];

/** Cycle order for [ ] build picker. */
export const BUILDING_ORDER: readonly BuildingId[] = ID_LIST;

export function buildingById(id: string): { cost: number; minTier: number } | null {
  if (id === "extractor") return EXTRACTOR;
  if (id === "purifier") return PURIFIER;
  if (id === "relay") return RELAY;
  if (id === "sentry") return SENTRY;
  return null;
}

export function isBuildingId(s: string): s is BuildingId {
  return s === "extractor" || s === "purifier" || s === "relay" || s === "sentry";
}

export function nextBuildingId(current: BuildingId, delta: 1 | -1): BuildingId {
  const i = ORDER[current];
  const n = (i + delta + ID_LIST.length) % ID_LIST.length;
  return ID_LIST[n]!;
}

export const GRID = 2;

export function snapToGrid(x: number, z: number): { x: number; z: number } {
  return {
    x: Math.round(x / GRID) * GRID,
    z: Math.round(z / GRID) * GRID,
  };
}

export function isPlacementBlocked(
  x: number,
  z: number,
  buildings: { x: number; z: number }[],
  coreX: number,
  coreZ: number
): boolean {
  const r = 0.75;
  for (const b of buildings) {
    if ((b.x - x) ** 2 + (b.z - z) ** 2 < (r * 2) ** 2) return true;
  }
  if ((x - coreX) ** 2 + (z - coreZ) ** 2 < 2.5 ** 2) return true; // clear zone around mobile core
  return false;
}
