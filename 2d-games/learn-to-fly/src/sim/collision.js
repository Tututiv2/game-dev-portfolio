// §4.9 — SWEEP EVERY STEP.
//
// At 100 m/s and 120 Hz the penguin moves 0.83 m per step and a snowman is about 2 m wide.
// Point sampling misses it *some* of the time, which is the worst bug class there is: an
// obstacle that usually works. Test 10 fires the penguin at every obstacle at 250 mph from
// 200 angles and allows zero misses.

import { segmentAabb } from './mathx.js';
import { applyHit, DAMAGE } from './obstacles.js';
import { STATE } from './physics.js';

/** Nearest obstacle the step's swept segment enters, or null. */
export function sweepObstacles(p, world, T) {
  const xa = Math.min(p.prevX, p.x) - T.PENGUIN_HALF_W;
  const xb = Math.max(p.prevX, p.x) + T.PENGUIN_HALF_W;
  const near = world.obstaclesNear(xa, xb);

  let best = null, bestT = Infinity;
  for (const o of near) {
    if (o.destroyed) continue;
    const t = segmentAabb(
      p.prevX, p.prevY, p.x, p.y,
      o.x0 - T.PENGUIN_HALF_W, o.y0 - T.PENGUIN_HALF_H,
      o.x1 + T.PENGUIN_HALF_W, o.y1 + T.PENGUIN_HALF_H,
    );
    if (t >= 0 && t < bestT) { bestT = t; best = o; }
  }
  return best === null ? null : { obstacle: best, t: bestT };
}

/**
 * The `hooks.obstacles` implementation physics.step() calls. Kept out of physics.js so LTF1
 * can run with no obstacle system at all — it simply passes no hook.
 *
 * Destroyed → you punch through and keep most of your speed.
 * Survived  → you stop dead against it and tumble.
 */
export function makeObstacleHook(onEvent) {
  return function obstacleHook(p, L, world, T) {
    const hit = sweepObstacles(p, world, T);
    if (hit === null) return;
    const o = hit.obstacle;

    // The bomb is fused for what it is FOR. A contact fuse that goes off on the first thing
    // the rig touches means carrying one into the rocky hill consumes it, deals no blast
    // (the hill does not accept it) and ends the run — the player watches their 9,000-dollar
    // payload do literally nothing. Arming it only on targets that accept blast keeps the
    // §7.4 bargain intact (one use, destroys your own rig) without that.
    const detonate = L.bomb && !p.bombUsed && o.needs.includes(DAMAGE.BOMB);
    const res = applyHit(o, p, L, T, detonate);
    p.hits++;

    // land the penguin at the contact point rather than inside the obstacle
    p.x = p.prevX + (p.x - p.prevX) * hit.t;
    p.y = p.prevY + (p.y - p.prevY) * hit.t;

    if (detonate) {
      p.bombUsed = true;
      p.gliderHp = 0;
      p.state = STATE.TUMBLE;
      p.omega = T.TUMBLE_SPIN;
      if (p.collectEvents) p.events.push({ type: 'bomb:detonate', obstacle: o.id, dealt: res.dealt });
    }

    if (res.destroyed) {
      p.destroyed.push(o.id);
      p.vx *= T.OBSTACLE_PASS_LOSS;
      p.vy *= T.OBSTACLE_PASS_LOSS;
      if (p.collectEvents) p.events.push({ type: 'obstacle:destroyed', obstacle: o.id, kind: o.kind });
    } else {
      p.vx *= -T.OBSTACLE_STOP_LOSS;
      p.vy *= T.OBSTACLE_STOP_LOSS;
      p.state = STATE.TUMBLE;
      p.omega = T.TUMBLE_SPIN;
      if (p.collectEvents) {
        p.events.push({ type: 'obstacle:hit', obstacle: o.id, dealt: res.dealt, blocked: res.blocked, hp: o.hp });
      }
    }
    if (onEvent) onEvent(p, o, res);
  };
}
