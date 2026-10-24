// Fixed-timestep accumulator. Sim runs at a constant TPS regardless of render fps.

export const TPS = 10;                 // simulation ticks per second
export const TICK_MS = 1000 / TPS;     // ms per sim tick
export const TICK_DT = 1 / TPS;        // seconds per sim tick

export function createTicker(onTick) {
  let accumulator = 0;
  const MAX_CATCHUP = 200; // safety cap: never run more than 200 sim ticks in one frame

  return function step(frameMs) {
    accumulator += frameMs;
    let ran = 0;
    while (accumulator >= TICK_MS && ran < MAX_CATCHUP) {
      onTick(TICK_DT);
      accumulator -= TICK_MS;
      ran++;
    }
    // discard backlog beyond catch-up cap (e.g. after a long stall)
    if (ran >= MAX_CATCHUP) accumulator = 0;
    return ran;
  };
}
