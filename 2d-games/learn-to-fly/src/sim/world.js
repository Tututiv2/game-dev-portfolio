// §14 seam 2 — THE WORLD IS GENERATED FROM SEGMENTS, NEVER HARDCODED.
//
// `world.segmentAt(xMetres)` is the only way anything learns what is under the penguin.
// For LTF1 the provider returns "ocean forever". For LTF2 it returns snow with five
// obstacles on it and ocean past the wall. Neither is special-cased anywhere else, so the
// day the owner wants a continent past the wall it is a new provider, and the flight model,
// camera, collision and HUD already work there.
//
// An `if (x > 6000)` anywhere in sim/ forfeits this. Treat one as a build error.
//
// A segment is:
//   { surface, groundY, biome, parallax,
//     surfaceAt?(x) -> SURFACE.*     terrain may vary INSIDE a segment
//     heightAt?(x)  -> y             ...including height, which is how cliffs arrive later
//     obstacles?: [] }

export const SURFACE = { WATER: 'water', SOLID: 'solid', VOID: 'void' };

/**
 * @param {(index:number, x0:number, x1:number, T:object) => object} provider
 * @param {object} T tuning
 */
export function createWorld(provider, T, meta = {}) {
  const width = T.SEGMENT_M;
  const cache = new Map();

  function segmentAt(x) {
    const index = Math.floor(x / width);
    let seg = cache.get(index);
    if (seg === undefined) {
      seg = provider(index, index * width, (index + 1) * width, T);
      seg.index = index;
      seg.x0 = index * width;
      seg.x1 = (index + 1) * width;
      cache.set(index, seg);
    }
    return seg;
  }

  function surfaceAt(x) {
    const seg = segmentAt(x);
    return seg.surfaceAt ? seg.surfaceAt(x) : seg.surface;
  }

  /** y of the solid surface under x, or null where there is none. */
  function groundYAt(x) {
    if (surfaceAt(x) !== SURFACE.SOLID) return null;
    const seg = segmentAt(x);
    return seg.heightAt ? seg.heightAt(x) : seg.groundY;
  }

  /** Every obstacle whose span overlaps [xa, xb]. Deduplicated across segments. */
  function obstaclesNear(xa, xb) {
    const lo = Math.floor(Math.min(xa, xb) / width);
    const hi = Math.floor(Math.max(xa, xb) / width);
    const out = [];
    for (let i = lo; i <= hi; i++) {
      const seg = segmentAt(i * width);
      if (!seg.obstacles) continue;
      for (const o of seg.obstacles) if (!out.includes(o)) out.push(o);
    }
    return out;
  }

  return {
    meta, segmentAt, surfaceAt, groundYAt, obstaclesNear,
    segmentWidth: width,
    reset() { cache.clear(); },
  };
}

/**
 * The complement of a segment's solid spans — i.e. exactly where there is open water.
 *
 * The renderer needs this rather than "is this whole segment water", because a segment is
 * 200 m wide and the ice shelf is 60: culling water per segment draws the ocean straight
 * over the shelf the ramp is standing on.
 */
export function waterSpansOf(seg) {
  const solid = (seg.solidSpans || []).slice().sort((a, b) => a[0] - b[0]);
  const out = [];
  let x = seg.x0;
  for (const [s, e] of solid) {
    if (s > x) out.push([x, Math.min(s, seg.x1)]);
    x = Math.max(x, e);
  }
  if (x < seg.x1) out.push([x, seg.x1]);
  return out;
}

// ── providers ───────────────────────────────────────────────────────────────

/**
 * LTF1: an ice shelf behind the ramp, open ocean ahead of it, forever.
 * The shelf exists so that flying BACKWARDS lands on something (medals 8 and 9).
 */
export function oceanProvider() {
  return (index, x0, x1, T) => {
    const back = -T.SHELF_BACK_M;
    const touchesShelf = x1 > back && x0 < 0;
    const seg = {
      surface: touchesShelf ? SURFACE.SOLID : SURFACE.WATER,
      groundY: T.SEA_LEVEL_Y,
      biome: touchesShelf ? 'shelf' : 'ocean',
      parallax: 'ocean',
      // The exact solid intervals inside this segment. The renderer draws these rather
      // than sampling the surface across the segment, so the shelf edge lands where the
      // collision says it does instead of within one sample of it.
      solidSpans: touchesShelf ? [[Math.max(x0, back), Math.min(x1, 0)]] : [],
    };
    if (touchesShelf) {
      seg.surfaceAt = (x) => (x >= back && x < 0 ? SURFACE.SOLID : SURFACE.WATER);
    }
    return seg;
  };
}

/**
 * LTF2: snow underfoot out past the wall, then ocean. Obstacles are live objects owned by
 * the caller (so damage persists between days — §7.3) and are handed in, not built here.
 */
export function fieldProvider(obstacles, fieldEndM) {
  return (index, x0, x1, T) => {
    const back = -T.SHELF_BACK_M;
    const solid = (x) => (x >= back && x < fieldEndM ? SURFACE.SOLID : SURFACE.WATER);
    const wholly = x0 >= back && x1 <= fieldEndM;
    const lo = Math.max(x0, back), hi = Math.min(x1, fieldEndM);
    return {
      surface: wholly ? SURFACE.SOLID : solid(x0),
      surfaceAt: wholly ? null : solid,
      groundY: T.SEA_LEVEL_Y,
      solidSpans: hi > lo ? [[lo, hi]] : [],
      obstacles: obstacles.filter((o) => o.x1 >= x0 && o.x0 <= x1),
      biome: x0 < fieldEndM ? 'snowfield' : 'ocean',
      parallax: x0 < fieldEndM ? 'snowfield' : 'ocean',
    };
  };
}
