package com.skyclone.mod.mayors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The election calendar and every random roll, as pure functions (research/05 §8.1-8.5).
 *
 * <pre>
 *   Voting opens  Late Summer 27 (month 6, day 27) of year E   -> "election E"
 *   Voting closes Late Spring 27 (month 3, day 27) of year E+1 -> the winner governs "term E+1"
 *   Term T runs from Late Spring 27 of year T to Late Spring 27 of year T+1 (one SB year = 124 real h)
 *   Voting window: 279 SB days = 93 real hours.
 *   A special mayor takes one of the five slots when E is a multiple of 8, rotating
 *   Scorpius -> Derpy -> Jerry by (E / 8) mod 3 (so 496 = Jerry, 504 = Scorpius, 512 = Derpy).
 * </pre>
 *
 * Every roll is seeded by (seed, election) so a restart, a second server or a unit test gets the
 * same ballot for the same year.
 */
public final class ElectionMath {

    private ElectionMath() {}

    public static final int DAYS_PER_MONTH = 31;
    public static final int DAYS_PER_YEAR = 372;
    public static final int CANDIDATES = 5;

    /** 0-based day of the year. */
    public static int doy(int month, int day) { return (month - 1) * DAYS_PER_MONTH + (day - 1); }

    public static final int CLOSE_DOY = doy(3, 27);
    public static final int OPEN_DOY = doy(6, 27);

    /** Length of the voting window in SB days (279 = 93 real hours). */
    public static int votingDays() { return DAYS_PER_YEAR - OPEN_DOY + CLOSE_DOY; }

    /** The term governing on this date. */
    public static int termOf(int year, int month, int day) {
        return doy(month, day) >= CLOSE_DOY ? year : year - 1;
    }

    /** The election whose booth is open on this date, or -1 between Late Spring 27 and Late Summer 27. */
    public static int openElection(int year, int month, int day) {
        int d = doy(month, day);
        if (d >= OPEN_DOY) return year;
        if (d < CLOSE_DOY) return year - 1;
        return -1;
    }

    /** SB days since the current term began (0 on Late Spring 27). */
    public static int daysIntoTerm(int year, int month, int day) {
        int d = doy(month, day);
        return d >= CLOSE_DOY ? d - CLOSE_DOY : d + DAYS_PER_YEAR - CLOSE_DOY;
    }

    /** SB days until voting closes, given we are inside the window. */
    public static int daysUntilClose(int year, int month, int day) {
        int d = doy(month, day);
        return d < CLOSE_DOY ? CLOSE_DOY - d : DAYS_PER_YEAR - d + CLOSE_DOY;
    }

    /** SB days until the booth opens, given we are outside the window. */
    public static int daysUntilOpen(int year, int month, int day) {
        int d = doy(month, day);
        return d <= OPEN_DOY ? OPEN_DOY - d : DAYS_PER_YEAR - d + OPEN_DOY;
    }

    /** The special mayor id on the ballot of election E, or null. */
    public static String specialFor(int election, int every, List<String> rotation) {
        if (every <= 0 || election <= 0 || rotation == null || rotation.isEmpty()) return null;
        if (election % every != 0) return null;
        return rotation.get((election / every) % rotation.size());
    }

    /** One candidate on a ballot. Plain class so Gson can persist it. */
    public static final class Candidate {
        public String mayor;
        public List<String> perks = new ArrayList<>();
        public String ministerPerk;
        public boolean special;

        public Candidate() {}

        Candidate(String mayor, List<String> perks, String ministerPerk, boolean special) {
            this.mayor = mayor; this.perks = perks; this.ministerPerk = ministerPerk; this.special = special;
        }
    }

    static Random rng(long seed, int election, long salt) {
        return new Random(seed * 1_000_003L + election * 7_919L + salt);
    }

    /**
     * Roll the five candidates of election {@code election}: the special mayor when due, then regular
     * mayors drawn at random excluding {@code exclude} (the sitting mayor and minister). A regular
     * candidate runs with a random 1..4 of their perks (research §8.2), exactly one of which is flagged
     * as the minister perk. {@code carry} (Long Term Investment) is put on the ballot with every perk.
     */
    public static List<Candidate> rollCandidates(MayorRoster roster, int election, long seed, Set<String> exclude,
                                                 String carry, int specialEvery, boolean includeRetired) {
        Random r = rng(seed, election, 11);
        List<Candidate> out = new ArrayList<>();
        String special = specialFor(election, specialEvery, roster.specialRotation);
        List<MayorRoster.Mayor> pool = new ArrayList<>(roster.regulars(includeRetired));
        pool.removeIf(m -> exclude.contains(m.id));
        Collections.shuffle(pool, r);
        MayorRoster.Mayor carried = carry == null ? null : roster.mayor(carry);
        if (carried != null && !carried.special) {
            pool.removeIf(m -> m.id.equals(carried.id));
            List<String> all = carried.perkIds();
            out.add(new Candidate(carried.id, all, pickMinister(carried, all, r), false));
        }
        int regulars = CANDIDATES - (special != null ? 1 : 0);
        for (MayorRoster.Mayor m : pool) {
            if (out.size() >= regulars) break;
            List<MayorRoster.Perk> perks = new ArrayList<>(m.perks);
            Collections.shuffle(perks, r);
            int n = 1 + r.nextInt(Math.min(4, perks.size()));
            List<String> chosen = new ArrayList<>();
            for (MayorRoster.Perk p : perks.subList(0, n)) chosen.add(p.id);
            ensureMinisterPerk(m, chosen, r);
            // keep the roster's order on the ballot so lore reads the same every year
            chosen.sort(Comparator.comparingInt(id -> m.perkIds().indexOf(id)));
            out.add(new Candidate(m.id, chosen, pickMinister(m, chosen, r), false));
        }
        if (special != null) {
            MayorRoster.Mayor s = roster.mayor(special);
            if (s != null) out.add(new Candidate(s.id, s.perkIds(), null, true));
        }
        return out;
    }

    /**
     * The reference ballot always carries the candidate's minister perk (election.json: a one-perk candidate's
     * only perk is always the flagged one). When the roll left every minister-eligible perk off, one of the
     * rolled perks is swapped for a random eligible one, so a mayor-only perk (the Mythological Ritual) can
     * never become the minister perk by default.
     */
    static void ensureMinisterPerk(MayorRoster.Mayor m, List<String> chosen, Random r) {
        List<String> eligible = new ArrayList<>();
        for (MayorRoster.Perk p : m.perks) if (p.ministerEligible) eligible.add(p.id);
        if (eligible.isEmpty() || chosen.isEmpty()) return;
        for (String id : chosen) if (eligible.contains(id)) return;
        chosen.set(r.nextInt(chosen.size()), eligible.get(r.nextInt(eligible.size())));
    }

    private static String pickMinister(MayorRoster.Mayor m, List<String> onBallot, Random r) {
        List<String> eligible = new ArrayList<>();
        for (String id : onBallot) {
            for (MayorRoster.Perk p : m.perks) if (p.id.equals(id) && p.ministerEligible) eligible.add(id);
        }
        return eligible.isEmpty() ? null : eligible.get(r.nextInt(eligible.size()));
    }

    // ---- Foxy's Extra Event -------------------------------------------------------------------

    /** The festivals Foxy's Extra Event can schedule (NEU FOXY_MAYOR_MONSTER lore: Fishing / Mining / Spooky). */
    public static final List<String> EXTRA_EVENT_VARIANTS = List.of("fishing", "mining", "spooky");

    /**
     * Which extra festival the Extra Event of election {@code election} schedules. The reference rolls it per ballot
     * (election.json Y517/Y518 both read "an extra Fishing Festival"); ours is a seeded roll per election so the
     * ballot, the office and a restart agree. {@code override} = a variant name pins it (dial), anything else rolls.
     */
    public static String extraEventVariant(long seed, int election, String override) {
        String o = override == null ? "" : override.trim().toLowerCase(java.util.Locale.ROOT);
        if (EXTRA_EVENT_VARIANTS.contains(o)) return o;
        return EXTRA_EVENT_VARIANTS.get(rng(seed, election, 47).nextInt(EXTRA_EVENT_VARIANTS.size()));
    }

    /** Is (month, day) inside the {@code days}-long window that starts on (startMonth, startDay)? Wraps the year end. */
    public static boolean inWindow(int month, int day, int startMonth, int startDay, int days) {
        int sm = Math.max(1, Math.min(12, startMonth));
        int sd = Math.max(1, Math.min(DAYS_PER_MONTH, startDay));
        int dd = Math.floorMod(doy(month, day) - doy(sm, sd), DAYS_PER_YEAR);
        return dd < Math.max(0, days);
    }

    /**
     * Rank candidates by votes, most first. Ties are broken by a seeded shuffle (research: tie rule
     * unknown; ours is deterministic per election so a recount gives the same answer).
     */
    public static List<String> rank(Map<String, Long> votes, List<String> candidates, long seed, int election) {
        List<String> order = new ArrayList<>(candidates);
        Collections.shuffle(order, rng(seed, election, 29));
        Map<String, Integer> tieBreak = new HashMap<>();
        for (int i = 0; i < order.size(); i++) tieBreak.put(order.get(i), i);
        order.sort((a, b) -> {
            int c = Long.compare(votes.getOrDefault(b, 0L), votes.getOrDefault(a, 0L));
            return c != 0 ? c : Integer.compare(tieBreak.get(a), tieBreak.get(b));
        });
        return order;
    }

    /**
     * Townsfolk votes so a small server's booth shows living percentages: {@code total} votes split
     * at random between candidates (a special candidate gets a bigger share: research notes no
     * special mayor has ever lost). TUNE: total is a dial.
     */
    public static Map<String, Long> npcVotes(List<Candidate> cands, int total, long seed, int election) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (cands.isEmpty() || total <= 0) { for (Candidate c : cands) out.put(c.mayor, 0L); return out; }
        Random r = rng(seed, election, 43);
        double[] w = new double[cands.size()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = 0.3 + r.nextDouble() * (cands.get(i).special ? 3.0 : 1.0);
            sum += w[i];
        }
        long given = 0;
        for (int i = 0; i < w.length; i++) {
            long v = (long) Math.floor(total * w[i] / sum);
            out.put(cands.get(i).mayor, v);
            given += v;
        }
        // remainder to the strongest
        int best = 0;
        for (int i = 1; i < w.length; i++) if (w[i] > w[best]) best = i;
        out.merge(cands.get(best).mayor, total - given, Long::sum);
        return out;
    }

    /**
     * Perk Parade (Jerry's Perkpocalypse): which regular mayor's full perk set is on during this
     * 18-day slot of the term. The order is a seeded shuffle of the eligible mayors (no specials, no
     * retired, nothing in {@code exclude} — research: Diaz is excluded).
     */
    public static String perkpocalypseMayor(MayorRoster roster, int term, int daysIntoTerm, int periodDays, long seed) {
        List<String> eligible = new ArrayList<>();
        for (MayorRoster.Mayor m : roster.regulars(false))
            if (!roster.perkpocalypseExclude.contains(m.id)) eligible.add(m.id);
        if (eligible.isEmpty()) return null;
        Collections.shuffle(eligible, rng(seed, term, 61));
        int slot = Math.max(0, daysIntoTerm) / Math.max(1, periodDays);
        return eligible.get(slot % eligible.size());
    }
}
