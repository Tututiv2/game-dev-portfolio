package org.aincrad.plugin.district;

import org.aincrad.plugin.generation.BlockEdit;
import org.aincrad.plugin.terrain.TerrainProfile;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds one complete house on one parcel: graded pad, footing course, framed walls, a real door
 * on the street frontage, windows, a genuinely climbable staircase, an upper floor with a bed,
 * a closed pitched roof with gable ends, and optionally a cellar.
 *
 * <p>The interior is carved as air <em>after</em> the shell is written, so "every public-facing
 * door opens into a real interior" is structural rather than aspirational - there is no code path
 * that produces a solid-filled building with a door on it.</p>
 */
public final class BuildingBuilder {

    public record Stats(boolean twoStorey, boolean cellar) {
    }

    /**
     * Everything about a building that is derived rather than stored. Verification reads its
     * coordinates from here rather than recomputing them, so a checker can never drift from the
     * builder and silently start probing the wrong blocks.
     */
    public record Plan(int baseY, int ceil1, int wallTop, boolean twoStorey, boolean cellar,
                        int doorX, int doorZ, int insideX, int insideZ) {
    }

    private BuildingBuilder() {
    }

    public static Plan plan(Parcel p, TerrainProfile terrain, long worldSeed) {
        Random rng = new Random(worldSeed ^ p.id().hashCode());
        int baseY = terrain.surfaceY(p.centreX(), p.centreZ());
        boolean twoStorey = rng.nextInt(100) < 72;              // >= 45% required
        boolean cellar = rng.nextInt(100) < 20;                 // >= 12% required
        int groundClear = 4 + rng.nextInt(2);                   // 4-5
        int upperClear = 3 + rng.nextInt(2);                    // 3-4
        int ceil1 = baseY + groundClear + 1;
        int wallTop = twoStorey ? ceil1 + upperClear + 1 : ceil1;

        int dx, dz, ix, iz;
        switch (p.frontage()) {
            case NORTH -> {
                dx = p.centreX();
                dz = p.minZ();
                ix = dx;
                iz = dz + 1;
            }
            case SOUTH -> {
                dx = p.centreX();
                dz = p.maxZ();
                ix = dx;
                iz = dz - 1;
            }
            case WEST -> {
                dx = p.minX();
                dz = p.centreZ();
                ix = dx + 1;
                iz = dz;
            }
            default -> {
                dx = p.maxX();
                dz = p.centreZ();
                ix = dx - 1;
                iz = dz;
            }
        }
        return new Plan(baseY, ceil1, wallTop, twoStorey, cellar, dx, dz, ix, iz);
    }

    public static Stats build(List<BlockEdit> out, Parcel p, TerrainProfile terrain, long worldSeed) {
        Plan plan = plan(p, terrain, worldSeed);
        // Separate stream for decoration, so changing furniture can never shift the structural
        // draws that plan() depends on.
        Random rng = new Random((worldSeed ^ p.id().hashCode()) * 31 + 7);
        BuildingPalette pal = BuildingPalette.of(p.variant(), p.role());

        int baseY = plan.baseY();
        boolean twoStorey = plan.twoStorey();
        boolean cellar = plan.cellar();
        int ceil1 = plan.ceil1();
        int wallTop = plan.wallTop();
        int groundClear = ceil1 - baseY - 1;
        int upperClear = twoStorey ? wallTop - ceil1 - 1 : 0;
        int minX = p.minX(), maxX = p.maxX(), minZ = p.minZ(), maxZ = p.maxZ();

        // --- Pad + foundation: cut anything above the pad, fill down to solid ground ---
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int t = terrain.surfaceY(x, z);
                for (int y = baseY + 1; y <= Math.max(t, baseY) + 2; y++) {
                    out.add(new BlockEdit(x, y, z, Material.AIR));
                }
                for (int y = Math.min(t, baseY) - 1; y < baseY; y++) {
                    out.add(new BlockEdit(x, y, z, pal.footing()));
                }
                out.add(new BlockEdit(x, baseY, z, pal.floorMaterial()));
            }
        }

        // --- Cellar ---
        if (cellar) {
            int cFloor = baseY - 5;
            for (int x = minX + 1; x <= maxX - 1; x++) {
                for (int z = minZ + 1; z <= maxZ - 1; z++) {
                    out.add(new BlockEdit(x, cFloor, z, Material.COBBLESTONE));
                    for (int y = cFloor + 1; y < baseY; y++) {
                        boolean edge = x == minX + 1 || x == maxX - 1 || z == minZ + 1 || z == maxZ - 1;
                        out.add(new BlockEdit(x, y, z, edge ? Material.COBBLESTONE : Material.AIR));
                    }
                }
            }
            // Cellar access: a hole in the ground floor with a ladder down.
            int lx = minX + 2, lz = minZ + 2;
            out.add(new BlockEdit(lx, baseY, lz, Material.AIR));
            for (int y = cFloor + 1; y <= baseY; y++) {
                out.add(new BlockEdit(lx, y, lz, Material.LADDER, "minecraft:ladder[facing=east]"));
            }
            out.add(new BlockEdit(lx - 1, cFloor + 1, lz, Material.COBBLESTONE));
            out.add(new BlockEdit(minX + 2, cFloor + 1, maxZ - 2, Material.LANTERN));
        }

        // --- Shell: walls with corner framing ---
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean perimeter = x == minX || x == maxX || z == minZ || z == maxZ;
                if (!perimeter) {
                    continue;
                }
                boolean corner = (x == minX || x == maxX) && (z == minZ || z == maxZ);
                for (int y = baseY + 1; y <= wallTop; y++) {
                    out.add(new BlockEdit(x, y, z, corner ? pal.frame() : pal.wall()));
                }
            }
        }

        // --- Hollow the interior, and lay the intermediate floor ---
        for (int x = minX + 1; x <= maxX - 1; x++) {
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                for (int y = baseY + 1; y <= wallTop; y++) {
                    out.add(new BlockEdit(x, y, z, Material.AIR));
                }
                if (twoStorey) {
                    out.add(new BlockEdit(x, ceil1, z, pal.floorMaterial()));
                }
            }
        }

        // --- Staircase against the back wall, plus the opening it arrives through ---
        if (twoStorey) {
            addStaircase(out, p, pal, baseY, ceil1, groundClear);
        }

        // --- Door on the street frontage ---
        addDoor(out, p, baseY);

        // --- Windows on the frontage and the two flanking walls ---
        addWindows(out, p, baseY, ceil1, twoStorey, upperClear);

        // --- Lighting and minimal furnishing ---
        out.add(new BlockEdit(p.centreX(), ceil1 - 1, p.centreZ(), Material.LANTERN));
        if (twoStorey) {
            out.add(new BlockEdit(p.centreX(), wallTop - 1, p.centreZ(), Material.LANTERN));
            addBed(out, p, ceil1 + 1);
        }
        addRoleFurniture(out, p, baseY, rng);

        // --- Roof ---
        addRoof(out, p, pal, wallTop, rng);

        return new Stats(twoStorey, cellar);
    }

    private static void addStaircase(List<BlockEdit> out, Parcel p, BuildingPalette pal,
                                      int baseY, int ceil1, int groundClear) {
        int steps = ceil1 - baseY;                 // climb from floor to upper floor
        int x = p.minX() + 1;
        int z = p.minZ() + 1;
        String facing = "east";
        for (int i = 0; i < steps; i++) {
            int sx = x + i;
            if (sx >= p.maxX()) {
                break;
            }
            out.add(new BlockEdit(sx, baseY + 1 + i, z, Material.OAK_STAIRS,
                    "minecraft:oak_stairs[facing=" + facing + ",half=bottom]"));
            // Clear headroom above every tread so the run is actually climbable.
            for (int h = 1; h <= 3; h++) {
                out.add(new BlockEdit(sx, baseY + 1 + i + h, z, Material.AIR));
            }
            // Opening through the upper floor above the run.
            out.add(new BlockEdit(sx, ceil1, z, Material.AIR));
        }
        // Landing.
        int landX = Math.min(x + steps, p.maxX() - 1);
        out.add(new BlockEdit(landX, ceil1, z, Material.AIR));
    }

    private static void addDoor(List<BlockEdit> out, Parcel p, int baseY) {
        int dx, dz;
        String facing;
        switch (p.frontage()) {
            case NORTH -> {
                dx = p.centreX();
                dz = p.minZ();
                facing = "north";
            }
            case SOUTH -> {
                dx = p.centreX();
                dz = p.maxZ();
                facing = "south";
            }
            case WEST -> {
                dx = p.minX();
                dz = p.centreZ();
                facing = "west";
            }
            default -> {
                dx = p.maxX();
                dz = p.centreZ();
                facing = "east";
            }
        }
        out.add(new BlockEdit(dx, baseY + 1, dz, Material.OAK_DOOR,
                "minecraft:oak_door[facing=" + facing + ",half=lower,hinge=left,open=false,powered=false]"));
        out.add(new BlockEdit(dx, baseY + 2, dz, Material.OAK_DOOR,
                "minecraft:oak_door[facing=" + facing + ",half=upper,hinge=left,open=false,powered=false]"));
        // Guarantee the square inside the door is open, so a door can never front a wall.
        int ix = dx, iz = dz;
        switch (p.frontage()) {
            case NORTH -> iz = dz + 1;
            case SOUTH -> iz = dz - 1;
            case WEST -> ix = dx + 1;
            default -> ix = dx - 1;
        }
        out.add(new BlockEdit(ix, baseY + 1, iz, Material.AIR));
        out.add(new BlockEdit(ix, baseY + 2, iz, Material.AIR));
        out.add(new BlockEdit(dx, baseY, dz, Material.STONE_BRICK_SLAB));   // threshold
    }

    private static void addWindows(List<BlockEdit> out, Parcel p, int baseY, int ceil1,
                                    boolean twoStorey, int upperClear) {
        int y1 = baseY + 3;
        int y2 = ceil1 + 2;
        for (int x = p.minX() + 2; x <= p.maxX() - 2; x += 3) {
            out.add(new BlockEdit(x, y1, p.minZ(), Material.GLASS_PANE));
            out.add(new BlockEdit(x, y1, p.maxZ(), Material.GLASS_PANE));
            if (twoStorey) {
                out.add(new BlockEdit(x, y2, p.minZ(), Material.GLASS_PANE));
                out.add(new BlockEdit(x, y2, p.maxZ(), Material.GLASS_PANE));
            }
        }
        for (int z = p.minZ() + 2; z <= p.maxZ() - 2; z += 3) {
            out.add(new BlockEdit(p.minX(), y1, z, Material.GLASS_PANE));
            out.add(new BlockEdit(p.maxX(), y1, z, Material.GLASS_PANE));
            if (twoStorey) {
                out.add(new BlockEdit(p.minX(), y2, z, Material.GLASS_PANE));
                out.add(new BlockEdit(p.maxX(), y2, z, Material.GLASS_PANE));
            }
        }
    }

    private static void addBed(List<BlockEdit> out, Parcel p, int y) {
        int bx = p.maxX() - 2;
        int bz = p.maxZ() - 2;
        out.add(new BlockEdit(bx, y, bz, Material.RED_BED,
                "minecraft:red_bed[facing=north,part=foot,occupied=false]"));
        out.add(new BlockEdit(bx, y, bz - 1, Material.RED_BED,
                "minecraft:red_bed[facing=north,part=head,occupied=false]"));
    }

    private static void addRoleFurniture(List<BlockEdit> out, Parcel p, int baseY, Random rng) {
        int cx = p.centreX();
        int cz = p.centreZ();
        switch (p.role()) {
            case SHOP -> {
                out.add(new BlockEdit(cx - 1, baseY + 1, cz + 1, Material.BARREL));
                out.add(new BlockEdit(cx, baseY + 1, cz + 1, Material.SMOOTH_STONE_SLAB));
                out.add(new BlockEdit(cx + 1, baseY + 1, cz + 1, Material.SMOOTH_STONE_SLAB));
            }
            case WORKSHOP -> {
                out.add(new BlockEdit(cx - 1, baseY + 1, cz, Material.CRAFTING_TABLE));
                out.add(new BlockEdit(cx + 1, baseY + 1, cz, Material.SMITHING_TABLE));
                out.add(new BlockEdit(cx, baseY + 1, cz - 1, Material.BARREL));
            }
            case INN -> {
                out.add(new BlockEdit(cx - 1, baseY + 1, cz, Material.BARREL));
                out.add(new BlockEdit(cx + 1, baseY + 1, cz, Material.CAULDRON));
                out.add(new BlockEdit(cx, baseY + 1, cz + 2, Material.OAK_PRESSURE_PLATE));
            }
            case CIVIC -> out.add(new BlockEdit(cx, baseY + 1, cz, Material.LECTERN));
            default -> {
                out.add(new BlockEdit(p.minX() + 2, baseY + 1, p.maxZ() - 2, Material.BARREL));
                if (rng.nextBoolean()) {
                    out.add(new BlockEdit(p.minX() + 3, baseY + 1, p.maxZ() - 2, Material.CRAFTING_TABLE));
                }
            }
        }
    }

    /** Closed pitched roof with gable ends, ridge along the longer axis, 1-block eaves overhang. */
    private static void addRoof(List<BlockEdit> out, Parcel p, BuildingPalette pal,
                                 int wallTop, Random rng) {
        int minX = p.minX() - 1, maxX = p.maxX() + 1;
        int minZ = p.minZ() - 1, maxZ = p.maxZ() + 1;
        boolean ridgeAlongZ = p.depth() >= p.width();

        if (ridgeAlongZ) {
            int cx = (minX + maxX) / 2;
            int rise = Math.max(3, Math.min(6, p.width() / 2));
            for (int x = minX; x <= maxX; x++) {
                int dist = Math.abs(x - cx);
                int y = wallTop + 1 + Math.max(0, rise - dist);
                String facing = x < cx ? "west" : "east";
                for (int z = minZ; z <= maxZ; z++) {
                    if (dist == 0) {
                        out.add(new BlockEdit(x, y, z, pal.roofSolid()));
                    } else if (dist <= rise) {
                        out.add(new BlockEdit(x, y, z, pal.roofStairs(),
                                "minecraft:" + key(pal.roofStairs()) + "[facing=" + facing
                                        + ",half=bottom,shape=straight]"));
                    } else {
                        out.add(new BlockEdit(x, wallTop + 1, z, pal.roofSolid()));
                    }
                    // Close the gable triangle at both ends of the ridge.
                    if (z == minZ || z == maxZ) {
                        for (int gy = wallTop + 1; gy < y; gy++) {
                            out.add(new BlockEdit(x, gy, z, pal.wall()));
                        }
                    }
                    // Clear anything above the roof line.
                    for (int cy = y + 1; cy <= wallTop + rise + 2; cy++) {
                        out.add(new BlockEdit(x, cy, z, Material.AIR));
                    }
                }
            }
        } else {
            int cz = (minZ + maxZ) / 2;
            int rise = Math.max(3, Math.min(6, p.depth() / 2));
            for (int z = minZ; z <= maxZ; z++) {
                int dist = Math.abs(z - cz);
                int y = wallTop + 1 + Math.max(0, rise - dist);
                String facing = z < cz ? "north" : "south";
                for (int x = minX; x <= maxX; x++) {
                    if (dist == 0) {
                        out.add(new BlockEdit(x, y, z, pal.roofSolid()));
                    } else if (dist <= rise) {
                        out.add(new BlockEdit(x, y, z, pal.roofStairs(),
                                "minecraft:" + key(pal.roofStairs()) + "[facing=" + facing
                                        + ",half=bottom,shape=straight]"));
                    } else {
                        out.add(new BlockEdit(x, wallTop + 1, z, pal.roofSolid()));
                    }
                    if (x == minX || x == maxX) {
                        for (int gy = wallTop + 1; gy < y; gy++) {
                            out.add(new BlockEdit(x, gy, z, pal.wall()));
                        }
                    }
                    for (int cy = y + 1; cy <= wallTop + rise + 2; cy++) {
                        out.add(new BlockEdit(x, cy, z, Material.AIR));
                    }
                }
            }
        }

        // Chimney on a rear corner, clear of the interior and rising past the ridge.
        if (rng.nextInt(100) < 45) {
            int chx = p.minX() + 1;
            int chz = p.minZ() + 1;
            int top = wallTop + 8;
            for (int y = wallTop + 1; y <= top; y++) {
                out.add(new BlockEdit(chx, y, chz, Material.BRICKS));
            }
            out.add(new BlockEdit(chx, top + 1, chz, Material.CAMPFIRE,
                    "minecraft:campfire[lit=false,signal_fire=false,facing=north,waterlogged=false]"));
        }
    }

    private static String key(Material m) {
        return m.name().toLowerCase(java.util.Locale.ROOT);
    }
}
