import { RELAY_SCIENCE_PER_TICK, SCIENCE_TIER_1, SCIENCE_TIER_2 } from "../../content/tech";
import type { GameEvent } from "../../core/eventBus";
import { countBuildings } from "../buildingCounts";
import type { GameState } from "../types";

/**
 * Auto-unlock tiers from accumulated science; relay uplinks add passive science every tick.
 */
export function runTechProgress(s: GameState, emit: (e: GameEvent) => void): void {
  if (s.gameOver) return;
  const relays = countBuildings(s, "relay");
  s.science += relays * RELAY_SCIENCE_PER_TICK;
  const prev = s.techTier;
  if (s.science >= SCIENCE_TIER_2) s.techTier = 2;
  else if (s.science >= SCIENCE_TIER_1) s.techTier = 1;
  if (s.techTier > prev) emit({ type: "tech", tier: s.techTier });
}
