// §8.2-8.4 — the Shadow Studio sheet loader, and the indirection that makes art swappable.
//
// This file is written BEFORE any art exists, against the version:2 contract, and it must
// NEVER THROW ON A MISSING SHEET (landmine 16). Missing, 404, corrupt, wrong shape: log
// once, draw the placeholder, keep playing. A failed export is not a crash.
//
// Landmine 4 is the one that costs an afternoon: Studio ships `offset_x`/`offset_y` which are
// just `-pivot`, and this file also honours a `pivot` override in the manifest. Applying BOTH
// puts the sprite exactly double the pivot off. THE MANIFEST PIVOT WINS WHEN PRESENT;
// otherwise Studio's pivot_x/pivot_y is used; the offsets are never read. One rule, stated
// once, here.

import { PLACEHOLDERS } from './placeholder.js';

const logged = new Set();
function warnOnce(key, msg) {
  if (logged.has(key)) return;
  logged.add(key);
  console.warn(`[sprites] ${msg}`);
}

export function createSprites(basePath = 'assets/') {
  const sheets = new Map();
  let manifest = { version: 1, sheets: {} };
  let ready = false;

  async function load() {
    try {
      const res = await fetch(`${basePath}manifest.json`, { cache: 'no-cache' });
      if (!res.ok) throw new Error(`manifest ${res.status}`);
      manifest = await res.json();
    } catch (e) {
      warnOnce('manifest', `no assets/manifest.json (${e.message}) — running on placeholder art, which is the supported configuration`);
      ready = true;
      return;
    }

    await Promise.all(Object.entries(manifest.sheets || {}).map(async ([name, def]) => {
      const entry = { def, img: null, meta: null, failed: false };
      sheets.set(name, entry);
      try {
        const [img, meta] = await Promise.all([loadImage(basePath + def.png), loadMeta(basePath + def.meta)]);
        // §8.3 — a metadata file with no "version" predates 2026-08-06. Its vertical motion
        // has been flattened out and its offset_y is a placeholder that was never measured.
        // Re-export rather than compensating; refusing it here is how that gets noticed.
        if (meta && meta.version === undefined) {
          warnOnce(name, `${name}: pre-v2 Studio export (no "version" key). Re-export it — its offset_y is a phantom and its vertical motion is gone. Falling back to placeholder.`);
          entry.failed = true;
          return;
        }
        entry.img = img;
        entry.meta = meta;
        if (meta && meta.dropped_frames && meta.dropped_frames.length) {
          warnOnce(`${name}:dropped`, `${name}: export dropped ${meta.dropped_frames.length} frame(s) — the animation is shorter than asked for`);
        }
      } catch (e) {
        warnOnce(name, `${name}: ${e.message} — using placeholder "${def.placeholder}"`);
        entry.failed = true;
      }
    }));
    ready = true;
  }

  function loadImage(url) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error(`could not load ${url}`));
      img.src = url;
    });
  }

  async function loadMeta(url) {
    if (!url) return null;
    const res = await fetch(url, { cache: 'no-cache' });
    if (!res.ok) throw new Error(`meta ${res.status} for ${url}`);
    return res.json();
  }

  /** THE pivot rule (landmine 4). Manifest wins; else Studio's; never both, never offsets. */
  function pivotOf(entry) {
    if (entry.def.pivot) return entry.def.pivot;
    const m = entry.meta;
    if (m && m.pivot_x !== undefined) return [m.pivot_x, m.pivot_y];
    return [(m ? m.frame_width : 0) * 0.5, m ? m.frame_height : 0];
  }

  return {
    load,
    get ready() { return ready; },
    get manifest() { return manifest; },

    /** Does a real sheet exist for `name`, or are we on the placeholder? */
    has(name) {
      const e = sheets.get(name);
      return !!(e && e.img && !e.failed);
    },

    /**
     * Draw sheet `name` at world position, or fall back to its placeholder. The caller has
     * already set up translate/rotate; this draws in the same unit space either way, which
     * is what makes swapping art a manifest edit and not a code edit (§13 phase 6 gate).
     * @param {number} t seconds, for animation
     */
    draw(ctx, name, t, placeholderArgs) {
      const e = sheets.get(name);
      if (!e || !e.img || e.failed) {
        const def = (e && e.def) || (manifest.sheets && manifest.sheets[name]) || {};
        const fn = PLACEHOLDERS[def.placeholder];
        if (fn) fn(ctx, ...(placeholderArgs || []));
        else if (def.placeholder) warnOnce(`ph:${name}`, `manifest names placeholder "${def.placeholder}" which placeholder.js does not export`);
        return false;
      }
      const m = e.meta;
      const fps = e.def.fps || m.fps || 12;
      const count = m.frame_count || 1;
      const loop = e.def.loop !== false;
      let f = Math.floor(t * fps);
      f = loop ? ((f % count) + count) % count : Math.min(f, count - 1);

      const [px, py] = pivotOf(e);
      const scale = e.def.scale || 1;             // metres per pixel
      ctx.save();
      ctx.imageSmoothingEnabled = e.def.smooth !== false;   // landmine 18
      ctx.scale(scale, -scale);                   // sheets are y-down, the world is y-up
      ctx.drawImage(
        e.img,
        f * m.frame_width, 0, m.frame_width, m.frame_height,
        -px, -py, m.frame_width, m.frame_height,
      );
      ctx.restore();
      return true;
    },

    /** Debug key P — a crosshair on the pivot. A wrong pivot is invisible in a still and
     *  obvious in motion, which is the worst combination to debug by guesswork (§8.3). */
    drawPivot(ctx, size) {
      ctx.beginPath();
      ctx.moveTo(-size, 0); ctx.lineTo(size, 0);
      ctx.moveTo(0, -size); ctx.lineTo(0, size);
      ctx.strokeStyle = '#ff00ff';
      ctx.lineWidth = size * 0.12;
      ctx.stroke();
    },
  };
}
