/* ============================================================================
   BAKED vs ANALYTIC, same world, same camera, differenced.

   The bake is only worth having if it draws the SAME PLANET the per-pixel
   renderer draws. This shoots both, writes both, writes an amplified
   difference image, and prints how far apart they are.

   It is also the only test that can catch the bake being subtly the wrong
   world: a planet made of the right ingredients in the wrong places still
   looks like a planet.

     node tests/ab.mjs
     node tests/ab.mjs --seed=VESPER-9 --arch=glacier --dist=1.9
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'shots', 'ab');
fs.mkdirSync(OUT, { recursive: true });
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const SEED = argv.seed || 'VESPER-1';
const ARCH = argv.arch || 'volcanic';
const DIST = +(argv.dist || 3.15);
const W = 900, H = 900;

async function shoot(query, tag) {
  const browser = await puppeteer.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: 'new', userDataDir: path.join(os.tmpdir(), 'vesper-ab-profile'),
    args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
    defaultViewport: { width: W, height: H }, protocolTimeout: 900000,
  });
  const page = await browser.newPage();
  page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
  await page.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href + '?pdb=1&time=9' + query,
    { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction('!!window.__V && window.__V.frames >= 5', { timeout: 300000 });
  await page.evaluate((s, a, d) => {
    window.__V.setWorld(s, a);
    window.__V.setCam(d, 0.55, 0.22);
    window.__V.setSun(0.62, 0.30);
    window.__V.frames = 0;
  }, SEED, ARCH, DIST);
  await page.waitForFunction('window.__V.frames >= 8', { timeout: 300000 });
  await new Promise(r => setTimeout(r, 500));
  const dataUrl = await page.evaluate(() => document.getElementById('cv').toDataURL('image/png'));
  const buf = Buffer.from(dataUrl.split(',')[1], 'base64');
  fs.writeFileSync(path.join(OUT, tag + '.png'), buf);
  // pull the raw pixels back through the page so we can difference them
  const px = await page.evaluate(async (u) => {
    const img = new Image(); img.src = u; await img.decode();
    const c = document.createElement('canvas'); c.width = 300; c.height = 300;
    const x = c.getContext('2d'); x.drawImage(img, 0, 0, 300, 300);
    return Array.from(x.getImageData(0, 0, 300, 300).data);
  }, dataUrl);
  await new Promise(r => setTimeout(r, 2000));
  await browser.close();
  return px;
}

console.log(`\n  ${SEED} / ${ARCH} at ${DIST}R, ${W}x${H}\n`);
const baked = await shoot('', 'baked');
const analytic = await shoot('&analytic=1', 'analytic');

let sum = 0, worst = 0, n = 0, lit = 0;
const diff = new Uint8ClampedArray(baked.length);
for (let i = 0; i < baked.length; i += 4) {
  const d = (Math.abs(baked[i] - analytic[i]) + Math.abs(baked[i + 1] - analytic[i + 1])
           + Math.abs(baked[i + 2] - analytic[i + 2])) / 3;
  sum += d; if (d > worst) worst = d; n++;
  if (analytic[i] + analytic[i + 1] + analytic[i + 2] > 24) lit++;
  const a = Math.min(255, d * 6);
  diff[i] = a; diff[i + 1] = a * 0.6; diff[i + 2] = a * 0.3; diff[i + 3] = 255;
}
console.log(`  mean pixel difference : ${(sum / n).toFixed(2)} / 255`);
console.log(`  worst pixel difference: ${worst.toFixed(0)} / 255`);
console.log(`  (a correct bake at orbital range should be a few units — texture`);
console.log(`   resolution and filtering. Tens means a different planet.)`);
console.log(`\n  shots/ab/baked.png  shots/ab/analytic.png\n`);
