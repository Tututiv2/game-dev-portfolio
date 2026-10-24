package com.skyclone.mod.island;

import com.skyclone.api.Sky;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.mod.island.gen.IslandBridge;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Re-lays the two bridges of islands built before generator v5 (wave-3 playtest: the old diagonal deck was ~1.4
 * blocks wide at its steps and players walking the Portal Isle bridge fell into the void). Built instances keep
 * their blocks, so without this every island made before the fix keeps the bridge you fall off.
 *
 * <p>The decision is pure and unit-tested ({@link IslandBridge#repairPlan}): only air and the old bridge's own
 * blocks at old-bridge positions change, and nothing happens unless the old bridge is actually there. This class
 * only copies the world into a canvas, asks for the plan and places it. Once per island per boot, when someone is
 * on it and the bridge's chunks are loaded (dial {@code island.repair-bridges}); admin {@code /sb isle bridges <p>}.
 */
final class IslandBridges {

    private static final String[] KEYS = {IslandBridge.DATA_ISLET, IslandBridge.DATA_PORTAL_ISLE};

    private final IslandService svc;
    private final IslandDials dials;
    private final Set<String> done = new HashSet<>();

    IslandBridges(IslandService svc, IslandDials dials) { this.svc = svc; this.dials = dials; }

    /** Every 2 s: each island someone stands on gets one look per boot. */
    void tick() {
        if (!dials.repairBridges.get()) return;
        World w = svc.world();
        if (w == null) return;
        Set<String> seen = new HashSet<>();
        for (Player p : w.getPlayers()) {
            if (!Sky.profiles().isLoaded(p)) continue;
            String key = svc.keyAt(p.getLocation());
            if (key == null || done.contains(key) || !seen.add(key)) continue;
            if (repair(key, null) >= 0) done.add(key);
        }
        if (done.size() > 20000) done.clear();
    }

    /**
     * Repair island {@code key}'s bridges now. Returns the number of blocks changed, or -1 when a bridge's chunks are
     * not loaded yet (try again later). {@code report}, when given, gets one line per bridge.
     */
    int repair(String key, org.bukkit.command.CommandSender report) {
        World w = svc.world();
        long[] o = svc.origin(key);
        Layout l = Sky.worlds().layout(IslandService.ISLAND);
        if (w == null || o == null || l == null) return -1;
        int total = 0;
        for (String dk : KEYS) {
            int[] s = IslandBridge.decode(l.data().get(dk)), old = IslandBridge.decode(l.data().get(dk + IslandBridge.LEGACY));
            if (s == null || old == null) { say(report, "&7" + dk + ": not in the template layout"); continue; }
            Box b = IslandBridge.box(s, old);
            if (!loaded(w, o, b)) return -1;
            MemoryCanvas world = new MemoryCanvas();
            for (int y = b.minY(); y <= b.maxY(); y++)
                for (int z = b.minZ(); z <= b.maxZ(); z++)
                    for (int x = b.minX(); x <= b.maxX(); x++) {
                        Block blk = at(w, o, x, y, z);
                        if (!blk.getType().isAir()) world.set(x, y, z, blk.getBlockData().getAsString());
                    }
            IslandBridge.Plan plan = IslandBridge.repairPlan(world, s, old, b);
            if (!plan.oldPresent()) {
                say(report, "&7" + dk + ": no old bridge here (" + plan.oldFound + "/" + plan.oldBlocks + " of its blocks) - left alone");
                continue;
            }
            int changed = 0;
            for (int i = 0; i < plan.cells.size(); i++) {
                int[] c = plan.cells.get(i);
                Block blk = at(w, o, c[0], c[1], c[2]);
                String st = plan.states.get(i);
                if ("air".equals(st)) {
                    if (blk.getType().isAir()) continue;
                    blk.setType(Material.AIR, false);
                    changed++;
                    continue;
                }
                BlockData bd = IslandPortals.blockData(st);
                if (bd == null || bd.getAsString().equals(blk.getBlockData().getAsString())) continue;
                blk.setBlockData(bd, false);
                changed++;
            }
            say(report, "&a" + dk + ": " + (changed == 0 ? "already right" : "re-laid (" + changed + " blocks)"));
            if (changed > 0) Sky.log().info("[island] re-laid " + dk + " on island " + key + " (" + changed + " blocks)");
            total += changed;
        }
        return total;
    }

    private static Block at(World w, long[] o, int x, int y, int z) { return w.getBlockAt((int) o[0] + x, y, (int) o[1] + z); }

    private static boolean loaded(World w, long[] o, Box b) {
        for (int cx = (int) (o[0] + b.minX()) >> 4; cx <= (int) (o[0] + b.maxX()) >> 4; cx++)
            for (int cz = (int) (o[1] + b.minZ()) >> 4; cz <= (int) (o[1] + b.maxZ()) >> 4; cz++)
                if (!w.isChunkLoaded(cx, cz)) return false;
        return true;
    }

    private static void say(org.bukkit.command.CommandSender s, String msg) {
        if (s != null) s.sendMessage(com.skyclone.api.ui.Text.c(msg));
    }
}
