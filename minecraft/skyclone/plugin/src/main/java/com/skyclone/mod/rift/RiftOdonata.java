package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Odonata (wave 3; research 08 sec 5.4: "Wyld Woods ... Odonata bottles"). Little glowing dragonflies drift
 * over the Wildwood Thicket's two monster meadows. Hold an Empty Odonata Bottle (Glade Peddlers, a few Motes)
 * and right-click while looking at one within reach to catch it: the bottle becomes a Bottled Odonata (a
 * material: the Mote Monger buys it, and it is an ingredient of the Wildwood trophy recipe). A caught
 * odonata comes back somewhere else after {@code rift.odonata-respawn} seconds.
 *
 * <p>Odonata are particles, not entities (like Riddle Souls and mote orbs), so there is nothing for core's
 * mob handling, the void or chunk unloads to break. They are shared: whoever catches one first gets it.
 */
final class RiftOdonata implements Listener {

    static final String EMPTY = "EMPTY_ODONATA_BOTTLE";
    static final String BOTTLED = "BOTTLED_ODONATA";
    static final String[] AREAS = {"mobs.wyld_shy", "mobs.wyld_larva"};
    private static final Color WING = Color.fromRGB(80, 230, 220);

    private static final class Fly {
        Location home, at;
        Vector vel = new Vector();
        long respawnAt;
        double phase;
    }

    private final RiftModule m;
    private final List<Fly> flies = new ArrayList<>();
    private final Random rnd = new Random();
    private long tick;

    RiftOdonata(RiftModule m) { this.m = m; }

    /** Every 4 ticks: drift, draw, respawn. */
    void tick() {
        tick++;
        World w = Sky.worlds().world(RiftCatalog.ISLAND);
        if (w == null || w.getPlayers().isEmpty()) return;
        int want = m.dials.odonataCount.get();
        while (flies.size() < want) flies.add(new Fly());
        while (flies.size() > want) flies.remove(flies.size() - 1);
        long now = System.currentTimeMillis();
        for (Fly f : flies) {
            if (f.at == null) {
                if (now < f.respawnAt) continue;
                Location spot = randomSpot(w);
                if (spot == null) continue;
                f.home = spot; f.at = spot.clone(); f.vel = new Vector(); f.phase = rnd.nextDouble() * 6.28;
            }
            if (!w.isChunkLoaded(f.at.getBlockX() >> 4, f.at.getBlockZ() >> 4)) continue;
            // darting flight: mostly hovering, with sudden short dashes, tethered to its home
            if (rnd.nextInt(10) == 0) f.vel = new Vector(rnd.nextGaussian(), rnd.nextGaussian() * 0.3, rnd.nextGaussian()).multiply(0.35);
            f.vel.multiply(0.8);
            Vector pull = f.home.toVector().subtract(f.at.toVector());
            if (pull.lengthSquared() > 36) f.vel.add(pull.normalize().multiply(0.15));
            f.at.add(f.vel);
            f.phase += 0.9;
            double bob = Math.sin(f.phase) * 0.06;
            Location draw = f.at.clone().add(0, bob, 0);
            if (!m.dials.particles.get()) continue;
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(draw) > 32 * 32) continue;
                p.spawnParticle(Particle.DUST, draw, 2, 0.04, 0.02, 0.04, 0, new Particle.DustOptions(WING, 0.7f));
                if (tick % 3 == 0) p.spawnParticle(Particle.END_ROD, draw, 1, 0.05, 0.02, 0.05, 0);
            }
        }
    }

    /** A point 1.5-3 blocks over open ground in one of the Wildwood meadows, or null. */
    private Location randomSpot(World w) {
        Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
        if (l == null) return null;
        List<Box> boxes = new ArrayList<>();
        for (String a : AREAS) { Box b = l.area(a); if (b != null) boxes.add(b); }
        if (boxes.isEmpty()) return null;
        for (int tries = 0; tries < 8; tries++) {
            Box b = boxes.get(rnd.nextInt(boxes.size()));
            int x = b.minX() + rnd.nextInt(Math.max(1, b.maxX() - b.minX() + 1));
            int z = b.minZ() + rnd.nextInt(Math.max(1, b.maxZ() - b.minZ() + 1));
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            if (y < b.minY() - 4 || y > b.maxY() + 4) continue;   // a roof or a hole, not the meadow
            return new Location(w, x + 0.5, y + 1.5 + rnd.nextDouble() * 1.5, z + 0.5);
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack held = e.getItem();
        if (held == null || !EMPTY.equals(Sky.items().idOf(held))) return;
        Player p = e.getPlayer();
        if (m.session(p) == null) return;
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);   // never a water bottle
        Fly f = aimed(p);
        if (f == null) {
            Msg.sound(p, Sound.ITEM_BOTTLE_EMPTY, 1.6f);
            return;
        }
        if (Sky.items().remove(p, EMPTY, 1) < 1) return;
        Location at = f.at;
        f.at = null;
        f.respawnAt = System.currentTimeMillis() + m.dials.odonataRespawn.get() * 1000L;
        Sky.loot().give(p, BOTTLED, 1, LootSource.OTHER);
        RiftData d = m.data(p);
        if (d != null) {
            d.odonataCaught++;
            if (d.odonataCaught == 1) m.award(p, "quest:odonata", 3, "First Odonata");
        }
        p.spawnParticle(Particle.DUST, at, 20, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(WING, 1f));
        Msg.sound(p, Sound.ITEM_BOTTLE_FILL_DRAGONBREATH, 1.4f);
        Sky.vitals().flash(p, "&3You bottled an Odonata!" + (d == null ? "" : " &8(" + d.odonataCaught + " caught)"), 30);
    }

    /** The odonata nearest the player's line of sight within reach (4.5 blocks, 0.9 off the ray). */
    private Fly aimed(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Fly best = null;
        double bestOff = 0.9;
        for (Fly f : flies) {
            if (f.at == null || f.at.getWorld() != eye.getWorld()) continue;
            Vector to = f.at.toVector().subtract(eye.toVector());
            double along = to.dot(dir);
            if (along < 0 || along > 4.5) continue;
            double off = to.clone().subtract(dir.clone().multiply(along)).length();
            if (off <= bestOff) { bestOff = off; best = f; }
        }
        return best;
    }

    int alive() { int n = 0; for (Fly f : flies) if (f.at != null) n++; return n; }
}
