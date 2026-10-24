// The simulation, with no renderer and no browser input attached.
//
// Splitting this out is what lets selftest.js run a full 10-minute run in a
// fraction of a second and actually MEASURE the balance, instead of me driving
// the real game for ten minutes and guessing.
import { CFG } from './config.js';
import { Field } from './field.js';
import { Harvester } from './harvester.js';
import { Swarm } from './swarm.js';
import { TowerSet, TOWERS } from './towers.js';
import { FX } from './fx.js';
import { lerp } from './util.js';

/** Everything the sim calls on audio, doing nothing. */
export const NULL_AUDIO = {
  setThrottle() {}, shot() {}, thud() {}, horn() {}, hurt() {},
  place() {}, deny() {}, death() {}, toggleMute() { return true; }, start() {},
};

/** Everything the sim reads off input, all false. */
export const NULL_CTRL = { up: false, down: false, left: false, right: false, cut: false, repair: false };

export function createWorld({ audio = NULL_AUDIO, viewRadius = 1440, extras = {} } = {}) {
  return {
    field: new Field(),
    hv: new Harvester(),
    swarm: new Swarm(),
    towers: new TowerSet(),
    fx: new FX(),
    audio,
    towerDefs: TOWERS,
    cam: { x: 0, y: 0 },
    grain: 40,
    harvested: 0,
    killed: 0,
    grainRate: 0,
    time: 0,
    shake: 0,
    selected: -1,
    hoverSlot: -1,
    viewRadius,
    lastHull: CFG.hv.hullMax,
    ...extras,
  };
}

/** One tick. Returns grain cut this step. */
export function stepSim(w, dt, ctrl) {
  w.time += dt;
  w.shake = Math.max(0, w.shake - CFG.shakeDecay * dt * 10);

  const cut = w.hv.update(dt, ctrl, w.field);
  w.grain += cut;
  w.harvested += cut;
  w.grainRate = lerp(w.grainRate, cut / Math.max(dt, 0.0001), Math.min(1, dt * 4));

  if (ctrl.repair && w.grain > 0 && w.hv.hull < CFG.hv.hullMax) {
    const spend = Math.min(w.grain, CFG.repairGrainPerSec * dt);
    w.grain -= spend;
    w.hv.repair((spend / CFG.repairGrainPerSec) * CFG.repairHullPerSec);
  }

  w.swarm.update(dt, w);
  w.towers.update(dt, w);
  w.fx.update(dt);
  return cut;
}

/** Buys the cheapest affordable weapon it can legally mount. Used by the tests
 *  and as the reference for "is the economy solvable at all". */
export function autoBuy(w) {
  const free = [];
  for (let s = 0; s < w.towers.mounted.length; s++) if (!w.towers.mounted[s]) free.push(s);
  if (!free.length) return false;

  let bestDef = -1, bestCost = Infinity;
  for (let i = 0; i < TOWERS.length; i++) {
    if (w.hv.dist < CFG.unlockAt[i]) continue;
    const c = w.towers.costOf(i);
    if (c <= w.grain && c < bestCost) { bestCost = c; bestDef = i; }
  }
  if (bestDef < 0) return false;

  // Alternate rails so coverage is even, back rail first — the fast movers
  // catch you from behind.
  const order = [0, 5, 4, 9, 1, 6, 3, 8, 2, 7];
  const slot = order.find((s) => free.includes(s));
  if (slot === undefined) return false;

  w.grain -= bestCost;
  w.towers.place(slot, bestDef);
  return true;
}
