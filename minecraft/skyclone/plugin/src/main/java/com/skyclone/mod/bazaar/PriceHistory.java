package com.skyclone.mod.bazaar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hourly price bars for one product: trades (volume-weighted) plus a quoted mid when nothing traded, so even a
 * quiet product has a chart and a 7-day average. PURE. Keeps {@link #KEEP_HOURS} hours.
 *
 * <p>The 7-day average is the research's price-protection reference (s2.3): instant buys above 2x it and instant
 * sells under 50% of it ask for confirmation, and sell offers may not be listed above 10x it.
 */
public final class PriceHistory {

    public static final int KEEP_HOURS = 24 * 7;

    public static final class Bar {
        public long hour;          // epoch hour
        public long open, high, low, close;   // trade prices, tenths (0 = no trade this hour)
        public long volume;        // units traded
        public long notional;      // sum of price*qty, tenths
        public long midSum;        // sum of quoted mids
        public int midCount;
        public boolean dirty;

        public long vwap() { return volume > 0 ? notional / volume : 0; }
        public long mid() { return midCount > 0 ? midSum / midCount : 0; }
        /** The bar's representative price: VWAP if anything traded, else the average quoted mid. */
        public long price() { long v = vwap(); return v > 0 ? v : mid(); }
    }

    private final TreeMap<Long, Bar> bars = new TreeMap<>();

    public static long hourOf(long millis) { return millis / 3_600_000L; }

    private Bar bar(long hour) {
        Bar b = bars.computeIfAbsent(hour, h -> { Bar n = new Bar(); n.hour = h; return n; });
        while (bars.size() > KEEP_HOURS + 2) bars.pollFirstEntry();
        return b;
    }

    public void trade(long priceTenths, long qty, long nowMillis) {
        if (priceTenths <= 0 || qty <= 0) return;
        Bar b = bar(hourOf(nowMillis));
        if (b.volume == 0) { b.open = priceTenths; b.high = priceTenths; b.low = priceTenths; }
        b.high = Math.max(b.high, priceTenths);
        b.low = b.low == 0 ? priceTenths : Math.min(b.low, priceTenths);
        b.close = priceTenths;
        b.volume += qty;
        b.notional += Pricing.total(priceTenths, qty);
        b.dirty = true;
    }

    public void quoteMid(long midTenths, long nowMillis) {
        if (midTenths <= 0) return;
        Bar b = bar(hourOf(nowMillis));
        b.midSum += midTenths;
        b.midCount++;
        b.dirty = true;
    }

    public void load(Bar b) { bars.put(b.hour, b); }

    public Map<Long, Bar> bars() { return bars; }

    /** Volume-weighted over trades in the window; falls back to the average bar price; 0 when empty. */
    public long average(long nowMillis, int hours) {
        long from = hourOf(nowMillis) - hours + 1;
        long vol = 0, not = 0, psum = 0;
        int pc = 0;
        for (Bar b : bars.tailMap(from, true).values()) {
            vol += b.volume;
            not += b.notional;
            long p = b.price();
            if (p > 0) { psum += p; pc++; }
        }
        if (vol > 0) return not / vol;
        return pc > 0 ? psum / pc : 0;
    }

    public long avg7d(long nowMillis) { return average(nowMillis, KEEP_HOURS); }

    public long volume(long nowMillis, int hours) {
        long from = hourOf(nowMillis) - hours + 1;
        long v = 0;
        for (Bar b : bars.tailMap(from, true).values()) v += b.volume;
        return v;
    }

    /** Representative price of each of the last {@code n} hours, oldest first (0 = no data that hour). */
    public List<Long> series(long nowMillis, int n) {
        long h = hourOf(nowMillis);
        List<Long> out = new ArrayList<>(n);
        for (long i = h - n + 1; i <= h; i++) {
            Bar b = bars.get(i);
            out.add(b == null ? 0L : b.price());
        }
        return out;
    }

    /** Change (fraction) between the price ~24h ago and now; NaN when either end is unknown. */
    public double change24h(long nowMillis) {
        List<Long> s = series(nowMillis, 25);
        long first = 0, last = 0;
        for (Long v : s) if (v > 0) { first = v; break; }
        for (int i = s.size() - 1; i >= 0; i--) if (s.get(i) > 0) { last = s.get(i); break; }
        if (first <= 0 || last <= 0) return Double.NaN;
        return (double) (last - first) / first;
    }

    public List<Bar> dirtyBars() {
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars.values()) if (b.dirty) out.add(b);
        return out;
    }
}
