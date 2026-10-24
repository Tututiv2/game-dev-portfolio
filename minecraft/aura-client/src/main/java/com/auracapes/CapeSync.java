package com.auracapes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Everybody's capes, from one small file on the web. No pasting UUIDs.
 *
 * <p><b>Why a file and not peer-to-peer.</b> Two Minecraft clients never talk
 * to each other; a mod's custom packets travel THROUGH the server, and a
 * public server like Hypixel drops payloads it does not recognise. So there
 * has to be exactly one place both clients read from, and the only question
 * is who owns it. A cape website owns yours. A file the owner controls does
 * not - which was the whole point of writing this mod instead of installing
 * one.
 *
 * <p>The file is trivial and can live anywhere that serves plain text:
 *
 * <pre>
 * { "players": { "9e93b8ee-50df-5fe3-9601-11b243607a93": "con_green" } }
 * </pre>
 *
 * <p><b>Failure is always silent and always harmless.</b> No network, a 404, a
 * half-written file, a cape name this build has never heard of - every one of
 * them leaves you with the capes you already had. A cosmetic mod that
 * interrupts your game because a web request failed would be worse than one
 * that does nothing at all, so the refresh runs on its own daemon thread and
 * nothing in the render path ever waits on it.
 */
public final class CapeSync {

    /** Long enough that nobody notices the traffic, short enough to feel live. */
    private static final long EVERY_MINUTES = 5;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<UUID, String> SYNCED = new HashMap<>();
    private static ScheduledExecutorService pool;

    private CapeSync() {
    }

    /** What the shared file says this player wears, or null. */
    public static String get(UUID uuid) {
        synchronized (SYNCED) {
            return SYNCED.get(uuid);
        }
    }

    public static int size() {
        synchronized (SYNCED) {
            return SYNCED.size();
        }
    }

    public static void start(java.util.List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            AuraCapes.LOG.info("[auracapes] no syncUrls set - only your own cape "
                    + "and anything in the local players block will show");
            return;
        }
        if (pool != null) {
            return;
        }
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auracapes-sync");
            // A daemon thread cannot keep the game from closing. A cosmetic
            // refresh has no business holding the JVM open on quit.
            t.setDaemon(true);
            return t;
        });
        pool.scheduleAtFixedRate(() -> refreshAll(urls), 0, EVERY_MINUTES, TimeUnit.MINUTES);
    }

    /** Merge every gist. Later files win on a clash, which never happens in
     *  practice because each person only ever writes their own UUID. */
    private static void refreshAll(java.util.List<String> urls) {
        Map<UUID, String> merged = new HashMap<>();
        for (String u : urls) {
            merged.putAll(fetch(u));
        }
        synchronized (SYNCED) {
            SYNCED.clear();
            SYNCED.putAll(merged);
        }
        AuraCapes.LOG.info("[auracapes] synced {} cape(s) from {} source(s)",
                merged.size(), urls.size());
    }

    private static Map<UUID, String> fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    // Ask for a fresh copy: a raw file host will happily serve
                    // a cached one for minutes, which reads as "my friend
                    // changed their cape and it did nothing".
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "AuraCapes/1.0")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                AuraCapes.LOG.warn("[auracapes] sync got HTTP {} from {}",
                        res.statusCode(), url);
                return Map.of();
            }
            return parse(res.body());
        } catch (Exception e) {
            // Offline, DNS down, host asleep. Keep what we have.
            AuraCapes.LOG.debug("[auracapes] sync skipped: {}", e.toString());
            return Map.of();
        }
    }

    private static Map<UUID, String> parse(String body) {
        Map<UUID, String> out = new HashMap<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject players = root.has("players")
                ? root.getAsJsonObject("players") : root;
        for (String key : players.keySet()) {
            UUID id = uuid(key);
            if (id == null) {
                continue;
            }
            String cape = players.get(key).getAsString();
            if (CapeRegistry.isKnown(cape)) {
                out.put(id, cape);
            } else {
                // A newer build of the mod may ship capes this one does not.
                // Skipping the entry is right; guessing a substitute is not.
                AuraCapes.LOG.warn("[auracapes] unknown cape '{}' in sync, skipped", cape);
            }
        }
        return out;
    }

    /**
     * Accept a UUID with dashes or without.
     *
     * <p>Mojang's API returns the dashless form and every launcher shows the
     * dashed one, so a shared file will contain both and refusing either would
     * make the file feel broken for no reason.
     */
    static UUID uuid(String s) {
        String t = s.trim();
        try {
            if (t.length() == 32) {
                t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-"
                        + t.substring(12, 16) + "-" + t.substring(16, 20)
                        + "-" + t.substring(20);
            }
            return UUID.fromString(t);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
