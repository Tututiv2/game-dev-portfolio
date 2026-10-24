// §5 — ONE MACHINE, AND EVERY SCREEN IS A STATE. No screen owns the loop.
//
// The transitions are exactly §5's diagram. The two that matter and are easy to get wrong:
//
//   PRE_LAUNCH is not a formality. It is where the player reads their new upgrades on the
//   penguin and gets the beat of anticipation that makes the launch land. One second of
//   stillness before the game's loudest moment.
//
//   POST is a real state, not a `return to menu` (§14 seam 1). In the faithful build it is a
//   two-line stub that goes back to the launcher. But it EXISTS, it is in the machine, and it
//   is in the save — so adding content past the wall is adding content, not surgery on a
//   finale.

import { STATE as SIM } from '../sim/physics.js';
import { penguinCapsule, kiwipediaBook, rampScaffold, obstacleShape, PALETTE } from '../render/placeholder.js';
import { createResults, flavourFor } from '../render/ui.js';
import { payout } from '../sim/economy.js';

export const S = {
  BOOT: 'BOOT', LAUNCHER: 'LAUNCHER', INTRO: 'INTRO', DAY_START: 'DAY_START',
  PRE_LAUNCH: 'PRE_LAUNCH', LAUNCH: 'LAUNCH', FLIGHT: 'FLIGHT', RESULTS: 'RESULTS',
  SHOP: 'SHOP', SECRET: 'SECRET', VICTORY: 'VICTORY', ENDING: 'ENDING', POST: 'POST',
  MEDALS: 'MEDALS', SIDEBYSIDE: 'SIDEBYSIDE', SANDBOX: 'SANDBOX',
};

// ── intro cutscenes (§6.1, §7.1) ───────────────────────────────────────────────────────

export function ltf1Intro() {
  return [
    { hold: 3.4, caption: 'Kiwipedia: "Penguins are flightless birds."', draw: (ctx) => kiwipediaBook(ctx) },
    { hold: 3.0, caption: 'The penguin took this personally.', draw: (ctx, t) => { ctx.rotate(Math.sin(t * 9) * 0.05); penguinCapsule(ctx, { pose: 'idle', t }); } },
    { hold: 3.2, caption: 'It began to build.', draw: (ctx) => { ctx.scale(0.05, 0.05); rampScaffold(ctx, { topX: 0, topY: 8, kneeX: -22, kneeY: 0, baseX: -34, baseY: 0, tier: 1 }); } },
    { hold: 2.4, caption: 'Goal: 6,000 feet.', draw: (ctx, t) => penguinCapsule(ctx, { pose: 'glide', t }) },
  ];
}

export function ltf2Intro(save) {
  const s = save.ltf1;
  const recap = s.completed
    ? `You flew ${Math.round(s.bestDistFt).toLocaleString()} ft in ${s.day} days. Then the iceberg.`
    : 'Somewhere out there, an iceberg is still standing.';
  return [
    { hold: 2.6, caption: 'You remember the iceberg.', draw: (ctx) => { ctx.scale(0.006, 0.006); obstacleShape(ctx, 'iceberg', 180, 260, 0); } },
    { hold: 3.6, caption: recap, draw: (ctx, t) => penguinCapsule(ctx, { pose: 'dead', t }) },
    { hold: 3.0, caption: 'The penguin woke up angry, and with a budget.', draw: (ctx, t) => penguinCapsule(ctx, { pose: 'idle', t }) },
    { hold: 3.0, caption: 'This time it is not getting in the vehicle.', draw: (ctx, t) => penguinCapsule(ctx, { pose: 'idle', t, dummy: true }) },
  ];
}

// ── the machine ────────────────────────────────────────────────────────────────────────

export function createMachine(G) {
  const T = G.T;
  let cur = S.BOOT;
  let t = 0;
  const ctxData = {};

  function go(next) {
    const from = cur;
    if (STATES[from] && STATES[from].exit) STATES[from].exit();
    cur = next;
    t = 0;
    if (STATES[next] && STATES[next].enter) STATES[next].enter(from);
    G.bus.emit('state:changed', { from, to: next });
  }

  // ── helpers shared by several states ────────────────────────────────────────────────

  function beginRun() {
    G.fx.clear();
    G.hud.reset();
    ctxData.run = G.startRun(G.mode, G.save);
    ctxData.replayFrom = null;
    G.audio.rampRumble();
  }

  function settleRun() {
    const run = ctxData.run;
    const res = G.mode.settle(G.save, run.p);
    ctxData.settled = res;
    ctxData.results = createResults(res.pay, run.p, G.mode.slot(G.save).day, T);
    ctxData.lastInputs = run.recorded.slice();

    G.bus.emit('run:ended', {
      run: run.p, pay: res.pay,
      overTheWall: G.mode.overTheWall ? G.mode.overTheWall(G.save, run.p) : false,
    });
    if (res.challenges && res.challenges.allDone) {
      G.bus.emit('challenges:allDone', { mode: G.mode.mode });
    }
    G.persist();
    return res;
  }

  const STATES = {
    // ── BOOT ──
    [S.BOOT]: {
      step() { if (t > 0.05) go(S.LAUNCHER); },
      draw() { G.ctx.fillStyle = '#0d1117'; G.ctx.fillRect(0, 0, G.w, G.h); },
    },

    // ── LAUNCHER ──
    [S.LAUNCHER]: {
      enter() { G.audio.stopAllLoops(); },
      draw() {
        G.ui.launcher(G.ctx, G.w, G.h, {
          t, clouds: G.launcherClouds,
          ltf2Unlocked: G.save.account.seenLtf1Ending || G.save.ltf1.completed,
          medalCount: Object.keys(G.save.account.medals).length,
          audioSuspended: G.audio.suspended,
          warning: G.warning,
        });
      },
      click(hit) {
        if (!hit) return;
        G.audio.click();
        switch (hit.id) {
          case 'ltf1':
            G.setMode('ltf1');
            ctxData.intro = ltf1Intro();
            go(G.save.ltf1.day > 1 ? S.DAY_START : S.INTRO);
            break;
          case 'ltf2':
            if (!(G.save.account.seenLtf1Ending || G.save.ltf1.completed)) return;
            G.setMode('ltf2-story');
            ctxData.intro = ltf2Intro(G.save);
            go(G.save.ltf2.day > 1 ? S.DAY_START : S.INTRO);
            break;
          case 'sidebyside': go(S.SIDEBYSIDE); break;
          case 'sandbox': G.setMode('sandbox'); go(S.DAY_START); break;
          case 'medals': G.bus.emit('ui:medalIcon', {}); go(S.MEDALS); break;
          case 'reset': G.resetSave(); break;
          // §7.7 medal 20 — the whole joke is that it is exactly this silly
          case 'beak': G.bus.emit('ui:beakClick', {}); G.audio.medal(); break;
          default: break;
        }
      },
    },

    // ── INTRO ──
    [S.INTRO]: {
      enter() { ctxData.frame = 0; },
      step(dt) {
        const frames = ctxData.intro;
        if (t >= frames[ctxData.frame].hold) {
          t = 0;
          ctxData.frame++;
          if (ctxData.frame >= frames.length) go(S.DAY_START);
        }
      },
      draw() { G.ui.intro(G.ctx, G.w, G.h, ctxData.intro, ctxData.frame, t); },
      key() { go(S.DAY_START); },
      click() { go(S.DAY_START); },
    },

    // ── DAY_START ──
    [S.DAY_START]: {
      enter() { ctxData.beforeGear = ctxData.beforeGear || null; },
      step() { if (t >= T.DAY_CARD_TIME) go(S.PRE_LAUNCH); },
      draw() {
        drawWorldStill();
        G.ui.dayCard(G.ctx, G.w, G.h, G.mode.slot(G.save).day, t, T.DAY_CARD_TIME);
      },
      key() { go(S.PRE_LAUNCH); },
      click() { go(S.PRE_LAUNCH); },
    },

    // ── PRE_LAUNCH ──
    [S.PRE_LAUNCH]: {
      enter() { beginRun(); G.audio.unlock(); },
      step(dt) {
        // camera settles onto the ramp, penguin idles. Nothing is stepped: this beat is the
        // point (§5).
        G.camera.update(ctxData.run.p, dt, G.w, G.h, G.rand);
      },
      draw() {
        drawRun();
        G.ui.preLaunch(G.ctx, G.w, G.h, { t, newGear: ctxData.newGear || [] });
      },
      key(code) { if (code === 'Space' || code === 'Enter') go(S.LAUNCH); },
      click() { go(S.LAUNCH); },
    },

    // ── LAUNCH (the scripted ramp slide lives in the sim itself, §4.7) ──
    [S.LAUNCH]: {
      enter() { ctxData.newGear = []; },
      step(dt) {
        stepFlight(dt);
        if (ctxData.run.p.state !== SIM.RAMP) go(S.FLIGHT);
      },
      draw() { drawRun(); },
    },

    // ── FLIGHT — the sim runs here ──
    [S.FLIGHT]: {
      step(dt) {
        stepFlight(dt);
        if (ctxData.run.finished) {
          ctxData.run.stopAudio();
          const res = settleRun();
          go(res.victory ? S.VICTORY : S.RESULTS);
        }
      },
      draw() { drawRun(); },
      key(code) { if (code === 'KeyR') { ctxData.run.stopAudio(); go(S.PRE_LAUNCH); } },
    },

    // ── RESULTS — the dopamine (§2.8) ──
    [S.RESULTS]: {
      step(dt) {
        const ticks = ctxData.results.update(dt);
        for (let i = 0; i < Math.min(ticks, 3); i++) G.audio.tick(i);
      },
      draw() {
        drawRun();
        G.ui.results(G.ctx, G.w, G.h, ctxData.results);
        drawExtras();
      },
      key() { if (ctxData.results.done) go(S.SHOP); else ctxData.results.skip(); },
      click(hit) {
        if (hit && hit.id === 'toShop') { G.audio.click(); go(S.SHOP); }
        else ctxData.results.skip();
      },
    },

    // ── SHOP ──
    [S.SHOP]: {
      enter() { ctxData.beforeGear = { ...(G.mode.slot(G.save).upgrades || G.mode.slot(G.save).gear) }; G.persist(); },
      draw() { G.ui.shop(G.ctx, G.w, G.h, G.mode.shop(G.save)); },
      click(hit) {
        if (!hit) return;
        if (hit.id === 'nextDay') {
          G.audio.click();
          const before = G.mode.nextDay(G.save);
          ctxData.newGear = G.mode.newGear(G.save, ctxData.beforeGear);
          ctxData.beforeGear = before;
          G.persist();
          go(S.DAY_START);
        } else if (hit.id === 'quit') { G.audio.back(); go(S.LAUNCHER); }
        else if (hit.id === 'cash') { G.bus.emit('ui:moneyClick', {}); G.audio.tick(); }
        else if (hit.id === 'secret') { G.audio.click(); go(S.SECRET); }
        else if (hit.id.startsWith('buy:')) {
          const key = hit.id.slice(4);
          const res = G.mode.buy(G.save, key);
          if (res) {
            G.audio.purchase();
            G.bus.emit('shop:bought', { id: key, ...res });
            G.persist();
          } else G.audio.back();
        }
      },
    },

    // ── SECRET SHOP (§7.7) ──
    [S.SECRET]: {
      draw() {
        G.ui.secretShop(G.ctx, G.w, G.h, {
          t, bp: G.save.account.bp,
          items: G.secretItems(),
        });
      },
      click(hit) {
        if (!hit) return;
        if (hit.id === 'closeSecret') { G.audio.back(); go(S.SHOP); return; }
        if (hit.id.startsWith('secret:')) {
          const id = hit.id.slice(7);
          if (G.buySecret(id)) { G.audio.purchase(); G.persist(); } else G.audio.back();
        }
      },
    },

    // ── VICTORY -> ENDING ──
    [S.VICTORY]: {
      enter() { G.audio.win(); G.bus.emit('mode:completed', { mode: G.mode.mode || 'ltf1' }); G.persist(); },
      step() { if (t > 2.6) go(S.ENDING); },
      draw() {
        drawRun();
        G.ui.ending(G.ctx, G.w, G.h, { phase: 'cheer', t });
      },
      key() { go(S.ENDING); },
    },

    // ── ENDING — §6.5, and DO NOT SOFTEN IT ──
    [S.ENDING]: {
      enter() { ctxData.endPhase = G.mode.id === 'ltf1' ? 'berg' : 'card'; },
      step() {
        if (ctxData.endPhase === 'berg' && t > 1.9) {
          ctxData.endPhase = 'black';
          t = 0;
          G.audio.thud(1);
          G.camera.kick(1);
        } else if (ctxData.endPhase === 'black' && t > 1.1) {
          ctxData.endPhase = 'card';
          t = 0;
          G.save.account.seenLtf1Ending = true;
          G.persist();
        }
      },
      draw() {
        G.ui.ending(G.ctx, G.w, G.h, {
          phase: ctxData.endPhase, t,
          days: G.mode.slot(G.save).day,
          bestFt: G.save.ltf1.bestDistFt,
          earned: G.save.account.lifetime.cashEarned,
        });
      },
      click(hit) { if (hit && hit.id === 'post') { G.audio.click(); go(S.POST); } },
      key() { if (ctxData.endPhase === 'card' && t > 2.2) go(S.POST); },
    },

    // ── POST — §14 seam 1. Two lines today. A door tomorrow. ──
    [S.POST]: {
      enter() {
        G.save.account.seenLtf1Ending = true;
        G.persist();
      },
      step() { if (t > 0.2) go(S.LAUNCHER); },
      draw() { G.ctx.fillStyle = '#0d1117'; G.ctx.fillRect(0, 0, G.w, G.h); },
    },

    // ── MEDALS ──
    [S.MEDALS]: {
      enter() { ctxData.medalPage = 0; },
      draw() {
        G.ui.medals(G.ctx, G.w, G.h, {
          all: G.medals.all, unlockedIds: G.save.account.medals,
          unlocked: Object.keys(G.save.account.medals).length,
          bp: G.save.account.bp, page: ctxData.medalPage,
        });
      },
      click(hit) {
        if (!hit) return;
        G.audio.click();
        if (hit.id === 'closeMedals') go(S.LAUNCHER);
        if (hit.id === 'medalNext') ctxData.medalPage++;
        if (hit.id === 'medalPrev') ctxData.medalPage = Math.max(0, ctxData.medalPage - 1);
      },
      key(code) { if (code === 'Escape') go(S.LAUNCHER); },
    },

    // ── SIDE-BY-SIDE (§11) ──
    [S.SIDEBYSIDE]: {
      draw() { G.ui.reference(G.ctx, G.w, G.h, { ready: false }); },
      click(hit) { if (hit && hit.id === 'closeRef') { G.audio.back(); go(S.LAUNCHER); } },
      key(code) { if (code === 'Escape') go(S.LAUNCHER); },
    },
  };

  // ── shared drawing ─────────────────────────────────────────────────────────────────

  function stepFlight(dt) {
    const run = ctxData.run;
    const input = G.replaying ? G.replay.sample() : G.input.sample();
    if (G.replaying) G.replay.advance();
    run.step(dt, input);
    G.camera.update(run.p, dt, G.w, G.h, G.rand);
    G.fx.update(dt, G.camera.bounds(G.w, G.h));
    run.audioTick();
  }

  function drawRun() {
    const run = ctxData.run;
    if (!run) return;
    const dt = G.frameDt || 1 / 60;
    G.fx.snowTick(dt, G.camera.bounds(G.w, G.h), G.reducedMotion ? 0 : 1);
    G.renderer.draw(G.ctx, {
      w: G.w, h: G.h, dt,
      p: run.p, loadout: run.L, world: run.world, obstacles: run.obstacles,
      day: G.mode.slot(G.save).day,
      cash: G.mode.slot(G.save).cash,
      bestDistFt: G.save.ltf1.bestDistFt,
      goalFt: G.mode.goalFt,
      gliderTier: G.mode.gliderTier(G.save),
      rampTier: G.mode.rampTier(G.save),
      payloadBomb: G.mode.payloadBomb(G.save),
      dummy: G.mode.dummy,
      glideRatio: run.glideRatio,
      hideHud: G.hideHud, showDebug: G.showDebug, showPivot: G.showPivot,
      loopStats: G.loop.stats,
      dayNight: 'day',
    });
  }

  /** A still frame of the world under the day card, so it never cuts to black. */
  function drawWorldStill() {
    if (ctxData.run) { drawRun(); return; }
    G.ctx.fillStyle = '#bfe6ff';
    G.ctx.fillRect(0, 0, G.w, G.h);
  }

  function drawExtras() {
    const ex = ctxData.settled && ctxData.settled.extras;
    if (!ex || !ex.length) return;
    G.ctx.save();
    G.ctx.font = '700 16px system-ui, sans-serif';
    G.ctx.textAlign = 'center';
    ex.forEach((line, i) => {
      G.ctx.fillStyle = PALETTE.gold;
      G.ctx.fillText(line, G.w / 2, G.h - 60 + i * 22);
    });
    G.ctx.restore();
  }

  return {
    get current() { return cur; },
    get data() { return ctxData; },
    go,
    step(dt) { t += dt; const s = STATES[cur]; if (s && s.step) s.step(dt); },
    draw() { const s = STATES[cur]; if (s && s.draw) s.draw(); },
    click(hit) { const s = STATES[cur]; if (s && s.click) s.click(hit); },
    key(code) { const s = STATES[cur]; if (s && s.key) s.key(code); },
    inFlight() { return cur === S.FLIGHT || cur === S.LAUNCH; },
  };
}
