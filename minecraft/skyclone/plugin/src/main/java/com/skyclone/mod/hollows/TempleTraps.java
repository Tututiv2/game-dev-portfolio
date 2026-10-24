package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * The Jungle Temple's dart traps (reference: the temple's arrow traps on the way to the Kalhuiki door).
 * Dispensers set into the stair railings (positions in layout data {@code hollows.temple.traps}) loose a
 * dart across the stairs on a rhythm while anyone is climbing. A dart that lands deals
 * {@code hollows.temple-dart-damage} (defense applies) and is gone; nothing is placed in the world.
 * Watch the rhythm and run between volleys. Dial {@code hollows.temple-traps} turns them off.
 */
final class TempleTraps implements Listener {

    private record Trap(int x, int y, int z, int dir) {}

    private final HollowsModule m;
    private final NamespacedKey key = new NamespacedKey(Sky.plugin(), "hollows_dart");
    private Layout cached;
    private List<Trap> traps = List.of();
    private int[] stairs;
    private long beat;

    TempleTraps(HollowsModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 80L, 10L);
    }

    /** Pure parser for "x,y,z,dir;x,y,z,dir" (tests). */
    static List<int[]> parse(String s) {
        List<int[]> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            String[] p = part.split(",");
            if (p.length < 4) continue;
            try { out.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim())}); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private boolean load() {
        Layout l = Sky.worlds().layout(Quests.CH);
        if (l == null) return false;
        if (l != cached) {
            cached = l;
            List<Trap> t = new ArrayList<>();
            for (int[] a : parse(l.data().get("hollows.temple.traps"))) t.add(new Trap(a[0], a[1], a[2], a[3]));
            traps = t;
            stairs = null;
            String raw = l.data().get("hollows.temple.stairs");
            if (raw != null) {
                String[] p = raw.split(",");
                if (p.length == 6) {
                    stairs = new int[6];
                    for (int i = 0; i < 6; i++) stairs[i] = Integer.parseInt(p[i].trim());
                }
            }
        }
        return !traps.isEmpty() && stairs != null;
    }

    private boolean onStairs(Location at) {
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        return x >= stairs[0] - 2 && x <= stairs[3] + 2 && y >= stairs[1] && y <= stairs[4] + 2 && z >= stairs[2] - 2 && z <= stairs[5] + 3;
    }

    private void tick() {
        if (!m.dials.templeTraps.get() || !load()) return;
        World w = Sky.worlds().world(Quests.CH);
        if (w == null) return;
        boolean anyone = false;
        for (Player p : w.getPlayers()) if (onStairs(p.getLocation())) { anyone = true; break; }
        if (!anyone) return;
        beat++;
        int every = Math.max(1, m.dials.templeTrapBeats.get());
        if (beat % every != 0) return;
        // alternate halves so there is always a gap to run through
        int half = (int) ((beat / every) % 2);
        for (int i = 0; i < traps.size(); i++) {
            if (i % 2 != half) continue;
            Trap t = traps.get(i);
            Location from = new Location(w, t.x + 0.5 + t.dir * 0.75, t.y + 0.35, t.z + 0.5);
            Arrow a = w.spawnArrow(from, new Vector(t.dir, 0.02, 0), 1.4f, 0f);
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            a.setDamage(0);
            a.setPersistent(false);
            a.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            w.playSound(from, Sound.BLOCK_DISPENSER_LAUNCH, 0.7f, 1.4f);
            w.spawnParticle(Particle.SMOKE, from, 4, 0.05, 0.05, 0.05, 0.01);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (a.isValid()) a.remove(); }, 30L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow a) || !a.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        a.remove();
        if (e.getHitEntity() instanceof Player p && Sky.profiles().isReady(p)) {
            Sky.combat().damagePlayer(p, Math.max(0, m.dials.templeDartDamage.get()), null, DamageContext.Kind.PROJECTILE);
            p.playSound(p.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 1f);
        }
    }
}
