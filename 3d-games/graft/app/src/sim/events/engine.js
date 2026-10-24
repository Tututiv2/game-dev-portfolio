// Runs cosmic events. Knows nothing about any particular one — every event is
// a definition in ./registry.js implementing ./api.js.

import { C } from '../const.js';
import * as P from '../planet.js';
import { EVENTS, byId } from './registry.js';
import { makeApi, validate } from './api.js';

export function initEvents(w) {
  w.events = [];
  w.eventLog = [];
  w.eventsSeen = {};
  w.nextEventAt = C.events.first;
}

/** Begin an event now. `dir` optional. Returns the live event, or null. */
export function fire(w, id, dir = null, hooks = null) {
  const def = byId(id);
  if (!def) return null;
  if (w.events.some(e => e.id === id)) return null;      // one of each at a time
  const ev = {
    id, def,
    t0: w.t, phase: 0, phaseAt: w.t,
    dir: dir || pickSite(w, id),
    data: {},
  };
  w.events.push(ev);
  w.eventsSeen[id] = (w.eventsSeen[id] || 0) + 1;
  const api = makeApi(hooks);
  if (def.start) def.start(w, ev, api);
  if (def.onPhase) def.onPhase(w, ev, def.phases[0].id, api);
  w.eventLog.push({ t: w.t, id, k: 'begins' });
  if (def.blurb) announceInto(w, def.blurb);
  return ev;
}

function announceInto(w, text) {
  w.notices = w.notices || [];
  w.notices.push({ t: w.t, text });
  if (w.notices.length > 30) w.notices.shift();
}

/**
 * Where an event happens, deterministic per event id and world time.
 *
 * NOT uniform on the sphere, which is what it was first and which was wrong:
 * the planet is small but it still has a far side, and a black hole that
 * arrives behind you is a black hole you never see. Every event is sited in
 * the player's own sky -- within `siteArc` of where they are standing -- so
 * the thing you are meant to dread is somewhere you can actually look.
 */
function pickSite(w, id) {
  let h = Math.imul(id.length * 7919, 374761393) + Math.imul((w.t * 100) | 0, 668265263);
  h = Math.imul(h ^ (h >>> 15), 2246822519);
  h ^= h >>> 16;
  const a = ((h >>> 0) / 4294967296) * Math.PI * 2;
  const t = (Math.imul(h, 2654435761) >>> 0) / 4294967296;

  const here = w.player && w.player.dir ? w.player.dir : P.v(0, 0, 1);
  const b = P.basis(here);
  // cos-weighted so it is not clustered on the exact spot you are standing on
  const arc = C.events.siteArc * (0.35 + 0.65 * Math.sqrt(t));
  const off = P.add(P.mul(b.east, Math.cos(a) * Math.sin(arc)),
                    P.mul(b.north, Math.sin(a) * Math.sin(arc)));
  return P.norm(P.add(P.mul(here, Math.cos(arc)), off));
}

/** Advance every live event. Called once per fixed tick by world.js. */
export function tickEvents(w, dt, hooks) {
  const api = makeApi(hooks);

  // something new, on the world's own clock
  if (w.t >= w.nextEventAt) {
    w.nextEventAt = w.t + C.events.gap + (w.events.length * C.events.gapPerActive);
    const options = EVENTS.filter(d =>
      w.t >= d.minAge &&
      !w.events.some(e => e.id === d.id) &&
      (C.events.repeat || !w.eventsSeen[d.id]));
    if (options.length) {
      let roll = 0;
      for (const d of options) roll += (d.weight || 1);
      let pick = ((Math.imul((w.t * 1000) | 0, 2246822519) >>> 0) / 4294967296) * roll;
      for (const d of options) {
        pick -= (d.weight || 1);
        if (pick <= 0) { fire(w, d.id, null, hooks); break; }
      }
    }
  }

  for (let i = w.events.length - 1; i >= 0; i--) {
    const ev = w.events[i];
    const def = ev.def;
    const next = def.phases[ev.phase + 1];
    if (next && w.t - ev.phaseAt >= next.at) {
      ev.phase++;
      ev.phaseAt = w.t;
      w.eventLog.push({ t: w.t, id: ev.id, k: 'phase', phase: next.id });
      if (def.onPhase) def.onPhase(w, ev, next.id, api);
    }
    if (def.tick) def.tick(w, ev, dt, api);

    // the last phase has a duration too: `end` when it runs out
    const last = def.phases[def.phases.length - 1];
    if (ev.phase === def.phases.length - 1 && last.lasts != null
        && w.t - ev.phaseAt >= last.lasts) {
      if (def.end) def.end(w, ev, api);
      w.eventLog.push({ t: w.t, id: ev.id, k: 'ends' });
      w.events.splice(i, 1);
    }
  }
  if (w.eventLog.length > 200) w.eventLog.splice(0, w.eventLog.length - 200);
}

export function saveEvents(w) {
  return w.events.map(e => [e.id, e.t0, e.phase, e.phaseAt,
    e.dir ? [+e.dir.x.toFixed(6), +e.dir.y.toFixed(6), +e.dir.z.toFixed(6)] : null, e.data]);
}

export function loadEvents(w, rows) {
  w.events = [];
  for (const [id, t0, phase, phaseAt, dir, data] of rows || []) {
    const def = byId(id);
    if (!def) continue;                       // a save cannot smuggle in an event
    w.events.push({
      id, def, t0, phase: Math.min(phase, def.phases.length - 1), phaseAt,
      dir: dir ? P.v(dir[0], dir[1], dir[2]) : null,
      data: data || {},
    });
  }
}

/** Every registered event is well-formed. Used by the suite. */
export function auditEvents() {
  const problems = [];
  const seen = new Set();
  for (const d of EVENTS) {
    if (seen.has(d.id)) problems.push(`duplicate event id ${d.id}`);
    seen.add(d.id);
    problems.push(...validate(d));
  }
  return problems;
}
