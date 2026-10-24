/* ============================================================================
   IS THE GROUND YOU SEE THE GROUND YOU STAND ON?

   Binding rule 3, applied to the terrain mesh. Every chunk vertex is generated
   from groundR() — the same function collision answers with — so this test
   takes the vertices back OUT of the finished BufferGeometry, undoes the
   chunk-local transform, and asks groundR() again.

   It is checking three things at once, and the third is the one that bites:
     1. the mesh really is the height field, not something near it
     2. the chunk-local origin trick did not lose precision
     3. the cube-sphere parameterisation round-trips — faceDir and dirToFace
        have to be exact inverses or the grid centres on the wrong place and
        the ground quietly appears somewhere other than under you

   Tolerance is in METRES, because that is what a player experiences. Vertices
   are float32 at planetary magnitude, so centimetres are the floor.

     node tests/terrain_conformance.mjs
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', userDataDir: path.join(os.tmpdir(), 'vesper-terrconf-profile'),
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
  defaultViewport: { width: 900, height: 700 }, protocolTimeout: 900000,
});
const page = await browser.newPage();
page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
page.on('console', m => { if (m.type() === 'error') console.log('  ERR ' + m.text().slice(0, 400)); });
await page.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href,
  { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__V && window.__V.frames >= 5', { timeout: 300000 });

const WORLDS = [
  ['VESPER-1', 'glacier'], ['VESPER-1', 'volcanic'],
  ['ASHFALL', 'crystal'], ['NORTHWIND', 'tundra'],
];

console.log('\n  terrain mesh vs the CPU height field\n');
console.log('    world                 chunks   verts    mean err   worst err');

let fails = 0;
for (const [seed, arch] of WORLDS) {
  const r = await page.evaluate(async (seed, arch) => {
    const V = window.__V;
    V.setWorld(seed, arch);
    V.setCam(1.0, 0.55, 0.05);
    for (let i = 0; i < 4; i++) await new Promise(r => requestAnimationFrame(r));

    /* STAND ON IT, do not hover at a fixed multiple of the radius.
       `dist` multiplies the DATUM, and the ground is routinely below the datum
       (-911 m on VESPER-1 glacier), so ?dist=1.002 leaves the eye 5.7 km up.
       That matters here and not just for screenshots: the tree only subdivides
       to the depth the altitude calls for, so from 5.7 km the finest leaf is
       depth 9 with a 19 m detail filter on it, and comparing THAT against the
       unfiltered field collision uses is measuring the low-pass, not a defect.
       Binding rule 3 is a promise about the ground under the player. Put the
       camera where the player would be before asking whether it holds. */
    const P0 = V.planet, d0 = V.def;
    const c0 = P0.group.worldToLocal(V.camera.position.clone());
    const R0 = Math.hypot(c0.x, c0.y, c0.z);
    const gR0 = V.groundR(d0, [c0.x / R0, c0.y / R0, c0.z / R0]);
    V.setCam((gR0 + 2) / d0.radius, null, null);

    // let the chunk budget stream them in
    for (let i = 0; i < 400; i++) await new Promise(r => requestAnimationFrame(r));

    const P = window.__V.planet;
    if (!P.terrain || P.terrain.chunks.size === 0) return { none: true };
    const def = window.__V.def;

    let n = 0, sum = 0, worst = 0, worstAt = null;
    let nF = 0, sumF = 0, worstF = 0;
    let big = 0, onStep = 0, bigF = 0, onStepF = 0;
    const d = [0, 0, 0];
    const RES = 32, G = RES + 3;
    const deepest = Math.max(...[...P.terrain.chunks.values()].map(c => c.depth));

    for (const [, c] of P.terrain.chunks) {
      const pos = c.mesh.geometry.getAttribute('position');
      const dir = c.mesh.geometry.getAttribute('aDir');
      const o = c.mesh.position;
      // Each chunk asked the height field to stop at twice its vertex spacing,
      // so it must be compared against THAT field, not against the unfiltered
      // one. Anything else is measuring the low-pass filter and calling it an
      // error. See detail.js.
      const edge = P.terrain.edgeAt(c.depth);
      const minWL = (edge / RES) * 2;

      // every 7th vertex — a full sweep is 27k height evaluations per world
      for (let i = 0; i < pos.count; i += 7) {
        // SKIP THE SKIRT. The outer ring of the rendered grid is deliberately
        // NOT on the surface — it hangs straight down to plug LOD cracks — so
        // including it would report the skirt depth as a conformance failure.
        const gi = i % G, gj = (i / G) | 0;
        if (gi === 0 || gj === 0 || gi === G - 1 || gj === G - 1) continue;

        const x = pos.getX(i) + o.x, y = pos.getY(i) + o.y, z = pos.getZ(i) + o.z;
        d[0] = dir.getX(i); d[1] = dir.getY(i); d[2] = dir.getZ(i);
        // the vertex must lie at groundR() ALONG ITS OWN DIRECTION
        const rMesh = Math.hypot(x, y, z);
        const e = Math.abs(rMesh - window.__V.groundR(def, d, minWL));
        sum += e; n++;
        if (e > worst) { worst = e; worstAt = [d[0], d[1], d[2]]; }
        /* On a TERRACED world a vertex sitting on a riser is genuinely
           ambiguous: aDir is stored as float32, so re-evaluating the field here
           uses a direction that differs from the one that built the vertex in
           the last few bits, and a quantised surface turns that epsilon into a
           whole step. Count those separately and check they really are whole
           steps — that is what tells "the mesh is a staircase" apart from "the
           mesh is wrong". */
        if (e > 0.5) {
          big++;
          const stp = def.art.microTerrace;
          if (stp > 0 && Math.abs(e / stp - Math.round(e / stp)) < 0.02) onStep++;
        }

        /* AND THE ONE THAT ACTUALLY MATTERS FOR BINDING RULE 3.
           Collision reads groundR with NO filter. At the finest LOD — which is
           where the player is standing — the mesh must equal that, or what you
           stand on is not what you see. Coarse chunks are allowed to be the
           smoothed version; the ground under your feet is not. */
        if (c.depth === deepest) {
          const eF = Math.abs(rMesh - window.__V.groundR(def, d));
          sumF += eF; nF++;
          if (eF > worstF) worstF = eF;
          if (eF > 0.5) {
            bigF++;
            const stp = def.art.microTerrace;
            if (stp > 0 && Math.abs(eF / stp - Math.round(eF / stp)) < 0.02) onStepF++;
          }
        }
      }
    }
    return { chunks: P.terrain.chunks.size, n, mean: sum / n, worst, worstAt, big, onStep, bigF, onStepF,
             step: def.art.microTerrace,
             deepest, nF, meanF: nF ? sumF / nF : 0, worstF,
             radius: def.radius, relief: def.relief };
  }, seed, arch);

  if (r.none) { console.log(`    ${(seed + ' ' + arch).padEnd(22)} no chunks generated`); fails++; continue; }
  // float32 at 850 km resolves about 0.06 m, and these are sums of three of
  // them, so a few centimetres is the floor rather than a defect.
  // The finest-LOD check is the strict one: that is the ground the player is
  // standing on and it is compared against the unfiltered field collision uses.
  /* A terraced world is ALLOWED to disagree by whole steps on plate edges, and
     only there: those vertices sit exactly on a riser, where the surface really
     is discontinuous and float32 aDir decides which side. What is NOT allowed is
     a disagreement that is not a whole step, or more than a sliver of them. */
  const stepOnly  = r.step > 0 && r.big  === r.onStep  && r.big  / r.n  < 0.02;
  const stepOnlyF = r.step > 0 && r.bigF === r.onStepF && r.bigF / Math.max(1, r.nF) < 0.02;
  const ok = r.mean < 0.25 && (r.worst < 2.0 || stepOnly) && (r.worstF < 2.0 || stepOnlyF);
  if (!ok) fails++;
  console.log(`    ${(seed + ' ' + arch).padEnd(22)}${String(r.chunks).padStart(5)}` +
    `${String(r.n).padStart(9)}   ${r.mean.toFixed(3).padStart(7)} m` +
    `   ${r.worst.toFixed(3).padStart(7)} m   ${ok ? 'ok' : '*** BAD ***'}`);
  console.log(`      finest LOD (depth ${r.deepest}) vs UNFILTERED groundR — what collision reads:` +
    `  ${r.nF} verts, mean ${r.meanF.toFixed(3)} m, worst ${r.worstF.toFixed(3)} m`);
  if (r.step > 0) console.log(`      micro-terrace ${r.step} m: ${r.big} of ${r.n} verts off by >0.5 m, ` +
    `${r.onStep} of those are whole steps (riser vertices, float32 aDir picks a side);` +
    `  finest LOD ${r.bigF}/${r.onStepF}`);
}

console.log(fails
  ? `\n  ${fails} world(s) FAILED — the mesh is not the height field\n`
  : '\n  the ground you see is the ground you stand on\n');
await browser.close();
process.exit(fails ? 1 : 0);
