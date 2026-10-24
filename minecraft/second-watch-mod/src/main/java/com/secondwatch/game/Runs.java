package com.secondwatch.game;

import net.minecraft.server.MinecraftServer;

/**
 * Holds the one run belonging to the running server.
 *
 * <p>Static because there is exactly one integrated or dedicated server in a
 * process and this mod is a two-player scenario, not a lobby system. If that
 * ever stops being true this becomes a map keyed on the server instance, and
 * nothing else has to change.
 */
public final class Runs {

    private static RunState current;

    private Runs() {
    }

    public static RunState get() {
        return current;
    }

    public static boolean active() {
        return current != null;
    }

    public static RunState loadFor(MinecraftServer server) {
        long seed = server.overworld().getSeed();
        current = RunState.load(server, seed);
        return current;
    }

    public static void clear() {
        current = null;
    }
}
