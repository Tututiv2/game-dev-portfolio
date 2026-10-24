// node tests/scale.test.mjs   — the scale ladder. No browser, no three.js.
//
// The two claims this file exists to defend:
//   1. THE PACING. A tier that is a dead wait is a tier the player quits in,
//      so a whole playthrough is simulated with the game's OWN hint as the
//      policy, and the length of every rung and every gap is measured.
//   2. THE NUMBERS SURVIVE. The ladder ends past 1e308, where a float is
//      silently Infinity -- which on screen reads as a finished game.

import { readFileSync } from 'node:fs';
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

const B = await import(pathToFileURL(join(ROOT, 'app/src/sim/bignum.js')));
const S = await import(pathToFileURL(join(ROOT, 'app/src/sim/scale.js')));
const { D } = B;

/* ============================================================== bignum === */

await check('bignum: the arithmetic agrees with a float where a float still works', () => {
  const cases = [[3, 7], [1.5, 0.25], [1e12, 3e7], [9.9e30, 1.1e12], [5, 5]];
  for (const [x, y] of cases) {
    const a = D.of(x), b = D.of(y);
    near(a.add(b).toNumber() / (x + y), 1, 1e-12, 'add');
    near(a.sub(b).toNumber() / (x - y || 1), (x - y) ? 1 : 0, 1e-12, 'sub');
    near(a.mul(b).toNumber() / (x * y), 1, 1e-12, 'mul');
    near(a.div(b).toNumber() / (x / y), 1, 1e-12, 'div');
  }
  near(D.of(2).pow(10).toNumber(), 1024, 1e-6, 'pow');
  near(D.of(1e42).log10(), 42, 1e-9, 'log10');
  eq(D.of(5).cmp(D.of(9)), -1);
  eq(D.of(1e50).cmp(D.of(1e49)), 1);
  eq(D.of(7).cmp(D.of(7)), 0);
  eq(D.of(3).max(D.of(11)).toNumber(), 11);
  eq(D.of(3).min(D.of(11)).toNumber(), 3);
  eq(D.of(0).div(D.of(0)).toNumber(), 0, 'dividing by nothing must not be NaN');
  return '5 pairs, add/sub/mul/div/pow/log10/cmp/max';
});

await check('bignum: it goes where a float cannot — past 1e308', () => {
  // This is the whole reason the file exists. The top of the ladder is here.
  const big = D.fromLog10(320);
  eq(big.toNumber(), Infinity, 'a float would hold this, so the test proves nothing');
  ok(isFinite(big.log10()), 'the D lost it too');
  near(big.log10(), 320, 1e-9);
  const bigger = big.mul(big);                       // 1e640
  near(bigger.log10(), 640, 1e-9);
  near(bigger.div(big).log10(), 320, 1e-9);
  near(big.pow(3).log10(), 960, 1e-9);
  ok(bigger.gt(big) && big.lt(bigger), 'comparison broke past the float wall');
  ok(bigger.add(big).log10() > 639, 'add lost the larger term');
  ok(typeof bigger.toString() === 'string' && bigger.toString().includes('e640'));
  return '1e320, 1e640 and 1e960 all survive; a float is Infinity at all three';
});

await check('bignum: it reads plain to a million and scientific after', () => {
  eq(D.of(0).toString(), '0');
  eq(D.of(42).toString(), '42');
  eq(D.of(4200).toString(), '4,200');
  eq(D.of(999999).toString(), '999,999');
  ok(/e/.test(D.of(1.2e9).toString()), 'a billion should be scientific');
  eq(D.of(5.97e24).toString(), '5.97e24');
  return "0 | 42 | 4,200 | 999,999 | 5.97e24";
});

await check('bignum: a huge number is measured against something real', () => {
  eq(B.compare(5.97e24), 'about one Earth');
  eq(B.compare(1.5e42), 'about a galaxy');
  ok(B.compare(1.2e25).indexOf('Earths') > 0, `got "${B.compare(1.2e25)}"`);
  ok(B.compare(70).indexOf('person') > 0);
  ok(B.compare(0) === 'nothing at all');
  ok(B.readout(5.97e24).startsWith('5.97e24 kg'), B.readout(5.97e24));
  // and it still says something sane far past a float
  ok(B.compare(D.fromLog10(320)).length > 0);
  return B.readout(5.97e24) + ' | ' + B.readout(1.5e42);
});

await check('bignum: a number survives a save', () => {
  const v = D.fromLog10(311.5);
  const back = D.of(JSON.parse(JSON.stringify(v)));
  near(back.log10(), 311.5, 1e-9);
});

/* =============================================================== catalog = */

await check('THE LADDER: every tier above the ground is entered by BUILDING something', () => {
  for (const t of S.TIERS) {
    if (t.id === 'surface') { eq(t.gate, null, 'you do not build your way onto the ground'); continue; }
    const gate = S.megaSpec(t.gate);
    ok(gate, `${t.id} has no gate`);
    eq(gate.opens, t.id, `${gate.id} does not open ${t.id}`);
    ok(gate.stages && gate.stages.length >= 2, `${gate.id} is not something you build`);
  }
  // exactly one gate per tier: two doors is a menu wearing a costume
  const opens = S.MEGA.filter(m => m.opens).map(m => m.opens);
  eq(new Set(opens).size, opens.length, 'a tier has two gates');
  eq(opens.length, S.TIERS.length - 1);
  return S.TIERS.map(t => t.gate || '(start)').join(' -> ');
});

await check('the catalog is well formed', () => {
  const ids = new Set();
  for (const m of S.MEGA) {
    ok(m.id && !ids.has(m.id), `duplicate id ${m.id}`);
    ids.add(m.id);
    ok(m.name && m.verb && m.blurb, `${m.id} is missing its words`);
    ok(m.blurb.length > 30, `${m.id}: a blurb is a sentence, not a label`);
    ok(S.tierIndex(m.tier) >= 0, `${m.id} is in no tier`);
    ok(Array.isArray(m.stages) && m.stages.length >= 2, `${m.id} has no build`);
    eq(m.stages[0].at, 0, `${m.id}: the first stage must be at 0`);
    ok(S.buildTime(m.id) > 0, `${m.id} builds instantly`);
    ok(m.needs && Object.keys(m.needs).length, `${m.id} is free`);
    if (m.eats) {
      ok(['world', 'star', 'galaxy'].includes(m.eats.what), `${m.id} eats ${m.eats.what}`);
      ok(m.eats.every > 0 && m.eats.mass > 0, `${m.id} eats nothing`);
    }
    if (m.opens) ok(S.tierIndex(m.opens) === S.tierIndex(m.tier) + 1, `${m.id} skips a tier`);
  }
  return `${S.MEGA.length} megastructures`;
});

await check('every tier above the ground has enough in it to be a tier', () => {
  const counts = {};
  for (const t of S.TIERS) {
    const n = S.megaOfTier(t.id).length;
    counts[t.id] = n;
    if (t.id === 'surface') { ok(n >= 2, 'the ground has nothing on the ladder'); continue; }
    ok(n >= 4, `${t.id} has only ${n} things in it, which is a corridor`);
  }
  return Object.entries(counts).map(([k, v]) => `${k} ${v}`).join(', ');
});

await check('the things the owner asked for are actually in there', () => {
  // "eat a planet or destroy a planet, bigger ships solar system scale, then
  //  maybe some galactic scale, you can harvest/or destroy a galaxy"
  const eater = S.megaSpec('maw');
  ok(eater.eats && eater.eats.what === 'world', 'nothing eats a planet');
  ok(eater.eats.mass >= 5e24, 'the planet-eater does not eat a whole planet');
  const cracker = S.megaSpec('cracker');
  ok(cracker.eats && cracker.wasteful, 'nothing DESTROYS a planet as a choice');
  ok(cracker.eats.mass < eater.eats.mass * 0.2, 'destroying should keep far less than eating');
  const lifter = S.megaSpec('lifter');
  ok(lifter.makes && lifter.makes.mass > 0, 'no star lifter');
  const dyson = S.megaSpec('dyson');
  ok(dyson.makes && dyson.makes.energy >= 1e17, 'the dyson swarm does not take a star');
  const nova = S.megaSpec('novalance');
  ok(nova.eats && nova.eats.what === 'star', 'nothing destroys a star');
  const harvester = S.megaSpec('spindle');
  ok(harvester.eats && harvester.eats.what === 'star' && harvester.eats.count > 1e6,
    'the galaxy harvester does not harvest at galaxy scale');
  const weave = S.megaSpec('weave');
  ok(weave.eats && weave.eats.what === 'galaxy', 'nothing eats a galaxy');
  ok(S.megaSpec('unmaking').eats.what === 'galaxy', 'nothing eats galaxies plural');
  return 'planet-eater, world-cracker, star-lifter, dyson swarm, nova lance, galaxy harvester, the Weave';
});

/* ============================================================ both ways == */

await check('RESOURCES FLOW UP: nothing above the ground is free of the ground', () => {
  const ground = ['alloy', 'shard', 'biomass'];
  for (const m of S.MEGA) {
    const paid = ground.filter(k => (m.needs || {})[k] > 0);
    ok(paid.length > 0, `${m.id} can be built without the walking game`);
  }
  // and the ground price keeps climbing, so the surface never becomes a memory
  const top = S.megaSpec('unmaking').needs.alloy;
  const bottom = S.megaSpec('foundry').needs.alloy;
  ok(top > bottom * 50, `the ground bill barely moves: ${bottom} -> ${top}`);
  return `alloy from ${bottom} at the Deep Foundry to ${top} at the Long Unmaking`;
});

await check('RESOURCES FLOW DOWN: the sky pays the ground back', () => {
  const boons = S.MEGA.filter(m => m.boon);
  ok(boons.length >= 10, `only ${boons.length} things up there do anything down here`);
  ok(boons.some(m => m.boon.growth), 'nothing above the ground makes the ground grow faster');
  // build the lot and measure what the surface economy becomes
  const sc = S.createScale();
  const before = S.modelGroundRate(sc);
  for (const m of S.MEGA) sc.built[m.id] = 1;
  const after = S.modelGroundRate(sc);
  const b = S.surfaceBoon(sc);
  ok(b.growth > 100, `growth only reaches x${b.growth.toFixed(1)}`);
  for (const k of Object.keys(before)) {
    ok(after[k] > before[k] * 1000, `${k} only went ${before[k]} -> ${after[k]}`);
  }
  return `growth x${Math.round(b.growth)}, alloy ${before.alloy}/s -> ${Math.round(after.alloy)}/s`;
});

await check('and the flow down is real income, not a label on a menu', () => {
  const sc = S.createScale();
  const ground = { alloy: 0, shard: 0, biomass: 0 };
  S.advanceScale(sc, ground, 100);
  eq(ground.alloy, 0, 'an empty ladder paid the ground something');
  sc.built.ring = 1;                                 // boon: alloy 0.6/s
  S.advanceScale(sc, ground, 100);
  near(ground.alloy, 60, 1e-6, 'the Orbital Ring did not deliver');
});

/* ============================================================== a run === */

// The reference playthrough. The policy is the game's OWN hint -- nextGoal --
// so what is measured here is the run a player following the game's advice
// gets, not a run tuned by hand until the numbers looked good.
function play(opts = {}) {
  const limit = opts.limit || 40000;
  const dt = opts.dt || 1;
  const base = opts.base || S.GROUND_BASE;
  const sc = S.createScale();
  const ground = { alloy: 0, shard: 0, biomass: 0 };
  const seen = { village: true, town: true, city: true };
  const tierAt = { surface: 0 };
  const waits = [];
  let gap = 0, worst = 0, idle = 0, done = 0;
  for (let i = 0; i * dt < limit; i++) {
    const was = sc.tier;
    const b = S.surfaceBoon(sc);
    // the walking game, modelled: everything grows faster, so it is harvested
    // more often. advanceScale adds the delivered flows itself.
    for (const k of Object.keys(base)) ground[k] += base[k] * b.growth * dt;
    S.advanceScale(sc, ground, dt);
    if (sc.tier !== was) tierAt[sc.tier] = sc.t;
    if (!sc.building) {
      const g = S.nextGoal(sc, ground, seen);
      if (!g) { done = sc.t; break; }
      if (!S.blockedReason(sc, ground, g.id, seen)) {
        S.begin(sc, ground, g.id, seen);
        waits.push([g.id, gap]);
        gap = 0;
      } else { gap += dt; idle += dt; worst = Math.max(worst, gap); }
    } else { gap = 0; done = sc.t; }
  }
  return { sc, ground, tierAt, waits, worst, idle, done };
}

await check('PACING: a whole playthrough, tier by tier, against the design', () => {
  const r = play();
  const line = [];
  for (let i = 0; i < S.TIER_IDS.length; i++) {
    const id = S.TIER_IDS[i];
    const a = r.tierAt[id];
    ok(a != null, `never reached ${id} — the ladder is broken above ${S.TIER_IDS[i - 1]}`);
    const next = S.TIER_IDS[i + 1];
    const b = next ? r.tierAt[next] : r.done;
    const took = b - a;
    const want = S.PACING[id];
    ok(took > 300, `${id} is over in ${Math.round(took)}s, which is not a tier`);
    ok(Math.abs(took - want) / want < 0.25,
      `${id} took ${Math.round(took)}s against a design of ${want}s`);
    line.push(`${id} ${Math.round(took)}s`);
  }
  ok(r.done > 0 && r.done < 20000, `the whole ladder took ${Math.round(r.done)}s`);
  line.push(`ALL ${(r.done / 3600).toFixed(2)}h`);
  return line.join('  ');
});

await check('NO DEAD TIER: you are never left with nothing under construction', () => {
  const r = play();
  ok(r.worst <= S.MAX_DEAD_WAIT,
    `waited ${Math.round(r.worst)}s with nothing building (limit ${S.MAX_DEAD_WAIT})`);
  const idleFrac = r.idle / r.done;
  ok(idleFrac < 0.35, `${(idleFrac * 100).toFixed(0)}% of the ladder is standing about`);
  // and every rung has at least three separate decisions in it
  for (const t of S.TIERS) {
    if (t.id === 'surface') continue;
    eq(S.megaOfTier(t.id).filter(m => !m.opens).length >= 3, true,
      `${t.id} has fewer than three things to do before its gate`);
  }
  const worstOf = r.waits.reduce((a, b) => (a[1] >= b[1] ? a : b));
  return `longest gap ${Math.round(r.worst)}s (before the ${S.megaSpec(worstOf[0]).name}), ` +
         `${(idleFrac * 100).toFixed(0)}% idle over ${r.waits.length} builds`;
});

await check('the ladder does not change shape with the step size', () => {
  const a = play({ dt: 1, limit: 9000 });
  const b = play({ dt: 1 / 30, limit: 9000 });
  eq(a.sc.tier, b.sc.tier, 'a fine step landed on a different tier');
  for (const k of Object.keys(a.tierAt)) {
    ok(Math.abs(a.tierAt[k] - b.tierAt[k]) < 30,
      `${k} at ${a.tierAt[k]}s coarse vs ${b.tierAt[k]}s fine`);
  }
  return `${Object.keys(a.tierAt).length} tiers within 30s across a 30x step change`;
});

await check('THE GROUND IS LOAD-BEARING: cut the walking game and the ladder stops', () => {
  // Rule 3 is a claim, and a claim you cannot break is decoration. Take the
  // surface economy away and the ladder must actually stall.
  const r = play({ base: { alloy: 0.10 * 0.02, shard: 0.30 * 0.02, biomass: 0.40 * 0.02 }, limit: 40000 });
  const full = play();
  ok(S.tierIndex(r.sc.tier) < S.tierIndex(full.sc.tier),
    `a starved surface still reached ${r.sc.tier}`);
  return `at 2% of the surface economy the ladder stops at ${r.sc.tier}, not ${full.sc.tier}`;
});

await check('a larder runs dry, and the game says so instead of going quiet', () => {
  const sc = S.createScale();
  const ground = { alloy: 1e9, shard: 1e9, biomass: 1e9 };
  sc.tier = 'system';
  sc.stock.world = 3;
  sc.built.maw = 1;
  S.advanceScale(sc, ground, 240 * 4);
  eq(sc.stock.world, 0, 'the Maw did not clear the larder');
  eq(sc.stalled.maw, true, 'it ran out and said nothing');
  eq(S.report(sc, ground).stock.world, 0);
  const before = sc.res.mass.log10();
  S.advanceScale(sc, ground, 6000);
  near(sc.res.mass.log10(), before, 1e-9, 'a stalled Maw kept paying');
  // and it must not BANK the wait. A stalled eater that saved up 6,000s of
  // credit would clear a refilled larder in a single tick, which is a whole
  // tier deleted by an accounting bug.
  sc.stock.world = 5;
  S.advanceScale(sc, ground, 1);
  eq(sc.stock.world, 4, 'a stalled Maw banked its idle time and ate the larder at once');
  return 'the Maw ate 3 worlds, stalled, stayed stalled, and banked nothing';
});

await check('the greedy option exists and the game never recommends it', () => {
  const sc = S.createScale();
  const ground = { alloy: 1e9, shard: 1e9, biomass: 1e9 };
  sc.tier = 'system';
  sc.res.mass = D.of(1e30); sc.res.energy = D.of(1e30);
  sc.stock.world = 900;
  const g = S.nextGoal(sc, ground);
  ok(g && g.id !== 'cracker', 'the hint walked the player into the World Cracker');
  eq(S.blockedReason(sc, ground, 'cracker'), null, 'but it must still be buildable');
  // and it really is the worse deal, which is why the hint refuses it
  const c = S.megaSpec('cracker'), m = S.megaSpec('maw');
  const perWorldC = c.eats.mass, perWorldM = m.eats.mass;
  ok(perWorldC * 14 < perWorldM, 'the cracker is not actually wasteful');
  return `the cracker keeps ${(100 * perWorldC / perWorldM).toFixed(0)}% of a world; the hint says nothing about it`;
});

await check('the endgame passes 1e308, which is the reason for all of this', () => {
  const sc = S.createScale();
  const ground = { alloy: 1e12, shard: 1e12, biomass: 1e12 };
  sc.tier = 'galaxies';
  sc.stock.galaxy = 1e300;
  sc.built.unmaking = 1;
  const step = 60;
  for (let t = 0; t < 60000; t += step) S.advanceScale(sc, ground, step);
  ok(sc.res.mass.log10() > 308, `only reached 1e${sc.res.mass.log10().toFixed(0)}`);
  eq(sc.res.mass.toNumber(), Infinity, 'a float would still have held this');
  ok(B.compare(sc.res.mass).length > 0);
  return `1e${Math.round(sc.res.mass.log10())} kg after ${(60000 / 3600).toFixed(0)}h of the Long Unmaking`;
});

/* ============================================================== state === */

await check('a ladder save round-trips and cannot smuggle anything in', () => {
  const r = play({ limit: 6000 });
  const back = S.loadScale(JSON.parse(JSON.stringify(S.saveScale(r.sc))));
  eq(back.tier, r.sc.tier);
  near(back.res.mass.log10(), r.sc.res.mass.log10(), 1e-9);
  eq(Object.keys(back.built).length, Object.keys(r.sc.built).length);
  eq(back.stock.world, r.sc.stock.world);

  const doctored = S.saveScale(r.sc);
  doctored.built.deathstar = 1;
  doctored.building = { id: 'deathstar', endsAt: 5 };
  const clean = S.loadScale(doctored);
  eq(clean.built.deathstar, undefined, 'a save added something that does not exist');
  eq(clean.building, null, 'a save started building something that does not exist');
  eq(S.loadScale({ v: 9 }), null, 'a future save version was accepted');
});

await check('you cannot build what you cannot pay for, or reach, or build twice', () => {
  const sc = S.createScale();
  const ground = { alloy: 0, shard: 0, biomass: 0 };
  eq(S.blockedReason(sc, ground, 'ring'), 'needs orbit');
  eq(S.blockedReason(sc, ground, 'foundry', { town: false }), 'needs a town');
  eq(S.blockedReason(sc, ground, 'foundry', { town: true }), 'not enough alloy');
  ground.alloy = 1e6; ground.shard = 1e6; ground.biomass = 1e6;
  ok(S.begin(sc, ground, 'foundry', { town: true }), 'could not sink a foundry');
  near(ground.alloy, 1e6 - S.megaSpec('foundry').needs.alloy, 1e-9, 'the cost was not taken');
  ok(/already building/.test(S.blockedReason(sc, ground, 'tether', { town: true })));
  S.advanceScale(sc, ground, S.buildTime('foundry') + 1);
  eq(S.blockedReason(sc, ground, 'foundry', { town: true }), 'already standing');
  eq(S.begin(sc, ground, 'foundry', { town: true }), null);
});

await check('a gate, and only a gate, moves you up', () => {
  const sc = S.createScale();
  const ground = { alloy: 1e9, shard: 1e9, biomass: 1e9 };
  ok(S.begin(sc, ground, 'foundry', { town: true }));
  S.advanceScale(sc, ground, S.buildTime('foundry') + 1);
  eq(sc.tier, 'surface', 'a non-gate moved the player up a tier');
  ok(S.begin(sc, ground, 'tether', { town: true }));
  S.advanceScale(sc, ground, S.buildTime('tether') + 1);
  eq(sc.tier, 'orbit', 'the Anchor Line did not open orbit');
  eq(sc.stock.world, 0, 'orbit handed out worlds');
});

/* ============================================================== purity == */

const MINE = ['app/src/sim/bignum.js', 'app/src/sim/scale.js', 'app/src/view/cosmos.js'];
const src = {};
for (const p of MINE) src[p] = readFileSync(join(ROOT, p), 'utf8');
function strip(t) {
  return t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

await check('sim/ stays pure', () => {
  const banned = [/\bdocument\b/, /\bwindow\b/, /Math\.random/, /\bnew Date\b/, /Date\.now/,
                  /requestAnimationFrame/, /localStorage/, /from ['"].*three/];
  for (const p of MINE) {
    if (!p.includes('sim/')) continue;
    const t = strip(src[p]);
    for (const b of banned) ok(!b.test(t), `${p} :: ${b}`);
  }
  return 'bignum.js, scale.js';
});

/* ============================================================== cosmos == */

const COS = src['app/src/view/cosmos.js'];

await check('ZERO ASSETS, ZERO DOWNLOADS: the view fetches nothing and needs no files', () => {
  const t = strip(COS);
  for (const b of [/\bfetch\s*\(/, /XMLHttpRequest/, /new Image\b/, /\.png\b/, /\.jpg\b/,
                   /\.hdr\b/, /TextureLoader/, /FileLoader/, /https?:\/\//]) {
    ok(!b.test(t), `cosmos.js reaches outside the project :: ${b}`);
  }
  // every texture is painted in code, and a machine that cannot paint one still runs
  ok(/createElement\('canvas'\)/.test(t), 'nothing is painted, so where do the textures come from');
  ok(/catch\s*\(/.test(t) && /return null/.test(t), 'a missing canvas is not survivable');
  ok(/if \(gtex\)/.test(t), 'a null texture is handed to a material anyway');
  return 'no fetch, no loader, no URL, no file — every texture is a canvas';
});

await check('cosmos: nothing is allocated in the frame', () => {
  // Extract tick() by brace-matching and read what is actually in it. A single
  // `new THREE.Vector3` per frame at 60fps is 3,600 objects a minute and a GC
  // pause somebody will report as a stutter.
  const t = strip(COS);
  const at = t.indexOf('function tick(');
  ok(at > 0, 'there is no per-frame function called tick');
  let i = t.indexOf('{', at), depth = 0, end = -1;
  for (; i < t.length; i++) {
    if (t[i] === '{') depth++;
    else if (t[i] === '}') { depth--; if (depth === 0) { end = i; break; } }
  }
  ok(end > 0, 'could not read the body of tick()');
  const body = t.slice(at, end);
  ok(!/\bnew\b/.test(body), 'tick() allocates: it contains `new`');
  ok(!/\.clone\s*\(/.test(body), 'tick() allocates: it clones');
  ok(/_m|_p|_q|_s/.test(body), 'tick() does not use the preallocated temporaries');
  return `${body.split('\n').length} lines of frame code, no allocation`;
});

await check('cosmos: a glow has a falloff and everything far away is fog:false', () => {
  const t = strip(COS);
  ok(/createRadialGradient/.test(t), 'a glow was drawn without a falloff');
  ok(!/SphereGeometry[\s\S]{0,200}AdditiveBlending[\s\S]{0,80}opacity:\s*0\.\d+[\s\S]{0,80}glow/.test(t),
    'a constant-opacity additive sphere is a flat disc');
  const mats = t.match(/new THREE\.[A-Za-z]*Material\(\{[\s\S]*?\}\)/g) || [];
  ok(mats.length >= 8, `only found ${mats.length} materials`);
  for (const m of mats) ok(/fog:\s*false/.test(m), 'a material out here is not fog:false: ' + m.slice(0, 60));
  return `${mats.length} materials, all fog:false; every glow is a radial gradient`;
});

await check('cosmos: there is a view for every tier above the ground, and it is lazy', () => {
  const t = strip(COS);
  for (const tier of ['orbit', 'system', 'galaxy', 'galaxies']) {
    ok(t.includes(`'${tier}'`), `no view for ${tier}`);
    ok(new RegExp(`CAMERA[\\s\\S]*${tier}:`).test(t), `no camera for ${tier}`);
  }
  ok(/function ensure\(/.test(t) && /if \(views\[tier\]\) return/.test(t),
    'every tier is built at boot, so looking at nothing costs everything');
  ok(!/render\.js/.test(t), 'cosmos reaches into the surface renderer');
  ok(/heightAt/.test(t), 'the planet from orbit is not the planet you walked on');
  return 'orbit, system, galaxy, galaxies — each built the first time it is looked at';
});

await check('cosmos: it builds, runs and tears down with NO canvas at all', async () => {
  // The rule the whole project stands on: zero downloads, zero assets. This
  // runs the real view in node, where `document` does not exist, so every
  // texture comes back null -- and every tier must still build and tick.
  const V = await import(pathToFileURL(join(ROOT, 'app/src/view/cosmos.js')));
  const t0 = Date.now();
  const cos = V.createCosmos();
  const sc = S.createScale();
  sc.built.tether = 1; sc.built.ring = 1; sc.built.maw = 1;
  sc.eaten.world = 3; sc.eaten.star = 1.2e11; sc.eaten.galaxy = 40;
  eq(cos.group.visible, false, 'the cosmos is on screen before anyone asked for it');
  for (const tier of V.COSMOS_TIERS) {
    eq(cos.setTier(tier), true, `no view for ${tier}`);
    eq(cos.currentTier(), tier);
    for (let i = 0; i < 4; i++) cos.tick(sc, 1 / 60);
    let shown = 0;
    for (const c of cos.group.children) if (c.type === 'Group' && c.visible) shown++;
    eq(shown, 1, `${tier} left ${shown} tiers on screen at once`);
    ok(V.cameraFor(tier).far > V.cameraFor(tier).near * 100, `${tier} has no depth range`);
  }
  cos.setTier(null);
  eq(cos.currentTier(), null);
  eq(cos.group.visible, false);
  cos.dispose();
  return `4 tiers built, ticked and disposed in ${Date.now() - t0}ms with no DOM and no textures`;
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
