package com.skyclone.mod.mining;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event blocks that exist for a while and then put back what was there: the Fallen Star.
 * They are mined with the normal engine (their def overrides the island table).
 */
final class TempBlocks {

    private record Entry(BlockTable.Def def, String original, String group) {}

    private final Map<Regen.Key, Entry> blocks = new HashMap<>();

    static BlockTable.Def starDef() {
        BlockTable.Def d = new BlockTable.Def();
        d.id = "fallen_star";
        d.name = "Fallen Star";
        d.kind = BlockTable.Kind.STAR;
        d.strength = 600;          // TUNE: as hard as a pure ore
        d.breakingPower = 4;
        d.drop = "STARFALL";
        d.min = 1;
        d.max = 2;
        d.xp = 60;                 // TUNE
        d.powder = "MITHRIL";
        d.powderAmount = 10;       // TUNE
        d.fuel = 1;
        return d;
    }

    BlockTable.Def defAt(Block b) {
        if (blocks.isEmpty()) return null;
        Entry e = blocks.get(Regen.key(b));
        if (e == null || b.getType() == Material.AIR) return null;
        return e.def;
    }

    void place(Block b, Material mat, BlockTable.Def def, String group) {
        Regen.Key k = Regen.key(b);
        if (!blocks.containsKey(k)) blocks.put(k, new Entry(def, b.getBlockData().getAsString(), group));
        b.setType(mat, false);
    }

    void mined(Block b) { b.setType(Material.AIR, false); }

    /** How many blocks of a group are still standing. */
    int standing(String group) {
        int n = 0;
        for (Map.Entry<Regen.Key, Entry> e : blocks.entrySet()) {
            if (!e.getValue().group.equals(group)) continue;
            World w = Bukkit.getWorld(e.getKey().world());
            if (w != null && w.getBlockAt(e.getKey().x(), e.getKey().y(), e.getKey().z()).getType() != Material.AIR) n++;
        }
        return n;
    }

    void clear(String group) {
        List<Regen.Key> done = new ArrayList<>();
        for (Map.Entry<Regen.Key, Entry> e : blocks.entrySet()) {
            if (group != null && !e.getValue().group.equals(group)) continue;
            World w = Bukkit.getWorld(e.getKey().world());
            if (w != null) {
                try {
                    BlockData bd = Bukkit.createBlockData(e.getValue().original);
                    w.getBlockAt(e.getKey().x(), e.getKey().y(), e.getKey().z()).setBlockData(bd, false);
                } catch (IllegalArgumentException ignored) { }
            }
            done.add(e.getKey());
        }
        done.forEach(blocks::remove);
    }
}
