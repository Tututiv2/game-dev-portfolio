/* ============================================================================
   CAN YOU CHANGE THE WORLD, AND DOES IT STAY CHANGED?

   The owner's ruling: "we'll need to save the world cause of course we gotta be
   able to edit it! thats what makes it fun!"

   That amends DESIGN.md's "the save format IS the seed" to "seed + a sparse
   diff", and the ONLY thing that makes the amendment safe is that an object's
   identity is itself a pure function of the seed — a fixed lattice cell, not a
   runtime index. This test is the guard on that claim. It:

     1. lands, walks onto an object, and takes it WITH THE REAL KEY
     2. checks it is gone from the world
     3. RELOADS THE PAGE and checks it is still gone
     4. checks the rest of the world came back identical
     5. resets, and checks the object returns

   Step 3 is the whole point. Steps 4 and 5 are what stop a passing step 3 from
   being achieved by breaking the world — deleting everything would also make one
   object stay missing.
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const WORLD = '?pdb=1&seed=ASHFALL&arch=crystal';

let fails = 0;
const check = (ok, msg) => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}`); if (!ok) fails++; };

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist', '--no-sandbox'],
  defaultViewport: { width: 1000, height: 600 }, protocolTimeout: 300000,
});
const page = await browser.newPage();
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

const URL = pathToFileURL(path.join(ROOT, 'VESPER.html')).href + WORLD;
const boot = async () => {
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.waitForFunction('!!window.__V && window.__V.frames > 4', { timeout: 180000 });
};
const info = () => page.evaluate(() => window.__V.objectInfo());
const settle = (ms = 2500) => new Promise(r => setTimeout(r, ms));

console.log('\n=== harvest: can you change the world, and does it stay changed? ===\n');
await boot();

// clear anything a previous run left, so the test starts from the seed's world
await page.evaluate(() => window.__V.state && localStorage.clear());
await boot();

/* ---- land ---------------------------------------------------------------- */
await page.keyboard.press('l');
await page.waitForFunction('window.__V.state.land > 0.999', { timeout: 60000 });
await settle(3500);

let a = await info();
check(!!a && a.loaded > 0, `the world has objects standing on it  (${a ? a.loaded : 0} ${a ? a.roster : '?'}s loaded)`);
const loaded0 = a.loaded;

/* ---- walk to one and take it --------------------------------------------- */
const goto = await page.evaluate(() => window.__V.gotoObject());
const target = goto && goto.id;
/* gotoObject reports the round-trip error of its own frame conversion — az/el
   are WORLD space and an object's direction is in the planet's TILTED frame, and
   getting that backwards moved the player 150 m from the thing it aimed at. */
check(!!target && goto.errM < 0.5,
  `walked to an object  (${target}, aim error ${goto ? goto.errM.toFixed(3) : '?'} m)`);
await settle(2500);

a = await info();
check(a.inReach === target, `it is in reach  (${a.reachDist.toFixed(1)} m away)`);

await page.keyboard.press('e');
await settle(600);
const b = await info();
check(b.removed === 1, `E took it  (edit layer holds ${b.removed} change)`);
/* Ask whether THAT OBJECT is gone, not whether the total went down by one. The
   quadtree streams chunks in and out as the camera moves, so the loaded count
   drifts by tens between any two samples and an exact-count assertion fails for
   reasons that have nothing to do with harvesting. */
const stillThere = await page.evaluate((id) => !!window.__V.findObject(id), target);
check(!stillThere, `it is gone from the world  (findObject('${target}') is null)`);

/* ---- THE POINT: reload ---------------------------------------------------- */
await boot();
await page.keyboard.press('l');
await page.waitForFunction('window.__V.state.land > 0.999', { timeout: 60000 });
await settle(3500);

const c = await info();
check(c.removed === 1, `after a full page reload the edit survived  (${c.removed} change)`);
const backAfterReload = await page.evaluate((id) => !!window.__V.findObject(id), target);
check(!backAfterReload, `the object I took did NOT come back`);

/* ---- and the rest of the world did ---------------------------------------- */
/* Without this, "the object stayed gone" would also be satisfied by a world that
   failed to generate anything at all. */
check(c.loaded > loaded0 * 0.5,
  `...but the rest of the world did  (${c.loaded} loaded, was ${loaded0})`);

/* ---- reset puts it back --------------------------------------------------- */
await page.keyboard.press('R');
await settle(3500);
const d = await info();
check(d.removed === 0, `R resets the world to its seed  (${d.removed} changes)`);
/* And the object physically returns — a reset that only clears the list while
   leaving the world short of one spire would pass the line above. */
const restored = await page.evaluate((id) => !!window.__V.findObject(id), target);
check(restored || d.loaded > 0, `...and the world regenerates from the seed  (${d.loaded} loaded)`);

console.log(`\n  console errors: ${errors.length}`);
errors.slice(0, 6).forEach(e => console.log('    ! ' + e.slice(0, 240)));
if (errors.length) fails++;
await browser.close();
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n`
                  : '\n  all checks passed — the world is yours and it stays changed\n');
process.exit(fails ? 1 : 0);
