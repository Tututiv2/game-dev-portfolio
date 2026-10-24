package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/** The black fortress players arrive in: spawn plaza, the hub portal, guide, and the Edgewraith's yard. */
final class CStronghold {

    private CStronghold() {}

    static final int X0 = -32, X1 = 32, Z0 = 46, Z1 = 106, WALL = 3, WALL_H = 12;

    static void build(CCtx x) {
        VoxelCanvas c = x.c;
        int sy = x.y(CPlan.STRONGHOLD);
        Palette brick = CKit.BLACK_BRICK;
        x.res.reserve("stronghold", Box.of(X0 - 6, sy - 10, Z0 - 6, X1 + 6, sy + 30, Z1 + 6));

        // ---- courtyard paving first (so walls stand on it) ----
        for (int z = Z0; z <= Z1; z++)
            for (int xx = X0; xx <= X1; xx++) {
                boolean centre = Math.abs(xx) <= 2;
                String b = centre ? "chiseled_polished_blackstone" : (((xx + z) & 1) == 0 ? "polished_blackstone" : "polished_blackstone_bricks");
                if (Math.abs(xx) <= 2 && z % 6 != 0) b = "polished_blackstone_bricks";
                c.set(xx, sy, z, b);
                Builders.footing(c, xx, sy, z, Fill.of("blackstone"), 14);
                for (int h = 1; h <= 4; h++) if (Builders.natural(c.get(xx, sy + h, z))) c.set(xx, sy + h, z, "air");
            }

        // ---- the curtain wall with crenellations ----
        for (int z = Z0; z <= Z1; z++)
            for (int xx = X0; xx <= X1; xx++) {
                boolean ring = xx < X0 + WALL || xx > X1 - WALL || z < Z0 + WALL || z > Z1 - WALL;
                if (!ring) continue;
                boolean gateN = z < Z0 + WALL && Math.abs(xx) <= 3;
                boolean gateS = z > Z1 - WALL && Math.abs(xx) <= 3;
                for (int h = 1; h <= WALL_H; h++) {
                    if ((gateN || gateS) && h <= 7) { c.set(xx, sy + h, z, "air"); continue; }
                    c.set(xx, sy + h, z, (gateN || gateS) && h == 8 ? "chiseled_polished_blackstone" : brick.at(xx, sy + h, z));
                }
                boolean outer = xx == X0 || xx == X1 || z == Z0 || z == Z1;
                if (outer && ((xx + z) & 1) == 0) c.set(xx, sy + WALL_H + 1, z, brick.at(xx, sy + WALL_H + 1, z));
                if (outer && (xx + z) % 9 == 0 && !(gateN || gateS)) {
                    // arrow slits
                    c.set(xx, sy + 6, z, "iron_bars");
                }
            }
        // banners and soul lanterns flanking both gates
        for (int side : new int[]{-1, 1}) {
            int gx = side * 5;
            c.set(gx, sy + 6, Z0 - 1, Blocks.wallBanner("black", "north"));
            c.set(gx, sy + 6, Z1 + 1, Blocks.wallBanner("black", "south"));
            c.set(side * 4, sy + 9, Z0 - 1, Blocks.wallBanner("red", "north"));
            c.set(side * 4, sy + 9, Z1 + 1, Blocks.wallBanner("red", "south"));
            CKit.brazier(c, side * 6, sy, Z0 - 3, true);
            CKit.brazier(c, side * 6, sy, Z1 + 3, true);
        }
        // ground outside the gates
        for (int xx = -5; xx <= 5; xx++)
            for (int dz = 1; dz <= 4; dz++) {
                c.set(xx, sy, Z0 - dz, "polished_blackstone_bricks");
                c.set(xx, sy, Z1 + dz, "polished_blackstone_bricks");
                Builders.footing(c, xx, sy, Z0 - dz, Fill.of("blackstone"), 10);
                Builders.footing(c, xx, sy, Z1 + dz, Fill.of("blackstone"), 10);
            }
        x.stops.put("stronghold.north", new int[]{0, sy, Z0 - 5});
        x.stops.put("stronghold.south", new int[]{0, sy, Z1 + 5});

        // ---- corner towers, doors facing out to the grounds ----
        int[][] corners = {{X0, Z0, 0}, {X1, Z0, 0}, {X0, Z1, 1}, {X1, Z1, 1}};
        int k = 0;
        for (int[] co : corners) {
            Builders.Tower t = new Builders.Tower(co[0], sy, co[1]);
            t.radius = 5; t.height = 20; t.top = "battlements"; t.wall = brick; t.floor = Fill.of("crimson_planks");
            t.door = co[2] == 0 ? "north" : "south"; t.doorWood = "crimson"; t.window = "iron_bars";
            t.build(c);
            x.door("stronghold.tower" + k, t.doorStep, t.door, 2);
            if (k == 1) x.soul(t.platform[0], t.platform[1], t.platform[2]);
            if (k == 2) x.soul(t.inside[0], t.inside[1], t.inside[2]);
            k++;
        }

        // ---- keep at the back of the courtyard ----
        Builders.House keep = new Builders.House(-11, sy, 80, 23, 17);
        keep.floors = 2; keep.floorHeight = 5; keep.door = "north";
        keep.wall = brick; keep.frame = "crimson_stem"; keep.foundation = Palette.of("polished_blackstone:3,blackstone:1");
        keep.floor = Fill.of("crimson_planks"); keep.roof = "polished_blackstone_brick"; keep.roofStyle = "hip";
        keep.window = "iron_bars"; keep.doorWood = "crimson"; keep.seed = x.seed;
        keep.build(c);
        x.layout.standAt("crimson.keep", keep.inside[0], keep.inside[1], keep.inside[2], 180f);
        x.soul(-9, sy + 5, 94);                                          // upstairs in the keep, back corner
        // banners on the keep front
        for (int bx : new int[]{-8, -4, 4, 8}) c.set(bx, sy + 4, 79, Blocks.wallBanner(bx < 0 ? "black" : "red", "north"));

        // ---- spawn plaza: obelisk, braziers, benches ----
        int oz = 58;
        for (int h = 1; h <= 9; h++) {
            String b = h <= 2 ? "polished_blackstone_bricks" : h == 9 ? "crying_obsidian" : "polished_blackstone";
            c.set(0, sy + h, oz, b);
            if (h <= 2) for (int d = 0; d < 4; d++) c.set(Blocks.DX[d], sy + h, oz + Blocks.DZ[d], h == 1 ? "polished_blackstone_bricks" : Blocks.stairs("polished_blackstone_brick", Blocks.opposite(Blocks.DIRS[d])));
        }
        c.set(0, sy + 10, oz, "soul_campfire[lit=true,facing=north,signal_fire=false,waterlogged=false]");
        for (int[] b : new int[][]{{-10, 54}, {10, 54}, {-10, 74}, {10, 74}}) CKit.brazier(c, b[0], sy, b[1], true);
        Builders.bench(c, -7, sy, 70, "east", 3, "crimson");
        Builders.bench(c, 7, sy, 68, "west", 3, "crimson");
        for (int[] p : new int[][]{{-18, 52}, {18, 52}, {-18, 76}, {18, 76}, {-18, 100}, {18, 100}})
            Builders.lampPost(c, p[0], sy, p[1], "soul", "north");
        x.layout.standAt("spawn", 0, sy, 66, 180f);
        x.layout.standAt("npc.crimson_guide", 4, sy, 63, 180f);

        // ---- the hub portal on the west side of the courtyard ----
        Box portal = CKit.portal(c, -24, sy, 64, false, 3, brick, "soul_lantern[hanging=false,waterlogged=false]");
        x.layout.area("portal.hub", portal);
        c.set(-23, sy + 6, 61, Blocks.wallBanner("light_blue", "east"));
        c.set(-23, sy + 6, 67, Blocks.wallBanner("light_blue", "east"));
        for (int z = 60; z <= 68; z++) c.set(-22, sy, z, "chiseled_polished_blackstone");

        // crates and stalls of the garrison
        Builders.crates(c, 24, sy, 52, x.seed);
        Builders.crates(c, 25, sy, 98, x.seed + 1);
        Builders.crates(c, -26, sy, 98, x.seed + 2);
        x.soul(27, sy, 53);                                               // behind the crates

        // ---- zones ----
        x.layout.zone("crimson_stronghold_court", "Stronghold", 'c', Box.of(X0 + WALL, sy - 3, Z0 + WALL, X1 - WALL, sy + 25, Z1 - WALL), null, Layout.Zone.SAFE);
        x.layout.zone("crimson_stronghold", "Stronghold", 'c', Box.of(-80, sy - 20, 30, 46, sy + 40, 128), null);
        x.layout.area("mobs.stronghold_grounds", Box.of(-74, sy - 4, 40, -40, sy + 10, 118));

        bladeYard(x);
    }

    /** The Edgewraith's ruined duelling yard east of the fortress. */
    private static void bladeYard(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.BLADE_YARD);
        int cx = 66, cz = 74;
        x.res.reserve("blade_yard", Box.of(cx - 15, y - 8, cz - 15, cx + 15, y + 10, cz + 15));
        Palette floor = Palette.of("polished_blackstone:4,polished_blackstone_bricks:3,cracked_polished_blackstone_bricks:2,blackstone:1");
        for (int dz = -14; dz <= 14; dz++)
            for (int dx = -14; dx <= 14; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 14.3) continue;
                int bx = cx + dx, bz = cz + dz;
                c.set(bx, y, bz, d > 13.3 ? "chiseled_polished_blackstone" : floor.at(bx, y, bz));
                Builders.footing(c, bx, y, bz, Fill.of("blackstone"), 10);
                for (int h = 1; h <= 4; h++) if (Builders.natural(c.get(bx, y + h, bz))) c.set(bx, y + h, bz, "air");
            }
        for (int a = 0; a < 10; a++) {
            if (a == 5) continue;                                         // the west gap faces the road
            double ang = a * Math.PI / 5;
            int px = cx + (int) Math.round(Math.cos(ang) * 12), pz = cz + (int) Math.round(Math.sin(ang) * 12);
            CKit.ruinPillar(c, px, y, pz, 7, CKit.BLACK_BRICK, x.seed);
        }
        // swords stuck in the ground (iron bars + a slab hilt)
        for (int[] s : new int[][]{{-4, 3}, {5, -5}, {3, 6}}) {
            c.set(cx + s[0], y + 1, cz + s[1], "iron_bars");
            c.set(cx + s[0], y + 2, cz + s[1], "iron_bars");
            c.set(cx + s[0], y + 3, cz + s[1], "polished_blackstone_button[face=floor,facing=north,powered=false]");
        }
        x.layout.standAt("boss.bladesoul", cx, y, cz, 270f);
        x.layout.zone("crimson_blade_yard", "Wraith Yard", 'c', Box.of(cx - 15, y - 4, cz - 15, cx + 15, y + 20, cz + 15), null);
        x.stops.put("blade_yard", new int[]{cx - 15, y, cz});
        x.soul(cx + 11, y, cz + 9);
        long s = Hash.salt(x.seed, "yard");
        if (Hash.unit(s, 1, 1) < 2) c.set(cx + 12, y + 1, cz - 9, "skeleton_skull[rotation=4]");
    }
}
