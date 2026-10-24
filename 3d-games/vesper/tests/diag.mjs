/* Minimal diagnostic: load the orbit page, dump every console line, every
   page error, and whether __V ever appeared. Use this when shoot.mjs hangs —
   it prints the shader compile log that the shoot swallows. */
import puppeteer from 'puppeteer-core';
import { createServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const server = await createServer({ root: ROOT, logLevel: 'error',
  server: { port: 5274, strictPort: true } });
await server.listen();

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 600, height: 600 },
  protocolTimeout: 300000,
});
const page = await browser.newPage();
page.on('console', m => console.log(`[${m.type()}] ${m.text().slice(0, 4000)}`));
page.on('pageerror', e => console.log('PAGEERROR: ' + e.message + '\n' + (e.stack || '')));
page.on('requestfailed', r => console.log('REQFAIL ' + r.url()));

await page.goto('http://localhost:5274/orbit.html?pdb=1',
  { waitUntil: 'domcontentloaded', timeout: 60000 });
await new Promise(r => setTimeout(r, 25000));

const s = await page.evaluate(() => ({
  hasV: !!window.__V,
  frames: window.__V ? window.__V.frames : -1,
  def: window.__V && window.__V.def ? window.__V.def.key : null,
  progs: window.__V ? window.__V.renderer.info.programs.length : -1,
  calls: window.__V ? window.__V.renderer.info.render.calls : -1,
}));
console.log('\nSTATE: ' + JSON.stringify(s));

await browser.close();
await server.close();
