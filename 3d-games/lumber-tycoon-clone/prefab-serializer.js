/**
 * Freeform prefab save/load: mesh list with primitive geometry + local transform.
 * Storage: localStorage key LT2_PrefabEdits → { tree, conveyor, shelf, ramp, sawmill, axe, …custom }.
 */
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import {
  defaultSawmillConvertZoneDims,
  normalizeSawmillConvertZone,
  EDITOR_DEFAULT_SAWMILL_DIMS,
} from './sawmill-convert-zone.js';
import { normalizeTreeBranchSettings } from './tree-procedural-branches.js';

const STORAGE_KEY = 'LT2_PrefabEdits';

export function tryGetPrefabDataForKind(kind) {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const all = JSON.parse(raw);
    const data = all[kind];
    if (!data || !Array.isArray(data.parts)) return null;
    return data;
  } catch {
    return null;
  }
}

export function saveAllPrefabsToStorage(obj) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(obj, null, 2));
}

/** Removes one kind from <code>LT2_PrefabEdits</code> (same as clearing that entry in the editor). */
export function removePrefabKindFromStorage(kind) {
  const all = loadAllPrefabsFromStorage();
  delete all[kind];
  saveAllPrefabsToStorage(all);
}

const PREFAB_DEFAULTS = () => ({
  tree: null,
  treeSpook: null,
  treeVoid: null,
  plankWood: null,
  plankNeon: null,
  conveyor: null,
  shelf: null,
  ramp: null,
  sawmill: null,
  axe: null,
});

export function loadAllPrefabsFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return PREFAB_DEFAULTS();
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return PREFAB_DEFAULTS();
    return { ...PREFAB_DEFAULTS(), ...parsed };
  } catch {
    return PREFAB_DEFAULTS();
  }
}

function serializeGeometry(geo, mesh) {
  if (mesh?.userData?.prefabGeom?.type === 'rounded_box') {
    const g = mesh.userData.prefabGeom;
    return {
      type: 'rounded_box',
      width: g.width,
      height: g.height,
      depth: g.depth,
      segments: g.segments,
      radius: g.radius,
    };
  }
  const p = geo.parameters;
  const t = geo.type;
  if (t === 'BoxGeometry') {
    return { type: 'box', width: p.width, height: p.height, depth: p.depth };
  }
  if (t === 'CylinderGeometry') {
    return {
      type: 'cylinder',
      radiusTop: p.radiusTop,
      radiusBottom: p.radiusBottom,
      height: p.height,
      radialSegments: p.radialSegments,
      heightSegments: p.heightSegments,
    };
  }
  if (t === 'SphereGeometry') {
    return {
      type: 'sphere',
      radius: p.radius,
      widthSegments: p.widthSegments,
      heightSegments: p.heightSegments,
    };
  }
  if (t === 'ConeGeometry') {
    return {
      type: 'cone',
      radius: p.radius,
      height: p.height,
      radialSegments: p.radialSegments,
      heightSegments: p.heightSegments,
    };
  }
  if (t === 'TorusGeometry') {
    return {
      type: 'torus',
      radius: p.radius,
      tube: p.tube,
      radialSegments: p.radialSegments,
      tubularSegments: p.tubularSegments,
      arc: p.arc,
    };
  }
  if (t === 'RingGeometry') {
    return {
      type: 'ring',
      innerRadius: p.innerRadius,
      outerRadius: p.outerRadius,
      thetaSegments: p.thetaSegments,
      phiSegments: p.phiSegments,
    };
  }
  if (t === 'CircleGeometry') {
    return {
      type: 'circle',
      radius: p.radius,
      segments: p.segments,
      thetaStart: p.thetaStart,
      thetaLength: p.thetaLength,
    };
  }
  if (t === 'PlaneGeometry') {
    return {
      type: 'plane',
      width: p.width,
      height: p.height,
      widthSegments: p.widthSegments,
      heightSegments: p.heightSegments,
    };
  }
  if (t === 'CapsuleGeometry') {
    return {
      type: 'capsule',
      radius: p.radius,
      length: p.length,
      capSegments: p.capSegments,
      radialSegments: p.radialSegments,
    };
  }
  if (t === 'TetrahedronGeometry') {
    return { type: 'tetrahedron', radius: p.radius, detail: p.detail };
  }
  if (t === 'OctahedronGeometry') {
    return { type: 'octahedron', radius: p.radius, detail: p.detail };
  }
  if (t === 'DodecahedronGeometry') {
    return { type: 'dodecahedron', radius: p.radius, detail: p.detail };
  }
  if (t === 'IcosahedronGeometry') {
    return { type: 'icosahedron', radius: p.radius, detail: p.detail };
  }
  if (t === 'TorusKnotGeometry') {
    return {
      type: 'torus_knot',
      radius: p.radius,
      tube: p.tube,
      tubularSegments: p.tubularSegments,
      radialSegments: p.radialSegments,
      p: p.p,
      q: p.q,
    };
  }
  return null;
}

function primaryMaterial(mesh) {
  const m = mesh.material;
  return Array.isArray(m) ? m[0] : m;
}

function serializeMaterial(m) {
  const out = {
    color: m.color?.getHex?.() ?? 0x888888,
    roughness: m.roughness ?? 0.85,
    metalness: m.metalness ?? 0.05,
  };
  if (m.map?.image && typeof document !== 'undefined') {
    try {
      const img = m.map.image;
      const w = img.width;
      const h = img.height;
      if (w && h) {
        const c = document.createElement('canvas');
        c.width = w;
        c.height = h;
        const ctx = c.getContext('2d');
        if (ctx) {
          ctx.drawImage(img, 0, 0);
          out.mapDataUrl = c.toDataURL('image/png');
        }
      }
    } catch {
      /* skip non-serializable map */
    }
  }
  return out;
}

function applyMapDataUrlToMaterial(mat, dataUrl) {
  if (!dataUrl || !mat) return;
  const tex = new THREE.TextureLoader().load(dataUrl);
  tex.colorSpace = THREE.SRGBColorSpace;
  if (mat.map) mat.map.dispose();
  mat.map = tex;
  mat.needsUpdate = true;
}

function deserializeGeometry(spec) {
  if (!spec || !spec.type) return null;
  if (spec.type === 'box') {
    return new THREE.BoxGeometry(spec.width, spec.height, spec.depth);
  }
  if (spec.type === 'cylinder') {
    return new THREE.CylinderGeometry(
      spec.radiusTop,
      spec.radiusBottom,
      spec.height,
      spec.radialSegments ?? 12,
      spec.heightSegments ?? 1
    );
  }
  if (spec.type === 'sphere') {
    return new THREE.SphereGeometry(
      spec.radius,
      spec.widthSegments ?? 12,
      spec.heightSegments ?? 10
    );
  }
  if (spec.type === 'cone') {
    return new THREE.ConeGeometry(
      spec.radius,
      spec.height,
      spec.radialSegments ?? 12,
      spec.heightSegments ?? 1
    );
  }
  if (spec.type === 'torus') {
    return new THREE.TorusGeometry(
      spec.radius,
      spec.tube,
      spec.radialSegments ?? 12,
      spec.tubularSegments ?? 24,
      spec.arc ?? Math.PI * 2
    );
  }
  if (spec.type === 'ring') {
    return new THREE.RingGeometry(
      spec.innerRadius,
      spec.outerRadius,
      spec.thetaSegments ?? 16,
      spec.phiSegments ?? 1
    );
  }
  if (spec.type === 'circle') {
    return new THREE.CircleGeometry(
      spec.radius,
      spec.segments ?? 32,
      spec.thetaStart ?? 0,
      spec.thetaLength ?? Math.PI * 2
    );
  }
  if (spec.type === 'plane') {
    return new THREE.PlaneGeometry(
      spec.width,
      spec.height,
      spec.widthSegments ?? 1,
      spec.heightSegments ?? 1
    );
  }
  if (spec.type === 'capsule') {
    return new THREE.CapsuleGeometry(
      spec.radius,
      spec.length,
      spec.capSegments ?? 4,
      spec.radialSegments ?? 12
    );
  }
  if (spec.type === 'tetrahedron') {
    return new THREE.TetrahedronGeometry(spec.radius, spec.detail ?? 0);
  }
  if (spec.type === 'octahedron') {
    return new THREE.OctahedronGeometry(spec.radius, spec.detail ?? 0);
  }
  if (spec.type === 'dodecahedron') {
    return new THREE.DodecahedronGeometry(spec.radius, spec.detail ?? 0);
  }
  if (spec.type === 'icosahedron') {
    return new THREE.IcosahedronGeometry(spec.radius, spec.detail ?? 0);
  }
  if (spec.type === 'torus_knot') {
    return new THREE.TorusKnotGeometry(
      spec.radius,
      spec.tube,
      spec.tubularSegments ?? 64,
      spec.radialSegments ?? 8,
      spec.p ?? 2,
      spec.q ?? 3
    );
  }
  if (spec.type === 'rounded_box') {
    return new RoundedBoxGeometry(
      spec.width,
      spec.height,
      spec.depth,
      spec.segments ?? 4,
      spec.radius ?? 0.02
    );
  }
  return null;
}

/** In-game belt push is base × this (conveyor prefab only). */
function clampBeltPushMul(m) {
  const x = Number(m);
  if (!Number.isFinite(x)) return 1;
  return Math.min(4, Math.max(0.25, x));
}

/**
 * Serialize a prefab root Group: all Mesh descendants (flat), with world-space bounding size as base.
 */
export function serializePrefabGroup(root, kind) {
  root.updateMatrixWorld(true);
  const meshes = [];
  root.traverse((o) => {
    if (o.isMesh && !o.userData.excludeFromPrefabSerialize) meshes.push(o);
  });
  const box = new THREE.Box3();
  for (const mesh of meshes) {
    mesh.updateMatrixWorld(true);
    box.expandByObject(mesh);
  }
  const size = new THREE.Vector3();
  if (!box.isEmpty()) box.getSize(size);
  else size.set(0.35, 0.35, 0.35);
  const parts = [];
  for (const mesh of meshes) {
    const geom = serializeGeometry(mesh.geometry, mesh);
    if (!geom) continue;
    const m = primaryMaterial(mesh);
    parts.push({
      name: mesh.name || `part_${parts.length}`,
      geom,
      mat: serializeMaterial(m),
      pos: mesh.position.toArray(),
      rot: [mesh.rotation.x, mesh.rotation.y, mesh.rotation.z],
      scale: mesh.scale.toArray(),
    });
  }
  const bw = Math.max(0.01, size.x);
  const bh = Math.max(0.01, size.y);
  const bd = Math.max(0.01, size.z);
  const out = {
    version: 1,
    kind,
    base: { w: bw, h: bh, d: bd },
    parts,
  };
  if (kind === 'conveyor') {
    out.beltPushMul = clampBeltPushMul(root.userData?.beltPushMul ?? 1);
  }
  if (kind === 'sawmill') {
    const z = normalizeSawmillConvertZone(root.userData?.sawmillConvertZone);
    out.sawmillConvertZone = z || defaultSawmillConvertZoneDims(EDITOR_DEFAULT_SAWMILL_DIMS);
  }
  if (kind === 'tree' || kind === 'treeSpook' || kind === 'treeVoid') {
    out.treeBranches = normalizeTreeBranchSettings(root.userData?.treeBranches);
  }
  return out;
}

export function deserializePrefabGroup(data) {
  const g = new THREE.Group();
  g.name = 'PrefabRoot';
  if (!data.parts) return g;
  for (const p of data.parts) {
    const geo = deserializeGeometry(p.geom);
    if (!geo) continue;
    const mat = new THREE.MeshStandardMaterial({
      color: p.mat?.color ?? 0x888888,
      roughness: p.mat?.roughness ?? 0.85,
      metalness: p.mat?.metalness ?? 0.05,
    });
    if (p.mat?.mapDataUrl) {
      applyMapDataUrlToMaterial(mat, p.mat.mapDataUrl);
    }
    const mesh = new THREE.Mesh(geo, mat);
    mesh.name = p.name || 'part';
    if (p.geom?.type === 'rounded_box') {
      mesh.userData.prefabGeom = {
        type: 'rounded_box',
        width: p.geom.width,
        height: p.geom.height,
        depth: p.geom.depth,
        segments: p.geom.segments ?? 4,
        radius: p.geom.radius ?? 0.02,
      };
    }
    if (p.pos) mesh.position.fromArray(p.pos);
    if (p.rot) mesh.rotation.set(p.rot[0], p.rot[1], p.rot[2]);
    if (p.scale) mesh.scale.fromArray(p.scale);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    g.add(mesh);
  }
  if (data.base) {
    g.userData.prefabBase = { ...data.base };
  }
  if (data.kind === 'conveyor' && data.beltPushMul != null) {
    g.userData.beltPushMul = clampBeltPushMul(data.beltPushMul);
  }
  if (data.sawmillConvertZone) {
    const z = normalizeSawmillConvertZone(data.sawmillConvertZone);
    if (z) {
      const span = z.xMax - z.xMin;
      /** Old editor/game default (~3.81) — too tight for full-height box logs along X. */
      const legacySpan = 2.1 + 0.76 + 0.95;
      if (Math.abs(span - legacySpan) < 0.1) {
        g.userData.sawmillConvertZone = defaultSawmillConvertZoneDims(EDITOR_DEFAULT_SAWMILL_DIMS);
      } else {
        g.userData.sawmillConvertZone = z;
      }
    }
  }
  if (data.kind === 'tree' || data.kind === 'treeSpook' || data.kind === 'treeVoid') {
    g.userData.treeBranches = normalizeTreeBranchSettings(data.treeBranches);
  }
  return g;
}

/** World AABB center + half extents for static Cannon box (axis-aligned). */
export function computeWorldAabbCollider(grp) {
  grp.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(grp);
  const size = new THREE.Vector3();
  const center = new THREE.Vector3();
  box.getSize(size);
  box.getCenter(center);
  return {
    center: { x: center.x, y: center.y, z: center.z },
    half: { x: size.x / 2, y: size.y / 2, z: size.z / 2 },
  };
}
