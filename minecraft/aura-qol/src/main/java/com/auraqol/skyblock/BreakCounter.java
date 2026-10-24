package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Decides which block changes are blocks YOU broke - including Mining Spread.
 * Pure, no Minecraft: the client feeds it events, it answers "count n more".
 *
 * <p><b>Three ways a break reaches the client.</b>
 * <ol>
 *   <li>{@link #clientBreak} - the client destroyed the block itself (Garden
 *       crops, anything instant). Fabric's break event; always counts, once.</li>
 *   <li>{@link #serverChange} at a block you were AIMING at while holding
 *       attack ({@link #aim}) - a mining-island break, which Hypixel performs
 *       server-side. Any change to a different block counts (ore to bedrock,
 *       gemstone to air, mithril tiers stepping down).</li>
 *   <li>{@link #serverChange} somewhere else - Mining Spread (and Pickobulus
 *       style multi-breaks). It counts only if ALL of: the old block was a
 *       solid breakable block (not air, not bedrock, not a plant or liquid);
 *       it became air or an unbreakable block (bedrock is what a mined
 *       Dwarven Mines vein turns into); and it sits within {@code radius} of
 *       one of your own breaks ("anchor") at most {@code windowMs} away in
 *       time, before OR after - the spread blocks often arrive in the same
 *       packet batch as, or even ahead of, the block you were mining, so an
 *       early one waits in {@code pending} for its anchor.</li>
 * </ol>
 *
 * <p><b>Never counted:</b> regeneration (the old block is bedrock or air),
 * someone else's break further than {@code radius} from yours or outside the
 * window, a change while you are not mining, and the same position twice
 * within {@code dedupeMs} (the crosshair poll and the block-update hook see
 * the same break). Client breaks only ANCHOR spread when the block was a
 * plain solid block, so a Garden cactus column falling or a replanted crop
 * never turns into extra counts; and a client break is never itself refused
 * as a duplicate, because each Fabric event is a real, separate break.
 */
public final class BreakCounter {

    /** What a block state is, as far as counting goes. */
    public enum Kind {
        AIR,
        /** Bedrock and friends: destroy speed below zero. */
        UNBREAKABLE,
        /** Ordinary breakable block - what spread can break. */
        SOLID,
        /** Plants, liquids, replaceables: counts only as the block you aimed at. */
        OTHER
    }

    private record Anchor(long t, int x, int y, int z) {
    }

    private record Pending(long t, int x, int y, int z) {
    }

    private final double radiusSq;
    private final long windowMs;
    private final long aimMs;
    private final long dedupeMs;

    private final Map<Long, Long> aimed = new HashMap<>();
    private final Map<Long, Long> counted = new HashMap<>();
    private final List<Anchor> anchors = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private long lastAim = Long.MIN_VALUE / 2;
    private long spreadTotal;

    /**
     * @param radius   how far (Euclidean, blocks) from your own break a spread block may be
     * @param windowMs how far apart in time a spread block and its anchor may be
     * @param aimMs    how long a block counts as "aimed at" after the crosshair leaves it
     * @param dedupeMs one position counts once inside this span
     */
    public BreakCounter(double radius, long windowMs, long aimMs, long dedupeMs) {
        this.radiusSq = radius * radius;
        this.windowMs = windowMs;
        this.aimMs = aimMs;
        this.dedupeMs = dedupeMs;
    }

    /** The values the mod ships with: 4.5 blocks, 400 ms, 1.5 s, 1 s. */
    public static BreakCounter standard() {
        return new BreakCounter(4.5, 400, 1500, 1000);
    }

    /** The crosshair is on this block while attack is held (call every tick). */
    public synchronized void aim(long t, int x, int y, int z) {
        aimed.put(pack(x, y, z), t);
        lastAim = t;
        if (aimed.size() > 64) {
            prune(t);
        }
    }

    /** Forget a position (the client broke it itself; the poll must not see it again). */
    public synchronized void forgetAim(int x, int y, int z) {
        aimed.remove(pack(x, y, z));
    }

    /**
     * The client destroyed a block itself (Fabric's break event).
     *
     * @param kind what the block was; only SOLID anchors spread
     * @return always 1
     */
    public synchronized int clientBreak(long t, int x, int y, int z, Kind kind) {
        prune(t);
        long key = pack(x, y, z);
        aimed.remove(key);
        counted.put(key, t);
        if (kind == Kind.SOLID) {
            return 1 + anchor(t, x, y, z);
        }
        return 1;
    }

    /**
     * Fallback for when no block-update hook is installed: the crosshair poll
     * saw the block it was watching turn into a different block.
     */
    public synchronized int primary(long t, int x, int y, int z) {
        prune(t);
        long key = pack(x, y, z);
        if (isCounted(key, t)) {
            return 0;
        }
        counted.put(key, t);
        return 1 + anchor(t, x, y, z);
    }

    /**
     * The server changed a block the client can see.
     *
     * @param attackHeld whether the attack key is down right now
     * @return how many breaks this change makes countable (0, 1, or more when
     *         it is an anchor that resolves waiting spread blocks)
     */
    public synchronized int serverChange(long t, int x, int y, int z,
                                         String fromBlock, Kind from, String toBlock, Kind to,
                                         boolean attackHeld) {
        prune(t);
        if (from != Kind.SOLID && from != Kind.OTHER) {
            return 0; // air or bedrock becoming something: regeneration, placement
        }
        if (fromBlock != null && fromBlock.equals(toBlock)) {
            return 0; // same block, new state (redstone ore lighting up)
        }
        long key = pack(x, y, z);
        if (isCounted(key, t)) {
            return 0;
        }
        Long aimedAt = aimed.get(key);
        if (aimedAt != null && t - aimedAt <= aimMs) {
            counted.put(key, t);
            return 1 + anchor(t, x, y, z);
        }
        if (from != Kind.SOLID || (to != Kind.AIR && to != Kind.UNBREAKABLE)) {
            return 0;
        }
        for (Anchor a : anchors) {
            if (Math.abs(t - a.t()) <= windowMs && near(a.x(), a.y(), a.z(), x, y, z)) {
                counted.put(key, t);
                spreadTotal++;
                return 1;
            }
        }
        if (attackHeld || t - lastAim <= windowMs) {
            if (pending.size() < 256) {
                pending.add(new Pending(t, x, y, z));
            }
        }
        return 0;
    }

    /** Spread blocks counted since the start (or the last {@link #clear}). */
    public synchronized long spreadTotal() {
        return spreadTotal;
    }

    /** New world: forget positions and timing, keep the session's spread total. */
    public synchronized void forgetWorld() {
        long keep = spreadTotal;
        clear();
        spreadTotal = keep;
    }

    public synchronized void clear() {
        aimed.clear();
        counted.clear();
        anchors.clear();
        pending.clear();
        spreadTotal = 0;
        lastAim = Long.MIN_VALUE / 2;
    }

    // ------------------------------------------------------------ inside

    /** Record your own break and claim the waiting spread blocks around it. */
    private int anchor(long t, int x, int y, int z) {
        anchors.add(new Anchor(t, x, y, z));
        int n = 0;
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (Math.abs(t - p.t()) > windowMs || !near(x, y, z, p.x(), p.y(), p.z())) {
                continue;
            }
            it.remove();
            long key = pack(p.x(), p.y(), p.z());
            if (isCounted(key, t)) {
                continue;
            }
            counted.put(key, t);
            spreadTotal++;
            n++;
        }
        return n;
    }

    private boolean near(int ax, int ay, int az, int bx, int by, int bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz <= radiusSq;
    }

    private boolean isCounted(long key, long t) {
        Long at = counted.get(key);
        return at != null && t - at < dedupeMs;
    }

    private void prune(long t) {
        anchors.removeIf(a -> t - a.t() > windowMs);
        pending.removeIf(p -> t - p.t() > windowMs);
        counted.values().removeIf(at -> t - at >= dedupeMs);
        aimed.values().removeIf(at -> t - at > aimMs);
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}
