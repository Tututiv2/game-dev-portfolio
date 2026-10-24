package com.skyclone.mod.mayors;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * One round of Zombie Shootout, pure (no Bukkit), after the reference wiki: 100 seconds standing still with the Dart
 * Tube while zombies walk back and forth on two platforms. Leather 30 points (7 s on the platform; one in ten is a
 * fast baby worth 60), Iron 50 (6 s), Gold 80 (5 s), Diamond 120 (4 s). Redstone lamps light up now and then: a hit
 * is 100 points and x1.2 points for the next 6 seconds. Accuracy = hits / darts fired.
 * Spawn pacing, walking speed and how often lamps light are not published: they are TUNE constants below.
 */
final class ShootRound {

    enum Kind {
        LEATHER(30, 7000, 45), IRON(50, 6000, 25), GOLD(80, 5000, 18), DIAMOND(120, 4000, 12);
        final int points, lifeMs, weight;
        Kind(int points, int lifeMs, int weight) { this.points = points; this.lifeMs = lifeMs; this.weight = weight; }
    }

    // ---- TUNE ----
    static final int MAX_UP = 5;
    static final long SPAWN_MIN_MS = 800, SPAWN_JITTER_MS = 600;
    static final double WALK = 1.4, BABY_WALK = 2.6;      // blocks per second
    static final double BABY_CHANCE = 0.10;
    static final long LAMP_ON_MS = 3000, LAMP_GAP_MS = 7000, LAMP_JITTER_MS = 6000;
    static final int LAMP_POINTS = 100;
    static final long BOOST_MS = 6000;
    static final double BOOST = 1.2;

    static final class Target {
        final int id;
        final Kind kind;
        final boolean baby;
        final int lane;
        double pos;            // 0..1 along the lane
        double dir;            // +1 or -1
        final long born, until;
        Target(int id, Kind kind, boolean baby, int lane, double pos, double dir, long born) {
            this.id = id; this.kind = kind; this.baby = baby; this.lane = lane; this.pos = pos; this.dir = dir;
            this.born = born; this.until = born + kind.lifeMs;
        }
        int points() { return kind.points * (baby ? 2 : 1); }
        /** 1 when it just appeared, 0 when it is about to leave. */
        double life(long now) { return Math.max(0, Math.min(1, (until - now) / (double) kind.lifeMs)); }
    }

    final List<Target> targets = new ArrayList<>();
    final List<Target> expired = new ArrayList<>();
    final long start, end;
    final int lanes, lamps;
    final double[] laneLength;
    final Random rnd;
    long last, nextSpawn, nextLamp, lampUntil, boostUntil;
    int lit = -1;
    int nextId = 1;
    int score, kills, diamonds, lampHits, shots, hits;
    boolean over, paid;

    ShootRound(long now, long seconds, int lanes, double[] laneLength, int lamps, long seed) {
        this.start = now; this.end = now + seconds * 1000; this.lanes = Math.max(1, lanes); this.laneLength = laneLength;
        this.lamps = lamps; this.rnd = new Random(seed); this.last = now; this.nextSpawn = now + 1500;
        this.nextLamp = now + 5000 + rnd.nextInt(5000);
    }

    long left(long now) { return Math.max(0, end - now); }

    double accuracy() { return shots == 0 ? 0 : hits * 100.0 / shots; }

    boolean boosted(long now) { return now < boostUntil; }

    Target target(int id) {
        for (Target t : targets) if (t.id == id) return t;
        return null;
    }

    /** Advance to {@code now}: walk, retire zombies whose time is up (into {@link #expired}), spawn, light lamps. */
    void step(long now) {
        if (over) return;
        if (now >= end) { over = true; expired.addAll(targets); targets.clear(); lit = -1; return; }
        double dt = Math.max(0, (now - last) / 1000.0);
        last = now;
        for (Iterator<Target> it = targets.iterator(); it.hasNext(); ) {
            Target t = it.next();
            if (now >= t.until) { expired.add(t); it.remove(); continue; }
            double len = Math.max(1, laneLength[Math.min(t.lane, laneLength.length - 1)]);
            t.pos += t.dir * (t.baby ? BABY_WALK : WALK) * dt / len;
            if (t.pos >= 1) { t.pos = 1; t.dir = -1; }
            if (t.pos <= 0) { t.pos = 0; t.dir = 1; }
        }
        while (now >= nextSpawn && targets.size() < MAX_UP) {
            Kind k = pick();
            boolean baby = k == Kind.LEATHER && rnd.nextDouble() < BABY_CHANCE;
            targets.add(new Target(nextId++, k, baby, rnd.nextInt(lanes), rnd.nextDouble(), rnd.nextBoolean() ? 1 : -1, now));
            nextSpawn = now + SPAWN_MIN_MS + rnd.nextInt((int) SPAWN_JITTER_MS);
        }
        if (now >= nextSpawn) nextSpawn = now + 200;   // full: try again shortly
        if (lit >= 0 && now >= lampUntil) { lit = -1; nextLamp = now + LAMP_GAP_MS + rnd.nextInt((int) LAMP_JITTER_MS); }
        if (lit < 0 && lamps > 0 && now >= nextLamp) { lit = rnd.nextInt(lamps); lampUntil = now + LAMP_ON_MS; }
    }

    private Kind pick() {
        int total = 0;
        for (Kind k : Kind.values()) total += k.weight;
        int r = rnd.nextInt(total);
        for (Kind k : Kind.values()) { r -= k.weight; if (r < 0) return k; }
        return Kind.LEATHER;
    }

    /** A dart was fired (count it before resolving what it hit). */
    void fired() { if (!over) shots++; }

    /** The dart hit zombie {@code id}: the points it scored (0 if gone). */
    int hitZombie(int id, long now) {
        if (over) return 0;
        Target t = target(id);
        if (t == null) return 0;
        targets.remove(t);
        int pts = (int) Math.round(t.points() * (boosted(now) ? BOOST : 1.0));
        hits++;
        kills++;
        if (t.kind == Kind.DIAMOND) diamonds++;
        score += pts;
        return pts;
    }

    /** The dart hit lamp {@code index}: 100 and the x1.2 window if it was the lit one, else 0. */
    int hitLamp(int index, long now) {
        if (over || index != lit || lit < 0) return 0;
        lit = -1;
        nextLamp = now + LAMP_GAP_MS + rnd.nextInt((int) LAMP_JITTER_MS);
        hits++;
        lampHits++;
        boostUntil = now + BOOST_MS;
        score += LAMP_POINTS;
        return LAMP_POINTS;
    }
}
