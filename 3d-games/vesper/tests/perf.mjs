/* ============================================================================
   FRAME COST, ON THE REAL GPU. The before/after number.

   closeup.mjs also prints fps, but it runs with ?pdb=1 (preserveDrawingBuffer)
   so it can read the canvas back, and that costs real frames. This one measures
   the path the owner actually runs: no readback, no flags, hardware ANGLE.

   Measures a WINDOW of frames rather than trusting the on-screen readout, so a
   single slow frame during a world switch cannot flatter or ruin the number.

     node tests/perf.mjs
     node tests/perf.mjs --arch=glacier --seed=VESPER-9
     node tests/perf.mjs --w=1920 --h=1080
     node tests/perf.mjs --analytic     force the OLD per-pixel path (A/B)
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));

const W = +(argv.w || 1280), H = +(argv.h || 900);
const SEED = argv.seed || 'VESPER-1';
const ARCH = argv.arch || 'volcanic';

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist',
         `--window-size=${W},${H}`],
  defaultViewport: { width: W, height: H },
  protocolTimeout: 600000,
});
const page = await browser.newPage();
page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
page.on('console', m => { if (m.type() === 'error') console.log('  ERR: ' + m.text().slice(0, 400)); });

const url = pathToFileURL(path.join(ROOT, 'VESPER.html')).href
  + (argv.analytic ? '?analytic=1' : '');
await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__V && window.__V.frames >= 3', { timeout: 180000 });

const gpu = await page.evaluate(() => window.__V.gpu.name);
console.log(`\n  ${gpu}`);
console.log(`  ${W}x${H}   seed=${SEED} arch=${ARCH}` +
            (argv.analytic ? '   [ANALYTIC — old per-pixel path]' : '   [baked]'));

/* How much of the frame the planet covers, so "1 fps" has a cause attached. */
const DISTS = [['far', 3.15], ['mid', 1.90], ['close', 1.22], ['grazing', 1.045]];

const bakeMs = await page.evaluate((s, a) => {
  const t0 = performance.now();
  window.__V.setWorld(s, a);
  return +(performance.now() - t0).toFixed(1);
}, SEED, ARCH);
console.log(`  world build + bake: ${bakeMs} ms\n`);

for (const [name, dist] of DISTS) {
  await page.evaluate(d => window.__V.setCam(d, 0.55, 0.22), dist);
  // let it settle, then time a fixed window of frames
  await new Promise(r => setTimeout(r, 700));
  const r = await page.evaluate(() => new Promise(res => {
    const t0 = performance.now(), f0 = window.__V.frames;
    const wait = () => {
      const dt = performance.now() - t0;
      const df = window.__V.frames - f0;
      if (df >= 40 || dt > 8000) res({ fps: df * 1000 / dt, frames: df, ms: dt });
      else requestAnimationFrame(wait);
    };
    requestAnimationFrame(wait);
  }));
  const ms = r.ms / Math.max(r.frames, 1);
  console.log(`  ${name.padEnd(8)} dist=${String(dist).padEnd(6)}` +
              `${r.fps.toFixed(1).padStart(7)} fps   ${ms.toFixed(1).padStart(6)} ms/frame`);
}
console.log('');
await browser.close();
