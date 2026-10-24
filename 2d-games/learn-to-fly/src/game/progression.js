// A COMPETENT PLAYER, PLAYED OUT DAY BY DAY.
//
// Test 13 is a claim about pacing — "the first glider is affordable by day 3 and 6,000 ft
// falls in 16-25 days" — and a claim about pacing is only falsifiable if there is a player to
// make it about. This module is that player. It is also what tools/calibrate.mjs sweeps
// against, so the price ladder is measured rather than felt.
//
// Headless and pure: no DOM, no rendering, no save. It may live in game/ because it is a
// policy over the sim, not part of it.

import { simulate, STATE } from '../sim/physics.js';
import { makeAutopilot, LINES } from '../sim/autopilot.js';
import { payout } from '../sim/economy.js';
import { createWorld, oceanProvider } from '../sim/world.js';
import { UPGRADES, UPGRADE_ORDER, loadoutFrom, freshLevels, nextTier } from '../data/ltf1.upgrades.js';

// Best-of over every line in sim/autopilot.js, because a competent player is one who has
// worked out which line their current kit wants — and the answer genuinely changes: with no
// rockets there is nothing to decide, and with a Prototype wing there is.

/** One day's flight, flown three ways; the player keeps the best. Deterministic. */
export function flyBest(levels, T, table = UPGRADES) {
  const L = loadoutFrom(levels, T, table);
  let best = null;
  for (const line of LINES) {
    const world = createWorld(oceanProvider(), T);
    const p = simulate(L, world, T, makeAutopilot(T, line), { collectEvents: false });
    const pay = payout(p, T);
    if (best === null || pay.cash > best.pay.cash) best = { line: line.name, p, pay };
  }
  return best;
}

/**
 * Greedy value shopping with the patience to save up.
 *
 * The community's verified advice is "acceleration first, then a glider as soon as
 * possible", and this reproduces that WITHOUT being told to: acceleration is the cheapest
 * thing on the board and a glider is the biggest single jump in dollars per day. If a
 * change to the price table ever makes that stop being true, this notices, which is the
 * whole reason it is a search and not a hardcoded shopping list.
 */
export function chooseBuy(levels, cash, T, table = UPGRADES, baseCash = null) {
  // The VERIFIED opening: "buy acceleration first, then a glider as soon as possible."
  // It is in here rather than left to the search because a competent player has read the
  // same guide the owner has, and because §6.2 is explicit that a glider by day 3 is a
  // property of the PRICE CURVE — testing it against a player who has to rediscover the
  // opening would be testing the search instead.
  for (const key of OPENING) {
    const nxt = nextTier(levels, key, table);
    if (nxt === null) continue;
    if (levels[key] > 0) continue;                 // opening covers the FIRST tier only
    return nxt.price <= cash ? { key, price: nxt.price, gain: null, value: null } : null;
  }

  const base = baseCash === null ? flyBest(levels, T, table).pay.cash : baseCash;
  const options = [];
  for (const key of UPGRADE_ORDER) {
    const nxt = nextTier(levels, key, table);
    if (nxt === null || nxt.price <= 0) continue;
    const trial = { ...levels, [key]: nxt.index };
    const gain = flyBest(trial, T, table).pay.cash - base;
    options.push({ key, price: nxt.price, gain, value: gain / nxt.price });
  }
  if (options.length === 0) return null;
  options.sort((a, b) => b.value - a.value);

  const affordable = options.filter((o) => o.price <= cash && o.gain > 0);
  const bestOverall = options[0];
  if (affordable.length === 0) return null;                       // save
  const bestAfford = affordable[0];
  // Hold out for something meaningfully better rather than dribbling the money away.
  if (bestOverall !== bestAfford && bestOverall.value > bestAfford.value * SAVE_RATIO) return null;
  return bestAfford;
}

const SAVE_RATIO = 1.6;                       // hold out for something this much better
const OPENING = ['acceleration', 'glider'];   // VERIFIED community opening — see chooseBuy

/**
 * @returns {{days, winDay, gliderDay, log:Array}} — `winDay` is null if the goal was not met
 *          inside `maxDays`, which is a failure of the price curve, not of the player (§17.15).
 */
export function playThrough(T, opts = {}) {
  const table = opts.table || UPGRADES;
  const maxDays = opts.maxDays || 40;
  const levels = freshLevels();
  let cash = 0, day = 0, best = 0;
  let winDay = null, gliderDay = null;
  const log = [];

  while (day < maxDays) {
    day++;
    const flight = flyBest(levels, T, table);
    cash += flight.pay.cash;
    if (flight.pay.distFt > best) best = flight.pay.distFt;

    const bought = [];
    for (;;) {
      const buy = chooseBuy(levels, cash, T, table, flight.pay.cash);
      if (buy === null) break;
      cash -= buy.price;
      levels[buy.key]++;
      bought.push(`${table[buy.key].name} ${levels[buy.key]}`);
      if (buy.key === 'glider' && gliderDay === null) gliderDay = day;
    }

    log.push({
      day, distFt: flight.pay.distFt, altFt: flight.pay.altFt, mult: flight.pay.mult,
      earned: flight.pay.cash, cash, bought, line: flight.line,
      levels: { ...levels }, airTime: flight.p.airTime, bounces: flight.p.bounces,
      state: flight.p.state === STATE.REST ? flight.p.endReason : flight.p.state,
    });

    if (winDay === null && best >= T.GOAL_FT) { winDay = day; if (!opts.keepPlaying) break; }
  }
  return { days: day, winDay, gliderDay, bestFt: best, log };
}
