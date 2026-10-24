package com.shadowprison.core;

/**
 * Anything with a start/stop lifecycle managed by {@link ServiceStack}.
 *
 * <p>Shadow Prison 1's shutdown was a flat list of hand-written calls; when one threw, every
 * save below it was silently skipped (the 2026-07-15 item-loss incident). Implementing this
 * means a service gets its own try/catch for free and cannot take its neighbours down with it.
 * Ported from THE STILL, where it is proven.
 */
public interface Service {

    default String serviceName() {
        return getClass().getSimpleName();
    }

    /** Acquire resources. Throwing aborts plugin enable — correct: never run half-wired. */
    default void start() throws Exception {
    }

    /**
     * Release resources and flush state. Called in reverse start order. Must tolerate the plugin
     * already being disabled — no Bukkit scheduler then, so anything async must run inline.
     */
    default void stop() throws Exception {
    }
}
