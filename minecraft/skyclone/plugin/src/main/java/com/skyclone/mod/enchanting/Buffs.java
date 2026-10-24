package com.skyclone.mod.enchanting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Short timed buffs enchants grant (Riposte, Last Stand, Refrigerate, Fatal Tempo, Flowstate...). Main thread only. */
final class Buffs {

    private Buffs() {}

    record Buff(double value, long until) {}

    private static final Map<UUID, Map<String, Buff>> BUFFS = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();

    static void set(UUID p, String id, double value, long millis) {
        BUFFS.computeIfAbsent(p, k -> new HashMap<>()).put(id, new Buff(value, System.currentTimeMillis() + millis));
    }

    /** Active value, or 0 when absent/expired. */
    static double get(UUID p, String id) {
        Map<String, Buff> m = BUFFS.get(p);
        if (m == null) return 0;
        Buff b = m.get(id);
        if (b == null) return 0;
        if (b.until() < System.currentTimeMillis()) { m.remove(id); return 0; }
        return b.value();
    }

    static boolean active(UUID p, String id) { return get(p, id) != 0; }

    /** True (and starts the cooldown) when the cooldown is ready. */
    static boolean cooldown(UUID p, String id, long millis) {
        Map<String, Long> m = COOLDOWNS.computeIfAbsent(p, k -> new HashMap<>());
        long now = System.currentTimeMillis();
        Long ready = m.get(id);
        if (ready != null && ready > now) return false;
        m.put(id, now + millis);
        return true;
    }

    static void forget(UUID p) { BUFFS.remove(p); COOLDOWNS.remove(p); }
}
