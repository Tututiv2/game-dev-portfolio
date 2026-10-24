package com.thestill.region;

import com.thestill.core.Service;
import com.thestill.state.StateMap;
import com.thestill.store.Database;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The answer to "which shaft is this block in", and the owner of everything
 * recorded about a shaft.
 *
 * <p>Din spawn pressure, room heat, the PvP flag, steam rendering and the Ledger
 * all ask this one question, which is why the build plan puts it in Phase 0:
 * build it once, or retrofit it four times into four systems that each grew
 * their own answer.
 */
public final class RegionService implements Service {

    private final Logger log;
    private final Database database;
    private final RegionIndex index = new RegionIndex();

    /**
     * Last known region per player, keyed on the block they were standing in.
     *
     * <p>A player's region is asked for constantly — the scoreboard, the heat
     * readout, every mob retarget. Caching on block position means the index is
     * only consulted when they actually move a block, and correctness is free
     * because the cache key <em>is</em> the position it was computed from.
     */
    private final Map<UUID, Cached> playerRegions = new ConcurrentHashMap<>();

    public RegionService(Logger log, Database database) {
        this.log = log;
        this.database = database;
    }

    // ---- lifecycle ------------------------------------------------------

    @Override
    public void start() throws Exception {
        List<Loaded> loaded = database.readDuringLifecycle(this::readAll);
        Map<String, StateMap> states = database.readDuringLifecycle(this::readAllState);

        // Parents must be indexed before their children, and the table has no
        // ordering guarantee, so insert in dependency waves rather than trusting
        // the row order.
        List<Loaded> pending = new ArrayList<>(loaded);
        int added = 0;
        int failed = 0;
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                Loaded row = iterator.next();
                if (row.parentId != null && index.byId(row.parentId) == null) {
                    continue; // parent not in yet; try again next wave
                }
                iterator.remove();
                try {
                    StateMap state = states.getOrDefault(row.id, new StateMap());
                    index.add(new Region(row.id, row.type, row.parentId, row.bounds,
                            row.createdAt, state));
                    added++;
                } catch (RuntimeException e) {
                    // One malformed region must never abort the load. Shadow
                    // Prison lost live data exactly this way: a bad row threw out
                    // of the load loop, the partial cache stayed in memory, and
                    // shutdown wrote it back over the good file.
                    failed++;
                    log.severe("skipping region '" + row.id + "': " + e.getMessage());
                }
                progress = true;
            }
        }
        for (Loaded orphan : pending) {
            failed++;
            log.severe("skipping region '" + orphan.id + "': parent '" + orphan.parentId
                    + "' is missing or forms a cycle");
        }

        log.info("regions loaded: " + added + (failed > 0 ? " (" + failed + " skipped)" : ""));
    }

    @Override
    public void stop() throws Exception {
        flushDirty();
        playerRegions.clear();
        index.clear();
    }

    // ---- lookup ---------------------------------------------------------

    public Region deepestAt(World world, int x, int y, int z) {
        return world == null ? null : index.deepestAt(world.getName(), x, y, z);
    }

    public Region deepestAt(Location location) {
        World world = location.getWorld();
        return world == null ? null : index.deepestAt(world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Region at(Block block) {
        return index.deepestAt(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
    }

    /**
     * The player's current region, recomputed only when they change block.
     *
     * <p>Pull-based on purpose: there is no move listener to forget to register
     * and no staleness window, because the cached value carries the coordinates
     * it was derived from.
     */
    public Region regionFor(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        String worldName = world.getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        Cached cached = playerRegions.get(player.getUniqueId());
        if (cached != null && cached.matches(worldName, x, y, z)) {
            return cached.region;
        }
        Region region = index.deepestAt(worldName, x, y, z);
        playerRegions.put(player.getUniqueId(), new Cached(worldName, x, y, z, region));
        return region;
    }

    public void forget(UUID playerId) {
        playerRegions.remove(playerId);
    }

    public RegionIndex index() {
        return index;
    }

    // ---- definition -----------------------------------------------------

    /**
     * Defines a region and persists it.
     *
     * @throws IllegalArgumentException if it is invalid — duplicate id, missing
     *         parent, not inside its parent, or overlapping a sibling
     */
    public Region define(String id, RegionType type, String parentId, Cuboid bounds) {
        Region region = new Region(id, type, parentId, bounds,
                System.currentTimeMillis(), new StateMap());
        index.add(region); // validates; throws before anything is written
        database.submit("region-define:" + id, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO regions
                        (id, type, parent_id, world, min_x, min_y, min_z, max_x, max_y, max_z, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        type=excluded.type, parent_id=excluded.parent_id, world=excluded.world,
                        min_x=excluded.min_x, min_y=excluded.min_y, min_z=excluded.min_z,
                        max_x=excluded.max_x, max_y=excluded.max_y, max_z=excluded.max_z
                    """)) {
                statement.setString(1, region.id());
                statement.setString(2, region.type().id());
                statement.setString(3, region.parentId());
                statement.setString(4, bounds.world());
                statement.setInt(5, bounds.minX());
                statement.setInt(6, bounds.minY());
                statement.setInt(7, bounds.minZ());
                statement.setInt(8, bounds.maxX());
                statement.setInt(9, bounds.maxY());
                statement.setInt(10, bounds.maxZ());
                statement.setLong(11, region.createdAt());
                statement.executeUpdate();
            }
        });
        // Positions changed, so every cached answer about them is suspect.
        playerRegions.clear();
        return region;
    }

    /**
     * Deletes a region.
     *
     * @param cascade delete its children too; without this a region with
     *                children is refused rather than silently orphaning them
     * @return how many regions were removed
     */
    public int delete(String id, boolean cascade) {
        Region region = index.byId(id);
        if (region == null) {
            return 0;
        }
        List<Region> children = index.childrenOf(id);
        if (!children.isEmpty() && !cascade) {
            throw new IllegalStateException("region '" + id + "' has " + children.size()
                    + " child region(s); pass cascade to delete them too");
        }
        int removed = 0;
        for (Region child : children) {
            removed += delete(child.id(), true);
        }
        index.remove(id);
        removed++;
        database.submit("region-delete:" + id, connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM regions WHERE id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM region_state WHERE region_id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
        });
        playerRegions.clear();
        return removed;
    }

    // ---- persistence ----------------------------------------------------

    /** Writes every region whose state changed since the last flush. */
    public int flushDirty() {
        int flushed = 0;
        for (Region region : index.all()) {
            Map<String, String> snapshot = region.state().takeDirtySnapshot();
            if (snapshot == null) {
                continue;
            }
            flushed++;
            database.submit("region-state:" + region.id(), connection -> {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM region_state WHERE region_id = ?")) {
                    delete.setString(1, region.id());
                    delete.executeUpdate();
                }
                if (snapshot.isEmpty()) {
                    return;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO region_state (region_id, key, value) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                        insert.setString(1, region.id());
                        insert.setString(2, entry.getKey());
                        insert.setString(3, entry.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            });
        }
        return flushed;
    }

    private List<Loaded> readAll(java.sql.Connection connection) throws java.sql.SQLException {
        List<Loaded> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, type, parent_id, world, min_x, min_y, min_z, max_x, max_y, max_z, created_at
                FROM regions
                """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                try {
                    RegionType type = RegionType.parse(rs.getString("type"));
                    if (type == null) {
                        log.severe("region '" + id + "' has unknown type '"
                                + rs.getString("type") + "'; skipping");
                        continue;
                    }
                    rows.add(new Loaded(
                            id,
                            type,
                            rs.getString("parent_id"),
                            new Cuboid(rs.getString("world"),
                                    rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                                    rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z")),
                            rs.getLong("created_at")));
                } catch (RuntimeException e) {
                    log.log(Level.SEVERE, "malformed region row '" + id + "'; skipping", e);
                }
            }
        }
        return rows;
    }

    private Map<String, StateMap> readAllState(java.sql.Connection connection)
            throws java.sql.SQLException {
        Map<String, Map<String, String>> raw = new HashMap<>();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT region_id, key, value FROM region_state");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                raw.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                        .put(rs.getString(2), rs.getString(3));
            }
        }
        Map<String, StateMap> states = new HashMap<>(raw.size() * 2);
        raw.forEach((regionId, values) -> {
            StateMap state = new StateMap();
            state.load(values, (key, reason) ->
                    log.warning("region '" + regionId + "' state key '" + key
                            + "' unreadable (" + reason + "); using the default"));
            states.put(regionId, state);
        });
        return states;
    }

    private record Loaded(String id, RegionType type, String parentId,
                          Cuboid bounds, long createdAt) {
    }

    private record Cached(String world, int x, int y, int z, Region region) {

        boolean matches(String world, int x, int y, int z) {
            return this.x == x && this.y == y && this.z == z && this.world.equals(world);
        }
    }
}
