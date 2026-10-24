package com.skyclone.mod.dungeons.gen;

import com.skyclone.api.world.gen.Box;

/**
 * A single cell's interior seen from its door: u runs across (0..29), v runs away from the door (0 = just inside,
 * 29 = against the far wall). Puzzle rooms are authored in (u, v) and come out right whichever wall the door is in;
 * the runtime uses the same frame (rebuilt from the room's cell and door side) to turn block positions back into (u, v).
 * Pure.
 */
public final class Frame {

    public final Box cell;
    /** 'N', 'E', 'S', 'W': the wall the door is in. */
    public final char side;

    public Frame(Box interior, char side) {
        this.cell = interior;
        this.side = side;
    }

    public int x(int u, int v) {
        return switch (side) {
            case 'N' -> cell.minX() + u;
            case 'S' -> cell.maxX() - u;
            case 'W' -> cell.minX() + v;
            default -> cell.maxX() - v;
        };
    }

    public int z(int u, int v) {
        return switch (side) {
            case 'N' -> cell.minZ() + v;
            case 'S' -> cell.maxZ() - v;
            case 'W' -> cell.maxZ() - u;
            default -> cell.minZ() + u;
        };
    }

    /** Inverse: u of a world (x, z). */
    public int u(int x, int z) {
        return switch (side) {
            case 'N' -> x - cell.minX();
            case 'S' -> cell.maxX() - x;
            case 'W' -> cell.maxZ() - z;
            default -> z - cell.minZ();
        };
    }

    /** Inverse: v of a world (x, z). */
    public int v(int x, int z) {
        return switch (side) {
            case 'N' -> z - cell.minZ();
            case 'S' -> cell.maxZ() - z;
            case 'W' -> x - cell.minX();
            default -> cell.maxX() - x;
        };
    }

    /** World (dx, dz) of one step of +u. */
    public int[] du() { return new int[]{x(1, 0) - x(0, 0), z(1, 0) - z(0, 0)}; }

    /** World (dx, dz) of one step of +v. */
    public int[] dv() { return new int[]{x(0, 1) - x(0, 0), z(0, 1) - z(0, 0)}; }

    /** Compass name of +v (away from the door). */
    public String away() {
        return switch (side) { case 'N' -> "south"; case 'S' -> "north"; case 'W' -> "east"; default -> "west"; };
    }

    public String toward() {
        return switch (side) { case 'N' -> "north"; case 'S' -> "south"; case 'W' -> "west"; default -> "east"; };
    }

    /** Compass name of +u. */
    public String plusU() {
        return switch (side) { case 'N' -> "east"; case 'S' -> "west"; case 'W' -> "north"; default -> "south"; };
    }
}
