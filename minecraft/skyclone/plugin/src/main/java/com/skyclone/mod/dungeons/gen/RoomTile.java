package com.skyclone.mod.dungeons.gen;

/**
 * A room template ("tile"). PURE (no Bukkit): it runs off-thread while a run is generated, and in the preview tool.
 * Register more with {@link Tiles#register} (agent B: rare rooms, boss-floor variants).
 *
 * <p>When {@link #build} runs, the room's shell already exists (floor at {@link Geo#FLOOR_Y}, walls, ceiling, door
 * openings). A tile decorates the interior and fills {@code ctx.info}: mob spots, secrets, crypts, weak walls and named
 * points (puzzles and special rooms read their points at runtime by name).
 */
public interface RoomTile {

    /** Unique id, lower_snake. */
    String id();

    /** Room name shown on entry ("Ossuary"). */
    String name();

    DungeonPlan.RoomType type();

    /** Whether this tile can build this room (shape, puzzle id, floor). */
    default boolean fits(DungeonPlan.Room room, com.skyclone.mod.dungeons.model.FloorDef floor) { return true; }

    /** Relative pick weight among the tiles that fit. */
    default int weight() { return 10; }

    void build(RoomCtx ctx);
}
