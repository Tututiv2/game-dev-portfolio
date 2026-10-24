package com.skyclone.api.command;

/**
 * Command registry. Must be called during module enable() — Paper registers commands in a
 * lifecycle event that fires after plugin enable, and core flushes everything collected then.
 */
public interface CommandService {

    void register(SkyCommand command);

    /**
     * Register {@code /sb <sub> ...} (admin only). {@code args} passed to the executor exclude
     * the sub name itself. Shows in /commands under "Admin".
     */
    void admin(String sub, String usage, String description, SkyCommand.Executor executor, SkyCommand.Completer completer);

    default void admin(String sub, String usage, String description, SkyCommand.Executor executor) {
        admin(sub, usage, description, executor, (s, a) -> java.util.List.of());
    }

    /**
     * A live self-check, run by {@code /sb selftest [filter]} on the real server (also over RCON,
     * which is how the integrator verifies a build). Return null when it passes, or a short
     * description of what is wrong. Ids are "module.what": "slayer.boss-drops-exist".
     *
     * <p>Good self-tests check that the DATA hangs together at runtime: every drop/recipe/shop
     * item id exists, every NPC anchor exists in the built layout, every collection tier unlocks
     * something real, menus build without throwing for a fake profile, a formula gives the
     * documented number for a documented input.
     */
    void selfTest(String id, java.util.function.Supplier<String> test);

    /**
     * Run a REGISTERED command (name or alias) as if the player typed it, through the same guards
     * (permission, player-only, rate limit, exception catch). For menus that open another module's
     * feature ("/profiles", "/calendar", "/bz") without importing it. Never pass player-typed text as
     * {@code name}. Returns false when no such command is registered. (Added by the core gate.)
     */
    boolean run(org.bukkit.entity.Player p, String name, String... args);

    /**
     * True when a command (name or alias) is registered through this service, so a menu can mark a
     * feature "Coming soon" without asking Paper's command map. (Added by integration round 2,
     * menus Request 2.)
     */
    default boolean exists(String name) { return false; }
}
