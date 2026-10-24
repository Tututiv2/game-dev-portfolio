// §7.6 — medals as PURE LISTENERS OVER THE EVENT BUS.
//
// Nothing in sim/ imports this file. The sim pushes plain data onto `p.events`, run.js
// republishes it on the bus, and this subscribes. Wire it the other way and physics gains a
// dependency on the achievement system and the headless tests stop running.
//
// Everything cumulative lands in `account.lifetime`, which exists in the save from version 1
// (landmine 12) because Traveler, Duration, Destruction, Diving and Reverse are all totals
// across every run forever, and retrofitting that means every player's counters start at zero.

import { MEDALS } from '../data/medals.data.js';

export function createMedals(bus, T) {
  let account = null;
  let onUnlock = null;
  const pending = [];

  function ensure(a) {
    a.flags = a.flags || {};
    a.medals = a.medals || {};
    return a;
  }

  /** Re-evaluate every predicate. Cheap (48 closures) and impossible to get out of sync. */
  function evaluate() {
    if (!account) return;
    for (const m of MEDALS) {
      if (account.medals[m.id]) continue;
      let ok = false;
      try { ok = m.test(account); } catch { ok = false; }
      if (!ok) continue;
      account.medals[m.id] = new Date().toISOString();
      account.bp += m.bp;
      account.lifetime.bpEarned += m.bp;
      pending.push({ id: m.id, name: m.name, bp: m.bp });
      if (onUnlock) onUnlock(m);
      bus.emit('medal:unlocked', m);
    }
  }

  const L = () => account.lifetime;

  // ── subscriptions ────────────────────────────────────────────────────────────────────
  // One line per thing the game can do. Adding a medal never means touching the sim.

  bus.on('run:ended', (e) => {
    if (!account) return;
    const l = L();
    l.distFt += Math.max(0, e.run.dist) * T.FT_PER_M;
    l.airTimeS += e.run.airTime;
    l.bounces += e.run.bounces;
    l.diveFt += e.run.diveM * T.FT_PER_M;
    l.reverseFt += e.run.reverseM * T.FT_PER_M;
    l.cashEarned += e.pay.cash;
    l.days += 1;
    l.maxAltFt = Math.max(l.maxAltFt, Math.max(0, e.run.maxAlt) * T.FT_PER_M);
    l.maxSpeedMph = Math.max(l.maxSpeedMph, e.run.maxSpeed * T.MPH_PER_MS);

    // Pacifist is CONSECUTIVE days, so a single hit resets it rather than decrementing.
    if (e.run.hits === 0 && e.run.destroyed.length === 0) l.pacifistDays += 1;
    else l.pacifistDays = 0;

    if (e.run.destroyed.length >= 5) account.flags.comboRun = true;
    if (e.overTheWall) account.flags.overTheWall = true;
    evaluate();
  });

  bus.on('water:skip', () => { /* counted in run stats; nothing per-bounce to do */ });

  bus.on('obstacle:destroyed', (e) => {
    if (!account) return;
    L().destroyed += 1;
    L().destroyedKinds = L().destroyedKinds || {};
    L().destroyedKinds[e.kind] = (L().destroyedKinds[e.kind] || 0) + 1;
    if (account.flags.usingBrick) account.flags.brickKill = true;
    evaluate();
  });

  bus.on('crash:skull', () => { if (account) { account.flags.skullCrush = true; evaluate(); } });
  bus.on('crash:planet', () => { if (account) { account.flags.planetCrush = true; evaluate(); } });
  bus.on('rob:refuel', () => { if (account) { account.flags.robRefuel = true; evaluate(); } });
  bus.on('phoenix:rise', () => { if (account) { account.flags.phoenixRise = true; evaluate(); } });

  bus.on('ui:moneyClick', () => {
    if (!account) return;
    account.flags.moneyClicks = (account.flags.moneyClicks || 0) + 1;
    evaluate();
  });
  bus.on('ui:beakClick', () => { if (account) { account.flags.beakClick = true; evaluate(); } });
  bus.on('ui:medalIcon', () => { if (account) { account.flags.medalIconClick = true; evaluate(); } });

  bus.on('shop:bought', (e) => {
    if (!account) return;
    if (e.id === 'brick') account.flags.boughtBrick = true;
    evaluate();
  });

  bus.on('arcade:score', (e) => { if (account) { L().arcadePoints += e.points; evaluate(); } });
  bus.on('mode:completed', (e) => {
    if (!account) return;
    if (e.mode === 'story') account.flags.storyComplete = true;
    if (e.mode === 'classic') account.flags.classicComplete = true;
    evaluate();
  });
  bus.on('challenges:allDone', (e) => {
    if (!account) return;
    if (e.mode === 'story') account.flags.allChallengesStory = true;
    if (e.mode === 'classic') account.flags.allChallengesClassic = true;
    evaluate();
  });

  return {
    attach(acc, cb) { account = ensure(acc); onUnlock = cb; evaluate(); },
    detach() { account = null; onUnlock = null; },
    /** Drain newly-unlocked medals for the toast queue. */
    takePending() { return pending.splice(0, pending.length); },
    evaluate,
    get all() { return MEDALS; },
    get unlockedCount() { return account ? Object.keys(account.medals).length : 0; },
  };
}
