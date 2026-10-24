import * as THREE from 'three';

/**
 * Shared neon "dark tree" / Spook plank settings.
 * - `color` — surface hue (albedo + emissive tint).
 * - `intensity` — drives emissive brightness and selective bloom strength.
 */
export const NEON_SETTINGS_KEY = 'LT2_NeonTreeSettings';

/**
 * Lit base color for neon wood: same hue as the picker but darker so sunlight × map
 * does not wash toward pale yellow (emissive + bloom stay full-strength orange).
 */
export function neonAlbedoColorFromPicker(hex) {
  const c = new THREE.Color(hex);
  /** Strong dimming so sun-lit diffuse reads clearly darker; emissive/bloom carry the glow. */
  c.multiplyScalar(0.075);
  const f = 0.008;
  c.r = Math.max(f, c.r);
  c.g = Math.max(f, c.g);
  c.b = Math.max(f, c.b);
  return c;
}

/**
 * Maps slider 0.5–12 → emissiveIntensity.
 * Restored from the “visible glow” recipe, then halved (was 0.92 + k*2.15).
 */
export function neonEmissiveIntensityFromSlider(intensity) {
  const t = Math.min(12, Math.max(0.5, Number(intensity)));
  const k = (t - 0.5) / 11.5;
  return 0.46 + k * 1.075;
}

/**
 * Selective bloom — same curve as the first working glow (0.22+k*1.72, 0.4+k*0.55, threshold 0.18), halved.
 */
export function applyNeonBloomToPass(bloomPass, intensity) {
  if (!bloomPass) return;
  const t = Math.min(12, Math.max(0.5, Number(intensity)));
  const k = (t - 0.5) / 11.5;
  bloomPass.strength = 0.11 + k * 0.86;
  bloomPass.radius = 0.2 + k * 0.275;
  bloomPass.threshold = 0.18;
}

export function defaultNeonSettings() {
  return { color: '#ff9933', intensity: 5.2 };
}

export function loadNeonSettings() {
  try {
    const raw = localStorage.getItem(NEON_SETTINGS_KEY);
    if (!raw) return defaultNeonSettings();
    const o = JSON.parse(raw);
    if (!o || typeof o !== 'object') return defaultNeonSettings();
    const color = typeof o.color === 'string' && /^#[0-9a-fA-F]{6}$/.test(o.color) ? o.color : defaultNeonSettings().color;
    let intensity = Number(o.intensity);
    if (!Number.isFinite(intensity)) intensity = defaultNeonSettings().intensity;
    intensity = Math.min(12, Math.max(0.5, intensity));
    return { color, intensity };
  } catch (_) {
    return defaultNeonSettings();
  }
}

export function saveNeonSettings(s) {
  try {
    localStorage.setItem(NEON_SETTINGS_KEY, JSON.stringify(s));
  } catch (_) {
    /* ignore */
  }
}

/**
 * Procedural neon surface texture (full bleed; color from settings).
 * Caller must dispose previous texture when replacing.
 */
export function createNeonCapCanvasTexture(THREE, colorHex) {
  const size = 256;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = colorHex;
  ctx.fillRect(0, 0, size, size);
  for (let i = 0; i < 900; i++) {
    const px = Math.floor(Math.random() * size);
    const py = Math.floor(Math.random() * size);
    ctx.fillStyle = `rgba(255,255,255,${0.08 + Math.random() * 0.18})`;
    ctx.fillRect(px, py, 1, 1);
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.ClampToEdgeWrapping;
  tex.wrapT = THREE.ClampToEdgeWrapping;
  tex.needsUpdate = true;
  return tex;
}
