/* ============================================================================
   BISECT a GPU/CPU disagreement down to a single noise primitive.

   When conformance.mjs says the height fields differ, this says WHICH function
   differs. Each probe evaluates one primitive on the GPU and the identical call
   on the CPU over the same 4096 directions.
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';
import { snoise, fbm, ridged, crackMask, warp1, warp2 } from '../src/core/simplex.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const _o = [0, 0, 0];
const PROBES = [
  [7, 'HARNESS: d.x', (d) => d[0]],
  [8, 'HARNESS: d.y', (d) => d[1]],
  [1, 'snoise(d*7.3)', (d) => snoise(d[0] * 7.3, d[1] * 7.3, d[2] * 7.3)],
  [2, 'fbm(d*3.1, 2oct)', (d) => fbm(d[0] * 3.1, d[1] * 3.1, d[2] * 3.1, 2)],
  [3, 'crackMask(d*9, 0.1)', (d) => crackMask(d[0] * 9, d[1] * 9, d[2] * 9, 0.1)],
  [6, 'warp1(d*2, 0.7).x', (d) => warp1([d[0] * 2, d[1] * 2, d[2] * 2], 0.7, _o)[0]],
  [4, 'warp2(d*2, 0.7).x', (d) => warp2([d[0] * 2, d[1] * 2, d[2] * 2], 0.7, _o)[0]],
  [5, 'ridged(d*5, 2.4, 3oct)', (d) => ridged(d[0] * 5, d[1] * 5, d[2] * 5, 2.4, 3)],
];

const server = await createServer({ root: ROOT, logLevel: 'error',
  server: { port: 5279, strictPort: true, hmr: false } });
await server.listen();
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 300, height: 300 }, protocolTimeout: 600000,
});
const page = await browser.newPage();
page.on('pageerror', e => console.log('PAGEERROR: ' + e.message));
await page.goto('http://localhost:5279/conformance.html',
  { waitUntil: 'domcontentloaded', timeout: 120000 });
await page.waitForFunction('!!window.__C && window.__C.ready', { timeout: 180000 });

/* LIVENESS FIRST — this tool has form. DECISIONS records it giving the height
   field a clean bill of health while that field was 20% wrong, because its
   probes use hardcoded frequencies and never read a uniform. The complementary
   failure is worse and has now happened too: on a LOST WebGL context every
   probe reads back 0.0, and this tool cheerfully reports "DIFFER" against a
   dead GPU as though the noise primitives had drifted. Mode 9 asks the shader
   what gEps is at the point of use — it is a literal assigned at the top of
   main(), so anything but 1e-7 means nothing is executing. Refuse to print
   numbers rather than print misleading ones. */
{
  const live = await page.evaluate(() => ({
    lost: window.__C.contextLost(), g: window.__C.probe(9).gpu[0],
    rend: window.__C.rendererName,
  }));
  console.log(`\n  renderer: ${live.rend}`);
  if (live.lost || Math.abs(live.g - 1e-7) > 1e-9) {
    console.log(`\n  ABORT: WebGL context is dead (lost=${live.lost}, gEps read back ` +
      `${live.g}, expected 1e-7). Every probe would read 0.0 and every primitive\n` +
      `  would be reported as DIFFER. This is a broken instrument, NOT a noise bug.\n`);
    await browser.close(); await server.close();
    process.exit(2);
  }
}

console.log('\n=== bisecting GPU vs CPU, primitive by primitive ===\n');
const d = [0, 0, 0];
for (const [mode, label, cpuFn] of PROBES) {
  const { dirs, gpu } = await page.evaluate((m) => window.__C.probe(m), mode);
  let maxE = 0, sumE = 0, at = -1, gMin = 1e9, gMax = -1e9;
  for (let i = 0; i < gpu.length; i++) {
    d[0] = dirs[i * 3]; d[1] = dirs[i * 3 + 1]; d[2] = dirs[i * 3 + 2];
    const c = cpuFn(d);
    gMin = Math.min(gMin, gpu[i]); gMax = Math.max(gMax, gpu[i]);
    const e = Math.abs(c - gpu[i]);
    sumE += e;
    if (e > maxE) { maxE = e; at = i; }
  }
  // The DISTRIBUTION matters more than the max. "a few samples completely
  // wrong" is a discrete bug you can hunt down; "everything slightly off" is
  // float32-vs-float64 drift and no amount of staring will fix it.
  let nBad = 0, nNear = 0;
  for (let i = 0; i < gpu.length; i++) {
    d[0] = dirs[i * 3]; d[1] = dirs[i * 3 + 1]; d[2] = dirs[i * 3 + 2];
    const e = Math.abs(cpuFn(d) - gpu[i]);
    if (e > 0.01) nBad++; else if (e < 1e-4) nNear++;
  }
  const ok = maxE < 1e-3;
  console.log(`  ${ok ? 'MATCH ' : 'DIFFER'}  ${label.padEnd(24)} ` +
    `max ${maxE.toExponential(2)}  mean ${(sumE / gpu.length).toExponential(2)}` +
    `   >1% off: ${(nBad / gpu.length * 100).toFixed(1)}%` +
    `   near-exact: ${(nNear / gpu.length * 100).toFixed(1)}%`);
  if (!ok) {
    d[0] = dirs[at * 3]; d[1] = dirs[at * 3 + 1]; d[2] = dirs[at * 3 + 2];
    console.log(`          worst at dir(${d.map(v => v.toFixed(4)).join(', ')})` +
      `  cpu ${cpuFn(d).toFixed(6)}  gpu ${gpu[at].toFixed(6)}`);
  }
}

await browser.close();
await server.close();
console.log('');
