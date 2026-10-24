package com.thestill.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chunk-keyed spatial index over every region.
 *
 * <p>Lookup cost is the number of regions overlapping <em>one chunk</em>, not
 * the number of regions on the server. That distinction is the whole reason
 * this class exists: Shadow Prison asked "which mine is this?" with a linear
 * scan over every mine, and called it from fifteen separate listeners on
 * {@code BlockBreakEvent} — one of them six times per event. With thirty-odd
 * prestige mines that is several hundred box tests for every block a player
 * breaks, and it is why the review's verdict was "fine at 2 players, falls over
 * at 20+".
 *
 * <p>Here, breaking a block is one hash lookup and usually one or two box tests.
 *
 * <p>Thread-safe for concurrent reads with occasional writes: definitions change
 * when an admin defines a region or a player claims a rig, which is rare, while
 * reads happen on every block break and every mob AI tick.
 */
public final class RegionIndex {

    /** world name -> packed chunk key -> regions touching that chunk */
    private final Map<String, Map<Long, List<Region>>> byWorld = new ConcurrentHashMap<>();

    /** id -> region, for parent resolution and direct access */
    private final Map<String, Region> byId = new ConcurrentHashMap<>();

    // ---- mutation -------------------------------------------------------

    /**
     * Adds a region and links it into the nesting chain.
     *
     * @throws IllegalArgumentException if the region is invalid: duplicate id,
     *         missing or cyclic parent, not contained by its parent, overlapping
     *         a sibling, or covering an implausible number of chunks
     */
    public void add(Region region) {
        validate(region);

        byId.put(region.id(), region);
        region.setNesting(computeNesting(region));

        Map<Long, List<Region>> chunks =
                byWorld.computeIfAbsent(region.bounds().world(), w -> new ConcurrentHashMap<>());
        forEachChunk(region.bounds(), key ->
                chunks.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(region));
    }

    public void remove(String id) {
        Region region = byId.remove(id);
        if (region == null) {
            return;
        }
        Map<Long, List<Region>> chunks = byWorld.get(region.bounds().world());
        if (chunks == null) {
            return;
        }
        forEachChunk(region.bounds(), key -> {
            List<Region> list = chunks.get(key);
            if (list != null) {
                list.remove(region);
                if (list.isEmpty()) {
                    chunks.remove(key);
                }
            }
        });
    }

    public void clear() {
        byWorld.clear();
        byId.clear();
    }

    private void validate(Region region) {
        if (byId.containsKey(region.id())) {
            throw new IllegalArgumentException("region '" + region.id() + "' already exists");
        }

        long chunks = region.bounds().chunkCount();
        if (chunks > Cuboid.MAX_CHUNKS) {
            throw new IllegalArgumentException("region '" + region.id() + "' covers " + chunks
                    + " chunks, over the " + Cuboid.MAX_CHUNKS + " limit — check the coordinates");
        }

        Region parent = null;
        if (region.parentId() != null) {
            parent = byId.get(region.parentId());
            if (parent == null) {
                throw new IllegalArgumentException("region '" + region.id() + "' names parent '"
                        + region.parentId() + "', which does not exist");
            }
            if (!parent.bounds().containsFully(region.bounds())) {
                throw new IllegalArgumentException("region '" + region.id()
                        + "' is not fully inside its parent '" + parent.id() + "'");
            }
            if (isAncestorOf(region.id(), parent)) {
                throw new IllegalArgumentException("region '" + region.id()
                        + "' would create a parent cycle");
            }
        }

        // Siblings must not overlap. Allowing it would mean "which region is
        // this block in" has two answers, and every system downstream - heat
        // deposit, Din pressure, the PvP flag, the payout multiplier - would
        // silently pick whichever the iteration order happened to hit first.
        String parentId = region.parentId();
        for (Region existing : byId.values()) {
            if (!java.util.Objects.equals(existing.parentId(), parentId)) {
                continue;
            }
            if (existing.bounds().intersects(region.bounds())) {
                throw new IllegalArgumentException("region '" + region.id() + "' overlaps sibling '"
                        + existing.id() + "'; siblings must be disjoint");
            }
        }
    }

    private boolean isAncestorOf(String candidateId, Region start) {
        Region walker = start;
        int guard = 0;
        while (walker != null && guard++ < 64) {
            if (walker.id().equals(candidateId)) {
                return true;
            }
            walker = walker.parentId() == null ? null : byId.get(walker.parentId());
        }
        return false;
    }

    private int computeNesting(Region region) {
        int depth = 0;
        Region walker = region.parentId() == null ? null : byId.get(region.parentId());
        while (walker != null && depth < 64) {
            depth++;
            walker = walker.parentId() == null ? null : byId.get(walker.parentId());
        }
        return depth;
    }

    private void forEachChunk(Cuboid bounds, java.util.function.LongConsumer action) {
        for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
            for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) {
                action.accept(Cuboid.chunkKey(cx, cz));
            }
        }
    }

    // ---- lookup ---------------------------------------------------------

    /**
     * The most specific region containing this block, or null.
     *
     * <p>"Most specific" means deepest in the nesting chain, so a block inside
     * {@code shaft:d7-north} returns the shaft rather than {@code depth:d7}.
     * Walk {@link #parentOf} to get the level.
     */
    public Region deepestAt(String world, int x, int y, int z) {
        List<Region> candidates = candidates(world, x, z);
        if (candidates == null) {
            return null;
        }
        Region best = null;
        // Indexed loop, no iterator allocation: this runs on every block break
        // and on every mob retarget tick.
        for (int i = 0; i < candidates.size(); i++) {
            Region region = candidates.get(i);
            if (region.contains(x, y, z) && (best == null || region.nesting() > best.nesting())) {
                best = region;
            }
        }
        return best;
    }

    /** Every region containing this block, innermost first. */
    public List<Region> allAt(String world, int x, int y, int z) {
        List<Region> candidates = candidates(world, x, z);
        if (candidates == null) {
            return List.of();
        }
        List<Region> hits = new ArrayList<>(2);
        for (int i = 0; i < candidates.size(); i++) {
            Region region = candidates.get(i);
            if (region.contains(x, y, z)) {
                hits.add(region);
            }
        }
        hits.sort((a, b) -> Integer.compare(b.nesting(), a.nesting()));
        return hits;
    }

    private List<Region> candidates(String world, int x, int z) {
        Map<Long, List<Region>> chunks = byWorld.get(world);
        return chunks == null ? null : chunks.get(Cuboid.chunkKeyOfBlock(x, z));
    }

    /**
     * Every region within {@code chunkRadius} chunks of this block.
     *
     * <p>What mob AI wants: "which shafts are near enough to matter", asked once
     * per retarget tick rather than per mob.
     */
    public Set<Region> near(String world, int blockX, int blockZ, int chunkRadius) {
        Map<Long, List<Region>> chunks = byWorld.get(world);
        if (chunks == null) {
            return Set.of();
        }
        int centreX = blockX >> 4;
        int centreZ = blockZ >> 4;
        Set<Region> found = new HashSet<>();
        for (int cx = centreX - chunkRadius; cx <= centreX + chunkRadius; cx++) {
            for (int cz = centreZ - chunkRadius; cz <= centreZ + chunkRadius; cz++) {
                List<Region> list = chunks.get(Cuboid.chunkKey(cx, cz));
                if (list != null) {
                    found.addAll(list);
                }
            }
        }
        return found;
    }

    public Region byId(String id) {
        return id == null ? null : byId.get(id);
    }

    public Region parentOf(Region region) {
        return region == null || region.parentId() == null ? null : byId.get(region.parentId());
    }

    /** The region and every ancestor, innermost first. */
    public List<Region> chainOf(Region region) {
        List<Region> chain = new ArrayList<>(3);
        Region walker = region;
        int guard = 0;
        while (walker != null && guard++ < 64) {
            chain.add(walker);
            walker = parentOf(walker);
        }
        return chain;
    }

    public List<Region> childrenOf(String parentId) {
        List<Region> children = new ArrayList<>();
        for (Region region : byId.values()) {
            if (parentId.equals(region.parentId())) {
                children.add(region);
            }
        }
        return children;
    }

    public Collection<Region> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /** Diagnostics for {@code /still region stats}: how wide the index actually is. */
    public Map<String, Integer> chunkEntryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        byWorld.forEach((world, chunks) -> counts.put(world, chunks.size()));
        return counts;
    }
}
