/* ============================================================================
   ABLATION — render ONE world several times, each with one shader term removed.

   The point is to stop guessing. When a planet has a texture you do not like,
   zeroing terms one at a time tells you in a single run which one is causing it,
   instead of four rounds of "change a number and re-render twelve worlds".

     node tests/ablate.mjs --arch=volcanic --seed=EMBERFALL
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = path.join(ROOT, 'shots', 'ablate');

const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const ARCH = argv.arch || 'volcanic';
const SEED = argv.seed || 'EMBERFALL';
const W = parseInt(argv.w || '700', 10);

/* each variant is a set of uniform overrides applied on top of the real world.
   --sweep=uName:a,b,c renders one image per value instead. */
let VARIANTS = [
  ['baseline',    {}],
  ['no-ao',       { uAOAmt: 0 }],
  ['no-grain',    { uGrainAmt: 0 }],
  ['no-mountains',{ uMtnAmt: 0 }],
  ['no-warp',     { uWarpAmt: 0 }],
  ['no-relief',   { uReliefBoost: 0.0001 }],
  ['no-emissive', { uEmisAmt: 0 }],
  ['no-fine-crack', { uCrackWidth2: 0 }],
  ['no-atmo',     { uAtmoAmt: 0 }],
];
if (argv.sweep) {
  const [name, list] = argv.sweep.split(':');
  VARIANTS = list.split(',').map(v => [`${name}=${v}`, { [name]: parseFloat(v) }]);
}

fs.mkdirSync(OUT, { recursive: true });
for (const f of fs.readdirSync(OUT)) fs.unlinkSync(path.join(OUT, f));

const server = await createServer({ root: ROOT, logLevel: 'error',
  server: { port: 5275, strictPort: true } });
await server.listen();

const errors = [];
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: W, height: W }, protocolTimeout: 600000,
});
const page = await browser.newPage();
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

await page.goto('http://localhost:5275/orbit.html?pdb=1',
  { waitUntil: 'domcontentloaded', timeout: 120000 });
await page.waitForFunction('!!window.__V && window.__V.frames > 0', { timeout: 180000 });

console.log(`\n=== ablation: ${ARCH} / ${SEED} @ ${W}px ===\n`);
for (const [name, ov] of VARIANTS) {
  await page.evaluate((s, a, o) => {
    window.__V.clearUniforms();
    window.__V.setWorld(s, a);
    for (const [k, v] of Object.entries(o)) window.__V.setUniform(k, v);
    window.__V.frames = 0;
  }, SEED, ARCH, ov);
  await page.waitForFunction('window.__V.frames >= 3', { timeout: 600000 });
  const d = await page.evaluate(() => document.getElementById('cv').toDataURL('image/png'));
  fs.writeFileSync(path.join(OUT, `${name}.png`), Buffer.from(d.split(',')[1], 'base64'));
  console.log(`  ${name}`);
}

await browser.close();
await server.close();
console.log(`\n  console errors: ${errors.length}`);
errors.slice(0, 8).forEach(e => console.log('    ! ' + e.slice(0, 300)));
console.log(`  wrote ${VARIANTS.length} images -> shots/ablate/\n`);
