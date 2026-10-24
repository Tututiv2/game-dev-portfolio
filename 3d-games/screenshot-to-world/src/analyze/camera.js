/**
 * The pinhole camera that is assumed to have taken the photo.
 *
 * This is the piece that makes the world feel like the place in the picture
 * rather than a wallpaper. Once we know the horizon row, every ground pixel has
 * a real distance: rows far below the horizon are near your feet, rows just
 * above it are hundreds of metres out. Objects standing on that ground plane
 * get a real distance and a real height from the same relation.
 *
 *   z = f * eyeHeight / (y - yHorizon)
 *   x = (px - cx) * z / f
 *
 * f is in pixels, derived from an assumed vertical field of view. Most phone
 * and camera shots land near 60 deg vertical, and the failure mode of guessing
 * wrong is a world that is uniformly too deep or too shallow -- which reads as
 * a stylistic choice, not as broken.
 */

export const DEFAULT_FOV_DEG = 60;
export const DEFAULT_EYE_HEIGHT = 1.7; // metres

/** Distances beyond this are treated as backdrop; keeps the near horizon sane. */
export const MAX_DEPTH = 320;
export const MIN_DEPTH = 0.9;

export function makeCamera(imgWidth, imgHeight, horizonPx, opts = {}) {
  const fovDeg = opts.fovDeg ?? DEFAULT_FOV_DEG;
  const eyeHeight = opts.eyeHeight ?? DEFAULT_EYE_HEIGHT;
  const f = imgHeight / 2 / Math.tan((fovDeg * Math.PI) / 360);
  const cx = imgWidth / 2;

  return {
    width: imgWidth,
    height: imgHeight,
    fovDeg,
    eyeHeight,
    f,
    cx,
    yh: horizonPx,

    /** Ground depth (metres) for an image row. Infinity at/above the horizon. */
    depthAtRow(y) {
      const dy = y - horizonPx;
      if (dy <= 0.5) return Infinity;
      return clampDepth((f * eyeHeight) / dy);
    },

    /** Image row for a ground point at depth z. */
    rowAtDepth(z) {
      return horizonPx + (f * eyeHeight) / Math.max(0.0001, z);
    },

    /** Lateral world offset (metres) for an image column at depth z. */
    xAtCol(col, z) {
      return ((col - cx) * z) / f;
    },

    /** Image column for a world point (x, z). */
    colAtX(x, z) {
      return cx + (x * f) / Math.max(0.0001, z);
    },

    /** Metres per pixel of vertical extent, for an object standing at depth z. */
    metresPerPixelAt(z) {
      return z / f;
    },

    /**
     * A standing object: base row sets its distance, the base-to-top pixel span
     * sets its real height.
     */
    standingObject(baseRow, topRow, leftCol, rightCol) {
      const z = this.depthAtRow(baseRow);
      if (!Number.isFinite(z)) return null;
      const mpp = this.metresPerPixelAt(z);
      return {
        z,
        height: Math.max(0.2, (baseRow - topRow) * mpp),
        width: Math.max(0.2, (rightCol - leftCol) * mpp),
        x: this.xAtCol((leftCol + rightCol) / 2, z),
      };
    },
  };
}

function clampDepth(z) {
  return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, z));
}
