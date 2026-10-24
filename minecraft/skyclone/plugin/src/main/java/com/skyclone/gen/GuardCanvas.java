package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a canvas and counts writes outside a declared box (the generator's {@code bounds()}).
 * Out-of-bounds writes still go through; the preview tool and core report them, because clearing
 * and rebuilding only cover the declared bounds.
 */
public final class GuardCanvas implements VoxelCanvas {

    private final VoxelCanvas target;
    private final Box box;
    private long outside;
    private final List<String> samples = new ArrayList<>();
    private long writes;

    public GuardCanvas(VoxelCanvas target, Box box) { this.target = target; this.box = box; }

    @Override public void set(int x, int y, int z, String block) {
        writes++;
        if (box != null && !(x >= box.minX() && x <= box.maxX() && y >= box.minY() && y <= box.maxY() && z >= box.minZ() && z <= box.maxZ())) {
            outside++;
            if (samples.size() < 8) samples.add(x + "," + y + "," + z + " " + block);
        }
        target.set(x, y, z, block);
    }

    @Override public String get(int x, int y, int z) { return target.get(x, y, z); }

    @Override public boolean isAir(int x, int y, int z) { return target.isAir(x, y, z); }

    @Override public int topY(int x, int z, int minY, int maxY) { return target.topY(x, z, minY, maxY); }

    public long outside() { return outside; }

    public long writes() { return writes; }

    public List<String> samples() { return samples; }
}
