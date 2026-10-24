package com.thestill.config;

/**
 * Every dial Phase 0 owns.
 *
 * <p>An instance, not a pile of statics: services take {@code Dials} in their
 * constructor, which keeps them unit-testable and keeps the registry out of
 * global mutable state. Shadow Prison's static service singletons are one of the
 * reasons nothing in it could be tested.
 *
 * <p>Later phases add their dials on the service that owns them, not here —
 * this class only holds cross-cutting ones. The rule is that the dial lives
 * wherever the behaviour lives, and registration is part of writing it.
 */
public final class Dials {

    public final Dial<Integer> storeFlushSeconds;
    public final Dial<Integer> storeBusyTimeoutMs;
    public final Dial<Integer> storeQueueWarnDepth;
    public final Dial<Boolean> storeVerboseTiming;

    public final Dial<Boolean> probeEnabled;
    public final Dial<Integer> probeWritesPerSecond;

    public final Dial<Boolean> joinMessageEnabled;

    public Dials(DialRegistry r) {
        storeFlushSeconds = r.intDial("store.flush-seconds", 20)
                .category("Storage").label("Write-behind flush")
                .describe("How often dirty player and region state is written to disk. "
                        + "Durable writes (money, ranks) ignore this and commit immediately.")
                .range(1, 300).step(5)
                .register();

        storeBusyTimeoutMs = r.intDial("store.busy-timeout-ms", 5_000)
                .category("Storage").label("SQLite busy timeout")
                .describe("How long a statement waits for a lock before giving up.")
                .range(100, 60_000).step(500)
                .register();

        storeQueueWarnDepth = r.intDial("store.queue-warn-depth", 5_000)
                .category("Storage").label("Write queue warning depth")
                .describe("Log a warning when the write queue backs up past this many tasks. "
                        + "A queue that keeps growing means the disk cannot keep up with the game.")
                .range(100, 1_000_000).step(500)
                .register();

        storeVerboseTiming = r.boolDial("store.verbose-timing", false)
                .category("Storage").label("Log write timings")
                .describe("Log how long each flush took. Noisy; for diagnosing stalls only.")
                .register();

        probeEnabled = r.boolDial("probe.enabled", false)
                .category("Crash probe").label("Crash probe")
                .describe("Writes a monotonically increasing counter durably and logs each "
                        + "committed value, so the crash-test harness can prove nothing "
                        + "acknowledged was lost. Development only — leave off in production.")
                .register();

        probeWritesPerSecond = r.intDial("probe.writes-per-second", 20)
                .category("Crash probe").label("Probe write rate")
                .describe("Durable writes per second while the probe is running.")
                .range(1, 200).step(5)
                .register();

        joinMessageEnabled = r.boolDial("general.join-message", true)
                .category("General").label("Join message")
                .describe("The one visible thing Phase 0 ships, and only so you can tell "
                        + "from in game that the plugin actually loaded.")
                .register();
    }
}
