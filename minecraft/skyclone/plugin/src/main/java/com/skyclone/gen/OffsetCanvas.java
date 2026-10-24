package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;

/** Translates every coordinate by (dx, dy, dz) before passing it on. */
public final class OffsetCanvas implements VoxelCanvas {

    private final VoxelCanvas target;
    private final int dx, dy, dz;

    public OffsetCanvas(VoxelCanvas target, int dx, int dy, int dz) {
        this.target = target; this.dx = dx; this.dy = dy; this.dz = dz;
    }

    @Override public void set(int x, int y, int z, String block) { target.set(x + dx, y + dy, z + dz, block); }

    @Override public String get(int x, int y, int z) { return target.get(x + dx, y + dy, z + dz); }

    @Override public boolean isAir(int x, int y, int z) { return target.isAir(x + dx, y + dy, z + dz); }

    @Override public void fill(Box b, String block) {
        target.fill(new Box(b.minX() + dx, b.minY() + dy, b.minZ() + dz, b.maxX() + dx, b.maxY() + dy, b.maxZ() + dz), block);
    }

    @Override public int topY(int x, int z, int minY, int maxY) {
        int t = target.topY(x + dx, z + dz, minY + dy, maxY + dy);
        return t == Integer.MIN_VALUE ? t : t - dy;
    }
}
