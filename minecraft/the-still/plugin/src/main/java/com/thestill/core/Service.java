package com.thestill.core;

/**
 * Anything with a start/stop lifecycle managed by {@link ServiceStack}.
 *
 * <p>The point of this interface is {@code onDisable}. Shadow Prison's shutdown
 * path was a flat list of hand-written calls, and when one of them threw, every
 * save below it was silently skipped — which fired on <em>every</em> clean
 * shutdown with a player online. Implementing this interface means a service
 * gets its own try/catch for free and cannot take its neighbours down with it.
 */
public interface Service {

    /** Human name used in log lines. Defaults to the simple class name. */
    default String serviceName() {
        return getClass().getSimpleName();
    }

    /** Acquire resources. Throwing here aborts plugin enable, which is correct. */
    default void start() throws Exception {
    }

    /**
     * Release resources and flush state.
     *
     * <p>Called in reverse start order. Must tolerate being called when the
     * plugin is already disabled — that means no Bukkit scheduler is available,
     * so anything that would normally be async has to run inline.
     */
    default void stop() throws Exception {
    }
}
