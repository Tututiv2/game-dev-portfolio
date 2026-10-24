/* ============================================================================
   SHIPLOD — how a ship is drawn from one metre away to a hundred kilometres.

   PURE. No three, no DOM, no clock, no Math.random. Rendering lives in
   shiplod_view.js. This file only DECIDES, and every decision it makes is a
   number a headless test can read back.

   ---------------------------------------------------------------------------
   THE ONE IDEA: DECIDE ON ANGULAR SIZE, NEVER ON DISTANCE.

   A world-ship at 40 km and a fighter at 40 km are not the same problem. The
   fighter is six metres across and covers a fifth of a pixel; the world-ship is
   six kilometres across and fills a third of the screen. A distance-keyed LOD
   table cannot tell them apart, which is exactly why grid.js has size classes:
   the ladder spans 0.1 m to 62.5 m per cell, a 625:1 range, and DESIGN.md
   requires a megastructure to be "visible from a planet's SURFACE at the
   correct angular size". So every threshold in this file is an angle.

   Angles are converted to PIXELS before they are compared, because "how big is
   it on screen" is the only question a threshold can honestly answer, and a
   pixel is the only unit in which the four thresholds below have a reason
   rather than a taste. Pass a view made by pixelScale(fovYdeg, pxHeight); the
   default is the project's own 45 degree camera at 1080p.

   ---------------------------------------------------------------------------
   THE FOUR TIERS, AND WHY EACH ENDS WHERE IT DOES

     BLOCKS      every block drawn as itself. Only worth it while ONE CELL is
                 still a few pixels: below that its six faces, its rotation and
                 its material are all sub-pixel and you are paying thousands of
                 draws for a smudge. So this boundary is keyed to the CELL
                 angle, which is where the size class enters the maths.
     HULL        one merged shell — the faces of the grid that touch vacuum,
                 welded into a single geometry. Same silhouette, same lighting,
                 one draw. Correct while the ship is still large on screen but
                 its cells are not.
     SILHOUETTE  a coarsened shell: shape and outline only. Below ~64 px the
                 surface detail of a hull cannot be resolved, but the OUTLINE
                 still reads and is the entire information content — which is
                 the moment DESIGN.md's world-ship in the sky lives in.
     POINT       a point of light. Below ~3 px nothing about the shape survives;
                 what survives is presence and brightness. Flux goes as the
                 square of the angular size, so the point DIMS as it recedes
                 rather than sitting there as a constant dot forever (see
                 pointGain). This is the tier the owner's request lives in:
                 "see my other ships in orbit from my cockpit".
     HIDDEN      below the cull angle a point of light, correctly dimmed, is
                 fainter than 1/400th of the bloom threshold. It is not a dot,
                 it is black. Culling there is not an approximation.

   The tiers are numbered so that MORE DETAIL IS A HIGHER NUMBER. Walking the
   camera in must produce a non-decreasing sequence with no oscillation; that is
   an assertion in tests/ships_shiplod.mjs, not a hope.

   ---------------------------------------------------------------------------
   WHAT THE CONTRACT DID NOT GIVE ME, AND HOW I WORKED AROUND IT
   (api.js is owned by integration — these are notes for it, not edits.)

   a) NO CAMERA. api.js hands out gravity, altitude and toWorld but nothing
      about the eye: no position, no fov, no viewport height. LOD is a fact
      about the camera and the ship together, so every entry point here TAKES
      the camera position (planet frame) and a view descriptor. If api ever
      grows api.camera() -> { pos, fovY, pxHeight, nearPlane }, this file
      should read it and the signatures can keep their optional arguments.

   b) NO SCENE SPLIT RULE. view.js renders ship-scale geometry in nearScene,
      whose FAR plane is camera.near (15.5 km in orbit). A ship at 40 km is
      therefore invisible if it is put there — the near slices would clip it
      away. So lodFor() also returns `slice`: 'near' for anything inside the
      handed-in nearPlane, 'far' for anything beyond it, and shiplod_view.js
      puts each mesh in the matching scene. Pass ctx.nearPlane = camera.near
      every frame; with no nearPlane everything is 'near', which is correct on
      the ground where camera.near is 1 m and ships are metres away.

   c) NO PLANET QUATERNION ON THE API OBJECT. api.toWorld(v) rotates a vector,
      but a mesh also needs an ORIENTATION. api.js does export world(), whose
      .quat is that quaternion, so the view takes it via ctx.planetQuat rather
      than reconstructing it from three rotated basis vectors.

   NEVER 2.5. Cell size is always api.grid.cellM(ship.grid.sizeClass); the test
   greps this file for the literal and fails on it.
   ========================================================================= */

import { registerSystem } from './api.js';
import { cellM, bounds } from './grid.js';

/* ---- tiers ---------------------------------------------------------------- */
export const TIER = { HIDDEN: 0, POINT: 1, SILHOUETTE: 2, HULL: 3, BLOCKS: 4 };
export const TIER_NAMES = ['hidden', 'point', 'silhouette', 'hull', 'blocks'];
export const tierName = (t) => TIER_NAMES[t] || 'hidden';

/* ---- the thresholds, in PIXELS at the view handed in ----------------------
   Four numbers, and each is the answer to a question about pixels rather than
   a preference. Changing one changes how the game looks, so they are exported:
   the project's own trap 25 is that a literal nobody can sweep is how a wrong
   number survives. */
export const PX = {
  cull: 0.05,   // whole ship: below this a correctly-dimmed point is black
  point: 3,     // whole ship: below this no outline survives resampling
  shape: 64,    // whole ship: below this surface detail is under a pixel
  cell: 3,      // ONE CELL: below this a block cannot show a face
};

/** A point sprite is never drawn smaller than this, or it flickers through the
    pixel grid; brightness carries the distance instead. */
export const MIN_POINT_PX = 2;
/** HDR value of a point at full gain. It must sit ABOVE view.js's bloom
    threshold (0.95) or a distant ship is a hard dot instead of a spark. */
export const POINT_HDR = 4.0;

/* ---- the view descriptor -------------------------------------------------- */
/** radians per pixel, vertically. VESPER's camera is 45 degrees (view.js). */
export function pixelScale(fovYdeg = 45, pxHeight = 1080) {
  return (fovYdeg * Math.PI / 180) / Math.max(pxHeight, 1);
}
export const DEFAULT_RAD_PER_PX = pixelScale(45, 1080);
const rpp = (ctx) => (ctx && ctx.radPerPx) || DEFAULT_RAD_PER_PX;

/* ---- angles --------------------------------------------------------------- */
/** Angular DIAMETER of a sphere of radius r seen from distance d, in radians.
    asin, not atan: for a sphere the tangent lines meet the centre line at
    asin(r/d). Inside the sphere the answer is "everywhere", so PI. */
export function angularSize(radiusM, distM) {
  if (!(radiusM > 0)) return 0;
  const d = Math.max(distM, 0);
  if (d <= radiusM) return Math.PI;
  return 2 * Math.asin(radiusM / d);
}
/** The inverse: how far away a sphere of radius r subtends `ang`. */
export function distanceForAngle(radiusM, ang) {
  if (!(radiusM > 0)) return 0;
  if (ang >= Math.PI) return radiusM;
  if (ang <= 0) return Infinity;
  return radiusM / Math.sin(ang / 2);
}

/* ---- the ship's own size -------------------------------------------------- */
/** Bounding-sphere radius in METRES, from the grid's cell bounds. Empty -> 0. */
export function boundingRadiusM(grid) {
  const b = bounds(grid);
  if (!b) return 0;
  return 0.5 * Math.hypot(b.sizeM[0], b.sizeM[1], b.sizeM[2]);
}

/** The geometric centre of the grid in METRES, grid-local. Not the centre of
    mass: LOD is about the shape you can see, and a ship with its reactor at one
    end still occupies the same pixels. */
export function centreLocalM(grid) {
  const b = bounds(grid);
  if (!b) return [0, 0, 0];
  const s = cellM(grid.sizeClass);
  return [0, 1, 2].map(i => (b.lo[i] + b.hi[i] + 1) * 0.5 * s);
}

/** Rotate a vector by a quaternion [x,y,z,w]. Plain arrays; no three. */
export function rotByQuat(q, v) {
  const [x, y, z] = v, [qx, qy, qz, qw] = q;
  const ix =  qw * x + qy * z - qz * y;
  const iy =  qw * y + qz * x - qx * z;
  const iz =  qw * z + qx * y - qy * x;
  const iw = -qx * x - qy * y - qz * z;
  return [ix * qw + iw * -qx + iy * -qz - iz * -qy,
          iy * qw + iw * -qy + iz * -qx - ix * -qz,
          iz * qw + iw * -qz + ix * -qy - iy * -qx];
}

/** The ship's visual centre in the PLANET's frame. pos is the grid origin, so
    a long ship whose origin is at its nose would otherwise be measured from the
    wrong end — worth a metre on a fighter and three kilometres on a world-ship.
    PLANET FRAME IN, PLANET FRAME OUT. Use api.toWorld() to leave it. */
export function centreOf(ship) {
  const c = rotByQuat(ship.ori || [0, 0, 0, 1], centreLocalM(ship.grid));
  return [ship.pos[0] + c[0], ship.pos[1] + c[1], ship.pos[2] + c[2]];
}

/** Distance from an eye to a ship's visual centre. Both in the planet's frame. */
export function distanceTo(ship, eyePos) {
  const c = centreOf(ship);
  return Math.hypot(c[0] - eyePos[0], c[1] - eyePos[1], c[2] - eyePos[2]);
}

/* ---- the decision --------------------------------------------------------- */
/** The distance at which each tier BEGINS, farthest first. A tier holds for
    dist <= its own entry and > the entry of the next one in. Exported because
    a boundary you can only find by bisection is a boundary nothing can test.

      blocks < d <= hull        -> HULL
      hull   < d <= silhouette  -> SILHOUETTE   ... and so on outward.

    `blocks` is the MINIMUM of two constraints — the cell must be big enough to
    draw AND the ship must be big enough to be worth the draws — so on a
    one-cell drone the HULL band is legitimately EMPTY (blocks === hull) rather
    than inverted. Merging the shell of a single block is the same block. */
export function tierRanges(grid, ctx) {
  const R = boundingRadiusM(grid);
  const k = rpp(ctx);
  if (R <= 0) return { blocks: 0, hull: 0, silhouette: 0, point: 0, radiusM: 0 };
  const cellR = cellM(grid.sizeClass) * 0.5;
  const shape = distanceForAngle(R, PX.shape * k);
  return {
    radiusM: R,
    blocks: Math.min(shape, distanceForAngle(cellR, PX.cell * k)),
    hull: shape,
    silhouette: distanceForAngle(R, PX.point * k),
    point: distanceForAngle(R, PX.cull * k),
  };
}

/** THE FUNCTION. Given a ship's grid and how far away it is, which
    representation, and the angular size in RADIANS that drove that choice.

    ctx: { radPerPx, nearPlane }  — both optional, both documented above. */
export function lodForGrid(grid, distM, ctx) {
  const k = rpp(ctx);
  const R = boundingRadiusM(grid);
  const dist = Math.max(distM, 0);

  if (R <= 0) {
    return { tier: TIER.HIDDEN, name: 'hidden', angle: 0, drivenBy: 'ship',
             shipAngle: 0, cellAngle: 0, shipPx: 0, cellPx: 0,
             dist, radiusM: 0, pointPx: 0, pointGain: 0, hdr: 0, slice: 'far' };
  }

  const cellR = cellM(grid.sizeClass) * 0.5;
  const shipAngle = angularSize(R, dist);
  const cellAngle = angularSize(cellR, dist);
  const shipPx = shipAngle / k;
  const cellPx = cellAngle / k;

  let tier, drivenBy = 'ship';
  if (shipPx < PX.cull) tier = TIER.HIDDEN;
  else if (shipPx < PX.point) tier = TIER.POINT;
  else if (shipPx < PX.shape) tier = TIER.SILHOUETTE;
  else if (cellPx < PX.cell) { tier = TIER.HULL; drivenBy = 'cell'; }
  else { tier = TIER.BLOCKS; drivenBy = 'cell'; }

  /* Flux from a distant object goes as the square of its angular size. A point
     drawn at a fixed MIN_POINT_PX would otherwise be as bright at 100 km as at
     10, which is the single most common way a distant-object LOD announces
     itself. Gain is 1 at the top of the point band and 6.25e-4 at the cull. */
  const pointGain = Math.min(1, (shipPx / MIN_POINT_PX) * (shipPx / MIN_POINT_PX));
  const nearPlane = ctx && ctx.nearPlane;

  return {
    tier, name: TIER_NAMES[tier], drivenBy,
    angle: drivenBy === 'cell' ? cellAngle : shipAngle,
    shipAngle, cellAngle, shipPx, cellPx,
    dist, radiusM: R,
    pointPx: Math.max(MIN_POINT_PX, shipPx),
    pointGain,
    hdr: POINT_HDR * pointGain,
    slice: (nearPlane === undefined || nearPlane === null || dist < nearPlane)
      ? 'near' : 'far',
  };
}

/** The same, from a ship and an eye position in the PLANET's frame. */
export function lodFor(ship, eyePos, ctx) {
  return lodForGrid(ship.grid, distanceTo(ship, eyePos), ctx);
}

/* ---- hysteresis ----------------------------------------------------------
   A ship parked on a boundary flips tier every frame, which on the BLOCKS/HULL
   boundary means rebuilding a merged shell sixty times a second. So a tier is
   held until the distance passes the boundary by a margin. State is the
   CALLER'S, keyed by ship id, and is deliberately not stored on ship.data:
   ship.data is saved, and which mesh was on screen last frame is not a fact
   about the world. */
export const HYSTERESIS = 1.10;

export const makeLodState = () => ({ tier: new Map() });
export const forget = (state, id) => { state.tier.delete(id); };

/** Band a tier holds over, given the ranges. Returns [minDist, maxDist]. */
function bandOf(tier, r) {
  switch (tier) {
    case TIER.BLOCKS: return [0, r.blocks];
    case TIER.HULL: return [r.blocks, r.hull];
    case TIER.SILHOUETTE: return [r.hull, r.silhouette];
    case TIER.POINT: return [r.silhouette, r.point];
    default: return [r.point, Infinity];
  }
}

/** lodFor with the previous frame's answer taken into account. */
export function updateLod(state, ship, eyePos, ctx) {
  const lod = lodFor(ship, eyePos, ctx);
  const prev = state && state.tier.get(ship.id);
  if (prev === undefined || prev === lod.tier) {
    if (state) state.tier.set(ship.id, lod.tier);
    return lod;
  }
  const r = tierRanges(ship.grid, ctx);
  const [lo, hi] = bandOf(prev, r);
  // Still inside the previous band, widened by the margin? Then keep it.
  if (lod.dist >= lo / HYSTERESIS && lod.dist <= hi * HYSTERESIS) {
    return { ...lod, tier: prev, name: TIER_NAMES[prev], held: true };
  }
  state.tier.set(ship.id, lod.tier);
  return lod;
}

/* ---- the silhouette's coarseness ------------------------------------------
   A decision, so it is here and not in the view: how many cells to fuse into
   one before emitting the shell. Targets a face budget, because a silhouette is
   worth exactly one thing — its outline — and 200 faces draw an outline as well
   as 200,000 do at 60 px on screen. */
export function silhouetteCoarsen(grid, targetFaces = 200) {
  const b = bounds(grid);
  if (!b) return 1;
  const cells = [0, 1, 2].map(i => b.hi[i] - b.lo[i] + 1);
  const maxC = Math.max(...cells);
  // A shell's face count goes roughly as the surface area in coarse cells.
  for (let c = 1; c <= maxC; c++) {
    const s = cells.map(n => Math.ceil(n / c));
    const faces = 2 * (s[0] * s[1] + s[1] * s[2] + s[0] * s[2]);
    if (faces <= targetFaces) return c;
  }
  return maxC;
}

/* ---- the frame budget -----------------------------------------------------
   Twelve ships in a shipyard can all be inside their BLOCKS range at once, and
   twelve times a few thousand blocks is a dropped frame. So the tier a ship
   EARNS by angle is a ceiling, and the budget lowers it — largest on screen
   keeps the detail, because that is where a missing block is visible. Nothing
   is ever promoted above what its angle earned. */
export function assignLods(ships, eyePos, ctx = {}, state = null) {
  const budget = ctx.blockBudget === undefined ? 4 : ctx.blockBudget;
  const out = new Map();
  for (const s of ships) {
    out.set(s.id, state ? updateLod(state, s, eyePos, ctx) : lodFor(s, eyePos, ctx));
  }
  const atBlocks = [...out.entries()].filter(([, l]) => l.tier === TIER.BLOCKS)
    .sort((a, b) => b[1].shipAngle - a[1].shipAngle);
  for (let i = budget; i < atBlocks.length; i++) {
    const [id, l] = atBlocks[i];
    out.set(id, { ...l, tier: TIER.HULL, name: TIER_NAMES[TIER.HULL], demoted: true });
  }
  return out;
}

/* ---- registered as a system so integration can find it --------------------
   It has no tick: LOD is a fact about the frame, not about the simulation, and
   a tick would make it run at the physics rate rather than the render rate.
   onSplit is real work though — a split makes new ship ids, and the halves must
   not inherit the parent's held tier. */
export default registerSystem({
  id: 'shiplod',
  onSplit(parent, children) {
    for (const c of children) if (c && c.data) c.data._lodParent = parent.id;
  },
});
