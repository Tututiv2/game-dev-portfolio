package com.auracapes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Which cape each player wears. The whole state of the mod, in one place.
 *
 * <p><b>There is no server and that is the design, not a shortcut.</b> Every
 * other way to wear a custom cape hands your texture to somebody else's
 * website and asks them to serve it to the world - which is exactly what the
 * owner did not want. So the mapping from player to cape lives in a JSON file
 * on this machine, the textures are baked into the mod's own jar, and nothing
 * ever leaves the computer.
 *
 * <p>The consequence is worth stating plainly rather than burying: <b>a cape
 * is only visible to somebody running this mod.</b> You always see your own.
 * A friend sees yours when they install the mod and your UUID is in their
 * config - which is why {@code /cape share} exists, to print the one line they
 * need to paste. That is the honest trade for owning the whole thing.
 */
public final class CapeRegistry {

    public static final String MOD_ID = "auracapes";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Capes shipped inside the jar. The key is what you type after /cape. */
    private static final Map<String, String> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("con_green", "Convention green, creeper emblem");
        BUILT_IN.put("con_blue", "Deep blue, gem emblem");
        BUILT_IN.put("con_red", "Crimson, bolt emblem");
        BUILT_IN.put("con_gold", "Black and gold, star emblem");
        BUILT_IN.put("void", "Black fading to violet at the hem");
        // The five MineCon capes. Their textures carry an elytra wing in the
        // right-hand half as well as the cape itself, which the mod does not
        // use yet - the mixin swaps skin.cape() and leaves skin.elytra() alone.
        BUILT_IN.put("none", "No cape");
    }

    /** What the local player wears. */
    private static String self = "con_blue";

    /** Other people's capes, by UUID. Populated from the config file. */
    private static final Map<UUID, String> OTHERS = new LinkedHashMap<>();

    /** Every gist to READ, including your own. Blank list disables syncing. */
    private static final List<String> syncUrls = new ArrayList<>();

    /** Your OWN gist, for publishing. These two never leave this machine. */
    private static String gistId = "";
    private static String githubToken = "";
    private static String gistFile = "capes.json";

    private CapeRegistry() {
    }

    public static List<String> names() {
        return new ArrayList<>(BUILT_IN.keySet());
    }

    public static String describe(String name) {
        return BUILT_IN.getOrDefault(name, "");
    }

    public static boolean isKnown(String name) {
        return BUILT_IN.containsKey(name);
    }

    public static String current() {
        return self;
    }

    /**
     * The texture to draw on this player, or null to leave vanilla alone.
     *
     * <p>Returning null rather than a blank texture matters: the cape layer
     * checks {@code skin.cape() != null} before it draws anything, so null is
     * how "this player has no cape" is spelled. Handing it a transparent PNG
     * instead would draw an invisible cape every frame and still flap.
     */
    public static Identifier capeFor(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        // Precedence, strongest first: your own choice, then anything you
        // set by hand locally, then the shared file. A hand-written entry
        // beating the sync is what makes the local block useful as an
        // override rather than a thing the next refresh silently undoes.
        String name = null;
        if (isLocalPlayer(uuid)) {
            name = self;
        }
        if (name == null) {
            name = OTHERS.get(uuid);
        }
        if (name == null) {
            name = CapeSync.get(uuid);
        }
        if (name == null || name.equals("none") || !BUILT_IN.containsKey(name)) {
            return null;
        }
        return Identifier.fromNamespaceAndPath(MOD_ID, "textures/cape/" + name + ".png");
    }

    private static boolean isLocalPlayer(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && uuid.equals(mc.player.getUUID());
    }

    public static UUID localUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }

    // ------------------------------------------------------------ config

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
    }

    public static void load() {
        Path p = configPath();
        if (!Files.exists(p)) {
            save();
            return;
        }
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root.has("self")) {
                String want = root.get("self").getAsString();
                if (BUILT_IN.containsKey(want)) {
                    self = want;
                }
            }
            OTHERS.clear();
            if (root.has("players")) {
                JsonObject players = root.getAsJsonObject("players");
                for (String key : players.keySet()) {
                    // A malformed UUID is one bad line, not a broken mod: skip
                    // it and keep every other entry the file got right.
                    try {
                        OTHERS.put(UUID.fromString(key), players.get(key).getAsString());
                    } catch (IllegalArgumentException ignored) {
                        AuraCapes.LOG.warn("[auracapes] not a UUID, skipping: {}", key);
                    }
                }
            }
            syncUrls.clear();
            if (root.has("syncUrls")) {
                root.getAsJsonArray("syncUrls").forEach(
                        e -> syncUrls.add(e.getAsString()));
            }
            if (root.has("gistId")) {
                gistId = root.get("gistId").getAsString();
            }
            if (root.has("githubToken")) {
                githubToken = root.get("githubToken").getAsString();
            }
            if (root.has("gistFile")) {
                gistFile = root.get("gistFile").getAsString();
            }
            StreamerName.restore(
                    root.has("streamerOn") && root.get("streamerOn").getAsBoolean(),
                    root.has("streamerName") ? root.get("streamerName").getAsString() : "");
            List<String> accounts = new ArrayList<>();
            if (root.has("accounts")) {
                root.getAsJsonArray("accounts").forEach(e -> accounts.add(e.getAsString()));
            }
            Accounts.restore(accounts,
                    root.has("activeAccount") ? root.get("activeAccount").getAsString() : "");
            AuraCapes.LOG.info("[auracapes] wearing '{}', {} local override(s), "
                            + "reading {} gist(s), publishing {}",
                    self, OTHERS.size(), syncUrls.size(),
                    gistId.isBlank() ? "off" : "on");
        } catch (IOException | RuntimeException e) {
            AuraCapes.LOG.warn("[auracapes] could not read {}: {}", p, e.toString());
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment",
                "self = your cape. syncUrls = raw gist URLs to read (yours and "
                        + "your friends'). gistId + githubToken publish YOUR cape "
                        + "automatically and never leave this PC. players = manual "
                        + "overrides. Capes: " + String.join(", ", BUILT_IN.keySet()));
        root.addProperty("self", self);
        com.google.gson.JsonArray urls = new com.google.gson.JsonArray();
        for (String u : syncUrls) {
            urls.add(u);
        }
        root.add("syncUrls", urls);
        root.addProperty("gistId", gistId);
        root.addProperty("githubToken", githubToken);
        root.addProperty("gistFile", gistFile);
        // The alias is kept even while switched off, so turning streamer mode
        // back on does not mean typing the name again.
        root.addProperty("streamerOn", StreamerName.rawEnabled());
        root.addProperty("streamerName", StreamerName.alias());
        // Offline account names only - there is no token or password for an
        // offline account, so nothing secret is ever written here.
        com.google.gson.JsonArray accounts = new com.google.gson.JsonArray();
        for (String n : Accounts.savedNames()) {
            accounts.add(n);
        }
        root.add("accounts", accounts);
        root.addProperty("activeAccount", Accounts.savedActive());
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, String> e : OTHERS.entrySet()) {
            players.addProperty(e.getKey().toString(), e.getValue());
        }
        root.add("players", players);
        try {
            Path p = configPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AuraCapes.LOG.warn("[auracapes] could not write config: {}", e.toString());
        }
    }

    /** Everyone added by hand, for /cape friend list. */
    public static Map<UUID, String> friends() {
        return new LinkedHashMap<>(OTHERS);
    }

    public static List<String> syncUrls() {
        return new ArrayList<>(syncUrls);
    }

    public static String gistId() {
        return gistId;
    }

    public static String githubToken() {
        return githubToken;
    }

    public static String gistFile() {
        return gistFile;
    }

    public static void wear(String name) {
        self = name;
        save();
        // Publish before anybody asks. This is the whole point of the gist:
        // a friend should never have to be told you changed.
        CapePush.publish(name);
    }

    public static void setOther(UUID uuid, String name) {
        if (name == null || name.equals("none")) {
            OTHERS.remove(uuid);
        } else {
            OTHERS.put(uuid, name);
        }
        save();
    }
}
