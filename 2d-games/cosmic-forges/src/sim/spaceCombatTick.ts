import type { EventBus } from "../core/eventBus";
import { runAerialCoreCollisions, runAerialEnemyMovement } from "./systems/movementAndCombat";
import { runSpaceWaves } from "./systems/spaceWave";
import type { GameState } from "./types";

/**
 * M5: advances **global tick** and runs only deep-orbit hostiles (no economy / ground sim).
 * Used from the main loop when the core is in deep SOI with {@link GameState.spaceCombatEnabled}.
 */
export function spaceCombatStep(s: GameState, bus: EventBus): void {
  s.tick += 1;
  if (s.gameOver) return;
  runSpaceWaves(s, (e) => bus.emit(e));
  runAerialEnemyMovement(s);
  runAerialCoreCollisions(s, (e) => bus.emit(e));
}
