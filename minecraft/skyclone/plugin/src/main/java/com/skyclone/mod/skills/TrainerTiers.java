package com.skyclone.mod.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure numbers for the wave-5 additions (no Bukkit, so the unit tests run them): the Pet Trainer's
 * sessions, session timing, the Runebook's digit count and the NPC spot search.
 *
 * <p>The Taming road unlocks one session type every 10 levels (research 02 §2.4: Free at 10, Light 20,
 * Moderate 30, Expert 40, Ultra 50, Turbo! 60). The research has NO costs, XP amounts or durations for
 * them, only that the Turbo session costs 500 Bits (research 05 bits shop), so every number below is our
 * design // TUNE (each is also scaled by a dial). Coins buy pet XP more cheaply the higher the session,
 * which is what makes reaching the next Taming tier worth something.
 */
public final class TrainerTiers {

    private TrainerTiers() {}

    /** One session type. {@code taming} = Taming level needed; {@code minutes} = real time to finish. */
    public record Tier(String id, String name, int taming, double coins, long bits, double xp, int minutes, String icon) {
        public double coinsPerXp() { return xp <= 0 ? 0 : coins / xp; }
    }

    public static final List<Tier> TIERS = List.of(
            new Tier("free", "Free", 10, 0, 0, 10_000, 60, "WHEAT"),                              // TUNE
            new Tier("light", "Light", 20, 50_000, 0, 100_000, 120, "BONE"),                      // TUNE
            new Tier("moderate", "Moderate", 30, 200_000, 0, 500_000, 240, "COOKED_BEEF"),         // TUNE
            new Tier("expert", "Expert", 40, 750_000, 0, 2_000_000, 480, "GOLDEN_CARROT"),         // TUNE
            new Tier("ultra", "Ultra", 50, 2_000_000, 0, 6_000_000, 960, "GOLDEN_APPLE"),          // TUNE
            new Tier("turbo", "Turbo!", 60, 2_000_000, 500, 6_000_000, 120, "ENCHANTED_GOLDEN_APPLE")); // 500 bits: research 05

    public static Tier byId(String id) {
        if (id == null) return null;
        String k = id.toLowerCase(Locale.ROOT);
        for (Tier t : TIERS) if (t.id.equals(k)) return t;
        return null;
    }

    /** Fraction 0..1 of a session that runs from {@code start} to {@code end} (millis) at {@code now}. */
    public static double progress(long start, long end, long now) {
        if (end <= start) return 1;
        if (now <= start) return 0;
        if (now >= end) return 1;
        return (double) (now - start) / (double) (end - start);
    }

    /** Millis left, never negative. */
    public static long remaining(long end, long now) { return Math.max(0, end - now); }

    /** Session length in millis after the time dial. */
    public static long durationMillis(Tier t, double timeScale) {
        double s = Double.isNaN(timeScale) || timeScale < 0 ? 1 : timeScale;
        return Math.max(1000L, Math.round(t.minutes * 60_000.0 * s));
    }

    /** Decimal digits of a non-negative count (the Runebook: 1-9 kills = 1, 10-99 = 2...); 0 for 0. */
    public static int digits(long n) {
        if (n <= 0) return 0;
        return (int) Math.floor(Math.log10((double) n)) + 1;
    }

    /** Whether a block offset is a place an NPC can stand (asked by {@link #findSpot}). */
    public interface Standable { boolean ok(int dx, int dy, int dz); }

    /**
     * Search for a spot near an anchor: rings from {@code minR} to {@code maxR} blocks out (nearest first,
     * ties in a fixed order so a restart picks the same spot), each column tried at dy 0, +1, -1, +2, -2.
     * Returns {dx, dy, dz} or null.
     */
    public static int[] findSpot(int minR, int maxR, Standable s) {
        for (int[] o : ring(minR, maxR)) {
            for (int dy : new int[]{0, 1, -1, 2, -2}) if (s.ok(o[0], dy, o[1])) return new int[]{o[0], dy, o[1]};
        }
        return null;
    }

    /** Column offsets with minR <= distance <= maxR, nearest first. */
    static List<int[]> ring(int minR, int maxR) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -maxR; dx <= maxR; dx++)
            for (int dz = -maxR; dz <= maxR; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 >= minR * minR && d2 <= maxR * maxR) out.add(new int[]{dx, dz});
            }
        out.sort((a, b) -> {
            int c = Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]);
            if (c != 0) return c;
            c = Integer.compare(a[0], b[0]);
            return c != 0 ? c : Integer.compare(a[1], b[1]);
        });
        return out;
    }
}
