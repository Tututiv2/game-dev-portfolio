// The AcousticBackend boundary.
//
// Two implementations of "put a wall between the listener and the weather", swappable live with B
// (BUILD_PROMPT §4). This is not a compromise between the simple approach and the real one — it is
// the argument for the real one, made in a form the owner can check with his own ears while
// standing at his own window, mid-storm, without taking anyone's word for it.
//
// It also forces the seam that the real space game will need in order to lift this system out.

export class AcousticBackend {
  /**
   * @param {AudioContext} ctx
   * @param {AudioNode} dest where this backend's output goes
   * @param {object} buffers shared, pre-rendered source material
   */
  constructor(ctx, dest, buffers) {
    this.ctx = ctx;
    this.dest = dest;
    this.buffers = buffers;
    this.started = false;
  }

  /** Build and start the graph. */
  start() { throw new Error('not implemented'); }

  /**
   * Apply one frame of smoothed acoustics.
   * @param {import('./smooth.js').SmoothedAcoustics} s
   * @param {object} listener pose
   * @param {object} weather
   */
  apply(s, listener, weather) { throw new Error('not implemented'); }

  /**
   * Tear everything down. Web Audio nodes are NOT garbage collected while connected — a storm left
   * running for an hour with emitters leaking would strangle the tab — so this must actually
   * disconnect, not just drop references.
   */
  stop() { throw new Error('not implemented'); }

  /** For the HUD. */
  describe() { return { name: 'none', nodes: 0 }; }
}

/** Count of live AudioNodes, so the HUD can show a leak before it becomes a stutter. */
export class NodeTally {
  constructor() { this.n = 0; }
  add(k = 1) { this.n += k; return this; }
  sub(k = 1) { this.n -= k; return this; }
}
