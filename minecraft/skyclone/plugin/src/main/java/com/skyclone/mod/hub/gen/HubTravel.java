package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * The hub's second set of exits, besides the Portal Plaza (research 03 section 1.1): launch pads
 * that fling you to a neighbouring island (Graveyard to the Spider's Den, the Farm road to the
 * Barn, the Foraging Camp to the Park) and the tunnel gate at the back of the Coal Mine that leads
 * on to the Gold Mine. The hub module runs them: an area {@code pad.<warp>} launches, an area
 * {@code gate.<warp>} warps on contact. Each also carries a same-named anchor so the walk check
 * proves a player can reach it, and pads get a {@code label.pad.<warp>} anchor for a floating sign.
 */
final class HubTravel {

    private HubTravel() {}

    /** One pad: warp id, the spot to search around, trim block, trim wall for the corner posts. */
    record Pad(String warp, int x, int z, String trim, String post) {}

    /** Additive pass: after the districts, before roads and trees (so both keep off the pads). */
    static void pads(HubCtx g) {
        pad(g, new Pad("spiders_den", GRAVEYARD.maxX() - 4, HubEast.GATE_Z, "polished_blackstone_bricks", "polished_blackstone_brick_wall"), g.graveY);
        pad(g, new Pad("barn", 199, 117, "stripped_oak_wood[axis=y]", "oak_fence"), g.farmY);
        pad(g, new Pad("park", -222, 5, "mossy_stone_bricks", "mossy_stone_brick_wall"), g.campY);
    }

    /**
     * A 5x5 launch pad near (p.x, p.z): a 3x3 slime bed in a trim ring with four lit posts. Searches
     * outward for level ground clear of buildings, graves and lamps; fails loudly if there is none.
     */
    static void pad(HubCtx g, Pad p, int y) {
        for (int r = 0; r <= 14; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = p.x() + dx, cz = p.z() + dz;
                    if (!fits(g, cx, cz, y)) continue;
                    if (!g.tryReserve("pad." + p.warp(), Box.of(cx - 2, y - 3, cz - 2, cx + 2, y + 4, cz + 2))) continue;
                    build(g, p, cx, y, cz);
                    return;
                }
        throw new IllegalStateException("no room for launch pad " + p.warp());
    }

    static boolean fits(HubCtx g, int cx, int cz, int y) {
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx, z = cz + dz;
                if (!g.h.has(x, z) || g.h.wet(x, z) || g.res.covers(x, z, 0)) return false;
                if (Math.abs(g.ground(x, z) - y) > 1) return false;
                String top = BlockState.name(g.c.get(x, y, z));
                if (top.contains("water") || top.endsWith("_log") || top.endsWith("_leaves")) return false;
                for (int a = 1; a <= 4; a++) {
                    String b = g.c.get(x, y + a, z);
                    if (!g.c.isAir(x, y + a, z) && !Builders.natural(b)) return false;
                }
            }
        return true;
    }

    static void build(HubCtx g, Pad p, int cx, int y, int cz) {
        Fill dirt = Fill.of("dirt");
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx, z = cz + dz;
                boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                g.c.set(x, y, z, ring ? p.trim() : "slime_block");
                Builders.footing(g.c, x, y, z, dirt, 10);
                g.clearAbove(x, y, z, 5);
                for (int a = 1; a <= 5; a++) if (!g.c.isAir(x, y + a, z) && Builders.natural(g.c.get(x, y + a, z))) g.c.set(x, y + a, z, "air");
            }
        for (int[] q : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            g.c.set(cx + q[0], y + 1, cz + q[1], p.post());
            g.c.set(cx + q[0], y + 2, cz + q[1], "end_rod[facing=up]");
        }
        g.area("pad." + p.warp(), Box.of(cx - 1, y + 1, cz - 1, cx + 1, y + 2, cz + 1));
        g.npc("pad." + p.warp(), cx, y, cz, 0f);
        g.layout.anchor("label.pad." + p.warp(), cx + 0.5, y + 3.4, cz + 0.5, 0f);
    }

    /**
     * Carving pass, after the Coal Mine: a short drift north out of the chamber to a deepslate gate
     * set into the rock face. Walking into it warps to the Gold Mine (research 4.2: "tunnel to Gold
     * Mine"). It sits beyond the mining module's ore area (which it grows by 3), so its frame is
     * never mineable.
     */
    static void mineGate(HubCtx g) {
        int F = g.miningY;
        int x = 77, zFrom = -182, zGate = -194;
        Palette rock = Palette.of("stone:6,andesite:3,cobblestone:2,tuff:1");
        Palette ore = Palette.of("coal_ore:10,stone:40,cobblestone:10,andesite:10,iron_ore:2");
        Palette floor = Palette.of("stone:3,gravel:2,cobblestone:2,andesite:1");
        // the drift: 5 wide and 5 high like the main tunnel, lined where the mountain is thin
        for (int z = zFrom; z > zGate; z--)
            for (int dx = -3; dx <= 3; dx++)
                for (int y = F; y <= F + 6; y++) {
                    int xx = x + dx;
                    boolean inside = Math.abs(dx) <= 2 && y >= F + 1 && y <= F + 5;
                    if (inside) { g.c.set(xx, y, z, "air"); continue; }
                    if (y == F && Math.abs(dx) <= 2) { g.c.set(xx, y, z, floor.at(xx, y, z)); continue; }
                    if (z <= -185 && g.c.isAir(xx, y, z)) g.c.set(xx, y, z, rock.at(xx, y, z));
                    else if (z <= -185 && "stone".equals(BlockState.name(g.c.get(xx, y, z)))) g.c.set(xx, y, z, ore.at(xx, y, z));
                }
        // timber sets every 5 blocks, rails down the middle (stopping short of the gate)
        for (int z = zFrom - 2; z > zGate + 2; z -= 5) {
            for (int y = F + 1; y <= F + 4; y++) { g.c.set(x - 2, y, z, "oak_log[axis=y]"); g.c.set(x + 2, y, z, "oak_log[axis=y]"); }
            for (int dx = -2; dx <= 2; dx++) g.c.set(x + dx, F + 5, z, "stripped_oak_log[axis=x]");
        }
        for (int z = zFrom; z > zGate + 4; z--)
            if (g.c.isAir(x, F + 1, z) && BlockInfo.of(g.c.get(x, F, z)).top16 == 16) g.c.set(x, F + 1, z, "rail[shape=north_south,waterlogged=false]");
        HubNorth.portal(g, new HubNorth.Portal("gold_mine", "deepslate_tiles:3,polished_deepslate:2,deepslate_bricks:1",
                "chiseled_deepslate", "orange"), x, zGate, F, "south", "gate.", false);
        g.c.set(x, F + 5, zGate + 3, com.skyclone.gen.Blocks.lantern(true));
        g.npc("gate.gold_mine", x, F, zGate + 3, 180f);
        g.layout.anchor("label.gate.gold_mine", x + 0.5, F + 4.1, zGate + 2.5, 0f);
    }
}
