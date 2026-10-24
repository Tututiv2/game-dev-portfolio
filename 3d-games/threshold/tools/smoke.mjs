// Boots the actual game in a real browser, drives it to a few places, and screenshots each one.
//
// The test suite proves the acoustics. This proves the thing RUNS — that no module threw, no
// shader failed to compile, and the cabin is where it is supposed to be. Those are different
// failures and neither suite catches the other's.
//
//   node tools/smoke.mjs

import { spawn } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const SHOTS = resolve(ROOT, 'shots');
const PORT = 5522;

const PUPPETEER = [
  resolve(ROOT, '../VESPER/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js'),
  resolve(ROOT, '../VESPER/node_modules/puppeteer-core/lib/cjs/puppeteer/puppeteer-core.js'),
];
const CHROME = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
];
const firstExisting = list => list.find(p => existsSync(p));

// [label, x, y, z, yaw, extra setup]
const SPOTS = [
  ['00-the-cabin-from-outside', 4.5, 1.0, -7.0, 0, 'overview'],
  ['01-outside-in-the-storm', 4.5, 1.0, -7.0, 0, null],
  ['02-looking-out-the-open-door', 4.5, 1.0, 2.4, Math.PI, 'open-door'],
  ['03-inside-looking-back', 2.0, 1.0, 5.2, Math.PI, 'open-door'],
  ['04-by-the-cracked-window', 2.6, 1.0, 4.1, -Math.PI / 2, 'crack-window'],
  ['05-the-nook', 1.6, 1.0, 3.4, Math.PI / 2, 'open-nook'],
  ['06-the-hearth', 7.0, 1.0, 4.6, Math.atan2(1.0, -3.1), 'shut-all'],
];

async function main() {
  const chrome = firstExisting(CHROME);
  const mod = firstExisting(PUPPETEER);
  if (!chrome || !mod) { console.error('need Chrome and ../VESPER puppeteer-core'); process.exit(2); }
  const puppeteer = (await import('file:///' + mod.replace(/\\/g, '/'))).default;

  if (!existsSync(SHOTS)) mkdirSync(SHOTS);

  const server = spawn(process.execPath, [resolve(HERE, 'serve.mjs'), String(PORT)],
    { stdio: 'ignore', cwd: ROOT });
  const shutdown = () => { try { server.kill(); } catch (e) { /* gone */ } };
  process.on('exit', shutdown);
  await new Promise(r => setTimeout(r, 400));

  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: true,
    args: [
      '--autoplay-policy=no-user-gesture-required',
      '--no-sandbox',
      '--use-gl=angle', '--use-angle=swiftshader',
      '--enable-unsafe-swiftshader',
      '--mute-audio',
      '--window-size=1280,760',
      '--window-position=-32000,-32000',
    ],
  });

  const errors = [];
  let code = 1;
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 760 });
    page.on('pageerror', e => errors.push('pageerror: ' + e.message));
    page.on('console', m => {
      const t = m.text();
      if (m.type() === 'error' && !t.includes('favicon')) errors.push('console: ' + t);
    });

    await page.goto(`http://localhost:${PORT}/?nolock`, { waitUntil: 'networkidle2' });
    await page.waitForSelector('#startbtn', { timeout: 20000 });
    await page.click('#startbtn');
    await page.waitForFunction('!document.querySelector("#title")', { timeout: 60000 });
    await new Promise(r => setTimeout(r, 1200));
    // The HUD is a development instrument, not the picture. Hide it for the shots.
    await page.evaluate(() => window.THRESHOLD.hud.toggle());

    for (const [label, x, y, z, yaw, extra] of SPOTS) {
      await page.evaluate((px, py, pz, pyaw, mode) => {
        const a = window.THRESHOLD;
        a.player.pos = [px, py, pz];
        a.player.yaw = pyaw;
        a.player.pitch = 0;
        const o = a.openings;
        const set = (op, v) => { op.openness = v; op.target = v; };
        set(o.door, 0); set(o.windowWest, 0); set(o.windowNorth, 0); set(o.nookDoor, 0);
        if (mode === 'open-door') set(o.door, 1);
        if (mode === 'crack-window') { set(o.windowWest, 0.22); set(o.door, 1); }
        if (mode === 'open-nook') { set(o.nookDoor, 1); set(o.door, 1); }
        a.freecam = mode === 'overview'
          ? { pos: [-9, 12, -13], yaw: Math.atan2(13.5, 16.5), pitch: -0.60 }
          : null;
        a.weather.snap('storm');
      }, x, y, z, yaw, extra);
      // Let the rain field, the smoothing and the shadow map settle.
      await new Promise(r => setTimeout(r, 900));
      await page.screenshot({ path: resolve(SHOTS, label + '.png') });
      console.log('  shot: ' + label);
    }

    const state = await page.evaluate(() => {
      const a = window.THRESHOLD;
      const f = a.audio.frame;
      return {
        backend: a.audio.describe(),
        volumes: a.graph.volumes.size,
        fills: a.graph.solveCount,
        outsideWeight: f ? f.outsideWeight : null,
        apertures: f ? f.apertures.length : null,
        rt60: f ? f.reverb.rt60 : null,
        audioReady: a.audio.ready,
      };
    });
    console.log('\n  state:', JSON.stringify(state));

    if (errors.length) {
      console.log('\n  ERRORS:');
      for (const e of errors.slice(0, 20)) console.log('   ', e);
    } else {
      console.log('\n  no console or page errors.');
      code = 0;
    }
  } catch (e) {
    console.error('  smoke failed:', e.message);
    for (const err of errors.slice(0, 20)) console.error('   ', err);
  } finally {
    await browser.close();
    shutdown();
  }
  process.exit(code);
}

main();
