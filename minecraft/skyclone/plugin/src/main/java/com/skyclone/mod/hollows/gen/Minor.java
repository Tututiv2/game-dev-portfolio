package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/**
 * The smaller places of the crystal caverns: the Treasure Gang's hideout (Mithril Deposits), the
 * Dragon's Lair on the west edge, the three Fairy Grottos (the only Jasper in the caverns) and a
 * niche on the Magma descent.
 */
final class Minor {

    private Minor() {}

    static final int HX = 178, HZ = -40, HFLOOR = 106;
    static final int LX = -186, LZ = 0, LFLOOR = 94;
    /** Grottos: x, floorY, z. */
    static final int[][] GROTTOS = {{-68, 96, -26}, {85, 89, 45}, {195, 100, -87}};
    private static int[] magmaNiche;

    static void prepare(Ctx x) {
        // the gang's hideout
        Carve.blob(x, HX, HFLOOR + 8, HZ, 20, 9, 18, 0.15);
        Carve.floor(x, HX, HZ, 19, 17, HFLOOR + 4, 6, (xx, z) -> HFLOOR);
        // the dragon's lair
        Carve.blob(x, LX, LFLOOR + 8, LZ, 18, 11, 18, 0.15);
        Carve.floor(x, LX, LZ, 17, 17, LFLOOR + 4, 6, (xx, z) -> LFLOOR);
        // fairy grottos
        for (int[] gr : GROTTOS) {
            Carve.blob(x, gr[0], gr[1] + 5, gr[2], 9.5, 6, 9.5, 0.1);
            Carve.floor(x, gr[0], gr[2], 8.5, 8.5, gr[1] + 3, 5, (xx, z) -> gr[1]);
        }
        // a niche off the Magma descent
        magmaNiche = Carve.alcove(x, -26, 66, 84, -1, 0, 4);
    }

    static void build(Ctx x) {
        hideout(x);
        lair(x);
        for (int i = 0; i < GROTTOS.length; i++) grotto(x, i + 1, GROTTOS[i]);
        if (magmaNiche != null) {
            x.soul(magmaNiche[0], magmaNiche[1], magmaNiche[2]);
            x.c.set(magmaNiche[0] - 1, magmaNiche[1] + 1, magmaNiche[2] - 1, "skeleton_skull[rotation=4]");
        }
    }

    private static void hideout(Ctx x) {
        Layout l = x.l;
        String[] colors = {"red", "black", "white", "red"};
        int[][] tents = {{HX - 9, HZ - 9}, {HX + 6, HZ - 10}, {HX - 10, HZ + 5}, {HX + 7, HZ + 5}};
        for (int t = 0; t < tents.length; t++) {
            int tx = tents[t][0], tz = tents[t][1];
            for (int z = tz; z <= tz + 4; z++)
                for (int dx = -2; dx <= 2; dx++) {
                    int y = HFLOOR + 1 + (2 - Math.abs(dx));
                    x.c.set(tx + dx, y, z, colors[t] + "_wool");
                    if (dx != 0) x.c.set(tx + dx, y + 1, z, colors[t] + "_wool");
                }
            x.c.set(tx, HFLOOR + 1, tz + 4, "spruce_fence");
            x.c.set(tx, HFLOOR + 2, tz + 4, "spruce_fence");
            x.c.set(tx - 1, HFLOOR + 1, tz + 3, "barrel[facing=up,open=false]");
            if (t == 1) x.soul(tx, HFLOOR, tz + 1);
        }
        Builders.campfire(x.c, HX, HFLOOR, HZ);
        Builders.crates(x.c, HX + 3, HFLOOR, HZ + 12, x.seed + 7);
        Builders.crates(x.c, HX - 4, HFLOOR, HZ + 13, x.seed + 8);
        Builders.banner(x.c, HX - 2, HFLOOR, HZ - 3, "red", 0);
        Builders.banner(x.c, HX + 2, HFLOOR, HZ - 3, "black", 0);
        x.c.set(HX + 10, HFLOOR + 1, HZ - 2, "target");
        x.c.set(HX + 10, HFLOOR + 1, HZ + 1, "hay_block[axis=y]");
        Structures.chandelier(x, HX, HFLOOR + 6, HZ + 2, 3);
        l.anchor("boss.gang_boss", HX + 0.5, HFLOOR + 1, HZ + 4.5, 0f);
        l.anchor("structure.gang_hideout", HX + 0.5, HFLOOR + 1, HZ + 0.5, 0f);
        l.zone("mithril_gang_hideout", "Gang Hideout", '2', Box.of(HX - 21, HFLOOR - 2, HZ - 19, HX + 21, HFLOOR + 18, HZ + 19), null);
        l.area("mobs.gang", Box.of(HX - 15, HFLOOR + 1, HZ - 14, HX + 15, HFLOOR + 3, HZ + 14));
    }

    private static void lair(Ctx x) {
        Layout l = x.l;
        long s = Hash.salt(x.seed, "lair");
        Palette nest = Palette.of("hay_block[axis=y]:4,bone_block[axis=y]:2,raw_gold_block:1");
        for (int z = LZ - 7; z <= LZ + 7; z++)
            for (int xx = LX - 7; xx <= LX + 7; xx++) {
                double d = Math.hypot(xx - LX, z - LZ);
                if (d < 6.2) x.c.set(xx, LFLOOR + 1, z, nest.at(xx, LFLOOR + 1, z));
                if (d < 3.6) x.c.set(xx, LFLOOR + 2, z, nest.at(xx, LFLOOR + 2, z));
                if (d < 1.6) x.c.set(xx, LFLOOR + 3, z, "raw_gold_block");
            }
        x.c.set(LX, LFLOOR + 4, LZ, "dragon_egg");
        x.soul(LX + 1, LFLOOR + 3, LZ + 1);
        for (int i = 0; i < 12; i++) {
            int bx = LX - 14 + (int) (Hash.unit(s, i, 0, 0) * 28), bz = LZ - 14 + (int) (Hash.unit(s, i, 1, 0) * 28);
            if (Math.hypot(bx - LX, bz - LZ) < 8 || !x.g.locked(bx, LFLOOR, bz) || x.g.locked(bx, LFLOOR + 1, bz)) continue;
            double r = Hash.unit(s, i, 2, 0);
            x.c.set(bx, LFLOOR + 1, bz, r < 0.4 ? "bone_block[axis=x]" : r < 0.7 ? "skeleton_skull[rotation=" + (i % 16) + "]" : "raw_gold_block");
        }
        for (int[] p : new int[][]{{LX - 9, LZ - 9}, {LX + 9, LZ + 9}, {LX - 9, LZ + 9}, {LX + 9, LZ - 9}})
            if (x.g.locked(p[0], LFLOOR, p[1]) && !x.g.locked(p[0], LFLOOR + 1, p[1])) x.c.set(p[0], LFLOOR, p[1], "shroomlight");
        l.anchor("landmark.dragons_lair", LX + 0.5, LFLOOR + 2, LZ + 0.5, 0f);
        l.zone("jungle_dragons_lair", "Dragon's Lair", 'd', Box.of(LX - 19, LFLOOR - 2, LZ - 19, LX + 19, LFLOOR + 18, LZ + 19), null);
    }

    private static void grotto(Ctx x, int n, int[] gr) {
        Layout l = x.l;
        int cx = gr[0], fy = gr[1], cz = gr[2];
        long s = Hash.salt(x.seed, "grotto" + n);
        Palette ground = Palette.of("moss_block:4,grass_block:3");
        String[] flowers = {"allium", "azure_bluet", "pink_tulip", "cornflower", "lily_of_the_valley", "oxeye_daisy",
                "pink_petals[facing=north,flower_amount=4]", "short_grass", "fern", "flowering_azalea"};
        for (int z = cz - 12; z <= cz + 12; z++)
            for (int xx = cx - 12; xx <= cx + 12; xx++)
                for (int y = fy - 2; y <= fy + 13; y++) {
                    double d = Math.sqrt(Math.pow(xx - cx, 2) + Math.pow((y - fy - 5) * 1.5, 2) + Math.pow(z - cz, 2));
                    if (d > 12.5) continue;
                    if (y == fy && x.g.locked(xx, y, z) && x.g.solid(xx, y, z)) {
                        x.c.set(xx, y, z, ground.at(xx, y, z));
                        if (x.g.air(xx, y + 1, z) && !x.g.locked(xx, y + 1, z) && Hash.unit(s, xx, y, z) < 0.45)
                            x.c.set(xx, y + 1, z, flowers[(int) (Hash.unit(s, xx, y + 1, z) * flowers.length)]);
                        continue;
                    }
                    if (x.g.solid(xx, y, z) && !x.g.locked(xx, y, z) && x.g.exposed(xx, y, z) && x.g.kind(xx, y, z) == Grid.ROCK) {
                        double r = Hash.unit(s, xx, y, z);
                        if (r < 0.40) x.c.set(xx, y, z, "magenta_stained_glass");
                        else if (r < 0.46) x.c.set(xx, y, z, "pearlescent_froglight");
                        if (r < 0.03 && x.g.air(xx, y - 1, z)) x.c.set(xx, y - 1, z, "spore_blossom");
                    }
                }
        // the soul sits on the far side from the entrance tunnel
        int[][] far = {{-4, 5}, {3, -6}, {4, -4}};
        x.soul(cx + far[n - 1][0], fy, cz + far[n - 1][1]);
        l.anchor("structure.fairy_grotto_" + n, cx + 0.5, fy + 1, cz + 0.5, 0f);
        l.zone("fairy_grotto_" + n, "Fairy Grotto", 'd', Box.of(cx - 11, fy - 2, cz - 11, cx + 11, fy + 12, cz + 11), null);
        l.area("mobs.grotto." + n, Box.of(cx - 6, fy + 1, cz - 6, cx + 6, fy + 3, cz + 6));
    }
}
