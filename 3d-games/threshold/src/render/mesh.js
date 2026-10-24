// Turns the block grid into meshes: one merged geometry per material, faces adjacent to air only.
//
// Two things do most of the visual work here and neither is expensive:
//
//  · per-vertex ambient occlusion, the standard voxel trick — darkening a corner by how many solid
//    neighbours touch it. Untextured cubes look like a spreadsheet without it and like a room with
//    it, and it is what makes the inside of the cabin read as somewhere you could sit down.
//  · a per-cell colour jitter hashed off the coordinates, so a wall is not one flat swatch.
//
// NB face culling asks the MATERIAL whether a cell is solid, not grid.isSolid() — the latter counts
// openings as solid (the solver needs that), and using it here would brick up every window.

import { MATERIALS, isSolidMat } from '../world/materials.js';
import { unkey } from '../world/grid.js';

// [normal, four corner offsets] for each face, wound counter-clockwise seen from outside.
const FACES = [
  { n: [-1, 0, 0], v: [[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]] },
  { n: [1, 0, 0], v: [[1, 0, 1], [1, 0, 0], [1, 1, 0], [1, 1, 1]] },
  { n: [0, -1, 0], v: [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]] },
  { n: [0, 1, 0], v: [[0, 1, 1], [1, 1, 1], [1, 1, 0], [0, 1, 0]] },
  { n: [0, 0, -1], v: [[1, 0, 0], [0, 0, 0], [0, 1, 0], [1, 1, 0]] },
  { n: [0, 0, 1], v: [[0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]] },
];

function hash3(x, y, z) {
  let h = x * 374761393 + y * 668265263 + z * 2147483647;
  h = (h ^ (h >> 13)) * 1274126177;
  return ((h ^ (h >> 16)) >>> 0) / 4294967296;
}

/**
 * Classic voxel AO: for a face corner, look at the two edge-neighbours and the diagonal. Three
 * occluders means a tight inside corner and gets darkest; none means open air.
 */
function cornerAO(solid, bx, by, bz, n, cx, cy, cz) {
  // Build the two tangent axes of this face.
  const t1 = n[0] !== 0 ? [0, 1, 0] : [1, 0, 0];
  const t2 = n[1] !== 0 ? [0, 0, 1] : (n[0] !== 0 ? [0, 0, 1] : [0, 1, 0]);
  const s1 = (cx * t1[0] + cy * t1[1] + cz * t1[2]) * 2 - 1;
  const s2 = (cx * t2[0] + cy * t2[1] + cz * t2[2]) * 2 - 1;
  const px = bx + n[0], py = by + n[1], pz = bz + n[2];
  const a = solid(px + t1[0] * s1, py + t1[1] * s1, pz + t1[2] * s1) ? 1 : 0;
  const b = solid(px + t2[0] * s2, py + t2[1] * s2, pz + t2[2] * s2) ? 1 : 0;
  const c = (a && b) ? 1
    : (solid(px + t1[0] * s1 + t2[0] * s2, py + t1[1] * s1 + t2[1] * s2, pz + t1[2] * s1 + t2[2] * s2) ? 1 : 0);
  return 1 - (a + b + c) * 0.19;
}

/**
 * @returns {Map<number, {positions,normals,colors,indices}>} raw arrays keyed by material id
 */
export function meshGrid(grid) {
  const solid = (x, y, z) => isSolidMat(grid.get(x, y, z));
  const out = new Map();

  const get = m => {
    let e = out.get(m);
    if (!e) { e = { positions: [], normals: [], colors: [], indices: [], count: 0 }; out.set(m, e); }
    return e;
  };

  for (const [k, m] of grid.cells) {
    if (!isSolidMat(m)) continue;
    const [x, y, z] = unkey(k);
    const spec = MATERIALS[m];
    const base = spec.color;
    const br = ((base >> 16) & 255) / 255, bg = ((base >> 8) & 255) / 255, bb = (base & 255) / 255;
    const jitter = 0.86 + hash3(x, y, z) * 0.28;

    for (const f of FACES) {
      if (solid(x + f.n[0], y + f.n[1], z + f.n[2])) continue;
      const e = get(m);
      const start = e.count;
      for (const c of f.v) {
        e.positions.push(x + c[0], y + c[1], z + c[2]);
        e.normals.push(f.n[0], f.n[1], f.n[2]);
        const ao = cornerAO(solid, x, y, z, f.n, c[0], c[1], c[2]);
        const s = jitter * ao;
        e.colors.push(br * s, bg * s, bb * s);
        e.count++;
      }
      // Split the quad along its shorter AO gradient, or the shading kinks visibly on corners.
      const c0 = e.colors[(start + 0) * 3], c1 = e.colors[(start + 1) * 3];
      const c2 = e.colors[(start + 2) * 3], c3 = e.colors[(start + 3) * 3];
      if (Math.abs(c0 - c2) > Math.abs(c1 - c3)) {
        e.indices.push(start, start + 1, start + 3, start + 1, start + 2, start + 3);
      } else {
        e.indices.push(start, start + 1, start + 2, start, start + 2, start + 3);
      }
    }
  }
  return out;
}

/** Build three.js meshes from meshGrid's arrays. */
export function buildWorldMeshes(THREE, grid) {
  const group = new THREE.Group();
  group.name = 'blocks';
  const raw = meshGrid(grid);

  for (const [m, e] of raw) {
    if (!e.indices.length) continue;
    const spec = MATERIALS[m];
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(e.positions, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(e.normals, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(e.colors, 3));
    g.setIndex(e.indices);
    g.computeBoundingSphere();

    const mtl = new THREE.MeshStandardMaterial({
      vertexColors: true,
      roughness: spec.rough,
      metalness: spec.name === 'metal' ? 0.6 : 0.0,
    });
    const mesh = new THREE.Mesh(g, mtl);
    mesh.castShadow = spec.name !== 'grass' && spec.name !== 'dirt';
    mesh.receiveShadow = true;
    mesh.name = spec.name;
    group.add(mesh);
  }
  return group;
}
