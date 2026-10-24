package com.skyclone.mod.bank;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/**
 * Every bank formula, with no Bukkit in it, so unit tests run the shipping numbers.
 * Numbers come from research 05 (section 1.2 tiers, 1.3 interest bands, 1.8 trade, 6.2 bits, 6.3 fame)
 * through the generated JSON in {@code data/bank/}.
 */
public final class BankMath {

    private BankMath() {}

    // ---- data shapes (Gson fills these from data/bank/*.json) ------------------------------------------

    public static final class Tier {
        public int index;
        public String id;
        public String name;
        public long capacity;
        public long coins;
        public int enchantedGoldBlocks;
        public long goldCollection;
        public int museumMilestone;
        public long maxInterest;
        public int sbxp;
    }

    public static final class Band {
        public long lo;
        public long hi;
        public double rate;
        public long fullBand;
        /** Index of the first account tier that earns this band. */
        public int tier;
    }

    public static final class FameRank {
        public String name;
        public long fame;
        public double mult;
        public int votes;
    }

    // ---- coins as tenths (same rounding rule as core: credits down, debits up) -----------------------

    public static long creditTenths(double coins) {
        if (!(coins >= 0) || Double.isInfinite(coins)) return 0;
        double x = coins * 10.0;
        long r = Math.round(x);
        if (Math.abs(x - r) < 1e-6) return r;
        return (long) Math.floor(x);
    }

    public static long debitTenths(double coins) {
        if (!(coins >= 0) || Double.isInfinite(coins)) return 0;
        double x = coins * 10.0;
        long r = Math.round(x);
        if (Math.abs(x - r) < 1e-6) return r;
        return (long) Math.ceil(x);
    }

    public static double coins(long tenths) { return tenths / 10.0; }

    /**
     * Exact tenths of a balance the economy reports. Purse and bank are always whole tenths (core stores
     * them that way), so nearest only absorbs float noise (102.5 might arrive as 102.49999999999999).
     */
    public static long tenthsOf(double coins) {
        if (!(coins > 0) || Double.isInfinite(coins)) return 0;
        return Math.round(coins * 10.0);
    }

    /**
     * A share of a balance ({@code num/den}, e.g. 1/2 or 1/5), computed in TENTHS and rounded DOWN, so the
     * amount shown on the button is the amount that moves and the odd tenth stays where it was:
     * half of 102.5 is 51.2 and 51.3 remains; half of 407.5 is 203.7. Doing this in doubles (balance / 2)
     * produced 51.25 / 203.75 — amounts the economy cannot hold — which were then silently truncated.
     */
    public static long share(long tenths, long num, long den) {
        if (tenths <= 0 || num <= 0 || den <= 0) return 0;
        if (num >= den) return tenths;
        return (tenths / den) * num + (tenths % den) * num / den;   // no overflow at any coin cap
    }

    /** Round DOWN to a tenth: the amount the bank can actually move without minting anything. */
    public static double floorTenth(double coins) {
        if (!(coins > 0) || Double.isInfinite(coins)) return 0;
        return creditTenths(coins) / 10.0;
    }

    // ---- interest (research 05 section 1.3) -----------------------------------------------------------

    /**
     * Interest for one payout: every band the tier has unlocked pays its rate on the part of the balance
     * that falls inside it. Starter at 50M = 10M x 2% + 5M x 1% = 250,000 (its cap).
     */
    public static double interest(double balance, int tier, List<Band> bands) {
        if (!(balance > 0)) return 0;
        double total = 0;
        for (Band b : bands) {
            if (b.tier > tier) continue;
            double covered = Math.max(0, Math.min(balance, b.hi) - b.lo);
            total += covered * b.rate;
        }
        return total;
    }

    /**
     * Interest on a smaller account whose bands are {@code scale} times as wide (personal accounts: the
     * research says "treat as 1/5 of main-bank scale"). scale 1 = the normal table.
     */
    public static double interestScaled(double balance, int tier, List<Band> bands, double scale) {
        if (!(scale > 0)) return 0;
        return interest(balance / scale, tier, bands) * scale;
    }

    /** The most one payout can be at this tier (sum of every unlocked full band). */
    public static double maxInterest(int tier, List<Band> bands) {
        double total = 0;
        for (Band b : bands) if (b.tier <= tier) total += (b.hi - b.lo) * b.rate;
        return total;
    }

    // ---- calendar periods ----------------------------------------------------------------------------

    /**
     * Index of the interest period a date falls in. Periods are {@code monthsPerPeriod} months long and
     * start on day 1 of month 1 (research: every season = 3 months: Early Spring/Summer/Autumn/Winter 1st).
     */
    public static long periodIndex(int year, int month, int monthsPerPeriod) {
        int m = Math.max(1, monthsPerPeriod);
        long months = (long) Math.max(0, year) * 12L + (Math.max(1, month) - 1);
        return months / m;
    }

    /** In-game days until the next period starts (fractional), given the current date. */
    public static double daysUntilNextPeriod(int month, int day, int hour, int minute, int monthsPerPeriod, int daysPerMonth) {
        int m = Math.max(1, monthsPerPeriod);
        double elapsed = ((Math.max(1, month) - 1) % m) * (double) daysPerMonth + (Math.max(1, day) - 1)
                + hour / 24.0 + minute / 1440.0;
        return Math.max(0, m * (double) daysPerMonth - elapsed);
    }

    // ---- tiers ------------------------------------------------------------------------------------------

    /** Highest tier whose capacity is at most {@code capacity} (used to repair a lost tier record). */
    public static int tierForCapacity(double capacity, List<Tier> tiers) {
        int best = 0;
        for (Tier t : tiers) if (t.capacity <= capacity + 0.5 && t.index > best) best = t.index;
        return best;
    }

    // ---- fame & bits (research 05 sections 6.2, 6.3) ----------------------------------------------------

    public static int fameRank(long fame, List<FameRank> ranks) {
        int idx = 0;
        for (int i = 0; i < ranks.size(); i++) if (fame >= ranks.get(i).fame) idx = i;
        return idx;
    }

    public static double bitsMultiplier(long fame, List<FameRank> ranks) {
        if (ranks.isEmpty()) return 1.0;
        return ranks.get(fameRank(fame, ranks)).mult;
    }

    /** Bits added to the "available" pool when a cookie is eaten: 4,800 x fame multiplier. */
    public static long cookiePool(long basePool, double mult) {
        return Math.max(0, Math.round(basePool * mult));
    }

    /** One payout from the pool: 250 x multiplier, never more than what is left. */
    public static long bitsPayout(long available, long base, double mult) {
        long want = Math.max(0, Math.round(base * mult));
        return Math.max(0, Math.min(available, want));
    }

    /**
     * Fame-rank bonus on bits from another source (research 05 section 6.2: "all x fame multiplier"): the part
     * ABOVE the base ({@code base x (mult - 1)}), with the fraction carried in {@code carry[0]} so 15 bits at 1.1x
     * pays 1, then 2, then 1 ... and nothing is lost or minted over time. Returns the whole bits to add now.
     */
    public static long fameBonus(long base, double mult, double[] carry) {
        if (base <= 0 || !(mult > 1.0) || Double.isInfinite(mult)) return 0;
        double c = (carry != null && carry.length > 0 && carry[0] >= 0 && carry[0] < 1) ? carry[0] : 0;
        double exact = base * (mult - 1.0) + c;
        long whole = (long) Math.floor(exact + 1e-9);
        if (carry != null && carry.length > 0) carry[0] = Math.max(0, exact - whole);
        return Math.max(0, whole);
    }

    /**
     * Should a bits gain with this ledger {@code reason} get the fame bonus? Never: our own cookie payouts (they
     * already carry the multiplier), a Bits Talisman double (it doubles what was already multiplied), refunds and
     * admin grants ({@code exclude}: lower-case substrings). Modules that apply {@code bitsMultiplier} themselves
     * ({@code selfApplying}, by reason prefix) are skipped too. {@code sources}: reason prefixes that get it
     * ("*" = every other source).
     */
    public static boolean fameAppliesTo(String reason, List<String> sources, List<String> exclude, java.util.Collection<String> selfApplying) {
        if (reason == null || reason.isBlank()) return false;
        String r = reason.trim().toLowerCase(Locale.ROOT);
        if (r.startsWith("bank:") || r.startsWith("accessories:")) return false;
        for (String x : exclude) if (x != null && !x.isBlank() && r.contains(x.trim().toLowerCase(Locale.ROOT))) return false;
        String mod = reasonModule(r);
        if (mod != null && selfApplying != null && selfApplying.contains(mod)) return false;
        for (String s : sources) {
            if (s == null || s.isBlank()) continue;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("*")) return true;
            if (r.startsWith(t)) return true;
            if (mod != null && mod.equals(t.endsWith(":") ? t.substring(0, t.length() - 1) : t)) return true;
        }
        return false;
    }

    /** The module a ledger reason names ("slayer: zombie T5" -> "slayer"), or null. */
    public static String reasonModule(String reason) {
        if (reason == null) return null;
        int i = reason.indexOf(':');
        if (i <= 0) return null;
        String m = reason.substring(0, i).trim().toLowerCase(Locale.ROOT);
        return m.matches("[a-z0-9_-]{1,32}") ? m : null;
    }

    /**
     * The feature module that called into the bank, from a stack of class names (innermost first): skip the
     * bank's own frames at the top (the resolver/service itself), then return the first module frame below them;
     * null when that frame is the bank again (the bank asking itself through core) or there is none.
     */
    public static String callerModule(List<String> classNames) {
        int i = 0;
        while (i < classNames.size() && classNames.get(i) != null && classNames.get(i).startsWith(BANK_PKG)) i++;
        for (; i < classNames.size(); i++) {
            String c = classNames.get(i);
            if (c == null || !c.startsWith(MOD_PKG)) continue;
            String rest = c.substring(MOD_PKG.length());
            int dot = rest.indexOf('.');
            String mod = dot < 0 ? rest : rest.substring(0, dot);
            return mod.equals("bank") || mod.isEmpty() ? null : mod;
        }
        return null;
    }

    private static final String MOD_PKG = "com.skyclone.mod.";
    private static final String BANK_PKG = "com.skyclone.mod.bank.";

    /** Fame for coins spent on something the reference sells for gems (research 6.3: 200 Fame per gem). */
    public static long fameForGems(double gems, double famePerGem) {
        if (!(gems > 0) || !(famePerGem > 0)) return 0;
        return (long) Math.floor(gems * famePerGem + 1e-9);
    }

    /**
     * How many tiers of a Community Shop upgrade award SkyBlock XP (10 each): NEU {@code sblevels.json}
     * {@code community_shop_upgrades_max} = island_size 1, minion_slots 5, guests_count 1, coins_allowance 5 (total 120);
     * every other upgrade 0.
     */
    public static int communityXpTiers(String upgradeId) {
        if (upgradeId == null) return 0;
        return switch (upgradeId) {
            case "island_size", "guests_limit" -> 1;
            case "minion_slots", "coins_allowance" -> 5;
            default -> 0;
        };
    }

    // ---- co-op slots (research 05 sections 6.4, 11: 5 members, +1..+3 via Co-op Slots I-III) -------------

    /**
     * Co-op cap of a profile: {@code base} plus the extra members the owned tier gives. Tier values are the research's
     * TOTALS (6/7/8 over a research base of {@code researchBase} = 5), so the extra is {@code value - researchBase};
     * never above core's server-wide cap.
     */
    public static int coopCap(int base, int tier, double tierValue, int researchBase, int coreCap) {
        int extra = tier > 0 ? Math.max(0, (int) Math.round(tierValue) - researchBase) : 0;
        int cap = Math.max(1, base) + extra;
        return Math.max(1, Math.min(cap, Math.max(1, coreCap)));
    }

    /**
     * {"invite", name} for "/coop invite|add Name" and "/coopadd Name", {"accept"} for "/coop accept|join",
     * otherwise null. Any case, extra spaces, a "namespace:" prefix.
     */
    public static String[] coopCommand(String message) {
        if (message == null) return null;
        String[] w = message.trim().split("\\s+");
        if (w.length == 0) return null;
        String cmd = w[0].toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (cmd.equals("coopadd")) {
            return w.length >= 2 && w[1].matches("[A-Za-z0-9_]{1,16}") ? new String[]{"invite", w[1]} : null;
        }
        if (!cmd.equals("coop") || w.length < 2) return null;
        String sub = w[1].toLowerCase(Locale.ROOT);
        if (sub.equals("invite") || sub.equals("add")) {
            return w.length >= 3 && w[2].matches("[A-Za-z0-9_]{1,16}") ? new String[]{"invite", w[2]} : null;
        }
        if (sub.equals("accept") || sub.equals("join")) return new String[]{"accept"};
        return null;
    }

    // ---- museum interest bonus (research 05 section 1.3) -------------------------------------------------

    /**
     * {@code 1 + perMilestone x milestones}: research "each Museum milestone adds +2% to interest earned",
     * applied as a multiplier on the computed payout. {@code maxMilestones} caps the count (0 or less = no cap).
     */
    public static double museumInterestMultiplier(int milestones, double perMilestone, int maxMilestones) {
        int m = Math.max(0, milestones);
        if (maxMilestones > 0) m = Math.min(m, maxMilestones);
        if (!(perMilestone > 0) || Double.isInfinite(perMilestone)) return 1.0;
        return 1.0 + perMilestone * m;
    }

    // ---- trade (research 05 section 1.8) ----------------------------------------------------------------

    /** Daily coin cap for /trade: 50M under SkyBlock level 50, 1B at 50+, 10B at 100+. */
    public static double tradeCap(int sbLevel, double low, double mid, double high, int midLevel, int highLevel) {
        if (sbLevel >= highLevel) return high;
        if (sbLevel >= midLevel) return mid;
        return low;
    }

    // ---- text -------------------------------------------------------------------------------------------

    private static final ThreadLocal<DecimalFormat> COMMAS =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US)));

    /**
     * "1,234.5" — always rounded DOWN to a tenth (never shows coins nobody will receive). Every amount the
     * bank moves is made a whole tenth BEFORE it is shown (see {@link #share}), so this floor only ever
     * touches projections such as interest, which core also credits rounded down.
     */
    public static String commas(double v) { return COMMAS.get().format(Math.floor(v * 10 + 1e-6) / 10.0); }

    private static final ThreadLocal<DecimalFormat> EXACT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(Locale.US)));

    /** What the player typed, unrounded ("51.25"), for the "rounded down to 51.2" note. */
    public static String commasExact(double v) { return EXACT.get().format(v); }

    /** {@link #commas} of an exact tenths count. */
    public static String commasTenths(long tenths) { return commas(coins(tenths)); }

    /** 1234 -> 1.2k ; 12500000 -> 12.5M ; 3e9 -> 3B (rounded down, never up). */
    public static String shortNum(double v) {
        double a = Math.abs(v);
        String[] units = {"", "k", "M", "B", "T"};
        int i = 0;
        while (a >= 1000 && i < units.length - 1) { a /= 1000; i++; }
        String n;
        if (i == 0) n = commas(a);
        else if (a >= 100) n = String.valueOf((long) Math.floor(a));
        else {
            double f = Math.floor(a * 10) / 10.0;
            n = f == Math.floor(f) ? String.valueOf((long) f) : String.valueOf(f);
        }
        return (v < 0 ? "-" : "") + n + units[i];
    }

    /**
     * The anti-scam coin readout: full digits plus the short form, so "1.25M" and "12.5M" can never be
     * confused (research 05 section 1.8 rule 4): {@code 12,500,000 coins (12.5M)}.
     */
    public static String coinWords(double coins) {
        if (coins < 1000) return commas(coins) + " coins";
        return commas(coins) + " coins (" + shortNum(coins) + ")";
    }

    public static String roman(int n) {
        if (n <= 0) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { n -= v[i]; sb.append(s[i]); }
        return sb.toString();
    }

    /** "3d 4h", "2h 5m", "45s" from milliseconds. */
    public static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    /** "3 hours ago" style age for the transaction log. */
    public static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return "just now";
        long m = s / 60;
        if (m < 60) return m + (m == 1 ? " minute ago" : " minutes ago");
        long h = m / 60;
        if (h < 48) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        return d + " days ago";
    }

    /**
     * Why accepting this side of a trade needs a second click, or null. Research 05 section 1.8: "a trade where
     * one side is empty" and "a large disparity / first-time partner may prompt an extra confirmation".
     * {@code pairTrades} = trades these two completed before (-1 = unknown yet, treated as not-first).
     */
    public static String tradeRisk(boolean giving, boolean getting, double myCoins, int pairTrades,
                                   double firstCoinsThreshold, String partner) {
        if (giving && !getting) return "You are getting nothing back!";
        if (pairTrades == 0 && firstCoinsThreshold > 0 && myCoins >= firstCoinsThreshold)
            return "Your first trade with " + partner + " and you pay " + coinWords(myCoins) + ".";
        return null;
    }

    /** Eight-point compass word for a horizontal offset (Minecraft: -Z is north, +X is east). */
    public static String compass(double dx, double dz) {
        if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) return "here";
        double deg = Math.toDegrees(Math.atan2(dx, -dz));   // 0 = north, 90 = east
        if (deg < 0) deg += 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(deg / 45.0) % 8];
    }
}
