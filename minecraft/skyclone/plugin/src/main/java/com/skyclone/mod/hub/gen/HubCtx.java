package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the hub's builder passes share: the canvas, the layout, the heightmap, the building
 * reservations, the road network, and small helpers (ground lookup, NPC anchors, fairy souls,
 * lamps, paving). PURE: no Bukkit anywhere in this package.
 */
public final class HubCtx {

    public final VoxelCanvas c;
    public final Layout layout;
    public final long seed;
    public Terrain.Heightmap h;
    public final Reservations res = new Reservations();
    /** Every road paved so far (trees and scatter keep off it). */
    public final Builders.PathResult roads = new Builders.PathResult();
    /** Village ground level (the grass/paving block y). */
    public int V;
    /** Ground heights picked for each district plot by the terrain pass. */
    public int portalY, miningY, graveY, farmY, colY, campY, summitY, wizardY, daY, witchY, outpostY, ruinsY, castleY, carnivalY;
    /** Step outside the Dark Auction hall's door (the warp lands facing it); set by {@link HubSouth#wilderness}. */
    int[] daDoorStep;
    /** The Dark Auction's SAFE zone (the whole plot, forecourt and landing included). */
    Box daSafe;
    private int souls;
    /** Spots where a lamp has been placed, so roads do not crowd them. */
    final List<int[]> lamps = new ArrayList<>();
    /** Columns trees must never use (yards, fields, plazas that are not reserved buildings). */
    final List<Box> keepOut = new ArrayList<>();

    public HubCtx(VoxelCanvas c, Layout layout, long seed) {
        this.c = c; this.layout = layout; this.seed = seed;
    }

    // ---- ground ---------------------------------------------------------------------------

    /** Highest full-top ground block at (x, z), looking down from y 260. */
    public int ground(int x, int z) {
        int g = Builders.ground(c, x, z, 260, 10);
        if (g == Integer.MIN_VALUE) {
            int t = h == null ? Terrain.Heightmap.NONE : h.top(x, z);
            return t == Terrain.Heightmap.NONE ? V : t;
        }
        return g;
    }

    /** Ground looking down from {@code fromY} (ignores roofs above it). */
    public int groundBelow(int x, int z, int fromY) {
        int g = Builders.ground(c, x, z, fromY, 10);
        return g == Integer.MIN_VALUE ? V : g;
    }

    public boolean natural(int x, int y, int z) { return Builders.natural(c.get(x, y, z)); }

    /** Clear plants/terrain from y+1..y+n at a column (never touches buildings). */
    public void clearAbove(int x, int y, int z, int n) {
        for (int a = 1; a <= n; a++) {
            String b = c.get(x, y + a, z);
            if (!c.isAir(x, y + a, z) && Builders.natural(b)) c.set(x, y + a, z, "air");
        }
    }

    // ---- anchors --------------------------------------------------------------------------

    /** An NPC / feature anchor standing on floor block (x, y, z); clears feet + head. */
    public void npc(String name, int x, int y, int z, float yaw) {
        for (int a = 1; a <= 2; a++) {
            BlockInfo i = BlockInfo.of(c.get(x, y + a, z));
            if (i.top16 != 0 || i.lava) c.set(x, y + a, z, "air");
        }
        layout.standAt(name, x, y, z, yaw);
    }

    /** Yaw that looks along a direction ("south" = 0). */
    public static float yaw(String dir) {
        return switch (dir) { case "south" -> 0f; case "west" -> 90f; case "north" -> 180f; default -> -90f; };
    }

    /** A hidden fairy soul standing on floor block (x, y, z). */
    public void soul(int x, int y, int z) {
        souls++;
        npc("fairy_soul." + souls, x, y, z, 0f);
    }

    /** A fairy soul on whatever ground is at (x, z). */
    public void soulOnGround(int x, int z) { soul(x, ground(x, z), z); }

    public int soulCount() { return souls; }

    // ---- zones / areas --------------------------------------------------------------------

    public void zone(String id, String name, char color, Box box, String... flags) {
        layout.zone(id, name, color, box, null, flags);
    }

    /** A zone spanning the full height around an x/z box. */
    public void zoneXZ(String id, String name, char color, int x1, int z1, int x2, int z2, int y1, int y2, String... flags) {
        layout.zone(id, name, color, Box.of(x1, y1, z1, x2, y2, z2), null, flags);
    }

    public void area(String name, Box b) { layout.area(name, b); }

    // ---- building helpers -----------------------------------------------------------------

    /** Reserve or fail loudly: an overlap is a layout bug and must be seen in the preview. */
    public void reserve(String name, Box b) {
        if (!res.reserve(name, b)) throw new IllegalStateException("hub reservation refused: " + res.refused().get(res.refused().size() - 1));
    }

    public boolean tryReserve(String name, Box b) { return res.reserve(name, b); }

    public void keepOut(Box b) { keepOut.add(b); }

    public boolean treeBlocked(int x, int z, int margin) {
        if (res.covers(x, z, margin) || roads.near(x, z, Math.min(margin, 3))) return true;
        for (Box b : keepOut)
            if (x >= b.minX() - margin && x <= b.maxX() + margin && z >= b.minZ() - margin && z <= b.maxZ() + margin) return true;
        return false;
    }

    /** Lamp post on the ground at (x, z). */
    public void lamp(int x, int z, String style, String facing) {
        int y = ground(x, z);
        if (!c.isAir(x, y + 1, z) && !Builders.natural(c.get(x, y + 1, z))) return;
        clearAbove(x, y, z, 5);
        Builders.lampPost(c, x, y, z, style, facing);
        lamps.add(new int[]{x, z});
    }

    public boolean lampNear(int x, int z, int r) {
        for (int[] l : lamps) if (Math.abs(l[0] - x) <= r && Math.abs(l[1] - z) <= r) return true;
        return false;
    }

    /** Pave an x/z box at height y (top block replaced, terrain above cleared, footing below). */
    public void pave(int x1, int z1, int x2, int z2, int y, Fill f) {
        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) paveCell(x, y, z, f);
    }

    public void paveCell(int x, int y, int z, Fill f) {
        String cur = c.get(x, y, z);
        if (!c.isAir(x, y, z) && !Builders.natural(cur) && !BlockState.name(cur).equals("water")) return;
        c.set(x, y, z, f.at(x, y, z));
        Builders.footing(c, x, y, z, Fill.of("dirt"), 14);
        clearAbove(x, y, z, 4);
    }

    /** Pave a disk. */
    public void paveDisk(int cx, int cz, double r, int y, Fill f) {
        int R = (int) Math.ceil(r);
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++)
                if (dx * dx + dz * dz <= r * r + 0.3) paveCell(cx + dx, y, cz + dz, f);
    }

    /** A road along a polyline; recorded in {@link #roads}. */
    public Builders.PathResult road(int[][] pts, int width, Fill surface) {
        // The toolkit samples the FIRST point's height from the sky down, so a roof, tower or lamp
        // over the start lifts the whole path onto a causeway. Hide everything above the terrain
        // in that one column while the path is planned.
        int sx = pts[0][0], sz = pts[0][1];
        int t = h == null ? Terrain.Heightmap.NONE : h.top(sx, sz);
        final int limit = t == Terrain.Heightmap.NONE ? Integer.MAX_VALUE : t + 2;
        VoxelCanvas view = new VoxelCanvas() {
            @Override public void set(int x, int y, int z, String block) { c.set(x, y, z, block); }
            @Override public String get(int x, int y, int z) { return x == sx && z == sz && y > limit ? "air" : c.get(x, y, z); }
        };
        return Builders.path(view, pts, width, surface, "cobblestone_stairs", roads);
    }

    public long salt(String s) { return Hash.salt(seed, s); }

    public double rnd(String s, int x, int z) { return Hash.unit(salt(s), x, z); }

    public static Palette pal(String spec) { return Palette.of(spec); }

    /** Wall banner on the outside face of a wall block (bx, by, bz) facing {@code out}. */
    public void wallBanner(int bx, int by, int bz, String color, String out) {
        int x = bx + Blocks.dx(out), z = bz + Blocks.dz(out);
        if (c.isAir(x, by, z)) c.set(x, by, z, Blocks.wallBanner(color, out));
    }
}
