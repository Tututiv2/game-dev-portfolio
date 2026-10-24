package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/** The Blazing Volcano's rim lookout, its lava streaks and spawn areas, and the lava-fishing hut. */
final class CVolcano {

    private CVolcano() {}

    static void build(CCtx x) {
        VoxelCanvas c = x.c;
        int vx = CPlan.VOLCANO_X, vz = CPlan.VOLCANO_Z;

        // ---- rim lookout: a small blackstone terrace on the south rim, facing the lava ----
        int lz = vz + (int) CPlan.CRATER_R + 2;
        int ly = x.ground(vx, lz);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -1; dz <= 2; dz++) {
                c.set(vx + dx, ly, lz + dz, ((dx + dz) & 1) == 0 ? "polished_blackstone" : "polished_basalt[axis=y]");
                Builders.footing(c, vx + dx, ly, lz + dz, Fill.of("blackstone"), 12);
                for (int h = 1; h <= 3; h++) if (Builders.natural(c.get(vx + dx, ly + h, lz + dz))) c.set(vx + dx, ly + h, lz + dz, "air");
            }
        for (int dx : new int[]{-4, 4}) {
            c.set(vx + dx, ly + 1, lz - 1, "polished_blackstone_wall");
            c.set(vx + dx, ly + 2, lz - 1, "soul_lantern[hanging=false,waterlogged=false]");
            Builders.footing(c, vx + dx, ly + 1, lz - 1, Fill.of("blackstone"), 12);
        }
        x.layout.standAt("crimson.crater_lookout", vx, ly, lz, 180f);
        x.stops.put("crater", new int[]{vx, ly, lz + 2});
        x.res.reserve("crater_lookout", Box.of(vx - 4, ly - 4, lz - 1, vx + 4, ly + 4, lz + 2));
        int cr = (int) CPlan.CRATER_R;
        x.layout.area("fishing.lava.crater", Box.of(vx - cr + 2, x.lavaLevel, vz - cr + 2, vx + cr - 2, x.lavaLevel, vz + cr - 2));
        x.soul(vx + 3, ly, lz + 2);

        // ---- spawn areas + zone ----
        x.layout.area("mobs.volcano_slopes", Box.of(vx - 62, CPlan.BASE_Y, vz - 62, vx + 62, x.rimY, vz + 64));
        x.layout.area("mobs.volcano_rim", Box.of(vx - 26, x.rimY - 12, vz - 26, vx + 26, x.rimY + 6, vz + 26));
        x.layout.area("mobs.volcano_base", Box.of(-74, CPlan.BASE_Y - 4, -112, -30, CPlan.BASE_Y + 14, -70));
        x.layout.zone("crimson_volcano", "Blazing Volcano", 'c',
                Box.of(vx - (int) CPlan.VOLCANO_R, CPlan.CHAMBER_FLOOR + 18, vz - (int) CPlan.VOLCANO_R,
                        vx + (int) CPlan.VOLCANO_R, x.rimY + 40, vz + (int) CPlan.VOLCANO_R), null);

        hut(x);
    }

    /** Magma streaks down the flanks; runs AFTER the roads so it can keep off them. */
    static void streaks(CCtx x) {
        VoxelCanvas c = x.c;
        int vx = CPlan.VOLCANO_X, vz = CPlan.VOLCANO_Z;
        // ---- magma streaks running down the flanks (decoration, off the road) ----
        long s = Hash.salt(x.seed, "streaks");
        for (int i = 0; i < 9; i++) {
            double ang = (i / 9.0) * Math.PI * 2 + 0.3;
            if (Math.abs(Math.sin(ang) - 1) < 0.25) continue;                // keep the south face (the road) clean
            for (double r = CPlan.CRATER_R + 1; r < CPlan.VOLCANO_R - 12; r += 0.7) {
                double wob = Math.sin(r / 5.0 + i) * 1.5;
                int sx = vx + (int) Math.round(Math.cos(ang) * r - Math.sin(ang) * wob);
                int sz = vz + (int) Math.round(Math.sin(ang) * r + Math.cos(ang) * wob);
                if (x.paths.near(sx, sz, 3)) continue;
                int g = x.ground(sx, sz);
                if (g < CPlan.BASE_Y + 4) continue;
                c.set(sx, g, sz, Hash.unit(s, sx, sz) < 0.7 ? "magma_block" : "orange_terracotta");
            }
        }

        // ---- quartz veins and glowstone outcrops on the lower flanks (the crimson module lets these be mined) ----
        long q = Hash.salt(x.seed, "veins");
        int veins = 0, glow = 0;
        for (int i = 0; i < 400 && (veins < 40 || glow < 16); i++) {
            double ang = Hash.unit(q, i, 1) * Math.PI * 2;
            double r = 30 + Hash.unit(q, i, 2) * 38;
            int sx = vx + (int) Math.round(Math.cos(ang) * r), sz = vz + (int) Math.round(Math.sin(ang) * r);
            if (x.paths.near(sx, sz, 3) || x.res.covers(sx, sz, 3) || !x.h.has(sx, sz)) continue;
            int g = x.ground(sx, sz);
            if (!c.isAir(sx, g + 1, sz)) continue;
            if (veins < 40) {
                for (int k = 0; k < 4; k++) {
                    int dx = k == 0 ? 0 : (int) Math.round(Hash.unit(q, i, 10 + k) * 2 - 1);
                    int dz = k == 0 ? 0 : (int) Math.round(Hash.unit(q, i, 20 + k) * 2 - 1);
                    int gg = x.ground(sx + dx, sz + dz);
                    c.set(sx + dx, gg, sz + dz, "nether_quartz_ore");
                }
                veins++;
            } else {
                c.set(sx, g + 1, sz, "glowstone");
                if (Hash.unit(q, i, 3) < 0.6) c.set(sx, g + 2, sz, "glowstone");
                for (int d = 0; d < 4; d++) if (Hash.unit(q, i, 30 + d) < 0.5 && c.isAir(sx + Blocks.DX[d], g + 1, sz + Blocks.DZ[d]))
                    c.set(sx + Blocks.DX[d], g + 1, sz + Blocks.DZ[d], "glowstone");
                glow++;
            }
        }
        x.layout.data("crimson.volcano.veins", veins + "," + glow);
    }

    /** A spruce-and-blackstone fishing hut on the shore of a lava pool (the trophy keeper's). */
    private static void hut(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.HUT);
        int px = -98, pz = -76;
        String lvl = x.layout.data().get("crimson.pool.hut");
        Builders.House h = new Builders.House(-114, y, -100, 11, 9);
        h.door = "south"; h.wall = Palette.of("spruce_planks:5,stripped_spruce_log[axis=y]:1");
        h.frame = "dark_oak_log"; h.foundation = Palette.of("blackstone:3,polished_blackstone:1");
        h.roof = "dark_oak"; h.roofStyle = "gable"; h.chimney = true; h.chimneyBlock = "polished_blackstone_bricks";
        h.seed = x.seed; h.doorWood = "spruce";
        if (x.res.reserve("lava_hut", h.reserve())) h.build(c);
        x.layout.standAt("npc.trophy_keeper", h.inside[0], h.inside[1], h.inside[2], 0f);
        x.stops.put("hut", new int[]{h.doorStep[0], h.doorStep[1], h.doorStep[2] + 2});
        // a little dock out over the lava's edge
        for (int dz = 0; dz <= 3; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                int bx = px + dx, bz = pz - 8 + dz;
                if (!c.isAir(bx, y + 1, bz)) continue;
                c.set(bx, y, bz, "spruce_planks");
                Builders.footing(c, bx, y, bz, Fill.of("spruce_log[axis=y]"), 4);
            }
        for (int dx : new int[]{-1, 1}) {
            if (!CKit.solid(c, px + dx, y, pz - 5)) continue;
            c.set(px + dx, y + 1, pz - 5, "spruce_fence");
            c.set(px + dx, y + 2, pz - 5, Blocks.lantern(false));
        }
        Builders.crates(c, -103, y, -104, x.seed + 9);
        Builders.campfire(c, -86, y, -96);
        x.layout.area("fishing.lava.hut", Box.of(px - 5, y - 3, pz - 5, px + 5, y, pz + 5));
        // NO_MOBS (not SAFE): rule-spawned mobs never spawn here and are put back if they walk in, but the lava sea
        // creatures players fish up (not rule-spawned) still fight. Wave-4 playtest: a Lv80 Mushroom Bull from the
        // marsh box came within 13 blocks of the pool; south edge reaches -60 so the road-side shore is covered too.
        x.layout.zone("crimson_lava_hut", "Cinder Shore", '6', Box.of(-120, y - 6, -106, -76, y + 14, -60), null,
                Layout.Zone.NO_MOBS);
        x.soul(-117, y, -68);
        if (lvl == null) x.layout.data("crimson.pool.hut.missing", "true");
    }
}
