package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Real buried treasure in the Mines of Varnas (wave 5; reference: the Metal Detector leads you to a dwarven
 * treasure chest hidden in the hall). The generator buries a real chest one block under the hall floor at every
 * {@code varnas.treasure.N} spot, under a floor tile that looks like any other. The detector points a player at
 * THEIR spot; they hit the tile above it open ({@code hollows.treasure-hits} hits, any item: the caverns' custom
 * mining never touches these tiles), and opening the chest pays out the lost tool. The tile closes again after
 * {@code hollows.treasure-reseal-seconds}, and on shutdown. Other spots' chests are empty to that player.
 */
final class VarnasDig implements Listener {

    private record Open(Location at, BlockData was, long until) {}
    private record Chip(long key, int hits, long at) {}

    private final HollowsModule m;
    private final Map<Long, Open> open = new HashMap<>();
    private final Map<UUID, Chip> chips = new HashMap<>();
    private final Map<UUID, Long> quiet = new HashMap<>();

    VarnasDig(HollowsModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 20L);
    }

    /** The built caverns bury real chests (generator v3+). */
    boolean buried() {
        Layout l = Sky.worlds().layout(Quests.CH);
        return l != null && l.data().get("hollows.varnas.buried") != null;
    }

    private static long key(Block b) { return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF); }

    /** The floor tile over the player's current target, or null. */
    private Block targetCover(Player p) {
        String t = m.quests.data(p).detectorTarget;
        Location a = t == null ? null : Sky.worlds().anchor(Quests.CH, t);
        if (a == null || a.getWorld() != p.getWorld()) return null;
        return a.getBlock().getRelative(0, -1, 0);
    }

    /** Is this block a Varnas floor tile with a buried chest under it? */
    private boolean isCover(Block b) {
        Box hall = Sky.worlds().area(Quests.CH, "varnas.hall");
        if (hall == null || b.getY() != hall.minY() - 1) return false;
        if (b.getX() < hall.minX() || b.getX() > hall.maxX() || b.getZ() < hall.minZ() || b.getZ() > hall.maxZ()) return false;
        return b.getRelative(0, -1, 0).getType() == Material.CHEST;
    }

    /** Right-click with the detector on top of the spot: show which tile to dig. */
    void hint(Player p) {
        Block c = targetCover(p);
        if (c == null) { Msg.error(p, "The detector can't find anything here yet."); return; }
        Location top = c.getLocation().add(0.5, 1.05, 0.5);
        p.spawnParticle(Particle.BLOCK, top, 25, 0.3, 0.02, 0.3, 0, c.getBlockData());
        p.spawnParticle(Particle.HAPPY_VILLAGER, top, 8, 0.3, 0.1, 0.3, 0);
        Msg.send(p, "&a&lRIGHT HERE! &7The signal comes from under this floor tile. &eHit it " + Math.max(1, m.dials.treasureHits.get())
                + " times &7to break it open, then open what is underneath.");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (!Quests.CH.equals(Quests.island(p)) || !Sky.profiles().isReady(p)) return;
        Block b = e.getClickedBlock();
        if (e.getAction() == Action.LEFT_CLICK_BLOCK && isCover(b)) { chip(p, b); return; }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType() == Material.CHEST && isCover(b.getRelative(0, 1, 0))) {
            e.setCancelled(true);
            openChest(p, b);
        }
    }

    private void chip(Player p, Block b) {
        Block target = targetCover(p);
        long now = System.currentTimeMillis();
        if (target == null || !target.equals(b)) {
            if (now - quiet.getOrDefault(p.getUniqueId(), 0L) > 2000) {
                quiet.put(p.getUniqueId(), now);
                Sky.vitals().flash(p, "&8The floor is solid here. " + (target == null ? "&7Hold a Metal Detector to find a treasure." : "&7The detector points elsewhere."), 30);
            }
            return;
        }
        long k = key(b);
        Chip c = chips.get(p.getUniqueId());
        int hits = c != null && c.key == k && now - c.at < 10_000 ? c.hits + 1 : 1;
        chips.put(p.getUniqueId(), new Chip(k, hits, now));
        Location mid = b.getLocation().add(0.5, 1.0, 0.5);
        b.getWorld().spawnParticle(Particle.BLOCK, mid, 12, 0.3, 0.05, 0.3, 0, b.getBlockData());
        b.getWorld().playSound(mid, Sound.BLOCK_DEEPSLATE_TILES_HIT, 1f, 0.8f + hits * 0.08f);
        int need = Math.max(1, m.dials.treasureHits.get());
        if (hits < need) { Sky.vitals().flash(p, "&7Digging... &e" + hits + "&7/&e" + need, 20); return; }
        chips.remove(p.getUniqueId());
        open.put(k, new Open(b.getLocation(), b.getBlockData(), now + Math.max(5, m.dials.treasureReseal.get()) * 1000L));
        b.setType(Material.AIR, false);
        b.getWorld().playSound(mid, Sound.BLOCK_DEEPSLATE_TILES_BREAK, 1f, 0.9f);
        b.getWorld().spawnParticle(Particle.BLOCK, mid.clone().add(0, -0.5, 0), 30, 0.3, 0.2, 0.3, 0, Material.DEEPSLATE_TILES.createBlockData());
        Msg.send(p, "&6The tile cracks open: &7there is a chest underneath! &eRight-click it.");
    }

    private void openChest(Player p, Block chest) {
        Block cover = chest.getRelative(0, 1, 0);
        if (!cover.getType().isAir()) return;                     // still buried (a client glitch)
        Block target = targetCover(p);
        if (target == null || !target.equals(cover)) {
            Msg.send(p, "&7Empty. &8Somebody picked this cache clean long ago.");
            Msg.sound(p, Sound.BLOCK_CHEST_LOCKED, 1f);
            return;
        }
        lid(chest, true);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> lid(chest, false), 50L);
        chest.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, chest.getLocation().add(0.5, 1.2, 0.5), 25, 0.3, 0.4, 0.3, 0.2);
        String found = m.quests.treasure(p);
        m.quests.data(p).chestsOpened++;
        if (found != null) Msg.send(p, "&6&lTREASURE! &7The chest holds the " + Quests.name(found) + "&7! Return it to its Keeper.");
        // close the hole soon after the chest is emptied
        Open o = open.get(key(cover));
        if (o != null) open.put(key(cover), new Open(o.at, o.was, Math.min(o.until, System.currentTimeMillis() + 4000)));
    }

    private static void lid(Block chest, boolean openIt) {
        try {
            if (chest.getState() instanceof Lidded l) { if (openIt) l.open(); else l.close(); }
        } catch (RuntimeException ignored) { }
    }

    private void tick() {
        if (open.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var it = open.entrySet().iterator(); it.hasNext(); ) {
            Open o = it.next().getValue();
            if (now < o.until) continue;
            it.remove();
            reseal(o);
        }
    }

    private static void reseal(Open o) {
        Block b = o.at.getBlock();
        if (!b.getType().isAir()) return;
        for (Player p : b.getWorld().getPlayers()) {
            Location l = p.getLocation();
            if (l.getBlockX() == b.getX() && l.getBlockZ() == b.getZ() && l.getY() < b.getY() + 1.0 && l.getY() > b.getY() - 2)
                p.teleport(l.clone().add(0, b.getY() + 1.0 - l.getY(), 0));
        }
        b.setBlockData(o.was, false);
    }

    /** Shutdown: every dug tile goes back now, so no hole outlives the server. */
    void resealAll() {
        for (Open o : open.values()) reseal(o);
        open.clear();
    }

    int openTiles() { return open.size(); }
}
