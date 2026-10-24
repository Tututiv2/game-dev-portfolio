/* ============================================================================
   HUD — what the pilot can see about their own ship.

   PURE. This file returns a DATA STRUCTURE and draws nothing. Anything that
   wants pixels reads `readout()` and lays it out; a `hud_view.js` may import
   three, this may not. That split is the whole reason the numbers below can be
   checked headlessly against an independent oracle (tests/ships_hud.mjs
   integrates a descent numerically and compares it to the analytic stop margin).

   ---------------------------------------------------------------------------
   THE TWO EDITORIAL RULES, borrowed from this portfolio's own scar tissue

   1. A READOUT THE PLAYER CANNOT ACT ON IS NOISE. PYRE's HUD ended up as one
      number (scrap) because "score at the top AND bank in the panel" was two
      numbers for one decision. So: no quaternion, no raw position, no spin
      vector, no block count for its own sake. Every field below either changes
      what you build or changes what you do with the stick.

   2. PREFER ONE NUMBER OVER TWO THE PLAYER MUST SUBTRACT. Hence:
        payloadKg   signed. + = kg you may still bolt on. - = kg you must strip.
        stopMargin  metres of altitude you would have LEFT after braking.
        powerSpare  produced - drawn, once, signed.
      A pilot should never be doing arithmetic while descending.

   THE HEADLINE. Thrust-to-weight under 1.0 means this ship cannot leave THIS
   world, which is the single most useful thing a builder can be told, and it is
   already implied by mass, thrust and gravity — so it is said in words:
        "CANNOT LIFT OFF THIS WORLD — 0.68 : 1, shed 2,140 kg or add thrust"
   TWR is against the LOCAL gravity, not a constant: the same hull lifts off a
   moon and is a paperweight on a heavy world, and api.gravity() is inverse
   square from the planet's own mu, so that difference is real.

   ---------------------------------------------------------------------------
   THE DESIGN LAW APPLIES TO KNOWLEDGE TOO

   DESIGN.md: the natives hold the survival information, "the law applied to
   knowledge instead of to capability". So the instruments are not a menu the
   player switches on — the readout exists because there is a COCKPIT in the
   grid, a block that was welded on out of gathered material. No cockpit, no
   numbers: `ok:false`, and the reason says which block to go and build. No
   block type is defined here on purpose — `cockpit` already exists in grid.js,
   and defineBlock() throws on a duplicate id, which with eight agents in this
   directory at once is a collision waiting to happen.

   ---------------------------------------------------------------------------
   WHAT THE CONTRACT DID NOT GIVE ME, AND WHAT I DID ABOUT IT
   (written here rather than reached around, as api.js asks)

   (a) NO GRID-FRAME <-> PLANET-FRAME TRANSFORM. api.thrust(ship, dir) wants a
       direction in GRID space, but "up" is a fact about the planet frame
       (pos/|pos|). api.toWorld() is the wrong hop — planet -> world. So this
       file carries `qRotInv()`, the ship's own ori applied backwards, written
       to match api.toWorld's quaternion convention exactly so the two can never
       disagree about handedness. It is arithmetic on plain arrays and reaches
       into nothing. If api ever grows api.toGrid(ship, v), delete it and call
       that instead.

   (b) NO PLANET ANGULAR VELOCITY. `pos` and `vel` are both in the planet's
       frame and `vel` is the rate of change of `pos`, so it is ALREADY
       surface-relative and there is no omega x r to subtract — provided that
       frame really is body-fixed, which is what api.toWorld's "planet group's
       quaternion" (which carries the spin) says. If the api ever exposes
       w.spinRate, the correction goes in surfaceVel() and nowhere else.

   (c) NO WAY TO ASK WHETHER A WORLD IS ATTACHED. api.gravity/altitude throw
       when it is not, and rightly (a ship that silently never falls costs a
       session). A readout that throws kills the frame, so those two calls are
       caught here — and the fields come back NULL, never 0. A zero altitude is
       "you are on the ground"; null is "there is no nav data", and the two must
       never be confusable. tests assert null, not falsy.

   (d) NOTHING IS SAVED. Every field is derived from the grid and the ship's
       state, so there is no save()/load() — nothing of the HUD's belongs in the
       sparse diff, and a cached readout in the save file would be a stale lie
       after any edit.
   ========================================================================= */

import { registerSystem } from './api.js';

/* ---- small pure helpers ---------------------------------------------------- */
const len = (v) => Math.hypot(v[0], v[1], v[2]);

/** Planet frame -> GRID frame. The inverse of the ship's ori, using the same
    quaternion convention as api.toWorld (q v q*), conjugated. Normalised on the
    way in so a drifted ori cannot quietly scale the thrust direction. */
export function qRotInv(q, v) {
  const n = Math.hypot(q[0], q[1], q[2], q[3]) || 1;
  const qx = -q[0] / n, qy = -q[1] / n, qz = -q[2] / n, qw = q[3] / n;
  const [x, y, z] = v;
  const ix =  qw * x + qy * z - qz * y;
  const iy =  qw * y + qz * x - qx * z;
  const iz =  qw * z + qx * y - qy * x;
  const iw = -qx * x - qy * y - qz * z;
  return [ix * qw + iw * -qx + iy * -qz - iz * -qy,
          iy * qw + iw * -qy + iz * -qx - ix * -qz,
          iz * qw + iw * -qz + ix * -qy - iy * -qx];
}

/** Thousands separators without Intl or a locale — deterministic everywhere. */
export function fmt(x, dp = 0) {
  if (x === null || !Number.isFinite(x)) return '--';
  const neg = x < 0, s = Math.abs(x).toFixed(dp);
  const [i, f] = s.split('.');
  const g = i.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return (neg ? '-' : '') + g + (f ? '.' + f : '');
}

/** Velocity split into "how fast am I falling" and "how fast am I moving over
    the ground" — the two a pilot flies by. Signed vertical: + is climbing. */
export function surfaceVel(ship) {
  const r = len(ship.pos);
  if (r < 1e-6) return { up: [0, 1, 0], vertical: 0, ground: len(ship.vel), speed: len(ship.vel) };
  const up = [ship.pos[0] / r, ship.pos[1] / r, ship.pos[2] / r];
  const vertical = ship.vel[0] * up[0] + ship.vel[1] * up[1] + ship.vel[2] * up[2];
  const speed = len(ship.vel);
  const ground = Math.sqrt(Math.max(0, speed * speed - vertical * vertical));
  return { up, vertical, ground, speed };
}

/** Damage and wholeness. `pieces > 1` means the grid is already in bits — B2's
    gate is "cut it in half and both halves behave", so the pilot is told. */
export function integrityOf(grid, api) {
  const blocks = api.grid.uniqueBlocks(grid);
  let hp = 0, hpMax = 0;
  for (const b of blocks) {
    const t = api.grid.BLOCK_TYPES[b.type];
    hp += Math.max(0, b.hp);
    hpMax += t ? t.hp : 0;
  }
  return {
    blocks: blocks.length,
    hp, hpMax,
    frac: hpMax > 0 ? hp / hpMax : 0,
    pieces: blocks.length ? api.grid.islands(grid).length : 0,
  };
}

/* ---- THE READOUT ----------------------------------------------------------- */
/**
 * Everything the pilot can see about their own ship, as JSON-safe data.
 * @param ship a ship from api.makeShip
 * @param api  the object every hook is handed
 */
export function readout(ship, api) {
  const grid = ship.grid;
  const cellM = api.grid.cellM(grid.sizeClass);     // never 2.5. B6 is a grid too.
  const blocks = api.grid.uniqueBlocks(grid);
  const cockpit = blocks.some(b => {
    const t = api.grid.BLOCK_TYPES[b.type];
    return t && t.cockpit;
  });

  const base = {
    ok: false, reason: '', lines: [],
    sizeClass: grid.sizeClass, blockM: cellM,
    mass: null, sizeM: null,
    altitude: null, gravity: null, weight: null,
    speed: null, vertical: null, ground: null,
    thrustUp: null, twr: null, canLiftOff: null, payloadKg: null, netUp: null,
    power: null, integrity: integrityOf(grid, api),
    stopMargin: null, timeToGround: null, canStop: null,
    weightless: false, nav: true,
  };

  if (!blocks.length) {
    base.ok = false; base.reason = 'no ship';
    base.lines = [line('none', 'alarm', 'NO GRID')];
    base.nav = false;
    return base;
  }

  const mass = api.mass(ship);
  base.mass = mass;
  const bb = api.grid.bounds(grid);
  base.sizeM = bb ? bb.sizeM.map(v => v) : null;
  base.power = (() => { const p = api.power(ship); return { produced: p.produced, drawn: p.drawn, spare: p.produced - p.drawn, ok: p.ok }; })();

  /* THE INSTRUMENTS ARE A BLOCK YOU BUILT. Not a menu. */
  if (!cockpit) {
    base.ok = false;
    base.reason = 'no cockpit — weld one on and the instruments come with it';
    base.lines = [line('cockpit', 'alarm', 'NO COCKPIT — no instruments')];
    return base;
  }
  base.ok = true;

  const sv = surfaceVel(ship);
  base.speed = sv.speed; base.vertical = sv.vertical; base.ground = sv.ground;

  /* Nav depends on a world. See note (c): null, never 0. */
  let gVec = null, alt = null;
  try { gVec = api.gravity(ship); alt = api.altitude(ship); }
  catch { gVec = null; alt = null; }
  if (gVec === null) {
    base.nav = false;
    base.lines.push(line('nav', 'alarm', 'NO NAV DATA — no world'));
    base.lines.push(...massLines(base));
    return base;
  }

  const g = len(gVec);
  base.gravity = g;
  base.altitude = alt;
  base.weight = mass * g;

  /* Up in GRID space, so api.thrust can be asked the only question that
     matters on a planet: how hard can this thing push AWAY from the ground. */
  const gridUp = qRotInv(ship.ori, sv.up);
  const thrustUp = api.thrust(ship, gridUp);
  base.thrustUp = thrustUp;

  base.weightless = g < 1e-6;
  if (base.weightless) {
    base.twr = null;
    base.payloadKg = null;
    base.canLiftOff = thrustUp > 0;
    base.netUp = mass > 0 ? thrustUp / mass : 0;
  } else {
    base.twr = base.weight > 0 ? thrustUp / base.weight : 0;
    base.payloadKg = thrustUp / g - mass;   // ONE signed number, note rule 2
    base.canLiftOff = base.twr >= 1;
    base.netUp = thrustUp / mass - g;       // m/s^2 left over once weight is paid
  }

  /* THE LANDING NUMBER. Falling at v with a_net of spare upward acceleration
     needs v^2 / 2a to stop. What the pilot wants is not that distance, it is
     how much altitude is LEFT after it — one number, and its sign is the whole
     decision. Null when climbing: an instrument that reads during a climb is
     one more thing to ignore during a descent. */
  const vDown = -sv.vertical;
  if (vDown > 0.05 && alt !== null) {
    base.timeToGround = vDown > 0 ? alt / vDown : null;
    if (base.netUp > 1e-9) {
      base.canStop = true;
      base.stopMargin = alt - (vDown * vDown) / (2 * base.netUp);
      if (base.stopMargin < 0) base.canStop = false;
    } else {
      base.canStop = false;
      base.stopMargin = null;   // cannot stop at ANY altitude — margin is meaningless
    }
  }

  base.lines = composeLines(base);
  return base;
}

const line = (id, level, text) => ({ id, level, text });

function massLines(r) {
  const out = [];
  const t = r.mass >= 1000 ? `${fmt(r.mass / 1000, 1)} t` : `${fmt(r.mass, 0)} kg`;
  out.push(line('mass', 'info', `MASS ${t} · ${r.integrity.blocks} blocks · ${r.sizeClass} grid`));
  return out;
}

/** The words. Composed HERE and not in the view, because choosing what to say —
    and in what order — is a decision, and decisions may not live in a *_view. */
export function composeLines(r) {
  const out = [];

  /* 1. The headline: can this thing leave the ground. */
  if (r.weightless) {
    out.push(line('lift', 'info',
      `FREE SPACE — no weight to lift · ${fmt(r.netUp, 2)} m/s2 on full thrust`));
  } else if (r.canLiftOff) {
    out.push(line('lift', 'info',
      `LIFTS OFF THIS WORLD — ${fmt(r.twr, 2)} : 1 · ${fmt(r.payloadKg, 0)} kg spare`));
  } else {
    out.push(line('lift', 'alarm',
      `CANNOT LIFT OFF THIS WORLD — ${fmt(r.twr, 2)} : 1, shed ${fmt(-r.payloadKg, 0)} kg or add thrust`));
  }

  /* 2. The descent, if there is one. Sign of stopMargin IS the decision. */
  if (r.canStop === false && r.timeToGround !== null) {
    out.push(line('stop', 'alarm',
      `CANNOT STOP — ground in ${fmt(r.timeToGround, 1)} s at ${fmt(-r.vertical, 0)} m/s`));
  } else if (r.canStop === true) {
    out.push(line('stop', r.stopMargin < 50 ? 'warn' : 'info',
      `BRAKING ROOM ${fmt(r.stopMargin, 0)} m · ground in ${fmt(r.timeToGround, 1)} s`));
  }

  /* 3. Where and how fast. Altitude and the two velocities a pilot flies by. */
  out.push(line('nav', r.altitude !== null && r.altitude < 100 ? 'warn' : 'info',
    `ALT ${fmt(r.altitude, 0)} m · ${r.vertical >= 0 ? 'CLIMB' : 'DROP'} ${fmt(Math.abs(r.vertical), 1)} m/s · GND ${fmt(r.ground, 1)} m/s`));

  /* 4. Power, as one signed number. */
  if (r.power) {
    out.push(r.power.ok
      ? line('power', 'info', `POWER +${fmt(r.power.spare, 0)} spare`)
      : line('power', 'alarm', `POWER OVERDRAWN BY ${fmt(-r.power.spare, 0)}`));
  }

  /* 5. Damage and wholeness. */
  if (r.integrity.pieces > 1)
    out.push(line('pieces', 'alarm', `HULL IN ${r.integrity.pieces} PIECES`));
  if (r.integrity.frac < 0.999)
    out.push(line('damage', r.integrity.frac < 0.5 ? 'alarm' : 'warn',
      `HULL ${fmt(r.integrity.frac * 100, 0)}%`));

  out.push(...massLines(r));
  return out;
}

/* ---- the system ------------------------------------------------------------
   `needs` is deliberately EMPTY. Everything the HUD reads it reads through the
   api, which reads grid.js — so it cannot be made to wait on a system that a
   different agent may or may not have shipped, and systemOrder() throws on a
   `needs` naming an unregistered id. Ticking LAST would be nicer; the contract
   has no way to say "after everyone", so the readout is recomputed from the
   ship's current state and is correct whenever it is read. */
export default registerSystem({
  id: 'hud',
  needs: [],

  tick(ship, dt, api) {
    ship.data.hud = readout(ship, api);
  },

  /* Derived, therefore never saved — see note (d). No save/load on purpose. */
});
