import { EXTRACTOR } from "../../content/buildings";
import { LOGISTICS_OVERFLOW_EFF, logisticsCap } from "../../content/tech";
import { countExtractors } from "../buildingCounts";
import type { GameState } from "../types";

export function runProduction(s: GameState): void {
  if (s.gameOver) return;
  if (s.tick <= 0 || s.tick % EXTRACTOR.outputEveryTicks !== 0) return;
  const n = countExtractors(s);
  if (n === 0) return;
  const cap = logisticsCap(s.techTier);
  const full = Math.min(n, cap);
  const over = Math.max(0, n - cap);
  const effective = full + over * LOGISTICS_OVERFLOW_EFF;
  s.ferriumCarry += effective;
  let whole = Math.floor(s.ferriumCarry);
  s.ferriumCarry -= whole;
  s.ferrium += whole;
  s.science += n * 0.75 + whole * 0.25;
}
