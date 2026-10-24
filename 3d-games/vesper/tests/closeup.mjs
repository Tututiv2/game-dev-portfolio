/* Close-up shots on the REAL GPU, to see what the surface looks like when the
   planet fills the frame — where mesh faceting and shader cost both show up.
     node tests/closeup.mjs --dist=1.15 --arch=volcanic --seed=VESPER-1        */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'shots', 'closeup');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
fs.mkdirSync(OUT, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
  defaultViewport: { width: 1280, height: 1280 }, protocolTimeout: 300000,
});
const page = await browser.newPage();
page.on('pageerror', e => console.log('PAGEERROR: ' + e.message));
await page.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href + '?pdb=1',
  { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__V && window.__V.frames >= 5', { timeout: 120000 });

const SHOTS = [
  ['far',   3.15], ['mid', 1.9], ['close', 1.22], ['grazing', 1.045],
];
for (const [name, dist] of SHOTS) {
  await page.evaluate((s, a, d) => {
    window.__V.setWorld(s, a);
    window.__V.setCam(d, 0.55, 0.22);
    window.__V.frames = 0;
  }, argv.seed || 'VESPER-1', argv.arch || 'volcanic', dist);
  await page.waitForFunction('window.__V.frames >= 8', { timeout: 180000 });
  await new Promise(r => setTimeout(r, 400));
  const fps = await page.evaluate(() => window.__V.fps || 0);
  const d = await page.evaluate(() => document.getElementById('cv').toDataURL('image/png'));
  fs.writeFileSync(path.join(OUT, `${name}.png`), Buffer.from(d.split(',')[1], 'base64'));
  console.log(`  ${name.padEnd(8)} dist=${dist}R   ${fps.toFixed(0)} fps`);
}
await browser.close();
