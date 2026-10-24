package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;

/**
 * A building's ground-floor interior seen from its front door, so interiors are written once and
 * work whichever way the building faces: {@code u} runs to the right as you walk in, {@code v}
 * runs forward (0 = the cell just inside the door, {@link #vMax} = the cell against the back
 * wall), {@code h} is height above the floor (1 = feet).
 */
public final class Room {

    public final HubCtx g;
    public final Builders.House house;
    /** Floor block y (stand at y + 1). */
    public final int y;
    /** Direction you walk INTO the building. */
    public final String in;
    final int ox, oz, fx, fz, rx, rz;
    public final int uMin, uMax, vMax;
    /** Free height inside the ground floor (floorHeight - 1). */
    public final int height;

    public Room(HubCtx g, Builders.House b) {
        this.g = g; this.house = b; this.y = b.y;
        this.in = Blocks.opposite(b.door);
        fx = Blocks.dx(in); fz = Blocks.dz(in);
        String right = Blocks.cw(in);
        rx = Blocks.dx(right); rz = Blocks.dz(right);
        // the door block is one step back from the door step; the first inside cell one more
        int doorX = b.doorStep[0] + fx, doorZ = b.doorStep[2] + fz;
        ox = doorX + fx; oz = doorZ + fz;
        int x0 = b.x + 1, x1 = b.x + b.width - 2, z0 = b.z + 1, z1 = b.z + b.depth - 2;
        int umin = Integer.MAX_VALUE, umax = Integer.MIN_VALUE, vmax = Integer.MIN_VALUE;
        for (int[] p : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            int u = u(p[0], p[1]), v = v(p[0], p[1]);
            umin = Math.min(umin, u); umax = Math.max(umax, u); vmax = Math.max(vmax, v);
        }
        uMin = umin; uMax = umax; vMax = vmax;
        height = b.floorHeight - 1;
    }

    public int u(int x, int z) { return (x - ox) * rx + (z - oz) * rz; }
    public int v(int x, int z) { return (x - ox) * fx + (z - oz) * fz; }
    public int x(int u, int v) { return ox + u * rx + v * fx; }
    public int z(int u, int v) { return oz + u * rz + v * fz; }

    public void set(int u, int h, int v, String block) { g.c.set(x(u, v), y + h, z(u, v), block); }

    public boolean free(int u, int h, int v) { return g.c.isAir(x(u, v), y + h, z(u, v)); }

    /** Direction names relative to someone who just walked in. */
    public String fwd() { return in; }
    public String back() { return Blocks.opposite(in); }
    public String right() { return Blocks.cw(in); }
    public String left() { return Blocks.ccw(in); }

    /** NPC anchor at (u, v) looking toward the door. */
    public void npc(String name, int u, int v) { g.npc(name, x(u, v), y, z(u, v), HubCtx.yaw(back())); }

    /** NPC anchor at (u, v) looking along {@code dir}. */
    public void npc(String name, int u, int v, String dir) { g.npc(name, x(u, v), y, z(u, v), HubCtx.yaw(dir)); }

    /** Fill a u/v rectangle at height h. */
    public void rect(int u1, int v1, int u2, int v2, int h, Fill f) {
        for (int u = Math.min(u1, u2); u <= Math.max(u1, u2); u++)
            for (int v = Math.min(v1, v2); v <= Math.max(v1, v2); v++) {
                int X = x(u, v), Z = z(u, v);
                g.c.set(X, y + h, Z, f.at(X, y + h, Z));
            }
    }

    public void rect(int u1, int v1, int u2, int v2, int h, String block) { rect(u1, v1, u2, v2, h, Fill.of(block)); }

    /** Floor pattern over the whole room (h = 0). */
    public void floor(Fill f) { rect(uMin, 0, uMax, vMax, 0, f); }

    /** A carpet runner from the door to {@code vEnd}, {@code half} cells each side of u=0. */
    public void runner(int half, int vEnd, String carpet) {
        for (int v = 0; v <= vEnd; v++)
            for (int u = -half; u <= half; u++) if (free(u, 1, v)) set(u, 1, v, carpet);
    }

    /** Line the side and back walls with a block at heights h1..h2, skipping windows' row if asked. */
    public void lineWalls(int h1, int h2, Fill f, boolean sides, boolean backWall, int vFrom) {
        for (int h = h1; h <= h2; h++) {
            if (sides)
                for (int v = vFrom; v <= vMax; v++) {
                    if (free(uMin, h, v)) set(uMin, h, v, f.at(x(uMin, v), y + h, z(uMin, v)));
                    if (free(uMax, h, v)) set(uMax, h, v, f.at(x(uMax, v), y + h, z(uMax, v)));
                }
            if (backWall)
                for (int u = uMin; u <= uMax; u++) if (free(u, h, vMax)) set(u, h, vMax, f.at(x(u, vMax), y + h, z(u, vMax)));
        }
    }

    /** Hanging lanterns in a grid under the ceiling. */
    public void chandeliers(int step) {
        int top = y + height;
        for (int u = uMin + 2; u <= uMax - 2; u += step)
            for (int v = 2; v <= vMax - 1; v += step) {
                int X = x(u, v), Z = z(u, v);
                if (g.c.isAir(X, top, Z)) g.c.set(X, top, Z, Blocks.lantern(true));
            }
    }

    /** Wall banner hung INSIDE on the back wall at (u, h). */
    public void backBanner(int u, int h, String color) {
        if (free(u, h, vMax)) set(u, h, vMax, Blocks.wallBanner(color, back()));
    }

    /** The interior volume (ground floor) as a box. */
    public Box box() {
        Box b = Box.of(x(uMin, 0), y + 1, z(uMin, 0), x(uMax, vMax), y + height, z(uMax, vMax));
        return b;
    }

    public static Palette pal(String s) { return Palette.of(s); }
}
