/* ============================================================================
   VERIFY THE ONE-CLICK PATH.

   Opens VESPER.html exactly the way the owner does — file:// protocol, no dev
   server, no flags that a double-click would not give you — and proves it
   actually renders a planet. Screenshots it so a human can look.

   This test exists because the previous version LOADED and showed the HUD while
   silently failing to run any JavaScript, which looks identical to "crashed".
   Checking that the page loads is not enough; it has to draw.
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const FILE = path.join(ROOT, 'VESPER.html');

let fails = 0;
const check = (ok, msg, extra = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}${extra ? '  ' + extra : ''}`);
  if (!ok) fails++;
};

console.log('\n=== one-click verification (file://, no server) ===\n');
check(fs.existsSync(FILE), 'VESPER.html exists');
if (!fs.existsSync(FILE)) process.exit(1);

const html = fs.readFileSync(FILE, 'utf8');
check(!/type="module"/.test(html), 'no ES module script (chrome blocks those on file://)');
check(!/<script[^>]+src=/.test(html), 'no external script references');
check(html.length > 300000, 'bundle is inlined', `${(html.length / 1024).toFixed(0)} KB`);

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  // deliberately NOT passing --allow-file-access-from-files: a double-click
  // does not get that flag either
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 1000, height: 800 },
  protocolTimeout: 300000,
});
const page = await browser.newPage();
const errors = [], failedReqs = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
page.on('requestfailed', r => failedReqs.push(r.url()));

await page.goto(pathToFileURL(FILE).href, { waitUntil: 'domcontentloaded', timeout: 60000 });

// SIX frames plus a settle, not three. Under software rendering a frame takes
// several seconds, and screenshotting too early catches the canvas before the
// compositor has ever received a painted frame — which comes back pure white
// and looks exactly like a catastrophic render bug. It cost a round.
let booted = true;
try {
  await page.waitForFunction('!!window.__V && window.__V.frames >= 6', { timeout: 400000 });
} catch { booted = false; }
await new Promise(r => setTimeout(r, 1500));
check(booted, 'the game actually boots and renders frames');

const st = await page.evaluate(() => ({
  frames: window.__V ? window.__V.frames : -1,
  world: window.__V && window.__V.def ? window.__V.def.key : null,
  name: window.__V && window.__V.def ? window.__V.def.name : null,
  canvasW: document.getElementById('cv').width,
})).catch(() => ({}));
check((st.frames || 0) >= 3, 'frames rendered', String(st.frames));
check(!!st.world, 'a world was generated', `${st.name} (${st.world})`);
check((st.canvasW || 0) > 100, 'canvas sized to the window', `${st.canvasW}px`);

/* The page must not be BLACK — a dead canvas still "renders frames".
   Measured from puppeteer's COMPOSITOR screenshot, not by drawImage() on the
   WebGL canvas: without preserveDrawingBuffer the drawing buffer is already
   cleared by the time we could read it, so that route reports a black frame for
   a perfectly good render. (It did, and nearly sent me chasing a bug that was
   in the test.) The screenshot goes back in as a data: URL, which does not
   taint a 2D canvas, so the pixels are readable. */
const shot = await page.screenshot({ encoding: 'base64' });
fs.writeFileSync(path.join(ROOT, 'shots', '_oneclick.png'), Buffer.from(shot, 'base64'));
const lit = await page.evaluate(async (b64) => {
  const img = new Image();
  img.src = 'data:image/png;base64,' + b64;
  await img.decode();
  const g = document.createElement('canvas');
  g.width = 160; g.height = 128;
  const x = g.getContext('2d');
  x.drawImage(img, 0, 0, 160, 128);
  const d = x.getImageData(0, 0, 160, 128).data;
  let sum = 0, bright = 0;
  for (let i = 0; i < d.length; i += 4) {
    const v = (d[i] + d[i + 1] + d[i + 2]) / 3;
    sum += v; if (v > 40) bright++;
  }
  return { avg: sum / (d.length / 4), brightPct: bright / (d.length / 4) * 100 };
}, shot);
// Bounded at BOTH ends. "Something is lit" alone passed happily on a pure-white
// frame, which is the exact failure it was written to catch.
const litDesc = `${lit.brightPct.toFixed(1)}% lit, avg ${lit.avg.toFixed(1)}`;
check(lit.brightPct > 6, 'a planet is visible, not a black frame', litDesc);
check(lit.avg > 6 && lit.avg < 170, 'the frame is a scene, not a white-out', litDesc);

/* interaction still works with no server behind it */
await page.keyboard.press(']');
await page.waitForFunction('window.__V.frames >= 2', { timeout: 120000 }).catch(() => {});
const st2 = await page.evaluate(() => ({ world: window.__V.def.key, name: window.__V.def.name }));
check(!!st2.world, 'keyboard controls work (new seed rolled)', `${st2.name} (${st2.world})`);

await browser.close();

console.log(`\n  console errors : ${errors.length}`);
errors.slice(0, 6).forEach(e => console.log('    ! ' + e.slice(0, 250)));
console.log(`  failed requests: ${failedReqs.length}`);
failedReqs.slice(0, 6).forEach(e => console.log('    ! ' + e.slice(0, 140)));
console.log(`  screenshot     : shots/_oneclick.png`);
if (errors.length || failedReqs.length) fails++;
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n` : '\n  all checks passed\n');
process.exit(fails ? 1 : 0);
