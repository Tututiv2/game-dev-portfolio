/* THE COCKPIT, HEADLESS. No GPU, no browser, no puppeteer.

   Runs the shipping src/ships/cockpit.js against the shipping grid.js and
   api.js. It tests BEHAVIOUR — where the eye lands, which frame the pose is in,
   whether the canopy is actually out of the way, what happens when the seat is
   ground off — and it tests that its own hardest check can FAIL, because this
   project's own lesson is that a measurement which cannot detect a defect will
   certify it.

   node tests/ships_cockpit.mjs
*/
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHIPS = path.join(ROOT, 'src', 'ships');
const imp = (f) => import(pathToFileURL(path.join(SHIPS, f)).href);

let pass = 0, fail = 0;
const ok = (cond, name, detail = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}${detail ? '  ' + detail : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  ' + detail : ''}`); }
};
const near = (a, b, tol, name, detail) =>
  ok(Math.abs(a - b) <= tol, name, detail !== undefined ? detail : `${a} vs ${b}`);
const nearV = (a, b, tol, name) =>
  ok(a.every((x, i) => Math.abs(x - b[i]) <= tol), name,
     `[${a.map(x => x.toFixed(4))}] vs [${b.map(x => x.toFixed(4))}]`);

const G = await imp('grid.js');
const A = await imp('api.js');
const C = await imp('cockpit.js');

const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const qrot = (q, v) => {
  const [x, y, z] = v, [qx, qy, qz, qw] = q;
  const ix = qw * x + qy * z - qz * y, iy = qw * y + qz * x - qx * z;
  const iz = qw * z + qx * y - qy * x, iw = -qx * x - qy * y - qz * z;
  return [ix * qw + iw * -qx + iy * -qz - iz * -qy,
          iy * qw + iw * -qy + iz * -qx - ix * -qz,
          iz * qw + iw * -qz + ix * -qy - iy * -qx];
};
const rotIndex = (fwd, up) => G.ROTS.findIndex(r =>
  r.fwd.join() === fwd.join() && r.up.join() === up.join());

/* ROTS[0] IS NOT THE IDENTITY — it is fwd +Z with up +X, because the up loop
   takes the first perpendicular axis it finds. grid.js's own suite never
   notices, since a thruster only cares about fwd. Every test here that talks
   about "up" must name the rotation it means. */
const ID = rotIndex([0, 0, 1], [0, 1, 0]);

/* a hull-plus-cockpit ship, so the grid is never a bare seat */
function build(cls = 'large', rot = ID, cell = [0, 0, 0], type = 'cockpit') {
  const g = G.makeGrid(cls, 'g');
  G.addBlock(g, type, cell, rot);
  G.addBlock(g, 'hull', [cell[0] - 1, cell[1], cell[2]]);
  return g;
}

console.log('\n  COCKPIT — the seat, the pose, and whether you can see out\n');

/* ---- purity, on THIS file specifically ---------------------------------- */
{
  const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const banned = [/\bfrom ['"]three['"]/, /\bdocument\b/, /\bwindow\b/,
                  /Math\.random/, /\bnew Date\b/, /Date\.now/];
  const src = strip(fs.readFileSync(path.join(SHIPS, 'cockpit.js'), 'utf8'));
  const bad = banned.filter(re => re.test(src)).map(String);
  ok(bad.length === 0, 'cockpit.js is pure', bad.join(' · '));
  ok(banned.some(re => re.test('Math.random()')), '...and that grep can still fail');
  ok(fs.existsSync(path.join(SHIPS, 'cockpit_view.js')),
     'the rendering half exists and is named *_view.js');
}

/* ---- no cockpit block, no cockpit --------------------------------------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const g = G.makeGrid('large', 'g');
  G.addBlock(g, 'hull', [0, 0, 0]);
  const s = A.makeShip('bare', g);
  ok(C.pose(s, A.API) === null, 'a ship with no cockpit block has no pose');
  ok(C.canopySpec(s, A.API) === null, '...and no canopy');
  ok(C.enter(s, [0, 0, 0], A.API) === null, '...and cannot be entered at all');
}

/* ---- THE DESIGN LAW: you walk to the seat, you do not pick it from a list -- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const p = A.makeShip('mine', build('large'), { owner: 'player' });
  ok(!C.isSeated(p, A.API), 'a player ship does NOT seat you for free');
  const n = A.makeShip('theirs', build('large'), { owner: 'npc-3' });
  ok(C.isSeated(n, A.API), '...but an NPC ship arrives already flown');

  // reach comes from the size class, so it is never the number 2.5 in disguise
  const mk = (cls) => {
    const sh = A.makeShip('r-' + cls, build(cls), { owner: 'player' });
    const b = C.cockpitBlocks(sh, A.API)[0];
    const eye = C.eyeOf(sh, b, A.API);
    sh.pos = [-eye[0], -eye[1], -eye[2]];      // put the eye on the origin
    return sh;
  };
  const small = mk('small'), large = mk('large');
  const from = [10, 0, 0];                      // ten metres away, both times
  ok(C.enter(small, from, A.API) === null,
     'ten metres is too far for a small hull', `reach ${G.SIZE_CLASSES.small.reach} m`);
  ok(C.enter(large, from, A.API) !== null,
     '...and close enough for a large one', `reach ${G.SIZE_CLASSES.large.reach} m`);
  ok(C.isSeated(large, A.API) && !C.isSeated(small, A.API),
     '...and only the one that succeeded is seated');
  ok(C.enter(small, [0.2, 0, 0], A.API) !== null, 'walk up to it and you are in');
}

/* ---- where the eye is ---------------------------------------------------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const mk = (cls) => {
    const sh = A.makeShip('e-' + cls, build(cls), { owner: 'npc' });
    return C.eyeLocal(sh, A.API);
  };
  const sm = mk('small'), lg = mk('large');
  const ratio = lg.map((v, i) => v / sm[i]);
  ok(ratio.every(r => Math.abs(r - 5) < 1e-12),
     'the eye scales EXACTLY with cellM — nothing hardcodes a cell size',
     `x${ratio.map(r => r.toFixed(6)).join(' ')}`);

  const s = G.cellM('large');
  nearV(lg, [0.5 * s, 0.6 * s, 1.28 * s], 1e-9,
        'and it sits inside the block: centred, above mid-height, forward');
  ok(lg[2] < 2 * s, '...behind the canopy plane, not through it',
     `${lg[2].toFixed(2)} m of a ${(2 * s).toFixed(2)} m block`);

  // rotation must move the eye, or every ship faces the same way (the yaw bug)
  const r = rotIndex([1, 0, 0], [0, 1, 0]);
  const turned = A.makeShip('turned', build('large', r), { owner: 'npc' });
  const et = C.eyeLocal(turned, A.API);
  nearV(et, [1.28 * s, 0.6 * s, 0.5 * s], 1e-9,
        'a seat facing +X puts the eye forward along +X');
}

/* ---- the pose is in the PLANET's frame ----------------------------------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const R = 600000;
  A.attachWorld({ radius: R, mu: 2e11, groundR: () => R, quat: [0, 0, 0, 1] });
  const sh = A.makeShip('pose', build('large'), { owner: 'npc' });
  const eye = C.eyeLocal(sh, A.API);

  nearV(C.pose(sh, A.API).pos, eye, 1e-9,
        'at the origin with no rotation, the pose IS the grid-local eye');

  sh.pos = [100, 200, 300];
  nearV(C.pose(sh, A.API).pos, [100 + eye[0], 200 + eye[1], 300 + eye[2]], 1e-9,
        'moving the ship moves the eye with it');

  sh.ori = [0, 1, 0, 0];                       // 180 degrees about Y
  const p = C.pose(sh, A.API);
  nearV(p.pos, [100 - eye[0], 200 + eye[1], 300 - eye[2]], 1e-9,
        'turning the ship swings the eye around it');
  nearV(p.fwd, [0, 0, -1], 1e-9, '...and the pilot now faces the other way');

  // camera convention: three looks down its own -Z
  nearV(qrot(p.quat, [0, 0, -1]), p.fwd, 1e-9,
        'the quaternion is in the camera convention (-Z is forward)');
  nearV(qrot(p.quat, [0, 1, 0]), p.up, 1e-9, '...with up where up is');
  near(dot(p.fwd, p.up), 0, 1e-9, '...and the basis is orthogonal', '');

  // THE FRAME TEST: turn the planet and the pose must not move.
  const before = C.pose(sh, A.API).pos;
  A.attachWorld({ radius: R, mu: 2e11, groundR: () => R, quat: [0, 1, 0, 0] });
  nearV(C.pose(sh, A.API).pos, before, 1e-12,
        'spinning the PLANET does not move the pose — it is in the planet frame');
  const w = C.worldPose(sh, A.API).pos;
  nearV(w, [-before[0], before[1], -before[2]], 1e-9,
        '...and worldPose is the one that follows the planet', );
}

/* ---- looking around ------------------------------------------------------ */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  A.attachWorld({ radius: 600000, mu: 2e11, groundR: () => 600000, quat: [0, 0, 0, 1] });
  const sh = A.makeShip('look', build('large'), { owner: 'npc' });
  const seat = C.pose(sh, A.API);
  const seatFwd = seat.fwd, seatRight = seat.right;
  nearV(seatFwd, [0, 0, 1], 1e-9, 'the seat faces +Z');
  nearV(seatRight, [-1, 0, 0], 1e-9,
        'facing +Z with up +Y, the pilot RIGHT is -X (not ROTS[].right, which is +X)');

  C.look(sh, Math.PI / 2, 0);
  const y = C.pose(sh, A.API);
  nearV(y.fwd, seatRight, 1e-9, 'a positive yaw turns the head toward the right');
  nearV(y.up, [0, 1, 0], 1e-9, '...and yaw alone does not tilt you');

  C.recentre(sh);
  C.look(sh, 0, 0.5);
  const p = C.pose(sh, A.API);
  ok(p.fwd[1] > 0 && dot(p.fwd, seatFwd) > 0, 'a positive pitch looks UP',
     `fwd.y ${p.fwd[1].toFixed(3)}`);
  near(Math.hypot(...p.fwd), 1, 1e-9, '...and the basis stays unit length');
  near(dot(p.fwd, p.right), 0, 1e-9, '...and orthogonal');

  C.recentre(sh);
  C.look(sh, 99, 99);
  const [yaw, pitch] = C.head(sh);
  ok(Math.abs(yaw) <= C.YAW_LIMIT + 1e-12 && Math.abs(pitch) <= C.PITCH_LIMIT + 1e-12,
     'a neck is not a turret: the head clamps',
     `${(yaw * 57.3).toFixed(0)} deg yaw, ${(pitch * 57.3).toFixed(0)} deg pitch`);

  // the canopy is bolted to the SHIP, so it must not swing when the head does
  const seatQ = C.pose(sh, A.API).seatQuat;
  C.recentre(sh);
  nearV(C.pose(sh, A.API).seatQuat, seatQ, 1e-12,
        'seatQuat ignores the head — the canopy cannot swing when you look left');
  ok(C.pose(sh, A.API).quat.some((v, i) => Math.abs(v - seatQ[i]) > 1e-9) === false,
     '...and with the head centred the two agree');

  // the same thing again as VECTORS, because that is what the renderer uses
  C.look(sh, 1.2, -0.6);
  const t = C.pose(sh, A.API);
  nearV(t.seatFwd, seatFwd, 1e-12, 'seatFwd ignores the head too');
  nearV(t.seatRight, seatRight, 1e-12, '...and so does seatRight');
  ok(Math.abs(dot(t.fwd, t.seatFwd) - 1) > 1e-6,
     '...while the head really did move', `fwd . seatFwd = ${dot(t.fwd, t.seatFwd).toFixed(3)}`);
  near(dot(t.seatFwd, t.seatUp), 0, 1e-12, 'and the seat basis is still orthogonal');
  const wp = C.worldPose(sh, A.API);
  ok(wp.seatFwd && wp.seatUp && wp.seatRight,
     'worldPose carries the seat basis the canopy is oriented by');
  C.recentre(sh);
}

/* ---- CAN YOU SEE OUT --------------------------------------------------------
   PYRE paid for this: anything within a unit of the eye fills the screen. The
   canopy must FRAME the view, so nothing at all may sit inside the cone a 45
   degree fov actually looks through. */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  G.defineBlock({ id: 'test_seat1', name: 'pod seat', size: [1, 1, 1], cockpit: true, mass: 120 });
  G.defineBlock({ id: 'test_bridge', name: 'bridge', size: [2, 2, 3], cockpit: true, mass: 900 });

  let worst = 90, worstAt = '';
  for (const cls of G.SIZE_ORDER) {
    for (const type of ['cockpit', 'test_seat1', 'test_bridge']) {
      const sh = A.makeShip(`c-${cls}-${type}`, build(cls, ID, [0, 0, 0], type), { owner: 'npc' });
      const spec = C.canopySpec(sh, A.API);
      const a = C.clearHalfDeg(spec);
      if (a < worst) { worst = a; worstAt = `${cls}/${type}`; }
    }
  }
  ok(worst >= C.FOV_HALF_DEG, 'every cockpit leaves the central 45 degrees empty',
     `worst ${worst.toFixed(1)} deg at ${worstAt} (need ${C.FOV_HALF_DEG})`);

  // the geometry is scale invariant, so a world-ship canopy is not a helmet
  const spSm = C.canopySpec(A.makeShip('k-small', build('small'), { owner: 'npc' }), A.API);
  const spLg = C.canopySpec(A.makeShip('k-large', build('large'), { owner: 'npc' }), A.API);
  const ratios = spLg.parts.flatMap((p, i) =>
    [...p.c.map((v, j) => (spSm.parts[i].c[j] === 0 ? 5 : v / spSm.parts[i].c[j])),
     ...p.h.map((v, j) => (spSm.parts[i].h[j] === 0 ? 5 : v / spSm.parts[i].h[j]))]);
  ok(ratios.every(r => Math.abs(r - 5) < 1e-9),
     'the whole canopy scales exactly 5x with the size class', `${spLg.parts.length} parts`);
  ok(spLg.parts.length >= 8 && spLg.parts.some(p => p.glow > 0),
     'and it is a real frame, with something glowing on it',
     `${spLg.parts.length} parts`);

  /* PROVE THE CHECK CAN FAIL. Two ways: a blatant one, and one just inside the
     limit, so we know the instrument has resolution and is not merely printing
     90 for everything it is handed. */
  const d = spLg.eyeToCanopyM;
  const blatant = { ...spLg, parts: [...spLg.parts,
    { id: 'spine', kind: 'box', c: [0, 0, d / 2], h: [0.05, 0.05, d / 2] }] };
  ok(C.clearHalfDeg(blatant) < 1e-9, 'a strut down the middle reads as 0 degrees clear',
     `${C.clearHalfDeg(blatant).toFixed(2)} deg`);

  const tan20 = Math.tan(20 * Math.PI / 180);
  const subtle = { ...spLg, parts: [...spLg.parts,
    { id: 'sneak', kind: 'box', c: [0, d * tan20 + 0.01, d / 2], h: [1, 0.01, d / 2] }] };
  const sa = C.clearHalfDeg(subtle);
  ok(sa < C.FOV_HALF_DEG && sa > 19 && sa < 21,
     '...and a part that only just intrudes is caught, at the right angle',
     `${sa.toFixed(2)} deg, so the 45 degree check FAILS as it should`);
  ok(C.partClearHalfDeg({ c: [0, 0, -1], h: [5, 5, 0.1] }) === 90,
     'a part behind the eye costs nothing');
}

/* ---- randomness that survives a reload ----------------------------------- */
{
  const roll = (seed, id) => {
    A.clearShips(); A.setWorldSeed(seed);
    return A.makeShip(id, build('large'), { owner: 'npc' }).data.cockpit.panel;
  };
  const a = roll('VESPER-1', 'panel'), b = roll('VESPER-1', 'panel');
  ok(a === b && a > 0, 'the instrument panel rolls the same after a reload', `${a}`);
  ok(roll('OTHER', 'panel') !== a, '...and follows the world seed, not the clock');
}

/* ---- save / load --------------------------------------------------------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const sh = A.makeShip('sv', build('large'), { owner: 'npc' });
  C.look(sh, 0.4, -0.2);
  const saved = JSON.parse(JSON.stringify(C.default.save(sh)));
  const sh2 = A.makeShip('sv2', build('large'), { owner: 'player' });
  ok(!C.isSeated(sh2, A.API), 'a fresh player ship starts unseated');
  C.default.load(sh2, saved);
  ok(C.isSeated(sh2, A.API), 'loading a save puts the pilot back in the seat');
  nearV(C.head(sh2).concat(0), [0.4, -0.2, 0], 1e-12, '...still looking where they were');
}

/* ---- the seat can be destroyed ------------------------------------------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const events = [];
  A.registerSystem({ id: 'spy', onEvent: (e, p) => events.push(e) });
  const sh = A.makeShip('grind', build('large'), { owner: 'npc' });
  ok(C.isSeated(sh, A.API), 'seated to begin with');
  G.removeBlock(sh.grid, 0, 0, 0);                    // grind the cockpit off
  ok(C.pose(sh, A.API) === null, 'grinding the seat off ends the view immediately');
  A.step(1 / 60);
  ok(events.includes('cockpit:ejected'), '...and the tick reports the ejection',
     events.join(','));
  ok(sh.data.cockpit.seat === null, '...and the stale seat cell is cleared');
}

/* ---- cut it in half and both halves behave ------------------------------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const g = G.makeGrid('large', 'g');
  G.addBlock(g, 'cockpit', [0, 0, 0]);                // occupies [0,0,0],[0,0,1]
  for (let x = 1; x <= 4; x++) G.addBlock(g, 'hull', [x, 0, 0]);
  const sh = A.makeShip('split', g, { owner: 'npc' });
  C.look(sh, 0.3, 0.1);
  G.removeBlock(g, 2, 0, 0);
  const halves = G.splitIslands(g);
  ok(halves.length === 2, 'the grid really did fall in two', `${halves.length}`);
  const kids = halves.map((h, i) => A.makeShip(`split.${i}`, h, { owner: 'npc' }));
  C.default.onSplit(sh, kids, A.API);
  const seated = kids.filter(k => C.isSeated(k, A.API));
  ok(seated.length === 1, 'exactly one half still has a pilot');
  ok(G.blockAt(seated[0].grid, 0, 0, 0) !== null, '...the half holding the seat');
  nearV(C.head(seated[0]).concat(0), [0.3, 0.1, 0], 1e-12,
        '...and they did not lose their head angle in the crash');
  ok(C.pose(kids.find(k => !C.isSeated(k, A.API)), A.API) === null,
     'the other half is unmanned debris');
}

/* ---- THE RENDERING HALF ------------------------------------------------------
   Geometry is CPU work, so the view can be built headlessly with no GPU at all —
   only rendering needs one. This matters more than it looks: `clearHalfDeg()` is
   only BINDING on what reaches the screen if the renderer draws exactly the
   boxes the pure layer handed it, so that is what is asserted here. If this file
   ever invents a strut, this check fails. */
{
  let T = null;
  try { T = await import('three'); } catch { /* no node_modules — see below */ }
  if (!T) {
    console.log('  SKIP  the view half (three is not installed here)');
  } else {
    const { makeCockpitView } = await imp('cockpit_view.js');
    A.clearShips(); A.setWorldSeed('VESPER-1');
    A.attachWorld({ radius: 6e5, mu: 2e11, groundR: () => 6e5, quat: [0, 0, 0, 1] });
    const sh = A.makeShip('view', build('large'), { owner: 'npc' });
    const spec = C.canopySpec(sh, A.API);

    const nearScene = new T.Scene();
    const cv = makeCockpitView({ nearScene });
    ok(nearScene.children.length === 0,
       'an unmounted cockpit leaves nearScene empty, so view.js keeps the extra passes off');

    cv.setSpec(spec);
    ok(nearScene.children.length === 1 && cv.mounted, 'setting a spec mounts it');
    ok(cv.group.children.length === spec.parts.length,
       'one mesh per part and not one more', `${cv.group.children.length} of ${spec.parts.length}`);

    // every drawn box must be exactly where the pure layer put it
    let worst = 0, worstId = '';
    for (const m of cv.group.children) {
      const p = spec.parts.find(q => q.id === m.name);
      m.geometry.computeBoundingBox();
      const bb = m.geometry.boundingBox;
      const c = [(bb.min.x + bb.max.x) / 2, (bb.min.y + bb.max.y) / 2, (bb.min.z + bb.max.z) / 2];
      const h = [(bb.max.x - bb.min.x) / 2, (bb.max.y - bb.min.y) / 2, (bb.max.z - bb.min.z) / 2];
      for (let i = 0; i < 3; i++) {
        const e = Math.max(Math.abs(c[i] - p.c[i]), Math.abs(h[i] - p.h[i]));
        if (e > worst) { worst = e; worstId = p.id; }
      }
    }
    ok(worst < 1e-9, 'the geometry drawn IS the geometry that was cone-checked',
       `worst ${worst.toExponential(1)} m${worstId ? ' at ' + worstId : ''}`);

    // orientation comes from the SEAT, so the frame does not swing with the head
    C.look(sh, 1.0, 0.3);
    const wp = C.worldPose(sh, A.API);
    const cam = new T.PerspectiveCamera();
    cam.position.set(11, 22, 33);
    cv.sync(wp, cam);
    const drawnFwd = new T.Vector3(0, 0, -1).applyQuaternion(cv.group.quaternion);
    nearV([drawnFwd.x, drawnFwd.y, drawnFwd.z], wp.seatFwd, 1e-6,
          'the canopy is drawn along the SEAT forward, not the head forward');
    ok(Math.abs(drawnFwd.dot(new T.Vector3(...wp.fwd)) - 1) > 1e-6,
       '...which is a different direction while the pilot is looking around');
    nearV([cv.group.position.x, cv.group.position.y, cv.group.position.z], [11, 22, 33], 1e-9,
          'and it sits on the camera, because the eye IS the camera');

    cv.setSpec(null);
    ok(nearScene.children.length === 0 && !cv.mounted,
       'leaving the seat takes the canopy out of nearScene entirely');
    cv.dispose();
  }
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
