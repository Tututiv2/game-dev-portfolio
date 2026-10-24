package com.skyclone.mod.kuudra.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ballista on the platform, as data: every block relative to the BASE block (the platform block
 * under the ballista's centre), facing north (-z, toward the lake). Pure Java: the generator writes the
 * whole thing (so the preview shows it and the block checker validates every name), and the module,
 * at the start of a hunt, clears every {@link #built()} block and puts them back one by one as the crew
 * builds (research 04 sec 4.4 phase 2: "build the ballista to 100%").
 *
 * <p>{@link #built()} is ordered bottom-up, so a half-built ballista never has a block hanging in air.
 */
public final class BallistaShape {

    /** One block: offsets from the base, and the block-state string. */
    public record Part(int dx, int dy, int dz, String block) {}

    private static final List<Part> FOUNDATION;
    private static final List<Part> BUILT;

    static {
        List<Part> f = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                f.add(new Part(dx, 1, dz, dx == 0 && dz == 0 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks"));
        FOUNDATION = Collections.unmodifiableList(f);

        List<Part> b = new ArrayList<>();
        // the turret post and its braces
        b.add(new Part(0, 2, 0, "dark_oak_log[axis=y]"));
        b.add(new Part(-1, 2, 0, "polished_blackstone_bricks"));
        b.add(new Part(1, 2, 0, "polished_blackstone_bricks"));
        b.add(new Part(0, 2, 1, "polished_blackstone_bricks"));
        // the stock, running toward the lake
        for (int dz : new int[]{1, 0, 2, -1, -2, -3}) b.add(new Part(0, 3, dz, "dark_oak_planks"));
        // the bow arms
        for (int dx = -3; dx <= 3; dx++) if (dx != 0) b.add(new Part(dx, 3, -2, "stripped_dark_oak_log[axis=x]"));
        b.add(new Part(-3, 4, -2, "stripped_dark_oak_log[axis=y]"));
        b.add(new Part(3, 4, -2, "stripped_dark_oak_log[axis=y]"));
        // the string, pulled back to the crank
        b.add(new Part(-2, 3, -1, "iron_chain[axis=z]"));
        b.add(new Part(2, 3, -1, "iron_chain[axis=z]"));
        b.add(new Part(-1, 3, 0, "iron_chain[axis=x]"));
        b.add(new Part(1, 3, 0, "iron_chain[axis=x]"));
        // the crank at the back
        b.add(new Part(0, 4, 2, "grindstone[face=floor,facing=north]"));
        // the bolt, laid on the stock
        for (int dz = 1; dz >= -4; dz--) b.add(new Part(0, 4, dz, dz == -4 ? "end_rod[facing=north]" : "dark_oak_slab[type=bottom]"));
        // arm caps
        b.add(new Part(-3, 5, -2, "end_rod[facing=up]"));
        b.add(new Part(3, 5, -2, "end_rod[facing=up]"));
        BUILT = Collections.unmodifiableList(b);
    }

    private BallistaShape() {}

    /** Always present, never cleared (players stand next to it to build). */
    public static List<Part> foundation() { return FOUNDATION; }

    /** Cleared at the start of a hunt and put back in this order as the build progresses. */
    public static List<Part> built() { return BUILT; }

    /** How many {@link #built()} blocks stand at {@code percent} (0..100) built. */
    public static int partsAt(double percent) {
        double p = Math.max(0, Math.min(100, percent));
        return (int) Math.floor(BUILT.size() * p / 100.0 + 1e-9);
    }

    /** Horizontal half-size of the whole shape (for "is this block part of the ballista"). */
    public static int reach() { return 4; }
}
