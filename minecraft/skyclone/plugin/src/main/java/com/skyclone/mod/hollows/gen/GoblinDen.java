package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;

/**
 * The Goblin Holdout (south-west): a muddy cavern of huts around a fire pit and a fishing pool, and,
 * dug into the far wall, the Goblin King's Burrow — a long throne hall where the King trades the Amber
 * crystal for a Goblin Egg.
 */
final class GoblinDen {

    private GoblinDen() {}

    static final int CX = -124, CZ = 124, FLOOR = 82, RAD = 48;
    /** The throne hall, dug into the south-west wall: x0, z0, x1, z1. */
    static final int HX0 = -184, HZ0 = 152, HX1 = -166, HZ1 = 168;

    static int floorAt(Ctx x, int xx, int z) {
        if (Math.hypot(xx - CX, z - CZ) < 14) return FLOOR;
        if (xx >= HX1 - 2 && z >= HZ0 - 8) return FLOOR;
        return FLOOR + (int) Math.round(1.6 * x.noise.fbm2(xx / 24.0 + 7, z / 24.0, 2));
    }

    static void prepare(Ctx x) {
        Carve.blob(x, CX, FLOOR + 14, CZ, RAD, 24, RAD, 0.15);
        Carve.floor(x, CX, CZ, RAD - 1, RAD - 1, FLOOR + 8, 8, (xx, z) -> floorAt(x, xx, z));
        // the fishing pool (water worms live here)
        for (int z = 96; z <= 112; z++)
            for (int xx = -150; xx <= -134; xx++) {
                double d = Math.hypot((xx + 142) / 7.0, (z + -104) / 6.0);
                if (d > 1) continue;
                int f = floorAt(x, xx, z);
                x.g.put(xx, f, z, Grid.WATER, true);
                x.g.put(xx, f - 1, z, Grid.WATER, true);
                if (d < 0.5) x.g.put(xx, f - 2, z, Grid.WATER, true);
                x.g.put(xx, f - 3, z, Grid.ROCK, true);
            }
        // the King's burrow
        Structures.carveRoom(x, HX0, FLOOR + 1, HZ0, HX1, FLOOR + 9, HZ1);
        Carve.blob(x, (HX0 + HX1) / 2.0, FLOOR + 9, (HZ0 + HZ1) / 2.0, 11, 4, 10, 0.1);
        x.structure(Box.of(HX0 - 1, FLOOR + 1, HZ0 - 1, HX1 + 1, FLOOR + 14, HZ1 + 1), true);
        // paths: tunnel mouth -> fire pit -> burrow door, and fire pit -> the lair tunnel
        int[] mouth = {-97, FLOOR, 95};
        Carve.tunnel(x, new int[][]{mouth, {-110, FLOOR, 110}, {CX + 4, FLOOR, CZ - 4}}, 1.6, 4, Network.DECK_GOBLIN, false);
        Carve.tunnel(x, new int[][]{{CX - 4, FLOOR, CZ + 4}, {-140, FLOOR, 140}, {HX1 + 2, FLOOR, HZ0 + 4}}, 1.6, 4, Network.DECK_GOBLIN, false);
        Carve.tunnel(x, new int[][]{{CX - 4, FLOOR, CZ - 4}, {-134, FLOOR, 100}, {-140, FLOOR, 86}}, 1.6, 4, Network.DECK_GOBLIN, false);
    }

    static void build(Ctx x) {
        Layout l = x.l;
        long s = Hash.salt(x.seed, "goblin");
        // ---- the fire pit ----
        for (int z = CZ - 4; z <= CZ + 4; z++)
            for (int xx = CX - 4; xx <= CX + 4; xx++) {
                double d = Math.hypot(xx - CX, z - CZ);
                if (d <= 4.4) x.c.set(xx, FLOOR, z, d < 1.5 ? "magma_block" : "packed_mud");
            }
        x.c.set(CX, FLOOR + 1, CZ, "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]");
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6;
            int px = CX + (int) Math.round(Math.cos(a) * 3), pz = CZ + (int) Math.round(Math.sin(a) * 3);
            x.c.set(px, FLOOR + 1, pz, i % 2 == 0 ? "stripped_spruce_log[axis=y]" : "hay_block[axis=y]");
        }
        // ---- huts around it ----
        Reservations res = new Reservations();
        res.reserve("pit", Box.of(CX - 6, 0, CZ - 6, CX + 6, 0, CZ + 6));
        res.reserve("pool", Box.of(-151, 0, 95, -133, 0, 113));
        res.reserve("burrow", Box.of(HX0 - 2, 0, HZ0 - 6, HX1 + 4, 0, HZ1 + 2));
        int huts = 0;
        int[] hutInside = null;
        for (int i = 0; i < 120 && huts < 9; i++) {
            double a = Hash.unit(s, i, 0, 0) * Math.PI * 2, r = 14 + Hash.unit(s, i, 1, 0) * 20;
            int w = 5 + (int) (Hash.unit(s, i, 2, 0) * 3), d = 5 + (int) (Hash.unit(s, i, 3, 0) * 2);
            int hx = CX + (int) (Math.cos(a) * r) - w / 2, hz = CZ + (int) (Math.sin(a) * r) - d / 2;
            if (!flat(x, hx - 1, hz - 1, hx + w, hz + d)) continue;
            String door = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? "west" : "east") : (Math.sin(a) > 0 ? "north" : "south");
            Builders.House h = new Builders.House(hx, FLOOR, hz, w, d);
            h.door = door;
            h.wall = Palette.of("spruce_planks:3,stripped_spruce_log[axis=y]:1,mud_bricks:1");
            h.frame = "spruce_log";
            h.roof = huts % 2 == 0 ? "dark_oak" : "spruce";
            h.roofStyle = "hip";
            h.foundation = Fill.of("packed_mud");
            h.floor = Fill.of("spruce_planks");
            h.window = "air";
            h.seed = s + i;
            Box rb = h.reserve();
            if (!res.reserve("hut" + i, Box.of(rb.minX(), 0, rb.minZ(), rb.maxX(), 0, rb.maxZ()))) continue;
            if (pathNear(x, rb)) { continue; }
            h.build(x.c);
            if (hutInside == null) hutInside = h.inside;
            huts++;
            x.c.set(h.doorStep[0], h.doorStep[1] + 1, h.doorStep[2], "air");
        }
        if (hutInside != null) x.soul(hutInside[0], hutInside[1], hutInside[2]);
        // ---- junk: crates, banners, fences ----
        for (int i = 0; i < 14; i++) {
            int px = CX - 36 + (int) (Hash.unit(s, i, 5, 1) * 72), pz = CZ - 36 + (int) (Hash.unit(s, i, 6, 1) * 72);
            if (res.covers(px, pz, 2) || x.g.locked(px, FLOOR + 1, pz) || !x.g.locked(px, FLOOR, pz)) continue;
            if (x.g.floorBelow(px, FLOOR + 3, pz) != FLOOR) continue;
            if (Hash.unit(s, i, 7, 1) < 0.6) Builders.crates(x.c, px, FLOOR, pz, s + i);
            else Builders.banner(x.c, px, FLOOR, pz, "orange", (int) (Hash.unit(s, i, 8, 1) * 16));
        }
        // ---- the King's burrow ----
        Palette wall = Palette.of("mud_bricks:4,packed_mud:2,spruce_planks:1");
        for (int z = HZ0; z <= HZ1; z++)
            for (int xx = HX0; xx <= HX1; xx++) x.c.set(xx, FLOOR, z, (xx + z) % 7 == 0 ? "dark_oak_planks" : "spruce_planks");
        for (int y = FLOOR + 1; y <= FLOOR + 9; y++) {
            for (int xx = HX0 - 1; xx <= HX1 + 1; xx++) { lining(x, xx, y, HZ0 - 1, wall); lining(x, xx, y, HZ1 + 1, wall); }
            for (int z = HZ0; z <= HZ1; z++) { lining(x, HX0 - 1, y, z, wall); lining(x, HX1 + 1, y, z, wall); }
        }
        // door: the burrow opens north-east toward the camp
        Structures.air(x, HX1 + 1, FLOOR + 1, HZ0 + 3, HX1 + 1, FLOOR + 3, HZ0 + 5);
        x.c.set(HX1 + 1, FLOOR + 4, HZ0 + 3, "dark_oak_planks");
        x.c.set(HX1 + 1, FLOOR + 4, HZ0 + 5, "dark_oak_planks");
        x.c.set(HX1 + 1, FLOOR + 4, HZ0 + 4, "dark_oak_planks");
        // red carpet from the door to the throne in the far (west) end
        for (int xx = HX0 + 3; xx <= HX1; xx++) x.c.set(xx, FLOOR + 1, HZ0 + 4, "red_carpet");
        int tx = HX0 + 1, tz = HZ0 + 4;
        x.c.set(tx, FLOOR + 1, tz, "raw_gold_block");
        x.c.set(tx, FLOOR + 2, tz, "raw_gold_block");
        x.c.set(tx, FLOOR + 3, tz, "orange_banner[rotation=4]");
        x.c.set(tx, FLOOR + 1, tz - 1, "spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        x.c.set(tx, FLOOR + 1, tz + 1, "spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        for (int xx = HX0 + 3; xx <= HX1 - 1; xx += 4) {
            x.c.set(xx, FLOOR + 1, HZ0, "hay_block[axis=y]");
            x.c.set(xx, FLOOR + 1, HZ1, "barrel[facing=up,open=false]");
            x.c.set(xx, FLOOR + 2, HZ1, "lantern[hanging=false,waterlogged=false]");
            Structures.chandelier(x, xx, FLOOR + 6, HZ0 + 4, 3);
        }
        l.standAt("npc.goblin_king", tx + 2, FLOOR, tz, -90f);
        l.anchor("structure.goblin_burrow", tx + 3.5, FLOOR + 1, tz + 0.5, 0f);
        x.soul(HX0, FLOOR, HZ1);
        // ---- lamps ----
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + 0.3;
            int px = CX + (int) Math.round(Math.cos(a) * 10), pz = CZ + (int) Math.round(Math.sin(a) * 10);
            if (x.g.locked(px, FLOOR + 1, pz) || res.covers(px, pz, 0)) continue;
            Builders.lampPost(x.c, px, FLOOR, pz, "torch", "north");
        }
        l.zone("goblin_burrow", "Goblin King's Burrow", '6', Box.of(HX0 - 1, FLOOR, HZ0 - 1, HX1 + 1, FLOOR + 12, HZ1 + 1), null);
        l.area("mobs.goblin_holdout", Box.of(CX - 42, FLOOR - 2, CZ - 42, CX + 42, FLOOR + 4, CZ + 42));
        l.area("mobs.goblin_guard", Box.of(HX1 + 2, FLOOR - 1, HZ0 - 6, HX1 + 10, FLOOR + 3, HZ0 + 6));
        l.anchor("landmark.goblin_holdout", CX + 0.5, FLOOR + 12, CZ + 0.5, 0f);
    }

    private static boolean flat(Ctx x, int x0, int z0, int x1, int z1) {
        for (int z = z0; z <= z1; z++)
            for (int xx = x0; xx <= x1; xx++) {
                if (!x.g.locked(xx, FLOOR, z) && x.g.floorBelow(xx, FLOOR + 3, z) != FLOOR) return false;
                if (x.g.fluid(xx, FLOOR, z) || x.g.solid(xx, FLOOR + 1, z) || x.g.solid(xx, FLOOR + 6, z)) return false;
                if (x.g.floorBelow(xx, FLOOR + 3, z) != FLOOR) return false;
            }
        return true;
    }

    private static boolean pathNear(Ctx x, Box b) {
        for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++)
            for (int xx = b.minX() - 1; xx <= b.maxX() + 1; xx++)
                if (x.g.locked(xx, FLOOR + 1, z) && x.g.air(xx, FLOOR + 1, z)) return true;
        return false;
    }

    private static void lining(Ctx x, int xx, int y, int z, Fill f) {
        if (x.g.locked(xx, y, z) && x.g.air(xx, y, z)) return;
        if (!x.g.solid(xx, y, z)) return;
        x.c.set(xx, y, z, f.at(xx, y, z));
    }
}
