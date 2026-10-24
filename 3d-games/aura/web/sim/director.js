// The story, as a state machine. Pure: it is told the facts each frame and
// answers with the events that just happened. game.js does the showing.
//
//   intro -> weak -> downed -> reveal -> getup -> awaken -> awakened -> finisher -> victory
//                                                              \-> dead -> (retry from getup)
//
// Everything from `awaken` on runs on the AUDIO clock, not the frame clock: the
// strike must land on the drop, and frames can hitch while audio never does.

import { WEAK } from './combat.js';

export const T = {
  introMax: 10,     // ambush after this long even if you stand still
  ambushZ: -9,      // ...or as soon as you walk this far up the road
  downed: 2.4,      // on the ground, ears ringing
  reveal: 4.8,      // the camera finds her
  getUpPresses: 3,  // SPACE presses to stand
  bwHold: 1.25,     // seconds of black and white after the drop
  finisher: 3.6,
};

export class Director {
  constructor() {
    this.phase = 'title';
    this.t = 0;          // seconds in this phase (frame clock)
    this.fightT = 0;     // seconds of weak-phase fighting
    this.presses = 0;
    this.plan = null;    // awakening timeline, audio clock
    this.fired = new Set();
  }

  go(phase) {
    this.phase = phase;
    this.t = 0;
    this.fired.clear();
    return phase;
  }

  start(skip = false) {
    this.fightT = 0;
    this.presses = 0;
    this.plan = null;
    return this.go(skip ? 'downed' : 'intro');
  }

  // Called once, the moment she stands. musicStart/dropAt/beat are audio-clock seconds.
  planAwakening(musicStart, dropAt, beat) {
    this.plan = {
      musicStart,
      dropAt,
      beat,
      ringAt: dropAt - 2 * beat,     // the strike prompt appears
      silenceAt: dropAt - beat,      // the song cuts out, the world holds its breath
      colorAt: dropAt + T.bwHold,    // colour comes back
    };
    return this.plan;
  }

  getUpPress() {
    if (this.phase !== 'getup') return 0;
    this.presses++;
    return this.presses;
  }

  once(key, cond, out) {
    if (cond && !this.fired.has(key)) { this.fired.add(key); out.push(key); }
  }

  // f = { hp, playerZ, audioNow, ogreHp }
  update(dt, f) {
    const ev = [];
    this.t += dt;
    switch (this.phase) {
      case 'intro':
        if (this.t >= T.introMax || f.playerZ >= T.ambushZ) { this.go('weak'); ev.push('ambush'); }
        break;
      case 'weak':
        this.fightT += dt;
        if (f.hp <= WEAK.downAt) { this.go('downed'); ev.push('downed'); }
        else if (this.fightT >= WEAK.forceDownAfter) { this.go('downed'); ev.push('blindside', 'downed'); }
        break;
      case 'downed':
        if (this.t >= T.downed) { this.go('reveal'); ev.push('reveal'); }
        break;
      case 'reveal':
        if (this.t >= T.reveal) { this.presses = 0; this.go('getup'); ev.push('getup'); }
        break;
      case 'getup':
        if (this.presses >= T.getUpPresses) { this.go('awaken'); ev.push('awaken'); }
        break;
      case 'awaken': {
        const p = this.plan;
        if (!p) break;
        const a = f.audioNow;
        this.once('ring', a >= p.ringAt, ev);
        this.once('silence', a >= p.silenceAt, ev);
        this.once('strike', a >= p.dropAt, ev);
        if (a >= p.colorAt) { this.go('awakened'); ev.push('color'); }
        break;
      }
      case 'awakened':
        if (f.hp <= 0) { this.go('dead'); ev.push('dead'); }
        else if (f.ogreHp <= 0) { this.go('finisher'); ev.push('finisher'); }
        break;
      case 'finisher':
        if (this.t >= T.finisher) { this.go('victory'); ev.push('victory'); }
        break;
    }
    return ev;
  }
}
