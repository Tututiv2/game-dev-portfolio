/* THE GROUND GALLERY — all thirteen surfaced worlds, from the player's eye,
   day and night, on one contact sheet.

   CLAUDE.md's item 1 is "judge the ground — 8 of 14 archetypes have never been
   looked at", and until now there was no instrument that could answer it in one
   run. groundshot.mjs shoots from a solved 60-140 m eye, which is right for
   judging TERRAIN and wrong for judging a world you are standing in; standshot
   shoots from eye height but does one world per invocation and, until this
   session, had no way to ask for daylight — the landing site decided.

   So: land on each world the way the player does (press L, let it settle), then
   shoot the SAME spot twice with the star put 35 deg above the player's horizon
   and 12 deg below it. Two frames per world that differ only in where the star
   is, which is the only way to see what the star is worth.

   Every frame carries its own numbers, and they are of the BOTTOM 40% only —
   the near ground, which is the thing being judged. Counting the whole frame
   dilutes it with sky and is how a ground change can look like it did something.

   node tests/groundgallery.mjs [--seed=VESPER-1] [--look=-0.25] [--tag=before]
*/
import puppeteer from 'puppeteer-core';
import fs from 'fs'; import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'shots', 'ground');
fs.mkdirSync(OUT, { recursive: true });
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));

const ALL = ['volcanic', 'glacier', 'oceanic', 'verdant', 'crystal', 'toxic', 'ashen',
             'desert', 'barren', 'tundra', 'inferno', 'fungal', 'shattered'];
const list = argv.arch ? [argv.arch] : ALL;
const seed = argv.seed || 'VESPER-1';
const look = argv.look || '-0.25';
const tag = argv.tag ? `-${argv.tag}` : '';
const LIGHTS = [['day', 35], ['night', -12]];

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 1280, height: 720 }, protocolTimeout: 600000 });
const p = await b.newPage();
p.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));

const setSun = (deg) => p.evaluate((targetDeg) => {
  const V = window.__V;
  const up = V.camera.position.clone().normalize();
  const az = V.state.az, sunAz = 0.9;
  const want = Math.sin(targetDeg * Math.PI / 180);
  let best = null;
  for (let i = 0; i <= 400; i++) {
    const se = -Math.PI / 2 + (i / 400) * Math.PI;
    const d = Math.cos(se) * Math.sin(az + sunAz) * up.x + Math.sin(se) * up.y
            + Math.cos(se) * Math.cos(az + sunAz) * up.z;
    const e = Math.abs(d - want);
    if (!best || e < best.e) best = { e, se };
  }
  V.setSun(sunAz, best.se);
}, deg);

const read = () => p.evaluate(() => {
  const V = window.__V, P = V.planet;
  const c = P.group.worldToLocal(V.camera.position.clone());
  const R = Math.hypot(c.x, c.y, c.z);
  const s = P.uniforms.uSun.value;
  const deg = Math.asin(Math.max(-1, Math.min(1,
    (s.x * c.x + s.y * c.y + s.z * c.z) / R))) * 180 / Math.PI;
  const cv = document.getElementById('cv');
  const t = document.createElement('canvas'); t.width = 640; t.height = 220;
  const g = t.getContext('2d');
  g.drawImage(cv, 0, cv.height * 0.6, cv.width, cv.height * 0.4, 0, 0, 640, 220);
  const px = g.getImageData(0, 0, 640, 220).data, set = new Set();
  let sum = 0, sum2 = 0;
  for (let i = 0; i < px.length; i += 4) {
    const l = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
    sum += l; sum2 += l * l;
    set.add((px[i] << 16) | (px[i + 1] << 8) | px[i + 2]);
  }
  const n = px.length / 4, mean = sum / n;
  return { sunDeg: +deg.toFixed(0), cols: set.size, mean: +mean.toFixed(1),
           sd: +Math.sqrt(Math.max(0, sum2 / n - mean * mean)).toFixed(2),
           png: cv.toDataURL('image/png') };
});

const rows = [];
console.log(`\n  GROUND GALLERY — seed ${seed}, player eye, look ${look}\n`);
console.log('  ' + 'world'.padEnd(11) + '  objects  ' +
  'DAY: sun  colours   mean     sd  |  NIGHT: sun  colours   mean     sd  |  day/night');

for (const arch of list) {
  await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
    `?pdb=1&time=9&spin=0&seed=${seed}&arch=${arch}&look=${look}`,
    { waitUntil: 'domcontentloaded' });
  await p.waitForFunction('window.__V && window.__V.frames>4', { timeout: 180000 });
  if (await p.evaluate(() => window.__V.renderer.getContext().isContextLost())) {
    console.log(`  ${arch.padEnd(11)}  ABORT — context lost`); continue;
  }
  await p.keyboard.press('l');
  await p.waitForFunction('window.__V.state.land>0.999', { timeout: 60000 });
  await new Promise(r => setTimeout(r, 4200));

  // Trap 23 / the underground check: a frame from below the surface is full of
  // edges and scores HIGH, and it is a photograph of a different thing.
  const g = await p.evaluate(() => window.__V.groundInfo());
  const info = await p.evaluate(() => window.__V.objectInfo());
  if (!g || g.alt < 0.5 || g.alt > 400) {
    console.log(`  ${arch.padEnd(11)}  ABORT — not on the surface (alt ${g ? g.alt.toFixed(1) : '?'} m)`);
    continue;
  }

  const cell = {};
  for (const [lname, deg] of LIGHTS) {
    await setSun(deg);
    await new Promise(r => setTimeout(r, 900));
    const r = await read();
    fs.writeFileSync(path.join(OUT, `g-${arch}-${lname}${tag}.png`),
      Buffer.from(r.png.split(',')[1], 'base64'));
    delete r.png;
    cell[lname] = r;
  }
  const ratio = cell.night.mean > 0.01 ? (cell.day.mean / cell.night.mean) : Infinity;
  rows.push({ arch, ...cell, ratio, objects: info ? info.loaded : 0,
              water: g.onWater, nearest: info && info.nearestAny ? info.nearestAny.dist : -1 });
  const f = (x, w) => String(x).padStart(w);
  console.log(`  ${arch.padEnd(11)}${f(info ? info.loaded : 0, 9)}  ` +
    `${f(cell.day.sunDeg, 9)}${f(cell.day.cols, 9)}${f(cell.day.mean, 7)}${f(cell.day.sd, 7)}  | ` +
    `${f(cell.night.sunDeg, 10)}${f(cell.night.cols, 9)}${f(cell.night.mean, 7)}${f(cell.night.sd, 7)}  | ` +
    `${f(ratio.toFixed(2), 10)}${g.onWater ? '  ON WATER' : ''}`);
}
await b.close();

/* THE CONTACT SHEET. Day and night side by side per world, because the whole
   point is the comparison and flicking between two folders is not one. */
const cell = (r, l) => `<figure><img src="g-${r.arch}-${l}${tag}.png" loading="lazy">
  <figcaption>${l} &middot; sun ${r[l].sunDeg >= 0 ? '+' : ''}${r[l].sunDeg}&deg; &middot;
  ${r[l].cols} colours &middot; mean ${r[l].mean} &middot; sd ${r[l].sd}</figcaption></figure>`;
fs.writeFileSync(path.join(OUT, `index${tag}.html`), `<!doctype html>
<title>VESPER ground gallery${tag}</title>
<style>
 body{background:#0b0b0d;color:#cfcfd6;font:14px/1.5 system-ui,sans-serif;margin:24px}
 h1{font-size:18px;font-weight:600} h2{font-size:15px;margin:28px 0 6px;color:#fff}
 .pair{display:grid;grid-template-columns:1fr 1fr;gap:10px}
 figure{margin:0} img{width:100%;display:block;border:1px solid #24242a;border-radius:4px}
 figcaption{font-size:12px;color:#8d8d98;padding:4px 2px}
 .r{color:#ff8f5a} .ok{color:#6ede8a}
</style>
<h1>VESPER ground gallery — seed ${seed}, player eye height, look ${look}</h1>
<p>Same spot in each world, twice: the star 35&deg; above the player's horizon and 12&deg; below it.
Numbers are the bottom 40% of the frame only — the near ground.
<b>day/night under 1.00 means the world is brighter at night.</b></p>
${rows.map(r => `<h2>${r.arch} &mdash; ${r.objects} objects, nearest ${r.nearest.toFixed(1)} m,
 day/night <span class="${r.ratio < 1.25 ? 'r' : 'ok'}">${r.ratio.toFixed(2)}</span>
 ${r.water ? '<span class="r">ON WATER</span>' : ''}</h2>
<div class="pair">${cell(r, 'day')}${cell(r, 'night')}</div>`).join('\n')}
`);
console.log(`\n  wrote shots/ground/index${tag}.html  (${rows.length} worlds)\n`);
