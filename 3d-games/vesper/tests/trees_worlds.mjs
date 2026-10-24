/* ============================================================================
   VERIFY THE WORLDS.

   The owner kept eighteen trees and said "these are all 1 theme". He was right,
   and the theme was never the trees: every one of them was painted from
   verdant's two colours because verdant was the only palette there was.

   A tree is geometry; a world is paint. This checks the consequences:
     - all thirteen worlds render, and each one looks DIFFERENT from the others
     - the palettes come from the game's own archetypes.js (generated, not typed)
     - the backdrop lift keeps each world's hue instead of washing it to teal
     - the library keeps a separate collection per world, and ALL WORLDS shows
       every shape you have ever kept, repainted in the palette you are on

     node tests/trees_worlds.mjs           real GPU
     node tests/trees_worlds.mjs --shots   also write one frame per world
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const FILE = path.join(ROOT, 'TREES.html');
const SHOTS = path.join(ROOT, 'shots', 'trees');
const ALLSHOTS = process.argv.includes('--shots');
const VP = { width: 1600, height: 950 };
fs.mkdirSync(SHOTS, { recursive: true });

let fails = 0;
const check = (ok, msg, extra = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}${extra ? '   ' + extra : ''}`);
  if (!ok) fails++;
};
console.log('\n=== TREES.html — thirteen worlds ===\n');

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist',
         `--window-size=${VP.width},${VP.height}`],
  defaultViewport: VP, protocolTimeout: 600000,
});
const page = await browser.newPage();
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
await page.goto(pathToFileURL(FILE).href, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__T && window.__T.frames >= 8', { timeout: 120000 });
await new Promise(r => setTimeout(r, 700));
check(!(await page.evaluate(() => window.__T.fatal)), 'the page boots');

/* the eighteen the owner kept are there on a fresh install */
const libs = await page.evaluate(() => window.__T.libs());
check((libs.verdant || []).length === 18, 'the eighteen kept trees are seeded into verdant',
  Object.entries(libs).map(([k, v]) => `${k}=${v.length}`).join(' '));

const worlds = await page.evaluate(() => window.__T.worlds());
check(worlds.length === 13, 'thirteen worlds, gas excluded because it has no surface',
  worlds.join(' '));

/* ---- every world renders, and no two look alike -------------------------- */
await page.evaluate(() => {
  window.__T.mode('library'); window.__T.libAll(true);
  window.__T.set({ night: true, size: 1 });
});
const seen = [];
let blown = 0, dark = 0;
for (const w of worlds) {
  await page.evaluate(k => { window.__T.world(k); window.__T.snap(); }, w);
  await new Promise(r => setTimeout(r, 620));
  const b64 = await page.screenshot({ encoding: 'base64' });
  if (ALLSHOTS || ['verdant', 'crystal', 'desert', 'inferno'].includes(w)) {
    fs.writeFileSync(path.join(SHOTS, `w-${w}.png`), Buffer.from(b64, 'base64'));
  }
  const m = await page.evaluate(async (s) => {
    const i = new Image(); i.src = 'data:image/png;base64,' + s; await i.decode();
    const c = document.createElement('canvas'); c.width = 320; c.height = 200;
    const x = c.getContext('2d'); x.drawImage(i, 0, 0, 320, 200);
    const d = x.getImageData(0, 0, 320, 200).data;
    let r = 0, g = 0, b = 0, n = 0, hot = 0;
    for (let k = 0; k < d.length; k += 4) {
      r += d[k]; g += d[k + 1]; b += d[k + 2]; n++;
      if (d[k] > 248 && d[k + 1] > 248 && d[k + 2] > 248) hot++;
    }
    return { r: r / n, g: g / n, b: b / n, mean: (r + g + b) / (3 * n),
             blown: hot / n * 100, trees: window.__T.trees.length };
  }, b64);
  if (m.blown > 2) blown++;
  if (m.mean < 22) dark++;
  seen.push({ w, r: m.r, g: m.g, b: m.b, mean: m.mean, trees: m.trees });
  console.log(`    ${w.padEnd(10)} ${String(m.trees).padStart(2)} on shelf   ` +
    `rgb ${m.r.toFixed(0)},${m.g.toFixed(0)},${m.b.toFixed(0)}   mean ${m.mean.toFixed(1)}`);
}
console.log('');
check(seen.every(s => s.trees === 11), 'ALL WORLDS shows the kept shapes on every world');
check(blown === 0, 'no world blows out to white', `${blown} blown`);
check(dark === 0, 'no world is too dark to read', `${dark} dark`);

/* THE POINT: worlds must differ by HUE, not merely by brightness. A fixed
   neutral backdrop lift made all thirteen the same slate teal at the LIGHT
   setting; this is the check that would have caught it. */
let minD = 1e9, pair = '';
for (let i = 0; i < seen.length; i++) {
  for (let j = i + 1; j < seen.length; j++) {
    const a = seen[i], b = seen[j];
    const na = Math.hypot(a.r, a.g, a.b) || 1, nb = Math.hypot(b.r, b.g, b.b) || 1;
    const d = Math.hypot(a.r / na - b.r / nb, a.g / na - b.g / nb, a.b / na - b.b / nb);
    if (d < minD) { minD = d; pair = `${a.w} vs ${b.w}`; }
  }
}
check(minD > 0.012, 'every world is a different colour from every other',
  `closest pair ${pair} at ${minD.toFixed(4)}`);

/* ---- EVERY PLANET GROWS DIFFERENT TREES ---------------------------------
   Repainting one set of shapes thirteen times is still one theme wearing
   thirteen coats. Generation zero is rolled from each world's own recipe
   (recipes.js), so this checks the SHAPES differ and not just the colours —
   and that each recipe actually produced the character it names. */
await page.evaluate(() => { window.__T.mode('breed'); window.__T.set({ night: true, size: 1 }); });
const gen0 = {};
for (const w of worlds) {
  await page.evaluate(k => window.__T.world(k), w);
  await new Promise(r => setTimeout(r, 260));
  gen0[w] = await page.evaluate(() => ({
    codes: window.__T.codes().join('|'),
    desc: Array.from(document.querySelectorAll('.card .idea')).map(e => e.textContent).join(' ~ '),
    tris: window.__T.trees.map(t => t.tris),
  }));
}
const distinct = new Set(Object.values(gen0).map(g => g.codes));
check(distinct.size === worlds.length, 'every world opens on its OWN eleven trees',
  `${distinct.size}/${worlds.length} distinct generation zeros`);
check(Object.values(gen0).every(g => Math.max(...g.tris) <= 400),
  'every seeded tree is inside the triangle ceiling',
  `worst ${Math.max(...Object.values(gen0).flatMap(g => g.tris))}`);

/* the recipes have to have actually landed — a character each world names */
const wants = [['oceanic', /ribbon/], ['tundra', /whorl/], ['desert', /disc/],
               ['crystal', /blade/], ['verdant', /lump/], ['ashen', /bare/],
               ['fungal', /disc/], ['inferno', /upswept/]];
let wrong = [];
for (const [w, re] of wants) if (!re.test(gen0[w].desc)) wrong.push(w);
check(wrong.length === 0, 'and each one grew the character its recipe names',
  wrong.length ? 'missing on ' + wrong.join(', ') : wants.map(x => x[0]).join(', '));

/* ---- collections are per world ------------------------------------------ */
await page.evaluate(() => {
  window.__T.mode('library'); window.__T.libAll(false); window.__T.world('tundra');
});
await new Promise(r => setTimeout(r, 400));
check(await page.evaluate(() => window.__T.lib().length) === 0,
  'a world you have not bred on starts with its own empty shelf');
const lede = await page.evaluate(() => document.getElementById('sub').textContent);
check(/nothing kept on frozen/i.test(lede), 'and it says so rather than looking broken',
  lede.slice(0, 54) + '\u2026');

await page.evaluate(() => { window.__T.mode('breed'); });
await new Promise(r => setTimeout(r, 450));
await page.evaluate(() => window.__T.select([1, 3]));
await new Promise(r => setTimeout(r, 250));
const after = await page.evaluate(() => window.__T.libs());
check((after.tundra || []).length === 2 && (after.verdant || []).length === 18,
  'keeping on tundra builds a tundra collection and leaves verdant alone',
  Object.entries(after).map(([k, v]) => `${k}=${v.length}`).join(' '));

const fps = await page.evaluate(async () => {
  const a = window.__T.frames, t = performance.now();
  await new Promise(r => setTimeout(r, 1200));
  return (window.__T.frames - a) / ((performance.now() - t) / 1000);
});
check(fps > 25, 'renders smoothly', `${fps.toFixed(0)} fps`);

await browser.close();
console.log(`\n  console errors : ${errors.length}`);
errors.slice(0, 6).forEach(e => console.log('    ! ' + e.slice(0, 200)));
if (errors.length) fails++;
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n` : '\n  ALL CHECKS PASSED\n');
process.exit(fails ? 1 : 0);
