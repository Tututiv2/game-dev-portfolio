import * as THREE from 'three';
import { NetClient } from './net.js';

// Shared geometries/materials: entities churn constantly (bot respawns), so
// per-spawn allocations without dispose leak GPU memory.
const playerGeometry = new THREE.CapsuleGeometry(0.35, 0.9, 4, 10);
const botGeometry = new THREE.SphereGeometry(0.35, 10, 10);
const botMaterial = new THREE.MeshStandardMaterial({ color: 0xff4444 });

function makePlayerMesh(color) {
  // Material is per-player (color differs); geometry is shared.
  const mat = new THREE.MeshStandardMaterial({ color });
  const mesh = new THREE.Mesh(playerGeometry, mat);
  mesh.castShadow = false;
  return mesh;
}

function makeBotMesh() {
  return new THREE.Mesh(botGeometry, botMaterial);
}

function lerpVec3(a, b, t) {
  return new THREE.Vector3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
}

export function startGame() {
  const hudConn = document.getElementById('hud-conn');
  const hudPlayer = document.getElementById('hud-player');
  const hudNet = document.getElementById('hud-net');
  const hudEvents = document.getElementById('hud-events');
  const hudObjective = document.getElementById('hud-objective');
  const hudWarning = document.getElementById('hud-warning');
  const hudWeapon = document.getElementById('hud-weapon');
  const hudAmmo = document.getElementById('hud-ammo');
  const crosshair = document.getElementById('crosshair');
  const damageVignette = document.getElementById('damage-vignette');
  const mouseHint = document.getElementById('mouse-hint');
  const lootOverlay = document.getElementById('loot-overlay');
  const lootSubtitle = document.getElementById('loot-subtitle');
  const lootList = document.getElementById('loot-list');
  const lootClose = document.getElementById('loot-close');

  const app = document.getElementById('app');
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  app.appendChild(renderer.domElement);
  // Pointer-lock keeps mouse movement stable and prevents leaving the screen.
  let pointerLocked = false;
  function updatePointerHint() {
    if (!mouseHint) return;
    mouseHint.style.display = pointerLocked ? 'none' : 'block';
  }
  updatePointerHint();
  document.addEventListener('pointerlockchange', () => {
    pointerLocked = document.pointerLockElement === renderer.domElement;
    renderer.domElement.style.cursor = pointerLocked ? 'none' : 'default';
    updatePointerHint();
  });
  renderer.domElement.addEventListener('mousedown', () => {
    if (document.pointerLockElement) return;
    renderer.domElement.requestPointerLock?.();
  });

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x000000);

  const camera = new THREE.PerspectiveCamera(70, window.innerWidth / window.innerHeight, 0.1, 500);
  camera.position.set(0, 6, 14);

  const ambient = new THREE.AmbientLight(0xffffff, 0.6);
  scene.add(ambient);
  const dir = new THREE.DirectionalLight(0xffffff, 0.9);
  dir.position.set(10, 20, 10);
  scene.add(dir);

  const grid = new THREE.GridHelper(200, 40, 0x222244, 0x111122);
  scene.add(grid);

  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(200, 200),
    new THREE.MeshStandardMaterial({ color: 0x050510, roughness: 1, metalness: 0 }),
  );
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = 0;
  scene.add(floor);

  // id -> {mesh, targetPos, targetYaw, animMoving, speed}
  // Local player is reconciled via prediction; remote players use interpolation.
  const players = new Map();
  const bots = new Map(); // botId -> mesh
  const tempVfx = []; // {obj, kind, bornAt, ttlMs}
  let objectiveMesh = null;
  let objectiveRadiusCached = 0;
  let eventCounter = 0;

  // Input
  const keys = new Set();
  const mouseButtons = new Set();
  let shoulderTarget = -1; // -1 = left shoulder, +1 = right shoulder
  let shoulderBlend = -1;
  let xLatch = false;
  let reloadLatch = false;
  let switch1Latch = false;
  let switch2Latch = false;
  let upgradeLatch = false;
  window.addEventListener('keydown', (e) => {
    if (e.code === 'Space') e.preventDefault();
    if (e.code === 'KeyX' && !xLatch) {
      xLatch = true;
      shoulderTarget = shoulderTarget === -1 ? 1 : -1;
    }
    if (e.code === 'KeyR') reloadLatch = true;
    if (e.code === 'Digit1') switch1Latch = true;
    if (e.code === 'Digit2') switch2Latch = true;
    if (e.code === 'KeyU') upgradeLatch = true;
    keys.add(e.code);
  });
  window.addEventListener('keyup', (e) => {
    if (e.code === 'Space') e.preventDefault();
    if (e.code === 'KeyX') xLatch = false;
    if (e.code === 'KeyR') reloadLatch = false;
    if (e.code === 'Digit1') switch1Latch = false;
    if (e.code === 'Digit2') switch2Latch = false;
    if (e.code === 'KeyU') upgradeLatch = false;
    keys.delete(e.code);
  });
  let smoothedMoveVec = { x: 0, z: 0 };
  renderer.domElement.addEventListener('mousedown', (e) => {
    if (e.button !== 0) return;
    if (!pointerLocked) return;
    mouseButtons.add(0);
  });
  window.addEventListener('mouseup', (e) => {
    if (e.button !== 0) return;
    mouseButtons.delete(0);
  });
  document.addEventListener('pointerlockchange', () => {
    if (!pointerLocked) mouseButtons.clear();
  });
  // Losing focus mid-press would otherwise leave keys/fire stuck on.
  window.addEventListener('blur', () => {
    keys.clear();
    mouseButtons.clear();
  });


  let aimYaw = 0;
  let aimPitch = 0; // reserved for later
  window.addEventListener('mousemove', (e) => {
    // Minimal mouse-aim scaffolding. Later we'll do proper ray/ADS + recoil.
    if (!pointerLocked) return;
    // Mouse-to-aim: invert axes so "mouse up = look up" feels natural.
    aimYaw -= e.movementX * 0.003;
    aimPitch -= e.movementY * 0.001;
    // Wider vertical range so "look up" can rotate much farther around the anchor.
    aimPitch = Math.max(-1.1, Math.min(1.35, aimPitch));
  });

  function currentMoveVec() {
    const forwardInput = (keys.has('KeyW') ? 1 : 0) - (keys.has('KeyS') ? 1 : 0);
    // Fix strafing orientation: user expects D=right, A=left relative to camera.
    // Current mapping is flipped, so invert the strafe input.
    const rightInput = (keys.has('KeyA') ? 1 : 0) - (keys.has('KeyD') ? 1 : 0);

    // Move relative to where you're looking (aimYaw).
    const forwardX = Math.sin(aimYaw);
    const forwardZ = Math.cos(aimYaw);
    const rightX = Math.cos(aimYaw);
    const rightZ = -Math.sin(aimYaw);

    let x = rightInput * rightX + forwardInput * forwardX;
    let z = rightInput * rightZ + forwardInput * forwardZ;

    const len = Math.hypot(x, z);
    if (len > 0) {
      x /= len;
      z /= len;
    }
    return { x, z };
  }

  const camForward = new THREE.Vector3();
  function currentAimDir() {
    // Use camera-forward for perfect crosshair alignment.
    camera.getWorldDirection(camForward);
    return { x: camForward.x, y: camForward.y, z: camForward.z };
  }

  // Movement prediction constants (must match server behavior).
  const MAX_SPEED = 7;
  const ACC_BLEND = 0.78; // keep old vel
  const ACC_WEIGHT = 0.22; // add desired vel
  const ARENA_HALF = 40;

  function clamp(n, a, b) {
    return Math.max(a, Math.min(b, n));
  }

  // Frame-rate independent equivalent of "lerp by `rate` once per 60fps frame".
  function smoothFactor(rate, dt) {
    return 1 - Math.pow(1 - rate, dt * 60);
  }

  // Networking
  const net = new NetClient({
    wsUrl: new URLSearchParams(location.search).get('ws') || 'ws://localhost:8080',
    roomId: new URLSearchParams(location.search).get('room') || 'default',
  });

  // Local prediction state
  const localSim = {
    initialized: false,
    pos: new THREE.Vector3(),
    vel: new THREE.Vector3(),
    yaw: 0,
    onGround: true,
    slideTicksLeft: 0,
    slideCooldownUntil: 0,
  };
  const localRenderPos = new THREE.Vector3();
  let localRenderInit = false;
  let pendingInputs = []; // [{seq, moveVec, aimDir}]
  let stateHistory = new Map(); // seq -> {pos, vel, yaw}
  let lastAckSeq = 0;

  function aimToYaw(aimDir) {
    const x = aimDir?.x ?? 0;
    const z = aimDir?.z ?? 1;
    return Math.atan2(x, z);
  }

  const sendIntervalMs = 50; // keep network cadence at ~20Hz
  const GROUND_Y = 0.5;
  const GRAVITY = 22;
  const JUMP_VELOCITY = 8.2;
  const SLIDE_SPEED_MULT = 1.85;
  const SLIDE_TICKS = 9;
  const SLIDE_COOLDOWN_TICKS = 24;
  let lastLocalJumpAt = -10000;

  // Shooting feel (local VFX gating only; damage remains server-authoritative).
  const FIRE_COOLDOWN_TICKS = 3;
  const FIRE_INTERVAL_MS = sendIntervalMs * FIRE_COOLDOWN_TICKS;
  let nextLocalShotAt = 0;
  let recoil = 0;
  let damageFlash = 0;
  let latestMoveVec = { x: 0, z: 0 };
  let latestAimDir = { x: 0, y: 0, z: 1 };
  let latestJumpPressed = false;
  let latestSlidePressed = false;
  let latestTickId = 0;
  const weaponView = {
    weaponId: 'tempest_placeholder',
    label: 'Tempest',
    level: 1,
    ammoInMag: 30,
    reserveAmmo: 90,
    magSize: 30,
    durability: 100,
    durabilityMax: 100,
    isReloading: false,
  };
  const recoilPatterns = {
    bobcat_placeholder: [0.45, 0.58, 0.52, 0.62, 0.56, 0.48],
    tempest_placeholder: [0.28, 0.31, 0.36, 0.34, 0.39, 0.37],
  };
  let recoilPatternIdx = 0;

  function spawnVfx(mesh, ttlMs) {
    tempVfx.push({ obj: mesh, kind: 'mesh', bornAt: performance.now(), ttlMs });
    scene.add(mesh);
  }

  function spawnTracer(startPoint, endPoint) {
    if (!startPoint || !endPoint) return;
    const start = new THREE.Vector3(startPoint.x, startPoint.y, startPoint.z);
    const end = new THREE.Vector3(endPoint.x, endPoint.y, endPoint.z);
    const dir = end.clone().sub(start);
    const len = dir.length();
    if (len < 0.05) return;

    // A glowing cylinder is much more visible than a 1px line.
    const geo = new THREE.CylinderGeometry(0.06, 0.06, len, 8, 1, true);
    const mat = new THREE.MeshBasicMaterial({
      color: 0x00d9ff,
      transparent: true,
      opacity: 0.9,
      blending: THREE.AdditiveBlending,
      depthTest: false,
      depthWrite: false,
    });
    const mesh = new THREE.Mesh(geo, mat);
    const mid = start.clone().add(end).multiplyScalar(0.5);
    mesh.position.copy(mid);

    // Default cylinder points up (Y axis). Rotate to match dir.
    const up = new THREE.Vector3(0, 1, 0);
    const quat = new THREE.Quaternion().setFromUnitVectors(up, dir.clone().normalize());
    mesh.quaternion.copy(quat);

    // Render slightly above other objects.
    mesh.renderOrder = 20;
    spawnVfx(mesh, 420);
  }

  function spawnMuzzleFlash() {
    if (!net.playerId || !players.has(net.playerId)) return;
    const entry = players.get(net.playerId);
    const p = entry.mesh.position;
    const flash = new THREE.Mesh(
      new THREE.SphereGeometry(0.12, 10, 10),
      new THREE.MeshBasicMaterial({ color: 0x00d9ff, transparent: true, opacity: 1 }),
    );
    flash.position.set(p.x, p.y + 0.95, p.z);
    spawnVfx(flash, 90);
  }

  function spawnImpact(hitPoint) {
    if (!hitPoint) return;
    const impact = new THREE.Mesh(
      new THREE.SphereGeometry(0.2, 12, 12),
      new THREE.MeshBasicMaterial({ color: 0xff3355, transparent: true, opacity: 1 }),
    );
    impact.position.set(hitPoint.x, hitPoint.y, hitPoint.z);
    spawnVfx(impact, 240);
  }

  function stepLocal({ moveVec, aimDir, seq, jumpPressed, slidePressed, clientTickId, dt }) {
    if (!localSim.initialized) return;

    const mx = moveVec?.x ?? 0;
    const mz = moveVec?.z ?? 0;

    if (slidePressed && localSim.onGround && clientTickId >= localSim.slideCooldownUntil) {
      localSim.slideTicksLeft = SLIDE_TICKS;
      localSim.slideCooldownUntil = clientTickId + SLIDE_COOLDOWN_TICKS;
    }
    // This runs per render frame, but ticks are 50ms on the server — scale by dt
    // so a slide lasts the same wall-clock time the server simulates.
    if (localSim.slideTicksLeft > 0) localSim.slideTicksLeft = Math.max(0, localSim.slideTicksLeft - dt / 0.05);
    const speedMult = localSim.slideTicksLeft > 0 ? SLIDE_SPEED_MULT : 1;

    const desiredVx = mx * MAX_SPEED * speedMult;
    const desiredVz = mz * MAX_SPEED * speedMult;

    // Match the server blend (0.78/0.22 per 50ms tick), made frame-rate independent:
    // keep^(dt/tick) equals the server factor exactly at tick cadence.
    const keep = Math.pow(ACC_BLEND, dt / 0.05);
    localSim.vel.x = localSim.vel.x * keep + desiredVx * (1 - keep);
    localSim.vel.z = localSim.vel.z * keep + desiredVz * (1 - keep);

    localSim.pos.x += localSim.vel.x * dt;
    localSim.pos.z += localSim.vel.z * dt;
    localSim.pos.x = clamp(localSim.pos.x, -ARENA_HALF, ARENA_HALF);
    localSim.pos.z = clamp(localSim.pos.z, -ARENA_HALF, ARENA_HALF);

    localSim.yaw = aimToYaw(aimDir);
    if (jumpPressed && localSim.onGround) {
      localSim.vel.y = JUMP_VELOCITY;
      localSim.onGround = false;
      lastLocalJumpAt = performance.now();
    }
    localSim.vel.y -= GRAVITY * dt;
    localSim.pos.y += localSim.vel.y * dt;
    if (localSim.pos.y <= GROUND_Y) {
      localSim.pos.y = GROUND_Y;
      localSim.vel.y = 0;
      localSim.onGround = true;
    }

    if (typeof seq === 'number') {
      stateHistory.set(seq, {
        pos: { x: localSim.pos.x, y: localSim.pos.y, z: localSim.pos.z },
        vel: { x: localSim.vel.x, y: localSim.vel.y, z: localSim.vel.z },
        yaw: localSim.yaw,
      });
    }
  }

  function reconcileLocalWithServer(p) {
    if (!p) return;

    const ackSeq = typeof p.inputSeq === 'number' ? p.inputSeq : lastAckSeq;
    lastAckSeq = ackSeq;

    if (!localSim.initialized) {
      localSim.initialized = true;
      localSim.pos.set(p.pos.x, p.pos.y, p.pos.z);
      localSim.vel.set(p.vel.x, p.vel.y, p.vel.z);
      localSim.yaw = p.yaw || 0;
      localSim.onGround = p.pos.y <= GROUND_Y + 0.001;
    } else {
      // Soft correction avoids 20Hz snap/jitter for local movement.
      const ex = p.pos.x - localSim.pos.x;
      const ez = p.pos.z - localSim.pos.z;
      const horizontalErr = Math.hypot(ex, ez);
      if (horizontalErr > 3.8) {
        localSim.pos.x = p.pos.x;
        localSim.pos.z = p.pos.z;
      } else {
        localSim.pos.x += ex * 0.08;
        localSim.pos.z += ez * 0.08;
      }
      localSim.vel.x += (p.vel.x - localSim.vel.x) * 0.1;
      localSim.vel.z += (p.vel.z - localSim.vel.z) * 0.1;
      localSim.yaw = p.yaw || localSim.yaw;

      const serverGrounded = p.pos.y <= GROUND_Y + 0.001;
      const jumpGraceMs = 420;
      const inJumpGrace = performance.now() - lastLocalJumpAt < jumpGraceMs;
      if (serverGrounded && inJumpGrace && !localSim.onGround) {
        // Ignore very-early ground snaps right after a local jump press.
      } else if (Math.abs(p.pos.y - localSim.pos.y) > 2.6) {
        localSim.pos.y = p.pos.y;
        localSim.vel.y = p.vel.y;
      } else {
        localSim.pos.y += (p.pos.y - localSim.pos.y) * 0.08;
        localSim.vel.y += (p.vel.y - localSim.vel.y) * 0.08;
      }
      if (localSim.pos.y <= GROUND_Y + 0.01) localSim.onGround = true;
      else if (!serverGrounded) localSim.onGround = false;
    }

    // Drop inputs the server has already processed.
    pendingInputs = pendingInputs.filter((inp) => inp.seq > ackSeq);
    for (const seq of stateHistory.keys()) {
      if (seq <= ackSeq) stateHistory.delete(seq);
    }

    // Re-simulate remaining inputs to get the latest local predicted state.
    for (const inp of pendingInputs) {
      // We already run continuous local simulation each frame; no replay needed.
      // Keep pending queue only for future net diagnostics.
    }
  }

  net.setCallbacks({
    onSnapshot: (snap) => {
      if (!snap || !snap.players) return;
      const nextPlayers = new Set();
      for (const p of snap.players) {
        nextPlayers.add(p.id);
        if (!players.has(p.id)) {
          const color = p.id === net.playerId ? 0x00d9ff : 0x00cc66;
          const mesh = makePlayerMesh(color);
          scene.add(mesh);
          players.set(p.id, { mesh, targetPos: null, targetYaw: p.yaw || 0, animMoving: false, speed: 0 });
        }
        const entry = players.get(p.id);

        entry.animMoving = Boolean(p.anim?.moving);
        entry.speed = Number(p.anim?.speed) || 0;

        if (p.id === net.playerId) {
          // Predictively update local mesh via reconciliation.
          if (!localSim.initialized) {
            localSim.initialized = true;
            localSim.pos.set(p.pos.x, p.pos.y, p.pos.z);
            localSim.vel.set(p.vel.x, p.vel.y, p.vel.z);
            localSim.yaw = p.yaw || 0;
          }
          reconcileLocalWithServer(p);
          if (hudPlayer) hudPlayer.textContent = `${Math.floor(p.hp)} HP / ${Math.floor(p.shield)} SH`;
        } else {
          entry.targetPos = new THREE.Vector3(p.pos.x, p.pos.y, p.pos.z);
          entry.targetYaw = p.yaw || 0;
        }
      }

      // Bots (placeholder for later to-do)
      const nextBots = new Set();
      for (const b of snap.bots || []) {
        nextBots.add(b.id);
        if (!bots.has(b.id)) {
          const mesh = makeBotMesh();
          scene.add(mesh);
          bots.set(b.id, mesh);
        }
        const mesh = bots.get(b.id);
        mesh.position.set(b.pos.x, b.pos.y, b.pos.z);
        if (typeof b.yaw === 'number') mesh.rotation.y = b.yaw;
      }
      // cleanup removed bots
      for (const [botId, mesh] of bots.entries()) {
        if (!nextBots.has(botId)) {
          scene.remove(mesh);
          bots.delete(botId);
        }
      }

      // cleanup disconnected players (mesh would otherwise linger forever)
      for (const [pid, entry] of players.entries()) {
        if (!nextPlayers.has(pid)) {
          scene.remove(entry.mesh);
          entry.mesh.material?.dispose?.(); // per-player material; geometry is shared
          players.delete(pid);
        }
      }

      const obj = snap.objectiveState;
      if (obj?.objectivePos) {
        const pos = obj.objectivePos;
        const radius = Number(obj.objectiveRadius) || 3.2;
        const phase = obj.phase || 'unknown';

        const color =
          phase === 'hold'
            ? 0x00e676
            : phase === 'extracting'
              ? 0xff5252
              : phase === 'complete'
                ? 0x00d9ff
                : 0x444444;

        if (!objectiveMesh || Math.abs(radius - objectiveRadiusCached) > 0.01) {
          if (objectiveMesh) {
            scene.remove(objectiveMesh);
            objectiveMesh.geometry.dispose?.();
            objectiveMesh.material.dispose?.();
          }
          const geo = new THREE.RingGeometry(radius * 0.6, radius, 40);
          const mat = new THREE.MeshBasicMaterial({
            color,
            transparent: true,
            opacity: 0.35,
            side: THREE.DoubleSide,
          });
          objectiveMesh = new THREE.Mesh(geo, mat);
          objectiveMesh.rotation.x = -Math.PI / 2;
          objectiveMesh.position.set(pos.x, 0.01, pos.z);
          scene.add(objectiveMesh);
          objectiveRadiusCached = radius;
        } else {
          objectiveMesh.material.color.setHex(color);
          objectiveMesh.position.set(pos.x, 0.01, pos.z);
        }
      }

      if (hudNet) {
        const obj = snap.objectiveState;
        if (obj?.phase === 'hold') {
          const pct = Math.round((obj.holdProgress || 0) * 100);
          hudNet.textContent = `Net: tick ${snap.serverTick ?? '—'} | Obj: hold ${pct}%`;
          if (hudObjective) hudObjective.textContent = `Objective: secure area (${pct}%)`;
          if (hudWarning) hudWarning.textContent = pct < 25 ? 'Get inside objective to build progress' : '';
        } else if (obj?.phase === 'extracting') {
          const sec = Math.ceil((obj.extractionTicksLeft || 0) * 0.05);
          hudNet.textContent = `Net: tick ${snap.serverTick ?? '—'} | Obj: extracting ${sec}s`;
          if (hudObjective) hudObjective.textContent = `Objective: survive extraction (${sec}s)`;
          if (hudWarning) hudWarning.textContent = sec <= 5 ? 'Extraction almost complete - hold!' : '';
        } else if (obj?.phase === 'warmup') {
          const sec = Math.ceil((obj.warmupTicksLeft || 0) * 0.05);
          hudNet.textContent = `Net: tick ${snap.serverTick ?? '—'} | Obj: warmup ${sec}s`;
          if (hudObjective) hudObjective.textContent = `Objective: prepare (${sec}s)`;
          if (hudWarning) hudWarning.textContent = '';
        } else if (obj?.phase === 'failed') {
          const sec = Math.ceil((obj.completeTicksLeft || 0) * 0.05);
          hudNet.textContent = `Net: tick ${snap.serverTick ?? '—'} | Obj: failed`;
          if (hudObjective) hudObjective.textContent = `Objective: failed (reset in ${sec}s)`;
          if (hudWarning) hudWarning.textContent = 'Team wiped - round reset incoming';
        } else {
          hudNet.textContent = `Net: tick ${snap.serverTick ?? '—'} | Obj: ${obj?.phase ?? '—'}`;
          if (hudObjective) hudObjective.textContent = `Objective: ${obj?.phase ?? '—'}`;
          if (hudWarning) hudWarning.textContent = '';
        }
      }
      const me = (snap.players || []).find((pp) => pp.id === net.playerId);
      if (me?.weaponState) {
        Object.assign(weaponView, me.weaponState);
        if (hudWeapon) hudWeapon.textContent = `${weaponView.label} Lv${weaponView.level}`;
        if (hudAmmo) {
          const rTxt = weaponView.isReloading ? ' (reloading)' : '';
          hudAmmo.textContent = `${weaponView.ammoInMag} / ${weaponView.reserveAmmo}${rTxt}`;
        }
      }

      // Hide end-of-match UI when the next match begins.
      if (lootOverlay && snap.objectiveState?.phase === 'warmup') {
        lootOverlay.style.display = 'none';
      }
    },
    onEvent: (msg) => {
      if (!msg || msg.type !== 'event') return;
      eventCounter += 1;
      if (hudEvents) hudEvents.textContent = `Events: ${eventCounter}`;
      if (msg.eventType === 'hit') {
        const payload = msg.payload || {};
        spawnTracer(payload.startPoint, payload.endPoint || payload.hitPoint);
        spawnImpact(payload.hitPoint);
        if (payload.attackerId === net.playerId && crosshair) {
          crosshair.style.background = 'rgba(255, 70, 90, 0.95)';
          setTimeout(() => {
            if (crosshair) crosshair.style.background = 'rgba(0, 217, 255, 0.85)';
          }, 70);
        }
      }
      if (msg.eventType === 'shot') {
        const payload = msg.payload || {};
        spawnTracer(payload.startPoint, payload.endPoint);
      }
      if (msg.eventType === 'bot_shot') {
        const payload = msg.payload || {};
        spawnTracer(payload.startPoint, payload.endPoint);
      }
      if (msg.eventType === 'player_damaged') {
        if (msg.payload?.targetPlayerId === net.playerId && players.has(net.playerId)) {
          recoil = Math.min(1.4, recoil + 0.55);
          damageFlash = 1;
          const pos = players.get(net.playerId).mesh.position;
          spawnImpact({ x: pos.x, y: pos.y + 0.9, z: pos.z });
        }
      }
      if (msg.eventType === 'objective_failed' && hudWarning) {
        hudWarning.textContent = 'Mission failed - regroup';
      }
      if (msg.eventType === 'extraction_complete') {
        const loot = msg.payload?.loot || [];
        const mats = msg.payload?.materials || {};
        if (lootOverlay && lootList && lootSubtitle) {
          const matTxt = ` | Mats T1:${mats.alloy_shard_t1 || 0} T2:${mats.alloy_shard_t2 || 0} T3:${mats.alloy_shard_t3 || 0} T4:${mats.alloy_shard_t4 || 0}`;
          lootSubtitle.textContent = `Rewards: ${loot.length} item${loot.length === 1 ? '' : 's'}${matTxt}`;
          lootList.innerHTML = '';
          for (const it of loot) {
            const li = document.createElement('li');
            li.textContent = `${it.rarity}: ${it.name} x${it.qty}`;
            lootList.appendChild(li);
          }
          lootOverlay.style.display = 'block';
        }
      }
      // shot/bot_died could be used later for tracer/explosion VFX.
    },
  });

  if (lootClose && lootOverlay) {
    lootClose.addEventListener('click', () => {
      lootOverlay.style.display = 'none';
    });
  }

  net.connect();

  // Game loop
  let startedAt = performance.now();
  let lastSend = 0;
  const smoothedCamPos = new THREE.Vector3();
  let smoothedCamInit = false;
  const camDesiredPos = new THREE.Vector3();

  let lastFrameAt = performance.now();
  function animate() {
    requestAnimationFrame(animate);

    const tNow = performance.now();
    const frameDt = Math.min(0.05, Math.max(0.001, (tNow - lastFrameAt) / 1000));
    lastFrameAt = tNow;

      // Update transient VFX (muzzle flash / impacts)
    for (let i = tempVfx.length - 1; i >= 0; i--) {
      const v = tempVfx[i];
      const age = tNow - v.bornAt;
      if (age >= v.ttlMs) {
        scene.remove(v.obj);
        v.obj.geometry?.dispose?.();
        v.obj.material?.dispose?.();
        tempVfx.splice(i, 1);
        continue;
      }
      const k = age / v.ttlMs;
      if (v.kind === 'mesh') {
        v.obj.scale?.setScalar?.(1 + k * 2.2);
      }
      if (v.obj.material && typeof v.obj.material.opacity === 'number') {
        v.obj.material.opacity = 1 - k;
      }
    }

    // Update meshes
    for (const [id, entry] of players.entries()) {
      if (id === net.playerId) {
        // Local: use predicted state.
        if (localSim.initialized) {
          const bob = entry.animMoving ? Math.sin(tNow / 120) * 0.06 : 0;
          if (!localRenderInit) {
            localRenderInit = true;
            localRenderPos.set(localSim.pos.x, localSim.pos.y, localSim.pos.z);
          } else {
            const lerpRate = localSim.onGround ? 0.45 : 0.35;
            localRenderPos.lerp(localSim.pos, smoothFactor(lerpRate, frameDt));
          }
          entry.mesh.position.set(localRenderPos.x, localRenderPos.y + bob, localRenderPos.z);
          entry.mesh.rotation.y = localSim.yaw;
        }
      } else {
        if (!entry.targetPos) continue;
        entry.mesh.position.lerp(entry.targetPos, smoothFactor(0.25, frameDt));
        entry.mesh.rotation.y = entry.targetYaw;
      }
    }

    // Continuous local simulation at render frame-rate (fixes 20Hz stepping feel).
    if (net.playerId && localSim.initialized) {
      stepLocal({
        moveVec: latestMoveVec,
        aimDir: latestAimDir,
        jumpPressed: latestJumpPressed,
        slidePressed: latestSlidePressed,
        clientTickId: latestTickId,
        dt: frameDt,
      });
    }

    // Camera follows local player
    if (net.playerId && localSim.initialized && players.has(net.playerId)) {
      const localEntry = players.get(net.playerId);
      const lp = localEntry.mesh.position;

      // Third-person orbit camera driven by mouse aim.
      // Shoulder camera rig: true orbit around character anchor.
      // Vertical mouse input should rotate camera around the player (not move freely in world space).
      const baseDist = 5;
      const pitchAbs = Math.min(1, Math.abs(aimPitch) / 1.35);
      const camDist = Math.max(3.2, baseDist - pitchAbs * 1.1); // pull in a bit more at steep pitch
      const baseLift = 1.05; // lower camera overall

      const sy = Math.sin(aimYaw);
      const cy = Math.cos(aimYaw);

      // Basis vectors from yaw only.
      const forwardX = sy;
      const forwardZ = cy;
      const rightX = Math.cos(aimYaw);
      const rightZ = -Math.sin(aimYaw);

      // Anchor point (kept centered). This is what you orbit around.
      const pivotX = lp.x;
      const pivotY = lp.y + 0.95; // lower anchor (more shoulder-level)
      const pivotZ = lp.z;

      // Smooth shoulder swap: blend towards target each frame.
      shoulderBlend += (shoulderTarget - shoulderBlend) * smoothFactor(0.12, frameDt);

      // Bigger offset so it's clearly on the shoulder.
      const shoulderOffset = 1.7 * shoulderBlend;

      // Position: behind the player + shoulder offset.
      // Strong inverted pitch influence: mouse up moves camera down around anchor.
      const desiredCamX = pivotX - forwardX * camDist + rightX * shoulderOffset;
      const desiredCamY = pivotY + baseLift - aimPitch * 2.4 + recoil * 0.25;
      const desiredCamZ = pivotZ - forwardZ * camDist + rightZ * shoulderOffset;

      // Smooth camera motion to avoid snapping during swap.
      if (!smoothedCamInit) {
        smoothedCamInit = true;
        smoothedCamPos.set(desiredCamX, desiredCamY, desiredCamZ);
      } else {
        // Avoid per-frame allocations (prevents GC stutter while moving).
        camDesiredPos.set(desiredCamX, desiredCamY, desiredCamZ);
        smoothedCamPos.lerp(camDesiredPos, smoothFactor(0.25, frameDt));
      }

      camera.position.copy(smoothedCamPos);

      // Keep anchor behavior, but offset look target opposite shoulder so character
      // remains visibly off-center (true shoulder framing).
      const lookSideOffset = -shoulderBlend * 0.9;
      camera.lookAt(pivotX + rightX * lookSideOffset, pivotY, pivotZ + rightZ * lookSideOffset);

      if (crosshair) {
        const firePressed = mouseButtons.has(0);
        crosshair.style.opacity = firePressed ? '1' : '0.85';
        const bloom = Math.min(1, recoil);
        const s = (firePressed ? 1.2 : 1.0) + bloom * 0.25;
        crosshair.style.transform = `translate(-50%, -50%) scale(${s})`;
      }
    }

    // Connection status must update even while disconnected (the old code only
    // wrote it inside the `net.connected` gate, so it stayed "yes" forever).
    if (hudConn) {
      const connTxt = net.connected ? 'yes' : 'no';
      if (hudConn.textContent !== connTxt) hudConn.textContent = connTxt;
    }

    // Send inputs at stable cadence
    if (net.connected && tNow - lastSend >= sendIntervalMs) {
      lastSend = tNow;
      if (hudPlayer && hudPlayer.textContent === '?') hudPlayer.textContent = net.playerId ?? '?';

      const tickId = Math.floor((tNow - startedAt) / sendIntervalMs);
      const rawMoveVec = currentMoveVec();
      const aimDir = currentAimDir();
      smoothedMoveVec.x = smoothedMoveVec.x * 0.7 + rawMoveVec.x * 0.3;
      smoothedMoveVec.z = smoothedMoveVec.z * 0.7 + rawMoveVec.z * 0.3;
      const moveVec = { x: smoothedMoveVec.x, z: smoothedMoveVec.z };
      const firePressed = mouseButtons.has(0);
      const jumpPressed = keys.has('Space');
      const slidePressed = keys.has('KeyC');
      const reloadPressed = reloadLatch;
      const switchBobcatPressed = switch1Latch;
      const switchTempestPressed = switch2Latch;
      const upgradePressed = upgradeLatch;
      latestMoveVec = moveVec;
      latestAimDir = aimDir;
      latestJumpPressed = jumpPressed;
      latestSlidePressed = slidePressed;
      latestTickId = tickId;
      const actions = [];
      if (firePressed) actions.push({ type: 'fire', weaponId: weaponView.weaponId, pressed: true });
      if (jumpPressed) actions.push({ type: 'jump', pressed: true });
      if (slidePressed) actions.push({ type: 'slide', pressed: true });
      if (reloadPressed) actions.push({ type: 'reload', pressed: true });
      if (switchBobcatPressed) actions.push({ type: 'switch_bobcat', pressed: true });
      if (switchTempestPressed) actions.push({ type: 'switch_tempest', pressed: true });
      if (upgradePressed) actions.push({ type: 'upgrade_weapon', pressed: true });

      reloadLatch = false;
      switch1Latch = false;
      switch2Latch = false;
      upgradeLatch = false;

      const seq = net.sendInput({ tickId, moveVec, aimDir, actions });
      if (typeof seq === 'number' && net.playerId) {
        // Queue for reconciliation + simulate locally for responsiveness.
        pendingInputs.push({ seq, tickId, moveVec, aimDir, jumpPressed, slidePressed });
        // Prevent unbounded growth if snapshots lag.
        if (pendingInputs.length > 160) pendingInputs = pendingInputs.slice(pendingInputs.length - 160);
        // Step local simulation right away.
        if (!localSim.initialized) {
          // If a snapshot hasn't arrived yet, initialize from current mesh position.
          const localEntry = players.get(net.playerId);
          if (localEntry) {
            localSim.initialized = true;
            localSim.pos.set(localEntry.mesh.position.x, localEntry.mesh.position.y, localEntry.mesh.position.z);
            localSim.vel.set(0, 0, 0);
          }
        }
      }

      // Local VFX + recoil (gated by local fire interval; server remains authoritative).
      if (firePressed && !weaponView.isReloading && weaponView.ammoInMag > 0 && tNow >= nextLocalShotAt) {
        nextLocalShotAt = tNow + FIRE_INTERVAL_MS;
        const pattern = recoilPatterns[weaponView.weaponId] || recoilPatterns.tempest_placeholder;
        const kick = (pattern[recoilPatternIdx % pattern.length] || 0.35) * (1 - (weaponView.level - 1) * 0.08);
        recoilPatternIdx += 1;
        recoil = Math.min(1.2, recoil + kick);
        aimPitch += 0.015 + kick * 0.01;
        spawnMuzzleFlash();
      }
    }

    // Recoil decay (frame-rate independent; matches old per-frame *0.86/*0.84 at 60fps)
    recoil *= Math.pow(0.86, frameDt * 60);
    damageFlash *= Math.pow(0.84, frameDt * 60);
    if (damageVignette) damageVignette.style.opacity = String(Math.max(0, Math.min(1, damageFlash * 0.75)));

    renderer.render(scene, camera);
  }

  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  animate();
}

