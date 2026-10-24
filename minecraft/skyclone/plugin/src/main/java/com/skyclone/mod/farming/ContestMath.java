package com.skyclone.mod.farming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Harvest Fair maths (research 08 section 1.16, "Jacob's Farming Contest"). Pure.
 *
 * <ul>
 *   <li>Schedule: a contest every {@code period} real millis, starting {@code offset} past the boundary,
 *       lasting {@code duration} (reference: every 3 SkyBlock days = 60 real minutes, 20 minutes long). The same
 *       epoch maths as the core calendar's realtime events, so restarts never move a contest.</li>
 *   <li>Three crops per contest, chosen deterministically from the contest's index.</li>
 *   <li>Brackets by the share of participants at or above you: Bronze top 60%, Silver 30%, Gold 10%,
 *       Platinum 5%, Diamond 2% (GOATed perk: 70/40/20/10/5).</li>
 *   <li>Ghost competitors: NPC farmers whose final scores are drawn from a log-normal around a per-crop median,
 *       so a server with two players still has a field to rank in. Each one farms at its own smooth, drifting
 *       pace ({@link GhostPace}) that reaches its final score exactly at the end, and a contest that is ended early ranks them where they stood at that moment ({@link #progress}).</li>
 * </ul>
 */
public final class ContestMath {

    private ContestMath() {}

    public enum Bracket {
        NONE("&7", "No Medal", 0, 0, 0, 0, 0),
        BRONZE("&c", "BRONZE", 10, 1, 40, 1, 0),
        SILVER("&f", "SILVER", 15, 1, 50, 0, 1),
        GOLD("&6", "GOLD", 25, 2, 60, 0, 0),
        PLATINUM("&3", "PLATINUM", 30, 2, 70, 1, 0),
        DIAMOND("&b", "DIAMOND", 35, 3, 80, 0, 1);

        public final String color, label;
        /** Research 08 1.16 reward table. */
        public final int tickets, carnivalTickets, bits;
        /** Medals awarded: Bronze 1 bronze; Silver 1 silver; Gold 1 gold; Platinum gold + bronze; Diamond gold + silver. */
        public final int extraBronze, extraSilver;

        Bracket(String color, String label, int tickets, int carnival, int bits, int extraBronze, int extraSilver) {
            this.color = color; this.label = label; this.tickets = tickets; this.carnivalTickets = carnival; this.bits = bits;
            this.extraBronze = extraBronze; this.extraSilver = extraSilver;
        }

        public int bronze() { return this == BRONZE ? 1 : extraBronze; }
        public int silver() { return this == SILVER ? 1 : extraSilver; }
        public int gold() { return ordinal() >= GOLD.ordinal() ? 1 : 0; }

        /** At least Gold: counts toward unique golds (the farming level cap). */
        public boolean goldOrBetter() { return ordinal() >= GOLD.ordinal(); }

        public String colored() { return color + "&l" + label; }

        public static Bracket parse(String s) {
            if (s == null) return NONE;
            try { return valueOf(s.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return NONE; }
        }
    }

    // ---- schedule ---------------------------------------------------------------------------------

    /** Index of the contest window containing {@code now} (the window starts at index*period+offset). */
    public static long index(long now, long period, long offset) {
        return Math.floorDiv(now - offset, period);
    }

    public static long startOf(long index, long period, long offset) { return index * period + offset; }

    public static boolean active(long now, long period, long offset, long duration) {
        long phase = Math.floorMod(now - offset, period);
        return phase < duration;
    }

    /** Millis until the next contest starts (0 while one is running). */
    public static long untilNext(long now, long period, long offset, long duration) {
        long phase = Math.floorMod(now - offset, period);
        return phase < duration ? 0 : period - phase;
    }

    /** The three crops of contest {@code index}: a seeded shuffle of the eligible list. */
    public static List<Crop> crops(long index, List<Crop> eligible) {
        List<Crop> pool = new ArrayList<>(eligible);
        Random r = new Random(index * 0x9E3779B97F4A7C15L ^ 0x5DEECE66DL);
        for (int i = pool.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            Crop t = pool.get(i); pool.set(i, pool.get(j)); pool.set(j, t);
        }
        return new ArrayList<>(pool.subList(0, Math.min(3, pool.size())));
    }

    // ---- ghosts -----------------------------------------------------------------------------------

    /**
     * Final scores of {@code n} ghost farmers for one crop of one contest. Log-normal: median
     * {@code median x crop.averageDrops()}, spread {@code sigma} (TUNE dials). Deterministic per (index, crop).
     */
    public static double[] ghostFinals(long index, Crop crop, int n, double median, double sigma) {
        Random r = new Random(index * 31 + crop.ordinal() * 1_000_003L + 7);
        double[] out = new double[Math.max(0, n)];
        double m = median * crop.averageDrops();
        for (int i = 0; i < out.length; i++) out[i] = Math.max(100, Math.round(m * Math.exp(r.nextGaussian() * sigma)));
        Arrays.sort(out);
        return out;
    }

    // Ghost farmers are people on a leaderboard, so no first name here may be a world NPC's or a mayor's
    // ("Bram Barley" read as the Mayor and the blacksmith; wave-4 playtest). Guarded by NpcNamesTest.
    private static final String[] FIRST = {"Ada", "Bettany", "Cora", "Dell", "Edda", "Crispin", "Greta", "Hob", "Ines", "Jolene",
            "Kesta", "Linnet", "Milo", "Gunnar", "Otis", "Hamish", "Silas", "Rosa", "Sven", "Tilda", "Ulla", "Bertil", "Wolcott",
            "Yara", "Zeke", "Marta", "Odo", "Penny", "Rufus", "Saffi"};
    private static final String[] LAST = {"Barley", "Furrow", "Hayward", "Thatch", "Millstone", "Oatley", "Rye", "Sheaf",
            "Tiller", "Wheatley", "Clover", "Fallow", "Grange", "Haybale", "Plowright", "Seedwell"};

    /** A stable display name for ghost {@code i} of a contest/crop. */
    public static String ghostName(long index, Crop crop, int i) {
        Random r = new Random(index * 131 + crop.ordinal() * 7919L + i * 104729L);
        return FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
    }

    /** A ghost's score at contest progress {@code t} in [0,1], at an even pace (no warm-up, no rhythm). */
    public static double ghostAt(double finalScore, double t) {
        return Math.floor(finalScore * Math.max(0, Math.min(1, t)));
    }

    /**
     * How one NPC farmer works through a fair: a short warm-up before the first crop, then a pace that drifts
     * (some start strong and tire, some finish with a sprint) with two slow rhythms on top (breaks, refills, walking
     * back down a row). Deterministic per (contest, crop, ghost), so a restart, the live sidebar and the final
     * ranking all see the same farmer.
     *
     * <p>The pace is always positive (worst case 1 - 0.2 - 0.3 - 0.15 = 0.35 of the average), so the cumulative
     * share {@link #share} rises SMOOTHLY and never goes backwards, and it is exactly 1 at t = 1: a natural end is
     * still the ghost's final score. Playtest: an even-paced field let every ghost sit in the same order all fair
     * and read like a table being revealed rather than a race; with paces the leaderboard moves, and a ghost's
     * score is never a sudden jump.</p>
     */
    public record GhostPace(double delay, double drift, double a1, double f1, double p1, double a2, double f2, double p2) {

        /** Pace relative to the average at u in [0,1] of the farming window (after the warm-up). Always > 0. */
        double rate(double u) {
            return 1 + drift * (u - 0.5) + a1 * Math.sin(2 * Math.PI * f1 * u + p1) + a2 * Math.sin(2 * Math.PI * f2 * u + p2);
        }

        /** Integral of {@link #rate} from 0 to u. */
        double area(double u) {
            return u + drift * (u * u - u) / 2
                    + a1 / (2 * Math.PI * f1) * (Math.cos(p1) - Math.cos(2 * Math.PI * f1 * u + p1))
                    + a2 / (2 * Math.PI * f2) * (Math.cos(p2) - Math.cos(2 * Math.PI * f2 * u + p2));
        }

        /** Share of the final score collected by progress t: 0 during the warm-up, 1 at t = 1, rising in between. */
        public double share(double t) {
            if (t >= 1) return 1;
            if (t <= delay) return 0;
            double u = (t - delay) / (1 - delay);
            double s = area(u) / area(1);
            return Math.max(0, Math.min(1, s));
        }
    }

    /** The pace of every ghost of one contest/crop (index-aligned with {@link #ghostFinals}). */
    public static GhostPace[] ghostPaces(long index, Crop crop, int n) {
        GhostPace[] out = new GhostPace[Math.max(0, n)];
        for (int i = 0; i < out.length; i++) {
            Random r = new Random(index * 6271 + crop.ordinal() * 92821L + i * 15485863L + 11);
            out[i] = new GhostPace(
                    r.nextDouble() * 0.06,                    // 0 - 72 s of a 20-minute fair before the first crop
                    (r.nextDouble() * 2 - 1) * 0.4,           // tiring (-) or finishing strong (+)
                    0.10 + r.nextDouble() * 0.20, 1 + r.nextInt(3), r.nextDouble() * 2 * Math.PI,
                    0.05 + r.nextDouble() * 0.10, 4 + r.nextInt(5), r.nextDouble() * 2 * Math.PI);
        }
        return out;
    }

    /** One ghost's score at progress {@code t}, following its pace. */
    public static double ghostAt(double finalScore, GhostPace pace, double t) {
        if (pace == null) return ghostAt(finalScore, t);
        return Math.floor(finalScore * pace.share(t));
    }

    /**
     * How far through its window a contest is at {@code at}, in [0,1]. The ONE definition of contest time: the live
     * standings and the final ranking both read ghosts through it, so "#23 of 41" on the sidebar is exactly the result
     * the fair would pay if it ended at that moment (a natural end is t = 1, i.e. the ghosts' final scores).
     */
    public static double progress(long at, long start, long end) {
        if (end <= start) return 1;
        return Math.max(0, Math.min(1, (double) (at - start) / (end - start)));
    }

    /** Every ghost's score at progress {@code t} at an even pace (same order as {@code finals}). */
    public static double[] ghostsAt(double[] finals, double t) {
        return ghostsAt(finals, null, t);
    }

    /**
     * Every ghost's score at progress {@code t} (same order as {@code finals}), each following its own pace. The ONE
     * ghost projection: the live sidebar, the leaderboard, an early end and a late ranking all read ghosts here.
     */
    public static double[] ghostsAt(double[] finals, GhostPace[] paces, double t) {
        double[] out = new double[finals.length];
        for (int i = 0; i < finals.length; i++)
            out[i] = ghostAt(finals[i], paces != null && i < paces.length ? paces[i] : null, t);
        return out;
    }

    // ---- ranking ----------------------------------------------------------------------------------

    /** 1-based position of {@code mine} among {@code others} (ties share the better place). */
    public static int position(double mine, double[] others) {
        int above = 0;
        for (double o : others) if (o > mine) above++;
        return above + 1;
    }

    /**
     * Bracket for a position among {@code participants} (you included). {@code goated} widens every cut
     * (research 08 1.16 GOATed column).
     */
    public static Bracket bracket(int position, int participants, boolean goated) {
        if (participants <= 0 || position <= 0) return Bracket.NONE;
        double[] cuts = goated ? new double[]{0.05, 0.10, 0.20, 0.40, 0.70} : new double[]{0.02, 0.05, 0.10, 0.30, 0.60};
        Bracket[] order = {Bracket.DIAMOND, Bracket.PLATINUM, Bracket.GOLD, Bracket.SILVER, Bracket.BRONZE};
        // "Top X%" rounds the number of places UP, so every bracket has at least one place: with the default 40 ghosts
        // a field is 41 and 2% of 41 is 0.82 - a plain position/participants <= 2% would make Diamond unreachable
        // even for first place.
        for (int i = 0; i < cuts.length; i++) if (position <= Math.ceil(cuts[i] * participants - 1e-9)) return order[i];
        return Bracket.NONE;
    }

    /** Smallest score that would reach {@code b} given the other scores (for "Next: GOLD at 4,210" hints). */
    public static double scoreFor(Bracket b, double[] others, boolean goated) {
        double[] sorted = others.clone();
        Arrays.sort(sorted);
        int n = sorted.length + 1;
        // walk upward through the field: being above k others puts you at position n-k
        for (int k = 0; k <= sorted.length; k++) {
            int pos = n - k;
            if (bracket(pos, n, goated).ordinal() >= b.ordinal()) return k == 0 ? 100 : sorted[k - 1] + 1;
        }
        return sorted.length == 0 ? 100 : sorted[sorted.length - 1] + 1;
    }

    /** Participation reward below Bronze (research: "Jacob's Tickets x1-5 (?)" -> 1 per 1,000 collected, 1..5). TUNE. */
    public static int participationTickets(long score) {
        return (int) Math.max(1, Math.min(5, score / 1000));
    }
}
