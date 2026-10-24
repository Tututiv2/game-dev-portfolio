/* ============================================================================
   SHOOT — render N worlds from orbit and write PNGs to shots/.

   This is the acceptance harness for build stage B1a. It is also the thing that
   makes "verified" mean something: it launches a real Chrome, runs the real
   page, fails on any console error or failed request, and writes images that a
   human then LOOKS AT. Several bugs in this project's history were invisible to
   assertions and obvious in a screenshot.

     node tests/shoot.mjs                 # the standard 12
     node tests/shoot.mjs --set=all       # one of every archetype
     node tests/shoot.mjs --w=1600        # bigger
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const SHOTS = path.join(ROOT, 'shots');

const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const W = parseInt(argv.w || '1200', 10);
const H = parseInt(argv.h || '1200', 10);

/* The standard 12: four straight from the reference images, then eight that
   push the generator somewhere the references do not go. Fixed seeds so a
   change to the shader is visible as a diff, not as a different planet. */
const SET_12 = [
  ['volcanic',  'EMBERFALL'],
  ['glacier',   'HALCYON'],
  ['oceanic',   'MERIDIAN'],
  ['verdant',   'ASPHODEL'],
  ['crystal',   'VITRINE'],
  ['inferno',   'CAUTERY'],
  ['toxic',     'VERDIGRIS'],
  ['shattered', 'KEENING'],
  ['tundra',    'STILLWATER'],
  ['fungal',    'MYCELIA'],
  ['desert',    'OSSUARY'],
  ['gas',       'LEVIATHAN'],
];

const ALL_ARCH = ['volcanic', 'glacier', 'oceanic', 'verdant', 'crystal', 'toxic',
  'ashen', 'desert', 'barren', 'tundra', 'inferno', 'fungal', 'shattered', 'gas'];

let SET = SET_12;
if (argv.set === 'all') SET = ALL_ARCH.map((a, i) => [a, 'SPREAD-' + i]);
if (argv.set === 'same') SET = Array.from({ length: 12 },
  (_, i) => [argv.arch || 'volcanic', 'VARIANT-' + i]);

// Runs first, and exits non-zero on failure. A backtick in a GLSL comment
// breaks the page in a way whose error message points nowhere useful.
await import('./lint_glsl.mjs');

fs.mkdirSync(SHOTS, { recursive: true });
for (const f of fs.readdirSync(SHOTS)) {
  if (f.endsWith('.png')) fs.unlinkSync(path.join(SHOTS, f));
}

// Port 5276, NOT the 5273 that `npm run dev` uses: otherwise a shoot fails with
// EADDRINUSE whenever you happen to have the viewer open, which is exactly when
// you are most likely to want one.
// hmr:false as well — a shoot takes several minutes, and editing any source file
// while one is running otherwise hot-reloads the page out from under puppeteer
// ("Execution context was destroyed, most likely because of a navigation").
const server = await createServer({
  root: ROOT, logLevel: 'error',
  server: { port: 5276, strictPort: true, hmr: false },
});
await server.listen();
const URL = 'http://localhost:5276/orbit.html?pdb=1&time=9';

const errors = [], failedReqs = [];
const torn = [], retried = [];
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  // THE REAL GPU by default (--sw forces the old SwiftShader path).
  // Two reasons it changed, both from the bake landing:
  //   - boot.js detects a software renderer and deliberately drops to reduced
  //     quality — half-resolution bake, no self-shadowing, a 96-segment sphere.
  //     A gallery rendered through that is not the gallery the owner sees.
  //   - SwiftShader would have to evaluate ~19 million texels of the bake per
  //     world, on the processor, before drawing anything.
  // It is also about a hundred times faster, so there is no cost to it.
  args: (argv.sw
    ? ['--enable-unsafe-swiftshader', '--use-angle=swiftshader']
    : ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'])
    .concat(['--no-sandbox', '--disable-dev-shm-usage', `--window-size=${W},${H}`]),
  defaultViewport: { width: W, height: H },
  protocolTimeout: 600000,
});
const page = await browser.newPage();
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
page.on('requestfailed', r => failedReqs.push(r.url()));
page.on('response', r => { if (r.status() >= 400) failedReqs.push(r.status() + ' ' + r.url()); });

/* Any failure below must print what the page said before it dies. A harness
   that hides the shader compile log costs a whole debugging round every time. */
async function bail(err) {
  console.log(`\n  FAILED: ${err.message}\n`);
  console.log(`  console errors (${errors.length}):`);
  errors.slice(0, 20).forEach(e => console.log('    ! ' + e.slice(0, 1500)));
  console.log(`  failed requests (${failedReqs.length}):`);
  failedReqs.slice(0, 10).forEach(e => console.log('    ! ' + e));
  try {
    const st = await page.evaluate(() => ({
      hasV: !!window.__V, frames: window.__V ? window.__V.frames : -1,
    }));
    console.log('  state: ' + JSON.stringify(st));
  } catch { /* page may be gone */ }
  await browser.close(); await server.close();
  process.exit(1);
}
process.on('unhandledRejection', bail);

console.log(`\n=== VESPER planet shoot — ${SET.length} worlds @ ${W}x${H} ===\n`);
const t00 = Date.now();
// NOT networkidle: vite's dev server holds an HMR websocket open forever, so
// the network never goes idle and the navigation times out at 100% health.
await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 120000 });
await page.waitForFunction('!!window.__V && window.__V.frames > 0', { timeout: 180000 });
console.log(`  page up, first frame in ${((Date.now() - t00) / 1000).toFixed(1)}s\n`);

const gpu = await page.evaluate(() => {
  const gl = document.getElementById('cv').getContext('webgl2');
  const dbg = gl.getExtension('WEBGL_debug_renderer_info');
  return dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
});
console.log(`  renderer: ${gpu}\n`);

const results = [];
for (let i = 0; i < SET.length; i++) {
  const [arch, seed] = SET[i];
  const t0 = Date.now();

  const info = await page.evaluate((s, a) => {
    const k = window.__V.setWorld(s, a);
    const d = window.__V.def;
    return {
      key: k, name: d.name, radiusKm: Math.round(d.radius / 1000),
      features: d.features.length, ring: !!d.ring, clouds: !!d.clouds,
      emisAmt: +d.art.emisAmt.toFixed(2), crackFreq: +d.art.crackFreq.toFixed(1),
    };
  }, seed, arch);

  // Let it render several complete frames. Under SwiftShader one frame of this
  // shader is ~1-3 s, so this is the slow part and it is supposed to be.
  await page.waitForFunction('window.__V.frames >= 4', { timeout: 600000 });

  /* THE CAPTURE HAS TO PROVE IT GOT A WHOLE FRAME.
     `frames >= 4` says the render LOOP has run four times; it does not say the
     GPU has finished writing the buffer that toDataURL is about to read. On a
     slow frame the read lands mid-composite and comes back as TILES — a couple
     of rectangular strips of real image with pure black everywhere else.

     That is not hypothetical and it is not new: `12-gas-LEVIATHAN.png` in the
     approved gallery was 1,440,000 of 1,440,000 pixels PURE BLACK, byte for
     byte identical across runs, and had been for as long as anyone had looked.
     A gas giant is the slowest world in the set (it is analytic — no bake) so
     it lost the race every single time, and nobody noticed because a black
     square in a contact sheet of planets on black backgrounds reads as "space".

     So: settle for real animation frames, capture, and MEASURE. A frame that is
     >92% pure black is not a picture of a planet — it is a torn read — and the
     capture is retried rather than written. Pure black is the right test
     because a real frame's background is nebula and bloom, never exactly zero. */
  const capture = async () => {
    await page.evaluate(() => new Promise(res => {
      let n = 0;
      const tick = () => (++n < 4 ? requestAnimationFrame(tick) : res());
      requestAnimationFrame(tick);
    }));
    /* MEASURE THE BYTES THAT GET WRITTEN, not the live canvas. The first
       version of this check drew the canvas into a 2D context and counted
       there — and it passed a gas giant whose PNG was 1,440,000 of 1,440,000
       pixels pure black. drawImage() and toDataURL() are two different reads of
       the drawing buffer and they do not agree when one of them tears. So the
       PNG is decoded back and counted: whatever this reports is exactly what
       lands on disk. */
    return page.evaluate(async () => {
      const cv = document.getElementById('cv');
      const url = cv.toDataURL('image/png');
      const img = new Image(); img.src = url; await img.decode();
      const g = document.createElement('canvas');
      g.width = img.width; g.height = img.height;
      const ctx = g.getContext('2d');
      ctx.drawImage(img, 0, 0);
      const px = ctx.getImageData(0, 0, g.width, g.height).data;
      let black = 0;
      for (let k = 0; k < px.length; k += 4)
        if (px[k] === 0 && px[k + 1] === 0 && px[k + 2] === 0) black++;
      return { url, black: black / (px.length / 4) };
    });
  };
  let shot = await capture(), attempts = 1;
  while (shot.black > 0.92 && attempts < 5) {
    await new Promise(r => setTimeout(r, 400));
    shot = await capture(); attempts++;
  }
  if (shot.black > 0.92) torn.push(`${info.key}/${seed} ${(shot.black * 100).toFixed(1)}% black`);
  else if (attempts > 1) retried.push(`${info.key} (${attempts} tries)`);
  const dataUrl = shot.url;
  const file = path.join(SHOTS,
    `${String(i + 1).padStart(2, '0')}-${info.key}-${seed}.png`);
  fs.writeFileSync(file, Buffer.from(dataUrl.split(',')[1], 'base64'));

  const secs = ((Date.now() - t0) / 1000).toFixed(1);
  const kb = (fs.statSync(file).size / 1024).toFixed(0);
  console.log(`  [${String(i + 1).padStart(2)}/${SET.length}] ${info.key.padEnd(10)} ` +
    `${seed.padEnd(11)} r=${String(info.radiusKm).padStart(3)}km ` +
    `feat=${info.features} ${info.ring ? 'ring ' : '     '}` +
    `glow=${info.emisAmt}  ${secs}s  ${kb}KB`);
  results.push({ ...info, seed, file: path.basename(file), kb: +kb });
}

/* ---- contact sheet -------------------------------------------------------- */
const html = `<!doctype html><meta charset="utf-8"><title>VESPER — ${SET.length} worlds</title>
<style>
  body{margin:0;background:#04050a;color:#cbd6e4;
    font:300 13px/1.6 ui-sans-serif,system-ui,"Segoe UI",sans-serif;padding:26px}
  h1{font-weight:200;letter-spacing:.34em;font-size:24px;color:#ffb066;margin:0 0 4px}
  .sub{color:#5d6a7e;letter-spacing:.18em;text-transform:uppercase;font-size:11px;
    margin-bottom:24px}
  .g{display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:18px}
  figure{margin:0;background:#080a12;border:1px solid rgba(150,180,220,.11)}
  img{width:100%;display:block}
  figcaption{padding:10px 13px;font-size:11.5px;letter-spacing:.09em}
  figcaption b{color:#fff;font-weight:400}
  figcaption span{color:#5d6a7e}
</style>
<h1>VESPER</h1><div class="sub">${SET.length} worlds from orbit · ${new Date().toISOString().slice(0, 16).replace('T', ' ')}</div>
<div class="g">
${results.map(r => `<figure><img src="${r.file}" loading="lazy">
<figcaption><b>${r.name}</b> <span>· ${r.key} · ${r.seed}</span><br>
<span>${r.radiusKm} km · ${r.features} features${r.ring ? ' · rings' : ''}${r.clouds ? ' · clouds' : ''}</span>
</figcaption></figure>`).join('\n')}
</div>`;
fs.writeFileSync(path.join(SHOTS, 'index.html'), html);

await browser.close();
await server.close();

if (retried.length) console.log(`\n  torn frames re-captured: ${retried.join(', ')}`);
if (torn.length) {
  console.log(`\n  !! ${torn.length} IMAGE(S) TORN OR BLANK AFTER 5 TRIES:`);
  torn.forEach(t => console.log('     ' + t));
}
console.log(`\n  console errors : ${errors.length}`);
errors.slice(0, 12).forEach(e => console.log('    ! ' + e.slice(0, 400)));
console.log(`  failed requests: ${failedReqs.length}`);
failedReqs.slice(0, 8).forEach(e => console.log('    ! ' + e));
console.log(`\n  wrote ${results.length} images -> shots/`);
console.log(`  contact sheet  -> shots/index.html`);
console.log(`  total ${((Date.now() - t00) / 1000).toFixed(0)}s\n`);
process.exit(errors.length || failedReqs.length ? 1 : 0);
