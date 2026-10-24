/* ============================================================================
   HOW LONG FROM DOUBLE-CLICK TO A PLANET?

   The one number the owner actually experiences, and the one no other test in
   here measures. A white window and a slow window are the same window from the
   outside (DECISIONS, 2026-08-01) — so measure it, and measure BOTH paths, or
   a change that quietly doubles the shader compile ships unnoticed.

     node tests/boottime.mjs
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = pathToFileURL(path.join(ROOT, 'VESPER.html')).href;

async function boot(label, query) {
  const browser = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new',
    args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist',
           // a cold shader cache every run, which is what a first launch is
           '--disable-gpu-program-cache', '--disable-gpu-shader-disk-cache'],
    defaultViewport: { width: 1280, height: 900 },
    protocolTimeout: 600000,
  });
  const page = await browser.newPage();
  page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
  const t0 = Date.now();
  await page.goto(FILE + query, { waitUntil: 'domcontentloaded', timeout: 60000 });
  const tDom = Date.now();
  await page.waitForFunction('!!window.__V && window.__V.frames >= 1', { timeout: 300000 });
  const tFirst = Date.now();
  const info = await page.evaluate(() => window.__V.bakeInfo && window.__V.bakeInfo());
  await browser.close();
  console.log(`  ${label.padEnd(22)} first frame ${((tFirst - t0) / 1000).toFixed(1)} s` +
              `   (dom ${((tDom - t0) / 1000).toFixed(1)} s)` +
              (info && info.baked ? `   bake ${(info.ms / 1000).toFixed(2)} s` : ''));
}

console.log('\n  cold shader cache, real GPU\n');
await boot('baked (shipping)', '');
await boot('analytic (old path)', '?analytic=1');
console.log('');
