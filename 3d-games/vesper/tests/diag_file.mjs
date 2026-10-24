/* Reproduce what happens when the pages are opened by DOUBLE-CLICKING them
   (file:// protocol) rather than through the dev server. */
import puppeteer from 'puppeteer-core';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const browser = await puppeteer.launch({
  executablePath: CHROME, headless: 'new',
  args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--no-sandbox',
         '--allow-file-access-from-files'],
  defaultViewport: { width: 900, height: 700 },
});

for (const page of ['index.html', 'shots/index.html', 'orbit.html']) {
  const p = await browser.newPage();
  const errs = [], reqf = [];
  p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  p.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));
  p.on('requestfailed', r => reqf.push(r.url().slice(0, 110)));

  const url = pathToFileURL(path.join(ROOT, page)).href;
  console.log('\n=== ' + page + ' ===');
  try {
    await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await new Promise(r => setTimeout(r, 3500));
    const state = await p.evaluate(() => ({
      title: document.title,
      imgs: document.images.length,
      imgsBroken: [...document.images].filter(i => !i.complete || i.naturalWidth === 0).length,
      hasCanvas: !!document.querySelector('canvas'),
      hasV: !!window.__V,
      bodyText: document.body.innerText.slice(0, 70).replace(/\n/g, ' | '),
    }));
    console.log('  ' + JSON.stringify(state));
  } catch (e) { console.log('  NAVIGATION FAILED: ' + e.message); }
  console.log('  console errors : ' + errs.length);
  errs.slice(0, 5).forEach(e => console.log('    ! ' + e.slice(0, 200)));
  console.log('  failed requests: ' + reqf.length);
  reqf.slice(0, 5).forEach(e => console.log('    ! ' + e));
  await p.close();
}
await browser.close();
