/* ============================================================================
   FLIGHT — six degrees of freedom, from the blocks that are actually there.

   Nothing in here is a stat. Every number a ship flies with is read off its own
   grid: mass from the plates, force from the thrusters, the arm from where those
   thrusters sit relative to the centre of mass, and the authority to hold an
   attitude from gyros. That is the design law ("there always has to be a path in
   game") applied to movement — a ship turns faster because you welded another
   gyro to it, never because a slider moved.

   Which means the B2 gate falls out rather than being special-cased: cut a ship
   in half and each half is simply a different grid, so it flies on the thrust
   and mass it still has. onSplit() only has to hand the halves the parent's
   kinematics; the physics never knew the parent existed.

   PURE. No three, no DOM, no clock, no global random. Rendering, if any, goes in
   flight_view.js.

   ---------------------------------------------------------------------------
   THINGS THE CONTRACT DOES NOT GIVE ME, AND WHAT I DID INSTEAD
   (all four are workarounds, not reaches — nothing outside this file is edited)

   1. THE GYRO HAS NO TORQUE RATING. grid.js's BLOCK_TYPES.gyro carries mass, hp
      and power, but no newton-metres, and there is no torque field in the
      defineBlock defaults. So torqueOf() reads `type.torque` if a block type
      declares one — any later agent gets gyro behaviour free by adding that one
      field — and falls back to GYRO_TORQUE_LARGE for the stock gyro. If the
      contract grows a `torque:` default, delete the fallback and nothing else.

   2. THERE IS NO api.hasWorld(). api.gravity() THROWS with no world attached,
      which is right (a ship that silently never falls costs a session), but a
      ship in a build-editor harness with no planet must still integrate. So the
      call is guarded once per tick and the fact is PUBLISHED as
      ship.data.flight.control === 'no-world' rather than swallowed.

   3. onSplit's `children` are not typed. This handles children that are ships
      (have .grid, .pos) and ignores anything else, so it is safe whichever way
      the builder ends up calling it.

   4. NO INERTIA TENSOR IN grid.js. Computed here from cell point masses plus
      each cell's own cube term (m*s^2/6) — the DIAGONAL only. Off-diagonal terms
      would add precession; a ship is close enough to axis-aligned that leaving
      them out is a smaller lie than the one-block ship having zero inertia and
      dividing by nothing, which is what the self term prevents.
   ========================================================================= */

import { registerSystem, systems } from './api.js';

/* dt is CLAMPED here as well as in api.step(). PYRE handed a 30 fps phone a
   harder game than a 60 fps one by stepping physics from the raw frame delta,
   and Learn to Fly measured a flight model that was a function of the machine.
   A clamp in one place is a clamp that a direct caller can miss. */
export const MAX_DT = 1 / 30;

/* Newton-metres for ONE gyro at the `large` class, scaled by volume like mass
   is. Sized against the stock thruster: a 12 kN thruster on a 5 m arm makes
   60 kN-m, so one gyro holds about three badly-placed thrusters. */
export const GYRO_TORQUE_LARGE = 2.0e5;

const EPS = 1e-9;
const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
const zero3 = () => [0, 0, 0];

/* ---- quaternion helpers ---------------------------------------------------
   ori is [x, y, z, w], a plain array — the contract is explicit that nothing in
   src/ships/ may hold a three.js object. */
function qRot(q, v) {
  const [x, y, z, w] = q, [a, b, c] = v;
  const tx = 2 * (y * c - z * b), ty = 2 * (z * a - x * c), tz = 2 * (x * b - y * a);
  return [a + w * tx + (y * tz - z * ty),
          b + w * ty + (z * tx - x * tz),
          c + w * tz + (x * ty - y * tx)];
}
const qConj = (q) => [-q[0], -q[1], -q[2], q[3]];
const qRotInv = (q, v) => qRot(qConj(q), v);

/** q += 0.5 * dt * (q (x) omega_body), then renormalised. Body-frame omega,
    because ship.spin is "radians/second about each GRID axis". */
function integrateOri(q, om, dt) {
  const [x1, y1, z1, w1] = q, [x2, y2, z2] = om, h = 0.5 * dt;
  const nx = q[0] + h * (w1 * x2 + y1 * z2 - z1 * y2);
  const ny = q[1] + h * (w1 * y2 + z1 * x2 - x1 * z2);
  const nz = q[2] + h * (w1 * z2 + x1 * y2 - y1 * x2);
  const nw = q[3] + h * (-(x1 * x2 + y1 * y2 + z1 * z2));
  const n = Math.hypot(nx, ny, nz, nw) || 1;
  return [nx / n, ny / n, nz / n, nw / n];
}

/* ---- derived grid properties, cached --------------------------------------
   Mass, centre of mass, the diagonal inertia, and every thruster's arm. All of
   it is a pure function of the grid, so it is cached against the grid object and
   dropped whenever a block moves. The blocks.size check is a safety net for a
   builder that forgets to call the hooks. */
const CACHE = new WeakMap();
export function invalidate(grid) { if (grid) CACHE.delete(grid); }

function torqueOf(type) {
  if (typeof type.torque === 'number') return type.torque;
  return type.id === 'gyro' ? GYRO_TORQUE_LARGE : 0;
}

export function props(ship, api) {
  const grid = ship.grid;
  const hit = CACHE.get(grid);
  if (hit && hit.n === grid.blocks.size) return hit;

  const G = api.grid;
  const s = G.cellM(grid.sizeClass);                 // NEVER 2.5. See DESIGN.md.
  const vol = s / G.SIZE_CLASSES.large.m;            // class ratio, 1 at `large`
  const m = api.mass(ship);
  const com = api.com(ship);
  const I = [0, 0, 0];
  const thrusters = [];
  let gyroTorque = 0, hasCockpit = false;

  for (const b of G.uniqueBlocks(grid)) {
    const t = G.BLOCK_TYPES[b.type];
    const cells = G.cellsOf(b.type, b.cell, b.rot);
    const bm = G.blockMass(grid, b);
    const w = bm / cells.length;
    let px = 0, py = 0, pz = 0;
    for (const c of cells) {
      const x = (c[0] + 0.5) * s, y = (c[1] + 0.5) * s, z = (c[2] + 0.5) * s;
      px += x; py += y; pz += z;
      const dx = x - com[0], dy = y - com[1], dz = z - com[2];
      const self = w * s * s / 6;                    // the cell's own cube term
      I[0] += w * (dy * dy + dz * dz) + self;
      I[1] += w * (dz * dz + dx * dx) + self;
      I[2] += w * (dx * dx + dy * dy) + self;
    }
    if (t.cockpit) hasCockpit = true;
    gyroTorque += torqueOf(t) * vol * vol * vol;
    if (t.thrust) {
      /* A thruster pushes along its own -Z. rotVec of an axis under an integer
         rotation is always another axis, so `axis` and `sign` are exact. */
      const push = G.rotVec(b.rot, [0, 0, -1]);
      const axis = push[0] ? 0 : push[1] ? 1 : 2;
      thrusters.push({
        axis, sign: push[axis],
        force: t.thrust * vol * vol,                 // area scales as the square
        r: [px / cells.length - com[0],
            py / cells.length - com[1],
            pz / cells.length - com[2]],
      });
    }
  }
  /* An empty or massless grid must not divide by zero downstream. */
  for (let i = 0; i < 3; i++) if (!(I[i] > EPS)) I[i] = Math.max(m, 1) * s * s / 6 || 1;

  const out = { n: grid.blocks.size, mass: m, com, inertia: I,
                thrusters, gyroTorque, hasCockpit, cellM: s };
  CACHE.set(grid, out);
  return out;
}

/** 0..1. A ship that cannot power its draw cannot fire its thrusters — the
    reactor is a block you welded on, not a setting. */
export function powerFactor(ship, api) {
  const p = api.power(ship);
  if (!(p.drawn > 0)) return 1;
  return clamp(p.produced / p.drawn, 0, 1);
}

/* ---- the control input ----------------------------------------------------
   ONE place both the player and an autopilot write, neither knowing about the
   other: ship.data.flight.cmd. -1..1 per axis, in the ship's OWN axes.
     lin[0] right   lin[1] up      lin[2] forward(+Z)
     ang[0] pitch   ang[1] yaw     ang[2] roll
   `damp` is not a capability granted by a toggle — the capability is the
   thrusters and gyros. It is a mode of the flight computer in the cockpit, and
   with no cockpit there is no damping and no command at all. */
export function ensure(ship) {
  let st = ship.data.flight;
  if (!st) {
    st = ship.data.flight = {
      cmd: { lin: zero3(), ang: zero3() },
      damp: true,
      control: 'ok',       // 'ok' | 'no-cockpit' | 'no-power' | 'no-mass'
      noWorld: false,      // no planet attached: flying, but nothing pulls
      accel: zero3(),      // telemetry, planet frame, m/s^2 (thrust only)
      alpha: zero3(),      // telemetry, body frame, rad/s^2
      thrustN: 0,          // telemetry, newtons actually fired this tick
    };
  }
  if (!st.cmd) st.cmd = { lin: zero3(), ang: zero3() };
  if (!st.cmd.lin) st.cmd.lin = zero3();
  if (!st.cmd.ang) st.cmd.ang = zero3();
  return st;
}

/** Write a command. Anyone may call it; nobody has to know who else does. */
export function command(ship, lin, ang) {
  const st = ensure(ship);
  if (lin) for (let i = 0; i < 3; i++) st.cmd.lin[i] = clamp(lin[i] || 0, -1, 1);
  if (ang) for (let i = 0; i < 3; i++) st.cmd.ang[i] = clamp(ang[i] || 0, -1, 1);
  return st;
}
export function setDamp(ship, on) { ensure(ship).damp = !!on; return ship.data.flight; }

/* ---- the step -------------------------------------------------------------- */
export function stepShip(ship, dt, api) {
  const d = clamp(dt, 0, MAX_DT);
  if (!(d > 0)) return;
  const st = ensure(ship);
  const P = props(ship, api);
  if (!(P.mass > 0)) { st.control = 'no-mass'; return; }

  /* gravity: see workaround 2 at the top of this file. */
  let gP = zero3(), worldless = false;
  try { gP = api.gravity(ship); } catch { worldless = true; }

  const pf = powerFactor(ship, api);
  /* Two separate facts, deliberately not folded into one: `noWorld` says the
     harness has no planet (published rather than swallowed — workaround 2), and
     `control` says why the stick does nothing. A worldless ship still flies;
     only gravity is missing. */
  st.noWorld = worldless;
  st.control = !P.hasCockpit ? 'no-cockpit' : pf <= 0 ? 'no-power' : 'ok';
  const flyable = P.hasCockpit && pf > 0;

  const cl = flyable ? st.cmd.lin : zero3();
  const ca = flyable ? st.cmd.ang : zero3();
  const vL = qRotInv(ship.ori, ship.vel);
  const gL = qRotInv(ship.ori, gP);

  /* ---- linear: newtons demanded per body axis, and what fraction of that
     axis's thrusters that is (which is what the torque loop needs). */
  const demand = zero3(), frac = zero3();
  for (let i = 0; i < 3; i++) {
    const c = clamp(cl[i], -1, 1);
    if (Math.abs(c) > EPS) {
      const dir = zero3(); dir[i] = Math.sign(c);
      demand[i] = c * api.thrust(ship, dir) * pf;
      frac[i] = Math.abs(c);
    } else if (flyable && st.damp) {
      /* Null the velocity this axis will have at the end of the step, gravity
         included — which is what makes a hover emergent rather than a special
         case: enough thrust and you hang there, not enough and you sink at the
         rate the shortfall buys. Never overshoots: the force asked for is
         exactly the one that reaches zero, and it is clamped to what exists. */
      const need = -P.mass * (vL[i] / d + gL[i]);
      if (Math.abs(need) > EPS) {
        const dir = zero3(); dir[i] = Math.sign(need);
        const avail = api.thrust(ship, dir) * pf;
        demand[i] = clamp(need, -avail, avail);
        frac[i] = avail > EPS ? Math.abs(demand[i]) / avail : 0;
      }
    }
  }

  /* ---- torque from thrust that misses the centre of mass ------------------ */
  const tau = zero3();
  let fired = 0;
  for (const t of P.thrusters) {
    const f = frac[t.axis];
    if (f <= EPS) continue;
    if (Math.sign(demand[t.axis]) !== t.sign) continue;
    const mag = t.force * f * pf;
    fired += mag;
    const F = zero3(); F[t.axis] = t.sign * mag;
    tau[0] += t.r[1] * F[2] - t.r[2] * F[1];
    tau[1] += t.r[2] * F[0] - t.r[0] * F[2];
    tau[2] += t.r[0] * F[1] - t.r[1] * F[0];
  }
  st.thrustN = fired;

  /* ---- gyros: command, or hold attitude against the thrusters -------------- */
  const gmax = P.gyroTorque * pf;
  const gy = zero3();
  for (let i = 0; i < 3; i++) {
    const a = clamp(ca[i], -1, 1);
    if (Math.abs(a) > EPS) gy[i] = a * gmax;
    else if (flyable && st.damp) gy[i] = -P.inertia[i] * ship.spin[i] / d - tau[i];
  }
  const gl = Math.hypot(gy[0], gy[1], gy[2]);
  if (gl > gmax) { const k = gmax / gl; gy[0] *= k; gy[1] *= k; gy[2] *= k; }

  /* ---- integrate (semi-implicit Euler) ------------------------------------ */
  const aL = [demand[0] / P.mass, demand[1] / P.mass, demand[2] / P.mass];
  const aP = qRot(ship.ori, aL);
  st.accel = aP;
  for (let i = 0; i < 3; i++) {
    ship.vel[i] += (aP[i] + gP[i]) * d;
    ship.pos[i] += ship.vel[i] * d;
    const al = (tau[i] + gy[i]) / P.inertia[i];
    st.alpha[i] = al;
    ship.spin[i] += al * d;
  }
  ship.ori = integrateOri(ship.ori, ship.spin, d);
}

/* ---- the system ------------------------------------------------------------ */
const SYS = {
  id: 'flight',

  /* A SOFT dependency. The contract's example says needs:['power'], but
     systemOrder() THROWS on a needs entry that is not registered, and eight
     agents are landing files independently — a hard edge to a file that may not
     exist yet would take the whole layer down. `needs` is read live by
     systemOrder(), so this asks for the ordering only when there is something to
     order against. */
  get needs() { return systems().some((s) => s.id === 'power') ? ['power'] : []; },

  init(ship, api) { ensure(ship); invalidate(ship.grid); },
  tick(ship, dt, api) { stepShip(ship, dt, api); },

  onBlockAdded(ship) { invalidate(ship.grid); },
  onBlockRemoved(ship) { invalidate(ship.grid); },

  /* THE B2 GATE. The halves already fly correctly — they are grids, and every
     number came off the grid. All this does is give each half where it was, how
     fast it was going, and the velocity its own offset from the old centre of
     mass had because the parent was spinning. A half with no cockpit keeps its
     momentum and loses its command: it is debris, and it tumbles. */
  onSplit(parent, children, api) {
    invalidate(parent.grid);
    const kids = (children || []).filter((c) => c && c.grid && c.pos);
    if (!kids.length) return;
    for (const c of kids) invalidate(c.grid);

    let pCom = api.com(parent);
    if (!parent.grid.blocks.size) {
      let m = 0; const acc = zero3();
      for (const c of kids) {
        const cm = api.mass(c), cc = api.com(c);
        m += cm; for (let i = 0; i < 3; i++) acc[i] += cc[i] * cm;
      }
      if (m > 0) pCom = [acc[0] / m, acc[1] / m, acc[2] / m];
    }
    const pOri = [...parent.ori], pSpin = [...parent.spin];
    const pDamp = ensure(parent).damp;
    const pCmd = ensure(parent).cmd;

    for (const c of kids) {
      if (c === parent) continue;
      const cCom = api.com(c);
      const off = [cCom[0] - pCom[0], cCom[1] - pCom[1], cCom[2] - pCom[2]];
      const dP = qRot(pOri, off);
      const spinV = qRot(pOri, [pSpin[1] * off[2] - pSpin[2] * off[1],
                               pSpin[2] * off[0] - pSpin[0] * off[2],
                               pSpin[0] * off[1] - pSpin[1] * off[0]]);
      c.ori = [...pOri];
      c.spin = [...pSpin];
      c.pos = [parent.pos[0] + dP[0], parent.pos[1] + dP[1], parent.pos[2] + dP[2]];
      c.vel = [parent.vel[0] + spinV[0], parent.vel[1] + spinV[1], parent.vel[2] + spinV[2]];
      const st = ensure(c);
      st.damp = pDamp;
      const keeps = props(c, api).hasCockpit;
      st.cmd.lin = keeps ? [...pCmd.lin] : zero3();
      st.cmd.ang = keeps ? [...pCmd.ang] : zero3();
    }
  },

  /* A command is an INPUT, not state — saving it would have a reloaded ship
     still holding the throttle down. The dampener mode is a real setting. */
  save(ship) { return { damp: ensure(ship).damp }; },
  load(ship, saved) { if (saved && 'damp' in saved) setDamp(ship, saved.damp); },
};

registerSystem(SYS);
export default SYS;
