package org.aincrad.plugin;

/**
 * Every world dimension in one place, at the project's selected <b>Compact 1:4</b> profile.
 *
 * <p>The build originally used the spec's true 1:1 profile: a 10,000-block floor with a
 * 1,000-block town. That is faithful but produced 308,028 chunks (~3 hours of generation) and a
 * town so large that the surrounding floor could never realistically be filled, so the owner chose
 * to rescale. At 1:4 the floor is 2,500 blocks across and ~19,200 chunks, which can be generated
 * completely in about ten minutes — the difference between a town on an endless flat plate and an
 * actual finished floating island.</p>
 *
 * <p><b>Horizontal geography scales; architecture and vertical do not.</b> Dividing a 9x14 house by
 * four gives a 2x4 box, and the spec is explicit: "never uniformly shrink important interiors below
 * playable minimums". So the floor, the town envelope and the region positions shrink by 4, while
 * building footprints, storey heights, the Y=320 surface datum and Floor Two's Y=420 plate stay
 * exactly as they were. The practical effect is a denser, more walkable town with ~200 buildings
 * rather than a sprawling one with 1,150.</p>
 */
public final class WorldScale {

    private WorldScale() {
    }

    /** Horizontal divisor against the spec's 1:1 figures. */
    public static final int DIVISOR = 4;

    // ---- Floor shell ----
    /** 5,000 / 4. */
    public static final int FLOOR_RADIUS = 1250;
    public static final int FLOOR_DIAMETER = FLOOR_RADIUS * 2;
    /** Outermost walkable ring, 4,976 / 4. */
    public static final int PLAYABLE_RADIUS = 1244;
    /** Rim overlook band inner edge, 4,936 / 4. */
    public static final int RIM_INNER = 1234;

    // ---- Vertical: unchanged from 1:1, deliberately ----
    public static final int SURFACE_Y = 320;
    public static final int BASELINE_GRASS_Y = 319;
    public static final int FLOOR_TWO_PLATE_Y = 420;
    public static final int MIN_Y = -64;
    /** Underside of the shell directly beneath the town, from the layout formula at this radius. */
    public static final int SHELL_UNDERSIDE_Y = 227;

    // ---- Town of Beginnings ----
    /** Arc centre, 4,500 / 4. */
    public static final int TOWN_CX = 0;
    public static final int TOWN_CZ = 1125;
    /** Wall radius, 500 / 4. */
    public static final int TOWN_R = 125;
    /** Straight northern wall, 4,000 / 4. */
    public static final int NORTH_WALL_Z = 1000;
    public static final int SIDE_WALL_X = 125;
    public static final int SOUTH_EDGE_Z = 1250;

    // ---- Gates (on the north wall) ----
    public static final int NORTH_GATE_X = 0;
    public static final int NW_GATE_X = -95;
    public static final int NE_GATE_X = 95;
    public static final int GATE_Z = NORTH_WALL_Z;

    // ---- Central Plaza: 4,416 / 4 ----
    public static final int PLAZA_CX = 0;
    public static final int PLAZA_CZ = 1104;
    public static final int PLAZA_PAVED_R = 30;
    public static final int PLAZA_ARCADE_INNER_R = 32;
    public static final int PLAZA_ARCADE_OUTER_R = 40;
    public static final int PLAZA_DECK_Y = 322;
    /** Documented arrival point; a player standing on the deck occupies Y=323. */
    public static final int ARRIVAL_Y = 323;

    // ---- Clock tower, over the Teleport Gate ----
    public static final int TOWER_HALF = 5;          // 11x11 footprint
    public static final int TOWER_HEIGHT = 30;       // deck -> apex

    // ---- Black Iron Palace: 4,136 / 4 ----
    public static final int PALACE_CX = 0;
    public static final int PALACE_CZ = 1034;
    public static final int PALACE_PODIUM_HALF_W = 50;   // 100 wide
    public static final int PALACE_PODIUM_HALF_D = 20;   // 40 deep
    public static final int PALACE_HALL_HALF_W = 35;     // 70 wide
    public static final int PALACE_HALL_HALF_D = 15;     // 30 deep
    public static final int PALACE_DRUM_R = 22;
    public static final int PALACE_DECK_Y = 328;
    public static final int PALACE_HALL_TOP_Y = 348;
    public static final int PALACE_DRUM_TOP_Y = 356;
    public static final int PALACE_APEX_Y = 368;

    // ---- Processional avenue ----
    public static final int AVENUE_HALF_W = 6;       // 13 wide
    public static final int AVENUE_Z_START = PALACE_CZ + PALACE_PODIUM_HALF_D + 7;  // clear of the stair
    public static final int AVENUE_Z_END = PLAZA_CZ - PLAZA_PAVED_R + 2;

    /** Scale a 1:1 horizontal figure into this profile. */
    public static int h(int oneToOne) {
        return Math.round(oneToOne / (float) DIVISOR);
    }

    /** Chunks intersecting the floor disc - the full generation job at this scale. */
    public static int approximateFloorChunks() {
        return (int) Math.round(Math.PI * FLOOR_RADIUS * FLOOR_RADIUS / 256.0);
    }
}
