package com.auracapes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Publish YOUR cape to YOUR gist, so friends see a change without being told.
 *
 * <p><b>Everyone owns their own file, and that is the security design.</b> The
 * obvious shape - one shared gist everybody writes to - needs everybody to
 * hold a token that can edit it, which means handing a friend a credential
 * that can also edit anything else that token can reach. Instead each person
 * publishes their own gist and reads everybody else's, so a token never
 * leaves the machine that owns it and the worst a friend can do to your gist
 * is read it.
 *
 * <p><b>It is read-modify-write, not overwrite.</b> A gist can hold entries
 * for several players - your own plus anybody you added by hand - so pushing
 * a freshly built object would silently delete the rest. It fetches the file,
 * changes the single line that is yours, and puts it back.
 *
 * <p>Every failure is silent past a log line. No token, no gist id, no
 * network, a revoked token, a rate limit - all of them leave the game exactly
 * as it was. Changing a cape is a cosmetic act and must never produce an
 * error the player has to deal with.
 */
public final class CapePush {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService POOL =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "auracapes-push");
                t.setDaemon(true);
                return t;
            });

    private CapePush() {
    }

    public static boolean configured() {
        return !CapeRegistry.gistId().isBlank()
                && !CapeRegistry.githubToken().isBlank();
    }

    /** Fire-and-forget: set your own entry in your own gist. */
    public static void publish(String cape) {
        if (!configured()) {
            return;
        }
        UUID me = CapeRegistry.localUuid();
        if (me == null) {
            return;
        }
        POOL.submit(() -> {
            try {
                push(me, cape);
            } catch (Exception e) {
                AuraCapes.LOG.warn("[auracapes] could not publish cape: {}", e.toString());
            }
        });
    }

    private static void push(UUID me, String cape) throws Exception {
        String id = CapeRegistry.gistId();
        String token = CapeRegistry.githubToken();
        String file = CapeRegistry.gistFile();

        HttpRequest get = HttpRequest.newBuilder(
                        URI.create("https://api.github.com/gists/" + id))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AuraCapes/1.0")
                .GET().build();
        HttpResponse<String> res = HTTP.send(get, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            AuraCapes.LOG.warn("[auracapes] gist read got HTTP {} - check gistId "
                    + "and that the token has the `gist` scope", res.statusCode());
            return;
        }

        JsonObject gist = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonObject files = gist.getAsJsonObject("files");
        JsonObject players = new JsonObject();
        if (files != null && files.has(file)) {
            try {
                JsonObject cur = JsonParser
                        .parseString(files.getAsJsonObject(file).get("content").getAsString())
                        .getAsJsonObject();
                if (cur.has("players")) {
                    players = cur.getAsJsonObject("players");
                }
            } catch (RuntimeException e) {
                // A hand-edited file that no longer parses would otherwise
                // block every future push. Start clean rather than wedge.
                AuraCapes.LOG.warn("[auracapes] gist content was not valid JSON, "
                        + "rewriting it");
            }
        }

        // Only the line that is ours. Everything else in the file survives.
        players.addProperty(me.toString(), cape);

        JsonObject root = new JsonObject();
        root.add("players", players);
        JsonObject one = new JsonObject();
        one.addProperty("content", root.toString());
        JsonObject wrap = new JsonObject();
        wrap.add(file, one);
        JsonObject body = new JsonObject();
        body.add("files", wrap);

        HttpRequest patch = HttpRequest.newBuilder(
                        URI.create("https://api.github.com/gists/" + id))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "AuraCapes/1.0")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> up = HTTP.send(patch, HttpResponse.BodyHandlers.ofString());
        if (up.statusCode() / 100 == 2) {
            AuraCapes.LOG.info("[auracapes] published '{}' to your gist", cape);
        } else {
            AuraCapes.LOG.warn("[auracapes] gist write got HTTP {}", up.statusCode());
        }
    }
}
