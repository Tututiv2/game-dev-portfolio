/* ============================================================================
   SHIPLOD_VIEW — the meshes for the four tiers. RENDERING ONLY.

   Every decision in here was already made by shiplod.js: which tier, how bright
   a point is, how coarse a silhouette is, and which SCENE the mesh belongs in.
   This file turns those answers into three.js objects and nothing else. If you
   find yourself writing a threshold here, it belongs next door.

   WHICH SCENE. view.js renders `nearScene` through two extra depth slices whose
   far plane is `camera.near` — 1 m on the ground, 15.5 km in orbit. A ship
   beyond that plane put in nearScene is CLIPPED AWAY, silently: the near
   cameras' frusta simply do not reach it. So the near tiers go in
   `view.nearScene` and everything beyond the near plane goes in `view.scene`,
   which is the far slice and already spans out to radius*22. lod.slice says
   which, and it is computed from ctx.nearPlane, which the caller must set to
   camera.near every frame. Getting this wrong is invisible in code review and
   obvious on screen: your fleet vanishes the moment you leave the atmosphere.

   FRAMES. Ship positions are in the PLANET's frame. Both scenes are world
   space. So every position goes through ctx.toWorld (api.API.toWorld) and every
   orientation is pre-multiplied by ctx.planetQuat (api.world().quat). This
   project has lost a day twice to a mixed frame; do not convert by hand here.

   HDR. view.js renders linear HDR into a half-float target and tone maps once
   at the end, so EVERY material here sets toneMapped = false. A point of light
   is authored above the bloom threshold (0.95) on purpose — that is what makes
   a distant ship a spark rather than a dot.
   ========================================================================= */

import * as T from 'three';
import * as G from './grid.js';
import { TIER, silhouetteCoarsen } from './shiplod.js';

const AXES = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

/* ---- the surface material -------------------------------------------------
   A lambert term plus a rim, from a sun direction handed in as a uniform. It is
   hand-written rather than MeshStandardMaterial because nearScene has no lights
   in it and adding some would light the planet's own meshes too.
   NO BACKTICKS INSIDE THE SHADER (CLAUDE.md trap 1). */
const VERT = `
  varying vec3 vN;
  varying vec3 vP;
  void main() {
    vN = normalize(normalMatrix * normal);
    vec4 wp = modelMatrix * vec4(position, 1.0);
    vP = wp.xyz;
    gl_Position = projectionMatrix * viewMatrix * wp;
  }
`;
const FRAG = `
  uniform vec3 uCol;
  uniform vec3 uSun;
  uniform float uAmb;
  uniform float uRim;
  varying vec3 vN;
  varying vec3 vP;
  void main() {
    vec3 n = normalize(vN);
    if (!gl_FrontFacing) n = -n;
    float lam = max(dot(n, normalize(uSun)), 0.0);
    vec3 v = normalize(cameraPosition - vP);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
    gl_FragColor = vec4(uCol * (uAmb + lam) + uRim * rim * uCol, 1.0);
  }
`;

function surfaceMaterial(colour, opts = {}) {
  const m = new T.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    side: T.DoubleSide,
    uniforms: {
      uCol: { value: new T.Color(colour) },
      uSun: { value: new T.Vector3(0.4, 0.8, 0.3).normalize() },
      uAmb: { value: opts.amb ?? 0.12 },
      uRim: { value: opts.rim ?? 0.25 },
    },
  });
  m.toneMapped = false;
  return m;
}

/* A point of light. gl_PointCoord falloff rather than a texture, so nothing
   here touches a canvas and the file stays importable in any order. */
const PVERT = `
  uniform float uPx;
  void main() {
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    gl_PointSize = uPx;
  }
`;
const PFRAG = `
  uniform vec3 uCol;
  uniform float uGain;
  void main() {
    vec2 d = gl_PointCoord - vec2(0.5);
    float r = length(d) * 2.0;
    if (r > 1.0) discard;
    float f = pow(1.0 - r, 2.0);
    gl_FragColor = vec4(uCol * uGain * f, 1.0);
  }
`;

function pointMaterial(colour) {
  const m = new T.ShaderMaterial({
    vertexShader: PVERT, fragmentShader: PFRAG,
    transparent: true, depthWrite: false, blending: T.AdditiveBlending,
    uniforms: {
      uCol: { value: new T.Color(colour) },
      uGain: { value: 1 },
      uPx: { value: 2 },
    },
  });
  m.toneMapped = false;
  return m;
}

/* ---- geometry -------------------------------------------------------------
   ONE builder serves both the merged hull and the silhouette: emit only the
   faces of the voxel shell that touch vacuum, at a coarseness the caller names.
   coarsen = 1 is the true hull; shiplod.silhouetteCoarsen() picks the rest. */
export function shellGeometry(grid, coarsen = 1) {
  const s = G.cellM(grid.sizeClass);
  const step = Math.max(1, Math.floor(coarsen));
  const occ = new Set();
  for (const k of grid.blocks.keys()) {
    const c = k.split(',').map(Number);
    occ.add(`${Math.floor(c[0] / step)},${Math.floor(c[1] / step)},${Math.floor(c[2] / step)}`);
  }
  const pos = [], nrm = [];
  const e = s * step;                      // metres per coarse cell
  const quad = (o, u, v, n) => {
    const p = [
      [o[0], o[1], o[2]],
      [o[0] + u[0], o[1] + u[1], o[2] + u[2]],
      [o[0] + u[0] + v[0], o[1] + u[1] + v[1], o[2] + u[2] + v[2]],
      [o[0], o[1], o[2]],
      [o[0] + u[0] + v[0], o[1] + u[1] + v[1], o[2] + u[2] + v[2]],
      [o[0] + v[0], o[1] + v[1], o[2] + v[2]],
    ];
    for (const q of p) { pos.push(q[0], q[1], q[2]); nrm.push(n[0], n[1], n[2]); }
  };
  for (const k of occ) {
    const c = k.split(',').map(Number);
    for (const d of AXES) {
      if (occ.has(`${c[0] + d[0]},${c[1] + d[1]},${c[2] + d[2]}`)) continue;
      const lo = [c[0] * e, c[1] * e, c[2] * e];
      // the face's own origin and its two in-plane edges
      const o = [lo[0] + (d[0] > 0 ? e : 0), lo[1] + (d[1] > 0 ? e : 0), lo[2] + (d[2] > 0 ? e : 0)];
      let u, v;
      if (d[0]) { u = [0, e, 0]; v = [0, 0, e]; }
      else if (d[1]) { u = [0, 0, e]; v = [e, 0, 0]; }
      else { u = [e, 0, 0]; v = [0, e, 0]; }
      quad(o, u, v, d);
    }
  }
  const g = new T.BufferGeometry();
  g.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new T.Float32BufferAttribute(nrm, 3));
  g.computeBoundingSphere();
  return g;
}

/** BLOCKS tier: one InstancedMesh per block type, each instance carrying the
    block's real 24-way rotation out of grid.ROTS — no trigonometry, the basis
    is already integers. */
export function blocksGroup(grid) {
  const s = G.cellM(grid.sizeClass);
  const byType = new Map();
  for (const b of G.uniqueBlocks(grid)) {
    if (!byType.has(b.type)) byType.set(b.type, []);
    byType.get(b.type).push(b);
  }
  const group = new T.Group();
  const m4 = new T.Matrix4();
  for (const [type, list] of byType) {
    const t = G.BLOCK_TYPES[type];
    const geo = new T.BoxGeometry(t.size[0] * s, t.size[1] * s, t.size[2] * s);
    const col = t.thrust ? 0xff8844 : t.power > 0 ? 0x66ddff : t.cockpit ? 0xaaddff : 0x8a8f9a;
    const mesh = new T.InstancedMesh(geo, surfaceMaterial(col), list.length);
    list.forEach((b, i) => {
      const r = G.ROTS[b.rot];
      // half the footprint, in the block's own axes, rotated into grid space
      const half = G.rotVec(b.rot, [t.size[0] * s / 2, t.size[1] * s / 2, t.size[2] * s / 2]);
      m4.makeBasis(new T.Vector3(...r.right), new T.Vector3(...r.up), new T.Vector3(...r.fwd));
      m4.setPosition(b.cell[0] * s + half[0], b.cell[1] * s + half[1], b.cell[2] * s + half[2]);
      mesh.setMatrixAt(i, m4);
    });
    mesh.instanceMatrix.needsUpdate = true;
    group.add(mesh);
  }
  return group;
}

/* ---- the per-ship renderable ---------------------------------------------- */
function buildTier(grid, tier, colour) {
  if (tier === TIER.BLOCKS) return blocksGroup(grid);
  if (tier === TIER.HULL) return new T.Mesh(shellGeometry(grid, 1), surfaceMaterial(colour));
  if (tier === TIER.SILHOUETTE)
    return new T.Mesh(shellGeometry(grid, silhouetteCoarsen(grid)),
                      surfaceMaterial(colour, { amb: 0.20, rim: 0.45 }));
  if (tier === TIER.POINT) {
    const g = new T.BufferGeometry();
    g.setAttribute('position', new T.Float32BufferAttribute([0, 0, 0], 3));
    return new T.Points(g, pointMaterial(colour));
  }
  return null;
}

const disposeDeep = (o) => {
  if (!o) return;
  o.traverse((c) => {
    if (c.geometry) c.geometry.dispose();
    if (c.material) (Array.isArray(c.material) ? c.material : [c.material]).forEach(m => m.dispose());
  });
};

/**
 * The renderer for the whole fleet.
 *
 *   const lodView = makeShipLodView(view);
 *   lodView.update(api.ships(), eyePlanetFrame, {
 *     radPerPx: pixelScale(camera.fov, drawingBufferHeight),
 *     nearPlane: camera.near,          // or the fleet disappears in orbit
 *     toWorld:   api.API.toWorld,      // planet frame -> world space
 *     planetQuat: api.world().quat,
 *     sun: [x, y, z],                  // world space, unit
 *   });
 *
 * `lods` may be handed in already computed (assignLods) — this function makes
 * no LOD decisions of its own.
 */
export function makeShipLodView(view, opts = {}) {
  const entries = new Map();   // ship id -> { tier, obj, scene, colour }
  const near = view.nearScene, far = view.scene;
  const q = new T.Quaternion(), qp = new T.Quaternion();

  function place(rec, ship, lod, ctx) {
    const c = ctx.toWorld ? ctx.toWorld(ship.pos) : ship.pos;
    rec.obj.position.set(c[0], c[1], c[2]);
    const pq = ctx.planetQuat || [0, 0, 0, 1];
    qp.set(pq[0], pq[1], pq[2], pq[3]);
    const o = ship.ori || [0, 0, 0, 1];
    q.set(o[0], o[1], o[2], o[3]);
    rec.obj.quaternion.copy(qp).multiply(q);
    if (lod.tier === TIER.POINT) {
      rec.obj.material.uniforms.uPx.value = lod.pointPx;
      rec.obj.material.uniforms.uGain.value = lod.hdr;
    } else if (ctx.sun) {
      rec.obj.traverse((n) => {
        if (n.material && n.material.uniforms && n.material.uniforms.uSun)
          n.material.uniforms.uSun.value.set(ctx.sun[0], ctx.sun[1], ctx.sun[2]);
      });
    }
  }

  function drop(id) {
    const rec = entries.get(id);
    if (!rec) return;
    if (rec.obj && rec.obj.parent) rec.obj.parent.remove(rec.obj);
    disposeDeep(rec.obj);
    entries.delete(id);
  }

  return {
    entries,
    /** lods: Map(shipId -> lod) from shiplod.assignLods(). */
    update(ships, lods, ctx = {}) {
      const live = new Set();
      for (const ship of ships) {
        const lod = lods.get(ship.id);
        if (!lod || lod.tier === TIER.HIDDEN) continue;
        live.add(ship.id);
        const wantScene = lod.slice === 'far' ? far : near;
        let rec = entries.get(ship.id);
        // A tier change or a SCENE change is a rebuild; nothing else is.
        if (rec && (rec.tier !== lod.tier || rec.grid !== ship.grid)) { drop(ship.id); rec = null; }
        if (!rec) {
          const colour = opts.colour || (ship.owner === 'player' ? 0xbfd4e6 : 0xe8a06a);
          const obj = buildTier(ship.grid, lod.tier, colour);
          if (!obj) continue;
          rec = { tier: lod.tier, obj, scene: null, grid: ship.grid };
          entries.set(ship.id, rec);
        }
        if (rec.scene !== wantScene) {
          if (rec.obj.parent) rec.obj.parent.remove(rec.obj);
          wantScene.add(rec.obj);
          rec.scene = wantScene;
        }
        place(rec, ship, lod, ctx);
      }
      for (const id of [...entries.keys()]) if (!live.has(id)) drop(id);
    },
    dispose() { for (const id of [...entries.keys()]) drop(id); },
  };
}
