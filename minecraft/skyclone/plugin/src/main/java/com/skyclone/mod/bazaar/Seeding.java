package com.skyclone.mod.bazaar;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The seeding trick's maths (research 05 s2.9: "periodic NPC sell offers for progression-gating materials to keep
 * early game moving"). PURE so the money rules are unit tested; {@link Seeder} does the live work.
 *
 * <p>The Merchant Guild re-stocks one standing Sell Offer per seeded product every interval. Three rules keep it a
 * supply and never a coin printer:
 * <ol>
 *   <li>its price is never below one tick over the market maker's best bid, so "buy from the guild, sell to the bots"
 *       always loses (and the market maker's bid is in turn kept under the guild's ask, see MarketMaker.quote);</li>
 *   <li>never at or below the NPC sell price (buy from the guild, sell to the NPC);</li>
 *   <li>never above an NPC shop's price (nobody would buy it; the shop is the supply then) - such a product is
 *       skipped rather than sold below its floor.</li>
 * </ol>
 * The batch never ACCUMULATES: each drop replaces the guild's offer with a fresh one of exactly the batch size, so
 * a quiet week is one batch on the book, not a week of them.
 */
final class Seeding {

    private Seeding() {}

    /** Largest batch any drop may post (the genre's max order size). */
    static final int MAX_BATCH = 71_680;

    /**
     * The guild's unit price in tenths, or 0 when the product must not be seeded right now.
     *
     * @param nominalAsk   the market maker's un-skewed ask in coins (fair x (1 + spread/2)); 0 = unknown
     * @param mmBestBid    the market maker's best bid on the book in tenths (0 = none)
     * @param npcSell      NPC sell price in coins (0 = none)
     * @param shop         NPC shop price in coins (0 = none)
     * @param pricePercent the guild's price as a percent of the nominal ask (dial)
     */
    static long price(double nominalAsk, long mmBestBid, double npcSell, double shop, double pricePercent) {
        if (!(nominalAsk > 0) || !Double.isFinite(nominalAsk)) return 0;
        double pct = Double.isFinite(pricePercent) ? Math.max(1, Math.min(1_000, pricePercent)) : 100;
        long price = Pricing.tenthsUp(nominalAsk * pct / 100.0);
        long floor = 1;
        if (mmBestBid > 0) floor = Math.max(floor, mmBestBid + 1);
        if (npcSell > 0) floor = Math.max(floor, Pricing.tenthsUp(npcSell) + 1);
        price = Math.max(price, floor);
        if (shop > 0) {
            long ceil = Pricing.tenthsDown(shop);
            if (ceil < floor) return 0;
            price = Math.min(price, ceil);
        }
        return price;
    }

    /**
     * Units per drop: the generator's batch x {@code mult}, at least one, worth at most {@code maxValue} coins at
     * {@code unitCoins} each (0 = no value cap), never over {@link #MAX_BATCH}.
     */
    static int batch(int base, double mult, double unitCoins, double maxValue) {
        if (base <= 0 || !(mult > 0) || !Double.isFinite(mult)) return 0;
        double n = Math.round(base * mult);
        if (maxValue > 0 && unitCoins > 0) n = Math.min(n, Math.floor(maxValue / unitCoins));
        return (int) Math.max(1, Math.min(MAX_BATCH, n));
    }

    /**
     * The seeded list: the generator's ({@code data}, id -> batch) edited by the dial {@code spec}:
     * {@code ID:n} adds or resizes, {@code ID} adds at {@code defBatch} (keeps an existing size), {@code -ID} removes,
     * comma or space separated. Malformed entries are skipped (a dial typo never stops the guild).
     */
    static Map<String, Integer> merge(Map<String, Integer> data, String spec, int defBatch) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (data != null) for (Map.Entry<String, Integer> e : data.entrySet()) if (e.getValue() != null && e.getValue() > 0) out.put(e.getKey(), e.getValue());
        if (spec == null || Gate.key(spec).isEmpty()) return out;   // none / off / "" = no edits (a dial cannot be set empty)
        for (String raw : spec.split("[,\\s]+")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("-")) { out.remove(t.substring(1).trim().toUpperCase(Locale.ROOT)); continue; }
            int c = t.lastIndexOf(':');
            String id = (c > 0 ? t.substring(0, c) : t).trim().toUpperCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            if (c > 0) {
                int n;
                try { n = Integer.parseInt(t.substring(c + 1).trim()); } catch (NumberFormatException e) { continue; }
                if (n <= 0) out.remove(id); else out.put(id, Math.min(MAX_BATCH, n));
            } else {
                out.putIfAbsent(id, Math.max(1, defBatch));
            }
        }
        return out;
    }

    /** Millis until the next drop given the last one and the interval (minutes); 0 = due now. */
    static long untilNext(long lastDrop, long now, double intervalMinutes) {
        long iv = (long) (Math.max(1, intervalMinutes) * 60_000L);
        if (lastDrop <= 0) return 0;
        return Math.max(0, lastDrop + iv - now);
    }
}
