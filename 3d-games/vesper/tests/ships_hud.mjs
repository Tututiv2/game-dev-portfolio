/* THE HUD, HEADLESS. No GPU, no browser, no puppeteer.

   It runs the real grid.js, api.js and hud.js — the shipping files — against a
   fake planet made of plain numbers.

   THE ONE THAT MATTERS: the stop margin ("can I still brake before I hit the
   ground") is checked against an INDEPENDENT ORACLE — a numeric integration of
   the descent at 0.5 ms steps — rather than against the formula that produced
   it. And because this project's own scar tissue says a measurement that cannot
   detect a defect will certify it, the oracle is then fed a deliberately WRONG
   margin (one computed with twice the ship's thrust) and is required to catch
   it. If that last check ever passes silently, the oracle has stopped working.

   node tests/ships_hud.mjs
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
const H = await import(pathToFileURL(path.join(SHIPS_DIR, 'hud.js')).href);

console.log('\n  HUD — what the pilot can see about their own ship, headless\n');

/* ---- the fake planet ------------------------------------------------------
   600 km, ground 1 km under the datum everywhere, and a mu chosen so the local
   gravity is a low-but-real 1.66 m/s^2 — a small world, which is the point:
   TWR is against THIS world's pull, not against a constant. */
const R = 600000, MU = 6.0e11;
const WORLD = { radius: R, mu: MU, groundR: () => R - 1000, quat: [0, 0, 0, 1] };
const attach = (w = WORLD) => A.attachWorld(w);
const gAt = (r) => MU / (r * r);

/* ---- ship building -------------------------------------------------------- */
let nGrid = 0;
/** the rotation index whose nozzle pushes along `dir` in grid space */
function rotPushing(dir) {
  for (let i = 0; i < G.ROTS.length; i++) {
    const p = G.rotVec(i, [0, 0, -1]);
    if (p[0] === dir[0] && p[1] === dir[1] && p[2] === dir[2]) return i;
  }
  throw new Error('no rotation pushes ' + dir);
}
const UP = rotPushing([0, 1, 0]);

/** cockpit + reactor(s) + N thrusters pushing along GRID +Y, in a line on +X. */
function build({ thrusters = 4, reactors = 1, hull = 0, cockpit = true, cls = 'large' } = {}) {
  const g = G.makeGrid(cls, 'g' + (nGrid++));
  let x = 0;
  if (cockpit) { G.addBlock(g, 'cockpit', [0, 0, 0], 0); x = 1; }
  else { G.addBlock(g, 'hull', [0, 0, 0], 0); x = 1; }
  for (let i = 0; i < reactors; i++) { G.addBlock(g, 'reactor', [x, 0, 0], 0); x += 2; }
  for (let i = 0; i < thrusters; i++) { G.addBlock(g, 'thruster', [x, 0, 0], UP); x += 1; }
  for (let i = 0; i < hull; i++) { G.addBlock(g, 'hull', [x, 0, 0], 0); x += 1; }
  return g;
}
let nShip = 0;
const ship = (g, opts = {}) => A.makeShip('s' + (nShip++), g, opts);

/** every number in a readout must be finite or explicitly null — never NaN,
    never Infinity. A NaN on a HUD looks like a rendering bug for a week. */
function finiteOrNull(o, trail = 'r', bad = []) {
  if (o === null) return bad;
  if (typeof o === 'number') { if (!Number.isFinite(o)) bad.push(trail); return bad; }
  if (Array.isArray(o)) { o.forEach((v, i) => finiteOrNull(v, `${trail}[${i}]`, bad)); return bad; }
  if (typeof o === 'object') { for (const k of Object.keys(o)) finiteOrNull(o[k], `${trail}.${k}`, bad); return bad; }
  return bad;
}
const textOf = (r) => r.lines.map(l => l.text).join(' | ');

/* ========================================================================== */
/* 1. THE INSTRUMENTS ARE A BLOCK YOU BUILT                                   */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const s = ship(build({ cockpit: false, thrusters: 4 }), { pos: [0, R + 1000, 0] });
  const r = H.readout(s, A.API);
  ok(r.ok === false, 'no cockpit, no instruments — ok is false');
  ok(/cockpit/i.test(r.reason), '...and the reason names the block to go and build', r.reason);
  ok(r.twr === null && r.altitude === null,
     '...and it invents no numbers to fill the gap', `twr ${r.twr} alt ${r.altitude}`);
  ok(/NO COCKPIT/.test(textOf(r)), '...and says so on the panel', textOf(r));
  ok(r.integrity.blocks > 0, '...but still counts the hull that IS there', `${r.integrity.blocks}`);

  const s2 = ship(build({ thrusters: 4 }), { pos: [0, R + 1000, 0] });
  ok(H.readout(s2, A.API).ok === true, 'weld a cockpit on and the instruments come with it');
}

/* ========================================================================== */
/* 2. MASS, ALTITUDE, AND THE VELOCITY A PILOT ACTUALLY FLIES BY              */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const g = build({ thrusters: 4 });
  const s = ship(g, { pos: [0, R + 1000, 0] });
  s.vel = [30, 10, 0];                       // 30 m/s east, 10 m/s up
  const r = H.readout(s, A.API);

  near(r.mass, A.API.mass(s), 1e-9, 'mass is the grid\'s real mass', `${r.mass.toFixed(0)} kg`);
  near(r.altitude, 2000, 1e-6, 'altitude is above the TERRAIN', '1 km up + 1 km of valley');
  near(r.vertical, 10, 1e-9, 'vertical speed is signed and + is climbing');
  near(r.ground, 30, 1e-9, 'ground speed is the horizontal part');
  near(r.speed, Math.hypot(30, 10), 1e-9, '...and speed is the whole vector');

  /* off-axis, so the split cannot be accidentally reading vel[1] */
  const k = 1 / Math.SQRT2;
  s.pos = [(R + 1000) * k, (R + 1000) * k, 0];
  const up = [k, k, 0], east = [k, -k, 0];
  s.vel = [up[0] * 7 + east[0] * 24, up[1] * 7 + east[1] * 24, 0];
  const r2 = H.readout(s, A.API);
  near(r2.vertical, 7, 1e-6, 'the split follows the LOCAL up, not world +Y', `${r2.vertical.toFixed(3)}`);
  near(r2.ground, 24, 1e-6, '...and so does ground speed');
  ok(finiteOrNull(r2).length === 0, 'no NaN and no Infinity anywhere in the readout');
}

/* ========================================================================== */
/* 3. THE HEADLINE — CAN THIS SHIP LEAVE THIS WORLD                           */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const gv = gAt(R + 1000);
  const lifter = ship(build({ thrusters: 4 }), { pos: [0, R + 1000, 0] });
  const brick  = ship(build({ thrusters: 1 }), { pos: [0, R + 1000, 0] });
  const rl = H.readout(lifter, A.API), rb = H.readout(brick, A.API);

  near(rl.gravity, gv, 1e-9, 'gravity is this world\'s, from its own mu', `${gv.toFixed(3)} m/s2`);
  near(rl.thrustUp, 4 * 12000, 1e-6, 'thrust up is the thrusters that face up', `${rl.thrustUp} N`);
  near(rl.twr, rl.thrustUp / (rl.mass * gv), 1e-9, 'TWR is thrust over weight HERE',
       `${rl.twr.toFixed(2)} : 1`);

  ok(rl.canLiftOff === true, 'four thrusters lift this hull off this world', `${rl.twr.toFixed(2)} : 1`);
  ok(rb.canLiftOff === false, 'one thruster does not', `${rb.twr.toFixed(2)} : 1`);
  ok(/CANNOT LIFT OFF THIS WORLD/.test(textOf(rb)),
     '...and it is said in as many words', rb.lines[0].text);
  ok(rb.lines[0].level === 'alarm', '...as the first line, at alarm level');
  ok(/LIFTS OFF THIS WORLD/.test(textOf(rl)), 'and the good case says so too', rl.lines[0].text);

  /* THE CHECK MUST BE ABLE TO FAIL. `canLiftOff` is asserted true above and
     false here off the SAME code path, so it is discriminating and not a
     constant — the failure mode ships.mjs's purity probe guards against. */
  ok(rl.canLiftOff !== rb.canLiftOff,
     'the lift-off verdict discriminates — it is not a constant true');

  /* ONE NUMBER, NOT TWO. payloadKg is signed: + kg you may still bolt on,
     - kg you must strip. Adding exactly that must land TWR on 1.0. */
  ok(rl.payloadKg > 0 && rb.payloadKg < 0, 'payloadKg is signed the right way round',
     `${rl.payloadKg.toFixed(0)} kg spare · ${rb.payloadKg.toFixed(0)} kg over`);
  near((rl.mass + rl.payloadKg) * gv, rl.thrustUp, 1e-6,
       'add exactly payloadKg and weight equals thrust (TWR 1.00)');
  near((rb.mass + rb.payloadKg) * gv, rb.thrustUp, 1e-6,
       '...and shed exactly it and the brick just lifts');
  ok(rb.lines[0].text.includes('shed'), '...and the panel says how much to shed, so nobody subtracts',
     rb.lines[0].text);

  /* the same ship at a heavier world stops lifting — TWR is not a property of
     the ship alone, which is the whole reason the wording says "THIS WORLD" */
  attach({ ...WORLD, mu: 6.0e12 });
  const heavyWorld = H.readout(lifter, A.API);
  ok(heavyWorld.canLiftOff === false,
     'the SAME ship cannot lift off a ten-times-heavier world', `${heavyWorld.twr.toFixed(2)} : 1`);
  attach();
}

/* ========================================================================== */
/* 4. ORIENTATION — thrust is asked in GRID space, so ori has to be honoured   */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const s = ship(build({ thrusters: 4 }), { pos: [0, R + 1000, 0] });
  const up = H.readout(s, A.API);
  s.ori = [1, 0, 0, 0];                     // 180 degrees about grid X: nozzles now point up
  const inverted = H.readout(s, A.API);
  near(inverted.thrustUp, 0, 1e-9, 'roll the ship over and its up-thrust is zero',
       `${up.thrustUp} N upright vs ${inverted.thrustUp} N inverted`);
  ok(inverted.canLiftOff === false, '...so upside down it cannot lift off');
  ok(finiteOrNull(inverted).length === 0, '...and a zero-thrust ship still produces no NaN');

  const c = Math.SQRT1_2;
  s.ori = [c, 0, 0, c];                     // 90 degrees about X: nozzles point sideways
  near(H.readout(s, A.API).thrustUp, 0, 1e-9, 'on its side, likewise nothing pushes up');
  s.ori = [0, 0, 0, 1];
}

/* ========================================================================== */
/* 5. THE STOP MARGIN, AGAINST AN INDEPENDENT ORACLE                          */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const s = ship(build({ thrusters: 4 }), { pos: [0, R + 1000, 0] });

  /* THE ORACLE: no formula from hud.js. Step the descent at 0.5 ms with full
     up-thrust and see whether the ship stops above the ground. */
  function integrate(alt, vDown, netUp) {
    let h = alt, v = vDown;
    const dt = 0.0005;
    for (let i = 0; i < 4e6 && v > 0 && h > 0; i++) { h -= v * dt; v -= netUp * dt; }
    return { stopped: h > 0, heightLeft: h };
  }
  const netUp = (r) => r.thrustUp / r.mass - r.gravity;

  const scenarios = [
    { alt: 1000, v: 50 },       // plenty of room
    { alt: 200,  v: 50 },       // not enough
    { alt: 100,  v: 50 },       // nowhere near enough
    { alt: 3000, v: 120 },      // fast but high
  ];
  let agree = 0;
  for (const sc of scenarios) {
    s.pos = [0, R - 1000 + sc.alt, 0];
    s.vel = [0, -sc.v, 0];
    const r = H.readout(s, A.API);
    const o = integrate(sc.alt, sc.v, netUp(r));
    const hudSays = r.canStop === true;
    if (hudSays === o.stopped) agree++;
    ok(hudSays === o.stopped,
       `stop verdict matches a numeric descent — ${sc.alt} m at ${sc.v} m/s`,
       `hud ${hudSays ? 'STOPS' : 'CRASHES'} · oracle ${o.stopped ? 'STOPS' : 'CRASHES'}`);
    if (o.stopped)
      near(r.stopMargin, o.heightLeft, Math.max(1, o.heightLeft * 0.01),
           `...and the margin itself is right to 1%`,
           `${r.stopMargin.toFixed(1)} m vs ${o.heightLeft.toFixed(1)} m integrated`);
  }
  ok(agree === scenarios.length, 'every scenario agreed', `${agree}/${scenarios.length}`);
  const verdicts = new Set(scenarios.map(sc => {
    s.pos = [0, R - 1000 + sc.alt, 0]; s.vel = [0, -sc.v, 0];
    return H.readout(s, A.API).canStop;
  }));
  ok(verdicts.size === 2, 'and the scenarios cover BOTH verdicts, so agreement means something',
     [...verdicts].join('/'));

  /* PROVE THE ORACLE CAN FAIL. Recompute the margin with twice the thrust —
     a plausible, wrong model — and require the oracle to disagree with it.
     If this passes, the check above is vacuous and everything it certified is
     worthless. */
  s.pos = [0, R - 1000 + 200, 0]; s.vel = [0, -50, 0];
  const r = H.readout(s, A.API);
  const wrongNet = 2 * r.thrustUp / r.mass - r.gravity;
  const wrongMargin = 200 - (50 * 50) / (2 * wrongNet);
  const truth = integrate(200, 50, netUp(r));
  ok((wrongMargin > 0) !== truth.stopped,
     'the oracle CATCHES a deliberately wrong margin (2x thrust)',
     `wrong says ${wrongMargin > 0 ? 'STOPS' : 'CRASHES'} · oracle says ${truth.stopped ? 'STOPS' : 'CRASHES'}`);

  /* a ship that cannot decelerate at all has no margin — not a negative one */
  const brick = ship(build({ thrusters: 1 }), { pos: [0, R - 1000 + 500, 0] });
  brick.vel = [0, -40, 0];
  const rb = H.readout(brick, A.API);
  ok(rb.canStop === false && rb.stopMargin === null,
     'a ship that cannot out-thrust gravity reports no margin at all, not a number');
  ok(/CANNOT STOP/.test(textOf(rb)), '...and the panel says CANNOT STOP', rb.lines[1].text);
  near(rb.timeToGround, 500 / 40, 1e-6, '...and gives the seconds left instead',
       `${rb.timeToGround.toFixed(1)} s`);

  /* climbing is not a landing: no braking instrument to ignore */
  brick.vel = [0, 12, 0];
  const rc = H.readout(brick, A.API);
  ok(rc.stopMargin === null && rc.timeToGround === null && rc.canStop === null,
     'climbing, the landing instruments read nothing rather than nonsense');
}

/* ========================================================================== */
/* 6. POWER AS ONE SIGNED NUMBER                                              */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const fine = ship(build({ thrusters: 4, reactors: 1 }), { pos: [0, R + 1000, 0] });
  const rf = H.readout(fine, A.API);
  const pf = A.API.power(fine);
  near(rf.power.spare, pf.produced - pf.drawn, 1e-9,
       'power is produced minus drawn, once', `+${rf.power.spare}`);
  ok(rf.power.ok === true && /POWER \+/.test(textOf(rf)), '...and reads healthy when it is');

  const starved = ship(build({ thrusters: 12, reactors: 1 }), { pos: [0, R + 1000, 0] });
  const rs = H.readout(starved, A.API);
  ok(rs.power.ok === false && rs.power.spare < 0, 'add thrusters past the reactor and it goes negative',
     `${rs.power.spare}`);
  ok(/POWER OVERDRAWN BY \d/.test(textOf(rs)),
     '...and the panel says by how much, not "insufficient"',
     rs.lines.find(l => l.id === 'power').text);
}

/* ========================================================================== */
/* 7. INTEGRITY — B2's gate is "cut it in half and both halves behave"         */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const g = build({ thrusters: 4 });
  const s = ship(g, { pos: [0, R + 1000, 0] });
  const whole = H.readout(s, A.API);
  near(whole.integrity.frac, 1, 1e-12, 'an undamaged grid reads 100%');
  ok(whole.integrity.pieces === 1, '...and one piece');
  ok(!/HULL/.test(textOf(whole)), '...and says nothing about damage, because there is none');

  const victim = G.uniqueBlocks(g).find(b => b.type === 'thruster');
  victim.hp = Math.round(victim.hp * 0.1);
  const hurt = H.readout(s, A.API);
  ok(hurt.integrity.frac < whole.integrity.frac, 'damage lowers integrity',
     `${(hurt.integrity.frac * 100).toFixed(0)}%`);
  ok(/HULL \d+%/.test(textOf(hurt)), '...and it appears on the panel');

  /* cut it in half */
  const cut = build({ thrusters: 4 });
  const s2 = ship(cut, { pos: [0, R + 1000, 0] });
  ok(H.readout(s2, A.API).integrity.pieces === 1, 'before the cut: one piece');
  for (const b of G.uniqueBlocks(cut)) {
    if (b.type === 'thruster' && b.cell[0] === 3) { G.removeBlock(cut, ...b.cell); break; }
  }
  const split = H.readout(s2, A.API);
  ok(split.integrity.pieces === 2, 'cut a thruster out of the spine and it reads TWO pieces',
     `${split.integrity.pieces}`);
  ok(/HULL IN 2 PIECES/.test(textOf(split)), '...and the pilot is told, at alarm level',
     split.lines.find(l => l.id === 'pieces').level);
  ok(split.integrity.blocks < whole.integrity.blocks, '...and the block count fell');
}

/* ========================================================================== */
/* 8. SIZE CLASSES — nothing here may assume 2.5 m                            */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const big   = ship(build({ thrusters: 4, cls: 'large' }), { pos: [0, R + 1000, 0] });
  const small = ship(build({ thrusters: 4, cls: 'small' }), { pos: [0, R + 1000, 0] });
  const rb = H.readout(big, A.API), rs = H.readout(small, A.API);
  ok(rb.blockM === G.cellM('large') && rs.blockM === G.cellM('small'),
     'the readout reports metres-per-cell from cellM(), never a literal',
     `${rb.blockM} m vs ${rs.blockM} m`);
  /* thrust goes as area (s^2), mass as volume (s^3), so TWR goes as 1/s: the
     small ship must out-lift the large one by exactly 2.5/0.5 = 5. A hardcoded
     2.5 anywhere in the chain breaks this ratio. */
  near(rs.twr / rb.twr, G.cellM('large') / G.cellM('small'), 1e-9,
       'TWR scales as 1/cell size — the small grid lifts 5x better',
       `${rs.twr.toFixed(2)} vs ${rb.twr.toFixed(2)}`);
  ok(rs.sizeM.every((v, i) => Math.abs(v - rb.sizeM[i] * (0.5 / 2.5)) < 1e-9),
     '...and the hull dimensions are in real metres for the class', `[${rs.sizeM}] m`);
}

/* ========================================================================== */
/* 9. NO WORLD, NO WEIGHT — degrade, do not lie and do not throw               */
/* ========================================================================== */
{
  A.clearShips();
  attach({ ...WORLD, mu: 1e-3 });          // effectively free space
  const s = ship(build({ thrusters: 4 }), { pos: [0, R + 1000, 0] });
  const w = H.readout(s, A.API);
  ok(w.weightless === true && w.twr === null && w.payloadKg === null,
     'in free space there is no TWR to report, and it reports none');
  ok(w.canLiftOff === true && /FREE SPACE/.test(textOf(w)),
     '...it says FREE SPACE and quotes the acceleration instead', w.lines[0].text);
  ok(finiteOrNull(w).length === 0, '...with no division-by-zero NaN', finiteOrNull(w).join(','));

  A.attachWorld(null);                      // orbit.js has not attached a world
  let threw = false, r = null;
  try { r = H.readout(s, A.API); } catch { threw = true; }
  ok(!threw, 'with no world attached the readout does not throw and kill the frame');
  ok(r.nav === false, '...it reports that it has no nav data');
  ok(r.altitude === null && r.gravity === null,
     '...as NULL, never 0 — a zero altitude means "on the ground"',
     `alt ${r.altitude} · g ${r.gravity}`);
  ok(r.mass !== null && r.integrity.blocks > 0,
     '...while the facts that do not need a planet are still reported', `${r.mass} kg`);
  ok(/NO NAV DATA/.test(textOf(r)), '...and the panel says why', textOf(r));
  attach();
}

/* ========================================================================== */
/* 10. THE READOUT IS PURE, SAVED NOWHERE, AND JSON-SAFE                      */
/* ========================================================================== */
{
  A.clearShips(); attach();
  const s = ship(build({ thrusters: 4 }), { pos: [0, R + 1000, 0] });
  s.vel = [3, -9, 1];
  const snap = JSON.stringify({ pos: s.pos, vel: s.vel, ori: s.ori, spin: s.spin, data: s.data });
  const r = H.readout(s, A.API);
  ok(JSON.stringify({ pos: s.pos, vel: s.vel, ori: s.ori, spin: s.spin, data: s.data }) === snap,
     'reading the HUD changes nothing about the ship');

  const round = JSON.parse(JSON.stringify(r));
  ok(JSON.stringify(round) === JSON.stringify(r),
     'the readout survives a JSON round trip (no undefined, no functions)');
  ok(r.lines.length > 0 && r.lines.every(l => l.id && l.text && l.level),
     'every line has an id, a level and words', `${r.lines.length} lines`);
  ok(r.lines.every(l => ['info', 'warn', 'alarm'].includes(l.level)),
     '...and only the three levels a view knows how to colour');

  /* the system: tick caches the readout where a view can find it */
  const def = H.default;
  ok(def.id === 'hud', 'the system registers as "hud"');
  ok(!def.save && !def.load,
     'and has no save/load — every field is derived, so none belongs in the diff');
  A.step(1 / 60);
  ok(s.data.hud && s.data.hud.ok === true, 'tick() leaves the readout on ship.data.hud');
  ok(s.data.hud.twr > 0, '...fully populated', `${s.data.hud.twr.toFixed(2)} : 1`);
}

/* ========================================================================== */
/* 11. NO CONTROL CHARACTERS IN THE SOURCE                                    */
/*     Three times in this portfolio a \b or a NUL written through a script    */
/*     has compiled, read fine in an editor, and matched nothing.              */
/* ========================================================================== */
{
  const src = fs.readFileSync(path.join(SHIPS_DIR, 'hud.js'), 'utf8');
  // eslint-disable-next-line no-control-regex
  const ctrl = /[\x00-\x08\x0b\x0c\x0e-\x1f]/;
  ok(!ctrl.test(src), 'hud.js holds no stray control characters');
  // built with fromCharCode, never typed: writing the probe as a literal control
  // character is the exact mistake this check exists to catch.
  ok(ctrl.test('a' + String.fromCharCode(8) + 'b'),
     '...and that scan can still detect one');
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
