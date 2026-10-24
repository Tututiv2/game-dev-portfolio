package com.skyclone.mod.alchemy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure bookkeeping for one brewing stand ({@link AlchemyData.Stand}): keys, brew time, the pending Alchemy XP
 * that is paid when a potion is TAKEN OUT (research 02 section 2.4.7), migration of the pre-wave-5 personal
 * stand. No Bukkit, so the unit tests run exactly this.
 */
public final class Stands {

    private Stands() {}

    public static final int BOTTLES = 3;

    // ---- keys -----------------------------------------------------------------------------------

    /** "12,70,-3" */
    public static String pos(long x, long y, long z) { return x + "," + y + "," + z; }

    /** A public stand: "hub|12,70,-3". */
    public static String publicKey(String island, long x, long y, long z) { return island + "|" + pos(x, y, z); }

    /** Centre of the instance slot a coordinate lies in (core's grid: round(c / spacing) * spacing). */
    public static long slotOrigin(double coord, int spacing) {
        int sp = Math.max(16, spacing);
        return Math.round(coord / sp) * (long) sp;
    }

    /**
     * Key of a stand on a member's instanced island: "private_island|3,70,-12", the block position relative
     * to the slot origin, so the brew stays with the island whichever grid slot it is loaded into.
     */
    public static String islandKey(String island, long x, long y, long z, int spacing) {
        return island + "|" + pos(x - slotOrigin(x, spacing), y, z - slotOrigin(z, spacing));
    }

    /**
     * Identity of the item in a bottle slot (from its serialised bytes): the XP a potion earned stays with
     * it until a DIFFERENT item (or nothing) is in that slot. Null for an empty slot.
     */
    public static String sig(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        return Integer.toHexString(encoded.hashCode()) + ":" + encoded.length();
    }

    // ---- brew time ------------------------------------------------------------------------------

    /** Brew length: the dial's seconds, made {@code fasterPct}% faster (Witch pet: time / (1 + pct/100)). */
    public static long brewMillis(int dialSeconds, double fasterPct) {
        long base = Math.max(1, dialSeconds) * 1000L;
        return Math.max(1000L, Math.round(base * 100.0 / (100.0 + Math.max(0, fasterPct))));
    }

    /** The length the running brew needs (the value fixed at start, or the dial for an old brew). */
    public static long need(AlchemyData.Stand s, int dialSeconds) {
        return s.brewMillis > 0 ? s.brewMillis : Math.max(1, dialSeconds) * 1000L;
    }

    // ---- slots ----------------------------------------------------------------------------------

    /** Pad the per-bottle lists so index 0..2 always exists. */
    public static void normalise(AlchemyData.Stand s) {
        if (s.slots == null) s.slots = new ArrayList<>();
        if (s.xp == null) s.xp = new ArrayList<>();
        if (s.sig == null) s.sig = new ArrayList<>();
        while (s.xp.size() < BOTTLES) s.xp.add(0.0);
        while (s.sig.size() < BOTTLES) s.sig.add(null);
        for (int i = 0; i < BOTTLES; i++) if (s.xp.get(i) == null || !(s.xp.get(i) >= 0)) s.xp.set(i, 0.0);
    }

    /** A potion was brewed into bottle slot {@code i}: its XP waits there until someone takes it. */
    public static void store(AlchemyData.Stand s, int i, double xp, String sig) {
        normalise(s);
        if (i < 0 || i >= BOTTLES) return;
        s.xp.set(i, s.xp.get(i) + Math.max(0, xp));
        s.sig.set(i, sig);
    }

    /**
     * Pay out every bottle slot whose item is no longer the one that earned the XP (taken, swapped or
     * emptied). {@code current[i]} = signature of what is in bottle slot i now (null = empty). Returns the
     * XP collected; the slots it came from are cleared, so the same XP is never paid twice.
     */
    public static double collect(AlchemyData.Stand s, String[] current) {
        normalise(s);
        double sum = 0;
        for (int i = 0; i < BOTTLES; i++) {
            String now = current != null && i < current.length ? current[i] : null;
            if (Objects.equals(s.sig.get(i), now)) continue;
            sum += s.xp.get(i);
            s.xp.set(i, 0.0);
            s.sig.set(i, null);
        }
        return sum;
    }

    /** All XP still waiting (the stand's status line, a broken stand). */
    public static double pending(AlchemyData.Stand s) {
        if (s == null || s.xp == null) return 0;
        double t = 0;
        for (Double d : s.xp) if (d != null && d > 0) t += d;
        return t;
    }

    /** Nothing in it, nothing brewing, nothing owed: safe to forget. */
    public static boolean idle(AlchemyData.Stand s) {
        if (s == null) return true;
        if (s.brewStart > 0 || pending(s) > 0) return false;
        if (s.slots != null) for (String x : s.slots) if (x != null && !x.isEmpty()) return false;
        return true;
    }

    /** Clear the running brew. */
    public static void stop(AlchemyData.Stand s) {
        s.brewStart = 0;
        s.brewIngredient = null;
        s.brewMillis = 0;
        s.brewer = null;
        s.brewerPct = 0;
    }

    /** The pre-wave-5 personal stand as a {@link AlchemyData.Stand} (the legacy fields are then cleared). */
    public static AlchemyData.Stand portable(AlchemyData d) {
        if (d.portable != null) { normalise(d.portable); return d.portable; }
        AlchemyData.Stand s = new AlchemyData.Stand();
        if (d.stand != null) s.slots = new ArrayList<>(d.stand);
        s.brewStart = d.brewStart;
        s.brewIngredient = d.brewIngredient;
        normalise(s);
        d.portable = s;
        d.stand = new ArrayList<>();
        d.brewStart = 0;
        d.brewIngredient = null;
        return s;
    }

    /** Copy of the slot list padded to 4 entries. */
    public static List<String> slots(AlchemyData.Stand s) {
        List<String> out = new ArrayList<>(s.slots == null ? List.of() : s.slots);
        while (out.size() < 1 + BOTTLES) out.add("");
        return out;
    }
}
