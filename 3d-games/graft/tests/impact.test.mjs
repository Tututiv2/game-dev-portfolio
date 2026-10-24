// node tests/impact.test.mjs   — THE IMPACT, on its own. No browser, no framework.
//
// The event is not wired in yet (the integrator does that), so this file does
// the wiring itself: it registers the definition, merges CATALOG_ADDITIONS the
// way catalog.js will, and supplies the four world hooks the engine's api calls.
// Everything below then runs the REAL engine over the REAL world.

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const imp = (p) => import(pathToFileURL(join(ROOT, p)));

const { C } = await imp('app/src/sim/const.js');
const P = await imp('app/src/sim/planet.js');
const CAT = await imp('app/src/sim/catalog.js');
const W = await imp('app/src/sim/world.js');
const E = await imp('app/src/sim/events/engine.js');
const REG = await imp('app/src/sim/events/registry.js');
const API = await imp('app/src/sim/events/api.js');
const M = await imp('app/src/sim/events/impact.js');

const EVENT = M.EVENT;
const IMPACT = M.IMPACT;

// what the integrator will do, done here so the checks below exercise the real
// thing: the catalog is one shared array, so pushing into it is the merge
const BEFORE_MERGE = new Set(CAT.CATALOG.map(c => c.id));
for (const e of M.CATALOG_ADDITIONS) if (!CAT.isCreatable(e.id)) CAT.CATALOG.push(e);
if (!REG.EVENTS.some(d => d.id === EVENT.id)) REG.EVENTS.push(EVENT);

let pass = 0, fail = 0;
const results = [];
async function check(name, fn) {
  try { const n = await fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} expected ${b}, got ${a}`); };
const ok = (c, m) => { if (!c) throw new Error(m || 'assertion failed'); };

/* ------------------------------------------------------------- plumbing --- */
// Stand-ins for the four world hooks makeApi() closes over. They do what
// world.js will do and nothing more: the EVENT is what is under test.
let fakeId = 900000;
function hooks(w) {
  return {
    w,
    near: (ww, dir, r) => W.near(ww, dir, r),
    destroyObject: (ww, o) => {
      const n = ww.objects.length;
      ww.objects = ww.objects.filter(q => q !== o);
      if (ww.objects.length === n) return false;
      ww.rev++;
      return true;
    },
    forcePlace: (ww, kind, dir, stage = 0) => {
      const s = CAT.spec(kind);
      if (!s) return null;
      const o = {
        id: fakeId++, kind, dir: P.norm(dir), stage,
        nextAt: stage + 1 < s.stages.length ? ww.t + s.stages[stage + 1].at : Infinity,
        bornAt: ww.t, lastMake: ww.t, spin: 0,
      };
      ww.objects.push(o);
      ww.rev++;
      return o;
    },
    grantRes: (ww, res) => { for (const k of Object.keys(res)) ww.res[k] = (ww.res[k] || 0) + res[k]; },
    announce: (ww, text) => { (ww.notices = ww.notices || []).push({ t: ww.t, text }); },
  };
}

/** A world with the event engine initialised, parked at time `t`. Nothing fires
    on its own: every check below starts the bombardment deliberately. */
function worldAt(t, seed = C.seed) {
  const w = W.createWorld(seed);
  E.initEvents(w);
  w.t = t;
  w.nextEventAt = Infinity;
  return w;
}

/** Advance the world AND its events together, in the sim's own fixed tick. */
function run(w, h, seconds) {
  let left = seconds;
  while (left > 1e-9) {
    const dt = Math.min(C.tick, left);
    W.advance(w, dt);
    E.tickEvents(w, dt, h);
    left -= dt;
  }
}

const TOTAL = IMPACT.dur.streaks + IMPACT.dur.first + IMPACT.dur.heavy + IMPACT.dur.passes;

/** Dry, gentle ground with things standing on it. */
function drySite(seedOffset) {
  return P.scatter(1200, seedOffset).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.heightAt(d) < C.planet.alpine && P.slopeAt(d) < 0.25);
}
const SITE = drySite(0.41);

/* --------------------------------------------------------------- checks --- */

await check('the definition is well-formed by the engine\'s own rules', () => {
  const problems = API.validate(EVENT);
  eq(problems.length, 0, problems.join(' | '));
  eq(EVENT.phases[0].at, 0);
  ok(EVENT.phases[EVENT.phases.length - 1].lasts > 0, 'the last phase never runs out');
  return EVENT.phases.map(p => p.id).join(' -> ');
});

await check('the trigger is a catalog entry, shaped like every other one', () => {
  for (const e of M.CATALOG_ADDITIONS) {
// Integration note: every event's trigger building is now registered into the
// catalog at boot by sim/boot.js, so "my entry is not in the catalog yet" is
// false by design once the game is wired together. What that check was really
// for is that the entry EXISTS and is not a duplicate of somebody else's.
    eq(CAT.CATALOG.filter(c => c.id === e.id).length, 1, `${e.id} is registered exactly once`);
    for (const k of ['id', 'name', 'verb', 'blurb', 'needs', 'stages', 'maxSlope', 'settles']) {
      ok(e[k] !== undefined, `${e.id} has no ${k}`);
    }
    ok(Array.isArray(e.stages) && e.stages.length >= 1, `${e.id} has no stages`);
    eq(e.stages[0].at, 0, `${e.id}: the first stage must be instant`);
    for (const st of e.stages) ok(st.h > 0, `${e.id}: a stage with no height`);
  }
  const lure = M.CATALOG_ADDITIONS.find(e => e.id === EVENT.trigger);
  ok(lure, `nothing in the catalog causes ${EVENT.id} deliberately`);
  ok(CAT.isCreatable(EVENT.trigger), 'the trigger did not survive the merge');
  return M.CATALOG_ADDITIONS.map(e => e.id).join(', ');
});

await check('a strike destroys what is standing near it', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  const ev = E.fire(w, EVENT.id, SITE, h);
  ok(ev, 'the event refused to start');
  const standing = (ww) => W.near(ww, SITE, IMPACT.spread)
    .filter(o => o.kind !== M.CRATER && o.kind !== CAT.KIND.CRYSTAL).length;
  const before = standing(w);
  ok(before > 0, 'nothing was standing there to destroy');
  run(w, h, TOTAL - 1);
  const after = standing(w);
  ok(ev.data.hit > 0, 'not one rock hit the ground in a whole bombardment');
  ok(after < before, `nothing was destroyed: ${before} standing before, ${after} after`);
  return `${ev.data.hit} strikes, ${before} -> ${after} standing`;
});

await check('a strike leaves a crater, and it is still there hours later', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  const ev = E.fire(w, EVENT.id, SITE, h);
  run(w, h, TOTAL + 5);
  eq(w.events.length, 0, 'the bombardment never ended');

  const craters = w.objects.filter(o => o.kind === M.CRATER);
  ok(craters.length > 0, 'a whole bombardment and not one crater');
  // a later strike can land in an earlier hole and take it with it
  ok(craters.length <= ev.data.hit, 'more craters than strikes');

  // the event is over and its fx is gone; the scar is an OBJECT, so it is not
  W.advance(w, 2 * 3600);
  const still = w.objects.filter(o => o.kind === M.CRATER);
  eq(still.length, craters.length, 'the craters healed themselves');
  const last = CAT.spec(M.CRATER).stages.length - 1;
  for (const c of still) eq(c.stage, last, 'a crater never finished cooling');
  return `${still.length} craters, still there two hours later`;
});

await check('a crater is worth having: it exposes something you can take', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  E.fire(w, EVENT.id, SITE, h);
  const seams0 = W.near(w, SITE, IMPACT.spread).filter(o => o.kind === CAT.KIND.CRYSTAL).length;
  run(w, h, TOTAL + 5);
  const seams1 = W.near(w, SITE, IMPACT.spread).filter(o => o.kind === CAT.KIND.CRYSTAL).length;
  ok(seams1 > seams0, 'the strikes turned up nothing');

  W.advance(w, 3600);                       // it has to cool before you can work it
  const crater = w.objects.find(o => o.kind === M.CRATER);
  const before = w.res.shard;
  const got = W.harvest(w, crater);
  ok(got, 'a cooled crater refused to be worked');
  ok(w.res.shard > before, 'working a crater paid nothing');
  ok(!w.objects.includes(crater), 'the crater refilled itself, which is a shard printer');
  return `${seams1 - seams0} seams exposed, and the crater pays ${JSON.stringify(got)}`;
});

await check('nothing lands without warning: it is in the sky first', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  const ev = E.fire(w, EVENT.id, SITE, h);
  let seen = 0, worst = Infinity, most = 0;
  const dt = C.tick;
  for (let i = 0; i * dt < TOTAL - 1; i++) {
    const known = new Set(ev.data.m.map(m => m.n));
    run(w, h, dt);
    most = Math.max(most, ev.data.m.length);
    for (const m of ev.data.m) {
      if (known.has(m.n)) continue;
      seen++;
      worst = Math.min(worst, m.tl - m.t0);
      ok(w.t < m.tl, 'a rock appeared in the sky already landed');
    }
  }
  ok(seen > 0, 'nothing ever appeared in the sky');
  ok(worst >= IMPACT.fall - 1e-9, `a rock gave only ${worst}s of warning`);
  ok(most <= IMPACT.maxLive, 'more rocks in the sky at once than the cap allows');
  return `${seen} rocks, ${worst}s of warning each, at most ${most} at once`;
});

await check('the streaks come before the strikes', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  const ev = E.fire(w, EVENT.id, SITE, h);
  run(w, h, IMPACT.dur.streaks - 1);
  eq(ev.def.phases[ev.phase].id, 'streaks');
  eq(ev.data.c.length, 0, 'something landed during the warning');
  ok(ev.data.m.length > 0, 'the warning phase showed nothing at all');
  const inSky = ev.data.m.length;
  run(w, h, IMPACT.dur.first + IMPACT.dur.heavy);
  ok(ev.data.c.length > 0, 'the fall never arrived');
  return `${inSky} crossing the sky before the first one lands`;
});

await check('same world, same clock, same bombardment', () => {
  const one = worldAt(IMPACT.minAge + 10), two = worldAt(IMPACT.minAge + 10);
  const h1 = hooks(one), h2 = hooks(two);
  const a = E.fire(one, EVENT.id, SITE, h1);
  const b = E.fire(two, EVENT.id, SITE, h2);
  run(one, h1, TOTAL - 1);
  run(two, h2, TOTAL - 1);
  eq(a.data.n, b.data.n, 'a different number of rocks fell');
  eq(JSON.stringify(a.data.c), JSON.stringify(b.data.c), 'the strikes landed somewhere else');
  eq(one.objects.length, two.objects.length, 'the two worlds ended up different');

  // and a bombardment that begins at another time is another bombardment
  const later = worldAt(IMPACT.minAge + 400);
  const h3 = hooks(later);
  const c = E.fire(later, EVENT.id, SITE, h3);
  run(later, h3, TOTAL - 1);
  ok(JSON.stringify(c.data.c) !== JSON.stringify(a.data.c), 'every bombardment falls identically');
  return `${a.data.c.length} strikes, twice, in the same places`;
});

await check('the swarm survives a save and goes on falling', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  const ev = E.fire(w, EVENT.id, SITE, h);
  // mid-fall: craters already on the ground AND rocks still in the sky
  run(w, h, IMPACT.dur.streaks + IMPACT.dur.first + 20);
  ok(ev.data.m.length > 0, 'nothing was in flight to save');
  ok(ev.data.c.length > 0, 'nothing had landed yet to save');

  // through JSON, exactly as the game stores it
  const rows = JSON.parse(JSON.stringify(E.saveEvents(w)));
  const back = W.load(W.save(w));
  ok(back, 'the world save was refused');
  E.initEvents(back);
  E.loadEvents(back, rows);
  back.nextEventAt = Infinity;

  eq(back.events.length, 1, 'the bombardment did not come back');
  const ev2 = back.events[0];
  eq(ev2.data.m.length, ev.data.m.length, 'the sky emptied over a save');
  eq(ev2.data.n, ev.data.n, 'it forgot how many had fallen');
  eq(JSON.stringify(ev2.data.c), JSON.stringify(ev.data.c), 'the strikes did not survive');
  eq(ev2.phase, ev.phase, 'it came back in a different phase');
  eq(back.objects.filter(o => o.kind === M.CRATER).length,
     w.objects.filter(o => o.kind === M.CRATER).length, 'the craters did not survive the world save');

  const strikes = ev2.data.c.length;
  run(back, hooks(back), 40);
  ok(back.events[0].data.c.length > strikes, 'the reloaded swarm stopped falling');
  return `${ev.data.m.length} rocks in the air and ${strikes} craters, through JSON`;
});

await check('it ends, and it ends by itself', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  E.fire(w, EVENT.id, SITE, h);
  eq(w.events.length, 1);
  run(w, h, TOTAL + 30);
  eq(w.events.length, 0, 'the bombardment never stopped');
  ok(w.eventLog.some(r => r.id === EVENT.id && r.k === 'ends'), 'it left without saying so');
  const said = (w.notices || []).map(n => n.text);
  ok(said.length >= EVENT.phases.length, 'the player was never told what was happening');

  // and nothing goes on falling after it has gone
  const craters = w.objects.filter(o => o.kind === M.CRATER).length;
  run(w, h, 600);
  eq(w.objects.filter(o => o.kind === M.CRATER).length, craters, 'it kept bombarding after it ended');
  return `${TOTAL}s, ${said.length} lines, then quiet`;
});

await check('the sea swallows what falls into it', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  const wet = P.scatter(1200, 0.13).find(d => P.heightAt(d) < C.planet.sea - 2);
  ok(wet, 'no open water anywhere');
  const ev = E.fire(w, EVENT.id, wet, h);
  run(w, h, TOTAL + 5);
  const drowned = ev.data.c.filter(c => c.sea).length;
  ok(drowned > 0, 'a swarm aimed at the ocean missed the ocean entirely');
  for (const o of w.objects) {
    if (P.heightAt(o.dir) >= C.planet.sea) continue;
    eq(!!CAT.spec(o.kind).underwater, true, `${o.kind} is standing on the sea bed`);
  }
  return `${drowned} of ${ev.data.c.length} went into the water and left nothing`;
});

await check('THE RULE: everything a strike leaves behind is something you can make', () => {
  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  E.fire(w, EVENT.id, SITE, h);
  run(w, h, TOTAL + 5);
  const kinds = new Set(w.objects.map(o => o.kind));
  for (const k of kinds) ok(CAT.isCreatable(k), `${k} appeared and is not makeable`);
  ok(kinds.has(M.CRATER), 'no crater in the world to check');
  return [...kinds].sort().join(', ');
});

/* ------------------------------------------------------------------ fx --- */
// The view is imported by no other check in this project, which is how two bad
// literals once reached render.js and sat there. three.js loads headless, so
// the fx module is driven here with real geometry and a real camera.
const THREE = await imp('app/vendor/three/three.module.js');
const FX = await imp('app/src/view/fx/impact.js');

function fxCtx() {
  const scene = new THREE.Scene();
  const root = new THREE.Group();
  const sky = new THREE.Group();
  scene.add(root); scene.add(sky);
  const camera = new THREE.PerspectiveCamera(62, 1, 0.15, 3000);
  return { THREE, scene, sky, root, P, C, camera };
}
const finite = (mat, what) => {
  for (const e of mat.elements) ok(Number.isFinite(e), `${what} has a non-finite matrix`);
};

await check('the fx builds, draws a real bombardment and tidies up after itself', () => {
  eq(FX.id, EVENT.id, 'the fx module answers to the wrong event');
  const ctx = fxCtx();
  const self = FX.build(ctx);
  eq(ctx.root.children.length, 1, 'the fx did not put exactly one group in the scene');

  const w = worldAt(IMPACT.minAge + 10);
  const h = hooks(w);
  const ev = E.fire(w, EVENT.id, SITE, h);
  run(w, h, IMPACT.dur.streaks + IMPACT.dur.first + 20);
  ok(ev.data.m.length > 0 && ev.data.c.length > 0, 'nothing to draw');

  FX.update(self, ev, w, 1 / 60, ctx);
  const kids = ctx.root.children[0].children.length;
  eq(self.head.count, ev.data.m.length, 'not every rock in the sky was drawn');
  ok(self.shock.count + self.scorch.count > 0, 'the strikes left nothing on the ground');

  const m = new THREE.Matrix4();
  for (let i = 0; i < self.head.count; i++) { self.head.getMatrixAt(i, m); finite(m, 'a rock'); }
  for (let i = 0; i < self.scorch.count; i++) { self.scorch.getMatrixAt(i, m); finite(m, 'a scorch'); }

  // a second frame must not grow the scene: nothing allocates per frame
  FX.update(self, ev, w, 1 / 60, ctx);
  eq(ctx.root.children[0].children.length, kids, 'the fx added meshes on the second frame');

  FX.dispose(self, ctx);
  eq(ctx.root.children.length, 0, 'the fx left itself in the scene');
  return `${self.head.count} rocks, ${self.scorch.count} scorches, ${kids} draws`;
});

await check('the fx puts a rock in the sky first and on the ground last', () => {
  const ctx = fxCtx();
  const self = FX.build(ctx);
  const w = worldAt(IMPACT.minAge + 10);
  const target = SITE;
  const b = P.basis(target);
  const entry = P.norm(P.add(P.mul(target, C.planet.radius), P.mul(b.east, 20)));
  const ev = {
    id: EVENT.id, def: EVENT, t0: w.t, phase: 1, phaseAt: w.t, dir: target,
    data: { n: 1, hit: 0, sa: 0, pa: 0, c: [], m: [{
      n: 0, s: 1,
      tx: target.x, ty: target.y, tz: target.z,
      ex: entry.x, ey: entry.y, ez: entry.z,
      t0: w.t, tl: w.t + IMPACT.fall,
    }] },
  };
  const m = new THREE.Matrix4(), pos = new THREE.Vector3();
  const radiusAt = (t) => {
    w.t = t;
    FX.update(self, ev, w, 1 / 60, ctx);
    self.head.getMatrixAt(0, m);
    return pos.setFromMatrixPosition(m).length();
  };
  const start = radiusAt(ev.data.m[0].t0);
  const mid = radiusAt(ev.data.m[0].t0 + IMPACT.fall * 0.5);
  const arrive = radiusAt(ev.data.m[0].tl);
  const ground = P.groundR(target);

  ok(start > ground + IMPACT.high * 0.5, `it entered at ${start.toFixed(0)}, barely above the ground`);
  ok(mid < start && mid > arrive, 'it did not come down in between');
  ok(Math.abs(arrive - ground) < 1.0, `it arrived at ${arrive.toFixed(1)}, not the ground at ${ground.toFixed(1)}`);

  // and the scorch is ON the ground, not hovering over it -- a centred mesh floats
  ev.data.c.push({ x: target.x, y: target.y, z: target.z, t: w.t, sea: 0 });
  FX.update(self, ev, w, 1 / 60, ctx);
  eq(self.scorch.count, 1, 'the crater was not drawn');
  self.scorch.getMatrixAt(0, m);
  const lift = pos.setFromMatrixPosition(m).length() - ground;
  ok(lift >= 0 && lift < 1.0, `the scar sits ${lift.toFixed(2)} off the ground`);
  FX.dispose(self, ctx);
  return `${start.toFixed(0)} -> ${ground.toFixed(0)} over ${IMPACT.fall}s, scar ${lift.toFixed(2)} above the dirt`;
});

await check('the sim half is pure, and the fx half never writes the world', () => {
  const banned = [/\bdocument\b/, /\bwindow\b/, /Math\.random/, /\bnew Date\b/, /Date\.now/,
                  /requestAnimationFrame/, /localStorage/, /from ['"].*three/];
  const strip = (t) => t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  const sim = strip(readFileSync(join(ROOT, 'app/src/sim/events/impact.js'), 'utf8'));
  for (const b of banned) ok(!b.test(sim), `sim/events/impact.js uses ${b}`);

  const fx = strip(readFileSync(join(ROOT, 'app/src/view/fx/impact.js'), 'utf8'));
  for (const b of [/api\.place/, /api\.destroy/, /api\.grant/, /w\.objects\s*=/, /ev\.data\s*[.[][^=]*=[^=]/])
    ok(!b.test(fx), `the fx module changes the world: ${b}`);
  for (const need of [/export const id/, /export function build/, /export function update/,
                      /export function dispose/])
    ok(need.test(fx), `the fx module does not implement ${need}`);
  return 'sim pure, fx read-only';
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
