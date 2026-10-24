package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Grappling Hook: cast into a block, reel in, fly toward the hook. 2 second cooldown (research 06).
 * Runs at LOWEST and cancels the event so the fishing module never treats it as a fishing rod.
 */
final class GrapplingHook implements Listener {

    private final Map<UUID, Long> next = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!"GRAPPLING_HOOK".equals(Traits.idOf(held))) return;
        PlayerFishEvent.State st = e.getState();
        if (st == PlayerFishEvent.State.FISHING) return;   // casting is fine
        FishHook hook = e.getHook();
        boolean anchored = st == PlayerFishEvent.State.IN_GROUND || hook.isOnGround()
                || !hook.getLocation().clone().subtract(0, 0.3, 0).getBlock().isPassable();
        if (st == PlayerFishEvent.State.REEL_IN || st == PlayerFishEvent.State.IN_GROUND || st == PlayerFishEvent.State.FAILED_ATTEMPT
                || st == PlayerFishEvent.State.CAUGHT_FISH || st == PlayerFishEvent.State.BITE) {
            e.setCancelled(true);
            hook.remove();
            if (!anchored) return;
            long now = Buffs.now();
            Long n = next.get(p.getUniqueId());
            if (n != null && n > now) {
                Msg.send(p, "&cWhoa! Slow down there!");
                return;
            }
            next.put(p.getUniqueId(), now + (long) (WeaponDials.GRAPPLE_COOLDOWN.get() * 1000));
            Location from = p.getLocation(), to = hook.getLocation();
            Vector v = to.toVector().subtract(from.toVector());
            double dist = v.length();
            if (dist < 0.5) return;
            v.normalize().multiply(Math.min(3.5, 0.4 + dist * 0.12) * WeaponDials.GRAPPLE_STRENGTH.get());   // TUNE: feel
            v.setY(Math.max(v.getY(), 0.35) + 0.2);
            p.setVelocity(v);
            p.setFallDistance(0);
            p.getWorld().playSound(from, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 1.2f);
            p.getWorld().playSound(from, Sound.ENTITY_BAT_TAKEOFF, 0.6f, 1.6f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { next.remove(e.getPlayer().getUniqueId()); }

    static boolean present() { return Sky.items().exists("GRAPPLING_HOOK"); }
}
