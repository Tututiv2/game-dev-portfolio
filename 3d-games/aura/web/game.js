// AURA -- the game. Owns the loop, input, camera, combat, the story and the dojo.
// Characters (actors.js), world/fx/post and audio are built behind CONTRACT.md.
//
// Three clocks, and which one a thing runs on matters:
//   rt  real seconds            -- UI, hit-stop, post pulses
//   gt  game seconds (scaled)   -- movement, animation, weak-phase goblins
//   a   audio seconds           -- the song, every beat-locked attack, every skill check
// Presses are stamped on the audio clock minus the player's timing offset. A hit
// that lands on a beat is HEARD `outputLatency` later, so it is graded at impact+lat.

import * as THREE from './vendor/three/three.module.js';
import { buildWorld, buildDojo } from './world.js';
import { FX } from './fx.js';
import { Post } from './post.js';
import { makePlayer, makeSister, makeGoblin, makeOgre, makeViewmodel } from './actors.js';
import { AudioEngine } from './audio/engine.js';
import { Input } from './input.js';
import { UI } from './ui.js';
import { BeatGrid, ParryInput, WINDOWS } from './sim/beat.js';
import * as C from './sim/combat.js';
import { Director, T } from './sim/director.js';
import * as SK from './sim/skill.js';

// ------------------------------------------------------------------ settings
const DEFAULTS = {
  sens: 1, fov: 80, offset: 0, master: 0.45, music: 0.7, sfx: 0.7, v: 2, invert: false, beatHud: true,
  view: 'third', directional: true, flow: true, reset: true, deflect: true, track: 'code',
};
const settings = (() => {
  try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem('aura:settings') || '{}') }; }
  catch { return { ...DEFAULTS }; }
})();
if (!settings.v || settings.v < 2) { settings.master = 0.45; settings.music = 0.7; settings.sfx = 0.7; settings.v = 2; }  // first build was far too loud
function saveSettings() { try { localStorage.setItem('aura:settings', JSON.stringify(settings)); } catch {} }

// ------------------------------------------------------------------ core objects
const canvas = document.getElementById('c');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.NoToneMapping;

const camera = new THREE.PerspectiveCamera(settings.fov, innerWidth / innerHeight, 0.05, 900);
const post = new Post(renderer);
const ui = new UI();
const A = new AudioEngine();
let audioReady = false;
const perf0 = performance.now();
const aNow = () => (audioReady ? A.now : (performance.now() - perf0) / 1000);
const pressClock = () => aNow() - settings.offset / 1000;
const outLat = () => (audioReady ? A.outputLatency : 0);
const input = new Input(canvas, pressClock);

const storyScene = new THREE.Scene();
const world = buildWorld(storyScene, renderer);
const dojo = buildDojo();
const fxStory = new FX(storyScene);
const fxDojo = new FX(dojo.scene);
const sun = world.sun;
if (sun && sun.shadow) sun.shadow.normalBias = Math.max(sun.shadow.normalBias || 0, 0.02);  // toon cloth acne otherwise
const sunOffset = sun ? sun.position.clone().sub(sun.target.position) : new THREE.Vector3(20, 30, 10);
if (sun && !sun.target.parent) storyScene.add(sun.target);

const director = new Director();
const parry = new ParryInput();
const flow = new SK.Flow();

// ------------------------------------------------------------------ small helpers
const V = (x = 0, y = 0, z = 0) => new THREE.Vector3(x, y, z);
const tmp = V(), tmp2 = V(), tmp3 = V();
const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
const lerp = (a, b, k) => a + (b - a) * k;
const damp = (a, b, rate, dt) => a + (b - a) * (1 - Math.exp(-rate * dt));
const angDiff = (a, b) => { let d = b - a; while (d > Math.PI) d -= Math.PI * 2; while (d < -Math.PI) d += Math.PI * 2; return d; };
const yawTo = (from, to) => Math.atan2(to.x - from.x, to.z - from.z);
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
let rand = rng(1337);
const sock = (actor, name, out = V()) => {
  const s = actor.sockets && actor.sockets[name];
  if (!s) return out.copy(actor.root.position).add(tmp3.set(0, actor.height * 0.6, 0));
  return s.getWorldPosition(out);
};
function safePose(actor, dt, s) { try { actor.pose(dt, s); } catch (e) { if (!safePose.warned) { console.warn('pose failed', e); safePose.warned = true; } } }

// ------------------------------------------------------------------ game state
const G = {
  mode: 'menu',         // 'menu' | 'story' | 'dojo'
  paused: false,
  scene: storyScene,
  fx: fxStory,
  rt: 0, gt: 0,
  ts: 1, tsTarget: 1, hitstopUntil: 0,
  frozen: false,
  shake: 0, fovKick: 0,
  cam: 'play',          // 'play' | 'cine'
  look: { gray: 0, invert: 0, flash: 0, speed: 0, aberration: 0, down: 0 },
  base: { sat: 0.75, contrast: 1, gray: 0, exposure: 1, vignette: 0.4, tint: [0.92, 0.96, 1.05] },
  awaken: 0, awakenTarget: 0,
  awk: null,            // awakening timeline state
  heartbeatAt: 0,
  release: null,        // scheduled aura release
  endShown: false,
  dojo: { drill: 'parry', bpm: 130, stats: null, orbs: [] },
};

const P = {
  actor: null, vm: null,
  pos: V(), vel: V(), yaw: 0, camYaw: 0, camPitch: -0.12, grounded: true,
  hp: C.PLAYER.maxHp, aura: 0, awakened: false, control: false,
  act: null, comboIdx: 0, comboUntil: 0,
  dashUntil: 0, dashDir: V(), iUntil: 0, hangUntil: 0, dashCooldownUntil: 0,
  air: new SK.AirState(),
  score: 0, counterUntilA: 0, invulnerable: false,
  stats: null,
};
function freshStats() { return { perfects: 0, blocks: 0, hitsTaken: 0, resets: 0, deflectKills: 0, chain: 0, maxChain: 0, kills: 0, start: 0, bestFlow: 0, awakenGrade: null }; }

let enemies = [];     // goblins + ogre
let OG = null;        // the ogre entry in enemies
let SIS = null;       // { actor, pos, vel, state }
let spears = [];
let pend = [];        // pending impacts awaiting parry resolution
let debris = [];      // severed arm etc.

// ------------------------------------------------------------------ actors
function buildPlayer() {
  if (P.actor) { P.actor.root.removeFromParent(); }
  P.actor = makePlayer();
  if (!P.vm) { P.vm = makeViewmodel(); camera.add(P.vm.group); }
  P.actor.root.traverse((o) => { if (o.isMesh) o.castShadow = true; });
}

function makeSpearMesh() {
  const g = new THREE.Group();
  const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.022, 0.022, 1.5, 6), new THREE.MeshToonMaterial({ color: 0x5a4028 }));
  shaft.rotation.x = Math.PI / 2;
  const tip = new THREE.Mesh(new THREE.ConeGeometry(0.05, 0.22, 6), new THREE.MeshToonMaterial({ color: 0xb8c2c8 }));
  tip.rotation.x = Math.PI / 2; tip.position.z = 0.85;
  g.add(shaft, tip);
  return g;
}

function spawnGoblin(pos, opts = {}) {
  const seed = Math.floor(rand() * 1e9);
  const actor = makeGoblin(opts.thrower ? seed | 1 : seed);
  actor.root.traverse((o) => { if (o.isMesh) o.castShadow = true; });
  G.scene.add(actor.root);
  const g = {
    kind: 'goblin', actor, pos: pos.clone(), vel: V(), yaw: yawTo(pos, P.pos), hp: C.GOBLIN.hp,
    state: 'approach', st: G.gt, att: null, cooldownUntil: G.gt + 1 + rand() * 1.5, stunUntil: 0,
    thrower: !!opts.thrower, dead: false, deadAt: 0, strafe: rand() < 0.5 ? 1 : -1, radius: C.GOBLIN.radius,
    dir: rand() < 0.5 ? 'left' : 'right', hurtUntil: 0, knock: V(),
  };
  actor.root.position.copy(g.pos);
  enemies.push(g);
  return g;
}

function spawnOgre(pos) {
  const actor = makeOgre();
  actor.root.traverse((o) => { if (o.isMesh) o.castShadow = true; });
  G.scene.add(actor.root);
  OG = {
    kind: 'ogre', actor, pos: pos.clone(), vel: V(), yaw: yawTo(pos, P.pos), hp: C.BRUTE.hp, maxHp: C.BRUTE.hp,
    state: 'idle', plan: null, recoverUntilA: 0, staggerUntilA: 0, roarUntilA: 0, dead: false, deadAt: 0,
    holding: false, squeeze: 0, action: 'idle', actionT: 0, severed: false, radius: C.BRUTE.radius,
    lastDropPlanned: -1, perfectsInPlan: 0, hurtUntil: 0,
  };
  actor.root.position.copy(OG.pos);
  enemies.push(OG);
  return OG;
}

function clearActors() {
  for (const e of enemies) { e.actor.root.removeFromParent(); try { e.actor.dispose && e.actor.dispose(); } catch {} }
  enemies = []; OG = null;
  if (SIS) { SIS.actor.root.removeFromParent(); SIS = null; }
  for (const s of spears) s.mesh.removeFromParent();
  spears = [];
  for (const d of debris) d.obj.removeFromParent();
  debris = [];
  for (const o of G.dojo.orbs) o.mesh.removeFromParent();
  G.dojo.orbs = [];
  pend = [];
}

// ------------------------------------------------------------------ mode setup
function useScene(scene, fx) {
  G.scene = scene; G.fx = fx;
  scene.add(camera);
  if (P.actor) scene.add(P.actor.root);
}

function resetPlayer(pos, yaw, awakened) {
  P.pos.copy(pos); P.vel.set(0, 0, 0); P.yaw = yaw; P.camYaw = yaw; P.camPitch = -0.12;
  P.grounded = true; P.hp = C.PLAYER.maxHp; P.aura = 0; P.awakened = awakened; P.control = true;
  P.act = null; P.comboIdx = 0; P.dashUntil = 0; P.iUntil = 0; P.hangUntil = 0; P.counterUntilA = 0;
  P.air = new SK.AirState(); P.score = 0; P.stats = freshStats(); P.stats.start = G.rt;
  P.actor.setAura && P.actor.setAura(awakened ? 1 : 0);
  P.vm.setAura && P.vm.setAura(awakened ? 1 : 0);
  flow.reset(); flow.best = 0; parry.reset();
}

function startStory(skip) {
  hideScreens();
  G.mode = 'story'; G.endShown = false; G.paused = false;
  clearActors();
  useScene(storyScene, fxStory);
  rand = rng(20260929);
  resetPlayer(world.spots.start, 0, false);
  SIS = { actor: makeSister(), pos: world.spots.start.clone().add(V(-1.2, 0, -1.6)), vel: V(), yaw: 0, state: 'follow', st: 0 };
  SIS.actor.root.traverse((o) => { if (o.isMesh) o.castShadow = true; });
  storyScene.add(SIS.actor.root);
  G.awaken = 0; G.awakenTarget = 0; G.awk = null; G.release = null;
  G.base = { sat: 0.62, contrast: 1, gray: 0, exposure: 1, vignette: 0.45, tint: [0.92, 0.96, 1.06] };
  if (audioReady) { A.music.stop(0.2); A.setMuffle(0, 0.2); A.setAmbient(0.65, 2); }
  director.start(skip);
  ui.hud(true); ui.boss(false); ui.cine(skip ? 'cine' : 'off'); ui.timing(false);
  ui.show('dojoPanel', false);
  if (skip) { enterDowned(true); }
  else ui.subtitle('The road home. Stay close to me.', 3.2);
  lockSoon();
}

function startDojo() {
  hideScreens();
  G.mode = 'dojo'; G.endShown = false; G.paused = false;
  clearActors();
  useScene(dojo.scene, fxDojo);
  rand = rng(777);
  resetPlayer(dojo.spots.player, 0, true);
  P.invulnerable = true;
  G.base = { sat: 1.08, contrast: 1.08, gray: 0, exposure: 1, vignette: 0.4, tint: [0.96, 1, 1.05] };
  G.awaken = 1; G.awakenTarget = 1; G.release = null;
  director.go('dojo');
  ui.hud(true); ui.boss(false); ui.cine('off'); ui.show('dojoPanel', true); ui.timing(true);
  ui.timingZones(WINDOWS.perfect * 1000, WINDOWS.lateGrace * 1000, WINDOWS.good * 1000);
  if (audioReady) { A.setMuffle(0, 0.1); A.setAmbient(0.25, 1); }
  setDrill(G.dojo.drill);
  lockSoon();
}

function toMenu() {
  hideScreens();
  G.mode = 'menu'; G.paused = false;
  if (audioReady) { A.music.stop(0.6); A.setMuffle(0, 0.2); A.setAmbient(0.5, 1); try { A.ctx.state === 'suspended' && A.ctx.resume(); } catch {} }
  ui.hud(false); ui.cine('off'); ui.show('dojoPanel', false); ui.timing(false); ui.prompt(''); ui.ring(false);
  input.unlock();
  document.getElementById('menu').classList.remove('hidden');
  // the title screen sits over a slow drift through the quiet world
  clearActors(); useScene(storyScene, fxStory);
  resetPlayer(world.spots.start, 0, false);
  P.actor.root.visible = false;
  G.base = { sat: 0.8, contrast: 1, gray: 0, exposure: 1, vignette: 0.5, tint: [0.92, 0.96, 1.06] };
  G.awaken = 0; G.awakenTarget = 0;
}

function hideScreens() { for (const id of ['menu', 'settings', 'pause', 'end']) ui.show(id, false); }
function lockSoon() { input.lock(); setTimeout(() => ui.lockHint(G.mode !== 'menu' && !input.locked && !G.paused && !G.endShown), 250); }

// ------------------------------------------------------------------ player
function stats() { return P.awakened ? C.PLAYER.awakened : C.PLAYER.weak; }
function camForward(out = V()) {
  const cp = Math.cos(P.camPitch);
  return out.set(Math.sin(P.camYaw) * cp, Math.sin(P.camPitch), Math.cos(P.camYaw) * cp);
}
function flatForward(yaw, out = V()) { return out.set(Math.sin(yaw), 0, Math.cos(yaw)); }
function flatRight(yaw, out = V()) { return out.set(-Math.cos(yaw), 0, Math.sin(yaw)); }
function heard(t) { return t + outLat(); }  // an audio-clock event as the ear receives it

function flowAct(t) {
  if (!settings.flow || !A.music || !A.music.grid || !P.awakened) return;
  const r = flow.act(t - outLat(), A.music.grid);
  if (r.result === 'up') {
    A.sfx('flowUp', { pitch: 1 + Math.min(flow.level, 20) * 0.04, vol: 0.5 });
    if (flow.level % 4 === 0) { G.fx.burst('flow', sock(P.actor, 'chest'), null, 16); }
  } else if (r.result === 'break') {
    A.sfx('flowBreak', { vol: 0.7 });
    ui.pop('FLOW BROKEN', 'miss', 700);
  }
  ui.flow(flow.mult, flow.level, r.result);
  if (G.mode === 'dojo' && G.dojo.drill === 'flow' && r.off != null) {
    const ms = r.off * 1000;
    ui.timingTick(ms, Math.abs(r.off) <= SK.FLOW.on ? 'perfect' : Math.abs(r.off) <= SK.FLOW.off ? 'good' : 'miss');
  }
  P.stats.bestFlow = Math.max(P.stats.bestFlow, flow.level);
}

function addAura(n) { if (P.awakened) P.aura = clamp(P.aura + n, 0, C.AURA.max); }
function addScore(kind, extra = 1) {
  if (!P.awakened) return;
  const pts = Math.round(SK.score(kind, settings.flow ? flow.mult : 1, !P.grounded) * extra);
  P.score += pts;
  if (pts >= 100) ui.pop(`+${pts}`, 'points', 800);
}

function handleActions() {
  for (const ev of input.drain()) {
    const t = ev.t;
    if (ev.type === 'escape') continue; // pointer lock loss handles pause
    if (ev.type === 'view') { settings.view = settings.view === 'first' ? 'third' : 'first'; saveSettings(); continue; }
    if (ev.type === 'panel' && G.mode === 'dojo') { document.getElementById('dojoPanel').classList.toggle('hidden'); continue; }
    if (ev.type === 'skip' && (director.phase === 'downed' || director.phase === 'reveal')) { skipToGetup(); continue; }
    const ph = director.phase;
    if (ph === 'getup' && ev.type === 'jump') { getUpPress(); continue; }
    if (ph === 'awaken' && ev.type === 'attack') { if (G.awk && G.awk.click == null && aNow() >= G.awk.plan.ringAt - 0.25) G.awk.click = t; continue; }
    if (!P.control) continue;
    const st = stats();
    const g = G.gt;
    if (ev.type === 'attack') {
      if (P.act && P.act.name === 'attack' && (g - P.act.t0) < P.act.dur * 0.62) { P.act.buffered = t; continue; }
      if (P.act && P.act.name === 'hurt') continue;
      startAttack(t);
    } else if (ev.type === 'parry') {
      if (P.act && P.act.name === 'hurt') continue;
      parry.press(t);
      P.act = { name: 'parry', t0: g, dur: 0.32, dir: 'over' };
      A.sfx('swing', { vol: 0.25, pitch: 1.4 });
      flowAct(t);
    } else if (ev.type === 'jump') {
      if (P.grounded) {
        P.vel.y = P.awakened ? SK.AIR.jump : 6.2;
        P.grounded = false; P.air.leave();
        A.sfx('jump', { vol: 0.6 });
        flowAct(t);
      }
    } else if (ev.type === 'dash') {
      if (!st.dash) continue;
      if (g < P.dashCooldownUntil) continue;
      if (!P.air.canDash()) { ui.pop('NO DASH', 'miss', 400); continue; }
      startDash(t);
    } else if (ev.type === 'release') {
      if (P.awakened && P.aura >= C.AURA.max && !G.release) {
        const grid = A.music && A.music.grid;
        const at = grid ? grid.next(aNow() + 0.02) : aNow() + 0.05;
        G.release = { at };
        A.sfx('auraBurst', { vol: 0.6, pitch: 1.3 });
        ui.pop('RELEASE ON THE BEAT', 'reset', 600);
      }
    }
  }
}

function startAttack(t) {
  const st = stats();
  const g = G.gt;
  P.comboIdx = g < P.comboUntil ? (P.comboIdx + 1) % st.combo : 0;
  const dir = P.comboIdx === 2 ? 'over' : P.comboIdx === 1 ? 'right' : 'left';
  P.act = { name: 'attack', t0: g, dur: st.swing, hitAt: st.hitAt, combo: P.comboIdx, dir, hitDone: false, air: !P.grounded };
  P.comboUntil = g + st.swing + 0.45;
  // soft aim: swing toward the nearest enemy near where you are looking
  const target = aimAssist();
  if (target && P.grounded) P.yaw = yawTo(P.pos, target.pos);
  else P.yaw = P.camYaw;
  A.sfx(P.awakened ? 'swing' : 'swingHeavy', { vol: 0.7, pitch: P.awakened ? 1 + P.comboIdx * 0.08 : 0.8 });
  flowAct(t);
}

function startDash(t) {
  const st = C.PLAYER.awakened;
  const ax = input.axis();
  if (!P.grounded) {
    camForward(P.dashDir);                      // air dash goes where you look, up included
  } else if (ax.x || ax.z) {
    P.dashDir.copy(flatForward(P.camYaw)).multiplyScalar(ax.z).addScaledVector(flatRight(P.camYaw, tmp), ax.x).normalize();
  } else flatForward(P.camYaw, P.dashDir);
  P.dashUntil = G.gt + SK.AIR.dashTime;
  P.iUntil = G.gt + st.iframes;
  P.air.useDash();
  if (!settings.reset && !P.grounded) P.dashCooldownUntil = G.gt + 0.9; // layer off: it just recharges
  if (!P.grounded && !settings.reset) setTimeout(() => { P.air.charges = SK.AIR.charges; }, 900);
  A.sfx('whoosh', { vol: 0.7 });
  G.fx.afterimage(P.actor.root, 0x4fdcff);
  G.fovKick = Math.max(G.fovKick, 7);
  flowAct(t);
}

function aimAssist() {
  let best = null, bestScore = Infinity;
  for (const e of enemies) {
    if (e.dead) continue;
    const d = Math.hypot(e.pos.x - P.pos.x, e.pos.z - P.pos.z) - e.radius;
    if (d > stats().reach + 1.6) continue;
    const off = Math.abs(angDiff(P.camYaw, yawTo(P.pos, e.pos)));
    if (off > 0.55) continue;
    const s = d + off * 3;
    if (s < bestScore) { bestScore = s; best = e; }
  }
  return best;
}

function updatePlayer(dt) {
  const st = stats();
  const g = G.gt;
  // look
  const lk = input.look();
  if (G.cam === 'play' && input.locked && !G.paused) {
    const k = 0.0022 * settings.sens;
    P.camYaw -= lk.dx * k;
    P.camPitch = clamp(P.camPitch - lk.dy * k * (settings.invert ? -1 : 1), -1.35, 1.25);
  }
  // move
  const ax = P.control ? input.axis() : { x: 0, z: 0 };
  const fwd = flatForward(P.camYaw, tmp), right = flatRight(P.camYaw, tmp2);
  const sprint = !P.awakened && input.down('ShiftLeft');
  const speed = P.act && P.act.name === 'attack' && P.grounded ? st.speed * 0.35 : sprint ? st.sprint : st.speed;
  const wx = (fwd.x * ax.z + right.x * ax.x) * speed, wz = (fwd.z * ax.z + right.z * ax.x) * speed;
  if (g < P.dashUntil) {
    P.vel.copy(P.dashDir).multiplyScalar(SK.AIR.dashSpeed);
    if (P.grounded) P.vel.y = 0;
    if (G.rt % 0.05 < dt) G.fx.afterimage(P.actor.root, 0x4fdcff);
  } else {
    const acc = P.grounded ? (P.awakened ? 22 : 12) : 4;
    P.vel.x = damp(P.vel.x, wx, acc, dt);
    P.vel.z = damp(P.vel.z, wz, acc, dt);
    if (!P.grounded && g > P.hangUntil) P.vel.y -= SK.AIR.gravity * dt;
    else if (!P.grounded) P.vel.y = Math.max(P.vel.y, 0);
  }
  P.pos.addScaledVector(P.vel, dt);
  if (P.pos.y <= 0) {
    P.pos.y = 0;
    if (!P.grounded) {
      P.grounded = true; P.air.land(); P.vel.y = 0;
      A.sfx('land', { vol: 0.5 });
      G.fx.burst('dust', P.pos, null, 10);
    }
  } else if (P.grounded && P.pos.y > 0.01) { P.grounded = false; P.air.leave(); }
  // arena and bodies
  const R = G.mode === 'dojo' ? 13 : world.arenaRadius - 1.5;
  const r = Math.hypot(P.pos.x, P.pos.z);
  if (director.phase === 'intro') { P.pos.x = clamp(P.pos.x, -9, 9); P.pos.z = clamp(P.pos.z, -30, 26); }
  else if (r > R) { P.pos.x *= R / r; P.pos.z *= R / r; }
  for (const e of enemies) {
    if (e.dead) continue;
    if (e.kind === 'ogre' && P.pos.y > 3.4) continue;
    const dx = P.pos.x - e.pos.x, dz = P.pos.z - e.pos.z, d = Math.hypot(dx, dz), min = e.radius + 0.4;
    if (d < min && d > 1e-4) { P.pos.x = e.pos.x + dx / d * min; P.pos.z = e.pos.z + dz / d * min; }
  }
  // facing
  if (P.act && P.act.name === 'parry') P.yaw = P.camYaw;
  else if (P.act && P.act.name === 'attack') { /* keeps the yaw it was aimed at */ }
  else if (settings.view === 'first') P.yaw = P.camYaw;
  else if (Math.hypot(P.vel.x, P.vel.z) > 0.5) P.yaw += angDiff(P.yaw, Math.atan2(P.vel.x, P.vel.z)) * Math.min(1, dt * 12);
  // actions
  if (P.act) {
    const k = (g - P.act.t0) / P.act.dur;
    if (P.act.name === 'attack' && !P.act.hitDone && g - P.act.t0 >= P.act.hitAt) { P.act.hitDone = true; resolveSwing(); }
    if (k >= 1) {
      const buf = P.act.buffered;
      P.act = null;
      if (buf != null && pressClock() - buf < 0.4) startAttack(buf);
    }
  }
  // aura regen visuals
  // the aura grows with the meter; in first person it is kept to the screen edges
  if (P.awakened) G.fx.auraBody(P.actor.root, (settings.view === 'first' ? 0.25 : 0.35) + 0.5 * (P.aura / C.AURA.max));
}

function playerPoseState() {
  const g = G.gt;
  const move = P.cineMove != null ? P.cineMove : clamp(Math.hypot(P.vel.x, P.vel.z) / stats().sprint, 0, 1);
  let action = 'idle', t = 0, dir = 'left', combo = 0;
  if (P.act) { action = P.act.name; t = clamp((g - P.act.t0) / P.act.dur, 0, 1); dir = P.act.dir || dir; combo = P.act.combo || 0; }
  if (g < P.dashUntil) { action = 'dash'; t = 1 - (P.dashUntil - g) / SK.AIR.dashTime; }
  return { move, air: !P.grounded, action, t, combo, dir };
}

// Player swing lands: everything in the cone takes it.
function resolveSwing() {
  const st = stats();
  const origin = tmp.copy(P.pos).add(tmp3.set(0, 1.2, 0));
  const aim = P.grounded ? flatForward(P.yaw, tmp2) : camForward(tmp2);
  let hitAny = false;
  const air = !P.grounded;
  for (const e of enemies) {
    if (e.dead) continue;
    const parts = e.kind === 'ogre'
      ? [{ c: V(e.pos.x, 2.3, e.pos.z), r: 1.45, head: false }, { c: V(e.pos.x, 4.15, e.pos.z), r: 0.7, head: true }]
      : [{ c: V(e.pos.x, 0.65, e.pos.z), r: 0.6, head: false }];
    let hit = null;
    for (const p of parts) {
      const d = V().subVectors(p.c, origin);
      const dist = d.length();
      if (dist - p.r > st.reach) continue;
      const ang = dist > 1e-3 ? Math.acos(clamp(d.dot(aim) / dist, -1, 1)) : 0;
      if (ang > st.arc / 2 + Math.atan2(p.r, Math.max(dist, 0.3))) continue;
      if (!hit || p.head) hit = p;
    }
    if (!hit) continue;
    hitAny = true;
    const counter = e.kind === 'ogre' ? aNow() < P.counterUntilA : g_isStunned(e);
    let dmg = st.dmg * (counter ? C.AURA.counterMult : 1) * (hit.head && air ? 2 : 1);
    if (settings.flow && P.awakened) dmg *= 1 + Math.min(flow.level, 20) * 0.04;
    damageEnemy(e, dmg, hit.c, { counter, head: hit.head && air });
  }
  // dojo orbs
  if (G.mode === 'dojo') for (const o of G.dojo.orbs) {
    if (o.lit) continue;
    const d = V().subVectors(o.mesh.position, origin);
    if (d.length() - 0.6 <= st.reach + 0.4 && d.normalize().dot(aim) > 0.35) { o.lit = true; hitAny = true; orbHit(o); }
  }
  if (hitAny) {
    hitstop(P.awakened ? 0.06 : 0.04);
    if (air) airTouch();
  }
  const slashPos = tmp3.copy(P.pos).add(V(0, 1.25, 0)).addScaledVector(aim, 1.1);
  G.fx.slash(slashPos, P.yaw, P.grounded ? 0 : P.camPitch, P.act.dir, P.awakened ? 0x4fdcff : 0xdfe6f0, P.awakened ? 1.3 : 0.9);
}
function g_isStunned(e) { return G.gt < e.stunUntil; }

function airTouch() {
  if (!P.awakened || P.grounded) return;
  const wasReset = P.air.touch();
  P.vel.y = Math.max(P.vel.y, SK.AIR.popOnReset);
  P.hangUntil = G.gt + SK.AIR.hangAfterHit;
  if (wasReset && settings.reset) {
    P.stats.resets++;
    A.sfx('reset', { vol: 0.8, pitch: 1 + Math.min(P.air.chain, 8) * 0.05 });
    ui.pop(P.air.chain > 1 ? `AURA RESET x${P.air.chain}` : 'AURA RESET', 'reset', 700);
    ui.dashFlash();
    G.fx.burst('reset', sock(P.actor, 'chest'), null, 24);
    addScore('reset');
    addAura(8);
  }
}

function damageEnemy(e, dmg, at, { counter = false, head = false, deflect = false, release = false } = {}) {
  if (e.dead) return;
  e.hp -= dmg;
  e.hurtUntil = G.gt + 0.18;
  e.actor.flash && e.actor.flash(0xffffff, 0.12);
  G.fx.burst(P.awakened ? 'ink' : 'dust', at, null, e.kind === 'ogre' ? 18 : 12);
  G.fx.burst('spark', at, null, 8);
  A.sfx(P.awakened ? 'slice' : 'hit', { vol: 0.8, pitch: counter ? 0.85 : 1 });
  G.shake = Math.max(G.shake, e.kind === 'ogre' ? 0.18 : 0.1);
  if (head) ui.pop('HEADSHOT', 'reset', 600);
  if (counter) ui.pop('COUNTER', 'good', 500);
  addAura(C.AURA.perHit);
  addScore('hit', counter ? 2 : 1);
  if (e.kind === 'goblin') {
    tmp3.subVectors(e.pos, P.pos).setY(0).normalize();
    e.knock.copy(tmp3).multiplyScalar(P.awakened ? 9 : 3.5);
    if (!P.awakened) e.stunUntil = Math.max(e.stunUntil, G.gt + 0.35);
    if (e.att && !P.awakened) e.att = null; // a hit interrupts a weak-phase windup
    if (e.hp <= 0) killGoblin(e, deflect || release);
  } else if (e.kind === 'ogre') {
    ui.boss(true, e.hp / e.maxHp);
    if (G.mode === 'dojo') e.hp = Math.max(e.hp, 1); // the sparring partner does not die
  }
}

function killGoblin(e, big = false) {
  e.dead = true; e.deadAt = G.gt; e.att = null;
  A.sfx('goblinDie', { vol: 0.7, pitch: 0.9 + rand() * 0.3 });
  G.fx.burst('ink', V(e.pos.x, 0.7, e.pos.z), null, big ? 40 : 22);
  P.stats.kills++;
  addScore('kill');
  if (big) G.fx.shockwave(e.pos, 2.5, 0x4fdcff);
}

// One-time contextual hints. The game should teach at the moment you need it.
const tipsSeen = (() => { try { return new Set(JSON.parse(localStorage.getItem('aura:tips') || '[]')); } catch { return new Set(); } })();
function tip(key, text, secs = 4) {
  if (tipsSeen.has(key)) return;
  tipsSeen.add(key);
  try { localStorage.setItem('aura:tips', JSON.stringify([...tipsSeen])); } catch {}
  ui.subtitle(text, secs);
}
function hitstop(sec) { G.hitstopUntil = Math.max(G.hitstopUntil, G.rt + sec); }
function flashBW(amount, invertFrames = 0) {
  G.look.gray = Math.max(G.look.gray, amount);
  if (invertFrames) G.look.invertUntil = G.rt + invertFrames / 60;
}

// ------------------------------------------------------------------ incoming hits: register, then grade
// at = press-clock time the hit is perceived. inRange is decided at the moment of impact.
function registerImpact(p) {
  pend.push({ ...p, resolveAt: p.at + WINDOWS.lateGrace + 0.005 });
}

function resolvePending() {
  const now = pressClock();
  for (let i = pend.length - 1; i >= 0; i--) {
    const p = pend[i];
    if (now < p.resolveAt) continue;
    pend.splice(i, 1);
    if (!p.inRange) { if (p.onWhiff) p.onWhiff(); continue; }
    if (p.unparryable) {                       // the stomp: only air or i-frames beat it
      if (G.gt < P.iUntil) ui.pop('DODGE', 'good', 500); else onHurt(p);
      continue;
    }
    const timing = parry.resolve(p.at);
    const pressT = timing.d != null ? p.at + timing.d : null;
    const flick = pressT != null ? SK.readFlick(input.recentMoves(now), pressT) : null;
    const directional = p.dir && (G.mode === 'dojo' ? document.getElementById('dDirectional').checked : settings.directional) && P.awakened;
    let result = SK.judgeParry(timing, flick, p.dir, directional);
    if (G.mode === 'dojo' && (G.dojo.drill === 'parry' || G.dojo.drill === 'deflect')) dojoRecord(timing, result, flick, p.dir);
    if (timing.grade === 'good' && timing.d < -WINDOWS.perfect) tip('early', 'BLOCK = you were early. Wait for the hit itself. PERFECT is within ~75 ms of it.', 4);
    if (result === 'miss' && G.gt < P.iUntil) { ui.pop('DODGE', 'good', 500); continue; }
    if (result === 'perfect' || result === 'flawless') onPerfect(p, result, timing.d);
    else if (result === 'good') onBlock(p, directional && (timing.grade === 'perfect' || timing.grade === 'flawless') && flick !== p.dir);
    else onHurt(p);
  }
}

// Two tiers. PERFECT (22-75 ms): a flash and a stagger. AURA (within 22 ms): the full
// black-and-white freeze, a longer stun, double everything. Only a real hand gets the second.
function onPerfect(p, tier = 'perfect', d = 0) {
  const top = tier === 'flawless';
  P.stats.perfects++; P.stats.chain++; P.stats.maxChain = Math.max(P.stats.maxChain, P.stats.chain);
  if (top) P.stats.flawless = (P.stats.flawless || 0) + 1;
  const contact = sock(P.actor, 'swordTip', V());
  A.sfx('clangPerfect', { vol: top ? 1 : 0.8, pitch: top ? 0.9 : 1.05 });
  if (top) A.sfx('impactFrame', { vol: 0.55 });
  G.fx.burst('spark', contact, null, top ? 60 : 30);
  G.fx.shockwave(V(P.pos.x, 0.05, P.pos.z), top ? 5 : 3, 0x4fdcff);
  hitstop(top ? 0.17 : 0.08);
  if (top || p.bigMoment) flashBW(1, 2); else flashBW(0.5, 0);
  G.shake = Math.max(G.shake, top ? 0.35 : 0.22); G.fovKick = Math.max(G.fovKick, top ? -8 : -4);
  const ms = `${d > 0 ? '+' : ''}${Math.round(d * 1000)} ms`;
  const chain = P.stats.chain >= 3 ? ` x${P.stats.chain}` : '';
  ui.pop(top ? `AURA${chain}` : `PERFECT${chain}`, 'perfect' + (top || p.bigMoment ? ' big' : ''));
  ui.pop(ms, 'points', 700);
  if (P.awakened) {
    addAura((C.AURA.perPerfect * (settings.flow ? flow.mult : 1) * 0.5 + C.AURA.perPerfect * 0.5) * (top ? 2 : 1));
    addScore('perfect', (p.bigMoment ? 3 : 1) * (top ? 2 : 1));
  }
  if (!P.grounded) airTouch();
  const e = p.src;
  if (p.spear) { deflectSpear(p.spear); return; }
  if (!e || e.dead) return;
  if (e.kind === 'goblin') {
    e.stunUntil = G.gt + C.PLAYER[P.awakened ? 'awakened' : 'weak'].parryStun;
    e.att = null;
    e.knock.subVectors(e.pos, P.pos).setY(0).normalize().multiplyScalar(6);
  } else if (e.kind === 'ogre') {
    OG.perfectsInPlan++;
    // Any perfect breaks an ordinary combo; the drop triple has to be parried all the way through.
    const triple = OG.plan && OG.plan.kind === 'triple';
    const last = !OG.plan || !triple || p.impactIndex === OG.plan.impacts.length - 1;
    if (last) {
      const beat = A.music.grid ? A.music.grid.beat : 0.46;
      if (OG.plan && OG.plan.kind === 'triple' && OG.perfectsInPlan >= 3) {
        ui.pop('UNTOUCHABLE', 'perfect big', 1400);
        flashBW(1, 3); hitstop(0.2);
        addScore('perfect', 4); addAura(40);
        A.sfx('impactFrame', { vol: 0.9 });
      }
      OG.plan = null;
      OG.staggerUntilA = aNow() + (top ? 4 : 2.5) * beat;
      OG.recoverUntilA = OG.staggerUntilA + beat;   // and he gets his feet back before the next one
      P.counterUntilA = OG.staggerUntilA;
      A.sfx('ogreRoar', { vol: 0.45, pitch: 1.3 });
    }
  }
}

function onBlock(p, wrongWay) {
  P.stats.blocks++; P.stats.chain = 0;
  A.sfx('clang', { vol: 0.8 });
  G.fx.burst('spark', sock(P.actor, 'swordTip', V()), null, 12);
  ui.pop(wrongWay ? 'WRONG WAY — BLOCK' : 'BLOCK', 'good', 600);
  addScore('block');
  if (p.src && !p.spear) {
    tmp3.subVectors(P.pos, p.src.pos).setY(0).normalize();
    P.vel.addScaledVector(tmp3, p.src.kind === 'ogre' ? 9 : 4);
  }
  if (p.spear) breakSpear(p.spear);
  G.shake = Math.max(G.shake, 0.12);
}

function onHurt(p) {
  if (P.invulnerable) {
    ui.pop('HIT', 'miss', 500);
    P.stats.chain = 0; P.stats.hitsTaken++;
    A.sfx('hit', { vol: 0.5 });
    if (p.spear) breakSpear(p.spear);
    return;
  }
  const phase = director.phase === 'weak' ? 'weak' : 'awakened';
  P.hp = C.damagePlayer(P.hp, p.dmg, phase);
  P.stats.hitsTaken++; P.stats.chain = 0;
  if (settings.flow && flow.level > 0) { flow.reset(); A.sfx('flowBreak', { vol: 0.6 }); ui.flow(flow.mult, 0, 'break'); }
  A.sfx('hit', { vol: 1, pitch: 0.8 });
  G.fx.burst('ink', sock(P.actor, 'chest', V()), null, 14);
  G.look.down = Math.max(G.look.down, 0.55);
  G.shake = Math.max(G.shake, 0.35);
  if (p.src && !p.spear) {
    tmp3.subVectors(P.pos, p.src.pos).setY(0).normalize();
    P.vel.addScaledVector(tmp3, p.src.kind === 'ogre' ? 12 : 5);
    if (p.src.kind === 'ogre') { P.vel.y = 5; P.grounded = false; P.air.leave(); }
  }
  if (p.spear) breakSpear(p.spear);
  P.act = { name: 'hurt', t0: G.gt, dur: 0.35 };
}

// ------------------------------------------------------------------ goblins
function goblinUpdate(e, dt) {
  const a = aNow();
  const g = G.gt;
  const ph = director.phase;
  if (e.dead) {
    safePose(e.actor, dt, { action: 'dead', t: clamp((g - e.deadAt) / 0.6, 0, 1), move: 0 });
    e.pos.addScaledVector(e.knock, dt); e.knock.multiplyScalar(Math.exp(-dt * 6));
    e.actor.root.position.copy(e.pos);
    if (g - e.deadAt > 5) e.remove = true;
    return;
  }
  e.pos.addScaledVector(e.knock, dt); e.knock.multiplyScalar(Math.exp(-dt * 7));
  const toP = tmp.subVectors(P.pos, e.pos).setY(0);
  const dist = toP.length();
  const want = yawTo(e.pos, P.pos);
  let action = 'idle', t = 0, move = 0, dir = e.dir;
  const passive = ['downed', 'reveal', 'getup', 'awaken', 'finisher', 'victory', 'dead'].includes(ph) || G.mode === 'menu';

  if (g < e.stunUntil) {
    action = 'stagger'; t = 1 - (e.stunUntil - g) / 1.2;
  } else if (passive) {
    // circle and jeer at the fallen hero
    const keep = 5.5;
    if (dist < keep - 0.5) e.pos.addScaledVector(toP.normalize(), -dt * 1.5);
    e.yaw += angDiff(e.yaw, want) * Math.min(1, dt * 4);
    action = ph === 'victory' || ph === 'finisher' ? 'hurt' : 'idle';
    e.att = null;
  } else if (e.att) {
    const clock = e.att.clock === 'a' ? a - outLat() : g;   // beat-locked goblins animate on the heard clock
    const { start, impact } = e.att;
    if (!e.att.committed) e.yaw += angDiff(e.yaw, want) * Math.min(1, dt * 6);
    if (clock < impact) {
      action = e.thrower ? 'throw' : 'windup'; t = clamp((clock - start) / (impact - start), 0, 1) * (e.thrower ? 0.5 : 1);
      if (impact - clock < 0.12) e.att.committed = true;
    } else if (!e.att.fired) {
      e.att.fired = true;
      if (e.thrower) throwSpear(e);
      else {
        // lunge
        e.knock.copy(flatForward(e.yaw, tmp2)).multiplyScalar(5);
        const inRange = C.inArc(e.pos, e.yaw, P.pos, C.GOBLIN.reach + 0.4, 1.9) && P.pos.y < 1.6;
        const at = e.att.clock === 'a' ? heard(impact) : pressClock() - (g - impact) / Math.max(G.ts, 0.05);
        registerImpact({ at, inRange, src: e, dir: e.att.dir, dmg: C.GOBLIN.dmg, kind: 'melee' });
        A.sfx('goblinAttack', { vol: 0.6, pitch: 0.9 + rand() * 0.2 });
      }
    } else {
      action = e.thrower ? 'throw' : 'attack'; t = clamp((clock - impact) / 0.35, 0, 1) * (e.thrower ? 0.5 : 1) + (e.thrower ? 0.5 : 0);
      if (clock - impact > C.GOBLIN.recover * (e.att.clock === 'a' ? 0.6 : 1)) {
        e.att = null;
        e.cooldownUntil = g + (P.awakened ? 1.4 + rand() * 2 : 1.4 + rand() * 1.8);
      }
    }
    dir = e.att ? e.att.dir : dir;
  } else {
    // approach / circle
    e.yaw += angDiff(e.yaw, want) * Math.min(1, dt * 5);
    const keep = e.thrower ? 11 : 2.6;
    const n = toP.clone().normalize();
    const side = V(-n.z, 0, n.x).multiplyScalar(e.strafe);
    let vx = 0, vz = 0;
    const spd = C.GOBLIN.speed * (P.awakened ? 1.35 : 1);
    if (dist > keep + 0.6) { vx += n.x * spd; vz += n.z * spd; }
    else if (dist < keep - 0.6) { vx -= n.x * spd * 0.7; vz -= n.z * spd * 0.7; }
    vx += side.x * spd * 0.35; vz += side.z * spd * 0.35;
    e.pos.x += vx * dt; e.pos.z += vz * dt;
    move = Math.hypot(vx, vz) / spd;
    if (rand() < dt * 0.3) e.strafe *= -1;
    // attack if allowed
    const attackers = enemies.filter((x) => x.kind === 'goblin' && x.att && !x.dead).length;
    const maxAtt = P.awakened ? (OG && !OG.dead && director.phase === 'awakened' ? 1 : 3) : 2;
    const canReach = e.thrower ? dist < 16 : dist < keep + 0.9;
    // one threat at a time: goblins hold off while the ogre is winding up (fodder, not a second boss)
    const ogreBusy = OG && !OG.dead && OG.plan && ph === 'awakened';
    if (canReach && g > e.cooldownUntil && attackers < maxAtt && ph !== 'intro' && !ogreBusy) {
      const d = rand() < 0.5 ? 'left' : 'right';
      const grid = P.awakened && A.music && A.music.grid;
      if (grid) {
        const imp = grid.next(a + (e.thrower ? 0.5 : 0.48));
        e.att = { clock: 'a', start: a, impact: imp, dir: d };
      } else {
        e.att = { clock: 'g', start: g, impact: g + C.GOBLIN.windup + rand() * 0.2, dir: d };
      }
      if (!e.thrower && rand() < 0.5) A.sfx('goblinGrowl', { vol: 0.35, pitch: 1 + rand() * 0.3 });
      tip('parry', 'RIGHT-CLICK or F = PARRY. Press it right as the club lands, not when it starts swinging.', 5);
    }
  }
  // separation
  for (const o of enemies) {
    if (o === e || o.dead) continue;
    const dx = e.pos.x - o.pos.x, dz = e.pos.z - o.pos.z, d = Math.hypot(dx, dz), min = e.radius + o.radius + 0.1;
    if (d < min && d > 1e-4) { e.pos.x += dx / d * (min - d) * 0.5; e.pos.z += dz / d * (min - d) * 0.5; }
  }
  const pd = Math.hypot(e.pos.x - P.pos.x, e.pos.z - P.pos.z);
  if (pd < e.radius + 0.35 && pd > 1e-4 && P.pos.y < 1) {
    e.pos.x = P.pos.x + (e.pos.x - P.pos.x) / pd * (e.radius + 0.35);
    e.pos.z = P.pos.z + (e.pos.z - P.pos.z) / pd * (e.radius + 0.35);
  }
  const R = G.mode === 'dojo' ? 14 : world.arenaRadius + 4;
  const rr = Math.hypot(e.pos.x, e.pos.z); if (rr > R) { e.pos.x *= R / rr; e.pos.z *= R / rr; }
  if (G.gt < e.hurtUntil && action === 'idle') action = 'hurt';
  e.actor.root.position.copy(e.pos);
  e.actor.root.rotation.y = e.yaw;
  safePose(e.actor, dt, { action, t, move, dir, air: false });
}

// ------------------------------------------------------------------ spears (deflect-aim)
function throwSpear(e) {
  const grid = A.music && A.music.grid;
  const a = aNow();
  const beat = grid ? grid.beat : 0.46;
  const arrive = grid ? grid.next(a + beat * 1.6) : a + 0.9;
  const from = sock(e.actor, 'hand', V());
  const target = P.pos.clone().add(V(0, 1.2, 0)).addScaledVector(P.vel.clone().setY(0), Math.min(0.5, arrive - a) * 0.5);
  const mesh = makeSpearMesh();
  G.scene.add(mesh);
  const s = { mesh, from, target, t0: a, arrive, state: 'fly', vel: V(), src: e, life: 0 };
  spears.push(s);
  A.sfx('throw', { vol: 0.6 });
}

function spearUpdate(s, dt) {
  const a = aNow();
  if (s.state === 'fly') {
    const k = clamp((a - s.t0) / (s.arrive - s.t0), 0, 1.2);
    const p = V().lerpVectors(s.from, s.target, k);
    p.y += Math.sin(Math.min(k, 1) * Math.PI) * 1.2;
    const next = V().lerpVectors(s.from, s.target, k + 0.02); next.y += Math.sin(Math.min(k + 0.02, 1) * Math.PI) * 1.2;
    s.mesh.position.copy(p); s.mesh.lookAt(next);
    if (!s.registered && a >= s.arrive) {
      s.registered = true;
      const near = P.pos.clone().add(V(0, 1.1, 0)).distanceTo(s.target) < 1.6;
      registerImpact({ at: heard(s.arrive), inRange: near, src: s.src, spear: s, dir: null, dmg: 12, kind: 'spear', onWhiff: () => { s.state = 'stuck'; s.life = 0; } });
    }
    if (k >= 1.2 && s.state === 'fly') { s.state = 'stuck'; s.life = 0; }
  } else if (s.state === 'deflect') {
    const step = s.vel.clone().multiplyScalar(dt);
    const o = s.mesh.position.clone();
    const dirN = s.vel.clone().normalize();
    for (const e of enemies) {
      if (e.dead) continue;
      const c = e.kind === 'ogre' ? V(e.pos.x, 2.6, e.pos.z) : V(e.pos.x, 0.7, e.pos.z);
      const r = e.kind === 'ogre' ? 1.6 : 0.7;
      const hitT = SK.raySphere(o, dirN, c, r);
      if (hitT >= 0 && hitT <= step.length()) {
        if (e.kind === 'goblin') { damageEnemy(e, 99, c, { deflect: true }); P.stats.deflectKills++; addScore('deflectKill'); ui.pop('SNIPED', 'reset', 700); A.sfx('deflect', { vol: 0.9, pitch: 0.8 }); }
        else { damageEnemy(e, 4, c, { deflect: true }); addScore('deflect'); }
        s.state = 'gone';
        break;
      }
    }
    s.mesh.position.add(step);
    s.mesh.lookAt(s.mesh.position.clone().add(s.vel));
    G.fx.beam(o, s.mesh.position, 0x4fdcff, 0.06);
    s.life += dt;
    if (s.life > 1.2) s.state = 'gone';
  } else if (s.state === 'stuck') {
    s.life += dt;
    if (s.mesh.position.y > 0.3) s.mesh.position.y -= dt * 8;
    if (s.life > 2) s.state = 'gone';
  } else if (s.state === 'broken') {
    s.state = 'gone';
  }
  if (s.state === 'gone') { s.mesh.removeFromParent(); s.dead = true; }
}

function deflectSpear(s) {
  if (!settings.deflect) { breakSpear(s); return; }
  s.state = 'deflect'; s.life = 0;
  const start = sock(P.actor, 'swordTip', V());
  s.mesh.position.copy(start);
  s.vel = camForward(V()).multiplyScalar(46);
  A.sfx('deflect', { vol: 1 });
  G.fx.burst('deflect', start, null, 20);
}
function breakSpear(s) { G.fx.burst('glass', s.mesh.position, null, 10); s.state = 'broken'; }

// ------------------------------------------------------------------ the ogre

function ogreUpdate(dt) {
  const e = OG; if (!e) return;
  const a = aNow();
  const av = a - outLat();   // the audio clock as HEARD: what the eye must agree with
  const ph = director.phase;
  const grid = A.music && A.music.grid;
  const beat = grid ? grid.beat : 0.46;
  let action = e.action, t = e.actionT, dir = 'over', move = 0;
  if (ph === 'awakened' || (G.mode === 'dojo' && G.dojo.drill === 'parry')) {
    const want = yawTo(e.pos, P.pos);
    const dist = Math.hypot(P.pos.x - e.pos.x, P.pos.z - e.pos.z);
    if (a < e.roarUntilA) { action = 'roar'; t = 1 - (e.roarUntilA - a) / 1.6; }
    else if (a < e.staggerUntilA) { action = 'stagger'; t = 1 - (e.staggerUntilA - a) / (3.5 * beat); }
    else if (e.plan) {
      const pl = e.plan;
      const first = pl.impacts[0], last = pl.impacts[pl.impacts.length - 1];
      if (a < first - beat * 0.5) e.yaw += angDiff(e.yaw, want) * Math.min(1, dt * 3.5); // commits half a beat out
      // impact registration
      pl.impacts.forEach((imp, i) => {
        if (!pl.done[i] && a >= imp) {
          pl.done[i] = true;
          const k = pl.kind === 'triple' ? 'triple' : pl.kind;
          const spec = C.OGRE_ATTACKS[k];
          const heightOk = pl.stomp ? P.pos.y < 0.6 : pl.dirs[i] === 'over' ? P.pos.y < 4.2 : P.pos.y < 2.0;
          const inRange = C.inArc(e.pos, e.yaw, P.pos, spec.reach, spec.arc, 0.4) && heightOk;
          if (pl.stomp && !heightOk && Math.hypot(P.pos.x - e.pos.x, P.pos.z - e.pos.z) < spec.reach) { ui.pop('JUMPED IT', 'reset', 600); addScore('block', 3); }
          registerImpact({ at: heard(imp), inRange, src: e, dir: pl.stomp ? null : pl.dirs[i], unparryable: !!pl.stomp, dmg: spec.dmg, kind: 'ogre', impactIndex: i, bigMoment: pl.kind === 'triple' && i === pl.impacts.length - 1 && e.perfectsInPlan >= 2,
            onWhiff: () => {} });
        }
        // Sounds are SCHEDULED on the audio clock a little ahead, so they land on the beat to the
        // sample instead of on whichever frame happened to notice (up to a frame late, every time).
        if (!pl.swung[i] && a >= imp - 0.35) {
          pl.swung[i] = true;
          A.sfx('ogreSwing', { vol: 0.8, pitch: 0.9 + i * 0.05, at: imp - 0.13 });
          if (pl.dirs[i] === 'over' || pl.stomp) A.sfx('ogreImpact', { vol: 0.9, at: imp });
        }
        // the ground effects wait until the blow is SEEN landing, i.e. as the beat is heard
        if (!pl.shown) pl.shown = [];
        if (!pl.shown[i] && av >= imp) {
          pl.shown[i] = true;
          if (pl.dirs[i] === 'over' || pl.stomp) { G.fx.shockwave(V(e.pos.x, 0.05, e.pos.z).addScaledVector(flatForward(e.yaw, tmp2), 3.2), 3, 0xffffff); G.fx.burst('dust', V(e.pos.x, 0.1, e.pos.z).addScaledVector(flatForward(e.yaw, tmp2), 3.2), null, 26); G.shake = Math.max(G.shake, 0.25); }
        }
      });
      if (pl.stomp) {
        const k = Math.floor((a - pl.start) / beat);
        if (k >= pl.pulses && a < pl.impacts[0]) { pl.pulses = k + 1; G.fx.shockwave(V(e.pos.x, 0.06, e.pos.z), C.OGRE_ATTACKS.stomp.reach, 0xff2a3a); A.sfx('stepHeavy', { vol: 0.8 }); }
      }
      // The strike's contact frame sits partway into its animation (slam 18%, sweep 24%) and the
      // pose eases toward it. Starting the strike AT the beat drew the blade landing ~110 ms late,
      // so everyone parrying what they SAW was late. It starts `lead` early now.
      const strikeDur = beat * C.STRIKE.dur;
      let idx = 0;
      for (let i = 0; i < pl.impacts.length; i++) if (av >= pl.impacts[i] - (i === 0 ? 1e9 : beat * 0.5)) idx = i;
      const tgt = pl.impacts[idx];
      const kindAnim = pl.dirs[idx] === 'over' ? 'slam' : 'sweep';
      const lead = C.strikeLead(kindAnim, beat);   // measured on the rig: tests/swing_sync.mjs
      const windStart = idx === 0 ? pl.start : pl.impacts[idx - 1];
      const strikeAt = tgt - lead;
      if (av < strikeAt) { action = 'windup'; t = clamp((av - windStart) / Math.max(0.05, strikeAt - windStart) * (pl.hold ? 1.6 : 1), 0, 1); }
      else { action = kindAnim; t = clamp((av - strikeAt) / strikeDur, 0, 1); }
      dir = pl.dirs[idx];
      if (a > last + beat * 1.2) { e.plan = null; e.recoverUntilA = a + beat * (G.mode === 'dojo' ? 1 : 1 + Math.floor(rand() * 2)); }
    } else {
      e.yaw += angDiff(e.yaw, want) * Math.min(1, dt * 3);
      const spd = C.BRUTE.speed * (dist > 12 ? 1.8 : 1);
      if (dist > 4.4) { e.pos.addScaledVector(flatForward(e.yaw, tmp2), spd * dt); move = 0.6; action = 'idle'; }
      else action = 'idle';
      t = 0;
      if (grid) planOgre(a, grid, dist);
    }
  } else if (ph === 'finisher' || e.dead) {
    action = 'dead'; t = clamp(director.t / 2.2, 0, 1);
  }
  if (G.gt < e.hurtUntil && action === 'idle') action = 'hurt';
  e.action = action; e.actionT = t;
  e.actor.root.position.copy(e.pos);
  e.actor.root.rotation.y = e.yaw;
  safePose(e.actor, dt, { action, t, move, dir, holding: e.holding && !e.severed, squeeze: e.squeeze, air: false });
}

function planOgre(a, grid, dist) {
  const e = OG;
  const beat = grid.beat;
  const drop = A.music.nextSectionDrop ? A.music.nextSectionDrop(a) : null;
  const tripleWind = C.OGRE_ATTACKS.triple.windup * beat;
  // The song says when the big one comes: the triple is pinned to the next section drop.
  // Checked before recovery on purpose -- it was being missed while he got his breath back.
  if (drop != null && drop !== e.lastDropPlanned && drop - a >= tripleWind && drop - a < tripleWind + 2.5 * beat) {
    e.lastDropPlanned = drop;
    const pl = C.scheduleAttackAt('triple', grid, drop);
    const flip = rand() < 0.5;
    e.plan = { ...pl, dirs: flip ? ['left', 'right', 'over'] : ['right', 'left', 'over'], done: [], swung: [] };
    e.perfectsInPlan = 0;
    A.sfx('ogreRoar', { vol: 0.7 });
    return;
  }
  if (a < e.recoverUntilA) return;
  if (dist > 9 && G.mode !== 'dojo') return;
  e.bag = e.bag || [];
  const kind = C.drawAttack(e.bag, e.lastKind, rand);
  const pl = C.scheduleAttack(kind, grid, a);
  // do not start something that would still be swinging when the drop combo needs to wind up
  // ...including his recovery after it (up to ~3.2 beats): he waits, the calm before the drop
  if (drop != null && drop !== e.lastDropPlanned && pl.impacts[pl.impacts.length - 1] + 3.4 * beat > drop - tripleWind) return;
  // a sweep never comes from the same side as the last blow did
  let side = rand() < 0.5 ? 'left' : 'right';
  if ((kind === 'sweep' || kind === 'quick') && e.lastDir === side) side = side === 'left' ? 'right' : 'left';
  const other = side === 'left' ? 'right' : 'left';
  const dirs = kind === 'slam' || kind === 'delay' || kind === 'stomp' ? ['over'] : kind === 'double' ? [side, other] : [side];
  e.plan = { ...pl, dirs, done: [], swung: [], hold: !!C.OGRE_ATTACKS[kind].hold, stomp: kind === 'stomp', pulses: 0 };
  e.lastKind = kind; e.lastDir = dirs[dirs.length - 1];
  e.perfectsInPlan = 0;
  if (kind === 'stomp') tip('stomp', 'RED RING = STOMP. You cannot parry it. JUMP (SPACE) or dash through it.', 5);
}

// ------------------------------------------------------------------ the sister
function sisterUpdate(dt) {
  const s = SIS; if (!s) return;
  let action = 'idle', t = 0, move = 0;
  const ph = director.phase;
  if (s.state === 'held' && OG) {
    // while 'held' her neck is pinned at (0, neckY, 0) of her own root
    const hand = sock(OG.actor, 'grabHand', V());
    s.yaw = OG.yaw;
    s.pos.copy(hand); s.pos.y -= s.actor.neckY || 0.95;
    action = 'held'; t = (G.rt * 1.7) % 1;
  } else if (s.state === 'fall') {
    s.vel.y -= 22 * dt;
    s.pos.addScaledVector(s.vel, dt);
    if (s.pos.y <= 0) { s.pos.y = 0; s.vel.set(0, 0, 0); s.state = 'sit'; s.st = G.gt; A.sfx('bodyFall', { vol: 0.5, pitch: 1.5 }); }
    action = 'held'; t = 0.5;
  } else if (s.state === 'sit') {
    action = 'sit'; t = clamp((G.gt - s.st) / 0.5, 0, 1);
    if (ph === 'awakened' && G.gt - s.st > 1.5) s.state = 'run';
  } else {
    let target = null;
    if (s.state === 'follow') target = P.pos.clone().add(flatRight(P.yaw, tmp).multiplyScalar(1.1)).addScaledVector(flatForward(P.yaw, tmp2), -1.6);
    if (s.state === 'run' || s.state === 'hide') target = world.spots.sisterHide.clone().add(V(0.6, 0, 1.4));
    if (s.state === 'hug') target = P.pos.clone().addScaledVector(flatForward(P.yaw, tmp2), 0.55);
    if (target) {
      const d = tmp3.subVectors(target, s.pos).setY(0);
      const dist = d.length();
      const spd = s.state === 'follow' ? 4.2 : 5.2;
      if (dist > 0.25) {
        s.pos.addScaledVector(d.normalize(), Math.min(dist, spd * dt));
        s.yaw += angDiff(s.yaw, Math.atan2(d.x, d.z)) * Math.min(1, dt * 8);
        move = Math.min(1, dist) * (s.state === 'follow' ? 0.6 : 1);
      } else {
        if (s.state === 'run') s.state = 'hide';
        if (s.state === 'hug') { s.yaw = yawTo(s.pos, P.pos); action = 'hug'; t = 1; }
        if (s.state === 'hide') { action = 'hide'; t = 1; s.yaw = yawTo(s.pos, P.pos); }
      }
      s.pos.y = 0;
    }
  }
  s.actor.root.position.copy(s.pos);
  s.actor.root.rotation.y = s.yaw;
  safePose(s.actor, dt, { action, t, move, air: false });
}

// ------------------------------------------------------------------ story beats
function onEvent(ev) {
  switch (ev) {
    case 'ambush': {
      ui.subtitle('Goblins — stay behind me!', 2.6);
      A.sfx('goblinGrowl', { vol: 0.8 });
      for (let i = 0; i < 3; i++) {
        const ang = P.yaw + (i - 1) * 0.7;
        spawnGoblin(V(P.pos.x + Math.sin(ang) * 11, 0, P.pos.z + Math.cos(ang) * 11));
      }
      if (SIS) SIS.state = 'run';
      break;
    }
    case 'blindside':
      A.sfx('hit', { vol: 1, pitch: 0.7 });
      ui.pop('!', 'miss', 500);
      break;
    case 'downed': enterDowned(false); break;
    case 'reveal':
      A.sfx('ogreRoar', { vol: 0.55, pitch: 0.85 });
      setTimeout(() => { if (director.phase === 'reveal' || director.phase === 'getup') ui.subtitle('B-big brother...!!', 3); }, 1500);
      break;
    case 'getup':
      ui.prompt('[SPACE]  GET UP');
      break;
    case 'awaken': beginAwakening(); break;
    case 'ring': break;
    case 'silence': onSilence(); break;
    case 'strike': onStrike(); break;
    case 'color': onColor(); break;
    case 'finisher': onFinisher(); break;
    case 'victory': onVictory(); break;
    case 'dead': onDead(); break;
  }
}

function enterDowned(skip) {
  P.control = false; P.act = null;
  P.vel.set(0, 0, 0);
  if (skip) {
    P.pos.set(0, 0, -3); P.yaw = 0; P.camYaw = 0;
    for (let i = 0; i < 3; i++) { const ang = i * 2.1 + 0.5; spawnGoblin(V(Math.sin(ang) * 5, 0, -3 + Math.cos(ang) * 5)); }
  }
  // the ogre arrives while the camera is on the ground; it already has her
  const toHold = V().subVectors(world.spots.ogreHold, P.pos).setY(0);
  if (toHold.length() < 1) toHold.set(0, 0, 1);
  toHold.normalize();
  const og = spawnOgre(P.pos.clone().addScaledVector(toHold, 12));
  const R = world.arenaRadius - 3;
  const rr = Math.hypot(og.pos.x, og.pos.z); if (rr > R) { og.pos.x *= R / rr; og.pos.z *= R / rr; }
  og.yaw = yawTo(og.pos, P.pos); og.holding = true; og.squeeze = 0.3; og.action = 'idle';
  if (!SIS) { SIS = { actor: makeSister(), pos: V(), vel: V(), yaw: 0, state: 'held', st: 0 }; storyScene.add(SIS.actor.root); }
  SIS.state = 'held';
  P.yaw = yawTo(P.pos, og.pos);
  G.cam = 'cine';
  ui.cine('cine'); ui.hud(false);
  if (audioReady) { A.setMuffle(1, 0.25); A.setAmbient(0.3, 1); }
  G.look.down = 1;
  G.heartbeatAt = G.rt + 0.4;
  A.sfx('bodyFall', { vol: 0.8 });
}

function skipToGetup() { director.go('getup'); director.presses = 0; onEvent('getup'); }

function getUpPress() {
  const n = director.getUpPress();
  A.sfx('getUpPulse', { vol: 0.8, pitch: 1 + n * 0.1 });
  ui.promptHit();
  G.look.flash = Math.max(G.look.flash, 0.18 + n * 0.08);
  G.shake = Math.max(G.shake, 0.08 * n);
  if (n >= T.getUpPresses) ui.prompt('');
}

function beginAwakening() {
  ui.prompt('');
  if (!audioReady) return;
  A.setMuffle(0, 0.15);
  A.setAmbient(0, 0.5);
  const plan = A.music.startAwakening();
  director.planAwakening(plan.musicStart, plan.dropAt, plan.beat);
  G.awk = { plan: director.plan, click: null, lastShot: -1, strikePos: null, from: null, graded: false };
  P.hp = C.PLAYER.maxHp;
  G.look.down = 0;
  ui.cine('cine');
}

function onSilence() {
  // the world holds its breath; he is already there
  const aw = G.awk; if (!aw || !OG) return;
  const stub = sock(OG.actor, 'stub', V());
  const toP = V().subVectors(P.pos, OG.pos).setY(0).normalize();
  aw.from = P.pos.clone();
  aw.strikePos = V(stub.x, Math.max(0, stub.y - 1.25), stub.z).addScaledVector(toP, 1.1);
  G.fx.beam(aw.from.clone().setY(1.2), aw.strikePos.clone().setY(aw.strikePos.y + 1.2), 0xffffff, 0.07);
  for (let i = 0; i < 5; i++) { P.pos.lerpVectors(aw.from, aw.strikePos, i / 5); P.actor.root.position.copy(P.pos); G.fx.afterimage(P.actor.root, 0x4fdcff); }
  P.pos.copy(aw.strikePos); P.vel.set(0, 0, 0); P.grounded = false;
  P.yaw = yawTo(P.pos, OG.pos);
  ui.cine('tight');
  ui.ring(false);
}

function onStrike() {
  const aw = G.awk; if (!aw || !OG) return;
  // grade the click against the drop as heard
  const d = aw.click != null ? aw.click - heard(aw.plan.dropAt) : null;
  const g = d == null ? 'miss' : (d >= -WINDOWS.perfect && d <= WINDOWS.lateGrace ? 'perfect' : Math.abs(d) < 0.2 ? 'good' : 'miss');
  aw.grade = g; aw.d = d;
  P.stats.awakenGrade = g;
  // impact frame, then black and white
  G.look.invertUntil = G.rt + 0.05;
  G.look.gray = 1; G.look.holdGray = true;
  const stub = sock(OG.actor, 'stub', V());
  // slash THROUGH: he was in front of the arm at the silence, on the drop he is past it
  const ogLeft = V(Math.cos(OG.yaw), 0, -Math.sin(OG.yaw)), ogFwd = flatForward(OG.yaw, V());
  const before = P.pos.clone();
  const through = stub.clone().addScaledVector(ogLeft, 3.2).addScaledVector(ogFwd, 0.6);
  through.y = Math.max(0.6, stub.y - 1.1);
  for (let i = 1; i <= 3; i++) { P.actor.root.position.lerpVectors(before, through, i / 4); G.fx.afterimage(P.actor.root, 0x4fdcff); }
  P.pos.copy(through); P.yaw = Math.atan2(ogLeft.x, ogLeft.z);
  aw.ogLeft = ogLeft; aw.ogFwd = ogFwd; aw.stubAt = stub.clone();
  G.fx.beam(before.clone().add(V(0, 1.2, 0)), through.clone().add(V(0, 1.2, 0)), 0xffffff, 0.05);
  G.fx.slash(stub, Math.atan2(ogLeft.x, ogLeft.z), -0.2, 'left', 0xffffff, 1.8);
  let arm = null;
  try { arm = OG.actor.sever(); } catch (e) { console.warn('sever failed', e); }
  OG.severed = true; OG.holding = false;
  if (arm) {
    storyScene.attach(arm);
    arm.position.addScaledVector(ogLeft, 0.45);   // the frozen frame has to SHOW the cut: a gap, not a join
    arm.position.y -= 0.15;
    debris.push({ obj: arm, vel: ogLeft.clone().multiplyScalar(3).add(V(0, 3, 0)), spin: V(2.5, 1, 3), floor: 0.4 });
  }
  G.fx.burst('ink', stub, null, 70);
  G.fx.burst('spark', stub, null, 40);
  G.fx.shockwave(V(OG.pos.x, 0.05, OG.pos.z), 10, 0x4fdcff);
  if (SIS) { SIS.pos.addScaledVector(ogLeft, 0.45); SIS.state = 'fall'; SIS.vel.copy(ogLeft).multiplyScalar(2.5).setY(1.5); }
  A.sfx('sever', { vol: 1 }); A.sfx('impactFrame', { vol: 1 });
  // everything nearby is thrown by the aura
  for (const e of enemies) if (e.kind === 'goblin' && !e.dead) { e.knock.subVectors(e.pos, P.pos).setY(0).normalize().multiplyScalar(14); e.stunUntil = G.gt + 2.5; }
  G.shake = 0.5;
  G.strikeRt = G.rt;
  P.awakened = true;
  P.actor.setAura && P.actor.setAura(1);
  P.vm.setAura && P.vm.setAura(1);
}

function onColor() {
  const aw = G.awk;
  G.look.holdGray = false;
  G.look.flash = 0.7;
  G.cam = 'play';
  P.control = true; P.awakened = true; P.cineMove = null;
  P.camYaw = P.yaw; P.camPitch = -0.15;
  ui.cine('off'); ui.hud(true); ui.boss(true, OG ? OG.hp / OG.maxHp : 1);
  ui.titlecard();
  setTimeout(() => tip('beat', 'His swings land ON THE BEAT. Parry on the beat and FLICK the mouse toward the side the cleaver comes from.', 6), 2800);
  setTimeout(() => tip('air', 'SPACE jumps, SHIFT dashes. Your air dash only comes back when you hit something in the air (AURA RESET).', 6), 9500);
  G.awakenTarget = 1;
  G.base = { sat: 1.12, contrast: 1.1, gray: 0, exposure: 1.02, vignette: 0.42, tint: [0.95, 1, 1.06] };
  if (aw && aw.grade === 'perfect') {
    setTimeout(() => ui.pop('PERFECT DROP', 'perfect big', 1400), 700);
    P.score += 1000; P.aura = 50;
  } else if (aw && aw.grade === 'good') { setTimeout(() => ui.pop(`${Math.round(aw.d * 1000)} ms — CLOSE`, 'good', 1100), 700); }
  if (OG) { OG.roarUntilA = aNow() + 1.6; OG.recoverUntilA = aNow() + 2.4; A.sfx('ogreRoar', { vol: 1 }); }
  if (SIS) SIS.state = SIS.state === 'sit' ? 'sit' : 'run';
  // fresh fodder: two melee and a spear thrower
  for (let i = 0; i < 3; i++) { const ang = rand() * Math.PI * 2; spawnGoblin(V(Math.sin(ang) * 18, 0, Math.cos(ang) * 18), { thrower: i === 2 }); }
  P.stats.start = G.rt;
}

function onFinisher() {
  A.music.cut();          // the loudest thing in the game is the silence here
  G.look.gray = 1; G.look.holdGray = true;
  G.tsTarget = 0.15;
  G.cam = 'cine';
  ui.boss(false); ui.cine('cine');
  OG.dead = true;
  A.sfx('impactFrame', { vol: 0.8 });
  hitstop(0.25);
  for (const e of enemies) if (e.kind === 'goblin' && !e.dead) killGoblin(e, true);
  setTimeout(() => {
    if (director.phase !== 'finisher') return;
    A.music.boom();
    A.sfx('ogreImpact', { vol: 1 });
    G.fx.shockwave(V(OG.pos.x, 0.05, OG.pos.z), 14, 0x4fdcff);
    G.fx.burst('dust', OG.pos, null, 60);
    G.shake = 0.6;
    G.look.holdGray = false; G.look.flash = 0.5;
    G.tsTarget = 1;
  }, 1500);
}

function onVictory() {
  G.cam = 'play';
  ui.cine('off');
  P.control = true;
  if (SIS) SIS.state = 'hug';
  A.setAmbient(0.55, 3);
  ui.subtitle('...You came back for me.', 3);
  setTimeout(() => showEnd(true), 3200);
}

function onDead() {
  A.music.stop(0.4);
  G.look.gray = 0.85; G.tsTarget = 0.3;
  P.control = false;
  setTimeout(() => showEnd(false), 1400);
}

function showEnd(won) {
  if (G.endShown) return;
  G.endShown = true;
  input.unlock();
  G.tsTarget = 1;
  const s = P.stats;
  const secs = Math.max(0, G.rt - s.start);
  document.getElementById('endTitle').textContent = won ? 'SHE IS SAFE' : 'YOU FELL';
  const rk = won ? C.rank({ perfects: s.perfects, blocks: s.blocks, hitsTaken: s.hitsTaken, seconds: secs, maxChain: s.maxChain }) : '—';
  document.getElementById('rank').textContent = rk;
  const rows = [
    ['Aura', P.score.toLocaleString()], ['The drop', s.awakenGrade ? s.awakenGrade.toUpperCase() : '—'],
    ['Perfect parries', s.perfects], ['AURA parries (within 22 ms)', s.flawless || 0], ['Best perfect chain', s.maxChain], ['Best flow', `x${(1 + Math.min(s.bestFlow, 20) * SK.FLOW.step).toFixed(1)} (${s.bestFlow})`],
    ['Aura resets', s.resets], ['Sniped with their own spear', s.deflectKills], ['Hits taken', s.hitsTaken], ['Time', `${Math.floor(secs / 60)}:${String(Math.floor(secs % 60)).padStart(2, '0')}`],
  ];
  document.getElementById('endStats').innerHTML = rows.map(([k, v]) => `<span>${k}</span><b>${v}</b>`).join('');
  ui.show('end', true);
}

// ------------------------------------------------------------------ the dojo
const DRILLS = {
  parry: 'The ogre swings on the beat. Parry on the beat, flick toward the side the blade comes from. Every big section drop brings the triple. The strip shows exactly how early or late you were.',
  deflect: 'Spear throwers circle you and throw on the beat; a spear lands two beats later. Perfect-parry it and it flies down your crosshair. Aim at a goblin before it lands.',
  reset: 'Hit the orbs in the air. Every touch refills your air dash (the flip reset). Jump, dash at an orb, hit it, dash again. Touch the ground and the course resets. Chain all 8.',
  flow: 'No enemies. Every action (attack, dash, jump, parry) is graded against the beat. Keep your flow climbing. Movement is free: move while you keep time.',
};
function setDrill(name) {
  G.dojo.drill = name;
  document.querySelectorAll('#dojoPanel .drills button').forEach((b) => b.classList.toggle('on', b.dataset.drill === name));
  document.getElementById('drillDesc').textContent = DRILLS[name];
  // rebuild the room for the drill
  for (const e of enemies) e.actor.root.removeFromParent();
  enemies = []; OG = null;
  for (const s of spears) s.mesh.removeFromParent(); spears = [];
  for (const o of G.dojo.orbs) o.mesh.removeFromParent(); G.dojo.orbs = [];
  pend = [];
  P.pos.copy(dojo.spots.player); P.vel.set(0, 0, 0); P.yaw = P.camYaw = 0;
  if (name === 'parry') { spawnOgre(dojo.spots.target); OG.yaw = Math.PI; OG.recoverUntilA = aNow() + 1.5; }
  if (name === 'deflect') {
    for (let i = 0; i < 3; i++) { const ang = (i - 1) * 0.9; spawnGoblin(V(Math.sin(ang) * 11, 0, Math.cos(ang) * 11), { thrower: true }); }
  }
  if (name === 'reset') buildOrbs();
  dojoStatsReset();
  startDojoMusic();
}
function startDojoMusic() {
  if (!audioReady) return;
  const bpm = +document.getElementById('dBpm').value;
  G.dojo.bpm = bpm;
  A.music.startPractice(bpm);
  const phonk = document.getElementById('dMusic').checked;
  A.setVolumes({ music: phonk ? settings.music : 0 });
  A.music.setMetronome(!phonk || G.dojo.drill === 'flow');
}
function buildOrbs() {
  const geo = new THREE.IcosahedronGeometry(0.45, 1);
  for (let i = 0; i < 8; i++) {
    const ang = i / 8 * Math.PI * 2;
    const mat = new THREE.MeshBasicMaterial({ color: 0x4fdcff, transparent: true, opacity: 0.9 });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(Math.sin(ang) * 6.5, 3.2 + (i % 3) * 1.3, Math.cos(ang) * 6.5 + 1);
    G.scene.add(mesh);
    G.dojo.orbs.push({ mesh, lit: false, base: mesh.position.clone() });
  }
}
function orbHit(o) {
  o.mesh.material.color.set(0xffffff); o.mesh.material.opacity = 0.35;
  A.sfx('clangPerfect', { vol: 0.5, pitch: 1.3 });
  G.fx.burst('reset', o.mesh.position, null, 20);
  const lit = G.dojo.orbs.filter((x) => x.lit).length;
  const st = G.dojo.stats;
  st.orbChain = lit; st.bestOrb = Math.max(st.bestOrb, lit);
  if (lit === G.dojo.orbs.length) { ui.pop('COURSE CLEAR', 'perfect big', 1400); addScore('release'); }
}
function resetOrbs() { for (const o of G.dojo.orbs) { o.lit = false; o.mesh.material.color.set(0x4fdcff); o.mesh.material.opacity = 0.9; } if (G.dojo.stats) G.dojo.stats.orbChain = 0; }
function dojoStatsReset() {
  G.dojo.stats = { n: 0, perfect: 0, good: 0, miss: 0, sumAbs: 0, nTimed: 0, streak: 0, best: 0, wrongWay: 0, orbChain: 0, bestOrb: 0 };
  ui.timingClear();
  P.score = 0; flow.reset(); flow.best = 0;
}
function dojoRecord(timing, result, flick, dir) {
  const st = G.dojo.stats;
  st.n++;
  if (result === 'perfect' || result === 'flawless') { st.perfect++; if (result === 'flawless') st.aura = (st.aura || 0) + 1; st.streak++; st.best = Math.max(st.best, st.streak); }
  else { st.streak = 0; if (result === 'good') st.good++; else st.miss++; }
  if ((timing.grade === 'perfect' || timing.grade === 'flawless') && result === 'good') st.wrongWay++;
  if (timing.d != null) { st.sumAbs += Math.abs(timing.d) * 1000; st.nTimed++; }
  ui.timingTick(timing.d != null ? timing.d * 1000 : null, result === 'flawless' ? 'perfect' : result);
}
function dojoHud() {
  const st = G.dojo.stats; if (!st) return;
  const d = G.dojo.drill;
  if (d === 'parry' || d === 'deflect') {
    const pct = st.n ? Math.round(100 * st.perfect / st.n) : 0;
    const avg = st.nTimed ? Math.round(st.sumAbs / st.nTimed) : 0;
    ui.dojoStats(`PERFECT ${pct}% &middot; ${st.perfect}/${st.n} &middot; AURA ${st.aura || 0} &middot; AVG ${avg} ms &middot; STREAK ${st.streak} (BEST ${st.best})${st.wrongWay ? ` &middot; WRONG WAY ${st.wrongWay}` : ''}`);
  } else if (d === 'reset') {
    ui.dojoStats(`ORBS ${st.orbChain}/8 &middot; BEST ${st.bestOrb} &middot; AIR CHAIN ${P.air.chain} (BEST ${P.air.bestChain})`);
  } else {
    ui.dojoStats(`FLOW ${flow.level} &middot; BEST ${flow.best} &middot; x${flow.mult.toFixed(1)}`);
  }
}
function dojoUpdate(dt) {
  if (G.dojo.drill === 'reset') {
    for (const o of G.dojo.orbs) { o.mesh.rotation.y += dt; o.mesh.position.y = o.base.y + Math.sin(G.rt * 2 + o.base.x) * 0.15; }
    if (P.grounded && G.dojo.orbs.some((o) => o.lit)) resetOrbs();
  }
  if (G.dojo.drill === 'deflect') {
    const live = enemies.filter((e) => !e.dead && e.kind === 'goblin').length;
    if (live < 3 && rand() < dt * 0.8) { const ang = rand() * Math.PI * 2; spawnGoblin(V(Math.sin(ang) * 12, 0, Math.cos(ang) * 12), { thrower: true }); }
  }
  // dojo ring (training wheels): over the ogre's next impact
  const showRing = document.getElementById('dRing').checked;
  let ringed = false;
  if (showRing && OG && OG.plan) {
    const a = aNow();
    const next = OG.plan.impacts.find((t) => t > a - 0.02);
    if (next != null) {
      const span = OG.plan.impacts[0] - OG.plan.start;
      const k = clamp(1 - (next - a) / Math.max(span, 0.3), 0, 1);
      const p = sock(OG.actor, 'cleaverTip', V()).project(camera);
      if (p.z < 1) { ui.ring(true, (p.x * 0.5 + 0.5) * innerWidth, (-p.y * 0.5 + 0.5) * innerHeight, k); ringed = true; }
    }
  }
  if (!ringed && director.phase === 'dojo') ui.ring(false);
  dojoHud();
}

// ------------------------------------------------------------------ camera
const camPos = V(), camLook = V();
function updateCamera(dt) {
  const ph = director.phase;
  let fov = settings.fov + G.fovKick;
  const first = settings.view === 'first';
  let showBody = true, showVm = false;
  if (G.mode === 'menu') {
    const t = G.rt * 0.05;
    camPos.set(Math.sin(t) * 16, 3.2, -6 + Math.cos(t) * 10);
    camLook.set(0, 2.2, 8);
    camera.position.copy(camPos); camera.lookAt(camLook);
    camera.fov = 55; showBody = false;
  } else if (G.cam === 'cine') {
    showBody = !['downed', 'reveal', 'getup'].includes(ph);
    fov = cineCamera(dt);
    ui.lockHint(false);
  } else {
    const eye = V(P.pos.x, P.pos.y + 1.62, P.pos.z);
    const f = camForward(V());
    if (first) {
      camera.position.copy(eye);
      camera.lookAt(eye.clone().add(f));
      showBody = false; showVm = true;
    } else {
      const pivot = V(P.pos.x, P.pos.y + 1.55, P.pos.z);
      const right = flatRight(P.camYaw, V());
      const dist = P.awakened ? 4.4 : 3.9;
      const want = pivot.clone().addScaledVector(f, -dist).addScaledVector(right, 0.6).add(V(0, 0.25, 0));
      if (want.y < 0.35) want.y = 0.35;
      camera.position.copy(want);
      camera.lookAt(pivot.clone().addScaledVector(f, 6).addScaledVector(right, 0.6));
    }
  }
  if (G.shake > 0) {
    const s = G.shake * G.shake;
    camera.position.x += (Math.random() - 0.5) * s * 0.8;
    camera.position.y += (Math.random() - 0.5) * s * 0.8;
    camera.rotation.z += (Math.random() - 0.5) * s * 0.08;
    G.shake = Math.max(0, G.shake - dt * 1.6);
  }
  G.fovKick = damp(G.fovKick, 0, 6, dt);
  camera.fov = damp(camera.fov, fov, 14, dt);
  camera.updateProjectionMatrix();
  if (P.actor) P.actor.root.visible = showBody && G.mode !== 'menu';
  if (P.vm) P.vm.group.visible = showVm;
  ui.crosshair(G.mode !== 'menu' && G.cam === 'play' && (first || P.awakened));
  ui.view(G.mode === 'menu' ? '' : `[V] ${first ? 'FIRST' : 'THIRD'} PERSON`);
}

// Cinematic shots. Returns the fov to use.
function cineCamera(dt) {
  const ph = director.phase;
  const eyeGround = V(P.pos.x, 0.32, P.pos.z);
  if (ph === 'downed') {
    // on your back, ears ringing, sky spinning
    const k = clamp(director.t / T.downed, 0, 1);
    camera.position.copy(eyeGround);
    const up = V(P.pos.x, 9 - k * 3, P.pos.z).addScaledVector(flatForward(P.yaw, tmp2), 3 + k * 4);
    camera.lookAt(up);
    camera.rotation.z += 0.4 - k * 0.2;
    return 70;
  }
  if (ph === 'reveal' || ph === 'getup') {
    const n = ph === 'getup' ? director.presses : 0;
    const k = ph === 'reveal' ? clamp(director.t / T.reveal, 0, 1) : 1;
    const e = k * k * (3 - 2 * k);
    camera.position.set(P.pos.x, 0.32 + n * 0.3, P.pos.z);
    const target = OG ? sock(OG.actor, 'grabHand', V()).add(V(0, -0.3, 0)) : V(0, 2, 10);
    const sky = V(P.pos.x, 7, P.pos.z).addScaledVector(flatForward(P.yaw, tmp2), 6);
    camera.lookAt(sky.lerp(target, Math.min(1, e * 1.4)));
    camera.rotation.z += 0.2 * (1 - e) - n * 0.02;
    return ph === 'reveal' ? lerp(70, 34, e) : 38 + n * 5;
  }
  if (ph === 'awaken' && G.awk) return awakenCamera(dt);
  if (ph === 'finisher' && OG) {
    const t = director.t;
    const ang = OG.yaw + 1.2 + t * 0.35;
    camera.position.set(OG.pos.x + Math.sin(ang) * 11, 2.2 + t * 0.4, OG.pos.z + Math.cos(ang) * 11);
    camera.lookAt(OG.pos.x, 1.8, OG.pos.z);
    return 42;
  }
  if (ph === 'awakened' && G.awk) return awakenCamera(dt); // B&W tail after the strike
  camera.position.set(P.pos.x, P.pos.y + 3, P.pos.z - 5); camera.lookAt(P.pos);
  return 60;
}

// The Awakening, cut like an edit: one hard cut per beat of the lead-in.
function awakenCamera(dt) {
  const aw = G.awk, pl = aw.plan;
  const a = aNow();
  const b = (a - pl.musicStart) / pl.beat;   // beat index into the lead-in
  const shot = a >= pl.dropAt ? 'strike' : a >= pl.silenceAt ? 'blink' : b < 1 ? 'eye' : b < 2 ? 'vein' : b < 3 ? 'grip' : b < 4 ? 'hero' : 'run';
  if (shot !== aw.shot) {
    aw.shot = shot;
    // an edit cut: one frame of black and white on the beat
    if (['vein', 'grip', 'hero', 'run'].includes(shot)) G.look.cutFlash = G.rt + 0.045;
    if (shot === 'hero') { A.sfx('auraBurst', { vol: 1 }); G.fx.shockwave(V(P.pos.x, 0.05, P.pos.z), 6, 0x4fdcff); G.fx.burst('aura', sock(P.actor, 'chest', V()), null, 60); for (const e of enemies) if (e.kind === 'goblin' && !e.dead) { e.knock.subVectors(e.pos, P.pos).setY(0).normalize().multiplyScalar(10); e.stunUntil = G.gt + 3; } }
    if (shot === 'run') ui.cine('cine');
  }
  const face = flatForward(P.yaw, V()), right = flatRight(P.yaw, V());
  if (shot === 'eye') {
    const eyes = sock(P.actor, 'eyes', V());
    camera.position.copy(eyes).addScaledVector(face, 0.22).add(V(0, 0.01, 0));
    camera.lookAt(eyes);
    return 24;
  }
  if (shot === 'vein') {
    const arm = sock(P.actor, 'forearmR', V());
    camera.position.copy(arm).addScaledVector(right, -0.45).addScaledVector(face, 0.15).add(V(0, 0.12, 0));
    camera.lookAt(arm);
    return 34;
  }
  if (shot === 'grip') {
    const hand = sock(P.actor, 'hand', V());
    camera.position.copy(hand).addScaledVector(face, 0.4).addScaledVector(right, 0.1).add(V(0, -0.12, 0));
    camera.lookAt(hand);
    return 32;
  }
  if (shot === 'hero') {
    camera.position.copy(P.pos).addScaledVector(face, 2.4).addScaledVector(right, 1.3).add(V(0, 0.35, 0));
    camera.lookAt(P.pos.x, 1.5, P.pos.z);
    return 55;
  }
  if (shot === 'run') {
    if (settings.view === 'first') {
      const eye = V(P.pos.x, 1.6, P.pos.z);
      camera.position.copy(eye);
      camera.lookAt(OG ? sock(OG.actor, 'grabHand', V()) : eye.clone().add(face));
      P.actor.root.visible = false;
    } else {
      camera.position.copy(P.pos).addScaledVector(face, -3.4).addScaledVector(right, 0.8).add(V(0, 1.3, 0));
      camera.lookAt(OG ? sock(OG.actor, 'grabHand', V()) : P.pos);
    }
    return 92;
  }
  // strike: low, from the ogre's left-front, looking UP so ogre / arm / hero sit on the sky
  if (shot === 'strike' && aw.stubAt) {
    const t = G.rt - (G.strikeRt || G.rt);
    const c = aw.stubAt.clone().addScaledVector(aw.ogFwd, 8.5 - t * 0.6).addScaledVector(aw.ogLeft, 4.2);
    camera.position.set(c.x, 0.75 + t * 0.15, c.z);
    camera.lookAt(aw.stubAt.x + aw.ogLeft.x * 1.4, 2.7, aw.stubAt.z + aw.ogLeft.z * 1.4);
    return 52;
  }
  // blink: the side angle
  const stub = OG ? sock(OG.actor, 'stub', V()) : P.pos.clone();
  const side = V().subVectors(stub, P.pos).setY(0).normalize();
  const perp = V(-side.z, 0, side.x);
  const orbit = shot === 'strike' ? (G.rt - (G.strikeRt || G.rt)) * 0.12 : 0;
  const mid = V().lerpVectors(P.pos.clone().add(V(0, 1.3, 0)), stub, 0.5);
  camera.position.copy(mid).addScaledVector(perp, 6.5 * Math.cos(orbit)).addScaledVector(side, 6.5 * Math.sin(orbit) - 1).add(V(0, -0.4, 0));
  camera.lookAt(mid);
  return 46;
}

// ------------------------------------------------------------------ per-frame phases
function storyUpdate(dt, rdt) {
  const ph = director.phase;
  // heartbeat while down
  if (['downed', 'reveal', 'getup'].includes(ph) && G.rt >= G.heartbeatAt) {
    A.sfx('heartbeat', { vol: 0.9 });
    G.heartbeatAt = G.rt + (ph === 'getup' ? 0.7 - director.presses * 0.12 : 0.95);
    G.look.downPulse = 1;
  }
  if (OG && ph === 'reveal') {
    OG.squeeze = clamp(director.t / T.reveal, 0, 1) * 0.75 + 0.2;
    if (director.t < 2.4) { OG.action = 'raise'; OG.actionT = clamp(director.t / 1.2, 0, 1); }
    else { OG.action = 'chop'; OG.actionT = (0.5 - 0.5 * Math.cos((director.t - 2.4) * 2.4)) * 0.3; }
  }
  if (OG && ph === 'getup') {
    // he is not just holding it up: he brings the blade to her, slowly, and draws it back
    OG.squeeze = 0.95; OG.action = 'chop';
    const swing = 0.5 - 0.5 * Math.cos(G.rt * 2.4);
    OG.actionT = 0.05 + swing * Math.min(0.5, 0.3 + director.t * 0.02);
    if (swing > 0.9 && !OG.menaceSwung) { OG.menaceSwung = true; A.sfx('ogreSwing', { vol: 0.35, pitch: 0.8 }); }
    if (swing < 0.3) OG.menaceSwung = false;
  }
  if (ph === 'awaken' && G.awk) awakenUpdate(dt, rdt);
  // the weak phase keeps three goblins on you
  if (ph === 'weak') {
    const live = enemies.filter((e) => e.kind === 'goblin' && !e.dead).length;
    if (live < C.WEAK.goblinsAlive && rand() < rdt * 0.6) {
      const ang = rand() * Math.PI * 2;
      spawnGoblin(V(P.pos.x + Math.sin(ang) * 14, 0, P.pos.z + Math.cos(ang) * 14));
    }
  }
  // awakened: fodder keeps coming, more at each section drop
  if (ph === 'awakened') {
    const live = enemies.filter((e) => e.kind === 'goblin' && !e.dead).length;
    if (live < 3 && rand() < rdt * 0.35) {
      const ang = rand() * Math.PI * 2;
      spawnGoblin(V(Math.sin(ang) * 20, 0, Math.cos(ang) * 20), { thrower: rand() < 0.35 });
    }
  }
  // severed arm and anything else falling
  for (const d of debris) {
    if (d.rest) continue;
    d.vel.y -= 20 * dt;
    d.obj.position.addScaledVector(d.vel, dt);
    d.obj.rotation.x += d.spin.x * dt; d.obj.rotation.y += d.spin.y * dt; d.obj.rotation.z += d.spin.z * dt;
    if (d.obj.position.y < d.floor) { d.obj.position.y = d.floor; d.vel.multiplyScalar(0.3); d.vel.y = Math.abs(d.vel.y) * 0.2; d.spin.multiplyScalar(0.4); if (d.vel.length() < 0.5) d.rest = true; A.sfx('bodyFall', { vol: 0.6, pitch: 0.7 }); G.fx.burst('dust', d.obj.position, null, 14); }
  }
}

function awakenUpdate(dt, rdt) {
  const aw = G.awk, pl = aw.plan;
  const a = aNow();
  const lead = clamp((a - pl.musicStart) / (pl.dropAt - pl.musicStart), 0, 1);
  P.actor.setAura && P.actor.setAura(clamp(lead * 1.6, 0, 1));
  const b = (a - pl.musicStart) / pl.beat;
  // time: normal for the close-ups, slow for the run, near-frozen in the silence
  if (a < pl.silenceAt) G.tsTarget = b < 4 ? 1 : 0.32;
  else if (a < pl.dropAt) G.tsTarget = 0.04;
  else G.tsTarget = (G.rt - (G.strikeRt || G.rt)) < 0.35 ? 0.02 : 0.25;
  // stand up, then run at him
  if (b >= 3 && a < pl.silenceAt && OG) {
    const to = V().subVectors(OG.pos, P.pos).setY(0);
    const d = to.length();
    P.yaw = Math.atan2(to.x, to.z);
    if (b >= 4 && d > 3) { P.pos.addScaledVector(to.normalize(), Math.min(d - 3, 10 * dt)); P.cineMove = 1; } else P.cineMove = 0;
    G.look.speed = b >= 4 ? 1 : 0;
  }
  if (a >= pl.silenceAt) { G.look.speed = a < pl.dropAt ? 1.4 : 0; P.cineMove = 0; }
  // the ogre brings the cleaver down on her across the last two beats
  if (OG) {
    if (a < pl.silenceAt) { OG.action = 'raise'; OG.actionT = 1; OG.squeeze = 1; }
    else { OG.action = 'chop'; OG.actionT = clamp((a - pl.silenceAt) / (pl.beat * 2.6), 0, 1); }
  }
  // the strike ring: collapses onto the drop
  if (a >= pl.ringAt && a < pl.dropAt && OG) {
    const p = sock(OG.actor, 'stub', V()).project(camera);
    const k = clamp((a - pl.ringAt) / (pl.dropAt - pl.ringAt), 0, 1);
    ui.ring(p.z < 1, (p.x * 0.5 + 0.5) * innerWidth, (-p.y * 0.5 + 0.5) * innerHeight, k);
  } else ui.ring(false);
  // player pose during the cinematic
  P.control = false;
  if (b < 3) { P.act = { name: 'getup', t0: G.gt, dur: 1, dir: 'over' }; P.act.t0 = G.gt - clamp(b / 3, 0, 1); }
  else if (a < pl.silenceAt) P.act = null;
  else if (a >= pl.dropAt && !aw.slashed) { aw.slashed = true; P.act = { name: 'attack', t0: G.gt, dur: 0.6, hitAt: 99, combo: 2, dir: 'over', hitDone: true }; }
}

// ------------------------------------------------------------------ post look
function updateLook(dt, rdt) {
  const L = G.look, B = G.base;
  const pu = post.u;
  G.awaken = damp(G.awaken, G.awakenTarget, 1.2, rdt);
  if (!L.holdGray) L.gray = Math.max(0, L.gray - rdt * 3.2);
  if (L.cutFlash && G.rt < L.cutFlash) { pu.gray = 1; }
  else pu.gray = clamp(B.gray + L.gray, 0, 1);
  pu.invert = L.invertUntil && G.rt < L.invertUntil ? 1 : 0;
  L.flash = Math.max(0, L.flash - rdt * 2.2);
  pu.flash = L.flash;
  L.down = director.phase === 'downed' || director.phase === 'reveal' || director.phase === 'getup' ? Math.max(0.6 - (director.presses || 0) * 0.12, 0.25) + (L.downPulse || 0) * 0.35 : Math.max(0, L.down - rdt * 1.6);
  L.downPulse = Math.max(0, (L.downPulse || 0) - rdt * 2.5);
  pu.down = L.down;
  L.speed = damp(L.speed, 0, 1.5, rdt) + (director.phase === 'awaken' ? 0 : 0);
  pu.speed = Math.max(L.speed, G.gt < P.dashUntil ? 0.7 : 0);
  pu.aberration = clamp(pu.speed * 0.8 + (pu.gray > 0.9 ? 0.4 : 0), 0, 1.5);
  pu.sat = B.sat;
  pu.contrast = B.contrast + (pu.gray > 0.5 ? 0.6 : 0);
  pu.exposure = B.exposure * (pu.gray > 0.6 ? 1 + 1.2 * (pu.gray - 0.6) / 0.4 : 1);  // B&W of a night scene is black without this
  pu.vignette = B.vignette + (G.cam === 'cine' ? 0.25 : 0);
  pu.tint = B.tint;
  pu.time = G.rt;
}

// ------------------------------------------------------------------ main loop
let last = performance.now();
function tick(rdt) {
  G.rt += rdt;
  // time scale: hit-stop beats everything, then the story's target
  G.ts = damp(G.ts, G.tsTarget, 18, rdt);
  let ts = G.rt < G.hitstopUntil ? 0.04 : G.ts;
  if (G.paused) ts = 0;
  const dt = rdt * ts;
  G.gt += dt;
  if (audioReady) A.setSlowmo(clamp(ts, 0.15, 1));

  if (!G.paused && G.mode !== 'menu') {
    handleActions();
    // director
    if (G.mode === 'story') {
      const evs = director.update(director.phase === 'finisher' ? rdt : dt, { hp: P.hp, playerZ: P.pos.z, audioNow: aNow(), ogreHp: OG ? OG.hp : 1 });
      for (const ev of evs) onEvent(ev);
      storyUpdate(dt, rdt);
    }
    if (director.phase !== 'awaken' && (P.control || director.phase === 'finisher' || director.phase === 'victory')) updatePlayer(dt);
    else { input.look(); }
    resolvePending();
    for (const e of enemies) if (e.kind === 'goblin') goblinUpdate(e, dt);
    ogreUpdate(dt);
    enemies = enemies.filter((e) => { if (e.remove) { e.actor.root.removeFromParent(); return false; } return true; });
    for (const s of spears) spearUpdate(s, dt);
    spears = spears.filter((s) => !s.dead);
    sisterUpdate(dt);
    if (G.mode === 'dojo') dojoUpdate(dt);
    // aura release lands on the beat
    if (G.release && aNow() >= G.release.at) { doRelease(); G.release = null; }
    // player visuals
    P.actor.root.position.copy(P.pos);
    P.actor.root.rotation.y = P.yaw;
    const ps = P.act && P.act.name === 'getup' ? { action: 'getup', t: clamp((G.gt - P.act.t0) / P.act.dur, 0, 1), move: 0, air: false }
      : ['downed', 'reveal'].includes(director.phase) ? { action: 'down', t: 1, move: 0, air: false }
      : director.phase === 'getup' ? { action: 'getup', t: director.presses / T.getUpPresses * 0.8, move: 0, air: false }
      : playerPoseState();
    if (director.phase === 'victory' && SIS && SIS.state === 'hug') ps.action = ps.move > 0.1 ? ps.action : 'idle';
    safePose(P.actor, dt, ps);
    if (P.vm) safePose(P.vm, dt, ps);
    // hud
    const grid = A.music && A.music.grid;
    ui.bars(P.hp, C.PLAYER.maxHp, P.aura, C.AURA.max);
    ui.dash(P.air.canDash(), P.awakened);
    ui.score(P.score, rdt);
    ui.flowVisible(P.awakened && settings.flow);
    ui.flow(flow.mult, flow.level, null);
    ui.beat(grid ? grid.pulse(aNow() - outLat()) : 0, !!grid && settings.beatHud && P.awakened && G.cam === 'play');
    if (OG && director.phase === 'awakened') ui.boss(true, OG.hp / OG.maxHp);
    ui.set('awk', P.awakened, (v) => {
      document.getElementById('scoreNum').style.visibility = v ? 'visible' : 'hidden';
      document.getElementById('aura').parentElement.style.visibility = v ? 'visible' : 'hidden';
    });
  }
  // world + fx
  if (sun && G.scene === storyScene) sun.target.position.copy(P.pos);   // world.update moves the sun to target+offset
  if (G.mode === 'dojo') dojo.update(dt, G.gt);
  else world.update(dt, G.gt, G.awaken);
  G.fx.update(dt);
  updateCamera(rdt);
  updateLook(dt, rdt);
}

function doRelease() {
  P.aura = 0;
  flashBW(1, 3); hitstop(0.18);
  A.sfx('impactFrame', { vol: 1 }); A.sfx('auraBurst', { vol: 1 });
  G.fx.shockwave(V(P.pos.x, 0.05, P.pos.z), 10, 0x4fdcff);
  G.fx.slash(V(P.pos.x, 1.2, P.pos.z), P.yaw, 0, 'left', 0xffffff, 4);
  G.shake = 0.5;
  addScore('release');
  ui.pop('AURA RELEASE', 'perfect big', 1200);
  for (const e of enemies) {
    if (e.dead) continue;
    const d = Math.hypot(e.pos.x - P.pos.x, e.pos.z - P.pos.z);
    if (d > 9 + e.radius) continue;
    if (e.kind === 'goblin') damageEnemy(e, 99, V(e.pos.x, 0.7, e.pos.z), { release: true });
    else { damageEnemy(e, C.AURA.releaseDmg, V(e.pos.x, 2.6, e.pos.z), { release: true }); OG.plan = null; OG.staggerUntilA = aNow() + 2 * (A.music.grid ? A.music.grid.beat : 0.46); P.counterUntilA = OG.staggerUntilA; }
  }
}

function frame(now) {
  requestAnimationFrame(frame);
  // rAF's timestamp is the frame START and can precede a performance.now() taken at load:
  // an unclamped first dt was negative and froze a warm-up afterimage on screen for good.
  const rdt = clamp((now - last) / 1000, 0, 0.05);
  last = now;
  if (!G.frozen) tick(rdt);
  post.render(G.scene, camera);
}

function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h, false);
  post.setSize(w * renderer.getPixelRatio(), h * renderer.getPixelRatio());
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
addEventListener('resize', resize);

// ------------------------------------------------------------------ menus, settings, audio init
async function ensureAudio() {
  if (audioReady) return;
  await A.init();
  audioReady = true;
  A.setVolumes({ master: settings.master, music: settings.music, sfx: settings.sfx });
  A.setAmbient(0.5, 2);
  await loadTracks();
}

async function loadTracks() {
  const sel = document.getElementById('track');
  let tracks = [{ id: 'code', name: 'Code Phonk (claim-free)' }];
  try { tracks = await A.music.listTracks(); } catch (e) { console.warn('listTracks', e); }
  sel.innerHTML = tracks.map((t) => `<option value="${t.id}">${t.name}</option>`).join('');
  // The owner's own reel is the song until he picks something else himself.
  const reel = tracks.find((t) => t.file === 'Aura Edit (your reel).wav');
  if (reel && !settings.trackChosen) settings.track = reel.id;
  if (!tracks.some((t) => t.id === settings.track)) settings.track = 'code';
  sel.value = settings.track;
  await selectTrack(settings.track);
}

async function selectTrack(id) {
  const info = document.getElementById('trackInfo');
  info.textContent = id === 'code' ? 'Made in code. Safe to stream.' : 'Listening for the drop...';
  try {
    const t = await A.music.select(id);
    settings.track = id; saveSettings();
    if (id === 'code') { ui.show('dropNudge', false); info.textContent = `Made in code. Safe to stream. ${Math.round(t.bpm)} BPM.`; return; }
    info.textContent = `${Math.round(t.bpm)} BPM · drop ${t.detected ? 'found automatically' : 'set by you'} — nudge it if it is off.`;
    document.getElementById('dropNudge').classList.remove('hidden');
    document.getElementById('dropAt').textContent = fmtTime(t.dropAt);
    G.trackDrop = t.dropAt;
  } catch (e) {
    console.warn(e);
    info.textContent = 'Could not read that file. Using code phonk.';
    if (id !== 'code') { document.getElementById('track').value = 'code'; await selectTrack('code'); }
  }
}
const fmtTime = (s) => `${Math.floor(s / 60)}:${(s % 60).toFixed(2).padStart(5, '0')}`;

function bindUI() {
  const on = (id, fn) => document.getElementById(id).addEventListener('click', async () => { await ensureAudio(); A.sfx('uiSelect', { vol: 0.5 }); fn(); });
  on('bPlay', () => startStory(false));
  on('bSkip', () => startStory(true));
  on('bDojo', () => startDojo());
  on('bSettings', () => openSettings('menu'));
  on('bBack', () => closeSettings());
  on('bResume', () => resume());
  on('bPauseSettings', () => openSettings('pause'));
  on('bQuit', () => toMenu());
  on('bAgain', () => startStory(false));
  on('bReplay', () => startStory(true));
  on('bEndDojo', () => startDojo());
  on('bEndQuit', () => toMenu());
  on('bCalibrate', () => calibrate());
  on('dReset', () => dojoStatsReset());
  on('bHearDrop', () => A.music.previewDrop());
  document.getElementById('track').addEventListener('change', async (e) => { await ensureAudio(); settings.trackChosen = true; saveSettings(); selectTrack(e.target.value); });
  document.querySelectorAll('#dropNudge [data-nudge]').forEach((b) => b.addEventListener('click', async () => {
    await ensureAudio();
    const id = settings.track; if (id === 'code') return;
    G.trackDrop = Math.max(0, (G.trackDrop || 0) + +b.dataset.nudge);
    A.music.setDropOverride(id, G.trackDrop);
    document.getElementById('dropAt').textContent = fmtTime(G.trackDrop);
    await A.music.select(id);
  }));
  document.querySelectorAll('#dojoPanel [data-drill]').forEach((b) => b.addEventListener('click', async () => { await ensureAudio(); setDrill(b.dataset.drill); }));
  const bpm = document.getElementById('dBpm');
  bpm.addEventListener('input', () => { document.getElementById('dBpmV').textContent = bpm.value; });
  bpm.addEventListener('change', () => startDojoMusic());
  document.getElementById('dMusic').addEventListener('change', () => startDojoMusic());
  canvas.addEventListener('click', () => { if (G.mode !== 'menu' && !G.paused && !G.endShown && !input.locked) input.lock(); });
  addEventListener('keydown', (e) => {
    if (e.code !== 'Escape' || e.repeat) return;
    if (G.mode === 'menu' || G.endShown) return;
    if (G.inSettings) { closeSettings(); return; }
    // with pointer lock, Esc first releases the lock (which pauses); ignore the same keypress
    if (G.paused) { if (performance.now() - (G.pausedAt || 0) > 300) resume(); }
    else pause();
  });
  input.onLockChange = (locked) => {
    ui.lockHint(false);
    if (!locked && G.mode !== 'menu' && !G.endShown && !G.paused && !G.inSettings) pause();
  };
  // settings sliders
  const bind = (id, key, fmt = (v) => v, apply = () => {}) => {
    const el = document.getElementById(id);
    const out = el.parentElement.querySelector('span');
    const set = () => { if (el.type === 'checkbox') el.checked = !!settings[key]; else el.value = settings[key]; if (out) out.textContent = fmt(settings[key]); };
    set();
    el.addEventListener('input', () => { settings[key] = el.type === 'checkbox' ? el.checked : +el.value; if (out) out.textContent = fmt(settings[key]); apply(); saveSettings(); });
    return set;
  };
  G.settingsRefresh = [
    bind('sSens', 'sens', (v) => (+v).toFixed(2)),
    bind('sFov', 'fov', (v) => v),
    bind('sOffset', 'offset', (v) => `${v > 0 ? '+' : ''}${v}`),
    bind('sMaster', 'master', (v) => Math.round(v * 100), () => audioReady && A.setVolumes({ master: settings.master })),
    bind('sMusic', 'music', (v) => Math.round(v * 100), () => audioReady && A.setVolumes({ music: settings.music })),
    bind('sSfx', 'sfx', (v) => Math.round(v * 100), () => audioReady && A.setVolumes({ sfx: settings.sfx })),
    bind('pMaster', 'master', (v) => Math.round(v * 100), () => audioReady && A.setVolumes({ master: settings.master })),
    bind('pMusic', 'music', (v) => Math.round(v * 100), () => audioReady && A.setVolumes({ music: settings.music })),
    bind('pSfx', 'sfx', (v) => Math.round(v * 100), () => audioReady && A.setVolumes({ sfx: settings.sfx })),
    bind('sInvert', 'invert'), bind('sBeatHud', 'beatHud'),
    bind('lDirectional', 'directional'), bind('lFlow', 'flow'), bind('lReset', 'reset'), bind('lDeflect', 'deflect'),
  ];
  document.getElementById('dDirectional').checked = settings.directional;
}

function openSettings(from) {
  G.inSettings = from;
  G.settingsRefresh.forEach((f) => f());
  ui.show(from === 'menu' ? 'menu' : 'pause', false);
  ui.show('settings', true);
}
function closeSettings() {
  ui.show('settings', false);
  const from = G.inSettings; G.inSettings = null;
  ui.show(from === 'menu' ? 'menu' : 'pause', true);
}
function pause() {
  G.paused = true;
  G.pausedAt = performance.now();
  G.settingsRefresh.forEach((f) => f());
  ui.show('pause', true);
  try { A.ctx && A.ctx.suspend(); } catch {}   // the song stops, so every beat-locked swing stops with it
}
function resume() {
  ui.show('pause', false);
  G.paused = false;
  try { A.ctx && A.ctx.resume(); } catch {}
  input.lock();
}

// Tap SPACE along with 16 clicks; the mean offset is your setup's latency.
async function calibrate() {
  const info = document.getElementById('calibInfo');
  const ctx = A.ctx, out = A.sfxOut || ctx.destination;
  const bpm = 100, beat = 60 / bpm, t0 = ctx.currentTime + 1.0, n = 16;
  const clicks = [];
  for (let i = 0; i < n; i++) {
    const t = t0 + i * beat;
    const o = ctx.createOscillator(), g = ctx.createGain();
    o.frequency.value = i % 4 === 0 ? 1760 : 1320;
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(0.5, t + 0.002); g.gain.exponentialRampToValueAtTime(0.001, t + 0.06);
    o.connect(g).connect(out); o.start(t); o.stop(t + 0.08);
    clicks.push(t);
  }
  info.textContent = 'Tap SPACE on every click...';
  const taps = [];
  const onKey = (e) => { if (e.code === 'Space') { e.preventDefault(); taps.push(ctx.currentTime); } };
  addEventListener('keydown', onKey);
  await new Promise((r) => setTimeout(r, (1.0 + n * beat + 0.4) * 1000));
  removeEventListener('keydown', onKey);
  const offs = [];
  for (const tp of taps) {
    let best = null;
    for (const c of clicks.slice(4)) { const d = tp - (c + outLat()); if (Math.abs(d) < beat * 0.4 && (best == null || Math.abs(d) < Math.abs(best))) best = d; }
    if (best != null) offs.push(best);
  }
  if (offs.length < 6) { info.textContent = `Only ${offs.length} taps landed near a click. Try again.`; return; }
  offs.sort((x, y) => x - y);
  const med = offs[Math.floor(offs.length / 2)];
  const spread = offs[Math.floor(offs.length * 0.85)] - offs[Math.floor(offs.length * 0.15)];
  settings.offset = Math.round(med * 1000);
  saveSettings(); G.settingsRefresh.forEach((f) => f());
  info.textContent = `Offset set to ${settings.offset > 0 ? '+' : ''}${settings.offset} ms (your taps spread ${Math.round(spread * 1000)} ms).`;
}

// ------------------------------------------------------------------ debug hooks
window.AURA = {
  state: () => ({ mode: G.mode, phase: director.phase, hp: P.hp, aura: P.aura, score: P.score, flow: flow.level, ogre: OG && OG.hp, enemies: enemies.length, ts: G.ts, cam: G.cam, view: settings.view, locked: input.locked, pos: P.pos.toArray().map((v) => +v.toFixed(2)), audio: audioReady && A.now }),
  jump: async (phase) => {
    await ensureAudio();
    if (phase === 'dojo') return startDojo();
    if (phase === 'menu') return toMenu();
    startStory(phase !== 'intro' && phase !== 'weak');
    if (phase === 'weak') { director.go('weak'); onEvent('ambush'); }
    if (phase === 'getup') skipToGetup();
    if (phase === 'awaken') { skipToGetup(); for (let i = 0; i < T.getUpPresses; i++) getUpPress(); }
  },
  freeze: (on = true) => { G.frozen = on; },
  step: (sec = 1 / 60) => { const n = Math.max(1, Math.round(sec * 60)); for (let i = 0; i < n; i++) tick(1 / 60); post.render(G.scene, camera); },
  press: (type) => input.queue.push({ type, t: pressClock() }),
  // a parry with a mouse flick toward `dir` ('left' | 'right' | 'over'), as the hand would do it
  flickParry: (dir) => {
    const t = pressClock();
    const d = { left: [-40, 0], right: [40, 0], over: [0, -40] }[dir] || [0, 0];
    input.moves.push({ t: t - 0.01, dx: d[0] / 2, dy: d[1] / 2 }, { t: t + 0.01, dx: d[0] / 2, dy: d[1] / 2 });
    input.queue.push({ type: 'parry', t });
  },
  look: (yaw, pitch) => { if (yaw != null) P.camYaw = yaw; if (pitch != null) P.camPitch = pitch; },
  view: (v) => { settings.view = v; },
  god: (on = true) => { P.invulnerable = on; },
  G, P, director, get OG() { return OG; }, get enemies() { return enemies; }, A, post, camera, settings,
};

buildPlayer();
// The first afterimage/aura call for an object builds its geometry; do it now, not mid-fight.
for (const fx of [fxStory, fxDojo]) { try { fx.afterimage(P.actor.root, 0x4fdcff); fx.auraBody(P.actor.root, 0); } catch (e) { console.warn('fx warm-up', e); } }
bindUI();
resize();
toMenu();
requestAnimationFrame(frame);
