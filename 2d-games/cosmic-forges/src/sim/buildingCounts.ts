import type { BuildingKind, GameState } from "./types";

export function countBuildings(s: GameState, kind: BuildingKind): number {
  let n = 0;
  for (const b of s.buildings) {
    if (b.type === kind) n += 1;
  }
  return n;
}

export function countExtractors(s: GameState): number {
  return countBuildings(s, "extractor");
}
