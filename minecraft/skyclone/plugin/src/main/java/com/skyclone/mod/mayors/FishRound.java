package com.skyclone.mod.mayors;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * One round of Catch a Fish, pure (no Bukkit), after the reference wiki: 100 seconds on the dock with the Carnival
 * Rod. Red fish 50 points (60%), Blue 100 (30%), Yellow 200 (10%). Hook one, then HOLD right-click to reel until its
 * bar fills - red fills fastest, yellow slowest. Every catch in a row adds 5 bonus points (up to +50 a catch), and any
 * catch has a 10% chance to count double. The streak breaks when a fish gets away or after 3 seconds without one on
 * the hook. How fast the bar fills (pulls per fish), how many swim at once and how long they stay are TUNE.
 */
final class FishRound {

    enum Kind {
        RED(50, 60, 5, "&c"), BLUE(100, 30, 8, "&9"), YELLOW(200, 10, 12, "&e");
        final int points, weight, pulls;
        final String color;
        Kind(int points, int weight, int pulls, String color) { this.points = points; this.weight = weight; this.pulls = pulls; this.color = color; }
    }

    // ---- TUNE ----
    static final int MAX_FISH = 6;
    static final long SPAWN_MIN_MS = 700, SPAWN_JITTER_MS = 900;
    static final long LIFE_MIN_MS = 9000, LIFE_JITTER_MS = 5000;
    /** A hooked fish slips off when you stop reeling for this long. */
    static final long ESCAPE_MS = 1500;
    /** Reference: the streak resets after 3 seconds without hooking a fish. */
    static final long STREAK_IDLE_MS = 3000;
    static final int STREAK_STEP = 5, STREAK_CAP = 50;
    static final double DOUBLE_CHANCE = 0.10;

    static final class Fish {
        final int id;
        final Kind kind;
        final long until;
        int pulls;
        boolean hooked;
        long lastPull;
        Fish(int id, Kind kind, long until) { this.id = id; this.kind = kind; this.until = until; }
        double progress() { return Math.min(1, pulls / (double) kind.pulls); }
    }

    /** What one pull did. {@code caught} with the points scored, else the progress so far. */
    record Pull(boolean caught, int points, boolean doubled, double progress) {}

    final List<Fish> fish = new ArrayList<>();
    final List<Fish> gone = new ArrayList<>();
    final long start, end;
    final Random rnd;
    long nextSpawn, lastHook;
    int nextId = 1;
    int score, caught, yellows, streak, bestStreak, escaped;
    boolean over, paid;

    FishRound(long now, long seconds, long seed) {
        start = now; end = now + seconds * 1000; rnd = new Random(seed); nextSpawn = now + 600; lastHook = now;
    }

    long left(long now) { return Math.max(0, end - now); }

    Fish get(int id) {
        for (Fish f : fish) if (f.id == id) return f;
        return null;
    }

    Fish hooked() {
        for (Fish f : fish) if (f.hooked) return f;
        return null;
    }

    /** Advance to {@code now}: fish that leave go to {@link #gone}; a fish left unreeled escapes; new fish appear. */
    void step(long now) {
        if (over) return;
        if (now >= end) { over = true; gone.addAll(fish); fish.clear(); return; }
        for (Iterator<Fish> it = fish.iterator(); it.hasNext(); ) {
            Fish f = it.next();
            if (f.hooked && now - f.lastPull > ESCAPE_MS) { f.hooked = false; f.pulls = 0; escaped++; streak = 0; }
            if (!f.hooked && now >= f.until) { gone.add(f); it.remove(); }
        }
        if (streak > 0 && hooked() == null && now - lastHook > STREAK_IDLE_MS) streak = 0;
        while (now >= nextSpawn && fish.size() < MAX_FISH) {
            fish.add(new Fish(nextId++, pick(), now + LIFE_MIN_MS + rnd.nextInt((int) LIFE_JITTER_MS)));
            nextSpawn = now + SPAWN_MIN_MS + rnd.nextInt((int) SPAWN_JITTER_MS);
        }
        if (now >= nextSpawn) nextSpawn = now + 250;
    }

    private Kind pick() {
        int r = rnd.nextInt(100);
        for (Kind k : Kind.values()) { r -= k.weight; if (r < 0) return k; }
        return Kind.RED;
    }

    /** The hook caught fish {@code id}. False when it is gone or something else is already on the line. */
    boolean hook(int id, long now) {
        if (over) return false;
        Fish f = get(id);
        if (f == null || f.hooked || hooked() != null) return false;
        f.hooked = true;
        f.pulls = 0;
        f.lastPull = now;
        lastHook = now;
        return true;
    }

    /** One reel tick on the hooked fish (holding right-click sends one about every 4 game ticks). */
    Pull pull(long now) {
        Fish f = hooked();
        if (over || f == null) return null;
        f.pulls++;
        f.lastPull = now;
        lastHook = now;
        if (f.pulls < f.kind.pulls) return new Pull(false, 0, false, f.progress());
        fish.remove(f);
        gone.add(f);
        caught++;
        if (f.kind == Kind.YELLOW) yellows++;
        streak++;
        bestStreak = Math.max(bestStreak, streak);
        int pts = f.kind.points + Math.min(STREAK_CAP, STREAK_STEP * (streak - 1));
        boolean dbl = rnd.nextDouble() < DOUBLE_CHANCE;
        if (dbl) pts *= 2;
        score += pts;
        return new Pull(true, pts, dbl, 1);
    }

    /** The line was reeled in or broke with a fish on it: it gets away. */
    void lose() {
        Fish f = hooked();
        if (f == null) return;
        f.hooked = false;
        f.pulls = 0;
        escaped++;
        streak = 0;
    }
}
