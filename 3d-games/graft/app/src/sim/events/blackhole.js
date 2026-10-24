// THE BLACK HOLE — the headline cosmic event.
//
// A singularity arrives as a speck among the stars and hangs there getting
// bigger for minutes before it touches anything. That order is the whole point:
// it must be SEEN before it is felt, so the dread is the event and the damage is
// only its last act.
//
// It has a GROUND TRACK: the point on the sphere it is directly above, drifting
// along a great circle. Everything the destruction does and everything the
// renderer draws reads that one function, so the thing overhead and the thing
// being torn apart can never disagree.
//
// PURE: no DOM, no three.js, no Math.random, no Date. Randomness is api.rand.

import { phaseAge } from './api.js';
import * as P from '../planet.js';

/* ------------------------------------------------------------------ numbers */
// Mine, not const.js's: an event owns its own dials so the integrator merges
// one object rather than reconciling a dozen literals.
export const BH = {
  // the four acts, in seconds
  speck: 168,          // a dark point among the stars. nothing happens.
  swell: 156,          // it grows, the sky bends. still nothing is touched.
  pull: 252,           // the taking-apart
  passing: 132,        // it lets go and goes

  trackRate: 0.004,    // radians of ground track per second (~0.47 units/s)
  reach: 32,           // world units either side of the track, at full strength
  pulseEvery: 1.4,     // seconds between grabs
  maxPerPulse: 3,      // and how much it can take in one
  grip: 0.85,          // chance the nearest thing in reach actually goes

  scarR: 3.4,          // a fresh scar
  scarGrow: 0.9,       // how much a repeat hit in the same place widens it
  scarMaxR: 9.0,
  scarSpacing: 7.0,    // closer than this and it widens the last scar instead
  scarCap: 260,        // scars persist for ever, so they must be bounded

  guardRange: 46,      // a live lattice this near the track closes the throat
  skyDist: 900,        // where it hangs, for the renderer
  discMin: 0.9,        // apparent radius of the black disc, at first sight
  discMax: 62,         // and at full swell
};

/* --------------------------------------------------------------- geometry */

/**
 * The axis its ground track turns about. Perpendicular to where it arrived, so
 * the track is a great circle through the arrival point.
 *
 * It is chosen ONCE, in start(), and lives in ev.data -- which is what the save
 * carries. Everything afterwards (including the renderer, which has no api) can
 * work it out from the event alone.
 */
function axisOf(ev, api) {
  const d = ev.dir || P.v(0, 1, 0);
  const b = P.basis(d);
  if (ev.data && ev.data.axis) {
    const a = ev.data.axis;
    return P.norm(P.v(a[0], a[1], a[2]));
  }
  const a = api ? api.rand(ev, 1) * Math.PI * 2 : 0;
  return P.norm(P.add(P.mul(b.east, Math.cos(a)), P.mul(b.north, Math.sin(a))));
}

/**
 * Where it is overhead, right now. A unit direction.
 * Rodrigues, simplified: the axis is perpendicular to the arrival direction, so
 * the third term drops out.
 */
export function trackDir(w, ev) {
  const d = P.norm(ev.dir || P.v(0, 1, 0));
  const a = axisOf(ev, null);
  const th = BH.trackRate * (w.t - ev.t0);
  return P.norm(P.add(P.mul(d, Math.cos(th)), P.mul(P.cross(a, d), Math.sin(th))));
}

/** 0..1 through the phase, from the phase's own length rather than the next
    phase's `at` -- the last phase has no next and would otherwise read as 1. */
function through(w, ev) {
  const id = ev.def.phases[ev.phase].id;
  const span = { speck: BH.speck, swell: BH.swell, pull: BH.pull, passing: BH.passing }[id] || 1;
  return Math.max(0, Math.min(1, phaseAge(w, ev) / span));
}

/**
 * How much of it there is, 0..1. The renderer sizes the disc off this and the
 * destruction sizes its reach off it, so what you see IS what is taking things.
 */
export function strength(w, ev) {
  const id = ev.def.phases[ev.phase].id;
  const p = through(w, ev);
  if (id === 'speck') return 0.04 + 0.14 * p;
  if (id === 'swell') return 0.18 + 0.42 * p;
  if (id === 'pull') return 0.60 + 0.40 * p;
  return Math.max(0, 1 - p) * 0.6;                 // passing: it shuts
}

/** Apparent radius of the black disc, for the sky. */
export function discRadius(w, ev) {
  const s = strength(w, ev);
  return BH.discMin + (BH.discMax - BH.discMin) * s * s;
}

/* ----------------------------------------------------------------- scars */

/** The ground remembers. Kept on the world, not on the event, because it must
    outlive the event -- see SCARS FOR THE INTEGRATOR at the bottom of the file. */
function scar(w, dir) {
  if (!w.scars) w.scars = [];
  const last = w.scars[w.scars.length - 1];
  if (last && P.surfDist(P.norm(P.v(last[0], last[1], last[2])), dir) < BH.scarSpacing) {
    last[3] = Math.min(BH.scarMaxR, last[3] + BH.scarGrow);
    return;
  }
  w.scars.push([+dir.x.toFixed(5), +dir.y.toFixed(5), +dir.z.toFixed(5), BH.scarR]);
  if (w.scars.length > BH.scarCap) w.scars.shift();
}

export function saveScars(w) { return (w.scars || []).map(s => s.slice()); }
export function loadScars(w, rows) {
  w.scars = [];
  for (const s of rows || []) {
    if (!Array.isArray(s) || s.length < 4) continue;
    if (!s.every(n => typeof n === 'number' && isFinite(n))) continue;
    w.scars.push([s[0], s[1], s[2], Math.min(BH.scarMaxR, Math.abs(s[3]))]);
  }
  return w.scars;
}

/* ------------------------------------------------------------ the taking */

function pulse(w, ev, api, n) {
  const here = trackDir(w, ev);
  const r = BH.reach * strength(w, ev);
  if (r <= 0) return 0;

  const hit = api.near(here, r);
  if (!hit.length) return 0;
  // nearest the track goes first, and ties break on id so two identical worlds
  // tear apart in identical order
  hit.sort((a, b) => {
    const da = P.surfDist(a.dir, here), db = P.surfDist(b.dir, here);
    return da === db ? a.id - b.id : da - db;
  });

  let taken = 0, k = 0;
  for (const o of hit) {
    if (taken >= BH.maxPerPulse) break;
    const q = 1 - P.surfDist(o.dir, here) / r;        // surer, the closer it is
    // k, not taken: keyed on taken alone every skipped object would draw the
    // same number and the whole pulse would skip or take together
    const roll = api.rand(ev, n * 31 + (k++));
    if (roll > BH.grip * q) continue;
    if (api.destroy(o)) {
      taken++;
      scar(w, o.dir);
    }
  }
  return taken;
}

/** A finished lattice near the track can shut what a lattice opened. */
function guardNear(w, ev, api) {
  const here = trackDir(w, ev);
  const finished = LATTICE.stages.length - 1;
  for (const o of api.near(here, BH.guardRange)) {
    if (o.kind === LATTICE.id && o.stage >= finished) return true;
  }
  return false;
}

/** Move to a phase ourselves. The engine only ever moves forward on the clock,
    so nothing else has to know this happened. */
function enterPhase(w, ev, id, api) {
  const i = ev.def.phases.findIndex(p => p.id === id);
  if (i < 0 || i <= ev.phase) return;
  ev.phase = i;
  ev.phaseAt = w.t;
  if (ev.def && ev.def.onPhase) ev.def.onPhase(w, ev, id, api);
}

/* -------------------------------------------------------------- the entry */

// The trigger. Very late game, and deliberately enormous: the point of the
// whole game is that everything you can see you can make, including this.
export const LATTICE = {
  id: 'lattice',
  name: 'Singularity Lattice',
  verb: 'raise',
  blurb: 'Twelve rings holding a hole open. It is the last thing on the list and it is not a decoration.',
  needs: { alloy: 220, shard: 380, biomass: 300 },
  unlock: 'city',
  stages: [
    { name: 'anchor', at: 0, h: 3.2 },
    { name: 'strung', at: 420, h: 10.0 },
    { name: 'singular', at: 900, h: 13.5 },
  ],
  harvest: null,             // you do not harvest the thing holding the sky open
  maxSlope: 0.2,
  settles: 0,
  neon: true,
};

export const CATALOG_ADDITIONS = [LATTICE];

export const DEF = {
  id: 'blackhole',
  name: 'the Unmaking',
  blurb: 'There is a point in the sky with no light coming out of it, and it is getting bigger.',
  minAge: 1500,
  weight: 0.6,
  trigger: LATTICE.id,

  phases: [
    { id: 'speck', at: 0 },
    { id: 'swell', at: BH.speck },
    { id: 'pull', at: BH.swell },
    { id: 'passing', at: BH.pull, lasts: BH.passing },
  ],

  start(w, ev, api) {
    const a = axisOf(ev, api);
    ev.data.axis = [+a.x.toFixed(6), +a.y.toFixed(6), +a.z.toFixed(6)];
    ev.data.pulses = 0;
    ev.data.taken = 0;
    ev.data.stopped = 0;
  },

  onPhase(w, ev, phase, api) {
    if (phase === 'speck') api.say('A star has gone out. It is the wrong shape for a star.');
    else if (phase === 'swell') api.say('It is closer than it was, and the sky around it is bending.');
    else if (phase === 'pull') api.say('It has found the ground. Get away from underneath it.');
    else if (phase === 'passing') {
      api.say(ev.data.stopped
        ? 'The lattice took the strain and the hole folded shut.'
        : 'It is letting go. Whatever it took, it took.');
    }
  },

  tick(w, ev, dt, api) {
    if (ev.def.phases[ev.phase].id !== 'pull') return;

    if (guardNear(w, ev, api)) {
      ev.data.stopped = 1;
      enterPhase(w, ev, 'passing', api);
      return;
    }

    // Keyed on elapsed phase time rather than accumulated dt, so the same world
    // clock always produces the same number of grabs however it was stepped.
    const want = Math.floor(phaseAge(w, ev) / BH.pulseEvery);
    let guard = 0;
    while ((ev.data.pulses | 0) < want && guard++ < 64) {
      ev.data.pulses = (ev.data.pulses | 0) + 1;
      ev.data.taken = (ev.data.taken | 0) + pulse(w, ev, api, ev.data.pulses);
    }
  },

  end(w, ev, api) {
    const n = ev.data.taken | 0;
    api.say(n
      ? `It is gone. ${n} things that were here are not.`
      : 'It is gone, and it never quite reached the ground.');
  },
};

export default DEF;

/* -------------------------------------------------- SCARS FOR THE INTEGRATOR
   `w.scars` is an array of [x, y, z, radius] on the WORLD, not on the event,
   because the whole promise is that the marks outlive the thing that made them.
   world.js does not save it yet. Two lines in save/load do it:
       scars: BH_saveScars(w)          ->  BH_loadScars(w, d.scars)
   using saveScars / loadScars exported above. Without them the ground heals on
   reload, which is the one thing this event must not do.                    */
