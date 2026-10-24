// Weather.
//
// A profile, not a hardcoded storm (BUILD_PROMPT §9). Different planets are different profiles;
// there is no `if (planet === ...)` anywhere and there never will be.
//
// Intensity is CONTINUOUS. States are targets the weather drifts toward, not modes it switches
// between, so a storm arrives rather than appearing.

export function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const TEMPERATE = {
  name: 'temperate',
  states: {
    //                rain  wind  gust  cloud  thunder (strikes/min)  skyTint
    calm:      { rain: 0.00, wind: 0.10, gust: 0.15, cloud: 0.15, thunder: 0 },
    breeze:    { rain: 0.00, wind: 0.38, gust: 0.55, cloud: 0.35, thunder: 0 },
    rain:      { rain: 0.45, wind: 0.30, gust: 0.35, cloud: 0.80, thunder: 0 },
    // Thunder rates are per MINUTE, and the first pass at them was a scheduling mistake dressed as
    // realism: 0.4/min means one strike every two and a half minutes, so the state the game boots
    // into had thunder in it that a player would never once encounter.
    downpour:  { rain: 0.85, wind: 0.52, gust: 0.60, cloud: 0.95, thunder: 1.6 },
    storm:     { rain: 1.00, wind: 0.88, gust: 1.00, cloud: 1.00, thunder: 7.0 },
  },
  order: ['calm', 'breeze', 'rain', 'downpour', 'storm'],
  // How fast the weather moves toward a new target, in seconds (time constant).
  tau: 7.0,
};

export class Weather {
  constructor(profile = TEMPERATE, seed = 1337) {
    this.profile = profile;
    this.rnd = mulberry32(seed);
    this.stateName = 'calm';
    const s = profile.states.calm;
    this.rain = s.rain;
    this.wind = s.wind;
    this.gust = s.gust;
    this.cloud = s.cloud;
    this.thunder = s.thunder;
    // Gusts ride on top of the base wind, so wind is never a constant hiss.
    this.gustPhase = this.rnd() * 100;
    this.gustValue = 0;
    this.time = 0;
  }

  setState(name) {
    if (!this.profile.states[name]) return;
    this.stateName = name;
  }

  /** Jump to the target immediately — used by tests and by the 1–5 debug keys. */
  snap(name) {
    if (name) this.setState(name);
    const t = this.profile.states[this.stateName];
    this.rain = t.rain; this.wind = t.wind; this.gust = t.gust;
    this.cloud = t.cloud; this.thunder = t.thunder;
  }

  update(dt) {
    this.time += dt;
    const t = this.profile.states[this.stateName];
    const k = 1 - Math.exp(-dt / this.profile.tau);
    this.rain += (t.rain - this.rain) * k;
    this.wind += (t.wind - this.wind) * k;
    this.gust += (t.gust - this.gust) * k;
    this.cloud += (t.cloud - this.cloud) * k;
    this.thunder += (t.thunder - this.thunder) * k;

    // Slow, irregular gusting — three detuned sines so it never obviously repeats.
    this.gustPhase += dt;
    const p = this.gustPhase;
    const g = (Math.sin(p * 0.23) * 0.5 + Math.sin(p * 0.41 + 1.7) * 0.3 + Math.sin(p * 0.11 + 4.2) * 0.2);
    this.gustValue = Math.max(0, g) * this.gust;
  }

  /** Wind including gusting — what the audio and the foliage both read. */
  get windNow() {
    return Math.min(1.4, this.wind * (1 + this.gustValue * 0.7));
  }

  get label() {
    return this.stateName;
  }
}
