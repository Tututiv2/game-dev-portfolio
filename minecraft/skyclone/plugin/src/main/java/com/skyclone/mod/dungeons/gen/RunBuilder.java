package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Connect;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.gen.DungeonPlan.Door;
import com.skyclone.mod.dungeons.gen.DungeonPlan.DoorKind;
import com.skyclone.mod.dungeons.gen.DungeonPlan.Room;
import com.skyclone.mod.dungeons.gen.RunBlueprint.RoomInfo;
import com.skyclone.mod.dungeons.model.FloorDef;

import java.util.Iterator;
import java.util.Random;

/**
 * Plan -> blocks. Additive first (floors, walls, ceilings), then carving (door openings), then the locked doors, then
 * every room's tile, then the boss arena. Pure: runs off-thread at run start and in the preview tool.
 */
public final class RunBuilder {

    private RunBuilder() {}

    public static RunBlueprint build(DungeonPlan plan, FloorDef floor, BossArena arena) {
        long t0 = System.nanoTime();
        Theme theme = Theme.of(floor.theme);
        RunBlueprint bp = new RunBlueprint(plan, floor, theme);
        VoxelCanvas c = bp.canvas;
        Random r = new Random(plan.seed ^ 0x5DEECE66DL);

        // 1. shells
        for (Room room : plan.rooms) shell(c, plan, room, theme);

        // 2. door openings through both wall layers, then locked doors filled
        for (int i = 0; i < plan.doors.size(); i++) {
            Door d = plan.doors.get(i);
            Box o = Geo.doorBox(d);
            c.fill(o, "air");
            // a frame of trim around the opening on both faces
            frame(c, d, theme);
            if (d.kind == DoorKind.WITHER) c.fill(o, "coal_block");
            else if (d.kind == DoorKind.BLOOD) c.fill(o, "red_terracotta");
            bp.doors.add(new RunBlueprint.DoorInfo(i, d.kind, o, Geo.doorCenter(d)));
        }

        // 3. tiles
        for (Room room : plan.rooms) {
            RoomInfo info = bp.rooms[room.index];
            for (int[] cell : room.cells) info.interiors.add(Geo.interior(cell[0], cell[1]));
            int[] fc = room.firstCell();
            Box first = Geo.interior(fc[0], fc[1]);
            info.center = new int[]{first.centerX(), Geo.FLOOR_Y + 1, first.centerZ()};
            RoomCtx ctx = new RoomCtx(c, plan, room, info, theme, floor, plan.seed * 31 + room.index * 7919L, doorSide(plan, room));
            for (Door d : plan.doorsOf(room.index)) ctx.doorZones.add(doorZone(d, room));
            RoomTile tile = Tiles.pick(room, floor, r);
            if (tile != null) {
                info.tile = tile.id();
                info.name = tile.name();
                room.tile = tile.id();
                room.name = tile.name();
                try { tile.build(ctx); }
                catch (RuntimeException e) { info.data.put("tile_error", String.valueOf(e)); }
            } else {
                info.name = room.type.label;
            }
            if (!standable(c, info.center)) info.center = nearestStandable(c, ctx, info.center);
            if (!info.points.containsKey("spawn")) info.points.put("spawn", info.center);
            // keep only mob spots that are really standable after decoration
            for (Iterator<int[]> it = info.mobSpots.iterator(); it.hasNext(); ) {
                int[] m = it.next();
                if (!c.isAir(m[0], m[1], m[2]) || !c.isAir(m[0], m[1] + 1, m[2]) || c.isAir(m[0], m[1] - 1, m[2])) it.remove();
            }
        }

        // 4. boss arena
        if (arena != null && !floor.isEntrance()) {
            int ax = Geo.arenaX(plan.w), az = Geo.arenaZ();
            int sx = Math.min(Geo.ARENA_MAX_X, Math.max(16, arena.sizeX()));
            int sz = Math.min(Geo.ARENA_MAX_Z, Math.max(16, arena.sizeZ()));
            bp.arena = new Box(ax, Geo.FLOOR_Y - 30, az, ax + sx - 1, Geo.FLOOR_Y + 60, az + sz - 1);
            bp.arenaId = arena.id();
            BossArena.Points pts = new BossArena.Points();
            try { arena.build(c, ax, Geo.FLOOR_Y, az, plan.seed, floor, pts); }
            catch (RuntimeException e) { bp.arenaPoints.put("error", new int[]{0, 0, 0}); }
            bp.arenaPoints.putAll(pts.map());
            if (!bp.arenaPoints.containsKey("spawn")) bp.arenaPoints.put("spawn", new int[]{ax + sx / 2, Geo.FLOOR_Y + 1, az + 4});
            if (!bp.arenaPoints.containsKey("boss")) bp.arenaPoints.put("boss", new int[]{ax + sx / 2, Geo.FLOOR_Y + 1, az + sz / 2});
            if (!bp.arenaPoints.containsKey("chests")) bp.arenaPoints.put("chests", new int[]{ax + sx / 2, Geo.FLOOR_Y + 1, az + sz / 2});
        }

        Connect.apply(bp.canvas);
        bp.buildMillis = (System.nanoTime() - t0) / 1_000_000;
        return bp;
    }

    static boolean standable(VoxelCanvas c, int[] p) {
        return c.isAir(p[0], p[1], p[2]) && c.isAir(p[0], p[1] + 1, p[2]) && !c.isAir(p[0], p[1] - 1, p[2])
                && !String.valueOf(c.get(p[0], p[1] - 1, p[2])).contains("lava");
    }

    /** The standable floor spot inside the room nearest to {@code p} (spiral search), or p itself. */
    static int[] nearestStandable(VoxelCanvas c, RoomCtx ctx, int[] p) {
        for (int r = 1; r < 30; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int[] q = {p[0] + dx, p[1], p[2] + dz};
                    if (ctx.inside(q[0], q[2]) && standable(c, q)) return q;
                }
        return p;
    }

    /** Floor, walls on the room's own boundary edges, ceiling. */
    static void shell(VoxelCanvas c, DungeonPlan plan, Room room, Theme t) {
        for (int[] cell : room.cells) {
            int gx = cell[0], gz = cell[1];
            int x0 = Geo.cellX(gx), z0 = Geo.cellZ(gz), x1 = x0 + Geo.CELL - 1, z1 = z0 + Geo.CELL - 1;
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    c.set(x, Geo.FLOOR_Y, z, t.floor.at(x, Geo.FLOOR_Y, z));
                    c.set(x, Geo.FLOOR_Y - 1, z, "stone");
                    c.set(x, Geo.CEIL_Y, z, t.ceiling.at(x, Geo.CEIL_Y, z));
                }
            boolean n = !room.has(gx, gz - 1), s = !room.has(gx, gz + 1), w = !room.has(gx - 1, gz), e = !room.has(gx + 1, gz);
            for (int y = Geo.FLOOR_Y + 1; y < Geo.CEIL_Y; y++) {
                boolean band = y == Geo.FLOOR_Y + 1 || y == Geo.CEIL_Y - 1;
                for (int x = x0; x <= x1; x++) {
                    if (n) c.set(x, y, z0, band ? t.trim.at(x, y, z0) : t.wall.at(x, y, z0));
                    if (s) c.set(x, y, z1, band ? t.trim.at(x, y, z1) : t.wall.at(x, y, z1));
                }
                for (int z = z0; z <= z1; z++) {
                    if (w) c.set(x0, y, z, band ? t.trim.at(x0, y, z) : t.wall.at(x0, y, z));
                    if (e) c.set(x1, y, z, band ? t.trim.at(x1, y, z) : t.wall.at(x1, y, z));
                }
            }
            // concave corners of L / 2x2 shapes: close the diagonal gap
            for (int[] dd : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                boolean side1 = room.has(gx + dd[0], gz), side2 = room.has(gx, gz + dd[1]), diag = room.has(gx + dd[0], gz + dd[1]);
                if (side1 && side2 && !diag) {
                    int cx = dd[0] < 0 ? x0 : x1, cz = dd[1] < 0 ? z0 : z1;
                    for (int y = Geo.FLOOR_Y + 1; y < Geo.CEIL_Y; y++) c.set(cx, y, cz, t.wall.at(cx, y, cz));
                }
            }
        }
    }

    private static void frame(VoxelCanvas c, Door d, Theme t) {
        Box o = Geo.doorBox(d);
        int top = Geo.FLOOR_Y + Geo.DOOR_H + 1;
        if (d.dir == 'E') {
            for (int x = o.minX(); x <= o.maxX(); x++) {
                for (int z = o.minZ() - 1; z <= o.maxZ() + 1; z++) c.set(x, top, z, t.accent);
                for (int y = o.minY(); y <= o.maxY(); y++) { c.set(x, y, o.minZ() - 1, t.trim.at(x, y, o.minZ() - 1)); c.set(x, y, o.maxZ() + 1, t.trim.at(x, y, o.maxZ() + 1)); }
            }
        } else {
            for (int z = o.minZ(); z <= o.maxZ(); z++) {
                for (int x = o.minX() - 1; x <= o.maxX() + 1; x++) c.set(x, top, z, t.accent);
                for (int y = o.minY(); y <= o.maxY(); y++) { c.set(o.minX() - 1, y, z, t.trim.at(o.minX() - 1, y, z)); c.set(o.maxX() + 1, y, z, t.trim.at(o.maxX() + 1, y, z)); }
            }
        }
    }

    /** The side ('N','E','S','W') of the first door of a room, seen from inside that room. */
    static char doorSide(DungeonPlan plan, Room room) {
        for (Door d : plan.doorsOf(room.index)) {
            boolean roomIsA = room.has(d.gx, d.gz);
            if (d.dir == 'E') return roomIsA ? 'E' : 'W';
            return roomIsA ? 'S' : 'N';
        }
        return 'N';
    }

    /** A 5 x 5 walkable zone just inside the room in front of a door. */
    static Box doorZone(Door d, Room room) {
        Box o = Geo.doorBox(d);
        boolean roomIsA = room.has(d.gx, d.gz);
        int y0 = Geo.FLOOR_Y + 1, y1 = Geo.FLOOR_Y + 3;
        if (d.dir == 'E') {
            int x = roomIsA ? o.minX() - 1 : o.maxX() + 1;
            int xFar = roomIsA ? x - 4 : x + 4;
            return Box.of(x, y0, o.minZ() - 1, xFar, y1, o.maxZ() + 1);
        }
        int z = roomIsA ? o.minZ() - 1 : o.maxZ() + 1;
        int zFar = roomIsA ? z - 4 : z + 4;
        return Box.of(o.minX() - 1, y0, z, o.maxX() + 1, y1, zFar);
    }
}
