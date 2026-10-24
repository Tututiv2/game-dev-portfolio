package com.shadowprison.core;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts services in declaration order and stops them in exactly the reverse, isolating every
 * stop behind its own try/catch. A shutdown step that throws must never prevent the steps after
 * it from running. Ported from THE STILL.
 */
public final class ServiceStack {

    private final Logger log;
    private final List<Service> started = new ArrayList<>();

    public ServiceStack(Logger log) {
        this.log = log;
    }

    /** Starts the service and records it for shutdown. Throws {@link StartupException} on failure. */
    public <T extends Service> T start(T service) {
        try {
            service.start();
        } catch (Exception e) {
            throw new StartupException(service.serviceName() + " failed to start", e);
        }
        started.add(service);
        return service;
    }

    /** Stops everything started so far, newest first. Never throws. */
    public void stopAll() {
        for (int i = started.size() - 1; i >= 0; i--) {
            Service service = started.get(i);
            try {
                service.stop();
            } catch (Throwable t) {
                // Throwable, not Exception: an Error during shutdown must still not skip the saves below.
                log.log(Level.SEVERE, "shutdown: " + service.serviceName() + " failed to stop cleanly (continuing)", t);
            }
        }
        started.clear();
    }

    public int size() {
        return started.size();
    }

    public static final class StartupException extends RuntimeException {
        public StartupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
