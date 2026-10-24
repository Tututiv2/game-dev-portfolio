/* ============================================================================
   CAN YOU WALK ON IT?

   The height field was authored for the ORBITAL view, where a world is a disc a
   few hundred pixels across and craggy reads as dramatic. Standing on it, the
   same numbers mean something else entirely: measured at 3.7 m vertex spacing
   the ground averages a 0.68 gradient — a 34 degree slope — and peaks past 8.0,
   which is an 83 degree wall. A player cannot walk up any of that.

   This prints the distribution rather than an average, because the average is
   not the thing that matters. What matters is the FRACTION of ground you can
   stand on, and whether the steep parts are concentrated into readable features
   (cliffs, plate risers, canyon walls) or smeared everywhere.

   It is also the before/after for micro-terraces: quantising elevation converts
   a uniform slope into flat plates separated by risers, which should move mass
   from the middle of this histogram out to both ends.

     node tests/walkable.mjs
     node tests/walkable.mjs --arch=glacier --n=4000
   ========================================================================= */

import { makePlanetDef } from '../src/world/planetDef.js';
import { groundR } from '../src/world/heightField.js';

const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));

const N = Number(argv.n || 3000);
const SPACING = Number(argv.spacing || 2);   // metres, ~ standing-LOD vertex gap

let _s = 4242;
const rnd = () => ((_s = (_s * 1664525 + 1013904223) >>> 0) / 4294967296);

/** gradient magnitude at a direction, over `SPACING` metres of true arc */
function gradientAt(def, u) {
  const R = def.radius, e = SPACING / R;
  const up = Math.abs(u[1]) < 0.985 ? [0, 1, 0] : [1, 0, 0];
  let tx = [up[1] * u[2] - up[2] * u[1], up[2] * u[0] - up[0] * u[2], up[0] * u[1] - up[1] * u[0]];
  const il = 1 / Math.hypot(...tx); tx = tx.map(v => v * il);
  const ty = [u[1] * tx[2] - u[2] * tx[1], u[2] * tx[0] - u[0] * tx[2], u[0] * tx[1] - u[1] * tx[0]];
  const st = (d, a) => {
    const o = [u[0] + d[0] * a, u[1] + d[1] * a, u[2] + d[2] * a];
    const L = 1 / Math.hypot(...o);
    return [o[0] * L, o[1] * L, o[2] * L];
  };
  const gx = (groundR(def, st(tx, e)) - groundR(def, st(tx, -e))) / (2 * SPACING);
  const gy = (groundR(def, st(ty, e)) - groundR(def, st(ty, -e))) / (2 * SPACING);
  return Math.hypot(gx, gy);
}

/* The bands that matter to a person on foot. 30 degrees is roughly the limit of
   comfortable walking; 45 is a scramble; past 60 you need the terrain to be
   offering you a route rather than a wall. */
const BANDS = [
  ['flat      <10', 0, Math.tan(10 * Math.PI / 180)],
  ['walkable  <30', Math.tan(10 * Math.PI / 180), Math.tan(30 * Math.PI / 180)],
  ['scramble  <45', Math.tan(30 * Math.PI / 180), Math.tan(45 * Math.PI / 180)],
  ['steep     <60', Math.tan(45 * Math.PI / 180), Math.tan(60 * Math.PI / 180)],
  ['cliff     >60', Math.tan(60 * Math.PI / 180), Infinity],
];

const ARCHES = argv.arch ? [argv.arch]
  : ['glacier', 'crystal', 'shattered', 'tundra', 'volcanic', 'oceanic'];

console.log(`\n  gradient over ${SPACING} m of ground, ${N} samples per world\n`);
console.log('    world        step   ' + BANDS.map(b => b[0].split(' ')[0].padStart(9)).join('') + '     mean');
console.log('    ' + '-'.repeat(78));

for (const arch of ARCHES) {
  const def = makePlanetDef(argv.seed || 'VESPER-1', arch);
  if (def.art.isGas) continue;
  const counts = BANDS.map(() => 0);
  let sum = 0, n = 0;
  for (let i = 0; i < N; i++) {
    const z = rnd() * 2 - 1, t = rnd() * Math.PI * 2, r = Math.sqrt(1 - z * z);
    const u = [r * Math.cos(t), r * Math.sin(t), z];
    // dry land only — an ocean is flat by definition and would flatter the score
    if (def.art.hasSea && groundR(def, u) <= def.radius + def.art.seaLevel * def.relief + 0.01) continue;
    const g = gradientAt(def, u);
    sum += g; n++;
    for (let b = 0; b < BANDS.length; b++) {
      if (g >= BANDS[b][1] && g < BANDS[b][2]) { counts[b]++; break; }
    }
  }
  if (!n) continue;
  const pct = counts.map(c => ((c / n) * 100).toFixed(1).padStart(8) + '%');
  console.log(`    ${arch.padEnd(11)} ${String(def.art.microTerrace || 0).padStart(3)} m ` +
              pct.join('') + `   ${(sum / n).toFixed(2).padStart(6)}`);
}

console.log('');
console.log('  A world where "walkable or flatter" is under about half is a world the');
console.log('  player fights rather than crosses. Terraces move mass OUT of the middle:');
console.log('  more flat plate, more vertical riser, less uniform 34-degree slog.');
console.log('');
