// Boot, mode select, and the wiring. Everything above this file is a module that does one
// thing; this is where they meet.

import { TUNING as T } from './data/tuning.js';
import { createLoop } from './core/loop.js';
import { createInput, createReplay, unpackInput } from './core/input.js';
import { createSave } from './core/save.js';
import { createAudio } from './core/audio.js';
import { bus } from './core/events.js';
import { makeRng } from './core/rng.js';
import { createCamera } from './render/camera.js';
import { createSprites } from './render/sprites.js';
import { createFx } from './render/fx.js';
import { createParallax } from './render/parallax.js';
import { createHud } from './render/hud.js';
import { createUi } from './render/ui.js';
import { createRenderer } from './render/render.js';
import { createRun } from './game/run.js';
import { createMedals } from './game/medals.js';
import { createMachine, S } from './game/state.js';
import { createLtf1 } from './game/modes/ltf1.js';
import { createLtf2Story } from './game/modes/ltf2-story.js';
import { createLtf2Classic } from './game/modes/ltf2-classic.js';
import { createLtf2Arcade } from './game/modes/ltf2-arcade.js';
import { SECRET_SHOP } from './data/ltf2.catalog.js';
import { freshLevels, UPGRADES } from './data/ltf1.upgrades.js';

const canvas = document.getElementById('game');
const ctx = canvas.getContext('2d', { alpha: false });

// Landmine 17 — scale the backing store by devicePixelRatio and set the CSS size separately,
// or the whole thing is blurry on any modern display.
let W = 0, H = 0;
function resize() {
  // clamp BELOW at 1 too: a zoomed-out browser reports dpr < 1, and honouring that
  // renders the game smaller than its own CSS pixels and softens every outline.
  const dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
  const cssW = canvas.clientWidth || window.innerWidth;
  const cssH = canvas.clientHeight || window.innerHeight;
  canvas.width = Math.round(cssW * dpr);
  canvas.height = Math.round(cssH * dpr);
  W = cssW; H = cssH;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}
window.addEventListener('resize', resize);

const rngGlobal = makeRng(0x1EAF1);
const rand = () => rngGlobal.next();

const saveStore = createSave();
let save = saveStore.load();
const warning = saveStore.takeWarning();

const audio = createAudio();
audio.setVolume(save.settings.volume);
audio.setMuted(save.settings.muted);

const input = createInput(window);
const camera = createCamera(T);
const sprites = createSprites('assets/');
const fx = createFx(T, rand);
const parallax = createParallax(T, rand);
const hud = createHud(T);
const ui = createUi(T);
const renderer = createRenderer(T, { camera, sprites, fx, parallax, hud });
const medals = createMedals(bus, T);
const startRun = createRun(T, { bus, fx, audio, camera });

const MODES = {
  ltf1: createLtf1(T),
  'ltf2-story': createLtf2Story(T),
  'ltf2-classic': createLtf2Classic(T),
  'ltf2-arcade': createLtf2Arcade(T),
};
// SANDBOX (§1) — every upgrade unlocked, no economy, just the physics playground.
MODES.sandbox = (() => {
  const base = createLtf1(T);
  const maxed = {}; for (const k of Object.keys(UPGRADES)) maxed[k] = UPGRADES[k].tiers.length - 1;
  return {
    ...base, id: 'sandbox', title: 'Sandbox', goalFt: null,
    slot: () => ({ day: 0, cash: 0, upgrades: maxed }),
    loadout: () => base.loadout({ ltf1: { upgrades: maxed } }),
    gliderTier: () => maxed.glider, rampTier: () => maxed.rampHeight,
    shop: (s) => ({ ...base.shop({ ...s, ltf1: { ...s.ltf1, upgrades: maxed, day: 0 } }), items: [] }),
    settle: (s, run) => ({ pay: base.settle({ ...s, ltf1: { ...s.ltf1, upgrades: maxed } }, run).pay, victory: false, extras: ['sandbox — nothing is saved'] }),
    nextDay: () => maxed,
    newGear: () => [],
  };
})();

const launcherClouds = [];
for (let i = 0; i < 9; i++) {
  launcherClouds.push({ x: rand() * 1400, y: 60 + rand() * 200, s: 40 + rand() * 90, seed: (i * 7919) | 0, drift: 6 + rand() * 14 });
}

// The application context every state reads. One object, passed once.
const G = {
  T, ctx, bus, input, audio, camera, sprites, fx, parallax, hud, ui, renderer, medals,
  startRun, rand, launcherClouds,
  get w() { return W; }, get h() { return H; },
  get save() { return save; },
  mode: MODES.ltf1,
  warning,
  hideHud: false, showDebug: false, showPivot: false, replaying: false, replay: null,
  frameDt: 1 / 60,
  reducedMotion: save.settings.reducedMotion,
  loop: null, machine: null,

  setMode(id) {
    G.mode = MODES[id] || MODES.ltf1;
    if (G.mode.mode) save.ltf2.mode = G.mode.mode;
  },
  persist() { saveStore.save(save); },
  resetSave() {
    save = saveStore.reset();
    medals.attach(save.account, onMedal);
    audio.back();
  },
  secretItems() {
    return SECRET_SHOP.map((s) => ({ ...s, owned: !!save.ltf2.secrets[s.id] }));
  },
  buySecret(id) {
    const item = SECRET_SHOP.find((s) => s.id === id);
    if (!item || save.ltf2.secrets[id] || save.account.bp < item.price) return false;
    save.account.bp -= item.price;
    save.ltf2.secrets[id] = true;
    if (id === 'brick') save.ltf2.secrets.brickEquipped = true;
    bus.emit('shop:bought', { id });
    return true;
  },
};

// ── medal toasts ───────────────────────────────────────────────────────────────────────
const toasts = [];
function onMedal(m) {
  audio.medal();
  toasts.push({ name: m.name, bp: m.bp, life: 3.2, maxLife: 3.2 });
}
medals.attach(save.account, onMedal);

// `usingBrick` is a flag medals.js reads to award Revenge of the Brick, and it can only be
// known here, where the loadout is built.
bus.on('state:changed', (e) => {
  if (e.to === S.PRE_LAUNCH) {
    save.account.flags = save.account.flags || {};
    save.account.flags.usingBrick = !!(G.mode.loadout(save).usingBrick);
  }
});

const machine = createMachine(G);
G.machine = machine;

// ── input plumbing ─────────────────────────────────────────────────────────────────────

const mouse = { x: 0, y: 0 };
canvas.addEventListener('mousemove', (e) => {
  const r = canvas.getBoundingClientRect();
  mouse.x = e.clientX - r.left;
  mouse.y = e.clientY - r.top;
});
canvas.addEventListener('click', (e) => {
  audio.unlock();
  const r = canvas.getBoundingClientRect();
  machine.click(ui.hitAt(e.clientX - r.left, e.clientY - r.top));
});

input.onKey((code) => {
  audio.unlock();
  // §15 — capture mode. These are for the video and they are all one line each.
  switch (code) {
    case 'F3': G.showDebug = !G.showDebug; return;
    case 'F9': G.hideHud = !G.hideHud; return;
    case 'F10': machine.go(machine.current === S.SIDEBYSIDE ? S.LAUNCHER : S.SIDEBYSIDE); return;
    case 'KeyP': G.showPivot = !G.showPivot; return;
    case 'BracketLeft': G.loop.timeScale = Math.max(0.1, G.loop.timeScale * 0.5); return;
    case 'BracketRight': G.loop.timeScale = Math.min(2, G.loop.timeScale * 2); return;
    case 'F8': startReplay(); return;
    case 'KeyM': audio.setMuted(!audio.muted); save.settings.muted = audio.muted; G.persist(); return;
    default: break;
  }
  machine.key(code);
});

/** §15 — deterministic replay. Test 9 is what makes this exact rather than approximate. */
function startReplay() {
  const frames = machine.data.lastInputs;
  if (!frames || !frames.length) return;
  G.replay = createReplay(frames.map(unpackInput));
  G.replaying = true;
  machine.go(S.PRE_LAUNCH);
  setTimeout(() => machine.go(S.LAUNCH), 30);
}
bus.on('state:changed', (e) => { if (e.to === S.RESULTS || e.to === S.LAUNCHER) G.replaying = false; });

// ── the loop ───────────────────────────────────────────────────────────────────────────

const loop = createLoop({
  dt: T.DT,
  maxSteps: T.MAX_STEPS,
  maxFrameDt: T.MAX_FRAME_DT,
  step(dt) { machine.step(dt); },
  render(alpha, real) {
    G.frameDt = real;
    ctx.fillStyle = '#0d1117';
    ctx.fillRect(0, 0, W, H);
    renderer.tick(real);
    ui.begin(mouse);
    machine.draw();
    ui.resolveHover();

    for (let i = toasts.length - 1; i >= 0; i--) {
      toasts[i].life -= real;
      if (toasts[i].life <= 0) { toasts.splice(i, 1); continue; }
      ui.medalToast(ctx, W, H, toasts[i]);
    }
    if (G.replaying) {
      ctx.fillStyle = 'rgba(255,80,80,0.9)';
      ctx.font = '700 14px system-ui, sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('REPLAY', 20, H - 20);
    }
    if (loop.timeScale !== 1) {
      ctx.fillStyle = 'rgba(255,255,255,0.7)';
      ctx.font = '700 14px system-ui, sans-serif';
      ctx.textAlign = 'right';
      ctx.fillText(`${loop.timeScale.toFixed(2)}x`, W - 20, H - 20);
    }
    if (!G.hideHud && machine.current === S.LAUNCHER) ui.keyHints(ctx, W, H);
    input.endFrame();
  },
});
G.loop = loop;

// ── go ─────────────────────────────────────────────────────────────────────────────────

resize();
sprites.load().then(() => loop.start());
// If the manifest fetch hangs on a cold server, start anyway — placeholder art is the
// supported configuration and waiting on assets must never block playing (§8.4).
setTimeout(() => loop.start(), 1200);

window.LTF = { G, machine, save: () => save, T, MODES, medals };
