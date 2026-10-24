/* ============================================================================
   WORLD — render generated worlds in a real Chrome and write contact sheets.

   Captures the dive at fixed points (0 = the untouched photo, 1 = standing in
   it) and then looks around from inside. The dive frames are the acceptance
   test for the flat/world pose pairing: at t=0 the geometry must reassemble
   into the source photograph, and any mismatch is instantly visible.

     node tests/world.mjs
     node tests/world.mjs --scene=city
   ========================================================================= */

import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const SHOTS = path.join(ROOT, 'shots');

const argv = Object.fromEntries(
  process.argv.slice(2).map((a) => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']),
);
const W = parseInt(argv.w || '900', 10);
const H = parseInt(argv.h || '560', 10);
const ONLY = argv.scene || null;

fs.mkdirSync(SHOTS, { recursive: true });

const server = await createServer({ root: ROOT, server: { port: 5199, strictPort: true } });
await server.listen();

const browser = await puppeteer.launch({
  executablePath: argv.chrome || CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--enable-unsafe-swiftshader', '--use-gl=swiftshader', '--disable-lcd-text'],
});

const problems = [];
const page = await browser.newPage();
await page.setViewport({ width: W, height: H, deviceScaleFactor: 1 });
page.on('console', (m) => {
  if (m.type() === 'error') problems.push(`[console.error] ${m.text()}`);
});
page.on('pageerror', (e) => problems.push(`[pageerror] ${e.message}`));

await page.goto('http://localhost:5199/index.html', { waitUntil: 'networkidle0' });

const scenes = await page.evaluate(async (only) => {
  const { SCENES, renderScene } = await import('/tests/scenes.js');
  const out = [];
  for (const s of SCENES) {
    if (only && s.id !== only) continue;
    out.push({ id: s.id, label: s.label, url: renderScene(s).toDataURL('image/png') });
  }
  return out;
}, ONLY);

const DIVE_STEPS = [0, 0.4, 0.75, 1];
const LOOKS = [
  { label: 'ahead', yaw: 0, pitch: 0 },
  { label: 'left', yaw: 1.1, pitch: 0 },
  { label: 'down', yaw: 0, pitch: -0.55 },
];

for (const scene of scenes) {
  console.log(`\n=== ${scene.label}`);
  await page.evaluate(async (url) => {
    delete document.body.dataset.ready;
    await window.__sagm.load(url, 'test');
  }, scene.url);

  await page.waitForFunction(() => document.body.dataset.ready === '1', { timeout: 60000 });
  await new Promise((r) => setTimeout(r, 900)); // textures decode

  const info = await page.evaluate(() => {
    const s = window.__sagm.spec;
    return { archetype: s.archetype, props: s.props?.length ?? 0, name: s.name };
  });
  console.log(`   archetype=${info.archetype} props=${info.props}`);

  for (const t of DIVE_STEPS) {
    await page.evaluate((tt) => window.__sagm.setDive(tt), t);
    await new Promise((r) => setTimeout(r, 120));
    await page.screenshot({ path: path.join(SHOTS, `w_${scene.id}_dive${String(t).replace('.', '')}.png`) });
  }

  await page.evaluate(() => window.__sagm.setDive(1));
  for (const l of LOOKS) {
    await page.evaluate((yaw, pitch) => window.__sagm.look(yaw, pitch), l.yaw, l.pitch);
    await new Promise((r) => setTimeout(r, 120));
    await page.screenshot({ path: path.join(SHOTS, `w_${scene.id}_${l.label}.png`) });
  }
  console.log(`   wrote ${DIVE_STEPS.length + LOOKS.length} frames`);
}

await browser.close();
await server.close();

if (problems.length) {
  console.error(`\n${problems.length} problem(s):`);
  for (const p of problems.slice(0, 25)) console.error('  ' + p);
  process.exit(1);
}
console.log('\nno console errors.');
