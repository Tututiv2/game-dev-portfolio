import {
  POLLUTION_PER_EXTRACTOR_TICK,
  PURIFIER_SCRUB_PER_TICK,
  RELAY_POLLUTION_PER_TICK,
  SENTRY_POLLUTION_PER_TICK,
  pollutionDecay,
  pollutionGenMult,
} from "../../content/tech";
import { countBuildings, countExtractors } from "../buildingCounts";
import type { GameState } from "../types";

const MAX_P = 220;

/**
 * Industry footprint: extractors and relays; purifiers scrub; decay by tier.
 */
export function runPollution(s: GameState): void {
  if (s.gameOver) return;
  const e = countExtractors(s);
  const r = countBuildings(s, "relay");
  const t = countBuildings(s, "sentry");
  const p = countBuildings(s, "purifier");
  const m = pollutionGenMult(s.techTier);
  s.pollution += e * POLLUTION_PER_EXTRACTOR_TICK * m;
  s.pollution += r * RELAY_POLLUTION_PER_TICK;
  s.pollution += t * SENTRY_POLLUTION_PER_TICK;
  s.pollution -= p * PURIFIER_SCRUB_PER_TICK;
  s.pollution -= pollutionDecay(s.techTier) * (1.0 + s.pollution * 0.008);
  s.pollution = Math.max(0, Math.min(MAX_P, s.pollution));
}
