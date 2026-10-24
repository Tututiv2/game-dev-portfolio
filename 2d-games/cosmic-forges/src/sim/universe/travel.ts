/**
 * Local instance travel. Multiplayer: each client only loads this game’s `UniverseState`;
 * do not add paths that build or render *another* player’s `systemSeed` / sector without
 * an explicit shared-instance handoff. See `docs/MULTIPLAYER.md`.
 */
import { inBounds, sectorId, starAtSector, WARP_ENERGY } from "./galaxyModel";
import { initialGalacticPositionLy } from "./galacticSpace";
import type { GameState, ViewMode } from "../types";
function ensureDiscovered(s: GameState, sid: number): void {
  if (!s.universe.discovered.includes(sid)) s.universe.discovered.push(sid);
}

export function setViewMode(s: GameState, mode: ViewMode): void {
  s.universe.viewMode = mode;
  const sid = sectorId(s.universe.galaxyX, s.universe.galaxyY);
  ensureDiscovered(s, sid);
}

export function selectBody(s: GameState, index: number): void {
  s.universe.bodyIndex = index;
  setViewMode(s, "surface");
}

/**
 * Try warp to a neighbor sector. Returns false if out of range or not enough FTL.
 */
export function tryWarpTo(s: GameState, tox: number, toy: number): boolean {
  if (!inBounds(tox, toy)) return false;
  // Already in this sector — don't burn FTL energy on a no-op jump.
  if (tox === s.universe.galaxyX && toy === s.universe.galaxyY) return false;
  if (s.universe.ftlEnergy < WARP_ENERGY) return false;
  s.universe.ftlEnergy -= WARP_ENERGY;
  s.universe.galaxyX = tox;
  s.universe.galaxyY = toy;
  s.universe.navTarget = null;
  const { systemSeed } = starAtSector(tox, toy);
  s.universe.systemSeed = systemSeed;
  s.universe.bodyIndex = 0;
  const g = initialGalacticPositionLy(tox, toy, systemSeed);
  s.universe.gposX = g.x;
  s.universe.gposY = g.y;
  s.universe.gposZ = g.z;
  ensureDiscovered(s, sectorId(tox, toy));
  s.universe.viewMode = "solar";
  return true;
}

export function returnToSurface(s: GameState): void {
  s.universe.viewMode = "surface";
}

export function openGalaxy(s: GameState): void {
  s.universe.viewMode = "galaxy";
  ensureDiscovered(s, sectorId(s.universe.galaxyX, s.universe.galaxyY));
}

export function openSolar(s: GameState): void {
  s.universe.viewMode = "solar";
  ensureDiscovered(s, sectorId(s.universe.galaxyX, s.universe.galaxyY));
}
