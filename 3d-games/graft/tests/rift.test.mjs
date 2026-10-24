// node tests/rift.test.mjs   — THE RIFT, on its own. No browser, no framework.
//
// Everything here asserts BEHAVIOUR: that the rift actually changes the world,
// that the same world at the same time tears open the same way twice, that its
// state survives a save, and that it ends. No number this module invented is
// asserted against itself.

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const load = (p) => import(pathToFileURL(join(ROOT, p)));

let pass = 0, fail = 0;
const results = [];
async function check(name, fn) {
  try { const n = await fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} expected ${b}, got ${a}`); };
const ok = (c, m) => { if (!c) throw new Error(m || 'assertion failed'); };

const { C } = await load('app/src/sim/const.js');
const P = await load('app/src/sim/planet.js');
const CAT = await load('app/src/sim/catalog.js');
const W = await load('app/src/sim/world.js');
const API = await load('app/src/sim/events/api.js');
const ENG = await load('app/src/sim/events/engine.js');
const REG = await load('app/src/sim/events/registry.js');
const R = await load('app/src/sim/events/rift.js');
const FX = await load('app/src/view/fx/rift.js');

// The integrator does exactly this: merges the additions into the catalog and
// the definition into the registry. Doing it here, in memory, is what lets this
// file test the shipping code paths instead of a copy of them.
for (const add of R.CATALOG_ADDITIONS) {
  if (!CAT.CATALOG.some(c => c.id === add.id)) CAT.CATALOG.push(add);
}
CAT.KIND.ANOMALY = R.RIFT_KIND.ANOMALY;
CAT.KIND.ENGINE = R.RIFT_KIND.ENGINE;
if (!REG.EVENTS.some(d => d.id === R.rift.id)) REG.EVENTS.push(R.rift);

/* ---------------------------------------------------------------- harness --
   The world module does not yet export the hooks the event api needs -- that
   is the integrator's half. This is the smallest honest stand-in: every hook
   does what its name says and nothing else. */
let hookId = 900000;
function hooksFor(w) {
  return {
    w,
    near: (ww, dir, r) => W.near(ww, dir, r),
    destroyObject(ww, o) {
      const n = ww.objects.length;
      ww.objects = ww.objects.filter(q => q.id !== o.id);
      if (ww.objects.length === n) return false;
      ww.rev++;
      return true;
    },
    forcePlace(ww, kind, dir, stage = 0) {
      const s = CAT.spec(kind);
      if (!s) return null;
      const o = {
        id: hookId++, kind, dir: P.norm(dir), stage,
        nextAt: stage + 1 < s.stages.length ? ww.t + s.stages[stage + 1].at : Infinity,
        bornAt: ww.t, lastMake: ww.t, spin: 0,
      };
      ww.objects.push(o);
      ww.rev++;
      return o;
    },
    grantRes(ww, res) {
      for (const k of Object.keys(res)) ww.res[k] = (ww.res[k] || 0) + res[k];
      return ww.res;
    },
    announce(ww, text) {
      ww.notices = ww.notices || [];
      ww.notices.push({ t: ww.t, text });
    },
  };
}

/** Advance a world in slices, driving the event engine with it. */
function run(w, seconds, withEvents = true, slice = 0.5) {
  const h = hooksFor(w);
  for (let left = seconds; left > 1e-9; left -= slice) {
    const dt = Math.min(slice, left);
    W.advance(w, dt);
    if (withEvents) ENG.tickEvents(w, dt, h);
  }
  return w;
}

/** A world with a rift torn open at a chosen moment, and its event. */
function openRift(seed = C.seed, warm = 0) {
  const w = W.createWorld(seed);
  w.nextEventAt = Infinity;   // only the rift may happen in this test
  ENG.initEvents(w);
  w.nextEventAt = Infinity;              // no OTHER event may wander in
  if (warm) run(w, warm);
  const ev = ENG.fire(w, 'rift', null, hooksFor(w));
  return { w, ev };
}

const kindsIn = (w, dir, r) => W.near(w, dir, r).map(o => o.kind).sort().join(',');
const stagesIn = (w, dir, r) => W.near(w, dir, r).reduce((a, o) => a + o.stage, 0);
const LIFE = R.RIFT.phase.crack + R.RIFT.phase.opening
           + R.RIFT.phase.other + R.RIFT.phase.closing;

/* -------------------------------------------------------------- the shape --- */

await check('the definition is well-formed and the engine accepts it', () => {
  eq(API.validate(R.rift).length, 0, API.validate(R.rift).join(' | '));
  // scoped to this event on purpose: the registry is shared, and a neighbour's
  // bug is not this file's to report
  const problems = ENG.auditEvents().filter(p => p.includes('rift'));
  eq(problems.length, 0, problems.join(' | '));
  eq(R.rift.phases[0].at, 0);
  ok(R.rift.phases[R.rift.phases.length - 1].lasts > 0, 'the last phase never runs out');
  return `${R.rift.phases.length} phases: ` + R.rift.phases.map(p => p.id).join(' -> ');
});

await check('its catalog additions are shaped like catalog entries', () => {
  ok(R.CATALOG_ADDITIONS.length > 0, 'the rift adds nothing');
  const sample = CAT.CATALOG[0];
  for (const e of R.CATALOG_ADDITIONS) {
    for (const key of ['id', 'name', 'verb', 'blurb', 'needs', 'stages', 'maxSlope', 'settles']) {
      ok(e[key] != null, `${e.id} has no ${key}`);
      eq(typeof e[key], typeof sample[key], `${e.id}.${key} is the wrong sort of thing`);
    }
    ok(e.stages.length >= 2, `${e.id} does not grow`);
    eq(e.stages[0].at, 0, `${e.id} does not start at 0`);
    for (const st of e.stages) ok(st.name && typeof st.h === 'number', `${e.id} has a bad stage`);
    eq(CAT.CATALOG.filter(c => c.id === e.id).length, 1, `${e.id} is in the catalog twice`);
    ok(CAT.isCreatable(e.id), `${e.id} cannot be made`);
  }
  return R.CATALOG_ADDITIONS.map(e => e.name).join(' + ');
});

await check('the trigger is a building the player raises, and it names this event', () => {
  const t = CAT.spec(R.rift.trigger);
  ok(t, `the event names a trigger (${R.rift.trigger}) that is not in the catalog`);
  eq(t.tears, R.rift.id, 'the trigger building does not tear this event open');
  ok(Object.keys(t.needs).length > 0, 'the trigger is free');
  ok(t.unlock, 'the trigger is available from the first minute');
  ok(t.settles === 0 && t.harvest === null, 'the trigger is pretending to be a house');
  return `${t.name}: ` + Object.entries(t.needs).map(([k, v]) => `${v} ${k}`).join(', ');
});

/* --------------------------------------------------------------- the rule --- */

await check('THE RULE: everything the rift leaves behind is something you can make', () => {
  const { w } = openRift();
  run(w, LIFE + 60);
  eq(w.events.length, 0, 'it is still open');
  for (const o of w.objects) ok(CAT.isCreatable(o.kind), `${o.kind} appeared and is not makeable`);
  const kinds = new Set(w.objects.map(o => o.kind));
  return `${w.objects.length} objects, ${kinds.size} kinds, all of them in the catalog`;
});

await check('a rift lands on dry ground, not in the deep sea', () => {
  let moved = 0;
  for (let i = 0; i < 12; i++) {
    const w = W.createWorld();
    w.nextEventAt = Infinity;   // only the rift may happen in this test
    ENG.initEvents(w);
    w.nextEventAt = Infinity;
    w.t = 100 + i * 37.3;                 // pickSite is keyed on the world clock
    const ev = ENG.fire(w, 'rift', null, hooksFor(w));
    const h = P.heightAt(ev.dir);
    ok(h >= C.planet.sea, `rift ${i} opened under ${(C.planet.sea - h).toFixed(1)} units of water`);
    if (h > C.planet.sea) moved++;
    w.events.length = 0;
  }
  return `${moved}/12 sites usable`;
});

/* ---------------------------------------------------------- what it does --- */

await check('the rift changes the world, and a world without one is untouched', () => {
  const { w, ev } = openRift();
  const site = ev.dir, r = R.RIFT.radius;
  const control = W.createWorld();
  control.nextEventAt = Infinity;   // only the rift may happen in this test
  ENG.initEvents(control);
  control.nextEventAt = Infinity;

  const before = kindsIn(w, site, r);
  run(w, LIFE + 30);
  run(control, LIFE + 30, false);

  const after = kindsIn(w, site, r);
  const untouched = kindsIn(control, site, r);
  eq(untouched, before, 'the control world drifted on its own');
  ok(after !== before, 'the rift came and went and left the ground exactly as it was');
  ok(W.near(w, site, r).length >= W.near(control, site, r).length,
     'the rift emptied the ground instead of filling it');
  return `${W.near(control, site, r).length} things inside became ${W.near(w, site, r).length}`;
});

await check('growth inside the rift runs at a different speed', () => {
  // The same planting, in the same places, in two identical worlds. Only one
  // of them has a hole in it.
  const { w, ev } = openRift();
  const site = ev.dir;
  const control = W.createWorld();
  control.nextEventAt = Infinity;   // only the rift may happen in this test
  ENG.initEvents(control);
  control.nextEventAt = Infinity;

  const b = P.basis(site);
  const hw = hooksFor(w), hc = hooksFor(control);
  // Track exactly the saplings this check planted. The rift seeds vanes of its
  // own through the same hook, and counting those measured the seeder rather
  // than the clock -- the first version of this check could not fail.
  const mine = [], theirs = [];
  // The rift flickers things between kinds on purpose, so a fair share of any
  // sample stops being a tree before the clock is read. Plant enough that
  // enough survive AS TREES to compare -- the sample size is the fix, not the
  // mechanic.
  for (let i = 0; i < 72 && mine.length < 24; i++) {
    const a = (i / 12) * Math.PI * 2;
    const rr = 5 + (i % 6) * 2.2;
    const d = P.norm(P.add(P.mul(site, C.planet.radius),
      P.add(P.mul(b.east, Math.cos(a) * rr), P.mul(b.north, Math.sin(a) * rr))));
    if (P.heightAt(d) < C.planet.sea) continue;
    mine.push(hw.forcePlace(w, CAT.KIND.TREE, d, 0));
    theirs.push(hc.forcePlace(control, CAT.KIND.TREE, d, 0));
  }
  const planted = mine.length;
  ok(planted >= 4, `only ${planted} test plantings took`);

  const span = R.RIFT.phase.crack + R.RIFT.phase.opening + 120;
  run(w, span);
  run(control, span, false);

  // Only the saplings that are STILL saplings on both sides. A flicker turns a
  // tree into a bloom, and a bloom has shorter stages -- so leaving them in
  // measured the flicker and not the clock, and this check passed with the
  // acceleration ripped out.
  let inside = 0, outside = 0, pairs = 0;
  for (let i = 0; i < mine.length; i++) {
    if (mine[i].kind !== CAT.KIND.TREE || theirs[i].kind !== CAT.KIND.TREE) continue;
    inside += mine[i].stage; outside += theirs[i].stage; pairs++;
  }
  ok(pairs >= 3, `only ${pairs} saplings survived as saplings`);
  ok(inside > outside, `inside ${inside} stages, outside ${outside} -- the rift is not faster`);
  return `${pairs} still-trees: ${outside} stages outside, ${inside} inside`;
});

await check('things inside flicker into other kinds, and stay that way', () => {
  const { w, ev } = openRift();
  const site = ev.dir, r = R.RIFT.radius;
  const was = new Map(W.near(w, site, r * 1.2).map(o => [o.id, o.kind]));
  run(w, LIFE + 30);
  eq(w.events.length, 0, 'it never closed');
  let changed = 0;
  for (const o of w.objects) {
    if (was.has(o.id) && was.get(o.id) !== o.kind) changed++;
  }
  ok(changed > 0, 'nothing inside ever became anything else');
  // and it is a lasting change: the rift is long gone by now
  run(w, 200);
  let still = 0;
  for (const o of w.objects) if (was.has(o.id) && was.get(o.id) !== o.kind) still++;
  ok(still >= changed, 'the changes unwound after it closed');
  return `${changed} things are not what they were`;
});

await check('it yields a resource you cannot get anywhere else', () => {
  const { w } = openRift();
  run(w, LIFE + 30);
  const grown = w.objects.filter(o => o.kind === R.RIFT_KIND.ANOMALY);
  ok(grown.length > 0, 'the rift grew nothing of its own');

  const kinds = Object.keys(CAT.spec(R.RIFT_KIND.ANOMALY).harvest);
  eq(kinds.length > 0, true);
  const prize = kinds[0];
  ok(!(prize in { biomass: 1, shard: 1, alloy: 1 }), `${prize} is an ordinary resource`);

  // nothing that existed before the rift produces it
  for (const c of CAT.CATALOG) {
    if (R.CATALOG_ADDITIONS.some(a => a.id === c.id)) continue;
    ok(!(c.harvest && c.harvest[prize]), `${c.id} already harvests ${prize}`);
    ok(!(c.produces && c.produces[prize]), `${c.id} already produces ${prize}`);
  }

  // and it is really payable: grow one out and take it
  const one = grown[0];
  run(w, CAT.growTime(R.RIFT_KIND.ANOMALY) + 10, false);
  ok(W.finalStage(one), 'the vane never finished growing');
  const before = w.res[prize] || 0;
  ok(W.harvest(w, one), 'the vane refused to be harvested');
  ok((w.res[prize] || 0) > before, `harvesting paid no ${prize}`);
  return `${grown.length} vanes, +${w.res[prize] - before} ${prize}`;
});

await check('what the rift unlocks is locked until you have seen one', () => {
  const clean = W.createWorld();
  clean.nextEventAt = Infinity;   // only the rift may happen in this test
  clean.res = { biomass: 9999, shard: 9999, alloy: 9999, flux: 9999 };
  const dir = P.scatter(400, 0.41).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.2);
  for (const e of R.CATALOG_ADDITIONS) {
    eq(W.unlocked(clean, e.id), false, `${e.id} is available before any rift`);
    eq(W.blockedReason(clean, e.id, dir), `needs a ${e.unlock}`);
  }
  const { w } = openRift();
  eq(!!w.seen.rift, true, 'standing next to a rift unlocked nothing');
  for (const e of R.CATALOG_ADDITIONS) eq(W.unlocked(w, e.id), true, `${e.id} stayed locked`);
  return R.CATALOG_ADDITIONS.map(e => e.name).join(' + ') + ' unlocked';
});

/* -------------------------------------------------------------- mechanics --- */

await check('it ends, inside the lifetime it advertises', () => {
  const { w } = openRift();
  run(w, LIFE - 5);
  eq(w.events.length, 1, 'it ended early');
  run(w, 30);
  eq(w.events.length, 0, `it was still open ${25}s past its own last phase`);
  const log = w.eventLog.filter(e => e.id === 'rift');
  eq(log[0].k, 'begins');
  eq(log[log.length - 1].k, 'ends');
  const phases = log.filter(e => e.k === 'phase').map(e => e.phase);
  eq(phases.join(','), R.rift.phases.slice(1).map(p => p.id).join(','), 'it skipped a phase');
  return `${LIFE}s, ${log.length} log lines`;
});

await check('deterministic: the same world at the same moment tears the same way', () => {
  const a = openRift(11, 60);
  const b = openRift(11, 60);
  eq(a.ev.dir.x, b.ev.dir.x, 'two identical worlds tore in different places');
  run(a.w, LIFE + 20);
  run(b.w, LIFE + 20);
  eq(a.w.objects.length, b.w.objects.length, 'they finished with different numbers of things');
  const ka = a.w.objects.map(o => o.kind + ':' + o.stage).join('|');
  const kb = b.w.objects.map(o => o.kind + ':' + o.stage).join('|');
  eq(ka === kb, true, 'the two worlds disagree about what is standing where');
  // and a different moment gives a different rift
  const c = openRift(11, 137);
  ok(c.ev.dir.x !== a.ev.dir.x || c.ev.dir.z !== a.ev.dir.z, 'every rift opens in the same place');
  return `${a.w.objects.length} objects, identical`;
});

await check('its data survives a save, and it goes on ending afterwards', () => {
  const { w } = openRift();
  run(w, R.RIFT.phase.crack + R.RIFT.phase.opening + 40);
  const live = w.events[0];
  ok(live, 'it closed too early to save');

  const rows = ENG.saveEvents(w);
  const json = JSON.stringify(rows);
  ok(!/undefined|null,null/.test(json.replace(/,null\]/g, '')), 'the save has holes in it');
  const back = JSON.parse(json);

  const w2 = W.load(W.save(w));
  ENG.initEvents(w2);
  w2.nextEventAt = Infinity;
  ENG.loadEvents(w2, back);
  eq(w2.events.length, 1, 'the rift did not come back');
  const rev = w2.events[0];
  eq(rev.id, live.id);
  eq(rev.phase, live.phase, 'it came back in a different phase');
  eq(rev.t0, live.t0);
  eq(JSON.stringify(rev.data), JSON.stringify(live.data), 'its data changed shape in the save');
  for (const k of Object.keys(rev.data)) {
    eq(typeof rev.data[k], 'number', `data.${k} is not a plain number`);
  }
  eq(+rev.dir.y.toFixed(5), +live.dir.y.toFixed(5), 'it came back somewhere else');

  // and the reloaded one still closes on its own
  run(w2, LIFE);
  eq(w2.events.length, 0, 'a reloaded rift never closes');
  return `${Object.keys(live.data).length} fields, ${json.length} bytes`;
});

await check('the trigger tears one open on purpose', () => {
  // exactly the line the integrator adds to grow(): a finished Seam fires it
  const { w } = openRift();
  run(w, LIFE + 30);
  eq(w.events.length, 0);
  const dir = P.scatter(600, 0.28).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.2 && !W.near(w, d, 4).length);
  const seam = hooksFor(w).forcePlace(w, R.rift.trigger, dir,
    CAT.spec(R.rift.trigger).stages.length - 1);
  ok(seam, 'could not raise the trigger');
  const s = CAT.spec(seam.kind);
  ok(s.tears && W.finalStage(seam), 'the seam is not finished');
  const again = ENG.fire(w, s.tears, seam.dir, hooksFor(w));
  ok(again, 'a finished seam did not tear anything open');
  eq(again.id, 'rift');
  run(w, LIFE + 30);
  eq(w.events.length, 0, 'the deliberate one never closed');
  return 'Seam -> rift -> closed';
});

/* ---------------------------------------------------------------- hygiene --- */

const MINE = ['app/src/sim/events/rift.js', 'app/src/view/fx/rift.js', 'tests/rift.test.mjs'];

await check('the sim half is pure', () => {
  const banned = [/\bdocument\b/, /\bwindow\b/, /Math\.random/, /\bnew Date\b/, /Date\.now/,
                  /requestAnimationFrame/, /localStorage/, /from ['"].*three/];
  const src = readFileSync(join(ROOT, 'app/src/sim/events/rift.js'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  const bad = banned.filter(b => b.test(src)).map(String);
  eq(bad.length, 0, bad.join(' | '));
});

await check('no control characters in anything I wrote', () => {
  // Three separate bugs in this portfolio have been a backspace or a NUL that
  // reached a source file through a shell. It compiles; it reads correctly in
  // an editor; it never shows up in a diff.
  const bad = [];
  for (const p of MINE) {
    const t = readFileSync(join(ROOT, p), 'utf8');
    for (let i = 0; i < t.length; i++) {
      const c = t.charCodeAt(i);
      if (c < 32 && c !== 10 && c !== 13 && c !== 9) { bad.push(`${p}@${i}`); break; }
      if (c > 126 && c < 160) { bad.push(`${p}@${i} (C1)`); break; }
    }
  }
  eq(bad.length, 0, bad.join(' | '));
  return `${MINE.length} files clean`;
});

/* --------------------------------------------------------------------- fx --- */

await check('the fx module builds, runs every phase, and clears up after itself', () => {
  const T = stubThree();
  const root = new T.Group();
  const ctx = { THREE: T, scene: new T.Group(), sky: new T.Group(), root, P, C, camera: {} };

  eq(FX.id, R.rift.id, 'the fx module is for a different event');
  for (const fn of ['build', 'update', 'dispose']) eq(typeof FX[fn], 'function', `no ${fn}`);

  const { w, ev } = openRift();
  const self = FX.build(ctx);
  ok(root.children.length > 0, 'build() added nothing to the scene');

  // drive it through the whole life of a real rift, at a real frame rate
  let frames = 0;
  const h = hooksFor(w);
  for (let i = 0; i < Math.ceil((LIFE + 20) / 0.5); i++) {
    W.advance(w, 0.5);
    ENG.tickEvents(w, 0.5, h);
    if (!w.events.length) break;
    for (let f = 0; f < 3; f++) { FX.update(self, w.events[0], w, 1 / 60, ctx); frames++; }
  }
  ok(frames > 100, `only ${frames} frames were drawn`);
  ok(self.crack.count > 0, 'the crack was never drawn');
  ok(T.maxInstance >= 0, 'no instance was ever written');

  FX.dispose(self, ctx);
  eq(root.children.length, 0, 'dispose() left meshes in the scene');
  ok(T.disposed.geometry >= 5 && T.disposed.material >= 5,
     `dispose() leaked: ${T.disposed.geometry} geometries, ${T.disposed.material} materials`);
  return `${frames} frames, then nothing left behind`;
});

/* A stub of just enough three.js to run the fx module under node. Its only job
   is to be faithful in SHAPE -- and to shout if an instance is written past the
   capacity the mesh was built with, which is the one fx bug a screenshot would
   not show you. */
function stubThree() {
  const T = { disposed: { geometry: 0, material: 0 }, maxInstance: -1 };
  class V3 {
    constructor(x = 0, y = 0, z = 0) { this.x = x; this.y = y; this.z = z; }
    set(x, y, z) { this.x = x; this.y = y; this.z = z; return this; }
    copy(o) { return this.set(o.x, o.y, o.z); }
    normalize() {
      const l = Math.hypot(this.x, this.y, this.z) || 1;
      return this.set(this.x / l, this.y / l, this.z / l);
    }
  }
  class Quat {
    constructor(x = 0, y = 0, z = 0, w = 1) { this.x = x; this.y = y; this.z = z; this.w = w; }
    set(x, y, z, w) { this.x = x; this.y = y; this.z = z; this.w = w; return this; }
    copy(q) { return this.set(q.x, q.y, q.z, q.w); }
    setFromUnitVectors(a, b) {
      const d = a.x * b.x + a.y * b.y + a.z * b.z + 1;
      if (d < 1e-8) return this.set(-a.y, a.x, 0, 0);
      return this.set(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z,
                      a.x * b.y - a.y * b.x, d).nrm();
    }
    setFromAxisAngle(ax, r) {
      const h = r / 2, s = Math.sin(h);
      return this.set(ax.x * s, ax.y * s, ax.z * s, Math.cos(h));
    }
    multiply(q) {
      const { x: ax, y: ay, z: az, w: aw } = this;
      return this.set(
        ax * q.w + aw * q.x + ay * q.z - az * q.y,
        ay * q.w + aw * q.y + az * q.x - ax * q.z,
        az * q.w + aw * q.z + ax * q.y - ay * q.x,
        aw * q.w - ax * q.x - ay * q.y - az * q.z);
    }
    nrm() {
      const l = Math.hypot(this.x, this.y, this.z, this.w) || 1;
      return this.set(this.x / l, this.y / l, this.z / l, this.w / l);
    }
  }
  class M4 {
    constructor() { this.elements = new Float64Array(16); this.elements[15] = 1; }
    compose(p, q, s) {
      for (const n of [p.x, p.y, p.z, q.x, q.y, q.z, q.w, s.x, s.y, s.z]) {
        if (!Number.isFinite(n)) throw new Error('a matrix was composed from a NaN');
      }
      this.elements[12] = p.x; this.elements[13] = p.y; this.elements[14] = p.z;
      this.elements[0] = s.x; this.elements[5] = s.y; this.elements[10] = s.z;
      return this;
    }
  }
  class Obj3D {
    constructor() {
      this.position = new V3(); this.quaternion = new Quat(); this.scale = new V3(1, 1, 1);
      this.children = []; this.visible = true;
    }
    add(c) { this.children.push(c); return this; }
    remove(c) { const i = this.children.indexOf(c); if (i >= 0) this.children.splice(i, 1); return this; }
  }
  class Attr {
    constructor(array, itemSize) {
      this.array = array; this.itemSize = itemSize;
      this.count = array.length / itemSize; this.needsUpdate = false;
    }
  }
  class Geo {
    constructor() { this.attributes = {}; this.index = null; }
    setAttribute(n, a) { this.attributes[n] = a; return this; }
    setIndex(a) { this.index = a; return this; }
    dispose() { T.disposed.geometry++; }
  }
  class Mat {
    constructor(p = {}) { Object.assign(this, p); if (this.opacity == null) this.opacity = 1; }
    dispose() { T.disposed.material++; }
  }
  class Mesh extends Obj3D {
    constructor(g, m) { super(); this.geometry = g; this.material = m; }
  }
  class Inst extends Mesh {
    constructor(g, m, n) {
      super(g, m);
      this.capacity = n; this.count = 0;
      this.instanceMatrix = { needsUpdate: false, array: new Float32Array(n * 16) };
    }
    setMatrixAt(i, m) {
      if (i < 0 || i >= this.capacity) {
        throw new Error(`instance ${i} written to a mesh built for ${this.capacity}`);
      }
      T.maxInstance = Math.max(T.maxInstance, i);
      this.instanceMatrix.array.set(m.elements.subarray ? m.elements.subarray(0, 16)
        : Float32Array.from(m.elements), i * 16);
    }
  }
  Object.assign(T, {
    Vector3: V3, Quaternion: Quat, Matrix4: M4, Group: Obj3D,
    BufferGeometry: Geo, BufferAttribute: Attr,
    BoxGeometry: Geo, OctahedronGeometry: Geo, ConeGeometry: Geo,
    SphereGeometry: Geo, TorusGeometry: Geo,
    MeshBasicMaterial: Mat, MeshStandardMaterial: Mat,
    Mesh, InstancedMesh: Inst,
    AdditiveBlending: 2, DoubleSide: 2, BackSide: 1, FrontSide: 0,
  });
  return T;
}

/* ----------------------------------------------------------------- report --- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
