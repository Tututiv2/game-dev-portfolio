/* ============================================================================
   ORBIT VIEWER — the judging harness for the planet renderer.

   Not the game. This exists so a world can be looked at, from orbit, on its
   own terms, before anything is built on top of it. Drag to orbit, wheel to
   zoom, [ ] to step through seeds, arrow keys to move the star.

   URL:  ?seed=NAME&arch=volcanic&dist=3.2&az=0.4&el=0.15&sun=0.5
   ========================================================================= */

import * as T from 'three';
import { makeView } from './render/view.js';
import { makeBackdrop, NEBULA_SETS } from './render/nebula.js';
import { makePlanetDef } from './world/planetDef.js';
import { PlanetView } from './world/planetView.js';
import { ARCH_KEYS } from './world/archetypes.js';
import { PlanetBake, BAKE_SIZE, BAKE_SIZE_LOW } from './render/planetBake.js';
import { heightNorm, groundR } from './world/heightField.js';
import { WorldEdits } from './world/edits.js';
import { seaNorm } from './world/heightField.js';
import { biomeAt } from './world/biome.js';
import { painted, bootMsg, bootNote, bootDone, bootError, probeRenderer,
         isFirstRun, markRun, FIRST_RUN_NOTE, SOFTWARE_HELP } from './boot.js';

const Q = new URLSearchParams(location.search);
const num = (k, d) => (Q.has(k) ? parseFloat(Q.get(k)) : d);

const state = {
  seed: Q.get('seed') || 'VESPER-1',
  arch: Q.get('arch') || null,
  dist: num('dist', 3.15),          // multiples of planet radius
  az: num('az', 0.55),
  el: num('el', 0.30),      // high enough that a ringed world shows an ellipse
  sunAz: num('sun', 0.62),
  sunEl: num('sunel', 0.30),
  // where the camera LOOKS once it is on the ground: 0 = dead level with the
  // horizon, negative = down at the ground, positive = up at the sky
  elLook: num('look', -0.12),
  // ON by default. It used to default to off, and the first thing the owner
  // said on seeing it was "the planet isn't moving".
  spin: num('spin', 1),
  spinPhase: 0,
  frames: 0,

  /* ---- STANDING ON IT ----------------------------------------------------
     The viewer could always reach the ground — the camera clamps to the height
     field and swings its up-vector to the local radial — but there was no way
     to ASK it to, so the only thing anyone could do was orbit and zoom. That is
     what "am I supposed to be able to walk?" meant, and the answer was no.

     `mode` is what the player chose; `land` is where the camera actually is,
     animated 0..1, so descending and launching are a continuous move rather
     than a teleport. az/el double as the walker's position on the sphere (they
     already parameterise a direction from the centre), and `heading` is which
     way they face — kept separate from az/el so that looking around does not
     move you and walking does not spin you. */
  mode: Q.get('mode') || 'orbit',      // 'orbit' | 'ground'
  land: Q.get('mode') === 'ground' ? 1 : 0,
  heading: num('head', 0),             // 0 = facing local north
  eyeM: num('eye', 2.0),               // eye height above the ground, in METRES
};

/* How long the drop takes. Long enough to read as a descent, short enough that
   nobody thinks it has hung — and the terrain quadtree streams in over it. */
const LAND_S = 4.0;
const WALK_MS = 6, RUN_MS = 90;        // metres per second

/* ?time=12 pins the clock. Screenshots have to be reproducible or a gallery
   diff shows drifting clouds and a travelling glow pulse instead of the change
   that was actually made. Every shooting harness passes it. */
const PIN_TIME = Q.has('time') ? parseFloat(Q.get('time')) : -1;

/* Ask what GPU we actually got BEFORE building anything. On a software
   renderer this shader takes tens of seconds a frame, which is a white screen
   by another name — so say so, and turn the quality right down. */
const gpu = probeRenderer();
const LOW = gpu.software || Q.has('low');
/* ?analytic=1 draws the world the OLD way — surfaceAt() per pixel, per frame.
   It is the reference the bake is checked against: same seed, same world, two
   renderers, put the screenshots side by side. Keep it working. */
const ANALYTIC = Q.has('analytic');

const view = makeView(document.getElementById('cv'), {
  fov: 45, exposure: 1.0,
  // On a software renderer, render at a fraction of the window and let the
  // browser scale it up. Pixel count is the whole cost of this shader.
  maxPR: LOW ? 0.4 : 1,
  bloomStrength: 0.62, bloomRadius: 0.42, bloomThreshold: 0.95,
  samples: LOW ? 0 : 4,
  // the screenshot harness reads the canvas back with toDataURL(), which needs
  // the drawing buffer to survive the frame
  preserveDrawingBuffer: Q.has('pdb'),
});
const { renderer, scene, camera, composer } = view;

/* ---- ?nearprobe — DOES THE DEPTH SPLIT ACTUALLY WORK -----------------------
   The whole claim of view.js's slices is that a ship-scale object half a metre
   from your eye can be drawn in the same frame as a planet 300 km away. That is
   invisible in ordinary play until B2 lands, so it is provable now, on purpose,
   with its own control:

     ?nearprobe=1   the cube goes in view.nearScene   — should be VISIBLE
     ?nearprobe=2   the same cube goes in `scene`     — should be INVISIBLE,
                    because in orbit camera.near is 15.5 km and the cube is at
                    0.5 m, i.e. thirty thousand times inside the near plane

   Two flags, one difference. A single flag would only show a cube and prove
   nothing about why. */
let nearProbe = null;
const NEAR_PROBE = Q.has('nearprobe') ? parseInt(Q.get('nearprobe'), 10) : 0;
if (NEAR_PROBE) {
  /* SMALL, and off to one side. A 0.25 m cube at 0.5 m subtends 28 degrees of a
     45 degree fov and swallows the planet — the same thing PYRE found when a
     collar 14 cm below the camera turned the lower half of the view into a
     band. The point of this probe is to show a near object AND the world behind
     it, so it has to be small enough to leave the world visible. */
  nearProbe = new T.Mesh(
    new T.BoxGeometry(0.06, 0.06, 0.06),
    // unlit and toneMapped=false, like everything else upstream of the composer
    new T.MeshBasicMaterial({ color: 0xff3bd0, toneMapped: false }));
  (NEAR_PROBE === 2 ? scene : view.nearScene).add(nearProbe);
}

let planet = null, backdrop = null;
let bootBuildMs = 0;      // how long the first world took, compile included

/* ONE set of cubemaps for the whole session, reused by every world. Allocating
   fresh ones per planet costs 1.3 s in the driver against 25 ms of drawing —
   see planetBake.js. Built lazily so ?analytic=1 never pays for it. */
let bakePool = null;
function getBake() {
  if (ANALYTIC) return null;
  if (!bakePool) bakePool = new PlanetBake(renderer, LOW ? BAKE_SIZE_LOW : BAKE_SIZE);
  return bakePool;
}

function build() {
  if (planet) { scene.remove(planet.group); planet.dispose(); }
  if (backdrop) {
    scene.remove(backdrop.dome); scene.remove(backdrop.stars);
    backdrop.dome.geometry.dispose(); backdrop.dome.material.dispose();
    backdrop.stars.geometry.dispose(); backdrop.stars.material.dispose();
  }

  const def = makePlanetDef(state.seed, state.arch);
  /* The player's changes to THIS world. Keyed by seed and archetype, so
     stepping through seeds with [ and ] does not carry one world's edits into
     another — every world remembers its own. */
  edits = new WorldEdits(state.seed, def.key);
  planet = new PlanetView(def, {
    segments: LOW ? 96 : 320,
    lowQuality: LOW,
    bake: getBake(),           // shared across worlds; null on ?analytic=1
    analytic: ANALYTIC,        // ?analytic=1 — the old per-pixel reference path
    edits,
  });
  scene.add(planet.group);

  window.__V.planetGroup = planet.group;
  window.__V.radius = def.radius;

  const hues = NEBULA_SETS[def.key] || NEBULA_SETS.barren;
  backdrop = makeBackdrop(def.seed, hues, def.radius * 15);
  scene.add(backdrop.dome, backdrop.stars);

  camera.near = def.radius * 0.05;
  camera.far = def.radius * 22;
  camera.updateProjectionMatrix();

  // The spin is measured FROM the world's own axis orientation, never instead
  // of it. Without this the frame loop assigns rotation.y and def.tiltDir is
  // gone — which is not a subtle bug, it is a different planet.
  state.spinPhase = def.tiltDir;

  window.__V.def = def;
  window.__V.planet = planet;
  for (const [k, v] of Object.entries(window.__V.overrides || {})) {
    const u = planet.mesh.material.uniforms[k];
    if (u) u.value = v;
  }
  // Reset BOTH. The test waits on window.__V.frames, and leaving a stale count
  // there lets a screenshot fire against the previous world.
  state.frames = 0;
  window.__V.frames = 0;

  const el = document.getElementById('info');
  if (el) {
    el.innerHTML =
      `<b>${def.name}</b> · <span class="k">${def.key}</span><br>` +
      `<span class="d">${def.blurb}</span><br>` +
      `seed <b>${state.seed}</b> · r ${(def.radius / 1000).toFixed(0)} km · ` +
      `${def.features.length} features${def.ring ? ' · rings' : ''}` +
      `${def.clouds ? ' · clouds' : ''}<br>` +
      `<span class="d">${planet.baked
        ? `baked in ${planet.bakeMs.toFixed(0)} ms` +
          (planet.compileMs > 40 ? ` (+${planet.compileMs.toFixed(0)} ms shader compile, once)` : '')
        : (def.art.isGas ? 'gas giant — analytic (bands drift)' : 'ANALYTIC')}</span>`;
  }
  return def;
}

/* ---- performance / renderer readout --------------------------------------
   On screen permanently, because "is it actually using the graphics card?"
   turned out to be the difference between a working game and a white window,
   and there was no way to tell from the outside. */
const perfEl = document.getElementById('perf');
const shortGPU = (n) => String(n)
  .replace(/^ANGLE \(/, '').replace(/\)$/, '')
  .replace(/\s*\(0x[0-9A-Fa-f]+\)/, '')
  .replace(/Direct3D11.*$/, 'D3D11').replace(/,\s*D3D11.*$/, '')
  .split(',').slice(0, 2).join(',').trim().slice(0, 46);
let _pT = 0, _pN = 0, _pFps = 0;
function perf(ms) {
  _pN++;
  if (ms - _pT < 500) return;
  _pFps = _pN * 1000 / (ms - _pT); _pT = ms; _pN = 0;
  if (!perfEl) return;
  const cls = _pFps >= 50 ? 'good' : _pFps >= 20 ? '' : 'bad';
  perfEl.innerHTML =
    `<span class="fps ${cls}">${_pFps.toFixed(0)} fps</span><br>` +
    `<span class="${LOW ? 'bad' : 'good'}">${LOW ? 'SOFTWARE — reduced quality' : 'hardware accelerated'}</span><br>` +
    `<span class="${planet && planet.baked ? 'good' : ''}">${
      planet && planet.baked ? 'baked surface' : 'per-pixel surface'}</span><br>` +
    `<span>${shortGPU(gpu.name)}</span><br>` +
    `<span>${renderer.domElement.width}x${renderer.domElement.height}</span>` +
    /* WHERE AM I STANDING? The owner asked "maybe im underwater? but not sure,
       cause i dont see any water" — and he could not tell, because there is
       nothing to tell him. groundR() CLAMPS to sea level, so standing over an
       ocean puts you on a perfectly flat plane that is drawn as ordinary
       ground. No surface, no shore, no cue of any kind. Until the sea is
       actually rendered, the least this can do is say so out loud. */
    (() => {
      if (!(state.land > 0.5)) return '';
      const g = window.__V.groundInfo();
      if (!g) return '';
      return `<br><span class="${g.onWater ? 'bad' : 'good'}">${
        g.onWater ? `ON WATER — ${g.seaDepth.toFixed(0)} m of sea below you`
                  : 'on dry land'}</span>`;
    })();
  window.__V.fps = _pFps;
}

const sunDir = new T.Vector3();
const _camDir = new T.Vector3(), _camLocal = new T.Vector3();
const _up = new T.Vector3(), _east = new T.Vector3(), _look = new T.Vector3();
const _north = new T.Vector3(), _eastT = new T.Vector3(), _fwd = new T.Vector3();
const _worldUp = new T.Vector3(0, 1, 0);
const _target = new T.Vector3(), _origin = new T.Vector3(0, 0, 0);
const _gd = [0, 0, 0];
let firstFrameDone = false;
let lastMs = 0;
let edits = null;
function frame(ms) {
  requestAnimationFrame(frame);
  const t = PIN_TIME >= 0 ? PIN_TIME : ms * 0.001;
  // REAL elapsed time, even when ?time= pins the clock: the pinned clock exists
  // so screenshots are reproducible, and tying movement to it would freeze the
  // player. Capped so an alt-tab does not teleport you across the world.
  const dt = lastMs ? Math.min(0.05, (ms - lastMs) * 0.001) : 0;
  lastMs = ms;
  try {

  /* ---- WALKING ----------------------------------------------------------
     Movement is in az/el, i.e. straight on the sphere, so there is no flat-world
     approximation to break down when you walk a long way. The two conversions
     are just the metric of the parameterisation: a step north is ds/R of
     latitude, and a step east is ds/(R cos el) of longitude — the cos falls out
     because lines of longitude converge, and without it you would accelerate
     towards the poles. el is clamped short of the pole because the basis below
     is singular exactly there. */
  if (state.land > 0.5 && planet) {
    const R = planet.def.radius;
    let f = (keys.w ? 1 : 0) - (keys.s ? 1 : 0);
    let g = (keys.d ? 1 : 0) - (keys.a ? 1 : 0);
    if (f || g) {
      const L = Math.hypot(f, g); f /= L; g /= L;      // no diagonal speed boost
      const step = (keys.shift ? RUN_MS : WALK_MS) * dt;
      const ch = Math.cos(state.heading), sh = Math.sin(state.heading);
      const n = f * ch - g * sh, e = f * sh + g * ch;
      state.el = Math.max(-1.45, Math.min(1.45, state.el + (step * n) / R));
      state.az += (step * e) / (R * Math.max(Math.cos(state.el), 1e-3));
    }
  }
  // the descent itself, eased at both ends so it starts and stops softly
  const want = state.mode === 'ground' ? 1 : 0;
  state.land = Math.max(0, Math.min(1, state.land + (want ? dt : -dt) / LAND_S));

  // Camera direction first, THEN how far out along it — because "1.004 radii"
  // is measured from the datum and the ground is not at the datum. On a world
  // with 20 km of relief that puts you two kilometres UNDERGROUND, and what you
  // get is a flat screen the colour of the atmosphere seen from inside, which
  // reads exactly like the terrain having failed to generate.
  _camDir.set(
    Math.cos(state.el) * Math.sin(state.az),
    Math.sin(state.el),
    Math.cos(state.el) * Math.cos(state.az),
  );
  // the tilt is on the group, so ask the height field in the planet's own frame
  _gd[0] = _camDir.x; _gd[1] = _camDir.y; _gd[2] = _camDir.z;
  planet.group.worldToLocal(_camLocal.copy(_camDir)).normalize();
  _gd[0] = _camLocal.x; _gd[1] = _camLocal.y; _gd[2] = _camLocal.z;
  const gR = groundR(planet.def, _gd);
  const floor = gR + planet.def.radius * 2e-4;
  /* Two distances, blended by the landing animation. The orbit one is measured
     from the DATUM and clamped off the ground; the standing one is measured
     from the GROUND, because eye height is a property of the person and not of
     the planet's radius. */
  const orbitD = Math.max(planet.def.radius * state.dist, floor);
  const groundD = gR + state.eyeM;
  const eas = state.land * state.land * (3 - 2 * state.land);   // smoothstep
  const d = orbitD + (groundD - orbitD) * eas;
  camera.position.copy(_camDir).multiplyScalar(d);

  // WHERE TO LOOK. From orbit, at the planet. From the ground, at the HORIZON —
  // looking at the centre of the world while standing on it means staring at
  // your own feet, which renders as a black screen and reads as a bug.
  // Blend between the two by altitude so descending is continuous.
  const altR = (d - gR) / planet.def.radius;
  const grounded = 1 - Math.min(1, Math.max(0, (altR - 0.002) / 0.03));
  if (grounded > 0.001) {
    _up.copy(camera.position).normalize();
    /* THE SAME BASIS WALKING USES. North and east come straight from the
       spherical parameterisation rather than from a cross product with world
       +Y — if looking and walking derive their axes differently they disagree,
       and pressing W sends you off at an angle to the way you are facing, which
       is the kind of bug that feels like bad controls rather than bad maths. */
    const ce = Math.cos(state.el), se = Math.sin(state.el);
    const ca = Math.cos(state.az), sa = Math.sin(state.az);
    _north.set(-se * sa, ce, -se * ca);
    _eastT.set(ca, 0, -sa);
    _fwd.copy(_north).multiplyScalar(Math.cos(state.heading))
      .addScaledVector(_eastT, Math.sin(state.heading)).normalize();
    // look along the heading, pitched by elLook
    _look.copy(_fwd).multiplyScalar(Math.cos(state.elLook))
      .addScaledVector(_up, Math.sin(state.elLook)).normalize();
    _target.copy(camera.position).addScaledVector(_look, planet.def.radius * 0.5);
    _target.lerp(_origin, 1 - grounded);
    /* UP IS THE LOCAL RADIAL, NOT WORLD +Y.
       lookAt() resolves the camera's ROLL from camera.up, which defaults to
       (0,1,0). That is fine in orbit, where "up" is a free choice. Standing on
       a sphere it is wrong everywhere except the equator, and near a pole it is
       catastrophic: the local radial is nearly parallel to world +Y, lookAt has
       almost no plane to work with, and the frame rolls up to 90 degrees. The
       symptom is a horizon running vertically down the screen with the ground
       stacked along one side, which is exactly what it looked like — the ground
       was not misplaced, the camera was on its ear.
       Lerped back to +Y as you climb, so orbit keeps the framing it was
       approved with. */
    camera.up.copy(_up).multiplyScalar(grounded)
      .addScaledVector(_worldUp, 1 - grounded).normalize();
    camera.lookAt(_target);
  } else {
    camera.up.copy(_worldUp);
    camera.lookAt(0, 0, 0);
  }

  // The star sits off the camera axis, never behind it: a fully front-lit ball
  // has no form, and a crescent hides the surface we are judging.
  sunDir.set(
    Math.cos(state.sunEl) * Math.sin(state.az + state.sunAz),
    Math.sin(state.sunEl),
    Math.cos(state.sunEl) * Math.cos(state.az + state.sunAz),
  ).normalize();

  // The near plane has to follow the altitude. A fixed one at 5% of the radius
  // is 15 km on a small world — stand on the ground with that and everything in
  // front of you is clipped away, which looks exactly like the terrain failing
  // to generate. Far stays put so the backdrop and the rings survive.
  // Height above the GROUND, not above the datum. Standing in a basin the two
  // differ by the world's relief, and using the datum put the near plane
  // kilometres in front of a camera that was two metres up.
  const alt = Math.max(d - gR, 1);

  /* Every control the owner has, on screen, in the mode it applies to. The
     arrow keys are in here because on the night side the ground is legible or
     not depending entirely on where the star is, and a player who cannot find
     that key concludes the planet is broken rather than dark. */
  setHint(state.land < 0.02
    ? '<b>L</b> — land on the planet &middot; drag to orbit &middot; wheel to zoom ' +
      '&middot; <b>&#8592;&#8593;&#8594;&#8595;</b> move the star &middot; ' +
      '<b>]</b> new world &middot; <b>n</b>/<b>p</b> world type'
    : state.land < 0.999
      ? (state.mode === 'ground' ? 'descending' : 'launching') +
        ` &middot; ${(alt / 1000).toFixed(1)} km`
      : (() => {
        /* WHAT IS IN REACH, named. A capability nobody can discover does not
           exist — the same lesson the landing prompt exists for. Without this
           line the objects are scenery, because nothing tells you that you can
           take one. */
        const near = nearestObj();
        const took = flash && (t - flashT) < 2.2 ? ` &middot; <b>${flash}</b>` : '';
        return '<b>W A S D</b> walk &middot; <b>shift</b> run &middot; drag to look ' +
          (near ? ` &middot; <b>E</b> — take the ${planet.terrain.roster.label}` : '') +
          '&middot; <b>L</b> — launch' + took +
          `&middot; <span style="opacity:.55">${alt.toFixed(1)} m up` +
          (harvested ? ` &middot; ${harvested} taken` : '') + '</span>';
      })());
  const near = Math.min(Math.max(alt * 0.08, 1.0), planet.def.radius * 0.05);
  if (Math.abs(camera.near - near) > near * 0.2) {
    camera.near = near;
    camera.updateProjectionMatrix();
  }

  /* SPIN. Time-based, not per-frame: the old `rotation.y += 0.002` meant the
     world turned at whatever rate the machine happened to render, so a fast
     GPU got a fast planet. `def.day` is the world's own day length, rolled from
     the seed, so every planet turns at its own speed.

     Held while the terrain is up. The chunk grid is chosen from the camera's
     position in the planet's frame, so a spinning planet slides the ground out
     from under it and it regenerates continuously — and a chunk is a thousand
     height-field evaluations. Standing still on a spinning world is the
     player's frame anyway; that arrives properly with walking. */
  const spinRate = Math.PI * 2 / planet.def.day;
  const terrainUp = planet.terrain && planet.terrain.group.visible;
  if (state.spin && !terrainUp) {
    // spinPhase starts at def.tiltDir — see build(). rotation.y IS the world's
    // axis orientation, so ASSIGNING it without that base silently throws the
    // planet's tilt away, and every world quietly becomes a different world.
    planet.group.rotation.y = state.spinPhase + t * spinRate;
  } else {
    // held: re-base so that resuming carries on from here instead of jumping
    state.spinPhase = planet.group.rotation.y - t * spinRate;
  }
  planet.update(camera, sunDir, null, innerHeight, t);

  backdrop.dome.position.copy(camera.position);
  backdrop.stars.position.copy(camera.position);

  /* Park the probe half a metre in front of the eye, wherever the eye is. */
  if (nearProbe) {
    nearProbe.position.copy(camera.position)
      .add(new T.Vector3(0.16, -0.11, -0.5).applyQuaternion(camera.quaternion));
    nearProbe.quaternion.copy(camera.quaternion);
  }

  /* Re-cut the depth range for ship-scale geometry. Must run AFTER the near
     plane is moved above, and it is a no-op (both passes disabled) while
     view.nearScene is empty — which is every frame until B2 puts a ship in it. */
  window.__V.slices = view.syncNear();

  composer.render();
  state.frames++;
  window.__V.frames = state.frames;
  perf(ms);
  // The curtain only lifts once a frame has genuinely been drawn — so it
  // covers the whole shader-compile stall rather than flashing away first.
  if (!firstFrameDone) { firstFrameDone = true; bootDone(); }
  } catch (e) {
    bootError(e);
    throw e;                      // still surface it to the console for the tests
  }
}

/* Building a world BAKES it, which blocks the main thread for a couple of
   seconds. Put the notice up and let the browser actually paint it before
   starting, or the page simply freezes with no explanation. `build()` stays
   synchronous — the tests drive it directly and expect a return value. */
let rebuilding = false;
async function rebuild() {
  if (rebuilding) return;
  rebuilding = true;
  const b = document.getElementById('busy');
  if (b) b.classList.add('on');
  await painted();
  try { build(); } finally {
    if (b) b.classList.remove('on');
    rebuilding = false;
  }
}

/* ---- THE PROMPT ------------------------------------------------------------
   Built here rather than in orbit.html so the bundled single file gets it for
   free. It exists because a control nobody can discover does not exist: the
   camera could always reach the ground, and the owner's report was "I can only
   see the planet from orbit and zoom — am I supposed to be able to walk?" */
const hint = document.createElement('div');
hint.style.cssText =
  'position:fixed;left:50%;bottom:16px;transform:translateX(-50%);z-index:40;' +
  'font:13px/1.5 ui-monospace,Menlo,Consolas,monospace;color:#dff;text-align:center;' +
  'background:rgba(0,0,0,.45);padding:7px 14px;border-radius:6px;pointer-events:none;' +
  'white-space:nowrap;letter-spacing:.02em';
document.body.appendChild(hint);
let _hintTxt = null;
function setHint(h) { if (h !== _hintTxt) { _hintTxt = h; hint.innerHTML = h; } }

/* ---- reaching things ------------------------------------------------------ */
/* How far the player can reach, in metres. Generous on purpose: this is a
   keyboard game with no cursor, so "the thing in front of me" has to be
   forgiving or taking something becomes a game of pixel-hunting with your feet. */
const REACH = 9;
const _hitP = new T.Vector3();
let harvested = 0, flash = '', flashT = 0;

function nearestObj() {
  if (!planet || !planet.terrain || !planet.terrain.group.visible) return null;
  // the camera, in the PLANET's frame — the group is tilted, and asking the
  // height field about a world-space direction is the bug walk.mjs caught once
  planet.group.worldToLocal(_hitP.copy(camera.position));
  return planet.terrain.nearestObject([_hitP.x, _hitP.y, _hitP.z], REACH);
}

/* ---- WHERE YOU ACTUALLY LAND ----------------------------------------------
   Pressing L used to drop you exactly below the camera, and on a world that is
   mostly ocean that is usually THE SEA — where groundR clamps to sea level, so
   you stand on the water with nothing on it in any direction. verdant is 59%
   ocean and oceanic is 79%. That is most of what "there's just rocks every once
   in a while" was: not a density bug, a LANDING bug. Nothing grows in water.

   So the landing spot is nudged to the nearest place that is dry AND has
   something growing on it. The search is a widening spiral in the planet's own
   frame, capped at a few degrees — far enough to clear a coastline, near enough
   that you land where you were looking rather than somewhere else entirely. */
const _lsDir = new T.Vector3(), _lsBio = { cover: 0, region: 0 };
/* WHAT THE LANDING SEARCH ACTUALLY DID, for tests. Without this, "the world is
   still bare" cannot be told from "the search never moved you", and those need
   opposite fixes — trap 14 again, since a landing change that finds nothing
   better renders byte-identically to no landing change at all. */
let _landLog = null;
function findLandingSite() {
  if (!planet || !planet.def || planet.def.art.isGas) return;
  const def = planet.def, sea = seaNorm(def);
  const score = (az, el) => {
    _lsDir.set(Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az));
    planet.group.worldToLocal(_lsDir).normalize();     // the TILTED frame
    const d = [_lsDir.x, _lsDir.y, _lsDir.z];
    if (heightNorm(def, d) <= sea) return -1;          // in the sea
    biomeAt(def, d, _lsBio);
    /* THE FLOOR OF 0.05 IS A BUG FIX, NOT A TWEAK. This used to return cover
       straight, so a dry but BARE spot scored exactly 0 — and the acceptance
       test below is "best > 0", which 0 fails. The search would find perfectly
       good dry land, score it zero, reject it, and leave the player standing on
       the ocean instead. Any dry ground beats any sea. */
    return 0.05 + _lsBio.cover;
  };

  /* ---- PHASE 2: LAND SOMEWHERE THE WORLD IS ALIVE -------------------------
     Law 3 of archetypes.js is that the art CONCENTRATES: "30% ablaze and 70%
     dead reads as fire; 100% evenly cracked reads as texture". So on a world
     built to that law most of the surface is deliberately dead black rock, and
     an arbitrary landing is a coin flip. Measured on VESPER-1 volcanic, the near
     ground came back with 99 distinct colours IN BROAD DAYLIGHT against
     glacier's 1998 — the owner pressed L, arrived in the dead 70%, and reported
     the foreground as bare. It was bare, and it was also correct.

     def.features is the placed macro-feature list — CPU data rolled from the
     seed in planetDef.js, carrying a unit position, an angular radius and a
     `glow`. So "is anything happening here" costs one dot product per feature,
     with no new noise and no GPU readback.

     IT RUNS AS A SECOND PASS, NOT AS A TERM IN `score`, and that is the whole
     design. Folding it into score() looks tidier and is wrong twice over: the
     early-out ("where you are is fine") would fire on a covered-but-dead spot
     before liveness was ever consulted, and on a world whose features barely
     glow — barren, desert — every landing would run all 22 rings and then drag
     the player a quarter of the way round the planet chasing a number that was
     never there. Phase 1's dry-land guarantee is therefore untouched, byte for
     byte, and this only re-picks among sites CLOSE TO the one it chose. */
  const liveAt = (az, el) => {
    _lsDir.set(Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az));
    planet.group.worldToLocal(_lsDir).normalize();
    const d = [_lsDir.x, _lsDir.y, _lsDir.z];
    if (heightNorm(def, d) <= sea) return -1;          // never trade dry for sea
    biomeAt(def, d, _lsBio);
    if (_lsBio.cover <= 0.02) return -1;               // nor cover for a bare plain
    let live = 0;
    for (let i = 0; i < def.features.length; i++) {
      const f = def.features[i];
      const dot = d[0] * f.x + d[1] * f.y + d[2] * f.z;
      const reach = f.r * 1.4;                          // the falloff, not the rim
      const ang = Math.acos(Math.max(-1, Math.min(1, dot)));
      if (ang < reach) {
        const w = f.glow * (1 - ang / reach);
        if (w > live) live = w;
      }
    }
    return live;
  };
  /* Six rings, ~43 km on a 600 km world — the radius phase 1 used to have. Far
     enough to walk out of a dead patch, near enough that you still land where
     you were looking, which is the constraint the comment above phase 1 sets. */
  const nudgeToLife = () => {
    let bAz = state.az, bEl = state.el, bLive = liveAt(state.az, state.el);
    const was = bLive;
    if (bLive >= 0.85) { _landLog = { was, got: bLive, moved: false, why: 'already alive' }; return; }
    for (let ring = 1; ring <= 6; ring++) {
      const rad = ring * 0.02;
      for (let k = 0; k < 12; k++) {
        const a = (k / 12) * Math.PI * 2;
        const az = state.az + Math.cos(a) * rad / Math.max(Math.cos(state.el), 0.2);
        const el = Math.max(-1.4, Math.min(1.4, state.el + Math.sin(a) * rad));
        const lv = liveAt(az, el);
        if (lv > bLive) { bLive = lv; bAz = az; bEl = el; }
      }
    }
    const moved = bLive > was && (bAz !== state.az || bEl !== state.el);
    if (bLive > 0) { state.az = bAz; state.el = bEl; }
    _landLog = { was, got: bLive, moved,
                 why: bLive <= 0 ? 'nothing dry+covered in range'
                    : !moved ? 'nowhere better within 6 rings' : 'moved to a feature' };
  };

  if (score(state.az, state.el) > 0.35) { nudgeToLife(); return; }

  let bestAz = state.az, bestEl = state.el, best = -2;
  /* WIDENING RINGS, AND THEY HAVE TO GO A LONG WAY.
     This used to stop after 6 rings — about 43 km on a 600 km world — and if
     every sample came back sea it simply left you where you were. These worlds
     are 59-79% ocean, so that is not a rare corner: verdant on VESPER-4 put the
     player on 5,593 m of open sea, with nothing loaded and no water surface
     drawn to tell them. 22 rings reaches roughly a quarter of the way round the
     planet, which is enough to find a coast from almost anywhere, and the
     early-out below means a normal landing still stops in the first ring or
     two and costs nothing. */
  for (let ring = 1; ring <= 22; ring++) {
    const rad = ring * 0.02;
    for (let k = 0; k < 12; k++) {
      const a = (k / 12) * Math.PI * 2;
      const az = state.az + Math.cos(a) * rad / Math.max(Math.cos(state.el), 0.2);
      const el = Math.max(-1.4, Math.min(1.4, state.el + Math.sin(a) * rad));
      const sc = score(az, el);
      if (sc > best) { best = sc; bestAz = az; bestEl = el; }
    }
    if (best > 0.6) break;                             // good enough, stop early
  }
  if (best > 0) { state.az = bestAz; state.el = bestEl; }
  nudgeToLife();
}

/* ---- input ---------------------------------------------------------------- */
const keys = { w: false, a: false, s: false, d: false, shift: false };
const grounded = () => state.land > 0.5;

let drag = false, lx = 0, ly = 0;
addEventListener('mousedown', e => { drag = true; lx = e.clientX; ly = e.clientY; });
addEventListener('mouseup', () => { drag = false; });
addEventListener('mousemove', e => {
  if (!drag) return;
  const dx = e.clientX - lx, dy = e.clientY - ly;
  if (grounded()) {
    /* On the ground the mouse TURNS YOUR HEAD; it does not move you. Dragging
       az/el down here would slide the whole body sideways while the view
       appeared to pan, which is the single most disorienting thing a
       first-person camera can do. Pitch is clamped just short of straight
       up/down because the look vector degenerates against the local radial. */
    state.heading += dx * 0.004;
    state.elLook = Math.max(-1.5, Math.min(1.5, state.elLook - dy * 0.004));
  } else {
    state.az -= dx * 0.005;
    state.el = Math.max(-1.45, Math.min(1.45, state.el + dy * 0.005));
  }
  lx = e.clientX; ly = e.clientY;
});
addEventListener('wheel', e => {
  // Down to 1.0002 radii, not 1.02: on a 310 km world 1.02 is still SIX
  // KILOMETRES up, which is nowhere near standing on it. The step also has to
  // shrink as you approach or the last 6 km takes one notch of the wheel.
  const k = state.dist < 1.05 ? 0.006 : 0.08;
  state.dist = Math.max(1.0002, Math.min(40, state.dist * (1 + Math.sign(e.deltaY) * k)));
}, { passive: true });
const CODE = { KeyW: 'w', KeyA: 'a', KeyS: 's', KeyD: 'd' };
addEventListener('keyup', e => {
  if (CODE[e.code]) keys[CODE[e.code]] = false;
  if (e.key === 'Shift') keys.shift = false;
});
addEventListener('keydown', e => {
  const i = ARCH_KEYS.indexOf(planet.def.key);
  // Physical key codes, not e.key: on the ground W/A/S/D are a SHAPE on the
  // keyboard, and reading e.key would break them under any non-QWERTY layout.
  if (CODE[e.code]) keys[CODE[e.code]] = true;
  if (e.key === 'Shift') keys.shift = true;
  if (e.key === 'l' || e.key === 'L' || e.key === 'Enter') {
    if (state.mode !== 'ground') findLandingSite();
    state.mode = state.mode === 'ground' ? 'orbit' : 'ground';
  }
  if (e.key === 'Escape') state.mode = 'orbit';

  /* ---- HARVEST -----------------------------------------------------------
     The design law says every capability is a physical object gathered with
     hands or a machine, never a menu. This is the first thing in the game that
     obeys it literally: walk to a thing, reach it, take it, and the world stays
     changed. */
  if ((e.key === 'e' || e.key === 'E') && grounded() && planet && planet.terrain) {
    const hit = nearestObj();
    if (hit && edits && edits.remove(hit.obj.id)) {
      planet.terrain.takeObject(hit);
      harvested++;
      flash = `took a ${planet.terrain.roster.label}`; flashT = lastMs * 0.001;
    }
  }
  // put the world back the way its seed made it
  if (e.key === 'R' && edits) { edits.reset(); harvested = 0; if (planet.terrain) planet.terrain.clear(); }
  if (e.key === '[') { state.seed = state.seed + 'a'; rebuild(); }
  if (e.key === ']') { state.seed = 'VESPER-' + Math.floor(Math.random() * 1e6); rebuild(); }
  if (e.key === 'ArrowLeft') state.sunAz -= 0.08;
  if (e.key === 'ArrowRight') state.sunAz += 0.08;
  if (e.key === 'ArrowUp') state.sunEl = Math.min(1.4, state.sunEl + 0.06);
  if (e.key === 'ArrowDown') state.sunEl = Math.max(-1.4, state.sunEl - 0.06);
  if (e.key === 'n') { state.arch = ARCH_KEYS[(i + 1) % ARCH_KEYS.length]; rebuild(); }
  if (e.key === 'p') { state.arch = ARCH_KEYS[(i + ARCH_KEYS.length - 1) % ARCH_KEYS.length]; rebuild(); }
  if (e.key === ' ') state.spin = state.spin ? 0 : 1;
});

/* ---- debug hook (the tests drive the viewer through this) ----------------- */
window.__V = {
  T, state, view, renderer, scene, camera, frames: 0,
  ARCH_KEYS, makePlanetDef, heightNorm, groundR,
  setWorld(seed, arch) { state.seed = seed; state.arch = arch; return build().key; },
  setCam(dist, az, el) {
    if (dist != null) state.dist = dist;
    if (az != null) state.az = az;
    if (el != null) state.el = el;
  },
  setSun(az, el) { if (az != null) state.sunAz = az; if (el != null) state.sunEl = el; },
  /* Poke any planet uniform at runtime. This is how you find out which term is
     responsible for a look instead of guessing and re-rendering twelve worlds:
     zero one, shoot, compare. `overrides` survive a rebuild. */
  overrides: {},
  setUniform(name, value) {
    window.__V.overrides[name] = value;
    const u = planet.mesh.material.uniforms[name];
    if (u) u.value = value;
    return !!u;
  },
  clearUniforms() { window.__V.overrides = {}; build(); },
  gpu, lowQuality: LOW, analytic: ANALYTIC,
  /* SURFACE OBJECTS, for tests. `goto` walks the player onto the nearest loaded
     object rather than teleporting the object to them — the harvest itself must
     still go through the real key path, or the test proves nothing about what
     the player can actually do. */
  objectInfo() {
    const t = planet && planet.terrain;
    if (!t) return null;
    const near = nearestObj();
    return {
      loaded: t.objectCount, roster: t.roster ? t.roster.label : null,
      removed: edits ? edits.count : 0,
      inReach: near ? near.obj.id : null,
      reachDist: near ? near.dist : -1,
      // the nearest object at ANY distance, so a failing reach test can tell
      // "nothing is near me" from "the reach limit is too small"
      nearestAny: (() => {
        planet.group.worldToLocal(_hitP.copy(camera.position));
        const h = t.nearestObject([_hitP.x, _hitP.y, _hitP.z], 1e9);
        return h ? { id: h.obj.id, dist: h.dist } : null;
      })(),
    };
  },
  planetGroup: null,   // set below, for tests that need the planet's frame
  radius: 0,
  findObject(id) {
    const t = planet && planet.terrain; if (!t) return null;
    for (const [, ch] of t.chunks) {
      if (!ch.objs) continue;
      const o = ch.objs.find(x => x.id === id && !x.taken);
      if (o) return o;
    }
    return null;
  },
  gotoObject() {
    const t = planet && planet.terrain;
    if (!t) return null;
    let pick = null;
    for (const [, ch] of t.chunks) {
      if (ch.objs && ch.objs.length) { pick = ch.objs.find(o => !o.taken); if (pick) break; }
    }
    if (!pick) return null;
    /* Stand ON it. az/el ARE the walker's position, so this is a walk and not a
       warp — but they are WORLD-space, and an object's dir is in the PLANET's
       frame, which is tilted and spun. Setting one from the other directly puts
       the player somewhere else on the world entirely; that is the same tilt
       bug walk.mjs caught when it thought the camera was 8 km underground. */
    _hitP.set(pick.dir[0], pick.dir[1], pick.dir[2])
      .applyQuaternion(planet.group.quaternion).normalize();
    state.el = Math.max(-1.45, Math.min(1.45, Math.asin(Math.max(-1, Math.min(1, _hitP.y)))));
    state.az = Math.atan2(_hitP.x, _hitP.z);
    // DIAGNOSTIC: round-trip the angles we just set back through the exact
    // expression frame() uses, convert to the planet's frame, and report how far
    // off the object we actually landed. Reasoning about which way a quaternion
    // goes is how this kind of bug survives; measuring it is how it does not.
    const ce = Math.cos(state.el), se = Math.sin(state.el);
    const ca = Math.cos(state.az), sa = Math.sin(state.az);
    const w = new T.Vector3(ce * sa, se, ce * ca);
    planet.group.worldToLocal(w).normalize();
    const od = new T.Vector3(pick.dir[0], pick.dir[1], pick.dir[2]);
    return { id: pick.id, errM: od.angleTo(w) * planet.def.radius };
  },
  /* WHERE AM I STANDING, according to the game rather than to the test. The
     planet GROUP is tilted, so a height query has to be made in the planet's
     own frame — a test that builds a direction from state.az/el and calls
     groundR() straight is asking about a different point on the world, and on
     a 310 km planet with 23 km of relief that came out as "you are 8 km
     underground" when the camera was in fact exactly at eye height. */
  landInfo() { return _landLog; },
  groundInfo() {
    if (!planet) return null;
    const p = camera.position, dd = p.length();
    planet.group.worldToLocal(_camLocal.copy(p)).normalize();
    const d3 = [_camLocal.x, _camLocal.y, _camLocal.z];
    const gR = groundR(planet.def, d3);
    const tr = planet.terrain;
    /* ON WATER OR ON LAND. groundR clamps at sea level (heightField.js says so
       in as many words: "this clamps to sea level, so a naive spawn using it
       stands the player ON THE OCEAN as if it were solid"), so altitude alone
       can never tell you — you are always exactly eye height above SOMETHING.
       The height field has to be asked directly. */
    const sea = seaNorm(planet.def);
    const hn = heightNorm(planet.def, d3);
    return { d: dd, gR, alt: dd - gR, land: state.land, mode: state.mode,
             radius: planet.def.radius,
             onWater: hn <= sea,
             seaDepth: sea > -Infinity ? Math.max(0, (sea - hn) * planet.def.relief) : 0,
             terrain: !!(tr && tr.group.visible),
             leaves: tr ? tr.leaves : -1, depth: tr ? tr.depth : -1,
             built: tr ? tr.chunks.size : -1, pending: tr ? tr.pending.length : -1 };
  },
  /* the bake, exposed so tests/bake_conformance.mjs can read the baked height
     back at known directions and check it against the CPU mirror — that is how
     a flipped cube face gets caught as a number instead of as "looks odd" */
  bakeInfo() {
    return planet ? { baked: planet.baked, ms: planet.bakeMs,
                      compileMs: planet.compileMs || 0,
                      size: planet.bake ? planet.bake.size : 0 } : null;
  },
  /* bake n.y and read it back — pure orientation check, no height field */
  probeOrientation(dirs, pass) {
    if (!planet || !planet.bake) return null;
    const out = Array.from(
      planet.bake.checkOrientation(planet.uniforms, Float32Array.from(dirs), pass));
    planet.bake.run(planet.uniforms);      // put the real world back
    return out;
  },
  probeBaked(dirs, mode) {
    if (!planet || !planet.bake) return null;
    return Array.from(planet.bake.probeHeights(Float32Array.from(dirs), mode || 0));
  },
};

/* Yield to the browser so the dark curtain is actually ON SCREEN before any of
   the blocking work starts. Without this the first paint happens after shader
   compilation, and the window sits white for the whole of it. */
(async () => {
  try {
    if (!gpu.ok) { bootError(new Error('WebGL unavailable: ' + gpu.reason)); return; }
    bootMsg(LOW ? 'software renderer — reducing quality' : 'building a world…',
            LOW ? SOFTWARE_HELP : '');
    await painted();
    if (LOW) await new Promise(r => setTimeout(r, 2600));   // time to read it

    // Say the true thing BEFORE the stall, because nothing can be said during
    // it — compiling blocks the main thread and no script runs until it ends.
    const first = isFirstRun();
    bootMsg(first ? 'compiling the renderer…' : 'building a world…');
    bootNote(first ? FIRST_RUN_NOTE : '');
    await painted();

    const t0 = performance.now();
    build();
    bootBuildMs = performance.now() - t0;
    markRun();
    requestAnimationFrame(frame);
  } catch (e) {
    bootError(e);
    throw e;
  }
})();
