package com.secondwatch.game;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.secondwatch.SecondWatch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches anybody's skin by username, so a phantom can be previewed wearing a
 * face that is not currently logged in.
 *
 * <p>Needed because the thing this mod most has to get right - <i>does it
 * convincingly look like your friend</i> - is exactly the thing you cannot check
 * alone. Waiting for a second person to be free before you can evaluate the
 * central illusion makes the feedback loop hours long. This makes it seconds.
 *
 * <p>The skin is not a texture file. It is a <b>signed</b> property issued by
 * Mojang, which is why an offline-mode profile has none and why this has to go
 * to the network rather than making something up locally. Both the value and the
 * signature are carried through untouched - a client will reject the pair if
 * they do not match.
 */
public final class SkinLookup {

    private static final String NAME_TO_ID =
            "https://api.mojang.com/users/profiles/minecraft/";
    private static final String ID_TO_PROFILE =
            "https://sessionserver.mojang.com/session/minecraft/profile/";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Cached for the session. Skins change rarely and a horror mod that pauses
     * to talk to Mojang every time it shows you a figure in the dark has already
     * lost.
     */
    private static final Map<String, GameProfile> CACHE = new ConcurrentHashMap<>();

    private SkinLookup() {
    }

    /**
     * Resolve a username to a fully skinned profile.
     *
     * <p>Runs off the server thread. The caller must hop back before touching
     * anything in the world - see {@code SecondWatchCommands}, which does this
     * with {@code server.execute}.
     *
     * @return a profile with textures, or null if the name does not exist
     */
    public static CompletableFuture<GameProfile> resolve(String username) {
        GameProfile cached = CACHE.get(username.toLowerCase(Locale.ROOT));
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject idJson = getJson(NAME_TO_ID + username);
                if (idJson == null || !idJson.has("id")) return null;

                String undashed = idJson.get("id").getAsString();
                String realName = idJson.has("name")
                        ? idJson.get("name").getAsString() : username;
                UUID uuid = dashify(undashed);

                JsonObject profileJson =
                        getJson(ID_TO_PROFILE + undashed + "?unsigned=false");
                if (profileJson == null) return null;

                Multimap<String, Property> props0 = ArrayListMultimap.create();
                JsonArray props = profileJson.getAsJsonArray("properties");
                if (props != null) {
                    for (var element : props) {
                        JsonObject p = element.getAsJsonObject();
                        String name = p.get("name").getAsString();
                        String value = p.get("value").getAsString();
                        // The signature must survive intact or the client will
                        // refuse the texture and silently fall back to Steve.
                        String signature = p.has("signature")
                                ? p.get("signature").getAsString() : null;
                        props0.put(name, signature == null
                                ? new Property(name, value)
                                : new Property(name, value, signature));
                    }
                }

                GameProfile profile = new GameProfile(uuid, realName, new PropertyMap(props0));
                CACHE.put(username.toLowerCase(Locale.ROOT), profile);
                return profile;
            } catch (Exception e) {
                SecondWatch.LOG.warn("could not look up the skin for '{}'", username, e);
                return null;
            }
        });
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        // 204 is Mojang's "no such player", not an error worth logging.
        if (response.statusCode() != 200 || response.body().isBlank()) return null;
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** Mojang returns ids without dashes; UUID.fromString demands them. */
    private static UUID dashify(String undashed) {
        return UUID.fromString(undashed.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"));
    }
}
