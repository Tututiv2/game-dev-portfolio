/* global window */
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";

let renderer;
let scene;
let camera;
let unitRoot;
let canvasEl;
/** @type {{ entity: object, unitType: string, url: string }[]} */
let frameList = [];
const loader = new GLTFLoader();
const modelCache = new Map();

function disposeObject3D(obj) {
  if (!obj) return;
  obj.traverse((o) => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      if (Array.isArray(o.material)) o.material.forEach((m) => m.dispose());
      else o.material.dispose();
    }
  });
}

function dirToYaw(dir) {
  const map = {
    N: Math.PI,
    NE: Math.PI * 0.75,
    E: Math.PI * 0.5,
    SE: Math.PI * 0.25,
    S: 0,
    SW: -Math.PI * 0.25,
    W: -Math.PI * 0.5,
    NW: -Math.PI * 0.75
  };
  return map[dir] != null ? map[dir] : 0;
}

/**
 * @param {string} dataUrl
 * @returns {Promise<THREE.Group>}
 */
function loadModelOnce(dataUrl) {
  if (!dataUrl) return Promise.resolve(new THREE.Group());
  if (modelCache.has(dataUrl)) return Promise.resolve(modelCache.get(dataUrl));
  return new Promise((resolve, reject) => {
    loader.load(
      dataUrl,
      (gltf) => {
        const root = gltf.scene;
        modelCache.set(dataUrl, root);
        resolve(root);
      },
      undefined,
      (err) => reject(err || new Error("GLTF load failed"))
    );
  });
}

function ensureRenderer(parent, w, h) {
  if (!renderer) {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "low-power" });
    renderer.setClearColor(0x000000, 0);
    canvasEl = renderer.domElement;
    canvasEl.id = "mission-webgl-overlay";
    canvasEl.className = "mission-webgl-overlay";
    canvasEl.style.cssText =
      "position:absolute;inset:0;width:100%;height:100%;display:block;pointer-events:none;z-index:2;";
  }
  if (canvasEl.parentElement !== parent) parent.appendChild(canvasEl);

  if (!scene) {
    scene = new THREE.Scene();
    unitRoot = new THREE.Group();
    scene.add(unitRoot);
    scene.add(new THREE.AmbientLight(0xffffff, 0.78));
    const dir = new THREE.DirectionalLight(0xfff0dc, 0.92);
    dir.position.set(140, 240, 120);
    scene.add(dir);
  }

  const rw = Math.max(4, w);
  const rh = Math.max(4, h);
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  renderer.setPixelRatio(dpr);
  renderer.setSize(rw, rh, false);

  const halfW = rw / 2;
  const halfH = rh / 2;
  if (!camera) {
    camera = new THREE.OrthographicCamera(-halfW, halfW, halfH, -halfH, 2, 12000);
    camera.position.set(0, 0, 900);
    camera.lookAt(0, 0, 0);
  } else {
    camera.left = -halfW;
    camera.right = halfW;
    camera.top = halfH;
    camera.bottom = -halfH;
    camera.updateProjectionMatrix();
  }
  return { halfW, halfH };
}

window.MissionThreeLayer = {
  beginFrame() {
    frameList = [];
  },

  enqueueUnit(entity, unitType, _isEnemy) {
    const reg = window.MODEL_REGISTRY?.byUnitType?.[unitType];
    if (!reg?.gltfDataUrl) return;
    frameList.push({ entity, unitType, url: reg.gltfDataUrl });
  },

  renderFrame(hooks) {
    const canvas2d = hooks.getCanvas?.();
    const mission = hooks.getMissionState?.();
    if (!canvas2d || !mission) {
      return;
    }
    if (!frameList.length) {
      if (renderer && scene && camera) {
        ensureRenderer(canvas2d.parentElement, canvas2d.width, canvas2d.height);
        while (unitRoot.children.length) {
          const o = unitRoot.children.pop();
          disposeObject3D(o);
        }
        renderer.setClearColor(0x000000, 0);
        renderer.clear();
      }
      return;
    }

    const parent = canvas2d.parentElement;
    if (!parent) return;
    const { halfW, halfH } = ensureRenderer(parent, canvas2d.width, canvas2d.height);

    while (unitRoot.children.length) {
      const o = unitRoot.children.pop();
      disposeObject3D(o);
    }

    for (let i = 0; i < frameList.length; i += 1) {
      const item = frameList[i];
      const src = modelCache.get(item.url);
      if (!src) continue;
      const clone = src.clone(true);
      const s = 0.72;
      clone.scale.set(s, s, s);
      const dir = hooks.getEntityDirection?.(item.entity) || "S";
      clone.rotation.y = dirToYaw(dir);
      const pos = hooks.entityFootCanvas?.(item.entity);
      if (!pos) continue;
      clone.position.set(pos.x - halfW, -(pos.y - halfH), 0);
      unitRoot.add(clone);
    }

    renderer.render(scene, camera);
  },

  async preloadFromRegistry() {
    const reg = window.MODEL_REGISTRY?.byUnitType || {};
    const urls = [...new Set(Object.values(reg).map((r) => r?.gltfDataUrl).filter(Boolean))];
    await Promise.all(urls.map((u) => loadModelOnce(u).catch(() => null)));
  },

  dispose() {
    if (renderer) {
      try {
        renderer.dispose();
      } catch {
        /* ignore */
      }
    }
    renderer = null;
    if (canvasEl && canvasEl.parentNode) canvasEl.parentNode.removeChild(canvasEl);
    canvasEl = null;
    scene = null;
    unitRoot = null;
    camera = null;
    frameList = [];
    modelCache.clear();
  }
};
