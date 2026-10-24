package com.skyclone.mod.kuudra.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Shapes;

import java.util.ArrayList;
import java.util.List;

/**
 * Leviathan's Hollow (reference: Kuudra's Hollow): the INSTANCED arena one hunt is fought in.
 * Pure Java - {@code bash tools/preview.sh com.skyclone.mod.kuudra.gen.KuudraArenaGenerator}.
 *
 * <pre>
 *            north (-z)
 *   ~~~~~~~~ the lava lake: the Leviathan surfaces at kuudra.pos.* ~~~~~~~~
 *   ~~~~~~~~~~~~~~~~~~~ kuudra.dps (the beast beaches here) ~~~~~~~~~~~~~~~
 *   fuel.*  ---- shore ----  [ ballista platform: ballista, 6 pads, cannon ]  ---- fuel.*
 *          supply.* crates scattered over the cavern floor, spawn.mob.* on the rim
 *            south (+z)                      spawn / chests / NPCs on the platform's south edge
 *   under the lake:   the Lair (Infernal only)   - lair.spawn, lair.head
 *   under the floor:  the Gullet (stun phase)    - stomach.spawn, six orbs
 * </pre>
 *
 * A dome of blackstone closes it all in. Everything the module needs at runtime is an anchor, an area
 * or a {@code kuudra.*} layout data entry; the module never hard-codes a coordinate except through the
 * public constants below (and those are what this generator wrote).
 */
public final class KuudraArenaGenerator implements IslandGenerator {

    public static final String ISLAND = "kuudra";
    /** Cavern floor top block; players stand at FLOOR + 1. The lava lake is flush with it. */
    public static final int FLOOR = 63;
    static final int R_FLOOR = 92, R_WALL = 89, R_DOME = 96;
    /** The platform: centre, radius, top block. */
    static final int PCX = 0, PCZ = 6, PR = 15, PY = 65;
    /** The ballista's base block (platform top under its centre). */
    public static final int BX = 0, BY = PY, BZ = 2;
    /**
     * Where the beached beast's front face reaches in the DPS phase: one block onto the shore in front of the
     * rail gap (the lake edge is z = -14 for |x| <= 26, see {@link #shoreZ}), so a hunter on the shore or the
     * platform lip is in sword reach. Anchor {@code kuudra.beach}; the module falls back to this constant when a
     * persisted layout predates the anchor.
     */
    public static final int BEACH_Z = -13;
    /** The lair floor top block (Infernal). */
    static final int LAIR_Y = 24;
    /** Stomach (the Gullet) centre and radii. */
    static final int SX = 0, SY = 51, SZ = 50;

    @Override public String islandId() { return ISLAND; }

    @Override public int version() { return 1; }

    @Override public Box bounds() { return new Box(-100, 18, -100, 100, 116, 100); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // All per-run state is local (the game runs a generator twice: layout first, then the build).
        Noise noise = new Noise(Hash.salt(seed, "kuudra.terrain"));
        Reservations res = new Reservations();
        List<int[]> keep = new ArrayList<>();   // {x, z, radius}: no mounds or decor near these

        // ---- 0. plan the spots first (they only depend on the lake line) ----
        List<int[]> supply = supplySpots(noise);
        List<int[]> fuel = fuelSpots(noise);
        List<int[]> mobSpots = mobSpots(noise);
        for (int[] s : supply) keep.add(new int[]{s[0], s[1], 4});
        for (int[] s : fuel) keep.add(new int[]{s[0], s[1], 4});
        for (int[] s : mobSpots) keep.add(new int[]{s[0], s[1], 3});
        keep.add(new int[]{PCX, PCZ, PR + 6});

        // ---- 1. floor and the lava lake ----
        Palette topDark = Palette.of("blackstone:7,polished_blackstone:1,blackstone:1,gilded_blackstone:1");
        Palette topBasalt = Palette.of("basalt[axis=y]:5,smooth_basalt:3,polished_basalt[axis=y]:1,blackstone:1");
        Palette topCrimson = Palette.of("netherrack:5,crimson_nylium:3,nether_wart_block:1,blackstone:1");
        Palette top = topDark;
        Palette under = Palette.of("blackstone:3,netherrack:2,basalt[axis=y]:1");
        Palette bed = Palette.of("blackstone:3,magma_block:2,basalt[axis=y]:1");
        for (int x = -R_FLOOR; x <= R_FLOOR; x++)
            for (int z = -R_FLOOR; z <= R_FLOOR; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > R_FLOOR) continue;
                if (isLake(noise, x, z)) {
                    for (int y = 56; y <= 60; y++) c.set(x, y, z, bed.at(x, y, z));
                    for (int y = 61; y <= FLOOR; y++) c.set(x, y, z, "lava");
                } else {
                    for (int y = 59; y < FLOOR; y++) c.set(x, y, z, under.at(x, y, z));
                    double region = noise.fbm2(x / 34.0 + 40, z / 34.0 - 17, 3);
                    Palette here = region < -0.18 ? topBasalt : region > 0.22 ? topCrimson : topDark;
                    c.set(x, FLOOR, z, here.at(x, FLOOR, z));
                    if (d < R_WALL - 2 && !kept(keep, x, z, 0) && noise.fbm2(x / 17.0, z / 17.0, 3) > 0.38)
                        c.set(x, FLOOR + 1, z, here.at(x, FLOOR + 1, z));      // low mounds, one block, walkable
                }
            }

        // ---- 2. the dome and the rim wall ----
        Palette rock = Palette.of("blackstone:5,basalt[axis=y]:3,netherrack:2,smooth_basalt:1,magma_block:1");
        for (int x = -R_DOME; x <= R_DOME; x++)
            for (int z = -R_DOME; z <= R_DOME; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > R_DOME) continue;
                int outer = (int) Math.round(62 + 48 * Math.sqrt(Math.max(0, 1 - (d / R_DOME) * (d / R_DOME))));
                int from;
                if (d >= R_WALL) from = 56;
                else from = (int) Math.ceil(62 + 46 * Math.sqrt(Math.max(0, 1 - (d / 94.0) * (d / 94.0))));
                for (int y = from; y <= outer; y++) c.set(x, y, z, rock.at(x, y, z));
                if (d < R_WALL - 3 && Hash.chance(Hash.salt(seed, "glow"), x, z, 0.010)) {
                    c.set(x, from, z, "shroomlight");
                    if (Hash.chance(Hash.salt(seed, "vine"), x, z, 0.5)) {
                        int len = Hash.range(Hash.salt(seed, "vlen"), x, 0, z, 1, 4);
                        for (int i = 1; i <= len; i++) c.set(x, from - i, z, i == len ? "weeping_vines" : "weeping_vines_plant");
                    }
                }
            }

        // rock spires standing out of the lava (kept clear of where the beast surfaces)
        int[][] islets = {{-66, -40, 4}, {-30, -74, 3}, {34, -76, 4}, {68, -30, 3}, {-8, -82, 2}, {12, -30, 2}};
        for (int[] is : islets) {
            int top0 = FLOOR + 3 + Hash.range(Hash.salt(seed, "islet"), is[0], 0, is[1], 2, 14);
            for (int x = is[0] - is[2] - 1; x <= is[0] + is[2] + 1; x++)
                for (int z = is[1] - is[2] - 1; z <= is[1] + is[2] + 1; z++) {
                    double dd = Math.hypot(x - is[0], z - is[1]);
                    if (dd > is[2] + 0.3) continue;
                    int h = (int) Math.round(top0 - (top0 - FLOOR) * (dd / (is[2] + 0.5)) * 0.8);
                    for (int y = 56; y <= h; y++) c.set(x, y, z, rock.at(x, y, z));
                }
        }
        // stalactites hanging from the dome
        long st = Hash.salt(seed, "stalactite");
        for (int k = 0; k < 26; k++) {
            int sx = Hash.range(st, k, 1, 0, -70, 70), sz = Hash.range(st, k, 2, 0, -70, 70);
            if (Math.hypot(sx, sz) > 72) continue;
            int ceil = ceilingAt(c, sx, sz);
            int len = Hash.range(st, k, 3, 0, 5, 16);
            for (int i = 0; i < len; i++) {
                double r = 2.2 * (1 - i / (double) len);
                for (int x = sx - 2; x <= sx + 2; x++)
                    for (int z = sz - 2; z <= sz + 2; z++)
                        if (Math.hypot(x - sx, z - sz) <= r + 0.2) c.set(x, ceil - 1 - i, z, i == len - 1 ? "magma_block" : rock.at(x, ceil - 1 - i, z));
            }
        }

        // ---- 3. the ballista platform ----
        Palette deck = Palette.of("polished_blackstone_bricks:6,cracked_polished_blackstone_bricks:2,polished_blackstone:2");
        res.reserve("platform", Box.of(PCX - PR - 2, 58, PCZ - PR - 2, PCX + PR + 2, 80, PCZ + PR + 2));
        for (int x = PCX - PR - 2; x <= PCX + PR + 2; x++)
            for (int z = PCZ - PR - 2; z <= PCZ + PR + 2; z++) {
                double d = Math.hypot(x - PCX, z - PCZ);
                if (d > PR + 2 || isLake(noise, x, z)) continue;
                int topY = d <= PR ? PY : PY - 1;
                for (int y = 59; y <= topY; y++) c.set(x, y, z, y == topY ? deck.at(x, y, z) : "polished_blackstone");
                for (int y = topY + 1; y <= topY + 3; y++) c.set(x, y, z, "air");
                if (d > PR) c.set(x, topY, z, "polished_blackstone");
            }
        // the north lip over the lava, so nobody walks off the platform into the lake by accident
        for (int x = -PR; x <= PR; x++)
            for (int z = PCZ - PR - 2; z <= PCZ - PR + 1; z++) {
                double d = Math.hypot(x - PCX, z - PCZ);
                if (d > PR || d < PR - 1.5) continue;
                if (Math.abs(x) <= 2) continue;   // a gap in the rail facing the beast (and the DPS shore)
                c.set(x, PY + 1, z, "polished_blackstone_wall");
            }

        // pads (supplies are set down here)
        int[][] pads = {{6, 5}, {0, 9}, {-6, 5}, {-6, -1}, {0, -5}, {6, -1}};
        for (int i = 0; i < pads.length; i++) {
            int px = pads[i][0], pz = pads[i][1];
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    c.set(px + dx, PY, pz + dz, dx == 0 && dz == 0 ? "gilded_blackstone" : "red_nether_bricks");
            layout.standAt("pad." + i, px, PY, pz, 180f);
        }

        // the ballista (written whole; the module clears and rebuilds it every hunt)
        for (BallistaShape.Part p : BallistaShape.foundation()) c.set(BX + p.dx(), BY + p.dy(), BZ + p.dz(), p.block());
        for (BallistaShape.Part p : BallistaShape.built()) c.set(BX + p.dx(), BY + p.dy(), BZ + p.dz(), p.block());

        // the cannon (Burning and up: ride it into the beast's maw)
        int cx = 9, cz = -3;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) c.set(cx + dx, PY + 1, cz + dz, "polished_blackstone_bricks");
        c.set(cx, PY + 2, cz, "blast_furnace[facing=north,lit=true]");
        c.set(cx, PY + 3, cz, "iron_block");
        c.set(cx, PY + 3, cz - 1, "iron_block");
        c.set(cx, PY + 4, cz - 1, "iron_block");
        c.set(cx, PY + 4, cz - 2, "iron_block");
        c.set(cx, PY + 5, cz - 2, "anvil[facing=east]");
        c.set(cx - 1, PY + 2, cz, "polished_blackstone_wall");
        c.set(cx + 1, PY + 2, cz, "polished_blackstone_wall");
        layout.area("cannon", Box.of(cx - 1, PY + 1, cz - 2, cx + 1, PY + 5, cz + 1));
        layout.data("kuudra.cannon", cx + "," + PY + "," + cz);

        // reward chests and the arrival
        c.set(-5, PY + 1, 17, "chest[facing=south]");
        c.set(5, PY + 1, 17, "chest[facing=south]");
        layout.anchor("chest.free", -4.5, PY + 1, 17.5, 0f);
        layout.anchor("chest.paid", 5.5, PY + 1, 17.5, 0f);
        layout.data("kuudra.chest.free", "-5," + (PY + 1) + ",17");
        layout.data("kuudra.chest.paid", "5," + (PY + 1) + ",17");
        layout.standAt("spawn", 0, PY, 17, 180f);
        layout.standAt("rewards", 0, PY, 14, 180f);
        layout.standAt("npc.ferryman", -3, PY, 19, 180f);
        layout.standAt("npc.quartermaster", 3, PY, 19, 180f);
        for (int[] l : new int[][]{{-12, 10}, {12, 10}, {-12, 0}, {12, 1}, {-8, -6}, {5, -8}, {-9, 16}, {9, 16}})
            Builders.lampPost(c, l[0], PY, l[1], "soul", "north");

        // ---- 4. the Lair under the lake (Infernal) ----
        Palette lairRock = Palette.of("blackstone:4,basalt[axis=y]:3,magma_block:1,gilded_blackstone:1");
        Palette lairFloor = Palette.of("blackstone:4,polished_blackstone:2,smooth_basalt:2,basalt[axis=y]:1");
        double lrx = 36, lrz = 28, lry = 32;
        int lcz = -45;
        for (int x = -37; x <= 37; x++)
            for (int z = lcz - 29; z <= lcz + 29; z++)
                for (int y = LAIR_Y - 2; y <= LAIR_Y + 32; y++) {
                    double ox = x / lrx, oz = (z - lcz) / lrz, oy = Math.max(0, y - LAIR_Y) / lry;
                    double outer = ox * ox + oz * oz + oy * oy;
                    if (outer > 1) continue;
                    double ix = x / (lrx - 2), iz = (z - lcz) / (lrz - 2), iy = Math.max(0, y - LAIR_Y) / (lry - 2);
                    boolean inside = ix * ix + iz * iz + iy * iy < 1;
                    if (y <= LAIR_Y) c.set(x, y, z, inside ? lairFloor.at(x, y, z) : lairRock.at(x, y, z));
                    else if (!inside) c.set(x, y, z, lairRock.at(x, y, z));
                }
        // the head's pit and the lava runs
        for (int x = -7; x <= 7; x++)
            for (int z = -71; z <= -61; z++) {
                double e = (x / 7.0) * (x / 7.0) + ((z + 66) / 5.0) * ((z + 66) / 5.0);
                if (e <= 1) { c.set(x, LAIR_Y, z, "lava"); c.set(x, LAIR_Y - 1, z, "lava"); }
            }
        for (int[] pl : new int[][]{{-18, -40}, {18, -40}, {-11, -56}, {11, -56}})
            Shapes.cylinder(c, pl[0], LAIR_Y + 1, pl[1], 1.6, 30, Fill.of("basalt[axis=y]"), 0);
        layout.standAt("lair.spawn", 0, LAIR_Y, -23, 180f);
        layout.anchor("lair.head", 0.5, LAIR_Y - 1, -65.5, 0f);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * (0.15 + 0.7 * i / 5.0);
            layout.standAt("lair.slam." + i, (int) Math.round(Math.cos(a) * 22), LAIR_Y, (int) Math.round(lcz + 8 - Math.sin(a) * 12), 0f);
        }
        layout.zone("kuudra_lair", "The Lair", '4', Box.of(-36, LAIR_Y - 2, lcz - 28, 36, 56, lcz + 28), null);
        layout.area("lair", Box.of(-33, LAIR_Y + 1, lcz - 25, 33, LAIR_Y + 20, lcz + 25));

        // ---- 5. the Gullet under the floor (stun phase) ----
        Palette flesh = Palette.of("nether_wart_block:6,red_nether_bricks:2,crimson_hyphae[axis=y]:1,shroomlight:1");
        double srx = 12, sry = 8, srz = 10;
        for (int x = SX - 13; x <= SX + 13; x++)
            for (int z = SZ - 11; z <= SZ + 11; z++)
                for (int y = SY - 9; y <= SY + 9; y++) {
                    double ox = (x - SX) / srx, oy = (y - SY) / sry, oz = (z - SZ) / srz;
                    if (ox * ox + oy * oy + oz * oz > 1) continue;
                    double ix = (x - SX) / (srx - 2), iy = (y - SY) / (sry - 2), iz = (z - SZ) / (srz - 2);
                    boolean inside = ix * ix + iy * iy + iz * iz < 1;
                    if (!inside || y <= SY - 5) c.set(x, y, z, y <= SY - 5 && inside ? "nether_wart_block" : flesh.at(x, y, z));
                    else c.set(x, y, z, "air");
                }
        int gulletFloor = SY - 5;
        StringBuilder orbs = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6.0;
            int ox = (int) Math.round(SX + Math.cos(a) * 7.0), oz = (int) Math.round(SZ + Math.sin(a) * 5.5);
            c.set(ox, gulletFloor + 1, oz, "nether_wart_block");
            c.set(ox, gulletFloor + 2, oz, "nether_wart_block");
            c.set(ox, gulletFloor + 3, oz, "ochre_froglight");
            if (orbs.length() > 0) orbs.append(';');
            orbs.append(ox).append(',').append(gulletFloor + 3).append(',').append(oz);
            layout.anchor("stomach.orb." + i, ox + 0.5, gulletFloor + 3, oz + 0.5, 0f);
        }
        layout.data("kuudra.orbs", orbs.toString());
        layout.standAt("stomach.spawn", SX, gulletFloor, SZ, 180f);
        layout.zone("kuudra_stomach", "The Gullet", 'd', Box.of(SX - 12, SY - 8, SZ - 10, SX + 12, SY + 7, SZ + 10), null);
        layout.area("stomach", Box.of(SX - 10, gulletFloor + 1, SZ - 8, SX + 10, SY + 5, SZ + 8));

        // ---- 6. the cavern floor: pillars, ribs, pools, fungi ----
        int[][] pillars = {{-44, 52}, {46, 58}, {-72, 18}, {72, 14}, {-24, 74}, {26, 76}};
        for (int[] pl : pillars) {
            if (kept(keep, pl[0], pl[1], 3)) continue;
            if (!res.reserve("pillar" + pl[0], Box.of(pl[0] - 3, 60, pl[1] - 3, pl[0] + 3, 110, pl[1] + 3))) continue;
            int h = ceilingAt(c, pl[0], pl[1]) - (FLOOR + 1);
            Shapes.cylinder(c, pl[0], FLOOR + 1, pl[1], 2.6, h, Fill.parse("basalt[axis=y]:4,polished_basalt[axis=y]:2,blackstone:1"), 0);
            keep.add(new int[]{pl[0], pl[1], 4});
        }
        // the bones of something older than the beast
        int[][] spines = {{-56, 40, 1}, {50, 36, -1}};
        for (int[] sp : spines) {
            int x0 = sp[0], z0 = sp[1];
            if (kept(keep, x0, z0, 10)) continue;
            if (!res.reserve("bones" + x0, Box.of(x0 - 10, 60, z0 - 6, x0 + 10, 76, z0 + 6))) continue;
            for (int i = -9; i <= 9; i++) c.set(x0 + i, FLOOR + 1, z0, "bone_block[axis=x]");
            for (int i = -8; i <= 8; i += 4) {
                Shapes.line(c, x0 + i, FLOOR + 1, z0 - 5, x0 + i, FLOOR + 6, z0 - 2, 0.6, Fill.of("bone_block[axis=y]"));
                Shapes.line(c, x0 + i, FLOOR + 6, z0 - 2, x0 + i, FLOOR + 7, z0, 0.6, Fill.of("bone_block[axis=y]"));
                Shapes.line(c, x0 + i, FLOOR + 1, z0 + 5, x0 + i, FLOOR + 6, z0 + 2, 0.6, Fill.of("bone_block[axis=y]"));
                Shapes.line(c, x0 + i, FLOOR + 6, z0 + 2, x0 + i, FLOOR + 7, z0, 0.6, Fill.of("bone_block[axis=y]"));
            }
            keep.add(new int[]{x0, z0, 11});
        }
        int[][] pools = {{-32, 30}, {33, 44}, {2, 62}, {-60, 66}, {62, 52}};
        for (int[] po : pools) {
            if (kept(keep, po[0], po[1], 4)) continue;
            for (int x = po[0] - 3; x <= po[0] + 3; x++)
                for (int z = po[1] - 3; z <= po[1] + 3; z++) {
                    if (Math.hypot(x - po[0], z - po[1]) > 3.2) continue;
                    c.set(x, FLOOR + 1, z, "air");
                    c.set(x, FLOOR, z, "lava");
                    c.set(x, FLOOR - 1, z, "lava");
                }
            keep.add(new int[]{po[0], po[1], 5});
        }
        long fs = Hash.salt(seed, "fungi");
        for (int x = -R_WALL + 3; x <= R_WALL - 3; x++)
            for (int z = -R_WALL + 3; z <= R_WALL - 3; z++) {
                if (Math.hypot(x, z) > R_WALL - 3 || kept(keep, x, z, 0)) continue;
                int ty = c.topY(x, z, FLOOR - 1, FLOOR + 2);
                if (ty == Integer.MIN_VALUE || !c.isAir(x, ty + 1, z)) continue;
                String b = c.get(x, ty, z);
                if (b == null || !(b.startsWith("netherrack") || b.startsWith("crimson_nylium") || b.startsWith("nether_wart"))) continue;
                double r = Hash.unit(fs, x, z);
                if (r < 0.05) c.set(x, ty + 1, z, "crimson_roots");
                else if (r < 0.075) c.set(x, ty + 1, z, "nether_sprouts");
                else if (r < 0.085) c.set(x, ty + 1, z, "crimson_fungus");
            }
        // a wrecked jetty on the west shore: the last crew that came here
        for (int i = 0; i < 9; i++) {
            int x = -48 + i % 3, z = shoreZ(noise, -48) + 1 - i / 3;
            if (isLake(noise, x, z)) c.set(x, FLOOR, z, i % 4 == 0 ? "crimson_slab[type=top]" : "crimson_planks");
        }

        // ---- 7. what the module needs to know ----
        for (int i = 0; i < supply.size(); i++) {
            int[] s = supply.get(i);
            layout.standAt("supply." + i, s[0], ground(c, s[0], s[1]), s[1], 0f);
        }
        for (int i = 0; i < fuel.size(); i++) {
            int[] s = fuel.get(i);
            layout.standAt("fuel." + i, s[0], ground(c, s[0], s[1]), s[1], 0f);
        }
        for (int i = 0; i < mobSpots.size(); i++) {
            int[] s = mobSpots.get(i);
            layout.standAt("spawn.mob." + i, s[0], ground(c, s[0], s[1]), s[1], 0f);
        }
        int[][] kpos = {{-45, -50}, {-15, -62}, {20, -60}, {50, -45}, {0, -42}, {-30, -36}};
        for (int i = 0; i < kpos.length; i++) layout.anchor("kuudra.pos." + i, kpos[i][0] + 0.5, 60, kpos[i][1] + 0.5, 0f);
        layout.anchor("kuudra.dps", 0.5, 60, -22.5, 0f);
        layout.anchor("kuudra.beach", 0.5, FLOOR + 1, BEACH_Z, 0f);
        layout.anchor("kuudra.rise", 0.5, 60, -50.5, 0f);
        layout.data("kuudra.ballista", BX + "," + BY + "," + BZ);
        layout.data("walk.ignore", "kuudra.,lair.,stomach.,chest.");

        layout.zone("kuudra_hollow", "Leviathan's Hollow", 'c', Box.of(-R_DOME, 50, -R_DOME, R_DOME, 115, R_DOME), null);
        layout.zone("kuudra_platform", "Ballista Platform", 'e', Box.of(PCX - PR - 2, PY - 2, PCZ - PR - 2, PCX + PR + 2, PY + 12, PCZ + PR + 2), null);
        layout.area("arena", Box.of(-R_WALL + 2, FLOOR + 1, -R_WALL + 2, R_WALL - 2, FLOOR + 8, R_WALL - 2));
        layout.area("platform", Box.of(PCX - PR, PY + 1, PCZ - PR, PCX + PR, PY + 6, PCZ + PR));
        layout.area("ballista", Box.of(BX - 4, BY + 1, BZ - 5, BX + 4, BY + 6, BZ + 3));
        layout.area("lake", Box.of(-R_FLOOR, 56, -R_FLOOR, R_FLOOR, FLOOR + 1, -10));
        layout.data("reservations", res.summary());
    }

    // ---- geometry helpers (pure; the module never needs them, the layout carries the answers) ----

    /** The lake's southern edge at {@code x}: north of it is lava. Kept back from the platform. */
    static int shoreZ(Noise n, int x) {
        if (Math.abs(x) <= 26) return -14;
        double blend = Math.min(1, (Math.abs(x) - 26) / 12.0);
        return -14 + (int) Math.round(blend * (9 * n.fbm2(x / 19.0, 7.3, 3) + 4));
    }

    static boolean isLake(Noise n, int x, int z) {
        return z < shoreZ(n, x) && Math.sqrt(x * x + z * z) <= R_WALL;
    }

    private static boolean kept(List<int[]> keep, int x, int z, int margin) {
        for (int[] k : keep) {
            int r = k[2] + margin;
            int dx = x - k[0], dz = z - k[1];
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    private static int ground(VoxelCanvas c, int x, int z) {
        int y = c.topY(x, z, FLOOR - 1, FLOOR + 2);
        return y == Integer.MIN_VALUE ? FLOOR : y;
    }

    /** Twelve crate spots over the cavern floor south of the lake, 34-72 blocks from the platform. */
    static List<int[]> supplySpots(Noise n) {
        List<int[]> out = new ArrayList<>();
        int[] radii = {36, 54, 72};
        for (int k = 0; k < 12; k++) {
            double a = Math.toRadians(8 + k * (164 / 11.0));
            int r = radii[k % 3];
            int x, z;
            do {
                x = (int) Math.round(PCX + Math.cos(a) * r);
                z = (int) Math.round(PCZ + Math.sin(a) * r);
                r -= 4;
            } while ((Math.hypot(x, z) > R_WALL - 6 || isLake(n, x, z)) && r > 20);
            out.add(new int[]{x, z});
        }
        return out;
    }

    /** Six fuel spots along the shore, three each side of the platform. */
    static List<int[]> fuelSpots(Noise n) {
        List<int[]> out = new ArrayList<>();
        for (int x : new int[]{-64, -44, -28, 28, 44, 64}) {
            int z = shoreZ(n, x) + 3;
            while (isLake(n, x, z)) z++;
            if (Math.hypot(x, z) > R_WALL - 6) z = (int) -Math.sqrt(Math.max(0, (R_WALL - 7.0) * (R_WALL - 7.0) - x * x));
            while (isLake(n, x, z)) z++;
            out.add(new int[]{x, z});
        }
        return out;
    }

    /** Twelve places the brood crawls out: the shore either side, and the far south rim. */
    static List<int[]> mobSpots(Noise n) {
        List<int[]> out = new ArrayList<>();
        for (int x : new int[]{-76, -56, -36, 36, 56, 76}) {
            int z = shoreZ(n, x) + 2;
            while (isLake(n, x, z)) z++;
            double lim = R_WALL - 6;
            if (Math.hypot(x, z) > lim) { int sx = (int) Math.signum(x); x = sx * (int) Math.floor(Math.sqrt(Math.max(0, lim * lim - z * z))); }
            out.add(new int[]{x, z});
        }
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(30 + k * 24);
            out.add(new int[]{(int) Math.round(Math.cos(a) * 80), (int) Math.round(Math.sin(a) * 80)});
        }
        return out;
    }

    /** The first solid block above the floor in a column (the cave ceiling), 108 if none. */
    private static int ceilingAt(VoxelCanvas c, int x, int z) {
        for (int y = FLOOR + 2; y <= 115; y++) if (!c.isAir(x, y, z)) return y;
        return 108;
    }
}
