/* Boot on a WARM shader cache and photograph the result.

   Exists because tests/bakecost.mjs found the first bake of a warm launch
   reporting 19 ms and every later bake reporting 1330 ms for identical work.
   One of those is not doing the work, and a bake that silently did nothing
   produces a black planet — on the owner's SECOND launch, not their first,
   which is the worst possible way for a bug to present.

     node tests/warmshot.mjs                                                 */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = pathToFileURL(path.join(ROOT, 'VESPER.html')).href;
const PROFILE = path.join(os.tmpdir(), 'vesper-warmshot-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const launch = () => puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', userDataDir: PROFILE,
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
  defaultViewport: { width: 1280, height: 900 }, protocolTimeout: 900000,
});

async function shot(tag) {
  const b = await launch();
  const p = await b.newPage();
  p.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
  p.on('console', m => { if (m.type() === 'error') console.log('  ERR ' + m.text().slice(0, 300)); });
  await p.goto(FILE, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForFunction('!!window.__V && window.__V.frames >= 8', { timeout: 300000 });
  await new Promise(r => setTimeout(r, 1200));
  const info = await p.evaluate(() => window.__V.bakeInfo());
  const png = await p.screenshot({ encoding: 'base64' });
  fs.writeFileSync(path.join(ROOT, 'shots', `_warm_${tag}.png`), Buffer.from(png, 'base64'));
  const lit = await p.evaluate(async (b64) => {
    const img = new Image(); img.src = 'data:image/png;base64,' + b64; await img.decode();
    const g = document.createElement('canvas'); g.width = 120; g.height = 120;
    const x = g.getContext('2d'); x.drawImage(img, 0, 0, 120, 120);
    const d = x.getImageData(0, 0, 120, 120).data;
    let s = 0, mx = 0;
    for (let i = 0; i < d.length; i += 4) {
      const v = (d[i] + d[i + 1] + d[i + 2]) / 3; s += v; if (v > mx) mx = v;
    }
    return { mean: +(s / (d.length / 4)).toFixed(1), max: +mx.toFixed(0) };
  }, png);
  await new Promise(r => setTimeout(r, 2500));   // flush the program cache
  await b.close();
  console.log(`  ${tag.padEnd(6)} bake ${String(info.ms.toFixed(0)).padStart(6)} ms   ` +
              `mean ${String(lit.mean).padStart(5)}  peak ${String(lit.max).padStart(4)}` +
              `   -> shots/_warm_${tag}.png`);
  return lit;
}

console.log('\n  (scene mean ~15-40 with a bright peak. mean ~0 = the bake did nothing)\n');
await shot('cold');
await shot('warm');
console.log('');
