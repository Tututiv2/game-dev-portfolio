/* One-off: WHY does conformance disagree? Prints GPU vs CPU side by side for a
   handful of directions, and reads five world uniforms straight back out of the
   shader (probe modes 20-24) — the technique that found the last two silent
   bugs. Delete once the divergence is understood. */
import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';
import { makePlanetDef } from '../src/world/planetDef.js';
import { heightNorm } from '../src/world/heightField.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const server = await createServer({ root: ROOT, logLevel: 'error',
  server: { port: 5279, strictPort: true, hmr: false } });
await server.listen();
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 400, height: 400 }, protocolTimeout: 900000,
});
const page = await browser.newPage();
page.on('console', m => console.log(`  [${m.type()}] ${m.text().slice(0, 400)}`));
page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
await page.goto('http://localhost:5279/conformance.html', { waitUntil: 'domcontentloaded' });
await page.waitForFunction('!!window.__C && window.__C.ready', { timeout: 180000 });

const ctx = async (when) => {
  const s = await page.evaluate(() => {
    const gl = window.__C.gl;
    return { lost: gl.isContextLost(), err: gl.getError() };
  });
  console.log(`  context @ ${when}: lost=${s.lost} glError=${s.err}`);
};

const def = makePlanetDef('PROBE', 'volcanic');
const A = def.art;

console.log('\n=== context state ===');
await ctx('after init');
await page.evaluate(() => window.__C.probe(9));
await ctx('after one probe');

console.log('\n=== uniform readback (probe modes 20-24), world PROBE/volcanic ===');
const UNI = [[20, 'uContFreq', A.contFreq], [21, 'uSeedOff.x', def.seedOff[0]],
             [22, 'uCrackFreq', A.crackFreq], [23, 'uMtnFreq', A.mtnFreq],
             [24, 'uFeatParam[0].x', 1 - Math.cos(def.features[0].r)]];
for (const [mode, name, cpu] of UNI) {
  const { gpu } = await page.evaluate(m => window.__C.probe(m), mode);
  console.log(`  ${name.padEnd(16)} gpu ${gpu[0].toFixed(6).padStart(12)}   cpu ${Number(cpu).toFixed(6).padStart(12)}` +
    `   ${Math.abs(gpu[0] - cpu) < 1e-4 ? 'ok' : '  <-- MISMATCH'}`);
}

console.log('\n=== gEps at point of use (mode 9) ===');
{
  const { gpu } = await page.evaluate(() => window.__C.probe(9));
  console.log(`  gEps = ${gpu[0]}`);
}

console.log('\n=== heightAt: GPU vs CPU, 16 directions ===');
const { dirs, gpu } = await page.evaluate(() => window.__C.probe(0));
let sum = 0, sumG = 0, sumC = 0, n = 0;
for (let i = 0; i < 4096; i++) {
  const d = [dirs[i * 3], dirs[i * 3 + 1], dirs[i * 3 + 2]];
  const c = heightNorm(def, d);
  sum += gpu[i] - c; sumG += gpu[i]; sumC += c; n++;
  if (i % 256 === 0) {
    console.log(`  [${String(i).padStart(4)}] gpu ${gpu[i].toFixed(5).padStart(10)}  ` +
      `cpu ${c.toFixed(5).padStart(10)}  diff ${(gpu[i] - c).toFixed(5).padStart(10)}  ` +
      `ratio ${(gpu[i] / c).toFixed(4).padStart(9)}`);
  }
}
console.log(`\n  mean gpu ${(sumG / n).toFixed(5)}   mean cpu ${(sumC / n).toFixed(5)}   ` +
  `mean signed diff ${(sum / n).toFixed(5)}`);

await browser.close();
await server.close();
