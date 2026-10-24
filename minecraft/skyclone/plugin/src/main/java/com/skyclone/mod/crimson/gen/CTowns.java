package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;

/**
 * The two rival towns. Drakehold (the barbarians, west): timber halls on blackstone footings, a bone
 * dragon skull in the square. Carmine Spire (the mages, east): red-brick halls crowned by gold spires.
 */
final class CTowns {

    private CTowns() {}

    // ---- house presets -------------------------------------------------------------------------

    private static Builders.House barbarian(CCtx x, int hx, int hz, int w, int d, String door, int floors) {
        Builders.House h = new Builders.House(hx, x.y(CPlan.DRAKEHOLD), hz, w, d);
        h.floors = floors; h.door = door; h.seed = x.seed;
        h.wall = Palette.of("spruce_planks:5,stripped_spruce_log[axis=y]:1,mud_bricks:1");
        h.frame = "dark_oak_log"; h.foundation = Palette.of("blackstone:3,polished_blackstone:1,cobblestone:1");
        h.floor = Fill.of("spruce_planks"); h.roof = "spruce"; h.roofStyle = "gable"; h.doorWood = "spruce";
        h.window = "brown_stained_glass_pane";
        return h;
    }

    private static Builders.House mage(CCtx x, int hx, int hz, int w, int d, String door, int floors) {
        Builders.House h = new Builders.House(hx, x.y(CPlan.CARMINE), hz, w, d);
        h.floors = floors; h.door = door; h.seed = x.seed;
        h.wall = Palette.of("red_nether_bricks:6,nether_bricks:1,red_terracotta:1");
        h.frame = "stripped_crimson_stem"; h.foundation = Palette.of("polished_blackstone_bricks:3,blackstone:1");
        h.floor = Fill.of("crimson_planks"); h.roof = "red_nether_brick"; h.roofStyle = "hip"; h.doorWood = "crimson";
        h.window = "orange_stained_glass_pane";
        return h;
    }

    private static Builders.House place(CCtx x, String name, Builders.House h) {
        if (!x.res.reserve(name, h.reserve())) return null;
        h.build(x.c);
        return h;
    }

    private static void npcInside(CCtx x, String anchor, Builders.House h, float yaw) {
        if (h == null) return;
        x.layout.standAt(anchor, h.inside[0], h.inside[1], h.inside[2], yaw);
    }

    private static void paved(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, Palette p) {
        for (int z = z0; z <= z1; z++)
            for (int xx = x0; xx <= x1; xx++) {
                c.set(xx, y, z, p.at(xx, y, z));
                Builders.footing(c, xx, y, z, Fill.of("blackstone"), 10);
                for (int h = 1; h <= 4; h++) if (Builders.natural(c.get(xx, y + h, z))) c.set(xx, y + h, z, "air");
            }
    }

    // ---- Drakehold --------------------------------------------------------------------------------

    static void drakehold(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.DRAKEHOLD);
        Palette plazaP = Palette.of("coarse_dirt:3,gravel:2,blackstone:2,polished_blackstone:1,packed_mud:2");
        int px0 = -175, pz0 = 10, px1 = -151, pz1 = 34;
        x.res.reserve("drakehold_square", Box.of(px0, y - 6, pz0, px1, y + 12, pz1));
        paved(c, px0, pz0, px1, pz1, y, plazaP);
        CKit.dragonSkull(c, -163, y, 18, "south");
        for (int[] p : new int[][]{{px0, pz0}, {px1, pz0}, {px0, pz1}, {px1, pz1}}) CKit.brazier(c, p[0], y, p[1], false);
        for (int[] p : new int[][]{{-171, 14}, {-155, 14}}) CKit.bannerPole(c, p[0], y, p[1], "red", 0);
        Builders.campfire(c, -170, y, 27);
        Builders.bench(c, -158, y, 30, "north", 3, "spruce");

        Builders.House hall = barbarian(x, -176, -16, 25, 13, "south", 2);
        hall.chimney = true;
        hall = place(x, "drakehold_hall", hall);
        if (hall != null) {
            npcInside(x, "npc.barbarian_leader", hall, 0f);
            x.door("drakehold_hall", hall.doorStep, hall.door, 2);
            x.soul(-174, y + 4, -14);                                       // upstairs corner of the hall
            for (int bx : new int[]{-172, -156}) c.set(bx, y + 4, -17, Blocks.wallBanner("red", "north"));
        }
        Builders.House tavern = place(x, "drakehold_tavern", barbarian(x, -205, 2, 13, 11, "east", 1));
        npcInside(x, "npc.brewer_dragontail", tavern, 270f);
        if (tavern != null) x.door("drakehold_tavern", tavern.doorStep, tavern.door, 2);
        Builders.House forge = barbarian(x, -205, 24, 13, 11, "east", 1);
        forge.chimney = true; forge.chimneyBlock = "polished_blackstone_bricks";
        forge = place(x, "drakehold_forge", forge);
        npcInside(x, "npc.generator_smith_dragontail", forge, 270f);
        if (forge != null) {
            x.door("drakehold_forge", forge.doorStep, forge.door, 2);
            c.set(forge.inside[0] - 3, y + 1, forge.inside[2] + 2, "anvil[facing=north]");
            c.set(forge.inside[0] - 3, y + 1, forge.inside[2] - 2, "blast_furnace[facing=east,lit=true]");
        }
        Builders.House bank = place(x, "drakehold_bank", barbarian(x, -147, -2, 11, 9, "west", 1));
        npcInside(x, "npc.drakehold_banker", bank, 90f);
        if (bank != null) x.door("drakehold_bank", bank.doorStep, bank.door, 2);
        Builders.House shop = place(x, "drakehold_quartermaster", barbarian(x, -147, 24, 11, 9, "west", 1));
        npcInside(x, "npc.barbarian_shop", shop, 90f);
        if (shop != null) x.door("drakehold_shop", shop.doorStep, shop.door, 2);

        // homes south of the square
        int[][] homes = {{-204, 48, 9, 8}, {-190, 52, 9, 8}, {-150, 50, 9, 8}, {-138, 56, 9, 8}, {-204, 62, 9, 8}};
        int i = 0;
        for (int[] hm : homes) {
            Builders.House h = place(x, "drakehold_home" + i, barbarian(x, hm[0], hm[1], hm[2], hm[3], "north", 1));
            if (h != null) {
                x.door("drakehold_home" + i, h.doorStep, h.door, 2);
                if (i == 1) x.soul(h.inside[0] + 2, h.inside[1], h.inside[2] + 2);
                if (i == 3) x.layout.standAt("npc.drakehold_lore.1", h.doorStep[0] + 1, h.doorStep[1], h.doorStep[2] - 1, 0f);
            }
            i++;
        }
        // stalls
        Builders.Stall st = Builders.marketStall(c, -170, y, 32, "north", "red", "black", x.seed);
        x.layout.standAt("npc.peddler_dragontail", st.vendor()[0], st.vendor()[1], st.vendor()[2], 180f);
        Builders.Stall bz = Builders.marketStall(c, -156, y, 12, "south", "orange", "black", x.seed + 3);
        x.layout.standAt("npc.drakehold_bazaar", bz.vendor()[0], bz.vendor()[1], bz.vendor()[2], 0f);

        // east gate toward the fields, with the envoy
        int gx = -124;
        gate(c, gx, y, 22, false, "spruce", "red");
        x.layout.standAt("npc.barbarian_envoy", gx - 3, y, 16, 90f);
        x.stops.put("drakehold_gate", new int[]{gx + 3, y, 22});
        x.stops.put("drakehold_square", new int[]{-163, y, 30});
        x.layout.standAt("warp.drakehold", -163, y, 30, 180f);
        Builders.crates(c, -130, y, 12, x.seed + 11);
        x.soul(-131, y, 11);
        // lamps round the square
        for (int[] p : new int[][]{{-178, 8}, {-148, 8}, {-178, 36}, {-148, 36}, {-163, 40}, {-140, 22}})
            Builders.lampPost(c, p[0], y, p[1], "post", "north");

        // a log palisade with gaps for the east gate road and the south road
        CKit.townWall(x, new int[][]{{-212, -26}, {-118, -26}, {-118, 80}, {-212, 80}}, 3,
                Palette.of("spruce_log[axis=y]:5,stripped_spruce_log[axis=y]:1"), "spruce_fence", "dark_oak_log[axis=y]",
                (wx, wz) -> (wx == -118 && wz >= 14 && wz <= 30) || (wz == 80 && wx >= -174 && wx <= -160));
        x.layout.zone("crimson_drakehold", "Drakehold", 'e', Box.of(-212, y - 10, -36, -114, y + 40, 82), null, Layout.Zone.SAFE);
        x.layout.zone("crimson_drakehold_hall", "Warchief's Hall", 'e', Box.of(-176, y - 2, -16, -152, y + 16, -4), null, Layout.Zone.SAFE);
    }

    // ---- Carmine Spire -----------------------------------------------------------------------------

    static void carmine(CCtx x) {
        VoxelCanvas c = x.c;
        int y = x.y(CPlan.CARMINE);
        Palette plazaP = Palette.of("polished_blackstone_bricks:4,red_nether_bricks:3,chiseled_polished_blackstone:1,polished_blackstone:2");
        int px0 = 151, pz0 = 10, px1 = 175, pz1 = 34;
        x.res.reserve("carmine_square", Box.of(px0, y - 6, pz0, px1, y + 12, pz1));
        paved(c, px0, pz0, px1, pz1, y, plazaP);
        Builders.fountain(c, 163, y, 22, 3, Palette.of("red_nether_bricks:4,gold_block:1"), x.seed);
        for (int[] p : new int[][]{{px0, pz0}, {px1, pz0}, {px0, pz1}, {px1, pz1}}) CKit.brazier(c, p[0], y, p[1], true);

        Builders.House palace = mage(x, 150, -32, 27, 17, "south", 2);
        palace.floorHeight = 6;
        palace = place(x, "carmine_palace", palace);
        if (palace != null) {
            npcInside(x, "npc.mage_leader", palace, 0f);
            x.door("carmine_palace", palace.doorStep, palace.door, 2);
            x.soul(174, y + 6, -30);                                       // throne room gallery, back corner
            // a throne: gold block seat with stairs
            int tx = palace.inside[0], tz = -29;
            c.set(tx, y + 1, tz, "gold_block");
            c.set(tx, y + 2, tz, "gold_block");
            c.set(tx, y + 1, tz + 1, Blocks.stairs("red_nether_brick", "north"));
            for (int bx : new int[]{156, 170}) c.set(bx, y + 5, -33, Blocks.wallBanner("purple", "north"));
        }
        // gold-capped spires flanking the palace
        for (int sx : new int[]{142, 184}) {
            Builders.Tower t = new Builders.Tower(sx, y, -22);
            t.radius = 4; t.height = 24; t.top = "cone"; t.wall = Palette.of("red_nether_bricks:5,nether_bricks:1");
            t.roofFill = Palette.of("gold_block:3,orange_terracotta:1"); t.floor = Fill.of("crimson_planks");
            t.door = "south"; t.doorWood = "crimson"; t.window = "orange_stained_glass_pane";
            if (x.res.reserve("carmine_spire" + sx, t.reserve())) {
                t.build(c);
                x.door("carmine_spire" + sx, t.doorStep, t.door, 4);
                if (sx == 184) x.soul(t.inside[0], t.inside[1] + 10, t.inside[2]);
            }
        }
        Builders.House tavern = place(x, "carmine_tavern", mage(x, 184, 0, 13, 11, "west", 1));
        npcInside(x, "npc.brewer_scarleton", tavern, 90f);
        if (tavern != null) x.door("carmine_tavern", tavern.doorStep, tavern.door, 2);
        Builders.House forge = mage(x, 184, 24, 13, 11, "west", 1);
        forge.chimney = true; forge.chimneyBlock = "nether_bricks";
        forge = place(x, "carmine_forge", forge);
        npcInside(x, "npc.generator_smith_scarleton", forge, 90f);
        if (forge != null) x.door("carmine_forge", forge.doorStep, forge.door, 2);
        Builders.House lamp = place(x, "carmine_lamps", mage(x, 128, -2, 11, 9, "east", 1));
        npcInside(x, "npc.lampwright", lamp, 270f);
        if (lamp != null) {
            x.door("carmine_lamps", lamp.doorStep, lamp.door, 2);
            for (int dz = -2; dz <= 2; dz += 2) c.set(lamp.inside[0] - 3, y + 1, lamp.inside[2] + dz, dz == 0 ? "ochre_froglight" : "pearlescent_froglight");
        }
        Builders.House phone = place(x, "carmine_phones", mage(x, 128, 24, 11, 9, "east", 1));
        npcInside(x, "npc.phone_dealer", phone, 270f);
        if (phone != null) x.door("carmine_phones", phone.doorStep, phone.door, 2);
        Builders.House bank = place(x, "carmine_bank", mage(x, 148, 44, 11, 9, "north", 1));
        npcInside(x, "npc.carmine_banker", bank, 180f);
        if (bank != null) x.door("carmine_bank", bank.doorStep, bank.door, 2);
        Builders.House shop = place(x, "carmine_quartermaster", mage(x, 168, 44, 11, 9, "north", 1));
        npcInside(x, "npc.mage_shop", shop, 180f);
        if (shop != null) {
            x.door("carmine_shop", shop.doorStep, shop.door, 2);
            x.soul(shop.inside[0] + 3, shop.inside[1], shop.inside[2] + 2);
        }
        Builders.House home = place(x, "carmine_home", mage(x, 188, 50, 9, 8, "north", 1));
        if (home != null) {
            x.door("carmine_home", home.doorStep, home.door, 2);
            x.layout.standAt("npc.carmine_lore.1", home.doorStep[0] - 1, home.doorStep[1], home.doorStep[2] - 1, 0f);
        }
        // more of the order's houses so the town reads as lived-in
        int[][] more = {{130, 44, 0}, {200, -2, 1}, {200, 22, 1}, {124, -30, 2}};
        String[] doors = {"north", "west", "east"};
        for (int i = 0; i < more.length; i++) {
            Builders.House h = mage(x, more[i][0], more[i][1], 9, 8, doors[more[i][2]], 1);
            h.roofStyle = i % 2 == 0 ? "gable" : "hip";
            h = place(x, "carmine_home" + (i + 2), h);
            if (h != null) x.door("carmine_home" + (i + 2), h.doorStep, h.door, 2);
        }

        Builders.Stall st = Builders.marketStall(c, 156, y, 32, "north", "purple", "orange", x.seed + 5);
        x.layout.standAt("npc.peddler_scarleton", st.vendor()[0], st.vendor()[1], st.vendor()[2], 180f);
        Builders.Stall bz = Builders.marketStall(c, 170, y, 32, "north", "magenta", "orange", x.seed + 6);
        x.layout.standAt("npc.carmine_bazaar", bz.vendor()[0], bz.vendor()[1], bz.vendor()[2], 180f);

        int gx = 124;
        gate(c, gx, y, 22, false, "crimson", "purple");
        x.layout.standAt("npc.mage_envoy", gx + 3, y, 16, 270f);
        x.stops.put("carmine_gate", new int[]{gx - 3, y, 22});
        x.stops.put("carmine_square", new int[]{163, y, 30});
        x.layout.standAt("warp.carmine", 163, y, 29, 180f);
        for (int[] p : new int[][]{{148, 8}, {178, 8}, {148, 36}, {178, 36}, {163, 40}, {140, 22}})
            Builders.lampPost(c, p[0], y, p[1], "soul", "north");
        Builders.crates(c, 199, y, 40, x.seed + 13);
        x.soul(203, y, 43);

        // a low red-brick town wall with gates west (the envoy's gate) and south (to the Courtyard)
        CKit.townWall(x, new int[][]{{117, -42}, {212, -42}, {212, 80}, {117, 80}}, 2,
                Palette.of("red_nether_bricks:5,nether_bricks:1"), "red_nether_brick_wall", "gold_block",
                (wx, wz) -> (wx == 117 && wz >= 14 && wz <= 30) || (wz == 80 && wx >= 168 && wx <= 184));
        x.layout.zone("crimson_carmine", "Carmine Spire", 'd', Box.of(116, y - 10, -40, 212, y + 50, 82), null, Layout.Zone.SAFE);
        x.layout.zone("crimson_carmine_palace", "Throne Hall", 'd', Box.of(150, y - 2, -32, 176, y + 18, -16), null, Layout.Zone.SAFE);
    }

    /** A town gate: two posts with a lintel across a road running east-west (alongX=false: the gate spans Z). */
    private static void gate(VoxelCanvas c, int gx, int y, int gz, boolean alongX, String wood, String banner) {
        int half = 4;
        for (int dz = -half; dz <= half; dz++) {
            boolean post = Math.abs(dz) == half;
            int z = gz + dz;
            if (post) {
                for (int h = 1; h <= 6; h++) c.set(gx, y + h, z, h <= 1 ? "polished_blackstone_bricks" : Blocks.wood(wood, "log") + "[axis=y]");
                Builders.footing(c, gx, y + 1, z, Fill.of("blackstone"), 8);
                c.set(gx, y + 7, z, Blocks.lantern(false));
            } else {
                c.set(gx, y + 6, z, Blocks.wood(wood, "log") + "[axis=z]");
            }
        }
        for (int dz : new int[]{-half, half}) {
            c.set(gx + 1, y + 4, gz + dz, Blocks.wallBanner(banner, "east"));
            c.set(gx - 1, y + 4, gz + dz, Blocks.wallBanner(banner, "west"));
        }
    }
}
