/* Same as boottime.mjs but keeps ONE Chrome profile across two launches, so the
   second one gets Chrome's compiled-program disk cache. That is the difference
   between "the first time you ever open it" and "every time after", and they
   are not remotely the same number.

     node tests/boottime_warm.mjs                                            */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = pathToFileURL(path.join(ROOT, 'VESPER.html')).href;
const PROFILE = path.join(os.tmpdir(), 'vesper-warmcache-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

async function boot(label, query) {
  const browser = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new',
    userDataDir: PROFILE,
    args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
    defaultViewport: { width: 1280, height: 900 },
    protocolTimeout: 600000,
  });
  const page = await browser.newPage();
  const t0 = Date.now();
  await page.goto(FILE + query, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction('!!window.__V && window.__V.frames >= 1', { timeout: 300000 });
  const dt = Date.now() - t0;
  const info = await page.evaluate(() => window.__V.bakeInfo && window.__V.bakeInfo());
  // let Chrome actually flush its program cache to disk before we kill it
  await new Promise(r => setTimeout(r, 2500));
  await browser.close();
  console.log(`  ${label.padEnd(16)} first frame ${(dt / 1000).toFixed(1)} s` +
              (info && info.baked ? `   reported bake ${(info.ms / 1000).toFixed(2)} s` : ''));
}

console.log('\n  same profile twice — cold cache, then warm\n');
await boot('launch 1 (cold)', '');
await boot('launch 2 (warm)', '');
await boot('launch 3 (warm)', '');
console.log('');
