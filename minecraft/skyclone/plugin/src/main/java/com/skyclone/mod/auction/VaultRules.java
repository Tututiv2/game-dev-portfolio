package com.skyclone.mod.auction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Vault Auction's numbers, as pure functions (no Bukkit) so the unit tests check what the game runs.
 * Research 05 section 5.2 (the reference game's yearly auction beneath the Auction House):
 * <pre>
 *   one auction every SkyBlock year (124 real hours); limited stock per item; the top bidders claim after it ends
 *   main track: always the same three items (gated on Museum Milestone 10)
 *   special track: a rotating pair (gated on Museum Milestone 20)
 *   volume perk (Diaz): stock x2 and +2 extra special lots
 * </pre>
 * Ironman (research 05 5.2 + 9.3): "its own Ironman-only Shen's with 50 % stock" - every classic lot (main AND
 * Special, since the research does not split them) has an Ironman copy at half the stock, rounded up; only
 * Ironman profiles see or bid on the copies, and only classic profiles the originals.
 * Ranked bidding: a lot with stock N keeps the N highest bids; a bid that pushes someone out must beat the
 * lowest kept bid by the increment, and the bid it pushes out is refunded at once. Everyone kept when the
 * year ends wins and pays their OWN bid. Ties go to the earlier bid.
 */
public final class VaultRules {

    private VaultRules() {}

    /** One bid as the rules see it. */
    public record Entry(String who, double amount, long time) {}

    /** One main-track lot from the dial text. */
    public record Spec(String itemId, int stock, double minBid) {}

    /** Highest first; on equal amounts the earlier bid ranks higher. */
    public static final Comparator<Entry> RANK = Comparator.comparingDouble(Entry::amount).reversed()
            .thenComparingLong(Entry::time).thenComparing(Entry::who);

    /** Smallest bid a NEW bidder may place on a lot holding {@code active} bids and {@code stock} places. */
    public static double minNewBid(double minimum, int stock, List<Entry> active, double increment) {
        double floor = Math.ceil(Math.max(1, minimum));
        if (active.size() < Math.max(1, stock)) return floor;
        double lowest = lowestKept(stock, active);
        double next = Math.max(Math.ceil(lowest * (1 + increment)), Math.floor(lowest) + 1);
        return Math.max(floor, next);
    }

    /** Smallest new total for a bidder who already holds {@code own} on the lot. */
    public static double minRaise(double own, double increment) {
        return Math.max(Math.ceil(own * (1 + increment)), Math.floor(own) + 1);
    }

    /** The lowest bid that is still kept (would win if the year ended now); 0 when there are none. */
    public static double lowestKept(int stock, List<Entry> active) {
        List<Entry> sorted = new ArrayList<>(active);
        sorted.sort(RANK);
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size(), Math.max(1, stock)) - 1).amount();
    }

    /** Who drops out when {@code active} holds more bids than {@code stock}: the lowest-ranked ones. */
    public static List<Entry> displaced(int stock, List<Entry> active) {
        List<Entry> sorted = new ArrayList<>(active);
        sorted.sort(RANK);
        int keep = Math.max(1, stock);
        return sorted.size() <= keep ? List.of() : new ArrayList<>(sorted.subList(keep, sorted.size()));
    }

    /**
     * Copies of a lot on the Ironman-only track (research 05 5.2: "Ironman has its own Ironman-only Shen's with
     * 50 % stock"): the classic stock times {@code fraction}, rounded UP so a 3-copy lot gives 2 rather than 1,
     * never below 1. A non-finite or non-positive fraction falls back to the research's 0.5.
     */
    public static int ironmanStock(int classicStock, double fraction) {
        double f = Double.isFinite(fraction) && fraction > 0 ? Math.min(1, fraction) : 0.5;
        return Math.max(1, (int) Math.ceil(Math.max(1, classicStock) * f - 1e-9));
    }

    // ---- tracks (research 05 5.2: Shen's Auction, Shen's Special, and Ironman's own copy of both) -------------

    public static final String MAIN = "main", SPECIAL = "special";
    /** The Ironman-only copy of the main track. */
    public static final String IRONMAN = "ironman";
    /** The Ironman-only copy of this year's Special lots (wave 5). */
    public static final String IRONMAN_SPECIAL = "ironman_special";

    /** Lots only Ironman profiles see and bid on. */
    public static boolean ironmanTrack(String track) { return IRONMAN.equals(track) || IRONMAN_SPECIAL.equals(track); }

    /** Lots gated on the Special requirement (research: Museum Milestone 20). */
    public static boolean specialTrack(String track) { return SPECIAL.equals(track) || IRONMAN_SPECIAL.equals(track); }

    /**
     * Does a viewer see (and may bid on) a lot of this track? An Ironman profile sees ONLY the Ironman lots,
     * a classic profile never sees them (research 05 5.2: "Ironman has its own Ironman-only Shen's").
     */
    public static boolean visible(String track, boolean ironmanViewer) { return ironmanTrack(track) == ironmanViewer; }

    /** Menu order: main, Ironman main, Special, Ironman Special (unknown tracks last). */
    public static int trackOrder(String track) {
        if (MAIN.equals(track)) return 0;
        if (IRONMAN.equals(track)) return 1;
        if (SPECIAL.equals(track)) return 2;
        if (IRONMAN_SPECIAL.equals(track)) return 3;
        return 4;
    }

    /**
     * May a profile of this game mode use the Vault at all? Null = yes, else the refusal. Classic always;
     * Ironman while it has its own track ({@code ironmanVault}) - independent of the cosmetic-only main AH,
     * because research 05 5.2 gives Ironman its own Shen's whatever the main Auction House does; Stranded and
     * Bingo never ("Bingo/Stranded excluded"). {@code specialModes} (the AH's "open everything" dial) lets
     * every mode in as a classic profile.
     */
    public static String modeRefusal(String mode, boolean specialModes, boolean ironmanVault) {
        if (specialModes || mode == null || mode.isBlank() || "normal".equalsIgnoreCase(mode) || "classic".equalsIgnoreCase(mode)) return null;
        if ("ironman".equalsIgnoreCase(mode)) return ironmanVault ? null : "The Vault Auction has no Ironman lots right now.";
        String m = mode.substring(0, 1).toUpperCase(Locale.ROOT) + mode.substring(1).toLowerCase(Locale.ROOT);
        return "The Vault Auction is not available on " + m + " profiles.";
    }

    /** The Ironman copy of a classic track ({@code main -> ironman}, {@code special -> ironman_special}). */
    public static String ironmanCopyOf(String classicTrack) {
        return SPECIAL.equals(classicTrack) ? IRONMAN_SPECIAL : MAIN.equals(classicTrack) ? IRONMAN : null;
    }

    /**
     * The Ironman copy of a set of classic lots: same item, same minimum bid, {@link #ironmanStock} of the classic
     * stock (the volume perk is already in {@code classic}'s stock, so it carries over at the same ratio).
     */
    public static List<Spec> ironmanCopies(List<Spec> classic, double fraction) {
        List<Spec> out = new ArrayList<>();
        if (classic == null) return out;
        for (Spec s : classic) out.add(new Spec(s.itemId(), ironmanStock(s.stock(), fraction), s.minBid()));
        return out;
    }

    /**
     * The gate a Vault track uses: the dial's key when its prefix is resolvable, otherwise {@code fallback}.
     * The research gates are Museum Milestones 10 / 20 ({@code museum_milestone:<n>}, progression's resolver);
     * without progression that prefix is unknown and would fail closed for everyone, so the Vault falls back to
     * a donation count instead of locking every player out.
     */
    public static String gate(String dial, String fallback, java.util.function.Predicate<String> knownPrefix) {
        if (dial == null || dial.isBlank()) return "";
        for (String part : dial.split("&")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            int c = t.indexOf(':');
            String prefix = (c < 0 ? t : t.substring(0, c)).trim().toLowerCase(Locale.ROOT);
            if (!knownPrefix.test(prefix)) return fallback == null ? dial : fallback;
        }
        return dial;
    }

    /** How a closed lot went: copies taken, the lowest / highest / mean winning bid (0 when none). */
    public record Result(int sold, int unsold, double lowest, double highest, double mean) {}

    /** Summarise the winning bids of a closed lot with {@code stock} copies (the Regalia Room's ledger). */
    public static Result result(int stock, List<Double> winning) {
        List<Double> w = new ArrayList<>();
        if (winning != null) for (Double d : winning) if (d != null && Double.isFinite(d) && d > 0) w.add(d);
        int sold = Math.min(w.size(), Math.max(1, stock));
        if (w.isEmpty()) return new Result(0, Math.max(1, stock), 0, 0, 0);
        double lo = Double.MAX_VALUE, hi = 0, sum = 0;
        for (double d : w) { lo = Math.min(lo, d); hi = Math.max(hi, d); sum += d; }
        return new Result(sold, Math.max(0, Math.max(1, stock) - sold), lo, hi, sum / w.size());
    }

    /** 1-based place of {@code who} among the active bids, or 0. */
    public static int place(String who, List<Entry> active) {
        List<Entry> sorted = new ArrayList<>(active);
        sorted.sort(RANK);
        for (int i = 0; i < sorted.size(); i++) if (sorted.get(i).who().equals(who)) return i + 1;
        return 0;
    }

    /**
     * Parse {@code "ITEM:stock:minBid,ITEM:stock:minBid"} (min bid accepts 1.5m / 250k). Bad entries are
     * skipped, so a typo in the dial loses one lot, not the auction.
     */
    public static List<Spec> parseItems(String dial) {
        List<Spec> out = new ArrayList<>();
        if (dial == null) return out;
        for (String part : dial.split(",")) {
            String[] f = part.trim().split(":");
            if (f.length < 3 || f[0].isBlank()) continue;
            try {
                int stock = Integer.parseInt(f[1].trim());
                double min = parseCoins(f[2]);
                if (stock > 0 && min > 0) out.add(new Spec(f[0].trim().toUpperCase(Locale.ROOT), stock, min));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static double parseCoins(String v) {
        String t = v.trim().toLowerCase(Locale.ROOT);
        double mult = 1;
        if (t.endsWith("k")) { mult = 1e3; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m")) { mult = 1e6; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("b")) { mult = 1e9; t = t.substring(0, t.length() - 1); }
        return Double.parseDouble(t) * mult;
    }

    /** Real millis left in the SkyBlock year at (month 1-12, day 1-31, hour, minute); a year is 12 x 31 days. */
    public static long millisLeftInYear(int month, int day, int hour, int minute, long millisPerDay) {
        double elapsedDays = (month - 1) * 31 + (day - 1) + (hour * 60 + minute) / 1440.0;
        return Math.max(0, Math.round((372 - elapsedDays) * millisPerDay));
    }

    /** Slots for {@code n} icons centred in the 7 inner columns of the row that starts at {@code rowStart}. */
    public static int[] centered(int rowStart, int n) {
        n = Math.max(0, Math.min(7, n));
        int[] out = new int[n];
        if (n == 0) return out;
        int step = n <= 4 ? 2 : 1;
        int width = (n - 1) * step + 1;
        int first = rowStart + 1 + (7 - width) / 2;
        for (int i = 0; i < n; i++) out[i] = first + i * step;
        return out;
    }
}
