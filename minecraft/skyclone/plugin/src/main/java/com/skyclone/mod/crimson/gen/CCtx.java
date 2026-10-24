package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared state for one Crimson Isle build. Pure Java. */
final class CCtx {

    final VoxelCanvas c;
    final Layout layout;
    final long seed;
    final Noise districtNoise;
    final Reservations res = new Reservations();
    Terrain.Heightmap h;
    int rimY, lavaLevel;
    Builders.PathResult paths = new Builders.PathResult();
    /** Plot name -> floor y chosen by the terrain pass. */
    final Map<Box, Integer> plotY = new HashMap<>();
    /** Spots ({x,groundY,z}) builders offered for fairy souls, in a stable order. */
    final List<int[]> soulSpots = new ArrayList<>();
    /** Door steps etc. that roads should reach: name -> {x, y, z}. */
    final Map<String, int[]> stops = new HashMap<>();

    CCtx(VoxelCanvas c, Layout layout, long seed) {
        this.c = c;
        this.layout = layout;
        this.seed = seed;
        this.districtNoise = new Noise(Hash.salt(seed, "crimson-districts"));
    }

    CPlan.District district(int x, int z) { return CPlan.district(districtNoise, x, z); }

    int y(Box plot) {
        Integer v = plotY.get(plot);
        return v == null ? CPlan.BASE_Y : v;
    }

    /** Highest ground block at (x,z), searching down from {@code from}. */
    int ground(int x, int z, int from) {
        int g = Builders.ground(c, x, z, from, 10);
        return g == Integer.MIN_VALUE ? CPlan.BASE_Y : g;
    }

    int ground(int x, int z) { return ground(x, z, 190); }

    long salt(String s) { return Hash.salt(seed, s); }

    /** Register a road stop {@code n} blocks out from a door step, clear of eaves and roof cones. */
    void door(String name, int[] step, String dir, int n) {
        stops.put(name, new int[]{step[0] + com.skyclone.gen.Blocks.dx(dir) * n, step[1], step[2] + com.skyclone.gen.Blocks.dz(dir) * n});
    }

    void soul(int x, int groundY, int z) { soulSpots.add(new int[]{x, groundY, z}); }
}
