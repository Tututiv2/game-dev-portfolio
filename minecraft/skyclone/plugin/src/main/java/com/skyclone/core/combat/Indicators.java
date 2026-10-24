package com.skyclone.core.combat;

import com.skyclone.api.ui.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floating damage numbers as non-persistent TextDisplays. Hard cap on how many exist at once
 * (oldest is removed first), one repeating task drives every one of them: tick 1 starts a
 * client-interpolated rise, tick {@code combat.indicator-ticks} removes it. With 50 players
 * fighting this is at most {@code combat.indicator-max} entities, not one per hit.
 */
final class Indicators {

    private record Live(TextDisplay display, long born, Location to) {}

    private final Plugin plugin;
    private final ArrayDeque<Live> alive = new ArrayDeque<>();
    private final Set<UUID> ids = new HashSet<>();
    private long now;

    Indicators(Plugin plugin) { this.plugin = plugin; }

    /** Show {@code legacy} text at a location; {@code onlyFor} (nullable) makes it private to one player. */
    void show(Location at, String legacy, Player onlyFor) {
        World w = at.getWorld();
        if (w == null) return;
        int max = Math.max(1, CombatDials.INDICATOR_MAX.get());
        while (alive.size() >= max) kill(alive.pollFirst());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location spawn = at.clone().add(r.nextDouble(-0.45, 0.45), r.nextDouble(-0.15, 0.25), r.nextDouble(-0.45, 0.45));
        spawn.setYaw(0f);
        spawn.setPitch(0f);
        TextDisplay td;
        try {
            td = w.spawn(spawn, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.text(Text.c(legacy));
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(0.5f);
                d.getPersistentDataContainer().set(CombatKeys.INDICATOR, PersistentDataType.BYTE, (byte) 1);
                if (onlyFor != null) d.setVisibleByDefault(false);
            });
        } catch (RuntimeException e) {
            return;
        }
        if (onlyFor != null) onlyFor.showEntity(plugin, td);
        alive.addLast(new Live(td, now, spawn.clone().add(0, 0.9, 0)));
        ids.add(td.getUniqueId());
    }

    /** Every tick. */
    void tick() {
        now++;
        if (alive.isEmpty()) return;
        int life = Math.max(2, CombatDials.INDICATOR_TICKS.get());
        Iterator<Live> it = alive.iterator();
        while (it.hasNext()) {
            Live l = it.next();
            long age = now - l.born;
            if (!l.display.isValid() || age >= life) {
                it.remove();
                kill(l);
            } else if (age == 1) {
                try {
                    l.display.setTeleportDuration(Math.min(59, life - 1));
                    l.display.teleport(l.to);
                } catch (RuntimeException ignored) { }
            }
        }
    }

    boolean isTracked(Entity e) { return ids.contains(e.getUniqueId()); }

    int size() { return alive.size(); }

    private void kill(Live l) {
        if (l == null) return;
        ids.remove(l.display.getUniqueId());
        try { l.display.remove(); } catch (RuntimeException ignored) { }
    }

    void clear() {
        while (!alive.isEmpty()) kill(alive.pollFirst());
    }
}
