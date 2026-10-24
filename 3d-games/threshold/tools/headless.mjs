// Runs the browser suite with no browser window, so the spectral checks can be part of an ordinary
// "did I break it" loop rather than something you have to remember to look at.
//
// This is a DEV tool and is deliberately the only thing in the project with a dependency: it
// borrows puppeteer-core from a sibling project rather than adding a node_modules here. The game
// itself has no dependencies and no build step, and that stays true.
//
//   node tools/headless.mjs

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const PORT = 5521;

const PUPPETEER = [
  resolve(ROOT, '../VESPER/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js'),
  resolve(ROOT, '../VESPER/node_modules/puppeteer-core/lib/cjs/puppeteer/puppeteer-core.js'),
];
const CHROME = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
];

const firstExisting = list => list.find(p => existsSync(p));

async function main() {
  const chrome = firstExisting(CHROME);
  if (!chrome) { console.error('No Chrome found. Open http://localhost:5520/?test by hand.'); process.exit(2); }

  let puppeteer;
  try {
    const mod = firstExisting(PUPPETEER);
    if (!mod) throw new Error('puppeteer-core not found in ../VESPER/node_modules');
    puppeteer = (await import('file:///' + mod.replace(/\\/g, '/'))).default;
  } catch (e) {
    console.error(`Could not load puppeteer-core (${e.message}).`);
    console.error('Run Play.bat and open http://localhost:5520/?test instead.');
    process.exit(2);
  }

  const server = spawn(process.execPath, [resolve(HERE, 'serve.mjs'), String(PORT)], {
    stdio: 'ignore', cwd: ROOT,
  });
  const shutdown = () => { try { server.kill(); } catch (e) { /* already gone */ } };
  process.on('exit', shutdown);

  await new Promise(r => setTimeout(r, 450));

  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: 'new',
    args: [
      '--autoplay-policy=no-user-gesture-required',
      '--no-sandbox',
      '--disable-gpu',
      '--mute-audio',
    ],
  });

  let code = 1;
  try {
    const page = await browser.newPage();
    page.on('pageerror', e => console.error('  page error:', e.message));
    page.on('console', m => { if (m.type() === 'error') console.error('  console:', m.text()); });

    await page.goto(`http://localhost:${PORT}/?test`, { waitUntil: 'domcontentloaded' });
    // Rendering four seconds of audio through HRTF panners takes a while on a cold JIT.
    await page.waitForFunction('window.__TEST_RESULT !== undefined', { timeout: 180000 });
    const r = await page.evaluate('window.__TEST_RESULT');

    if (process.argv.includes('--all')) {
      for (const g of r.groups) {
        console.log(`\n  ${g.name}`);
        for (const c of g.checks) {
          console.log(`    ${c.ok ? 'PASS' : 'FAIL'}  ${c.label}${c.detail ? `\n            ${c.detail}` : ''}`);
        }
      }
    }
    for (const f of r.failures) console.log(`  FAIL  [${f.group}] ${f.label}\n          ${f.detail}`);
    console.log(`\n  ${r.pass}/${r.pass + r.fail} checks passed${r.fail ? `  — ${r.fail} FAILED` : ''}\n`);
    code = r.fail > 0 ? 1 : 0;
  } catch (e) {
    console.error('  runner failed:', e.message);
  } finally {
    await browser.close();
    shutdown();
  }
  process.exit(code);
}

main();
