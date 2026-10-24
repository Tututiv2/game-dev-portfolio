package com.thestill.region;

/**
 * What a region <em>is</em>, structurally.
 *
 * <p>Deliberately not a list of game states. A Warren is not a type — it is what
 * a {@link #SHAFT} becomes above 1,400 heat, and it stops being one when the
 * room cools. Encoding it here would make a reversible condition into a
 * permanent identity, and then "clear the Warren by cooling the room" would need
 * a type migration instead of a subtraction.
 */
public enum RegionType {

    /** Safe ground above the fog. Spawn and shared services. */
    HUB,

    /** One level of the Depths, D1..D26. The unit players unlock and warp to. */
    DEPTH,

    /**
     * A room inside a Depth. The unit room heat is measured in, because the
     * design reads history off individual doorways rather than whole levels.
     */
    SHAFT,

    /** A player's claimed platform over the fog. */
    RIG;

    public String id() {
        return name().toLowerCase();
    }

    public static RegionType parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (RegionType type : values()) {
            if (type.name().equalsIgnoreCase(raw)) {
                return type;
            }
        }
        return null;
    }
}
