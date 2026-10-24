package com.thestill.core;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts services in declaration order and stops them in exactly the reverse,
 * isolating every stop behind its own try/catch.
 *
 * <p>Rule inherited from Shadow Prison the hard way: a shutdown step that throws
 * must never prevent the steps after it from running. That single behaviour is
 * the difference between "the server restarted" and "everybody lost an hour".
 */
public final class ServiceStack {

    private final Logger log;
    private final List<Service> started = new ArrayList<>();

    public ServiceStack(Logger log) {
        this.log = log;
    }

    /**
     * Starts the service and records it for shutdown.
     *
     * @throws StartupException if the service fails, wrapping the cause. The
     *         caller is expected to abort enable — a half-wired plugin that
     *         keeps running is how corrupt data gets written.
     */
    public <T extends Service> T start(T service) {
        try {
            service.start();
        } catch (Exception e) {
            throw new StartupException(service.serviceName() + " failed to start", e);
        }
        started.add(service);
        log.fine(() -> "started " + service.serviceName());
        return service;
    }

    /** Stops everything started so far, newest first. Never throws. */
    public void stopAll() {
        for (int i = started.size() - 1; i >= 0; i--) {
            Service service = started.get(i);
            try {
                service.stop();
            } catch (Throwable t) {
                // Throwable, not Exception: an Error here (OOM, NoClassDefFound
                // during shutdown) must still not skip the saves below it.
                log.log(Level.SEVERE, "shutdown: " + service.serviceName()
                        + " failed to stop cleanly (continuing)", t);
            }
        }
        started.clear();
    }

    public static final class StartupException extends RuntimeException {
        public StartupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
