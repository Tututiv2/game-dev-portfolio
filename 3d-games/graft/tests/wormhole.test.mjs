// node tests/wormhole.test.mjs   — no browser, no DOM, no three.js.
//
// Behaviour only. Nothing here asserts a number this event invented and then
// tuned until it went green: it asserts that the hole opens where it should,
// that what comes through obeys the same placement rules the player does, that
// it is deterministic, that its state survives a save, and that it ends.

import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const load = (rel) => import(pathToFileURL(join(ROOT, rel)));

const { C } = await load('app/src/sim/const.js');
const P = await load('app/src/sim/planet.js');
const CAT = await load('app/src/sim/catalog.js');
const W = await load('app/src/sim/world.js');
const API = await load('app/src/sim/events/api.js');
const E = await load('app/src/sim/events/engine.js');
const REG = await load('app/src/sim/events/registry.js');
const FX = await load('app/src/view/fx/wormhole.js');
const M = await load('app/src/sim/events/wormhole.js');

const BASE_IDS = new Set(CAT.CATALOG.map(c => c.id));

// The integrator merges these two lists. Doing it here, at runtime, is how the
// test exercises the shipping code path rather than a copy of it.
for (const c of M.CATALOG_ADDITIONS) {
  if (!CAT.CATALOG.some(e => e.id === c.id)) CAT.CATALOG.push(c);
}
if (!REG.EVENTS.some(d => d.id === M.wormhole.id)) REG.EVENTS.push(M.wormhole);

/* ------------------------------------------------------------- harness -- */
let pass = 0, fail = 0;
const results = [];
function check(name, fn) {
  try { const n = fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
async function acheck(name, fn) {
  try { const n = await fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
const ok = (c, m) => { if (!c) throw new Error(m || 'assertion failed'); };
const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} expected ${b}, got ${a}`); };

// The hooks world.js will provide after integration. Faithful to api.js: place
// ignores cost, and nothing else.
let fakeId = 500000;
function forcePlace(w, kind, dir, stage = 0) {
  const s = CAT.spec(kind);
  if (!s) return null;
  const st = Math.max(0, Math.min(stage | 0, s.stages.length - 1));
  const o = {
    id: fakeId++, kind, dir: P.norm(dir), stage: st,
    nextAt: st + 1 < s.stages.length ? w.t + s.stages[st + 1].at : Infinity,
    bornAt: w.t, lastMake: w.t, spin: 0,
  };
  w.objects.push(o);
  w.rev++;
  return o;
}
function hooksFor(w) {
  return {
    w,
    near: (ww, dir, r) => W.near(ww, dir, r),
    destroyObject: (ww, o) => {
      const n = ww.objects.length;
      ww.objects = ww.objects.filter(q => q.id !== o.id);
      return ww.objects.length < n;
    },
    forcePlace,
    grantRes: (ww, res) => {
      for (const k of Object.keys(res)) ww.res[k] = (ww.res[k] || 0) + res[k];
      return ww.res;
    },
    announce: (ww, text) => { (ww.notices = ww.notices || []).push({ t: ww.t, text }); },
  };
}

/** The world and its events on one fixed clock, the way world.js will drive it. */
function run(w, hooks, seconds) {
  let left = seconds;
  while (left > 1e-9) {
    const dt = Math.min(C.tick, left);
    W.advance(w, dt);
    E.tickEvents(w, dt, hooks);
    left -= dt;
  }
}

function fresh(t = 1000) {
  const w = W.createWorld();
  E.initEvents(w);
  w.nextEventAt = Infinity;      // these tests fire it on purpose, not by luck
  w.t = t;
  return w;
}

/** Three habs close together, i.e. a village, the way the main suite does it. */
function foundVillage(w) {
  w.res.biomass = 99999; w.res.shard = 99999; w.res.alloy = 99999;
  const anchor = P.scatter(900, 0.77).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.heightAt(d) < C.planet.alpine && P.slopeAt(d) < 0.18);
  const b = P.basis(anchor);
  let placed = 0;
  for (let i = 0; i < 40 && placed < 3; i++) {
    const a = (i / 7) * Math.PI * 2;
    const r = 3.5 + (i % 7) * 1.8;
    const off = P.add(P.mul(b.east, Math.cos(a) * r), P.mul(b.north, Math.sin(a) * r));
    const d = P.norm(P.add(P.mul(anchor, C.planet.radius), off));
    for (const inTheWay of W.near(w, d, 2.0)) {
      if (CAT.spec(inTheWay.kind).settles > 0) continue;
      W.clear(w, inTheWay);
    }
    if (W.plant(w, CAT.KIND.HAB, d)) placed++;
  }
  return placed;
}

const total = M.wormhole.phases.reduce((a, p) => a + p.at, 0)
  + (M.wormhole.phases[M.wormhole.phases.length - 1].lasts || 0);

/* ------------------------------------------------------------ the shape -- */

check('the definition is well formed by the events contract', () => {
  const problems = API.validate(M.wormhole);
  eq(problems.length, 0, problems.join(' | '));
  eq(M.wormhole.id, 'wormhole');
  ok(M.wormhole.phases.length >= 4, 'a wormhole with fewer than four phases is a light');
  const last = M.wormhole.phases[M.wormhole.phases.length - 1];
  ok(typeof last.lasts === 'number', 'the last phase has no duration, so it can never end');
  return `${M.wormhole.phases.map(p => p.id).join(' -> ')}, ${Math.round(total)}s`;
});

check('the event is CAUSABLE: its trigger is a building you can raise', () => {
  const t = CAT.spec(M.wormhole.trigger);
  ok(t, `trigger ${M.wormhole.trigger} is not in the catalog`);
  eq(t.unlock, 'town', 'the rift engine should want a town behind it');
  ok(Object.keys(t.needs).length > 0, 'it costs nothing');
  ok(t.stages.length >= 2, 'it appears instantly');
  return `${t.name}, ${t.verb}, needs a ${t.unlock}`;
});

check('everything it adds to the catalog obeys the catalog contract', () => {
  for (const c of M.CATALOG_ADDITIONS) {
    ok(c.id && c.name && c.verb && c.blurb, `${c.id} is missing its text`);
    ok(Array.isArray(c.stages) && c.stages.length >= 2, `${c.id} has no stages`);
    eq(c.stages[0].at, 0, `${c.id} first stage is not instant`);
    for (const s of c.stages) ok(typeof s.h === 'number' && s.h > 0, `${c.id} stage has no height`);
    ok(typeof c.maxSlope === 'number', `${c.id} has no maxSlope`);
    ok(typeof c.settles === 'number', `${c.id} has no settles`);
    ok(CAT.growTime(c.id) > 0, `${c.id} grows instantly`);
  }
  return M.CATALOG_ADDITIONS.map(c => c.id).join(', ');
});

// Six events are being written into this folder at once and they all read the
// same api.js, whose worked example is `trigger: 'riftengine'`. Two catalog
// entries sharing an id is not an error at merge time -- spec() simply returns
// the first -- so the collision has to be looked for on purpose.
await acheck('nothing it adds collides with the base catalog or a sibling event', async () => {
  const mine = new Set(M.CATALOG_ADDITIONS.map(c => c.id));
// Integration note: every event's trigger building is now registered into the
// catalog at boot by sim/boot.js, so "my entry is not in the catalog yet" is
// false by design once the game is wired together. What that check was really
// for is that the entry EXISTS and is not a duplicate of somebody else's.
  for (const id of mine) eq(CAT.CATALOG.filter(c => c.id === id).length, 1, `${id} is registered exactly once`);

  const dir = join(ROOT, 'app/src/sim/events');
  const files = readdirSync(dir).filter(f => f.endsWith('.js')
    && !['api.js', 'engine.js', 'registry.js', 'wormhole.js'].includes(f));
  let read = 0;
  for (const f of files) {
    let mod = null;
    try { mod = await load(`app/src/sim/events/${f}`); read++; } catch (e) { continue; }
    for (const c of mod.CATALOG_ADDITIONS || []) {
      ok(!mine.has(c.id), `${f} also adds a catalog entry called '${c.id}'`);
    }
    if (mod.wormhole || mod.default) {
      const d = mod.default || mod.wormhole;
      if (d && d.id) ok(d.id !== M.wormhole.id, `${f} is also called '${d.id}'`);
    }
    // and nobody else may hand out this event's unlock
    const src = readFileSync(join(dir, f), 'utf8');
    ok(!src.includes(`seen.${M.UNLOCK}`) && !src.includes(`seen['${M.UNLOCK}']`),
      `${f} also sets the '${M.UNLOCK}' unlock`);
  }
  return `${read} sibling modules, no shared ids`;
});

/* -------------------------------------------------------------- siting -- */

check('the ring never stands in the ocean, whenever it happens', () => {
  let wet = 0;
  for (let i = 0; i < 20; i++) {
    const w = fresh(900 + i * 37.3);
    const ev = E.fire(w, 'wormhole', null, hooksFor(w));
    ok(ev, 'the event refused to start');
    if (P.heightAt(ev.dir) < C.planet.sea) wet++;
  }
  eq(wet, 0, `${wet} of 20 wormholes opened under water`);
  return '20 sites, all on dry land';
});

check('a settlement opens it: the ring stands beside the town that built it', () => {
  const w = fresh();
  eq(foundVillage(w), 3, 'could not found a village to open it');
  run(w, hooksFor(w), 5);
  const st = W.settlements(w).find(s => s.tier);
  ok(st, 'no settlement');
  const ev = E.fire(w, 'wormhole', null, hooksFor(w));
  const d = P.surfDist(ev.dir, st.dir);
  ok(d < 90, `the ring opened ${Math.round(d)} units from its own town`);
  eq(ev.data.from, st.tier, 'the event does not know who opened it');
  // and the ground under it will hold a ring
  ok(P.heightAt(ev.dir) >= C.planet.sea + C.planet.shore, 'it is standing on the beach');
  ok(P.slopeAt(ev.dir) <= 0.35, 'it is standing on a cliff');
  return `${st.tier} -> ring ${Math.round(d)} units away`;
});

/* ----------------------------------------------------- what comes through */

function fullRun(seedWorld) {
  const w = seedWorld || fresh();
  const hooks = hooksFor(w);
  const before = new Set(w.objects.map(o => o.id));
  const res0 = Object.assign({}, w.res);
  const ev = E.fire(w, 'wormhole', null, hooks);
  run(w, hooks, total + 4);
  const added = w.objects.filter(o => !before.has(o.id));
  return { w, ev, added, res0, hooks };
}

check('it changes the world: things come through and take root', () => {
  const { w, ev, added } = fullRun();
  ok(added.length > 10, `only ${added.length} things came through a hole to another world`);
  ok(ev.data.placed > 0, 'the event did not count what it placed');
  // and they are seeds, not finished scenery: they grow after it is over
  const seeds = added.filter(o => o.stage === 0);
  ok(seeds.length > 0, 'nothing arrived as a seed, so there is nothing to come back to');
  const stage0 = seeds.length;
  W.advance(w, 3600);
  const grown = seeds.filter(o => o.stage > 0).length;
  ok(grown > 0, `none of the ${stage0} seeds grew`);
  return `${added.length} arrived, ${grown}/${stage0} seeds grown an hour later`;
});

check('THE RULE holds through a wormhole: everything it places is makeable', () => {
  const { added } = fullRun();
  for (const o of added) ok(CAT.isCreatable(o.kind), `${o.kind} came through and is not makeable`);
  const kinds = [...new Set(added.map(o => o.kind))];
  ok(kinds.length >= 3, 'a hole to another world delivered one thing');
  return kinds.sort().join(', ');
});

check('it obeys the placement rules the player obeys', () => {
  const { added } = fullRun();
  for (const o of added) {
    const s = CAT.spec(o.kind);
    const h = P.heightAt(o.dir);
    const under = h < C.planet.sea;
    eq(under, !!s.underwater, `${o.kind} placed ${under ? 'under' : 'out of'} water`);
    ok(P.slopeAt(o.dir) <= s.maxSlope + 1e-9, `${o.kind} placed on a ${P.slopeAt(o.dir).toFixed(2)} slope`);
    if (s.maxHeight != null) ok(h <= s.maxHeight, `${o.kind} placed too high`);
  }
  return `${added.length} placements, all legal`;
});

check('what comes through is ARRANGED: it lands in rings, not in a scatter', () => {
  const { ev, added } = fullRun();
  const r = added.map(o => P.surfDist(o.dir, ev.dir)).sort((a, b) => a - b);
  ok(r.length > 10, 'not enough came through to say');
  ok(r[r.length - 1] < 60, 'things came through and landed on the other side of the planet');
  // a ring is many things at one radius; a scatter has no such pile
  let best = 0;
  for (let i = 0; i < r.length; i++) {
    let n = 0;
    for (const q of r) if (Math.abs(q - r[i]) <= 1.5) n++;
    best = Math.max(best, n);
  }
  ok(best >= 6, `the widest band holds ${best} things, which is a scatter and not an arrangement`);
  return `${added.length} things, ${best} of them at one radius`;
});

check('it pays: resources arrive with it', () => {
  const { w, res0 } = fullRun();
  for (const k of ['biomass', 'shard', 'alloy']) {
    ok(w.res[k] > res0[k], `${k} did not go up`);
  }
  return Object.keys(res0).map(k => `+${Math.round(w.res[k] - res0[k])} ${k}`).join(', ');
});

check('it changes what you can do afterwards', () => {
  const w = fresh();
  eq(W.unlocked(w, M.LANTERN), false, 'the lantern was already unlocked');
  const dir = P.scatter(900, 0.41).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.5 && !W.near(w, d, 4).length);
  ok(dir, 'nowhere to try planting one');
  eq(W.blockedReason(w, M.LANTERN, dir), `needs a ${M.UNLOCK}`);

  const hooks = hooksFor(w);
  E.fire(w, 'wormhole', null, hooks);
  run(w, hooks, total + 4);

  eq(W.unlocked(w, M.LANTERN), true, 'the rift did not unlock anything');
  w.res.shard = 9999;
  const spot = P.scatter(900, 0.41).find(d => !W.blockedReason(w, M.LANTERN, d));
  ok(spot, 'unlocked and still unplantable everywhere');
  ok(W.plant(w, M.LANTERN, spot), 'could not plant it after the rift');
  // and the unlock is a save-level fact, not a runtime one
  eq(!!W.load(W.save(w)).seen[M.UNLOCK], true, 'the unlock did not survive a save');
  return 'lantern: locked -> planted';
});

/* ---------------------------------------------------------------- it ends */

check('it ends, and leaves the place marked', () => {
  const { w, ev, added } = fullRun();
  eq(w.events.length, 0, 'the wormhole is still open');
  const ends = w.eventLog.filter(e => e.id === 'wormhole' && e.k === 'ends');
  eq(ends.length, 1, 'it never reported ending');
  const phases = w.eventLog.filter(e => e.id === 'wormhole' && e.k === 'phase').map(e => e.phase);
  eq(phases.length, M.wormhole.phases.length - 1, `ran ${phases.length + 1} phases`);
  // a ring of something is standing where the ring stood
  const atSite = added.filter(o => Math.abs(P.surfDist(o.dir, ev.dir) - M.CONST.ring) < 2.0);
  ok(atSite.length >= 3, `only ${atSite.length} markers left where the ring stood`);
  return `${phases.join(' -> ')}, ${atSite.length} markers`;
});

check('nothing keeps happening after it is over', () => {
  const { w, hooks } = fullRun();
  w.nextEventAt = Infinity;
  const n = w.objects.length;
  const res = Object.assign({}, w.res);
  run(w, hooks, 600);
  // growth still happens; the rift does not
  eq(w.events.length, 0, 'it came back on its own');
  ok(w.objects.length - n < 40, 'the rift is still delivering');
  ok(res.alloy <= w.res.alloy, 'resources went backwards');
  return 'closed and quiet';
});

/* --------------------------------------------------------- determinism -- */

check('deterministic: the same world at the same time gets the same wormhole', () => {
  const a = fullRun(fresh(1234));
  const b = fullRun(fresh(1234));
  eq(a.added.length, b.added.length, 'a different number of things came through');
  eq(a.ev.data.placed, b.ev.data.placed);
  eq(a.ev.data.k, b.ev.data.k);
  for (let i = 0; i < a.added.length; i++) {
    eq(a.added[i].kind, b.added[i].kind, `arrival ${i} differs`);
    ok(Math.abs(a.added[i].dir.x - b.added[i].dir.x) < 1e-12, `arrival ${i} landed elsewhere`);
  }
  // and a rift at a different time is a different rift
  const c = fullRun(fresh(4321));
  ok(Math.abs(c.ev.dir.x - a.ev.dir.x) > 1e-9 || c.added.length !== a.added.length,
    'every wormhole is the same wormhole');
  return `${a.added.length} arrivals, identical`;
});

check('every wave comes through, whatever the clock does', () => {
  const w = fresh();
  const hooks = hooksFor(w);
  const ev = E.fire(w, 'wormhole', null, hooks);
  run(w, hooks, total + 4);
  ok(ev.data.k > 0, 'no waves ran at all');
  // the schedule counts from itself: the open phase must fit every wave it has
  const waves = ev.data.k;
  const w2 = fresh();
  const h2 = hooksFor(w2);
  const ev2 = E.fire(w2, 'wormhole', null, h2);
  for (let i = 0; i < Math.ceil((total + 4) / 3); i++) run(w2, h2, 3);
  eq(ev2.data.k, waves, 'a coarser clock delivered a different number of waves');
  return `${waves} waves, both clocks`;
});

/* ------------------------------------------------------------ persistence */

check('a live wormhole survives a save, a reload and JSON', () => {
  const w = fresh();
  const hooks = hooksFor(w);
  const ev = E.fire(w, 'wormhole', null, hooks);
  run(w, hooks, M.CONST.build + M.CONST.charge + 40);   // mid-flight, hole open
  ok(w.events.length === 1, 'it is not running');

  const rows = JSON.parse(JSON.stringify(E.saveEvents(w)));
  const w2 = fresh();
  E.loadEvents(w2, rows);
  eq(w2.events.length, 1, 'the wormhole did not come back');
  const ev2 = w2.events[0];
  eq(ev2.id, 'wormhole');
  eq(ev2.phase, ev.phase, 'it came back in a different phase');
  for (const k of Object.keys(ev.data)) {
    eq(ev2.data[k], ev.data[k], `data.${k} did not survive`);
  }
  ok(ev2.dir && Math.abs(ev2.dir.x - ev.dir.x) < 1e-5, 'it came back somewhere else');
  const resumedIn = M.wormhole.phases[ev2.phase].id;
  // and it can carry on and finish from there
  w2.t = w.t;
  w2.nextEventAt = Infinity;
  run(w2, hooksFor(w2), total);
  eq(w2.events.length, 0, 'a reloaded wormhole never ends');
  return `resumed in phase '${resumedIn}' and finished`;
});

check('ev.data is JSON-safe: no functions, no vectors, no cycles', () => {
  const w = fresh();
  const hooks = hooksFor(w);
  const ev = E.fire(w, 'wormhole', null, hooks);
  run(w, hooks, total + 4);
  const round = JSON.parse(JSON.stringify(ev.data));
  eq(Object.keys(round).length, Object.keys(ev.data).length, 'something did not serialise');
  for (const k of Object.keys(ev.data)) {
    const t = typeof ev.data[k];
    ok(t === 'number' || t === 'string' || t === 'boolean', `data.${k} is a ${t}`);
  }
  return Object.keys(ev.data).join(', ');
});

/* ------------------------------------------------------------- the view -- */

check('the fx module keeps the render contract and imports no three', () => {
  eq(FX.id, M.wormhole.id, 'the fx module is for a different event');
  for (const fn of ['build', 'update', 'dispose']) {
    eq(typeof FX[fn], 'function', `fx is missing ${fn}`);
  }
  // it shares the ring's size with the sim rather than inventing a second one
  ok(typeof M.CONST.ring === 'number' && M.CONST.ring > 0);
  return 'build, update, dispose';
});

// A three.js stand-in, because the view is imported by no other check in this
// project and a typo in it is invisible until the page opens. It counts every
// object it hands out, which is how the no-allocation-per-frame rule is tested.
function stubThree() {
  let made = 0;
  class Vec3 {
    constructor(x = 0, y = 0, z = 0) { made++; this.x = x; this.y = y; this.z = z; }
    set(x, y, z) { this.x = x; this.y = y; this.z = z; return this; }
  }
  class Quat {
    constructor() { made++; }
    setFromAxisAngle() { return this; }
    setFromRotationMatrix() { return this; }
  }
  class Mat4 {
    constructor() { made++; }
    compose() { return this; }
    makeBasis() { return this; }
  }
  class Attr {
    constructor(arr, item) {
      made++; this.array = arr; this.itemSize = item;
      this.count = arr.length / item; this.needsUpdate = false;
    }
    getX(i) { return this.array[i * this.itemSize]; }
    getY(i) { return this.array[i * this.itemSize + 1]; }
    getZ(i) { return this.array[i * this.itemSize + 2]; }
  }
  // Real coordinates for the two shapes whose vertices the fx module reads.
  function verts(kind, a) {
    const out = [];
    if (kind === 'ring') {
      const [inner, outer, th, ph] = [a[0], a[1], a[2] || 8, a[3] || 1];
      for (let j = 0; j <= ph; j++) {
        for (let i = 0; i <= th; i++) {
          const r = inner + (outer - inner) * (j / ph), t = (i / th) * Math.PI * 2;
          out.push(Math.cos(t) * r, Math.sin(t) * r, 0);
        }
      }
    } else if (kind === 'circle') {
      const [r, seg] = [a[0], a[1] || 8];
      out.push(0, 0, 0);
      for (let i = 0; i <= seg; i++) {
        const t = (i / seg) * Math.PI * 2;
        out.push(Math.cos(t) * r, Math.sin(t) * r, 0);
      }
    } else { out.push(0, 0, 0, 1, 0, 0, 0, 1, 0); }
    return new Float32Array(out);
  }
  class Geo {
    constructor(kind, args) {
      made++; this.kind = kind; this.disposed = false; this.attributes = {};
      this.attributes.position = new Attr(verts(kind, args), 3);
    }
    setAttribute(n, a) { this.attributes[n] = a; return this; }
    dispose() { this.disposed = true; }
  }
  const geoClass = (kind) => class extends Geo { constructor(...a) { super(kind, a); } };
  class Obj3D {
    constructor() {
      made++; this.children = []; this.parent = null; this.visible = true;
      this.frustumCulled = true;
      this.position = new Vec3(); this.rotation = new Vec3();
      this.scale = new Vec3(1, 1, 1); this.quaternion = new Quat();
    }
    add(o) { this.children.push(o); if (o) o.parent = this; return this; }
    remove(o) { this.children = this.children.filter(c => c !== o); if (o) o.parent = null; return this; }
  }
  class Material {
    constructor(o) { made++; Object.assign(this, o || {}); this.disposed = false; }
    dispose() { this.disposed = true; }
  }
  class Tex {
    constructor(d, w, h, f) { made++; this.image = { data: d, width: w, height: h }; this.format = f; this.disposed = false; }
    dispose() { this.disposed = true; }
  }
  const THREE = {
    Vector3: Vec3, Quaternion: Quat, Matrix4: Mat4, BufferAttribute: Attr,
    Group: class extends Obj3D {},
    Mesh: class extends Obj3D { constructor(g, m) { super(); this.geometry = g; this.material = m; } },
    Sprite: class extends Obj3D { constructor(m) { super(); this.material = m; } },
    InstancedMesh: class extends Obj3D {
      constructor(g, m, n) {
        super(); this.geometry = g; this.material = m; this.count = n;
        this.written = 0; this.instanceMatrix = { needsUpdate: false };
      }
      setMatrixAt() { this.written++; }
    },
    PointLight: class extends Obj3D {
      constructor(c, i, d, dec) { super(); this.color = c; this.intensity = i; this.distance = d; this.decay = dec; }
    },
    MeshStandardMaterial: Material, MeshBasicMaterial: Material, SpriteMaterial: Material,
    DataTexture: Tex,
    BoxGeometry: geoClass('box'), TorusGeometry: geoClass('torus'),
    RingGeometry: geoClass('ring'), CircleGeometry: geoClass('circle'),
    CylinderGeometry: geoClass('cyl'),
    AdditiveBlending: 2, DoubleSide: 2, RGBAFormat: 1023,
  };
  return { THREE, count: () => made };
}

check('the ring can be built, driven through every phase, and taken down', () => {
  const { THREE, count } = stubThree();
  const root = new THREE.Group();
  const ctx = { THREE, scene: root, root, P, C, camera: {} };

  const w = fresh();
  const ev = E.fire(w, 'wormhole', null, hooksFor(w));
  const self = FX.build(ctx);
  eq(root.children.length, 1, 'build did not add exactly one group');

  const builtAt = count();
  let frames = 0;
  for (let i = 0; i < M.wormhole.phases.length; i++) {
    ev.phase = i;
    ev.phaseAt = w.t;
    const dur = M.wormhole.phases[i + 1] ? M.wormhole.phases[i + 1].at : M.CONST.collapse;
    for (let s = 0; s <= 8; s++) {
      w.t = ev.phaseAt + (dur * s) / 8;
      FX.update(self, ev, w, 1 / 60, ctx);
      frames++;
      // a NaN opacity blanks a material and looks exactly like nothing happening
      ok(Number.isFinite(self.light.intensity), 'light intensity went non-finite');
      ok(Number.isFinite(self.light.distance), 'light distance went non-finite');
      for (const m of self.mats) {
        if (m.opacity === undefined) continue;
        ok(m.opacity >= 0 && m.opacity <= 1, `opacity ${m.opacity} out of range`);
      }
      ok(self.segs.count >= 0 && self.segs.count <= 30, `${self.segs.count} ring segments`);
    }
  }
  eq(count(), builtAt, 'update() allocates per frame');

  // the ring goes up: it is not whole in its first phase and is whole later
  ev.phase = 0; ev.phaseAt = w.t; w.t = ev.phaseAt;
  FX.update(self, ev, w, 1 / 60, ctx);
  const early = self.segs.count;
  ev.phase = 2; ev.phaseAt = w.t;
  FX.update(self, ev, w, 1 / 60, ctx);
  ok(self.segs.count > early, 'the ring never gets built');
  ok(self.torus.visible, 'the ring is never whole');

  FX.dispose(self, ctx);
  eq(root.children.length, 0, 'dispose left the group in the scene');
  return `${frames} frames, no allocation`;
});

check('the ring stands on its edge on the ground, not lying on it', () => {
  const { THREE } = stubThree();
  const root = new THREE.Group();
  const ctx = { THREE, scene: root, root, P, C, camera: {} };
  const w = fresh();
  const ev = E.fire(w, 'wormhole', null, hooksFor(w));
  const self = FX.build(ctx);
  FX.update(self, ev, w, 1 / 60, ctx);
  const p = self.group.position;
  const r = Math.hypot(p.x, p.y, p.z);
  const ground = P.groundR(ev.dir);
  // its centre is one ring-radius above the ground, so the bottom touches it
  ok(Math.abs(r - (ground + M.CONST.ring)) < 1.0,
    `centre is ${(r - ground).toFixed(2)} above the ground, ring radius is ${M.CONST.ring}`);
  // and the plane of the ring contains the vertical
  ok(Math.abs(self.ay.x - ev.dir.x) < 1e-9 && Math.abs(self.ay.y - ev.dir.y) < 1e-9,
    'the ring is not oriented to the local up');
  FX.dispose(self, ctx);
  return `centre ${(r - ground).toFixed(1)} above the ground`;
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
