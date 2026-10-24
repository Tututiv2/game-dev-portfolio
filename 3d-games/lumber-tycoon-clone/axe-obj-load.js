/**
 * Loads <code>models/files/axe.obj</code> (Roblox/Thingiverse export) for game + prefab preview.
 * Uses several URL bases and per-attempt timeout to avoid "forever loading" on bad URLs.
 */
import * as THREE from 'three';
import { OBJLoader } from 'three/addons/loaders/OBJLoader.js';

const OBJ_REL = 'models/files/axe.obj';
const TEX_REL = 'models/images/2fe3fc8c-a117-4d8b-b4a1-1dbd0b8a6902.png';

function candidateUrlsFor(relPath) {
  const out = [];
  try {
    out.push(new URL(relPath, import.meta.url).href);
  } catch (_) {}
  if (typeof window !== 'undefined' && window.location?.href) {
    try {
      out.push(new URL(relPath, window.location.href).href);
    } catch (_) {}
    try {
      out.push(new URL(`/${relPath}`, window.location.origin).href);
    } catch (_) {}
    const base = typeof document !== 'undefined' ? document.querySelector('base')?.href : '';
    if (base) {
      try {
        out.push(new URL(relPath, base).href);
      } catch (_) {}
    }
  }
  return [...new Set(out)];
}

async function fetchWithTimeout(href, ms = 5000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    return await fetch(href, { cache: 'no-store', signal: ctrl.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function fetchTextFirstOk(relPath) {
  const urls = candidateUrlsFor(relPath);
  let lastErr = new Error(`No URL candidates for ${relPath}`);
  for (const href of urls) {
    try {
      const res = await fetchWithTimeout(href);
      if (res.ok) return await res.text();
      lastErr = new Error(`${relPath} -> HTTP ${res.status} (${href})`);
    } catch (e) {
      lastErr = new Error(`${relPath} fetch failed (${href}): ${e?.message || e}`);
    }
  }
  throw lastErr;
}

function loadTextureFirstOk(relPath) {
  const urls = candidateUrlsFor(relPath);
  return new Promise((resolve, reject) => {
    const tryNext = (i) => {
      if (i >= urls.length) {
        reject(new Error(`Texture fetch failed for ${relPath}`));
        return;
      }
      new THREE.TextureLoader().load(
        urls[i],
        (tex) => resolve(tex),
        undefined,
        () => tryNext(i + 1)
      );
    };
    tryNext(0);
  });
}

/** Deep dispose (shared materials deduped). */
export function disposeAxeObjDeep(root) {
  if (!root) return;
  const matsDone = new Set();
  root.traverse((x) => {
    if (x.geometry) x.geometry.dispose();
    if (x.material) {
      const mats = Array.isArray(x.material) ? x.material : [x.material];
      for (const m of mats) {
        if (!m || matsDone.has(m)) continue;
        matsDone.add(m);
        if (m.map) m.map.dispose();
        if (m.dispose) m.dispose();
      }
    }
  });
}

/**
 * @param {{
 *   targetMaxDim?: number;
 *   castShadow?: boolean;
 *   receiveShadow?: boolean;
 *   tryTexture?: boolean;
 *   skipBloomMaterialSwap?: boolean;
 * }} [options]
 */
export async function loadAxeObjAsGroup(options = {}) {
  const {
    targetMaxDim = 0.52,
    castShadow = false,
    receiveShadow = false,
    tryTexture = false,
    skipBloomMaterialSwap = false,
  } = options;

  try {
  globalThis.LT_appendDebugLog?.(`[axe.obj] fetch ${OBJ_REL} (URLs: ${candidateUrlsFor(OBJ_REL).join(' | ')})`);

  const text = await fetchTextFirstOk(OBJ_REL);
  globalThis.LT_appendDebugLog?.(`[axe.obj] fetched ${text.length} chars, parsing…`);
  const obj = new OBJLoader().parse(text);
  obj.name = 'AxeObj';
  obj.userData.axeExternalObj = true;

  let meshCount = 0;
  obj.traverse((o) => {
    if (o.isMesh) meshCount++;
  });
  if (meshCount === 0) {
    throw new Error('axe.obj parsed but contained no meshes');
  }
  globalThis.LT_appendDebugLog?.(`[axe.obj] ${meshCount} mesh(es), centering & scale (target max dim ${targetMaxDim})`);

  const mat = new THREE.MeshStandardMaterial({
    color: 0x8f96a3,
    roughness: 0.52,
    metalness: 0.28,
    emissive: 0x1b1f28,
    emissiveIntensity: 0.65,
    side: THREE.DoubleSide,
  });

  if (tryTexture) {
    try {
      const tex = await loadTextureFirstOk(TEX_REL);
      tex.colorSpace = THREE.SRGBColorSpace;
      mat.map = tex;
      mat.color.setHex(0xffffff);
    } catch (e) {
      console.warn('axe-obj-load: texture optional load failed', e);
    }
  }

  obj.traverse((o) => {
    if (!o.isMesh) return;
    const prev = o.material;
    o.material = mat;
    o.castShadow = castShadow;
    o.receiveShadow = receiveShadow;
    o.frustumCulled = false;
    if (skipBloomMaterialSwap) o.userData.skipBloomMaterialSwap = true;
    for (const m of Array.isArray(prev) ? prev : [prev]) {
      if (m && m !== mat && m.dispose) m.dispose();
    }
  });

  /**
   * Roblox OBJ uses huge absolute vertex coords; moving only the root Group can disagree with
   * world bbox in some cases. Baking the offset into {@link BufferGeometry} guarantees origin.
   */
  const meshList = [];
  obj.traverse((o) => {
    if (o.isMesh && o.geometry) meshList.push(o);
  });
  obj.position.set(0, 0, 0);
  obj.scale.set(1, 1, 1);
  obj.quaternion.set(0, 0, 0, 1);
  obj.updateMatrixWorld(true);

  if (meshList.length === 1) {
    const geo = meshList[0].geometry;
    geo.computeBoundingBox();
    const lc = new THREE.Vector3();
    geo.boundingBox.getCenter(lc);
    geo.translate(-lc.x, -lc.y, -lc.z);
    if (geo.attributes.position) geo.attributes.position.needsUpdate = true;
    geo.computeBoundingBox();
    geo.computeBoundingSphere();
  } else {
    const box0 = new THREE.Box3().setFromObject(obj);
    if (box0.isEmpty()) throw new Error('axe.obj bounding box empty');
    const wc = box0.getCenter(new THREE.Vector3());
    meshList.forEach((m) => {
      const g = m.geometry;
      if (!g) return;
      const inv = new THREE.Matrix4().copy(m.matrixWorld).invert();
      const localDelta = wc.clone().applyMatrix4(inv);
      g.translate(-localDelta.x, -localDelta.y, -localDelta.z);
      if (g.attributes.position) g.attributes.position.needsUpdate = true;
      g.computeBoundingBox();
      g.computeBoundingSphere();
    });
  }

  obj.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(obj);
  if (box.isEmpty()) {
    throw new Error('axe.obj bounding box empty after bake');
  }
  const size = box.getSize(new THREE.Vector3());
  const maxDim = Math.max(size.x, size.y, size.z, 1e-6);
  const scale = targetMaxDim / maxDim;
  obj.scale.setScalar(scale);
  obj.updateMatrixWorld(true);
  const boxAfter = new THREE.Box3().setFromObject(obj);
  const drift = boxAfter.getCenter(new THREE.Vector3());
  obj.position.sub(drift);

  obj.traverse((o) => {
    if (o.isMesh && o.material?.map) o.material.map.colorSpace = THREE.SRGBColorSpace;
  });

  obj.updateMatrixWorld(true);
  const box2 = new THREE.Box3().setFromObject(obj);
  const c2 = box2.getCenter(new THREE.Vector3());
  const s2 = box2.getSize(new THREE.Vector3());
  globalThis.LT_appendDebugLog?.(
    `[axe.obj] bbox center=${c2.x.toFixed(3)},${c2.y.toFixed(3)},${c2.z.toFixed(3)} size=${s2.x.toFixed(3)},${s2.y.toFixed(3)},${s2.z.toFixed(3)}`
  );
  globalThis.LT_appendDebugLog?.('[axe.obj] done');
  return obj;
  } catch (e) {
    globalThis.LT_appendDebugLog?.('[axe.obj] ERROR: ' + (e?.message || e), 'error');
    throw e;
  }
}
