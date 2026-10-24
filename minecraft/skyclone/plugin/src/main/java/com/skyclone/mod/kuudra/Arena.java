package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.kuudra.gen.BallistaShape;
import com.skyclone.mod.kuudra.gen.KuudraArenaGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * One instance slot of the {@code kuudra} island in world coordinates: anchors, the ballista's blocks, the
 * Gullet's organs, and the reset that makes a used arena fresh again (the arenas are kept BUILT between
 * hunts and reset by us, so a hunt starts instantly instead of re-placing ~330k blocks).
 */
final class Arena {

    static NamespacedKey TAG;

    final String key;
    Location origin;

    Arena(String key) { this.key = key; }

    World world() { return origin == null ? null : origin.getWorld(); }

    Location anchor(String name) {
        Location l = Sky.worlds().instanceAnchor(KuudraArenaGenerator.ISLAND, key, name);
        return l == null ? null : l.clone();
    }

    List<Location> anchors(String prefix) {
        List<Location> out = new ArrayList<>();
        Layout l = Sky.worlds().layout(KuudraArenaGenerator.ISLAND);
        if (l == null) return out;
        for (String n : l.anchors().keySet()) if (n.startsWith(prefix)) { Location a = anchor(n); if (a != null) out.add(a); }
        return out;
    }

    /** A template block position from layout data ("x,y,z"), in world coordinates. */
    Location dataBlock(String dataKey) {
        Layout l = Sky.worlds().layout(KuudraArenaGenerator.ISLAND);
        String v = l == null ? null : l.data().get(dataKey);
        return v == null ? null : parse(v);
    }

    List<Location> dataBlocks(String dataKey) {
        List<Location> out = new ArrayList<>();
        Layout l = Sky.worlds().layout(KuudraArenaGenerator.ISLAND);
        String v = l == null ? null : l.data().get(dataKey);
        if (v == null) return out;
        for (String part : v.split(";")) { Location x = parse(part); if (x != null) out.add(x); }
        return out;
    }

    Box area(String name) {
        Layout l = Sky.worlds().layout(KuudraArenaGenerator.ISLAND);
        return l == null ? null : l.area(name);
    }

    /** Is a world location inside a template area of this instance? */
    boolean inArea(Location loc, String area, double grow) {
        Box b = area(area);
        if (b == null || origin == null || loc.getWorld() != world()) return false;
        double x = loc.getX() - origin.getBlockX(), y = loc.getY(), z = loc.getZ() - origin.getBlockZ();
        return x >= b.minX() - grow && x < b.maxX() + 1 + grow && y >= b.minY() - grow && y < b.maxY() + 1 + grow
                && z >= b.minZ() - grow && z < b.maxZ() + 1 + grow;
    }

    private Location parse(String v) {
        try {
            String[] p = v.trim().split(",");
            return new Location(world(), origin.getBlockX() + Integer.parseInt(p[0]), origin.getBlockY() + Integer.parseInt(p[1]),
                    origin.getBlockZ() + Integer.parseInt(p[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    Location ballistaBase() {
        Location b = dataBlock("kuudra.ballista");
        return b != null ? b : new Location(world(), origin.getBlockX() + KuudraArenaGenerator.BX, KuudraArenaGenerator.BY,
                origin.getBlockZ() + KuudraArenaGenerator.BZ);
    }

    /** Is this block one of the ballista's (foundation or built)? */
    boolean isBallistaBlock(Block b) {
        Location base = ballistaBase();
        if (b.getWorld() != base.getWorld()) return false;
        int dx = b.getX() - base.getBlockX(), dy = b.getY() - base.getBlockY(), dz = b.getZ() - base.getBlockZ();
        if (Math.abs(dx) > BallistaShape.reach() || dy < 0 || dy > 6 || dz < -5 || dz > 3) return false;
        return true;
    }

    /** Place the first {@code n} built parts and clear the rest (bottom-up order). */
    void setBallista(int n) {
        Location base = ballistaBase();
        List<BallistaShape.Part> parts = BallistaShape.built();
        for (int i = 0; i < parts.size(); i++) {
            BallistaShape.Part p = parts.get(i);
            Block b = base.getWorld().getBlockAt(base.getBlockX() + p.dx(), base.getBlockY() + p.dy(), base.getBlockZ() + p.dz());
            if (i < n) set(b, p.block()); else if (!b.getType().isAir()) b.setType(org.bukkit.Material.AIR, false);
        }
        for (BallistaShape.Part p : BallistaShape.foundation())
            set(base.getWorld().getBlockAt(base.getBlockX() + p.dx(), base.getBlockY() + p.dy(), base.getBlockZ() + p.dz()), p.block());
    }

    List<Location> orbs() { return dataBlocks("kuudra.orbs"); }

    void restoreOrbs() {
        for (Location o : orbs()) set(o.getBlock(), "ochre_froglight");
    }

    private static final Map<String, BlockData> DATA = new HashMap<>();

    static void set(Block b, String state) {
        BlockData d = DATA.computeIfAbsent(state, s -> {
            try { return Bukkit.createBlockData(s.contains(":") ? s : "minecraft:" + s); }
            catch (IllegalArgumentException e) { Sky.log().log(Level.WARNING, "[kuudra] bad block state " + s); return Bukkit.createBlockData(org.bukkit.Material.STONE); }
        });
        if (!b.getBlockData().equals(d)) b.setBlockData(d, false);
    }

    /** Make a used arena fresh: ballista down to its foundation, organs back, our leftover entities gone. */
    void reset() {
        if (origin == null) return;
        setBallista(0);
        restoreOrbs();
        World w = world();
        for (Entity e : w.getNearbyEntities(origin.clone().add(0, 64, 0), 110, 80, 110)) {
            if (e instanceof org.bukkit.entity.Player) continue;
            if (TAG != null && e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) { e.remove(); continue; }
            if (e instanceof org.bukkit.entity.LivingEntity le && Sky.mobs().isSkyMob(le)) Sky.mobs().remove(le);
            else if (e instanceof org.bukkit.entity.Item) e.remove();
        }
    }
}
