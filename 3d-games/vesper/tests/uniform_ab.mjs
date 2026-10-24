/* ONE WORLD, ONE PAGE, ONE UNIFORM MOVED — a trustworthy orbital A/B.

   ablate.mjs cannot do this and it is worth writing down why, because it took a
   wrong conclusion with it. Between variants ablate calls clearUniforms() ->
   build() -> setWorld(), which re-rolls and RE-BAKES the world, and then it
   captures after `frames >= 3`. Three frames is not enough for a rebuilt world
   to settle, so consecutive captures differ for reasons that have nothing to do
   with the uniform. The control proves it: rendering uSkyNight=0.22 twice
   through ablate differs in 91.98% of pixels — MORE than the 64% that had been
   read as the effect of changing it. An instrument noisier than the signal will
   confidently report the signal.

   So: load once, never rebuild, wait until the frame is genuinely stable
   (checked by capturing twice and requiring the two to be identical), then move
   ONE uniform, wait again, capture. Anything that differs is the uniform.

   node tests/uniform_ab.mjs --arch=glacier --seed=HALCYON --u=uSkyNight:0.65,0.22
*/
import puppeteer from 'puppeteer-core';
import fs from 'fs'; import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'shots', 'ab');
fs.mkdirSync(OUT, { recursive: true });
const argv = Object.fromEntries(process.argv.slice(2)
  .map(a => a.replace(/^--/, '').split('=')).map(([k, v]) => [k, v ?? '1']));
const arch = argv.arch || 'glacier', seed = argv.seed || 'HALCYON';
const [uname, ulist] = (argv.u || 'uSkyNight:0.65,0.22').split(':');
const vals = ulist.split(',').map(Number);

const b = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new', args: ['--use-angle=default', '--enable-gpu', '--no-sandbox'],
  defaultViewport: { width: 700, height: 700 }, protocolTimeout: 300000 });
const p = await b.newPage();
p.on('pageerror', e => console.log('  PAGEERROR ' + e.message));
await p.goto(pathToFileURL(path.join(ROOT, 'VESPER.html')).href +
  `?pdb=1&time=9&spin=0&seed=${seed}&arch=${arch}`, { waitUntil: 'domcontentloaded' });
await p.waitForFunction('window.__V && window.__V.frames>4', { timeout: 180000 });
if (await p.evaluate(() => window.__V.renderer.getContext().isContextLost())) {
  console.log('  ABORT: context lost'); await b.close(); process.exit(1);
}

const grab = () => p.evaluate(() => document.getElementById('cv').toDataURL('image/png'));

/* STABLE MEANS TWO CONSECUTIVE CAPTURES ARE THE SAME STRING. Waiting a fixed
   number of seconds is a guess; this is the actual condition. */
const settle = async (label) => {
  let prev = null;
  for (let i = 0; i < 40; i++) {
    await new Promise(r => setTimeout(r, 400));
    const now = await grab();
    if (prev === now) return { ok: true, tries: i, png: now };
    prev = now;
  }
  console.log(`  WARN ${label}: never settled — the A/B below is not trustworthy`);
  return { ok: false, tries: 40, png: prev };
};

const shots = [];
for (const v of vals) {
  const wired = await p.evaluate((n, x) => window.__V.setUniform(n, x), uname, v);
  if (!wired) { console.log(`  ABORT: no such uniform ${uname}`); await b.close(); process.exit(1); }
  const s = await settle(`${uname}=${v}`);
  const f = path.join(OUT, `ab-${arch}-${uname}-${v}.png`);
  fs.writeFileSync(f, Buffer.from(s.png.split(',')[1], 'base64'));
  shots.push({ v, file: f, settled: s.ok, tries: s.tries });
}
await b.close();

const { execFileSync } = await import('child_process');
console.log(`\n  ORBITAL A/B — ${arch}/${seed}, ${uname} ${vals.join(' vs ')}`);
for (const s of shots) console.log(`    ${uname}=${s.v} settled after ${s.tries} polls${s.settled ? '' : ' (NOT SETTLED)'}`);
console.log(execFileSync('python', [path.join(ROOT, 'tools', 'abdiff.py'),
  shots[0].file, shots[1].file], { encoding: 'utf8' }));
