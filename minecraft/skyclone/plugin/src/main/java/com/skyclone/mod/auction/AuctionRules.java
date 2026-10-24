package com.skyclone.mod.auction;

/**
 * Every Auction House number, as pure functions (no Bukkit), so unit tests and the live self-test
 * check the same code the game runs. Sources: research/05-economy-events-mayors.md section 3.
 *
 * <pre>
 *   BIN listing fee      1% under 10M, 2% from 10M to 100M, 2.5% at 100M and above   (3.2)
 *   Auction listing fee  5% of the starting bid                                       (3.2)
 *   Duration fee         1h 20, 6h 45, 12h 100, 24h 350, 48h 1,200, 14 days 55,200      (3.2)
 *   Claim tax            1% on collections above 1M, never below 1M                     (3.4)
 *   Min next bid         top bid + 2.5% (older behaviour +15%: a dial)                  (3.3)
 *   Anti-snipe           a bid with under 2 min left resets the clock to 2 min          (3.3)
 *   BIN grace            20 s after listing before it can be bought                     (3.2)
 *   Max listings         14, +3 per extra co-op member, 35 at most                      (3.5)
 *   COMMON price cap     may net at most 5x the stack's NPC sell value, fees on top     (3.5)
 * </pre>
 */
public final class AuctionRules {

    /** Tunables, read from dials at use time (see AuctionModule). Defaults are the research numbers. */
    public static final class Settings {
        public double binFeeLow = 0.01, binFeeMid = 0.02, binFeeHigh = 0.025;
        public double auctionFee = 0.05;
        public double durationFeeScale = 1.0;
        public double claimTax = 0.01;
        public double claimTaxThreshold = 1_000_000;
        public double claimTaxMultiplier = 1.0;        // 4 under the "quad taxes" mayor perk
        public double minIncrement = 0.025;
        public long antiSnipeSeconds = 120;
        public long binGraceSeconds = 20;
        public int maxListingsBase = 14, maxListingsPerMember = 3, maxListingsCap = 35;
        public double commonCapMultiplier = 5;
        public double minPrice = 1;
        public double maxPrice = 50_000_000_000d;
        public long minDurationMinutes = 5;            // TUNE: custom durations shorter than an hour are allowed
        public long maxDurationHours = 336;
    }

    /** Per-instance {@code ItemData.extra} keys that bind an item to a player or co-op (never auctionable). */
    public static final java.util.List<String> BOUND_KEYS = java.util.List.of("soulbound", "coop_soulbound", "museum_soulbound");

    /** True when an item's extra keys include any binding key. */
    public static boolean boundKeys(java.util.Collection<String> extraKeys) {
        if (extraKeys == null) return false;
        for (String k : BOUND_KEYS) if (extraKeys.contains(k)) return true;
        return false;
    }

    /** BIN fee bands (research 3.2): below 10M, below 100M, from 100M. */
    public static final double BIN_BAND_MID = 10_000_000, BIN_BAND_HIGH = 100_000_000;

    /** The preset durations offered by the duration menu, in hours. */
    public static final int[] PRESET_HOURS = {1, 6, 12, 24, 48};

    /** Duration fee anchor points: hours -> coins. Between points the fee is linear. */
    private static final double[][] DURATION_FEE = {
            {1, 20}, {6, 45}, {12, 100}, {24, 350}, {48, 1_200}, {336, 55_200}
    };

    private AuctionRules() {}

    // ---- fees -----------------------------------------------------------------------------------

    /** Listing fee alone (no duration part). {@code price} = BIN price or starting bid for the whole stack. */
    public static double listingFee(Settings s, boolean bin, double price) {
        if (price <= 0) return 0;
        if (!bin) return price * s.auctionFee;
        double rate = price < BIN_BAND_MID ? s.binFeeLow : price < BIN_BAND_HIGH ? s.binFeeMid : s.binFeeHigh;
        return price * rate;
    }

    /** Duration fee for a length in minutes; piecewise linear through the research points, flat below 1h. */
    public static double durationFee(Settings s, long minutes) {
        double h = minutes / 60.0;
        double fee;
        if (h <= DURATION_FEE[0][0]) fee = DURATION_FEE[0][1];
        else if (h >= DURATION_FEE[DURATION_FEE.length - 1][0]) fee = DURATION_FEE[DURATION_FEE.length - 1][1];
        else {
            fee = DURATION_FEE[0][1];
            for (int i = 1; i < DURATION_FEE.length; i++) {
                double h0 = DURATION_FEE[i - 1][0], f0 = DURATION_FEE[i - 1][1];
                double h1 = DURATION_FEE[i][0], f1 = DURATION_FEE[i][1];
                if (h <= h1) { fee = f0 + (f1 - f0) * (h - h0) / (h1 - h0); break; }
            }
        }
        return Math.round(fee * s.durationFeeScale * 10) / 10.0;
    }

    /** What creating the listing costs, rounded up to a tenth of a coin like the economy does. */
    public static double totalFee(Settings s, boolean bin, double price, long minutes) {
        double f = listingFee(s, bin, price) + durationFee(s, minutes);
        return Math.ceil(f * 10 - 1e-9) / 10.0;
    }

    /** What the seller receives when collecting a sale of {@code price}. */
    public static double claimPayout(Settings s, double price) {
        if (price <= s.claimTaxThreshold) return price;
        double taxed = price * (1 - s.claimTax * s.claimTaxMultiplier);
        return Math.floor(Math.max(s.claimTaxThreshold, taxed) * 10) / 10.0;
    }

    // ---- bidding ----------------------------------------------------------------------------------

    /** Smallest acceptable next bid. */
    public static double minNextBid(Settings s, double startingBid, double topBid) {
        if (topBid <= 0) return Math.ceil(startingBid);
        double next = Math.ceil(topBid * (1 + s.minIncrement));
        return Math.max(next, Math.floor(topBid) + 1);
    }

    /** New end time after a bid at {@code now}: bids in the last N seconds push the end out to N seconds. */
    public static long endAfterBid(Settings s, long endsAt, long now) {
        long min = now + s.antiSnipeSeconds * 1000L;
        return Math.max(endsAt, min);
    }

    // ---- listing limits -------------------------------------------------------------------------------

    public static int maxListings(Settings s, int coopMembers) {
        int extra = Math.max(0, coopMembers - 1);
        return Math.min(s.maxListingsCap, s.maxListingsBase + s.maxListingsPerMember * extra);
    }

    /**
     * The most a COMMON stack may NET its seller: {@code commonCapMultiplier} (5) x its NPC sell value
     * (research 3.5), or +infinity when there is no cap. Items that cannot be sold to an NPC have no NPC
     * value to anchor a cap to, so they are capped at 0 by the reference rule; we leave those uncapped
     * instead (TUNE: the cap exists to stop laundering coins through junk, and junk always has an NPC price).
     */
    public static double commonValueCap(Settings s, String rarity, double npcSellEach, int amount) {
        if (!"COMMON".equals(rarity) || npcSellEach <= 0) return Double.POSITIVE_INFINITY;
        return Math.max(1, Math.floor(npcSellEach * Math.max(1, amount) * s.commonCapMultiplier));
    }

    /**
     * Highest LIST price for a COMMON stack: the smallest price whose sale still nets the seller
     * {@link #commonValueCap} after the listing fee, the duration fee and the claim tax. The research cap
     * read literally (price at most 5x NPC value) made every COMMON listing a loss - a Wooden Sword capped at
     * 5 coins against a 20-coin cheapest duration fee (playtest 2026-10). Fees are a coin sink, so pricing
     * them in opens no laundering: the most a buyer can move to the seller through junk is still 5x its
     * NPC value. +infinity when there is no cap.
     */
    public static double commonCap(Settings s, String rarity, double npcSellEach, int amount, boolean bin, long minutes) {
        double net = commonValueCap(s, rarity, npcSellEach, amount);
        if (Double.isInfinite(net)) return net;
        double p = Math.ceil(net + durationFee(s, minutes));
        for (int i = 0; i < 64; i++) {                       // fee ~1-5% of p: converges in a few steps
            double short_ = net - netIfSold(s, bin, p, minutes);
            if (short_ <= 1e-9) break;
            p += Math.max(1, Math.ceil(short_));
        }
        return Math.min(p, s.maxPrice);
    }

    /** What the seller keeps if the stack sells at exactly {@code price}: claim payout minus every fee paid up front. */
    public static double netIfSold(Settings s, boolean bin, double price, long minutes) {
        return claimPayout(s, price) - totalFee(s, bin, price, minutes);
    }

    /** Null when the price is allowed, else the reason. Pass the stack's rarity, NPC value, size, type and duration. */
    public static String checkPrice(Settings s, double price, String rarity, double npcSellEach, int amount, boolean bin, long minutes) {
        if (Double.isNaN(price) || Double.isInfinite(price)) return "That is not a price.";
        if (price < s.minPrice) return "The price must be at least " + fmt(s.minPrice) + " coins.";
        if (price > s.maxPrice) return "The price cannot be above " + fmt(s.maxPrice) + " coins.";
        double cap = commonCap(s, rarity, npcSellEach, amount, bin, minutes);
        if (price > cap) return "COMMON items cannot be listed above " + fmt(cap) + " coins for this duration ("
                + fmt(s.commonCapMultiplier) + "x their NPC value after fees).";
        return null;
    }

    public static String checkDuration(Settings s, long minutes) {
        if (minutes < s.minDurationMinutes) return "An auction must last at least " + s.minDurationMinutes + " minutes.";
        if (minutes > s.maxDurationHours * 60) return "An auction cannot last longer than " + s.maxDurationHours + " hours.";
        return null;
    }

    // ---- words --------------------------------------------------------------------------------------

    /** "12,500,000" (whole coins keep no decimals). */
    public static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.format(java.util.Locale.US, "%,d", (long) Math.rint(v));
        return String.format(java.util.Locale.US, "%,.1f", v);
    }

    /** Anti-scam readout: "12,500,000 (12.5M)" - digits plus the short form, so 1.25M and 12.5M never blur. */
    public static String coinsWords(double v) {
        if (v < 1000) return fmt(v);
        return fmt(v) + " (" + shortNum(v) + ")";
    }

    public static String shortNum(double v) {
        double a = Math.abs(v);
        String[] units = {"", "k", "M", "B", "T"};
        int i = 0;
        while (a >= 1000 && i < units.length - 1) { a /= 1000; i++; }
        String n = (a >= 100 || i == 0) ? String.valueOf((long) Math.floor(a))
                : new java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US)).format(Math.floor(a * 10) / 10);
        return (v < 0 ? "-" : "") + n + units[i];
    }

    /** "1d 4h", "3h 12m", "12m 5s", "4s" - always two units when there are two. */
    public static String timeLeft(long millis) {
        long s = Math.max(0, millis / 1000);
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    /** "6 Hours", "1 Hour", "2 Days", "90 Minutes". */
    public static String durationWords(long minutes) {
        if (minutes % (60 * 24) == 0 && minutes >= 60 * 24 * 2) return (minutes / 1440) + " Days";
        if (minutes % 60 == 0) return (minutes / 60) + (minutes == 60 ? " Hour" : " Hours");
        return minutes + " Minutes";
    }

    /** Parse "6h", "90m", "2d", "1d12h", "36" (hours). Returns minutes, or -1. */
    public static long parseDuration(String in) {
        if (in == null) return -1;
        String s = in.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) return -1;
        try {
            if (s.chars().allMatch(Character::isDigit)) return Long.parseLong(s) * 60;
            long total = 0;
            StringBuilder num = new StringBuilder();
            for (char ch : s.toCharArray()) {
                if (Character.isDigit(ch) || ch == '.') { num.append(ch); continue; }
                if (num.length() == 0) return -1;
                double v = Double.parseDouble(num.toString());
                num.setLength(0);
                switch (ch) {
                    case 'd' -> total += Math.round(v * 1440);
                    case 'h' -> total += Math.round(v * 60);
                    case 'm' -> total += Math.round(v);
                    default -> { return -1; }
                }
            }
            if (num.length() > 0) return -1;
            return total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
