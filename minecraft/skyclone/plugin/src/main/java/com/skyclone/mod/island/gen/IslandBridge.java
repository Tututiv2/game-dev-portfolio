package com.skyclone.mod.island.gen;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Palette;

/**
 * The island's railed spruce bridges (to the NW islet and to the Portal Isle). PURE: no Bukkit.
 *
 * <p>Why not {@code Builders.bridge}: that one lays the deck as a strip PERPENDICULAR TO THE NEAREST AXIS at each
 * step of the line. On an axis-aligned bridge that is a clean 3-wide deck; on a diagonal one each row is shifted a
 * block from the last, so neighbouring rows overlap by only two blocks, and where a row repeats its z (the line
 * stepped only in x) the next row's rail fence lands ON the walkway. The Portal Isle bridge runs at ~45 degrees and
 * players walking it in a straight line fell into the void at the step (wave-3 playtest, near template -28,29):
 * the real width there was ~1.4 blocks.
 *
 * <p>This one is a thick LINE instead: a cell is deck when its perpendicular distance to the centre line is at
 * most {@link #HALF}, so the walkway is at least 3 blocks wide measured across the bridge on ANY heading. The rail is every
 * cell 8-adjacent to the deck but not in it, which is a 4-connected ring, so the fence posts always join (two posts
 * touching only at a corner leave a gap a player fits through). Height changes are taken in half blocks with
 * bottom slabs, which are walkable from every side - a stair is only a step from one.
 */
public final class IslandBridge {

    private IslandBridge() {}

    /**
     * Deck half-width, perpendicular to the bridge, measured to a block's CENTRE. A point of a block can sit up to
     * 0.707 nearer the line than its centre (a 45-degree heading), so 1.75 guarantees every point within 1.0 of the
     * centre line is over deck on any heading - a 2-block lane you cannot step off - while an axis-aligned bridge is
     * still exactly 3 wide (centres at 0, 1, 2: the third is out).
     */
    public static final double HALF = 1.75;
    public static final String DECK = "spruce_planks:5,stripped_spruce_log[axis=y]:1";
    public static final String RAIL = "spruce_fence";
    public static final String BEAM = "spruce_log";
    public static final String SLAB = "spruce";

    /** Layout data keys holding each bridge's ends as "x1,y1,z1,x2,y2,z2" (template-relative). */
    public static final String DATA_ISLET = "bridge.islet", DATA_PORTAL_ISLE = "bridge.portal_isle";
    /** v6: Portal Isle -> Far Court. Built with this class from the start, so it has no legacy form to repair. */
    public static final String DATA_FAR_COURT = "bridge.far_court";
    /** Suffix of the key holding where generator v1-v4 laid the same bridge (for {@link #legacy}). */
    public static final String LEGACY = ".v4";

    /** A bridge deck from ground block (x1,y1,z1) to (x2,y2,z2). */
    public static void build(VoxelCanvas c, int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x2 - x1, dz = z2 - z1;
        double len = Math.max(1e-9, Math.hypot(dx, dz));
        double ux = dx / len, uz = dz / len;
        Palette deck = Palette.of(DECK);
        int pad = (int) Math.ceil(HALF) + 3;
        int minX = Math.min(x1, x2) - pad, maxX = Math.max(x1, x2) + pad;
        int minZ = Math.min(z1, z2) - pad, maxZ = Math.max(z1, z2) + pad;
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        boolean[] isDeck = new boolean[w * d];
        double[] tAt = new double[w * d], offAt = new double[w * d];
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                double rx = x - x1, rz = z - z1;
                double t = rx * ux + rz * uz, off = rz * ux - rx * uz;
                int i = (z - minZ) * w + (x - minX);
                tAt[i] = t; offAt[i] = off;
                isDeck[i] = t >= -1 && t <= len + 1 && Math.abs(off) <= HALF + 1e-9;
            }
        char ax = Blocks.axisOf(x2 - x1, 0, z2 - z1);
        // deck (+ a half-block slab where the slope sits between two block heights), beams under the two edge lanes
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                int i = (z - minZ) * w + (x - minX);
                if (!isDeck[i]) continue;
                int hs = halfSteps(tAt[i], len, y1, y2);
                int fy = Math.floorDiv(hs, 2);
                boolean slab = (hs & 1) != 0;
                // past either end the deck only paves ground that is already level with it: never over something built
                // (the courtyard), and never by cutting into a rise (that undercut a farm hay bale and left it floating)
                boolean cap = tAt[i] < 0 || tAt[i] > len;
                if (cap && ((!c.isAir(x, fy, z) && !Builders.natural(c.get(x, fy, z)))
                        || !com.skyclone.gen.BlockInfo.of(c.get(x, fy + 1, z)).passable())) continue;
                c.set(x, fy, z, deck.at(x, fy, z));
                if (slab) c.set(x, fy + 1, z, Blocks.slab(SLAB, "bottom"));
                for (int a = slab ? 2 : 1; a <= 3; a++) if (Builders.natural(c.get(x, fy + a, z))) c.set(x, fy + a, z, "air");
                if (Math.abs(offAt[i]) > HALF - 1 && c.isAir(x, fy - 1, z)) c.set(x, fy - 1, z, BEAM + "[axis=" + ax + "]");
            }
        // the rail: every non-deck cell touching the deck (8-neighbourhood), except at the two open ends and over
        // land (a fence on the grass beside the bridge head only gets in the way of the path running into it)
        boolean[] isRail = new boolean[w * d];
        for (int z = minZ + 1; z < maxZ; z++)
            for (int x = minX + 1; x < maxX; x++) {
                int i = (z - minZ) * w + (x - minX);
                if (isDeck[i] || tAt[i] < 0.5 || tAt[i] > len - 0.5) continue;
                boolean touches = false;
                for (int ox = -1; ox <= 1 && !touches; ox++)
                    for (int oz = -1; oz <= 1 && !touches; oz++) touches = isDeck[i + oz * w + ox];
                if (!touches) continue;
                int ry = railBase(tAt[i], len, y1, y2);
                if (overLand(c, x, ry, z)) continue;
                isRail[i] = true;
                c.set(x, ry, z, deck.at(x, ry, z));
                c.set(x, ry + 1, z, RAIL);
                for (int a = 2; a <= 3; a++) if (Builders.natural(c.get(x, ry + a, z))) c.set(x, ry + a, z, "air");
            }
        // lantern posts every 8 blocks along each side
        for (double k = 4; k <= len - 2; k += 8)
            for (int side = -1; side <= 1; side += 2) {
                int best = -1;
                double bestScore = Double.MAX_VALUE;
                for (int i = 0; i < w * d; i++) {
                    if (!isRail[i] || Math.signum(offAt[i]) != side) continue;
                    double score = Math.abs(tAt[i] - k) * 4 + Math.abs(offAt[i]);
                    if (score < bestScore) { bestScore = score; best = i; }
                }
                if (best < 0) continue;
                int x = minX + best % w, z = minZ + best / w;
                c.set(x, railBase(tAt[best], len, y1, y2) + 2, z, Blocks.lantern(false));
            }
    }

    /** Is there ground right under a rail post's foot (land you could step down onto, not a cliff underside)? */
    private static boolean overLand(VoxelCanvas c, int x, int y, int z) {
        for (int dy = 0; dy <= 2; dy++) if (!c.isAir(x, y - dy, z)) return true;
        return false;
    }

    /** Deck height at distance t along the bridge, in half blocks. */
    static int halfSteps(double t, double len, int y1, int y2) {
        double f = Math.max(0, Math.min(1, t / len));
        return (int) Math.round(2 * (y1 + (y2 - y1) * f));
    }

    /** A rail post's foot block: level with the deck, or a half block higher where the deck carries a slab (so the
     *  fence still stands a full 1.5 blocks above where you walk and cannot be jumped onto). */
    static int railBase(double t, double len, int y1, int y2) {
        int hs = halfSteps(t, len, y1, y2);
        return Math.floorDiv(hs + 1, 2);
    }

    public static void build(VoxelCanvas c, int[] s) { build(c, s[0], s[1], s[2], s[3], s[4], s[5]); }

    /** The bridge generator versions 1-4 built ({@code Builders.bridge}), for repairing islands built with it. */
    public static void legacy(VoxelCanvas c, int[] s) {
        Builders.bridge(c, s[0], s[1], s[2], s[3], s[4], s[5], 3, Palette.of(DECK), RAIL, BEAM, "spruce_stairs");
    }

    // ---------------------------------------------------------------- repairing islands built before v5

    /** Every material either bridge (old or new) is made of. */
    public static final java.util.Set<String> PARTS = java.util.Set.of(
            "spruce_planks", "stripped_spruce_log", "spruce_fence", "spruce_log", "spruce_stairs", "spruce_slab", "lantern");
    private static final java.util.Set<String> DECKISH = java.util.Set.of("spruce_planks", "stripped_spruce_log", "spruce_log", "spruce_slab");

    /** What {@link #repairPlan} wants changed: parallel lists of template-relative cells and block states ("air" = remove). */
    public static final class Plan {
        public int oldBlocks, oldFound;
        public final java.util.List<int[]> cells = new java.util.ArrayList<>();
        public final java.util.List<String> states = new java.util.ArrayList<>();
        /** Is the old bridge actually there (at least half of its blocks, by material, at their exact places)? */
        public boolean oldPresent() { return oldBlocks > 0 && oldFound * 2 >= oldBlocks; }
    }

    /**
     * Swap the old ({@code Builders.bridge}, ends {@code old}) bridge in {@code world} for the new one (ends {@code s}).
     * {@code world} is a copy of the island's blocks over box {@code b}. Nothing a player built is touched: a cell is
     * changed only if it is air or holds the old bridge's own material at an old-bridge position, and an old deck block
     * bedded in the ground (solid below) is kept rather than leave a hole at the bridge head. The new bridge is laid on
     * the world with the old one taken out, so it sees the real terrain (no rail on land) exactly as the generator does.
     * Returns an empty plan (with the counts) when the old bridge is not there.
     */
    public static Plan repairPlan(VoxelCanvas world, int[] s, int[] old, com.skyclone.api.world.gen.Box b) {
        Plan plan = new Plan();
        com.skyclone.gen.MemoryCanvas legacy = new com.skyclone.gen.MemoryCanvas();
        legacy(legacy, old);
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    if (legacy.isAir(x, y, z)) continue;
                    plan.oldBlocks++;
                    if (name(legacy.get(x, y, z)).equals(name(world.get(x, y, z)))) plan.oldFound++;
                }
        if (!plan.oldPresent()) return plan;
        com.skyclone.gen.MemoryCanvas copy = new com.skyclone.gen.MemoryCanvas();
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++)
                    if (!world.isAir(x, y, z) && !isOld(legacy, world, x, y, z)) copy.set(x, y, z, world.get(x, y, z));
        build(copy, s);
        com.skyclone.gen.Connect.apply(copy);
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    boolean wasOld = isOld(legacy, world, x, y, z);
                    if (!world.isAir(x, y, z) && !wasOld) continue;              // never a player's block, never terrain
                    if (copy.isAir(x, y, z)) {
                        if (!wasOld) continue;
                        if (DECKISH.contains(name(world.get(x, y, z))) && !world.isAir(x, y - 1, z)) continue;
                        plan.cells.add(new int[]{x, y, z});
                        plan.states.add("air");
                        continue;
                    }
                    String want = copy.get(x, y, z);
                    if (com.skyclone.gen.BlockState.normalise(want).equals(com.skyclone.gen.BlockState.normalise(world.get(x, y, z)))) continue;
                    plan.cells.add(new int[]{x, y, z});
                    plan.states.add(want);
                }
        return plan;
    }

    private static boolean isOld(VoxelCanvas legacy, VoxelCanvas world, int x, int y, int z) {
        if (legacy.isAir(x, y, z)) return false;
        String m = name(world.get(x, y, z));
        return PARTS.contains(m) && name(legacy.get(x, y, z)).equals(m);
    }

    /** Bare block name: no namespace, no properties. */
    public static String name(String state) {
        if (state == null) return "air";
        String n = state.startsWith("minecraft:") ? state.substring(10) : state;
        int k = n.indexOf('[');
        return k >= 0 ? n.substring(0, k) : n;
    }

    /** Template-relative box around a bridge: its deck, rails, beams and lanterns, new ({@code a}) or old ({@code b}). */
    public static com.skyclone.api.world.gen.Box box(int[] a, int[] b) {
        int pad = 6;
        return com.skyclone.api.world.gen.Box.of(Math.min(a[0], a[3]) - pad, Math.min(Math.min(a[1], a[4]), Math.min(b[1], b[4])) - 3, Math.min(a[2], a[5]) - pad,
                Math.max(a[0], a[3]) + pad, Math.max(Math.max(a[1], a[4]), Math.max(b[1], b[4])) + 4, Math.max(a[2], a[5]) + pad);
    }

    public static String encode(int x1, int y1, int z1, int x2, int y2, int z2) {
        return x1 + "," + y1 + "," + z1 + "," + x2 + "," + y2 + "," + z2;
    }

    /** Null when the text is not six integers. */
    public static int[] decode(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 6) return null;
        int[] out = new int[6];
        try { for (int i = 0; i < 6; i++) out[i] = Integer.parseInt(p[i].trim()); } catch (NumberFormatException e) { return null; }
        return out;
    }
}
