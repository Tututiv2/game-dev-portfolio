package com.skyclone.mod.armor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived, per-player bonus state that is deliberately NOT persisted (charges, stacks,
 * cooldowns). Everything that must survive a relog lives on the item (ItemData.extra) instead.
 * Main thread only.
 */
final class ArmorState {

    static final class P {
        /** Thunder: millis of the last discharge or of putting the set on. */
        long thunderSince = System.currentTimeMillis();
        /** Werewolf / Strong Blood stacks: expiry millis of each stack. */
        final Deque<Long> howl = new ArrayDeque<>();
        final Deque<Long> strong = new ArrayDeque<>();
        /** Bone Shield bones and when the next one regrows. */
        int bones = -1;
        long nextBone;
        long blockDamageReady;
        long detonateReady;
        long growthReady;
        long zombieHealUntil;
        long unstableNext;
        /** Spring boots: when sneaking started (0 = not charging). */
        long springSince;
        boolean doubleJumpUsed;
        /** Goblin conversion: Intelligence removed on the previous stat pass (for the live readout). */
        double goblinRemoved;
        /** Rekindle: millis the wearer caught fire (0 = not burning). */
        long burningSince;
        /** Breath: we raised the player's maximum air and must put it back. */
        boolean airRaised;
        /** Millis of the last hit on a magmatic / mythic creature ("Magic Find vs ..." bonuses). */
        long magmaticHit, mythicHit;
    }

    private static final Map<UUID, P> STATE = new HashMap<>();

    static P of(UUID id) { return STATE.computeIfAbsent(id, k -> new P()); }

    /** The state if it exists, without creating one. */
    static P peek(UUID id) { return STATE.get(id); }

    static void forget(UUID id) { STATE.remove(id); }

    static void clear() { STATE.clear(); }

    /** Drop expired stacks and return how many remain. */
    static int live(Deque<Long> stacks, long now) {
        while (!stacks.isEmpty() && stacks.peekFirst() <= now) stacks.pollFirst();
        return stacks.size();
    }
}
