/* CAN A COCKPIT AND A PLANET SHARE ONE FRAME?

   Wave 0's gate, and the one thing that could have sunk B2. In orbit, orbit.js
   clamps the camera's near plane to radius*0.05 — 15.5 km on a 310 km world —
   because the far plane is radius*22 and a 1 m near plane would be a 6,800,000:1
   depth ratio that z-fights the planet to pieces. A cockpit canopy sits about a
   metre from the eye, i.e. thirty thousand times inside that near plane.

   view.js now renders in depth SLICES. This proves it, with a control:

     ?nearprobe=2   a 25 cm cube at 0.5 m, in the FAR scene  -> must be INVISIBLE
     ?nearprobe=1   the same cube, in view.nearScene         -> must be VISIBLE

   Two renders that differ in one thing. Showing only the second would prove a
   cube can be drawn, not that the slices are what draws it — and this project
   has been burned three times by an instrument that could not fail.

   It also reads the slice boundaries back out of the running page rather than
   recomputing them here, and asserts the depth ratio is actually sane. A split
   at the wrong place still renders; it just z-fights, and that does not show up
   reliably in a still.

   node tests/depthslice.mjs
*/
import puppeteer from 'puppeteer-core';
import fs from 'fs'; import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'shots', 'ab');
fs.mkdirSync(OUT, { recursive: true });

let pass = 0, fail = 0;
const ok = (c, n, d = '') => { if (c) { pass++; console.log(`  PASS  ${n}  ${d}`); }
                               else { fail++; console.log(`  FAIL  ${n}  ${d}`); } };

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 900, height: 900 }, protocolTimeout: 300000 });

console.log('\n  DEPTH SLICES — a cockpit and a planet in one frame\n');

/* How much of the frame is the probe's magenta? Counting a NAMED colour rather
   than "did the frame change" means a difference cannot be scored by clouds
   drifting or the glow pulsing. */
async function shoot(mode) {
  const p = await b.newPage();
  p.on('pageerror', e => console.log('   PAGEERROR ' + e.message));
  await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
    `?pdb=1&time=9&spin=0&seed=VESPER-1&arch=glacier&nearprobe=${mode}`,
    { waitUntil: 'domcontentloaded' });
  await p.waitForFunction('window.__V && window.__V.frames>20', { timeout: 180000 });
  if (await p.evaluate(() => window.__V.renderer.getContext().isContextLost())) {
    console.log('  ABORT: context lost'); await b.close(); process.exit(1);
  }
  await new Promise(r => setTimeout(r, 1200));
  const r = await p.evaluate(() => {
    const cv = document.getElementById('cv');
    const t = document.createElement('canvas');
    t.width = 300; t.height = 300;
    const g = t.getContext('2d');
    g.drawImage(cv, 0, 0, cv.width, cv.height, 0, 0, 300, 300);
    const px = g.getImageData(0, 0, 300, 300).data;
    let probe = 0;
    for (let i = 0; i < px.length; i += 4) {
      // the probe is 0xff3bd0 through ACES: strongly red+blue, weak green
      if (px[i] > 120 && px[i + 2] > 90 && px[i + 1] < px[i] * 0.75) probe++;
    }
    return { probe, total: 90000, slices: window.__V.slices,
             near: window.__V.planet ? undefined : undefined,
             camNear: window.__V.camera.near,
             png: cv.toDataURL('image/png') };
  });
  fs.writeFileSync(path.join(OUT, `nearprobe-${mode}.png`),
    Buffer.from(r.png.split(',')[1], 'base64'));
  delete r.png;
  await p.close();
  return r;
}

const far = await shoot(2);
const nearR = await shoot(1);

console.log(`   far camera near plane: ${far.camNear.toFixed(0)} m`);
if (nearR.slices) {
  const s = nearR.slices;
  console.log(`   slices: ${s.nearest} -> ${s.split.toFixed(2)} -> ${s.outer.toFixed(0)} m` +
              `   worst ratio ${s.ratio.toFixed(0)}:1`);
}

ok(far.camNear > 1000, 'in orbit the far camera really is clamped far out',
   `${(far.camNear / 1000).toFixed(1)} km`);
ok(far.probe === 0, 'a 0.5 m object in the FAR scene is invisible (the control)',
   `${far.probe} px`);
ok(nearR.probe > 200, 'the SAME object in nearScene is drawn',
   `${nearR.probe} px of ${nearR.total}`);
ok(!!nearR.slices, 'the page reports its slice boundaries');
if (nearR.slices) {
  const s = nearR.slices;
  ok(s.ratio < 5000, 'both slices carry a sane depth ratio', `${s.ratio.toFixed(0)}:1`);
  ok(Math.abs(s.split - Math.sqrt(s.nearest * s.outer)) < 1e-6,
     'the split is the geometric mean, so the ratios are equal', `${s.split.toFixed(2)} m`);
  ok(Math.abs(s.outer - far.camNear) < 1e-6 || s.outer > 1000,
     'the near slices end exactly where the far camera begins — no gap');
}

await b.close();
console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
