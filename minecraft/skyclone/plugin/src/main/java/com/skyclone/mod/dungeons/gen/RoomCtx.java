package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.mod.dungeons.gen.RunBlueprint.RoomInfo;
import com.skyclone.mod.dungeons.gen.RunBlueprint.Secret;
import com.skyclone.mod.dungeons.gen.RunBlueprint.SecretKind;
import com.skyclone.mod.dungeons.model.FloorDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What a room tile builds with: the canvas, the room (cells, doors), the theme, a seeded Random, and the outputs it
 * fills (mob spots, secrets, crypts, weak walls, named points). The room's shell (floor, walls, ceiling, door openings)
 * is already built when a tile runs; tiles only decorate the interior and must keep the door zones clear.
 *
 * <p>For single-cell rooms {@link #u}/{@link #v} give a FRAME: u runs across the room (0..29), v runs away from the
 * room's first door (0 = just inside the door, 29 = the far wall), so puzzles can be authored once and come out right
 * whichever wall the door is in.
 */
public final class RoomCtx {

    public final VoxelCanvas c;
    public final DungeonPlan plan;
    public final DungeonPlan.Room room;
    public final RoomInfo info;
    public final Theme theme;
    public final FloorDef floor;
    public final Random rng;
    public final long seed;
    /** Boxes (feet-space, inside the room) in front of each door that must stay walkable. */
    public final List<Box> doorZones = new ArrayList<>();
    /** The door side of the first door of this room: 'N', 'E', 'S', 'W'. */
    public final char doorSide;
    public final int fy = Geo.FLOOR_Y;

    public RoomCtx(VoxelCanvas c, DungeonPlan plan, DungeonPlan.Room room, RoomInfo info, Theme theme, FloorDef floor, long seed, char doorSide) {
        this.c = c;
        this.plan = plan;
        this.room = room;
        this.info = info;
        this.theme = theme;
        this.floor = floor;
        this.seed = seed;
        this.rng = new Random(seed);
        this.doorSide = doorSide;
        info.data.put("frame.side", String.valueOf(doorSide));
    }

    // ---- frame (single-cell rooms) ---------------------------------------------------------

    private Frame frame;

    /** The (u, v) frame of the room's first cell, seen from its first door. */
    public Frame frame() {
        if (frame == null) frame = new Frame(Geo.interior(room.firstCell()[0], room.firstCell()[1]), doorSide);
        return frame;
    }

    /** World x of frame point (u, v). */
    public int x(int u, int v) { return frame().x(u, v); }

    /** World z of frame point (u, v). */
    public int z(int u, int v) { return frame().z(u, v); }

    /** Set a block at frame (u, height above the floor block, v): h = 1 is the first block players stand in. */
    public void fset(int u, int h, int v, String block) { c.set(x(u, v), fy + h, z(u, v), block); }

    public String fget(int u, int h, int v) { return c.get(x(u, v), fy + h, z(u, v)); }

    /** Compass direction of increasing v (away from the door). */
    public String away() {
        return switch (doorSide) { case 'N' -> "south"; case 'S' -> "north"; case 'W' -> "east"; default -> "west"; };
    }

    /** Compass direction of decreasing v (toward the door). */
    public String toward() { return Blocks.opposite(away()); }

    /** Compass direction of increasing u. */
    public String right() {
        return switch (doorSide) { case 'N' -> "east"; case 'S' -> "west"; case 'W' -> "north"; default -> "south"; };
    }

    public String left() { return Blocks.opposite(right()); }

    /** Record a named point in frame coordinates, standing at height h (1 = on the floor). */
    public int[] fpoint(String name, int u, int h, int v) {
        int[] p = {x(u, v), fy + h, z(u, v)};
        info.points.put(name, p);
        return p;
    }

    public void fbox(String name, int u0, int h0, int v0, int u1, int h1, int v1) {
        info.boxes.put(name, Box.of(x(u0, v0), fy + h0, z(u0, v0), x(u1, v1), fy + h1, z(u1, v1)));
    }

    // ---- multi-cell helpers ----------------------------------------------------------------

    /** Interiors of every cell of the room. */
    public List<Box> cells() {
        List<Box> out = new ArrayList<>();
        for (int[] cell : room.cells) out.add(Geo.interior(cell[0], cell[1]));
        return out;
    }

    /** True when (x, z) is walkable interior of this room (incl. seams between its cells). */
    public boolean inside(int x, int z) {
        int gx = Geo.toCell(x), gz = Geo.toCell(z);
        if (!room.has(gx, gz)) return false;
        int lx = x - gx * Geo.CELL, lz = z - gz * Geo.CELL;
        boolean wallW = lx == 0 && !room.has(gx - 1, gz);
        boolean wallE = lx == Geo.CELL - 1 && !room.has(gx + 1, gz);
        boolean wallN = lz == 0 && !room.has(gx, gz - 1);
        boolean wallS = lz == Geo.CELL - 1 && !room.has(gx, gz + 1);
        return !(wallW || wallE || wallN || wallS);
    }

    /** True when (x, z) is in front of a door (keep it walkable). */
    public boolean nearDoor(int x, int z) {
        for (Box b : doorZones) if (b.containsXZ(x, z)) return true;
        return false;
    }

    /** Free floor for a decoration: inside, not by a door, and nothing built there yet. */
    public boolean free(int x, int z) {
        if (!inside(x, z) || nearDoor(x, z) || !c.isAir(x, fy + 1, z) || !c.isAir(x, fy + 2, z)) return false;
        String ground = c.get(x, fy, z);
        return ground != null && !ground.contains("lava") && !ground.contains("water") && !c.isAir(x, fy, z);
    }

    /** True when (x, z) touches the room's outer wall (for wall decorations). */
    public boolean againstWall(int x, int z) {
        return inside(x, z) && (!inside(x + 1, z) || !inside(x - 1, z) || !inside(x, z + 1) || !inside(x, z - 1));
    }

    /** The compass side of the wall next to (x, z), or null. Facing for a wall-mounted block is the opposite. */
    public String wallSide(int x, int z) {
        if (!inside(x + 1, z)) return "east";
        if (!inside(x - 1, z)) return "west";
        if (!inside(x, z + 1)) return "south";
        if (!inside(x, z - 1)) return "north";
        return null;
    }

    // ---- outputs ---------------------------------------------------------------------------

    public void mob(int x, int z) { info.mobSpots.add(new int[]{x, fy + 1, z}); }

    public void secret(SecretKind kind, int x, int y, int z) { info.secrets.add(new Secret(kind, x, y, z)); }

    /** A random free floor cell anywhere in the room, or null after 60 tries. */
    public int[] randomFree(int margin) {
        List<Box> cells = cells();
        for (int t = 0; t < 60; t++) {
            Box b = cells.get(rng.nextInt(cells.size()));
            int x = b.minX() + margin + rng.nextInt(Math.max(1, b.maxX() - b.minX() + 1 - 2 * margin));
            int z = b.minZ() + margin + rng.nextInt(Math.max(1, b.maxZ() - b.minZ() + 1 - 2 * margin));
            if (free(x, z)) return new int[]{x, z};
        }
        return null;
    }

    /** A random free floor cell against the outer wall, or null. */
    public int[] randomWallSpot() {
        List<Box> cells = cells();
        for (int t = 0; t < 120; t++) {
            Box b = cells.get(rng.nextInt(cells.size()));
            int x, z;
            int side = rng.nextInt(4);
            int a = rng.nextInt(30);
            switch (side) {
                case 0 -> { x = b.minX(); z = b.minZ() + a; }
                case 1 -> { x = b.maxX(); z = b.minZ() + a; }
                case 2 -> { x = b.minX() + a; z = b.minZ(); }
                default -> { x = b.minX() + a; z = b.maxZ(); }
            }
            if (free(x, z) && againstWall(x, z)) return new int[]{x, z};
        }
        return null;
    }

    // ---- decorations every tile uses -----------------------------------------------------

    /** Standard wall decorations: hanging lanterns on a grid, cobwebs in corners, banners. */
    public void lights(int spacing) {
        for (Box b : cells())
            for (int x = b.minX() + 4; x <= b.maxX() - 3; x += spacing)
                for (int z = b.minZ() + 4; z <= b.maxZ() - 3; z += spacing) {
                    if (!inside(x, z)) continue;
                    int y = Geo.CEIL_Y - 1;
                    c.set(x, y, z, "iron_chain[axis=y]");
                    c.set(x, y - 1, z, theme.lightHanging);
                }
    }

    public void cobwebs(int count) {
        for (int i = 0; i < count; i++) {
            int[] p = randomWallSpot();
            if (p == null) return;
            int y = rng.nextBoolean() ? Geo.CEIL_Y - 1 : fy + 1;
            if (y == fy + 1 && nearDoor(p[0], p[1])) continue;
            if (c.isAir(p[0], y, p[1])) c.set(p[0], y, p[1], "cobweb");
        }
    }

    /** A 1-block-wide pillar from floor to ceiling. */
    public void pillar(int x, int z) {
        for (int y = fy + 1; y < Geo.CEIL_Y; y++) c.set(x, y, z, theme.pillar.at(x, y, z));
    }

    /** A 2x2 pillar with a capital. */
    public void bigPillar(int x, int z) {
        for (int dx = 0; dx < 2; dx++)
            for (int dz = 0; dz < 2; dz++) {
                for (int y = fy + 1; y < Geo.CEIL_Y; y++) c.set(x + dx, y, z + dz, theme.pillar.at(x + dx, y, z + dz));
                c.set(x + dx, fy + 1, z + dz, theme.trim.at(x + dx, fy + 1, z + dz));
                c.set(x + dx, Geo.CEIL_Y - 1, z + dz, theme.trim.at(x + dx, Geo.CEIL_Y - 1, z + dz));
            }
    }

    /**
     * A crypt: a 3x1 stone tomb against the floor with a lid that explosives remove (the lid is the box recorded).
     * {@code along} = "x" or "z".
     */
    public boolean crypt(int x, int z, char along) {
        int dx = along == 'x' ? 1 : 0, dz = along == 'z' ? 1 : 0;
        for (int i = -1; i <= 1; i++) if (!free(x + dx * i, z + dz * i)) return false;
        for (int i = -1; i <= 1; i++) {
            int bx = x + dx * i, bz = z + dz * i;
            c.set(bx, fy + 1, bz, "polished_andesite");
            c.set(bx, fy + 2, bz, i == 0 ? "chiseled_stone_bricks" : "stone_brick_slab[type=bottom,waterlogged=false]");
        }
        String face = along == 'x' ? "south" : "east";
        if (c.isAir(x + (along == 'x' ? 0 : 1), fy + 1, z + (along == 'x' ? 1 : 0)))
            c.set(x + (along == 'x' ? 0 : 1), fy + 1, z + (along == 'x' ? 1 : 0), "skeleton_wall_skull[facing=" + face + "]");
        info.crypts.add(Box.of(x - dx, fy + 1, z - dz, x + dx, fy + 2, z + dz));
        return true;
    }

    /** A chest secret on the floor against a wall. */
    public boolean chestSecret(int x, int z) {
        String side = wallSide(x, z);
        if (side == null || !free(x, z)) return false;
        c.set(x, fy + 1, z, "chest[facing=" + Blocks.opposite(side) + ",type=single,waterlogged=false]");
        secret(SecretKind.CHEST, x, fy + 1, z);
        return true;
    }

    /** A wither-essence skull on a small plinth. */
    public boolean essenceSecret(int x, int z) {
        if (!free(x, z)) return false;
        c.set(x, fy + 1, z, theme.trim.at(x, fy + 1, z));
        c.set(x, fy + 2, z, "wither_skeleton_skull[rotation=" + rng.nextInt(16) + "]");
        secret(SecretKind.ESSENCE, x, fy + 2, z);
        return true;
    }

    /** An item pedestal (the item itself is spawned at runtime above it). */
    public boolean itemSecret(int x, int z) {
        if (!free(x, z)) return false;
        c.set(x, fy + 1, z, "polished_andesite_slab[type=bottom,waterlogged=false]");
        secret(SecretKind.ITEM, x, fy + 1, z);
        return true;
    }

    /** A lever on the wall (pulling it is the secret). */
    public boolean leverSecret(int x, int z) {
        String side = wallSide(x, z);
        if (side == null || !free(x, z)) return false;
        c.set(x, fy + 2, z, "lever[face=wall,facing=" + Blocks.opposite(side) + ",powered=false]");
        secret(SecretKind.LEVER, x, fy + 2, z);
        return true;
    }

    /** A bat secret: a roost under the ceiling where a bat appears when the room is entered. */
    public boolean batSecret(int x, int z) {
        if (!inside(x, z)) return false;
        secret(SecretKind.BAT, x, Geo.CEIL_Y - 3, z);
        return true;
    }

    /**
     * A sealed alcove against an outer wall: 3 wide, 3 deep, 3 tall, its front of cracked bricks (a weak wall) and a
     * chest secret inside. Explosives open it.
     */
    public boolean weakWallAlcove() {
        for (int t = 0; t < 40; t++) {
            int[] p = randomWallSpot();
            if (p == null) return false;
            String side = wallSide(p[0], p[1]);
            if (side == null) continue;
            int ix = -Blocks.dx(side), iz = -Blocks.dz(side);       // direction into the room
            int px = iz != 0 ? 1 : 0, pz = ix != 0 ? 1 : 0;           // perpendicular
            boolean ok = true;
            for (int depth = 0; depth < 4 && ok; depth++)
                for (int w = -2; w <= 2 && ok; w++) {
                    int x = p[0] + ix * depth + px * w, z = p[1] + iz * depth + pz * w;
                    if (!free(x, z)) ok = false;
                }
            if (!ok) continue;
            // walls of the alcove: depth 0..2 inside, front at depth 3
            for (int depth = 0; depth <= 3; depth++)
                for (int w = -2; w <= 2; w++)
                    for (int y = fy + 1; y <= fy + 4; y++) {
                        int x = p[0] + ix * depth + px * w, z = p[1] + iz * depth + pz * w;
                        boolean edge = Math.abs(w) == 2 || depth == 3 || y == fy + 4;
                        if (!edge) continue;
                        c.set(x, y, z, depth == 3 && Math.abs(w) < 2 && y < fy + 4 ? theme.weakWall : theme.wall.at(x, y, z));
                    }
            // the cracked front
            int fx = p[0] + ix * 3, fz = p[1] + iz * 3;
            info.weakWalls.add(Box.of(fx - px, fy + 1, fz - pz, fx + px, fy + 3, fz + pz));
            // chest at the back of the alcove
            c.set(p[0], fy + 1, p[1], "chest[facing=" + Blocks.opposite(side) + ",type=single,waterlogged=false]");
            secret(SecretKind.CHEST, p[0], fy + 1, p[1]);
            c.set(p[0] + px, fy + 1, p[1] + pz, "candle[candles=3,lit=true,waterlogged=false]");
            return true;
        }
        return false;
    }

    /** Spread {@code n} mob spots over free floor. */
    public void mobs(int n) {
        for (int i = 0; i < n; i++) {
            int[] p = randomFree(3);
            if (p != null) mob(p[0], p[1]);
        }
    }

    /** One random secret of the common kinds. */
    public void randomSecret() {
        for (int t = 0; t < 6; t++) {
            int roll = rng.nextInt(100);
            boolean ok;
            if (roll < 30) { int[] p = randomWallSpot(); ok = p != null && chestSecret(p[0], p[1]); }
            else if (roll < 50) { int[] p = randomFree(4); ok = p != null && itemSecret(p[0], p[1]); }
            else if (roll < 65) { int[] p = randomFree(3); ok = p != null && batSecret(p[0], p[1]); }
            else if (roll < 82) { int[] p = randomFree(3); ok = p != null && essenceSecret(p[0], p[1]); }
            else if (roll < 92) { int[] p = randomWallSpot(); ok = p != null && leverSecret(p[0], p[1]); }
            else ok = weakWallAlcove();
            if (ok) return;
        }
    }
}
