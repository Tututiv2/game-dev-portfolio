/* ============================================================================
   WHERE DOES THE FRAME ACTUALLY GO?

   Turns one layer off at a time and re-times, on the real GPU. This is the
   ablate.mjs idea applied to COST instead of to looks, and it exists because
   the recorded figure for "the planet is too slow" turned out to have been
   measured through the screenshot harness, which forces a full framebuffer
   copy every frame and roughly halves everything.

   Do not optimise a layer until this says it is the one.

     node tests/attrib.mjs
     node tests/attrib.mjs --w=2560 --h=1440 --dist=1.9
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const W = +(argv.w || 1920), H = +(argv.h || 1080);
const DIST = +(argv.dist || 1.9);

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

await page.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href,
  { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__V && window.__V.frames >= 3', { timeout: 180000 });

console.log(`\n  ${await page.evaluate(() => window.__V.gpu.name)}`);
console.log(`  ${W}x${H}  seed=${argv.seed || 'VESPER-1'} arch=${argv.arch || 'volcanic'}` +
            `  dist=${DIST}\n`);

await page.evaluate((s, a, d) => {
  window.__V.setWorld(s, a);
  window.__V.setCam(d, 0.55, 0.22);
}, argv.seed || 'VESPER-1', argv.arch || 'volcanic', DIST);

/* Hide layers by name. The backdrop is found by walking the scene, because the
   viewer only publishes the planet. */
await page.evaluate(() => {
  const V = window.__V;
  V.__layers = () => {
    const p = V.planet;
    const back = [];
    V.scene.traverse(o => { if (o !== p.group && !p.group.children.includes(o) && o.isMesh || o.isPoints) {
      if (!p.group.children.includes(o)) back.push(o);
    }});
    return {
      surface: [p.mesh], clouds: p.clouds ? [p.clouds] : [],
      rings: p.rings ? [p.rings] : [], atmo: p.atmo ? [p.atmo] : [],
      backdrop: back,
    };
  };
  V.__show = (only) => {
    const L = V.__layers();
    for (const [k, arr] of Object.entries(L)) {
      for (const o of arr) o.visible = only === null ? true : (only !== k);
    }
  };
  V.__bloom = (on) => {
    const passes = V.view.composer.passes;
    for (const p of passes) if (p.constructor.name === 'UnrealBloomPass') p.enabled = on;
  };
});

async function time(label) {
  await new Promise(r => setTimeout(r, 600));
  const r = await page.evaluate(() => new Promise(res => {
    const t0 = performance.now(), f0 = window.__V.frames;
    const wait = () => {
      const dt = performance.now() - t0, df = window.__V.frames - f0;
      if (df >= 40 || dt > 8000) res({ fps: df * 1000 / dt, ms: dt / Math.max(df, 1) });
      else requestAnimationFrame(wait);
    };
    requestAnimationFrame(wait);
  }));
  return { label, ...r };
}

const rows = [];
await page.evaluate(() => { window.__V.__show(null); window.__V.__bloom(true); });
const base = await time('everything');
rows.push(base);

for (const layer of ['surface', 'clouds', 'rings', 'atmo', 'backdrop']) {
  await page.evaluate(l => window.__V.__show(l), layer);
  rows.push(await time('without ' + layer));
}
await page.evaluate(() => window.__V.__show(null));
await page.evaluate(() => window.__V.__bloom(false));
rows.push(await time('without bloom'));

for (const r of rows) {
  const saved = r.label === 'everything' ? '' :
    `   costs ${(base.ms - r.ms).toFixed(1)} ms`;
  console.log(`  ${r.label.padEnd(18)}${r.fps.toFixed(1).padStart(7)} fps  ` +
              `${r.ms.toFixed(1).padStart(6)} ms${saved}`);
}
console.log('');
await browser.close();
