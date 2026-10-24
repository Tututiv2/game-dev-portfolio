package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;

/**
 * Run geometry in TEMPLATE coordinates (the instance origin is added at placement). Cell (gx, gz) spans
 * x in [gx*32, gx*32+31], z likewise; walls stand on a room's own boundary, so two rooms meet with a 2-thick wall and
 * a door is a 3-wide, 4-high opening through both. The boss arena sits east of the grid.
 */
public final class Geo {

    private Geo() {}

    public static final int CELL = 32;
    /** The floor block layer; players stand at FLOOR_Y + 1. */
    public static final int FLOOR_Y = 70;
    public static final int CEIL_Y = 82;
    /** Door opening: offsets 14..16 along the edge, y FLOOR_Y+1 .. FLOOR_Y+4. */
    public static final int DOOR_LO = 14, DOOR_HI = 16, DOOR_H = 4;
    /** Largest grid any floor uses. */
    public static final int MAX_GRID = 6;
    /** Gap between the grid and the boss arena. */
    public static final int ARENA_GAP = 48;
    /** Largest boss arena footprint a BossArena may ask for (x by z). */
    public static final int ARENA_MAX_X = 160, ARENA_MAX_Z = 200;

    /** Everything a run may ever write, template-relative: also the instanced island's bounds, so core clears it on reuse. */
    public static final Box RUN_BOUNDS = new Box(-48, 20, -48,
            MAX_GRID * CELL + ARENA_GAP + ARENA_MAX_X + 8, 200, Math.max(MAX_GRID * CELL, ARENA_MAX_Z) + 48);

    /** The waiting platform the shell template builds (players never see it unless a placement fails). */
    public static final int STAGE_X = -30, STAGE_Z = -30;

    public static int cellX(int gx) { return gx * CELL; }
    public static int cellZ(int gz) { return gz * CELL; }

    /** Walkable interior of one cell (no walls): 30 x 30, y FLOOR_Y+1 .. CEIL_Y-1. */
    public static Box interior(int gx, int gz) {
        return new Box(gx * CELL + 1, FLOOR_Y + 1, gz * CELL + 1, gx * CELL + CELL - 2, CEIL_Y - 1, gz * CELL + CELL - 2);
    }

    /** Full footprint of a cell (walls included). */
    public static Box cell(int gx, int gz) {
        return new Box(gx * CELL, FLOOR_Y, gz * CELL, gx * CELL + CELL - 1, CEIL_Y, gz * CELL + CELL - 1);
    }

    /** Arena origin (its min corner, at floor level) for a grid of this width. */
    public static int arenaX(int gridW) { return gridW * CELL + ARENA_GAP; }

    public static int arenaZ() { return 0; }

    /** Cell coordinate of a template block x/z (may be out of the grid). */
    public static int toCell(int v) { return Math.floorDiv(v, CELL); }

    /**
     * The door opening of a plan door (both wall layers), as a box: for 'E' x = gx*32+31..gx*32+32, for 'S'
     * z = gz*32+31..gz*32+32; 3 wide along the edge, DOOR_H tall.
     */
    public static Box doorBox(DungeonPlan.Door d) {
        int y0 = FLOOR_Y + 1, y1 = FLOOR_Y + DOOR_H;
        if (d.dir == 'E') {
            int x = d.gx * CELL + CELL - 1;
            int z0 = d.gz * CELL + DOOR_LO;
            return new Box(x, y0, z0, x + 1, y1, z0 + (DOOR_HI - DOOR_LO));
        }
        int z = d.gz * CELL + CELL - 1;
        int x0 = d.gx * CELL + DOOR_LO;
        return new Box(x0, y0, z, x0 + (DOOR_HI - DOOR_LO), y1, z + 1);
    }

    /** Centre of a door opening at foot level (x+0.5 handled by the caller). */
    public static int[] doorCenter(DungeonPlan.Door d) {
        Box b = doorBox(d);
        return new int[]{b.centerX(), FLOOR_Y + 1, b.centerZ()};
    }

    /**
     * Did a move from {@code from} to {@code to} (feet positions, x/y/z) pass through {@code box}? The path is sampled
     * every quarter block, so a sprint, a speed-400 dash or a short teleport across a thin frame still counts.
     * Moves longer than {@code maxLen} (a warp, a rescue) only test the end point. Fixer: the boss portal used to be
     * tested at one point every two ticks only.
     */
    public static boolean crossed(Box box, double[] from, double[] to, double maxLen) {
        if (box == null || to == null) return false;
        if (box.contains(to[0], to[1] + 0.1, to[2])) return true;
        if (from == null) return false;
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0 || len > maxLen) return false;
        int n = (int) Math.ceil(len / 0.25);
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            if (box.contains(from[0] + dx * t, from[1] + dy * t + 0.1, from[2] + dz * t)) return true;
        }
        return false;
    }
}
