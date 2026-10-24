/* ============================================================================
   RUN ON THE REAL GPU.

   Every test in this project so far has used SwiftShader — a SOFTWARE renderer
   that is far more permissive than a real driver. It accepts shaders that an
   NVIDIA/ANGLE toolchain rejects, and it has no practical limit on how big a
   compiled shader may get.

   This launches Chrome with hardware acceleration ON, reports which renderer it
   actually got, and dumps any shader compile or link failure in full.

     node tests/diag_realgpu.mjs            headless, hardware GPU
     node tests/diag_realgpu.mjs --show     a real visible window
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const FILE = path.join(ROOT, 'VESPER.html');
const SHOW = process.argv.includes('--show');

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: SHOW ? false : 'new',
  // NOTE what is absent: no --use-gl=swiftshader, no --enable-unsafe-swiftshader.
  // These ask for the real hardware path the owner actually runs.
  args: [
    '--use-angle=default',
    '--enable-gpu',
    '--enable-gpu-rasterization',
    '--ignore-gpu-blocklist',
    '--window-size=1280,900',
  ],
  defaultViewport: SHOW ? null : { width: 1280, height: 900 },
  protocolTimeout: 300000,
});

const page = await browser.newPage();
const errors = [], warns = [];
page.on('console', m => {
  const t = m.text();
  if (m.type() === 'error') errors.push(t);
  else if (m.type() === 'warning') warns.push(t);
});
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message + '\n' + (e.stack || '')));
page.on('requestfailed', r => errors.push('REQFAIL ' + r.url().slice(0, 120)));

console.log('\n=== VESPER on the REAL GPU ===\n');
await page.goto(pathToFileURL(FILE).href, { waitUntil: 'domcontentloaded', timeout: 60000 });

// give it a generous window; a huge shader can take many seconds to compile
let booted = false;
try {
  await page.waitForFunction('!!window.__V && window.__V.frames >= 5', { timeout: 90000 });
  booted = true;
} catch { /* fall through and report */ }
await new Promise(r => setTimeout(r, 1500));

const info = await page.evaluate(() => {
  const cv = document.getElementById('cv');
  const gl = cv && (cv.getContext('webgl2') || cv.getContext('webgl'));
  const dbg = gl && gl.getExtension('WEBGL_debug_renderer_info');
  return {
    hasV: !!window.__V,
    frames: window.__V ? window.__V.frames : -1,
    world: window.__V && window.__V.def ? window.__V.def.key : null,
    renderer: dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : (gl ? gl.getParameter(gl.RENDERER) : 'NO GL'),
    vendor: dbg ? gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : '?',
    contextLost: gl ? gl.isContextLost() : 'no-gl',
    glError: gl ? gl.getError() : -1,
    maxFrag: gl ? gl.getParameter(gl.MAX_FRAGMENT_UNIFORM_VECTORS) : -1,
    maxVary: gl ? gl.getParameter(gl.MAX_VARYING_VECTORS) : -1,
    maxTexUnits: gl ? gl.getParameter(gl.MAX_TEXTURE_IMAGE_UNITS) : -1,
    dpr: window.devicePixelRatio,
    canvasW: cv ? cv.width : -1,
    canvasH: cv ? cv.height : -1,
  };
}).catch(e => ({ err: e.message }));

console.log('  renderer     : ' + info.renderer);
console.log('  vendor       : ' + info.vendor);
console.log('  booted       : ' + booted + '   frames=' + info.frames + '   world=' + info.world);
console.log('  context lost : ' + info.contextLost + '   glError=' + info.glError);
console.log('  devicePixelRatio ' + info.dpr + '   canvas ' + info.canvasW + 'x' + info.canvasH);
console.log('  limits: fragUniformVectors=' + info.maxFrag + ' varyings=' + info.maxVary);

const shot = await page.screenshot({ encoding: 'base64' });
fs.writeFileSync(path.join(ROOT, 'shots', '_realgpu.png'), Buffer.from(shot, 'base64'));
const lit = await page.evaluate(async (b64) => {
  const img = new Image(); img.src = 'data:image/png;base64,' + b64; await img.decode();
  const g = document.createElement('canvas'); g.width = 120; g.height = 120;
  const x = g.getContext('2d'); x.drawImage(img, 0, 0, 120, 120);
  const d = x.getImageData(0, 0, 120, 120).data;
  let s = 0; for (let i = 0; i < d.length; i += 4) s += (d[i] + d[i + 1] + d[i + 2]) / 3;
  return +(s / (d.length / 4)).toFixed(1);
}, shot).catch(() => -1);
console.log('  mean screen brightness: ' + lit + '   (scene ~15-40, WHITE-OUT ~250)');

console.log('\n  console errors (' + errors.length + '):');
errors.slice(0, 10).forEach(e => console.log('    ! ' + e.slice(0, 2500)));
console.log('  warnings (' + warns.length + '):');
warns.slice(0, 6).forEach(e => console.log('    ~ ' + e.slice(0, 700)));
console.log('\n  screenshot -> shots/_realgpu.png\n');

await browser.close();
