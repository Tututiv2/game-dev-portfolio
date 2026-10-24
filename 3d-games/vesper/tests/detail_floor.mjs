/* ============================================================================
   THE DETAIL FLOOR — measured, not asserted.

   CLAUDE.md has been claiming a "~4 km" geometry floor. That number was never
   measured; it was reasoned from the finest octave's frequency. Trap 8 says
   read it back instead of reasoning about it, so this reads it back.

   METHOD — AND WHY IT IS THE SECOND DIFFERENCE.

   The obvious measurement is: step d metres along a great circle, look at how
   much the height changed. That was the first version of this file and it is
   USELESS, because it is dominated by the local tilt. These worlds average a
   0.37 gradient, so at d = 0.25 m the height changes by ~9 cm no matter what,
   and a detail layer with tens of metres of amplitude over kilometres moves
   that number by less than a percent. The instrument could not see the very
   thing it was built to see — an instrument that cannot resolve a class of
   input will confidently exonerate it (DECISIONS, trap 8).

   So: sample THREE points, a - m - b, evenly spaced d apart along a great
   circle, and take h(a) - 2h(m) + h(b). A tilted plane gives exactly zero, at
   any tilt. What survives is curvature — structure at scale d and nothing
   else, which is the definition of detail.

   HOW TO READ THE OUTPUT. For a fractal surface the curvature rms falls
   smoothly as d shrinks, roughly as d^H, and never reaches zero: there is
   always another octave. When the field runs out of octaves the curvature
   COLLAPSES — orders of magnitude in one step — because below the finest
   octave the surface really is a plane. The floor is where that collapse
   begins.

   No GPU. This is the CPU height field, which is what collision and the mesh
   both read, so it is the honest measure of what you can stand on.
   ========================================================================= */

import { makePlanetDef } from '../src/world/planetDef.js';
import { groundR } from '../src/world/heightField.js';

const SEEDS = Number(process.argv.find(a => a.startsWith('--seeds='))?.slice(8) || 6);
const SAMPLES = Number(process.argv.find(a => a.startsWith('--n='))?.slice(4) || 400);
const ARCH = process.argv.find(a => a.startsWith('--arch='))?.slice(7) || null;

/* deterministic sampling: a fixed LCG, so two runs of this harness compare */
let _s = 12345;
const rnd = () => ((_s = (_s * 1664525 + 1013904223) >>> 0) / 4294967296);

function randDir() {
  // uniform on the sphere
  const z = rnd() * 2 - 1, t = rnd() * Math.PI * 2, r = Math.sqrt(1 - z * z);
  return [r * Math.cos(t), r * Math.sin(t), z];
}

/** the two points `ang` either side of `n` along one randomly-oriented great
 *  circle. Collinear by construction, which is what the second difference needs. */
function straddle(n, ang) {
  const up = Math.abs(n[1]) < 0.985 ? [0, 1, 0] : [1, 0, 0];
  let tx = [up[1] * n[2] - up[2] * n[1], up[2] * n[0] - up[0] * n[2], up[0] * n[1] - up[1] * n[0]];
  const il = 1 / Math.hypot(...tx); tx = tx.map(v => v * il);
  const ty = [n[1] * tx[2] - n[2] * tx[1], n[2] * tx[0] - n[0] * tx[2], n[0] * tx[1] - n[1] * tx[0]];
  // a random bearing in the tangent plane, so we are not always probing one axis
  const a = rnd() * Math.PI * 2;
  const dx = tx.map((v, i) => v * Math.cos(a) + ty[i] * Math.sin(a));
  const rot = (s) => {
    const c = Math.cos(s), sn = Math.sin(s);
    const o = [n[0] * c + dx[0] * sn, n[1] * c + dx[1] * sn, n[2] * c + dx[2] * sn];
    const inv = 1 / Math.hypot(...o);
    return [o[0] * inv, o[1] * inv, o[2] * inv];
  };
  return { a: rot(-ang), b: rot(ang) };
}

console.log('\n=== detail floor: how much does the ground change over D metres ===\n');

// The distances that matter, from a footprint to a mountain range.
const DISTS = [0.25, 1, 4, 16, 64, 256, 1024, 4096, 16384, 65536];

const totals = new Map(DISTS.map(d => [d, { s2: 0, n: 0 }]));
const FLOORS = [];

for (let k = 0; k < SEEDS; k++) {
  const def = makePlanetDef(1000 + k * 7717, ARCH);
  if (def.art.isGas) continue;
  const R = def.radius;

  const rows = [];
  for (const D of DISTS) {
    const ang = D / R;                       // metres of arc -> radians
    let s2 = 0, n = 0;
    for (let i = 0; i < SAMPLES; i++) {
      // a - m - b, evenly spaced along ONE great circle. Stepping from the
      // middle in both directions along the same tangent is what makes the
      // three collinear; three independent steps would measure something else.
      const m = randDir();
      const { a, b } = straddle(m, ang);
      const curv = groundR(def, a) - 2 * groundR(def, m) + groundR(def, b);
      s2 += curv * curv; n++;
    }
    const rms = Math.sqrt(s2 / n);
    rows.push({ D, rms });
    const t = totals.get(D); t.s2 += s2; t.n += n;
  }

  const terr = def.art.terrace > 0 ? def.art.terrace * def.relief : 0;
  const micro = def.art.microTerrace || 0;
  console.log(`  seed ${def.seed}  ${def.name}  R=${(R / 1000).toFixed(0)} km  relief=${(def.relief / 1000).toFixed(1)} km` +
              (terr ? `  MACRO TERRACE ${terr.toFixed(0)} m` : '') +
              (micro ? `  micro-terrace ${micro} m` : ''));

  /* THE DISCRIMINATOR. Successive distances here differ by 4x, so:
       - a surface with real octaves at scale d is fractal, rms ~ d^H with
         H around 0.8, so each 4x step multiplies curvature by 4^0.8 ~ 3
       - a surface that has run out of octaves is locally SMOOTH, and the
         second difference of a smooth function is h''*d^2, so each 4x step
         multiplies curvature by 16
     A ratio near 3 means detail. A ratio near 16 means you are measuring the
     curvature of the big shape through an empty band. Halfway is 8. */
  let prevRms = null, floor = null, stillBelow = true;
  for (const { D, rms } of rows) {
    const ratio = prevRms === null || prevRms === 0 ? null : rms / prevRms;
    const empty = ratio === null || ratio > 8;
    // Only the CONTIGUOUS empty run from the bottom counts. Once real octaves
    // appear the floor is fixed; a later smooth band is a gap in the spectrum,
    // not the floor, and letting it move the answer reports the wrong number.
    if (empty && stillBelow) floor = D; else stillBelow = false;
    console.log(
      `    ${String(D).padStart(6)} m   curvature rms ${rms.toExponential(3).padStart(11)} m` +
      (ratio === null ? '' : `   x${ratio.toFixed(2)}`) +
      (ratio === null ? '' : (empty ? '   <- smooth, no octaves here' : '   detail')));
    prevRms = rms;
  }
  FLOORS.push({ name: def.name, seed: def.seed, floor, terr, micro });
  console.log('');
}

/* Pooling ACROSS archetypes is not meaningful — a terraced world's step
   swamps a smooth world's octaves and the average describes neither. The
   per-world floor is the honest number. */
console.log('  ---- the floor, per world ----');
console.log('  (the finest scale at which the ground still has structure; below');
console.log('   it the surface is smooth and there is nothing left to see)\n');
let worst = 0;
for (const f of FLOORS) {
  const v = f.floor ?? Infinity;
  worst = Math.max(worst, Number.isFinite(v) ? v : 0);
  console.log(`    ${f.name.padEnd(12)} seed ${String(f.seed).padEnd(7)} ` +
              `floor ${f.floor === null ? '> 65536' : String(f.floor).padStart(6)} m` +
              (f.terr ? `   (macro terrace ${f.terr.toFixed(0)} m)` : '') +
              (f.micro ? `   (micro-terrace ${f.micro} m — plates ARE flat, so a coarse floor here is correct)` : ''));
}
console.log(`\n  WORST FLOOR: ${worst} m\n`);

