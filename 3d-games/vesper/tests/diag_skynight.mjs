/* Sweeping uSkyNight from orbit should now be a NO-OP: the term is
   mix(uSkyNight, uSkyNightOrbit, camNear) and camNear saturates to 1 long before
   any gallery distance. It is not a no-op — 64% of the pixels still move — so
   read the chain back instead of reasoning about it (trap 17). */
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 700, height: 700 }, protocolTimeout: 300000 });
const p = await b.newPage();
p.on('pageerror', e => console.log('  PAGEERROR ' + e.message));
p.on('console', m => { if (m.type() === 'error') console.log('  ERR ' + m.text().slice(0, 200)); });
await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
  '?pdb=1&time=9&spin=0&seed=HALCYON&arch=glacier', { waitUntil: 'domcontentloaded' });
await p.waitForFunction('window.__V && window.__V.frames>6', { timeout: 180000 });

const r = await p.evaluate(() => {
  const V = window.__V, P = V.planet;
  const u = P.mesh.material.uniforms;
  const names = Object.keys(u);
  const R = P.def.radius;
  return {
    hasSkyNight: names.includes('uSkyNight'),
    hasSkyNightOrbit: names.includes('uSkyNightOrbit'),
    skyNight: u.uSkyNight ? u.uSkyNight.value : null,
    skyNightOrbit: u.uSkyNightOrbit ? u.uSkyNightOrbit.value : null,
    camAlt: u.uCamAlt ? u.uCamAlt.value : null,
    radius: R,
    camNear: u.uCamAlt ? Math.min(1, u.uCamAlt.value / (R * 0.03)) : null,
    dist: V.state.dist,
    camLen: V.camera.position.length(),
    // does the ORBITAL material's program actually declare it?
    setOk: V.setUniform('uSkyNight', 0.22),
    setOrbOk: V.setUniform('uSkyNightOrbit', 0.65),
    // is the visible mesh the orbital sphere, or is terrain on?
    terrainVisible: P.terrain ? P.terrain.group.visible : null,
  };
});
console.log('\n  ' + JSON.stringify(r, null, 2).replace(/\n/g, '\n  ') + '\n');
await b.close();
