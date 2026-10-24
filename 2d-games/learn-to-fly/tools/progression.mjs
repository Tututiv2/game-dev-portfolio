// node tools/progression.mjs [--days=40] [--keep]
//
// Plays a fresh save day by day with src/game/progression.js and prints the table §12.13 is
// an assertion about. Run it after touching a price, a cash rate, or the flight model — the
// two gates are "a glider by day 3" and "6,000 ft by day 16-25", and this is where you see
// WHICH day moved and by how much rather than just that a check went red.

import { TUNING as BASE } from '../src/data/tuning.js';
import { UPGRADES } from '../src/data/ltf1.upgrades.js';
import { playThrough } from '../src/game/progression.js';

const arg = (k, d) => {
  const a = process.argv.find((s) => s.startsWith(`--${k}=`));
  return a ? Number(a.slice(k.length + 3)) : d;
};

// --rate / --steep mirror tools/calibrate.mjs's two knobs so a candidate can be SEEN as a
// day-by-day curve before it is baked into the data files. Two gates passing is necessary;
// a curve that grinds for fifteen days and then wins in one is not the same game.
const rate = arg('rate', 1);
const steep = arg('steep', 1);
const T = { ...BASE, DIST_RATE: BASE.DIST_RATE * rate, ALT_RATE: BASE.ALT_RATE * rate };
const table = {};
for (const [k, cat] of Object.entries(UPGRADES)) {
  table[k] = { ...cat, tiers: cat.tiers.map((t, i) => ({ ...t, price: i === 0 ? 0 : Math.round(t.price * steep ** (i - 1)) })) };
}

const t0 = Date.now();
const r = playThrough(T, { table, maxDays: arg('days', 40), keepPlaying: process.argv.includes('--keep') });

const pad = (s, n) => String(s).padStart(n);
console.log('\n  day    dist ft   alt ft   mult    line     earned      cash   bought');
console.log('  ' + '-'.repeat(84));
for (const d of r.log) {
  console.log(
    `  ${pad(d.day, 3)}  ${pad(d.distFt.toFixed(0), 9)} ${pad(d.altFt.toFixed(0), 8)}  ` +
    `${d.mult.toFixed(2)}  ${pad(d.line, 6)}  ${pad('$' + d.earned.toLocaleString(), 9)} ` +
    `${pad('$' + d.cash.toLocaleString(), 9)}   ${d.bought.join(', ')}`);
}
console.log('  ' + '-'.repeat(84));
console.log(`  first glider: day ${r.gliderDay ?? '—'}      6,000 ft: day ${r.winDay ?? 'NEVER'}      best ${r.bestFt.toFixed(0)} ft`);
console.log(`  gates: glider by day 3 ${r.gliderDay !== null && r.gliderDay <= 3 ? 'OK' : 'MISS'}   ` +
            `win in 16-25 ${r.winDay !== null && r.winDay >= 16 && r.winDay <= 25 ? 'OK' : 'MISS'}   ` +
            `(${((Date.now() - t0) / 1000).toFixed(1)}s)\n`);
