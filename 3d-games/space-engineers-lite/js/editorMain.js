import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import {
  normalizeUniverse,
  parseUniverse,
  upsertUniverseInLibrary,
  saveUniverseToSession,
  serializeUniverse,
  validateUniverse,
} from "./universeIO.js";
import { defaultUniverse } from "./universeSchema.js";

const viewport = document.getElementById("viewport");
const statusEl = document.getElementById("editor-status");
const nameEl = document.getElementById("universe-name");
const emptyEl = document.getElementById("selected-empty");
const fieldsEl = document.getElementById("selected-fields");
const previewToggleBtn = document.getElementById("btn-preview-toggle");
const previewSpeedEl = document.getElementById("preview-speed");

const fieldEls = {
  name: document.getElementById("body-name"),
  type: document.getElementById("body-type"),
  parentId: document.getElementById("body-parent"),
  radius: document.getElementById("body-radius"),
  color: document.getElementById("body-color"),
  surfaceGravity: document.getElementById("body-gravity"),
  orbitSemi: document.getElementById("orbit-semi"),
  orbitInc: document.getElementById("orbit-inc"),
  orbitPhase: document.getElementById("orbit-phase"),
  orbitSpeed: document.getElementById("orbit-speed"),
  rotSpin: document.getElementById("rot-spin"),
  rotTilt: document.getElementById("rot-tilt"),
};

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x04070f);
scene.add(new THREE.AmbientLight(0x99aacc, 0.35));
const dirLight = new THREE.DirectionalLight(0xffffff, 1.2);
dirLight.position.set(40000, 80000, 50000);
scene.add(dirLight);

const camera = new THREE.PerspectiveCamera(50, 1, 1, 3000000);
camera.position.set(170000, 120000, 170000);

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
viewport.appendChild(renderer.domElement);

const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.screenSpacePanning = true;
controls.minDistance = 1000;
controls.maxDistance = 2000000;
controls.target.set(0, 0, 0);
controls.minPolarAngle = 0.01;
controls.maxPolarAngle = Math.PI - 0.01;

const starGeo = new THREE.BufferGeometry();
const starPos = [];
for (let i = 0; i < 24000; i += 1) {
  const p = new THREE.Vector3().randomDirection().multiplyScalar(2200000);
  starPos.push(p.x, p.y, p.z);
}
starGeo.setAttribute("position", new THREE.Float32BufferAttribute(starPos, 3));
scene.add(
  new THREE.Points(
    starGeo,
    new THREE.PointsMaterial({ color: 0xffffff, size: 12, transparent: true, opacity: 0.88, depthWrite: false })
  )
);

const bodiesGroup = new THREE.Group();
const orbitGroup = new THREE.Group();
scene.add(orbitGroup);
scene.add(bodiesGroup);

const raycaster = new THREE.Raycaster();
const plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
const pointer = new THREE.Vector2();

let universe = normalizeUniverse(defaultUniverse());
let selectedId = null;
let previewPaused = false;
let previewSpeed = 1;
let dragActive = false;
let dragStartSnapshot = "";

const meshById = new Map();
const orbitById = new Map();
const undoStack = [];
let clipboardPayload = null;

function setStatus(msg, isError = false) {
  statusEl.textContent = msg;
  statusEl.style.color = isError ? "#ff9b9b" : "#b2ffca";
}

function uniqueId(prefix) {
  return `${prefix}-${Date.now().toString(36)}-${Math.floor(Math.random() * 999).toString(16)}`;
}

function bodyById(id) {
  return universe.bodies.find((b) => b.id === id) || null;
}

function universeSnapshot() {
  return JSON.stringify(universe);
}

function restoreUniverseFromSnapshot(snapshot) {
  universe = normalizeUniverse(JSON.parse(snapshot));
  nameEl.value = universe.name || "Custom Universe";
  rebuildAndRefresh();
}

function pushUndoSnapshot(snapshot = universeSnapshot()) {
  undoStack.push(snapshot);
  if (undoStack.length > 100) undoStack.shift();
}

function undoLastChange() {
  if (undoStack.length === 0) {
    setStatus("Nothing to undo.");
    return;
  }
  const snapshot = undoStack.pop();
  restoreUniverseFromSnapshot(snapshot);
  setStatus("Undo applied.");
}

function collectSubtreeIds(rootId) {
  const acc = new Set();
  const walk = (id) => {
    if (acc.has(id)) return;
    acc.add(id);
    universe.bodies.forEach((body) => {
      if (body.parentId === id) walk(body.id);
    });
  };
  walk(rootId);
  return [...acc];
}

function copySelectionToClipboard(removeOriginal = false) {
  if (!selectedId) {
    setStatus("Select a body first.", true);
    return;
  }
  const ids = collectSubtreeIds(selectedId);
  const chunk = universe.bodies.filter((body) => ids.includes(body.id));
  clipboardPayload = {
    sourceRootId: selectedId,
    bodies: JSON.parse(JSON.stringify(chunk)),
  };
  if (removeOriginal) {
    pushUndoSnapshot();
    universe.bodies = universe.bodies.filter((body) => !ids.includes(body.id));
    selectedId = null;
    rebuildAndRefresh();
    setStatus(`Cut ${chunk.length} body/bodies.`);
    return;
  }
  setStatus(`Copied ${chunk.length} body/bodies.`);
}

function pasteClipboard() {
  if (!clipboardPayload || !Array.isArray(clipboardPayload.bodies) || clipboardPayload.bodies.length === 0) {
    setStatus("Clipboard is empty.", true);
    return;
  }
  pushUndoSnapshot();
  const idMap = new Map();
  const pasted = clipboardPayload.bodies.map((body) => {
    const nextId = uniqueId(body.type || "body");
    idMap.set(body.id, nextId);
    return {
      ...JSON.parse(JSON.stringify(body)),
      id: nextId,
      name: `${body.name} Copy`,
    };
  });

  pasted.forEach((body, idx) => {
    const old = clipboardPayload.bodies[idx];
    if (old.parentId && idMap.has(old.parentId)) {
      body.parentId = idMap.get(old.parentId);
    } else {
      body.parentId = old.parentId || null;
    }
    body.orbit = body.orbit || {};
    body.orbit.phase = (Number(body.orbit.phase) || 0) + 0.3;
  });
  universe.bodies.push(...pasted);
  selectedId = idMap.get(clipboardPayload.sourceRootId) || pasted[0].id;
  rebuildAndRefresh();
  setStatus(`Pasted ${pasted.length} body/bodies.`);
}

function computeOffset(body) {
  const r = Number(body.orbit.semiMajor) || 0;
  const a = Number(body.orbit.phase) || 0;
  const i = Number(body.orbit.inclination) || 0;
  const x = Math.cos(a) * r;
  const y = Math.sin(a) * r * Math.sin(i);
  const z = Math.sin(a) * r * Math.cos(i);
  return new THREE.Vector3(x, y, z);
}

function computeBodyPositions() {
  const positions = new Map();
  const byId = new Map(universe.bodies.map((b) => [b.id, b]));
  const seen = new Set();
  const loopGuard = new Set();
  const place = (id) => {
    if (seen.has(id)) return;
    if (loopGuard.has(id)) return;
    loopGuard.add(id);
    const body = byId.get(id);
    if (!body) {
      loopGuard.delete(id);
      return;
    }
    const parentPos = body.parentId && byId.has(body.parentId) ? (place(body.parentId), positions.get(body.parentId)) : null;
    const pos = computeOffset(body);
    if (parentPos) pos.add(parentPos);
    positions.set(id, pos);
    seen.add(id);
    loopGuard.delete(id);
  };
  universe.bodies.forEach((b) => place(b.id));
  return positions;
}

function updatePreview(dt) {
  if (!previewPaused) {
    universe.bodies.forEach((body) => {
      body.orbit.phase += (Number(body.orbit.orbitSpeed) || 0) * dt * 60 * previewSpeed;
      body._spinAngle = (body._spinAngle || 0) + (Number(body.rotation.spinSpeed) || 0) * dt * 60 * previewSpeed;
    });
  }
  const positions = computeBodyPositions();
  universe.bodies.forEach((body) => {
    syncOrbitLine(body);
    const mesh = meshById.get(body.id);
    if (!mesh) return;
    const pos = positions.get(body.id) || new THREE.Vector3();
    mesh.position.copy(pos);
    mesh.rotation.set(0, 0, 0);
    mesh.rotateZ(Number(body.rotation.axialTilt) || 0);
    mesh.rotateY(body._spinAngle || 0);
    mesh.scale.setScalar(body.id === selectedId ? 1.08 : 1);
    const orbit = orbitById.get(body.id);
    if (orbit) {
      const parentPos = body.parentId ? positions.get(body.parentId) : new THREE.Vector3();
      orbit.position.copy(parentPos || new THREE.Vector3());
    }
  });
}

function makeOrbitLine(body) {
  const r = Math.max(10, Number(body.orbit.semiMajor) || 0);
  const i = Number(body.orbit.inclination) || 0;
  const points = [];
  for (let step = 0; step <= 96; step += 1) {
    const a = (step / 96) * Math.PI * 2;
    const x = Math.cos(a) * r;
    const y = Math.sin(a) * r * Math.sin(i);
    const z = Math.sin(a) * r * Math.cos(i);
    points.push(new THREE.Vector3(x, y, z));
  }
  const color = 0xffea00;
  const geometry = new THREE.BufferGeometry().setFromPoints(points);
  const line = new THREE.LineLoop(geometry, new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.45 }));
  line.userData.orbitRadius = r;
  line.userData.orbitInclination = i;
  return line;
}

function syncOrbitLine(body) {
  const shouldShow = (Number(body.orbit.semiMajor) || 0) > 0;
  let orbit = orbitById.get(body.id);
  if (!shouldShow) {
    if (!orbit) return;
    orbitGroup.remove(orbit);
    orbit.geometry.dispose();
    orbit.material.dispose();
    orbitById.delete(body.id);
    return;
  }

  if (!orbit) {
    orbit = makeOrbitLine(body);
    orbitGroup.add(orbit);
    orbitById.set(body.id, orbit);
    return;
  }

  const radius = Math.max(10, Number(body.orbit.semiMajor) || 0);
  const inclination = Number(body.orbit.inclination) || 0;
  /** Runs every frame — only rebuild the ring buffer when the orbit actually changed. */
  if (orbit.userData.orbitRadius === radius && orbit.userData.orbitInclination === inclination) return;
  const points = [];
  for (let step = 0; step <= 96; step += 1) {
    const a = (step / 96) * Math.PI * 2;
    points.push(
      new THREE.Vector3(
        Math.cos(a) * radius,
        Math.sin(a) * radius * Math.sin(inclination),
        Math.sin(a) * radius * Math.cos(inclination)
      )
    );
  }
  orbit.geometry.setFromPoints(points);
  orbit.userData.orbitRadius = radius;
  orbit.userData.orbitInclination = inclination;
}

function buildScene() {
  meshById.clear();
  orbitById.clear();
  /** Rebuilt on every edit — dispose GPU resources of the previous scene or they leak. */
  const disposeChild = (c) => {
    if (c.geometry) c.geometry.dispose();
    if (c.material) c.material.dispose();
  };
  while (bodiesGroup.children.length) {
    const c = bodiesGroup.children[0];
    bodiesGroup.remove(c);
    disposeChild(c);
  }
  while (orbitGroup.children.length) {
    const c = orbitGroup.children[0];
    orbitGroup.remove(c);
    disposeChild(c);
  }

  universe.bodies.forEach((body) => {
    const radius = Math.max(10, Number(body.radius) || 1000);
    const segments = body.type === "sun" ? 28 : 22;
    const geometry = new THREE.SphereGeometry(radius, segments, segments);
    const material =
      body.type === "sun"
        ? new THREE.MeshBasicMaterial({ color: Number(body.color) || 0xffaa00 })
        : new THREE.MeshStandardMaterial({ color: Number(body.color) || 0x88bbff, roughness: 0.8, metalness: 0.08 });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.userData.bodyId = body.id;
    bodiesGroup.add(mesh);
    meshById.set(body.id, mesh);

    if ((Number(body.orbit.semiMajor) || 0) > 0) {
      const orbit = makeOrbitLine(body);
      orbitGroup.add(orbit);
      orbitById.set(body.id, orbit);
    }
  });
}

function isDescendant(targetId, possibleAncestorId) {
  let probe = bodyById(targetId);
  const maxDepth = universe.bodies.length + 1;
  for (let idx = 0; idx < maxDepth && probe; idx += 1) {
    if (probe.parentId === possibleAncestorId) return true;
    if (!probe.parentId) return false;
    probe = bodyById(probe.parentId);
  }
  return false;
}

function refreshParentOptions() {
  const body = bodyById(selectedId);
  if (!body) return;
  fieldEls.parentId.innerHTML = "";
  const rootOpt = document.createElement("option");
  rootOpt.value = "";
  rootOpt.textContent = "(no parent)";
  fieldEls.parentId.appendChild(rootOpt);
  universe.bodies.forEach((candidate) => {
    if (candidate.id === body.id) return;
    if (isDescendant(candidate.id, body.id)) return;
    const opt = document.createElement("option");
    opt.value = candidate.id;
    opt.textContent = `${candidate.name} (${candidate.id})`;
    fieldEls.parentId.appendChild(opt);
  });
  fieldEls.parentId.value = body.parentId || "";
}

function refreshInspector() {
  const body = bodyById(selectedId);
  if (!body) {
    emptyEl.classList.remove("hidden");
    fieldsEl.classList.add("hidden");
    return;
  }
  emptyEl.classList.add("hidden");
  fieldsEl.classList.remove("hidden");
  fieldEls.name.value = body.name || "";
  fieldEls.type.value = body.type || "planet";
  fieldEls.radius.value = String(body.radius);
  fieldEls.color.value = `0x${Number(body.color || 0xffffff).toString(16)}`;
  fieldEls.surfaceGravity.value = String(body.surfaceGravity ?? 0);
  fieldEls.orbitSemi.value = String(body.orbit.semiMajor ?? 0);
  fieldEls.orbitInc.value = String(body.orbit.inclination ?? 0);
  fieldEls.orbitPhase.value = String(body.orbit.phase ?? 0);
  fieldEls.orbitSpeed.value = String(body.orbit.orbitSpeed ?? 0);
  fieldEls.rotSpin.value = String(body.rotation.spinSpeed ?? 0);
  fieldEls.rotTilt.value = String(body.rotation.axialTilt ?? 0);
  refreshParentOptions();
}

function rebuildAndRefresh() {
  buildScene();
  refreshInspector();
}

function applySelectedEdits() {
  const body = bodyById(selectedId);
  if (!body) return;
  pushUndoSnapshot();
  body.name = fieldEls.name.value.trim() || body.id;
  body.type = fieldEls.type.value;
  body.parentId = fieldEls.parentId.value || null;
  body.radius = Math.max(10, Number(fieldEls.radius.value) || body.radius);
  {
    /** `|| body.color` treated black (0) as “invalid” and silently reverted it. */
    const parsedColor = Number(fieldEls.color.value);
    if (Number.isFinite(parsedColor)) body.color = parsedColor;
  }
  body.surfaceGravity = Math.max(0, Number(fieldEls.surfaceGravity.value) || 0);
  body.orbit.semiMajor = Math.max(0, Number(fieldEls.orbitSemi.value) || 0);
  body.orbit.inclination = Number(fieldEls.orbitInc.value) || 0;
  body.orbit.phase = Number(fieldEls.orbitPhase.value) || 0;
  body.orbit.orbitSpeed = Number(fieldEls.orbitSpeed.value) || 0;
  body.rotation.spinSpeed = Number(fieldEls.rotSpin.value) || 0;
  body.rotation.axialTilt = Number(fieldEls.rotTilt.value) || 0;
  rebuildAndRefresh();
}

function pickBodyFromPointer(ev) {
  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const hits = raycaster.intersectObjects([...meshById.values()]);
  if (hits.length === 0) return null;
  return hits[0].object.userData.bodyId || null;
}

function pointerToGround(ev) {
  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const out = new THREE.Vector3();
  return raycaster.ray.intersectPlane(plane, out) ? out : null;
}

renderer.domElement.addEventListener("pointerdown", (ev) => {
  if (ev.button !== 0) return;
  const bodyId = pickBodyFromPointer(ev);
  if (!bodyId) return;
  selectedId = bodyId;
  refreshInspector();
  dragActive = true;
  dragStartSnapshot = universeSnapshot();
  controls.enabled = false;
});

window.addEventListener("pointerup", () => {
  if (dragActive && dragStartSnapshot !== universeSnapshot()) pushUndoSnapshot(dragStartSnapshot);
  dragActive = false;
  dragStartSnapshot = "";
  controls.enabled = true;
});

window.addEventListener("pointermove", (ev) => {
  if (!dragActive || !selectedId) return;
  const body = bodyById(selectedId);
  if (!body) return;
  const point = pointerToGround(ev);
  if (!point) return;
  let parentPos = new THREE.Vector3(0, 0, 0);
  if (body.parentId) {
    const parentMesh = meshById.get(body.parentId);
    if (parentMesh) parentPos = parentMesh.position.clone();
  }
  const rel = point.clone().sub(parentPos);
  const radius = Math.max(0, Math.sqrt(rel.x * rel.x + rel.z * rel.z));
  body.orbit.semiMajor = radius;
  body.orbit.phase = Math.atan2(rel.z, rel.x);
  body.orbit.inclination = 0;
  refreshInspector();
});

Object.values(fieldEls).forEach((el) => {
  el.addEventListener("change", applySelectedEdits);
});

nameEl.addEventListener("change", () => {
  universe.name = nameEl.value.trim() || "Custom Universe";
});

document.getElementById("btn-add-sun").addEventListener("click", () => {
  pushUndoSnapshot();
  universe.bodies.push({
    id: uniqueId("sun"),
    name: "New Sun",
    type: "sun",
    color: 0xffc46b,
    radius: 12000,
    terrainStyle: "gas",
    surfaceGravity: 0,
    parentId: null,
    orbit: { semiMajor: 0, eccentricity: 0, inclination: 0, phase: 0, orbitSpeed: 0 },
    rotation: { spinSpeed: 0.001, axialTilt: 0 },
  });
  rebuildAndRefresh();
});

document.getElementById("btn-add-planet").addEventListener("click", () => {
  pushUndoSnapshot();
  const firstSun = universe.bodies.find((b) => b.type === "sun");
  universe.bodies.push({
    id: uniqueId("planet"),
    name: "New Planet",
    type: "planet",
    color: 0x6da1ff,
    radius: 8000,
    terrainStyle: "rocky",
    surfaceGravity: 9.8,
    parentId: firstSun ? firstSun.id : null,
    orbit: { semiMajor: 60000, eccentricity: 0, inclination: 0, phase: 0.4, orbitSpeed: 0.00003 },
    rotation: { spinSpeed: 0.01, axialTilt: 0.05 },
  });
  rebuildAndRefresh();
});

document.getElementById("btn-delete").addEventListener("click", () => {
  if (!selectedId) return;
  pushUndoSnapshot();
  const deletingId = selectedId;
  universe.bodies = universe.bodies.filter((b) => b.id !== deletingId);
  universe.bodies.forEach((b) => {
    if (b.parentId === deletingId) b.parentId = null;
  });
  selectedId = null;
  rebuildAndRefresh();
});

previewToggleBtn.addEventListener("click", () => {
  previewPaused = !previewPaused;
  previewToggleBtn.textContent = previewPaused ? "Resume Preview" : "Pause Preview";
});

previewSpeedEl.addEventListener("change", () => {
  previewSpeed = Math.max(0, Number(previewSpeedEl.value) || 0);
});

document.getElementById("btn-export").addEventListener("click", () => {
  const normalized = normalizeUniverse(universe);
  const check = validateUniverse(normalized);
  if (!check.ok) {
    setStatus(check.errors.join(" "), true);
    return;
  }
  const blob = new Blob([serializeUniverse(normalized)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${(normalized.name || "universe").replace(/\s+/g, "_").toLowerCase()}.json`;
  a.click();
  URL.revokeObjectURL(url);
  setStatus("Universe exported.");
});

document.getElementById("btn-save-library").addEventListener("click", () => {
  try {
    const normalized = normalizeUniverse(universe);
    const id = upsertUniverseInLibrary(normalized, normalized.meta?.libraryId || "");
    universe.meta = { ...(universe.meta || {}), libraryId: id };
    setStatus("Universe saved to library.");
  } catch (err) {
    setStatus(err.message || "Could not save to library.", true);
  }
});

document.getElementById("btn-play").addEventListener("click", () => {
  try {
    const normalized = normalizeUniverse(universe);
    const id = upsertUniverseInLibrary(normalized, normalized.meta?.libraryId || "");
    normalized.meta = { ...(normalized.meta || {}), libraryId: id };
    universe.meta = { ...(universe.meta || {}), libraryId: id };
    saveUniverseToSession(normalized);
    window.location.href = "index.html";
  } catch (err) {
    setStatus(err.message || "Cannot play this universe.", true);
  }
});

window.addEventListener("keydown", (ev) => {
  if (!ev.ctrlKey || ev.repeat) return;
  const tag = (ev.target && ev.target.tagName ? String(ev.target.tagName) : "").toLowerCase();
  if (tag === "input" || tag === "textarea" || tag === "select") return;
  const key = ev.key.toLowerCase();
  if (key === "c") {
    ev.preventDefault();
    copySelectionToClipboard(false);
    return;
  }
  if (key === "x") {
    ev.preventDefault();
    copySelectionToClipboard(true);
    return;
  }
  if (key === "v") {
    ev.preventDefault();
    pasteClipboard();
    return;
  }
  if (key === "z") {
    ev.preventDefault();
    undoLastChange();
  }
});

document.getElementById("file-import").addEventListener("change", async (ev) => {
  const input = ev.target;
  const file = input.files && input.files[0];
  if (!file) return;
  try {
    const text = await file.text();
    universe = parseUniverse(text);
    nameEl.value = universe.name || "Custom Universe";
    selectedId = null;
    rebuildAndRefresh();
    setStatus(`Imported ${file.name}`);
  } catch (err) {
    setStatus(err.message || "Import failed.", true);
  } finally {
    input.value = "";
  }
});

function resize() {
  const rect = viewport.getBoundingClientRect();
  camera.aspect = rect.width / rect.height;
  camera.updateProjectionMatrix();
  renderer.setSize(rect.width, rect.height);
}
window.addEventListener("resize", resize);

let last = performance.now();
function animate() {
  requestAnimationFrame(animate);
  const now = performance.now();
  const dt = Math.min(0.1, (now - last) / 1000);
  last = now;
  updatePreview(dt);
  controls.update();
  renderer.render(scene, camera);
}

nameEl.value = universe.name || "Custom Universe";
buildScene();
refreshInspector();
resize();
animate();
