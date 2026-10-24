// A minimal glTF-binary (.glb) reader. Written rather than vendored, on purpose.
//
// three ships a GLTFLoader example, but it is 110 KB, it imports three, and it
// returns a SCENE GRAPH -- a tree of Meshes. This game draws every kind through
// one InstancedMesh per material, so a scene graph is the wrong shape: it would
// have to be walked, flattened and its node transforms baked before anything
// could be instanced. That flattening is most of the work, so this does it
// directly and returns flat, already-baked parts grouped by material.
//
// It also imports NOTHING, which is what lets the suite parse a real Blender
// .glb in node with no browser and no three.js in the process.
//
// Supported, because that is all `tools/blender_models.py` emits: GLB
// container, triangles, POSITION and NORMAL, indexed or not, node hierarchy
// with matrix or TRS, and pbrMetallicRoughness factors plus
// KHR_materials_emissive_strength. Anything else -- Draco, sparse accessors,
// textures, skins, morph targets -- is not read. A file using them throws, and
// the caller falls back to the procedural shape, which is the whole contract.

const MAGIC = 0x46546c67;   // 'glTF'
const CHUNK_JSON = 0x4e4f534a;
const CHUNK_BIN = 0x004e4942;

const COMPONENT = {
  5120: { array: Int8Array, bytes: 1 },
  5121: { array: Uint8Array, bytes: 1 },
  5122: { array: Int16Array, bytes: 2 },
  5123: { array: Uint16Array, bytes: 2 },
  5125: { array: Uint32Array, bytes: 4 },
  5126: { array: Float32Array, bytes: 4 },
};
const NUM = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT2: 4, MAT3: 9, MAT4: 16 };

const DEFAULT_MATERIAL = {
  name: 'default',
  baseColor: [1, 1, 1],
  opacity: 1,
  metallic: 1,
  roughness: 1,
  emissive: [0, 0, 0],
  emissiveStrength: 1,
  doubleSided: false,
  alphaMode: 'OPAQUE',
  alphaCutoff: 0.5,
};

export function isGLB(buffer) {
  const b = toArrayBuffer(buffer);
  if (!b || b.byteLength < 12) return false;
  return new DataView(b).getUint32(0, true) === MAGIC;
}

function toArrayBuffer(x) {
  if (!x) return null;
  if (x instanceof ArrayBuffer) return x;
  if (ArrayBuffer.isView(x)) return x.buffer.slice(x.byteOffset, x.byteOffset + x.byteLength);
  return null;
}

/* ------------------------------------------------------------------ matrices */
// Column-major, like glTF and like three, so m[col * 4 + row].

function identity() {
  return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
}

function multiply(a, b) {
  const o = new Array(16);
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] +
                     a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
    }
  }
  return o;
}

function fromTRS(t, r, s) {
  const [x, y, z, w] = r;
  const x2 = x + x, y2 = y + y, z2 = z + z;
  const xx = x * x2, xy = x * y2, xz = x * z2;
  const yy = y * y2, yz = y * z2, zz = z * z2;
  const wx = w * x2, wy = w * y2, wz = w * z2;
  return [
    (1 - (yy + zz)) * s[0], (xy + wz) * s[0], (xz - wy) * s[0], 0,
    (xy - wz) * s[1], (1 - (xx + zz)) * s[1], (yz + wx) * s[1], 0,
    (xz + wy) * s[2], (yz - wx) * s[2], (1 - (xx + yy)) * s[2], 0,
    t[0], t[1], t[2], 1,
  ];
}

// The inverse transpose of the upper 3x3. Scaling a model non-uniformly and
// then reusing the same matrix on its normals is how a mesh ends up lit wrong,
// and blender_models.py does scale non-uniformly (squashed foliage, stretched
// torsos), so this cannot be skipped.
function normalMatrix(m) {
  const a = m[0], b = m[1], c = m[2];
  const d = m[4], e = m[5], f = m[6];
  const g = m[8], h = m[9], i = m[10];
  const A = e * i - f * h, B = f * g - d * i, Cc = d * h - e * g;
  let det = a * A + b * B + c * Cc;
  if (!det) return [1, 0, 0, 0, 1, 0, 0, 0, 1];
  det = 1 / det;
  // inverse (row-major 3x3), then transposed as we write it out
  return [
    A * det, B * det, Cc * det,
    (c * h - b * i) * det, (a * i - c * g) * det, (b * g - a * h) * det,
    (b * f - c * e) * det, (c * d - a * f) * det, (a * e - b * d) * det,
  ];
}

/* -------------------------------------------------------------------- parse */

function readChunks(buf) {
  const dv = new DataView(buf);
  if (buf.byteLength < 12) throw new Error('glb: too short');
  if (dv.getUint32(0, true) !== MAGIC) throw new Error('glb: not a glb (bad magic)');
  const version = dv.getUint32(4, true);
  if (version !== 2) throw new Error('glb: version ' + version + ', only 2 is read');
  const total = Math.min(dv.getUint32(8, true), buf.byteLength);

  let json = null, bin = null, at = 12;
  while (at + 8 <= total) {
    const len = dv.getUint32(at, true);
    const type = dv.getUint32(at + 4, true);
    const start = at + 8;
    if (start + len > buf.byteLength) throw new Error('glb: chunk runs past the file');
    if (type === CHUNK_JSON && json === null) {
      json = new TextDecoder().decode(new Uint8Array(buf, start, len));
    } else if (type === CHUNK_BIN && bin === null) {
      bin = new Uint8Array(buf, start, len);
    }
    at = start + len + ((4 - (len % 4)) % 4);
  }
  if (json === null) throw new Error('glb: no JSON chunk');
  let gltf;
  try { gltf = JSON.parse(json); } catch (e) { throw new Error('glb: JSON chunk is not JSON'); }
  return { gltf, bin };
}

function accessor(gltf, bin, index) {
  const acc = (gltf.accessors || [])[index];
  if (!acc) throw new Error('glb: accessor ' + index + ' is missing');
  if (acc.sparse) throw new Error('glb: sparse accessors are not read');
  const comp = COMPONENT[acc.componentType];
  const n = NUM[acc.type];
  if (!comp || !n) throw new Error('glb: accessor type ' + acc.type + '/' + acc.componentType);

  const out = new comp.array(acc.count * n);
  const viewIndex = acc.bufferView;
  if (viewIndex === undefined) return out;              // all zeroes, per spec

  const view = (gltf.bufferViews || [])[viewIndex];
  if (!view) throw new Error('glb: bufferView ' + viewIndex + ' is missing');
  if (!bin) throw new Error('glb: no BIN chunk, but an accessor wants one');
  if ((gltf.buffers || [])[view.buffer || 0] === undefined) throw new Error('glb: no buffer');

  const base = (view.byteOffset || 0) + (acc.byteOffset || 0);
  const elemBytes = comp.bytes * n;
  const stride = view.byteStride || elemBytes;
  const need = base + (acc.count - 1) * stride + elemBytes;
  if (need > bin.byteLength) throw new Error('glb: accessor reads past the buffer');

  if (stride === elemBytes) {
    // tight: one copy
    const src = new comp.array(bin.buffer, bin.byteOffset + base, acc.count * n);
    out.set(src);
  } else {
    const dv = new DataView(bin.buffer, bin.byteOffset);
    const get = readerFor(comp.array);
    for (let i = 0; i < acc.count; i++) {
      for (let k = 0; k < n; k++) {
        out[i * n + k] = get(dv, base + i * stride + k * comp.bytes);
      }
    }
  }
  return out;
}

function readerFor(Arr) {
  if (Arr === Float32Array) return (dv, o) => dv.getFloat32(o, true);
  if (Arr === Uint32Array) return (dv, o) => dv.getUint32(o, true);
  if (Arr === Uint16Array) return (dv, o) => dv.getUint16(o, true);
  if (Arr === Int16Array) return (dv, o) => dv.getInt16(o, true);
  if (Arr === Uint8Array) return (dv, o) => dv.getUint8(o);
  return (dv, o) => dv.getInt8(o);
}

function materialOf(gltf, index) {
  if (index === undefined || index === null) return { ...DEFAULT_MATERIAL };
  const m = (gltf.materials || [])[index];
  if (!m) return { ...DEFAULT_MATERIAL };
  const pbr = m.pbrMetallicRoughness || {};
  const bc = pbr.baseColorFactor || [1, 1, 1, 1];
  const em = m.emissiveFactor || [0, 0, 0];
  const ext = m.extensions || {};
  const strength = (ext.KHR_materials_emissive_strength || {}).emissiveStrength;
  return {
    name: m.name || ('material' + index),
    baseColor: [bc[0], bc[1], bc[2]],
    opacity: bc.length > 3 ? bc[3] : 1,
    metallic: pbr.metallicFactor === undefined ? 1 : pbr.metallicFactor,
    roughness: pbr.roughnessFactor === undefined ? 1 : pbr.roughnessFactor,
    emissive: [em[0], em[1], em[2]],
    emissiveStrength: strength === undefined ? 1 : strength,
    doubleSided: !!m.doubleSided,
    alphaMode: m.alphaMode || 'OPAQUE',
    alphaCutoff: m.alphaCutoff === undefined ? 0.5 : m.alphaCutoff,
  };
}

function localMatrix(node) {
  if (node.matrix && node.matrix.length === 16) return node.matrix.slice();
  const t = node.translation || [0, 0, 0];
  const r = node.rotation || [0, 0, 0, 1];
  const s = node.scale || [1, 1, 1];
  return fromTRS(t, r, s);
}

/**
 * Parse a .glb into flat parts, one per material, with every node transform
 * already baked into the vertices.
 *
 * @returns {{parts: Array, tris: number, materials: number}}
 */
export function parseGLB(buffer) {
  const buf = toArrayBuffer(buffer);
  if (!buf) throw new Error('glb: expected an ArrayBuffer');
  const { gltf, bin } = readChunks(buf);

  for (const need of gltf.extensionsRequired || []) {
    // KHR_materials_emissive_strength is only ever "used", never "required",
    // so anything landing here is genuinely unreadable.
    throw new Error('glb: requires extension ' + need);
  }

  const nodes = gltf.nodes || [];
  const sceneIndex = gltf.scene === undefined ? 0 : gltf.scene;
  const scene = (gltf.scenes || [])[sceneIndex];
  const roots = scene && scene.nodes ? scene.nodes : nodes.map((_, i) => i);

  // material index -> accumulating part
  const groups = new Map();
  const seen = new Set();

  function visit(index, parent) {
    if (seen.has(index)) return;               // a cycle would otherwise hang
    seen.add(index);
    const node = nodes[index];
    if (!node) return;
    const world = multiply(parent, localMatrix(node));
    if (node.mesh !== undefined) emit(node.mesh, world);
    for (const c of node.children || []) visit(c, world);
    seen.delete(index);
  }

  function emit(meshIndex, world) {
    const mesh = (gltf.meshes || [])[meshIndex];
    if (!mesh) return;
    const nm = normalMatrix(world);
    for (const prim of mesh.primitives || []) {
      const mode = prim.mode === undefined ? 4 : prim.mode;
      if (mode !== 4) continue;                            // triangles only
      const attrs = prim.attributes || {};
      if (attrs.POSITION === undefined) continue;
      const pos = accessor(gltf, bin, attrs.POSITION);
      const count = pos.length / 3;
      const nrm = attrs.NORMAL === undefined ? null : accessor(gltf, bin, attrs.NORMAL);

      let idx;
      if (prim.indices === undefined) {
        idx = new Uint32Array(count);
        for (let i = 0; i < count; i++) idx[i] = i;
      } else {
        const raw = accessor(gltf, bin, prim.indices);
        idx = raw instanceof Uint32Array ? raw : Uint32Array.from(raw);
      }

      const key = prim.material === undefined ? -1 : prim.material;
      let g = groups.get(key);
      if (!g) {
        g = { material: materialOf(gltf, prim.material), name: mesh.name || 'part',
              pos: [], nrm: [], idx: [], base: 0, hasNormals: true };
        groups.set(key, g);
      }
      for (let i = 0; i < count; i++) {
        const x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
        g.pos.push(world[0] * x + world[4] * y + world[8] * z + world[12],
                   world[1] * x + world[5] * y + world[9] * z + world[13],
                   world[2] * x + world[6] * y + world[10] * z + world[14]);
        if (nrm) {
          const a = nrm[i * 3], b = nrm[i * 3 + 1], c = nrm[i * 3 + 2];
          let nx = nm[0] * a + nm[3] * b + nm[6] * c;
          let ny = nm[1] * a + nm[4] * b + nm[7] * c;
          let nz = nm[2] * a + nm[5] * b + nm[8] * c;
          const l = Math.hypot(nx, ny, nz) || 1;
          g.nrm.push(nx / l, ny / l, nz / l);
        } else {
          g.hasNormals = false;
          g.nrm.push(0, 1, 0);
        }
      }
      for (let i = 0; i < idx.length; i++) g.idx.push(g.base + idx[i]);
      g.base += count;
    }
  }

  for (const r of roots) visit(r, identity());

  const parts = [];
  let tris = 0;
  for (const g of groups.values()) {
    if (!g.idx.length) continue;
    tris += g.idx.length / 3;
    parts.push({
      name: g.material.name || g.name,
      material: g.material,
      positions: new Float32Array(g.pos),
      normals: g.hasNormals ? new Float32Array(g.nrm) : null,
      indices: g.base > 65535 ? new Uint32Array(g.idx) : new Uint16Array(g.idx),
    });
  }
  if (!parts.length) throw new Error('glb: no triangles in the file');
  return { parts, tris, materials: parts.length };
}

/** Axis-aligned bounds of every part, in one pass. */
export function bounds(parts) {
  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
  for (const p of parts) {
    const a = p.positions;
    for (let i = 0; i < a.length; i += 3) {
      if (a[i] < minX) minX = a[i];
      if (a[i] > maxX) maxX = a[i];
      if (a[i + 1] < minY) minY = a[i + 1];
      if (a[i + 1] > maxY) maxY = a[i + 1];
      if (a[i + 2] < minZ) minZ = a[i + 2];
      if (a[i + 2] > maxZ) maxZ = a[i + 2];
    }
  }
  return { minX, minY, minZ, maxX, maxY, maxZ,
           height: maxY - minY, width: maxX - minX, depth: maxZ - minZ };
}

/**
 * Fit parts to the game's contract IN PLACE: base on y = 0, centred on x/z,
 * `height` units tall. The exporter already does this, but the owner can drop
 * any .glb of any size into the pack, and a model that is 180 cm tall in
 * Blender units would otherwise be planted as a 180-unit tree.
 */
export function fitToUnit(parts, height = 1) {
  const b = bounds(parts);
  if (!isFinite(b.height) || b.height <= 0) return b;
  const s = height / b.height;
  const cx = (b.minX + b.maxX) * 0.5, cz = (b.minZ + b.maxZ) * 0.5;
  for (const p of parts) {
    const a = p.positions;
    for (let i = 0; i < a.length; i += 3) {
      a[i] = (a[i] - cx) * s;
      a[i + 1] = (a[i + 1] - b.minY) * s;
      a[i + 2] = (a[i + 2] - cz) * s;
    }
  }
  return bounds(parts);
}
