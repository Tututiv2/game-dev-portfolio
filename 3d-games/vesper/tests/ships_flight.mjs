/* FLIGHT, HEADLESS. No GPU, no browser, no puppeteer.

   Runs the real src/ships/flight.js against the real grid.js and api.js. Every
   number asserted here is derived from the blocks in the test's own grid, not
   from a table inside flight.js — an assertion that reads its expectation out of
   the thing it is testing certifies whatever that thing does.

   The gate this file exists for is B2's: "cut it in half and both halves
   behave". It is the block marked THE GATE.

   node tests/ships_flight.mjs
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
const F = await import(pathToFileURL(path.join(SHIPS_DIR, 'flight.js')).href);

console.log('\n  FLIGHT — 6DOF from real grid mass and thrust, headless\n');

/* ---- helpers -------------------------------------------------------------- */
const hyp = (v) => Math.hypot(v[0], v[1], v[2]);
/** ROTS[0] is NOT the identity basis — its `right` is -Y, so a multi-cell block
    at rot 0 grows in directions a test author would not predict. Name the two
    rotations these layouts want, by their actual axes. */
const rotOf = (fwd, up) => G.ROTS.findIndex(r =>
  r.fwd.join() === fwd.join() && r.up.join() === up.join());
const ID = rotOf([0, 0, 1], [0, 1, 0]);       // +X right, +Y up, +Z fwd; pushes -Z
const UP = rotOf([0, -1, 0], [0, 0, 1]);      // nozzle down, so it pushes +Y

const build = (cls, spec, id = 'g') => {
  const g = G.makeGrid(cls, id);
  for (const [type, cell, rot] of spec) {
    const b = G.addBlock(g, type, cell, rot === undefined ? ID : rot);
    if (!b) throw new Error(`test grid is wrong: ${type} at ${cell} was refused`);
  }
  return g;
};
/** metres, grid-local, centre of one placed block — computed HERE, from grid.js. */
const blockCentre = (grid, b) => {
  const cells = G.cellsOf(b.type, b.cell, b.rot), s = G.cellM(grid.sizeClass);
  const p = [0, 0, 0];
  for (const c of cells) for (let i = 0; i < 3; i++) p[i] += (c[i] + 0.5) * s / cells.length;
  return p;
};

/* A ship that can fly: hull spine, a reactor, a cockpit, one -Z thruster. */
const SHIP_SPEC = [
  ['hull', [0, 0, 0]], ['hull', [1, 0, 0]], ['hull', [2, 0, 0]],
  ['reactor', [0, 1, 0]],
  ['cockpit', [2, 1, 0]],
  ['thruster', [3, 0, 0]],
];

/* ---- purity + the size-class law ------------------------------------------ */
{
  const src = fs.readFileSync(path.join(SHIPS_DIR, 'flight.js'), 'utf8');
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const banned = [/\bfrom ['"]three['"]/, /\bdocument\b/, /\bwindow\b/,
                  /Math\.random/, /\bnew Date\b/, /Date\.now/];
  ok(!banned.some(re => re.test(code)), 'flight.js is pure');
  ok(!/2\.5/.test(code), 'flight.js never hardcodes 2.5 — it calls cellM()');
  ok(/cellM\(/.test(code), '...and it does call cellM()');
}

/* ---- straight line: a = F/m, from the blocks that are there ---------------- */
A.clearShips();
A.setWorldSeed('VESPER-1');
A.attachWorld(null);            // no planet: isolates thrust from gravity
{
  const g = build('large', SHIP_SPEC, 'g1');
  const s = A.makeShip('s1', g);
  const m = G.mass(g), T = G.thrustAlong(g, [0, 0, -1]);
  near(m, 8660, 1e-9, 'the test ship masses what its blocks mass', `${m} kg`);
  near(T, 12000, 1e-9, 'and carries one thruster of rated push', `${T} N`);

  F.command(s, [0, 0, -1], null);
  F.setDamp(s, false);
  const dt = 1 / 60;
  A.step(dt);
  const a = hyp(s.vel) / dt;
  near(a, T / m, 1e-9, 'one tick accelerates it by exactly thrust/mass',
       `${a.toFixed(6)} m/s^2`);
  ok(s.vel[2] < 0 && Math.abs(s.vel[0]) < 1e-12 && Math.abs(s.vel[1]) < 1e-12,
     '...along the axis the nozzle points, and no other', `[${s.vel.map(x => x.toFixed(5))}]`);
  ok(s.data.flight.noWorld === true && s.data.flight.control === 'ok',
     'with no planet attached it still flies, and SAYS gravity is missing',
     `noWorld ${s.data.flight.noWorld} · control ${s.data.flight.control}`);

  // the same ship, half throttle, is half the acceleration
  s.vel = [0, 0, 0];
  F.command(s, [0, 0, -0.5], null);
  A.step(dt);
  near(hyp(s.vel) / dt, 0.5 * T / m, 1e-9, 'half throttle is half the acceleration');

  // a command beyond the contract's -1..1 is clamped, not obeyed
  s.vel = [0, 0, 0];
  F.command(s, [0, 0, -5], null);
  A.step(dt);
  near(hyp(s.vel) / dt, T / m, 1e-9, 'a command past -1..1 clamps instead of cheating');
}

/* ---- the size ladder: nothing hardcodes the large grid --------------------- */
{
  A.clearShips();
  const mk = (cls, id) => {
    const s = A.makeShip(id, build(cls, SHIP_SPEC, id));
    F.command(s, [0, 0, -1], null); F.setDamp(s, false);
    return s;
  };
  const big = mk('large', 'big'), sml = mk('small', 'sml');
  const dt = 1 / 60;
  A.step(dt);
  const aBig = hyp(big.vel) / dt, aSml = hyp(sml.vel) / dt;
  // thrust goes as area (s^2), mass as volume (s^3), so acceleration goes as 1/s
  const expect = G.cellM('large') / G.cellM('small');
  near(aSml / aBig, expect, 1e-9,
       'the identical ship at a smaller class accelerates by exactly the class ratio',
       `${aSml.toFixed(4)} / ${aBig.toFixed(4)} = ${(aSml / aBig).toFixed(4)}x`);
}

/* ---- dt is CLAMPED --------------------------------------------------------- */
{
  A.clearShips();
  const mk = (id) => {
    const s = A.makeShip(id, build('large', SHIP_SPEC, id));
    F.command(s, [0, 0, -1], null); F.setDamp(s, false);
    return s;
  };
  const slow = mk('slow'), fine = mk('fine');
  F.stepShip(slow, 10, A.API);        // a 10-second frame: a stalled machine
  F.stepShip(fine, F.MAX_DT, A.API);
  ok(hyp(slow.vel) === hyp(fine.vel),
     'a 10 s frame is stepped as MAX_DT — a slow machine gets the same game',
     `${hyp(slow.vel).toFixed(6)} m/s both`);
  const raw = (G.thrustAlong(build('large', SHIP_SPEC, 'x'), [0, 0, -1]) / 8660) * 10;
  ok(Math.abs(hyp(slow.vel) - raw) > 1,
     '...and the check can FAIL: an unclamped dt would give a wildly different number',
     `clamped ${hyp(slow.vel).toFixed(3)} vs raw ${raw.toFixed(3)} m/s`);
  ok(F.MAX_DT <= 1 / 30 + 1e-12, 'MAX_DT is at most a 30 Hz step', `${F.MAX_DT}`);
}

/* ---- a thruster off the centre of mass induces spin ------------------------ */
{
  const spinFor = (ax, withGyro) => {
    A.clearShips();
    const spec = [
      ['hull', [0, 0, 0]], ['hull', [1, 0, 0]], ['hull', [2, 0, 0]], ['hull', [3, 0, 0]],
      ['reactor', [0, 1, 0]], ['cockpit', [2, 1, 0]],
      ['thruster', [ax, -1, 0]],
    ];
    if (withGyro) spec.push(['gyro', [3, 1, 0]]);   // clear of the reactor at x0-1
    const g = build('large', spec, `t${ax}${withGyro ? 'g' : ''}`);
    const s = A.makeShip(`t${ax}${withGyro ? 'g' : ''}`, g);
    F.command(s, [0, 0, -1], null);
    F.setDamp(s, !!withGyro);
    for (let i = 0; i < 30; i++) A.step(1 / 60);
    const thr = G.uniqueBlocks(g).find(b => b.type === 'thruster');
    const com = G.centreOfMass(g), c = blockCentre(g, thr);
    return { spin: s.spin, r: [c[0] - com[0], c[1] - com[1], c[2] - com[2]] };
  };

  const left = spinFor(0, false), right = spinFor(3, false);
  ok(hyp(left.spin) > 1e-6 && hyp(right.spin) > 1e-6,
     'thrust that misses the centre of mass makes the ship spin',
     `${hyp(left.spin).toFixed(4)} · ${hyp(right.spin).toFixed(4)} rad/s`);
  // push is -Z, so tau = (-ry*F, +rx*F, 0): the Z component must be exactly zero
  ok(left.spin[2] === 0 && right.spin[2] === 0,
     '...about the two axes the cross product allows, and never the third',
     `spin.z ${left.spin[2]} / ${right.spin[2]}`);
  ok(Math.sign(left.spin[1]) === Math.sign(left.r[0]) &&
     Math.sign(right.spin[1]) === Math.sign(right.r[0]),
     '...and moving the thruster to the far side of the CoM reverses the yaw',
     `rx ${left.r[0].toFixed(2)} -> ${left.spin[1].toExponential(2)} · ` +
     `rx ${right.r[0].toFixed(2)} -> ${right.spin[1].toExponential(2)}`);
  ok(Math.sign(left.spin[0]) === -Math.sign(left.r[1]) &&
     Math.sign(right.spin[0]) === -Math.sign(right.r[1]),
     '...and the pitch follows -ry, as r x F says it must');

  const gyroed = spinFor(0, true);
  ok(hyp(gyroed.spin) < 1e-9 && hyp(left.spin) > 1e-6,
     'a GYRO holds it straight — and without one, nothing does',
     `gyro ${hyp(gyroed.spin).toExponential(2)} vs none ${hyp(left.spin).toFixed(4)} rad/s`);
}

/* ---- attitude command turns the ship, and only with gyros ------------------ */
{
  const turn = (withGyro) => {
    A.clearShips();
    const spec = [...SHIP_SPEC];
    if (withGyro) spec.push(['gyro', [2, 2, 0]]);
    const g = build('large', spec, `y${withGyro}`);
    const s = A.makeShip(`y${withGyro}`, g);
    F.command(s, null, [0, 1, 0]);      // yaw
    F.setDamp(s, false);
    for (let i = 0; i < 60; i++) A.step(1 / 60);
    return s;
  };
  const withG = turn(true), without = turn(false);
  ok(Math.abs(withG.spin[1]) > 1e-3,
     'a yaw command with a gyro aboard actually yaws', `${withG.spin[1].toFixed(4)} rad/s`);
  ok(hyp(without.spin) === 0,
     '...and with no gyro welded on, the command does nothing at all — the capability is the block');
  const q = withG.ori;
  ok(Math.abs(Math.hypot(q[0], q[1], q[2], q[3]) - 1) < 1e-9,
     'the orientation stays a unit quaternion after a second of integrating',
     `|q| = ${Math.hypot(q[0], q[1], q[2], q[3]).toFixed(12)}`);
  ok(Math.abs(q[1]) > 1e-3 && Math.abs(q[0]) < 1e-12 && Math.abs(q[2]) < 1e-12,
     '...and it turned about Y and nothing else', `[${q.map(x => x.toFixed(5))}]`);
}

/* ---- the world: falling, hovering, and what a cockpit is for --------------- */
{
  const R = 600000;
  A.attachWorld({ radius: R, mu: 1.0 * R * R, groundR: () => R, quat: [0, 0, 0, 1] });
  ok(UP >= 0 && G.rotVec(UP, [0, 0, -1]).join() === '0,1,0',
     'a rotation exists whose nozzle pushes +Y', `rot ${UP}`);

  const HOVER = [
    ['hull', [0, 0, 0]],
    ['thruster', [0, -1, 0], UP],
    ['reactor', [1, 0, 0]],
    ['cockpit', [0, 1, 0]],
    ['gyro', [0, 2, 0]],
  ];
  const ALT = R + 1000;
  const gExp = (1.0 * R * R) / (ALT * ALT);   // inverse square, at the ship's own r
  const mk = (id, spec) => A.makeShip(id, build('large', spec, id), { pos: [0, ALT, 0] });

  A.clearShips();
  const fall = mk('fall', HOVER);
  F.setDamp(fall, false);
  for (let i = 0; i < 60; i++) A.step(1 / 60);
  near(fall.vel[1], -gExp, 2e-3, 'with the dampeners off it falls at g',
       `${fall.vel[1].toFixed(4)} m/s after 1 s vs g ${-gExp.toFixed(4)}`);

  A.clearShips();
  const hover = mk('hover', HOVER);
  const m = G.mass(hover.grid), T = G.thrustAlong(hover.grid, [0, 1, 0]);
  ok(T > m * gExp, 'the hover ship out-thrusts its own weight',
     `${T} N vs ${(m * gExp).toFixed(0)} N`);
  const y0 = hover.pos[1];
  for (let i = 0; i < 300; i++) A.step(1 / 60);
  ok(Math.abs(hover.pos[1] - y0) < 0.01 && hyp(hover.vel) < 0.01,
     'with them on it hovers — the dampener fights gravity with real thrust',
     `drift ${(hover.pos[1] - y0).toExponential(2)} m over 5 s`);
  ok(hover.data.flight.thrustN > 0 && hover.data.flight.thrustN < T,
     '...burning less than full throttle to do it',
     `${hover.data.flight.thrustN.toFixed(0)} of ${T} N`);

  // and a ship too heavy to hover sinks instead of cheating
  A.clearShips();
  const heavy = mk('heavy', [...HOVER, ['hull', [3, 0, 0]],
                             ['reactor', [3, 1, 0]], ['reactor', [3, -2, 0]]]);
  const hy0 = heavy.pos[1];
  for (let i = 0; i < 120; i++) A.step(1 / 60);
  ok(G.mass(heavy.grid) * gExp > G.thrustAlong(heavy.grid, [0, 1, 0]) &&
     heavy.pos[1] < hy0 - 0.5,
     'a ship whose weight beats its thrust sinks — the dampener is not a cheat',
     `${(heavy.pos[1] - hy0).toFixed(2)} m in 2 s`);

  // no cockpit: no command, no dampener, still falls
  A.clearShips();
  // the gyro hangs off the cockpit, so it goes with it — this is the bare hull
  const debris = mk('debris', HOVER.filter(b => b[0] !== 'cockpit' && b[0] !== 'gyro'));
  F.command(debris, [0, 1, 0], [1, 1, 1]);
  for (let i = 0; i < 60; i++) A.step(1 / 60);
  ok(debris.data.flight.control === 'no-cockpit' && debris.data.flight.thrustN === 0,
     'with no cockpit nothing obeys the stick, and the reason is published',
     debris.data.flight.control);
  near(debris.vel[1], -gExp, 2e-3, '...but gravity still has it', `${debris.vel[1].toFixed(4)} m/s`);

  // no reactor: the thrusters have nothing to burn
  A.clearShips();
  const dark = mk('dark', HOVER.filter(b => b[0] !== 'reactor'));
  F.command(dark, [0, 1, 0], null);
  A.step(1 / 60);
  ok(dark.data.flight.control === 'no-power' && dark.data.flight.thrustN === 0,
     'and with no reactor the thrusters have nothing to burn', dark.data.flight.control);
}

/* ============================================================================
   THE GATE — "cut it in half and both halves behave"
   ========================================================================= */
{
  A.attachWorld(null);
  A.clearShips();
  /* One hull spine x=0..6. Left of the cut carries one thruster, right carries
     two, and each side has its own reactor and cockpit. Cut at x=3. */
  const SHIP = [
    ['hull', [0, 0, 0]], ['hull', [1, 0, 0]], ['hull', [2, 0, 0]], ['hull', [3, 0, 0]],
    ['hull', [4, 0, 0]], ['hull', [5, 0, 0]], ['hull', [6, 0, 0]],
    ['reactor', [0, 1, 0]], ['cockpit', [2, -1, 0]], ['thruster', [0, -1, 0]],
    ['reactor', [4, 1, 0]], ['cockpit', [6, 1, 0]], ['thruster', [4, -1, 0]],
    ['thruster', [6, -1, 0]],
  ];
  const g = build('large', SHIP, 'whole');
  const parent = A.makeShip('whole', g, { pos: [100, 200, 300] });
  parent.vel = [5, 0, 0];
  parent.spin = [0, 0, 2];                       // spinning about Z when it breaks
  const mWhole = G.mass(g), tWhole = G.thrustAlong(g, [0, 0, -1]);
  ok(G.islands(g).length === 1, 'the whole ship is one island', `${mWhole} kg, ${tWhole} N`);

  G.removeBlock(g, 3, 0, 0);                     // the cut
  const halves = G.splitIslands(g);
  ok(halves.length === 2, 'cutting it in half gives two grids', `${halves.length}`);

  const one = halves.find(h => G.uniqueBlocks(h).filter(b => b.type === 'thruster').length === 1);
  const two = halves.find(h => G.uniqueBlocks(h).filter(b => b.type === 'thruster').length === 2);
  ok(!!one && !!two, 'one half keeps one thruster, the other keeps two');

  const kids = [one, two].map((h, i) => A.makeShip(`half${i}`, h));
  F.default.onSplit(parent, kids, A.API);

  // each half inherits where it was and how fast it was going, plus w x r
  const pCom = G.centreOfMass(g);
  for (const k of kids) {
    const c = G.centreOfMass(k.grid);
    const off = [c[0] - pCom[0], c[1] - pCom[1], c[2] - pCom[2]];
    const v = [parent.spin[1] * off[2] - parent.spin[2] * off[1],
               parent.spin[2] * off[0] - parent.spin[0] * off[2],
               parent.spin[0] * off[1] - parent.spin[1] * off[0]];
    near(k.vel[0], parent.vel[0] + v[0], 1e-9, `half "${k.id}" keeps the parent's momentum`);
    near(k.vel[1], parent.vel[1] + v[1], 1e-9,
         `...plus the velocity its own offset had from the spin`, `${k.vel[1].toFixed(3)} m/s`);
    near(k.pos[0], parent.pos[0] + off[0], 1e-9, `...and separates by its own offset`);
  }
  ok(Math.sign(kids[0].vel[1]) === -Math.sign(kids[1].vel[1]),
     'so a spinning ship throws its two halves in opposite directions',
     `${kids[0].vel[1].toFixed(3)} vs ${kids[1].vel[1].toFixed(3)} m/s`);

  // THE GATE ITSELF: each half now flies on its OWN remaining thrust and mass
  const dt = 1 / 60;
  const accels = kids.map((k) => {
    k.vel = [0, 0, 0]; k.spin = [0, 0, 0]; k.ori = [0, 0, 0, 1];
    F.command(k, [0, 0, -1], null); F.setDamp(k, false);
    A.step(dt);
    return hyp(k.vel) / dt;
  });
  /* The oracle: computed here, from grid.js, on the CHILD grid. */
  const holds = (a, grid) =>
    Math.abs(a - G.thrustAlong(grid, [0, 0, -1]) / G.mass(grid)) <= 1e-9;
  kids.forEach((k, i) => ok(holds(accels[i], k.grid),
    `THE GATE: half "${k.id}" flies on its own thrust and its own mass`,
    `${G.thrustAlong(k.grid, [0, 0, -1])} N / ${G.mass(k.grid)} kg = ` +
    `${accels[i].toFixed(5)} m/s^2`));

  ok(accels[1] > accels[0] * 1.5,
     '...so the half that kept two thrusters out-accelerates the half that kept one',
     `${accels[1].toFixed(4)} vs ${accels[0].toFixed(4)} m/s^2`);

  /* PROVE THE CHECK CAN FAIL. A measurement that cannot detect a defect will
     certify one: if the halves were still flying on the PARENT's numbers, the
     assertion above must reject them. */
  const asParent = { blocks: g.blocks, sizeClass: g.sizeClass };
  ok(!holds(accels[0], asParent) && !holds(accels[1], asParent),
     'and the gate check FAILS when handed the parent grid — it can detect the defect',
     `parent would be ${(tWhole / mWhole).toFixed(5)} m/s^2`);

  // conservation: the halves together are the ship, minus the block that was cut
  near(G.mass(one) + G.mass(two), mWhole - 100, 1e-9,
       'the two halves mass the whole ship less the plate that was cut out');
}

/* ---- a half with no cockpit is debris: it keeps momentum, loses the stick --- */
{
  A.clearShips();
  const g = build('large', [
    ['hull', [0, 0, 0]], ['hull', [1, 0, 0]], ['hull', [2, 0, 0]],
    ['reactor', [0, 1, 0]], ['cockpit', [0, -1, 0]], ['thruster', [2, -1, 0]],
  ], 'brk');
  const parent = A.makeShip('brk', g, { pos: [0, 0, 0] });
  parent.vel = [3, 0, 0];
  F.command(parent, [0, 0, -1], null);
  G.removeBlock(g, 1, 0, 0);
  const halves = G.splitIslands(g);
  const kids = halves.map((h, i) => A.makeShip(`brk${i}`, h));
  F.default.onSplit(parent, kids, A.API);
  const piloted = kids.find(k => G.uniqueBlocks(k.grid).some(b => b.type === 'cockpit'));
  const junk = kids.find(k => k !== piloted);
  ok(piloted.data.flight.cmd.lin[2] === -1 && junk.data.flight.cmd.lin[2] === 0,
     'the half with the cockpit keeps the command; the half without loses it');
  const v0 = [...junk.vel];
  F.command(junk, [0, 0, -1], null);            // shout at the debris
  for (let i = 0; i < 60; i++) A.step(1 / 60);
  ok(junk.data.flight.control === 'no-cockpit' &&
     Math.abs(hyp(junk.vel) - hyp(v0)) < 1e-9,
     '...and the debris will not obey it — it coasts on the momentum it had',
     `${hyp(junk.vel).toFixed(3)} m/s, unchanged`);
}

/* ---- editing the ship changes how it flies, with no reload ----------------- */
{
  A.clearShips();
  const g = build('large', [...SHIP_SPEC, ['thruster', [3, 1, 0]]], 'edit');
  const s = A.makeShip('edit', g);
  F.command(s, [0, 0, -1], null); F.setDamp(s, false);
  const dt = 1 / 60;
  A.step(dt);
  const a2 = hyp(s.vel) / dt;
  G.removeBlock(g, 3, 1, 0);
  F.default.onBlockRemoved(s, null, A.API);
  s.vel = [0, 0, 0];
  A.step(dt);
  const a1 = hyp(s.vel) / dt;
  ok(a1 < a2 && Math.abs(a1 - G.thrustAlong(g, [0, 0, -1]) / G.mass(g)) < 1e-9,
     'grinding a thruster off changes the flight model on the next tick',
     `${a2.toFixed(4)} -> ${a1.toFixed(4)} m/s^2`);
}

/* ---- ship.data stays JSON-safe, and save/load is honest -------------------- */
{
  A.clearShips();
  const s = A.makeShip('json', build('large', SHIP_SPEC, 'json'));
  F.command(s, [0.5, -0.25, 1], [0, 0, 1]);
  F.setDamp(s, false);
  A.step(1 / 60);
  let round = null, threw = false;
  try { round = JSON.parse(JSON.stringify(s.data)); } catch { threw = true; }
  ok(!threw && round && round.flight && Array.isArray(round.flight.cmd.lin),
     'ship.data survives a round trip through JSON — it is saved and reloaded');
  const saved = F.default.save(s);
  ok(saved.damp === false && !('cmd' in saved),
     'save keeps the dampener mode and NOT the stick — a reload must not hold the throttle');
  F.setDamp(s, true);
  F.default.load(s, saved);
  ok(s.data.flight.damp === false, '...and load puts it back');
}

/* ---- the soft dependency on a power system --------------------------------- */
{
  ok(A.systemOrder().map(x => x.id).includes('flight'),
     'flight is registered and orders fine with nothing else present',
     A.systemOrder().map(x => x.id).join(' -> '));
  A.registerSystem({ id: 'power' });
  const names = A.systemOrder().map(x => x.id);
  ok(names.indexOf('power') < names.indexOf('flight'),
     'and once a power system exists, flight ticks after it', names.join(' -> '));
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
