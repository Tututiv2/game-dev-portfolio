package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.util.LongSet;
import com.skyclone.gen.util.Pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything one cave-world generation run shares: the byte grid, the canvas, the layout, the
 * structure boxes, and the walkway bookkeeping (deck blocks, bridge cells, rails, lanterns).
 * Pure Java (no Bukkit).
 */
public final class Ctx {

    public final VoxelCanvas c;
    public final Layout l;
    public final Grid g;
    /** Fixed seed: structure placement. */
    public final long seed;
    /** Lobby seed: noise caves, veins, ores (changes with a "new lobby"). */
    public final long var;
    public final Noise noise;
    public final Noise varNoise;

    /** Structure boxes: decoration never writes inside them. */
    public final List<Box> structs = new ArrayList<>();
    /** Walkway top cells -> the block to show there (paths, plazas). */
    public final Map<Long, String> deck = new HashMap<>();
    /** Walkway cells that span air or fluid (written as bridge deck). */
    public final LongSet bridge = new LongSet(1 << 14);
    /** Hanging lantern spots. */
    public final List<int[]> lanterns = new ArrayList<>();
    /** Zone of a cell (for palettes): set by the generator. */
    public Function<int[], String> zoneFn = p -> "rock";
    private int souls;

    public Ctx(VoxelCanvas c, Layout l, Box bounds, long seed, long var) {
        this.c = c;
        this.l = l;
        this.g = new Grid(bounds);
        this.seed = seed;
        this.var = var;
        this.noise = new Noise(seed);
        this.varNoise = new Noise(var);
    }

    public static long key(int x, int y, int z) { return Pack.key(x, y, z); }

    public boolean inStruct(int x, int y, int z) {
        for (Box b : structs) if (b.contains(x, y, z)) return true;
        return false;
    }

    public boolean inStruct(int x, int y, int z, int margin) {
        for (Box b : structs)
            if (x >= b.minX() - margin && x <= b.maxX() + margin && y >= b.minY() - margin && y <= b.maxY() + margin
                    && z >= b.minZ() - margin && z <= b.maxZ() + margin) return true;
        return false;
    }

    /** Reserve a structure box: decoration stays out, and its air is locked against later carving/filling. */
    public void structure(Box b, boolean lockAir) {
        structs.add(b);
        if (!lockAir) return;
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++)
                    if (g.in(x, y, z) && g.kind(x, y, z) == Grid.AIR) g.put(x, y, z, Grid.AIR, true);
    }

    /** A fairy soul standing on the block (x, floorY, z). */
    public String soul(int x, int floorY, int z) {
        String n = "fairy_soul." + (++souls);
        l.standAt(n, x, floorY, z, 0f);
        return n;
    }

    public int souls() { return souls; }

    public double unit(String salt, int x, int y, int z) { return Hash.unit(Hash.salt(seed, salt), x, y, z); }

    /** Air that is safe to decorate: inside the grid, plain air, not reserved for a walkway or a structure. */
    public boolean freeAir(int x, int y, int z) {
        return g.in(x, y, z) && g.kind(x, y, z) == Grid.AIR && !g.locked(x, y, z) && !inStruct(x, y, z);
    }
}
