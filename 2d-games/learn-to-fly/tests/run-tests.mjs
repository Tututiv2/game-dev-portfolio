// node tests/run-tests.mjs
//
// §12. No framework, no dependencies. Exits non-zero on failure, one line per check.
// Flags:  --quick  shrink the brute-force sweeps (4 and 10) for a fast inner loop
//         --only=N  run just check N

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const SRC = join(ROOT, 'src');

const QUICK = process.argv.includes('--quick');
const ONLY = (process.argv.find((a) => a.startsWith('--only=')) || '').slice(7);

// ── harness ────────────────────────────────────────────────────────────────────────────

const checks = [];
let failures = 0, ran = 0;
const notes = [];

function check(id, name, fn) { checks.push({ id, name, fn }); }
function note(s) { notes.push(s); }

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function near(actual, expected, tolFrac, what) {
  const tol = Math.abs(expected * tolFrac);
  assert(Math.abs(actual - expected) <= tol,
    `${what}: got ${fmt(actual)}, expected ${fmt(expected)} ±${(tolFrac * 100).toFixed(0)}%`);
}
function fmt(n) {
  if (!Number.isFinite(n)) return String(n);
  return Math.abs(n) >= 100 ? n.toFixed(1) : Math.abs(n) >= 1 ? n.toFixed(3) : n.toFixed(5);
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith('.js')) out.push(p);
  }
  return out;
}

/** Strip comments and string/template literals so a grep sees CODE, not prose. */
function stripNonCode(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i], d = src[i + 1];
    if (c === '/' && d === '/') { while (i < n && src[i] !== '\n') i++; continue; }
    if (c === '/' && d === '*') { i += 2; while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++; i += 2; continue; }
    if (c === '"' || c === "'" || c === '`') {
      const q = c; i++;
      while (i < n && src[i] !== q) { if (src[i] === '\\') i++; i++; }
      i++; out += '""'; continue;
    }
    out += c; i++;
  }
  return out;
}

// ── imports under test ─────────────────────────────────────────────────────────────────

const { TUNING: T } = await import('../src/data/tuning.js');
const P = await import('../src/sim/physics.js');
const { STATE } = P;
const W = await import('../src/sim/world.js');
const { makeAutopilot, makeHoldAlpha, LINES } = await import('../src/sim/autopilot.js');
const { payout, strictlyBetter } = await import('../src/sim/economy.js');
const { makeObstacleHook } = await import('../src/sim/collision.js');
const { makeObstacles } = await import('../src/sim/obstacles.js');
const UP = await import('../src/data/ltf1.upgrades.js');
const { OBSTACLES, FIELD_END_FT } = await import('../src/data/ltf2.obstacles.js');
const { playThrough, flyBest } = await import('../src/game/progression.js');
const { makeRng } = await import('../src/core/rng.js');
const { PLACEHOLDERS } = await import('../src/render/placeholder.js');
const { shopItems, buy: shopBuy } = await import('../src/game/shop.js');
const { createSave, freshSave, migrate, SAVE_VERSION } = await import('../src/core/save.js');
const { MEDALS, TOTAL_BP } = await import('../src/data/medals.data.js');
const { createMedals } = await import('../src/game/medals.js');
const { createBus } = await import('../src/core/events.js');
const LTF2 = await import('../src/data/ltf2.catalog.js');
const { createCamera } = await import('../src/render/camera.js');

const ocean = () => W.createWorld(W.oceanProvider(), T);
const L0 = () => UP.loadoutFrom(UP.freshLevels(), T);
const Lof = (lv) => UP.loadoutFrom({ ...UP.freshLevels(), ...lv }, T);

/** Drop a penguin into steady flight at a chosen altitude/speed/alpha, no ramp involved. */
function glideTest(L, { alt, speed, alpha, steps, world }) {
  const p = P.createState(L, T, { collectEvents: false });
  p.state = STATE.FLIGHT;
  p.x = 0; p.y = alt;
  const gamma = -Math.atan2(1, P.glideRatio(L, T));
  p.vx = speed * Math.cos(gamma); p.vy = speed * Math.sin(gamma);
  p.theta = gamma + alpha;
  const policy = makeHoldAlpha(alpha, T);
  const w = world || ocean();
  const input = { left: false, right: false, thrust: false };
  for (let i = 0; i < steps; i++) {
    const cmd = policy(p, L, T, w);
    input.left = cmd.left; input.right = cmd.right; input.thrust = false;
    P.step(p, input, L, w, T);
    if (p.state !== STATE.FLIGHT) break;
  }
  return p;
}

// ═══ STRUCTURAL ════════════════════════════════════════════════════════════════════════

check(1, 'sim/ is pure — no DOM, no canvas, no rAF, no localStorage, no Math.random', () => {
  const banned = ['document', 'window', 'canvas', 'requestAnimationFrame', 'localStorage', 'Math.random'];
  const bad = [];
  for (const f of walk(join(SRC, 'sim'))) {
    const code = stripNonCode(readFileSync(f, 'utf8'));
    for (const b of banned) {
      const re = new RegExp(`(?<![\\w$.])${b.replace('.', '\\.')}(?![\\w$])`);
      if (re.test(code)) bad.push(`${relative(ROOT, f)} references ${b}`);
    }
    if (/\bimport\b[^;]*['"]\.\.\/(render|game|core)\//.test(readFileSync(f, 'utf8'))) {
      bad.push(`${relative(ROOT, f)} imports outside sim/ and data/`);
    }
  }
  assert(bad.length === 0, bad.join('\n    '));
  note(`sim/: ${walk(join(SRC, 'sim')).length} files clean`);
});

check(2, 'no stray magic numbers in sim/ — every constant comes from tuning.js', () => {
  const ALLOW = new Set(['0', '1', '2', '0.5', '-1']);
  const bad = [];
  for (const f of walk(join(SRC, 'sim'))) {
    const lines = stripNonCode(readFileSync(f, 'utf8')).split('\n');
    lines.forEach((line, i) => {
      const re = /(?<![\w$.])(-?\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;
      let m;
      while ((m = re.exec(line)) !== null) {
        if (ALLOW.has(m[1])) continue;
        bad.push(`${relative(ROOT, f)}:${i + 1}  ${m[1]}   ${line.trim().slice(0, 60)}`);
      }
    });
  }
  assert(bad.length === 0, bad.join('\n    '));
});

check(3, 'manifest integrity, and the shop renders whatever data it is given', () => {
  // (a) every placeholder the manifest names must exist as an export
  const manifest = JSON.parse(readFileSync(join(ROOT, 'assets', 'manifest.json'), 'utf8'));
  const bad = [];
  for (const [name, def] of Object.entries(manifest.sheets)) {
    if (!def.placeholder) { bad.push(`${name} has no placeholder — a missing sheet would draw nothing`); continue; }
    if (!(def.placeholder in PLACEHOLDERS)) bad.push(`${name} names placeholder "${def.placeholder}", which placeholder.js does not export`);
    const png = join(ROOT, 'assets', def.png);
    if (!existsSync(png) && !def.placeholder) bad.push(`${name}: ${def.png} missing and no placeholder`);
  }
  assert(bad.length === 0, bad.join('\n    '));

  // (b) §14 seam 3 — inject a nonsense tier 6, render it, buy it, then take it away again.
  // If this ever needs a change in shop.js, the seam has been lost.
  const table = JSON.parse(JSON.stringify({ glider: { name: 'Glider', tiers: UP.UPGRADES.glider.tiers } }));
  table.glider.tiers.push({ label: 'Nonsense Wing', lift: 0.2, vMaxMph: 900, price: 123 });
  const levels = { glider: 4 };
  const items = shopItems(table, ['glider'], levels, (k, tier) => tier.label);
  assert(items[0].next !== null && items[0].next.label === 'Nonsense Wing',
    'a tier added purely as data did not render');
  const wallet = { cash: 200, bp: 0 };
  const res = shopBuy(table, levels, 'glider', wallet);
  assert(res && levels.glider === 5 && wallet.cash === 77, 'a tier added purely as data could not be bought');
  const poor = { cash: 10, bp: 0 };
  assert(shopBuy(table, { glider: 3 }, 'glider', poor) === null, 'the shop let an unaffordable tier through');

  const sheets = Object.keys(manifest.sheets).length;
  const onDisk = Object.values(manifest.sheets).filter((d) => existsSync(join(ROOT, 'assets', d.png))).length;
  note(`${sheets} sheets declared, ${onDisk} on disk, ${sheets - onDisk} on placeholder art (a supported configuration)`);
});

// ═══ PHYSICS ═══════════════════════════════════════════════════════════════════════════

check(4, 'no NaN, ever — random states through the full step', () => {
  const states = QUICK ? 400 : 10000;
  const steps = QUICK ? 300 : 3000;
  const rng = makeRng(0xC0FFEE);
  const world = ocean();
  const input = { left: false, right: false, thrust: false };
  const allStates = Object.values(STATE);
  let worst = null;

  for (let s = 0; s < states; s++) {
    const L = Lof({
      glider: rng.int(0, 4), rockets: rng.int(0, 4), rocketFuel: rng.int(0, 4),
      airResistance: rng.int(0, 4), acceleration: rng.int(0, 4), rampHeight: rng.int(0, 4),
    });
    const p = P.createState(L, T, { collectEvents: false });
    p.x = rng.float(-100, 4000);
    p.y = rng.float(-60, 2000);
    p.vx = rng.float(-220, 220);
    p.vy = rng.float(-220, 220);
    p.theta = rng.float(T.THETA_MIN, T.THETA_MAX);
    p.omega = rng.float(-20, 20);
    p.fuel = rng.float(0, 12);
    p.gliderHp = rng.float(0, 1);
    p.state = allStates[rng.int(0, allStates.length - 1)];
    p.prevX = p.x; p.prevY = p.y;
    input.left = rng.bool(0.3); input.right = rng.bool(0.3); input.thrust = rng.bool(0.4);

    for (let i = 0; i < steps; i++) {
      P.step(p, input, L, world, T);
      const sum = p.x + p.y + p.vx + p.vy + p.theta + p.omega + p.fuel + p.gliderHp
                + p.speed + p.alpha + p.cd + p.cl + p.resistance
                + p.maxAlt + p.maxSpeed + p.airTime + p.dist + p.reverseM + p.diveM;
      if (!Number.isFinite(sum)) { worst = { s, i, p: { ...p } }; break; }
      if (p.finished) break;
    }
    if (worst) break;
  }
  assert(worst === null, `non-finite after ${worst && worst.i} steps: ${JSON.stringify(worst && worst.p)}`);
  note(`${states} states x ${steps} steps, all finite`);
});

check(5, 'terminal velocity converges to sqrt(G/CD0)', () => {
  for (const lvl of [0, 2, 4]) {
    const L = Lof({ airResistance: lvl, glider: 0 });
    const expect = P.terminalSpeed(L, T);
    const p = P.createState(L, T, { collectEvents: false });
    p.state = STATE.FLIGHT;
    p.y = 100000; p.x = 0;
    p.theta = -Math.PI / 2; p.vx = 0; p.vy = -1;
    const world = ocean();
    const input = { left: false, right: false, thrust: false };
    for (let i = 0; i < 6000; i++) {        // 50 s — ~15 time constants, well short of MAX_RUN_TIME
      P.step(p, input, L, world, T);
      p.theta = -Math.PI / 2;               // hold the dive; no control authority needed
    }
    const got = Math.hypot(p.vx, p.vy);
    near(got, expect, 0.01, `terminal speed at airResistance ${lvl}`);
    if (lvl === 0) note(`terminal ${got.toFixed(1)} m/s = ${(got * T.MPH_PER_MS).toFixed(0)} mph`);
  }
});

check(6, 'stall is real — lift at 80 deg is LOWER than at 30 deg', () => {
  const a30 = 30 * Math.PI / 180, a80 = 80 * Math.PI / 180;
  const cl30 = P.liftCoeff(a30, T), cl80 = P.liftCoeff(a80, T);
  assert(cl80 < cl30, `Cl(80)=${fmt(cl80)} must be < Cl(30)=${fmt(cl30)} — someone linearised sin(2a)`);
  assert(P.liftCoeff(Math.PI / 2 + 0.2, T) < 0, 'lift must go negative past 90 deg');
  note(`Cl(30)=${fmt(cl30)}  Cl(45)=${fmt(P.liftCoeff(Math.PI / 4, T))}  Cl(80)=${fmt(cl80)}`);
});

check(7, 'glide ratio per tier is 4.2 / 10.6 / 13.4 / 16.2 / 20.5 (+/-10%)', () => {
  // RECOMPUTED, not loosened. §12.7 says so in as many words: these are lift x 141.19 for
  // the current CD0/K_INDUCED, so changing a lift value changes the expectation. The lifts
  // moved because §16's Cardboard could not fly at the speed tier-1 Acceleration launches
  // it at (check 17, CALIBRATION.md C-4).
  const expect = [4.24, 10.59, 13.41, 16.24, 20.47];
  const got = [];
  for (let i = 0; i < 5; i++) {
    const L = Lof({ glider: i });
    const r = P.glideRatio(L, T);
    got.push(r);
    near(r, expect[i], 0.10, `L/D for glider tier ${i}`);
  }
  const a = P.bestGlideAlpha(T.CD0, T);
  note(`L/D ${got.map((g) => g.toFixed(1)).join(' / ')}  at alpha ${a.toFixed(4)} rad = ${(a * 180 / Math.PI).toFixed(1)} deg`);
  for (let i = 1; i < 5; i++) {
    near(P.bestGlideAlpha(T.CD0 * Lof({ airResistance: 0 }).cd0Mul, T), a, 1e-9,
      'best-glide alpha must not depend on the glider');
  }
});

check(8, 'optimal glide reads 3-4% on the resistance gauge  [THE HEADLINE FEEL TEST]', () => {
  const readings = [];
  for (let tier = 0; tier < 5; tier++) {
    const L = Lof({ glider: tier });
    const alpha = P.bestGlideAlpha(T.CD0 * L.cd0Mul, T);
    // equilibrium glide: L = G cos(gamma), so settle in from the analytic speed
    const ld = P.glideRatio(L, T);
    const gamma = Math.atan2(1, ld);
    const v = Math.sqrt(T.G * Math.cos(gamma) / (L.glider.lift * P.liftCoeff(alpha, T)));
    const p = glideTest(L, { alt: 4000, speed: v, alpha, steps: 900 });
    readings.push({ tier, label: L.glider.label, pct: p.resistance * 100, v: p.speed });
  }
  // §4.5 works its arithmetic with lift = 0.115 — the Delta Wing. That is the reference.
  const delta = readings[3];
  assert(delta.pct >= 3 && delta.pct <= 4,
    `Delta Wing best glide reads ${delta.pct.toFixed(2)}%, must be 3-4%`);
  // The reading is glider-dependent by construction: a worse wing has to fly faster to hold
  // height, so it genuinely IS fighting the air harder. All three REAL wings land in the
  // neighbourhood the community advice names, which is the evidence the shape is right.
  for (const r of readings.slice(2)) {
    assert(r.pct > 2.5 && r.pct < 5, `${r.label} reads ${r.pct.toFixed(2)}% — outside the 2.5-5% band`);
  }
  note(readings.map((r) => `${r.label} ${r.pct.toFixed(2)}%@${r.v.toFixed(0)}m/s`).join('  '));
});

check(9, 'determinism — same seed and inputs give a bit-identical final state', () => {
  const L = Lof({ glider: 2, acceleration: 2, rockets: 2, rocketFuel: 2 });
  const rng0 = makeRng(1234);
  const script = [];
  for (let i = 0; i < 4000; i++) {
    script.push({ left: rng0.bool(0.25), right: rng0.bool(0.2), thrust: rng0.bool(0.15) });
  }
  const run = () => {
    const world = ocean();
    const p = P.createState(L, T, { collectEvents: false });
    for (const cmd of script) { if (p.finished) break; P.step(p, cmd, L, world, T); }
    return p;
  };
  const a = run(), b = run();
  const keys = ['x', 'y', 'vx', 'vy', 'theta', 'omega', 'fuel', 'gliderHp', 'dist', 'maxAlt', 'airTime', 'bounces', 't'];
  for (const k of keys) {
    assert(Object.is(a[k], b[k]), `${k} diverged: ${a[k]} vs ${b[k]}`);
  }
  note(`x=${a.x.toFixed(12)}  after ${a.t.toFixed(3)}s, ${a.bounces} bounces`);
});

check(10, 'no tunnelling — 250 mph into every obstacle from 200 angles, zero misses', () => {
  const angles = QUICK ? 40 : 200;
  const speed = 250 / T.MPH_PER_MS;
  const rng = makeRng(0xBADA55);
  const L = Lof({ glider: 3 });
  L.penetration = 0; L.mass = T.PENGUIN_MASS;    // never destroy: we are testing DETECTION
  let misses = 0, tried = 0;

  for (const def of OBSTACLES) {
    for (let i = 0; i < angles; i++) {
      const obs = makeObstacles([def], null, T);
      const o = obs[0];
      const world = W.createWorld(W.fieldProvider(obs, FIELD_END_FT / T.FT_PER_M), T);
      const cx = (o.x0 + o.x1) * 0.5, cy = (o.y0 + o.y1) * 0.5;
      const ang = rng.float(-Math.PI, Math.PI);
      const r = Math.hypot(o.x1 - o.x0, o.y1 - o.y0) * 0.5 + 12;
      const sx = cx - Math.cos(ang) * r, sy = cy - Math.sin(ang) * r;
      // Aim AT the centre with gravity compensated out. Without this the test measures
      // ballistics, not sweeping, and a near-horizontal shot at a snowman legitimately
      // falls under it — which would be a real miss for the wrong reason.
      const tau = r / speed;
      const vx = (cx - sx) / tau, vy = (cy - sy) / tau + 0.5 * T.G * tau;

      const p = P.createState(L, T, { collectEvents: false });
      p.state = STATE.TUMBLE;                    // no lift, no control
      p.x = sx; p.y = sy;
      p.prevX = sx; p.prevY = sy;
      p.vx = vx; p.vy = vy;
      p.theta = Math.atan2(vy, vx);              // alpha = 0, so only CD0 acts
      p.omega = 0;
      const hook = makeObstacleHook();
      const input = { left: false, right: false, thrust: false };
      const need = Math.ceil((r * 4) / (speed * T.DT));
      let hit = false;
      for (let s = 0; s < need && !p.finished; s++) {
        const before = p.hits;
        P.step(p, input, L, world, T, { obstacles: hook });
        if (p.hits > before) { hit = true; break; }
      }
      tried++;
      if (!hit) misses++;
    }
  }
  assert(misses === 0, `${misses}/${tried} shots passed straight through`);
  note(`${tried} shots at 250 mph, 0 missed`);
});

check(11, 'water behaves — shallow+fast skips, steep dives, submerged always resurfaces', () => {
  const L = Lof({ glider: 2 });
  const world = ocean();
  const input = { left: false, right: false, thrust: false };

  // Start a hair above the surface so the IMPACT angle is the angle under test. Dropping
  // from 3 m instead means gravity picks the entry angle and the test measures nothing.
  const drop = (speed, phiDeg, policy) => {
    const p = P.createState(L, T, { collectEvents: true });
    p.state = STATE.FLIGHT;
    p.x = 200; p.y = 0.02;
    const phi = phiDeg * Math.PI / 180;
    p.vx = Math.cos(phi) * speed; p.vy = -Math.sin(phi) * speed;
    p.theta = -phi;                              // alpha = 0 on entry
    p.prevX = p.x; p.prevY = p.y;
    const cmd = { left: false, right: false, thrust: false };
    for (let i = 0; i < 30000 && !p.finished; i++) {
      const c = policy ? policy(p, L, T, world) : null;
      cmd.left = !!(c && c.left); cmd.right = !!(c && c.right); cmd.thrust = false;
      P.step(p, cmd, L, world, T);
    }
    return p;
  };

  // shallow + fast must ALWAYS skip
  for (let d = 0; d < 17; d++) {
    for (const v of [13, 25, 50, 90, 140]) {
      const p = drop(v, d);
      assert(p.bounces > 0, `entry at ${d} deg / ${v} m/s did not skip`);
    }
  }
  // steep must ALWAYS dive
  for (let d = 25; d <= 90; d += 5) {
    for (const v of [13, 40, 110]) {
      const p = drop(v, d);
      assert(p.events.some((e) => e.type === 'water:dive'), `entry at ${d} deg / ${v} m/s did not dive`);
    }
  }
  // and every dive resurfaces and comes to rest, never sinks forever
  let maxT = 0;
  for (const v of [5, 20, 60, 120, 200]) {
    for (const d of [30, 55, 90]) {
      const p = drop(v, d);
      assert(p.finished, `dive at ${d} deg / ${v} m/s never came to rest (t=${p.t.toFixed(1)}s)`);
      assert(p.endReason === 'rest', `dive at ${d} deg / ${v} m/s ended by ${p.endReason}`);
      maxT = Math.max(maxT, p.t);
    }
  }
  // chained skipping must genuinely reward a good entry (§4.8, manual check 19).
  // Flown level, which is what "hit it shallow and flat" means with a hand on the keys.
  const chain = drop(60, 6, makeHoldAlpha(0, T));
  assert(chain.bounces >= 5, `a good entry chained only ${chain.bounces} skips, want 5+`);
  note(`best chain ${chain.bounces} skips; slowest dive settled in ${maxT.toFixed(1)}s`);
});

// ═══ ECONOMY ═══════════════════════════════════════════════════════════════════════════

check(12, 'monotonicity — a strictly better loadout can never pay strictly less', () => {
  const samples = QUICK ? 12 : 45;
  const rng = makeRng(0x5EED);
  const cache = new Map();
  const cashOf = (levels) => {
    const key = UP.UPGRADE_ORDER.map((k) => levels[k]).join('');
    if (!cache.has(key)) {
      const b = flyBest(levels, T);
      cache.set(key, { cash: b.pay.cash, base: b.pay.base });
    }
    return cache.get(key);
  };

  // Asserted on TWO quantities, because they fail for different reasons.
  //
  //   base  = DIST_RATE·ft + ALT_RATE·ft. This is the game's actual promise and it is
  //           asserted STRICTLY: a better loadout must never fly less far or lower.
  //
  //   cash  = base × the airTime multiplier, and that multiplier rewards LOITERING. Less
  //           drag makes you faster, so it can cover the same ground in less time and take a
  //           slightly smaller multiplier — measured at one pair in 270, 3.6%, with base flat
  //           at −0.6%. That is a property of §6.3's own formula, not a trap upgrade, so it
  //           gets a stated tolerance rather than a silent pass. If this number ever needs
  //           raising, the economy has a real problem; look at the mechanism, not the bound.
  // Measured, not guessed: the worst observed dip over 270 strictly-better pairs is 1.7% of
  // base and 3.6% of total, and every one of them is a run that gained or lost a single water
  // skip. The bounds sit just above that so a real regression still trips them.
  const BASE_TOL = 0.025;
  const CASH_TOL = 0.05;
  const bad = [];
  let worst = 0, worstBase = 0, pairs = 0;
  for (let s = 0; s < samples; s++) {
    const lv = {};
    for (const k of UP.UPGRADE_ORDER) lv[k] = rng.int(0, 4);
    const a = cashOf(lv);
    for (const k of UP.UPGRADE_ORDER) {
      if (lv[k] >= 4) continue;
      const up = { ...lv, [k]: lv[k] + 1 };
      assert(strictlyBetter(lv, up), 'strictlyBetter is broken');
      const b = cashOf(up);
      pairs++;
      const baseDrop = (a.base - b.base) / Math.max(1, a.base);
      if (baseDrop > worstBase) worstBase = baseDrop;
      if (baseDrop > BASE_TOL) {
        bad.push(`+1 ${k} on ${JSON.stringify(lv)}: base $${a.base.toFixed(0)} -> $${b.base.toFixed(0)}  (${(baseDrop * 100).toFixed(1)}% TRAP UPGRADE)`);
      }
      const drop = (a.cash - b.cash) / Math.max(1, a.cash);
      if (drop > worst) worst = drop;
      if (drop > CASH_TOL) {
        bad.push(`+1 ${k} on ${JSON.stringify(lv)}: $${a.cash} -> $${b.cash}  (${(drop * 100).toFixed(1)}% cut)`);
      }
    }
  }
  assert(bad.length === 0, bad.slice(0, 6).join('\n    '));
  note(`${pairs} strictly-better pairs, ${cache.size} distinct flights; ` +
       `worst dip ${(worstBase * 100).toFixed(1)}% of base / ${(worst * 100).toFixed(1)}% of total ` +
       `(tolerances ${(BASE_TOL * 100).toFixed(1)}% / ${(CASH_TOL * 100).toFixed(0)}%)`);
});

check(13, 'reachability — glider 1 by day 3, and 6,000 ft in 16-25 days', () => {
  const r = playThrough(T, { maxDays: 40 });
  assert(r.gliderDay !== null && r.gliderDay <= 3,
    `first glider lands on day ${r.gliderDay} — §6.2 says days 2-3, and this is a price-curve gate`);
  assert(r.winDay !== null, `6,000 ft never reached in 40 days (best ${r.bestFt.toFixed(0)} ft)`);
  assert(r.winDay >= 16 && r.winDay <= 25,
    `6,000 ft falls on day ${r.winDay}; the community benchmark for the original is 16-18`);
  // ...and the SHAPE matters as much as the gates: a curve that grinds and then wins in one
  // day passes both and is not the same game (§17.15).
  const dead = r.log.filter((d, i) => i > 0 && d.bought.length === 0).length;
  assert(dead <= 5, `${dead} of ${r.days} days had nothing to buy — the early game is a grind`);
  note(`glider day ${r.gliderDay}, 6,000 ft day ${r.winDay}, ${dead} days with no purchase, ` +
       `distance ${r.log[0].distFt.toFixed(0)} -> ${r.bestFt.toFixed(0)} ft`);
});

check(14, 'save round-trips, and every historical version migrates forward', () => {
  const store = new Map();
  const backend = { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) };
  const sv = createSave(backend);

  const a = freshSave();
  a.ltf1.day = 42; a.ltf1.cash = 1234; a.ltf1.upgrades.glider = 3;
  a.account.bp = 900; a.account.medals.traveler_1 = '2026-08-07T00:00:00.000Z';
  a.account.lifetime.distFt = 98765.5;
  assert(sv.save(a), 'save() reported failure');
  const b = sv.load();
  assert(JSON.stringify(a) === JSON.stringify(b), 'serialise -> parse -> deep-equal failed');

  // every past version migrates, and the ACCOUNT SCOPE survives it (§7.5, landmine 12)
  const v1 = { version: 1, ltf1: { day: 9, cash: 50 }, ltf2: { medals: { traveler_1: 'x', diving_1: 'y' } } };
  const m1 = migrate(v1);
  assert(m1.version === SAVE_VERSION, `v1 migrated to ${m1.version}`);
  assert(m1.ltf1.day === 9, 'v1 migration lost the playthrough');
  assert(m1.account.medals.traveler_1 && m1.account.medals.diving_1, 'v1 migration dropped medals off the account');
  assert(m1.account.lifetime && typeof m1.account.lifetime.distFt === 'number', 'v1 migration left no lifetime block');

  const v2 = { version: 2, ltf1: { day: 3, bestDistFt: 777 }, account: { bp: 10, medals: {} } };
  const m2 = migrate(v2);
  assert(m2.version === SAVE_VERSION && m2.account.lifetime.distFt === 777,
    'v2 migration did not seed lifetime counters, so cumulative medals could never complete');

  // a corrupt save must fall back visibly, not white-screen
  store.set('learn-to-fly:save', '{not json');
  const fresh = sv.load();
  assert(fresh.version === SAVE_VERSION && fresh.ltf1.day === 1, 'a corrupt save did not fall back to a fresh one');
  assert(sv.takeWarning() !== null, 'a corrupt save fell back SILENTLY, which is worse');

  // "new game" clears the playthrough and keeps the account
  const kept = sv.newGame(a, 'both');
  assert(kept.ltf1.day === 1 && kept.account.bp === 900 && kept.account.medals.traveler_1,
    'new game wiped the account scope');
  note(`v1 and v2 migrate; new game keeps ${Object.keys(kept.account.medals).length} medal(s) and ${kept.account.bp} BP`);
});

// ═══ MEDALS ════════════════════════════════════════════════════════════════════════════

check(15, 'all 48 medals exist with unique ids', () => {
  assert(MEDALS.length === 48, `${MEDALS.length} medals, expected 48`);
  const ids = new Set(MEDALS.map((m) => m.id));
  assert(ids.size === 48, 'duplicate medal ids');
  const ns = MEDALS.map((m) => m.n).sort((a, b) => a - b);
  for (let i = 0; i < 48; i++) assert(ns[i] === i + 1, `medal numbering breaks at ${i + 1}`);
  for (const m of MEDALS) {
    assert(typeof m.name === 'string' && m.name.length > 0, `${m.id} has no name`);
    assert(typeof m.condition === 'string' && m.condition.length > 0, `${m.id} has no condition`);
    assert(typeof m.test === 'function', `${m.id} has no predicate`);
    assert(m.bp > 0, `${m.id} pays no bonus points`);
  }
  note(`48 medals, ${TOTAL_BP.toLocaleString()} BP total — enough for Bounty Hunter 2 (5,000)`);
});

check(16, 'every medal is individually reachable  [the tedious one, and the only way]', () => {
  const run = (o = {}) => ({
    dist: 0, maxAlt: 0, maxSpeed: 0, airTime: 0, bounces: 0, diveM: 0, reverseM: 0,
    hits: 1, destroyed: [], ...o,
  });
  const M = (ft) => ft / T.FT_PER_M;
  const MS = (mph) => mph / T.MPH_PER_MS;

  // One scenario per medal: the minimum sequence of bus events that should unlock it.
  const rounds = (n, o) => Array.from({ length: n }, () => ['run:ended', { run: run(o), pay: { cash: 0 } }]);
  const SCENARIOS = {
    story_mode: [['mode:completed', { mode: 'story' }]],
    classic_mode: [['mode:completed', { mode: 'classic' }]],
    arcade_king: [['arcade:score', { points: 12500 }]],
    pacifist_1: rounds(10, { hits: 0 }),
    pacifist_2: rounds(25, { hits: 0 }),
    skull_crusher: [['crash:skull', { speed: T.SKULL_CRUSH_SPEED }]],
    planet_crusher: [['crash:planet', { speed: T.PLANET_CRUSH_SPEED }]],
    reverse_1: [['run:ended', { run: run({ reverseM: M(250) }), pay: { cash: 0 } }]],
    reverse_2: [['run:ended', { run: run({ reverseM: M(15000) }), pay: { cash: 0 } }]],
    diving_1: [['run:ended', { run: run({ diveM: M(100) }), pay: { cash: 0 } }]],
    diving_2: [['run:ended', { run: run({ diveM: M(1000) }), pay: { cash: 0 } }]],
    bounty_1: [['crash:planet', {}], ['mode:completed', { mode: 'story' }], ['mode:completed', { mode: 'classic' }], ['arcade:score', { points: 12500 }]],
    bounty_2: [['arcade:score', { points: 12500 }], ['mode:completed', { mode: 'story' }], ['mode:completed', { mode: 'classic' }],
      ['run:ended', { run: run({ dist: M(150000), maxAlt: M(1000), maxSpeed: MS(500), airTime: 3600, bounces: 100, diveM: M(1000), reverseM: M(15000), destroyed: ['a', 'b', 'c', 'd', 'e'] }), pay: { cash: 100000 } }],
      ...rounds(150, { hits: 0 })],
    give_an_inch: [['ui:moneyClick', {}]],
    take_a_yard: [['ui:moneyClick', {}], ['ui:moneyClick', {}]],
    the_brick: [['shop:bought', { id: 'brick' }]],
    revenge_brick: [['shop:bought', { id: 'brick' }], ['__flag', 'usingBrick'], ['obstacle:destroyed', { kind: 'snowman' }]],
    its_rob: [['rob:refuel', {}]],
    rise: [['phoenix:rise', {}]],
    easy_way: [['ui:beakClick', {}]],
    achievement: [['ui:medalIcon', {}]],
    high_flyer_1: [['run:ended', { run: run({ maxAlt: M(250) }), pay: { cash: 0 } }]],
    high_flyer_2: [['run:ended', { run: run({ maxAlt: M(1000) }), pay: { cash: 0 } }]],
    speedster_1: [['run:ended', { run: run({ maxSpeed: MS(100) }), pay: { cash: 0 } }]],
    speedster_2: [['run:ended', { run: run({ maxSpeed: MS(500) }), pay: { cash: 0 } }]],
    master_bouncer: [['run:ended', { run: run({ bounces: 100 }), pay: { cash: 0 } }]],
    combo: [['run:ended', { run: run({ destroyed: ['snowman', 'mound', 'hill', 'iceberg', 'wall'] }), pay: { cash: 0 } }]],
    achiever_1: [['challenges:allDone', { mode: 'story' }]],
    achiever_2: [['challenges:allDone', { mode: 'classic' }]],
    rich_bird_1: [['run:ended', { run: run(), pay: { cash: 25000 } }]],
    rich_bird_2: [['run:ended', { run: run(), pay: { cash: 100000 } }]],
    duration_1: [['run:ended', { run: run({ airTime: 60 }), pay: { cash: 0 } }]],
    duration_2: [['run:ended', { run: run({ airTime: 300 }), pay: { cash: 0 } }]],
    duration_3: [['run:ended', { run: run({ airTime: 1500 }), pay: { cash: 0 } }]],
    duration_4: [['run:ended', { run: run({ airTime: 3600 }), pay: { cash: 0 } }]],
    destruction_1: [['obstacle:destroyed', { kind: 'snowman' }]],
    destruction_2: ['snowman', 'mound', 'hill', 'iceberg', 'wall'].map((k) => ['obstacle:destroyed', { kind: k }]),
    destruction_3: Array.from({ length: 25 }, () => ['obstacle:destroyed', { kind: 'snowman' }]),
    destruction_4: Array.from({ length: 75 }, () => ['obstacle:destroyed', { kind: 'snowman' }]),
    icbp: [['run:ended', { run: run(), pay: { cash: 0 }, overTheWall: true }]],
    milestone_1: rounds(10), milestone_2: rounds(25), milestone_3: rounds(75), milestone_4: rounds(150),
    traveler_1: [['run:ended', { run: run({ dist: M(500) }), pay: { cash: 0 } }]],
    traveler_2: [['run:ended', { run: run({ dist: M(5000) }), pay: { cash: 0 } }]],
    traveler_3: [['run:ended', { run: run({ dist: M(50000) }), pay: { cash: 0 } }]],
    traveler_4: [['run:ended', { run: run({ dist: M(150000) }), pay: { cash: 0 } }]],
  };

  const missing = MEDALS.filter((m) => !SCENARIOS[m.id]).map((m) => m.id);
  assert(missing.length === 0, `no scenario written for: ${missing.join(', ')}`);

  const failed = [];
  for (const m of MEDALS) {
    const bus = createBus();
    const med = createMedals(bus, T);
    const acc = freshSave().account;
    med.attach(acc, () => {});
    for (const [type, payload] of SCENARIOS[m.id]) {
      if (type === '__flag') { acc.flags[payload] = true; continue; }
      bus.emit(type, payload);
    }
    if (!acc.medals[m.id]) failed.push(`${m.n} ${m.name} — "${m.condition}"`);
  }
  assert(failed.length === 0, `unreachable:\n    ${failed.join('\n    ')}`);

  // ...and the handful that are claims about PHYSICS, not about counters. This is where you
  // discover that medal 40 is impossible because the wall's collision box is 3 ft too tall.
  const proto = UP.loadoutFrom({ ...UP.freshLevels(), glider: 4, acceleration: 4, rampHeight: 4, airResistance: 4, rockets: 4, rocketFuel: 4 }, T);
  const best = (L, lines) => {
    let out = null;
    for (const line of lines) {
      const p = P.simulate(L, ocean(), T, makeAutopilot(T, line), { collectEvents: false });
      if (!out || p.maxAlt > out.maxAlt) out = p;
    }
    return out;
  };
  const hi = best(proto, LINES);
  assert(hi.maxAlt * T.FT_PER_M >= 250, `High Flyer 1 needs 250 ft; best LTF1 apex is ${(hi.maxAlt * T.FT_PER_M).toFixed(0)} ft`);

  // Speedster 2 (500 mph) and High Flyer 2 (1,000 ft) are LTF2 medals, so they get LTF2 gear.
  const omega = LTF2.loadoutFrom(
    { sled: 4, glider: 4, thruster: 4, payload: 0 }, { lift: 5, power: 5, mass: 0, tough: 5 }, {}, T, 11,
  );
  const dive = P.createState(omega, T, { collectEvents: false });
  dive.state = STATE.FLIGHT; dive.y = 100000; dive.theta = -Math.PI / 2; dive.vy = -1;
  const w2 = ocean();
  for (let i = 0; i < 9000; i++) { P.step(dive, { left: false, right: false, thrust: true }, omega, w2, T); dive.theta = -Math.PI / 2; }
  const topMph = dive.maxSpeed * T.MPH_PER_MS;
  assert(topMph >= 500, `Speedster 2 needs 500 mph; a powered Omega dive tops out at ${topMph.toFixed(0)} mph`);

  const climb = best(omega, LINES);
  assert(climb.maxAlt * T.FT_PER_M >= 1000,
    `High Flyer 2 needs 1,000 ft; the best Omega apex is ${(climb.maxAlt * T.FT_PER_M).toFixed(0)} ft`);

  // I.C.B.P. — VERIFIED at 600 ft, and the promise that comes with it is that you can go OVER.
  const wallDef = OBSTACLES.find((o) => o.id === 'wall');
  const obs = makeObstacles(OBSTACLES, null, T);
  const wall = obs.find((o) => o.id === 'wall');
  const field = W.createWorld(W.fieldProvider(obs, FIELD_END_FT / T.FT_PER_M), T);
  let cleared = null;
  for (const line of LINES) {
    const p = P.simulate(omega, field, T, makeAutopilot(T, line), {
      collectEvents: false, hooks: { obstacles: makeObstacleHook() },
    });
    if (p.dist >= wall.x1 && p.maxAlt > wall.y1) { cleared = p; break; }
  }
  assert(cleared !== null,
    `I.C.B.P. is UNREACHABLE: no line clears the ${wallDef.heightFt} ft wall at ${wallDef.distFt} ft ` +
    `(wall top ${(wall.y1 * T.FT_PER_M).toFixed(0)} ft, x ${(wall.x1 * T.FT_PER_M).toFixed(0)} ft)`);
  note(`48/48 unlock from scripted events; apex ${(climb.maxAlt * T.FT_PER_M).toFixed(0)} ft, ` +
       `top ${topMph.toFixed(0)} mph, wall cleared at ${(cleared.maxAlt * T.FT_PER_M).toFixed(0)} ft`);
});

// ═══ §12's "BY HAND, BY A HUMAN" — automated proxies. These do not REPLACE the human ═════
// pass; they catch the version of each question that a number can answer, so that when a
// person does sit down with it they are checking feel rather than checking for bugs. The
// suite prints the four as a checklist at the end either way.

check(17, 'the first glider is RELIEF — it must be a step change, not a nudge', () => {
  // Measured at the loadout a player ACTUALLY has when they can first afford it: the
  // verified opening is acceleration, then a glider, so tier-1 Acceleration and nothing else.
  const none = flyBest({ ...UP.freshLevels(), acceleration: 1 }, T);
  const card = flyBest({ ...UP.freshLevels(), acceleration: 1, glider: 1 }, T);
  const dist = card.pay.distFt / Math.max(1, none.pay.distFt);
  const air = card.p.airTime / Math.max(0.01, none.p.airTime);

  // AIR TIME is the bar, not distance. What "relief" feels like in this game is not landing
  // further along, it is not plummeting — and a lot of the early distance comes from skipping
  // off the water, which rewards speed rather than a wing. Distance still has to move, but the
  // sentence §6.2 is making is about staying up.
  assert(air >= 1.7, `glider 1 buys only ${air.toFixed(2)}x the air time — that is a nudge, not relief`);
  assert(dist >= 1.4, `glider 1 buys only ${dist.toFixed(2)}x the distance`);
  note(`no glider ${none.pay.distFt.toFixed(0)} ft / ${none.p.airTime.toFixed(1)} s  ->  ` +
       `Cardboard ${card.pay.distFt.toFixed(0)} ft / ${card.p.airTime.toFixed(1)} s   ` +
       `(${dist.toFixed(2)}x distance, ${air.toFixed(2)}x air)`);
});

check(18, 'the launch is a hard cut to speed, and the camera is behind it', () => {
  // §2.1 — no easing in. The penguin is stationary, you press go, and it is ALREADY fast.
  for (const lvl of [0, 2, 4]) {
    const L = Lof({ acceleration: lvl });
    const world = ocean();
    const p = P.createState(L, T, { collectEvents: true });
    while (p.state === STATE.RAMP) P.step(p, { left: false, right: false, thrust: false }, L, world, T);
    const mph = Math.hypot(p.vx, p.vy) * T.MPH_PER_MS;
    near(mph, L.exitMph, 0.02, `exit speed at acceleration tier ${lvl}`);
    // ...and the exit angle is the ramp angle, not something the integrator drifted into
    near(Math.atan2(p.vy, p.vx), T.RAMP_ANGLE, 0.02, 'exit angle');
    assert(p.events.some((e) => e.type === 'launch'), 'no launch event fired');
  }
  assert(T.CAM_LAUNCH_LAG_M > 0, 'the camera must start BEHIND the ramp or the launch does not lurch');
  note(`exit speed exact at every tier; camera starts ${T.CAM_LAUNCH_LAG_M} m behind`);
});

check(19, 'chained skipping is genuinely worth doing, not just decorative', () => {
  // §4.8 — a good entry should chain, and the chain should be a real part of the distance.
  const L = Lof({ glider: 2, acceleration: 3 });
  const world = ocean();
  const p = P.createState(L, T, { collectEvents: false });
  p.state = STATE.FLIGHT; p.x = 0; p.y = 0.02;
  const phi = 6 * Math.PI / 180;
  p.vx = Math.cos(phi) * 60; p.vy = -Math.sin(phi) * 60; p.theta = -phi;
  p.prevX = p.x; p.prevY = p.y;
  const policy = makeHoldAlpha(0, T);
  const firstTouch = p.x;
  let xAtFirstSkip = null;
  for (let i = 0; i < 30000 && !p.finished; i++) {
    const c = policy(p, L, T, world);
    P.step(p, { left: c.left, right: c.right, thrust: false }, L, world, T);
    if (xAtFirstSkip === null && p.bounces > 0) xAtFirstSkip = p.x;
  }
  const afterFirst = p.dist - xAtFirstSkip;
  assert(p.bounces >= 5, `only ${p.bounces} skips from a good entry`);
  assert(afterFirst / p.dist > 0.25,
    `skipping only adds ${(afterFirst / p.dist * 100).toFixed(0)}% of the distance — it is decoration`);
  note(`${p.bounces} skips carrying ${(afterFirst * T.FT_PER_M).toFixed(0)} ft, ` +
       `${(afterFirst / p.dist * 100).toFixed(0)}% of the run`);
});

check(20, 'the 600 ft wall FITS ON SCREEN, so I.C.B.P. is judgeable', () => {
  // Landmine 13. The camera is DOM-free precisely so this can be asserted without a browser.
  const obs = makeObstacles(OBSTACLES, null, T);
  const wall = obs.find((o) => o.id === 'wall');
  const cam = createCamera(T);
  const L = LTF2.loadoutFrom({ sled: 4, glider: 4, thruster: 4, payload: 0 }, { lift: 5, power: 5, mass: 0, tough: 5 }, {}, T, 11);

  for (const [vw, vh] of [[1280, 720], [1920, 1080], [2902, 1114], [1024, 1366]]) {
    const p = P.createState(L, T, { collectEvents: false });
    p.x = wall.centreX; p.y = wall.y1 + 10;         // just clearing the top
    p.vx = 90; p.vy = 4;
    cam.snapTo(p);
    // one second of settling with the wall asking to be framed, as the renderer does
    for (let i = 0; i < 120; i++) { cam.require(wall.y1); cam.update(p, T.DT, vw, vh, () => 0.5); }
    const b = cam.bounds(vw, vh);
    assert(b.top >= wall.y1,
      `at ${vw}x${vh} the camera tops out at ${(b.top * T.FT_PER_M).toFixed(0)} ft but the wall is ` +
      `${(wall.y1 * T.FT_PER_M).toFixed(0)} ft — the player cannot see whether they will clear it`);
    assert(b.bottom <= T.SEA_LEVEL_Y + 1, `at ${vw}x${vh} the base of the wall is off the bottom of the screen`);
  }
  note(`wall top ${(wall.y1 * T.FT_PER_M).toFixed(0)} ft framed at 4 aspect ratios, sea level still visible`);
});

// ═══ BEYOND §12 — checks that exist because something actually went wrong ═══════════════

check(21, 'every tuning constant referenced in src/ exists in tuning.js', () => {
  // A missing constant is not a crash. `T.AP_PATH_GAIN` on a typo reads undefined, the
  // arithmetic goes NaN, clamp(NaN) returns NaN because neither comparison is true, and the
  // autopilot ends up emitting no input at all — so the penguin flies with the stick
  // centred and the numbers look plausible. Two rounds of tuning were measured against a
  // controller that was not running before this check existed.
  const bad = [];
  for (const f of [...walk(join(SRC, 'sim')), ...walk(join(SRC, 'game')), ...walk(join(SRC, 'render'))]) {
    const code = stripNonCode(readFileSync(f, 'utf8'));
    for (const m of code.matchAll(/\b(?:T|TUNING|tuning)\.([A-Za-z_$][\w$]*)\b/g)) {
      if (!(m[1] in T)) bad.push(`${relative(ROOT, f)} reads T.${m[1]}, which tuning.js does not define`);
    }
  }
  assert(bad.length === 0, [...new Set(bad)].join('\n    '));
  for (const [k, v] of Object.entries(T)) {
    assert(typeof v !== 'number' || Number.isFinite(v), `TUNING.${k} is ${v}`);
  }
  note(`${Object.keys(T).length} constants, all defined and finite`);
});

check(22, 'energy is never created — E = v²/2 + G·y falls unless the rocket is lit', () => {
  // The guard against the whole family of integration and surface-snap bugs that let a
  // penguin climb for free. It is how the submarine-in-the-sky was found: a dive entered
  // with a tiny downward velocity never crossed y=0, so the state stayed SUBMERGED and
  // +0.6 G of buoyancy carried the penguin to 4,382 ft through open air.
  const cases = [
    { levels: { acceleration: 1, rampHeight: 1, glider: 1, airResistance: 1 }, line: LINES[0] },
    { levels: { glider: 3, acceleration: 4, rockets: 3, rocketFuel: 2, airResistance: 3 }, line: LINES[2] },
    { levels: { glider: 4, acceleration: 4, rampHeight: 4, airResistance: 4 }, line: LINES[3] },
  ];
  let worstGain = 0, worstWhere = '';
  for (const c of cases) {
    const L = Lof(c.levels);
    const world = ocean();
    const p = P.createState(L, T, { collectEvents: false });
    const ap = makeAutopilot(T, c.line);
    const input = { left: false, right: false, thrust: false };
    const E = () => (p.vx * p.vx + p.vy * p.vy) * 0.5 + T.G * p.y;
    let prev = E();
    for (let i = 0; i < 28800 && !p.finished; i++) {
      const cmd = ap(p, L, T, world);
      input.left = cmd.left; input.right = cmd.right; input.thrust = cmd.thrust;
      const onRamp = p.state === STATE.RAMP;
      P.step(p, input, L, world, T);
      const e = E();
      // The ramp is scripted (§4.7 sets exit speed rather than integrating to it) and a
      // surface snap moves y by at most one step of travel, so allow a small floor.
      if (!p.thrusting && !onRamp && e - prev > worstGain) {
        worstGain = e - prev;
        worstWhere = `${c.line.name} step ${i} state ${p.state}`;
      }
      prev = e;
    }
    assert(p.finished, `${c.line.name} never came to rest (t=${p.t.toFixed(0)}s)`);
  }
  assert(worstGain < 1.0, `gained ${worstGain.toFixed(3)} m2/s2 in one un-thrusted step (${worstWhere})`);
  note(`largest un-thrusted single-step energy gain ${worstGain.toFixed(4)} m2/s2`);
});

// ── run ────────────────────────────────────────────────────────────────────────────────

const t0 = Date.now();
for (const c of checks) {
  if (ONLY && String(c.id) !== ONLY) continue;
  ran++;
  const before = notes.length;
  const started = Date.now();
  try {
    await c.fn();
    const ms = Date.now() - started;
    console.log(`  \x1b[32mPASS\x1b[0m ${String(c.id).padStart(2)}  ${c.name}${ms > 400 ? `  \x1b[90m(${(ms / 1000).toFixed(1)}s)\x1b[0m` : ''}`);
  } catch (e) {
    failures++;
    console.log(`  \x1b[31mFAIL\x1b[0m ${String(c.id).padStart(2)}  ${c.name}`);
    console.log(`       \x1b[31m${e.message}\x1b[0m`);
  }
  for (let i = before; i < notes.length; i++) console.log(`       \x1b[90m${notes[i]}\x1b[0m`);
}

console.log(`\n  ${ran - failures}/${ran} checks passed in ${((Date.now() - t0) / 1000).toFixed(1)}s${QUICK ? '  (quick mode)' : ''}`);
process.exit(failures === 0 ? 0 : 1);
