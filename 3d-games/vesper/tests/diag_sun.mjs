/* IS THE SUN WHERE THE HARNESS SAYS IT IS?

   standshot --sun=35 reported "+35deg DAY" over a frame that is pitch black,
   and --sun=-12 came back BRIGHTER. One of the two is lying and no ground
   judgement can be made until we know which. So read every link in the chain
   straight out of the running page instead of reasoning about frames:

     state.sunAz/sunEl  -> what the solver set
     sunDir (world)     -> what orbit.js builds from it
     uSun  (object)     -> what the shader is actually given, on BOTH the
                           orbital material and the terrain material
     dot(uSun, up)      -> the sun's elevation over the player, in each frame
     dot(uSun, nrm)     -> the lambert term at the player's own feet

   Trap 17: if a uniform is not doing anything, READ IT BACK. Trap 6: a material
   can hold a uniform object that is no longer the one being updated, and it
   fails silently with plausible values.
*/
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const arch = argv.arch || 'volcanic', seed = argv.seed || 'VESPER-1';

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 1280, height: 720 }, protocolTimeout: 300000 });
const p = await b.newPage();
await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
  `?pdb=1&time=9&spin=0&seed=${seed}&arch=${arch}&look=-0.55`, { waitUntil: 'domcontentloaded' });
await p.waitForFunction('window.__V && window.__V.frames>4', { timeout: 180000 });
await p.keyboard.press('l');
await p.waitForFunction('window.__V.state.land>0.999', { timeout: 60000 });
await new Promise(r => setTimeout(r, 4000));

const probe = await p.evaluate(() => {
  const V = window.__V, P = V.planet;
  const T = V.T;
  const out = { sweeps: [] };

  const camWorld = V.camera.position.clone();
  const camLocal = P.group.worldToLocal(camWorld.clone());
  const upWorld = camWorld.clone().normalize();
  const upLocal = camLocal.clone().normalize();
  out.upWorld = upWorld.toArray().map(x => +x.toFixed(4));
  out.upLocal = upLocal.toArray().map(x => +x.toFixed(4));

  const orbU = P.mesh.material.uniforms;
  const terU = P.terrain ? P.terrain.mat.uniforms : null;
  out.sameUniformObject = terU ? (terU === orbU) : null;
  out.sameSunObject = terU ? (terU.uSun === orbU.uSun) : null;

  for (const want of [35, 10, -12, -40]) {
    // exactly the solver standshot/groundshot use
    const az = V.state.az, sunAz = 0.9;
    const w = Math.sin(want * Math.PI / 180);
    let best = null;
    for (let i = 0; i <= 400; i++) {
      const se = -Math.PI / 2 + (i / 400) * Math.PI;
      const sx = Math.cos(se) * Math.sin(az + sunAz);
      const sy = Math.sin(se);
      const sz = Math.cos(se) * Math.cos(az + sunAz);
      const d = sx * upWorld.x + sy * upWorld.y + sz * upWorld.z;
      const e = Math.abs(d - w);
      if (!best || e < best.e) best = { e, se, d };
    }
    V.setSun(sunAz, best.se);
    // force a frame so orbit.js recomputes sunDir and pushes it to the material
    V.frames = 0;
    return { ...out, pending: { want, se: best.se, worldDot: best.d } };
  }
});

// let the page render a few frames with the new sun before reading the uniform
const rows = [];
for (const want of [35, 10, -12, -40]) {
  await p.evaluate((want) => {
    const V = window.__V;
    const upWorld = V.camera.position.clone().normalize();
    const az = V.state.az, sunAz = 0.9;
    const w = Math.sin(want * Math.PI / 180);
    let best = null;
    for (let i = 0; i <= 400; i++) {
      const se = -Math.PI / 2 + (i / 400) * Math.PI;
      const sx = Math.cos(se) * Math.sin(az + sunAz);
      const sy = Math.sin(se);
      const sz = Math.cos(se) * Math.cos(az + sunAz);
      const d = sx * upWorld.x + sy * upWorld.y + sz * upWorld.z;
      const e = Math.abs(d - w);
      if (!best || e < best.e) best = { e, se, d };
    }
    V.setSun(sunAz, best.se);
  }, want);
  await new Promise(r => setTimeout(r, 700));

  const r = await p.evaluate((want) => {
    const V = window.__V, P = V.planet;
    const camWorld = V.camera.position.clone();
    const camLocal = P.group.worldToLocal(camWorld.clone());
    const upWorld = camWorld.clone().normalize();
    const upLocal = camLocal.clone().normalize();
    const orb = P.mesh.material.uniforms.uSun.value;
    const ter = P.terrain ? P.terrain.mat.uniforms.uSun.value : null;
    const deg = (d) => Math.asin(Math.max(-1, Math.min(1, d))) * 180 / Math.PI;
    // the world-space star direction orbit.js builds this frame
    const st = V.state;
    const sw = {
      x: Math.cos(st.sunEl) * Math.sin(st.az + st.sunAz),
      y: Math.sin(st.sunEl),
      z: Math.cos(st.sunEl) * Math.cos(st.az + st.sunAz),
    };
    // mean luminance of the bottom 40% — the near ground
    const c = document.getElementById('cv');
    const t = document.createElement('canvas'); t.width = 320; t.height = 110;
    const g = t.getContext('2d');
    g.drawImage(c, 0, c.height * 0.6, c.width, c.height * 0.4, 0, 0, 320, 110);
    const px = g.getImageData(0, 0, 320, 110).data;
    let sum = 0;
    for (let i = 0; i < px.length; i += 4)
      sum += 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
    return {
      want,
      sunEl: +st.sunEl.toFixed(3),
      worldDeg: +deg(sw.x * upWorld.x + sw.y * upWorld.y + sw.z * upWorld.z).toFixed(1),
      orbLocalDeg: +deg(orb.x * upLocal.x + orb.y * upLocal.y + orb.z * upLocal.z).toFixed(1),
      terLocalDeg: ter ? +deg(ter.x * upLocal.x + ter.y * upLocal.y + ter.z * upLocal.z).toFixed(1) : null,
      orbSun: [orb.x, orb.y, orb.z].map(x => +x.toFixed(3)),
      terSun: ter ? [ter.x, ter.y, ter.z].map(x => +x.toFixed(3)) : null,
      mean: +(sum / (px.length / 4)).toFixed(1),
    };
  }, want);
  rows.push(r);
}

console.log(`\n  ${arch} ${seed} — sun chain readback\n`);
console.log(`  camera up  world ${JSON.stringify(probe.upWorld)}  local ${JSON.stringify(probe.upLocal)}`);
console.log(`  terrain shares the orbital uniform OBJECT: ${probe.sameUniformObject}` +
            `   shares the uSun entry: ${probe.sameSunObject}\n`);
console.log('   want   sunEl   world deg   orbital-local deg   terrain-local deg   near-ground mean');
for (const r of rows)
  console.log(`  ${String(r.want).padStart(5)}  ${String(r.sunEl).padStart(6)}  ` +
    `${String(r.worldDeg).padStart(9)}   ${String(r.orbLocalDeg).padStart(17)}   ` +
    `${String(r.terLocalDeg).padStart(17)}   ${String(r.mean).padStart(16)}`);
console.log('');
for (const r of rows) console.log(`   sun@${r.want}: orbital ${JSON.stringify(r.orbSun)}  terrain ${JSON.stringify(r.terSun)}`);
console.log('');
await b.close();
