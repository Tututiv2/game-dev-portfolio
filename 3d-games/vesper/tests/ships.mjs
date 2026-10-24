/* THE SHIP LAYER, HEADLESS. No GPU, no browser, no window.

   This is Wave 0's gate. It runs the real grid.js and api.js — the shipping
   files, not a copy — and it exists so that eight agents can work against the
   contract at once and each know within a second whether they broke it.

   It also enforces the purity rule by GREP, the same way HOLLOW does, because
   a single `import * as T from 'three'` in src/ships/ silently ends the ability
   to test any of this without a GPU. GRAFT's guard tripped on its own PROSE
   once, so the scan ignores comments.

   node tests/ships.mjs
*/
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHIPS_DIR = path.join(ROOT, 'src', 'ships');

let pass = 0, fail = 0;
const ok = (cond, name, detail = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}${detail ? '  ' + detail : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  ' + detail : ''}`); }
};
const near = (a, b, tol, name, detail) => ok(Math.abs(a - b) <= tol, name,
  detail !== undefined ? detail : `${a} vs ${b} (tol ${tol})`);

const G = await import(pathToFileURL(path.join(SHIPS_DIR, 'grid.js')).href);
const A = await import(pathToFileURL(path.join(SHIPS_DIR, 'api.js')).href);

console.log('\n  SHIP LAYER — grid model + contract, headless\n');

/* ---- purity ------------------------------------------------------------- */
{
  /* Strip comments before scanning: GRAFT's equivalent guard failed on a
     comment that merely STATED the rule. A guard that cannot tell code from
     prose reports the documentation as the violation. */
  const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const banned = [/\bfrom ['"]three['"]/, /\bdocument\b/, /\bwindow\b/,
                  /Math\.random/, /\bnew Date\b/, /Date\.now/];
  let bad = [];
  for (const f of fs.readdirSync(SHIPS_DIR)) {
    if (!f.endsWith('.js') || f.endsWith('_view.js')) continue;
    const src = strip(fs.readFileSync(path.join(SHIPS_DIR, f), 'utf8'));
    for (const re of banned) if (re.test(src)) bad.push(`${f}: ${re}`);
  }
  ok(bad.length === 0, 'src/ships/ is pure — no three, DOM, random or clock', bad.join(' · '));

  // The guard must be able to FAIL. GRAFT shipped a purity check that could not.
  const probe = strip('const x = 1; // Math.random is fine in a comment\nMath.random();');
  ok(/Math\.random/.test(probe), 'the purity grep can still detect a real violation');
}

/* ---- size classes ------------------------------------------------------- */
{
  const m = G.SIZE_ORDER.map(k => G.SIZE_CLASSES[k].m);
  ok(m.length === 5, 'five size classes exist', m.join(' / ') + ' m');
  let ladder = true;
  for (let i = 1; i < m.length; i++) if (Math.abs(m[i] / m[i - 1] - 5) > 1e-9) ladder = false;
  ok(ladder, 'each class is exactly 5x the last (125 blocks by volume)');
  ok(G.cellM('large') === 2.5 && G.cellM('world') === 62.5,
     'cellM() is the only way to get metres', 'large 2.5 · world 62.5');
}

/* ---- orientation -------------------------------------------------------- */
{
  ok(G.ROTS.length === 24, 'there are 24 orientations', `${G.ROTS.length}`);
  const seen = new Set(G.ROTS.map(r => r.fwd.join() + '|' + r.up.join()));
  ok(seen.size === 24, 'and they are all distinct');
  let orthonormal = true;
  for (const r of G.ROTS) {
    const d = (a, b) => a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
    if (d(r.fwd, r.up) !== 0 || d(r.fwd, r.right) !== 0 || d(r.up, r.right) !== 0)
      orthonormal = false;
    if (d(r.fwd, r.fwd) !== 1 || d(r.up, r.up) !== 1 || d(r.right, r.right) !== 1)
      orthonormal = false;
  }
  ok(orthonormal, 'every orientation is an orthonormal integer basis');
  // rotation must be exact, not trigonometric — no drift after composing
  const v = G.rotVec(7, [1, 2, 3]);
  ok(v.every(Number.isInteger), 'rotating an integer vector stays integer', `[${v}]`);
}

/* ---- placement ---------------------------------------------------------- */
{
  const g = G.makeGrid('large', 'test');
  ok(G.addBlock(g, 'hull', [0, 0, 0]) !== null, 'the first block may be placed anywhere');
  ok(G.addBlock(g, 'hull', [0, 0, 0]) === null, 'a cell cannot hold two blocks');
  ok(G.addBlock(g, 'hull', [9, 9, 9]) === null, 'a detached block is refused');
  ok(G.addBlock(g, 'hull', [1, 1, 0]) === null, 'corner contact is not structure');
  ok(G.addBlock(g, 'hull', [1, 0, 0]) !== null, 'face contact is');

  // a 1x1x2 block must occupy BOTH cells, and block them
  const g2 = G.makeGrid('large');
  G.addBlock(g2, 'thruster', [0, 0, 0], 0);
  ok(g2.blocks.size === 2, 'a 1x1x2 block occupies two cells', `${g2.blocks.size}`);
  ok(G.uniqueBlocks(g2).length === 1, '...but is one block');
  ok(G.blockAt(g2, 0, 0, 1) === G.blockAt(g2, 0, 0, 0),
     '...reachable from either of them, as the same object');
  G.removeBlock(g2, 0, 0, 1);
  ok(g2.blocks.size === 0, 'removing it from either cell removes all of it');
}

/* ---- mass scales with the CUBE of the class ----------------------------- */
{
  const build = (cls) => { const g = G.makeGrid(cls); G.addBlock(g, 'hull', [0,0,0]); return g; };
  const lg = G.mass(build('large')), sm = G.mass(build('small'));
  near(lg, 100, 1e-9, 'one large hull plate is the 100 kg reference');
  near(sm, 100 * Math.pow(0.5 / 2.5, 3), 1e-9,
       'a small one is (0.5/2.5)^3 of it', `${sm.toFixed(3)} kg`);
  const wd = G.mass(build('world'));
  near(wd, 100 * Math.pow(25, 3), 1e-6, 'a world one is 25^3 of it',
       `${(wd / 1000).toFixed(0)} tonnes`);
  ok(wd / lg === 15625, 'so a world block outweighs a large one 15,625:1');
}

/* ---- thrust ------------------------------------------------------------- */
{
  const g = G.makeGrid('large');
  G.addBlock(g, 'hull', [0, 0, 0]);
  // rot 0 has fwd +Z, so the nozzle points -Z and the push is -Z
  G.addBlock(g, 'thruster', [1, 0, 0], 0);
  const back = G.thrustAlong(g, [0, 0, -1]), fwd = G.thrustAlong(g, [0, 0, 1]);
  ok(back > 0 && fwd === 0, 'a thruster pushes one way only', `-Z ${back} · +Z ${fwd}`);
  near(back, 12000, 1e-6, 'and delivers its rated newtons');
}

/* ---- islands: "cut it in half and both halves behave" ------------------- */
{
  const g = G.makeGrid('large');
  for (let x = 0; x < 5; x++) G.addBlock(g, 'hull', [x, 0, 0]);
  ok(G.islands(g).length === 1, 'a connected grid is one island');
  G.removeBlock(g, 2, 0, 0);
  const isl = G.islands(g);
  ok(isl.length === 2, 'cutting it in half makes two', `${isl.length}`);
  ok(isl[0].length === 2 && isl[1].length === 2, 'of two blocks each');
  const halves = G.splitIslands(g);
  ok(halves.length === 2, 'and splitIslands returns two real grids');
  ok(halves.every(h => h.sizeClass === 'large'), '...that keep the size class');
  ok(G.mass(halves[0]) + G.mass(halves[1]) === G.mass(g),
     '...and conserve mass between them');
  ok(g.blocks.size === 4, 'the original grid is left untouched');
}

/* ---- the contract ------------------------------------------------------- */
{
  A.clearShips();
  A.setWorldSeed('VESPER-1');
  const order = [];
  A.registerSystem({ id: 'power',  tick: () => order.push('power') });
  A.registerSystem({ id: 'flight', needs: ['power'], tick: () => order.push('flight') });
  const names = A.systemOrder().map(s => s.id);
  ok(names.indexOf('power') < names.indexOf('flight'),
     'systems tick in dependency order', names.join(' -> '));

  const g = G.makeGrid('large', 'g');
  G.addBlock(g, 'cockpit', [0, 0, 0]);
  const s = A.makeShip('ship-1', g);
  A.step(1 / 60);
  ok(order.join() === 'power,flight', 'and step() honours that order', order.join(' '));

  // determinism: the same ship, the same seed, the same rolls
  A.setWorldSeed('VESPER-1');
  const a1 = [A.rand(s), A.rand(s), A.rand(s)];
  s.data._r = 0;
  A.setWorldSeed('VESPER-1');
  const a2 = [A.rand(s), A.rand(s), A.rand(s)];
  ok(a1.join() === a2.join(), 'api.rand is deterministic for a ship',
     a1.map(x => x.toFixed(4)).join(' '));
  s.data._r = 0;
  A.setWorldSeed('OTHER-SEED');
  const a3 = [A.rand(s), A.rand(s), A.rand(s)];
  ok(a3.join() !== a1.join(), '...and follows the world seed');
  ok(a1.every(x => x >= 0 && x < 1), '...and stays in 0..1');

  /* With no world attached, the world-dependent calls must THROW rather than
     return a plausible zero — a ship that silently never falls is a bug that
     costs a session to find. */
  let threw = false;
  try { A.API.gravity(s); } catch { threw = true; }
  ok(threw, 'with no world attached, gravity throws instead of returning zero');
}

/* ---- the world hooks ----------------------------------------------------- */
{
  A.clearShips();
  // A fake planet: 600 km, and ground 1 km below the datum everywhere. Plain
  // numbers only — if this ever needs three.js, purity has been broken.
  const R = 600000;
  A.attachWorld({
    radius: R,
    mu: 2.0e11,                       // GM, m^3/s^2
    groundR: () => R - 1000,
    quat: [0, 0, 0, 1],               // identity: world frame == planet frame
  });
  const g = G.makeGrid('large', 'g');
  G.addBlock(g, 'cockpit', [0, 0, 0]);
  const s = A.makeShip('probe', g, { pos: [0, R + 5000, 0] });

  const gv = A.API.gravity(s);
  ok(gv[1] < 0 && gv[0] === 0 && gv[2] === 0, 'gravity points at the centre',
     `[${gv.map(x => x.toFixed(4))}]`);
  const gMag = Math.hypot(...gv);
  near(gMag, 2.0e11 / Math.pow(R + 5000, 2), 1e-9, 'and follows inverse square',
       `${gMag.toFixed(3)} m/s^2`);

  // twice as far out must be a quarter the pull
  s.pos = [0, 2 * (R + 5000), 0];
  const far = Math.hypot(...A.API.gravity(s));
  near(far / gMag, 0.25, 1e-9, '...so double the distance is a quarter the pull');

  s.pos = [0, R + 5000, 0];
  near(A.API.altitude(s), 6000, 1e-6,
       'altitude is above the TERRAIN, not the datum', '5 km up + 1 km of valley');

  const w = A.API.toWorld([1, 2, 3]);
  ok(Math.abs(w[0] - 1) < 1e-9 && Math.abs(w[1] - 2) < 1e-9 && Math.abs(w[2] - 3) < 1e-9,
     'toWorld under an identity quaternion is a no-op', `[${w}]`);
  // a 180 degree turn about Y must flip x and z and leave y
  A.attachWorld({ radius: R, mu: 1, groundR: () => R, quat: [0, 1, 0, 0] });
  const t = A.API.toWorld([1, 2, 3]);
  ok(Math.abs(t[0] + 1) < 1e-9 && Math.abs(t[1] - 2) < 1e-9 && Math.abs(t[2] + 3) < 1e-9,
     '...and a real rotation actually rotates', `[${t.map(x => x.toFixed(3))}]`);
}

/* ---- cycles are refused ------------------------------------------------- */
{
  A.registerSystem({ id: 'a', needs: ['b'] });
  A.registerSystem({ id: 'b', needs: ['a'] });
  let threw = false;
  try { A.systemOrder(); } catch { threw = true; }
  ok(threw, 'a dependency cycle throws instead of picking a lucky order');
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
