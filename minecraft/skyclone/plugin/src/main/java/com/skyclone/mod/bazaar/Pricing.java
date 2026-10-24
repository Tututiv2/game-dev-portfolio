package com.skyclone.mod.bazaar;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Money maths for the Bazaar, in long TENTHS of a coin. PURE (no Bukkit).
 *
 * <p>Research 05 s2.3: prices tick in 0.1 coins; tax is charged on the SELLER's proceeds (instant sell and filled
 * sell offers), 1.25% by default. Tax rounds UP (in the house's favour, never a fraction of a tenth given away), so
 * proceeds = gross - ceil(gross * rate).
 */
public final class Pricing {

    private Pricing() {}

    public static final long MAX_TENTHS = 10_000_000_000_000L; // 1e12 coins: far beyond any real order

    public static long tenths(double coins) {
        if (!Double.isFinite(coins) || coins <= 0) return 0;
        return Math.round(coins * 10.0);
    }

    /** Round a coin price UP to the next tenth (an ask, or a floor that must hold). */
    public static long tenthsUp(double coins) {
        if (!Double.isFinite(coins) || coins <= 0) return 0;
        return (long) Math.ceil(coins * 10.0 - 1e-9);
    }

    /** Round a coin price DOWN to the tenth below (a bid, or a ceiling that must hold). */
    public static long tenthsDown(double coins) {
        if (!Double.isFinite(coins) || coins <= 0) return 0;
        return (long) Math.floor(coins * 10.0 + 1e-9);
    }

    public static double coins(long tenths) { return tenths / 10.0; }

    /** Overflow-safe price x quantity (tenths). */
    public static long total(long priceTenths, long qty) {
        if (priceTenths <= 0 || qty <= 0) return 0;
        if (priceTenths > MAX_TENTHS / Math.max(1, qty)) return MAX_TENTHS;
        return priceTenths * qty;
    }

    /** Tax in tenths on a gross amount, rounded up. {@code ratePercent} = 1.25 for 1.25%. */
    public static long tax(long grossTenths, double ratePercent) {
        if (grossTenths <= 0 || ratePercent <= 0) return 0;
        double t = grossTenths * (ratePercent / 100.0);
        long r = (long) Math.ceil(t - 1e-9);
        return Math.min(grossTenths, Math.max(0, r));
    }

    public static long afterTax(long grossTenths, double ratePercent) { return grossTenths - tax(grossTenths, ratePercent); }

    /**
     * How much better than the player's own limit an order that crossed the book did, in tenths (never negative).
     * A BUY at {@code limit} that cost {@code actual} for {@code qty} saved {@code limit*qty - actual}; a SELL that
     * grossed {@code actual} earned {@code actual - limit*qty} extra. Zero means it filled AT the player's price, which
     * must not be announced as "a better price" (playtest 2.2: a same-price cross said it was).
     */
    public static long improvement(boolean buy, long limit, long qty, long actual) {
        long atLimit = total(limit, qty);
        return Math.max(0, buy ? atLimit - actual : actual - atLimit);
    }

    private static final ThreadLocal<DecimalFormat> ONE =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0", DecimalFormatSymbols.getInstance(Locale.US)));
    private static final ThreadLocal<DecimalFormat> INT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)));

    /** "1,234.5" (always one decimal, as the genre shows unit prices). */
    public static String fmt(long tenths) { return ONE.get().format(tenths / 10.0); }

    /** Totals: "1,234,567" when whole, "1,234,567.5" otherwise. */
    public static String fmtTotal(long tenths) {
        if (tenths % 10 == 0) return INT.get().format(tenths / 10);
        return ONE.get().format(tenths / 10.0);
    }

    /** Short form for tight lore: 1.2k, 3.4M. */
    public static String shortc(long tenths) {
        double v = tenths / 10.0;
        double a = Math.abs(v);
        String[] u = {"", "k", "M", "B", "T"};
        int i = 0;
        while (a >= 1000 && i < u.length - 1) { a /= 1000; i++; }
        if (i == 0) return fmt(tenths);
        String n = a >= 100 ? String.valueOf((long) Math.floor(a)) : new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US)).format(Math.floor(a * 10) / 10);
        return (v < 0 ? "-" : "") + n + u[i];
    }

    /**
     * Parse a player-typed price: "12.3", "1.5k", "2m", "1,234.5". Returns tenths, or -1 on garbage. Never
     * accepts NaN/infinite/negative.
     */
    public static long parse(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("coins", "").trim();
        if (t.isEmpty()) return -1;
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k') mult = 1e3; else if (last == 'm') mult = 1e6; else if (last == 'b') mult = 1e9;
        if (mult != 1) t = t.substring(0, t.length() - 1);
        try {
            double v = Double.parseDouble(t) * mult;
            if (!Double.isFinite(v) || v <= 0 || v > 1e12) return -1;
            return Math.round(v * 10.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- limits that depend on the item (research 05 s2.3) -----------------------------------------------------

    /** Max units in one order: {@code stackable} (71,680) for a stacking item, {@code unstackable} (256) otherwise. */
    public static int orderCap(int maxStack, int stackable, int unstackable) {
        return Math.max(1, maxStack <= 1 ? unstackable : stackable);
    }

    /** Buy Order amount presets: 64 / 160 / 1,024 for stackables, 1 / 4 / 64 for unstackables (research). */
    public static int[] orderPresets(int maxStack) {
        return maxStack <= 1 ? new int[] {1, 4, 64} : new int[] {64, 160, 1024};
    }

    /**
     * Coins held up front for an Instant Buy: the quoted cost plus {@code reservePercent} (research: charged 4% above
     * the current offers, the difference refunded after the fill), never below the cost itself.
     */
    public static long instantBuyHold(long costTenths, double reservePercent) {
        if (costTenths <= 0) return 0;
        double pct = Double.isFinite(reservePercent) ? Math.max(0, Math.min(100, reservePercent)) : 0;
        return Math.addExact(costTenths, (long) Math.ceil(costTenths * pct / 100.0));
    }
}
