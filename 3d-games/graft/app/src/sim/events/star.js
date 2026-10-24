// THE STAR — the sun stops being a lamp and becomes something that happens to you.
//
// Two events, one body. They are the same object seen from either end: a star
// that gives too much, and a star that stops giving. Both are provoked by the
// same catalog entry, the Stellar Tap, because the fiction only works if
// poking the sun is one verb with two answers.
//
// Pure: no DOM, no three.js, no Math.random, no clock but the world's own.

import { C } from '../const.js';
import * as P from '../planet.js';
import { spec, KIND } from '../catalog.js';
import { phaseProgress } from './api.js';

/* ------------------------------------------------------------- the numbers */
// Everything invented here lives in this object, never in const.js. The
// integrator merges it; nothing below reads a literal.
export const CONST = {
  flare: {
    minAge: 720,        // not the first thing that ever happens to you
    weight: 3,
    swell: 34,          // the star fattens, and you get to see it coming
    wave: 46,           // the front crosses the whole sky
    afterglow: 210,     // and what lived through it runs hot
    burn: 0.55,         // base chance an exposed plant is knocked back
    exposure: 0.85,     // how much bare high ground adds to that
    shelter: 15,        // world units of shade a roof or a mast throws
    charge: 3,          // shard per spire the wave lights up
    quicken: 3.0,       // growth clock multiplier during the afterglow
    ash: 40,            // biomass the burnt ground gives back at the end
  },
  dim: {
    minAge: 640,
    weight: 3,
    falter: 30,         // the light goes out over half a minute
    cold: 260,          // and stays out this long if you do nothing
    rekindle: 70,       // coming back is slower than going
    stall: 0.94,        // growth crawls at 6% of its clock in the dark
    payback: 1.8,       // and runs a little hot while the light returns
  },
};

/* ----------------------------------------------------------- the trigger -- */
// A Stellar Tap is a throat of mirrors aimed at the sun. It is the deliberate
// cause of both events: build one and the star answers, one way or the other.
export const TAP = 'stellartap';

export const CATALOG_ADDITIONS = [
  {
    id: TAP,
    name: 'Stellar Tap',
    verb: 'raise',
    blurb: 'A throat of mirrors pointed at the sun. It asks a question, and the sky answers it.',
    needs: { alloy: 24, shard: 45, biomass: 30 },
    unlock: 'town',
    stages: [
      { name: 'ring', at: 0, h: 2.6 },
      { name: 'mirrored', at: 130, h: 7.0 },
      { name: 'aimed', at: 320, h: 8.4 },
    ],
    harvest: null,
    maxSlope: 0.28,
    settles: 0,
    neon: true,
  },
];

const TAP_STAGES = CATALOG_ADDITIONS[0].stages.length;

/* -------------------------------------------------------------- helpers -- */

/** 0..1 through the LAST phase, which api.phaseProgress cannot know. */
function endProgress(w, ev, lasts) {
  return lasts > 0 ? Math.min(1, (w.t - ev.phaseAt) / lasts) : 1;
}

function phaseId(ev) { return ev.def.phases[ev.phase].id; }

/** How badly the sky can reach this spot: bare high ground is worst. */
function exposureAt(dir) {
  const h = P.heightAt(dir);
  const span = C.planet.snow - C.planet.sea;
  return Math.max(0, Math.min(1, (h - C.planet.sea) / (span || 1)));
}

/** Things that throw shade: anything somebody built and is standing under. */
function shelters(w) {
  const out = [];
  for (const o of w.objects) {
    const s = spec(o.kind);
    if (!s) continue;
    if (s.settles > 0 || s.aura) out.push(o);
  }
  return out;
}

function sheltered(o, list, r) {
  for (const s of list) if (P.surfDist(s.dir, o.dir) <= r) return true;
  return false;
}

/** A Stellar Tap that is finished and pointing at the sun, or null. */
function litTap(w) {
  for (const o of w.objects) {
    if (o.kind === TAP && o.stage >= TAP_STAGES - 1) return o;
  }
  return null;
}

/** Bend a growth clock. Positive slows it, negative hurries it along. */
function bendGrowth(w, dt, k) {
  let touched = 0;
  for (const o of w.objects) {
    if (!isFinite(o.nextAt)) continue;       // finished things have no clock left
    const want = o.nextAt + dt * k;
    o.nextAt = Math.max(w.t, want);
    touched++;
  }
  return touched;
}

/* ================================================================ FLARE == */
// The star throws out a wave. It crosses the sky as a front, so the burning is
// progressive rather than a single frame in which half the planet dies. What it
// reaches, it scorches; what it does not kill, it feeds.

export const FLARE = {
  id: 'starflare',
  name: 'the Flare',
  blurb: 'The sun swells. Whatever is standing in the open is about to find out how open it is.',
  minAge: CONST.flare.minAge,
  weight: CONST.flare.weight,
  trigger: TAP,
  phases: [
    { id: 'swell', at: 0 },
    { id: 'wave', at: CONST.flare.swell },
    { id: 'afterglow', at: CONST.flare.wave, lasts: CONST.flare.afterglow },
  ],

  start(w, ev, api) {
    // Everything the renderer needs to draw a whole sky is in here, and all of
    // it is a plain number, because ev.data is saved as JSON.
    ev.data = {
      swell: 0,      // 0..1 the star fattening
      front: 0,      // 0..1 how far the wave has crossed
      glare: 0,      // 0..1 how blown-out the whole world looks
      scorched: 0,
      charged: 0,
    };
  },

  onPhase(w, ev, phase, api) {
    if (phase === 'wave') api.say('The wave is coming over the horizon.');
    if (phase === 'afterglow') {
      api.say(`${ev.data.scorched} things burned. What is left is growing fast.`);
    }
  },

  tick(w, ev, dt, api) {
    const id = phaseId(ev);

    if (id === 'swell') {
      ev.data.swell = phaseProgress(w, ev);
      ev.data.glare = ev.data.swell * 0.25;
      return;
    }

    if (id === 'wave') {
      const p = phaseProgress(w, ev);
      ev.data.swell = 1;
      // brightest as the front passes over you, not at either end of it
      ev.data.glare = 0.25 + 0.75 * Math.sin(Math.min(1, p) * Math.PI);
      const was = ev.data.front;
      if (p > was) {
        ev.data.front = p;
        scorch(w, ev, api, was, p);
      }
      return;
    }

    // afterglow: the sky calms down and the ground goes mad
    const p = endProgress(w, ev, CONST.flare.afterglow);
    ev.data.front = 1;
    ev.data.swell = 1 - p;
    ev.data.glare = 0.3 * (1 - p);
    bendGrowth(w, dt, -(CONST.flare.quicken - 1));
  },

  end(w, ev, api) {
    ev.data.glare = 0;
    ev.data.swell = 0;
    if (ev.data.scorched > 0) api.grant({ biomass: CONST.flare.ash });
    api.say('The sky is an ordinary colour again.');
  },
};

/**
 * The front sweeps from `a` to `b` (both 0..1 across the wave phase). Each
 * object is given a fixed arrival point by its own deterministic roll, so the
 * same world time burns the same things in the same order however the ticks
 * happen to be sliced.
 */
function scorch(w, ev, api, a, b) {
  const due = [];
  for (const o of w.objects) {
    const u = api.rand(ev, o.id);
    if (u >= a && u < b) due.push(o);
  }
  if (!due.length) return;

  const shade = shelters(w);
  const F = CONST.flare;
  let charged = 0;

  for (const o of due) {
    const s = spec(o.kind);
    if (!s) continue;
    if (s.underwater) continue;                       // the sea is a roof
    if (!s.harvest) continue;                         // nobody's house burns down

    if (o.kind === KIND.CRYSTAL) {
      // mineral does not scorch, it charges: the wave is free shard
      charged += F.charge;
      ev.data.charged += F.charge;
      continue;
    }
    if (sheltered(o, shade, F.shelter)) continue;

    const chance = F.burn * (1 - F.exposure * 0.5 + F.exposure * exposureAt(o.dir));
    if (api.rand(ev, o.id + 1000003) >= chance) continue;

    if (o.stage <= 0) {
      api.destroy(o);
    } else {
      // knocked back a stage, and it has to grow the whole way again
      o.stage -= 1;
      const next = s.stages[o.stage + 1];
      o.nextAt = next ? w.t + next.at : Infinity;
      w.rev++;                                        // the renderer redraws on rev
    }
    ev.data.scorched++;
  }

  if (charged > 0) api.grant({ shard: charged });
}

/* =============================================================== DIMMING == */
// The other end of the same star. It guts itself, the world goes cold and
// nothing grows. It comes back on its own eventually -- but a lit Stellar Tap
// gives it something to catch on, and it comes back now.

export const DIMMING = {
  id: 'stardim',
  name: 'the Guttering',
  blurb: 'The sun is going out. Not quickly, and not, on its own, for good.',
  minAge: CONST.dim.minAge,
  weight: CONST.dim.weight,
  trigger: TAP,
  phases: [
    { id: 'falter', at: 0 },
    { id: 'cold', at: CONST.dim.falter },
    { id: 'rekindle', at: CONST.dim.cold, lasts: CONST.dim.rekindle },
  ],

  start(w, ev, api) {
    ev.data = {
      dark: 0,      // 0..1 how much of the daylight is gone
      flash: 0,     // 0..1 the moment it catches again
      held: 0,      // seconds of growth the cold has taken off the world
      fed: 0,       // 1 once a Stellar Tap ended it early
    };
  },

  onPhase(w, ev, phase, api) {
    if (phase === 'cold') api.say('Nothing is growing. Raise a Stellar Tap and give it something to catch on.');
    if (phase === 'rekindle') api.say(ev.data.fed ? 'The tap catches. The sun comes back hard.' : 'The sun finds itself again.');
  },

  tick(w, ev, dt, api) {
    const id = phaseId(ev);
    const D = CONST.dim;

    if (id === 'falter') {
      ev.data.dark = phaseProgress(w, ev);
      // growth is already labouring on the way down
      ev.data.held += dt * D.stall * ev.data.dark;
      bendGrowth(w, dt, D.stall * ev.data.dark);
      return;
    }

    if (id === 'cold') {
      ev.data.dark = 1;
      ev.data.held += dt * D.stall;
      bendGrowth(w, dt, D.stall);

      // The thing the player can do about it. The engine only ever advances a
      // phase on its own clock, so ending one early means moving the event on
      // by hand -- the two fields have to change together or the next tick
      // measures the new phase against the old start.
      if (!ev.data.fed && litTap(w)) {
        ev.data.fed = 1;
        ev.phase = ev.def.phases.length - 1;
        ev.phaseAt = w.t;
        if (ev.def.onPhase) ev.def.onPhase(w, ev, 'rekindle', api);
      }
      return;
    }

    // rekindle: the light climbs back, and the world catches up a little
    const p = endProgress(w, ev, D.rekindle);
    ev.data.dark = 1 - p;
    ev.data.flash = ev.data.fed ? Math.max(0, 1 - p * 4) : 0;
    bendGrowth(w, dt, -(D.payback - 1));
  },

  end(w, ev, api) {
    ev.data.dark = 0;
    ev.data.flash = 0;
    api.say('Daylight, at the usual price.');
  },
};

/** Register both, in one line. */
export const STAR_EVENTS = [FLARE, DIMMING];
