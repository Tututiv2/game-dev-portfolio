package com.skyclone.mod.extras;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wall-clock rotations for Fire Sales and City Projects (pure). Both are keyed off a fixed epoch so every
 * server restart and every player sees the same cycle without storing anything.
 */
final class Rotation {

    private Rotation() {}

    /** 2026-10-05 00:00 UTC, a Monday: cycle 0 starts here. */
    static final long EPOCH_MS = 1_791_158_400_000L;

    static long cycle(long nowMs, double cycleHours) {
        long period = periodMs(cycleHours);
        return Math.floorDiv(nowMs - EPOCH_MS, period);
    }

    static long cycleStart(long cycle, double cycleHours) { return EPOCH_MS + cycle * periodMs(cycleHours); }

    static long periodMs(double hours) { return Math.max(60_000L, (long) (hours * 3_600_000L)); }

    /** True inside the first {@code openHours} of the current cycle (a sale window). */
    static boolean open(long nowMs, double cycleHours, double openHours) {
        long c = cycle(nowMs, cycleHours);
        return nowMs - cycleStart(c, cycleHours) < periodMs(Math.min(openHours, cycleHours));
    }

    /**
     * {@code count} distinct picks out of {@code n} for a cycle. The catalogue is walked in one fixed shuffled
     * order, {@code count} per cycle, so nothing repeats until everything has been on sale once.
     */
    static List<Integer> picks(long cycle, int n, int count) {
        List<Integer> out = new ArrayList<>();
        if (n <= 0 || count <= 0) return out;
        int[] order = order(n);
        long start = Math.floorMod(cycle * (long) Math.min(count, n), n);
        for (int i = 0; i < Math.min(count, n); i++) out.add(order[(int) ((start + i) % n)]);
        return out;
    }

    static int[] order(int n) {
        int[] o = new int[n];
        for (int i = 0; i < n; i++) o[i] = i;
        Random r = new Random(0x5EED_F12EL);
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = o[i]; o[i] = o[j]; o[j] = t;
        }
        return o;
    }

    /** City projects: {@code perCycle} of {@code n} projects run each cycle, walking the list in order. */
    static List<Integer> activeProjects(long cycle, int n, int perCycle) {
        List<Integer> out = new ArrayList<>();
        if (n <= 0) return out;
        int k = Math.max(1, Math.min(perCycle, n));
        long start = Math.floorMod(cycle * (long) k, n);
        for (int i = 0; i < k; i++) out.add((int) ((start + i) % n));
        return out;
    }
}
