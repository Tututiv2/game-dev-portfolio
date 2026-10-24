package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Goblin King's "avoid detection" (research 07: give the egg "while avoiding goblin detection").
 * Around the King's Burrow - its door yard (area {@code mobs.goblin_guard}) and the throne hall (zone
 * {@code goblin_burrow}) - a goblin that can SEE a player who is not sneaking, within a few blocks,
 * raises the alarm: the player is shoved back toward the door and King Grubnak refuses to trade with
 * anyone spotted in the last {@code hollows.king-spotted-seconds}. Sneaking (shift) gets you past.
 * Dial {@code hollows.king-stealth} turns the whole thing off.
 */
final class Stealth {

    private final HollowsModule m;
    /** player -> last time a guard spotted them. */
    private final Map<UUID, Long> spotted = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private Box yard, hall;
    private Layout cached;

    Stealth(HollowsModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 60L, 10L);
    }

    /** Seconds the King still refuses this player, 0 when free to trade. */
    int refusedFor(Player p) {
        if (!m.dials.kingStealth.get()) return 0;
        Long t = spotted.get(p.getUniqueId());
        if (t == null) return 0;
        long left = t + m.dials.kingSpottedSeconds.get() * 1000L - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    private boolean boxes() {
        Layout l = Sky.worlds().layout(Quests.CH);
        if (l == null) return false;
        if (l != cached) {
            cached = l;
            yard = l.area("mobs.goblin_guard");
            hall = null;
            for (Layout.Zone z : l.zones()) if (z.id().equals("goblin_burrow")) hall = z.box();
        }
        return yard != null || hall != null;
    }

    private boolean inside(Location at) {
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        if (hall != null && hall.contains(x, y, z)) return true;
        return yard != null && x >= yard.minX() - 3 && x <= yard.maxX() + 3 && z >= yard.minZ() - 3 && z <= yard.maxZ() + 3
                && y >= yard.minY() - 2 && y <= yard.maxY() + 4;
    }

    private void tick() {
        if (!m.dials.kingStealth.get() || !boxes()) return;
        double r = Math.max(1, m.dials.kingSightRange.get());
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isSneaking() || !Quests.CH.equals(Quests.island(p)) || !Sky.profiles().isReady(p)) continue;
            if (!inside(p.getLocation())) continue;
            LivingEntity seer = null;
            for (Entity e : p.getNearbyEntities(r, 3, r)) {
                if (!(e instanceof LivingEntity le) || !Sky.mobs().isSkyMob(le)) continue;
                MobDef d = Sky.mobs().defOf(le);
                if (d == null || !d.id.startsWith("ch_goblin") || d.id.equals(GoblinQueen.MOB) || d.id.equals(GoblinQueen.GUARD)) continue;
                if (le.hasLineOfSight(p)) { seer = le; break; }
            }
            if (seer == null) continue;
            Long before = spotted.put(p.getUniqueId(), now);
            if (before != null && now - before < 3000) continue;          // one shove per alarm, not every half second
            Vector push = p.getLocation().toVector().subtract(seer.getLocation().toVector()).setY(0);
            if (push.lengthSquared() < 1e-4) push = new Vector(1, 0, 0);
            p.setVelocity(push.normalize().multiply(0.9).setY(0.35));
            seer.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, seer.getLocation().add(0, 1.4, 0), 4, 0.3, 0.3, 0.3, 0);
            if (now - lastWarn.getOrDefault(p.getUniqueId(), 0L) > 4000) {
                lastWarn.put(p.getUniqueId(), now);
                Msg.send(p, "&c&lSPOTTED! &7A goblin guard raised the alarm. &eSneak &7(hold shift) to slip past the King's guards.");
                p.playSound(p.getLocation(), Sound.ENTITY_PILLAGER_CELEBRATE, 1f, 1.6f);
            }
        }
    }
}
