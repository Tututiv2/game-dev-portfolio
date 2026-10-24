// Beat grid + parry grading. Pure: no DOM, no audio, no Math.random.
//
// The whole skill mechanic lives here. A parry is graded by how far the press
// sits from the moment the hit LANDS, and in the awakened fight every hit lands
// on a beat of the song -- so the music is the tell. Someone who has not learned
// the song reacts to the swing and is late; someone who has, presses on the beat.

export const WINDOWS = {
  flawless: 0.022,  // within 22 ms either side = AURA, the tier above perfect (the owner asked for one)
  perfect: 0.075,   // press up to 75 ms before the hit lands = PERFECT
  lateGrace: 0.06,  // ...or up to 60 ms after it (input + display latency is real)
  good: 0.20,       // earlier than perfect but within 200 ms = a plain block
  spamLock: 0.35,   // a press within 350 ms of the last press does not count
};

export class BeatGrid {
  // t0 = time of beat 0 (any clock), bpm = beats per minute.
  constructor(bpm, t0 = 0, beatsPerBar = 4) {
    this.bpm = bpm;
    this.t0 = t0;
    this.beatsPerBar = beatsPerBar;
  }
  get beat() { return 60 / this.bpm; }
  get bar() { return this.beat * this.beatsPerBar; }
  // Fractional beat index at time t.
  beatAt(t) { return (t - this.t0) / this.beat; }
  beatTime(i) { return this.t0 + i * this.beat; }
  // First grid point at or after t, on a subdivision of the beat (1 = beats, 2 = eighths).
  next(t, sub = 1) {
    const step = this.beat / sub;
    const k = Math.ceil((t - this.t0) / step - 1e-9);
    return this.t0 + k * step;
  }
  nextBar(t) {
    const k = Math.ceil((t - this.t0) / this.bar - 1e-9);
    return this.t0 + k * this.bar;
  }
  // Distance (seconds, signed) from t to the nearest beat. Negative = t is before it.
  offset(t, sub = 1) {
    const step = this.beat / sub;
    const x = (t - this.t0) / step;
    return (x - Math.round(x)) * step;
  }
  // 0..1 pulse that peaks exactly on each beat -- for the HUD.
  pulse(t) {
    const x = this.beatAt(t);
    const f = x - Math.floor(x);
    return Math.exp(-f * 7);
  }
}

// d = press - impact. Negative = early.
export function gradeParry(press, impact, w = WINDOWS) {
  if (press == null) return { grade: 'miss', d: null };
  const d = press - impact;
  if (d > w.lateGrace) return { grade: 'miss', d };
  if (Math.abs(d) <= w.flawless) return { grade: 'flawless', d };
  if (d >= -w.perfect) return { grade: 'perfect', d };
  if (d >= -w.good) return { grade: 'good', d };
  return { grade: 'miss', d };
}

// Holds parry presses, enforces the mash lock, and lets each press answer
// exactly one hit. Times are whatever clock the caller grades against.
export class ParryInput {
  constructor(w = WINDOWS) {
    this.w = w;
    this.lastPress = -Infinity;
    this.accepted = [];   // [{t, used}]
  }
  press(t) {
    const ok = t - this.lastPress >= this.w.spamLock;
    this.lastPress = t;   // every press resets the lock, so mashing never works
    if (ok) {
      this.accepted.push({ t, used: null });
      if (this.accepted.length > 8) this.accepted.shift();
    }
    return ok;
  }
  // Grade the hit landing at `impact`. Call once the late grace has passed.
  // A press answers every hit landing within `together` of the first one it answered:
  // two blows on the same beat are one moment, and one parry takes both.
  resolve(impact, together = 0.035) {
    let best = null;
    for (const p of this.accepted) {
      if (p.used != null && Math.abs(p.used - impact) > together) continue;
      const d = p.t - impact;
      if (d > this.w.lateGrace || d < -this.w.good) continue;
      if (!best || Math.abs(d) < Math.abs(best.t - impact)) best = p;
    }
    const g = gradeParry(best ? best.t : null, impact, this.w);
    if (best && g.grade !== 'miss' && best.used == null) best.used = impact;
    return g;
  }
  reset() { this.lastPress = -Infinity; this.accepted.length = 0; }
}
