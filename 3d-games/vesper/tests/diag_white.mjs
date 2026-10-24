/* Compare the SAME scene through the dev server (ES modules) and through the
   built single file (file://, IIFE), at the same size, and screenshot both.
   If only one of them is white, the difference is in the build, not the game. */
import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = path.join(ROOT, 'shots', 'ablate');
fs.mkdirSync(OUT, { recursive: true });

const server = await createServer({ root: ROOT, logLevel: 'error',
  server: { port: 5277, strictPort: true, hmr: false } });
await server.listen();

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 800, height: 800 }, protocolTimeout: 600000,
});

for (const [label, url] of [
  ['devserver', 'http://localhost:5277/orbit.html'],
  ['singlefile', pathToFileURL(path.join(ROOT, 'VESPER.html')).href],
]) {
  const p = await browser.newPage();
  const errs = [];
  p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  p.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));
  await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await p.waitForFunction('!!window.__V && window.__V.frames >= 5', { timeout: 400000 })
    .catch(() => {});

  const info = await p.evaluate(() => {
    const gl = document.getElementById('cv').getContext('webgl2');
    const V = window.__V;
    return {
      frames: V ? V.frames : -1,
      world: V && V.def ? V.def.key : null,
      lost: gl ? gl.isContextLost() : 'no-gl',
      glErr: gl ? gl.getError() : -1,
      // what the composer's final target actually is
      passes: V && V.view ? V.view.composer.passes.length : -1,
      rtType: V && V.view ? V.view.composer.renderTarget1.texture.type : -1,
      halfFloat: !!(document.createElement('canvas').getContext('webgl2')
        && document.createElement('canvas').getContext('webgl2')
             .getExtension('EXT_color_buffer_half_float')),
      exposure: V && V.view ? V.view.renderer.toneMappingExposure : -1,
    };
  }).catch(e => ({ err: e.message }));

  const shot = await p.screenshot({ encoding: 'base64' });
  fs.writeFileSync(path.join(OUT, `white-${label}.png`), Buffer.from(shot, 'base64'));
  const lit = await p.evaluate(async (b64) => {
    const img = new Image(); img.src = 'data:image/png;base64,' + b64; await img.decode();
    const g = document.createElement('canvas'); g.width = 120; g.height = 120;
    const x = g.getContext('2d'); x.drawImage(img, 0, 0, 120, 120);
    const d = x.getImageData(0, 0, 120, 120).data;
    let s = 0; for (let i = 0; i < d.length; i += 4) s += (d[i] + d[i+1] + d[i+2]) / 3;
    return +(s / (d.length / 4)).toFixed(1);
  }, shot);

  console.log(`\n=== ${label} ===`);
  console.log('  ' + JSON.stringify(info));
  console.log('  mean brightness: ' + lit + '   (black space ~ 10-40, white-out ~ 250)');
  console.log('  errors: ' + errs.length);
  errs.slice(0, 5).forEach(e => console.log('    ! ' + e.slice(0, 220)));
  await p.close();
}

await browser.close();
await server.close();
