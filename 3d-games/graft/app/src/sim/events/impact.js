// THE IMPACT — a swarm crosses the sky, and some of it lands.
//
// The planet's terrain mesh is built once and shared with the player's feet, so
// nothing here deforms it. A crater is therefore an OBJECT: it goes in the
// catalog, it is saved with everything else, and it is still sitting there an
// hour later. The event's own `ev.data` carries the swarm in flight and a record
// of every strike, which is what the renderer draws and what a save restores.
//
// The bargain: a strike destroys what is standing near it AND opens the ground.
// So a bombardment is a disaster and a mineral seam at the same time, which is
// why somebody would raise a Lure Mast and call one down on purpose.
//
// Pure: no DOM, no three.js, no Math.random, no Date.

import { C } from '../const.js';
import * as P from '../planet.js';
import { KIND, spec } from '../catalog.js';

/** Every number this event invents. The integrator merges nothing from here. */
export const IMPACT = {
  spread: 74,        // world units around the centre that the swarm falls in
  entrySpread: 26,   // how far off-target a rock enters, so it comes in at an angle
  fall: 9,           // SECONDS in the sky before it lands. a strike with no
                     // warning is just a number changing, so this is the point
  high: 132,         // altitude above the ground it appears at
  burn: 44,          // a passer burns out this far up and never touches down
  // The world holds ~1,000 things over 175,000 square units, so a blast has to
  // be WIDE to be destruction rather than a statistic: at 7.5 a strike took out
  // one object on average and read as nothing at all.
  blast: 12.5,       // everything standing within this of a strike goes
  crater: 11.0,      // the scar the renderer draws around it
  seeds: 5,          // shards thrown up around the rim
  ring: [14.0, 18.5], // and where they land: outside the blast, so they survive it
  // strikes and passers per second, by phase. streaks announce; heavy delivers.
  strikes: { streaks: 0, first: 0.10, heavy: 0.22, passes: 0 },
  passers: { streaks: 0.55, first: 0.30, heavy: 0.45, passes: 0.22 },
  dur: { streaks: 22, first: 42, heavy: 68, passes: 40 },
  maxLive: 36,       // rocks in the sky at once, so a save can never balloon
  minAge: 1500,      // late: the world has to be worth wrecking first
};

export const CRATER = 'crater';
export const LURE = 'luremast';

// Counter bases for api.rand. scatterNear consumes 2n and 2n+1, so these are
// kept far enough apart that no two draws can ever be the same number.
const R_TARGET = 1000;
const R_ENTRY = 200000;
const R_ANGLE = 700000;
const R_RADIUS = 900000;

/** The trigger, and what a strike leaves behind. Shaped like catalog.js. */
export const CATALOG_ADDITIONS = [
  {
    id: LURE,
    name: 'Lure Mast',
    verb: 'raise',
    blurb: 'It sings at the sky until the sky answers. What comes down is worth more than what it lands on.',
    needs: { alloy: 30, shard: 80, biomass: 40 },
    unlock: 'town',
    stages: [
      { name: 'anchor', at: 0, h: 2.5 },
      { name: 'strung', at: 120, h: 9.0 },
      { name: 'calling', at: 360, h: 10.0 },
    ],
    harvest: null,
    maxSlope: 0.3,
    settles: 0,
    neon: true,
    triggers: 'impact',      // the canonical link is EVENT.trigger; this is the hint
  },
  {
    id: CRATER,
    name: 'Crater',
    verb: 'scour',
    blurb: 'A hole punched from orbit. Let it cool, then take what it turned up.',
    needs: { alloy: 6 },
    stages: [
      { name: 'molten', at: 0, h: 1.8 },
      { name: 'cooling', at: 70, h: 1.5 },
      { name: 'scar', at: 240, h: 1.3 },
    ],
    // no regrow: you mine it out once and the ground is spent. A crater that
    // paid for ever would make the Lure Mast the only building in the game.
    harvest: { shard: 26, alloy: 3 },
    maxSlope: 0.9,
    settles: 0,
  },
];

/** Announcing is cosmetic; the engine permits fire() with no world hooks, and a
    line of text must never be the thing that takes an event down. */
function tell(api, text) {
  try { api.say(text); } catch (e) { /* no hooks: nobody is listening */ }
}

const PHASE_LINE = {
  streaks: 'Something is crossing the sky, and it is getting brighter.',
  first: 'The first of them comes down.',
  heavy: 'The whole sky is falling now.',
  passes: 'The last of them burn out overhead.',
};

/** Put a rock in the sky. It lands IMPACT.fall seconds from now, or burns out. */
function spawn(w, ev, api, strike) {
  const d = ev.data;
  const i = d.n++;
  const centre = ev.dir || P.v(0, 1, 0);
  const target = api.scatterNear(centre, IMPACT.spread, ev, R_TARGET + i);
  const entry = api.scatterNear(target, IMPACT.entrySpread, ev, R_ENTRY + i);
  d.m.push({
    n: i,
    s: strike ? 1 : 0,
    tx: +target.x.toFixed(6), ty: +target.y.toFixed(6), tz: +target.z.toFixed(6),
    ex: +entry.x.toFixed(6), ey: +entry.y.toFixed(6), ez: +entry.z.toFixed(6),
    t0: w.t,
    tl: w.t + IMPACT.fall,
  });
}

/** A rock arrives. This is the only place the world changes. */
function land(w, ev, api, m) {
  const dir = P.v(m.tx, m.ty, m.tz);
  const sea = P.heightAt(dir) < C.planet.sea;
  ev.data.c.push({ x: m.tx, y: m.ty, z: m.tz, t: w.t, sea: sea ? 1 : 0 });

  // The water swallows it. Nothing is destroyed and nothing is exposed, which
  // is why a Lure Mast is worth raising inland.
  if (sea) return;

  for (const o of api.near(dir, IMPACT.blast)) api.destroy(o);
  api.place(CRATER, dir, 0);

  // The valuable half: the strike throws mineral up out of the ground, outside
  // its own blast so it survives the thing that made it.
  const b = P.basis(dir);
  const maxSlope = spec(KIND.CRYSTAL).maxSlope;
  const spin = api.rand(ev, R_ANGLE + m.n) * Math.PI * 2;
  for (let k = 0; k < IMPACT.seeds; k++) {
    const a = spin + (k / IMPACT.seeds) * Math.PI * 2;
    const f = api.rand(ev, R_RADIUS + m.n * 8 + k);
    const rr = IMPACT.ring[0] + (IMPACT.ring[1] - IMPACT.ring[0]) * f;
    const off = P.add(P.mul(b.east, Math.cos(a) * rr), P.mul(b.north, Math.sin(a) * rr));
    const at = P.norm(P.add(P.mul(dir, C.planet.radius), off));
    if (P.heightAt(at) < C.planet.sea) continue;     // never a spire in the sea
    if (P.slopeAt(at) > maxSlope) continue;
    api.place(KIND.CRYSTAL, at, 0);
  }
  ev.data.hit++;
}

export const EVENT = {
  id: 'impact',
  name: 'the Bombardment',
  blurb: 'A swarm is crossing the sky, and some of it is going to land.',
  minAge: IMPACT.minAge,
  weight: 1,
  trigger: LURE,
  phases: [
    { id: 'streaks', at: 0 },
    { id: 'first', at: IMPACT.dur.streaks },
    { id: 'heavy', at: IMPACT.dur.first },
    { id: 'passes', at: IMPACT.dur.heavy, lasts: IMPACT.dur.passes },
  ],

  start(w, ev) {
    ev.data.m = [];      // rocks in the sky: the warning, and what the fx draws
    ev.data.c = [];      // every strike, kept so a save still knows where they fell
    ev.data.n = 0;       // rocks spawned so far, and the seed for all of them
    ev.data.hit = 0;     // strikes that actually bit ground
    ev.data.sa = 0;      // spawn accumulators
    ev.data.pa = 0;
  },

  onPhase(w, ev, phase, api) {
    if (PHASE_LINE[phase]) tell(api, PHASE_LINE[phase]);
  },

  tick(w, ev, dt, api) {
    const d = ev.data;
    if (!d.m) return;                       // a save written before start() ran
    const phase = ev.def.phases[ev.phase].id;

    d.sa += (IMPACT.strikes[phase] || 0) * dt;
    while (d.sa >= 1) {
      d.sa -= 1;
      if (d.m.length < IMPACT.maxLive) spawn(w, ev, api, 1);
    }
    d.pa += (IMPACT.passers[phase] || 0) * dt;
    while (d.pa >= 1) {
      d.pa -= 1;
      if (d.m.length < IMPACT.maxLive) spawn(w, ev, api, 0);
    }

    for (let i = d.m.length - 1; i >= 0; i--) {
      const m = d.m[i];
      if (w.t < m.tl) continue;
      d.m.splice(i, 1);
      if (m.s) land(w, ev, api, m);
    }
  },

  end(w, ev, api) {
    ev.data.m = [];
    const n = ev.data.hit || 0;
    tell(api, n
      ? `It passes, and leaves ${n} holes in the ground with something in the bottom of each.`
      : 'It passes, and every last one of them went into the sea.');
  },
};

export default EVENT;
