package org.aincrad.plugin.terrain;

import org.aincrad.plugin.editor.BlockMask;
import org.aincrad.plugin.editor.EditQueue;
import org.aincrad.plugin.editor.Editor;
import org.aincrad.plugin.editor.RegionReader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Grows a belt of natural land outward from an existing island, and carves the island edge on the
 * <em>outer</em> boundary of the result.
 *
 * <p>The problem it solves: the imported SAO Floor One build ends where its region files end, which is a
 * straight line through the middle of the countryside with villages sitting right on the brink. Aincrad's
 * floors are plates hanging in the sky, so the edge is supposed to be visible - but it is supposed to be
 * an edge with nothing important next to it. This grows the island by half its area again in every
 * direction, blends the new ground into the old, and puts the drop at the far side of that belt.</p>
 *
 * <h2>Scan first, always</h2>
 * <p>{@code //apron} only ever measures and reports; {@code //apron confirm} writes. This is the same
 * discipline {@code //import} follows, for the same reason: {@code //island 50} once read as "radius 50",
 * carved 800 blocks instead, and deleted 117 million blocks of somebody's world. A command that shapes
 * terrain by the tens of millions of blocks has to say what it is about to do first.</p>
 *
 * <p>{@link ApronPlan} does the measuring and all the geometry off the main thread. This class turns the
 * finished plan into blocks, and does it lazily: one chunk's writes are built at a time, on demand from
 * {@link EditQueue}, because a 30-million-block operation materialised up front would be gigabytes of
 * {@code Write} records.</p>
 */
public final class ApronGenerator {

    /** How deep the soil layer under the surface block goes. */
    private static final int SOIL = 3;

    /** Plans waiting for a confirm, one per player. Dropped as soon as it is used. */
    private static final Map<UUID, Pending> pending = new HashMap<>();

    /** A plan older than this may no longer describe the world, so the confirm says so. */
    private static final long STALE_MILLIS = 10 * 60 * 1000L;


    private record Pending(ApronPlan plan, String worldName, long madeAt) {
    }

    private ApronGenerator() {
    }

    // ================================================================== commands

    /**
     * Measures the island the player is standing on, plans an apron and reports it. Writes nothing.
     *
     * @param margin    belt width in blocks, or null to grow the island by half its area again
     * @param thickness rock depth under the new ground, or null to match the existing land
     */
    public static void scan(Plugin plugin, Player player, ApronPlan.Bounds bounds,
                            Integer margin, Integer thickness) {
        World world = player.getWorld();
        java.io.File regionDir = RegionReader.regionFolder(world.getWorldFolder().getAbsoluteFile(),
                world.getKey().getNamespace(), world.getKey().getKey());
        int startCx = player.getLocation().getBlockX() >> 4;
        int startCz = player.getLocation().getBlockZ() >> 4;

        Editor.tell(player, "Reading the land around you from disk. <gray>Nothing is being written.");
        // The plan reads region files, so anything edited but not yet saved would be invisible to it.
        // Saving here rather than telling him to type save-all: a step he has to remember is a step
        // that gets forgotten, and the failure would look like the apron ignoring his last edit.
        long savedAt = System.currentTimeMillis();
        world.save();
        long saveMillis = System.currentTimeMillis() - savedAt;
        if (saveMillis > 1500) {
            Editor.tell(player, "<gray>Saved the world first (" + saveMillis + " ms) so the scan sees "
                    + "your most recent edits.");
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ApronPlan plan;
            try {
                plan = ApronPlan.build(regionDir, world.getSeed(), startCx, startCz, bounds,
                        margin, thickness,
                        message -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) {
                                player.sendActionBar(Editor.component("<gray>" + message));
                            }
                        }));
            } catch (ApronPlan.Fault fault) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        Editor.tell(player, "<red>" + fault.getMessage()));
                return;
            } catch (RuntimeException | OutOfMemoryError ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Editor.tell(player, "<red>Planning the apron failed: " + ex);
                    plugin.getLogger().severe("Apron plan aborted: " + ex);
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                pending.put(player.getUniqueId(),
                        new Pending(plan, world.getName(), System.currentTimeMillis()));
                report(player, plan);
            });
        });
    }

    /** Writes the plan the player last scanned. */
    public static void confirm(Plugin plugin, Editor editor, Player player) {
        Pending held = pending.remove(player.getUniqueId());
        if (held == null) {
            Editor.tell(player, "<red>Nothing planned yet.</red> Run "
                    + Editor.instant("//apron", "//apron") + " first - it measures the land and tells you "
                    + "what it would build before anything is written.");
            return;
        }
        if (!held.worldName().equals(player.getWorld().getName())) {
            Editor.tell(player, "<red>That plan was made in " + held.worldName()
                    + " and you are in " + player.getWorld().getName() + ".</red> Run //apron again.");
            return;
        }
        ApronPlan plan = held.plan();
        ApronPlan.Report r = plan.report();
        if (System.currentTimeMillis() - held.madeAt() > STALE_MILLIS) {
            Editor.tell(player, "<yellow>That plan is " + ((System.currentTimeMillis() - held.madeAt())
                    / 60000) + " minutes old.</yellow> <gray>If you have edited the terrain since, run "
                    + Editor.instant("//apron", "//apron") + " again first - it will not know about it.");
        }

        Editor.tell(player, "<aqua>Building the apron.</aqua> " + Editor.plural(r.totalBlocks(), "block")
                + " across " + Editor.plural(r.chunkCount(), "chunk") + ".");
        Editor.tell(player, "<red>Not undoable</red> <gray>- at this size an undo buffer would be larger "
                + "than the world. " + Editor.runnable("//cancel", "//cancel") + " stops it part-way, and "
                + "what is written stays written.");
        long seconds = r.totalBlocks() / Math.max(1, editor.queue().blocksPerTick() * 20L);
        Editor.tell(player, "<gray>About " + describeDuration(seconds) + " at the current "
                + Editor.format(editor.queue().blocksPerTick()) + " blocks/tick. Faster: "
                + Editor.runnable("//perf 40000", "//perf 40000"));

        editor.queue().submitBulk(player, player.getWorld(), "grow the apron", source(plan));
    }

    private static String describeDuration(long seconds) {
        if (seconds < 90) {
            return Math.max(1, seconds) + " seconds";
        }
        return (seconds / 60) + " minutes";
    }

    // ================================================================== the report

    private static void report(Player p, ApronPlan plan) {
        ApronPlan.Report r = plan.report();
        Editor.raw(p, "");
        Editor.raw(p, "<dark_aqua><strikethrough>              </strikethrough> "
                + "<aqua><bold>Apron plan</bold></aqua> "
                + "<dark_aqua><strikethrough>              </strikethrough>");
        Editor.raw(p, "  <gray>The land you are on: <white>"
                + Editor.format((long) (r.landMaxX() - r.landMinX() + 1)) + " x "
                + Editor.format((long) (r.landMaxZ() - r.landMinZ() + 1)) + "</white> blocks, "
                + Editor.plural(r.landChunks(), "chunk") + " of it, ground around <white>Y="
                + r.medianGroundY() + "</white>");
        Editor.raw(p, "     <dark_gray>X " + r.landMinX() + ".." + r.landMaxX()
                + "   Z " + r.landMinZ() + ".." + r.landMaxZ()
                + "   (measured in " + r.millis() + " ms)");
        if (r.capped()) {
            Editor.raw(p, "  <yellow>The land ran past the " + Editor.format(ApronPlan.MAX_LAND_CHUNKS)
                    + "-chunk scan limit, so this is only part of it.");
        }
        Editor.raw(p, "  <gray>Surface it will continue outward:");
        r.surfaceMix().forEach((name, count) -> Editor.raw(p, "     <white>"
                + String.format("%,10d", count) + "</white>  <gray>" + name.replace("minecraft:", "")));

        Editor.raw(p, "");
        Editor.raw(p, "  <gray>New belt <white>" + r.margin() + " blocks</white> wide"
                + (r.marginChosen() ? " <dark_gray>(chosen to make the island half again as big)" : ""));
        Editor.raw(p, "  <gray>Island grows to <white>"
                + String.format("%.0f%%", r.areaGrowth() * 100) + "</white> of its current area: "
                + Editor.plural(r.apronColumns(), "new column"));
        Editor.raw(p, "     <dark_gray>the coastline wobbles, so the belt is not the same width all "
                + "the way round");
        Editor.raw(p, "  <gray>Reaches X <white>" + r.outMinX() + ".." + r.outMaxX()
                + "</white>  Z <white>" + r.outMinZ() + ".." + r.outMaxZ() + "</white>");
        Editor.raw(p, "  <gray>Rock under it <white>" + r.thickness() + " blocks</white>, tapering to a "
                + "thin rim at the edge");
        if (r.undersideY() == Integer.MIN_VALUE) {
            Editor.raw(p, "     <yellow>Could not read the existing island's underside, so this is a guess.");
        } else if (r.medianGroundY() - r.undersideY() > 120) {
            Editor.raw(p, "     <yellow>The land you have is solid down to Y=" + r.undersideY()
                    + "</yellow><gray> - so from below, the new edge will be a thin plate joined to a "
                    + "solid block. " + Editor.runnable("//island", "//island")
                    + " over the imported area gives it a matching underside.");
        } else {
            Editor.raw(p, "     <dark_gray>matching the existing underside at Y=" + r.undersideY());
        }
        if (r.waterBlocks() > 0) {
            Editor.raw(p, "  <gray>Water at <white>Y=" + r.waterY() + "</white> in the low ground: "
                    + Editor.plural(r.waterBlocks(), "block")
                    + " <dark_gray>(none within 26 blocks of the edge, so nothing pours off)");
        }
        if (r.trees() > 0) {
            Editor.raw(p, "  <gray>Trees <white>" + Editor.format(r.trees()) + "</white>, plus grass "
                    + "and flowers on the open ground");
        }
        if (r.clearBlocks() > 0) {
            Editor.raw(p, "");
            Editor.raw(p, "  <gray>Old terrain to take out: <white>"
                    + Editor.plural(r.clearBlocks(), "block") + "</white>");
            Editor.raw(p, "     <dark_gray>the belt is not empty - it is flat leftover pregeneration, so "
                    + "the apron replaces it rather than filling around it");
            if (r.clearColumns() > 0) {
                Editor.raw(p, "     <dark_gray>" + Editor.format(r.clearColumns()) + " of those columns "
                        + "are past the new coastline in chunks that have to be kept, so the edge does "
                        + "not come out as 16-block steps");
            }
        }
        Editor.raw(p, "");
        Editor.raw(p, "  <white>" + Editor.plural(r.totalBlocks(), "block") + "</white> <gray>to write, "
                + "over " + Editor.plural(r.chunkCount(), "chunk") + ".");
        Editor.raw(p, "  <gray>Island keeps <white>" + Editor.format(r.keptChunks())
                + "</white> chunks. The other <white>" + Editor.format(r.trimmedChunks())
                + "</white> on disk are stale plain <gray>-");
        Editor.raw(p, "     <dark_gray>those are deleted from the region files by "
                + "<white>Trim To Island.bat</white> with the server stopped, not written as air. "
                + "It backs the world up first.");
        Editor.raw(p, "  " + Editor.instant("<aqua>[ //apron confirm ]", "//apron confirm")
                + " <gray>builds it.  <dark_gray>Not undoable.");
        Editor.raw(p, "  <dark_gray>Different width: <white>//apron 300</white>. "
                + "Different rock depth: <white>//apron 300 60</white>.");
        Editor.raw(p, "");
    }

    // ================================================================== writing

    /**
     * The apron as a stream of block writes, chunk by chunk.
     *
     * <p>No mask, deliberately. An earlier version restricted writes to air on the theory that the apron
     * only ever fills empty space, which turned out to be false: the belt is already occupied by a slab
     * of flat leftover terrain, and an air-only mask would have made the whole command silently do
     * nothing. What protects the imported build is the plan - it never marks a column that holds source
     * land - not a mask.</p>
     */
    private static EditQueue.WriteSource source(ApronPlan plan) {
        return new EditQueue.WriteSource() {
            @Override
            public long estimate() {
                return plan.report().totalBlocks();
            }

            @Override
            public Iterator<EditQueue.Write> iterator() {
                return new ChunkAtATime(plan);
            }
        };
    }

    /**
     * Emits one chunk's worth of writes at a time.
     *
     * <p>{@link EditQueue} requires writes grouped by chunk and never revisited, which this satisfies by
     * construction - it walks the plan's chunk list in order and fully drains each chunk before moving
     * on. Building a chunk costs about a millisecond and happens roughly once per tick at the default
     * per-tick budget, which is a far better trade than holding 30 million records in memory.</p>
     */
    private static final class ChunkAtATime implements Iterator<EditQueue.Write> {

        private final ApronPlan plan;
        private final long[] chunks;
        private final BlockData[] surface;
        private final BlockData[] soil;
        private final BlockData water;
        private final BlockData air;
        private final BlockData[] deep;
        private final BlockData log;
        private final BlockData leaves;
        private final BlockData shortGrass;
        private final BlockData[] flowers;
        private final boolean[] grassy;

        private int chunkIndex;
        private List<EditQueue.Write> batch = List.of();
        private int cursor;

        ChunkAtATime(ApronPlan plan) {
            this.plan = plan;
            this.chunks = plan.chunks();
            String[] names = plan.palette();
            this.surface = new BlockData[names.length];
            this.soil = new BlockData[names.length];
            this.grassy = new boolean[names.length];
            for (int i = 0; i < names.length; i++) {
                surface[i] = create(names[i], Material.GRASS_BLOCK);
                soil[i] = create(soilUnder(names[i]), Material.DIRT);
                grassy[i] = names[i].equals("minecraft:grass_block") || names[i].equals("minecraft:podzol");
            }
            this.water = create("minecraft:water", Material.WATER);
            this.air = create("minecraft:air", Material.AIR);
            // A speckled interior, so a cutaway through the new rock does not read as one solid colour.
            this.deep = new BlockData[]{
                    create("minecraft:stone", Material.STONE),
                    create("minecraft:stone", Material.STONE),
                    create("minecraft:stone", Material.STONE),
                    create("minecraft:stone", Material.STONE),
                    create("minecraft:stone", Material.STONE),
                    create("minecraft:andesite", Material.STONE),
                    create("minecraft:gravel", Material.STONE),
                    create("minecraft:diorite", Material.STONE)};
            this.log = create("minecraft:oak_log", Material.OAK_LOG);
            // persistent=true or the canopy decays the moment a player walks into loading range.
            this.leaves = create("minecraft:oak_leaves[persistent=true,distance=1]", Material.OAK_LEAVES);
            this.shortGrass = create("minecraft:short_grass", Material.SHORT_GRASS);
            this.flowers = new BlockData[]{
                    create("minecraft:dandelion", Material.DANDELION),
                    create("minecraft:poppy", Material.POPPY),
                    create("minecraft:cornflower", Material.CORNFLOWER),
                    create("minecraft:oxeye_daisy", Material.OXEYE_DAISY),
                    create("minecraft:azure_bluet", Material.AZURE_BLUET)};
        }

        private static BlockData create(String state, Material fallback) {
            try {
                return Bukkit.createBlockData(state);
            } catch (IllegalArgumentException ex) {
                return Bukkit.createBlockData(fallback);
            }
        }

        /** What goes directly beneath a given surface block. */
        private static String soilUnder(String surfaceName) {
            return switch (surfaceName) {
                case "minecraft:sand" -> "minecraft:sandstone";
                case "minecraft:red_sand" -> "minecraft:red_sandstone";
                case "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium",
                     "minecraft:coarse_dirt", "minecraft:rooted_dirt" -> "minecraft:dirt";
                case "minecraft:dirt" -> "minecraft:dirt";
                case "minecraft:gravel", "minecraft:clay", "minecraft:mud" -> "minecraft:stone";
                default -> "minecraft:stone";
            };
        }

        @Override
        public boolean hasNext() {
            while (cursor >= batch.size()) {
                if (chunkIndex >= chunks.length) {
                    return false;
                }
                long packed = chunks[chunkIndex++];
                batch = build(ApronPlan.chunkX(packed), ApronPlan.chunkZ(packed));
                cursor = 0;
            }
            return true;
        }

        @Override
        public EditQueue.Write next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return batch.get(cursor++);
        }

        /**
         * Every write that lands inside one chunk: clears first, then terrain, then vegetation.
         *
         * <p>Order matters and is relied on. A belt column is cleared above its new surface before the
         * water is poured into it, so the two overlap harmlessly in the right direction; reversed, the
         * clear would delete the water it had just placed.</p>
         */
        private List<EditQueue.Write> build(int cx, int cz) {
            int fromX = cx << 4;
            int fromZ = cz << 4;
            List<EditQueue.Write> out = new ArrayList<>(8192);

            for (int x = fromX; x < fromX + 16; x++) {
                for (int z = fromZ; z < fromZ + 16; z++) {
                    int i = plan.indexOf(x, z);
                    if (i < 0) {
                        continue;
                    }
                    int existTop = plan.existingTopAt(i);
                    int existBottom = plan.existingBottomAt(i);
                    boolean occupied = !ApronPlan.nothingThere(existTop);

                    if (plan.isClear(i)) {
                        // Past the coastline, in a chunk the trim tool has to keep. Take it all out.
                        for (int y = existTop; y >= existBottom; y--) {
                            out.add(new EditQueue.Write(x, y, z, air));
                        }
                        continue;
                    }
                    if (!plan.isBelt(i)) {
                        continue;
                    }
                    int y = plan.surfaceYAt(i);
                    int mat = plan.materialAt(i);
                    int rock = plan.keepAt(i);

                    if (occupied) {
                        for (int cy = existTop; cy > y; cy--) {
                            out.add(new EditQueue.Write(x, cy, z, air));
                        }
                        for (int cy = y - rock - 1; cy >= existBottom; cy--) {
                            out.add(new EditQueue.Write(x, cy, z, air));
                        }
                    }

                    out.add(new EditQueue.Write(x, y, z, surface[mat]));
                    int soilTo = Math.max(y - rock, y - SOIL);
                    for (int sy = y - 1; sy >= soilTo; sy--) {
                        out.add(new EditQueue.Write(x, sy, z, soil[mat]));
                    }
                    for (int dy = soilTo - 1; dy >= y - rock; dy--) {
                        out.add(new EditQueue.Write(x, dy, z,
                                deep[(int) (ApronPlan.hash(x, dy * 31 + z, 23) & 7)]));
                    }
                    if (plan.waterAt(i)) {
                        for (int wy = y + 1; wy <= plan.waterY(); wy++) {
                            out.add(new EditQueue.Write(x, wy, z, water));
                        }
                    } else if (grassy[mat] && ApronPlan.hasPlant(x, z)) {
                        out.add(new EditQueue.Write(x, y + 1, z, ApronPlan.isFlower(x, z)
                                ? flowers[(int) (ApronPlan.hash(x, z, 19) % flowers.length)]
                                : shortGrass));
                    }
                }
            }
            addTrees(out, cx, cz, fromX, fromZ);
            return out;
        }

        /**
         * Trees for this chunk, clipped to it.
         *
         * <p>A canopy is 5 blocks wide, so it can hang over into the next chunk - and writing there
         * would break the queue's one-visit-per-chunk rule. Instead every chunk also looks at the trees
         * rooted in its eight neighbours and keeps only the blocks that fall inside its own 16x16. Each
         * tree therefore gets written in pieces, by whichever chunk owns each piece.</p>
         */
        private void addTrees(List<EditQueue.Write> out, int cx, int cz, int fromX, int fromZ) {
            int toX = fromX + 15;
            int toZ = fromZ + 15;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int[] tree : plan.treesIn(cx + dx, cz + dz)) {
                        int tx = tree[0];
                        int tz = tree[1];
                        int trunk = tree[2];
                        int baseY = tree[3];
                        for (int h = 0; h < trunk; h++) {
                            if (tx >= fromX && tx <= toX && tz >= fromZ && tz <= toZ) {
                                out.add(new EditQueue.Write(tx, baseY + h, tz, log));
                            }
                        }
                        int top = baseY + trunk - 1;
                        for (int[] offset : ApronPlan.CANOPY) {
                            int lx = tx + offset[0];
                            int lz = tz + offset[2];
                            if (lx < fromX || lx > toX || lz < fromZ || lz > toZ) {
                                continue;
                            }
                            out.add(new EditQueue.Write(lx, top + offset[1], lz, leaves));
                        }
                    }
                }
            }
        }
    }
}
