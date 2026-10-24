// THE WORMHOLE — a civilisation on your planet tears a hole to somewhere else.
//
// It is caused by the town you grew: the ring goes up at the edge of your
// biggest settlement, because a place with enough people in it eventually
// decides to do something you did not ask for. It charges, it opens, things
// come through, it collapses.
//
// What comes through is GOOD. Seeds of kinds already in the catalog, laid out
// in arrangements nothing on this planet would ever make on its own — a perfect
// ring of saplings, a spiral of blooms, spokes of spires, an avenue leading
// away from nowhere. Plus a gift of resources, and a kind that did not exist
// here before, which is what changes what you can do afterwards.
//
// Pure: no DOM, no three.js, no Math.random, no Date. Randomness is api.rand.

import { C } from '../const.js';
import * as P from '../planet.js';
import { KIND, spec } from '../catalog.js';
// Namespace import on purpose: registry -> here -> world.js can be a cycle once
// the integrator wires events into world.js, and a namespace is the form that
// survives one (nothing is read at module-evaluation time).
import * as WORLD from '../world.js';

/* ------------------------------------------------------------- my numbers */
// Every number this event invents. The integrator merges nothing from here into
// const.js -- it is exported so the renderer can share the ring's size.
export const CONST = {
  minAge: 900,             // a quarter of an hour before it may happen on its own
  weight: 1,

  ring: 7.4,               // radius of the standing ring, world units
  build: 96,               // they raise it
  charge: 78,              // it charges
  open: 168,               // it is open
  collapse: 34,            // it closes

  waveEvery: 18,           // seconds between things coming through
  spacing: 1.9,            // never drop a seed on top of something

  siteRadius: 30,          // how far outside the settlement the ring stands
  siteRings: 4,            // candidate distances tried, outward
  siteSpokes: 24,          // candidate bearings tried at each distance
  siteScan: 700,           // fallback: candidate directions over the whole world
  siteSlope: 0.30,         // the ring needs flat ground to stand on

  arrival: { biomass: 70, shard: 50, alloy: 14 },   // when it opens
  trickle: { biomass: 6, shard: 5 },                // with each wave
  parting: { biomass: 40, shard: 40, alloy: 26 },   // when it closes

  markers: 9,              // spires left standing where the ring stood
};

/* --------------------------------------------------- what the rift leaves */
// A new kind and the machine that calls the rift. Shaped exactly like the
// entries in catalog.js; the integrator merges this array.
//
// LANTERN is the point of the whole event: it comes through the hole, and once
// it has, you may grow it yourself. It takes ground far too steep for anything
// else, so a rift makes the mountains worth walking up.
//
// NOTE the names: the obvious ones were taken. `riftengine` is the example in
// api.js and a sibling event already holds it, and `rift` is already somebody
// else's unlock token -- two catalog entries with one id is a silent merge, not
// an error, because spec() returns the first match.
export const LANTERN = 'lantern';
export const WORMGATE = 'wormgate';
export const UNLOCK = 'wormhole';           // set by this event, and only by it

export const CATALOG_ADDITIONS = [
  {
    id: WORMGATE,
    name: 'Gate',
    verb: 'raise',
    blurb: 'It points at nothing and it is very loud. A town will finish what it starts.',
    needs: { alloy: 40, shard: 60, biomass: 30 },
    unlock: 'town',
    stages: [
      { name: 'cradle', at: 0, h: 2.2 },
      { name: 'coiled', at: 130, h: 5.4 },
      { name: 'singing', at: 340, h: 7.0 },
    ],
    harvest: null,
    maxSlope: 0.26,
    settles: 0,
    neon: true,
    triggers: 'wormhole',        // read by the integrator: finishing one fires the event
  },
  {
    id: LANTERN,
    name: 'Lantern',
    verb: 'plant',
    blurb: 'It came through the hole. It roots on ground nothing here will hold, and it pays.',
    needs: { shard: 14 },
    unlock: UNLOCK,
    stages: [
      { name: 'husk', at: 0, h: 0.6 },
      { name: 'hung', at: 105, h: 2.2 },
      { name: 'lit', at: 300, h: 3.1 },
    ],
    harvest: { shard: 6 },
    regrow: 1,
    maxSlope: 0.88,              // the mountains, which nothing else will take
    produces: { alloy: 1, every: 40 },
    settles: 0,
    neon: true,
  },
];

const ADDED = {};
for (const c of CATALOG_ADDITIONS) ADDED[c.id] = c;

/** The catalog entry for a kind, whether or not the additions are merged yet. */
function kindSpec(kind) { return spec(kind) || ADDED[kind] || null; }

/* ------------------------------------------------------------- geometry */

/** A unit direction `ex` east and `ny` north of a centre, in world units. */
function offset(centre, b, ex, ny) {
  return P.norm(P.add(P.mul(centre, C.planet.radius),
    P.add(P.mul(b.east, ex), P.mul(b.north, ny))));
}

// The arrangements. This is the whole reason the rift reads as somebody else's
// work: nothing on this planet grows in a circle.
function shapePoints(shape, centre, cfg, turn) {
  const b = P.basis(centre);
  const out = [];
  const n = cfg.n;
  if (shape === 'ring') {
    for (let i = 0; i < n; i++) {
      const a = turn + (i / n) * Math.PI * 2;
      out.push(offset(centre, b, Math.cos(a) * cfg.r, Math.sin(a) * cfg.r));
    }
  } else if (shape === 'spiral') {
    for (let i = 0; i < n; i++) {
      const t = i / (n - 1 || 1);
      const a = turn + t * Math.PI * 2 * 2.5;
      const r = cfg.r + (cfg.r2 - cfg.r) * t;
      out.push(offset(centre, b, Math.cos(a) * r, Math.sin(a) * r));
    }
  } else if (shape === 'rays') {
    const spokes = 6, per = Math.max(1, Math.round(n / spokes));
    for (let s = 0; s < spokes; s++) {
      const a = turn + (s / spokes) * Math.PI * 2;
      for (let i = 0; i < per; i++) {
        const r = cfg.r + (cfg.r2 - cfg.r) * (i / (per - 1 || 1));
        out.push(offset(centre, b, Math.cos(a) * r, Math.sin(a) * r));
      }
    }
  } else if (shape === 'avenue') {
    // two straight rows leading away, which is the strangest of the four
    const a = turn;
    const ux = Math.cos(a), uy = Math.sin(a);
    const half = Math.max(1, Math.round(n / 2));
    for (let i = 0; i < half; i++) {
      const r = cfg.r + (cfg.r2 - cfg.r) * (i / (half - 1 || 1));
      for (const side of [-1, 1]) {
        out.push(offset(centre, b,
          ux * r - uy * side * cfg.wide, uy * r + ux * side * cfg.wide));
      }
    }
  }
  return out;
}

// In order. Each wormhole runs the same programme, so what comes through is
// legible rather than a shrug -- what varies is where it lands.
const WAVES = [
  { kind: KIND.TREE, shape: 'ring', n: 20, r: 15 },
  { kind: KIND.BLOOM, shape: 'spiral', n: 40, r: 9, r2: 26 },
  { kind: LANTERN, shape: 'ring', n: 9, r: 11 },
  { kind: KIND.CRYSTAL, shape: 'rays', n: 24, r: 13, r2: 29 },
  { kind: KIND.KELP, shape: 'ring', n: 22, r: 34 },
  { kind: KIND.TREE, shape: 'avenue', n: 18, r: 12, r2: 38, wide: 3.2 },
  { kind: KIND.BLOOM, shape: 'ring', n: 28, r: 21 },
  { kind: LANTERN, shape: 'rays', n: 18, r: 16, r2: 28 },
];

/* ------------------------------------------------------------ placement */

/** Put one seed down if this planet will take it there. Returns true if it went. */
function drop(w, ev, api, kind, dir, stage) {
  const s = kindSpec(kind);
  if (!s) return false;
  const h = P.heightAt(dir);
  const underwater = h < C.planet.sea;
  // the same rule the player is held to: kelp under the sea, nothing else
  if (underwater !== !!s.underwater) return false;
  if (s.maxHeight != null && h > s.maxHeight) return false;
  if (P.slopeAt(dir) > s.maxSlope) return false;
  if (api.near(dir, CONST.spacing).length) return false;
  const o = api.place(kind, dir, stage);
  if (!o) return false;
  ev.data.placed = (ev.data.placed || 0) + 1;
  return true;
}

/* ----------------------------------------------------------- where it is */

function standable(dir) {
  const h = P.heightAt(dir);
  if (h < C.planet.sea + C.planet.shore) return false;   // not in the water, not on the beach
  if (h > C.planet.alpine) return false;                 // not on bare rock
  return P.slopeAt(dir) <= CONST.siteSlope;
}

/** The biggest settlement, or null. Deterministic: no ties broken by order alone. */
function biggest(w) {
  let best = null;
  for (const st of WORLD.settlements(w)) {
    if (!st.tier) continue;
    if (!best || st.count > best.count
      || (st.count === best.count && st.dir.x > best.dir.x)) best = st;
  }
  return best;
}

/**
 * Where the ring stands. Beside the largest settlement if there is one, because
 * a wormhole your own town opened is a far better story than one that arrives.
 * Falling back is not optional: the engine's default site is a uniform point on
 * the sphere and ~47% of this planet is ocean.
 */
function chooseSite(w, ev) {
  const st = biggest(w);
  const anchor = st ? st.dir : (ev.dir || P.v(0, 1, 0));
  const near0 = st ? CONST.siteRadius : 0;

  const b = P.basis(anchor);
  for (let ring = 0; ring < CONST.siteRings; ring++) {
    const r = near0 + ring * 11;
    if (r < 1e-6) { if (standable(anchor)) return anchor; continue; }
    for (let i = 0; i < CONST.siteSpokes; i++) {
      const a = (i / CONST.siteSpokes) * Math.PI * 2;
      const d = offset(anchor, b, Math.cos(a) * r, Math.sin(a) * r);
      if (standable(d)) return d;
    }
  }

  // nothing within reach: take the nearest standable ground on the whole world
  let best = null, bestArc = Infinity;
  const scan = P.scatter(CONST.siteScan, 0.37);
  for (const d of scan) {
    if (!standable(d)) continue;
    const a = P.arc(d, anchor);
    if (a < bestArc) { bestArc = a; best = d; }
  }
  return best || anchor;
}

/* ------------------------------------------------------------ the event */

export const wormhole = {
  id: 'wormhole',
  name: 'the Opening',
  blurb: 'Your people have started raising a ring, and nobody asked you.',
  minAge: CONST.minAge,
  weight: CONST.weight,
  trigger: WORMGATE,

  phases: [
    { id: 'ring', at: 0 },
    { id: 'charge', at: CONST.build },
    { id: 'open', at: CONST.charge },
    { id: 'collapse', at: CONST.open, lasts: CONST.collapse },
  ],

  start(w, ev, api) {
    ev.dir = chooseSite(w, ev);
    const st = biggest(w);
    ev.data = {
      from: st ? st.tier : 'nobody',
      k: 0,                 // waves that have come through
      placed: 0,            // things that came through and took root
      next: 0,              // world time of the next wave
      flash: 0,             // world time of the last arrival, for the renderer
      unlocked: 0,
    };
  },

  onPhase(w, ev, phase, api) {
    if (phase === 'ring') {
      const from = ev.data.from;
      api.say(from === 'nobody'
        ? 'Something is putting up a ring on the far side of the world.'
        : `The ${from} has begun raising a ring, and it is not one of yours.`);
    } else if (phase === 'charge') {
      api.say('The ring is charging. You can see the ground under it from here.');
    } else if (phase === 'open') {
      api.say('It is open, and it is not dark on the other side.');
      api.grant(CONST.arrival);
      ev.data.next = w.t;              // first wave immediately
    } else if (phase === 'collapse') {
      api.say('The hole is closing, and it is leaving its things behind.');
      api.grant(CONST.parting);
      // The only thing this event changes that is not an object: what you may
      // now grow. There is no api verb for an unlock, so it is set the same way
      // a settlement sets its own tier.
      w.seen = w.seen || {};
      if (!w.seen[UNLOCK]) {
        w.seen[UNLOCK] = true;
        ev.data.unlocked = 1;
        api.say('Whatever came through has taken root. You know how to grow a Lantern now.');
      }
    }
  },

  tick(w, ev, dt, api) {
    if (ev.def.phases[ev.phase].id !== 'open') return;
    // The schedule counts from itself, not from the clock, so a long offline
    // catch-up delivers exactly the same waves a live session does.
    let guard = 0;
    while (ev.data.k < WAVES.length && w.t >= ev.data.next && guard++ < WAVES.length) {
      const k = ev.data.k++;
      const cfg = WAVES[k];
      const turn = api.rand(ev, 40 + k) * Math.PI * 2;
      let landed = 0;
      for (const d of shapePoints(cfg.shape, ev.dir, cfg, turn)) {
        if (drop(w, ev, api, cfg.kind, d, 0)) landed++;
      }
      if (landed) {
        api.grant(CONST.trickle);
        ev.data.flash = w.t;
      }
      ev.data.next += CONST.waveEvery;
    }
  },

  end(w, ev, api) {
    // A ring of spires stands where the ring stood, so the place is still a
    // place a week later when you walk back to it.
    const b = P.basis(ev.dir);
    for (let i = 0; i < CONST.markers; i++) {
      const a = (i / CONST.markers) * Math.PI * 2;
      const d = offset(ev.dir, b, Math.cos(a) * CONST.ring, Math.sin(a) * CONST.ring);
      drop(w, ev, api, KIND.CRYSTAL, d, 1);
    }
    w.seen = w.seen || {};
    w.seen[UNLOCK] = true;
    api.say(`The ring is gone. ${ev.data.placed} things came through and stayed.`);
  },
};

export default wormhole;
