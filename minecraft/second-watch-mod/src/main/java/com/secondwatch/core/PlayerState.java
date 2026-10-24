package com.secondwatch.core;

/**
 * The slice of a player the core needs. Deliberately tiny and deliberately free
 * of anything Minecraft-shaped - positions are plain doubles, not Vec3, so this
 * whole package can be tested with no game running.
 *
 * @param id             player id
 * @param x              world x
 * @param z              world z
 * @param lightLevel     block light where they stand, 0..15
 * @param lastSpokeMs    run-relative time they last finished speaking, or -1
 * @param seesPartner    whether the real partner is currently in view
 * @param alive          false once written out
 */
public record PlayerState(
        String id,
        double x,
        double z,
        int lightLevel,
        long lastSpokeMs,
        boolean seesPartner,
        boolean alive) {

    public PlayerState {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("player needs an id");
        if (lightLevel < 0 || lightLevel > 15) {
            throw new IllegalArgumentException("light level out of range: " + lightLevel);
        }
    }

    public double distanceTo(PlayerState other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean spokeWithin(long nowMs, long windowMs) {
        return lastSpokeMs >= 0 && (nowMs - lastSpokeMs) <= windowMs;
    }
}
