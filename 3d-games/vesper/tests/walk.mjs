/* ============================================================================
   CAN YOU LAND ON IT AND WALK? — drives the REAL key path, on the REAL file.

   Not `setCam(1.002)`. This presses L, waits out the descent, holds W, and
   measures how far across the surface the player actually moved. The whole
   reason this file exists is that the engine could always put a camera on the
   ground while the person holding the keyboard had no way to get there, and
   every ground screenshot in shots/ was taken by a harness teleporting itself.
   A capability nobody can reach is not a capability.

   It runs against VESPER.html over file://, because that is the thing the
   owner double-clicks.
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = path.join(ROOT, 'shots', 'ground');

let fails = 0;
const check = (ok, msg) => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}`); if (!ok) fails++; };

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist', '--no-sandbox'],
  defaultViewport: { width: 1280, height: 720 }, protocolTimeout: 300000,
});
const page = await browser.newPage();
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));

console.log('\n=== land and walk (real keys, real file) ===\n');
await page.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href + '?pdb=1&seed=VESPER-1&arch=volcanic',
  { waitUntil: 'domcontentloaded', timeout: 120000 });
await page.waitForFunction('!!window.__V && window.__V.frames > 4', { timeout: 180000 });

/* Ask the GAME where it thinks it is. Deriving a ground height here from
   state.az/el would ignore the planet group's tilt and report being kilometres
   underground while the camera sat exactly at eye height — which it did. */
const info = () => page.evaluate(() => {
  const V = window.__V, s = V.state, g = V.groundInfo();
  return { ...g, az: s.az, el: s.el, heading: s.heading, eyeM: s.eyeM, frames: V.frames };
});
const radius = (await info()).radius;

const a0 = await info();
check(a0.land < 0.01, `starts in orbit  (land ${a0.land.toFixed(3)}, r/R ${(a0.d / radius).toFixed(2)})`);

/* ---- land ---------------------------------------------------------------- */
/* THE LANDING HITCH. The terrain program is the only one carrying the noise
   library, and it links the first time a chunk is drawn — i.e. during the
   descent. That is the cost of evaluating the crack network live at ground
   scale, and it is the number to watch if the ground shader ever grows. */
await page.evaluate(() => {
  window.__hitch = 0; let prev = performance.now();
  const tick = () => {
    const now = performance.now();
    window.__hitch = Math.max(window.__hitch, now - prev);
    prev = now; requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
});
await page.keyboard.press('l');
await page.waitForFunction('window.__V.state.land > 0.999', { timeout: 60000 });
// let the terrain quadtree stream in before judging the frame
await page.waitForFunction('window.__V.frames > 0', { timeout: 5000 });
await new Promise(r => setTimeout(r, 3500));

const g = await info();
const altM = g.alt;
check(g.land > 0.999, `L lands you  (land ${g.land.toFixed(3)})`);
console.log(`   terrain mesh ${g.terrain ? 'UP' : 'DOWN'} · ${g.built} chunks built, ` +
  `${g.pending} pending · ${g.leaves} leaves wanted · deepest ${g.depth}`);
check(g.terrain && g.built > 20,
  `the terrain QUADTREE is up  (${g.built} chunks, deepest depth ${g.depth})`);
check(Math.abs(altM - g.eyeM) < 1.5,
  `standing at eye height  ${altM.toFixed(2)} m above the ground (asked for ${g.eyeM})`);

const shot = async (name) => {
  const stats = await page.evaluate(() => {
    const cv = document.getElementById('cv');
    const c = document.createElement('canvas');
    c.width = cv.width; c.height = cv.height;
    c.getContext('2d').drawImage(cv, 0, 0);
    const px = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    const seen = new Set(); let n = 0, s = 0, s2 = 0;
    for (let i = 0; i < px.length; i += 4) {
      seen.add((px[i] << 16) | (px[i + 1] << 8) | px[i + 2]);
      const l = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
      n++; s += l; s2 += l * l;
    }
    return { colours: seen.size, mean: s / n, sd: Math.sqrt(Math.max(0, s2 / n - (s / n) ** 2)) };
  });
  const d = await page.evaluate(() => document.getElementById('cv').toDataURL('image/png'));
  fs.writeFileSync(path.join(OUT, name), Buffer.from(d.split(',')[1], 'base64'));
  return stats;
};

const hitch = await page.evaluate(() => window.__hitch);
console.log(`   worst frame during the descent: ${hitch.toFixed(0)} ms ` +
  `(the terrain program links here — it is the one carrying the noise)`);
check(hitch < 1500, `no landing freeze  (worst frame ${hitch.toFixed(0)} ms)`);

const s1 = await shot('walk-landed.png');
console.log(`   landed frame: ${s1.colours} colours, mean ${s1.mean.toFixed(1)}, sd ${s1.sd.toFixed(2)}` +
  `  -> shots/ground/walk-landed.png`);
check(s1.colours > 500 && s1.mean > 4,
  `the landed frame is a scene, not a black screen or a constant`);

/* ---- walk ---------------------------------------------------------------- */
const gcDist = (p, q) => {
  const v = (a) => [Math.cos(a.el) * Math.sin(a.az), Math.sin(a.el), Math.cos(a.el) * Math.cos(a.az)];
  const [x1, y1, z1] = v(p), [x2, y2, z2] = v(q);
  return Math.acos(Math.min(1, x1 * x2 + y1 * y2 + z1 * z2)) * radius;
};

const before = await info();
await page.keyboard.down('w');
const t0 = Date.now();
await new Promise(r => setTimeout(r, 3000));
await page.keyboard.up('w');
const secs = (Date.now() - t0) / 1000;
const after = await info();
const walked = gcDist(before, after);
console.log(`   held W for ${secs.toFixed(2)} s -> travelled ${walked.toFixed(1)} m ` +
  `(${(walked / secs).toFixed(2)} m/s)`);
check(walked > 5, `W actually moves you across the surface`);
check(walked / secs > 3 && walked / secs < 12, `at a walking pace, not a teleport`);

const s2 = await shot('walk-after.png');
check(s2.colours > 500, `still a scene after walking  (${s2.colours} colours)`);

/* the ground must still be under your feet after moving — this is the one that
   catches walking drifting off the height field */
const alt2 = after.alt;
check(Math.abs(alt2 - after.eyeM) < 1.5,
  `still at eye height after walking  ${alt2.toFixed(2)} m`);

/* ---- run ----------------------------------------------------------------- */
const rb = await info();
await page.keyboard.down('Shift');
await page.keyboard.down('w');
await new Promise(r => setTimeout(r, 1500));
await page.keyboard.up('w');
await page.keyboard.up('Shift');
const ra = await info();
const ran = gcDist(rb, ra) / 1.5;
console.log(`   shift+W -> ${ran.toFixed(1)} m/s`);
check(ran > 30, `shift runs, and is clearly faster than walking`);

/* ---- turning ------------------------------------------------------------- */
const preTurn = await info();
const hb = preTurn.heading;
await page.mouse.move(400, 360);
await page.mouse.down();
await page.mouse.move(700, 360, { steps: 10 });
await page.mouse.up();
const ha = await info();
check(Math.abs(ha.heading - hb) > 0.2, `dragging turns your head  (heading ${hb.toFixed(2)} -> ${ha.heading.toFixed(2)})`);
check(Math.abs(ha.az - preTurn.az) < 1e-6 && Math.abs(ha.el - preTurn.el) < 1e-6,
  `...and does NOT slide your body  (az/el unchanged)`);

/* ---- launch -------------------------------------------------------------- */
await page.keyboard.press('l');
await page.waitForFunction('window.__V.state.land < 0.001', { timeout: 60000 });
const up = await info();
check(up.land < 0.001 && up.d / radius > 2, `L launches you back to orbit  (r/R ${(up.d / radius).toFixed(2)})`);

const s3 = await shot('walk-relaunched.png');
check(s3.colours > 500, `orbit still renders after a round trip  (${s3.colours} colours)`);

console.log(`\n  console errors: ${errors.length}`);
errors.slice(0, 6).forEach(e => console.log('    ! ' + e.slice(0, 240)));
if (errors.length) fails++;
await browser.close();
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n` : '\n  all checks passed — you can land on it and walk\n');
process.exit(fails ? 1 : 0);
