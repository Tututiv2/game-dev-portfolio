package com.skyclone.mod.rift;

import java.util.Collection;
import java.util.Map;

/**
 * Pure Rift rules (no Bukkit), so they can be unit-tested and quoted by self-tests.
 */
public final class RiftMath {

    private RiftMath() {}

    // ---- Planar Infusions (research 03 sec 19: 1 free every 4 h, 3 stored) -----------------------

    /** Result of {@link #accrue}: the new stored count and the clock the next one charges from. */
    public record Infusions(int count, long clock) {}

    /**
     * Charges earned since {@code clock}. While the store is full the clock follows "now", so a full
     * store never banks time.
     */
    public static Infusions accrue(int count, long clock, long now, long periodMs, int max) {
        if (periodMs <= 0) return new Infusions(max, now);
        if (count >= max) return new Infusions(max, now);
        if (clock <= 0 || clock > now) return new Infusions(count, now);
        long elapsed = now - clock;
        long gained = elapsed / periodMs;
        if (gained <= 0) return new Infusions(count, clock);
        int n = (int) Math.min(max, count + gained);
        long next = n >= max ? now : clock + gained * periodMs;
        return new Infusions(n, next);
    }

    /** Milliseconds until the next infusion, 0 when the store is full. */
    public static long untilNext(int count, long clock, long now, long periodMs, int max) {
        if (count >= max) return 0;
        long due = clock + periodMs;
        return Math.max(0, due - now);
    }

    // ---- Rift Time ----------------------------------------------------------------------------------

    /**
     * Max Rift Time in seconds: base + timecharms + soul milestones + gear/accessory/slayer bonuses.
     * {@code soulStep} souls give {@code soulBonus} seconds each step.
     */
    public static double maxTime(double base, int charms, double perCharm, int souls, int soulStep, double soulBonus, double gear) {
        double milestones = soulStep <= 0 ? 0 : Math.floor((double) souls / soulStep) * soulBonus;
        return Math.max(10, base + charms * perCharm + milestones + gear);
    }

    /** "7m 32s" as the action bar shows it (research 01: "7m 32sф Left"). */
    public static String clock(double seconds) {
        long s = (long) Math.max(0, Math.ceil(seconds));
        long m = s / 60;
        s %= 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }

    /**
     * Does a player pass an area gate? Gates are research 19.1: every listed Timecharm AND at least
     * {@code gateSeconds} of Rift Time left.
     */
    public static boolean passesGate(RiftCatalog.Area area, Collection<String> charms, double remaining) {
        if (area == null) return true;
        for (String c : area.gateCharms()) if (!charms.contains(c)) return false;
        return remaining >= area.gateSeconds();
    }

    // ---- Hearts --------------------------------------------------------------------------------------

    /**
     * Real HP a Rift hit of {@code hearts} costs. Rift hearts are a fraction of the player's own max HP,
     * so a 10-heart player loses 10% per heart however much HP their main-world gear gives.
     */
    public static double heartDamage(double hearts, double maxHp, double riftHearts) {
        if (riftHearts <= 0 || maxHp <= 0) return 0;
        return hearts * maxHp / riftHearts;
    }

    /** Hearts left shown on the action bar (rounded up, never above the max). */
    public static double heartsLeft(double hp, double maxHp, double riftHearts) {
        if (maxHp <= 0) return 0;
        double v = Math.ceil(hp / maxHp * riftHearts * 2) / 2.0;
        return Math.max(0, Math.min(riftHearts, v));
    }

    // ---- Motes ----------------------------------------------------------------------------------------

    /** Selling {@code amount} of an item worth {@code each} motes. */
    public static long sellValue(Map<String, Integer> sell, String id, int amount) {
        Integer v = sell.get(id);
        return v == null || amount <= 0 ? 0 : (long) v * amount;
    }

    // ---- Maze -----------------------------------------------------------------------------------------

    /**
     * A safe path across the Looking-Glass maze grid: {@code w} columns, {@code d} rows, row 0 is the
     * entry. Returns, per row, the set of safe columns as a bit mask. Each step moves to the next row
     * after drifting sideways 0-2 columns, so the path is connected (4-neighbour) and always ends on the
     * last row. Deterministic for a seed.
     */
    public static long[] mazePath(int w, int d, long seed) {
        long[] rows = new long[d];
        java.util.Random r = new java.util.Random(seed);
        int col = r.nextInt(w);
        for (int row = 0; row < d; row++) {
            rows[row] |= 1L << col;
            if (row == d - 1) break;
            int drift = r.nextInt(3);              // 0, 1 or 2 sideways steps on this row
            int dir = r.nextBoolean() ? 1 : -1;
            for (int k = 0; k < drift; k++) {
                int nc = col + dir;
                if (nc < 0 || nc >= w) { dir = -dir; nc = col + dir; }
                col = nc;
                rows[row] |= 1L << col;
            }
        }
        return rows;
    }

    public static boolean mazeSafe(long[] rows, int row, int col) {
        return row >= 0 && row < rows.length && col >= 0 && col < 63 && (rows[row] & (1L << col)) != 0;
    }

    // ---- Race -----------------------------------------------------------------------------------------

    /** The next checkpoint index (1-based) a racer must touch, given how many were touched in order. */
    public static int nextCheckpoint(int touched, int total) { return touched >= total ? -1 : touched + 1; }

    // ---- Split or Steal (wave 3) ------------------------------------------------------------------

    /**
     * Motes each side of a Split or Steal round wins from a {@code pot}: both split = half each, one
     * steals = the stealer takes the whole pot and the splitter nothing, both steal = nothing for either.
     * Returns {a, b}.
     */
    public static long[] splitSteal(boolean aSteals, boolean bSteals, long pot) {
        pot = Math.max(0, pot);
        if (!aSteals && !bSteals) return new long[]{pot / 2, pot / 2};
        if (aSteals && bSteals) return new long[]{0, 0};
        return aSteals ? new long[]{pot, 0} : new long[]{0, pot};
    }

    /** Milliseconds until the next Split or Steal round is allowed, 0 = ready. */
    public static long splitStealCooldown(long lastMs, long now, long cooldownMs) {
        if (lastMs <= 0 || cooldownMs <= 0 || lastMs > now) return 0;
        return Math.max(0, lastMs + cooldownMs - now);
    }

    // ---- Crux talismans (wave 3) --------------------------------------------------------------------

    /** The crux monsters, in the order the talisman line adds them (tier n tracks the first n; research 06 recipes). */
    public static final java.util.List<String> CRUX_MOBS = java.util.List.of(
            "rift_shy", "rift_shadow", "rift_volt", "rift_scribe", "rift_frozille", "rift_splatter", "rift_puff");
    /** The crux each of those monsters drops, same order. */
    public static final java.util.List<String> CRUX_ITEMS = java.util.List.of(
            "SHAME_CRUX", "SHADOW_CRUX", "VOLT_CRUX", "SCRIBE_CRUX", "FROSTY_CRUX", "SPLATTER_CRUX", "PUFF_CRUX");
    /** Kill milestones per crux monster (research: "Shy: 0/10 kills" is the first; the rest TUNE). */
    public static final int[] CRUX_STEPS = {10, 50, 100, 250, 500, 1000};

    /** Milestones reached for one monster's kill count. */
    public static int cruxMilestones(int kills) {
        int n = 0;
        for (int s : CRUX_STEPS) if (kills >= s) n++;
        return n;
    }

    /**
     * Milestones a crux talisman of {@code tier} (1..7, 0 = none) counts: the sum over the monsters that tier
     * tracks. Each is worth +5 Rift Time and +5 Crux Fortune (research 06).
     */
    public static int cruxMilestones(Map<String, Integer> kills, int tier) {
        int n = 0;
        for (int i = 0; i < Math.min(tier, CRUX_MOBS.size()); i++) {
            Integer k = kills == null ? null : kills.get(CRUX_MOBS.get(i));
            n += cruxMilestones(k == null ? 0 : k);
        }
        return n;
    }

    /** Bonus cruxes from {@code fortune} Crux Fortune and a roll in [0,1): 100 fortune = +1 for sure, 150 = +1 and a 50% second. */
    public static int cruxBonus(double fortune, double roll) {
        if (fortune <= 0) return 0;
        int whole = (int) Math.floor(fortune / 100);
        return whole + (roll < (fortune - whole * 100) / 100 ? 1 : 0);
    }

    // ---- Rift Gallery (wave 3) ----------------------------------------------------------------------

    /**
     * Share of its full power a gallery-scaled necklace gives with {@code secured} of {@code total}
     * Timecharms in the Rift Gallery (research 06: "Becomes more powerful as you fill the Rift Gallery").
     */
    public static double galleryScale(int secured, int total) {
        if (total <= 0) return 1;
        return Math.max(0, Math.min(1, (double) secured / total));
    }
}
