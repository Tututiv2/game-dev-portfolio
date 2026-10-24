package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;

/**
 * Where a district warp puts you, and which way you face. PURE (canvas + layout only): the generator
 * places with it and the unit test checks the shipped hub with the very same rule.
 *
 * <p>Wave-4 endgame playtest: {@code /warp wizard} landed beside the Wizard Tower with its wall filling
 * the left half of the screen, because every open-ground warp was given yaw 180 (north) whatever stood
 * there; and {@code /warp da} put the player in the Wilderness, outside the Dark Auction's SAFE zone,
 * one block from a Lv50 Wraith during the Spooky Festival. A landing is now chosen, not just found:
 * open ground inside the district's own SAFE zone, far enough back that the landmark is a view and not
 * a wall, turned to face it, with nothing at eye level close in front.
 */
public final class Landing {

    private Landing() {}

    /** Half-angle of the view checked in front of a landing (about the screen's width at default FOV). */
    public static final double CONE_DEG = 45;
    /** Nothing at eye level may stand closer than this anywhere inside that view. */
    public static final double NEAR = 5;

    /** The Wizard Tower as a landmark: its radius (+ half a block), and how far back from its wall the warp stands. */
    public static final double WIZARD_REACH = HubSouth.WIZARD_TOWER_R + 0.5, WIZARD_GAP = 9;
    /** The Dark Auction warp stands at least this far from the hall's door step, facing it. */
    public static final double DA_GAP = 6;

    /** Yaw (Minecraft: 0 = south, 90 = west, 180 = north) that looks from (x, z) towards (tx, tz). */
    public static float yawTo(double x, double z, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }

    /** A cell that hides what is behind it at eye level (plants, air, doors and light do not). */
    static boolean blocksView(String state) {
        BlockInfo bi = BlockInfo.of(state);
        if (bi.isAir() || bi.invisible || bi.sprite || bi.water) return false;
        return bi.cube || bi.top16 != 0;
    }

    /**
     * Distance along the ray from the eye of a player standing at feet (x, feetY, z), heading {@code yaw},
     * to the first block that hides the view, or {@code max} when the ray is clear that far.
     */
    public static double clearAlong(VoxelCanvas c, double x, double feetY, double z, double yaw, double max) {
        double r = Math.toRadians(yaw), dx = -Math.sin(r), dz = Math.cos(r);
        int ey = (int) Math.floor(feetY + 1.62);
        for (double s = 0.5; s <= max; s += 0.25) {
            int bx = (int) Math.floor(x + dx * s), bz = (int) Math.floor(z + dz * s);
            if (blocksView(c.get(bx, ey, bz))) return s;
        }
        return max;
    }

    /** The nearest eye-level obstacle anywhere in the {@link #CONE_DEG} view ahead, capped at {@code max}. */
    public static double nearestInView(VoxelCanvas c, double x, double feetY, double z, double yaw, double max) {
        double best = max;
        for (double a = -CONE_DEG; a <= CONE_DEG + 1e-9; a += 5) best = Math.min(best, clearAlong(c, x, feetY, z, yaw + a, max));
        return best;
    }

    /**
     * What is wrong with landing at anchor {@code a} to look at a landmark centred on (lx, lz) that is
     * {@code reach} blocks in radius (0.5 for a door), or null when it is right: the anchor faces the
     * landmark (within 10 degrees), stands at least {@code minGap} blocks clear of it, sees it (the line
     * of sight reaches it), and nothing else stands within {@link #NEAR} anywhere in the view.
     */
    public static String viewProblem(VoxelCanvas c, Layout.Anchor a, double lx, double lz, double reach, double minGap) {
        if (a == null) return "no anchor";
        double dist = Math.hypot(lx - a.x(), lz - a.z());
        double gap = dist - reach;
        if (gap < minGap) return a.name() + " stands " + fmt(gap) + " blocks from its landmark (wants " + fmt(minGap) + "+)";
        float want = yawTo(a.x(), a.z(), lx, lz);
        double off = Math.abs(wrap(a.yaw() - want));
        if (off > 10) return a.name() + " faces yaw " + fmt(a.yaw()) + ", " + fmt(off) + " degrees away from its landmark";
        double sight = clearAlong(c, a.x(), a.y(), a.z(), want, gap);
        if (sight < gap - 0.75) return a.name() + ": something blocks the view of its landmark " + fmt(sight) + " blocks ahead";
        double near = nearestInView(c, a.x(), a.y(), a.z(), a.yaw(), NEAR);
        if (near < NEAR) return a.name() + ": a block stands " + fmt(near) + " blocks ahead at eye level";
        return null;
    }

    /**
     * Place {@code name} on open ground inside {@code inside} (the district's SAFE zone), as near to
     * (prefX, prefZ) as the rules allow, facing the landmark. Throws when nowhere fits: a layout bug
     * that must be seen in the preview, not shipped.
     */
    static void place(HubCtx g, String name, Box inside, int prefX, int prefZ, double lx, double lz, double reach, double minGap) {
        int best = -1, bx = 0, by = 0, bz = 0;
        for (int z = inside.minZ(); z <= inside.maxZ(); z++)
            for (int x = inside.minX(); x <= inside.maxX(); x++) {
                int d2 = (x - prefX) * (x - prefX) + (z - prefZ) * (z - prefZ);
                if (best >= 0 && d2 >= best) continue;
                int y = openGround(g, x, z);
                if (y == Integer.MIN_VALUE) continue;
                Layout.Anchor a = new Layout.Anchor(name, x + 0.5, y + 1, z + 0.5, yawTo(x + 0.5, z + 0.5, lx, lz), 0f);
                if (viewProblem(g.c, a, lx, lz, reach, minGap) != null) continue;
                best = d2; bx = x; by = y; bz = z;
            }
        if (best < 0) throw new IllegalStateException("no landing for " + name + " in " + inside);
        g.npc(name, bx, by, bz, yawTo(bx + 0.5, bz + 0.5, lx, lz));
    }

    /**
     * Every {@code warp.<district>} landing that stands inside another district's mob spawn area without a
     * SAFE zone round it (the smallest zone there must be SAFE). A district's own warp may land in its own
     * hunting ground ({@code warp.graveyard}, {@code warp.wilderness}: that is where the player asked to go,
     * and the Graveyard's quiet gate is the pack's job); a warp to anywhere else must not. Pure.
     */
    public static java.util.List<String> unsafeLandings(Layout l, java.util.Collection<String> spawnAreas) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (Layout.Anchor a : l.anchors().values()) {
            if (!a.name().startsWith("warp.")) continue;
            String district = a.name().substring(5);
            for (String area : new java.util.TreeSet<>(spawnAreas)) {
                if (area.equals(district) || area.startsWith(district + ".") || area.startsWith(district + "_")) continue;
                Box b = l.area(area);
                if (b == null || !b.containsXZ(a.x(), a.z()) || a.y() < b.minY() - 3 || a.y() > b.maxY() + 3) continue;
                Layout.Zone z = smallestZone(l, a.x(), a.y() + 0.1, a.z());
                if (z == null || z.flags() == null || !z.flags().contains(Layout.Zone.SAFE))
                    bad.add(a.name() + " lands in the " + area + " spawn area, in " + (z == null ? "no zone" : "zone " + z.id() + " (not SAFE)"));
            }
        }
        return bad;
    }

    /** The zone a player at (x, y, z) is in: the smallest containing one, as core resolves it. */
    public static Layout.Zone smallestZone(Layout l, double x, double y, double z) {
        Layout.Zone best = null;
        for (Layout.Zone zn : l.zones())
            if (zn.box().contains(x, y, z) && (best == null || zn.box().volume() < best.box().volume())) best = zn;
        return best;
    }

    /** The ground block at (x, z) when a player can land there (open sky above, dry, not a tree), else MIN_VALUE. */
    static int openGround(HubCtx g, int x, int z) {
        if (!g.h.has(x, z) || g.h.wet(x, z)) return Integer.MIN_VALUE;
        int y = g.ground(x, z);
        if (Math.abs(y - g.h.top(x, z)) > 3) return Integer.MIN_VALUE;
        for (int yy = y + 1; yy <= y + 10; yy++)
            if (BlockInfo.of(g.c.get(x, yy, z)).top16 != 0) return Integer.MIN_VALUE;
        String top = BlockState.name(g.c.get(x, y, z));
        if (top.endsWith("_leaves") || top.endsWith("_log") || top.contains("water")) return Integer.MIN_VALUE;
        return y;
    }

    static double wrap(double deg) {
        double d = deg % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }
}
