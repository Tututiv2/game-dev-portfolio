// Thunder.
//
// One synth, filtered by distance. Distant thunder is a long low rumble and near thunder is a
// sharp crack, and they are THE SAME SOUND — air absorption eats high frequencies over distance far
// faster than low ones. Modelling that rather than authoring two sounds gives storms a real sense
// of scale as they approach, for free. See BUILD_PROMPT §3.5.
//
// Pure maths and scheduling; the engine turns a Strike into noise.

export const SPEED_OF_SOUND = 343; // m/s

/** Flash now, boom later. Three seconds per kilometre, as every child knows. */
export function boomDelay(distanceM) {
  return distanceM / SPEED_OF_SOUND;
}

/**
 * How distance shapes the sound.
 *  cutoff   air absorption — the top end is gone by a kilometre
 *  gain     spreading loss, gently rolled off so far strikes stay audible as rumble
 *  spread   envelope stretch — a crack becomes a roll, because the sound arrives via many paths
 *           of slightly different length
 *
 * `gain` falls as 1/r and not as 1/r². Sound INTENSITY obeys the inverse square law, but a gain
 * multiplies an amplitude, and amplitude is the square root of intensity — so the correct spreading
 * law here is the first power. Writing the inverse square straight into an amplitude squares the
 * attenuation: this said -24 dB at 3 km where the physics says -12, and the panner (see
 * backendPortal) then quietly charged for the same distance a second time. Between them a strike
 * two miles off arrived 39 dB down, underneath a downpour. It was firing perfectly and could not
 * be heard.
 */
export function strikeShape(distanceM) {
  const km = distanceM / 1000;
  return {
    cutoff: Math.max(150, 13000 * Math.exp(-km * 1.3)),
    gain: Math.min(1, 1 / (1 + km * 1.6)),
    spread: 0.35 + km * 1.9,
    subGain: Math.min(1, 0.35 + km * 0.4), // the low thump survives distance best
  };
}

export class Strike {
  constructor(distance, azimuth, rnd) {
    this.distance = distance;
    this.azimuth = azimuth;          // radians, world space
    this.shape = strikeShape(distance);
    this.delay = boomDelay(distance);
    this.age = 0;
    this.flashed = false;
    this.boomed = false;
    this.seed = rnd ? rnd() : 0.5;
    // A near strike often flickers two or three times.
    this.flickers = distance < 2500 ? 2 + Math.floor((rnd ? rnd() : 0.5) * 2) : 1;
  }

  /** Flash brightness right now, 0..1. Multi-stage flicker, fast decay. */
  flashIntensity() {
    if (this.age > 0.6) return 0;
    let v = 0;
    for (let i = 0; i < this.flickers; i++) {
      const t = this.age - i * 0.085;
      if (t >= 0 && t < 0.12) v = Math.max(v, (1 - t / 0.12) * (i === 0 ? 1 : 0.6));
    }
    return v * Math.min(1, 1400 / Math.max(300, this.distance));
  }
}

export class ThunderScheduler {
  constructor(rnd) {
    this.rnd = rnd;
    this.cooldown = 2.5;
    /** @type {Strike[]} */
    this.active = [];
  }

  /**
   * @param {number} dt
   * @param {number} ratePerMin strikes per minute at the current weather intensity
   * @returns {Strike[]} strikes that flashed this frame
   */
  update(dt, ratePerMin) {
    const started = [];
    for (const s of this.active) s.age += dt;
    this.active = this.active.filter(s => s.age < s.delay + s.shape.spread + 6);

    if (ratePerMin > 0.01) {
      this.cooldown -= dt;
      if (this.cooldown <= 0) {
        const s = this.strike();
        started.push(s);
        // Poisson-ish spacing so strikes never feel metronomic.
        const mean = 60 / ratePerMin;
        this.cooldown = mean * (0.35 + this.rnd() * 1.5);
      }
    }
    return started;
  }

  /** Force one — the T key, and the tests. */
  strike(distance = null) {
    const d = distance !== null ? distance
      // Biased toward the middle distance: mostly rumbles, occasionally right overhead.
      : 180 + Math.pow(this.rnd(), 1.7) * 7500;
    const s = new Strike(d, this.rnd() * Math.PI * 2, this.rnd);
    this.active.push(s);
    return s;
  }

  /** Strikes whose boom should be fired this frame. */
  dueBooms() {
    const out = [];
    for (const s of this.active) {
      if (!s.boomed && s.age >= s.delay) { s.boomed = true; out.push(s); }
    }
    return out;
  }

  /** Combined flash for the lighting, 0..1. */
  flash() {
    let v = 0;
    for (const s of this.active) v = Math.max(v, s.flashIntensity());
    return v;
  }
}
