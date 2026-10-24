package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;

/**
 * The district map, in one place (north = -z, east = +x). Every pass reads its plot from here so
 * the districts can never drift into each other.
 *
 * <pre>
 *                      N
 *            Ruins        MOUNTAIN (weather station)
 *         Colosseum         |      Mining District -> Coal Mine
 *  Forest &       \         |      /
 *  Camp -- PORTAL PLAZA -- VILLAGE (spawn) ------------- Graveyard + Crypts
 *                           |      \
 *         Wilderness      Lake &     Farm
 *   (Wizard Tower, DA)   Fishing Outpost
 *                      S
 * </pre>
 */
public final class HubPlan {

    private HubPlan() {}

    /** Island radius (blocks) and the radius content must stay inside. */
    public static final int ISLAND_R = 300, SAFE_R = 250;

    // ---- the mountain (north) ----
    public static final int MX = 30, MZ = -190, MR = 92, MPEAK = 96;

    // ---- plots (x/z boxes, y ignored) ----
    public static final Box VILLAGE = Box.of(-98, 0, -98, 98, 0, 98);
    public static final Box PORTALS = Box.of(-172, 0, -44, -102, 0, 44);
    public static final Box MINING = Box.of(64, 0, -134, 126, 0, -92);
    public static final Box GRAVEYARD = Box.of(140, 0, -76, 238, 0, 28);
    public static final Box FARM = Box.of(102, 0, 96, 214, 0, 206);
    public static final Box COLOSSEUM = Box.of(-110, 0, -175, -50, 0, -115);
    public static final Box CAMP = Box.of(-238, 0, -34, -192, 0, 12);
    public static final Box SUMMIT = Box.of(MX - 7, 0, MZ - 7, MX + 7, 0, MZ + 7);
    public static final Box WIZARD = Box.of(-114, 0, 112, -86, 0, 140);
    public static final Box DARK_AUCTION = Box.of(-186, 0, 160, -156, 0, 186);
    public static final Box WITCH = Box.of(-134, 0, 190, -112, 0, 210);
    public static final Box OUTPOST = Box.of(26, 0, 132, 62, 0, 158);
    public static final Box CASTLE = Box.of(141, 0, -152, 189, 0, -104);
    public static final Box CARNIVAL = Box.of(40, 0, 198, 84, 0, 234);

    // ---- landmarks ----
    public static final int RUINS_X = -160, RUINS_Z = -128, RUINS_R = 40, RUINS_HILL = 13;
    public static final int LAKE_X = 0, LAKE_Z = 182, LAKE_R = 30;
    public static final int FAIRY_POND_X = -76, FAIRY_POND_Z = 76;

    /** Is the column comfortably inside the island? */
    public static boolean safe(int x, int z) { return x * (long) x + z * (long) z <= (long) SAFE_R * SAFE_R; }
}
