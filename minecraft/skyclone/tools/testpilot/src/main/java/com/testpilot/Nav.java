package com.testpilot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walking geometry for {@code /walkto} and {@code /clearance}: everything here asks the world
 * with the PLAYER'S OWN HITBOX (0.6 wide, 1.8 tall) instead of guessing from whole block cells.
 *
 * <p>Why: the first {@code /walkto} decided "is that a step?" by looking at block cells (solid
 * block ahead + two free cells above). That is only true for full cubes. A {@code dirt_path} is
 * 15/16 high, so the rise is 0.94 - too high for the 0.6 auto-step, but the cell logic did not
 * treat it as a jumpable step and the walker pushed into it for minutes (wave-3 playtest, the
 * Barn square). Slabs, paths, farmland, carpets, snow layers, stairs, fences (1.5 high, never
 * jumpable) all have the same problem in one form or another. Testing the actual box answers
 * all of them with one rule.
 *
 * <p>And straight-line steering cannot get round a wall at all (the Library's outer wall), so
 * this also holds a small A* over standable cells: 8 directions, jumps up to {@link #MAX_JUMP},
 * drops up to {@link #MAX_DROP}, closed wooden doors and fence gates counted as passable (the
 * walker opens them, both leaves of a double door).
 */
final class Nav {

    /** Highest rise a standing jump clears (vanilla apex is ~1.2522; keep a margin). */
    static final double MAX_JUMP = 1.2;
    /** Deepest drop the planner walks off (3 blocks costs no fall damage). */
    static final double MAX_DROP = 3.0;
    static final double HALF_W = 0.3;
    static final double HEIGHT = 1.8;
    private static final double EPS = 1e-3;

    private Nav() {
    }

    // ------------------------------------------------------------------ block rules

    /** A wooden door or a fence gate: a player opens it with a right click. */
    static boolean openable(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof DoorBlock d) {
            return d.type().canOpenByHand();
        }
        return b instanceof FenceGateBlock;
    }

    static boolean isOpen(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof DoorBlock) {
            return s.getValue(DoorBlock.OPEN);
        }
        if (b instanceof FenceGateBlock) {
            return s.getValue(FenceGateBlock.OPEN);
        }
        return true;
    }

    /**
     * A ladder or a vine: a player whose FEET block is one climbs it (holding jump, or walking into
     * a wall), slides down it at 0.15 a tick with no fall damage, and holds still on it while
     * sneaking. Vines count since 2026-10-05 (the walker must handle both directions); a climb costs
     * twice a walk per block, so a vine-covered wall is only climbed when it is the way to go.
     * Scaffolding is not here: it is stood on, not climbed, and descended by sneaking.
     */
    static boolean climbable(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof LadderBlock) {
            return true;
        }
        return CLIMB_BLOCKS.computeIfAbsent(b, Nav::climbBlock);
    }

    private static boolean climbBlock(Block b) {
        // the vanilla #climbable tag minus ladders/scaffolding, by id: tags are not bound in unit tests
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return b instanceof VineBlock || id.equals("weeping_vines") || id.equals("weeping_vines_plant")
                || id.equals("twisting_vines") || id.equals("twisting_vines_plant")
                || id.equals("cave_vines") || id.equals("cave_vines_plant");
    }

    private static final Map<Block, Boolean> CLIMB_BLOCKS = new ConcurrentHashMap<>();

    static boolean isWater(BlockState s) {
        FluidState f = s.getFluidState();
        return f.getType() == Fluids.WATER || f.getType() == Fluids.FLOWING_WATER;
    }

    /** Eye height of a standing player above the feet. */
    static final double EYE = 1.62;
    /** Deepest water column {@link #breathableAbove} looks up through for the surface. */
    static final int MAX_SWIM_UP = 12;

    /** A player with feet at {@code y} in column (x, z) has his eyes under the water's surface. */
    static boolean eyesInWater(BlockGetter level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y + EYE, z);
        FluidState f = level.getFluidState(pos);
        if (f.getType() != Fluids.WATER && f.getType() != Fluids.FLOWING_WATER) {
            return false;
        }
        return y + EYE < pos.getY() + f.getHeight(level, pos);
    }

    /**
     * Air to breathe straight up from feet {@code y} in column (x, z): going up through the water,
     * the first block that is not open water has no collision, so a swimmer comes up with his head
     * in it. False under a dock deck, a roof, ice or a waterlogged slab, and for water deeper than
     * {@link #MAX_SWIM_UP}. Only meaningful where {@link #eyesInWater} is true.
     */
    static boolean breathableAbove(BlockGetter level, int x, double y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int by = Mth.floor(y + EYE);
        for (int i = 0; i <= MAX_SWIM_UP; i++, by++) {
            BlockState s = level.getBlockState(m.set(x, by, z));
            boolean open = boxes(level, s, m).length == 0;
            if (isWater(s) && open) {
                continue;                                   // water, kelp, seagrass: keep rising
            }
            return open && !hazard(s);
        }
        return false;
    }

    /**
     * The top of the open water column a player with feet at {@code y} in (x, z) stands in (the
     * block boundary over the highest water block), when it has air above it; NaN under a deck or
     * a roof, or out of water.
     */
    static double waterTop(BlockGetter level, int x, double y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int by = Mth.floor(y + EPS);
        int top = Integer.MIN_VALUE;
        for (int i = 0; i <= MAX_SWIM_UP + 2; i++, by++) {
            BlockState s = level.getBlockState(m.set(x, by, z));
            boolean open = boxes(level, s, m).length == 0;
            if (isWater(s) && open) {
                top = by;
                continue;
            }
            return top != Integer.MIN_VALUE && open && !hazard(s) ? top + 1.0 : Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * The nearest point within {@code r} (horizontal) where a swimmer at {@code from} can surface
     * ({@link #breathableAbove}), reachable in a straight line through the water by a body
     * {@code halfW} wide and {@code height} tall; null when there is none. Used by the walker's
     * breath guard: under a dock, holding jump only presses the head into the deck.
     */
    static Vec3 nearestAir(BlockGetter level, Vec3 from, int r, double halfW, double height) {
        int cx = Mth.floor(from.x), cz = Mth.floor(from.z);
        List<double[]> cands = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double dx = x + 0.5 - from.x, dz = z + 0.5 - from.z;
                double d2 = dx * dx + dz * dz;
                if (d2 <= (double) r * r) {
                    cands.add(new double[] {d2, x, z});
                }
            }
        }
        cands.sort((a, b) -> Double.compare(a[0], b[0]));
        for (double[] c : cands) {
            int x = (int) c[1], z = (int) c[2];
            if (!breathableAbove(level, x, from.y, z)) {
                continue;
            }
            double tx = x + 0.5, tz = z + 0.5;
            double len = Math.sqrt(c[0]);
            boolean clear = true;
            // swept in quarter blocks at auto-step height (a sloping bed is stepped up, a post is not)
            for (double t = 0; t <= len + 1e-9 && clear; t += 0.25) {
                double f = len < 1e-9 ? 1 : Math.min(1, t / len);
                double px = from.x + (tx - from.x) * f, pz = from.z + (tz - from.z) * f;
                clear = boxFree(level, new AABB(px - halfW, from.y + 0.6, pz - halfW, px + halfW, from.y + 0.6 + height,
                        pz + halfW), true, null);
            }
            if (clear) {
                return new Vec3(tx, from.y, tz);
            }
        }
        return null;
    }

    /**
     * The feet height to plan from. Normally the player's own; but a player placed INSIDE a thin
     * block - a carpet, a snow layer - stands below its top, his box overlaps it, and every move out
     * of the start is "blocked". Wave-5 regression: the dungeon start room has a carpet runner, the
     * party is put at y 71.0 inside a carpet whose top is 71.0625, and {@code /walkto} answered
     * {@code noRoute} with 0 nodes expanded (2 runs of 2). Then the floor he is sunk into, if it is
     * within {@code min(stepUp, 0.5)} above the feet (vanilla's own move gets him out of it).
     */
    static double startY(BlockGetter level, Vec3 from, double stepUp, Set<Long> avoid) {
        AABB here = new AABB(from.x - HALF_W, from.y + EPS, from.z - HALF_W, from.x + HALF_W, from.y + HEIGHT, from.z + HALF_W);
        if (boxFree(level, here, false, avoid)) {
            return from.y;
        }
        double up = standAtBox(level, from.x, from.z, from.y, Math.min(stepUp, 0.5), 0, false, avoid);
        return !Double.isNaN(up) && up > from.y ? up : from.y;
    }

    /** Things a person walks around rather than through: lava, fire, cactus, magma, berry bushes. */
    static boolean hazard(BlockState s) {
        FluidState f = s.getFluidState();
        if (f.getType() == Fluids.LAVA || f.getType() == Fluids.FLOWING_LAVA) {
            return true;
        }
        return HAZARD_BLOCKS.computeIfAbsent(s.getBlock(), Nav::hazardBlock);
    }

    private static boolean hazardBlock(Block b) {
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return id.equals("fire") || id.equals("soul_fire") || id.contains("campfire") || id.equals("cactus")
                || id.equals("magma_block") || id.equals("sweet_berry_bush") || id.equals("powder_snow")
                || id.equals("wither_rose");
    }

    // Collision boxes per block state. Almost every state's collision shape is the same wherever
    // it stands, so it is converted to boxes once; the planner asks for tens of thousands of
    // cells per plan and VoxelShape.toAabbs() allocates every time (measured: 110 us a node).
    private static final Map<BlockState, AABB[]> SHAPES = new ConcurrentHashMap<>();
    private static final Map<Block, Boolean> HAZARD_BLOCKS = new ConcurrentHashMap<>();
    private static final AABB[] NONE = new AABB[0];

    static AABB[] boxes(BlockGetter level, BlockState s, BlockPos pos) {
        AABB[] c = SHAPES.get(s);
        if (c != null) {
            return c;
        }
        VoxelShape shape = s.getCollisionShape(level, pos);
        AABB[] a = shape.isEmpty() ? NONE : shape.toAabbs().toArray(new AABB[0]);
        Block b = s.getBlock();
        // position- or context-dependent shapes are never cached (bamboo's offset, scaffolding,
        // powder snow, shulker boxes and moving pistons, which are block entities)
        if (!s.hasOffsetFunction() && !(b instanceof EntityBlock) && !(b instanceof ScaffoldingBlock)
                && !(b instanceof PowderSnowBlock)) {
            SHAPES.put(s, a);
        }
        return a;
    }

    /**
     * No block collision inside {@code box}. Closed openable doors/gates count as free unless
     * {@code doorsSolid} or they are in {@code avoid} (doors that refused to open).
     */
    static boolean boxFree(BlockGetter level, AABB box, boolean doorsSolid, Set<Long> avoid) {
        int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX - 1e-7);
        int y0 = Mth.floor(box.minY) - 1, y1 = Mth.floor(box.maxY - 1e-7);   // -1: fences/walls reach up 1.5
        int z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ - 1e-7);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    m.set(x, y, z);
                    BlockState s = level.getBlockState(m);
                    if (!doorsSolid && openable(s) && (avoid == null || !avoid.contains(lowerHalf(s, m).asLong()))) {
                        continue;
                    }
                    for (AABB a : boxes(level, s, m)) {
                        if (box.intersects(a.minX + x, a.minY + y, a.minZ + z, a.maxX + x, a.maxY + y, a.maxZ + z)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** Hazard anywhere in the cells a standing player at (cell, y) touches, or in the floor. */
    private static boolean hazardAt(BlockGetter level, int x, double y, int z) {
        int fy = Mth.floor(y + EPS);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            if (hazard(level.getBlockState(m.set(x, fy + dy, z)))) {
                return true;
            }
        }
        return false;
    }

    /** The player's box standing centred on block column (x, z) with its feet at {@code y}. */
    static AABB standBox(int x, double y, int z) {
        return new AABB(x + 0.5 - HALF_W, y + EPS, z + 0.5 - HALF_W, x + 0.5 + HALF_W, y + HEIGHT, z + 0.5 + HALF_W);
    }

    /**
     * Highest place a player can stand in column (x, z) between {@code refY - maxDown} and
     * {@code refY + maxUp}, with a free 1.8-tall box above it. NaN when there is none.
     */
    static double standAt(BlockGetter level, int x, int z, double refY, double maxUp, double maxDown,
                          boolean doorsSolid, Set<Long> avoid) {
        if (level instanceof LevelReader r && !r.hasChunkAt(new BlockPos(x, Mth.floor(refY), z))) {
            return Double.NaN;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = Mth.floor(refY + maxUp);
        int bottom = Mth.floor(refY - maxDown) - 1;
        for (int by = top; by >= bottom; by--) {
            BlockState s = level.getBlockState(m.set(x, by, z));
            if (openable(s) && !doorsSolid && (avoid == null || !avoid.contains(lowerHalf(s, m).asLong()))) {
                continue;                                   // a door is not a floor
            }
            if (climbable(s)) {
                continue;                                   // nor is a ladder: its panel is at the cell's edge
            }
            AABB[] parts = boxes(level, s, m);
            if (parts.length == 0) {
                continue;
            }
            double partTop = 0;
            for (AABB a : parts) {
                partTop = Math.max(partTop, a.maxY);
            }
            double floor = by + partTop;
            if (floor > refY + maxUp + EPS) {
                continue;                                   // a wall at body height; maybe a floor below
            }
            if (floor < refY - maxDown - EPS) {
                return Double.NaN;
            }
            if (boxFree(level, standBox(x, floor, z), doorsSolid, avoid)) {
                return floor;
            }
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ steering helpers

    /**
     * Jump height needed to get onto what is directly ahead, or NaN when no jump is needed or a
     * jump would not help. Uses the player's real box: blocked now, still blocked at the
     * auto-step height, free at some lift up to {@link #MAX_JUMP} with headroom to rise there.
     */
    static double jumpNeeded(BlockGetter level, AABB box, double step, double nx, double nz, double probe) {
        AABB ahead = box.move(nx * probe, 0, nz * probe);
        if (boxFree(level, ahead, true, null)) {
            return Double.NaN;                              // nothing in front
        }
        if (boxFree(level, ahead.move(0, step + EPS, 0), true, null)) {
            return Double.NaN;                              // vanilla auto-step handles it
        }
        for (double lift = step + 0.0625; lift <= MAX_JUMP + EPS; lift += 0.0625) {
            if (boxFree(level, ahead.move(0, lift, 0), true, null)
                    && boxFree(level, box.expandTowards(0, lift, 0), true, null)) {
                return lift;
            }
        }
        return Double.NaN;                                  // a wall, a door frame, a fence
    }

    /**
     * How far one can walk from {@code from} along a horizontal direction without jumping or
     * dropping more than one block. Used to point a ridden pet / a sprint at open ground.
     */
    static double clearance(BlockGetter level, Vec3 from, double dx, double dz, double max, double maxUp) {
        // The player's own box slid along the ray in quarter blocks (consecutive 0.6-wide boxes
        // overlap, so no panel between two samples is missed). It used to ask the CELLS along the
        // ray, centre to centre, which answered 0 in all 16 directions for a player standing on the
        // lift cage's sill beside the iron bars: every cell centre near him was inside the bars.
        double y = from.y;
        double d = 0;
        for (double t = 0.25; t <= max + EPS; t += 0.25) {
            double px = from.x + dx * t, pz = from.z + dz * t;
            double ny = standAtBox(level, px, pz, y, maxUp, 1.0, true, null);
            int cx = Mth.floor(px), cz = Mth.floor(pz);
            if (Double.isNaN(ny) || hazardAt(level, cx, ny, cz)
                    || isWater(level.getBlockState(new BlockPos(cx, Mth.floor(ny + EPS), cz)))) {
                break;
            }
            y = ny;
            d = t;
        }
        return d;
    }

    /**
     * {@link #standAt} for the player's box centred anywhere, not only on a cell centre: the
     * highest floor under the box's footprint (a collision box of a block it overlaps, so the top
     * of a wall beside the player is not a floor) with a free 1.8-tall box on it. NaN when none.
     */
    static double standAtBox(BlockGetter level, double x, double z, double refY, double maxUp, double maxDown,
                             boolean doorsSolid, Set<Long> avoid) {
        if (level instanceof LevelReader r && !r.hasChunkAt(BlockPos.containing(x, refY, z))) {
            return Double.NaN;
        }
        double fx0 = x - HALF_W, fx1 = x + HALF_W, fz0 = z - HALF_W, fz1 = z + HALF_W;
        int top = Mth.floor(refY + maxUp);
        int bottom = Mth.floor(refY - maxDown) - 1;
        List<Double> floors = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int bx = Mth.floor(fx0); bx <= Mth.floor(fx1 - 1e-7); bx++) {
            for (int bz = Mth.floor(fz0); bz <= Mth.floor(fz1 - 1e-7); bz++) {
                for (int by = top; by >= bottom; by--) {
                    BlockState s = level.getBlockState(m.set(bx, by, bz));
                    if (openable(s) && !doorsSolid && (avoid == null || !avoid.contains(lowerHalf(s, m).asLong()))) {
                        continue;
                    }
                    for (AABB a : boxes(level, s, m)) {
                        if (a.minX + bx < fx1 && a.maxX + bx > fx0 && a.minZ + bz < fz1 && a.maxZ + bz > fz0) {
                            floors.add(by + a.maxY);
                        }
                    }
                }
            }
        }
        floors.sort(Collections.reverseOrder());
        for (double f : floors) {
            if (f > refY + maxUp + EPS) {
                continue;
            }
            if (f < refY - maxDown - EPS) {
                break;
            }
            if (boxFree(level, new AABB(fx0, f + EPS, fz0, fx1, f + HEIGHT, fz1), doorsSolid, avoid)) {
                return f;
            }
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ edge guard

    /**
     * The deepest fall the walker ever steps into: the planner's {@link #MAX_DROP}, plus slack for
     * measuring from the player's feet mid-stride rather than from a cell's floor.
     */
    static final double SAFE_DROP = MAX_DROP + 0.5;

    /**
     * Something under the player's box centred at (x, z) that stops a fall within {@code maxDrop}
     * of {@code feetY}: a collision box (a floor, or a wall at body height - walking into it is not
     * a fall), water, or a ladder. False over the void, over lava, or over an unloaded chunk.
     *
     * <p>Why it exists: the planner only ever plans onto standable cells, but the walker does not
     * only follow plans - the final approach to the exact point, the "wiggle free" straight
     * steering after three re-plans that do not help, {@code path:false} and the straight-mode
     * sidestep all just hold W. Wave-4 endgame: legs aimed at a Hub coordinate from The End walked
     * off the island into the void about 20 times in a row (a death and half the purse each).
     * This is the one check every steering mode goes through.
     */
    static boolean groundBelow(BlockGetter level, double x, double z, double feetY, double maxDrop) {
        if (level instanceof LevelReader r && !r.hasChunkAt(BlockPos.containing(x, feetY, z))) {
            return false;
        }
        double fx0 = x - HALF_W, fx1 = x + HALF_W, fz0 = z - HALF_W, fz1 = z + HALF_W;
        double lowest = feetY - maxDrop - EPS;
        int top = Mth.floor(feetY + HEIGHT);
        int bottom = Mth.floor(lowest);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int bx = Mth.floor(fx0); bx <= Mth.floor(fx1 - 1e-7); bx++) {
            for (int bz = Mth.floor(fz0); bz <= Mth.floor(fz1 - 1e-7); bz++) {
                for (int by = top; by >= bottom; by--) {
                    BlockState s = level.getBlockState(m.set(bx, by, bz));
                    if (climbable(s) || isWater(s)) {
                        return true;
                    }
                    for (AABB a : boxes(level, s, m)) {
                        if (a.minX + bx < fx1 && a.maxX + bx > fx0 && a.minZ + bz < fz1 && a.maxZ + bz > fz0
                                && by + a.maxY >= lowest) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * How far ahead to look for an edge at a horizontal speed of {@code speed} blocks a tick. The
     * look-ahead box loses its ground {@code look} blocks before the player's own box does (a box
     * falls only once its centre is 0.3 past the edge), so {@code look} is the stopping margin: a
     * player who lets go of every key slides ~1.2x a tick's motion (ground friction 0.546 a tick),
     * and the walker also taps back to brake. Sprint (0.28/tick) looks 0.75 ahead, walking 0.65
     * (worst case it trips 0.75 short of falling and slides 0.34). Not more than 0.8: on a one-wide
     * bridge's corner, sprint momentum carried past the turn would then read as an edge.
     */
    static double edgeLookahead(double speed) {
        return Mth.clamp(0.3 + 1.6 * speed, 0.45, 0.8);
    }

    /**
     * True when moving along (dx, dz) from (x, feetY, z) at {@code speed} would put the player's
     * box over a drop deeper than {@link #SAFE_DROP} (an island edge, a hole into the void, lava)
     * within the stopping distance. Samples the look-ahead and half of it.
     */
    static boolean edgeAhead(BlockGetter level, double x, double z, double feetY, double dx, double dz, double speed) {
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-9) {
            return false;
        }
        double nx = dx / d, nz = dz / d;
        double look = edgeLookahead(speed);
        return !groundBelow(level, x + nx * look * 0.5, z + nz * look * 0.5, feetY, SAFE_DROP)
                || !groundBelow(level, x + nx * look, z + nz * look, feetY, SAFE_DROP);
    }

    // ------------------------------------------------------------------ ladder holes

    /**
     * On a ladder, heading for a waypoint below it: keep sliding (no W) while the box at this height
     * cannot move toward it - its head is still under the floor the ladder came down through, and W
     * into that floor is a CLIMB in vanilla (walking into a wall while on a ladder).
     */
    static boolean slideFirst(BlockGetter level, AABB box, double nx, double nz) {
        return !boxFree(level, box.move(nx * 0.35, 0, nz * 0.35), true, null);
    }

    /** The player's box at (x, z) one hair below feet height {@code y} touches nothing: he drops. */
    static boolean dropsAt(BlockGetter level, double x, double y, double z) {
        return boxFree(level, new AABB(x - HALF_W, y - 0.05, z - HALF_W, x + HALF_W, y + HEIGHT - 0.1, z + HALF_W), true, null);
    }

    /**
     * Where to stand to drop INTO the hole of column (x, z) from feet height {@code y} - the top of a
     * ladder down through a floor. The body only falls in once the whole 0.6-wide box is over the
     * hole, and a ladder's panel takes 3/16 of a one-block hole, so the window is 0.21 wide on that
     * axis and the cell centre sits 0.0125 from its edge (Tavern ladder, wave-4: the walker circled
     * the hole for four minutes standing on its rim). Picks the point with the most room on every
     * side (up to 0.15), nearest the centre on a tie; the centre when nothing drops.
     */
    static Vec3 grabPoint(BlockGetter level, int x, double y, int z) {
        double bx = x + 0.5, bz = z + 0.5, bestRoom = -1, bestD = Double.MAX_VALUE;
        for (int i = 0; i <= 16; i++) {
            for (int j = 0; j <= 16; j++) {
                double px = x + 0.3 + i * 0.025, pz = z + 0.3 + j * 0.025;
                if (!dropsAt(level, px, y, pz)) {
                    continue;
                }
                double room = 0;
                for (double m = 0.025; m <= 0.15 + 1e-9; m += 0.025) {
                    if (dropsAt(level, px + m, y, pz) && dropsAt(level, px - m, y, pz)
                            && dropsAt(level, px, y, pz + m) && dropsAt(level, px, y, pz - m)) {
                        room = m;
                    } else {
                        break;
                    }
                }
                double d = (px - x - 0.5) * (px - x - 0.5) + (pz - z - 0.5) * (pz - z - 0.5);
                if (room > bestRoom + 1e-9 || (Math.abs(room - bestRoom) < 1e-9 && d < bestD)) {
                    bestRoom = room;
                    bestD = d;
                    bx = px;
                    bz = pz;
                }
            }
        }
        return new Vec3(bx, y, bz);
    }

    // ------------------------------------------------------------------ A*

    /** One cell of a planned path: walk to its centre, feet at {@code y}. */
    static final class Waypoint {
        final int bx, bz;
        final double y;
        final boolean jump;
        /** Reached on a ladder: climbing up or down its column, or stepping off a floor onto its top. */
        final boolean climb;
        /** Closed-at-plan-time openable doors/gates in this cell (feet or head), lower halves. */
        final List<BlockPos> doors;
        /**
         * A climb cell with no floor at this height: the top of a ladder down a hole, or a rung
         * mid-column. It is reached by being ON the ladder, never by standing 0.4 from its centre -
         * the old walker counted the hole as reached from its rim and stood there for minutes.
         */
        final boolean hang;

        Waypoint(int bx, double y, int bz, boolean jump, boolean climb, List<BlockPos> doors) {
            this(bx, y, bz, jump, climb, doors, false);
        }

        Waypoint(int bx, double y, int bz, boolean jump, boolean climb, List<BlockPos> doors, boolean hang) {
            this.bx = bx;
            this.y = y;
            this.bz = bz;
            this.jump = jump;
            this.climb = climb;
            this.doors = doors;
            this.hang = hang;
        }

        double cx() {
            return bx + 0.5;
        }

        double cz() {
            return bz + 0.5;
        }
    }

    static final class Plan {
        final List<Waypoint> path;
        /** True when the path ends at the goal, false when it ends at the closest reachable cell. */
        final boolean complete;
        final int expanded;

        Plan(List<Waypoint> path, boolean complete, int expanded) {
            this.path = path;
            this.complete = complete;
            this.expanded = expanded;
        }
    }

    private record Col(int x, int z, long yq) {
    }

    private static final class Node {
        final int x, z;
        final double y;
        final long key;
        double g, f;
        Node parent;
        boolean jump, climb;
        /** Reached by a drop too deep to jump back up (somewhere along the way): see {@link #TRAP_GAIN}. */
        boolean trap;
        boolean closed;

        Node(int x, double y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = BlockPos.asLong(x, Mth.floor(y + EPS), z);
        }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** Climbing is ~2.35 blocks/s against ~4.3 walking; sliding down is about walking pace. */
    private static final double CLIMB_UP_COST = 2.0, CLIMB_DOWN_COST = 1.0;

    /** The open/closed bookkeeping of one search. */
    private static final class Search {
        final Map<Long, Node> nodes = new HashMap<>();
        final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        final Vec3 target;
        final boolean useY;

        Search(Vec3 target) {
            this.target = target;
            this.useY = !Double.isNaN(target.y);
        }

        void relax(Node cur, int x, double y, int z, double cost, boolean jump, boolean climb, boolean trap) {
            Node n = new Node(x, y, z);
            Node known = nodes.get(n.key);
            if (known != null) {
                if (known.closed || known.g <= cur.g + cost) {
                    return;
                }
                n = known;
            } else {
                nodes.put(n.key, n);
            }
            n.g = cur.g + cost;
            n.f = n.g + heuristic(n, target, useY);
            n.parent = cur;
            n.jump = jump;
            n.climb = climb;
            n.trap = cur.trap || trap;
            open.add(n);
        }
    }

    /**
     * A partial plan (the goal is out of reach or out of budget) ends at the explored cell closest to
     * the goal. It may only get there by a drop the player cannot climb back up if that drop buys
     * at least this many blocks of progress over the best cell reachable without one. Wave-4 final
     * verification: walks toward points off the Private Island's north side ended on a one-wide
     * ledge three blocks under the islet's rim - one block "closer" - from which nothing led back
     * (every way out was a 2-block wall), so the next walk answered "no route" at once.
     */
    static final double TRAP_GAIN = 8.0;

    /** A column key for {@code noGo} sets: (x, z), any height. */
    static long column(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    static Plan plan(BlockGetter level, Vec3 from, Vec3 target, double goalR, double stepUp, Set<Long> avoid, int maxNodes) {
        return plan(level, from, target, goalR, stepUp, avoid, maxNodes, null);
    }

    /**
     * Path from the player's feet to within {@code goalR} (horizontal) of {@code target}. If the
     * goal is not reached inside {@code maxNodes}, the path goes to the explored cell closest to
     * it (the caller re-plans from there). Null when not even one step can be taken. Columns in
     * {@code noGo} ({@link #column}) are never stepped into: steps the walker found it could not
     * take without falling.
     */
    static Plan plan(BlockGetter level, Vec3 from, Vec3 target, double goalR, double stepUp, Set<Long> avoid, int maxNodes,
                     Set<Long> noGo) {
        return plan(level, from, target, goalR, stepUp, avoid, maxNodes, noGo, false);
    }

    /**
     * {@code dry}: never step INTO water (a fight from a dock, {@code kill_nearest}). A player
     * already in water may still swim through it to get out. Whatever {@code dry} says, a water
     * cell where the eyes would be under water with no air above (under a dock, a roof, ice) is
     * never planned: see {@link #breathableAbove}.
     */
    static Plan plan(BlockGetter level, Vec3 from, Vec3 target, double goalR, double stepUp, Set<Long> avoid, int maxNodes,
                     Set<Long> noGo, boolean dry) {
        Search q = new Search(target);
        boolean useY = q.useY;
        Node start = new Node(Mth.floor(from.x), startY(level, from, stepUp, avoid), Mth.floor(from.z));
        boolean startWet = isWater(level.getBlockState(BlockPos.containing(from.x, start.y + EPS, from.z)))
                || eyesInWater(level, from.x, start.y, from.z);
        boolean noWater = dry && !startWet;
        Map<Col, Double> stands = new HashMap<>();      // a column is asked by up to 8 neighbours
        start.g = 0;
        start.f = heuristic(start, target, useY);
        start.climb = climbable(level.getBlockState(BlockPos.containing(from.x, from.y + EPS, from.z)));
        q.nodes.put(start.key, start);
        q.open.add(start);
        // the closest cell to the goal so far, with and without an irreversible drop on the way
        // (only cells one can STAND in: a partial plan never ends hanging on a ladder or a vine)
        Node best = start, bestTrap = null;
        // A partial plan must get at least a block closer than where the player stands. Live on the
        // Private Island's islet rim (2026-10-05), two one-block dips the same distance from a far
        // target each "beat" the other by a hair - the player was still settling, mid-fall, so his
        // own cell scored his fractional height - and the walker ping-ponged between them for the
        // whole timeout instead of answering "no route".
        double bestH = start.f - 1.0, bestTrapH = Double.MAX_VALUE;
        Node goal = null;
        int expanded = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!q.open.isEmpty() && expanded < maxNodes) {
            Node cur = q.open.poll();
            if (cur.closed) {
                continue;
            }
            cur.closed = true;
            expanded++;
            double h = heuristic(cur, target, useY);
            int fy = Mth.floor(cur.y + EPS);
            // On a ladder with no floor under the feet: no jumping off it, no diagonal steps.
            boolean hanging = cur.climb && Double.isNaN(standAt(level, cur.x, cur.z, cur.y, 2 * EPS, 2 * EPS, false, avoid));
            if (!hanging || cur == start) {
                if (!cur.trap) {
                    if (h < bestH - 1e-9 || (Math.abs(h - bestH) < 1e-9 && cur.g < best.g)) {
                        best = cur;
                        bestH = h;
                    }
                } else if (h < bestTrapH - 1e-9 || (Math.abs(h - bestTrapH) < 1e-9 && cur.g < bestTrap.g)) {
                    bestTrap = cur;
                    bestTrapH = h;
                }
            }
            if (isGoal(cur, target, goalR, useY)) {
                goal = cur;
                break;
            }
            // Moves out of the START leave from where the player really stands, not from its cell's
            // centre. A player pressed against a wall, a fence or iron bars is often in a cell whose
            // centre is INSIDE them (the Graveyard's west wall at x=140, the ledge of the Deep
            // Caverns lift cage, beside the Tavern's notice board); swept from the centre, every move
            // hit the wall, the plan came back null and the walker spent minutes re-planning the
            // same nothing (wave-4 playtest: "57 replans, 0 waypoints"; "clearance 0 in all 16").
            double ox = cur == start ? from.x : cur.x + 0.5;
            double oz = cur == start ? from.z : cur.z + 0.5;
            // In water a swimmer floats up to the surface and climbs out onto a bank up to a block
            // above it (vanilla's water-exit boost), whatever the depth he stands at: neighbours are
            // asked from the surface. Without this a 3-deep pool had no way out at all.
            double refY = cur.y, refDown = MAX_DROP;
            double surf = isWater(level.getBlockState(m.set(cur.x, fy, cur.z))) ? waterTop(level, cur.x, cur.y, cur.z) : Double.NaN;
            if (!Double.isNaN(surf) && surf - 0.2 > cur.y) {
                refY = surf - 0.2;
                refDown = MAX_DROP + (refY - cur.y);
            }
            final double askY = refY, askDown = refDown;
            for (int[] d : DIRS) {
                int nx = cur.x + d[0], nz = cur.z + d[1];
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && cur.climb) {
                    continue;
                }
                if (noGo != null && noGo.contains(column(nx, nz))) {
                    continue;
                }
                double ny = askY == cur.y
                        ? stands.computeIfAbsent(new Col(nx, nz, Math.round(cur.y * 64)),
                                k -> standAt(level, k.x(), k.z(), cur.y, MAX_JUMP, MAX_DROP, false, avoid))
                        : standAt(level, nx, nz, askY, MAX_JUMP, askDown, false, avoid);
                boolean onLadder = false;
                if (!diag && Double.isNaN(ny)
                        && (climbable(level.getBlockState(m.set(nx, fy - 1, nz))) || climbable(level.getBlockState(m.set(nx, fy, nz))))
                        && boxFree(level, standBox(nx, fy, nz), false, avoid)) {
                    // The top of a ladder down a hole (an upper floor, a well, a mine shaft): no
                    // floor within a safe drop, but stepping off the edge onto it is how one goes down.
                    ny = fy;
                    onLadder = true;
                }
                if (Double.isNaN(ny) || hazardAt(level, nx, ny, nz)) {
                    continue;
                }
                boolean wet = isWater(level.getBlockState(m.set(nx, Mth.floor(ny + EPS), nz)));
                boolean eyesWet = eyesInWater(level, nx + 0.5, ny, nz + 0.5);
                if ((wet || eyesWet) && noWater) {
                    continue;                               // dry: the fight stays on the dock
                }
                if (eyesWet && !breathableAbove(level, nx, ny, nz)) {
                    // Head under water with a dock, a roof or ice over the water: no air to come up
                    // for. Wave-5 regression, the Bayou dock: kill_nearest followed a Trash Gobbler
                    // under the deck and the player drowned there (half the purse).
                    continue;
                }
                double rise = ny - cur.y;
                if (hanging && rise > EPS) {
                    continue;
                }
                boolean jump = rise > stepUp + EPS;
                if (rise > EPS) {
                    // room to rise in the column we jump from
                    AABB col = new AABB(ox - HALF_W, cur.y + EPS, oz - HALF_W, ox + HALF_W, ny + HEIGHT, oz + HALF_W);
                    if (!boxFree(level, col, false, avoid)) {
                        continue;
                    }
                } else if (rise < -EPS) {
                    // room to walk over the edge at our height, then fall
                    AABB col = new AABB(nx + 0.5 - HALF_W, ny + EPS, nz + 0.5 - HALF_W,
                            nx + 0.5 + HALF_W, cur.y + HEIGHT, nz + 0.5 + HALF_W);
                    if (!boxFree(level, col, false, avoid)) {
                        continue;
                    }
                }
                // The move itself, not just the two ends: a closed door, a glass pane, iron bars or a
                // fence arm is a thin panel at a cell EDGE - a box standing at either centre misses it.
                double hy = Math.max(cur.y, ny);
                AABB sweep = new AABB(Math.min(ox, nx + 0.5) - HALF_W, hy + EPS, Math.min(oz, nz + 0.5) - HALF_W,
                        Math.max(ox, nx + 0.5) + HALF_W, hy + HEIGHT, Math.max(oz, nz + 0.5) + HALF_W);
                if (!boxFree(level, sweep, false, avoid)) {
                    continue;
                }
                List<BlockPos> doors = closedDoors(level, nx, ny, nz, avoid);
                if (diag) {
                    if (jump || rise < -stepUp || !doors.isEmpty()) {
                        continue;
                    }
                    if (!flatNeighbour(level, cur.x + d[0], cur.z, cur.y, ny, stepUp, avoid)
                            || !flatNeighbour(level, cur.x, cur.z + d[1], cur.y, ny, stepUp, avoid)) {
                        continue;                           // never cut a corner
                    }
                }
                double cost = diag ? Math.sqrt(2) : 1.0;
                if (jump) {
                    cost += 1.0;
                }
                if (rise < -1.1) {
                    cost += -rise * 0.5;
                }
                if (rise < -stepUp - EPS && !onLadder && !diag
                        && !groundBelow(level, nx + d[0] + 0.5, nz + d[1] + 0.5, ny, SAFE_DROP)) {
                    // a step DOWN onto a ledge with the void right past it: the fall carries the body
                    // on (a sprint lands ~1.4 blocks past the lip). The walker's guard makes it
                    // creep down such a step; prefer a way that does not need one.
                    cost += 3.0;
                }
                if (onLadder) {
                    cost += 0.5;
                }
                if (!doors.isEmpty()) {
                    cost += 1.5;
                }
                if (wet) {
                    cost += 4.0;
                }
                if (eyesWet) {
                    cost += 4.0;                            // swimming with the head under: air runs out
                }
                boolean irreversible = rise < -MAX_JUMP - EPS && !onLadder
                        && !climbable(level.getBlockState(m.set(nx, Mth.floor(ny + EPS), nz)));
                q.relax(cur, nx, ny, nz, cost, jump, onLadder, irreversible);
            }
            // Up and down a ladder. Up: the feet are in a ladder block (hold jump; one block past
            // its top is where one steps off onto the floor it leads to). Down: a ladder under the
            // feet. Same column, so the room needed is just the box at the new height.
            if (climbable(level.getBlockState(m.set(cur.x, fy, cur.z)))
                    && boxFree(level, standBox(cur.x, fy + 1, cur.z), false, avoid) && !hazardAt(level, cur.x, fy + 1, cur.z)) {
                q.relax(cur, cur.x, fy + 1, cur.z, CLIMB_UP_COST, false, true, false);
            }
            if (climbable(level.getBlockState(m.set(cur.x, fy - 1, cur.z)))
                    && boxFree(level, standBox(cur.x, fy - 1, cur.z), false, avoid) && !hazardAt(level, cur.x, fy - 1, cur.z)) {
                q.relax(cur, cur.x, fy - 1, cur.z, CLIMB_DOWN_COST, false, true, false);
            }
        }
        Node end = goal;
        if (end == null) {
            end = best;
            // a lot of progress, or the only progress there is (but not a pit for one block of it)
            if (bestTrap != null && bestTrapH < bestH - (best == start ? 3.0 : TRAP_GAIN)) {
                end = bestTrap;
            }
        }
        if (end == start) {
            return goal != null ? new Plan(List.of(), true, expanded) : null;
        }
        List<Waypoint> path = new ArrayList<>();
        for (Node n = end; n != null && n != start; n = n.parent) {
            boolean hang = n.climb && Double.isNaN(standAt(level, n.x, n.z, n.y, 2 * EPS, 2 * EPS, false, avoid));
            path.add(new Waypoint(n.x, n.y, n.z, n.jump, n.climb, closedDoors(level, n.x, n.y, n.z, avoid), hang));
        }
        Collections.reverse(path);
        return new Plan(path, goal != null, expanded);
    }

    /**
     * The waypoint to steer for next: skips every one already reached (within 0.4 and a block of
     * height) or already passed on a straight run. A {@link Waypoint#hang} cell - a ladder hole - is
     * reached only by being on the ladder or below its top: the old walker counted the hole as
     * reached from its rim, stopped pushing, and stood there.
     */
    static int advance(List<Waypoint> path, int idx, double x, double y, double z, boolean onLadder) {
        while (idx < path.size()) {
            Waypoint w = path.get(idx);
            double hx = w.cx() - x, hz = w.cz() - z;
            double hd = Math.sqrt(hx * hx + hz * hz);
            boolean passed = false;
            if (!w.hang && idx + 1 < path.size() && hd < 0.9 && Math.abs(y - w.y) < 0.6) {
                // already nearer the NEXT centre than this one is: on a straight run that means we
                // are past it. On a turn it is false until the corner is actually reached, so this
                // never cuts round a wall.
                Waypoint nw = path.get(idx + 1);
                double px = nw.cx() - x, pz = nw.cz() - z;
                double cx = nw.cx() - w.cx(), cz = nw.cz() - w.cz();
                passed = px * px + pz * pz < cx * cx + cz * cz && Math.abs(nw.y - w.y) < 0.6;
            }
            boolean reached = (hd < 0.4 && Math.abs(y - w.y) < 1.0) || passed;
            if (w.climb) {
                // a rung is reached at its height, or once past it toward the next rung of the same
                // column: counted a block early, the walker stepped off a ladder with its head still
                // under the floor above and the push into that floor climbed it back up
                Waypoint nw = idx + 1 < path.size() ? path.get(idx + 1) : null;
                double dir = nw != null && nw.bx == w.bx && nw.bz == w.bz ? Math.signum(nw.y - w.y) : 0;
                reached = hd < 0.4 && (Math.abs(y - w.y) < 0.35 || (dir != 0 && (y - w.y) * dir > 0));
            }
            if (w.hang && !onLadder && y > w.y - 0.3) {
                reached = false;
            }
            if (!reached) {
                break;
            }
            idx++;
        }
        return idx;
    }

    /**
     * A plan that gets the player nowhere: none at all (boxed in), or a partial one whose closest
     * cell is where he already stands (every reachable cell is further from the target).
     */
    static boolean leadsNowhere(Plan plan, Vec3 from) {
        if (plan == null) {
            return true;
        }
        if (plan.complete) {
            return false;
        }
        if (plan.path.isEmpty()) {
            return true;
        }
        Waypoint end = plan.path.get(plan.path.size() - 1);
        double hx = end.cx() - from.x, hz = end.cz() - from.z;
        return hx * hx + hz * hz < 1.0 && Math.abs(end.y - from.y) < 1.0;
    }

    private static boolean flatNeighbour(BlockGetter level, int x, int z, double y0, double y1, double stepUp, Set<Long> avoid) {
        double y = standAt(level, x, z, y0, stepUp, stepUp, true, avoid);
        return !Double.isNaN(y) && Math.abs(y - y1) <= stepUp + EPS && !hazardAt(level, x, y, z);
    }

    private static double heuristic(Node n, Vec3 t, boolean useY) {
        double dx = n.x + 0.5 - t.x, dz = n.z + 0.5 - t.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        if (useY) {
            h += Math.max(0, Math.abs(n.y - t.y) - 1.0) * 0.5;
        }
        return h;
    }

    private static boolean isGoal(Node n, Vec3 t, double goalR, boolean useY) {
        double dx = n.x + 0.5 - t.x, dz = n.z + 0.5 - t.z;
        return Math.sqrt(dx * dx + dz * dz) <= goalR && (!useY || Math.abs(n.y - t.y) <= 2.5);
    }

    // ------------------------------------------------------------------ doors

    /** Closed openable doors/gates a player standing at (x, y, z) would be inside (lower halves). */
    static List<BlockPos> closedDoors(BlockGetter level, int x, double y, int z, Set<Long> avoid) {
        List<BlockPos> out = new ArrayList<>(0);
        int fy = Mth.floor(y + EPS);
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos pos = new BlockPos(x, fy + dy, z);
            BlockState s = level.getBlockState(pos);
            if (!openable(s) || isOpen(s)) {
                continue;
            }
            BlockPos lower = lowerHalf(s, pos);
            if ((avoid == null || !avoid.contains(lower.asLong())) && !out.contains(lower)) {
                out.add(lower);
            }
        }
        return out;
    }

    static BlockPos lowerHalf(BlockState s, BlockPos pos) {
        if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    /**
     * The other leaf of a double door: a closed door beside this one, same facing, other hinge.
     * Null when there is none (or it is already open).
     */
    static BlockPos partnerLeaf(BlockGetter level, BlockPos door) {
        BlockState s = level.getBlockState(door);
        if (!(s.getBlock() instanceof DoorBlock)) {
            return null;
        }
        Direction facing = s.getValue(DoorBlock.FACING);
        for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos q = door.relative(side);
            BlockState o = level.getBlockState(q);
            if (o.getBlock() instanceof DoorBlock && openable(o) && !isOpen(o)
                    && o.getValue(DoorBlock.FACING) == facing && o.getValue(DoorBlock.HINGE) != s.getValue(DoorBlock.HINGE)) {
                return lowerHalf(o, q);
            }
        }
        return null;
    }
}
