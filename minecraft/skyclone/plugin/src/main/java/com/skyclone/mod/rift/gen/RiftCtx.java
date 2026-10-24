package com.skyclone.mod.rift.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Terrain;
import com.skyclone.mod.rift.RiftCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one generator run shares. A NEW instance per {@code generate()} call: the game runs a
 * generator twice (layout first, then the build), so nothing may live in generator fields
 * (integration round 1 lost two islands to exactly that).
 */
final class RiftCtx {

    static final int BASE = 70;
    static final int WATER = 69;

    final VoxelCanvas c;
    final Layout l;
    final long seed;
    Terrain.Heightmap h;
    final Reservations res = new Reservations();
    Builders.PathResult paths = new Builders.PathResult();
    /** Boxes trees and scatter must keep out of (x/z only), on top of reservations and paths. */
    final List<Box> keepOut = new ArrayList<>();
    /** Polylines (x,z) that the lagoon must not drown: the causeways. */
    final List<int[][]> dryLines = new ArrayList<>();

    private final Map<String, Integer> soulNext = new HashMap<>();
    private final Map<String, Integer> soulEnd = new HashMap<>();
    int eggs, hearts, raceCps;
    /** Plot heights, set by RiftBuild.flattenPlots each run. */
    int plazaY, cradleY, colY, chateauY, dreadY, squareY, summitY;

    RiftCtx(VoxelCanvas c, Layout l, long seed) {
        this.c = c; this.l = l; this.seed = seed;
        int n = 1;
        for (Map.Entry<String, Integer> e : RiftCatalog.SOULS_PER_AREA.entrySet()) {
            soulNext.put(e.getKey(), n);
            n += e.getValue();
            soulEnd.put(e.getKey(), n - 1);
        }
    }

    // ---- ground --------------------------------------------------------------------------------

    /** Highest standable ground block at (x, z), or BASE if the column is empty. */
    int ground(int x, int z) {
        int g = Builders.ground(c, x, z, 230, 10);
        return g == Integer.MIN_VALUE ? BASE : g;
    }

    /** Ground below a given height (inside caves and under roofs). */
    int groundBelow(int x, int fromY, int z) {
        int g = Builders.ground(c, x, z, fromY, 10);
        return g == Integer.MIN_VALUE ? BASE : g;
    }

    boolean blocked(int x, int z, int margin) {
        if (res.covers(x, z, margin) || paths.near(x, z, margin)) return true;
        for (Box b : keepOut) if (x >= b.minX() - margin && x <= b.maxX() + margin && z >= b.minZ() - margin && z <= b.maxZ() + margin) return true;
        return false;
    }

    // ---- anchors ----------------------------------------------------------------------------------

    /** NPC / point anchor standing ON block (x, y, z); keeps the head room clear. */
    void stand(String name, int x, int y, int z, float yaw) {
        if (!c.isAir(x, y + 1, z) && Builders.natural(c.get(x, y + 1, z))) c.set(x, y + 1, z, "air");
        if (!c.isAir(x, y + 2, z) && Builders.natural(c.get(x, y + 2, z))) c.set(x, y + 2, z, "air");
        l.standAt(name, x, y, z, yaw);
    }

    /** Riddles already given a spot (wave 5): every soul is one of RiftEnigma's puzzles. */
    final java.util.Set<String> riddlesPlaced = new java.util.HashSet<>();
    /** Set before looseSouls: a reserved riddle whose district spot failed may now go anywhere in its area. */
    boolean looseMode;

    /** Next Riddle Soul of an area, standing on block (x, y, z): the area's next riddle nobody claimed by id. */
    boolean soul(String area, int x, int y, int z, String hint) {
        for (com.skyclone.mod.rift.RiftEnigma.Riddle r : com.skyclone.mod.rift.RiftEnigma.ofArea(area))
            if (!riddlesPlaced.contains(r.id()) && (looseMode || !RESERVED.contains(r.id()))) return soul(area, r.id(), x, y, z, hint);
        return false;
    }

    /**
     * Riddles a district builder places by id. The generic {@link #soul(String, int, int, int, String)} never takes
     * one of these, even before its builder ran, so a conditional spot that fails leaves its riddle to looseSouls.
     */
    static final java.util.Set<String> RESERVED = java.util.Set.of(
            "rift_enigma_original", "rift_enigma_jump_pad_1", "rift_enigma_two_plates", "rift_enigma_phantom",
            "rift_enigma_flower_pot_1", "rift_enigma_pressure_water", "rift_enigma_bark", "rift_enigma_silk_1",
            "rift_tel_kar", "rift_lagoon_parkour", "rift_rocks_enigma",
            "rift_hot_dog", "rift_cake_house_1", "rift_cake_house_2", "rift_kat_soul", "rift_dolphin_soul",
            "rift_buttons_soul", "rift_dreadfarm_flowerpot", "rift_beanstalk", "rift_dreadfarm_balloons",
            "rift_lonely_dave", "rift_scammer", "rift_back2basics", "rift_horsing_around", "rift_village_phantom",
            "rift_rabbit_under_hat", "rift_cave_wall", "rift_cave_flowers", "rift_mole", "rift_castle_standing",
            "rift_castle_phantom", "rift_castle_balloons", "rift_mountain_ubik_averages", "rift_mountain_ikrus",
            "rift_mountain_sungecko_perks", "rift_mountain_ubik_boxes", "rift_mountain_walk_of_fame_wall");

    /** A named riddle's soul, standing on block (x, y, z), with its location hint for the soul menu. */
    boolean soul(String area, String riddle, int x, int y, int z, String hint) {
        Integer n = soulNext.get(area);
        if (n == null || n > soulEnd.get(area) || !riddlesPlaced.add(riddle)) return false;
        soulNext.put(area, n + 1);
        l.standAt("soul." + n, x, y, z, 0f);
        l.data("soul." + n + ".hint", hint);
        l.data("soul." + n + ".area", area);
        l.data("soul." + n + ".riddle", riddle);
        return true;
    }

    /** How many souls of an area still need a spot. */
    int soulsLeft(String area) {
        Integer n = soulNext.get(area);
        return n == null ? 0 : soulEnd.get(area) - n + 1;
    }

    void egg(int x, int y, int z) { if (eggs < RiftCatalog.EGGS) l.standAt("egg." + (++eggs), x, y, z, 0f); }

    void heart(int x, int y, int z) { if (hearts < RiftCatalog.HEART_SPOTS) l.standAt("heart." + (++hearts), x, y, z, 0f); }

    void raceCp(int x, int y, int z) { if (raceCps < RiftCatalog.RACE_CHECKPOINTS) l.standAt("race." + (++raceCps), x, y, z, 0f); }

    /** Feet-space box for a spawn area: the ground y range of the region plus head room. */
    void mobArea(String name, int x1, int z1, int x2, int z2, int y1, int y2) {
        l.area(name, Box.of(x1, y1, z1, x2, y2, z2));
    }

    double rnd(String salt, int a, int b) { return Hash.unit(Hash.salt(seed, salt), a, b); }

    // ---- small geometry ---------------------------------------------------------------------------

    static double distToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double len2 = vx * vx + vz * vz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len2));
        double qx = ax + vx * t, qz = az + vz * t;
        return Math.hypot(px - qx, pz - qz);
    }

    double distToDry(int x, int z) {
        double best = Double.MAX_VALUE;
        for (int[][] line : dryLines)
            for (int i = 0; i + 1 < line.length; i++)
                best = Math.min(best, distToSegment(x, z, line[i][0], line[i][1], line[i + 1][0], line[i + 1][1]));
        return best;
    }
}
