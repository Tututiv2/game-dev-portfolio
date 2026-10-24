package com.auraqol.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.auraqol.AuraQoL;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Where each HUD piece sits, and the file that remembers it.
 *
 * <p>Replaces the three separate schemes the features shipped with - a corner
 * enum for the info panel and hardcoded pixel constants for the other two -
 * because none of them could be moved without editing a file, and "I cannot
 * move the WASD" is exactly the complaint that produced this class.
 *
 * <p><b>Positions are stored as FRACTIONS of the screen, not pixels.</b> A
 * pixel coordinate is only meaningful at the resolution it was set at: drag
 * the CPS counter to the right-hand edge in a window, go fullscreen, and a
 * stored x of 780 is suddenly in the middle of the screen. A fraction lands
 * in the same visual place at any size, which is what a person means when
 * they put something in a corner. Only the conversion at draw time knows
 * about pixels.
 *
 * <p>Each element also reports the size it last drew at. The editor needs a
 * box to drag and cannot know how wide "12 CPS | 3 CPS" is, or that the
 * keystroke block is 52x64 - so rather than duplicate those numbers where
 * they would rot, whoever draws a thing says how big it turned out.
 */
public final class HudLayout {

    public static final String INFO = "info";
    public static final String CPS = "cps";
    public static final String KEYS = "keys";
    public static final String BPS = "bps";
    public static final String MONEY = "money";
    public static final String TODO = "todo";
    public static final String COMMS = "comms";
    public static final String SLAYER = "slayer";
    public static final String ABILITY = "ability";
    public static final String SACKS = "sacks";
    // Shadow Prison live panels (ShadowHud). Ids double as the /aq toggle names.
    public static final String SP_MINING = "sp-mining";
    public static final String SP_EARNINGS = "sp-earnings";
    public static final String SP_RANKUP = "sp-rankup";
    public static final String SP_BOOSTS = "sp-boosts";
    public static final String SP_CONTRACT = "sp-contract";
    public static final String SP_SKILLS = "sp-skills";
    public static final String SP_PROFILE = "sp-profile";

    /** id -> {xFraction, yFraction, scale}. */
    private static final Map<String, float[]> POS = new LinkedHashMap<>();

    /** id -> {width, height} in pixels, as last drawn. */
    private static final Map<String, int[]> SIZE = new LinkedHashMap<>();

    private static final Map<String, String> LABEL = new LinkedHashMap<>();

    static {
        POS.put(INFO, new float[]{0.006f, 0.010f, 1.0f});
        POS.put(CPS, new float[]{0.006f, 0.300f, 1.0f});
        POS.put(KEYS, new float[]{0.006f, 0.620f, 1.0f});
        // The SkyBlock pieces default to the left column under CPS (money)
        // and the upper right (to-do): Hypixel's sidebar owns the middle of
        // the right edge, and vanilla status-effect icons the top 30px of it.
        POS.put(BPS, new float[]{0.006f, 0.355f, 1.0f});
        POS.put(MONEY, new float[]{0.006f, 0.410f, 1.0f});
        POS.put(TODO, new float[]{0.790f, 0.100f, 1.0f});
        // Context panels (they only draw on a mining island / during a
        // quest): a second column right of the info panel, clear of the
        // boss bar at top centre and the crosshair.
        // Measured at 480x270 GUI px (1080p, auto scale): commissions and the
        // ability timer in a second column clear of the money panel's width,
        // the slayer panel beside the keystrokes, below the crosshair line.
        POS.put(COMMS, new float[]{0.245f, 0.010f, 1.0f});
        POS.put(ABILITY, new float[]{0.245f, 0.300f, 1.0f});
        POS.put(SLAYER, new float[]{0.120f, 0.620f, 1.0f});
        // Sacks: bottom right, under Hypixel's sidebar and clear of the hotbar.
        POS.put(SACKS, new float[]{0.790f, 0.760f, 1.0f});
        // Shadow Prison panels sit where the Hypixel-only ones do (money,
        // commissions, ability, slayer, sacks): the two sets are never shown on
        // the same server, and the editor only offers the set that applies
        // (editorShows). Shadow's own scoreboard owns the middle of the right edge.
        POS.put(SP_MINING, new float[]{0.006f, 0.410f, 1.0f});
        POS.put(SP_EARNINGS, new float[]{0.245f, 0.010f, 1.0f});
        POS.put(SP_RANKUP, new float[]{0.245f, 0.300f, 1.0f});
        POS.put(SP_BOOSTS, new float[]{0.245f, 0.450f, 1.0f});
        POS.put(SP_CONTRACT, new float[]{0.120f, 0.620f, 1.0f});
        POS.put(SP_SKILLS, new float[]{0.790f, 0.760f, 1.0f});
        POS.put(SP_PROFILE, new float[]{0.500f, 0.060f, 1.0f});
        SIZE.put(INFO, new int[]{90, 60});
        SIZE.put(CPS, new int[]{70, 12});
        SIZE.put(KEYS, new int[]{52, 64});
        SIZE.put(BPS, new int[]{80, 12});
        SIZE.put(MONEY, new int[]{110, 48});
        SIZE.put(TODO, new int[]{120, 30});
        SIZE.put(COMMS, new int[]{150, 60});
        SIZE.put(ABILITY, new int[]{150, 14});
        SIZE.put(SLAYER, new int[]{150, 50});
        SIZE.put(SACKS, new int[]{120, 40});
        SIZE.put(SP_MINING, new int[]{130, 60});
        SIZE.put(SP_EARNINGS, new int[]{130, 70});
        SIZE.put(SP_RANKUP, new int[]{130, 40});
        SIZE.put(SP_BOOSTS, new int[]{130, 36});
        SIZE.put(SP_CONTRACT, new int[]{130, 50});
        SIZE.put(SP_SKILLS, new int[]{130, 50});
        SIZE.put(SP_PROFILE, new int[]{150, 110});
        LABEL.put(SP_MINING, "Mining (Shadow)");
        LABEL.put(SP_EARNINGS, "Earnings (Shadow)");
        LABEL.put(SP_RANKUP, "Rank-up (Shadow)");
        LABEL.put(SP_BOOSTS, "Boosts (Shadow)");
        LABEL.put(SP_CONTRACT, "Contracts (Shadow)");
        LABEL.put(SP_SKILLS, "Skills (Shadow)");
        LABEL.put(SP_PROFILE, "Profile card (Shadow)");
        LABEL.put(INFO, "Info");
        LABEL.put(CPS, "CPS");
        LABEL.put(KEYS, "Keystrokes");
        LABEL.put(BPS, "Blocks/s");
        LABEL.put(MONEY, "Money");
        LABEL.put(TODO, "To-do");
        LABEL.put(COMMS, "Commissions");
        LABEL.put(ABILITY, "Pickaxe ability");
        LABEL.put(SLAYER, "Slayer");
        LABEL.put(SACKS, "Sacks");
    }

    private HudLayout() {
    }

    public static Iterable<String> ids() {
        return POS.keySet();
    }

    public static String label(String id) {
        return LABEL.getOrDefault(id, id);
    }

    public static int x(String id, int screenW) {
        return Math.round(POS.get(id)[0] * screenW);
    }

    public static int y(String id, int screenH) {
        return Math.round(POS.get(id)[1] * screenH);
    }

    public static float scale(String id) {
        return POS.get(id)[2];
    }

    public static void setScale(String id, float s) {
        POS.get(id)[2] = Math.max(0.5f, Math.min(3.0f, s));
    }

    /** Move by pixels, clamped so a piece can never be dragged off-screen. */
    public static void move(String id, int px, int py, int screenW, int screenH) {
        int[] wh = size(id);
        float sc = scale(id);
        int nx = Math.max(0, Math.min(screenW - Math.round(wh[0] * sc), px));
        int ny = Math.max(0, Math.min(screenH - Math.round(wh[1] * sc), py));
        float[] p = POS.get(id);
        p[0] = screenW <= 0 ? 0 : (float) nx / screenW;
        p[1] = screenH <= 0 ? 0 : (float) ny / screenH;
    }

    /**
     * Where to draw, in real (unscaled) GUI pixels, kept fully on screen.
     *
     * <p>Positions are fractions, so a panel placed against the right edge in
     * a wide window can hang off it in a narrow one, and a to-do list that
     * grows by three lines can run off the bottom. Clamping at draw time is
     * what keeps it readable without rewriting the saved position.
     */
    public static int[] place(String id, int screenW, int screenH) {
        int[] wh = size(id);
        float sc = scale(id);
        int w = Math.round(wh[0] * sc);
        int h = Math.round(wh[1] * sc);
        int x = Math.max(0, Math.min(screenW - w, x(id, screenW)));
        int y = Math.max(0, Math.min(screenH - h, y(id, screenH)));
        return new int[]{x, y};
    }

    /** Whether this element is switched on (HudConfig owns the flags). */
    public static boolean enabled(String id) {
        HudConfig c = HudConfig.get();
        return switch (id) {
            case INFO -> c.enabled;
            case CPS -> c.cpsEnabled;
            case KEYS -> c.keysEnabled;
            case BPS -> c.bpsEnabled;
            case MONEY -> c.moneyEnabled;
            case TODO -> c.todoEnabled;
            case COMMS -> c.commsEnabled;
            case SLAYER -> c.slayerEnabled;
            case ABILITY -> c.abilityEnabled;
            case SACKS -> c.sacksEnabled;
            case SP_MINING -> c.spMiningEnabled;
            case SP_EARNINGS -> c.spEarningsEnabled;
            case SP_RANKUP -> c.spRankupEnabled;
            case SP_BOOSTS -> c.spBoostsEnabled;
            case SP_CONTRACT -> c.spContractEnabled;
            case SP_SKILLS -> c.spSkillsEnabled;
            case SP_PROFILE -> c.spProfileEnabled;
            default -> true;
        };
    }

    /**
     * Whether the HUD editor offers this element here. The Hypixel-only panels
     * and the Shadow Prison panels share screen space by design (they never
     * draw on the same server), so the editor shows only the set that applies:
     * no Shadow boxes on SkyBlock, no Hypixel boxes once a Shadow server has
     * said WELCOME. Everywhere else (singleplayer, other servers) both sets.
     */
    public static boolean editorShows(String id) {
        if (id.startsWith("sp-")) {
            return !Skyblock.inSkyblock();
        }
        return switch (id) {
            case MONEY, COMMS, SLAYER, ABILITY, SACKS -> !ShadowLive.active();
            default -> true;
        };
    }

    /** Flip an element on/off and save. Returns the new state. */
    public static boolean toggle(String id) {
        HudConfig c = HudConfig.get();
        boolean now = !enabled(id);
        switch (id) {
            case INFO -> c.enabled = now;
            case CPS -> c.cpsEnabled = now;
            case KEYS -> c.keysEnabled = now;
            case BPS -> c.bpsEnabled = now;
            case MONEY -> c.moneyEnabled = now;
            case TODO -> c.todoEnabled = now;
            case COMMS -> c.commsEnabled = now;
            case SLAYER -> c.slayerEnabled = now;
            case ABILITY -> c.abilityEnabled = now;
            case SACKS -> c.sacksEnabled = now;
            case SP_MINING -> c.spMiningEnabled = now;
            case SP_EARNINGS -> c.spEarningsEnabled = now;
            case SP_RANKUP -> c.spRankupEnabled = now;
            case SP_BOOSTS -> c.spBoostsEnabled = now;
            case SP_CONTRACT -> c.spContractEnabled = now;
            case SP_SKILLS -> c.spSkillsEnabled = now;
            case SP_PROFILE -> c.spProfileEnabled = now;
            default -> {
                return true;
            }
        }
        c.save();
        return now;
    }

    /**
     * Report the size an element drew at, in UNSCALED units - the editor and
     * {@link #place} multiply by the element's scale themselves. (Info and CPS
     * used to report already-scaled sizes, so at scale 2 their editor boxes
     * were drawn at 4x.)
     */
    public static void reportSize(String id, int w, int h) {
        SIZE.put(id, new int[]{Math.max(8, w), Math.max(8, h)});
    }

    public static int[] size(String id) {
        int[] wh = SIZE.get(id);
        return wh == null ? new int[]{60, 20} : wh;
    }

    // ------------------------------------------------------------- file

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("auraqol-layout.json");
    }

    /**
     * Read the file if it is there.
     *
     * <p>Hand-parsed for the same reason the rest of this mod's config is:
     * three numbers per element is not worth a dependency, and a total parser
     * that can never throw is worth more than a tidy one that can. Anything
     * unreadable leaves the defaults in place.
     */
    public static void load() {
        Path p = path();
        if (!Files.exists(p)) {
            return;
        }
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            for (String id : POS.keySet()) {
                Float fx = num(s, id, "x");
                Float fy = num(s, id, "y");
                Float fs = num(s, id, "scale");
                float[] cur = POS.get(id);
                if (fx != null) {
                    cur[0] = Math.max(0f, Math.min(1f, fx));
                }
                if (fy != null) {
                    cur[1] = Math.max(0f, Math.min(1f, fy));
                }
                if (fs != null) {
                    cur[2] = Math.max(0.5f, Math.min(3.0f, fs));
                }
            }
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] layout unreadable, using defaults: {}", e.toString());
        }
    }

    private static Float num(String json, String id, String key) {
        int at = json.indexOf("\"" + id + "\"");
        if (at < 0) {
            return null;
        }
        int end = json.indexOf('}', at);
        int k = json.indexOf("\"" + key + "\"", at);
        if (k < 0 || (end > 0 && k > end)) {
            return null;
        }
        int colon = json.indexOf(':', k);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        StringBuilder sb = new StringBuilder();
        while (i < json.length() && (Character.isDigit(json.charAt(i))
                || json.charAt(i) == '.' || json.charAt(i) == '-'
                || json.charAt(i) == 'E' || json.charAt(i) == 'e')) {
            sb.append(json.charAt(i));
            i++;
        }
        try {
            return Float.valueOf(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void save() {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"_comment\": \"Positions are fractions of the screen (0-1) ")
          .append("so they hold up at any resolution. Move things in game instead: ")
          .append("press the AuraQoL 'Edit HUD' key.\",\n");
        int i = 0;
        for (Map.Entry<String, float[]> e : POS.entrySet()) {
            float[] v = e.getValue();
            sb.append(String.format(java.util.Locale.ROOT, "  \"%s\": { \"x\": %.4f, \"y\": %.4f, \"scale\": %.2f }",
                    e.getKey(), v[0], v[1], v[2]));
            sb.append(++i < POS.size() ? ",\n" : "\n");
        }
        sb.append("}\n");
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AuraQoL.LOG.warn("[auraqol] could not save layout: {}", e.toString());
        }
    }
}
