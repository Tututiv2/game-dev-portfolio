package com.skyclone.mod.farming.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * Farm pieces shared by the Barn and the Mushroom Desert. Pure (no Bukkit): fields, patches, cane rows, cocoa
 * walls, cactus and mushroom beds, wart beds, pens, the windmill, hay piles. Every piece takes the GROUND level
 * {@code y} of a flat plot and writes the soil at y and the plants at y+1 (a person walks at y+1).
 */
public final class FarmKit {

    private FarmKit() {}

    static final String FARMLAND = "farmland[moisture=7]";

    /** Clear plants/terrain above a plot so nothing pokes through the crops. */
    static void clearAbove(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, int height) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int h = 1; h <= height; h++) c.set(x, y + h, z, "air");
    }

    /** A rim of path blocks around a rectangle (at y), so water channels can never leak. */
    static void rim(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, Fill path) {
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            c.set(x, y, z0 - 1, path.at(x, y, z0 - 1));
            c.set(x, y, z1 + 1, path.at(x, y, z1 + 1));
            Builders.footing(c, x, y, z0 - 1, Fill.of("dirt"), 8);
            Builders.footing(c, x, y, z1 + 1, Fill.of("dirt"), 8);
        }
        for (int z = z0 - 1; z <= z1 + 1; z++) {
            c.set(x0 - 1, y, z, path.at(x0 - 1, y, z));
            c.set(x1 + 1, y, z, path.at(x1 + 1, y, z));
            Builders.footing(c, x0 - 1, y, z, Fill.of("dirt"), 8);
            Builders.footing(c, x1 + 1, y, z, Fill.of("dirt"), 8);
        }
        clearAbove(c, x0 - 1, z0 - 1, x1 + 1, z0 - 1, y, 4);
        clearAbove(c, x0 - 1, z1 + 1, x1 + 1, z1 + 1, y, 4);
        clearAbove(c, x0 - 1, z0 - 1, x0 - 1, z1 + 1, y, 4);
        clearAbove(c, x1 + 1, z0 - 1, x1 + 1, z1 + 1, y, 4);
    }

    /**
     * An ageable crop field (wheat/carrots/potatoes): farmland rows with a water channel every
     * {@code waterEvery} columns (along x), mature crops on top. Returns the field box (feet space).
     */
    static Box cropField(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, String crop, int waterEvery) {
        clearAbove(c, x0, z0, x1, z1, y, 4);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean water = waterEvery > 0 && Math.floorMod(x - x0, waterEvery) == waterEvery / 2 && x != x0 && x != x1;
                Builders.footing(c, x, y, z, Fill.of("dirt"), 8);
                if (water) { c.set(x, y, z, "water"); c.set(x, y - 1, z, "dirt"); continue; }
                c.set(x, y, z, FARMLAND);
                c.set(x, y + 1, z, crop);
            }
        rim(c, x0, z0, x1, z1, y, Palette.of("dirt_path:5,coarse_dirt:1"));
        return Box.of(x0, y + 1, z0, x1, y + 2, z1);
    }

    /**
     * Pumpkin or melon patch: along x, period 3 = [stem on farmland][fruit on dirt][walk lane].
     * {@code fruit} = "pumpkin" or "melon".
     */
    static Box fruitPatch(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, String fruit) {
        clearAbove(c, x0, z0, x1, z1, y, 4);
        String stem = "attached_" + fruit + "_stem[facing=east]";
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                int k = Math.floorMod(x - x0, 3);
                Builders.footing(c, x, y, z, Fill.of("dirt"), 8);
                if (k == 0 && x + 1 <= x1) { c.set(x, y, z, FARMLAND); c.set(x, y + 1, z, stem); }
                else if (k == 1) { c.set(x, y, z, Hash.chance(0x5eedL, x, z, 0.3) ? "coarse_dirt" : "dirt"); c.set(x, y + 1, z, fruit); }
                else c.set(x, y, z, "dirt_path");
            }
        rim(c, x0, z0, x1, z1, y, Palette.of("dirt_path:5,coarse_dirt:1"));
        return Box.of(x0, y + 1, z0, x1, y + 2, z1);
    }

    /** Sugar cane rows: along x, period 4 = [sand+cane][water][sand+cane][path]; cane 3 high. */
    static Box caneRows(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, String soil) {
        clearAbove(c, x0, z0, x1, z1, y, 5);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                int k = Math.floorMod(x - x0, 4);
                Builders.footing(c, x, y, z, Fill.of("dirt"), 8);
                if (k == 1 && x != x1) { c.set(x, y, z, "water"); c.set(x, y - 1, z, "dirt"); }
                else if (k == 3) c.set(x, y, z, "dirt_path");
                else {
                    c.set(x, y, z, soil);
                    boolean nextToWater = Math.floorMod(x + 1 - x0, 4) == 1 && x + 1 <= x1 || Math.floorMod(x - 1 - x0, 4) == 1;
                    if (nextToWater) for (int h = 1; h <= 3; h++) c.set(x, y + h, z, "sugar_cane");
                }
            }
        rim(c, x0, z0, x1, z1, y, Palette.of("dirt_path:5,gravel:1"));
        return Box.of(x0, y + 1, z0, x1, y + 2, z1);
    }

    /**
     * A wall of jungle logs along x at {@code z}, {@code height} tall, with ripe cocoa on both faces.
     * The ground on both sides is paved so it can be walked.
     */
    static void cocoaWall(VoxelCanvas c, int x0, int x1, int z, int y, int height) {
        for (int x = x0; x <= x1; x++) {
            for (int dz = -2; dz <= 2; dz++) {
                c.set(x, y, z + dz, dz == 0 ? "jungle_wood" : "dirt_path");
                Builders.footing(c, x, y, z + dz, Fill.of("dirt"), 8);
                for (int h = 1; h <= height + 1; h++) c.set(x, y + h, z + dz, "air");
            }
            boolean post = Math.floorMod(x - x0, 6) == 0;
            for (int h = 1; h <= height; h++) {
                c.set(x, y + h, z, post ? "stripped_jungle_log[axis=y]" : "jungle_log[axis=y]");
                if (!post) {
                    c.set(x, y + h, z - 1, "cocoa[age=2,facing=south]");
                    c.set(x, y + h, z + 1, "cocoa[age=2,facing=north]");
                }
            }
            c.set(x, y + height + 1, z, post ? Blocks.lantern(false) : Blocks.slab("jungle", "bottom"));
        }
    }

    /** Cactus on sand in a checkerboard (cactus never touches a block sideways), 3 high. */
    static Box cactusField(VoxelCanvas c, int x0, int z0, int x1, int z1, int y) {
        clearAbove(c, x0, z0, x1, z1, y, 5);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                Builders.footing(c, x, y, z, Fill.of("sandstone"), 8);
                c.set(x, y, z, Hash.chance(0xcac7L, x, z, 0.2) ? "red_sand" : "sand");
                if (Math.floorMod(x + z, 2) == 0 && Math.floorMod(z - z0, 4) != 3) {
                    for (int h = 1; h <= 3; h++) c.set(x, y + h, z, "cactus");
                }
            }
        rim(c, x0, z0, x1, z1, y, Palette.of("smooth_sandstone:3,sandstone:2,red_sand:1"));
        return Box.of(x0, y + 1, z0, x1, y + 2, z1);
    }

    /** Mushrooms on mycelium/podzol, every other block (paths between). */
    static Box mushroomBed(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, long seed) {
        clearAbove(c, x0, z0, x1, z1, y, 3);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                Builders.footing(c, x, y, z, Fill.of("dirt"), 8);
                boolean bed = Math.floorMod(z - z0, 3) != 2;
                c.set(x, y, z, bed ? (Hash.chance(seed, x, z, 0.5) ? "mycelium" : "podzol") : "coarse_dirt");
                if (bed && Math.floorMod(x - x0, 2) == 0)
                    c.set(x, y + 1, z, Hash.chance(seed ^ 77, x, z, 0.5) ? "red_mushroom" : "brown_mushroom");
            }
        return Box.of(x0, y + 1, z0, x1, y + 2, z1);
    }

    /** Nether wart on soul sand, rows with soul-soil lanes. */
    static Box wartBed(VoxelCanvas c, int x0, int z0, int x1, int z1, int y) {
        clearAbove(c, x0, z0, x1, z1, y, 3);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                Builders.footing(c, x, y, z, Fill.of("soul_soil"), 8);
                boolean lane = Math.floorMod(x - x0, 4) == 3;
                c.set(x, y, z, lane ? "soul_soil" : "soul_sand");
                if (!lane) c.set(x, y + 1, z, "nether_wart[age=3]");
            }
        rim(c, x0, z0, x1, z1, y, Palette.of("nether_bricks:3,red_nether_bricks:1"));
        return Box.of(x0, y + 1, z0, x1, y + 2, z1);
    }

    /** A meadow of the three 2025 flowers in rows (sunflower and rose bush are two blocks tall). */
    static Box flowerMeadow(VoxelCanvas c, int x0, int z0, int x1, int z1, int y) {
        clearAbove(c, x0, z0, x1, z1, y, 3);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                Builders.footing(c, x, y, z, Fill.of("dirt"), 8);
                int row = Math.floorMod(z - z0, 4);
                c.set(x, y, z, row == 3 ? "dirt_path" : "grass_block");
                if (row == 3 || Math.floorMod(x - x0, 2) == 1) continue;
                switch (row) {
                    case 0 -> { c.set(x, y + 1, z, "sunflower[half=lower]"); c.set(x, y + 2, z, "sunflower[half=upper]"); }
                    case 1 -> c.set(x, y + 1, z, "blue_orchid");
                    default -> { c.set(x, y + 1, z, "rose_bush[half=lower]"); c.set(x, y + 2, z, "rose_bush[half=upper]"); }
                }
            }
        rim(c, x0, z0, x1, z1, y, Palette.of("dirt_path:5,coarse_dirt:1"));
        return Box.of(x0, y + 1, z0, x1, y + 2, z1);
    }

    /**
     * A fenced pen on flat ground: fence ring with a gate in the {@code gateSide} wall, a water trough, hay.
     * Returns the inner box (feet space) for the spawner. {@code ground} = interior surface block.
     */
    static Box pen(VoxelCanvas c, int x0, int z0, int x1, int z1, int y, String fence, String gateWood, String gateSide, String ground, long seed) {
        clearAbove(c, x0, z0, x1, z1, y, 4);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                Builders.footing(c, x, y, z, Fill.of("dirt"), 8);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                c.set(x, y, z, edge ? "coarse_dirt" : ground.equals("grass_block") && Hash.chance(seed, x, z, 0.15) ? "coarse_dirt" : ground);
                if (edge) c.set(x, y + 1, z, fence);
                else if (ground.equals("grass_block") && Hash.chance(seed ^ 9, x, z, 0.12)) c.set(x, y + 1, z, "short_grass");
            }
        int gx, gz;
        switch (gateSide) {
            case "north" -> { gx = (x0 + x1) / 2; gz = z0; }
            case "south" -> { gx = (x0 + x1) / 2; gz = z1; }
            case "west" -> { gx = x0; gz = (z0 + z1) / 2; }
            default -> { gx = x1; gz = (z0 + z1) / 2; }
        }
        String gateFacing = gateSide.equals("north") || gateSide.equals("south") ? "south" : "east";
        c.set(gx, y + 1, gz, Blocks.fenceGate(gateWood, gateFacing, false));
        // trough + hay in the corner away from the gate
        int tx = gateSide.equals("west") ? x1 - 2 : x0 + 2, tz = gateSide.equals("north") ? z1 - 2 : z0 + 2;
        c.set(tx, y + 1, tz, "water_cauldron[level=3]");
        c.set(tx + (tx < (x0 + x1) / 2 ? 1 : -1), y + 1, tz, "hay_block[axis=y]");
        // fence corner posts lit
        c.set(x0, y + 2, z0, Blocks.lantern(false));
        c.set(x1, y + 2, z1, Blocks.lantern(false));
        return Box.of(x0 + 1, y + 1, z0 + 1, x1 - 1, y + 2, z1 - 1);
    }

    /** Outside the gate of a pen built with {@link #pen}: the ground block a path should end at. */
    static int[] gateOutside(int x0, int z0, int x1, int z1, String gateSide) {
        return switch (gateSide) {
            case "north" -> new int[]{(x0 + x1) / 2, z0 - 1};
            case "south" -> new int[]{(x0 + x1) / 2, z1 + 1};
            case "west" -> new int[]{x0 - 1, (z0 + z1) / 2};
            default -> new int[]{x1 + 1, (z0 + z1) / 2};
        };
    }

    /**
     * A windmill: a round stone-and-timber tower with a cone roof, a door and floors, plus four diagonal sails
     * on its {@code face} side. Returns the tower (door step, inside, floors).
     */
    static Builders.Tower windmill(VoxelCanvas c, int cx, int y, int cz, String door, String face) {
        Builders.Tower t = new Builders.Tower(cx, y, cz);
        t.radius = 4; t.height = 18; t.floorHeight = 6; t.top = "cone";
        t.wall = Palette.of("white_terracotta:4,bricks:1,stone_bricks:2");
        t.roofFill = Palette.of("dark_oak_planks:3,spruce_planks:1");
        t.floor = Fill.of("spruce_planks");
        t.door = door; t.doorWood = "spruce";
        t.build(c);
        // sails
        int hubY = y + 15;
        int fx = Blocks.dx(face), fz = Blocks.dz(face);
        int hx = cx + fx * (t.radius + 1), hz = cz + fz * (t.radius + 1);
        c.set(hx, hubY, hz, "stripped_spruce_log[axis=" + (fx != 0 ? 'x' : 'z') + "]");
        c.set(hx + fx, hubY, hz + fz, "spruce_planks");
        int ox = hx + fx, oz = hz + fz;                 // plane of the sails
        int ux = fz != 0 ? 1 : 0, uz = fx != 0 ? 1 : 0;    // horizontal axis in the sail plane
        int len = 11;
        int[][] dirs = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int[] d : dirs) {
            for (int i = 1; i <= len; i++) {
                int px = ox + ux * d[0] * i, pz = oz + uz * d[0] * i, py = hubY + d[1] * i;
                c.set(px, py, pz, "spruce_planks");
                // a step block so each spine is face-connected (diagonals alone only touch at edges)
                c.set(px - ux * d[0], py, pz - uz * d[0], "spruce_planks");
                if (i >= 3) {
                    // a sail cloth beside each spine block, on the same side for every arm (looks like it turns)
                    int sx = px + ux * -d[1], sz = pz + uz * -d[1];
                    c.set(sx, py, sz, "white_wool");
                    c.set(sx + ux * -d[1], py, sz + uz * -d[1], i % 3 == 0 ? "spruce_planks" : "white_wool");
                }
            }
        }
        return t;
    }

    /** A heap of hay bales with a stair to climb it; returns the top block you can stand on {x, y, z}. */
    static int[] hayPile(VoxelCanvas c, int x, int y, int z, String stairFacing) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                c.set(x + dx, y + 1, z + dz, "hay_block[axis=y]");
                Builders.footing(c, x + dx, y + 1, z + dz, Fill.of("dirt"), 6);
            }
        c.set(x, y + 2, z, "hay_block[axis=x]");
        c.set(x + Blocks.dx(stairFacing) * 0, y + 2, z, "hay_block[axis=x]");
        int sx = x - Blocks.dx(stairFacing) * 2, sz = z - Blocks.dz(stairFacing) * 2;
        c.set(sx, y + 1, sz, Blocks.stairs("spruce_stairs", stairFacing));
        Builders.footing(c, sx, y + 1, sz, Fill.of("dirt"), 6);
        return new int[]{x, y + 2, z};
    }

    /** Lamp posts every {@code every} blocks along a polyline, offset to its {@code side}. */
    static void lampsAlong(VoxelCanvas c, int[][] pts, int every, int sideOffset, String style) {
        double acc = every / 2.0;
        for (int i = 0; i + 1 < pts.length; i++) {
            int ax = pts[i][0], az = pts[i][1], bx = pts[i + 1][0], bz = pts[i + 1][1];
            double len = Math.hypot(bx - ax, bz - az);
            double ux = (bx - ax) / Math.max(1e-6, len), uz = (bz - az) / Math.max(1e-6, len);
            for (double t = 0; t <= len; t += 1) {
                acc += 1;
                if (acc < every) continue;
                acc = 0;
                int x = (int) Math.round(ax + ux * t - uz * sideOffset), z = (int) Math.round(az + uz * t + ux * sideOffset);
                int g = Builders.ground(c, x, z, 160, 20);
                if (g == Integer.MIN_VALUE) continue;
                if (!c.isAir(x, g + 1, z) && !Builders.natural(c.get(x, g + 1, z))) continue;
                String facing = Math.abs(ux) > Math.abs(uz) ? (sideOffset > 0 ? "north" : "south") : (sideOffset > 0 ? "west" : "east");
                Builders.lampPost(c, x, g, z, style, facing);
            }
        }
    }

    /** Framed portal opening (3 wide, 4 tall) facing along z or x. Returns the portal area. */
    static Box portal(VoxelCanvas c, int cx, int y, int cz, boolean alongX, Fill frame, String fillBlock) {
        int ax = alongX ? 1 : 0, az = alongX ? 0 : 1;
        for (int i = -3; i <= 3; i++)
            for (int j = -2; j <= 2; j++) {
                int x = cx + ax * i + (alongX ? 0 : j), z = cz + az * i + (alongX ? j : 0);
                c.set(x, y, z, Palette.of("polished_andesite:3,stone_bricks:2").at(x, y, z));
                Builders.footing(c, x, y, z, Fill.of("stone"), 10);
                for (int h = 1; h <= 6; h++) c.set(x, y + h, z, "air");
            }
        for (int h = 1; h <= 5; h++) {
            c.set(cx - ax * 2, y + h, cz - az * 2, frame.at(cx - ax * 2, y + h, cz - az * 2));
            c.set(cx + ax * 2, y + h, cz + az * 2, frame.at(cx + ax * 2, y + h, cz + az * 2));
        }
        for (int i = -2; i <= 2; i++) c.set(cx + ax * i, y + 5, cz + az * i, frame.at(cx + ax * i, y + 5, cz + az * i));
        for (int i = -1; i <= 1; i++)
            for (int h = 1; h <= 4; h++) c.set(cx + ax * i, y + h, cz + az * i, fillBlock + "[axis=" + (alongX ? 'x' : 'z') + "]");
        return Box.of(cx - ax, y + 1, cz - az, cx + ax, y + 4, cz + az);
    }

    /** Collect the fairy soul spots that passed, as standAt anchors named fairy_soul.1..N. */
    static void souls(VoxelCanvas c, Layout layout, List<int[]> spots) {
        int n = 1;
        for (int[] s : spots) {
            String b = com.skyclone.gen.BlockState.name(c.get(s[0], s[1], s[2]));
            if (b.equals("dirt_path") || b.equals("farmland") || b.equals("air")) c.set(s[0], s[1], s[2], "coarse_dirt");
            for (int k = 1; k <= 2; k++) if (!c.isAir(s[0], s[1] + k, s[2]) && Builders.natural(c.get(s[0], s[1] + k, s[2]))) c.set(s[0], s[1] + k, s[2], "air");
            layout.standAt("fairy_soul." + n++, s[0], s[1], s[2], 0f);
        }
    }

    static List<int[]> list() { return new ArrayList<>(); }

    /** A scarecrow on ground block (x, y, z) facing {@code facing}. */
    static void scarecrow(VoxelCanvas c, int x, int y, int z, String facing) {
        c.set(x, y + 1, z, "spruce_fence");
        c.set(x, y + 2, z, "hay_block[axis=y]");
        c.set(x, y + 3, z, "carved_pumpkin[facing=" + facing + "]");
        String side = Blocks.cw(facing);
        c.set(x + Blocks.dx(side), y + 2, z + Blocks.dz(side), "spruce_fence");
        c.set(x - Blocks.dx(side), y + 2, z - Blocks.dz(side), "spruce_fence");
    }
}
