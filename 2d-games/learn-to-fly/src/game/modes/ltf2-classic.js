// LEARN TO FLY 2 — CLASSIC. §7.2.
//
// LTF1's distance chase with the better toy box. Ocean, no obstacles, a player-set target.
// It shares the LTF2 catalogue and the LTF2 save slot, so gear bought here is gear you have.

import { CATALOG, CATEGORY_ORDER, ATTRIBUTES, loadoutFrom } from '../../data/ltf2.catalog.js';
import { createWorld, oceanProvider } from '../../sim/world.js';
import { shopItems, buy, attrItems, buyAttr } from '../shop.js';
import { payout } from '../../sim/economy.js';
import { recordRun, settle as settleChallenges, freshChallengeStats } from '../challenges.js';

const RAMP_M = 11;
export const CLASSIC_TARGETS_FT = [2000, 6000, 15000, 40000];

export function createLtf2Classic(T) {
  return {
    id: 'ltf2-classic',
    title: 'Learn to Fly 2 — Classic',
    hangar: true,
    dummy: false,
    secretShop: true,
    mode: 'classic',

    get goalFt() { return this._goal || CLASSIC_TARGETS_FT[1]; },
    setTarget(ft) { this._goal = ft; },

    slot(save) { return save.ltf2; },
    world() { return createWorld(oceanProvider(), T); },
    obstacles() { return null; },

    loadout(save) { return loadoutFrom(save.ltf2.gear, save.ltf2.attrs, save.ltf2.secrets, T, RAMP_M); },
    gliderTier(save) { return save.ltf2.secrets.brickEquipped ? 0 : (save.ltf2.gear.glider | 0); },
    rampTier() { return 2; },
    payloadBomb() { return false; },

    shop(save) {
      return {
        hangar: true,
        day: save.ltf2.day,
        cash: save.ltf2.cash,
        bp: save.account.bp,
        goalFt: this.goalFt,
        bestFt: save.ltf2.challenges ? save.ltf2.challenges.bestDistFt : 0,
        secretShop: true,
        items: [
          ...shopItems(CATALOG, CATEGORY_ORDER, save.ltf2.gear, (k, tier) => tier.label),
          ...attrItems(save.ltf2.attrs, ATTRIBUTES),
        ],
      };
    },

    buy(save, key) {
      const wallet = { cash: save.ltf2.cash, bp: save.account.bp };
      const res = key.startsWith('attr:')
        ? buyAttr(save.ltf2.attrs, ATTRIBUTES, key.slice(5), wallet)
        : buy(CATALOG, save.ltf2.gear, key, wallet);
      if (!res) return null;
      save.ltf2.cash = wallet.cash;
      save.account.bp = wallet.bp;
      return res;
    },

    newGear(save, before) {
      const out = [];
      for (const k of CATEGORY_ORDER) {
        if ((save.ltf2.gear[k] | 0) > (before[k] | 0)) out.push(`${CATALOG[k].name}: ${CATALOG[k].tiers[save.ltf2.gear[k]].label}`);
      }
      return out;
    },

    settle(save, run) {
      const pay = payout(run, T);
      const s = save.ltf2;
      s.cash += pay.cash;
      s.challenges = s.challenges && s.challenges.done ? s.challenges : freshChallengeStats();
      recordRun(s.challenges, run, pay, T);
      const ch = settleChallenges(s.challenges, 'classic');
      s.cash += ch.cash;
      const victory = !s.completed && pay.distFt >= this.goalFt;
      if (victory) s.completed = true;
      return { pay, victory, extras: ch.newly.map((c) => `${c.name}  +$${c.cash}`), challenges: ch };
    },

    overTheWall() { return false; },
    nextDay(save) { save.ltf2.day += 1; return { ...save.ltf2.gear }; },
    reset(save) { save.ltf2.completed = false; },
  };
}
