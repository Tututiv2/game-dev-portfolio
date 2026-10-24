/* ============================================================================
   IS THE BAKE THIS WORLD, THE RIGHT WAY UP?

   Two failure modes, both silent, both catastrophic, and neither one visible
   in a screenshot unless you already suspect it:

     1. A CUBE FACE IS FLIPPED. The six face orientations are pure convention
        and one wrong sign in cubeDir() tears the world along a seam. On a
        noise-covered planet that reads as "hm, that bit looks odd".
     2. THE BAKE DID NOT RUN. The cubemaps still hold the PREVIOUS world, so
        you get a perfectly plausible planet that is simply the wrong one. This
        is the one that nearly shipped: reusing the render targets across worlds
        took the reported bake time from 1330 ms to 1 ms, which is either a
        fifty-fold win or a fifty-fold lie, and a picture cannot tell you which.

   Both come out as the same number: correlate the BAKED height against the CPU
   height mirror over a few thousand directions. Right world, right way up, and
   they track each other closely. Flip one face and it collapses. Bake the wrong
   world and it goes to zero.

   They will never agree EXACTLY and must not be asked to: the bake is
   band-limited to one texel (that is the whole point of gEps) while the CPU
   mirror evaluates every octave, because what you stand on cannot depend on
   how finely anything was sampled. Correlation is the right instrument here;
   tests/conformance.mjs is the one that demands agreement to a fraction of a
   percent, and it still does.

     node tests/bake_conformance.mjs
   ========================================================================= */
import puppeteer from 'puppeteer-core';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = pathToFileURL(path.join(ROOT, 'VESPER.html')).href;
const PROFILE = path.join(os.tmpdir(), 'vesper-bakeconf-profile');

const launch = () => puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', userDataDir: PROFILE,
  args: ['--use-angle=default', '--enable-gpu', '--ignore-gpu-blocklist'],
  defaultViewport: { width: 900, height: 700 }, protocolTimeout: 900000,
});

/* warm the program cache so the first draw is not a 25 second ANGLE compile */
if (!fs.existsSync(PROFILE)) {
  console.log('\n  warming the shader cache (one time, slow)...');
  const b = await launch(); const p = await b.newPage();
  await p.goto(FILE, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForFunction('!!window.__V && window.__V.frames >= 1', { timeout: 300000 });
  await new Promise(r => setTimeout(r, 2500));
  await b.close();
}

const browser = await launch();
const page = await browser.newPage();
page.on('pageerror', e => console.log('  PAGEERROR: ' + e.message));
page.on('console', m => { if (m.type() === 'error') console.log('  ERR ' + m.text().slice(0, 1800)); });
await page.goto(FILE, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForFunction('!!window.__V && window.__V.frames >= 3', { timeout: 300000 });

const WORLDS = [
  ['VESPER-1', 'volcanic'], ['VESPER-1', 'glacier'], ['VESPER-7', 'ocean'],
  ['ASHFALL', 'crystal'], ['VESPER-1', 'barren'], ['NORTHWIND', 'terran'],
];

console.log('\n  baked cubemap vs the CPU height mirror\n');
console.log('    world                    r      mean|err|   worst face   verdict');

let bad = 0;
for (const [seed, arch] of WORLDS) {
  const res = await page.evaluate((seed, arch, N) => {
    const key = window.__V.setWorld(seed, arch);
    const info = window.__V.bakeInfo();
    if (!info.baked) return { key, skipped: 'not baked (gas giant)' };

    // Fibonacci lattice — the same deterministic spread conformance.mjs uses
    const dirs = new Float32Array(N * 3);
    const ga = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < N; i++) {
      const y = 1 - (i / (N - 1)) * 2;
      const r = Math.sqrt(Math.max(1 - y * y, 0));
      const a = ga * i;
      dirs[i * 3] = Math.cos(a) * r; dirs[i * 3 + 1] = y; dirs[i * 3 + 2] = Math.sin(a) * r;
    }

    // FIRST: does the harness itself line up? Ask for the direction back and
    // check it against what we sent. If this is wrong nothing below means
    // anything, and it presents identically to a broken bake.
    const back = window.__V.probeBaked(dirs, 1);
    let harness = 0;
    for (let i = 0; i < N; i++) harness = Math.max(harness, Math.abs(back[i] - dirs[i * 3]));

    const gpu = window.__V.probeBaked(dirs);
    const def = window.__V.def;
    const cpu = new Float64Array(N);
    const d3 = [0, 0, 0];
    for (let i = 0; i < N; i++) {
      d3[0] = dirs[i * 3]; d3[1] = dirs[i * 3 + 1]; d3[2] = dirs[i * 3 + 2];
      cpu[i] = window.__V.heightNorm(def, d3);
    }

    const stat = (idx) => {
      let n = idx.length, sa = 0, sb = 0;
      for (const i of idx) { sa += cpu[i]; sb += gpu[i]; }
      const ma = sa / n, mb = sb / n;
      let vaa = 0, vbb = 0, vab = 0, err = 0, lo = 1e9, hi = -1e9;
      for (const i of idx) {
        const a = cpu[i] - ma, b = gpu[i] - mb;
        vaa += a * a; vbb += b * b; vab += a * b;
        err += Math.abs(cpu[i] - gpu[i]);
        lo = Math.min(lo, cpu[i]); hi = Math.max(hi, cpu[i]);
      }
      return { r: vab / Math.sqrt(vaa * vbb || 1e-12), err: err / n, spread: hi - lo };
    };

    // Which cube face does each direction land on? A single flipped face shows
    // up here and nowhere else — five good faces drown one bad one in the mean.
    const faces = [[], [], [], [], [], []];
    for (let i = 0; i < N; i++) {
      const x = dirs[i * 3], y = dirs[i * 3 + 1], z = dirs[i * 3 + 2];
      const ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
      faces[ax >= ay && ax >= az ? (x > 0 ? 0 : 1)
            : ay >= az ? (y > 0 ? 2 : 3) : (z > 0 ? 4 : 5)].push(i);
    }
    const all = stat(Array.from({ length: N }, (_, i) => i));
    const per = faces.map(f => (f.length > 40 ? stat(f).r : 1));
    return { key, ms: info.ms, all, per, harness, worst: Math.min(...per),
             worstFace: per.indexOf(Math.min(...per)) };
  }, seed, arch, 4096);

  if (res.skipped) { console.log(`    ${(seed + ' ' + arch).padEnd(24)} ${res.skipped}`); continue; }
  const rel = res.all.err / (res.all.spread || 1);
  if (res.harness > 1e-5) console.log(`      HARNESS BROKEN: direction round-trip off by ${res.harness.toExponential(2)}`);
  const ok = res.all.r > 0.90 && res.worst > 0.85;
  if (!ok) bad++;
  console.log(`    ${(seed + ' ' + res.key).padEnd(24)} ` +
    `${res.all.r.toFixed(4)}   ${(rel * 100).toFixed(2)}% of relief   ` +
    `${res.worst.toFixed(3)} (${['+X', '-X', '+Y', '-Y', '+Z', '-Z'][res.worstFace]})   ` +
    (ok ? 'ok' : '*** BAD ***'));
}

console.log(bad ? `\n  ${bad} world(s) FAILED\n` : '\n  all worlds: baked cube agrees with the CPU height field\n');
await browser.close();
process.exit(bad ? 1 : 0);
