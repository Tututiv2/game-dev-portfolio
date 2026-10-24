// node tests/moon.test.mjs   — no browser, no DOM, no three.js.
//
// The moon is one number read three ways: a disc in the sky, a darkness on the
// ground, a sea that comes and goes. So these checks are about the SHAPE of
// that number -- that it moves both ways, that every darkening ends, that the
// same world time always gives the same sky -- and not about any figure that
// was tuned until it went green.

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
let pass = 0, fail = 0;
const results = [];

async function check(name, fn) {
  try { const n = await fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} expected ${b}, got ${a}`); };
const ok = (c, m) => { if (!c) throw new Error(m || 'assertion failed'); };
const near = (a, b, t, m) => { if (Math.abs(a - b) > t) throw new Error(`${m || ''} ${a} not within ${t} of ${b}`); };

const load = (p) => import(pathToFileURL(join(ROOT, p)));
const { C } = await load('app/src/sim/const.js');
const P = await load('app/src/sim/planet.js');
const CAT = await load('app/src/sim/catalog.js');
const W = await load('app/src/sim/world.js');
const E = await load('app/src/sim/events/engine.js');
const API = await load('app/src/sim/events/api.js');
const REG = await load('app/src/sim/events/registry.js');
const M = await load('app/src/sim/events/moon.js');

// The integrator's job is one import and one line. This is that line, done in
// the test so the module can be exercised through the real engine.
if (!REG.byId(M.ID)) REG.EVENTS.push(M.def);

/* ------------------------------------------------------------- scaffolding */

let fakeId = 100000;

/** The world-side hooks makeApi() expects. Kept here so this file does not
    depend on world.js having grown forcePlace/announce yet. */
function hooksFor(w) {
  const said = [];
  const placed = [];
  return {
    w, said, placed,
    near: (ww, dir, r) => ww.objects.filter(o => P.surfDist(o.dir, dir) <= r),
    destroyObject: (ww, o) => {
      const n = ww.objects.length;
      ww.objects = ww.objects.filter(q => q !== o);
      return ww.objects.length < n;
    },
    forcePlace: (ww, kind, dir, stage) => {
      const o = {
        id: ++fakeId, kind, dir: P.norm(dir), stage: stage | 0,
        nextAt: Infinity, bornAt: ww.t, lastMake: ww.t, spin: 0,
      };
      ww.objects.push(o); ww.rev++; placed.push(o);
      return o;
    },
    grantRes: (ww, res) => { for (const k of Object.keys(res)) ww.res[k] = (ww.res[k] || 0) + res[k]; },
    announce: (ww, text) => { said.push(text); },
  };
}

/** A world with the events engine started and nothing scheduled by chance. */
function freshWorld() {
  const w = W.createWorld();
  E.initEvents(w);
  w.nextEventAt = Infinity;          // these checks fire the moon themselves
  return w;
}

function run(w, hooks, seconds, dt = 0.5) {
  let left = seconds;
  let guard = 0;
  while (left > 1e-9 && guard++ < 2000000) {
    const s = Math.min(dt, left);
    w.t += s;
    E.tickEvents(w, s, hooks);
    left -= s;
  }
}

/* ------------------------------------------------------- the contract ---- */

await check('the definition is well-formed, and the last phase never runs out', () => {
  eq(API.validate(M.def).length, 0, API.validate(M.def).join(' | '));
  const last = M.def.phases[M.def.phases.length - 1];
  eq(last.lasts, undefined, 'the moon has an expiry date');
  eq(M.def.trigger, 'anchor');
  ok(M.CATALOG_ADDITIONS.some(c => c.id === M.def.trigger),
    'the event names a trigger it does not ship');
  return `${M.def.phases.length} phases, last one open-ended`;
});

await check('the Anchor is a catalog entry, and it is the dearest thing in the game', () => {
  const anchor = M.CATALOG_ADDITIONS.find(c => c.id === 'anchor');
  const tower = CAT.spec(CAT.KIND.TOWER);
  for (const k of ['id', 'name', 'verb', 'blurb', 'needs', 'stages', 'harvest', 'maxSlope', 'settles']) {
    ok(k in anchor, `the anchor has no ${k}, so it is not shaped like a catalog entry`);
  }
  anchor.stages.forEach((s, i) => {
    ok(s.name && typeof s.at === 'number' && typeof s.h === 'number', `stage ${i} is malformed`);
    if (i === 0) eq(s.at, 0, 'the first stage is not immediate');
  });
  for (const k of Object.keys(tower.needs)) {
    ok((anchor.needs[k] || 0) > tower.needs[k], `an anchor is cheaper than a tower in ${k}`);
  }
  ok(anchor.unlock, 'the anchor is available from the start');
// Integration note: every event's trigger building is now registered into the
// catalog at boot by sim/boot.js, so "my entry is not in the catalog yet" is
// false by design once the game is wired together. What that check was really
// for is that the entry EXISTS and is not a duplicate of somebody else's.
  eq(CAT.CATALOG.filter(c => c.id === 'anchor').length, 1, 'the anchor is registered exactly once');
  return `${Object.entries(anchor.needs).map(([k, v]) => v + ' ' + k).join(', ')}, needs a ${anchor.unlock}`;
});

/* ------------------------------------------------------------- it arrives - */

await check('the moon arrives, settles, and never leaves', () => {
  const w = freshWorld();
  const h = hooksFor(w);
  const ev = E.fire(w, M.ID, null, h);
  ok(ev, 'the moon would not start');
  run(w, h, 4 * 3600);
  ok(M.liveMoon(w), 'the moon went away again');
  eq(M.liveMoon(w).phase, M.def.phases.length - 1, 'it never reached its final phase');
  ok(!w.eventLog.some(l => l.id === M.ID && l.k === 'ends'), 'the engine ended it');
  ok(h.said.length >= 2, 'it arrived without saying anything');
  return `still up after 4 hours, ${w.eventLog.filter(l => l.id === M.ID).length} log entries`;
});

/* ------------------------------------------------------------- the tide --- */

await check('no moon, no tide', () => {
  const w = freshWorld();
  eq(M.tideOffset(w), 0);
  near(M.seaLevel(w), C.planet.sea, 1e-12);
  eq(M.eclipse(w), 0);
});

await check('the tide runs both ways and stays inside its own amplitude', () => {
  const w = freshWorld();
  const h = hooksFor(w);
  E.fire(w, M.ID, null, h);
  run(w, h, M.MOON.settleAt + 10);       // let the orbit bed in

  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < 2 * M.MOON.synodic; i++) {
    run(w, h, 1);
    const t = M.tideOffset(w);
    ok(Math.abs(t) <= M.MOON.tide + 1e-9, `the tide overshot its amplitude at ${t}`);
    lo = Math.min(lo, t); hi = Math.max(hi, t);
  }
  ok(lo < -0.5 * M.MOON.tide, `low tide only reached ${lo.toFixed(3)}`);
  ok(hi > 0.5 * M.MOON.tide, `high tide only reached ${hi.toFixed(3)}`);
  return `${lo.toFixed(2)} to +${hi.toFixed(2)} of sea level`;
});

await check('low tide uncovers seabed, and there is kelp standing on it', () => {
  const w = freshWorld();
  const h = hooksFor(w);
  E.fire(w, M.ID, null, h);
  run(w, h, M.MOON.settleAt + 10);

  // walk forward to the lowest water of the next cycle
  let best = Infinity, bestT = w.t;
  for (let i = 0; i < M.MOON.synodic; i++) {
    run(w, h, 1);
    const t = M.tideOffset(w);
    if (t < best) { best = t; bestT = w.t; }
  }
  ok(best < 0, 'the sea never went out at all');
  w.t = bestT;
  const low = M.seaLevel(w);
  ok(low < C.planet.sea, 'low tide is not lower than the sea');

  // ground that is under water at mean tide and dry at the bottom of the tide
  const band = P.scatter(4000, 0.3).filter(d => {
    const hh = P.heightAt(d);
    return hh < C.planet.sea && hh >= low;
  });
  ok(band.length > 0, 'the tide is too small to uncover anything');

  const stranded = w.objects.filter(o => {
    const hh = P.heightAt(o.dir);
    return hh < C.planet.sea && hh >= low;
  });
  ok(stranded.length > 0, 'nothing at all lives in the tidal band');
  for (const o of stranded) eq(o.kind, CAT.KIND.KELP, `a ${o.kind} was under the sea`);
  return `${stranded.length} kelp left standing in air at low water`;
});

/* ---------------------------------------------------------- the eclipse --- */

await check('eclipses come on a schedule, and every one of them ends', () => {
  const w = freshWorld();
  const h = hooksFor(w);
  E.fire(w, M.ID, null, h);
  run(w, h, M.MOON.settleAt + 10);

  const runsOf = [];
  let cur = null;
  const span = 5 * M.MOON.synodic;
  for (let i = 0; i < span; i++) {
    run(w, h, 1);
    const c = M.eclipse(w);
    ok(c >= 0 && c <= 1, `coverage left 0..1 at ${c}`);
    if (c > 0) {
      if (!cur) cur = { start: w.t, peak: 0 };
      cur.peak = Math.max(cur.peak, c);
    } else if (cur) {
      cur.len = w.t - cur.start; runsOf.push(cur); cur = null;
    }
  }
  ok(runsOf.length >= 4, `only ${runsOf.length} eclipses in five cycles`);
  for (const r of runsOf) {
    ok(r.len < M.MOON.synodic / 4, `an eclipse lasted ${r.len}s, which is not an event`);
    ok(r.peak > 0.2, `an eclipse only reached ${r.peak.toFixed(2)} coverage`);
  }
  // gaps are regular: the moon keeps a timetable
  for (let i = 1; i < runsOf.length; i++) {
    near(runsOf[i].start - runsOf[i - 1].start, M.MOON.synodic, 4, 'the schedule slipped');
  }
  const peaks = runsOf.map(r => r.peak);
  ok(Math.max(...peaks) - Math.min(...peaks) > 0.05, 'every eclipse is identical');
  return `${runsOf.length} eclipses, ${runsOf[0].len}s each, peaks ${Math.min(...peaks).toFixed(2)}-${Math.max(...peaks).toFixed(2)}`;
});

await check('an eclipse is the moon standing where the sun is', () => {
  // The whole design rests on this: the sim measures the moon as an angle FROM
  // the sun, so the view can place it against the real light and the two can
  // never disagree about when the sky goes dark.
  const sun = P.norm(P.v(0.3, 0.9, -0.2));
  const n = P.norm(P.cross(sun, P.v(0, 0, 1)));
  const at = M.moonUnit(sun, n, 0, 0);
  near(P.dot(at, sun), 1, 1e-9, 'a new moon is not in front of the sun');
  for (const [th, ti] of [[0.4, 0.05], [2.0, -0.1], [Math.PI, 0.09], [5.5, 0]]) {
    const d = M.moonUnit(sun, n, th, ti);
    near(P.len(d), 1, 1e-9, 'the moon left the unit sphere');
    near(P.arc(d, sun), Math.acos(Math.cos(th) * Math.cos(ti)), 1e-9, 'separation does not match');
  }

  const w = freshWorld();
  const h = hooksFor(w);
  const ev = E.fire(w, M.ID, null, h);
  run(w, h, M.MOON.settleAt + 10);
  let bestC = 0, bestT = w.t;
  for (let i = 0; i < M.MOON.synodic; i++) {
    run(w, h, 1);
    if (M.eclipse(w) > bestC) { bestC = M.eclipse(w); bestT = w.t; }
  }
  w.t = bestT;
  ok(M.separation(w, ev) < M.MOON.cover, 'the sky darkened with the moon nowhere near the sun');
});

/* ------------------------------------------------------- it does something */

await check('the tide brings kelp in, and it does not bring in the sea', () => {
  const w = freshWorld();
  const h = hooksFor(w);
  E.fire(w, M.ID, null, h);
  const before = w.objects.length;
  run(w, h, 6 * 3600);
  ok(w.objects.length > before, 'six hours of tides changed nothing on the ground');
  for (const o of h.placed) {
    eq(o.kind, CAT.KIND.KELP, 'the tide left something that is not kelp');
    ok(P.heightAt(o.dir) < C.planet.sea, 'the tide planted kelp on dry land');
    ok(CAT.isCreatable(o.kind), 'the tide made something the player cannot');
  }
  ok(h.placed.length <= M.MOON.sowCap, `the moon ran away with ${h.placed.length} kelp`);
  return `+${h.placed.length} kelp over six hours`;
});

/* ---------------------------------------------------------- determinism --- */

await check('the same world time always gives the same sky', () => {
  const a = freshWorld(), b = freshWorld();
  const ha = hooksFor(a), hb = hooksFor(b);
  E.fire(a, M.ID, null, ha);
  E.fire(b, M.ID, null, hb);
  run(a, ha, 1800, 1 / 30);
  run(b, hb, 1800, 0.5);
  near(a.t, b.t, 1e-6, 'the two runs did not reach the same time');
  near(M.tideOffset(a), M.tideOffset(b), 1e-6, 'the tide depends on the step size');
  near(M.eclipse(a), M.eclipse(b), 1e-6, 'the eclipse depends on the step size');
  // and the world change is stepsize-independent to within one tide crossing
  ok(Math.abs(ha.placed.length - hb.placed.length) <= 1,
    `${ha.placed.length} vs ${hb.placed.length} kelp from the same half hour`);

  // reading it twice at the same instant gives the same answer
  eq(M.tideOffset(a), M.tideOffset(a));
  return `tide ${M.tideOffset(a).toFixed(4)} either way`;
});

/* ------------------------------------------------------------ persistence - */

await check('the moon survives a save, and comes back to the same sky', () => {
  const w = freshWorld();
  const h = hooksFor(w);
  E.fire(w, M.ID, null, h);
  run(w, h, 2000);
  const tide = M.tideOffset(w), cov = M.eclipse(w);
  const sown = M.liveMoon(w).data.sown;

  const rows = JSON.parse(JSON.stringify(E.saveEvents(w)));
  const back = W.createWorld();
  E.initEvents(back);
  back.t = w.t;
  E.loadEvents(back, rows);

  const ev = M.liveMoon(back);
  ok(ev, 'the moon did not survive the save');
  eq(ev.phase, M.liveMoon(w).phase, 'it came back at the wrong phase');
  eq(ev.data.sown, sown, 'it forgot what it had sown');
  near(M.tideOffset(back), tide, 1e-9, 'the tide moved across a save');
  near(M.eclipse(back), cov, 1e-9, 'the eclipse moved across a save');

  // and it keeps running from there
  const h2 = hooksFor(back);
  run(back, h2, M.MOON.synodic);
  ok(M.liveMoon(back), 'it stopped after being loaded');
  return `tide ${tide.toFixed(3)} preserved exactly`;
});

/* --------------------------------------------------------------- hygiene -- */

const MINE = [
  'app/src/sim/events/moon.js',
  'app/src/view/fx/moon.js',
  'tests/moon.test.mjs',
];

await check('the sim half is pure: no DOM, no three, no Math.random, no clock', () => {
  const banned = [/\bdocument\b/, /\bwindow\b/, /Math\.random/, /\bnew Date\b/, /Date\.now/,
                  /requestAnimationFrame/, /localStorage/, /from ['"].*three/];
  const t = readFileSync(join(ROOT, 'app/src/sim/events/moon.js'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  const bad = banned.filter(b => b.test(t)).map(String);
  eq(bad.length, 0, bad.join(' | '));
});

await check('my files parse and carry no control characters', () => {
  for (const f of MINE) {
    const p = join(ROOT, f);
    execFileSync(process.execPath, ['--check', p], { stdio: 'pipe' });
    const t = readFileSync(p, 'utf8');
    for (let i = 0; i < t.length; i++) {
      const c = t.charCodeAt(i);
      if (c < 32 && c !== 10 && c !== 13 && c !== 9) throw new Error(`${f}@${i} is a control character`);
    }
  }
  return `${MINE.length} files clean`;
});

await check('the render half asks for nothing the contract does not give it', () => {
  const src = readFileSync(join(ROOT, 'app/src/view/fx/moon.js'), 'utf8');
  for (const fn of ['export function build', 'export function update', 'export function dispose']) {
    ok(src.includes(fn), `the fx module has no ${fn}`);
  }
  ok(/export const id = 'moon'/.test(src), 'the fx module does not declare its id');
  ok(!/^import \* as THREE/m.test(src), 'the fx module imports three itself instead of taking ctx.THREE');
  ok(src.includes('anchorParts'), 'the anchor has no draw entry for the integrator');
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
