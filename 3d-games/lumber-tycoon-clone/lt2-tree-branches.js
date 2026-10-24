/**
 * LT2-inspired layered tree: trunk stays {@link createTrunkSegment}; this builds
 * {@link procedural_branches_holder} with main branches, sub-branches, and leaf pads
 * so {@link attachTreeBranchColliders} can register wood boxes (leaf meshes excluded).
 *
 * Geometry sizes are driven by {@link LT2_TREE_DEFAULTS} multipliers in `cfg`.
 */
import * as THREE from 'three';
import * as BufferGeometryUtils from 'three/addons/utils/BufferGeometryUtils.js';
import { mulberry32, disposeProceduralBranchHolder, trunkBodyDims } from './tree-procedural-branches.js';
import { LT2_STUD, normalizeLt2Config } from './lt2-tree-config.js';
import { buildLeafPadMaterial } from './lt2-tree-textures.js';

const _up = new THREE.Vector3(0, 1, 0);
const _tipOff = new THREE.Vector3();

function barkMaterialFromTrunk(trunkMesh) {
  const m = trunkMesh.material;
  if (Array.isArray(m) && m[0]) return m[0];
  return m;
}

/**
 * Leaf-pad materials cached per `(texture, color)` key. Older revisions kept a single live
 * material and disposed-on-miss, which broke the InstancedMesh forest path: building one
 * prototype per preset (palm, oak, cherry, pine, default) needs FIVE simultaneous leaf
 * materials live in memory. Keying them stops the "disposed texture" flash when the player
 * lands on a planet whose biomes mix presets.
 *
 * Entries live for the lifetime of the tab — dense forests reuse them constantly, and the
 * memory cost is trivial (each entry is one `CanvasTexture` of ~16 KB + a `MeshStandardMaterial`).
 */
const _leafMats = /** @type {Map<string, THREE.Material>} */ (new Map());
function leafPadMaterial(hex, texture) {
  const color = hex || '#73d178';
  const tex = texture || 'solid';
  const key = `${tex}::${color}`;
  const hit = _leafMats.get(key);
  if (hit) return hit;
  const mat = buildLeafPadMaterial({ texture: tex, color });
  _leafMats.set(key, mat);
  return mat;
}

const _tmpMat4 = new THREE.Matrix4();
const _tmpScale = new THREE.Vector3(1, 1, 1);
const _tmpLeafQuat = new THREE.Quaternion();
const _tmpLeafEuler = new THREE.Euler();

/**
 * Bake a Box geometry with `(pos, quat)` into world-local space for a single large merged
 * BufferGeometry. Used by the merge path to flatten dozens of per-branch meshes into one.
 * Returns a fresh (disposable) BoxGeometry — caller disposes after merging.
 */
function bakeBoxGeometry(w, h, d, pos, quat) {
  const g = new THREE.BoxGeometry(w, h, d);
  _tmpMat4.compose(pos, quat, _tmpScale);
  g.applyMatrix4(_tmpMat4);
  return g;
}

/**
 * @param {THREE.Mesh} trunkMesh
 * @param {number} seed
 * @param {object} [cfgRaw] Optional config; falls back to normalized defaults.
 * @param {object} [opts]
 * @param {boolean} [opts.mergeMeshes=false]
 *   When `true`, all generated branch + leaf Box meshes are **merged into two buffers** — one
 *   bark geometry + one leaf geometry — so the procedural crown becomes just two draw calls
 *   instead of up to ~100. Only makes sense for scenery-grade procedural trees where per-branch
 *   colliders aren't attached anyway (chopping just falls the trunk, branches are visual only).
 */
export function applyLt2StyleTreeBranches(trunkMesh, seed, cfgRaw, opts) {
  const mergeMeshes = !!(opts && opts.mergeMeshes);
  const cfg = normalizeLt2Config(cfgRaw);
  const prev = trunkMesh.getObjectByName('procedural_branches_holder');
  if (prev) {
    trunkMesh.remove(prev);
    disposeProceduralBranchHolder(prev);
  }

  const rng = mulberry32(seed);
  const { halfH } = trunkBodyDims(trunkMesh);
  const mat = barkMaterialFromTrunk(trunkMesh);
  if (!mat) return;

  const leafMat = leafPadMaterial(cfg.leafColor, cfg.leafTexture);

  const holder = new THREE.Group();
  holder.name = 'procedural_branches_holder';
  holder.userData.excludeFromPrefabSerialize = true;
  /** Tag so {@link attachTreeBranchColliders} knows this holder is merged — it wouldn't find
   *  per-branch Box children to wrap in CANNON.Box anyway, but the flag makes intent explicit. */
  holder.userData.mergedMeshes = mergeMeshes;

  /** When merging, we collect transformed BoxGeometries into these pools and emit one merged
   *  mesh per pool at the end. When NOT merging, children are added to `holder` directly as
   *  before (preserves the old editor-visible, per-branch-colliderable path). */
  const barkPool = mergeMeshes ? [] : null;
  const leafPool = mergeMeshes ? [] : null;

  const topY = halfH - 0.02;
  const S = LT2_STUD;
  const branches = Math.max(1, cfg.mainCount | 0);
  /** Fan main branches around the full trunk (360°/N) instead of packing into a 45° wedge. */
  const mainStepDeg = 360 / branches;
  /** Per-tree random rotation offset so two trees with the same count don't face the same way. */
  const mainRotOffsetDeg = rng() * 360;

  const mainW = 0.65 * S * cfg.mainThickness;
  const mainH = 7.5 * S * cfg.mainLength;
  const mainD = 0.65 * S * cfg.mainThickness;
  const subW = 0.42 * S * cfg.subThickness;
  const subH = 4.5 * S * cfg.subLength;
  const subD = 0.42 * S * cfg.subThickness;
  const leafW = 4.5 * S * cfg.leafPadSize;
  const leafH = 0.85 * S * cfg.leafPadSize;
  const leafD = 4.5 * S * cfg.leafPadSize;

  const mainJitter = Math.max(0, cfg.mainAngleJitterDeg);
  const subJitter = Math.max(0, cfg.subAngleJitterDeg);
  const subMin = cfg.subMin;
  const subSpread = Math.max(0, cfg.subMax - cfg.subMin);
  const pitchCenterRad = (cfg.mainPitchDeg * Math.PI) / 180;
  const pitchJitterRad = (cfg.mainPitchJitterDeg * Math.PI) / 180;
  const subPitchCenterRad = (cfg.subPitchDeg * Math.PI) / 180;
  const subPitchJitterRad = (cfg.subPitchJitterDeg * Math.PI) / 180;
  /** Prevent downward plunge from large pitch jitter; droopy but never diving. */
  const PITCH_FLOOR = (-15 * Math.PI) / 180;
  const PITCH_CEIL = (85 * Math.PI) / 180;
  const clampPitch = (p) => Math.min(PITCH_CEIL, Math.max(PITCH_FLOOR, p));

  for (let i = 1; i <= branches; i++) {
    const angleDeg = mainRotOffsetDeg + mainStepDeg * (i - 1) + (rng() - 0.5) * mainJitter;
    const angleRad = (angleDeg * Math.PI) / 180;
    const pitch = clampPitch(pitchCenterRad + (rng() - 0.5) * 2 * pitchJitterRad);
    const cp = Math.cos(pitch);
    const sp = Math.sin(pitch);
    const dir = new THREE.Vector3(
      Math.sin(angleRad) * cp,
      sp,
      Math.cos(angleRad) * cp
    ).normalize();

    const mainQuat = new THREE.Quaternion().setFromUnitVectors(_up, dir);
    const mainPos = new THREE.Vector3(0, topY, 0).addScaledVector(dir, mainH * 0.5 + 0.06);
    if (mergeMeshes) {
      barkPool.push(bakeBoxGeometry(mainW, mainH, mainD, mainPos, mainQuat));
    } else {
      const geo = new THREE.BoxGeometry(mainW, mainH, mainD);
      const mesh = new THREE.Mesh(geo, mat);
      mesh.name = `lt2_branch_${i}`;
      mesh.userData.excludeFromPrefabSerialize = true;
      mesh.castShadow = true;
      mesh.receiveShadow = true;
      mesh.quaternion.copy(mainQuat);
      mesh.position.copy(mainPos);
      holder.add(mesh);
    }

    _tipOff.set(0, mainH * 0.5, 0).applyQuaternion(mainQuat);
    const branchTip = mainPos.clone().add(_tipOff);

    const nSub = subMin + Math.floor(rng() * (subSpread + 1));
    const subStepDeg = 360 / Math.max(1, nSub);
    /** Start the sub-fan roughly along the parent branch's horizontal bearing so subs reach outward, not back toward the trunk. */
    const subRotOffsetDeg = angleDeg + rng() * subStepDeg;
    for (let j = 1; j <= nSub; j++) {
      const ang2deg = subRotOffsetDeg + subStepDeg * (j - 1) + (rng() - 0.5) * subJitter;
      const ang2 = (ang2deg * Math.PI) / 180;
      const subPitch = clampPitch(subPitchCenterRad + (rng() - 0.5) * 2 * subPitchJitterRad);
      const sCp = Math.cos(subPitch);
      const sSp = Math.sin(subPitch);
      const dir2 = new THREE.Vector3(
        Math.sin(ang2) * sCp + (rng() - 0.5) * 0.18,
        sSp,
        Math.cos(ang2) * sCp + (rng() - 0.5) * 0.18
      ).normalize();

      const subQuat = new THREE.Quaternion().setFromUnitVectors(_up, dir2);
      const subPos = branchTip.clone().addScaledVector(dir2, subH * 0.5 + 0.05);
      if (mergeMeshes) {
        barkPool.push(bakeBoxGeometry(subW, subH, subD, subPos, subQuat));
      } else {
        const g2 = new THREE.BoxGeometry(subW, subH, subD);
        const sm = new THREE.Mesh(g2, mat);
        sm.name = `lt2_sub_${i}_${j}`;
        sm.userData.excludeFromPrefabSerialize = true;
        sm.castShadow = true;
        sm.receiveShadow = true;
        sm.quaternion.copy(subQuat);
        sm.position.copy(subPos);
        holder.add(sm);
      }

      _tipOff.set(0, subH * 0.5, 0).applyQuaternion(subQuat);
      const subTip = subPos.clone().add(_tipOff);

      const nLeaves = Math.max(0, cfg.leavesPerSub | 0);
      const leafAngleStep = Math.max(1, Math.floor(15 / Math.max(1, nLeaves)));
      for (let k = 1; k <= nLeaves; k++) {
        const yaw = (leafAngleStep * k * Math.PI) / 180 + rng() * 0.9;
        const leafPos = new THREE.Vector3(
          subTip.x + (rng() - 0.5) * 0.25,
          subTip.y + leafH * 0.35,
          subTip.z + (rng() - 0.5) * 0.25,
        );
        _tmpLeafEuler.set(
          (rng() - 0.5) * 0.55 + (leafAngleStep * k * Math.PI) / 180 / 3,
          yaw,
          (rng() - 0.5) * 0.55,
        );
        _tmpLeafQuat.setFromEuler(_tmpLeafEuler);
        if (mergeMeshes) {
          leafPool.push(bakeBoxGeometry(leafW, leafH, leafD, leafPos, _tmpLeafQuat));
        } else {
          const lg = new THREE.BoxGeometry(leafW, leafH, leafD);
          const leaf = new THREE.Mesh(lg, leafMat);
          leaf.name = `branch_tip_leaf_lt2_${i}_${j}_${k}`;
          leaf.userData.excludeFromPrefabSerialize = true;
          leaf.castShadow = true;
          leaf.receiveShadow = true;
          leaf.position.copy(leafPos);
          leaf.quaternion.copy(_tmpLeafQuat);
          holder.add(leaf);
        }
      }
    }
  }

  /**
   * Finalize the merge path: take the two per-tree BoxGeometry pools and fold each one into a
   * single `BufferGeometry`. Each resulting mesh is one draw call, regardless of how many
   * branches / leaves went in. Individual input geometries are disposed after merging.
   */
  if (mergeMeshes) {
    if (barkPool.length > 0) {
      const merged = BufferGeometryUtils.mergeGeometries(barkPool, false);
      for (const g of barkPool) g.dispose();
      if (merged) {
        const m = new THREE.Mesh(merged, mat);
        m.name = 'lt2_branches_merged';
        m.userData.excludeFromPrefabSerialize = true;
        m.castShadow = true;
        m.receiveShadow = true;
        holder.add(m);
      }
    }
    if (leafPool.length > 0) {
      const merged = BufferGeometryUtils.mergeGeometries(leafPool, false);
      for (const g of leafPool) g.dispose();
      if (merged) {
        const m = new THREE.Mesh(merged, leafMat);
        m.name = 'lt2_leaves_merged';
        m.userData.excludeFromPrefabSerialize = true;
        m.castShadow = true;
        m.receiveShadow = true;
        holder.add(m);
      }
    }
  }

  trunkMesh.add(holder);
}
