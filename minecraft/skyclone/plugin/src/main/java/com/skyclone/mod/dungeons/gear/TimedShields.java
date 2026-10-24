package com.skyclone.mod.dungeons.gear;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Absorption that runs out. The core's absorption pool never decays on its own (it is only consumed by damage), so a
 * shield granted with a plain {@code addAbsorption} stays until something hits it: the endgame playtest kept a Wither
 * Shield's 600 absorption (1,544/944 HP) for minutes and across warps out of the dungeon. Every dungeons shield goes
 * through {@link #grant} instead: when it expires, whatever is left of it is taken back (never more than it gave, never
 * more than the pool holds: damage eats absorption first, so a shield that already soaked hits returns nothing).
 * Quitting, or leaving a run ({@link #expireAll}), ends every pending shield at once.
 */
public final class TimedShields implements Listener {

    private TimedShields() {}

    public static final TimedShields INSTANCE = new TimedShields();

    private static final class Grant {
        double left;
        Grant(double amount) { this.left = amount; }
    }

    private static final Map<UUID, List<Grant>> PENDING = new HashMap<>();

    /** Give {@code amount} absorption that is taken back (what is left of it) after {@code ticks}. */
    public static void grant(Player p, double amount, long ticks) {
        if (p == null || !(amount > 0)) return;
        Sky.vitals().addAbsorption(p, amount);
        Grant g = new Grant(amount);
        PENDING.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(g);
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            List<Grant> l = PENDING.get(id);
            if (l == null || !l.remove(g)) return;          // already ended (quit / left the run)
            if (l.isEmpty()) PENDING.remove(id);
            Player q = Bukkit.getPlayer(id);
            if (q != null && q.isOnline()) take(q, g.left);
        }, Math.max(1, ticks));
    }

    /** End every pending shield of this player now (leaving a run, quitting). */
    public static void expireAll(Player p) {
        if (p == null) return;
        List<Grant> l = PENDING.remove(p.getUniqueId());
        if (l == null) return;
        double total = 0;
        for (Grant g : l) total += g.left;
        take(p, total);
    }

    private static void take(Player p, double amount) {
        double abs = Sky.vitals().absorption(p);
        double t = Math.min(abs, amount);
        if (t > 0) Sky.vitals().addAbsorption(p, -t);
    }

    /** LOWEST: before the core saves the player's vitals on quit. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) { expireAll(e.getPlayer()); }
}
