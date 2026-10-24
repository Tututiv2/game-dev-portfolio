/**
 * Canvas-generated LT2 trunk-side and leaf-pad textures.
 *
 * Used by both the in-game tree builder ({@link game.js} bark, and
 * {@link lt2-tree-branches.js} leaves) and the live editor preview
 * ({@link lt2-tree-config-ui.js}) so the preview renders a faithful preview of
 * what spawns in the world.
 *
 * `stock` trunk texture is intentionally not generated here — the caller keeps
 * using the shared bark PNG for that option; this module generates every other
 * pattern. Leaves have no PNG fallback; `solid` is handled as a plain colored
 * material (no map) to match the original single-color look.
 */
import * as THREE from 'three';

export const LT2_TRUNK_TEXTURES = Object.freeze(['stock', 'smooth', 'grain', 'bark', 'rings']);
export const LT2_LEAF_TEXTURES = Object.freeze(['solid', 'grass', 'leafy', 'dense', 'speckle']);

export const LT2_TRUNK_TEXTURE_LABELS = Object.freeze({
  stock: 'Stock bark (PNG)',
  smooth: 'Smooth',
  grain: 'Vertical grain',
  bark: 'Rough bark',
  rings: 'Horizontal rings',
});

export const LT2_LEAF_TEXTURE_LABELS = Object.freeze({
  solid: 'Solid',
  grass: 'Grass noise',
  leafy: 'Leafy clumps',
  dense: 'Dense canopy',
  speckle: 'Fine speckle',
});

const CANVAS_SIZE = 128;

function hexToRgb(hex) {
  const s = String(hex || '#000000').replace('#', '');
  const n = parseInt(s.length === 3 ? s.split('').map((c) => c + c).join('') : s, 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function cssRgba({ r, g, b }, a) {
  return `rgba(${r},${g},${b},${a})`;
}

/** Deterministic RNG so a given (texture, color) produces the same canvas every call. */
function mulberry32(seed) {
  let t = seed | 0;
  return () => {
    t = (t + 0x6d2b79f5) | 0;
    let x = Math.imul(t ^ (t >>> 15), 1 | t);
    x = (x + Math.imul(x ^ (x >>> 7), 61 | x)) ^ x;
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
}

function createCanvas(size) {
  if (typeof document !== 'undefined') {
    const c = document.createElement('canvas');
    c.width = size;
    c.height = size;
    return c;
  }
  return new OffscreenCanvas(size, size);
}

function fillBase(ctx, size, baseHex) {
  ctx.fillStyle = baseHex;
  ctx.fillRect(0, 0, size, size);
}

function drawBarkPattern(ctx, size, texture, baseHex) {
  const rng = mulberry32(0x1234abcd);
  const rgb = hexToRgb(baseHex);
  const isDark = rgb.r * 0.299 + rgb.g * 0.587 + rgb.b * 0.114 < 110;
  const darkOverlay = isDark ? 'rgba(255,255,255,' : 'rgba(0,0,0,';
  const lightOverlay = isDark ? 'rgba(255,255,255,' : 'rgba(255,255,255,';

  switch (texture) {
    case 'smooth':
      break;
    case 'grain': {
      for (let x = 0; x < size; x += 1) {
        if (rng() > 0.55) continue;
        const alpha = 0.06 + rng() * 0.22;
        ctx.fillStyle = `${darkOverlay}${alpha})`;
        const w = 1 + Math.floor(rng() * 2);
        ctx.fillRect(x, 0, w, size);
      }
      /** Sparse highlights to add warmth over the dark grain. */
      for (let x = 0; x < size; x += 4) {
        if (rng() > 0.7) continue;
        ctx.fillStyle = `${lightOverlay}${0.04 + rng() * 0.1})`;
        ctx.fillRect(x, 0, 1, size);
      }
      break;
    }
    case 'bark': {
      for (let i = 0; i < 280; i++) {
        const x = rng() * size;
        const y = rng() * size;
        const r = 1 + rng() * 2.5;
        ctx.fillStyle = `${darkOverlay}${0.08 + rng() * 0.22})`;
        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.fill();
      }
      /** Vertical cracks on top of the pitted noise. */
      for (let i = 0; i < 24; i++) {
        const x = rng() * size;
        const h = 16 + rng() * 60;
        const y = rng() * (size - h);
        ctx.fillStyle = `${darkOverlay}${0.2 + rng() * 0.35})`;
        ctx.fillRect(x, y, 1 + Math.floor(rng() * 2), h);
      }
      break;
    }
    case 'rings': {
      const bands = 8;
      const step = size / bands;
      for (let i = 0; i < bands; i++) {
        const a = (i & 1) ? 0.12 : 0.06;
        ctx.fillStyle = (i & 1) ? `${darkOverlay}${a})` : `${lightOverlay}${a})`;
        ctx.fillRect(0, i * step, size, step);
      }
      break;
    }
  }
}

function drawLeafPattern(ctx, size, texture, baseHex) {
  const rng = mulberry32(0xdeadbeef);
  const rgb = hexToRgb(baseHex);
  const isLight = rgb.r * 0.299 + rgb.g * 0.587 + rgb.b * 0.114 > 200;
  const darkA = isLight ? 'rgba(0,0,0,' : 'rgba(0,0,0,';
  const lightA = 'rgba(255,255,255,';

  switch (texture) {
    case 'solid':
      break;
    case 'grass': {
      for (let i = 0; i < 5200; i++) {
        const x = (rng() * size) | 0;
        const y = (rng() * size) | 0;
        const r = rng();
        if (r < 0.5) ctx.fillStyle = `${darkA}${0.22 + rng() * 0.2})`;
        else ctx.fillStyle = `${lightA}${0.08 + rng() * 0.12})`;
        ctx.fillRect(x, y, 1, 1);
      }
      break;
    }
    case 'leafy': {
      for (let i = 0; i < 200; i++) {
        const x = rng() * size;
        const y = rng() * size;
        const r = 2 + rng() * 5;
        ctx.fillStyle = rng() < 0.5 ? `${darkA}${0.18})` : `${lightA}${0.12})`;
        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.fill();
      }
      break;
    }
    case 'dense': {
      for (let i = 0; i < 2400; i++) {
        const x = rng() * size;
        const y = rng() * size;
        ctx.fillStyle = rng() < 0.55 ? `${darkA}${0.2})` : `${lightA}${0.08})`;
        ctx.fillRect(x, y, 2, 2);
      }
      break;
    }
    case 'speckle': {
      for (let i = 0; i < 1600; i++) {
        const x = rng() * size;
        const y = rng() * size;
        ctx.fillStyle = `${darkA}${0.08 + rng() * 0.24})`;
        ctx.fillRect(x, y, 1, 1);
      }
      break;
    }
  }
}

export function drawTrunkSideCanvas(texture, tintHex) {
  const size = CANVAS_SIZE;
  const canvas = createCanvas(size);
  const ctx = canvas.getContext('2d');
  fillBase(ctx, size, tintHex || '#77513a');
  drawBarkPattern(ctx, size, texture, tintHex || '#77513a');
  return canvas;
}

export function drawLeafPadCanvas(texture, tintHex) {
  const size = CANVAS_SIZE;
  const canvas = createCanvas(size);
  const ctx = canvas.getContext('2d');
  fillBase(ctx, size, tintHex || '#73d178');
  drawLeafPattern(ctx, size, texture, tintHex || '#73d178');
  return canvas;
}

/**
 * Build a MeshStandardMaterial for a trunk side face from a generated canvas.
 * Caller must use this ONLY when {@link texture} is not `'stock'` — the stock
 * PNG is a caller-managed shared resource.
 */
export function buildTrunkSideMaterial({ texture, tintHex }) {
  const canvas = drawTrunkSideCanvas(texture, tintHex);
  const tex = new THREE.CanvasTexture(canvas);
  if ('colorSpace' in tex) tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  tex.anisotropy = 4;
  tex.needsUpdate = true;
  const mat = new THREE.MeshStandardMaterial({
    map: tex,
    roughness: 0.88,
    metalness: 0.04,
  });
  mat.userData.__lt2TrunkTexture = tex;
  return mat;
}

/**
 * Build a leaf-pad MeshStandardMaterial. `solid` returns a plain colored
 * material (no map) to match the pre-texture default look exactly.
 */
export function buildLeafPadMaterial({ texture, color }) {
  const hex = typeof color === 'string' ? color : '#73d178';
  if (!texture || texture === 'solid') {
    return new THREE.MeshStandardMaterial({
      color: new THREE.Color(hex),
      roughness: 0.88,
      metalness: 0.04,
      transparent: true,
      opacity: 0.96,
    });
  }
  const canvas = drawLeafPadCanvas(texture, hex);
  const tex = new THREE.CanvasTexture(canvas);
  if ('colorSpace' in tex) tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  tex.anisotropy = 4;
  tex.needsUpdate = true;
  const mat = new THREE.MeshStandardMaterial({
    map: tex,
    roughness: 0.88,
    metalness: 0.04,
    transparent: true,
    opacity: 0.96,
  });
  mat.userData.__lt2LeafTexture = tex;
  return mat;
}

export function disposeLt2TexturedMaterial(mat) {
  if (!mat) return;
  const a = mat.userData?.__lt2TrunkTexture;
  const b = mat.userData?.__lt2LeafTexture;
  if (a) a.dispose?.();
  if (b) b.dispose?.();
  mat.dispose?.();
}
