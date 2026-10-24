import { buildingById, isPlacementBlocked, isBuildingId, snapToGrid, type BuildingId } from "../content/buildings";
import type { BuildingKind, GameState } from "./types";
import { EventBus } from "../core/eventBus";

function placeFail(bus: EventBus, reason: "funds" | "block" | "tier"): void {
  bus.emit({ type: "place_fail", reason });
}

/**
 * Place the selected building type at grid position (after world-space snap).
 */
export function tryPlaceBuilding(
  s: GameState,
  worldX: number,
  worldZ: number,
  kind: BuildingId,
  bus: EventBus
): boolean {
  if (s.gameOver) return false;
  const def = buildingById(kind);
  if (!def) return false;
  if (s.techTier < def.minTier) {
    placeFail(bus, "tier");
    return false;
  }
  const { x, z } = snapToGrid(worldX, worldZ);
  if (s.ferrium < def.cost) {
    placeFail(bus, "funds");
    return false;
  }
  if (isPlacementBlocked(x, z, s.buildings, s.coreX, s.coreZ)) {
    placeFail(bus, "block");
    return false;
  }
  s.ferrium -= def.cost;
  s.buildings.push({ id: s.nextEntityId++, x, z, type: kind });
  return true;
}

export function tryPlaceExtractor(
  s: GameState,
  worldX: number,
  worldZ: number,
  bus: EventBus
): boolean {
  return tryPlaceBuilding(s, worldX, worldZ, "extractor", bus);
}

export function normalizeBuildingType(t: string): BuildingKind {
  return isBuildingId(t) ? t : "extractor";
}
