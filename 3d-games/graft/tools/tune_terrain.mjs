// Sweep terrain smoothness against the thing that actually matters:
// how much dry land you can put a building on. A planet you cannot build on
// fails the whole game, however good it looks.
//
//   node tools/tune_terrain.mjs

import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const FILE = join(ROOT, 'app/src/sim/planet.js');
const orig = readFileSync(FILE, 'utf8');

const variants = [
  ['as-is', '5, 2.4', '0.44'],
  ['smoother', '4, 1.9', '0.30'],
  ['rolling', '4, 1.6', '0.22'],
  ['calm', '3, 1.4', '0.18'],
  ['calmer', '3, 1.2', '0.14'],
];

try {
  for (const [name, oct, ridge] of variants) {
    const s = orig
      .replace(/const n = fbm\(dir, [\d.]+, [\d.]+\)/, `const n = fbm(dir, ${oct})`)
      .replace(/\* 0\.78 \+ \(ridge - 0\.5\) \* [\d.]+\)/, `* 0.78 + (ridge - 0.5) * ${ridge})`);
    writeFileSync(FILE, s);
    const tag = '?v=' + encodeURIComponent(name);
    const P = await import(pathToFileURL(FILE).href + tag);
    const { C } = await import(pathToFileURL(join(ROOT, 'app/src/sim/const.js')).href + tag);

    const pts = P.scatter(2500, 0.5);
    const hs = pts.map(d => P.heightAt(d));
    const ocean = hs.filter(h => h < C.planet.sea).length / hs.length;
    const land = pts.filter(d => P.heightAt(d) > C.planet.sea + C.planet.shore);
    const slopes = land.map(d => P.slopeAt(d)).sort((a, b) => a - b);
    const flat = slopes.filter(x => x < 0.35).length / slopes.length;
    console.log(
      name.padEnd(9),
      'buildable ' + (flat * 100).toFixed(0).padStart(3) + '%',
      '| median slope ' + slopes[Math.floor(slopes.length / 2)].toFixed(2),
      '| ocean ' + (ocean * 100).toFixed(0) + '%',
      '| relief ' + (Math.max(...hs) - Math.min(...hs)).toFixed(1),
    );
  }
} finally {
  writeFileSync(FILE, orig);
  console.log('\n(planet.js restored unchanged)');
}
