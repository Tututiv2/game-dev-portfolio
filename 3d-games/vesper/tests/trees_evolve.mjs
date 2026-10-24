/* ============================================================================
   VERIFY THE BREEDER.

   The gallery has eleven trees I chose, and I looked at all of them. The
   breeder has a space of 256^34 trees and nobody will ever look at more than a
   few hundred, so the guarantees have to be about the SPACE rather than about
   the specimens:

     - no reachable genome exceeds the triangle ceiling
     - every genome normalises to base y=0, tip y=1, widest r=1
     - the same genome grows the same tree, every time, so the code printed
       under a favourite really does bring it back
     - selecting and breeding changes what is on screen, without a reload
     - the gallery still works (this all landed on top of it)

     node tests/trees_evolve.mjs           real GPU
     node tests/trees_evolve.mjs --deep    12,000 random genomes instead of 3,000
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const FILE = path.join(ROOT, 'TREES.html');
const SHOTS = path.join(ROOT, 'shots', 'trees');
const N = process.argv.includes('--deep') ? 12000 : 3000;
const VP = { width: 1600, height: 950 };
fs.mkdirSync(SHOTS, { recursive: true });

let fails = 0;
const check = (ok, msg, extra = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}${extra ? '   ' + extra : ''}`);
  if (!ok) fails++;
};
console.log('\n=== TREES.html — the breeder ===\n');

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
check(!(await page.evaluate(() => window.__T.fatal)), 'the page still boots');

/* ---- 1. the space, not the specimens ------------------------------------ */
const sweep = await page.evaluate((n) => {
  const out = { worst: 0, worstCode: '', bad: 0, offSpec: 0, nan: 0, ms: 0, empty: 0 };
  const t0 = performance.now();
  for (let i = 0; i < n; i++) {
    const code = window.__T.randomCode(i * 2654435761 + 12345);
    const r = window.__T.grow(code);
    if (!r) { out.bad++; continue; }
    if (!isFinite(r.tris) || r.tris <= 0) { out.nan++; continue; }
    if (r.tris < 4) out.empty++;
    if (r.tris > out.worst) { out.worst = r.tris; out.worstCode = code; }
    if (r.tris > 400) out.bad++;
    if (Math.abs(r.minY) > 1e-4 || Math.abs(r.maxY - 1) > 1e-4 || Math.abs(r.maxR - 1) > 0.02) out.offSpec++;
  }
  out.ms = (performance.now() - t0) / n;
  return out;
}, N);
check(sweep.bad === 0, `no genome in ${N} random ones breaks the 400-triangle ceiling`,
  `worst ${sweep.worst}`);
check(sweep.offSpec === 0, `all ${N} normalise to base 0, tip 1, widest 1`,
  sweep.offSpec ? `${sweep.offSpec} off spec` : '');
check(sweep.nan === 0, 'no genome produces a broken mesh', `${sweep.nan} bad`);
check(sweep.empty === 0, 'no genome produces an empty mesh', `${sweep.empty} empty`);
console.log(`    ${sweep.ms.toFixed(3)} ms to grow one tree — a whole generation is ` +
  `${(sweep.ms * 11).toFixed(2)} ms, which is why nothing has to reload\n`);

/* ---- 2. a genome is a promise ------------------------------------------- */
const det = await page.evaluate(() => {
  let same = 0, n = 60;
  for (let i = 0; i < n; i++) {
    const c = window.__T.randomCode(777 + i * 31);
    const a = window.__T.grow(c), b = window.__T.grow(c);
    if (a.hash === b.hash && a.tris === b.tris) same++;
  }
  return { same, n };
});
check(det.same === det.n, 'the same genome grows the same tree, every time',
  `${det.same}/${det.n}`);

const round = await page.evaluate(() => {
  const c = window.__T.codes()[0] || window.__T.randomCode(5);
  return { c, ok: true };
});
await page.evaluate(() => window.__T.mode('breed'));
await new Promise(r => setTimeout(r, 500));
const codes0 = await page.evaluate(() => window.__T.codes());
check(codes0.length === 11 && codes0.every(c => /^[0-9A-F]{68}$/.test(c)),
  'every tree prints a 68-character genome you can paste back',
  codes0[0] ? codes0[0].slice(0, 20) + '…' : 'none');

const rt = await page.evaluate((c) => {
  const a = window.__T.grow(c);
  const b = window.__T.grow(c.toLowerCase());
  return { a, b };
}, codes0[3]);
check(rt.a.hash === rt.b.hash, 'a pasted genome grows back byte-identical',
  `hash ${rt.a.hash}`);

/* ---- 3. it actually breeds, in the page, with no reload ----------------- */
async function shot(name) {
  const b64 = await page.screenshot({ encoding: 'base64' });
  fs.writeFileSync(path.join(SHOTS, name + '.png'), Buffer.from(b64, 'base64'));
  return b64;
}
async function diff(a, b) {
  return page.evaluate(async (x, y) => {
    const load = async s => { const i = new Image(); i.src = 'data:image/png;base64,' + s; await i.decode(); return i; };
    const g = document.createElement('canvas'); g.width = 400; g.height = 240;
    const c = g.getContext('2d');
    c.drawImage(await load(x), 0, 0, 400, 240);
    const A = c.getImageData(0, 0, 400, 240).data;
    c.clearRect(0, 0, 400, 240);
    c.drawImage(await load(y), 0, 0, 400, 240);
    const B = c.getImageData(0, 0, 400, 240).data;
    let n = 0;
    for (let i = 0; i < A.length; i += 4) {
      if (Math.abs(A[i] - B[i]) + Math.abs(A[i + 1] - B[i + 1]) + Math.abs(A[i + 2] - B[i + 2]) > 24) n++;
    }
    return n / (A.length / 4) * 100;
  }, a, b);
}

await page.evaluate(() => { window.__T.set({ night: false, size: 1 }); window.__T.snap(); });
await new Promise(r => setTimeout(r, 500));
const gen0 = await shot('b0-gen0-day');

/* select through the REAL click path, not by setting state */
const sel = [2, 5, 9];
for (const i of sel) {
  const p = await page.evaluate(k => window.__T.podScreen(k), i);
  await page.mouse.click(p[0], p[1]);
  await new Promise(r => setTimeout(r, 90));
}
const selNow = await page.evaluate(() => window.__T.state.sel.slice());
check(JSON.stringify(selNow) === JSON.stringify(sel), 'clicking a tree keeps it',
  `selected ${selNow.join(',')}`);
await shot('b1-gen0-selected');

const before = await page.evaluate(() => window.__T.codes());
const bred = await page.evaluate(() => window.__T.breed());
await new Promise(r => setTimeout(r, 600));
const after = await page.evaluate(() => window.__T.codes());
check(bred === true, 'BREED produced a generation');
check(await page.evaluate(() => window.__T.state.gen) === 1, 'the generation counter moved');
check(after.slice(0, 3).join() === sel.map(i => before[i]).join(),
  'the ones you picked come back UNCHANGED, in the front slots (elitism)');
const changed = after.slice(3).filter((c, i) => c !== before[i + 3]).length;
check(changed === 8, 'the other eight are new', `${changed}/8 differ`);
const gen1 = await shot('b2-gen1-day');
const d01 = await diff(gen0, gen1);
check(d01 > 6, 'the picture really changed, with no reload', `${d01.toFixed(1)}% of pixels`);

/* run four more generations the way a person would, and watch nothing break */
let lastShot = gen1;
for (let g = 2; g <= 5; g++) {
  const pick = [0, 1 + (g % 3), 6 + (g % 4)];
  await page.evaluate(p => window.__T.select(p), pick);
  await page.evaluate(() => window.__T.breed());
  await new Promise(r => setTimeout(r, 340));
  lastShot = await shot(`b3-gen${g}-day`);
}
const st5 = await page.evaluate(() => ({
  gen: window.__T.state.gen,
  tris: window.__T.trees.map(t => t.tris),
  spec: window.__T.trees.every(t => Math.abs(t.minY) < 1e-4 && Math.abs(t.maxY - 1) < 1e-4),
}));
check(st5.gen === 5, 'five generations in without a reload', `gen ${st5.gen}`);
check(Math.max(...st5.tris) <= 400, 'still inside the triangle ceiling',
  `worst ${Math.max(...st5.tris)}, mean ${(st5.tris.reduce((a, b) => a + b) / 11).toFixed(0)}`);
check(st5.spec, 'still normalised after five generations');

const backOK = await page.evaluate(() => window.__T.back());
await new Promise(r => setTimeout(r, 400));
check(backOK && await page.evaluate(() => window.__T.state.gen) === 4, 'BACK undoes a generation');

/* ---- 3b. NEW TREES: reroll everything you have NOT kept ----------------- */
await page.evaluate(() => window.__T.select([0, 4]));
const beforeNew = await page.evaluate(() => window.__T.codes());
const shotBeforeNew = await shot('b3a-before-new');
await page.evaluate(() => window.__T.newTrees());
await new Promise(r => setTimeout(r, 500));
const afterNew = await page.evaluate(() => window.__T.codes());
check(afterNew[0] === beforeNew[0] && afterNew[4] === beforeNew[4],
  'NEW TREES leaves the kept ones exactly where they were');
const rerolled = afterNew.filter((c, i) => i !== 0 && i !== 4 && c !== beforeNew[i]).length;
check(rerolled === 9, 'NEW TREES rerolls all nine of the others', `${rerolled}/9`);
const newShot = await shot('b3b-after-new');
check(await diff(shotBeforeNew, newShot) > 6, 'and the picture changed for it');
const trisNew = await page.evaluate(() => window.__T.trees.map(t => t.tris));
check(Math.max(...trisNew) <= 400, 'fresh random trees are inside the ceiling too',
  `worst ${Math.max(...trisNew)}`);
check(await page.evaluate(() => window.__T.back()), 'NEW TREES is undoable');
await new Promise(r => setTimeout(r, 350));

/* NEW TREES MUST NEVER COME BACK AROUND.
   The first version seeded each reroll from one byte of the population it was
   replacing, so the seed walked a 256-element set and cycled after 5-21
   presses: hammer the button and it handed back trees you had already seen and
   already saved. A test that pressed it once could not see that. This one
   presses it forty times with nothing kept and demands that no tree, in any
   slot, in any round, ever repeats. */
await page.evaluate(() => window.__T.select([]));
const spin = await page.evaluate(async () => {
  const seen = new Set(), pops = new Set();
  let dupTree = 0, dupPop = 0, rounds = 40;
  for (let r = 0; r < rounds; r++) {
    window.__T.newTrees();
    const codes = window.__T.codes();
    const key = codes.join('|');
    if (pops.has(key)) dupPop++; else pops.add(key);
    for (const c of codes) { if (seen.has(c)) dupTree++; else seen.add(c); }
  }
  return { dupPop, dupTree, unique: seen.size, want: rounds * 11 };
});
check(spin.dupPop === 0, 'forty presses of NEW TREES, forty different populations',
  `${spin.dupPop} repeated`);
check(spin.dupTree === 0, 'and not one repeated tree in 440',
  `${spin.unique}/${spin.want} unique`);

const clash = await page.evaluate(() => {
  const libCodes = new Set(window.__T.lib().map(e => e.code));
  window.__T.newTrees();
  return window.__T.codes().filter(c => libCodes.has(c)).length;
});
check(clash === 0, 'and none of them is a tree already in your library', `${clash} clashes`);
await page.evaluate(() => { window.__T.reset(); });
await new Promise(r => setTimeout(r, 400));

/* ---- 3c. keeping a tree remembers it ------------------------------------ */
await page.evaluate(() => window.__T.libClear());
await page.evaluate(() => window.__T.select([]));
const persists = await page.evaluate(() => window.__T.libPersists());
const keeps = [1, 6];
for (const i of keeps) {
  const p = await page.evaluate(k => window.__T.podScreen(k), i);
  await page.mouse.click(p[0], p[1]);
  await new Promise(r => setTimeout(r, 110));
}
const libNow = await page.evaluate(() => window.__T.lib());
check(libNow.length === 2, 'keeping a tree puts it in the library, with no extra click',
  `${libNow.length} kept${persists ? ', persisted' : ', MEMORY ONLY (localStorage blocked)'}`);
const popCodes = await page.evaluate(() => window.__T.codes());
check(keeps.every((k, n) => libNow[n].code === popCodes[k]),
  'and it is the right tree, by genome');

/* the library shelf shows them, and can send one back to be crossed */
await page.evaluate(() => window.__T.mode('library'));
await new Promise(r => setTimeout(r, 600));
const shelf = await page.evaluate(() => ({
  names: window.__T.trees.map(t => t.name),
  active: Array.from(document.querySelectorAll('.card'))
    .filter(c => c.style.visibility !== 'hidden').length,
}));
check(shelf.names.length === 2 && shelf.active === 2,
  'the library shelf shows exactly what you kept, and no empty pods',
  `${shelf.active} pods, ${shelf.names.join(' + ')}`);
await shot('b3c-library-shelf');

await page.evaluate(c => window.__T.recall(c), libNow[0].code);
await new Promise(r => setTimeout(r, 500));
const recalled = await page.evaluate(() => ({
  mode: window.__T.state.mode, sel: window.__T.state.sel.slice(),
  codes: window.__T.codes(),
}));
check(recalled.mode === 'breed' && recalled.sel.length > 0 &&
      recalled.codes[recalled.sel[recalled.sel.length - 1]] === libNow[0].code,
  'a saved tree can be recalled into the pool, already kept',
  `slot ${recalled.sel[recalled.sel.length - 1]}`);

/* night, and one enlarged, so there are pictures to look at */
await page.evaluate(() => { window.__T.set({ night: true }); window.__T.snap(); });
await new Promise(r => setTimeout(r, 450));
await shot('b4-gen4-night');
await page.evaluate(() => { window.__T.zoomTo(0); window.__T.snap(); });
await new Promise(r => setTimeout(r, 600));
await shot('b5-zoom-with-genome');
const codeShown = await page.evaluate(() => {
  const e = document.getElementById('bigCode');
  return { txt: e.textContent, vis: e.style.display !== 'none' };
});
check(codeShown.vis && /^[0-9A-F]{68}$/.test(codeShown.txt),
  'the enlarged view shows the genome, ready to copy', codeShown.txt.slice(0, 18) + '…');

/* ---- 3d. the roll counter outlives the page ----------------------------- */
const rollsBefore = await page.evaluate(() => window.__T.rolls());
await page.reload({ waitUntil: 'domcontentloaded' });
await page.waitForFunction('!!window.__T && window.__T.frames >= 8', { timeout: 120000 });
await new Promise(r => setTimeout(r, 500));
const rollsAfter = await page.evaluate(() => window.__T.rolls());
check(rollsBefore > 0 && rollsAfter >= rollsBefore,
  'the roll counter survives a reload, so a new session does not repeat the last one',
  `${rollsBefore} -> ${rollsAfter}`);
await page.evaluate(() => { window.__T.mode('breed'); window.__T.set({ night: false, size: 1 }); window.__T.snap(); });
await new Promise(r => setTimeout(r, 500));

/* ---- 4. the hand-built eleven survived ---------------------------------- */
await page.evaluate(() => { window.__T.zoomTo(-1); window.__T.mode('originals'); window.__T.set({ night: false }); window.__T.snap(); });
await new Promise(r => setTimeout(r, 600));
const gal = await page.evaluate(() => ({
  names: window.__T.trees.map(t => `${t.name}:${t.tris}`),
  labelled: document.getElementById('subhead').textContent,
  cards: Array.from(document.querySelectorAll('.card .nm')).map(e => e.textContent),
}));
check(gal.names.length === 11 && gal.names[0].startsWith('TIERED LATHE:180'),
  'switching back rebuilds the hand-built eleven', gal.names[0]);
check(/cannot be bred/i.test(gal.labelled),
  'and the shelf says plainly that they cannot be bred', gal.labelled.slice(0, 60));
check(gal.cards[2] === 'PARASOL', 'the cards carry their real names', gal.cards.slice(0, 3).join(', '));
await shot('b6-originals-shelf');

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
console.log(`  shots          : shots/trees/b*.png`);
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n` : '\n  ALL CHECKS PASSED\n');
process.exit(fails ? 1 : 0);
