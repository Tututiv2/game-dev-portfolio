// The audio engine: owns the AudioContext, the shared buffers, the listener, and whichever
// backend is currently live. It does not decide anything about acoustics — model.js does that and
// smooth.js makes it safe. This just applies numbers and moves the listener.

import { makeFoleyBuffer } from './synth.js';
import { loadBuffers } from './samples.js';
import { computeAcoustics, makeListener, DEFAULT_MIX } from './model.js';
import { SmoothedAcoustics } from './smooth.js';
import { PortalBackend } from './backendPortal.js';
import { BakedBackend, renderStems } from './backendBaked.js';
import { mat } from '../world/materials.js';

export class AudioEngine {
  constructor() {
    this.ctx = null;
    this.ready = false;
    this.smooth = new SmoothedAcoustics();
    this.backendName = 'portal';
    this.backend = null;
    this.frame = null;
    this.listener = makeListener([0, 0, 0], 0, 0);
    this.footCooldown = 0;
    this.masterVolume = 0.9;
    // Live mix + solo. Taste is the thing being optimised here and it cannot be measured from
    // inside the process, so it has to be adjustable by the person listening.
    this.mix = { ...DEFAULT_MIX };
    this.solo = null;   // null | 'roof' | 'diffuse' | 'portals' | 'wind'
  }

  /**
   * Must be called from a user gesture — an AudioContext created without one starts suspended and
   * never makes a sound. The title screen exists partly to be that gesture.
   */
  async init() {
    if (this.ready) return;
    const AC = globalThis.AudioContext || globalThis.webkitAudioContext;
    this.ctx = new AC({ latencyHint: 'interactive' });
    if (this.ctx.state === 'suspended') await this.ctx.resume();

    this.out = this.ctx.createGain();
    this.out.gain.value = this.masterVolume;
    // A gentle limiter: thunder overhead plus an open door should not clip.
    this.limiter = this.ctx.createDynamicsCompressor();
    this.limiter.threshold.value = -8;
    this.limiter.knee.value = 6;
    this.limiter.ratio.value = 8;
    this.limiter.attack.value = 0.004;
    this.limiter.release.value = 0.18;
    this.out.connect(this.limiter);
    this.limiter.connect(this.ctx.destination);

    // Recordings if they are there, synth if they are not — see samples.js. Awaited before the
    // backend is built, because a backend with no buffers is silent rather than merely wrong.
    this.buffers = await loadBuffers(this.ctx);
    this.bufferSource = this.buffers.source;
    this.foley = new Map();

    this.stems = await renderStems(this.ctx.sampleRate, this.buffers);

    this._makeBackend(this.backendName);
    this.ready = true;
  }

  _makeBackend(name) {
    if (this.backend) this.backend.stop();
    this.backend = name === 'baked'
      ? new BakedBackend(this.ctx, this.out, this.buffers, this.stems)
      : new PortalBackend(this.ctx, this.out, this.buffers);
    this.backend.start();
    this.backendName = name;
  }

  /** The B key. Live, mid-storm, no reload and no dropout. */
  toggleBackend() {
    this._makeBackend(this.backendName === 'portal' ? 'baked' : 'portal');
    return this.backendName;
  }

  /**
   * One frame. Returns the acoustic frame so the HUD can show what the system believes.
   * Safe to call before init() — it simply does nothing.
   */
  update(scene, pose, dt) {
    this.listener = pose;
    if (!this.ready) return null;

    const frame = computeAcoustics({
      grid: scene.grid,
      graph: scene.graph,
      listener: pose,
      weather: { rain: scene.weather.rain, wind: scene.weather.windNow },
      mix: this.mix,
    });
    this.frame = frame;

    if (!this.smooth.primed) this.smooth.prime(frame);
    else this.smooth.update(frame, dt);

    this._placeListener(pose);
    this.backend.solo = this.solo;
    this.backend.apply(this.smooth, pose, scene.weather);

    if (this.footCooldown > 0) this.footCooldown -= dt;
    return frame;
  }

  _placeListener(pose) {
    const l = this.ctx.listener;
    const [x, y, z] = pose.pos;
    const f = pose.forward, u = pose.up;
    if (l.positionX) {
      const t = this.ctx.currentTime;
      l.positionX.setValueAtTime(x, t);
      l.positionY.setValueAtTime(y, t);
      l.positionZ.setValueAtTime(z, t);
      l.forwardX.setValueAtTime(f[0], t);
      l.forwardY.setValueAtTime(f[1], t);
      l.forwardZ.setValueAtTime(f[2], t);
      l.upX.setValueAtTime(u[0], t);
      l.upY.setValueAtTime(u[1], t);
      l.upZ.setValueAtTime(u[2], t);
    } else {
      l.setPosition(x, y, z);
      l.setOrientation(f[0], f[1], f[2], u[0], u[1], u[2]);
    }
  }

  thunder(strike) {
    // The frame goes with it so the room can muffle the boom. It is the LAST frame rather than a
    // fresh solve on purpose: thunder fires from the scheduler, outside the audio update, and one
    // frame of staleness is 16 ms against a sound that takes seconds to arrive.
    if (this.ready && this.backend.thunder) this.backend.thunder(strike, this.listener, this.frame);
  }

  /** Footsteps take their character from whatever you are standing on. */
  footstep(materialId, speed = 1) {
    if (!this.ready || this.footCooldown > 0) return;
    const spec = mat(materialId).step;
    if (!spec) return;
    let buf = this.foley.get(materialId);
    if (!buf) {
      buf = makeFoleyBuffer(this.ctx, spec, 100 + materialId * 17);
      this.foley.set(materialId, buf);
    }
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.playbackRate.value = 0.86 + Math.random() * 0.28;
    const g = this.ctx.createGain();
    g.gain.value = spec.gain * (0.5 + speed * 0.5) * 0.5;
    src.connect(g);
    // Footsteps go through the room, so your own steps tell you what size space you are in.
    if (this.backend.reverb) {
      const send = this.ctx.createGain();
      send.gain.value = 0.6;
      g.connect(send);
      send.connect(this.backend.reverb.input);
      src.onended = () => { try { src.disconnect(); g.disconnect(); send.disconnect(); } catch (e) { /* gone */ } };
    } else {
      src.onended = () => { try { src.disconnect(); g.disconnect(); } catch (e) { /* gone */ } };
    }
    g.connect(this.out);
    src.start();
    this.footCooldown = 0.28 / Math.max(0.4, speed);
  }

  setVolume(v) {
    this.masterVolume = Math.max(0, Math.min(1, v));
    if (this.out) this.out.gain.setTargetAtTime(this.masterVolume, this.ctx.currentTime, 0.05);
  }

  describe() {
    if (!this.ready) return { name: 'not started', nodes: 0 };
    return {
      ...this.backend.describe(),
      weather: this.bufferSource,
      mix: this.mix,
      solo: this.solo,
    };
  }
}
