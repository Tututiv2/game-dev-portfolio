/* ============================================================================
   COCKPIT — sitting in the ship and looking out of it.

   PURE. No three, no DOM, no clock, no global random. Everything about where the
   pilot's eye is, which way it points, and what frames the view is decided here,
   as plain numbers; cockpit_view.js only turns the numbers into meshes.

   ---------------------------------------------------------------------------
   THE ONE IDEA THIS FILE IS BUILT AROUND

   ANYTHING WITHIN A UNIT OF THE EYE FILLS THE SCREEN. PYRE paid for this twice —
   a collar 14 cm below the camera subtended 64 degrees and turned the lower half
   of the view into an orange band, and a first-person body authored facing the
   wrong way rendered perfectly while showing nothing. A 0.25 m cube at 0.5 m is
   28 degrees of a 45 degree fov. So the canopy is a FRAME, not a windscreen: the
   parts list this file emits is checked against a central cone, and
   `clearHalfDeg()` reports the half-angle inside which there is nothing at all.
   The renderer draws the parts it is given and makes no geometric decision, so
   that check is binding on what is actually on screen.

   ---------------------------------------------------------------------------
   FRAMES, because this project has lost a day twice to mixing them

     BLOCK space   — cell units, the cockpit block's own axes before rotation.
     GRID space    — METRES, relative to the grid's cell origin, the same frame
                     api.com() returns. `eyeLocal()` lives here.
     PLANET frame  — metres, ship.pos + the ship's orientation applied.
                     `pose()` lives here. THIS IS WHAT THE CAMERA WANTS.
     WORLD space   — only ever reached through api.toWorld(). `worldPose()`.

   `pose()` never touches the world. Turn the planet and the pose does not move,
   which is the definition of being in the planet's frame and is asserted.

   ---------------------------------------------------------------------------
   THE DESIGN LAW

   "There always has to be a path in game." There is no seat-select menu. You
   occupy a cockpit because a cockpit BLOCK exists in the grid and you physically
   walked to it: `enter()` refuses from beyond the size class's own construction
   reach, which is why entering a fighter is a different distance from entering a
   station. Grind the cockpit block off and the pilot is out of it — no unlock
   anywhere.

   ---------------------------------------------------------------------------
   WHAT THE CONTRACT DID NOT GIVE ME, AND HOW I WORKED AROUND IT

   1. No api call rotates a vector by SHIP.ori. api.toWorld() only does the
      planet group. So `qrot()` below is local. It is four lines of quaternion
      and it is the ship's own orientation, not a frame conversion, so it is not
      the thing the header forbids doing by hand.
   2. THE HEADER AND THE CODE DISAGREE ON toWorld: the doc block says
      `api.toWorld(ship, v)`, the implementation is `toWorld(v)`. `worldPose()`
      sniffs `api.toWorld.length` and calls it whichever way it was written, so
      it keeps working if the contract is corrected either direction.
   3. ship.pos has no stated anchor — grid cell origin, or centre of mass. I
      assume THE CELL ORIGIN (so eyeLocal and api.com are in one frame). If
      flight ends up anchoring at the centre of mass, pass
      `pose(ship, api, { pivot: api.com(ship) })` and nothing else changes.
   4. No block type is defined here on purpose. Eight agents are in this folder
      and defineBlock() THROWS on a duplicate id; a cockpit is any block type
      whose `cockpit` flag is true, which grid.js's starter set already has.
   ========================================================================= */

import { registerSystem } from './api.js';

/* ---- small pure vector maths ---------------------------------------------- */
const add3 = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub3 = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const mul3 = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot3 = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross3 = (a, b) => [a[1] * b[2] - a[2] * b[1],
                          a[2] * b[0] - a[0] * b[2],
                          a[0] * b[1] - a[1] * b[0]];
const norm3 = (v) => {
  const l = Math.hypot(v[0], v[1], v[2]);
  return l > 1e-12 ? [v[0] / l, v[1] / l, v[2] / l] : [0, 0, 1];
};

/** Rotate v by the quaternion q = [x,y,z,w]. The SHIP's own orientation only. */
function qrot(q, v) {
  const [x, y, z] = v, [qx, qy, qz, qw] = q;
  const ix =  qw * x + qy * z - qz * y;
  const iy =  qw * y + qz * x - qx * z;
  const iz =  qw * z + qx * y - qy * x;
  const iw = -qx * x - qy * y - qz * z;
  return [ix * qw + iw * -qx + iy * -qz - iz * -qy,
          iy * qw + iw * -qy + iz * -qx - ix * -qz,
          iz * qw + iw * -qz + ix * -qy - iy * -qx];
}

/** Rodrigues: rotate v about a UNIT axis by `ang` radians. */
function axisRot(v, axis, ang) {
  const c = Math.cos(ang), s = Math.sin(ang);
  const k = cross3(axis, v), d = dot3(axis, v) * (1 - c);
  return [v[0] * c + k[0] * s + axis[0] * d,
          v[1] * c + k[1] * s + axis[1] * d,
          v[2] * c + k[2] * s + axis[2] * d];
}

/** An orthonormal basis -> a quaternion in THREE'S CAMERA CONVENTION: the
    camera looks down its own -Z, so the matrix columns are [right, up, -fwd].
    Returned as a plain array so this file stays pure.

    `right` MUST be the viewer's right, i.e. cross(fwd, up) — see pilotRight()
    below, and do not hand it grid.js's ROTS[].right. */
function basisQuat(right, up, fwd) {
  const m00 = right[0], m01 = up[0], m02 = -fwd[0];
  const m10 = right[1], m11 = up[1], m12 = -fwd[1];
  const m20 = right[2], m21 = up[2], m22 = -fwd[2];
  const tr = m00 + m11 + m22;
  let x, y, z, w;
  if (tr > 0) {
    const s = 0.5 / Math.sqrt(tr + 1);
    w = 0.25 / s; x = (m21 - m12) * s; y = (m02 - m20) * s; z = (m10 - m01) * s;
  } else if (m00 > m11 && m00 > m22) {
    const s = 2 * Math.sqrt(1 + m00 - m11 - m22);
    w = (m21 - m12) / s; x = 0.25 * s; y = (m01 + m10) / s; z = (m02 + m20) / s;
  } else if (m11 > m22) {
    const s = 2 * Math.sqrt(1 + m11 - m00 - m22);
    w = (m02 - m20) / s; x = (m01 + m10) / s; y = 0.25 * s; z = (m12 + m21) / s;
  } else {
    const s = 2 * Math.sqrt(1 + m22 - m00 - m11);
    w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = 0.25 * s;
  }
  return [x, y, z, w];
}

/* ---- A TRAP IN THE SHARED MODEL, PAID FOR BY THIS FILE ---------------------
   grid.js builds ROTS[].right as cross(up, fwd). That is a perfectly good
   integer basis and [right, up, fwd] is a proper rotation (det +1) — placement
   is not mirrored and nothing in grid.js is wrong. But it is NOT a VIEWER's
   right: for a viewer, right = cross(fwd, up), and grid.js's is the negative of
   it. Feed ROTS[].right straight into a camera basis and the matrix
   [right, up, -fwd] has determinant -1 — a REFLECTION — and the standard
   matrix-to-quaternion conversion returns a non-unit quaternion (measured:
   [0, 0, 0, 0.7071], length 0.707) that silently half-rotates every vector it
   touches. It renders as a plausible picture pointing the wrong way, which is
   the exact failure PYRE's first-person body had.

   So the pilot's right is derived here and ROTS[].right is never used for a
   pose. Written down rather than fixed because grid.js is not this file's. */
export const pilotRight = (fwd, up) => cross3(fwd, up);

/* ---- how far a head turns -------------------------------------------------
   A pilot is strapped into a seat, so this is a NECK and not a turret: you can
   look over your shoulder and you cannot spin. The limits are here as exported
   constants rather than literals because trap 25 in CLAUDE.md is that a literal
   cannot be swept, which is how a wrong one survives. */
export const YAW_LIMIT = 2.60;    // rad, ~149 degrees either way
export const PITCH_LIMIT = 1.40;  // rad, ~80 degrees up or down

/* ---- where the eye sits inside a cockpit block, in BLOCK space (cells) -----
   Derived from the block's own footprint, never from a literal, so a 1x1x1 seat
   and a 2x2x3 bridge module both work and every class scales with cellM(). */
const EYE_HEIGHT_FRAC = 0.60;   // up the block: a seated eye is above mid-height
const EYE_SETBACK = 0.72;       // cells back from the block's front face
export function eyeBlockPoint(size) {
  const [sx, sy, sz] = size;
  return [sx * 0.5, sy * EYE_HEIGHT_FRAC, Math.max(sz * 0.5, sz - EYE_SETBACK)];
}

/* ---- seats ---------------------------------------------------------------- */
const st = (ship) => (ship.data.cockpit || (ship.data.cockpit = {
  seat: null, yaw: 0, pitch: 0, panel: 0,
}));

const isCockpit = (api, b) => !!(b && api.grid.BLOCK_TYPES[b.type] &&
                                 api.grid.BLOCK_TYPES[b.type].cockpit);

/** Every cockpit block in the grid, in a stable order (cell x, then y, then z),
    so "the first cockpit" means the same thing after a reload. */
export function cockpitBlocks(ship, api) {
  const out = api.grid.uniqueBlocks(ship.grid).filter(b => isCockpit(api, b));
  return out.sort((a, b) => a.cell[0] - b.cell[0] || a.cell[1] - b.cell[1] ||
                            a.cell[2] - b.cell[2] || (a.rot - b.rot));
}

/** The block the pilot is in, or null. Re-resolved from the saved CELL every
    call, so a seat that has been ground off cannot come back as a stale object
    reference — the commonest way a destroyed thing keeps working. */
export function seatBlock(ship, api) {
  const s = st(ship);
  if (!s.seat) return null;
  const b = api.grid.blockAt(ship.grid, s.seat[0], s.seat[1], s.seat[2]);
  if (!isCockpit(api, b)) return null;
  return b;
}

export const isSeated = (ship, api) => seatBlock(ship, api) !== null;

/** Take a specific cockpit block. `cell` may be ANY cell the block occupies. */
export function occupy(ship, cell, api) {
  const b = api.grid.blockAt(ship.grid, cell[0], cell[1], cell[2]);
  if (!isCockpit(api, b)) return false;
  const s = st(ship);
  s.seat = [...b.cell];
  s.yaw = 0; s.pitch = 0;
  return true;
}

export function vacate(ship) {
  const s = st(ship);
  s.seat = null; s.yaw = 0; s.pitch = 0;
}

/* ---- ENTERING IS A PHYSICAL ACT, NOT A MENU -------------------------------
   `from` is the player's position in the PLANET's frame — where they are
   standing. The reach is the size class's own construction reach, so a small
   pod is entered from arm's length and a capital hull from further out, and
   nothing here knows the number 2.5. Returns the block entered, or null. */
export function enter(ship, from, api, opts = {}) {
  const cls = api.grid.SIZE_CLASSES[ship.grid.sizeClass];
  const reach = opts.reach !== undefined ? opts.reach : cls.reach;
  let best = null, bestD = Infinity;
  for (const b of cockpitBlocks(ship, api)) {
    const p = eyeOf(ship, b, api, opts);
    const d = Math.hypot(p[0] - from[0], p[1] - from[1], p[2] - from[2]);
    if (d < bestD) { bestD = d; best = b; }
  }
  if (!best || bestD > reach) return null;
  occupy(ship, best.cell, api);
  return best;
}

/* ---- geometry -------------------------------------------------------------
   BLOCK point -> GRID metres. A block's cells are laid out from its origin cell
   by rotVec(), so the continuous map is: origin cell CENTRE, plus the rotated
   offset from that centre. Multiplying by cellM() is the only place metres
   enter, and it is a call, never a constant. */
export function blockPointToGridM(api, grid, block, p) {
  const s = api.grid.cellM(grid.sizeClass);
  const off = api.grid.rotVec(block.rot, [p[0] - 0.5, p[1] - 0.5, p[2] - 0.5]);
  return [(block.cell[0] + 0.5 + off[0]) * s,
          (block.cell[1] + 0.5 + off[1]) * s,
          (block.cell[2] + 0.5 + off[2]) * s];
}

/** The eye of a GIVEN cockpit block, in grid metres. */
export function eyeOf(ship, block, api) {
  const t = api.grid.BLOCK_TYPES[block.type];
  return blockPointToGridM(api, ship.grid, block, eyeBlockPoint(t.size));
}

/** The occupied eye, in GRID metres (same frame as api.com). Null if unseated. */
export function eyeLocal(ship, api) {
  const b = seatBlock(ship, api);
  return b ? eyeOf(ship, b, api) : null;
}

/* ---- looking around -------------------------------------------------------
   Deltas in radians, clamped. Nothing here is per-frame smoothed: smoothing a
   camera is a renderer's business and putting it in the model makes the pose
   depend on frame rate, which PYRE and Learn to Fly both paid for. */
export function look(ship, dYaw, dPitch) {
  const s = st(ship);
  s.yaw = Math.max(-YAW_LIMIT, Math.min(YAW_LIMIT, s.yaw + (dYaw || 0)));
  s.pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, s.pitch + (dPitch || 0)));
  return [s.yaw, s.pitch];
}
export function recentre(ship) { const s = st(ship); s.yaw = 0; s.pitch = 0; }
export const head = (ship) => { const s = st(ship); return [s.yaw, s.pitch]; };

/* ---- THE POSE, IN THE PLANET'S FRAME --------------------------------------
   pos  — where the eye is
   fwd/up/right — where it looks, head turn included
   quat — the same rotation in three's camera convention (looks down -Z)
   seatQuat — the SHIP's own basis with NO head turn. The canopy is bolted to
              the ship, so the frame must not swing when the pilot looks left;
              cockpit_view.js orients by this and never by `quat`. */
export function pose(ship, api, opts = {}) {
  const b = seatBlock(ship, api);
  if (!b) return null;
  const s = st(ship);
  const R = api.grid.ROTS[b.rot];

  // seat basis, in grid axes. NOTE the right: see the trap note above.
  let up = [...R.up], fwd = [...R.fwd];
  let right = pilotRight(fwd, up);
  const seatQuat = basisQuat(qrot(ship.ori, right), qrot(ship.ori, up),
                             qrot(ship.ori, fwd));

  /* The neck. POSITIVE YAW TURNS TO THE PILOT'S RIGHT and positive pitch looks
     UP, which is what a mouse does; turning right is a NEGATIVE rotation about
     up in a right-handed frame, hence the sign. */
  if (s.yaw) { fwd = axisRot(fwd, up, -s.yaw); right = axisRot(right, up, -s.yaw); }
  if (s.pitch) { fwd = axisRot(fwd, right, s.pitch); up = axisRot(up, right, s.pitch); }
  fwd = norm3(fwd); up = norm3(up); right = norm3(pilotRight(fwd, up));

  const pivot = opts.pivot || [0, 0, 0];
  const eye = eyeOf(ship, b, api);
  const pos = add3(ship.pos, qrot(ship.ori, sub3(eye, pivot)));

  const F = norm3(qrot(ship.ori, fwd));
  const U = norm3(qrot(ship.ori, up));
  const Rt = norm3(pilotRight(F, U));
  /* The seat basis with NO head turn, because the canopy is bolted to the ship.
     A renderer that orients the frame by `fwd` swings the whole cockpit when
     the pilot glances left, which reads as the ship rolling. */
  const sF = norm3(qrot(ship.ori, R.fwd));
  const sU = norm3(qrot(ship.ori, R.up));
  return { seat: [...b.cell], pos, fwd: F, up: U, right: Rt,
           quat: basisQuat(Rt, U, F), seatQuat,
           seatFwd: sF, seatUp: sU, seatRight: norm3(pilotRight(sF, sU)),
           yaw: s.yaw, pitch: s.pitch };
}

/** The same pose in WORLD space. Rotation only — api.toWorld is a rotation by
    the planet group's quaternion, so a caller that also needs the planet
    group's translation must add it; nothing in the contract exposes it. */
export function worldPose(ship, api, opts = {}) {
  const p = pose(ship, api, opts);
  if (!p) return null;
  const tw = (v) => (api.toWorld.length >= 2 ? api.toWorld(ship, v) : api.toWorld(v));
  return { ...p, pos: tw(p.pos), fwd: tw(p.fwd), up: tw(p.up), right: tw(p.right),
           seatFwd: tw(p.seatFwd), seatUp: tw(p.seatUp), seatRight: tw(p.seatRight) };
}

/* ============================================================================
   THE CANOPY — emitted as DATA so the clearance can be tested without a GPU.

   Every part is an axis-aligned box in EYE-LOCAL metres, in the SEAT's axes:
   +X right, +Y up, +Z the way the pilot faces. cockpit_view.js turns each into
   a mesh and decides nothing.

   The aperture is the block's own front face. The frame ribs sit BEHIND that
   plane (toward the pilot) rather than in front of it, which is both what a real
   canopy does and the arrangement that costs the least view: the clear cone
   widens with distance, so a rib further from the eye at the same off-axis
   distance eats MORE of the frame. */
const STRUT_FRAC = 0.055;   // rib thickness, in cells — scale invariant
const BROW_DROP = 0.05;     // fraction of the upper aperture the brow eats
const DASH_RISE = 0.12;     // fraction of the lower aperture the sill eats
const PILLAR_IN = 0.02;     // fraction of the half width the pillars eat
const APERTURE = 0.95;      // the glass stops short of the block's own shell

export function canopySpec(ship, api) {
  const b = seatBlock(ship, api);
  if (!b) return null;
  const s = api.grid.cellM(ship.grid.sizeClass);
  const size = api.grid.BLOCK_TYPES[b.type].size;
  const e = eyeBlockPoint(size);

  const d  = (size[2] - e[2]) * s;              // eye to the canopy plane
  const w  = size[0] * 0.5 * APERTURE * s;      // aperture half width
  const u  = (size[1] - e[1]) * APERTURE * s;   // aperture above the eye
  const dn = e[1] * APERTURE * s;               // aperture below the eye
  const t  = STRUT_FRAC * s;
  const brow = u * BROW_DROP, rise = dn * DASH_RISE, pin = w * PILLAR_IN;

  const box = (id, x0, x1, y0, y1, z0, z1, tint, glow) => ({
    id, kind: 'box',
    c: [(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2],
    h: [Math.abs(x1 - x0) / 2, Math.abs(y1 - y0) / 2, Math.abs(z1 - z0) / 2],
    tint: tint || 'shell', glow: glow || 0,
  });

  const zf1 = d - 3 * t, zf0 = d;               // rib slab: [d-3t, d]
  const parts = [
    // the ring around the opening
    box('brow',    -(w + 3 * t), w + 3 * t,  u - brow, u + 3 * t,        zf1, zf0, 'shell'),
    box('sill',    -(w + 3 * t), w + 3 * t,  -dn,      -(dn - rise),     zf1, zf0, 'shell'),
    box('pillarL', -(w + 3 * t), -(w - pin), -(dn + 3 * t), u + 3 * t,   zf1, zf0, 'shell'),
    box('pillarR',  w - pin,     w + 3 * t,  -(dn + 3 * t), u + 3 * t,   zf1, zf0, 'shell'),
    // peripheral enclosure: panels running back from the pillars toward the eye
    box('sideL', -(w + 5 * t), -(w - pin), -dn, u * 0.55, 0.12 * d, zf1, 'hull'),
    box('sideR',   w - pin,     w + 5 * t, -dn, u * 0.55, 0.12 * d, zf1, 'hull'),
    box('roof',  -(w + 5 * t),  w + 5 * t,  u - brow, u + 3 * t, 0.12 * d, zf1, 'hull'),
    // the console, entirely below the sill, sloping back under the view
    box('console', -(w + t), w + t, -(dn + 2.2 * t), -(dn - rise), 0.22 * d, zf0, 'hull'),
    box('dashglow', -(w * 0.72), w * 0.72, -(dn - rise) - 2.4 * t, -(dn - rise) - 0.6 * t,
        0.34 * d, 0.86 * d, 'glow', 1),
    // pinstripes up the pillars: the art direction is "sometimes glowing"
    box('stripL', -(w + 0.6 * t), -(w + 0.1 * t), -dn * 0.4, u * 0.75, zf1 + 0.6 * t, zf0 - 0.6 * t, 'glow', 1),
    box('stripR',  w + 0.1 * t,    w + 0.6 * t,  -dn * 0.4, u * 0.75, zf1 + 0.6 * t, zf0 - 0.6 * t, 'glow', 1),
    // behind the pilot: never in front of the eye, so it can never cost a degree
    box('seatback', -w * 0.55, w * 0.55, -dn, u * 0.35, -0.55 * d, -0.28 * d, 'hull'),
  ];

  return {
    sizeClass: ship.grid.sizeClass, cellM: s, blockSize: [...size],
    eyeToCanopyM: d, halfWidthM: w, aboveM: u, belowM: dn, strutM: t,
    seat: [...b.cell], panel: st(ship).panel, parts,
  };
}

/* ---- IS THE VIEW ACTUALLY CLEAR -------------------------------------------
   The half-angle of the central square cone that contains no part at all.

   Exact, not sampled: the cone half-extent at depth z is z*tan(a), which grows
   with z, so a box first touches the cone at its DEEPEST z. For a box the
   nearest-to-axis x and y are independent (it is a product set), so it intrudes
   at angle a iff zHi > 0 and max(|x|min, |y|min) <= zHi*tan(a). Invert that per
   box and take the smallest. A part entirely behind the eye costs nothing. */
export function partClearHalfDeg(part) {
  const zHi = part.c[2] + part.h[2];
  if (zHi <= 0) return 90;
  const nearAxis = (c, h) => (Math.abs(c) <= h ? 0 : Math.abs(c) - h);
  const m = Math.max(nearAxis(part.c[0], part.h[0]), nearAxis(part.c[1], part.h[1]));
  return Math.atan2(m, zHi) * 180 / Math.PI;
}

export function clearHalfDeg(spec) {
  if (!spec || !spec.parts.length) return 90;
  let a = 90;
  for (const p of spec.parts) a = Math.min(a, partClearHalfDeg(p));
  return a;
}

/** The clear cone a 45 degree fov needs is 22.5 degrees; this is the margin. */
export const FOV_HALF_DEG = 22.5;

/* ---- the system ----------------------------------------------------------- */
const def = {
  id: 'cockpit',
  needs: [],   // deliberately empty: systemOrder() THROWS on a needs id that is
               // not registered, and which other systems exist is not knowable
               // from here while eight of them are being written at once.

  init(ship, api) {
    const s = st(ship);
    // A per-ship instrument-panel roll, from the SHIP's rng so it survives a
    // reload. Math.random here would desync a seed+diff save silently.
    if (!s.panel) s.panel = Math.floor(api.rand(ship) * 1e6) / 1e6;
    // An NPC is already flying its ship when it appears. The player is not:
    // they have to walk to the seat, which is `enter()`.
    if (ship.owner !== 'player') {
      const c = cockpitBlocks(ship, api)[0];
      if (c) occupy(ship, c.cell, api);
    }
  },

  tick(ship, dt, api) {
    // Re-validate every step. A seat can vanish without onBlockRemoved ever
    // firing — grind it, split the grid, load a diff — and a pilot flying a
    // cockpit that is no longer there is exactly the class of bug that shows up
    // an hour later. This is O(1): one map lookup.
    const s = st(ship);
    if (s.seat && !seatBlock(ship, api)) {
      vacate(ship);
      api.emit('cockpit:ejected', { ship: ship.id });
    }
  },

  onBlockRemoved(ship, block, api) {
    const s = st(ship);
    if (s.seat && block && block.cell &&
        s.seat[0] === block.cell[0] && s.seat[1] === block.cell[1] &&
        s.seat[2] === block.cell[2]) {
      vacate(ship);
      api.emit('cockpit:ejected', { ship: ship.id });
    }
  },

  /* The pilot goes with the half that still holds their seat. The other half is
     an unmanned wreck, which is B2's gate: cut it in half and both halves
     behave — one of them just behaves like debris. */
  onSplit(parent, children, api) {
    const s = st(parent);
    for (const c of children) {
      const cs = st(c);
      cs.panel = s.panel;
      const keeps = s.seat &&
        isCockpit(api, api.grid.blockAt(c.grid, s.seat[0], s.seat[1], s.seat[2]));
      if (keeps) { cs.seat = [...s.seat]; cs.yaw = s.yaw; cs.pitch = s.pitch; }
      else { cs.seat = null; cs.yaw = 0; cs.pitch = 0; }
    }
  },

  save(ship) {
    const s = st(ship);
    return { seat: s.seat ? [...s.seat] : null, yaw: s.yaw, pitch: s.pitch, panel: s.panel };
  },

  load(ship, saved) {
    if (!saved) return;
    const s = st(ship);
    s.seat = saved.seat ? [...saved.seat] : null;
    s.yaw = saved.yaw || 0;
    s.pitch = saved.pitch || 0;
    s.panel = saved.panel || 0;
  },
};

export default registerSystem(def);
