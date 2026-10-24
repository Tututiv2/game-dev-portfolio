/* ============================================================================
   VERIFY TREES.html.

   Opens it exactly the way the owner does — file://, double-click, no flags a
   double-click would not give you — on the REAL GPU, and proves it draws.
   Then shoots the gallery in every mode a judgement will be made in, so the
   pictures exist before anything is called finished.

     node tests/trees_verify.mjs             real GPU (default)
     node tests/trees_verify.mjs --soft      SwiftShader, for a machine with no GPU
     node tests/trees_verify.mjs --show      a real visible window
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const FILE = path.join(ROOT, 'TREES.html');
const SHOTS = path.join(ROOT, 'shots', 'trees');
const SOFT = process.argv.includes('--soft');
const SHOW = process.argv.includes('--show');
const VP = { width: 1600, height: 950 };

fs.mkdirSync(SHOTS, { recursive: true });

let fails = 0;
const check = (ok, msg, extra = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}${extra ? '   ' + extra : ''}`);
  if (!ok) fails++;
};

console.log('\n=== TREES.html — one-click verification ===\n');
check(fs.existsSync(FILE), 'TREES.html exists');
if (!fs.existsSync(FILE)) process.exit(1);
const html = fs.readFileSync(FILE, 'utf8');
check(!/type="module"/.test(html), 'no ES module script (chrome blocks those on file://)');
check(!/<script[^>]+\bsrc=/.test(html), 'no external script reference');
// an external RESOURCE, not any http:// text — three's own source comments are
// full of github links and the first version of this check flagged those.
check(!/\b(?:src|href)\s*=\s*["']https?:/i.test(html), 'nothing is loaded from the network');
check(html.length > 400000, 'three is inlined', `${(html.length / 1024).toFixed(0)} KB`);

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: SHOW ? false : 'new',
  args: SOFT
    ? ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox']
    : ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist',
       `--window-size=${VP.width},${VP.height}`],
  defaultViewport: SHOW ? null : VP,
  protocolTimeout: 300000,
});
const page = await browser.newPage();
const errors = [], warns = [], failedReqs = [];
page.on('console', m => {
  if (m.type() === 'error') errors.push(m.text());
  else if (m.type() === 'warning') warns.push(m.text());
});
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
page.on('requestfailed', r => failedReqs.push(r.url()));

await page.goto(pathToFileURL(FILE).href, { waitUntil: 'domcontentloaded', timeout: 60000 });

let booted = true;
try { await page.waitForFunction('!!window.__T && window.__T.frames >= 8', { timeout: 120000 }); }
catch { booted = false; }
await new Promise(r => setTimeout(r, 900));

/* This suite is about the ELEVEN HAND-BUILT candidates, and the page now opens
   on BREED (each world's own trees) — so say which shelf we are testing rather
   than depending on whatever the default happens to be. */
await page.evaluate(() => window.__T && window.__T.mode && window.__T.mode('originals'));
await new Promise(r => setTimeout(r, 700));
const fatal = await page.evaluate(() => window.__T && window.__T.fatal).catch(() => 'no __T');
check(booted && !fatal, 'the page boots and renders frames', fatal ? 'FATAL: ' + fatal : '');
if (fatal) { console.log('\n  ' + fatal + '\n'); }

/* the GPU must be alive and it must not be a software renderer we did not ask
   for — a dead or software context is how this project has been fooled before */
const gpu = await page.evaluate(() => {
  const c = document.createElement('canvas').getContext('webgl2');
  const d = c.getExtension('WEBGL_debug_renderer_info');
  return { r: d ? c.getParameter(d.UNMASKED_RENDERER_WEBGL) : c.getParameter(c.RENDERER),
           lost: c.isContextLost() };
}).catch(() => ({ r: '?', lost: true }));
check(!gpu.lost, 'webgl context alive', String(gpu.r).slice(0, 74));
if (!SOFT) check(!/swiftshader|software/i.test(gpu.r), 'running on real hardware', String(gpu.r).slice(0, 60));

/* ---- the shipping contract, measured in the page ------------------------- */
const trees = await page.evaluate(() => window.__T.trees);
check(trees.length === 11, 'eleven candidates built', `${trees.length}`);
console.log('');
let overBudget = 0, offSpec = 0;
for (const t of trees) {
  const yOK = Math.abs(t.minY) < 1e-4 && Math.abs(t.maxY - 1) < 1e-4;
  const rOK = Math.abs(t.maxR - 1) < 0.02;
  const bOK = t.tris <= 400;
  if (!bOK) overBudget++;
  if (!(yOK && rOK)) offSpec++;
  console.log(`    ${String(t.n).padStart(2, '0')}  ${t.name.padEnd(14)} ` +
    `${String(t.tris).padStart(4)} tris  ${String(t.verts).padStart(5)} verts  ` +
    `y ${t.minY.toFixed(3)}-${t.maxY.toFixed(3)}  r ${t.maxR.toFixed(3)}  ` +
    `${bOK && yOK && rOK ? 'ok' : '<-- OFF SPEC'}`);
}
console.log('');
check(overBudget === 0, 'every tree is inside the 400-triangle ceiling');
check(offSpec === 0, 'every tree is normalised: base y=0, tip y=1, max r=1');
const mean = trees.reduce((a, t) => a + t.tris, 0) / trees.length;
console.log(`    mean ${mean.toFixed(0)} tris, max ${Math.max(...trees.map(t => t.tris))}\n`);

/* ---- pixels ------------------------------------------------------------- */
let lastB64 = null;
/* HOW MANY PIXELS ACTUALLY CHANGED between two frames. Brightness percentages
   could not tell 1:1 from rBase 0.16 apart — 89.2% vs 88.1% — because the frame
   is mostly ground and sky either way, and the thing that changed was only the
   trees. A summary statistic that is dominated by the background cannot measure
   a change in the foreground. */
async function diffPct(a, b) {
  return page.evaluate(async (x, y) => {
    const load = async (s) => { const i = new Image(); i.src = 'data:image/png;base64,' + s; await i.decode(); return i; };
    const g = document.createElement('canvas'); g.width = 400; g.height = 240;
    const c = g.getContext('2d');
    c.drawImage(await load(x), 0, 0, 400, 240);
    const A = c.getImageData(0, 0, 400, 240).data;
    c.clearRect(0, 0, 400, 240);
    c.drawImage(await load(y), 0, 0, 400, 240);
    const B = c.getImageData(0, 0, 400, 240).data;
    let n = 0;
    for (let i = 0; i < A.length; i += 4) {
      if (Math.abs(A[i] - B[i]) + Math.abs(A[i + 1] - B[i + 1]) + Math.abs(A[i + 2] - B[i + 2]) > 24) n++;
    }
    return n / (A.length / 4) * 100;
  }, a, b);
}
async function shoot(name) {
  const b64 = await page.screenshot({ encoding: 'base64' });
  lastB64 = b64;
  fs.writeFileSync(path.join(SHOTS, name + '.png'), Buffer.from(b64, 'base64'));
  return page.evaluate(async (s) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + s;
    await img.decode();
    const g = document.createElement('canvas');
    g.width = 400; g.height = 240;
    const x = g.getContext('2d');
    x.drawImage(img, 0, 0, 400, 240);
    const d = x.getImageData(0, 0, 400, 240).data;
    let sum = 0, bright = 0, blown = 0;
    const seen = new Set();
    for (let i = 0; i < d.length; i += 4) {
      const v = (d[i] + d[i + 1] + d[i + 2]) / 3;
      sum += v; if (v > 40) bright++; if (v > 248) blown++;
      seen.add((d[i] >> 2 << 12) | (d[i + 1] >> 2 << 6) | (d[i + 2] >> 2));
    }
    const n = d.length / 4;
    return { avg: sum / n, brightPct: bright / n * 100, blownPct: blown / n * 100, cols: seen.size };
  }, b64);
}
const modes = [];
async function mode(name, set) {
  await page.evaluate((s) => { window.__T.set(s); window.__T.snap(); }, set);
  await new Promise(r => setTimeout(r, 500));
  const m = await shoot(name);
  modes.push([name, m]);
  return m;
}

const night = await mode('01-night-12m', { night: true, size: 1, zoom: -1, bg: 2 });
check(night.brightPct > 4, 'night grid is not a black frame',
  `${night.brightPct.toFixed(1)}% lit, avg ${night.avg.toFixed(1)}, ${night.cols} colours`);
check(night.blownPct < 3, 'nothing has bloomed out to white', `${night.blownPct.toFixed(2)}% blown`);
/* AND IT IS LEGIBLE, not merely non-black. Bark on this palette paints from
   `hi` (#33254f, near black); against a near-black sky a trunk-heavy tree was
   black on black and could not be judged at all. The old default measured a
   mean of 12.9 and passed the "not a black frame" check above quite happily,
   which is why that check alone was not enough. */
check(night.avg > 30, 'and the night frame is bright enough to read a dark trunk against',
  `mean ${night.avg.toFixed(1)}`);

const day = await mode('02-day-12m', { night: false });
check(day.brightPct > 20 && day.avg > 25, 'day grid is lit',
  `${day.brightPct.toFixed(1)}% lit, avg ${day.avg.toFixed(1)}, ${day.cols} colours`);
check(day.blownPct < 4, 'daylight does not blow out', `${day.blownPct.toFixed(2)}% blown`);
check(Math.abs(day.avg - night.avg) > 8, 'day and night are actually different frames',
  `avg ${night.avg.toFixed(1)} -> ${day.avg.toFixed(1)}`);

/* the backdrop dial has to move the BACKGROUND without lifting the trees with
   it — raising exposure would raise both and the contrast would not change */
await mode('02a-night-backdrop-dark', { night: true, bg: 0 });
const bgDark = lastB64;
const bgLit = await mode('02b-night-backdrop-light', { bg: 2 });
const dBg = await diffPct(bgDark, lastB64);
check(dBg > 25, 'the backdrop dial really lifts the background',
  `${dBg.toFixed(0)}% of pixels differ, mean ${bgLit.avg.toFixed(1)}`);
await page.evaluate(() => window.__T.set({ night: false, bg: 2 }));

await mode('03-day-3m', { size: 0 });
await mode('04-day-30m', { size: 2 });
const back12 = await mode('05-night-30m', { night: true, size: 2 });
check(back12.brightPct > 3, '30 m night frame draws', `${back12.brightPct.toFixed(1)}% lit`);

/* the width control has to actually squeeze the instance matrix — rBase is how
   the game will really draw these, so a dead button here would hide it */
await mode('05a-day-width-1to1', { night: false, size: 1, wide: 0 });
const wideShot = lastB64;
await mode('05b-day-width-narrow', { wide: 2 });
const narrowShot = lastB64;
await mode('05c-day-width-broad', { wide: 1 });
const dWide = await diffPct(wideShot, narrowShot);
check(dWide > 4, 'the width (rBase) control really squeezes the instance matrix',
  `${dWide.toFixed(1)}% of pixels differ between 1:1 and 0.16`);
await page.evaluate(() => window.__T.set({ wide: 0 }));

/* ---- rotation actually moves the silhouette ----------------------------- */
await page.evaluate(() => { window.__T.set({ night: false, size: 1, spin: false }); window.__T.spinTo(0); });
await new Promise(r => setTimeout(r, 400));
await shoot('06-day-yaw0');
const yaw0 = lastB64;
await page.evaluate(() => window.__T.spinTo(Math.PI / 2));
await new Promise(r => setTimeout(r, 400));
await shoot('07-day-yaw90');
const dYaw = await diffPct(yaw0, lastB64);
check(dYaw > 3, 'rotating changes the picture (the silhouettes really turn)',
  `${dYaw.toFixed(1)}% of pixels differ at 0 vs 90 degrees`);

/* ---- aPart earns its place --------------------------------------------- */
await page.evaluate(() => { window.__T.set({ old: false }); });
await new Promise(r => setTimeout(r, 300));
const withPart = await shoot('08-day-aPart-on');
const onShot = lastB64;
await page.evaluate(() => window.__T.set({ old: true }));
await new Promise(r => setTimeout(r, 300));
const noPart = await shoot('09-day-aPart-off');
const dPart = await diffPct(onShot, lastB64);
check(dPart > 4, 'the aPart attribute changes what is drawn',
  `${dPart.toFixed(1)}% of pixels differ; colours ${noPart.cols} -> ${withPart.cols}`);
await page.evaluate(() => window.__T.set({ old: false, spin: true }));

/* ---- click to enlarge, through a real mouse event ----------------------- */
await page.evaluate(() => { window.__T.set({ night: true, size: 1, zoom: -1 }); window.__T.snap(); });
await new Promise(r => setTimeout(r, 400));
// ask the page where pod 4 (PRISM) actually is, rather than guessing at pixels
const box = await page.evaluate(() => {
  const p = window.__T.podScreen(4);
  return { x: p[0], y: p[1] };
});
await page.mouse.click(box.x, box.y);
await new Promise(r => setTimeout(r, 1200));
const z = await page.evaluate(() => window.__T.state.zoom);
check(z === 4, 'clicking a tree blows it up', `zoom = ${z}`);
await shoot('10-zoom-04-prism');
await page.mouse.click(30, VP.height - 30);
await new Promise(r => setTimeout(r, 700));
check(await page.evaluate(() => window.__T.state.zoom) === -1, 'clicking again comes back');

/* one portrait of every candidate, at 12 m, night, so each can be looked at */
for (let i = 0; i < 11; i++) {
  await page.evaluate((k) => { window.__T.set({ night: true, size: 1 }); window.__T.zoomTo(k); window.__T.snap(); }, i);
  await new Promise(r => setTimeout(r, 380));
  await shoot(`p${String(i).padStart(2, '0')}-night-${trees[i].name.toLowerCase().replace(/ /g, '-')}`);
  await page.evaluate(() => { window.__T.set({ night: false }); window.__T.snap(); });
  await new Promise(r => setTimeout(r, 380));
  await shoot(`q${String(i).padStart(2, '0')}-day-${trees[i].name.toLowerCase().replace(/ /g, '-')}`);
}

const fps = await page.evaluate(async () => {
  const a = window.__T.frames; const t = performance.now();
  await new Promise(r => setTimeout(r, 1500));
  return (window.__T.frames - a) / ((performance.now() - t) / 1000);
});
check(fps > 25, 'renders smoothly', `${fps.toFixed(0)} fps at ${VP.width}x${VP.height}`);

if (!SHOW) await browser.close();

const shaderErr = errors.filter(e => /shader|glsl|program/i.test(e));
console.log(`\n  console errors : ${errors.length}`);
errors.slice(0, 8).forEach(e => console.log('    ! ' + e.slice(0, 220)));
console.log(`  shader errors  : ${shaderErr.length}`);
console.log(`  failed requests: ${failedReqs.length}`);
failedReqs.slice(0, 5).forEach(e => console.log('    ! ' + e.slice(0, 120)));
console.log(`  shots          : shots/trees/  (${fs.readdirSync(SHOTS).length} png)`);
if (errors.length || failedReqs.length) fails++;
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n` : '\n  ALL CHECKS PASSED\n');
process.exit(fails ? 1 : 0);
