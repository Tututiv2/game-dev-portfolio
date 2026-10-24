// LEARN TO FLY 2 — STORY. §7.1-7.3.
//
// A revenge story: five things to destroy, in order, ending with the wall you have been able
// to see since day one. Two facts do most of the work here:
//
//   OBSTACLE DAMAGE PERSISTS BETWEEN DAYS. Chipping the iceberg down over four runs IS the
//   arc, so hp lives in the save and the world is built around live objects.
//
//   THE PENGUIN IS REPLACED BY A DUMMY (VERIFIED). The payload destroys whatever carries it,
//   and the joke is that the penguin has learned enough not to be in the vehicle.

import { CATALOG, CATEGORY_ORDER, ATTRIBUTES, loadoutFrom, freshGear, freshAttrs } from '../../data/ltf2.catalog.js';
import { OBSTACLES, FIELD_END_FT } from '../../data/ltf2.obstacles.js';
import { makeObstacles, obstacleHp } from '../../sim/obstacles.js';
import { createWorld, fieldProvider } from '../../sim/world.js';
import { shopItems, buy, attrItems, buyAttr } from '../shop.js';
import { payout } from '../../sim/economy.js';
import { recordRun, settle as settleChallenges, freshChallengeStats } from '../challenges.js';

const RAMP_M = 11;

export function createLtf2Story(T) {
  let live = null;

  function obstaclesFor(save) {
    if (!live) live = makeObstacles(OBSTACLES, save.ltf2.obstacleHp, T);
    return live;
  }

  return {
    id: 'ltf2-story',
    title: 'Learn to Fly 2 — Story',
    hangar: true,
    dummy: true,
    goalFt: null,
    secretShop: true,
    mode: 'story',

    slot(save) { return save.ltf2; },
    world(save) {
      return createWorld(fieldProvider(obstaclesFor(save), FIELD_END_FT / T.FT_PER_M), T);
    },
    obstacles(save) { return obstaclesFor(save); },

    loadout(save) {
      return loadoutFrom(save.ltf2.gear, save.ltf2.attrs, save.ltf2.secrets, T, RAMP_M);
    },
    gliderTier(save) { return save.ltf2.secrets.brickEquipped ? 0 : (save.ltf2.gear.glider | 0); },
    rampTier() { return 2; },
    payloadBomb(save) { return !!CATALOG.payload.tiers[save.ltf2.gear.payload | 0].bomb; },

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

    newGear(save, before) {
      const out = [];
      for (const k of CATEGORY_ORDER) {
        if ((save.ltf2.gear[k] | 0) > (before[k] | 0)) {
          out.push(`${CATALOG[k].name}: ${CATALOG[k].tiers[save.ltf2.gear[k]].label}`);
        }
      }
      return out;
    },

    settle(save, run) {
      const pay = payout(run, T);
      const s = save.ltf2;
      s.cash += pay.cash;
      s.obstacleHp = obstacleHp(obstaclesFor(save));
      s.challenges = s.challenges && s.challenges.done ? s.challenges : freshChallengeStats();
      recordRun(s.challenges, run, pay, T);
      const ch = settleChallenges(s.challenges, 'story');
      s.cash += ch.cash;

      const obs = obstaclesFor(save);
      const wall = obs.find((o) => o.id === 'wall');
      const victory = !s.completed && wall && wall.destroyed;
      if (victory) s.completed = true;
      return { pay, victory, extras: ch.newly.map((c) => `${c.name}  +$${c.cash}`), challenges: ch };
    },

    /** I.C.B.P. — did the run clear the top of the 600 ft wall? (medal 40) */
    overTheWall(save, run) {
      const wall = obstaclesFor(save).find((o) => o.id === 'wall');
      return !!wall && run.dist >= wall.x1 && run.maxAlt > wall.y1;
    },

    nextDay(save) { save.ltf2.day += 1; return { ...save.ltf2.gear }; },
    reset(save) {
      live = null;
      save.ltf2 = {
        mode: 'story', day: 1, cash: 0, gear: freshGear(), attrs: freshAttrs(),
        obstacleHp: {}, challenges: freshChallengeStats(), secrets: {}, arcadeScore: 0, completed: false,
      };
    },
    releaseObstacles() { live = null; },
  };
}
