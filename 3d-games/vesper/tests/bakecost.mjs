/* ============================================================================
   WHAT DOES ONE BAKE COST?

   Runs on a WARM shader cache, because on a cold one the first draw drags a
   ~24 second ANGLE compile in with it and every number after that is a lie.
   Then bakes repeatedly and reports the spread, because a single sample of a
   GPU timing is not a measurement.

     node tests/bakecost.mjs
     node tests/bakecost.mjs --n=8 --arch=glacier
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = pathToFileURL(path.join(ROOT, 'VESPER.html')).href;
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const N = +(argv.n || 6);
const PROFILE = path.join(os.tmpdir(), 'vesper-bakecost-profile');

const launch = () => puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', userDataDir: PROFILE,
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
  defaultViewport: { width: 1280, height: 900 }, protocolTimeout: 900000,
});

/* pass 1: warm the program cache and throw it away */
fs.rmSync(PROFILE, { recursive: true, force: true });
console.log('\n  warming the shader cache (this is the slow one)...');
{
  const b = await launch();
  const p = await b.newPage();
  await p.goto(FILE, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForFunction('!!window.__V && window.__V.frames >= 1', { timeout: 300000 });
  await new Promise(r => setTimeout(r, 2500));
  await b.close();
}

const browser = await launch();
const page = await browser.newPage();
page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
await page.goto(FILE, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__V && window.__V.frames >= 3', { timeout: 300000 });

const first = await page.evaluate(() => window.__V.bakeInfo());
console.log(`  warm boot, bake size ${first.size}, first bake ${first.ms.toFixed(0)} ms\n`);

const ms = [];
for (let i = 0; i < N; i++) {
  const r = await page.evaluate((i) => {
    const t = performance.now();
    window.__V.setWorld('BAKECOST-' + i, null);
    return { wall: performance.now() - t, ...window.__V.bakeInfo() };
  }, i);
  ms.push(r.ms);
  console.log(`  bake ${String(i).padEnd(2)} ${r.ms.toFixed(0).padStart(6)} ms` +
              `   (setWorld total ${r.wall.toFixed(0)} ms)` +
              (r.baked ? '' : '   [not baked — gas giant]'));
  await new Promise(r => setTimeout(r, 250));
}
const good = ms.filter(x => x > 0);
if (good.length) {
  good.sort((a, b) => a - b);
  console.log(`\n  median ${good[good.length >> 1].toFixed(0)} ms` +
              `   min ${good[0].toFixed(0)}   max ${good[good.length - 1].toFixed(0)}`);
}
console.log('');
await browser.close();
