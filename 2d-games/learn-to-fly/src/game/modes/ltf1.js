// LEARN TO FLY (2009). §6.
//
// A mode is a small object the state machine drives; nothing in game/state.js knows which
// game it is running. That is what makes LTF2 content rather than a fork.

import { UPGRADES, UPGRADE_ORDER, loadoutFrom, freshLevels } from '../../data/ltf1.upgrades.js';
import { shopItems, buy } from '../shop.js';
import { createWorld, oceanProvider } from '../../sim/world.js';
import { payout } from '../../sim/economy.js';

export function createLtf1(T) {
  return {
    id: 'ltf1',
    title: 'Learn to Fly',
    hangar: false,
    dummy: false,
    goalFt: T.GOAL_FT,
    secretShop: false,

    slot(save) { return save.ltf1; },
    world() { return createWorld(oceanProvider(), T); },
    obstacles() { return null; },

    loadout(save) { return loadoutFrom(save.ltf1.upgrades, T); },
    gliderTier(save) { return save.ltf1.upgrades.glider | 0; },
    rampTier(save) { return save.ltf1.upgrades.rampHeight | 0; },
    payloadBomb() { return false; },

    shop(save) {
      return {
        hangar: false,
        day: save.ltf1.day,
        cash: save.ltf1.cash,
        goalFt: T.GOAL_FT,
        bestFt: save.ltf1.bestDistFt,
        secretShop: false,
        items: shopItems(UPGRADES, UPGRADE_ORDER, save.ltf1.upgrades, (key, tier) => tier.label),
      };
    },

    buy(save, key) {
      const wallet = { cash: save.ltf1.cash, bp: save.account.bp };
      const res = buy(UPGRADES, save.ltf1.upgrades, key, wallet);
      if (!res) return null;
      save.ltf1.cash = wallet.cash;
      return res;
    },

    /** New gear bought since the last launch, for the PRE_LAUNCH beat (§5). */
    newGear(save, before) {
      const out = [];
      for (const k of UPGRADE_ORDER) {
        if ((save.ltf1.upgrades[k] | 0) > (before[k] | 0)) {
          out.push(`${UPGRADES[k].name}: ${UPGRADES[k].tiers[save.ltf1.upgrades[k]].label}`);
        }
      }
      return out;
    },

    settle(save, run) {
      const pay = payout(run, T);
      const s = save.ltf1;
      s.cash += pay.cash;
      s.bestDistFt = Math.max(s.bestDistFt, pay.distFt);
      s.bestAltFt = Math.max(s.bestAltFt, pay.altFt);
      const victory = !s.completed && pay.distFt >= T.GOAL_FT;
      if (victory) s.completed = true;
      return { pay, victory, extras: [] };
    },

    nextDay(save) { save.ltf1.day += 1; return { ...save.ltf1.upgrades }; },
    reset(save) {
      save.ltf1 = { day: 1, cash: 0, upgrades: freshLevels(), bestDistFt: 0, bestAltFt: 0, completed: false };
    },
  };
}
