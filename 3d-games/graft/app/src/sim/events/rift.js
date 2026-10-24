// THE RIFT — a patch of the planet stops being part of this universe.
//
// A hairline crack opens somewhere on the surface, widens, and for a few
// minutes the ground inside it belongs to somewhere else: growth runs at
// another speed, things flicker between kinds, and a plant nobody here has a
// name for pushes up through the soil. Then it seals, and everything it touched
// stays the way it left it.
//
// THE RULE still holds. Everything the rift creates is a catalog kind, and both
// kinds it needs are exported below in CATALOG_ADDITIONS with a way for the
// player to make them: the Vane you carry out of the rift, and the Seam you
// raise to tear a new one open on purpose.
//
// Pure: no DOM, no three.js, no Math.random, no Date.

import { CATALOG, KIND } from '../catalog.js';

/* ------------------------------------------------------------- the numbers */
// Every number this event invents. The integrator does NOT merge these into
// const.js -- they are the rift's own and nothing else reads them.
export const RIFT = {
  radius: 26,          // full extent in world units: ~7% of the way round, so
                       // it is a place you walk to rather than stumble into
  hairline: 2.6,       // how wide the crack is before it opens
  closeTo: 4.0,        // what it shrinks back to before it seals
  growth: 6.0,         // stages arrive this many times faster inside
  step: 0.5,           // seconds between passes over the interior; doing this
                       // every tick is an acos per object per 33 ms for nothing
  swapEvery: 8,        // seconds between one thing inside becoming another
  seedEvery: 16,       // seconds between the far side growing a Vane of its own
  maxSeeds: 12,
  siteScan: 500,       // candidate points considered when moving off the water
  maxScars: 12,        // how many closed rifts the world remembers
  phase: { crack: 55, opening: 65, other: 240, closing: 55 },
};

/** The two kinds this event needs. The integrator adds these to catalog.js KIND. */
export const RIFT_KIND = {
  ANOMALY: 'anomaly',
  ENGINE: 'riftengine',
};

/* ------------------------------------------------------------- the catalog */
// Shaped exactly like the entries in catalog.js. The integrator merges these.
export const CATALOG_ADDITIONS = [
  {
    id: RIFT_KIND.ANOMALY,
    name: 'Vane',
    verb: 'graft',
    blurb: 'It finished growing somewhere that is not here, and it never stands quite straight.',
    needs: { flux: 2, biomass: 10 },
    unlock: 'rift',          // you cannot plant one until you have seen a rift
    stages: [
      { name: 'splinter', at: 0, h: 0.5 },
      { name: 'leaning', at: 18, h: 1.6 },
      { name: 'vane', at: 55, h: 3.1 },
    ],
    harvest: { flux: 3 },    // the only source of flux in the game
    regrow: 1,
    maxSlope: 0.7,
    settles: 0,
  },
  {
    id: RIFT_KIND.ENGINE,
    name: 'Seam',
    verb: 'raise',
    blurb: 'It does not open the way. It only makes this patch of ground thin enough for something else to.',
    needs: { alloy: 18, shard: 45, flux: 8 },
    unlock: 'rift',
    stages: [
      { name: 'anchor', at: 0, h: 1.4 },
      { name: 'strung', at: 120, h: 3.6 },
      { name: 'thin', at: 330, h: 4.0 },
    ],
    harvest: null,
    maxSlope: 0.3,
    settles: 0,
    // INTEGRATOR: one generic line in world.js grow() reads this --
    //   if (s.tears && finalStage(o) && !o.tore) { o.tore = 1; fire(w, s.tears, o.dir); }
    // which works for every future event's trigger, not just this one.
    tears: 'rift',
  },
];

// What may flicker into what. Buildings are left alone -- a house that turns
// into a tree while somebody lives in it is a joke, not a rift -- and so is
// anything under water, because kelp is the only kind allowed down there and
// that rule holds even when the universe does not.
const FLICKER = [KIND.BLOOM, KIND.CRYSTAL, KIND.TREE, RIFT_KIND.ANOMALY];

/** Works whether or not CATALOG_ADDITIONS has been merged yet. */
function specOf(kind) {
  return CATALOG.find(c => c.id === kind) || CATALOG_ADDITIONS.find(c => c.id === kind);
}

/* --------------------------------------------------------------- the event */

/** How wide the tear is right now, given the phase and how far through it is. */
function targetRadius(w, ev) {
  const p = ev.def.phases[ev.phase].id;
  const t = ev.phase + 1 < ev.def.phases.length
    ? Math.min(1, (w.t - ev.phaseAt) / (ev.def.phases[ev.phase + 1].at || 1))
    : Math.min(1, (w.t - ev.phaseAt) / RIFT.phase.closing);
  if (p === 'hairline') return RIFT.hairline;
  if (p === 'opens') return RIFT.hairline + (RIFT.radius - RIFT.hairline) * t;
  if (p === 'other') return RIFT.radius;
  return RIFT.radius + (RIFT.closeTo - RIFT.radius) * t;
}

/** How much faster the far side runs, right now. 1 means this universe. */
function speedNow(w, ev) {
  const p = ev.def.phases[ev.phase].id;
  const r = ev.data.r || 0;
  if (p === 'hairline') return 1;
  // the interior speeds up as it widens and slows again as it seals, so the
  // pull of the place is legible from outside without reading a number
  const open = Math.max(0, Math.min(1, (r - RIFT.hairline) / (RIFT.radius - RIFT.hairline)));
  return 1 + (RIFT.growth - 1) * open;
}

/** Dry ground near where the event landed. A rift in the deep sea is a rumour. */
function landNear(ev, api) {
  const P = api.P, C = api.C;
  const floor = C.planet.sea + C.planet.shore;
  if (P.heightAt(ev.dir) >= floor && P.heightAt(ev.dir) <= C.planet.alpine) return ev.dir;
  const pts = P.scatter(RIFT.siteScan, ((ev.t0 * 0.017) % 1) + 0.13);
  let best = null, bd = Infinity;
  for (const d of pts) {
    const h = P.heightAt(d);
    if (h < floor || h > C.planet.alpine) continue;
    const dist = P.arc(d, ev.dir);
    if (dist < bd) { bd = dist; best = d; }
  }
  return best || ev.dir;
}

function canFlicker(o, api) {
  if (FLICKER.indexOf(o.kind) < 0) return false;
  return api.P.heightAt(o.dir) >= api.C.planet.sea;
}

/** One thing inside stops being what it was. This is the change that lasts. */
function flickerOne(w, ev, api) {
  const inside = api.near(ev.dir, ev.data.r).filter(o => canFlicker(o, api));
  if (!inside.length) return false;
  const o = inside[Math.floor(api.rand(ev, 90000 + ev.data.m++) * inside.length)];
  const opts = FLICKER.filter(k => k !== o.kind && specOf(k));
  if (!opts.length) return false;
  const k = opts[Math.floor(api.rand(ev, 90000 + ev.data.m++) * opts.length)];
  const s = specOf(k);
  o.kind = k;
  o.stage = Math.min(o.stage, s.stages.length - 1);
  o.nextAt = o.stage + 1 < s.stages.length
    ? w.t + s.stages[o.stage + 1].at / speedNow(w, ev)
    : Infinity;
  w.rev++;                       // the renderer rebuilds off this and nothing else
  ev.data.swaps++;
  return true;
}

/** The far side grows something of its own. */
function seedOne(w, ev, api) {
  if (ev.data.seeds >= RIFT.maxSeeds) return false;
  for (let i = 0; i < 6; i++) {
    const d = api.scatterNear(ev.dir, ev.data.r * 0.82, ev, ev.data.n++);
    if (api.P.heightAt(d) < api.C.planet.sea) continue;   // never under the water
    const o = api.place(RIFT_KIND.ANOMALY, d, 0);
    if (o) { ev.data.seeds++; return true; }
  }
  return false;
}

/** Everything inside runs on the far side's clock. */
function runInterior(w, ev, api, chunk) {
  const mult = speedNow(w, ev);
  if (mult <= 1.0001) return;
  const pull = (mult - 1) * chunk;
  for (const o of api.near(ev.dir, ev.data.r)) {
    if (!isFinite(o.nextAt)) continue;
    o.nextAt -= pull;
    ev.data.pulled += pull;
  }
}

export const rift = {
  id: 'rift',
  name: 'the Rift',
  blurb: 'A hairline crack has opened in the ground, and the light coming out of it is the wrong colour.',
  minAge: 720,
  weight: 1,
  trigger: RIFT_KIND.ENGINE,
  phases: [
    { id: 'hairline', at: 0 },
    { id: 'opens', at: RIFT.phase.crack },
    { id: 'other', at: RIFT.phase.opening },
    { id: 'closes', at: RIFT.phase.other, lasts: RIFT.phase.closing },
  ],

  start(w, ev, api) {
    ev.dir = landNear(ev, api);
    ev.data.r = RIFT.hairline;
    ev.data.swaps = 0;
    ev.data.seeds = 0;
    ev.data.pulled = 0;
    ev.data.acc = 0;
    ev.data.swapIn = RIFT.swapEvery;
    ev.data.seedIn = RIFT.seedEvery;
    ev.data.n = 1;      // two counters so scatter and flicker never share a draw
    ev.data.m = 1;
    // Having stood next to one is what unlocks both of its kinds. The Vane and
    // the Seam both read this through the ordinary unlock rule.
    w.seen.rift = true;
  },

  onPhase(w, ev, phase, api) {
    if (phase === 'opens') api.say('The crack is widening, and something is growing through it.');
    if (phase === 'other') api.say('Inside the rift nothing is the right colour or the right speed.');
    if (phase === 'closes') api.say('The rift is sealing. Take what you can carry.');
  },

  tick(w, ev, dt, api) {
    ev.data.r = targetRadius(w, ev);

    ev.data.acc += dt;
    if (ev.data.acc >= RIFT.step) {
      const chunk = ev.data.acc;
      ev.data.acc = 0;
      runInterior(w, ev, api, chunk);
    }

    const p = ev.def.phases[ev.phase].id;
    if (p === 'hairline') return;      // nothing is through yet

    ev.data.swapIn -= dt;
    if (ev.data.swapIn <= 0) { ev.data.swapIn += RIFT.swapEvery; flickerOne(w, ev, api); }

    if (p === 'opens' || p === 'other') {
      ev.data.seedIn -= dt;
      if (ev.data.seedIn <= 0) { ev.data.seedIn += RIFT.seedEvery; seedOne(w, ev, api); }
    }
  },

  end(w, ev, api) {
    // Whatever the last moment made of a thing is what it is now.
    flickerOne(w, ev, api);
    for (const o of api.near(ev.dir, ev.data.r)) {
      if (isFinite(o.nextAt) && o.nextAt < w.t) o.nextAt = w.t;
    }
    // The scar. INTEGRATOR: add `rifts: w.rifts` to save() and read it back in
    // load() if you want the closed ones drawn for good.
    w.rifts = w.rifts || [];
    w.rifts.push({
      x: +ev.dir.x.toFixed(6), y: +ev.dir.y.toFixed(6), z: +ev.dir.z.toFixed(6),
      r: +RIFT.radius.toFixed(2), t: +w.t.toFixed(2),
    });
    while (w.rifts.length > RIFT.maxScars) w.rifts.shift();
    api.say('The rift closed. Everything it touched stayed the way it left it.');
  },
};

export default rift;
