package com.thestill.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Turning a {@link Location} into a string and back, for storage in a state bag. */
public final class Locations {

    private Locations() {
    }

    /** {@code "world,12.5,64.0,-8.5,90.0,0.0"} */
    public static String serialise(Location location) {
        World world = location.getWorld();
        return (world == null ? "world" : world.getName())
                + "," + location.getX()
                + "," + location.getY()
                + "," + location.getZ()
                + "," + location.getYaw()
                + "," + location.getPitch();
    }

    /**
     * @return the location, or null if the string is malformed or names a world
     *         that is not loaded — callers are expected to fall back rather than
     *         teleport someone into the void
     */
    public static Location parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                return null;
            }
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
