package com.aisky.showcam;

/**
 * Pure camera maths, no Minecraft imports, so {@code check/CamMathCheck.java}
 * can exercise it with plain javac.
 *
 * <p>Minecraft's angle conventions (verified against {@code Camera.setRotation}
 * in 26.1.2, which builds the view as {@code rotationYXZ(PI - yRot, -xRot, 0)}
 * applied to forward {@code (0,0,-1)}):
 * <ul>
 *   <li>yaw (yRot) in degrees; the forward vector is {@code (-sin yaw, 0, cos yaw)},
 *       so yaw 0 looks south (+Z) and yaw 90 looks west (-X);</li>
 *   <li>pitch (xRot) in degrees, <b>positive looks down</b>.</li>
 * </ul>
 */
public final class CamMath {

    private CamMath() {
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Ken Perlin's smootherstep: zero velocity AND zero acceleration at both ends. */
    public static double ease(double t) {
        t = clamp01(t);
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    /**
     * Frame-rate independent exponential approach factor: applying
     * {@code x += (target - x) * alpha(dt, tau)} every frame converges at the
     * same wall-clock speed at 30 fps and at 144 fps.
     */
    public static double alpha(double dt, double tau) {
        if (tau <= 0.0) {
            return 1.0;
        }
        if (dt <= 0.0) {
            return 0.0;
        }
        return 1.0 - Math.exp(-dt / tau);
    }

    /** Wraps degrees into [-180, 180). */
    public static double wrapDeg(double d) {
        d = d % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** Moves an angle toward a target along the short way round. */
    public static double approachDeg(double cur, double target, double a) {
        return cur + wrapDeg(target - cur) * a;
    }

    /** Minecraft yaw (degrees) that looks along (dx, dz). */
    public static double yawTo(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Minecraft pitch (degrees, positive = down) that looks along (dx, dy, dz). */
    public static double pitchTo(double dx, double dy, double dz) {
        return -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    public static double fwdX(double yawDeg) {
        return -Math.sin(Math.toRadians(yawDeg));
    }

    public static double fwdZ(double yawDeg) {
        return Math.cos(Math.toRadians(yawDeg));
    }

    /** The viewer's right-hand side for a yaw: forward x up. Yaw 0 (south) -> west (-X). */
    public static double rightX(double yawDeg) {
        return -Math.cos(Math.toRadians(yawDeg));
    }

    public static double rightZ(double yawDeg) {
        return -Math.sin(Math.toRadians(yawDeg));
    }

    /**
     * Speed envelope for a continuous move: ramps 0 -> 1 over {@code edge}
     * seconds at the start and, when {@code total > 0}, 1 -> 0 over the last
     * {@code edge} seconds, then stays 0. {@code total <= 0} means "forever".
     */
    public static double envelope(double t, double total, double edge) {
        if (edge <= 0.0) {
            return total > 0.0 && t >= total ? 0.0 : 1.0;
        }
        if (total <= 0.0) {
            return ease(t / edge);
        }
        if (t >= total) {
            return 0.0;
        }
        double e = Math.min(edge, total / 2.0);
        return Math.min(ease(t / e), ease((total - t) / e));
    }
}
