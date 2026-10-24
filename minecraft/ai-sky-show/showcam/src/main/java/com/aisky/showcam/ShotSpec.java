package com.aisky.showcam;

import java.util.Locale;

/**
 * One requested shot (CONTRACT section 11), with per-type defaults resolved.
 * Immutable, pure Java (no Minecraft imports). Built by {@link CamServer} on an
 * HTTP thread and handed to the render thread through a volatile field.
 */
public final class ShotSpec {

    public enum Type {
        ORBIT, PUSHIN, FOLLOW, CRANE, DOLLY, STATIC, STOP;

        public static Type parse(String s) {
            if (s == null) {
                return null;
            }
            String k = s.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (k.equals("PUSH")) {
                k = "PUSHIN";
            }
            for (Type t : values()) {
                if (t.name().equals(k)) {
                    return t;
                }
            }
            return null;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public final Type type;
    /** Player name to frame, or null when the target is a point. */
    public final String player;
    /** Fixed point to frame (NaN when the target is a player). */
    public final double x;
    public final double y;
    public final double z;
    /** Move duration in seconds; {@code <= 0} = runs until replaced (orbit/follow/static only). */
    public final double seconds;
    public final double radius;
    public final double height;
    public final double fov;
    public final double speed;
    /** Snap to the new framing instead of gliding from the previous one. */
    public final boolean cut;
    /** Pull the camera in front of blocks between it and the target. */
    public final boolean clip;

    public ShotSpec(Type type, String player, double x, double y, double z, double seconds, double radius,
                    double height, double fov, double speed, boolean cut, boolean clip) {
        this.type = type;
        this.player = player;
        this.x = x;
        this.y = y;
        this.z = z;
        this.seconds = seconds;
        this.radius = radius;
        this.height = height;
        this.fov = fov;
        this.speed = speed;
        this.cut = cut;
        this.clip = clip;
    }

    /**
     * Fills NaN fields with the per-type default and clamps everything into a
     * sane range, so a typo from the director can never fling the camera 10 km.
     */
    public static ShotSpec resolve(Type type, String player, double x, double y, double z, double seconds,
                                   double radius, double height, double fov, double speed, boolean cut, boolean clip) {
        double dSeconds;
        double dRadius;
        double dHeight;
        switch (type) {
            case ORBIT -> { dSeconds = 0; dRadius = 6; dHeight = 2; }
            case PUSHIN -> { dSeconds = 4; dRadius = 4; dHeight = 0.5; }
            case FOLLOW -> { dSeconds = 0; dRadius = 3.5; dHeight = 0.8; }
            case CRANE -> { dSeconds = 6; dRadius = 5; dHeight = 6; }
            case DOLLY -> { dSeconds = 6; dRadius = 5; dHeight = 1; }
            case STATIC -> { dSeconds = 0; dRadius = 6; dHeight = 2; }
            default -> { dSeconds = 0; dRadius = 0; dHeight = 0; }
        }
        double s = Double.isNaN(seconds) ? dSeconds : clamp(seconds, 0, 600);
        // The move shots need a duration to move over.
        if ((type == Type.PUSHIN || type == Type.CRANE || type == Type.DOLLY) && s <= 0) {
            s = dSeconds;
        }
        return new ShotSpec(type, player, x, y, z, s,
                Double.isNaN(radius) ? dRadius : clamp(radius, 0.5, 64),
                Double.isNaN(height) ? dHeight : clamp(height, -16, 64),
                Double.isNaN(fov) ? 60 : clamp(fov, 10, 120),
                Double.isNaN(speed) ? 1 : clamp(speed, -10, 10),
                cut, clip);
    }

    private static double clamp(double v, double lo, double hi) {
        return CamMath.clamp(v, lo, hi);
    }

    public boolean hasPoint() {
        return !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z);
    }

    public String targetText() {
        if (player != null) {
            return player;
        }
        if (hasPoint()) {
            return String.format(Locale.ROOT, "%.1f,%.1f,%.1f", x, y, z);
        }
        return "-";
    }
}
