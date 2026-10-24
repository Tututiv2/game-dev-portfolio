package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The inner Jungle Temple (wave 5; reference: the Jungle Key opens the temple, inside is a parkour and the
 * Kalhuiki Door Guardian before the Amethyst crystal). The shrine on top takes the key and lets the player
 * down onto the start pad ({@code parkour.start}); a fall into the pit ({@code temple.pit}) puts them back on
 * the last checkpoint they touched; reaching the sanctum ({@code temple.sanctum}) wakes the Door Guardian for
 * them; the exit stone ({@code temple.exit}) carries them out to the court. All positions come from the layout,
 * so an island built before generator v3 (no inner temple) simply keeps the old shrine fight.
 */
final class TempleRun {

    private final HollowsModule m;
    /** player -> checkpoint reached this entry (0 = start pad). */
    private final Map<UUID, Integer> checkpoint = new HashMap<>();
    private final Map<UUID, Long> lastFallMsg = new HashMap<>();
    /** Players who beat the guardian since they last came in through the shrine (no "sleeps" nagging). */
    private final java.util.Set<UUID> finished = new java.util.HashSet<>();

    TempleRun(HollowsModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 4L);
    }

    /** True when the built caverns have the inner temple (generator v3+) and the dial wants it. */
    boolean available() {
        Layout l = Sky.worlds().layout(Quests.CH);
        return m.dials.templeParkour.get() && l != null && "1".equals(l.data().get("hollows.temple.inner"))
                && l.anchor("parkour.start") != null && l.area("temple.sanctum") != null;
    }

    /** Send a player who just used (or still has paid for) a key into the temple. */
    boolean enter(Player p) {
        Location start = Sky.worlds().anchor(Quests.CH, "parkour.start");
        if (start == null || start.getWorld() != p.getWorld()) return false;
        checkpoint.put(p.getUniqueId(), 0);
        finished.remove(p.getUniqueId());
        p.teleport(start);
        p.setFallDistance(0);
        Msg.send(p, "&5The shrine's floor slides open and you drop into the temple. &7Cross the pit to the sanctum; a fall puts you back on the last &echeckpoint plate&7.");
        Msg.sound(p, Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.6f);
        return true;
    }

    private static boolean in(Box b, Location l, int grow) {
        return b != null && l.getBlockX() >= b.minX() - grow && l.getBlockX() <= b.maxX() + grow && l.getBlockY() >= b.minY() - grow
                && l.getBlockY() <= b.maxY() + grow && l.getBlockZ() >= b.minZ() - grow && l.getBlockZ() <= b.maxZ() + grow;
    }

    private void tick() {
        Layout l = Sky.worlds().layout(Quests.CH);
        World w = Sky.worlds().world(Quests.CH);
        if (l == null || w == null || l.area("temple.hall") == null) return;
        Box hall = l.area("temple.hall"), pit = l.area("temple.pit"), sanctum = l.area("temple.sanctum"), exit = l.area("temple.exit");
        for (Player p : w.getPlayers()) {
            Location at = p.getLocation();
            UUID u = p.getUniqueId();
            if (in(hall, at, 0)) {
                if (in(pit, at, 0)) { fell(p); continue; }
                Layout.Anchor cp = l.anchor("parkour.cp.1");
                if (cp != null && checkpoint.getOrDefault(u, 0) < 1 && Math.abs(at.getX() - cp.x()) < 0.9 && Math.abs(at.getZ() - cp.z()) < 0.9
                        && Math.abs(at.getY() - cp.y()) < 1.2) {
                    checkpoint.put(u, 1);
                    Msg.send(p, "&aCheckpoint! &7A fall now brings you back here.");
                    Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.6f);
                }
                continue;
            }
            if (in(exit, at, 0)) {
                Location out = Sky.worlds().anchor(Quests.CH, "temple.exit_to");
                checkpoint.remove(u);
                if (out != null) {
                    p.teleport(out);
                    Msg.send(p, "&7The exit stone carries you back out to the temple court.");
                    Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f);
                }
                continue;
            }
            if (in(sanctum, at, 0)) sanctum(p);
        }
    }

    private void fell(Player p) {
        int cp = checkpoint.getOrDefault(p.getUniqueId(), 0);
        Location to = Sky.worlds().anchor(Quests.CH, cp >= 1 ? "parkour.cp.1" : "parkour.start");
        if (to == null) return;
        p.teleport(to);
        p.setFallDistance(0);
        long now = System.currentTimeMillis();
        if (now - lastFallMsg.getOrDefault(p.getUniqueId(), 0L) > 1500) {
            lastFallMsg.put(p.getUniqueId(), now);
            Msg.send(p, "&cYou fell into the pit! &7Back to the " + (cp >= 1 ? "checkpoint" : "start") + ".");
            Msg.sound(p, Sound.ENTITY_PLAYER_HURT, 0.8f);
        }
    }

    /** In the sanctum: wake this player's Door Guardian if they came in with a key and it is not up yet. */
    private void sanctum(Player p) {
        if (!Sky.profiles().isReady(p) || finished.contains(p.getUniqueId()) || m.quests.guardianAlive(p)) return;
        HollowsData d = m.quests.data(p);
        if (d.templePaidUntil <= System.currentTimeMillis()) {
            Sky.vitals().flash(p, "&5The Door Guardian sleeps. &7It wakes only for someone who used a Jungle Key.", 30);
            return;
        }
        Location g = Sky.worlds().anchor(Quests.CH, "temple.guardian");
        if (g == null) return;
        p.getWorld().spawnParticle(Particle.PORTAL, g.clone().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.4);
        m.quests.summonGuardian(p, g);
    }

    /** A finished run: forget the checkpoint. */
    void done(Player p) { checkpoint.remove(p.getUniqueId()); finished.add(p.getUniqueId()); }

    int checkpointOf(Player p) { return checkpoint.getOrDefault(p.getUniqueId(), -1); }
}
