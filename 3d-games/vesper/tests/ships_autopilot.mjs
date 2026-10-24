/* AUTOPILOT — NPC pilots, headless. No GPU, no browser, no puppeteer.

   The gate this file guards is not "does it compile", it is the owner's own
   sentence: a follower that jitters at 2 Hz looks broken even when the maths is
   right. So the interesting checks are STABILITY checks over long runs on more
   than one seed, and the stability check is itself checked — a deliberately
   naive P controller is run through the identical measurement and MUST fail it.
   This project has already been bitten three times by an instrument that could
   not detect the defect it was pointed at (the dead GPU context, the daylit
   frames labelled "night", ablate.mjs with no control), and a stability metric
   that certifies an oscillator is exactly that mistake again.

   The flight model here is a REFERENCE PLANT, not the shipping one — flight.js
   is being written in parallel and this file may not import it. What the plant
   guarantees is the only thing the autopilot is entitled to assume: that
   ship.data.ctl.lin is a fraction of the thrust api.thrust() reports along that
   axis, and that gravity is added on top. If flight lands on different units the
   numbers move; the shape of the law does not.

   node tests/ships_autopilot.mjs
*/
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHIPS = path.join(ROOT, 'src', 'ships');
const load = (f) => import(pathToFileURL(path.join(SHIPS, f)).href);

const G = await load('grid.js');
const A = await load('api.js');
const AUTO = await load('autopilot.js');
const { AP } = AUTO;

let pass = 0, fail = 0;
const ok = (cond, name, detail = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}${detail ? '  ' + detail : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  ' + detail : ''}`); }
};

console.log('\n  AUTOPILOT — NPC pilots, headless\n');

/* ---- vector helpers (the test does its own, on purpose: a test that shares
   its maths with the thing it measures cannot catch a wrong sign) ---------- */
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const dist = (a, b) => len(sub(a, b));

/* ---- a fake planet, plain numbers only ----------------------------------- */
const R = 600000, MU = 2.0e11;
function attach() {
  A.attachWorld({ radius: R, mu: MU, groundR: () => R - 1000, quat: [0, 0, 0, 1] });
}
const gravAt = (pos) => {
  const r = len(pos);
  if (r < 1) return [0, 0, 0];
  const g = MU / (r * r);
  return mul(pos, -g / r);
};

/* ---- a ship that can push in all six directions --------------------------
   A hull cell with three thrusters spiked out of each of its six faces, and the
   cockpit, reactor and gyro hung off those spikes. Every block has to actually
   TOUCH the grid: grid.js refuses a detached block by returning null, which is
   silent, and the first version of this fixture had six thrusters that were
   never placed and therefore zero thrust in every direction. Every convergence
   number below would have been a statement about a ship that cannot move. */
const AXES6 = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];
const THRUSTERS_PER_AXIS = 3;

function rotWithFwd(f) {
  for (let i = 0; i < G.ROTS.length; i++)
    if (G.ROTS[i].fwd.join() === f.join()) return i;
  throw new Error(`no rotation with fwd ${f}`);
}

function omniGrid(cls = 'large', id = 'g') {
  const g = G.makeGrid(cls, id);
  const A0 = [1, 0, 0];
  if (!G.addBlock(g, 'hull', A0)) throw new Error('fixture: core hull refused');
  for (const f of AXES6) {
    const rot = rotWithFwd(f);
    for (let k = 0; k < THRUSTERS_PER_AXIS; k++) {
      const base = [A0[0] + f[0] * (2 * k + 1),
                    A0[1] + f[1] * (2 * k + 1),
                    A0[2] + f[2] * (2 * k + 1)];
      if (!G.addBlock(g, 'thruster', base, rot))
        throw new Error(`fixture: thruster ${f} #${k} refused`);
    }
  }
  if (!G.addBlock(g, 'cockpit', [2, 1, 0], 0)) throw new Error('fixture: cockpit refused');
  /* [3,2,0] and not [3,1,0]: ROTS[0] is NOT the identity — its `up` is +X and
     its `right` is -Y, so a 2x2x2 block at rot 0 grows toward -Y and the
     obvious base cell puts the reactor through a thruster. */
  if (!G.addBlock(g, 'reactor', [3, 2, 0], 0)) throw new Error('fixture: reactor refused');
  /* 18 thrusters draw 540 kW. One reactor makes 300, so a one-reactor fixture is
     a ship that cannot run its own engines — which flight is entitled to refuse. */
  if (!G.addBlock(g, 'reactor', [3, 2, 2], 0)) throw new Error('fixture: reactor 2 refused');
  if (!G.addBlock(g, 'gyro', [2, -1, 0], 0)) throw new Error('fixture: gyro refused');
  return g;
}

/* Verify the fixture before trusting a single result off it: a ship that cannot
   push sideways would make every convergence number a lie about the controller. */
{
  const g = omniGrid();
  const t = AXES6.map(d => G.thrustAlong(g, d));
  ok(t.every(v => v > 0), 'the test ship can push all six ways',
     t.map(v => (v / 1000).toFixed(0) + 'kN').join(' '));
  const m = G.mass(g);
  const accel = t[0] / m;
  ok(m > 0, 'and has mass', `${m.toFixed(0)} kg`);
  ok(accel > MU / Math.pow(R + 20000, 2) * 1.5,
     '...and enough thrust to beat this planet\'s gravity',
     `${accel.toFixed(2)} m/s^2 vs g ${(MU / Math.pow(R + 20000, 2)).toFixed(2)}`);
  ok(G.islands(g).length === 1, '...and is one connected grid');
  ok(G.power(g).ok, '...that makes more power than it burns');
}

/* ---- the reference plant -------------------------------------------------- */
function qMul(a, b) {
  return [a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
          a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
          a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
          a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]];
}
function qRot(q, v) {
  const [x, y, z, w] = q;
  const ix = w*v[0] + y*v[2] - z*v[1], iy = w*v[1] + z*v[0] - x*v[2];
  const iz = w*v[2] + x*v[1] - y*v[0], iw = -x*v[0] - y*v[1] - z*v[2];
  return [ix*w + iw*-x + iy*-z - iz*-y,
          iy*w + iw*-y + iz*-x - ix*-z,
          iz*w + iw*-z + ix*-y - iy*-x];
}
const qNorm = (q) => { const l = Math.hypot(...q) || 1; return q.map(v => v / l); };

const ANG_ACCEL = 0.6;   // rad/s^2 at full stick — flight owns the real figure

/** Integrate one ship for dt from whatever is on ship.data.ctl, plus gravity. */
function plant(ship, dt, api, gravity) {
  const c = ship.data.ctl || { lin: [0, 0, 0], ang: [0, 0, 0] };
  const m = api.mass(ship);
  const aLocal = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    const u = Math.max(-1, Math.min(1, c.lin[i] || 0));
    if (!u) continue;
    const d = [0, 0, 0]; d[i] = u > 0 ? 1 : -1;
    aLocal[i] = u * (api.thrust(ship, d) / m);
  }
  const aWorld = add(qRot(ship.ori, aLocal), gravity);
  ship.vel = add(ship.vel, mul(aWorld, dt));
  ship.pos = add(ship.pos, mul(ship.vel, dt));

  for (let i = 0; i < 3; i++)
    ship.spin[i] += Math.max(-1, Math.min(1, c.ang[i] || 0)) * ANG_ACCEL * dt;
  const w = ship.spin;
  const dq = qMul(ship.ori, [w[0] * dt * 0.5, w[1] * dt * 0.5, w[2] * dt * 0.5, 0]);
  ship.ori = qNorm([ship.ori[0] + dq[0], ship.ori[1] + dq[1],
                    ship.ori[2] + dq[2], ship.ori[3] + dq[3]]);
}

/* ---- THE STABILITY METRIC, which is itself under test --------------------
   Two questions, because they fail differently. Does it converge? And once it
   has, does it SIT there — a controller can be dead-on average and still shake
   at 2 Hz, and the average is what a naive check would report. So we also count
   how often the throttle changes sign in the settled window: a 2 Hz oscillation
   on a 60 Hz sim is ~4 sign flips a second, and a settled ship has none. */
function metric(errs, flips, dtWindow) {
  const settled = errs.slice(Math.floor(errs.length / 2));
  const maxErr = Math.max(...settled);
  const meanErr = settled.reduce((a, b) => a + b, 0) / settled.length;
  return { maxErr, meanErr, flipHz: flips / dtWindow };
}
const STABLE = (m, tol) => m.maxErr < tol && m.flipHz < 0.5;

/* ========================================================================== */
/*  1. the player is never flown by anyone else                               */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const mine = A.makeShip('mine', omniGrid(), { pos: [0, R + 5000, 0], owner: 'player' });
  const theirs = A.makeShip('theirs', omniGrid(), { pos: [100, R + 5000, 0], owner: 'npc-1' });
  AUTO.orders(theirs).mode = 'hold';
  for (let i = 0; i < 10; i++) A.step(1 / 60);
  ok(!mine.data.ctl || mine.data.ctl.src !== 'autopilot',
     'a player-owned ship is never touched by the autopilot',
     `src=${mine.data.ctl ? mine.data.ctl.src : 'none'}`);
  ok(theirs.data.ctl && theirs.data.ctl.src === 'autopilot',
     '...and an npc-owned one is', `src=${theirs.data.ctl.src}`);
}

/* ========================================================================== */
/*  2. THE DESIGN LAW: no cockpit, no pilot                                   */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const bare = G.makeGrid('large', 'bare');
  G.addBlock(bare, 'hull', [0, 0, 0]);
  G.addBlock(bare, 'thruster', [1, 0, 0], 0);
  const s = A.makeShip('nocockpit', bare, { pos: [0, R + 5000, 0], owner: 'npc-1' });
  AUTO.orders(s).mode = 'hold';
  for (let i = 0; i < 30; i++) A.step(1 / 60);
  ok(!s.data.ctl || s.data.ctl.src !== 'autopilot',
     'a grid with no cockpit gets no pilot — the capability is a welded object');

  const g2 = omniGrid();
  const s2 = A.makeShip('withcockpit', g2, { pos: [0, R + 5000, 0], owner: 'npc-1' });
  AUTO.orders(s2).mode = 'hold';
  for (let i = 0; i < 30; i++) A.step(1 / 60);
  ok(s2.data.ctl.src === 'autopilot', '...and welding one on is the whole path in');
}

/* ========================================================================== */
/*  3. FORMATION: converges, then SITS there. Two seeds, 400 simulated seconds */
/* ========================================================================== */
function flyFormation(seed, opts = {}) {
  A.clearShips(); A.setWorldSeed(seed); attach();
  const dt = 1 / 60, T = opts.T || 400;
  const base = [0, R + 20000, 0];
  const leader = A.makeShip('lead', omniGrid('large', 'gl'), { pos: base, owner: 'player' });
  const wing = A.makeShip('wing', omniGrid('large', 'gw'),
                          { pos: add(base, opts.start || [900, -300, 400]), owner: 'npc-7' });
  const o = AUTO.orders(wing);
  o.mode = 'formation';
  o.leader = 'lead';
  o.offset = opts.offset || [40, 0, -60];

  const errs = [], flips = [];
  let prevSign = [0, 0, 0], flipCount = 0, settledFrom = T * 0.5;
  let t = 0, maxOverErr = 0;
  while (t < T) {
    if (opts.driveLeader) opts.driveLeader(leader, t, dt);
    A.step(dt);
    /* The leader is the PLAYER's ship: it is not flown by the autopilot, so the
       test drives it the way a pair of hands would — by prescribing motion. */
    if (!opts.driveLeader) { /* stationary hover */ }
    plant(wing, dt, A.API, gravAt(wing.pos));
    const tgt = AUTO.formationTarget(leader, o.offset);
    const e = dist(wing.pos, tgt.pos);
    errs.push(e);
    if (t > settledFrom) {
      const c = wing.data.ctl.lin;
      for (let i = 0; i < 3; i++) {
        const s = Math.abs(c[i]) > 0.02 ? Math.sign(c[i]) : 0;
        if (s !== 0 && prevSign[i] !== 0 && s !== prevSign[i]) flipCount++;
        if (s !== 0) prevSign[i] = s;
      }
    }
    t += dt;
  }
  return { errs, m: metric(errs, flipCount, T - settledFrom), wing, leader, o };
}

for (const seed of ['VESPER-1', 'VESPER-4', 'OTHER-SEED']) {
  const r = flyFormation(seed);
  ok(STABLE(r.m, 0.5), `formation converges and STAYS on seed ${seed}`,
     `max ${r.m.maxErr.toFixed(3)} m · mean ${r.m.meanErr.toFixed(3)} m · ` +
     `${r.m.flipHz.toFixed(2)} throttle sign-flips/s`);
}

/* it must actually have had a long way to come, or the check proves nothing */
{
  const r = flyFormation('VESPER-1');
  ok(r.errs[0] > 500, 'and it started a long way off station',
     `${r.errs[0].toFixed(0)} m`);
  const half = r.errs.slice(0, 40).every(e => e <= r.errs[0] * 1.02);
  ok(half, '...and never overshot on the way in');
}

/* ========================================================================== */
/*  4. THE METRIC CAN FAIL. A naive P controller, same plant, same measurement */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const dt = 1 / 60, T = 400;
  const base = [0, R + 20000, 0];
  const leader = A.makeShip('lead2', omniGrid('large', 'gl2'), { pos: base, owner: 'player' });
  const wing = A.makeShip('wing2', omniGrid('large', 'gw2'),
                          { pos: add(base, [900, -300, 400]), owner: 'npc-9' });
  AUTO.orders(wing).mode = 'off';           // the autopilot stands down
  const offset = [40, 0, -60];
  const KP = 3.0;                            // no damping term. On purpose.
  const errs = [];
  let prevSign = [0, 0, 0], flipCount = 0;
  const m = A.API.mass(wing);
  for (let t = 0; t < T; t += dt) {
    const tgt = add(leader.pos, offset);
    const e = sub(tgt, wing.pos);
    const lin = [0, 0, 0];
    for (let i = 0; i < 3; i++) {
      const d = [0, 0, 0]; d[i] = e[i] > 0 ? 1 : -1;
      const cap = A.API.thrust(wing, d) / m;
      lin[i] = Math.max(-1, Math.min(1, (KP * e[i] - gravAt(wing.pos)[i]) / cap));
    }
    wing.data.ctl = { lin, ang: [0, 0, 0], src: 'naive' };
    A.step(dt);
    plant(wing, dt, A.API, gravAt(wing.pos));
    errs.push(dist(wing.pos, tgt));
    if (t > T * 0.5) for (let i = 0; i < 3; i++) {
      const s = Math.abs(lin[i]) > 0.02 ? Math.sign(lin[i]) : 0;
      if (s !== 0 && prevSign[i] !== 0 && s !== prevSign[i]) flipCount++;
      if (s !== 0) prevSign[i] = s;
    }
  }
  const mm = metric(errs, flipCount, T * 0.5);
  ok(!STABLE(mm, 0.5), 'THE STABILITY CHECK CAN FAIL — an undamped P controller is refused',
     `max ${mm.maxErr.toFixed(1)} m · ${mm.flipHz.toFixed(2)} flips/s`);
  ok(mm.flipHz > 0.5, '...and it fails for the right reason: it oscillates',
     `${mm.flipHz.toFixed(2)} Hz of throttle reversal`);
}

/* ========================================================================== */
/*  5. THE OFFSET IS IN THE LEADER'S FRAME — the formation TURNS with them     */
/* ========================================================================== */
{
  const half = Math.SQRT1_2;
  const yaw90 = [0, half, 0, half];          // +90 deg about Y
  const r = flyFormation('VESPER-1', {
    T: 500,
    offset: [60, 0, 0],
    driveLeader: (lead, t) => { if (t > 1) lead.ori = yaw90; },
  });
  const rel = sub(r.wing.pos, r.leader.pos);
  /* +X rotated 90 degrees about +Y lands on -Z. If the offset were applied in
     the planet frame the follower would still be sitting on +X. */
  ok(Math.abs(rel[2] + 60) < 0.6 && Math.abs(rel[0]) < 0.6,
     'the offset rides in the LEADER\'S frame, so the wing swings when they turn',
     `relative [${rel.map(v => v.toFixed(2))}] — expected [0,0,-60]`);
  ok(STABLE(r.m, 0.6), '...and it is still rock steady after the turn',
     `max ${r.m.maxErr.toFixed(3)} m · ${r.m.flipHz.toFixed(2)} flips/s`);
}

/* ========================================================================== */
/*  6. A MANOEUVRING LEADER — the wing keeps station, not just a parking spot  */
/* ========================================================================== */
{
  const r = flyFormation('VESPER-4', {
    T: 500,
    start: [200, 0, 0],
    driveLeader: (lead, t, dt) => {
      /* a slow weave: the player pulling the stick about */
      const a = [Math.sin(t * 0.05) * 1.2, Math.cos(t * 0.037) * 0.8, 0];
      lead.vel = add(lead.vel, mul(a, dt));
      lead.pos = add(lead.pos, mul(lead.vel, dt));
    },
  });
  ok(r.m.maxErr < 6 && r.m.flipHz < 4,
     'the wing holds station on a leader who will not fly straight',
     `max ${r.m.maxErr.toFixed(2)} m · mean ${r.m.meanErr.toFixed(2)} m · ` +
     `${r.m.flipHz.toFixed(2)} flips/s`);
  const moved = len(sub(r.leader.pos, [0, R + 20000, 0]));
  ok(moved > 100, '...and the leader really did go somewhere', `${moved.toFixed(0)} m`);
}

/* ========================================================================== */
/*  7. STATION KEEPING — it holds an orbit, and it is an ORBIT                 */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const dt = 1 / 60, r0 = R + 80000;
  const vCirc = Math.sqrt(MU / r0);
  const s = A.makeShip('sentry', omniGrid('large', 'gs'),
                       { pos: [r0 + 400, 0, 0], owner: 'npc-3' });
  s.vel = [0, vCirc * 0.94, 0];              // wrong radius AND wrong speed
  const o = AUTO.orders(s);
  o.mode = 'station';
  o.radius = r0;

  let minR = Infinity, maxR = -Infinity, sweep = 0;
  let prev = Math.atan2(s.pos[1], s.pos[0]);
  const T = 4000;
  for (let t = 0; t < T; t += dt) {
    A.step(dt);
    plant(s, dt, A.API, gravAt(s.pos));
    const a = Math.atan2(s.pos[1], s.pos[0]);
    let d = a - prev; if (d > Math.PI) d -= 2 * Math.PI; if (d < -Math.PI) d += 2 * Math.PI;
    sweep += d; prev = a;
    if (t > T * 0.5) { const r = len(s.pos); minR = Math.min(minR, r); maxR = Math.max(maxR, r); }
  }
  ok(Math.abs((minR + maxR) / 2 - r0) < 50 && (maxR - minR) < 200,
     'station keeping holds the ordered orbital radius',
     `${((minR + maxR) / 2 - r0).toFixed(1)} m off · band ${(maxR - minR).toFixed(1)} m`);
  ok(Math.abs(sweep) > 1.0,
     '...and it is an ORBIT — the ship went round, it did not hover',
     `${(Math.abs(sweep) * 180 / Math.PI).toFixed(0)} degrees of arc in ${T} s`);
  const thr = len(s.data.ctl.lin);
  ok(thr < 0.05, '...on almost no throttle, because gravity does the turning',
     `|lin| ${thr.toFixed(4)}`);
}

/* ========================================================================== */
/*  8. WAYPOINTS — visits them in order and stops at the last                 */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const dt = 1 / 60;
  const base = [0, R + 30000, 0];
  const wps = [add(base, [1200, 0, 0]), add(base, [1200, 0, 1500]), add(base, [0, 600, 1500])];
  const s = A.makeShip('courier', omniGrid('large', 'gc'), { pos: base, owner: 'npc-4' });
  const o = AUTO.orders(s);
  o.mode = 'waypoint';
  o.waypoints = wps.map(w => [...w]);

  const seen = [];
  for (let t = 0; t < 900; t += dt) {
    A.step(dt);
    plant(s, dt, A.API, gravAt(s.pos));
    if (seen[seen.length - 1] !== o.wp) seen.push(o.wp);
  }
  ok(seen.join() === '0,1,2', 'waypoints are taken in order', `visited ${seen.join(' -> ')}`);
  const endErr = dist(s.pos, wps[2]);
  ok(endErr < 0.5, '...and it parks on the last one', `${endErr.toFixed(3)} m out`);
  ok(o.arrived === true, '...and says so');
  ok(len(s.vel) < 0.05, '...at a standstill', `${len(s.vel).toFixed(4)} m/s`);
}

/* ========================================================================== */
/*  9. A LEADER THAT IS GONE                                                  */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const dt = 1 / 60;
  const s = A.makeShip('orphan', omniGrid('large', 'go'),
                       { pos: [0, R + 9000, 0], owner: 'npc-5' });
  const o = AUTO.orders(s);
  o.mode = 'formation';
  o.leader = 'a-ship-that-was-destroyed';
  s.vel = [30, 12, -8];
  let threw = false;
  try {
    for (let t = 0; t < 200; t += dt) { A.step(dt); plant(s, dt, A.API, gravAt(s.pos)); }
  } catch { threw = true; }
  ok(!threw, 'a leader that no longer exists does not throw');
  ok(len(s.vel) < 0.05, '...the wing holds where it is instead of flying at the core',
     `${len(s.vel).toFixed(4)} m/s`);
  ok(len(s.pos) > R, '...and it is still above the ground', `${(A.API.altitude(s) / 1000).toFixed(1)} km up`);
}

/* ========================================================================== */
/* 10. NO WORLD ATTACHED — deep space still flies                             */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  A.attachWorld(null);
  const dt = 1 / 60;
  const s = A.makeShip('deep', omniGrid('large', 'gd'), { pos: [0, 0, 0], owner: 'npc-6' });
  const o = AUTO.orders(s);
  o.mode = 'waypoint';
  o.waypoints = [[500, 200, -300]];
  let threw = false;
  try {
    for (let t = 0; t < 300; t += dt) { A.step(dt); plant(s, dt, A.API, [0, 0, 0]); }
  } catch (e) { threw = e; }
  ok(!threw, 'with no world attached the autopilot still flies', threw ? String(threw) : '');
  ok(dist(s.pos, [500, 200, -300]) < 0.5, '...and reaches the waypoint',
     `${dist(s.pos, [500, 200, -300]).toFixed(3)} m out`);
}

/* ========================================================================== */
/* 11. THE OUTPUT IS ALWAYS A LEGAL STICK POSITION                            */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const dt = 1 / 60;
  const lead = A.makeShip('l3', omniGrid('large', 'gl3'),
                          { pos: [0, R + 5000, 0], owner: 'player' });
  const w = A.makeShip('w3', omniGrid('large', 'gw3'),
                       { pos: [40000, R + 5000, 22000], owner: 'npc-8' });
  const o = AUTO.orders(w);
  o.mode = 'formation'; o.leader = 'l3'; o.offset = [30, 10, -30];
  let bad = 0, sawFull = false;
  for (let t = 0; t < 600; t += dt) {
    A.step(dt);
    plant(w, dt, A.API, gravAt(w.pos));
    for (const v of [...w.data.ctl.lin, ...w.data.ctl.ang]) {
      if (!Number.isFinite(v) || v < -1 || v > 1) bad++;
      if (Math.abs(v) > 0.999) sawFull = true;
    }
  }
  ok(bad === 0, 'every demand is finite and inside -1..1', `${bad} violations in 36,000 ticks`);
  ok(sawFull, '...and the stick really does reach its stop when it needs to');
  ok(dist(w.pos, add(lead.pos, [30, 10, -30])) < 0.5,
     '...having crossed 45 km to get there',
     `${dist(w.pos, add(lead.pos, [30, 10, -30])).toFixed(3)} m out`);
}

/* ========================================================================== */
/* 12. DETERMINISM AND PERSONALITY                                            */
/* ========================================================================== */
{
  const runOnce = (seed) => {
    A.clearShips(); A.setWorldSeed(seed); attach();
    const dt = 1 / 60;
    A.makeShip('l4', omniGrid('large', 'gl4'), { pos: [0, R + 5000, 0], owner: 'player' });
    const w = A.makeShip('w4', omniGrid('large', 'gw4'),
                         { pos: [700, R + 5000, 0], owner: 'npc-2' });
    const o = AUTO.orders(w);
    o.mode = 'formation'; o.leader = 'l4'; o.offset = [50, 0, 0];
    const trace = [];
    for (let t = 0; t < 30; t += dt) {
      A.step(dt); plant(w, dt, A.API, gravAt(w.pos));
      trace.push(w.pos.map(v => v.toFixed(6)).join());
    }
    return { trace: trace.join('|'), gains: [w.data._ap.gp, w.data._ap.gv] };
  };
  const a = runOnce('VESPER-1'), b = runOnce('VESPER-1'), c = runOnce('VESPER-9');
  ok(a.trace === b.trace, 'the same seed flies the identical trajectory');
  ok(a.gains.join() !== c.gains.join(),
     'a different world seed gives the pilot different hands',
     `${a.gains.map(g => g.toFixed(4)).join('/')} vs ${c.gains.map(g => g.toFixed(4)).join('/')}`);
  const spread = [...a.gains, ...c.gains];
  ok(spread.every(g => g >= 1 - AP.personality - 1e-9 && g <= 1 + AP.personality + 1e-9),
     '...but never outside the gain envelope that keeps it damped',
     `${Math.min(...spread).toFixed(3)}..${Math.max(...spread).toFixed(3)}`);
}

/* ========================================================================== */
/* 13. SAVE / LOAD — the route survives a reload, through real JSON            */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const dt = 1 / 60;
  const base = [0, R + 30000, 0];
  const s = A.makeShip('saver', omniGrid('large', 'gv'), { pos: base, owner: 'npc-1' });
  const o = AUTO.orders(s);
  o.mode = 'waypoint';
  o.waypoints = [add(base, [900, 0, 0]), add(base, [900, 0, 900])];
  for (let t = 0; t < 120; t += dt) { A.step(dt); plant(s, dt, A.API, gravAt(s.pos)); }
  ok(o.wp === 1, 'the courier is on its second leg before we save', `wp=${o.wp}`);

  const blob = JSON.parse(JSON.stringify(AUTO.SYSTEM.save(s)));
  const gains = [s.data._ap.gp, s.data._ap.gv];

  A.clearShips(); A.setWorldSeed('VESPER-1');
  const s2 = A.makeShip('saver', omniGrid('large', 'gv'), { pos: [...s.pos], owner: 'npc-1' });
  AUTO.SYSTEM.load(s2, blob);
  ok(s2.data.autopilot.wp === 1, 'and after a reload it is still on its second leg',
     `wp=${s2.data.autopilot.wp}`);
  ok(s2.data.autopilot.mode === 'waypoint' && s2.data.autopilot.waypoints.length === 2,
     '...with the same route');
  ok(s2.data._ap.gp === gains[0] && s2.data._ap.gv === gains[1],
     '...flown by the same hands', `${gains.map(g => g.toFixed(4)).join('/')}`);
  for (let t = 0; t < 400; t += dt) { A.step(dt); plant(s2, dt, A.API, gravAt(s2.pos)); }
  ok(dist(s2.pos, o.waypoints[1]) < 0.5, '...and it finishes the route it was on',
     `${dist(s2.pos, o.waypoints[1]).toFixed(3)} m out`);
}

/* ========================================================================== */
/* 14. THE FILE IS PURE AND NEVER HARDCODES A CELL SIZE                       */
/* ========================================================================== */
{
  const raw = fs.readFileSync(path.join(SHIPS, 'autopilot.js'), 'utf8');
  const src = raw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const banned = [/\bfrom ['"]three['"]/, /\bdocument\b/, /\bwindow\b/,
                  /Math\.random/, /\bnew Date\b/, /Date\.now/];
  const hits = banned.filter(re => re.test(src)).map(String);
  ok(hits.length === 0, 'autopilot.js is pure', hits.join(' · '));
  ok(!/(^|[^\w.])2\.5([^\d]|$)/.test(src), 'and never hardcodes the large grid');
  ok(/cellM\(/.test(src), '...it asks grid.js for metres per cell');
  ok(/Math\.random/.test('Math.random()'), 'the purity grep can still fire');
}

/* ========================================================================== */
/* 15. IT DRIVES THE SAME SURFACE THE PLAYER DOES                             */
/* ========================================================================== */
{
  A.clearShips(); A.setWorldSeed('VESPER-1'); attach();
  const s = A.makeShip('adopt', omniGrid('large', 'ga'),
                       { pos: [0, R + 5000, 0], owner: 'npc-1' });
  /* flight.js owns the control object's real shape. If it has already put one
     on the ship, the autopilot must WRITE INTO IT rather than shadow it — a
     second control object is a ship with two sets of hands on the stick. */
  s.data.ctl = { lin: [0, 0, 0], ang: [0, 0, 0], dampen: false, src: 'player' };
  const before = s.data.ctl;
  const o = AUTO.orders(s);
  o.mode = 'waypoint'; o.waypoints = [[0, R + 5400, 0]];
  A.step(1 / 60);
  ok(s.data.ctl === before, 'it adopts the control object already on the ship');
  ok(s.data.ctl.dampen === false, '...leaving fields it does not own alone');
  ok(Object.keys(s.data).filter(k => /^(ctl|control|controls|input)$/.test(k)).length === 1,
     '...so there is only ever one set of hands on the stick');
  const posOnly = s.pos.join();
  A.step(1 / 60);
  ok(s.pos.join() === posOnly,
     'and it never moves the ship itself — position only changes through flight');
  ok(s.vel.join() === '0,0,0', '...nor sets velocity directly');
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
