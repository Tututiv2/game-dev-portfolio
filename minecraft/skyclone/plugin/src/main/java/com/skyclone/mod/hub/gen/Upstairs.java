package com.skyclone.mod.hub.gen;

import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Furniture for the upper floors of the hub's two-storey buildings (the Tavern, the Old Keep and the
 * two-storey townhouses). The toolkit's house gives every upper floor a ladder, a lantern and bare
 * boards, so a player who climbed up found an empty attic. Each floor gets a theme:
 * <ul>
 *   <li>{@code inn}: guest beds down both side walls with a nightstand between each pair, a table.</li>
 *   <li>{@code home}: one bed, a wardrobe corner (chest, bookshelf), a table and a rug.</li>
 *   <li>{@code keep}: a lord's study: bookshelves along the back wall, a lectern desk, a bed, a runner.</li>
 * </ul>
 * Nothing is placed within one cell of the ladder, so climbing off it always lands on clear floor,
 * and only on boards that are really there (never over the ladder hole).
 */
final class Upstairs {

    private Upstairs() {}

    static void furnish(HubCtx g, Room r, String theme) {
        int floors = r.house.floors, fh = r.house.floorHeight;
        for (int f = 1; f < floors; f++) new Floor(g, r, r.y + f * fh, theme, f).fill();
    }

    /** One upper floor: the room's u/v grid at floor block {@code fy}. */
    private static final class Floor {
        final HubCtx g;
        final Room r;
        final int fy, level;
        final String theme;
        final List<int[]> ladder = new ArrayList<>();

        Floor(HubCtx g, Room r, int fy, String theme, int level) {
            this.g = g; this.r = r; this.fy = fy; this.theme = theme; this.level = level;
            for (int u = r.uMin; u <= r.uMax; u++)
                for (int v = 0; v <= r.vMax; v++)
                    if (name(u, 0, v).equals("ladder") || name(u, 1, v).equals("ladder")) ladder.add(new int[]{u, v});
        }

        String name(int u, int h, int v) { return BlockState.name(g.c.get(r.x(u, v), fy + h, r.z(u, v))); }

        /** Free to furnish: boards below, air here and above, not next to the ladder. */
        boolean ok(int u, int v) {
            if (u < r.uMin || u > r.uMax || v < 0 || v > r.vMax) return false;
            for (int[] l : ladder) if (Math.abs(l[0] - u) <= 1 && Math.abs(l[1] - v) <= 1) return false;
            int X = r.x(u, v), Z = r.z(u, v);
            return !g.c.isAir(X, fy, Z) && !name(u, 0, v).equals("ladder") && g.c.isAir(X, fy + 1, Z) && g.c.isAir(X, fy + 2, Z);
        }

        void put(int u, int h, int v, String block) { g.c.set(r.x(u, v), fy + h, r.z(u, v), block); }

        double rnd(String s, int u, int v) { return g.rnd(s + level, r.x(u, v), r.z(u, v)); }

        String colour(String salt) {
            String[] c = {"red", "blue", "green", "yellow", "light_blue", "brown", "cyan", "purple"};
            return c[(int) (rnd(salt, 0, 0) * c.length) % c.length];
        }

        /** A bed along a side wall with its head toward the back; false if it does not fit. */
        boolean bed(int u, int v, String colour) {
            if (!ok(u, v) || !ok(u, v + 1)) return false;
            String b = colour + "_bed[facing=" + r.fwd() + ",occupied=false,part=";
            put(u, 1, v, b + "foot]");
            put(u, 1, v + 1, b + "head]");
            return true;
        }

        void table(int u, int v) {
            if (!ok(u, v)) return;
            put(u, 1, v, "spruce_fence");
            put(u, 2, v, "spruce_pressure_plate[powered=false]");
            if (ok(u - 1, v)) put(u - 1, 1, v, Blocks.stairs("spruce", r.right()));
            if (ok(u + 1, v)) put(u + 1, 1, v, Blocks.stairs("spruce", r.left()));
        }

        void rug(int u0, int v0, int u1, int v1, String carpet) {
            for (int u = u0; u <= u1; u++) for (int v = v0; v <= v1; v++) if (ok(u, v)) put(u, 1, v, carpet);
        }

        void fill() {
            switch (theme) {
                case "inn" -> inn();
                case "keep" -> keep();
                default -> home();
            }
        }

        void inn() {
            String[] stands = {"barrel[facing=up,open=false]", "potted_red_tulip", "chest[facing=" + r.right() + ",type=single,waterlogged=false]",
                    "potted_fern", "lantern[hanging=false,waterlogged=false]"};
            int k = 0;
            for (int u : new int[]{r.uMin, r.uMax})
                for (int v = 1; v + 1 <= r.vMax; v += 3) {
                    if (bed(u, v, (k & 1) == 0 ? "red" : "brown") && ok(u, v + 2)) {
                        String s = stands[k % stands.length];
                        if (s.startsWith("chest") && u == r.uMax) s = "chest[facing=" + r.left() + ",type=single,waterlogged=false]";
                        put(u, 1, v + 2, s);
                    }
                    k++;
                }
            int mid = r.vMax / 2;
            table(0, mid);
            rug(-1, mid - 2, 1, mid - 1, "green_carpet");
            rug(-1, mid + 1, 1, mid + 2, "green_carpet");
        }

        void home() {
            int mid = r.vMax / 2;
            if (!bed(r.uMin, mid, colour("bed"))) bed(r.uMax, mid, colour("bed"));
            if (ok(r.uMax, r.vMax)) put(r.uMax, 1, r.vMax, "chest[facing=" + r.left() + ",type=single,waterlogged=false]");
            if (ok(r.uMax - 1, r.vMax)) put(r.uMax - 1, 1, r.vMax, "bookshelf");
            if (ok(r.uMin, r.vMax)) put(r.uMin, 1, r.vMax, rnd("pot", 0, 0) < 0.5 ? "potted_azure_bluet" : "potted_oak_sapling");
            if (ok(r.uMax, 1)) put(r.uMax, 1, 1, "barrel[facing=up,open=false]");
            table(0, Math.max(1, mid - 1));
            rug(-1, mid + 1, 1, mid + 2, colour("rug") + "_carpet");
        }

        void keep() {
            // the study wall: two rows of shelves across the back
            for (int u = r.uMin; u <= r.uMax; u++)
                if (ok(u, r.vMax)) { put(u, 1, r.vMax, "bookshelf"); put(u, 2, r.vMax, (u & 1) == 0 ? "bookshelf" : "chiseled_bookshelf[facing=" + r.back() + "]"); }
            int desk = r.vMax - 3;
            if (ok(0, desk)) put(0, 1, desk, "lectern[facing=" + r.back() + ",has_book=true,powered=false]");
            if (ok(-1, desk)) put(-1, 1, desk, "polished_andesite_slab[type=top,waterlogged=false]");
            if (ok(1, desk)) put(1, 1, desk, "polished_andesite_slab[type=top,waterlogged=false]");
            bed(r.uMax, 2, "blue");
            if (ok(r.uMax, 4)) put(r.uMax, 1, 4, "chest[facing=" + r.left() + ",type=single,waterlogged=false]");
            if (ok(r.uMin, 3)) put(r.uMin, 1, 3, "barrel[facing=up,open=false]");
            if (ok(r.uMin, 4)) put(r.uMin, 1, 4, "potted_blue_orchid");
            for (int v = 1; v < desk - 1; v++) if (ok(0, v)) put(0, 1, v, "red_carpet");
        }
    }
}
