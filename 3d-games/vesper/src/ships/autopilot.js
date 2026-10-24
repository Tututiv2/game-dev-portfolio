/* ============================================================================
   AUTOPILOT — the NPC pilots who fly your other ships.

   A ship whose `owner` is not 'player' is flown by a person who is not you. This
   file is that person. It does exactly one thing: it writes the SAME control
   input a player's hands write, every tick, and then gets out of the way.

   ---------------------------------------------------------------------------
   THE RULE THAT SHAPES THE WHOLE FILE

   There is no "npc mode". No teleport, no velocity assignment, no "snap to
   formation", no kinematic path following. The autopilot's entire output is:

       ship.data.ctl.lin  -> -1..1 per SHIP-LOCAL axis  (translation demand)
       ship.data.ctl.ang  -> -1..1 per SHIP-LOCAL axis  (rotation demand)

   which is the identical surface a stick and a keyboard write to. The reason is
   not purity for its own sake: the moment an NPC ship is allowed a shortcut, it
   stops obeying its own mass, its own thruster layout and its own damage, and
   the first time the player WATCHES one — which is the only reason NPC ships
   exist — the illusion is over. A follower with half its thrusters shot off has
   to fall behind. That only happens if it is flying the same way you are.

   Corollary, and it is load-bearing: this file must never read a flight model.
   It commands a normalised demand and flight decides what that is worth in
   newtons. If there is no thrust that way, the ship does not go that way.

   ---------------------------------------------------------------------------
   THE DESIGN LAW APPLIES HERE

   CLAUDE.md: "There always has to be a path in game." So:

     - THERE IS NO HIRING MENU. Ownership is a fact already decided elsewhere and
       read off `ship.owner`. This file never assigns it.
     - AN NPC FLIES FROM A COCKPIT. A grid with no cockpit block gets no
       autopilot, because there is nowhere for the pilot to sit. The capability
       comes from a physical object that somebody welded on, which is the whole
       of the law. `npm run` nothing: build a cockpit.

   ---------------------------------------------------------------------------
   THE ORDER, which lives on ship.data.autopilot and is JSON-safe

     {
       mode: 'formation' | 'station' | 'waypoint' | 'hold' | 'off',

       leader:  'ship-id',      // formation: whose wing you are on
       offset:  [x, y, z],      // formation: METRES in the LEADER'S OWN FRAME,
                                //   so the formation turns when they turn

       radius:  metres,         // station: orbital radius to hold. null = the
                                //   radius the ship was at when ordered

       waypoints: [[x,y,z]...], // waypoint: planet-frame points, in order
       wp: 0,                   // which one we are on. Saved, so a reload does
                                //   not restart the route
       loop: false,

       cruise: metres/second,   // speed cap. Optional
       arrive: metres,          // how close counts as arrived. Optional
     }

   POSITIONS ARE IN THE PLANET'S FRAME, all of them, always. Not one line of
   this file converts to world space; `api.toWorld` exists for the renderer.

   ---------------------------------------------------------------------------
   THE CONTROL LAW, and why it is this one

   The obvious autopilot is a PD controller on position error. The obvious
   autopilot also jitters, because the actuator saturates: far from the target it
   commands more acceleration than the ship has, arrives with too much speed,
   overshoots, and rings. A follower that trembles at 2 Hz looks broken even when
   the maths is right, and the owner judges by eye.

   So the law is a VELOCITY law, not an acceleration law. Every tick it asks
   "what velocity should I have right now", then leans on the throttle to get
   there:

       v_want = min( cruise,
                     sqrt(2 * a_safe * distance),   <- can I still stop in time
                     distance / tauPos )            <- exponential settle, close in
              * direction_to_target
              + v_of_the_target                     <- feed-forward

       a_cmd  = (v_want - v) / tauVel + a_of_the_target - gravity

   The sqrt branch is a braking profile: it never asks for a speed the ship
   cannot shed before it arrives, which is what removes overshoot at range. The
   linear branch takes over near the target, where sqrt has an infinite slope and
   would chatter. Unsaturated, the linear branch is a second-order system with

       zeta = sqrt(tauPos / tauVel) / 2

   so tauPos = 4 * tauVel is critically damped, and that is exactly the ratio the
   defaults use. A pilot's personal gains vary by +-15% (rolled from api.rand, so
   the same pilot flies the same way after a reload) which moves zeta to
   0.92..1.08 — still nowhere near ringing.

   THE TWO FEED-FORWARD TERMS ARE THE SUBTLE PART and getting them backwards
   costs a whole behaviour. Gravity is subtracted because a velocity law has no
   integrator, so without it a hovering follower sinks until the damping term
   balances the weight — a permanent droop of g * tauVel that no gain fixes. But
   the TARGET's own acceleration is added back, and that is what stops the same
   subtraction from wrecking an orbit: a station holding a circular orbit is
   accelerating at exactly g, so its two feed-forwards cancel, the autopilot
   commands nothing, and GRAVITY does the turning — which is what an orbit is.
   Cancel gravity there and the ship would thrust itself into a straight line.
   For a formation the target's acceleration is the leader's, which cannot be
   read from the contract, so it is differenced from their velocity and low-pass
   filtered — that estimate is right for a hovering leader (0) and for a
   coasting one (g) without this file having to know which it is looking at.

   ---------------------------------------------------------------------------
   WHAT THE CONTRACT DID NOT GIVE ME, AND HOW I WORKED AROUND IT

   1. THERE IS NO WAY TO ASK WHETHER A WORLD IS ATTACHED. `api.gravity` throws by
      design (ships.mjs asserts that it throws, and it is right to). An autopilot
      that only works after a planet is built is useless in a test and useless in
      deep space, so gravity is read through a try/catch and remembered as
      absent, re-probed every RE_PROBE ticks in case a world arrives later.

   2. THERE IS NO DECLARED SHAPE FOR THE CONTROL INPUT. `flight` is being written
      in parallel and owns it. `ctlOf()` below therefore ADOPTS whatever control
      object already exists on ship.data rather than insisting on its own — it
      looks for a container under a few likely names and for the vector pair
      under a few likely names, and only creates the canonical `ctl.lin/ang` if
      it finds nothing. If flight lands on a different shape, add its names to
      CTL_NAMES and nothing else in this file changes.

   3. `api.thrust` IS THE ONLY CAPABILITY READOUT. There is no torque figure
      anywhere in grid.js — a gyro block has mass, hp and power but no rating —
      so the angular channel is a normalised RATE demand against `omegaRef`
      rather than a torque in newton-metres. If NPC ships turn sluggishly or
      wobble once flight lands, omegaRef is the single knob.
   ========================================================================= */

import { registerSystem } from './api.js';

/* ---- tunables. Every one of these is a number that decides how something
   looks or feels, so it is a named constant and not a literal buried in a
   branch — CLAUDE.md trap 25, "a literal cannot be swept". ---------------- */
export const AP = {
  tauVel: 0.8,        // seconds to reach a commanded velocity
  tauPos: 3.2,        // = 4 * tauVel, i.e. critically damped
  brakeSafety: 0.35,  // fraction of available accel the braking profile trusts
  cruise: 300,        // m/s cap unless the order says otherwise
  arriveCells: 4,     // waypoint arrival radius, in CELLS of this grid's class
  holdCells: 0.04,    // station-keeping deadband, in CELLS
  deadCtl: 0.004,     // below this a demand is written as an exact zero
  tauAcc: 1.0,        // low-pass on the estimate of a leader's acceleration
  tauAng: 4.0,        // seconds to null an attitude error
  omegaRef: 0.5,      // rad/s that a full-scale ang demand is worth
  spinCap: 0.35,      // rad/s the autopilot will ever ask for
  personality: 0.15,  // +-15% per-pilot gain spread
  reProbe: 300,       // ticks between re-checks for a world appearing
};

/* ---- names this file will ADOPT rather than fight over (gap 2 above) ----- */
const CTL_NAMES = ['ctl', 'control', 'controls', 'input'];
const VEC_PAIRS = [['lin', 'ang'], ['move', 'turn'], ['thrust', 'torque'],
                   ['translate', 'rotate'], ['lin', 'rot']];

/* ---- small vector / quaternion maths. Pure, no library. ------------------ */
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1],
                         a[2] * b[0] - a[0] * b[2],
                         a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => { const l = len(a); return l > 1e-12 ? mul(a, 1 / l) : [0, 0, 0]; };
const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const finite = (v) => Number.isFinite(v) ? v : 0;

export const qConj = (q) => [-q[0], -q[1], -q[2], q[3]];

export function qMul(a, b) {
  return [
    a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
    a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
    a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
    a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
  ];
}

/** Rotate a vector by a quaternion. */
export function qRot(q, v) {
  const [x, y, z, w] = q;
  const ix = w * v[0] + y * v[2] - z * v[1];
  const iy = w * v[1] + z * v[0] - x * v[2];
  const iz = w * v[2] + x * v[1] - y * v[0];
  const iw = -x * v[0] - y * v[1] - z * v[2];
  return [ix * w + iw * -x + iy * -z - iz * -y,
          iy * w + iw * -y + iz * -x - ix * -z,
          iz * w + iw * -z + ix * -y - iy * -x];
}

/** A quaternion whose local +Z lands on `fwd` and whose local +Y leans toward
    `up`. Falls back to any perpendicular when the two are parallel — a look-at
    that returns NaN once puts NaN in the ship's orientation for ever. */
export function qLook(fwd, up) {
  let f = norm(fwd);
  if (len(f) < 0.5) return [0, 0, 0, 1];
  let u = norm(up);
  if (len(u) < 0.5 || Math.abs(dot(f, u)) > 0.999) {
    const alt = Math.abs(f[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0];
    u = norm(sub(alt, mul(f, dot(alt, f))));
  } else {
    u = norm(sub(u, mul(f, dot(u, f))));
  }
  const r = cross(u, f);
  const m = [r, u, f];
  const tr = m[0][0] + m[1][1] + m[2][2];
  if (tr > 0) {
    const s = Math.sqrt(tr + 1) * 2;
    return [(m[1][2] - m[2][1]) / s, (m[2][0] - m[0][2]) / s,
            (m[0][1] - m[1][0]) / s, 0.25 * s];
  }
  if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
    const s = Math.sqrt(1 + m[0][0] - m[1][1] - m[2][2]) * 2;
    return [0.25 * s, (m[1][0] + m[0][1]) / s, (m[2][0] + m[0][2]) / s,
            (m[1][2] - m[2][1]) / s];
  }
  if (m[1][1] > m[2][2]) {
    const s = Math.sqrt(1 + m[1][1] - m[0][0] - m[2][2]) * 2;
    return [(m[1][0] + m[0][1]) / s, 0.25 * s, (m[2][1] + m[1][2]) / s,
            (m[2][0] - m[0][2]) / s];
  }
  const s = Math.sqrt(1 + m[2][2] - m[0][0] - m[1][1]) * 2;
  return [(m[2][0] + m[0][2]) / s, (m[2][1] + m[1][2]) / s, 0.25 * s,
          (m[0][1] - m[1][0]) / s];
}

/** The rotation that takes `cur` to `des`, as an axis*angle vector expressed in
    CUR's own local axes — which is the frame `ship.spin` is already in, so the
    two can be differenced without another change of basis. Shortest path. */
export function attitudeError(cur, des) {
  let e = qMul(des, qConj(cur));
  if (e[3] < 0) e = [-e[0], -e[1], -e[2], -e[3]];
  const s = Math.hypot(e[0], e[1], e[2]);
  if (s < 1e-9) return [0, 0, 0];
  const ang = 2 * Math.atan2(s, clamp(e[3], -1, 1));
  const axisWorld = [e[0] / s, e[1] / s, e[2] / s];
  return qRot(qConj(cur), mul(axisWorld, ang));
}

/* ---- the control surface -------------------------------------------------- */

/** Find, or create, the object a player's hands would be writing to. */
export function ctlOf(ship) {
  for (const n of CTL_NAMES) {
    const c = ship.data[n];
    if (c && typeof c === 'object') return c;
  }
  ship.data.ctl = { lin: [0, 0, 0], ang: [0, 0, 0], dampen: true, src: 'idle' };
  return ship.data.ctl;
}

/** Which pair of key names this control object actually uses. */
function ctlKeys(c) {
  for (const [a, b] of VEC_PAIRS)
    if (Array.isArray(c[a]) && Array.isArray(c[b])) return [a, b];
  return ['lin', 'ang'];
}

function writeCtl(ship, lin, ang) {
  const c = ctlOf(ship);
  const [kl, ka] = ctlKeys(c);
  if (!Array.isArray(c[kl])) c[kl] = [0, 0, 0];
  if (!Array.isArray(c[ka])) c[ka] = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    let v = clamp(finite(lin[i]), -1, 1);
    if (Math.abs(v) < AP.deadCtl) v = 0;
    c[kl][i] = v;
    let w = clamp(finite(ang[i]), -1, 1);
    if (Math.abs(w) < AP.deadCtl) w = 0;
    c[ka][i] = w;
  }
  c.src = 'autopilot';
  return c;
}

/* ---- state ---------------------------------------------------------------- */

/** The order. Created empty rather than absent so a caller can just set fields. */
export function orders(ship) {
  if (!ship.data.autopilot || typeof ship.data.autopilot !== 'object')
    ship.data.autopilot = { mode: 'hold' };
  return ship.data.autopilot;
}

function stateOf(ship, api) {
  let st = ship.data._ap;
  if (!st || typeof st !== 'object') {
    /* The pilot's own hands. Rolled from api.rand so it survives a reload; a
       Math.random here would desync the save silently (contract rule 3). */
    const j = () => 1 + (api.rand(ship) * 2 - 1) * AP.personality;
    st = { gp: j(), gv: j(), noWorld: false, probe: 0, hold: null, orbN: null,
           lid: null, lv: null, la: [0, 0, 0] };
    ship.data._ap = st;
  }
  return st;
}

/** Gravity, or zero, without needing the world to exist (contract gap 1). */
function gravOf(ship, api, st) {
  if (st.noWorld && st.probe > 0) { st.probe--; return [0, 0, 0]; }
  try {
    const g = api.gravity(ship);
    st.noWorld = false;
    return g;
  } catch {
    st.noWorld = true;
    st.probe = AP.reProbe;
    return [0, 0, 0];
  }
}

/** Is there anywhere for a pilot to sit? The design law, in one function. */
export function hasCockpit(ship, api) {
  for (const b of api.grid.uniqueBlocks(ship.grid)) {
    const t = api.grid.BLOCK_TYPES[b.type];
    if (t && t.cockpit) return true;
  }
  return false;
}

const cellsM = (ship, api, n) => api.grid.cellM(ship.grid.sizeClass) * n;

/* ---- the flight director -------------------------------------------------- */

/** Available acceleration along a PLANET-frame unit direction, m/s^2. */
function accelAlong(ship, api, dirPlanet) {
  const m = api.mass(ship);
  if (!(m > 0)) return 0;
  const local = qRot(qConj(ship.ori), dirPlanet);
  return api.thrust(ship, local) / m;
}

/** THE LAW. Target position and target velocity in the planet frame, in;
    commanded acceleration in the planet frame, out. Exported so a test can
    drive it directly and so the failure mode is inspectable. */
export function commandAccel(ship, api, st, target, targetVel, cruise) {
  const err = sub(target, ship.pos);
  const d = len(err);
  const dir = d > 1e-9 ? mul(err, 1 / d) : [0, 0, 0];

  const aCap = Math.max(accelAlong(ship, api, dir), 0);
  const aSafe = Math.max(aCap * AP.brakeSafety, 1e-6);

  const vBrake = Math.sqrt(2 * aSafe * d);          // can I still stop in time
  const vNear = d / (AP.tauPos * st.gp);            // exponential settle, close in
  const vMag = Math.min(cruise, vBrake, vNear);

  const vWant = add(mul(dir, vMag), targetVel);
  return mul(sub(vWant, ship.vel), 1 / (AP.tauVel * st.gv));
}

/** Commanded acceleration -> the throttle a player would be holding. Each axis
    is normalised by what that axis can ACTUALLY do, so a ship with one big
    thruster and five small ones flies like one. */
function linDemand(ship, api, aCmd) {
  const m = api.mass(ship);
  const local = qRot(qConj(ship.ori), aCmd);
  const out = [0, 0, 0];
  if (!(m > 0)) return out;
  for (let i = 0; i < 3; i++) {
    if (Math.abs(local[i]) < 1e-12) continue;
    const dir = [0, 0, 0];
    dir[i] = local[i] > 0 ? 1 : -1;
    const cap = api.thrust(ship, dir) / m;
    out[i] = cap > 1e-9 ? clamp(local[i] / cap, -1, 1) : 0;
  }
  return out;
}

/** Attitude -> stick. A rate law: null the error at tauAng, cap the rate, then
    ask for the difference against the rate we already have. */
function angDemand(ship, st, qDes) {
  const e = attitudeError(ship.ori, qDes);
  const mag = len(e);
  const wWant = mag > 1e-9
    ? mul(mul(e, 1 / mag), Math.min(AP.spinCap, mag / (AP.tauAng * st.gp)))
    : [0, 0, 0];
  const w = ship.spin || [0, 0, 0];
  return [ (wWant[0] - w[0]) / AP.omegaRef,
           (wWant[1] - w[1]) / AP.omegaRef,
           (wWant[2] - w[2]) / AP.omegaRef ];
}

/* ---- the behaviours ------------------------------------------------------- */

function findShip(api, id) {
  for (const s of api.ships()) if (s.id === id) return s;
  return null;
}

/** Where a follower should be, and how fast. The offset is rotated by the
    LEADER'S orientation, which is the whole point of a formation: bank the
    leader and the wing banks with them rather than sliding out sideways. The
    velocity feed-forward includes the leader's own spin crossed into the offset,
    so a turning leader does not drag its wing behind by a constant lag. */
export function formationTarget(leader, offset) {
  const off = qRot(leader.ori, offset);
  const w = leader.spin || [0, 0, 0];
  const vSpin = qRot(leader.ori, cross(w, offset));
  return { pos: add(leader.pos, off), vel: add(leader.vel || [0, 0, 0], vSpin) };
}

function upOf(ship) {
  const r = len(ship.pos);
  return r > 1e-6 ? mul(ship.pos, 1 / r) : [0, 1, 0];
}

/** A stable orbit normal, chosen once and remembered so the ship does not pick
    a different plane on a tick where its velocity happens to be radial. */
function orbitNormal(ship, api, st) {
  const up = upOf(ship);
  const tan = sub(ship.vel, mul(up, dot(ship.vel, up)));
  if (len(tan) > 1e-3) {
    st.orbN = norm(cross(up, norm(tan)));
    return st.orbN;
  }
  if (st.orbN && len(st.orbN) > 0.5) return st.orbN;
  const alt = Math.abs(up[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0];
  st.orbN = norm(cross(up, alt));
  return st.orbN;
}

/* ---- the system ----------------------------------------------------------- */

/** A plan is: where the target IS, how fast it is MOVING, how hard it is
    ACCELERATING, and which way to face. Everything downstream of here is the
    same code for every behaviour. */
export function plan(ship, api, st, dt, g) {
  const o = orders(ship);
  const cruise = Number.isFinite(o.cruise) ? o.cruise : AP.cruise;
  const arrive = Number.isFinite(o.arrive) ? o.arrive : cellsM(ship, api, AP.arriveCells);
  const zero = [0, 0, 0];

  if (o.mode === 'formation') {
    const leader = o.leader ? findShip(api, o.leader) : null;
    /* A leader that is gone is not an error, it is a bereavement: hold where you
       are rather than flying at [0,0,0], which is the planet's centre. */
    if (!leader || leader === ship) return holdPlan(ship, st, cruise);
    const t = formationTarget(leader, o.offset || zero);
    return { pos: t.pos, vel: t.vel, acc: leaderAccel(leader, st, dt),
             ori: leader.ori, cruise };
  }

  if (o.mode === 'waypoint') {
    const wps = Array.isArray(o.waypoints) ? o.waypoints : [];
    if (!wps.length) return holdPlan(ship, st, cruise);
    if (!Number.isFinite(o.wp) || o.wp < 0) o.wp = 0;
    if (o.wp >= wps.length) o.wp = o.loop ? 0 : wps.length - 1;
    if (len(sub(wps[o.wp], ship.pos)) <= arrive) {
      if (o.wp < wps.length - 1) o.wp++;
      else if (o.loop) o.wp = 0;
      else o.arrived = true;
    }
    const next = wps[o.wp];
    const toward = sub(next, ship.pos);
    return { pos: next, vel: zero, acc: zero, cruise,
             ori: len(toward) > arrive ? qLook(toward, upOf(ship)) : null };
  }

  if (o.mode === 'station') {
    const up = upOf(ship);
    const r = len(ship.pos);
    if (!Number.isFinite(o.radius)) o.radius = r;
    const gm = len(g);
    const n = orbitNormal(ship, api, st);
    const tan = norm(cross(n, up));
    /* v = sqrt(g r) is the circular speed, taken from the gravity the api
       already hands us rather than from mu, which the contract does not expose.
       With no world attached gm is 0 and this degenerates to "hold still",
       which is the correct behaviour for a station in deep space. */
    const vOrb = Math.sqrt(Math.max(gm * r, 0));
    const radial = mul(up, -(r - o.radius) / (AP.tauPos * st.gp));
    const vWant = add(mul(tan, vOrb), radial);
    /* The target POSITION is where we already are: station keeping is a
       velocity problem, not a position problem, and asking for a position on a
       circle would fight the orbit every tick. `acc` is gravity, so the two
       feed-forwards cancel and the ship lets itself fall around the planet. */
    return { pos: ship.pos, vel: vWant, acc: g, ori: qLook(tan, up),
             cruise, velOnly: true };
  }

  if (o.mode === 'hold') return holdPlan(ship, st, cruise);
  return null;
}

function holdPlan(ship, st, cruise) {
  if (!st.hold) st.hold = [...ship.pos];
  return { pos: st.hold, vel: [0, 0, 0], acc: [0, 0, 0], ori: null, cruise };
}

/** The leader's acceleration, which the contract does not publish, differenced
    from their velocity and low-passed. A raw difference of two floats a tick
    apart is a differentiator and would put its own noise straight into the
    throttle; the filter is what makes it usable, and it is why the follower's
    steady-state offset behind an accelerating leader is small instead of
    a_leader * tauPos * tauVel. */
function leaderAccel(leader, st, dt) {
  const v = leader.vel || [0, 0, 0];
  if (st.lid !== leader.id) { st.lid = leader.id; st.lv = [...v]; st.la = [0, 0, 0]; }
  if (!(dt > 0)) return st.la || [0, 0, 0];
  const raw = mul(sub(v, st.lv || v), 1 / dt);
  st.lv = [...v];
  const k = 1 - Math.exp(-dt / AP.tauAcc);
  const la = st.la || [0, 0, 0];
  st.la = add(la, mul(sub(raw, la), k));
  return st.la;
}

const SYSTEM = {
  id: 'autopilot',
  /* `needs` is deliberately EMPTY. api.systemOrder() throws on a dependency that
     is not registered, so naming 'flight' here would break every configuration
     that does not happen to include it — including this file's own test. Order
     autopilot before flight at registration and the demand is consumed the same
     tick; after it, and it is consumed the next one. Neither is wrong. */
  needs: [],

  init(ship, api) {
    stateOf(ship, api);
    if (ship.owner !== 'player') orders(ship);
  },

  tick(ship, dt, api) {
    if (ship.owner === 'player') return;          // your hands, not the pilot's
    const o = orders(ship);
    if (o.mode === 'off') return;
    if (!hasCockpit(ship, api)) return;           // nowhere for the pilot to sit

    const st = stateOf(ship, api);
    if (o.mode !== 'hold') st.hold = null;

    const g = gravOf(ship, api, st);

    const p = plan(ship, api, st, dt, g);
    if (!p) return;

    let aCmd;
    if (p.velOnly) {
      aCmd = mul(sub(p.vel, ship.vel), 1 / (AP.tauVel * st.gv));
    } else {
      aCmd = commandAccel(ship, api, st, p.pos, p.vel, p.cruise);
      /* Inside the deadband stop asking for position at all — the velocity term
         alone holds it. Without this, float noise on a 600 km position keeps a
         parked follower shivering, which reads as broken however small it is. */
      const d = len(sub(p.pos, ship.pos));
      if (d < cellsM(ship, api, AP.holdCells))
        aCmd = mul(sub(p.vel, ship.vel), 1 / (AP.tauVel * st.gv));
    }
    aCmd = sub(add(aCmd, p.acc || [0, 0, 0]), g);   // see the header note

    const qDes = p.ori || ship.ori;
    writeCtl(ship, linDemand(ship, api, aCmd), angDemand(ship, st, qDes));
  },

  onSplit(parent, children, api) {
    /* Half a ship is still somebody's ship. The half without the cockpit stops
       being flown by tick()'s own gate, so nothing needs to be cleared. */
    for (const c of children) if (c.data) c.data._ap = null;
  },

  save(ship) {
    const o = ship.data.autopilot;
    const st = ship.data._ap;
    return {
      autopilot: o ? JSON.parse(JSON.stringify(o)) : null,
      _ap: st ? { gp: st.gp, gv: st.gv, hold: st.hold, orbN: st.orbN } : null,
    };
  },

  load(ship, saved) {
    if (!saved) return;
    if (saved.autopilot) ship.data.autopilot = saved.autopilot;
    if (saved._ap)
      ship.data._ap = { ...saved._ap, noWorld: false, probe: 0 };
  },
};

export default registerSystem(SYSTEM);
export { SYSTEM };
