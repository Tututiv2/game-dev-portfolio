/* WHAT IS ACTUALLY WITHIN ARM'S REACH — a distance histogram of the objects
   the game has built, split by tier.

   It exists because a picture cannot tell "there is nothing there" from "there
   is something there and it is the same colour as the ground". volcanic's
   foreground reads as a smooth brown blob while the mid-distance is visibly
   speckled, and those are opposite bugs: one is a placement fault, the other is
   a paint fault. This answers which, in numbers, before anything is tuned.

   Reported per band: how many objects the game has BUILT there, and the density
   that implies in items/km2, against what objectKinds.js asked for. A band whose
   delivered density collapses toward the camera is a placement bug; a band that
   delivers what was asked while the picture stays empty is a paint bug.

   Usage: node tests/nearfield.mjs [--arch=volcanic] [--seed=VESPER-1]
          node tests/nearfield.mjs --all
*/
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));

const BANDS = [0, 5, 10, 20, 40, 80, 140, 300, 700, 1200];

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 1280, height: 720 }, protocolTimeout: 300000 });
const p = await b.newPage();

const list = argv.all
  ? ['volcanic', 'glacier', 'oceanic', 'verdant', 'crystal', 'toxic', 'ashen',
     'desert', 'barren', 'tundra', 'inferno', 'fungal', 'shattered']
  : [argv.arch || 'volcanic'];
const seed = argv.seed || 'VESPER-1';

console.log(`\n  near-field census — bands in metres, DELIVERED items/km2\n`);
console.log('  ' + 'world'.padEnd(11) + 'tier'.padEnd(9) +
  BANDS.slice(0, -1).map((lo, i) => `${lo}-${BANDS[i + 1]}`.padStart(9)).join('') +
  '   asked');

for (const arch of list) {
  await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
    `?pdb=1&time=9&spin=0&seed=${seed}&arch=${arch}`, { waitUntil: 'domcontentloaded' });
  await p.waitForFunction('window.__V && window.__V.frames>4', { timeout: 180000 });
  if (await p.evaluate(() => window.__V.renderer.getContext().isContextLost())) {
    console.log(`  ${arch}: ABORT — context lost`); continue;
  }
  await p.keyboard.press('l');
  await p.waitForFunction('window.__V.state.land>0.999', { timeout: 60000 });
  await new Promise(r => setTimeout(r, 4500));

  const r = await p.evaluate((BANDS) => {
    const V = window.__V, t = V.planet.terrain;
    if (!t) return null;
    // The camera in the PLANET's frame — the objects' own frame. Doing this in
    // world space is the tilt bug walk.mjs caught, and it reads as "everything
    // is 8 km away" rather than as an error.
    /* ANGLE, NOT CHORD IN 3D. An object's dir is a UNIT vector; multiplying it
       by the radius puts it on the reference sphere, while the camera sits at
       groundR + eye — and on a world with 23 km of relief that radial gap is
       kilometres, so every object reads as "700 m+ away" no matter where you
       stand. Distance along the surface is the angle between the two directions
       times the radius, which is what a walker actually covers. */
    const c = V.camera.position.clone();
    V.planet.group.worldToLocal(c);
    c.normalize();
    const R = V.planet.def.radius;
    const bands = { scatter: new Array(BANDS.length - 1).fill(0),
                    stand:   new Array(BANDS.length - 1).fill(0) };
    let total = 0, chunks = 0, withObjs = 0, minEdge = 1e9;
    for (const [, ch] of t.chunks) {
      chunks++;
      if (ch.edgeM !== undefined) minEdge = Math.min(minEdge, ch.edgeM);
      if (!ch.objs || !ch.objs.length) continue;
      withObjs++;
      for (const o of ch.objs) {
        if (o.taken) continue;
        total++;
        // objects carry a unit dir in the planet frame; chord distance along
        // the surface is close enough to great-circle at these ranges
        const dot = Math.max(-1, Math.min(1, o.dir[0] * c.x + o.dir[1] * c.y + o.dir[2] * c.z));
        const d = Math.acos(dot) * R;
        const tier = o.id[0] === 's' ? 'scatter' : 'stand';
        for (let i = 0; i < BANDS.length - 1; i++) {
          if (d >= BANDS[i] && d < BANDS[i + 1]) { bands[tier][i]++; break; }
        }
      }
    }
    return { bands, total, chunks, withObjs, minEdge,
             loaded: t.objectCount, alt: V.groundInfo().alt,
             onWater: V.groundInfo().onWater };
  }, BANDS);

  if (!r) { console.log(`  ${arch}: no terrain`); continue; }

  // area of each annulus, km2
  const area = BANDS.slice(0, -1).map((lo, i) =>
    Math.PI * (BANDS[i + 1] ** 2 - lo ** 2) / 1e6);

  for (const tier of ['scatter', 'stand']) {
    const row = r.bands[tier].map((n, i) => (n / area[i]).toFixed(0).padStart(9)).join('');
    console.log(`  ${arch.padEnd(11)}${tier.padEnd(9)}${row}`);
  }
  console.log(`  ${''.padEnd(11)}${'counts'.padEnd(9)}` +
    r.bands.scatter.map((n, i) => `${n}/${r.bands.stand[i]}`.padStart(9)).join('') +
    `   alt ${r.alt.toFixed(1)}m ${r.onWater ? 'WATER' : 'dry'} · ${r.total} objs in ${r.withObjs}/${r.chunks} chunks`);
}
await b.close();
console.log('');
