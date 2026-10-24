package com.aisky.showcam;

/**
 * Everything that decides whether ShowCam does anything at all.
 *
 * <p>Deliberately free of Minecraft and Fabric imports: the title mixin reads
 * {@link #TITLE} from inside the {@code Minecraft} constructor (the window is
 * created there), long before mod entrypoints run, so this class must be
 * loadable at that point without dragging anything else in.
 *
 * <p>The Fabric-26.1.2 instance is also played by the owner himself, so the
 * default is <b>off</b>: with neither {@code SHOWCAM=1} nor {@code SHOWCAM_TITLE}
 * in the game's environment, every mixin returns immediately and the entrypoint
 * logs one line and returns.
 */
public final class Flags {

    /** {@code SHOWCAM_TITLE} / {@code -Dshowcam.title}: the window title, or null to leave vanilla's. */
    public static final String TITLE = title();

    /** {@code SHOWCAM=1} / {@code -Dshowcam.enabled=true}: the camera client (HTTP server + camera override). */
    public static final boolean CAMERA = camera();

    /** Anything at all active. */
    public static final boolean ANY = TITLE != null || CAMERA;

    private Flags() {
    }

    private static String title() {
        String v = setting("SHOWCAM_TITLE", "showcam.title", null);
        return v == null || v.isBlank() ? null : v;
    }

    private static boolean camera() {
        String env = System.getenv("SHOWCAM");
        if (env != null && !env.isBlank()) {
            String e = env.trim();
            return !e.equals("0") && !e.equalsIgnoreCase("false") && !e.equalsIgnoreCase("off");
        }
        return Boolean.getBoolean("showcam.enabled");
    }

    /** System property first (so a dev run can override), then environment, then default. */
    public static String setting(String env, String prop, String dflt) {
        String v = System.getProperty(prop);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        v = System.getenv(env);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return dflt;
    }

    public static boolean flag(String env, String prop, boolean dflt) {
        String v = setting(env, prop, null);
        if (v == null) {
            return dflt;
        }
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes");
    }

    public static int port() {
        try {
            return Integer.parseInt(setting("SHOWCAM_PORT", "showcam.port", "25660"));
        } catch (NumberFormatException e) {
            return 25660;
        }
    }

    /** {@code SHOWCAM_TP=0} stops ShowCam from ever sending {@code /tp} for the camera account. */
    public static boolean autoTeleport() {
        return flag("SHOWCAM_TP", "showcam.tp", true);
    }

    /** {@code SHOWCAM_HUD=1} keeps the HUD visible during shots (default: hidden while a shot runs). */
    public static boolean keepHud() {
        return flag("SHOWCAM_HUD", "showcam.hud", false);
    }
}
