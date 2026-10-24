package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * Floor I: Grinko's big top (research 04 sec 3.13, "circus arena, 7 pillars"). A round ring under a striped tent, a
 * sawdust floor, tiered benches round the edge and seven quartz pillars that block balloons: they are the cover the
 * second phase is about. Points: spawn, boss, centre, chests, pillar.0..6 (pillar tops).
 */
public final class CircusArena implements BossArena {

    public static final CircusArena INSTANCE = new CircusArena();
    static final int R = 29, H = 15;

    @Override public String id() { return "f1_circus"; }

    @Override public int sizeX() { return 65; }

    @Override public int sizeZ() { return 65; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        int cx = ox + 32, cz = oz + 32;
        Palette sawdust = Palette.of("smooth_sandstone:4,sandstone:2,sand:1,stripped_birch_wood[axis=y]:1");
        Palette red = Palette.of("red_wool");
        Palette white = Palette.of("white_wool");
        Palette trim = Palette.of("yellow_terracotta:2,orange_terracotta:1");
        ArenaKit.round(c, cx, cz, R, fy, H, sawdust, red, trim, null);
        // striped walls: eight stripes of red and white
        for (int x = cx - R - 2; x <= cx + R + 2; x++)
            for (int z = cz - R - 2; z <= cz + R + 2; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d <= R || d > R + 1.6) continue;
                double a = Math.atan2(z - cz, x - cx);
                boolean r = ((int) Math.floor((a + Math.PI) / (Math.PI / 8))) % 2 == 0;
                for (int y = fy + 2; y < fy + H - 1; y++) c.set(x, y, z, r ? "red_wool" : "white_wool");
            }
        // the tent: a striped cone from the wall top to a peak
        int peak = 12;
        for (int x = cx - R - 2; x <= cx + R + 2; x++)
            for (int z = cz - R - 2; z <= cz + R + 2; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > R + 1.6) continue;
                int y = fy + H + (int) Math.round(peak * (1 - Math.min(1, d / (R + 1.6))));
                double a = Math.atan2(z - cz, x - cx);
                boolean r = ((int) Math.floor((a + Math.PI) / (Math.PI / 8))) % 2 == 0;
                // two blocks thick so neighbouring rings touch face to face (a one-block cone is a staircase of
                // separate rings, i.e. floating)
                c.set(x, y, z, r ? "red_wool" : "white_wool");
                c.set(x, y - 1, z, d < 3 ? "glowstone" : (r ? "red_wool" : "white_wool"));
                for (int yy = fy + H; yy < y - 1; yy++) c.set(x, yy, z, "air");
            }
        c.set(cx, fy + H + peak + 1, cz, "yellow_banner");
        // ring of lights under the tent edge and in the floor
        for (int k = 0; k < 24; k++) {
            double a = Math.PI * 2 * k / 24;
            int x = cx + (int) Math.round(Math.cos(a) * (R + 1)), z = cz + (int) Math.round(Math.sin(a) * (R + 1));
            c.set(x, fy + H - 2, z, "glowstone");
            c.set(x, fy + 6, z, "glowstone");
            int lx = cx + (int) Math.round(Math.cos(a) * 10), lz = cz + (int) Math.round(Math.sin(a) * 10);
            c.set(lx, fy, lz, "shroomlight");
        }
        // the centre ring: a red and white target
        for (int x = cx - 8; x <= cx + 8; x++)
            for (int z = cz - 8; z <= cz + 8; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d <= 8) c.set(x, fy, z, ((int) d) % 3 == 0 ? "red_concrete" : "white_concrete");
            }
        // tiered benches between r 25 and the wall (walkable steps, open toward the ring)
        for (int x = cx - R; x <= cx + R; x++)
            for (int z = cz - R; z <= cz + R; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d < 25 || d > R) continue;
                int tier = (int) (d - 25);
                for (int y = fy + 1; y <= fy + 1 + tier; y++) c.set(x, y, z, y == fy + 1 + tier ? "spruce_planks" : "spruce_log[axis=y]");
            }
        // seven pillars of cover at radius 17
        for (int k = 0; k < 7; k++) {
            double a = Math.PI * 2 * k / 7 - Math.PI / 2;
            int px = cx + (int) Math.round(Math.cos(a) * 17), pz = cz + (int) Math.round(Math.sin(a) * 17);
            ArenaKit.column(c, px, pz, 1.6, fy + 1, 6, Palette.of("quartz_pillar[axis=y]:3,chiseled_quartz_block:1"));
            ArenaKit.disc(c, px, pz, 1.6, fy + 7, Palette.of("smooth_quartz"));
            c.set(px + 1, fy + 8, pz, "lantern[hanging=false,waterlogged=false]");
            out.put("pillar." + k, px, fy + 8, pz);
        }
        out.put("spawn", cx, fy + 1, cz + 21);
        out.put("boss", cx, fy + 1, cz - 4);
        out.put("centre", cx, fy + 1, cz);
        out.put("chests", cx, fy + 1, cz - 12);
    }
}
