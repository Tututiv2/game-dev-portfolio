// LEARN TO FLY 2 — ARCADE. §7.2.
//
// Scored sub-challenges. Points accumulate across every arcade run forever, and
// `Arcade King` = 12,500 total points across all arcade modes (VERIFIED, medal 3).
//
// Three sub-modes, each scoring a different thing the flight model already produces, which is
// the whole reason arcade is cheap to build: no new physics, just a different question.

import { CATALOG, CATEGORY_ORDER, ATTRIBUTES, loadoutFrom } from '../../data/ltf2.catalog.js';
import { createWorld, oceanProvider } from '../../sim/world.js';
import { shopItems, buy, attrItems, buyAttr } from '../shop.js';
import { payout } from '../../sim/economy.js';

const RAMP_M = 11;

export const ARCADE_GAMES = [
  {
    id: 'skipper', name: 'Skipper', desc: 'Points per water skip, squared into a chain bonus.',
    score: (run) => Math.round(run.bounces * 60 + run.bounces * run.bounces * 12),
  },
  {
    id: 'altitude', name: 'Ceiling', desc: 'Points for the highest single point of the flight.',
    score: (run, T) => Math.round(Math.max(0, run.maxAlt) * T.FT_PER_M * 1.6),
  },
  {
    id: 'loiter', name: 'Loiter', desc: 'Points per second airborne. Distance is worth nothing.',
    score: (run) => Math.round(run.airTime * 42),
  },
];

export function createLtf2Arcade(T) {
  let game = ARCADE_GAMES[0];

  return {
    id: 'ltf2-arcade',
    title: 'Learn to Fly 2 — Arcade',
    hangar: true,
    dummy: false,
    goalFt: null,
    secretShop: true,
    mode: 'arcade',

    get game() { return game; },
    setGame(id) { game = ARCADE_GAMES.find((g) => g.id === id) || ARCADE_GAMES[0]; },

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
        goalFt: null,
        bestFt: 0,
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

    newGear() { return []; },

    settle(save, run) {
      const pay = payout(run, T);
      const points = game.score(run, T);
      save.ltf2.cash += pay.cash;
      save.ltf2.arcadeScore += points;
      return {
        pay, victory: false, points,
        extras: [`${game.name}  +${points.toLocaleString()} pts`],
      };
    },

    overTheWall() { return false; },
    nextDay(save) { save.ltf2.day += 1; return { ...save.ltf2.gear }; },
    reset(save) { save.ltf2.arcadeScore = 0; },
  };
}
