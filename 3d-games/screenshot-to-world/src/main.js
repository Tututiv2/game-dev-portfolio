import * as THREE from 'three';
import { buildWorld } from './analyze/world.js';
import { buildScene } from './render/scene.js';
import { createPlayer } from './render/player.js';
import { clamp } from './analyze/util.js';

/**
 * App shell: photo in, world out, and the dive between them.
 */

const DIVE_SECONDS = 2.8;

const canvas = document.getElementById('view');
const dropEl = document.getElementById('drop');
const zoneEl = document.getElementById('zone');
const statusEl = document.getElementById('status');
const statusText = document.getElementById('statusText');
const hudEl = document.getElementById('hud');
const hudName = document.getElementById('hudName');
const hudArch = document.getElementById('hudArch');
const clickEl = document.getElementById('click2play');
const errEl = document.getElementById('err');

const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.outputColorSpace = THREE.SRGBColorSpace;

let current = null; // { spec, world, player, diveT, diving }
let rafId = 0;
const clock = new THREE.Clock();

// --- loading ---------------------------------------------------------------

async function loadFromSource(src, name) {
  showStatus('Reading the photograph…');
  hideError();
  try {
    const img = new Image();
    img.decoding = 'async';
    img.src = src;
    await img.decode();

    // Yield once so the spinner actually paints before the analysis blocks.
    await new Promise((r) => requestAnimationFrame(r));

    statusText.textContent = 'Finding the horizon…';
    const spec = await buildWorld(img, { name: name || 'Untitled world' });

    statusText.textContent = 'Building the world…';
    await new Promise((r) => requestAnimationFrame(r));

    if (current) {
      current.player.dispose();
      current.world.dispose();
    }

    const world = buildScene(spec, { width: innerWidth, height: innerHeight });
    const player = createPlayer(world.camera, world, {
      eyeHeight: spec.camera.eyeHeight,
      domElement: canvas,
    });
    player.state.pos.set(spec.spawn.x, spec.camera.eyeHeight, -spec.spawn.z);

    current = { spec, world, player, diveT: 0, diving: true };

    hudName.textContent = spec.name;
    hudArch.textContent = `· ${spec.archetype}${spec.props?.length ? ` · ${spec.props.length} silhouettes` : ''}`;

    hideStatus();
    dropEl.classList.add('hidden');
    hudEl.classList.add('on');
    clickEl.classList.add('on');

    document.body.dataset.ready = '1';
    document.body.dataset.archetype = spec.archetype;
  } catch (e) {
    hideStatus();
    showError(e?.message || String(e));
    console.error(e);
  }
}

async function loadFromFile(file) {
  if (!file || !/^image\//.test(file.type)) {
    showError('That is not an image file.');
    return;
  }
  const url = await new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => res(fr.result);
    fr.onerror = () => rej(new Error('Could not read that file'));
    fr.readAsDataURL(file);
  });
  await loadFromSource(url, stripExt(file.name));
}

function stripExt(n) {
  return String(n || '').replace(/\.[^.]+$/, '') || 'Untitled world';
}

// --- input -----------------------------------------------------------------

window.addEventListener('dragover', (e) => {
  e.preventDefault();
  document.body.classList.add('dragging');
});
window.addEventListener('dragleave', (e) => {
  if (e.relatedTarget === null) document.body.classList.remove('dragging');
});
window.addEventListener('drop', async (e) => {
  e.preventDefault();
  document.body.classList.remove('dragging');
  const file = e.dataTransfer?.files?.[0];
  if (!file) return;

  // In Electron, read via the main process so large photos skip the FileReader.
  const path = window.sagm?.pathForFile?.(file);
  if (path) {
    try {
      const url = await window.sagm.readImage(path);
      await loadFromSource(url, stripExt(path.split(/[\\/]/).pop()));
      return;
    } catch {
      /* fall through to the browser path */
    }
  }
  await loadFromFile(file);
});

zoneEl.addEventListener('click', async () => {
  if (window.sagm?.pickImage) {
    const path = await window.sagm.pickImage();
    if (!path) return;
    const url = await window.sagm.readImage(path);
    await loadFromSource(url, stripExt(path.split(/[\\/]/).pop()));
    return;
  }
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*';
  input.onchange = () => input.files[0] && loadFromFile(input.files[0]);
  input.click();
});

window.addEventListener('paste', (e) => {
  const item = [...(e.clipboardData?.items || [])].find((i) => i.type.startsWith('image/'));
  if (item) loadFromFile(item.getAsFile());
});

canvas.addEventListener('click', () => {
  if (current && !current.diving) current.player.lock();
});

document.addEventListener('pointerlockchange', () => {
  clickEl.classList.toggle('on', !!current && document.pointerLockElement !== canvas);
});

window.addEventListener('keydown', (e) => {
  if (!current) return;
  if (e.code === 'KeyR') {
    current.diveT = 0;
    current.diving = true;
    current.player.state.pos.set(0, current.spec.camera.eyeHeight, 0);
    current.player.state.yaw = 0;
    current.player.state.pitch = 0;
  }
  if (e.code === 'KeyN') {
    dropEl.classList.remove('hidden');
    hudEl.classList.remove('on');
    clickEl.classList.remove('on');
    document.exitPointerLock?.();
  }
});

window.addEventListener('resize', () => {
  renderer.setSize(innerWidth, innerHeight);
  current?.world.setViewportSize(innerWidth, innerHeight);
});

// --- loop ------------------------------------------------------------------

function frame() {
  rafId = requestAnimationFrame(frame);
  const dt = clock.getDelta();
  if (!current) return;

  const { world, player } = current;

  if (current.diving) {
    current.diveT += dt / DIVE_SECONDS;
    const t = clamp(current.diveT, 0, 1);
    world.setDive(t);

    // Ease the camera forward through the picture plane as it unfolds. The
    // push is what turns "the image expanded" into "I went in".
    const ease = t * t * (3 - 2 * t);
    const eye = current.spec.camera.eyeHeight;
    player.state.pos.set(0, eye, -(1 - ease) * -6);
    world.camera.position.copy(player.state.pos);
    world.camera.rotation.set(0, 0, 0, 'YXZ');

    if (t >= 1) {
      current.diving = false;
      player.state.pos.set(0, eye, 0);
    }
  } else {
    player.update(dt);
  }

  world.faceProps(world.camera.position);
  renderer.render(world.scene, world.camera);
}
frame();

// --- ui helpers ------------------------------------------------------------

function showStatus(t) {
  statusText.textContent = t;
  statusEl.classList.add('on');
}
function hideStatus() {
  statusEl.classList.remove('on');
}
function showError(m) {
  errEl.textContent = m;
  errEl.classList.add('on');
}
function hideError() {
  errEl.classList.remove('on');
}

// --- test hook -------------------------------------------------------------

window.__sagm = {
  load: loadFromSource,
  /** Jump straight to a dive position, for deterministic screenshots. */
  setDive(t) {
    if (!current) return;
    current.diving = false;
    current.diveT = t;
    current.world.setDive(t);
    const eye = current.spec.camera.eyeHeight;
    current.player.state.pos.set(0, eye, 0);
    current.world.camera.position.copy(current.player.state.pos);
    current.world.camera.rotation.set(0, 0, 0, 'YXZ');
    current.world.faceProps(current.world.camera.position);
  },
  look(yaw, pitch = 0) {
    if (!current) return;
    current.player.state.yaw = yaw;
    current.player.state.pitch = pitch;
    current.world.camera.rotation.set(pitch, yaw, 0, 'YXZ');
    current.world.faceProps(current.world.camera.position);
  },
  moveTo(x, z) {
    if (!current) return;
    const y = current.world.heightAt(x, z) + current.spec.camera.eyeHeight;
    current.player.state.pos.set(x, y, z);
    current.world.camera.position.set(x, y, z);
  },
  get spec() {
    return current?.spec ?? null;
  },
};
