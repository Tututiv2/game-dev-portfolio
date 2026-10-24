package com.skyclone.mod.shops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every number the shops compute, with no Bukkit in it so the unit tests run the real code.
 *
 * <p>Research 05 §4: buy limit 640 per item per merchant per day (x10 under the "Shopping Spree" mayor perk),
 * sell cap 500,000,000 coins per player per day, reset at 00:00 UTC, buyback = the last 10 sold items for 15
 * minutes at the price they sold for. Accessories: Shady Ring 1%, Crooked Artifact 2%, Seal of the Family 3%
 * off most shops (highest one counts).
 */
public final class Pricing {

    private Pricing() {}

    public static final long DAY_MS = 86_400_000L;

    /** UTC day number; the daily counters reset when it changes (research: 00:00 UTC). */
    public static long utcDay(long epochMillis) { return Math.floorDiv(epochMillis, DAY_MS); }

    /** Milliseconds until the next 00:00 UTC. */
    public static long msToReset(long epochMillis) { return DAY_MS - Math.floorMod(epochMillis, DAY_MS); }

    /** Round UP to whole tenths (the economy stores tenths and rounds debits up anyway). */
    public static double ceilTenth(double v) {
        if (v <= 0) return 0;
        return Math.ceil(v * 10.0 - 1e-9) / 10.0;
    }

    /**
     * Coins for ONE bundle of an entry (the entry sells {@code bundleAmount} items for {@code listedCoins}).
     * A discount (percent) applies first; then the price is floored at what the merchants would pay back for
     * the same items ({@code npcSellEach x bundleAmount x markup}) so buying and re-selling can never print
     * coins (materials hand-off request 5). {@code markup} below 1 is treated as 1.
     */
    public static double bundleCoins(double listedCoins, int bundleAmount, double npcSellEach, double discountPct, double markup) {
        if (listedCoins <= 0) return 0;
        double pct = Math.max(0, Math.min(100, discountPct));
        double discounted = listedCoins * (1.0 - pct / 100.0);
        double floor = npcSellEach > 0 ? npcSellEach * Math.max(1, bundleAmount) * Math.max(1.0, markup) : 0;
        return ceilTenth(Math.max(discounted, floor));
    }

    /** Reference Emissary key discount (SkyHanni misc/ItemDiscounts.json): reputation threshold -> percent off coins. */
    public static final String DEFAULT_EMISSARY = "1000:5/3000:10/7000:15/12000:20";

    /** "1000:5/3000:10" -> sorted threshold -> percent; malformed parts are skipped, null/blank = empty. */
    public static java.util.TreeMap<Integer, Integer> parseEmissary(String text) {
        java.util.TreeMap<Integer, Integer> out = new java.util.TreeMap<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split("[/,]")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) continue;
            try {
                int at = Integer.parseInt(kv[0].trim()), pct = Integer.parseInt(kv[1].trim());
                if (at >= 0 && pct > 0) out.put(at, Math.min(100, pct));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /**
     * Percent off at the highest threshold the player reaches. {@code reaches} answers "has at least N reputation"
     * (a requirement check), so this needs no access to the faction module's data.
     */
    public static int emissaryPercent(java.util.TreeMap<Integer, Integer> table, java.util.function.IntPredicate reaches) {
        if (table == null || reaches == null) return 0;
        for (Integer at : table.descendingKeySet()) if (reaches.test(at)) return table.get(at);
        return 0;
    }

    /** {@code coins} less {@code pct} percent (the Emissary discount, before the accessory/attribute discount). */
    public static double emissaryCoins(double coins, int pct) {
        if (coins <= 0) return 0;
        return coins * (100 - Math.max(0, Math.min(100, pct))) / 100.0;
    }

    /** True when the listed (undiscounted) price is below the re-sell value: the floor will lift it. */
    public static boolean clamped(double listedCoins, int bundleAmount, double npcSellEach) {
        return listedCoins > 0 && npcSellEach > 0 && listedCoins < npcSellEach * Math.max(1, bundleAmount);
    }

    /** The day's buy limit per item per merchant. */
    public static int dailyLimit(int base, boolean spree, int spreeMultiplier) {
        long v = (long) Math.max(0, base) * (spree ? Math.max(1, spreeMultiplier) : 1);
        return (int) Math.min(Integer.MAX_VALUE, v);
    }

    /** Highest discount among the owned accessories (they do not stack). */
    public static double bestDiscount(boolean shady, boolean crooked, boolean seal, double shadyPct, double crookedPct, double sealPct) {
        double d = 0;
        if (shady) d = Math.max(d, shadyPct);
        if (crooked) d = Math.max(d, crookedPct);
        if (seal) d = Math.max(d, sealPct);
        return d;
    }

    /**
     * The whole discount at one merchant: the best accessory (they do not stack with each other) plus that
     * merchant's own Hunting attribute (Builder Discount / Bartender Discount, wave 5). The two are different
     * sources and add (TUNE: the reference does not say; it never matters past a few percent: 3% + 10% at most).
     * Clamped to 0..100; the anti-arbitrage floor still applies after it.
     */
    public static double combinedDiscount(double accessoryPct, double attributePct) {
        double a = Double.isFinite(accessoryPct) ? Math.max(0, accessoryPct) : 0;
        double b = Double.isFinite(attributePct) ? Math.max(0, attributePct) : 0;
        return Math.min(100, a + b);
    }

    /**
     * How many bundles may be bought right now: limited by the daily allowance (in ITEMS), by what was asked
     * and by the per-click cap. Never negative.
     */
    public static int allowedBundles(int askedBundles, int bundleAmount, int boughtToday, int dailyLimit) {
        int left = Math.max(0, dailyLimit - Math.max(0, boughtToday));
        int byLimit = left / Math.max(1, bundleAmount);
        return Math.max(0, Math.min(askedBundles, byLimit));
    }

    // ---- per-player persistent counters (member data "shops") ---------------------------------------

    /** Gson-friendly member data. Fields are only ever ADDED (CORE_GUIDE trap 3). */
    public static final class Ledger {
        /** UTC day the counters below belong to. */
        public long day = -1;
        /** "shopId|ITEM_ID" -> items bought today. */
        public Map<String, Integer> bought = new HashMap<>();
        /** Coins received from selling today. */
        public double sold;
        /** Shops whose greeting this player has heard. */
        public Set<String> met = new HashSet<>();
        public long lifetimeItemsBought;
        public double lifetimeCoinsSpent;
        public double lifetimeCoinsSold;

        /** Reset the daily counters if the UTC day changed. */
        public void roll(long today) {
            if (day != today) {
                day = today;
                bought.clear();
                sold = 0;
            }
        }

        public int boughtToday(String shop, String item) { return bought.getOrDefault(shop + "|" + item, 0); }

        public void addBought(String shop, String item, int n) {
            bought.merge(shop + "|" + item, n, Integer::sum);
            lifetimeItemsBought += n;
        }
    }

    // ---- buyback ----------------------------------------------------------------------------------------

    /** Newest-first list of recent sales a player may undo at the price they sold for. Generic for tests. */
    public static final class Buyback<T> {

        public static final class Sale<T> {
            public final T item;
            public final String itemId;
            public final int amount;
            public final double price;
            public final long at;
            public final String shop;
            Sale(T item, String itemId, int amount, double price, long at, String shop) {
                this.item = item; this.itemId = itemId; this.amount = amount; this.price = price; this.at = at; this.shop = shop;
            }
        }

        private final Deque<Sale<T>> sales = new ArrayDeque<>();

        public void push(T item, String itemId, int amount, double price, long now, String shop, int capacity) {
            sales.addFirst(new Sale<>(item, itemId, amount, price, now, shop));
            while (sales.size() > Math.max(1, capacity)) sales.removeLast();
        }

        /** Drop sales older than {@code maxAgeMs}. */
        public void expire(long now, long maxAgeMs) {
            sales.removeIf(s -> now - s.at > maxAgeMs);
        }

        public List<Sale<T>> list(long now, long maxAgeMs) {
            expire(now, maxAgeMs);
            return new ArrayList<>(sales);
        }

        /** Take one sale out (by index in {@link #list} order), or null. */
        public Sale<T> take(int index, long now, long maxAgeMs) {
            expire(now, maxAgeMs);
            if (index < 0 || index >= sales.size()) return null;
            Iterator<Sale<T>> it = sales.iterator();
            for (int i = 0; it.hasNext(); i++) {
                Sale<T> s = it.next();
                if (i == index) { it.remove(); return s; }
            }
            return null;
        }

        /** Put a sale back at the front (an undo that could not be paid for). */
        public void restore(Sale<T> s, int capacity) {
            sales.addFirst(s);
            while (sales.size() > Math.max(1, capacity)) sales.removeLast();
        }

        public int size() { return sales.size(); }
        public boolean isEmpty() { return sales.isEmpty(); }
        public void clear() { sales.clear(); }
    }
}
