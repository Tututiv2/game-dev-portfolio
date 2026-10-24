package com.skyclone.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * Connects fences, glass panes, iron bars and walls to their neighbours. In the game blocks are
 * placed WITHOUT physics (so nothing falls or flows mid-build), which also means nothing
 * re-computes its connections — a fence written as plain "oak_fence" would stand as a lone post.
 * Core runs this pass over the finished canvas before placing it (and the preview tool does the
 * same), so builders just write the plain id. Blocks that already carry explicit side properties
 * are left alone.
 */
public final class Connect {

    private Connect() {}

    private static final String[] DIRS = Blocks.DIRS;
    private static final int[] DX = Blocks.DX, DZ = Blocks.DZ;

    private enum Fam { FENCE_WOOD, FENCE_NETHER, PANE, WALL, NONE }

    private static Fam fam(String name) {
        if (name.endsWith("_fence")) return name.equals("nether_brick_fence") ? Fam.FENCE_NETHER : Fam.FENCE_WOOD;
        if (name.endsWith("_pane") || name.equals("iron_bars") || name.endsWith("_bars")) return Fam.PANE;
        if (name.endsWith("_wall") && !name.endsWith("_wall_banner")) return Fam.WALL;
        return Fam.NONE;
    }

    /** @return number of blocks rewritten. */
    public static int apply(MemoryCanvas c) {
        List<int[]> todo = new ArrayList<>();
        c.forEachId((x, y, z, id) -> {
            String b = c.palette(id);
            String n = BlockState.name(b);
            if (fam(n) == Fam.NONE) return;
            if (BlockState.prop(b, "north") != null) return;   // explicit: leave it
            todo.add(new int[]{x, y, z});
        });
        for (int[] p : todo) {
            String b = c.get(p[0], p[1], p[2]);
            String n = BlockState.name(b);
            Fam f = fam(n);
            boolean[] side = new boolean[4];
            for (int d = 0; d < 4; d++) side[d] = connects(c, f, p[0] + DX[d], p[1], p[2] + DZ[d], d);
            String out;
            if (f == Fam.WALL) {
                boolean above = !c.isAir(p[0], p[1] + 1, p[2]);
                String tall = above ? "tall" : "low";
                boolean straightNS = side[0] && side[2] && !side[1] && !side[3];
                boolean straightEW = side[1] && side[3] && !side[0] && !side[2];
                boolean up = !(straightNS || straightEW) || above && fam(BlockState.name(c.get(p[0], p[1] + 1, p[2]))) == Fam.WALL;
                StringBuilder sb = new StringBuilder(n).append('[');
                for (int d = 0; d < 4; d++) sb.append(DIRS[d]).append('=').append(side[d] ? tall : "none").append(',');
                sb.append("up=").append(up).append(",waterlogged=false]");
                out = sb.toString();
            } else {
                StringBuilder sb = new StringBuilder(n).append('[');
                for (int d = 0; d < 4; d++) sb.append(DIRS[d]).append('=').append(side[d]).append(',');
                sb.append("waterlogged=false]");
                out = sb.toString();
            }
            c.set(p[0], p[1], p[2], out);
        }
        return todo.size();
    }

    private static boolean connects(MemoryCanvas c, Fam self, int x, int y, int z, int dirToNeighbour) {
        String nb = c.get(x, y, z);
        String n = BlockState.name(nb);
        Fam f = fam(n);
        if (n.endsWith("_fence_gate")) {
            // a gate connects when its wall-line runs across the direction we are looking
            String facing = BlockState.prop(nb, "facing");
            if (facing == null) return true;
            int fi = Blocks.index(facing);
            return (fi & 1) != (dirToNeighbour & 1);
        }
        switch (self) {
            case FENCE_WOOD:
                if (f == Fam.FENCE_WOOD) return true;
                break;
            case FENCE_NETHER:
                if (f == Fam.FENCE_NETHER) return true;
                break;
            case PANE:
                if (f == Fam.PANE || f == Fam.WALL) return true;
                break;
            case WALL:
                if (f == Fam.WALL || f == Fam.PANE) return true;
                break;
            default:
        }
        BlockInfo info = BlockInfo.of(nb);
        return info.cube && info.top16 == 16 && !n.endsWith("_leaves");
    }
}
