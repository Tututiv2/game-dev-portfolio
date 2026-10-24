package com.skyclone.gen;

import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;

import java.util.List;

/**
 * Runs a generator the one way both the preview tool and the game do: into a MemoryCanvas,
 * through a bounds guard, then the fence/pane/wall connection pass. Pure — safe off-thread.
 */
public final class IslandRun {

    private IslandRun() {}

    public static final class Result {
        public final MemoryCanvas canvas;
        public final Layout layout;
        public final long generateMillis;
        public final long outsideBounds;
        public final List<String> outsideSamples;
        public final int connected;
        public final long seed;

        Result(MemoryCanvas canvas, Layout layout, long ms, long outside, List<String> samples, int connected, long seed) {
            this.canvas = canvas; this.layout = layout; this.generateMillis = ms; this.outsideBounds = outside;
            this.outsideSamples = samples; this.connected = connected; this.seed = seed;
        }
    }

    /** Run with block offset (dx, dy, dz) applied to every write (instances); the layout stays template-relative. */
    public static Result run(IslandGenerator gen, long seed, int dx, int dy, int dz) {
        MemoryCanvas mem = new MemoryCanvas();
        Layout layout = new Layout();
        VoxelCanvas target = (dx | dy | dz) == 0 ? mem : new OffsetCanvas(mem, dx, dy, dz);
        GuardCanvas guard = new GuardCanvas(target, gen.bounds());
        long t0 = System.nanoTime();
        gen.generate(guard, layout, seed);
        int connected = Connect.apply(mem);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Result(mem, layout, ms, guard.outside(), guard.samples(), connected, seed);
    }

    public static Result run(IslandGenerator gen, long seed) { return run(gen, seed, 0, 0, 0); }

    /** The default seed of an island: stable per id, the same in preview and in game. */
    public static long seedOf(String islandId) { return Hash.of("skyclone:island:" + islandId); }
}
