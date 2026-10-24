// node tools/energy-probe.mjs
//
// Total specific energy E = v²/2 + G·y must never RISE except while the rocket is burning.
// Kept in the repo because it is the cheapest possible guard against the whole family of
// integration bugs that make a penguin climb for free, and every one of those reads as
// "the flight model feels wrong" long before anyone can say why.

import { TUNING as T } from '../src/data/tuning.js';
import { createState, step, STATE } from '../src/sim/physics.js';
import { createWorld, oceanProvider } from '../src/sim/world.js';
import { makeAutopilot } from '../src/sim/autopilot.js';
import { loadoutFrom, freshLevels } from '../src/data/ltf1.upgrades.js';

const levels = { ...freshLevels(), acceleration: 1, rampHeight: 1, glider: 1, airResistance: 1 };
const L = loadoutFrom(levels, T);
const world = createWorld(oceanProvider(), T);
const p = createState(L, T, { collectEvents: true });
const ap = makeAutopilot(T, { thrustMode: 'hold' });
const input = { left: false, right: false, thrust: false };

const E = () => Math.hypot(p.vx, p.vy) ** 2 * 0.5 + T.G * p.y;
let prevE = E(), worst = 0, worstAt = null;

for (let i = 0; i < 28800 && !p.finished; i++) {
  const c = ap(p, L, T, world);
  input.left = c.left; input.right = c.right; input.thrust = c.thrust;
  const before = { state: p.state, y: p.y, vx: p.vx, vy: p.vy, theta: p.theta };
  step(p, input, L, world, T);
  const e = E();
  const gain = e - prevE;
  if (!p.thrusting && gain > worst) {
    worst = gain;
    worstAt = { i, gain, e, prevE, before, after: { state: p.state, y: p.y, vx: p.vx, vy: p.vy, theta: p.theta }, alpha: p.alpha, cl: p.cl };
  }
  prevE = e;
}

console.log(`\n  finished=${p.finished} reason=${p.endReason} t=${p.t.toFixed(2)}s`);
console.log(`  maxAlt ${(p.maxAlt * T.FT_PER_M).toFixed(0)} ft   dist ${(p.dist * T.FT_PER_M).toFixed(0)} ft   maxSpeed ${(p.maxSpeed * T.MPH_PER_MS).toFixed(0)} mph   bounces ${p.bounces}`);
console.log(`  launch energy would allow apex ${((0.5 * (L.exitMph / T.MPH_PER_MS) ** 2 + T.G * L.rampHeightM) / T.G * T.FT_PER_M).toFixed(0)} ft`);
console.log(`\n  largest un-thrusted energy GAIN in one step: ${worst.toFixed(3)} m2/s2`);
if (worstAt) console.log('  ' + JSON.stringify(worstAt, null, 2).replace(/\n/g, '\n  '));
const counts = {};
for (const e of p.events) counts[e.type] = (counts[e.type] || 0) + 1;
console.log('\n  events:', counts, '\n');
