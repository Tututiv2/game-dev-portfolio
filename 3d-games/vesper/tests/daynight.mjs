/* DOES THE STAR DO ANYTHING? — day/night contrast on the ground, per world.

   Nobody had ever measured this. Every ground judgement in the project has been
   about how a single frame looks; none asked whether the frame CHANGES when the
   sun comes up. It barely does, and on some worlds it goes the wrong way:
   volcanic is DARKER with the sun 35 deg up than with it 12 deg down.

   The mechanism is in shadeSurf(). The star's whole contribution is capped at
   s.col * 0.92, while the sky term is s.col * uSkyCol * uSkyAmt * (0.35+0.65*night)
   with uSkyAmt defaulting to 3.4 — so the sky alone swings 1.19 (day) to 3.40
   (night), and NIGHT IS THE BRIGHTER SETTING unless the world's nebula colour is
   dark enough to hold it under the sun. The comment above that term states the
   opposite intent in as many words ("the sun is three orders of magnitude
   brighter when it is up, so the day side keeps a third of it"). The code and
   the comment disagree and the code wins.

   Per world it prints the near-ground mean at sun +35 and sun -12, with the sky
   term at its authored value and again at ZERO — because an ablation is the only
   thing that separates "the sun is weak" from "something else is loud", and this
   project has already been burned by believing a null result whose knob was not
   connected (trap 14).

   Usage: node tests/daynight.mjs [--arch=volcanic] [--seed=VESPER-1] [--all]
*/
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));

const ALL = ['volcanic', 'glacier', 'oceanic', 'verdant', 'crystal', 'toxic', 'ashen',
             'desert', 'barren', 'tundra', 'inferno', 'fungal', 'shattered'];
const list = argv.all ? ALL : [argv.arch || 'volcanic'];
const seed = argv.seed || 'VESPER-1';

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 1280, height: 720 }, protocolTimeout: 600000 });
const p = await b.newPage();

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

/* Near-ground mean AND the sun angle the shader actually has, together, so a
   row can never report a brightness against a sun it did not have. */
const read = () => p.evaluate(() => {
  const V = window.__V, P = V.planet;
  const c = P.group.worldToLocal(V.camera.position.clone());
  const R = Math.hypot(c.x, c.y, c.z);
  const s = P.uniforms.uSun.value;
  const deg = Math.asin(Math.max(-1, Math.min(1,
    (s.x * c.x + s.y * c.y + s.z * c.z) / R))) * 180 / Math.PI;
  const cv = document.getElementById('cv');
  const t = document.createElement('canvas'); t.width = 480; t.height = 165;
  const g = t.getContext('2d');
  g.drawImage(cv, 0, cv.height * 0.6, cv.width, cv.height * 0.4, 0, 0, 480, 165);
  const px = g.getImageData(0, 0, 480, 165).data, set = new Set();
  let sum = 0;
  for (let i = 0; i < px.length; i += 4) {
    sum += 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
    set.add((px[i] << 16) | (px[i + 1] << 8) | px[i + 2]);
  }
  return { deg: +deg.toFixed(0), mean: +(sum / (px.length / 4)).toFixed(1), cols: set.size };
});

console.log(`\n  DOES THE STAR DO ANYTHING — near-ground mean, seed ${seed}\n`);
console.log('  ' + 'world'.padEnd(11) +
  '  sky ON: day    night   day/night' +
  '   |  sky OFF: day    night   day/night   sun-only swing');

/* SWEEP MODE — --sweep=uSkyNight:0.65,0.4,0.25. Same landing site, same world,
   one uniform moved. Night COLOURS are printed beside night MEAN because the
   constraint on lowering the night sky is READABILITY, not brightness: the term
   exists because ashen came back as 56 distinct colours in a 1280x720 frame, and
   a fix that restores day/night by making every night frame black again has only
   swapped which half of the clock is broken. */
const sweep = argv.sweep ? (() => {
  const [name, vals] = argv.sweep.split(':');
  return { name, vals: vals.split(',').map(Number) };
})() : null;
if (sweep) console.log('  ' + 'world'.padEnd(11) + sweep.name.padEnd(11) +
  '   day mean  day cols |  night mean  night cols |  day/night');

const rows = [];
for (const arch of list) {
  await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
    `?pdb=1&time=9&spin=0&seed=${seed}&arch=${arch}&look=-0.25`,
    { waitUntil: 'domcontentloaded' });
  await p.waitForFunction('window.__V && window.__V.frames>4', { timeout: 180000 });
  if (await p.evaluate(() => window.__V.renderer.getContext().isContextLost())) {
    console.log(`  ${arch.padEnd(11)}  ABORT — context lost`); continue;
  }
  await p.keyboard.press('l');
  await p.waitForFunction('window.__V.state.land>0.999', { timeout: 60000 });
  await new Promise(r => setTimeout(r, 4200));

  const g = await p.evaluate(() => window.__V.groundInfo());
  if (!g || g.alt < 0.5 || g.alt > 400) {
    console.log(`  ${arch.padEnd(11)}  ABORT — not on the surface (alt ${g ? g.alt.toFixed(1) : '?'})`);
    continue;
  }

  const cell = async () => { await new Promise(r => setTimeout(r, 700)); return read(); };

  if (sweep) {
    for (const v of sweep.vals) {
      const wired = await p.evaluate((n, x) => window.__V.setUniform(n, x), sweep.name, v);
      if (!wired) { console.log(`  ${arch.padEnd(11)}  NO SUCH UNIFORM ${sweep.name}`); break; }
      await setSun(35);  const d = await cell();
      await setSun(-12); const n = await cell();
      const rat = n.mean > 0.01 ? (d.mean / n.mean) : Infinity;
      console.log(`  ${arch.padEnd(11)}${String(v).padEnd(11)}` +
        `${String(d.mean).padStart(11)}${String(d.cols).padStart(10)} | ` +
        `${String(n.mean).padStart(11)}${String(n.cols).padStart(12)} | ` +
        `${rat.toFixed(2).padStart(10)}`);
    }
    continue;
  }

  await setSun(35);  const dOn = await cell();
  await setSun(-12); const nOn = await cell();

  // SKY OFF. Prove the knob moved something before trusting the pair.
  const ok = await p.evaluate(() => window.__V.setUniform('uSkyAmt', 0));
  await setSun(35);  const dOff = await cell();
  await setSun(-12); const nOff = await cell();
  await p.evaluate(() => window.__V.setUniform('uSkyAmt', null) );

  const r = { arch, dOn, nOn, dOff, nOff, wired: ok && Math.abs(dOff.mean - dOn.mean) > 0.05,
              water: g.onWater };
  rows.push(r);
  const f = (x) => String(x.mean).padStart(7);
  const ratio = (a, b) => (b.mean > 0.01 ? (a.mean / b.mean).toFixed(2) : '  inf').padStart(9);
  console.log(`  ${arch.padEnd(11)}${f(dOn)}${f(nOn)}${ratio(dOn, nOn)}` +
    `   |${f(dOff)}${f(nOff)}${ratio(dOff, nOff)}` +
    `${(dOff.mean - nOff.mean).toFixed(1).padStart(16)}` +
    `${r.wired ? '' : '   uSkyAmt NOT WIRED'}${r.water ? '  ON WATER' : ''}`);
}

console.log(`
  day   = star 35 deg ABOVE the player's horizon
  night = star 12 deg BELOW it
  day/night under 1.00 means the world is BRIGHTER AT NIGHT.
  "sun-only swing" is what the star is worth once the sky term is off — if that
  is large while the left-hand ratio is near 1.00, the star is not weak, it is
  being drowned.
`);
await b.close();
