package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nether Lord (Ember, full set), wave 5: "Walking over Lava creates Obsidian under your feet. Disabled in
 * some zones and near Fishing Hotspots." Frost-walker style: still lava SOURCE blocks in a small disc under
 * the wearer become obsidian and turn back into lava {@code armor.ember-obsidian-seconds} later (held while
 * anyone still stands on them).
 *
 * <p>Public-server safety: never on the private island or the garden (player-built worlds, where obsidian
 * could be farmed); never while a fishing rod is in hand (our stand-in for "near Fishing Hotspots": the
 * lava you fish in stays lava); the obsidian can never be broken; every converted block is also written to
 * its chunk's persistent data, so a crash or a chunk unload can never leave obsidian behind - the chunk
 * puts its lava back the next time it loads.</p>
 */
final class NetherLord implements Listener {

    static final NamespacedKey KEY = new NamespacedKey("skyclone", "armor_obsidian");
    static final Set<String> DISABLED_ISLANDS = Set.of("private_island", "garden");

    /** "world:x:y:z" -> revert time (millis). */
    private static final Map<String, Long> LIVE = new HashMap<>();

    static void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), NetherLord::revertDue, 40L, 10L);
    }

    static int live() { return LIVE.size(); }

    static boolean active(Player p, Worn w) {
        if (!ArmorDials.ENABLED.get() || !ArmorDials.EMBER_OBSIDIAN.get() || !w.full(SetIds.EMBER)) return false;
        if (p.getGameMode() == GameMode.SPECTATOR || p.isFlying()) return false;
        if (DISABLED_ISLANDS.contains(ArmorUtil.island(p))) return false;
        return !holdsRod(p);
    }

    static boolean holdsRod(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h.getType() == Material.FISHING_ROD) return true;
        String id = Sky.items().idOf(h);
        ItemDef d = id == null ? null : Sky.items().def(id);
        return d != null && d.category == ItemCategory.FISHING_ROD;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        Worn w = Worn.of(p);
        if (!active(p, w)) return;
        Block under = e.getTo().getBlock().getRelative(0, -1, 0);
        long until = System.currentTimeMillis() + ArmorDials.EMBER_OBSIDIAN_SECONDS.get() * 1000L;
        for (int[] o : SetMath.discOffsets(ArmorDials.EMBER_OBSIDIAN_RADIUS.get())) {
            Block b = under.getRelative(o[0], 0, o[1]);
            String k = key(b);
            if (LIVE.containsKey(k)) { LIVE.put(k, until); continue; }
            if (b.getType() != Material.LAVA || !(b.getBlockData() instanceof Levelled lv) || lv.getLevel() != 0) continue;
            if (!b.getRelative(0, 1, 0).isPassable()) continue;
            b.setType(Material.OBSIDIAN, false);
            LIVE.put(k, until);
            mark(b.getChunk(), b, true);
        }
    }

    /** The obsidian is never a block anyone can mine. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (LIVE.containsKey(key(e.getBlock()))) e.setCancelled(true);
    }

    /** Leaving: put the lava back while the chunk is still here. */
    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        revertChunk(e.getChunk());
    }

    /** Crash recovery: a chunk that still lists obsidian of ours gets its lava back. */
    @EventHandler
    public void onLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        String raw = pdc.get(KEY, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return;
        for (String cell : raw.split(";")) {
            int[] xyz = parse(cell);
            if (xyz == null) continue;
            Block b = c.getWorld().getBlockAt(xyz[0], xyz[1], xyz[2]);
            if (!LIVE.containsKey(key(b)) && b.getType() == Material.OBSIDIAN) b.setType(Material.LAVA, false);
        }
        pdc.remove(KEY);
    }

    static void revertDue() {
        if (LIVE.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, Long> en : LIVE.entrySet()) if (en.getValue() <= now) due.add(en.getKey());
        for (String k : due) {
            Block b = block(k);
            if (b == null) { LIVE.remove(k); continue; }
            if (standingOn(b)) { LIVE.put(k, now + 1000L); continue; }
            revert(b, k);
        }
    }

    static void revertAll() {
        for (String k : new ArrayList<>(LIVE.keySet())) {
            Block b = block(k);
            if (b != null) revert(b, k); else LIVE.remove(k);
        }
    }

    private static void revertChunk(Chunk c) {
        String prefix = c.getWorld().getName() + ":";
        for (Iterator<Map.Entry<String, Long>> it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            String k = it.next().getKey();
            if (!k.startsWith(prefix)) continue;
            int[] xyz = parse(k.substring(prefix.length()));
            if (xyz == null || (xyz[0] >> 4) != c.getX() || (xyz[2] >> 4) != c.getZ()) continue;
            Block b = c.getWorld().getBlockAt(xyz[0], xyz[1], xyz[2]);
            if (b.getType() == Material.OBSIDIAN) b.setType(Material.LAVA, false);
            it.remove();
        }
        c.getPersistentDataContainer().remove(KEY);
    }

    private static void revert(Block b, String k) {
        if (b.getType() == Material.OBSIDIAN) b.setType(Material.LAVA, false);
        LIVE.remove(k);
        mark(b.getChunk(), b, false);
    }

    private static boolean standingOn(Block b) {
        for (Player p : b.getWorld().getPlayers()) {
            Block under = p.getLocation().getBlock().getRelative(0, -1, 0);
            if (under.equals(b) || (p.getLocation().getBlock().equals(b.getRelative(0, 1, 0)))) return true;
        }
        return false;
    }

    /** Adds / removes one cell in the chunk's list, so a crash can be undone on the next load. */
    private static void mark(Chunk c, Block b, boolean add) {
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        String cell = b.getX() + "," + b.getY() + "," + b.getZ();
        String raw = pdc.getOrDefault(KEY, PersistentDataType.STRING, "");
        List<String> cells = new ArrayList<>(raw.isEmpty() ? List.of() : List.of(raw.split(";")));
        if (add) { if (!cells.contains(cell)) cells.add(cell); } else cells.remove(cell);
        if (cells.isEmpty()) pdc.remove(KEY); else pdc.set(KEY, PersistentDataType.STRING, String.join(";", cells));
    }

    static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ(); }

    private static Block block(String k) {
        int i = k.lastIndexOf(':');
        World w = Bukkit.getWorld(k.substring(0, i));
        int[] xyz = parse(k.substring(i + 1));
        if (w == null || xyz == null || !w.isChunkLoaded(xyz[0] >> 4, xyz[2] >> 4)) return null;
        return w.getBlockAt(xyz[0], xyz[1], xyz[2]);
    }

    static int[] parse(String cell) {
        String[] s = cell.split(",");
        if (s.length != 3) return null;
        try { return new int[]{Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim()), Integer.parseInt(s[2].trim())}; }
        catch (NumberFormatException e) { return null; }
    }
}
