package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.gen.DungeonPlan.RoomType;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.puzzle.PuzzleBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * The room-tile registry (an extension point: agent B and later work add tiles with {@link #register}) and the
 * built-in tiles: nine normal room templates that adapt to any shape, the five special rooms, and one tile per
 * puzzle that hands over to {@link PuzzleBuilders}.
 */
public final class Tiles {

    private Tiles() {}

    private static final List<RoomTile> TILES = new CopyOnWriteArrayList<>();

    static { registerBuiltins(); }

    /** Add a tile. A tile with an id already registered replaces it. */
    public static void register(RoomTile t) {
        TILES.removeIf(x -> x.id().equals(t.id()));
        TILES.add(t);
    }

    public static List<RoomTile> all() { return List.copyOf(TILES); }

    public static RoomTile byId(String id) {
        for (RoomTile t : TILES) if (t.id().equals(id)) return t;
        return null;
    }

    /** Weighted pick among the tiles of the room's type that fit it; null if none does. */
    public static RoomTile pick(DungeonPlan.Room room, FloorDef floor, Random r) {
        List<RoomTile> fit = new ArrayList<>();
        int total = 0;
        for (RoomTile t : TILES) {
            if (t.type() != room.type) continue;
            try { if (!t.fits(room, floor)) continue; } catch (RuntimeException e) { continue; }
            fit.add(t);
            total += Math.max(1, t.weight());
        }
        if (fit.isEmpty()) return null;
        int roll = r.nextInt(total);
        for (RoomTile t : fit) {
            roll -= Math.max(1, t.weight());
            if (roll < 0) return t;
        }
        return fit.get(fit.size() - 1);
    }

    // ---- a compact way to declare tiles ----------------------------------------------------

    public static RoomTile tile(String id, String name, RoomType type, int weight, BiPredicate<DungeonPlan.Room, FloorDef> fits, Consumer<RoomCtx> build) {
        return new RoomTile() {
            @Override public String id() { return id; }
            @Override public String name() { return name; }
            @Override public RoomType type() { return type; }
            @Override public int weight() { return weight; }
            @Override public boolean fits(DungeonPlan.Room room, FloorDef floor) { return fits == null || fits.test(room, floor); }
            @Override public void build(RoomCtx ctx) { build.accept(ctx); }
        };
    }

    private static int mobCount(RoomCtx ctx) {
        return Math.max(2, ctx.room.cells.size() * ctx.floor.mobsPerCell + ctx.rng.nextInt(2));
    }

    private static void secrets(RoomCtx ctx, int perCellMin, int perCellMax) {
        int n = 0;
        for (int i = 0; i < ctx.room.cells.size(); i++) n += perCellMin + ctx.rng.nextInt(Math.max(1, perCellMax - perCellMin + 1));
        for (int i = 0; i < n; i++) ctx.randomSecret();
    }

    private static void registerBuiltins() {
        // ------------------------------------------------------------------ normal rooms
        register(tile("ossuary", "Ossuary", RoomType.NORMAL, 12, null, ctx -> {
            ctx.lights(10);
            for (Box b : ctx.cells()) {
                int crypts = 1 + ctx.rng.nextInt(2);
                for (int i = 0; i < crypts * 4 && crypts > 0; i++) {
                    int[] p = ctx.randomFree(4);
                    if (p == null) break;
                    if (ctx.crypt(p[0], p[1], ctx.rng.nextBoolean() ? 'x' : 'z')) crypts--;
                }
                for (int i = 0; i < 6; i++) {
                    int[] p = ctx.randomWallSpot();
                    if (p == null) break;
                    ctx.c.set(p[0], ctx.fy + 1, p[1], ctx.rng.nextInt(3) == 0 ? "bone_block[axis=y]" : "candle[candles=" + (1 + ctx.rng.nextInt(4)) + ",lit=true,waterlogged=false]");
                }
                if (ctx.rng.nextBoolean()) { int[] p = ctx.randomFree(5); if (p != null) ctx.essenceSecret(p[0], p[1]); }
            }
            ctx.cobwebs(6 * ctx.room.cells.size());
            secrets(ctx, 1, 2);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("colonnade", "Colonnade", RoomType.NORMAL, 12, null, ctx -> {
            for (Box b : ctx.cells())
                for (int[] o : new int[][]{{7, 7}, {7, 21}, {21, 7}, {21, 21}}) {
                    int x = b.minX() + o[0] - 1, z = b.minZ() + o[1] - 1;
                    boolean ok = true;
                    for (int dx = -1; dx <= 2 && ok; dx++) for (int dz = -1; dz <= 2 && ok; dz++) if (!ctx.free(x + dx, z + dz)) ok = false;
                    if (ok) ctx.bigPillar(x, z);
                }
            for (Box b : ctx.cells()) {
                int mz = b.centerZ();
                for (int x = b.minX() + 2; x <= b.maxX() - 2; x++)
                    for (int dz = -1; dz <= 1; dz++)
                        if (ctx.free(x, mz + dz)) ctx.c.set(x, ctx.fy + 1, mz + dz, ctx.theme.carpet);
            }
            ctx.lights(9);
            if (ctx.rng.nextInt(10) < 6) ctx.weakWallAlcove();
            ctx.cobwebs(3 * ctx.room.cells.size());
            secrets(ctx, 1, 2);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("archive", "Archive", RoomType.NORMAL, 10, null, ctx -> {
            for (Box b : ctx.cells())
                for (int x = b.minX(); x <= b.maxX(); x++)
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        if (!ctx.againstWall(x, z) || !ctx.free(x, z) || ctx.rng.nextInt(10) < 3) continue;
                        int hgt = 2 + ctx.rng.nextInt(3);
                        for (int y = 1; y <= hgt; y++) ctx.c.set(x, ctx.fy + y, z, ctx.rng.nextInt(9) == 0 ? "chiseled_bookshelf[facing=north]" : "bookshelf");
                    }
            for (Box b : ctx.cells())
                for (int i = 0; i < 3; i++) {
                    int[] p = ctx.randomFree(6);
                    if (p == null) break;
                    boolean ok = ctx.free(p[0] + 1, p[1]) && ctx.free(p[0], p[1] + 1) && ctx.free(p[0] + 1, p[1] + 1);
                    if (!ok) continue;
                    for (int dx = 0; dx < 2; dx++)
                        for (int dz = 0; dz < 2; dz++) {
                            ctx.c.set(p[0] + dx, ctx.fy + 1, p[1] + dz, "spruce_fence");
                            ctx.c.set(p[0] + dx, ctx.fy + 2, p[1] + dz, "spruce_slab[type=bottom,waterlogged=false]");
                        }
                    ctx.c.set(p[0], ctx.fy + 3, p[1], "candle[candles=2,lit=true,waterlogged=false]");
                }
            for (int i = 0; i < ctx.room.cells.size(); i++) { int[] p = ctx.randomFree(5); if (p != null) ctx.c.set(p[0], ctx.fy + 1, p[1], "lectern[facing=" + Blocks.DIRS[ctx.rng.nextInt(4)] + ",has_book=false,powered=false]"); }
            ctx.lights(10);
            ctx.cobwebs(4 * ctx.room.cells.size());
            { int[] p = ctx.randomWallSpot(); if (p != null) ctx.leverSecret(p[0], p[1]); }
            secrets(ctx, 1, 1);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("collapsed_hall", "Collapsed Hall", RoomType.NORMAL, 10, null, ctx -> {
            for (Box b : ctx.cells())
                for (int i = 0; i < 5; i++) {
                    int[] p = ctx.randomFree(3);
                    if (p == null) break;
                    int rad = 1 + ctx.rng.nextInt(3);
                    for (int dx = -rad; dx <= rad; dx++)
                        for (int dz = -rad; dz <= rad; dz++) {
                            int x = p[0] + dx, z = p[1] + dz;
                            if (!ctx.free(x, z)) continue;
                            int hgt = rad + 1 - Math.max(Math.abs(dx), Math.abs(dz));
                            if (hgt <= 0) continue;
                            for (int y = 1; y <= Math.min(3, hgt); y++) ctx.c.set(x, ctx.fy + y, z, ctx.theme.rubble.at(x, ctx.fy + y, z));
                        }
                }
            for (int i = 0; i < 2 * ctx.room.cells.size(); i++) {
                int[] p = ctx.randomFree(4);
                if (p == null) break;
                boolean xAxis = ctx.rng.nextBoolean();
                int len = 4 + ctx.rng.nextInt(4);
                for (int k = 0; k < len; k++) {
                    int x = p[0] + (xAxis ? k : 0), z = p[1] + (xAxis ? 0 : k);
                    if (!ctx.free(x, z)) break;
                    ctx.c.set(x, ctx.fy + 1, z, Blocks.log(ctx.theme.id.equals("wither") ? "polished_basalt" : "stripped_spruce_log", xAxis ? 'x' : 'z'));
                }
            }
            for (Box b : ctx.cells())
                for (int i = 0; i < 6; i++) {
                    int x = b.minX() + ctx.rng.nextInt(30), z = b.minZ() + ctx.rng.nextInt(30);
                    if (ctx.inside(x, z) && ctx.c.isAir(x, Geo.CEIL_Y - 1, z)) ctx.c.set(x, Geo.CEIL_Y - 1, z, "hanging_roots[waterlogged=false]");
                }
            ctx.lights(12);
            ctx.cobwebs(8 * ctx.room.cells.size());
            { int[] p = ctx.randomFree(3); if (p != null) ctx.batSecret(p[0], p[1]); }
            secrets(ctx, 1, 2);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("cellblock", "Cellblock", RoomType.NORMAL, 9, null, ctx -> {
            boolean placedChest = false;
            for (Box b : ctx.cells()) {
                // a row of three 4x4 cells along the cell's north or south wall
                boolean north = ctx.rng.nextBoolean();
                int zWall = north ? b.minZ() : b.maxZ();
                int dz = north ? 1 : -1;
                int x0 = b.minX() + 4 + ctx.rng.nextInt(4);
                for (int k = 0; k < 3; k++) {
                    int cx0 = x0 + k * 5;
                    boolean ok = true;
                    for (int x = cx0; x <= cx0 + 4 && ok; x++) for (int d = 0; d <= 4 && ok; d++) if (!ctx.free(x, zWall + dz * d)) ok = false;
                    if (!ok || !ctx.inside(cx0, zWall) || !ctx.inside(cx0 + 4, zWall)) continue;
                    for (int x = cx0; x <= cx0 + 4; x++)
                        for (int d = 0; d <= 4; d++) {
                            boolean side = x == cx0 || x == cx0 + 4, front = d == 4;
                            if (!side && !front) continue;
                            boolean gap = front && x == cx0 + 2;
                            for (int y = 1; y <= 3; y++) if (!gap || y == 3) ctx.c.set(x, ctx.fy + y, zWall + dz * d, side && d == 0 ? ctx.theme.wall.at(x, ctx.fy + y, zWall) : "iron_bars");
                        }
                    ctx.c.set(cx0 + 2, Geo.CEIL_Y - 1, zWall + dz * 2, "iron_chain[axis=y]");
                    ctx.c.set(cx0 + 2, Geo.CEIL_Y - 2, zWall + dz * 2, "iron_chain[axis=y]");
                    if (!placedChest && ctx.rng.nextBoolean()) placedChest = ctx.chestSecret(cx0 + 1, zWall);
                    else if (ctx.c.isAir(cx0 + 3, ctx.fy + 1, zWall)) ctx.c.set(cx0 + 3, ctx.fy + 1, zWall, "skeleton_skull[rotation=" + ctx.rng.nextInt(16) + "]");
                }
            }
            ctx.lights(10);
            ctx.cobwebs(4 * ctx.room.cells.size());
            { int[] p = ctx.randomWallSpot(); if (p != null) ctx.leverSecret(p[0], p[1]); }
            secrets(ctx, 0, 2);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("root_garden", "Root Garden", RoomType.NORMAL, 9, null, ctx -> {
            for (Box b : ctx.cells()) {
                // a small pool (water at floor level, stone underneath)
                int[] p = ctx.randomFree(7);
                if (p != null) {
                    boolean ok = true;
                    for (int dx = -2; dx <= 2 && ok; dx++) for (int dz = -2; dz <= 2 && ok; dz++) if (!ctx.free(p[0] + dx, p[1] + dz)) ok = false;
                    if (ok) {
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dz = -1; dz <= 1; dz++) {
                                ctx.c.set(p[0] + dx, ctx.fy - 1, p[1] + dz, "stone");
                                ctx.c.set(p[0] + dx, ctx.fy, p[1] + dz, "water[level=0]");
                            }
                        for (int dx = -2; dx <= 2; dx++)
                            for (int dz = -2; dz <= 2; dz++)
                                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) ctx.c.set(p[0] + dx, ctx.fy + 1, p[1] + dz, "moss_carpet");
                    }
                }
                for (int x = b.minX(); x <= b.maxX(); x++)
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        if (!ctx.free(x, z)) continue;
                        int roll = ctx.rng.nextInt(100);
                        if (roll < 18) ctx.c.set(x, ctx.fy + 1, z, "moss_carpet");
                        else if (roll < 20 && !ctx.nearDoor(x, z)) ctx.c.set(x, ctx.fy + 1, z, ctx.rng.nextBoolean() ? "azalea" : "flowering_azalea");
                        if (ctx.rng.nextInt(100) < 6 && ctx.c.isAir(x, Geo.CEIL_Y - 1, z)) ctx.c.set(x, Geo.CEIL_Y - 1, z, "hanging_roots[waterlogged=false]");
                    }
            }
            ctx.lights(11);
            { int[] p = ctx.randomFree(3); if (p != null) ctx.batSecret(p[0], p[1]); }
            { int[] p = ctx.randomFree(5); if (p != null) ctx.essenceSecret(p[0], p[1]); }
            secrets(ctx, 0, 1);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("chapel", "Chapel", RoomType.NORMAL, 9, null, ctx -> {
            for (Box b : ctx.cells()) {
                int cx = b.centerX();
                for (int row = 0; row < 5; row++) {
                    int z = b.minZ() + 6 + row * 4;
                    for (int x = cx - 6; x <= cx + 6; x++) {
                        if (Math.abs(x - cx) <= 1) continue;
                        if (ctx.free(x, z)) ctx.c.set(x, ctx.fy + 1, z, Blocks.stairs("spruce", "north"));
                    }
                }
                int az = b.maxZ() - 2;
                if (ctx.free(cx, az) && ctx.free(cx - 1, az) && ctx.free(cx + 1, az)) {
                    for (int x = cx - 1; x <= cx + 1; x++) {
                        ctx.c.set(x, ctx.fy + 1, az, ctx.theme.accent);
                        ctx.c.set(x, ctx.fy + 2, az, "candle[candles=" + (2 + ctx.rng.nextInt(3)) + ",lit=true,waterlogged=false]");
                    }
                }
                if (ctx.rng.nextBoolean()) { int[] p = ctx.randomFree(4); if (p != null) ctx.crypt(p[0], p[1], 'x'); }
            }
            ctx.lights(10);
            ctx.cobwebs(3 * ctx.room.cells.size());
            { int[] p = ctx.randomFree(4); if (p != null) ctx.essenceSecret(p[0], p[1]); }
            secrets(ctx, 1, 1);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("old_dig", "Old Dig", RoomType.NORMAL, 8, null, ctx -> {
            for (Box b : ctx.cells())
                for (int k = 0; k < 3; k++) {
                    int z = b.minZ() + 5 + k * 10;
                    int xl = b.minX() + 2, xr = b.maxX() - 2;
                    if (!ctx.free(xl, z) || !ctx.free(xr, z)) continue;
                    for (int y = 1; y <= 5; y++) { ctx.c.set(xl, ctx.fy + y, z, "stripped_oak_log[axis=y]"); ctx.c.set(xr, ctx.fy + y, z, "stripped_oak_log[axis=y]"); }
                    for (int x = xl; x <= xr; x++) ctx.c.set(x, ctx.fy + 6, z, "stripped_oak_log[axis=x]");
                }
            for (Box b : ctx.cells()) {
                int x = b.centerX();
                for (int z = b.minZ(); z <= b.maxZ(); z++) if (ctx.free(x, z)) ctx.c.set(x, ctx.fy + 1, z, "rail[shape=north_south,waterlogged=false]");
                Palette ores = Palette.of("coal_ore:4,iron_ore:2,gold_ore:1,redstone_ore[lit=false]:1");
                for (int i = 0; i < 10; i++) {
                    int[] p = ctx.randomWallSpot();
                    if (p == null) break;
                    String side = ctx.wallSide(p[0], p[1]);
                    if (side == null) continue;
                    int wx = p[0] + Blocks.dx(side), wz = p[1] + Blocks.dz(side);
                    int y = ctx.fy + 3 + ctx.rng.nextInt(5);
                    ctx.c.set(wx, y, wz, ores.at(wx, y, wz));
                }
            }
            ctx.lights(10);
            ctx.cobwebs(6 * ctx.room.cells.size());
            { int[] p = ctx.randomFree(3); if (p != null) ctx.batSecret(p[0], p[1]); }
            secrets(ctx, 1, 1);
            ctx.mobs(mobCount(ctx));
        }));

        register(tile("ember_rift", "Ember Rift", RoomType.NORMAL, 8, (room, f) -> f.number >= 4 || "deep".equals(f.theme) || "wither".equals(f.theme), ctx -> {
            for (Box b : ctx.cells()) {
                int cx = b.centerX(), cz = b.centerZ();
                boolean ok = true;
                for (int dx = -5; dx <= 5 && ok; dx++) for (int dz = -5; dz <= 5 && ok; dz++) if (!ctx.free(cx + dx, cz + dz)) ok = false;
                if (!ok) continue;
                for (int dx = -4; dx <= 4; dx++)
                    for (int dz = -4; dz <= 4; dz++) {
                        if (Math.abs(dx) <= 1 || Math.abs(dz) <= 1) continue;       // a cross-shaped bridge stays
                        ctx.c.set(cx + dx, ctx.fy - 1, cz + dz, "magma_block");
                        ctx.c.set(cx + dx, ctx.fy, cz + dz, "lava[level=0]");
                    }
                for (int dx = -5; dx <= 5; dx++)
                    for (int dz = -5; dz <= 5; dz++)
                        if ((Math.abs(dx) == 5 || Math.abs(dz) == 5) && (Math.abs(dx) > 1 && Math.abs(dz) > 1))
                            ctx.c.set(cx + dx, ctx.fy + 1, cz + dz, ctx.theme.slab + "[type=bottom,waterlogged=false]");
            }
            ctx.lights(10);
            secrets(ctx, 1, 2);
            ctx.mobs(mobCount(ctx));
        }));

        // ------------------------------------------------------------------ special rooms
        register(tile("entrance_hall", "Entrance Hall", RoomType.START, 10, null, ctx -> {
            Box b = ctx.cells().get(0);
            int cx = b.centerX(), cz = b.centerZ();
            for (int x = b.minX() + 3; x <= b.maxX() - 3; x++)
                for (int z = cz - 2; z <= cz + 2; z++) if (ctx.free(x, z)) ctx.c.set(x, ctx.fy + 1, z, "green_carpet");
            for (int z = b.minZ() + 3; z <= b.maxZ() - 3; z++)
                for (int x = cx - 2; x <= cx + 2; x++) if (ctx.free(x, z)) ctx.c.set(x, ctx.fy + 1, z, "green_carpet");
            for (int[] o : new int[][]{{6, 6}, {6, 23}, {23, 6}, {23, 23}}) {
                int x = b.minX() + o[0], z = b.minZ() + o[1];
                if (ctx.free(x, z)) {
                    ctx.pillar(x, z);
                    ctx.c.set(x + 1, ctx.fy + 4, z, "green_wall_banner[facing=east]");
                }
            }
            ctx.lights(8);
            ctx.info.points.put("spawn", new int[]{cx, ctx.fy + 1, cz});
            ctx.info.points.put("mort", new int[]{cx + 3, ctx.fy + 1, cz + 3});
        }));

        register(tile("fairy_grove", "Fairy Grove", RoomType.FAIRY, 10, null, ctx -> {
            Box b = ctx.cells().get(0);
            int cx = b.centerX(), cz = b.centerZ();
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    if (!ctx.free(x, z)) continue;
                    int roll = ctx.rng.nextInt(100);
                    if (roll < 35) ctx.c.set(x, ctx.fy + 1, z, "pink_petals[facing=" + Blocks.DIRS[ctx.rng.nextInt(4)] + ",flower_amount=" + (1 + ctx.rng.nextInt(4)) + "]");
                    else if (roll < 40) ctx.c.set(x, ctx.fy + 1, z, ctx.rng.nextBoolean() ? "allium" : "pink_tulip");
                }
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    int x = cx + dx, z = cz + dz;
                    boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    // wave-4 endgame playtest: the rim used to stand ON the floor (fy+1) round a pool sunk INTO it (fy),
                    // so a player who stepped in stood two blocks below the rim top and could not jump or swim out (the
                    // Fairy heals on entry, so walking up to her is the natural move). The rim is now set into the floor:
                    // the pool is a one-block step down, out with a normal jump.
                    ctx.c.set(x, ctx.fy + 1, z, "air");
                    if (rim) ctx.c.set(x, ctx.fy, z, "pink_terracotta");
                    else { ctx.c.set(x, ctx.fy - 1, z, "stone"); ctx.c.set(x, ctx.fy, z, "water[level=0]"); }
                }
            ctx.c.set(cx, ctx.fy + 1, cz, "quartz_pillar[axis=y]");
            ctx.c.set(cx, ctx.fy + 2, cz, "quartz_pillar[axis=y]");
            ctx.c.set(cx, ctx.fy + 3, cz, "pink_stained_glass");
            ctx.lights(9);
            ctx.info.points.put("fairy", new int[]{cx, ctx.fy + 4, cz});
            ctx.info.points.put("spawn", new int[]{cx + 4, ctx.fy + 1, cz});
        }));

        register(tile("blood_chamber", "Blood Chamber", RoomType.BLOOD, 10, null, ctx -> {
            for (Box b : ctx.cells())
                for (int x = b.minX(); x <= b.maxX(); x++)
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        if (!ctx.inside(x, z)) continue;
                        if ((x + z) % 7 == 0) ctx.c.set(x, ctx.fy, z, "red_terracotta");
                        else if (ctx.rng.nextInt(9) == 0) ctx.c.set(x, ctx.fy, z, "nether_wart_block");
                    }
            // dais at the far wall (frame coordinates)
            for (int u = 9; u <= 20; u++)
                for (int v = 23; v <= 28; v++) {
                    ctx.fset(u, 1, v, "red_nether_bricks");
                    if (v >= 25) ctx.fset(u, 2, v, "red_nether_bricks");
                }
            for (int u = 9; u <= 20; u++) ctx.fset(u, 2, 24, Blocks.stairs("red_nether_brick", ctx.away()));
            // the portal to the boss (frame stands mid-room, facing the door)
            for (int u = 11; u <= 17; u++)
                for (int h = 1; h <= 7; h++) {
                    boolean edge = u == 11 || u == 17 || h == 7;
                    ctx.fset(u, h, 15, edge ? "obsidian" : "iron_bars");
                }
            ctx.fbox("boss_portal", 12, 1, 14, 16, 6, 16);
            ctx.fpoint("portal", 14, 1, 13);
            ctx.fpoint("watcher", 14, 8, 26);
            ctx.fpoint("blood.spawn", 14, 3, 25);
            ctx.fpoint("spawn", 14, 1, 4);
            for (int u : new int[]{3, 26}) for (int v : new int[]{6, 22}) { ctx.fset(u, 1, v, "nether_bricks"); ctx.fset(u, 2, v, "soul_campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]"); }
        }));

        register(tile("trap_gauntlet", "Gauntlet", RoomType.TRAP, 10, null, ctx -> {
            // lava between the door strip and the goal strip, with a winding row of stepping stones
            for (int u = 1; u <= 28; u++)
                for (int v = 5; v <= 24; v++) {
                    ctx.fset(u, -1, v, "magma_block");
                    ctx.fset(u, 0, v, "lava[level=0]");
                }
            // a winding but connected path: each row walks from the previous row's position to a new target
            int cu = 14;
            double phase = ctx.rng.nextDouble() * Math.PI * 2;
            for (int v = 5; v <= 24; v++) {
                int target = 14 + (int) Math.round(9 * Math.sin(v / 3.0 + phase));
                while (cu != target) { ctx.fset(cu, 0, v, ctx.theme.trim.at(ctx.x(cu, v), ctx.fy, ctx.z(cu, v))); cu += Integer.signum(target - cu); }
                ctx.fset(cu, 0, v, ctx.theme.trim.at(ctx.x(cu, v), ctx.fy, ctx.z(cu, v)));
            }
            for (int k = 1; k <= 28; k++) ctx.fset(k, 0, 25, ctx.theme.floor.at(ctx.x(k, 25), ctx.fy, ctx.z(k, 25)));
            ctx.fset(14, 1, 27, "light_weighted_pressure_plate[power=0]");
            ctx.fpoint("trap.end", 14, 1, 27);
            ctx.fpoint("trap.start", 14, 1, 2);
            ctx.fpoint("spawn", 14, 1, 2);
            for (int k = 0; k < 6; k++) {
                int v = 7 + k * 3;
                ctx.fset(0, 2, v, "dispenser[facing=" + ctx.right() + ",triggered=false]");
                ctx.fpoint("dart." + k, 0, 2, v);
            }
            ctx.lights(10);
            int[] p = {ctx.x(26, 28), ctx.z(26, 28)};
            if (!ctx.chestSecret(p[0], p[1])) ctx.randomSecret();
            ctx.randomSecret();
        }));

        register(tile("miniboss_arena", "Arena", RoomType.MINIBOSS, 10, null, ctx -> {
            Box b = ctx.cells().get(0);
            int cx = b.centerX(), cz = b.centerZ();
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    double d = Math.hypot(x - cx, z - cz);
                    if (d < 11 && d >= 10) ctx.c.set(x, ctx.fy, z, ctx.theme.accent);
                    else if (d < 10) ctx.c.set(x, ctx.fy, z, (x + z) % 2 == 0 ? "smooth_stone" : ctx.theme.floor.at(x, ctx.fy, z));
                }
            for (int k = 0; k < 6; k++) {
                double a = Math.PI * 2 * k / 6;
                int x = cx + (int) Math.round(Math.cos(a) * 12), z = cz + (int) Math.round(Math.sin(a) * 12);
                if (ctx.free(x, z)) { ctx.c.set(x, ctx.fy + 1, z, ctx.theme.trim.at(x, ctx.fy + 1, z)); ctx.c.set(x, ctx.fy + 2, z, "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]"); }
            }
            ctx.lights(10);
            ctx.info.points.put("miniboss", new int[]{cx, ctx.fy + 1, cz});
            ctx.randomSecret();
        }));

        // ------------------------------------------------------------------ puzzles (one tile per puzzle)
        for (String id : PuzzleBuilders.IDS) {
            register(tile("puzzle_" + id, PuzzleBuilders.name(id), RoomType.PUZZLE, 10, (room, f) -> id.equals(room.puzzle),
                    ctx -> PuzzleBuilders.build(id, ctx)));
        }
    }
}
