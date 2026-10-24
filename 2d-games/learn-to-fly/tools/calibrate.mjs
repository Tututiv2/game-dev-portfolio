// node tools/calibrate.mjs [--sweep] [--targets=reference/targets.json]
//
// §11. Two jobs, one file.
//
//   --sweep   Coordinate descent over the pacing constants until §12.13's two gates hold:
//             a glider by day 3, and 6,000 ft in 16-25 days. Prints a table you paste into
//             tuning.js and ltf1.upgrades.js, plus the line for CALIBRATION.md.
//
//   default   If reference/targets.json exists (numbers read off the real SWF or off frame-
//             stepped footage — see reference/README.md), fly the same six calibration points
//             in the remake and print the delta against each §11 tolerance.
//
// It does not need to be clever. The point is that tuning is measurement rather than vibes.

import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { TUNING as BASE } from '../src/data/tuning.js';
import { UPGRADES } from '../src/data/ltf1.upgrades.js';
import { playThrough, flyBest } from '../src/game/progression.js';
import { loadoutFrom, freshLevels } from '../src/data/ltf1.upgrades.js';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

// ── the knobs, and what each one is FOR ────────────────────────────────────────────────
// rate   scales DIST_RATE and ALT_RATE together — moves the whole income curve up or down,
//        which is what sets whether day 3 can afford a 500-dollar glider.
// steep  price[tier] *= steep^(tier-1) — leaves every tier-1 price alone (so the opening is
//        untouched) and stretches the back half, which is what sets the day the goal falls.
const KNOBS = {
  rate: { lo: 0.30, hi: 4.0, value: 1.0 },
  steep: { lo: 1.0, hi: 6.0, value: 1.0 },
};

// `pivot` is the first tier the steepening touches. Everything below it keeps its price.
// It exists because stretching from tier 2 buys the extra days out of the EARLY game, where
// they land as three identical 189 ft days in a row with nothing to buy — which passes both
// gates and is still the wrong game (§17.15).
const PIVOT = Number((process.argv.find((s) => s.startsWith('--pivot=')) || '--pivot=2').slice(8));

function tableWith(steep) {
  const out = {};
  for (const [k, cat] of Object.entries(UPGRADES)) {
    out[k] = {
      ...cat,
      tiers: cat.tiers.map((t, i) => ({
        ...t,
        price: i === 0 ? 0 : Math.round(t.price * steep ** Math.max(0, i - PIVOT)),
      })),
    };
  }
  return out;
}

function tuningWith(rate) {
  return { ...BASE, DIST_RATE: BASE.DIST_RATE * rate, ALT_RATE: BASE.ALT_RATE * rate };
}

function evaluate(rate, steep) {
  const T = tuningWith(rate);
  const table = tableWith(steep);
  const r = playThrough(T, { table, maxDays: 60 });
  // cost: distance outside each gate, in days
  const g = r.gliderDay === null ? 60 : r.gliderDay;
  const w = r.winDay === null ? 60 : r.winDay;
  const gliderErr = Math.max(0, g - 3);
  const winErr = w < 16 ? 16 - w : w > 25 ? w - 25 : 0;
  return { r, cost: gliderErr * 3 + winErr, gliderDay: r.gliderDay, winDay: r.winDay };
}

if (process.argv.includes('--sweep')) {
  console.log('\n  coordinate descent — gates: glider by day 3, 6,000 ft in 16-25 days\n');
  // The two knobs are LEXICOGRAPHIC, not independent, and running a joint descent over them
  // finds worse answers than solving them in order: `steep` leaves every tier-1 price alone,
  // so it cannot move the glider gate at all, while `rate` moves BOTH gates. So pin the
  // glider gate with `rate` first — taking the LEANEST income that still clears it, since a
  // richer day 1 makes the whole early game mushy — and then stretch the back half with
  // `steep`, which is guaranteed not to undo it.

  // pass 1 — smallest `rate` that still puts a glider in the player's hands by day 3
  let lo = KNOBS.rate.lo, hi = KNOBS.rate.hi;
  for (let i = 0; i < 16; i++) {
    const mid = (lo + hi) * 0.5;
    const g = evaluate(mid, 1.0).gliderDay;
    if (g !== null && g <= 3) hi = mid; else lo = mid;
  }
  KNOBS.rate.value = Math.ceil(hi * 200) / 200;    // round up to a clean 0.005 step
  console.log(`  rate  ${KNOBS.rate.value.toFixed(3)}  ->  glider day ${evaluate(KNOBS.rate.value, 1.0).gliderDay}`);

  // pass 2 — `steep` until 6,000 ft lands in the middle of the 16-25 window
  lo = KNOBS.steep.lo; hi = KNOBS.steep.hi;
  for (let i = 0; i < 16; i++) {
    const mid = (lo + hi) * 0.5;
    const w = evaluate(KNOBS.rate.value, mid).winDay;
    if (w === null || w > 20) hi = mid; else lo = mid;
  }
  KNOBS.steep.value = Math.round(lo * 1000) / 1000;
  const best = evaluate(KNOBS.rate.value, KNOBS.steep.value);
  console.log(`  steep ${KNOBS.steep.value.toFixed(3)}  ->  glider ${best.gliderDay}  win ${best.winDay}  cost ${best.cost}`);

  const { rate, steep } = { rate: KNOBS.rate.value, steep: KNOBS.steep.value };
  const T = tuningWith(rate);
  console.log(`\n  RESULT   DIST_RATE ${T.DIST_RATE.toFixed(3)}   ALT_RATE ${T.ALT_RATE.toFixed(3)}   price steepening ${steep.toFixed(3)}`);
  console.log('\n  prices to paste into src/data/ltf1.upgrades.js:');
  const table = tableWith(steep);
  for (const [k, cat] of Object.entries(table)) {
    console.log(`    ${cat.name.padEnd(15)} ${cat.tiers.map((t) => String(t.price).padStart(7)).join(' ')}`);
  }
  const r = playThrough(T, { table, maxDays: 60 });
  console.log(`\n  glider day ${r.gliderDay}   win day ${r.winDay}   best ${r.bestFt.toFixed(0)} ft\n`);
  process.exit(0);
}

// ── §11 comparison against recorded originals ─────────────────────────────────────────

const POINTS = [
  { name: 'fresh save', levels: {} },
  { name: 'glider 1', levels: { glider: 1 } },
  { name: 'glider 2 + accel 2', levels: { glider: 2, acceleration: 2 } },
  { name: 'glider 3 + accel 3 + rockets 2', levels: { glider: 3, acceleration: 3, rockets: 2, rocketFuel: 2 } },
  { name: 'all tier 4', levels: { glider: 3, acceleration: 3, rockets: 3, rocketFuel: 3, rampHeight: 3, airResistance: 3 } },
  { name: 'all tier 5', levels: { glider: 4, acceleration: 4, rockets: 4, rocketFuel: 4, rampHeight: 4, airResistance: 4 } },
];

const TOL = {
  exitMph: 0.03, apexFt: 0.05, distFt: 0.05, airTimeS: 0.08, cash: 0.10, rotSeconds: 0.10,
};

const targetsPath = join(ROOT, 'reference', 'targets.json');
const targets = existsSync(targetsPath) ? JSON.parse(readFileSync(targetsPath, 'utf8')) : null;

console.log('\n  §11 calibration points — remake measurements' + (targets ? ' vs recorded originals' : ' (no reference/targets.json yet)'));
console.log('  ' + '-'.repeat(78));
console.log('  point                              exit mph   apex ft   dist ft   air s     cash');
for (const pt of POINTS) {
  const levels = { ...freshLevels(), ...pt.levels };
  const L = loadoutFrom(levels, BASE);
  const best = flyBest(levels, BASE);
  const row = {
    exitMph: L.exitMph,
    apexFt: best.pay.altFt,
    distFt: best.pay.distFt,
    airTimeS: best.p.airTime,
    cash: best.pay.cash,
  };
  const cells = [row.exitMph.toFixed(0), row.apexFt.toFixed(0), row.distFt.toFixed(0),
    row.airTimeS.toFixed(1), '$' + row.cash.toLocaleString()];
  console.log(`  ${pt.name.padEnd(33)} ${cells.map((c, i) => c.padStart([8, 9, 9, 7, 8][i])).join(' ')}`);
  if (targets && targets[pt.name]) {
    for (const [k, tol] of Object.entries(TOL)) {
      const want = targets[pt.name][k];
      if (want === undefined) continue;
      const got = row[k];
      const err = Math.abs(got - want) / Math.abs(want);
      console.log(`      ${k.padEnd(10)} original ${String(want).padStart(9)}  remake ${String(got.toFixed(1)).padStart(9)}  ` +
        `${(err * 100).toFixed(1)}%  ${err <= tol ? 'OK' : `OUT (tol ${(tol * 100).toFixed(0)}%)`}`);
    }
  }
}
// The rotation metric is a pure property of ROT_RATE, so it is reported once, not per point.
console.log(`\n  full nose-up to full nose-down: ${(Math.PI / BASE.ROT_RATE).toFixed(3)} s  (ROT_RATE ${(BASE.ROT_RATE * 180 / Math.PI).toFixed(0)} deg/s)`);
console.log('\n  Record originals in reference/targets.json to turn this table into a pass/fail.\n');
