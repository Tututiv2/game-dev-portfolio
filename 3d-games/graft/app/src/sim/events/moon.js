// THE MOON — a body caught in orbit, and it never leaves.
//
// Three things, and they are one thing: a disc that crosses the sky, a darkness
// that crosses the ground, and a sea that comes and goes. All three are driven
// by ONE angle -- how far round the moon is from the sun -- so an eclipse and a
// spring tide are not two schedules that happen to line up, they are the same
// number read twice.
//
// The angle is measured FROM THE SUN rather than from any fixed point on the
// sphere, which is what lets this file stay pure. The view knows where the sun
// is (it owns the light); the sim only needs to know how far the moon is from
// it, and that is a function of world time alone.
//
// Pure: no DOM, no three.js, no Math.random, no clock but w.t.

import { C } from '../const.js';
import * as P from '../planet.js';
import { KIND } from '../catalog.js';
import { age } from './api.js';

export const ID = 'moon';

// Numbers this event invents. They do not belong in const.js -- nothing else
// reads them, and const.js is somebody else's file.
export const MOON = {
  distance: 720,      // world units from the planet centre once it has settled
  arriveFrom: 1060,   // it comes in from the edge of the star field
  radius: 62,         // a captured moon around a 118-unit planet IS enormous
  arriveScale: 0.34,  // ...and it looks small until it is close

  synodic: 420,       // seconds from one eclipse to the next
  theta0: Math.PI / 2, // it arrives at quarter phase: the sea goes OUT first
  nodeCycle: 2360,    // the tilt of the orbit drifts, so no two passes match
  incl: 0.11,         // radians of tilt at the extreme
  cover: 0.20,        // separation from the sun at which the darkening starts
  darkest: 0.86,      // how much of the daylight a total eclipse takes

  tide: 0.55,         // world units the sea rises and falls, either way of C.planet.sea
  settleAt: 150,      // the tide reaches full strength when the orbit does

  sowCap: 40,         // how much kelp one moon may ever bring in
  sowRadius: 16,      // near an existing bed, because that is where it is deep
  deepEclipse: 0.55,  // coverage at which it is worth saying something
};

const TAU = Math.PI * 2;
const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);
const smooth = (x) => { const t = clamp01(x); return t * t * (3 - 2 * t); };

/* --------------------------------------------------------------- geometry */

/** The live moon, or null. There is only ever one. */
export function liveMoon(w) {
  if (!w || !w.events) return null;
  return w.events.find(e => e.id === ID) || null;
}

/** How far round the moon is from the sun. 0 is an eclipse, PI is a full moon. */
export function phaseAngle(w, ev) {
  return MOON.theta0 + TAU * ((w.t - ev.t0) / MOON.synodic);
}

/** How far the orbit is tilted out of the sun's plane right now. */
export function tilt(w, ev) {
  return MOON.incl * Math.sin(TAU * ((w.t - ev.t0) / MOON.nodeCycle));
}

/** Angle between the moon and the sun as seen from the ground. */
export function separation(w, ev) {
  const c = Math.cos(phaseAngle(w, ev)) * Math.cos(tilt(w, ev));
  return Math.acos(Math.max(-1, Math.min(1, c)));
}

/** How much of the orbit has bedded in: 0 as it arrives, 1 once it is settled. */
export function settled(w, ev) {
  return smooth(age(w, ev) / MOON.settleAt);
}

/**
 * Where the moon is, given where the sun is. The view has the real sun (it owns
 * the light) and hands it in, so this never has to guess and can never drift
 * out of step with what is actually lighting the ground.
 *
 * `sunDir` unit; `planeN` unit and perpendicular to the sun's orbit plane.
 */
export function moonUnit(sunDir, planeN, theta, tiltA) {
  const east = P.norm(P.cross(planeN, sunDir));
  const inPlane = P.add(P.mul(sunDir, Math.cos(theta)), P.mul(east, Math.sin(theta)));
  return P.norm(P.add(P.mul(inPlane, Math.cos(tiltA)), P.mul(planeN, Math.sin(tiltA))));
}

/* ---------------------------------------------------------------- eclipse */

/** 0..1 of the sun covered. Zero for nearly all of the cycle, which is the point. */
export function coverage(w, ev) {
  if (!ev) return 0;
  const sep = separation(w, ev);
  if (sep >= MOON.cover) return 0;
  return smooth(1 - sep / MOON.cover) * settled(w, ev);
}

/** 0..1 for the whole world. The view multiplies this by its own daylight. */
export function eclipse(w) {
  const ev = liveMoon(w);
  return ev ? coverage(w, ev) : 0;
}

/* ------------------------------------------------------------------- tide */

/**
 * Highest when the moon is in line with the sun -- at the eclipse and again at
 * the full moon -- and lowest at the quarters. That is a spring/neap cycle, and
 * it means the two spectacles land at different times: the sky goes dark, and a
 * quarter of a cycle later the sea walks out and shows you the kelp.
 */
export function tideAt(w, ev) {
  return MOON.tide * Math.cos(2 * phaseAngle(w, ev)) * settled(w, ev);
}

/**
 * The delta the rest of the game must add to C.planet.sea.
 *
 * INTEGRATOR: apply this to the ocean mesh radius, to the `under water` test in
 * blockedReason, and to the shore wall in stepOnce. Without it there is a moon
 * and no tide.
 */
export function tideOffset(w) {
  const ev = liveMoon(w);
  return ev ? tideAt(w, ev) : 0;
}

/** Sea level right now, for anything that would otherwise read C.planet.sea. */
export function seaLevel(w) { return C.planet.sea + tideOffset(w); }

/* ------------------------------------------------------------ the event */

// A high tide happens every half turn of the phase angle. Counting them as an
// integer rather than watching a threshold is what keeps this the same whatever
// step size the world is advanced by.
function highTideIndex(w, ev) { return Math.floor(phaseAngle(w, ev) / Math.PI); }

// `k` is the tide this is, not the count of successes. Keying the retry on the
// success counter meant a tide that found nowhere to sow reran the identical
// draw at the next tide, and the next, for ever: deterministic randomness only
// makes progress if the number you feed it actually moves.
function sowKelp(w, ev, api, k) {
  const kelp = w.objects.filter(o => o.kind === KIND.KELP);
  if (!kelp.length) return false;
  const n = Math.abs(k) * 977 + ((ev.data.sown | 0) * 31);
  const from = kelp[Math.floor(api.rand(ev, n + 3) * kelp.length) % kelp.length];
  for (let i = 0; i < 8; i++) {
    const dir = api.scatterNear(from.dir, MOON.sowRadius, ev, n + 11 + i);
    // Well under the sea at every tide, so the returning water cannot strand it.
    if (P.heightAt(dir) >= C.planet.sea - 1.0) continue;
    if (api.place(KIND.KELP, dir, 0)) { ev.data.sown = (ev.data.sown | 0) + 1; return true; }
  }
  return false;
}

export const def = {
  id: ID,
  name: 'the Moon',
  blurb: 'Something came down out of the dark and stayed. The sea noticed first.',
  minAge: 900,
  weight: 1,
  trigger: 'anchor',

  // No `lasts` on the final phase, so the engine never ends it. A moon is not
  // an incident; once it is up there it is part of the sky.
  phases: [
    { id: 'capture', at: 0 },
    { id: 'settling', at: 90 },
    { id: 'sky', at: 60 },
  ],

  start(w, ev) {
    ev.data.sown = 0;
    ev.data.tideK = highTideIndex(w, ev);
    ev.data.dark = 0;
    ev.data.eclipses = 0;
    ev.data.ecl = 0;
  },

  onPhase(w, ev, phase, api) {
    if (phase === 'settling') api.say('The tide goes out further than it has ever gone.');
    if (phase === 'sky') api.say('The moon is in orbit. It will not be leaving.');
  },

  tick(w, ev, dt, api) {
    // Saved, so the renderer and the HUD can read the last known value straight
    // out of the event without recomputing anything.
    ev.data.ecl = +coverage(w, ev).toFixed(4);
    ev.data.tide = +tideAt(w, ev).toFixed(4);

    if (ev.data.ecl >= MOON.deepEclipse && !ev.data.dark) {
      ev.data.dark = 1;
      ev.data.eclipses = (ev.data.eclipses | 0) + 1;
      api.say('The sun goes out.');
    } else if (ev.data.ecl <= 0 && ev.data.dark) {
      ev.data.dark = 0;
    }

    // Each returning high tide carries something in with it.
    const k = highTideIndex(w, ev);
    if (k !== ev.data.tideK) {
      ev.data.tideK = k;
      if ((ev.data.sown | 0) < MOON.sowCap && settled(w, ev) > 0.9) sowKelp(w, ev, api, k);
    }
  },
};

/* --------------------------------------------------------------- the cause */

// The event has to be CAUSABLE, so here is the thing that causes it: an engine
// that reaches up. It is the most expensive object in the game on purpose --
// the sky is the last thing you get to change.
//
// INTEGRATOR: merge into CATALOG (and add KIND.ANCHOR = 'anchor'). It needs a
// draw entry in render.js too or the catalog/render check fails --
// `anchorParts(THREE)` in view/fx/moon.js is ready to splice in.
export const CATALOG_ADDITIONS = [
  {
    id: 'anchor',
    name: 'Anchor',
    verb: 'raise',
    blurb: 'A ring that pulls on whatever passes. Point it at the dark for long enough and something comes down to look.',
    needs: { alloy: 120, shard: 300, biomass: 300 },
    unlock: 'city',
    stages: [
      { name: 'ring', at: 0, h: 3.0 },
      { name: 'strung', at: 260, h: 9.0 },
      { name: 'pulling', at: 700, h: 11.5 },
    ],
    harvest: null,
    maxSlope: 0.22,
    settles: 0,
    neon: true,
  },
];

export default def;
