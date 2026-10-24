// The whole game. Pure: no DOM, no three.js, no Math.random, no Date.
// The renderer reads this and decides nothing.

import { C } from './const.js';
import { CATALOG, KIND, SELF_BUILD, spec, tierOf, isCreatable } from './catalog.js';
import * as P from './planet.js';
import './boot.js';   // registers every event's trigger building
import { initEvents, tickEvents, saveEvents, loadEvents, fire } from './events/engine.js';
import { createScale, advanceScale, saveScale, loadScale, surfaceBoon } from './scale.js';

let nextId = 1;

function makeObj(kind, dir, t, stage = 0) {
  const s = spec(kind);
  return {
    id: nextId++,
    kind,
    dir: P.norm(dir),
    stage,
    // when the next stage arrives; stage 0 is instant
    nextAt: stage + 1 < s.stages.length ? t + s.stages[stage + 1].at : Infinity,
    bornAt: t,
    lastMake: t,
    spin: (nextId * 2.399963) % 6.283,
  };
}

export function createWorld(seed = C.seed) {
  nextId = 1;
  const w = {
    t: 0,
    res: { biomass: 12, shard: 0, alloy: 0 },
    objects: [],
    player: { dir: P.v(0, 0, 1), yaw: 0, harvesting: null, held: 0 },
    log: [],
    rev: 0,           // bumped whenever the object list changes, so the
                      // renderer can rebuild instance buffers and nothing else
    made: 0,          // how many things you personally created
    seen: {},         // tiers reached, for the unlock rules
    notices: [],      // one-line things the world wants to tell you
    scars: [],        // marks left by whatever has happened to this place
    scale: createScale(),   // everything above the surface: orbit to galaxies
  };
  initEvents(w);

  // The world is FULL when you arrive: forests in the lowlands, spires on the
  // high ground, kelp in the shallows. None of it is off-limits -- every kind
  // here is in the catalog, so anything you can see, you can grow.
  const dirs = P.scatter(C.world.scatter, seed * 0.618);
  for (let i = 0; i < dirs.length; i++) {
    const d = dirs[i];
    const h = P.heightAt(d);
    const slope = P.slopeAt(d);
    const jitter = ((i * 2654435761) % 1000) / 1000;

    let kind = null;
    if (h < C.planet.sea) {
      // the shallows, not the deeps
      if (h > C.planet.sea - 3.2 && jitter < 0.22) kind = KIND.KELP;
    } else if (h < C.planet.sea + C.planet.shore) {
      if (jitter < 0.14) kind = KIND.BLOOM;                 // sand, mostly bare
    } else if (h < C.planet.alpine) {
      const forest = jitter < C.world.forestBias;
      kind = forest ? KIND.TREE : (jitter < 0.82 ? KIND.BLOOM : KIND.CRYSTAL);
    } else {
      if (jitter < 0.30) kind = KIND.CRYSTAL;               // bare rock
    }
    if (!kind) continue;

    const sp = spec(kind);
    if (slope > sp.maxSlope) continue;
    if (sp.maxHeight != null && h > sp.maxHeight) continue;
    w.objects.push(makeObj(kind, d, 0, sp.stages.length - 1));
  }

  // Put the player somewhere they can stand, with a view.
  w.player.dir = landfall(seed);
  return w;
}

/** A dry, gentle, low-lying spot: where you would actually set down. */
export function landfall(seed = C.seed) {
  const dirs = P.scatter(400, seed * 0.31 + 0.17);
  let best = dirs[0], bestScore = -Infinity;
  for (const d of dirs) {
    const h = P.heightAt(d);
    if (h < C.planet.sea + C.planet.shore) continue;
    if (h > C.planet.alpine) continue;
    // near the coast is the good bit: you can see the sea and the trees
    const coastal = -Math.abs(h - (C.planet.sea + 2.4));
    const score = coastal - P.slopeAt(d) * 6;
    if (score > bestScore) { bestScore = score; best = d; }
  }
  return best;
}

/* ------------------------------------------------- what an event may do -- */
// Deliberately small. Everything a cosmic event does to the world goes through
// these four, so "what can an event actually change" has one answer.

export function destroyObject(w, o) {
  const before = w.objects.length;
  w.objects = w.objects.filter(q => q.id !== o.id);
  if (w.objects.length === before) return false;
  w.rev++;
  return true;
}

export function forcePlace(w, kind, dir, stage) {
  if (!isCreatable(kind)) return null;
  const sp = spec(kind);
  const st = stage == null ? 0 : Math.min(stage, sp.stages.length - 1);
  const o = makeObj(kind, dir, w.t, st);
  w.objects.push(o);
  w.rev++;
  return o;
}

export function grantRes(w, res) {
  for (const k of Object.keys(res || {})) w.res[k] = (w.res[k] || 0) + res[k];
  return w.res;
}

export function announce(w, text) {
  w.notices = w.notices || [];
  w.notices.push({ t: w.t, text });
  if (w.notices.length > 30) w.notices.shift();
}

function hooks(w) {
  return { w, near, destroyObject, forcePlace, grantRes, announce };
}

/** Begin a named event now. Used by the trigger buildings, and by the console. */
export function trigger(w, id, dir = null) { return fire(w, id, dir, hooks(w)); }

/* --------------------------------------------------------------- queries */

export function objAt(w, id) { return w.objects.find(o => o.id === id); }
export function finalStage(o) { return o.stage >= spec(o.kind).stages.length - 1; }
export function heightOf(o) { return spec(o.kind).stages[o.stage].h; }

/** Everything within `r` world units of a direction. */
export function near(w, dir, r) {
  return w.objects.filter(o => P.surfDist(o.dir, dir) <= r);
}

/** The thing you are close enough to touch, nearest first. */
export function targeted(w) {
  let best = null, bd = C.player.reach;
  for (const o of w.objects) {
    const d = P.surfDist(o.dir, w.player.dir);
    if (d < bd) { bd = d; best = o; }
  }
  return best;
}

/** Clusters of habs. Returns [{dir, count, tier, members}] */
export function settlements(w) {
  const seeds = w.objects.filter(o => spec(o.kind).settles > 0);
  const used = new Set();
  const out = [];
  for (const s of seeds) {
    if (used.has(s.id)) continue;
    const group = [s];
    used.add(s.id);
    // one pass of growing the cluster is enough at these sizes and keeps this
    // deterministic and cheap; a settlement is a local thing by definition
    for (const o of seeds) {
      if (used.has(o.id)) continue;
      if (group.some(g => P.surfDist(g.dir, o.dir) <= C.grow.settleRadius)) {
        group.push(o); used.add(o.id);
      }
    }
    const count = group.reduce((a, o) => a + spec(o.kind).settles, 0);
    let c = P.v();
    for (const g of group) c = P.add(c, g.dir);
    out.push({ dir: P.norm(c), count, tier: tierOf(count, C), members: group });
  }
  return out;
}

/** Growth multiplier at a point, from any mast whose aura covers it. */
export function auraAt(w, dir) {
  let m = 1;
  for (const o of w.objects) {
    const s = spec(o.kind);
    if (!s.aura || !finalStage(o)) continue;
    if (P.surfDist(o.dir, dir) <= s.aura.radius) m = Math.max(m, s.aura.growth);
  }
  return m;
}

/* ---------------------------------------------------------------- actions */

export function canAfford(w, kind) {
  const need = spec(kind).needs || {};
  return Object.keys(need).every(k => (w.res[k] || 0) >= need[k]);
}

export function unlocked(w, kind) {
  const u = spec(kind).unlock;
  return !u || !!w.seen[u];
}

/** Why you cannot plant this here, or null if you can. */
export function blockedReason(w, kind, dir) {
  const s = spec(kind);
  if (!unlocked(w, kind)) return `needs a ${s.unlock}`;
  if (!canAfford(w, kind)) return 'not enough';
  const h = P.heightAt(dir);
  const underwater = h < C.planet.sea;
  if (underwater && !s.underwater) return 'under water';
  if (!underwater && s.underwater) return 'needs water';
  if (s.maxHeight != null && h > s.maxHeight) return 'too high';
  if (P.slopeAt(dir) > s.maxSlope) return 'too steep';
  if (near(w, dir, 2.0).length) return 'too close to something';
  return null;
}

export function plant(w, kind, dir) {
  if (!isCreatable(kind)) return null;
  if (blockedReason(w, kind, dir)) return null;
  const need = spec(kind).needs || {};
  for (const k of Object.keys(need)) w.res[k] -= need[k];
  const o = makeObj(kind, dir, w.t);
  w.objects.push(o);
  w.rev++;
  w.made++;
  w.log.push({ t: w.t, k: 'made', kind });
  return o;
}

/** Hold to harvest. Returns what you got, or null. */
export function harvest(w, o) {
  const s = spec(o.kind);
  if (!s.harvest || !finalStage(o)) return null;
  for (const k of Object.keys(s.harvest)) w.res[k] = (w.res[k] || 0) + s.harvest[k];
  w.rev++;
  if (s.regrow != null) {
    o.stage = s.regrow;
    o.nextAt = w.t + s.stages[o.stage + 1].at;
  } else {
    w.objects = w.objects.filter(q => q.id !== o.id);
  }
  return s.harvest;
}

/** Uproot something entirely, to make room. The world is full when you arrive,
    so without this there is nowhere to put a village and the whole rule
    ("everything you see, you can make") only works in one direction. */
export function clear(w, o) {
  const s = spec(o.kind);
  const before = w.objects.length;
  w.objects = w.objects.filter(q => q.id !== o.id);
  if (w.objects.length === before) return null;
  w.rev++;
  // you get back a little of what it was made of
  const back = {};
  const src = s.harvest || s.needs || {};
  for (const k of Object.keys(src)) {
    const v = Math.floor(src[k] * 0.5);
    if (v > 0) { w.res[k] = (w.res[k] || 0) + v; back[k] = v; }
  }
  w.log.push({ t: w.t, k: 'cleared', kind: o.kind });
  return back;
}

/* ------------------------------------------------------------------ clock */

function grow(w, dt) {
  for (const o of w.objects) {
    const s = spec(o.kind);
    if (o.stage + 1 < s.stages.length && w.t >= o.nextAt) {
      o.stage++;
      w.rev++;
      const boon = w.scale ? surfaceBoon(w.scale).growth : 1;
      o.nextAt = o.stage + 1 < s.stages.length
        ? w.t + s.stages[o.stage + 1].at / (auraAt(w, o.dir) * boon)
        : Infinity;
      w.log.push({ t: w.t, k: 'grew', kind: o.kind, stage: o.stage });
    }
    if (s.produces && finalStage(o) && w.t - o.lastMake >= s.produces.every) {
      o.lastMake = w.t;
      for (const k of Object.keys(s.produces)) {
        if (k === 'every') continue;
        w.res[k] = (w.res[k] || 0) + s.produces[k];
      }
    }
  }
}

/** A settlement raises its own next building. This is why leaving is worth it. */
function selfBuild(w) {
  for (const st of settlements(w)) {
    if (!st.tier) continue;
    w.seen[st.tier] = true;
    const anchor = st.members.reduce((a, b) => (a.lastMake <= b.lastMake ? a : b));
    if (w.t - anchor.lastMake < C.grow.selfBuild[st.tier]) continue;
    anchor.lastMake = w.t;

    const list = SELF_BUILD[st.tier];
    const pick = list[(st.count + w.objects.length) % list.length];
    // find a spot just outside the cluster that the rules allow
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2 + st.count;
      const b = P.basis(st.dir);
      const r = (C.grow.settleRadius * 0.55 + i * 0.9 + C.grow.selfBuildSpread * 0.35);
      const off = P.add(P.mul(b.east, Math.cos(a) * r), P.mul(b.north, Math.sin(a) * r));
      const d = P.norm(P.add(P.mul(st.dir, C.planet.radius), off));
      if (blockedReason({ ...w, res: bottomless() }, pick, d)) continue;
      const o = makeObj(pick, d, w.t);
      w.objects.push(o);
      w.rev++;
      w.log.push({ t: w.t, k: 'settled', kind: pick, tier: st.tier });
      break;
    }
  }
}

// A settlement does not pay your bills; it builds from its own means.
function bottomless() {
  return new Proxy({}, { get: () => Infinity, has: () => true });
}

function stepOnce(w, dt, input) {
  w.t += dt;
  const p = w.player;

  if (input) {
    p.yaw += input.turn * C.player.turn * dt;
    const speed = (input.run ? C.player.run : C.player.walk);
    const want = P.step(p.dir, p.yaw, input.forward, input.strafe, speed * dt);
    // The shore is a wall, not a slope you slide down. Stopping at the water is
    // clearer than swimming badly.
    p.dir = P.heightAt(want) >= C.planet.sea ? want : p.dir;
    p.wet = P.heightAt(p.dir) < C.planet.sea + 0.5;
  }

  grow(w, dt);
  selfBuild(w);
  tickEvents(w, dt, hooks(w));
  // The ladder above the surface. It only ADDS its flows to the purse --
  // world.js produces the rest, and counting it twice would make the ground
  // free exactly when the ground is supposed to be the wall.
  if (w.scale) advanceScale(w.scale, w.res, dt);
}

/** Advance by real seconds, in fixed ticks. Never step from the frame delta. */
export function advance(w, seconds, input = null) {
  let left = Math.min(seconds, C.grow.offlineCap);
  let guard = 0;
  while (left > 1e-9 && guard++ < 400000) {
    const dt = Math.min(C.tick, left);
    stepOnce(w, dt, input);
    left -= dt;
  }
  if (w.log.length > 400) w.log.splice(0, w.log.length - 400);
  return w;
}

/* ------------------------------------------------------------ persistence */

export function save(w) {
  return JSON.stringify({
    v: 1, t: w.t, res: w.res, made: w.made, seen: w.seen,
    p: [w.player.dir.x, w.player.dir.y, w.player.dir.z, w.player.yaw],
    o: w.objects.map(o => [o.kind, +o.dir.x.toFixed(6), +o.dir.y.toFixed(6), +o.dir.z.toFixed(6),
                           o.stage, o.nextAt === Infinity ? -1 : +o.nextAt.toFixed(2),
                           +o.lastMake.toFixed(2)]),
    ev: saveEvents(w),
    sc: saveScale(w.scale),
    scars: (w.scars || []).map(s2 => s2.slice()),
  });
}

export function load(json) {
  const d = typeof json === 'string' ? JSON.parse(json) : json;
  if (!d || d.v !== 1) return null;
  const w = createWorld();
  w.objects = [];
  w.t = d.t || 0;
  w.res = Object.assign({ biomass: 0, shard: 0, alloy: 0 }, d.res || {});
  w.made = d.made || 0;
  w.seen = d.seen || {};
  w.player.dir = P.norm(P.v(d.p[0], d.p[1], d.p[2]));
  w.player.yaw = d.p[3] || 0;
  loadEvents(w, d.ev);
  if (d.sc) w.scale = loadScale(d.sc) || w.scale;
  w.scars = Array.isArray(d.scars) ? d.scars.map(s2 => s2.slice()) : [];
  for (const [kind, x, y, z, stage, nextAt, lastMake] of d.o) {
    if (!isCreatable(kind)) continue;          // a save cannot smuggle in scenery
    const o = makeObj(kind, P.v(x, y, z), w.t, stage);
    o.nextAt = nextAt < 0 ? Infinity : nextAt;
    o.lastMake = lastMake;
    w.objects.push(o);
  }
  return w;
}

export { CATALOG, KIND, spec, tierOf, isCreatable };
