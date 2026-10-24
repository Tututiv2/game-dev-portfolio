package com.skyclone.mod.bazaar;

import java.util.ArrayList;
import java.util.List;

/**
 * Market-maker quoting maths (research 05 s2.9), PURE so the invariants can be unit tested:
 * <ul>
 *   <li>every bid is strictly below every ask (no bot-vs-bot arbitrage, ever);</li>
 *   <li>the best bid is never below the NPC sell price when one exists (the floor is visible on the book),
 *       unless a recipe cap says otherwise (arbitrage prevention beats liquidity);</li>
 *   <li>the best bid never exceeds the recipe cap {@code bidCap}; a cap below one tick means no bid at all;</li>
 *   <li>the best ask is never above an NPC shop price when one exists;</li>
 *   <li>inventory skew: holding more than the target pushes both quotes down (sell more), less pushes them up.</li>
 * </ul>
 */
public final class MmMath {

    private MmMath() {}

    public record Level(long price, int qty) {}

    public record Quotes(List<Level> bids, List<Level> asks, long fairTenths) {
        public long bestBid() { return bids.isEmpty() ? 0 : bids.get(0).price; }
        public long bestAsk() { return asks.isEmpty() ? 0 : asks.get(0).price; }
    }

    public static final class Params {
        public double fair;          // coins
        public double inventory;     // units the bot holds
        public double target;        // units it wants to hold
        public double spread;        // full spread as a fraction (0.08 = 8%)
        public int depth;            // units per level
        public int levels = 3;
        public double step = 0.015;  // extra distance per deeper level (fraction of fair)
        public double skewK = 0.05;  // research: k ~ 0.05
        public double npcSell;       // coins, 0 = none
        public double shopPrice;     // coins, 0 = none
        public double bidCap;        // coins, 0 = none (recipe arbitrage cap)
        public boolean quoteBids = true;
        public boolean quoteAsks = true;
    }

    /** Inventory skew factor in [-1, 2] (how far above/below target the bot is, in targets). */
    public static double skew(double inv, double target) {
        if (target <= 0) return 0;
        return Math.max(-1, Math.min(2, (inv - target) / target));
    }

    public static Quotes quote(Params p) {
        double fair = Math.max(0.1, p.fair);
        double eff = fair * (1 - p.skewK * skew(p.inventory, p.target));
        double half = Math.max(0.005, p.spread / 2);
        long bid = Pricing.tenthsDown(eff * (1 - half));
        long ask = Pricing.tenthsUp(eff * (1 + half));
        // floors and ceilings. An NPC shop that sells below the NPC sell price is a data inconsistency elsewhere;
        // then the floor is dropped (never bid above what a shop sells for) and only the ceiling holds.
        boolean shop = p.shopPrice > 0;
        if (p.npcSell > 0 && (!shop || p.npcSell < p.shopPrice)) bid = Math.max(bid, Pricing.tenthsUp(p.npcSell));
        if (shop) {
            ask = Math.min(ask, Pricing.tenthsDown(p.shopPrice));
            bid = Math.min(bid, Pricing.tenthsDown(p.shopPrice * 0.97));
        }
        if (p.bidCap > 0) bid = Math.min(bid, Pricing.tenthsDown(p.bidCap));
        // a cap under one tick (0.1 coin) cannot be honoured by any bid: post none rather than the 1-tick minimum
        boolean quoteBids = p.quoteBids && !(p.bidCap > 0 && Pricing.tenthsDown(p.bidCap) < 1);
        // spread can never close: the ask stays at least one tick (and 1% of the bid) above the bid
        long minGap = Math.max(1, Math.round(bid * 0.01));
        if (ask < bid + minGap) {
            if (shop && bid + minGap > Pricing.tenthsDown(p.shopPrice)) {
                ask = Math.max(2, Pricing.tenthsDown(p.shopPrice));             // ceiling binds: give way on the bid
                bid = Math.max(1, ask - minGap);
            } else {
                ask = bid + minGap;
            }
        }
        if (bid < 1) bid = 1;
        if (ask <= bid) ask = bid + 1;
        List<Level> bids = new ArrayList<>(), asks = new ArrayList<>();
        int levels = Math.max(1, p.levels);
        int depth = Math.max(1, p.depth);
        double room = Math.max(0, p.inventory);                 // the bot can only sell what it holds
        double headroom = Math.max(0, p.target * 3 - p.inventory); // and stops buying when overstocked
        long stepT = Math.max(1, Pricing.tenthsUp(fair * p.step));
        for (int i = 0; i < levels; i++) {
            if (quoteBids) {
                long bp = bid - stepT * i;
                int q = (int) Math.min(depth, Math.floor(headroom));
                if (bp >= 1 && q > 0) { bids.add(new Level(bp, q)); headroom -= q; }
            }
            if (p.quoteAsks) {
                long ap = ask + stepT * i;
                int q = (int) Math.min(depth, Math.floor(room));
                if (q > 0) { asks.add(new Level(ap, q)); room -= q; }
            }
        }
        return new Quotes(bids, asks, Pricing.tenths(eff));
    }

    /** Spread by liquidity (weekly reference volume). TUNE: research gives 6-12% commodities, 15-30% rare. */
    public static double spreadFor(long weekly, double mult) {
        double s;
        if (weekly >= 1_000_000) s = 0.06;
        else if (weekly >= 50_000) s = 0.09;
        else if (weekly >= 1_000) s = 0.14;
        else if (weekly >= 50) s = 0.20;
        else s = 0.28;
        return Math.max(0.01, s * mult);
    }

    /** Units per level: about an hour of reference volume x scale, at least 1, and worth at most {@code maxValue}. */
    public static int depthFor(long weekly, double fair, double scale, int min, int max, double maxValue) {
        double hourly = weekly / (7.0 * 24.0);
        double d = Math.max(min, hourly * scale);
        if (fair > 0 && maxValue > 0) d = Math.min(d, Math.max(1, maxValue / fair));
        return (int) Math.max(1, Math.min(max, Math.round(d)));
    }

    /**
     * One Ornstein-Uhlenbeck step on log(fair) toward log(anchor): mean reversion {@code theta} per hour, volatility
     * {@code sigma} per sqrt(hour), {@code z} a standard normal draw. Returns the new fair (never below 0.1).
     */
    public static double drift(double fair, double anchor, double hours, double theta, double sigma, double z) {
        if (fair <= 0) fair = Math.max(0.1, anchor);
        if (anchor <= 0) return fair;
        double lf = Math.log(fair), la = Math.log(anchor);
        double keep = Math.exp(-theta * hours);
        lf = la + (lf - la) * keep + sigma * Math.sqrt(Math.max(0, hours)) * z;
        // never wander further than x3 / /3 from the anchor
        lf = Math.max(la - Math.log(3), Math.min(la + Math.log(3), lf));
        return Math.max(0.1, Math.exp(lf));
    }

    /**
     * Research 05 s2.9 fade-out: as players trade a product themselves, the market maker steps back so real players
     * set busy prices. {@code playerVolume} is the decayed player volume (~6h half-life); its share against a day of
     * the maker's own depth shrinks the depth by up to {@code maxFade}, never below {@code min}.
     */
    public static int fade(int depth, double playerVolume, double maxFade, int min) {
        if (depth <= 0) return depth;
        double pv = Math.max(0, playerVolume);
        double share = pv / (pv + depth * 24.0);
        share = Math.min(Math.max(0, Math.min(0.95, maxFade)), share);
        return (int) Math.max(Math.max(1, Math.min(min, depth)), Math.round(depth * (1 - share)));
    }

    /** Restock toward the target with a half-life (hours). */
    public static double restock(double inv, double target, double hours, double halfLife) {
        if (halfLife <= 0) return target;
        double k = 1 - Math.pow(0.5, hours / halfLife);
        return inv + (target - inv) * k;
    }
}
