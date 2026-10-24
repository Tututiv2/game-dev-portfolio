// BACKEND A — what most games ship.
//
// Two pre-rendered stems, "outside" and "inside", crossfaded as you cross the threshold. Cheap,
// robust, and about a hundred lines.
//
// The stems are rendered AT BOOT from the same synth the portal backend uses, through an
// OfflineAudioContext. Nothing is recorded and nothing ships as a file, and — the point — pressing
// B compares two TECHNIQUES on identical source material rather than two different rainstorms.
//
// What it structurally cannot do, and what you should listen for when you toggle:
//   · the noise is nowhere. Crack the window and the room gets louder as a whole; there is no
//     sound AT the window to turn your head toward.
//   · the room has one size forever. Walking into the nook does not tighten anything.
//   · a door is either open or shut. Half-open is a volume knob, not a smaller hole.
//   · the wind never whistles, because a whistle is a property of a gap and there are no gaps.

import { AcousticBackend } from './backend.js';

const LOOP = 6.0;

/**
 * Render the two stems offline. Both are made from the same rain, wind and roof buffers the live
 * backend uses; the only difference is that the "inside" filtering is baked in and fixed, decided
 * here once for every room in the world.
 */
export async function renderStems(sampleRate, buffers) {
  const OAC = globalThis.OfflineAudioContext || globalThis.webkitOfflineAudioContext;

  const loop = (ctx, buf, gain, dest) => {
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.loop = true;
    const g = ctx.createGain();
    g.gain.value = gain;
    src.connect(g); g.connect(dest);
    src.start();
    return g;
  };

  // ---- outside: the storm, unobstructed --------------------------------------------------
  const outCtx = new OAC(2, Math.ceil(sampleRate * LOOP), sampleRate);
  loop(outCtx, buffers.rain, 1.0, outCtx.destination);
  loop(outCtx, buffers.wind, 0.5, outCtx.destination);
  const outside = await outCtx.startRendering();

  // ---- inside: one fixed guess at what "indoors" sounds like -----------------------------
  const inCtx = new OAC(2, Math.ceil(sampleRate * LOOP), sampleRate);
  const wall = inCtx.createBiquadFilter();
  wall.type = 'lowpass';
  wall.frequency.value = 760;   // a wall, decided once, for every wall
  wall.Q.value = 0.7;
  wall.connect(inCtx.destination);
  loop(inCtx, buffers.rain, 0.11, wall);

  const windWall = inCtx.createBiquadFilter();
  windWall.type = 'lowpass';
  windWall.frequency.value = 1600;
  windWall.connect(inCtx.destination);
  loop(inCtx, buffers.wind, 0.22, windWall);

  // The roof still drums — that part is not a portal trick, and leaving it out would be a straw man.
  loop(inCtx, buffers.roof, 0.62, inCtx.destination);
  const inside = await inCtx.startRendering();

  return { outside, inside };
}

export class BakedBackend extends AcousticBackend {
  constructor(ctx, dest, buffers, stems) {
    super(ctx, dest, buffers);
    this.stems = stems;
    this.voices = new Set();
  }

  start() {
    if (this.started) return;
    const { ctx } = this;
    this.started = true;

    this.master = ctx.createGain();
    this.master.connect(this.dest);

    const mk = buf => {
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.loop = true;
      const g = ctx.createGain();
      g.gain.value = 0;
      src.connect(g);
      g.connect(this.master);
      src.start();
      return { src, g };
    };
    this.outside = mk(this.stems.outside);
    this.inside = mk(this.stems.inside);
  }

  apply(s) {
    if (!this.started) return;
    const now = this.ctx.currentTime;
    // Equal-power crossfade on the same outside/inside weight the portal backend uses, so the
    // switchover point is identical and only the technique differs.
    const w = Math.max(0, Math.min(1, s.outsideWeight));
    const a = Math.cos(w * Math.PI * 0.5);
    const b = Math.sin(w * Math.PI * 0.5);
    this.inside.g.gain.setTargetAtTime(a, now, 0.05);
    this.outside.g.gain.setTargetAtTime(b, now, 0.05);
  }

  /** Thunder the simple way: one sound, quieter when further off. No distance filtering. */
  thunder(strike) {
    if (!this.started) return;
    const { ctx } = this;
    const src = ctx.createBufferSource();
    src.buffer = this.buffers.thunder;
    const g = ctx.createGain();
    g.gain.value = strike.shape.gain * 0.8;
    src.connect(g); g.connect(this.master);
    const voice = { src, g };
    this.voices.add(voice);
    src.onended = () => {
      try { src.disconnect(); g.disconnect(); } catch (e) { /* gone */ }
      this.voices.delete(voice);
    };
    src.start();
  }

  stop() {
    if (!this.started) return;
    this.started = false;
    for (const v of this.voices) { try { v.src.stop(); } catch (e) { /* gone */ } }
    this.voices.clear();
    for (const p of [this.outside, this.inside]) {
      try { p.src.stop(); } catch (e) { /* gone */ }
      p.src.disconnect(); p.g.disconnect();
    }
    this.master.disconnect();
  }

  describe() {
    return { name: 'baked', nodes: 6 + this.voices.size * 2, portals: 0 };
  }
}
