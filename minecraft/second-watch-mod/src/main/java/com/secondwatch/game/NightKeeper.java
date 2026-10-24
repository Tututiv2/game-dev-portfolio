package com.secondwatch.game;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

/**
 * The sun does not come back up.
 *
 * <p>26.x replaced the old day-time field with a registry of {@code WorldClock}s
 * driven by a {@code ServerClockManager}, and that turns out to be a gift:
 * pausing the overworld clock stops the sky in one call rather than a fight with
 * the level data, and everything downstream (mobs not burning, crops not
 * growing, the clock item sitting still in your hand) follows from vanilla for
 * free.
 *
 * <p><b>Never call {@code setRate(clock, 0)}.</b> It looks like the obvious way
 * to stop time and it is a trap: {@code ServerClockManager} is a
 * {@code SavedData}, its rate codec rejects non-positive values, and the failure
 * does not surface at the call site. It surfaces later as
 * {@code IllegalStateException: Value must be positive: 0.0} out of
 * {@code SavedDataStorage.encodeUnchecked} during {@code saveAllChunks} - so
 * <b>the world silently stops being able to save</b>, the shutdown throws, and
 * the watchdog then declares the server crashed after hanging for 60 seconds.
 * On a recording night that reads as "Minecraft broke", not as "the mod broke".
 * Found by actually running a dedicated server and reading the shutdown log.
 *
 * <p>This is deliberately server-wide rather than per-player. Both players are
 * meant to be in the same endless night; the two clients disagreeing is the
 * phantom's job, and using the same mechanism for both would make the scariest
 * effect in the mod indistinguishable from the weather.
 */
public final class NightKeeper {

    /**
     * Where the clock stops. Deep enough to be unambiguously night, but short of
     * midnight, so the sky is not at its prettiest and the moon sits slightly
     * wrong for the rest of the run. The journal calls it "half nine at night".
     */
    public static final long HELD_AT_TICKS = 16_000L;

    private NightKeeper() {
    }

    public static Holder<WorldClock> overworldClock(MinecraftServer server) {
        return server.registryAccess()
                .lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(WorldClocks.OVERWORLD);
    }

    /**
     * Bring the state of the sky into line with the state of the run. Safe to
     * call every tick - it only writes when something is actually wrong, because
     * hammering setRate every tick would fight the manager's own sync packets.
     */
    public static void enforce(MinecraftServer server, RunState run) {
        var clock = overworldClock(server);
        var manager = server.clockManager();

        if (!run.sunIsGone()) {
            // Night one is an ordinary Minecraft night and that is the single
            // most important ten minutes in the mod. Leave it completely alone.
            return;
        }

        long total = manager.getTotalTicks(clock);
        long timeOfDay = Math.floorMod(total, 24_000L);

        if (timeOfDay != HELD_AT_TICKS) {
            // Walk it to the held time once, then stop it. setTotalTicks rather
            // than a marker so the held moment is exact and identical in every
            // run - players comparing recordings should see the same wrong sky.
            manager.setTotalTicks(clock, total - timeOfDay + HELD_AT_TICKS);
        }

        // Pause it. Do NOT also setRate(clock, 0) - see below.
        manager.setPaused(clock, true);
    }

    /** Give the sky back. Only the ending should ever call this. */
    public static void release(MinecraftServer server) {
        var clock = overworldClock(server);
        server.clockManager().setPaused(clock, false);
        server.clockManager().setRate(clock, 1.0f);
    }
}
