// §14 seam 3 — THE SHOP RENDERS WHATEVER DATA IT IS GIVEN.
//
// Adding a seventh upgrade category, or a tier 6, must be a row in a data file and never a
// change to this file. Check 3 proves it by injecting a nonsense tier at runtime, rendering
// it, buying it and deleting it again.
//
// This file knows nothing about penguins. It turns {table, levels, cash} into view models and
// applies purchases. Both games use it; LTF2 additionally spends bonus points, which is the
// only branch in here.

/** @returns {Array} one view model per category, ready for ui.shop(). */
export function shopItems(table, order, levels, describe) {
  return order.map((key) => {
    const cat = table[key];
    const lvl = levels[key] | 0;
    const tiers = cat.tiers;
    const nxt = lvl >= tiers.length - 1 ? null : { index: lvl + 1, ...tiers[lvl + 1] };
    return {
      key,
      name: cat.name,
      blurb: cat.blurb || '',
      level: lvl,
      tiers: tiers.length,
      current: describe ? describe(key, tiers[lvl]) : (tiers[lvl].label || '—'),
      next: nxt === null ? null : {
        label: describe ? describe(key, nxt) : (nxt.label || '—'),
        price: nxt.price !== undefined ? nxt.price : 0,
        bp: nxt.bp,
        omega: !!nxt.omega,
        index: nxt.index,
      },
    };
  });
}

/**
 * Apply a purchase. Returns null if it cannot be made — this function is the single place
 * that decides affordability, so a UI bug can never grant a free tier.
 */
export function buy(table, levels, key, wallet) {
  const cat = table[key];
  const lvl = levels[key] | 0;
  if (lvl >= cat.tiers.length - 1) return null;
  const nxt = cat.tiers[lvl + 1];
  // Omega gear is bought with BONUS POINTS, not cash (VERIFIED, §7.4).
  if (nxt.omega || nxt.bp !== undefined) {
    if ((wallet.bp | 0) < nxt.bp) return null;
    wallet.bp -= nxt.bp;
    levels[key] = lvl + 1;
    return { key, index: lvl + 1, spent: nxt.bp, currency: 'bp', tier: nxt };
  }
  if (wallet.cash < nxt.price) return null;
  wallet.cash -= nxt.price;
  levels[key] = lvl + 1;
  return { key, index: lvl + 1, spent: nxt.price, currency: 'cash', tier: nxt };
}

/** Attribute upgrades (LTF2) — flat repeated steps rather than a tier table. */
export function attrItems(attrs, defs) {
  return Object.entries(defs).map(([key, d]) => {
    const lvl = attrs[key] | 0;
    const maxed = lvl >= d.steps;
    return {
      key: `attr:${key}`,
      name: d.name,
      blurb: d.blurb,
      level: lvl - 1,
      tiers: d.steps,
      current: `${lvl === 0 ? 'stock' : `${(lvl * d.per * 100).toFixed(0)}${d.unit}`}`,
      next: maxed ? null : { label: `${((lvl + 1) * d.per * 100).toFixed(0)}${d.unit}`, price: 0, bp: d.bp * (lvl + 1), omega: true, index: lvl + 1 },
    };
  });
}

export function buyAttr(attrs, defs, key, wallet) {
  const d = defs[key];
  const lvl = attrs[key] | 0;
  if (!d || lvl >= d.steps) return null;
  const cost = d.bp * (lvl + 1);
  if ((wallet.bp | 0) < cost) return null;
  wallet.bp -= cost;
  attrs[key] = lvl + 1;
  return { key, index: lvl + 1, spent: cost, currency: 'bp' };
}
