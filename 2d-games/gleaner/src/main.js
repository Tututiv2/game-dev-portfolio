// GLEANER — mobile tower defence in the Goldsea.
// One lever sets your income and your difficulty. It is the same lever.
import { CFG, HARDPOINTS } from './config.js';
import { bakeEnemies, bakeHarvester, TOWER_ART } from './art.js';
import { TOWERS } from './towers.js';
import { Audio } from './audio.js';
import { Input } from './input.js';
import { Renderer } from './render.js';
import { drawHUD, drawTitle, drawDead } from './hud.js';
import { createWorld, stepSim } from './sim.js';

// ---- headless self-test:  /?test  ----
const TESTING = location.search.includes('test');
if (TESTING) {
  const { report } = await import('./selftest.js');
  const r = report();
  console.log(r.text);
  const el = document.getElementById('boom');
  el.style.display = 'flex';
  el.style.color = r.pass === r.total ? '#8fd07a' : '#e0533a';
  el.style.alignItems = 'flex-start';
  el.textContent = r.text;
  window.HARVTEST = r;
}

const canvas = document.getElementById('game');
const R = new Renderer(canvas);
const input = new Input(canvas);
const audio = new Audio();

const sprites = bakeEnemies();
const hvArt = bakeHarvester();

let w = null;
let state = 'title';
const _p = { x: 0, y: 0 };
const _m = { x: 0, y: 0 };

function newRun() {
  return createWorld({
    audio,
    viewRadius: R.viewRadius,
    extras: { sprites, hvArt, towerArt: TOWER_ART },
  });
}

/** Input object -> the plain control record the sim understands. */
const ctrl = { up: false, down: false, left: false, right: false, cut: false, repair: false };
function readCtrl() {
  ctrl.up = input.down('up');
  ctrl.down = input.down('down');
  ctrl.left = input.down('left');
  ctrl.right = input.down('right');
  ctrl.cut = input.down('cut');
  ctrl.repair = input.down('repair');
  return ctrl;
}

/* ------------------------------ placement ------------------------------ */

function pickHardpoint(w) {
  if (w.selected < 0) return -1;
  R.toWorld(w.cam, input.mx, input.my, _m);
  let best = -1, bestD = 34 * 34;
  for (let s = 0; s < HARDPOINTS.length; s++) {
    w.towers.worldOf(w.hv, s, _p);
    const dx = _m.x - _p.x, dy = _m.y - _p.y;
    const d = dx * dx + dy * dy;
    if (d < bestD) { bestD = d; best = s; }
  }
  return best;
}

/** Right-click sells whatever is under the cursor, selected or not. */
function pickMounted(w) {
  R.toWorld(w.cam, input.mx, input.my, _m);
  let best = -1, bestD = 26 * 26;
  for (let s = 0; s < HARDPOINTS.length; s++) {
    if (!w.towers.mounted[s]) continue;
    w.towers.worldOf(w.hv, s, _p);
    const dx = _m.x - _p.x, dy = _m.y - _p.y;
    const d = dx * dx + dy * dy;
    if (d < bestD) { bestD = d; best = s; }
  }
  return best;
}

function handleBuild(w) {
  const dig = input.digit();
  if (dig >= 0 && dig < TOWERS.length) {
    if (w.hv.dist < CFG.unlockAt[dig]) { audio.deny(); }
    else w.selected = w.selected === dig ? -1 : dig;
  }
  if (input.hit('Escape')) w.selected = -1;
  if (input.hit('KeyM')) audio.toggleMute();

  w.hoverSlot = pickHardpoint(w);

  if (input.rightClicked) {
    const s = pickMounted(w);
    if (s >= 0) {
      w.towers.worldOf(w.hv, s, _p);
      w.grain += w.towers.remove(s);
      audio.place();
      w.fx.burst(_p.x, _p.y, 8, '#d8c48a');
    } else {
      w.selected = -1;
    }
  }

  if (input.clicked && w.selected >= 0) {
    const s = w.hoverSlot;
    const cost = w.towers.costOf(w.selected);
    if (s < 0 || w.towers.mounted[s]) audio.deny();
    else if (w.grain < cost) audio.deny();
    else {
      w.grain -= cost;
      w.towers.place(s, w.selected);
      audio.place();
      w.towers.worldOf(w.hv, s, _p);
      w.fx.ring(_p.x, _p.y, 26, 'rgba(150,255,190,0.8)');
      if (w.grain < w.towers.costOf(w.selected)) w.selected = -1;
    }
  }
}

/* ------------------------------ loop ------------------------------ */

let last = performance.now();

function frame(now) {
  const dt = Math.min((now - last) / 1000, 1 / 20);
  last = now;
  const g = R.g;

  if (state === 'title') {
    R.g.setTransform(R.dpr, 0, 0, R.dpr, 0, 0);
    g.fillStyle = '#c9962f';
    g.fillRect(0, 0, R.vw, R.vh);
    drawTitle(g, R, now / 1000);
    if (input.clicked) {
      audio.start();
      w = newRun();
      state = 'play';
    }
  } else if (state === 'play') {
    w.viewRadius = R.viewRadius;   // time and shake are advanced inside stepSim
    handleBuild(w);
    stepSim(w, dt, readCtrl());
    audio.setThrottle(w.hv.throttle);

    if (w.hv.hull < w.lastHull - 22) {
      audio.hurt();
      w.shake = Math.max(w.shake, 4);
      w.lastHull = w.hv.hull;
    } else if (w.hv.hull > w.lastHull) {
      w.lastHull = w.hv.hull;
    }

    R.updateCamera(w.cam, w.hv, dt);
    w.field.prune(w.cam.x, w.cam.y);

    R.draw(w);
    drawHUD(g, w, R);

    if (w.hv.hull <= 0) {
      state = 'dead';
      audio.setThrottle(0);
      audio.death();
      w.shake = 22;
    }
  } else if (state === 'dead') {
    w.time += dt;
    w.shake = Math.max(0, w.shake - CFG.shakeDecay * dt * 6);
    w.fx.update(dt);
    R.draw(w);
    drawHUD(g, w, R);
    drawDead(g, R, w);
    if (input.clicked) { w = newRun(); state = 'play'; }
  }

  input.endFrame();
  requestAnimationFrame(frame);
}

if (!TESTING) requestAnimationFrame(frame);

// Debug handle — lets a browser console (or an automated check) read the live
// run without reaching into module scope.
window.HARV = {
  get w() { return w; },
  get state() { return state; },
  set state(s) { state = s; },
};
