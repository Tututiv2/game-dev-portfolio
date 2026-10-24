// node tests/blackhole.test.mjs   — the black hole, on its own. No browser.
//
// Self-contained on purpose: it registers the event with the real engine and
// drives the real clock, so what it asserts is what the game will do.

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const load = (p) => import(pathToFileURL(join(ROOT, p)));

const { C } = await load('app/src/sim/const.js');
const P = await load('app/src/sim/planet.js');
const W = await load('app/src/sim/world.js');
const API = await load('app/src/sim/events/api.js');
const REG = await load('app/src/sim/events/registry.js');
const ENG = await load('app/src/sim/events/engine.js');
const BHM = await load('app/src/sim/events/blackhole.js');
const FX = await load('app/src/view/fx/blackhole.js');

const SIM_FILE = join(ROOT, 'app/src/sim/events/blackhole.js');
const FX_FILE = join(ROOT, 'app/src/view/fx/blackhole.js');
const SELF_FILE = fileURLToPath(import.meta.url);

// The engine only knows what the registry knows, and driving the real engine is
// the only honest way to test it. Registering it here is temporary: once the
// integrator puts it in registry.js this becomes a no-op rather than a duplicate
// id, which auditEvents() would (rightly) fail on.
if (!REG.byId(BHM.DEF.id)) REG.EVENTS.push(BHM.DEF);

let pass = 0, fail = 0;
const results = [];
async function check(name, fn) {
  try { const n = await fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} expected ${b}, got ${a}`); };
const ok = (c, m) => { if (!c) throw new Error(m || 'assertion failed'); };

/* ------------------------------------------------------------- the rig --- */

/** Exactly the hooks makeApi() reaches through. world.js will grow these; the
    test implements them itself so it does not wait on another agent's file. */
function rig(w) {
  const killed = [];
  return {
    killed,
    hooks: {
      w,
      near: (ww, dir, r) => W.near(ww, dir, r),
      destroyObject: (ww, o) => {
        const before = ww.objects.length;
        ww.objects = ww.objects.filter(q => q.id !== o.id);
        if (ww.objects.length === before) return false;
        ww.rev++;
        killed.push(o);
        return true;
      },
      forcePlace: () => null,
      grantRes: (ww, res) => { for (const k of Object.keys(res)) ww.res[k] = (ww.res[k] || 0) + res[k]; },
      announce: (ww, text) => { (ww.notices = ww.notices || []).push({ t: ww.t, text }); },
    },
  };
}

/** The world clock, events only. Fixed ticks, never the caller's delta. */
function run(w, hooks, seconds, dt = C.tick) {
  let left = seconds;
  let guard = 0;
  while (left > 1e-9 && guard++ < 500000) {
    const step = Math.min(dt, left);
    w.t += step;
    ENG.tickEvents(w, step, hooks);
    left -= step;
  }
}

function fresh(t0 = 2000) {
  const w = W.createWorld();
  ENG.initEvents(w);
  w.t = t0;
  w.nextEventAt = Infinity;         // nothing else may gatecrash the test
  return w;
}

const sig = (o) => `${o.kind}@${o.dir.x.toFixed(4)},${o.dir.y.toFixed(4)},${o.dir.z.toFixed(4)}`;
const phaseOf = (ev) => ev.def.phases[ev.phase].id;
const TOTAL = BHM.BH.speck + BHM.BH.swell + BHM.BH.pull + BHM.BH.passing;

/* --------------------------------------------------------------- checks --- */

await check('the definition is well formed, and the engine accepts it', () => {
  const problems = API.validate(BHM.DEF);
  eq(problems.length, 0, problems.join(' | '));
  const audit = ENG.auditEvents().filter(p => p.includes('blackhole'));
  eq(audit.length, 0, audit.join(' | '));
  eq(BHM.DEF.trigger, BHM.LATTICE.id, 'nothing in the catalog can cause it');
  return BHM.DEF.phases.map(p => p.id).join(' -> ');
});

await check('sim/ stays pure: no DOM, no three, no Math.random, no Date', () => {
  const banned = [/\bdocument\b/, /\bwindow\b/, /Math\.random/, /\bnew Date\b/, /Date\.now/,
                  /requestAnimationFrame/, /localStorage/, /from ['"].*three/];
  const t = readFileSync(SIM_FILE, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  for (const b of banned) ok(!b.test(t), `the sim side uses ${b}`);
});

await check('no control characters, and both files parse', () => {
  for (const f of [SIM_FILE, FX_FILE, SELF_FILE]) {
    const t = readFileSync(f, 'utf8');
    for (let i = 0; i < t.length; i++) {
      const c = t.charCodeAt(i);
      ok(c >= 32 || c === 10 || c === 13 || c === 9, `control character in ${f} at ${i}`);
    }
    execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
  }
});

await check('it is SEEN long before it is felt', () => {
  const w = fresh();
  const r = rig(w);
  const n0 = w.objects.length;
  const ev = ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
  ok(ev, 'the black hole refused to begin');

  const dread = BHM.BH.speck + BHM.BH.swell;
  ok(dread >= 120, `only ${dread}s of dread is not minutes`);

  const first = BHM.strength(w, ev);
  run(w, r.hooks, dread - 1);
  eq(w.objects.length, n0, 'it took something before it ever reached the ground');
  eq(r.killed.length, 0, 'it destroyed something during the dread');
  ok(BHM.strength(w, ev) > first, 'it never grew while it hung there');
  ok(BHM.discRadius(w, ev) > 1, 'it is still a speck by the time it starts pulling');
  return `${dread}s of it just hanging there, growing`;
});

await check('it takes the world apart, and the objects are really gone', () => {
  const w = fresh();
  const r = rig(w);
  const ev = ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
  const n0 = w.objects.length;
  run(w, r.hooks, BHM.BH.speck + BHM.BH.swell + BHM.BH.pull);
  ok(r.killed.length > 0, 'the black hole passed over a full planet and took nothing');
  eq(w.objects.length, n0 - r.killed.length, 'the books do not balance');
  for (const o of r.killed) {
    ok(!w.objects.some(q => q.id === o.id), `${o.kind} was hidden, not destroyed`);
  }
  return `${r.killed.length} of ${n0} objects torn loose`;
});

await check('everything it took was under its ground track', () => {
  const w = fresh();
  const r = rig(w);
  const ev = ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
  run(w, r.hooks, BHM.BH.speck + BHM.BH.swell + BHM.BH.pull);
  ok(r.killed.length > 0);
  // sample the track over the whole event and ask how far each victim was from
  // the nearest point of it
  const times = [];
  for (let t = ev.t0; t <= w.t; t += 0.5) times.push(t);
  let worst = 0;
  for (const o of r.killed) {
    let best = Infinity;
    for (const t of times) {
      const d = P.surfDist(o.dir, BHM.trackDir({ t }, ev));
      if (d < best) best = d;
    }
    worst = Math.max(worst, best);
  }
  ok(worst <= BHM.BH.reach + 1e-6,
    `something ${worst.toFixed(1)} units off the track was taken, reach is ${BHM.BH.reach}`);
  return `worst victim was ${worst.toFixed(1)} units from the track`;
});

await check('the ground track actually moves, and stays on the sphere', () => {
  const w = fresh();
  const r = rig(w);
  const ev = ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
  const a = BHM.trackDir({ t: ev.t0 }, ev);
  const b = BHM.trackDir({ t: ev.t0 + TOTAL }, ev);
  ok(Math.abs(P.len(a) - 1) < 1e-9 && Math.abs(P.len(b) - 1) < 1e-9, 'the track left the sphere');
  const moved = P.surfDist(a, b);
  ok(moved > C.planet.radius * 0.2, `it only drifted ${moved.toFixed(0)} units; that is not a track`);
  return `${moved.toFixed(0)} units of ground crossed`;
});

await check('it leaves scars, and they are still there when it has gone', () => {
  const w = fresh();
  const r = rig(w);
  ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
  run(w, r.hooks, TOTAL + 30);
  eq(w.events.length, 0, 'it never left');
  ok((w.scars || []).length > 0, 'it tore the planet up and left no mark');
  ok(w.scars.length <= BHM.BH.scarCap, 'scars are unbounded');
  for (const s of w.scars) {
    eq(s.length, 4, 'a scar is [x, y, z, radius]');
    for (const n of s) ok(typeof n === 'number' && isFinite(n), 'a scar holds something that is not a number');
    ok(s[3] > 0 && s[3] <= BHM.BH.scarMaxR, 'a scar has an impossible radius');
  }
  const back = BHM.loadScars({}, JSON.parse(JSON.stringify(BHM.saveScars(w))));
  eq(JSON.stringify(back), JSON.stringify(w.scars), 'scars do not survive a save');
  return `${w.scars.length} scars left behind`;
});

await check('it ends on its own', () => {
  const w = fresh();
  const r = rig(w);
  ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
  run(w, r.hooks, TOTAL + 60);
  eq(w.events.length, 0, 'it is still hanging there');
  ok(w.eventLog.some(e => e.id === 'blackhole' && e.k === 'ends'), 'it never logged an ending');
  const n1 = w.objects.length;
  run(w, r.hooks, 300);
  eq(w.objects.length, n1, 'it kept taking things after it had gone');
  return `over in ${TOTAL}s`;
});

await check('determinism: the same world at the same clock loses the same things', () => {
  const one = [];
  for (let i = 0; i < 2; i++) {
    const w = fresh();
    const r = rig(w);
    ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
    run(w, r.hooks, TOTAL + 5);
    one.push({ killed: r.killed.map(sig).sort().join('|'), scars: JSON.stringify(w.scars) });
  }
  eq(one[0].killed, one[1].killed, 'two identical worlds tore apart differently');
  eq(one[0].scars, one[1].scars, 'the scars differed');
  ok(one[0].killed.length > 0);
});

await check('a save reloaded mid-event carries on doing exactly the same thing', () => {
  const a = fresh();
  const ra = rig(a);
  ENG.fire(a, 'blackhole', a.player.dir, ra.hooks);
  const upTo = BHM.BH.speck + BHM.BH.swell + BHM.BH.pull * 0.4;
  run(a, ra.hooks, upTo);
  ok(ra.killed.length > 0, 'nothing had happened yet, so the round trip proves nothing');

  // everything that crosses the save: the world, the events, the scars
  const wire = JSON.parse(JSON.stringify({
    world: W.save(a),
    events: ENG.saveEvents(a),
    scars: BHM.saveScars(a),
  }));
  const b = W.load(wire.world);
  ENG.initEvents(b);
  b.t = a.t;
  b.nextEventAt = Infinity;
  ENG.loadEvents(b, wire.events);
  BHM.loadScars(b, wire.scars);
  eq(b.events.length, 1, 'the event did not survive the save');
  eq(b.events[0].data.axis.join(','), a.events[0].data.axis.join(','), 'it forgot which way it was going');

  const rb = rig(b);
  rb.hooks.w = b;
  const rest = TOTAL - upTo + 5;
  run(a, ra.hooks, rest);
  run(b, rb.hooks, rest);

  const ka = ra.killed.slice(ra.killed.length - rb.killed.length).map(sig).sort().join('|');
  const kb = rb.killed.map(sig).sort().join('|');
  ok(rb.killed.length > 0, 'the reloaded event stopped doing anything');
  eq(kb, ka, 'the reloaded event took different things');

  // Scars are compared with a tolerance, not byte for byte, and that is not a
  // fudge: world.js's save rounds every direction to six places, so a scar torn
  // from a reloaded object is the same scar measured through a coarser ruler.
  // Demanding equality here would be asserting the save format's precision.
  eq(b.scars.length, a.scars.length, 'a different number of scars after a reload');
  for (let i = 0; i < a.scars.length; i++) {
    const x = a.scars[i], y = b.scars[i];
    eq(y[3], x[3], `scar ${i} came out a different size`);
    const off = P.surfDist(P.norm(P.v(x[0], x[1], x[2])), P.norm(P.v(y[0], y[1], y[2])));
    ok(off < 0.01, `scar ${i} moved ${off} units after a reload`);
  }
  return `${rb.killed.length} more taken, identically, after a round trip`;
});

await check('a finished lattice on the track shuts the hole early', () => {
  const build = (stage) => {
    const w = fresh();
    const r = rig(w);
    const ev = ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
    // stand it where the track will be when the pulling starts
    const when = { t: ev.t0 + BHM.BH.speck + BHM.BH.swell + BHM.BH.pull * 0.05 };
    const dir = BHM.trackDir(when, ev);
    w.objects.push({ id: 999001, kind: BHM.LATTICE.id, dir, stage, nextAt: Infinity,
                     bornAt: 0, lastMake: 0, spin: 0 });
    run(w, r.hooks, BHM.BH.speck + BHM.BH.swell + BHM.BH.pull * 0.5);
    return { w, r, ev };
  };

  const half = build(0);                                   // an unfinished lattice
  eq(phaseOf(half.ev), 'pull', 'an unfinished lattice stopped it');
  ok(half.r.killed.length > 0, 'nothing was taken, so there is nothing to stop');

  const done = build(BHM.LATTICE.stages.length - 1);        // a live one
  eq(phaseOf(done.ev), 'passing', 'a finished lattice did not close the hole');
  eq(done.ev.data.stopped, 1, 'it closed for some other reason');
  ok(done.r.killed.length < half.r.killed.length, 'closing it early cost it nothing');
  return `${half.r.killed.length} taken unchecked vs ${done.r.killed.length} with a lattice standing`;
});

const CAT = await load('app/src/sim/catalog.js');

await check('the trigger is a real catalog entry, and it is late', () => {
  eq(Array.isArray(BHM.CATALOG_ADDITIONS), true);
  eq(BHM.CATALOG_ADDITIONS.length, 1);
  const e = BHM.CATALOG_ADDITIONS[0];
  for (const k of ['id', 'name', 'verb', 'blurb', 'needs', 'stages', 'maxSlope']) {
    ok(e[k] != null, `the lattice has no ${k}`);
  }
// Integration note: every event's trigger building is now registered into the
// catalog at boot by sim/boot.js, so "my entry is not in the catalog yet" is
// false by design once the game is wired together. What that check was really
// for is that the entry EXISTS and is not a duplicate of somebody else's.
  eq(CAT.CATALOG.filter(c => c.id === e.id).length, 1, 'the lattice is registered exactly once');
  eq(e.stages[0].at, 0, 'the first stage must be instant, like every other entry');
  for (const st of e.stages) {
    ok(typeof st.at === 'number' && typeof st.h === 'number' && st.name, 'a malformed stage');
  }
  ok(['village', 'town', 'city'].includes(e.unlock), 'the lattice is not gated behind a settlement');
  for (const k of Object.keys(e.needs)) {
    ok(['biomass', 'shard', 'alloy'].includes(k), `it costs ${k}, which is not a resource`);
  }
  // late game: it must cost more than the most expensive thing already there
  // Once the game is wired together this entry IS in the catalog, so comparing
  // against the whole list compares it with itself and 900 > 900 is false.
  const worst = Math.max(...CAT.CATALOG.filter(c => c.id !== e.id).map(c =>
    Object.values(c.needs || {}).reduce((a, b) => a + b, 0)));
  const mine = Object.values(e.needs).reduce((a, b) => a + b, 0);
  ok(mine > worst, `the lattice costs ${mine}, less than the ${worst} a tower already costs`);
  return `${e.name}: ${mine} of everything, behind a ${e.unlock}`;
});

await check('the renderer reads the same track the destruction does', () => {
  eq(FX.id, BHM.DEF.id, 'the fx module is for a different event');
  for (const fn of ['build', 'update', 'dispose']) eq(typeof FX[fn], 'function', `fx has no ${fn}`);
  const src = readFileSync(FX_FILE, 'utf8');
  ok(/from '\.\.\/\.\.\/sim\/events\/blackhole\.js'/.test(src),
    'the visuals invent their own numbers instead of reading the sim');
  for (const name of ['trackDir', 'strength', 'discRadius']) {
    ok(src.includes(name), `the visuals ignore ${name}`);
  }
});

await check('the renderer changes nothing', () => {
  const src = readFileSync(FX_FILE, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  for (const b of [/api\./, /\bw\.objects\s*=/, /w\.objects\.(push|splice|pop|shift)/,
                   /\bw\.res\b/, /w\.scars\s*=/, /w\.scars\.(push|splice|shift)/]) {
    ok(!b.test(src), `the fx module writes to the world: ${b}`);
  }
});

await check('what you see is what is taking things', () => {
  // strength drives the reach the destruction uses AND the size the renderer
  // draws, so a bigger hole is always a hungrier one.
  const w = fresh();
  const r = rig(w);
  const ev = ENG.fire(w, 'blackhole', w.player.dir, r.hooks);
  const seen = [];
  for (let i = 0; i < 40; i++) {
    run(w, r.hooks, TOTAL / 40);
    if (w.events.length) seen.push([BHM.strength(w, ev), BHM.discRadius(w, ev)]);
  }
  ok(seen.length > 10);
  for (const [s, d] of seen) {
    ok(s >= 0 && s <= 1, `strength went to ${s}`);
    ok(d >= BHM.BH.discMin && d <= BHM.BH.discMax, `the disc was ${d} across`);
  }
  // bigger disc, bigger strength: one is a function of the other, never inverted
  const sorted = seen.slice().sort((a, b) => a[0] - b[0]);
  for (let i = 1; i < sorted.length; i++) ok(sorted[i][1] >= sorted[i - 1][1] - 1e-9,
    'a bigger black hole drew smaller');
  const peak = Math.max(...seen.map(x => x[0]));
  ok(peak > 0.9, `it never got past ${peak.toFixed(2)} of full strength`);
});

/* --------------------------------------------------------------- report --- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
