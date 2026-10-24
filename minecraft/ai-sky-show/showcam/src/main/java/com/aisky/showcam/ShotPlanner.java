package com.aisky.showcam;

/**
 * Turns a {@link ShotSpec} plus the target's current position into the IDEAL
 * camera for this frame: where the lens is, what point it looks at, and its FOV.
 * Pure Java, no Minecraft imports - {@code check/CamMathCheck.java} runs the
 * shipping planner at several frame rates with no game.
 *
 * <p>The ideal pose is already a smooth function of time (every move is eased
 * at both ends); {@link CamDirector} additionally low-passes it so that a
 * change of shot glides instead of cutting.
 *
 * <p>"Bearing" below is the horizontal angle (radians, {@code atan2(dz, dx)})
 * from the target to the camera. Every shot starts from the bearing the camera
 * is ALREADY at, so consecutive shots on one subject continue from where the
 * last one left off rather than swinging round to a fixed side.
 */
public final class ShotPlanner {

    /** Orbit angular speed at speed=1: one full turn every 20 seconds. */
    public static final double ORBIT_DEG_PER_S = 18.0;

    public final ShotSpec spec;

    private double t;
    private boolean started;
    private double bearing;
    private double orbitAngle;
    private double facing = Double.NaN;
    private double staticX;
    private double staticY;
    private double staticZ;

    // Ideal output of the last step.
    public double camX;
    public double camY;
    public double camZ;
    public double lookX;
    public double lookY;
    public double lookZ;
    public double fov;

    public ShotPlanner(ShotSpec spec) {
        this.spec = spec;
    }

    public double elapsed() {
        return t;
    }

    public double progress() {
        return spec.seconds > 0 ? CamMath.clamp01(t / spec.seconds) : 0.0;
    }

    public boolean done() {
        return spec.seconds > 0 && t >= spec.seconds;
    }

    /**
     * @param dt         seconds since the previous frame (0 on the first)
     * @param fx         focus point (target's face/chest, or the requested point)
     * @param targetYaw  the target's facing yaw in degrees, NaN when it has none
     * @param curX       where the camera currently is (used once, to pick the starting bearing)
     */
    public void step(double dt, double fx, double fy, double fz, double targetYaw, double curX, double curZ) {
        if (!started) {
            started = true;
            double dx = curX - fx;
            double dz = curZ - fz;
            if (dx * dx + dz * dz > 0.25) {
                bearing = Math.atan2(dz, dx);
            } else if (!Double.isNaN(targetYaw)) {
                // Camera is on top of the target: start in front of its face.
                bearing = Math.atan2(CamMath.fwdZ(targetYaw), CamMath.fwdX(targetYaw));
            } else {
                bearing = 0.0;
            }
            orbitAngle = bearing;
            facing = Double.isNaN(targetYaw) ? CamMath.yawTo(Math.cos(bearing), Math.sin(bearing)) : targetYaw;
            staticX = fx + Math.cos(bearing) * spec.radius;
            staticY = fy + spec.height;
            staticZ = fz + Math.sin(bearing) * spec.radius;
        } else {
            t += Math.max(0.0, dt);
        }
        if (!Double.isNaN(targetYaw)) {
            // Players flick their heads; the camera must not. ~0.5 s lag on facing.
            facing = CamMath.approachDeg(facing, targetYaw, CamMath.alpha(dt, 0.5));
        }

        double r = spec.radius;
        double h = spec.height;
        double e = spec.seconds > 0 ? CamMath.ease(t / spec.seconds) : 0.0;
        fov = spec.fov;
        lookX = fx;
        lookY = fy;
        lookZ = fz;

        switch (spec.type) {
            case ORBIT -> {
                double edge = spec.seconds > 0 ? Math.min(1.5, spec.seconds / 3.0) : 1.5;
                double omega = Math.toRadians(ORBIT_DEG_PER_S * spec.speed);
                orbitAngle += omega * CamMath.envelope(t, spec.seconds, edge) * Math.max(0.0, dt);
                camX = fx + Math.cos(orbitAngle) * r;
                camY = fy + h;
                camZ = fz + Math.sin(orbitAngle) * r;
            }
            case PUSHIN -> {
                // In front of the face, closing in, lens narrowing: the classic "realisation" push.
                double dirX;
                double dirZ;
                if (Double.isNaN(targetYaw)) {
                    dirX = Math.cos(bearing);
                    dirZ = Math.sin(bearing);
                } else {
                    dirX = CamMath.fwdX(facing);
                    dirZ = CamMath.fwdZ(facing);
                }
                double dist = CamMath.lerp(r, Math.max(1.0, r * 0.3), e);
                camX = fx + dirX * dist;
                camY = fy + CamMath.lerp(h, 0.05, e);
                camZ = fz + dirZ * dist;
                fov = CamMath.lerp(spec.fov, spec.fov * 0.55, e);
            }
            case FOLLOW -> {
                // Over the right shoulder, looking where the target is going.
                double fwX = CamMath.fwdX(facing);
                double fwZ = CamMath.fwdZ(facing);
                double shoulder = 0.9 * (r / 3.5);
                camX = fx - fwX * r + CamMath.rightX(facing) * shoulder;
                camY = fy + h;
                camZ = fz - fwZ * r + CamMath.rightZ(facing) * shoulder;
                lookX = fx + fwX * 8.0;
                lookY = fy - 0.2;
                lookZ = fz + fwZ * 8.0;
            }
            case CRANE -> {
                // Starts low and close, rises up and back to reveal the surroundings.
                double dist = CamMath.lerp(r * 0.5, r * 1.6, e);
                camX = fx + Math.cos(bearing) * dist;
                camY = fy + CamMath.lerp(-0.8, h, e);
                camZ = fz + Math.sin(bearing) * dist;
            }
            case DOLLY -> {
                // Tracks sideways past the target along a straight line, always looking at it.
                double side = r * 1.6 * spec.speed;
                double sx = -Math.sin(bearing);
                double sz = Math.cos(bearing);
                double off = CamMath.lerp(-side, side, e);
                camX = fx + Math.cos(bearing) * r + sx * off;
                camY = fy + h;
                camZ = fz + Math.sin(bearing) * r + sz * off;
            }
            case STATIC -> {
                // Locked-off tripod that pans to keep the target framed.
                camX = staticX;
                camY = staticY;
                camZ = staticZ;
            }
            default -> {
                camX = curX;
                camY = fy;
                camZ = curZ;
            }
        }
    }
}
