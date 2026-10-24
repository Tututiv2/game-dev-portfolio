package com.aisky.showcontrol;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

/**
 * The episode clock and the freeze switch. Fields are volatile: written on the main thread,
 * read by /health on HTTP threads.
 */
final class Episode {

    private final ShowControlPlugin plugin;

    volatile boolean running;
    volatile long startedAt;      // epoch ms
    volatile long endedAt;        // epoch ms, 0 while running / never ran
    volatile int minutes;
    volatile boolean frozen;
    volatile String endReason;

    Episode(ShowControlPlugin plugin) { this.plugin = plugin; }

    /** Main thread. */
    void start(int mins) {
        if (running) stop("restarted");
        plugin.memory().clear();
        running = true;
        minutes = Math.max(1, mins);
        startedAt = System.currentTimeMillis();
        endedAt = 0;
        endReason = null;
        plugin.bus().ev("episode_start").drama(8).data("minutes", minutes)
                .text("The episode begins: " + minutes + " minutes on the clock").emit();
    }

    /** Main thread. */
    void stop(String reason) {
        if (!running) return;
        running = false;
        endedAt = System.currentTimeMillis();
        endReason = reason;
        plugin.bus().ev("episode_end").drama(8).data("reason", reason)
                .data("elapsed_s", elapsedSeconds())
                .text("The episode is over (" + reason + ")").emit();
    }

    long elapsedSeconds() {
        if (startedAt == 0) return 0;
        long end = running ? System.currentTimeMillis() : (endedAt == 0 ? startedAt : endedAt);
        return Math.max(0, (end - startedAt) / 1000);
    }

    long remainingSeconds() {
        if (!running) return 0;
        return Math.max(0, minutes * 60L - elapsedSeconds());
    }

    /** Runs every second on the main thread. */
    void tick() {
        if (running && elapsedSeconds() >= minutes * 60L) stop("time");
    }

    /** Main thread. */
    void freeze(boolean on) {
        frozen = on;
        if (plugin.config().freezeWorldTicks) {
            try { Bukkit.getServerTickManager().setFrozen(on); } catch (Throwable t) {
                plugin.getLogger().warning("tick freeze unavailable: " + t);
            }
        }
    }

    JsonObject json() {
        JsonObject j = new JsonObject();
        j.addProperty("running", running);
        j.addProperty("minutes", minutes);
        j.addProperty("started_at", startedAt);
        j.addProperty("elapsed_s", elapsedSeconds());
        j.addProperty("remaining_s", remainingSeconds());
        j.addProperty("frozen", frozen);
        if (endReason != null) j.addProperty("end_reason", endReason);
        String rs = plugin.rulesets().active;
        if (rs != null) j.addProperty("ruleset", rs);
        return j;
    }
}
