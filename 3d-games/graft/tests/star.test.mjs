// node tests/star.test.mjs   — no browser, no DOM, no three.js, no framework.
//
// THE STAR: two cosmic events (a solar flare and a guttering sun) plus their
// render module. Everything here asserts BEHAVIOUR -- that the world is
// genuinely different afterwards, that the same world time gives the same
// answer twice, that the data survives being saved, and that both events stop.

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const imp = (p) => import(pathToFileURL(join(ROOT, p)));

const { C } = await imp('app/src/sim/const.js');
const P = await imp('app/src/sim/planet.js');
const CAT = await imp('app/src/sim/catalog.js');
const W = await imp('app/src/sim/world.js');
const API = await imp('app/src/sim/events/api.js');
const ENG = await imp('app/src/sim/events/engine.js');
const REG = await imp('app/src/sim/events/registry.js');
const STAR = await imp('app/src/sim/events/star.js');
const FX = await imp('app/src/view/fx/star.js');

// The integrator adds these to registry.js. Doing it here, in memory, means the
// tests drive the REAL engine rather than a re-implementation of it.
REG.EVENTS.push(...STAR.STAR_EVENTS);

let pass = 0, fail = 0;
const results = [];
async function check(name, fn) {
  try { const n = await fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
const ok = (c, m) => { if (!c) throw new Error(m || 'assertion failed'); };
const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} expected ${b}, got ${a}`); };
const near = (a, b, t, m) => { if (Math.abs(a - b) > t) throw new Error(`${m || ''} ${a} not within ${t} of ${b}`); };

/* ------------------------------------------------------------- the rig --- */

// world.js does not export the event hooks yet -- makeApi() names exactly what
// it needs, so this is that shape and nothing more.
function hooksFor(w) {
  return {
    w,
    near: (x, dir, r) => W.near(x, dir, r),
    destroyObject: (x, o) => {
      const n = x.objects.length;
      x.objects = x.objects.filter(q => q.id !== o.id);
      if (x.objects.length === n) return false;
      x.rev++;
      return true;
    },
    forcePlace: () => null,
    grantRes: (x, res) => { for (const k of Object.keys(res)) x.res[k] = (x.res[k] || 0) + res[k]; },
    announce: (x, text) => { (x.notices = x.notices || []).push({ t: x.t, text }); },
  };
}

function armed(seed = C.seed) {
  const w = W.createWorld(seed);
  ENG.initEvents(w);
  w.nextEventAt = Infinity;          // nothing fires except what a test fires
  return w;
}

/** Run the event clock. `live` also runs the world's own growth alongside. */
function drive(w, hooks, seconds, live = false) {
  let left = seconds;
  while (left > 1e-9) {
    const dt = Math.min(C.tick, left);
    if (live) W.advance(w, dt); else w.t += dt;
    ENG.tickEvents(w, dt, hooks);
    left -= dt;
  }
}

/** Some things part-grown, so there are growth clocks to speed up or stall. */
function sowClocks(w, n = 40) {
  let done = 0;
  for (const o of w.objects) {
    if (done >= n) break;
    if (o.kind !== CAT.KIND.TREE || !W.finalStage(o)) continue;
    W.harvest(w, o);
    done++;
  }
  return done;
}

const remainingOf = (w) => {
  const m = new Map();
  for (const o of w.objects) if (isFinite(o.nextAt)) m.set(o.id, o.nextAt - w.t);
  return m;
};

const FLARE_LEN = STAR.CONST.flare.swell + STAR.CONST.flare.wave + STAR.CONST.flare.afterglow;
const DIM_LEN = STAR.CONST.dim.falter + STAR.CONST.dim.cold + STAR.CONST.dim.rekindle;

/* ------------------------------------------------------------- contract --- */

await check('both star events are well-formed definitions', () => {
  const problems = [];
  for (const d of STAR.STAR_EVENTS) problems.push(...API.validate(d));
  eq(problems.length, 0, problems.join(' | '));
  eq(STAR.STAR_EVENTS.length, 2);
  return STAR.STAR_EVENTS.map(d => d.id).join(' + ');
});

await check('the trigger is a real catalog entry and both events name it', () => {
  const tap = STAR.CATALOG_ADDITIONS.find(c => c.id === STAR.TAP);
  ok(tap, 'no Stellar Tap in CATALOG_ADDITIONS');
  for (const k of ['id', 'name', 'verb', 'blurb', 'needs', 'stages', 'maxSlope', 'settles']) {
    ok(tap[k] != null, `the tap is missing ${k}`);
  }
  eq(tap.stages[0].at, 0, 'the first stage must be instant');
  ok(tap.stages.length >= 2, 'a thing you raise has to take time');
  for (const s of tap.stages) ok(s.h > 0, 'a stage with no height cannot be drawn');
  for (const d of STAR.STAR_EVENTS) eq(d.trigger, STAR.TAP, `${d.id} is triggered by nothing`);
  return `${tap.name}, ${tap.stages.length} stages`;
});

await check('nothing invented leaked into const.js or catalog.js', () => {
  const c = readFileSync(join(ROOT, 'app/src/sim/const.js'), 'utf8');
  const k = readFileSync(join(ROOT, 'app/src/sim/catalog.js'), 'utf8');
  ok(!/starflare|stardim|stellartap/.test(c + k), 'a star number was written into a shared file');
  ok(STAR.CONST.flare && STAR.CONST.dim, 'the numbers are not in this module');
});

await check('the star module is pure', () => {
  const t = readFileSync(join(ROOT, 'app/src/sim/events/star.js'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  for (const b of [/\bdocument\b/, /\bwindow\b/, /Math\.random/, /\bnew Date\b/, /Date\.now/,
                   /requestAnimationFrame/, /localStorage/, /from ['"].*three/]) {
    ok(!b.test(t), `sim/events/star.js uses ${b}`);
  }
});

/* ---------------------------------------------------------------- flare --- */

await check('THE FLARE changes the world: growth in the open is knocked back', () => {
  const w = armed();
  const h = hooksFor(w);
  const before = new Map(w.objects.map(o => [o.id, o.stage]));
  const n0 = w.objects.length;

  const ev = ENG.fire(w, 'starflare', null, h);
  ok(ev, 'the flare would not start');
  drive(w, h, STAR.CONST.flare.swell + STAR.CONST.flare.wave + 1);

  ok(ev.data.scorched > 0, 'the wave passed and burned nothing');
  let hurt = 0;
  for (const [id, st] of before) {
    const now = w.objects.find(o => o.id === id);
    if (!now || now.stage < st) hurt++;
  }
  ok(hurt > 0, 'nothing on the whole planet was any different afterwards');
  eq(hurt, ev.data.scorched, 'the event counted a different number than it did');
  ok(w.objects.length <= n0, 'the flare planted things');
  return `${ev.data.scorched} of ${n0} scorched, +${ev.data.charged} shard off the spires`;
});

await check('the sea and a roof are shelter; a spire is paid rather than burned', () => {
  const w = armed();
  const h = hooksFor(w);
  const kelp = w.objects.filter(o => o.kind === CAT.KIND.KELP).map(o => [o.id, o.stage]);
  ok(kelp.length > 0, 'no kelp to test with');
  const shard0 = w.res.shard;

  const ev = ENG.fire(w, 'starflare', null, h);
  drive(w, h, STAR.CONST.flare.swell + STAR.CONST.flare.wave + 1);

  for (const [id, st] of kelp) {
    const now = w.objects.find(o => o.id === id);
    ok(now && now.stage === st, 'something under the sea was scorched');
  }
  ok(w.res.shard > shard0, 'the spires were not charged by the wave');
  ok(ev.data.charged > 0);
  return `${kelp.length} kelp untouched, +${w.res.shard - shard0} shard`;
});

await check('what survives grows FASTER than the clock afterwards', () => {
  const w = armed();
  const h = hooksFor(w);
  sowClocks(w, 60);
  const ev = ENG.fire(w, 'starflare', null, h);
  drive(w, h, STAR.CONST.flare.swell + STAR.CONST.flare.wave + 0.5);
  eq(ev.def.phases[ev.phase].id, 'afterglow', 'never reached the afterglow');

  const span = 30;
  const a = remainingOf(w);
  drive(w, h, span);
  const b = remainingOf(w);

  let n = 0, worst = Infinity;
  for (const [id, r0] of a) {
    if (r0 < span * 6) continue;                 // one that cannot tick over mid-span
    const r1 = b.get(id);
    if (r1 == null) continue;
    n++;
    worst = Math.min(worst, r0 - r1);
  }
  ok(n > 0, 'nothing was still growing to measure');
  ok(worst > span, `growth kept ordinary time: ${worst.toFixed(1)}s of clock in ${span}s`);
  return `${n} clocks, slowest ran ${(worst / span).toFixed(2)}x real time`;
});

await check('the flare is deterministic given the same world time', () => {
  const run = () => {
    const w = armed();
    const h = hooksFor(w);
    ENG.fire(w, 'starflare', null, h);
    drive(w, h, STAR.CONST.flare.swell + STAR.CONST.flare.wave + 5);
    return {
      data: JSON.stringify(w.events[0].data),
      ids: w.objects.map(o => `${o.id}:${o.stage}`).join(','),
      res: JSON.stringify(w.res),
    };
  };
  const a = run(), b = run();
  eq(a.data, b.data, 'the event told two different stories');
  eq(a.ids, b.ids, 'two identical planets burned differently');
  eq(a.res, b.res);
  return JSON.parse(a.data).scorched + ' scorched, twice';
});

/* -------------------------------------------------------------- dimming --- */

await check('THE GUTTERING stalls growth and the world stays put', () => {
  const w = armed();
  const h = hooksFor(w);
  sowClocks(w, 60);
  const ev = ENG.fire(w, 'stardim', null, h);
  drive(w, h, STAR.CONST.dim.falter + 1);
  eq(ev.def.phases[ev.phase].id, 'cold');
  eq(ev.data.dark, 1, 'the sun is still up in the dark phase');

  const span = 40;
  const a = remainingOf(w);
  drive(w, h, span);
  const b = remainingOf(w);

  let n = 0, best = -Infinity;
  for (const [id, r0] of a) {
    const r1 = b.get(id);
    if (r1 == null) continue;
    n++;
    best = Math.max(best, r0 - r1);
  }
  ok(n > 0, 'nothing was growing to stall');
  ok(best < span, `growth carried on regardless: ${best.toFixed(1)}s of clock in ${span}s`);
  ok(best >= 0, 'growth went backwards');
  ok(ev.data.held > 0, 'the cold took nothing off anybody');
  return `${n} clocks, fastest ran ${(best / span).toFixed(2)}x real time`;
});

await check('the player can do something about it: a lit tap ends the dark early', () => {
  const measure = (withTap) => {
    const w = armed();
    const h = hooksFor(w);
    if (withTap) {
      // The integrator merges CATALOG_ADDITIONS, and only then can one be
      // planted. Standing one on the ground is enough to test the rule.
      w.objects.push({
        id: 999001, kind: STAR.TAP, dir: P.v(0, 1, 0),
        stage: STAR.CATALOG_ADDITIONS[0].stages.length - 1,
        nextAt: Infinity, bornAt: 0, lastMake: 0, spin: 0,
      });
    }
    const ev = ENG.fire(w, 'stardim', null, h);
    const t0 = w.t;
    let over = -1;
    for (let i = 0; i < Math.ceil(DIM_LEN * 1.4 / C.tick); i++) {
      w.t += C.tick;
      ENG.tickEvents(w, C.tick, h);
      if (!w.events.length) { over = w.t - t0; break; }
    }
    return { over, fed: ev.data.fed };
  };
  const alone = measure(false);
  const helped = measure(true);
  ok(alone.over > 0, 'the guttering never ended on its own');
  ok(helped.over > 0, 'the guttering never ended with a tap either');
  eq(alone.fed, 0, 'it claimed it was fed with no tap on the planet');
  eq(helped.fed, 1, 'the tap did nothing');
  ok(helped.over < alone.over, `the tap did not shorten it: ${helped.over} vs ${alone.over}`);
  return `alone ${alone.over.toFixed(0)}s, with a tap ${helped.over.toFixed(0)}s`;
});

await check('the guttering is deterministic given the same world time', () => {
  const run = () => {
    const w = armed();
    const h = hooksFor(w);
    sowClocks(w, 30);
    ENG.fire(w, 'stardim', null, h);
    drive(w, h, STAR.CONST.dim.falter + 60);
    return JSON.stringify([w.events[0].data, w.objects.map(o => +o.nextAt.toFixed(6))]);
  };
  eq(run(), run(), 'the dark fell differently on two identical planets');
});

/* ----------------------------------------------------------- ending, save - */

await check('both events END, and leave the world running', () => {
  for (const [id, len] of [['starflare', FLARE_LEN], ['stardim', DIM_LEN]]) {
    const w = armed();
    const h = hooksFor(w);
    ENG.fire(w, id, null, h);
    eq(w.events.length, 1);
    drive(w, h, len + 20);
    eq(w.events.length, 0, `${id} never ended`);
    ok(w.eventLog.some(e => e.id === id && e.k === 'ends'), `${id} did not log its ending`);
    ok(w.objects.length > 0, `${id} left an empty planet`);
    for (const o of w.objects) ok(isFinite(o.nextAt) || o.nextAt === Infinity, 'a growth clock went bad');
  }
});

await check('a flare runs alongside the real world clock without breaking it', () => {
  const w = armed();
  const h = hooksFor(w);
  const kinds0 = new Set(w.objects.map(o => o.kind));
  ENG.fire(w, 'starflare', null, h);
  drive(w, h, FLARE_LEN + 10, true);
  eq(w.events.length, 0);
  for (const o of w.objects) {
    ok(CAT.isCreatable(o.kind), `${o.kind} survived the flare and is not makeable`);
    ok(o.stage >= 0 && o.stage < CAT.spec(o.kind).stages.length, 'a stage went out of range');
  }
  ok(w.objects.length > 0);
  return `${kinds0.size} kinds in, ${new Set(w.objects.map(o => o.kind)).size} kinds out`;
});

await check('event data survives a JSON round trip and the event carries on', () => {
  const w = armed();
  const h = hooksFor(w);
  ENG.fire(w, 'stardim', null, h);
  drive(w, h, STAR.CONST.dim.falter + 30);

  const live = w.events[0];
  const copy = JSON.parse(JSON.stringify(live.data));
  eq(JSON.stringify(copy), JSON.stringify(live.data), 'ev.data is not JSON-safe');
  for (const k of Object.keys(copy)) ok(Number.isFinite(copy[k]), `${k} is not a plain number`);

  // and through the engine's own save, which is what actually gets written
  const rows = JSON.parse(JSON.stringify(ENG.saveEvents(w)));
  const w2 = armed();
  w2.t = w.t;
  w2.objects = w.objects;
  ENG.loadEvents(w2, rows);
  eq(w2.events.length, 1, 'the event did not come back');
  eq(JSON.stringify(w2.events[0].data), JSON.stringify(live.data));
  const h2 = hooksFor(w2);
  drive(w2, h2, DIM_LEN + 20);
  eq(w2.events.length, 0, 'a reloaded event never ends');
  return Object.keys(copy).join(', ');
});

await check('the sim publishes a look for every frame of both events', () => {
  // The renderer reads ev.data and nothing else. If any of these drift out of
  // 0..1 the wash quads clip and the sky goes flat.
  for (const [id, len, keys] of [
    ['starflare', FLARE_LEN, ['swell', 'front', 'glare']],
    ['stardim', DIM_LEN, ['dark', 'flash']],
  ]) {
    const w = armed();
    const h = hooksFor(w);
    const ev = ENG.fire(w, id, null, h);
    let peak = 0;
    for (let t = 0; t < len + 5; t += C.tick) {
      w.t += C.tick;
      ENG.tickEvents(w, C.tick, h);
      if (!w.events.length) break;
      for (const k of keys) {
        const v = ev.data[k];
        ok(Number.isFinite(v) && v >= 0 && v <= 1, `${id}.${k} left 0..1 at ${v}`);
      }
      peak = Math.max(peak, ev.data[keys[keys.length - 1]] || 0, ev.data[keys[0]] || 0);
    }
    ok(peak > 0.5, `${id} never actually looked like anything (peak ${peak.toFixed(2)})`);
  }
});

/* ------------------------------------------------------------------- fx --- */

// A three.js stand-in. Enough of the API for the module to build and update, so
// that "the effect is where the sun is" is a checked claim rather than a hope.
function fakeThree() {
  class Vec3 {
    constructor(x = 0, y = 0, z = 0) { this.x = x; this.y = y; this.z = z; }
    set(x, y, z) { this.x = x; this.y = y; this.z = z; return this; }
    setScalar(k) { return this.set(k, k, k); }
    copy(v) { return this.set(v.x, v.y, v.z); }
    multiplyScalar(k) { return this.set(this.x * k, this.y * k, this.z * k); }
    addScaledVector(v, k) { return this.set(this.x + v.x * k, this.y + v.y * k, this.z + v.z * k); }
    lengthSq() { return this.x * this.x + this.y * this.y + this.z * this.z; }
    length() { return Math.sqrt(this.lengthSq()); }
    normalize() { const l = this.length() || 1; return this.multiplyScalar(1 / l); }
    applyQuaternion(q) {
      const { x, y, z } = this;
      const ix = q.w * x + q.y * z - q.z * y, iy = q.w * y + q.z * x - q.x * z;
      const iz = q.w * z + q.x * y - q.y * x, iw = -q.x * x - q.y * y - q.z * z;
      return this.set(
        ix * q.w + iw * -q.x + iy * -q.z - iz * -q.y,
        iy * q.w + iw * -q.y + iz * -q.x - ix * -q.z,
        iz * q.w + iw * -q.z + ix * -q.y - iy * -q.x);
    }
  }
  class Quat { constructor() { this.x = 0; this.y = 0; this.z = 0; this.w = 1; }
    copy(q) { this.x = q.x; this.y = q.y; this.z = q.z; this.w = q.w; return this; } }
  class Col {
    constructor(hex = 0xffffff) { this.setHex(hex); }
    setHex(h) { this.r = ((h >> 16) & 255) / 255; this.g = ((h >> 8) & 255) / 255; this.b = (h & 255) / 255; return this; }
    setRGB(r, g, b) { this.r = r; this.g = g; this.b = b; return this; }
  }
  class Obj {
    constructor() {
      this.children = []; this.parent = null; this.visible = true;
      this.position = new Vec3(); this.scale = new Vec3(1, 1, 1);
      this.quaternion = new Quat(); this.rotation = { x: 0, y: 0, z: 0 };
    }
    add(o) { o.parent = this; this.children.push(o); return this; }
    remove(o) { this.children = this.children.filter(c => c !== o); o.parent = null; return this; }
  }
  // `new` is used on all of these, so none of them may be arrow functions
  function mat(o = {}) {
    return Object.assign({ dispose() { this.disposed = true; }, opacity: 1 }, o,
      { color: o.color instanceof Col ? o.color : new Col(o.color == null ? 0xffffff : o.color) });
  }
  function geo() {
    return { attrs: {}, setAttribute(k, v) { this.attrs[k] = v; }, dispose() { this.disposed = true; } };
  }
  class Mesh extends Obj { constructor(g, m) { super(); this.geometry = g; this.material = m; } }
  class Sprite extends Obj { constructor(m) { super(); this.material = m; this.isSprite = true; } }
  class Points extends Obj { constructor(g, m) { super(); this.geometry = g; this.material = m; } }
  class Light extends Obj {
    constructor(c, i) { super(); this.color = new Col(c); this.intensity = i; this.isDirectionalLight = true; }
  }
  return {
    Vector3: Vec3, Quaternion: Quat, Color: Col, Group: Obj, Object3D: Obj,
    Mesh, Sprite, Points, DirectionalLight: Light,
    MeshBasicMaterial: mat, SpriteMaterial: mat, PointsMaterial: mat,
    SphereGeometry: geo, PlaneGeometry: geo,
    BufferGeometry: geo, BufferAttribute: function (a, n) { return { array: a, itemSize: n }; },
    AdditiveBlending: 2, MultiplyBlending: 4, NormalBlending: 1, DoubleSide: 2,
    CanvasTexture: function () { return { dispose() {} }; },
  };
}

function fxRig(sunAt) {
  const T = fakeThree();
  const scene = new T.Group();
  const light = new T.DirectionalLight(0xffffff, 1);
  light.position.set(sunAt[0], sunAt[1], sunAt[2]);
  scene.add(light);
  const root = new T.Group(); scene.add(root);
  const sky = new T.Group(); scene.add(sky);
  const camera = new T.Object3D();
  camera.fov = 62; camera.aspect = 1.6;
  camera.position.set(0, C.planet.radius + 5, 0);
  return {
    THREE: T, scene, root, sky, camera, P, C,
    glowTexture: () => ({ dispose() {} }),
    light,
  };
}

await check('the FX modules match the events they draw', () => {
  eq(FX.FX.length, 2);
  const ids = FX.FX.map(m => m.id).sort();
  eq(ids.join(','), STAR.STAR_EVENTS.map(d => d.id).sort().join(','), 'fx and sim name different events');
  for (const m of FX.FX) {
    for (const k of ['build', 'update', 'dispose']) eq(typeof m[k], 'function', `${m.id} has no ${k}`);
  }
  return ids.join(' + ');
});

await check('the flare happens where the sun ACTUALLY is, not where it is guessed', () => {
  // render.js parks a DirectionalLight at sunDir * 900 and builds the day in
  // the plane of where you landed. Reading that light is the only way to be
  // sure the wave comes out of the thing casting the shadows.
  const ctx = fxRig([0, 900, 0]);
  const w = armed();
  const self = FX.flareFx.build(ctx);
  const ev = { id: 'starflare', data: { swell: 1, front: 0.5, glare: 1 } };
  FX.flareFx.update(self, ev, w, 0.016, ctx);
  near(self.corona.position.y, 900, 1e-6, 'the star is not overhead');
  near(self.corona.position.x, 0, 1e-6);
  near(self.shell.position.y, 900, 1e-6, 'the wave does not come from the star');
  near(self.light.position.y, 900, 1e-6, 'the extra light is not at the sun');

  // move the sun a quarter turn, as the day does, and everything follows
  ctx.light.position.set(900, 0, 0);
  FX.flareFx.update(self, ev, w, 0.016, ctx);
  near(self.corona.position.x, 900, 1e-6, 'the star did not follow the day');
  near(self.corona.position.y, 0, 1e-6);
  near(self.shell.position.x, 900, 1e-6);
  FX.flareFx.dispose(self, ctx);
  return 'corona, wave front and flare light all track the real light';
});

await check('the flare washes the WHOLE frame, and stops when the event does', () => {
  const ctx = fxRig([0, 900, 0]);
  const w = armed();
  const self = FX.flareFx.build(ctx);
  const quiet = { id: 'starflare', data: { swell: 0, front: 0, glare: 0 } };
  FX.flareFx.update(self, quiet, w, 0.016, ctx);
  eq(self.wash.burn.visible, false, 'the sky is burning with nothing happening');
  eq(self.light.intensity, 0);

  const peak = { id: 'starflare', data: { swell: 1, front: 0.5, glare: 1 } };
  FX.flareFx.update(self, peak, w, 0.016, ctx);
  eq(self.wash.burn.visible, true, 'the peak of a solar flare did not change the frame');
  ok(self.wash.burn.material.opacity > 0, 'the wash is invisible');
  ok(self.light.intensity > 0, 'the ground is not lit any brighter');
  ok(self.cinders.visible, 'no ash in the air');
  // the quad is in front of the camera, facing it, and big enough to cover it
  const d = Math.hypot(
    self.wash.burn.position.x - ctx.camera.position.x,
    self.wash.burn.position.y - ctx.camera.position.y,
    self.wash.burn.position.z - ctx.camera.position.z);
  ok(d > 0 && d < 2, `the wash is ${d.toFixed(2)} from the eye`);
  ok(self.wash.burn.scale.x > 1 && self.wash.burn.scale.y > 1, 'the wash is smaller than the screen');

  FX.flareFx.update(self, quiet, w, 0.016, ctx);
  eq(self.wash.burn.visible, false, 'the sky stayed on fire after the flare ended');
  FX.flareFx.dispose(self, ctx);
  return `wash ${self.wash.burn.scale.x.toFixed(1)} x ${self.wash.burn.scale.y.toFixed(1)} at ${d.toFixed(2)}m`;
});

await check('the guttering darkens the whole planet, and gives it back', () => {
  const ctx = fxRig([0, 900, 0]);
  const w = armed();
  const self = FX.dimFx.build(ctx);

  FX.dimFx.update(self, { id: 'stardim', data: { dark: 0, flash: 0 } }, w, 0.016, ctx);
  eq(self.wash.dark.visible, false, 'the world is dim with nothing happening');
  eq(self.shroud.visible, false);

  FX.dimFx.update(self, { id: 'stardim', data: { dark: 1, flash: 0 } }, w, 0.016, ctx);
  const c1 = self.wash.dark.material.color;
  const cold = [c1.r, c1.g, c1.b];
  eq(self.wash.dark.visible, true, 'the sun went out and the frame did not change');
  ok(c1.r < 1 && c1.g < 1 && c1.b < 1, 'the multiply wash does not darken anything');
  ok(c1.b > c1.r, 'the dark is not cold');
  ok(self.shroud.visible, 'nothing is putting the sun out');
  near(self.shroud.position.y, 900 - 22, 1e-6, 'the shroud is not on the star');
  ok(self.frost.visible, 'no frost in the air');

  FX.dimFx.update(self, { id: 'stardim', data: { dark: 0, flash: 1 } }, w, 0.016, ctx);
  eq(self.wash.dark.visible, false, 'the dark did not lift');
  eq(self.wash.burn.visible, true, 'the sun came back without a flash');
  FX.dimFx.dispose(self, ctx);
  return `multiply wash white -> ${cold.map(x => x.toFixed(2)).join(',')}`;
});

await check('the FX tidies up after itself', () => {
  const ctx = fxRig([0, 900, 0]);
  const w = armed();
  for (const m of FX.FX) {
    const self = m.build(ctx);
    m.update(self, { id: m.id, data: { swell: 1, front: 0.4, glare: 1, dark: 1, flash: 0.5 } }, w, 0.016, ctx);
    m.dispose(self, ctx);
  }
  const count = (o) => 1 + o.children.reduce((a, c) => a + count(c), 0);
  eq(count(ctx.root), 1, 'the fx left meshes in the world');
  eq(count(ctx.sky), 1, 'the fx left things in the sky');
  eq(ctx.scene.children.filter(c => c.isDirectionalLight).length, 1, 'the fx left a light behind');
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
