/* Stand on it AS THE PLAYER and take a picture. groundshot.mjs solves for a
   comfortable eye height and picks 60-140 m, which is right for judging TERRAIN
   and useless for judging things that stand on it — a 20 m tree seen from 136 m
   up is six pixels. This lands with L, walks to the nearest object, and shoots
   from eye height. Usage: node tests/standshot.mjs --arch=verdant [--seed=X]

   --here          shoot from where you LAND rather than walking to an object.
                   gotoObject() stands you ON the thing, i.e. inside its
                   geometry, which is why an un-flagged shot can come back empty
                   with an object reported 2 m away.
   --set=u:v[,u:v] override world uniforms before shooting.
   --sweep=u:a,b,c one shot per value, suffixed with it.

   THE SWEEP IS WHY THIS EXISTS ALONGSIDE ablate.mjs, WHICH CANNOT REPLACE IT:
   ablate renders the ORBITAL sphere, and half the ground's material is in the
   terrain program, which the orbital sphere does not even compile. A term that
   only exists in fragBaked(true) is invisible to every other instrument here.

   It also prints distinct colours / mean / sd of the BOTTOM 40% of the frame —
   the near ground, which is the thing being judged. Counting the whole frame
   dilutes it with sky and horizon, and that is how a change to the ground can
   look like it did something when it did not. */
import puppeteer from 'puppeteer-core';
import fs from 'fs'; import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const arch = argv.arch || 'verdant', seed = argv.seed || 'VESPER-1';
const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 1280, height: 720 }, protocolTimeout: 300000 });
const p = await b.newPage();
await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
  /* --time MATTERS AND IS NOT COSMETIC. Every ground judgement in this project
     had been made on NIGHT frames, where emissive carries the picture and flat
     unlit material hides. The owner looked at a DAYLIT one and it fell apart.
     Shoot both before believing anything about how a world looks. */
  `?pdb=1&time=${argv.time || '9'}&spin=0&seed=${seed}&arch=${arch}&look=${argv.look || '-0.05'}`,
  { waitUntil: 'domcontentloaded' });
await p.waitForFunction('window.__V && window.__V.frames>4', { timeout: 180000 });
/* WHERE YOU LAND DECIDES DAY OR NIGHT, and --time does not: the clock is pinned
   and spin=0 holds the world still, so the sun sits at a fixed angle to the
   surface and the only way to reach the lit side is to descend somewhere else.
   --az/--el aim the orbital camera before L, so a world can be judged in
   DAYLIGHT — which is the condition every ground judgement in this project had
   silently skipped, and the one the owner looked at. */
if (argv.az !== undefined || argv.el !== undefined) {
  await p.evaluate((az, el) => window.__V.setCam(3.15, az, el),
    parseFloat(argv.az || '0'), parseFloat(argv.el || '0'));
  await new Promise(r => setTimeout(r, 1200));
}
await p.keyboard.press('l');
await p.waitForFunction('window.__V.state.land>0.999', { timeout: 60000 });
await new Promise(r => setTimeout(r, 3500));
if (!argv.here) { await p.evaluate(() => window.__V.gotoObject()); await new Promise(r => setTimeout(r, 4000)); }
const info = await p.evaluate(() => window.__V.objectInfo());

/* PROVE THE INSTRUMENT IS ALIVE BEFORE IT REPORTS A NUMBER — trap 13. A lost
   context reads back as zeros, and a uniform name that does not exist sets
   nothing at all while every override still "succeeds". Both failures produce
   a confident, stable, wrong answer. */
const alive = await p.evaluate(() => ({
  lost: window.__V.renderer.getContext().isContextLost(),
  keys: Object.keys(window.__V.planet.mesh.material.uniforms).length,
  // the terrain is supposed to SHARE the world's uniform object; if that ever
  // stops being true, --set would silently only reach the orbital sphere.
  shared: window.__V.planet.terrain
    ? window.__V.planet.terrain.mat.uniforms === window.__V.planet.mesh.material.uniforms
    : null,
}));
if (alive.lost) { console.log('  ABORT: WebGL context lost — any number from here is fiction'); await b.close(); process.exit(1); }

/* ARE WE ACTUALLY STANDING ON IT? A camera that ends up BELOW the surface still
   renders a picture — the underside of the terrain, lit, with the skirts
   hanging down — and it is full of edges, so it scores a HIGH colour count. Two
   such frames were read as "lots of structure" before this check existed. A
   frame taken from underground is not a bad photograph of the world, it is a
   photograph of a different thing, and no judgement may be made from it. */
const g = await p.evaluate(() => window.__V.groundInfo());
if (!g || g.alt < 0.5 || g.alt > 400) {
  console.log(`  ABORT: not standing on the surface (alt ${g ? g.alt.toFixed(1) : '?'} m).` +
              ` Underground frames score HIGH on colour count and mean nothing.`);
  await b.close(); process.exit(1);
}
if (alive.shared === false) console.log('  WARN: terrain does not share the world uniform object; --set may not reach it');

const stats = () => p.evaluate(() => {
  const c = document.getElementById('cv');
  const t = document.createElement('canvas'); t.width = 640; t.height = 220;
  const g = t.getContext('2d');
  g.drawImage(c, 0, c.height * 0.6, c.width, c.height * 0.4, 0, 0, 640, 220);
  const px = g.getImageData(0, 0, 640, 220).data, s = new Set();
  let sum = 0, sum2 = 0;
  for (let i = 0; i < px.length; i += 4) {
    s.add((px[i] << 16) | (px[i + 1] << 8) | px[i + 2]);
    const l = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
    sum += l; sum2 += l * l;
  }
  const n = px.length / 4, mean = sum / n;
  return { colours: s.size, mean, sd: Math.sqrt(Math.max(0, sum2 / n - mean * mean)) };
});

const apply = (spec) => p.evaluate((s) => s.split(',').filter(Boolean).map(kv => {
  const [k, v] = kv.split(':');
  return k + '=' + (window.__V.setUniform(k, parseFloat(v)) ? 'ok' : 'NO SUCH UNIFORM');
}), spec);

/* --sun=<degrees above the PLAYER'S horizon>. Without this, the landing site
   alone decides day or night and there is no way to ask the other one: --time is
   pinned and spin=0 holds the world still, so two seeds were the only lever and
   they are two different worlds. That is trap 22 wearing a different hat, and it
   is why volcanic's foreground was read as bare — the same spot in the same
   world scores 85 colours at night and 11,685 in daylight.

   No closed form: orbit.js builds the star direction in WORLD space from
   (state.az + state.sunAz, state.sunEl) and the camera has its own up, so sweep
   sunEl and keep the best. 400 steps lands inside a quarter of a degree and
   costs nothing. Ported from groundshot.mjs, which has had it since the day it
   was found to be shooting daylit frames labelled "night". */
const setSun = (deg) => p.evaluate((targetDeg) => {
  const V = window.__V;
  const up = V.camera.position.clone().normalize();
  const az = V.state.az, sunAz = 0.9;
  const want = Math.sin(targetDeg * Math.PI / 180);
  let best = null;
  for (let i = 0; i <= 400; i++) {
    const se = -Math.PI / 2 + (i / 400) * Math.PI;
    const sx = Math.cos(se) * Math.sin(az + sunAz);
    const sy = Math.sin(se);
    const sz = Math.cos(se) * Math.cos(az + sunAz);
    const dot = sx * up.x + sy * up.y + sz * up.z;
    const err = Math.abs(dot - want);
    if (!best || err < best.err) best = { err, se };
  }
  V.setSun(sunAz, best.se);
}, deg);

/* WHERE THE SUN ACTUALLY ENDED UP, read out of the uniform the shader is using
   rather than out of the value we asked for — the solver is a sweep and the
   landing site can put the target out of reach. A shot must say which of day and
   night it is a photograph of, or it cannot be compared with any other shot. */
const sunDeg = () => p.evaluate(() => {
  const V = window.__V, P = V.planet;
  /* uSun IS OBJECT SPACE — planetShader.js says so on the uniform's own line.
     The planet group is tilted and spun, so dotting it against a WORLD-space up
     is two different frames and the answer is a plausible, stable, wrong number:
     it reported -29 deg NIGHT for a sun the solver had just put at +35. Take the
     camera into the planet's frame first, exactly as groundshot.mjs does. */
  const c = P.group.worldToLocal(V.camera.position.clone());
  const R = Math.hypot(c.x, c.y, c.z);
  const s = P.uniforms.uSun.value;
  const d = (s.x * c.x + s.y * c.y + s.z * c.z) / R;
  return Math.asin(Math.max(-1, Math.min(1, d))) * 180 / Math.PI;
});

if (argv.sun !== undefined) { await setSun(parseFloat(argv.sun)); await new Promise(r => setTimeout(r, 900)); }

if (argv.set) console.log('  set: ' + (await apply(argv.set)).join(' '));

const shoot = async (suffix) => {
  await new Promise(r => setTimeout(r, 900));
  const st = await stats();
  const d = await p.evaluate(() => document.getElementById('cv').toDataURL('image/png'));
  const f = path.join(ROOT, 'shots', 'ground', `stand-${arch}${suffix}.png`);
  fs.writeFileSync(f, Buffer.from(d.split(',')[1], 'base64'));
  const sd = await sunDeg();
  console.log(`  ${(arch + suffix).padEnd(18)} ${seed.padEnd(10)} ` +
    `sun ${(sd >= 0 ? '+' : '') + sd.toFixed(0)}deg ${sd < 0 ? 'NIGHT' : 'DAY  '} ` +
    `${String(info ? info.loaded : 0).padStart(6)} loaded ` +
    `· ${g.onWater ? `ON WATER(${g.seaDepth.toFixed(0)}m)` : 'dry land   '} ` +
    `· nearest ${info && info.nearestAny ? info.nearestAny.dist.toFixed(1).padStart(6) + ' m' : '     -'}` +
    ` · near-ground ${String(st.colours).padStart(6)} colours  mean ${st.mean.toFixed(1).padStart(5)}  sd ${st.sd.toFixed(2).padStart(6)}` +
    ` -> ${path.basename(f)}`);
};

if (argv.sweep) {
  const [name, list] = argv.sweep.split(':');
  for (const v of list.split(',')) {
    console.log('  ' + (await apply(`${name}:${v}`)).join(' '));
    await shoot(`-${name}-${v}`);
  }
} else {
  await shoot('');
}
await b.close();
