package com.shadowprison.profile;

import com.shadowprison.money.Currency;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One online player's loaded data. Main-thread only after join — every field is read and written
 * on the server thread, and persisted through the store's single writer. (SP1's profile map was a
 * plain HashMap mutated from the async chat thread — MINECRAFT_REVIEW #3.)
 */
public final class Profile {

    private final UUID uuid;
    private String name;
    private final long firstSeen;
    private long playtimeMs;
    private final long sessionStart = System.currentTimeMillis();
    final EnumMap<Currency, Long> balances = new EnumMap<>(Currency.class);
    final Map<String, String> state = new HashMap<>();

    Profile(UUID uuid, String name, long firstSeen, long playtimeMs) {
        this.uuid = uuid;
        this.name = name;
        this.firstSeen = firstSeen;
        this.playtimeMs = playtimeMs;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    void name(String name) {
        this.name = name;
    }

    public long firstSeen() {
        return firstSeen;
    }

    /** Stored playtime plus the current session. */
    public long playtimeMs() {
        return playtimeMs + (System.currentTimeMillis() - sessionStart);
    }

    long storedPlaytimeMs() {
        return playtimeMs;
    }

    public boolean isNew() {
        return playtimeMs == 0 && System.currentTimeMillis() - firstSeen < 60_000L;
    }

    public long balance(Currency currency) {
        return balances.getOrDefault(currency, 0L);
    }

    public String state(String key) {
        return state.get(key);
    }

    public long stateLong(String key, long fallback) {
        String v = state.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
