import * as THREE from 'three';

/**
 * @typedef {{
 *   enabled: boolean;
 *   minBranches: number;
 *   maxBranches: number;
 *   segmentCount: number;
 *   previewSeed?: number;
 * }} TreeBranchSettings
 */

export function defaultTreeBranchSettings() {
  return {
    enabled: true,
    minBranches: 2,
    maxBranches: 4,
    segmentCount: 3,
    previewSeed: undefined,
  };
}

/**
 * @param {unknown} raw
 * @returns {TreeBranchSettings}
 */
export function normalizeTreeBranchSettings(raw) {
  const d = defaultTreeBranchSettings();
  if (!raw || typeof raw !== 'object') return { ...d, previewSeed: undefined };
  const o = /** @type {Record<string, unknown>} */ (raw);
  const enabled = o.enabled === false ? false : true;
  let minBranches = Math.round(Number(o.minBranches));
  let maxBranches = Math.round(Number(o.maxBranches));
  if (!Number.isFinite(minBranches)) minBranches = d.minBranches;
  if (!Number.isFinite(maxBranches)) maxBranches = d.maxBranches;
  minBranches = Math.min(6, Math.max(2, minBranches));
  maxBranches = Math.min(6, Math.max(2, maxBranches));
  if (maxBranches < minBranches) [minBranches, maxBranches] = [maxBranches, minBranches];
  let segmentCount = Math.round(Number(o.segmentCount));
  if (!Number.isFinite(segmentCount)) segmentCount = d.segmentCount;
  segmentCount = Math.min(6, Math.max(2, segmentCount));
  let previewSeed = Number(o.previewSeed);
  if (!Number.isFinite(previewSeed)) previewSeed = undefined;
  else previewSeed = previewSeed >>> 0;
  return { enabled, minBranches, maxBranches, segmentCount, previewSeed };
}

export function hashTreeBranchSeed(treeId, x, z) {
  const tid = Number(treeId) | 0;
  const xf = Number(x) || 0;
  const zf = Number(z) || 0;
  let h = (tid * 2654435761) ^ (Math.floor(xf * 1000) * 374761393) ^ (Math.floor(zf * 1000) * 668265263);
  return h >>> 0;
}

/** @param {number} seed */
export function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    let t = (a += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function disposeBranchHolder(holder) {
  if (!holder) return;
  holder.traverse((o) => {
    if (o.isMesh) {
      o.geometry?.dispose?.();
    }
  });
  holder.clear();
}

/** Game uses this when stripping the whole procedural crown without re-running apply. */
export function disposeProceduralBranchHolder(holder) {
  disposeBranchHolder(holder);
}

/**
 * @returns {{ halfH: number; halfW: number; girth: number }}
 * `girth` ≈ trunk cross-section (min box side or cylinder diameter) — branch thickness matches this.
 */
export function trunkBodyDims(trunkMesh) {
  const g = trunkMesh.geometry;
  const p = g?.parameters;
  if (g?.type === 'BoxGeometry' && p) {
    const halfH = p.height / 2;
    const hw = p.width / 2;
    const hd = p.depth / 2;
    const halfW = Math.max(hw, hd);
    const girth = Math.min(p.width, p.depth);
    return { halfH, halfW, girth };
  }
  if (g?.type === 'CylinderGeometry' && p) {
    const r = Math.max(p.radiusTop, p.radiusBottom);
    const d = 2 * r;
    return { halfH: p.height / 2, halfW: r, girth: d };
  }
  return { halfH: 1.1, halfW: 0.12, girth: 0.22 };
}

function barkMaterialFromTrunk(trunkMesh) {
  const m = trunkMesh.material;
  if (Array.isArray(m) && m[0]) return m[0];
  return m;
}

/** Shared tiny foliage material (not disposed with branch holder). */
let _foliageMat = null;
function foliageMaterial() {
  if (!_foliageMat) {
    _foliageMat = new THREE.MeshStandardMaterial({
      color: 0x2d6b2d,
      roughness: 0.91,
      metalness: 0.04,
    });
  }
  return _foliageMat;
}

const _up = new THREE.Vector3(0, 1, 0);
const _tmpDir = new THREE.Vector3();
const _tmpAx = new THREE.Vector3();
const _tmpAz = new THREE.Vector3();
const _tmpTip = new THREE.Vector3();
const _tmpPos = new THREE.Vector3();
const _subU = new THREE.Vector3();
const _subV = new THREE.Vector3();
const _subDir = new THREE.Vector3();

/**
 * Small leaf-like boxes around the **rim** of a branch segment’s top face (terminal twigs).
 * @param {THREE.Group} branchRoot
 * @param {THREE.Mesh} lastMesh
 * @param {() => number} rng
 * @param {number} [leafScale=1]
 */
function addTipFoliage(branchRoot, lastMesh, rng, leafScale = 1) {
  const p = lastMesh.geometry?.parameters;
  if (!p || lastMesh.geometry?.type !== 'BoxGeometry') return;

  const halfLen = p.height / 2;
  _tmpDir.set(0, 1, 0).applyQuaternion(lastMesh.quaternion).normalize();
  _tmpTip.copy(lastMesh.position).addScaledVector(_tmpDir, halfLen);

  _tmpAx.set(1, 0, 0).applyQuaternion(lastMesh.quaternion).normalize();
  _tmpAz.set(0, 0, 1).applyQuaternion(lastMesh.quaternion).normalize();

  const fol = foliageMaterial();
  const n = 2 + Math.floor(rng() * 2);
  for (let i = 0; i < n; i++) {
    const t = (i / n) * Math.PI * 2 + rng() * 0.35;
    const rim = 0.38 + rng() * 0.08;
    _tmpPos
      .copy(_tmpTip)
      .addScaledVector(_tmpAx, Math.cos(t) * p.width * rim)
      .addScaledVector(_tmpAz, Math.sin(t) * p.depth * rim);

    const sx = (0.052 + rng() * 0.065) * leafScale;
    const sy = (0.016 + rng() * 0.024) * leafScale;
    const sz = (0.065 + rng() * 0.078) * leafScale;
    const geo = new THREE.BoxGeometry(sx * 2, sy * 2, sz * 2);
    const leaf = new THREE.Mesh(geo, fol);
    leaf.name = `branch_tip_leaf_${i}`;
    leaf.userData.excludeFromPrefabSerialize = true;
    leaf.castShadow = false;
    leaf.receiveShadow = true;
    leaf.position.copy(_tmpPos);
    leaf.quaternion.copy(lastMesh.quaternion);
    leaf.rotation.x += (rng() - 0.5) * 0.85;
    leaf.rotation.y += (rng() - 0.5) * 1.1;
    leaf.rotation.z += (rng() - 0.5) * 0.75;
    branchRoot.add(leaf);
  }
}

/**
 * Short thinner twigs from a parent branch tip; caller adds leaves on returned meshes.
 * @param {THREE.Group} branchRoot
 * @param {() => number} rng
 * @param {number} girth
 * @param {THREE.Material} mat
 * @param {THREE.Vector3} tip parent tip in branchRoot space
 * @param {THREE.Vector3} parentDir normalized parent growth axis
 * @param {number} parentTwLen
 * @param {number} parentTh
 * @param {string} namePrefix
 * @returns {THREE.Mesh[]}
 */
function addTerminalSubTwigs(branchRoot, rng, girth, mat, tip, parentDir, parentTwLen, parentTh, namePrefix) {
  const n = 2 + Math.floor(rng() * 3);
  const terminals = [];
  if (Math.abs(parentDir.y) < 0.9) _subU.set(0, 1, 0).cross(parentDir).normalize();
  else _subU.set(1, 0, 0).cross(parentDir).normalize();
  _subV.copy(parentDir).cross(_subU).normalize();

  const terminalScale = 1.15;
  for (let j = 0; j < n; j++) {
    const ta = rng() * Math.PI * 2;
    const fan = 0.36 + rng() * 0.5;
    _subDir
      .copy(parentDir)
      .addScaledVector(_subU, Math.cos(ta) * fan)
      .addScaledVector(_subV, Math.sin(ta) * fan);
    _subDir.y += (rng() - 0.32) * 0.38;
    if (_subDir.lengthSq() < 1e-8) _subDir.copy(parentDir);
    _subDir.normalize();

    const subLen = parentTwLen * (0.42 + rng() * 0.32) * terminalScale;
    const subTh = Math.max(girth * 0.028 * terminalScale, parentTh * (0.36 + rng() * 0.18) * terminalScale);
    const geo = new THREE.BoxGeometry(subTh, subLen, subTh);
    const sm = new THREE.Mesh(geo, mat);
    sm.name = `${namePrefix}_sub_${j}`;
    sm.userData.excludeFromPrefabSerialize = true;
    sm.castShadow = true;
    sm.receiveShadow = true;
    sm.quaternion.setFromUnitVectors(_up, _subDir);
    sm.position.copy(tip).addScaledVector(_subDir, subLen * 0.5);
    branchRoot.add(sm);
    terminals.push(sm);
  }
  return terminals;
}

/**
 * Crown on the **main trunk mesh**: 2+ thick columns from the treetop (two boxes each, with raised
 * length floors). Each column’s axis is random: mostly **up**, **radial horizontal**, or **tangential**.
 * Twigs attach on the **end** of the second box; one optional twig may appear on the main bark.
 * `minBranches`/`maxBranches` = crown column count (2–6); `segmentCount` = twigs per column (2–6).
 * @param {THREE.Mesh} trunkMesh
 * @param {TreeBranchSettings} settings
 * @param {number} seed
 */
function addTwigsFromSecondTrunkTip(crownRoot, rng, girth, len0, len1, limbThick, mat, count) {
  const attach = new THREE.Vector3();
  const twigDir = new THREE.Vector3();
  const tipY = len0 + len1;
  const baseTh = girth * (0.13 + rng() * 0.1) * 4.92;
  for (let i = 0; i < count; i++) {
    attach.set(
      (rng() - 0.5) * limbThick * 0.78,
      tipY - 0.018,
      (rng() - 0.5) * limbThick * 0.78
    );
    const ang = rng() * Math.PI * 2;
    const lift = 0.05 + rng() * 0.42;
    twigDir.set(Math.cos(ang) * 0.92, lift, Math.sin(ang) * 0.92);
    if (twigDir.lengthSq() < 1e-6) twigDir.set(0.35, 0.45, 0.2);
    twigDir.normalize();

    const twLen = girth * (0.34 + rng() * 0.62) * 6.32;
    const taper = Math.max(0.64, 1 - (i / Math.max(1, count - 1)) * (0.28 + rng() * 0.08));
    const twTh = Math.max(girth * 0.085, baseTh * taper * (0.94 + rng() * 0.1));
    const geo = new THREE.BoxGeometry(twTh, twLen, twTh);
    const mesh = new THREE.Mesh(geo, mat);
    mesh.name = `crown_twig_${i}`;
    mesh.userData.excludeFromPrefabSerialize = true;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.quaternion.setFromUnitVectors(_up, twigDir);
    mesh.position.copy(attach).addScaledVector(twigDir, twLen * 0.5);
    crownRoot.add(mesh);

    _tmpTip.copy(attach).addScaledVector(twigDir, twLen);
    const terminals = addTerminalSubTwigs(
      crownRoot,
      rng,
      girth,
      mat,
      _tmpTip,
      twigDir,
      twLen,
      twTh,
      `crown_twig_${i}`
    );
    for (let k = 0; k < terminals.length; k++) {
      addTipFoliage(crownRoot, terminals[k], rng, 0.72 + rng() * 0.22);
    }
  }
}

/** ~70% chance: one twig from the main standing trunk mesh (trunk local space). */
function addOptionalMainTrunkTwig(holder, rng, girth, halfH, halfW, mat) {
  if (rng() > 0.72) return;
  const phi = rng() * Math.PI * 2;
  const ty = -halfH * 0.22 + rng() * halfH * 1.12;
  const r = Math.max(0.045, Math.min(halfW, girth * 0.52) * 0.97);
  const attach = new THREE.Vector3(Math.cos(phi) * r, ty, Math.sin(phi) * r);
  const twigDir = new THREE.Vector3(Math.cos(phi) * 0.88, 0.14 + rng() * 0.32, Math.sin(phi) * 0.88);
  if (twigDir.lengthSq() < 1e-6) twigDir.set(1, 0.2, 0);
  twigDir.normalize();

  const twLen = girth * (0.32 + rng() * 0.55) * 5.95;
  const twTh = Math.max(girth * 0.08, girth * (0.11 + rng() * 0.09) * 4.55);
  const geo = new THREE.BoxGeometry(twTh, twLen, twTh);
  const mesh = new THREE.Mesh(geo, mat);
  mesh.name = 'main_trunk_twig';
  mesh.userData.excludeFromPrefabSerialize = true;
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  mesh.quaternion.setFromUnitVectors(_up, twigDir);
  mesh.position.copy(attach).addScaledVector(twigDir, twLen * 0.5);
  holder.add(mesh);

  _tmpTip.copy(attach).addScaledVector(twigDir, twLen);
  const terminals = addTerminalSubTwigs(holder, rng, girth, mat, _tmpTip, twigDir, twLen, twTh, 'main_trunk_twig');
  for (let k = 0; k < terminals.length; k++) {
    addTipFoliage(holder, terminals[k], rng, 0.7 + rng() * 0.24);
  }
}

export function applyProceduralTreeBranches(trunkMesh, settings, seed) {
  const prev = trunkMesh.getObjectByName('procedural_branches_holder');
  if (prev) {
    trunkMesh.remove(prev);
    disposeBranchHolder(prev);
  }
  const s = normalizeTreeBranchSettings(settings);
  if (!s.enabled) return;

  const rng = mulberry32(seed);
  const nMin = s.minBranches;
  const nMax = s.maxBranches;
  const crownCount = nMin + Math.floor(rng() * (nMax - nMin + 1));

  const { halfH, halfW, girth } = trunkBodyDims(trunkMesh);
  const mat = barkMaterialFromTrunk(trunkMesh);
  if (!mat) return;

  const holder = new THREE.Group();
  holder.name = 'procedural_branches_holder';
  holder.userData.excludeFromPrefabSerialize = true;

  const topInset = Math.max(0.012, girth * 0.035);
  const topY = halfH - topInset;
  const ringR = Math.max(halfW * 0.2, girth * 0.16);

  for (let c = 0; c < crownCount; c++) {
    const theta = (c / crownCount) * Math.PI * 2 + rng() * 0.22;
    const cos = Math.cos(theta);
    const sin = Math.sin(theta);
    const outward = new THREE.Vector3(cos, 0, sin).normalize();
    /** Horizontal tangent to the treetop ring (~90° from radial). */
    const tangent = new THREE.Vector3(-sin, 0, cos).normalize();

    /** Growth axis for the two stacked trunk boxes (local +Y of crownRoot). */
    const columnDir = new THREE.Vector3();
    const axisPick = rng();
    if (axisPick < 0.34) {
      /** Mostly vertical, slight lean outward. */
      columnDir.set(
        outward.x * (0.26 + rng() * 0.34),
        0.5 + rng() * 0.4,
        outward.z * (0.26 + rng() * 0.34)
      );
    } else if (axisPick < 0.66) {
      /** Largely horizontal — limb runs outward from the cap. */
      columnDir.copy(outward);
      columnDir.y = 0.04 + rng() * 0.16;
      columnDir.normalize();
    } else {
      /** Sideways around the tree (perpendicular to radial), with a tilt. */
      columnDir.copy(tangent);
      columnDir.y = -0.08 + rng() * 0.28;
      columnDir.normalize();
    }
    if (columnDir.lengthSq() < 1e-6) columnDir.set(0.2, 1, 0.15);
    columnDir.normalize();
    if (Math.abs(columnDir.y) > 0.998) {
      columnDir.x += 0.12;
      columnDir.normalize();
    }

    const crownRoot = new THREE.Group();
    crownRoot.name = `crown_${c}`;
    crownRoot.userData.excludeFromPrefabSerialize = true;
    crownRoot.position.set(cos * ringR * 0.42, topY, sin * ringR * 0.42);
    crownRoot.quaternion.setFromUnitVectors(_up, columnDir);

    const limbThick = Math.min(girth * 0.998, girth * (0.94 + rng() * 0.058));
    let len0 = Math.min(halfH * (0.42 + rng() * 0.34), Math.max(girth * 2.22, halfW * 2.55));
    len0 = Math.max(len0, girth * 2.05);
    let len1 = len0 * (0.98 + rng() * 0.11);
    len1 = Math.max(len1, girth * 1.98);

    const geo0 = new THREE.BoxGeometry(limbThick, len0, limbThick);
    const m0 = new THREE.Mesh(geo0, mat);
    m0.name = `crown_${c}_trunk0`;
    m0.userData.excludeFromPrefabSerialize = true;
    m0.castShadow = true;
    m0.receiveShadow = true;
    m0.position.set(0, len0 * 0.5, 0);
    crownRoot.add(m0);

    const geo1 = new THREE.BoxGeometry(limbThick, len1, limbThick);
    const m1 = new THREE.Mesh(geo1, mat);
    m1.name = `crown_${c}_trunk1`;
    m1.userData.excludeFromPrefabSerialize = true;
    m1.castShadow = true;
    m1.receiveShadow = true;
    m1.position.set(0, len0 + len1 * 0.5, 0);
    crownRoot.add(m1);

    addTwigsFromSecondTrunkTip(crownRoot, rng, girth, len0, len1, limbThick, mat, s.segmentCount);

    holder.add(crownRoot);
  }

  addOptionalMainTrunkTwig(holder, rng, girth, halfH, halfW, mat);

  trunkMesh.add(holder);
}
