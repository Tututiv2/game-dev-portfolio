package com.skyclone.mod.combatzones;

import java.util.Random;

/**
 * Our own flight for the nest dragon. Pure Java (no Bukkit) so the unit tests fly the shipping model.
 *
 * <p>Why it exists (playtest-endgame 2026-10-03): the vanilla dragon AI plans its flight over a ring
 * of path nodes built around the world ORIGIN. The nest is at (52, -42) in a NORMAL world with no
 * vanilla dragon fight, so the dragon circled the origin instead of the nest, and its landing
 * approach (DragonPhase 2) found no path and froze for good at (-48, 104, -12), 110 blocks from
 * the podium, where nobody could hit it. Now the dragon is held in vanilla's HOVER phase (which
 * Paper does not move at all) and {@link DragonFight} teleports it every tick to this model.
 *
 * <p>All coordinates are absolute; the centre is the podium top. The cycle is
 * CIRCLE (orbit high over the crystal towers, 10-17 s) -> SWOOP (level over the tower tops, then a
 * dive onto the dais) -> PERCH (hover in melee reach over the podium, 6 s) -> CIRCLE. Rush puts it
 * in CHARGE: it physically flies at the target, and only touching the target deals the damage.
 */
public final class DragonFlight {

    public enum Mode { CIRCLE, SWOOP, PERCH, CHARGE }

    /** Orbit radius / height over the podium. The crystal towers stand at radius 26, tops up to +29. */
    public static final double ORBIT_RADIUS = 38, ORBIT_ALT = 32;
    /** Hover height over the podium top while perched: the body sits in sword reach of the dais. */
    public static final double PERCH_ALT = 2.5;
    /** The ring the crystal towers stand in, and the height that clears their caged tops. */
    public static final double TOWER_IN = 19, TOWER_OUT = 34, TOWER_ALT = 31;
    /** Never lower than this under the podium top (the crater floor is 3 below it). */
    public static final double FLOOR = -1;
    /** Charge targets farther than this (horizontally) from the podium are pulled in to it. */
    public static final double LEASH = 60;
    /** Blocks per tick at speed multiplier 1 (15 blocks/s). */
    public static final double BASE_SPEED = 0.75;
    /** A charge connects when the dragon is this close to its target. */
    public static final double CONTACT = 6;
    public static final int CIRCLE_MIN = 200, CIRCLE_MAX = 340, SWOOP_MAX = 260, PERCH_TICKS = 120, CHARGE_MAX = 120;

    public final double cx, cy, cz, speed;
    public double x, y, z, vx, vy, vz;
    private Mode mode = Mode.CIRCLE;
    private int modeTicks, modeLength;
    private int dir;
    private double tx, ty, tz;
    private float yaw;
    private final Random rng;

    /**
     * @param speedMult the variant's speed (Young 2.25, Old 0.8); clamped to 0.6..1.8 so a fast
     *                  dragon still reads as flight rather than a blink.
     */
    public DragonFlight(double cx, double cy, double cz, double x, double y, double z, double speedMult, Random rng) {
        this.cx = cx; this.cy = cy; this.cz = cz;
        this.x = x; this.y = y; this.z = z;
        this.speed = BASE_SPEED * Math.max(0.6, Math.min(1.8, speedMult));
        this.rng = rng;
        this.dir = rng.nextBoolean() ? 1 : -1;
        setMode(Mode.CIRCLE);
    }

    public Mode mode() { return mode; }

    public int modeTicks() { return modeTicks; }

    /** Dragon yaw: the vanilla dragon's head points along (sin yaw, -cos yaw), the reverse of other mobs. */
    public float yaw() { return yaw; }

    public static float dragonYaw(double vx, double vz) { return (float) Math.toDegrees(Math.atan2(vx, -vz)); }

    public double horizontalFromCentre() { return Math.hypot(x - cx, z - cz); }

    /** Fly at a target (Rush). The caller keeps {@link #retarget} up to date and checks {@link #inContact}. */
    public void charge(double tx, double ty, double tz) {
        setMode(Mode.CHARGE);
        retarget(tx, ty, tz);
    }

    public void retarget(double tx, double ty, double tz) {
        double h = Math.hypot(tx - cx, tz - cz);
        if (h > LEASH) { tx = cx + (tx - cx) / h * LEASH; tz = cz + (tz - cz) / h * LEASH; }
        this.tx = tx; this.ty = ty; this.tz = tz;
    }

    public boolean inContact() {
        return mode == Mode.CHARGE && dist(x, y, z, tx, ty + 1, tz) <= CONTACT;
    }

    /** End a charge (hit or called off): climb back to the orbit. */
    public void endCharge() { if (mode == Mode.CHARGE) setMode(Mode.CIRCLE); }

    /** Watchdog: the entity is not where the model says. Continue from where it really is. */
    public void resync(double x, double y, double z) {
        this.x = x; this.y = y; this.z = z;
        vx = vy = vz = 0;
        setMode(Mode.CIRCLE);
    }

    private void setMode(Mode m) {
        mode = m;
        modeTicks = 0;
        modeLength = switch (m) {
            case CIRCLE -> CIRCLE_MIN + rng.nextInt(CIRCLE_MAX - CIRCLE_MIN + 1);
            case SWOOP -> SWOOP_MAX;
            case PERCH -> PERCH_TICKS;
            case CHARGE -> CHARGE_MAX;
        };
        if (m == Mode.CIRCLE && rng.nextInt(4) == 0) dir = -dir;
    }

    /** Advance one server tick. */
    public void tick() {
        modeTicks++;
        double hd = horizontalFromCentre();
        double px, py, pz, sp = speed, steer = 0.12;
        boolean arrive = true;
        switch (mode) {
            case CIRCLE -> {
                if (hd < TOWER_IN + 1 && y < cy + TOWER_ALT - 2) {
                    // leaving the dais: climb almost straight up before crossing the tower ring
                    double ux = hd > 0.5 ? (x - cx) / hd : Math.cos(dir), uz = hd > 0.5 ? (z - cz) / hd : Math.sin(dir);
                    px = x + ux * 3; py = cy + ORBIT_ALT; pz = z + uz * 3;
                    arrive = false;
                } else {
                    double a = Math.atan2(z - cz, x - cx) + dir * 0.45;          // a point ahead on the ring
                    px = cx + Math.cos(a) * ORBIT_RADIUS;
                    pz = cz + Math.sin(a) * ORBIT_RADIUS;
                    py = cy + ORBIT_ALT + 2.5 * Math.sin(modeTicks / 30.0);
                    arrive = false;
                }
                if (modeTicks >= modeLength) setMode(Mode.SWOOP);
            }
            case SWOOP -> {
                px = cx; pz = cz;
                // level over the tower tops, then the dive once inside the ring
                py = hd > TOWER_IN ? Math.max(cy + TOWER_ALT + 1, Math.min(y, cy + ORBIT_ALT)) : cy + PERCH_ALT;
                sp = speed * 1.15;
                if (dist(x, y, z, cx, cy + PERCH_ALT, cz) < 1.5) setMode(Mode.PERCH);
                else if (modeTicks >= modeLength) setMode(Mode.CIRCLE);
            }
            case PERCH -> {
                px = cx; pz = cz;
                py = cy + PERCH_ALT + 0.4 * Math.sin(modeTicks / 12.0);
                sp = speed * 0.3;
                steer = 0.25;
                if (modeTicks >= modeLength) setMode(Mode.CIRCLE);
            }
            default -> {   // CHARGE
                px = tx; py = ty + 1; pz = tz;
                sp = speed * 1.6;
                steer = 0.2;
                arrive = false;
                if (modeTicks >= modeLength) setMode(Mode.CIRCLE);
            }
        }
        py = Math.max(py, cy + FLOOR);

        double dx = px - x, dy = py - y, dz = pz - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double want = arrive ? sp * Math.min(1, d / 6.0) : sp;
        double wx = 0, wy = 0, wz = 0;
        if (d > 1e-6) { wx = dx / d * want; wy = dy / d * want; wz = dz / d * want; }
        vx += (wx - vx) * steer;
        vy += (wy - vy) * steer;
        vz += (wz - vz) * steer;
        double v = Math.sqrt(vx * vx + vy * vy + vz * vz), cap = Math.max(sp, 0.05);
        if (v > cap) { vx *= cap / v; vy *= cap / v; vz *= cap / v; }
        x += vx; y += vy; z += vz;
        if (y < cy + FLOOR) { y = cy + FLOOR; if (vy < 0) vy = 0; }

        double h = Math.hypot(vx, vz);
        if (h > 0.05) yaw = dragonYaw(vx, vz);
        else yaw = (float) ((yaw + 0.8) % 360);                                 // perched: look around
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
