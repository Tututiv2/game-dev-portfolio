/* ============================================================================
   SHOOT — run the analysis debug page in a real Chrome and write a PNG.

   Following VESPER's convention: launch real Chrome against a real vite server,
   fail loudly on any console error or failed request, and write an image that a
   human then LOOKS AT. Assertions do not catch "the horizon is 40px off" or
   "every prop landed at the same distance"; a picture does.

     node tests/shoot.mjs                # analysis debug sheet
     node tests/shoot.mjs --page=world   # the 3D world itself
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
  process.argv
    .slice(2)
    .map((a) => a.replace(/^--/, '').split('='))
    .map(([k, v]) => [k, v ?? '1']),
);

const PAGE = argv.page || 'debug';
const W = parseInt(argv.w || '1500', 10);
const H = parseInt(argv.h || '1000', 10);

fs.mkdirSync(SHOTS, { recursive: true });

if (!fs.existsSync(CHROME)) {
  console.error(`Chrome not found at ${CHROME}. Pass --chrome=<path> or edit tests/shoot.mjs.`);
  process.exit(1);
}

const server = await createServer({ root: ROOT, server: { port: 5199, strictPort: true } });
await server.listen();

const browser = await puppeteer.launch({
  executablePath: argv.chrome || CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--enable-unsafe-swiftshader', '--use-gl=swiftshader'],
});

const problems = [];
const page = await browser.newPage();
await page.setViewport({ width: W, height: H, deviceScaleFactor: 1 });

page.on('console', (m) => {
  const t = m.type();
  if (t === 'error' || t === 'warning') problems.push(`[console.${t}] ${m.text()}`);
});
page.on('pageerror', (e) => problems.push(`[pageerror] ${e.message}`));
page.on('requestfailed', (r) => problems.push(`[requestfailed] ${r.url()} ${r.failure()?.errorText}`));

const url = `http://localhost:5199/${PAGE}.html`;
console.log(`→ ${url}`);
await page.goto(url, { waitUntil: 'networkidle0', timeout: 60000 });

try {
  await page.waitForFunction(() => document.body.dataset.ready === '1', { timeout: 60000 });
} catch {
  problems.push('[timeout] page never signalled ready');
}

// Let async image decodes inside the debug canvases settle before capturing.
await new Promise((r) => setTimeout(r, 1200));

const file = path.join(SHOTS, `${PAGE}.png`);
await page.screenshot({ path: file, fullPage: PAGE === 'debug' });
console.log(`✓ wrote ${path.relative(ROOT, file)}`);

// Pull the numeric diagnostics out so regressions are greppable, not just visible.
if (PAGE === 'debug') {
  const rows = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.scene')).map((s) => ({
      label: s.querySelector('h2').textContent,
      meta: Array.from(s.querySelectorAll('.meta dt')).map((dt, i) => [
        dt.textContent,
        s.querySelectorAll('.meta dd')[i].textContent.trim(),
      ]),
    })),
  );
  for (const r of rows) {
    console.log(`\n${r.label}`);
    for (const [k, v] of r.meta) console.log(`   ${k.padEnd(12)} ${v}`);
  }
}

await browser.close();
await server.close();

if (problems.length) {
  console.error(`\n${problems.length} problem(s):`);
  for (const p of problems.slice(0, 30)) console.error('  ' + p);
  process.exit(1);
}
console.log('\nno console errors.');
