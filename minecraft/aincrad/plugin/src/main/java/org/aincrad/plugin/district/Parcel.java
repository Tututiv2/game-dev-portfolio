package org.aincrad.plugin.district;

/**
 * One non-overlapping building plot. {@code frontage} is the side the front door opens onto, which
 * is always the street the parcel was subdivided from - so a door can never open into a wall or a
 * neighbour.
 */
public record Parcel(
        String id,
        String districtId,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        Facing frontage,
        ParcelRole role,
        int variant
) {
    public enum Facing {NORTH, SOUTH, EAST, WEST}

    public enum ParcelRole {SHOP, INN, WORKSHOP, HOME, CIVIC}

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public int centreX() {
        return (minX + maxX) / 2;
    }

    public int centreZ() {
        return (minZ + maxZ) / 2;
    }

    public boolean overlaps(Parcel other) {
        return minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
