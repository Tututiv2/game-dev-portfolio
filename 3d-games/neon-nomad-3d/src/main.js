import * as THREE from 'three';
import { EffectComposer } from 'three/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/postprocessing/OutputPass.js';

import { WORLD } from './terrain.js';
import { Planet } from './planet.js';
import { Sky } from './sky.js';
import { Crawler } from './crawler.js';
import { CameraRig } from './cameras.js';
import { Dust } from './dust.js';
import { BuildSystem, Resources, STRUCTURES, GRID_LEVEL, BUILD_RANGE } from './build.js';
import { Pollution } from './pollution.js';
import { PollutionMap } from './pollutionmap.js';
import { Hives } from './hives.js';
import { SUN_DIR, PALETTE } from './materials.js';

const canvas = document.getElementById('view');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(62, innerWidth / innerHeight, 0.6, 30000);

// Scene lights exist for the crawler only — the terrain runs its own shader and
// takes its sun as a uniform. Both read from the same SUN_DIR, so they agree.
const sun = new THREE.DirectionalLight(0xdaeaff, 2.6);
sun.position.copy(SUN_DIR).multiplyScalar(1000);
scene.add(sun);
scene.add(new THREE.HemisphereLight(0x4a2f80, 0x0a0d14, 0.85));

const planet = new Planet();
scene.add(planet.group);

const sky = new Sky();
scene.add(sky.mesh);

const crawler = new Crawler(planet);
scene.add(crawler.group);

const dust = new Dust(planet.uniforms);
scene.add(dust.points);

const rig = new CameraRig(camera, planet);
const resources = new Resources(300, 0);
const build = new BuildSystem(planet, crawler, scene, resources);
const pollution = new Pollution();
const pollutionMap = new PollutionMap(pollution);
const hives = new Hives(planet, pollution, scene);
let coreHp = 100;

const composer = new EffectComposer(renderer);
composer.addPass(new RenderPass(scene, camera));
const bloom = new UnrealBloomPass(new THREE.Vector2(innerWidth, innerHeight), 0.58, 0.42, 0.78);
composer.addPass(bloom);
composer.addPass(new OutputPass());

// --- input -------------------------------------------------------------------

const input = { throttle: 0, steer: 0, boost: false };
const keys = Object.create(null);

addEventListener('keydown', (e) => {
  const k = e.key.toLowerCase();
  if (k === ' ') {
    e.preventDefault();
    if (!keys[' ']) toggleDeploy();
  }
  if (k === 'r') respawn();
  if (k === '1') selectStructure('wall');
  if (k === '2') selectStructure('extractor');
  if (k === '3') selectStructure('cannon');
  keys[k] = true;
});
addEventListener('keyup', (e) => { keys[e.key.toLowerCase()] = false; });
addEventListener('blur', () => { for (const k in keys) keys[k] = false; });

let dragging = false;
let lastX = 0, lastY = 0;
const ndc = new THREE.Vector2(0, 0);
canvas.addEventListener('contextmenu', (e) => e.preventDefault());
canvas.addEventListener('pointerdown', (e) => {
  if (e.button === 0) { build.place(); return; }
  if (e.button !== 2 && e.button !== 1) return;
  dragging = true; lastX = e.clientX; lastY = e.clientY;
  canvas.setPointerCapture(e.pointerId);
});
canvas.addEventListener('pointermove', (e) => {
  ndc.x = (e.clientX / innerWidth) * 2 - 1;
  ndc.y = -(e.clientY / innerHeight) * 2 + 1;
  if (!dragging) return;
  rig.onDrag(e.clientX - lastX, e.clientY - lastY);
  lastX = e.clientX; lastY = e.clientY;
});
canvas.addEventListener('pointerup', (e) => {
  dragging = false;
  if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
});
canvas.addEventListener('wheel', (e) => { e.preventDefault(); rig.onWheel(e.deltaY); }, { passive: false });

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
  composer.setSize(innerWidth, innerHeight);
});

function respawn() {
  crawler.pos.set(0, 0, 1).normalize();
  crawler.fwd.set(0, 1, 0);
  crawler.orthonormalise();
  crawler.speed = 0;
  crawler.snapToGround();
  rig.initialised = false;
}

// --- HUD ---------------------------------------------------------------------

const el = {
  mode: document.getElementById('mode'),
  mass: document.getElementById('mass'),
  tech: document.getElementById('tech'),
  speed: document.getElementById('speed'),
  alt: document.getElementById('alt'),
  coord: document.getElementById('coord'),
  fps: document.getElementById('fps'),
  chunks: document.getElementById('chunks'),
  hotbar: document.getElementById('hotbar'),
  threat: document.getElementById('threat'),
  pol: document.getElementById('pol'),
  polfill: document.getElementById('polfill'),
  polr: document.getElementById('polr'),
  nest: document.getElementById('nest'),
  swarm: document.getElementById('swarm'),
  hint: document.getElementById('buildhint'),
  boot: document.getElementById('boot'),
};

const slots = [...document.querySelectorAll('.slot')];
for (const s of slots) s.addEventListener('click', () => selectStructure(s.dataset.type));

function selectStructure(type) {
  build.select(type);
  for (const s of slots) s.classList.toggle('sel', s.dataset.type === type);
}

function setDeployed(deployed) {
  el.mode.textContent = deployed ? 'ANCHORED' : 'MOBILE';
  el.mode.className = deployed ? 'deployed' : '';
  el.hotbar.classList.toggle('on', deployed);
}

function toggleDeploy() {
  const deployed = crawler.toggleDeploy();
  // Packing up hands back half the Mass — the 2D game's rule, and the reason
  // running away is a decision instead of a loss.
  if (!deployed) build.packUp();
  // Driving away does NOT wipe the cloud you made. It stays where you left it,
  // keeps spreading, and the nests keep eating it — you are running from a
  // mess that outlives you leaving.
  setDeployed(deployed);
}

// --- loop --------------------------------------------------------------------

const clock = new THREE.Clock();
const tmpUp = new THREE.Vector3();
const tmpLampDir = new THREE.Vector3();
let elapsed = 0;
let fpsAcc = 0, fpsFrames = 0, hudAcc = 0;
let booted = false;

function frame() {
  requestAnimationFrame(frame);
  // Clamped: a tab-switch must not teleport the crawler across the planet.
  tick(Math.min(clock.getDelta(), 0.05));
}

function tick(dt, render = true) {
  elapsed += dt;

  input.throttle = (keys['w'] || keys['arrowup'] ? 1 : 0) - (keys['s'] || keys['arrowdown'] ? 1 : 0);
  input.steer = (keys['d'] || keys['arrowright'] ? 1 : 0) - (keys['a'] || keys['arrowleft'] ? 1 : 0);
  input.boost = !!(keys['shift']);

  const deployEase = crawler.update(dt, input);
  planet.update(crawler.worldPos);
  rig.update(crawler, deployEase, dt);

  tmpUp.copy(crawler.pos);
  sky.update(tmpUp, elapsed);

  const u = planet.uniforms;
  u.uTime.value = elapsed;
  u.uLampPos.value.copy(crawler.worldPos).addScaledVector(tmpUp, 2.4);
  crawler.lampDir(u.uLampDir.value);
  u.uLampPower.value = 1 - deployEase * 0.45;

  dust.update(dt, crawler);

  // Build grid on the ground, fading in with the deploy animation.
  u.uBuildActive.value = deployEase;
  u.uGridLevel.value = GRID_LEVEL;
  u.uBuildRadius.value = (Math.PI * 0.5 * WORLD.radius / (1 << GRID_LEVEL)) * (BUILD_RANGE + 1.5);
  u.uBuildOrigin.value.copy(crawler.worldPos);

  build.updateCursor(camera, ndc);

  const polSteps = pollution.stepCount;
  pollution.update(dt);
  // Rebuild the GPU window only when the field actually changed, or when the
  // crawler has moved far enough that the window is stale.
  const moved = pollutionMap.origin.distanceToSquared(crawler.worldPos) > 400;
  if (pollution.stepCount !== polSteps || moved) pollutionMap.rebuild(crawler);
  pollutionMap.bindTo(u);

  hives.update(dt, crawler, elapsed, (dmg) => { coreHp = Math.max(0, coreHp - dmg); });
  // After hives, so the cannons aim at this frame's positions rather than last
  // frame's.
  build.update(dt, elapsed, pollution, hives.units);

  crawler.core.rotation.y += dt * 0.9;
  crawler.core.rotation.x += dt * 0.35;
  crawler.ring.rotation.z += dt * 0.25;

  if (render) composer.render();

  // HUD, four times a second — updating text every frame is a real cost.
  fpsAcc += dt; fpsFrames++; hudAcc += dt;
  if (hudAcc > 0.25) {
    const lat = Math.asin(THREE.MathUtils.clamp(crawler.pos.y, -1, 1)) * 180 / Math.PI;
    const lon = Math.atan2(crawler.pos.z, crawler.pos.x) * 180 / Math.PI;
    el.mass.textContent = Math.floor(resources.mass);
    el.tech.textContent = Math.floor(resources.tech);
    for (const s of slots) {
      s.classList.toggle('poor', !resources.canAfford(STRUCTURES[s.dataset.type]));
    }
    const reach = pollution.total > 0 ? pollution.radiusFrom(crawler.pos, 0.6) : 0;
    const nearest = hives.nearestNest(crawler.pos);
    el.pol.textContent = Math.round(pollution.total);
    el.polfill.style.width = Math.min(100, pollution.total / 12) + '%';
    el.polr.textContent = Math.round(reach) + ' M';
    el.nest.textContent = isFinite(nearest) ? Math.round(nearest) + ' M' : '—';
    el.swarm.textContent = hives.stats.live + (build.kills ? ` · ${build.kills} KILLED` : '') + (hives.stats.agitated ? ` · ${hives.stats.agitated} ROUSED` : '');
    el.threat.classList.toggle('hot', pollution.total > 200);
    el.threat.classList.toggle('hunted', hives.stats.live > 0);
    el.hint.textContent = build.reason;
    el.hint.classList.toggle('on', crawler.deployed && !!build.reason && !!build.cursorCell);
    el.speed.textContent = Math.round(Math.abs(crawler.speed) * 3.6) + ' KPH';
    el.alt.textContent = Math.round(planet.heightAt(crawler.pos)) + ' M';
    el.coord.textContent = `${lat >= 0 ? 'N' : 'S'}${Math.abs(lat).toFixed(1)}° ${lon >= 0 ? 'E' : 'W'}${Math.abs(lon).toFixed(1)}°`;
    el.fps.textContent = Math.round(fpsFrames / fpsAcc) + ' FPS';
    el.chunks.textContent = planet.stats.chunks + ' CHUNKS · ' + planet.stats.rocks + ' ROCKS · ' + planet.stats.trees + ' FLORA' + (planet.stats.queued ? ` (+${planet.stats.queued})` : '');
    fpsAcc = 0; fpsFrames = 0; hudAcc = 0;
  }

  if (!booted && elapsed > 0.6) {
    booted = true;
    el.boot.classList.add('gone');
  }
}

selectStructure("wall");
setDeployed(false);
frame();

// Drive frames by hand. requestAnimationFrame is throttled to nothing in a
// background tab, so without this there is no way to render, screenshot or
// test the game except by staring at it — which is not a test.
function step(frames = 1, dt = 1 / 60) {
  for (let i = 0; i < frames; i++) tick(dt);
  return { elapsed, chunks: planet.stats.chunks, queued: planet.stats.queued };
}

// Simulate without drawing. Balance questions need minutes of game time, and
// rendering every one of those frames synchronously just hangs the tab.
function simulate(seconds, dt = 1 / 30) {
  const n = Math.ceil(seconds / dt);
  for (let i = 0; i < n; i++) tick(dt, false);
  return elapsed;
}

// Handy from the console while tuning.
Object.assign(window, { THREE, scene, camera, planet, crawler, rig, bloom, renderer, composer, dust, sky, build, resources, pollution, pollutionMap, hives, toggleDeploy, selectStructure, ndc, WORLD, PALETTE, step, simulate, input, keys });
