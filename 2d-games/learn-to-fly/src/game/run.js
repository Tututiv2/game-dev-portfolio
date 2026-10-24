// ONE FLIGHT, START TO REST.
//
// This is the seam between the pure sim and everything that is not pure. It steps
// physics.step(), drains `p.events` onto the bus, turns those events into particles and
// sound, records the input stream for §15's replay, and applies the two secret-shop items
// that reach into a live flight.
//
// It is also the ONLY place that decides a run is over (§4.10): rest, then RESULTS_DELAY
// seconds of stillness with the camera settling, and only then the results panel. Do not cut
// to the shop (§2.7).

import { createState, step, STATE, glideRatio } from '../sim/physics.js';
import { makeObstacleHook } from '../sim/collision.js';
import { packInput } from '../core/input.js';

export function createRun(T, { bus, fx, audio, camera }) {
  return function startRun(mode, save, opts = {}) {
    const L = mode.loadout(save);
    const world = mode.world(save);
    const obstacles = mode.obstacles(save);
    const hooks = obstacles ? { obstacles: makeObstacleHook() } : undefined;
    const p = createState(L, T, { collectEvents: true });
    const secrets = (mode.slot(save).secrets) || {};

    const recorded = [];
    let restHold = 0;
    let done = false;
    let phoenixUsed = false;
    let robUsed = false;
    let prevState = p.state;

    camera.snapTo(p);
    // §2.1 — the launch is a hard cut to speed and THE CAMERA SNAPS TO IT LATE, so the first
    // half-second is a lurch. Starting the camera behind the ramp is what produces that.
    camera.cam.x -= T.CAM_LAUNCH_LAG_M;

    return {
      p, L, world, obstacles, mode,
      get finished() { return done; },
      get recorded() { return recorded; },
      glideRatio: glideRatio(L, T),

      step(dt, input) {
        if (done) return;
        recorded.push(packInput(input));
        step(p, input, L, world, T, hooks);

        // ── secret shop items that reach into a live flight (§7.7) ──
        if (secrets.rob && !robUsed && p.state === STATE.FLIGHT && p.y > T.ROB_ALT_M && L.fuelSeconds > 0) {
          robUsed = true;
          p.fuel = L.fuelSeconds;
          bus.emit('rob:refuel', {});
          fx.float('R.O.B. 64!', p.x, p.y + 2, '#8fe3ff');
          audio.medal();
        }
        if (secrets.phoenix && !phoenixUsed
            && (p.state === STATE.GROUND || p.state === STATE.WATER) && prevState !== p.state) {
          phoenixUsed = true;
          p.state = STATE.FLIGHT;
          p.vy = T.PHOENIX_BOOST;
          p.vx = Math.max(p.vx, T.PHOENIX_BOOST * 0.4);
          p.restTimer = 0;
          bus.emit('phoenix:rise', {});
          fx.explosion(p.x, p.y, 0.6);
          fx.float('RISE!', p.x, p.y + 2, '#ffb03f');
          audio.explosion();
          camera.kick(0.7);
        }
        prevState = p.state;

        // ── events -> bus, fx, audio ──
        for (const e of p.events) {
          bus.emit(e.type, e);
          switch (e.type) {
            case 'launch':
              audio.launch(); camera.kick(0.35); break;
            case 'water:skip':
              fx.spray(p.x, T.SEA_LEVEL_Y, e.speed, e.n);
              audio.skip(e.n);
              camera.kick(Math.min(0.3, e.speed * 0.004));
              if (e.n >= 3) fx.float(`x${e.n}`, p.x, 2.5, '#ffffff');
              break;
            case 'water:dive':
              fx.splash(p.x, T.SEA_LEVEL_Y, e.speed); audio.splash();
              camera.kick(Math.min(0.5, e.speed * 0.006)); break;
            case 'water:surface':
              fx.spray(p.x, T.SEA_LEVEL_Y, 8, 1); audio.surface(); break;
            case 'ground:impact':
              if (e.speed > 6) {
                fx.debris(p.x, p.y, Math.min(20, Math.round(e.speed * 0.4)));
                audio.thud(Math.min(1, e.speed / 40));
                camera.kick(Math.min(0.8, e.speed * 0.012));
              }
              break;
            case 'glider:snap':
              fx.debris(p.x, p.y, 26, '#d8d2c4');
              audio.snap(); camera.kick(0.6);
              fx.float('WING!', p.x, p.y + 2, '#ff5b4a');
              break;
            case 'obstacle:hit':
              fx.iceBurst(p.x, p.y, e.blocked ? 6 : 18);
              audio.crack(); camera.kick(0.55);
              if (e.blocked) fx.float('needs a bomb', p.x, p.y + 2, '#ffd166');
              break;
            case 'obstacle:destroyed':
              fx.explosion(p.x, p.y, 1.4);
              fx.iceBurst(p.x, p.y, 50);
              audio.explosion(); camera.kick(1);
              fx.float('DESTROYED', p.x, p.y + 3, '#ffc93c');
              break;
            case 'bomb:detonate':
              fx.explosion(p.x, p.y, 2.4);
              audio.explosion(); camera.kick(1);
              break;
            case 'crash:planet':
              fx.explosion(p.x, p.y, 2); camera.kick(1); break;
            default: break;
          }
        }
        p.events.length = 0;

        if (p.thrusting) fx.exhaust(p.x, p.y, p.theta, 1);

        if (p.finished) {
          restHold += dt;
          if (restHold >= T.RESULTS_DELAY) done = true;
        }
      },

      /** Continuous audio that tracks state rather than events. */
      audioTick() {
        const ceiling = Math.max(1, L.glider.vMaxMph);
        audio.wind(Math.min(1, (p.speed * T.MPH_PER_MS) / ceiling));
        audio.rocket(p.thrusting);
        audio.creak(p.gliderHp < 1 ? 1 - p.gliderHp : (p.stressed ? 0.2 : 0));
      },

      stopAudio() { audio.stopAllLoops(); },
    };
  };
}
