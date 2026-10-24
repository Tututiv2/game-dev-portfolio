/**
 * Shared LT2 trunk-interior cap material builder.
 *
 * Generates a canvas-backed {@link THREE.MeshStandardMaterial} for the end-caps
 * of LT2 trunks and logs so cut cross-sections can carry a color, an inset
 * border, and a simple pattern texture. Used by both the live config preview
 * and the in-game trunk/log meshes, so edits in the editor look 1:1 in-game.
 */
import * as THREE from 'three';
import { LT2_INTERIOR_TEXTURES } from './lt2-tree-config.js';

export const INTERIOR_TEXTURES = LT2_INTERIOR_TEXTURES;

const TEXTURE_SIZE = 128;

function hexToRgb(hex) {
  const s = String(hex || '#000000').replace('#', '');
  const n = parseInt(s.length === 3 ? s.split('').map((c) => c + c).join('') : s, 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function rgbToCss({ r, g, b }, a = 1) {
  return `rgba(${r},${g},${b},${a})`;
}

function shade(hex, amt) {
  const { r, g, b } = hexToRgb(hex);
  const f = (v) => Math.max(0, Math.min(255, Math.round(v + amt)));
  return rgbToCss({ r: f(r), g: f(g), b: f(b) });
}

function drawPattern(ctx, size, cfg) {
  const base = cfg.interiorColor || '#9a6a3d';
  ctx.fillStyle = base;
  ctx.fillRect(0, 0, size, size);

  switch (cfg.interiorTexture) {
    case 'rings': {
      const cx = size / 2;
      const cy = size / 2;
      const rings = 6;
      for (let i = rings; i >= 1; i--) {
        const r = (i / rings) * (size * 0.55);
        ctx.beginPath();
        ctx.arc(cx, cy, r, 0, Math.PI * 2);
        ctx.strokeStyle = shade(base, i % 2 === 0 ? -28 : 18);
        ctx.lineWidth = 2.2;
        ctx.stroke();
      }
      break;
    }
    case 'grain': {
      const cols = 10;
      const step = size / cols;
      for (let i = 0; i < cols; i++) {
        ctx.fillStyle = shade(base, i % 2 === 0 ? -16 : 12);
        ctx.fillRect(i * step, 0, step * 0.55, size);
      }
      break;
    }
    case 'checker': {
      const n = 8;
      const s = size / n;
      for (let y = 0; y < n; y++) {
        for (let x = 0; x < n; x++) {
          if ((x + y) & 1) {
            ctx.fillStyle = shade(base, -22);
            ctx.fillRect(x * s, y * s, s, s);
          }
        }
      }
      break;
    }
    case 'planks': {
      const rows = 5;
      const h = size / rows;
      for (let r = 0; r < rows; r++) {
        ctx.fillStyle = shade(base, r % 2 === 0 ? 10 : -14);
        ctx.fillRect(0, r * h, size, h);
        ctx.strokeStyle = shade(base, -40);
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(0, r * h);
        ctx.lineTo(size, r * h);
        ctx.stroke();
      }
      break;
    }
    case 'solid':
    default:
      break;
  }
}

/** Draws a canvas element ready for use as a CanvasTexture. */
export function drawInteriorCanvas(cfg) {
  const size = TEXTURE_SIZE;
  const canvas = typeof document !== 'undefined'
    ? document.createElement('canvas')
    : new OffscreenCanvas(size, size);
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  drawPattern(ctx, size, cfg);

  if (cfg.interiorBorder) {
    const frac = Math.max(0, Math.min(0.45, cfg.interiorBorderThickness ?? 0));
    const t = Math.max(1, Math.round(frac * size));
    ctx.strokeStyle = cfg.interiorBorderColor || '#3a2516';
    ctx.lineWidth = t;
    ctx.strokeRect(t / 2, t / 2, size - t, size - t);
  }

  return canvas;
}

/** Creates a MeshStandardMaterial whose `map` is a canvas texture driven by cfg. */
export function buildInteriorCapMaterial(cfg) {
  const canvas = drawInteriorCanvas(cfg);
  const tex = new THREE.CanvasTexture(canvas);
  if ('colorSpace' in tex) tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 4;
  tex.needsUpdate = true;
  const params = {
    map: tex,
    roughness: cfg.interiorNeon ? 0.32 : 0.92,
    metalness: 0,
  };
  if (cfg.interiorNeon) {
    params.emissive = new THREE.Color(cfg.interiorColor || '#9a6a3d');
    params.emissiveMap = tex;
    params.emissiveIntensity = 1.35;
    params.toneMapped = true;
  }
  const mat = new THREE.MeshStandardMaterial(params);
  mat.userData.__lt2InteriorTexture = tex;
  return mat;
}

/** Disposes material AND its owned canvas texture, safe on any material. */
export function disposeInteriorCapMaterial(mat) {
  if (!mat) return;
  const tex = mat.userData?.__lt2InteriorTexture;
  if (tex && typeof tex.dispose === 'function') tex.dispose();
  if (typeof mat.dispose === 'function') mat.dispose();
}
