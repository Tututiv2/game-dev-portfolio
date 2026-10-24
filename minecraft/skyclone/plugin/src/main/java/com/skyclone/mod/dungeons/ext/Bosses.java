package com.skyclone.mod.dungeons.ext;

import com.skyclone.mod.dungeons.model.FloorDef;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EXTENSION POINT (agent B): the boss-floor registry. Register in your enable() code:
 * <pre>  Bosses.register("F1", new GrinkoFight());   // also used for M1 unless "M1" is registered</pre>
 * Lookups fall back M&lt;n&gt; -&gt; F&lt;n&gt; -&gt; the framework default (set by the module).
 */
public final class Bosses {

    private Bosses() {}

    private static final Map<String, BossFloor> FLOORS = new ConcurrentHashMap<>();
    private static volatile BossFloor fallback;
    /** Part B: false = every floor uses the fallback (dial dungeons.real-bosses). Read at run start. */
    public static volatile java.util.function.BooleanSupplier enabled = () -> true;

    public static void register(String floorKey, BossFloor floor) { FLOORS.put(floorKey.toUpperCase(java.util.Locale.ROOT), floor); }

    public static void unregister(String floorKey) { FLOORS.remove(floorKey.toUpperCase(java.util.Locale.ROOT)); }

    /** Set by the framework: the boss used when a floor has no registration. */
    public static void setFallback(BossFloor f) { fallback = f; }

    /** True when a real (non-fallback) boss is registered for this floor or its normal-mode twin. */
    public static boolean hasCustom(FloorDef f) {
        if (!enabled.getAsBoolean()) return false;
        return FLOORS.containsKey(f.id) || (f.bossKey != null && FLOORS.containsKey(f.bossKey));
    }

    public static BossFloor forFloor(FloorDef f) {
        if (!enabled.getAsBoolean()) return fallback;
        BossFloor b = FLOORS.get(f.id);
        if (b == null && f.bossKey != null) b = FLOORS.get(f.bossKey);
        return b != null ? b : fallback;
    }

    public static Map<String, BossFloor> all() { return Map.copyOf(FLOORS); }
}
