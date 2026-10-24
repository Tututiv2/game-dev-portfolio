// THRESHOLD — boot, loop, wiring.

import * as THREE from '../vendor/three/three.module.js';

import { Grid } from './world/grid.js';
import { VolumeGraph } from './world/volumes.js';
import { buildCabin, SPAWN, CABIN } from './world/cabin.js';
import { buildWorldMeshes } from './render/mesh.js';
import { buildOpeningViews } from './render/openings.js';
import { Sky } from './render/sky.js';
import { Props, LAMP_CD } from './render/props.js';
import { Precipitation, RoofMap } from './weather/precip.js';
import { Weather, mulberry32 } from './weather/weather.js';
import { ThunderScheduler } from './weather/thunder.js';
import { Player } from './player.js';
import { AudioEngine } from './audio/engine.js';
import { Hud } from './ui/hud.js';

const app = {};
const NO_LOCK = new URLSearchParams(location.search).has('nolock');

function build() {
  const grid = new Grid();
  const { openings } = buildCabin(grid);
  const graph = new VolumeGraph(grid);
  const weather = new Weather(undefined, 20260816);
  const rnd = mulberry32(4242);
  const thunder = new ThunderScheduler(rnd);

  const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.75));
  renderer.setSize(innerWidth, innerHeight);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.35;
  if ('outputColorSpace' in renderer) renderer.outputColorSpace = THREE.SRGBColorSpace;
  document.body.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(72, innerWidth / innerHeight, 0.06, 900);

  scene.add(buildWorldMeshes(THREE, grid));
  const { group: openingGroup, views, glass } = buildOpeningViews(THREE, grid);
  scene.add(openingGroup);

  const sky = new Sky(THREE, scene);
  const props = new Props(THREE, scene, openings);
  const roofMap = new RoofMap(grid, CABIN.x0 - 20, CABIN.z0 - 20, CABIN.x1 + 20, CABIN.z1 + 20);
  const precip = new Precipitation(THREE, scene, roofMap);

  const player = new Player(grid, SPAWN);
  const audio = new AudioEngine();
  const hud = new Hud();

  Object.assign(app, {
    grid, graph, openings, weather, thunder, rnd,
    renderer, scene, camera, sky, props, precip, views, glass,
    player, audio, hud, time: 0, shake: 0, flash: 0,
  });
  return app;
}

// ------------------------------------------------------------------ input

function bindInput() {
  const { player, audio, hud, weather, thunder, renderer } = app;
  const canvas = renderer.domElement;

  addEventListener('resize', () => {
    app.camera.aspect = innerWidth / innerHeight;
    app.camera.updateProjectionMatrix();
    app.renderer.setSize(innerWidth, innerHeight);
  });

  canvas.addEventListener('click', () => {
    if (!NO_LOCK && document.pointerLockElement !== canvas) canvas.requestPointerLock();
  });

  addEventListener('mousemove', e => {
    if (document.pointerLockElement === canvas) player.look(e.movementX, e.movementY);
  });

  addEventListener('wheel', e => {
    if (document.pointerLockElement === canvas) player.wheel(e.deltaY);
  }, { passive: true });

  addEventListener('keydown', e => {
    if (e.repeat) {
      if (e.code === 'KeyE') player.interactHeld = true;
      return;
    }
    player.keys.add(e.code);
    switch (e.code) {
      case 'KeyE': {
        const op = player.interactPress();
        if (op && op.type === 'door') hud.say(`${op.target > 0.5 ? 'opened' : 'closed'} the ${op.name}`);
        break;
      }
      case 'KeyB': {
        const name = audio.toggleBackend();
        hud.say(name === 'portal'
          ? 'BACKEND B — portals, per-material filters, solver reverb'
          : 'BACKEND A — two baked stems, crossfaded', 3.2);
        break;
      }
      case 'KeyT': {
        // Close, because T is the "let me hear thunder" key and the honest random distribution is
        // a bad answer to that question: it used to reach 3.3 km, which is a ten second wait for a
        // rumble. Shift for a distant one — the contrast between the two is the point of §3.5.
        const far = app.player.keys.has('ShiftLeft') || app.player.keys.has('ShiftRight');
        const s = thunder.strike(far ? 2600 + app.rnd() * 4500 : 220 + app.rnd() * 700);
        app.hud.say(`lightning at ${(s.distance / 1000).toFixed(1)} km — boom in ${s.delay.toFixed(1)} s`
          + (far ? '' : '   (shift+T for a distant one)'), 4);
        break;
      }
      case 'KeyM': {
        // Solo one layer. The fastest way to answer "which part sounds wrong" is to hear the parts.
        const order = [null, 'roof', 'diffuse', 'portals', 'wind'];
        const label = {
          null: 'MIX — everything',
          roof: 'SOLO: rain on the roof (the sound you only get indoors)',
          diffuse: 'SOLO: the storm through the walls',
          portals: 'SOLO: the windows and doors',
          wind: 'SOLO: wind',
        };
        audio.solo = order[(order.indexOf(audio.solo) + 1) % order.length];
        hud.say(label[String(audio.solo)], 3.5);
        break;
      }
      case 'BracketLeft':
      case 'BracketRight': {
        // Roof trim, live. This is the number that decides whether anything else in the room is
        // audible at all — see DEFAULT_MIX in model.js — so it is the one worth being able to taste.
        const d = e.code === 'BracketRight' ? -1 : 1;
        audio.mix.roofDb = Math.max(0, Math.min(24, audio.mix.roofDb + d));
        hud.say(`roof level ${(-audio.mix.roofDb).toFixed(0)} dB`
          + (audio.mix.roofDb === 9 ? '  (default)' : ''), 2.2);
        break;
      }
      case 'Backquote': hud.toggle(); break;
      case 'Digit1': weather.setState('calm'); hud.say('weather: calm'); break;
      case 'Digit2': weather.setState('breeze'); hud.say('weather: breeze'); break;
      case 'Digit3': weather.setState('rain'); hud.say('weather: rain'); break;
      case 'Digit4': weather.setState('downpour'); hud.say('weather: downpour'); break;
      case 'Digit5': weather.setState('storm'); hud.say('weather: STORM'); break;
      case 'KeyF': {
        app.props.lamp.intensity = app.props.lamp.intensity > 0.1 ? 0 : LAMP_CD;
        hud.say(app.props.lamp.intensity > 0 ? 'lamp on' : 'lamp off');
        break;
      }
      default: break;
    }
  });

  addEventListener('keyup', e => {
    player.keys.delete(e.code);
    if (e.code === 'KeyE') player.interactRelease();
  });

  addEventListener('blur', () => player.keys.clear());
}

// ------------------------------------------------------------------ loop

let last = 0;

function frame(now) {
  requestAnimationFrame(frame);
  const t0 = performance.now();
  const dt = Math.min((now - last) / 1000 || 0.016, 0.1);
  last = now;
  app.time += dt;

  const { player, grid, graph, weather, thunder, audio, camera, sky, props, precip, hud } = app;

  weather.update(dt);
  player.interactHold(dt);
  player.update(dt, audio);
  player.findTarget();
  for (const op of grid.openingList) op.update(dt);
  for (const v of app.views) v.update();

  // Thunder: the flash happens now, the sound arrives when it has finished travelling.
  for (const s of thunder.update(dt, weather.thunder)) void s;
  for (const s of thunder.dueBooms()) audio.thunder(s);
  app.flash += (thunder.flash() - app.flash) * Math.min(1, dt * 26);

  const pose = player.pose();
  const acoustic = audio.update({ grid, graph, weather }, pose, dt);

  // Camera. Outdoors in a gale the world shoves you about; indoors it does not, which is half of
  // why stepping inside reads as relief rather than merely as a change of scene.
  const outWeight = acoustic ? acoustic.outsideWeight : 1;
  const buffet = weather.windNow * outWeight;
  app.shake += ((Math.random() - 0.5) * buffet * 0.05 - app.shake) * Math.min(1, dt * 9);
  camera.rotation.set(0, 0, 0);
  if (app.freecam) {
    // Detached camera for development: set THRESHOLD.freecam = {pos:[x,y,z], yaw, pitch}. The
    // LISTENER stays on the player, so the audio keeps telling the truth about where a body is
    // while the eye goes and looks at the building from outside.
    const c = app.freecam;
    camera.position.set(c.pos[0], c.pos[1], c.pos[2]);
    camera.rotateY(c.yaw + Math.PI);
    camera.rotateX(c.pitch || 0);
  } else {
    camera.position.set(pose.pos[0], pose.pos[1] + app.shake, pose.pos[2]);
    camera.rotateY(player.yaw + Math.PI);   // the model's yaw 0 looks +Z; three.js looks -Z
    camera.rotateX(player.pitch);
    camera.rotateZ(app.shake * 1.6);
  }

  sky.update(weather, app.flash, app.time, camera);
  props.update(dt, weather, app.flash);
  precip.update(dt, weather, camera, grid.openingList);

  app.glass.uniforms.uTime.value = app.time;
  app.glass.uniforms.uRain.value = weather.rain;
  app.glass.uniforms.uWind.value = weather.windNow;
  app.glass.uniforms.uSky.value.copy(sky.scene.fog.color);

  // The sun follows you so the shadow map stays where the player is.
  sky.sun.position.set(camera.position.x - 30, 55, camera.position.z - 34);
  sky.sun.target.position.copy(camera.position);
  sky.sun.target.updateMatrixWorld();

  app.renderer.render(app.scene, camera);

  hud.update({
    player, graph, weather,
    frame: acoustic,
    smooth: audio.ready ? audio.smooth : null,
    backend: audio.describe(),
    strikes: thunder.active.length,
    frameMs: performance.now() - t0,
  }, dt);
}

// ------------------------------------------------------------------ boot

function titleScreen(onStart) {
  const el = document.createElement('div');
  el.id = 'title';
  el.innerHTML = `
    <div class="card">
      <h1>THRESHOLD</h1>
      <p class="sub">Cozy is not quiet. Cozy is <em>filtered</em>.</p>
      <p>Walk out into the storm. Then walk inside and shut the door behind you.
         Crack a window and listen to <em>where</em> the noise comes from.</p>
      <p class="keys">WASD move &nbsp;·&nbsp; E interact &nbsp;·&nbsp; wheel opens a window by degrees
         &nbsp;·&nbsp; 1&ndash;5 weather &nbsp;·&nbsp; T lightning (shift+T distant) &nbsp;·&nbsp; B swap acoustics
         &nbsp;·&nbsp; ~ debug</p>
      <p class="keys">M solos one layer at a time &nbsp;·&nbsp; [ and ] set how loud the roof is</p>
      <p class="phones">Headphones strongly recommended &mdash; the openings are positioned in 3D,
         and on speakers you lose most of the point.</p>
      <button id="startbtn">click to begin</button>
    </div>`;
  document.body.appendChild(el);
  const btn = el.querySelector('#startbtn');
  btn.addEventListener('click', async () => {
    if (btn.disabled) return;
    btn.disabled = true;
    btn.textContent = 'making rain…';

    // Two frames before the heavy work, so that label actually PAINTS. Boot synthesises every
    // buffer in the game on the main thread; setting text and then blocking in the same task means
    // the browser never gets a chance to draw it, so a slow boot is indistinguishable from a dead
    // button. (It was: the roof layer alone once took seven seconds, and the only symptom visible
    // from outside was "clicking begin doesn't do anything".)
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));

    // And if it throws, SAY SO. Without this the button sits on "making rain…" for ever and every
    // possible failure — no Web Audio, a bad buffer, a typo in the synth — looks identical to a
    // click that did not register.
    try {
      await onStart();
    } catch (e) {
      btn.disabled = false;
      btn.textContent = 'audio failed — click to retry';
      el.insertAdjacentHTML('beforeend',
        `<p class="err">${String((e && e.message) || e)}</p>`);
      console.error(e);
      return;
    }

    el.remove();
    // ?nolock exists for tools/smoke.mjs. Pointer lock confines the REAL cursor to the window, so
    // an automated run that clicks start will otherwise trap the mouse of whoever is at the
    // machine — which is exactly what it did.
    if (!NO_LOCK) app.renderer.domElement.requestPointerLock();
  });
}

if (new URLSearchParams(location.search).has('test')) {
  // ?test renders the audio graph offline and asserts on the samples. No game, no gesture needed —
  // OfflineAudioContext does not require one, only a live AudioContext does.
  import('../tests/browser.mjs').then(m => m.run());
} else {
  build();
  bindInput();
  requestAnimationFrame(frame);

  titleScreen(async () => {
    // An AudioContext created outside a user gesture starts suspended and never makes a sound, so
    // everything audio waits for this click.
    await app.audio.init();
    app.weather.snap('rain');
    app.weather.setState('downpour');
    app.hud.say('press B to hear the difference between the two acoustic backends', 5);
  });

  globalThis.THRESHOLD = app;
}
