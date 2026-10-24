/* Dump everything the single-file build says when opened over file://. */
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = path.join(ROOT, 'VESPER.html');

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox'],
  defaultViewport: { width: 800, height: 600 },
});
const p = await browser.newPage();
p.on('console', m => console.log(`[${m.type()}] ${m.text().slice(0, 600)}`));
p.on('pageerror', e => console.log('PAGEERROR: ' + e.message + '\n' + (e.stack || '').slice(0, 700)));
p.on('requestfailed', r => console.log('REQFAIL ' + r.url().slice(0, 140)));
await p.goto(pathToFileURL(FILE).href, { waitUntil: 'domcontentloaded', timeout: 60000 });
await new Promise(r => setTimeout(r, 8000));
console.log('hasV=' + await p.evaluate(() => !!window.__V));
await browser.close();
