/* ============================================================================
   WHAT SHAPE IS THE TREE?

   The quadtree has two numbers that decide whether the whole design works, and
   they pull against each other:

     LEAF COUNT     — every leaf is a chunk, and a chunk is 7.1 ms of
                      height-field evaluation on the main thread. 200 leaves is
                      1.4 s of streaming; 2000 is unusable.
     VERTEX SPACING — how far apart two vertices are directly under the camera.
                      This is the real resolution of the ground you stand on,
                      and it has to get below the finest thing the height field
                      carries (3.5 m) or the detail layer is invisible again.

   SPLIT_K trades one for the other, and guessing it from the formula is how you
   end up with either a slideshow or a smooth ramp. So this prints both, at
   every altitude from orbit to standing, without a GPU or a browser.

     node tests/quadtree.mjs
     node tests/quadtree.mjs --k=0.7
   ========================================================================= */

import * as T from 'three';
import { makePlanetDef } from '../src/world/planetDef.js';
import { groundR } from '../src/world/heightField.js';
import { Terrain, faceDir, dirToFace } from '../src/world/terrain.js';

const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));

const def = makePlanetDef(argv.seed || 'VESPER-1', argv.arch || 'glacier');
const R = def.radius;

/* A ShaderMaterial can be constructed without a GL context — nothing is
   compiled until something is drawn — so the whole tree can be exercised in
   node. Uniforms are shared with the planet in the real thing; here they only
   have to exist. */
const terr = new Terrain(def, {}, argv.k ? { splitK: Number(argv.k) } : {});

console.log(`\n  ${def.name}  R=${(R / 1000).toFixed(0)} km  relief=${(def.relief / 1000).toFixed(1)} km` +
            `  splitK=${terr.splitK}  maxDepth=${terr.maxDepth}\n`);
console.log('     eye above ground     leaves   deepest   vertex at foot   horizon   chunk build');
console.log('     ------------------------------------------------------------------------------');

// a direction with ordinary terrain under it
const dir0 = (() => {
  const d = [0, 0, 0];
  faceDir(4, 0.13, -0.07, d);
  return d;
})();

const ALTS = [200000, 30000, 8000, 2000, 500, 120, 30, 2];
let worst = 0;
for (const alt of ALTS) {
  const leaves = [];
  terr._collect(dir0, alt, leaves);

  // the leaf directly under the camera decides the resolution you experience
  const { f, u, v } = dirToFace(dir0);
  let under = null;
  for (const l of leaves) {
    if (l.f !== f) continue;
    if (u >= l.u0 && u < l.u0 + l.size && v >= l.v0 && v < l.v0 + l.size) { under = l; break; }
  }
  const spacing = under ? under.edge / 32 : NaN;
  const deepest = leaves.length ? Math.max(...leaves.map(l => l.depth)) : -1;
  const horizon = Math.sqrt(Math.max(0, (R + alt) * (R + alt) - R * R));
  worst = Math.max(worst, leaves.length);

  console.log(
    `     ${(alt >= 1000 ? (alt / 1000).toFixed(0) + ' km' : alt + ' m').padStart(8)}` +
    `        ${String(leaves.length).padStart(6)}` +
    `    ${String(deepest).padStart(2)}` +
    `      ${(spacing < 1000 ? spacing.toFixed(2) + ' m' : (spacing / 1000).toFixed(1) + ' km').padStart(10)}` +
    `   ${(horizon / 1000).toFixed(1).padStart(7)} km` +
    `   ${(leaves.length * 7.1 / 1000).toFixed(2).padStart(6)} s`);
}

console.log('');
console.log(`  worst leaf count: ${worst}  (~${(worst * 7.1 / 1000).toFixed(1)} s of main thread to populate,`);
console.log(`   streamed at ${terr.budget}/frame = ${Math.ceil(worst / terr.budget)} frames)`);

/* The single-LOD grid this replaced covered 5 chunks across at ONE depth. At
   the depth that resolves metre detail that was ~300 m against an 8.6 km
   horizon — the number this whole rewrite exists to fix. */
const groundLeaves = [];
terr._collect(dir0, 120, groundLeaves);
const near = groundLeaves.filter(l => l.dist < 2000).length;
console.log('');
console.log(`  standing at 120 m: ${groundLeaves.length} leaves reach the horizon,`);
console.log(`   ${near} of them within 2 km. The old 5x5 grid had 25, total, covering 0.3 km.`);
console.log('');

if (!Number.isFinite(worst) || worst === 0) { console.log('  FAIL: the tree produced no leaves'); process.exit(1); }
console.log('  ok\n');
