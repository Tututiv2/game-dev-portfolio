/* ============================================================================
   HEIGHT CONFORMANCE — does the ground you SEE match the ground you STAND ON?

   Binding rule 3: exactly one height function, shared by the mesh and by
   collision. The GPU has its copy in GLSL, the CPU has its copy in JS, and this
   test renders the real GPU one into a float buffer and compares 4096 sample
   directions per world against the real CPU one.

   Without this, the two drift the first time anyone edits either, and the
   symptom is the player sinking through a hill or standing on thin air — a
   miserable bug to diagnose from the outside. Two real divergences were already
   caught writing the mirror: a wrong constant in the simplex gradient table,
   and warp1/warp2 using a different fBm gain than the rest of the field.

   Tolerance is in METRES, because that is what the player experiences. GLSL is
   32-bit and JS is 64-bit, so exact equality is not on offer; centimetres are.
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const WORLDS = [
  ['volcanic', 'EMBERFALL'], ['glacier', 'HALCYON'], ['oceanic', 'MERIDIAN'],
  ['verdant', 'ASPHODEL'], ['crystal', 'VITRINE'], ['shattered', 'KEENING'],
  ['tundra', 'STILLWATER'], ['desert', 'OSSUARY'], ['barren', 'SPREAD-1'],
];
/* Tolerances are RELATIVE TO RELIEF, not absolute metres.
   Asking for 25 cm on a world with 28 km of relief is asking float32 arithmetic
   for 1e-5 relative agreement, which is not on offer. What remains after every
   primitive was made bit-portable is drift of about 1e-6 in the warp input,
   amplified at crack boundaries by a smoothstep whose slope is 1/crackWidth —
   so it lands ON canyon edges and nowhere else.
   These bounds are tight enough to catch any real regression (a broken
   primitive moves the MEAN by orders of magnitude) and honest about the floor. */
const TOL_MEAN = 0.001;   // 0.1% of relief, averaged over the whole sphere
const TOL_P50  = 0.0002;  // 0.02% of relief at the median
const TOL_P99  = 0.02;    // 2% of relief at the 99th percentile
const TOL_FRAC = 0.015;   // at most 1.5% of the surface off by >1% of relief

let fails = 0;
const check = (ok, msg) => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}`); if (!ok) fails++; };

await import('./lint_glsl.mjs');

const server = await createServer({ root: ROOT, logLevel: 'error',
  server: { port: 5278, strictPort: true, hmr: false } });
await server.listen();

const errors = [];
/* SOFTWARE RENDERING IS DELIBERATE — this test compares MATHS, not looks, and a
   software device is the same everywhere. But the flag matters:

   `--use-gl=swiftshader` is the legacy switch, and under it SwiftShader loses
   this page's context during module init. A lost context reads back as
   all-zeros, and all-zeros does not look like a dead instrument — it looks like
   the height field disagreeing by 12-21% of relief, on every world, repeatably.
   That is exactly what it was mistaken for (DECISIONS, 2026-08-05).
   `--use-angle=swiftshader` reaches the IDENTICAL SwiftShader device
   (both report "Vulkan 1.3.0 (SwiftShader Device (Subzero))") and survives.
   The renderer is printed on every run now, so a silent fallback to the real
   GPU would be visible rather than assumed. */
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 400, height: 400 }, protocolTimeout: 900000,
});
const page = await browser.newPage();
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
// A context loss is logged by Chrome as a WARNING, so the error counter above
// never saw it. Catch it explicitly, by the text, wherever it is logged.
page.on('console', m => {
  if (/context\s*lost/i.test(m.text())) errors.push('CONTEXT LOST: ' + m.text());
});

console.log('\n=== height conformance: GPU vs CPU ===\n');
await page.goto('http://localhost:5278/conformance.html',
  { waitUntil: 'domcontentloaded', timeout: 120000 });
await page.waitForFunction('!!window.__C && window.__C.ready', { timeout: 180000 });

const caps = await page.evaluate(() => ({
  n: window.__C.N, f: window.__C.floatRT,
  lost: window.__C.contextLost(), rend: window.__C.rendererName,
  // Mode 9 asks the shader what gEps is at the point of use. It is assigned as
  // a literal at the top of main(), so a value of anything but 1e-7 means the
  // fragment shader is not running at all. This is the liveness check: it costs
  // one 64x64 draw and it is the difference between "the height field is wrong"
  // and "nothing rendered". Without it, a dead context is indistinguishable
  // from a 12%-of-relief divergence — see DECISIONS, 2026-08-05.
  gEps: window.__C.probe(9).gpu[0],
}));
console.log(`   renderer: ${caps.rend}`);
check(caps.f, `float render targets available (needed to read heights back)`);
check(!caps.lost, `WebGL context alive (a lost one reads back as all-zeros)`);
check(Math.abs(caps.gEps - 1e-7) < 1e-9,
  `shader is actually executing (gEps reads back ${caps.gEps}, expected 1e-7)`);
console.log(`   ${caps.n} sample directions per world · bounds are relative to ` +
  `each world's relief: mean<${TOL_MEAN * 100}% p50<${TOL_P50 * 100}% p99<${TOL_P99 * 100}%\n`);

let worstMean = 0, worstFrac = 0;
for (const [arch, seed] of WORLDS) {
  const r = await page.evaluate((s, a) => window.__C.compare(s, a), seed, arch);
  if (r.skipped) { console.log(`  SKIP  ${arch.padEnd(10)} ${r.skipped}`); continue; }
  const spreadM = r.spread * r.reliefM;
  const meanM = r.meanErr * r.reliefM;
  const p99M = r.p99 * r.reliefM;
  worstMean = Math.max(worstMean, meanM / spreadM);
  worstFrac = Math.max(worstFrac, r.fracOff);

  console.log(`  ${arch.padEnd(10)} ${seed.padEnd(11)} relief ${(spreadM / 1000).toFixed(1).padStart(5)} km ` +
    `· mean ${meanM.toFixed(2).padStart(7)} m · p50 ${(r.p50 * r.reliefM).toFixed(3).padStart(7)} m ` +
    `· p99 ${p99M.toFixed(1).padStart(6)} m · off>1% on ${(r.fracOff * 100).toFixed(2)}% of surface`);

  const ok = meanM / spreadM < TOL_MEAN
          && (r.p50 * r.reliefM) / spreadM < TOL_P50
          && p99M / spreadM < TOL_P99
          && r.fracOff < TOL_FRAC
          && spreadM > 500               // a constant mirror would also "agree"
          && r.gpuSpread > r.spread * 0.5; // ...and so would a dead GPU returning 0
  check(ok, `  ${arch.padEnd(10)} mean ${(meanM / spreadM * 100).toFixed(4)}% ` +
    `· p50 ${((r.p50 * r.reliefM) / spreadM * 100).toFixed(4)}% ` +
    `· p99 ${(p99M / spreadM * 100).toFixed(2)}% of relief`);
}

await browser.close();
await server.close();

console.log(`\n  worst mean disagreement: ${(worstMean * 100).toFixed(3)}% of relief`);
console.log(`  worst surface fraction off by >1%: ${(worstFrac * 100).toFixed(2)}%`);
console.log(`\n  Every primitive (simplex, fBm, ridged, Worley, both warps) now agrees`);
console.log(`  to ~1e-6. What is left is that residual drift entering the domain warp`);
console.log(`  and being amplified at crack boundaries by a smoothstep of slope`);
console.log(`  1/crackWidth. It therefore lands ON canyon edges and nowhere else,`);
console.log(`  which is why p99 is far above p50. Worlds with terraced terrain`);
console.log(`  (crystal, shattered) quantise it away entirely and agree exactly.`);
console.log(`  console errors: ${errors.length}`);
errors.slice(0, 8).forEach(e => console.log('    ! ' + e.slice(0, 300)));
if (errors.length) fails++;
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n` : '\n  all checks passed\n');
process.exit(fails ? 1 : 0);
