import {
  runAerialCoreCollisions,
  runAerialEnemyMovement,
  runCoreCollisions,
  runSurfaceEnemyMovement,
  runSentryFire,
} from "./systems/movementAndCombat";
import { runPollution } from "./systems/pollution";
import { runProduction } from "./systems/production";
import { runTechProgress } from "./systems/techProgress";
import { runWaves } from "./systems/wave";
import type { GameState } from "./types";
import type { EventBus } from "../core/eventBus";

/**
 * One fixed (30 Hz) tick: production, waves, hostiles, etc.
 * Skipped in the main loop while the core is in **deep orbit** without M5 space combat — see
 * `main.ts`. Mid-air near the world still runs the sim; ground waves never spawn in any flight.
 */
export function simStep(s: GameState, bus: EventBus): void {
  s.tick += 1;
  if (s.gameOver) return;
  runProduction(s);
  runPollution(s);
  runTechProgress(s, (e) => bus.emit(e));
  runWaves(s, (e) => bus.emit(e));
  runSurfaceEnemyMovement(s);
  runAerialEnemyMovement(s);
  runSentryFire(s);
  runCoreCollisions(s, (e) => bus.emit(e));
  runAerialCoreCollisions(s, (e) => bus.emit(e));
}
