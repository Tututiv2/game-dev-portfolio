package com.thestill.mine;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session counters that are deliberately <em>not</em> persisted.
 *
 * <p>"Blocks this session" resets when you log out, which is the whole point of
 * it — it is a measure of what you are doing right now, and persisting it would
 * make it a second, worse lifetime total.
 */
public final class SessionStats {

    private final Map<UUID, AtomicLong> blocksMined = new ConcurrentHashMap<>();

    public long recordBlock(UUID playerId) {
        return blocksMined.computeIfAbsent(playerId, id -> new AtomicLong()).incrementAndGet();
    }

    public long blocksThisSession(UUID playerId) {
        AtomicLong counter = blocksMined.get(playerId);
        return counter == null ? 0L : counter.get();
    }

    public void forget(UUID playerId) {
        blocksMined.remove(playerId);
    }

    public void clear() {
        blocksMined.clear();
    }
}
