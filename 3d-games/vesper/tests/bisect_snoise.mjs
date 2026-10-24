/* Trace a CPU/GPU snoise disagreement to the exact step that diverges. */
import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

/* CPU replicas of the same intermediates, straight out of src/core/simplex.js */
const mod289 = (x) => x - Math.floor(x * (1.0 / 289.0)) * 289.0;
const permute = (x) => mod289(((x * 34.0) + 1.0) * x);
const stp = (edge, x) => (x < edge ? 0 : 1);

function setup(vx, vy, vz) {
  const Cx = 1 / 6, Cy = 1 / 3;
  const s = (vx + vy + vz) * Cy;
  const ix = Math.floor(vx + s), iy = Math.floor(vy + s), iz = Math.floor(vz + s);
  const t0 = (ix + iy + iz) * Cx;
  const x0 = [vx - ix + t0, vy - iy + t0, vz - iz + t0];
  const g = [stp(x0[1], x0[0]), stp(x0[2], x0[1]), stp(x0[0], x0[2])];
  const l = [1 - g[0], 1 - g[1], 1 - g[2]];
  const i1 = [Math.min(g[0], l[2]), Math.min(g[1], l[0]), Math.min(g[2], l[1])];
  const i2 = [Math.max(g[0], l[2]), Math.max(g[1], l[0]), Math.max(g[2], l[1])];
  const m = [mod289(ix), mod289(iy), mod289(iz)];
  const pz0 = permute(m[2] + 0), pz1 = permute(m[2] + i1[2]);
  const py0 = permute(pz0 + m[1] + 0), py1 = permute(pz1 + m[1] + i1[1]);
  const p0 = permute(py0 + m[0] + 0), p1 = permute(py1 + m[0] + i1[0]);
  return { i: [ix, iy, iz], i1, i2, p: [p0, p1], x0 };
}

const STEPS = [
  [15, 'x0.x        ', (v) => setup(...v).x0[0]],
  [10, 'i.x  (cell) ', (v) => setup(...v).i[0]],
  [11, 'i1.x        ', (v) => setup(...v).i1[0]],
  [12, 'i2.x        ', (v) => setup(...v).i2[0]],
  [13, 'p.x  (perm) ', (v) => setup(...v).p[0]],
  [14, 'p.y  (perm) ', (v) => setup(...v).p[1]],
];

const server = await createServer({ root: ROOT, logLevel: 'error',
  server: { port: 5280, strictPort: true, hmr: false } });
await server.listen();
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 300, height: 300 }, protocolTimeout: 600000,
});
const page = await browser.newPage();
page.on('pageerror', e => console.log('PAGEERROR: ' + e.message));
await page.goto('http://localhost:5280/conformance.html',
  { waitUntil: 'domcontentloaded', timeout: 120000 });
await page.waitForFunction('!!window.__C && window.__C.ready', { timeout: 180000 });

console.log('\n=== tracing snoise internals, GPU vs CPU ===\n');
for (const [mode, label, cpuFn] of STEPS) {
  const { dirs, gpu } = await page.evaluate((m) => window.__C.probe(m), mode);
  let nBad = 0, first = null;
  for (let i = 0; i < gpu.length; i++) {
    const v = [dirs[i * 3] * 7.3, dirs[i * 3 + 1] * 7.3, dirs[i * 3 + 2] * 7.3];
    const c = cpuFn(v);
    if (Math.abs(c - gpu[i]) > 1e-3) {
      nBad++;
      if (!first) first = { v: v.map(x => +x.toFixed(4)), cpu: c, gpu: gpu[i] };
    }
  }
  console.log(`  ${nBad === 0 ? 'MATCH ' : 'DIFFER'}  ${label}  ${(nBad / gpu.length * 100).toFixed(1)}% differ` +
    (first ? `   e.g. v=(${first.v}) cpu=${first.cpu} gpu=${first.gpu}` : ''));
}

await browser.close();
await server.close();
console.log('');
