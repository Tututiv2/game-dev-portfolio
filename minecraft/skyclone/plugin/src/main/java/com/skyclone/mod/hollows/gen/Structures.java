package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.Fill;

/**
 * Runs every structure of the crystal caverns in two phases: {@link #prepare} works on the GRID
 * (carve the chamber, give it a floor, lock the structure's air) before the tunnels are dug, and
 * {@link #build} writes blocks into the CANVAS after the rock has been written. Also the small
 * block helpers the structure classes share. Pure Java.
 */
public final class Structures {

    private Structures() {}

    public static void prepare(Ctx x) {
        Nucleus.prepare(x);
        JungleTemple.prepare(x);
        Varnas.prepare(x);
        RelicCity.prepare(x);
        GoblinDen.prepare(x);
        QueenDen.prepare(x);
        Emberdeep.prepare(x);
        Minor.prepare(x);
    }

    public static void build(Ctx x) {
        Nucleus.build(x);
        JungleTemple.build(x);
        Varnas.build(x);
        RelicCity.build(x);
        GoblinDen.build(x);
        QueenDen.build(x);
        Emberdeep.build(x);
        Minor.build(x);
    }

    // ---- helpers ------------------------------------------------------------------------------

    static void box(Ctx x, int x0, int y0, int z0, int x1, int y1, int z1, Fill f) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int xx = Math.min(x0, x1); xx <= Math.max(x0, x1); xx++) x.c.set(xx, y, z, f.at(xx, y, z));
    }

    static void box(Ctx x, int x0, int y0, int z0, int x1, int y1, int z1, String b) { box(x, x0, y0, z0, x1, y1, z1, Fill.of(b)); }

    static void air(Ctx x, int x0, int y0, int z0, int x1, int y1, int z1) { box(x, x0, y0, z0, x1, y1, z1, "air"); }

    /** Grid: carve a box to air and lock its floor at y0-1. */
    static void carveRoom(Ctx x, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int xx = x0; xx <= x1; xx++) x.g.carve(xx, y, z);
        for (int z = z0; z <= z1; z++)
            for (int xx = x0; xx <= x1; xx++) x.g.put(xx, y0 - 1, z, Grid.ROCK, true);
    }

    /** A column from y0 upward while the canvas is air there (a pillar that meets the ceiling), at most maxH. */
    static int column(Ctx x, int px, int y0, int pz, Fill f, int maxH) {
        int y = y0;
        while (y < y0 + maxH && x.g.in(px, y, pz) && !x.g.solid(px, y, pz)) {
            x.c.set(px, y, pz, f.at(px, y, pz));
            y++;
        }
        return y;
    }

    static Box b(int x0, int y0, int z0, int x1, int y1, int z1) { return Box.of(x0, y0, z0, x1, y1, z1); }

    /** Hanging lantern on an iron chain down from the first rock above (x, fromY, z). */
    static void chandelier(Ctx x, int px, int fromY, int pz, int drop) {
        int top = fromY;
        while (top < fromY + 30 && x.g.in(px, top + 1, pz) && !x.g.solid(px, top + 1, pz)) top++;
        if (!x.g.solid(px, top + 1, pz)) return;
        int bottom = Math.max(fromY, top - drop);
        for (int y = top; y > bottom; y--) x.c.set(px, y, pz, "iron_chain[axis=y]");
        x.c.set(px, bottom, pz, "lantern[hanging=true,waterlogged=false]");
    }
}
