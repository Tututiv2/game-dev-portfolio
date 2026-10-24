// COSMIC EVENTS — the contract.
//
// An event is something enormous that happens TO your planet on its own clock:
// a black hole in the sky, a wormhole a civilization opens, a moon that eclipses
// the sun. It is not scenery and it is not a building.
//
// THE RULE STILL HOLDS, in a bigger form. Every OBJECT on the ground must be in
// the catalog and makeable. Every EVENT must eventually be CAUSABLE: each one
// names a catalog entry that triggers it deliberately, unlocked late. So the
// horizon is still a list of things you have not done yet -- it just now
// includes "tear a hole in the sky".
//
// Everything here is PURE: no DOM, no three.js, no Math.random, no Date.
// The suite greps for all four.

import { C } from '../const.js';
import * as P from '../planet.js';

/**
 * An event DEFINITION looks like this:
 *
 *   {
 *     id: 'blackhole',                 // unique, lowercase
 *     name: 'the Unmaking',            // shown to the player
 *     blurb: 'one sentence, plain',    // shown when it begins
 *     minAge: 900,                     // seconds of world time before it may
 *                                      // occur on its own
 *     weight: 1,                       // relative chance against other events
 *     trigger: 'riftengine',           // catalog id that causes it deliberately
 *                                      // (that entry is YOURS to add, see below)
 *     phases: [                        // in order; `at` is seconds from the
 *       { id: 'arrives', at: 0 },      // previous phase
 *       { id: 'pulls',   at: 120 },
 *       { id: 'passes',  at: 420 },
 *     ],
 *     start(w, ev, api) {},            // once, when it begins
 *     onPhase(w, ev, phase, api) {},   // when each phase begins (incl. the first)
 *     tick(w, ev, dt, api) {},         // every sim tick while active
 *     end(w, ev, api) {},              // once, when the last phase runs out
 *   }
 *
 * The live EVENT the engine hands back to you:
 *
 *   ev = {
 *     id, def,
 *     t0,          // world time it began
 *     phase,       // index into def.phases
 *     phaseAt,     // world time the current phase began
 *     dir,         // unit vector: where on the sphere it is centred (may be null)
 *     data: {},    // yours. must be JSON-safe: it is saved and reloaded
 *   }
 */

/** Seconds the current phase has been running. */
export function phaseAge(w, ev) { return w.t - ev.phaseAt; }

/** 0..1 through the current phase (1 if the phase is the last / open-ended). */
export function phaseProgress(w, ev) {
  const next = ev.def.phases[ev.phase + 1];
  if (!next || !next.at) return 1;
  return Math.min(1, (w.t - ev.phaseAt) / next.at);
}

/** Total seconds the event has been alive. */
export function age(w, ev) { return w.t - ev.t0; }

/**
 * The helpers every event is handed as `api`. Pure, and the ONLY sanctioned way
 * to change the world from inside an event -- so that what an event does stays
 * visible in one place.
 */
export function makeApi(world) {
  return {
    P, C,

    /** Objects within `r` world units of a direction. */
    near(dir, r) { return world.near(world.w, dir, r); },

    /** Remove an object outright (destroyed, not harvested). Returns true if it went. */
    destroy(o) { return world.destroyObject(world.w, o); },

    /** Put something from the catalog on the ground, ignoring cost. Returns it or null. */
    place(kind, dir, stage) { return world.forcePlace(world.w, kind, dir, stage); },

    /** Give (or take, with a negative) resources. */
    grant(res) { return world.grantRes(world.w, res); },

    /** Say something to the player. Keep it to one sentence. */
    say(text) { world.announce(world.w, text); },

    /** Deterministic 0..1 from an event and a counter. Events may not use Math.random. */
    rand(ev, n) {
      let h = Math.imul((ev.t0 * 1000) | 0, 374761393) + Math.imul(n | 0, 668265263)
            + Math.imul(ev.id.length, 1274126177);
      h = Math.imul(h ^ (h >>> 15), 2246822519);
      h = Math.imul(h ^ (h >>> 13), 3266489917);
      h ^= h >>> 16;
      return (h >>> 0) / 4294967296;
    },

    /** A unit direction scattered around `dir` at up to `r` world units. */
    scatterNear(dir, r, ev, n) {
      const b = P.basis(dir);
      const a = this.rand(ev, n * 2) * Math.PI * 2;
      const d = Math.sqrt(this.rand(ev, n * 2 + 1)) * r;
      const off = P.add(P.mul(b.east, Math.cos(a) * d), P.mul(b.north, Math.sin(a) * d));
      return P.norm(P.add(P.mul(dir, C.planet.radius), off));
    },
  };
}

/** A definition is well-formed, or it is a bug. Checked by the suite. */
export function validate(def) {
  const problems = [];
  const need = ['id', 'name', 'blurb', 'phases', 'trigger'];
  for (const k of need) if (def[k] == null) problems.push(`${def.id || '?'}: missing ${k}`);
  if (!Array.isArray(def.phases) || def.phases.length < 1) problems.push(`${def.id}: no phases`);
  else {
    def.phases.forEach((p, i) => {
      if (!p.id) problems.push(`${def.id}: phase ${i} has no id`);
      if (typeof p.at !== 'number') problems.push(`${def.id}: phase ${p.id} has no 'at'`);
      if (i === 0 && p.at !== 0) problems.push(`${def.id}: the first phase must be at 0`);
    });
  }
  if (typeof def.minAge !== 'number') problems.push(`${def.id}: missing minAge`);
  for (const fn of ['start', 'tick', 'onPhase', 'end']) {
    if (def[fn] != null && typeof def[fn] !== 'function') problems.push(`${def.id}: ${fn} is not a function`);
  }
  return problems;
}
